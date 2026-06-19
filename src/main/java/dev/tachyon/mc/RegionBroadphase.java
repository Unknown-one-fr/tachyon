package dev.tachyon.mc;

import com.google.common.collect.ImmutableList;
import dev.tachyon.core.ChunkKey;
import dev.tachyon.core.Region;
import dev.tachyon.soa.EntitySoAStore;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * The collision-broadphase half of the Mosaic R/W phase split, wired into real entity ticks.
 *
 * <p><b>Phase R (parallel, read-only):</b> {@link #build} snapshots every collidable entity in a
 * region's chunk footprint into the {@link EntitySoAStore} hot store — center + AABB half-extents,
 * the latter inflated by a per-entity movement bound so the dense {@code queryBox} scan is a
 * guaranteed <em>superset</em> of anything the entity could collide with this tick. This is the
 * expensive spatial read, and it runs off the serial path on the region's worker.
 *
 * <p><b>Phase W (serial, single-writer):</b> while the region's entities tick, the
 * {@code EntityCollisionBroadphaseMixin} redirect calls {@link #collisions}, which serves
 * {@code getEntityCollisions} from the cache-friendly SoA scan instead of a fresh
 * {@code EntitySectionStorage} walk per move. The result is <b>bit-identical</b> to vanilla: the
 * SoA superset is re-filtered against the <em>live</em> bounding boxes with the exact same
 * predicate ({@code != source}, {@code intersects(testArea.inflate(1e-7))},
 * {@code NO_SPECTATORS.and(canCollideWith)}), and collision clamping is order-independent.
 *
 * <p>Because regions are ≥ the interaction radius apart, a source's collision reach never leaves
 * its region footprint, so the footprint set is authoritative. The rare unsafe shape — a level
 * with EnderDragon parts (handled separately by vanilla) — disables the cache for that region.
 *
 * <p>The active broadphase is published per worker thread via {@link #ACTIVE}; null means "fall
 * back to vanilla". Gated behind {@code mosaic.entityBroadphase} (default off) — experimental.
 */
public final class RegionBroadphase {

    /** The broadphase bound to the current worker thread, or null to use vanilla. */
    public static final ThreadLocal<RegionBroadphase> ACTIVE = new ThreadLocal<>();

    /** Baseline stored-extent inflation (blocks); exceeds any normal per-tick entity movement. */
    private static final double BASE_MARGIN = 16.0;

    // A/B counters (workers fill these; keep lock-free).
    private static final AtomicLong QUERIES = new AtomicLong();
    private static final AtomicLong CANDIDATES = new AtomicLong();
    private static final AtomicLong NANOS = new AtomicLong();

    private final EntitySoAStore soa;
    private final Map<Integer, Entity> byId;
    private int[] scratch;

    private RegionBroadphase(EntitySoAStore soa, Map<Integer, Entity> byId) {
        this.soa = soa;
        this.byId = byId;
        this.scratch = new int[Math.max(16, soa.size())];
    }

    /**
     * Phase R: build the broadphase for one region from the authoritative set of collidable
     * entities in its chunk footprint. Returns null when the cache cannot safely apply (no
     * footprint, or dragon parts present), signalling the caller to tick with vanilla collisions.
     */
    public static RegionBroadphase build(Level level, Region region) {
        if (!level.dragonParts().isEmpty()) {
            return null; // EnderDragon parts take a separate collision path; don't risk it.
        }
        long[] chunks = region.chunks;
        if (chunks.length == 0) {
            return null;
        }
        int minCx = Integer.MAX_VALUE, minCz = Integer.MAX_VALUE, maxCx = Integer.MIN_VALUE, maxCz = Integer.MIN_VALUE;
        for (long c : chunks) {
            int cx = ChunkKey.x(c), cz = ChunkKey.z(c);
            if (cx < minCx) minCx = cx;
            if (cx > maxCx) maxCx = cx;
            if (cz < minCz) minCz = cz;
            if (cz > maxCz) maxCz = cz;
        }
        double minY = level.getMinY();
        double maxY = minY + level.getHeight();
        AABB footprint = new AABB(minCx << 4, minY, minCz << 4,
                (maxCx + 1) << 4, maxY, (maxCz + 1) << 4);

        List<Entity> entities = level.getEntities((Entity) null, footprint, e -> true);
        EntitySoAStore soa = new EntitySoAStore(Math.max(16, entities.size()));
        Map<Integer, Entity> byId = new HashMap<>(entities.size() * 2);
        for (Entity e : entities) {
            AABB box = e.getBoundingBox();
            double cx = (box.minX + box.maxX) * 0.5;
            double cy = (box.minY + box.maxY) * 0.5;
            double cz = (box.minZ + box.maxZ) * 0.5;
            double margin = Math.max(BASE_MARGIN, e.getDeltaMovement().length() + 1.0);
            float hx = (float) ((box.maxX - box.minX) * 0.5 + margin);
            float hy = (float) ((box.maxY - box.minY) * 0.5 + margin);
            float hz = (float) ((box.maxZ - box.minZ) * 0.5 + margin);
            soa.put(e.getId(), 0, cx, cy, cz, 0, 0, 0, hx, hy, hz, 0L);
            byId.put(e.getId(), e);
        }
        return new RegionBroadphase(soa, byId);
    }

    /**
     * Phase W: an exact, drop-in replacement for {@code EntityGetter.getEntityCollisions(source,
     * testArea)} backed by the SoA broadphase. Bit-identical to vanilla.
     */
    public List<VoxelShape> collisions(Entity source, AABB testArea) {
        long t0 = System.nanoTime();
        QUERIES.incrementAndGet();
        if (testArea.getSize() < 1.0E-7) {
            return List.of();
        }
        AABB area = testArea.inflate(1.0E-7);
        Predicate<Entity> canCollide = source == null
                ? EntitySelector.CAN_BE_COLLIDED_WITH
                : EntitySelector.NO_SPECTATORS.and(source::canCollideWith);

        int n = soa.queryBox(area.minX, area.minY, area.minZ, area.maxX, area.maxY, area.maxZ, scratch);
        if (n == scratch.length && n < soa.size()) {
            // scratch was saturated; widen and retry so the candidate set stays a true superset.
            scratch = new int[soa.size()];
            n = soa.queryBox(area.minX, area.minY, area.minZ, area.maxX, area.maxY, area.maxZ, scratch);
        }
        CANDIDATES.addAndGet(n);
        if (n == 0) {
            NANOS.addAndGet(System.nanoTime() - t0);
            return List.of();
        }
        ImmutableList.Builder<VoxelShape> shapes = ImmutableList.builder();
        for (int k = 0; k < n; k++) {
            Entity e = byId.get(scratch[k]);
            if (e == null || e == source) {
                continue;
            }
            AABB box = e.getBoundingBox();
            if (box.intersects(area) && canCollide.test(e)) {
                shapes.add(Shapes.create(box));
            }
        }
        NANOS.addAndGet(System.nanoTime() - t0);
        return shapes.build();
    }

    /** One-line A/B readout, surfaced via {@code /tachyon perf}. */
    public static String stats() {
        long q = QUERIES.get(), c = CANDIDATES.get(), nanos = NANOS.get();
        if (q == 0) {
            return "entity-broadphase: idle (no SoA collision queries yet)";
        }
        return String.format(Locale.ROOT,
                "entity-broadphase: queries=%d avgCandidates=%.1f total=%.1fms",
                q, c / (double) q, nanos / 1e6);
    }

    public static void resetStats() {
        QUERIES.set(0);
        CANDIDATES.set(0);
        NANOS.set(0);
    }
}

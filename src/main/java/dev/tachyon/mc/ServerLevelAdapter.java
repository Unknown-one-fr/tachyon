package dev.tachyon.mc;

import dev.tachyon.core.ChunkKey;
import dev.tachyon.core.Region;
import dev.tachyon.core.RegionGraph;
import dev.tachyon.engine.RegionizedTickWorld;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Bridges a live {@link ServerLevel} to the Mosaic engine's {@link RegionizedTickWorld}
 * contract — the real-Minecraft counterpart of the synthetic {@code EntityWorld}.
 *
 * <p>{@link #partition()} snapshots the level's entities, groups them into regions by the
 * chunk they occupy (via {@link RegionGraph}), and is safe/read-only — used by measure
 * mode to report how parallelizable the live world is. The tick methods
 * ({@link #computeRegion}/{@link #applyIntent}) run the entities' real vanilla tick and
 * are only invoked by the experimental parallel routing (see the tick mixin). Real
 * entities use a full-tick model rather than the synthetic R/W split, so {@code applyIntent}
 * is a no-op.
 */
public final class ServerLevelAdapter implements RegionizedTickWorld {
    private final ServerLevel level;
    private final RegionGraph graph;

    private Entity[] entities = new Entity[0];
    private int entityCount;
    private List<Region> regions = List.of();
    private int[][] members = new int[0][];

    public ServerLevelAdapter(ServerLevel level, int interactionRadiusChunks) {
        this.level = level;
        this.graph = new RegionGraph(interactionRadiusChunks);
    }

    /** Snapshot non-passenger entities and partition into regions by occupied chunk. Returns count. */
    public int partition() {
        ArrayList<Entity> list = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (e != null && !e.isRemoved() && !e.isPassenger()) {
                list.add(e);
            }
        }
        int n = list.size();
        entities = list.toArray(new Entity[0]);
        entityCount = n;

        long[] chunkKeyOf = new long[n];
        HashSet<Long> occupied = new HashSet<>();
        for (int i = 0; i < n; i++) {
            Entity e = entities[i];
            int cx = (int) Math.floor(e.getX()) >> 4;
            int cz = (int) Math.floor(e.getZ()) >> 4;
            long key = ChunkKey.of(cx, cz);
            chunkKeyOf[i] = key;
            occupied.add(key);
        }

        regions = graph.partition(occupied);

        HashMap<Long, Integer> regionOfChunk = new HashMap<>();
        for (Region r : regions) {
            for (long c : r.chunks) regionOfChunk.put(c, r.id);
        }

        int[] counts = new int[regions.size()];
        int[] regionOfEntity = new int[n];
        for (int i = 0; i < n; i++) {
            int rid = regionOfChunk.get(chunkKeyOf[i]);
            regionOfEntity[i] = rid;
            counts[rid]++;
        }
        members = new int[regions.size()][];
        for (int r = 0; r < members.length; r++) members[r] = new int[counts[r]];
        int[] fill = new int[regions.size()];
        for (int i = 0; i < n; i++) {
            int rid = regionOfEntity[i];
            members[rid][fill[rid]++] = i;
        }
        return n;
    }

    public int entityCount() {
        return entityCount;
    }

    public int maxRegionEntities() {
        int max = 0;
        for (int[] m : members) max = Math.max(max, m.length);
        return max;
    }

    // --- RegionizedTickWorld (used only by experimental parallel routing) ---

    @Override
    public List<Region> regions() {
        return regions;
    }

    @Override
    public int[] members(int regionId) {
        return members[regionId];
    }

    @Override
    public void beginTick() {
        partition();
    }

    @Override
    public void computeRegion(int regionId) {
        for (int slot : members[regionId]) tickEntity(entities[slot]);
    }

    @Override
    public void computeIntent(int slot) {
        tickEntity(entities[slot]);
    }

    @Override
    public void applyIntent(int slot) {
        // Real entities run a full vanilla tick in computeRegion; nothing to apply separately.
    }

    private void tickEntity(Entity e) {
        if (e == null || e.isRemoved() || e.isPassenger()) return;
        level.tickNonPassenger(e);
    }
}

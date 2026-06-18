package dev.tachyon.mc;

import dev.tachyon.TachyonMod;
import dev.tachyon.core.ChunkKey;
import dev.tachyon.core.Region;
import dev.tachyon.core.RegionGraph;
import dev.tachyon.core.RegionScheduler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * EXPERIMENTAL parallel entity ticking on real Minecraft (the Mosaic takeover).
 *
 * <p>Groups the level's ticking entities into non-interacting regions ({@link RegionGraph})
 * and ticks each region on the work-stealing {@link RegionScheduler} — serial within a
 * region, parallel across regions. Crucially it reuses vanilla's own per-entity consumer
 * (the {@code guardEntityTick} wrapper), so each entity ticks exactly as vanilla would;
 * only the cross-region dispatch is parallelized.
 *
 * <p><b>Honest caveat:</b> vanilla entity/world code assumes single-threaded access. The
 * region interaction-radius keeps spatially-separated entities apart, but shared global
 * structures (block-event queues, neighbour updates, the entity lookup, scheduled ticks)
 * are NOT thread-safe, so this WILL race/crash under real load. Gated behind
 * {@code mosaic.enabled}; for the max-perf playground only.
 */
public final class ParallelEntityTicker {
    private static volatile ParallelEntityTicker instance;

    private final RegionGraph graph;
    private final RegionScheduler scheduler;

    private ParallelEntityTicker(int radius, int parallelism) {
        this.graph = new RegionGraph(radius);
        this.scheduler = new RegionScheduler(parallelism);
    }

    public static ParallelEntityTicker get() {
        ParallelEntityTicker t = instance;
        if (t == null) {
            synchronized (ParallelEntityTicker.class) {
                t = instance;
                if (t == null) {
                    int radius = TachyonMod.config != null ? TachyonMod.config.interactionRadiusChunks : 2;
                    int par = TachyonMod.config != null ? TachyonMod.config.parallelism : 4;
                    instance = t = new ParallelEntityTicker(radius, par);
                }
            }
        }
        return t;
    }

    /** Partition the ticking entities into regions and tick them in parallel via {@code tickFn}. */
    public void tick(EntityTickList list, Consumer<Entity> tickFn) {
        final ArrayList<Entity> entities = new ArrayList<>();
        list.forEach(entities::add);                       // collect on the calling (main) thread
        final int n = entities.size();
        if (n == 0) {
            return;
        }

        final long[] chunkKeyOf = new long[n];
        final HashSet<Long> occupied = new HashSet<>();
        for (int i = 0; i < n; i++) {
            Entity e = entities.get(i);
            int cx = (int) Math.floor(e.getX()) >> 4;
            int cz = (int) Math.floor(e.getZ()) >> 4;
            long key = ChunkKey.of(cx, cz);
            chunkKeyOf[i] = key;
            occupied.add(key);
        }

        final List<Region> regions = graph.partition(occupied);
        final HashMap<Long, Integer> regionOfChunk = new HashMap<>();
        for (Region r : regions) {
            for (long c : r.chunks) regionOfChunk.put(c, r.id);
        }

        final ArrayList<ArrayList<Entity>> buckets = new ArrayList<>(regions.size());
        for (int r = 0; r < regions.size(); r++) buckets.add(new ArrayList<>());
        for (int i = 0; i < n; i++) {
            buckets.get(regionOfChunk.get(chunkKeyOf[i])).add(entities.get(i));
        }

        // parallel across regions, serial within (single-writer per region)
        scheduler.tick(regions, region -> {
            for (Entity e : buckets.get(region.id)) {
                if (!e.isRemoved()) tickFn.accept(e);
            }
        });

        TachyonMod.engine.metrics.recordPhases(
                scheduler.lastParallelNanos(), scheduler.lastBarrierNanos(), 0L, regions.size());
    }
}

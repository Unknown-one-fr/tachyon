package dev.tachyon.mc;

import dev.tachyon.TachyonMod;
import dev.tachyon.core.ChunkKey;
import dev.tachyon.core.Region;
import dev.tachyon.core.RegionGraph;
import dev.tachyon.core.RegionScheduler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * EXPERIMENTAL intra-level regionization: tick a single level's entities in parallel across
 * interaction-radius-separated regions, on top of the multi-owner chunk cache
 * ({@code ServerChunkCacheMixin}).
 *
 * <p>Entities are partitioned by occupied chunk into non-interacting regions ({@link RegionGraph}).
 * Each region ticks on its own worker, which registers itself as a chunk-cache owner so its chunk
 * access runs inline. Because regions are ≥ the interaction radius apart, a region's entities only
 * touch chunks inside that region — so no two workers touch the same chunk (single-writer per
 * region). Cross-region effects (an entity that reaches into another region, or moves across the
 * boundary) are the unsafe edge this experiment is built to expose.
 *
 * <p>Reuses vanilla's own per-entity consumer (the {@code guardEntityTick} wrapper), so each entity
 * ticks exactly as vanilla would; only the dispatch is parallel. Gated behind {@code mosaic.intraLevel}.
 */
public final class IntraLevelEntityTicker {
    private static volatile IntraLevelEntityTicker instance;

    private final RegionGraph graph;
    private final RegionScheduler scheduler;

    private IntraLevelEntityTicker(int radius, int parallelism) {
        this.graph = new RegionGraph(radius);
        this.scheduler = new RegionScheduler(parallelism);
    }

    public static IntraLevelEntityTicker get() {
        IntraLevelEntityTicker t = instance;
        if (t == null) {
            synchronized (IntraLevelEntityTicker.class) {
                t = instance;
                if (t == null) {
                    int radius = TachyonMod.config != null ? TachyonMod.config.interactionRadiusChunks : 2;
                    int par = TachyonMod.config != null ? TachyonMod.config.parallelism : 4;
                    instance = t = new IntraLevelEntityTicker(radius, par);
                }
            }
        }
        return t;
    }

    public void tick(ServerLevel level, EntityTickList list, Consumer<Entity> tickFn) {
        final ArrayList<Entity> entities = new ArrayList<>();
        list.forEach(entities::add);
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
        if (regions.size() <= 1) {
            // One blob — no intra-level parallelism to be had; tick inline (still on the level thread).
            for (Entity e : entities) {
                if (!e.isRemoved()) tickFn.accept(e);
            }
            return;
        }

        final HashMap<Long, Integer> regionOfChunk = new HashMap<>();
        for (Region r : regions) {
            for (long c : r.chunks) regionOfChunk.put(c, r.id);
        }
        final ArrayList<ArrayList<Entity>> buckets = new ArrayList<>(regions.size());
        for (int r = 0; r < regions.size(); r++) buckets.add(new ArrayList<>());
        for (int i = 0; i < n; i++) {
            buckets.get(regionOfChunk.get(chunkKeyOf[i])).add(entities.get(i));
        }

        final boolean broadphase = TachyonMod.config != null && TachyonMod.config.entityBroadphase;
        final RegionOwnable cache = (RegionOwnable) (Object) level.getChunkSource();
        try {
            scheduler.tick(regions, region -> {
                Thread worker = Thread.currentThread();
                cache.tachyon$addOwner(worker);   // claim inline chunk access for this region
                // Phase R: build the SoA collision broadphase for this region (read-only) so the
                // per-entity getEntityCollisions during Phase W is served from the hot store.
                RegionBroadphase bp = broadphase ? RegionBroadphase.build(level, region) : null;
                if (bp != null) RegionBroadphase.ACTIVE.set(bp);
                try {
                    for (Entity e : buckets.get(region.id)) {
                        if (!e.isRemoved()) tickFn.accept(e);
                    }
                } finally {
                    RegionBroadphase.ACTIVE.remove();
                    cache.tachyon$removeOwner(worker);
                }
            });
        } finally {
            cache.tachyon$clearOwners();
        }

        // Tick any players deferred off the workers — only drains if we're the true main thread
        // (intra-only mode runs here on the server thread; in combined mode the per-level driver drains).
        ParallelLevelTicker.drainDeferredPlayersIfMain();

        if (TachyonMod.engine != null) {
            TachyonMod.engine.metrics.recordPhases(
                    scheduler.lastParallelNanos(), scheduler.lastBarrierNanos(), 0L, regions.size());
        }
    }
}

package dev.tachyon;

import dev.tachyon.config.TachyonConfig;
import dev.tachyon.core.ChunkKey;
import dev.tachyon.core.Region;
import dev.tachyon.core.RegionContext;
import dev.tachyon.core.RegionGraph;
import dev.tachyon.core.RegionScheduler;
import dev.tachyon.ffm.ScratchArenaPool;
import dev.tachyon.metrics.TickMetrics;
import dev.tachyon.simd.NoiseKernel;
import dev.tachyon.soa.EntitySoAStore;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the engine's long-lived subsystems and wires them together. MC-agnostic: it
 * depends only on the Tachyon core + JDK, so it compiles and runs (including the
 * self-test) regardless of Minecraft-internal mapping drift.
 */
public final class TachyonEngine {
    public final TachyonConfig config;
    public final TickMetrics metrics;
    public final NoiseKernel noise;
    public final ScratchArenaPool scratch;
    public final RegionGraph regionGraph;

    private volatile RegionScheduler scheduler;

    public TachyonEngine(TachyonConfig config) {
        this.config = config;
        this.metrics = new TickMetrics(config.metricsWindow);
        this.noise = config.simdNoise ? NoiseKernel.create() : null;
        this.scratch = config.ffmScratch ? new ScratchArenaPool(config.scratchBytesPerThread) : null;
        this.regionGraph = new RegionGraph(config.interactionRadiusChunks);
        if (config.mosaicEnabled) {
            this.scheduler = new RegionScheduler(config.parallelism);
        }
    }

    public RegionScheduler scheduler() {
        return scheduler;
    }

    public void shutdown() {
        RegionScheduler s = scheduler;
        if (s != null) s.close();
    }

    /**
     * Exercise every subsystem once on the live runtime and return a human-readable
     * report. This is how we confirm the JDK 25 features (Vector API, FFM, scoped
     * values, work-stealing parallelism) actually function in this server's JVM
     * before trusting them on the hot path.
     */
    public String selfTest() {
        StringBuilder sb = new StringBuilder("Tachyon self-test:\n");

        // 1) Region partitioning: two well-separated chunk clusters -> two regions.
        Set<Long> loaded = new HashSet<>();
        for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) loaded.add(ChunkKey.of(x, z));
        for (int x = 40; x < 44; x++) for (int z = 40; z < 44; z++) loaded.add(ChunkKey.of(x, z));
        List<Region> regions = regionGraph.partition(loaded);
        sb.append("  [region-graph] ").append(loaded.size()).append(" chunks -> ")
          .append(regions.size()).append(" regions (expected 2)\n");

        // 2) Parallel scheduling across many regions; record distinct worker threads.
        Set<Long> many = new HashSet<>();
        for (int c = 0; c < 8; c++) {
            int base = c * 40;
            for (int x = base; x < base + 3; x++) for (int z = 0; z < 3; z++) many.add(ChunkKey.of(x, z));
        }
        List<Region> manyRegions = regionGraph.partition(many);
        RegionScheduler sched = scheduler != null ? scheduler : new RegionScheduler(config.parallelism);
        AtomicInteger ticked = new AtomicInteger();
        Set<String> workers = ConcurrentHashMap.newKeySet();
        sched.tick(manyRegions, region -> {
            // proves the scoped-value region context is bound on the worker thread
            if (RegionContext.current() == region) ticked.incrementAndGet();
            workers.add(Thread.currentThread().getName());
        });
        sb.append("  [scheduler] ticked ").append(ticked.get()).append('/').append(manyRegions.size())
          .append(" regions across ").append(workers.size()).append(" worker threads\n");
        if (scheduler == null) sched.close();

        // 3) SoA store: insert, query box, remove.
        EntitySoAStore soa = new EntitySoAStore(256);
        for (int i = 0; i < 100; i++) soa.put(i, i % 5, i, 64, i, 0, 0, 0, 0.3f, 0.9f, 0.3f, 0L);
        int[] hits = new int[128];
        // entities lie on the x==z diagonal at y=64, so the box [10,20]^2 catches i=10..20.
        int found = soa.queryBox(10, 0, 10, 20, 128, 20, hits);
        soa.remove(50);
        sb.append("  [soa] size=").append(soa.size()).append(" (after 1 removal) boxQuery=")
          .append(found).append(" (expected 11)\n");

        // 4) FFM off-heap scratch: write/read through a confined arena slice.
        if (scratch != null) {
            ScratchArenaPool.Scratch s = scratch.local();
            s.reset();
            MemorySegment seg = s.alloc(1024, 8);
            String ffm;
            if (seg != null) {
                seg.set(ValueLayout.JAVA_LONG, 0, 0xCAFEBABEL);
                long back = seg.get(ValueLayout.JAVA_LONG, 0);
                ffm = "ok (used=" + s.used() + "B, roundtrip=" + (back == 0xCAFEBABEL) + ")";
            } else {
                ffm = "arena exhausted";
            }
            sb.append("  [ffm] ").append(ffm).append('\n');
        } else {
            sb.append("  [ffm] disabled\n");
        }

        // 5) SIMD noise kernel.
        if (noise != null) {
            float[] out = new float[64];
            noise.fill1D(out, 0f, 0.05f, 1.0f, 4, 0.5f);
            sb.append("  [simd] backend=").append(noise.backend())
              .append(" sample[0..2]=").append(String.format("%.3f,%.3f,%.3f", out[0], out[1], out[2])).append('\n');
        } else {
            sb.append("  [simd] disabled\n");
        }

        return sb.toString();
    }
}

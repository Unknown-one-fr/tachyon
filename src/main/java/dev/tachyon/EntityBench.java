package dev.tachyon;

import dev.tachyon.core.RegionScheduler;
import dev.tachyon.engine.EntityWorld;
import dev.tachyon.engine.FlockKernel;
import dev.tachyon.engine.MosaicTicker;

/**
 * Two-axis throughput of the Mosaic entity engine: regionized parallelism (threads) and
 * SIMD flocking (Vector API). Directly answers "Minecraft is single-threaded; how much
 * does this actually buy?" — across both cores and SIMD lanes.
 */
public final class EntityBench {
    public static void main(String[] args) {
        final int n = 30_000;
        final double world = 1500.0;
        final double region = 250.0;   // ~36 regions
        final int warmup = 40;
        final int iters = 80;

        EntityWorld base = new EntityWorld(n, world, region, 42L);
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
        RegionScheduler scheduler = new RegionScheduler(threads);
        MosaicTicker ticker = new MosaicTicker();

        FlockKernel scalar = FlockKernel.scalar();
        FlockKernel simd = FlockKernel.create();

        System.out.printf("EntityBench: n=%d regions=%d threads=%d simd=%s (%s)%n",
                n, base.regionCount(), threads, FlockKernel.simdAvailable(), simd.name());

        double scalarSerial = run(base.copy(), scalar, ticker, scheduler, false, warmup, iters);
        double scalarPar = run(base.copy(), scalar, ticker, scheduler, true, warmup, iters);
        double simdSerial = run(base.copy(), simd, ticker, scheduler, false, warmup, iters);
        double simdPar = run(base.copy(), simd, ticker, scheduler, true, warmup, iters);

        System.out.printf("  scalar : serial=%6.2f ms  parallel=%6.2f ms  (%.2fx threads)%n",
                scalarSerial, scalarPar, scalarSerial / scalarPar);
        System.out.printf("  %-6s : serial=%6.2f ms  parallel=%6.2f ms  (%.2fx threads)%n",
                simd.name().startsWith("simd") ? "simd" : "scalar2", simdSerial, simdPar, simdSerial / simdPar);
        System.out.printf("  SIMD flock speedup (single-thread): %.2fx%n", scalarSerial / simdSerial);
        System.out.printf("  combined best vs scalar-serial baseline: %.2fx%n", scalarSerial / simdPar);

        scheduler.close();
    }

    private static double run(EntityWorld w, FlockKernel kernel, MosaicTicker ticker,
                              RegionScheduler scheduler, boolean parallel, int warmup, int iters) {
        w.setKernel(kernel);
        for (int i = 0; i < warmup; i++) {
            if (parallel) ticker.tickParallel(w, scheduler);
            else ticker.tickSerial(w);
        }
        long ns = 0;
        for (int i = 0; i < iters; i++) {
            long t = System.nanoTime();
            if (parallel) ticker.tickParallel(w, scheduler);
            else ticker.tickSerial(w);
            ns += System.nanoTime() - t;
        }
        return ns / 1e6 / iters;
    }
}

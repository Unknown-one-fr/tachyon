package dev.tachyon;

import dev.tachyon.ffm.ScratchArenaPool;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

/**
 * Demonstrates the point of the FFM scratch arenas: large per-tick scratch buffers
 * (think pathfinding grids / packet assembly) allocated off-heap with bump+reset
 * generate <b>zero</b> GC, whereas allocating the same buffer on the heap each tick
 * churns the collector. Same work, same checksum — the difference is GC pressure.
 */
public final class FfmScratchBench {
    private static final int GRID = 1 << 16;   // 65,536 doubles = 512 KiB "grid" per tick
    private static final int ITERS = 5000;
    private static final ScratchArenaPool POOL = new ScratchArenaPool(GRID * 8L + 64);

    public static void main(String[] args) {
        System.out.println("FfmScratchBench: " + GRID + " doubles scratch x " + ITERS + " iters ("
                + (GRID * 8L * ITERS >> 20) + " MiB of would-be allocation)");

        heap(300);
        ffm(300); // warmup

        long gc0 = gcCount();
        long t0 = System.nanoTime();
        double s1 = heap(ITERS);
        double heapMs = (System.nanoTime() - t0) / 1e6 / ITERS;
        long heapGc = gcCount() - gc0;

        long gc1 = gcCount();
        long u0 = System.nanoTime();
        double s2 = ffm(ITERS);
        double ffmMs = (System.nanoTime() - u0) / 1e6 / ITERS;
        long ffmGc = gcCount() - gc1;

        System.out.printf("  heap new[] : %.4f ms/iter   GC collections=%d   (checksum %.1f)%n", heapMs, heapGc, s1);
        System.out.printf("  ffm reset  : %.4f ms/iter   GC collections=%d   (checksum %.1f)%n", ffmMs, ffmGc, s2);
        System.out.printf("  -> FFM avoided %d garbage collections this run%n", heapGc - ffmGc);
    }

    private static double heap(int iters) {
        double sum = 0;
        for (int i = 0; i < iters; i++) {
            double[] grid = new double[GRID];
            for (int k = 0; k < GRID; k++) grid[k] = k * 0.5 + i;
            sum += grid[i % GRID];
        }
        return sum;
    }

    private static double ffm(int iters) {
        double sum = 0;
        ScratchArenaPool.Scratch scratch = POOL.local();
        for (int i = 0; i < iters; i++) {
            scratch.reset();
            MemorySegment grid = scratch.alloc(GRID * 8L, 8);
            for (int k = 0; k < GRID; k++) grid.set(ValueLayout.JAVA_DOUBLE, k * 8L, k * 0.5 + i);
            sum += grid.get(ValueLayout.JAVA_DOUBLE, (long) (i % GRID) * 8L);
        }
        return sum;
    }

    private static long gcCount() {
        long c = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            c += Math.max(0, b.getCollectionCount());
        }
        return c;
    }
}

package dev.tachyon;

import dev.tachyon.soa.EntitySoAStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Honest A/B for the collision-broadphase R/W split: the SoA flat-scan {@code queryBox} (cache-linear
 * but O(N) per query) vs a section-style uniform grid (O(local) per query, like vanilla's
 * {@code EntitySectionStorage}). Run across entity densities to find the crossover — i.e. whether
 * replacing vanilla's spatial index with the SoA scan actually helps, and where it stops helping.
 *
 * <p>MC-independent. Each entity issues one swept-AABB neighbour query (the per-move broadphase);
 * we time the whole region doing that once.
 */
public final class BroadphaseBench {
    private static final int ITERS = 200;
    private static final int WARMUP = 60;
    private static final double WORLD = 256.0;     // region span (blocks)
    private static final double HALF = 0.3;        // entity AABB half-extent
    private static final double REACH = 2.0;       // swept-AABB query radius around the entity

    public static void main(String[] args) {
        int[] counts = {32, 64, 128, 256, 512, 1024, 2048};
        System.out.printf("BroadphaseBench: world=%.0f reach=%.1f  (per-region all-entities broadphase pass)%n", WORLD, REACH);
        System.out.printf("  %6s | %10s | %10s | %8s%n", "N", "SoA ms", "grid ms", "SoA/grid");
        for (int n : counts) {
            bench(n);
        }
    }

    private static void bench(int n) {
        Random rng = new Random(99L + n);
        double[] x = new double[n], y = new double[n], z = new double[n];
        EntitySoAStore soa = new EntitySoAStore(n);
        Grid grid = new Grid();
        for (int i = 0; i < n; i++) {
            x[i] = rng.nextDouble() * WORLD;
            y[i] = rng.nextDouble() * 64;
            z[i] = rng.nextDouble() * WORLD;
            soa.put(i, 0, x[i], y[i], z[i], 0, 0, 0, (float) HALF, (float) HALF, (float) HALF, 0L);
            grid.put(i, x[i], y[i], z[i]);
        }

        int[] out = new int[n];
        for (int w = 0; w < WARMUP; w++) {
            soaPass(soa, x, y, z, n, out);
            gridPass(grid, x, y, z, n);
        }
        double soaMs = time(() -> soaPass(soa, x, y, z, n, out));
        double gridMs = time(() -> gridPass(grid, x, y, z, n));
        System.out.printf("  %6d | %10.3f | %10.3f | %8.2f%n", n, soaMs, gridMs, soaMs / gridMs);
    }

    /** Each entity does one SoA flat-scan swept-AABB query; sum hit counts so nothing is dead-code-eliminated. */
    private static long soaPass(EntitySoAStore soa, double[] x, double[] y, double[] z, int n, int[] out) {
        long hits = 0;
        for (int i = 0; i < n; i++) {
            hits += soa.queryBox(x[i] - REACH, y[i] - REACH, z[i] - REACH,
                    x[i] + REACH, y[i] + REACH, z[i] + REACH, out);
        }
        return hits;
    }

    private static long gridPass(Grid grid, double[] x, double[] y, double[] z, int n) {
        long hits = 0;
        for (int i = 0; i < n; i++) {
            hits += grid.query(x[i] - REACH, y[i] - REACH, z[i] - REACH, x[i] + REACH, y[i] + REACH, z[i] + REACH);
        }
        return hits;
    }

    private static double time(java.util.function.LongSupplier r) {
        long t0 = System.nanoTime();
        long sink = 0;
        for (int i = 0; i < ITERS; i++) sink += r.getAsLong();
        if (sink == Long.MIN_VALUE) System.out.print("");
        return (System.nanoTime() - t0) / 1e6 / ITERS;
    }

    /** Vanilla-style 16-block uniform grid spatial index (the structure SoA replaces). */
    private static final class Grid {
        private static final int CELL = 16;
        private final Map<Long, List<double[]>> cells = new HashMap<>();

        void put(int id, double px, double py, double pz) {
            cells.computeIfAbsent(key(px, pz), k -> new ArrayList<>()).add(new double[]{px, py, pz});
        }

        long query(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            int cxMin = Math.floorDiv((int) Math.floor(minX), CELL);
            int cxMax = Math.floorDiv((int) Math.floor(maxX), CELL);
            int czMin = Math.floorDiv((int) Math.floor(minZ), CELL);
            int czMax = Math.floorDiv((int) Math.floor(maxZ), CELL);
            long hits = 0;
            for (int cx = cxMin; cx <= cxMax; cx++) {
                for (int cz = czMin; cz <= czMax; cz++) {
                    List<double[]> bucket = cells.get(((long) cx << 32) ^ (cz & 0xFFFFFFFFL));
                    if (bucket == null) continue;
                    for (double[] e : bucket) {
                        if (e[0] + HALF >= minX && e[0] - HALF <= maxX
                                && e[1] + HALF >= minY && e[1] - HALF <= maxY
                                && e[2] + HALF >= minZ && e[2] - HALF <= maxZ) {
                            hits++;
                        }
                    }
                }
            }
            return hits;
        }

        private static long key(double px, double pz) {
            int cx = Math.floorDiv((int) Math.floor(px), CELL);
            int cz = Math.floorDiv((int) Math.floor(pz), CELL);
            return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        }
    }
}

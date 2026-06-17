package dev.tachyon;

import dev.tachyon.simd.NoiseKernel;

/**
 * Throughput comparison of the SIMD vs scalar noise kernels — concrete evidence the
 * Vector API path is a real speedup, not just a functional alternative. Warms up the
 * JIT, then times many fills of a 1M-sample buffer with 5 octaves.
 */
public final class NoiseBench {
    private static final int N = 1 << 20;      // 1,048,576 samples
    private static final int ITERS = 200;
    private static final int WARMUP = 60;

    public static void main(String[] args) {
        float[] out = new float[N];
        NoiseKernel simd = NoiseKernel.create();
        NoiseKernel scalar = NoiseKernel.scalar();
        System.out.println("NoiseBench: " + N + " samples x " + ITERS + " iters, 5 octaves");
        System.out.println("  simd backend  = " + simd.backend());
        System.out.println("  scalar backend= " + scalar.backend());

        for (int i = 0; i < WARMUP; i++) {
            simd.fill1D(out, 0f, 0.001f, 1f, 5, 0.5f);
            scalar.fill1D(out, 0f, 0.001f, 1f, 5, 0.5f);
        }

        double scalarMs = time(scalar, out);
        double simdMs = time(simd, out);

        System.out.printf("  scalar: %.3f ms/iter  (%.1f Msamples/s)%n", scalarMs, N / (scalarMs * 1e3));
        System.out.printf("  simd  : %.3f ms/iter  (%.1f Msamples/s)%n", simdMs, N / (simdMs * 1e3));
        System.out.printf("  speedup: %.2fx%n", scalarMs / simdMs);
    }

    private static double time(NoiseKernel k, float[] out) {
        long t0 = System.nanoTime();
        for (int i = 0; i < ITERS; i++) {
            k.fill1D(out, 0f, 0.001f, 1f, 5, 0.5f);
        }
        return (System.nanoTime() - t0) / 1e6 / ITERS;
    }
}

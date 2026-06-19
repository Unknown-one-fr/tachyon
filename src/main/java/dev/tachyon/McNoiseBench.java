package dev.tachyon;

import dev.tachyon.simd.VanillaNoiseMath;

import java.util.Random;

/**
 * Throughput comparison of the bit-exact MC worldgen noise kernels — scalar reference vs the
 * Vector API batch fill — for both {@code NormalNoise} and the heavy 3D {@code BlendedNoise}. This
 * is the concrete speedup the worldgen integration realizes (the kernels are pinned bit-for-bit
 * against vanilla by the parity tests; this measures how much faster the vectorized path is).
 *
 * <p>MC-independent: it builds synthetic {@link VanillaNoiseMath} models (random permutation tables
 * of the same shape vanilla uses), so it runs on the plain runtime classpath without booting MC.
 */
public final class McNoiseBench {
    private static final int N = 1 << 16;     // points per fill
    private static final int ITERS = 400;
    private static final int WARMUP = 120;

    public static void main(String[] args) {
        Random rng = new Random(1234567L);
        VanillaNoiseMath.Normal normal = syntheticNormal(rng);
        VanillaNoiseMath.Blended blended = syntheticBlended(rng);

        double[] xs = new double[N], ys = new double[N], zs = new double[N], out = new double[N];
        int[] ix = new int[N], iy = new int[N], iz = new int[N];
        for (int i = 0; i < N; i++) {
            xs[i] = (i - N / 2) * 0.37 + 0.5;
            ys[i] = (i % 97) * 1.3 - 40;
            zs[i] = (i % 131) * 0.61 - 50;
            ix[i] = i - N / 2;
            iy[i] = (i % 256) - 64;
            iz[i] = (i % 311) - 80;
        }

        System.out.println("McNoiseBench: " + N + " samples x " + ITERS + " iters");
        System.out.println("  backend = " + VanillaNoiseMath.backend());

        // NormalNoise
        for (int w = 0; w < WARMUP; w++) {
            scalarNormal(normal, xs, ys, zs, out);
            VanillaNoiseMath.fillNormal(normal, xs, ys, zs, out, N);
        }
        double nScalar = time(() -> scalarNormal(normal, xs, ys, zs, out));
        double nVector = time(() -> VanillaNoiseMath.fillNormal(normal, xs, ys, zs, out, N));
        report("NormalNoise ", nScalar, nVector);

        // BlendedNoise (the heavy 3D terrain noise)
        for (int w = 0; w < WARMUP; w++) {
            scalarBlended(blended, ix, iy, iz, out);
            VanillaNoiseMath.fillBlended(blended, ix, iy, iz, out, N);
        }
        double bScalar = time(() -> scalarBlended(blended, ix, iy, iz, out));
        double bVector = time(() -> VanillaNoiseMath.fillBlended(blended, ix, iy, iz, out, N));
        report("BlendedNoise", bScalar, bVector);
    }

    private static void scalarNormal(VanillaNoiseMath.Normal n, double[] xs, double[] ys, double[] zs, double[] out) {
        for (int i = 0; i < N; i++) out[i] = VanillaNoiseMath.sampleScalar(n, xs[i], ys[i], zs[i]);
    }

    private static void scalarBlended(VanillaNoiseMath.Blended b, int[] xs, int[] ys, int[] zs, double[] out) {
        for (int i = 0; i < N; i++) out[i] = VanillaNoiseMath.sampleBlendedScalar(b, xs[i], ys[i], zs[i]);
    }

    private static double time(Runnable r) {
        long t0 = System.nanoTime();
        for (int i = 0; i < ITERS; i++) r.run();
        return (System.nanoTime() - t0) / 1e6 / ITERS;
    }

    private static void report(String name, double scalarMs, double vectorMs) {
        System.out.printf("  %s: scalar %.3f ms  | simd %.3f ms  | %.2fx  (%.1f Msamples/s simd)%n",
                name, scalarMs, vectorMs, scalarMs / vectorMs, N / (vectorMs * 1e3));
    }

    // --- synthetic models (same shape as vanilla, random permutations) ---

    private static byte[] perm(Random rng) {
        byte[] p = new byte[256];
        for (int i = 0; i < 256; i++) p[i] = (byte) i;
        for (int i = 0; i < 256; i++) {
            int j = i + rng.nextInt(256 - i);
            byte t = p[i]; p[i] = p[j]; p[j] = t;
        }
        return p;
    }

    private static VanillaNoiseMath.Perlin syntheticPerlin(Random rng, int octaves) {
        byte[][] p = new byte[octaves][];
        double[] xo = new double[octaves], yo = new double[octaves], zo = new double[octaves], amp = new double[octaves];
        for (int i = 0; i < octaves; i++) {
            p[i] = perm(rng);
            xo[i] = rng.nextDouble() * 256;
            yo[i] = rng.nextDouble() * 256;
            zo[i] = rng.nextDouble() * 256;
            amp[i] = 1.0;
        }
        double lowestFreqInputFactor = Math.pow(2.0, -(octaves - 1));
        double lowestFreqValueFactor = Math.pow(2.0, octaves - 1) / (Math.pow(2.0, octaves) - 1.0);
        return new VanillaNoiseMath.Perlin(p, xo, yo, zo, amp, lowestFreqInputFactor, lowestFreqValueFactor);
    }

    private static VanillaNoiseMath.Normal syntheticNormal(Random rng) {
        return new VanillaNoiseMath.Normal(syntheticPerlin(rng, 8), syntheticPerlin(rng, 8), 0.5);
    }

    private static VanillaNoiseMath.BlendedStack syntheticStack(Random rng, int octaves) {
        byte[][] p = new byte[octaves][];
        double[] xo = new double[octaves], yo = new double[octaves], zo = new double[octaves];
        for (int i = 0; i < octaves; i++) {
            p[i] = perm(rng);
            xo[i] = rng.nextDouble() * 256;
            yo[i] = rng.nextDouble() * 256;
            zo[i] = rng.nextDouble() * 256;
        }
        return new VanillaNoiseMath.BlendedStack(p, xo, yo, zo);
    }

    private static VanillaNoiseMath.Blended syntheticBlended(Random rng) {
        // Same shape as BASE_3D_NOISE_OVERWORLD: 16/16 limit octaves, 8 main, 684.412 scale.
        double xzMul = 684.412 * 0.25, yMul = 684.412 * 0.125;
        return new VanillaNoiseMath.Blended(
                syntheticStack(rng, 16), syntheticStack(rng, 16), syntheticStack(rng, 8),
                xzMul, yMul, 80.0, 160.0, 8.0);
    }
}

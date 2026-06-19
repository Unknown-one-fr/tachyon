package dev.tachyon.mc;

import dev.tachyon.simd.VanillaNoiseMath;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MC-facing bridge for the SIMD worldgen noise path. Extracts the permutation tables, octave
 * offsets and scaling factors out of a live {@link NormalNoise} (via access-widened fields,
 * read-only) into the MC-free {@link VanillaNoiseMath.Normal} model, caches that per
 * {@code NormalNoise}, and drives the vectorized batch fill.
 *
 * <p>The extracted model is bit-identical input to {@link VanillaNoiseMath}, which reproduces
 * vanilla's {@code NormalNoise.getValue} exactly — so swapping this into worldgen does not
 * change terrain. See {@code NoiseDensityFunctionMixin} for the wiring point
 * ({@code DensityFunctions$Noise.fillArray}) and {@code McNoiseParityTest} for the proof.
 */
public final class McNoiseKernel {
    private McNoiseKernel() {}

    /** Cache keyed by NormalNoise identity (NormalNoise has no value-equality, so this is identity). */
    private static final Map<NormalNoise, VanillaNoiseMath.Normal> CACHE = new ConcurrentHashMap<>();

    /** Cache for the heavy BlendedNoise 3D terrain noise, keyed by identity. */
    private static final Map<BlendedNoise, VanillaNoiseMath.Blended> BLENDED_CACHE = new ConcurrentHashMap<>();

    /** Reusable per-thread coordinate scratch, sized to the largest batch seen on that thread. */
    private static final ThreadLocal<double[][]> SCRATCH = ThreadLocal.withInitial(() -> new double[3][0]);

    /** Reusable per-thread integer block-coordinate scratch for the BlendedNoise path. */
    private static final ThreadLocal<int[][]> ISCRATCH = ThreadLocal.withInitial(() -> new int[3][0]);

    // Lightweight A/B counters (worldgen runs off the server thread, so keep these lock-free).
    private static final AtomicLong FILLS = new AtomicLong();
    private static final AtomicLong POINTS = new AtomicLong();
    private static final AtomicLong NANOS = new AtomicLong();
    private static volatile boolean loggedEngaged;

    /** Build (or fetch cached) the MC-free model of a NormalNoise's exact math. */
    public static VanillaNoiseMath.Normal model(NormalNoise nn) {
        return CACHE.computeIfAbsent(nn, McNoiseKernel::extract);
    }

    private static VanillaNoiseMath.Normal extract(NormalNoise nn) {
        return new VanillaNoiseMath.Normal(extractPerlin(nn.first), extractPerlin(nn.second), nn.valueFactor);
    }

    private static VanillaNoiseMath.Perlin extractPerlin(PerlinNoise pn) {
        ImprovedNoise[] levels = pn.noiseLevels;
        DoubleList amplitudes = pn.amplitudes;
        int octaves = levels.length;
        byte[][] p = new byte[octaves][];
        double[] xo = new double[octaves], yo = new double[octaves], zo = new double[octaves];
        double[] amp = new double[octaves];
        for (int i = 0; i < octaves; i++) {
            ImprovedNoise in = levels[i];
            if (in != null) {
                p[i] = in.p;       // shared reference; read-only on the hot path
                xo[i] = in.xo;
                yo[i] = in.yo;
                zo[i] = in.zo;
            }
            amp[i] = amplitudes.getDouble(i);
        }
        return new VanillaNoiseMath.Perlin(p, xo, yo, zo, amp,
                pn.lowestFreqInputFactor, pn.lowestFreqValueFactor);
    }

    /** Build (or fetch cached) the MC-free model of a BlendedNoise. */
    public static VanillaNoiseMath.Blended blendedModel(BlendedNoise bn) {
        return BLENDED_CACHE.computeIfAbsent(bn, McNoiseKernel::extractBlended);
    }

    private static VanillaNoiseMath.Blended extractBlended(BlendedNoise bn) {
        return new VanillaNoiseMath.Blended(
                extractStack(bn.minLimitNoise), extractStack(bn.maxLimitNoise), extractStack(bn.mainNoise),
                bn.xzMultiplier, bn.yMultiplier, bn.xzFactor, bn.yFactor, bn.smearScaleMultiplier);
    }

    /** Extract a PerlinNoise stack in {@code getOctaveNoise(i)} order (the order BlendedNoise samples). */
    private static VanillaNoiseMath.BlendedStack extractStack(PerlinNoise pn) {
        int octaves = pn.noiseLevels.length;
        byte[][] p = new byte[octaves][];
        double[] xo = new double[octaves], yo = new double[octaves], zo = new double[octaves];
        for (int i = 0; i < octaves; i++) {
            ImprovedNoise in = pn.getOctaveNoise(i);
            if (in != null) {
                p[i] = in.p;
                xo[i] = in.xo;
                yo[i] = in.yo;
                zo[i] = in.zo;
            }
        }
        return new VanillaNoiseMath.BlendedStack(p, xo, yo, zo);
    }

    /**
     * Fill {@code output} for a {@code DensityFunctions$Noise} by sampling the SIMD kernel at each
     * context index, scaling block coords by {@code xzScale}/{@code yScale} exactly as
     * {@code Noise.compute} does. Returns {@code false} (caller should fall back to vanilla) when
     * the noise is uninitialised or the batch is below {@code minBatch}.
     */
    public static boolean fillArray(NormalNoise nn, double xzScale, double yScale,
                                    double[] output, DensityFunction.ContextProvider cp, int minBatch) {
        if (nn == null) {
            return false;
        }
        int count = output.length;
        if (count < minBatch) {
            return false;
        }
        double[] xs, ys, zs;
        {
            double[][] s = scratch(count);
            xs = s[0]; ys = s[1]; zs = s[2];
        }
        for (int i = 0; i < count; i++) {
            DensityFunction.FunctionContext ctx = cp.forIndex(i);
            xs[i] = ctx.blockX() * xzScale;
            ys[i] = ctx.blockY() * yScale;
            zs[i] = ctx.blockZ() * xzScale;
        }
        run(nn, xs, ys, zs, output, count);
        return true;
    }

    /**
     * As {@link #fillArray} but for {@code DensityFunctions$ShiftedNoise}, whose sample coordinates
     * are offset by per-point sub-functions: {@code blockX*xzScale + shiftX.compute(ctx)} (etc.),
     * then {@code noise.getValue(x,y,z)}. The cheap per-point shift evaluation stays scalar (exactly
     * vanilla); only the expensive {@code NormalNoise} octave sampling is vectorized. This covers the
     * bulk of the vanilla noise router (continentalness/erosion/weirdness/temperature/humidity/etc.).
     */
    public static boolean fillArrayShifted(NormalNoise nn, double xzScale, double yScale,
                                           DensityFunction shiftX, DensityFunction shiftY, DensityFunction shiftZ,
                                           double[] output, DensityFunction.ContextProvider cp, int minBatch) {
        if (nn == null) {
            return false;
        }
        int count = output.length;
        if (count < minBatch) {
            return false;
        }
        double[] xs, ys, zs;
        {
            double[][] s = scratch(count);
            xs = s[0]; ys = s[1]; zs = s[2];
        }
        for (int i = 0; i < count; i++) {
            DensityFunction.FunctionContext ctx = cp.forIndex(i);
            xs[i] = ctx.blockX() * xzScale + shiftX.compute(ctx);
            ys[i] = ctx.blockY() * yScale + shiftY.compute(ctx);
            zs[i] = ctx.blockZ() * xzScale + shiftZ.compute(ctx);
        }
        run(nn, xs, ys, zs, output, count);
        return true;
    }

    /**
     * Fill {@code output} for a {@code BlendedNoise} (the heavy 3D terrain noise) via the SIMD kernel.
     * Block coordinates are taken straight from the context (BlendedNoise scales them internally).
     */
    public static boolean fillArrayBlended(BlendedNoise bn, double[] output,
                                           DensityFunction.ContextProvider cp, int minBatch) {
        if (bn == null) {
            return false;
        }
        int count = output.length;
        if (count < minBatch) {
            return false;
        }
        int[][] s = ISCRATCH.get();
        if (s[0].length < count) {
            s = new int[][]{new int[count], new int[count], new int[count]};
            ISCRATCH.set(s);
        }
        int[] xs = s[0], ys = s[1], zs = s[2];
        for (int i = 0; i < count; i++) {
            DensityFunction.FunctionContext ctx = cp.forIndex(i);
            xs[i] = ctx.blockX();
            ys[i] = ctx.blockY();
            zs[i] = ctx.blockZ();
        }
        long t0 = System.nanoTime();
        VanillaNoiseMath.fillBlended(blendedModel(bn), xs, ys, zs, output, count);
        NANOS.addAndGet(System.nanoTime() - t0);
        FILLS.incrementAndGet();
        POINTS.addAndGet(count);
        engagedLog(count);
        if (dev.tachyon.TachyonMod.config != null && dev.tachyon.TachyonMod.config.simdVerifyNoise) {
            for (int i = 0; i < count; i++) {
                verify(output[i], bn.compute(new DensityFunction.SinglePointContext(xs[i], ys[i], zs[i])));
            }
        }
        return true;
    }

    private static double[][] scratch(int count) {
        double[][] s = SCRATCH.get();
        if (s[0].length < count) {
            s = new double[][]{new double[count], new double[count], new double[count]};
            SCRATCH.set(s);
        }
        return s;
    }

    private static void run(NormalNoise nn, double[] xs, double[] ys, double[] zs, double[] output, int count) {
        long t0 = System.nanoTime();
        VanillaNoiseMath.fillNormal(model(nn), xs, ys, zs, output, count);
        NANOS.addAndGet(System.nanoTime() - t0);
        FILLS.incrementAndGet();
        POINTS.addAndGet(count);
        engagedLog(count);
        if (dev.tachyon.TachyonMod.config != null && dev.tachyon.TachyonMod.config.simdVerifyNoise) {
            for (int i = 0; i < count; i++) {
                verify(output[i], nn.getValue(xs[i], ys[i], zs[i]));
            }
        }
    }

    private static void engagedLog(int count) {
        if (!loggedEngaged) {
            loggedEngaged = true;
            dev.tachyon.TachyonMod.LOG.info("SIMD worldgen noise path engaged ({}, first batch={} samples)",
                    VanillaNoiseMath.backend(), count);
        }
    }

    // DEBUG cross-check: confirm the SIMD output is bit-for-bit equal to the vanilla value.
    private static final AtomicLong VERIFY_CHECKED = new AtomicLong();
    private static final AtomicLong VERIFY_MISMATCH = new AtomicLong();

    private static void verify(double simd, double vanilla) {
        long checked = VERIFY_CHECKED.incrementAndGet();
        if (Double.doubleToLongBits(simd) != Double.doubleToLongBits(vanilla)) {
            long m = VERIFY_MISMATCH.incrementAndGet();
            if (m <= 5) {
                dev.tachyon.TachyonMod.LOG.error("SIMD noise MISMATCH: simd={} vanilla={}", simd, vanilla);
            }
        } else if (checked == 50_000L) {
            dev.tachyon.TachyonMod.LOG.info("SIMD noise verify: {} samples checked, {} mismatches (bit-exact)",
                    checked, VERIFY_MISMATCH.get());
        }
    }

    /** One-line A/B readout of the SIMD worldgen path, surfaced via {@code /tachyon perf}. */
    public static String stats() {
        long fills = FILLS.get(), points = POINTS.get(), nanos = NANOS.get();
        if (fills == 0) {
            return "simd-noise: idle (no batched worldgen fills yet)";
        }
        return String.format(Locale.ROOT,
                "simd-noise: fills=%d points=%d total=%.1fms (%.1f Msamples/s)",
                fills, points, nanos / 1e6, points / (nanos / 1e3));
    }

    public static void resetStats() {
        FILLS.set(0);
        POINTS.set(0);
        NANOS.set(0);
    }

    /**
     * Live confirmation (logged at server start) that the field extraction + vector path reproduce
     * the real {@link NormalNoise} bit-for-bit in <em>this</em> JVM — not just the test JVM. The
     * JUnit {@code McNoiseParityTest} is the exhaustive gate; this is the runtime smoke test.
     */
    public static String selfCheck() {
        try {
            NormalNoise nn = NormalNoise.create(RandomSource.create(42L),
                    new NormalNoise.NoiseParameters(-7, 1.0, 1.0, 1.0, 1.0));
            VanillaNoiseMath.Normal model = model(nn);
            int n = 33;
            double[] xs = new double[n], ys = new double[n], zs = new double[n], out = new double[n];
            for (int i = 0; i < n; i++) {
                xs[i] = (i - 16) * 1.37 + 0.5;
                ys[i] = (i % 7) * 2.1 - 3.0;
                zs[i] = (i % 11) * 0.9 - 2.5;
            }
            VanillaNoiseMath.fillNormal(model, xs, ys, zs, out, n);
            int mismatches = 0;
            for (int i = 0; i < n; i++) {
                if (Double.doubleToLongBits(out[i]) != Double.doubleToLongBits(nn.getValue(xs[i], ys[i], zs[i]))) {
                    mismatches++;
                }
            }
            return String.format(Locale.ROOT, "simd-noise self-check: backend=%s N=%d mismatches=%d%s",
                    VanillaNoiseMath.backend(), n, mismatches, mismatches == 0 ? " (bit-exact vs vanilla)" : " !!");
        } catch (Throwable t) {
            return "simd-noise self-check: ERROR " + t;
        }
    }
}

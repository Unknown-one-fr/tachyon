package dev.tachyon.simd;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * A <b>bit-exact</b> reimplementation of Minecraft's worldgen noise math
 * ({@code NormalNoise} / {@code PerlinNoise} / {@code ImprovedNoise}), batched and
 * vectorized with the JDK incubator Vector API.
 *
 * <p>Unlike {@link SimdNoise} (a fast <em>approximate</em> sum-of-sines used only to prove
 * the Vector API path standalone), this class reproduces vanilla's exact gradient-noise
 * arithmetic — gradient dot products, quintic {@code smoothstep}, trilinear {@code lerp3},
 * octave accumulation — in the identical evaluation order, so the output is
 * bit-for-bit equal to {@code NormalNoise.getValue}. That equality is the whole point: it
 * lets us swap the kernel into live worldgen ({@code DensityFunctions$Noise.fillArray})
 * without changing terrain. {@code McNoiseParityTest} pins it against the real MC class.
 *
 * <p>This class is deliberately Minecraft-independent: it operates on the permutation
 * tables / offsets / factors <em>extracted</em> from a {@code NormalNoise} (see
 * {@code dev.tachyon.mc.McNoiseKernel}), so it stays in the MC-free {@code simd} package and
 * can be unit-tested and benched without booting the game.
 *
 * <p><b>Why it is exact under vectorization:</b> IEEE-754 {@code +,-,*} are performed
 * per-lane by the Vector API with the same rounding as scalar Java, and we never emit a
 * fused multiply-add. Multiplication is commutative bit-for-bit, so reassociating
 * {@code amplitude*nv*valueFactor} into {@code nv*amplitude*valueFactor} is safe. Floor /
 * coordinate {@code wrap} (the only integer-ish steps) are computed scalar per lane.
 */
public final class VanillaNoiseMath {
    private VanillaNoiseMath() {}

    /** {@code NormalNoise.INPUT_FACTOR} — the second stack samples slightly offset coordinates. */
    public static final double INPUT_FACTOR = 1.0181268882175227;

    /** {@code SimplexNoise.GRADIENT} (used by {@code ImprovedNoise.gradDot}); index is {@code hash & 15}. */
    private static final int[][] GRADIENT = {
            {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
            {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
            {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1},
            {1, 1, 0}, {0, -1, 1}, {-1, 1, 0}, {0, -1, -1}
    };

    private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;
    private static final int LANES = SPECIES.length();

    /** Human-readable backend (lane width) for diagnostics. */
    public static String backend() {
        return "simd(" + LANES + "x f64)";
    }

    // --- extracted, MC-free models of the noise structures ---

    /** One {@code PerlinNoise} octave stack: the per-octave {@code ImprovedNoise} data + factors. */
    public static final class Perlin {
        /** Per-octave 256-entry permutation tables; a null entry means that octave is skipped. */
        public final byte[][] p;
        public final double[] xo, yo, zo;   // per-octave random offsets
        public final double[] amplitude;     // per-octave amplitude
        public final double lowestFreqInputFactor;
        public final double lowestFreqValueFactor;

        public Perlin(byte[][] p, double[] xo, double[] yo, double[] zo, double[] amplitude,
                      double lowestFreqInputFactor, double lowestFreqValueFactor) {
            this.p = p;
            this.xo = xo;
            this.yo = yo;
            this.zo = zo;
            this.amplitude = amplitude;
            this.lowestFreqInputFactor = lowestFreqInputFactor;
            this.lowestFreqValueFactor = lowestFreqValueFactor;
        }

        int octaves() {
            return p.length;
        }
    }

    /** A {@code NormalNoise}: two {@link Perlin} stacks combined with a value factor. */
    public static final class Normal {
        public final Perlin first, second;
        public final double valueFactor;

        public Normal(Perlin first, Perlin second, double valueFactor) {
            this.first = first;
            this.second = second;
            this.valueFactor = valueFactor;
        }
    }

    // --- scalar reference (exact replica of NormalNoise.getValue) ---

    /** Exact scalar replica of {@code NormalNoise.getValue(x,y,z)}. */
    public static double sampleScalar(Normal n, double x, double y, double z) {
        double s1 = perlinScalar(n.first, x, y, z);
        double s2 = perlinScalar(n.second, x * INPUT_FACTOR, y * INPUT_FACTOR, z * INPUT_FACTOR);
        return (s1 + s2) * n.valueFactor;
    }

    private static double perlinScalar(Perlin pk, double x, double y, double z) {
        double value = 0.0;
        double factor = pk.lowestFreqInputFactor;
        double valueFactor = pk.lowestFreqValueFactor;
        for (int i = 0; i < pk.octaves(); i++) {
            byte[] pi = pk.p[i];
            if (pi != null) {
                double nv = improvedNoise(pi, pk.xo[i], pk.yo[i], pk.zo[i],
                        wrap(x * factor), wrap(y * factor), wrap(z * factor));
                value += pk.amplitude[i] * nv * valueFactor;
            }
            factor *= 2.0;
            valueFactor /= 2.0;
        }
        return value;
    }

    /** Exact replica of {@code ImprovedNoise.noise(x,y,z)} (yScale=0, so no y-fudge). */
    private static double improvedNoise(byte[] p, double xo, double yo, double zo,
                                        double x, double y, double z) {
        double xx = x + xo, yy = y + yo, zz = z + zo;
        int xf = floor(xx), yf = floor(yy), zf = floor(zz);
        double xr = xx - xf, yr = yy - yf, zr = zz - zf;

        int x0 = p(p, xf);
        int x1 = p(p, xf + 1);
        int xy00 = p(p, x0 + yf);
        int xy01 = p(p, x0 + yf + 1);
        int xy10 = p(p, x1 + yf);
        int xy11 = p(p, x1 + yf + 1);
        double d000 = gradDot(p(p, xy00 + zf), xr, yr, zr);
        double d100 = gradDot(p(p, xy10 + zf), xr - 1.0, yr, zr);
        double d010 = gradDot(p(p, xy01 + zf), xr, yr - 1.0, zr);
        double d110 = gradDot(p(p, xy11 + zf), xr - 1.0, yr - 1.0, zr);
        double d001 = gradDot(p(p, xy00 + zf + 1), xr, yr, zr - 1.0);
        double d101 = gradDot(p(p, xy10 + zf + 1), xr - 1.0, yr, zr - 1.0);
        double d011 = gradDot(p(p, xy01 + zf + 1), xr, yr - 1.0, zr - 1.0);
        double d111 = gradDot(p(p, xy11 + zf + 1), xr - 1.0, yr - 1.0, zr - 1.0);
        double xa = smoothstep(xr), ya = smoothstep(yr), za = smoothstep(zr);
        return lerp3(xa, ya, za, d000, d100, d010, d110, d001, d101, d011, d111);
    }

    // --- vectorized batch fill ---

    /**
     * Fill {@code out[0..count)} with {@code sampleScalar(n, xs[i], ys[i], zs[i])}, processing
     * {@link #LANES} points per vector and a scalar tail. Bit-exact with the scalar path.
     */
    public static void fillNormal(Normal n, double[] xs, double[] ys, double[] zs, double[] out, int count) {
        int i = 0;
        int upper = count - (count % LANES);
        if (LANES > 1) {
            double[] x2 = new double[LANES], y2 = new double[LANES], z2 = new double[LANES];
            DoubleVector vf = DoubleVector.broadcast(SPECIES, n.valueFactor);
            for (; i < upper; i += LANES) {
                DoubleVector s1 = perlinVector(n.first, xs, ys, zs, i);
                // second stack samples coordinates scaled by INPUT_FACTOR
                for (int l = 0; l < LANES; l++) {
                    x2[l] = xs[i + l] * INPUT_FACTOR;
                    y2[l] = ys[i + l] * INPUT_FACTOR;
                    z2[l] = zs[i + l] * INPUT_FACTOR;
                }
                DoubleVector s2 = perlinVector(n.second, x2, y2, z2, 0);
                s1.add(s2).mul(vf).intoArray(out, i);
            }
        }
        for (; i < count; i++) {
            out[i] = sampleScalar(n, xs[i], ys[i], zs[i]);
        }
    }

    /** Accumulate one Perlin stack's value for the {@link #LANES} points starting at {@code base}. */
    private static DoubleVector perlinVector(Perlin pk, double[] xs, double[] ys, double[] zs, int base) {
        // per-corner scratch (8 corners): gradient components + fractional coords, per lane
        double[] gx = new double[LANES], gy = new double[LANES], gz = new double[LANES];
        double[] xr = new double[LANES], yr = new double[LANES], zr = new double[LANES];
        // permutation indices reused across corners, per lane
        int[] xf = new int[LANES], yf = new int[LANES], zf = new int[LANES];
        int[] xy00 = new int[LANES], xy01 = new int[LANES], xy10 = new int[LANES], xy11 = new int[LANES];

        DoubleVector acc = DoubleVector.zero(SPECIES);
        double factor = pk.lowestFreqInputFactor;
        double valueFactor = pk.lowestFreqValueFactor;
        for (int o = 0; o < pk.octaves(); o++) {
            byte[] p = pk.p[o];
            if (p != null) {
                double xo = pk.xo[o], yo = pk.yo[o], zo = pk.zo[o];
                for (int l = 0; l < LANES; l++) {
                    double xx = wrap(xs[base + l] * factor) + xo;
                    double yy = wrap(ys[base + l] * factor) + yo;
                    double zz = wrap(zs[base + l] * factor) + zo;
                    int ix = floor(xx), iy = floor(yy), iz = floor(zz);
                    xf[l] = ix; yf[l] = iy; zf[l] = iz;
                    xr[l] = xx - ix; yr[l] = yy - iy; zr[l] = zz - iz;
                    int x0 = p(p, ix);
                    int x1 = p(p, ix + 1);
                    xy00[l] = p(p, x0 + iy);
                    xy01[l] = p(p, x0 + iy + 1);
                    xy10[l] = p(p, x1 + iy);
                    xy11[l] = p(p, x1 + iy + 1);
                }
                DoubleVector xrV = DoubleVector.fromArray(SPECIES, xr, 0);
                DoubleVector yrV = DoubleVector.fromArray(SPECIES, yr, 0);
                DoubleVector zrV = DoubleVector.fromArray(SPECIES, zr, 0);
                DoubleVector xr1 = xrV.sub(1.0), yr1 = yrV.sub(1.0), zr1 = zrV.sub(1.0);

                DoubleVector d000 = corner(p, xy00, zf, 0, gx, gy, gz, xrV, yrV, zrV);
                DoubleVector d100 = corner(p, xy10, zf, 0, gx, gy, gz, xr1, yrV, zrV);
                DoubleVector d010 = corner(p, xy01, zf, 0, gx, gy, gz, xrV, yr1, zrV);
                DoubleVector d110 = corner(p, xy11, zf, 0, gx, gy, gz, xr1, yr1, zrV);
                DoubleVector d001 = corner(p, xy00, zf, 1, gx, gy, gz, xrV, yrV, zr1);
                DoubleVector d101 = corner(p, xy10, zf, 1, gx, gy, gz, xr1, yrV, zr1);
                DoubleVector d011 = corner(p, xy01, zf, 1, gx, gy, gz, xrV, yr1, zr1);
                DoubleVector d111 = corner(p, xy11, zf, 1, gx, gy, gz, xr1, yr1, zr1);

                DoubleVector xa = smoothstep(xrV), ya = smoothstep(yrV), za = smoothstep(zrV);
                DoubleVector res = lerp3(xa, ya, za, d000, d100, d010, d110, d001, d101, d011, d111);
                acc = acc.add(res.mul(pk.amplitude[o]).mul(valueFactor));
            }
            factor *= 2.0;
            valueFactor /= 2.0;
        }
        return acc;
    }

    /**
     * Build the gradient-dot vector for one cube corner across all lanes:
     * gathers {@code GRADIENT[p(xyNN + zf + zAdd) & 15]} per lane, then computes
     * {@code g0*cx + g1*cy + g2*cz} in the same order as {@code SimplexNoise.dot}.
     */
    private static DoubleVector corner(byte[] p, int[] xyNN, int[] zf, int zAdd,
                                       double[] gx, double[] gy, double[] gz,
                                       DoubleVector cx, DoubleVector cy, DoubleVector cz) {
        for (int l = 0; l < LANES; l++) {
            int[] g = GRADIENT[p(p, xyNN[l] + zf[l] + zAdd) & 15];
            gx[l] = g[0];
            gy[l] = g[1];
            gz[l] = g[2];
        }
        DoubleVector g0 = DoubleVector.fromArray(SPECIES, gx, 0);
        DoubleVector g1 = DoubleVector.fromArray(SPECIES, gy, 0);
        DoubleVector g2 = DoubleVector.fromArray(SPECIES, gz, 0);
        return g0.mul(cx).add(g1.mul(cy)).add(g2.mul(cz));
    }

    // ============================================================================================
    //  BlendedNoise (the heavy 3D terrain noise, BASE_3D_NOISE_*) — distinct from NormalNoise.
    //  Three PerlinNoise stacks accessed via getOctaveNoise(i) order, with the y-fudge path active.
    //  Both limit stacks are summed unconditionally: vanilla skips one when factor is at an extreme,
    //  but clampedLerp discards the skipped one there, so the result is bit-identical either way.
    // ============================================================================================

    /** One BlendedNoise PerlinNoise stack, octaves in {@code getOctaveNoise(i)} order (null = absent). */
    public static final class BlendedStack {
        public final byte[][] p;          // [octave i][256]; null entry => octave absent
        public final double[] xo, yo, zo;

        public BlendedStack(byte[][] p, double[] xo, double[] yo, double[] zo) {
            this.p = p;
            this.xo = xo;
            this.yo = yo;
            this.zo = zo;
        }

        int octaves() {
            return p.length;
        }
    }

    /** Extracted model of a {@code BlendedNoise}. */
    public static final class Blended {
        public final BlendedStack minLimit, maxLimit, main;
        public final double xzMultiplier, yMultiplier, xzFactor, yFactor, smearScaleMultiplier;

        public Blended(BlendedStack minLimit, BlendedStack maxLimit, BlendedStack main,
                       double xzMultiplier, double yMultiplier, double xzFactor, double yFactor,
                       double smearScaleMultiplier) {
            this.minLimit = minLimit;
            this.maxLimit = maxLimit;
            this.main = main;
            this.xzMultiplier = xzMultiplier;
            this.yMultiplier = yMultiplier;
            this.xzFactor = xzFactor;
            this.yFactor = yFactor;
            this.smearScaleMultiplier = smearScaleMultiplier;
        }
    }

    /** Exact scalar replica of {@code BlendedNoise.compute} (computes both limit stacks). */
    public static double sampleBlendedScalar(Blended b, int blockX, int blockY, int blockZ) {
        double limitX = blockX * b.xzMultiplier;
        double limitY = blockY * b.yMultiplier;
        double limitZ = blockZ * b.xzMultiplier;
        double mainX = limitX / b.xzFactor;
        double mainY = limitY / b.yFactor;
        double mainZ = limitZ / b.xzFactor;
        double limitSmear = b.yMultiplier * b.smearScaleMultiplier;
        double mainSmear = limitSmear / b.yFactor;

        double mainNoiseValue = 0.0;
        double pow = 1.0;
        for (int i = 0; i < b.main.octaves(); i++) {
            byte[] p = b.main.p[i];
            if (p != null) {
                mainNoiseValue += improvedNoiseFudge(p, b.main.xo[i], b.main.yo[i], b.main.zo[i],
                        wrap(mainX * pow), wrap(mainY * pow), wrap(mainZ * pow), mainSmear * pow, mainY * pow) / pow;
            }
            pow /= 2.0;
        }
        double factor = (mainNoiseValue / 10.0 + 1.0) / 2.0;

        double blendMin = 0.0, blendMax = 0.0;
        pow = 1.0;
        for (int i = 0; i < 16; i++) {
            double wx = wrap(limitX * pow), wy = wrap(limitY * pow), wz = wrap(limitZ * pow);
            double yScalePow = limitSmear * pow;
            byte[] pmin = i < b.minLimit.octaves() ? b.minLimit.p[i] : null;
            if (pmin != null) {
                blendMin += improvedNoiseFudge(pmin, b.minLimit.xo[i], b.minLimit.yo[i], b.minLimit.zo[i],
                        wx, wy, wz, yScalePow, limitY * pow) / pow;
            }
            byte[] pmax = i < b.maxLimit.octaves() ? b.maxLimit.p[i] : null;
            if (pmax != null) {
                blendMax += improvedNoiseFudge(pmax, b.maxLimit.xo[i], b.maxLimit.yo[i], b.maxLimit.zo[i],
                        wx, wy, wz, yScalePow, limitY * pow) / pow;
            }
            pow /= 2.0;
        }
        return clampedLerp(factor, blendMin / 512.0, blendMax / 512.0) / 128.0;
    }

    /** Exact replica of {@code ImprovedNoise.noise(x,y,z,yScale,yFudge)} (full y-fudge path). */
    private static double improvedNoiseFudge(byte[] p, double xo, double yo, double zo,
                                             double x, double y, double z, double yScale, double yFudge) {
        double xx = x + xo, yy = y + yo, zz = z + zo;
        int xf = floor(xx), yf = floor(yy), zf = floor(zz);
        double xr = xx - xf, yr = yy - yf, zr = zz - zf;
        double yrFudge;
        if (yScale != 0.0) {
            double fudgeLimit = (yFudge >= 0.0 && yFudge < yr) ? yFudge : yr;
            yrFudge = Math.floor(fudgeLimit / yScale + 1.0E-7) * yScale;
        } else {
            yrFudge = 0.0;
        }
        return sampleAndLerp(p, xf, yf, zf, xr, yr - yrFudge, zr, yr);
    }

    private static double sampleAndLerp(byte[] p, int x, int y, int z,
                                        double xr, double yr, double zr, double yrOriginal) {
        int x0 = p(p, x), x1 = p(p, x + 1);
        int xy00 = p(p, x0 + y), xy01 = p(p, x0 + y + 1), xy10 = p(p, x1 + y), xy11 = p(p, x1 + y + 1);
        double d000 = gradDot(p(p, xy00 + z), xr, yr, zr);
        double d100 = gradDot(p(p, xy10 + z), xr - 1.0, yr, zr);
        double d010 = gradDot(p(p, xy01 + z), xr, yr - 1.0, zr);
        double d110 = gradDot(p(p, xy11 + z), xr - 1.0, yr - 1.0, zr);
        double d001 = gradDot(p(p, xy00 + z + 1), xr, yr, zr - 1.0);
        double d101 = gradDot(p(p, xy10 + z + 1), xr - 1.0, yr, zr - 1.0);
        double d011 = gradDot(p(p, xy01 + z + 1), xr, yr - 1.0, zr - 1.0);
        double d111 = gradDot(p(p, xy11 + z + 1), xr - 1.0, yr - 1.0, zr - 1.0);
        return lerp3(smoothstep(xr), smoothstep(yrOriginal), smoothstep(zr),
                d000, d100, d010, d110, d001, d101, d011, d111);
    }

    private static double clampedLerp(double factor, double min, double max) {
        if (factor < 0.0) return min;
        return factor > 1.0 ? max : lerp(factor, min, max);
    }

    /** Vectorized batch fill matching {@link #sampleBlendedScalar}, bit-exact. */
    public static void fillBlended(Blended b, int[] xs, int[] ys, int[] zs, double[] out, int count) {
        int i = 0;
        int upper = count - (count % LANES);
        if (LANES > 1) {
            double limitSmear = b.yMultiplier * b.smearScaleMultiplier;
            double mainSmear = limitSmear / b.yFactor;
            double[] limX = new double[LANES], limY = new double[LANES], limZ = new double[LANES];
            double[] mainX = new double[LANES], mainY = new double[LANES], mainZ = new double[LANES];
            Scratch sc = new Scratch();
            for (; i < upper; i += LANES) {
                for (int l = 0; l < LANES; l++) {
                    double lx = xs[i + l] * b.xzMultiplier;
                    double ly = ys[i + l] * b.yMultiplier;
                    double lz = zs[i + l] * b.xzMultiplier;
                    limX[l] = lx; limY[l] = ly; limZ[l] = lz;
                    mainX[l] = lx / b.xzFactor; mainY[l] = ly / b.yFactor; mainZ[l] = lz / b.xzFactor;
                }
                DoubleVector mainSum = blendedStack(b.main, mainSmear, mainX, mainY, mainZ, mainY, sc);
                DoubleVector minSum = blendedStack(b.minLimit, limitSmear, limX, limY, limZ, limY, sc);
                DoubleVector maxSum = blendedStack(b.maxLimit, limitSmear, limX, limY, limZ, limY, sc);
                DoubleVector factor = mainSum.div(10.0).add(1.0).div(2.0);
                DoubleVector res = clampedLerp(factor, minSum.div(512.0), maxSum.div(512.0)).div(128.0);
                res.intoArray(out, i);
            }
        }
        for (; i < count; i++) {
            out[i] = sampleBlendedScalar(b, xs[i], ys[i], zs[i]);
        }
    }

    /** Reusable per-call lane scratch for the blended octave inner loop. */
    private static final class Scratch {
        final double[] gx = new double[LANES], gy = new double[LANES], gz = new double[LANES];
        final double[] xr = new double[LANES], yrEff = new double[LANES], zr = new double[LANES], yrOrig = new double[LANES];
        final int[] zf = new int[LANES], xy00 = new int[LANES], xy01 = new int[LANES], xy10 = new int[LANES], xy11 = new int[LANES];
    }

    /** Accumulate one BlendedNoise stack's value across {@link #LANES} points (octaves in stack order). */
    private static DoubleVector blendedStack(BlendedStack stk, double smear,
                                             double[] inX, double[] inY, double[] inZ, double[] fudgeBaseY, Scratch s) {
        DoubleVector acc = DoubleVector.zero(SPECIES);
        double pow = 1.0;
        for (int o = 0; o < stk.octaves(); o++) {
            byte[] p = stk.p[o];
            if (p != null) {
                double xo = stk.xo[o], yo = stk.yo[o], zo = stk.zo[o];
                double yScale = smear * pow;
                for (int l = 0; l < LANES; l++) {
                    double xx = wrap(inX[l] * pow) + xo;
                    double yy = wrap(inY[l] * pow) + yo;
                    double zz = wrap(inZ[l] * pow) + zo;
                    int ix = floor(xx), iy = floor(yy), iz = floor(zz);
                    double yrr = yy - iy;
                    double yFudge = fudgeBaseY[l] * pow;
                    double yrFudge;
                    if (yScale != 0.0) {
                        double fudgeLimit = (yFudge >= 0.0 && yFudge < yrr) ? yFudge : yrr;
                        yrFudge = Math.floor(fudgeLimit / yScale + 1.0E-7) * yScale;
                    } else {
                        yrFudge = 0.0;
                    }
                    s.xr[l] = xx - ix;
                    s.yrEff[l] = yrr - yrFudge;
                    s.zr[l] = zz - iz;
                    s.yrOrig[l] = yrr;
                    s.zf[l] = iz;
                    int x0 = p(p, ix), x1 = p(p, ix + 1);
                    s.xy00[l] = p(p, x0 + iy);
                    s.xy01[l] = p(p, x0 + iy + 1);
                    s.xy10[l] = p(p, x1 + iy);
                    s.xy11[l] = p(p, x1 + iy + 1);
                }
                DoubleVector xrV = DoubleVector.fromArray(SPECIES, s.xr, 0);
                DoubleVector yV = DoubleVector.fromArray(SPECIES, s.yrEff, 0);
                DoubleVector zrV = DoubleVector.fromArray(SPECIES, s.zr, 0);
                DoubleVector xr1 = xrV.sub(1.0), yr1 = yV.sub(1.0), zr1 = zrV.sub(1.0);
                DoubleVector d000 = corner(p, s.xy00, s.zf, 0, s.gx, s.gy, s.gz, xrV, yV, zrV);
                DoubleVector d100 = corner(p, s.xy10, s.zf, 0, s.gx, s.gy, s.gz, xr1, yV, zrV);
                DoubleVector d010 = corner(p, s.xy01, s.zf, 0, s.gx, s.gy, s.gz, xrV, yr1, zrV);
                DoubleVector d110 = corner(p, s.xy11, s.zf, 0, s.gx, s.gy, s.gz, xr1, yr1, zrV);
                DoubleVector d001 = corner(p, s.xy00, s.zf, 1, s.gx, s.gy, s.gz, xrV, yV, zr1);
                DoubleVector d101 = corner(p, s.xy10, s.zf, 1, s.gx, s.gy, s.gz, xr1, yV, zr1);
                DoubleVector d011 = corner(p, s.xy01, s.zf, 1, s.gx, s.gy, s.gz, xrV, yr1, zr1);
                DoubleVector d111 = corner(p, s.xy11, s.zf, 1, s.gx, s.gy, s.gz, xr1, yr1, zr1);
                DoubleVector xa = smoothstep(xrV);
                DoubleVector ya = smoothstep(DoubleVector.fromArray(SPECIES, s.yrOrig, 0)); // alpha uses original yr
                DoubleVector za = smoothstep(zrV);
                DoubleVector res = lerp3(xa, ya, za, d000, d100, d010, d110, d001, d101, d011, d111);
                acc = acc.add(res.div(pow));
            }
            pow /= 2.0;
        }
        return acc;
    }

    private static DoubleVector clampedLerp(DoubleVector factor, DoubleVector min, DoubleVector max) {
        DoubleVector lerped = min.add(factor.mul(max.sub(min)));
        VectorMask<Double> ltZero = factor.compare(VectorOperators.LT, 0.0);
        VectorMask<Double> gtOne = factor.compare(VectorOperators.GT, 1.0);
        return lerped.blend(min, ltZero).blend(max, gtOne);
    }

    // --- scalar helpers (exact MC arithmetic) ---

    private static int p(byte[] p, int x) {
        return p[x & 0xFF] & 0xFF;
    }

    private static double gradDot(int hash, double x, double y, double z) {
        int[] g = GRADIENT[hash & 15];
        return g[0] * x + g[1] * y + g[2] * z;
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }

    /** {@code PerlinNoise.wrap}. */
    private static double wrap(double x) {
        return x - (long) Math.floor(x / 3.3554432E7 + 0.5) * 3.3554432E7;
    }

    private static double smoothstep(double x) {
        return x * x * x * (x * (x * 6.0 - 15.0) + 10.0);
    }

    private static double lerp3(double a1, double a2, double a3,
                                double x000, double x100, double x010, double x110,
                                double x001, double x101, double x011, double x111) {
        return lerp(a3, lerp2(a1, a2, x000, x100, x010, x110), lerp2(a1, a2, x001, x101, x011, x111));
    }

    private static double lerp2(double a1, double a2, double x00, double x10, double x01, double x11) {
        return lerp(a2, lerp(a1, x00, x10), lerp(a1, x01, x11));
    }

    private static double lerp(double a, double p0, double p1) {
        return p0 + a * (p1 - p0);
    }

    // --- vector helpers (same expression structure as the scalar ones) ---

    private static DoubleVector smoothstep(DoubleVector x) {
        DoubleVector inner = x.mul(x.mul(6.0).sub(15.0)).add(10.0); // x*(x*6-15)+10
        return x.mul(x).mul(x).mul(inner);                          // (x*x*x)*inner
    }

    private static DoubleVector lerp3(DoubleVector a1, DoubleVector a2, DoubleVector a3,
                                      DoubleVector x000, DoubleVector x100, DoubleVector x010, DoubleVector x110,
                                      DoubleVector x001, DoubleVector x101, DoubleVector x011, DoubleVector x111) {
        return lerp(a3, lerp2(a1, a2, x000, x100, x010, x110), lerp2(a1, a2, x001, x101, x011, x111));
    }

    private static DoubleVector lerp2(DoubleVector a1, DoubleVector a2,
                                      DoubleVector x00, DoubleVector x10, DoubleVector x01, DoubleVector x11) {
        return lerp(a2, lerp(a1, x00, x10), lerp(a1, x01, x11));
    }

    private static DoubleVector lerp(DoubleVector a, DoubleVector p0, DoubleVector p1) {
        return p0.add(a.mul(p1.sub(p0)));
    }
}

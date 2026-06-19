package dev.tachyon.simd;

import dev.tachyon.mc.McNoiseKernel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The SIMD worldgen kernel must produce <b>bit-for-bit</b> the same value as the real MC
 * {@code NormalNoise.getValue} — otherwise wiring it into worldgen would change terrain. This
 * pins both the scalar reimplementation (proves the field extraction is faithful) and the
 * vectorized path (proves the Vector API lane math reassociates nothing) against vanilla.
 *
 * <p>{@code assertEquals(double,double)} with no delta compares {@code doubleToLongBits} — exact.
 */
class McNoiseParityTest {

    private static NormalNoise makeNoise(long seed) {
        // firstOctave -7, four unit amplitudes — a representative multi-octave stack.
        NormalNoise.NoiseParameters params =
                new NormalNoise.NoiseParameters(-7, 1.0, 1.0, 1.0, 1.0, 1.0);
        return NormalNoise.create(RandomSource.create(seed), params);
    }

    @Test
    void scalarReimplMatchesVanillaBitExact() {
        NormalNoise nn = makeNoise(123456789L);
        VanillaNoiseMath.Normal model = McNoiseKernel.model(nn);
        for (double x = -40.5; x < 40; x += 7.3) {
            for (double y = -12.25; y < 20; y += 3.1) {
                for (double z = -33.0; z < 33; z += 6.7) {
                    double expected = nn.getValue(x, y, z);
                    double actual = VanillaNoiseMath.sampleScalar(model, x, y, z);
                    assertEquals(expected, actual, "scalar mismatch at (" + x + "," + y + "," + z + ")");
                }
            }
        }
    }

    @Test
    void vectorFillMatchesVanillaBitExact() {
        assumeTrue(NoiseKernel.simdAvailable(), "jdk.incubator.vector not present");
        NormalNoise nn = makeNoise(987654321L);
        VanillaNoiseMath.Normal model = McNoiseKernel.model(nn);

        int n = 257; // not a multiple of any lane count, to exercise the scalar tail
        double[] xs = new double[n], ys = new double[n], zs = new double[n], out = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = (i - 128) * 0.37 + 0.123;
            ys[i] = (i % 31) * 1.9 - 11.0;
            zs[i] = (i % 53) * 0.61 - 7.5;
        }
        VanillaNoiseMath.fillNormal(model, xs, ys, zs, out, n);
        for (int i = 0; i < n; i++) {
            double expected = nn.getValue(xs[i], ys[i], zs[i]);
            assertEquals(expected, out[i], "vector mismatch at index " + i
                    + " (" + xs[i] + "," + ys[i] + "," + zs[i] + ")");
        }
    }
}

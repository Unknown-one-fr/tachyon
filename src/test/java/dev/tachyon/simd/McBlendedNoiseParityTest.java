package dev.tachyon.simd;

import dev.tachyon.mc.McNoiseKernel;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The SIMD {@code BlendedNoise} kernel — the heavy 3D terrain noise — must be <b>bit-for-bit</b>
 * equal to the real {@code BlendedNoise.compute}, including the y-fudge path and the
 * compute-both-limit-stacks simplification (safe because {@code clampedLerp} discards the unused
 * stack at the extremes). Pins both the scalar reimplementation and the vectorized batch fill.
 */
class McBlendedNoiseParityTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        // BlendedNoise implements DensityFunction.SimpleFunction, whose class-init touches the
        // built-in registries — these must be bootstrapped before the noise can be constructed.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static BlendedNoise makeBlended(long seed) {
        // Same shape as BASE_3D_NOISE_OVERWORLD.
        return new BlendedNoise(RandomSource.create(seed), 0.25, 0.125, 80.0, 160.0, 8.0);
    }

    private static DensityFunction.FunctionContext ctx(int x, int y, int z) {
        return new DensityFunction.SinglePointContext(x, y, z);
    }

    @Test
    void scalarReimplMatchesVanillaBitExact() {
        BlendedNoise bn = makeBlended(424242L);
        VanillaNoiseMath.Blended model = McNoiseKernel.blendedModel(bn);
        for (int x = -50; x <= 50; x += 13) {
            for (int y = -32; y <= 96; y += 11) {
                for (int z = -50; z <= 50; z += 13) {
                    double expected = bn.compute(ctx(x, y, z));
                    double actual = VanillaNoiseMath.sampleBlendedScalar(model, x, y, z);
                    assertEquals(expected, actual, "blended scalar mismatch at (" + x + "," + y + "," + z + ")");
                }
            }
        }
    }

    @Test
    void vectorFillMatchesVanillaBitExact() {
        assumeTrue(NoiseKernel.simdAvailable(), "jdk.incubator.vector not present");
        BlendedNoise bn = makeBlended(13371337L);
        VanillaNoiseMath.Blended model = McNoiseKernel.blendedModel(bn);

        int n = 201; // not a lane multiple — exercises the scalar tail
        int[] xs = new int[n], ys = new int[n], zs = new int[n];
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = (i - 100) * 3;
            ys[i] = (i % 48) * 2 - 32;
            zs[i] = (i % 37) * 4 - 64;
        }
        VanillaNoiseMath.fillBlended(model, xs, ys, zs, out, n);
        for (int i = 0; i < n; i++) {
            double expected = bn.compute(ctx(xs[i], ys[i], zs[i]));
            assertEquals(expected, out[i], "blended vector mismatch at index " + i
                    + " (" + xs[i] + "," + ys[i] + "," + zs[i] + ")");
        }
    }
}

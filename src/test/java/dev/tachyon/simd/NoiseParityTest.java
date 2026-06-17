package dev.tachyon.simd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The SIMD kernel must compute the same noise as the scalar one (within float
 * tolerance) — otherwise worldgen would differ depending on CPU/flags. Skipped
 * cleanly when the Vector API isn't on the module path.
 */
class NoiseParityTest {

    @Test
    void simdMatchesScalar() {
        assumeTrue(NoiseKernel.simdAvailable(), "jdk.incubator.vector not present");
        NoiseKernel simd = NoiseKernel.create();
        assumeTrue(simd.backend().startsWith("simd"), "SIMD backend unavailable on this CPU");

        NoiseKernel scalar = NoiseKernel.scalar();
        int len = 1024;
        float[] a = new float[len];
        float[] b = new float[len];
        simd.fill1D(a, 3.5f, 0.013f, 1.0f, 4, 0.5f);
        scalar.fill1D(b, 3.5f, 0.013f, 1.0f, 4, 0.5f);

        for (int i = 0; i < len; i++) {
            assertEquals(b[i], a[i], 1e-3f, "mismatch at index " + i);
        }
    }
}

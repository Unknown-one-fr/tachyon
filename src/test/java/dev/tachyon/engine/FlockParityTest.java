package dev.tachyon.engine;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The SIMD flocking kernel must agree with the scalar reference (within double
 * tolerance) for the same region — otherwise switching kernels would change the
 * simulation. Skipped cleanly when the Vector API is absent.
 */
class FlockParityTest {

    @Test
    void simdFlockMatchesScalar() {
        assumeTrue(FlockKernel.simdAvailable(), "jdk.incubator.vector not present");
        FlockKernel simd = FlockKernel.create();
        assumeTrue(simd.name().startsWith("simd"), "SIMD flock unavailable on this CPU");
        FlockKernel scalar = FlockKernel.scalar();

        int m = 500;
        int[] members = new int[m];
        double[] sx = new double[m];
        double[] sz = new double[m];
        Random rng = new Random(2024);
        for (int i = 0; i < m; i++) {
            members[i] = i;
            sx[i] = rng.nextDouble() * 100;
            sz[i] = rng.nextDouble() * 100;
        }

        double[] aVx = new double[m], aVz = new double[m];
        double[] bVx = new double[m], bVz = new double[m];
        double cohesion = 0.02, separation = 0.5, sepRadius2 = 16.0;
        // exercise the batched (packed) fast path that the engine actually uses
        scalar.computeRegion(members, sx, sz, cohesion, separation, sepRadius2, aVx, aVz);
        simd.computeRegion(members, sx, sz, cohesion, separation, sepRadius2, bVx, bVz);
        for (int s = 0; s < m; s++) {
            assertEquals(aVx[s], bVx[s], 1e-6, "vx mismatch at entity " + s);
            assertEquals(aVz[s], bVz[s], 1e-6, "vz mismatch at entity " + s);
        }
    }
}

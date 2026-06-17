package dev.tachyon.simd;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * SIMD fractal noise using the JDK 25 incubator Vector API. Processes
 * {@code SPECIES_PREFERRED.length()} samples per iteration (8/16 floats on AVX2/AVX-512),
 * computing all octaves in vector lanes, with a scalar tail for the remainder.
 *
 * <p>Referencing {@code jdk.incubator.vector} means this class only links when the
 * module is present; {@link NoiseKernel#create()} probes that and falls back to
 * {@link ScalarNoise} otherwise.
 */
final class SimdNoise implements NoiseKernel {
    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;
    private static final FloatVector LANE_INDEX = laneIndex();

    @Override
    public void fill1D(float[] out, float baseX, float stepX, float freq, int octaves, float persistence) {
        final int len = out.length;
        final int upper = SPECIES.loopBound(len);
        final FloatVector stepLanes = LANE_INDEX.mul(stepX);

        int i = 0;
        for (; i < upper; i += SPECIES.length()) {
            // x = baseX + (i + lane)*stepX
            FloatVector x = FloatVector.broadcast(SPECIES, baseX + i * stepX).add(stepLanes);
            FloatVector acc = FloatVector.zero(SPECIES);
            float amp = 1f, norm = 0f, f = freq;
            for (int o = 0; o < octaves; o++) {
                acc = acc.add(value(x.mul(f)).mul(amp));
                norm += amp;
                amp *= persistence;
                f *= 2f;
            }
            if (norm != 0f) {
                acc = acc.div(norm);
            }
            acc.intoArray(out, i);
        }
        // scalar tail
        for (; i < len; i++) {
            out[i] = ScalarNoise.fractal(baseX + i * stepX, freq, octaves, persistence);
        }
    }

    @Override
    public String backend() {
        return "simd(" + SPECIES.length() + "x f32)";
    }

    /**
     * Vectorized counterpart of {@link ScalarNoise#value}: a single sinusoid. SIN is
     * the costly transcendental the SIMD path accelerates, and (unlike a chaotic
     * frac-hash) it stays numerically close to {@code Math.sin}, so SIMD/scalar output
     * is parity-testable.
     */
    private static FloatVector value(FloatVector x) {
        return x.lanewise(VectorOperators.SIN);
    }

    private static FloatVector laneIndex() {
        float[] idx = new float[SPECIES.length()];
        for (int l = 0; l < idx.length; l++) idx[l] = l;
        return FloatVector.fromArray(SPECIES, idx, 0);
    }
}

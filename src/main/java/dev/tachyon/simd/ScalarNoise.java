package dev.tachyon.simd;

/** Plain-Java fractal value noise; the fallback and the source of truth for the SIMD tail. */
final class ScalarNoise implements NoiseKernel {

    @Override
    public void fill1D(float[] out, float baseX, float stepX, float freq, int octaves, float persistence) {
        for (int i = 0; i < out.length; i++) {
            out[i] = fractal(baseX + i * stepX, freq, octaves, persistence);
        }
    }

    @Override
    public String backend() {
        return "scalar";
    }

    static float fractal(float x, float freq, int octaves, float persistence) {
        float acc = 0f, amp = 1f, norm = 0f, f = freq;
        for (int o = 0; o < octaves; o++) {
            acc += value(x * f) * amp;
            norm += amp;
            amp *= persistence;
            f *= 2f;
        }
        return norm == 0f ? 0f : acc / norm;
    }

    /** Single sinusoid in [-1, 1]; summed across octaves by {@link #fractal}. */
    static float value(float x) {
        return (float) Math.sin(x);
    }
}

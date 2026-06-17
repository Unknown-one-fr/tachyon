package dev.tachyon.simd;

/**
 * A batched fractal-noise kernel used by the worldgen pipeline. Two implementations
 * exist: a scalar fallback and a SIMD one built on the JDK 25 incubator Vector API.
 *
 * <p>{@link #create()} returns the SIMD kernel when {@code jdk.incubator.vector} is
 * available on the module path (we request it via {@code --add-modules}), otherwise
 * the scalar one — so the mod still runs if the launcher omits the flag.
 *
 * <p>Note: this is a fast approximate value-noise used to prove the vectorized path
 * end-to-end. Wiring it into MC's exact {@code DensityFunction} graph is the
 * integration step; the lane math below is the reusable compute core.
 */
public interface NoiseKernel {

    /**
     * Fill {@code out} with fractal noise sampled along x: {@code out[i]} corresponds
     * to {@code baseX + i*stepX}.
     */
    void fill1D(float[] out, float baseX, float stepX, float freq, int octaves, float persistence);

    /** Human-readable backend name for diagnostics. */
    String backend();

    static NoiseKernel create() {
        try {
            NoiseKernel k = new SimdNoise();
            // Force class init + linkage so a missing incubator module fails here.
            k.fill1D(new float[Math.max(1, 8)], 0f, 0.1f, 1f, 2, 0.5f);
            return k;
        } catch (Throwable t) {
            return new ScalarNoise();
        }
    }

    /** Force the scalar implementation (for benchmarking / fallback comparison). */
    static NoiseKernel scalar() {
        return new ScalarNoise();
    }

    static boolean simdAvailable() {
        try {
            Class.forName("jdk.incubator.vector.FloatVector");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}

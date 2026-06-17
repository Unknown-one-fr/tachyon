package dev.tachyon.engine;

/**
 * The read-only per-entity Phase-R computation (a stand-in for entity AI/pathfinding).
 * Pluggable so we can run a scalar reference or a SIMD (Vector API) implementation and
 * prove they agree. Implementations must be pure: read the snapshot, write only
 * {@code outVx[self]}/{@code outVz[self]}.
 */
public interface FlockKernel {

    /**
     * Compute entity {@code self}'s steering intent from its region's snapshot positions.
     *
     * @param self     entity slot being computed
     * @param members  slots of every entity in {@code self}'s region (includes self)
     * @param sx,sz    snapshot positions (indexed by slot)
     * @param outVx,outVz intent output (written at index {@code self})
     */
    void compute(int self, int[] members, double[] sx, double[] sz,
                 double cohesion, double separation, double sepRadius2,
                 double[] outVx, double[] outVz);

    /**
     * Compute intents for every entity in a region in one call. The SIMD kernel uses
     * this to pack the region's positions contiguously first, turning the inner loop's
     * gathers into cheap vector loads — the layout change that makes SIMD actually win.
     */
    void computeRegion(int[] members, double[] sx, double[] sz,
                       double cohesion, double separation, double sepRadius2,
                       double[] outVx, double[] outVz);

    String name();

    static FlockKernel scalar() {
        return new ScalarFlock();
    }

    /** SIMD kernel if the Vector API is present, else scalar. */
    static FlockKernel create() {
        try {
            FlockKernel k = new SimdFlock();
            // force linkage so a missing incubator module fails here
            k.compute(0, new int[]{0, 1}, new double[]{0, 1}, new double[]{0, 1},
                    0.1, 0.1, 4.0, new double[2], new double[2]);
            return k;
        } catch (Throwable t) {
            return new ScalarFlock();
        }
    }

    static boolean simdAvailable() {
        try {
            Class.forName("jdk.incubator.vector.DoubleVector");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}

package dev.tachyon.engine;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * SIMD flocking via the JDK 25 incubator Vector API. The neighbour loop is the broadphase
 * bottleneck (O(region²)); here it processes {@code SPECIES_PREFERRED} neighbours per
 * iteration. Neighbour positions are <b>gathered</b> (entities aren't contiguous in their
 * region) and the separation radius test becomes a lane mask. Self is summed then
 * subtracted, and zero-distance lanes are masked out of the reciprocal.
 *
 * <p>Numerically equivalent to {@link ScalarFlock} within float/double tolerance
 * (different reduction order) — verified by {@code FlockParityTest}.
 */
final class SimdFlock implements FlockKernel {
    private static final VectorSpecies<Double> SP = DoubleVector.SPECIES_PREFERRED;
    private static final DoubleVector ONE = DoubleVector.broadcast(SP, 1.0);

    // Per-worker reusable packing buffers (no per-region allocation, no GC churn).
    private static final ThreadLocal<double[]> PACK_X = ThreadLocal.withInitial(() -> new double[0]);
    private static final ThreadLocal<double[]> PACK_Z = ThreadLocal.withInitial(() -> new double[0]);

    private static double[] buffer(ThreadLocal<double[]> tl, int n) {
        double[] a = tl.get();
        if (a.length < n) {
            a = new double[n];
            tl.set(a);
        }
        return a;
    }

    /**
     * Batched region compute. Packs the region's snapshot positions into contiguous
     * buffers once (O(m)), then each entity's neighbour loop uses contiguous vector
     * loads instead of gathers — the layout that lets SIMD beat scalar.
     */
    @Override
    public void computeRegion(int[] m, double[] sx, double[] sz,
                              double cohesion, double separation, double sepRadius2,
                              double[] outVx, double[] outVz) {
        final int len = m.length;
        final double[] px = buffer(PACK_X, len);
        final double[] pz = buffer(PACK_Z, len);
        for (int i = 0; i < len; i++) {
            px[i] = sx[m[i]];
            pz[i] = sz[m[i]];
        }

        final DoubleVector zeroV = DoubleVector.zero(SP);
        final DoubleVector sepR2V = DoubleVector.broadcast(SP, sepRadius2);
        final int bound = SP.loopBound(len);

        for (int a = 0; a < len; a++) {
            final double xi = px[a];
            final double zi = pz[a];
            final DoubleVector xiV = DoubleVector.broadcast(SP, xi);
            final DoubleVector ziV = DoubleVector.broadcast(SP, zi);
            DoubleVector sumX = zeroV, sumZ = zeroV, sepXv = zeroV, sepZv = zeroV;

            int b = 0;
            for (; b < bound; b += SP.length()) {
                DoubleVector xj = DoubleVector.fromArray(SP, px, b);  // contiguous load
                DoubleVector zj = DoubleVector.fromArray(SP, pz, b);
                sumX = sumX.add(xj);
                sumZ = sumZ.add(zj);
                DoubleVector dx = xiV.sub(xj);
                DoubleVector dz = ziV.sub(zj);
                DoubleVector d2 = dx.mul(dx).add(dz.mul(dz));
                VectorMask<Double> sepMask = d2.compare(VectorOperators.GT, zeroV)
                        .and(d2.compare(VectorOperators.LT, sepR2V));
                DoubleVector inv = ONE.div(d2.lanewise(VectorOperators.SQRT));
                sepXv = sepXv.add(dx.mul(inv), sepMask);
                sepZv = sepZv.add(dz.mul(inv), sepMask);
            }
            double centroidSumX = sumX.reduceLanes(VectorOperators.ADD);
            double centroidSumZ = sumZ.reduceLanes(VectorOperators.ADD);
            double sepX = sepXv.reduceLanes(VectorOperators.ADD);
            double sepZ = sepZv.reduceLanes(VectorOperators.ADD);
            for (; b < len; b++) {
                final double dx = xi - px[b];
                final double dz = zi - pz[b];
                final double d2 = dx * dx + dz * dz;
                centroidSumX += px[b];
                centroidSumZ += pz[b];
                if (d2 > 0 && d2 < sepRadius2) {
                    final double inv = 1.0 / Math.sqrt(d2);
                    sepX += dx * inv;
                    sepZ += dz * inv;
                }
            }

            final double centroidX = centroidSumX - xi;
            final double centroidZ = centroidSumZ - zi;
            final int cnt = len - 1;
            final int self = m[a];
            if (cnt > 0) {
                outVx[self] = cohesion * ((centroidX / cnt) - xi) + separation * sepX;
                outVz[self] = cohesion * ((centroidZ / cnt) - zi) + separation * sepZ;
            } else {
                outVx[self] = 0;
                outVz[self] = 0;
            }
        }
    }

    @Override
    public void compute(int self, int[] m, double[] sx, double[] sz,
                        double cohesion, double separation, double sepRadius2,
                        double[] outVx, double[] outVz) {
        final double xi = sx[self];
        final double zi = sz[self];
        final DoubleVector xiV = DoubleVector.broadcast(SP, xi);
        final DoubleVector ziV = DoubleVector.broadcast(SP, zi);
        final DoubleVector zeroV = DoubleVector.zero(SP);
        final DoubleVector sepR2V = DoubleVector.broadcast(SP, sepRadius2);

        DoubleVector sumX = zeroV, sumZ = zeroV, sepXv = zeroV, sepZv = zeroV;
        final int len = m.length;
        final int bound = SP.loopBound(len); // full vector blocks only

        int b = 0;
        for (; b < bound; b += SP.length()) {
            // unmasked gather: b+lane is always < len here, so members[] is never over-read
            DoubleVector xj = DoubleVector.fromArray(SP, sx, 0, m, b);
            DoubleVector zj = DoubleVector.fromArray(SP, sz, 0, m, b);

            sumX = sumX.add(xj);
            sumZ = sumZ.add(zj);

            DoubleVector dx = xiV.sub(xj);
            DoubleVector dz = ziV.sub(zj);
            DoubleVector d2 = dx.mul(dx).add(dz.mul(dz));

            VectorMask<Double> sepMask = d2.compare(VectorOperators.GT, zeroV)
                    .and(d2.compare(VectorOperators.LT, sepR2V));
            DoubleVector inv = ONE.div(d2.lanewise(VectorOperators.SQRT));
            sepXv = sepXv.add(dx.mul(inv), sepMask);
            sepZv = sepZv.add(dz.mul(inv), sepMask);
        }

        // self is summed (somewhere in m) then subtracted; separation excludes it via d2>0
        double centroidSumX = sumX.reduceLanes(VectorOperators.ADD);
        double centroidSumZ = sumZ.reduceLanes(VectorOperators.ADD);
        double sepX = sepXv.reduceLanes(VectorOperators.ADD);
        double sepZ = sepZv.reduceLanes(VectorOperators.ADD);

        // scalar tail (and the whole computation for regions smaller than one vector)
        for (; b < len; b++) {
            final int j = m[b];
            final double dx = xi - sx[j];
            final double dz = zi - sz[j];
            final double d2 = dx * dx + dz * dz;
            centroidSumX += sx[j];
            centroidSumZ += sz[j];
            if (d2 > 0 && d2 < sepRadius2) {
                final double inv = 1.0 / Math.sqrt(d2);
                sepX += dx * inv;
                sepZ += dz * inv;
            }
        }

        double centroidX = centroidSumX - xi;
        double centroidZ = centroidSumZ - zi;
        int cnt = len - 1;
        if (cnt > 0) {
            outVx[self] = cohesion * ((centroidX / cnt) - xi) + separation * sepX;
            outVz[self] = cohesion * ((centroidZ / cnt) - zi) + separation * sepZ;
        } else {
            outVx[self] = 0;
            outVz[self] = 0;
        }
    }

    @Override
    public String name() {
        return "simd(" + SP.length() + "x f64)";
    }
}

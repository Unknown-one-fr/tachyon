package dev.tachyon.engine;

/** Plain-Java flocking (cohesion + separation); the reference and SIMD-tail source of truth. */
final class ScalarFlock implements FlockKernel {

    @Override
    public void compute(int self, int[] members, double[] sx, double[] sz,
                        double cohesion, double separation, double sepRadius2,
                        double[] outVx, double[] outVz) {
        final double xi = sx[self];
        final double zi = sz[self];
        double centroidX = 0, centroidZ = 0, sepX = 0, sepZ = 0;
        int neighbours = 0;
        for (int b = 0; b < members.length; b++) {
            final int j = members[b];
            if (j == self) continue;
            final double dx = xi - sx[j];
            final double dz = zi - sz[j];
            final double d2 = dx * dx + dz * dz;
            centroidX += sx[j];
            centroidZ += sz[j];
            neighbours++;
            if (d2 > 0 && d2 < sepRadius2) {
                final double inv = 1.0 / Math.sqrt(d2);
                sepX += dx * inv;
                sepZ += dz * inv;
            }
        }
        if (neighbours > 0) {
            outVx[self] = cohesion * ((centroidX / neighbours) - xi) + separation * sepX;
            outVz[self] = cohesion * ((centroidZ / neighbours) - zi) + separation * sepZ;
        } else {
            outVx[self] = 0;
            outVz[self] = 0;
        }
    }

    @Override
    public void computeRegion(int[] members, double[] sx, double[] sz,
                              double cohesion, double separation, double sepRadius2,
                              double[] outVx, double[] outVz) {
        for (int a = 0; a < members.length; a++) {
            compute(members[a], members, sx, sz, cohesion, separation, sepRadius2, outVx, outVz);
        }
    }

    @Override
    public String name() {
        return "scalar";
    }
}

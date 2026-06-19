package dev.tachyon.soa;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The collision-broadphase R/W split is only safe if it produces the <b>identical</b> entity set
 * to vanilla. {@code RegionBroadphase} stores each entity's AABB into {@link EntitySoAStore} with
 * half-extents inflated by a movement margin, runs {@code queryBox} as a fast superset prune, then
 * re-filters that superset against the <em>live</em> (moved) boxes with the exact AABB test.
 *
 * <p>This pins both halves of that contract over randomized layouts <em>with movement</em>:
 * <ol>
 *   <li>the inflated {@code queryBox} is always a superset of the truly-intersecting live set, and</li>
 *   <li>re-filtering that superset by the exact live box reproduces a brute-force scan exactly.</li>
 * </ol>
 * Mirrors MC's {@code AABB.intersects} (strict {@code <}/{@code >}) for the exact test.
 */
class BroadphaseParityTest {

    private record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        boolean intersects(Box o) {
            return minX < o.maxX && maxX > o.minX
                    && minY < o.maxY && maxY > o.minY
                    && minZ < o.maxZ && maxZ > o.minZ;
        }
    }

    @Test
    void supersetPruneAndExactRefilterMatchBruteForce() {
        Random rng = new Random(20260619L);
        double margin = 16.0;

        for (int trial = 0; trial < 200; trial++) {
            int n = 1 + rng.nextInt(120);
            EntitySoAStore soa = new EntitySoAStore(n);
            List<Box> live = new ArrayList<>(n); // boxes after this-tick movement (what collisions see)

            for (int i = 0; i < n; i++) {
                // start-of-tick center + exact half-extents
                double cx = rng.nextDouble() * 200 - 100;
                double cy = rng.nextDouble() * 200 - 100;
                double cz = rng.nextDouble() * 200 - 100;
                float hx = (float) (0.1 + rng.nextDouble() * 2.0);
                float hy = (float) (0.1 + rng.nextDouble() * 2.0);
                float hz = (float) (0.1 + rng.nextDouble() * 2.0);

                // Phase R: store with half-extents inflated by the movement margin.
                soa.put(i, 0, cx, cy, cz, 0, 0, 0,
                        (float) (hx + margin), (float) (hy + margin), (float) (hz + margin), 0L);

                // "live" box = start box moved by up to ±margin per axis (bounded by the margin).
                double dx = (rng.nextDouble() * 2 - 1) * margin;
                double dy = (rng.nextDouble() * 2 - 1) * margin;
                double dz = (rng.nextDouble() * 2 - 1) * margin;
                double lcx = cx + dx, lcy = cy + dy, lcz = cz + dz;
                live.add(new Box(lcx - hx, lcy - hy, lcz - hz, lcx + hx, lcy + hy, lcz + hz));
            }

            int[] scratch = new int[n];
            for (int q = 0; q < 25; q++) {
                Box query = randomQuery(rng);

                // brute force: every live box that strictly intersects the query
                List<Integer> brute = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    if (live.get(i).intersects(query)) brute.add(i);
                }

                // SoA superset prune (inflated extents)
                int found = soa.queryBox(query.minX, query.minY, query.minZ,
                        query.maxX, query.maxY, query.maxZ, scratch);

                // (1) prune must be a superset of the brute-force set
                List<Integer> candidates = new ArrayList<>();
                for (int k = 0; k < found; k++) candidates.add(scratch[k]);
                for (int b : brute) {
                    assertTrue(candidates.contains(b),
                            "queryBox missed a truly-intersecting entity " + b + " (trial " + trial + ")");
                }

                // (2) exact re-filter of the superset must equal brute force
                List<Integer> refiltered = new ArrayList<>();
                for (int k = 0; k < found; k++) {
                    int i = scratch[k];
                    if (live.get(i).intersects(query)) refiltered.add(i);
                }
                refiltered.sort(Integer::compareTo);
                assertTrue(refiltered.equals(brute),
                        "refiltered set != brute force (trial " + trial + "): " + refiltered + " vs " + brute);
            }
        }
    }

    private static Box randomQuery(Random rng) {
        double cx = rng.nextDouble() * 200 - 100;
        double cy = rng.nextDouble() * 200 - 100;
        double cz = rng.nextDouble() * 200 - 100;
        double ex = 0.5 + rng.nextDouble() * 4.0;
        double ey = 0.5 + rng.nextDouble() * 4.0;
        double ez = 0.5 + rng.nextDouble() * 4.0;
        return new Box(cx - ex, cy - ey, cz - ez, cx + ex, cy + ey, cz + ez);
    }
}

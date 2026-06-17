package dev.tachyon.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Partitions the loaded-chunk set into independent {@link Region}s.
 *
 * <p>Two chunks belong to the same region if they are within {@code interactionRadius}
 * (Chebyshev distance, in chunks) of each other — chosen as the conservative upper
 * bound on anything that can cross a chunk boundary in one tick (entity movement +
 * tracking, explosions, piston/redstone reach, fluid/dispenser range). Regions are
 * therefore guaranteed not to interact within a single tick, so they can be ticked
 * fully in parallel.
 *
 * <p>This recomputes connected components by flood fill. For a handful of players
 * (~1–2k loaded chunks) that is well under 0.1 ms and only runs when the loaded set
 * actually changes, so an incremental union-find is an optimization, not a necessity.
 */
public final class RegionGraph {
    private final int interactionRadius;

    public RegionGraph(int interactionRadius) {
        this.interactionRadius = Math.max(1, interactionRadius);
    }

    public int interactionRadius() {
        return interactionRadius;
    }

    public List<Region> partition(Set<Long> loaded) {
        List<Region> regions = new ArrayList<>();
        if (loaded.isEmpty()) return regions;

        HashSet<Long> unvisited = new HashSet<>(loaded);
        ArrayDeque<Long> frontier = new ArrayDeque<>();
        ArrayList<Long> component = new ArrayList<>();
        int id = 0;

        while (!unvisited.isEmpty()) {
            long seed = unvisited.iterator().next();
            unvisited.remove(seed);
            frontier.add(seed);
            component.clear();

            while (!frontier.isEmpty()) {
                long cur = frontier.poll();
                component.add(cur);
                int cx = ChunkKey.x(cur);
                int cz = ChunkKey.z(cur);
                for (int dx = -interactionRadius; dx <= interactionRadius; dx++) {
                    for (int dz = -interactionRadius; dz <= interactionRadius; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        long nb = ChunkKey.of(cx + dx, cz + dz);
                        if (unvisited.remove(nb)) {
                            frontier.add(nb);
                        }
                    }
                }
            }

            long[] arr = new long[component.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = component.get(i);
            regions.add(new Region(id++, arr));
        }
        return regions;
    }
}

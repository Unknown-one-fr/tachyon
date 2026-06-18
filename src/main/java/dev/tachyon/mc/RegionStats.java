package dev.tachyon.mc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live region-partitioning stats per dimension, updated by the measure-mode mixin and
 * shown by {@code /tachyon regions}. "Ideal parallelism" = entities / largest-region —
 * an upper bound on the speedup the Mosaic engine could extract from this world right now.
 */
public final class RegionStats {
    private RegionStats() {}

    private static final Map<String, String> BY_DIMENSION = new ConcurrentHashMap<>();

    public static void update(String dimension, int regions, int entities, int maxRegionEntities) {
        double idealParallelism = maxRegionEntities > 0 ? (double) entities / maxRegionEntities : 0.0;
        BY_DIMENSION.put(dimension, String.format(
                "%s: %d entities across %d regions (largest %d/region -> ~%.1fx ideal parallelism)",
                dimension, entities, regions, maxRegionEntities, idealParallelism));
    }

    public static String summary() {
        if (BY_DIMENSION.isEmpty()) return "Tachyon regions: no data yet (measure mode warming up)";
        StringBuilder sb = new StringBuilder("Tachyon region partitioning (live):\n");
        BY_DIMENSION.values().forEach(line -> sb.append("  ").append(line).append('\n'));
        return sb.toString();
    }
}

package dev.tachyon.metrics;

import java.util.Arrays;
import java.util.Locale;

/**
 * Rolling window of recent whole-tick durations plus the last cycle's phase
 * breakdown (parallel / barrier / main). This is the "measure first" layer: every
 * later experiment is judged by its effect on these numbers, surfaced via
 * {@code /tachyon perf} and feeding the {@link dev.tachyon.govern.MsptGovernor}.
 */
public final class TickMetrics {
    private final long[] ring;
    private int idx;
    private int count;

    private volatile long parallelNs;
    private volatile long barrierNs;
    private volatile long mainNs;
    private volatile int regionCount;

    public TickMetrics(int window) {
        this.ring = new long[Math.max(20, window)];
    }

    public synchronized void recordTick(long tickNanos) {
        ring[idx] = tickNanos;
        idx = (idx + 1) % ring.length;
        if (count < ring.length) count++;
    }

    public void recordPhases(long parallel, long barrier, long main, int regions) {
        this.parallelNs = parallel;
        this.barrierNs = barrier;
        this.mainNs = main;
        this.regionCount = regions;
    }

    public synchronized double meanMspt() {
        if (count == 0) return 0;
        long sum = 0;
        for (int i = 0; i < count; i++) sum += ring[i];
        return (sum / (double) count) / 1_000_000.0;
    }

    public synchronized double percentileMspt(double p) {
        if (count == 0) return 0;
        long[] copy = Arrays.copyOf(ring, count);
        Arrays.sort(copy);
        int k = (int) Math.ceil(p * count) - 1;
        if (k < 0) k = 0;
        if (k >= count) k = count - 1;
        return copy[k] / 1_000_000.0;
    }

    public int samples() {
        return count;
    }

    public synchronized Snapshot snapshot() {
        if (count == 0) {
            return new Snapshot(0, 0, 0, 0, 0,
                    parallelNs / 1e6, barrierNs / 1e6, mainNs / 1e6, regionCount);
        }

        long[] copy = Arrays.copyOf(ring, count);
        Arrays.sort(copy);
        long sum = 0;
        for (long v : copy) {
            sum += v;
        }
        return new Snapshot(
                (sum / (double) count) / 1_000_000.0,
                percentileFromSorted(copy, 0.95),
                percentileFromSorted(copy, 0.99),
                copy[count - 1] / 1_000_000.0,
                count,
                parallelNs / 1e6,
                barrierNs / 1e6,
                mainNs / 1e6,
                regionCount);
    }

    /** Clear the rolling window (e.g. after toggling a subsystem, for a clean measurement). */
    public synchronized void reset() {
        idx = 0;
        count = 0;
        Arrays.fill(ring, 0L);
    }

    public String summary() {
        Snapshot s = snapshot();
        return String.format(
                Locale.ROOT,
                "MSPT mean=%.2f  p95=%.2f  p99=%.2f  (n=%d)%n" +
                "phases(ms): parallel=%.2f  barrier=%.2f  main=%.2f  regions=%d",
                s.meanMspt(), s.p95Mspt(), s.p99Mspt(), s.samples(),
                s.parallelMs(), s.barrierMs(), s.mainMs(), s.regionCount());
    }

    private static double percentileFromSorted(long[] sorted, double p) {
        int k = (int) Math.ceil(p * sorted.length) - 1;
        if (k < 0) k = 0;
        if (k >= sorted.length) k = sorted.length - 1;
        return sorted[k] / 1_000_000.0;
    }

    public record Snapshot(
            double meanMspt,
            double p95Mspt,
            double p99Mspt,
            double maxMspt,
            int samples,
            double parallelMs,
            double barrierMs,
            double mainMs,
            int regionCount) {

        public double estimatedTps() {
            if (meanMspt <= 0) {
                return 20.0;
            }
            return Math.min(20.0, 1000.0 / meanMspt);
        }

        public String oneLine() {
            return String.format(Locale.ROOT,
                    "MSPT mean=%.2f p95=%.2f p99=%.2f max=%.2f TPS=%.2f n=%d phases(ms) parallel=%.2f barrier=%.2f main=%.2f regions=%d",
                    meanMspt, p95Mspt, p99Mspt, maxMspt, estimatedTps(), samples,
                    parallelMs, barrierMs, mainMs, regionCount);
        }
    }
}

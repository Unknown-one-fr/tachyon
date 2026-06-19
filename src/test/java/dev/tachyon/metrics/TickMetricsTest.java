package dev.tachyon.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickMetricsTest {

    @Test
    void snapshotIncludesTailStatsAndPhaseBreakdown() {
        TickMetrics metrics = new TickMetrics(20);
        metrics.recordTick(50_000_000L);
        metrics.recordTick(100_000_000L);
        metrics.recordTick(25_000_000L);
        metrics.recordPhases(10_000_000L, 2_000_000L, 5_000_000L, 7);

        TickMetrics.Snapshot snapshot = metrics.snapshot();

        assertEquals(3, snapshot.samples());
        assertEquals(58.33, snapshot.meanMspt(), 0.01);
        assertEquals(100.0, snapshot.p95Mspt(), 0.01);
        assertEquals(100.0, snapshot.p99Mspt(), 0.01);
        assertEquals(100.0, snapshot.maxMspt(), 0.01);
        assertEquals(17.14, snapshot.estimatedTps(), 0.01);
        assertEquals(10.0, snapshot.parallelMs(), 0.01);
        assertEquals(2.0, snapshot.barrierMs(), 0.01);
        assertEquals(5.0, snapshot.mainMs(), 0.01);
        assertEquals(7, snapshot.regionCount());
        assertTrue(snapshot.oneLine().contains("MSPT mean=58.33"));
    }
}

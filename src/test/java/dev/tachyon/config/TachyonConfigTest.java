package dev.tachyon.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TachyonConfigTest {

    @Test
    void defaultsKeepDisabledBaselineQuietButRecorded() {
        TachyonConfig config = new TachyonConfig();

        assertFalse(config.parallelTakeoverEnabled());
        assertFalse(config.measureWhenDisabled, "region scans should be opt-in when takeover is disabled");
        assertEquals(100, config.measureIntervalTicks);
        assertEquals(200, config.metricsLogIntervalTicks);
        assertTrue(config.metricsCsv);
    }
}

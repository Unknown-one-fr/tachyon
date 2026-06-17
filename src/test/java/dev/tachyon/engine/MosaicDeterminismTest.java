package dev.tachyon.engine;

import dev.tachyon.core.RegionScheduler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The central correctness claim of the engine: snapshot-read + single-writer regions
 * make parallel ticking <em>bitwise-identical</em> to serial, regardless of thread
 * scheduling. If this holds, regionized parallelism is safe.
 */
class MosaicDeterminismTest {

    @Test
    void parallelEqualsSerialBitwise() {
        EntityWorld serial = new EntityWorld(800, 500, 100, 12345L);
        EntityWorld parallel = serial.copy();

        // sanity: identical starting state and partition
        assertArrayEquals(serial.x, parallel.x, 0.0);
        assertEquals(serial.regionCount(), parallel.regionCount());

        MosaicTicker ticker = new MosaicTicker();
        RegionScheduler scheduler = new RegionScheduler(8);
        try {
            long serialTotal = 0, parallelTotal = 0;
            for (int tick = 0; tick < 30; tick++) {
                serialTotal = ticker.tickSerial(serial);
                parallelTotal = ticker.tickParallel(parallel, scheduler);
                // diverge-check every tick so a failure points at the first bad tick
                assertArrayEquals(serial.x, parallel.x, 0.0, "x diverged at tick " + tick);
                assertArrayEquals(serial.z, parallel.z, 0.0, "z diverged at tick " + tick);
            }
            // cross-region barrier summed every entity exactly once, deterministically
            assertEquals(serial.n, parallelTotal, "barrier total should equal entity count");
            assertEquals(serialTotal, parallelTotal);
        } finally {
            scheduler.close();
        }
    }

    @Test
    void everyEntityGetsTickedAcrossThreads() {
        EntityWorld w = new EntityWorld(2000, 800, 100, 7L);
        MosaicTicker ticker = new MosaicTicker();
        RegionScheduler scheduler = new RegionScheduler(8);
        try {
            long total = ticker.tickParallel(w, scheduler);
            assertEquals(w.n, total);
        } finally {
            scheduler.close();
        }
    }
}

package dev.tachyon.engine;

import dev.tachyon.core.RegionScheduler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live cross-region migration: with a drift applied, entities flow across region
 * borders and are re-homed by {@link EntityWorld#repartition()} between ticks. The
 * engine must keep parallel == serial bitwise throughout, and real migration must
 * actually occur (not a no-op).
 */
class MigrationTest {

    @Test
    void entitiesMigrateAndStayDeterministic() {
        EntityWorld serial = new EntityWorld(600, 500, 50, 99L);
        serial.setDrift(1.5, 0.5);
        EntityWorld parallel = serial.copy();

        int[] initialRegion = new int[serial.n];
        for (int i = 0; i < serial.n; i++) initialRegion[i] = serial.regionOf(i);

        MosaicTicker ticker = new MosaicTicker();
        RegionScheduler scheduler = new RegionScheduler(8);
        try {
            for (int tick = 0; tick < 50; tick++) {
                serial.repartition();
                parallel.repartition();
                ticker.tickSerial(serial);
                ticker.tickParallel(parallel, scheduler);
                assertArrayEquals(serial.x, parallel.x, 0.0, "x diverged at tick " + tick);
                assertArrayEquals(serial.z, parallel.z, 0.0, "z diverged at tick " + tick);
            }
        } finally {
            scheduler.close();
        }

        int migrated = 0;
        for (int i = 0; i < serial.n; i++) {
            if (serial.regionOf(i) != initialRegion[i]) migrated++;
        }
        assertTrue(migrated > serial.n / 4,
                "expected substantial cross-region migration, got " + migrated + "/" + serial.n);
    }
}

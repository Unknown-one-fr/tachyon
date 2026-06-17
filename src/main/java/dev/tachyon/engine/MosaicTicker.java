package dev.tachyon.engine;

import dev.tachyon.core.Region;
import dev.tachyon.core.RegionContext;
import dev.tachyon.core.RegionScheduler;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The Mosaic engine orchestrator. Drives a {@link RegionizedTickWorld} through one
 * tick — Phase R then Phase W per region — either serially or in parallel on a
 * {@link RegionScheduler}. It holds no world state and no behaviour: those live in the
 * world adapter, so this same class will drive real Minecraft entities unchanged.
 *
 * <p><b>Determinism:</b> both paths call the world's identical {@code computeIntent}
 * (reads the pre-tick snapshot) and {@code applyIntent} (writes only region-owned
 * slots). Phase R never reads live state, so a region doing Phase W concurrently with
 * another region's Phase R cannot perturb it. Result: parallel output is bitwise-equal
 * to serial regardless of scheduling — proven in {@code MosaicDeterminismTest}.
 */
public final class MosaicTicker {

    /** Single-threaded reference path (deterministic region order). */
    public long tickSerial(RegionizedTickWorld w) {
        w.beginTick();
        long total = 0;
        for (Region r : w.regions()) {
            int[] m = w.members(r.id);
            w.computeRegion(r.id);                 // Phase R (batched)
            for (int s : m) w.applyIntent(s);      // Phase W
            total += m.length;
        }
        w.endTick();
        return total;
    }

    /** Parallel path: regions ticked on the scheduler; identical math to {@link #tickSerial}. */
    public long tickParallel(RegionizedTickWorld w, RegionScheduler scheduler) {
        w.beginTick();
        AtomicLong total = new AtomicLong();
        scheduler.tick(w.regions(), region -> {
            assert RegionContext.current() == region;      // single-writer invariant
            int[] m = w.members(region.id);
            w.computeRegion(region.id);                    // Phase R (parallel, read-only)
            for (int s : m) w.applyIntent(s);              // Phase W (single-writer)
            final int count = m.length;
            region.post(() -> total.addAndGet(count));     // commutative cross-region post
        });
        w.endTick();
        return total.get();
    }
}

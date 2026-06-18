package dev.tachyon.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the cooperative main-thread dispatcher resolves the parallel-tick deadlock.
 *
 * <p>The deadlock the experimental takeover hit: region work on a worker thread needs the main
 * thread mid-tick, but the main thread is blocked waiting for the parallel phase, so neither can
 * proceed. {@link RegionScheduler} now pumps the dispatcher while it waits, so the worker's hop is
 * serviced and progress is always possible. A bare {@code join()} scheduler would hang this test
 * forever — hence the preemptive timeout, which turns any regression back into a fast failure.
 */
class MainThreadDispatcherTest {

    @Test
    void workerHopToMainCompletesWithoutDeadlock() {
        final MainThreadDispatcher d = MainThreadDispatcher.INSTANCE;

        final List<Region> regions = new ArrayList<>();
        for (int i = 0; i < 16; i++) regions.add(new Region(i, new long[]{i}));

        final AtomicInteger ranOnMain = new AtomicInteger();
        final AtomicInteger sum = new AtomicInteger();
        final AtomicBoolean tickedOffMain = new AtomicBoolean(false);

        final RegionScheduler scheduler = new RegionScheduler(4);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                // The thread that drives the tick is the one that owns shared state.
                final Thread main = Thread.currentThread();
                d.setMainThread(main);

                scheduler.tick(regions, region -> {
                    // Region body runs on a worker; simulate vanilla needing the server thread.
                    if (Thread.currentThread() != main) {
                        tickedOffMain.set(true);
                    }
                    int v = d.call(() -> {
                        if (Thread.currentThread() == main) ranOnMain.incrementAndGet();
                        return region.id * 2;          // "work" that must run on main
                    });
                    sum.addAndGet(v);
                });

                // Every hop must have executed on the registered main thread.
                assertEquals(16, ranOnMain.get(), "all main-thread hops should run on main");
                // sum of id*2 for id 0..15 = 2 * 120 = 240
                assertEquals(240, sum.get(), "each region's main-thread result must be applied once");
                assertTrue(tickedOffMain.get(), "regions should have ticked on worker threads");
            });
        } finally {
            scheduler.close();
        }
    }

    @Test
    void callOnMainRunsInline() {
        MainThreadDispatcher d = MainThreadDispatcher.INSTANCE;
        d.setMainThread(Thread.currentThread());
        long before = d.servicedCount();

        int v = d.call(() -> 42);
        AtomicInteger ran = new AtomicInteger();
        d.execute(ran::incrementAndGet);

        assertEquals(42, v);
        assertEquals(1, ran.get(), "execute on main should run inline");
        assertEquals(before, d.servicedCount(), "inline calls must not go through the queue");
    }

    @Test
    void callRethrowsBodyException() {
        final MainThreadDispatcher d = MainThreadDispatcher.INSTANCE;
        final List<Region> regions = List.of(new Region(0, new long[]{0}));
        final RegionScheduler scheduler = new RegionScheduler(2);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                d.setMainThread(Thread.currentThread());
                // A worker hop whose body throws must surface the failure to the worker, which
                // the scheduler then propagates to the caller via job.join().
                assertThrows(RuntimeException.class, () ->
                        scheduler.tick(regions, region ->
                                d.call(() -> { throw new RuntimeException("boom"); })));
            });
        } finally {
            scheduler.close();
        }
    }
}

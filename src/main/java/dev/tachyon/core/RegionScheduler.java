package dev.tachyon.core;

import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.function.Consumer;

/**
 * Ticks regions in parallel on a work-stealing {@link ForkJoinPool}, then runs the
 * tick barrier on the calling (main) thread.
 *
 * <p>Each region's tick function runs entirely on one worker (serial-within-region)
 * with the region bound as the current {@link RegionContext}. Because regions are
 * provably non-interacting (see {@link RegionGraph}) and single-writer, no locks on
 * world data are needed during the parallel phase. Cross-region effects are deferred
 * to the barrier where they run single-threaded in deterministic region order.
 *
 * <p><b>Cooperative wait (no deadlock).</b> The calling (main) thread does <em>not</em> simply
 * block in {@code join()} while workers run — a worker that needs the main thread (chunk
 * futures, {@code managedBlock}, global managers) would then wait forever on a main thread that
 * is itself waiting. Instead the main thread runs a managed wait: it {@link MainThreadDispatcher#pump()}s
 * worker→main requests until the parallel phase completes. This is what makes parallel ticking of
 * code that still occasionally needs the main thread possible at all.
 *
 * <p>Parallelism is fixed per instance (the JDK pool does not resize cleanly); the
 * governor changes width by asking the engine to swap in a new scheduler.
 */
public final class RegionScheduler implements AutoCloseable {
    private final ForkJoinPool pool;
    private final DeferredBarrier barrier = new DeferredBarrier();
    private final int parallelism;

    private volatile long lastParallelNs;
    private volatile long lastBarrierNs;

    public RegionScheduler(int parallelism) {
        this.parallelism = Math.max(1, parallelism);
        this.pool = new ForkJoinPool(
                this.parallelism,
                ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                (t, e) -> System.err.println("[Tachyon] region worker crashed: " + e),
                /* asyncMode = */ false);
    }

    public int parallelism() {
        return parallelism;
    }

    public DeferredBarrier barrier() {
        return barrier;
    }

    public long lastParallelNanos() {
        return lastParallelNs;
    }

    public long lastBarrierNanos() {
        return lastBarrierNs;
    }

    /**
     * Run one parallel tick over all regions, then the barrier. Blocks until complete.
     *
     * @param regions the regions to tick this cycle
     * @param tickFn  the per-region work (runs on a worker, region bound as context)
     */
    public void tick(List<Region> regions, Consumer<Region> tickFn) {
        if (regions.isEmpty()) {
            return;
        }

        long t0 = System.nanoTime();
        // Submitting the parallelStream into our pool makes the stream use THIS pool's
        // workers (not the common pool). We do NOT bare-join: a worker may need the main
        // thread mid-tick, so the main thread pumps the dispatcher while it waits (below).
        final MainThreadDispatcher dispatcher = MainThreadDispatcher.INSTANCE;
        ForkJoinTask<?> job = pool.submit(() -> {
            try {
                regions.parallelStream().forEach(region -> {
                    region.owner = Thread.currentThread();
                    try {
                        RegionContext.runIn(region, () -> tickFn.accept(region));
                    } finally {
                        region.owner = null;
                    }
                });
            } finally {
                dispatcher.wake(); // unblock the managed wait the instant the phase ends
            }
        });

        // Managed wait: service worker→main requests until the parallel phase is done.
        dispatcher.pumpUntil(job::isDone);
        job.join();         // propagate worker exceptions to the main thread
        long t1 = System.nanoTime();

        // Barrier: single-threaded, deterministic order. Cross-region inboxes first
        // (so an entity handed off this tick is materialized), then global tasks.
        for (Region r : regions) {
            r.drainInbox();
        }
        barrier.drain();
        long t2 = System.nanoTime();

        lastParallelNs = t1 - t0;
        lastBarrierNs = t2 - t1;
    }

    @Override
    public void close() {
        pool.shutdown();
    }
}

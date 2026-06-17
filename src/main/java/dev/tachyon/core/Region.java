package dev.tachyon.core;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A maximal set of loaded chunks that can interact within a single tick.
 *
 * <p>Invariant that makes the whole engine safe: a region is ticked by exactly
 * one thread at a time (single-writer). Anything that needs to touch a
 * <em>different</em> region must not write it directly — it posts a task to that
 * region's {@link #inbox}, which is drained on the owning thread at the tick
 * barrier. This single-writer rule is what lets us drop locks on world data and
 * structurally remove the ConcurrentHashMap contention the Async mod suffered.
 */
public final class Region {
    public final int id;
    public final long[] chunks;

    /** The worker currently ticking this region, or null. Informational/asserts. */
    volatile Thread owner;

    /** Cross-region work targeted at this region, applied at the barrier. */
    final ConcurrentLinkedQueue<Runnable> inbox = new ConcurrentLinkedQueue<>();

    private final AtomicLong crossRegionPosts = new AtomicLong();

    public Region(int id, long[] chunks) {
        this.id = id;
        this.chunks = chunks;
    }

    public int chunkCount() {
        return chunks.length;
    }

    /** Post work to be executed against this region at the next barrier. */
    public void post(Runnable task) {
        crossRegionPosts.incrementAndGet();
        inbox.add(task);
    }

    /** Drain queued cross-region work. Must be called single-threaded (at the barrier). */
    int drainInbox() {
        int n = 0;
        Runnable r;
        while ((r = inbox.poll()) != null) {
            r.run();
            n++;
        }
        return n;
    }

    public long crossRegionPosts() {
        return crossRegionPosts.get();
    }

    public boolean ownedByCurrentThread() {
        return owner == Thread.currentThread();
    }

    @Override
    public String toString() {
        return "Region#" + id + "[" + chunks.length + " chunks]";
    }
}

package dev.tachyon.core;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Collects mutations that must run on the main server thread <em>after</em> the
 * parallel region phase — e.g. adding/removing entities from the global world
 * managers, cross-dimension travel, or anything that touches shared server state
 * not owned by any single region. Drained once per tick at the barrier in a
 * single-threaded, deterministic pass.
 */
public final class DeferredBarrier {
    private final ConcurrentLinkedQueue<Runnable> mainThreadTasks = new ConcurrentLinkedQueue<>();

    public void runOnMain(Runnable task) {
        mainThreadTasks.add(task);
    }

    /** Execute and clear all queued main-thread tasks. Returns how many ran. */
    public int drain() {
        int n = 0;
        Runnable r;
        while ((r = mainThreadTasks.poll()) != null) {
            r.run();
            n++;
        }
        return n;
    }

    public boolean isEmpty() {
        return mainThreadTasks.isEmpty();
    }
}

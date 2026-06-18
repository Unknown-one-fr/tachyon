package dev.tachyon.core;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Cooperative main-thread executor — the primitive that breaks the parallel-tick deadlock.
 *
 * <p><b>The problem it solves.</b> When region ticks run on worker threads, vanilla code deep
 * in the call stack still needs the <em>server (main) thread</em> for some operations — chunk
 * futures chained onto the server's main executor, {@code managedBlock} waits, global manager
 * mutations. If the main thread simply blocks in {@code join()} waiting for the parallel phase
 * (as a naive scheduler does), those worker→main requests can never be serviced and both sides
 * wait forever (the deadlock the experimental takeover hit; watchdog kill at 60s).
 *
 * <p><b>The fix.</b> A worker that needs the main thread calls {@link #call}/{@link #execute},
 * which enqueues the work and (for {@code call}) blocks <em>only that worker</em> on the result.
 * Blocking a worker is fine; blocking the main thread without servicing the queue is not. The
 * main thread therefore never does a bare {@code join()}: it {@link #pump()}s this queue while it
 * waits for the parallel phase to finish (see {@link RegionScheduler}). Progress is always
 * possible, so the deadlock cannot form.
 *
 * <p>This is the same cooperative-scheduling shape Folia uses for its "global region" hops. It is
 * the load-bearing seam for per-region world isolation: any vanilla touch that must stay on the
 * main thread gets routed here instead of executing illegally on a worker.
 *
 * <p><b>Ordering.</b> Tasks run on the main thread in arrival order. Cross-region <em>ordered</em>
 * effects must still go through {@link Region#post} / the barrier, which run in deterministic
 * region order; this dispatcher is for point operations whose main-thread interleaving does not
 * affect per-region results (independent chunks/state). See the determinism note on the engine.
 */
public final class MainThreadDispatcher {

    /** Process-wide dispatcher. The engine registers the server thread on it at startup. */
    public static final MainThreadDispatcher INSTANCE = new MainThreadDispatcher();

    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final Object signal = new Object();
    private final AtomicLong serviced = new AtomicLong();

    private volatile Thread mainThread;

    /** Bind the thread that owns shared server state (the server tick thread). */
    public void setMainThread(Thread t) {
        this.mainThread = t;
    }

    public boolean isMainThread() {
        return Thread.currentThread() == mainThread;
    }

    /** True once a main thread has been registered. */
    public boolean isBound() {
        return mainThread != null;
    }

    public long servicedCount() {
        return serviced.get();
    }

    /**
     * Run {@code task} on the main thread, fire-and-forget. If the caller already <em>is</em> the
     * main thread it runs inline (so callers need no special-casing). Otherwise it is queued and
     * picked up by the next {@link #pump()}.
     */
    public void execute(Runnable task) {
        if (isMainThread() || mainThread == null) {
            task.run();
            return;
        }
        queue.add(task);
        wake();
    }

    /**
     * Run {@code body} on the main thread and return its result. Inline if already on main;
     * otherwise the calling (worker) thread blocks until the main thread pumps it. Exceptions
     * thrown by {@code body} are rethrown to the caller.
     */
    public <T> T call(Supplier<T> body) {
        if (isMainThread() || mainThread == null) {
            return body.get();
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        queue.add(() -> {
            try {
                result.complete(body.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        wake();
        return result.join(); // blocks this worker only; main keeps pumping
    }

    /** Execute every queued task on the calling (main) thread. Returns how many ran. */
    public int pump() {
        int n = 0;
        Runnable r;
        while ((r = queue.poll()) != null) {
            r.run();
            n++;
        }
        if (n > 0) {
            serviced.addAndGet(n);
        }
        return n;
    }

    /**
     * Pump worker→main requests until {@code done} reports true, then pump once more. The main
     * thread calls this for the whole parallel phase: it services requests, and sleeps (briefly)
     * only when both the queue is empty and {@code done} is still false.
     *
     * <p>The {@code done} predicate is re-checked while holding the wait monitor, and {@link #wake()}
     * also takes that monitor, so there is no lost-wakeup window: a completion that fires {@code done}
     * cannot slip in between the check and the {@code wait}. The 1ms timeout is a belt-and-suspenders
     * ceiling, not the normal wake path.
     */
    public void pumpUntil(BooleanSupplier done) {
        while (!done.getAsBoolean()) {
            pump();
            synchronized (signal) {
                if (!queue.isEmpty() || done.getAsBoolean()) {
                    continue; // more to pump, or finished — don't sleep
                }
                try {
                    signal.wait(1L); // ≤1ms safety ceiling; normally woken by wake()
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        pump(); // drain anything posted just before completion
    }

    /** Wake the main thread out of {@link #pumpUntil} (e.g. when the parallel phase completes). */
    public void wake() {
        synchronized (signal) {
            signal.notifyAll();
        }
    }
}

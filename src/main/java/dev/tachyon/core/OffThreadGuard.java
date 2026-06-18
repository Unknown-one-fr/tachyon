package dev.tachyon.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tripwire for unsafe off-main access during parallel region ticks.
 *
 * <p>Per-region world isolation is reached incrementally: we let regions tick on workers, then
 * hunt down every place where vanilla code reaches into state that is <em>not</em> owned by the
 * ticking region (global managers, neighbouring chunks, scheduled-tick lists). Each such touch
 * must be re-routed — to {@link Region#post}/the barrier for ordered cross-region effects, or to
 * {@link MainThreadDispatcher} for genuinely main-thread-only operations.
 *
 * <p>This guard makes those touches <em>visible</em> instead of manifesting as a mysterious
 * deadlock or corruption. Instrumented call sites call {@link #requireMain(String)}; depending on
 * {@link #mode}:
 * <ul>
 *   <li>{@link Mode#OFF} — zero overhead, no checking (production default).</li>
 *   <li>{@link Mode#WARN} — count + log each distinct off-main site once (discovery mode).</li>
 *   <li>{@link Mode#STRICT} — throw immediately, so a test/dev run pinpoints the exact stack.</li>
 * </ul>
 *
 * <p>"Main" here means the {@link MainThreadDispatcher}-registered server thread. Calls made
 * before a main thread is bound, or on the main thread itself, always pass.
 */
public final class OffThreadGuard {

    public enum Mode { OFF, WARN, STRICT }

    private static volatile Mode mode = Mode.OFF;
    private static final AtomicLong violations = new AtomicLong();
    private static final ConcurrentHashMap<String, Boolean> seenSites = new ConcurrentHashMap<>();

    private OffThreadGuard() {}

    public static void setMode(Mode m) {
        mode = m;
    }

    public static Mode mode() {
        return mode;
    }

    public static long violationCount() {
        return violations.get();
    }

    /** Forget recorded sites (e.g. between test cases or dev sessions). */
    public static void reset() {
        violations.set(0);
        seenSites.clear();
    }

    /**
     * Assert that the current thread may touch shared/main-only state for operation {@code op}.
     * Passes on the main thread (or when no main thread is bound, or when {@link Mode#OFF}).
     * In {@link Mode#STRICT} an off-main call throws {@link OffThreadAccessException}.
     *
     * @return {@code true} if the access is on the main thread (so callers can branch to an
     *         inline fast path vs. a {@link MainThreadDispatcher} hop)
     */
    public static boolean requireMain(String op) {
        Mode m = mode;
        if (m == Mode.OFF) {
            return true; // unchecked: assume caller knows the context
        }
        MainThreadDispatcher d = MainThreadDispatcher.INSTANCE;
        if (!d.isBound() || d.isMainThread()) {
            return true;
        }
        violations.incrementAndGet();
        if (m == Mode.STRICT) {
            throw new OffThreadAccessException(op);
        }
        // WARN: log each distinct site once to avoid flooding the tick loop.
        if (seenSites.putIfAbsent(op, Boolean.TRUE) == null) {
            System.err.println("[Tachyon] off-main access during region tick: " + op
                    + " (thread=" + Thread.currentThread().getName() + ")");
        }
        return false;
    }

    /** Thrown by {@link #requireMain} in {@link Mode#STRICT} when shared state is touched off-main. */
    public static final class OffThreadAccessException extends IllegalStateException {
        public OffThreadAccessException(String op) {
            super("Tachyon: off-main access during region tick: " + op
                    + " (thread=" + Thread.currentThread().getName() + ")");
        }
    }
}

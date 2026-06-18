package dev.tachyon.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The off-main access tripwire: passes on the registered main thread, and in STRICT/WARN flags
 * access from any other thread — the mechanism we use to locate unsafe shared-state touches while
 * building per-region isolation.
 */
class OffThreadGuardTest {

    @BeforeEach
    void bind() {
        MainThreadDispatcher.INSTANCE.setMainThread(Thread.currentThread());
        OffThreadGuard.reset();
    }

    @AfterEach
    void unbind() {
        OffThreadGuard.setMode(OffThreadGuard.Mode.OFF);
        OffThreadGuard.reset();
    }

    private static Throwable runOffMain(Runnable r) throws InterruptedException {
        AtomicReference<Throwable> caught = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                r.run();
            } catch (Throwable ex) {
                caught.set(ex);
            }
        }, "off-main-probe");
        t.start();
        t.join();
        return caught.get();
    }

    @Test
    void strictPassesOnMainThrowsOffMain() throws InterruptedException {
        OffThreadGuard.setMode(OffThreadGuard.Mode.STRICT);

        assertTrue(OffThreadGuard.requireMain("on-main"), "main thread access must pass");
        assertEquals(0, OffThreadGuard.violationCount());

        Throwable ex = runOffMain(() -> OffThreadGuard.requireMain("off-main"));
        assertNotNull(ex, "off-main STRICT access must throw");
        assertInstanceOf(OffThreadGuard.OffThreadAccessException.class, ex);
        assertEquals(1, OffThreadGuard.violationCount());
    }

    @Test
    void warnCountsButDoesNotThrow() throws InterruptedException {
        OffThreadGuard.setMode(OffThreadGuard.Mode.WARN);

        AtomicBoolean returned = new AtomicBoolean(true);
        Throwable ex = runOffMain(() -> {
            // same op twice: both count, only one log line
            returned.set(OffThreadGuard.requireMain("dup-site"));
            OffThreadGuard.requireMain("dup-site");
        });

        assertNull(ex, "WARN must not throw");
        assertFalse(returned.get(), "off-main WARN access should report not-on-main (false)");
        assertEquals(2, OffThreadGuard.violationCount(), "every off-main hit counts");
    }

    @Test
    void offModeIsUncheckedAndCountsNothing() throws InterruptedException {
        OffThreadGuard.setMode(OffThreadGuard.Mode.OFF);

        AtomicBoolean ok = new AtomicBoolean(false);
        Throwable ex = runOffMain(() -> ok.set(OffThreadGuard.requireMain("ignored")));

        assertNull(ex);
        assertTrue(ok.get(), "OFF mode always passes (unchecked)");
        assertEquals(0, OffThreadGuard.violationCount());
    }
}

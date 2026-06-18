package dev.tachyon.mc;

import dev.tachyon.TachyonMod;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Read-write serialization of per-level entity storage during intra-level regionized ticking.
 *
 * <p>Region AI/physics tick in parallel, but they share one level's entity structures:
 * <ul>
 *   <li><b>Writes</b> — entity add/remove/section-move structurally mutate the {@code EntityTickList}
 *       map and the {@code PersistentEntitySectionManager} (UUID set, section storage, tracking and
 *       ticking registration). Concurrent mutation corrupts them (observed: AIOOBE in the fastutil
 *       map rehash when two mobs die on different workers).</li>
 *   <li><b>Reads</b> — {@code getEntities}/AABB queries (target selection, collision, sensors) read
 *       the entity lookup + section storage on every worker, constantly. A read concurrent with a
 *       structural write sees a half-updated map.</li>
 * </ul>
 *
 * <p>A single {@link ReentrantReadWriteLock} resolves both: many workers hold the <em>read</em> lock
 * at once (queries stay parallel), while a lifecycle mutation takes the exclusive <em>write</em> lock
 * and briefly excludes readers. Lifecycle events are rare relative to per-tick AI cost, so writes are
 * infrequent and contention is low. Reentrant, and a write holder may take the read lock (downgrade),
 * so a mutation that queries entities won't self-deadlock. (A read holder must not acquire the write
 * lock — vanilla entity queries never mutate storage mid-iteration, so this does not arise.)
 *
 * <p>Engaged only while intra-level mode is active; the vanilla/per-level paths pay nothing.
 */
public final class EntityLifecycleLock {
    private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock();

    private EntityLifecycleLock() {}

    public static boolean engaged() {
        return TachyonMod.config != null && TachyonMod.config.intraLevel;
    }

    public static void lockWrite() {
        LOCK.writeLock().lock();
    }

    public static void unlockWrite() {
        LOCK.writeLock().unlock();
    }

    public static void lockRead() {
        LOCK.readLock().lock();
    }

    public static void unlockRead() {
        LOCK.readLock().unlock();
    }
}

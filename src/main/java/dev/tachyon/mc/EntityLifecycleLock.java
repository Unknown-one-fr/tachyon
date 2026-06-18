package dev.tachyon.mc;

import dev.tachyon.TachyonMod;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Coarse serialization of per-level entity <em>lifecycle</em> mutations during intra-level
 * regionized ticking.
 *
 * <p>Region AI/physics can tick in parallel, but entity add/remove (death, despawn, spawned
 * drops/projectiles, ticking-section transitions) structurally mutate per-level storage that is
 * <em>shared</em> across regions — the non-thread-safe {@code EntityTickList} map and the
 * {@code PersistentEntitySectionManager}. Concurrent mutation corrupts them (observed: AIOOBE in
 * the fastutil map rehash when two mobs die on different workers in the same tick).
 *
 * <p>These lifecycle events are infrequent relative to the per-tick AI cost, so guarding just them
 * with a single reentrant lock keeps the expensive ticking parallel while making the structural
 * mutations safe. Reentrant so a section-manager transition that calls back into the tick list
 * doesn't self-deadlock. Only engaged while intra-level mode is active, so vanilla/per-level paths
 * pay nothing.
 */
public final class EntityLifecycleLock {
    private static final ReentrantLock LOCK = new ReentrantLock();

    private EntityLifecycleLock() {}

    public static boolean engaged() {
        return TachyonMod.config != null && TachyonMod.config.intraLevel;
    }

    public static void lock() {
        LOCK.lock();
    }

    public static void unlock() {
        LOCK.unlock();
    }
}

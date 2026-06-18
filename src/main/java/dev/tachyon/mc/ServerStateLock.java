package dev.tachyon.mc;

import dev.tachyon.TachyonMod;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Read-write serialization of server-<em>global</em> mutable state (the scoreboard) during the
 * parallel takeover.
 *
 * <p>Unlike per-level state, the {@code Scoreboard} is one object shared by every dimension, so it
 * is touched by region/level workers across all dimensions at once: AI reads it (team/alliance
 * lookups, score conditions) constantly, and entity events write it (kill/criteria score awards,
 * score cleanup on entity removal). Its backing maps are plain fastutil/hash maps — concurrent
 * structural mutation corrupts them, and a read concurrent with a structural write sees a
 * half-updated map.
 *
 * <p>A single {@link ReentrantReadWriteLock} resolves it: workers hold the shared <em>read</em> lock
 * for lookups (so AI queries stay parallel) and the exclusive <em>write</em> lock for the rare
 * mutations. Each entity ticks on one worker, so a given score is only ever mutated by one thread —
 * only the shared maps need protecting, which this provides.
 *
 * <p>Command-driven scoreboard edits run on the server thread between parallel phases (commands are
 * processed outside {@code tickChildren}'s region phase), so they never overlap worker access and
 * don't need guarding. Engaged whenever any parallel takeover is active.
 *
 * <p><b>Lock ordering:</b> the only cross-lock nesting is entity-removal → {@code entityRemoved}
 * (this lock) inside {@link EntityLifecycleLock} write, i.e. always EntityLifecycle → ServerState;
 * nothing acquires them in the reverse order, so no deadlock.
 */
public final class ServerStateLock {
    private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock();

    private ServerStateLock() {}

    public static boolean engaged() {
        return TachyonMod.config != null && (TachyonMod.config.mosaicEnabled || TachyonMod.config.intraLevel);
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

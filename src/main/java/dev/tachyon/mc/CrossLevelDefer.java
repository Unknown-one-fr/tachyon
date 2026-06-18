package dev.tachyon.mc;

import dev.tachyon.TachyonMod;
import dev.tachyon.core.MainThreadDispatcher;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;

/**
 * Decides whether an entity teleport must be deferred off the parallel level-tick threads.
 *
 * <p>Under {@link ParallelLevelTicker}, two levels tick concurrently. A <em>same-dimension</em>
 * teleport only touches the level being ticked (region-local) and is safe to run inline on the
 * worker. A <em>cross-dimension</em> teleport writes both the source and destination levels' entity
 * managers — if the destination level is being ticked on another thread, that races. So when we are
 * off the main thread and the teleport crosses dimensions, the real teleport is deferred to run
 * single-threaded on the main thread after every level finishes (see {@link ParallelLevelTicker}).
 */
public final class CrossLevelDefer {

    private CrossLevelDefer() {}

    /**
     * If this teleport must be deferred, queue {@code realTeleport} for the post-tick main-thread
     * pass and return {@code true} (the caller should then short-circuit, returning null). Returns
     * {@code false} to let the teleport proceed inline (engine off, on main, or same-dimension).
     */
    public static boolean deferIfCrossLevel(Entity entity, TeleportTransition transition, Runnable realTeleport) {
        if (TachyonMod.config == null || !TachyonMod.config.mosaicEnabled) {
            return false;
        }
        MainThreadDispatcher d = MainThreadDispatcher.INSTANCE;
        if (!d.isBound() || d.isMainThread()) {
            return false; // already on the main thread — safe to run now
        }
        if (!(entity.level() instanceof ServerLevel from)) {
            return false;
        }
        if (transition.newLevel().dimension() == from.dimension()) {
            return false; // same dimension: region-local, safe on this worker
        }
        ParallelLevelTicker.deferPostTick(realTeleport);
        return true;
    }
}

package dev.tachyon.mc;

import dev.tachyon.TachyonMod;
import dev.tachyon.core.MainThreadDispatcher;
import dev.tachyon.core.Region;
import dev.tachyon.core.RegionScheduler;
import dev.tachyon.mixin.ServerLevelPassengerTickInvoker;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;

/**
 * Parallel <em>per-level</em> ticking — the first real Mosaic takeover that is safe by construction.
 *
 * <p>Each {@link ServerLevel} (overworld, nether, end, …) owns its own entity list, entity manager,
 * chunk source, scheduled-tick queues and block-event queues. Those structures are <b>disjoint
 * across levels</b>, so ticking different levels on different threads does not race on them — unlike
 * ticking entities <em>within</em> one level, which all share that level's state (the approach that
 * deadlocked). This is the layer Folia builds on and the step the staged
 * {@code MinecraftServerMixin} template called for.
 *
 * <p>Each level is handed to the {@link RegionScheduler} as a one-member region; the scheduler's
 * cooperative wait (see {@link dev.tachyon.core.MainThreadDispatcher}) lets any level that needs the
 * server thread mid-tick hop back without deadlocking. The one mutation that genuinely crosses two
 * levels — entity dimension travel — is deferred via {@link #deferPostTick} and replayed
 * single-threaded on the main thread after every level has finished (see the teleport mixins).
 *
 * <p><b>Honest scope:</b> server-global mutable state still touched from a level tick (scoreboard,
 * advancements/stats, the player list) is not yet isolated; run with {@code mosaic.guardMode=WARN}
 * to surface those. Gated behind {@code mosaic.enabled} — playground only.
 */
public final class ParallelLevelTicker {
    private static volatile ParallelLevelTicker instance;

    private final RegionScheduler scheduler;
    private final ConcurrentLinkedQueue<Runnable> postTick = new ConcurrentLinkedQueue<>();

    /**
     * Players whose tick was deferred off a worker thread to the main thread. A {@code ServerPlayer}
     * shares state with the main-thread connection/menu/advancement machinery (opening a container,
     * packet sync, stats), so ticking it on a region worker races with packet handling — observed as
     * containers refusing to open. We skip players during the parallel phase and re-tick them on the
     * main thread at the barrier instead. Process-wide (filled from any worker / any level).
     */
    private static final ConcurrentLinkedQueue<Runnable> DEFERRED_PLAYERS = new ConcurrentLinkedQueue<>();

    private ParallelLevelTicker(int parallelism) {
        this.scheduler = new RegionScheduler(parallelism);
    }

    public static ParallelLevelTicker get() {
        ParallelLevelTicker t = instance;
        if (t == null) {
            synchronized (ParallelLevelTicker.class) {
                t = instance;
                if (t == null) {
                    int par = TachyonMod.config != null ? TachyonMod.config.parallelism : 4;
                    instance = t = new ParallelLevelTicker(par);
                }
            }
        }
        return t;
    }

    /**
     * Defer a cross-level mutation to run on the main thread after all levels finish this tick.
     * If no parallel tick is in flight (or the engine is off), it runs inline immediately.
     */
    public static void deferPostTick(Runnable r) {
        ParallelLevelTicker t = instance;
        if (t != null) {
            t.postTick.add(r);
        } else {
            r.run();
        }
    }

    /**
     * Queue a player's tick to run on the main thread (called from a worker via the tick mixin).
     * The full vanilla {@code tickNonPassenger} runs at the barrier on the main thread, where the
     * chunk cache owner is the server thread again and no packet-handling race exists.
     */
    public static void deferPlayerTick(ServerLevel level, Entity player) {
        DEFERRED_PLAYERS.add(() -> level.tickNonPassenger(player));
    }

    /**
     * Queue a riding player's passenger tick to run on the main thread. Vanilla routes mounted
     * players through private {@code tickPassenger(...)} instead of {@code tickNonPassenger(...)};
     * replay that exact path at the barrier so vehicle validation, {@code rideTick()}, and nested
     * passenger recursion stay vanilla.
     */
    public static void deferPlayerPassengerTick(ServerLevel level, Entity vehicle, Entity player) {
        DEFERRED_PLAYERS.add(() ->
                ((ServerLevelPassengerTickInvoker) level).tachyon$invokeTickPassenger(vehicle, player));
    }

    /**
     * Tick all deferred players — but only when the caller is the true main thread. Both the
     * per-level driver (server thread) and the intra-level driver call this; in the nested
     * combined mode only the outer server-thread driver actually drains, so players never tick on a
     * worker. No-op if the engine isn't bound yet (runs inline elsewhere).
     */
    public static void drainDeferredPlayersIfMain() {
        if (!MainThreadDispatcher.INSTANCE.isMainThread()) {
            return;
        }
        Runnable r;
        while ((r = DEFERRED_PLAYERS.poll()) != null) {
            r.run();
        }
    }

    /** Tick every level in parallel, then replay deferred cross-level mutations on the main thread. */
    public void tickLevels(List<ServerLevel> levels, BooleanSupplier haveTime) {
        final int n = levels.size();
        if (n == 0) {
            return;
        }

        final List<Region> regions = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            regions.add(new Region(i, new long[]{i})); // one synthetic chunk-id per level
        }

        scheduler.tick(regions, region -> {
            ServerLevel level = levels.get(region.id);
            // Claim this level's chunk cache for the current worker so chunk access runs inline
            // (single-writer per level) instead of bouncing to the blocked server thread. Restore
            // ownership to the real server thread afterwards. See tachyon.accesswidener.
            ServerChunkCache cache = level.getChunkSource();
            Thread serverThread = cache.mainThread;
            cache.mainThread = Thread.currentThread();
            try {
                level.tick(haveTime);
            } finally {
                cache.mainThread = serverThread;
            }
        });

        // Barrier complete (all levels ticked, on the server thread). Tick players that were
        // deferred off the workers, then replay cross-level mutations — all single-threaded here.
        drainDeferredPlayersIfMain();
        Runnable r;
        while ((r = postTick.poll()) != null) {
            r.run();
        }

        if (TachyonMod.engine != null) {
            TachyonMod.engine.metrics.recordPhases(
                    scheduler.lastParallelNanos(), scheduler.lastBarrierNanos(), 0L, n);
        }
    }
}

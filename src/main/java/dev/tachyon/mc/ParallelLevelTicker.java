package dev.tachyon.mc;

import dev.tachyon.TachyonMod;
import dev.tachyon.core.Region;
import dev.tachyon.core.RegionScheduler;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;

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

        // Barrier complete (all levels ticked). Replay cross-level mutations single-threaded.
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

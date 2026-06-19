package dev.tachyon;

import com.sun.management.HotSpotDiagnosticMXBean;
import dev.tachyon.command.TachyonCommand;
import dev.tachyon.config.TachyonConfig;
import dev.tachyon.core.MainThreadDispatcher;
import dev.tachyon.core.OffThreadGuard;
import dev.tachyon.govern.MsptGovernor;
import dev.tachyon.metrics.PerfRecorder;
import dev.tachyon.simd.NoiseKernel;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;

/**
 * Entry point. Loads config, builds the {@link TachyonEngine}, registers measurement
 * hooks (whole-tick timing via Fabric lifecycle events) and the {@code /tachyon}
 * command, and starts the MSPT governor once the server is up.
 *
 * <p>The deep Mosaic interception lives in the {@code dev.tachyon.mixin} layer (conflict-gated by
 * {@link dev.tachyon.mixin.TachyonMixinPlugin}); everything wired here runs regardless so the mod
 * loads and measures even when the takeover is gated off.
 */
public final class TachyonMod implements ModInitializer {
    public static final String ID = "tachyon";
    public static final String VERSION = "0.1.0-experimental";
    public static final Logger LOG = LoggerFactory.getLogger("Tachyon");

    public static TachyonConfig config;
    public static TachyonEngine engine;

    private static MsptGovernor governor;
    private static PerfRecorder perfRecorder;
    private static long tickStartNs;
    private static long serverTicks;

    @Override
    public void onInitialize() {
        Path cfgFile = FabricLoader.getInstance().getConfigDir().resolve("tachyon.properties");
        config = TachyonConfig.load(cfgFile);
        engine = new TachyonEngine(config);

        LOG.info("Tachyon {} initializing", VERSION);
        LOG.info("  takeover: mosaic={} intraLevel={} parallelism={} guard={}",
                config.mosaicEnabled, config.intraLevel, config.parallelism, config.guardMode);
        LOG.info("  measure: regions={} intervalTicks={} whenDisabled={}",
                config.measureRegions, config.measureIntervalTicks, config.measureWhenDisabled);
        LOG.info("  subsystems: soa={} ffm={} simd={} governor={} targetMSPT={}",
                config.soaEnabled, config.ffmScratch, config.simdNoise,
                config.governorEnabled, config.targetMspt);
        LOG.info("  metrics: window={} logIntervalTicks={} csv={}",
                config.metricsWindow, config.metricsLogIntervalTicks, config.metricsCsv);
        LOG.info("  jdk25: VectorAPI={} CompactObjectHeaders={}",
                NoiseKernel.simdAvailable(), readVmFlag("UseCompactObjectHeaders"));

        if (dev.tachyon.mixin.TachyonMixinPlugin.isMeasureOnly()) {
            LOG.warn("  MEASURE-ONLY MODE: a conflicting deep tick/chunk mod (e.g. Lithium/C2ME) is "
                    + "present, so the parallel takeover is disabled. /tachyon regions still works. "
                    + "Run on a server without that perf stack to enable the takeover.");
        }

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            boolean takeoverActive = config.parallelTakeoverEnabled()
                    && !dev.tachyon.mixin.TachyonMixinPlugin.isMeasureOnly();
            if (config.governorEnabled && takeoverActive) {
                governor = new MsptGovernor(config.targetMspt, new ServerActuators(server, 10));
                LOG.info("Tachyon governor active (targetMSPT={})", config.targetMspt);
            } else if (config.governorEnabled) {
                LOG.info("Tachyon governor idle until a parallel takeover mode is active");
            }
            perfRecorder = new PerfRecorder(FabricLoader.getInstance().getGameDir());
            LOG.info("Tachyon engaged.\n{}", engine.selfTest());
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            engine.shutdown();
            governor = null;
            perfRecorder = null;
            serverTicks = 0;
        });

        // The server tick thread owns all shared world state. Bind it as the dispatcher's main
        // thread on the first tick so region workers can hop back to it cooperatively (and the
        // off-thread guard knows which thread is "main"). Cheap idempotent check on the hot path.
        OffThreadGuard.setMode(config.guardMode);
        ServerTickEvents.START_SERVER_TICK.register(s -> {
            if (!MainThreadDispatcher.INSTANCE.isBound()) {
                MainThreadDispatcher.INSTANCE.setMainThread(Thread.currentThread());
                LOG.info("Tachyon: bound server thread '{}' as dispatcher main (guard={})",
                        Thread.currentThread().getName(), config.guardMode);
            }
            tickStartNs = System.nanoTime();
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            serverTicks++;
            engine.metrics.recordTick(System.nanoTime() - tickStartNs);
            if (governor != null) {
                governor.update(engine.metrics.meanMspt(), System.currentTimeMillis());
            }
            if (perfRecorder != null) {
                perfRecorder.reportIfDue(serverTicks, config, engine.metrics,
                        governor != null, governorAdjustments());
            }
        });

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, access, env) -> TachyonCommand.register(dispatcher));
    }

    /** Best-effort read of a manageable HotSpot flag, for the startup banner. */
    private static String readVmFlag(String flag) {
        try {
            HotSpotDiagnosticMXBean bean =
                    ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
            return bean.getVMOption(flag).getValue();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    public static boolean governorActive() {
        return governor != null;
    }

    public static long governorAdjustments() {
        return governor != null ? governor.adjustments() : 0;
    }
}

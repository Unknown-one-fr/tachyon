package dev.tachyon.metrics;

import dev.tachyon.config.TachyonConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * Lightweight built-in replacement for the Spark data needed during Tachyon A/B runs.
 * It records periodic tick statistics to the server log and an append-only CSV file.
 */
public final class PerfRecorder {
    private static final Logger LOG = LoggerFactory.getLogger("Tachyon");
    private static final double BYTES_PER_MIB = 1024.0 * 1024.0;

    private final Path csvFile;
    private boolean csvFailed;

    public PerfRecorder(Path gameDir) {
        this.csvFile = gameDir.resolve("logs").resolve("tachyon-metrics.csv");
    }

    public void reportIfDue(long serverTick, TachyonConfig config, TickMetrics metrics,
                            boolean governorActive, long governorAdjustments) {
        int interval = config.metricsLogIntervalTicks;
        if (interval <= 0 || serverTick % interval != 0) {
            return;
        }

        TickMetrics.Snapshot snapshot = metrics.snapshot();
        if (snapshot.samples() == 0) {
            return;
        }

        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        double heapUsedMiB = heap.getUsed() / BYTES_PER_MIB;
        double heapMaxMiB = heap.getMax() > 0 ? heap.getMax() / BYTES_PER_MIB : 0;

        LOG.info("Tachyon perf: mode={} governor={} adjustments={} {} heap={}/{}MiB csv={}",
                mode(config),
                governorActive ? "active" : "idle",
                governorAdjustments,
                snapshot.oneLine(),
                format(heapUsedMiB),
                heapMaxMiB > 0 ? format(heapMaxMiB) : "unknown",
                config.metricsCsv ? csvFile : "off");

        if (config.metricsCsv && !csvFailed) {
            appendCsv(serverTick, config, snapshot, governorActive, governorAdjustments,
                    heapUsedMiB, heapMaxMiB);
        }
    }

    private void appendCsv(long serverTick, TachyonConfig config, TickMetrics.Snapshot snapshot,
                           boolean governorActive, long governorAdjustments,
                           double heapUsedMiB, double heapMaxMiB) {
        try {
            Files.createDirectories(csvFile.getParent());
            if (Files.notExists(csvFile) || Files.size(csvFile) == 0) {
                Files.writeString(csvFile, header(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            Files.writeString(csvFile, row(serverTick, config, snapshot, governorActive,
                    governorAdjustments, heapUsedMiB, heapMaxMiB),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            csvFailed = true;
            LOG.warn("Tachyon perf: disabling CSV output after write failure for {}", csvFile, e);
        }
    }

    private static String header() {
        return "epoch_ms,tick,mode,mosaic_enabled,intra_level,measure_regions,governor_active,"
                + "governor_adjustments,samples,mean_mspt,p95_mspt,p99_mspt,max_mspt,estimated_tps,"
                + "parallel_ms,barrier_ms,main_ms,regions,heap_used_mib,heap_max_mib"
                + System.lineSeparator();
    }

    private static String row(long serverTick, TachyonConfig config, TickMetrics.Snapshot s,
                              boolean governorActive, long governorAdjustments,
                              double heapUsedMiB, double heapMaxMiB) {
        return String.format(Locale.ROOT,
                "%d,%d,%s,%s,%s,%s,%s,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%.3f,%.3f%n",
                System.currentTimeMillis(),
                serverTick,
                mode(config),
                config.mosaicEnabled,
                config.intraLevel,
                config.measureRegions,
                governorActive,
                governorAdjustments,
                s.samples(),
                s.meanMspt(),
                s.p95Mspt(),
                s.p99Mspt(),
                s.maxMspt(),
                s.estimatedTps(),
                s.parallelMs(),
                s.barrierMs(),
                s.mainMs(),
                s.regionCount(),
                heapUsedMiB,
                heapMaxMiB);
    }

    private static String mode(TachyonConfig config) {
        if (config.mosaicEnabled && config.intraLevel) {
            return "mosaic+intra";
        }
        if (config.mosaicEnabled) {
            return "mosaic";
        }
        if (config.intraLevel) {
            return "intra";
        }
        return "disabled";
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}

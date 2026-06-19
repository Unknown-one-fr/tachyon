package dev.tachyon.config;

import dev.tachyon.core.OffThreadGuard;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Plain {@code .properties}-backed feature flags. Every subsystem is individually
 * toggleable so a regression or incompatibility can be bisected without recompiling.
 * The riskiest subsystem (the Mosaic parallel engine) defaults OFF even in this
 * playground build — you opt in explicitly.
 */
public final class TachyonConfig {
    // subsystems
    public boolean mosaicEnabled = false;   // parallel region tick engine (experimental)
    public boolean soaEnabled = true;       // struct-of-arrays entity hot store
    public boolean ffmScratch = true;       // off-heap FFM scratch arenas
    public boolean simdNoise = true;        // SIMD worldgen kernels
    public boolean governorEnabled = true;  // self-tuning MSPT governor
    public boolean measureRegions = true;   // live region-partitioning stats (safe, read-only)
    public boolean measureWhenDisabled = false; // keep disabled baselines close to no-op by default
    public boolean intraLevel = false;       // EXPERIMENTAL: regionize entity ticking within a level

    // tunables
    public int targetMspt = 35;
    public int interactionRadiusChunks = 2;
    public int parallelism = Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
    public int measureIntervalTicks = 100;
    public int metricsWindow = 200;
    public int metricsLogIntervalTicks = 200;
    public boolean metricsCsv = true;
    public long scratchBytesPerThread = 8L * 1024 * 1024;

    // off-thread access tripwire for the parallel engine: OFF (default) | WARN | STRICT.
    // Used to locate unsafe shared-state touches while building per-region isolation.
    public OffThreadGuard.Mode guardMode = OffThreadGuard.Mode.OFF;

    public static TachyonConfig load(Path file) {
        TachyonConfig c = new TachyonConfig();
        if (Files.exists(file)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            } catch (IOException e) {
                return c;
            }
            c.mosaicEnabled = bool(p, "mosaic.enabled", c.mosaicEnabled);
            c.soaEnabled = bool(p, "soa.enabled", c.soaEnabled);
            c.ffmScratch = bool(p, "ffm.scratch", c.ffmScratch);
            c.simdNoise = bool(p, "simd.noise", c.simdNoise);
            c.governorEnabled = bool(p, "governor.enabled", c.governorEnabled);
            c.measureRegions = bool(p, "mosaic.measureRegions", c.measureRegions);
            c.measureWhenDisabled = bool(p, "mosaic.measureWhenDisabled", c.measureWhenDisabled);
            c.intraLevel = bool(p, "mosaic.intraLevel", c.intraLevel);
            c.targetMspt = integer(p, "governor.targetMspt", c.targetMspt);
            c.interactionRadiusChunks = integer(p, "mosaic.interactionRadiusChunks", c.interactionRadiusChunks);
            c.parallelism = integer(p, "mosaic.parallelism", c.parallelism);
            c.measureIntervalTicks = positiveInteger(p, "mosaic.measureIntervalTicks", c.measureIntervalTicks);
            c.metricsWindow = integer(p, "metrics.window", c.metricsWindow);
            c.metricsLogIntervalTicks = nonNegativeInteger(p, "metrics.logIntervalTicks", c.metricsLogIntervalTicks);
            c.metricsCsv = bool(p, "metrics.csv", c.metricsCsv);
            c.scratchBytesPerThread = longer(p, "ffm.bytesPerThread", c.scratchBytesPerThread);
            c.guardMode = guardMode(p, "mosaic.guardMode", c.guardMode);
        } else {
            c.save(file);
        }
        return c;
    }

    public void save(Path file) {
        Properties p = new Properties();
        p.setProperty("mosaic.enabled", Boolean.toString(mosaicEnabled));
        p.setProperty("mosaic.interactionRadiusChunks", Integer.toString(interactionRadiusChunks));
        p.setProperty("mosaic.parallelism", Integer.toString(parallelism));
        p.setProperty("soa.enabled", Boolean.toString(soaEnabled));
        p.setProperty("ffm.scratch", Boolean.toString(ffmScratch));
        p.setProperty("ffm.bytesPerThread", Long.toString(scratchBytesPerThread));
        p.setProperty("simd.noise", Boolean.toString(simdNoise));
        p.setProperty("governor.enabled", Boolean.toString(governorEnabled));
        p.setProperty("mosaic.measureRegions", Boolean.toString(measureRegions));
        p.setProperty("mosaic.measureWhenDisabled", Boolean.toString(measureWhenDisabled));
        p.setProperty("mosaic.intraLevel", Boolean.toString(intraLevel));
        p.setProperty("governor.targetMspt", Integer.toString(targetMspt));
        p.setProperty("mosaic.measureIntervalTicks", Integer.toString(measureIntervalTicks));
        p.setProperty("metrics.window", Integer.toString(metricsWindow));
        p.setProperty("metrics.logIntervalTicks", Integer.toString(metricsLogIntervalTicks));
        p.setProperty("metrics.csv", Boolean.toString(metricsCsv));
        p.setProperty("mosaic.guardMode", guardMode.name());
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                p.store(out, "Tachyon experimental performance engine — playground build");
            }
        } catch (IOException ignored) {
        }
    }

    public boolean parallelTakeoverEnabled() {
        return mosaicEnabled || intraLevel;
    }

    private static boolean bool(Properties p, String k, boolean def) {
        String v = p.getProperty(k);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private static int integer(Properties p, String k, int def) {
        try {
            String v = p.getProperty(k);
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int positiveInteger(Properties p, String k, int def) {
        int value = integer(p, k, def);
        return value > 0 ? value : def;
    }

    private static int nonNegativeInteger(Properties p, String k, int def) {
        int value = integer(p, k, def);
        return value >= 0 ? value : def;
    }

    private static long longer(Properties p, String k, long def) {
        try {
            String v = p.getProperty(k);
            return v == null ? def : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static OffThreadGuard.Mode guardMode(Properties p, String k, OffThreadGuard.Mode def) {
        String v = p.getProperty(k);
        if (v == null) return def;
        try {
            return OffThreadGuard.Mode.valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}

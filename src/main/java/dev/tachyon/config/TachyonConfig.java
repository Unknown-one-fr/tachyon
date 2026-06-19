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
    // SIMD worldgen noise: bit-exact vs vanilla, but BENCHMARKED SLOWER (MC's gradient noise is
    // permutation-gather-bound, which the Vector API can't accelerate — see McNoiseBench). Default OFF;
    // kept as a correctness-proven foundation, not a speedup. Enabling it slows worldgen ~1.3-1.5x.
    public boolean simdNoise = false;       // SIMD worldgen kernels (foundation; not a perf win — see McNoiseBench)
    public boolean simdVerifyNoise = false; // DEBUG: cross-check each SIMD noise batch vs vanilla bit-for-bit
    public boolean governorEnabled = true;  // self-tuning MSPT governor
    public boolean measureRegions = true;   // live region-partitioning stats (safe, read-only)
    public boolean intraLevel = false;       // EXPERIMENTAL: regionize entity ticking within a level
    // SoA R/W-split collision broadphase: bit-exact, but the flat O(N) scan is BENCHMARKED SLOWER than
    // vanilla's section index at real entity densities (O(N^2) per region — see BroadphaseBench). OFF.
    public boolean entityBroadphase = false;  // EXPERIMENTAL: needs intraLevel; not a perf win — see BroadphaseBench

    // tunables
    public int targetMspt = 35;
    public int simdNoiseMinBatch = 8;  // skip the SIMD worldgen path for batches smaller than this
    public int interactionRadiusChunks = 2;
    public int parallelism = Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
    public int metricsWindow = 200;
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
            c.simdVerifyNoise = bool(p, "simd.verifyNoise", c.simdVerifyNoise);
            c.simdNoiseMinBatch = Math.max(1, integer(p, "simd.noiseMinBatch", c.simdNoiseMinBatch));
            c.governorEnabled = bool(p, "governor.enabled", c.governorEnabled);
            c.measureRegions = bool(p, "mosaic.measureRegions", c.measureRegions);
            c.intraLevel = bool(p, "mosaic.intraLevel", c.intraLevel);
            c.entityBroadphase = bool(p, "mosaic.entityBroadphase", c.entityBroadphase);
            c.targetMspt = integer(p, "governor.targetMspt", c.targetMspt);
            c.interactionRadiusChunks = integer(p, "mosaic.interactionRadiusChunks", c.interactionRadiusChunks);
            c.parallelism = integer(p, "mosaic.parallelism", c.parallelism);
            c.metricsWindow = integer(p, "metrics.window", c.metricsWindow);
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
        p.setProperty("simd.verifyNoise", Boolean.toString(simdVerifyNoise));
        p.setProperty("simd.noiseMinBatch", Integer.toString(simdNoiseMinBatch));
        p.setProperty("governor.enabled", Boolean.toString(governorEnabled));
        p.setProperty("mosaic.measureRegions", Boolean.toString(measureRegions));
        p.setProperty("mosaic.intraLevel", Boolean.toString(intraLevel));
        p.setProperty("mosaic.entityBroadphase", Boolean.toString(entityBroadphase));
        p.setProperty("governor.targetMspt", Integer.toString(targetMspt));
        p.setProperty("metrics.window", Integer.toString(metricsWindow));
        p.setProperty("mosaic.guardMode", guardMode.name());
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                p.store(out, "Tachyon experimental performance engine — playground build");
            }
        } catch (IOException ignored) {
        }
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

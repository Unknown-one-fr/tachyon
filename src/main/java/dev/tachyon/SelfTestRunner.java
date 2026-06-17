package dev.tachyon;

import dev.tachyon.config.TachyonConfig;

/**
 * Standalone entry point that exercises the entire Minecraft-independent engine core
 * on the real JDK 25 runtime — no Loom, no Minecraft required. Proves the Vector API,
 * FFM arenas, scoped-value region context, and work-stealing scheduler all function
 * in this JVM before they are trusted on the server hot path.
 */
public final class SelfTestRunner {
    public static void main(String[] args) {
        TachyonConfig cfg = new TachyonConfig();
        cfg.mosaicEnabled = true;   // build the real scheduler
        cfg.simdNoise = true;
        cfg.ffmScratch = true;

        TachyonEngine engine = new TachyonEngine(cfg);
        System.out.println(engine.selfTest());
        engine.shutdown();

        System.out.println("runtime: java " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vendor") + ")");
        System.out.println("availableProcessors=" + Runtime.getRuntime().availableProcessors()
                + " parallelism=" + cfg.parallelism);
    }
}

package dev.tachyon.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.tachyon.TachyonMod;
import dev.tachyon.config.TachyonConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /tachyon perf | selftest | status} — operator-only diagnostics.
 * The other MC-touching class besides {@link dev.tachyon.ServerActuators}; uses only
 * long-stable Brigadier + command-source APIs.
 */
public final class TachyonCommand {
    private TachyonCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tachyon")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))   // op level 2 (26.1 PermissionCheck)
                .then(Commands.literal("regions").executes(ctx -> {
                    ctx.getSource().sendSuccess(
                            () -> Component.literal(dev.tachyon.mc.RegionStats.summary()), false);
                    return 1;
                }))
                .then(Commands.literal("perf").executes(ctx -> {
                    ctx.getSource().sendSuccess(
                            () -> Component.literal(TachyonMod.engine.metrics.summary()), false);
                    return 1;
                }))
                .then(Commands.literal("selftest").executes(ctx -> {
                    ctx.getSource().sendSuccess(
                            () -> Component.literal(TachyonMod.engine.selfTest()), false);
                    return 1;
                }))
                .then(Commands.literal("mosaic")
                        .then(Commands.literal("on").executes(ctx -> setMosaic(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> setMosaic(ctx, false))))
                .then(Commands.literal("status").executes(ctx -> {
                    TachyonConfig c = TachyonMod.config;
                    String s = "Tachyon " + TachyonMod.VERSION
                            + " | mosaic=" + c.mosaicEnabled + " soa=" + c.soaEnabled
                            + " ffm=" + c.ffmScratch + " simd=" + c.simdNoise
                            + " governor=" + c.governorEnabled
                            + " | parallelism=" + c.parallelism + " targetMSPT=" + c.targetMspt;
                    ctx.getSource().sendSuccess(() -> Component.literal(s), false);
                    return 1;
                })));
    }

    /** Toggle the parallel takeover at runtime and clear the metrics window for a clean A/B read. */
    private static int setMosaic(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, boolean on) {
        TachyonMod.config.mosaicEnabled = on;
        if (TachyonMod.engine != null) {
            TachyonMod.engine.metrics.reset();
        }
        ctx.getSource().sendSuccess(
                () -> Component.literal("Tachyon mosaic " + (on ? "ENABLED" : "disabled")
                        + " (metrics window cleared)"), false);
        return 1;
    }
}

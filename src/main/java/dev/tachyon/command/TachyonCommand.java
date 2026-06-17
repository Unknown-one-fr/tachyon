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
        // TODO(26.1): re-gate to operators via the new PermissionSet API
        // (CommandSourceStack.permissions().hasPermission(<Permission>)). Open on the
        // private test instance for now — milestone 1 is compile + load.
        dispatcher.register(Commands.literal("tachyon")
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
}

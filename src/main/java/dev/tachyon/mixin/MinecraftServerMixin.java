package dev.tachyon.mixin;

import dev.tachyon.TachyonMod;
import dev.tachyon.mc.ParallelLevelTicker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Mosaic takeover (per-level). Replaces {@code tickChildren}'s serial
 * {@code for (level : getAllLevels()) level.tick(...)} loop with a single parallel pass over all
 * levels (see {@link ParallelLevelTicker}).
 *
 * <p>We redirect the {@code level.tick(...)} call inside the loop. The first redirected call in a
 * server tick ticks <em>all</em> levels in parallel and remembers the tick number; the remaining
 * loop iterations (the other levels) become no-ops. The surrounding profiler push/pop pairs stay
 * balanced because we only replace the inner invoke, not the loop structure. When
 * {@code mosaic.enabled} is off, each call ticks its level exactly as vanilla would.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Unique private int tachyon$lastParallelTick = -1;

    @Redirect(method = "tickChildren",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;tick(Ljava/util/function/BooleanSupplier;)V"))
    private void tachyon$parallelLevelTick(ServerLevel level, BooleanSupplier haveTime) {
        if (TachyonMod.config == null || !TachyonMod.config.mosaicEnabled) {
            level.tick(haveTime); // vanilla serial path
            return;
        }

        MinecraftServer self = (MinecraftServer) (Object) this;
        int tick = self.getTickCount();
        if (tick == tachyon$lastParallelTick) {
            return; // all levels already ticked in parallel earlier this server tick
        }
        tachyon$lastParallelTick = tick;

        List<ServerLevel> levels = new ArrayList<>();
        for (ServerLevel l : self.getAllLevels()) {
            levels.add(l);
        }
        ParallelLevelTicker.get().tickLevels(levels, haveTime);
    }
}

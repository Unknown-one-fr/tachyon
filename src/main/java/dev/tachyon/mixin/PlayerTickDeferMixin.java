package dev.tachyon.mixin;

import dev.tachyon.TachyonMod;
import dev.tachyon.core.MainThreadDispatcher;
import dev.tachyon.mc.ParallelLevelTicker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps {@link ServerPlayer} ticking on the main thread under the parallel takeover.
 *
 * <p>A player's tick touches state shared with the main-thread connection/menu/advancement/stat
 * machinery (container open + revalidation, packet sync). Ticking it on a region/level worker races
 * with packet handling on the server thread — the symptom being that containers (chests, hoppers,
 * furnaces, …) won't open. So when a player's {@code tickNonPassenger} is reached off the main
 * thread, we skip it and queue the player for a re-tick on the main thread at the barrier
 * ({@link ParallelLevelTicker#deferPlayerTick}). The re-tick calls {@code tickNonPassenger} on the
 * main thread, where this guard is a no-op and the full vanilla tick runs normally.
 *
 * <p>Non-player entities are unaffected and keep ticking in parallel. Conflict-gated like the other
 * takeover mixins ({@link TachyonMixinPlugin}).
 */
@Mixin(ServerLevel.class)
public class PlayerTickDeferMixin {

    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void tachyon$deferPlayerToMain(Entity entity, CallbackInfo ci) {
        if (!tachyon$parallelTakeoverActive()) {
            return;
        }
        if (!(entity instanceof ServerPlayer)) {
            return;
        }
        MainThreadDispatcher d = MainThreadDispatcher.INSTANCE;
        if (d.isBound() && !d.isMainThread()) {
            ParallelLevelTicker.deferPlayerTick((ServerLevel) (Object) this, entity);
            ci.cancel();
        }
    }

    @Inject(method = "tickPassenger", at = @At("HEAD"), cancellable = true)
    private void tachyon$deferRidingPlayerToMain(Entity vehicle, Entity entity, CallbackInfo ci) {
        if (!tachyon$parallelTakeoverActive()) {
            return;
        }
        if (!(entity instanceof ServerPlayer)) {
            return;
        }
        MainThreadDispatcher d = MainThreadDispatcher.INSTANCE;
        if (d.isBound() && !d.isMainThread()) {
            ParallelLevelTicker.deferPlayerPassengerTick((ServerLevel) (Object) this, vehicle, entity);
            ci.cancel();
        }
    }

    private static boolean tachyon$parallelTakeoverActive() {
        return TachyonMod.config != null
                && (TachyonMod.config.mosaicEnabled || TachyonMod.config.intraLevel);
    }
}

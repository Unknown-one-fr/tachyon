package dev.tachyon.mixin;

import dev.tachyon.mc.CrossLevelDefer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@link ServerPlayer} overrides {@code teleport(TeleportTransition)} with its own cross-dimension
 * logic (respawn packets, player-list updates), so it needs the same deferral as
 * {@link EntityTeleportMixin}: a player walking through a portal on a level-tick worker must apply
 * the dimension change on the main thread, after the parallel phase.
 */
@Mixin(ServerPlayer.class)
public class ServerPlayerTeleportMixin {

    @Inject(method = "teleport", at = @At("HEAD"), cancellable = true)
    private void tachyon$deferCrossDimension(TeleportTransition transition, CallbackInfoReturnable<ServerPlayer> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (CrossLevelDefer.deferIfCrossLevel(self, transition, () -> self.teleport(transition))) {
            cir.setReturnValue(null);
        }
    }
}

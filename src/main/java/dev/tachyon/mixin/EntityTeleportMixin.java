package dev.tachyon.mixin;

import dev.tachyon.mc.CrossLevelDefer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Defers cross-dimension entity teleports off the parallel level-tick threads. A teleport into a
 * different dimension touches another level's entity manager (which may be ticking concurrently);
 * when off-main, the real teleport is queued to run single-threaded after the parallel phase. The
 * re-invoked teleport then runs on the main thread, where this guard is a no-op. Same-dimension
 * teleports are region-local and proceed inline.
 */
@Mixin(Entity.class)
public class EntityTeleportMixin {

    @Inject(method = "teleport", at = @At("HEAD"), cancellable = true)
    private void tachyon$deferCrossDimension(TeleportTransition transition, CallbackInfoReturnable<Entity> cir) {
        Entity self = (Entity) (Object) this;
        if (CrossLevelDefer.deferIfCrossLevel(self, transition, () -> self.teleport(transition))) {
            cir.setReturnValue(null);
        }
    }
}

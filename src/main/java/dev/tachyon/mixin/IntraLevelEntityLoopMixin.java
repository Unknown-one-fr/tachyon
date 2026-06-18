package dev.tachyon.mixin;

import dev.tachyon.TachyonMod;
import dev.tachyon.mc.IntraLevelEntityTicker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Consumer;

/**
 * EXPERIMENTAL intra-level regionization: tick a level's entities in parallel across
 * interaction-radius-separated regions (on the multi-owner chunk cache). Reuses vanilla's own
 * per-entity consumer; behind {@code mosaic.intraLevel} (default off).
 *
 * <p>Conflict-gated: {@link TachyonMixinPlugin} disables this (and the other takeover mixins) when a
 * deep-tick/chunk mod such as Lithium is present, so the mod still loads in measure-only mode there.
 */
@Mixin(ServerLevel.class)
public class IntraLevelEntityLoopMixin {

    @Redirect(method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"))
    private void tachyon$intraLevelEntityTick(EntityTickList list, Consumer<Entity> consumer) {
        if (TachyonMod.config != null && TachyonMod.config.intraLevel) {
            IntraLevelEntityTicker.get().tick((ServerLevel) (Object) this, list, consumer);
        } else {
            list.forEach(consumer);
        }
    }
}

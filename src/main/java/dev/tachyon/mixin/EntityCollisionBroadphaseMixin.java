package dev.tachyon.mixin;

import dev.tachyon.TachyonMod;
import dev.tachyon.mc.RegionBroadphase;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * Phase W of the collision-broadphase R/W split: serves {@code Entity.collide}'s single
 * {@code getEntityCollisions} call from the per-region {@link RegionBroadphase} SoA cache built in
 * Phase R, instead of a fresh entity-section walk per move.
 *
 * <p>The redirect only diverts when a broadphase is bound to this worker thread
 * ({@link RegionBroadphase#ACTIVE}); for any entity ticking without one (main thread, deferred
 * players, broadphase disabled) it calls straight through to vanilla. The cache result is
 * bit-identical to vanilla (see {@link RegionBroadphase}).
 *
 * <p>Part of the intra-level takeover, so conflict-gated by {@link TachyonMixinPlugin}.
 */
@Mixin(Entity.class)
public abstract class EntityCollisionBroadphaseMixin {

    @Redirect(method = "collide",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getEntityCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private List<VoxelShape> tachyon$broadphaseCollisions(Level level, Entity source, AABB testArea) {
        RegionBroadphase bp = RegionBroadphase.ACTIVE.get();
        if (bp != null && TachyonMod.config != null && TachyonMod.config.entityBroadphase) {
            return bp.collisions(source, testArea);
        }
        return level.getEntityCollisions(source, testArea);
    }
}

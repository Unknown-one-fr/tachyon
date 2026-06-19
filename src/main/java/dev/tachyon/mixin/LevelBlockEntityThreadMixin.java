package dev.tachyon.mixin;

import dev.tachyon.TachyonMod;
import dev.tachyon.core.RegionContext;
import dev.tachyon.mc.RegionOwnable;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lets Tachyon's temporary level/region owners pass vanilla's block-entity thread check.
 *
 * <p>{@link Level#getBlockEntity} returns {@code null} on any non-owner thread. That is correct for
 * arbitrary async callers, but Tachyon deliberately moves a level or disjoint region onto a worker
 * during the parallel tick. Without mirroring that ownership here, worker-run dispenser/dropper
 * scheduled ticks see no block entity and skip the dispense.
 */
@Mixin(Level.class)
public abstract class LevelBlockEntityThreadMixin {
    @Shadow @Final private Thread thread;

    @Redirect(
            method = "getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/world/level/Level;thread:Ljava/lang/Thread;",
                    opcode = Opcodes.GETFIELD))
    private Thread tachyon$effectiveBlockEntityOwner(Level self) {
        Thread current = Thread.currentThread();
        if (current == this.thread || TachyonMod.config == null) {
            return this.thread;
        }
        if (!TachyonMod.config.mosaicEnabled && !TachyonMod.config.intraLevel) {
            return this.thread;
        }
        if (!RegionContext.isInRegion() || !(self instanceof ServerLevel level)) {
            return this.thread;
        }

        ServerChunkCache cache = level.getChunkSource();
        if (cache.mainThread == current || ((RegionOwnable) (Object) cache).tachyon$isOwner(current)) {
            return current;
        }
        return this.thread;
    }
}

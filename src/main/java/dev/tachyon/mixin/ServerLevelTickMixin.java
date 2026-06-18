package dev.tachyon.mixin;

import dev.tachyon.TachyonMod;
import dev.tachyon.mc.IntraLevelEntityTicker;
import dev.tachyon.mc.RegionStats;
import dev.tachyon.mc.ServerLevelAdapter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Measure mode (safe, default): after each level tick, periodically partition the live
 * world into Mosaic regions and publish stats. Read-only — no behavior change. This is
 * how we observe, on a real server, how parallelizable the world is.
 *
 * <p>The actual parallel takeover now operates one level coarser — across whole levels in
 * {@code MinecraftServer.tickChildren} (see {@link dev.tachyon.mixin.MinecraftServerMixin}),
 * because per-level state is disjoint and safe to tick concurrently, whereas the earlier
 * intra-level entity-loop redirect raced/deadlocked on that shared per-level state.
 */
@Mixin(ServerLevel.class)
public class ServerLevelTickMixin {
    @Unique private ServerLevelAdapter tachyon$adapter;
    @Unique private int tachyon$counter;

    @Inject(method = "tick", at = @At("TAIL"))
    private void tachyon$measureRegions(BooleanSupplier keepRunning, CallbackInfo ci) {
        if (TachyonMod.config == null || !TachyonMod.config.measureRegions) return;
        if ((tachyon$counter++ % 20) != 0) return; // ~once per second

        ServerLevel self = (ServerLevel) (Object) this;
        if (tachyon$adapter == null) {
            tachyon$adapter = new ServerLevelAdapter(self, TachyonMod.config.interactionRadiusChunks);
        }
        int n = tachyon$adapter.partition();
        RegionStats.update(self.dimension().identifier().toString(),  // 26.1: ResourceKey.identifier()
                tachyon$adapter.regions().size(), n, tachyon$adapter.maxRegionEntities());
    }

    /**
     * EXPERIMENTAL intra-level regionization: tick this level's entities in parallel across
     * interaction-radius-separated regions (on the multi-owner chunk cache). Reuses vanilla's own
     * per-entity consumer. Behind {@code mosaic.intraLevel}; off by default.
     */
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

package dev.tachyon.mixin;

import dev.tachyon.TachyonMod;
import dev.tachyon.mc.RegionStats;
import dev.tachyon.mc.ServerLevelAdapter;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

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
}

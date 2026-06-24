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
 * Measure mode (safe, default): after each level tick, periodically partition the live world into
 * Mosaic regions and publish read-only stats — no behaviour change. This is the only Tachyon mixin
 * that always applies (it survives the {@link TachyonMixinPlugin} conflict gate), so even on a
 * server running other deep-tick mods (Lithium/C2ME/…) you can still observe how parallelizable the
 * world is via {@code /tachyon regions}. The intra-level entity takeover lives in a separate,
 * conflict-gated mixin ({@link IntraLevelEntityLoopMixin}).
 */
@Mixin(ServerLevel.class)
public class ServerLevelTickMixin {
    @Unique private ServerLevelAdapter tachyon$adapter;
    @Unique private int tachyon$counter;

    @Inject(method = "tick", at = @At("TAIL"))
    private void tachyon$measureRegions(BooleanSupplier keepRunning, CallbackInfo ci) {
        if (TachyonMod.config == null || !TachyonMod.config.measureRegions) return;
        if (!tachyon$shouldMeasureRegions()) return;
        int interval = Math.max(1, TachyonMod.config.measureIntervalTicks);
        if ((tachyon$counter++ % interval) != 0) return;

        ServerLevel self = (ServerLevel) (Object) this;
        if (tachyon$adapter == null) {
            tachyon$adapter = new ServerLevelAdapter(self, TachyonMod.config.interactionRadiusChunks);
        }
        int n = tachyon$adapter.partition();
        RegionStats.update(self.dimension().identifier().toString(),  // 26.1: ResourceKey.identifier()
                tachyon$adapter.regions().size(), n, tachyon$adapter.maxRegionEntities());
    }

    @Unique
    private boolean tachyon$shouldMeasureRegions() {
        if (TachyonMod.config.parallelTakeoverEnabled()) {
            return true;
        }
        return TachyonMod.config.measureWhenDisabled || TachyonMixinPlugin.isMeasureOnly();
    }
}

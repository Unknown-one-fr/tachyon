package dev.tachyon.mixin;

import dev.tachyon.TachyonMod;
import dev.tachyon.config.TachyonConfig;
import dev.tachyon.mc.McNoiseKernel;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Routes the heavy 3D terrain noise ({@code BlendedNoise}, the {@code BASE_3D_NOISE_*} functions)
 * through the SIMD kernel. This is the noise the vanilla overworld actually spends its worldgen time
 * on — sampled at every cell corner. {@code BlendedNoise} is a {@code SimpleFunction} that inherits
 * the point-wise {@code fillArray} default; this mixin adds a concrete {@code fillArray} override that
 * batches the corner samples through {@link McNoiseKernel} (bit-exact — {@code McBlendedNoiseParityTest}),
 * falling back to the exact vanilla point-wise behaviour otherwise.
 *
 * <p>Same gating as the other noise mixins: behind {@code simd.noise}, conflict-gated by
 * {@link TachyonMixinPlugin}.
 */
@Mixin(BlendedNoise.class)
public abstract class BlendedNoiseDensityFunctionMixin {

    /** Overrides the inherited {@code DensityFunction.SimpleFunction.fillArray} default on BlendedNoise. */
    public void fillArray(double[] output, DensityFunction.ContextProvider cp) {
        TachyonConfig cfg = TachyonMod.config;
        if (cfg != null && cfg.simdNoise
                && McNoiseKernel.fillArrayBlended((BlendedNoise) (Object) this, output, cp, cfg.simdNoiseMinBatch)) {
            return;
        }
        cp.fillAllDirectly(output, (DensityFunction) (Object) this); // exact vanilla point-wise path
    }
}

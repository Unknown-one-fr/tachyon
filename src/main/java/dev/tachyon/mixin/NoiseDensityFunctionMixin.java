package dev.tachyon.mixin;

import dev.tachyon.TachyonMod;
import dev.tachyon.config.TachyonConfig;
import dev.tachyon.mc.McNoiseKernel;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wires the SIMD worldgen noise kernel into vanilla. {@code DensityFunctions$Noise.fillArray}
 * normally falls back to point-wise {@code compute()} (one {@code NormalNoise.getValue} per cell);
 * this intercepts the batch and routes it through {@link McNoiseKernel}, which samples the same
 * noise with the JDK Vector API.
 *
 * <p><b>Terrain is unchanged:</b> {@code McNoiseParityTest} pins the kernel bit-for-bit against
 * {@code NormalNoise.getValue}, so this is purely a faster way to compute the identical values.
 *
 * <p>Gated behind {@code simd.noise} (default on) and conflict-gated by {@link TachyonMixinPlugin}
 * (disabled in measure-only mode when a worldgen-noise mod such as C2ME/Noisium is present).
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$Noise")
public abstract class NoiseDensityFunctionMixin {

    @Shadow @Final private DensityFunction.NoiseHolder noise;
    @Shadow @Final private double xzScale;
    @Shadow @Final private double yScale;

    @Inject(method = "fillArray", at = @At("HEAD"), cancellable = true)
    private void tachyon$simdFillArray(double[] output, DensityFunction.ContextProvider cp, CallbackInfo ci) {
        TachyonConfig cfg = TachyonMod.config;
        if (cfg == null || !cfg.simdNoise) {
            return;
        }
        NormalNoise nn = this.noise.noise();
        if (McNoiseKernel.fillArray(nn, this.xzScale, this.yScale, output, cp, cfg.simdNoiseMinBatch)) {
            ci.cancel();
        }
    }
}

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
 * Routes {@code DensityFunctions$ShiftedNoise.fillArray} through the SIMD kernel. ShiftedNoise is
 * the dominant noise node in the vanilla router (continentalness, erosion, weirdness, temperature,
 * humidity, …), so this is where most of the worldgen-noise win is realized — the plain
 * {@code Noise} node ({@link NoiseDensityFunctionMixin}) is comparatively rare.
 *
 * <p>The per-point coordinate shift ({@code shiftX/Y/Z.compute}) is left to vanilla (scalar, cheap);
 * only the {@code NormalNoise} octave sampling is vectorized, and it is bit-exact
 * ({@code McNoiseParityTest}). Same gating as {@link NoiseDensityFunctionMixin}.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$ShiftedNoise")
public abstract class ShiftedNoiseDensityFunctionMixin {

    @Shadow @Final private DensityFunction shiftX;
    @Shadow @Final private DensityFunction shiftY;
    @Shadow @Final private DensityFunction shiftZ;
    @Shadow @Final private double xzScale;
    @Shadow @Final private double yScale;
    @Shadow @Final private DensityFunction.NoiseHolder noise;

    @Inject(method = "fillArray", at = @At("HEAD"), cancellable = true)
    private void tachyon$simdFillArray(double[] output, DensityFunction.ContextProvider cp, CallbackInfo ci) {
        TachyonConfig cfg = TachyonMod.config;
        if (cfg == null || !cfg.simdNoise) {
            return;
        }
        NormalNoise nn = this.noise.noise();
        if (McNoiseKernel.fillArrayShifted(nn, this.xzScale, this.yScale,
                this.shiftX, this.shiftY, this.shiftZ, output, cp, cfg.simdNoiseMinBatch)) {
            ci.cancel();
        }
    }
}

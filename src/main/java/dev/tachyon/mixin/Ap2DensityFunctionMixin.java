package dev.tachyon.mixin;

import dev.tachyon.TachyonMod;
import dev.tachyon.config.TachyonConfig;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The NoiseChunk-level batching enabler. Vanilla's {@code DensityFunctions$Ap2} (the binary
 * +,*,min,max node) fills its <b>second</b> operand point-wise via {@code compute()} for MUL/MIN/MAX
 * (only ADD batches both). That collapses the {@code fillArray} batch chain before it reaches the
 * noise leaves — e.g. the overworld terrain path {@code slide() → lerp() → mul(factor, add(caves,…))}
 * buries {@code BlendedNoise} in arg2, so it is only ever sampled one point at a time.
 *
 * <p>This override re-fills arg2 via {@code fillArray} (allocating a temp array, exactly as ADD
 * already does) so batching propagates all the way down to the SIMD noise kernels
 * ({@link NoiseDensityFunctionMixin}, {@link ShiftedNoiseDensityFunctionMixin},
 * {@link BlendedNoiseDensityFunctionMixin}). The per-index combination is byte-for-byte identical to
 * vanilla: {@code argument2.fillArray} yields exactly {@code argument2.compute} per index (the
 * {@code fillArray} contract vanilla itself relies on for ADD), and the short-circuit guards are
 * preserved so an unused arg2 value (e.g. where MUL multiplies by 0) is never folded in.
 *
 * <p>Gated behind {@code simd.noise}: off ⇒ exact vanilla point-wise behaviour (clean A/B), on ⇒
 * batched. Conflict-gated by {@link TachyonMixinPlugin}.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$Ap2")
public abstract class Ap2DensityFunctionMixin {

    @Shadow @Final private DensityFunctions.TwoArgumentSimpleFunction.Type type;
    @Shadow @Final private DensityFunction argument1;
    @Shadow @Final private DensityFunction argument2;

    @Inject(method = "fillArray", at = @At("HEAD"), cancellable = true)
    private void tachyon$batchSecondOperand(double[] output, DensityFunction.ContextProvider cp, CallbackInfo ci) {
        TachyonConfig cfg = TachyonMod.config;
        if (cfg == null || !cfg.simdNoise) {
            return; // leave vanilla's point-wise fillArray in place
        }
        this.argument1.fillArray(output, cp);
        double[] v2 = new double[output.length];
        this.argument2.fillArray(v2, cp); // batched — propagates to noise leaves
        switch (this.type) {
            case ADD -> {
                for (int i = 0; i < output.length; i++) {
                    output[i] += v2[i];
                }
            }
            case MUL -> {
                for (int i = 0; i < output.length; i++) {
                    double v = output[i];
                    output[i] = v == 0.0 ? 0.0 : v * v2[i];
                }
            }
            case MIN -> {
                double min = this.argument2.minValue();
                for (int i = 0; i < output.length; i++) {
                    double v = output[i];
                    output[i] = v < min ? v : Math.min(v, v2[i]);
                }
            }
            case MAX -> {
                double max = this.argument2.maxValue();
                for (int i = 0; i < output.length; i++) {
                    double v = output[i];
                    output[i] = v > max ? v : Math.max(v, v2[i]);
                }
            }
        }
        ci.cancel();
    }
}

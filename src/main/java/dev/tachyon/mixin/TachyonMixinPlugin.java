package dev.tachyon.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Makes the Tachyon jar load <em>everywhere</em> by disabling the parallel-takeover mixins when a
 * mod that deeply rewrites the same vanilla systems is present.
 *
 * <p>Tachyon's takeover relocates chunk-cache ownership, regionizes entity ticking, and guards the
 * scoreboard by injecting into {@code ServerChunkCache}, the entity-storage classes, the tick loop,
 * and the scoreboard. Mods like <b>Lithium</b> and <b>C2ME</b> rewrite those exact methods — e.g.
 * Lithium's {@code chunk_access.ServerChunkCacheMixin} merges {@code getChunk} and removes the
 * {@code mainThread} field read Tachyon redirects, which is a hard mixin-apply crash at startup.
 * More fundamentally, those mods assume single-threaded ticking, so Tachyon's takeover cannot run
 * safely alongside them regardless.
 *
 * <p>When any such mod is detected, this plugin disables the takeover mixins and leaves only the
 * always-safe, read-only <b>measure mode</b> ({@link ServerLevelTickMixin}). The mod then loads and
 * reports parallelizability via {@code /tachyon regions} without touching behaviour. To actually run
 * the takeover, use a server without these mods (Tachyon replaces that perf stack).
 */
public final class TachyonMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOG = LoggerFactory.getLogger("Tachyon");

    /** Mods that rewrite chunk/entity/tick internals Tachyon's takeover also transforms. */
    private static final Set<String> CONFLICTING_MODS = Set.of(
            "lithium", "c2me", "vmp", "servercore", "tt20", "moonrise", "noisium");

    /** Takeover mixins, disabled when a conflicting mod is present. Measure mode is NOT in this set. */
    private static final Set<String> TAKEOVER_MIXINS = Set.of(
            "dev.tachyon.mixin.MinecraftServerMixin",
            "dev.tachyon.mixin.ServerChunkCacheMixin",
            "dev.tachyon.mixin.EntityTickListMixin",
            "dev.tachyon.mixin.SectionManagerAddMixin",
            "dev.tachyon.mixin.SectionManagerCallbackMixin",
            "dev.tachyon.mixin.LevelEntityGetterMixin",
            "dev.tachyon.mixin.ScoreboardMixin",
            "dev.tachyon.mixin.IntraLevelEntityLoopMixin",
            "dev.tachyon.mixin.PlayerTickDeferMixin",
            "dev.tachyon.mixin.EntityTeleportMixin",
            "dev.tachyon.mixin.ServerPlayerTeleportMixin",
            "dev.tachyon.mixin.NoiseDensityFunctionMixin",
            "dev.tachyon.mixin.ShiftedNoiseDensityFunctionMixin",
            "dev.tachyon.mixin.BlendedNoiseDensityFunctionMixin",
            "dev.tachyon.mixin.Ap2DensityFunctionMixin",
            "dev.tachyon.mixin.EntityCollisionBroadphaseMixin");

    private boolean conflictDetected;
    private String detectedMod;
    private boolean warned;

    @Override
    public void onLoad(final String mixinPackage) {
        for (String mod : CONFLICTING_MODS) {
            if (FabricLoader.getInstance().isModLoaded(mod)) {
                conflictDetected = true;
                detectedMod = mod;
                break;
            }
        }
    }

    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        if (conflictDetected && TAKEOVER_MIXINS.contains(mixinClassName)) {
            if (!warned) {
                warned = true;
                LOG.warn("Tachyon: detected '{}' (deep tick/chunk mod) — disabling the parallel takeover "
                        + "and running MEASURE MODE ONLY. The takeover requires a server without that "
                        + "perf stack; see /tachyon status.", detectedMod);
            }
            return false;
        }
        return true;
    }

    /** True if a conflicting mod forced measure-mode-only (read by {@code /tachyon status}). */
    public static boolean isMeasureOnly() {
        for (String mod : CONFLICTING_MODS) {
            if (FabricLoader.getInstance().isModLoaded(mod)) {
                return true;
            }
        }
        return false;
    }

    // --- unused IMixinConfigPlugin hooks ---
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String t, ClassNode tn, String m, IMixinInfo i) {}
    @Override public void postApply(String t, ClassNode tn, String m, IMixinInfo i) {}
}

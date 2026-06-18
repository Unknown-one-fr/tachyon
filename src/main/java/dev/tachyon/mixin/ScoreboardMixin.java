package dev.tachyon.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.tachyon.mc.ServerStateLock;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Serializes the server-global {@link Scoreboard}'s tick-time access under {@link ServerStateLock}.
 *
 * <p>Mutators reached during the parallel tick (kill/criteria score awards via {@code forAllObjectives},
 * score creation, score cleanup on entity removal) take the write lock; lookups reached during AI
 * (team/alliance checks, criteria/score reads) take the shared read lock so they stay parallel.
 * Command-driven scoreboard edits run on the server thread between parallel phases, so they aren't
 * wrapped here.
 */
@Mixin(Scoreboard.class)
public class ScoreboardMixin {

    @Unique
    private static <R> R tachyon$readLocked(Supplier<R> body) {
        if (!ServerStateLock.engaged()) {
            return body.get();
        }
        ServerStateLock.lockRead();
        try {
            return body.get();
        } finally {
            ServerStateLock.unlockRead();
        }
    }

    @Unique
    private static <R> R tachyon$writeLocked(Supplier<R> body) {
        if (!ServerStateLock.engaged()) {
            return body.get();
        }
        ServerStateLock.lockWrite();
        try {
            return body.get();
        } finally {
            ServerStateLock.unlockWrite();
        }
    }

    // --- writes (mutate the shared maps / scores during ticking) ---

    @WrapMethod(method = "forAllObjectives")
    private void tachyon$forAllObjectives(ObjectiveCriteria criteria, ScoreHolder name, Consumer<ScoreAccess> op, Operation<Void> original) {
        tachyon$writeLocked(() -> { original.call(criteria, name, op); return null; });
    }

    @WrapMethod(method = "getOrCreatePlayerScore(Lnet/minecraft/world/scores/ScoreHolder;Lnet/minecraft/world/scores/Objective;Z)Lnet/minecraft/world/scores/ScoreAccess;")
    private ScoreAccess tachyon$getOrCreatePlayerScore(ScoreHolder holder, Objective objective, boolean forceWritable, Operation<ScoreAccess> original) {
        return tachyon$writeLocked(() -> original.call(holder, objective, forceWritable));
    }

    @WrapMethod(method = "resetAllPlayerScores")
    private void tachyon$resetAllPlayerScores(ScoreHolder player, Operation<Void> original) {
        tachyon$writeLocked(() -> { original.call(player); return null; });
    }

    @WrapMethod(method = "resetSinglePlayerScore")
    private void tachyon$resetSinglePlayerScore(ScoreHolder player, Objective objective, Operation<Void> original) {
        tachyon$writeLocked(() -> { original.call(player, objective); return null; });
    }

    @WrapMethod(method = "onPlayerScoreRemoved")
    private void tachyon$onPlayerScoreRemoved(ScoreHolder player, Objective objective, Operation<Void> original) {
        tachyon$writeLocked(() -> { original.call(player, objective); return null; });
    }

    @WrapMethod(method = "onPlayerRemoved")
    private void tachyon$onPlayerRemoved(ScoreHolder player, Operation<Void> original) {
        tachyon$writeLocked(() -> { original.call(player); return null; });
    }

    @WrapMethod(method = "entityRemoved")
    private void tachyon$entityRemoved(Entity entity, Operation<Void> original) {
        tachyon$writeLocked(() -> { original.call(entity); return null; });
    }

    // --- reads (lookups during AI) ---

    @WrapMethod(method = "getObjective")
    private Objective tachyon$getObjective(String name, Operation<Objective> original) {
        return tachyon$readLocked(() -> original.call(name));
    }

    @WrapMethod(method = "getPlayersTeam")
    private PlayerTeam tachyon$getPlayersTeam(String name, Operation<PlayerTeam> original) {
        return tachyon$readLocked(() -> original.call(name));
    }

    @WrapMethod(method = "getPlayerTeam")
    private PlayerTeam tachyon$getPlayerTeam(String name, Operation<PlayerTeam> original) {
        return tachyon$readLocked(() -> original.call(name));
    }

    @WrapMethod(method = "getPlayerScoreInfo")
    private ReadOnlyScoreInfo tachyon$getPlayerScoreInfo(ScoreHolder name, Objective objective, Operation<ReadOnlyScoreInfo> original) {
        return tachyon$readLocked(() -> original.call(name, objective));
    }
}

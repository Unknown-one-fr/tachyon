package dev.tachyon.mixin;

import dev.tachyon.mc.RegionOwnable;
import net.minecraft.server.level.ServerChunkCache;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-owner chunk cache — the core of intra-level regionization.
 *
 * <p>Vanilla confines all chunk access to a single {@code mainThread}; off-thread {@code getChunk}
 * is bounced to the main executor and blocks (see {@code ServerChunkCache.getChunk}). For per-LEVEL
 * parallelism we relocate that single owner to the level's tick thread. For per-REGION parallelism
 * <em>within</em> a level, several workers tick disjoint, interaction-radius-separated regions of the
 * same level concurrently — so the cache must accept access from any of them.
 *
 * <p>We redirect every read of the {@code mainThread} field inside the thread-confinement checks:
 * if the calling thread is a registered region owner, we hand back <em>that same thread</em>, so the
 * {@code currentThread() != mainThread} guard is false and chunk access runs inline. Safety rests on
 * the region invariant: a region's entities only touch chunks within their region (interaction
 * radius), so two owners never touch the same chunk concurrently — the same single-writer argument
 * vanilla relies on, sharded per region instead of per server.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin implements RegionOwnable {

    @Shadow
    Thread mainThread;

    @Unique
    private final Set<Thread> tachyon$owners = ConcurrentHashMap.newKeySet();

    @Override
    public void tachyon$addOwner(Thread t) {
        tachyon$owners.add(t);
    }

    @Override
    public void tachyon$removeOwner(Thread t) {
        tachyon$owners.remove(t);
    }

    @Override
    public void tachyon$clearOwners() {
        tachyon$owners.clear();
    }

    @Override
    public boolean tachyon$isOwner(Thread t) {
        return tachyon$owners.contains(t);
    }

    @Redirect(
            method = {"getChunk", "getChunkNow", "getChunkFuture"},
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/server/level/ServerChunkCache;mainThread:Ljava/lang/Thread;",
                    opcode = Opcodes.GETFIELD))
    private Thread tachyon$effectiveOwner(ServerChunkCache self) {
        if (!tachyon$owners.isEmpty()) {
            Thread cur = Thread.currentThread();
            if (tachyon$owners.contains(cur)) {
                return cur; // claim ownership for this region worker -> inline access
            }
        }
        return this.mainThread;
    }
}

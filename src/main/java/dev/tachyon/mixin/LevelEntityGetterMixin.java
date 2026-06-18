package dev.tachyon.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.tachyon.mc.EntityLifecycleLock;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelEntityGetterAdapter;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Read-locks every spatial / lookup entity query during intra-level regionized ticking.
 *
 * <p>{@link LevelEntityGetterAdapter} is the single choke point for {@code getEntities}, AABB
 * queries and id/uuid lookups — all of which read the shared {@code EntityLookup} +
 * {@code EntitySectionStorage}. Taking the shared read lock keeps queries parallel across region
 * workers while excluding the brief exclusive writes done by the lifecycle mixins
 * ({@link EntityLifecycleLock}), closing the read-during-write race on entity storage.
 *
 * <p>Vanilla query consumers collect/inspect entities but never mutate storage mid-iteration, so a
 * read holder never needs the write lock (no upgrade deadlock).
 */
@Mixin(LevelEntityGetterAdapter.class)
public class LevelEntityGetterMixin {

    @Unique
    private static <R> R tachyon$readLocked(Supplier<R> body) {
        if (!EntityLifecycleLock.engaged()) {
            return body.get();
        }
        EntityLifecycleLock.lockRead();
        try {
            return body.get();
        } finally {
            EntityLifecycleLock.unlockRead();
        }
    }

    @WrapMethod(method = "get(I)Lnet/minecraft/world/level/entity/EntityAccess;")
    private EntityAccess tachyon$getById(int id, Operation<EntityAccess> original) {
        return tachyon$readLocked(() -> original.call(id));
    }

    @WrapMethod(method = "get(Ljava/util/UUID;)Lnet/minecraft/world/level/entity/EntityAccess;")
    private EntityAccess tachyon$getByUuid(UUID id, Operation<EntityAccess> original) {
        return tachyon$readLocked(() -> original.call(id));
    }

    @WrapMethod(method = "getAll()Ljava/lang/Iterable;")
    private Iterable<?> tachyon$getAll(Operation<Iterable<?>> original) {
        return tachyon$readLocked(original::call);
    }

    @WrapMethod(method = "get(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/util/AbortableIterationConsumer;)V")
    private void tachyon$getByType(EntityTypeTest type, AbortableIterationConsumer consumer, Operation<Void> original) {
        tachyon$readLocked(() -> { original.call(type, consumer); return null; });
    }

    @WrapMethod(method = "get(Lnet/minecraft/world/phys/AABB;Ljava/util/function/Consumer;)V")
    private void tachyon$getInBox(AABB bb, Consumer output, Operation<Void> original) {
        tachyon$readLocked(() -> { original.call(bb, output); return null; });
    }

    @WrapMethod(method = "get(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V")
    private void tachyon$getByTypeInBox(EntityTypeTest type, AABB bb, AbortableIterationConsumer consumer, Operation<Void> original) {
        tachyon$readLocked(() -> { original.call(type, bb, consumer); return null; });
    }
}

package dev.tachyon.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.tachyon.mc.EntityLifecycleLock;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Serializes {@link EntityTickList} structural mutations during intra-level regionized ticking.
 * The backing fastutil map is not thread-safe; concurrent add/remove from region workers (e.g. mobs
 * dying on different threads) corrupts it. Guarded by the shared {@link EntityLifecycleLock}.
 */
@Mixin(EntityTickList.class)
public class EntityTickListMixin {

    @WrapMethod(method = "add")
    private void tachyon$lockedAdd(Entity entity, Operation<Void> original) {
        if (!EntityLifecycleLock.engaged()) {
            original.call(entity);
            return;
        }
        EntityLifecycleLock.lockWrite();
        try {
            original.call(entity);
        } finally {
            EntityLifecycleLock.unlockWrite();
        }
    }

    @WrapMethod(method = "remove")
    private void tachyon$lockedRemove(Entity entity, Operation<Void> original) {
        if (!EntityLifecycleLock.engaged()) {
            original.call(entity);
            return;
        }
        EntityLifecycleLock.lockWrite();
        try {
            original.call(entity);
        } finally {
            EntityLifecycleLock.unlockWrite();
        }
    }
}

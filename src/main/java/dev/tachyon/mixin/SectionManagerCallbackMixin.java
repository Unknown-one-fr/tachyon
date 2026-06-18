package dev.tachyon.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.tachyon.mc.EntityLifecycleLock;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Serializes entity REMOVE and section-MOVE on the shared {@code PersistentEntitySectionManager}
 * during intra-level regionized ticking. {@code onRemove} (death/despawn) and {@code onMove} (an
 * entity crossing a 16-block section boundary — frequent) both restructure the shared section
 * storage, tracking, ticking and UUID maps. Guarded by {@link EntityLifecycleLock}.
 */
@Mixin(targets = "net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback")
public class SectionManagerCallbackMixin {

    @WrapMethod(method = "onMove")
    private void tachyon$lockedOnMove(Operation<Void> original) {
        if (!EntityLifecycleLock.engaged()) {
            original.call();
            return;
        }
        EntityLifecycleLock.lock();
        try {
            original.call();
        } finally {
            EntityLifecycleLock.unlock();
        }
    }

    @WrapMethod(method = "onRemove")
    private void tachyon$lockedOnRemove(Entity.RemovalReason reason, Operation<Void> original) {
        if (!EntityLifecycleLock.engaged()) {
            original.call(reason);
            return;
        }
        EntityLifecycleLock.lock();
        try {
            original.call(reason);
        } finally {
            EntityLifecycleLock.unlock();
        }
    }
}

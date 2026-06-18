package dev.tachyon.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.tachyon.mc.EntityLifecycleLock;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Serializes entity ADD into the shared {@link PersistentEntitySectionManager} (UUID set, section
 * storage, tracking/ticking registration) during intra-level regionized ticking — e.g. a mob
 * spawning drops/projectiles/offspring while ticking on a region worker. See {@link EntityLifecycleLock}.
 */
@Mixin(PersistentEntitySectionManager.class)
public class SectionManagerAddMixin {

    @WrapMethod(method = "addEntity")
    private boolean tachyon$lockedAddEntity(EntityAccess entity, boolean loaded, Operation<Boolean> original) {
        if (!EntityLifecycleLock.engaged()) {
            return original.call(entity, loaded);
        }
        EntityLifecycleLock.lock();
        try {
            return original.call(entity, loaded);
        } finally {
            EntityLifecycleLock.unlock();
        }
    }
}

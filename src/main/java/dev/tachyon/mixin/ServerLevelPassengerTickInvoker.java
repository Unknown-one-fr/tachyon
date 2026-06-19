package dev.tachyon.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerLevel.class)
public interface ServerLevelPassengerTickInvoker {
    @Invoker("tickPassenger")
    void tachyon$invokeTickPassenger(Entity vehicle, Entity entity);
}

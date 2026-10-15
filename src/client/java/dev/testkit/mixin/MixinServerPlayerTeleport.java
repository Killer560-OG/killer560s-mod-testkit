package dev.testkit.mixin;

import dev.testkit.harness.HypixelOrder;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * {@link HypixelOrder}: the integrated server's teleport, then (when a scenario asks) the pickup Hypixel does in the
 * same step. {@code public boolean teleportTo(ServerLevel, double, double, double, Set<Relative>, float, float,
 * boolean)} - javap on the 26.1.2 and 26.2 merged jars, the same on both. Every sim ability teleport goes through it.
 */
@Mixin(ServerPlayer.class)
public abstract class MixinServerPlayerTeleport {

    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FFZ)Z", at = @At("RETURN"))
    private void testkit$hypixelOrder(ServerLevel level, double x, double y, double z, Set<Relative> relative, float yaw,
                                      float pitch, boolean resetCamera, CallbackInfoReturnable<Boolean> cir) {
        HypixelOrder.afterTeleport((ServerPlayer) (Object) this);
    }
}

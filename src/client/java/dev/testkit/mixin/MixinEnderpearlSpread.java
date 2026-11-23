package dev.testkit.mixin;

import dev.testkit.harness.PearlSpread;

import net.minecraft.world.item.EnderpearlItem;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * {@link PearlSpread}: the inaccuracy argument (1.0) of {@code EnderpearlItem.use}'s
 * {@code Projectile.spawnProjectileFromRotation(factory, level, stack, shooter, 0f, 1.5f, 1f)} becomes 0 while a case
 * asks for it. javap on the 26.3 and 26.2 merged jars: the same call on both.
 */
@Mixin(EnderpearlItem.class)
public abstract class MixinEnderpearlSpread {

    @ModifyArg(method = "use", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/Projectile;"
            + "spawnProjectileFromRotation(Lnet/minecraft/world/entity/projectile/Projectile$ProjectileFactory;"
            + "Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;"
            + "Lnet/minecraft/world/entity/LivingEntity;FFF)Lnet/minecraft/world/entity/projectile/Projectile;"), index = 6)
    private float testkit$noSpread(float inaccuracy) {
        if (!PearlSpread.off) {
            return inaccuracy;
        }
        PearlSpread.removed++;
        return 0f;
    }
}

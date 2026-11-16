package dev.testkit.server.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Zombie Shootout's Carnival Dart Tube on Hypixel fires an arrow at about 4 blocks a tick (his 11-14 log: arrows seen at
 * 3.97 b/t, gravity 0.05). For a player tagged {@code legacydart} a use of the tube (a carrot on a stick) fires that arrow
 * from the eye along the server's look, with no spread, in the tick the use arrives (carnfix3-1114), so a test can count
 * real hits on moving zombies instead of reading the aim only.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class MixinDartTube {

    @Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
    private void testkit$dart(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (!stack.is(Items.CARROT_ON_A_STICK) || !player.entityTags().contains("legacydart")) {
            return;
        }
        Arrow dart = new Arrow(level, player, new ItemStack(Items.ARROW), stack);
        dart.shootFromRotation(player, player.getXRot(), player.getYRot(), 0f, 4.0f, 0f);
        dart.pickup = AbstractArrow.Pickup.DISALLOWED;
        level.addFreshEntity(dart);
        cir.setReturnValue(InteractionResult.CONSUME);
    }
}

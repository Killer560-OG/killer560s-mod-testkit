package dev.testkit.mixin;

import dev.testkit.harness.ForeignTermFilter;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link ForeignTermFilter}: AbstractContainerMenu.setItem, which a slot packet calls and a contents packet never does. */
@Mixin(AbstractContainerMenu.class)
public abstract class MixinForeignTermMenu {

    @Inject(method = "setItem(IILnet/minecraft/world/item/ItemStack;)V", at = @At("TAIL"), require = 0)
    private void testkit$foreignTermSetItem(int slot, int stateId, ItemStack stack, CallbackInfo ci) {
        if (ForeignTermFilter.mode != ForeignTermFilter.Mode.OFF) {
            ForeignTermFilter.onSetItem((AbstractContainerMenu) (Object) this, slot, stack);
        }
    }
}

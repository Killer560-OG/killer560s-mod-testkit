package dev.testkit.mixin;

import dev.testkit.harness.ForeignTermFilter;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link ForeignTermFilter}: the slotClicked HEAD cancel other mods' terminal solvers use (Odin, NoFrills). Inert unless a case turns it on. */
@Mixin(AbstractContainerScreen.class)
public abstract class MixinForeignTermScreen {

    @Shadow @Final protected AbstractContainerMenu menu;

    @Inject(method = "slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ContainerInput;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void testkit$foreignTermClick(Slot slot, int slotId, int button, ContainerInput input, CallbackInfo ci) {
        if (ForeignTermFilter.mode != ForeignTermFilter.Mode.OFF && ForeignTermFilter.onSlotClicked(menu, slot, slotId, button, input)) {
            ci.cancel();
        }
    }
}

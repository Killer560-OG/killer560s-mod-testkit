package dev.testkit.mixin.bench;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.testkit.harness.MixinBench;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

import org.spongepowered.asm.mixin.Mixin;

/** {@link MixinBench} timers around a container screen's slot and whole-screen extraction (javap: same on 26.1.2 and 26.2). */
@Mixin(AbstractContainerScreen.class)
public abstract class BenchContainerScreen {

    @WrapMethod(method = "extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/inventory/Slot;II)V", require = 0)
    private void testkit$benchSlot(GuiGraphicsExtractor g, Slot slot, int mx, int my, Operation<Void> original) {
        if (!MixinBench.enter(0)) {
            original.call(g, slot, mx, my);
            return;
        }
        try {
            original.call(g, slot, mx, my);
        } finally {
            MixinBench.exit(0);
        }
    }

    @WrapMethod(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", require = 0)
    private void testkit$benchScreen(GuiGraphicsExtractor g, int mx, int my, float pt, Operation<Void> original) {
        if (!MixinBench.enter(1)) {
            original.call(g, mx, my, pt);
            return;
        }
        try {
            original.call(g, mx, my, pt);
        } finally {
            MixinBench.exit(1);
        }
    }
}

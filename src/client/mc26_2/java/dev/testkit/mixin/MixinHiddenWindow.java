package dev.testkit.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.testkit.harness.OsInputGuard;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 26.2: with -Dtestkit.hiddenWindow=true Minecraft's one {@code glfwShowWindow(J)V} call (in its constructor, javap 26.2)
 * is skipped; createGlfwWindow already sets GLFW_VISIBLE=0. The 26.3 twin creates the SDL window hidden instead.
 */
@Mixin(Minecraft.class)
public abstract class MixinHiddenWindow {

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/glfw/GLFW;glfwShowWindow(J)V", remap = false))
    private void testkit$noShow(long handle, Operation<Void> op) {
        if (!OsInputGuard.hiddenWindow()) {
            op.call(handle);
        }
    }
}

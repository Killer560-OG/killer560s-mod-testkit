package dev.testkit.mixin;

import com.mojang.blaze3d.platform.Window;

import dev.testkit.compat.McWindowOs;
import dev.testkit.harness.OsInputGuard;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.2: the test window is created unfocused and is not focused when Minecraft shows it - see {@link OsInputGuard}.
 * javap 26.2: {@code public static long createGlfwWindow(int, int, String, long, GpuBackend)} sets GLFW_VISIBLE=0 and
 * calls {@code glfwCreateWindow(IILjava/lang/CharSequence;JJ)J}; Minecraft later calls {@code glfwShowWindow}, which
 * focuses the window while GLFW_FOCUS_ON_SHOW is at its default (true).
 */
@Mixin(Window.class)
public abstract class MixinNoGrabWindow {

    @Inject(method = "createGlfwWindow", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J", remap = false))
    private static void testkit$noFocusHints(CallbackInfoReturnable<Long> cir) {
        if (OsInputGuard.active()) {
            McWindowOs.beforeCreate();
        }
    }
}

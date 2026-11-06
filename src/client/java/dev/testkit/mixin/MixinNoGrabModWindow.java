package dev.testkit.mixin;

import dev.testkit.harness.OsInputGuard;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The mod's window facade must not reach the OS cursor or focus in a test client ({@link OsInputGuard}):
 * {@code focus} (SDL_RaiseWindow / glfwFocusWindow, Shorts), {@code setCursorHidden} (FakeCursor: SDL relative mode /
 * GLFW_CURSOR_DISABLED straight to the native API, past Fabric's InputConstants cancel) and {@code restore}
 * (SW_RESTORE activates), {@code warpCursor} (Inventory Walk's Look Key puts the OS cursor back; a test drives
 * MouseHandler.onMove directly and the mod's xpos never moves, so nothing is lost). The mod's own state (FakeCursor.hidden, which 1027-menu-term-normal-hide reads) is untouched.
 * Same signatures in the mod's mc26_2 and mc26_3 copies; inert without the mod.
 */
@Pseudo
@Mixin(targets = "com.killer560.hub.compat.McWindow", remap = false)
public abstract class MixinNoGrabModWindow {

    @Inject(method = "focus(Lcom/mojang/blaze3d/platform/Window;)V", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void testkit$noFocus(CallbackInfo ci) {
        if (OsInputGuard.active()) {
            OsInputGuard.blocked("McWindow.focus");
            ci.cancel();
        }
    }

    @Inject(method = "setCursorHidden(Lcom/mojang/blaze3d/platform/Window;ZZ)V", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void testkit$noCursorMode(CallbackInfo ci) {
        if (OsInputGuard.active()) {
            OsInputGuard.blocked("McWindow.setCursorHidden");
            ci.cancel();
        }
    }

    @Inject(method = "restore(Lcom/mojang/blaze3d/platform/Window;)V", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void testkit$noRestore(CallbackInfo ci) {
        if (OsInputGuard.active()) {
            OsInputGuard.blocked("McWindow.restore");
            ci.cancel();
        }
    }

    @Inject(method = "warpCursor(Lcom/mojang/blaze3d/platform/Window;DD)V", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void testkit$noWarp(CallbackInfo ci) {
        if (OsInputGuard.active()) {
            OsInputGuard.blocked("McWindow.warpCursor");
            ci.cancel();
        }
    }
}

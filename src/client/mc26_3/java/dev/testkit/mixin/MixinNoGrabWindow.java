package dev.testkit.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.Window;

import dev.testkit.compat.McWindowOs;
import dev.testkit.harness.OsInputGuard;

import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.3: the test window is created NOT_FOCUSABLE with SDL's activate-on-show/raise hints off, and never takes a mouse
 * grab - see {@link OsInputGuard}. javap 26.3: {@code private long createWindow(GpuBackend, int, int, String)} passes
 * the window flags (0x2020, no HIDDEN, so SDL shows it activated) as the 4th argument of
 * {@code GpuBackend.createWindow(String, int, int, long)}; {@code private void updateWindowMouseGrab()} calls
 * {@code SDL_SetWindowMouseGrab(JZ)Z}.
 */
@Mixin(Window.class)
public abstract class MixinNoGrabWindow {

    @Inject(method = "createWindow", at = @At("HEAD"))
    private void testkit$noActivateHints(CallbackInfoReturnable<Long> cir) {
        if (OsInputGuard.active()) {
            McWindowOs.beforeCreate();
        }
    }

    @ModifyArg(method = "createWindow", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuBackend;createWindow(Ljava/lang/String;IIJ)J"), index = 3)
    private long testkit$notFocusable(long flags) {
        return OsInputGuard.active() ? flags | SDLVideo.SDL_WINDOW_NOT_FOCUSABLE : flags;
    }

    @WrapOperation(method = "updateWindowMouseGrab", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/sdl/SDLVideo;SDL_SetWindowMouseGrab(JZ)Z", remap = false))
    private boolean testkit$noMouseGrab(long handle, boolean grab, Operation<Boolean> op) {
        if (grab && OsInputGuard.active()) {
            OsInputGuard.blocked("SDL_SetWindowMouseGrab");
            return op.call(handle, false);
        }
        return op.call(handle, grab);
    }
}

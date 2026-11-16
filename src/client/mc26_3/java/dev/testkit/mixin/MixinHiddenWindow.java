package dev.testkit.mixin;

import com.mojang.blaze3d.platform.Window;

import dev.testkit.harness.OsInputGuard;

import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 26.3: with -Dtestkit.hiddenWindow=true the window is created SDL_WINDOW_HIDDEN, so it never shows on any monitor or the
 * taskbar. Same flags argument MixinNoGrabWindow adds NOT_FOCUSABLE to (javap 26.3: {@code GpuBackend.createWindow(String,
 * int, int, long)} inside {@code Window.createWindow}). The 26.2 twin skips glfwShowWindow instead.
 */
@Mixin(Window.class)
public abstract class MixinHiddenWindow {

    @ModifyArg(method = "createWindow", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuBackend;createWindow(Ljava/lang/String;IIJ)J"), index = 3)
    private long testkit$hidden(long flags) {
        return OsInputGuard.hiddenWindow() ? flags | SDLVideo.SDL_WINDOW_HIDDEN : flags;
    }
}

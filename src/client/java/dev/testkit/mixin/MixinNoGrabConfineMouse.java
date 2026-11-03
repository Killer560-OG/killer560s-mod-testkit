package dev.testkit.mixin;

import dev.testkit.harness.OsInputGuard;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The mod's Confine Mouse In Menus calls user32 {@code ClipCursor} (global: it holds HIS cursor) whenever
 * {@code isWindowActive()} - which the client gametest forces true - so in a test client it never runs
 * ({@link OsInputGuard}). Its decision table ({@code decide}) stays testable; only the per-tick clip is cancelled.
 * {@code private static void tick(Minecraft)}; inert without the mod.
 */
@Pseudo
@Mixin(targets = "com.killer560.hub.window.ConfineMouseFeature", remap = false)
public abstract class MixinNoGrabConfineMouse {

    @Inject(method = "tick(Lnet/minecraft/client/Minecraft;)V", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void testkit$noClip(CallbackInfo ci) {
        if (OsInputGuard.active()) {
            OsInputGuard.blocked("ConfineMouseFeature.tick");
            ci.cancel();
        }
    }
}

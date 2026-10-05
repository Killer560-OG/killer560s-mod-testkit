package dev.testkit.mixin;

import dev.testkit.harness.FrameClock;

import net.minecraft.client.renderer.GameRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times {@code GameRenderer.extract} and {@code GameRenderer.render} for {@link FrameClock}. Both are
 * {@code public void (DeltaTracker, boolean)} on 26.1.2 and 26.2 (javap, 2026-10-05); the handlers take only
 * {@code CallbackInfo}, which Mixin accepts for any target. Read-only; inert unless a bench is recording.
 */
@Mixin(GameRenderer.class)
public abstract class MixinFrameClockGameRenderer {

    @Inject(method = "extract", at = @At("HEAD"))
    private void testkit$extractHead(CallbackInfo ci) {
        FrameClock.extractHead();
    }

    @Inject(method = "extract", at = @At("RETURN"))
    private void testkit$extractReturn(CallbackInfo ci) {
        FrameClock.extractReturn();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void testkit$renderHead(CallbackInfo ci) {
        FrameClock.renderHead();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void testkit$renderReturn(CallbackInfo ci) {
        FrameClock.renderReturn();
    }
}

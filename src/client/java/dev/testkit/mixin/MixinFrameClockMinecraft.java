package dev.testkit.mixin;

import dev.testkit.harness.FrameClock;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times {@code Minecraft.tick()} for {@link FrameClock}. {@code public void tick()} on both 26.1.2 and 26.2 (javap,
 * 2026-10-05). Read-only; inert unless a bench is recording.
 */
@Mixin(Minecraft.class)
public abstract class MixinFrameClockMinecraft {

    @Inject(method = "tick", at = @At("HEAD"))
    private void testkit$tickHead(CallbackInfo ci) {
        FrameClock.tickHead();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void testkit$tickReturn(CallbackInfo ci) {
        FrameClock.tickReturn();
    }
}

package dev.testkit.mixin;

import dev.testkit.harness.PacketWatch;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Supplies {@link PacketWatch} with a tick boundary.
 *
 * <p>"How many interactions went out on one tick" is the whole question for an automation module, and it
 * cannot be answered by timestamps: at 50 ms a tick, wall-clock bucketing puts packets from either side of
 * a boundary in the same bucket whenever the client stutters, which is exactly when a module misbehaves.
 * The real tick is the only honest divider.
 *
 * <p>At HEAD, closing the PREVIOUS tick. The first version sat at TAIL and was wrong in the one case it
 * existed to measure: Fabric's END_CLIENT_TICK handlers run after a TAIL injection, so a module ticking
 * there had its packets counted against the following tick - where no movement packet had been sent yet -
 * and the counter reported zero breaks-after-movement while the anticheat was reporting eight hundred Post
 * violations for exactly that. It was caught only because the server disagreed, which is why the scenario
 * now reconciles the two out loud instead of quoting this counter alone.
 *
 * <p>The head of tick N+1 is unambiguously after everything tick N did, whoever hooked it.
 */
@Mixin(Minecraft.class)
public abstract class MixinClientTick {

    @Inject(method = "tick", at = @At("HEAD"))
    private void testkit$endPreviousTick(CallbackInfo ci) {
        try {
            PacketWatch.endTick();
        } catch (Throwable ignored) {
        }
    }
}

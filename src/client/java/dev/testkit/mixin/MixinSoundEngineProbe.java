package dev.testkit.mixin;

import dev.testkit.harness.SoundProbe;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@link SoundProbe}'s feed: every sound handed to the engine. {@code public SoundEngine$PlayResult play(SoundInstance)}
 * on both 26.3 and 26.2 (javap, 2026-11-02). Read-only.
 */
@Mixin(SoundEngine.class)
public abstract class MixinSoundEngineProbe {

    @Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;",
            at = @At("HEAD"))
    private void testkit$soundReached(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        try {
            SoundProbe.reached(String.valueOf(instance.getIdentifier()));
        } catch (RuntimeException ignored) {
            // a probe never breaks the sound it watches
        }
    }
}

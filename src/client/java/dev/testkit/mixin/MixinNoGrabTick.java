package dev.testkit.mixin;

import dev.testkit.harness.OsInputGuard;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Samples the OS focus/cursor state every client tick for {@link OsInputGuard} ({@code public void tick()}, both versions). */
@Mixin(Minecraft.class)
public abstract class MixinNoGrabTick {

    @Inject(method = "tick", at = @At("HEAD"))
    private void testkit$sampleOsInput(CallbackInfo ci) {
        OsInputGuard.sample((Minecraft) (Object) this);
    }
}

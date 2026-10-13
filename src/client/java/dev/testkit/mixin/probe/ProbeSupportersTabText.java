package dev.testkit.mixin.probe;

import dev.testkit.harness.NameProbe;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** {@link NameProbe} timer around {@code SupportersTabText} (975-perf-nametag). Targets the mod by name; inert without it. */
@Pseudo
@Mixin(targets = "com.killer560.hub.supporters.SupportersTabText", remap = false)
public abstract class ProbeSupportersTabText {

    @Inject(method = "replace(Lnet/minecraft/client/multiplayer/PlayerInfo;Lnet/minecraft/network/chat/Component;)Lnet/minecraft/network/chat/Component;", at = @At("HEAD"), require = 0, remap = false)
    private static void testkit$headreplace8(CallbackInfoReturnable<?> ci) {
        NameProbe.enter();
    }

    @Inject(method = "replace(Lnet/minecraft/client/multiplayer/PlayerInfo;Lnet/minecraft/network/chat/Component;)Lnet/minecraft/network/chat/Component;", at = @At("RETURN"), require = 0, remap = false)
    private static void testkit$retreplace8(CallbackInfoReturnable<?> ci) {
        NameProbe.exit(8);
    }
}

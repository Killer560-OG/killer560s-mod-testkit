package dev.testkit.mixin.probe;

import dev.testkit.harness.NameProbe;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** {@link NameProbe} timer around {@code PlayerNameDisplay} (975-perf-nametag). Targets the mod by name; inert without it. */
@Pseudo
@Mixin(targets = "com.killer560.hub.supporters.PlayerNameDisplay", remap = false)
public abstract class ProbePlayerNameDisplay {

    @Inject(method = "displayNameFor(Ljava/util/UUID;)Lnet/minecraft/network/chat/Component;", at = @At("HEAD"), require = 0, remap = false)
    private static void testkit$headdisplayNameFor6(CallbackInfoReturnable<?> ci) {
        NameProbe.enter();
    }

    @Inject(method = "displayNameFor(Ljava/util/UUID;)Lnet/minecraft/network/chat/Component;", at = @At("RETURN"), require = 0, remap = false)
    private static void testkit$retdisplayNameFor6(CallbackInfoReturnable<?> ci) {
        NameProbe.exit(6);
    }

    @Inject(method = "modelScaleFor(Ljava/util/UUID;Z)F", at = @At("HEAD"), require = 0, remap = false)
    private static void testkit$headmodelScaleFor7(CallbackInfoReturnable<?> ci) {
        NameProbe.enter();
    }

    @Inject(method = "modelScaleFor(Ljava/util/UUID;Z)F", at = @At("RETURN"), require = 0, remap = false)
    private static void testkit$retmodelScaleFor7(CallbackInfoReturnable<?> ci) {
        NameProbe.exit(7);
    }
}

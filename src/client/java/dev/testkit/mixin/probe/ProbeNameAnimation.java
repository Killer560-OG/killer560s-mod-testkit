package dev.testkit.mixin.probe;

import dev.testkit.harness.NameProbe;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** {@link NameProbe} timer around {@code NameAnimation} (975-perf-nametag). Targets the mod by name; inert without it. */
@Pseudo
@Mixin(targets = "com.killer560.hub.namechanger.NameAnimation", remap = false)
public abstract class ProbeNameAnimation {

    @Inject(method = "animate(Lnet/minecraft/util/FormattedCharSequence;)Lnet/minecraft/util/FormattedCharSequence;", at = @At("HEAD"), require = 0, remap = false)
    private static void testkit$headanimate4(CallbackInfoReturnable<?> ci) {
        NameProbe.enter();
    }

    @Inject(method = "animate(Lnet/minecraft/util/FormattedCharSequence;)Lnet/minecraft/util/FormattedCharSequence;", at = @At("RETURN"), require = 0, remap = false)
    private static void testkit$retanimate4(CallbackInfoReturnable<?> ci) {
        NameProbe.exit(4);
    }

    @Inject(method = "iterate(Ljava/lang/String;Lnet/minecraft/network/chat/Style;Lnet/minecraft/util/FormattedCharSink;)Z", at = @At("HEAD"), require = 0, remap = false)
    private static void testkit$headiterate5(CallbackInfoReturnable<?> ci) {
        NameProbe.enter();
    }

    @Inject(method = "iterate(Ljava/lang/String;Lnet/minecraft/network/chat/Style;Lnet/minecraft/util/FormattedCharSink;)Z", at = @At("RETURN"), require = 0, remap = false)
    private static void testkit$retiterate5(CallbackInfoReturnable<?> ci) {
        NameProbe.exit(5);
    }
}

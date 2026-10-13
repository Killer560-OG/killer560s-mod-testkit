package dev.testkit.mixin.probe;

import dev.testkit.harness.NameProbe;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** {@link NameProbe} timer around {@code NameReplacer} (975-perf-nametag). Targets the mod by name; inert without it. */
@Pseudo
@Mixin(targets = "com.killer560.hub.namechanger.NameReplacer", remap = false)
public abstract class ProbeNameReplacer {

    @Inject(method = "replace(Lnet/minecraft/util/FormattedCharSequence;)Lnet/minecraft/util/FormattedCharSequence;", at = @At("HEAD"), require = 0, remap = false)
    private static void testkit$headreplace0(CallbackInfoReturnable<?> ci) {
        NameProbe.enter();
    }

    @Inject(method = "replace(Lnet/minecraft/util/FormattedCharSequence;)Lnet/minecraft/util/FormattedCharSequence;", at = @At("RETURN"), require = 0, remap = false)
    private static void testkit$retreplace0(CallbackInfoReturnable<?> ci) {
        NameProbe.exit(0);
    }

    @Inject(method = "replace(Ljava/lang/String;)Ljava/lang/String;", at = @At("HEAD"), require = 0, remap = false)
    private static void testkit$headreplace1(CallbackInfoReturnable<?> ci) {
        NameProbe.enter();
    }

    @Inject(method = "replace(Ljava/lang/String;)Ljava/lang/String;", at = @At("RETURN"), require = 0, remap = false)
    private static void testkit$retreplace1(CallbackInfoReturnable<?> ci) {
        NameProbe.exit(1);
    }

    @Inject(method = "replaceForWidth(Lnet/minecraft/network/chat/FormattedText;)Lnet/minecraft/util/FormattedCharSequence;", at = @At("HEAD"), require = 0, remap = false)
    private static void testkit$headreplaceForWidth2(CallbackInfoReturnable<?> ci) {
        NameProbe.enter();
    }

    @Inject(method = "replaceForWidth(Lnet/minecraft/network/chat/FormattedText;)Lnet/minecraft/util/FormattedCharSequence;", at = @At("RETURN"), require = 0, remap = false)
    private static void testkit$retreplaceForWidth2(CallbackInfoReturnable<?> ci) {
        NameProbe.exit(2);
    }

    @Inject(method = "markIfSuppressed(Lnet/minecraft/util/FormattedCharSequence;)Lnet/minecraft/util/FormattedCharSequence;", at = @At("HEAD"), require = 0, remap = false)
    private static void testkit$headmarkIfSuppressed3(CallbackInfoReturnable<?> ci) {
        NameProbe.enter();
    }

    @Inject(method = "markIfSuppressed(Lnet/minecraft/util/FormattedCharSequence;)Lnet/minecraft/util/FormattedCharSequence;", at = @At("RETURN"), require = 0, remap = false)
    private static void testkit$retmarkIfSuppressed3(CallbackInfoReturnable<?> ci) {
        NameProbe.exit(3);
    }
}

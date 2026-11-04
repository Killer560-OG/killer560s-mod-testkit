package dev.testkit.mixin.audit;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Control handlers for 1104-mixin-applied (the mixin audit): the audit must report the "broken" ones as unapplied and
 * the "good" one as applied, or its verdict on the mod means nothing. All do nothing; every injection is require = 0,
 * so the broken ones fail silently exactly as the mod's would.
 */
@Mixin(Minecraft.class)
public abstract class AuditControlMixin {

    /** Applies: Minecraft.tick exists. */
    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void testkit$auditGood(CallbackInfo ci) {
    }

    /** Never applies: no such method. */
    @Inject(method = "tickNoSuchMethodForTheAudit", at = @At("HEAD"), require = 0)
    private void testkit$auditBadMethod(CallbackInfo ci) {
    }

    /** Never applies: the method exists, the call it names does not. */
    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Ljava/lang/Math;noSuchCallForTheAudit()V"), require = 0)
    private void testkit$auditBadAt(CallbackInfo ci) {
    }

    /** Never applies: a wrapped call that is not in the method. */
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Ljava/lang/Math;noSuchCallForTheAudit2()V"), require = 0)
    private void testkit$auditBadWrap(Operation<Void> original) {
    }

    /** Never applies: no such method (a MixinExtras injector). */
    @WrapWithCondition(method = "tickNoSuchMethodForTheAudit2", at = @At(value = "INVOKE", target = "Ljava/lang/Math;abs(I)I"), require = 0)
    private boolean testkit$auditBadCondition(int v) {
        return true;
    }
}

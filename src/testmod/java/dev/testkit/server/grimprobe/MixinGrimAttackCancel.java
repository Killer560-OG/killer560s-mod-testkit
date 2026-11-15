package dev.testkit.server.grimprobe;

import com.google.gson.JsonObject;

import dev.testkit.server.hx.HxEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The anticheat's attack cancels (witheraim-1113c). GrimAC 2.3.74's Reach.onInteract (javap) cancels an attack packet
 * before the server sees it at three points: {@code teleport} - first thing, while {@code SetbackTeleportUtil
 * .shouldBlockMovement()} is true, which is every server teleport sent and not yet answered (addSentTeleport sets
 * {@code requiredSetBack} for all of them), so an attack the client sent before the teleport reached it is dropped;
 * {@code part} - an unknown entity id the server still places; {@code impossible} - a known-impossible hit or more than
 * 10 queued attacks. Each cancel is an {@code ac_attack_cancel} event ({@code entityId}, {@code reason}) a gametest can
 * count next to {@code attack_entity}, and one {@code [testkit-atkcancel]} line. Neither names the anticheat (the flag
 * reader matches its name).
 */
@Mixin(targets = "ac.grim.grimac.checks.impl.combat.Reach", remap = false)
public class MixinGrimAttackCancel {

    @Unique private static final Logger testkit$LOG = LoggerFactory.getLogger("testkit-atkcancel");
    @Unique private static final String testkit$CANCEL =
            "Lcom/github/retrooper/packetevents/event/PacketReceiveEvent;setCancelled(Z)V";

    @Inject(method = "onInteract", at = @At(value = "INVOKE", target = testkit$CANCEL, ordinal = 0, remap = false),
            require = 0, remap = false)
    private void testkit$teleport(@Coerce Object event, int entityId, @Coerce Object hand, CallbackInfo ci) {
        testkit$record(entityId, "teleport");
    }

    @Inject(method = "onInteract", at = @At(value = "INVOKE", target = testkit$CANCEL, ordinal = 1, remap = false),
            require = 0, remap = false)
    private void testkit$part(@Coerce Object event, int entityId, @Coerce Object hand, CallbackInfo ci) {
        testkit$record(entityId, "part");
    }

    @Inject(method = "onInteract", at = @At(value = "INVOKE", target = testkit$CANCEL, ordinal = 2, remap = false),
            require = 0, remap = false)
    private void testkit$impossible(@Coerce Object event, int entityId, @Coerce Object hand, CallbackInfo ci) {
        testkit$record(entityId, "impossible");
    }

    @Unique
    private static void testkit$record(int entityId, String reason) {
        JsonObject data = new JsonObject();
        data.addProperty("entityId", entityId);
        data.addProperty("reason", reason);
        HxEvents.custom("ac_attack_cancel", null, data);
        testkit$LOG.info("[testkit-atkcancel] attack on {} canceled: {}", entityId, reason);
    }
}

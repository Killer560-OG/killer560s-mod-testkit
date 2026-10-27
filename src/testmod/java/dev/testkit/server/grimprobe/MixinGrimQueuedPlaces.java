package dev.testkit.server.grimprobe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Queue;

/**
 * Probe (ap3-blockuse-1027): the anticheat's own number. {@code CheckManagerListener.handleQueuedPlaces(GrimPlayer,
 * hasLook, pitch, yaw, now)} runs on every movement packet; with a place queued it lends the place this packet's
 * rotation only when {@code now - player.lastBlockPlaceUseItem < 15} (GrimAC 2.3.74, javap), else the place is judged
 * by the rotation before (RotationPlace post-flying). One server-log line per queued place:
 * {@code [testkit-gap] place -> movement packet: N ms (lends its rotation | judged by the rotation before ...)}.
 * The logger and line never contain the anticheat's name: the testkit's flag reader matches on it.
 * Its own mixin config is optional: with no anticheat (-Pnogrim) nothing applies.
 */
@Mixin(targets = "ac.grim.grimac.events.packets.CheckManagerListener", remap = false)
public class MixinGrimQueuedPlaces {

    @Unique private static final Logger testkit$LOG = LoggerFactory.getLogger("testkit-gap");
    @Unique private static boolean testkit$warned;

    @Inject(method = "handleQueuedPlaces", at = @At("HEAD"), require = 0, remap = false)
    private static void testkit$queued(@Coerce Object player, boolean hasLook, float pitch, float yaw, long now,
                                       CallbackInfo ci) {
        try {
            GrimPlayerAccess p = (GrimPlayerAccess) player;
            Queue<?> q = p.testkit$placeUseItemPackets();
            if (q == null || q.isEmpty()) {
                return;
            }
            long gap = now - p.testkit$lastBlockPlaceUseItem();
            testkit$LOG.info(String.format(java.util.Locale.ROOT,
                    "[testkit-gap] place -> movement packet: %d ms (%s; packet look %s, rotation before %.2f/%.2f, thread %s)",
                    gap, gap < 15 && hasLook ? "lends its rotation" : "judged by the rotation before",
                    hasLook ? String.format(java.util.Locale.ROOT, "%.2f/%.2f", yaw, pitch) : "none",
                    p.testkit$yaw(), p.testkit$pitch(), Thread.currentThread().getName()));
        } catch (Throwable t) {
            if (!testkit$warned) {
                testkit$warned = true;
                testkit$LOG.warn("[testkit-gap] probe unavailable: " + t);
            }
        }
    }
}

package dev.testkit.server.grimprobe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Probe (multibreak-1102, the RotationBreak post-flying root cause). Every serverbound packet the anticheat's listener
 * receives goes into a ring with its {@code System.nanoTime()} and {@code System.currentTimeMillis()}; at every
 * {@code handleQueuedBreaks} with a dig queued (2.3.74: the dig is lent this movement packet's look only when
 * {@code now - player.lastBlockBreak < 15}, both from currentTimeMillis) one line gives the anticheat's own gap, the
 * nanoTime gap between the dig and this packet, the look in the packet and before, and the last packets received.
 * Lines: {@code [testkit-dig] ...}. The logger and line never contain the anticheat's name (the flag reader matches it).
 */
@Mixin(targets = "ac.grim.grimac.events.packets.CheckManagerListener", remap = false)
public class MixinGrimDigGap {

    @Unique private static final Logger testkit$LOG = LoggerFactory.getLogger("testkit-dig");
    @Unique private static final ArrayDeque<Object[]> testkit$RING = new ArrayDeque<>();
    @Unique private static long testkit$lastDigNanos;
    @Unique private static long testkit$lastDigMillis;
    @Unique private static boolean testkit$warned;

    @Inject(method = "onPacketReceive", at = @At("HEAD"), require = 0, remap = false)
    private void testkit$received(@Coerce Object event, CallbackInfo ci) {
        try {
            String type = String.valueOf(event.getClass().getMethod("getPacketType").invoke(event));
            long n = System.nanoTime();
            long m = System.currentTimeMillis();
            synchronized (testkit$RING) {
                testkit$RING.addLast(new Object[]{type, n, m, Thread.currentThread().getName()});
                while (testkit$RING.size() > 12) {
                    testkit$RING.removeFirst();
                }
                if (type.contains("DIGGING")) {
                    testkit$lastDigNanos = n;
                    testkit$lastDigMillis = m;
                }
            }
        } catch (Throwable t) {
            testkit$warn(t);
        }
    }

    @Inject(method = "handleQueuedBreaks", at = @At("HEAD"), require = 0, remap = false)
    private static void testkit$queued(@Coerce Object player, boolean hasLook, float pitch, float yaw, long now,
                                       CallbackInfo ci) {
        try {
            GrimPlayerAccess p = (GrimPlayerAccess) player;
            Queue<?> q = p.testkit$queuedBreaks();
            if (q == null || q.isEmpty()) {
                return;
            }
            long nowNanos = System.nanoTime();
            StringBuilder ring = new StringBuilder();
            synchronized (testkit$RING) {
                for (Object[] e : testkit$RING) {
                    ring.append(String.format(java.util.Locale.ROOT, " %s@%.2fms/%dms", e[0],
                            ((Long) e[1] - nowNanos) / 1e6, (Long) e[2] - now));
                }
            }
            long gap = now - p.testkit$lastBlockBreak();
            testkit$LOG.info(String.format(java.util.Locale.ROOT,
                    "[testkit-dig] %d dig(s) queued -> movement packet: millis gap %d (%s), nanos gap %.3f ms since the last"
                            + " dig received; packet look %s, rotation before %.2f/%.2f, last %.2f/%.2f, pos %.4f %.4f %.4f,"
                            + " thread %s; last packets:%s",
                    q.size(), gap, gap < 15 && hasLook ? "LENDS this look" : "JUDGED BY THE LOOK BEFORE",
                    (nowNanos - testkit$lastDigNanos) / 1e6,
                    hasLook ? String.format(java.util.Locale.ROOT, "%.2f/%.2f", yaw, pitch) : "none",
                    p.testkit$yaw(), p.testkit$pitch(), p.testkit$lastYaw(), p.testkit$lastPitch(),
                    p.testkit$x(), p.testkit$y(), p.testkit$z(), Thread.currentThread().getName(), ring));
        } catch (Throwable t) {
            testkit$warn(t);
        }
    }

    @Unique
    private static void testkit$warn(Throwable t) {
        if (!testkit$warned) {
            testkit$warned = true;
            testkit$LOG.warn("[testkit-dig] probe unavailable: " + t);
        }
    }
}

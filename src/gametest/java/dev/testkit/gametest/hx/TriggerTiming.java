package dev.testkit.gametest.hx;

import com.google.gson.JsonObject;

import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Predicate;

/**
 * For the per-trigger-bot delay cases (mod branch trigger-delays, 2026-10-11: "The trigger bots should all have their
 * own unique specific timer"): turn the crosshair onto a target and count how many CLIENT ticks pass before the trigger
 * bot sends its packet; the server's record of the packet ({@code type}) proves it was handled. Comparing two delays on
 * the same setup cancels every constant (the feature's first-tick bookkeeping, the gate, the packet's tick boundary),
 * leaving the delay itself.
 *
 * <p>Client ticks, not server ticks (preexisting-1026): every trigger bot counts its delay in client ticks
 * ({@code ActionGate.currentTick}, whole ticks since mod grim-post), and with up to nine test clients on the machine the
 * client and the dedicated server tick at different rates. Read on the server's clock a 400 ms delay came out 6 ticks
 * (787, 797) and a delay of 0 as 2, while the client waits exactly 8 and 0. When the client packet cannot be found
 * (an unmapped {@code type}), the server's clock is used and the note says so.
 */
public final class TriggerTiming {

    private TriggerTiming() {
    }

    /** Point the player's view at {@code at} (client side; the next movement packet carries it). */
    public static void look(Session c, Vec3 at) {
        c.onClient(mc -> {
            aim(mc, at);
            return null;
        });
    }

    /** Point the view straight up, off any target. */
    public static void lookAway(Session c) {
        c.onClient(mc -> {
            mc.player.setXRot(-90f);
            return null;
        });
    }

    /**
     * Client ticks from the moment the view is turned onto {@code at} until the client sent the packet behind the first
     * {@code type} event that {@code match} accepts (server ticks for an unmapped type), or -1 within {@code maxTicks}. {@code arm} (may be null) runs right after the turn and the
     * clock starts after it - for a trigger that only arms on a server line.
     */
    public static long ticksToFire(Session c, Vec3 at, String type, Predicate<JsonObject> match, Runnable arm,
                                   int maxTicks) {
        String kind = CLIENT_PACKET.get(type);
        List<long[]> sent = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.function.Consumer<net.minecraft.network.protocol.Packet<?>> before = c.onClient(mc -> {
            hookClock();
            java.util.function.Consumer<net.minecraft.network.protocol.Packet<?>> was = dev.testkit.harness.PacketTrace.tap;
            if (kind != null) {
                dev.testkit.harness.PacketTrace.tap = p -> {
                    if (was != null) {
                        was.accept(p);
                    }
                    if (kind.equals(packetName(p))) {
                        sent.add(new long[]{CLIENT_TICK.get()});
                    }
                };
            }
            return was;
        });
        try {
            long since = c.hx().head();
            // The look and the tick it was set in, in one client task: the trigger bot sees it from the next tick on.
            long lookTick = c.onClient(mc -> {
                aim(mc, at);
                return CLIENT_TICK.get();
            });
            if (arm != null) {
                arm.run();
            }
            long t0 = c.hx().call("server.tick").getAsLong();
            for (int i = 0; i < maxTicks; i++) {
                List<JsonObject> events = c.hx().events(since, type);
                for (JsonObject e : events) {
                    if (match == null || match.test(e)) {
                        long server = e.get("tick").getAsLong() - t0;
                        synchronized (sent) {
                            for (long[] s : sent) {
                                if (s[0] > lookTick) {
                                    c.note(String.format(java.util.Locale.ROOT, "%s: %d client tick(s) from the aim to "
                                            + "the %s packet (server clock: %d)", type, s[0] - lookTick, kind, server));
                                    return s[0] - lookTick;
                                }
                            }
                        }
                        c.note(type + ": no client " + kind + " packet seen after the aim; server clock " + server);
                        return server;
                    }
                }
                c.ctx().waitTicks(1);
            }
            return -1;
        } finally {
            if (kind != null) {
                c.onClient(mc -> {
                    dev.testkit.harness.PacketTrace.tap = before;
                    return null;
                });
            }
        }
    }

    /** The serverbound packet each Hx event type is the server's record of. */
    private static final java.util.Map<String, String> CLIENT_PACKET = java.util.Map.of(
            "term.interact", "interact", "use_entity", "interact", "attack_entity", "attack", "use_block", "use_item_on");

    /** Client ticks, counted at START_CLIENT_TICK (where the trigger bots tick and send). */
    private static final java.util.concurrent.atomic.AtomicLong CLIENT_TICK = new java.util.concurrent.atomic.AtomicLong();
    private static boolean clockHooked;

    private static void hookClock() {
        if (!clockHooked) {
            clockHooked = true;
            net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.START_CLIENT_TICK.register(
                    mc -> CLIENT_TICK.incrementAndGet());
        }
    }

    private static String packetName(net.minecraft.network.protocol.Packet<?> p) {
        net.minecraft.resources.Identifier id = p.type().id();
        return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
    }

    private static void aim(net.minecraft.client.Minecraft mc, Vec3 at) {
        Vec3 d = at.subtract(mc.player.getEyePosition());
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
    }

    /**
     * The shared verdict: both trials fired, and the second (delay {@code delayMs}) came {@code delayMs / 50} ticks
     * after the first (delay 0), within 1.5 ticks (a millisecond timer read on 50 ms ticks, at both ends).
     */
    public static void judge(Session c, String what, long t0, long tD, int delayMs) {
        double want = delayMs / 50.0;
        c.note(String.format(java.util.Locale.ROOT, "%s: delay 0 fired %d tick(s) after the aim, delay %d ms %d tick(s);"
                + " moved %d, want %.1f", what, t0, delayMs, tD, tD - t0, want));
        c.check(t0 >= 0 && tD >= 0, what + " never fired (delay 0: " + t0 + ", delay " + delayMs + " ms: " + tD + ")");
        c.check(Math.abs((tD - t0) - want) <= 1.5, String.format(java.util.Locale.ROOT,
                "%s: a %d ms delay moved the click %d tick(s), want %.1f", what, delayMs, tD - t0, want));
    }
}

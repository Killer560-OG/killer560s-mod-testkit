package dev.testkit.gametest.hx;

import com.google.gson.JsonObject;

import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Predicate;

/**
 * For the per-trigger-bot delay cases (mod branch trigger-delays, 2026-10-11: "The trigger bots should all have their
 * own unique specific timer"): turn the crosshair onto a target and read, on the SERVER's clock, how many ticks pass
 * before the trigger bot's packet is handled. Comparing two delays on the same setup cancels every constant (the
 * feature's first-tick bookkeeping, the gate, the packet's tick boundary), leaving the delay itself.
 */
public final class TriggerTiming {

    private TriggerTiming() {
    }

    /** Point the player's view at {@code at} (client side; the next movement packet carries it). */
    public static void look(Session c, Vec3 at) {
        c.onClient(mc -> {
            Vec3 d = at.subtract(mc.player.getEyePosition());
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
            mc.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
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
     * Server ticks from the moment the view is turned onto {@code at} until the first {@code type} event that
     * {@code match} accepts, or -1 within {@code maxTicks}. {@code arm} (may be null) runs right after the turn and the
     * clock starts after it - for a trigger that only arms on a server line.
     */
    public static long ticksToFire(Session c, Vec3 at, String type, Predicate<JsonObject> match, Runnable arm,
                                   int maxTicks) {
        long since = c.hx().head();
        look(c, at);
        if (arm != null) {
            arm.run();
        }
        long t0 = c.hx().call("server.tick").getAsLong();
        for (int i = 0; i < maxTicks; i++) {
            List<JsonObject> events = c.hx().events(since, type);
            for (JsonObject e : events) {
                if (match == null || match.test(e)) {
                    return e.get("tick").getAsLong() - t0;
                }
            }
            c.ctx().waitTicks(1);
        }
        return -1;
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

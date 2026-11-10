package dev.testkit.gametest;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.Ap3RuntimeTests.LANE_X0;
import static dev.testkit.gametest.Ap3RuntimeTests.node;
import static dev.testkit.gametest.Ap3RuntimeTests.onClient;
import static dev.testkit.gametest.Ap3RuntimeTests.set;

/**
 * mod ap3blinkoff-1109 (killer560, 2026-11-09: "if blink is off but i step on a blink node just have that node do nothing.
 * no effect no matter what it is set to"). Two runs of RUN -> BLINK_CHARGE (count 5, plain stop) -> RUN -> BLINK_RELEASE ->
 * STOP on a straight lane with Blink OFF: one with the Blink nodes, one with them deleted. Per tick position and the held
 * keys must equal, the STOP is reached on the same tick, and no Blink node says anything in chat. A third run with Blink ON
 * (cheat jar only) is the control: the charge must bank for AP3. Runs in the 63-ap3 session, so Grim must stay silent.
 */
final class Ap3BlinkOff1109Cases {

    private static final String CHARGE = "blink.BlinkCharge";
    private static final String CFG = "blink.BlinkConfig";
    private static final String EXEC_SHORT = "ap3.Ap3Executor";

    private Ap3BlinkOff1109Cases() {
    }

    private record Tick(double x, double y, double z, String keys, String active, int banked) {
    }

    private static boolean cheat() {
        try {
            return Class.forName("com.killer560.hub.BuildVariant").getField("CHEAT_FEATURES_ENABLED").getBoolean(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no BuildVariant in the mod under test", e);
        }
    }

    static void run(Session c) {
        ClientGameTestContext ctx = c.ctx();
        boolean cheat = cheat();
        if (!cheat) {
            Scenario.skipped("1026-ap3-blink-off", "AP3 is cheat-only: a legit jar has no AP3 route to carry Blink nodes");
            return;
        }
        c.server().command("attribute @p minecraft:movement_speed base set 0.10");
        c.server().sync(20);
        boolean gate = onClient(ctx, mc -> {
            Mod.staticCall("blink.BlinkFeature", "end", "test setup");
            Mod.staticCall(CHARGE, "reset", "test setup");
            boolean g = (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled");
            Mod.staticCall("util.SkyblockGate", "setEnabled", false);
            Mod.setField("blink.BlinkFeature", "keyHeldForTest", null);
            Mod.setField(CHARGE, "keyHeldForTest", null);
            Mod.set(CFG, "setChargeTicks", 20);
            Mod.set(CFG, "setChargeKeyCode", -1);
            Mod.set(CFG, "setChatMessages", false);
            return g;
        });
        try {
            long mark = LogTap.mark();
            List<Tick> with = trace(c, true, false);
            int skipped = 0;
            for (String l : LogTap.since(mark)) {
                if (l.contains("skipped: Blink is off")) {
                    skipped++;
                }
                c.check(!l.contains("waits: Blink") && !l.contains("waiting - Blink") && !(l.contains("(chat)") && l.contains("Blink")),
                        "a Blink node spoke with Blink off: " + l);
            }
            c.check(skipped == 2, "expected one INFO skip line per Blink node run (2), saw " + skipped);
            List<Tick> without = trace(c, false, false);
            c.check(!with.isEmpty() && !without.isEmpty(), "a run recorded nothing");
            int n = Math.min(with.size(), without.size());
            double worst = 0;
            int keyDiff = 0;
            for (int i = 0; i < n; i++) {
                Tick a = with.get(i);
                Tick b = without.get(i);
                worst = Math.max(worst, Math.max(Math.abs(a.x() - b.x()), Math.max(Math.abs(a.y() - b.y()), Math.abs(a.z() - b.z()))));
                if (!a.keys().equals(b.keys())) {
                    keyDiff++;
                }
                c.check(a.banked() == 0, "Blink off but something banked at tick " + i);
            }
            int stopWith = firstStop(with);
            int stopWithout = firstStop(without);
            c.note(String.format(Locale.ROOT, "Blink OFF, nodes vs deleted: %d vs %d ticks, STOP active at %d vs %d, worst position gap %.6f, "
                    + "tick(s) with other keys %d, skip lines %d", with.size(), without.size(), stopWith, stopWithout, worst, keyDiff, skipped));
            c.check(stopWith > 0 && stopWithout > 0, "the route never reached its STOP (" + stopWith + ", " + stopWithout + ") - nothing proven");
            c.check(stopWith == stopWithout, "arrival tick differs: " + stopWith + " with the nodes, " + stopWithout + " without");
            c.check(worst < 1.0e-4, "positions differ by " + worst + " blocks between the route with Blink nodes and without");
            c.check(keyDiff == 0, keyDiff + " tick(s) pressed other keys with the Blink nodes in the route");
            if (cheat) {
                List<Tick> on = trace(c, true, true);
                int maxBank = 0;
                for (Tick t : on) {
                    maxBank = Math.max(maxBank, t.banked());
                }
                c.note("Blink ON control: max bank " + maxBank + ", STOP at " + firstStop(on));
                c.check(maxBank >= 5, "control: with Blink ON the charge node banked only " + maxBank + " (it did not act)");
            } else {
                c.note("legit jar: Blink cannot run; the route ran through the nodes untouched (checked above)");
            }
        } finally {
            onClient(ctx, mc -> {
                Mod.set(CFG, "setEnabled", false);
                Mod.staticCall("util.SkyblockGate", "setEnabled", gate);
                return null;
            });
        }
    }

    private static int firstStop(List<Tick> l) {
        for (int i = 0; i < l.size(); i++) {
            if ("STOP".equals(l.get(i).active())) {
                return i;
            }
        }
        return -1;
    }

    /** One run of the lane; ticks are numbered from the first tick the RUN node's walk is carrying him. */
    private static List<Tick> trace(Session c, boolean blinkNodes, boolean blinkOn) {
        ClientGameTestContext ctx = c.ctx();
        double z = Ap3RuntimeTests.lane();
        onClient(ctx, mc -> {
            Mod.staticCall("blink.BlinkFeature", "end", "test setup");
            Mod.staticCall(CHARGE, "reset", "test setup");
            Mod.set(CFG, "setEnabled", blinkOn);
            return null;
        });
        double cx = LANE_X0 + 10.5;
        Object run = Ap3RuntimeTests.runNode(z);
        List<Object> chain = new ArrayList<>();
        if (blinkNodes) {
            Object charge = node("BLINK_CHARGE", cx, z, 0f, 0f, 2.0, 2.0);
            set(charge, "blinkTicks", 5);
            set(charge, "blinkStop", true);
            chain.add(charge);
        }
        chain.add(node("RUN", cx, z, -90f, 0f, 2.0, 2.0));
        if (blinkNodes) {
            chain.add(node("BLINK_RELEASE", cx + 9, z, 0f, 0f, 2.0, 2.0));
        }
        chain.add(node("STOP", cx + 20, z, 0f, 0f, 2.0, 2.0));
        Ap3RuntimeTests.walkIntoRun(c, z, run, chain.toArray());
        List<Tick> out = new ArrayList<>();
        for (int t = 0; t < 400; t++) {
            ctx.waitTick();
            Tick s = onClient(ctx, mc -> {
                Object n = Mod.field(EXEC_SHORT, "activeNode");
                String active = n == null ? "none" : String.valueOf(Ap3RuntimeTests.field(n, "type"));
                return new Tick(mc.player.getX(), mc.player.getY(), mc.player.getZ() - z,
                        String.valueOf(mc.player.input.keyPresses), active,
                        ((Boolean) Mod.staticCall(CHARGE, "isActive")) ? ((Number) Mod.staticCall(CHARGE, "banked")).intValue() : 0);
            });
            out.add(s);
            boolean running = onClient(ctx, mc -> (Boolean) ModUnderTest.staticCall(Ap3RuntimeTests.EXEC, "isRunning"));
            if (t > 20 && (!running || "STOP".equals(s.active()))) {
                break;
            }
        }
        ctx.waitTicks(10);
        return out;
    }
}

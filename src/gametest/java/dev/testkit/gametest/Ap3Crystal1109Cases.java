package dev.testkit.gametest;

import dev.testkit.gametest.hx.Session;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.lang.reflect.Method;
import java.util.Locale;

import static dev.testkit.gametest.Ap3RuntimeTests.EXEC;
import static dev.testkit.gametest.Ap3RuntimeTests.LANE_X0;
import static dev.testkit.gametest.Ap3RuntimeTests.node;
import static dev.testkit.gametest.Ap3RuntimeTests.onClient;
import static dev.testkit.gametest.Ap3RuntimeTests.set;

/**
 * mod ap3crystal-1109: the {@code crystals:<1|2>} suffix (killer560, 2026-11-09: "add a crystal tracker as a suffix for ap3
 * ... if a crystal is placed within 5 blocks of the user ... 1 or 2 for how many it waits for").
 * <ul>
 *   <li>1109-ap3-crystal-1 / -2: a Walk with the suffix holds; a crystal standing before the wait and one 6 blocks away do
 *       not count; with 2, the first near crystal still holds; the last near one releases it that tick and the Walk goes.</li>
 *   <li>1109-ap3-crystal-store: the setting saves, loads, and an old node (no key) loads as Off; the command text
 *       carries it.</li>
 * </ul>
 */
final class Ap3Crystal1109Cases {

    private Ap3Crystal1109Cases() {
    }

    static void register(Session s) {
        s.test("1109-ap3-crystal-1", c -> crystalCase(c, 1));
        s.test("1109-ap3-crystal-2", c -> crystalCase(c, 2));
        s.test("1109-ap3-crystal-store", Ap3Crystal1109Cases::store);
    }

    private static void summon(Session c, double x, double y, double z) {
        c.server().command(String.format(Locale.ROOT, "summon minecraft:end_crystal %.2f %.2f %.2f {ShowBottom:0b}", x, y, z));
        c.server().sync(20);
        c.ctx().waitTicks(3);
    }

    private static Object gated(ClientGameTestContext ctx) {
        return onClient(ctx, mc -> ModUnderTest.staticCall(EXEC, "leapGateNode"));
    }

    private static void crystalCase(Session c, int n) {
        ClientGameTestContext ctx = c.ctx();
        double z = Ap3RuntimeTests.lane();
        double ax = LANE_X0 + 10.5;
        c.server().command("kill @e[type=minecraft:end_crystal]");
        try {
            Object run = Ap3RuntimeTests.runNode(z);
            Object stop = node("STOP", ax, z, -90f, 0f, 3.0, 3.0);
            Object walk = node("WALK", ax, z, 0f, 0f, 3.0, 3.0);
            set(walk, "crystalWait", n);
            // A crystal standing before the wait began, 2 blocks from the box: it must never count.
            double fy = onClient(ctx, mc -> mc.player.getY());
            summon(c, ax + 2, fy, z + 2);
            Ap3RuntimeTests.walkIntoRun(c, z, run, stop, walk);
            double[] p = {0, 0, 0};
            for (int k = 0; k < 100 && gated(ctx) != walk; k++) {
                ctx.waitTick();
            }
            c.check(gated(ctx) == walk, "the crystals:" + n + " Walk was not held by the gate");
            double[] at = onClient(ctx, mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
            ctx.waitTicks(20);
            double z0 = onClient(ctx, mc -> mc.player.getZ());
            c.check(gated(ctx) == walk && Math.abs(z0 - at[2]) < 1e-3,
                    "the Walk went with only a crystal that was already standing");
            summon(c, at[0] + 6, at[1], at[2]);
            ctx.waitTicks(5);
            c.check(gated(ctx) == walk, "a crystal 6 blocks away released the Walk");
            summon(c, at[0] + 3, at[1], at[2]);
            if (n == 2) {
                ctx.waitTicks(5);
                c.check(gated(ctx) == walk, "1 of 2 crystals released the Walk");
                summon(c, at[0] - 3, at[1], at[2]);
            }
            int released = -1;
            for (int k = 0; k < 6 && released < 0; k++) {
                if (gated(ctx) != walk) {
                    released = k;
                }
                ctx.waitTick();
            }
            for (int k = 0; k < 40; k++) {
                ctx.waitTick();
            }
            double zEnd = onClient(ctx, mc -> mc.player.getZ());
            c.note(String.format(Locale.ROOT, "crystals:%d released after %d ticks, walked %+.2f", n, released, zEnd - at[2]));
            c.check(released >= 0 && zEnd - at[2] > 2.0, "crystals:" + n + " never released the Walk (walked "
                    + String.format(Locale.ROOT, "%.2f", zEnd - at[2]) + ")");
        } finally {
            c.server().command("kill @e[type=minecraft:end_crystal]");
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
        }
    }

    private static void store(Session c) {
        Object n = node("WALK", 1, 1, 0f, 0f, 2.0, 2.0);
        set(n, "crystalWait", 2);
        try {
            Class<?> st = Class.forName(Ap3RuntimeTests.STORE);
            Method w = st.getDeclaredMethod("writeNode", Class.forName("com.killer560.hub.ap3.Ap3Node"));
            w.setAccessible(true);
            Object json = w.invoke(null, n);
            Method r = st.getDeclaredMethod("readNode", Class.forName("com.google.gson.JsonObject"), int.class);
            r.setAccessible(true);
            Object back = r.invoke(null, json, 3);
            c.check(n.getClass().getField("crystalWait").getInt(back) == 2, "crystalWait did not survive save/load: " + json);
            Object old = w.invoke(null, node("WALK", 1, 1, 0f, 0f, 2.0, 2.0));
            c.check(!old.toString().contains("crystalWait"), "an Off node wrote a crystalWait key: " + old);
            Object offBack = r.invoke(null, old, 3);
            c.check(n.getClass().getField("crystalWait").getInt(offBack) == 0, "an old node did not load as Off");
            c.check(n.toString().contains("crystals:2") || String.valueOf(json).contains("\"crystalWait\":2"), "no crystals:2 in the node");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("crystal store roundtrip", e);
        }
    }
}

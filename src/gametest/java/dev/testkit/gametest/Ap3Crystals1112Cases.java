package dev.testkit.gametest;

import dev.testkit.gametest.hx.Session;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import java.util.Locale;
import static dev.testkit.gametest.Ap3RuntimeTests.EXEC;
import static dev.testkit.gametest.Ap3RuntimeTests.LANE_X0;
import static dev.testkit.gametest.Ap3RuntimeTests.node;
import static dev.testkit.gametest.Ap3RuntimeTests.onClient;
import static dev.testkit.gametest.Ap3RuntimeTests.set;

/**
 * ap3crystals-1112: the {@code crystals:<n>} node modifier waits on Energy Crystal PLACEMENTS (Maxor's Crystals active
 * phases: each Maxor crystal phase ends on its own "2/2 Energy Crystals are now active!" line.
 * <ul>
 *   <li>1112-ap3-crystals-wait: a Walk with crystals:2 holds through phase 1's 2/2 and goes within a tick of phase 2's 2/2.</li>
 *   <li>1112-ap3-crystals-already: both phases done when he steps on the node: it goes at once.</li>
 *   <li>1112-ap3-crystals-save: the modifier survives the route file write and read; an old node reads 0.</li>
 * </ul>
 */
final class Ap3Crystals1112Cases {

    private static final String MAXOR = "com.killer560.hub.maxor.MaxorCrystalsFeature";

    private Ap3Crystals1112Cases() {
    }

    static void register(Session s) {
        s.test("1112-ap3-crystals-wait", Ap3Crystals1112Cases::waitCase);
        s.test("1112-ap3-crystals-already", Ap3Crystals1112Cases::already);
        s.test("1112-ap3-crystals-save", Ap3Crystals1112Cases::save);
    }

    private static void active(Session c, int n) {
        c.server().command("tellraw @a \"" + n + "/2 Energy Crystals are now active!\"");
        c.server().sync(20);
        c.ctx().waitTicks(3);
    }

    private static int phases(ClientGameTestContext ctx) {
        return onClient(ctx, mc -> ((Number) ModUnderTest.staticCall(MAXOR, "phasesDone")).intValue());
    }

    private static void clear(ClientGameTestContext ctx) {
        onClient(ctx, mc -> ModUnderTest.staticCall(MAXOR, "reset"));
    }

    private static void waitCase(Session c) {
        ClientGameTestContext ctx = c.ctx();
        double z = Ap3RuntimeTests.lane();
        double ax = LANE_X0 + 10.5;
        clear(ctx);
        try {
            Object run = Ap3RuntimeTests.runNode(z);
            Object stop = node("STOP", ax, z, -90f, 0f, 3.0, 3.0);
            Object walk = node("WALK", ax, z, 0f, 0f, 3.0, 3.0);
            set(walk, "crystalWait", 2);
            Ap3RuntimeTests.walkIntoRun(c, z, run, stop, walk);
            c.waitUntil("the crystal gate to hold on the Walk", mc -> ModUnderTest.staticCall(EXEC, "leapGateNode") == walk, 60);
            ctx.waitTicks(10);
            double z0 = onClient(ctx, mc -> mc.player.getZ());
            active(c, 1);
            ctx.waitTicks(12);
            active(c, 2);
            ctx.waitTicks(15);
            double zMid = onClient(ctx, mc -> mc.player.getZ());
            boolean held = onClient(ctx, mc -> ModUnderTest.staticCall(EXEC, "leapGateNode") == walk);
            c.note(String.format(Locale.ROOT, "phase 1 done: phases %d, gate still on the Walk %s, moved %.3f", phases(ctx), held, zMid - z0));
            c.check(held && Math.abs(zMid - z0) < 1e-3, "the Walk went after one phase of two");
            active(c, 1);
            ctx.waitTicks(12);
            c.server().command("tellraw @a \"2/2 Energy Crystals are now active!\"");
            int doneAt = -1;
            int releasedAt = -1;
            double zEnd = z0;
            for (int k = 0; k < 60; k++) {
                ctx.waitTick();
                Object[] r = onClient(ctx, mc -> new Object[]{((Number) ModUnderTest.staticCall(MAXOR, "phasesDone")).intValue(),
                        ModUnderTest.staticCall(EXEC, "leapGateNode") == null, mc.player.getZ()});
                if ((Integer) r[0] >= 2 && doneAt < 0) {
                    doneAt = k;
                }
                if ((Boolean) r[1] && releasedAt < 0) {
                    releasedAt = k;
                }
                zEnd = (Double) r[2];
            }
            c.note(String.format(Locale.ROOT, "phase 2: phases reached 2 at tick %d, gate released at tick %d, walked %+.2f", doneAt, releasedAt, zEnd - z0));
            c.check(doneAt >= 0, "the second phase never counted");
            c.check(releasedAt >= doneAt && releasedAt <= doneAt + 1, "the gate released at " + releasedAt + ", phases hit 2 at " + doneAt);
            c.check(zEnd - z0 > 2.0, String.format(Locale.ROOT, "the Walk did not go (%.2f)", zEnd - z0));
        } finally {
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
            clear(ctx);
        }
    }

    private static void already(Session c) {
        ClientGameTestContext ctx = c.ctx();
        double z = Ap3RuntimeTests.lane();
        double ax = LANE_X0 + 10.5;
        clear(ctx);
        try {
            for (int ph = 0; ph < 2; ph++) {
                active(c, 1);
                ctx.waitTicks(12);
                active(c, 2);
                ctx.waitTicks(12);
            }
            c.check(phases(ctx) == 2, "phases do not read 2 after both phases (" + phases(ctx) + ")");
            Object run = Ap3RuntimeTests.runNode(z);
            Object stop = node("STOP", ax, z, -90f, 0f, 3.0, 3.0);
            Object walk = node("WALK", ax, z, 0f, 0f, 3.0, 3.0);
            set(walk, "crystalWait", 2);
            Ap3RuntimeTests.walkIntoRun(c, z, run, stop, walk);
            double zEnd = z;
            for (int k = 0; k < 160 && zEnd - z <= 2.0; k++) {
                ctx.waitTick();
                zEnd = onClient(ctx, mc -> mc.player.getZ());
            }
            c.note(String.format(Locale.ROOT, "both phases already done: walked %+.2f", zEnd - z));
            c.check(zEnd - z > 2.0, "a crystals:2 Walk did not go with both phases already done");
        } finally {
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
            clear(ctx);
        }
    }

    private static void save(Session c) {
        ClientGameTestContext ctx = c.ctx();
        Object walk = node("WALK", 0.5, 0.5, 0f, 0f, 3.0, 3.0);
        set(walk, "crystalWait", 2);
        Object back = onClient(ctx, mc -> {
            Object json = ModUnderTest.staticCall(Ap3RuntimeTests.STORE, "writeNode", new Class<?>[]{walk.getClass()}, new Object[]{walk});
            c.check(json.toString().contains("\"crystalWait\":2"), "the node file does not carry crystalWait: " + json);
            return ModUnderTest.staticCall(Ap3RuntimeTests.STORE, "readNode", new Class<?>[]{json.getClass(), int.class}, new Object[]{json, 2});
        });
        int got = onClient(ctx, mc -> {
            try {
                return back.getClass().getField("crystalWait").getInt(back);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        c.note("crystalWait after write + read: " + got);
        c.check(got == 2, "crystalWait did not survive the route file (" + got + ")");
        Object plain = node("WALK", 0.5, 0.5, 0f, 0f, 3.0, 3.0);
        Object old = onClient(ctx, mc -> {
            Object json = ModUnderTest.staticCall(Ap3RuntimeTests.STORE, "writeNode", new Class<?>[]{plain.getClass()}, new Object[]{plain});
            c.check(!json.toString().contains("crystalWait"), "a node without the modifier writes crystalWait");
            return ModUnderTest.staticCall(Ap3RuntimeTests.STORE, "readNode", new Class<?>[]{json.getClass(), int.class}, new Object[]{json, 2});
        });
        int zero = onClient(ctx, mc -> {
            try {
                return old.getClass().getField("crystalWait").getInt(old);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        c.check(zero == 0, "an old node reads crystalWait " + zero);
    }
}

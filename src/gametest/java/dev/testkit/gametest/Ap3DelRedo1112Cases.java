package dev.testkit.gametest;

import dev.testkit.gametest.hx.Session;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.Ap3RuntimeTests.FEATURE;
import static dev.testkit.gametest.Ap3RuntimeTests.LANE_X0;
import static dev.testkit.gametest.Ap3RuntimeTests.node;
import static dev.testkit.gametest.Ap3RuntimeTests.onClient;

/**
 * mod delredo-1112. killer560, 2026-11-12: "if i use the delete command and then a redo it should redo that delete".
 * Three nodes 10 blocks apart, he stands on the middle one. (a) delete #2 by number, undo puts it back at #2 with its
 * width, redo deletes it again; (b) the same with the bare nearest delete; (c) delete, then redo with nothing undone
 * brings the deleted node back (as /ar redo does), and a second delete + undo + redo still works.
 */
final class Ap3DelRedo1112Cases {

    private Ap3DelRedo1112Cases() {
    }

    static void register(Session s) {
        s.test("1112-ap3-delete-redo", Ap3DelRedo1112Cases::run);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> nodes() {
        Object store = ModUnderTest.staticCall(Ap3RuntimeTests.STORE, "getInstance");
        try {
            Object chain = store.getClass().getMethod("forAreaOrCreate", Class.forName(Ap3RuntimeTests.AREA),
                    Class.forName("com.killer560.hub.dungeonclass.DungeonClass"))
                    .invoke(store, ModUnderTest.enumValue(Ap3RuntimeTests.AREA, "BOSS"), null);
            return new ArrayList<>((List<Object>) ModUnderTest.call(chain, "nodes", new Class<?>[]{}, new Object[]{}));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("chain", e);
        }
    }

    private static boolean call(ClientGameTestContext ctx, String method, Object... args) {
        return onClient(ctx, mc -> {
            Class<?>[] types = args.length == 0 ? new Class<?>[]{} : new Class<?>[]{int.class};
            return (Boolean) ModUnderTest.staticCall(FEATURE, method, types, args);
        });
    }

    private static List<Object> now(ClientGameTestContext ctx) {
        return onClient(ctx, mc -> nodes());
    }

    private static void run(Session c) {
        ClientGameTestContext ctx = c.ctx();
        double z = Ap3RuntimeTests.lane();
        Object[] n = new Object[3];
        for (int i = 0; i < 3; i++) {
            n[i] = node("WALK", LANE_X0 + 0.5 + 10.0 * i, z, -90f, 0f, 1.0, 3.0 + i);
        }
        c.server().command(String.format(Locale.ROOT, "tp @p %.2f %d %.2f -90 0", LANE_X0 + 10.5, Ap3RuntimeTests.FLOOR + 1, z));
        c.server().sync(20);
        ctx.waitTicks(15);
        onClient(ctx, mc -> {
            ModUnderTest.staticCall(Ap3RuntimeTests.EXEC, "stop", new Class<?>[]{String.class}, new Object[]{"test"});
            ModUnderTest.staticCall(FEATURE, "setForceDungeon", new Class<?>[]{boolean.class}, new Object[]{true});
            Ap3RuntimeTests.setChain(n);
            return null;
        });
        // (a) by number
        c.check(call(ctx, "deleteNode", 1), "delete #2 by number returned false");
        List<Object> l = now(ctx);
        c.check(l.size() == 2 && l.get(0) == n[0] && l.get(1) == n[2], "after delete #2 the chain is not [n0, n2]: " + l.size());
        c.check(call(ctx, "undoLastAdded"), "undo returned false");
        l = now(ctx);
        c.check(l.size() == 3 && l.get(1) == n[1], "undo did not put the node back at #2");
        c.check(Double.valueOf(4.0).equals(onClient(ctx, mc -> {
            try {
                return n[1].getClass().getField("width").get(n[1]);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        })), "the restored node lost its width");
        c.check(call(ctx, "redo"), "redo after undo returned false");
        l = now(ctx);
        c.check(l.size() == 2 && l.get(1) == n[2], "redo did not delete #2 again");
        // (c) redo with nothing undone: the delete comes back
        c.check(call(ctx, "redo"), "redo right after a delete (nothing undone) returned false");
        l = now(ctx);
        c.check(l.size() == 3 && l.get(1) == n[1], "redo after a delete did not bring the node back at #2");
        // (b) bare nearest delete: he stands on node #2
        c.check(call(ctx, "deleteNearestNode"), "bare delete returned false");
        l = now(ctx);
        c.check(l.size() == 2 && l.get(0) == n[0] && l.get(1) == n[2], "bare delete did not take the middle node");
        c.check(call(ctx, "undoLastAdded"), "undo of the bare delete returned false");
        c.check(now(ctx).size() == 3, "undo of the bare delete did not restore it");
        c.check(call(ctx, "redo"), "redo after the undone bare delete returned false");
        l = now(ctx);
        c.check(l.size() == 2 && l.get(1) == n[2], "redo did not re-delete the nearest node");
        onClient(ctx, mc -> {
            Ap3RuntimeTests.setChain();
            return null;
        });
    }
}

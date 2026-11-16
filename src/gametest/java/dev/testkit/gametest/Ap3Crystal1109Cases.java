package dev.testkit.gametest;

import dev.testkit.gametest.hx.Session;

import java.lang.reflect.Method;

import static dev.testkit.gametest.Ap3RuntimeTests.node;
import static dev.testkit.gametest.Ap3RuntimeTests.set;

/**
 * mod ap3crystal-1109: the {@code crystals:<n>} suffix. 1109-ap3-crystal-1 / -2 (a crystal entity within 5 blocks releases
 * the Walk) were removed 11-14: ap3crystals-1112 made the wait count finished crystal phases (each "2/2 Energy Crystals
 * are now active!" line), so they failed on every build; 1112-ap3-crystals-wait / -already cover the new behavior.
 * <ul>
 *   <li>1109-ap3-crystal-store: the setting saves, loads, and an old node (no key) loads as Off; the command text
 *       carries it.</li>
 * </ul>
 */
final class Ap3Crystal1109Cases {

    private Ap3Crystal1109Cases() {
    }

    static void register(Session s) {
        s.test("1109-ap3-crystal-store", Ap3Crystal1109Cases::store);
    }

    static boolean cheatBuild() {
        try {
            return Class.forName("com.killer560.hub.BuildVariant").getField("CHEAT_FEATURES_ENABLED").getBoolean(null);
        } catch (ReflectiveOperationException e) {
            return true;
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

package dev.testkit.gametest.boss;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * 416-418: AP3's Term Aura node ({@code ap3/Ap3Executor.tickTermAura}, cheat build) against {@code boss.term.*}
 * (hx/boss/HxTerminalStand). The node clicks the nearest terminal in range once when its box is entered, and it works
 * with the Terminal Aura SETTING off (killer560, 2026-09-22: "It should effectively toggle term aura for a packet ...
 * But not actually turn the setting on or off") - so every case here runs with Terminal Aura switched OFF, and any
 * click the server sees is the node's.
 *
 * <p>The chain is the shape he places: a TERM_AURA node and a TERMINAL node on the same spot, the TERM_AURA first. The
 * Term Aura opens the terminal, the TERMINAL node then waits for "&lt;you&gt; completed a terminal!", and AP3 must still
 * be running the chain when the terminal is open - the proof the click did not derail it (a screen AP3 did not ask
 * for, or the body turn reading as "you moved the camera", would each stop everything).
 * <ul>
 *   <li>416 (cheat jar): the terminal directly BEHIND him, Turn To Terminal on (the default). The click must reach the
 *       server and open the terminal, GrimAC must stay silent, the TERMINAL node must take over and finish on the
 *       completion line, the CAMERA must never move, and the body must be given back to where he was facing.</li>
 *   <li>417 (cheat jar): the terminal in FRONT of him - the click needs no turn. Same judgement; this is the case that
 *       says whether the node's click goes out at a point in the tick GrimAC accepts at all.</li>
 *   <li>418 (cheat jar, EXPECTING flags): behind, Turn To Terminal off - the click goes out with a look ray that misses
 *       the stand. Recorded, not judged: what GrimAC says about it is the reason the turn exists.</li>
 * </ul>
 * Own coordinates (pillar at x 60, z 40), away from 409/414/415's.
 */
final class Ap3TermAuraCases {

    static final String EXEC = "ap3.Ap3Executor";
    static final String FEATURE = "ap3.Ap3Feature";
    static final String AP3_CONFIG = "ap3.Ap3Config";
    static final String NODE = "com.killer560.hub.ap3.Ap3Node";
    static final String TYPE = "com.killer560.hub.ap3.Ap3Node$Type";

    static final int X = 60;
    static final int Z = 40;
    static final int G = 151;

    private Ap3TermAuraCases() {
    }

    static void register(Session s) {
        if (!Mod.isCheat()) {
            return; // AP3's executor runs in both, but Terminal Aura (which the node calls) is compiled out of legit
        }
        s.test("416-boss-ap3-termaura-behind", c -> run(c, Mode.BEHIND_TURN));
        s.test("417-boss-ap3-termaura-front", c -> run(c, Mode.FRONT));
        s.testExpectingFlags("418-boss-ap3-termaura-behind-noturn", c -> run(c, Mode.BEHIND_NOTURN));
    }

    enum Mode { BEHIND_TURN, FRONT, BEHIND_NOTURN }

    static void run(Session c, Mode mode) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        try {
            c.hx().call("boss.term.build", "x", X, "z", Z, "groundY", G);
            TerminalAuraCases.tp(c, X - 1.5, G + 3, Z + 0.5, -90f);
            JsonObject st = c.hx().call("boss.term.spawn").getAsJsonObject();
            int standId = st.get("standId").getAsInt();
            c.waitUntil("the client to have the terminal stand", mc -> mc.level.getEntity(standId) != null, 200);
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
            c.hx().chat(TerminalAuraCases.GOLDOR);
            c.waitUntil("Floor7Tracker phase P3",
                    mc -> "P3".equals(String.valueOf(Mod.staticCall(TerminalAuraCases.TRACKER, "getPhase"))), 100);

            String ta = TerminalAuraCases.CONFIG;
            undo.add(c.onClient(mc -> Mod.with(ta, "Enabled", false))); // the node must work with the setting OFF
            undo.add(c.onClient(mc -> Mod.with(ta, "Range", 3.0)));
            undo.add(c.onClient(mc -> Mod.with(ta, "TurnToTerminal", mode != Mode.BEHIND_NOTURN)));
            undo.add(c.onClient(mc -> Mod.with(AP3_CONFIG, "Enabled", true)));
            undo.add(c.onClient(mc -> Mod.with(AP3_CONFIG, "ChatFeedback", true)));

            float yaw = mode == Mode.FRONT ? -90f : 90f; // -90 faces +x, the stand; 90 has it directly behind
            double px = X - 1.5;
            double pz = Z + 0.5;
            c.onClient(mc -> {
                Mod.staticCall(EXEC, "stop", "test lane");
                Mod.staticCall(FEATURE, "setForceDungeon", true);
                setChain();
                return null;
            });
            TerminalAuraCases.closeScreen(c);
            TerminalAuraCases.tp(c, px, G + 3, pz, yaw);
            c.ctx().waitTicks(5);
            int before = c.events("term.interact").size();
            Object termAura = node("TERM_AURA", px, G + 3, pz);
            Object terminal = node("TERMINAL", px, G + 3, pz);
            float[] camera = {Float.NaN, Float.NaN, 0f}; // start yaw, start pitch, worst deviation seen
            c.onClient(mc -> {
                camera[0] = mc.player.getViewYRot(1f);
                camera[1] = mc.player.getViewXRot(1f);
                setChain(termAura, terminal);
                return null;
            });
            if (mode == Mode.BEHIND_NOTURN) {
                // Recorded, not judged: GrimAC drops an interact it flags as Hitboxes, so the server may never see it -
                // the flags in this case's report are the evidence the node clicked (and what about).
                c.ctx().waitTicks(40);
                c.note(mode + ": interacts the server handled " + (c.events("term.interact").size() - before)
                        + ", AP3 stop reason '" + c.onClient(mc -> String.valueOf(Mod.staticCall(EXEC, "stopReason"))) + "'");
                return;
            }
            c.waitUntil("the node's click reaching the server", mc -> {
                trackCamera(mc, camera);
                return c.events("term.interact").size() > before;
            }, 40);
            List<JsonObject> ev = c.events("term.interact");
            c.check(ev.size() - before == 1, "expected exactly one interact from the node, the server saw " + (ev.size() - before));
            JsonObject last = ev.get(ev.size() - 1);
            c.note(mode + ": clicked, server " + last);
            c.check(last.get("opened").getAsBoolean(), "the node clicked but the terminal did not open: " + last);
            c.waitUntil("the terminal screen", mc -> {
                trackCamera(mc, camera);
                return dev.testkit.compat.McCompat.screen(mc) != null;
            }, 40);
            // The chain carried on: the TERMINAL node is the one being performed now, with the terminal open.
            c.waitUntil("AP3's TERMINAL node to take over", mc -> {
                trackCamera(mc, camera);
                return "TERMINAL".equals(activeType());
            }, 20);
            boolean running = c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "isRunning"));
            String reason = c.onClient(mc -> String.valueOf(Mod.staticCall(EXEC, "stopReason")));
            c.note(mode + ": with the terminal open AP3 is running=" + running + ", active node " + c.onClient(mc -> activeType())
                    + ", stop reason '" + reason + "'");
            c.check(running && "test lane".equals(reason), "AP3 stopped after the Term Aura click: " + reason);

            String self = c.onClient(mc -> mc.player.getGameProfile().name());
            c.hx().chat(self + " completed a terminal! (1/7)");
            c.waitUntil("the TERMINAL node to finish on the completion line", mc -> {
                trackCamera(mc, camera);
                return !(Boolean) Mod.staticCall(EXEC, "isRunning");
            }, 40);
            String after = c.onClient(mc -> String.valueOf(Mod.staticCall(EXEC, "stopReason")));
            c.check("test lane".equals(after), "the chain ended by a stop, not by finishing: " + after);
            c.ctx().waitTicks(5);

            float bodyYaw = c.onClient(mc -> mc.player.getYRot());
            float bodyPitch = c.onClient(mc -> mc.player.getXRot());
            c.note(String.format(java.util.Locale.ROOT, "%s: camera moved at most %.3f deg; body now yaw %.2f pitch %.2f (was %.2f, 0)",
                    mode, camera[2], Mth.wrapDegrees(bodyYaw), bodyPitch, yaw));
            if (mode != Mode.BEHIND_NOTURN) {
                c.check(camera[2] < 0.01f, "the camera moved " + camera[2] + " degrees - only the body may turn");
                c.check(Math.abs(Mth.wrapDegrees(bodyYaw - yaw)) < 0.01f && Math.abs(bodyPitch) < 0.01f,
                        "the body was not given back to the view: yaw " + bodyYaw + " pitch " + bodyPitch);
            }
        } finally {
            TerminalAuraCases.closeScreen(c);
            c.onClient(mc -> {
                try {
                    Mod.staticCall(EXEC, "stop", "test end");
                    setChain();
                    Mod.staticCall(FEATURE, "setForceDungeon", false);
                } catch (Throwable t) {
                    System.out.println("[ap3-termaura] AP3 reset failed: " + t);
                }
                for (int i = undo.size() - 1; i >= 0; i--) {
                    try {
                        undo.get(i).close();
                    } catch (Exception e) {
                        System.out.println("[ap3-termaura] restore failed: " + e);
                    }
                }
                return null;
            });
            c.hx().call("boss.term.clear");
            c.hx().sidebarClear();
            c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
        }
    }

    private static void trackCamera(net.minecraft.client.Minecraft mc, float[] camera) {
        if (mc.player == null || Float.isNaN(camera[0])) {
            return;
        }
        float dy = Math.abs(Mth.wrapDegrees(mc.player.getViewYRot(1f) - camera[0]));
        float dp = Math.abs(mc.player.getViewXRot(1f) - camera[1]);
        camera[2] = Math.max(camera[2], Math.max(dy, dp));
    }

    private static String activeType() {
        Object n = Mod.staticCall(EXEC, "activeNode");
        return n == null ? "none" : String.valueOf(Mod.field(n, "type"));
    }

    static Object node(String type, double x, double y, double z) {
        try {
            Class<?> cls = Mod.cls(NODE);
            Object n = cls.getConstructor(Mod.cls(TYPE), double.class, double.class, double.class, float.class, float.class)
                    .newInstance(Mod.enumValue(TYPE, type), x, y, z, 0f, 0f);
            cls.getField("length").set(n, 2.0);
            cls.getField("width").set(n, 2.0);
            return n;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot make an AP3 " + type + " node", e);
        }
    }

    /** Replace the boss chain (the one AP3 runs under Force Dungeon) with {@code nodes}; never saved. */
    @SuppressWarnings("unchecked")
    static void setChain(Object... nodes) {
        Object store = Mod.staticCall("ap3.Ap3Store", "getInstance");
        Object chain;
        try {
            chain = store.getClass().getMethod("forAreaOrCreate", Mod.cls("ap3.Ap3Area"),
                    Mod.cls("dungeonclass.DungeonClass")).invoke(store, Mod.enumValue("ap3.Ap3Area", "BOSS"), null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Ap3Store.forAreaOrCreate", e);
        }
        List<Object> list = (List<Object>) Mod.call(chain, "nodes");
        list.clear();
        list.addAll(List.of(nodes));
    }
}

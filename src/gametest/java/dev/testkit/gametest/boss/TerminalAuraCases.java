package dev.testkit.gametest.boss;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 409: the mod's Terminal Aura ({@code terminalaura/TerminalAuraFeature}, cheat build) against {@code boss.term.*}
 * (hx/boss/HxTerminalStand), which opens "Click in order!" only when the clicking eye is at or above the stand's feet.
 * Replaced the Terminal Open Logger's case on 2026-10-07, when the logger went and the aura took QUOI's rule (jcnlk's
 * fork, 077adbe/44eac98): skip any terminal whose stand feet are above your eyes.
 *
 * <p>Judged, with the F7 sidebar, Goldor's P3 line and the arena in the boss's x/z, every click proven by the server's
 * own {@code term.interact}:
 * <ol>
 *   <li>level with the stand, facing it: the aura clicks by itself and the terminal opens (so it is really running);</li>
 *   <li>below it (eye 1.38 under the stand's feet, still 2.2 blocks from its box): no click in 40 ticks;</li>
 *   <li>level, facing AWAY, Aura FOV 90: no click.</li>
 * </ol>
 * 414 (cheat jar only): level, facing away, Aura FOV Any (the default, and QUOI's), Turn To Terminal on (the default):
 * it clicks, the terminal opens, GrimAC stays clean, and afterwards the player's rotation is back where it was - the
 * body was turned for the click, never the camera.
 * 415 (cheat jar only, EXPECTING flags): the same with Turn To Terminal off - the click goes out with a look ray that
 * misses the stand, and GrimAC flags {@code Hitboxes type=armor_stand} (seen 2026-10-07, before the turn existed).
 * The aura is switched off while the player is moved, so a click can only come from the spot being judged.
 */
final class TerminalAuraCases {

    static final String CONFIG = "terminalaura.TerminalAuraConfig";
    static final String TRACKER = "fastleap.Floor7Tracker";
    static final String GOLDOR = "[BOSS] Goldor: Who dares trespass into my domain?";

    /** Pillar at block (X, Z); ground top at G; stand feet at G + 3 (HxTerminalStand). */
    static final int X = 40;
    static final int Z = 40;
    static final int G = 151;

    private TerminalAuraCases() {
    }

    static void register(Session s) {
        s.test("409-boss-termaura", c -> run(c, 0));
        if (Mod.isCheat()) {
            s.test("414-boss-termaura-behind", c -> run(c, 1));
            s.testExpectingFlags("415-boss-termaura-behind-noturn", c -> run(c, 2));
        }
    }

    /** mode 0: 409; 1: 414, behind with the turn; 2: 415, behind without it. */
    static void run(Session c, int mode) throws Exception {
        if (!Mod.isCheat()) {
            c.note("legit jar: Terminal Aura is compiled out; checking only that it never clicks");
        }
        List<AutoCloseable> undo = new ArrayList<>();
        try {
            c.hx().call("boss.term.build", "x", X, "z", Z, "groundY", G);
            tp(c, X - 1.5, G + 3, Z + 0.5, -90f);
            JsonObject st = c.hx().call("boss.term.spawn").getAsJsonObject();
            int standId = st.get("standId").getAsInt();
            c.hx().call("boss.term.config", "line", true);
            c.waitUntil("the client to have the terminal stand", mc -> mc.level.getEntity(standId) != null, 200);

            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
            c.hx().chat(GOLDOR);
            c.waitUntil("Floor7Tracker phase P3", mc -> "P3".equals(String.valueOf(Mod.staticCall(TRACKER, "getPhase"))), 100);

            if (!Mod.isCheat()) {
                int before = c.events("term.interact").size();
                c.ctx().waitTicks(40);
                c.check(c.events("term.interact").size() == before, "a legit jar clicked a terminal by itself");
                return;
            }
            undo.add(c.onClient(mc -> Mod.with(CONFIG, "Range", 3.0)));
            undo.add(c.onClient(mc -> Mod.with(CONFIG, "DelayMs", 750)));
            undo.add(c.onClient(mc -> Mod.with(CONFIG, "GroundOnly", false)));
            undo.add(c.onClient(mc -> Mod.with(CONFIG, "LeapDelayEnabled", false)));
            undo.add(c.onClient(mc -> Mod.with(CONFIG, "PauseOnMovementKeys", false)));
            undo.add(c.onClient(mc -> Mod.with(CONFIG, "FovDegrees", 360)));
            undo.add(c.onClient(mc -> Mod.with(CONFIG, "Enabled", false)));

            if (mode != 0) {
                boolean turn = mode == 1;
                undo.add(c.onClient(mc -> Mod.with(CONFIG, "TurnToTerminal", turn)));
                expect(c, turn ? "away-any-turn" : "away-any-noturn", X - 1.5, G + 3, Z + 0.5, 90f, true);
                if (turn) {
                    closeScreen(c);
                    c.ctx().waitTicks(5);
                    float yaw = c.onClient(mc -> net.minecraft.util.Mth.wrapDegrees(mc.player.getYRot()));
                    float pitch = c.onClient(mc -> mc.player.getXRot());
                    c.note("rotation after the click: yaw " + yaw + ", pitch " + pitch + " (was 90, 0)");
                    c.check(Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - 90f)) < 0.5f && Math.abs(pitch) < 0.5f,
                            "the body was not given back to the view: yaw " + yaw + " pitch " + pitch);
                }
                return;
            }

            // ---- 1. level, facing it: clicks and opens ----
            expect(c, "level", X - 1.5, G + 3, Z + 0.5, -90f, true);

            // ---- 2. below: eye under the stand's feet, never clicked ----
            expect(c, "below", X + 2.5, G, Z + 0.5, 90f, false);

            // ---- 3. FOV 90: facing away is skipped ----
            c.onClient(mc -> {
                Mod.set(CONFIG, "setFovDegrees", 90);
                return null;
            });
            expect(c, "away-fov90", X - 1.5, G + 3, Z + 0.5, 90f, false);
        } finally {
            closeScreen(c);
            c.onClient(mc -> {
                for (int i = undo.size() - 1; i >= 0; i--) {
                    try {
                        undo.get(i).close();
                    } catch (Exception e) {
                        System.out.println("[termaura] restore failed: " + e);
                    }
                }
                return null;
            });
            c.hx().call("boss.term.clear");
            c.hx().sidebarClear();
            c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
        }
    }

    /** Aura off, move to the spot, aura on, and judge 40 ticks of it. */
    static void expect(Session c, String name, double x, double y, double z, float yaw, boolean click) throws Exception {
        c.onClient(mc -> {
            Mod.set(CONFIG, "setEnabled", false);
            return null;
        });
        closeScreen(c);
        tp(c, x, y, z, yaw);
        c.ctx().waitTicks(20); // past the 750 ms delay from the previous click
        int before = c.events("term.interact").size();
        c.onClient(mc -> {
            Mod.set(CONFIG, "setEnabled", true);
            return null;
        });
        if (click) {
            c.waitUntil(name + ": the aura's click reaching the server", mc -> c.events("term.interact").size() > before, 40);
            List<JsonObject> ev = c.events("term.interact");
            JsonObject last = ev.get(ev.size() - 1);
            c.note(name + ": clicked, server " + last);
            c.check(last.get("opened").getAsBoolean(), name + ": the aura clicked but the terminal did not open: " + last);
            c.waitUntil(name + ": the terminal screen", mc -> dev.testkit.compat.McCompat.screen(mc) != null, 40);
        } else {
            c.ctx().waitTicks(40);
            int after = c.events("term.interact").size();
            c.note(name + ": interacts " + before + " -> " + after);
            c.check(after == before, name + ": the aura clicked a terminal it should have skipped: "
                    + c.events("term.interact").get(after - 1));
        }
        c.onClient(mc -> {
            Mod.set(CONFIG, "setEnabled", false);
            return null;
        });
    }

    static void tp(Session c, double x, double y, double z, float yaw) {
        c.hx().call("dungeon.tp", "x", x, "y", y, "z", z, "yaw", yaw, "pitch", 0f);
        c.waitUntil("the client at " + x + "," + y + "," + z, mc -> mc.player != null
                && mc.player.position().distanceTo(new Vec3(x, y, z)) < 0.05, 200);
        c.ctx().waitTicks(3);
    }

    static void closeScreen(Session c) {
        c.onClient(mc -> {
            if (mc.player != null && dev.testkit.compat.McCompat.screen(mc) != null) {
                mc.player.closeContainer();
            }
            return null;
        });
    }
}

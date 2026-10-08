package dev.testkit.gametest.boss;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * killer560, 2026-10-08: "All terminal solvers and auto terminal is broken, probably because they changed all boss
 * dialogue." Phase detection, which Terminal Aura, AP3 and Fast Leap's P3 parts wait on (mod branch dungeon-fixes):
 * <ul>
 *   <li>556: F7 sidebar, Maxor's and Storm's lines heard in the P2 band (chat phase P2, and it stays P2 there), then on
 *       the P3 terminal pillar with NO Goldor line: the position fallback must start P3 within about a second - the old
 *       jar stays on P2 for good, its fallback needed "no boss line at all". On the cheat jar Terminal Aura then opens the
 *       terminal by itself and the Terminal Solver reads the "Click in order!" board it opened (detected, highlights =
 *       the server's order); on the legit jar the solver is checked on the same board opened by hand.</li>
 *   <li>557: M7, P4: "[BOSS] The Wither King: You... again?" starts P5 (quoi 8562102f / Odin 1cf58050 moved to it once
 *       Necron's line stopped being a reliable P5 start); a player typing "[BOSS] The Wither King: x" in party chat does
 *       not.</li>
 * </ul>
 */
final class DungeonFixBossCases {

    static final String TRACKER = TerminalAuraCases.TRACKER;
    static final String SOLVER = "terminals.TerminalSolverFeature";
    static final String SOLVER_CFG = "terminals.TerminalSolverConfig";

    private DungeonFixBossCases() {
    }

    static void register(Session s) {
        s.test("556-boss-p3-fallback", DungeonFixBossCases::p3Fallback);
        s.test("557-boss-p5-wither-king", DungeonFixBossCases::p5WitherKing);
    }

    static String phase(Session c) {
        return c.onClient(mc -> String.valueOf(Mod.staticCall(TRACKER, "getPhase")));
    }

    /** A 3x3 stone floor with its top at y, centred on block x/z. */
    static void floor(Session c, int x, int y, int z) {
        JsonArray blocks = new JsonArray();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                blocks.add((x + dx) + " " + (y - 1) + " " + (z + dz) + " minecraft:stone");
            }
        }
        JsonObject a = new JsonObject();
        a.add("blocks", blocks);
        c.hx().call("dungeon.blocks", a);
    }

    static void p3Fallback(Session c) throws Exception {
        int X = TerminalAuraCases.X;
        int Z = TerminalAuraCases.Z;
        int G = TerminalAuraCases.G;
        List<AutoCloseable> undo = new ArrayList<>();
        try {
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
            // P2 band (y 155-210): Maxor's and Storm's lines heard here.
            floor(c, 70, 180, 70);
            TerminalAuraCases.tp(c, 70.5, 180, 70.5, 0f);
            c.hx().chat("[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!");
            c.hx().chat("[BOSS] Storm: Pathetic Maxor, just like expected.");
            c.waitUntil("Floor7Tracker phase P2", mc -> "P2".equals(String.valueOf(Mod.staticCall(TRACKER, "getPhase"))), 100);
            c.ctx().waitTicks(40);
            String inP2 = phase(c);
            c.note("after Maxor + Storm and 40 ticks in the P2 band: phase " + inP2);
            c.check("P2".equals(inP2), "standing in the P2 band changed the phase to " + inP2);

            // The P3 pillar, no Goldor line.
            c.hx().call("boss.term.build", "x", X, "z", Z, "groundY", G);
            TerminalAuraCases.tp(c, X - 1.5, G + 3, Z + 0.5, -90f);
            long arrived = c.onClient(mc -> mc.level.getGameTime());
            c.waitUntil("Floor7Tracker phase P3 without Goldor's line", mc -> "P3".equals(String.valueOf(
                    Mod.staticCall(TRACKER, "getPhase"))), 100);
            long took = c.onClient(mc -> mc.level.getGameTime()) - arrived;
            String stage = c.onClient(mc -> String.valueOf(Mod.staticCall(TRACKER, "getStage")));
            c.note("on the P3 pillar with no Goldor line: phase P3 after " + took + " tick(s), stage " + stage);
            c.check(took <= 40, "P3 took " + took + " ticks");

            JsonObject st = c.hx().call("boss.term.spawn").getAsJsonObject();
            int standId = st.get("standId").getAsInt();
            c.waitUntil("the client to have the terminal stand", mc -> mc.level.getEntity(standId) != null, 200);
            undo.add(c.onClient(mc -> Mod.with(SOLVER_CFG, "Enabled", true)));
            undo.add(c.onClient(mc -> Mod.with(SOLVER_CFG, "AutoTerminalsEnabled", false)));
            if (Mod.isCheat()) {
                String cfg = TerminalAuraCases.CONFIG;
                undo.add(c.onClient(mc -> Mod.with(cfg, "Range", 3.0)));
                undo.add(c.onClient(mc -> Mod.with(cfg, "DelayMs", 750)));
                undo.add(c.onClient(mc -> Mod.with(cfg, "GroundOnly", false)));
                undo.add(c.onClient(mc -> Mod.with(cfg, "LeapDelayEnabled", false)));
                undo.add(c.onClient(mc -> Mod.with(cfg, "PauseOnMovementKeys", false)));
                undo.add(c.onClient(mc -> Mod.with(cfg, "FovDegrees", 360)));
                undo.add(c.onClient(mc -> Mod.with(cfg, "Enabled", true)));
                int before = c.events("term.interact").size();
                c.waitUntil("Terminal Aura's click reaching the server (phase " + phase(c) + ")",
                        mc -> c.events("term.interact").size() > before, 100);
                List<JsonObject> ev = c.events("term.interact");
                c.note("Terminal Aura clicked: " + ev.get(ev.size() - 1));
                c.check(ev.get(ev.size() - 1).get("opened").getAsBoolean(), "the aura's click did not open the terminal");
                c.onClient(mc -> {
                    Mod.set(cfg, "setEnabled", false);
                    return null;
                });
            } else {
                // Legit: open it by hand, a real interact on the stand.
                c.onClient(mc -> {
                    var stand = mc.level.getEntity(standId);
                    var eye = mc.player.getEyePosition();
                    var hit = stand.getBoundingBox().inflate(0.1).clip(eye, eye.add(mc.player.getViewVector(1f).scale(5)))
                            .orElse(stand.getBoundingBox().getCenter());
                    mc.gameMode.interact(mc.player, stand, new net.minecraft.world.phys.EntityHitResult(stand, hit),
                            net.minecraft.world.InteractionHand.MAIN_HAND);
                    return null;
                });
            }
            c.waitUntil("the terminal screen", mc -> dev.testkit.compat.McCompat.screen(mc) != null, 60);
            c.waitUntil("the solver to track NUMBERS", mc -> "NUMBERS".equals(String.valueOf(Mod.field(SOLVER, "currentType"))), 60);
            c.waitUntil("the solver to highlight the board", mc -> !((Map<?, ?>) Mod.field(SOLVER, "currentHighlights")).isEmpty(), 60);
            JsonObject board = c.hx().call("menu.terminal.state").getAsJsonObject();
            List<Integer> order = new ArrayList<>();
            board.getAsJsonArray("remaining").forEach(e -> order.add(e.getAsInt()));
            List<Integer> shown = c.onClient(mc -> {
                List<Integer> out = new ArrayList<>();
                ((Map<?, ?>) Mod.field(SOLVER, "currentHighlights")).keySet().forEach(k -> out.add((Integer) k));
                return out;
            });
            c.note("solver on the opened '" + board.get("title").getAsString() + "': highlights " + shown + ", server order "
                    + order);
            c.check(shown.size() >= 2 && order.size() >= 2 && shown.get(0).equals(order.get(0)) && shown.get(1).equals(order.get(1)),
                    "the solver's first two highlights " + shown + " are not the server's order " + order);
        } finally {
            TerminalAuraCases.closeScreen(c);
            c.onClient(mc -> {
                for (int i = undo.size() - 1; i >= 0; i--) {
                    try {
                        undo.get(i).close();
                    } catch (Exception e) {
                        System.out.println("[dungeon-fixes] restore failed: " + e);
                    }
                }
                return null;
            });
            c.hx().call("boss.term.clear");
            c.hx().sidebarClear();
            c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
        }
    }

    static void p5WitherKing(Session c) throws Exception {
        try {
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(M7)");
            c.waitUntil("DungeonState M7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
            // P4 band (y 45-100), inside the boss x/z.
            floor(c, 70, 80, 70);
            TerminalAuraCases.tp(c, 70.5, 80, 70.5, 0f);
            c.hx().chat("[BOSS] Necron: Finally, I heard so much about you. The Eye likes you very much.");
            c.waitUntil("Floor7Tracker phase P4", mc -> "P4".equals(String.valueOf(Mod.staticCall(TRACKER, "getPhase"))), 100);
            // A player's line in party chat must not move the phase.
            c.hx().chat("Party > [MVP+] Bob: [BOSS] The Wither King: You... again?");
            c.ctx().waitTicks(10);
            String afterForged = phase(c);
            c.note("after a party line quoting the Wither King: phase " + afterForged);
            c.check("P4".equals(afterForged), "a party chat line moved the phase to " + afterForged);
            c.hx().chat("[BOSS] The Wither King: You... again?");
            c.waitUntil("Floor7Tracker phase P5 on the Wither King's line", mc -> "P5".equals(String.valueOf(
                    Mod.staticCall(TRACKER, "getPhase"))), 100);
            c.note("[BOSS] The Wither King: You... again? -> phase P5");
        } finally {
            c.hx().sidebarClear();
            c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
        }
    }
}

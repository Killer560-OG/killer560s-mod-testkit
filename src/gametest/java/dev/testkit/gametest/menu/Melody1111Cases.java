package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Latency;

import java.util.ArrayList;
import java.util.List;

/**
 * melody-1111 (killer560, 2026-11-11: "for auto terms in melody it seems to be missing a lot of clicks"). His config:
 * Auto Melody, Skip Mode EDGES, Lookahead 2, Stream Safe off; ping 31-88 ms. His log: every live click hit; all 8 misses
 * were lookahead clicks sent before the next row's target could be seen (Hypixel picks a new random target per row), and
 * twice the stale lookahead bit then held back a live click on a row whose pane arrived ON its target, so that row waited
 * a whole bounce (about 3 s). Boards here use his real row targets (sim columns 1-5) under 50 ms of added inbound
 * latency, and the server counts every pass where the pane left the current row's target unclicked (missedPasses).
 */
final class Melody1111Cases {

    private static final String FEATURE = TerminalCases.FEATURE;
    private static final String CONFIG = TerminalCases.CONFIG;
    /** One step of the sim pane, server ticks (Hypixel's step is ~500 ms). */
    private static final int INTERVAL = 10;

    private Melody1111Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1111-menu-term-melody-gamble", Melody1111Cases::gamble);
    }

    /** {row targets, whether row 3 starts with the pane already on its target (the case the stale bit broke)}. */
    private static final int[][] BOARDS = {
            {5, 1, 2},   // his 21:51:19 board
            {5, 4, 3},   // his 21:44:51 board
            {5, 3, 3},   // row 3's pane arrives on its target
            {1, 4, 4},   // the same from the first slot
            {5, 5, 5},   // the gamble wins: both lookahead clicks must land
    };

    static void gamble(Session c) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Auto Melody is cheat-only");
            return;
        }
        boolean fixed = hasField("melodyLookaheadStale");
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", true).set(CONFIG, "AutoMelodyEnabled", true)
                    .set(CONFIG, "HoverTerminalsEnabled", false).set(CONFIG, "HoldToClickEnabled", false)
                    .set(CONFIG, "AutoStreamSafe", false)
                    .set(CONFIG, "MelodySkipMode", Mod.enumValue(CONFIG + "$MelodySkipMode", "EDGES"))
                    .set(CONFIG, "MelodyLookaheadClicks", 2);
            c.onClient(mc -> {
                Latency.install(50);
                return null;
            });
            // Let the ping tracker see the added latency before the first board.
            c.ctx().waitTicks(80);
            int totalMissed = 0;
            int totalWrong = 0;
            long totalTicks = 0;
            List<String> rows = new ArrayList<>();
            for (int b = 0; b < BOARDS.length; b++) {
                int[] targets = BOARDS[b];
                int firedBefore = c.onClient(mc -> (Integer) Mod.field(FEATURE, "melodyLookaheadFired"));
                int staleBefore = fixed ? c.onClient(mc -> (Integer) Mod.field(FEATURE, "melodyLookaheadStale")) : 0;
                int clickHead = c.events("terminal.click").size();
                int solvedHead = c.events("terminal.solved").size();
                com.google.gson.JsonArray ts = new com.google.gson.JsonArray();
                for (int t : targets) {
                    ts.add(t);
                }
                JsonObject args = new JsonObject();
                args.addProperty("type", "MELODY");
                args.addProperty("seed", 1111 + b);
                args.addProperty("interval", INTERVAL);
                args.addProperty("bandRows", 3);
                args.add("targets", ts);
                JsonObject t = c.hx().call("menu.terminal", args).getAsJsonObject();
                MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
                c.waitUntil("Melody " + java.util.Arrays.toString(targets) + " solved",
                        mc -> c.events("terminal.solved").size() > solvedHead, 2400);
                MenuKit.awaitNoScreen(c, 40);
                JsonObject solved = c.events("terminal.solved").get(solvedHead);
                int missed = solved.get("missedPasses").getAsInt();
                int wrong = solved.get("wrong").getAsInt();
                long ticks = solved.get("solvedTick").getAsLong() - solved.get("openedTick").getAsLong();
                int fired = c.onClient(mc -> (Integer) Mod.field(FEATURE, "melodyLookaheadFired")) - firedBefore;
                int stale = fixed ? c.onClient(mc -> (Integer) Mod.field(FEATURE, "melodyLookaheadStale")) - staleBefore : 0;
                List<JsonObject> all = c.events("terminal.click");
                long row2At = -1;
                long row3At = -1;
                for (JsonObject e : all.subList(clickHead, all.size())) {
                    if (!e.get("correct").getAsBoolean()) {
                        continue;
                    }
                    int slot = e.get("slot").getAsInt();
                    if (slot == 25) {
                        row2At = e.get("tick").getAsLong();
                    } else if (slot == 34) {
                        row3At = e.get("tick").getAsLong();
                    }
                }
                totalMissed += missed;
                totalWrong += wrong;
                totalTicks += ticks;
                String row = java.util.Arrays.toString(targets) + ": " + ticks + " ticks, " + missed + " missed pass(es), "
                        + wrong + " wrong (lookahead " + fired + " sent, " + stale + " freed as stale), row 3 "
                        + (row2At < 0 || row3At < 0 ? "?" : (row3At - row2At)) + " ticks after row 2";
                rows.add(row);
                c.note(row);
                if (targets[1] == targets[2]) {
                    // Row 3's pane arrives on its target: its click must go on that pass, not a bounce later.
                    c.check(row3At > 0 && row2At > 0 && row3At - row2At <= INTERVAL,
                            java.util.Arrays.toString(targets) + ": row 3 started on its target but was clicked "
                                    + (row3At - row2At) + " ticks after row 2 (a pass missed)");
                }
                if (targets[0] == targets[1] && targets[1] == targets[2]) {
                    c.check(wrong == 0 && fired == 2, "the winning gamble should land both lookahead clicks: sent " + fired
                            + ", " + wrong + " wrong");
                }
            }
            c.note("TOTAL (" + (fixed ? "fixed" : "old") + " jar): " + totalTicks + " ticks, " + totalMissed
                    + " missed passes, " + totalWrong + " wrong clicks over " + BOARDS.length + " boards");
        } finally {
            c.onClient(mc -> {
                Latency.remove();
                return null;
            });
            MenuKit.reset(c);
        }
    }

    private static boolean hasField(String name) {
        try {
            Mod.field(FEATURE, name);
            return true;
        } catch (AssertionError | RuntimeException e) {
            return false;
        }
    }
}

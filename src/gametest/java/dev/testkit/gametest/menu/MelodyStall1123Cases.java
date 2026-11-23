package dev.testkit.gametest.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Latency;

import java.util.ArrayList;
import java.util.List;

/**
 * runreview-melodystall-1123: the owner's 7 real F7 Melody solves (5.2-7.3 s, 8 "pacing dropped" lines) had Lookahead 2 +
 * EDGES clicks for rows 2 and 3 go out 50-100 ms after the live click, 300-3470 ms before those rows showed. Hypixel shows
 * the next row about 400 ms after the click that finished the row (the sim's "rowDelay"), so those clicks were ignored (a
 * wrong click to the server), and the 2.5 s "no click" stall net then logged a pacing drop while the mod was only waiting
 * for the pane to come round to the new row's target. Boards: edge targets first so the lookahead arms. Pass: no click the
 * server ignored, no stall reset, and every row clicked once, at least the row delay after the one before.
 */
final class MelodyStall1123Cases {

    private static final String FEATURE = TerminalCases.FEATURE;
    private static final String CONFIG = TerminalCases.CONFIG;
    private static final int INTERVAL = 10;
    private static final int ROW_DELAY = 8;
    private static final int[][] BOARDS = {{1, 5, 5}, {5, 1, 3}, {1, 1, 1}, {5, 3, 1}, {1, 4, 5}};

    private MelodyStall1123Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1123-menu-term-melody-rowdelay", MelodyStall1123Cases::rowDelay);
    }

    static void rowDelay(Session c) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Auto Melody is cheat-only");
            return;
        }
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", true).set(CONFIG, "AutoMelodyEnabled", true)
                    .set(CONFIG, "HoverTerminalsEnabled", false).set(CONFIG, "HoldToClickEnabled", false)
                    .set(CONFIG, "AutoStreamSafe", false)
                    .set(CONFIG, "MelodySkipMode", Mod.enumValue(CONFIG + "$MelodySkipMode", "EDGES"))
                    .set(CONFIG, "MelodyLookaheadClicks", 2);
            c.onClient(mc -> {
                Latency.install(40);
                return null;
            });
            c.ctx().waitTicks(100);
            long totalTicks = 0;
            int totalWrong = 0;
            int stalls = 0;
            List<String> rows = new ArrayList<>();
            for (int b = 0; b < BOARDS.length; b++) {
                int[] targets = BOARDS[b];
                int stallBefore = c.onClient(mc -> (Integer) Mod.field(FEATURE, "stallResets"));
                int clickHead = c.events("terminal.click").size();
                int solvedHead = c.events("terminal.solved").size();
                JsonArray ts = new JsonArray();
                for (int t : targets) {
                    ts.add(t);
                }
                JsonObject args = new JsonObject();
                args.addProperty("type", "MELODY");
                args.addProperty("seed", 1123 + b);
                args.addProperty("interval", INTERVAL);
                args.addProperty("bandRows", 3);
                args.addProperty("rowDelay", ROW_DELAY);
                args.add("targets", ts);
                JsonObject t = c.hx().call("menu.terminal", args).getAsJsonObject();
                MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
                c.waitUntil("Melody " + java.util.Arrays.toString(targets) + " solved",
                        mc -> c.events("terminal.solved").size() > solvedHead, 2400);
                MenuKit.awaitNoScreen(c, 40);
                JsonObject solved = c.events("terminal.solved").get(solvedHead);
                int wrong = solved.get("wrong").getAsInt();
                long ticks = solved.get("solvedTick").getAsLong() - solved.get("openedTick").getAsLong();
                int stall = c.onClient(mc -> (Integer) Mod.field(FEATURE, "stallResets")) - stallBefore;
                List<Long> good = new ArrayList<>();
                List<JsonObject> all = c.events("terminal.click");
                for (JsonObject e : all.subList(clickHead, all.size())) {
                    if (e.get("correct").getAsBoolean()) {
                        good.add(e.get("tick").getAsLong());
                    }
                }
                totalTicks += ticks;
                totalWrong += wrong;
                stalls += stall;
                String row = java.util.Arrays.toString(targets) + ": " + ticks + " ticks, " + wrong + " ignored click(s), "
                        + stall + " stall reset(s), correct clicks at " + good;
                rows.add(row);
                c.note(row);
                c.check(good.size() == 3, java.util.Arrays.toString(targets) + ": expected 3 correct clicks, got " + good);
                if (good.size() == 3) {
                    c.check(good.get(1) - good.get(0) >= ROW_DELAY && good.get(2) - good.get(1) >= ROW_DELAY,
                            "the row delay was not in force (clicks " + good + "): the sim did not act");
                }
                c.check(wrong == 0, java.util.Arrays.toString(targets) + ": " + wrong + " click(s) went out before their row"
                        + " was live and the server ignored them");
            }
            c.note("TOTAL: " + totalTicks + " ticks over " + BOARDS.length + " boards, " + totalWrong + " ignored clicks, "
                    + stalls + " stall resets");
            c.check(stalls == 0, stalls + " 'no click for 2.5 s, pacing dropped' resets while only waiting for the pane");
        } finally {
            c.onClient(mc -> {
                Latency.remove();
                return null;
            });
            MenuKit.reset(c);
        }
    }
}

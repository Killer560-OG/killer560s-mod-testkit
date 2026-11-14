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
        MenuSuite.test(s, "1111-same-melody-click", c -> sameTick(c, true));
        MenuSuite.test(s, "1111-same-melody-click-unblocked", c -> sameTick(c, false));
    }

    /**
     * SAME-TICK GRIM CASES for the freed live click: HIS mouse click on the menu's last slot every client tick (a real
     * press through the screen) while Auto Melody plays boards whose stale lookahead rows are freed, under 50 ms latency.
     * Block Input ON (default): his clicks are held, the auto clicks all land right. OFF: they share server ticks with the
     * auto clicks, which must still all be right. GrimAC (default and experimental) must stay silent.
     */
    static void sameTick(Session c, boolean block) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Auto Melody is cheat-only");
            return;
        }
        dev.testkit.gametest.SameTick st = null;
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", true).set(CONFIG, "AutoMelodyEnabled", true)
                    .set(CONFIG, "HoverTerminalsEnabled", false).set(CONFIG, "HoldToClickEnabled", false)
                    .set(CONFIG, "AutoStreamSafe", false).set(CONFIG, "BlockInputWhileAutoClicking", block)
                    .set(CONFIG, "MelodySkipMode", Mod.enumValue(CONFIG + "$MelodySkipMode", "EDGES"))
                    .set(CONFIG, "MelodyLookaheadClicks", 2);
            c.onClient(mc -> {
                Latency.install(50);
                return null;
            });
            c.ctx().waitTicks(80);
            int[][] boards = {{5, 3, 3}, {5, 5, 5}, {1, 4, 4}};
            int autoWrong = 0;
            int his = 0;
            int autoRight = 0;
            for (int b = 0; b < boards.length; b++) {
                int clickHead = c.events("terminal.click").size();
                int solvedHead = c.events("terminal.solved").size();
                com.google.gson.JsonArray ts = new com.google.gson.JsonArray();
                for (int t : boards[b]) {
                    ts.add(t);
                }
                JsonObject args = new JsonObject();
                args.addProperty("type", "MELODY");
                args.addProperty("seed", 1121 + b);
                args.addProperty("interval", INTERVAL);
                args.addProperty("bandRows", 3);
                args.add("targets", ts);
                JsonObject t = c.hx().call("menu.terminal", args).getAsJsonObject();
                MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
                c.ctx().waitTicks(1);
                if (st == null) {
                    st = c.onClient(mc -> dev.testkit.gametest.SameTick.start(dev.testkit.gametest.SameTick.Act.SCREEN_CLICK, 1,
                            p -> p instanceof net.minecraft.network.protocol.game.ServerboundContainerClickPacket, 4, 5));
                }
                int last = c.onClient(mc -> ((net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)
                        dev.testkit.compat.McCompat.screen(mc)).getMenu().slots.size() - 1);
                c.waitUntil("Melody " + java.util.Arrays.toString(boards[b]) + " solved",
                        mc -> c.events("terminal.solved").size() > solvedHead, 2400);
                MenuKit.awaitNoScreen(c, 40);
                List<JsonObject> all = c.events("terminal.click");
                for (JsonObject e : all.subList(clickHead, all.size())) {
                    int slot = e.get("slot").getAsInt();
                    if (slot == last) {
                        his++;
                    } else if (slot == 16 || slot == 25 || slot == 34) {
                        if (e.get("correct").getAsBoolean()) {
                            autoRight++;
                        }
                    } else {
                        autoWrong++;
                    }
                }
            }
            c.note("block input " + block + ": auto clicks right " + autoRight + ", auto clicks off the row buttons "
                    + autoWrong + ", his clicks the server saw " + his);
            c.check(autoRight == 9, "expected 9 right auto clicks over 3 boards, got " + autoRight);
            c.check(autoWrong == 0, autoWrong + " auto click(s) off the row buttons");
            dev.testkit.gametest.SameTick.judge(c, st, "auto melody (block input " + block + ")",
                    block ? dev.testkit.gametest.SameTick.Expect.HELD : dev.testkit.gametest.SameTick.Expect.SHARE);
            st = null;
        } finally {
            if (st != null) {
                dev.testkit.gametest.SameTick s2 = st;
                c.onClient(mc -> {
                    s2.close();
                    return null;
                });
            }
            c.onClient(mc -> {
                Latency.remove();
                return null;
            });
            MenuKit.reset(c);
        }
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
            int totalMissed = 0;
            int totalWrong = 0;
            long totalTicks = 0;
            int staleSlow = 0;
            List<String> rows = new ArrayList<>();
            // At 50 ms the second lookahead usually drops (the row shows first); at 120 ms it always goes out.
            for (int lat : new int[] {50, 120}) {
            c.onClient(mc -> {
                Latency.install(lat);
                return null;
            });
            // Let the ping tracker see the added latency before the first board.
            c.ctx().waitTicks(100);
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
                if (lat == 120) {
                    staleSlow += stale;
                }
                String row = lat + " ms " + java.util.Arrays.toString(targets) + ": " + ticks + " ticks, " + missed + " missed pass(es), "
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
            }
            c.check(!fixed || staleSlow > 0, "at 120 ms no lookahead click was freed as stale: the fix never acted");
            c.note("TOTAL (" + (fixed ? "fixed" : "old") + " jar): " + totalTicks + " ticks, " + totalMissed
                    + " missed passes, " + totalWrong + " wrong clicks over " + 2 * BOARDS.length + " boards, " + staleSlow + " stale lookaheads freed at 120 ms");
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

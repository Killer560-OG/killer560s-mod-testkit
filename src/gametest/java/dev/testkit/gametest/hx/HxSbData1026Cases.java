package dev.testkit.gametest.hx;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.mod.Mod;

import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * The shared sidebar and tab-list reading with Custom Scoreboard OFF (its default), mod open-key-1026's cherry-pick of
 * bec7329a: {@code CustomScoreboardFeature} used to return before {@code ScoreboardData.refresh} when the board was off,
 * so {@code ScoreboardData.island()}, {@code sidebar()} and {@code tabPlain()} stayed empty for everyone who never
 * turned the board on - and Auto GFS's "Dungeons/Kuudra Only" gate ({@code ScoreboardData.inIsland}) never let it run.
 * <ul>
 *   <li>1026-hx-sbdata-board-off: with the board off, the hub's sidebar and tab fill the readings ("Hub"), and a
 *       dungeon tab's "Dungeon: Catacombs" moves the island to Catacombs.</li>
 *   <li>1026-hx-sbdata-autogfs-gate (cheat jar): Auto GFS on with Dungeons/Kuudra Only, board off: in the hub the gate
 *       says "not in a dungeon or Kuudra" (the control), in the dungeon it opens ("running"). Every refill item is off, so
 *       nothing is sent.</li>
 * </ul>
 */
final class HxSbData1026Cases {

    private static final String BOARD = "scoreboard.CustomScoreboardConfig";
    private static final String DATA = "scoreboard.ScoreboardData";
    private static final String GFS = "cheatutils.CheatUtilsConfig";

    private HxSbData1026Cases() {
    }

    static void register(Session s) {
        s.test("1026-hx-sbdata-board-off", HxSbData1026Cases::boardOff);
        s.test("1026-hx-sbdata-autogfs-gate", HxSbData1026Cases::autoGfsGate);
    }

    private static void dungeonTab(Session c) {
        dungeon(c);
        // Hypixel's dungeon tab names the island on a "Dungeon: Catacombs" line (ScoreboardPattern.TAB_AREA).
        c.hx().call("tab.set", Hx.args("entries", List.of("§r§b§lDungeon: §r§7Catacombs", "§r§b§lParty (3)"),
                "footer", ""));
    }

    private static void boardOff(Session c) {
        try (Settings set = new Settings(c).with(BOARD, "Enabled", false)) {
            hub(c);
            c.waitUntil("ScoreboardData.island() reads Hub with the board off",
                    mc -> "Hub".equals(Mod.staticCall(DATA, "island")), 100);
            String hub = c.onClient(mc -> "island " + Mod.staticCall(DATA, "island") + ", sidebar "
                    + ((List<?>) Mod.staticCall(DATA, "sidebar")).size() + " line(s), tab "
                    + ((List<?>) Mod.staticCall(DATA, "tabPlain")).size() + " entr(ies)");
            int sidebar = c.onClient(mc -> ((List<?>) Mod.staticCall(DATA, "sidebar")).size());
            int tab = c.onClient(mc -> ((List<?>) Mod.staticCall(DATA, "tabPlain")).size());
            boolean boardOn = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(BOARD), "isEnabled"));
            c.note("hub, board on " + boardOn + ": " + hub);
            c.check(!boardOn, "Custom Scoreboard is on, so the case does not test the board-off path");
            c.check(sidebar > 0, "the sidebar reading is empty with the board off");
            c.check(tab > 0, "the tab reading is empty with the board off");
            dungeonTab(c);
            c.waitUntil("ScoreboardData.island() reads Catacombs in the dungeon",
                    mc -> "Catacombs".equals(Mod.staticCall(DATA, "island")), 100);
            boolean in = c.onClient(mc -> (Boolean) Mod.staticCall(DATA, "inIsland", (Object) new String[]{"Catacombs", "Kuudra"}));
            c.note("dungeon: island " + c.onClient(mc -> Mod.staticCall(DATA, "island")) + ", inIsland(Catacombs, Kuudra) " + in);
            c.check(in, "inIsland(Catacombs, Kuudra) is false in the dungeon");
        } finally {
            hub(c);
        }
    }

    private static void autoGfsGate(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto GFS is cheat-only");
            return;
        }
        try (Settings set = new Settings(c).with(BOARD, "Enabled", false)
                .with(GFS, "AutoGfsEnabled", true).with(GFS, "GfsDungeonKuudraOnly", true)
                .with(GFS, "GfsPearls", false).with(GFS, "GfsLeaps", false).with(GFS, "GfsSuperbooms", false)
                .with(GFS, "GfsJerries", false).with(GFS, "GfsSkipIfNone", false).with(GFS, "GfsIntervalSec", 5)) {
            run(c, () -> {
                try {
                    Mod.setField("util.SackCommands", "dead", false);
                } catch (AssertionError | RuntimeException e) {
                    System.out.println("[1026-hx-sbdata-autogfs-gate] no util.SackCommands.dead: " + e.getMessage());
                }
            });
            long mark = LogTap.mark();
            hub(c);
            c.waitUntil("Auto GFS's gate says 'not in a dungeon or Kuudra' in the hub",
                    mc -> !logLines(mark, "[AutoGfs] gate:", "-> not in a dungeon or Kuudra").isEmpty(), 100);
            long mark2 = LogTap.mark();
            dungeonTab(c);
            c.waitUntil("Auto GFS's gate opens in the dungeon",
                    mc -> !logLines(mark2, "[AutoGfs] gate:", "-> running").isEmpty(), 100);
            List<String> lines = logLines(mark, "[AutoGfs] gate:");
            c.note("gate lines: " + lines);
            List<String> gfs = commandsStarting(c, "gfs");
            c.check(gfs.isEmpty(), "Auto GFS sent " + gfs + " with every refill item off");
        } finally {
            hub(c);
        }
    }
}

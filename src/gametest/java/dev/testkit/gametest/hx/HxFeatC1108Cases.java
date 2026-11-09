package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.util.List;
import java.util.Map;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod branch featC-1108 (killer560, 2026-11-08: every dungeon feature other mods have that we lack): Puzzle HUD, Reparty and
 * Watcher Helper. Each case feeds the real signals the features read (tab list puzzle rows, chat lines, a held movement key)
 * and asserts the drawn rows, the title, the chat line and the exact commands on the wire, so a feature that never acted fails.
 */
final class HxFeatC1108Cases {

    private static final String PUZZLE = "puzzlehud.PuzzleHudFeature";
    private static final String PUZZLE_CFG = "puzzlehud.PuzzleHudConfig";
    private static final String RP = "reparty.RepartyFeature";
    private static final String RP_CFG = "reparty.RepartyConfig";
    private static final String WH = "splittimers.WatcherHelper";
    private static final String WH_CFG = "splittimers.WatcherHelperConfig";
    private static final String TIDY = "chattidy.ChatTidy";
    private static final String DONE = "\u2714";
    private static final String FAIL = "\u2716";
    private static final String OPEN = "\u2726";

    private HxFeatC1108Cases() {
    }

    static void register(Session s) {
        s.test("1108-puzzlehud-rows", HxFeatC1108Cases::puzzleRows);
        s.test("1108-reparty-run", HxFeatC1108Cases::repartyRun);
        s.test("1108-reparty-notleader", HxFeatC1108Cases::repartyNotLeader);
        s.test("1108-reparty-auto", HxFeatC1108Cases::repartyAuto);
        s.test("1108-reparty-moving", HxFeatC1108Cases::repartyMoving);
        s.test("1108-watcher-helper", HxFeatC1108Cases::watcherHelper);
    }

    // ---- Puzzle HUD ---------------------------------------------------------------------------------------------------

    private static List<Map<String, String>> puzzles(String water, String boulder) {
        return List.of(Map.of("name", "Water Board", "state", "\u00a7r" + water), Map.of("name", "Boulder", "state", "\u00a7r" + boulder));
    }

    @SuppressWarnings("unchecked")
    private static List<String> rows(Session c) {
        return (List<String>) c.onClient(mc -> Mod.staticCall(PUZZLE, "rowsForTest"));
    }

    private static String rowOf(Session c, String name) {
        return rows(c).stream().filter(r -> r.contains(name)).findFirst().orElse("(no row for " + name + ": " + rows(c) + ")");
    }

    private static String header(Session c) {
        return (String) c.onClient(mc -> Mod.staticCall(PUZZLE, "headerForTest"));
    }

    private static void puzzleRows(Session c) {
        // Off: the tab list names two puzzles and nothing is drawn.
        dungeon(c, "puzzles", puzzles("\u00a7e" + OPEN, "\u00a7e" + OPEN));
        c.ctx().waitTicks(30);
        c.check(rows(c).isEmpty(), "Puzzle HUD OFF still built rows: " + rows(c));
        try (Settings set = new Settings(c).with(PUZZLE_CFG, "Enabled", true)) {
            dungeon(c, "puzzles", puzzles("\u00a7e" + OPEN, "\u00a7e" + OPEN));
            c.waitUntil("two open puzzle rows", mc -> ((List<?>) Mod.staticCall(PUZZLE, "rowsForTest")).size() == 2, 80);
            c.check(header(c).equals("Puzzles 0/2"), "header " + header(c));
            c.check(rowOf(c, "Water Board").contains(OPEN), "open row " + rows(c));
            // Water Board is solved by HxMateA, Boulder failed by HxMateB: state from the tab list, who from chat.
            dungeon(c, "puzzles", puzzles("\u00a7a" + DONE, "\u00a7c" + FAIL));
            c.hx().chat("PUZZLE SOLVED! HxMateA tied Water Board! Good job!");
            c.hx().chat("PUZZLE FAIL! HxMateB was fooled by Boulder! Yikes!");
            c.waitUntil("done and failed rows with who", mc -> {
                List<?> r = (List<?>) Mod.staticCall(PUZZLE, "rowsForTest");
                return r.size() == 2 && r.toString().contains("HxMateA") && r.toString().contains("HxMateB");
            }, 120);
            c.check(header(c).equals("Puzzles 1/2"), "header after solve " + header(c));
            String water = rowOf(c, "Water Board");
            String boulder = rowOf(c, "Boulder");
            c.check(water.contains(DONE) && water.contains("HxMateA") && water.matches(".*\\d:\\d\\d$"), "done row " + water);
            c.check(boulder.contains(FAIL) && boulder.contains("HxMateB"), "failed row " + boulder);
            c.note("header " + header(c) + ", rows " + rows(c));
            // Puzzle Solvers off drops the names, Puzzle Times off drops the clock.
            run(c, () -> {
                Mod.with(PUZZLE_CFG, "ShowWho", false);
                Mod.with(PUZZLE_CFG, "ShowTimes", false);
            });
            dungeon(c, "puzzles", puzzles("\u00a7a" + DONE, "\u00a7c" + FAIL));
            c.waitUntil("rows without who and time", mc -> !((List<?>) Mod.staticCall(PUZZLE, "rowsForTest")).toString().contains("HxMateA"), 80);
            c.check(!rowOf(c, "Water Board").matches(".*\\d:\\d\\d$"), "time still shown: " + rows(c));
            run(c, () -> {
                Mod.set(PUZZLE_CFG, "setShowWho", true);
                Mod.set(PUZZLE_CFG, "setShowTimes", true);
            });
            // Leaving the dungeon clears the rows.
            hub(c);
            c.waitUntil("rows cleared outside a dungeon", mc -> ((List<?>) Mod.staticCall(PUZZLE, "rowsForTest")).isEmpty(), 60);
        }
    }

    // ---- Reparty --------------------------------------------------------------------------------------------------------

    private static void partyList(Session c, String leader) {
        c.hx().chat("Party Members (3)");
        c.hx().chat("Party Leader: [MVP+] " + leader + " \u25cf");
        c.hx().chat("Party Moderators: HxMateA \u25cf");
        c.hx().chat("Party Members: [VIP] HxMateB \u25cf ");
    }

    private static List<String> chat(Session c) {
        @SuppressWarnings("unchecked")
        List<String> raw = c.onClient(mc -> (List<String>) Mod.staticCall(TIDY, "testChatLines", 30));
        return raw;
    }

    private static void repartyRun(Session c) {
        try (Settings set = new Settings(c).with(RP_CFG, "Enabled", true)) {
            hub(c);
            run(c, () -> Mod.staticCall(RP, "start", false));
            c.waitUntil("party list sent", mc -> c.commands().contains("party list"), 60);
            c.check(!c.commands().contains("party disband"), "disband went before the list was read");
            partyList(c, self(c));
            c.waitUntil("disband sent", mc -> c.commands().contains("party disband"), 100);
            c.waitUntil("invite sent", mc -> c.commands().stream().anyMatch(s -> s.startsWith("party invite")), 100);
            List<String> cmds = c.commands().stream().filter(s -> s.startsWith("party")).toList();
            c.check(cmds.equals(List.of("party list", "party disband", "party invite HxMateA HxMateB")), "commands on the wire: " + cmds);
            c.note("wire: " + cmds);
        }
    }

    private static void repartyNotLeader(Session c) {
        try (Settings set = new Settings(c).with(RP_CFG, "Enabled", true)) {
            hub(c);
            run(c, () -> Mod.staticCall(RP, "start", false));
            c.waitUntil("party list sent", mc -> c.commands().contains("party list"), 60);
            partyList(c, "HxMateA");
            c.waitUntil("not-leader line", mc -> chat(c).stream().anyMatch(l -> l.contains("Only the party leader")), 100);
            c.ctx().waitTicks(40);
            c.check(c.commands().stream().noneMatch(s -> s.startsWith("party disband") || s.startsWith("party invite")),
                    "a non-leader sent " + c.commands());
        }
        // Switch OFF: /rp says so and sends nothing.
        run(c, () -> Mod.staticCall(RP, "start", false));
        c.ctx().waitTicks(20);
        c.check(c.commands().stream().noneMatch(s -> s.equals("party list")) || c.commands().stream().filter(s -> s.equals("party list")).count() == 1,
                "Reparty OFF sent a second party list");
        c.check(chat(c).stream().anyMatch(l -> l.contains("Reparty is off")), "no 'Reparty is off' line");
    }

    private static void repartyAuto(Session c) {
        try (Settings set = new Settings(c).with(RP_CFG, "Enabled", true).with(RP_CFG, "Auto", false).with(RP_CFG, "DelaySeconds", 3)) {
            dungeon(c);
            c.hx().chat(" > EXTRA STATS <");
            c.ctx().waitTicks(100);
            c.check(!c.commands().contains("party list"), "Auto Reparty OFF reparty'd");
            run(c, () -> Mod.set(RP_CFG, "setAuto", true));
            c.hx().chat(" > EXTRA STATS <");
            c.ctx().waitTicks(30);
            c.check(!c.commands().contains("party list"), "Auto Reparty started before its 3 s delay");
            c.waitUntil("Auto Reparty sent party list after the delay", mc -> c.commands().contains("party list"), 100);
            c.note("auto: party list sent after the run's end line");
        }
    }

    private static void repartyMoving(Session c) {
        try (Settings set = new Settings(c).with(RP_CFG, "Enabled", true)) {
            hub(c);
            c.ctx().runOnClient(mc -> mc.options.keyUp.setDown(true));
            try {
                run(c, () -> Mod.staticCall(RP, "start", false));
                c.ctx().waitTicks(40);
                c.check(!c.commands().contains("party list"), "party list sent while a movement key was held (ChatC)");
            } finally {
                c.ctx().runOnClient(mc -> mc.options.keyUp.setDown(false));
            }
            c.waitUntil("party list once standing still", mc -> c.commands().contains("party list"), 160);
        }
    }

    // ---- Watcher Helper -------------------------------------------------------------------------------------------------

    private static void speak(Session c, long speakMs, long totalMs) {
        long t0 = 1_000_000L;
        run(c, () -> {
            Mod.staticCall(WH, "onLine", "Starting in 1 second.", t0 - 1000L);
            Mod.staticCall(WH, "onLine", "[BOSS] The Watcher: Ah, you've finally arrived.", t0);
            Mod.staticCall(WH, "onLine", "[BOSS] The Watcher: Let's see how you can handle this.", t0 + speakMs);
            Mod.staticCall(WH, "onLine", "[BOSS] The Watcher: You have proven yourself. You may pass.", t0 + totalMs);
        });
    }

    private static void watcherHelper(Session c) {
        dungeon(c);
        // OFF: nothing is shown.
        run(c, () -> Mod.staticCall("dungeonalerts.DungeonAlertsFeature", "showTitle", "", "", 0, 1, 0));
        speak(c, 21_000L, 40_000L);
        c.check(((Number) c.onClient(mc -> Mod.staticCall(WH, "speakMsForTest"))).longValue() == 0L, "Watcher Helper OFF timed the room");
        try (Settings set = new Settings(c).with(WH_CFG, "Enabled", true)) {
            long[][] cases = {{21_500L, 0}, {22_500L, 1}, {24_000L, 2}, {26_000L, 3}};
            String[] grades = {"Vroom", "Watcher", "Yawn", "Zzz"};
            for (int i = 0; i < cases.length; i++) {
                speak(c, cases[i][0], cases[i][0] + 18_000L);
                String title = c.onClient(mc -> HxAlerts1025Cases.guiTitle(mc));
                c.check(title != null && title.contains(grades[i]), "speak " + cases[i][0] + " ms showed title '" + title + "', not " + grades[i]);
            }
            c.check(chat(c).stream().anyMatch(l -> l.contains("Speak 26.00s, Total 44.00s")), "breakdown line missing: " + chat(c));
            // The Title switch off: the speak time is still timed but no title.
            run(c, () -> Mod.set(WH_CFG, "setSpeedTitle", false));
            run(c, () -> Mod.staticCall("dungeonalerts.DungeonAlertsFeature", "showTitle", "", "", 0, 1, 0));
            speak(c, 21_000L, 30_000L);
            String off = c.onClient(mc -> HxAlerts1025Cases.guiTitle(mc));
            c.check(off == null || !off.contains("Vroom"), "title shown with Watcher Speed Title off: " + off);
            run(c, () -> Mod.set(WH_CFG, "setSpeedTitle", true));
            // Real chat path: the greeting then the speaking line over the wire reads a ~0 s speak time as Vroom.
            run(c, () -> Mod.staticCall("dungeonalerts.DungeonAlertsFeature", "showTitle", "", "", 0, 1, 0));
            run(c, () -> Mod.staticCall(WH, "onLine", "Starting in 1 second.", 1L));
            c.hx().chat("[BOSS] The Watcher: Ah, you've finally arrived.");
            c.ctx().waitTicks(4);
            c.hx().chat("[BOSS] The Watcher: Let's see how you can handle this.");
            c.waitUntil("real chat drove the Vroom title", mc -> {
                String t = HxAlerts1025Cases.guiTitle(mc);
                return t != null && t.contains("Vroom");
            }, 60);
        }
    }
}

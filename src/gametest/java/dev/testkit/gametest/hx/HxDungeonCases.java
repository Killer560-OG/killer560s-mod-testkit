package dev.testkit.gametest.hx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.testkit.gametest.Fixtures;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Dungeon chat/HUD cases, 101-119. Mod source references are to killer560s-mod at f40ec89 (paths under
 * src/main/java/com/killer560/hub/). Each case states its premise, makes the mod act, and reads the mod's own state
 * (or the command the server received) back; a forged/hostile variant shows the same parser refusing what another
 * player could type.
 */
final class HxDungeonCases {

    private HxDungeonCases() {
    }

    static void register(Session s) {
        templates(s);
        dungeonState(s);
        skyblockGate(s);
        scoreCalc(s);
        dungeonInfo(s);
        runStats(s);
        runSummary(s);
        rngMeter(s);
        blessings(s);
        classColors(s);
        dungeonQueue(s);
        autoKick(s);
        doorKeys(s);
        architect(s);
        quiz(s);
        liveMap(s);
        interop(s);
        teammates(s);
    }

    // ---- 101 templates -------------------------------------------------------------------------------------

    private static void templates(Session s) {
        s.test("101-hx-templates", c -> {
            // The rendered templates must be exactly the fixtures WP5 checks against the mod's patterns.
            JsonObject sb = c.hx().call("surface.sidebar", Hx.args("template", "dungeon", "floor", "F7")).getAsJsonObject();
            JsonObject want = fx(c, "sidebar.dungeon-f7").payload().getAsJsonObject();
            c.check(sb.equals(want), "surface.sidebar dungeon != fixture sidebar.dungeon-f7:\n  " + sb + "\n  " + want);
            JsonObject q = c.hx().call("surface.sidebar", Hx.args("template", "queue", "floor", "F7")).getAsJsonObject();
            c.check(q.equals(fx(c, "sidebar.queue-f7").payload().getAsJsonObject()), "queue template drifted: " + q);
            JsonObject hub = c.hx().call("surface.sidebar", Hx.args("template", "hub")).getAsJsonObject();
            c.check(hub.equals(fx(c, "sidebar.hub").payload().getAsJsonObject()), "hub template drifted: " + hub);
            JsonObject ch = c.hx().call("surface.sidebar", Hx.args("template", "crystal_hollows")).getAsJsonObject();
            c.check(ch.equals(fx(c, "sidebar.crystal-hollows").payload().getAsJsonObject()), "CH template drifted: " + ch);
            JsonObject tab = c.hx().call("surface.tab", Hx.args("template", "dungeon",
                    "players", List.of(player("HxMateA", "Mage", null),
                            Map.of("name", "HxMateB", "cls", "Berserk", "level", "XL", "lvl", 38, "rank", "[MVP+]")),
                    "secrets", 12, "secretsPercent", "34.5", "crypts", 5, "rooms", 9, "deaths", 1,
                    "puzzles", List.of(Map.of("name", "Three Weirdos", "state", "§r§a✔"), Map.of("name", "Quiz", "state", "§r§c✖"))))
                    .getAsJsonObject();
            List<String> shown = new ArrayList<>();
            for (JsonElement e : tab.getAsJsonArray("entries")) {
                String t = e.getAsJsonObject().get("text").getAsString();
                if (!t.isEmpty()) {
                    shown.add(t);
                }
            }
            List<String> expected = new ArrayList<>();
            fx(c, "tab.dungeon-run").payload().getAsJsonObject().getAsJsonArray("entries").forEach(e -> expected.add(e.getAsString()));
            c.check(shown.equals(expected), "surface.tab dungeon != fixture tab.dungeon-run:\n  " + shown + "\n  " + expected);
            c.check(tab.getAsJsonArray("entries").size() == 40, "two 20-row columns expected, got "
                    + tab.getAsJsonArray("entries").size());
            // And applied: the mod reads them.
            dungeon(c, "secrets", 12, "crypts", 5);
            String ch2 = c.onClient(mc -> String.valueOf(Mod.staticCall("pathfinding.IslandDetector", "graphIsland")));
            c.note("templates == fixtures; applied: floor F7, PartyTracker mates, IslandDetector.graphIsland()=" + ch2);
        });
    }

    // ---- 102 secrets/DungeonState -----------------------------------------------------------------------

    private static void dungeonState(Session s) {
        s.test("102-hx-dungeonstate-boss-and-queue", c -> {
            dungeon(c);
            c.check(!(Boolean) c.onClient(mc -> Mod.staticCall("secrets.DungeonState", "isBossPhaseActive")),
                    "premise: boss phase already active");
            send(c, "hostile.allchat-maxor");
            c.ctx().waitTicks(10);
            c.check(!(Boolean) c.onClient(mc -> Mod.staticCall("secrets.DungeonState", "isBossPhaseActive")),
                    "a FORGED all-chat Maxor line started the boss phase");
            send(c, "chat.boss-maxor-start");
            c.waitUntil("DungeonState.isBossPhaseActive() after Maxor's real line",
                    mc -> (Boolean) Mod.staticCall("secrets.DungeonState", "isBossPhaseActive"), 60);
            // Queue sidebar: floor goes null (DungeonState :214) and with it the boss phase.
            sidebar(c, "template", "queue", "floor", "F7");
            c.waitUntil("floor null on a Queue sidebar", mc -> Mod.staticCall("secrets.DungeonState", "getFloor") == null, 60);
            c.waitUntil("boss phase off after leaving F7",
                    mc -> !(Boolean) Mod.staticCall("secrets.DungeonState", "isBossPhaseActive"), 60);
            sidebar(c, "template", "dungeon", "floor", "F7", "room", "Hx Room");
            c.waitUntil("floor F7 again", mc -> "F7".equals(Mod.staticCall("secrets.DungeonState", "getFloor")), 60);
            String room = c.onClient(mc -> (String) Mod.staticCall("secrets.DungeonState", "sidebarRoomName"));
            c.check("Hx Room".equals(room), "sidebarRoomName() = " + room);
            c.note("forged Maxor ignored; real Maxor -> boss phase; Queue sidebar -> floor null + boss off; Room: line -> " + room);
        });
    }

    // ---- 103 util/SkyblockGate ----------------------------------------------------------------------------------

    private static void skyblockGate(Session s) {
        s.test("103-hx-skyblockgate", c -> {
            boolean was = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
            try {
                run(c, () -> Mod.staticCall("util.SkyblockGate", "setEnabled", true));
                sidebar(c, "template", "hub");
                c.waitUntil("SkyblockGate.isOnSkyblock() on a SKYBLOCK sidebar",
                        mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isOnSkyblock"), 40);
                c.check(c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "allows")), "allows() false on Skyblock");
                // Not Skyblock: a lobby whose title lacks SKYBLOCK (the gate's only test, SkyblockGate :96-97).
                sidebar(c, "template", "hub", "title", "§e§lBED WARS");
                c.waitUntil("isOnSkyblock() false on a non-Skyblock sidebar",
                        mc -> !(Boolean) Mod.staticCall("util.SkyblockGate", "isOnSkyblock"), 40);
                c.check(!c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "allows")),
                        "allows() true off Skyblock with Skyblock Only on");
                // Spaces and colour codes inside the title still count (stripped, de-spaced).
                sidebar(c, "template", "hub", "title", "§e§lSKY §6BLOCK");
                c.waitUntil("isOnSkyblock() with 'SKY BLOCK' title",
                        mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isOnSkyblock"), 40);
                c.note("SKYBLOCK -> on; BED WARS -> off and allows() false; 'SKY §6BLOCK' -> on");
            } finally {
                run(c, () -> Mod.staticCall("util.SkyblockGate", "setEnabled", was));
                sidebar(c, "template", "hub");
            }
        });
    }

    // ---- 104 scorecalc ------------------------------------------------------------------------------------

    private static void scoreCalc(Session s) {
        s.test("104-hx-scorecalc", c -> {
            try (Settings set = new Settings(c).with("scorecalc.ScoreCalculatorConfig", "Enabled", true)
                    .with("scorecalc.ScoreCalculatorConfig", "PrinceAlertEnabled", false)) {
                dungeon(c, "secrets", 12, "secretsPercent", "34.5", "crypts", 5, "rooms", 9, "deaths", 1);
                c.waitUntil("ScoreCalculatorFeature.getCrypts() == 5 from the tab list",
                        mc -> ((Number) Mod.staticCall("scorecalc.ScoreCalculatorFeature", "getCrypts")).intValue() == 5, 100);
                Object result = c.onClient(mc -> Mod.staticCall("scorecalc.ScoreCalculatorFeature", "currentResult"));
                c.check(result != null, "no score result after the tab list was read");
                // Prince: forged all-chat first (the flag latches for the run), then the real server line.
                run(c, () -> Mod.setField("scorecalc.ScoreCalculatorFeature", "princeKilled", false));
                send(c, "hostile.allchat-prince");
                c.ctx().waitTicks(10);
                c.check(!c.onClient(mc -> (Boolean) Mod.staticCall("scorecalc.ScoreCalculatorFeature", "isPrinceKilled")),
                        "a forged all-chat 'A Prince falls' marked the prince killed");
                send(c, "chat.prince-killed");
                c.waitUntil("isPrinceKilled() after the real bonus line",
                        mc -> (Boolean) Mod.staticCall("scorecalc.ScoreCalculatorFeature", "isPrinceKilled"), 40);
                c.note("crypts 5 from tab, result " + result + "; forged prince ignored, real prince counted");
            }
        });
    }

    // ---- 105 dungeoninfo ------------------------------------------------------------------------------------

    private static void dungeonInfo(Session s) {
        s.test("105-hx-dungeoninfo", c -> {
            dungeon(c, "secrets", 12);
            c.waitUntil("DungeonInfoFeature.lastSecretsCount == 12",
                    mc -> ((Number) Mod.field("dungeoninfo.DungeonInfoFeature", "lastSecretsCount")).intValue() == 12, 60);
            // Hostile tab: an 11-digit count. Before a0fe2a2 this threw every tick until FeatureGuard turned the
            // feature off; Session's Mod.assertHealthy fails the case on any FeatureGuard disable or ERROR line.
            Fixtures.Fixture hostile = fx(c, "hostile.tab-secrets-overflow");
            send(c, hostile);
            c.ctx().waitTicks(40);
            int after = c.onClient(mc -> ((Number) Mod.field("dungeoninfo.DungeonInfoFeature", "lastSecretsCount")).intValue());
            c.check(after == 12, "an 11-digit Secrets Found changed the count to " + after);
            c.note("tab 12 -> lastSecretsCount 12; 11-digit line ignored for 40 ticks, still " + after);
        });
    }

    // ---- 106 runstats -------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void runStats(Session s) {
        s.test("106-hx-runstats", c -> {
            try (Settings set = new Settings(c).with("runstats.RunStatsConfig", "Enabled", true)
                    .with("runstats.RunStatsConfig", "AutoShowExtraStats", true)
                    .with("runstats.RunStatsConfig", "AnnounceToParty", false)) {
                dungeon(c);
                run(c, () -> Mod.staticCall("runstats.RunStatsTracker", "resetRun"));
                run(c, () -> Mod.setField("runstats.RunStatsFeature", "summarisedThisRun", false));
                send(c, "hostile.allchat-death");
                send(c, "hostile.allchat-extra-stats");
                c.ctx().waitTicks(10);
                Map<String, ?> deaths = c.onClient(mc -> (Map<String, ?>) Mod.field("runstats.RunStatsTracker", "DEATHS"));
                c.check(deaths.isEmpty(), "a forged all-chat death line was counted: " + deaths);
                assertNoCommand(c, 5, "showextrastats");
                send(c, "chat.death-teammate");
                c.waitUntil("RunStatsTracker.DEATHS has hxmatea",
                        mc -> ((Map<String, ?>) Mod.field("runstats.RunStatsTracker", "DEATHS")).containsKey("hxmatea"), 40);
                send(c, "chat.extra-stats");
                String cmd = awaitCommand(c, "showextrastats", 60);
                c.note("forged death + EXTRA STATS ignored; real death counted; real EXTRA STATS -> /" + cmd);
                c.ctx().waitTicks(5);
                run(c, () -> Mod.setField("runstats.RunStatsFeature", "pendingTicks", -1));
            }
        });
    }

    // ---- 107 runsummary -----------------------------------------------------------------------------------

    private static void runSummary(Session s) {
        s.test("107-hx-runsummary", c -> {
            try (Settings set = new Settings(c).with("runsummary.RunSummaryConfig", "Enabled", true)) {
                dungeon(c);
                run(c, () -> Mod.staticCall("runsummary.RunSummaryFeature", "resetRun"));
                c.check(!c.onClient(mc -> (Boolean) Mod.staticCall("runsummary.RunSummaryFeature", "isTrackingRun")),
                        "premise: a run is already tracked");
                send(c, "hostile.allchat-run-start");
                c.ctx().waitTicks(10);
                c.check(!c.onClient(mc -> (Boolean) Mod.staticCall("runsummary.RunSummaryFeature", "isTrackingRun")),
                        "a forged all-chat 'Starting in 1 second.' started a run");
                send(c, "chat.run-start");
                c.waitUntil("RunSummaryFeature.isTrackingRun()",
                        mc -> (Boolean) Mod.staticCall("runsummary.RunSummaryFeature", "isTrackingRun"), 40);
                send(c, "chat.team-score");
                c.waitUntil("hypixelScore == 300",
                        mc -> ((Number) Mod.field("runsummary.RunSummaryFeature", "hypixelScore")).intValue() == 300, 40);
                String rank = c.onClient(mc -> (String) Mod.field("runsummary.RunSummaryFeature", "hypixelRank"));
                c.check("S+".equals(rank), "hypixelRank " + rank);
                // Overflowing score: must not throw out of the listener (assertHealthy) nor keep a bogus number.
                send(c, "hostile.party-team-score-overflow");
                c.ctx().waitTicks(10);
                int after = c.onClient(mc -> ((Number) Mod.field("runsummary.RunSummaryFeature", "hypixelScore")).intValue());
                c.note("forged start ignored; real start tracked; Team Score 300 (S+) read; 20-digit score -> " + after);
                run(c, () -> Mod.staticCall("runsummary.RunSummaryFeature", "resetRun"));
                run(c, () -> Mod.setField("runsummary.RunSummaryFeature", "finishDelayTicks", -1));
            }
        });
    }

    // ---- 108 rngmeter -----------------------------------------------------------------------------------------

    private static void rngMeter(Session s) {
        s.test("108-hx-rngmeter-location", c -> {
            sidebar(c, "template", "crystal_hollows");
            c.waitUntil("LocationTracker.detectCategory() == 3 (Crystal Nucleus)",
                    mc -> ((Number) Mod.staticCall("rngmeter.LocationTracker", "detectCategory")).intValue() == 3, 40);
            sidebar(c, "template", "dungeon", "floor", "F7");
            c.waitUntil("detectCategory() == 0 (Dungeons)",
                    mc -> ((Number) Mod.staticCall("rngmeter.LocationTracker", "detectCategory")).intValue() == 0, 40);
            sidebar(c, "template", "hub");
            c.waitUntil("detectCategory() == -1 in the hub",
                    mc -> ((Number) Mod.staticCall("rngmeter.LocationTracker", "detectCategory")).intValue() == -1, 40);
            c.note("CH sidebar -> 3, dungeon -> 0, hub -> -1");
        });
        s.test("109-hx-rngmeter-magicfind-forged", c -> {
            send(c, "chat.magic-find-drop");
            c.waitUntil("MagicFindTracker.getLastMagicFind() == 164",
                    mc -> Integer.valueOf(164).equals(Mod.staticCall("rngmeter.MagicFindTracker", "getLastMagicFind")), 40);
            c.ctx().waitTicks(6);
            send(c, "hostile.allchat-magic-find");
            c.ctx().waitTicks(10);
            Object after = c.onClient(mc -> Mod.staticCall("rngmeter.MagicFindTracker", "getLastMagicFind"));
            c.note("real drop -> 164; another player's '(99999% Magic Find)' in all chat -> " + after);
            c.check(Integer.valueOf(164).equals(after), "ANOTHER PLAYER'S all-chat line set the tracked Magic Find to "
                    + after + " (MagicFindTracker :19-20 is an unanchored find() on every chat line, :37-38)");
        });
    }

    // ---- 110 blessings --------------------------------------------------------------------------------------

    private static void blessings(Session s) {
        s.test("110-hx-blessings-footer", c -> {
            Object power = c.onClient(mc -> Mod.enumValue("blessings.Blessing", "POWER"));
            Object wisdom = c.onClient(mc -> Mod.enumValue("blessings.Blessing", "WISDOM"));
            run(c, () -> Mod.staticCall("blessings.BlessingTracker", "reset"));
            // Outside a dungeon the footer is not read (BlessingTracker :32).
            hub(c);
            c.hx().call("tab.set", Hx.args("entries", List.of("§r§b§lArea: §r§7Hub"), "footer", "§r§7Blessing of Wisdom III"));
            c.ctx().waitTicks(10);
            int w0 = c.onClient(mc -> ((Number) Mod.staticCall("blessings.BlessingTracker", "level", wisdom)).intValue());
            c.check(w0 == 0, "a footer outside a dungeon set Wisdom to " + w0);
            // Party chat is never a blessing source (BlessingTracker :17-20).
            dungeon(c);
            c.hx().chat("§9Party §8> §b[MVP§c+§b] HxMateA§f: Blessing of Power X");
            c.ctx().waitTicks(10);
            int p0 = c.onClient(mc -> ((Number) Mod.staticCall("blessings.BlessingTracker", "level", power)).intValue());
            c.check(p0 == 0, "party chat set Power to " + p0);
            dungeon(c, "blessings", List.of("Blessing of Power V", "Blessing of Wisdom III"));
            c.waitUntil("BlessingTracker.level(POWER) == 5 from the footer",
                    mc -> ((Number) Mod.staticCall("blessings.BlessingTracker", "level", power)).intValue() == 5, 40);
            int w = c.onClient(mc -> ((Number) Mod.staticCall("blessings.BlessingTracker", "level", wisdom)).intValue());
            c.check(w == 3, "Wisdom " + w);
            c.note("footer outside dungeon ignored; party chat ignored; dungeon footer -> Power 5, Wisdom 3");
        });
    }

    // ---- 111 dungeonalerts (Class Colors) ----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void classColors(Session s) {
        s.test("111-hx-dungeonalerts-classcolors", c -> {
            try (Settings set = new Settings(c).field("dungeonalerts.DungeonAlertsConfig", "classColorsEnabled", true)) {
                dungeon(c);
                c.waitUntil("ClassColors.CLASSES has HxMateA",
                        mc -> ((Map<String, ?>) Mod.field("dungeonalerts.ClassColors", "CLASSES")).containsKey("HxMateA"), 60);
                Object cls = c.onClient(mc -> ((Map<String, ?>) Mod.field("dungeonalerts.ClassColors", "CLASSES")).get("HxMateA"));
                c.check(String.valueOf(cls).contains("MAGE"), "HxMateA class " + cls);
                send(c, "hostile.tab-teammate-longname");
                c.ctx().waitTicks(30);
                Map<String, ?> classes = c.onClient(mc -> Map.copyOf((Map<String, ?>) Mod.field("dungeonalerts.ClassColors", "CLASSES")));
                c.check(classes.keySet().stream().noneMatch(k -> k.startsWith("HxAAAA")), "a 22-char tab name was parsed: " + classes);
                c.note("tab -> CLASSES " + classes.keySet() + " (HxMateA=" + cls + "); over-long name refused");
            }
        });
    }

    // ---- 112 dungeonqueue ------------------------------------------------------------------------------------

    private static void dungeonQueue(Session s) {
        s.test("112-hx-dungeonqueue-requeue", c -> {
            try (Settings set = new Settings(c).with("dungeonqueue.DungeonQueueConfig", "Enabled", true)
                    .with("dungeonqueue.DungeonQueueConfig", "DelaySeconds", 1)
                    .with("dungeonqueue.DungeonQueueConfig", "DisableOnLeave", true)) {
                dungeon(c);
                c.hx().stub(null, "instancerequeue");
                resetQueue(c);
                send(c, "hostile.allchat-extra-stats");
                send(c, "hostile.allchat-left");
                assertNoCommand(c, 40, "instancerequeue");
                boolean disabled = c.onClient(mc -> (Boolean) Mod.field("dungeonqueue.DungeonQueueFeature", "disableRequeue"));
                c.check(!disabled, "a forged all-chat leave line disabled the requeue");
                send(c, "chat.extra-stats");
                String cmd = awaitCommand(c, "instancerequeue", 80);
                // Leave line before the end: the next requeue is skipped.
                resetQueue(c);
                long mark = LogTap.mark();
                send(c, "chat.party-left");
                c.waitUntil("disableRequeue after a real leave line",
                        mc -> (Boolean) Mod.field("dungeonqueue.DungeonQueueFeature", "disableRequeue"), 40);
                long before = c.commands().stream().filter(x -> x.startsWith("instancerequeue")).count();
                c.ctx().waitTicks(6);
                send(c, "chat.extra-stats");
                c.ctx().waitTicks(50);
                long after = c.commands().stream().filter(x -> x.startsWith("instancerequeue")).count();
                c.check(after == before, "requeued although a party member left");
                c.check(!logLines(mark, "Skipped - a party member left").isEmpty(), "no 'Skipped' line in chat");
                c.note("forged EXTRA STATS/leave ignored; real EXTRA STATS -> /" + cmd + "; after a real leave -> skipped");
            } finally {
                resetQueue(c);
            }
        });
    }

    private static void resetQueue(Session c) {
        run(c, () -> {
            Mod.setField("dungeonqueue.DungeonQueueFeature", "requeuedThisRun", false);
            Mod.setField("dungeonqueue.DungeonQueueFeature", "disableRequeue", false);
            Mod.setField("dungeonqueue.DungeonQueueFeature", "pendingTicks", -1);
        });
    }

    // ---- 113 autokick -----------------------------------------------------------------------------------------

    private static void autoKick(Session s) {
        s.test("113-hx-autokick", c -> {
            Object cfg = c.onClient(mc -> Mod.cfg("autokick.AutoKickConfig"));
            Object f7 = c.onClient(mc -> Mod.enumValue("autokick.AutoKickConfig$Floor", "F7"));
            Object specific = c.onClient(mc -> Mod.enumValue("autokick.AutoKickConfig$ActionMode", "KICK_SPECIFIC"));
            Object oldMode = c.onClient(mc -> Mod.call(cfg, "getMode"));
            String oldMembers = c.onClient(mc -> (String) Mod.call(cfg, "getSpecificMembers"));
            int oldTarget = c.onClient(mc -> ((Number) Mod.call(cfg, "getTargetSeconds", f7)).intValue());
            try (Settings set = new Settings(c).with("autokick.AutoKickConfig", "Enabled", true)
                    .with("splittimers.SplitTimersConfig", "Enabled", true)
                    .custom(() -> {
                        Mod.call(cfg, "setMode", specific);
                        Mod.call(cfg, "setTargetSeconds", f7, 1);
                    }, () -> {
                        Mod.call(cfg, "setMode", oldMode);
                        Mod.call(cfg, "setTargetSeconds", f7, oldTarget);
                        Mod.call(cfg, "setSpecificMembers", oldMembers);
                    })) {
                dungeon(c);
                // Forged: the name is configured, but HxEvil is NOT on the tab list - only in all chat.
                run(c, () -> Mod.call(cfg, "setSpecificMembers", "HxEvil"));
                startRun(c);
                c.hx().chat("§7[VIP] HxEvil§f: [42] HxEvil (Mage L)");
                assertNoCommand(c, 50, "p kick");
                // Positive: a real teammate named in the settings.
                run(c, () -> Mod.call(cfg, "setSpecificMembers", "HxMateA"));
                startRun(c);
                String cmd = awaitCommand(c, "p kick", 100);
                c.check(cmd.equals("p kick HxMateA"), "kicked the wrong name: /" + cmd);
                c.note("non-teammate name -> no kick in 2.5 s; teammate HxMateA, F7 target 1 s -> /" + cmd);
            }
        });
    }

    /** "Starting in 1 second." then Mort's line: Split Timers' first split, which starts the run clock. */
    private static void startRun(Session c) {
        run(c, () -> Mod.setField("autokick.AutoKickFeature", "lastKickAtMs", 0L));
        send(c, "chat.run-start");
        c.ctx().waitTicks(6);
        send(c, "chat.mort-map");
        c.waitUntil("SplitTimersFeature.getRunStartedAtMs() > 0",
                mc -> ((Number) Mod.staticCall("splittimers.SplitTimersFeature", "getRunStartedAtMs")).longValue() > 0, 40);
    }

    // ---- 114 doorkeys -----------------------------------------------------------------------------------------

    private static void doorKeys(Session s) {
        s.test("114-hx-doorkeys-stand", c -> {
            try (Settings set = new Settings(c).with("doorkeys.DoorKeysConfig", "Enabled", true)
                    .with("doorkeys.DoorKeysConfig", "HighlightWither", true)) {
                dungeon(c);
                double[] at = c.scenario().playerPosition();
                c.hx().removeStands();
                // Forged: a key-shaped name that is not the fixed "Wither Key" (DoorKeysFeature :74, equals()).
                c.hx().stand(at[0] + 2, at[1], at[2], "§7[VIP] HxEvil§f: Wither Key");
                c.hx().stand(at[0] - 2, at[1], at[2], "§8Wither Keys");
                c.ctx().waitTicks(30);
                Object none = c.onClient(mc -> Mod.field("doorkeys.DoorKeysFeature", "currentKey"));
                c.check(none == null, "a look-alike stand was taken for the Wither Key: " + none);
                c.hx().stand(at[0], at[1], at[2] + 3, "§8Wither Key");
                c.waitUntil("DoorKeysFeature.currentKey is the 'Wither Key' stand",
                        mc -> Mod.field("doorkeys.DoorKeysFeature", "currentKey") != null, 60);
                c.note("look-alike names ignored; '§8Wither Key' stand -> currentKey set");
            } finally {
                c.hx().removeStands();
            }
        });
    }

    // ---- 115 architect ------------------------------------------------------------------------------------------

    private static void architect(Session s) {
        s.test("115-hx-architect-draft", c -> {
            String self = self(c);
            boolean cheat = Mod.isCheat();
            try (Settings set = new Settings(c).with("architect.ArchitectDraftConfig", "ClickMessage", true)
                    .with("architect.ArchitectDraftConfig", (cheat ? "AutoGet" : "ClickMessage"), true)) {
                dungeon(c);
                run(c, () -> Mod.setField("architect.ArchitectDraftFeature", "lastMs", 0L));
                long mark = LogTap.mark();
                // Forged: Oruo's line quoted in all chat (ArchitectDraftFeature :44 is anchored at ^[STATUE]).
                c.hx().chat("§7[VIP] HxEvil§f: [STATUE] Oruo the Omniscient: " + self + " chose the wrong answer!");
                assertNoCommand(c, 20, "gfs");
                c.check(logLines(mark, "Architect's First Draft").isEmpty(), "a forged fail line produced a draft prompt");
                // Real: shape from :44 with this client's own name.
                c.hx().chat("§4[STATUE] Oruo the Omniscient§r§f: §r§b" + self + " §r§cchose the wrong answer! I shall never forget this moment of misrememberance.");
                if (cheat) {
                    String cmd = awaitCommand(c, "gfs", 60);
                    c.check(cmd.equals("gfs architect_first_draft 1"), "/" + cmd);
                    c.note("forged ignored; own Quiz fail -> /" + cmd + " (Auto Get, cheat jar)");
                } else {
                    c.waitUntil("the clickable draft line in chat",
                            mc -> !logLines(mark, "Click to get an Architect's First Draft").isEmpty(), 60);
                    c.note("forged ignored; own Quiz fail -> click message (legit jar)");
                }
            }
        });
    }

    // ---- 116 puzzle NPC lines (Quiz) ------------------------------------------------------------------------------

    private static void quiz(Session s) {
        s.test("116-hx-puzzle-quiz-lines", c -> {
            try (Settings set = new Settings(c).with("puzzlesolvers.QuizSolverConfig", "Enabled", true)) {
                dungeon(c);
                run(c, () -> Mod.setField("puzzlesolvers.QuizSolverFeature", "triviaAnswers", null));
                send(c, "hostile.allchat-quiz-year");
                c.ctx().waitTicks(10);
                c.check(c.onClient(mc -> Mod.field("puzzlesolvers.QuizSolverFeature", "triviaAnswers")) == null,
                        "a forged all-chat question set the Quiz answers");
                send(c, "chat.quiz-year");
                c.waitUntil("QuizSolverFeature.triviaAnswers set by Oruo's question",
                        mc -> Mod.field("puzzlesolvers.QuizSolverFeature", "triviaAnswers") != null, 40);
                // QuizSolverFeature :152: the SkyBlock year, computed exactly as the mod does.
                long year = ((System.currentTimeMillis() / 1000L) - 1560276000L) / 446400L + 1;
                send(c, "hostile.allchat-quiz-answer");
                c.ctx().waitTicks(6);
                c.hx().chat("§6 ⓑ §aYear " + year);
                c.waitUntil("option ⓑ marked correct", mc -> {
                    Object[] options = (Object[]) Mod.field("puzzlesolvers.QuizSolverFeature", "options");
                    return (Boolean) Mod.field(options[1], "correct");
                }, 40);
                boolean a = c.onClient(mc -> (Boolean) Mod.field(((Object[]) Mod.field("puzzlesolvers.QuizSolverFeature", "options"))[0], "correct"));
                c.check(!a, "the forged all-chat 'ⓐ' answer marked option ⓐ");
                c.note("forged question/answer ignored; real question -> answers [Year " + year + "]; 'ⓑ Year " + year + "' -> option b");
            }
        });
    }

    // ---- 117 livemap (hx map item) -----------------------------------------------------------------------------

    private static void liveMap(Session s) {
        s.test("117-hx-livemap-map-item", c -> {
            try (Settings set = new Settings(c).with("livemap.LiveMapConfig", "Enabled", true)) {
                dungeon(c);
                run(c, () -> Mod.staticCall("livemap.DungeonMapScanner", "reset"));
                // Forged: no 16/18-pixel entrance run (colour 30, DungeonMapScanner :37, :258-283) - noise only.
                c.hx().call("surface.map", "id", 9002, "rects", List.of(List.of(5, 5, 10, 3, 30), List.of(40, 40, 16, 16, 63)));
                c.hx().give(8, "minecraft:filled_map[map_id=9002]");
                c.ctx().waitTicks(40);
                c.check(!c.onClient(mc -> (Boolean) Mod.staticCall("livemap.DungeonMapScanner", "isCalibrated")),
                        "a map with no entrance room calibrated");
                // Real shape: entrance 16x16 at (5,5) -> room size 16, start corner (5,5) for F7 (:270-276);
                // a normal room (63) two cells right, centre 34 = cleared (:203); a door (63) between them.
                c.hx().call("surface.map", "id", 9001, "rects", List.of(
                        List.of(5, 5, 16, 16, 30),
                        List.of(25, 5, 16, 16, 63), List.of(31, 11, 4, 4, 34),
                        List.of(21, 11, 4, 4, 63)));
                c.hx().give(8, "minecraft:filled_map[map_id=9001]");
                c.waitUntil("DungeonMapScanner.isCalibrated()",
                        mc -> (Boolean) Mod.staticCall("livemap.DungeonMapScanner", "isCalibrated"), 80);
                c.waitUntil("cell 2 classified as a cleared room", mc ->
                        ((Number) Mod.staticCall("livemap.DungeonMapScanner", "kindAt", 2)).intValue() == 1
                                && ((Number) Mod.staticCall("livemap.DungeonMapScanner", "stateAt", 2)).intValue() == 1, 40);
                int k0 = c.onClient(mc -> ((Number) Mod.staticCall("livemap.DungeonMapScanner", "kindAt", 0)).intValue());
                int k1 = c.onClient(mc -> ((Number) Mod.staticCall("livemap.DungeonMapScanner", "kindAt", 1)).intValue());
                c.check(k0 == 1, "entrance cell kind " + k0);
                c.check(k1 == 3, "door cell kind " + k1 + " (3 = door)");
                c.note("noise map not calibrated; entrance+room+door map -> calibrated, cell0 room, cell1 door, cell2 cleared room");
            } finally {
                c.hx().call("give", "slot", 8, "stack", "minecraft:air");
            }
        });
    }

    // ---- 118 interop ------------------------------------------------------------------------------------------

    private static void interop(Session s) {
        s.test("118-hx-interop-party-flags", c -> {
            try (Settings set = new Settings(c).with("interop.InteropConfig", "Enabled", true)
                    .with("interop.InteropConfig", "ChatParsing", true)) {
                dungeon(c);
                Object mimic = c.onClient(mc -> Mod.enumValue("interop.PartyInteropState$Flag", "MIMIC_KILLED"));
                run(c, () -> Mod.staticCall("interop.PartyInteropState", "reset"));
                send(c, "hostile.allchat-mimic");
                c.hx().chat("§9Party §8> §b[MVP§c+§b] HxMateA§f: the mimic is in the room below");
                c.ctx().waitTicks(10);
                c.check(!c.onClient(mc -> (Boolean) Mod.staticCall("interop.PartyInteropState", "flag", mimic)),
                        "forged/loose mimic text set MIMIC_KILLED");
                send(c, "chat.party-mimic");
                c.waitUntil("PartyInteropState.flag(MIMIC_KILLED)",
                        mc -> (Boolean) Mod.staticCall("interop.PartyInteropState", "flag", mimic), 40);
                c.check(c.onClient(mc -> (Boolean) Mod.staticCall("interop.InteropFeature", "mimicKilled")),
                        "InteropFeature.mimicKilled() false");
                c.note("all-chat quote and 'the mimic is in the room below' ignored; 'Mimic Killed!' from a party mate -> flag");
                run(c, () -> Mod.staticCall("interop.PartyInteropState", "reset"));
            }
        });
    }

    // ---- 119 teammates ----------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void teammates(Session s) {
        s.test("119-hx-teammates-esp", c -> {
            dev.testkit.gametest.TestEnemy bot = null;
            try (Settings set = new Settings(c).with("teammates.TeammatesConfig", "Enabled", true)
                    .with("teammates.TeammatesConfig", "HighlightSelf", true)
                    .with("teammates.TeammatesConfig", "ThroughWalls", true)) {
                double[] at = c.scenario().playerPosition();
                bot = dev.testkit.gametest.TestEnemy.named("Tm", "A").at(at[0] + 2, at[1], at[2]).frozen(true)
                        .spawn(c.ctx(), c.server());
                String botName = c.onClient(mc -> {
                    for (net.minecraft.world.entity.player.Player p : mc.level.players()) {
                        if (p != mc.player) {
                            return p.getGameProfile().name();
                        }
                    }
                    return null;
                });
                c.check(botName != null, "premise: the bot player is not visible to the client");
                // The bot is on the dungeon tab as a teammate - but its UUID is an offline v3, not a v4 player.
                sidebar(c, "template", "dungeon", "floor", "F7");
                tab(c, "template", "dungeon", "players", List.of(player(self(c), "Archer", null), player(botName, "Mage", null)));
                c.waitUntil("Teammates targets include the client itself", mc -> {
                    Map<Integer, Integer> t = (Map<Integer, Integer>) Mod.field("teammates.TeammatesFeature", "targets");
                    return t.containsKey(mc.player.getId());
                }, 60);
                int botId = c.onClient(mc -> {
                    for (net.minecraft.world.entity.player.Player p : mc.level.players()) {
                        if (p != mc.player) {
                            return p.getId();
                        }
                    }
                    return -1;
                });
                int version = c.onClient(mc -> {
                    for (net.minecraft.world.entity.player.Player p : mc.level.players()) {
                        if (p != mc.player) {
                            return p.getUUID().version();
                        }
                    }
                    return -1;
                });
                Map<Integer, Integer> targets = c.onClient(mc -> Map.copyOf((Map<Integer, Integer>) Mod.field("teammates.TeammatesFeature", "targets")));
                c.check(!targets.containsKey(botId), "a non-v4 (NPC-shaped) player listed on the tab was highlighted");
                c.note("self highlighted; tab-listed bot " + botName + " (UUID v" + version + ") not highlighted: " + targets.keySet());
            } finally {
                if (bot != null) {
                    bot.remove();
                }
            }
        });
    }
}

package dev.testkit.gametest.ui;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.mod.Quiet;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.util.List;

/**
 * WP4 entrypoint: client UI, commands and config (singleplayer, no server). Scenario names start `3NN-ui-`;
 * `-Psuite=ui` selects them all, `-Pscenario=ui-smoke` only the smoke group (301-306, both jars, under 3 minutes).
 *
 * <pre>
 * title screen:  340 main menu theme, 330 ModPaths.migrateAll, 310 config file round trip, 311 config setters,
 *                320 profiles save/export/import/apply, 380 Auto Scale (window sizes, drawn size, clicks)
 * singleplayer:  301 every ModScreen tab, 302 search, 303 toggles, 304 HUD editor, 305 every mod Screen,
 *                306 client commands, 350 termism, 360 client visuals, 307 /profit hub and trackers,
 *                385 Interactive Map input/panel, 386 mod-menu sliders driven by real input + no overlapping rows
 *                (and the sim room Filters panel: chips wrap, no overlap, all reachable),
 *                387 AP3 node editor (type dropdown, per-type fields saved, hotbar item pick, no overlaps),
 *                388 Breaker Aura Display x Box Style each draw (screenshot pixel counts, a pick behind a wall),
 *                389 Custom Crosshair replaces vanilla's and draws exactly the pixels it is set to, editor preview,
 *                390 Wither Doors Style / Fill Opacity / Fill Color draw (screenshot pixels, alpha, through walls, reload),
 *                391 Auto Close Chest / Auto Blood Camp cheat-only tabs, rows, tooltips, legit jar without the code,
 *                392 Blood Camp look against NoammAddons, line start, predicted spot, countdown sounds,
 *                393 Health and Mana Bars tab: no scale control, every row reachable behind its parent, wired,
 *                tooltipped; an old jar's save draws the bar at the same pixel size (TESTKIT_BARS_EXPORT / seedConfig),
 *                394 every HUD element's editor box against the bounds of what it draws (slack 3 units a side),
 *                395 Dungeon Map Player Heads (his and a teammate's skin face, no-skin teammate keeps the arrow,
 *                heading ticks, Interactive Map too) and the outlined arrow on a green and a brown room, reload,
 *                401 Custom Scoreboard lists: drag / trash / Add by real mouse input change the saved and DRAWN
 *                order, auto-scroll, per-line options, reload, old toggle saves migrate to the same drawn board
 *                (TESTKIT_SCOREBOARD_EXPORT on the old jar, -PseedConfig on the new), no overlaps,
 *                399 HUD editor resize handles / cursors / snapping (real mouse drags, Auto Scale 0.5 and 1) and the
 *                Stat Bars Vitality + XP readouts and Classic Display migration ({@link HudEditorCases}),
 *                407 Health and Mana Bars Layout: Predefined areas round the hotbar measured on screenshots at Auto
 *                Scale 0.5 and 1 (shared width, clear of the vanilla rows), real-mouse drags between areas and onto
 *                Hidden, Custom positions untouched, old configs stay Custom ({@link StatBarsLayoutCases})
 * end:           370 deny lists (no child process, hooks in force)
 * </pre>
 *
 * Before any case: the QUIET profile (network features off) and SkyblockGate off, so nothing is refused for not
 * being on Skyblock. The whole run is watched for child processes (ProcWatch).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class UiSuite implements FabricClientGameTest {

    static final String[] TITLE_CASES = {"340-ui-mainmenu", "330-ui-migrate", "310-ui-config-file-roundtrip",
            "311-ui-config-setters", "320-ui-profiles", "380-ui-autoscale"};
    static final String[] WORLD_CASES = {"301-ui-smoke-tabs", "302-ui-smoke-search", "303-ui-smoke-toggles",
            "304-ui-smoke-hud-editor", "305-ui-smoke-screens", "306-ui-smoke-commands", "350-ui-termism",
            "360-ui-visuals", "365-ui-overlay-draws", "307-ui-profit", "385-ui-interactive-map", "386-ui-sliders",
            "387-ui-ap3-edit", "388-ui-breaker-display", "389-ui-crosshair",
            "390-ui-wither-doors-fill", "391-ui-cheat-tabs", "392-ui-blood-camp", "393-ui-stat-bars",
            "394-ui-hud-boxes", "395-ui-map-heads",
            "399-ui-hud-editor-resize", "399-ui-hud-editor-snap",
            "399-ui-stat-bars-vitality-xp", "407-ui-stat-bars-layout", "401-ui-scoreboard-editor",
            "411-ui-mining-shelved"};
    static final String DENY_CASE = "370-ui-deny";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (dev.testkit.harness.ScenarioList.active() || dev.testkit.harness.ModGate.missing() != null) {
            // ...and with killer560smod not loaded, the same loop gives every selected case its SKIP row.
            // List mode (-PlistScenarios): name the cases, open no world.
            for (String n : TITLE_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : WORLD_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            dev.testkit.gametest.Scenario.skip(DENY_CASE);
            return;
        }
        boolean anyTitle = any(TITLE_CASES);
        boolean anyWorld = any(WORLD_CASES);
        if (!anyTitle && !anyWorld && !UiCase.selected(DENY_CASE)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        long suiteMark = LogTap.mark();
        Deny deny = Deny.load();
        try (ProcWatch watch = new ProcWatch()) {
            List<String> quiet = ctx.computeOnClient(mc -> {
                List<String> done = Quiet.enabled() ? Quiet.apply() : List.of();
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                return done;
            });
            System.out.println("[ui] QUIET profile: " + quiet.size() + " switch(es) off; SkyblockGate off; "
                    + (Mod.isCheat() ? "cheat" : "legit") + " jar");

            UiCase.run(ctx, "340-ui-mainmenu", MiscCases::mainMenu);
            UiCase.run(ctx, "330-ui-migrate", MiscCases::migrate);
            UiCase.run(ctx, "310-ui-config-file-roundtrip", ConfigSweep::fileLevel);
            UiCase.run(ctx, "311-ui-config-setters", ConfigSweep::setterLevel);
            UiCase.run(ctx, "320-ui-profiles", MiscCases::profiles);
            UiCase.run(ctx, "380-ui-autoscale", AutoScaleCases::autoScale);

            if (anyWorld) {
                long worldStart = System.nanoTime();
                try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
                    ctx.waitTicks(40);
                    SafeWorld.apply(ctx);
                    double worldSeconds = (System.nanoTime() - worldStart) / 1e9;
                    long smokeStart = System.nanoTime();
                    UiCase.run(ctx, "301-ui-smoke-tabs", c -> TabSweep.tabs(c, deny));
                    UiCase.run(ctx, "302-ui-smoke-search", TabSweep::search);
                    UiCase.run(ctx, "303-ui-smoke-toggles", c -> TabSweep.toggles(c, deny));
                    UiCase.run(ctx, "304-ui-smoke-hud-editor", ScreenSweep::hudEditor);
                    UiCase.run(ctx, "305-ui-smoke-screens", c -> ScreenSweep.allScreens(c, deny));
                    UiCase.run(ctx, "306-ui-smoke-commands", c -> CommandSweep.commands(c, deny));
                    double smokeSeconds = (System.nanoTime() - smokeStart) / 1e9;
                    System.out.println(String.format(java.util.Locale.ROOT,
                            "[ui] smoke group (301-306) took %.1f s after a %.1f s world start", smokeSeconds,
                            worldSeconds));
                    UiCase.run(ctx, "350-ui-termism", MiscCases::termism);
                    UiCase.run(ctx, "360-ui-visuals", MiscCases::visuals);
                    UiCase.run(ctx, "365-ui-overlay-draws", MiscCases::overlayDraws);
                    UiCase.run(ctx, "307-ui-profit", ProfitCases::profit);
                    UiCase.run(ctx, "385-ui-interactive-map", InteractiveMapCases::run);
                    UiCase.run(ctx, "386-ui-sliders", c -> SliderCases.run(c, deny));
                    UiCase.run(ctx, "387-ui-ap3-edit", Ap3EditCases::run);
                    UiCase.run(ctx, "388-ui-breaker-display", BreakerDisplayCases::run);
                    UiCase.run(ctx, "389-ui-crosshair", CrosshairCases::run);
                    UiCase.run(ctx, "390-ui-wither-doors-fill", WitherDoorFillCases::run);
                    UiCase.run(ctx, "391-ui-cheat-tabs", CheatTabsCases::cheatTabs);
                    UiCase.run(ctx, "392-ui-blood-camp", CheatTabsCases::bloodCamp);
                    UiCase.run(ctx, "393-ui-stat-bars", c -> StatBarsCases.run(c, deny));
                    UiCase.run(ctx, "394-ui-hud-boxes", HudBoxCases::run);
                    UiCase.run(ctx, "399-ui-hud-editor-resize", HudEditorCases::resize);
                    UiCase.run(ctx, "399-ui-hud-editor-snap", HudEditorCases::snap);
                    UiCase.run(ctx, "399-ui-stat-bars-vitality-xp", HudEditorCases::vitalityXp);
                    UiCase.run(ctx, "407-ui-stat-bars-layout", StatBarsLayoutCases::run);
                    UiCase.run(ctx, "395-ui-map-heads", MapHeadCases::run);
                    UiCase.run(ctx, "401-ui-scoreboard-editor", ScoreboardEditorCases::run);
                    UiCase.run(ctx, "411-ui-mining-shelved", MiningShelvedCases::run);
                }
            }
            UiCase.run(ctx, DENY_CASE, c -> MiscCases.deny(c, watch, suiteMark));
        }
    }

    private static boolean any(String[] names) {
        for (String n : names) {
            if (UiCase.selected(n)) {
                return true;
            }
        }
        return false;
    }
}

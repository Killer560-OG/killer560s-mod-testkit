package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.VideoSim;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.mod.Quiet;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 1900-video-general: the footage for killer560's "General mod overview" YouTube pilot, recorded only in this client
 * (title screen, the Dungeon Sim's local world, singleplayer) - never on a server. Each case writes one clip of
 * 1920x1080 frames at 60 fps (see {@link VideoRecorder}) to {@code -PvideoOut=<dir>} (default
 * {@code <report>/video-general}); killer560s-mod-logs/video-general/build-video.py cuts them to the voiceover.
 *
 * <ul>
 *   <li>1901-video-title: the themed title screen (Amber), the cursor drifting down the button column.</li>
 *   <li>1902-video-sim-menu: Dungeon Sim on the title screen, then each page of the sim menu.</li>
 *   <li>1903-video-menu: /killer560 typed in chat, Home, the sidebar, cards, a switch, a drawer, the grid scrolled to
 *       the red Cheat block, other categories.</li>
 *   <li>1904-video-search: Ctrl F, typing, a result opened.</li>
 *   <li>1905-video-themes: the Menu Theme card and its theme row stepped through every preset.</li>
 *   <li>1906-video-hud-editor: Edit HUD from Home, an element dragged and resized.</li>
 *   <li>1907-video-profiles: the Profiles page.</li>
 *   <li>1908-video-sim-run: a generated floor, the camera turning slowly in three rooms with the Dungeon Map HUD and
 *       Secret Waypoints on.</li>
 *   <li>1909-video-sim-solver: Creeper Beams loaded by itself with its solver's lines.</li>
 * </ul>
 *
 * Only runs when named ({@code -Pscenario=1900-video}; a single clip by its own name). It asserts that each clip wrote
 * frames and that the thing it films happened (the screen it clicked opened, the theme it stepped changed, the box it
 * dragged moved), so a clip of nothing fails instead of reading as footage.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class VideoGeneralSuite implements FabricClientGameTest {

    static final String[] TITLE = {"1901-video-title", "1902-video-sim-menu"};
    static final String[] FLOOR = {"1903-video-menu", "1904-video-search", "1905-video-themes", "1906-video-hud-editor",
            "1907-video-profiles", "1908-video-sim-run"};
    static final String SOLVER = "1909-video-sim-solver";

    private static boolean named(String n) {
        String filter = System.getProperty("testkit.scenario", "");
        return !filter.isBlank() && filter.contains("video") && UiCase.selected(n);
    }

    private static boolean anyNamed(String... names) {
        for (String n : names) {
            if (named(n)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (dev.testkit.harness.ScenarioList.active() || dev.testkit.harness.ModGate.missing() != null) {
            for (String n : TITLE) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : FLOOR) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            dev.testkit.gametest.Scenario.skip(SOLVER);
            return;
        }
        if (!anyNamed(TITLE) && !anyNamed(FLOOR) && !named(SOLVER)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.computeOnClient(mc -> {
            if (Quiet.enabled()) {
                Quiet.apply();
            }
            Mod.staticCall("util.SkyblockGate", "setEnabled", false);
            mc.options.bobView().set(false);
            mc.options.guiScale().set(3);
            Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", Mod.enumValue("gui.MenuTheme", "AMBER"));
            MenuAnimSuite.mode("SMOOTH");
            return null;
        });
        ctx.getInput().resizeWindow(1920, 1080);
        ctx.waitTicks(3);
        ctx.runOnClient(mc -> mc.resizeGui());
        ctx.waitTicks(3);

        if (anyNamed(TITLE)) {
            if (named(TITLE[0])) {
                UiCase.run(ctx, TITLE[0], VideoGeneralSuite::title);
            }
            if (named(TITLE[1])) {
                UiCase.run(ctx, TITLE[1], VideoGeneralSuite::simMenu);
            }
            ctx.runOnClient(mc -> McCompat.setScreen(mc, new TitleScreen()));
            ctx.waitTicks(5);
        }
        boolean sim = (anyNamed(FLOOR) || named(SOLVER)) && VideoSim.prepare(ctx);
        if (anyNamed(FLOOR) && sim) {
            ctx.runOnClient(mc -> {
                Mod.set("livemap.LiveMapConfig", "setEnabled", true);
                Object wp = Mod.cfg("secretwaypoints.SecretWaypointsConfig");
                Mod.call(wp, "setEnabled", true);
                Mod.call(wp, "setShowCrypts", true);
            });
            VideoSim.floor(ctx, "F5", 2, 5);
            try {
                if (named(FLOOR[0])) {
                    UiCase.run(ctx, FLOOR[0], VideoGeneralSuite::menu);
                }
                if (named(FLOOR[1])) {
                    UiCase.run(ctx, FLOOR[1], VideoGeneralSuite::search);
                }
                if (named(FLOOR[2])) {
                    UiCase.run(ctx, FLOOR[2], VideoGeneralSuite::themes);
                }
                if (named(FLOOR[3])) {
                    UiCase.run(ctx, FLOOR[3], VideoGeneralSuite::hudEditor);
                }
                if (named(FLOOR[4])) {
                    UiCase.run(ctx, FLOOR[4], VideoGeneralSuite::profiles);
                }
                if (named(FLOOR[5])) {
                    UiCase.run(ctx, FLOOR[5], VideoGeneralSuite::simRun);
                }
            } finally {
                VideoSim.leave(ctx);
            }
        }
        if (named(SOLVER) && sim) {
            ctx.runOnClient(mc -> Mod.set("puzzlesolvers.BeamsSolverConfig", "setEnabled", true));
            VideoSim.singleRoom(ctx, "Creeper Beams");
            try {
                UiCase.run(ctx, SOLVER, VideoGeneralSuite::solver);
            } finally {
                VideoSim.leave(ctx);
            }
        }
        ctx.runOnClient(mc -> {
            Mod.staticCall("gui.MenuAnim", "setClockForTest", -1.0);
            Mod.staticCall("mainmenu.MainMenuScenes", "setClockForTest", -1.0);
        });
    }

    // =================================================================================================================
    // helpers
    // =================================================================================================================

    private static Screen screen(UiCase c) {
        return c.onClient(McCompat::screen);
    }

    /** The visible widget of the open screen whose label starts with {@code prefix}: {x, y, w, h} in its layout units. */
    private static int[] widget(UiCase c, String prefix) {
        return c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            for (var child : s.children()) {
                if (child instanceof AbstractWidget w && w.visible
                        && w.getMessage().getString().replaceAll("§.", "").strip().startsWith(prefix)) {
                    return new int[]{w.getX(), w.getY(), w.getWidth(), w.getHeight()};
                }
            }
            return null;
        });
    }

    private static double[] centre(UiCase c, int[] box) {
        Screen s = screen(c);
        return VideoRecorder.menuToWindow(c, s, box[0] + box[2] / 2.0, box[1] + box[3] / 2.0);
    }

    /** Glides to a point a little off the centre of a box (a person rarely hits dead centre) in menu units. */
    private static void glideTo(VideoRecorder r, int[] box, double fx, double fy, double ms) throws Exception {
        Screen s = screen(r.c);
        double[] w = VideoRecorder.menuToWindow(r.c, s, box[0] + box[2] * fx, box[1] + box[3] * fy);
        r.glide(w[0], w[1], ms);
    }

    @SuppressWarnings("unchecked")
    private static int[] sideBox(UiCase c, Screen s, String name) {
        return c.onClient(mc -> {
            List<String> texts = (List<String>) Mod.call(s, "sideTexts");
            List<Object> side = (List<Object>) Mod.field(s, "side");
            for (int i = 0; i < texts.size(); i++) {
                if (texts.get(i).startsWith(name)) {
                    Object e = side.get(i);
                    return new int[]{(Integer) Mod.call(e, "x"), (Integer) Mod.call(e, "y"), (Integer) Mod.call(e, "w"),
                            (Integer) Mod.call(e, "h")};
                }
            }
            throw new AssertionError("no sidebar entry " + name + " in " + texts);
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Object> cards(UiCase c, Screen s) {
        return c.onClient(mc -> new ArrayList<>((List<Object>) Mod.call(s, "cards")));
    }

    private static String category(UiCase c, Screen s) {
        return c.onClient(mc -> {
            List<?> tabs = (List<?>) Mod.field(Mod.ROOT + "gui.ModScreen", "tabs");
            int i = (Integer) Mod.field(Mod.ROOT + "gui.ModScreen", "selectedTab");
            return String.valueOf(Mod.field(tabs.get(i), "name"));
        });
    }

    /** Opens the mod menu the way a player does: T, "/killer560", Enter - typed on camera. */
    private static Screen typeCommand(VideoRecorder r) throws Exception {
        r.hideCursor();
        r.clocks();
        r.c.ctx().getInput().pressKey(InputConstants.KEY_T);
        r.hold(300);
        for (char ch : "/killer560".toCharArray()) {
            r.c.ctx().getInput().typeChar(ch);
            r.hold(70);
        }
        r.hold(250);
        r.clocks();
        r.c.ctx().getInput().pressKey(InputConstants.KEY_RETURN);
        for (int i = 0; i < 20; i++) {
            r.frame();
            Screen s = screen(r.c);
            if (s != null && s.getClass().getSimpleName().equals("ModScreen")) {
                return s;
            }
        }
        throw new AssertionError("/killer560 typed in chat did not open the mod menu (screen: " + screen(r.c) + ")");
    }

    /** A fresh menu on Home, opened off camera (the clip starts on it). */
    private static Screen openMenuQuiet(UiCase c, String category) {
        return c.onClient(mc -> {
            try {
                Screen m = MenuACases.open(mc);
                Mod.call(m, "closeDrawer");
                Mod.setField(Mod.ROOT + "gui.ModScreen", "gridScroll", 0);
                if (category != null) {
                    Mod.call(m, "selectCategory", category);
                }
                return m;
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    private static void clickSidebar(VideoRecorder r, Screen s, String name, double ms) throws Exception {
        glideTo(r, sideBox(r.c, s, name), 0.35, 0.5, ms);
        r.click();
    }

    // =================================================================================================================
    // 1901 / 1902 title screen and sim menu
    // =================================================================================================================

    static void title(UiCase c) throws Exception {
        c.onClient(mc -> {
            McCompat.setScreen(mc, new TitleScreen());
            return null;
        });
        c.ticks(5);
        Thread.sleep(1500); // the vanilla title fade-in runs on the wall clock
        c.ticks(5);
        VideoRecorder r = new VideoRecorder(c, "title").titleClock();
        try {
            r.hideCursor();
            r.hold(1500);
            r.cursorAt(1700, 900);
            r.hold(200);
            for (String b : new String[]{"Singleplayer", "Multiplayer", "Dungeon Sim"}) {
                int[] box = widget(c, b);
                c.check(box != null, "no " + b + " button on the title screen");
                if (box != null) {
                    glideTo(r, box, 0.42, 0.55, 650);
                    r.hold(700);
                }
            }
            r.hold(1200);
        } finally {
            r.close();
        }
        c.check(r.frames() >= 300, "the title clip has only " + r.frames() + " frames");
    }

    static void simMenu(UiCase c) throws Exception {
        if (!(screen(c) instanceof TitleScreen)) {
            c.onClient(mc -> {
                McCompat.setScreen(mc, new TitleScreen());
                return null;
            });
            c.ticks(5);
            Thread.sleep(1500);
            c.ticks(5);
        }
        VideoRecorder r = new VideoRecorder(c, "sim-menu").titleClock();
        try {
            int[] sim = widget(c, "Dungeon Sim");
            c.check(sim != null, "no Dungeon Sim button on the title screen");
            double[] at = centre(c, sim);
            r.cursorAt(at[0] + 40, at[1] + 6);
            r.hold(400);
            glideTo(r, sim, 0.45, 0.5, 300);
            r.click();
            for (int i = 0; i < 30 && !screen(c).getClass().getSimpleName().equals("SimMenuScreen"); i++) {
                r.frame();
            }
            c.check(screen(c).getClass().getSimpleName().equals("SimMenuScreen"),
                    "the Dungeon Sim button opened " + screen(c));
            r.mark("sim-menu-open");
            r.hold(1500);
            // the pages first (Create a New Map opens its own screen, so it goes last)
            for (String page : new String[]{"Load a Room", "Route Practice", "Previous Runs", "Create a New Map"}) {
                int[] box = widget(c, page);
                c.check(box != null, "no '" + page + "' page button");
                if (box == null) {
                    continue;
                }
                r.mark("page-" + page);
                glideTo(r, box, 0.4, 0.5, 550);
                r.click();
                r.hold(500);
                for (int i = 0; i < 60 && widget(c, "loading") != null; i++) {
                    r.frame();
                }
                switch (page) {
                    case "Load a Room" -> {
                        // drift over the list and scroll it a little
                        r.glide(r.cx + 520, r.cy + 160, 600);
                        for (int i = 0; i < 5; i++) {
                            r.wheel(-1);
                            r.hold(260);
                        }
                        r.hold(700);
                    }
                    case "Previous Runs" -> r.hold(700);
                    case "Create a New Map" -> {
                        r.hold(900);
                        int[] fill = widget(c, "Fill");
                        if (fill != null) {
                            glideTo(r, fill, 0.45, 0.5, 650);
                            r.click();
                            r.hold(500);
                        }
                        double[] grid = VideoRecorder.menuToWindow(c, screen(c), screen(c).width * 0.25, screen(c).height * 0.45);
                        r.glide(grid[0], grid[1], 800);
                        r.hold(1800);
                    }
                    default -> {
                        r.glide(r.cx + 300, r.cy + 60, 700);
                        r.hold(1300);
                    }
                }
            }
            r.hold(500);
        } finally {
            r.close();
            c.onClient(mc -> {
                McCompat.setScreen(mc, new TitleScreen());
                return null;
            });
        }
    }

    // =================================================================================================================
    // 1903 menu tour
    // =================================================================================================================

    static void menu(UiCase c) throws Exception {
        c.onClient(mc -> {
            mc.player.setYRot(mc.player.getYRot());
            return null;
        });
        VideoRecorder r = new VideoRecorder(c, "menu");
        try {
            r.hold(500);
            Screen s = typeCommand(r);
            r.hideCursor();
            r.hold(300);
            double[] mid = VideoRecorder.menuToWindow(c, s, s.width * 0.55, s.height * 0.6);
            r.cursorAt(mid[0], mid[1]);
            r.mark("home");
            r.hold(1300); // Home
            r.mark("dungeons");
            clickSidebar(r, s, "Dungeons", 700);
            r.hold(900);
            c.check(category(c, s).contains("Dungeons") || cards(c, s).size() > 4, "Dungeons did not open");

            // hover across a few cards
            List<Object> cards = cards(c, s);
            for (int i = 0; i < Math.min(4, cards.size()); i++) {
                int[][] b = MenuAnimSuite.bounds(c, s, cards.get(i));
                glideTo(r, b[0], 0.38, 0.5, 420);
                r.hold(260);
            }
            // a switch, on and back off
            Object sw = null;
            for (Object t : cards) {
                if (MenuAnimSuite.cardState(t) != null) {
                    sw = t;
                    break;
                }
            }
            if (sw != null) {
                Boolean before = MenuAnimSuite.cardState(sw);
                int[][] b = MenuAnimSuite.bounds(c, s, sw);
                glideTo(r, b[1], 0.5, 0.5, 450);
                r.click();
                r.hold(500);
                Boolean after = MenuAnimSuite.cardState(sw);
                c.check(!java.util.Objects.equals(before, after), "the card switch did not change (" + before + ")");
                r.click();
                r.hold(400);
            }
            r.mark("drawer");
            // a drawer: open, scroll it, close
            Object card = cards.get(Math.min(1, cards.size() - 1));
            int[][] cb = MenuAnimSuite.bounds(c, s, card);
            glideTo(r, cb[0], 0.33, 0.45, 500);
            r.click();
            r.hold(800);
            int[] drawer = c.onClient(mc -> new int[]{(Integer) Mod.field(s, "drawerX"), (Integer) Mod.field(s, "drawerY"),
                    (Integer) Mod.field(s, "drawerW"), (Integer) Mod.field(s, "drawerH")});
            c.check(drawer[2] > 0, "the card did not open a drawer");
            glideTo(r, drawer, 0.55, 0.55, 450);
            for (int i = 0; i < 4; i++) {
                r.wheel(-1);
                r.hold(300);
            }
            r.hold(600);
            for (int i = 0; i < 4; i++) {
                r.wheel(1);
                r.hold(200);
            }
            r.hold(300);
            glideTo(r, new int[]{drawer[0] + drawer[2] - 22, drawer[1] + 2, 20, 20}, 0.5, 0.5, 500);
            r.click();
            r.hold(600);

            // the grid down to the red Cheat block at the bottom, and back
            int[] grid = c.onClient(mc -> new int[]{(Integer) Mod.field(s, "gridX"), (Integer) Mod.field(s, "gridY"),
                    (Integer) Mod.field(s, "gridW"), (Integer) Mod.field(s, "gridH")});
            r.mark("cheat-scroll");
            glideTo(r, grid, 0.5, 0.6, 450);
            int before = c.onClient(mc -> ((Number) Mod.field(s, "gridScroll")).intValue());
            for (int i = 0; i < 14; i++) {
                r.wheel(-1);
                r.hold(240);
            }
            r.hold(2200);
            int after = c.onClient(mc -> ((Number) Mod.field(s, "gridScroll")).intValue());
            c.note("Dungeons grid scrolled " + before + " -> " + after);
            c.check(after != before, "the Dungeons grid did not scroll");
            for (int i = 0; i < 14; i++) {
                r.wheel(1);
                r.hold(90);
            }
            r.hold(400);
            r.mark("categories");
            for (String cat : new String[]{"HUD", "Chat", "Items"}) {
                clickSidebar(r, s, cat, 500);
                r.hold(900);
            }
            r.hold(400);
        } finally {
            r.close();
        }
    }

    // =================================================================================================================
    // 1904 search
    // =================================================================================================================

    static void search(UiCase c) throws Exception {
        Screen s = openMenuQuiet(c, "Dungeons");
        c.ticks(3);
        VideoRecorder r = new VideoRecorder(c, "search");
        try {
            double[] mid = VideoRecorder.menuToWindow(c, s, s.width * 0.5, s.height * 0.5);
            r.cursorAt(mid[0], mid[1]);
            r.hold(700);
            EditBox box = c.onClient(mc -> (EditBox) Mod.field(s, "searchField"));
            int[] sb = c.onClient(mc -> new int[]{box.getX(), box.getY(), box.getWidth(), box.getHeight()});
            glideTo(r, sb, 0.3, 0.5, 600);
            r.click();
            r.hold(300);
            for (char ch : "secret".toCharArray()) {
                r.clocks();
                c.ctx().getInput().typeChar(ch);
                r.hold(110);
            }
            r.hold(1600);
            int hits = c.onClient(mc -> MenuACases.results(s).size());
            c.note("search 'secret': " + hits + " results");
            c.check(hits > 0, "typing 'secret' found nothing");
            // down twice, Enter: the drawer opens scrolled to it
            for (int i = 0; i < 2; i++) {
                r.clocks();
                c.ctx().getInput().pressKey(InputConstants.KEY_DOWN);
                r.hold(350);
            }
            r.clocks();
            c.ctx().getInput().pressKey(InputConstants.KEY_RETURN);
            r.hold(2200);
        } finally {
            r.close();
        }
    }

    // =================================================================================================================
    // 1905 themes
    // =================================================================================================================

    static void themes(UiCase c) throws Exception {
        Object tab = c.onClient(mc -> MenuACases.leaf("MenuThemeTab"));
        Screen s = openMenuQuiet(c, null);
        // which sidebar category holds the Menu Theme card (found off camera)
        String cat = c.onClient(mc -> {
            @SuppressWarnings("unchecked")
            List<String> texts = (List<String>) Mod.call(s, "sideTexts");
            for (String t : texts) {
                if ((Boolean) Mod.call(s, "selectCategory", t) && ((List<?>) Mod.call(s, "cards")).contains(tab)) {
                    return t;
                }
            }
            return null;
        });
        c.check(cat != null, "no sidebar category lists the Menu Theme card");
        c.onClient(mc -> {
            Mod.call(s, "selectCategory", "Dungeons");
            return null;
        });
        c.ticks(3);
        VideoRecorder r = new VideoRecorder(c, "themes");
        try {
            double[] mid = VideoRecorder.menuToWindow(c, s, s.width * 0.6, s.height * 0.45);
            r.cursorAt(mid[0], mid[1]);
            r.hold(500);
            clickSidebar(r, s, cat, 600);
            r.hold(700);
            c.onClient(mc -> {
                Mod.call(s, "revealCard", tab);
                return null;
            });
            r.hold(400);
            int[][] b = MenuAnimSuite.bounds(c, s, tab);
            glideTo(r, b[0], 0.33, 0.45, 550);
            r.click();
            r.hold(800);
            String[] want = {"DARK", "LIGHT", "OCEAN", "GREEN", "AMBER"};
            for (String w : want) {
                AbstractWidget row = c.onClient(mc -> {
                    for (AbstractWidget x : MenuACases.paneRows(s)) {
                        if (x.getMessage().getString().startsWith("Menu Theme:")) {
                            return x;
                        }
                    }
                    return null;
                });
                c.check(row != null, "no Menu Theme row in the drawer");
                if (row == null) {
                    break;
                }
                int[] rb = c.onClient(mc -> {
                    try {
                        return (int[]) Mod.call(row, "controlBounds");
                    } catch (Throwable t) {
                        return new int[]{row.getX(), row.getY(), row.getWidth(), row.getHeight()};
                    }
                });
                glideTo(r, rb, 0.5, 0.5, w.equals("DARK") ? 550 : 180);
                if (w.equals("AMBER")) {
                    // back to the default by stepping back once from Green and again... the wrap is through Custom,
                    // so the last step is Amber set by its own right click count: step until Amber.
                    for (int i = 0; i < 3 && !MenuACases.themeName().equals("AMBER"); i++) {
                        r.click();
                        r.hold(250);
                    }
                    r.hold(1200);
                    break;
                }
                r.click();
                r.hold(1350);
                c.check(MenuACases.themeName().equals(w), "a click on Menu Theme gave " + MenuACases.themeName()
                        + ", wanted " + w);
            }
        } finally {
            r.close();
            c.onClient(mc -> {
                Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", Mod.enumValue("gui.MenuTheme", "AMBER"));
                Mod.call(Mod.cfg("gui.MenuConfig"), "save");
                return null;
            });
        }
    }

    // =================================================================================================================
    // 1906 HUD editor
    // =================================================================================================================

    @SuppressWarnings("unchecked")
    static void hudEditor(UiCase c) throws Exception {
        c.onClient(mc -> {
            Mod.call(Mod.cfg("hud.HudConfig"), "setEditorShowAll", true);
            return null;
        });
        Screen s = openMenuQuiet(c, null);
        c.onClient(mc -> {
            Mod.call(s, "selectCategory", "Home");
            return null;
        });
        c.ticks(3);
        VideoRecorder r = new VideoRecorder(c, "hud-editor");
        try {
            double[] mid = VideoRecorder.menuToWindow(c, s, s.width * 0.5, s.height * 0.65);
            r.cursorAt(mid[0], mid[1]);
            r.hold(600);
            int[] edit = c.onClient(mc -> (int[]) Mod.call(s, "homeButtonBounds", "Edit HUD"));
            c.check(edit != null, "no Edit HUD button on Home");
            glideTo(r, edit, 0.45, 0.5, 650);
            r.click();
            for (int i = 0; i < 20 && !screen(c).getClass().getSimpleName().equals("HudEditorScreen"); i++) {
                r.frame();
            }
            Screen ed = screen(c);
            c.check(ed.getClass().getSimpleName().equals("HudEditorScreen"), "Edit HUD opened " + ed);
            r.hold(700);
            // the biggest element on screen
            String[] pick = c.onClient(mc -> {
                Map<String, int[]> pos = (Map<String, int[]>) Mod.field(ed, "livePositions");
                Map<String, Float> sc = (Map<String, Float>) Mod.field(ed, "liveScales");
                float g = ((Number) Mod.call(Mod.cfg("hud.HudConfig"), "getEffectiveGlobalScale")).floatValue();
                String best = null;
                int bestArea = 0;
                for (var e : pos.entrySet()) {
                    Object el = Mod.staticCall("hud.HudElementRegistry", "byId", e.getKey());
                    if (el == null) {
                        continue;
                    }
                    int w = Math.round((Integer) Mod.call(el, "width") * sc.get(e.getKey()) * g);
                    int h = Math.round((Integer) Mod.call(el, "height") * sc.get(e.getKey()) * g);
                    int area = w * h;
                    if (area > bestArea && w < ed.width / 2 && h < ed.height / 2) {
                        bestArea = area;
                        best = e.getKey();
                    }
                }
                return new String[]{best, String.valueOf(pos.keySet())};
            });
            c.note("editor elements " + pick[1] + "; dragging " + pick[0]);
            c.check(pick[0] != null, "the HUD editor shows no element to drag");
            if (pick[0] == null) {
                return;
            }
            String id = pick[0];
            int[] box = hudBox(c, ed, id);
            // grab it a little left of centre and carry it to the right side of the screen
            double[] g0 = c.onClient(mc -> VideoRecorder.guiToWindow(mc, box[0] + (box[2] - box[0]) * 0.45,
                    box[1] + (box[3] - box[1]) * 0.5));
            r.glide(g0[0], g0[1], 700);
            r.hold(250);
            r.press();
            r.hold(120);
            double[] to = c.onClient(mc -> VideoRecorder.guiToWindow(mc, ed.width * 0.70, ed.height * 0.30));
            r.glide(to[0], to[1], 1300);
            r.hold(300);
            r.release();
            r.hold(500);
            int[] moved = hudBox(c, ed, id);
            c.check(moved[0] != box[0] || moved[1] != box[1], "the dragged element did not move");
            // scroll over it: bigger, then a notch back
            int[] mb = moved;
            double[] over = c.onClient(mc -> VideoRecorder.guiToWindow(mc, (mb[0] + mb[2]) / 2.0, (mb[1] + mb[3]) / 2.0));
            r.glide(over[0], over[1], 400);
            for (int i = 0; i < 4; i++) {
                r.wheel(1);
                r.hold(260);
            }
            r.hold(500);
            r.wheel(-1);
            r.hold(500);
            int[] scaled = hudBox(c, ed, id);
            c.check(scaled[2] - scaled[0] != moved[2] - moved[0], "scrolling over the element did not resize it");
            // drag its bottom-right corner outwards a little (shape kept)
            double[] corner = c.onClient(mc -> VideoRecorder.guiToWindow(mc, scaled[2] - 1, scaled[3] - 1));
            r.glide(corner[0], corner[1], 600);
            r.hold(250);
            r.press();
            r.hold(100);
            r.glide(corner[0] + 70, corner[1] + 45, 800);
            r.hold(200);
            r.release();
            r.hold(1200);
            // and back where it came from, so the clip ends on the layout it started with
            int[] fin = hudBox(c, ed, id);
            double[] grab = c.onClient(mc -> VideoRecorder.guiToWindow(mc, fin[0] + (fin[2] - fin[0]) * 0.45,
                    fin[1] + (fin[3] - fin[1]) * 0.5));
            r.glide(grab[0], grab[1], 500);
            r.hold(800);
        } finally {
            r.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static int[] hudBox(UiCase c, Screen ed, String id) {
        return c.onClient(mc -> {
            int[] p = ((Map<String, int[]>) Mod.field(ed, "livePositions")).get(id);
            float own = ((Map<String, Float>) Mod.field(ed, "liveScales")).get(id);
            float g = ((Number) Mod.call(Mod.cfg("hud.HudConfig"), "getEffectiveGlobalScale")).floatValue();
            Object e = Mod.staticCall("hud.HudElementRegistry", "byId", id);
            int w = Math.round((Integer) Mod.call(e, "width") * own * g);
            int h = Math.round((Integer) Mod.call(e, "height") * own * g);
            return new int[]{p[0], p[1], p[0] + w, p[1] + h};
        });
    }

    // =================================================================================================================
    // 1907 profiles
    // =================================================================================================================

    static void profiles(UiCase c) throws Exception {
        Screen s = openMenuQuiet(c, "Home");
        c.ticks(3);
        VideoRecorder r = new VideoRecorder(c, "profiles");
        try {
            double[] mid = VideoRecorder.menuToWindow(c, s, s.width * 0.5, s.height * 0.5);
            r.cursorAt(mid[0], mid[1]);
            r.hold(500);
            clickSidebar(r, s, "Profiles", 650);
            r.hold(1000);
            c.check(category(c, s).contains("Profiles"), "Profiles did not open: " + category(c, s));
            // drift down the page's buttons
            for (String b : new String[]{"Save", "New Blank Profile", "Export", "Open"}) {
                int[] box = widget(c, b);
                if (box != null) {
                    glideTo(r, box, 0.4, 0.5, 550);
                    r.hold(550);
                }
            }
            double[] low = VideoRecorder.menuToWindow(c, s, s.width * 0.55, s.height * 0.7);
            r.glide(low[0], low[1], 500);
            for (int i = 0; i < 3; i++) {
                r.wheel(-1);
                r.hold(300);
            }
            r.hold(1200);
        } finally {
            r.close();
        }
    }

    // =================================================================================================================
    // 1908 / 1909 the sim
    // =================================================================================================================

    /** A slow turn standing in a room: {@code turn} degrees of yaw and a short glide forward over {@code ms}. */
    private static void pan(VideoRecorder r, Vec3 eyeFeet, float yaw0, float turn, float pitch0, float pitch1,
                            double forward, double ms) throws Exception {
        int n = (int) Math.round(ms / VideoRecorder.FRAME_MS);
        for (int i = 0; i < n; i++) {
            double p = i / (double) Math.max(1, n - 1);
            double e = p < 0.5 ? 2 * p * p : 1 - Math.pow(-2 * p + 2, 2) / 2;
            float yaw = (float) (yaw0 + turn * e);
            float pitch = (float) (pitch0 + (pitch1 - pitch0) * e);
            double rad = Math.toRadians(yaw0 + turn * 0.5);
            double fx = -Math.sin(rad) * forward * e;
            double fz = Math.cos(rad) * forward * e;
            r.c.onClient(mc -> {
                VideoRecorder.pose(mc, eyeFeet.x + fx, eyeFeet.y, eyeFeet.z + fz, yaw, pitch);
                return null;
            });
            r.frame();
        }
    }

    private static float yawTowards(Vec3 from, Vec3 to) {
        return (float) Math.toDegrees(Math.atan2(-(to.x - from.x), to.z - from.z));
    }

    static void simRun(UiCase c) throws Exception {
        List<VideoSim.Room> rooms = VideoSim.rooms(c.ctx());
        c.note("rooms: " + rooms.stream().map(x -> x.name() + "/" + x.type() + "/" + x.unfound()).toList());
        List<VideoSim.Room> pick = new ArrayList<>(rooms.stream()
                .filter(x -> x.unfound() >= 3 && !x.type().toLowerCase(Locale.ROOT).contains("puzzle"))
                .sorted(Comparator.comparingInt(VideoSim.Room::unfound).reversed()).limit(3).toList());
        c.check(!pick.isEmpty(), "no room with secrets to film: " + rooms.size() + " rooms");
        if (pick.isEmpty()) {
            return;
        }
        VideoRecorder r = new VideoRecorder(c, "sim-run");
        try {
            r.hideCursor();
            for (int k = 0; k < pick.size(); k++) {
                VideoSim.Room room = pick.get(k);
                Vec3 at = room.stand();
                // stand back from the middle of the room, facing across it
                float yaw = yawTowards(at, room.centre()) + (k % 2 == 0 ? -70 : 70);
                c.onClient(mc -> {
                    VideoRecorder.pose(mc, at.x, at.y, at.z, yaw, 8f);
                    return null;
                });
                c.ticks(60); // off camera: chunks, the map's room, waypoints
                String inRoom = c.onClient(mc -> String.valueOf(Mod.staticCall("livemap.LiveMapFeature", "currentRoomType")));
                r.mark("room-" + (k + 1));
                c.note("room " + (k + 1) + ": " + room.name() + " (" + room.unfound() + " secrets), map says " + inRoom);
                pan(r, at, yaw, k % 2 == 0 ? 100f : -100f, 10f, 4f, 2.0, 5200);
            }
        } finally {
            r.close();
        }
    }

    static void solver(UiCase c) throws Exception {
        for (int i = 0; i < 600 && !c.onClient(mc -> mc.player != null && mc.player.onGround()); i++) {
            c.ticks(1);
        }
        c.ticks(40);
        Vec3 at = c.onClient(mc -> mc.player.position());
        Object pairs = c.onClient(mc -> Mod.staticCall("puzzlesolvers.BeamsSolverFeature", "getActivePairs"));
        c.note("Creeper Beams pairs drawn: " + pairs);
        c.check(pairs != null && !(pairs instanceof java.util.Collection<?> col && col.isEmpty()),
                "the Creeper Beams solver has no pairs to draw");
        float yaw0 = c.onClient(mc -> mc.player.getYRot());
        VideoRecorder r = new VideoRecorder(c, "sim-solver");
        try {
            r.hideCursor();
            pan(r, new Vec3(at.x, at.y + 1.5, at.z), yaw0 - 60, 160f, 18f, 8f, 1.5, 9000);
        } finally {
            r.close();
        }
    }
}

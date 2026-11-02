package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.compat.McKeys;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;

import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 1101-ui-intro-*: the first-launch tour (mod intro-1101, killer560 2026-11-01, "ONBOARDING / INTRO SPEC").
 * <ul>
 *   <li>first-show: run with {@code -PclientJvmArgs=-Dkiller560.test.intro=show}, on a wiped config or a seeded "updater"
 *       config (other settings plus the first version's {"done": true}), the tour opened ON ITS OWN, once, over the title
 *       screen, and is saved as seen the moment it opens; left mid-tour, a re-armed title screen never opens it again.
 *       Without that property it is SKIP (every other run must never see the tour).</li>
 *   <li>nav: scene 1 has Next and Skip Guide and NO Previous; a real click on Next moves to scene 2, Previous back to 1,
 *       the Right/Left arrow keys do the same; every scene is walked with real clicks and photographed (1920x1080 GUI 2)
 *       once its bubble has settled; the 3D guide was drawn on every scene (frame counter moved) and turns toward what he
 *       points at; Finish closes it back to the title screen and marks it done (the file says so).</li>
 *   <li>skip: Skip Guide closes it at once and marks it done; Escape asks first (Keep Going / Skip Tour), Escape again
 *       keeps going on the same scene, Skip Tour ends it; a skip from the theme scene puts the theme back.</li>
 *   <li>theme: on the theme scene the presets cycle on their own (at least two seen); a real click on Ocean applies it
 *       (MenuConfig theme OCEAN, saved in the file, the bubble's border pixels are Ocean's accent), and cycling stops.</li>
 *   <li>variant: the variant scene says CHEAT on a cheat jar and legit on a legit jar, and the ban-log scene is there only
 *       on a cheat jar.</li>
 *   <li>banlogs: Auto Report Bans stays OFF through Next without an answer and through No thanks; Yes turns it on (saved);
 *       No after that turns it back off. Legit: the scene is absent and the setting stays OFF after the whole tour.</li>
 *   <li>once: after finishing, the title screen never opens it again (launcher re-armed, 60 ticks on the title screen),
 *       done survives a re-read of the config, and Home's Replay Intro opens it again over the menu.</li>
 *   <li>sizes: at 854x480 GUI 1-2, 1280x720 GUI 2, 1920x1080 GUI 1-4 and 2560x1440 GUI 3-4 every scene's bubble and
 *       buttons are inside the window and clear of the guide.</li>
 *   <li>anim: with the menu clock driven (real durations), the bubble pops in (not settled 60 ms in, settled by 400 ms),
 *       types its text, and the guide walks between spots (his box is between the two spots half way through the
 *       walk); every 60/80 ms is photographed for a short clip.</li>
 * </ul>
 * TESTKIT_INTRO_SHOTS (env) copies each scene's screenshot there as {@code <mc>-<variant>-NN-<scene>.png}.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class IntroSuite implements FabricClientGameTest {

    static final String[] CASES = {"1101-ui-intro-first-show", "1101-ui-intro-nav", "1101-ui-intro-skip",
            "1101-ui-intro-theme", "1101-ui-intro-variant", "1101-ui-intro-banlogs", "1101-ui-intro-once",
            "1101-ui-intro-sizes", "1101-ui-intro-anim", "1101-ui-intro-autoupdate", "1101-ui-intro-mouth",
            "1101-ui-intro-facing", "1101-ui-intro-still", "1101-ui-intro-timebox"};
    static final String INTRO = "intro.IntroScreen";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (dev.testkit.harness.ScenarioList.active() || dev.testkit.harness.ModGate.missing() != null) {
            for (String n : CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            return;
        }
        boolean any = false;
        for (String n : CASES) {
            any |= UiCase.selected(n);
        }
        if (!any) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        UiCase.run(ctx, "1101-ui-intro-first-show", IntroSuite::firstShow);
        UiCase.run(ctx, "1101-ui-intro-nav", IntroSuite::nav);
        UiCase.run(ctx, "1101-ui-intro-skip", IntroSuite::skip);
        UiCase.run(ctx, "1101-ui-intro-theme", IntroSuite::theme);
        UiCase.run(ctx, "1101-ui-intro-variant", IntroSuite::variant);
        UiCase.run(ctx, "1101-ui-intro-banlogs", IntroSuite::banLogs);
        UiCase.run(ctx, "1101-ui-intro-once", IntroSuite::once);
        UiCase.run(ctx, "1101-ui-intro-sizes", IntroSuite::sizes);
        UiCase.run(ctx, "1101-ui-intro-anim", IntroSuite::anim);
        UiCase.run(ctx, "1101-ui-intro-autoupdate", IntroSuite::autoUpdateCase);
        UiCase.run(ctx, "1101-ui-intro-mouth", IntroSuite::mouthCase);
        UiCase.run(ctx, "1101-ui-intro-facing", IntroSuite::facingCase);
        UiCase.run(ctx, "1101-ui-intro-still", IntroSuite::stillCase);
        UiCase.run(ctx, "1101-ui-intro-timebox", IntroSuite::timeBoxCase);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    static boolean isIntro(Screen s) {
        return s != null && s.getClass().getName().equals(Mod.ROOT + INTRO);
    }

    static Screen intro(UiCase c) {
        return c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            return isIntro(s) ? s : null;
        });
    }

    /** Opens the tour over a new title screen, the done flag cleared, as Replay Intro does. */
    static Screen open(UiCase c) {
        c.onClient(mc -> {
            Object cfg = Mod.cfg("intro.IntroConfig");
            Mod.call(cfg, "setDone", false);
            Mod.call(cfg, "save");
            Mod.staticCall("intro.IntroLauncher", "replay", new TitleScreen());
            return null;
        });
        c.ticks(3);
        Screen s = intro(c);
        c.check(s != null, "the tour did not open");
        return s;
    }

    static String scene(UiCase c) {
        return c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            return isIntro(s) ? String.valueOf(Mod.call(s, "currentScene")) : "(no tour: "
                    + (s == null ? "null" : s.getClass().getSimpleName()) + ")";
        });
    }

    @SuppressWarnings("unchecked")
    static Map<String, AbstractWidget> buttons(UiCase c) {
        return c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            return isIntro(s) ? (Map<String, AbstractWidget>) Mod.call(s, "buttons") : Map.of();
        });
    }

    /** Waits (bounded) until the guide stopped walking and the bubble has popped in and typed. */
    static void settle(UiCase c) {
        for (int i = 0; i < 160; i++) {
            Boolean ok = c.onClient(mc -> {
                Screen s = McCompat.screen(mc);
                return !isIntro(s) || (Boolean) Mod.call(s, "settled");
            });
            if (ok) {
                c.ticks(2);
                return;
            }
            c.ticks(1);
        }
        c.problem("the tour never settled on " + scene(c));
    }

    /** A real left click on the tour's button {@code label}. */
    static void click(UiCase c, String label) {
        Map<String, AbstractWidget> b = buttons(c);
        AbstractWidget w = b.get(label);
        c.check(w != null, "no '" + label + "' button on " + scene(c) + " (buttons " + b.keySet() + ")");
        double[] at = c.onClient(mc -> toWindow(mc, McCompat.screen(mc), w.getX() + w.getWidth() / 2.0,
                w.getY() + w.getHeight() / 2.0));
        c.ctx().getInput().setCursorPos(at[0], at[1]);
        c.ticks(2);
        c.ctx().getInput().pressMouse(McKeys.mouse(0));
        c.ticks(4);
    }

    static double[] toWindow(Minecraft mc, Screen s, double sx, double sy) {
        float f = ((Number) ModUnderTest.staticCall("com.killer560.hub.hud.AutoScale", "appliedFactor",
                new Class<?>[]{Screen.class}, new Object[]{s})).floatValue();
        Window win = mc.getWindow();
        return new double[]{sx * f * win.getScreenWidth() / (double) win.getGuiScaledWidth(),
                sy * f * win.getScreenHeight() / (double) win.getGuiScaledHeight()};
    }

    static void key(UiCase c, int glfw) {
        c.ctx().getInput().pressKey(McKeys.nat(glfw));
        c.ticks(4);
    }

    static boolean done(UiCase c) {
        return c.onClient(mc -> (Boolean) Mod.call(Mod.cfg("intro.IntroConfig"), "isDone"));
    }

    static String configFile(UiCase c, String legacy) {
        Path p = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", legacy));
        try {
            return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            return "";
        }
    }

    static String text(UiCase c) {
        return c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            return isIntro(s) ? String.valueOf(Mod.call(s, "bubbleText")) : "";
        });
    }

    static Path shot(UiCase c, String label) {
        Path p = c.ctx().takeScreenshot(Report.fileName(c.name() + "-" + label));
        Report.screenshot(c.name(), p);
        String copy = System.getenv("TESTKIT_INTRO_SHOTS");
        if (copy != null && !copy.isBlank()) {
            try {
                String mcv = c.onClient(mc -> net.minecraft.SharedConstants.getCurrentVersion().name());
                String variant = Mod.isCheat() ? "cheat" : "legit";
                Files.createDirectories(Path.of(copy));
                Files.copy(p, Path.of(copy).resolve(mcv + "-" + variant + "-" + label + ".png"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                c.note("could not copy the shot: " + e);
            }
        }
        return p;
    }

    static String themeNow(UiCase c) {
        return c.onClient(mc -> String.valueOf(Mod.call(Mod.cfg("gui.MenuConfig"), "getTheme")));
    }

    static void setTheme(UiCase c, String t) {
        c.onClient(mc -> {
            Object cfg = Mod.cfg("gui.MenuConfig");
            Mod.call(cfg, "setTheme", Mod.enumValue("gui.MenuTheme", t));
            Mod.call(cfg, "save");
            return null;
        });
    }

    /** Answers a waiting question (No thanks for the ban logs, Yes for Auto Update: the defaults a test config has),
     *  then clicks Next (or Finish). */
    static void advance(UiCase c, boolean last) {
        boolean blocked = c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            return isIntro(s) && (Boolean) Mod.call(s, "nextBlocked");
        });
        if (blocked) {
            String sc = scene(c);
            click(c, sc.equals("BAN_LOGS") ? "No thanks" : "Yes, keep me updated");
        }
        click(c, last ? "Finish" : "Next");
    }

    /** Clicks Next until the scene is {@code want} (bounded). */
    static void goTo(UiCase c, String want) {
        for (int i = 0; i < 12 && !scene(c).equals(want); i++) {
            settle(c);
            advance(c, false);
        }
        c.check(scene(c).equals(want), "never reached " + want + " (at " + scene(c) + ")");
        settle(c);
    }

    // ---- cases -----------------------------------------------------------------------------------------------------

    static void firstShow(UiCase c) {
        if (!"show".equalsIgnoreCase(System.getProperty("killer560.test.intro"))) {
            c.skipCase("needs -PclientJvmArgs=-Dkiller560.test.intro=show (the tour never opens on its own in a test run)");
        }
        // It opens 12 ticks after the title screen is up; give it time if the suite started early.
        for (int i = 0; i < 200 && (Integer) c.onClient(mc -> Mod.staticCall("intro.IntroLauncher", "autoShown")) == 0; i++) {
            c.ticks(1);
        }
        int shown = c.onClient(mc -> (Integer) Mod.staticCall("intro.IntroLauncher", "autoShown"));
        Screen s = intro(c);
        // An updater: with -PseedConfig holding other settings and the first version's {"done": true} (which that version
        // wrote WITHOUT showing anything), the tour must still open (killer560, 2026-11-01).
        String seed = System.getProperty("testkit.introSeed", "");
        c.note("autoShown=" + shown + " screen=" + (s == null ? "other" : "tour") + " seed=" + (seed.isBlank() ? "none" : seed));
        if (!seed.isBlank()) {
            // Prove the seed really landed: its marker file sits beside the intro file, and the first version's key is
            // still the only thing that version would have read.
            String marker = configFile(c, "killer560smod-intro-seed.txt");
            c.check(marker.contains("seed marker"), "the updater seed was not copied into the config (no marker file)");
        }
        c.check(shown == 1, "the tour opened on its own " + shown + " times (expected once)");
        c.check(s != null, "the tour is not on screen after opening on its own");
        Screen parent = c.onClient(mc -> (Screen) Mod.call(s, "parent"));
        c.check(parent instanceof TitleScreen, "the tour opened over " + (parent == null ? "nothing" : parent.getClass().getName())
                + ", not the title screen");
        // Once and only once: seen from the moment it opens, saved, so quitting mid-tour never brings it back.
        c.check(done(c), "the tour is not marked seen while it is up");
        c.check(configFile(c, "killer560smod-intro.json").contains("\"seen\": true"),
                "the intro file does not say seen as it opens: " + configFile(c, "killer560smod-intro.json"));
        c.check(scene(c).equals("WELCOME"), "the first scene is " + scene(c));
        c.ticks(40);
        int again = c.onClient(mc -> (Integer) Mod.staticCall("intro.IntroLauncher", "autoShown"));
        c.check(again == 1, "it opened again by itself (" + again + ")");
        settle(c);
        shot(c, "first-show");
        // Quit mid-tour (no Finish, no Skip): a new session's title screen never opens it again.
        c.onClient(mc -> {
            McCompat.setScreen(mc, new TitleScreen());
            Mod.staticCall("intro.IntroConfig", "load");
            Mod.staticCall("intro.IntroLauncher", "resetSessionForTest");
            return null;
        });
        c.ticks(60);
        int after = c.onClient(mc -> (Integer) Mod.staticCall("intro.IntroLauncher", "autoShown"));
        c.check(after == 1 && intro(c) == null, "after leaving it mid-tour it opened again on the title screen (" + after + ")");
    }

    static void nav(UiCase c) {
        int[] win0 = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.options.guiScale().get()});
        FontsMainMenuSuite.setWindow(c, 1920, 1080, 2);
        try {
            long frames0 = c.onClient(mc -> (Long) Mod.staticCall(INTRO, "guideFrames"));
            open(c);
            settle(c);
            Map<String, AbstractWidget> b = buttons(c);
            c.check(b.containsKey("Next"), "scene 1 has no Next (" + b.keySet() + ")");
            c.check(b.containsKey("Skip Guide"), "scene 1 has no Skip Guide (" + b.keySet() + ")");
            c.check(!b.containsKey("Previous"), "scene 1 has a Previous button");
            c.check(text(c).contains("Killer560's Mod"), "scene 1 says: " + text(c));
            click(c, "Next");
            c.check(scene(c).equals("OPEN_MENU"), "Next went to " + scene(c));
            settle(c);
            c.check(buttons(c).containsKey("Previous"), "scene 2 has no Previous");
            String keyName = c.onClient(mc -> String.valueOf(Mod.staticCall("util.KeyUtil", "keyName",
                    (Integer) Mod.call(Mod.cfg("hud.HudConfig"), "getOpenMenuKeyCode"))));
            c.note("scene 2: " + text(c).replace('\n', ' ') + " (Open Menu key " + keyName + ")");
            c.check(text(c).contains(keyName) && text(c).contains("/killer560"),
                    "scene 2 does not name the Open Menu key " + keyName + " and /killer560: " + text(c));
            click(c, "Previous");
            c.check(scene(c).equals("WELCOME"), "Previous went to " + scene(c));
            settle(c);
            key(c, 262); // Right arrow (the text is typed: it goes on)
            c.check(scene(c).equals("OPEN_MENU"), "the Right arrow went to " + scene(c));
            key(c, 263); // Left arrow
            c.check(scene(c).equals("WELCOME"), "the Left arrow went to " + scene(c));

            // Every scene, with real clicks, photographed.
            settle(c);
            List<String> seen = new ArrayList<>();
            int count = c.onClient(mc -> (Integer) Mod.call(McCompat.screen(mc), "sceneCount"));
            for (int i = 0; i < count; i++) {
                settle(c);
                String sc = scene(c);
                seen.add(sc);
                long before = c.onClient(mc -> (Long) Mod.staticCall(INTRO, "guideFrames"));
                c.ticks(3);
                long after = c.onClient(mc -> (Long) Mod.staticCall(INTRO, "guideFrames"));
                c.check(after > before, "the 3D guide was not drawn on " + sc);
                float turn = c.onClient(mc -> (Float) Mod.staticCall(INTRO, "guideTurn"));
                c.note(String.format(Locale.ROOT, "scene %d %s: turn %.1f deg, says: %s", i + 1, sc, turn,
                        text(c).replace('\n', ' ')));
                shot(c, String.format(Locale.ROOT, "%02d-%s", i + 1, sc.toLowerCase(Locale.ROOT)));
                if (sc.equals("THEME")) {
                    // He points at the menu picture on his left: turned toward it.
                    c.check(Math.abs(turn) > 5f, "the guide did not turn toward the menu (turn " + turn + ")");
                }
                advance(c, i == count - 1);
            }
            c.note("scenes: " + seen);
            Screen now = c.onClient(McCompat::screen);
            c.check(now instanceof TitleScreen, "Finish left " + (now == null ? "nothing" : now.getClass().getName()));
            c.check(done(c), "finishing did not mark the tour done");
            c.check(configFile(c, "killer560smod-intro.json").contains("\"seen\": true"),
                    "the intro file does not say seen: " + configFile(c, "killer560smod-intro.json"));
            long frames1 = c.onClient(mc -> (Long) Mod.staticCall(INTRO, "guideFrames"));
            c.check(frames1 - frames0 > 50, "the 3D guide drew only " + (frames1 - frames0) + " frames");
        } finally {
            FontsMainMenuSuite.setWindow(c, win0[0], win0[1], win0[2]);
        }
    }

    static void skip(UiCase c) {
        setTheme(c, "AMBER");
        open(c);
        settle(c);
        click(c, "Skip Guide");
        Screen now = c.onClient(McCompat::screen);
        c.check(now instanceof TitleScreen, "Skip Guide left " + (now == null ? "nothing" : now.getClass().getName()));
        c.check(done(c), "Skip Guide did not mark the tour done");

        // Escape asks; Escape again keeps going; Skip Tour ends it. From the theme scene, the theme comes back.
        open(c);
        goTo(c, "OPEN_MENU");
        key(c, 256);
        boolean asking = c.onClient(mc -> (Boolean) Mod.call(McCompat.screen(mc), "confirmingSkip"));
        c.check(asking, "Escape did not ask before skipping");
        c.check(buttons(c).containsKey("Skip Tour") && buttons(c).containsKey("Keep Going"),
                "the confirm has " + buttons(c).keySet());
        shot(c, "escape-confirm");
        key(c, 256);
        c.check(intro(c) != null && scene(c).equals("OPEN_MENU")
                && !(Boolean) c.onClient(mc -> Mod.call(McCompat.screen(mc), "confirmingSkip")),
                "Escape twice did not keep going on OPEN_MENU (" + scene(c) + ")");
        goTo(c, "THEME");
        key(c, 256);
        click(c, "Keep Going");
        c.check(intro(c) != null, "Keep Going closed the tour");
        key(c, 256);
        click(c, "Skip Tour");
        c.check(intro(c) == null, "Skip Tour did not close the tour");
        c.check(done(c), "Skip Tour did not mark the tour done");
        String t = themeNow(c);
        c.check(t.equals("AMBER"), "skipping from the theme scene left the theme on " + t);
    }

    static void theme(UiCase c) {
        int[] win0 = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.options.guiScale().get()});
        FontsMainMenuSuite.setWindow(c, 1920, 1080, 2);
        try {
            setTheme(c, "AMBER");
            open(c);
            goTo(c, "THEME");
            // No auto-cycling (killer560, 2026-11-01): 4 s of real time on the scene, the theme never moves.
            java.util.Set<String> shown = new java.util.LinkedHashSet<>();
            long wait = System.nanoTime() + 4_000_000_000L;
            while (System.nanoTime() < wait) {
                shown.add(themeNow(c));
                c.ticks(2);
            }
            c.note("themes shown over 4 s without a click: " + shown);
            c.check(shown.equals(java.util.Set.of("AMBER")), "the theme changed without a click: " + shown);
            c.check("CHOSEN".equals(kind(c, "Amber")), "the current theme's chip is not marked chosen: " + kind(c, "Amber"));
            shot(c, "theme-no-click");
            click(c, "Ocean");
            c.check(themeNow(c).equals("OCEAN"), "clicking Ocean left the theme on " + themeNow(c));
            c.check(configFile(c, "killer560smod-menu.json").contains("\"theme\": \"OCEAN\""),
                    "Ocean was not saved: the menu file has no OCEAN theme");
            c.check("CHOSEN".equals(kind(c, "Ocean")), "the Ocean chip is not marked chosen after the click");
            // The main menu style: the same setting as Home > Menu Theme's Main Menu: Fancy / Simple chip.
            String mode0 = mainMenu(c);
            c.check(buttons(c).containsKey("Simple Main Menu") && buttons(c).containsKey("Fancy Main Menu"),
                    "no main menu style choice on the theme scene: " + buttons(c).keySet());
            click(c, "Simple Main Menu");
            c.check(mainMenu(c).equals("SIMPLE"), "Simple Main Menu left the main menu on " + mainMenu(c));
            c.check(configFile(c, "killer560smod-mainmenu.json").contains("\"sceneMode\": \"SIMPLE\""),
                    "Simple was not saved: " + configFile(c, "killer560smod-mainmenu.json"));
            shot(c, "picked-simple");
            click(c, "Fancy Main Menu");
            c.check(mainMenu(c).equals("FANCY"), "Fancy Main Menu left the main menu on " + mainMenu(c));
            c.note("main menu style " + mode0 + " -> SIMPLE -> FANCY by clicks");
            settle(c);
            // The bubble's border is drawn in the theme's accent: Ocean's teal 2EC4B6 must be on screen.
            Path p = shot(c, "picked-ocean");
            int teal = countNear(p, 0x2EC4B6, 18);
            c.note("Ocean accent pixels: " + teal);
            c.check(teal > 400, "only " + teal + " pixels of Ocean's accent: the picked theme is not drawn");
            click(c, "Next");
            c.check(scene(c).equals("THEME_DONE"), "Next after the pick went to " + scene(c));
            c.check(themeNow(c).equals("OCEAN"), "leaving the theme scene undid the pick: " + themeNow(c));
            click(c, "Skip Guide");
        } finally {
            setTheme(c, "AMBER");
            FontsMainMenuSuite.setWindow(c, win0[0], win0[1], win0[2]);
        }
    }

    static String kind(UiCase c, String label) {
        return c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            return isIntro(s) ? String.valueOf(Mod.call(s, "buttonKind", label)) : "";
        });
    }

    static String mainMenu(UiCase c) {
        return c.onClient(mc -> String.valueOf(Mod.call(Mod.cfg("mainmenu.MainMenuThemeConfig"), "getSceneMode")));
    }

    static int countNear(Path png, int rgb, int tol) {
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(png.toFile());
            int n = 0;
            int r0 = (rgb >> 16) & 0xFF;
            int g0 = (rgb >> 8) & 0xFF;
            int b0 = rgb & 0xFF;
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int p = img.getRGB(x, y);
                    if (Math.abs(((p >> 16) & 0xFF) - r0) <= tol && Math.abs(((p >> 8) & 0xFF) - g0) <= tol
                            && Math.abs((p & 0xFF) - b0) <= tol) {
                        n++;
                    }
                }
            }
            return n;
        } catch (Exception e) {
            return -1;
        }
    }

    static void variant(UiCase c) {
        boolean cheat = Mod.isCheat();
        open(c);
        @SuppressWarnings("unchecked")
        List<Object> scenes = c.onClient(mc -> (List<Object>) Mod.call(McCompat.screen(mc), "sceneList"));
        List<String> names = scenes.stream().map(String::valueOf).toList();
        c.note((cheat ? "cheat" : "legit") + " jar, scenes " + names);
        c.check(names.contains("BAN_LOGS") == cheat, "the ban-log scene is " + (cheat ? "missing on" : "present on")
                + " a " + (cheat ? "cheat" : "legit") + " jar");
        goTo(c, "VARIANT");
        String t = text(c);
        c.note("variant scene: " + t.replace('\n', ' '));
        if (cheat) {
            c.check(t.contains("CHEAT version") && t.contains("delete this one"), "the cheat jar's notice says: " + t);
        } else {
            c.check(t.contains("legit version") && t.contains("naughty") && !t.contains("CHEAT"),
                    "the legit jar's notice says: " + t);
        }
        shot(c, "variant");
        click(c, "Skip Guide");
    }

    static boolean ban(UiCase c) {
        return c.onClient(mc -> (Boolean) Mod.call(Mod.cfg("banreport.BanReportConfig"), "isEnabled"));
    }

    static void banLogs(UiCase c) {
        c.onClient(mc -> {
            Object cfg = Mod.cfg("banreport.BanReportConfig");
            Mod.call(cfg, "setEnabled", false);
            Mod.call(cfg, "save");
            return null;
        });
        boolean cheat = Mod.isCheat();
        open(c);
        if (!cheat) {
            // The whole tour, every scene with Next: the setting is never touched on a legit jar.
            int count = c.onClient(mc -> (Integer) Mod.call(McCompat.screen(mc), "sceneCount"));
            for (int i = 0; i < count; i++) {
                settle(c);
                c.check(!buttons(c).containsKey("Yes, share them"), "a legit jar asked about ban logs on " + scene(c));
                advance(c, i == count - 1);
            }
            c.check(!ban(c), "a legit tour turned Auto Report Bans on");
            return;
        }
        goTo(c, "BAN_LOGS");
        shot(c, "ban-logs");
        c.check(!ban(c), "Auto Report Bans is on before any answer");
        choiceGate(c, "BAN_LOGS", "Yes, share them");
        c.check(!ban(c), "Next with no answer turned Auto Report Bans on");
        click(c, "No thanks");
        c.check(!ban(c), "No thanks turned Auto Report Bans on");
        click(c, "Yes, share them");
        c.check(ban(c), "Yes did not turn Auto Report Bans on");
        c.check(configFile(c, "killer560smod-banreport.json").contains("\"enabled\": true"), "Yes was not saved");
        click(c, "No thanks");
        c.check(!ban(c), "No after Yes left Auto Report Bans on");
        c.check(configFile(c, "killer560smod-banreport.json").contains("\"enabled\": false"), "No was not saved");
        click(c, "Yes, share them");
        click(c, "Skip Guide");
        c.check(ban(c), "Yes did not stay after the tour ended");
        c.onClient(mc -> {
            Object cfg = Mod.cfg("banreport.BanReportConfig");
            Mod.call(cfg, "setEnabled", false);
            Mod.call(cfg, "save");
            return null;
        });
    }

    /** On a question scene: Yes is the suggested (accent) answer, Next is inactive, and neither a click on Next nor the
     *  Right arrow or Enter leaves the scene until an answer is clicked. */
    static void choiceGate(UiCase c, String sceneName, String yes) {
        c.check(scene(c).equals(sceneName), "not on " + sceneName + " (" + scene(c) + ")");
        c.check((Boolean) c.onClient(mc -> Mod.call(McCompat.screen(mc), "nextBlocked")), "Next is not blocked on " + sceneName);
        c.check(!buttons(c).get("Next").active, "the Next button is active before an answer on " + sceneName);
        c.check("PRIMARY".equals(kind(c, yes)), "'" + yes + "' is not the highlighted answer: " + kind(c, yes));
        click(c, "Next");
        c.check(scene(c).equals(sceneName), "a click on Next left " + sceneName + " with no answer (" + scene(c) + ")");
        key(c, 262);
        key(c, 257);
        c.check(scene(c).equals(sceneName), "Right / Enter left " + sceneName + " with no answer (" + scene(c) + ")");
    }

    static boolean autoUpdate(UiCase c) {
        return c.onClient(mc -> (Boolean) Mod.call(Mod.cfg("autoupdate.AutoUpdateConfig"), "isEnabled"));
    }

    static void setAutoUpdate(UiCase c, boolean on) {
        c.onClient(mc -> {
            Object cfg = Mod.cfg("autoupdate.AutoUpdateConfig");
            Mod.call(cfg, "setEnabled", on);
            Mod.call(cfg, "save");
            return null;
        });
    }

    /** The Auto Update question: Next waits for an answer; No turns Auto Update off, Yes on, each saved. */
    static void autoUpdateCase(UiCase c) {
        setAutoUpdate(c, true);
        try {
            open(c);
            goTo(c, "AUTO_UPDATE");
            shot(c, "auto-update");
            choiceGate(c, "AUTO_UPDATE", "Yes, keep me updated");
            c.check(autoUpdate(c), "Auto Update changed with no answer");
            click(c, "No thanks");
            c.check(!autoUpdate(c), "No thanks left Auto Update on");
            c.check(configFile(c, "killer560smod-autoupdate.json").contains("\"enabled\": false"), "No was not saved");
            c.check(buttons(c).get("Next").active, "Next stayed inactive after an answer");
            click(c, "Yes, keep me updated");
            c.check(autoUpdate(c), "Yes left Auto Update off");
            c.check(configFile(c, "killer560smod-autoupdate.json").contains("\"enabled\": true"), "Yes was not saved");
            c.check("CHOSEN".equals(kind(c, "Yes, keep me updated")), "the clicked answer is not marked chosen");
            click(c, "Next");
            c.check(scene(c).equals("MENUS"), "Next after an answer went to " + scene(c));
            click(c, "Skip Guide");
        } finally {
            setAutoUpdate(c, true);
        }
    }

    // ---- his face: measured on a plain green screen behind the tour, with the bubble left out ------------------------

    /** A plain green screen for the tour to open over, so the guide's silhouette is everything not green. */
    static final class GreenScreen extends Screen {
        GreenScreen() {
            super(net.minecraft.network.chat.Component.literal("green"));
        }

        @Override
        public void extractBackground(net.minecraft.client.gui.GuiGraphicsExtractor g, int mx, int my, float pt) {
            g.fill(0, 0, this.width, this.height, 0xFF00C800);
        }

        @Override
        public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor g, int mx, int my, float pt) {
            g.fill(0, 0, this.width, this.height, 0xFF00C800);
        }
    }

    static Screen openOverGreen(UiCase c) {
        c.onClient(mc -> {
            Object cfg = Mod.cfg("intro.IntroConfig");
            Mod.call(cfg, "setDone", false);
            Mod.staticCall("intro.IntroLauncher", "replay", new GreenScreen());
            return null;
        });
        c.ticks(3);
        Screen s = intro(c);
        c.check(s != null, "the tour did not open");
        return s;
    }

    static boolean background(int p) {
        int r = (p >> 16) & 0xFF;
        int g = (p >> 8) & 0xFF;
        int b = p & 0xFF;
        return g > r + 30 && g > b + 30;
    }

    static float lum(int p) {
        return 0.299f * ((p >> 16) & 0xFF) + 0.587f * ((p >> 8) & 0xFF) + 0.114f * (p & 0xFF);
    }

    /**
     * Reads his head off a screenshot, inside the guide's drawn box: {head left, head right, head top, model height,
     * eye centroid x, mouth x, mouth y} in screenshot pixels. The head is the top quarter of the silhouette (no raised
     * arm in the scenes measured); the eyes and brows are the only dark pixels of his face, the mouth the dark pixels of
     * its bottom row.
     */
    static float[] face(UiCase c, Path png) throws Exception {
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(png.toFile());
        int[] box = c.onClient(mc -> (int[]) Mod.staticCall(INTRO, "guideWindowBox"));
        double gs = c.onClient(mc -> (double) mc.getWindow().getGuiScale());
        double sx = img.getWidth() / (double) c.onClient(mc -> mc.getWindow().getScreenWidth()) * gs;
        double sy = img.getHeight() / (double) c.onClient(mc -> mc.getWindow().getScreenHeight()) * gs;
        int x0 = (int) Math.max(0, box[0] * sx);
        int x1 = (int) Math.min(img.getWidth(), box[2] * sx);
        int y0 = (int) Math.max(0, box[1] * sy);
        int y1 = (int) Math.min(img.getHeight(), box[3] * sy);
        int top = -1;
        int bottom = -1;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if (!background(img.getRGB(x, y))) {
                    if (top < 0) {
                        top = y;
                    }
                    bottom = y;
                    break;
                }
            }
        }
        c.check(top >= 0, "no guide pixels in his box " + x0 + "," + y0 + " - " + x1 + "," + y1);
        float h = bottom - top + 1;
        int headBottom = top + Math.round(h * 8f / 32.5f);
        int left = Integer.MAX_VALUE;
        int right = -1;
        double ex = 0;
        int en = 0;
        for (int y = top; y < headBottom; y++) {
            for (int x = x0; x < x1; x++) {
                int p = img.getRGB(x, y);
                if (background(p)) {
                    continue;
                }
                left = Math.min(left, x);
                right = Math.max(right, x);
                if (lum(p) < 75) {
                    ex += x;
                    en++;
                }
            }
        }
        // The mouth: the lowest dark pixels of the head (its bottom two rows of the face texture).
        double mx = 0;
        double my = 0;
        int mn = 0;
        int mouthTop = top + Math.round(h * 6.6f / 32.5f);
        for (int y = mouthTop; y < headBottom; y++) {
            for (int x = x0; x < x1; x++) {
                int p = img.getRGB(x, y);
                if (!background(p) && lum(p) < 30) {
                    mx += x;
                    my += y;
                    mn++;
                }
            }
        }
        return new float[]{left, right, top, h, en == 0 ? Float.NaN : (float) (ex / en),
                mn == 0 ? Float.NaN : (float) (mx / mn), mn == 0 ? Float.NaN : (float) (my / mn)};
    }

    /** Screen GUI units of the tour to screenshot pixels. */
    static float[] toShot(UiCase c, Path png, float gx, float gy) throws Exception {
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(png.toFile());
        double[] w = c.onClient(mc -> toWindow(mc, McCompat.screen(mc), gx, gy));
        double kx = img.getWidth() / (double) c.onClient(mc -> mc.getWindow().getScreenWidth());
        double ky = img.getHeight() / (double) c.onClient(mc -> mc.getWindow().getScreenHeight());
        return new float[]{(float) (w[0] * kx), (float) (w[1] * ky)};
    }

    static void hideBubble(UiCase c, boolean hide) {
        c.onClient(mc -> Mod.staticCall(INTRO, "setHideBubbleForTest", hide));
    }

    /**
     * The tail comes from his mouth: with the bubble left out of the picture, the dark pixels of his mouth are found on
     * the green screenshot and the point the tail is drawn to must be within 1.5 model pixels of them, on the left spot
     * (OPEN_MENU) and the right spot (VARIANT).
     */
    static void mouthCase(UiCase c) throws Exception {
        int[] win0 = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.options.guiScale().get()});
        FontsMainMenuSuite.setWindow(c, 1920, 1080, 2);
        try {
            openOverGreen(c);
            for (String sc : new String[]{"OPEN_MENU", "VARIANT"}) {
                goTo(c, sc);
                hideBubble(c, false);
                c.ticks(2);
                shot(c, "mouth-" + sc.toLowerCase(Locale.ROOT) + "-with-bubble");
                hideBubble(c, true);
                c.ticks(3);
                Path png = shot(c, "mouth-" + sc.toLowerCase(Locale.ROOT));
                float[] f = face(c, png);
                float[] tip = c.onClient(mc -> (float[]) Mod.call(McCompat.screen(mc), "mouth"));
                float[] tipPx = toShot(c, png, tip[0], tip[1]);
                float drawnTip[] = c.onClient(mc -> (float[]) Mod.call(McCompat.screen(mc), "tailTip"));
                float modelPx = f[3] / 32.5f;
                double d = Math.hypot(tipPx[0] - f[5], tipPx[1] - f[6]);
                c.note(String.format(Locale.ROOT, "%s: mouth pixels at %.1f,%.1f, tail point at %.1f,%.1f (%.1f px, %.2f model px)"
                        + "; the bubble drew its tail to %.1f,%.1f (mouth %.1f,%.1f GUI)", sc, f[5], f[6], tipPx[0], tipPx[1],
                        d, d / modelPx, drawnTip[0], drawnTip[1], tip[0], tip[1]));
                c.check(!Float.isNaN(f[5]), sc + ": no mouth pixels found on his face");
                c.check(d <= 1.5 * modelPx, String.format(Locale.ROOT,
                        "%s: the tail point is %.1f px (%.2f model px) from his mouth", sc, d, d / modelPx));
                c.check(Math.abs(drawnTip[0] - tip[0]) < 0.01f && Math.abs(drawnTip[1] - tip[1]) < 0.01f,
                        sc + ": the bubble's tail was not drawn to the mouth point");
            }
        } finally {
            hideBubble(c, false);
            c.onClient(mc -> {
                Screen s = McCompat.screen(mc);
                if (isIntro(s)) {
                    Mod.call(s, "finish");
                }
                return null;
            });
            FontsMainMenuSuite.setWindow(c, win0[0], win0[1], win0[2]);
        }
    }

    /** Which way his face points on a screenshot: +1 right, -1 left (eye centroid against the head's middle). */
    static int faceSide(UiCase c, Path png, String what) throws Exception {
        float[] f = face(c, png);
        float mid = (f[0] + f[1]) / 2f;
        float off = f[4] - mid;
        c.note(String.format(Locale.ROOT, "%s: head %.0f..%.0f, eyes at %.1f (%.1f from the middle), facing %.1f deg",
                what, f[0], f[1], f[4], off, (Float) c.onClient(mc -> Mod.staticCall(INTRO, "guideTurn"))));
        c.check(!Float.isNaN(f[4]), what + ": no eye pixels on his head");
        c.check(Math.abs(off) > (f[1] - f[0]) * 0.06f, what + ": his eyes are in the middle of his head (" + off + ")");
        return off > 0 ? 1 : -1;
    }

    /**
     * He faces the way he walks and, standing, toward what he talks about: read from the side of his head his eyes are
     * on. Standing on the left with the bubble on his right: right. Walking right (OPEN_MENU -> THEME): right. Standing
     * on the right with the menu on his left: left. Walking back left (Previous): left.
     */
    static void facingCase(UiCase c) throws Exception {
        int[] win0 = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.options.guiScale().get()});
        FontsMainMenuSuite.setWindow(c, 1920, 1080, 2);
        try {
            hideBubble(c, true);
            openOverGreen(c);
            goTo(c, "OPEN_MENU");
            c.ticks(3);
            c.check(faceSide(c, shot(c, "facing-standing-left"), "standing left, bubble on his right") > 0,
                    "standing on the left he does not face his bubble on the right");
            click(c, "Next");
            c.ticks(8);
            c.check((Boolean) c.onClient(mc -> Mod.call(McCompat.screen(mc), "guideWalking")), "he is not walking to THEME");
            c.check(faceSide(c, shot(c, "facing-walking-right"), "walking right") > 0, "walking right he faces left");
            settle(c);
            c.ticks(3);
            c.check(faceSide(c, shot(c, "facing-standing-right"), "standing right, menu on his left") < 0,
                    "standing on the right he does not face the menu on his left");
            click(c, "Previous");
            c.ticks(8);
            c.check((Boolean) c.onClient(mc -> Mod.call(McCompat.screen(mc), "guideWalking")), "he is not walking back");
            c.check(faceSide(c, shot(c, "facing-walking-left"), "walking left") < 0, "walking left he faces right");
        } finally {
            hideBubble(c, false);
            c.onClient(mc -> {
                Screen s = McCompat.screen(mc);
                if (isIntro(s)) {
                    Mod.call(s, "finish");
                }
                return null;
            });
            FontsMainMenuSuite.setWindow(c, win0[0], win0[1], win0[2]);
        }
    }

    /** Standing, he does not move: over 1.5 s of real time his drawn box and his silhouette's top and bottom rows stay
     *  the same (no idle bob, no breathing that shows). */
    static void stillCase(UiCase c) throws Exception {
        int[] win0 = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.options.guiScale().get()});
        FontsMainMenuSuite.setWindow(c, 1920, 1080, 2);
        try {
            hideBubble(c, true);
            openOverGreen(c);
            goTo(c, "THEME_DONE");
            // past the cheer, standing
            long until = System.nanoTime() + 2_500_000_000L;
            while (System.nanoTime() < until) {
                c.ticks(5);
            }
            List<String> rows = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                Path png = shot(c, "still-" + i);
                float[] f = face(c, png);
                int[] box = c.onClient(mc -> (int[]) Mod.staticCall(INTRO, "guideWindowBox"));
                rows.add(Math.round(f[2]) + "/" + Math.round(f[2] + f[3]) + "/" + box[1]);
                long t = System.nanoTime() + 300_000_000L;
                while (System.nanoTime() < t) {
                    c.ticks(2);
                }
            }
            c.note("top/bottom/box-y over 5 frames 0.3 s apart: " + rows);
            c.check(new java.util.HashSet<>(rows).size() == 1, "he moved up or down while standing: " + rows);
        } finally {
            hideBubble(c, false);
            c.onClient(mc -> {
                Screen s = McCompat.screen(mc);
                if (isIntro(s)) {
                    Mod.call(s, "finish");
                }
                return null;
            });
            FontsMainMenuSuite.setWindow(c, win0[0], win0[1], win0[2]);
        }
    }

    /** The time estimate sits in its own box on the first scene, in the bubble's accent. */
    static void timeBoxCase(UiCase c) throws Exception {
        int[] win0 = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.options.guiScale().get()});
        FontsMainMenuSuite.setWindow(c, 1920, 1080, 2);
        try {
            setTheme(c, "AMBER");
            open(c);
            settle(c);
            Path png = shot(c, "time-box");
            int[] b = c.onClient(mc -> (int[]) Mod.call(McCompat.screen(mc), "timeBox"));
            float[] a = toShot(c, png, b[0], b[1]);
            float[] z = toShot(c, png, b[0] + b[2], b[1] + b[3]);
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(png.toFile());
            // its border: accent pixels along the box's top edge (the middle half of it)
            int accent = 0;
            int y = Math.round(a[1]);
            for (int x = Math.round(a[0] + (z[0] - a[0]) * 0.25f); x < Math.round(a[0] + (z[0] - a[0]) * 0.75f); x++) {
                for (int dy = -2; dy <= 3; dy++) {
                    int p = img.getRGB(x, Math.max(0, y + dy));
                    if (((p >> 16) & 0xFF) > 150 && ((p >> 8) & 0xFF) > 60 && ((p >> 8) & 0xFF) < 170 && (p & 0xFF) < 90) {
                        accent++;
                        break;
                    }
                }
            }
            int span = Math.round((z[0] - a[0]) * 0.5f);
            c.note("time box " + java.util.Arrays.toString(b) + ": accent border on " + accent + " of " + span + " columns");
            c.check(accent > span * 0.8, "the time estimate has no box of its own (accent border on " + accent + "/" + span + ")");
            click(c, "Skip Guide");
        } finally {
            FontsMainMenuSuite.setWindow(c, win0[0], win0[1], win0[2]);
        }
    }

    static void once(UiCase c) {
        open(c);
        click(c, "Skip Guide");
        c.check(done(c), "not done after Skip Guide");
        c.onClient(mc -> {
            Mod.staticCall("intro.IntroConfig", "load");
            Mod.staticCall("intro.IntroLauncher", "resetSessionForTest");
            McCompat.setScreen(mc, new TitleScreen());
            return null;
        });
        c.check(done(c), "done did not survive a re-read of the config");
        c.ticks(60);
        Screen now = c.onClient(McCompat::screen);
        c.check(now instanceof TitleScreen, "after it was done the title screen showed "
                + (now == null ? "nothing" : now.getClass().getName()));

        // Home's Replay Intro opens it again, over the menu.
        c.onClient(mc -> {
            McCompat.setScreen(mc, (Screen) Mod.staticCall("gui.ModScreen", "atTab", new TitleScreen(), "Mod & HUD"));
            return null;
        });
        c.ticks(5);
        AbstractWidget replay = c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            List<Object> all = new ArrayList<>(s.children());
            Object pane = Mod.field(s, "contentPane"); // the drawer's rows live in the content pane
            if (pane != null) {
                all.addAll((java.util.Collection<?>) Mod.field(pane, "children"));
            }
            for (Object child : all) {
                if (child instanceof AbstractWidget w && w.getMessage().getString().equals("Replay Intro")) {
                    return w;
                }
            }
            return null;
        });
        c.check(replay != null, "Home's Mod & HUD has no Replay Intro button");
        double[] at = c.onClient(mc -> toWindow(mc, McCompat.screen(mc), replay.getX() + replay.getWidth() / 2.0,
                replay.getY() + replay.getHeight() / 2.0));
        c.ctx().getInput().setCursorPos(at[0], at[1]);
        c.ticks(2);
        c.ctx().getInput().pressMouse(McKeys.mouse(0));
        c.ticks(6);
        Screen s = intro(c);
        c.check(s != null, "Replay Intro did not open the tour");
        Screen parent = c.onClient(mc -> (Screen) Mod.call(s, "parent"));
        c.check(parent != null && parent.getClass().getSimpleName().equals("ModScreen"),
                "Replay Intro's tour returns to " + parent);
        settle(c);
        shot(c, "replay-over-menu");
        click(c, "Skip Guide");
        Screen back = c.onClient(McCompat::screen);
        c.check(back != null && back.getClass().getSimpleName().equals("ModScreen"), "skipping the replay left "
                + (back == null ? "nothing" : back.getClass().getName()));
    }

    static void sizes(UiCase c) {
        int[] win0 = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.options.guiScale().get()});
        int[][] sizes = {{854, 480, 1}, {854, 480, 2}, {1280, 720, 2}, {1920, 1080, 1}, {1920, 1080, 2},
                {1920, 1080, 3}, {1920, 1080, 4}, {2560, 1440, 3}, {2560, 1440, 4}};
        try {
            for (int[] sz : sizes) {
                FontsMainMenuSuite.setWindow(c, sz[0], sz[1], sz[2]);
                open(c);
                int count = c.onClient(mc -> (Integer) Mod.call(McCompat.screen(mc), "sceneCount"));
                for (int i = 0; i < count; i++) {
                    settle(c);
                    String sc = scene(c);
                    String why = c.onClient(mc -> {
                        Screen s = McCompat.screen(mc);
                        float[] b = (float[]) Mod.call(s, "bubbleRect");
                        int[] g = (int[]) Mod.call(s, "guideBox");
                        int w = s.width;
                        int h = s.height;
                        List<String> bad = new ArrayList<>();
                        if (b[0] < 0 || b[1] < 0 || b[0] + b[2] > w || b[1] + b[3] > h) {
                            bad.add(String.format(Locale.ROOT, "bubble %.0f,%.0f %.0fx%.0f outside %dx%d", b[0], b[1], b[2],
                                    b[3], w, h));
                        }
                        if (b[0] < g[0] + g[2] - 6 && b[0] + b[2] > g[0] + 6 && b[1] + b[3] > g[1] + 30) {
                            bad.add("bubble over the guide");
                        }
                        @SuppressWarnings("unchecked")
                        Map<String, AbstractWidget> btn = (Map<String, AbstractWidget>) Mod.call(s, "buttons");
                        for (var e : btn.entrySet()) {
                            AbstractWidget x = e.getValue();
                            if (x.getX() < 0 || x.getY() < 0 || x.getX() + x.getWidth() > w || x.getY() + x.getHeight() > h) {
                                bad.add("'" + e.getKey() + "' outside the window");
                            }
                            if (x.getX() < g[0] + g[2] - 4 && x.getX() + x.getWidth() > g[0] + 4) {
                                bad.add("'" + e.getKey() + "' over the guide");
                            }
                        }
                        return bad.isEmpty() ? null : String.join(", ", bad);
                    });
                    if (why != null) {
                        c.problem(sz[0] + "x" + sz[1] + " GUI " + sz[2] + " " + sc + ": " + why);
                    }
                    if (sz[2] == 1 && sz[0] == 854 || sz[0] == 2560 && sz[2] == 4) {
                        if (sc.equals("THEME") || sc.equals("WELCOME")) {
                            shot(c, sz[0] + "x" + sz[1] + "-gui" + sz[2] + "-" + sc.toLowerCase(Locale.ROOT));
                        }
                    }
                    advance(c, i == count - 1);
                }
            }
        } finally {
            setTheme(c, "AMBER");
            FontsMainMenuSuite.setWindow(c, win0[0], win0[1], win0[2]);
        }
    }

    static void clock(UiCase c, double ms) {
        c.onClient(mc -> Mod.staticCall("gui.MenuAnim", "setClockForTest", ms));
    }

    static void anim(UiCase c) {
        int[] win0 = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.options.guiScale().get()});
        FontsMainMenuSuite.setWindow(c, 1280, 720, 2);
        double t = 100_000;
        try {
            clock(c, t);
            open(c);
            c.ticks(1);
            boolean early = c.onClient(mc -> (Boolean) Mod.call(McCompat.screen(mc), "settled"));
            c.check(!early, "the bubble was settled the moment it opened (no pop-in or typing)");
            for (int ms = 0; ms <= 3000; ms += 100) {
                clock(c, t + ms);
                c.ticks(1);
                shot(c, String.format(Locale.ROOT, "anim-a-%04d", ms));
            }
            boolean popped = c.onClient(mc -> (Boolean) Mod.call(McCompat.screen(mc), "settled"));
            c.check(popped, "the bubble had not settled 3 s in (pop-in and typing never ended)");
            t += 3500;
            clock(c, t);
            c.ticks(1);
            int x0 = c.onClient(mc -> ((int[]) Mod.call(McCompat.screen(mc), "guideBox"))[0]);
            click(c, "Next");
            click(c, "Next"); // OPEN_MENU -> THEME: he walks across the screen
            t += 1;
            clock(c, t);
            c.ticks(1);
            int xEnd = -1;
            int xMid = -1;
            for (int ms = 0; ms <= 3200; ms += 80) {
                clock(c, t + ms);
                c.ticks(1);
                int x = c.onClient(mc -> ((int[]) Mod.call(McCompat.screen(mc), "guideBox"))[0]);
                if (ms == 800) {
                    xMid = x;
                }
                xEnd = x;
                shot(c, String.format(Locale.ROOT, "anim-b-%04d", ms));
            }
            c.note("guide x: start " + x0 + ", 0.8 s into the walk " + xMid + ", end " + xEnd);
            c.check(xMid > x0 + 5 && xMid < xEnd - 5, "the guide did not walk (x " + x0 + " -> " + xMid + " -> " + xEnd + ")");
        } finally {
            clock(c, -1);
            c.onClient(mc -> {
                Screen s = McCompat.screen(mc);
                if (isIntro(s)) {
                    Mod.call(s, "finish");
                }
                return null;
            });
            setTheme(c, "AMBER");
            FontsMainMenuSuite.setWindow(c, win0[0], win0[1], win0[2]);
        }
    }
}

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
            "1101-ui-intro-sizes", "1101-ui-intro-anim"};
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

    /** Clicks Next until the scene is {@code want} (bounded). */
    static void goTo(UiCase c, String want) {
        for (int i = 0; i < 12 && !scene(c).equals(want); i++) {
            settle(c);
            click(c, "Next");
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
                click(c, i == count - 1 ? "Finish" : "Next");
            }
            c.note("scenes: " + seen);
            Screen now = c.onClient(McCompat::screen);
            c.check(now instanceof TitleScreen, "Finish left " + (now == null ? "nothing" : now.getClass().getName()));
            c.check(done(c), "finishing did not mark the tour done");
            c.check(configFile(c, "killer560smod-intro.json").contains("\"done\": true"),
                    "the intro file does not say done: " + configFile(c, "killer560smod-intro.json"));
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
        // The presets cycle every 1.5 s of real time: wait (bounded) until another one is shown.
        for (int i = 0; i < 600 && themeNow(c).equals("AMBER"); i++) {
            c.ticks(1);
        }
        c.check(!themeNow(c).equals("AMBER"), "the theme scene never showed another preset");
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
            java.util.Set<String> shown = new java.util.LinkedHashSet<>();
            // 1.5 s of real time per preset; the lockstep can run ticks faster than that, so wait on the count.
            for (int i = 0; i < 900 && shown.size() < 3; i++) {
                shown.add(themeNow(c));
                c.ticks(1);
            }
            c.note("themes shown while cycling: " + shown);
            c.check(shown.size() >= 2, "the presets did not cycle: " + shown);
            shot(c, "cycling");
            click(c, "Ocean");
            c.check(themeNow(c).equals("OCEAN"), "clicking Ocean left the theme on " + themeNow(c));
            c.check(configFile(c, "killer560smod-menu.json").contains("\"theme\": \"OCEAN\""),
                    "Ocean was not saved: the menu file has no OCEAN theme");
            long until = System.nanoTime() + 3_500_000_000L; // two whole cycle steps of real time
            while (System.nanoTime() < until) {
                c.ticks(5);
            }
            c.check(themeNow(c).equals("OCEAN"), "the presets kept cycling after a pick: " + themeNow(c));
            c.check(text(c).contains("Ocean"), "the bubble does not say the pick: " + text(c));
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
                click(c, i == count - 1 ? "Finish" : "Next");
            }
            c.check(!ban(c), "a legit tour turned Auto Report Bans on");
            return;
        }
        goTo(c, "BAN_LOGS");
        shot(c, "ban-logs");
        c.check(!ban(c), "Auto Report Bans is on before any answer");
        click(c, "Next");
        c.check(!ban(c), "Next with no answer turned Auto Report Bans on");
        click(c, "Previous");
        settle(c);
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
                    click(c, i == count - 1 ? "Finish" : "Next");
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

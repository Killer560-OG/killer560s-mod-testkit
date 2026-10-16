package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.mod.Quiet;
import dev.testkit.harness.Report;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.TitleScreen;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 1680-1684: the full-resolution painted title scenes (mod scenes-dark-aurora-1016, killer560 2026-10-16: "can you make the
 * starfield more milky way themed? ... make aurora have some more going on at the bottom of it with flickering things",
 * then "for waterfall, starfield, and aurora can you add a custom character to each ... For the starfield one make one
 * without the character ... and one with", and "for these new pictures only push them to my title screen instance").
 * Every case runs at 1920x1080 GUI 2 with the clock held, and turns {@code killer560smod.previewScenes} on in this JVM for
 * the new scenes (off again after).
 * <ul>
 *   <li>1680 Milky Way: MILKY_WAY_HERO draws the character (her rectangles counted) and MILKY_WAY does not; both are dark,
 *       a different picture from the old STARS and from each other (only where she stands), move over 4 s (twinkle, mist,
 *       meteor, cape), keep the title band dark, and allocate no more per rectangle than STARS.</li>
 *   <li>1681 frozen aurora: the character is drawn; the sky above the land takes the Custom accent's hue (set to a green
 *       for the case); the campfire is warm and flickers between two frames 0.1 s apart; the title band is dark; it differs
 *       from the old CURTAINS; it moves; per-rectangle allocation within the old scene's.</li>
 *   <li>1682 waterfall: HERO draws the character on the left and stays blue; WATERFALL draws none; they differ only on the
 *       left side.</li>
 *   <li>1683 gating: without previewScenes each theme's default is the released scene (STARS, CURTAINS, WATERFALL: no
 *       character drawn), and with it the new one.</li>
 *   <li>1684 video (only when named): ~8 s at 60 fps of each of {@code -PpaintedScene} (default MILKY_WAY, MILKY_WAY_HERO,
 *       FROZEN, HERO) into {@code <report>/painted-<scene>/}.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class PaintedScenesSuite implements FabricClientGameTest {

    static final String[] CASES = {"1680-ui-painted-milkyway", "1681-ui-painted-aurora", "1682-ui-painted-waterfall",
            "1683-ui-painted-gating", "1684-ui-painted-video"};

    static final String SCENES = "mainmenu.MainMenuScenes";
    static final String PREVIEW = "killer560smod.previewScenes";

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
        ctx.computeOnClient(mc -> {
            if (Quiet.enabled()) {
                Quiet.apply();
            }
            Mod.staticCall("util.SkyblockGate", "setEnabled", false);
            return null;
        });
        UiCase.run(ctx, "1680-ui-painted-milkyway", PaintedScenesSuite::milkyWay);
        UiCase.run(ctx, "1681-ui-painted-aurora", PaintedScenesSuite::aurora);
        UiCase.run(ctx, "1682-ui-painted-waterfall", PaintedScenesSuite::waterfall);
        UiCase.run(ctx, "1683-ui-painted-gating", PaintedScenesSuite::gating);
        UiCase.run(ctx, "1684-ui-painted-video", PaintedScenesSuite::video);
    }

    // ---- shared ---------------------------------------------------------------------------------------------------------

    private interface Body {
        void run() throws Exception;
    }

    /** Runs {@code body} at 1920x1080 GUI 2 with the title screen's settings saved and restored, previewScenes as given. */
    private static void around(UiCase c, boolean preview, Body body) throws Exception {
        FontsMainMenuSuite.MmState saved = FontsMainMenuSuite.saveMm(c);
        int[] win0 = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.options.guiScale().get()});
        String before = System.getProperty(PREVIEW);
        try {
            if (preview) {
                System.setProperty(PREVIEW, "true");
            } else {
                System.clearProperty(PREVIEW);
            }
            FontsMainMenuSuite.setWindow(c, 1920, 1080, 2);
            c.ctx().getInput().setCursorPos(4, 4);
            body.run();
        } finally {
            if (before == null) {
                System.clearProperty(PREVIEW);
            } else {
                System.setProperty(PREVIEW, before);
            }
            c.onClient(mc -> {
                Mod.staticCall(SCENES, "setDarkSceneForTest", "");
                Mod.staticCall(SCENES, "setAuroraSceneForTest", "");
                Mod.staticCall(SCENES, "setOceanSceneForTest", "");
                Mod.staticCall(SCENES, "setClockForTest", -1.0);
                return null;
            });
            FontsMainMenuSuite.setWindow(c, win0[0], win0[1], win0[2]);
            FontsMainMenuSuite.restoreMm(c, saved);
        }
    }

    /** The title screen in {@code theme} (Separate style) with {@code hook}({@code scene}); "" = the default. */
    private static void title(UiCase c, String theme, String hook, String scene) {
        c.onClient(mc -> {
            FontsMainMenuSuite.setMm(mc, "SEPARATE", theme, "AUTO", 100, true);
            if (hook != null) {
                Mod.staticCall(SCENES, hook, scene);
            }
            McCompat.setScreen(mc, new TitleScreen());
            return null;
        });
        c.ticks(40);
    }

    private static void clock(UiCase c, double ms) {
        c.onClient(mc -> {
            Mod.staticCall(SCENES, "setClockForTest", ms);
            return null;
        });
        c.ticks(2);
    }

    private static int heroRects(UiCase c) {
        return c.onClient(mc -> (Integer) Mod.field(Mod.ROOT + SCENES, "heroRects"));
    }

    /** One scene, measured: two frames 4 s apart, its character's rectangles, 60 frames of the allocation probe. */
    private record Shot(String name, FontsMainMenuSuite.Pic a, FontsMainMenuSuite.Pic b, int hero, long[] alloc) {
        double moved() {
            return FontsMainMenuSuite.changed(a.img(), b.img());
        }

        double bytesPerQuad() {
            return alloc[0] / (double) Math.max(1, alloc[2]);
        }

        double quadsPerFrame() {
            return alloc[2] / (double) Math.max(1, alloc[1]);
        }
    }

    private static Shot measure(UiCase c, String theme, String hook, String scene, String label) throws Exception {
        title(c, theme, hook, scene);
        clock(c, 2_000_000);
        Path pa = FontsMainMenuSuite.shot(c, label + "-a");
        FontsMainMenuSuite.Pic a = FontsMainMenuSuite.pic(pa);
        int hero = heroRects(c);
        clock(c, 2_004_000);
        FontsMainMenuSuite.Pic b = FontsMainMenuSuite.pic(FontsMainMenuSuite.shot(c, label + "-b"));
        long[] alloc = GreenThemeSuite.alloc(c);
        c.check(alloc[1] >= 50 && alloc[2] > 0, "the allocation probe did not run for " + label);
        copy(pa, label);
        Shot s = new Shot(label, a, b, hero, alloc);
        c.note(String.format(Locale.ROOT, "%s: hero %d rects, lum %.3f, rgb %.0f,%.0f,%.0f, moved %.2f%% over 4 s, "
                        + "%.0f quads/frame, %.1f B/quad, title band lum %.3f", label, hero, a.lum(), a.r(), a.g(), a.b(),
                s.moved() * 100, s.quadsPerFrame(), s.bytesPerQuad(), titleLum(a.img())));
        return s;
    }

    /** TESTKIT_PAINTED_SHOTS copies each scene's first frame there as {@code <label>.png}. */
    private static void copy(Path p, String label) {
        String dir = System.getenv("TESTKIT_PAINTED_SHOTS");
        if (dir == null || dir.isBlank()) {
            return;
        }
        try {
            Files.createDirectories(Path.of(dir));
            Files.copy(p, Path.of(dir).resolve(label + ".png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
    }

    /** Mean luminance of the title band (the middle half of the width, the top 16% of the height), outside the logo's
     *  own pixels: the darkest 70% of samples, so the white title itself does not count. */
    static double titleLum(BufferedImage img) {
        java.util.List<Double> v = new java.util.ArrayList<>();
        for (int y = 0; y < img.getHeight() * 16 / 100; y += 2) {
            for (int x = img.getWidth() / 4; x < img.getWidth() * 3 / 4; x += 2) {
                v.add(GreenThemeSuite.lum709(img.getRGB(x, y)));
            }
        }
        v.sort(Double::compare);
        int n = v.size() * 7 / 10;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += v.get(i);
        }
        return n == 0 ? 0 : sum / n;
    }

    /** Share of changed pixels in a box given in fractions of the image. */
    static double changedIn(BufferedImage a, BufferedImage b, double x0, double y0, double x1, double y1) {
        int ax = (int) (a.getWidth() * x0);
        int ay = (int) (a.getHeight() * y0);
        int bw = (int) (a.getWidth() * (x1 - x0));
        int bh = (int) (a.getHeight() * (y1 - y0));
        return FontsMainMenuSuite.changed(a.getSubimage(ax, ay, bw, bh), b.getSubimage(ax, ay, bw, bh));
    }

    // ---- 1680 ----------------------------------------------------------------------------------------------------------

    static void milkyWay(UiCase c) throws Exception {
        around(c, true, () -> {
            Shot stars = measure(c, "DARK", "setDarkSceneForTest", "STARS", "stars");
            Shot plain = measure(c, "DARK", "setDarkSceneForTest", "MILKY_WAY", "milkyway-nochar");
            Shot hero = measure(c, "DARK", "setDarkSceneForTest", "MILKY_WAY_HERO", "milkyway-char");
            c.check(hero.hero() > 40, "MILKY_WAY_HERO drew no character: " + hero.hero() + " rects");
            c.check(plain.hero() == 0, "MILKY_WAY drew a character: " + plain.hero() + " rects");
            c.check(stars.hero() == 0, "STARS drew a character: " + stars.hero() + " rects");
            for (Shot s : new Shot[]{plain, hero}) {
                c.check(s.a().lum() < 0.25, s.name() + " is not dark: lum " + s.a().lum());
                // a calm sky: twinkling stars, mist and (with her) the cape; 0.046% measured without her (2026-10-16)
                c.check(s.moved() > 0.0002, String.format(Locale.ROOT, "%s did not move over 4 s: %.3f%%", s.name(), s.moved() * 100));
                c.check(titleLum(s.a().img()) < 0.09, String.format(Locale.ROOT, "%s is bright behind the title: %.3f",
                        s.name(), titleLum(s.a().img())));
                c.check(s.bytesPerQuad() <= stars.bytesPerQuad() * 1.15, String.format(Locale.ROOT,
                        "%s allocates beyond its rectangles: %.1f B/quad against STARS' %.1f", s.name(), s.bytesPerQuad(),
                        stars.bytesPerQuad()));
            }
            double vsStars = FontsMainMenuSuite.changed(plain.a().img(), stars.a().img());
            c.check(vsStars > 0.05, String.format(Locale.ROOT, "the Milky Way looks like the old starfield: %.1f%% differ", vsStars * 100));
            // with and without her: the same sky, so they differ where she stands (small, on the rise at the right) and
            // nowhere much else
            double right = changedIn(hero.a().img(), plain.a().img(), 0.76, 0.66, 0.84, 0.84);
            double left = changedIn(hero.a().img(), plain.a().img(), 0.02, 0.30, 0.30, 0.86);
            c.note(String.format(Locale.ROOT, "with/without her: %.2f%% of her box differs, %.3f%% of the left side", right * 100, left * 100));
            c.check(right > 0.03, String.format(Locale.ROOT, "the character is not where she should stand: %.2f%% differ", right * 100));
            c.check(left < 0.01, String.format(Locale.ROOT, "with and without the character differ away from her: %.3f%%", left * 100));
        });
    }

    // ---- 1681 ----------------------------------------------------------------------------------------------------------

    static void aurora(UiCase c) throws Exception {
        int[] colours0 = c.onClient(mc -> new int[]{
                (Integer) Mod.call(Mod.cfg("gui.MenuConfig"), "getCustomColour", Mod.enumValue("gui.MenuTheme$Slot", "ACCENT")),
                (Integer) Mod.call(Mod.cfg("gui.MenuConfig"), "getCustomColour", Mod.enumValue("gui.MenuTheme$Slot", "SWITCH_ON"))});
        try {
            c.onClient(mc -> {
                Mod.call(Mod.cfg("gui.MenuConfig"), "setCustomColour", Mod.enumValue("gui.MenuTheme$Slot", "ACCENT"), 0xFF3CFF8C);
                Mod.call(Mod.cfg("gui.MenuConfig"), "setCustomColour", Mod.enumValue("gui.MenuTheme$Slot", "SWITCH_ON"), 0xFF2EC4B6);
                return null;
            });
            around(c, true, () -> {
                Shot old = measure(c, "CUSTOM", "setAuroraSceneForTest", "CURTAINS", "aurora-curtains");
                Shot frozen = measure(c, "CUSTOM", "setAuroraSceneForTest", "FROZEN", "aurora-frozen");
                c.check(frozen.hero() > 200, "the frozen aurora drew no character: " + frozen.hero() + " rects");
                c.check(old.hero() == 0, "CURTAINS drew a character: " + old.hero());
                BufferedImage img = frozen.a().img();
                // the aurora band on the left of the menu takes the accent's hue: green over red and blue
                double[] sky = GreenThemeSuite.boxStats(img, 0.17, 0.30, 0.37, 0.52);
                int skyMid = (int) sky[1];
                double[] mean = meanRgb(img, 0.17, 0.30, 0.37, 0.52);
                c.note(String.format(Locale.ROOT, "aurora band mean rgb %.0f,%.0f,%.0f", mean[0], mean[1], mean[2]));
                c.check(mean[1] > mean[0] + 6 && mean[1] > mean[2], String.format(Locale.ROOT,
                        "the aurora does not take the Custom accent (green): mean rgb %.0f,%.0f,%.0f", mean[0], mean[1], mean[2]));
                // the campfire: warm, and flickering frame to frame
                double[] fire = meanRgb(img, 0.235, 0.76, 0.275, 0.85);
                c.note(String.format(Locale.ROOT, "campfire mean rgb %.0f,%.0f,%.0f", fire[0], fire[1], fire[2]));
                c.check(fire[0] > fire[2] + 25, "the campfire is not warm: " + java.util.Arrays.toString(fire));
                clock(c, 2_000_000);
                Path f1 = c.ctx().takeScreenshot(Report.fileName(c.name() + "-fire-1"));
                clock(c, 2_000_100);
                Path f2 = c.ctx().takeScreenshot(Report.fileName(c.name() + "-fire-2"));
                double flicker = changedIn(FontsMainMenuSuite.pic(f1).img(), FontsMainMenuSuite.pic(f2).img(), 0.22, 0.72, 0.29, 0.86);
                c.note(String.format(Locale.ROOT, "campfire changed %.2f%% in 0.1 s", flicker * 100));
                c.check(flicker > 0.02, String.format(Locale.ROOT, "the campfire does not flicker: %.3f%%", flicker * 100));
                c.check(titleLum(img) < 0.09, String.format(Locale.ROOT, "the frozen aurora is bright behind the title: %.3f", titleLum(img)));
                c.check(frozen.moved() > 0.003, String.format(Locale.ROOT, "the frozen aurora did not move: %.3f%%", frozen.moved() * 100));
                double differ = FontsMainMenuSuite.changed(img, old.a().img());
                c.check(differ > 0.05, String.format(Locale.ROOT, "the frozen aurora looks like the old one: %.1f%%", differ * 100));
                c.check(frozen.bytesPerQuad() <= old.bytesPerQuad() * 1.15, String.format(Locale.ROOT,
                        "the frozen aurora allocates beyond its rectangles: %.1f B/quad against %.1f", frozen.bytesPerQuad(),
                        old.bytesPerQuad()));
                c.note("aurora sky median " + Integer.toHexString(skyMid));
            });
        } finally {
            c.onClient(mc -> {
                Mod.call(Mod.cfg("gui.MenuConfig"), "setCustomColour", Mod.enumValue("gui.MenuTheme$Slot", "ACCENT"), colours0[0]);
                Mod.call(Mod.cfg("gui.MenuConfig"), "setCustomColour", Mod.enumValue("gui.MenuTheme$Slot", "SWITCH_ON"), colours0[1]);
                return null;
            });
        }
    }

    static double[] meanRgb(BufferedImage img, double x0, double y0, double x1, double y1) {
        long r = 0;
        long g = 0;
        long b = 0;
        long n = 0;
        for (int y = (int) (img.getHeight() * y0); y < (int) (img.getHeight() * y1); y++) {
            for (int x = (int) (img.getWidth() * x0); x < (int) (img.getWidth() * x1); x++) {
                int p = img.getRGB(x, y);
                r += (p >> 16) & 0xFF;
                g += (p >> 8) & 0xFF;
                b += p & 0xFF;
                n++;
            }
        }
        return new double[]{r / (double) n, g / (double) n, b / (double) n};
    }

    // ---- 1682 ----------------------------------------------------------------------------------------------------------

    static void waterfall(UiCase c) throws Exception {
        around(c, true, () -> {
            Shot old = measure(c, "OCEAN", "setOceanSceneForTest", "WATERFALL", "waterfall-old");
            Shot hero = measure(c, "OCEAN", "setOceanSceneForTest", "HERO", "waterfall-char");
            c.check(hero.hero() > 200, "the waterfall drew no character: " + hero.hero() + " rects");
            c.check(old.hero() == 0, "WATERFALL drew a character: " + old.hero());
            c.check(hero.a().b() > hero.a().r() + 8, "the waterfall with the character is not blue: " + hero.a());
            double left = changedIn(hero.a().img(), old.a().img(), 0.05, 0.35, 0.30, 0.95);
            double right = changedIn(hero.a().img(), old.a().img(), 0.65, 0.05, 0.95, 0.95);
            c.note(String.format(Locale.ROOT, "with/without her: left %.2f%%, right %.3f%%", left * 100, right * 100));
            c.check(left > 0.05, String.format(Locale.ROOT, "the character and ledge are not on the left: %.2f%%", left * 100));
            c.check(hero.moved() > 0.0005, "the waterfall with the character did not move");
        });
    }

    // ---- 1683 ----------------------------------------------------------------------------------------------------------

    static void gating(UiCase c) throws Exception {
        String[][] themes = {{"DARK", "STARS", "MILKY_WAY_HERO", "darkScene"}, {"CUSTOM", "CURTAINS", "FROZEN", "auroraScene"},
                {"OCEAN", "WATERFALL", "HERO", "oceanScene"}};
        for (boolean preview : new boolean[]{false, true}) {
            around(c, preview, () -> {
                for (String[] th : themes) {
                    title(c, th[0], null, null);
                    clock(c, 2_000_000);
                    String scene = c.onClient(mc -> String.valueOf(Mod.staticCall(SCENES, th[3])));
                    int hero = heroRects(c);
                    String want = preview ? th[2] : th[1];
                    c.note((preview ? "preview: " : "released: ") + th[0] + " draws " + scene + ", hero " + hero + " rects");
                    c.check(want.equals(scene), (preview ? "with" : "without") + " previewScenes " + th[0] + " draws " + scene
                            + ", not " + want);
                    c.check(preview == (hero > 40), (preview ? "with" : "without") + " previewScenes " + th[0]
                            + " drew " + hero + " character rects");
                }
            });
        }
    }

    // ---- 1684 ----------------------------------------------------------------------------------------------------------

    static void video(UiCase c) throws Exception {
        if (!System.getProperty("testkit.scenario", "").contains("1684")) {
            c.note("skipped: the video only runs when 1684 is named");
            return;
        }
        // -PpaintedScene=<one or a comma list>; default all four new scenes, -PpaintedFrames=N (default 480 = 8 s)
        for (String scene : System.getProperty("testkit.paintedScene", "MILKY_WAY,MILKY_WAY_HERO,FROZEN,HERO").split(",")) {
            record(c, scene.trim());
        }
    }

    private static void record(UiCase c, String scene) throws Exception {
        String theme = switch (scene) {
            case "FROZEN", "CURTAINS" -> "CUSTOM";
            case "HERO", "WATERFALL" -> "OCEAN";
            default -> "DARK";
        };
        String hook = theme.equals("CUSTOM") ? "setAuroraSceneForTest" : theme.equals("OCEAN") ? "setOceanSceneForTest"
                : "setDarkSceneForTest";
        around(c, true, () -> {
            title(c, theme, hook, scene);
            int frames = Integer.parseInt(System.getProperty("testkit.paintedFrames", "480"));
            Path dir = Report.dir().resolve("painted-" + scene.toLowerCase(Locale.ROOT));
            for (int i = 0; i < frames; i++) {
                double ms = 3_000_000 + i * (1000.0 / 60.0);
                c.onClient(mc -> {
                    Mod.staticCall(SCENES, "setClockForTest", ms);
                    return null;
                });
                c.ticks(1);
                MenuAnimSuite.keep(c.ctx().takeScreenshot("painted-frame"), dir, String.format(Locale.ROOT, "frame_%05d.png", i));
            }
            c.note("wrote " + frames + " frames to " + dir);
        });
    }
}

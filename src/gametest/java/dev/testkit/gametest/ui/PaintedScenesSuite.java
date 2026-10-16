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
 * 1680-1683: the picture scenes (mod scenes-dark-aurora-1016). killer560, 2026-10-16: "can you make the starfield more milky
 * way themed?", "For the starfield one make one without the character ... and one with", an inspiration picture, "mainly
 * black and white", and "I will create them and just let you animate them" - so a scene is his picture animated by
 * {@code mainmenu.PictureScene}, which was generalised from Green's TARGET scene. Every case runs at 1920x1080 GUI 2 with the
 * clock held, and turns {@code killer560smod.previewScenes} on in this JVM where it needs the new scenes (off again after).
 * <ul>
 *   <li>1680 Milky Way: MILKY_WAY_HERO's cape moves (its ripple tiles counted) and MILKY_WAY draws no character; both are
 *       dark and black and white, a different picture from the old STARS, move over 4 s, keep the title band dark, and
 *       allocate no more per drawn part than STARS; with and without differ where she sits and nowhere much else.</li>
 *   <li>1681 Green's TARGET through the pipeline: three frames at fixed clocks ({@code TESTKIT_PAINTED_SHOTS} copies them
 *       as target-*.png, for a pixel diff against main's jar), its cape and hair ripple, and it is still green.</li>
 *   <li>1682 gating: without previewScenes Dark draws STARS and no character; with it MILKY_WAY_HERO.</li>
 *   <li>1683 video (only when named): ~8 s at 60 fps of each of {@code -PpaintedScene} (default MILKY_WAY,
 *       MILKY_WAY_HERO) into {@code <report>/painted-<scene>/}.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class PaintedScenesSuite implements FabricClientGameTest {

    static final String[] CASES = {"1680-ui-painted-milkyway", "1681-ui-picture-target", "1682-ui-painted-gating",
            "1683-ui-painted-video"};

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
        UiCase.run(ctx, "1681-ui-picture-target", PaintedScenesSuite::target);
        UiCase.run(ctx, "1682-ui-painted-gating", PaintedScenesSuite::gating);
        UiCase.run(ctx, "1683-ui-painted-video", PaintedScenesSuite::video);
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
    private record Shot(String name, FontsMainMenuSuite.Pic a, FontsMainMenuSuite.Pic b, int hero, long[] alloc, int tiles) {
        double moved() {
            return FontsMainMenuSuite.changed(a.img(), b.img());
        }

        /** Bytes per drawn part: the batch's rectangles plus, for the picture-based Milky Way, the cape's tiles (each a
         *  blit of its own, so a part like a rectangle). */
        double bytesPerQuad() {
            return alloc[0] / (double) Math.max(1, alloc[2] + (long) tiles * alloc[1]);
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
        // MilkyWayTexScene's cape tiles are blits, not batch rectangles
        int tiles = "MILKY_WAY_HERO".equals(scene) ? hero : 0;
        Shot s = new Shot(label, a, b, hero, alloc, tiles);
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
            // with the character her cape flutters as tiles of the picture
            c.check(hero.hero() > 20, "MILKY_WAY_HERO's cape did not move: " + hero.hero() + " tiles");
            c.check(plain.hero() == 0, "MILKY_WAY drew a character: " + plain.hero() + " rects");
            c.check(stars.hero() == 0, "STARS drew a character: " + stars.hero() + " rects");
            for (Shot s : new Shot[]{plain, hero}) {
                c.check(s.a().lum() < 0.25, s.name() + " is not dark: lum " + s.a().lum());
                // a calm sky: twinkling stars, haze, a meteor now and then and (with her) the cape; 0.02-0.03% measured
                // over 4 s on the picture-based scene (2026-10-16), where STARS drifts 0.17%
                c.check(s.moved() > 0.0001, String.format(Locale.ROOT, "%s did not move over 4 s: %.3f%%", s.name(), s.moved() * 100));
                c.check(titleLum(s.a().img()) < 0.09, String.format(Locale.ROOT, "%s is bright behind the title: %.3f",
                        s.name(), titleLum(s.a().img())));
                // Since mod fps-gui GuiRects shares one pose copy between parts drawn under the same pose, so a part costs
                // ~29 B; the Milky Way's soft dots and crosses are placed at sub-pixel positions under their own pose, and
                // each of those poses is one 40 B matrix: 39.6 against STARS' 28.9 measured 2026-10-16. Allowed: 16 B.
                c.check(s.bytesPerQuad() <= stars.bytesPerQuad() * 1.15 + 16, String.format(Locale.ROOT,
                        "%s allocates beyond its rectangles: %.1f B/quad against STARS' %.1f", s.name(), s.bytesPerQuad(),
                        stars.bytesPerQuad()));
            }
            double vsStars = FontsMainMenuSuite.changed(plain.a().img(), stars.a().img());
            c.check(vsStars > 0.05, String.format(Locale.ROOT, "the Milky Way looks like the old starfield: %.1f%% differ", vsStars * 100));
            // killer560's picture graded black and white ("mainly black and white"): no channel far from the others
            for (Shot s : new Shot[]{plain, hero}) {
                double spread = Math.max(Math.abs(s.a().r() - s.a().g()), Math.max(Math.abs(s.a().g() - s.a().b()),
                        Math.abs(s.a().r() - s.a().b())));
                c.check(spread < 6, String.format(Locale.ROOT, "%s is not black and white: mean rgb %.1f,%.1f,%.1f",
                        s.name(), s.a().r(), s.a().g(), s.a().b()));
            }
            // with and without her: the same sky, so they differ where she sits (on the hill at the lower left) and
            // nowhere much else
            double right = changedIn(hero.a().img(), plain.a().img(), 0.232, 0.70, 0.322, 0.825);
            double left = changedIn(hero.a().img(), plain.a().img(), 0.55, 0.30, 0.95, 0.60);
            c.note(String.format(Locale.ROOT, "with/without her: %.2f%% of her box differs, %.3f%% of the left side", right * 100, left * 100));
            c.check(right > 0.03, String.format(Locale.ROOT, "the character is not where she sits: %.2f%% differ", right * 100));
            c.check(left < 0.01, String.format(Locale.ROOT, "with and without the character differ away from her: %.3f%%", left * 100));
        });
    }

    // ---- 1681 ----------------------------------------------------------------------------------------------------------

    static void target(UiCase c) throws Exception {
        around(c, true, () -> {
            String old = MenuACases.themeName();
            try {
                c.onClient(mc -> {
                    Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", FontsMainMenuSuite.menuTheme("GREEN"));
                    FontsMainMenuSuite.setMm(mc, "MATCH", "GREEN", "AUTO", 100, true);
                    Mod.staticCall(SCENES, "setGreenSceneForTest", "TARGET");
                    McCompat.setScreen(mc, new TitleScreen());
                    return null;
                });
                c.ticks(40);
                FontsMainMenuSuite.Pic first = null;
                for (double ms : new double[]{2_000_000, 2_001_700, 2_004_250}) {
                    clock(c, ms);
                    Path pa = FontsMainMenuSuite.shot(c, "target-" + (long) ms);
                    copy(pa, "target-" + (long) ms);
                    if (first == null) {
                        first = FontsMainMenuSuite.pic(pa);
                    }
                }
                int tiles = heroRects(c);
                c.note("TARGET: ripple tiles " + tiles + ", rgb " + Math.round(first.r()) + "," + Math.round(first.g()) + ","
                        + Math.round(first.b()));
                c.check(tiles > 50, "TARGET's cape and hair do not ripple: " + tiles + " tiles");
                c.check(first.g() > first.r() + 4, "TARGET is not green: " + first);
            } finally {
                c.onClient(mc -> {
                    Mod.staticCall(SCENES, "setGreenSceneForTest", "");
                    Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", Mod.enumValue("gui.MenuTheme", old));
                    return null;
                });
            }
        });
    }

    // ---- 1683 ----------------------------------------------------------------------------------------------------------

    static void gating(UiCase c) throws Exception {
        String[][] themes = {{"DARK", "STARS", "MILKY_WAY_HERO", "darkScene"}};
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
                    c.check(preview == (hero > 0), (preview ? "with" : "without") + " previewScenes " + th[0]
                            + " drew " + hero + " character rects");
                }
            });
        }
    }

    // ---- 1683 ----------------------------------------------------------------------------------------------------------

    static void video(UiCase c) throws Exception {
        if (!System.getProperty("testkit.scenario", "").contains("1683")) {
            c.note("skipped: the video only runs when 1683 is named");
            return;
        }
        // -PpaintedScene=<one or a comma list>; default all four new scenes, -PpaintedFrames=N (default 480 = 8 s)
        for (String scene : System.getProperty("testkit.paintedScene", "MILKY_WAY,MILKY_WAY_HERO").split(",")) {
            record(c, scene.trim());
        }
    }

    private static void record(UiCase c, String scene) throws Exception {
        String theme = "DARK";
        String hook = "setDarkSceneForTest";
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

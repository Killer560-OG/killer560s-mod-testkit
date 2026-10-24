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
 * {@code mainmenu.PictureScene}, which was generalized from Green's TARGET scene. Every case runs at 1920x1080 GUI 2 with the
 * clock held, and turns {@code killer560smod.previewScenes} on in this JVM where it needs the new scenes (off again after).
 * <ul>
 *   <li>1680 Milky Way: MILKY_WAY_HERO's cape moves (its ripple tiles counted) and MILKY_WAY draws no character; both are
 *       dark and black and white, a different picture from the old STARS, move over 4 s, keep the title band dark, and
 *       allocate no more per drawn part than STARS; with and without differ where she sits and nowhere much else.</li>
 *   <li>1681 Green's TARGET through the pipeline: three frames at fixed clocks ({@code TESTKIT_PAINTED_SHOTS} copies them
 *       as target-*.png, for a pixel diff against main's jar), its cape and hair ripple, and it is still green.</li>
 *   <li>1682 gating: Dark draws MILKY_WAY_HERO with her with and without previewScenes (released to every instance,
 *       mod simple-scenes-1016; before it, STARS without).</li>
 *   <li>1684 Light's Soft Light drawn smooth (killer560: "can you smooth out the light one"): without previewScenes the
 *       blocky scene, with it the smooth one; both light, both moving, the same picture in tone (mean color within a few
 *       levels) but drawn differently, the smooth one's side vignette has no strips (its calmest row steps at most 2 levels between neighbors, the
 *       blocky one 3 or more) and its rays still show.</li>
 *   <li>1683 video (only when named): ~8 s at {@code -PpaintedFps} (default 60) of each of {@code -PpaintedScene} (default
 *       MILKY_WAY, MILKY_WAY_HERO; SOFT_LIGHT and TARGET too) into {@code <report>/painted-<scene>/}.</li>
 *   <li>1684 also (light-hq-1016, "super high quality"): the smooth scene is drawn by the mod's scene shaders (their probe
 *       counts elements), and its gradients have no bands: in the calm sky no row is a line where most columns step to
 *       the next 8-bit level together (dithered), where the GuiRects version of the same scene steps every column at once.</li>
 *   <li>1685 title-screen frame rate (light-hq-1016, "120fps"): frames counted over 3 s of wall time on the themed title
 *       screen and on the vanilla one, Max Framerate 120, unlimited and 30, vsync off; the themed one is no longer held to
 *       vanilla's 60 out-of-world cap (FramerateLimitTracker reads the Max Framerate, so 120 by default, and 30 when set to 30), the vanilla one still is.</li>
 *   <li>1686 Viridian high quality (light-hq-1016): its base texture is 2560x1440 and linearly filtered, its glows and fog
 *       go through the cone-glow shader, and the picture is the same as the GuiRects version's in tone.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class PaintedScenesSuite implements FabricClientGameTest {

    static final String[] CASES = {"1680-ui-painted-milkyway", "1681-ui-picture-target", "1682-ui-painted-gating",
            "1683-ui-painted-video", "1684-ui-light-smooth", "1685-ui-title-fps", "1686-ui-viridian-hq"};

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
        UiCase.run(ctx, "1684-ui-light-smooth", PaintedScenesSuite::lightSmooth);
        UiCase.run(ctx, "1685-ui-title-fps", PaintedScenesSuite::titleFps);
        UiCase.run(ctx, "1686-ui-viridian-hq", PaintedScenesSuite::viridianHq);
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

        double perFrame() {
            return alloc[0] / (double) Math.max(1, alloc[1]);
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
            }
            // Rectangles: the Milky Way's batch costs what STARS' does per rectangle.
            c.check(plain.bytesPerQuad() <= stars.bytesPerQuad() * 1.15, String.format(Locale.ROOT,
                    "%s allocates beyond its rectangles: %.1f B/quad against STARS' %.1f", plain.name(), plain.bytesPerQuad(),
                    stars.bytesPerQuad()));
            // The cape: what the character adds a frame, per tile, against a vanilla blit's own render state. Not B/quad
            // against STARS: fps-gui-1015 made a batched rectangle ~29 B instead of ~160, a blit kept its ~213, so the
            // blended figure rose to 43 with nothing new allocated (1016b: 164.5 vs 160.5, 1016c: 43.0 vs 28.7 B/quad -
            // 190 KB a frame over the plain scene both times, 213 B a tile).
            double perTile = (hero.perFrame() - plain.perFrame()) / Math.max(1, hero.tiles());
            c.note(String.format(Locale.ROOT, "milkyway-char: %.0f B a frame over milkyway-nochar, %.0f B per cape tile",
                    hero.perFrame() - plain.perFrame(), perTile));
            c.check(perTile <= 320, String.format(Locale.ROOT, "%s allocates beyond its rectangles and tiles: %.0f B per cape"
                    + " tile a frame (a blit's render state is ~213)", hero.name(), perTile));
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

    // ---- 1684 ----------------------------------------------------------------------------------------------------------

    static void lightSmooth(UiCase c) throws Exception {
        Shot[] shots = new Shot[2];
        for (int k = 0; k < 2; k++) {
            boolean preview = k == 1;
            int idx = k;
            // The smooth Soft Light is released everywhere (mod simple-scenes-1016); the blocky one is behind a test hook.
            setOldSoftLight(c, !preview);
            try {
                around(c, preview, () -> shots[idx] = measure(c, "LIGHT", null, null, preview ? "light-smooth" : "light-blocky"));
            } finally {
                setOldSoftLight(c, false);
            }
        }
        Shot blocky = shots[0];
        Shot smooth = shots[1];
        // light-hq-1016: the smooth scene through the scene shaders, and the same scene through GuiRects for the band check
        long[] el = new long[2];
        Shot[] rects = new Shot[1];
        around(c, true, () -> {
            el[0] = shaderElements(c);
            title(c, "LIGHT", null, null);
            clock(c, 2_000_000);
            c.ticks(3);
            el[1] = shaderElements(c);
        });
        c.note("scene shader elements over the smooth Soft Light's frames: " + (el[1] - el[0]) + (el[0] < 0 ? " (no SceneShaders)" : ""));
        c.check(el[0] >= 0 && el[1] > el[0], "the smooth Soft Light is not drawn by the scene shaders");
        setShaders(c, false);
        try {
            around(c, true, () -> rects[0] = measure(c, "LIGHT", null, null, "light-rects"));
        } finally {
            setShaders(c, true);
        }
        double bandS = bandEdge(smooth.a().img());
        double bandR = bandEdge(rects[0].a().img());
        double bandB = bandEdge(blocky.a().img());
        c.note(String.format(Locale.ROOT, "sharpest band edge in the calm sky (share of columns stepping together between two rows):"
                + " shader %.2f, GuiRects smooth %.2f, blocky %.2f", bandS, bandR, bandB));
        c.check(bandR >= 0.8, "the instrument found no band edges in the GuiRects gradient (" + bandR + ") - it measures nothing");
        c.check(bandS <= 0.5, "the shader Soft Light still bands: " + bandS + " of the columns step together at one row");
        double toneR = Math.abs(rects[0].a().r() - smooth.a().r()) + Math.abs(rects[0].a().g() - smooth.a().g())
                + Math.abs(rects[0].a().b() - smooth.a().b());
        c.note(String.format(Locale.ROOT, "shader vs GuiRects smooth: tone %.2f levels apart", toneR));
        c.check(toneR < 3, "the shader Soft Light is not the GuiRects one in tone: " + toneR + " levels apart");
        for (Shot sh : shots) {
            c.check(sh.a().lum() > 0.55, sh.name() + " is not light: " + sh.a().lum());
            c.check(sh.moved() > 0.0005, String.format(Locale.ROOT, "%s did not move over 4 s: %.3f%%", sh.name(), sh.moved() * 100));
        }
        double tone = Math.abs(blocky.a().r() - smooth.a().r()) + Math.abs(blocky.a().g() - smooth.a().g())
                + Math.abs(blocky.a().b() - smooth.a().b());
        double differ = FontsMainMenuSuite.changed(blocky.a().img(), smooth.a().img());
        c.note(String.format(Locale.ROOT, "light: tone difference %.1f levels, %.2f%% of pixels differ by >30", tone, differ * 100));
        c.check(tone < 12, "the smooth Soft Light is not the same picture in tone: " + tone + " levels apart");
        c.check(differ > 0.0005 || smooth.alloc()[2] != blocky.alloc()[2], "the smooth Soft Light draws the same as the blocky one");
        // The side vignette: the blocky one is ten strips of falling alpha (a few levels' step at each), the smooth one a
        // true gradient (at most a level or two between neighbors). Rows that a drifting light crosses read high either
        // way, so the measure is the calmest row's largest step.
        int bStep = vignetteStep(blocky.a().img());
        int sStep = vignetteStep(smooth.a().img());
        c.note("calmest row's largest step across the left vignette: blocky " + bStep + ", smooth " + sStep + " levels");
        c.check(bStep >= 3, "the instrument found no strips in the blocky vignette (" + bStep + " levels) - it measures nothing");
        c.check(sStep <= 2, "the smooth Soft Light's vignette still steps: " + sStep + " levels");
        // The rays still show: the first ray's middle is brighter than the sky beside it, down the screen.
        double rayB = rayContrast(blocky.a().img());
        double rayS = rayContrast(smooth.a().img());
        c.note(String.format(Locale.ROOT, "first ray against the sky beside it: blocky %+.2f, smooth %+.2f levels", rayB, rayS));
        c.check(rayS > 0.5, String.format(Locale.ROOT, "the smooth Soft Light lost its rays: %+.2f levels", rayS));
    }

    /** Draws Light's old blocky Soft Light (mod simple-scenes-1016's hook); nothing on a jar without it, where the blocky one
     *  is what shows without previewScenes. */
    static void setOldSoftLight(UiCase c, boolean on) {
        c.onClient(mc -> {
            try {
                Mod.setField(Mod.ROOT + SCENES, "oldSoftLightForTest", on);
            } catch (Throwable ignored) {
            }
            return null;
        });
    }

    /** The scene shaders' element probe (mod light-hq-1016), or -1 when the jar has none. */
    static long shaderElements(UiCase c) {
        return c.onClient(mc -> {
            try {
                return ((Number) Mod.field("mainmenu.SceneShaders", "elements")).longValue();
            } catch (Throwable t) {
                return -1L;
            }
        });
    }

    /** Turns the scene shaders on or off (their test hook); nothing when the jar has none. */
    static void setShaders(UiCase c, boolean on) {
        c.onClient(mc -> {
            try {
                Mod.setField("mainmenu.SceneShaders", "enabledForTest", on);
            } catch (Throwable ignored) {
            }
            return null;
        });
    }

    /**
     * Banding: in the calm sky at the right (x 80-95%, rows 30-70%: no rays, the vignette constant down a column), for each
     * pair of neighboring rows the share of columns whose green level stepped down (or up) between them; the largest share.
     * A smooth vertical gradient rounded to 8 bits steps every column at the same row - a band edge, a share near 1. Dithered,
     * each column steps at its own rows (a level held for a while is still fine; what shows is a line), so no row gathers more
     * than a fraction of them (~0.33 measured). A drifting light crossing the box only lowers the share.
     */
    static double bandEdge(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int x0 = w * 80 / 100;
        int x1 = w * 95 / 100;
        double best = 0;
        for (int y = h * 30 / 100; y < h * 70 / 100 - 1; y++) {
            int dn = 0;
            int up = 0;
            for (int x = x0; x < x1; x++) {
                int a = (img.getRGB(x, y) >> 8) & 0xFF;
                int b = (img.getRGB(x, y + 1) >> 8) & 0xFF;
                if (b < a) {
                    dn++;
                } else if (b > a) {
                    up++;
                }
            }
            best = Math.max(best, Math.max(dn, up) / (double) (x1 - x0));
        }
        return best;
    }

    // ---- 1685 ----------------------------------------------------------------------------------------------------------

    /** Frames rendered over {@code seconds} of wall time (FrameClock counts every GameRenderer.render). */
    static double fps(UiCase c, double seconds) {
        // vanilla's AFK throttle (30 fps after 60 s with no input, Inactivity FPS Limit "AFK") is untouched and would read
        // as the menu's cap after a long session: count as input first
        c.onClient(mc -> {
            mc.getFramerateLimitTracker().onInputReceived();
            return null;
        });
        c.ticks(5);
        long f0 = dev.testkit.harness.FrameClock.frameCount();
        long t0 = System.nanoTime();
        while (System.nanoTime() - t0 < seconds * 1e9) {
            c.ticks(1);
        }
        long f1 = dev.testkit.harness.FrameClock.frameCount();
        return (f1 - f0) / ((System.nanoTime() - t0) / 1e9);
    }

    static void titleFps(UiCase c) throws Exception {
        int[] opt0 = c.onClient(mc -> new int[]{mc.options.framerateLimit().get(), mc.options.enableVsync().get() ? 1 : 0});
        try {
            around(c, true, () -> {
                c.onClient(mc -> {
                    mc.options.enableVsync().set(false);
                    return null;
                });
                StringBuilder sb = new StringBuilder();
                // 30: a Max Framerate below 120 is kept as set (killer560, 2026-10-16: "if below 120 is set then have it
                // restrict to whatever they have it set at").
                int[] limits = {120, 260, 30};
                double[] themed = new double[limits.length];
                double[] vanilla = new double[limits.length];
                int[] lim = new int[limits.length * 2];
                for (int k = 0; k < limits.length; k++) {
                    int max = limits[k];
                    c.onClient(mc -> {
                        mc.options.framerateLimit().set(max);
                        return null;
                    });
                    title(c, "LIGHT", null, null);
                    lim[k * 2] = c.onClient(mc -> {
                        mc.getFramerateLimitTracker().onInputReceived(); // not the AFK cap (see fps)
                        return mc.getFramerateLimitTracker().getFramerateLimit();
                    });
                    themed[k] = fps(c, 3.0);
                    c.onClient(mc -> {
                        Mod.call(Mod.cfg(FontsMainMenuSuite.MM), "setEnabled", false);
                        McCompat.setScreen(mc, new TitleScreen());
                        return null;
                    });
                    c.ticks(20);
                    lim[k * 2 + 1] = c.onClient(mc -> {
                        mc.getFramerateLimitTracker().onInputReceived();
                        return mc.getFramerateLimitTracker().getFramerateLimit();
                    });
                    vanilla[k] = fps(c, 3.0);
                    sb.append(String.format(Locale.ROOT, "Max Framerate %s: themed title %.1f fps (limit %d), vanilla title %.1f fps (limit %d); ",
                            max == 260 ? "unlimited" : String.valueOf(max), themed[k], lim[k * 2], vanilla[k], lim[k * 2 + 1]));
                }
                c.note(sb.toString());
                c.check(lim[0] == 120 && lim[2] == 260 && lim[4] == 30, "the themed title screen's frame cap is not the Max Framerate: "
                        + lim[0] + " at 120, " + lim[2] + " at unlimited, " + lim[4] + " at 30");
                c.check(themed[2] < 40, String.format(Locale.ROOT, "the themed title screen renders %.1f fps at Max Framerate 30", themed[2]));
                c.check(lim[1] == 60 && lim[3] == 60, "the vanilla title screen's cap changed: " + lim[1] + ", " + lim[3]);
                c.check(themed[0] > 90, String.format(Locale.ROOT, "the themed title screen renders %.1f fps at Max Framerate 120", themed[0]));
                c.check(vanilla[0] < 70, String.format(Locale.ROOT, "the instrument: the vanilla title screen renders %.1f fps"
                        + " under vanilla's 60 cap - the count is not frames", vanilla[0]));
            });
        } finally {
            c.onClient(mc -> {
                mc.options.framerateLimit().set(opt0[0]);
                mc.options.enableVsync().set(opt0[1] == 1);
                return null;
            });
        }
    }

    // ---- 1686 ----------------------------------------------------------------------------------------------------------

    static void viridianHq(UiCase c) throws Exception {
        String old = MenuACases.themeName();
        try {
            Shot[] shots = new Shot[2];
            long[] el = new long[2];
            for (int k = 0; k < 2; k++) {
                boolean shaders = k == 0;
                int idx = k;
                setShaders(c, shaders);
                around(c, false, () -> {
                    c.onClient(mc -> {
                        Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", FontsMainMenuSuite.menuTheme("GREEN"));
                        FontsMainMenuSuite.setMm(mc, "MATCH", "GREEN", "AUTO", 100, true);
                        Mod.staticCall(SCENES, "setGreenSceneForTest", "TARGET");
                        McCompat.setScreen(mc, new TitleScreen());
                        return null;
                    });
                    c.ticks(40);
                    long e0 = shaderElements(c);
                    clock(c, 2_000_000);
                    Path pa = FontsMainMenuSuite.shot(c, shaders ? "viridian-hq" : "viridian-rects");
                    copy(pa, shaders ? "viridian-hq" : "viridian-rects");
                    long e1 = shaderElements(c);
                    if (shaders) {
                        el[0] = e0;
                        el[1] = e1;
                    }
                    clock(c, 2_000_000 + 1000.0 / 120);
                    FontsMainMenuSuite.Pic b = FontsMainMenuSuite.pic(FontsMainMenuSuite.shot(c, (shaders ? "viridian-hq" : "viridian-rects") + "-next"));
                    shots[idx] = new Shot(shaders ? "viridian-hq" : "viridian-rects", FontsMainMenuSuite.pic(pa), b, heroRects(c),
                            new long[]{0, 1, 1}, 0);
                });
            }
            setShaders(c, true);
            int[] size = c.onClient(mc -> {
                try {
                    Object tex = mc.getTextureManager().getTexture(net.minecraft.resources.Identifier.fromNamespaceAndPath(
                            "killer560smod", "textures/gui/green_boss_target.png"));
                    Object gpu = tex.getClass().getMethod("getTexture").invoke(tex);
                    int w = (Integer) gpu.getClass().getMethod("getWidth", int.class).invoke(gpu, 0);
                    int h = (Integer) gpu.getClass().getMethod("getHeight", int.class).invoke(gpu, 0);
                    return new int[]{w, h};
                } catch (Throwable t) {
                    return new int[]{-1, -1};
                }
            });
            Shot hq = shots[0];
            Shot rects = shots[1];
            double tone = Math.abs(hq.a().r() - rects.a().r()) + Math.abs(hq.a().g() - rects.a().g())
                    + Math.abs(hq.a().b() - rects.a().b());
            c.note(String.format(Locale.ROOT, "Viridian: texture %dx%d, %d shader elements over its frames, tone %.2f levels from the"
                            + " GuiRects version, %.3f%% of pixels moved in 1/120 s, ripple tiles %d", size[0], size[1], el[1] - el[0],
                    tone, hq.moved() * 100, hq.hero()));
            c.check(size[0] >= 2560 && size[1] >= 1440, "Viridian's texture is " + size[0] + "x" + size[1] + ", not 2560x1440");
            c.check(el[0] >= 0 && el[1] > el[0], "Viridian's glows and fog are not drawn by the cone-glow shader");
            c.check(tone < 4, "Viridian through the shaders is not the same picture in tone: " + tone + " levels apart");
            c.check(hq.a().g() > hq.a().r() + 4, "Viridian is not green: " + hq.a());
            c.check(hq.hero() > 50, "Viridian's cape and hair do not ripple: " + hq.hero() + " tiles");
        } finally {
            setShaders(c, true);
            c.onClient(mc -> {
                Mod.staticCall(SCENES, "setGreenSceneForTest", "");
                Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", Mod.enumValue("gui.MenuTheme", old));
                return null;
            });
        }
    }

    /** Over rows of the left sixth of the screen: each row's largest step between neighboring pixels (summed RGB / 3);
     *  the smallest of those over the rows. */
    static int vignetteStep(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int best = Integer.MAX_VALUE;
        for (int y = h / 4; y < h * 3 / 4; y += 7) {
            int max = 0;
            int prev = -1;
            for (int x = 2; x < w / 6 - 2; x++) {
                int p = img.getRGB(x, y);
                int v = (((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF)) / 3;
                if (prev >= 0) {
                    max = Math.max(max, Math.abs(v - prev));
                }
                prev = v;
            }
            best = Math.min(best, max);
        }
        return best;
    }

    /** The first ray (x = 0.05 w + 0.55 y + its half width): mean of (middle - average of 40 px either side), rows 25-60%. */
    static double rayContrast(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        double scale = w / 960.0;
        double sum = 0;
        int n = 0;
        for (int y = h / 4; y < h * 6 / 10; y += 3) {
            int mid = (int) (w * 0.05 + 8 * scale + y * 0.55);
            if (mid + 45 >= w) {
                continue;
            }
            sum += lum255(img.getRGB(mid, y)) - (lum255(img.getRGB(mid - 40, y)) + lum255(img.getRGB(mid + 40, y))) / 2;
            n++;
        }
        return n == 0 ? 0 : sum / n;
    }

    static double lum255(int p) {
        return (((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF)) / 3.0;
    }

    // ---- 1682 ----------------------------------------------------------------------------------------------------------

    static void gating(UiCase c) throws Exception {
        String[][] themes = {{"DARK", "STARS", "MILKY_WAY_HERO", "darkScene"}};
        for (boolean preview : new boolean[]{false, true}) {
            around(c, preview, () -> {
                for (String[] th : themes) {
                    title(c, th[0], null, null);
                    clock(c, 2_000_000);
                    String scene = c.onClient(mc -> String.valueOf(Mod.staticCall(SCENES, th[3])));
                    int hero = heroRects(c);
                    // Released to every instance (mod simple-scenes-1016): the Milky Way with her, flag or no flag.
                    String want = th[2];
                    c.note((preview ? "preview: " : "released: ") + th[0] + " draws " + scene + ", hero " + hero + " rects");
                    c.check(want.equals(scene), (preview ? "with" : "without") + " previewScenes " + th[0] + " draws " + scene
                            + ", not " + want);
                    c.check(hero > 0, (preview ? "with" : "without") + " previewScenes " + th[0]
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
        // -PpaintedScene=<one or a comma list>; default all four new scenes, -PpaintedFps=N (default 60; 120 for the high
        // frame rate clips), -PpaintedFrames=N (default 8 s of frames)
        for (String scene : System.getProperty("testkit.paintedScene", "MILKY_WAY,MILKY_WAY_HERO").split(",")) {
            record(c, scene.trim());
        }
    }

    private static void record(UiCase c, String scene) throws Exception {
        boolean lightScene = scene.equals("SOFT_LIGHT");
        boolean target = scene.equals("TARGET");
        String theme = lightScene ? "LIGHT" : "DARK";
        String hook = lightScene ? null : "setDarkSceneForTest";
        double fpsRec = Double.parseDouble(System.getProperty("testkit.paintedFps", "60"));
        String old = MenuACases.themeName();
        around(c, true, () -> {
            if (target) {
                c.onClient(mc -> {
                    Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", FontsMainMenuSuite.menuTheme("GREEN"));
                    FontsMainMenuSuite.setMm(mc, "MATCH", "GREEN", "AUTO", 100, true);
                    Mod.staticCall(SCENES, "setGreenSceneForTest", "TARGET");
                    McCompat.setScreen(mc, new TitleScreen());
                    return null;
                });
                c.ticks(40);
            } else {
                title(c, theme, hook, scene);
            }
            int frames = Integer.parseInt(System.getProperty("testkit.paintedFrames", String.valueOf((int) Math.round(8 * fpsRec))));
            Path dir = Report.dir().resolve("painted-" + scene.toLowerCase(Locale.ROOT));
            for (int i = 0; i < frames; i++) {
                double ms = 3_000_000 + i * (1000.0 / fpsRec);
                c.onClient(mc -> {
                    Mod.staticCall(SCENES, "setClockForTest", ms);
                    return null;
                });
                c.ticks(1);
                MenuAnimSuite.keep(c.ctx().takeScreenshot("painted-frame"), dir, String.format(Locale.ROOT, "frame_%05d.png", i));
            }
            c.note("wrote " + frames + " frames at " + fpsRec + " fps to " + dir);
            if (target) {
                c.onClient(mc -> {
                    Mod.staticCall(SCENES, "setGreenSceneForTest", "");
                    Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", Mod.enumValue("gui.MenuTheme", old));
                    return null;
                });
            }
        });
    }
}

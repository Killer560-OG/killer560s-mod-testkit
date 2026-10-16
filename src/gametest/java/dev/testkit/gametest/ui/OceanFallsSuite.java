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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 1690-1693: Ocean's Cavern Falls (mod ocean-waterfall-1016). killer560, 2026-10-16, with his waterfall picture: "can you do
 * a similar thing with this new waterfall one" - the picture cleaned of its baked-in menu and animated by
 * {@code mainmenu.OceanFallsScene} on {@code PictureScene}, shown only with previewScenes (the Title Screen GUI instance).
 * Every case runs at 1920x1080 GUI 2 with the clock held.
 * <ul>
 *   <li>1690 the scene: CAVERN_FALLS is teal, a different picture from the old WATERFALL, moves over 1 s and over 4 s (its
 *       streaks fall: the open fall changes more than the cliffs), the white title and the button text keep their
 *       contrast, and it allocates no more per drawn part than WATERFALL.</li>
 *   <li>1691 the water really pours (ocean-waterfall-2): every sixtieth of a second several percent of the fall changes,
 *       drawn through the falls shader.</li>
 *   <li>1692 gating: without previewScenes Ocean draws WATERFALL; with it CAVERN_FALLS; the Title Screen Switcher lists
 *       "Ocean · Waterfall" and "Ocean · Cavern Falls".</li>
 *   <li>1693 video (only when named): {@code -PpaintedFrames} (default 600 = 10 s) at 60 fps into
 *       {@code <report>/painted-cavern_falls/}; {@code TESTKIT_PAINTED_SHOTS} also gets {@code ocean-falls.png}.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class OceanFallsSuite implements FabricClientGameTest {

    static final String[] CASES = {"1690-ui-ocean-falls", "1691-ui-ocean-falls-smooth", "1692-ui-ocean-falls-gating",
            "1693-ui-ocean-falls-video"};

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
        UiCase.run(ctx, "1690-ui-ocean-falls", OceanFallsSuite::scene);
        UiCase.run(ctx, "1691-ui-ocean-falls-smooth", OceanFallsSuite::smooth);
        UiCase.run(ctx, "1692-ui-ocean-falls-gating", OceanFallsSuite::gating);
        UiCase.run(ctx, "1693-ui-ocean-falls-video", OceanFallsSuite::video);
    }

    // ---- shared ---------------------------------------------------------------------------------------------------------

    private interface Body {
        void run() throws Exception;
    }

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
                Mod.staticCall(SCENES, "setOceanSceneForTest", "");
                Mod.staticCall(SCENES, "setClockForTest", -1.0);
                return null;
            });
            FontsMainMenuSuite.setWindow(c, win0[0], win0[1], win0[2]);
            FontsMainMenuSuite.restoreMm(c, saved);
        }
    }

    /** The title screen in Ocean (Separate style) showing {@code scene}; "" or null = the default. */
    private static void title(UiCase c, String scene) {
        c.onClient(mc -> {
            FontsMainMenuSuite.setMm(mc, "SEPARATE", "OCEAN", "AUTO", 100, true);
            if (scene != null) {
                Mod.staticCall(SCENES, "setOceanSceneForTest", scene);
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

    private static FontsMainMenuSuite.Pic at(UiCase c, double ms, String label) throws Exception {
        clock(c, ms);
        Path p = FontsMainMenuSuite.shot(c, label);
        copy(p, label);
        return FontsMainMenuSuite.pic(p);
    }

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

    /** Luminances of a box (fractions of the image), sorted. */
    static double[] lums(BufferedImage img, double x0, double y0, double x1, double y1) {
        List<Double> v = new ArrayList<>();
        for (int y = (int) (img.getHeight() * y0); y < (int) (img.getHeight() * y1); y += 2) {
            for (int x = (int) (img.getWidth() * x0); x < (int) (img.getWidth() * x1); x += 2) {
                v.add(GreenThemeSuite.lum709(img.getRGB(x, y)));
            }
        }
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = v.get(i);
        }
        java.util.Arrays.sort(a);
        return a;
    }

    static double contrast(double hi, double lo) {
        return (hi + 0.05) / (lo + 0.05);
    }

    // ---- 1690 ----------------------------------------------------------------------------------------------------------

    static void scene(UiCase c) throws Exception {
        around(c, true, () -> {
            title(c, "WATERFALL");
            FontsMainMenuSuite.Pic old = at(c, 2_000_000, "ocean-waterfall-old");
            long[] oldAlloc = GreenThemeSuite.alloc(c);
            title(c, "CAVERN_FALLS");
            FontsMainMenuSuite.Pic a = at(c, 2_000_000, "ocean-falls");
            FontsMainMenuSuite.Pic b1 = at(c, 2_001_000, "ocean-falls-1s");
            FontsMainMenuSuite.Pic b4 = at(c, 2_004_000, "ocean-falls-4s");
            long[] alloc = GreenThemeSuite.alloc(c);
            String scene = c.onClient(mc -> String.valueOf(Mod.staticCall(SCENES, "oceanScene")));
            c.check("CAVERN_FALLS".equals(scene), "Ocean draws " + scene + ", not CAVERN_FALLS");
            c.check(alloc[1] >= 50 && alloc[2] > 0 && oldAlloc[1] >= 50, "the allocation probe did not run");
            double qpf = alloc[2] / (double) alloc[1];
            double bpq = alloc[0] / (double) Math.max(1, alloc[2]);
            double oldBpq = oldAlloc[0] / (double) Math.max(1, oldAlloc[2]);
            double moved1 = FontsMainMenuSuite.changed(a.img(), b1.img());
            double moved4 = FontsMainMenuSuite.changed(a.img(), b4.img());
            // the open fall (between the cliffs, above the mist) against the cliffs at the far left and right
            double fall = PaintedScenesSuite.changedIn(a.img(), b1.img(), 0.20, 0.17, 0.30, 0.75);
            double fallR = PaintedScenesSuite.changedIn(a.img(), b1.img(), 0.66, 0.17, 0.78, 0.68);
            double cliffs = PaintedScenesSuite.changedIn(a.img(), b1.img(), 0.0, 0.05, 0.10, 0.45);
            double lake = PaintedScenesSuite.changedIn(a.img(), b1.img(), 0.50, 0.87, 0.98, 0.99);
            double vsOld = FontsMainMenuSuite.changed(a.img(), old.img());
            double titleLum = PaintedScenesSuite.titleLum(a.img());
            double[] buttons = lums(a.img(), 0.40, 0.30, 0.60, 0.62);
            double face = buttons[buttons.length / 2];
            double text = buttons[(int) (buttons.length * 0.985)];
            c.note(String.format(Locale.ROOT, "CAVERN_FALLS: rgb %.0f,%.0f,%.0f lum %.3f; moved %.2f%% over 1 s, %.2f%% over 4 s;"
                            + " fall %.2f%% / %.2f%%, cliffs %.2f%%, lake %.2f%%; %.1f%% differs from WATERFALL; title band lum"
                            + " %.3f; buttons face %.3f text %.3f (%.1f:1); %.0f quads/frame, %.1f B/quad (WATERFALL %.1f)",
                    a.r(), a.g(), a.b(), a.lum(), moved1 * 100, moved4 * 100, fall * 100, fallR * 100, cliffs * 100,
                    lake * 100, vsOld * 100, titleLum, face, text, contrast(text, face), qpf, bpq, oldBpq));
            // his palette: dark teal - green and blue well over red, and dark overall
            c.check(a.g() > a.r() + 20 && a.b() > a.r() + 20, "CAVERN_FALLS is not teal: " + a);
            c.check(a.lum() < 0.35, "CAVERN_FALLS is not dark: lum " + a.lum());
            c.check(vsOld > 0.20, String.format(Locale.ROOT, "CAVERN_FALLS looks like the old waterfall: %.1f%% differ", vsOld * 100));
            // it moves, and the moving is the falling water, not the whole view (no sway)
            c.check(moved1 > 0.004, String.format(Locale.ROOT, "CAVERN_FALLS did not move over 1 s: %.2f%%", moved1 * 100));
            c.check(moved4 > 0.004, String.format(Locale.ROOT, "CAVERN_FALLS did not move over 4 s: %.2f%%", moved4 * 100));
            c.check(fall > 0.01 && fallR > 0.01, String.format(Locale.ROOT, "the streaks do not fall: %.2f%% / %.2f%% of the fall"
                    + " changed in 1 s", fall * 100, fallR * 100));
            c.check(cliffs < fall * 0.5, String.format(Locale.ROOT, "the cliffs move like the water (a sway?): %.2f%% against"
                    + " %.2f%%", cliffs * 100, fall * 100));
            c.check(lake > 0.0005, String.format(Locale.ROOT, "nothing moves on the lake: %.3f%%", lake * 100));
            // the white title and the buttons' text stay readable on it
            c.check(contrast(1.0, titleLum) >= 3.0, String.format(Locale.ROOT, "the title band is too bright for the white"
                    + " title: lum %.3f", titleLum));
            c.check(contrast(text, face) >= 3.0, String.format(Locale.ROOT, "the button text is hard to read: %.3f on %.3f",
                    text, face));
            // a part costs what the old waterfall's do
            c.check(bpq <= oldBpq * 1.15, String.format(Locale.ROOT, "CAVERN_FALLS allocates beyond its rectangles: %.1f B/quad"
                    + " against WATERFALL's %.1f", bpq, oldBpq));
        });
    }

    // ---- 1691 ----------------------------------------------------------------------------------------------------------

    static void smooth(UiCase c) throws Exception {
        around(c, true, () -> {
            title(c, "CAVERN_FALLS");
            FontsMainMenuSuite.Pic prev = at(c, 2_100_000, "ocean-smooth-0");
            double most = 0;
            double least = 1;
            for (int i = 1; i <= 6; i++) {
                FontsMainMenuSuite.Pic next = at(c, 2_100_000 + i * (1000.0 / 60.0), "ocean-smooth-" + i);
                double d = PaintedScenesSuite.changedIn(prev.img(), next.img(), 0.20, 0.17, 0.80, 0.75);
                most = Math.max(most, d);
                least = Math.min(least, d);
                prev = next;
            }
            c.note(String.format(Locale.ROOT, "frame to frame (1/60 s) the fall changes %.2f%%..%.2f%%", least * 100, most * 100));
            // killer560 after the first clip (2026-10-16): "the water in that looks still" - it changed 0.08-0.10% a frame.
            // Now the whole curtain pours: several percent of the fall changes every frame.
            c.check(least > 0.02, String.format(Locale.ROOT, "the water does not pour: only %.2f%% of the fall changed in a"
                    + " frame", least * 100));
            boolean shader = c.onClient(mc -> (Boolean) Mod.staticCall("mainmenu.OceanFallsScene", "shaderActive"));
            long frames = c.onClient(mc -> (Long) Mod.field(Mod.ROOT + "mainmenu.OceanFallsScene", "shaderFrames"));
            c.note("shader active " + shader + ", " + frames + " frames through it");
            c.check(shader && frames > 0, "the falls shader did not draw: active " + shader + ", " + frames + " frames");
        });
    }

    // ---- 1692 ----------------------------------------------------------------------------------------------------------

    static void gating(UiCase c) throws Exception {
        for (boolean preview : new boolean[]{false, true}) {
            around(c, preview, () -> {
                title(c, null);
                clock(c, 2_000_000);
                String scene = c.onClient(mc -> String.valueOf(Mod.staticCall(SCENES, "oceanScene")));
                String want = preview ? "CAVERN_FALLS" : "WATERFALL";
                c.note((preview ? "preview: " : "released: ") + "Ocean draws " + scene);
                c.check(want.equals(scene), (preview ? "with" : "without") + " previewScenes Ocean draws " + scene + ", not " + want);
            });
        }
        List<String> labels = c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            for (Object l : (List<?>) Mod.staticCall("mainmenu.TitleScreenLooks", "looks")) {
                out.add(String.valueOf(Mod.call(l, "label")));
            }
            return out;
        });
        c.note("switcher looks: " + labels);
        c.check(labels.contains("Ocean · Waterfall"), "the switcher lost Ocean · Waterfall: " + labels);
        c.check(labels.contains("Ocean · Cavern Falls"), "the switcher has no Ocean · Cavern Falls: " + labels);
    }

    // ---- 1693 ----------------------------------------------------------------------------------------------------------

    static void video(UiCase c) throws Exception {
        if (!System.getProperty("testkit.scenario", "").contains("1693")) {
            c.note("skipped: the video only runs when 1693 is named");
            return;
        }
        around(c, true, () -> {
            title(c, "CAVERN_FALLS");
            int frames = Integer.parseInt(System.getProperty("testkit.paintedFrames", "600"));
            Path dir = Report.dir().resolve("painted-cavern_falls");
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

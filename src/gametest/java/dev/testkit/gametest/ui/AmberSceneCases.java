package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;

import net.minecraft.client.gui.screens.TitleScreen;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * 1568-ui-amber-scene: Amber's three title-screen backgrounds, EMBERS (the old drifting embers), NEW (mainmenu/
 * AmberForgeScene, the colonnade) and SIMPLE (mainmenu/AmberCalmScene). Each renders warm (red above blue), changes with
 * the clock and stays calm enough for the menu; each is a different picture from the others (so no check judged the wrong
 * scene); the default is AURORA since mod simple-scenes-1016 (EMBERS before); none allocates beyond the batch's own rectangles (bytes per batched rectangle match the
 * old embers'); and SIMPLE draws no more rectangles than EMBERS and is no brighter behind the title.
 * 1569-ui-amber-video: ~10 s of one scene ({@code -Dtestkit.amberVideo}, default SIMPLE) at 60 fps, one PNG per 1/60 s of
 * the scene clock into {@code <report>/amber-scene/<scene>/frame_NNNNN.png}. Only runs when its number is named.
 */
final class AmberSceneCases {

    private AmberSceneCases() {
    }

    private static final String SCENES = "mainmenu.MainMenuScenes";

    /** Opens the title screen on Amber with {@code scene} ("NEW", "EMBERS", "SIMPLE"; "" = the mod's default). */
    private static void title(UiCase c, String scene) {
        c.onClient(mc -> {
            FontsMainMenuSuite.setMm(mc, "CUSTOM", "AMBER", "EMBERS", 100, true);
            Mod.staticCall(SCENES, "setAmberSceneForTest", scene);
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
    }

    private static Path shot(UiCase c, String label) {
        Path p = c.ctx().takeScreenshot(Report.fileName(c.name() + "-" + label));
        Report.screenshot(c.name(), p);
        return p;
    }

    /** {bytes, frames, quads} the scene allocated over 60 frames. */
    private static long[] alloc(UiCase c) {
        c.onClient(mc -> {
            Mod.setField(Mod.ROOT + SCENES, "allocBytes", 0L);
            Mod.setField(Mod.ROOT + SCENES, "allocFrames", 0L);
            Mod.setField(Mod.ROOT + SCENES, "allocQuads", 0L);
            Mod.setField(Mod.ROOT + SCENES, "allocProbe", true);
            return null;
        });
        for (int i = 0; i < 60; i++) {
            clock(c, 2_000_000 + i * (1000.0 / 60.0));
            c.ticks(1);
        }
        return c.onClient(mc -> {
            Mod.setField(Mod.ROOT + SCENES, "allocProbe", false);
            return new long[]{(Long) Mod.field(Mod.ROOT + SCENES, "allocBytes"), (Long) Mod.field(Mod.ROOT + SCENES, "allocFrames"),
                    (Long) Mod.field(Mod.ROOT + SCENES, "allocQuads")};
        });
    }

    /** One scene's measurements: two frames 4 s apart, the title band's luminance, and 60 frames of the probe. */
    private record Measured(String name, FontsMainMenuSuite.Pic a, double moved, double topLum, long[] alloc) {
        double quadsPerFrame() {
            return alloc[2] / (double) Math.max(1, alloc[1]);
        }

        double bytesPerQuad() {
            return alloc[0] / (double) Math.max(1, alloc[2]);
        }
    }

    private static Measured measure(UiCase c, String scene) throws Exception {
        title(c, scene);
        clock(c, 2_000_000);
        c.ticks(2);
        String label = scene.isEmpty() ? "default" : scene.toLowerCase(Locale.ROOT);
        FontsMainMenuSuite.Pic a = FontsMainMenuSuite.pic(shot(c, label + "-a"));
        clock(c, 2_004_000);
        c.ticks(2);
        FontsMainMenuSuite.Pic b = FontsMainMenuSuite.pic(shot(c, label + "-b"));
        double moved = FontsMainMenuSuite.changed(a.img(), b.img());
        long[] alloc = alloc(c);
        c.check(alloc[1] >= 50 && alloc[2] > 0, "the allocation probe did not run for " + label + ": " + Arrays.toString(alloc));
        return new Measured(label, a, moved, bandLum(a.img(), 0.0, 0.2), alloc);
    }

    /** Mean luminance (0..1) of the rows between {@code from} and {@code to} (fractions of the height). */
    private static double bandLum(BufferedImage img, double from, double to) {
        double sum = 0;
        long n = 0;
        for (int y = (int) (img.getHeight() * from); y < (int) (img.getHeight() * to); y += 2) {
            for (int x = 0; x < img.getWidth(); x += 2) {
                int p = img.getRGB(x, y);
                sum += (0.2126 * ((p >> 16) & 0xFF) + 0.7152 * ((p >> 8) & 0xFF) + 0.0722 * (p & 0xFF)) / 255.0;
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    private static void checkScene(UiCase c, Measured m) {
        FontsMainMenuSuite.Pic a = m.a();
        c.check(a.r() > a.b() + 4, String.format(Locale.ROOT, "the %s Amber scene is not warm: rgb %.0f,%.0f,%.0f",
                m.name(), a.r(), a.g(), a.b()));
        // The scene's own motion only: the splash text, which pulses on the real clock, is gone from the themed main menu
        // since mod no-splash-1015, and embers alone measured 0.10% (0.23% with the splash on main, 2026-10-16).
        c.check(m.moved() > 0.0005, String.format(Locale.ROOT, "the %s Amber scene did not move over 4 s: %.3f%% changed",
                m.name(), m.moved() * 100));
        c.check(a.lum() < 0.4, String.format(Locale.ROOT, "the %s Amber scene is too bright for the menu: lum %.2f",
                m.name(), a.lum()));
    }

    static void scene(UiCase c) throws Exception {
        FontsMainMenuSuite.MmState saved = FontsMainMenuSuite.saveMm(c);
        int[] win0 = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.options.guiScale().get()});
        try {
            FontsMainMenuSuite.setWindow(c, 1600, 900, 2);
            Measured embers = measure(c, "EMBERS");
            Measured forge = measure(c, "NEW");
            Measured simple = measure(c, "SIMPLE");
            Measured dflt = measure(c, "");
            for (Measured m : new Measured[]{embers, forge, simple}) {
                c.note(String.format(Locale.ROOT, "%s: %.0f quads/frame, %.0f B/frame, %.1f B/quad, lum %.3f, title band lum %.3f, moved %.2f%%",
                        m.name(), m.quadsPerFrame(), m.alloc()[0] / (double) m.alloc()[1], m.bytesPerQuad(), m.a().lum(),
                        m.topLum(), m.moved() * 100));
                checkScene(c, m);
            }

            // Each scene really is a different picture at the same clock, so no check above judged the wrong scene.
            double forgeVsEmbers = FontsMainMenuSuite.changed(forge.a().img(), embers.a().img());
            double simpleVsEmbers = FontsMainMenuSuite.changed(simple.a().img(), embers.a().img());
            double simpleVsForge = FontsMainMenuSuite.changed(simple.a().img(), forge.a().img());
            double dfltVsEmbers = FontsMainMenuSuite.changed(dflt.a().img(), embers.a().img());
            c.note(String.format(Locale.ROOT, "differ: new/embers %.2f%%, simple/embers %.2f%%, simple/new %.2f%%, default/embers %.3f%%",
                    forgeVsEmbers * 100, simpleVsEmbers * 100, simpleVsForge * 100, dfltVsEmbers * 100));
            c.check(forgeVsEmbers > 0.05, String.format(Locale.ROOT, "the NEW and EMBERS scenes look alike: %.1f%% differ", forgeVsEmbers * 100));
            c.check(simpleVsForge > 0.05, String.format(Locale.ROOT, "the SIMPLE and NEW scenes look alike: %.1f%% differ", simpleVsForge * 100));
            c.check(simpleVsEmbers > 0.002, String.format(Locale.ROOT, "the SIMPLE scene draws the same as EMBERS: %.3f%% differ", simpleVsEmbers * 100));
            // The default is EMBERS (killer560 preferred the simple style, 2026-10-15) until he picks. Two shots of one
            // scene at one clock still differ a little, so
            // "the same" means ten times closer to EMBERS than either other scene is.
            // Since mod simple-scenes-1016 the default is AURORA, his picture, released to every instance: a different
            // picture from the embers, and the scene the mod names.
            String dfltScene = c.onClient(mc -> {
                Mod.staticCall(SCENES, "setAmberSceneForTest", "");
                return String.valueOf(Mod.staticCall(SCENES, "amberScene"));
            });
            c.note("default Amber scene: " + dfltScene);
            c.check("AURORA".equals(dfltScene), "Amber's default scene is " + dfltScene + ", not AURORA");
            c.check(dfltVsEmbers > 0.05, String.format(Locale.ROOT,
                    "Amber's default scene draws like EMBERS: %.3f%% differ", dfltVsEmbers * 100));

            // No allocation beyond the batch's own rectangles; SIMPLE draws no more rectangles than the old embers and
            // is no brighter than they are behind the title.
            c.check(forge.bytesPerQuad() <= embers.bytesPerQuad() * 1.15, String.format(Locale.ROOT,
                    "the NEW scene allocates beyond its rectangles: %.1f B/quad against embers' %.1f", forge.bytesPerQuad(), embers.bytesPerQuad()));
            c.check(simple.bytesPerQuad() <= embers.bytesPerQuad() * 1.15, String.format(Locale.ROOT,
                    "the SIMPLE scene allocates beyond its rectangles: %.1f B/quad against embers' %.1f", simple.bytesPerQuad(), embers.bytesPerQuad()));
            c.check(simple.quadsPerFrame() <= embers.quadsPerFrame(), String.format(Locale.ROOT,
                    "the SIMPLE scene draws more rectangles than the old embers: %.0f against %.0f", simple.quadsPerFrame(), embers.quadsPerFrame()));
            c.check(simple.topLum() <= embers.topLum() + 0.01, String.format(Locale.ROOT,
                    "the SIMPLE scene is brighter behind the title than the old embers: %.3f against %.3f", simple.topLum(), embers.topLum()));
        } finally {
            reset(c);
            FontsMainMenuSuite.setWindow(c, win0[0], win0[1], win0[2]);
            FontsMainMenuSuite.restoreMm(c, saved);
        }
    }

    private static void reset(UiCase c) {
        c.onClient(mc -> {
            Mod.staticCall(SCENES, "setAmberSceneForTest", "");
            Mod.staticCall(SCENES, "setClockForTest", -1.0);
            return null;
        });
    }

    static void video(UiCase c) throws Exception {
        if (!System.getProperty("testkit.scenario", "").contains("1569")) {
            c.note("skipped: the video only runs when 1569 is named");
            return;
        }
        FontsMainMenuSuite.MmState saved = FontsMainMenuSuite.saveMm(c);
        int[] win0 = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.options.guiScale().get()});
        try {
            FontsMainMenuSuite.setWindow(c, 1920, 1080, 2);
            String scene = System.getProperty("testkit.amberVideo", "SIMPLE");
            title(c, scene);
            int frames = 600;
            Path dir = Report.dir().resolve("amber-scene").resolve(scene.toLowerCase(Locale.ROOT));
            for (int i = 0; i < frames; i++) {
                clock(c, 3_000_000 + i * (1000.0 / 60.0));
                c.ticks(1);
                MenuAnimSuite.keep(c.ctx().takeScreenshot("amber-video-frame"), dir,
                        String.format(Locale.ROOT, "frame_%05d.png", i));
            }
            c.note("wrote " + frames + " frames to " + dir);
        } finally {
            reset(c);
            FontsMainMenuSuite.setWindow(c, win0[0], win0[1], win0[2]);
            FontsMainMenuSuite.restoreMm(c, saved);
        }
    }
}

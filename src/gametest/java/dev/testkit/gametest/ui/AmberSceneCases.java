package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;

import net.minecraft.client.gui.screens.TitleScreen;

import java.nio.file.Path;
import java.util.Locale;

/**
 * 1568-ui-amber-scene: the new Amber title-screen background (mainmenu/AmberForgeScene) against the old embers.
 * It renders warm (red above blue), its frame changes with the clock, it differs from the old embers (so the case is not
 * judging the wrong scene), it stays calm enough for the menu (average brightness), and it adds no allocation of its own:
 * bytes allocated per batched rectangle match the old scene's (the rectangle parts are the batcher's, shared by all scenes).
 * 1569-ui-amber-video: ~15 s of the new scene and ~5 s of the old at 60 fps, one PNG per 1/60 s of the scene clock into
 * {@code <report>/amber-scene/new|old/frame_NNNNN.png}. Only runs when its number is named.
 */
final class AmberSceneCases {

    private AmberSceneCases() {
    }

    private static final String SCENES = "mainmenu.MainMenuScenes";

    private static void title(UiCase c, boolean oldEmbers) {
        c.onClient(mc -> {
            FontsMainMenuSuite.setMm(mc, "CUSTOM", "AMBER", "EMBERS", 100, true, true);
            Mod.staticCall(SCENES, "setOldEmbersForTest", oldEmbers);
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

    static void scene(UiCase c) throws Exception {
        FontsMainMenuSuite.MmState saved = FontsMainMenuSuite.saveMm(c);
        int[] win0 = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.options.guiScale().get()});
        try {
            FontsMainMenuSuite.setWindow(c, 1600, 900, 2);
            title(c, false);
            clock(c, 2_000_000);
            c.ticks(2);
            FontsMainMenuSuite.Pic a = FontsMainMenuSuite.pic(shot(c, "new-a"));
            clock(c, 2_004_000);
            c.ticks(2);
            FontsMainMenuSuite.Pic b = FontsMainMenuSuite.pic(shot(c, "new-b"));
            double moved = FontsMainMenuSuite.changed(a.img(), b.img());
            c.check(a.r() > a.b() + 4, String.format(Locale.ROOT, "the new Amber scene is not warm: rgb %.0f,%.0f,%.0f", a.r(), a.g(), a.b()));
            c.check(moved > 0.002, String.format(Locale.ROOT, "the new Amber scene did not move over 4 s: %.3f%% changed", moved * 100));
            c.check(a.lum() < 0.4, String.format(Locale.ROOT, "the new Amber scene is too bright for the menu: lum %.2f", a.lum()));
            long[] nw = alloc(c);

            // Old scene at the same clock must look different, so this case really switched scenes.
            title(c, true);
            clock(c, 2_000_000);
            c.ticks(2);
            FontsMainMenuSuite.Pic o = FontsMainMenuSuite.pic(shot(c, "old-a"));
            double differs = FontsMainMenuSuite.changed(a.img(), o.img());
            c.check(differs > 0.05, String.format(Locale.ROOT, "the new and old Amber scenes look alike: %.1f%% differ", differs * 100));
            long[] old = alloc(c);

            c.check(nw[1] >= 50 && old[1] >= 50 && nw[2] > 0 && old[2] > 0, "the allocation probe did not run: new "
                    + java.util.Arrays.toString(nw) + " old " + java.util.Arrays.toString(old));
            double perQuadNew = nw[0] / (double) nw[2];
            double perQuadOld = old[0] / (double) old[2];
            c.note(String.format(Locale.ROOT, "new scene: %.0f quads/frame, %.0f B/frame, %.1f B/quad (lum %.2f, moved %.2f%%); "
                            + "old embers: %.0f quads/frame, %.0f B/frame, %.1f B/quad",
                    nw[2] / (double) nw[1], nw[0] / (double) nw[1], perQuadNew, a.lum(), moved * 100,
                    old[2] / (double) old[1], old[0] / (double) old[1], perQuadOld));
            c.check(perQuadNew <= perQuadOld * 1.15, String.format(Locale.ROOT,
                    "the new scene allocates beyond its rectangles: %.1f B/quad against the old %.1f", perQuadNew, perQuadOld));
        } finally {
            reset(c);
            FontsMainMenuSuite.setWindow(c, win0[0], win0[1], win0[2]);
            FontsMainMenuSuite.restoreMm(c, saved);
        }
    }

    private static void reset(UiCase c) {
        c.onClient(mc -> {
            Mod.staticCall(SCENES, "setOldEmbersForTest", false);
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
            for (boolean old : new boolean[]{false, true}) {
                title(c, old);
                int frames = old ? 300 : 900;
                Path dir = Report.dir().resolve("amber-scene").resolve(old ? "old" : "new");
                for (int i = 0; i < frames; i++) {
                    clock(c, 3_000_000 + i * (1000.0 / 60.0));
                    c.ticks(1);
                    MenuAnimSuite.keep(c.ctx().takeScreenshot("amber-video-frame"), dir,
                            String.format(Locale.ROOT, "frame_%05d.png", i));
                }
                c.note("wrote " + frames + " frames to " + dir);
            }
        } finally {
            reset(c);
            FontsMainMenuSuite.setWindow(c, win0[0], win0[1], win0[2]);
            FontsMainMenuSuite.restoreMm(c, saved);
        }
    }
}

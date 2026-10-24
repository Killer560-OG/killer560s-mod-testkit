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
import java.util.List;
import java.util.Locale;

/**
 * 1695-1696: Amber's Aurora main menu (mod amber-aurora-1016). killer560, 2026-10-16, of his orange aurora picture: "try
 * taking that and editing the video for it ... make sure [the aurora lines] do line up for the render of it". The mod draws
 * the picture through its own shader ({@code mainmenu.AmberAuroraScene}, pipeline {@code killer560smod:pipeline/amber_aurora})
 * and only behind {@code MainMenuScenes.previewScenes()} (the Title Screen GUI instance). At 1920x1080 GUI 2, clock held:
 * <ul>
 *   <li>1695 the scene: with previewScenes Amber draws AURORA through the shader (its frame counter rises, never the still
 *       fallback); since mod simple-scenes-1016 it does so without previewScenes too (released everywhere). The picture is warm (red over green over
 *       blue, no blue cast), moves over 2 s in the sky while the land at the bottom stays put (no whole-screen sway), stays
 *       dark behind the title and the buttons (the title and the button labels stand out), and differs from the embers.
 *       The Title Screen Switcher lists it as Amber "Aurora" beside Embers, New and Simple.</li>
 *   <li>1696 video (only when named): ~10 s at {@code -PpaintedFps} (default 60) fps ({@code -PpaintedFrames}) into
 *       {@code <report>/amber-aurora/}; {@code TESTKIT_PAINTED_SHOTS} also gets the first frame as amber-aurora.png.</li>
 * </ul>
 * Fails on main: there is no AURORA scene there ({@code setAmberSceneForTest("AURORA")} throws) and no AmberAuroraScene.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class AmberAuroraSuite implements FabricClientGameTest {

    static final String[] CASES = {"1695-ui-amber-aurora", "1696-ui-amber-aurora-video"};

    static final String SCENES = "mainmenu.MainMenuScenes";
    static final String AURORA = "mainmenu.AmberAuroraScene";
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
        UiCase.run(ctx, "1695-ui-amber-aurora", AmberAuroraSuite::scene);
        UiCase.run(ctx, "1696-ui-amber-aurora-video", AmberAuroraSuite::video);
    }

    private interface Body {
        void run() throws Exception;
    }

    /** Runs {@code body} at 1920x1080 GUI 2 in Amber (Separate style, scene Auto) with previewScenes as given, the title
     *  screen's settings saved and restored. */
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
            c.onClient(mc -> {
                FontsMainMenuSuite.setMm(mc, "SEPARATE", "AMBER", "AUTO", 100, true);
                Mod.staticCall(SCENES, "setAmberSceneForTest", "");
                McCompat.setScreen(mc, new TitleScreen());
                return null;
            });
            c.ticks(40);
            body.run();
        } finally {
            if (before == null) {
                System.clearProperty(PREVIEW);
            } else {
                System.setProperty(PREVIEW, before);
            }
            c.onClient(mc -> {
                Mod.staticCall(SCENES, "setAmberSceneForTest", "");
                Mod.staticCall(SCENES, "setClockForTest", -1.0);
                return null;
            });
            FontsMainMenuSuite.setWindow(c, win0[0], win0[1], win0[2]);
            FontsMainMenuSuite.restoreMm(c, saved);
        }
    }

    private static void clock(UiCase c, double ms) {
        c.onClient(mc -> {
            Mod.staticCall(SCENES, "setClockForTest", ms);
            return null;
        });
        c.ticks(2);
    }

    private static long counter(UiCase c, String name) {
        return c.onClient(mc -> (Long) Mod.field(Mod.ROOT + AURORA, name));
    }

    private static String amberScene(UiCase c) {
        return c.onClient(mc -> String.valueOf(Mod.staticCall(SCENES, "amberScene")));
    }

    /** TESTKIT_PAINTED_SHOTS copies a frame there as {@code <label>.png}. */
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

    /** Mean luminance of the darkest {@code keep} share of a box (fractions of the image): the background behind text,
     *  leaving the text's own bright pixels out. */
    static double darkLum(BufferedImage img, double x0, double y0, double x1, double y1, double keep) {
        List<Double> v = new java.util.ArrayList<>();
        for (int y = (int) (img.getHeight() * y0); y < (int) (img.getHeight() * y1); y += 2) {
            for (int x = (int) (img.getWidth() * x0); x < (int) (img.getWidth() * x1); x += 2) {
                v.add(GreenThemeSuite.lum709(img.getRGB(x, y)));
            }
        }
        v.sort(Double::compare);
        int n = (int) (v.size() * keep);
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += v.get(i);
        }
        return n == 0 ? 0 : sum / n;
    }

    /** Mean luminance of the brightest {@code top} share of a box (the text's own pixels). */
    static double brightLum(BufferedImage img, double x0, double y0, double x1, double y1, double top) {
        List<Double> v = new java.util.ArrayList<>();
        for (int y = (int) (img.getHeight() * y0); y < (int) (img.getHeight() * y1); y += 2) {
            for (int x = (int) (img.getWidth() * x0); x < (int) (img.getWidth() * x1); x += 2) {
                v.add(GreenThemeSuite.lum709(img.getRGB(x, y)));
            }
        }
        v.sort(Double::compare);
        int n = Math.max(1, (int) (v.size() * top));
        double sum = 0;
        for (int i = v.size() - n; i < v.size(); i++) {
            sum += v.get(i);
        }
        return sum / n;
    }

    // ---- 1695 ----------------------------------------------------------------------------------------------------------

    static void scene(UiCase c) throws Exception {
        // Released to every instance (mod simple-scenes-1016): without previewScenes Amber draws the aurora through its
        // shader too. The old embers, for the "differs from the embers" check below, are picked by the hook.
        around(c, false, () -> {
            clock(c, 2_000_000);
            long s0 = counter(c, "shaderFrames");
            long f0 = counter(c, "stillFrames");
            FontsMainMenuSuite.shot(c, "amber-released");
            String scene = amberScene(c);
            long s1 = counter(c, "shaderFrames");
            long f1 = counter(c, "stillFrames");
            c.note("released: Amber draws " + scene + ", aurora frames +" + (s1 - s0) + " shader, +" + (f1 - f0) + " still");
            c.check("AURORA".equals(scene), "without previewScenes Amber draws " + scene + ", not AURORA");
            c.check(s1 - s0 >= 1 && f1 == f0, "without previewScenes the aurora shader drew " + (s1 - s0) + " frames, the still "
                    + (f1 - f0));
            c.onClient(mc -> {
                Mod.staticCall(SCENES, "setAmberSceneForTest", "EMBERS");
                return null;
            });
            c.ticks(3);
            clock(c, 2_000_000);
            embersImg = FontsMainMenuSuite.pic(FontsMainMenuSuite.shot(c, "amber-embers")).img();
            c.onClient(mc -> {
                Mod.staticCall(SCENES, "setAmberSceneForTest", "");
                return null;
            });
        });
        around(c, true, () -> {
            String scene = amberScene(c);
            c.check("AURORA".equals(scene), "with previewScenes Amber draws " + scene + ", not AURORA");
            boolean active = c.onClient(mc -> (Boolean) Mod.staticCall(AURORA, "shaderActive"));
            c.check(active, "the amber_aurora pipeline is not active (registration or a draw failed; see the log)");
            long s0 = counter(c, "shaderFrames");
            long f0 = counter(c, "stillFrames");
            clock(c, 2_000_000);
            Path pa = FontsMainMenuSuite.shot(c, "amber-aurora-a");
            FontsMainMenuSuite.Pic a = FontsMainMenuSuite.pic(pa);
            copy(pa, "amber-aurora");
            clock(c, 2_002_000);
            FontsMainMenuSuite.Pic b = FontsMainMenuSuite.pic(FontsMainMenuSuite.shot(c, "amber-aurora-b"));
            long s1 = counter(c, "shaderFrames");
            long f1 = counter(c, "stillFrames");
            c.note(String.format(Locale.ROOT, "preview: Amber draws %s, %d shader frames, %d still", scene, s1 - s0, f1 - f0));
            c.check(s1 - s0 >= 3, "the shader drew " + (s1 - s0) + " frames");
            c.check(f1 == f0, "the aurora fell back to the still picture " + (f1 - f0) + " times");

            // warm: red over green over blue, no blue cast
            c.note(String.format(Locale.ROOT, "aurora: lum %.3f, mean rgb %.1f,%.1f,%.1f", a.lum(), a.r(), a.g(), a.b()));
            c.check(a.r() > a.g() + 25 && a.g() > a.b() + 5, String.format(Locale.ROOT,
                    "the aurora is not warm: mean rgb %.1f,%.1f,%.1f", a.r(), a.g(), a.b()));

            // it moves in the sky, smoothly (a small share over 2 s), and the land does not sway
            double all = FontsMainMenuSuite.changed(a.img(), b.img());
            double sky = PaintedScenesSuite.changedIn(a.img(), b.img(), 0.0, 0.10, 0.30, 0.45);
            double skyR = PaintedScenesSuite.changedIn(a.img(), b.img(), 0.70, 0.10, 1.0, 0.45);
            double land = PaintedScenesSuite.changedIn(a.img(), b.img(), 0.30, 0.90, 0.38, 1.0);
            double hill = PaintedScenesSuite.changedIn(a.img(), b.img(), 0.0, 0.60, 0.05, 0.75);
            // killer560, 2026-10-16: "don't have the lights kind of break as it goes around my head ... positioned a bit
            // higher so it doesn't hit the player's head": round the character's head (x 490-605, y 670-790 of 1920x1080)
            // nothing moves and no rays streak the sky (sideways ray detail, mean |d lum / dx|)
            double head = PaintedScenesSuite.changedIn(a.img(), b.img(), 0.255, 0.62, 0.315, 0.73);
            double headRays = rayDetail(a.img(), 0.25, 0.60, 0.32, 0.72);
            double skyRays = rayDetail(a.img(), 0.75, 0.55, 0.82, 0.67);
            c.note(String.format(Locale.ROOT, "round the head: %.3f%% changed over 2 s, ray detail %.4f (the right sky %.4f)",
                    head * 100, headRays, skyRays));
            c.check(head < 0.002, String.format(Locale.ROOT, "the aurora moves round the head: %.3f%%", head * 100));
            c.check(headRays < skyRays * 0.5, String.format(Locale.ROOT,
                    "rays reach the head: ray detail %.4f there against %.4f in the open sky", headRays, skyRays));
            double sum = 0;
            for (int y = 0; y < a.img().getHeight(); y += 3) {
                for (int x = 0; x < a.img().getWidth(); x += 3) {
                    int p = a.img().getRGB(x, y);
                    int q = b.img().getRGB(x, y);
                    sum += Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF))
                            + Math.abs((p & 0xFF) - (q & 0xFF));
                }
            }
            double meanDiff = sum / ((a.img().getHeight() / 3.0) * (a.img().getWidth() / 3.0));
            c.note(String.format(Locale.ROOT, "over 2 s: %.2f%% of the frame, sky left %.2f%%, sky right %.2f%%, land %.3f%%, "
                    + "hill %.3f%% changed; mean |drgb| %.2f", all * 100, sky * 100, skyR * 100, land * 100, hill * 100, meanDiff));
            c.check(meanDiff > 0.8, String.format(Locale.ROOT, "the aurora barely moved over 2 s: mean |drgb| %.2f", meanDiff));
            c.check(sky > 0.002 || skyR > 0.002, String.format(Locale.ROOT, "the sky did not move over 2 s: %.3f%%, %.3f%%",
                    sky * 100, skyR * 100));
            c.check(land < 0.003 && hill < 0.003, String.format(Locale.ROOT,
                    "the land moved (a sway?): %.3f%% at the ledge, %.3f%% on the hill", land * 100, hill * 100));

            // the title and the buttons read: dark behind them, their own pixels far brighter
            double titleBack = PaintedScenesSuite.titleLum(a.img());
            double titleText = brightLum(a.img(), 0.30, 0.04, 0.70, 0.17, 0.06);
            double buttonBack = darkLum(a.img(), 0.40, 0.30, 0.60, 0.62, 0.6);
            double buttonText = brightLum(a.img(), 0.40, 0.30, 0.60, 0.62, 0.02);
            c.note(String.format(Locale.ROOT, "title: back %.3f, text %.3f; buttons: back %.3f, text %.3f", titleBack, titleText,
                    buttonBack, buttonText));
            c.check(titleBack < 0.12, String.format(Locale.ROOT, "the aurora is bright behind the title: %.3f", titleBack));
            c.check(titleText > titleBack + 0.45, String.format(Locale.ROOT, "the title does not stand out: %.3f on %.3f",
                    titleText, titleBack));
            c.check(buttonBack < 0.20, String.format(Locale.ROOT, "the aurora is bright behind the buttons: %.3f", buttonBack));
            c.check(buttonText > buttonBack + 0.40, String.format(Locale.ROOT,
                    "the button labels do not stand out: %.3f on %.3f", buttonText, buttonBack));

            double vsEmbers = embersImg == null ? 1 : FontsMainMenuSuite.changed(a.img(), embersImg);
            c.note(String.format(Locale.ROOT, "against the embers: %.1f%% differ", vsEmbers * 100));
            c.check(vsEmbers > 0.20, String.format(Locale.ROOT, "the aurora looks like the embers: %.1f%% differ", vsEmbers * 100));

            // the Title Screen Switcher lists it under Amber, with the old Amber looks kept
            String names = c.onClient(mc -> {
                StringBuilder sb = new StringBuilder();
                for (Object l : (List<?>) Mod.staticCall("mainmenu.TitleScreenLooks", "looks")) {
                    sb.append(Mod.call(l, "label")).append('|');
                }
                return sb.toString();
            });
            c.note("switcher: " + names);
            for (String want : new String[]{"Amber \u00b7 Embers|", "Amber \u00b7 New|", "Amber \u00b7 Simple|", "Amber \u00b7 Aurora|"}) {
                c.check(names.contains(want), "the Title Screen Switcher does not list " + want.replace("|", ""));
            }
        });
        embersImg = null;
    }

    private static BufferedImage embersImg;

    /** Mean |luminance step| between horizontal neighbors in a box (fractions of the image): vertical rays read high. */
    static double rayDetail(BufferedImage img, double x0, double y0, double x1, double y1) {
        double sum = 0;
        long n = 0;
        for (int y = (int) (img.getHeight() * y0); y < (int) (img.getHeight() * y1); y++) {
            for (int x = (int) (img.getWidth() * x0); x < (int) (img.getWidth() * x1) - 1; x++) {
                sum += Math.abs(GreenThemeSuite.lum709(img.getRGB(x + 1, y)) - GreenThemeSuite.lum709(img.getRGB(x, y)));
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    // ---- 1696 ----------------------------------------------------------------------------------------------------------

    static void video(UiCase c) throws Exception {
        if (!System.getProperty("testkit.scenario", "").contains("1696")) {
            c.note("skipped: the video only runs when 1696 is named");
            return;
        }
        around(c, true, () -> {
            // -PpaintedFps=N (default 60; 120 for the high-fps check), -PpaintedFrames=N (default 10 s worth)
            int fps = Integer.parseInt(System.getProperty("testkit.paintedFps", "60"));
            int frames = Integer.parseInt(System.getProperty("testkit.paintedFrames", String.valueOf(fps * 10)));
            Path dir = Report.dir().resolve("amber-aurora");
            for (int i = 0; i < frames; i++) {
                double ms = 3_000_000 + i * (1000.0 / fps);
                c.onClient(mc -> {
                    Mod.staticCall(SCENES, "setClockForTest", ms);
                    return null;
                });
                c.ticks(1);
                MenuAnimSuite.keep(c.ctx().takeScreenshot("amber-aurora-frame"), dir, String.format(Locale.ROOT, "frame_%05d.png", i));
            }
            c.note("wrote " + frames + " frames to " + dir);
        });
    }
}

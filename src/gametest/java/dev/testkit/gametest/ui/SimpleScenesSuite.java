package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.mod.Quiet;
import dev.testkit.harness.Report;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 1700-1705: the Simple main menu scenes and the Main Menu Scene toggle (mod simple-scenes-1016). killer560, 2026-10-16:
 * "Look at ambers simple theme and apply that concept to all the other color palletes ... in hd. Keep light as only the one
 * option ... on the real mod they should have a toggle for fancy main screen or simple", then "for custom on fancy or plain
 * have it do the colors of their custom theme". At 1920x1080 GUI 2, clock held, no previewScenes property anywhere.
 * <ul>
 *   <li>1700 each Simple scene (Amber embers, Ocean bubbles, Dark stardust, Viridian fireflies, Custom embers in a pink
 *       Custom set): drawn through the scene shaders (their frame counter rises, the flavor is the theme's, particles were
 *       planned), in the theme's own colors, moving over 1 s and between two 1/120 s frames, dark behind the title, a
 *       different picture from the theme's Fancy scene, and dithered (no band edge in the calm sky; the GuiRects fallback of
 *       the same scene bands, which shows the instrument sees bands).</li>
 *   <li>1701 the toggle: every theme in Match and Separate draws its Fancy scene on Fancy and its Simple one on Simple (Light
 *       Soft Light both times, Custom the Simple embers both times), proved by the scene drawn and the Simple counter; the
 *       Themes drawer has a cycling "Main Menu Scene: Fancy" row whose real press switches to Simple, the choice survives a
 *       save and a reload from disk, and Themed Vanilla Menus (an Options screen out of a world) follow it.</li>
 *   <li>1702 the Fancy scenes in a normal instance (no preview property, the switcher off): Amber's aurora through its
 *       shader, Ocean's Cavern Falls, Dark's Milky Way with her cape moving, Viridian's slime boss rippling.</li>
 *   <li>1703 Light is Soft Light in both modes: the same scene, drawn by the scene shaders, nearly the same frame.</li>
 *   <li>1704 video (only when named): {@code -PpaintedScene} (default AMBER,OCEAN,DARK,GREEN,CUSTOM) each
 *       {@code -PpaintedFrames} (default 10 s) at {@code -PpaintedFps} (default 60) into {@code <report>/simple-<theme>/};
 *       {@code TESTKIT_PAINTED_SHOTS} also gets a still of each as simple-<theme>.png.</li>
 *   <li>1705 a still of the Themes drawer with the toggle ({@code TESTKIT_PAINTED_SHOTS}: simple-settings.png).</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimpleScenesSuite implements FabricClientGameTest {

    static final String[] CASES = {"1700-ui-simple-scenes", "1701-ui-simple-toggle", "1702-ui-fancy-released",
            "1703-ui-simple-light", "1704-ui-simple-video", "1705-ui-simple-settings-shot"};

    static final String SCENES = "mainmenu.MainMenuScenes";
    static final String SIMPLE = "mainmenu.SimpleScenes";
    static final String MM = FontsMainMenuSuite.MM;
    static final String PREVIEW = "killer560smod.previewScenes";
    /** A pink Custom set, far from every preset, so Custom's scene is seen to take its colors. */
    static final int[] PINK = {0xFFE040C0, 0xFF1C1020, 0xFF2A1830, 0xFFF2E6F0, 0xFFC03090};

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
        UiCase.run(ctx, "1700-ui-simple-scenes", SimpleScenesSuite::scenes);
        UiCase.run(ctx, "1701-ui-simple-toggle", SimpleScenesSuite::toggle);
        UiCase.run(ctx, "1702-ui-fancy-released", SimpleScenesSuite::released);
        UiCase.run(ctx, "1703-ui-simple-light", SimpleScenesSuite::light);
        UiCase.run(ctx, "1704-ui-simple-video", SimpleScenesSuite::video);
        UiCase.run(ctx, "1705-ui-simple-settings-shot", SimpleScenesSuite::settingsShot);
    }

    // ---- shared ---------------------------------------------------------------------------------------------------------

    private interface Body {
        void run() throws Exception;
    }

    /** At 1920x1080 GUI 2 with the main menu settings, scene mode and Custom colors saved and restored; no preview
     *  property and the Title Screen Switcher off, as in every normal instance. */
    private static void around(UiCase c, Body body) throws Exception {
        FontsMainMenuSuite.MmState saved = FontsMainMenuSuite.saveMm(c);
        Object mode0 = c.onClient(mc -> Mod.call(Mod.cfg(MM), "getSceneMode"));
        int[] custom0 = c.onClient(mc -> customColours());
        int[] win0 = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.options.guiScale().get()});
        String before = System.getProperty(PREVIEW);
        try {
            System.clearProperty(PREVIEW);
            c.onClient(mc -> {
                Mod.staticCall("mainmenu.TitleScreenLooks", "setEnabledForTest", false);
                return null;
            });
            FontsMainMenuSuite.setWindow(c, 1920, 1080, 2);
            c.ctx().getInput().setCursorPos(4, 4);
            body.run();
        } finally {
            if (before != null) {
                System.setProperty(PREVIEW, before);
            }
            c.onClient(mc -> {
                Mod.call(Mod.cfg(MM), "setSceneMode", mode0);
                setCustom(custom0);
                Mod.staticCall(SCENES, "setClockForTest", -1.0);
                return null;
            });
            PaintedScenesSuite.setShaders(c, true);
            FontsMainMenuSuite.setWindow(c, win0[0], win0[1], win0[2]);
            FontsMainMenuSuite.restoreMm(c, saved);
        }
    }

    static int[] customColours() {
        Object menu = Mod.cfg("gui.MenuConfig");
        String[] slots = {"ACCENT", "PANEL", "CARD", "TEXT", "SWITCH_ON"};
        int[] out = new int[slots.length];
        for (int i = 0; i < slots.length; i++) {
            out[i] = (Integer) Mod.call(menu, "getCustomColour", Mod.enumValue("gui.MenuTheme$Slot", slots[i]));
        }
        return out;
    }

    static void setCustom(int[] colours) {
        Object menu = Mod.cfg("gui.MenuConfig");
        String[] slots = {"ACCENT", "PANEL", "CARD", "TEXT", "SWITCH_ON"};
        for (int i = 0; i < slots.length; i++) {
            Mod.call(menu, "setCustomColour", Mod.enumValue("gui.MenuTheme$Slot", slots[i]), colours[i]);
        }
    }

    /** The title screen in {@code theme} ({@code style}: MATCH or SEPARATE) in scene mode {@code mode}. */
    private static void title(UiCase c, String style, String theme, String mode) {
        c.onClient(mc -> {
            if ("CUSTOM".equals(theme)) {
                setCustom(PINK);
            }
            if ("MATCH".equals(style)) {
                Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", FontsMainMenuSuite.menuTheme(theme));
            }
            FontsMainMenuSuite.setMm(mc, style, theme, "AUTO", 100, true);
            Mod.call(Mod.cfg(MM), "setSceneMode", Mod.enumValue(MM + "$SceneMode", mode));
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

    static long simpleFrames(UiCase c) {
        return c.onClient(mc -> (Long) Mod.field(Mod.ROOT + SIMPLE, "shaderFrames"));
    }

    static long rectFrames(UiCase c) {
        return c.onClient(mc -> (Long) Mod.field(Mod.ROOT + SIMPLE, "rectFrames"));
    }

    static String lookScene() {
        return String.valueOf(Mod.call(Mod.staticCall("mainmenu.MainMenuLook", "current"), "scene"));
    }

    /** Pixels whose summed RGB moved by more than {@code thr}, in a box of fractions of the image. */
    static int moved(BufferedImage a, BufferedImage b, double x0, double y0, double x1, double y1, int thr) {
        int n = 0;
        for (int y = (int) (a.getHeight() * y0); y < (int) (a.getHeight() * y1); y++) {
            for (int x = (int) (a.getWidth() * x0); x < (int) (a.getWidth() * x1); x++) {
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                int d = Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF))
                        + Math.abs((p & 0xFF) - (q & 0xFF));
                if (d > thr) {
                    n++;
                }
            }
        }
        return n;
    }

    /** {theme, expected Simple scene, expected flavor, Fancy scene}. */
    static final String[][] THEMES = {
            {"AMBER", "SIMPLE_EMBERS", "EMBERS", "EMBERS"},
            {"OCEAN", "SIMPLE_BUBBLES", "BUBBLES", "WATERFALL"},
            {"DARK", "SIMPLE_STARDUST", "STARDUST", "STARFIELD"},
            {"GREEN", "SIMPLE_FIREFLIES", "FIREFLIES", "FIREFLIES"},
            {"CUSTOM", "SIMPLE_EMBERS", "EMBERS", "SIMPLE_EMBERS"}};

    // ---- 1700 ----------------------------------------------------------------------------------------------------------

    static void scenes(UiCase c) throws Exception {
        around(c, () -> {
            for (String[] th : THEMES) {
                String name = th[0].toLowerCase(Locale.ROOT);
                // the Fancy frame first, to tell the two apart
                title(c, "SEPARATE", th[0], "FANCY");
                FontsMainMenuSuite.Pic fancy = at(c, 2_000_000, "fancy-" + name);
                title(c, "SEPARATE", th[0], "SIMPLE");
                long f0 = simpleFrames(c);
                long e0 = PaintedScenesSuite.shaderElements(c);
                FontsMainMenuSuite.Pic a = at(c, 2_000_000, "simple-" + name);
                FontsMainMenuSuite.Pic a1 = at(c, 2_000_000 + 1000.0 / 120, "simple-" + name + "-f1");
                FontsMainMenuSuite.Pic b = at(c, 2_001_000, "simple-" + name + "-1s");
                long f1 = simpleFrames(c);
                long e1 = PaintedScenesSuite.shaderElements(c);
                Object[] probe = c.onClient(mc -> new Object[]{lookScene(), String.valueOf(Mod.field(Mod.ROOT + SIMPLE, "lastFlavour")),
                        Mod.field(Mod.ROOT + SIMPLE, "lastParticles")});
                double moved1s = FontsMainMenuSuite.changed(a.img(), b.img());
                int frameMoved = moved(a.img(), a1.img(), 0.0, 0.25, 1.0, 1.0, 6);
                double vsFancy = FontsMainMenuSuite.changed(a.img(), fancy.img());
                double titleLum = PaintedScenesSuite.titleLum(a.img());
                double band = PaintedScenesSuite.bandEdge(a.img());
                c.note(String.format(Locale.ROOT, "%s Simple: scene %s flavor %s, %s particles, %d shader frames, %d shader elements;"
                                + " rgb %.1f,%.1f,%.1f lum %.3f; moved %.2f%% over 1 s, %d px changed in 1/120 s; %.1f%% differs from"
                                + " Fancy; title band lum %.3f; band edge %.2f", th[0], probe[0], probe[1], probe[2], f1 - f0, e1 - e0,
                        a.r(), a.g(), a.b(), a.lum(), moved1s * 100, frameMoved, vsFancy * 100, titleLum, band));
                c.check(th[1].equals(probe[0]), th[0] + " on Simple draws " + probe[0] + ", not " + th[1]);
                c.check(th[2].equals(probe[1]), th[0] + " on Simple drew the " + probe[1] + " flavor, not " + th[2]);
                c.check((Integer) probe[2] >= 10, th[0] + "'s Simple scene planned only " + probe[2] + " particles");
                c.check(f1 - f0 >= 3 && e1 > e0, th[0] + "'s Simple scene was not drawn by the scene shaders: " + (f1 - f0)
                        + " frames, " + (e1 - e0) + " elements");
                c.check(a.lum() < 0.3, th[0] + "'s Simple scene is not dark: lum " + a.lum());
                c.check(titleLum < 0.12, String.format(Locale.ROOT, "%s's Simple scene is bright behind the title: %.3f", th[0], titleLum));
                c.check(moved1s > 0.0004, String.format(Locale.ROOT, "%s's Simple scene did not move over 1 s: %.3f%%", th[0], moved1s * 100));
                c.check(frameMoved > 20, th[0] + "'s Simple scene did not move between two 1/120 s frames: " + frameMoved + " px");
                c.check(band <= 0.5, th[0] + "'s Simple scene bands: " + band + " of the columns step together at one row");
                if (!"CUSTOM".equals(th[0])) {
                    c.check(vsFancy > 0.05, String.format(Locale.ROOT, "%s's Simple scene looks like its Fancy one: %.1f%% differ",
                            th[0], vsFancy * 100));
                }
                switch (th[0]) {
                    case "AMBER" -> c.check(a.r() > a.g() + 2 && a.g() >= a.b(), "Amber's Simple scene is not warm: " + a);
                    case "OCEAN" -> c.check(a.g() > a.r() + 3 && a.b() > a.r() + 3, "Ocean's Simple scene is not teal: " + a);
                    case "DARK" -> c.check(Math.abs(a.r() - a.g()) < 4 && Math.abs(a.g() - a.b()) < 6,
                            "Dark's Simple scene is not nearly black and white: " + a);
                    case "GREEN" -> c.check(a.g() > a.r() + 3 && a.g() > a.b() + 3, "Viridian's Simple scene is not green: " + a);
                    case "CUSTOM" -> c.check(a.r() > a.g() + 3 && a.b() > a.g() + 3, "Custom's Simple scene is not in its pink set: " + a);
                    default -> {
                    }
                }
            }
            // The instrument: the same Amber scene through GuiRects (no shaders) bands where the shader one does not.
            PaintedScenesSuite.setShaders(c, false);
            try {
                title(c, "SEPARATE", "AMBER", "SIMPLE");
                long r0 = rectFrames(c);
                FontsMainMenuSuite.Pic rects = at(c, 2_000_000, "simple-amber-rects");
                long r1 = rectFrames(c);
                double bandR = PaintedScenesSuite.bandEdge(rects.img());
                c.note(String.format(Locale.ROOT, "Amber Simple through GuiRects: %d frames, band edge %.2f, lum %.3f", r1 - r0, bandR, rects.lum()));
                c.check(r1 > r0, "the GuiRects fallback did not draw");
                c.check(bandR >= 0.8, "the instrument found no band edges in the GuiRects fallback (" + bandR + ") - it measures nothing");
            } finally {
                PaintedScenesSuite.setShaders(c, true);
            }
        });
    }

    // ---- 1701 ----------------------------------------------------------------------------------------------------------

    static void toggle(UiCase c) throws Exception {
        String old = MenuACases.themeName();
        try {
            around(c, () -> {
                String[] all = {"AMBER", "OCEAN", "DARK", "LIGHT", "GREEN", "CUSTOM"};
                String[] fancyWant = {"EMBERS", "WATERFALL", "STARFIELD", "SOFT_LIGHT", "FIREFLIES", "SIMPLE_EMBERS"};
                String[] simpleWant = {"SIMPLE_EMBERS", "SIMPLE_BUBBLES", "SIMPLE_STARDUST", "SOFT_LIGHT", "SIMPLE_FIREFLIES", "SIMPLE_EMBERS"};
                List<String> rows = new ArrayList<>();
                for (String style : new String[]{"MATCH", "SEPARATE"}) {
                    for (int i = 0; i < all.length; i++) {
                        for (String mode : new String[]{"FANCY", "SIMPLE"}) {
                            title(c, style, all[i], mode);
                            long f0 = simpleFrames(c);
                            clock(c, 2_000_000);
                            c.ticks(3);
                            long f1 = simpleFrames(c);
                            String scene = c.onClient(mc -> lookScene());
                            String want = "FANCY".equals(mode) ? fancyWant[i] : simpleWant[i];
                            boolean simpleDrawn = f1 > f0;
                            boolean simpleWanted = want.startsWith("SIMPLE_");
                            rows.add(style + "/" + all[i] + "/" + mode + "=" + scene + (simpleDrawn ? "*" : ""));
                            c.check(want.equals(scene), style + " " + all[i] + " on " + mode + " draws " + scene + ", not " + want);
                            c.check(simpleDrawn == simpleWanted, style + " " + all[i] + " on " + mode + ": the Simple scene "
                                    + (simpleDrawn ? "drew" : "did not draw") + " (" + (f1 - f0) + " frames)");
                        }
                    }
                }
                c.note("scenes (* = drawn by SimpleScenes): " + rows);

                // The settings row: a real press on it in the Themes drawer, then a save and a reload from disk.
                Path file = c.onClient(mc -> (Path) Mod.field(Mod.ROOT + MM, "CONFIG_PATH"));
                String why = c.onClient(mc -> {
                    Mod.call(Mod.cfg(MM), "setSceneMode", Mod.enumValue(MM + "$SceneMode", "FANCY"));
                    Mod.call(Mod.cfg(MM), "save");
                    Screen s = FontsMainMenuSuite.themesDrawer(mc);
                    AbstractWidget row = FontsMainMenuSuite.row(s, "Main Menu Scene: ");
                    if (row == null) {
                        return "no 'Main Menu Scene:' row in the Themes drawer: " + FontsMainMenuSuite.labels(s);
                    }
                    if (!"Main Menu Scene: Fancy".equals(ModScreenDriver.label(row))) {
                        return "the row reads '" + ModScreenDriver.label(row) + "', expected 'Main Menu Scene: Fancy'";
                    }
                    if (!Boolean.TRUE.equals(Mod.call(row, "isCycling"))) {
                        return "Main Menu Scene is not a cycling button";
                    }
                    List<String> labels = FontsMainMenuSuite.labels(s);
                    int styleAt = -1;
                    int sceneAt = -1;
                    for (int k = 0; k < labels.size(); k++) {
                        if (labels.get(k).startsWith("Main Menu Style:")) {
                            styleAt = k;
                        }
                        if (labels.get(k).startsWith("Main Menu Scene:")) {
                            sceneAt = k;
                        }
                    }
                    if (styleAt < 0 || sceneAt != styleAt + 1) {
                        return "Main Menu Scene is not right under Main Menu Style: " + labels;
                    }
                    ModScreenDriver.press(row);
                    String now = String.valueOf(Mod.call(Mod.cfg(MM), "getSceneMode"));
                    if (!"SIMPLE".equals(now)) {
                        return "a press on Main Menu Scene set " + now + ", expected SIMPLE";
                    }
                    Screen s2 = FontsMainMenuSuite.themesDrawer(mc);
                    if (FontsMainMenuSuite.row(s2, "Main Menu Scene: Simple") == null) {
                        return "the rebuilt drawer does not show Main Menu Scene: Simple: " + FontsMainMenuSuite.labels(s2);
                    }
                    McCompat.setScreen(mc, null);
                    return null;
                });
                c.check(why == null, why);
                String json = Files.readString(file);
                c.check(json.contains("\"sceneMode\": \"SIMPLE\""), "the saved file does not hold sceneMode SIMPLE: " + json);
                String reloaded = c.onClient(mc -> {
                    Mod.call(Mod.cfg(MM), "setSceneMode", Mod.enumValue(MM + "$SceneMode", "FANCY")); // in memory only
                    Mod.staticCall(MM, "load");
                    return String.valueOf(Mod.call(Mod.cfg(MM), "getSceneMode"));
                });
                c.note("after a press, the file: sceneMode " + (json.contains("SIMPLE") ? "SIMPLE" : "?") + "; reloaded: " + reloaded);
                c.check("SIMPLE".equals(reloaded), "Main Menu Scene did not survive a reload: " + reloaded);
                // and back to Fancy, saved, reloaded
                String back = c.onClient(mc -> {
                    Mod.call(Mod.cfg(MM), "setSceneMode", Mod.enumValue(MM + "$SceneMode", "FANCY"));
                    Mod.call(Mod.cfg(MM), "save");
                    Mod.staticCall(MM, "load");
                    return String.valueOf(Mod.call(Mod.cfg(MM), "getSceneMode"));
                });
                c.check("FANCY".equals(back), "Fancy did not survive a reload: " + back);

                // Themed Vanilla Menus: an Options screen out of a world draws the main menu's scene behind it.
                for (String mode : new String[]{"FANCY", "SIMPLE"}) {
                    title(c, "SEPARATE", "OCEAN", mode);
                    long f0 = simpleFrames(c);
                    c.onClient(mc -> {
                        Mod.call(Mod.cfg(MM), "setOtherMenus", true);
                        McCompat.setScreen(mc, new net.minecraft.client.gui.screens.options.OptionsScreen(new TitleScreen(), mc.options, false));
                        return null;
                    });
                    c.ticks(10);
                    long f1 = simpleFrames(c);
                    Object flavour = c.onClient(mc -> String.valueOf(Mod.field(Mod.ROOT + SIMPLE, "lastFlavour")));
                    FontsMainMenuSuite.shot(c, "options-" + mode.toLowerCase(Locale.ROOT));
                    c.note("Options screen on " + mode + ": " + (f1 - f0) + " Simple frames, last flavor " + flavour);
                    if ("SIMPLE".equals(mode)) {
                        c.check(f1 - f0 >= 3 && "BUBBLES".equals(flavour), "the Options screen did not draw Ocean's Simple scene"
                                + " behind it: " + (f1 - f0) + " frames, " + flavour);
                    } else {
                        c.check(f1 == f0, "the Options screen drew the Simple scene on Fancy: " + (f1 - f0) + " frames");
                    }
                }
            });
        } finally {
            c.onClient(mc -> {
                Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", Mod.enumValue("gui.MenuTheme", old));
                return null;
            });
        }
    }

    // ---- 1702 ----------------------------------------------------------------------------------------------------------

    static void released(UiCase c) throws Exception {
        around(c, () -> {
            boolean switcher = c.onClient(mc -> (Boolean) Mod.staticCall("mainmenu.TitleScreenLooks", "enabled"));
            boolean hasPreview = c.onClient(mc -> {
                try {
                    Mod.staticCall(SCENES, "previewScenes");
                    return true;
                } catch (Throwable t) {
                    return false;
                }
            });
            c.note("switcher on: " + switcher + ", previewScenes still in the jar: " + hasPreview);
            c.check(!switcher && System.getProperty(PREVIEW) == null, "this is not a normal instance: switcher " + switcher);
            // Amber: the aurora through its shader
            title(c, "SEPARATE", "AMBER", "FANCY");
            long a0 = c.onClient(mc -> (Long) Mod.field(Mod.ROOT + "mainmenu.AmberAuroraScene", "shaderFrames"));
            clock(c, 2_000_000);
            c.ticks(3);
            long a1 = c.onClient(mc -> (Long) Mod.field(Mod.ROOT + "mainmenu.AmberAuroraScene", "shaderFrames"));
            String amber = c.onClient(mc -> String.valueOf(Mod.staticCall(SCENES, "amberScene")));
            c.note("Amber: " + amber + ", " + (a1 - a0) + " aurora shader frames");
            c.check("AURORA".equals(amber) && a1 - a0 >= 3, "Amber does not draw its aurora: " + amber + ", " + (a1 - a0) + " frames");
            // Ocean: Cavern Falls through its shader
            title(c, "SEPARATE", "OCEAN", "FANCY");
            long o0 = c.onClient(mc -> (Long) Mod.field(Mod.ROOT + "mainmenu.OceanFallsScene", "shaderFrames"));
            clock(c, 2_000_000);
            c.ticks(3);
            long o1 = c.onClient(mc -> (Long) Mod.field(Mod.ROOT + "mainmenu.OceanFallsScene", "shaderFrames"));
            String ocean = c.onClient(mc -> String.valueOf(Mod.staticCall(SCENES, "oceanScene")));
            c.note("Ocean: " + ocean + ", " + (o1 - o0) + " falls shader frames");
            c.check("CAVERN_FALLS".equals(ocean) && o1 - o0 >= 3, "Ocean does not draw Cavern Falls: " + ocean + ", " + (o1 - o0));
            // Dark: the Milky Way, her cape moving
            title(c, "SEPARATE", "DARK", "FANCY");
            clock(c, 2_000_000);
            int hero = c.onClient(mc -> (Integer) Mod.field(Mod.ROOT + SCENES, "heroRects"));
            String dark = c.onClient(mc -> String.valueOf(Mod.staticCall(SCENES, "darkScene")));
            c.note("Dark: " + dark + ", cape tiles " + hero);
            c.check("MILKY_WAY_HERO".equals(dark) && hero > 20, "Dark does not draw the Milky Way with her: " + dark + ", " + hero);
            // Viridian: the slime boss rippling
            String oldMenu = MenuACases.themeName();
            try {
                title(c, "MATCH", "GREEN", "FANCY");
                clock(c, 2_000_000);
                int tiles = c.onClient(mc -> (Integer) Mod.field(Mod.ROOT + SCENES, "heroRects"));
                String green = c.onClient(mc -> String.valueOf(Mod.staticCall(SCENES, "greenScene")));
                c.note("Viridian: " + green + ", ripple tiles " + tiles);
                c.check("TARGET".equals(green) && tiles > 50, "Viridian does not draw the slime boss: " + green + ", " + tiles);
            } finally {
                c.onClient(mc -> {
                    Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", Mod.enumValue("gui.MenuTheme", oldMenu));
                    return null;
                });
            }
            // Custom on Fancy: its own colors through the Simple embers, never Plain
            title(c, "SEPARATE", "CUSTOM", "FANCY");
            String custom = c.onClient(mc -> lookScene());
            c.check("SIMPLE_EMBERS".equals(custom), "Custom on Fancy draws " + custom + ", not the embers in its colors");
        });
    }

    // ---- 1703 ----------------------------------------------------------------------------------------------------------

    static void light(UiCase c) throws Exception {
        around(c, () -> {
            FontsMainMenuSuite.Pic[] pics = new FontsMainMenuSuite.Pic[2];
            String[] modes = {"FANCY", "SIMPLE"};
            for (int k = 0; k < 2; k++) {
                title(c, "SEPARATE", "LIGHT", modes[k]);
                long e0 = PaintedScenesSuite.shaderElements(c);
                long f0 = simpleFrames(c);
                pics[k] = at(c, 2_000_000, "light-" + modes[k].toLowerCase(Locale.ROOT));
                long e1 = PaintedScenesSuite.shaderElements(c);
                long f1 = simpleFrames(c);
                String scene = c.onClient(mc -> lookScene());
                c.note("Light on " + modes[k] + ": " + scene + ", " + (e1 - e0) + " shader elements, " + (f1 - f0) + " Simple frames, lum "
                        + String.format(Locale.ROOT, "%.3f", pics[k].lum()));
                c.check("SOFT_LIGHT".equals(scene), "Light on " + modes[k] + " draws " + scene + ", not SOFT_LIGHT");
                c.check(e1 > e0, "Light on " + modes[k] + " is not drawn by the scene shaders (the smooth Soft Light)");
                c.check(f1 == f0, "Light on " + modes[k] + " drew a Simple scene: " + (f1 - f0) + " frames");
                c.check(pics[k].lum() > 0.55, "Light on " + modes[k] + " is not light: " + pics[k].lum());
            }
            double d = FontsMainMenuSuite.changed(pics[0].img(), pics[1].img());
            c.note(String.format(Locale.ROOT, "Light Fancy vs Simple: %.3f%% differ", d * 100));
            c.check(d < 0.01, String.format(Locale.ROOT, "Light differs between Fancy and Simple: %.2f%%", d * 100));
        });
    }

    // ---- 1704 ----------------------------------------------------------------------------------------------------------

    static void video(UiCase c) throws Exception {
        if (!System.getProperty("testkit.scenario", "").contains("1704")) {
            c.note("skipped: the video only runs when 1704 is named");
            return;
        }
        double fps = Double.parseDouble(System.getProperty("testkit.paintedFps", "60"));
        String list = System.getProperty("testkit.paintedScene", "");
        if (list.isBlank()) {
            list = "AMBER,OCEAN,DARK,GREEN,CUSTOM";
        }
        for (String theme : list.split(",")) {
            String th = theme.trim();
            around(c, () -> {
                title(c, "SEPARATE", th, "SIMPLE");
                at(c, 2_500_000, "simple-" + th.toLowerCase(Locale.ROOT));
                int frames = Integer.parseInt(System.getProperty("testkit.paintedFrames", String.valueOf((int) Math.round(10 * fps))));
                Path dir = Report.dir().resolve("simple-" + th.toLowerCase(Locale.ROOT));
                long f0 = simpleFrames(c);
                for (int i = 0; i < frames; i++) {
                    double ms = 3_000_000 + i * (1000.0 / fps);
                    c.onClient(mc -> {
                        Mod.staticCall(SCENES, "setClockForTest", ms);
                        return null;
                    });
                    c.ticks(1);
                    MenuAnimSuite.keep(c.ctx().takeScreenshot("simple-frame"), dir, String.format(Locale.ROOT, "frame_%05d.png", i));
                }
                long f1 = simpleFrames(c);
                c.note("wrote " + frames + " frames of " + th + " Simple at " + fps + " fps to " + dir + " (" + (f1 - f0) + " Simple frames drawn)");
                c.check(f1 - f0 >= frames, "the Simple scene drew only " + (f1 - f0) + " frames for " + frames + " shots");
            });
        }
    }

    // ---- 1705 ----------------------------------------------------------------------------------------------------------

    static void settingsShot(UiCase c) throws Exception {
        around(c, () -> {
            title(c, "SEPARATE", "AMBER", "FANCY");
            c.onClient(mc -> {
                FontsMainMenuSuite.themesDrawer(mc);
                return null;
            });
            c.ticks(8);
            String label = c.onClient(mc -> {
                AbstractWidget row = FontsMainMenuSuite.row(McCompat.screen(mc), "Main Menu Scene: ");
                return row == null ? null : ModScreenDriver.label(row);
            });
            c.check(label != null, "no Main Menu Scene row in the Themes drawer");
            // scroll the drawer (real wheel turns over it) until the Main Menu section is in view
            int[] win = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
            c.ctx().getInput().setCursorPos(win[0] * 0.78, win[1] * 0.5);
            int rowY = -1;
            for (int k = 0; k < 40; k++) {
                int[] pos = c.onClient(mc -> {
                    Screen s = McCompat.screen(mc);
                    AbstractWidget row = FontsMainMenuSuite.row(s, "Main Menu Scene: ");
                    return row == null ? new int[]{-1, s.height} : new int[]{row.getY(), s.height};
                });
                rowY = pos[0];
                if (rowY >= 0 && rowY < pos[1] * 0.45) {
                    break;
                }
                c.ctx().getInput().scroll(-1);
                c.ticks(2);
            }
            c.note("Main Menu Scene row at y " + rowY);
            c.ticks(20);
            Path p = FontsMainMenuSuite.shot(c, "settings");
            copy(p, "simple-settings");
            c.note("Main Menu Scene row: " + label);
        });
    }
}

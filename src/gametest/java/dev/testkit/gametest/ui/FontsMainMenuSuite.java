package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.mod.Quiet;
import dev.testkit.harness.Report;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * killer560's 2026-10-15 Themes list (mod fonts-mainmenu-1015): the Font option and the redesigned main menu.
 * <ul>
 *   <li>1561-ui-font-menu-only: a bundled font set to Mod Menu Only changes the RENDERED width of the mod menu's text
 *       (each GUI text render state's prepared bounds) and of nothing else - a vanilla Options screen, text drawn
 *       outside any screen (the HUD path) and {@code Font.width} keep Minecraft's widths. Minecraft font first, as the
 *       control that the same measurement reads equal when nothing is chosen.</li>
 *   <li>1562-ui-font-everything: Everything changes all three, every bundled font builds and measures differently from
 *       Minecraft's, Font Size moves the width, and going back to Minecraft restores every width exactly.</li>
 *   <li>1563-ui-font-import: a .ttf dropped into the fonts folder is found by the Font row's cycling and drawn (its
 *       widths equal the same face's bundled copy); a broken file is refused with a message and falls back to
 *       Minecraft's font without an error line.</li>
 *   <li>1564-ui-font-settings: the Themes drawer has the Font and Main Menu rows, a real click on Font steps it and it
 *       survives a reload; Open Fonts Folder opens the import folder (the test hook's log line).</li>
 *   <li>1565-ui-mainmenu-styles: Match Menu Theme follows the menu's theme, Separate Theme its own, Custom every look
 *       setting (scene, speed, splash, panel); each title screen extracts cleanly, the panel holds every column button,
 *       the splash text is gone when switched off, Dungeon Sim is a themed vanilla button.</li>
 *   <li>1566-ui-mainmenu-scenes: a screenshot of every background in its theme: Ocean's waterfall reads blue, Amber's
 *       embers orange, Light bright, Dark dark, and each one moves between two frames.</li>
 *   <li>1567-ui-mainmenu-real-name: with Name Changer giving his name a red custom name, the title screen's
 *       "Playing as" chip still says the account's real name (text and screenshot pixels), while the same name drawn
 *       elsewhere is replaced (the control that Name Changer was on).</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class FontsMainMenuSuite implements FabricClientGameTest {

    static final String[] TITLE_CASES = {"1565-ui-mainmenu-styles", "1566-ui-mainmenu-scenes",
            "1567-ui-mainmenu-real-name", "1568-ui-mainmenu-mod-settings", "1569-ui-vanilla-menus"};
    static final String[] WORLD_CASES = {"1561-ui-font-menu-only", "1562-ui-font-everything", "1563-ui-font-import",
            "1564-ui-font-settings", "1570-ui-vanilla-pause"};

    static final String FC = "fonts.FontConfig";
    static final String CF = "fonts.CustomFonts";
    static final String CHOICES = "fonts.FontChoices";
    static final String MM = "mainmenu.MainMenuThemeConfig";
    static final String PROBE = "The quick brown fox jumps 0123456789";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (dev.testkit.harness.ScenarioList.active() || dev.testkit.harness.ModGate.missing() != null) {
            for (String n : TITLE_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : WORLD_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            return;
        }
        boolean anyTitle = any(TITLE_CASES);
        boolean anyWorld = any(WORLD_CASES);
        if (!anyTitle && !anyWorld) {
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
        UiCase.run(ctx, "1565-ui-mainmenu-styles", FontsMainMenuSuite::styles);
        UiCase.run(ctx, "1566-ui-mainmenu-scenes", FontsMainMenuSuite::scenes);
        UiCase.run(ctx, "1567-ui-mainmenu-real-name", FontsMainMenuSuite::realName);
        UiCase.run(ctx, "1568-ui-mainmenu-mod-settings", FontsMainMenuSuite::modSettings);
        UiCase.run(ctx, "1569-ui-vanilla-menus", VanillaThemeCases::menus);
        if (anyWorld) {
            try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
                ctx.waitTicks(40);
                SafeWorld.apply(ctx);
                UiCase.run(ctx, "1561-ui-font-menu-only", FontsMainMenuSuite::menuOnly);
                UiCase.run(ctx, "1562-ui-font-everything", FontsMainMenuSuite::everything);
                UiCase.run(ctx, "1563-ui-font-import", FontsMainMenuSuite::importFont);
                UiCase.run(ctx, "1564-ui-font-settings", FontsMainMenuSuite::settings);
                UiCase.run(ctx, "1570-ui-vanilla-pause", VanillaThemeCases::pause);
            }
        }
    }

    private static boolean any(String[] names) {
        for (String n : names) {
            if (UiCase.selected(n)) {
                return true;
            }
        }
        return false;
    }

    // =================================================================================================================
    // text measurement
    // =================================================================================================================

    private static Field textField;

    static FormattedCharSequence textOf(GuiTextRenderState t) {
        try {
            if (textField == null) {
                Field f = GuiTextRenderState.class.getDeclaredField("text");
                f.setAccessible(true);
                textField = f;
            }
            return (FormattedCharSequence) textField.get(t);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("GuiTextRenderState.text", e);
        }
    }

    static String plain(FormattedCharSequence seq) {
        StringBuilder sb = new StringBuilder();
        seq.accept((i, style, cp) -> {
            sb.appendCodePoint(cp);
            return true;
        });
        return sb.toString();
    }

    /** Every text a screen draws in one extract, with the width its PREPARED glyphs cover (first copy of each string). */
    static Map<String, Integer> screenTexts(Minecraft mc, Screen s) {
        GuiRenderState state = new GuiRenderState();
        GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
        s.extractRenderStateWithTooltipAndSubtitles(g, -1, -1, 0.5f);
        Map<String, Integer> out = new LinkedHashMap<>();
        state.forEachText(t -> {
            String str = plain(textOf(t)).strip();
            ScreenRectangle b = t.bounds();
            if (str.length() >= 5 && b != null && !out.containsKey(str)) {
                out.put(str, b.width());
            }
        });
        return out;
    }

    /** The prepared width of {@code s} drawn outside any screen - the path the HUD and chat take. */
    static int looseWidth(Minecraft mc, String s) {
        GuiRenderState state = new GuiRenderState();
        GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
        g.text(mc.font, s, 4, 4, 0xFFFFFFFF, false);
        int[] w = {-1};
        state.forEachText(t -> {
            ScreenRectangle b = t.bounds();
            w[0] = b == null ? -1 : b.width();
        });
        return w[0];
    }

    static void useFont(UiCase c, String id, String scope, int size) {
        c.onClient(mc -> {
            Object cfg = Mod.cfg(FC);
            Mod.call(cfg, "setFont", id);
            Mod.call(cfg, "setScope", Mod.enumValue(FC + "$Scope", scope));
            Mod.call(cfg, "setSize", size);
            Mod.staticCall(CF, "refresh", mc);
            return null;
        });
        c.ticks(2);
        String built = c.onClient(mc -> (String) Mod.staticCall(CF, "builtId"));
        if (!built.equals(id)) {
            throw new AssertionError("asked for font " + id + " but " + built + " is built; last error: "
                    + c.onClient(mc -> Mod.staticCall(CF, "lastError")));
        }
    }

    record Measure(Map<String, Integer> menu, Map<String, Integer> options, int loose, int width) {
    }

    static Measure measure(UiCase c) {
        return c.onClient(mc -> {
            Screen menu = themesDrawer(mc);
            Map<String, Integer> m = screenTexts(mc, menu);
            Screen options = new net.minecraft.client.gui.screens.options.OptionsScreen(null, mc.options, false);
            McCompat.setScreen(mc, options);
            Map<String, Integer> o = screenTexts(mc, options);
            McCompat.setScreen(mc, null);
            return new Measure(m, o, looseWidth(mc, PROBE), mc.font.width(PROBE));
        });
    }

    static Screen themesDrawer(Minecraft mc) {
        try {
            Screen s = MenuACases.open(mc);
            Mod.call(s, "openFeature", MenuACases.leaf("MenuThemeTab"));
            return s;
        } catch (Exception e) {
            throw new AssertionError("could not open the Themes drawer", e);
        }
    }

    /** {changed, compared}: strings in both maps whose widths differ. */
    static int[] diff(Map<String, Integer> a, Map<String, Integer> b) {
        int changed = 0;
        int compared = 0;
        for (Map.Entry<String, Integer> e : a.entrySet()) {
            Integer other = b.get(e.getKey());
            if (other == null) {
                continue;
            }
            compared++;
            if (!other.equals(e.getValue())) {
                changed++;
            }
        }
        return new int[]{changed, compared};
    }

    static void restoreFont(UiCase c, Object[] saved) {
        c.onClient(mc -> {
            Object cfg = Mod.cfg(FC);
            Mod.call(cfg, "setFont", saved[0]);
            Mod.call(cfg, "setScope", saved[1]);
            Mod.call(cfg, "setSize", saved[2]);
            Mod.call(cfg, "save");
            Mod.staticCall(CF, "refresh", mc);
            return null;
        });
    }

    static Object[] saveFont(UiCase c) {
        return c.onClient(mc -> {
            Object cfg = Mod.cfg(FC);
            return new Object[]{Mod.call(cfg, "getFont"), Mod.call(cfg, "getScope"), Mod.call(cfg, "getSize")};
        });
    }

    // =================================================================================================================
    // 1561 / 1562
    // =================================================================================================================

    static void menuOnly(UiCase c) {
        Object[] saved = saveFont(c);
        try {
            useFont(c, "minecraft", "MENU", 100);
            Measure base = measure(c);
            Measure again = measure(c);
            int[] control = diff(base.menu(), again.menu());
            c.note("control (Minecraft font twice): menu texts " + control[1] + ", changed " + control[0]
                    + "; options texts " + base.options().size() + ", probe width " + base.width());
            c.check(control[1] >= 10, "the Themes drawer drew only " + control[1] + " texts of 5+ letters: " + base.menu().keySet());
            c.check(control[0] == 0, "the same font measured twice changed " + control[0] + " widths: the measurement is unstable");
            c.check(base.options().size() >= 5, "the Options screen drew only " + base.options().size() + " texts");

            int builds = c.onClient(mc -> (Integer) Mod.staticCall(CF, "builds"));
            useFont(c, "bundled:lato", "MENU", 100);
            int builds2 = c.onClient(mc -> (Integer) Mod.staticCall(CF, "builds"));
            c.check(builds2 > builds, "choosing Lato built no font set (" + builds + " -> " + builds2 + ")");
            Measure lato = measure(c);
            int[] menu = diff(base.menu(), lato.menu());
            int[] options = diff(base.options(), lato.options());
            c.note("Lato, Mod Menu Only: menu " + menu[0] + "/" + menu[1] + " widths changed, options " + options[0] + "/"
                    + options[1] + ", loose " + base.loose() + " -> " + lato.loose() + ", Font.width " + base.width()
                    + " -> " + lato.width());
            c.check(menu[1] >= 10 && menu[0] >= menu[1] * 0.8, "the mod menu's text kept Minecraft's widths: only "
                    + menu[0] + " of " + menu[1] + " changed");
            c.check(options[0] == 0, options[0] + " of the Options screen's texts changed width under Mod Menu Only");
            c.check(lato.loose() == base.loose(), "text drawn outside a screen (the HUD path) changed width: "
                    + base.loose() + " -> " + lato.loose());
            c.check(lato.width() == base.width(), "Font.width outside the menu changed: " + base.width() + " -> " + lato.width());
            menuShot(c, "lato-menu-only");
        } finally {
            restoreFont(c, saved);
        }
    }

    static void everything(UiCase c) {
        Object[] saved = saveFont(c);
        try {
            useFont(c, "minecraft", "EVERYTHING", 100);
            Measure base = measure(c);
            useFont(c, "bundled:lato", "EVERYTHING", 100);
            Measure lato = measure(c);
            int[] menu = diff(base.menu(), lato.menu());
            int[] options = diff(base.options(), lato.options());
            c.note("Lato, Everything: menu " + menu[0] + "/" + menu[1] + ", options " + options[0] + "/" + options[1]
                    + ", loose " + base.loose() + " -> " + lato.loose() + ", Font.width " + base.width() + " -> " + lato.width());
            c.check(menu[1] >= 10 && menu[0] >= menu[1] * 0.8, "Everything: the mod menu kept Minecraft's widths ("
                    + menu[0] + "/" + menu[1] + ")");
            c.check(options[1] >= 5 && options[0] >= options[1] * 0.8, "Everything: the Options screen kept Minecraft's"
                    + " widths (" + options[0] + "/" + options[1] + ")");
            c.check(lato.loose() != base.loose(), "Everything: HUD-path text kept its width " + base.loose());
            c.check(lato.width() != base.width(), "Everything: Font.width kept " + base.width());
            menuShot(c, "lato-everything");

            // Every bundled font builds and measures differently from Minecraft's.
            List<String> widths = new ArrayList<>();
            for (String id : new String[]{"bundled:lato", "bundled:space_mono", "bundled:vt323", "bundled:press_start"}) {
                useFont(c, id, "EVERYTHING", 100);
                int w = c.onClient(mc -> mc.font.width(PROBE));
                widths.add(id + "=" + w);
                c.check(w != base.width(), id + " measures the probe exactly like Minecraft's font (" + w + ")");
            }
            c.note("probe widths: minecraft=" + base.width() + " " + widths);

            // Font Size moves the width.
            useFont(c, "bundled:space_mono", "EVERYTHING", 100);
            int w100 = c.onClient(mc -> mc.font.width(PROBE));
            useFont(c, "bundled:space_mono", "EVERYTHING", 130);
            int w130 = c.onClient(mc -> mc.font.width(PROBE));
            c.note("Space Mono at 100% " + w100 + ", at 130% " + w130);
            c.check(w130 > w100 + 10, "Font Size 130% did not widen the text (" + w100 + " -> " + w130 + ")");

            // A resource reload closes the default font's providers the custom set borrows: the set must be dropped and
            // rebuilt from the new ones (a stale one would read freed glyph data), and the font must still apply after.
            int buildsBefore = c.onClient(mc -> (Integer) Mod.staticCall(CF, "builds"));
            java.util.concurrent.CompletableFuture<?> reload = c.onClient(Minecraft::reloadResourcePacks);
            for (int i = 0; i < 1200 && !reload.isDone(); i++) {
                c.ticks(1);
            }
            c.ticks(10);
            int buildsAfter = c.onClient(mc -> (Integer) Mod.staticCall(CF, "builds"));
            String builtAfter = c.onClient(mc -> (String) Mod.staticCall(CF, "builtId"));
            int wAfter = c.onClient(mc -> mc.font.width(PROBE));
            c.note("resource reload: done " + reload.isDone() + ", builds " + buildsBefore + " -> " + buildsAfter + ", built "
                    + builtAfter + ", width " + wAfter);
            c.check(reload.isDone(), "the resource reload never finished");
            c.check(buildsAfter > buildsBefore && "bundled:space_mono".equals(builtAfter) && wAfter == w130,
                    "after a resource reload the font was not rebuilt the same (builds " + buildsBefore + " -> " + buildsAfter
                            + ", built " + builtAfter + ", width " + wAfter + " vs " + w130 + ")");

            // Back to Minecraft: every width exactly as before.
            useFont(c, "minecraft", "EVERYTHING", 100);
            Measure back = measure(c);
            int[] menuBack = diff(base.menu(), back.menu());
            int[] optionsBack = diff(base.options(), back.options());
            c.check(menuBack[0] == 0 && optionsBack[0] == 0 && back.loose() == base.loose() && back.width() == base.width(),
                    "going back to Minecraft did not restore every width: menu " + menuBack[0] + ", options "
                            + optionsBack[0] + ", loose " + back.loose() + "/" + base.loose());
        } finally {
            restoreFont(c, saved);
        }
    }

    // =================================================================================================================
    // 1563 import
    // =================================================================================================================

    static void importFont(UiCase c) throws Exception {
        Object[] saved = saveFont(c);
        Path folder = c.onClient(mc -> (Path) Mod.staticCall(CHOICES, "importFolder"));
        Path good = folder.resolve("Testkit Probe Mono.ttf");
        Path broken = folder.resolve("Testkit Broken.ttf");
        try (InputStream in = Mod.cls(CHOICES).getResourceAsStream("/assets/killer560smod/fonts/SpaceMono-Regular.ttf")) {
            if (in == null) {
                throw new AssertionError("the jar has no bundled Space Mono to copy");
            }
            Files.write(good, in.readAllBytes());
        }
        Files.write(broken, "this is not a font file at all, just text".getBytes());
        try {
            useFont(c, "bundled:space_mono", "EVERYTHING", 100);
            int bundled = c.onClient(mc -> mc.font.width(PROBE));
            useFont(c, "minecraft", "EVERYTHING", 100);
            int vanilla = c.onClient(mc -> mc.font.width(PROBE));

            // The Font row's own step finds the dropped file (it re-reads the folder), no Reload needed.
            String target = "file:" + good.getFileName();
            String reached = c.onClient(mc -> {
                for (int i = 0; i < 40; i++) {
                    Mod.staticCall("fonts.FontSettings", "stepFont", 1);
                    String f = (String) Mod.call(Mod.cfg(FC), "getFont");
                    if (f.equals(target)) {
                        return f;
                    }
                }
                return (String) Mod.call(Mod.cfg(FC), "getFont");
            });
            c.check(target.equals(reached), "stepping Font never reached the imported file (ended on " + reached + ")");
            c.onClient(mc -> {
                Mod.staticCall(CF, "refresh", mc);
                return null;
            });
            c.ticks(2);
            String built = c.onClient(mc -> (String) Mod.staticCall(CF, "builtId"));
            int imported = c.onClient(mc -> mc.font.width(PROBE));
            c.note("probe width: Minecraft " + vanilla + ", bundled Space Mono " + bundled + ", imported copy " + imported
                    + " (built " + built + ")");
            c.check(target.equals(built), "the imported font was not built: " + built);
            c.check(imported == bundled, "the imported copy of Space Mono measures " + imported + ", the bundled one " + bundled);
            c.check(imported != vanilla, "the imported font measures like Minecraft's (" + imported + ")");
            menuShot(c, "imported");

            useFont(c, "minecraft", "EVERYTHING", 100);
            c.onClient(mc -> {
                Object cfg = Mod.cfg(FC);
                Mod.call(cfg, "setFont", "file:" + broken.getFileName());
                Mod.staticCall(CF, "reloadNow", mc);
                return null;
            });
            c.ticks(2);
            String err = c.onClient(mc -> (String) Mod.staticCall(CF, "lastError"));
            String builtBroken = c.onClient(mc -> (String) Mod.staticCall(CF, "builtId"));
            int brokenWidth = c.onClient(mc -> mc.font.width(PROBE));
            c.note("broken file: error '" + err + "', built " + builtBroken + ", width " + brokenWidth);
            c.check(err != null && err.startsWith("Could not load"), "a broken font file gave no message: " + err);
            c.check("minecraft".equals(builtBroken) && brokenWidth == vanilla,
                    "a broken font file did not fall back to Minecraft's font (built " + builtBroken + ", width " + brokenWidth + ")");
        } finally {
            restoreFont(c, saved);
            Files.deleteIfExists(good);
            Files.deleteIfExists(broken);
            c.onClient(mc -> Mod.staticCall(CHOICES, "rescan"));
        }
    }

    // =================================================================================================================
    // 1564 settings rows
    // =================================================================================================================

    static AbstractWidget row(Screen s, String prefix) {
        for (AbstractWidget w : MenuACases.paneRows(s)) {
            if (ModScreenDriver.label(w).startsWith(prefix)) {
                return w;
            }
        }
        return null;
    }

    static List<String> labels(Screen s) {
        List<String> out = new ArrayList<>();
        for (AbstractWidget w : MenuACases.paneRows(s)) {
            out.add(ModScreenDriver.label(w));
        }
        return out;
    }

    static void settings(UiCase c) throws Exception {
        Object[] saved = saveFont(c);
        Path file = c.onClient(mc -> (Path) Mod.staticCall(FC, "path"));
        byte[] before = Files.exists(file) ? Files.readAllBytes(file) : null;
        try {
            useFont(c, "minecraft", "MENU", 100);
            c.onClient(mc -> {
                Mod.call(Mod.cfg(FC), "save");
                return null;
            });
            String why = c.onClient(mc -> {
                Screen s = themesDrawer(mc);
                for (String want : new String[]{"Font: Minecraft", "Font Applies To: Mod Menu Only", "Font Size: 100%",
                        "Open Fonts Folder", "Reload Fonts", "Main Menu Style:"}) {
                    if (row(s, want) == null) {
                        return "no '" + want + "' row in the Themes drawer: " + labels(s);
                    }
                }
                AbstractWidget font = row(s, "Font: ");
                ModScreenDriver.press(font);
                String now = (String) Mod.call(Mod.cfg(FC), "getFont");
                if (!"bundled:lato".equals(now)) {
                    return "a click on the Font row set " + now + ", expected the next font, Lato";
                }
                Screen s2 = themesDrawer(mc);
                if (row(s2, "Font: Lato") == null) {
                    return "the rebuilt drawer does not show Font: Lato: " + labels(s2);
                }
                Mod.staticCall(FC, "load");
                String reloaded = (String) Mod.call(Mod.cfg(FC), "getFont");
                if (!"bundled:lato".equals(reloaded)) {
                    return "the chosen font did not survive a reload: " + reloaded;
                }
                return null;
            });
            c.check(why == null, why);
            long mark = LogTap.mark();
            c.onClient(mc -> {
                Screen s = themesDrawer(mc);
                ModScreenDriver.press(row(s, "Open Fonts Folder"));
                return null;
            });
            c.ticks(2);
            boolean opened = LogTap.since(mark).stream().anyMatch(l -> l.contains("would open") && l.contains("killer560smod-fonts"));
            c.check(opened, "Open Fonts Folder did not try to open the fonts folder: " + LogTap.since(mark));
        } finally {
            restoreFont(c, saved);
            if (before == null) {
                Files.deleteIfExists(file);
            } else {
                Files.write(file, before);
            }
            c.onClient(mc -> {
                Mod.staticCall(FC, "load");
                return null;
            });
        }
    }

    // =================================================================================================================
    // main menu
    // =================================================================================================================

    record MmState(Object style, Object theme, Object scene, Object density, int speed, boolean splash, boolean panel,
                   boolean particles, boolean enabled, Object menuTheme) {
    }

    static MmState saveMm(UiCase c) {
        return c.onClient(mc -> {
            Object cfg = Mod.cfg(MM);
            return new MmState(Mod.call(cfg, "getStyle"), Mod.call(cfg, "getTheme"), Mod.call(cfg, "getScene"),
                    Mod.call(cfg, "getDensity"), (Integer) Mod.call(cfg, "getSpeed"), (Boolean) Mod.call(cfg, "isSplash"),
                    (Boolean) Mod.call(cfg, "isPanel"), (Boolean) Mod.call(cfg, "isParticles"),
                    (Boolean) Mod.call(cfg, "isEnabled"), Mod.call(Mod.cfg("gui.MenuConfig"), "getTheme"));
        });
    }

    static void restoreMm(UiCase c, MmState s) {
        c.onClient(mc -> {
            Object cfg = Mod.cfg(MM);
            Mod.call(cfg, "setStyle", s.style());
            Mod.call(cfg, "setTheme", s.theme());
            Mod.call(cfg, "setScene", s.scene());
            Mod.call(cfg, "setDensity", s.density());
            Mod.call(cfg, "setSpeed", s.speed());
            Mod.call(cfg, "setSplash", s.splash());
            Mod.call(cfg, "setPanel", s.panel());
            Mod.call(cfg, "setParticles", s.particles());
            Mod.call(cfg, "setEnabled", s.enabled());
            Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", s.menuTheme());
            McCompat.setScreen(mc, new TitleScreen());
            return null;
        });
    }

    static Object menuTheme(String name) {
        return Mod.enumValue("gui.MenuTheme", name);
    }

    static void setMm(Minecraft mc, String style, String theme, String scene, int speed, boolean splash, boolean panel) {
        Object cfg = Mod.cfg(MM);
        Mod.call(cfg, "setEnabled", true);
        Mod.call(cfg, "setParticles", true);
        Mod.call(cfg, "setStyle", Mod.enumValue(MM + "$Style", style));
        Mod.call(cfg, "setTheme", menuTheme(theme));
        Mod.call(cfg, "setScene", Mod.enumValue(MM + "$Scene", scene));
        Mod.call(cfg, "setDensity", Mod.enumValue(MM + "$Density", "NORMAL"));
        Mod.call(cfg, "setSpeed", speed);
        Mod.call(cfg, "setSplash", splash);
        Mod.call(cfg, "setPanel", panel);
    }

    /** {scene name, colours, splash, panel, speed} of MainMenuLook.current(). */
    static Object[] look() {
        Object look = Mod.staticCall("mainmenu.MainMenuLook", "current");
        return new Object[]{String.valueOf(Mod.call(look, "scene")), Mod.call(look, "colours"), Mod.call(look, "splash"),
                Mod.call(look, "panel"), Mod.call(look, "speed")};
    }

    static String hex(int[] c) {
        StringBuilder sb = new StringBuilder();
        for (int v : c) {
            sb.append(String.format(Locale.ROOT, "%08X ", v));
        }
        return sb.toString().trim();
    }

    static void styles(UiCase c) {
        MmState saved = saveMm(c);
        try {
            record Expect(String label, String menu, String style, String theme, String scene, String wantScene,
                          String coloursOf, boolean splash, boolean panel, int speed) {
            }
            List<Expect> cases = List.of(
                    new Expect("match amber", "AMBER", "MATCH", "OCEAN", "PLAIN", "EMBERS", "AMBER", true, true, 100),
                    new Expect("match ocean", "OCEAN", "MATCH", "LIGHT", "PLAIN", "WATERFALL", "OCEAN", true, true, 100),
                    new Expect("separate light", "AMBER", "SEPARATE", "LIGHT", "PLAIN", "SOFT_LIGHT", "LIGHT", true, true, 100),
                    new Expect("separate dark", "OCEAN", "SEPARATE", "DARK", "AURORA", "STARFIELD", "DARK", true, true, 100),
                    new Expect("custom aurora", "AMBER", "CUSTOM", "OCEAN", "AURORA", "AURORA", "OCEAN", false, false, 200),
                    new Expect("custom auto", "AMBER", "CUSTOM", "DARK", "AUTO", "STARFIELD", "DARK", true, true, 50));
            for (Expect e : cases) {
                String why = c.onClient(mc -> {
                    Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", menuTheme(e.menu()));
                    setMm(mc, e.style(), e.theme(), e.scene(), e.speed(), e.splash(), e.panel());
                    Object[] l = look();
                    int[] want = (int[]) Mod.call(menuTheme(e.coloursOf()), "base");
                    if (!e.wantScene().equals(l[0])) {
                        return e.label() + ": scene " + l[0] + ", expected " + e.wantScene();
                    }
                    if (!java.util.Arrays.equals(want, (int[]) l[1])) {
                        return e.label() + ": colours " + hex((int[]) l[1]) + ", expected " + e.coloursOf() + "'s " + hex(want);
                    }
                    if ((Boolean) l[2] != e.splash() || (Boolean) l[3] != e.panel()
                            || Math.abs((Float) l[4] - e.speed() / 100f) > 0.001f) {
                        return e.label() + ": splash/panel/speed " + l[2] + "/" + l[3] + "/" + l[4];
                    }
                    return null;
                });
                c.check(why == null, why);
                // The screen itself.
                c.onClient(mc -> {
                    McCompat.setScreen(mc, new TitleScreen());
                    return null;
                });
                c.ticks(4);
                String screenWhy = c.onClient(mc -> {
                    Screen s = McCompat.screen(mc);
                    if (!(s instanceof TitleScreen)) {
                        return e.label() + ": no title screen up";
                    }
                    Frames.Drawn d = Frames.extractFrames(mc, s, 3);
                    if ((Boolean) Mod.field("mainmenu.MainMenuTheme", "failed")) {
                        return e.label() + ": the themed main menu turned itself off after an error";
                    }
                    // Each scene, the panel and each button is ONE batched element (GuiRects), so a full screen is ~10.
                    if (d.elements() < 8) {
                        return e.label() + ": only " + d.elements() + " elements drawn";
                    }
                    int[] card = (int[]) Mod.staticCall("mainmenu.MainMenuChrome", "cardBox", s);
                    if (card == null) {
                        return e.label() + ": the layout placed no panel";
                    }
                    int inside = 0;
                    boolean simThemed = false;
                    for (AbstractWidget w : Screens.getWidgets(s)) {
                        if (!w.visible || w.getWidth() < 90 || w instanceof net.minecraft.client.gui.components.PlainTextButton) {
                            continue;
                        }
                        boolean in = w.getX() >= card[0] && w.getY() >= card[1] && w.getX() + w.getWidth() <= card[0] + card[2]
                                && w.getY() + w.getHeight() <= card[1] + card[3];
                        if (!in) {
                            return e.label() + ": " + w.getMessage().getString() + " @" + w.getX() + "," + w.getY()
                                    + " is outside the panel " + java.util.Arrays.toString(card);
                        }
                        inside++;
                        if (w.getMessage().getString().equals("Dungeon Sim")) {
                            simThemed = w instanceof net.minecraft.client.gui.components.AbstractButton;
                        }
                    }
                    if (inside < 6) {
                        return e.label() + ": only " + inside + " buttons in the panel";
                    }
                    if (!simThemed) {
                        return e.label() + ": Dungeon Sim is not a vanilla button the theme draws";
                    }
                    // Splash text drawn or not.
                    Object splash = Mod.field(s, "splash");
                    String splashText = splash == null ? null : net.minecraft.ChatFormatting.stripFormatting(plainSplash(splash));
                    boolean drawn = false;
                    if (splashText != null) {
                        GuiRenderState state = new GuiRenderState();
                        s.extractRenderStateWithTooltipAndSubtitles(new GuiGraphicsExtractor(mc, state, -1, -1), -1, -1, 0.5f);
                        boolean[] hit = {false};
                        state.forEachText(t -> hit[0] |= plain(textOf(t)).equals(splashText));
                        drawn = hit[0];
                    }
                    if (splashText != null && drawn != e.splash()) {
                        return e.label() + ": splash '" + splashText + "' drawn=" + drawn + " with Splash Text " + e.splash();
                    }
                    // Button labels: no drop shadow on a light theme (it smears dark text), a shadow on a dark one.
                    GuiRenderState state = new GuiRenderState();
                    s.extractRenderStateWithTooltipAndSubtitles(new GuiGraphicsExtractor(mc, state, -1, -1), -1, -1, 0.5f);
                    Boolean[] shadow = {null};
                    state.forEachText(t -> {
                        if (plain(textOf(t)).equals("Singleplayer")) {
                            shadow[0] = dropShadow(t);
                        }
                    });
                    // No footer texts (killer560, 2026-10-15): no version line, no mod name/version, no copyright -
                    // neither drawn nor as a widget (the credits link is removed, not just hidden).
                    List<String> footer = new ArrayList<>();
                    state.forEachText(t -> {
                        String str = plain(textOf(t));
                        if (str.startsWith("Minecraft ") || str.startsWith("Killer560's Mod") || str.contains("Copyright")) {
                            footer.add(str);
                        }
                    });
                    if (!footer.isEmpty()) {
                        return e.label() + ": footer text still drawn: " + footer;
                    }
                    for (AbstractWidget w : Screens.getWidgets(s)) {
                        if (w instanceof net.minecraft.client.gui.components.PlainTextButton) {
                            return e.label() + ": the copyright link is still a widget on the title screen";
                        }
                    }
                    boolean light = e.coloursOf().equals("LIGHT");
                    if (shadow[0] == null) {
                        return e.label() + ": no Singleplayer label drawn";
                    }
                    if (shadow[0] == light) {
                        return e.label() + ": the Singleplayer label's shadow is " + shadow[0] + " on a "
                                + (light ? "light" : "dark") + " theme";
                    }
                    return null;
                });
                c.check(screenWhy == null, screenWhy);
            }
        } finally {
            restoreMm(c, saved);
        }
    }

    static boolean dropShadow(GuiTextRenderState t) {
        try {
            Field f = GuiTextRenderState.class.getDeclaredField("dropShadow");
            f.setAccessible(true);
            return f.getBoolean(t);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("GuiTextRenderState.dropShadow", e);
        }
    }

    static String plainSplash(Object splashRenderer) {
        for (Field f : splashRenderer.getClass().getDeclaredFields()) {
            if (net.minecraft.network.chat.Component.class.isAssignableFrom(f.getType())) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(splashRenderer);
                    return v == null ? null : ((net.minecraft.network.chat.Component) v).getString();
                } catch (IllegalAccessException ignored) {
                }
            }
        }
        return null;
    }

    // ---- 1566 screenshots ---------------------------------------------------------------------------------------

    record Pic(double r, double g, double b, double lum, BufferedImage img) {
    }

    static Pic pic(Path p) throws Exception {
        BufferedImage img = ImageIO.read(p.toFile());
        long r = 0;
        long g = 0;
        long b = 0;
        long n = 0;
        for (int y = 0; y < img.getHeight(); y += 2) {
            for (int x = 0; x < img.getWidth(); x += 2) {
                int c = img.getRGB(x, y);
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
                n++;
            }
        }
        double rr = r / (double) n;
        double gg = g / (double) n;
        double bb = b / (double) n;
        return new Pic(rr, gg, bb, (0.299 * rr + 0.587 * gg + 0.114 * bb) / 255.0, img);
    }

    /** Share of pixels (sampled) whose summed RGB moved by more than 30 between two frames. */
    static double changed(BufferedImage a, BufferedImage b) {
        long n = 0;
        long moved = 0;
        for (int y = 0; y < Math.min(a.getHeight(), b.getHeight()); y += 2) {
            for (int x = 0; x < Math.min(a.getWidth(), b.getWidth()); x += 2) {
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                int d = Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF))
                        + Math.abs((p & 0xFF) - (q & 0xFF));
                n++;
                if (d > 30) {
                    moved++;
                }
            }
        }
        return n == 0 ? 0 : moved / (double) n;
    }

    /** Pixels whose summed RGB moved by more than 24 in the outer fifth of each side, above the bottom tenth. */
    static int sideMoved(BufferedImage a, BufferedImage b) {
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        int moved = 0;
        for (int y = 0; y < h * 9 / 10; y++) {
            for (int x = 0; x < w; x++) {
                if (x >= w / 5 && x < w - w / 5) {
                    continue;
                }
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                int d = Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF))
                        + Math.abs((p & 0xFF) - (q & 0xFF));
                if (d > 24) {
                    moved++;
                }
            }
        }
        return moved;
    }

    static Path shot(UiCase c, String label) {
        Path p = c.ctx().takeScreenshot(Report.fileName(c.name() + "-" + label));
        Report.screenshot(c.name(), p);
        c.note("screenshot " + label + ": " + p);
        String copy = System.getenv("TESTKIT_FONTS_SHOTS");
        if (copy != null && !copy.isBlank()) {
            try {
                Files.createDirectories(Path.of(copy));
                Files.copy(p, Path.of(copy).resolve(c.name() + "-" + label + ".png"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {
            }
        }
        return p;
    }

    /** The Themes drawer on screen with a chat line behind it, at 1600x900 GUI 2, photographed. */
    static void menuShot(UiCase c, String label) {
        int[] win0 = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.options.guiScale().get()});
        try {
            setWindow(c, 1600, 900, 2);
            c.onClient(mc -> {
                McCompat.chat(mc).addClientSystemMessage(net.minecraft.network.chat.Component.literal(
                        "Chat behind the menu: " + PROBE));
                themesDrawer(mc);
                return null;
            });
            c.ticks(6);
            shot(c, label);
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            setWindow(c, win0[0], win0[1], win0[2]);
        }
    }

    static void scenes(UiCase c) throws Exception {
        MmState saved = saveMm(c);
        int[] win0 = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.options.guiScale().get()});
        try {
            setWindow(c, 1600, 900, 2);
            String[][] scenes = {{"EMBERS", "AMBER"}, {"WATERFALL", "OCEAN"}, {"STARFIELD", "DARK"},
                    {"SOFT_LIGHT", "LIGHT"}, {"AURORA", "CUSTOM"}, {"PLAIN", "AMBER"}};
            List<String> summary = new ArrayList<>();
            for (String[] sc : scenes) {
                c.onClient(mc -> {
                    setMm(mc, "CUSTOM", sc[1], sc[0], 100, true, true);
                    McCompat.setScreen(mc, new TitleScreen());
                    return null;
                });
                c.ticks(40); // past the title screen's fade-in
                Pic a = pic(shot(c, sc[0].toLowerCase(Locale.ROOT)));
                c.ticks(30); // Soft Light drifts slowly: 12 ticks moved 0.05% on 26.2
                Pic b = pic(c.ctx().takeScreenshot(Report.fileName(c.name() + "-" + sc[0].toLowerCase(Locale.ROOT) + "-b")));
                double moved = changed(a.img(), b.img());
                // The panel is opaque (killer560, 2026-10-15): its padding beside the buttons must not change while the
                // background animates behind it.
                int[] card = c.onClient(mc -> (int[]) Mod.staticCall("mainmenu.MainMenuChrome", "cardBox", McCompat.screen(mc)));
                int screenW = c.onClient(mc -> McCompat.screen(mc).width);
                if (card != null && !sc[0].equals("PLAIN")) {
                    double k = a.img().getWidth() / (double) screenW;
                    int x0 = (int) Math.ceil((card[0] + 2) * k);
                    int x1 = (int) Math.floor((card[0] + 8) * k);
                    int y0 = (int) Math.ceil((card[1] + 3) * k);
                    int y1 = (int) Math.floor((card[1] + card[3] - 3) * k);
                    int seen = 0;
                    int diff = 0;
                    for (int y = y0; y < y1; y++) {
                        for (int x = x0; x < x1; x++) {
                            seen++;
                            if (a.img().getRGB(x, y) != b.img().getRGB(x, y)) {
                                diff++;
                            }
                        }
                    }
                    c.note(sc[0] + ": panel padding " + seen + " px, " + diff + " changed between frames");
                    c.check(seen > 100 && diff == 0, sc[0] + ": the background shows through the panel (" + diff + " of "
                            + seen + " padding pixels changed between frames)");
                }
                summary.add(String.format(Locale.ROOT, "%s/%s rgb %.0f,%.0f,%.0f lum %.2f moved %.2f%%", sc[0], sc[1], a.r(),
                        a.g(), a.b(), a.lum(), moved * 100));
                switch (sc[0]) {
                    case "WATERFALL" -> c.check(a.b() > a.r() + 8, "the Ocean waterfall is not blue: " + summary.getLast());
                    case "EMBERS" -> c.check(a.r() > a.b() + 4, "Amber's embers are not warm: " + summary.getLast());
                    case "SOFT_LIGHT" -> c.check(a.lum() > 0.55, "the Light theme's main menu is not light: " + summary.getLast());
                    case "STARFIELD" -> c.check(a.lum() < 0.25, "the Dark starfield is not dark: " + summary.getLast());
                    default -> {
                    }
                }
                // Motion is counted in the side strips only (no splash text, no buttons): the splash pulses on every
                // scene, Plain included, so a whole-screen count cannot tell a moving background from a still one.
                int side = sideMoved(a.img(), b.img());
                summary.set(summary.size() - 1, summary.getLast() + ", sides " + side + " px");
                if (sc[0].equals("PLAIN")) {
                    c.check(side < 20, "Plain moved " + side + " px at the sides - the control is not still");
                } else {
                    c.check(side >= 20, sc[0] + " did not move at the sides between two frames: " + summary.getLast());
                }
            }
            c.note("scenes: " + summary);
            c.check(!(Boolean) c.onClient(mc -> Mod.field("mainmenu.MainMenuTheme", "failed")),
                    "the themed main menu turned itself off after an error");
        } finally {
            setWindow(c, win0[0], win0[1], win0[2]);
            restoreMm(c, saved);
        }
    }

    static void setWindow(UiCase c, int w, int h, int gui) {
        c.ctx().getInput().resizeWindow(w, h);
        c.ctx().waitTicks(3);
        c.ctx().runOnClient(m -> {
            m.options.guiScale().set(gui);
            m.resizeGui();
        });
        c.ctx().waitTicks(4);
    }

    // ---- 1568 Mod Settings from the title screen ---------------------------------------------------------------

    /**
     * killer560, 2026-10-15: "add a button on the main menu to open my mod's settings and edit them from the main menu".
     * A real click on the title screen's Mod Settings button opens the mod menu with NO world loaded; every feature's
     * drawer is opened and drawn (each throw is attributed to its tab), the 301 tab sweep and the 303 toggle sweep run
     * there too; a setting toggled through the menu is saved; Escape goes back to the title screen and the setting is still
     * set after the menu is opened again and the config re-read. Any killer560smod ERROR line fails the case (UiCase).
     */
    static void modSettings(UiCase c) throws Exception {
        c.onClient(mc -> {
            Mod.call(Mod.cfg(MM), "setEnabled", true);
            McCompat.setScreen(mc, new TitleScreen());
            return null;
        });
        c.ticks(40);
        String opened = c.onClient(mc -> {
            if (mc.level != null) {
                return "a world is loaded - this case needs the title screen";
            }
            Screen s = McCompat.screen(mc);
            for (AbstractWidget w : Screens.getWidgets(s)) {
                if (w.getMessage().getString().equals("Mod Settings")) {
                    ModScreenDriver.press(w);
                    Screen now = McCompat.screen(mc);
                    return now != null && now.getClass().getName().endsWith(".gui.ModScreen") ? null
                            : "pressing Mod Settings opened " + (now == null ? "nothing" : now.getClass().getName());
                }
            }
            return "no Mod Settings button on the title screen";
        });
        c.check(opened == null, opened);
        if (opened != null) {
            return;
        }
        // Every drawer, opened and drawn with no world.
        List<String> broken = c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            Screen s = McCompat.screen(mc);
            for (Object leaf : MenuACases.leaves()) {
                try {
                    Mod.call(s, "openFeature", leaf);
                    Frames.extract(mc, s, -1, -1);
                    Frames.extract(mc, s, s.width - 60, s.height / 2);
                } catch (Throwable t) {
                    out.add(leaf.getClass().getSimpleName() + ": " + UiCase.describe(t));
                }
            }
            return out;
        });
        c.note("drawers opened and drawn with no world: " + MenuACases.leaves().size() + ", broken: " + broken);
        c.check(broken.isEmpty(), broken.size() + " drawer(s) threw with no world loaded: " + broken);
        // World-only things refuse with a note instead of opening over the title screen.
        String bars = c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            Mod.call(s, "openFeature", MenuACases.leaf("HealthAndManaBarsTab"));
            // Edit Bars shows while Stat Bars is on.
            AbstractWidget master = row(s, "Stat Bars: OFF");
            if (master != null) {
                ModScreenDriver.press(master);
            }
            AbstractWidget edit = row(s, "Edit Bars");
            if (edit == null) {
                return "no Edit Bars row in Health and Mana Bars: " + labels(s);
            }
            ModScreenDriver.press(edit);
            Screen now = McCompat.screen(mc);
            Object notice = Mod.staticCall("gui.ModScreen", "noWorldNotice");
            if (now != s) {
                return "Edit Bars with no world opened " + now.getClass().getName();
            }
            Frames.extract(mc, s, -1, -1);
            AbstractWidget on = row(s, "Stat Bars: ON");
            if (master != null && on != null) {
                ModScreenDriver.press(on);
            }
            return notice != null && notice.toString().contains("Join a world") ? null : "no 'join a world' notice: " + notice;
        });
        c.check(bars == null, bars);
        Deny deny = Deny.load();
        TabSweep.tabs(c, deny);
        TabSweep.toggles(c, deny);
        c.check(c.onClient(mc -> mc.level == null), "a sweep loaded a world");

        // A setting changed in the menu at the title screen is saved and still set after a reopen.
        Path file = c.onClient(mc -> (Path) Mod.staticCall(FC, "path"));
        byte[] before = Files.exists(file) ? Files.readAllBytes(file) : null;
        try {
            useFont(c, "minecraft", "MENU", 100);
            c.onClient(mc -> {
                Mod.call(Mod.cfg(FC), "save");
                McCompat.setScreen(mc, new TitleScreen());
                return null;
            });
            c.ticks(5);
            String why = c.onClient(mc -> {
                for (AbstractWidget w : Screens.getWidgets(McCompat.screen(mc))) {
                    if (w.getMessage().getString().equals("Mod Settings")) {
                        ModScreenDriver.press(w);
                    }
                }
                Screen menu = McCompat.screen(mc);
                Mod.call(menu, "openFeature", MenuACases.leaf("MenuThemeTab"));
                AbstractWidget size = row(menu, "Font Size: ");
                if (size == null) {
                    return "no Font Size row: " + labels(menu);
                }
                ModScreenDriver.press(size);
                menu.keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0));
                if (!(McCompat.screen(mc) instanceof TitleScreen)) {
                    return "Escape left " + McCompat.screen(mc) + " instead of the title screen";
                }
                Mod.staticCall(FC, "load");
                int now = (Integer) Mod.call(Mod.cfg(FC), "getSize");
                return now == 110 ? null : "Font Size after the click, Escape and a reload is " + now + "%, expected 110%";
            });
            c.check(why == null, why);
        } finally {
            if (before == null) {
                Files.deleteIfExists(file);
            } else {
                Files.write(file, before);
            }
            c.onClient(mc -> {
                Mod.staticCall(FC, "load");
                McCompat.setScreen(mc, new TitleScreen());
                return null;
            });
        }
    }

    // ---- 1567 real name -----------------------------------------------------------------------------------------

    static void realName(UiCase c) throws Exception {
        // Only the switch that every jar has, so on a jar without the fix this case fails on what it draws, not on a
        // missing setting.
        boolean enabled0 = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(MM), "isEnabled"));
        String cls = "namechanger.NameChangerConfig";
        Object[] nc = c.onClient(mc -> {
            Object cfg = Mod.cfg(cls);
            return new Object[]{Mod.call(cfg, "isEnabled"), Mod.call(cfg, "isOwnNameEnabled"),
                    Mod.call(cfg, "getOwnDisplayName"), Mod.call(cfg, "getOwnColor")};
        });
        int[] win0 = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.options.guiScale().get()});
        try {
            // A window tall enough for the chip under the panel (a short one shows "Playing as" as a tooltip instead).
            setWindow(c, 1600, 900, 2);
            String real = c.onClient(mc -> mc.getUser().getName());
            c.onClient(mc -> {
                Mod.call(Mod.cfg(MM), "setEnabled", true);
                Object cfg = Mod.cfg(cls);
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setOwnNameEnabled", true);
                Mod.call(cfg, "setOwnDisplayName", "FontTester");
                Mod.call(cfg, "setOwnColor", 0xFFFF2020);
                McCompat.setScreen(mc, new TitleScreen());
                return null;
            });
            c.ticks(40);
            // Control: the same name drawn as plain text anywhere else IS replaced.
            String control = c.onClient(mc -> plain((FormattedCharSequence) Mod.staticCall("namechanger.NameReplacer",
                    "replace", FormattedCharSequence.forward(real, Style.EMPTY))));
            c.note("account " + real + "; Name Changer turns it into '" + control + "' elsewhere");
            c.check(control.contains("FontTester"), "Name Changer was not on (control): '" + control + "'");

            // The chip, as the font will see it.
            List<String> drawn = c.onClient(mc -> {
                Screen s = McCompat.screen(mc);
                GuiRenderState state = new GuiRenderState();
                GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
                s.extractRenderStateWithTooltipAndSubtitles(g, -1, -1, 0.5f);
                ScreenEvents.afterExtract(s).invoker().afterExtract(s, g, -1, -1, 0.5f);
                List<String> out = new ArrayList<>();
                state.forEachText(t -> out.add(plain((FormattedCharSequence) Mod.staticCall("namechanger.NameReplacer",
                        "replace", textOf(t)))));
                return out;
            });
            c.note("title screen texts as drawn: " + drawn);
            c.check(drawn.contains(real), "the title screen never draws the real name '" + real + "'");
            c.check(drawn.stream().noneMatch(s -> s.contains("FontTester")),
                    "the title screen draws Name Changer's name: " + drawn);
            c.check(drawn.contains("Playing as "), "no 'Playing as' line on the title screen: " + drawn);

            // Pixels: no Name Changer red anywhere on the title screen.
            Pic p = pic(shot(c, "playing-as"));
            int red = 0;
            for (int y = 0; y < p.img().getHeight(); y++) {
                for (int x = 0; x < p.img().getWidth(); x++) {
                    int px = p.img().getRGB(x, y);
                    int r = (px >> 16) & 0xFF;
                    int g = (px >> 8) & 0xFF;
                    int b = px & 0xFF;
                    if (r > 200 && g < 70 && b < 70) {
                        red++;
                    }
                }
            }
            c.note("Name Changer red pixels on the title screen: " + red);
            c.check(red < 4, red + " Name Changer red pixels on the title screen");
        } finally {
            c.onClient(mc -> {
                Object cfg = Mod.cfg(cls);
                Mod.call(cfg, "setEnabled", nc[0]);
                Mod.call(cfg, "setOwnNameEnabled", nc[1]);
                Mod.call(cfg, "setOwnDisplayName", nc[2]);
                Mod.call(cfg, "setOwnColor", nc[3]);
                return null;
            });
            setWindow(c, win0[0], win0[1], win0[2]);
            c.onClient(mc -> {
                Mod.call(Mod.cfg(MM), "setEnabled", enabled0);
                McCompat.setScreen(mc, new TitleScreen());
                return null;
            });
        }
    }
}

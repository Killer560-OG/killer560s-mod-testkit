package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 1574-ui-title-switcher (mod title-switcher-1016, killer560 2026-10-16: "make a new instance called title screen gui that
 * has our mod with a button on the title screen to swap through different gui's you have built", then "add a button to
 * replace the multiplayer button that opens a menu to swap gui's"). The switcher exists only in the Title Screen GUI
 * instance (marker file, or {@code mainmenu.TitleScreenLooks.setEnabledForTest}):
 * <ul>
 *   <li>off (every normal instance): the title screen still has Multiplayer, no Main Menu Looks and no corner switcher;</li>
 *   <li>on: Multiplayer's slot holds Main Menu Looks and the top right corner a cycling button. Real left clicks on it
 *   step through EVERY look in {@code TitleScreenLooks.looks()} (each once, in order, with the button naming it), each
 *   is screenshotted, extracts without a throw, has the theme on or off as it should, and draws a different title screen
 *   from the look before it (the two Green gradient looks only differ in Mod Settings, so they are judged by the
 *   gradient they set); a right click steps back one;</li>
 *   <li>the picker: a real click on Main Menu Looks opens it; real clicks on three entries show each (the lit chip
 *   follows, the background changes) and Done goes back to the title screen in the last pick;</li>
 *   <li>nothing was saved: the main menu and menu config files are byte-identical afterwards, every saved setting reads
 *   as before, and turning the switcher off gives back the saved look (scenes and gradient at their defaults).</li>
 * </ul>
 * Screenshots go to {@code -Dtestkit.titleSwitcherDir} (default {@code <report>/title-switcher}).
 */
final class TitleSwitcherCases {

    private TitleSwitcherCases() {
    }

    private static final String LOOKS = "mainmenu.TitleScreenLooks";

    private static Path outDir() {
        String p = System.getProperty("testkit.titleSwitcherDir", "");
        return p.isEmpty() ? Report.dir().resolve("title-switcher") : Path.of(p);
    }

    private static boolean isMultiplayer(AbstractWidget w) {
        return w.getMessage() != null && w.getMessage().getContents() instanceof TranslatableContents tc
                && "menu.multiplayer".equals(tc.getKey());
    }

    private static AbstractWidget byLabel(Screen s, String label) {
        for (AbstractWidget w : Screens.getWidgets(s)) {
            if (label.equals(w.getMessage().getString())) {
                return w;
            }
        }
        return null;
    }

    /** A real click through the screen's own mouseClicked/mouseReleased at the widget's centre. */
    private static boolean click(Screen s, AbstractWidget w, int button) {
        double x = w.getX() + w.getWidth() / 2.0;
        double y = w.getY() + w.getHeight() / 2.0;
        boolean took = s.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0)), false);
        s.mouseReleased(new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0)));
        return took;
    }

    private static Path shot(UiCase c, String label) throws Exception {
        Path p = c.ctx().takeScreenshot(Report.fileName(c.name() + "-" + label));
        Path kept = outDir().resolve(label + ".png");
        Files.createDirectories(kept.getParent());
        Files.copy(p, kept, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Report.screenshot(c.name(), p);
        return p;
    }

    /** {label, vanilla, theme, amber, green, gradient} of look i. */
    private static Object[] lookInfo(Object look) {
        return new Object[]{Mod.call(look, "label"), Mod.call(look, "vanilla"), String.valueOf(Mod.call(look, "theme")),
                String.valueOf(Mod.call(look, "amber")), String.valueOf(Mod.call(look, "green")),
                String.valueOf(Mod.call(look, "gradient"))};
    }

    private static String slug(String label) {
        return label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    static void run(UiCase c) throws Exception {
        FontsMainMenuSuite.MmState saved = FontsMainMenuSuite.saveMm(c);
        Path mmFile = c.onClient(mc -> (Path) Mod.field(Mod.ROOT + FontsMainMenuSuite.MM, "CONFIG_PATH"));
        Path menuFile = c.onClient(mc -> (Path) Mod.staticCall("gui.MenuConfig", "path"));
        // Saved state as written: both files as they are now (saved first, so the comparison is to a real file).
        c.onClient(mc -> {
            Mod.call(Mod.cfg(FontsMainMenuSuite.MM), "save");
            Mod.call(Mod.cfg("gui.MenuConfig"), "save");
            return null;
        });
        byte[] mmBefore = Files.readAllBytes(mmFile);
        byte[] menuBefore = Files.readAllBytes(menuFile);
        int[] win0 = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.options.guiScale().get()});
        try {
            FontsMainMenuSuite.setWindow(c, 1600, 900, 2);

            // ---- off: a normal instance's title screen is untouched.
            String off = c.onClient(mc -> {
                Mod.staticCall(LOOKS, "setEnabledForTest", false);
                Mod.call(Mod.cfg(FontsMainMenuSuite.MM), "setEnabled", true);
                McCompat.setScreen(mc, new TitleScreen());
                return null;
            });
            c.ticks(20);
            off = c.onClient(mc -> {
                Screen s = McCompat.screen(mc);
                boolean mp = Screens.getWidgets(s).stream().anyMatch(TitleSwitcherCases::isMultiplayer);
                if (!mp) {
                    return "with the switcher off the title screen has no Multiplayer button";
                }
                if (byLabel(s, "Main Menu Looks") != null) {
                    return "with the switcher off the title screen has Main Menu Looks";
                }
                if (Mod.staticCall(LOOKS, "cornerButton") != null && Screens.getWidgets(s).contains(Mod.staticCall(LOOKS, "cornerButton"))) {
                    return "with the switcher off the title screen has the corner switcher";
                }
                return null;
            });
            c.check(off == null, off);

            // ---- on.
            c.onClient(mc -> {
                Mod.staticCall(LOOKS, "setEnabledForTest", true);
                McCompat.setScreen(mc, new TitleScreen());
                return null;
            });
            c.ticks(40);
            String on = c.onClient(mc -> {
                Screen s = McCompat.screen(mc);
                if (Screens.getWidgets(s).stream().anyMatch(TitleSwitcherCases::isMultiplayer)) {
                    return "with the switcher on Multiplayer is still on the title screen";
                }
                AbstractWidget looks = byLabel(s, "Main Menu Looks");
                if (looks == null) {
                    return "with the switcher on there is no Main Menu Looks button";
                }
                if (looks.getWidth() != 200) {
                    return "Main Menu Looks is not a column button: width " + looks.getWidth();
                }
                Object corner = Mod.staticCall(LOOKS, "cornerButton");
                if (corner == null || !Screens.getWidgets(s).contains(corner)) {
                    return "with the switcher on there is no corner switcher";
                }
                AbstractWidget w = (AbstractWidget) corner;
                if (!Boolean.TRUE.equals(Mod.call(corner, "isCycling"))) {
                    return "the corner switcher is not a cycling button";
                }
                if (w.getX() + w.getWidth() > s.width || w.getY() > 10 || w.getX() < s.width / 2 + 100) {
                    return "the corner switcher is not in the top right corner: " + w.getX() + "," + w.getY() + " w" + w.getWidth();
                }
                return null;
            });
            c.check(on == null, on);
            if (on != null) {
                return;
            }

            // ---- every look, by real left clicks on the corner button.
            int n = c.onClient(mc -> ((List<?>) Mod.staticCall(LOOKS, "looks")).size());
            int start = c.onClient(mc -> (Integer) Mod.staticCall(LOOKS, "currentIndex"));
            c.note("looks: " + n + ", the saved one is index " + start);
            List<String> visited = new ArrayList<>();
            Set<Integer> seen = new HashSet<>();
            FontsMainMenuSuite.Pic prev = null;
            Object[] prevInfo = null;
            for (int k = 1; k <= n; k++) {
                final int want = Math.floorMod(start + k, n);
                String err = c.onClient(mc -> {
                    Screen s = McCompat.screen(mc);
                    AbstractWidget corner = (AbstractWidget) Mod.staticCall(LOOKS, "cornerButton");
                    if (!(s instanceof TitleScreen) || corner == null) {
                        return "no title screen / corner button before click " + want;
                    }
                    return click(s, corner, 0) ? null : "the corner button did not take the left click";
                });
                c.check(err == null, err);
                c.ticks(6);
                Object[] got = c.onClient(mc -> {
                    int idx = (Integer) Mod.staticCall(LOOKS, "currentIndex");
                    Object look = ((List<?>) Mod.staticCall(LOOKS, "looks")).get(Math.max(0, idx));
                    Object[] info = lookInfo(look);
                    AbstractWidget corner = (AbstractWidget) Mod.staticCall(LOOKS, "cornerButton");
                    String label = corner == null ? "" : corner.getMessage().getString();
                    boolean active = (Boolean) Mod.staticCall("mainmenu.MainMenuTheme", "active");
                    String gradient = String.valueOf(Mod.staticCall("gui.GreenTheme", "gradient"));
                    String thrown = null;
                    try {
                        Frames.extract(mc, McCompat.screen(mc), -1, -1);
                        Frames.extract(mc, McCompat.screen(mc), McCompat.screen(mc).width / 2, McCompat.screen(mc).height / 3);
                    } catch (Throwable t) {
                        thrown = UiCase.describe(t);
                    }
                    return new Object[]{idx, info, label, active, gradient, thrown};
                });
                int idx = (Integer) got[0];
                Object[] info = (Object[]) got[1];
                String name = (String) info[0];
                visited.add(name);
                seen.add(idx);
                c.check(idx == want, "click " + k + " showed look " + idx + ", expected " + want);
                c.check(("Look: " + name).equals(got[2]), "the corner button reads '" + got[2] + "' on " + name);
                c.check((Boolean) got[3] != (Boolean) info[1], name + ": the themed main menu is "
                        + ((Boolean) got[3] ? "on" : "off") + " (vanilla look: " + info[1] + ")");
                if (!"null".equals(info[5])) {
                    c.check(info[5].equals(got[4]), name + " set the Green gradient to " + got[4] + ", expected " + info[5]);
                }
                c.check(got[5] == null, name + " threw while extracting: " + got[5]);
                FontsMainMenuSuite.Pic pic = FontsMainMenuSuite.pic(shot(c, String.format(Locale.ROOT, "%02d-%s", idx, slug(name))));
                if (prev != null) {
                    boolean sameTitle = info[2].equals(prevInfo[2]) && info[3].equals(prevInfo[3]) && info[4].equals(prevInfo[4]);
                    double d = FontsMainMenuSuite.changed(prev.img(), pic.img());
                    c.note(String.format(Locale.ROOT, "%s: rgb %.0f,%.0f,%.0f, %.1f%% differs from the look before",
                            name, pic.r(), pic.g(), pic.b(), d * 100));
                    if (!sameTitle) {
                        c.check(d > 0.05, String.format(Locale.ROOT, "%s draws the same title screen as the look before it: %.2f%% differ", name, d * 100));
                    }
                }
                prev = pic;
                prevInfo = info;
            }
            c.note("visited: " + visited);
            c.check(seen.size() == n, "left clicks visited " + seen.size() + " of " + n + " looks");

            // A right click steps back one.
            String back = c.onClient(mc -> {
                int before = (Integer) Mod.staticCall(LOOKS, "currentIndex");
                AbstractWidget corner = (AbstractWidget) Mod.staticCall(LOOKS, "cornerButton");
                click(McCompat.screen(mc), corner, 1);
                int after = (Integer) Mod.staticCall(LOOKS, "currentIndex");
                return after == Math.floorMod(before - 1, n) ? null : "a right click went from " + before + " to " + after;
            });
            c.check(back == null, back);
            c.ticks(6);

            // ---- the picker, from the Multiplayer slot.
            String opened = c.onClient(mc -> {
                Screen s = McCompat.screen(mc);
                AbstractWidget looks = byLabel(s, "Main Menu Looks");
                if (looks == null) {
                    return "no Main Menu Looks after cycling";
                }
                click(s, looks, 0);
                Screen now = McCompat.screen(mc);
                return now != null && now.getClass().getName().endsWith(".TitleScreenLooksScreen") ? null
                        : "Main Menu Looks opened " + (now == null ? "nothing" : now.getClass().getName());
            });
            c.check(opened == null, opened);
            if (opened == null) {
                c.ticks(10);
                shot(c, "picker-open");
                FontsMainMenuSuite.Pic before = null;
                for (String[] pick : new String[][]{{"Waterfall", "Ocean · Waterfall"}, {"Acid Cave", "Green · Acid Cave"},
                        {"New", "Amber · New"}}) {
                    String why = c.onClient(mc -> {
                        Screen s = McCompat.screen(mc);
                        AbstractWidget chip = byLabel(s, pick[0]);
                        if (chip == null) {
                            return "no '" + pick[0] + "' entry in the picker";
                        }
                        return click(s, chip, 0) ? null : "the '" + pick[0] + "' entry did not take the click";
                    });
                    c.check(why == null, why);
                    c.ticks(4);
                    String state = c.onClient(mc -> {
                        int idx = (Integer) Mod.staticCall(LOOKS, "currentIndex");
                        String label = (String) Mod.call(((List<?>) Mod.staticCall(LOOKS, "looks")).get(idx), "label");
                        if (!pick[1].equals(label)) {
                            return "picking '" + pick[0] + "' shows " + label;
                        }
                        Screen s = McCompat.screen(mc);
                        AbstractWidget chip = byLabel(s, pick[0]);
                        if (chip == null || !Boolean.TRUE.equals(Mod.call(chip, "toggleState"))) {
                            return "the '" + pick[0] + "' entry is not lit after picking it";
                        }
                        int lit = 0;
                        for (AbstractWidget w : Screens.getWidgets(s)) {
                            try {
                                if (Boolean.TRUE.equals(Mod.call(w, "toggleState"))) {
                                    lit++;
                                }
                            } catch (RuntimeException ignored) {
                            }
                        }
                        if (lit != 1) {
                            return lit + " entries are lit after picking '" + pick[0] + "'";
                        }
                        try {
                            Frames.extract(mc, s, -1, -1);
                        } catch (Throwable t) {
                            return "the picker threw while extracting: " + UiCase.describe(t);
                        }
                        return null;
                    });
                    c.check(state == null, state);
                    FontsMainMenuSuite.Pic p = FontsMainMenuSuite.pic(shot(c, "picker-" + slug(pick[1])));
                    if (before != null) {
                        double d = FontsMainMenuSuite.changed(before.img(), p.img());
                        c.check(d > 0.05, String.format(Locale.ROOT, "the picker's background did not change with '%s': %.2f%% differ", pick[0], d * 100));
                    }
                    before = p;
                }
                String done = c.onClient(mc -> {
                    Screen s = McCompat.screen(mc);
                    AbstractWidget d = byLabel(s, "Done");
                    if (d == null) {
                        return "no Done in the picker";
                    }
                    click(s, d, 0);
                    return McCompat.screen(mc) instanceof TitleScreen ? null : "Done left " + McCompat.screen(mc);
                });
                c.check(done == null, done);
                c.ticks(10);
                String after = c.onClient(mc -> {
                    AbstractWidget corner = (AbstractWidget) Mod.staticCall(LOOKS, "cornerButton");
                    String l = corner == null ? null : corner.getMessage().getString();
                    return "Look: Amber · New".equals(l) ? null : "back on the title screen the corner reads " + l;
                });
                c.check(after == null, after);
                shot(c, "after-picker");
            }

            // ---- nothing saved.
            FontsMainMenuSuite.MmState now = FontsMainMenuSuite.saveMm(c);
            c.check(now.equals(saved), "the switcher changed a saved setting: " + saved + " -> " + now);
            c.check(Arrays.equals(mmBefore, Files.readAllBytes(mmFile)), "the main menu config file changed: " + mmFile);
            c.check(Arrays.equals(menuBefore, Files.readAllBytes(menuFile)), "the menu config file changed: " + menuFile);
            String restored = c.onClient(mc -> {
                Mod.staticCall(LOOKS, "setEnabledForTest", false);
                Object amber = Mod.staticCall("mainmenu.MainMenuScenes", "amberScene");
                Object green = Mod.staticCall("mainmenu.MainMenuScenes", "greenScene");
                Object gradient = Mod.staticCall("gui.GreenTheme", "gradient");
                boolean active = (Boolean) Mod.staticCall("mainmenu.MainMenuTheme", "active");
                if (active != saved.enabled()) {
                    return "with the switcher off the themed main menu is " + active + ", saved " + saved.enabled();
                }
                if (!"EMBERS".equals(String.valueOf(amber)) || !"BOSS".equals(String.valueOf(green))
                        || !"CANOPY_GLOW".equals(String.valueOf(gradient))) {
                    return "turning the switcher off left amber " + amber + ", green " + green + ", gradient " + gradient;
                }
                return null;
            });
            c.check(restored == null, restored);
        } finally {
            c.onClient(mc -> {
                Mod.staticCall(LOOKS, "setEnabledForTest", false);
                return null;
            });
            FontsMainMenuSuite.setWindow(c, win0[0], win0[1], win0[2]);
            FontsMainMenuSuite.restoreMm(c, saved);
        }
    }
}

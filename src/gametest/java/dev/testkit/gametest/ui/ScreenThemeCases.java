package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The screen sweep, part C (mod sweep-c; killer560 2026-10-13: "make the theme for each match the gui's theme. Then redo
 * them to also look in a similar manner"). Every press is a real mouse event through the screen.
 * <ul>
 *   <li>975 every sweep-c screen (the sim's five, AP3's two, the Auto Routes node editor, Breaker Aura's config picker,
 *       Leap Order, the Interactive Map, the HUD editor, the scoreboard editor, Termism's two) opened under Amber and
 *       under Light at 1920x1080 GUI 2: the panel pixel the mod names (its themedPanelBounds) is sampled from the
 *       screenshot and must be the colour the theme puts there, and Amber's and Light's must differ; then the screen's
 *       main interaction still works (a list pick and a grid placement on Design a Map, a chip and Clear all on the
 *       Filters panel, Give me everything on Sim Items, Esc ignored by the loading screen, typing into a config name box, the
 *       node editors' Close, a class pick in Leap Order, the map legend's Controls toggle, the scoreboard editor's page
 *       buttons, Termism's Random into a practice board and Done back). Shots kept as "&lt;Screen&gt;-amber/light".</li>
 *   <li>976 a screen's own Theme setting: Termism's Theme row in its menu tab, pressed through Theme Default, Amber,
 *       Dark to Light (and a right click back one), makes Termism draw Light while the menu stays Amber; it survives a
 *       reload from disk; back on Theme Default the screen follows the menu again.</li>
 * </ul>
 */
final class ScreenThemeCases {

    private ScreenThemeCases() {
    }

    /** One screen of the sweep: its name, how to open it, and its interaction check (null = none here). {@code card}: its
     *  themedPanelBounds() is a card (the HUD editor's hint chip, the map legend), not the frame. */
    private record Spec(String name, boolean cheatOnly, Function<Minecraft, Screen> open, Check check, boolean card) {
        Spec(String name, boolean cheatOnly, Function<Minecraft, Screen> open, Check check) {
            this(name, cheatOnly, open, check, name.equals("HudEditorScreen") || name.equals("InteractiveMapScreen"));
        }
    }

    @FunctionalInterface
    private interface Check {
        /** @return a note on what acted; throw / return "FAIL ..." on failure */
        String run(UiCase c, Screen s) throws Exception;
    }

    private static Screen make(String cls, Class<?>[] types, Object... args) {
        try {
            return (Screen) Mod.cls(cls).getConstructor(types).newInstance(args);
        } catch (Exception e) {
            throw new AssertionError("cannot open " + cls + ": " + UiCase.describe(e), e);
        }
    }

    private static Screen withParent(String cls) {
        return make(cls, new Class<?>[]{Screen.class}, (Object) null);
    }

    private static List<Spec> specs() {
        List<Spec> out = new ArrayList<>();
        out.add(new Spec("SimMenuScreen", false, mc -> withParent("roomsim.SimMenuScreen"), (c, s) -> {
            press(c, s, button(s, "Load a Room"));
            String mode = String.valueOf(Mod.field(s, "mode"));
            check(c, "ROOM".equals(mode), "Load a Room left the sim menu in mode " + mode);
            return "Load a Room -> mode " + mode;
        }));
        out.add(new Spec("SimMapEditorScreen", false, mc -> withParent("roomsim.SimMapEditorScreen"), (c, s) -> {
            for (int i = 0; i < 200 && ((List<?>) Mod.field(s, "listed")).isEmpty(); i++) {
                c.ticks(5);
            }
            List<?> listed = (List<?>) Mod.field(s, "listed");
            check(c, !listed.isEmpty(), "Design a Map lists no rooms after 1000 ticks");
            int listX = (Integer) Mod.field(s, "listX");
            int panelY = (Integer) Mod.field(s, "panelY");
            c.onClient(mc -> {
                MenuACases.press(s, listX + 20, panelY + 66 + 11);
                return null;
            });
            Object selected = Mod.field(s, "selected");
            check(c, listed.get(0).equals(selected), "a click on the first room row selected " + selected);
            int gridX = (Integer) Mod.field(s, "gridX");
            int gridY = (Integer) Mod.field(s, "gridY");
            int cell = (Integer) Mod.field(s, "cell");
            c.onClient(mc -> {
                MenuACases.press(s, gridX + cell / 2.0, gridY + cell / 2.0);
                return null;
            });
            int placed = ((java.util.Map<?, ?>) Mod.field(s, "placements")).size();
            check(c, placed == 1, "a click on grid cell 0 placed " + placed + " room(s), status '" + Mod.field(s, "status") + "'");
            return "row click selected '" + selected + "', grid click placed it (" + Mod.field(s, "status") + ")";
        }));
        out.add(new Spec("SimRoomFilterScreen", false, mc -> withParent("roomsim.SimRoomFilterScreen"), (c, s) -> {
            Object filter = Mod.call(s, "filter");
            int before = (Integer) Mod.call(filter, "activeCount");
            AbstractWidget chip = null;
            for (GuiEventListener l : s.children()) {
                if (l instanceof AbstractWidget w && w.getClass().getSimpleName().equals("Chip") && w.visible && w.active) {
                    chip = w;
                    break;
                }
            }
            check(c, chip != null, "the Filters panel has no visible chip");
            press(c, s, chip);
            int after = (Integer) Mod.call(filter, "activeCount");
            check(c, after != before, "a chip click left the filter at " + after + " active row(s)");
            press(c, s, button(s, "Clear all"));
            int cleared = (Integer) Mod.call(filter, "activeCount");
            check(c, cleared == 0, "Clear all left " + cleared + " active row(s)");
            return "chip '" + ModScreenDriver.label(chip) + "' -> " + after + " active, Clear all -> 0";
        }));
        out.add(new Spec("SimItemsScreen", false, mc -> withParent("roomsim.SimItemsScreen"), (c, s) -> {
            long mark = LogTap.mark();
            int px = (Integer) Mod.field(s, "panelX");
            int py = (Integer) Mod.field(s, "panelY");
            int pw = (Integer) Mod.field(s, "panelW");
            int ph = (Integer) Mod.field(s, "panelH");
            c.onClient(mc -> {
                MenuACases.press(s, px + pw / 2.0, py + ph - 28 + 9);
                return null;
            });
            c.ticks(2);
            boolean answered = LogTap.since(mark).stream().anyMatch(l -> l.contains("sim items only work"));
            check(c, answered, "a click on Give me everything did not reach SimItems (no 'only inside a sim' line)");
            return "Give me everything reached SimItems (outside a sim it says so in chat)";
        }));
        out.add(new Spec("SimLoadingScreen", false, mc -> make("roomsim.SimLoadingScreen",
                new Class<?>[]{String.class}, "Building testkit room"), (c, s) -> {
            c.onClient(mc -> {
                MenuACases.key(s, com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE, 0);
                return null;
            });
            c.ticks(2);
            boolean still = c.onClient(mc -> McCompat.screen(mc) == s);
            check(c, still, "Escape closed the sim's loading screen");
            return "Escape ignored, the loading screen stays up";
        }));
        out.add(new Spec("Ap3ConfigScreen", true, mc -> withParent("ap3.Ap3ConfigScreen"), ScreenThemeCases::typeName));
        out.add(new Spec("Ap3EditScreen", true, mc -> make("ap3.Ap3EditScreen", new Class<?>[]{Screen.class, int.class},
                null, 0), ScreenThemeCases::closeOrCancel));
        out.add(new Spec("AutoRoutesEditScreen", true, mc -> make("autoroutes.AutoRoutesEditScreen",
                new Class<?>[]{Mod.cls("autoroutes.Route"), Mod.cls("autoroutes.RouteNode")}, null, null),
                ScreenThemeCases::closeOrCancel));
        out.add(new Spec("BreakerAuraConfigScreen", true, mc -> withParent("dungeonextras.BreakerAuraConfigScreen"),
                ScreenThemeCases::typeName));
        out.add(new Spec("LeapOrderScreen", false, mc -> withParent("leapmenu.LeapOrderScreen"), (c, s) -> {
            press(c, s, button(s, "Mage"));
            Object editing = Mod.field(s, "editing");
            check(c, editing != null, "a click on Mage did not open the Mage layout");
            return "Mage -> editing " + editing;
        }));
        out.add(new Spec("InteractiveMapScreen", false, mc -> make("livemap.InteractiveMapScreen",
                new Class<?>[]{boolean.class}, false), (c, s) -> {
            Object cfg = Mod.cfg("livemap.LiveMapConfig");
            boolean was = (Boolean) Mod.call(cfg, "isMapControlsExpanded");
            int[] r = (int[]) Mod.field(s, "controlsToggleRect");
            check(c, r != null, "the map legend drew no Controls header");
            c.onClient(mc -> {
                MenuACases.press(s, (r[0] + r[2]) / 2.0, (r[1] + r[3]) / 2.0);
                return null;
            });
            boolean now = (Boolean) Mod.call(cfg, "isMapControlsExpanded");
            c.ticks(2);
            int[] r2 = (int[]) Mod.field(s, "controlsToggleRect");
            c.onClient(mc -> {
                MenuACases.press(s, (r2[0] + r2[2]) / 2.0, (r2[1] + r2[3]) / 2.0);
                return null;
            });
            boolean back = (Boolean) Mod.call(cfg, "isMapControlsExpanded");
            check(c, now != was && back == was, "the Controls header click went " + was + " -> " + now + " -> " + back);
            return "Controls header toggled " + was + " -> " + now + " -> " + back;
        }));
        out.add(new Spec("HudEditorScreen", false, mc -> withParent("hud.HudEditorScreen"), (c, s) ->
                "drag / resize / snap are 399 and 612-614's; " + ((List<?>) Mod.field(s, "shown")).size() + " box(es) listed"));
        out.add(new Spec("ScoreboardEditorScreen", false, mc -> withParent("scoreboard.ScoreboardEditorScreen"), (c, s) -> {
            press(c, s, button(s, "Settings"));
            Screen now = c.onClient(McCompat::screen);
            String page = String.valueOf(Mod.field("scoreboard.ScoreboardEditorScreen", "page"));
            check(c, "SETTINGS".equals(page), "the Settings page button left the editor on " + page);
            press(c, now, button(now, "Board"));
            String back = String.valueOf(Mod.field("scoreboard.ScoreboardEditorScreen", "page"));
            check(c, "BOARD".equals(back), "the Board page button left the editor on " + back);
            return "page buttons Settings -> " + page + ", Board -> " + back;
        }));
        out.add(new Spec("TermismMenuScreen", false, mc -> withParent("termism.TermismMenuScreen"), (c, s) -> {
            press(c, s, button(s, "Random"));
            Screen practice = c.onClient(McCompat::screen);
            check(c, practice != null && practice.getClass().getSimpleName().equals("TermismPracticeScreen"),
                    "Random opened " + practice);
            c.ticks(3);
            press(c, practice, button(practice, "Done"));
            Screen back = c.onClient(McCompat::screen);
            check(c, back == s, "Done on the practice board went to " + back);
            return "Random -> a practice board, Done -> back to the menu";
        }));
        out.add(new Spec("TermismPracticeScreen", false, mc -> make("termism.TermismPracticeScreen",
                new Class<?>[]{Screen.class, Mod.cls("terminals.TerminalType")}, null,
                Mod.enumValue("terminals.TerminalType", "PANES")), null));
        return out;
    }

    private static String typeName(UiCase c, Screen s) {
        EditBox box = null;
        for (GuiEventListener l : s.children()) {
            if (l instanceof EditBox e) {
                box = e;
            }
        }
        check(c, box != null, "no name box");
        EditBox b = box;
        c.onClient(mc -> {
            MenuACases.press(s, b.getX() + b.getWidth() / 2.0, b.getY() + b.getHeight() / 2.0);
            MenuACases.type(s, "tk975");
            return null;
        });
        check(c, b.isFocused() && b.getValue().equals("tk975"), "typing into the name box gave '" + b.getValue()
                + "' (focused " + b.isFocused() + ")");
        return "a click focused the name box and typing filled it ('" + b.getValue() + "')";
    }

    private static String closeOrCancel(UiCase c, Screen s) {
        AbstractWidget w = find(s, "Close");
        String which = "Close";
        if (w == null) {
            w = find(s, "Cancel");
            which = "Cancel";
        }
        check(c, w != null, "no Close or Cancel button");
        press(c, s, w);
        Screen now = c.onClient(McCompat::screen);
        check(c, now != s, which + " did not close the editor");
        return which + " closed the editor";
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    private static void check(UiCase c, boolean ok, String why) {
        c.check(ok, why);
    }

    private static AbstractWidget find(Screen s, String label) {
        for (GuiEventListener l : s.children()) {
            if (l instanceof AbstractWidget w && w.visible && ModScreenDriver.label(w).trim().equals(label)) {
                return w;
            }
        }
        return null;
    }

    private static AbstractWidget button(Screen s, String label) {
        AbstractWidget w = find(s, label);
        if (w == null) {
            throw new AssertionError("no '" + label + "' on " + s.getClass().getSimpleName());
        }
        return w;
    }

    /** A real left press and release at the widget's centre, through the screen, on the client thread. */
    private static void press(UiCase c, Screen s, AbstractWidget w) {
        c.onClient(mc -> {
            MenuACases.press(s, w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0);
            return null;
        });
    }

    private static void setTheme(UiCase c, String theme) {
        c.onClient(mc -> {
            Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", Mod.enumValue("gui.MenuTheme", theme));
            return null;
        });
    }

    private static float lum(int c) {
        return MenuACases.lum(c);
    }

    /**
     * Screenshot of {@code s}, kept as {@code label}; one pixel of its themed panel (themedPanelBounds, just inside the
     * bottom-left bevel where every screen leaves the panel bare) sampled, and the colour the open screen's palette puts
     * there (MenuStyle's panel or card gradient at that row, read while the screen is up).
     * @return {sampled, expected}
     */
    static int[] shotAndSample(UiCase c, Screen s, boolean card, String label) throws Exception {
        c.ticks(4);
        boolean up = c.onClient(mc -> McCompat.screen(mc) == s);
        check(c, up, label + ": the screen is not up (" + c.onClient(McCompat::screen) + ")");
        String shot = c.name() + "-" + label;
        Path taken = c.ctx().takeScreenshot(Report.fileName(shot));
        Report.screenshot(shot, taken);
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(taken.toFile());
        int[] probe = c.onClient(mc -> {
            int[] b = (int[]) Mod.call(s, "themedPanelBounds");
            if (b == null || b[2] <= 0) {
                return null;
            }
            int top = (Integer) Mod.field("gui.MenuStyle", card ? "CARD_TOP" : "PANEL_TOP");
            int bottom = (Integer) Mod.field("gui.MenuStyle", card ? "CARD_BOTTOM" : "PANEL_BOTTOM");
            int px = b[0] + 3;
            int py = b[1] + b[3] - (card ? 3 : 4);
            float t = (py + 0.5f - (b[1] + 1)) / Math.max(1f, b[3] - 2);
            return new int[]{px, py, mix(top, bottom, t)};
        });
        check(c, probe != null, label + ": " + s.getClass().getSimpleName() + " reports no themed panel");
        double sc = img.getWidth() / (double) s.width;
        int px = img.getRGB((int) ((probe[0] + 0.5) * sc), (int) ((probe[1] + 0.5) * sc)) | 0xFF000000;
        return new int[]{px, probe[2] | 0xFF000000};
    }

    private static int mix(int a, int b, float t) {
        int r = 0;
        for (int sh = 0; sh < 32; sh += 8) {
            int ca = (a >>> sh) & 0xFF;
            int cb = (b >>> sh) & 0xFF;
            r |= (Math.round(ca + (cb - ca) * t) & 0xFF) << sh;
        }
        return r;
    }

    private static boolean near(int a, int b) {
        for (int sh = 0; sh < 24; sh += 8) {
            if (Math.abs(((a >> sh) & 0xFF) - ((b >> sh) & 0xFF)) > 6) {
                return false;
            }
        }
        return true;
    }

    private interface Body {
        void run() throws Exception;
    }

    /** 1920x1080 at GUI 2 with Auto Scale on (as he plays) for the body; everything put back after. */
    private static void bigWindow(UiCase c, Body body) throws Exception {
        int[] oldWindow = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        Object hud = Mod.cfg("hud.HudConfig");
        boolean oldAuto = (Boolean) Mod.call(hud, "isAutoScale");
        String oldTheme = MenuACases.themeName();
        try {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.call(hud, "setAutoScale", true);
                return null;
            });
            c.ctx().getInput().resizeWindow(1920, 1080);
            c.ticks(3);
            c.onClient(mc -> {
                mc.options.guiScale().set(2);
                mc.resizeGui();
                return null;
            });
            c.ticks(3);
            body.run();
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Object cfg = Mod.cfg("gui.MenuConfig");
                Mod.call(cfg, "setTheme", Mod.enumValue("gui.MenuTheme", oldTheme));
                Mod.call(cfg, "save");
                Mod.call(hud, "setAutoScale", oldAuto);
                mc.options.guiScale().set(oldGui);
                return null;
            });
            c.ctx().getInput().resizeWindow(oldWindow[0], oldWindow[1]);
            c.ticks(3);
            c.onClient(mc -> {
                mc.resizeGui();
                return null;
            });
        }
    }

    // ---- 975 ---------------------------------------------------------------------------------------------------------

    static void sweep(UiCase c) throws Exception {
        Object hudCfg = Mod.cfg("hud.HudConfig");
        boolean showAll = (Boolean) Mod.call(hudCfg, "isEditorShowAll");
        c.onClient(mc -> {
            Mod.call(hudCfg, "setEditorShowAll", true); // the HUD editor's shots show its boxes
            return null;
        });
        try {
            sweepBody(c);
        } finally {
            c.onClient(mc -> {
                Mod.call(hudCfg, "setEditorShowAll", showAll);
                return null;
            });
        }
    }

    private static void sweepBody(UiCase c) throws Exception {
        bigWindow(c, () -> {
            List<String> summary = new ArrayList<>();
            for (Spec spec : specs()) {
                if (spec.cheatOnly() && !Mod.isCheat()) {
                    c.note(spec.name() + ": cheat-only screen, not in this jar");
                    continue;
                }
                int[][] seen = new int[2][];
                String[] themes = {"AMBER", "LIGHT"};
                for (int t = 0; t < 2; t++) {
                    setTheme(c, themes[t]);
                    Screen s = c.onClient(mc -> {
                        Screen made = spec.open().apply(mc);
                        McCompat.setScreen(mc, made);
                        return made;
                    });
                    String label = spec.name() + "-" + themes[t].toLowerCase(Locale.ROOT);
                    int[] r = shotAndSample(c, s, spec.card(), label);
                    seen[t] = r;
                    c.note(String.format(Locale.ROOT, "%s: panel pixel %s, the %s palette puts %s there", label,
                            MenuACases.hex(r[0]), themes[t], MenuACases.hex(r[1])));
                    if (!near(r[0], r[1])) {
                        c.problem(label + ": the panel reads " + MenuACases.hex(r[0]) + ", the " + themes[t] + " theme puts "
                                + MenuACases.hex(r[1]) + " there - it does not follow the theme");
                    }
                    if (spec.check() != null) {
                        try {
                            c.note(label + ": " + spec.check().run(c, s));
                        } catch (AssertionError e) {
                            c.problem(label + ": " + e.getMessage());
                        }
                        // What the interaction led to (Leap Order's layout page, Load a Room, a placed room), when the
                        // screen is still up.
                        Screen after = c.onClient(McCompat::screen);
                        if (after != null && after.getClass().getSimpleName().equals(spec.name())) {
                            shotAndSample(c, after, spec.card(), label + "-after");
                        }
                    }
                    c.onClient(mc -> {
                        McCompat.setScreen(mc, null);
                        return null;
                    });
                    c.ticks(2);
                }
                float la = lum(seen[0][0]);
                float ll = lum(seen[1][0]);
                if (ll - la < 0.4f) {
                    c.problem(spec.name() + ": Light's panel (lum " + ll + ") is not clearly lighter than Amber's (" + la + ")");
                }
                summary.add(spec.name() + " " + MenuACases.hex(seen[0][0]) + "/" + MenuACases.hex(seen[1][0]));
            }
            c.note("amber/light panel pixels: " + summary);
            check(c, summary.size() >= (Mod.isCheat() ? 15 : 11), "only " + summary.size() + " screens were checked");
        });
        // the filter the Filters panel edited, and the leap class it opened, back to how a fresh run has them
        c.onClient(mc -> {
            Mod.call(Mod.field("roomsim.SimRoomFilters", "DESIGNER"), "clear");
            return null;
        });
    }

    // ---- 976 ---------------------------------------------------------------------------------------------------------

    private static AbstractWidget themeRow(Screen menu) {
        for (AbstractWidget w : MenuACases.paneRows(menu)) {
            if (ModScreenDriver.label(w).startsWith("Theme: ")) {
                return w;
            }
        }
        return null;
    }

    private static final String THEMES = "gui.ScreenThemes";

    private static String termismTheme() {
        return (String) Mod.staticCall(THEMES, "get", "termism");
    }

    static void ownTheme(UiCase c) throws Exception {
        bigWindow(c, () -> {
            try {
                setTheme(c, "AMBER");
                Screen[] menu = new Screen[1];
                c.onClient(mc -> {
                    Mod.staticCall(THEMES, "set", "termism", "DEFAULT");
                    try {
                        menu[0] = MenuACases.open(mc);
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                    Mod.call(menu[0], "openFeature", MenuACases.leaf("TermismTab"));
                    return null;
                });
                c.ticks(3);
                String first = c.onClient(mc -> {
                    AbstractWidget row = themeRow(menu[0]);
                    return row == null ? null : ModScreenDriver.label(row);
                });
                check(c, "Theme: Theme Default".equals(first), "Termism's tab Theme row reads '" + first + "'");
                // left, left, left -> Amber, Dark, Light; a right click back one (Dark), a left again (Light)
                String[] want = {"AMBER", "DARK", "LIGHT"};
                for (String w : want) {
                    c.onClient(mc -> {
                        Mod.call(themeRow(menu[0]), "press");
                        return null;
                    });
                    c.check(w.equals(termismTheme()), "a left click on Theme gave " + termismTheme() + ", wanted " + w);
                }
                c.onClient(mc -> {
                    AbstractWidget row = themeRow(menu[0]);
                    // on the value pill: since doc-menu a click on a cycling setting's label does nothing (987)
                    row.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(row.getX() + row.getWidth() - 8.0, row.getY() + row.getHeight() / 2.0,
                            new net.minecraft.client.input.MouseButtonInfo(1, 0)), false);
                    return null;
                });
                c.check("DARK".equals(termismTheme()), "a right click on Theme gave " + termismTheme() + ", wanted DARK");
                c.onClient(mc -> {
                    Mod.call(themeRow(menu[0]), "press");
                    return null;
                });
                String label = c.onClient(mc -> ModScreenDriver.label(themeRow(menu[0])));
                c.check("LIGHT".equals(termismTheme()) && "Theme: Light".equals(label),
                        "after right then left the Theme row reads '" + label + "' (" + termismTheme() + ")");
                c.note("Termism's Theme row: Theme Default -> Amber -> Dark -> Light, right click -> Dark, left -> Light");

                // Termism draws Light while the menu stays Amber.
                Screen s = c.onClient(mc -> {
                    Screen made = withParent("termism.TermismMenuScreen");
                    McCompat.setScreen(mc, made);
                    return made;
                });
                int[] own = shotAndSample(c, s, false, "termism-own-light");
                c.note("menu Amber, Termism set to Light: panel pixel " + MenuACases.hex(own[0]) + " (its palette puts "
                        + MenuACases.hex(own[1]) + " there)");
                c.check(near(own[0], own[1]) && lum(own[0]) > 0.6f, "Termism's panel reads " + MenuACases.hex(own[0])
                        + " (palette " + MenuACases.hex(own[1]) + "), not a Light panel");
                c.check("AMBER".equals(MenuACases.themeName()), "the menu theme moved to " + MenuACases.themeName());
                int[] menuNow = MenuACases.sample(c, reopenMenu(c), "menu-still-amber");
                c.check(lum(menuNow[1]) < 0.3f, "the mod menu's sidebar reads " + MenuACases.hex(menuNow[1])
                        + " with Termism on Light - the screen theme leaked into the menu");
                c.note("the mod menu still draws Amber (sidebar " + MenuACases.hex(menuNow[1]) + ")");

                // Saved: a reload from disk (and a profile's reload) keeps it.
                c.onClient(mc -> {
                    McCompat.setScreen(mc, null);
                    Mod.staticCall(THEMES, "load");
                    return null;
                });
                c.check("LIGHT".equals(termismTheme()), "after a reload from disk Termism's theme is " + termismTheme());
                c.note("Termism's Light survived a reload from disk");

                // Back to Theme Default (Light -> Ocean -> Green -> Custom -> Theme Default) and Termism follows the menu again.
                Screen m2 = reopenMenu(c);
                c.onClient(mc -> {
                    Mod.call(m2, "openFeature", MenuACases.leaf("TermismTab"));
                    return null;
                });
                for (int i = 0; i < 4; i++) {
                    c.onClient(mc -> {
                        Mod.call(themeRow(m2), "press");
                        return null;
                    });
                }
                c.check("DEFAULT".equals(termismTheme()), "four more left clicks gave " + termismTheme());
                Screen s2 = c.onClient(mc -> {
                    Screen made = withParent("termism.TermismMenuScreen");
                    McCompat.setScreen(mc, made);
                    return made;
                });
                int[] def = shotAndSample(c, s2, false, "termism-default-follows-amber");
                c.check(near(def[0], def[1]) && lum(def[0]) < 0.3f, "on Theme Default Termism reads "
                        + MenuACases.hex(def[0]) + " (palette " + MenuACases.hex(def[1]) + "), not the menu's Amber");
                c.note("back on Theme Default Termism draws the menu's Amber (" + MenuACases.hex(def[0]) + ")");
            } finally {
                c.onClient(mc -> {
                    Mod.staticCall(THEMES, "set", "termism", "DEFAULT");
                    Mod.staticCall(THEMES, "save");
                    return null;
                });
            }
        });
    }

    private static Screen reopenMenu(UiCase c) {
        Screen m = c.onClient(mc -> {
            try {
                return MenuACases.open(mc);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        c.ticks(3);
        return m;
    }
}

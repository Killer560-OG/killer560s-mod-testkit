package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;

import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Vanilla menus in the main menu's theme (mod vanilla-themes-1015; killer560, 2026-10-15: "make sure other menus like the
 * singleplayer menu and the multiplayer menu and settings and stuff fits the new themes", and the pause menu "does NOT
 * change when he changes the mod's theme").
 * <ul>
 *   <li>1572-ui-vanilla-menus: from the title screen, by real clicks: Singleplayer (and Create New World), Multiplayer
 *       (Add Server, Direct Connection), Options and every "..." page under it (and Controls' Key Binds / Mouse Settings),
 *       plus a confirm dialog, a disconnected screen and a message screen - each photographed in Amber and Ocean, and
 *       each left through its own Done / Back / Cancel, which must land on the screen it came from. The Done button's
 *       drawn color must differ between the two themes (the old fixed dark-and-orange look is the same in both).</li>
 *   <li>1573-ui-vanilla-pause: in a world, the pause menu with the sim's Change Room button: photographed in two themes,
 *       and the theme switched while it stays open - the drawn Back to Game button changes on the next frames. Options
 *       from the pause menu, and back.</li>
 * </ul>
 * {@code TESTKIT_VANILLA_SHOTS=<dir>} copies every picture there.
 */
final class VanillaThemeCases {

    private VanillaThemeCases() {
    }

    static final String MENU = "gui.MenuConfig";
    static final String[] BACK_KEYS = {"gui.done", "gui.back", "gui.cancel", "gui.toTitle", "gui.no"};

    static String key(AbstractWidget w) {
        Component m = w.getMessage();
        return m != null && m.getContents() instanceof TranslatableContents tc ? tc.getKey() : "";
    }

    static AbstractWidget find(Screen s, String keyOrLabel) {
        for (AbstractWidget w : Frames.widgets(s)) {
            if (w.visible && w.active && (keyOrLabel.equals(key(w)) || keyOrLabel.equals(w.getMessage().getString()))) {
                return w;
            }
        }
        return null;
    }

    static void theme(UiCase c, String name) {
        c.onClient(mc -> {
            Object mm = Mod.cfg("mainmenu.MainMenuThemeConfig");
            Mod.call(mm, "setEnabled", true);
            Mod.call(mm, "setOtherMenus", true);
            Mod.call(mm, "setStyle", Mod.enumValue("mainmenu.MainMenuThemeConfig$Style", "MATCH"));
            Mod.call(Mod.cfg(MENU), "setTheme", Mod.enumValue("gui.MenuTheme", name));
            return null;
        });
    }

    static Path shot(UiCase c, String label) {
        c.ticks(4);
        Path p = c.ctx().takeScreenshot(Report.fileName(c.name() + "-" + label));
        Report.screenshot(c.name(), p);
        String copy = System.getenv("TESTKIT_VANILLA_SHOTS");
        if (copy != null && !copy.isBlank()) {
            try {
                Files.createDirectories(Path.of(copy));
                Files.copy(p, Path.of(copy).resolve(label + ".png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {
            }
        }
        return p;
    }

    /** Mean color {r,g,b} of a widget's box in a screenshot (GUI units scaled by the screenshot's width). */
    static double[] mean(Path png, int screenW, int[] box) throws Exception {
        BufferedImage img = ImageIO.read(png.toFile());
        double k = img.getWidth() / (double) screenW;
        int x0 = (int) Math.ceil(box[0] * k) + 2;
        int y0 = (int) Math.ceil(box[1] * k) + 2;
        int x1 = (int) Math.floor((box[0] + box[2]) * k) - 2;
        int y1 = (int) Math.floor((box[1] + box[3]) * k) - 2;
        long r = 0;
        long g = 0;
        long b = 0;
        long n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int p = img.getRGB(x, y);
                r += (p >> 16) & 0xFF;
                g += (p >> 8) & 0xFF;
                b += p & 0xFF;
                n++;
            }
        }
        return n == 0 ? new double[]{0, 0, 0} : new double[]{r / (double) n, g / (double) n, b / (double) n};
    }

    static double dist(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]);
    }

    static String screenName(Screen s) {
        return s == null ? "none" : s.getClass().getSimpleName();
    }

    /** Presses {@code what} on the open screen (a real click); returns the screen it opened. */
    static Screen open(UiCase c, String what) {
        String why = c.onClient(mc -> {
            AbstractWidget w = find(McCompat.screen(mc), what);
            if (w == null) {
                return "no '" + what + "' on " + screenName(McCompat.screen(mc));
            }
            ModScreenDriver.press(w);
            return null;
        });
        if (why != null) {
            c.problem(why);
            return null;
        }
        c.ticks(4);
        return c.onClient(McCompat::screen);
    }

    /** Leaves the open screen through its own Done/Back/Cancel (Escape if it has none) and checks where it went. */
    static void back(UiCase c, Screen parent, String label) {
        String why = c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            for (String k : BACK_KEYS) {
                AbstractWidget w = find(s, k);
                if (w != null) {
                    ModScreenDriver.press(w);
                    return null;
                }
            }
            // A screen with one button (Disconnected's "Back to Title Screen") is left through that button.
            List<AbstractWidget> buttons = new ArrayList<>();
            for (AbstractWidget w : Frames.widgets(s)) {
                if (w instanceof AbstractButton && w.visible && w.active) {
                    buttons.add(w);
                }
            }
            if (buttons.size() == 1) {
                ModScreenDriver.press(buttons.get(0));
                return null;
            }
            s.keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0));
            return null;
        });
        c.ticks(4);
        Screen now = c.onClient(McCompat::screen);
        if (parent != null && (now == null || now.getClass() != parent.getClass())) {
            c.problem(label + ": leaving it landed on " + screenName(now) + ", not " + screenName(parent));
        }
    }

    // ---------------------------------------------------------------- 1572

    static void menus(UiCase c) throws Exception {
        Object menu0 = c.onClient(mc -> Mod.call(Mod.cfg(MENU), "getTheme"));
        int[] win0 = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.options.guiScale().get()});
        Map<String, double[]> doneColour = new LinkedHashMap<>();
        List<String> seen = new ArrayList<>();
        try {
            FontsMainMenuSuite.setWindow(c, 1600, 900, 2);
            for (String th : new String[]{"AMBER", "OCEAN"}) {
                theme(c, th);
                String t = th.toLowerCase(Locale.ROOT);
                c.onClient(mc -> {
                    McCompat.setScreen(mc, new TitleScreen());
                    return null;
                });
                c.ticks(30);
                Screen title = c.onClient(McCompat::screen);

                // Options and every page under it.
                Screen options = open(c, "menu.options");
                if (options == null) {
                    continue;
                }
                seen.add(t + ":" + screenName(options));
                Path optShot = shot(c, t + "-options");
                int[] doneBox = c.onClient(mc -> {
                    AbstractWidget d = find(McCompat.screen(mc), "gui.done");
                    return d == null ? null : new int[]{d.getX(), d.getY(), d.getWidth(), d.getHeight()};
                });
                int sw = c.onClient(mc -> McCompat.screen(mc).width);
                if (doneBox != null) {
                    doneColour.put(th, mean(optShot, sw, doneBox));
                }
                List<String> pages = c.onClient(mc -> {
                    List<String> out = new ArrayList<>();
                    for (AbstractWidget w : Frames.widgets(McCompat.screen(mc))) {
                        String k = key(w);
                        if (w instanceof AbstractButton && w.visible && w.active && !k.isEmpty()
                                && w.getMessage().getString().endsWith("...") && !k.equals("options.online")
                                && !k.equals("options.credits_and_attribution")) {
                            out.add(k);
                        }
                    }
                    return out;
                });
                c.check(pages.size() >= 6, "only " + pages.size() + " sub-pages found on Options: " + pages);
                for (String page : pages) {
                    Screen sub = open(c, page);
                    if (sub == null || sub == options) {
                        c.problem("Options' " + page + " opened nothing");
                        continue;
                    }
                    seen.add(t + ":" + screenName(sub));
                    shot(c, t + "-options-" + page.replace("options.", "").replace('.', '-'));
                    if (page.equals("options.controls")) {
                        for (String deeper : new String[]{"controls.keybinds", "options.mouse_settings"}) {
                            Screen d = open(c, deeper);
                            if (d != null && d != sub) {
                                seen.add(t + ":" + screenName(d));
                                shot(c, t + "-controls-" + deeper.replace('.', '-'));
                                back(c, sub, deeper);
                            }
                        }
                    }
                    back(c, options, page);
                }
                back(c, title, "Options");

                // Singleplayer: the world list (or straight to Create New World in a client with no worlds).
                Screen sp = open(c, "menu.singleplayer");
                if (sp != null) {
                    seen.add(t + ":" + screenName(sp));
                    // With no saved worlds Singleplayer goes straight to Create New World, whose own "Create New World"
                    // button (the same key) would CREATE one - only the world list's button is pressed.
                    boolean list = screenName(sp).contains("SelectWorld");
                    shot(c, t + (list ? "-singleplayer" : "-create-world"));
                    Screen create = list && c.onClient(mc -> find(McCompat.screen(mc), "selectWorld.create")) != null
                            ? open(c, "selectWorld.create") : null;
                    if (create != null && create != sp) {
                        seen.add(t + ":" + screenName(create));
                        shot(c, t + "-create-world");
                        back(c, sp, "Create New World");
                    }
                    back(c, title, "Singleplayer");
                }

                // Multiplayer (through the first-time warning if it shows).
                Screen mp = open(c, "menu.multiplayer");
                if (mp != null && find(mp, "gui.proceed") != null) {
                    shot(c, t + "-multiplayer-warning");
                    mp = open(c, "gui.proceed");
                }
                if (mp != null) {
                    seen.add(t + ":" + screenName(mp));
                    shot(c, t + "-multiplayer");
                    for (String k : new String[]{"selectServer.add", "selectServer.direct"}) {
                        Screen d = open(c, k);
                        if (d != null && d != mp) {
                            seen.add(t + ":" + screenName(d));
                            shot(c, t + "-multiplayer-" + k.replace("selectServer.", ""));
                            back(c, mp, k);
                        }
                    }
                    back(c, title, "Multiplayer");
                }

                // A confirm dialog, a disconnected screen, a message screen.
                c.onClient(mc -> {
                    McCompat.setScreen(mc, new net.minecraft.client.gui.screens.ConfirmScreen(b -> McCompat.setScreen(mc,
                            new TitleScreen()), Component.literal("Leave the sim?"), Component.literal(
                            "Everything in this room will be reset.")));
                    return null;
                });
                seen.add(t + ":ConfirmScreen");
                shot(c, t + "-confirm");
                c.onClient(mc -> {
                    McCompat.setScreen(mc, new net.minecraft.client.gui.screens.DisconnectedScreen(new TitleScreen(),
                            Component.literal("Connection Lost"), Component.literal("Timed out")));
                    return null;
                });
                seen.add(t + ":DisconnectedScreen");
                shot(c, t + "-disconnected");
                back(c, title, "Disconnected");
                c.onClient(mc -> {
                    McCompat.setScreen(mc, new net.minecraft.client.gui.screens.GenericMessageScreen(
                            Component.literal("Saving world")));
                    return null;
                });
                seen.add(t + ":GenericMessageScreen");
                shot(c, t + "-message");
            }
        } finally {
            c.onClient(mc -> {
                Mod.call(Mod.cfg(MENU), "setTheme", menu0);
                McCompat.setScreen(mc, new TitleScreen());
                return null;
            });
            FontsMainMenuSuite.setWindow(c, win0[0], win0[1], win0[2]);
        }
        c.note("screens visited: " + seen.size() + " " + seen);
        c.check(seen.size() >= 26, "only " + seen.size() + " screens visited");
        double[] a = doneColour.get("AMBER");
        double[] o = doneColour.get("OCEAN");
        c.check(a != null && o != null, "the Options Done button was not measured in both themes");
        if (a != null && o != null) {
            c.note(String.format(Locale.ROOT, "Options Done button: Amber %.0f,%.0f,%.0f  Ocean %.0f,%.0f,%.0f", a[0], a[1],
                    a[2], o[0], o[1], o[2]));
            c.check(dist(a, o) > 15, "the Options Done button looks the same in Amber and Ocean (distance "
                    + String.format(Locale.ROOT, "%.1f", dist(a, o)) + ")");
        }
        c.check(!(Boolean) c.onClient(mc -> Mod.field("mainmenu.MainMenuTheme", "failed")),
                "the menu theme turned itself off after an error");
    }

    // ---------------------------------------------------------------- 1573

    static void pause(UiCase c) throws Exception {
        Object menu0 = c.onClient(mc -> Mod.call(Mod.cfg(MENU), "getTheme"));
        int[] win0 = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.options.guiScale().get()});
        boolean simActive0 = (Boolean) Mod.field("roomsim.SimState", "active");
        try {
            FontsMainMenuSuite.setWindow(c, 1600, 900, 2);
            // The sim's Change Room button shows on the pause menu only "in the sim"; SimState's flag is what it asks.
            Mod.setField("roomsim.SimState", "active", true);
            theme(c, "AMBER");
            c.onClient(mc -> {
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.PauseScreen(true));
                return null;
            });
            c.ticks(10);
            int[] box = c.onClient(mc -> {
                AbstractWidget w = find(McCompat.screen(mc), "menu.returnToGame");
                return w == null ? null : new int[]{w.getX(), w.getY(), w.getWidth(), w.getHeight()};
            });
            boolean changeRoom = c.onClient(mc -> find(McCompat.screen(mc), "Change Room") != null);
            c.check(box != null, "no Back to Game button on the pause menu");
            c.check(changeRoom, "no Change Room button on the pause menu with the sim flag on");
            int sw = c.onClient(mc -> McCompat.screen(mc).width);
            Path amber = shot(c, "amber-pause");
            // Switch the theme while the pause menu stays open: the next frames must show it.
            theme(c, "OCEAN");
            Path ocean = shot(c, "ocean-pause");
            theme(c, "LIGHT");
            Path light = shot(c, "light-pause");
            if (box != null) {
                double[] a = mean(amber, sw, box);
                double[] o = mean(ocean, sw, box);
                double[] l = mean(light, sw, box);
                c.note(String.format(Locale.ROOT, "Back to Game: Amber %.0f,%.0f,%.0f  Ocean %.0f,%.0f,%.0f  Light %.0f,%.0f,%.0f",
                        a[0], a[1], a[2], o[0], o[1], o[2], l[0], l[1], l[2]));
                c.check(dist(a, o) > 15, "the pause menu's buttons did not change from Amber to Ocean while open");
                c.check(dist(a, l) > 60, "the pause menu's buttons did not change from Amber to Light while open");
            }
            // Options from the pause menu, and back.
            theme(c, "OCEAN");
            Screen pause = c.onClient(McCompat::screen);
            Screen options = open(c, "menu.options");
            if (options != null) {
                shot(c, "ocean-pause-options");
                back(c, pause, "Options (from pause)");
            }
            back(c, null, "pause menu");
            c.check(c.onClient(mc -> McCompat.screen(mc) == null), "Back to Game left a screen open: "
                    + c.onClient(mc -> screenName(McCompat.screen(mc))));
        } finally {
            Mod.setField("roomsim.SimState", "active", simActive0);
            c.onClient(mc -> {
                Mod.call(Mod.cfg(MENU), "setTheme", menu0);
                McCompat.setScreen(mc, null);
                return null;
            });
            FontsMainMenuSuite.setWindow(c, win0[0], win0[1], win0[2]);
        }
    }
}

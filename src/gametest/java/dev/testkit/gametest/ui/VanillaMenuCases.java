package dev.testkit.gametest.ui;

import com.mojang.blaze3d.platform.Window;
import dev.testkit.compat.McCompat;
import dev.testkit.compat.McKeys;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 1116-ui-vanilla-menu (vanillamenu-1116, killer560 2026-11-16: with the themed main menu off "there is no real way to
 * access some of our stuff"). Theme OFF: the vanilla title screen must carry Dungeon Sim, Mod Settings, What's New,
 * Mods (unless Mod Menu owns it) and Swap Accounts as plain vanilla Buttons inside the screen, overlapping no other
 * widget at GUI scales 1-4 and a small window, no dark corner bar, and a real click on each opens its screen. Theme ON:
 * none of the vanilla-style rows exist and the themed Swap Accounts button is still there.
 */
final class VanillaMenuCases {

    private VanillaMenuCases() {
    }

    private record Box(String label, String cls, int x, int y, int w, int h, boolean plainButton) {
        boolean overlaps(Box o) {
            return x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h;
        }

        @Override
        public String toString() {
            return label + "@" + x + "," + y + " " + w + "x" + h;
        }
    }

    private static final String[][] BUTTONS = {
            {"Dungeon Sim", "SimMenuScreen"}, {"Mod Settings", "ModScreen"}, {"What's New", "WhatsNewScreen"},
            {"Mods", "ModsScreen"}, {"Swap Accounts", "AccountSwitcherScreen"}};

    static void vanillaMenu(UiCase c) {
        boolean modMenu = FabricLoader.getInstance().isModLoaded("modmenu");
        Object cfg = Mod.cfg("mainmenu.MainMenuThemeConfig");
        boolean e0 = (Boolean) Mod.call(cfg, "isEnabled");
        int[] win0 = c.onClient(m -> new int[]{m.getWindow().getWidth(), m.getWindow().getHeight(),
                m.options.guiScale().get()});
        int[][] sizes = {{1920, 1080, 1}, {1920, 1080, 2}, {1920, 1080, 3}, {1920, 1080, 4}, {854, 480, 2}, {854, 480, 3},
                {win0[0], win0[1], win0[2]}};
        int checked = 0;
        try {
            for (int[] size : sizes) {
                setWindow(c, size[0], size[1], size[2]);
                String tag = size[0] + "x" + size[1] + "/gui" + size[2];
                int[] gui = c.onClient(m -> new int[]{m.getWindow().getGuiScaledWidth(), m.getWindow().getGuiScaledHeight()});
                open(c, true);
                List<Box> themed = c.onClient(m -> boxes(McCompat.screen(m)));
                c.check(themed.stream().anyMatch(b -> b.cls.equals("SwapAccountsButton")),
                        tag + ": themed menu lost its Swap Accounts button: " + themed);
                c.check(themed.stream().noneMatch(b -> b.label.startsWith("Swap Accounts") && b.plainButton),
                        tag + ": themed menu shows the vanilla-style swap row: " + themed);
                open(c, false);
                List<Box> boxes = c.onClient(m -> boxes(McCompat.screen(m)));
                c.note(tag + " vanilla: " + boxes);
                Box[] found = new Box[BUTTONS.length];
                for (int i = 0; i < BUTTONS.length; i++) {
                    final String label = BUTTONS[i][0];
                    for (Box b : boxes) {
                        if (b.label.startsWith(label)) {
                            found[i] = b;
                        }
                    }
                    if (label.equals("Mods") && modMenu) {
                        c.check(found[i] == null, tag + ": Mods row shown although Mod Menu owns it");
                        continue;
                    }
                    c.check(found[i] != null, tag + ": no '" + label + "' button on the vanilla title screen: " + boxes);
                }
                for (Box b : boxes) {
                    c.check(!b.cls.equals("SettingsButtonWidget"), tag + ": the old dark corner bar is back: " + b);
                    c.check(!b.cls.contains("SwapAccountsButton") && !b.cls.contains("DungeonSimButton"),
                            tag + ": themed marker button on the vanilla screen: " + b);
                    c.check(b.x >= 0 && b.y >= 0 && b.x + b.w <= gui[0] && b.y + b.h <= gui[1],
                            tag + ": outside the screen: " + b);
                }
                for (Box b : found) {
                    if (b != null) {
                        c.check(b.plainButton, tag + ": not a plain vanilla Button: " + b);
                        c.check(b.h == 20 && (b.w == 200 || b.w == 98), tag + ": not a vanilla size: " + b);
                    }
                }
                for (int i = 0; i < boxes.size(); i++) {
                    for (int j = i + 1; j < boxes.size(); j++) {
                        if (boxes.get(i).overlaps(boxes.get(j))) {
                            c.problem(tag + ": " + boxes.get(i) + " overlaps " + boxes.get(j));
                        }
                    }
                }
                if (size[0] == 1920 && size[2] == 2) {
                    Path shot = c.ctx().takeScreenshot(Report.fileName(c.name() + "-gui2"));
                    Report.screenshot(c.name(), shot);
                    copyShot(c, shot, "vanilla-title-gui2.png");
                }
                if (size[0] == 854 && size[2] == 2) {
                    Path shot = c.ctx().takeScreenshot(Report.fileName(c.name() + "-small"));
                    Report.screenshot(c.name(), shot);
                    copyShot(c, shot, "vanilla-title-854x480-gui2.png");
                }
                checked++;
            }
            // Clicks: a real mouse click on each button opens the right screen (once, at the first size).
            setWindow(c, 1920, 1080, 2);
            for (String[] b : BUTTONS) {
                if (b[0].equals("Mods") && modMenu) {
                    continue;
                }
                open(c, false);
                double[] at = c.onClient(m -> {
                    for (Box bx : boxes(McCompat.screen(m))) {
                        if (bx.label.startsWith(b[0])) {
                            Window win = m.getWindow();
                            return new double[]{(bx.x + bx.w / 2.0) * win.getScreenWidth() / (double) win.getGuiScaledWidth(),
                                    (bx.y + bx.h / 2.0) * win.getScreenHeight() / (double) win.getGuiScaledHeight()};
                        }
                    }
                    return null;
                });
                c.check(at != null, "click: no '" + b[0] + "' button");
                if (at == null) {
                    continue;
                }
                c.ctx().getInput().setCursorPos(at[0], at[1]);
                c.ticks(2);
                c.ctx().getInput().pressMouse(McKeys.mouse(0));
                c.ticks(6);
                String now = c.onClient(m -> McCompat.screen(m) == null ? "none" : McCompat.screen(m).getClass().getSimpleName());
                c.check(now.equals(b[1]) || (b[1].equals("SimMenuScreen") && now.equals("SimMapEditorScreen")), "clicking '" + b[0] + "' opened " + now + ", expected " + b[1]);
            }
        } finally {
            setWindow(c, win0[0], win0[1], win0[2]);
            c.onClient(m -> {
                Mod.call(cfg, "setEnabled", e0);
                McCompat.setScreen(m, new TitleScreen());
                return null;
            });
        }
        c.check(checked == sizes.length, "only " + checked + " layouts checked");
    }

    private static void copyShot(UiCase c, Path shot, String name) {
        try {
            Path dir = Path.of(System.getProperty("user.home"), "killer560s-mod-logs", "vanillamenu-1116");
            Files.createDirectories(dir);
            Files.copy(shot, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            c.note("screenshot " + dir.resolve(name));
        } catch (Exception e) {
            c.problem("could not copy the screenshot: " + e);
        }
    }

    private static void open(UiCase c, boolean themed) {
        Object cfg = Mod.cfg("mainmenu.MainMenuThemeConfig");
        c.onClient(m -> {
            Mod.call(cfg, "setEnabled", themed);
            McCompat.setScreen(m, new TitleScreen());
            return null;
        });
        c.ticks(4);
    }

    private static List<Box> boxes(Screen s) {
        List<Box> out = new ArrayList<>();
        if (!(s instanceof TitleScreen)) {
            return out;
        }
        for (AbstractWidget w : Screens.getWidgets(s)) {
            if (!w.visible || w instanceof PlainTextButton) {
                continue;
            }
            String label = w.getMessage() == null ? "" : w.getMessage().getString();
            out.add(new Box(label, w.getClass().getSimpleName(), w.getX(), w.getY(), w.getWidth(), w.getHeight(),
                    w instanceof Button && w.getClass().getName().startsWith("net.minecraft.")));
        }
        return out;
    }

    private static void setWindow(UiCase c, int w, int h, int gui) {
        c.ctx().getInput().resizeWindow(w, h);
        c.ctx().waitTicks(3);
        c.ctx().runOnClient(m -> {
            m.options.guiScale().set(gui);
            m.resizeGui();
        });
        c.ctx().waitTicks(4);
    }
}

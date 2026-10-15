package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 341-ui-title-widgets (mod title-262, 2026-10-13). killer560 on 26.2: "it has the friends and mod menu thing on my
 * main screen on 26.2 and that should be hidden". 26.2's TitleScreen adds a row of icons (FriendsButton, language,
 * accessibility) and Mod Menu 20's icon style adds SmallModMenuButtonWidget to it; the themed layout left both on
 * top of Swap Accounts.
 *
 * <p>For each Mod Menu style (classic and icon, set through ModMenuConfig.MODS_BUTTON_STYLE; his 26.2 instance is
 * icon, his 26.1.2 ones classic) and each window size: with the theme OFF the vanilla screen must show Mod Menu's
 * button (and on 26.2 the Friends icon) - the positive control that they exist to be hidden; with it ON, every
 * visible widget must be one of the intended set (Singleplayer, Multiplayer, Dungeon Sim, Swap Accounts, Options,
 * Quit; the copyright line is removed since 2026-10-15) and no two may overlap. Mod Menu's classic Mods row is hidden too (killer560,
 * 2026-10-13: both versions show the same column). Run with Mod Menu passed in
 * -PmodUnderTest beside the mod jar; without it the Mod Menu half is noted as not run, not passed.
 */
final class TitleWidgetCases {

    private TitleWidgetCases() {
    }

    private record Box(String what, int x, int y, int w, int h) {
        boolean overlaps(Box o) {
            return x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h;
        }

        @Override
        public String toString() {
            return what + "@" + x + "," + y + " " + w + "x" + h;
        }
    }

    static void titleWidgets(UiCase c) {
        boolean modMenu = FabricLoader.getInstance().isModLoaded("modmenu");
        String mc = FabricLoader.getInstance().getModContainer("minecraft").map(m -> m.getMetadata().getVersion()
                .getFriendlyString()).orElse("?");
        boolean is262 = mc.startsWith("26.2");
        c.note("Minecraft " + mc + ", Mod Menu " + (modMenu ? FabricLoader.getInstance().getModContainer("modmenu")
                .get().getMetadata().getVersion().getFriendlyString() : "NOT LOADED"));

        Object cfg = Mod.cfg("mainmenu.MainMenuThemeConfig");
        boolean e0 = (Boolean) Mod.call(cfg, "isEnabled");
        Object style0 = modMenu ? styleValue() : null;
        int[] win0 = c.onClient(m -> new int[]{m.getWindow().getWidth(), m.getWindow().getHeight(),
                m.options.guiScale().get()});
        String[] styles = modMenu ? new String[]{"CLASSIC", "ICON"} : new String[]{"none"};
        int[][] sizes = {{win0[0], win0[1], win0[2]}, {1600, 900, 2}, {1920, 1080, 3}};
        int checked = 0;
        try {
            for (int[] size : sizes) {
                setWindow(c, size[0], size[1], size[2]);
                for (String style : styles) {
                    String tag = style + " " + size[0] + "x" + size[1] + "/gui" + size[2];
                    if (modMenu) {
                        c.onClient(m -> {
                            setStyle(style);
                            return null;
                        });
                    }
                    // Positive control: the vanilla (theme off) screen has the things the theme must hide.
                    List<String> off = open(c, false);
                    boolean mmIcon = off.stream().anyMatch(s -> s.startsWith("SmallModMenuButtonWidget")
                            || s.startsWith("UpdateCheckerTexturedButtonWidget"));
                    boolean mmRow = off.stream().anyMatch(s -> s.startsWith("ModMenuButtonWidget"));
                    boolean friends = off.stream().anyMatch(s -> s.startsWith("FriendsButton"));
                    if (style.equals("ICON")) {
                        c.check(mmIcon, tag + ": theme off shows no Mod Menu icon, so its hiding proves nothing: " + off);
                    } else if (style.equals("CLASSIC")) {
                        c.check(mmRow, tag + ": theme off shows no Mod Menu Mods button: " + off);
                    }
                    if (is262) {
                        c.check(friends, tag + ": theme off on 26.2 shows no FriendsButton: " + off);
                    }

                    open(c, true);
                    List<Box> boxes = c.onClient(m -> boxes(McCompat.screen(m)));
                    c.note(tag + " themed: " + boxes);
                    for (Box b : boxes) {
                        if (!intended(b.what, style)) {
                            c.problem(tag + ": unexpected widget on the themed title screen: " + b);
                        }
                    }
                    for (int i = 0; i < boxes.size(); i++) {
                        for (int j = i + 1; j < boxes.size(); j++) {
                            if (boxes.get(i).overlaps(boxes.get(j))) {
                                c.problem(tag + ": " + boxes.get(i) + " overlaps " + boxes.get(j));
                            }
                        }
                    }
                    long columnRows = boxes.stream().filter(b -> b.w == 200).count();
                    c.check(columnRows >= 5, tag + ": only " + columnRows + " full-width column rows: " + boxes);
                    c.check(columnRows == 5, tag + ": " + columnRows + " full-width rows, expected exactly 5 "
                            + "(Singleplayer, Multiplayer, Dungeon Sim, Mod Settings, Swap Accounts)");
                    if (size[0] == 1920) {
                        Path shot = c.ctx().takeScreenshot(Report.fileName(c.name() + "-" + style.toLowerCase()));
                        Report.screenshot(c.name(), shot);
                        c.note("screenshot " + shot);
                    }
                    checked++;
                }
            }
        } finally {
            setWindow(c, win0[0], win0[1], win0[2]);
            c.onClient(m -> {
                Mod.call(cfg, "setEnabled", e0);
                if (style0 != null) {
                    setStyleValue(style0);
                }
                McCompat.setScreen(m, new TitleScreen());
                return null;
            });
        }
        c.check(checked == sizes.length * styles.length, "only " + checked + " layouts checked");
        if (!modMenu) {
            c.note("Mod Menu not loaded: its half of this case did NOT run (pass its jar in -PmodUnderTest)");
        }
    }

    private static List<String> open(UiCase c, boolean themed) {
        Object cfg = Mod.cfg("mainmenu.MainMenuThemeConfig");
        c.onClient(m -> {
            Mod.call(cfg, "setEnabled", themed);
            McCompat.setScreen(m, new TitleScreen());
            return null;
        });
        c.ticks(4);
        return c.onClient(m -> boxes(McCompat.screen(m)).stream().map(Box::toString).toList());
    }

    private static List<Box> boxes(Screen s) {
        List<Box> out = new ArrayList<>();
        if (!(s instanceof TitleScreen)) {
            return out;
        }
        for (AbstractWidget w : Screens.getWidgets(s)) {
            if (!w.visible) {
                continue;
            }
            String label = w.getMessage() == null ? "" : w.getMessage().getString();
            String key = w.getMessage() != null && w.getMessage().getContents() instanceof TranslatableContents tc
                    ? tc.getKey() : "";
            out.add(new Box(w.getClass().getSimpleName() + "[" + (key.isEmpty() ? label : key) + "]",
                    w.getX(), w.getY(), w.getWidth(), w.getHeight()));
        }
        return out;
    }

    private static boolean intended(String what, String style) {
        return what.contains("[menu.singleplayer]") || what.contains("[menu.multiplayer]")
                || what.contains("[menu.options]") || what.contains("[menu.quit]")
                || what.startsWith("SwapAccountsButton[") || what.contains("[Dungeon Sim]")
                || what.contains("[Mod Settings]");
        // Vanilla's copyright link (PlainTextButton) is removed since mod mainmenu-footer-1015 (killer560, 2026-10-15).
    }

    // ---- Mod Menu's config, by reflection (no compile dependency) --------------------------------------------

    private static Field styleField() throws ReflectiveOperationException {
        return Class.forName("com.terraformersmc.modmenu.config.ModMenuConfig").getField("MODS_BUTTON_STYLE");
    }

    private static Object styleValue() {
        try {
            Object opt = styleField().get(null);
            return opt.getClass().getMethod("getValue").invoke(opt);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Mod Menu MODS_BUTTON_STYLE", e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setStyle(String name) {
        try {
            Class<? extends Enum> en = (Class<? extends Enum>) Class.forName(
                    "com.terraformersmc.modmenu.config.ModMenuConfig$TitleMenuButtonStyle");
            setStyleValue(Enum.valueOf(en, name));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Mod Menu TitleMenuButtonStyle", e);
        }
    }

    private static void setStyleValue(Object value) {
        try {
            Object opt = styleField().get(null);
            for (var m : opt.getClass().getMethods()) {
                if (m.getName().equals("setValue") && m.getParameterCount() == 1) {
                    m.invoke(opt, value);
                    return;
                }
            }
            throw new IllegalStateException("no setValue on " + opt.getClass());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Mod Menu setValue", e);
        }
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

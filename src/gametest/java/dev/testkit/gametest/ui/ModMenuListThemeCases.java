package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;

/**
 * 1104-ui-modmenu-list-theme: Mod Menu's own mod list draws the selected row through its
 * {@code ModListWidget.drawSelectionHighlight}; the mod's MenuModMenuListMixin replaces that box with the themed one
 * (orange outline from MenuChrome.ACCENT / ACCENT_LIGHT). Opens Mod Menu's ModsScreen with Themed Menus on, selects the
 * first row, takes a screenshot and counts accent-colored pixels inside the list's box. Mod Menu draws white/gray there,
 * so with the mixin dead the count is 0. Needs Mod Menu in the run (-ModUnderTest "mod.jar,modmenu.jar"); skips if not.
 */
final class ModMenuListThemeCases {

    private ModMenuListThemeCases() {
    }

    private static Object get(Object o, String name) throws Exception {
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object invoke(Object o, String name, Object... args) throws Exception {
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                    m.setAccessible(true);
                    return m.invoke(o, args);
                }
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static boolean near(int rgb, int argb) {
        return Math.abs(((rgb >> 16) & 0xFF) - ((argb >> 16) & 0xFF)) <= 12
                && Math.abs(((rgb >> 8) & 0xFF) - ((argb >> 8) & 0xFF)) <= 12
                && Math.abs((rgb & 0xFF) - (argb & 0xFF)) <= 12;
    }

    static void run(UiCase c) throws Exception {
        if (!FabricLoader.getInstance().isModLoaded("modmenu")) {
            c.skipCase("Mod Menu is not in this run (add it to -ModUnderTest)");
            return;
        }
        VanillaThemeCases.theme(c, "AMBER");
        int[] geom = new int[5];
        String why = c.onClient(mc -> {
            try {
                Class<?> k = Class.forName("com.terraformersmc.modmenu.gui.ModsScreen", true, mc.getClass().getClassLoader());
                Screen s = (Screen) k.getConstructor(Screen.class).newInstance((Screen) null);
                McCompat.setScreen(mc, s);
                return null;
            } catch (Throwable t) {
                return "cannot open Mod Menu's screen: " + t;
            }
        });
        if (why != null) {
            c.problem(why);
            return;
        }
        c.ticks(10);
        c.onClient(mc -> {
            try {
                invoke(get(McCompat.screen(mc), "modList"), "setSelected", new Object[]{null});
            } catch (Throwable t) {
                c.problem("cannot clear the selection: " + t);
            }
            return null;
        });
        Path before = VanillaThemeCases.shot(c, "modmenu-unselected");
        why = c.onClient(mc -> {
            try {
                Screen s = McCompat.screen(mc);
                Object list = get(s, "modList");
                int count = (Integer) invoke(list, "getItemCount");
                if (count < 1) {
                    return "Mod Menu's list is empty";
                }
                Object entry = invoke(list, "getEntry", 0);
                invoke(list, "setSelected", entry);
                geom[0] = (Integer) invoke(list, "getX");
                geom[1] = (Integer) invoke(list, "getY");
                geom[2] = (Integer) invoke(list, "getWidth");
                geom[3] = (Integer) invoke(list, "getHeight");
                geom[4] = mc.getWindow().getGuiScaledWidth();
                return null;
            } catch (Throwable t) {
                return "cannot select a row: " + t;
            }
        });
        if (why != null) {
            c.problem(why);
            return;
        }
        c.ticks(6);
        Path p = VanillaThemeCases.shot(c, "modmenu-selected");
        BufferedImage img = ImageIO.read(p.toFile());
        BufferedImage img0 = ImageIO.read(before.toFile());
        int accent = (Integer) Mod.field(Mod.ROOT + "mainmenu.MenuChrome", "ACCENT");
        int light = (Integer) Mod.field(Mod.ROOT + "mainmenu.MenuChrome", "ACCENT_LIGHT");
        double k = img.getWidth() / (double) geom[4];
        long accentPx = 0;
        long whitePx = 0;
        int x0 = (int) (geom[0] * k);
        int y0 = (int) (geom[1] * k);
        int x1 = Math.min(img.getWidth(), (int) ((geom[0] + geom[2]) * k));
        int y1 = Math.min(img.getHeight(), (int) ((geom[1] + geom[3]) * k));
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int px = img.getRGB(x, y);
                int px0 = img0.getRGB(x, y);
                if ((near(px, accent) || near(px, light)) && !(near(px0, accent) || near(px0, light))) {
                    accentPx++;
                }
                if (near(px, 0xFFFFFFFF)) {
                    whitePx++;
                }
            }
        }
        c.note("list box " + java.util.Arrays.toString(geom) + " accent=" + Integer.toHexString(accent) + "/" + Integer.toHexString(light)
                + " accentPx=" + accentPx + " whitePx=" + whitePx);
        c.check(accentPx >= 100, "Mod Menu's selected row has no themed outline (" + accentPx + " accent pixels that the selection added in the list box): "
                + "MenuModMenuListMixin did not apply");
        Report.screenshot(c.name(), p);
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
    }
}

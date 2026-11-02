package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.ServerLinks;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * 1101-pause-layout (mod pause263-1101; killer560, 2026-11-01: "in 26.3 the escape menu looks just a hair weird", then
 * "the issue with the menu is those little buttons. I dont think they are needed"): joins the dedicated server, gives the
 * client a server link (so the pause menu shows "Server Links..." the way Hypixel's does), opens the pause menu at
 * 1600x900, GUI scale 2, writes every widget's class, label and box, and takes a screenshot.
 * <ul>
 *   <li>-NoMod: vanilla, the four icon buttons present.</li>
 *   <li>With the mod: Hide Pause Menu Icons ON (the default) - no icon button, Server Links still there; then OFF - the
 *       four icons back. Amber theme, other menus on.</li>
 * </ul>
 * Every state must have Server Links and rows spaced evenly (the same step between consecutive button rows).
 * {@code TESTKIT_PAUSE_SHOTS=<dir>} copies the pictures and the dumps there.
 */
public class PauseLayoutTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1101-pause-layout",
                (server, s) -> TestMap.on(server).platform(8).catchFloor(140).survival().spawn(0.5, 0.5, 0f).build(),
                (server, s) -> body(ctx, s));
    }

    private static void body(ClientGameTestContext ctx, Scenario s) {
        boolean mod = FabricLoader.getInstance().isModLoaded("killer560smod");
        String ver = FabricLoader.getInstance().getModContainer("minecraft")
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("mc");
        ctx.getInput().resizeWindow(1600, 900);
        ctx.waitTicks(3);
        ctx.runOnClient(mc -> {
            mc.options.guiScale().set(2);
            mc.resizeGui();
            setServerLinks(mc.getConnection());
            if (mod) {
                Object mm = Mod.cfg("mainmenu.MainMenuThemeConfig");
                Mod.call(mm, "setEnabled", true);
                Mod.call(mm, "setOtherMenus", true);
                Mod.call(mm, "setStyle", Mod.enumValue("mainmenu.MainMenuThemeConfig$Style", "MATCH"));
                Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", Mod.enumValue("gui.MenuTheme", "AMBER"));
            }
        });
        ctx.waitTicks(4);
        if (!mod) {
            check(ctx, s, "vanilla-" + ver, 4);
            return;
        }
        boolean def = (Boolean) ctx.computeOnClient(mc -> Mod.call(Mod.cfg("mainmenu.MainMenuThemeConfig"), "isHidePauseIcons"));
        s.log("Hide Pause Menu Icons default: " + def);
        if (!def) {
            throw new AssertionError("Hide Pause Menu Icons is not ON by default");
        }
        check(ctx, s, "mod-hide-on-" + ver, 0);
        ctx.runOnClient(mc -> Mod.call(Mod.cfg("mainmenu.MainMenuThemeConfig"), "setHidePauseIcons", false));
        try {
            check(ctx, s, "mod-hide-off-" + ver, 4);
        } finally {
            ctx.runOnClient(mc -> Mod.call(Mod.cfg("mainmenu.MainMenuThemeConfig"), "setHidePauseIcons", true));
        }
    }

    /** The client's server links, as a server's custom_report_details/server_links packet would set them. */
    private static void setServerLinks(Object connection) {
        ServerLinks links = new ServerLinks(List.of(ServerLinks.Entry.knownType(ServerLinks.KnownLinkType.WEBSITE,
                URI.create("https://example.com"))));
        for (Class<?> c = connection.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == ServerLinks.class && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    try {
                        f.setAccessible(true);
                        f.set(connection, links);
                    } catch (ReflectiveOperationException e) {
                        throw new AssertionError("could not set the server links: " + e, e);
                    }
                    return;
                }
            }
        }
        throw new AssertionError("no ServerLinks field on " + connection.getClass().getName());
    }

    private static void check(ClientGameTestContext ctx, Scenario s, String tag, int wantIcons) {
        ctx.runOnClient(mc -> McCompat.setScreen(mc, new PauseScreen(true)));
        ctx.waitTicks(20);
        List<String> lines = new ArrayList<>();
        int[] icons = {0};
        boolean[] links = {false};
        TreeSet<Integer> rows = new TreeSet<>();
        ctx.runOnClient(mc -> {
            Screen screen = McCompat.screen(mc);
            lines.add("screen " + (screen == null ? "null" : screen.getClass().getName()) + " " + (screen == null ? 0
                    : screen.width) + "x" + (screen == null ? 0 : screen.height) + " guiScale "
                    + mc.getWindow().getGuiScale());
            if (screen != null) {
                walk(screen.children(), lines, "", icons, links, rows);
            }
        });
        // Rows of 20-high buttons (the title is a 9-high StringWidget and is left out); every step must be the same.
        List<Integer> ys = new ArrayList<>(rows);
        TreeSet<Integer> steps = new TreeSet<>();
        for (int i = 1; i < ys.size(); i++) {
            steps.add(ys.get(i) - ys.get(i - 1));
        }
        lines.add("rows y=" + ys + " steps=" + steps + " icons=" + icons[0] + " serverLinks=" + links[0]);
        Path shot = ctx.takeScreenshot("1101-pause-" + tag);
        String dir = System.getenv("TESTKIT_PAUSE_SHOTS");
        try {
            if (dir != null && !dir.isBlank()) {
                Path d = Path.of(dir);
                Files.createDirectories(d);
                Files.copy(shot, d.resolve("pause-" + tag + ".png"), StandardCopyOption.REPLACE_EXISTING);
                Files.write(d.resolve("pause-" + tag + ".txt"), lines, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            s.log("could not copy: " + e);
        }
        for (String l : lines) {
            s.log("[" + tag + "] " + l);
        }
        ctx.runOnClient(mc -> McCompat.setScreen(mc, null));
        ctx.waitTicks(2);
        if (ys.size() < 4) {
            throw new AssertionError(tag + ": fewer than 4 button rows on the pause menu: " + lines);
        }
        if (!links[0]) {
            throw new AssertionError(tag + ": no Server Links button");
        }
        if (icons[0] != wantIcons) {
            throw new AssertionError(tag + ": " + icons[0] + " icon buttons, wanted " + wantIcons);
        }
        if (steps.size() != 1) {
            throw new AssertionError(tag + ": rows are not evenly spaced: y=" + ys + " steps=" + steps);
        }
    }

    private static void walk(List<? extends GuiEventListener> children, List<String> out, String indent, int[] icons,
                             boolean[] links, TreeSet<Integer> rows) {
        for (GuiEventListener l : children) {
            if (l instanceof AbstractWidget w) {
                out.add(String.format(Locale.ROOT, "%s%s '%s' x=%d y=%d w=%d h=%d active=%s", indent,
                        w.getClass().getName(), w.getMessage().getString(), w.getX(), w.getY(), w.getWidth(),
                        w.getHeight(), w.active));
                if (w instanceof SpriteIconButton) {
                    icons[0]++;
                }
                if (w.getMessage().getString().startsWith("Server Links")) {
                    links[0] = true;
                }
                if (w.getHeight() == 20 && w.visible) {
                    rows.add(w.getY());
                }
            } else {
                out.add(indent + l.getClass().getName());
            }
            if (l instanceof net.minecraft.client.gui.components.events.ContainerEventHandler c) {
                walk(c.children(), out, indent + "  ", icons, links, rows);
            }
        }
    }
}

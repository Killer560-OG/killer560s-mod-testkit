package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 1101-pause-layout (mod pause263-1101; killer560, 2026-11-01: "in 26.3 the escape menu looks just a hair weird"): joins
 * the dedicated server, opens the pause menu at 1600x900, GUI scale 2, writes every widget's class, label and box, and
 * takes a screenshot. Runs with the mod (theme on, other menus on, Amber) and with -NoMod, so the two can be compared.
 * {@code TESTKIT_PAUSE_SHOTS=<dir>} copies the picture and the dump there. Fails if the pause menu has no widgets
 * (nothing was drawn, so nothing was compared).
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
        String tag = (mod ? "mod" : "vanilla") + "-" + FabricLoader.getInstance().getModContainer("minecraft")
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("mc");
        ctx.getInput().resizeWindow(1600, 900);
        ctx.waitTicks(3);
        ctx.runOnClient(mc -> {
            mc.options.guiScale().set(2);
            mc.resizeGui();
            if (mod) {
                Object mm = Mod.cfg("mainmenu.MainMenuThemeConfig");
                Mod.call(mm, "setEnabled", true);
                Mod.call(mm, "setOtherMenus", true);
                Mod.call(mm, "setStyle", Mod.enumValue("mainmenu.MainMenuThemeConfig$Style", "MATCH"));
                Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", Mod.enumValue("gui.MenuTheme", "AMBER"));
            }
        });
        ctx.waitTicks(4);
        ctx.runOnClient(mc -> McCompat.setScreen(mc, new PauseScreen(true)));
        ctx.waitTicks(20);
        List<String> lines = ctx.computeOnClient(mc -> {
            Screen screen = McCompat.screen(mc);
            List<String> out = new ArrayList<>();
            out.add("screen " + (screen == null ? "null" : screen.getClass().getName()) + " " + (screen == null ? 0
                    : screen.width) + "x" + (screen == null ? 0 : screen.height) + " guiScale "
                    + mc.getWindow().getGuiScale());
            if (screen != null) {
                walk(screen.children(), out, "");
            }
            return out;
        });
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
            s.log(l);
        }
        if (lines.size() < 6) {
            throw new AssertionError("the pause menu showed fewer than 5 widgets: " + lines);
        }
        ctx.runOnClient(mc -> McCompat.setScreen(mc, null));
    }

    private static void walk(List<? extends GuiEventListener> children, List<String> out, String indent) {
        for (GuiEventListener l : children) {
            if (l instanceof AbstractWidget w) {
                out.add(String.format(Locale.ROOT, "%s%s '%s' x=%d y=%d w=%d h=%d active=%s", indent,
                        w.getClass().getName(), w.getMessage().getString(), w.getX(), w.getY(), w.getWidth(),
                        w.getHeight(), w.active));
            } else {
                out.add(indent + l.getClass().getName());
            }
            if (l instanceof net.minecraft.client.gui.components.events.ContainerEventHandler c) {
                walk(c.children(), out, indent + "  ");
            }
        }
    }
}

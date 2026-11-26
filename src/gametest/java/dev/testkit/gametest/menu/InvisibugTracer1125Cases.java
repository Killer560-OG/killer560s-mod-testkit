package dev.testkit.gametest.menu;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.components.AbstractWidget;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 1125-menu-invisibug-tracer (invibug-tracer-1125, killer560 11-25: "for invibug add an option to add a tracer line"): the
 * Invisibug Highlight card has a Tracer Line ON/OFF row (default OFF) that is found by the menu search while the card is
 * off; pressing it turns the setting on and writes it to the config file, and it stays on after a re-read of the file (a
 * restart) and after a profile reload.
 */
final class InvisibugTracer1125Cases {

    private static final String TAB = "gui.tab.InvisibugHighlightTab";

    private InvisibugTracer1125Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1125-menu-invisibug-tracer", InvisibugTracer1125Cases::tracer);
    }

    @SuppressWarnings("unchecked")
    private static List<AbstractWidget> widgets(boolean scan) {
        try {
            Object tab = Mod.cls(TAB).getDeclaredConstructor().newInstance();
            if (scan) {
                return (List<AbstractWidget>) Mod.call(tab, "searchScanWidgets");
            }
            return (List<AbstractWidget>) tab.getClass().getMethod("buildWidgets", int.class, int.class, int.class,
                    Runnable.class).invoke(tab, 10, 10, 330, (Runnable) () -> { });
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static AbstractWidget row(List<AbstractWidget> ws) {
        for (AbstractWidget w : ws) {
            if (w.getMessage().getString().startsWith("Tracer Line:")) {
                return w;
            }
        }
        return null;
    }

    private static void tracer(Session c) {
        String result = c.onClient(mc -> {
            Object cfg = Mod.field("foraging.InvisibugHighlight", "CFG");
            boolean wasOn = (Boolean) Mod.call(cfg, "isEnabledRaw");
            boolean wasTracer = (Boolean) Mod.call(cfg, "on", "tracer");
            try {
                Mod.call(cfg, "set", "tracer", false);
                Mod.call(cfg, "set", "enabled", false);
                Mod.call(cfg, "save");
                Mod.call(cfg, "load");
                if ((Boolean) Mod.call(cfg, "on", "tracer")) {
                    return "Tracer Line did not stay OFF after a save and re-read";
                }
                AbstractWidget scanRow = row(widgets(true));
                if (scanRow == null) {
                    return "the search scan of the card (switch OFF) has no Tracer Line row";
                }
                Mod.call(cfg, "set", "enabled", true);
                AbstractWidget w = row(widgets(false));
                if (w == null) {
                    return "the card has no Tracer Line row";
                }
                String before = w.getMessage().getString();
                Mod.call(w, "press");
                String after = w.getMessage().getString();
                if (!(Boolean) Mod.call(cfg, "on", "tracer")) {
                    return "pressing '" + before + "' did not turn the setting on (label now '" + after + "')";
                }
                Path file = Path.of(String.valueOf(Mod.call(cfg, "path")));
                String disk = Files.readString(file);
                if (!disk.replace(" ", "").contains("\"tracer\":true")) {
                    return "the file does not hold tracer true after the press: " + disk;
                }
                Mod.call(cfg, "set", "tracer", false);
                Mod.call(cfg, "load");
                if (!(Boolean) Mod.call(cfg, "on", "tracer")) {
                    return "Tracer Line was OFF after a re-read of its file (a restart)";
                }
                Mod.call(cfg, "set", "tracer", false);
                Mod.staticCall("profiles.ProfileManager", "reloadAllConfigs");
                if (!(Boolean) Mod.call(cfg, "on", "tracer")) {
                    return "Tracer Line was OFF after a profile reload";
                }
                return "ok " + before + " -> " + after;
            } catch (Exception e) {
                return "threw " + e;
            } finally {
                Mod.call(cfg, "set", "tracer", wasTracer);
                Mod.call(cfg, "set", "enabled", wasOn);
                Mod.call(cfg, "save");
            }
        });
        c.note(result);
        c.check(result.startsWith("ok"), result);
    }
}

package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.components.AbstractWidget;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 1124-ui-gaps-rows (mod gaps-sounds-hud-1124): every new row exists on its card (found through the search scan, so a
 * switched-off card still builds it) and a real click on it changes the setting: the Chat Hider custom box (typed value and
 * Enter), the Dungeon Alerts sound switches, the Terracotta Timer size slider and Seconds/Ticks cycler, Mask Invincibility
 * Proc Sound, and the HUD Text pair (Text Shadow, Background) on the nine timer HUD cards.
 */
final class GapsSoundsHud1124Cases {

    private static final String ACFG = "dungeonalerts.DungeonAlertsConfig";
    private static final String STYLE = "hud.HudTextStyle";

    private GapsSoundsHud1124Cases() {
    }

    @SuppressWarnings("unchecked")
    private static List<AbstractWidget> scan(String tabName) {
        for (Object t : MenuACases.leaves()) {
            if (tabName.equals(MenuACases.name(t))) {
                try {
                    return (List<AbstractWidget>) t.getClass().getMethod("searchScanWidgets").invoke(t);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("could not build " + tabName + ": " + UiCase.describe(e), e);
                }
            }
        }
        throw new AssertionError("no '" + tabName + "' tab among the menu's leaves");
    }

    private static List<String> labels(List<AbstractWidget> ws) {
        List<String> out = new ArrayList<>();
        for (AbstractWidget w : ws) {
            out.add(ModScreenDriver.label(w));
        }
        return out;
    }

    private static AbstractWidget find(String tab, String startsWith) {
        List<AbstractWidget> ws = scan(tab);
        for (AbstractWidget w : ws) {
            if (ModScreenDriver.label(w).startsWith(startsWith)) {
                return w;
            }
        }
        throw new AssertionError("no row '" + startsWith + "' on " + tab + ": " + labels(ws));
    }

    private static Object field(Object o, String name) {
        try {
            Field f = o.getClass().getField(name);
            return f.get(o);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no field " + name, e);
        }
    }

    static void rows(UiCase c) {
        String[][] huds = {{"Split Timers", "split_timers", "false"}, {"Tick Timers", "tick_timers", "false"},
                {"Mask Invincibility", "mask_invincibility", "false"}, {"Secret", "dungeon_info", "false"},
                {"Puzzle HUD", "puzzle_hud", "true"}, {"Leap Counter", "leap_counter", "true"}, {"Boss Health", "boss_health", "true"},
                {"Wither Dragons", "wither_dragon_timers", "true"}, {"Wither Dragons", "king_relic_timer", "true"}};
        try {
            // HUD Text pair: Background turns on, Text Shadow flips away from the HUD's old value.
            for (String[] h : huds) {
                boolean def = Boolean.parseBoolean(h[2]);
                boolean dragons = h[1].equals("king_relic_timer");
                // The Dragons card holds two pairs: the first belongs to the dragon timers, the second to the relic timer.
                List<AbstractWidget> ws = c.onClient(mc -> scan(h[0]));
                int skip = dragons ? 1 : 0;
                AbstractWidget bg = null;
                AbstractWidget sh = null;
                int seenBg = 0;
                int seenSh = 0;
                for (AbstractWidget w : ws) {
                    String l = ModScreenDriver.label(w);
                    if (l.startsWith("Background")) {
                        if (seenBg++ == skip) {
                            bg = w;
                        }
                    } else if (l.startsWith("Text Shadow")) {
                        if (seenSh++ == skip) {
                            sh = w;
                        }
                    }
                }
                c.check(bg != null && sh != null, h[0] + " has no HUD Text pair: " + labels(ws));
                if (bg == null || sh == null) {
                    continue;
                }
                AbstractWidget bgW = bg;
                AbstractWidget shW = sh;
                c.onClient(mc -> {
                    ModScreenDriver.press(bgW);
                    ModScreenDriver.press(shW);
                    return null;
                });
                c.check((Boolean) c.onClient(mc -> Mod.staticCall(STYLE, "background", h[1])), h[1] + ": the Background row did not turn it on");
                c.check(c.onClient(mc -> (Boolean) Mod.staticCall(STYLE, "shadow", h[1], def)) == !def,
                        h[1] + ": the Text Shadow row did not flip the shadow");
            }

            // Dungeon Alerts sound switches: each press flips the field from ON to OFF.
            Object[][] sounds = {{"Room Alert Sound", "roomAlertsSound"}, {"Key Spawn Sound", "keySpawnSound"},
                    {"Rare Room Sound", "rareRoomSound"}, {"Rare Drop Sound", "rareDropSound"},
                    {"Duplicate Class Sound", "duplicateClassSound"}, {"Dimensional Slash Sound", "dimensionalSlashSound"}};
            for (Object[] s : sounds) {
                AbstractWidget w = c.onClient(mc -> find("Dungeon Alerts", (String) s[0]));
                c.check((Boolean) c.onClient(mc -> field(Mod.cfg(ACFG), (String) s[1])), s[1] + " is not ON before the click");
                c.onClient(mc -> ModScreenDriver.press(w));
                c.check(!(Boolean) c.onClient(mc -> field(Mod.cfg(ACFG), (String) s[1])), s[0] + " row did not turn " + s[1] + " off");
            }

            // Mask Invincibility Proc Sound: OFF to ON.
            AbstractWidget proc = c.onClient(mc -> find("Mask Invincibility", "Proc Sound"));
            c.onClient(mc -> ModScreenDriver.press(proc));
            c.check((Boolean) c.onClient(mc -> Mod.call(Mod.cfg("maskinvincibility.MaskInvincibilityConfig"), "isProcSound")),
                    "the Proc Sound row did not turn it on");

            // Terracotta Timer: the cycler steps Seconds -> Ticks; the slider is there.
            AbstractWidget show = c.onClient(mc -> find("Terracotta Timer", "Show: Seconds"));
            c.onClient(mc -> ModScreenDriver.press(show));
            c.check((Boolean) c.onClient(mc -> field(Mod.cfg(ACFG), "terracottaTicks")), "the Show cycler did not switch to Ticks");
            c.onClient(mc -> find("Terracotta Timer", "Text Size: 2.0x"));

            // Chat Hider custom box: typed value plus Enter saves it.
            List<AbstractWidget> hider = c.onClient(mc -> scan("Chat Hider"));
            AbstractWidget box = null;
            for (AbstractWidget w : hider) {
                if (w.getClass().getSimpleName().equals("CommitEditBox")) {
                    box = w;
                }
            }
            c.check(box != null, "Chat Hider has no CommitEditBox: " + labels(hider));
            if (box != null) {
                AbstractWidget b = box;
                c.onClient(mc -> {
                    try {
                        b.getClass().getMethod("setValue", String.class).invoke(b, "tree gift | spam");
                        Mod.call(b, "commit");
                    } catch (ReflectiveOperationException e) {
                        throw new AssertionError(UiCase.describe(e), e);
                    }
                    return null;
                });
                c.check("tree gift | spam".equals(c.onClient(mc -> Mod.call(Mod.cfg("chattidy.ChatTidyConfig"), "getHideCustom"))),
                        "the custom box did not save: " + c.onClient(mc -> Mod.call(Mod.cfg("chattidy.ChatTidyConfig"), "getHideCustom")));
            }
        } finally {
            c.onClient(mc -> {
                for (String[] h : huds) {
                    Mod.staticCall(STYLE, "setShadow", h[1], Boolean.parseBoolean(h[2]));
                    Mod.staticCall(STYLE, "setBackground", h[1], false);
                }
                Object a = Mod.cfg(ACFG);
                try {
                    for (String f : new String[] {"roomAlertsSound", "keySpawnSound", "rareRoomSound", "rareDropSound",
                            "duplicateClassSound", "dimensionalSlashSound"}) {
                        a.getClass().getField(f).set(a, true);
                    }
                    a.getClass().getField("terracottaTicks").set(a, false);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
                Mod.call(a, "save");
                Mod.call(Mod.cfg("maskinvincibility.MaskInvincibilityConfig"), "setProcSound", false);
                Mod.call(Mod.cfg("chattidy.ChatTidyConfig"), "setHideCustom", "");
                Mod.call(Mod.cfg("chattidy.ChatTidyConfig"), "save");
                return null;
            });
        }
    }
}

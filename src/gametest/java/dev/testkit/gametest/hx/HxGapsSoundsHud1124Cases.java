package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.SoundProbe;

import java.lang.reflect.Field;
import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod gaps-sounds-hud-1124 (killer560 approved the settings-gaps list, 2026-11-23): the Chat Hider custom list, the Dungeon
 * Alerts sound switches, the Mask Invincibility proc sound, the Terracotta Timer size and Ticks and the shared timer HUD
 * shadow/background store. Sounds are judged by {@link SoundProbe} (a pling that reached the sound engine), never by a
 * counter alone.
 */
final class HxGapsSoundsHud1124Cases {

    private static final String PLING = "minecraft:block.note_block.pling";
    private static final String TIDY = "chattidy.ChatTidy";
    private static final String CFG = "chattidy.ChatTidyConfig";
    private static final String ALERTS = "dungeonalerts.DungeonAlertsFeature";
    private static final String ACFG = "dungeonalerts.DungeonAlertsConfig";
    private static final String MASK_CFG = "maskinvincibility.MaskInvincibilityConfig";
    private static final String STYLE = "hud.HudTextStyle";

    private HxGapsSoundsHud1124Cases() {
    }

    static void register(Session s) {
        s.test("1124-chathider-custom", HxGapsSoundsHud1124Cases::chatHiderCustom);
        s.test("1124-alerts-sounds", HxGapsSoundsHud1124Cases::alertSounds);
        s.test("1124-mask-procsound", HxGapsSoundsHud1124Cases::maskProcSound);
        s.test("1124-terracotta-style", HxGapsSoundsHud1124Cases::terracotta);
        s.test("1124-hudtext-store", HxGapsSoundsHud1124Cases::hudTextStore);
    }

    private static void skyblock(Session c) {
        c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Jungle", "Purse: 0");
        c.ctx().waitTicks(10);
    }

    private static int plings(Session c) {
        return c.onClient(mc -> SoundProbe.count(PLING));
    }

    private static Object getField(Object o, String name) {
        try {
            return o.getClass().getField(name).get(o);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no public field " + name, e);
        }
    }

    private static void setField(Object o, String name, Object v) {
        try {
            Field f = o.getClass().getField(name);
            f.set(o, v);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no public field " + name, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> lines(Session c) {
        List<String> raw = c.onClient(mc -> (List<String>) Mod.staticCall(TIDY, "testChatLines", 40));
        return raw.stream().map(l -> l.replaceAll("§.", "")).toList();
    }

    private static void say(Session c, String legacy) {
        c.hx().chat(legacy);
        c.ctx().waitTicks(6);
    }

    // ---- Chat Hider custom list -----------------------------------------------------------------------------------

    private static void chatHiderCustom(Session c) {
        hub(c);
        skyblock(c);
        Object cfg = c.onClient(mc -> Mod.cfg(CFG));
        boolean enabledWas = c.onClient(mc -> (Boolean) Mod.call(cfg, "getEnabledRaw"));
        String listWas = c.onClient(mc -> (String) Mod.call(cfg, "getHideCustom"));
        try {
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setHideCustom", " Zebra Crossing | | purple llama  |" + "x".repeat(150));
                Mod.call(cfg, "save");
            });
            // The stored value is trimmed, empty entries dropped and each entry cut to 100 characters.
            c.ctx().runOnClient(mc -> Mod.staticCall(CFG, "load"));
            String kept = c.onClient(mc -> (String) Mod.call(Mod.cfg(CFG), "getHideCustom"));
            c.check(kept.equals("Zebra Crossing | purple llama | " + "x".repeat(100)), "saved list after reload: '" + kept + "'");
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setHideCustom", "zebra crossing|PURPLE LLAMA"));

            long h0 = c.onClient(mc -> ((Number) Mod.staticCall(TIDY, "testHidden")).longValue());
            say(c, "§7The ZEBRA CROSSING is closed today");
            say(c, "§7a purple llama walked by");
            say(c, "§7An ordinary line of chat");
            long hidden = c.onClient(mc -> ((Number) Mod.staticCall(TIDY, "testHidden")).longValue()) - h0;
            c.check(hidden == 2, "the mod hid " + hidden + " lines, expected the 2 matching ones");
            List<String> shown = lines(c);
            c.check(shown.contains("An ordinary line of chat"), "the non-matching line is not in the chat window: " + shown);
            c.check(shown.stream().noneMatch(l -> l.toLowerCase().contains("zebra crossing") || l.contains("purple llama")),
                    "a matching line is in the chat window: " + shown);

            // Master switch off: nothing hidden even with a list.
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setEnabled", false));
            say(c, "§7zebra crossing again");
            c.check(lines(c).contains("zebra crossing again"), "Chat Hider off still hid the line: " + lines(c));
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setHideCustom", listWas);
                Mod.call(cfg, "setEnabled", enabledWas);
            });
        }
    }

    // ---- Dungeon Alerts sound switches ------------------------------------------------------------------------------

    private static final String[][] ALERT_KEYS = {
            {"rareroom", "rareRoomSound"}, {"raredrop", "rareDropSound"}, {"dupclass", "duplicateClassSound"},
            {"slash", "dimensionalSlashSound"}};

    private static void alertSounds(Session c) {
        hub(c);
        skyblock(c);
        Object cfg = c.onClient(mc -> Mod.cfg(ACFG));
        String[] all = {"rareRoomSound", "rareDropSound", "duplicateClassSound", "dimensionalSlashSound", "keySpawnSound", "roomAlertsSound"};
        for (String f : all) {
            c.check((Boolean) getField(cfg, f), f + " is not ON by default");
        }
        try {
            // Persistence: every switch OFF, saved, reloaded.
            c.ctx().runOnClient(mc -> {
                for (String f : all) {
                    setField(cfg, f, false);
                }
                Mod.call(cfg, "save");
                Mod.staticCall(ACFG, "load");
            });
            Object re = c.onClient(mc -> Mod.cfg(ACFG));
            for (String f : all) {
                c.check(!(Boolean) getField(re, f), f + " did not survive save and load");
            }
            // OFF: the alert still shows (count rises) but no pling reaches the engine.
            int p0 = plings(c);
            for (String[] k : ALERT_KEYS) {
                int n0 = c.onClient(mc -> (Integer) Mod.staticCall(ALERTS, "alertCount", k[0]));
                c.ctx().runOnClient(mc -> Mod.staticCall(ALERTS, "alert", k[0], "§dTest " + k[0], ""));
                c.ctx().waitTicks(4);
                c.check(c.onClient(mc -> (Integer) Mod.staticCall(ALERTS, "alertCount", k[0])) == n0 + 1, k[0] + " alert did not show");
            }
            c.ctx().runOnClient(mc -> Mod.staticCall("dungeonalerts.KeySpawnAlert", "alert", true));
            c.ctx().runOnClient(mc -> Mod.staticCall("dungeonalerts.RoomAlerts", "fireAlert", Mod.cfg(ACFG), "Room Cleared", 0x55FF55));
            c.ctx().waitTicks(10);
            int silent = plings(c) - p0;
            c.check(silent == 0, silent + " pling(s) played with every alert sound OFF");

            // ON: each alert plays exactly one pling.
            c.ctx().runOnClient(mc -> {
                for (String f : all) {
                    setField(Mod.cfg(ACFG), f, true);
                }
            });
            for (String[] k : ALERT_KEYS) {
                int before = plings(c);
                c.ctx().runOnClient(mc -> Mod.staticCall(ALERTS, "alert", k[0], "§dTest " + k[0], ""));
                c.ctx().waitTicks(8);
                c.check(plings(c) - before == 1, k[0] + " alert with its sound ON played " + (plings(c) - before) + " plings");
            }
            int b = plings(c);
            c.ctx().runOnClient(mc -> Mod.staticCall("dungeonalerts.KeySpawnAlert", "alert", true));
            c.ctx().waitTicks(8);
            c.check(plings(c) - b == 1, "key spawn with its sound ON played " + (plings(c) - b) + " plings");
            b = plings(c);
            c.ctx().runOnClient(mc -> Mod.staticCall("dungeonalerts.RoomAlerts", "fireAlert", Mod.cfg(ACFG), "Room Cleared", 0x55FF55));
            c.ctx().waitTicks(8);
            c.check(plings(c) - b == 1, "room alert with its sound ON played " + (plings(c) - b) + " plings");
        } finally {
            c.ctx().runOnClient(mc -> {
                for (String f : all) {
                    setField(Mod.cfg(ACFG), f, true);
                }
                Mod.call(Mod.cfg(ACFG), "save");
            });
        }
    }

    // ---- Mask Invincibility proc sound ------------------------------------------------------------------------------

    private static void maskProcSound(Session c) {
        hub(c);
        skyblock(c);
        try (Settings set = new Settings(c).with(MASK_CFG, "Enabled", true)) {
            Object cfg = c.onClient(mc -> Mod.cfg(MASK_CFG));
            c.check(!(Boolean) c.onClient(mc -> Mod.call(cfg, "isProcSound")), "Proc Sound is not OFF by default");
            int p0 = plings(c);
            say(c, "§aYour Bonzo's Mask saved your life!");
            c.ctx().waitTicks(8);
            c.check(plings(c) == p0, "a proc played a pling with Proc Sound OFF");
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setProcSound", true);
                Mod.call(cfg, "save");
                Mod.staticCall(MASK_CFG, "load");
            });
            c.check((Boolean) c.onClient(mc -> Mod.call(Mod.cfg(MASK_CFG), "isProcSound")), "Proc Sound did not survive save and load");
            c.ctx().waitTicks(2);
            int p1 = plings(c);
            say(c, "§aYour Bonzo's Mask saved your life!");
            c.ctx().waitTicks(8);
            c.check(plings(c) - p1 == 1, "a proc with Proc Sound ON played " + (plings(c) - p1) + " plings");
            c.ctx().runOnClient(mc -> {
                Mod.call(Mod.cfg(MASK_CFG), "setProcSound", false);
                Mod.call(Mod.cfg(MASK_CFG), "save");
            });
        }
    }

    // ---- Terracotta Timer -------------------------------------------------------------------------------------------

    private static void terracotta(Session c) {
        Object cfg = c.onClient(mc -> Mod.cfg(ACFG));
        c.check((Float) getField(cfg, "terracottaScale") == 2.0f && !(Boolean) getField(cfg, "terracottaTicks"),
                "defaults are not 2x and seconds");
        try {
            c.ctx().runOnClient(mc -> {
                setField(cfg, "terracottaScale", 3.5f);
                setField(cfg, "terracottaTicks", true);
                Mod.call(cfg, "save");
                Mod.staticCall(ACFG, "load");
            });
            Object re = c.onClient(mc -> Mod.cfg(ACFG));
            c.check((Float) getField(re, "terracottaScale") == 3.5f && (Boolean) getField(re, "terracottaTicks"),
                    "scale " + getField(re, "terracottaScale") + " ticks " + getField(re, "terracottaTicks") + " after reload");
            String seconds = c.onClient(mc -> (String) Mod.staticCall("dungeonalerts.TerracottaTimer", "label", 247, false));
            String ticks = c.onClient(mc -> (String) Mod.staticCall("dungeonalerts.TerracottaTimer", "label", 247, true));
            c.check(seconds.replaceAll("§.", "").equals("12.35s"), "seconds label '" + seconds + "'");
            c.check(ticks.replaceAll("§.", "").equals("247t"), "ticks label '" + ticks + "'");
            // The table keeps the two apart: asking again for seconds still gives seconds.
            c.check(c.onClient(mc -> (String) Mod.staticCall("dungeonalerts.TerracottaTimer", "label", 247, false)).equals(seconds),
                    "the seconds label changed after a ticks label was cached");
        } finally {
            c.ctx().runOnClient(mc -> {
                setField(Mod.cfg(ACFG), "terracottaScale", 2.0f);
                setField(Mod.cfg(ACFG), "terracottaTicks", false);
                Mod.call(Mod.cfg(ACFG), "save");
            });
        }
    }

    // ---- shared timer HUD text store --------------------------------------------------------------------------------

    private static void hudTextStore(Session c) {
        String[][] huds = {{"split_timers", "false"}, {"tick_timers", "false"}, {"mask_invincibility", "false"}, {"dungeon_info", "false"},
                {"puzzle_hud", "true"}, {"leap_counter", "true"}, {"boss_health", "true"}, {"wither_dragon_timers", "true"}, {"king_relic_timer", "true"}};
        try {
            for (String[] h : huds) {
                boolean def = Boolean.parseBoolean(h[1]);
                c.check(c.onClient(mc -> (Boolean) Mod.staticCall(STYLE, "shadow", h[0], def)) == def, h[0] + " default shadow changed");
                c.check(!(Boolean) c.onClient(mc -> Mod.staticCall(STYLE, "background", h[0])), h[0] + " background is not OFF by default");
                c.ctx().runOnClient(mc -> {
                    Mod.staticCall(STYLE, "setShadow", h[0], !def);
                    Mod.staticCall(STYLE, "setBackground", h[0], true);
                });
            }
            c.ctx().runOnClient(mc -> Mod.staticCall(STYLE, "load"));
            for (String[] h : huds) {
                boolean def = Boolean.parseBoolean(h[1]);
                c.check(c.onClient(mc -> (Boolean) Mod.staticCall(STYLE, "shadow", h[0], def)) == !def, h[0] + " shadow did not survive a reload");
                c.check((Boolean) c.onClient(mc -> Mod.staticCall(STYLE, "background", h[0])), h[0] + " background did not survive a reload");
            }
        } finally {
            c.ctx().runOnClient(mc -> {
                for (String[] h : huds) {
                    Mod.staticCall(STYLE, "setShadow", h[0], Boolean.parseBoolean(h[1]));
                    Mod.staticCall(STYLE, "setBackground", h[0], false);
                }
            });
        }
    }
}

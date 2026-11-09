package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.network.chat.Component;

/**
 * 1106-leaptick-overlay / 1106-leaptick-plain (mod branch leaptick-1106; killer560, 2026-11-06: "in a leap menu i should
 * still be able to see everything tick timer related"). With a Necron countdown running, the Spirit Leap menu opens (the
 * mod's overlay, then the plain chest menu with the overlay off): Tick Timers must draw (HudSeen stamp taken after the
 * menu opened, so only a draw over it counts) and its Necron value must keep falling while the menu is open.
 */
final class LeapTickCases {

    private static final String TT = "ticktimers.TickTimersFeature";

    private LeapTickCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1106-leaptick-overlay", c -> run(c, true));
        MenuSuite.test(s, "1106-leaptick-plain", c -> run(c, false));
    }

    private static void run(Session c, boolean overlay) throws Exception {
        MenuKit.reset(c);
        Object simWas = Mod.field("secrets.DungeonState", "simOverrideActive");
        boolean gateWas = (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled");
        Mod.staticCall("util.SkyblockGate", "setEnabled", false);
        Mod.setField("secrets.DungeonState", "simOverrideActive", true);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c);
             AutoCloseable on = Mod.with("ticktimers.TickTimersConfig", "Enabled", true);
             AutoCloseable n = Mod.with("ticktimers.TickTimersConfig", "NecronTimer", true)) {
            cfg.set("spiritleap.SpiritLeapOverlayConfig", "Enabled", overlay);
            c.onClient(mc -> {
                Mod.staticCall(TT, "resetAll");
                Mod.staticCall(TT, "onChatMessage", Component.literal(
                        "[BOSS] Necron: You went further than any human before, congratulations."));
                Mod.staticCall("hud.HudSeen", "forget", "tick_timers");
                return null;
            });
            JsonObject spec = MenuKit.menu("menus.spirit-leap");
            MenuKit.show(c, spec);
            MenuKit.awaitScreen(c, "Spirit Leap", 100);
            if (overlay) {
                c.waitUntil("the overlay to hide the vanilla menu", mc ->
                        (Boolean) Mod.staticCall("spiritleap.SpiritLeapOverlayFeature", "isHiding",
                                dev.testkit.compat.McCompat.screen(mc)), 100);
            }
            // The stamp is dropped with the menu fully open, so only a draw over the menu can set it again.
            c.onClient(mc -> {
                Mod.staticCall("hud.HudSeen", "forget", "tick_timers");
                return null;
            });
            int first = c.onClient(mc -> (Integer) Mod.field(TT, "necronTicks"));
            c.waitUntil("Tick Timers drawn over the open leap menu", mc ->
                    (Boolean) Mod.staticCall("hud.HudSeen", "drawnRecently", "tick_timers"), 60);
            c.ctx().waitTicks(10);
            int later = c.onClient(mc -> (Integer) Mod.field(TT, "necronTicks"));
            c.check(first > 0 && later < first, "Necron countdown over the leap menu: " + first + " then " + later);
            c.note((overlay ? "overlay" : "plain") + " leap menu: tick timers drew, Necron " + first + " -> " + later);
        } finally {
            MenuKit.reset(c);
            c.onClient(mc -> {
                Mod.staticCall(TT, "resetAll");
                return null;
            });
            Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas);
            Mod.setField("secrets.DungeonState", "simOverrideActive", simWas);
        }
    }
}

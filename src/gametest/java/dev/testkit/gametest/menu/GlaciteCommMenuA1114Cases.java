package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mod branch glacitecomm-a-1114: a right click on a commission in the Commissions menu picks it for Commission Route.
 * He stands in the Glacite Tunnels (sidebar and tab), the Tunnels' test graph holds Umber nodes, the menu lists
 * "Umber Collector" (slot 11) and "Mineshaft Explorer" (slot 12). A right click on Mineshaft Explorer (no spot on the
 * graph) picks nothing and the click goes through to the server; a right click on Umber Collector is swallowed (no
 * container click) and the route's picked commission is Umber Collector; a LEFT click on it picks nothing new.
 */
final class GlaciteCommMenuA1114Cases {

    private static final String MQ = "miningqol.MiningQolConfig";
    private static final String ROUTE = "miningqol.CommissionRoute";
    private static final String MAP = "miningqol.MinesAreaMap";
    private static final String STATE = "miningqol.MiningState";

    private static final String GRAPH = "{\"200\":{\"Position\":\"302.0:181.0:300.0\",\"Neighbours\":{\"201\":28.0}},"
            + "\"201\":{\"Position\":\"330.0:181.0:300.0\",\"Name\":\"§6Umber\",\"Tags\":[\"grind_ores\"],"
            + "\"Neighbours\":{\"200\":28.0}}}";

    private GlaciteCommMenuA1114Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1114-glacitecomm-a-menu-rightclick", GlaciteCommMenuA1114Cases::rightClick);
    }

    static void rightClick(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(MQ, "CommissionHud", true).set(MQ, "CommissionRoute", true);
            c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Glacite Tunnels", "Purse: 0");
            c.hx().tab(List.of("Area: Dwarven Mines", "Profile: Test", "", "Commissions:", " Umber Collector: 10%",
                    " Mineshaft Explorer: 0%", ""), null, null);
            c.ctx().runOnClient(mc -> Mod.staticCall(MAP, "useGraphJsonForTest", "GLACITE_TUNNELS", GRAPH));
            c.waitUntil("Tunnels and both commissions read", mc -> "GLACITE_TUNNELS".equals(Mod.staticCall(STATE, "island"))
                    && (Boolean) Mod.staticCall(STATE, "commissionsKnown")
                    && ((List<?>) Mod.staticCall(STATE, "commissions")).size() == 2
                    && (Boolean) Mod.staticCall(MAP, "loaded"), 100);
            c.ctx().runOnClient(mc -> Mod.staticCall(ROUTE, "clearSelectedForTest"));
            Map<Integer, JsonObject> slots = new LinkedHashMap<>();
            slots.put(11, FlipCroesusCases.item("minecraft:writable_book", "§6Umber Collector", "§7Mine Umber.", "",
                    "§7Progress: §e10%"));
            slots.put(12, FlipCroesusCases.item("minecraft:writable_book", "§6Mineshaft Explorer", "§7Explore.", "",
                    "§7Progress: §e0%"));
            MenuKit.show(c, FlipCroesusCases.menu("Commissions", 4, slots, Map.of()));
            MenuKit.awaitScreen(c, "Commissions", 100);
            c.ctx().waitTicks(5);
            // Mineshaft Explorer: no spot, so nothing picked and the click goes on.
            TermHoldCases.pointAt(c, 12, false);
            c.ctx().waitTicks(3);
            c.ctx().getInput().pressMouse(dev.testkit.compat.McKeys.mouse(1));
            c.ctx().waitTicks(5);
            String s1 = c.onClient(mc -> (String) Mod.staticCall(ROUTE, "selected"));
            c.check(s1 == null, "right click on Mineshaft Explorer picked " + s1);
            int through = c.events("container.click").size();
            c.check(through > 0, "the click on a non-routable commission was swallowed (no container click)");
            // Umber Collector: picked, click swallowed.
            TermHoldCases.pointAt(c, 11, false);
            c.ctx().waitTicks(3);
            c.ctx().getInput().pressMouse(dev.testkit.compat.McKeys.mouse(1));
            c.ctx().waitTicks(5);
            String s2 = c.onClient(mc -> (String) Mod.staticCall(ROUTE, "selected"));
            c.check("Umber Collector".equals(s2), "right click on Umber Collector picked " + s2);
            c.check(c.events("container.click").size() == through, "the routing click also reached the server");
            c.waitUntil("a route to the Umber node", mc -> (Integer) Mod.staticCall(ROUTE, "pointCount") > 0
                    || "Umber".equals(Mod.staticCall(ROUTE, "currentTargetArea")), 60);
            c.note("Mineshaft Explorer right click: none picked, click through; Umber Collector: picked, swallowed, target "
                    + c.onClient(mc -> Mod.staticCall(ROUTE, "currentTargetArea")));
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.staticCall(ROUTE, "clearSelectedForTest");
                Mod.staticCall(MAP, "clearTestGraphForTest");
            });
            MenuKit.reset(c);
        }
    }
}

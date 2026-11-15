package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod branch glacitecomm-a-1114: the existing commission features in the Glacite Tunnels. Own arena as in
 * {@link HxCommissionRoute1103Cases} (stone floor y 180, x 296..386, z 296..304) holding two test graphs at once: the
 * Dwarven Mines' (ids 0-6, areas Royal Mines / Lava Springs) and the Tunnels' (ids 200-205: 200 start at x 302, 201 at 330,
 * 202 "Tungsten" at 365, 203 "Umber" at 380, 205 a second Umber at (300, z 320), all tagged grind_ores). The mod picks the
 * graph by the island he stands on; the sidebar's area line switches island (Glacite Tunnels vs Royal Mines).
 * <ul>
 *   <li>1114-glacitecomm-a-names: the tab's Glacite commissions are read, each known to CommissionKinds with powder
 *       Glacite (area Glacite Tunnels), a Dwarven one with Mithril, an unknown one with none.</li>
 *   <li>1114-glacitecomm-a-route: in the Tunnels the line goes to a node of the TUNNELS graph (ids 200+), in the Mines to a
 *       node of the Dwarven graph, never the other; no line without a pick or the Auto switch; Auto Route picks the first
 *       open routable commission (Glacite Collector and Mineshaft Explorer are skipped); select() picks one.</li>
 *   <li>1114-glacitecomm-a-waypoints: the Tunnels' waypoints (gem spots, Umber from the graph, the Base Camp Emissary) draw
 *       in the Tunnels and are absent in the Mines (and the Mines' areas and emissaries absent in the Tunnels).</li>
 * </ul>
 */
final class HxGlaciteCommA1114Cases {

    private static final String CFG = "miningqol.MiningQolConfig";
    private static final String STATE = "miningqol.MiningState";
    private static final String MAP = "miningqol.MinesAreaMap";
    private static final String ROUTE = "miningqol.CommissionRoute";
    private static final String HUD = "miningqol.CommissionHud";
    private static final String KINDS = "miningqol.CommissionKinds";

    private static final int FY = 181;
    private static final int Z = 300;

    private HxGlaciteCommA1114Cases() {
    }

    static void register(Session s) {
        s.test("1114-glacitecomm-a-names", HxGlaciteCommA1114Cases::names);
        s.test("1114-glacitecomm-a-route", HxGlaciteCommA1114Cases::route);
        s.test("1114-glacitecomm-a-waypoints", HxGlaciteCommA1114Cases::waypoints);
    }

    // ---- shared ---------------------------------------------------------------------------------------------------

    /** Sidebar area and tab commissions of the Dwarven Mines island; {@code tunnels} puts him in the Glacite Tunnels. */
    private static void place(Session c, boolean tunnels, String... coms) {
        String area = tunnels ? "Glacite Tunnels" : "Royal Mines";
        c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ " + area, "Purse: 0");
        List<String> tab = new ArrayList<>();
        tab.add("Area: Dwarven Mines");
        tab.add("Profile: Test");
        tab.add("");
        tab.add("Commissions:");
        for (String com : coms) {
            tab.add(" " + com);
        }
        tab.add("");
        c.hx().tab(tab, null, null);
        String island = tunnels ? "GLACITE_TUNNELS" : "DWARVEN_MINES";
        c.waitUntil(island + ", area " + area, mc -> island.equals(Mod.staticCall(STATE, "island"))
                && area.equals(Mod.staticCall(STATE, "area")), 100);
        int n = coms.length;
        c.waitUntil(n + " commission(s) read", mc -> (Boolean) Mod.staticCall(STATE, "commissionsKnown")
                && ((List<?>) Mod.staticCall(STATE, "commissions")).size() == n
                && String.valueOf(((List<?>) Mod.staticCall(STATE, "commissions")).get(0)).contains(
                coms[0].substring(0, coms[0].indexOf(':'))), 100);
    }

    private static String node(int id, int x, int z, String name, String tags, String neighbours) {
        String extra = name == null ? "" : ",\"Name\":\"" + name + "\",\"Tags\":[\"" + tags + "\"]";
        return "\"" + id + "\":{\"Position\":\"" + x + ".0:" + FY + ".0:" + z + ".0\"" + extra
                + ",\"Neighbours\":{" + neighbours + "}}";
    }

    private static final String DWARVEN_JSON = "{" + node(0, 300, Z, "Royal Mines", "area", "\"1\":2.0")
            + "," + node(1, 302, Z, null, null, "\"0\":2.0,\"2\":28.0")
            + "," + node(2, 330, Z, null, null, "\"1\":28.0,\"3\":35.0")
            + "," + node(3, 365, Z, null, null, "\"2\":35.0,\"4\":15.0")
            + "," + node(4, 380, Z, "Lava Springs", "area", "\"3\":15.0") + "}";

    private static final String TUNNELS_JSON = "{" + node(200, 302, Z, null, null, "\"201\":28.0,\"205\":20.0")
            + "," + node(201, 330, Z, null, null, "\"200\":28.0,\"202\":35.0")
            + "," + node(202, 365, Z, "§7Tungsten", "grind_ores", "\"201\":35.0,\"203\":15.0")
            + "," + node(203, 380, Z, "§6Umber", "grind_ores", "\"202\":15.0")
            + "," + node(205, 300, 320, "§6Umber", "grind_ores", "\"200\":20.0") + "}";

    private static Vec3 home = null;

    private static void arena(Session c) {
        if (home == null) {
            home = c.onClient(mc -> mc.player.position());
        }
        c.server().command("forceload add 296 296 386 324");
        c.ctx().waitTicks(20);
        c.server().command("fill 296 180 296 386 180 324 minecraft:stone");
        c.server().command("fill 296 181 296 386 186 324 minecraft:air");
        c.server().command("tp @a 302.5 181 300.5 -90 0");
        c.waitUntil("standing on the arena floor", mc -> mc.player != null
                && mc.player.position().distanceTo(new Vec3(302.5, 181, 300.5)) < 0.6
                && mc.level.getBlockState(new BlockPos(384, 180, 300)).isSolid(), 800);
        c.ctx().waitTicks(10);
        c.ctx().runOnClient(mc -> {
            Mod.staticCall(MAP, "useGraphJsonForTest", "DWARVEN_MINES", DWARVEN_JSON);
            Mod.staticCall(MAP, "useGraphJsonForTest", "GLACITE_TUNNELS", TUNNELS_JSON);
        });
        c.waitUntil("the test area map in use", mc -> (Boolean) Mod.staticCall(MAP, "loaded"), 20);
    }

    private static void cleanup(Session c) {
        c.ctx().runOnClient(mc -> Mod.staticCall(MAP, "clearTestGraphForTest"));
        if (home != null) {
            Vec3 h = home;
            c.server().command(String.format(java.util.Locale.ROOT, "tp @a %.3f %.3f %.3f", h.x, h.y, h.z));
            c.waitUntil("back where he stood", mc -> mc.player.position().distanceTo(h) < 1.0, 100);
            home = null;
        }
        c.server().command("fill 296 180 296 386 186 324 minecraft:air");
        c.server().command("forceload remove 296 296 386 324");
        hub(c);
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> ids(Session c) {
        return c.onClient(mc -> new ArrayList<>((List<Integer>) Mod.staticCall(ROUTE, "pathNodeIds")));
    }

    private static int points(Session c) {
        return c.onClient(mc -> (Integer) Mod.staticCall(ROUTE, "pointCount"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> wps(Session c) {
        return c.onClient(mc -> new ArrayList<>((List<String>) Mod.staticCall(HUD, "waypointNames")));
    }

    private static int lastId(Session c, String what) {
        List<Integer> p = ids(c);
        c.check(!p.isEmpty(), what + ": no path");
        return p.isEmpty() ? -1 : p.get(p.size() - 1);
    }

    // ---- 1114-glacitecomm-a-names -------------------------------------------------------------------------------------

    private static void names(Session c) {
        String[] glacite = {"Aquamarine Gemstone Collector", "Citrine Gemstone Collector", "Onyx Gemstone Collector",
                "Peridot Gemstone Collector", "Glacite Collector", "Umber Collector", "Tungsten Collector",
                "Mineshaft Explorer", "Corpse Looter", "Scrap Collector", "Maniac Slayer"};
        try (Settings set = new Settings(c).with(CFG, "CommissionHud", true)) {
            for (String n : glacite) {
                String info = c.onClient(mc -> String.valueOf(Mod.staticCall(KINDS, "of", n)));
                c.check(info.contains("area=Glacite Tunnels") && info.contains("powder=Glacite"), n + " -> " + info);
            }
            // Tolerant spellings.
            for (String n : new String[]{"umber collector", "Peridot Collector", "  Corpse Looter "}) {
                String info = c.onClient(mc -> String.valueOf(Mod.staticCall(KINDS, "of", n)));
                c.check(info.contains("powder=Glacite"), "tolerant \"" + n + "\" -> " + info);
            }
            String dw = c.onClient(mc -> String.valueOf(Mod.staticCall(KINDS, "of", "Lava Springs Mithril")));
            c.check(dw.contains("area=Dwarven Mines") && dw.contains("powder=Mithril"), "Dwarven -> " + dw);
            c.check(c.onClient(mc -> Mod.staticCall(KINDS, "of", "Goblin Slayer")) == null, "Goblin Slayer is not a known kind");
            // The real tab widget: all four Mineshaft and the collectors are read as commissions in the Tunnels.
            place(c, true, "Umber Collector: 12%", "Mineshaft Explorer: 0%", "Corpse Looter: 50%", "Maniac Slayer: DONE");
            String read = c.onClient(mc -> String.valueOf(Mod.staticCall(STATE, "commissions")));
            for (String n : new String[]{"Umber Collector", "Mineshaft Explorer", "Corpse Looter", "Maniac Slayer"}) {
                c.check(read.contains(n), "tab commission not read: " + n + " in " + read);
            }
            c.note("11 Glacite names -> area Glacite Tunnels, powder Glacite; tolerant spellings; Dwarven -> Mithril; tab read " + read);
        } finally {
            hub(c);
        }
    }

    // ---- 1114-glacitecomm-a-route -------------------------------------------------------------------------------------

    private static void route(Session c) {
        try (Settings set = new Settings(c).with(CFG, "CommissionHud", true).with(CFG, "CommissionRoute", true)
                .with(CFG, "CommissionRouteEmissary", false).with(CFG, "CommissionAutoRoute", false)) {
            arena(c);
            // Tunnels, Auto off, nothing picked: no line (the Tunnels route is picked or Auto).
            place(c, true, "Glacite Collector: 5%", "Mineshaft Explorer: 0%", "Umber Collector: 10%", "Tungsten Collector: 0%");
            c.ctx().waitTicks(30);
            c.check(points(c) == 0, "a Tunnels line with Auto off and nothing picked");
            c.check(c.onClient(mc -> Mod.staticCall(ROUTE, "currentTargetArea")) == null, "a Tunnels target with Auto off");
            // select(): a Mineshaft commission and the Glacite Collector (no graph nodes) cannot be picked; Umber can.
            c.check(!(Boolean) c.onClient(mc -> Mod.staticCall(ROUTE, "select", "Mineshaft Explorer")), "picked Mineshaft Explorer");
            c.check(!(Boolean) c.onClient(mc -> Mod.staticCall(ROUTE, "select", "Glacite Collector")), "picked Glacite Collector");
            c.check((Boolean) c.onClient(mc -> Mod.staticCall(ROUTE, "select", "Umber Collector")), "could not pick Umber Collector");
            c.waitUntil("the picked Umber route", mc -> (Integer) Mod.staticCall(ROUTE, "pointCount") > 0, 100);
            List<Integer> picked = ids(c);
            c.check(picked.stream().allMatch(i -> i >= 200), "the Tunnels line uses a Dwarven node: " + picked);
            int umberEnd = lastId(c, "picked Umber");
            c.check(umberEnd == 203 || umberEnd == 205, "picked Umber ends at node " + umberEnd + ", wanted an Umber node (203/205)");
            c.check("Umber".equals(c.onClient(mc -> Mod.staticCall(ROUTE, "currentTargetArea"))), "target is not Umber");
            // Auto on, nothing picked: the first open routable commission is Umber, not Glacite / Mineshaft Explorer.
            c.ctx().runOnClient(mc -> Mod.staticCall(ROUTE, "clearSelectedForTest"));
            c.ctx().runOnClient(mc -> Mod.set(CFG, "setCommissionAutoRoute", true));
            waitRoute(c);
            int autoEnd = lastId(c, "auto");
            c.check(autoEnd == 203 || autoEnd == 205, "Auto Route ended at " + autoEnd + ", wanted an Umber node");
            // Tungsten first in the list: Auto picks it (node 202), still the Tunnels graph.
            place(c, true, "Mineshaft Explorer: 0%", "Tungsten Collector: 0%", "Umber Collector: 10%");
            c.waitUntil("a Tungsten target", mc -> "Tungsten".equals(Mod.staticCall(ROUTE, "currentTargetArea")), 100);
            c.waitUntil("the Tungsten line", mc -> (Integer) Mod.staticCall(ROUTE, "pointCount") > 0, 60);
            c.check(lastId(c, "tungsten") == 202, "Tungsten route ends at " + lastId(c, "tungsten") + ", wanted 202");
            // Back to the Dwarven Mines: the Dwarven graph, an area commission, a node below 200.
            place(c, false, "Lava Springs Mithril: 10%", "Umber Collector: 10%");
            c.waitUntil("the Mines line", mc -> "Lava Springs".equals(Mod.staticCall(ROUTE, "currentTargetArea"))
                    && (Integer) Mod.staticCall(ROUTE, "pointCount") > 0, 100);
            List<Integer> mines = ids(c);
            c.check(mines.stream().allMatch(i -> i < 200) && !mines.isEmpty(), "the Mines line uses a Tunnels node: " + mines);
            c.check(lastId(c, "mines") == 3 || lastId(c, "mines") == 4, "the Mines line ends at " + lastId(c, "mines"));
            // The Umber collector alone in the Mines has no route (Tunnels commission, Dwarven graph).
            place(c, false, "Umber Collector: 10%");
            c.ctx().waitTicks(30);
            c.check(points(c) == 0, "a line for a Tunnels collector in the Mines");
            c.note("Tunnels: no line without pick/Auto; pick -> Umber (node " + umberEnd + "); Auto -> " + autoEnd
                    + "; Tungsten first -> 202; Mines -> Dwarven node " + mines + "; Umber in the Mines -> none");
        } finally {
            c.ctx().runOnClient(mc -> Mod.staticCall(ROUTE, "clearSelectedForTest"));
            cleanup(c);
        }
    }

    private static void waitRoute(Session c) {
        c.waitUntil("a route", mc -> (Integer) Mod.staticCall(ROUTE, "pointCount") > 0, 100);
    }

    // ---- 1114-glacitecomm-a-waypoints ---------------------------------------------------------------------------------

    private static void waypoints(Session c) {
        try (Settings set = new Settings(c).with(CFG, "CommissionHud", true).with(CFG, "CommissionWaypoints", true)
                .with(CFG, "CommissionEmissaries", true)) {
            arena(c);
            place(c, true, "Umber Collector: 10%", "Peridot Gemstone Collector: 20%", "Glacite Collector: 5%", "Maniac Slayer: 0%");
            c.waitUntil("the Umber labels", mc -> wpsNow().stream().anyMatch(w -> w.startsWith("Umber@")), 100);
            List<String> t = wps(c);
            c.note("Tunnels waypoints " + t);
            c.check(t.contains("Umber@380,181,300") && t.contains("Umber@300,181,320"), "Umber spots from the graph missing: " + t);
            c.check(t.stream().anyMatch(w -> w.equals("Peridot@66,144,284")), "the Peridot gem spots are missing: " + t);
            c.check(t.stream().noneMatch(w -> w.startsWith("Glacite@") || w.startsWith("Maniac")),
                    "a label for a commission with no spot: " + t);
            c.check(t.stream().noneMatch(w -> w.startsWith("Tungsten")), "Tungsten label without that commission: " + t);
            int frames = c.onClient(mc -> (Integer) Mod.staticCall(HUD, "labelFrames"));
            c.waitUntil("the Tunnels labels drawn", mc -> (Integer) Mod.staticCall(HUD, "labelFrames") > frames + 3, 100);
            // DONE: only the Base Camp Emissary in the Tunnels.
            place(c, true, "Umber Collector: DONE");
            c.waitUntil("the emissary label", mc -> wpsNow().stream().anyMatch(w -> w.startsWith("Emissary@")), 100);
            List<String> em = wps(c);
            c.check(em.equals(List.of("Emissary@2,122,236")), "Tunnels emissary labels " + em + ", wanted only the Base Camp one");
            // The Dwarven Mines: none of the Tunnels' labels, the area label and the seven emissaries instead.
            place(c, false, "Lava Springs Mithril: 10%", "Umber Collector: 10%", "Peridot Gemstone Collector: 5%");
            c.waitUntil("the Mines label", mc -> wpsNow().stream().anyMatch(w -> w.startsWith("Lava Springs Mithril@")), 100);
            List<String> m = wps(c);
            c.check(m.stream().noneMatch(w -> w.startsWith("Umber") || w.startsWith("Peridot")),
                    "Tunnels labels in the Dwarven Mines: " + m);
            place(c, false, "Lava Springs Mithril: DONE");
            c.waitUntil("the Mines emissaries", mc -> wpsNow().stream().filter(w -> w.startsWith("Emissary@")).count() == 7, 100);
            c.check(wps(c).stream().noneMatch(w -> w.equals("Emissary@2,122,236")), "the Base Camp emissary in the Mines");
            c.note("Tunnels: Umber x2 + Peridot spots, nothing for Glacite/Maniac, one Base Camp emissary; Mines: none of those, 7 emissaries");
        } finally {
            cleanup(c);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> wpsNow() {
        return (List<String>) Mod.staticCall(HUD, "waypointNames");
    }
}

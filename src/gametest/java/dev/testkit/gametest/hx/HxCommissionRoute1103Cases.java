package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod branch comroute-1103, Commission Route (killer560, 2026-11-03: "draw a pathfinding line to wherever i need to go to do
 * a comission then once i am there there is no pathfinding line"; "one for etherwarp one for aote one for walking").
 *
 * <p>Own arena: a stone floor at y 180, x 296..386, z 296..304 (feet y 181), with a test graph (SkyHanni format) on it:
 * node 0 "Royal Mines" area at x 300, 1 at 302 (start), 2 at 330, 3 "Lava Springs" side at 365, 4 "Lava Springs" area at
 * 380, and a decoy 6 at (330, z 320) joined to 1 and 3 by a longer way. Areas by MinesAreaMap's rule: 0-2 and 6 Royal
 * Mines, 3-4 Lava Springs. The shortest path from 1 to Lava Springs is 1, 2, 3.
 * <ul>
 *   <li>1103-mining-route-etherwarp: hops land on solid blocks with two air above, each within 57 of the stand before it
 *       and in plain sight of it (vanilla clip), the last in Lava Springs; the target blocks are drawn; the line goes once
 *       he stands in Lava Springs.</li>
 *   <li>1103-mining-route-walk: the line's points are the graph path's node centers, 1, 2, 3; none for an area-less
 *       commission; the next commission with an area is used instead.</li>
 *   <li>1103-mining-route-aote: each landing is at most 8 blocks (plus a block's slack) from the stand before it, feet and
 *       head free, the last in Lava Springs.</li>
 *   <li>1103-mining-route-persist: switch, color and mode survive a save and reload.</li>
 * </ul>
 * Every case also checks the client sent no use or rotation packets beyond what it did before (information only).
 */
final class HxCommissionRoute1103Cases {

    private static final String CFG = "miningqol.MiningQolConfig";
    private static final String STATE = "miningqol.MiningState";
    private static final String MAP = "miningqol.MinesAreaMap";
    private static final String ROUTE = "miningqol.CommissionRoute";
    private static final String MODE = "miningqol.MiningQolConfig$CommissionRouteMode";

    private static final int FY = 181;
    private static final int Z = 300;

    private HxCommissionRoute1103Cases() {
    }

    static void register(Session s) {
        s.test("1103-mining-route-etherwarp", HxCommissionRoute1103Cases::etherwarp);
        s.test("1103-mining-route-walk", HxCommissionRoute1103Cases::walk);
        s.test("1103-mining-route-aote", HxCommissionRoute1103Cases::aote);
        s.test("1103-mining-route-persist", HxCommissionRoute1103Cases::persist);
    }

    // ---- shared ---------------------------------------------------------------------------------------------------

    private static void mines(Session c, String area, String... coms) {
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
        c.waitUntil("Dwarven Mines, area " + area, mc -> "DWARVEN_MINES".equals(Mod.staticCall(STATE, "island"))
                && area.equals(Mod.staticCall(STATE, "area")), 100);
        int n = coms.length;
        c.waitUntil(n + " commission(s) read", mc -> (Boolean) Mod.staticCall(STATE, "commissionsKnown")
                && ((List<?>) Mod.staticCall(STATE, "commissions")).size() == n
                && String.valueOf(((List<?>) Mod.staticCall(STATE, "commissions")).get(0)).contains(
                coms[0].substring(0, coms[0].indexOf(':'))), 100);
    }

    private static String node(int id, int x, int z, String area, String neighbours) {
        String tags = area == null ? "" : ",\"Name\":\"" + area + "\",\"Tags\":[\"area\"]";
        return "\"" + id + "\":{\"Position\":\"" + x + ".0:" + FY + ".0:" + z + ".0\"" + tags
                + ",\"Neighbours\":{" + neighbours + "}}";
    }

    /** Where he stood before the arena: put back there after, so the next case does not start falling. */
    private static Vec3 home = null;

    private static void arena(Session c) {
        if (home == null) {
            home = c.onClient(mc -> mc.player.position());
        }
        // fill needs the chunks loaded on the server; forceload them rather than standing there and falling (a fall onto
        // a floor placed under him drew Grim Simulation flags, b4).
        c.server().command("forceload add 296 296 386 304");
        c.ctx().waitTicks(20);
        c.server().command("fill 296 180 296 386 180 304 minecraft:stone");
        c.server().command("fill 296 181 296 386 186 304 minecraft:air");
        c.server().command("tp @a 302.5 181 300.5 -90 0");
        c.waitUntil("standing on the arena floor", mc -> mc.player != null
                && mc.player.position().distanceTo(new Vec3(302.5, 181, 300.5)) < 0.6
                && mc.level.getBlockState(new BlockPos(384, 180, 300)).isSolid()
                && mc.level.getBlockState(new BlockPos(330, 180, 300)).isSolid(), 800);
        c.ctx().waitTicks(10);
        String json = "{" + node(0, 300, Z, "Royal Mines", "\"1\":2.0")
                + "," + node(1, 302, Z, null, "\"0\":2.0,\"2\":28.0,\"6\":40.0")
                + "," + node(2, 330, Z, null, "\"1\":28.0,\"3\":35.0")
                + "," + node(3, 365, Z, null, "\"2\":35.0,\"4\":15.0,\"6\":40.0")
                + "," + node(4, 380, Z, "Lava Springs", "\"3\":15.0")
                + "," + node(6, 330, 320, null, "\"1\":40.0,\"3\":40.0") + "}";
        c.ctx().runOnClient(mc -> Mod.staticCall(MAP, "useGraphJsonForTest", json));
        c.waitUntil("the test area map in use", mc -> (Boolean) Mod.staticCall(MAP, "loaded"), 20);
        c.check("Lava Springs".equals(c.onClient(mc -> Mod.staticCall(MAP, "areaAt", 365, FY, Z))),
                "node 3's block is not Lava Springs");
        c.check("Royal Mines".equals(c.onClient(mc -> Mod.staticCall(MAP, "areaAt", 330, FY, Z))),
                "node 2's block is not Royal Mines");
    }

    private static void cleanup(Session c) {
        c.ctx().runOnClient(mc -> Mod.staticCall(MAP, "clearTestGraphForTest"));
        if (home != null) {
            Vec3 h = home;
            c.server().command(String.format(java.util.Locale.ROOT, "tp @a %.3f %.3f %.3f", h.x, h.y, h.z));
            c.waitUntil("back where he stood", mc -> mc.player.position().distanceTo(h) < 1.0, 100);
            home = null;
        }
        c.server().command("fill 296 180 296 386 186 304 minecraft:air");
        c.server().command("forceload remove 296 296 386 304");
        hub(c);
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Session c, String method) {
        return c.onClient(mc -> new ArrayList<>((List<String>) Mod.staticCall(ROUTE, method)));
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> ids(Session c) {
        return c.onClient(mc -> new ArrayList<>((List<Integer>) Mod.staticCall(ROUTE, "pathNodeIds")));
    }

    private static int count(Session c, String method) {
        return c.onClient(mc -> (Integer) Mod.staticCall(ROUTE, method));
    }

    private static int[] xyz(String s) {
        String[] p = s.split(",");
        return new int[]{(int) Math.floor(Double.parseDouble(p[0])), (int) Math.floor(Double.parseDouble(p[1])),
                (int) Math.floor(Double.parseDouble(p[2]))};
    }

    private static double[] dxyz(String s) {
        String[] p = s.split(",");
        return new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2])};
    }

    private static int sentUses(Session c) {
        return c.events("use.item").size() + c.events("use.block").size();
    }

    private static void waitRoute(Session c, String what) {
        c.waitUntil(what, mc -> (Integer) Mod.staticCall(ROUTE, "pointCount") > 0, 100);
    }

    private static void arrival(Session c) {
        int framesBefore = count(c, "frames");
        c.ctx().waitTicks(10);
        c.check(count(c, "frames") > framesBefore, "the route line was never drawn (frames did not move)");
        c.server().command("tp @a 366.5 181 300.5");
        c.waitUntil("the line gone in Lava Springs", mc -> (Integer) Mod.staticCall(ROUTE, "pointCount") == 0
                && mc.player.position().x > 366, 100);
        c.server().command("tp @a 302.5 181 300.5");
        waitRoute(c, "the line back after leaving");
        c.note("in Lava Springs (by the area map, sidebar still Royal Mines) the line is gone; back at the start it returns");
    }

    /** Checks every etherwarp hop from the stand before it; returns the hops. */
    private static List<String> checkEther(Session c, String what) {
        List<String> hops = list(c, "hops");
        List<String> pts = list(c, "linePoints");
        c.note(what + ": etherwarp hops " + hops + ", line " + pts);
        c.check(!hops.isEmpty(), what + ": no etherwarp target blocks");
        double[] prev = {302.5, 181.0, 300.5};
        int next = 0;
        for (String pt : pts) {
            double[] q = dxyz(pt);
            if (next < hops.size()) {
                int[] b = xyz(hops.get(next));
                if (Math.abs(q[0] - (b[0] + 0.5)) < 0.01 && Math.abs(q[1] - (b[1] + 1.0)) < 0.01
                        && Math.abs(q[2] - (b[2] + 0.5)) < 0.01) {
                    checkHop(c, what, hops.get(next), prev);
                    next++;
                }
            }
            prev = q;
        }
        c.check(next == hops.size(), what + ": hops not on the line: " + hops + " / " + pts);
        double[] last = dxyz(pts.get(pts.size() - 1));
        String lastArea = c.onClient(mc -> (String) Mod.staticCall(MAP, "areaAt", (int) Math.floor(last[0]),
                (int) Math.floor(last[1]), (int) Math.floor(last[2])));
        c.check("Lava Springs".equals(lastArea), what + ": the route ends in " + lastArea + ", not Lava Springs");
        return hops;
    }

    private static void checkHop(Session c, String what, String h, double[] stand) {
        int[] b = xyz(h);
        BlockPos p = new BlockPos(b[0], b[1], b[2]);
        Vec3 eye = new Vec3(stand[0], stand[1] + 1.27, stand[2]);
        boolean[] ok = c.onClient(mc -> {
            // In sight: some point of its top face, a vanilla clip from the eye ends on it (a 5x5 grid).
            boolean seen = false;
            for (int i = 0; i < 5 && !seen; i++) {
                for (int k = 0; k < 5 && !seen; k++) {
                    Vec3 aim = new Vec3(p.getX() + 0.1 + 0.2 * i, p.getY() + 0.999, p.getZ() + 0.1 + 0.2 * k);
                    BlockHitResult hit = mc.level.clip(new ClipContext(eye, aim, ClipContext.Block.COLLIDER,
                            ClipContext.Fluid.NONE, mc.player));
                    seen = hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(p);
                }
            }
            return new boolean[]{mc.level.getBlockState(p).isSolid(), mc.level.getBlockState(p.above()).isAir(),
                    mc.level.getBlockState(p.above(2)).isAir(), seen};
        });
        double d = eye.distanceTo(Vec3.atCenterOf(p));
        c.check(ok[0] && ok[1] && ok[2], what + ": hop " + h + " is not solid with two air above");
        c.check(ok[3], what + ": hop " + h + " is not in sight of the stand before it");
        c.check(d <= 57.0, what + ": hop " + h + " is " + d + " blocks from the stand before it");
    }

    // ---- 1103-mining-route-etherwarp --------------------------------------------------------------------------------

    private static void etherwarp(Session c) {
        Object mode = c.onClient(mc -> Mod.enumValue(MODE, "ETHERWARP"));
        int usesBefore = sentUses(c);
        try (Settings set = new Settings(c).with(CFG, "CommissionHud", true).with(CFG, "CommissionRoute", true)
                .with(CFG, "CommissionRouteMode", mode)) {
            arena(c);
            mines(c, "Royal Mines", "Lava Springs Mithril: 10%");
            waitRoute(c, "an etherwarp route");
            List<Integer> path = ids(c);
            c.check(path.equals(List.of(1, 2, 3)), "graph path " + path + ", wanted [1, 2, 3]");
            List<String> hops = checkEther(c, "open floor");
            c.check(hops.equals(List.of("330,180,300", "365,180,300")), "open floor hops " + hops
                    + ", wanted [330,180,300, 365,180,300] (57-block range: 302 -> 365 is too far, 302 -> 330 -> 365 is not)");
            // A wall between node 2 and node 3: no warp through it.
            c.server().command("fill 347 181 296 347 185 304 minecraft:stone");
            c.waitUntil("the wall on the client", mc -> mc.level.getBlockState(new BlockPos(347, 182, 300)).isSolid(), 100);
            Object walkMode = c.onClient(mc -> Mod.enumValue(MODE, "WALK"));
            Object etherMode = c.onClient(mc -> Mod.enumValue(MODE, "ETHERWARP"));
            c.ctx().runOnClient(mc -> Mod.set(CFG, "setCommissionRouteMode", walkMode));
            c.ctx().waitTicks(8);
            c.ctx().runOnClient(mc -> Mod.set(CFG, "setCommissionRouteMode", etherMode));
            c.ctx().waitTicks(8);
            waitRoute(c, "an etherwarp route with the wall");
            List<String> walled = checkEther(c, "wall at x 347");
            c.check(!walled.contains("365,180,300") || walled.size() > 2, "warped through the wall: " + walled);
            c.server().command("fill 347 181 296 347 185 304 minecraft:air");
            arrival(c);
        } finally {
            cleanup(c);
        }
        c.check(sentUses(c) == usesBefore, "the route sent use packets");
    }

    // ---- 1103-mining-route-walk -------------------------------------------------------------------------------------

    private static void walk(Session c) {
        Object mode = c.onClient(mc -> Mod.enumValue(MODE, "WALK"));
        try (Settings set = new Settings(c).with(CFG, "CommissionHud", true).with(CFG, "CommissionRoute", true)
                .with(CFG, "CommissionRouteMode", mode)) {
            arena(c);
            mines(c, "Royal Mines", "Lava Springs Mithril: 10%");
            waitRoute(c, "a walking route");
            c.check(ids(c).equals(List.of(1, 2, 3)), "graph path " + ids(c));
            List<String> pts = list(c, "linePoints");
            c.check(pts.equals(List.of("302.5,181.5,300.5", "330.5,181.5,300.5", "365.5,181.5,300.5")),
                    "walk line points " + pts);
            c.check(list(c, "hops").isEmpty(), "walk mode has hops");
            // Area-less commission: no line.
            mines(c, "Royal Mines", "Mithril Miner: 10%", "Goblin Slayer: 5%");
            c.waitUntil("no line for Mithril Miner / Goblin Slayer", mc -> (Integer) Mod.staticCall(ROUTE, "pointCount") == 0
                    && Mod.staticCall(ROUTE, "currentTargetArea") == null, 60);
            // The next one with an area is used; a DONE one is skipped.
            mines(c, "Royal Mines", "Mithril Miner: 10%", "Upper Mines Titanium: DONE", "Lava Springs Titanium: 0%");
            waitRoute(c, "a line to the next commission that has an area");
            c.check("Lava Springs".equals(c.onClient(mc -> Mod.staticCall(ROUTE, "currentTargetArea"))),
                    "target " + c.onClient(mc -> Mod.staticCall(ROUTE, "currentTargetArea")));
            c.note("walk " + pts + "; none for Mithril Miner + Goblin Slayer; next area commission picked");
            arrival(c);
            // Off: no line.
            c.ctx().runOnClient(mc -> Mod.set(CFG, "setCommissionRoute", false));
            c.waitUntil("no line when off", mc -> (Integer) Mod.staticCall(ROUTE, "pointCount") == 0, 40);
        } finally {
            cleanup(c);
        }
    }

    // ---- 1103-mining-route-aote -------------------------------------------------------------------------------------

    private static void aote(Session c) {
        Object mode = c.onClient(mc -> Mod.enumValue(MODE, "AOTE"));
        try (Settings set = new Settings(c).with(CFG, "CommissionHud", true).with(CFG, "CommissionRoute", true)
                .with(CFG, "CommissionRouteMode", mode)) {
            arena(c);
            mines(c, "Royal Mines", "Lava Springs Mithril: 10%");
            waitRoute(c, "an AOTE route");
            List<String> hops = list(c, "hops");
            List<String> pts = list(c, "linePoints");
            c.note("AOTE " + hops.size() + " landings: " + hops);
            c.check(hops.size() >= 6, "only " + hops.size() + " landings for ~63 blocks");
            c.check(hops.size() <= 12, hops.size() + " landings for ~63 blocks (it bounced)");
            double[] stand = {302.5, 181.0, 300.5};
            for (String pt : pts) {
                double[] q = dxyz(pt);
                double d = Math.sqrt(Math.pow(q[0] - stand[0], 2) + Math.pow(q[1] - stand[1], 2) + Math.pow(q[2] - stand[2], 2));
                c.check(d <= 9.0, "a hop of " + d + " blocks to " + pt);
                BlockPos f = BlockPos.containing(q[0], q[1], q[2]);
                boolean free = c.onClient(mc -> mc.level.getBlockState(f).isAir() && mc.level.getBlockState(f.above()).isAir());
                c.check(free, "landing " + pt + " has no room");
                stand = q;
            }
            double[] last = dxyz(pts.get(pts.size() - 1));
            String lastArea = c.onClient(mc -> (String) Mod.staticCall(MAP, "areaAt", (int) Math.floor(last[0]),
                    (int) Math.floor(last[1]), (int) Math.floor(last[2])));
            c.check("Lava Springs".equals(lastArea), "the last landing is in " + lastArea);
            arrival(c);
        } finally {
            cleanup(c);
        }
    }

    // ---- 1103-mining-route-persist ----------------------------------------------------------------------------------

    private static void persist(Session c) {
        Object[] before = c.onClient(mc -> new Object[]{Mod.get(CFG, "isCommissionRoute"), Mod.get(CFG, "getCommissionRouteColor"),
                Mod.get(CFG, "getCommissionRouteMode")});
        try {
            Object ether = c.onClient(mc -> Mod.enumValue(MODE, "ETHERWARP"));
            Object[] after = c.onClient(mc -> {
                Mod.set(CFG, "setCommissionRoute", true);
                Mod.set(CFG, "setCommissionRouteColor", 0xFF123456);
                Mod.set(CFG, "setCommissionRouteMode", ether);
                Mod.call(Mod.cfg(CFG), "save");
                Mod.staticCall(CFG, "load");
                return new Object[]{Mod.get(CFG, "isCommissionRoute"), Mod.get(CFG, "getCommissionRouteColor"),
                        String.valueOf(Mod.get(CFG, "getCommissionRouteMode"))};
            });
            c.check(Boolean.TRUE.equals(after[0]), "Commission Route off after reload");
            c.check(Integer.valueOf(0xFF123456).equals(after[1]), "color after reload " + after[1]);
            c.check("ETHERWARP".equals(after[2]), "mode after reload " + after[2]);
            c.note("Commission Route on, color 0xFF123456 and mode ETHERWARP survive save + load");
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.set(CFG, "setCommissionRoute", before[0]);
                Mod.set(CFG, "setCommissionRouteColor", before[1]);
                Mod.set(CFG, "setCommissionRouteMode", before[2]);
                Mod.call(Mod.cfg(CFG), "save");
            });
        }
    }
}

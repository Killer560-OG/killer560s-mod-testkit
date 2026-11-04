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
    private static final String CLAIM = "miningqol.CommissionClaim";

    private static final int FY = 181;
    private static final int Z = 300;

    private HxCommissionRoute1103Cases() {
    }

    static void register(Session s) {
        s.test("1103-mining-route-etherwarp", HxCommissionRoute1103Cases::etherwarp);
        s.test("1103-mining-route-walk", HxCommissionRoute1103Cases::walk);
        s.test("1103-mining-route-aote", HxCommissionRoute1103Cases::aote);
        s.test("1103-mining-route-persist", HxCommissionRoute1103Cases::persist);
        s.test("1103-mining2-route-origin", HxCommissionRoute1103Cases::origin);
        s.test("1103-mining2-route-own-toggle", HxCommissionRoute1103Cases::ownToggle);
        s.test("1103-mining2-route-aote-air", HxCommissionRoute1103Cases::aoteAir);
        s.test("1103-mining2-route-claim", HxCommissionRoute1103Cases::claim);
        s.test("1103-mining2-route-patch", HxCommissionRoute1103Cases::patch);
        s.test("1103-mining2-route-stable", HxCommissionRoute1103Cases::stable);
        s.test("1103-mining2-route-claim-matrix", HxCommissionRoute1103Cases::claimMatrix);
        s.test("1103-mining2-route-claim-ends", HxCommissionRoute1103Cases::claimEnds);
        s.test("1103-mining2-done-chat", HxCommissionRoute1103Cases::doneChat);
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
        return node(id, x, FY, z, area, neighbours);
    }

    private static String node(int id, int x, int y, int z, String area, String neighbours) {
        String tags = area == null ? "" : ",\"Name\":\"" + area + "\",\"Tags\":[\"area\"]";
        return "\"" + id + "\":{\"Position\":\"" + x + ".0:" + y + ".0:" + z + ".0\"" + tags
                + ",\"Neighbours\":{" + neighbours + "}}";
    }

    /** Where he stood before the arena: put back there after, so the next case does not start falling. */
    private static Vec3 home = null;

    private static void arena(Session c) {
        arenaWith(c, null);
    }

    private static void arenaWith(Session c, String custom) {
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
        String json = custom != null ? custom : "{" + node(0, 300, Z, "Royal Mines", "\"1\":2.0")
                + "," + node(1, 302, Z, null, "\"0\":2.0,\"2\":28.0,\"6\":40.0")
                + "," + node(2, 330, Z, null, "\"1\":28.0,\"3\":35.0")
                + "," + node(3, 365, Z, null, "\"2\":35.0,\"4\":15.0,\"6\":40.0")
                + "," + node(4, 380, Z, "Lava Springs", "\"3\":15.0")
                + "," + node(6, 330, 320, null, "\"1\":40.0,\"3\":40.0") + "}";
        c.ctx().runOnClient(mc -> Mod.staticCall(MAP, "useGraphJsonForTest", json));
        c.waitUntil("the test area map in use", mc -> (Boolean) Mod.staticCall(MAP, "loaded"), 20);
        if (custom == null) {
            c.check("Lava Springs".equals(c.onClient(mc -> Mod.staticCall(MAP, "areaAt", 365, FY, Z))),
                    "node 3's block is not Lava Springs");
            c.check("Royal Mines".equals(c.onClient(mc -> Mod.staticCall(MAP, "areaAt", 330, FY, Z))),
                    "node 2's block is not Royal Mines");
        }
    }

    private static void cleanup(Session c) {
        c.ctx().runOnClient(mc -> Mod.staticCall(MAP, "clearTestGraphForTest"));
        c.ctx().runOnClient(mc -> Mod.staticCall(CLAIM, "resetForTest"));
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
            c.check(path.size() == 2 && path.get(0) == 2 && (path.get(1) == 3 || path.get(1) == 4),
                    "warp path " + path + ", wanted two warps: node 2, then node 3 or 4");
            List<String> hops = checkEther(c, "open floor");
            c.check(hops.size() == 2 && hops.get(0).equals("330,180,300")
                    && (hops.get(1).equals("365,180,300") || hops.get(1).equals("380,180,300")),
                    "open floor hops " + hops + ", wanted [330,180,300, then 365 or 380] (57-block range: the fewest warps)");
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
            c.check(!walled.contains("365,180,300") && !walled.contains("380,180,300"), "warped through the wall: " + walled);
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
                .with(CFG, "CommissionRouteEmissary", false).with(CFG, "CommissionRouteMode", mode)) {
            arena(c);
            mines(c, "Royal Mines", "Lava Springs Mithril: 10%");
            waitRoute(c, "a walking route");
            c.check(ids(c).equals(List.of(1, 2, 3)), "graph path " + ids(c));
            List<String> pts = list(c, "linePoints");
            c.check(pts.equals(List.of("330.5,181.5,300.5", "365.5,181.5,300.5")), // node 1, where he stands, is already reached
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
            // Nodes every 7 blocks along the floor, 302 .. 379; Royal Mines at the first, Lava Springs at the last.
            StringBuilder sb = new StringBuilder("{");
            for (int k = 0; k < 12; k++) {
                String nb = (k > 0 ? "\"" + (k - 1) + "\":7.0" : "") + (k > 0 && k < 11 ? "," : "")
                        + (k < 11 ? "\"" + (k + 1) + "\":7.0" : "");
                sb.append(k > 0 ? "," : "").append(node(k, 302 + 7 * k, Z, k == 0 ? "Royal Mines" : k == 11 ? "Lava Springs" : null, nb));
            }
            arenaWith(c, sb.append("}").toString());
            mines(c, "Royal Mines", "Lava Springs Mithril: 10%");
            waitRoute(c, "an AOTE route");
            List<String> hops = list(c, "hops");
            List<String> pts = list(c, "linePoints");
            c.note("AOTE " + hops.size() + " landings: " + hops);
            c.check(hops.size() >= 4, "only " + hops.size() + " landings for ~40 blocks");
            c.check(hops.size() <= 9, hops.size() + " landings for ~40 blocks (it bounced)");
            double[] stand = {302.5, 181.0, 300.5};
            for (String pt : pts) {
                double[] q = dxyz(pt);
                double d = Math.sqrt(Math.pow(q[0] - stand[0], 2) + Math.pow(q[1] - stand[1], 2) + Math.pow(q[2] - stand[2], 2));
                c.check(d <= 9.0, "a hop of " + d + " blocks to " + pt);
                BlockPos f = BlockPos.containing(q[0], q[1] + 0.01, q[2]);
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

    // ---- 1103-mining2-route-origin ----------------------------------------------------------------------------------

    /** "the line for the pathfinding to commission spots also still doesnt go from my crosshair". */
    private static void origin(Session c) {
        Object mode = c.onClient(mc -> Mod.enumValue(MODE, "WALK"));
        try (Settings set = new Settings(c).with(CFG, "CommissionRoute", true).with(CFG, "CommissionRouteMode", mode)) {
            arena(c);
            mines(c, "Royal Mines", "Lava Springs Mithril: 10%");
            waitRoute(c, "a walking route");
            int frames = count(c, "frames");
            c.waitUntil("the line drawn", mc -> (Integer) Mod.staticCall(ROUTE, "frames") > frames + 2, 100);
            double[] o = c.onClient(mc -> (double[]) Mod.staticCall(ROUTE, "lastOrigin"));
            Vec3 eye = c.onClient(mc -> mc.player.getEyePosition());
            c.check(o != null, "no line origin recorded");
            double d = new Vec3(o[0], o[1], o[2]).distanceTo(eye);
            c.check(d > 0.3 && d < 0.7, "the line starts " + d + " blocks from the eye (the crosshair end is 0.5 along the look)");
            c.check(o[1] > eye.y - 0.7, "the line starts at y " + o[1] + ", below the eye " + eye.y + " (the body)");
            c.note("line origin " + java.util.Arrays.toString(o) + " is " + String.format(java.util.Locale.ROOT, "%.2f", d)
                    + " from the eye " + eye);
        } finally {
            cleanup(c);
        }
    }

    // ---- 1103-mining2-route-own-toggle ------------------------------------------------------------------------------

    /** "make the pathfinding thing its own setting/toggle": the route runs with the Commission HUD switch off. */
    private static void ownToggle(Session c) {
        Object mode = c.onClient(mc -> Mod.enumValue(MODE, "WALK"));
        try (Settings set = new Settings(c).with(CFG, "CommissionHud", false).with(CFG, "CommissionRoute", true)
                .with(CFG, "CommissionRouteMode", mode)) {
            arena(c);
            mines(c, "Royal Mines", "Lava Springs Mithril: 10%");
            waitRoute(c, "a route with the Commission HUD off");
            c.check(!(Boolean) c.onClient(mc -> Mod.get(CFG, "isCommissionHud")), "the Commission HUD is on");
            c.ctx().runOnClient(mc -> Mod.set(CFG, "setCommissionRoute", false));
            c.waitUntil("no line with the route switch off", mc -> (Integer) Mod.staticCall(ROUTE, "pointCount") == 0, 40);
            c.note("route draws with the Commission HUD off, and only its own switch turns it off");
        } finally {
            cleanup(c);
        }
    }

    // ---- 1103-mining2-route-aote-air --------------------------------------------------------------------------------

    /** "instead of just teleporting up through the air it just has me run around to it". */
    private static void aoteAir(Session c) {
        int uses = sentUses(c);
        try (Settings set = new Settings(c).with(CFG, "CommissionRoute", true)) {
            // Start node 1 at 302 (Royal Mines via node 0); the Lava Springs node 5 stands on a stone block 5 east and 6 up,
            // 7.8 blocks away through air. The walking edge to it is 300 long: a walk is the long way round.
            String json = "{" + node(0, 300, Z, "Royal Mines", "\"1\":2.0")
                    + "," + node(1, 302, Z, null, "\"0\":2.0,\"5\":300.0")
                    + "," + node(5, 307, FY + 6, Z, "Lava Springs", "\"1\":300.0") + "}";
            arenaWith(c, json);
            c.server().command("setblock 307 " + (FY + 5) + " 300 minecraft:stone");
            c.waitUntil("the platform on the client", mc -> mc.level.getBlockState(new BlockPos(307, FY + 5, 300)).isSolid(), 100);
            mines(c, "Royal Mines", "Lava Springs Mithril: 10%");
            for (String m : new String[]{"AOTE", "ETHERWARP", "WALK"}) {
                Object mode = c.onClient(mc -> Mod.enumValue(MODE, m));
                c.ctx().runOnClient(mc -> Mod.set(CFG, "setCommissionRouteMode", mode));
                c.waitUntil("a " + m + " route", mc -> (Integer) Mod.staticCall(ROUTE, "pointCount") > 0
                        && ((List<?>) Mod.staticCall(ROUTE, "hops")).isEmpty() == "WALK".equals(m), 100);
                List<String> hops = list(c, "hops");
                List<String> pts = list(c, "linePoints");
                if ("WALK".equals(m)) {
                    c.check(pts.size() == 1 && pts.get(0).startsWith("307.5,"), "walking: node 1 is reached, node 5 is next: " + pts);
                    continue;
                }
                c.check(hops.equals(List.of("307," + (FY + 5) + ",300")), m + " hops " + hops + ", wanted the platform block");
                c.check(pts.size() == 1, m + " line " + pts + ": one hop straight to the platform, not the walking graph");
                double[] q = dxyz(pts.get(0));
                c.check(Math.abs(q[0] - 307.5) < 0.01 && Math.abs(q[1] - (FY + 6)) < 0.01 && Math.abs(q[2] - 300.5) < 0.01,
                        m + " lands at " + pts.get(0));
                c.note(m + ": one hop " + hops + " straight up through the air instead of the 300-long walk");
            }
        } finally {
            c.server().command("setblock 307 " + (FY + 5) + " 300 minecraft:air");
            cleanup(c);
        }
        c.check(sentUses(c) == uses, "the route sent use packets");
    }

    // ---- 1103-mining2-route-claim -----------------------------------------------------------------------------------

    /** Claiming: route to the nearest Emissary, or with that off pulse the Royal Pigeon slot. */
    private static void claim(Session c) {
        Object mode = c.onClient(mc -> Mod.enumValue(MODE, "WALK"));
        try (Settings set = new Settings(c).with(CFG, "CommissionRoute", true).with(CFG, "CommissionRouteMode", mode)
                .with(CFG, "CommissionRouteEmissary", true)) {
            arena(c);
            // Two Emissaries: one by node 2 (330), one by node 4 (380). The nearest by the walk is node 2's.
            c.ctx().runOnClient(mc -> Mod.staticCall(CLAIM, "emissariesForTest",
                    List.of(new BlockPos(331, FY, Z), new BlockPos(381, FY, Z))));
            // Nothing done: an area commission's line, not an emissary line.
            mines(c, "Royal Mines", "Lava Springs Mithril: 10%");
            waitRoute(c, "the area line first");
            c.check("Lava Springs".equals(c.onClient(mc -> Mod.staticCall(ROUTE, "currentTargetArea"))), "target before the claim");
            // The commission completes.
            c.hx().chat("LAVA SPRINGS MITHRIL Commission Complete! Visit the King to claim your rewards!");
            c.waitUntil("a claim due", mc -> (Boolean) Mod.staticCall(CLAIM, "claimDue"), 40);
            c.waitUntil("the line goes to an Emissary", mc -> "Emissary".equals(Mod.staticCall(ROUTE, "currentTargetArea"))
                    && (Integer) Mod.staticCall(ROUTE, "pointCount") > 0, 100);
            List<String> ids = ids(c).stream().map(String::valueOf).toList();
            c.check(ids(c).equals(List.of(1, 2)), "the claim path " + ids + ", wanted node 1, node 2 (the nearest Emissary)");
            c.check((Boolean) c.onClient(mc -> Mod.staticCall(CLAIM, "pigeonPulse")), "no pigeon pulse due beside the Emissary route");
            // Emissary routing off: no line, the pigeon pulses.
            c.server().command("item replace entity @a hotbar.4 with " + HxPigeon.stack());
            c.waitUntil("the pigeon in slot 5", mc -> (Integer) Mod.staticCall(CLAIM, "pigeonSlot") == 4, 60);
            c.ctx().runOnClient(mc -> Mod.set(CFG, "setCommissionRouteEmissary", false));
            c.waitUntil("pigeon pulse due", mc -> (Boolean) Mod.staticCall(CLAIM, "pigeonPulse"), 40);
            int frames = c.onClient(mc -> (Integer) Mod.staticCall(CLAIM, "pulseFrames"));
            c.waitUntil("the pulse drawn", mc -> (Integer) Mod.staticCall(CLAIM, "pulseFrames") > frames + 5, 100);
            c.check(Integer.valueOf(4).equals(c.onClient(mc -> Mod.staticCall(CLAIM, "lastPulseSlot"))), "pulsed the wrong slot");
            c.check(!"Emissary".equals(c.onClient(mc -> Mod.staticCall(ROUTE, "currentTargetArea"))), "still routing to an Emissary");
            // No pigeon in the hotbar: nothing to pulse (no crash, no draw).
            c.server().command("item replace entity @a hotbar.4 with minecraft:air");
            c.waitUntil("no pigeon", mc -> (Integer) Mod.staticCall(CLAIM, "pigeonSlot") == -1, 60);
            int f2 = c.onClient(mc -> (Integer) Mod.staticCall(CLAIM, "pulseFrames"));
            c.ctx().waitTicks(20);
            c.check(c.onClient(mc -> (Integer) Mod.staticCall(CLAIM, "pulseFrames")) == f2, "pulsed with no pigeon");
            c.note("claim: area line -> Emissary line (nearest, node 2) -> pigeon pulse on slot 5 with routing off -> nothing without a pigeon");
        } finally {
            c.server().command("item replace entity @a hotbar.4 with minecraft:air");
            cleanup(c);
        }
    }

    /** The Royal Pigeon as the server hands it out. */
    private static final class HxPigeon {
        static String stack() {
            return "minecraft:feather[custom_data={id:\"ROYAL_PIGEON\"}]";
        }
    }

    // ---- 1103-mining2-route-patch -----------------------------------------------------------------------------------

    /** "3x3 in that area and full block not just top layer": a column one block up is highlighted at its own height. */
    private static void patch(Session c) {
        Object mode = c.onClient(mc -> Mod.enumValue(MODE, "ETHERWARP"));
        try (Settings set = new Settings(c).with(CFG, "CommissionRoute", true).with(CFG, "CommissionRouteMode", mode)) {
            arena(c);
            // Beside the first landing (330,180,300): a step up at 331 and a hole at 329.
            c.server().command("setblock 331 181 300 minecraft:stone");
            c.server().command("setblock 329 180 300 minecraft:air");
            c.waitUntil("the step and the hole on the client", mc -> mc.level.getBlockState(new BlockPos(331, 181, 300)).isSolid()
                    && mc.level.getBlockState(new BlockPos(329, 180, 300)).isAir(), 100);
            mines(c, "Royal Mines", "Lava Springs Mithril: 10%");
            waitRoute(c, "an etherwarp route");
            @SuppressWarnings("unchecked")
            List<String> boxes = c.onClient(mc -> new ArrayList<>((List<String>) Mod.staticCall(ROUTE, "patchBlockList")));
            c.note("patch blocks " + boxes);
            c.check(boxes.contains("330,180,300"), "the landing block is not in its patch: " + boxes);
            c.check(boxes.contains("331,181,300"), "the column with a block one up is not highlighted at that block: " + boxes);
            c.check(!boxes.contains("331,180,300"), "the column with a step up also got the layer below it: " + boxes);
            c.check(boxes.stream().noneMatch(b -> b.startsWith("329,") && b.endsWith(",300")), "the hole column was highlighted: " + boxes);
            c.check(boxes.contains("329,180,301") && boxes.contains("331,180,299"), "plain columns around the landing missing: " + boxes);
            int frames = count(c, "frames");
            c.waitUntil("drawn", mc -> (Integer) Mod.staticCall(ROUTE, "frames") > frames + 2, 100);
            c.check(count(c, "patches") >= 8, "the frame drew " + count(c, "patches") + " patch boxes");
        } finally {
            c.server().command("setblock 331 181 300 minecraft:air");
            c.server().command("setblock 329 180 300 minecraft:stone");
            cleanup(c);
        }
    }

    // ---- 1103-mining2-route-stable ----------------------------------------------------------------------------------

    /** "the waypoint routing stuff is still really janky": the plan holds still until he reaches a hop or leaves the line. */
    private static void stable(Session c) {
        Object mode = c.onClient(mc -> Mod.enumValue(MODE, "ETHERWARP"));
        try (Settings set = new Settings(c).with(CFG, "CommissionRoute", true).with(CFG, "CommissionRouteMode", mode)) {
            arena(c);
            mines(c, "Royal Mines", "Lava Springs Mithril: 10%");
            waitRoute(c, "an etherwarp route");
            List<String> pts0 = list(c, "linePoints");
            List<String> hops0 = list(c, "hops");
            int comp0 = count(c, "computes");
            c.check(pts0.size() == 2 && hops0.size() == 2, "plan " + pts0 + " / " + hops0);
            // Standing still: the same plan on every one of 60 ticks, nothing rebuilt.
            for (int i = 0; i < 60; i++) {
                c.ctx().waitTicks(1);
                c.check(list(c, "linePoints").equals(pts0), "the line changed while standing still, tick " + i + ": " + list(c, "linePoints"));
            }
            c.check(count(c, "computes") == comp0, "re-planned while standing still: " + (count(c, "computes") - comp0));
            // The commission's percent moving (the tab refreshes every few seconds) is no reason to re-plan.
            mines(c, "Royal Mines", "Lava Springs Mithril: 35%");
            c.ctx().waitTicks(30);
            c.check(list(c, "linePoints").equals(pts0) && count(c, "computes") == comp0, "a progress update re-planned: "
                    + list(c, "linePoints") + " computes +" + (count(c, "computes") - comp0));
            // Reaching the first hop: it is dropped from the front, nothing re-planned, then it holds again.
            double[] q = dxyz(pts0.get(0));
            c.server().command(String.format(java.util.Locale.ROOT, "tp @a %.3f %.3f %.3f", q[0], q[1], q[2]));
            c.waitUntil("the first hop dropped", mc -> ((List<?>) Mod.staticCall(ROUTE, "hops")).size() == 1, 100);
            List<String> after = list(c, "linePoints");
            c.check(after.equals(pts0.subList(1, 2)), "after reaching the hop the line is " + after + ", wanted " + pts0.subList(1, 2));
            for (int i = 0; i < 40; i++) {
                c.ctx().waitTicks(1);
                c.check(list(c, "linePoints").equals(after), "the line changed after the hop, tick " + i);
            }
            c.check(count(c, "computes") == comp0, "reaching a hop re-planned: " + (count(c, "computes") - comp0));
            // Leaving the line for good: one re-plan, then stable again.
            c.server().command("tp @a 298.5 181 300.5");
            c.waitUntil("one re-plan", mc -> (Integer) Mod.staticCall(ROUTE, "computes") == comp0 + 1, 100);
            List<String> moved = list(c, "linePoints");
            c.ctx().waitTicks(40);
            c.check(count(c, "computes") == comp0 + 1 && list(c, "linePoints").equals(moved), "unstable after the re-plan: computes +" + (count(c, "computes") - comp0) + ", line was " + moved + " now " + list(c, "linePoints")
                    + ", hops " + list(c, "hops") + ", target " + c.onClient(mc -> Mod.staticCall(ROUTE, "currentTargetArea")));
            c.note("60 ticks still: no change; progress update: none; reached hop: dropped once, no re-plan; left the line: 1 re-plan");
        } finally {
            cleanup(c);
        }
    }

    // ---- 1103-mining2-route-claim-matrix ----------------------------------------------------------------------------

    /** Route To Emissary and Highlight Royal Pigeon are two switches: all four combinations, and the route switch off. */
    private static void claimMatrix(Session c) {
        Object mode = c.onClient(mc -> Mod.enumValue(MODE, "WALK"));
        try (Settings set = new Settings(c).with(CFG, "CommissionRoute", true).with(CFG, "CommissionRouteMode", mode)) {
            arena(c);
            c.ctx().runOnClient(mc -> Mod.staticCall(CLAIM, "emissariesForTest", List.of(new BlockPos(331, FY, Z))));
            c.server().command("item replace entity @a hotbar.2 with " + HxPigeon.stack());
            c.waitUntil("the pigeon in slot 3", mc -> (Integer) Mod.staticCall(CLAIM, "pigeonSlot") == 2, 60);
            // One commission DONE (a claim is due), another open in a different area.
            mines(c, "Royal Mines", "Lava Springs Mithril: DONE", "Cliffside Veins Titanium: 10%");
            c.waitUntil("a claim due", mc -> (Boolean) Mod.staticCall(CLAIM, "claimDue"), 40);
            for (boolean emissary : new boolean[]{true, false}) {
                for (boolean pigeon : new boolean[]{true, false}) {
                    c.ctx().runOnClient(mc -> {
                        Mod.set(CFG, "setCommissionRouteEmissary", emissary);
                        Mod.set(CFG, "setCommissionPigeon", pigeon);
                    });
                    c.ctx().waitTicks(30);
                    String what = "Route To Emissary " + emissary + ", Highlight Royal Pigeon " + pigeon;
                    Object target = c.onClient(mc -> Mod.staticCall(ROUTE, "currentTargetArea"));
                    c.check("Emissary".equals(target) == emissary, what + ": route target " + target);
                    if (!emissary) {
                        c.check("Cliffside Veins".equals(target), what + ": without the Emissary route the normal area line carries on, target " + target);
                    }
                    int f0 = c.onClient(mc -> (Integer) Mod.staticCall(CLAIM, "pulseFrames"));
                    c.ctx().waitTicks(20);
                    int df = c.onClient(mc -> (Integer) Mod.staticCall(CLAIM, "pulseFrames")) - f0;
                    c.check((df > 5) == pigeon, what + ": " + df + " pulse frames in 20 ticks");
                    c.note(what + ": target " + target + ", pulse frames " + df);
                }
            }
            // The route switch off: no line, the pigeon switch alone still pulses.
            c.ctx().runOnClient(mc -> {
                Mod.set(CFG, "setCommissionRoute", false);
                Mod.set(CFG, "setCommissionRouteEmissary", true);
                Mod.set(CFG, "setCommissionPigeon", true);
            });
            c.waitUntil("no line with the route switch off", mc -> (Integer) Mod.staticCall(ROUTE, "pointCount") == 0, 40);
            int f1 = c.onClient(mc -> (Integer) Mod.staticCall(CLAIM, "pulseFrames"));
            c.ctx().waitTicks(20);
            c.check(c.onClient(mc -> (Integer) Mod.staticCall(CLAIM, "pulseFrames")) - f1 > 5, "no pulse with the route switch off");
        } finally {
            c.server().command("item replace entity @a hotbar.2 with minecraft:air");
            cleanup(c);
        }
    }

    // ---- 1103-mining2-route-claim-ends ------------------------------------------------------------------------------

    /** The chat line only bridges to the tab's next update; the claim ends when the tab shows no DONE. */
    private static void claimEnds(Session c) {
        try (Settings set = new Settings(c).with(CFG, "CommissionPigeon", true)) {
            c.ctx().runOnClient(mc -> Mod.staticCall(CLAIM, "resetForTest"));
            mines(c, "Royal Mines", "Lava Springs Mithril: 40%", "Mithril Miner: 0%");
            c.check(!(Boolean) c.onClient(mc -> Mod.staticCall(CLAIM, "claimDue")), "a claim due with nothing done");
            c.hx().chat("LAVA SPRINGS MITHRIL Commission Complete! Visit the King to claim your rewards!");
            c.waitUntil("pulse from the line alone", mc -> (Boolean) Mod.staticCall(CLAIM, "claimDue"), 40);
            // The tab updates and shows no DONE: over within a tick or two, not 20 seconds.
            mines(c, "Royal Mines", "Lava Springs Mithril: 0%", "Mithril Miner: 0%");
            c.waitUntil("the claim over once the tab updated", mc -> !(Boolean) Mod.staticCall(CLAIM, "claimDue"), 40);
            // DONE in the tab without any line.
            c.ctx().runOnClient(mc -> Mod.staticCall(CLAIM, "resetForTest"));
            mines(c, "Royal Mines", "Lava Springs Mithril: DONE", "Mithril Miner: 0%");
            c.waitUntil("pulse from the tab's DONE", mc -> (Boolean) Mod.staticCall(CLAIM, "claimDue"), 40);
            mines(c, "Royal Mines", "Lava Springs Mithril: 0%", "Mithril Miner: 0%");
            c.waitUntil("over when the DONE is gone", mc -> !(Boolean) Mod.staticCall(CLAIM, "claimDue"), 40);
            c.note("line -> due; tab without DONE -> over within ticks; tab DONE alone -> due; DONE gone -> over");
        } finally {
            c.ctx().runOnClient(mc -> Mod.staticCall(CLAIM, "resetForTest"));
            hub(c);
        }
    }

    // ---- 1103-mining2-done-chat -------------------------------------------------------------------------------------

    /** Hypixel's own completion line marks the commission DONE at once; a player's message cannot. */
    private static void doneChat(Session c) {
        try (Settings set = new Settings(c).with(CFG, "CommissionHud", true)) {
            c.ctx().runOnClient(mc -> Mod.staticCall(CLAIM, "resetForTest"));
            mines(c, "Royal Mines", "Royal Mines Mithril: 98%", "Mithril Miner: 10%");
            c.check(!(Boolean) c.onClient(mc -> Mod.staticCall(STATE, "anyDone")), "DONE before the line");
            // Forged lines from a player: nothing.
            c.hx().chat("Bob: ROYAL MINES MITHRIL Commission Complete! Visit the King to claim your rewards!");
            c.hx().chat("[VIP] Bob: ROYAL MINES MITHRIL Commission Complete! Visit the King to claim your rewards!");
            c.ctx().waitTicks(25);
            c.check(!(Boolean) c.onClient(mc -> Mod.staticCall(STATE, "anyDone")), "a player's chat marked a commission DONE");
            // Hypixel's line, while the tab still says 98%.
            c.hx().chat("ROYAL MINES MITHRIL Commission Complete! Visit the King to claim your rewards!");
            c.waitUntil("DONE from the line, tab still 98%", mc -> (Boolean) Mod.staticCall(STATE, "anyDone"), 20);
            c.ctx().waitTicks(25);
            c.check((Boolean) c.onClient(mc -> Mod.staticCall(STATE, "anyDone")), "the tab's old 98% undid the DONE");
            // The tab, lagging, still reads 99%: stays DONE. Then a new commission of that name reads 0%: not DONE.
            mines(c, "Royal Mines", "Royal Mines Mithril: 99%", "Mithril Miner: 10%");
            c.ctx().waitTicks(25);
            c.check((Boolean) c.onClient(mc -> Mod.staticCall(STATE, "anyDone")), "a lagging 99% undid the DONE");
            mines(c, "Royal Mines", "Royal Mines Mithril: 0%", "Mithril Miner: 10%");
            c.waitUntil("a fresh 0% is not DONE", mc -> !(Boolean) Mod.staticCall(STATE, "anyDone"), 40);
            c.note("line -> DONE at once; forged lines ignored; lagging tab keeps it; a lower reading ends it");
        } finally {
            c.ctx().runOnClient(mc -> Mod.staticCall(CLAIM, "resetForTest"));
            hub(c);
        }
    }
}

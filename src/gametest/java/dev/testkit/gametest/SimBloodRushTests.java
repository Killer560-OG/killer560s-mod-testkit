package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * bloodrush-1103: Blood Rush Helper (mod bloodrush-1103, killer560 2026-11-03: "Theirs shows where you should go to kill
 * blood rush mobs") and the Dungeon Sim's Spawn Starred Mobs on learned spawn spots ("for our clear sim you should be able
 * to add mobs in their blood rush helper spots since that is where they spawn"; "Someone should be able to choose whether
 * the mobs spawn or not for the sim").
 * <ul>
 *   <li><b>1103-sim-bloodrush</b> - on a floor with mobs, Blood Rush Helper on: in the Entrance (no mob room) nothing
 *       shows; teleported into the first key room (the room the hotbar map shows beside a wither door), every starred mob
 *       the SIM SERVER has in that room stands inside a drawn spot, the spots are numbered 1..n nearest-first from where
 *       he came in (each next the nearest to the last), exactly one is "next", spot 1 is the one he stands on; killing
 *       spot 1's mobs drops it and makes 2 the next; Killers 2 / My Role 2 numbers only the second run and marks the
 *       others R1; "The BLOOD DOOR has been opened!" clears every spot. Learning is off in the sim (no spot learned).</li>
 *   <li><b>1103-sim-starredspots</b> - Spawn Starred Mobs on, spawn spots seeded (room-relative, from each room's own
 *       secret positions in the room database) for every mob room before GO: each seeded room's starred mobs stand on
 *       those spots turned by the room's rotation (checked against RoomDatabase.toRealCoord, the proven transform, not the
 *       code under test), on the server, in at least three rooms and two rotations.</li>
 *   <li><b>1103-sim-starredoff</b> - Spawn Starred Mobs off: the floor's GO places no starred mob.</li>
 *   <li><b>1104-bloodrush-known</b> - mod bloodrush2-1104: the shipped spot table shows a room's boxes with no mob in
 *       view, proven in screen pixels (helper on vs off, Outline Width 1 vs 8, Filled Boxes, a wall hiding the box and
 *       Through Walls showing it on a cheat jar), the new settings survive save + load, and spots are learned after the
 *       blood door opens (see {@code known}).</li>
 * </ul>
 * Only when named. GrimAC does not apply (the sim's integrated server).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimBloodRushTests implements FabricClientGameTest {

    static final String RUSH = "1103-sim-bloodrush";
    static final String SPOTS = "1103-sim-starredspots";
    static final String OFF = "1103-sim-starredoff";
    static final String SPLIT = "1103-bloodrush-split";
    static final String KNOWN = "1104-bloodrush-known";
    private static final String BR = "com.killer560.hub.bloodrush.BloodRushHelperFeature";
    private static final String BR_CFG = "com.killer560.hub.bloodrush.BloodRushConfig";
    private static final String BR_SPOTS = "com.killer560.hub.bloodrush.BloodRushSpots";
    private static final String ROLES_CLS = "com.killer560.hub.bloodrush.BloodRushRoles";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String SIM_FLOOR = "com.killer560.hub.roomsim.SimMobFloor";
    private static final String SIM_MOBS = "com.killer560.hub.roomsim.SimMobs";
    private static final String SIM_LIVE = "com.killer560.hub.roomsim.SimLiveMobs";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_RUN = "com.killer560.hub.roomsim.SimRun";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String PREFS = "com.killer560.hub.roomsim.SimMenuPrefs";
    private static final String DB = "com.killer560.hub.roomdatabase.RoomDatabase";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank()) {
            return;
        }
        List<String> failed = new ArrayList<>();
        for (String name : List.of(SPLIT, RUSH, SPOTS, OFF, KNOWN)) {
            if (Scenario.skip(name)) {
                continue;
            }
            ModUnderTest.require("killer560smod");
            LogTap.install();
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
            if (SimMapTests.copyRoomsForOthers() < 20) {
                Scenario.skipped(name, "needs real room captures and room database");
                continue;
            }
            Scenario.ensureRoomDatabase(ctx);
            ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "forceReload"));
            ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "isReady"), 2400);
            ctx.runOnClient(mc -> {
                ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                        "setAutoPuzzlesMasterEnabled", false);
                ModUnderTest.set(ModUnderTest.config("com.killer560.hub.livemap.LiveMapConfig"), "setEnabled", true);
            });
            int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
            ctx.runOnClient(mc -> mc.options.renderDistance().set(16));
            List<String> problems = new ArrayList<>();
            List<String> facts = new ArrayList<>();
            try {
                if (name.equals(SPLIT)) {
                    splitRule(ctx, problems, facts);
                } else if (name.equals(RUSH)) {
                    // The floor is random; the helper shows the FIRST key room on the rush path, which can hold only 2
                    // starred mobs (2026-11-03 gate). Build a new floor until it has 3 or more (at most 3 floors).
                    for (int attempt = 1; attempt <= 3; attempt++) {
                        if (!SimMobFloorTests.buildMobFloor(ctx, problems, facts)) {
                            break;
                        }
                        int n = firstKeyRoomStarred(ctx);
                        if (n >= 3 || attempt == 3) {
                            rush(ctx, problems, facts);
                            break;
                        }
                        facts.add("floor " + attempt + ": first key room has only " + n + " starred mob(s), new floor");
                    }
                } else if (name.equals(SPOTS)) {
                    starredSpots(ctx, problems, facts);
                } else if (name.equals(KNOWN)) {
                    known(ctx, problems, facts);
                } else {
                    starredOff(ctx, problems, facts);
                }
            } catch (Throwable t) {
                problems.add("threw: " + t);
                t.printStackTrace(System.out);
            } finally {
                ctx.runOnClient(mc -> {
                    Object cfg = ModUnderTest.config(BR_CFG);
                    ModUnderTest.set(cfg, "setEnabled", false);
                    ModUnderTest.call(cfg, "setKillers", new Class<?>[]{int.class}, new Object[]{1});
                    ModUnderTest.staticCall(BR_SPOTS, "resetForTest", new Class<?>[]{boolean.class}, new Object[]{true});
                    try {
                        setStatic(BR, "learnInSimForTest", false);
                    } catch (Throwable ignored) {
                        // an older jar has no such hook
                    }
                    dev.testkit.compat.McCompat.setHudHidden(mc, false);
                });
                SimMobFloorTests.leave(ctx);
                ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
                ctx.runOnClient(mc -> setSpawnStarred(false));
            }
            for (String f : facts) {
                println("FACT " + f);
            }
            if (!problems.isEmpty()) {
                for (String p : problems) {
                    println("PROBLEM " + p);
                }
                failed.add(name + ": " + problems.size() + " problem(s): " + String.join("; ", problems));
                continue;
            }
            println("PASS");
        }
        if (!failed.isEmpty()) {
            throw new AssertionError(String.join(" | ", failed));
        }
    }

    /** What the Sim Settings tab's Spawn Starred Mobs switch does: the saved Mobs choice, and the next floor follows it. */
    private static void setSpawnStarred(boolean on) {
        Object prefs = ModUnderTest.staticCall(PREFS, "load");
        try {
            prefs.getClass().getField("mobs").setBoolean(prefs, on);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("SimMenuPrefs.mobs", e);
        }
        ModUnderTest.call(prefs, "save", new Class<?>[0], new Object[0]);
        ModUnderTest.staticCall(SIM_FLOOR, "setEnabled", new Class<?>[]{boolean.class}, new Object[]{on});
    }

    @SuppressWarnings("unchecked")
    private static List<String> spots(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (List<String>) ModUnderTest.staticCall(BR, "spotsForTest"));
    }

    /** A spot line "room|order|look|x0,y0,z0,x1,y1,z1|mobs|alive|learnedOnly|next|label|role" as its parts; x, y, z its
     *  box's bottom middle. */
    private record Spot(String room, int order, boolean mine, String look, AABB box, double x, double y, double z, int mobs,
                        int alive, boolean next, String label, int role) {
        static Spot of(String line) {
            String[] p = line.split("\\|", -1);
            String[] c = p[3].split(",");
            AABB b = new AABB(Double.parseDouble(c[0]), Double.parseDouble(c[1]), Double.parseDouble(c[2]),
                    Double.parseDouble(c[3]), Double.parseDouble(c[4]), Double.parseDouble(c[5]));
            return new Spot(p[0], Integer.parseInt(p[1]), "MINE".equals(p[2]), p[2], b, (b.minX + b.maxX) / 2, b.minY,
                    (b.minZ + b.maxZ) / 2, Integer.parseInt(p[4]), Integer.parseInt(p[5]), Boolean.parseBoolean(p[7]), p[8],
                    Integer.parseInt(p[9]));
        }
    }

    private static List<Spot> parsed(ClientGameTestContext ctx) {
        List<Spot> out = new ArrayList<>();
        for (String s : spots(ctx)) {
            out.add(Spot.of(s));
        }
        return out;
    }

    // ---- 1103-sim-bloodrush -------------------------------------------------------------------------------------------

    private static String me(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
    }

    /** A chat line as the server sends it (a system line, as Hypixel's party chat arrives). */
    private static void say(ClientGameTestContext ctx, String line) {
        onServer(ctx, (level, sp) -> {
            level.getServer().getPlayerList().broadcastSystemMessage(net.minecraft.network.chat.Component.literal(line), false);
            return null;
        });
        ctx.waitTicks(6);
    }

    private static String roles(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(ROLES_CLS, "stateForTest")));
    }

    @SuppressWarnings("unchecked")
    private static List<String> hud(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (List<String>) ModUnderTest.staticCall(BR, "hudLines"));
    }

    private static double[] doorOf(ClientGameTestContext ctx, String room) {
        @SuppressWarnings("unchecked")
        List<String> doors = ctx.computeOnClient(mc -> (List<String>) ModUnderTest.staticCall(BR, "doorsForTest"));
        for (String d : doors) {
            String[] p = d.split("\\|");
            if (p[0].equals(room)) {
                String[] xz = p[1].split(",");
                return new double[]{Double.parseDouble(xz[0]), Double.parseDouble(xz[1])};
            }
        }
        return null;
    }

    /** Starred mobs the sim server holds in the floor's first key room, or -1 with no key room. */
    private static int firstKeyRoomStarred(ClientGameTestContext ctx) {
        @SuppressWarnings("unchecked")
        List<String> keyRooms = ctx.computeOnClient(mc -> (List<String>) ModUnderTest.staticCall(SIM_FLOOR, "keyRoomsForTest"));
        return keyRooms.isEmpty() ? -1 : serverStarredIn(ctx, keyRooms.get(0).split("\\|")[0]).size();
    }

    private static void rush(ClientGameTestContext ctx, List<String> problems, List<String> facts) {
        Map<String, Integer> learnedBefore = learnedCounts(ctx);
        ctx.runOnClient(mc -> {
            ModUnderTest.staticCall(BR, "resetForTest");
            ModUnderTest.staticCall(ROLES_CLS, "resetForTest");
            Object cfg = ModUnderTest.config(BR_CFG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setAllRooms", false);
            ModUnderTest.set(cfg, "setShowOthers", true);
            ModUnderTest.set(cfg, "setShowLearned", false);
            ModUnderTest.call(cfg, "setKillers", new Class<?>[]{int.class}, new Object[]{1});
            ModUnderTest.set(cfg, "setThroughWalls", false);
            ModUnderTest.set(cfg, "setWholePath", false);
        });
        ctx.waitTicks(20);
        // Control: the Entrance is no mob room, so nothing shows there.
        List<String> atStart = spots(ctx);
        facts.add("in the Entrance: " + atStart.size() + " box(es); " + status(ctx));
        if (!atStart.isEmpty()) {
            problems.add("CONTROL FAILED: boxes in the Entrance: " + atStart);
            return;
        }
        @SuppressWarnings("unchecked")
        List<String> keyRooms = ctx.computeOnClient(mc -> (List<String>) ModUnderTest.staticCall(SIM_FLOOR, "keyRoomsForTest"));
        String room = keyRooms.isEmpty() ? null : keyRooms.get(0).split("\\|")[0];
        facts.add("key rooms " + keyRooms + "; into " + room);
        if (room == null) {
            problems.add("no key room on this floor");
            return;
        }
        List<double[]> mobs = serverStarredIn(ctx, room);
        facts.add(room + ": the sim server has " + mobs.size() + " starred mob(s) there");
        if (mobs.size() < 3) {
            problems.add("only " + mobs.size() + " starred mob(s) in " + room + " - nothing to split");
            return;
        }
        double[] land = mobs.get(0);
        SimMobFloorTests.teleport(ctx, land[0], land[1], land[2]);
        List<Spot> shown = List.of();
        for (int i = 0; i < 100 && shown.isEmpty(); i += 5) {
            ctx.waitTicks(5);
            shown = parsed(ctx);
        }
        @SuppressWarnings("unchecked")
        List<String> rushRooms = ctx.computeOnClient(mc -> (List<String>) ModUnderTest.staticCall(BR, "mapRushRoomsForTest"));
        facts.add("rush path by the hotbar map: " + rushRooms + "; " + status(ctx));
        facts.add("boxes in " + room + ": " + spots(ctx));
        if (!rushRooms.contains(room)) {
            problems.add(room + " is not on the rush path by the hotbar map (" + rushRooms + ")");
        }
        if (shown.isEmpty()) {
            problems.add("no box shown in " + room);
            return;
        }
        // Every starred mob the server has in the room stands inside a drawn box.
        mobs = serverStarredIn(ctx, room);
        int inside = 0;
        for (double[] m : mobs) {
            boolean in = false;
            for (Spot s : shown) {
                in |= s.box().inflate(0.3).contains(m[0], m[1] + 0.1, m[2]);
            }
            if (in) {
                inside++;
            } else {
                problems.add(String.format(Locale.ROOT, "starred mob at %.1f %.1f %.1f in no box", m[0], m[1], m[2]));
            }
        }
        facts.add(inside + " of " + mobs.size() + " server starred mobs inside a box; " + shown.size() + " box(es)");
        // Just Me: every box his, numbered 1..n from the door the split went by, each next the nearest to the last.
        double[] door = doorOf(ctx, room);
        facts.add("door the split went by: " + (door == null ? "none" : String.format(Locale.ROOT, "%.1f, %.1f", door[0], door[1])));
        List<Spot> mine = new ArrayList<>(shown);
        mine.sort((a, b) -> Integer.compare(a.order(), b.order()));
        for (int i = 0; i < mine.size(); i++) {
            if (mine.get(i).order() != i + 1 || !mine.get(i).mine()) {
                problems.add("box orders are not 1.." + mine.size() + ": " + spots(ctx));
                break;
            }
        }
        long nexts = mine.stream().filter(Spot::next).count();
        if (nexts != 1 || !mine.get(0).next()) {
            problems.add(nexts + " next box(es); the next should be box 1 only");
        }
        if (door != null) {
            Vec3 from = new Vec3(door[0], 0, door[1]);
            for (int i = 0; i < mine.size(); i++) {
                Spot s = mine.get(i);
                Vec3 c = new Vec3(s.x(), 0, s.z());
                for (int j = i + 1; j < mine.size(); j++) {
                    Spot o = mine.get(j);
                    if (new Vec3(o.x(), 0, o.z()).distanceTo(from) + 0.05 < c.distanceTo(from)) {
                        problems.add("box " + o.order() + " is nearer to " + (i == 0 ? "the door" : "box " + i) + " than box " + s.order());
                    }
                }
                from = c;
            }
        } else {
            problems.add("no door recorded for " + room);
        }
        // Kill box 1's mobs: it goes, and box 2 is the next.
        AABB first = mine.get(0).box().inflate(0.3, 2.5, 0.3);
        int killed = onServer(ctx, (level, sp) -> {
            int n = 0;
            for (Entity e : level.getEntitiesOfClass(Entity.class, first, e -> !(e instanceof Player))) {
                e.discard();
                n++;
            }
            return n;
        });
        ctx.waitTicks(20);
        List<Spot> after = parsed(ctx);
        facts.add("killed " + killed + " entities in box 1; boxes now " + spots(ctx));
        Spot nextNow = after.stream().filter(Spot::next).findFirst().orElse(null);
        if (after.stream().anyMatch(s -> s.order() == 1)) {
            problems.add("box 1 still shown after its mobs died");
        }
        if (nextNow == null || nextNow.order() != 2) {
            problems.add("after box 1, the next is " + nextNow + ", not box 2");
        }
        String self = me(ctx);
        // Forged role lines: none is Hypixel's own party line, so nothing changes and no notice shows.
        for (String forged : List.of("Guild > Bob: !3br 2", "Bob: !br 1", "[VIP] Bob: Party > Evil: !br d",
                "Party > Bob123456789012345x: !br 2", "Party > Bob: !5br 1", "Party > Bob: !3br 9",
                "Co-op > Bob: !2br 1", "From Bob: !br 1", "Party > Bob: hi !br 1")) {
            say(ctx, forged);
        }
        String forgedState = roles(ctx);
        facts.add("after forged lines: " + forgedState + "; hud " + hud(ctx));
        if (!forgedState.contains("seen=false") || !hud(ctx).isEmpty()) {
            problems.add("a forged role line was taken: " + forgedState);
        }
        // Party lines: three killing, Bob 1, me 2, Jim on the door.
        say(ctx, "Party > [MVP+] Bob: !3br 1");
        say(ctx, "Party > " + self + ": !3br 2");
        say(ctx, "Party > Jim: !br d");
        ctx.waitTicks(6);
        String claimed = roles(ctx);
        List<String> hudNow = hud(ctx);
        List<Spot> split = parsed(ctx);
        facts.add("after party claims: " + claimed + "; hud " + hudNow + "; boxes " + spots(ctx));
        if (!claimed.contains("count=3") || !claimed.contains("mine=2") || !claimed.contains("1 Bob, 2 you, door Jim")) {
            problems.add("party claims not adopted: " + claimed);
        }
        if (hudNow.size() != 2 || !hudNow.get(0).startsWith("ADM players in party")) {
            problems.add("no ADM notice on the HUD: " + hudNow);
        }
        if (split.stream().filter(Spot::mine).anyMatch(s -> s.role() != 2)
                || split.stream().filter(s -> !s.mine() && s.role() == 1).anyMatch(s -> !s.label().contains("R1 Bob"))) {
            problems.add("the split does not follow the claims: " + spots(ctx));
        }
        // Master mode class roles, under any party chat claim (claims cleared first).
        Map<String, String> classes = new java.util.LinkedHashMap<>();
        for (String[] t : new String[][]{{"ARCHER", "4"}, {"MAGE", "4"}, {"BERSERKER", "4"}, {"TANK", "4"}, {"HEALER", "4"},
                {"TANK", "2"}}) {
            ctx.runOnClient(mc -> {
                ModUnderTest.staticCall(ROLES_CLS, "resetForTest");
                ModUnderTest.staticCall(ROLES_CLS, "testClassAndFloor", new Class<?>[]{String.class, Boolean.class},
                        new Object[]{t[0], true});
                ModUnderTest.call(ModUnderTest.config(BR_CFG), "setKillers", new Class<?>[]{int.class},
                        new Object[]{Integer.parseInt(t[1])});
            });
            classes.put(t[0] + "/" + t[1], roles(ctx));
        }
        facts.add("master mode class roles: " + classes);
        check(problems, classes.get("ARCHER/4"), "mine=1", "Archer is role 1");
        check(problems, classes.get("MAGE/4"), "mine=2", "Mage is role 2");
        check(problems, classes.get("BERSERKER/4"), "mine=3", "Berserk is role 3");
        check(problems, classes.get("TANK/4"), "mine=4", "Tank is role 4");
        check(problems, classes.get("HEALER/4"), "door=true", "the Healer is on the door");
        check(problems, classes.get("TANK/2"), "count=0", "Tank past two killers is roles off");
        // A party chat claim overrides the class.
        ctx.runOnClient(mc -> {
            ModUnderTest.staticCall(ROLES_CLS, "resetForTest");
            ModUnderTest.staticCall(ROLES_CLS, "testClassAndFloor", new Class<?>[]{String.class, Boolean.class},
                    new Object[]{"MAGE", true});
            ModUnderTest.call(ModUnderTest.config(BR_CFG), "setKillers", new Class<?>[]{int.class}, new Object[]{2});
        });
        say(ctx, "Party > " + self + ": !br 1");
        String over = roles(ctx);
        facts.add("Mage, then '!br 1' from him: " + over);
        check(problems, over, "mine=1", "a party claim overrides the class role");
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROLES_CLS, "testClassAndFloor", new Class<?>[]{String.class,
                Boolean.class}, new Object[]{null, null}));
        // The blood door opens: every box goes.
        say(ctx, "The BLOOD DOOR has been opened!");
        List<String> end = spots(ctx);
        facts.add("after the blood door line: " + end.size() + " box(es); " + status(ctx));
        if (!end.isEmpty()) {
            problems.add("boxes still shown after the blood door opened: " + end);
        }
        Map<String, Integer> learnedAfter = learnedCounts(ctx);
        if (!learnedAfter.equals(learnedBefore)) {
            problems.add("spawn spots were learned in the sim: " + learnedBefore + " -> " + learnedAfter);
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROLES_CLS, "resetForTest"));
    }

    private static void check(List<String> problems, String state, String want, String what) {
        if (state == null || !state.contains(want)) {
            problems.add(what + ": " + state);
        }
    }

    // ---- 1103-bloodrush-split -----------------------------------------------------------------------------------------

    /**
     * The split rule on hand-made rooms, from a door, at all four rotations about the room's middle: the same boxes go to
     * the same roles at every rotation (door-relative, not world-relative); on normal floors role 1 is the region farthest
     * from the door and the regions differ by at most one box; in master mode role 1 has the most boxes; inside a role the
     * order is nearest the door first, then nearest the last; a one-box room is every role's.
     */
    @SuppressWarnings("unchecked")
    private static void splitRule(ClientGameTestContext ctx, List<String> problems, List<String> facts) {
        // Room A (a 1x1, door on the south wall): a far-left cluster, a far-right pair, a near-right pair, one near-left.
        double[][] a = {{4.3, 4.1}, {6.2, 5.4}, {5.1, 7.7}, {26.4, 4.6}, {28.1, 6.3}, {25.7, 25.2}, {27.4, 23.6}, {5.6, 24.9}};
        // Room B (a 1x2 lying east-west, door on the west wall): boxes along the length and both sides.
        double[][] b = {{3.2, 6.1}, {9.7, 25.3}, {18.4, 4.2}, {24.6, 27.1}, {33.3, 15.8}, {41.2, 5.7}, {47.9, 26.4}, {56.1, 15.2},
                {60.3, 8.8}};
        Object[][] rooms = {{"A", a, new double[]{16, 31.5}, new double[]{16, 16}}, {"B", b, new double[]{0.5, 16}, new double[]{32, 16}}};
        for (Object[] r : rooms) {
            double[][] pts = (double[][]) r[1];
            double[] door = (double[]) r[2];
            double[] mid = (double[]) r[3];
            for (boolean master : new boolean[]{false, true}) {
                for (int n = 2; n <= 3; n++) {
                    List<List<Integer>> base = null;
                    for (int rot = 0; rot < 4; rot++) {
                        double[][] p = new double[pts.length][];
                        for (int i = 0; i < pts.length; i++) {
                            p[i] = turn(pts[i], mid, rot);
                        }
                        double[] d = turn(door, mid, rot);
                        final int nn = n;
                        final boolean mm = master;
                        List<List<Integer>> got = ctx.computeOnClient(mc -> (List<List<Integer>>) ModUnderTest.staticCall(BR,
                                "splitForTest", new Class<?>[]{double[][].class, double.class, double.class, int.class,
                                        boolean.class}, new Object[]{p, d[0], d[1], nn, mm}));
                        String tag = r[0] + (master ? " M7" : " normal") + " n=" + n + " rot " + (rot * 90);
                        if (rot == 0) {
                            facts.add(tag + ": " + got);
                            base = got;
                            judge(problems, tag, got, p, d, master, n);
                        } else if (!sets(got).equals(sets(base))) {
                            problems.add(tag + ": " + got + " differs from rotation 0 " + base);
                        }
                    }
                }
            }
        }
        // One box: every role kills it.
        List<List<Integer>> one = ctx.computeOnClient(mc -> (List<List<Integer>>) ModUnderTest.staticCall(BR, "splitForTest",
                new Class<?>[]{double[][].class, double.class, double.class, int.class, boolean.class},
                new Object[]{new double[][]{{10.5, 10.5}}, 16.0, 31.5, 3, false}));
        facts.add("one box, three killers: " + one);
        if (one.size() != 3 || one.stream().anyMatch(l -> !l.equals(List.of(0)))) {
            problems.add("a one-box room is not every role's: " + one);
        }
    }

    private static List<Set<Integer>> sets(List<List<Integer>> roles) {
        List<Set<Integer>> out = new ArrayList<>();
        for (List<Integer> r : roles) {
            out.add(new HashSet<>(r));
        }
        return out;
    }

    private static double[] turn(double[] p, double[] mid, int quarter) {
        double x = p[0] - mid[0];
        double z = p[1] - mid[1];
        for (int i = 0; i < quarter; i++) {
            double t = x;
            x = -z;
            z = t;
        }
        return new double[]{x + mid[0], z + mid[1]};
    }

    private static void judge(List<String> problems, String tag, List<List<Integer>> got, double[][] p, double[] d,
                              boolean master, int n) {
        if (got.size() != n) {
            problems.add(tag + ": " + got.size() + " roles");
            return;
        }
        Set<Integer> all = new HashSet<>();
        for (List<Integer> r : got) {
            all.addAll(r);
        }
        if (all.size() != p.length) {
            problems.add(tag + ": not every box has a role: " + got);
        }
        double[] mean = new double[n];
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int k : got.get(i)) {
                s += Math.hypot(p[k][0] - d[0], p[k][1] - d[1]);
            }
            mean[i] = got.get(i).isEmpty() ? 0 : s / got.get(i).size();
        }
        if (!master) {
            for (int i = 1; i < n; i++) {
                if (mean[i] > mean[i - 1] + 1e-6) {
                    problems.add(tag + ": role " + (i + 1) + " is farther from the door than role " + i + " " + got);
                }
            }
            int min = Integer.MAX_VALUE;
            int max = 0;
            for (List<Integer> r : got) {
                min = Math.min(min, r.size());
                max = Math.max(max, r.size());
            }
            if (max - min > 1) {
                problems.add(tag + ": uneven regions " + got);
            }
        } else {
            for (int i = 1; i < n; i++) {
                if (got.get(i).size() > got.get(0).size()) {
                    problems.add(tag + ": role " + (i + 1) + " has more boxes than role 1 " + got);
                }
            }
        }
        for (List<Integer> r : got) {
            double x = d[0];
            double z = d[1];
            List<Integer> left = new ArrayList<>(r);
            for (int k : r) {
                int best = left.get(0);
                for (int o : left) {
                    if (Math.hypot(p[o][0] - x, p[o][1] - z) < Math.hypot(p[best][0] - x, p[best][1] - z) - 1e-9) {
                        best = o;
                    }
                }
                if (best != k) {
                    problems.add(tag + ": kill order " + r + " is not nearest-next from the door");
                    break;
                }
                left.remove(Integer.valueOf(k));
                x = p[k][0];
                z = p[k][1];
            }
        }
    }

    private static String status(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(BR, "statusForTest")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> learnedCounts(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> new HashMap<>((Map<String, Integer>) ModUnderTest.staticCall(BR_SPOTS, "counts")));
    }

    /** A spot's box exactly as the feature draws it. */
    private static AABB boxOf(ClientGameTestContext ctx, Spot s) {
        return s.box();
    }

    /** Every starred mob (not a Fel) the sim server has alive in {@code room}: {x, y, z}. */
    private static List<double[]> serverStarredIn(ClientGameTestContext ctx, String room) {
        @SuppressWarnings("unchecked")
        List<UUID[]> pairs = ctx.computeOnClient(mc -> (List<UUID[]>) ModUnderTest.staticCall(SIM_MOBS, "starredPairs"));
        List<double[]> all = onServer(ctx, (level, sp) -> {
            List<double[]> out = new ArrayList<>();
            for (UUID[] p : pairs) {
                Entity e = level.getEntity(p[0]);
                if (e != null && e.isAlive() && !dev.testkit.compat.McEntities.endermanClass().isInstance(e)) {
                    out.add(new double[]{e.getX(), e.getY(), e.getZ()});
                }
            }
            return out;
        });
        List<double[]> out = new ArrayList<>();
        ctx.runOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "current");
            for (double[] m : all) {
                int r = (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class, double.class},
                        new Object[]{m[0], m[2]});
                if (room.equals(ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r}))) {
                    out.add(m);
                }
            }
        });
        return out;
    }

    // ---- 1103-sim-starredspots ----------------------------------------------------------------------------------------

    private static void starredSpots(ClientGameTestContext ctx, List<String> problems, List<String> facts) {
        ctx.runOnClient(mc -> {
            ModUnderTest.staticCall(BR_SPOTS, "resetForTest", new Class<?>[]{boolean.class}, new Object[]{false});
            setSpawnStarred(true);
            // This case checks placement on the spots it seeds, so the jar's shipped table (bloodrush2-1104) is emptied;
            // an older jar has no table and no hook.
            try {
                @SuppressWarnings("unchecked")
                Map<String, Integer> shipped = (Map<String, Integer>) ModUnderTest.staticCall(BR_SPOTS, "bundledCounts");
                for (String room : new ArrayList<>(shipped.keySet())) {
                    ModUnderTest.staticCall(BR_SPOTS, "putBundledForTest", new Class<?>[]{String.class, List.class},
                            new Object[]{room, new ArrayList<int[]>()});
                }
            } catch (Throwable t) {
                // no shipped table in this jar
            }
        });
        long mark = LogTap.mark();
        startFloor(ctx);
        // Before GO (3 s countdown): seed every mob room the map has placed with spots from its own secret positions.
        Map<String, List<BlockPos>> expected = new HashMap<>();
        Map<String, Integer> rotation = new HashMap<>();
        for (int i = 0; i < 40 && expected.isEmpty(); i++) {
            ctx.waitTicks(2);
            ctx.runOnClient(mc -> seed(expected, rotation));
        }
        boolean running = false;
        for (int i = 0; i < 400 && !running; i++) {
            ctx.waitTicks(1);
            running = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_RUN, "isRunning"));
        }
        String placedLine = null;
        for (int i = 0; i < 200 && placedLine == null; i += 5) {
            ctx.waitTicks(5);
            placedLine = LogTap.since(mark).stream().filter(l -> l.contains("[Sim] live mobs placed:")).findFirst().orElse(null);
        }
        facts.add("seeded " + expected.size() + " room(s) before GO; running " + running + "; " + placedLine);
        if (placedLine == null) {
            problems.add("no mobs were placed at GO");
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, List<BlockPos>> placed = ctx.computeOnClient(mc ->
                (Map<String, List<BlockPos>>) ModUnderTest.staticCall(SIM_LIVE, "learnedSpotsPlaced"));
        Set<Integer> rotations = new HashSet<>();
        int checkedRooms = 0;
        for (Map.Entry<String, List<BlockPos>> e : placed.entrySet()) {
            List<BlockPos> want = expected.get(e.getKey());
            if (want == null) {
                problems.add(e.getKey() + " placed on learned spots it was never given");
                continue;
            }
            for (BlockPos p : e.getValue()) {
                boolean match = want.stream().anyMatch(w -> w.getX() == p.getX() && w.getZ() == p.getZ()
                        && Math.abs(w.getY() - p.getY()) <= 2);
                if (!match) {
                    problems.add(e.getKey() + " (rotation " + rotation.get(e.getKey()) + "): placed " + p.toShortString()
                            + " is none of its spots " + want);
                }
            }
            // The server's starred mobs in that room (not Fels) stand on those blocks.
            List<double[]> mobs = serverStarredIn(ctx, e.getKey());
            int on = 0;
            for (double[] m : mobs) {
                boolean hit = e.getValue().stream().anyMatch(p -> Math.abs(p.getX() + 0.5 - m[0]) < 0.6
                        && Math.abs(p.getZ() + 0.5 - m[2]) < 0.6 && Math.abs(p.getY() - m[1]) < 1.5);
                if (hit) {
                    on++;
                }
            }
            facts.add(String.format(Locale.ROOT, "%s rot %s: %d spot(s) given, %d placed on them, %d/%d server mobs on them",
                    e.getKey(), rotation.get(e.getKey()), want.size(), e.getValue().size(), on, mobs.size()));
            if (on != mobs.size() || mobs.isEmpty()) {
                problems.add(e.getKey() + ": " + on + " of " + mobs.size() + " starred mobs stand on its learned spots");
            }
            rotations.add(rotation.get(e.getKey()));
            checkedRooms++;
        }
        facts.add(checkedRooms + " room(s) on learned spots, rotations " + rotations);
        if (checkedRooms < 3) {
            problems.add("only " + checkedRooms + " room(s) got learned spots - fewer than three");
        }
        if (rotations.size() < 2) {
            problems.add("learned spots were checked at only " + rotations.size() + " rotation(s)");
        }
    }

    /** Client thread: for each mob room with a clay corner and rotation, up to three room-relative spots from its item and
     *  chest secrets; the expected world blocks are RoomDatabase.toRealCoord's, plus the sim's height shift. */
    private static void seed(Map<String, List<BlockPos>> expected, Map<String, Integer> rotation) {
        Object layout = ModUnderTest.staticCall(LAYOUT, "current");
        int n = (Integer) ModUnderTest.call(layout, "roomCount", new Class<?>[0], new Object[0]);
        int shift = (Integer) ModUnderTest.staticCall(LAYOUT, "simYOffset");
        for (int r = 0; r < n; r++) {
            String name = (String) ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r});
            Object entry = ModUnderTest.call(layout, "entry", new Class<?>[]{int.class}, new Object[]{r});
            int[] cr = (int[]) ModUnderTest.call(layout, "clayRotation", new Class<?>[]{int.class}, new Object[]{r});
            if (name == null || entry == null || cr == null || expected.containsKey(name)) {
                continue;
            }
            try {
                String type = String.valueOf(entry.getClass().getField("type").get(entry)).toUpperCase(Locale.ROOT);
                if (!type.equals("NORMAL") && !type.equals("RARE") && !type.equals("CHAMPION")) {
                    continue;
                }
                Object coords = entry.getClass().getField("secretCoords").get(entry);
                if (coords == null) {
                    continue;
                }
                List<Object> pos = new ArrayList<>();
                for (String f : new String[]{"item", "chest"}) {
                    Object l = coords.getClass().getField(f).get(coords);
                    if (l instanceof List<?> list) {
                        pos.addAll(list);
                    }
                }
                if (pos.isEmpty()) {
                    continue;
                }
                List<int[]> rel = new ArrayList<>();
                List<BlockPos> world = new ArrayList<>();
                for (Object p : pos.subList(0, Math.min(3, pos.size()))) {
                    int x = p.getClass().getField("x").getInt(p);
                    int y = p.getClass().getField("y").getInt(p);
                    int z = p.getClass().getField("z").getInt(p);
                    rel.add(new int[]{x, y, z});
                    BlockPos w = (BlockPos) ModUnderTest.staticCall(DB, "toRealCoord", new Class<?>[]{p.getClass(), int.class,
                            int.class, int.class}, new Object[]{p, cr[0], cr[1], cr[2]});
                    world.add(w.offset(0, shift, 0));
                }
                ModUnderTest.staticCall(BR_SPOTS, "putForTest", new Class<?>[]{String.class, List.class},
                        new Object[]{name, rel});
                expected.put(name, world);
                rotation.put(name, cr[2]);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("room entry fields", e);
            }
        }
    }

    /** A new F7 sim floor, generated: the floor follows the Spawn Starred Mobs choice already set. */
    private static void startFloor(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class}, new Object[]{"gametest"}));
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            ModUnderTest.staticCall(FLOOR_GEN, "generate", new Class<?>[]{Minecraft.class, floor.getClass(), int.class,
                    int.class}, new Object[]{mc, floor, 2, 6});
        }));
        ctx.waitFor(mc -> mc.level != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
    }

    // ---- 1103-sim-starredoff ------------------------------------------------------------------------------------------

    private static void starredOff(ClientGameTestContext ctx, List<String> problems, List<String> facts) {
        ctx.runOnClient(mc -> setSpawnStarred(false));
        boolean savedOff = ctx.computeOnClient(mc -> {
            Object prefs = ModUnderTest.staticCall(PREFS, "load");
            try {
                return !prefs.getClass().getField("mobs").getBoolean(prefs);
            } catch (ReflectiveOperationException e) {
                return false;
            }
        });
        long mark = LogTap.mark();
        startFloor(ctx);
        boolean running = false;
        for (int i = 0; i < 400 && !running; i++) {
            ctx.waitTicks(1);
            running = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_RUN, "isRunning"));
        }
        ctx.waitTicks(100);
        int starred = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_MOBS, "starredCount"));
        boolean placed = LogTap.since(mark).stream().anyMatch(l -> l.contains("[Sim] live mobs placed:"));
        boolean mobsOn = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_FLOOR, "isEnabled"));
        facts.add("Spawn Starred Mobs saved off " + savedOff + ", sim mobs " + mobsOn + "; running " + running
                + "; starred spawned " + starred + "; 'live mobs placed' line " + placed);
        if (!running) {
            problems.add("the floor never started (nothing to judge)");
        }
        if (!savedOff || mobsOn || starred != 0 || placed) {
            problems.add("Spawn Starred Mobs off still spawned starred mobs (" + starred + ", placed line " + placed + ")");
        }
    }

    // ---- 1104-bloodrush-known -----------------------------------------------------------------------------------------

    private static final String BR_KNOWN_WHY = "bloodrush2-1104 (killer560 2026-11-03: \"the blood rush helper boxes arent really "
            + "loading in properly or at all and it is really hard to see them\")";

    private static void setStatic(String cls, String field, boolean v) {
        try {
            Class.forName(cls).getField(field).setBoolean(null, v);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no " + cls + "." + field + " (old jar?)", e);
        }
    }

    private static int[] project(Minecraft mc, Vec3 p) {
        var cam = mc.gameRenderer.mainCamera();
        Vec3 d = p.subtract(cam.position());
        var fw = cam.forwardVector();
        var up = cam.upVector();
        var left = cam.leftVector();
        double depth = d.x * fw.x() + d.y * fw.y() + d.z * fw.z();
        if (depth <= 0.05) {
            return null;
        }
        double u = -(d.x * left.x() + d.y * left.y() + d.z * left.z());
        double v = d.x * up.x() + d.y * up.y() + d.z * up.z();
        double fpx = (mc.getWindow().getHeight() / 2.0) / Math.tan(Math.toRadians(cam.getFov()) / 2.0);
        return new int[]{(int) Math.round(mc.getWindow().getWidth() / 2.0 + u / depth * fpx),
                (int) Math.round(mc.getWindow().getHeight() / 2.0 - v / depth * fpx)};
    }

    /** The screen rect (window pixels) the box's eight corners project into, inflated by 8, or null if any is behind. */
    /** Client thread: no collision shape at {@code p}. */
    private static boolean clear(Minecraft mc, BlockPos p) {
        return mc.level.getBlockState(p).getCollisionShape(mc.level, p).isEmpty();
    }

    private static int[] rectOf(ClientGameTestContext ctx, AABB b) {
        return ctx.computeOnClient(mc -> {
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
            for (int i = 0; i < 8; i++) {
                int[] p = project(mc, new Vec3((i & 1) == 0 ? b.minX : b.maxX, (i & 2) == 0 ? b.minY : b.maxY,
                        (i & 4) == 0 ? b.minZ : b.maxZ));
                if (p == null) {
                    return null;
                }
                x0 = Math.min(x0, p[0]);
                y0 = Math.min(y0, p[1]);
                x1 = Math.max(x1, p[0]);
                y1 = Math.max(y1, p[1]);
            }
            return new int[]{x0 - 8, y0 - 8, x1 + 8, y1 + 8, mc.getWindow().getWidth(), mc.getWindow().getHeight()};
        });
    }

    private static java.awt.image.BufferedImage shot(ClientGameTestContext ctx, String tag) {
        ctx.waitTicks(6);
        java.nio.file.Path p = ctx.takeScreenshot(dev.testkit.harness.Report.fileName("1104-bloodrush-known-" + tag));
        try {
            return javax.imageio.ImageIO.read(p.toFile());
        } catch (java.io.IOException e) {
            throw new AssertionError("screenshot " + p, e);
        }
    }

    /** Pixels inside {@code r} (window coordinates) that differ by more than 40 in any channel. */
    private static int diff(java.awt.image.BufferedImage a, java.awt.image.BufferedImage b, int[] r) {
        double sx = a.getWidth() / (double) r[4], sy = a.getHeight() / (double) r[5];
        int n = 0;
        for (int y = Math.max(0, (int) (r[1] * sy)); y < Math.min(a.getHeight(), (int) (r[3] * sy)); y++) {
            for (int x = Math.max(0, (int) (r[0] * sx)); x < Math.min(a.getWidth(), (int) (r[2] * sx)); x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                int d = Math.max(Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)),
                        Math.max(Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)), Math.abs((p & 255) - (q & 255))));
                if (d > 40) {
                    n++;
                }
            }
        }
        return n;
    }

    private static void brSet(String setter, boolean v) {
        ModUnderTest.set(ModUnderTest.config(BR_CFG), setter, v);
    }

    private static void brSetInt(String setter, int v) {
        ModUnderTest.call(ModUnderTest.config(BR_CFG), setter, new Class<?>[]{int.class}, new Object[]{v});
    }

    /**
     * 1104-bloodrush-known: (1) the jar's shipped spot table loads, and a room that only it knows (his learned spots
     * forgotten, no mob anywhere: Spawn Starred Mobs off) shows its boxes the moment he is in it; pixels: the box
     * changes at least 300 pixels on screen against the helper off, two off shots differ by under 40. (2) Outline Width
     * 8 draws more pixels than 1 (outline only), Filled Boxes adds pixels over outline only. (3) Cheat build: a stone wall
     * between him and the box hides it (depth) and Through Walls shows it again. (4) Settings survive save + load. (5) A
     * floor with mobs, after "The BLOOD DOOR has been opened!": mobs first seen with nobody near are learned (the clear
     * teaches spots too, not only the rush), each learned spot on a server starred mob's block.
     */
    private static void known(ClientGameTestContext ctx, List<String> problems, List<String> facts) {
        String[] floorRoom = new String[1];
        try {
            knownPixels(ctx, problems, facts, floorRoom);
        } finally {
            ctx.runOnClient(mc -> dev.testkit.compat.McCompat.setHudHidden(mc, false));
        }
        if (floorRoom[0] != null) {
            learnAfterBlood(ctx, problems, facts, floorRoom[0]);
        }
    }

    /** Parts (1)-(4); {@code floorRoom[0]} is set once the mob floor stands (to the room whose mobs it hid, or ""). */
    private static void knownPixels(ClientGameTestContext ctx, List<String> problems, List<String> facts, String[] floorRoom) {
        facts.add(BR_KNOWN_WHY);
        ctx.runOnClient(mc -> {
            ModUnderTest.staticCall(BR_SPOTS, "resetForTest", new Class<?>[]{boolean.class}, new Object[]{false});
        });
        Map<String, Integer> shipped = null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Integer> got = ctx.computeOnClient(mc -> new HashMap<>((Map<String, Integer>) ModUnderTest.staticCall(BR_SPOTS,
                    "bundledCounts")));
            shipped = got;
        } catch (Throwable t) {
            problems.add("no shipped spawn spot table (BloodRushSpots.bundledCounts: " + t + ")");
        }
        facts.add("shipped table: " + shipped);
        if (shipped != null && shipped.isEmpty()) {
            problems.add("the shipped spawn spot table is empty");
        }
        // A floor WITH mobs: on a mobless floor every mob room clears as he walks in, and the helper rightly hides it.
        List<String> floorProblems = new ArrayList<>();
        if (!SimMobFloorTests.buildMobFloor(ctx, floorProblems, facts)) {
            problems.add("no floor with mobs: " + floorProblems);
            return;
        }
        floorRoom[0] = "";
        // A mob room on this floor: a shipped one if the floor has it, else its own secret spots stand in as shipped.
        Map<String, List<BlockPos>> expected = new HashMap<>();
        Map<String, Integer> rotation = new HashMap<>();
        final Map<String, Integer> table = shipped == null ? Map.of() : shipped;
        String room = ctx.computeOnClient(mc -> {
            seed(expected, rotation);
            for (String n : expected.keySet()) {
                if (table.containsKey(n)) {
                    return n;
                }
            }
            return expected.keySet().stream().sorted().findFirst().orElse(null);
        });
        // seed() put secret spots in as LEARNED; forget those and hand the chosen room's to the shipped table instead.
        ctx.runOnClient(mc -> ModUnderTest.staticCall(BR_SPOTS, "resetForTest", new Class<?>[]{boolean.class},
                new Object[]{false}));
        if (room == null) {
            problems.add("no mob room on the floor");
            return;
        }
        boolean fromJar = table.containsKey(room);
        if (!fromJar) {
            try {
                List<int[]> rel = new ArrayList<>();
                ctx.runOnClient(mc -> {
                    Map<String, List<BlockPos>> e2 = new HashMap<>();
                    seed(e2, new HashMap<>());
                    @SuppressWarnings("unchecked")
                    List<int[]> sp = (List<int[]>) ModUnderTest.staticCall(BR_SPOTS, "spots", new Class<?>[]{String.class},
                            new Object[]{room});
                    for (int[] s : sp) {
                        rel.add(new int[]{s[0], s[1], s[2]});
                    }
                    ModUnderTest.staticCall(BR_SPOTS, "resetForTest", new Class<?>[]{boolean.class}, new Object[]{false});
                    ModUnderTest.staticCall(BR_SPOTS, "putBundledForTest", new Class<?>[]{String.class, List.class},
                            new Object[]{room, rel});
                });
            } catch (Throwable t) {
                problems.add("could not give " + room + " shipped spots (old jar?): " + t);
            }
        }
        facts.add("room " + room + " (rotation " + rotation.get(room) + "), spots " + (fromJar ? "from the jar's table" : "put in the shipped table")
                + "; learned now " + learnedCounts(ctx));
        ctx.runOnClient(mc -> {
            ModUnderTest.staticCall(BR, "resetForTest");
            ModUnderTest.staticCall(ROLES_CLS, "resetForTest");
            Object cfg = ModUnderTest.config(BR_CFG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setAllRooms", true);
            ModUnderTest.set(cfg, "setShowLearned", true);
            ModUnderTest.set(cfg, "setThroughWalls", false);
            ModUnderTest.set(cfg, "setWholePath", false);
            ModUnderTest.call(cfg, "setKillers", new Class<?>[]{int.class}, new Object[]{1});
            dev.testkit.compat.McCompat.setHudHidden(mc, true);
        });
        try {
            brSet("setFilled", true);
            brSetInt("setOutlineWidth", 4);
            brSetInt("setFillOpacity", 25);
        } catch (Throwable t) {
            problems.add("no Filled Boxes / Outline Width settings (old jar?): " + t);
        }
        // Hide the room's starred mobs from the helper: every star tag in the room goes (so no starred mob is in view), and
        // each mob is lifted 30 blocks, invisible and frozen. They stay alive, so the room is not cleared and the helper
        // keeps showing it; any box in it now comes from known spots alone.
        @SuppressWarnings("unchecked")
        List<UUID[]> pairs = ctx.computeOnClient(mc -> (List<UUID[]>) ModUnderTest.staticCall(SIM_MOBS, "starredPairs"));
        List<double[]> where = onServer(ctx, (level, sp) -> {
            List<double[]> out = new ArrayList<>();
            for (int i = 0; i < pairs.size(); i++) {
                Entity e = level.getEntity(pairs.get(i)[0]);
                if (e != null && e.isAlive()) {
                    out.add(new double[]{i, e.getX(), e.getZ()});
                }
            }
            return out;
        });
        List<Integer> mine = ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "current");
            List<Integer> out = new ArrayList<>();
            for (double[] w : where) {
                int r = (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class, double.class},
                        new Object[]{w[1], w[2]});
                if (room.equals(ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r}))) {
                    out.add((int) w[0]);
                }
            }
            return out;
        });
        int lifted = onServer(ctx, (level, sp) -> {
            int n = 0;
            for (int i : mine) {
                Entity tag = level.getEntity(pairs.get(i)[1]);
                if (tag != null) {
                    tag.discard();
                }
                Entity mob = level.getEntity(pairs.get(i)[0]);
                if (mob != null && !dev.testkit.compat.McEntities.endermanClass().isInstance(mob)) {
                    if (mob instanceof net.minecraft.world.entity.Mob m) {
                        m.setNoAi(true);
                    }
                    mob.setNoGravity(true);
                    mob.setInvisible(true);
                    mob.snapTo(mob.getX(), mob.getY() + 30, mob.getZ(), 0f, 0f);
                    n++;
                }
            }
            return n;
        });
        floorRoom[0] = room;
        facts.add("hid " + mine.size() + " star tag(s) in " + room + ", lifted " + lifted + " mob(s)");
        // Stand in the room, then find one of its boxes with a clear view 4-7 blocks off.
        BlockPos s0 = expected.get(room).get(0);
        SimMobFloorTests.teleport(ctx, s0.getX() + 0.5, s0.getY(), s0.getZ() + 0.5);
        ctx.waitTicks(20);
        List<Spot> shown = parsed(ctx);
        facts.add("standing at " + s0.toShortString() + " in " + room + ": " + spots(ctx) + "; " + status(ctx));
        List<AABB> candidates = new ArrayList<>();
        for (Spot s : shown) {
            if (s.room().equals(room)) {
                candidates.add(s.box());
            }
        }
        if (candidates.isEmpty()) {
            problems.add("no box in " + room + " with no starred mob in it (its known spots did not show)");
            return;
        }
        // view: feet x, y, z, then the box index; wall: the blocks between his eye and the box (1.2-2.8 blocks out).
        double[] view = ctx.computeOnClient(mc -> {
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
            int[] dists = {5, 4, 6, 7};
            int[] dys = {0, -1, 1, -2, 2};
            for (int bi = 0; bi < candidates.size(); bi++) {
                AABB b = candidates.get(bi);
                Vec3 c = b.getCenter();
                int bx = (int) Math.floor(c.x), bz = (int) Math.floor(c.z), by = (int) Math.floor(b.minY);
                for (int dist : dists) {
                    for (int[] d : dirs) {
                        for (int dy : dys) {
                            BlockPos f = new BlockPos(bx + d[0] * dist, by + dy, bz + d[1] * dist);
                            if (!clear(mc, f) || !clear(mc, f.above()) || clear(mc, f.below())) {
                                continue;
                            }
                            Vec3 eye = new Vec3(f.getX() + 0.5, f.getY() + 1.62, f.getZ() + 0.5);
                            boolean seen = true;
                            for (int k = 1; k < 48 && seen; k++) {
                                Vec3 p = eye.lerp(c, k / 48.0);
                                BlockPos q = BlockPos.containing(p);
                                if (!b.inflate(0.5).contains(p)) {
                                    seen = clear(mc, q);
                                }
                            }
                            if (seen) {
                                return new double[]{f.getX() + 0.5, f.getY(), f.getZ() + 0.5, bi};
                            }
                        }
                    }
                }
            }
            return null;
        });
        if (view == null) {
            problems.add("no clear view point 4-7 blocks from any of " + room + "'s " + candidates.size() + " box(es): " + candidates);
            return;
        }
        final AABB box = candidates.get((int) view[3]);
        SimMobFloorTests.teleport(ctx, view[0], view[1], view[2]);
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> {
            Vec3 eye = mc.player.getEyePosition();
            Vec3 c = box.getCenter();
            double dx = c.x - eye.x, dy = c.y - eye.y, dz = c.z - eye.z;
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) Math.max(-89, Math.min(89, -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)))));
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
            mc.player.yRotO = yaw;
            mc.player.xRotO = pitch;
        });
        ctx.waitTicks(10);
        int[] r = rectOf(ctx, box);
        facts.add(String.format(Locale.ROOT, "view from %.1f %.1f %.1f, box %s on screen at %s", view[0], view[1], view[2], box,
                r == null ? "null" : r[0] + "," + r[1] + "-" + r[2] + "," + r[3]));
        if (r == null) {
            problems.add("the box is not in front of the camera");
            return;
        }
        java.awt.image.BufferedImage on = shot(ctx, "on");
        ctx.runOnClient(mc -> brSet("setEnabled", false));
        java.awt.image.BufferedImage off1 = shot(ctx, "off1");
        java.awt.image.BufferedImage off2 = shot(ctx, "off2");
        int boxPx = diff(on, off1, r);
        int noise = diff(off1, off2, r);
        facts.add("PIXELS box on vs off " + boxPx + ", off vs off " + noise);
        if (boxPx < 300 || noise >= 40) {
            problems.add("the box is not plainly visible: " + boxPx + " pixels against the helper off (want 300+), noise " + noise);
        }
        ctx.runOnClient(mc -> brSet("setEnabled", true));
        try {
            brSet("setFilled", false);
            brSetInt("setOutlineWidth", 1);
            int w1 = diff(shot(ctx, "w1"), off1, r);
            brSetInt("setOutlineWidth", 8);
            int w8 = diff(shot(ctx, "w8"), off1, r);
            brSet("setFilled", true);
            brSetInt("setOutlineWidth", 1);
            int f1 = diff(shot(ctx, "fill-w1"), off1, r);
            facts.add("PIXELS outline only width 1: " + w1 + ", width 8: " + w8 + "; filled width 1: " + f1);
            if (w8 < w1 * 2 || w1 < 20) {
                problems.add("Outline Width 8 (" + w8 + " px) is not plainly thicker than 1 (" + w1 + " px)");
            }
            if (f1 < w1 + 300) {
                problems.add("Filled Boxes adds only " + (f1 - w1) + " pixels over outline only");
            }
            brSetInt("setOutlineWidth", 4);
        } catch (Throwable t) {
            problems.add("outline/fill shots: " + t);
        }
        // Depth, and Through Walls (cheat build): stone filling the line of sight 1.2-2.8 blocks out from his eye.
        boolean cheat = ctx.computeOnClient(mc -> {
            brSet("setThroughWalls", true);
            boolean c = ModUnderTest.getBoolean(ModUnderTest.config(BR_CFG), "isThroughWalls");
            brSet("setThroughWalls", false);
            return c;
        });
        List<BlockPos> wall = ctx.computeOnClient(mc -> {
            Vec3 eye = mc.player.getEyePosition();
            Vec3 dir = box.getCenter().subtract(eye).normalize();
            AABB me = mc.player.getBoundingBox().inflate(0.3);
            List<BlockPos> out = new ArrayList<>();
            BlockPos e = BlockPos.containing(eye);
            for (BlockPos p : BlockPos.betweenClosed(e.offset(-4, -4, -4), e.offset(4, 4, 4))) {
                Vec3 v = Vec3.atCenterOf(p).subtract(eye);
                double along = v.dot(dir);
                double off = v.subtract(dir.scale(along)).length();
                if (along >= 1.2 && along <= 2.8 && off <= 1.8 && !me.intersects(new AABB(p))
                        && !box.inflate(0.5).intersects(new AABB(p)) && clear(mc, p)) {
                    out.add(p.immutable());
                }
            }
            return out;
        });
        onServer(ctx, (level, sp) -> {
            for (BlockPos p : wall) {
                level.setBlockAndUpdate(p, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            }
            return null;
        });
        ctx.waitTicks(10);
        java.awt.image.BufferedImage wallOff;
        ctx.runOnClient(mc -> brSet("setEnabled", false));
        wallOff = shot(ctx, "wall-off");
        ctx.runOnClient(mc -> brSet("setEnabled", true));
        int hidden = diff(shot(ctx, "wall-on"), wallOff, r);
        String walls = "PIXELS behind a " + wall.size() + "-block wall, depth-tested: " + hidden;
        if (hidden >= 40) {
            problems.add("the box shows through a stone wall without Through Walls (" + hidden + " px)");
        }
        if (cheat) {
            ctx.runOnClient(mc -> brSet("setThroughWalls", true));
            int through = diff(shot(ctx, "wall-through"), wallOff, r);
            ctx.runOnClient(mc -> brSet("setThroughWalls", false));
            walls += ", Through Walls: " + through;
            if (through < 300) {
                problems.add("Through Walls does not show the box behind the wall (" + through + " px)");
            }
        } else {
            walls += " (legit build: Through Walls is off whatever is saved)";
        }
        facts.add(walls);
        onServer(ctx, (level, sp) -> {
            for (BlockPos p : wall) {
                level.setBlockAndUpdate(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            }
            return null;
        });
        // The new settings survive a save and a load (what a restart and ProfileManager.reloadAllConfigs do).
        try {
            String kept = ctx.computeOnClient(mc -> {
                Object cfg = ModUnderTest.config(BR_CFG);
                ModUnderTest.set(cfg, "setFilled", false);
                ModUnderTest.call(cfg, "setOutlineWidth", new Class<?>[]{int.class}, new Object[]{7});
                ModUnderTest.call(cfg, "save", new Class<?>[0], new Object[0]);
                ModUnderTest.staticCall(BR_CFG, "load");
                Object again = ModUnderTest.config(BR_CFG);
                String out = "filled=" + ModUnderTest.getBoolean(again, "isFilled") + " width="
                        + ModUnderTest.getInt(again, "getOutlineWidth");
                ModUnderTest.set(again, "setFilled", true);
                ModUnderTest.call(again, "setOutlineWidth", new Class<?>[]{int.class}, new Object[]{4});
                ModUnderTest.call(again, "save", new Class<?>[0], new Object[0]);
                return out;
            });
            facts.add("after save + load: " + kept);
            if (!kept.equals("filled=false width=7")) {
                problems.add("Filled Boxes / Outline Width did not survive save + load: " + kept);
            }
        } catch (Throwable t) {
            problems.add("settings round trip: " + t);
        }
    }

    /** Part (5): the same mob floor, the blood door opened, learning switched on for the sim; spots learned on mob blocks
     *  in a key room other than {@code skip} (whose mobs part 1 hid). */
    private static void learnAfterBlood(ClientGameTestContext ctx, List<String> problems, List<String> facts, String skip) {
        ctx.runOnClient(mc -> {
            ModUnderTest.staticCall(BR_SPOTS, "resetForTest", new Class<?>[]{boolean.class}, new Object[]{false});
            ModUnderTest.staticCall(BR, "resetForTest");
            Object cfg = ModUnderTest.config(BR_CFG);
            // The helper itself OFF: Learn Spawn Spots learns passively (bloodrush2-1104), here after the blood door too.
            ModUnderTest.set(cfg, "setEnabled", false);
            ModUnderTest.set(cfg, "setLearnSpots", true);
        });
        try {
            ctx.runOnClient(mc -> setStatic(BR, "learnInSimForTest", true));
        } catch (Throwable t) {
            problems.add("no learnInSimForTest hook (old jar?): " + t);
            return;
        }
        say(ctx, "The BLOOD DOOR has been opened!");
        ctx.waitTicks(4);
        String st = status(ctx);
        if (!st.contains("learning spawn spots")) {
            problems.add("with the helper off, its status is not 'off (learning spawn spots)': " + st);
        }
        // A mob room whose starred mobs all stand 11+ blocks from some clear standing block of the same room.
        @SuppressWarnings("unchecked")
        List<String> keyRooms = ctx.computeOnClient(mc -> (List<String>) ModUnderTest.staticCall(SIM_FLOOR, "keyRoomsForTest"));
        String room = null;
        double[] stand = null;
        List<double[]> mobs = List.of();
        for (String k : keyRooms) {
            String name = k.split("\\|")[0];
            if (name.equals(skip)) {
                continue;
            }
            List<double[]> ms = serverStarredIn(ctx, name);
            if (ms.size() < 2) {
                continue;
            }
            double[] got = ctx.computeOnClient(mc -> {
                double[] m0 = ms.get(0);
                Object layout = ModUnderTest.staticCall(LAYOUT, "current");
                for (int dist = 12; dist <= 20; dist += 2) {
                    for (int a = 0; a < 16; a++) {
                        double x = m0[0] + dist * Math.cos(a * Math.PI / 8), z = m0[2] + dist * Math.sin(a * Math.PI / 8);
                        int r = (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class, double.class},
                                new Object[]{x, z});
                        if (!name.equals(ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r}))) {
                            continue;
                        }
                        boolean far = true;
                        for (double[] m : ms) {
                            far &= Math.hypot(m[0] - x, m[2] - z) >= 11.5;
                        }
                        BlockPos f = BlockPos.containing(x, m0[1], z);
                        if (far && mc.level.getBlockState(f).getCollisionShape(mc.level, f).isEmpty()
                                && mc.level.getBlockState(f.above()).getCollisionShape(mc.level, f.above()).isEmpty()
                                && !mc.level.getBlockState(f.below()).getCollisionShape(mc.level, f.below()).isEmpty()) {
                            return new double[]{f.getX() + 0.5, f.getY(), f.getZ() + 0.5};
                        }
                    }
                }
                return null;
            });
            if (got != null) {
                room = name;
                stand = got;
                mobs = ms;
                break;
            }
        }
        facts.add("after the blood door line: " + st + "; learning room " + room + " with " + mobs.size() + " starred mob(s)");
        if (room == null) {
            problems.add("no key room with a standing spot 11+ blocks from all its mobs (" + keyRooms + ")");
            return;
        }
        final String learnRoom = room;
        SimMobFloorTests.teleport(ctx, stand[0], stand[1], stand[2]);
        Map<String, Integer> learned = Map.of();
        for (int i = 0; i < 100; i += 5) {
            ctx.waitTicks(5);
            learned = learnedCounts(ctx);
            if (learned.getOrDefault(learnRoom, 0) >= 2) {
                break;
            }
        }
        int pending = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(BR, "learningForTest"));
        facts.add("learned after the blood door: " + learned + " (waiting " + pending + ")");
        if (learned.getOrDefault(learnRoom, 0) < 1) {
            problems.add("no spot learned in " + learnRoom + " after the blood door opened");
            return;
        }
        // Each learned spot of the room is the block a server starred mob stands on (wanderers may have moved: 1 block).
        List<double[]> nowMobs = serverStarredIn(ctx, learnRoom);
        @SuppressWarnings("unchecked")
        List<BlockPos> world = ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "current");
            int n = (Integer) ModUnderTest.call(layout, "roomCount", new Class<?>[0], new Object[0]);
            for (int r = 0; r < n; r++) {
                if (learnRoom.equals(ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r}))) {
                    int[] cr = (int[]) ModUnderTest.call(layout, "clayRotation", new Class<?>[]{int.class}, new Object[]{r});
                    try {
                        Class<?> fc = Class.forName("com.killer560.hub.autoroutes.RouteCoords$Frame");
                        Object frame = fc.getConstructor(String.class, int.class, int.class, int.class)
                                .newInstance(learnRoom, cr[0], cr[1], cr[2]);
                        return (List<BlockPos>) ModUnderTest.staticCall(BR_SPOTS, "worldSpots", new Class<?>[]{String.class, fc},
                                new Object[]{learnRoom, frame});
                    } catch (ReflectiveOperationException e) {
                        throw new AssertionError("RouteCoords.Frame", e);
                    }
                }
            }
            return List.of();
        });
        int on = 0;
        for (BlockPos p : world) {
            boolean hit = false;
            for (double[] m : nowMobs) {
                hit |= Math.abs(p.getX() + 0.5 - m[0]) <= 1.5 && Math.abs(p.getZ() + 0.5 - m[2]) <= 1.5
                        && Math.abs(p.getY() - m[1]) <= 1.5;
            }
            if (hit) {
                on++;
            }
        }
        facts.add(on + " of " + world.size() + " learned spot(s) in " + learnRoom + " on a server starred mob: " + world);
        if (on < Math.max(1, world.size() - 1)) {
            problems.add("learned spots in " + learnRoom + " are not on its mobs: " + on + " of " + world.size());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T onServer(ClientGameTestContext ctx,
                                  java.util.function.BiFunction<ServerLevel, net.minecraft.server.level.ServerPlayer, T> f) {
        AtomicReference<Object[]> box = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID id = mc.player.getUUID();
            server.execute(() -> {
                try {
                    box.set(new Object[]{f.apply(server.overworld(), server.getPlayerList().getPlayer(id)), null});
                } catch (Throwable t) {
                    box.set(new Object[]{null, t});
                }
            });
        });
        ctx.waitFor(mc -> box.get() != null, 400);
        if (box.get()[1] != null) {
            throw new AssertionError("server task threw", (Throwable) box.get()[1]);
        }
        return (T) box.get()[0];
    }

    private static void println(String s) {
        System.out.println("[" + SimBloodRushTests.class.getSimpleName() + "] " + s);
    }
}

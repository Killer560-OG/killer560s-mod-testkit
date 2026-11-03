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
 * </ul>
 * Only when named. GrimAC does not apply (the sim's integrated server).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimBloodRushTests implements FabricClientGameTest {

    static final String RUSH = "1103-sim-bloodrush";
    static final String SPOTS = "1103-sim-starredspots";
    static final String OFF = "1103-sim-starredoff";
    static final String SPLIT = "1103-bloodrush-split";
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
        for (String name : List.of(SPLIT, RUSH, SPOTS, OFF)) {
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
                    if (SimMobFloorTests.buildMobFloor(ctx, problems, facts)) {
                        rush(ctx, problems, facts);
                    }
                } else if (name.equals(SPOTS)) {
                    starredSpots(ctx, problems, facts);
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
        // The floor is random: a key room can hold only 2 starred mobs (26.3 legit gate, 2026-11-03), so take the first
        // key room with 3 or more.
        String room = null;
        List<double[]> mobs = List.of();
        for (String k : keyRooms) {
            String r = k.split("\\|")[0];
            List<double[]> m = serverStarredIn(ctx, r);
            if (m.size() >= 3) {
                room = r;
                mobs = m;
                break;
            }
            if (room == null && mobs.isEmpty()) {
                facts.add(r + " has only " + m.size() + " starred mob(s): next key room");
            }
        }
        if (room == null && !keyRooms.isEmpty()) {
            room = keyRooms.get(0).split("\\|")[0];
            mobs = serverStarredIn(ctx, room);
        }
        facts.add("key rooms " + keyRooms + "; into " + room);
        if (room == null) {
            problems.add("no key room on this floor");
            return;
        }
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

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
    private static final String BR = "com.killer560.hub.bloodrush.BloodRushHelperFeature";
    private static final String BR_CFG = "com.killer560.hub.bloodrush.BloodRushConfig";
    private static final String BR_SPOTS = "com.killer560.hub.bloodrush.BloodRushSpots";
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
        for (String name : List.of(RUSH, SPOTS, OFF)) {
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
                if (name.equals(RUSH)) {
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

    /** A spot line "room|order|mine|x0,y0,z0,x1,y1,z1|mobs|alive|learnedOnly|next|label" as its parts; x, y, z its box's
     *  bottom middle. */
    private record Spot(String room, int order, boolean mine, AABB box, double x, double y, double z, int mobs, int alive,
                        boolean next, String label) {
        static Spot of(String line) {
            String[] p = line.split("\\|", -1);
            String[] c = p[3].split(",");
            AABB b = new AABB(Double.parseDouble(c[0]), Double.parseDouble(c[1]), Double.parseDouble(c[2]),
                    Double.parseDouble(c[3]), Double.parseDouble(c[4]), Double.parseDouble(c[5]));
            return new Spot(p[0], Integer.parseInt(p[1]), Boolean.parseBoolean(p[2]), b, (b.minX + b.maxX) / 2, b.minY,
                    (b.minZ + b.maxZ) / 2, Integer.parseInt(p[4]), Integer.parseInt(p[5]), Boolean.parseBoolean(p[7]), p[8]);
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

    private static void rush(ClientGameTestContext ctx, List<String> problems, List<String> facts) {
        Map<String, Integer> learnedBefore = learnedCounts(ctx);
        ctx.runOnClient(mc -> {
            ModUnderTest.staticCall(BR, "resetForTest");
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
        facts.add("in the Entrance: " + atStart.size() + " spot(s); " + status(ctx));
        if (!atStart.isEmpty()) {
            problems.add("CONTROL FAILED: spots in the Entrance: " + atStart);
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
        // Where he lands: on one of the room's starred mobs (the sim's own spot, standable), so spot 1 is that one.
        List<double[]> mobs = serverStarredIn(ctx, room);
        facts.add(room + ": the sim server has " + mobs.size() + " starred mob(s) there");
        if (mobs.size() < 2) {
            problems.add("only " + mobs.size() + " starred mob(s) in " + room + " - nothing to order");
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
        facts.add("hotbar-map rush rooms: " + rushRooms + "; " + status(ctx));
        facts.add("spots in " + room + ": " + spots(ctx));
        if (!rushRooms.contains(room)) {
            problems.add(room + " is not a rush room by the hotbar map (" + rushRooms + ")");
        }
        if (shown.isEmpty()) {
            problems.add("no spot shown in " + room);
            return;
        }
        // Every starred mob the server has in the room stands inside a drawn spot.
        mobs = serverStarredIn(ctx, room);
        List<AABB> boxes = new ArrayList<>();
        for (Spot s : shown) {
            boxes.add(boxOf(ctx, s));
        }
        int inside = 0;
        for (double[] m : mobs) {
            boolean in = false;
            for (AABB b : boxes) {
                in |= b.inflate(0.3).contains(m[0], m[1] + 0.1, m[2]);
            }
            if (in) {
                inside++;
            } else {
                problems.add(String.format(Locale.ROOT, "starred mob at %.1f %.1f %.1f in no spot", m[0], m[1], m[2]));
            }
        }
        facts.add(inside + " of " + mobs.size() + " server starred mobs inside a spot; " + shown.size() + " spot(s)");
        // Numbered 1..n, one next (spot 1), each next the nearest left to the last, from where he landed.
        List<Spot> mine = new ArrayList<>(shown);
        mine.sort((a, b) -> Integer.compare(a.order(), b.order()));
        for (int i = 0; i < mine.size(); i++) {
            if (mine.get(i).order() != i + 1 || !mine.get(i).mine()) {
                problems.add("spot orders are not 1.." + mine.size() + ": " + spots(ctx));
                break;
            }
        }
        long nexts = mine.stream().filter(Spot::next).count();
        if (nexts != 1 || !mine.get(0).next()) {
            problems.add(nexts + " next spot(s); the next should be spot 1 only");
        }
        Vec3 at = new Vec3(land[0], land[1], land[2]);
        if (!boxOf(ctx, mine.get(0)).inflate(0.3).contains(at.x, at.y + 0.1, at.z)) {
            problems.add("spot 1 is not the one he came in on (" + mine.get(0) + ")");
        }
        Vec3 from = at;
        for (int i = 0; i < mine.size(); i++) {
            Spot s = mine.get(i);
            Vec3 c = new Vec3(s.x(), s.y(), s.z());
            for (int j = i + 1; j < mine.size(); j++) {
                Spot o = mine.get(j);
                // The order is by each spot's mob centroid, the line gives its box's middle: up to ~3 blocks apart for a
                // big spot, so only a spot nearer by more than that is out of order.
                if (new Vec3(o.x(), o.y(), o.z()).distanceTo(from) + 3.0 < c.distanceTo(from)) {
                    problems.add("spot " + o.order() + " is nearer to spot " + (i == 0 ? "his entry" : i) + " than spot " + s.order());
                }
            }
            from = c;
        }
        // Kill spot 1's mobs: it goes, and spot 2 is the next.
        if (mine.size() >= 2) {
            AABB first = boxOf(ctx, mine.get(0)).inflate(0.3, 2.5, 0.3);
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
            facts.add("killed " + killed + " entities in spot 1; spots now " + spots(ctx));
            boolean oneGone = after.stream().noneMatch(s -> s.order() == 1);
            Spot nextNow = after.stream().filter(Spot::next).findFirst().orElse(null);
            if (!oneGone) {
                problems.add("spot 1 still shown after its mobs died");
            }
            if (nextNow == null || nextNow.order() != 2) {
                problems.add("after spot 1, the next is " + nextNow + ", not spot 2");
            }
            // Roles: two killers, me the second: only the second run is numbered (from 1), the rest marked R1.
            ctx.runOnClient(mc -> {
                Object cfg = ModUnderTest.config(BR_CFG);
                ModUnderTest.call(cfg, "setKillers", new Class<?>[]{int.class}, new Object[]{2});
                ModUnderTest.call(cfg, "setRole", new Class<?>[]{int.class}, new Object[]{2});
            });
            ctx.waitTicks(10);
            List<Spot> roles = parsed(ctx);
            long mineN = roles.stream().filter(Spot::mine).count();
            long others = roles.stream().filter(s -> !s.mine()).count();
            boolean othersMarked = roles.stream().filter(s -> !s.mine()).allMatch(s -> s.label().startsWith("R1"));
            facts.add("two killers, role 2: " + mineN + " mine, " + others + " others: " + spots(ctx));
            if (roles.size() != after.size() || (after.size() >= 2 && (mineN == 0 || others == 0)) || !othersMarked) {
                problems.add("Killers 2 / My Role 2 did not split the " + after.size() + " spot(s): " + spots(ctx));
            }
            if (roles.stream().filter(Spot::mine).noneMatch(s -> s.order() == 1)) {
                problems.add("role 2's spots are not numbered from 1");
            }
        }
        // The blood door opens: every spot goes.
        onServer(ctx, (level, sp) -> {
            level.getServer().getPlayerList().broadcastSystemMessage(
                    net.minecraft.network.chat.Component.literal("The BLOOD DOOR has been opened!"), false);
            return null;
        });
        ctx.waitTicks(10);
        List<String> end = spots(ctx);
        facts.add("after the blood door line: " + end.size() + " spot(s); " + status(ctx));
        if (!end.isEmpty()) {
            problems.add("spots still shown after the blood door opened: " + end);
        }
        Map<String, Integer> learnedAfter = learnedCounts(ctx);
        if (!learnedAfter.equals(learnedBefore)) {
            problems.add("spawn spots were learned in the sim: " + learnedBefore + " -> " + learnedAfter);
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

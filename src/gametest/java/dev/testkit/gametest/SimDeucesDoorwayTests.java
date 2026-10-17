package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 1433-sim-deuces-doorway: the sim's Deuces doorway looks like Hypixel's (mod sim-deuces-1015, 2026-10-15).
 *
 * <p>killer560's Hypixel screenshot from inside Deuces, looking west and up at the doorway into Altar: a tall stone-brick
 * archway with the secret chest set in the wall high above it, two lit braziers (hoppers with fire on them) flanking the
 * corridor, cobble pillars at the sides, and the doorway open through to the next room. The capture (Dueces.json) holds
 * every block of that except the FIRE: all sixteen of its hoppers have air on top.
 *
 * <p>A two-room floor built from a map code, both rooms unrotated: Altar (2x2) on cells x 2..4, z 4..6, a NORMAL door at
 * (5,6), Deuces at (6,6) - Altar's east doorway on its second tile meets Deuces' west one, as on his floor. That Altar
 * doorway carries a row of iron bars along its top, one block in from its wall, which is the second thing the sim got
 * wrong there: the doorway carve (3 wide, 4 high, 3 deep into each room) cut it away. Then, from the server's level:
 * <ul>
 *   <li>the passage is walkable: three high from 3 blocks inside Altar to 3 inside Deuces, no collision, floor under it;</li>
 *   <li>the chest is still at capture (3, 78, 16), above the opening;</li>
 *   <li>both ends of the doorway are their captures' blocks, by id - Deuces x 1..8, z 10..22 and Altar x 56..63,
 *       z 42..54, y 66..84 - apart from fire on a hopper (the seam columns belong to the door and the seam fill);</li>
 *   <li>fire stands on the two braziers either side of the corridor nearest the doorway (capture x 5..6, z 14 and 18,
 *       y 72), and the count of lit hoppers in the room is printed.</li>
 * </ul>
 * On the main jar of 2026-10-15 the braziers are cold (0 of 4) and Altar's bars are gone (3 iron_bars -> air).
 * Also takes a screenshot from his viewpoint (feet at capture (7.8, 69, 16.5), facing west, looking up, FOV 110) and copies it to
 * {@code killer560s-mod-logs/sim-deuces-door-1016/} when that folder's parent exists. Sim only, so no GrimAC.
 *
 * <p><b>Door pieces</b> (mod sim-deuces-door-1016, killer560 2026-10-16: "this door isn't supposed to have almost any blocks
 * above it in Deuces"). The 5x3 box over a standard doorway's trim is Hypixel's door connector, and which half a doorway
 * has follows the side it faces in the WORLD: a recess (cyan terracotta, then air) on east and south walls, a fill (stone,
 * then bricks and four stairs) on west and north ones. {@link DoorPieceSweep} checks every placed room's standard doorways
 * on this floor at Deuces 0 and 180, on three generated F7s and on a solo Deuces at 0 and 180, and the chest doorway's
 * box is asserted on its own (fill at 0, facing west; recess at 180, facing east). Main fcba4045 passes every unrotated
 * case and fails every turned one; screenshots from his solo view (capture (9.5, 69, 16.5), pitch -22, FOV 90) for each.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimDeucesDoorwayTests implements FabricClientGameTest {

    private static final String NAME = "1433-sim-deuces-doorway";
    private static final String ROOM = "Dueces";
    private static final String NEIGHBOUR = "Altar";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String ROOM_DOORS = "com.killer560.hub.roomsim.RoomDoors";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ALTITUDE = "com.killer560.hub.roomsim.SimAltitude";
    private static final String MAP_CODE = "com.killer560.hub.roomsim.MapCode";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final int GRID = 11;
    private static final Path OUT = Path.of(System.getProperty("user.home"), "killer560s-mod-logs");
    /**
     * Where this scenario's screenshots go. Until 2026-10-16 it wrote {@code killer560s-mod-logs/deuces-doorway.png},
     * a sim shot with a name that read as a reference picture and was taken for Hypixel's.
     */
    private static final Path SHOTS = OUT.resolve("sim-deuces-door-1016");
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        SimMapTests.copyRoomsForOthers();
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        boolean have = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isUsable",
                new Class<?>[]{String.class}, new Object[]{ROOM})
                && (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isUsable", new Class<?>[]{String.class},
                new Object[]{NEIGHBOUR}));
        if (!have) {
            Scenario.skipped(NAME, "needs usable " + ROOM + " and " + NEIGHBOUR + " captures");
            return;
        }
        List<String> failures = new ArrayList<>();
        try {
            boolean altarEast = ctx.computeOnClient(mc -> hasDoorway(NEIGHBOUR, 1, 1));
            boolean deucesWest = ctx.computeOnClient(mc -> hasDoorway(ROOM, 3, 0));
            println("Altar has its east doorway on its second tile: " + altarEast + "; Deuces its west one: " + deucesWest);
            if (!altarEast || !deucesWest) {
                failures.add("premise: the doorways this floor joins are not measured (Altar E1 " + altarEast
                        + ", Deuces W0 " + deucesWest + ")");
                return;
            }
            build(ctx, 0);
            Check c = check(ctx);
            for (String l : c.lines()) {
                println(l);
            }
            failures.addAll(c.failures());
            screenshot(ctx, c.offset());
            // The box over each doorway's trim, against Hypixel's door piece for the side it faces (sim-deuces-door-1016).
            pieces(ctx, "floor, both unrotated", 0, 0, failures);
            chestDoorway(ctx, 6, 0, "fill", "floor, both unrotated", failures);
            heroShot(ctx, "floor-r0", 6, 0);
            // Deuces turned 180: its chest doorway faces EAST (sealed), its capture E0 faces west into Altar.
            build(ctx, 180);
            pieces(ctx, "floor, Deuces at 180", 2, 2, failures);
            chestDoorway(ctx, 6, 180, "recess", "floor, Deuces at 180", failures);
            heroShot(ctx, "floor-r180", 6, 180);
            // Three generated F7s: every room, every standard doorway.
            int crossed = 0;
            for (int i = 0; i < 3; i++) {
                generateF7(ctx);
                crossed += pieces(ctx, "generated F7 #" + (i + 1), 0, 0, failures);
            }
            println("generated floors: " + crossed + " doorway(s) faced a side whose piece differs from the capture's");
            if (crossed < 30) {
                failures.add("premise: only " + crossed + " doorway(s) on three generated F7s faced a side whose piece "
                        + "differs from the capture's - the sweep did not exercise the swap");
            }
            // Solo rooms, as Load a Room builds them (rotation 0) and turned 180 (the test rotation).
            solo(ctx, 0);
            pieces(ctx, "solo Deuces at 0", 0, 0, failures);
            chestDoorway(ctx, 4, 0, "fill", "solo Deuces at 0", failures);
            heroShot(ctx, "solo-r0", 4, 0);
            solo(ctx, 180);
            pieces(ctx, "solo Deuces at 180", 2, 2, failures);
            chestDoorway(ctx, 4, 180, "recess", "solo Deuces at 180", failures);
            heroShot(ctx, "solo-r180", 4, 180);
            ctx.runOnClient(mc -> ModUnderTest.staticCall(BUILDER, "setSingleRoomRotationForTest",
                    new Class<?>[]{int.class}, new Object[]{0}));
        } catch (Throwable t) {
            failures.add("threw: " + t);
            t.printStackTrace();
        } finally {
            teardown(ctx);
            if (failures.isEmpty()) {
                System.out.println("[" + NAME + "] PASS - Deuces' doorway matches the capture, open through to Altar, "
                        + "chest above it, braziers lit");
            } else {
                String why = String.join("; ", failures);
                System.out.println("[" + NAME + "] FAIL: " + why);
                SuiteVerdict.failCase(NAME, new AssertionError(why));
            }
        }
    }

    /** Whether {@code room}, unrotated, has a measured doorway on {@code side} (RoomDoors' 0..3) at tile {@code index}. */
    private static boolean hasDoorway(String room, int side, int index) {
        Object mask = ModUnderTest.staticCall(ROOM_DOORS, "of", new Class<?>[]{String.class}, new Object[]{room});
        return mask != null && (Boolean) ModUnderTest.call(mask, "has", new Class<?>[]{int.class, int.class},
                new Object[]{side, index});
    }

    /**
     * Altar (2x2) on grid cells x 2..4, z 4..6, Deuces on (6,6), both unrotated, and a NORMAL door at (5,6): Altar's
     * east doorway on its second tile (iron bars along the top of it, one block in) meets Deuces' west doorway.
     */
    private static void build(ClientGameTestContext ctx, int deucesRotation) {
        int[] cellRoom = new int[GRID * GRID];
        java.util.Arrays.fill(cellRoom, -1);
        int[] cellDoor = new int[GRID * GRID];
        int[] cellRotation = new int[GRID * GRID];
        for (int gz = 4; gz <= 6; gz++) {
            for (int gx = 2; gx <= 4; gx++) {
                cellRoom[gz * GRID + gx] = 0;
            }
        }
        cellRoom[6 * GRID + 6] = 1;
        cellRotation[6 * GRID + 6] = deucesRotation;
        cellDoor[6 * GRID + 5] = 1; // DungeonLayout.DOOR_NORMAL
        String code = ctx.computeOnClient(mc -> {
            try {
                Class<?> dec = Class.forName(MAP_CODE + "$Decoded");
                Object d = dec.getConstructor(String[].class, int[].class, int[].class, int[].class)
                        .newInstance(new String[]{NEIGHBOUR, ROOM}, cellRoom, cellDoor, cellRotation);
                return (String) ModUnderTest.staticCall(MAP_CODE, "encodeDecoded", new Class<?>[]{dec}, new Object[]{d});
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        long before = Scenario.simBuildCount(ctx);
        long mark = LogTap.mark();
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "build",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, code})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        for (int i = 0; i < 400 && !logHas(mark, "Sim doors: "); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(40);
        for (String l : LogTap.since(mark)) {
            if (l.contains("Sim doors: ") && !l.contains("[STDOUT]")) {
                println("log: " + l);
            }
        }
    }

    private record Check(List<String> lines, List<String> failures, int[] offset) {
    }

    /** Everything read on the server thread from the server's level, against the capture. */
    private static Check check(ClientGameTestContext ctx) {
        AtomicReference<Object> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            Object room = ModUnderTest.staticCall(ROOM_LIBRARY, "get", new Class<?>[]{String.class}, new Object[]{ROOM});
            Object altar = ModUnderTest.staticCall(ROOM_LIBRARY, "get", new Class<?>[]{String.class}, new Object[]{NEIGHBOUR});
            BlockPos centre = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class, int.class},
                    new Object[]{6, 6});
            BlockPos altarCentre = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter",
                    new Class<?>[]{int.class, int.class}, new Object[]{2, 4});
            int dy = (Integer) ModUnderTest.staticCall(ALTITUDE, "offset");
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                try {
                    out.set(checkOnServer(server.overworld(), room, centre, altar, altarCentre, dy));
                } catch (Throwable t) {
                    out.set(t);
                }
            });
        });
        ctx.waitFor(mc -> out.get() != null, 1200);
        if (out.get() instanceof Throwable t) {
            throw new AssertionError("the check threw", t);
        }
        return (Check) out.get();
    }

    private static Check checkOnServer(ServerLevel level, Object room, BlockPos centre, Object altar, BlockPos altarCentre,
                                       int dy) throws Exception {
        List<String> lines = new ArrayList<>();
        List<String> fail = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<String> palette = (List<String>) room.getClass().getField("palette").get(room);
        var at = room.getClass().getMethod("at", int.class, int.class, int.class);
        // Capture (x, y, z) -> world, at rotation 0: the paste anchors capture x 0 at centre - TILE/2 - margin = centre - 16.
        int ox = centre.getX() - 16;
        int oz = centre.getZ() - 16;
        java.util.function.Function<int[], BlockPos> w = c -> new BlockPos(ox + c[0], c[1] + dy, oz + c[2]);
        java.util.function.Function<int[], String> capture = c -> {
            try {
                short s = (Short) at.invoke(room, c[0], c[1], c[2]);
                return s < 0 ? null : idOf(palette.get(s));
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        };
        java.util.function.Function<BlockPos, String> world = p ->
                idOf(BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).toString());

        // Premise: the room is where we think it is - its captured chest is there.
        int[] chest = {3, 78, 16};
        String chestHere = world.apply(w.apply(chest));
        lines.add("chest at capture (3,78,16) -> world " + w.apply(chest).toShortString() + ": " + chestHere);
        if (!"chest".equals(chestHere) && !"trapped_chest".equals(chestHere)) { // the mimic's chest is trapped
            fail.add("no chest at capture (3,78,16) above the doorway (found " + chestHere + ")");
        }

        // 1. The passage: capture x -3..3 (three into Altar, the seam, three into Deuces), z 15..17, y 69..71 - walking
        //    height; the top layer (y 72) holds Altar's trim, which the capture comparison below checks.
        int blocked = 0;
        String firstBlocked = null;
        for (int x = -3; x <= 3; x++) {
            for (int z = 15; z <= 17; z++) {
                for (int y = 69; y <= 71; y++) {
                    BlockPos p = w.apply(new int[]{x, y, z});
                    if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) {
                        blocked++;
                        if (firstBlocked == null) {
                            firstBlocked = "capture (" + x + "," + y + "," + z + ") " + world.apply(p);
                        }
                    }
                }
            }
        }
        int floorMissing = 0;
        for (int x = -3; x <= 3; x++) {
            for (int z = 15; z <= 17; z++) {
                BlockPos p = w.apply(new int[]{x, 68, z});
                if (level.getBlockState(p).getCollisionShape(level, p).isEmpty()) {
                    floorMissing++;
                }
            }
        }
        lines.add("passage capture x -3..3, z 15..17, y 69..71: " + blocked + " of 63 blocked"
                + (firstBlocked == null ? "" : " (first " + firstBlocked + ")") + "; floor under it missing at "
                + floorMissing + " of 21");
        if (blocked > 0) {
            fail.add("the doorway into Altar is blocked at " + blocked + " block(s), first " + firstBlocked);
        }
        if (floorMissing > 0) {
            fail.add("the doorway has no floor at " + floorMissing + " column(s)");
        }

        // 2. Both ends of the doorway against their captures, block by block by id: Deuces x 1..8 (its west wall is x 1),
        //    Altar x 56..63 round its second east tile (its wall is x 63; the carve reaches x 61..63). The seam columns
        //    (Deuces x 0, Altar x 64) belong to the door and the seam fill and are left out.
        compareEnd(level, room, centre.getX() - 16, centre.getZ() - 16, dy, 1, 8, 10, 22, "Deuces", lines, fail);
        compareEnd(level, altar, altarCentre.getX() - 16, altarCentre.getZ() - 16, dy, 56, 63, 42, 54, "Altar", lines, fail);

        // 3. Fire on the braziers: the pair either side of the corridor nearest the doorway, then every hopper in the room.
        int[][] near = {{5, 72, 14}, {6, 72, 14}, {5, 72, 18}, {6, 72, 18}};
        int lit = 0;
        for (int[] c : near) {
            if ("fire".equals(world.apply(w.apply(c)))) {
                lit++;
            }
        }
        int hoppers = 0;
        int hoppersLit = 0;
        int sizeX = room.getClass().getField("sizeX").getInt(room);
        int sizeZ = room.getClass().getField("sizeZ").getInt(room);
        int minY = room.getClass().getField("minY").getInt(room);
        int maxY = room.getClass().getField("maxY").getInt(room);
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int y = minY; y < maxY; y++) {
                    if (!"hopper".equals(capture.apply(new int[]{x, y, z}))) {
                        continue;
                    }
                    hoppers++;
                    if ("fire".equals(world.apply(w.apply(new int[]{x, y + 1, z})))) {
                        hoppersLit++;
                    }
                }
            }
        }
        lines.add("braziers beside the doorway lit: " + lit + " of 4; hoppers in the room with fire on them: "
                + hoppersLit + " of " + hoppers);
        if (lit < 4) {
            fail.add("only " + lit + " of the 4 braziers beside the doorway have fire (Hypixel: all lit)");
        }
        return new Check(lines, fail, new int[]{ox, dy, oz});
    }

    /** One room's doorway end, capture x x0..x1, z z0..z1, y 66..84, against the world, by block id. */
    private static void compareEnd(ServerLevel level, Object room, int ox, int oz, int dy, int x0, int x1, int z0, int z1,
                                   String label, List<String> lines, List<String> fail) throws Exception {
        @SuppressWarnings("unchecked")
        List<String> palette = (List<String>) room.getClass().getField("palette").get(room);
        var at = room.getClass().getMethod("at", int.class, int.class, int.class);
        Map<String, Integer> diffs = new TreeMap<>();
        List<String> samples = new ArrayList<>();
        int compared = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = 66; y <= 84; y++) {
                    short s = (Short) at.invoke(room, x, y, z);
                    if (s < 0) {
                        continue;
                    }
                    String want = idOf(palette.get(s));
                    BlockPos p = new BlockPos(ox + x, y + dy, oz + z);
                    String got = idOf(BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).toString());
                    compared++;
                    if (want.equals(got)) {
                        continue;
                    }
                    short below = (Short) at.invoke(room, x, y - 1, z);
                    if ("air".equals(want) && "fire".equals(got) && below >= 0 && "hopper".equals(idOf(palette.get(below)))) {
                        continue; // a brazier's fire, checked on its own below
                    }
                    diffs.merge(want + " -> " + got, 1, Integer::sum);
                    if (samples.size() < 8) {
                        samples.add("(" + x + "," + y + "," + z + ") " + want + "->" + got);
                    }
                }
            }
        }
        int n = diffs.values().stream().mapToInt(Integer::intValue).sum();
        lines.add(label + "'s doorway end, capture x " + x0 + ".." + x1 + " z " + z0 + ".." + z1 + " y 66..84: " + compared
                + " block(s) compared, " + n + " differ " + diffs + (samples.isEmpty() ? "" : " e.g. " + samples));
        if (compared < 1000) {
            fail.add(label + ": only " + compared + " capture block(s) compared - the capture was not read");
        } else if (n > 0) {
            fail.add(label + "'s doorway end differs from its capture at " + n + " block(s): " + diffs);
        }
    }

    private static String idOf(String state) {
        String id = state.contains("[") ? state.substring(0, state.indexOf('[')) : state;
        if (id.startsWith("minecraft:")) {
            id = id.substring("minecraft:".length());
        }
        if (id.equals("trapped_chest")) {
            return "chest"; // the sim's mimic turns one secret chest into a trapped chest, as Hypixel's does
        }
        return id.equals("void_air") || id.equals("cave_air") ? "air" : id;
    }

    /** His viewpoint: feet at capture (7.8, 69, 16.5), facing west (yaw 90), looking up at the chest, wide FOV as in his shot. */
    private static void screenshot(ClientGameTestContext ctx, int[] o) {
        double x = o[0] + 7.8;
        double y = 69 + o[1];
        double z = o[2] + 16.5;
        float yaw = 90f;
        float pitch = -36f;
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                sp.teleportTo(server.overworld(), x, y, z, java.util.Set.<net.minecraft.world.entity.Relative>of(),
                        yaw, pitch, false);
                done.set(true);
            });
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
            McCompat.setHudHidden(mc, true);
            mc.options.fov().set(110);
        });
        ctx.waitFor(mc -> done.get() != null, 100);
        // The client has to have the rebuilt chunks and have meshed them, or the shot is sky.
        BlockPos chestAt = new BlockPos(o[0] + 3, 78 + o[1], o[2] + 16);
        ctx.waitFor(mc -> mc.level != null && (mc.level.getBlockState(chestAt).is(net.minecraft.world.level.block.Blocks.CHEST)
                || mc.level.getBlockState(chestAt).is(net.minecraft.world.level.block.Blocks.TRAPPED_CHEST)), 1200);
        ctx.waitTicks(100);
        ctx.runOnClient(mc -> {
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
        });
        ctx.waitTicks(5);
        Path shot = ctx.takeScreenshot(dev.testkit.harness.Report.fileName(NAME));
        ctx.runOnClient(mc -> McCompat.setHudHidden(mc, false));
        println("screenshot " + shot);
        try {
            if (Files.isDirectory(OUT)) {
                Files.createDirectories(SHOTS);
                Path to = SHOTS.resolve("deuces-floor-r0-1433view.png");
                Files.copy(shot, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                println("copied to " + to);
            }
        } catch (java.io.IOException e) {
            println("could not copy the screenshot: " + e);
        }
    }

    /**
     * The door-piece sweep over every placed room (see {@link DoorPieceSweep}), from the server's level. Fails on any
     * doorway whose box is not Hypixel's piece for the side it faces, and when fewer than {@code minChecked} standard
     * doorways were compared or fewer than {@code minCrossed} faced a side whose piece differs from the capture's.
     *
     * @return how many doorways faced a side whose piece differs from the capture's
     */
    private static int pieces(ClientGameTestContext ctx, String label, int minChecked, int minCrossed, List<String> fail) {
        AtomicReference<Object> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            int dy = (Integer) ModUnderTest.staticCall(ALTITUDE, "offset");
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                try {
                    out.set(DoorPieceSweep.run(server.overworld(), dy));
                } catch (Throwable t) {
                    out.set(t);
                }
            });
        });
        ctx.waitFor(mc -> out.get() != null, 1200);
        if (out.get() instanceof Throwable t) {
            t.printStackTrace();
            fail.add(label + ": the door-piece sweep threw " + t);
            return 0;
        }
        DoorPieceSweep.Result r = (DoorPieceSweep.Result) out.get();
        println("door pieces, " + label + ": " + r.summary());
        if (r.checked() < Math.max(1, minChecked)) {
            fail.add(label + ": premise - only " + r.checked() + " standard doorway(s) compared");
        }
        if (r.crossed() < minCrossed) {
            fail.add(label + ": premise - only " + r.crossed() + " doorway(s) faced a side whose piece differs");
        }
        if (r.wrong() > 0) {
            fail.add(label + ": " + r.wrong() + " doorway(s) carry the wrong door piece for the side they face "
                    + r.samples());
        }
        return r.crossed();
    }

    /**
     * The box over Deuces' chest doorway (capture W0, the chest at capture (3, 78, 16) above it) in the world, Deuces
     * pasted at {@code rotation} on cell ({@code cell}, {@code cell}): {@code want} is "fill" or "recess".
     */
    private static void chestDoorway(ClientGameTestContext ctx, int cell, int rotation, String want, String label,
                                     List<String> fail) {
        AtomicReference<String> got = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            int dy = (Integer) ModUnderTest.staticCall(ALTITUDE, "offset");
            BlockPos o = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class, int.class},
                    new Object[]{cell, cell});
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                ServerLevel level = server.overworld();
                StringBuilder k0 = new StringBuilder();
                StringBuilder k1 = new StringBuilder();
                for (int y = 76; y >= 74; y--) {
                    for (int u = -2; u <= 2; u++) {
                        k0.append(code(level, world(o, rotation, 1, y, 16 + u, dy)));
                        k1.append(code(level, world(o, rotation, 2, y, 16 + u, dy)));
                    }
                    k0.append(' ');
                    k1.append(' ');
                }
                String chest = BuiltInRegistries.BLOCK.getKey(
                        level.getBlockState(world(o, rotation, 3, 78, 16, dy)).getBlock()).getPath();
                got.set("wall plane " + k0.toString().trim() + " | one in " + k1.toString().trim() + " | chest " + chest);
            });
        });
        ctx.waitFor(mc -> got.get() != null, 600);
        String g = got.get();
        boolean recess = g.startsWith("wall plane ccccc ccccc ccccc | one in ..... ..... .....");
        boolean fill = g.startsWith("wall plane sssss sssss sssss | one in ") && !g.contains("one in .....")
                && !g.contains("?");
        println(label + ": the box over the chest doorway (y 76..74, u -2..2): " + g);
        if (!g.endsWith("chest") && !g.endsWith("trapped_chest")) {
            fail.add(label + ": premise - no chest above the chest doorway (" + g + ")");
        }
        if ("recess".equals(want) ? !recess : !fill) {
            fail.add(label + ": the chest doorway faces " + (rotation == 0 ? "west" : "east") + " and should have the "
                    + want + " over its trim, found " + g);
        }
    }

    /** World position of Deuces' capture block (x, y, z) pasted at rotation 0 or 180 on the cell centred at {@code o}. */
    private static BlockPos world(BlockPos o, int rotation, int x, int y, int z, int dy) {
        int x0 = o.getX() - 16;
        int z0 = o.getZ() - 16;
        return rotation == 180 ? new BlockPos(x0 + 32 - x, y + dy, z0 + 32 - z) : new BlockPos(x0 + x, y + dy, z0 + z);
    }

    private static char code(ServerLevel level, BlockPos p) {
        String id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).getPath();
        return switch (id) {
            case "air", "cave_air", "void_air" -> '.';
            case "cyan_terracotta" -> 'c';
            case "stone" -> 's';
            case "stone_bricks" -> 'B';
            case "mossy_stone_bricks" -> 'M';
            case "stone_brick_stairs" -> 'T';
            default -> '?';
        };
    }

    /** One generated F7, the way the designer's Generate does it. */
    private static void generateF7(ClientGameTestContext ctx) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            ModUnderTest.staticCall(FLOOR_GEN, "generate",
                    new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, 3, 4});
        }));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        ctx.waitTicks(40);
    }

    /** Deuces on its own, as Load a Room builds it, pasted at {@code rotation} (SimBuilder's test rotation). */
    private static void solo(ClientGameTestContext ctx, int rotation) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(BUILDER, "setSingleRoomRotationForTest",
                new Class<?>[]{int.class}, new Object[]{rotation}));
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildSingleRoom",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, ROOM})));
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        ctx.waitTicks(60);
    }

    /**
     * His view in his report (killer560, 2026-10-16, a solo Deuces): standing in the room about eight blocks from the
     * chest doorway's wall, facing it and looking up towards the chest. Feet at capture (9.5, 69, 16.5), facing the
     * doorway (west at rotation 0, east at 180), pitch -22, FOV 90. Copied to {@link #SHOTS}.
     */
    private static void heroShot(ClientGameTestContext ctx, String tag, int cell, int rotation) {
        AtomicReference<double[]> at = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            int dy = (Integer) ModUnderTest.staticCall(ALTITUDE, "offset");
            BlockPos o = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class, int.class},
                    new Object[]{cell, cell});
            double x0 = o.getX() - 16;
            double z0 = o.getZ() - 16;
            at.set(rotation == 180 ? new double[]{x0 + 33 - 9.5, 69 + dy, z0 + 33 - 16.5, -90}
                    : new double[]{x0 + 9.5, 69 + dy, z0 + 16.5, 90});
        });
        double[] a = at.get();
        float pitch = -22f;
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                sp.teleportTo(server.overworld(), a[0], a[1], a[2], java.util.Set.<net.minecraft.world.entity.Relative>of(),
                        (float) a[3], pitch, false);
                done.set(true);
            });
            mc.player.setYRot((float) a[3]);
            mc.player.setXRot(pitch);
            McCompat.setHudHidden(mc, true);
            mc.options.fov().set(90);
        });
        ctx.waitFor(mc -> done.get() != null, 100);
        ctx.waitTicks(100);
        ctx.runOnClient(mc -> {
            mc.player.setYRot((float) a[3]);
            mc.player.setXRot(pitch);
        });
        ctx.waitTicks(5);
        Path shot = ctx.takeScreenshot(dev.testkit.harness.Report.fileName(NAME + "-" + tag));
        ctx.runOnClient(mc -> McCompat.setHudHidden(mc, false));
        println("screenshot " + tag + ": " + shot);
        try {
            if (Files.isDirectory(OUT)) {
                Files.createDirectories(SHOTS);
                Path to = SHOTS.resolve("deuces-" + tag + ".png");
                Files.copy(shot, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                println("copied to " + to);
            }
        } catch (java.io.IOException e) {
            println("could not copy the screenshot: " + e);
        }
    }

    private static boolean logHas(long mark, String text) {
        for (String l : LogTap.since(mark)) {
            if (l.contains(text)) {
                return true;
            }
        }
        return false;
    }

    private static void teardown(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            try {
                ModUnderTest.staticCall(SIM_STATE, "leave", new Class<?>[]{}, new Object[]{});
            } catch (Throwable ignored) {
                // never let cleanup replace the verdict
            }
        });
        ctx.runOnClient(mc -> mc.execute(() -> {
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                mc.disconnectWithSavingScreen();
            }
        }));
        ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 1200);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> mc.execute(() ->
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen, 400);
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}

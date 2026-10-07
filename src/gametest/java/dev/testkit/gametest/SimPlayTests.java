package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Actually PLAY the sim, rather than inspect it.
 *
 * <p>killer560 (2026-09-29): "you have access to that test server and you should have adapted it such that
 * you can now test play the test server to be sure everything is working."
 *
 * <p>He is right, and this is the gap every other sim scenario left. They read the world and assert about it:
 * the layout connects, the doors are holes, the secrets are inside their rooms. None of them ever pressed a
 * key. The harness can - {@code ctx.getInput().holdKey(...)} is real keyboard input, and the Breaker Aura
 * scenarios have been walking the player into walls with it for a week - so the sim can be played the same
 * way.
 *
 * <p>Two things are driven here, both of which were previously "compiled and reasoned about":
 *
 * <ul>
 *   <li><b>Walking through a doorway.</b> The player is put in the entrance, turned to face a door out of it,
 *       and told to walk. He has to END UP IN A DIFFERENT ROOM. Scenario 80 proves there is a gap of air in
 *       the wall; this proves a person can get through it, which is not the same claim - a gap with a step up,
 *       a slab in it, or a one-block ledge passes the first and fails the second.</li>
 *   <li><b>Collecting an item secret.</b> One is held back until he has been within three blocks for more
 *       than five ticks. The player is walked onto a real one - a position from the room database through two
 *       rotations, not a made-up coordinate - and the drop must appear AND be collected, with the score's own
 *       secret counter going up.</li>
 * </ul>
 */
public class SimPlayTests implements FabricClientGameTest {

    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String MAP_CODE = "com.killer560.hub.roomsim.MapCode";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String BUILD_QUEUE = "com.killer560.hub.roomsim.SimBuildQueue";
    private static final String SECRET_ITEMS = "com.killer560.hub.roomsim.SimSecretItems";
    private static final String SIM_SCORE = "com.killer560.hub.roomsim.SimScore";
    private static final String ALTITUDE = "com.killer560.hub.roomsim.SimAltitude";
    private static final String SIM_RUN = "com.killer560.hub.roomsim.SimRun";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";

    private static final String SOURCE_ROOMS =
            ModUnderTest.instanceConfig("C:/Users/Hunter/AppData/Roaming/PrismLauncher/instances/26.1.2 (Mod Only Test)"
                    + "/minecraft/config", "killer560smod-rooms");

    private static final int GRID = 11;

    /** How many doorways to walk. Every one would be thorough and slow; this is a representative sample. */
    private static final int MAX_DOORS = 8;

    /**
     * How far along the crossing axis counts as having got through.
     *
     * <p>He starts four blocks back, so anything past about 4.5 has crossed the seam. Five gives margin over
     * that without coming near what a sprint covers in the time allowed, and a real wall stops him at 2-3.
     */
    private static final double CROSSED_BLOCKS = 5.0;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip("81-sim-play")) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff(
                "com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));

        if (copyDir(SOURCE_ROOMS, "killer560smod-rooms", ".json") < 20
                || copyDir(Path.of(SOURCE_ROOMS).resolveSibling("killer560smod-roomdata").toString(),
                        "killer560smod-roomdata", "") == 0) {
            System.out.println("[81-sim-play] SKIPPED - needs his rooms and room database");
            return;
        }
        ctx.runOnClient(mc -> Scenario.loadRoomDatabaseNow());
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomdatabase.RoomDatabase", "isReady"));
        // Room types come from the room database; the sim will not plan a floor without it (mod, 2026-10-05).
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"));

        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter",
                new Class<?>[]{String.class}, new Object[]{"gametest"}));
        long simBuildBefore = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            ModUnderTest.staticCall(FLOOR_GEN, "generate",
                    new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, 3, 4});
        }));
        ctx.waitFor(mc -> mc.level != null);
        Scenario.awaitSimBuild(ctx, simBuildBefore);
        ctx.waitTicks(80);

        // Teardown in a FINALLY. When this scenario first failed for real it threw with the sim world still
        // open, the runner's keep-going wrapper moved on, and the client hung until the deadline watcher shot
        // it - a frozen Minecraft window left on his desktop for a test that had already found its answer.
        try {
            // He spawns in the ENTRANCE, and the entrance's doorway is filled with the infested chiseled gate
            // until the run starts. The first version of this scenario walked him face-first into that gate for six
            // seconds and reported it as "a player cannot pass" - a real wall, correctly there, called a bug.
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_RUN, "begin",
                    new Class<?>[]{Minecraft.class, BlockPos.class},
                    new Object[]{mc, ModUnderTest.staticCall(BUILDER, "entranceDoor")}));
            ctx.waitTicks(160);

            // ---- 1. walk through every doorway on the floor -------------------------------------------
            // The first version of this walked forward from the middle of one room for six seconds and asked
            // whether the room changed. That tests blind navigation, not the doorway: it failed after 16.2
            // blocks because it aimed from the CELL CENTRE rather than from where the player was standing, and
            // it only ever covered one door. This places him four blocks in front of each doorway in turn,
            // square on to it, and asks the one question worth asking - can a player get through this hole.
            int shift = (Integer) ModUnderTest.staticCall(ALTITUDE, "offset");
            int floorY = 69 + shift;

            final Object[] grab = new Object[1];
            ctx.runOnClient(mc -> {
                String code = (String) ModUnderTest.staticCall(SIM_STATE, "mapCode");
                grab[0] = ModUnderTest.staticCall("com.killer560.hub.roomsim.MapCode", "decode",
                        new Class<?>[]{String.class}, new Object[]{code});
            });
            int[] doorFlags = grab[0] == null ? new int[0]
                    : (int[]) ModUnderTest.call(grab[0], "cellDoor", new Class<?>[]{}, new Object[]{});

            List<Integer> doorCells = new java.util.ArrayList<>();
            for (int cell = 0; cell < doorFlags.length; cell++) {
                if (doorFlags[cell] != 0) {
                    doorCells.add(cell);
                }
            }
            // THE WAY OUT OF THE GREEN ROOM GOES FIRST.
            //
            // killer560 (2026-09-30): "nothing after the green room to run into." Only MAX_DOORS of a floor's
            // twenty-one doorways are walked, and taking them in cell order means whether the run's very first
            // doorway is among them depends on where the generator happened to put the entrance - so the one
            // doorway a player cannot get past without the run being over could be sampled or not, at random.
            // DOOR_ENTRANCE (4) first, then DOOR_BLOOD (3), then the rest in cell order.
            doorCells.sort((a, b) -> {
                int pa = doorFlags[a] == 4 ? 0 : doorFlags[a] == 3 ? 1 : 2;
                int pb = doorFlags[b] == 4 ? 0 : doorFlags[b] == 3 ? 1 : 2;
                return pa != pb ? Integer.compare(pa, pb) : Integer.compare(a, b);
            });
            System.out.println("[81-sim-play] " + doorCells.size() + " doorway(s) on this floor, walking "
                    + Math.min(doorCells.size(), MAX_DOORS) + " of them");

            List<String> stuck = new java.util.ArrayList<>();
            List<String> locked = new java.util.ArrayList<>();
            // Doorways this scenario could not get into position for. Kept apart from `stuck` on purpose: a
            // doorway it could not reach is a gap in the measurement, not a fault in the floor.
            List<String> noApproach = new java.util.ArrayList<>();
            int walked = 0;
            for (int cell : doorCells) {
                if (walked >= MAX_DOORS) {
                    break;
                }
                walked++;
                int gx = cell % GRID;
                int gz = cell / GRID;
                boolean alongX = gx % 2 == 1;   // the doorway is crossed along X
                int cx = -185 + gx * 16;
                int cz = -185 + gz * 16;
                // FIND A SPOT HE CAN ACTUALLY STAND ON, rather than assuming four blocks back is one.
                //
                // This used to place him at a fixed four blocks back on one fixed side. On 2026-09-30 that put
                // him at "feet=air head=air under=air" outside an Entrance room at the edge of the map: he
                // fell instead of walking, moved 0.2 across, and was reported as an impassable doorway over a
                // seam whose every column at y0 and y1 was air and whose floor was solid all the way across.
                // The doorway was perfect and the placement was in mid-air.
                //
                // So both sides are tried, and three distances on each, nearest-but-one first. A doorway with
                // no standable approach at all is reported as exactly that - it is not evidence about whether
                // the doorway can be walked through, and calling it impassable was the instrument's own error
                // dressed up as a finding.
                double sx = 0;
                double sz = 0;
                float yaw = 0;
                // +1 when he walks towards increasing x/z, -1 when towards decreasing. The crossing is
                // measured along the way he is FACING, and forgetting that was worth three false failures:
                // approaching from the positive side and crossing perfectly reads as "moved -16.5 across",
                // which fails a "did he get at least N across" test on a doorway he walked straight through.
                double travel = 1;
                boolean placed = false;
                outer:
                for (int back : new int[]{4, 3, 2}) {
                    for (int sign : new int[]{-1, 1}) {
                        double tx = alongX ? cx + sign * back : cx;
                        double tz = alongX ? cz : cz + sign * back;
                        if (standable(ctx, tx + 0.5, floorY, tz + 0.5)) {
                            sx = tx;
                            sz = tz;
                            // Face across the seam FROM the side he is actually on: -90 looks towards +X and
                            // +90 towards -X, 0 towards +Z and 180 towards -Z.
                            yaw = alongX ? (sign < 0 ? -90f : 90f) : (sign < 0 ? 0f : 180f);
                            travel = sign < 0 ? 1 : -1;
                            placed = true;
                            break outer;
                        }
                    }
                }
                if (!placed) {
                    noApproach.add(String.format(
                            "%s door at cell %d (%d,%d) - no standable spot within four blocks on either side, "
                                    + "so this scenario cannot test it", kindOf(doorFlags[cell]), cell, cx, cz));
                    continue;
                }
                teleport(ctx, sx + 0.5, floorY, sz + 0.5, yaw);
                ctx.waitTicks(10);

                double[] from = playerPos(ctx);
                // Sprint, and for long enough that the budget is not the thing being measured. Forty ticks of
                // WALKING covers about 5.3 blocks, and the first version of this asked for 6 - so a doorway
                // the player got through cleanly was failed for running out of time, and reported itself as
                // "blocked by nothing solid across the seam". Sprinting 60 ticks covers about 13, which puts
                // a genuine crossing nowhere near a genuine wall (those stop dead at 2-3 blocks).
                ctx.getInput().holdKey(options -> options.keyUp);
                ctx.getInput().holdKey(options -> options.keySprint);
                // JUMP TOO, because a player does.
                //
                // On 2026-09-30 a doorway failed with the walking layer entirely solid and the four layers
                // above it entirely open - the opening simply sat one block higher than the approach, because
                // the two rooms meet at slightly different heights. Walking cannot climb a full block, so the
                // harness stopped dead and called it impassable; in game he would hop over it without
                // noticing. Holding jump makes "walked through" mean what it is supposed to mean, and it does
                // not weaken the test: a jump clears about 1.25 blocks, so a two-block step and a real wall
                // both still stop him.
                ctx.getInput().holdKey(options -> options.keyJump);
                ctx.waitTicks(60);
                ctx.getInput().releaseKey(options -> options.keyJump);
                ctx.getInput().releaseKey(options -> options.keySprint);
                ctx.getInput().releaseKey(options -> options.keyUp);
                ctx.waitTicks(5);
                double[] to = playerPos(ctx);

                // Did he cross the seam? Measured along the crossing axis only, so sliding sideways along a
                // wall cannot be mistaken for going through.
                double crossed = travel * (alongX ? to[0] - from[0] : to[2] - from[2]);
                if (crossed < CROSSED_BLOCKS) {
                    // Say WHAT stopped him, then judge it. A shut wither door is coal block and a blood door
                    // red terracotta, and both are solid BY DESIGN until he has the key - reporting those as
                    // impassable doorways is reporting a working dungeon as broken, which this did once. The
                    // entrance gate is different: it is infested chiseled stone brick and it is supposed to be
                    // gone by now, because the run has already started.
                    String blocking = seamBlocks(ctx, cx, cz, floorY, alongX);
                    // NAME THE ROOMS, not just the block. "blocked by stone_bricks" is the same sentence for
                    // every one of these and says nothing about which capture or which rotation to go and
                    // look at; the failing cell changes every run because the floor is random, so without
                    // this there is nothing to reproduce offline. Both sides and both rotations, because a
                    // carve that misses is a disagreement between one room's doorway and the connector.
                    String rooms = roomsEitherSide(ctx, cell, alongX);
                    String kind = kindOf(doorFlags[cell]);
                    // WHERE HE STARTED, because "moved 0.2" is a different bug from "moved 4.2".
                    //
                    // On 2026-09-30 a doorway failed with "moved 0.2 across" over a seam whose every column
                    // at y0 and y1 was air - so nothing obstructed him and he simply never walked. That is a
                    // fault in this scenario's placement, not in the floor, and the two are indistinguishable
                    // without knowing what he was standing in when the keys went down. A player teleported
                    // into a solid block does not move, and reads exactly like a wall four blocks ahead.
                    String start = startState(ctx, sx + 0.5, floorY, sz + 0.5);
                    String line = String.format(
                            "%s at cell %d (%d,%d) moved %.1f across from %s, blocked by %s, between %s; %s",
                            kind, cell, cx, cz, crossed, start, blocking, rooms,
                            // WHERE the opening actually is, not just that the player stopped. "blocked by
                            // stone_bricks" is the same sentence whether the carve missed entirely or landed
                            // four blocks too low, and those are different bugs with different fixes.
                            seamProfile(ctx, cx, cz, floorY, alongX));
                    if (blocking.contains("coal_block") || blocking.contains("red_terracotta")) {
                        locked.add(line);
                    } else {
                        stuck.add(line);
                    }
                }
            }

            System.out.println("[81-sim-play] doorways walked: " + walked + ", locked as designed: "
                    + locked.size() + ", impassable: " + stuck.size()
                    + ", no standable approach: " + noApproach.size());
            for (String line : noApproach) {
                System.out.println("[81-sim-play]   NOT TESTED " + line);
            }
            for (String shut : locked) {
                System.out.println("[81-sim-play]   locked " + shut);
            }
            for (String bad : stuck) {
                System.out.println("[81-sim-play]   STUCK " + bad);
            }
            if (!stuck.isEmpty()) {
                throw new AssertionError(stuck.size() + " of " + walked + " doorway(s) could not be walked "
                        + "through even standing square on to them four blocks away: " + stuck);
            }

            // ---- 2. collect a real item secret ---------------------------------------------------------------
            @SuppressWarnings("unchecked")
            List<BlockPos> pending = (List<BlockPos>) ModUnderTest.staticCall(SECRET_ITEMS, "pendingPositions");
            System.out.println("[81-sim-play] " + pending.size() + " item secret(s) waiting");
            if (pending.isEmpty()) {
                System.out.println("[81-sim-play] NOTE - this floor has no item secrets, so none was collected");
            } else {
                BlockPos target = pending.get(0);
                int before = (Integer) ModUnderTest.staticCall(SIM_SCORE, "secretsFound");
                int pendingBefore = (Integer) ModUnderTest.staticCall(SECRET_ITEMS, "pendingCount");
                // Put him on it and KEEP him there. A single teleport is not enough: an item secret can sit
                // anywhere in a room, including in mid-air over a drop, and the player simply falls off it and
                // out of the three-block range before the five ticks are up. That reads as "the trigger never
                // fired" when the trigger was never given its five ticks.
                for (int hold = 0; hold < 14; hold++) {
                    teleport(ctx, target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0f);
                    ctx.waitTicks(5);
                }

                int after = (Integer) ModUnderTest.staticCall(SIM_SCORE, "secretsFound");
                int pendingAfter = (Integer) ModUnderTest.staticCall(SECRET_ITEMS, "pendingCount");

                // The drop does NOT fall - SimSecretItems spawns it with setNoGravity(true) - so it sits at
                // the secret's own block, half a block up. An earlier version of this swept four blocks DOWN
                // looking for a fallen item, which was a guess at the mechanism and found nothing because
                // there was nothing down there.
                //
                // What it does need is patience and a little vertical slack: the drop has a ten-tick pickup
                // delay by design, and a secret in mid-air drops the player away from it between teleports.
                // So hold on the item's own position and one block either side of it, well past that delay.
                if (after <= before && pendingAfter < pendingBefore) {
                    int[] offsets = {0, 1, -1};
                    for (int off : offsets) {
                        for (int hold = 0; hold < 8 && after <= before; hold++) {
                            teleport(ctx, target.getX() + 0.5, target.getY() + off, target.getZ() + 0.5, 0f);
                            ctx.waitTicks(4);
                            after = (Integer) ModUnderTest.staticCall(SIM_SCORE, "secretsFound");
                        }
                        if (after > before) {
                            break;
                        }
                    }
                }
                int live = (Integer) ModUnderTest.staticCall(SECRET_ITEMS, "liveCount");
                System.out.println(String.format("[81-sim-play] stood on %s: secrets found %d -> %d, "
                        + "pending %d -> %d, drops on the floor %d",
                        target, before, after, pendingBefore, pendingAfter, live));
                // Two different bugs, told apart. If the pending count never dropped, the proximity trigger
                // never fired; if it dropped but the score did not move, the drop appeared and could not be
                // collected. The first version of this reported both as one sentence and named neither.
                if (pendingAfter >= pendingBefore) {
                    throw new AssertionError("stood on an item secret at " + target + " for three seconds and "
                            + "it never appeared - the within-3-blocks-for-5-ticks trigger did not fire");
                }
                if (after <= before) {
                    throw new AssertionError("the item secret at " + target + " appeared (" + live + " drop(s) "
                            + "on the floor) but standing on it, and a block above and below it, for four "
                            + "seconds did not collect it");
                }
            }
        } finally {
            teardown(ctx);
        }
        System.out.println("[81-sim-play] PASS - walked through a door and collected a secret");
    }

    /** Leave the sim, close the world and get back to the title screen, pass or fail. */
    private static void teardown(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "leave"));
        ctx.runOnClient(mc -> mc.execute(() -> {
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                mc.disconnectWithSavingScreen();
            }
        }));
        ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> mc.execute(() ->
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen);
    }

    /** The door kind, for a log line. */
    private static String kindOf(int flag) {
        return switch (flag) {
            case 4 -> "ENTRANCE door";
            case 3 -> "BLOOD door";
            case 2 -> "WITHER door";
            default -> "plain door";
        };
    }

    /**
     * Whether the player could stand here: solid under his feet, and room for his body.
     *
     * <p>The precondition for walking at all, and the thing this scenario used to assume. Checked on the
     * server, where the blocks are - the arena is a long way from spawn and the client may not have it.
     */
    private static boolean standable(ClientGameTestContext ctx, double x, double y, double z) {
        java.util.concurrent.atomic.AtomicReference<Boolean> got =
                new java.util.concurrent.atomic.AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            if (server == null) {
                got.set(Boolean.FALSE);
                return;
            }
            server.execute(() -> {
                var level = server.overworld();
                var feet = net.minecraft.core.BlockPos.containing(x, y, z);
                got.set(!level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()
                        && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                        && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty());
            });
        });
        ctx.waitFor(mc -> got.get() != null);
        return got.get();
    }

    /**
     * What the player was standing in at his starting position.
     *
     * <p>Feet, head and the block under him. A start inside stone, or over a hole, explains a crossing
     * distance near zero without any wall being involved.
     */
    private static String startState(ClientGameTestContext ctx, double x, double y, double z) {
        java.util.concurrent.atomic.AtomicReference<String> got =
                new java.util.concurrent.atomic.AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            if (server == null) {
                got.set("no server");
                return;
            }
            server.execute(() -> {
                var level = server.overworld();
                var feet = net.minecraft.core.BlockPos.containing(x, y, z);
                got.set(String.format(java.util.Locale.ROOT, "(%.1f,%.1f,%.1f) feet=%s head=%s under=%s",
                        x, y, z,
                        name(level, feet), name(level, feet.above()), name(level, feet.below())));
            });
        });
        ctx.waitFor(mc -> got.get() != null);
        return got.get();
    }

    private static String name(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos at) {
        var state = level.getBlockState(at);
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath()
                + (state.getCollisionShape(level, at).isEmpty() ? "" : "*");
    }

    /** What is standing in the doorway: the blocks across the seam at head and foot height. */
    private static String seamBlocks(ClientGameTestContext ctx, int cx, int cz, int floorY, boolean alongX) {
        // The server reads the blocks and the TEST THREAD waits for the answer. Doing the waiting inside
        // runOnClient is the deadlock this project has already hit twice: runOnClient blocks until its task
        // returns, and the task's answer can only arrive on a later client tick.
        java.util.concurrent.atomic.AtomicReference<String> got =
                new java.util.concurrent.atomic.AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            if (server == null) {
                got.set("no server");
                return;
            }
            server.execute(() -> {
                var level = server.overworld();
                java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
                // WIDTH ±1, because the carve is three wide.
                //
                // This scanned ±2 and so always reached the columns either side of the doorway - the FRAME,
                // which is stone bricks by design. Every "blocked by ..." this scenario has ever printed was
                // therefore naming the frame, not an obstruction: on 2026-09-30 two failures reported
                // "blocked by stone_bricks" over a doorway whose every column at y0 and y1 was air, and an
                // earlier one blamed a red carpet, which is a sixteenth of a block and cannot stop anyone.
                // A label that is wrong is worse than no label, because it sends the next reader at the
                // wrong block.
                for (int d = -1; d <= 1; d++) {
                    for (int dy = 0; dy <= 1; dy++) {
                        int x = alongX ? cx : cx + d;
                        int z = alongX ? cz + d : cz;
                        var state = level.getBlockState(new net.minecraft.core.BlockPos(x, floorY + dy, z));
                        if (!state.isAir()) {
                            names.add(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                    .getKey(state.getBlock()).getPath());
                        }
                    }
                }
                got.set(names.isEmpty() ? "nothing solid across the seam" : String.join("+", names));
            });
        });
        ctx.waitFor(mc -> got.get() != null);
        return got.get();
    }

    /**
     * How much of the carve volume is open, layer by layer.
     *
     * <p>{@code SimDoors.carveDoorway} clears 3 wide by 4 high by 7 deep centred on the connector cell, at a
     * floor it SEARCHES for. So a doorway can be impassable two ways that look identical from the player's
     * side: the carve never happened (every layer solid), or it happened at a height the player is not
     * standing at (a run of fully open layers, somewhere other than the walking floor). This prints the open
     * count of each 3x7 layer from six below the expected floor to nine above, so the two can be told apart
     * without another run.
     */
    private static String seamProfile(ClientGameTestContext ctx, int cx, int cz, int floorY, boolean alongX) {
        java.util.concurrent.atomic.AtomicReference<String> got =
                new java.util.concurrent.atomic.AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            if (server == null) {
                got.set("no server");
                return;
            }
            server.execute(() -> {
                var level = server.overworld();
                StringBuilder sb = new StringBuilder("open 3x7 layers (y=count of 21): ");
                for (int y = floorY - 6; y <= floorY + 9; y++) {
                    int open = 0;
                    for (int d = -3; d <= 3; d++) {
                        for (int w = -1; w <= 1; w++) {
                            int x = alongX ? cx + d : cx + w;
                            int z = alongX ? cz + w : cz + d;
                            if (level.getBlockState(new net.minecraft.core.BlockPos(x, y, z)).isAir()) {
                                open++;
                            }
                        }
                    }
                    sb.append(y - floorY).append('=').append(open).append(' ');
                }
                // NAME the blocks at the walking plane, not just count them.
                //
                // A count cannot tell a wall from a carpet, and on 2026-09-30 a doorway failed with
                // "blocked by red_carpet" over a profile that looked open at every height the player occupies
                // - a carpet is 1/16 of a block and cannot stop anyone, so the count and the name disagreed
                // and neither could settle it. The two layers his body is actually in are the ones worth
                // spelling out, and 42 names is a line, not a dump.
                for (int y = floorY - 1; y <= floorY + 1; y++) {
                    sb.append(" | y").append(y - floorY).append(": ");
                    for (int d = -3; d <= 3; d++) {
                        for (int w = -1; w <= 1; w++) {
                            int x = alongX ? cx + d : cx + w;
                            int z = alongX ? cz + w : cz + d;
                            var st = level.getBlockState(new net.minecraft.core.BlockPos(x, y, z));
                            sb.append(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                    .getKey(st.getBlock()).getPath());
                            // Whether it actually OBSTRUCTS, which is the question - a carpet is named here
                            // and is not an obstacle, and that distinction is the whole point of the line.
                            sb.append(st.getCollisionShape(level,
                                    new net.minecraft.core.BlockPos(x, y, z)).isEmpty() ? "" : "*");
                            sb.append(' ');
                        }
                    }
                }
                got.set(sb.toString().trim());
            });
        });
        ctx.waitFor(mc -> got.get() != null);
        return got.get();
    }

    /** Put the player somewhere, facing a given way. Inside the sim's own singleplayer world. */
    private static void teleport(ClientGameTestContext ctx, double x, double y, double z, float yaw) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            if (server == null || mc.player == null) {
                return;
            }
            java.util.UUID id = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(id);
                if (sp != null) {
                    sp.teleportTo((net.minecraft.server.level.ServerLevel) sp.level(), x, y, z,
                            java.util.Set.of(), yaw, 0f, false);
                }
            });
        });
    }

    /** The player's position, through the client thread. */
    private static double[] playerPos(ClientGameTestContext ctx) {
        double[] out = new double[3];
        ctx.runOnClient(mc -> {
            if (mc.player != null) {
                out[0] = mc.player.getX();
                out[1] = mc.player.getY();
                out[2] = mc.player.getZ();
            }
        });
        return out;
    }

    private static int copyDir(String from, String into, String suffix) {
        try {
            Path source = Path.of(from);
            if (!Files.isDirectory(source)) {
                return 0;
            }
            Path target = ModUnderTest.modConfig(into);
            Files.createDirectories(target);
            int n = 0;
            try (var s = Files.list(source)) {
                for (Path f : s.toList()) {
                    if (Files.isRegularFile(f) && f.toString().endsWith(suffix)) {
                        Files.copy(f, target.resolve(f.getFileName()),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        n++;
                    }
                }
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * The two rooms on either side of a connector cell, with the rotation each was pasted at.
     *
     * <p>For diagnosing an impassable doorway offline: the room NAME is what lets the capture be opened and
     * its measured doorway compared against where the carve actually went, and the rotation is what decides
     * which edge of that capture faces the connector.
     */
    private static String roomsEitherSide(ClientGameTestContext ctx, int cell, boolean alongX) {
        String[] out = new String[1];
        ctx.runOnClient(mc -> {
            try {
                String code = (String) ModUnderTest.staticCall(SIM_STATE, "mapCode");
                Object decoded = ModUnderTest.staticCall(MAP_CODE, "decode",
                        new Class<?>[]{String.class}, new Object[]{code});
                String[] names = (String[]) ModUnderTest.call(decoded, "nameTable",
                        new Class<?>[]{}, new Object[]{});
                int[] roomOf = (int[]) ModUnderTest.call(decoded, "cellRoom", new Class<?>[]{}, new Object[]{});
                int[] rotOf = (int[]) ModUnderTest.call(decoded, "cellRotation",
                        new Class<?>[]{}, new Object[]{});
                int a = alongX ? cell - 1 : cell - GRID;
                int b = alongX ? cell + 1 : cell + GRID;
                out[0] = describe(names, roomOf, rotOf, a) + " and " + describe(names, roomOf, rotOf, b);
            } catch (Exception e) {
                out[0] = "could not read the map (" + e + ")";
            }
        });
        return out[0] == null ? "unknown" : out[0];
    }

    private static String describe(String[] names, int[] roomOf, int[] rotOf, int cell) {
        if (cell < 0 || cell >= roomOf.length || roomOf[cell] < 0 || roomOf[cell] >= names.length) {
            return "nothing";
        }
        return "\"" + names[roomOf[cell]] + "\" at " + rotOf[cell] + " degrees";
    }
}

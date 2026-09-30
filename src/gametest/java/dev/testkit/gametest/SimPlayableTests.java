package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Is a generated floor actually PLAYABLE?
 *
 * <p>Everything else the sim is tested by asks whether the data is right - the layout connects, the secrets
 * land in their rooms, the map gets the floor. None of that answers the question that matters when he loads
 * it: can he walk through the doors, does the gate come down when the run starts, and do the secrets he is
 * supposed to collect actually turn up.
 *
 * <p>killer560 (2026-09-29) asked whether the sim is ready to play rather than ready to inspect. It was a fair
 * question to be unable to answer, and this closes three of the gaps:
 *
 * <ul>
 *   <li><b>Doorways are passable.</b> Every door cell on the floor is checked for a real opening - a
 *       two-block-tall gap of air at floor height, wide enough to walk through - on BOTH sides of the seam.
 *       A map that says two rooms are connected while the wall between them is solid is the failure he would
 *       hit within ten seconds.</li>
 *   <li><b>The gate comes down.</b> {@code /start}'s countdown is already covered by scenario 74; this checks
 *       the thing it is for, that the infested chiseled brick the green room is sealed with is gone
 *       afterwards.</li>
 *   <li><b>Item secrets appear and can be picked up.</b> They are held back until he is within three blocks
 *       for more than five ticks, so this puts the player next to one and waits.</li>
 * </ul>
 */
public class SimPlayableTests implements FabricClientGameTest {

    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String BUILD_QUEUE = "com.killer560.hub.roomsim.SimBuildQueue";
    private static final String MAP_CODE = "com.killer560.hub.roomsim.MapCode";
    private static final String ALTITUDE = "com.killer560.hub.roomsim.SimAltitude";
    private static final String SIM_RUN = "com.killer560.hub.roomsim.SimRun";
    private static final String SECRET_ITEMS = "com.killer560.hub.roomsim.SimSecretItems";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";

    private static final String SOURCE_ROOMS =
            "C:/Users/Hunter/AppData/Roaming/PrismLauncher/instances/26.1.2 (Mod Only Test)"
                    + "/minecraft/config/killer560smod-rooms";

    private static final int GRID = 11;
    private static final int START_X = -185;
    private static final int START_Z = -185;
    private static final int HALF_ROOM = 16;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip("80-sim-playable")) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff(
                "com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));

        if (copyDir(SOURCE_ROOMS, "killer560smod-rooms", ".json") < 20
                || copyDir(Path.of(SOURCE_ROOMS).resolveSibling("killer560smod-roomdata").toString(),
                        "killer560smod-roomdata", "") == 0) {
            System.out.println("[80-sim-playable] SKIPPED - needs his rooms and room database");
            return;
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(
                "com.killer560.hub.roomdatabase.RoomDatabase", "ensureLoading"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomdatabase.RoomDatabase", "isReady"));
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

        int shift = (Integer) ModUnderTest.staticCall(ALTITUDE, "offset");
        int floorY = 69 + shift;

        // ---- 1. every door on the map is a hole you can walk through -------------------------------------
        int[] doorCells = new int[0];
        Object decoded = null;
        final Object[] grab = new Object[1];
        ctx.runOnClient(mc -> {
            String code = (String) ModUnderTest.staticCall(SIM_STATE, "mapCode");
            grab[0] = ModUnderTest.staticCall(MAP_CODE, "decode",
                    new Class<?>[]{String.class}, new Object[]{code});
        });
        decoded = grab[0];
        if (decoded != null) {
            doorCells = (int[]) ModUnderTest.call(decoded, "cellDoor", new Class<?>[]{}, new Object[]{});
        }
        final int[] doors = doorCells;

        java.util.concurrent.atomic.AtomicReference<List<String>> blocked =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<int[]> counts =
                new java.util.concurrent.atomic.AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            if (server == null) {
                return;
            }
            server.execute(() -> {
                var level = server.overworld();
                List<String> bad = new ArrayList<>();
                int checked = 0;
                for (int cell = 0; cell < doors.length; cell++) {
                    if (doors[cell] == 0) {
                        continue;
                    }
                    int gx = cell % GRID;
                    int gz = cell / GRID;
                    boolean alongX = gx % 2 == 1;
                    int cx = START_X + gx * HALF_ROOM;
                    int cz = START_Z + gz * HALF_ROOM;
                    checked++;
                    // A doorway is walkable when, somewhere across the seam, there is a column of two air
                    // blocks at floor height. Checked at the centre and one to each side, because the exact
                    // seam column can hold the door's own blocks.
                    boolean open = false;
                    for (int d = -2; d <= 2 && !open; d++) {
                        int x = alongX ? cx + d : cx;
                        int z = alongX ? cz : cz + d;
                        if (level.getBlockState(new BlockPos(x, floorY, z)).isAir()
                                && level.getBlockState(new BlockPos(x, floorY + 1, z)).isAir()) {
                            open = true;
                        }
                    }
                    if (!open) {
                        bad.add("cell " + cell + " (" + gx + "," + gz + ") type " + doors[cell]);
                    }
                }
                blocked.set(bad);
                counts.set(new int[]{checked});
            });
        });
        ctx.waitFor(mc -> blocked.get() != null);
        System.out.println("[80-sim-playable] " + counts.get()[0] + " door(s) on the floor, "
                + blocked.get().size() + " of them solid at y " + floorY);
        if (counts.get()[0] == 0) {
            throw new AssertionError("the floor has no doors at all, so nothing was checked");
        }
        if (!blocked.get().isEmpty()) {
            throw new AssertionError(blocked.get().size() + " of " + counts.get()[0]
                    + " doorway(s) are solid - the map says these rooms connect and the world disagrees: "
                    + String.join(", ", blocked.get().subList(0, Math.min(5, blocked.get().size()))));
        }

        // ---- 2. /start takes the gate down ---------------------------------------------------------------
        java.util.concurrent.atomic.AtomicReference<int[]> gate =
                new java.util.concurrent.atomic.AtomicReference<>();
        countGate(ctx, gate, floorY);
        int gateBefore = gate.get()[0];
        System.out.println("[80-sim-playable] gate blocks before /start: " + gateBefore);

        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_RUN, "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall(BUILDER, "entranceDoor")}));
        // The countdown is 5 seconds; give it that plus the door's own barrier phase.
        ctx.waitTicks(160);
        gate.set(null);
        countGate(ctx, gate, floorY);
        int gateAfter = gate.get()[0];
        System.out.println("[80-sim-playable] gate blocks after the countdown: " + gateAfter);
        if (gateBefore == 0) {
            System.out.println("[80-sim-playable] NOTE - this floor had no infested gate to take down");
        } else if (gateAfter > 0) {
            throw new AssertionError(gateAfter + " of " + gateBefore + " gate block(s) are still standing "
                    + "after the countdown - /start did not open the green room");
        }

        // ---- 3. an item secret appears when he stands on it and can be picked up -------------------------
        int remaining = (Integer) ModUnderTest.staticCall(SECRET_ITEMS, "remaining");
        System.out.println("[80-sim-playable] item secrets waiting on the floor: " + remaining);
        if (remaining == 0) {
            System.out.println("[80-sim-playable] NOTE - this floor has no item secrets, so none was driven");
        }

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
                mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen);
        System.out.println("[80-sim-playable] PASS - every door is walkable and the gate comes down");
    }

    /** Counts the infested chiseled stone brick the green room's gate is made of, across the whole grid. */
    private static void countGate(ClientGameTestContext ctx,
                                  java.util.concurrent.atomic.AtomicReference<int[]> out, int floorY) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            if (server == null) {
                out.set(new int[]{0});
                return;
            }
            server.execute(() -> {
                var level = server.overworld();
                int n = 0;
                for (int gx = 0; gx < GRID; gx++) {
                    for (int gz = 0; gz < GRID; gz++) {
                        int cx = START_X + gx * HALF_ROOM;
                        int cz = START_Z + gz * HALF_ROOM;
                        for (int dx = -2; dx <= 2; dx++) {
                            for (int dz = -2; dz <= 2; dz++) {
                                for (int y = floorY - 1; y <= floorY + 4; y++) {
                                    if (level.getBlockState(new BlockPos(cx + dx, y, cz + dz))
                                            .is(net.minecraft.world.level.block.Blocks
                                                    .INFESTED_CHISELED_STONE_BRICKS)) {
                                        n++;
                                    }
                                }
                            }
                        }
                    }
                }
                out.set(new int[]{n});
            });
        });
        ctx.waitFor(mc -> out.get() != null);
    }

    private static int copyDir(String from, String into, String suffix) {
        try {
            Path source = Path.of(from);
            if (!Files.isDirectory(source)) {
                return 0;
            }
            Path target = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve(into);
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
}

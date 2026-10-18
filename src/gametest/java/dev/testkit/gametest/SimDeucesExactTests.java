package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 1435-sim-deuces-exact: Deuces' chest doorway is Hypixel's, block for block, at every turn (mod deuces-exact-1018).
 *
 * <p>killer560 (2026-10-18): "that door under the chest always looks like that regardless of direction for Deuces but it
 * is for it specifically. Just recreate it exactly". The mod pastes {@code DeucesChestDoorway}'s table over the capture's
 * W0 doorway (cobblestone wall jambs, a stone-brick slab lintel, one row of stone bricks on it, air to the wall plane's
 * stone, cobblestone at the box's top corners) and exempts it from {@code RoomDoorPieces} and the carve's kept-trim rule.
 *
 * <p>For Deuces solo (Load a Room) at 0, 90, 180 and 270, and on a two-room floor (Altar on cells x 2..4, z 4..6, Deuces
 * on (6,6), a normal door at (5,6)) with Deuces at 0, 90, 180 and 270, every block of the table is read back from the
 * server's level at the paste's rotation and must be the table's state turned the same way (a wall by block only, as its
 * arms follow its neighbours; air where the solo seal laid diamond counts as sealed). At floor rotation 0 the chest doorway
 * is the one Altar's east doorway opens into, so the carve runs through it and the passage must be open. On every build
 * the door-piece sweep ({@link DoorPieceSweep}) runs with Deuces' chest doorway exempt and must find no wrong doorway;
 * its counts are printed for comparison with 1433's.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimDeucesExactTests implements FabricClientGameTest {

    private static final String NAME = "1435-sim-deuces-exact";
    private static final String ROOM = "Dueces";
    private static final String NEIGHBOUR = "Altar";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ALTITUDE = "com.killer560.hub.roomsim.SimAltitude";
    private static final String MAP_CODE = "com.killer560.hub.roomsim.MapCode";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final int GRID = 11;
    private static final int[] TURNS = {0, 90, 180, 270};

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
            int tableSize = ctx.computeOnClient(mc -> DeucesExact.table(room()).size());
            println("the mod's table for Deuces' chest doorway holds " + tableSize + " block(s)");
            if (tableSize < 30) {
                failures.add("premise: the mod's DeucesChestDoorway table holds only " + tableSize + " block(s)");
                return;
            }
            for (int turn : TURNS) {
                solo(ctx, turn);
                exact(ctx, 4, turn, "solo Deuces at " + turn, failures);
                pieces(ctx, "solo Deuces at " + turn, failures);
            }
            for (int turn : TURNS) {
                floor(ctx, turn);
                exact(ctx, 6, turn, "floor, Deuces at " + turn, failures);
                pieces(ctx, "floor, Deuces at " + turn, failures);
                if (turn == 0) {
                    passage(ctx, failures);
                }
            }
            ctx.runOnClient(mc -> ModUnderTest.staticCall(BUILDER, "setSingleRoomRotationForTest",
                    new Class<?>[]{int.class}, new Object[]{0}));
        } catch (Throwable t) {
            failures.add("threw: " + t);
            t.printStackTrace();
        } finally {
            teardown(ctx);
            if (failures.isEmpty()) {
                System.out.println("[" + NAME + "] PASS - Deuces' chest doorway is the mod's exact table at 0, 90, 180 "
                        + "and 270, solo and on a floor; no other doorway's piece is wrong");
            } else {
                String why = String.join("; ", failures);
                System.out.println("[" + NAME + "] FAIL: " + why);
                SuiteVerdict.failCase(NAME, new AssertionError(why));
            }
        }
    }

    private static Object room() {
        return ModUnderTest.staticCall(ROOM_LIBRARY, "get", new Class<?>[]{String.class}, new Object[]{ROOM});
    }

    private static void exact(ClientGameTestContext ctx, int cell, int turn, String label, List<String> fail) {
        AtomicReference<Object> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            Object room = room();
            int dy = (Integer) ModUnderTest.staticCall(ALTITUDE, "offset");
            BlockPos o = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class, int.class},
                    new Object[]{cell, cell});
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                try {
                    out.set(DeucesExact.check(server.overworld(), room, o.getX() - 16, o.getZ() - 16, dy, turn));
                } catch (Throwable t) {
                    out.set(t);
                }
            });
        });
        ctx.waitFor(mc -> out.get() != null, 1200);
        if (out.get() instanceof Throwable t) {
            t.printStackTrace();
            fail.add(label + ": the check threw " + t);
            return;
        }
        DeucesExact.Result r = (DeucesExact.Result) out.get();
        println("chest doorway, " + label + ": " + r.summary());
        if (r.compared() < 30 || r.notCapture() < 20) {
            fail.add(label + ": premise - only " + r.compared() + " block(s) compared, " + r.notCapture()
                    + " of them unlike the capture");
        }
        if (r.wrong() > 0) {
            fail.add(label + ": the chest doorway differs from the table at " + r.wrong() + " block(s) " + r.samples());
        }
    }

    private static void pieces(ClientGameTestContext ctx, String label, List<String> fail) {
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
            fail.add(label + ": the door-piece sweep threw " + t);
            return;
        }
        DoorPieceSweep.Result r = (DoorPieceSweep.Result) out.get();
        println("door pieces, " + label + ": " + r.summary());
        if (r.exempt() != 1) {
            fail.add(label + ": premise - the sweep exempted " + r.exempt() + " doorway(s), not Deuces' chest doorway alone");
        }
        if (r.wrong() > 0) {
            fail.add(label + ": " + r.wrong() + " doorway(s) carry the wrong door piece " + r.samples());
        }
    }

    /** Floor rotation 0 only: the chest doorway is Altar's way in, carved through - walkable, three high, floored. */
    private static void passage(ClientGameTestContext ctx, List<String> fail) {
        AtomicReference<String> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            int dy = (Integer) ModUnderTest.staticCall(ALTITUDE, "offset");
            BlockPos o = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class, int.class},
                    new Object[]{6, 6});
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                ServerLevel level = server.overworld();
                int blocked = 0;
                int noFloor = 0;
                for (int x = -3; x <= 3; x++) {
                    for (int z = 15; z <= 17; z++) {
                        BlockPos f = new BlockPos(o.getX() - 16 + x, 68 + dy, o.getZ() - 16 + z);
                        if (level.getBlockState(f).getCollisionShape(level, f).isEmpty()) {
                            noFloor++;
                        }
                        for (int y = 69; y <= 71; y++) {
                            BlockPos p = new BlockPos(o.getX() - 16 + x, y + dy, o.getZ() - 16 + z);
                            if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) {
                                blocked++;
                            }
                        }
                    }
                }
                out.set(blocked + " " + noFloor);
            });
        });
        ctx.waitFor(mc -> out.get() != null, 600);
        String[] f = out.get().split(" ");
        println("passage into Altar through the chest doorway (capture x -3..3, z 15..17, y 69..71): " + f[0]
                + " of 63 blocked, floor missing at " + f[1] + " of 21");
        if (!f[0].equals("0") || !f[1].equals("0")) {
            fail.add("floor, Deuces at 0: the chest doorway into Altar is not walkable (" + f[0] + " blocked, " + f[1]
                    + " without floor)");
        }
    }

    private static void floor(ClientGameTestContext ctx, int deucesRotation) {
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

package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 1102-diag-predict-ruledout (mod diag-1101, owner evidence for "If i can see through 1 room into another that middle room
 * will stay grey", to-do item 5): when a tile ends with every placement ruled out, Predictive Rooms logs the rooms that
 * came closest with the seen blocks that disagreed with them - once per tile a floor.
 *
 * <p>On a sim F7 the room behind a free doorway is walled with diamond blocks a block inside its tiles (server side,
 * before he looks), so whatever he sees there matches no capture. A glance into it must write at most
 * {@code DISAGREE_ROOMS} (3) "ruled out" lines for its tile, each naming a placement and listing a diamond block as the
 * block seen; a second glance and settle write no more (once per tile); and the tile gets no prediction (nothing else
 * changed: the predictor still names nothing it cannot match).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimPredictDiagTests implements FabricClientGameTest {

    private static final String NAME = "1102-diag-predict-ruledout";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String HELD = "com.killer560.hub.livemap.HeldMap";
    private static final String PREDICTOR = "com.killer560.hub.livemap.predict.RoomPredictor";
    private static final String PCFG = "com.killer560.hub.livemap.predict.PredictiveRoomsConfig";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        Report.caseStarted(NAME);
        String verdict;
        boolean pass;
        try {
            String why = body(ctx);
            pass = why.startsWith("OK");
            verdict = why;
        } catch (Throwable t) {
            pass = false;
            verdict = "threw: " + t;
            t.printStackTrace(System.out);
        } finally {
            SimPredictHypixelTests.leave(ctx);
        }
        System.out.println("[" + NAME + "] " + (pass ? "PASS" : "FAIL") + " - " + verdict);
        Report.caseFinished(NAME, pass ? "PASS" : "FAIL", "n/a (singleplayer)", verdict, List.of(), List.of());
        SuiteVerdict.finished(NAME);
        if (!pass) {
            throw new AssertionError(verdict);
        }
    }

    private String body(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            ModUnderTest.set(ModUnderTest.config(PCFG), "setEnabled", true);
            mc.options.guiScale().set(2);
        });
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                new Object[]{"gametest"}));
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            ModUnderTest.staticCall(FLOOR_GEN, "generate",
                    new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, 3, 4});
        }));
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        ctx.waitFor(mc -> mc.player.onGround() && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir(), 2400);
        ctx.waitTicks(40);
        SimPredictHypixelTests.Floor f = ctx.computeOnClient(SimPredictHypixelTests::readFloor);
        SimPredictHypixelTests.creativeFlying(ctx);

        // A free doorway from one ordinary room into another single-tile room.
        int[] pick = null;
        for (Map.Entry<Integer, int[]> e : f.doorRooms.entrySet()) {
            for (int[] ab : new int[][]{{e.getValue()[0], e.getValue()[1]}, {e.getValue()[1], e.getValue()[0]}}) {
                if (pick != null || ab[1] == f.entrance || ab[1] == f.blood || ab[0] == f.blood
                        || f.tiles.get(ab[1]).length != 1) {
                    continue;
                }
                int d = e.getKey();
                boolean open = ctx.computeOnClient(mc -> mc.level.getBlockState(f.doorBlock(d)).isAir()
                        && mc.level.getBlockState(f.doorBlock(d).above()).isAir());
                if (open) {
                    pick = new int[]{d, ab[0], ab[1]};
                }
            }
        }
        if (pick == null) {
            return "premise: no free doorway into a 1x1 room";
        }
        int door = pick[0];
        int from = pick[1];
        int into = pick[2];
        int tile = f.tiles.get(into)[0];
        String tileName = (tile % 11) / 2 + "," + (tile / 11) / 2;
        System.out.println("[" + NAME + "] glance from " + f.names.get(from) + " into " + f.names.get(into) + " (tile "
                + tileName + ")");

        // Wall the room behind the doorway with diamond blocks, a block inside its tile. He stands at the doorway first so
        // its chunks are loaded on both sides; what he sees before the wall stands is forgotten by the reset below.
        glance(ctx, f, door, from, 2);
        BlockPos c = f.cellCenter(tile);
        int y0 = f.doorY;
        String fill = "fill " + (c.getX() - 13) + " " + y0 + " " + (c.getZ() - 13) + " " + (c.getX() + 13) + " " + (y0 + 6)
                + " " + (c.getZ() + 13) + " minecraft:diamond_block";
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withSuppressedOutput(), "forceload add " + (c.getX() - 16) + " "
                            + (c.getZ() - 16) + " " + (c.getX() + 16) + " " + (c.getZ() + 16)));
            server.execute(() -> server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withSuppressedOutput(), fill));
        });
        BlockPos probe = new BlockPos(c.getX(), y0 + 2, c.getZ());
        for (int t = 0; t < 400; t++) {
            if (ctx.computeOnClient(mc -> mc.level.getBlockState(probe).is(net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK))) {
                break;
            }
            ctx.waitTicks(1);
        }
        if (!ctx.computeOnClient(mc -> mc.level.getBlockState(probe).is(net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK))) {
            return "premise: the diamond wall never reached the client at " + probe.toShortString() + " (" + fill + ")";
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(HELD, "resetSimVisits"));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(PREDICTOR, "reset"));

        long mark = LogTap.mark();
        String key = "[Predict] tile " + tileName + " ruled out";
        glance(ctx, f, door, from, 2);
        for (int t = 0; t < 200 && lines(mark, key).isEmpty(); t++) {
            if (t % 20 == 0) {
                ctx.runOnClient(mc -> ModUnderTest.staticCall(PREDICTOR, "settle"));
            }
            ctx.waitTicks(1);
        }
        List<String> first = lines(mark, key);
        // Again: a second look from further back, and the decision run again. Once a tile a floor: no more lines.
        glance(ctx, f, door, from, 5);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(PREDICTOR, "settle"));
        ctx.waitTicks(20);
        List<String> all = lines(mark, key);
        boolean predicted = ctx.computeOnClient(mc -> ModUnderTest.staticCall(PREDICTOR, "predictionFor",
                new Class<?>[]{int.class}, new Object[]{tile}) != null);
        for (String l : all) {
            System.out.println("[" + NAME + "]   " + l);
        }
        List<String> summary = lines(mark, "[Predict] tile " + tileName);
        if (first.isEmpty()) {
            return "no 'ruled out' line for tile " + tileName + " (log for the tile: " + summary + ")";
        }
        if (all.size() != first.size()) {
            return "the second look added lines: " + first.size() + " then " + all.size();
        }
        if (first.size() > 3) {
            return first.size() + " lines for one tile (want at most 3)";
        }
        boolean diamond = first.stream().allMatch(l -> l.contains("seen=minecraft:diamond_block")
                || l.contains("no room was ruled out"));
        if (!diamond || first.stream().anyMatch(l -> l.contains("no room was ruled out"))) {
            return "the lines do not list the diamond blocks that ruled the rooms out: " + first;
        }
        if (predicted) {
            return "the walled tile was named anyway";
        }
        return "OK: " + first.size() + " line(s) once for tile " + tileName + ", each listing diamond blocks seen; no prediction";
    }

    private static void glance(ClientGameTestContext ctx, SimPredictHypixelTests.Floor f, int door, int from, double back) {
        double[] dc = f.doorCentre(door);
        double[] u = f.toward(from, door);
        double[] at = new double[]{dc[0] - u[0] * back, dc[1] + 0.2, dc[2] - u[1] * back};
        double[] target = new double[]{dc[0] + u[0] * 12, dc[1] + 1.0, dc[2] + u[1] * 12};
        SimPredictHypixelTests.place(ctx, at, target);
        ctx.waitTicks(20);
    }

    private static List<String> lines(long mark, String part) {
        List<String> out = new ArrayList<>();
        for (String l : LogTap.since(mark)) {
            if (l.contains(part)) {
                out.add(l);
            }
        }
        return out;
    }
}

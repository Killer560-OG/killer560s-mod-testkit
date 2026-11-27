package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 1127-sim-strand-cages (strand-cages-1126): in lane1126-1925's 1126-sim-acquiz-early run Auto Secret flew a 17-warp plan
 * onto Cages' testkit start node (the block {@link SimAutopilotTests#standOf} picks on its main tile, -153,-16,-53 at sim y
 * offset -87) and then every plan out of it failed ("no warp chain out of"), the recovery warp too, until the Autopilot
 * stopped. This rebuilds that floor, stands him on the same block, asks the server's own etherwarp walk what he can
 * reach (ground truth), the planner on the warm graph, and runs the trip out to the Quiz with the executor.
 */
public class SimStrandCagesTests implements FabricClientGameTest {

    static final String NAME = "1127-sim-strand-cages";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String EXECUTOR = "com.killer560.hub.livemap.autoclear.ClearExecutor";

    /** C:/kmtest/wt/qe-strand-code.txt (the evidence run's floor). */
    static final String CODE = "MC2:CxcEUXVpeghFbnRyYW5jZQlPdmVyZ3Jvd24FRmFpcnkLTG9ja2VkIEF3YXkMTXVsdGljb2xvcmVkBk11c2V1bQVNb3NzeQRa"
            + "b2RkBUJsb29kBU1pbmVzC1dhdGVyIEJvYXJkB0JyaWRnZXMFQ2FnZXMITmV3IFRyYXAER29sZAhBbmRlc2l0ZRBSZWRzdG9uZSBX"
            + "YXJyaW9yCkdyYXNzIFJ1aW4JTG9uZyBIYWxsBVNsb3RoC0NyaXNzIENyb3NzCktpbmcgTWlkYXMAFAEAAAAAEgEAEgEAEgEAAAAA"
            + "DwIAAAAAEAEAEAEAEAEBAAAAAAABAAAAAAAAAAAAAAABAAAAAAABAAAAAAABAAAAEwMAAAAADQAADQAADQABAAAACwMACwMACwMA"
            + "AAAAFQMAEwMAAAABAAAAAAAAAAAAAAAACwMACwMACwMAAAABAAAAEwMBAAAAEQMAAAAAAQIBAAAACwMACwMACwMAAAAAFgEAAAAA"
            + "AAAAAAAAAAAAAAAAAAABAAAAAAABAAAAAAABAAAABQABAAAABgEBAAAABwIABwIABwIAAAAADAAAAAAAFwIBAAAAAAAAAAAAAAAA"
            + "BwIABwIABwIAAAAAAAAAAAAAAAAABAIAAAAADgABAAAABwIABwIABwIAAAAACQMDAAAACgABAAAAAAAAAAAAAAABAAAAAAAAAAAA"
            + "AAABAAAAAAAAAAAAAwIEAAAAAgIAAAAACAIACAIACAIACAIACAIACAIACAI";
    /** The evidence run's sim y offset and blocks in it. */
    private static final int EVIDENCE_OFF = -87;
    private static final BlockPos EVIDENCE_STAND = new BlockPos(-153, -16, -53);
    private static final BlockPos EVIDENCE_QUIZ = new BlockPos(-113, -19, -121);

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
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
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "isReady"));
        Object mapCfg = ctx.computeOnClient(mc -> ModUnderTest.config("com.killer560.hub.livemap.LiveMapConfig"));
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> {
            mc.options.renderDistance().set(16);
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                    "setAutoPuzzlesMasterEnabled", false);
            ModUnderTest.set(mapCfg, "setEnabled", true);
            ModUnderTest.set(mapCfg, "setInteractiveMapEnabled", false);
            ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class}, new Object[]{"gametest"});
        });
        List<String> failures = new ArrayList<>();
        try {
            SimPlannerGapsTests.buildFloor(ctx, NAME, CODE);
            body(ctx, failures);
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(EXECUTOR, "cancel"));
            ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
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
        if (failures.isEmpty()) {
            System.out.println("[" + NAME + "] PASS");
        } else {
            System.out.println("[" + NAME + "] FAIL - " + String.join("; ", failures));
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
    }

    private static void body(ClientGameTestContext ctx, List<String> failures) {
        int off = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(LAYOUT, "simYOffset"));
        int dy = off - EVIDENCE_OFF;
        SimAutopilotTests.Status cages = null;
        for (SimAutopilotTests.Status s : ctx.computeOnClient(SimAutopilotTests::readStatus)) {
            if (s.name().equals("Cages")) {
                cages = s;
            }
        }
        if (cages == null) {
            failures.add("Cages is not on the rebuilt floor - not a reproduction");
            return;
        }
        BlockPos stand = SimAutopilotTests.standOf(ctx, cages);
        BlockPos want = EVIDENCE_STAND.above(dy);
        println("sim y offset " + off + "; Cages start node stands on " + (stand == null ? "nothing" : stand.toShortString())
                + " (evidence run: " + want.toShortString() + ")");
        if (stand == null) {
            failures.add("standOf found no block in Cages' main tile");
            return;
        }
        BlockPos quiz = EVIDENCE_QUIZ.above(dy);
        Vec3 feet = new Vec3(want.getX() + 0.5, want.getY() + 1.0, want.getZ() + 0.5);
        var st = ctx.computeOnClient(mc -> mc.level.getBlockState(want).toString());
        println("evidence stand block: " + st);
        if (!SimPlannerGapsTests.place(ctx, feet)) {
            Vec3 at = ctx.computeOnClient(mc -> mc.player.position());
            println("not on the ground at " + SimPlannerGapsTests.fmt(feet) + " - he is at " + SimPlannerGapsTests.fmt(at));
            feet = at;
        }
        SimPlannerGapsTests.dumpAround(ctx, NAME, "stand", feet, quiz);
        println("first warps: " + SimPlannerGapsTests.oracle(ctx, feet, quiz, "Cages", false, 1f));
        String truth = SimPlannerGapsTests.bfsOracle(ctx, feet, quiz);
        println("ground truth to the Quiz " + quiz.toShortString() + ": " + truth);
        String plan = SimPlannerGapsTests.planOnPlanner(ctx, feet, quiz);
        println("planner on the warm graph: " + plan);
        List<String> tripFailures = new ArrayList<>();
        String trip = SimPlannerGapsTests.runTrip(ctx, NAME, "to-quiz", feet, quiz, "Quiz", tripFailures);
        println(trip);
        if (truth.startsWith("sealed")) {
            failures.add("the start node block is a pocket no etherwarp leaves (" + truth + ")");
        } else {
            failures.addAll(tripFailures);
        }
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}

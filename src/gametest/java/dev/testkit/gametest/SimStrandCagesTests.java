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
        SimAutopilotTests.Status quizRoom = null;
        for (SimAutopilotTests.Status s : ctx.computeOnClient(SimAutopilotTests::readStatus)) {
            if (s.name().equals("Cages")) {
                cages = s;
            } else if (s.name().equals("Quiz")) {
                quizRoom = s;
            }
        }
        if (cages == null || quizRoom == null) {
            failures.add("Cages or the Quiz is not on the rebuilt floor - not a reproduction");
            return;
        }
        BlockPos pocket = EVIDENCE_STAND.above(dy);
        BlockPos quiz = EVIDENCE_QUIZ.above(dy);
        // 1. The evidence block: what walls it in, and the server's own etherwarp walk from it (the ground truth).
        Vec3 inPocket = new Vec3(pocket.getX() + 0.5, pocket.getY() + 1.0, pocket.getZ() + 0.5);
        println("sim y offset " + off + "; evidence block " + pocket.toShortString() + ": " + ctx.computeOnClient(mc -> {
            StringBuilder sb = new StringBuilder(mc.level.getBlockState(pocket).getBlock().getName().getString());
            for (int[] d : new int[][]{{1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1}, {1, 2, 0}, {0, 4, 0}}) {
                BlockPos p = pocket.offset(d[0], d[1], d[2]);
                sb.append("; ").append(p.toShortString()).append(' ')
                        .append(mc.level.getBlockState(p).getBlock().getName().getString());
            }
            return sb.toString();
        }));
        String pocketTruth = "not placed";
        if (SimPlannerGapsTests.place(ctx, inPocket)) {
            SimPlannerGapsTests.dumpAround(ctx, NAME, "pocket", inPocket, quiz);
            pocketTruth = SimPlannerGapsTests.bfsOracle(ctx, inPocket, quiz);
        }
        println("ground truth from the evidence block to the Quiz " + quiz.toShortString() + ": " + pocketTruth);
        // 2. Cages' room landing as the mod picks it (TeleportUtils.etherwarpableInTile from the tile center: Autopilot's
        //    tile site, and the testkit's route start node through standOf) must have a way out.
        BlockPos land = SimAutopilotTests.standOf(ctx, cages);
        println("Cages' tile landing (etherwarpableInTile from the center): " + (land == null ? "none" : land.toShortString()));
        if (land == null) {
            failures.add("no landing in Cages' main tile");
            return;
        }
        Vec3 onLand = new Vec3(land.getX() + 0.5, land.getY() + 1.0, land.getZ() + 0.5);
        if (!SimPlannerGapsTests.place(ctx, onLand)) {
            failures.add("could not stand him on Cages' tile landing " + land.toShortString());
            return;
        }
        String landTruth = SimPlannerGapsTests.bfsOracle(ctx, onLand, quiz);
        println("ground truth from Cages' tile landing to the Quiz: " + landTruth);
        if (!landTruth.startsWith("REACHABLE") && !landTruth.startsWith("unsure")) {
            failures.add("Cages' tile landing " + land.toShortString() + " is a pocket no etherwarp leaves (" + landTruth + ")");
        }
        // 3. From the Quiz, a trip onto the evidence block must not leave him in it (the planner refuses, or lands where
        //    he can warp on). Then the Quiz -> Cages -> Quiz round trip on the mod's own landing arrives both ways.
        BlockPos quizLand = SimAutopilotTests.standOf(ctx, quizRoom);
        Vec3 inQuiz = new Vec3(quizLand.getX() + 0.5, quizLand.getY() + 1.0, quizLand.getZ() + 0.5);
        if (!SimPlannerGapsTests.place(ctx, inQuiz)) {
            failures.add("could not stand him in the Quiz on " + quizLand.toShortString());
            return;
        }
        List<String> ignored = new ArrayList<>();
        String into = SimPlannerGapsTests.runTrip(ctx, NAME, "into-pocket", inQuiz, pocket, "Cages", ignored);
        println(into);
        BlockPos stoodOn = ctx.computeOnClient(mc -> BlockPos.containing(mc.player.getX(), mc.player.getY() - 0.2,
                mc.player.getZ()));
        if (stoodOn.equals(pocket) && pocketTruth.startsWith("sealed")) {
            failures.add("a trip flew him onto " + pocket.toShortString() + ", a pocket no etherwarp leaves");
        }
        if (!SimPlannerGapsTests.place(ctx, inQuiz)) {
            failures.add("could not stand him in the Quiz again");
            return;
        }
        List<String> trips = new ArrayList<>();
        println(SimPlannerGapsTests.runTrip(ctx, NAME, "quiz-to-cages", inQuiz, land, "Cages", trips));
        Vec3 at = ctx.computeOnClient(mc -> mc.player.position());
        println(SimPlannerGapsTests.runTrip(ctx, NAME, "cages-to-quiz", at, quizLand, "Quiz", trips));
        failures.addAll(trips);
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}

package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;

/**
 * Does /start actually count down?
 *
 * <p>killer560 has reported this twice - "it says the run is starting in 5 but doesnt count down" (2026-09-28)
 * and "/start still doesnt count down" (2026-09-29). Both times the code read correctly: the tick is
 * registered, the countdown decrements, the door opens. Reading it a third time is not going to find anything
 * the first two readings missed, so this runs it instead.
 *
 * <p>The assertion that matters is that the counter MOVES. A run that reports "starting in 5" and then sits
 * there looks identical, from chat, to one where begin() was never called at all - and the difference is
 * exactly which half is broken.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimRunTests implements FabricClientGameTest {

    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_RUN = "com.killer560.hub.roomsim.SimRun";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String BUILD_QUEUE = "com.killer560.hub.roomsim.SimBuildQueue";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip("74-sim-run")) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        // The auction scan off first.
        //
        // AuctionHouseFeature starts a background scan of the whole auction house the moment a player exists,
        // and it pulls about 43,000 listings across 44 pages, each decoded into an ItemStack. In a gametest
        // client that is enough to wedge the process - scenario 71 froze on exactly that, sixteen seconds
        // after the sim had finished building perfectly. Nothing here is testing the auction house, so the
        // scan is pure interference.
        ctx.runOnClient(mc -> ModUnderTest.turnOff(
                "com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));

        // Without his real rooms AND the room database the sim plans nothing (it waits for the database since mod
        // 0ad55108), never opens a world, and the waits below time out looking like a countdown bug. The testkit is
        // offline, so seed both, as 95 and the puzzle suite do. Unseeded, this also pushed RoomDatabase into retry
        // backoff and the next scenarios timed out too (2026-10-05).
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped("74-sim-run", "needs real room captures and room database");
            return;
        }
        // Room types come from the room database; the sim will not plan a floor without it (mod, 2026-10-05).
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter",
                new Class<?>[]{String.class}, new Object[]{"gametest"}));

        // A real floor, because /start opens the entrance door and a map with no door is a different test.
        long simBuildBefore = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            ModUnderTest.staticCall(FLOOR_GEN, "generate",
                    new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, 3, 3});
        }));
        ctx.waitFor(mc -> mc.level != null);
        Scenario.awaitSimBuild(ctx, simBuildBefore);
        ctx.waitTicks(20);

        // Does the MOD think this is a dungeon?
        //
        // killer560 (2026-09-29): "Make this feel like I am in dungeons." Nearly every dungeon feature here
        // gates on DungeonState, and DungeonState reads the scoreboard sidebar. Nothing put one up inside the
        // sim, so the live map never scanned, no secret waypoint drew, no solver ran and no timer started -
        // the sim was a room to walk around in with the mod switched off. SimSidebar puts up a real
        // Catacombs sidebar on our own integrated server so every feature behaves as it does on Hypixel,
        // with no special case. This asserts the gate it all hangs from.
        ctx.waitTicks(60);
        boolean[] inDungeon = new boolean[1];
        String[] floor = new String[1];
        ctx.runOnClient(mc -> {
            inDungeon[0] = (Boolean) ModUnderTest.staticCall(
                    "com.killer560.hub.secrets.DungeonState", "isInDungeon");
            Object f = ModUnderTest.staticCall("com.killer560.hub.secrets.DungeonState", "getFloor");
            floor[0] = f == null ? null : f.toString();
        });
        System.out.println("[74-sim-run] the mod sees: inDungeon=" + inDungeon[0] + " floor=" + floor[0]);
        if (!inDungeon[0]) {
            throw new AssertionError("inside the sim the mod does not believe it is in a dungeon");
        }
        // The SIDEBAR specifically, read back through the same parser that reads Hypixel's.
        //
        // Asserting isInDungeon() here would have been vacuous: SimState.enter already calls setRoomSim(true),
        // so that gate is open with or without a sidebar. sidebarRoomName() can only answer if a real
        // scoreboard exists and carries a "Room:" line, so this fails if SimSidebar is removed - which is the
        // difference between a test and a green tick.
        String[] room = new String[1];
        ctx.runOnClient(mc -> {
            Object r = ModUnderTest.staticCall(
                    "com.killer560.hub.secrets.DungeonState", "sidebarRoomName");
            room[0] = r == null ? null : r.toString();
        });
        System.out.println("[74-sim-run] the sim's sidebar says room=" + room[0]);
        if (room[0] == null || room[0].isBlank()) {
            throw new AssertionError("the sim put up no readable sidebar - DungeonState.sidebarRoomName() "
                    + "found no \"Room:\" line, so the sim shows none of a real run's information");
        }

        // canAct is the gate on both begin() and the tick, so prove it is open before blaming the countdown.
        boolean[] armed = new boolean[1];
        int[] atStart = new int[1];
        ctx.runOnClient(mc -> {
            ModUnderTest.staticCall(SIM_RUN, "begin",
                    new Class<?>[]{Minecraft.class, net.minecraft.core.BlockPos.class},
                    new Object[]{mc, null});
            armed[0] = (Boolean) ModUnderTest.staticCall(SIM_RUN, "isArmed");
            atStart[0] = (Integer) ModUnderTest.staticCall(SIM_RUN, "countdownTicks");
        });
        System.out.println("[74-sim-run] after begin: armed=" + armed[0] + " ticksLeft=" + atStart[0]);
        if (!armed[0]) {
            throw new AssertionError("begin() did not arm the run - SimState.canAct was false, so /start "
                    + "never starts anything and the countdown is not the bug");
        }
        if (atStart[0] <= 0) {
            throw new AssertionError("begin() armed the run but left the countdown at " + atStart[0]);
        }

        // Twenty ticks is a second. If the counter has not moved by then it is not being ticked at all.
        ctx.waitTicks(20);
        int[] afterOne = new int[1];
        ctx.runOnClient(mc -> afterOne[0] = (Integer) ModUnderTest.staticCall(SIM_RUN, "countdownTicks"));
        System.out.println("[74-sim-run] after 20 ticks: ticksLeft=" + afterOne[0]
                + " (moved " + (atStart[0] - afterOne[0]) + ")");
        if (afterOne[0] >= atStart[0]) {
            throw new AssertionError("the countdown did not move in 20 ticks: still " + afterOne[0]
                    + ". The tick handler is not running - this is exactly what killer560 sees.");
        }

        // And it must actually finish, not merely move. Five seconds plus slack.
        ctx.waitTicks(140);
        boolean[] running = new boolean[1];
        int[] left = new int[1];
        ctx.runOnClient(mc -> {
            running[0] = (Boolean) ModUnderTest.staticCall(SIM_RUN, "isRunning");
            left[0] = (Integer) ModUnderTest.staticCall(SIM_RUN, "countdownTicks");
        });
        System.out.println("[74-sim-run] after 8s: running=" + running[0] + " ticksLeft=" + left[0]);
        if (left[0] != 0) {
            throw new AssertionError("countdown stalled at " + left[0] + " ticks");
        }
        if (!running[0]) {
            throw new AssertionError("countdown reached zero but the run never started");
        }

        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_RUN, "reset"));
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
        System.out.println("[74-sim-run] PASS - the countdown ticks down and the run starts");
    }
}

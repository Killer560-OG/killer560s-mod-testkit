package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 1026-sim-autostart / 1026-sim-autostart-room: the dungeon sim starts a whole floor's run by itself (mod
 * sim-autostart-1025; killer560 2026-10-25: "make dungeon sim auto start and do a 3s countdown remove the /start
 * command").
 * <ul>
 *   <li>1026-sim-autostart: a generated floor, and nothing calls SimRun.begin: once the build finishes the run is armed
 *       with a countdown of at most 3 s (60 ticks) that is seen counting, the entrance gate (infested chiseled stone
 *       bricks round {@code SimBuilder.entranceDoor()}) stands during it, and after it the run is running, the gate is
 *       gone, and the elapsed time from arming to GO is about 3 s (not 5). The /start command is not registered.</li>
 *   <li>1026-sim-autostart-room: a single room (Water Board, {@code SimBuilder.buildSingleRoom}) never arms a run.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimAutoStartTests implements FabricClientGameTest {

    private static final String FLOOR_CASE = "1026-sim-autostart";
    private static final String ROOM_CASE = "1026-sim-autostart-room";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_RUN = "com.killer560.hub.roomsim.SimRun";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String ROOM = "Water Board";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        boolean floor = !Scenario.skip(FLOOR_CASE);
        boolean room = !Scenario.skip(ROOM_CASE);
        if (!floor && !room) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            if (floor) {
                Scenario.skipped(FLOOR_CASE, "needs real room captures and room database");
            }
            if (room) {
                Scenario.skipped(ROOM_CASE, "needs real room captures and room database");
            }
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        if (floor) {
            String v;
            try {
                v = floorCase(ctx);
            } finally {
                leave(ctx);
            }
            System.out.println("[" + FLOOR_CASE + "] " + v);
            if (!v.startsWith("PASS")) {
                throw new AssertionError(v);
            }
        }
        if (room) {
            String v;
            try {
                v = roomCase(ctx);
            } finally {
                leave(ctx);
            }
            System.out.println("[" + ROOM_CASE + "] " + v);
            if (!v.startsWith("PASS")) {
                throw new AssertionError(v);
            }
        }
    }

    private static String floorCase(ClientGameTestContext ctx) {
        // /start is gone from the client dispatcher.
        boolean startCommand = ctx.computeOnClient(mc -> {
            try {
                Object d = Class.forName("net.fabricmc.fabric.impl.command.client.ClientCommandInternals")
                        .getMethod("getActiveDispatcher").invoke(null);
                if (d == null) {
                    return false;
                }
                Object root = d.getClass().getMethod("getRoot").invoke(d);
                return root.getClass().getMethod("getChild", String.class).invoke(root, "start") != null;
            } catch (ReflectiveOperationException e) {
                return false;
            }
        });
        System.out.println("[" + FLOOR_CASE + "] /start registered on the client dispatcher: " + startCommand);

        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter",
                new Class<?>[]{String.class}, new Object[]{"gametest"}));
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object f = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            ModUnderTest.staticCall(FLOOR_GEN, "generate",
                    new Class<?>[]{Minecraft.class, f.getClass(), int.class, int.class},
                    new Object[]{mc, f, 3, 3});
        }));
        ctx.waitFor(mc -> mc.level != null, 2400);
        // Poll every tick from here: the run arms itself on the client thread right after the floor is published.
        int maxSeen = -1;
        int samples = 0;
        int distinct = 0;
        int last = -2;
        long armedAt = -1;
        int gateDuring = -1;
        boolean sawArmed = false;
        for (int t = 0; t < 2400; t++) {
            ctx.waitTicks(1);
            Object[] s = ctx.computeOnClient(mc -> new Object[]{
                    ModUnderTest.staticCall(SIM_RUN, "isArmed"),
                    ModUnderTest.staticCall(SIM_RUN, "countdownTicks"),
                    ModUnderTest.staticCall(SIM_RUN, "isRunning")});
            boolean armed = (Boolean) s[0];
            int left = (Integer) s[1];
            boolean running = (Boolean) s[2];
            if (armed && !running) {
                if (!sawArmed) {
                    sawArmed = true;
                    armedAt = System.nanoTime();
                    System.out.println("[" + FLOOR_CASE + "] armed by itself, " + left + " tick(s) left at first sight");
                }
                samples++;
                maxSeen = Math.max(maxSeen, left);
                if (left != last) {
                    distinct++;
                    last = left;
                }
                if (gateDuring < 0 && left <= 40) {
                    gateDuring = gateBlocks(ctx);
                }
            }
            if (running) {
                break;
            }
        }
        if (!sawArmed) {
            return "FAIL - the floor was built and no run armed by itself within 120 s (SimRun.isArmed never true)";
        }
        long goNanos = System.nanoTime() - armedAt;
        boolean running = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_RUN, "isRunning"));
        ctx.waitTicks(30);
        int gateAfter = gateBlocks(ctx);
        BlockPos door = ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(BUILDER, "entranceDoor"));
        System.out.println(String.format("[%s] countdown: max %d tick(s) seen, %d sample(s), %d distinct value(s); "
                + "armed->GO %.2f s; running %s; gate blocks round %s during %d, after %d", FLOOR_CASE, maxSeen, samples,
                distinct, goNanos / 1e9, running, door, gateDuring, gateAfter));
        if (startCommand) {
            return "FAIL - /start is still registered on the client dispatcher";
        }
        if (maxSeen > 60) {
            return "FAIL - the countdown was " + maxSeen + " ticks, longer than 3 s";
        }
        if (distinct < 10) {
            return "FAIL - the countdown was seen at only " + distinct + " value(s): it did not count";
        }
        if (!running) {
            return "FAIL - the countdown ended but the run is not running";
        }
        if (goNanos < 1.5e9 || goNanos > 6e9) {
            return String.format("FAIL - armed to GO took %.2f s, not about 3 s", goNanos / 1e9);
        }
        if (door == null) {
            return "FAIL premise - the floor has no entrance door";
        }
        if (gateDuring <= 0) {
            return "FAIL - no gate block stood round the entrance during the countdown (" + gateDuring + ")";
        }
        if (gateAfter != 0) {
            return "FAIL - " + gateAfter + " gate block(s) still stand after GO";
        }
        return "PASS - the floor armed its own run, counted " + maxSeen + " tick(s) down, the gate (" + gateDuring
                + " block(s)) opened at GO";
    }

    private static String roomCase(ClientGameTestContext ctx) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildSingleRoom",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, ROOM})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        boolean generated = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_STATE, "isGeneratedFloor"));
        boolean inSim = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_STATE, "canAct", new Class<?>[]{Minecraft.class}, new Object[]{mc}));
        boolean armedEver = false;
        for (int t = 0; t < 200; t++) {
            ctx.waitTicks(1);
            boolean a = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_RUN, "isArmed")
                    || (Boolean) ModUnderTest.staticCall(SIM_RUN, "isRunning"));
            armedEver |= a;
        }
        System.out.println("[" + ROOM_CASE + "] single room " + ROOM + ": in the sim " + inSim + ", generated floor "
                + generated + ", a run armed in 10 s: " + armedEver);
        if (!inSim) {
            return "FAIL premise - the room loaded but SimState.canAct is false";
        }
        if (generated) {
            return "FAIL premise - a single room reads as a generated floor";
        }
        if (armedEver) {
            return "FAIL - a single room started a run";
        }
        return "PASS - a single room never armed a run";
    }

    /** Infested chiseled stone bricks (the entrance gate) within 3 blocks of the entrance door, on the server. */
    private static int gateBlocks(ClientGameTestContext ctx) {
        AtomicReference<Integer> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            BlockPos door = (BlockPos) ModUnderTest.staticCall(BUILDER, "entranceDoor");
            if (server == null || door == null) {
                out.set(-1);
                return;
            }
            server.execute(() -> {
                var level = server.overworld();
                int n = 0;
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        for (int dy = -3; dy <= 6; dy++) {
                            if (level.getBlockState(door.offset(dx, dy, dz)).is(Blocks.INFESTED_CHISELED_STONE_BRICKS)) {
                                n++;
                            }
                        }
                    }
                }
                out.set(n);
            });
        });
        ctx.waitFor(mc -> out.get() != null, 400);
        return out.get();
    }

    private static void leave(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            try {
                ModUnderTest.staticCall(SIM_RUN, "reset");
                ModUnderTest.staticCall(SIM_STATE, "leave", new Class<?>[]{}, new Object[]{});
            } catch (Throwable ignored) {
                // cleanup never replaces a verdict
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
        ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen, 1200);
    }
}

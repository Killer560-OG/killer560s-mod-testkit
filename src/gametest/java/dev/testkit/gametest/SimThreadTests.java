package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The sim's two threading bugs (2026-10-06), each driven the way it failed.
 *
 * <p><b>97-sim-goto-loop</b>: build an F7 and {@code /goto} every room on it, twice round, through the command's own
 * method on the render thread (as typing it runs). The mod's goTo used to scan the ServerLevel from the render thread,
 * and in this client/server lockstep a column in a chunk the server had unloaded made getBlockState load it there and
 * park the render thread forever (99-sim-im's jstack, 2026-10-05). On that jar this case hangs at the first far room and
 * the freeze watcher shoots the client; the last "goto i/N" line says where. Each goto must MOVE him into the named
 * room's tile - a loop of no-ops would pass otherwise.
 *
 * <p><b>97-sim-leave-build</b>: start an F7 build and leave the world while it is still pasting, several times at
 * different points, then build a flat test room in a fresh sim world and require it to finish. Fabric fires DISCONNECT on
 * the netty thread; the mod's SimWorld.onWorldUnloaded ran its whole reset there, beside the render thread's teardown.
 * Records which thread DISCONNECT fired on (this scenario's own listener), the mod's "[SimPhase] world unloaded" line
 * when the jar has one (which thread handled it), the sim flag and build queue after each leave, and every mod ERROR.
 */
public class SimThreadTests implements FabricClientGameTest {

    private static final String GOTO = "97-sim-goto-loop";
    private static final String LEAVE = "97-sim-leave-build";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_INDEX = "com.killer560.hub.roomsim.SimRoomIndex";
    private static final String TELEPORT = "com.killer560.hub.roomsim.SimTeleportCommands";
    private static final String BUILD_QUEUE = "com.killer560.hub.roomsim.SimBuildQueue";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final int GRID = 11;

    private static final List<String> DISCONNECT_THREADS = new CopyOnWriteArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        boolean doGoto = !Scenario.skip(GOTO);
        boolean doLeave = !Scenario.skip(LEAVE);
        if (!doGoto && !doLeave) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            System.out.println("[97-sim-threads] SKIPPED - needs his real rooms and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "isReady"), 1000);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                DISCONNECT_THREADS.add(Thread.currentThread().getName()));
        List<String> failed = new ArrayList<>();
        if (doGoto) {
            List<String> failures = new ArrayList<>();
            try {
                gotoLoop(ctx, failures);
            } catch (Throwable t) {
                failures.add("scenario threw: " + t);
                t.printStackTrace(System.out);
            } finally {
                leave(ctx);
            }
            verdict(GOTO, failures, failed);
        }
        if (doLeave) {
            List<String> failures = new ArrayList<>();
            try {
                leaveDuringBuild(ctx, failures);
            } catch (Throwable t) {
                failures.add("scenario threw: " + t);
                t.printStackTrace(System.out);
            } finally {
                leave(ctx);
            }
            verdict(LEAVE, failures, failed);
        }
        if (!failed.isEmpty()) {
            throw new AssertionError(String.join("; ", failed));
        }
    }

    // ---------------------------------------------------------------- /goto

    private static void gotoLoop(ClientGameTestContext ctx, List<String> failures) {
        long mark = LogTap.mark();
        buildF7(ctx);
        ctx.waitTicks(60);
        List<Object[]> rooms = ctx.computeOnClient(mc -> {
            List<Object[]> out = new ArrayList<>();
            for (Object p : (List<?>) ModUnderTest.staticCall(ROOM_INDEX, "placed")) {
                String name = (String) ModUnderTest.call(p, "name", new Class<?>[]{}, new Object[]{});
                int gx = (Integer) ModUnderTest.call(p, "gridX", new Class<?>[]{}, new Object[]{});
                int gz = (Integer) ModUnderTest.call(p, "gridZ", new Class<?>[]{}, new Object[]{});
                int[] cells = (int[]) ModUnderTest.call(p, "cells", new Class<?>[]{}, new Object[]{});
                if (name != null && !name.isBlank() && out.stream().noneMatch(o -> o[0].equals(name))) {
                    out.add(new Object[]{name, gx, gz, cells});
                }
            }
            return out;
        });
        if (rooms.size() < 10) {
            failures.add("only " + rooms.size() + " room(s) on the floor - the build did not happen");
            return;
        }
        int total = rooms.size() * 2;
        int reached = 0;
        double travelled = 0;
        for (int i = 0; i < total; i++) {
            Object[] room = rooms.get(i % rooms.size());
            String name = (String) room[0];
            int gx = (Integer) room[1];
            int gz = (Integer) room[2];
            double[] from = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
            println(GOTO, "goto " + (i + 1) + "/" + total + " " + name + " (cell " + gx + "," + gz + ") from "
                    + fmt(from[0]) + "," + fmt(from[2]));
            // As the command runs: a render-thread task, not inside the test thread's runOnClient wait.
            ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(TELEPORT, "goTo",
                    new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, name})));
            BlockPos c = ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter",
                    new Class<?>[]{int.class}, new Object[]{gz * GRID + gx}));
            // ANY tile of the room. Since mod sim-roomcycle2 (2026-10-06) /goto lands by a doorway on the room's
            // main floor, which for a multi-tile room is often not the top-left tile this used to require.
            List<BlockPos> tiles = new ArrayList<>();
            for (int cell : (int[]) room[3]) {
                tiles.add(ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter",
                        new Class<?>[]{int.class}, new Object[]{cell})));
            }
            boolean in = false;
            for (int t = 0; t < 100 && !in; t++) {
                ctx.waitTicks(1);
                in = ctx.computeOnClient(mc -> tiles.stream().anyMatch(tc ->
                        Math.abs(mc.player.getX() - tc.getX()) <= 18 && Math.abs(mc.player.getZ() - tc.getZ()) <= 18));
            }
            double[] to = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
            travelled += Math.hypot(to[0] - from[0], to[2] - from[2]);
            if (in) {
                reached++;
            } else {
                failures.add("goto " + name + " left him at " + fmt(to[0]) + "," + fmt(to[2]) + ", tile centre "
                        + c.getX() + "," + c.getZ());
            }
            ctx.waitTicks(5);
        }
        println(GOTO, "reached " + reached + " of " + total + " gotos, travelled " + fmt(travelled) + " blocks");
        if (travelled < 100) {
            failures.add("travelled only " + fmt(travelled) + " blocks across " + total + " gotos - nothing moved him");
        }
        for (String e : LogTap.modErrorsSince(mark)) {
            failures.add("mod ERROR: " + e);
        }
    }

    // ---------------------------------------------------------------- leave mid-build

    private static void leaveDuringBuild(ClientGameTestContext ctx, List<String> failures) {
        long mark = LogTap.mark();
        int leaves = 5;
        for (int i = 0; i < leaves; i++) {
            int threadsBefore = DISCONNECT_THREADS.size();
            long before = Scenario.simBuildCount(ctx);
            ctx.runOnClient(mc -> mc.execute(() -> {
                Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
                ModUnderTest.staticCall(FLOOR_GEN, "generate",
                        new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                        new Object[]{mc, floor, 3, 4});
            }));
            ctx.waitFor(mc -> mc.level != null, 2000);
            int busyAt = -1;
            for (int t = 0; t < 600; t++) {
                if (ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(BUILD_QUEUE, "isBusy"))) {
                    busyAt = t;
                    break;
                }
                ctx.waitTicks(1);
            }
            if (busyAt < 0) {
                failures.add("leave " + (i + 1) + ": the build never started");
                leave(ctx);
                continue;
            }
            int delay = 2 + i * 4;   // leave at a different point of the paste each time
            ctx.waitTicks(delay);
            boolean stillBusy = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(BUILD_QUEUE, "isBusy"));
            long finished = Scenario.simBuildCount(ctx);
            leave(ctx);
            boolean active = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_STATE, "isActive"));
            boolean busy = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(BUILD_QUEUE, "isBusy"));
            String fired = DISCONNECT_THREADS.size() > threadsBefore
                    ? String.join(",", DISCONNECT_THREADS.subList(threadsBefore, DISCONNECT_THREADS.size())) : "none";
            println(LEAVE, "leave " + (i + 1) + "/" + leaves + ": " + delay + " tick(s) into the paste, still pasting "
                    + stillBusy + ", build finished " + (finished > before) + "; DISCONNECT fired on [" + fired
                    + "]; after: sim active " + active + ", queue busy " + busy);
            if (active) {
                failures.add("leave " + (i + 1) + ": the sim flag outlived the world");
            }
            if (busy) {
                failures.add("leave " + (i + 1) + ": the build queue still held jobs after the world closed");
            }
        }
        // The world after: a fresh sim must build and let him in.
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildFlatTest",
                new Class<?>[]{Minecraft.class}, new Object[]{mc})));
        ctx.waitFor(mc -> mc.level != null, 2000);
        Scenario.awaitSimBuild(ctx, before);
        boolean flatActive = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_STATE, "isActive"));
        int screenWait = 0;
        while (screenWait < 200 && ctx.computeOnClient(mc -> McCompat.screen(mc) != null)) {
            ctx.waitTicks(1);
            screenWait++;
        }
        String screen = ctx.computeOnClient(mc -> McCompat.screen(mc) == null ? "none"
                : McCompat.screen(mc).getClass().getSimpleName());
        println(LEAVE, "fresh flat test room after the leaves: built, sim active " + flatActive + ", screen " + screen);
        if (!flatActive) {
            failures.add("the fresh sim world after the leaves never turned the sim on");
        }
        if (!"none".equals(screen)) {
            failures.add("the fresh sim world kept a screen up: " + screen);
        }
        int handledRender = 0;
        int unloadLines = 0;
        for (String line : LogTap.since(mark)) {
            if (line.contains("[SimPhase] world unloaded")) {
                unloadLines++;
                if (line.contains("handled on Render thread")) {
                    handledRender++;
                }
                println(LEAVE, "mod: " + line.substring(Math.max(0, line.indexOf("[SimPhase]"))));
            }
        }
        println(LEAVE, unloadLines == 0
                ? "the mod logs no '[SimPhase] world unloaded' line (a jar before the fix): its reset ran on the "
                        + "DISCONNECT thread above"
                : handledRender + " of " + unloadLines + " unload(s) handled on the render thread");
        if (unloadLines > 0 && handledRender != unloadLines) {
            failures.add("only " + handledRender + " of " + unloadLines + " unloads were handled on the render thread");
        }
        for (String e : LogTap.modErrorsSince(mark)) {
            failures.add("mod ERROR: " + e);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void buildF7(ClientGameTestContext ctx) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            ModUnderTest.staticCall(FLOOR_GEN, "generate",
                    new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, 3, 4});
        }));
        ctx.waitFor(mc -> mc.level != null, 2000);
        Scenario.awaitSimBuild(ctx, before);
    }

    /** Same leave as 97-leave-loop / 84. */
    private static void leave(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.execute(() -> {
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                mc.disconnectWithSavingScreen();
            }
        }));
        ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 2000);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> mc.execute(() ->
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen);
    }

    /** Prints the case's verdict; a failure is thrown only once both cases have run. */
    private static void verdict(String name, List<String> failures, List<String> failed) {
        if (failures.isEmpty()) {
            println(name, "PASS");
            return;
        }
        for (String f : failures) {
            println(name, "FAIL " + f);
        }
        failed.add("[" + name + "] " + failures.size() + " failure(s): " + failures.get(0));
    }

    private static void println(String name, String line) {
        System.out.println("[" + name + "] " + line);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}

package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 1460-sim-server-lag (mod branch server-lag-1015). killer560, 2026-10-15: "Make sure interactive map detects server lag
 * and will not proc the warning if the server lags preventing it from etherwarping." Only when named.
 *
 * <p>A generated sim F7, an Aspect of the Void in hand, the Interactive Map on. Two map presses to far rooms (the same
 * call a map click makes, {@code AutoClearUtils.pathToRoom}):
 * <ol>
 *   <li><b>control</b>, no lag: the press's hop count and the client ticks from press to arrival are printed, to compare
 *   with the same scenario on a main jar (the timing of the normal case must not change);</li>
 *   <li><b>lag</b>: the moment the path's first hop is sent, nothing from the server reaches the client for
 *   {@link #STALL_MS} ({@link dev.testkit.harness.LagSpike}: no landing, no per-tick ping, then all of it at once) -
 *   longer than the executor's 20-tick "warp N never landed" stall. (Blocking the integrated server's thread cannot
 *   do this: the gametest ticks client and server in lockstep, so the client would stop ticking too.) The path must finish with no "never landed" / "off the plan" line and no
 *   failure, and the mod's {@code util.ServerLag} must have measured the stall (so the pass is not vacuous).</li>
 * </ol>
 * On a main jar the lag press says "Warp N never landed - planning again from here" (the bug) and there is no ServerLag.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimServerLagTests implements FabricClientGameTest {

    private static final String NAME = "1460-sim-server-lag";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    private static final String CLEAR_UTILS = "com.killer560.hub.livemap.autoclear.AutoClearUtils";
    private static final String EXECUTOR = "com.killer560.hub.livemap.autoclear.ClearExecutor";
    private static final String AUTO_CONFIG = "com.killer560.hub.autopuzzles.AutoPuzzlesConfig";
    private static final String SERVER_LAG = "com.killer560.hub.util.ServerLag";
    /** Longer than ClearExecutor's STALL_TICKS (20 ticks = 1 s) with room to spare. */
    static final long STALL_MS = 2500L;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        boolean cheat = ctx.computeOnClient(mc -> {
            try {
                return Class.forName("com.killer560.hub.BuildVariant").getField("CHEAT_FEATURES_ENABLED").getBoolean(null);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        if (!cheat) {
            Scenario.skipped(NAME, "legit jar: no Interactive Map");
            return;
        }
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        List<String> failures = new ArrayList<>();
        try {
            ctx.runOnClient(mc -> {
                ModUnderTest.set(ModUnderTest.config(AUTO_CONFIG), "setAutoPuzzlesMasterEnabled", false);
                Object map = ModUnderTest.config(LIVE_MAP_CONFIG);
                ModUnderTest.set(map, "setEnabled", true);
                ModUnderTest.set(map, "setInteractiveMapEnabled", true);
                mc.options.renderDistance().set(16);
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
            ctx.waitFor(mc -> mc.level != null, 2400);
            Scenario.awaitSimBuild(ctx, before);
            ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
            ctx.waitFor(mc -> mc.player.onGround() && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir(),
                    2400);
            SimEtherTicksTests.awaitFloorChunks(ctx);
            SimEtherTicksTests.giveAotv(ctx, failures);
            ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "begin",
                    new Class<?>[]{Minecraft.class, BlockPos.class},
                    new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
            for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                    "com.killer560.hub.roomsim.SimRun", "isRunning")); i++) {
                ctx.waitTicks(1);
            }
            ctx.waitTicks(100);   // the floor graph warms (Interactive Map on, in a dungeon)
            List<int[]> rooms = ctx.computeOnClient(SimEtherTicksTests::farthestRooms);
            int next = press(ctx, rooms, 0, false, failures);
            if (next >= 0) {
                // The lag press goes to another far room, from wherever the control left him.
                rooms = ctx.computeOnClient(SimEtherTicksTests::farthestRooms);
                press(ctx, rooms, 0, true, failures);
            }
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> {
                mc.options.renderDistance().set(renderBefore);
                ModUnderTest.staticCall(SIM_STATE, "leave");
            });
            ctx.runOnClient(mc -> mc.execute(() -> {
                if (mc.level != null) {
                    mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                    mc.disconnectWithSavingScreen();
                }
            }));
            ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 2400);
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> mc.execute(() ->
                    McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
            ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen, 2400);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
        System.out.println("[" + NAME + "] PASS");
    }

    /**
     * Presses the farthest rooms (up to 5 tries) until one press makes 3+ hops over 20+ blocks; with {@code lag}, a
     * {@link dev.testkit.harness.LagSpike} of {@link #STALL_MS} starts as the path's first hop goes out.
     * @return the attempt used, or -1 when none measured.
     */
    private static int press(ClientGameTestContext ctx, List<int[]> rooms, int from, boolean lag, List<String> failures) {
        String label = lag ? "lag" : "control";
        for (int attempt = from; attempt < Math.min(from + 5, rooms.size()); attempt++) {
            int[] t = rooms.get(attempt);
            for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> mc.player.onGround()); i++) {
                ctx.waitTicks(1);
            }
            Vec3 start = ctx.computeOnClient(mc -> mc.player.position());
            long hops0 = ctx.computeOnClient(mc -> SimEtherTicksTests.hopMark());
            int stalls0 = lagStalls(ctx);
            long log = LogTap.mark();
            if (lag) {
                ctx.runOnClient(mc -> dev.testkit.harness.LagSpike.armOnNextUse(STALL_MS));
            }
            Boolean ok = ctx.computeOnClient(mc -> {
                Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
                return (Boolean) ModUnderTest.staticCall(CLEAR_UTILS, "pathToRoom",
                        new Class<?>[]{layout.getClass(), int.class, int.class, int.class},
                        new Object[]{layout, t[1], t[0], 0});
            });
            boolean busySeen = false;
            int ticks = 0;
            int firstHopTick = -1;
            int busyAtRelease = -1;
            for (int i = 0; i < 900 && Boolean.TRUE.equals(ok); i++) {
                ctx.waitTicks(1);
                ticks++;
                int hops = ctx.computeOnClient(mc -> SimEtherTicksTests.hopsSince(hops0).size());
                if (firstHopTick < 0 && hops > 0) {
                    firstHopTick = ticks;
                }
                boolean busy = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isBusy"));
                busySeen |= busy;
                if (lag && busyAtRelease < 0 && dev.testkit.harness.LagSpike.releasedAt() != 0L) {
                    busyAtRelease = busy ? 1 : 0;
                }
                if (busySeen && !busy && (!lag || dev.testkit.harness.LagSpike.releasedAt() != 0L
                        || dev.testkit.harness.LagSpike.startedAt() == 0L)) {
                    break;
                }
            }
            ctx.runOnClient(mc -> dev.testkit.harness.LagSpike.disarm());
            ctx.waitTicks(5);
            Vec3 end = ctx.computeOnClient(mc -> mc.player.position());
            double moved = start.distanceTo(end);
            int hops = ctx.computeOnClient(mc -> SimEtherTicksTests.hopsSince(hops0).size());
            boolean failed = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "lastPathFailed"));
            List<String> lines = LogTap.since(log);
            List<String> warnings = new ArrayList<>();
            for (String l : lines) {
                if (l.contains("never landed") || l.contains("off the plan") || l.contains("Stopped:")
                        || l.contains("planning again from here")) {
                    warnings.add(l);
                }
            }
            println(String.format("%s: press to room %d (tile %d) %s; %d hop(s), first hop %d tick(s) after the press, "
                            + "busy until tick %d, moved %.1f blocks, failed %s, warnings %d", label, t[1], t[0],
                    Boolean.TRUE.equals(ok) ? "taken" : "REFUSED", hops, firstHopTick, ticks, moved, failed,
                    warnings.size()));
            long spike = dev.testkit.harness.LagSpike.releasedAt() - dev.testkit.harness.LagSpike.startedAt();
            if (lag) {
                println(String.format("lag: spike %d ms holding %d packet(s), the path still running at its end %s; "
                                + "ServerLag stalls seen +%d, longest %d ms", spike, dev.testkit.harness.LagSpike.held(),
                        busyAtRelease == 1, lagStalls(ctx) - stalls0, lagLongest(ctx)));
            }
            for (String w : warnings) {
                println("  " + label + " warning: " + w);
            }
            if (hops < 3 || moved < 20) {
                continue;   // a room too close or unreachable: try the next farthest
            }
            if (lag) {
                if (dev.testkit.harness.LagSpike.startedAt() == 0L || spike < STALL_MS - 100) {
                    failures.add("lag: no lag spike ran (" + spike + " ms) - the run proves nothing");
                } else if (busyAtRelease != 1) {
                    failures.add("lag: the path was not still running when the spike ended - the run proves nothing");
                }
                if (ticks < STALL_MS / 50) {
                    failures.add("lag: the client ran only " + ticks + " tick(s) across a " + STALL_MS + " ms spike - "
                            + "its timeouts never ran, so the run proves nothing");
                }
                if (lagLongest(ctx) < STALL_MS - 500) {
                    failures.add("lag: util.ServerLag did not measure the " + STALL_MS + " ms stall (longest "
                            + lagLongest(ctx) + " ms)");
                }
                if (!warnings.isEmpty()) {
                    failures.add("lag: a server stall was reported as a failed warp: " + warnings.get(0));
                }
                if (failed) {
                    failures.add("lag: the path failed under a server stall");
                }
            } else if (!warnings.isEmpty() || failed) {
                failures.add("control: the no-lag press failed or warned (" + (warnings.isEmpty() ? "failed"
                        : warnings.get(0)) + ") - the lag run would prove nothing");
            }
            return attempt;
        }
        failures.add(label + ": no press made 3+ hops over 20+ blocks to measure");
        return -1;
    }

    /** util.ServerLag's stall count, or -1 on a jar without it (main before server-lag-1015). */
    static int lagStalls(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            try {
                Class.forName(SERVER_LAG);
            } catch (ClassNotFoundException e) {
                return -1;
            }
            return (Integer) ModUnderTest.staticCall(SERVER_LAG, "stallsSeen");
        });
    }

    /** util.ServerLag's longest measured stall in ms, or -1 on a jar without it. */
    static long lagLongest(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            try {
                Class.forName(SERVER_LAG);
            } catch (ClassNotFoundException e) {
                return -1L;
            }
            return (Long) ModUnderTest.staticCall(SERVER_LAG, "longestStallMs");
        });
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}

package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.PacketTrace;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 1126-sim-ac-gatebarrier (mod autopilot-clears-1126, his 11-26 Hypixel clears, logger session 20261125-232944-36672):
 * one tick after Mort's line Hypixel turns the green room gate's infested bricks into BARRIERS, and only about 15 ticks
 * later into air (every run that night). The map is in hand by then, so the mod took the gate for open: a trip planned in
 * those ticks met the barriers ("Room hop 2 of 5 failed: no warp chain"), a key room walk failed four ticks running and
 * was given up 0.2 s in, and the first warp went 6-89 ticks after the gate was really gone.
 *
 * <p>The sim drops its gate to air at once, so this case plays Hypixel's part: the moment the run starts it puts BARRIERS
 * where the gate stood, presses the Interactive Map trip ({@code AutoClearUtils.pathToRoom}, the call Auto Secret and the
 * Autopilot make) to a room two doors out, and clears the barriers 15 ticks later. Pass: while the barriers stand the
 * trip waits on the gate (ClearExecutor.waitingForGate, and entranceGateShut for Auto Clear's key room walk), no
 * use_item goes, he does not move; the first use_item goes the tick the client sees the barriers gone, or the next; the
 * trip ends in the room pressed.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimGateBarrier1126Tests implements FabricClientGameTest {

    private static final String NAME = "1126-sim-ac-gatebarrier";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_RUN = "com.killer560.hub.roomsim.SimRun";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String CLEAR_UTILS = "com.killer560.hub.livemap.autoclear.AutoClearUtils";
    private static final String EXECUTOR = "com.killer560.hub.livemap.autoclear.ClearExecutor";
    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    /** Hypixel's barrier window, measured on 11-26: 15-16 ticks from the barriers to air. */
    private static final int BARRIER_TICKS = 15;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (!dev.testkit.gametest.mod.Mod.isCheat()) {
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
        String verdict;
        boolean pass;
        try {
            ctx.runOnClient(mc -> {
                ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                        "setAutoPuzzlesMasterEnabled", false);
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
            ctx.runOnClient(mc -> {
                ModUnderTest.staticCall(SIM_RUN, "reset");
                SimPrerunTripTests.rearm(mc);
            });
            for (int i = 0; i < 2400 && !ctx.computeOnClient(mc -> McCompat.screen(mc) == null && mc.player != null
                    && mc.player.onGround() && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir()); i++) {
                ctx.waitTicks(1);
                if (i % 20 == 0) {
                    ctx.runOnClient(SimPrerunTripTests::rearm);
                }
            }
            SimPrerunTripTests.giveAotv(ctx);
            for (int i = 0; i < 100; i++) {    // the floor graph warms; the run stays held
                ctx.waitTicks(1);
                if (i % 20 == 0) {
                    ctx.runOnClient(SimPrerunTripTests::rearm);
                }
            }
            List<BlockPos> gate = ctx.computeOnClient(SimPrerunTripTests::gateBlocks);
            int[] target = ctx.computeOnClient(SimPrerunTripTests::twoDoorsOut);
            // Let the countdown run out, then play Hypixel: barriers where the gate stood.
            boolean started = false;
            for (int t = 0; t < 600 && !started; t++) {
                ctx.waitTick();
                started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_RUN, "hasStarted"));
            }
            if (started && !gate.isEmpty()) {
                setGate(ctx, gate, true);
            }
            boolean barriersIn = false;
            for (int t = 0; t < 20 && !barriersIn; t++) {
                ctx.waitTick();
                barriersIn = ctx.computeOnClient(mc -> allAre(mc, gate, true));
            }
            println("run started " + started + ", " + gate.size() + " gate block(s) now barriers " + barriersIn
                    + ", target " + (target == null ? "none" : target[1] + " tile " + target[0]));
            if (!started || gate.isEmpty() || target == null || !barriersIn) {
                verdict = "premise: run started " + started + ", gate blocks " + gate.size() + ", target " + (target != null)
                        + ", barriers in " + barriersIn;
                pass = false;
            } else {
                Vec3 from = ctx.computeOnClient(mc -> mc.player.position());
                ctx.runOnClient(mc -> PacketTrace.start());
                Boolean ok = ctx.computeOnClient(mc -> {
                    Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
                    return (Boolean) ModUnderTest.staticCall(CLEAR_UTILS, "pathToRoom",
                            new Class<?>[]{layout.getClass(), int.class, int.class, int.class},
                            new Object[]{layout, target[1], target[0], 0});
                });
                boolean waitingSeen = false;
                String shutSeen = "no";
                int usesHeld = 0;
                double movedHeld = 0;
                for (int t = 1; t <= BARRIER_TICKS; t++) {
                    ctx.waitTick();
                    waitingSeen |= ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "waitingForGate"));
                    if (t == 2) {
                        shutSeen = ctx.computeOnClient(mc -> {
                            try {
                                return String.valueOf(ModUnderTest.staticCall(EXECUTOR, "entranceGateShut"));
                            } catch (Throwable e) {
                                return "missing";
                            }
                        });
                    }
                    usesHeld = ctx.computeOnClient(mc -> PacketTrace.sent("use_item"));
                    movedHeld = Math.max(movedHeld, ctx.computeOnClient(mc -> mc.player.position().distanceTo(from)));
                }
                setGate(ctx, gate, false);
                int gateAirTick = -1;
                int firstUseTick = -1;
                for (int t = 1; t <= 600; t++) {
                    ctx.waitTick();
                    if (gateAirTick < 0 && ctx.computeOnClient(mc -> allAre(mc, gate, false))) {
                        gateAirTick = t;
                    }
                    if (firstUseTick < 0 && ctx.computeOnClient(mc -> PacketTrace.sent("use_item")) > usesHeld) {
                        firstUseTick = t;
                    }
                    boolean busy = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isBusy"));
                    if (firstUseTick > 0 && !busy) {
                        break;
                    }
                }
                ctx.waitTicks(5);
                ctx.runOnClient(mc -> PacketTrace.stop());
                int here = ctx.computeOnClient(mc -> (Integer) ModUnderTest.call(ModUnderTest.staticCall(LAYOUT, "capture"),
                        "currentRoom", new Class<?>[]{}, new Object[]{}));
                double moved = ctx.computeOnClient(mc -> mc.player.position().distanceTo(from));
                int lag = firstUseTick < 0 || gateAirTick < 0 ? -99 : firstUseTick - gateAirTick;
                pass = Boolean.TRUE.equals(ok) && waitingSeen && "true".equals(shutSeen) && usesHeld == 0 && movedHeld < 0.5
                        && gateAirTick > 0 && firstUseTick > 0 && lag >= 0 && lag <= 1 && here == target[1] && moved > 8;
                verdict = "press " + (Boolean.TRUE.equals(ok) ? "taken" : "REFUSED") + "; " + BARRIER_TICKS
                        + " barrier ticks after the start: waiting on the gate " + waitingSeen + ", gate shut for Auto Clear "
                        + shutSeen + ", " + usesHeld + " use_item sent, moved " + String.format("%.2f", movedHeld)
                        + "; barriers gone (client) at tick " + gateAirTick + ", first use_item at tick " + firstUseTick
                        + " (" + lag + " tick(s) after); ended in room " + here + " (pressed " + target[1] + "), moved "
                        + String.format("%.1f", moved) + " blocks";
            }
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            verdict = "scenario threw: " + t;
            pass = false;
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
        System.out.println("[" + NAME + "] " + (pass ? "PASS" : "FAIL") + " - " + verdict);
        Report.caseFinished(NAME, pass ? "PASS" : "FAIL", "n/a (singleplayer)", verdict, List.of(), List.of());
        SuiteVerdict.finished(NAME);
        if (!pass) {
            throw new AssertionError(verdict);
        }
    }

    /** Server side: the gate's blocks become barriers (Hypixel's start) or air (15 ticks later). */
    private static void setGate(ClientGameTestContext ctx, List<BlockPos> gate, boolean barrier) {
        AtomicInteger done = new AtomicInteger(-1);
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                var level = server.overworld();
                for (BlockPos p : gate) {
                    level.setBlockAndUpdate(p, (barrier ? Blocks.BARRIER : Blocks.AIR).defaultBlockState());
                }
                done.set(gate.size());
            });
        });
        ctx.waitFor(mc -> done.get() >= 0, 200);
    }

    private static boolean allAre(Minecraft mc, List<BlockPos> gate, boolean barrier) {
        for (BlockPos p : gate) {
            boolean b = mc.level.getBlockState(p).is(Blocks.BARRIER);
            if (barrier ? !b : !mc.level.getBlockState(p).isAir()) {
                return false;
            }
        }
        return true;
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}

package dev.testkit.gametest;

import dev.testkit.harness.PacketTrace;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * autopilot-prerun-1126 (WORKQUEUE autopilot-first-trip-prerun; his 11-25 16:55:22 F7 run: Mort's line, the Autopilot's
 * first trip pressed that tick, then 448 ms queued behind the room order search and 707 ms searching, so the first warp
 * went about 7 ticks after the gate was air). On the start's own tick the map is in hand while the gate's bricks still
 * stand; the first trip was planned in that shut world, found nothing and was searched again (origin: 11 ticks after the
 * gate was air). Now those bricks are the gate: the trip is planned through it at the start, held while the barriers
 * stand, and the warp goes as the gate opens. (Planning it before the start was tried and dropped: the Autopilot's first
 * goal is Auto Clear's stand, which the trip cache does not plan, and the start's own plan takes milliseconds.)
 *
 * <p>The sim drops its gate at GO; this case plays Hypixel's part as 1126-sim-ac-gatebarrier does: from the server tick
 * the run starts the gate's blocks are BARRIERS for 15 ticks, then air. Seeded floor (SimGreenStart1122Tests'), Warp to
 * Door Before Start OFF (he spawns on the ledge, the slow trip of his log), AOTV and Hyperion.
 * <ul>
 *   <li>1126-sim-ac-firsttrip-prerun: Blood Rush None (his setting: the Autopilot's planner decides the first trip).</li>
 *   <li>1126-sim-ac-firsttrip-prerun-door: Blood Rush Door (Auto Clear's Door Only run decides it).</li>
 * </ul>
 * Pass: the barrier window was clean (no air glimpsed early), no use_item while the barriers stand, the first use_item
 * goes the sampled tick the client sees the gate air or the next (the harness floor: a trip held on the gate measures 1
 * in 1126-sim-ac-gatebarrier), and the feature acted: the first trip was planned and held on the gate
 * (ClearExecutor.waitingForGate) before the gate was air. On a legit jar each row passes by checking Dungeons Autopilot
 * refuses to switch on.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimFirstTripPrerun1126Tests implements FabricClientGameTest {

    private static final String NONE_CASE = "1126-sim-ac-firsttrip-prerun";
    private static final String DOOR_CASE = "1126-sim-ac-firsttrip-prerun-door";
    private static final String SIM_RUN = "com.killer560.hub.roomsim.SimRun";
    private static final String EXECUTOR = "com.killer560.hub.livemap.autoclear.ClearExecutor";
    private static final String PILOT = "com.killer560.hub.autosecret.DungeonsAutopilot";
    private static final String AS_CONFIG = "com.killer560.hub.autosecret.AutoSecretConfig";
    private static final String AUTO_CLEAR = "com.killer560.hub.autoclear.AutoClearFeature";
    private static final String AUTO_CLEAR_CONFIG = "com.killer560.hub.autoclear.AutoClearConfig";
    private static final String AS = "com.killer560.hub.autosecret.AutoSecretFeature";
    private static final int BARRIER_TICKS = 15;

    /** Server-side gate player: 0 off, 1 waiting for the run's start, 2 barriers standing, 3 air. */
    private static volatile int hookPhase;
    private static volatile int hookTicks;
    private static volatile List<BlockPos> hookGate = List.of();
    private static boolean hookRegistered;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        boolean none = !Scenario.skip(NONE_CASE);
        boolean door = !Scenario.skip(DOOR_CASE);
        // "1126-sim-ac-firsttrip-prerun" selects both names (substring match); "-door" alone selects only the door row.
        if (!none && !door) {
            return;
        }
        ModUnderTest.require("killer560smod");
        if (!dev.testkit.gametest.mod.Mod.isCheat()) {
            for (String n : new String[]{NONE_CASE, DOOR_CASE}) {
                if (!Scenario.skip(n)) {
                    SimGreenStart1122Tests.legitRow(ctx, n);
                }
            }
            return;
        }
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            for (String n : new String[]{NONE_CASE, DOOR_CASE}) {
                if (!Scenario.skip(n)) {
                    Scenario.skipped(n, "needs real room captures and room database");
                }
            }
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "isReady"), 2400);
        registerHook();
        ctx.runOnClient(mc -> {
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                    "setAutoPuzzlesMasterEnabled", false);
            Object cfg = ModUnderTest.config(AUTO_CLEAR_CONFIG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setHyperionHops", true);
            // His own setting (on in his 26.3): the trip cache that plans before the start runs only with it.
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.livemap.LiveMapConfig"), "setInteractiveMapEnabled", true);
            Object w = ModUnderTest.enumValue(AUTO_CLEAR_CONFIG + "$Weapon", "HYPERION");
            ModUnderTest.call(cfg, "setWeapon", new Class<?>[]{w.getClass()}, new Object[]{w});
        });
        SimGreenStart1122Tests.setWarp(ctx, false);
        List<String> failed = new ArrayList<>();
        if (none) {
            run(ctx, NONE_CASE, "NONE", false, failed);
        }
        if (door) {
            run(ctx, DOOR_CASE, "DOOR", true, failed);
        }
        ctx.runOnClient(mc -> {
            Object as = ModUnderTest.config(AS_CONFIG);
            Object n = ModUnderTest.enumValue(AS_CONFIG + "$BloodRush", "NONE");
            ModUnderTest.call(as, "setBloodRush", new Class<?>[]{n.getClass()}, new Object[]{n});
            ModUnderTest.set(as, "setDisableAfterBloodRush", false);
            ModUnderTest.set(ModUnderTest.config(AUTO_CLEAR_CONFIG), "setEnabled", false);
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.livemap.LiveMapConfig"), "setInteractiveMapEnabled", false);
        });
        if (!failed.isEmpty()) {
            throw new AssertionError(String.join("; ", failed));
        }
    }

    private static void registerHook() {
        if (hookRegistered) {
            return;
        }
        hookRegistered = true;
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            int ph = hookPhase;
            if (ph == 1 && (Boolean) ModUnderTest.staticCall(SIM_RUN, "hasStarted")) {
                hookPhase = ph = 2;
                hookTicks = 0;
            }
            if (ph != 2) {
                return;
            }
            var level = server.overworld();
            boolean air = ++hookTicks > BARRIER_TICKS;
            for (BlockPos p : hookGate) {
                var want = (air ? Blocks.AIR : Blocks.BARRIER).defaultBlockState();
                if (!level.getBlockState(p).equals(want)) {
                    level.setBlockAndUpdate(p, want);
                }
            }
            if (air) {
                hookPhase = 3;
            }
        });
    }

    /** {all barriers, all air} of the gate as the client sees it. */
    private static boolean[] gateSeen(Minecraft mc, List<BlockPos> gate) {
        boolean allBarrier = true;
        boolean allAir = true;
        for (BlockPos p : gate) {
            var s = mc.level.getBlockState(p);
            allBarrier &= s.is(Blocks.BARRIER);
            allAir &= s.isAir();
        }
        return new boolean[]{allBarrier, allAir};
    }

    private static void run(ClientGameTestContext ctx, String name, String bloodRush, boolean disableAfter,
                            List<String> failed) {
        List<String> f = new ArrayList<>();
        String detail = "";
        try {
            ctx.runOnClient(mc -> {
                Object as = ModUnderTest.config(AS_CONFIG);
                Object b = ModUnderTest.enumValue(AS_CONFIG + "$BloodRush", bloodRush);
                ModUnderTest.call(as, "setBloodRush", new Class<?>[]{b.getClass()}, new Object[]{b});
                ModUnderTest.set(as, "setDisableAfterBloodRush", disableAfter);
            });
            BlockPos doorPos = SimGreenStart1122Tests.stage(ctx, f, true);
            if (doorPos != null) {
                detail = measure(ctx, f);
            }
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            f.add("threw " + t);
        } finally {
            hookPhase = 0;
            ctx.runOnClient(mc -> {
                PacketTrace.tap = null;
                ModUnderTest.staticCall(PILOT, "setOn", new Class<?>[]{boolean.class}, new Object[]{false});
                ModUnderTest.staticCall(AUTO_CLEAR, "cancel");
                if ((Boolean) ModUnderTest.staticCall(AS, "isRunning")) {
                    ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class},
                            new Object[]{"test over", true});
                }
            });
            SimGreenStart1122Tests.leave(ctx);
        }
        f.removeIf(java.util.Objects::isNull);
        boolean pass = f.isEmpty();
        String text = pass ? detail : String.join("; ", f) + " | " + detail;
        System.out.println("[" + name + "] " + (pass ? "PASS" : "FAIL") + " - " + text);
        Report.caseStarted(name);
        Report.caseFinished(name, pass ? "PASS" : "FAIL", "n/a (singleplayer)", text, List.of(), List.of());
        SuiteVerdict.finished(name);
        if (!pass) {
            failed.add(name + ": " + text);
        }
    }

    private static String measure(ClientGameTestContext ctx, List<String> f) {
        List<BlockPos> gate = ctx.computeOnClient(SimPrerunTripTests::gateBlocks);
        if (gate.isEmpty()) {
            f.add("premise: no gate blocks found round the entrance door");
            return "";
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(PILOT, "setOn", new Class<?>[]{boolean.class}, new Object[]{true}));
        // Armed in the green room: what it plans before the start (once; the countdown is held).
        ctx.waitTicks(100);
        boolean stillShut = ctx.computeOnClient(mc -> !(Boolean) ModUnderTest.staticCall(SIM_RUN, "isRunning"));
        int[] uses = {0};
        ctx.runOnClient(mc -> PacketTrace.tap = p -> {
            if (p instanceof net.minecraft.network.protocol.game.ServerboundUseItemPacket) {
                uses[0]++;
            }
        });
        int usesBeforeGo = 0;
        hookGate = List.copyOf(gate);
        hookPhase = 1;
        ctx.runOnClient(mc -> SimGreenStart1122Tests.setTicksLeft(1));
        int goTick = -1;
        int barrierTicks = 0;
        boolean earlyAir = false;
        int airTick = -1;
        int firstUse = -1;
        int usesInBarrier = 0;
        boolean heldBeforeAir = false;
        for (int t = 1; t <= 600 && (firstUse < 0 || airTick < 0); t++) {
            ctx.waitTick();
            final int tt = t;
            boolean started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_RUN, "isRunning"));
            if (goTick < 0 && started) {
                goTick = tt;
                usesBeforeGo = ctx.computeOnClient(mc -> uses[0]);
            }
            boolean[] g = ctx.computeOnClient(mc -> gateSeen(mc, gate));
            if (g[0]) {
                barrierTicks++;
            }
            if (goTick > 0 && airTick < 0 && g[1]) {
                if (barrierTicks == 0) {
                    earlyAir = true;
                }
                airTick = tt;
            }
            if (goTick > 0 && airTick < 0 && !heldBeforeAir) {
                heldBeforeAir = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "waitingForGate"));
            }
            int u = ctx.computeOnClient(mc -> uses[0]);
            if (goTick > 0 && firstUse < 0 && u > usesBeforeGo) {
                firstUse = tt;
                if (airTick < 0) {
                    usesInBarrier = u - usesBeforeGo;
                }
            }
        }
        int lag = firstUse < 0 || airTick < 0 ? -99 : firstUse - airTick;
        String d = String.format(Locale.US, "armed 100 ticks, run held %s; GO at tick %d, barriers seen"
                        + " %d tick(s)%s, gate air at tick %d, first use_item at tick %d (%d tick(s) after the gate was air, %d"
                        + " after GO), %d use_item(s) while the barriers stood; trip held on the gate before air %s", stillShut,
                goTick, barrierTicks, earlyAir ? " (air glimpsed before them)" : "", airTick, firstUse, lag,
                firstUse < 0 || goTick < 0 ? -99 : firstUse - goTick, usesInBarrier, heldBeforeAir);
        f.add(stillShut ? null : "premise: the run started before the case let it");
        f.add(goTick > 0 ? null : "premise: the run never started");
        f.add(!earlyAir && barrierTicks >= BARRIER_TICKS - 3 ? null : "premise: the barrier window was not clean");
        f.add(usesInBarrier == 0 ? null : usesInBarrier + " use_item(s) while the barriers stood");
        f.add(lag >= 0 && lag <= 1 ? null : "first use_item " + lag + " tick(s) after the gate was air (want 0-1)");
        f.add(heldBeforeAir ? null : "the first trip was not planned and held on the gate before it opened");
        return d;
    }
}

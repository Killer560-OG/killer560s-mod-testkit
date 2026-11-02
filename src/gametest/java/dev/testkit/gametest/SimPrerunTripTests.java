package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.PacketTrace;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 1101-prerun-trip (mod doc-map-1101): killer560's to-do Doc, 2026-11-01: "if i click on a room while in green room in
 * interactive map before the dungeon opens it will obviously always fail. same for if i activate the auto pilot. make
 * those plan their route then but wait till the door is open and can etherwarp through it to actually go."
 *
 * <p>A generated F7 sim floor held before its run (the countdown re-armed, the Entrance gate's infested chiseled stone
 * bricks still in the doorway). The Interactive Map's press ({@code AutoClearUtils.pathToRoom}, the call Auto Secret and
 * Autopilot make too) to a room two doors from the Entrance. Pass:
 * <ul>
 *   <li>while the run has not started (60 ticks): the trip is waiting on the gate, no etherwarp (use_item) is sent, and
 *       he has not moved;</li>
 *   <li>the countdown is let run out: the first use_item goes on the tick the client sees the gate open, or the next;</li>
 *   <li>the trip ends in the room pressed.</li>
 * </ul>
 * Singleplayer: there is no GrimAC here. The hold only delays the trip's first warp; the packets after it are the same
 * trip's as at any other time.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimPrerunTripTests implements FabricClientGameTest {

    private static final String NAME = "1101-prerun-trip";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_RUN = "com.killer560.hub.roomsim.SimRun";
    private static final String SIM_BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String CLEAR_UTILS = "com.killer560.hub.livemap.autoclear.AutoClearUtils";
    private static final String EXECUTOR = "com.killer560.hub.livemap.autoclear.ClearExecutor";
    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";

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
            // Before its countdown runs out: held there for as long as the case needs.
            ctx.runOnClient(mc -> {
                ModUnderTest.staticCall(SIM_RUN, "reset");
                rearm(mc);
            });
            for (int i = 0; i < 2400 && !ctx.computeOnClient(mc -> McCompat.screen(mc) == null && mc.player != null
                    && mc.player.onGround() && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir()); i++) {
                ctx.waitTicks(1);
                if (i % 20 == 0) {
                    ctx.runOnClient(SimPrerunTripTests::rearm);
                }
            }
            giveAotv(ctx);
            for (int i = 0; i < 100; i++) {    // the floor graph warms; the run stays held
                ctx.waitTicks(1);
                if (i % 20 == 0) {
                    ctx.runOnClient(SimPrerunTripTests::rearm);
                }
            }
            boolean started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_RUN, "hasStarted"));
            List<BlockPos> gate = ctx.computeOnClient(SimPrerunTripTests::gateBlocks);
            int[] target = ctx.computeOnClient(SimPrerunTripTests::twoDoorsOut);
            println("before the run: started " + started + ", " + gate.size() + " gate block(s), target room "
                    + (target == null ? "none" : target[1] + " tile " + target[0]));
            if (started || gate.isEmpty() || target == null) {
                verdict = "premise: run already started " + started + ", gate blocks " + gate.size() + ", target " + (target != null);
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
                int usesHeld = 0;
                double movedHeld = 0;
                for (int t = 1; t <= 60; t++) {
                    ctx.waitTick();
                    if (t % 20 == 0) {
                        ctx.runOnClient(SimPrerunTripTests::rearm);
                    }
                    waitingSeen |= ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "waitingForGate"));
                    usesHeld = ctx.computeOnClient(mc -> PacketTrace.sent("use_item"));
                    movedHeld = Math.max(movedHeld, ctx.computeOnClient(mc -> mc.player.position().distanceTo(from)));
                }
                // Let the countdown run out.
                int gateAirTick = -1;
                int firstUseTick = -1;
                boolean busySeen = false;
                for (int t = 1; t <= 600; t++) {
                    ctx.waitTick();
                    boolean air = ctx.computeOnClient(mc -> {
                        for (BlockPos p : gate) {
                            if (!mc.level.getBlockState(p).isAir()) {
                                return false;
                            }
                        }
                        return true;
                    });
                    if (air && gateAirTick < 0) {
                        gateAirTick = t;
                    }
                    if (firstUseTick < 0 && ctx.computeOnClient(mc -> PacketTrace.sent("use_item")) > usesHeld) {
                        firstUseTick = t;
                    }
                    boolean busy = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isBusy"));
                    busySeen |= busy;
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
                pass = Boolean.TRUE.equals(ok) && waitingSeen && usesHeld == 0 && movedHeld < 0.5 && gateAirTick > 0
                        && firstUseTick > 0 && lag >= 0 && lag <= 1 && here == target[1] && moved > 8;
                verdict = "press " + (Boolean.TRUE.equals(ok) ? "taken" : "REFUSED") + "; held 60 ticks before the run: waiting"
                        + " on the gate " + waitingSeen + ", " + usesHeld + " use_item sent, moved " + String.format("%.2f", movedHeld)
                        + "; gate open (client) at tick " + gateAirTick + ", first use_item at tick " + firstUseTick
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

    private static void rearm(Minecraft mc) {
        if (!(Boolean) ModUnderTest.staticCall(SIM_RUN, "hasStarted")) {
            ModUnderTest.staticCall(SIM_RUN, "begin", new Class<?>[]{Minecraft.class, BlockPos.class},
                    new Object[]{mc, ModUnderTest.staticCall(SIM_BUILDER, "entranceDoor")});
        }
    }

    /** The infested chiseled stone bricks round the layout's entrance door cell(s). */
    private static List<BlockPos> gateBlocks(Minecraft mc) {
        List<BlockPos> out = new ArrayList<>();
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        int entranceType = (Integer) dev.testkit.gametest.mod.Mod.field(LAYOUT, "DOOR_ENTRANCE");
        for (int idx = 0; idx < 121; idx++) {
            if ((Integer) ModUnderTest.call(layout, "doorType", new Class<?>[]{int.class}, new Object[]{idx}) != entranceType) {
                continue;
            }
            BlockPos c = (BlockPos) ModUnderTest.staticCall(LAYOUT, "doorBlock", new Class<?>[]{int.class}, new Object[]{idx});
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    for (int dy = -2; dy <= 5; dy++) {
                        BlockPos p = c.offset(dx, dy, dz);
                        if (mc.level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.INFESTED_CHISELED_STONE_BRICKS)) {
                            out.add(p);
                        }
                    }
                }
            }
        }
        return out;
    }

    /** {tile, room} of a room two doors out from the room he stands in (or one, if the floor has none), not Blood. */
    private static int[] twoDoorsOut(Minecraft mc) {
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        int here = (Integer) ModUnderTest.call(layout, "currentRoom", new Class<?>[]{}, new Object[]{});
        if (here < 0) {
            return null;
        }
        List<Integer> one = neighbours(layout, here);
        for (int a : one) {
            for (int b : neighbours(layout, a)) {
                if (b != here && !one.contains(b) && !isBlood(layout, b)) {
                    int[] tiles = (int[]) ModUnderTest.call(layout, "tiles", new Class<?>[]{int.class}, new Object[]{b});
                    return new int[]{tiles[0], b};
                }
            }
        }
        for (int a : one) {
            if (!isBlood(layout, a)) {
                int[] tiles = (int[]) ModUnderTest.call(layout, "tiles", new Class<?>[]{int.class}, new Object[]{a});
                return new int[]{tiles[0], a};
            }
        }
        return null;
    }

    private static boolean isBlood(Object layout, int room) {
        return "Blood".equalsIgnoreCase((String) ModUnderTest.call(layout, "name", new Class<?>[]{int.class},
                new Object[]{room}));
    }

    private static List<Integer> neighbours(Object layout, int room) {
        List<Integer> out = new ArrayList<>();
        for (int idx = 0; idx < 121; idx++) {
            int gx = idx % 11;
            int gz = idx / 11;
            if ((gx % 2 == 1) == (gz % 2 == 1)
                    || !(Boolean) ModUnderTest.call(layout, "isDoor", new Class<?>[]{int.class}, new Object[]{idx})) {
                continue;
            }
            int a = (Integer) ModUnderTest.call(layout, "roomOfCell", new Class<?>[]{int.class},
                    new Object[]{gx % 2 == 1 ? gz * 11 + gx - 1 : (gz - 1) * 11 + gx});
            int b = (Integer) ModUnderTest.call(layout, "roomOfCell", new Class<?>[]{int.class},
                    new Object[]{gx % 2 == 1 ? gz * 11 + gx + 1 : (gz + 1) * 11 + gx});
            if (a == room && b >= 0 && b != room && !out.contains(b)) {
                out.add(b);
            } else if (b == room && a >= 0 && a != room && !out.contains(a)) {
                out.add(a);
            }
        }
        return out;
    }

    private static void giveAotv(ClientGameTestContext ctx) {
        AtomicReference<Boolean> given = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp == null) {
                    given.set(false);
                    return;
                }
                var inv = sp.getInventory();
                for (int i = 0; i < 36; i++) {
                    inv.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
                }
                inv.setItem(0, (net.minecraft.world.item.ItemStack) ModUnderTest.staticCall(
                        "com.killer560.hub.roomsim.SimItems", "build", new Class<?>[]{String.class},
                        new Object[]{"ASPECT_OF_THE_VOID"}));
                given.set(true);
            });
        });
        ctx.waitFor(mc -> given.get() != null, 200);
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        ctx.waitTicks(10);
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}

package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.PacketTrace;
import dev.testkit.harness.WarpDrop;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 1121-sim-acgaps2-warp-lost (mod acgaps2-1121). His acslow-1121 log, door 81: Auto Clear's one-warp path sent use seq 15,
 * Hypixel never answered it, and the path stood still until its 20-tick stall deadline ran out before it planned again
 * and warped (seq 16). A route waiting on something retries on a real signal and never idles: a warp with no answer is
 * judged by Auto Routes' refused-warp wait (twice the slowest recent landing + 2, at least the eaten-use wait).
 *
 * <p>A generated sim F7, an Aspect of the Void, the Interactive Map at 1 tick between etherwarps. A map press to a far
 * room is flown once with every sneaking use counted (H hops), he is put back on the same spot, and the same press is
 * flown again while the sim server drops the H-th (last) use unanswered ({@link WarpDrop}). Measured on the wire: the
 * client ticks from the dropped use to the next use (the retry). It must be at most {@code testkit.warpLostMax} (12),
 * the drop must have happened, a retry must have gone out, and he must end where the clean flight ended.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimAcWarpLost1121Tests implements FabricClientGameTest {

    private static final String NAME = "1121-sim-acgaps2-warp-lost";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    private static final String CLEAR_UTILS = "com.killer560.hub.livemap.autoclear.AutoClearUtils";
    private static final String EXECUTOR = "com.killer560.hub.livemap.autoclear.ClearExecutor";
    private static final String AUTO_CONFIG = "com.killer560.hub.autopuzzles.AutoPuzzlesConfig";
    private static final int MAX = Integer.getInteger("testkit.warpLostMax", 12);

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        if (!dev.testkit.gametest.mod.Mod.isCheat()) {
            Scenario.skipped(NAME, "legit jar: no Interactive Map");
            return;
        }
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
        int oldGap = ctx.computeOnClient(mc -> (Integer) ModUnderTest.call(ModUnderTest.config(LIVE_MAP_CONFIG),
                "getEtherwarpTickGap", new Class<?>[]{}, new Object[]{}));
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        List<String> failures = new ArrayList<>();
        String detail = "";
        try {
            ctx.runOnClient(mc -> {
                ModUnderTest.set(ModUnderTest.config(AUTO_CONFIG), "setAutoPuzzlesMasterEnabled", false);
                Object map = ModUnderTest.config(LIVE_MAP_CONFIG);
                ModUnderTest.set(map, "setEnabled", true);
                ModUnderTest.set(map, "setInteractiveMapEnabled", true);
                ModUnderTest.set(map, "setEtherwarpTickGap", 1);
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
            detail = measure(ctx, failures);
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            WarpDrop.dropAt = -1;
            ctx.runOnClient(mc -> {
                ModUnderTest.set(ModUnderTest.config(LIVE_MAP_CONFIG), "setEtherwarpTickGap", oldGap);
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
        System.out.println("[" + NAME + "] " + (failures.isEmpty() ? "PASS" : "FAIL") + " - "
                + (failures.isEmpty() ? detail : String.join("; ", failures)));
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
    }

    /** One clean flight to count hops, then the same flight from the same spot with the last use dropped. */
    private static String measure(ClientGameTestContext ctx, List<String> failures) {
        List<int[]> rooms = ctx.computeOnClient(SimEtherTicksTests::farthestRooms);
        for (int attempt = 0; attempt < Math.min(5, rooms.size()); attempt++) {
            int[] t = rooms.get(attempt);
            awaitGround(ctx);
            Vec3 start = ctx.computeOnClient(mc -> mc.player.position());
            float yaw = ctx.computeOnClient(mc -> mc.player.getYRot());
            WarpDrop.arm(0);
            Flight clean = fly(ctx, t);
            int hops = WarpDrop.seen;
            println(String.format(java.util.Locale.US, "clean press to room %d: %s, %d sneaking use(s) on the server, wire uses %s,"
                    + " moved %.1f", t[1], clean.taken ? "taken" : "REFUSED", hops, clean.wire, start.distanceTo(clean.end)));
            if (!clean.taken || hops < 1 || start.distanceTo(clean.end) < 10) {
                continue;
            }
            // back to the same spot and look, then fly it again with the last use unanswered
            String tp = String.format(java.util.Locale.US, "tp @a %.3f %.3f %.3f %.2f 0", start.x, start.y, start.z, yaw);
            ctx.runOnClient(mc -> {
                var server = mc.getSingleplayerServer();
                server.execute(() -> server.getCommands().performPrefixedCommand(
                        server.createCommandSourceStack().withSuppressedOutput(), tp));
            });
            ctx.waitFor(mc -> mc.player.position().distanceTo(start) < 0.5, 200);
            awaitGround(ctx);
            ctx.waitTicks(40);
            WarpDrop.arm(hops);
            Flight lost = fly(ctx, t);
            int dropped = WarpDrop.dropped;
            WarpDrop.dropAt = -1;
            double offEnd = lost.end.distanceTo(clean.end);
            int retry = lost.wire.size() > hops ? lost.wire.get(hops) - lost.wire.get(hops - 1) : -1;
            String facts = String.format(java.util.Locale.US, "%d hop(s); dropped use %d (%d dropped), wire uses %s, retry %d"
                    + " tick(s) after the dropped use (max %d), ended %.1f blocks from the clean flight's end", hops, hops,
                    dropped, lost.wire, retry, MAX, offEnd);
            println(facts);
            if (dropped != 1) {
                failures.add("the sim dropped " + dropped + " use(s), not 1 (the second flight made " + WarpDrop.seen
                        + " sneaking use(s)): " + facts);
            } else if (retry < 0) {
                failures.add("no use went out after the dropped warp: " + facts);
            } else if (retry > MAX) {
                failures.add("the retry of the unanswered warp waited " + retry + " tick(s), over " + MAX + ": " + facts);
            }
            if (offEnd > 3) {
                failures.add("the flight with the lost warp did not get where the clean one did: " + facts);
            }
            return facts;
        }
        failures.add("no press made 1+ hops over 10+ blocks to measure");
        return "";
    }

    private record Flight(boolean taken, List<Integer> wire, Vec3 end) {
    }

    private static Flight fly(ClientGameTestContext ctx, int[] t) {
        ctx.runOnClient(mc -> PacketTrace.start());
        Boolean ok = ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            return (Boolean) ModUnderTest.staticCall(CLEAR_UTILS, "pathToRoom",
                    new Class<?>[]{layout.getClass(), int.class, int.class, int.class},
                    new Object[]{layout, t[1], t[0], 0});
        });
        boolean busySeen = false;
        for (int i = 0; i < 900 && Boolean.TRUE.equals(ok); i++) {
            ctx.waitTicks(1);
            boolean busy = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isBusy"));
            busySeen |= busy;
            if (busySeen && !busy) {
                break;
            }
        }
        ctx.waitTicks(10);
        List<Integer> wire = ctx.computeOnClient(mc -> {
            PacketTrace.stop();
            List<Integer> out = new ArrayList<>();
            for (int i = 0; i < PacketTrace.tickCount(); i++) {
                if (PacketTrace.sentOn(i).contains("use_item")) {
                    out.add(i);
                }
            }
            return out;
        });
        return new Flight(Boolean.TRUE.equals(ok), wire, ctx.computeOnClient(mc -> mc.player.position()));
    }

    private static void awaitGround(ClientGameTestContext ctx) {
        for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> mc.player.onGround()); i++) {
            ctx.waitTicks(1);
        }
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}

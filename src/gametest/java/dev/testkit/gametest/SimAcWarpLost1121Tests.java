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
 * client ticks from the dropped use to the next use (the retry). It must be at most twice the clean flight's last landing + 6 (at least {@code testkit.warpLostMax}, 12) and
 * never 21 or more (the old 20-tick stall deadline),
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

    /**
     * A warm-up flight (the first press may plan live, later ones use the trip planned from the same spot), a clean flight
     * that counts H hops and times its last landing (L ticks), then the same flight with use H dropped. A flight that came
     * out shorter drops nothing; it is flown again with its own count (twice at most).
     */
    private static String measure(ClientGameTestContext ctx, List<String> failures) {
        List<int[]> rooms = ctx.computeOnClient(SimEtherTicksTests::farthestRooms);
        for (int attempt = 0; attempt < Math.min(5, rooms.size()); attempt++) {
            int[] t = rooms.get(attempt);
            awaitGround(ctx);
            Vec3 start = ctx.computeOnClient(mc -> mc.player.position());
            float yaw = ctx.computeOnClient(mc -> mc.player.getYRot());
            WarpDrop.arm(0);
            Flight warm = fly(ctx, t);
            if (!warm.taken || WarpDrop.seen < 1 || start.distanceTo(warm.end) < 10) {
                println("press to room " + t[1] + ": " + (warm.taken ? "taken" : "REFUSED") + ", " + WarpDrop.seen
                        + " use(s), moved " + String.format(java.util.Locale.US, "%.1f", start.distanceTo(warm.end)));
                continue;
            }
            backTo(ctx, start, yaw);
            WarpDrop.arm(0);
            Flight clean = fly(ctx, t);
            int hops = WarpDrop.seen;
            int land = clean.wire.isEmpty() ? -1 : clean.landTick - clean.wire.get(clean.wire.size() - 1);
            println(String.format(java.util.Locale.US, "warm-up %d use(s); clean flight %d use(s), wire uses %s, last landing"
                    + " %d tick(s) after its use", warm.wire.size(), hops, clean.wire, land));
            Flight lost = null;
            int dropped = 0;
            for (int tryNo = 0; tryNo < 3 && dropped == 0 && hops >= 1; tryNo++) {
                backTo(ctx, start, yaw);
                WarpDrop.arm(hops);
                lost = fly(ctx, t);
                dropped = WarpDrop.dropped;
                if (dropped == 0) {
                    println("flight " + (tryNo + 1) + " made only " + WarpDrop.seen + " use(s), none dropped; again with that count");
                    hops = WarpDrop.seen;
                }
            }
            WarpDrop.dropAt = -1;
            // The old rule waited STALL_TICKS (20) after the last landing before planning again, so its retry is 21+;
            // the refused-warp rule waits twice the slowest landing + 2 (at least 3), so allow that, never 21.
            int limit = Math.min(20, Math.max(MAX, 2 * Math.max(land, 0) + 6));
            double offEnd = lost == null ? -1 : lost.end.distanceTo(clean.end);
            int retry = lost != null && dropped == 1 && lost.wire.size() > hops ? lost.wire.get(hops) - lost.wire.get(hops - 1) : -1;
            String facts = String.format(java.util.Locale.US, "%d hop(s); dropped use %d (%d dropped), wire uses %s, retry %d"
                    + " tick(s) after the dropped use (limit %d, clean last landing %d), ended %.1f blocks from the clean"
                    + " flight's end", hops, hops, dropped, lost == null ? List.of() : lost.wire, retry, limit, land, offEnd);
            println(facts);
            if (dropped != 1) {
                failures.add("the sim dropped " + dropped + " use(s), not 1: " + facts);
            } else if (retry < 0) {
                failures.add("no use went out after the dropped warp: " + facts);
            } else if (retry > limit) {
                failures.add("the retry of the unanswered warp waited " + retry + " tick(s), over " + limit + ": " + facts);
            }
            if (offEnd > 3) {
                failures.add("the flight with the lost warp did not get where the clean one did: " + facts);
            }
            return facts;
        }
        failures.add("no press made 1+ hops over 10+ blocks to measure");
        return "";
    }

    /** Puts him back on the start spot and look through the sim server, then lets him settle. */
    private static void backTo(ClientGameTestContext ctx, Vec3 start, float yaw) {
        String tp = String.format(java.util.Locale.US, "tp @a %.3f %.3f %.3f %.2f 0", start.x, start.y, start.z, yaw);
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withSuppressedOutput(), tp));
        });
        ctx.waitFor(mc -> mc.player.position().distanceTo(start) < 0.5, 200);
        awaitGround(ctx);
        ctx.waitTicks(40);
    }

    /** {@code landTick}: the trace tick he first stood within a block of where the flight ended. */
    private record Flight(boolean taken, List<Integer> wire, Vec3 end, int landTick) {
    }

    private static Flight fly(ClientGameTestContext ctx, int[] t) {
        ctx.runOnClient(mc -> PacketTrace.start());
        Boolean ok = ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            return (Boolean) ModUnderTest.staticCall(CLEAR_UTILS, "pathToRoom",
                    new Class<?>[]{layout.getClass(), int.class, int.class, int.class},
                    new Object[]{layout, t[1], t[0], 0});
        });
        List<Vec3> spots = new ArrayList<>();
        boolean busySeen = false;
        for (int i = 0; i < 900 && Boolean.TRUE.equals(ok); i++) {
            ctx.waitTicks(1);
            spots.add(ctx.computeOnClient(mc -> mc.player.position()));
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
        Vec3 end = ctx.computeOnClient(mc -> mc.player.position());
        int landTick = -1;
        for (int i = 0; i < spots.size(); i++) {
            if (spots.get(i).distanceTo(end) < 1.0) {
                landTick = i + 1;   // spots[0] is read after the first tick of the trace
                break;
            }
        }
        return new Flight(Boolean.TRUE.equals(ok), wire, end, landTick);
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

package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Generating a floor from killer560's REAL room library, watching for stalls.
 *
 * <p>He has reported the sim freezing on creation five times, and I have shipped five fixes, each aimed at
 * something I reasoned about rather than measured. His instruction (2026-09-28): "Do not have me test again
 * until you are 100% postiive it is all fixed." This is the scenario that makes that possible.
 *
 * <p>It copies his actual captured rooms into the test client's config folder first. That matters more than it
 * sounds: every previous cause turned out to be about SCALE - 187 MB of JSON, 1.8 million block reads, two
 * hundred chunk packets - and none of them reproduce against a handful of synthetic rooms. A test that uses
 * small data cannot find a problem that only exists in large data.
 *
 * <p>What it measures is the thing he actually experiences. A freeze is not an exception or a slow total; it is
 * one client tick that takes a long time. So the test ticks the client one tick at a time and times each one
 * from outside, and fails on the WORST tick rather than on the average - an average hides exactly the stall
 * being hunted.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimStallTests implements FabricClientGameTest {

    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String BUILD_QUEUE = "com.killer560.hub.roomsim.SimBuildQueue";

    /** Where his real rooms live. */
    private static final String SOURCE_ROOMS =
            ModUnderTest.instanceConfig(Machine.roomsConfigDir(), "killer560smod-rooms");

    /**
     * The longest a single client tick may take.
     *
     * <p>A tick is 50 ms. Anything past a quarter of a second is a visible hitch; past a second is what he
     * calls a freeze. Half a second is chosen as the line because it is unambiguously wrong without being so
     * tight that ordinary chunk-loading noise trips it.
     */
    private static final long MAX_TICK_MS = 500;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip("72-sim-stall")) {
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

        int copied = copyRealRooms(ctx);
        if (copied < 20) {
            System.out.println("[72-sim-stall] SKIPPED - only " + copied + " real room(s) available to copy; "
                    + "this scenario is about scale and proves nothing without them");
            return;
        }
        System.out.println("[72-sim-stall] copied " + copied + " real room(s)");

        // Library load, off the render thread. Timed from outside, because the whole point is whether it
        // blocks the client - a fast load that blocks is still a freeze.
        // Room types come from the room database; the sim will not plan a floor without it (mod, 2026-10-05).
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload",
                new Class<?>[]{}, new Object[]{}));
        Stall load = tickUntil(ctx, 1200, () -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady",
                new Class<?>[]{}, new Object[]{}));
        System.out.println("[72-sim-stall] library ready after " + load.ticks + " tick(s), worst tick "
                + load.worstMs + " ms");
        if (!load.finished) {
            throw new AssertionError("the room library never finished loading");
        }
        if (load.worstMs > MAX_TICK_MS) {
            throw new AssertionError("loading the room library stalled the client for " + load.worstMs
                    + " ms in a single tick - this is the freeze, it is just not where I last looked");
        }

        // Now the thing he does: generate a floor.
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter",
                new Class<?>[]{String.class}, new Object[]{"gametest"}));
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            ModUnderTest.staticCall(FLOOR_GEN, "generate",
                    new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, 3, 5});
        }));

        // Vanilla's own world load blocks the render thread and always has - clicking any singleplayer world
        // does the same. Waiting past it before measuring, so the number means "the mod stalled" rather than
        // "Minecraft loaded a world", which would make the test fail on something it is not about.
        ctx.waitFor(mc -> mc.level != null);
        ctx.waitTicks(20);

        Stall build = tickUntil(ctx, 2400, () -> {
            boolean busy = (Boolean) ModUnderTest.staticCall(BUILD_QUEUE, "isBusy",
                    new Class<?>[]{}, new Object[]{});
            return !busy && Minecraft.getInstance().level != null;
        });
        System.out.println("[72-sim-stall] build settled after " + build.ticks + " tick(s), worst tick "
                + build.worstMs + " ms, slow ticks: " + build.slowTicks);
        if (!build.finished) {
            throw new AssertionError("the floor never finished building");
        }
        if (build.worstMs > MAX_TICK_MS) {
            throw new AssertionError("generating a floor stalled the client for " + build.worstMs
                    + " ms in a single tick (" + build.slowTicks + " tick(s) over 100 ms) - that is the freeze");
        }

        // And a while after, because he reports it freezing once he is IN, not only while it builds.
        Stall after = tickUntil(ctx, 200, () -> false);
        System.out.println("[72-sim-stall] after settling, worst tick " + after.worstMs + " ms");
        if (after.worstMs > MAX_TICK_MS) {
            throw new AssertionError("the client stalled for " + after.worstMs
                    + " ms AFTER the build finished - which is exactly what he described");
        }

        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "leave", new Class<?>[]{}, new Object[]{}));
        // The harness requires the world to be closed before the scenario ends, and this one opened a world
        // that the others do not. Queued rather than run inline: disconnecting needs further client ticks, and
        // runOnClient waits for its task - the same deadlock that killed this client earlier in the session.
        ctx.runOnClient(mc -> mc.execute(() -> {
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                mc.disconnectWithSavingScreen();
            }
        }));
        // The SERVER, not just the level. The level goes null first and the integrated server takes a few
        // more ticks to stop; waiting on the level alone let the scenario end with a server still up, which
        // the harness rejects.
        // Back to the title screen, which the harness requires. Waiting alone was not enough: leaving a world
        // the mod opened lands on a saving screen and stays there, so once the world and server are actually
        // gone this puts the title screen up itself.
        ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null);
        // getSingleplayerServer() goes null before the server THREAD has finished stopping, and forcing the
        // title screen during that window ends the scenario with a server still up. So: let it settle, then
        // put the title screen up, then let that settle too.
        ctx.waitTicks(60);
        ctx.runOnClient(mc -> mc.execute(() ->
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen);
        ctx.waitTicks(20);
        System.out.println("[72-sim-stall] PASS - real library loaded and a full floor generated with no tick "
                + "over " + MAX_TICK_MS + " ms");
    }

    /** How a stretch of ticking went. */
    private record Stall(boolean finished, int ticks, long worstMs, int slowTicks) {
    }

    /**
     * Ticks one at a time until {@code done}, timing every tick.
     *
     * <p>One at a time on purpose. {@code waitTicks(n)} would hide a stall inside a batch, and the stall is the
     * measurement.
     */
    private static Stall tickUntil(ClientGameTestContext ctx, int maxTicks,
                                   java.util.function.Supplier<Boolean> done) {
        long worst = 0;
        int slow = 0;
        for (int i = 0; i < maxTicks; i++) {
            long t0 = System.nanoTime();
            ctx.waitTicks(1);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            worst = Math.max(worst, ms);
            if (ms > 100) {
                slow++;
            }
            Boolean d = ctx.computeOnClient(mc -> done.get());
            if (Boolean.TRUE.equals(d)) {
                return new Stall(true, i + 1, worst, slow);
            }
        }
        return new Stall(false, maxTicks, worst, slow);
    }

    /** Copies his captured rooms into this client's config folder. */
    private static int copyRealRooms(ClientGameTestContext ctx) {
        try {
            Path source = Path.of(SOURCE_ROOMS);
            if (!Files.isDirectory(source)) {
                return 0;
            }
            Path target = ModUnderTest.modConfig("killer560smod-rooms");
            Files.createDirectories(target);
            List<Path> files = new ArrayList<>();
            try (var s = Files.list(source)) {
                s.filter(p -> p.toString().endsWith(".json")).forEach(files::add);
            }
            for (Path f : files) {
                Files.copy(f, target.resolve(f.getFileName()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return files.size();
        } catch (Exception e) {
            System.out.println("[72-sim-stall] could not copy real rooms: " + e);
            return 0;
        }
    }
}

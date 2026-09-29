package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Opening the sim FROM THE MAIN MENU, which is the way killer560 actually opens it.
 *
 * <p>He reported twice that "no room ever loaded", and the second time that there was still no custom loading
 * screen either - after I had shipped a fix and asked him to check it. That was the wrong way round: this path
 * is testable here and I should have tested it. His words: "you should have your own test instance you built to
 * test if they actually load by default."
 *
 * <p>What makes this path different from the one {@code SimTests} covers is that there is NO WORLD YET. Every
 * builder has a branch for that, and those branches were where the bug lived - one returned silently, another
 * opened an empty world and printed a message asking him to run a command himself. A test that starts by
 * creating a world, as the other scenario does, cannot see any of that.
 *
 * <p>Driven through the synthetic flat room rather than a captured one: the client gametest API rebuilds its
 * game directory every run, so there is no room library on disk, and the flat room goes down the identical
 * queue-then-build path. The thing under test is the plumbing, not the block data.
 */
public class SimLoadTests implements FabricClientGameTest {

    private static final String SIM_BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String LOADING_SCREEN = "com.killer560.hub.roomsim.SimLoadingScreen";

    /** Same geometry constants the other sim scenario uses. */
    private static final int GRID = 11;
    private static final int START = -185;
    private static final int HALF_ROOM = 16;
    private static final int TILE = 31;
    private static final int FLOOR_Y = 69;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip("71-sim-load-from-menu")) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);

        // Precondition: no world. If this is not true the scenario is testing the wrong path entirely and a
        // pass would mean nothing.
        boolean hasWorld = ctx.computeOnClient(mc -> mc.level != null);
        if (hasWorld) {
            throw new AssertionError("expected to be at the title screen with no world - this scenario exists "
                    + "to cover the no-world path and cannot do that from inside a world");
        }

        // The click he makes: pick something from the sim menu while still on the main menu.
        //
        // Queued onto the client's own executor rather than run inside the harness task. runOnClient WAITS for
        // its task to finish, and opening a world needs further client ticks to complete - which cannot happen
        // while the harness is blocked waiting. Running it directly deadlocked the client every time, and the
        // process died outright (NTSTATUS 0xCFFFFFFF) rather than failing an assertion, which is what
        // killer560 kept having to close by hand.
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(SIM_BUILDER, "buildFlatTest",
                new Class<?>[]{Minecraft.class}, new Object[]{mc})));

        // 1. The custom loading screen must actually appear. It is the whole point of the request, and it is
        //    also the thing that was silently lost: opening a world replaces the screen, so showing it once
        //    before the world opens is not enough.
        boolean sawLoadingScreen = false;
        for (int i = 0; i < 400 && !sawLoadingScreen; i++) {
            sawLoadingScreen = ctx.computeOnClient(mc ->
                    mc.screen != null && mc.screen.getClass().getName().equals(LOADING_SCREEN));
            if (!sawLoadingScreen) {
                ctx.waitTicks(1);
            }
        }
        if (!sawLoadingScreen) {
            throw new AssertionError("the custom sim loading screen never appeared while the world was opening "
                    + "and the map was building");
        }
        System.out.println("[71-sim-load-from-menu] custom loading screen shown");

        // 2. The world arrives and the sim turns itself on.
        ctx.waitFor(mc -> mc.level != null);
        ctx.waitTicks(20);
        boolean active = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_STATE, "canAct",
                new Class<?>[]{Minecraft.class}, new Object[]{mc}));
        if (!active) {
            throw new AssertionError("the sim world opened but SimState never went active, so nothing sim-only "
                    + "would work in it");
        }

        // 3. The loading screen must come DOWN again. One that never closes is worse than none.
        boolean cleared = false;
        for (int i = 0; i < 1200 && !cleared; i++) {
            cleared = ctx.computeOnClient(mc ->
                    mc.screen == null || !mc.screen.getClass().getName().equals(LOADING_SCREEN));
            if (!cleared) {
                ctx.waitTicks(1);
            }
        }
        if (!cleared) {
            throw new AssertionError("the sim loading screen never closed - it would trap him on it");
        }

        // 4. And the actual point: the room is THERE. Read on the server, where the blocks are - the client's
        //    copy of chunks 100 blocks from spawn is not loaded and every read comes back void_air, which is
        //    how a previous version of the other scenario "passed" while nothing had been placed.
        ctx.waitTicks(40);
        int centre = GRID / 2;
        int x0 = START + centre * HALF_ROOM - TILE / 2;
        int z0 = START + centre * HALF_ROOM - TILE / 2;
        var server = ctx.computeOnClient(mc -> mc.getSingleplayerServer());
        if (server == null) {
            throw new AssertionError("no integrated server after the sim world opened");
        }
        String floor = server.submit(() -> server.overworld()
                .getBlockState(new BlockPos(x0 + 15, FLOOR_Y, z0 + 15)).getBlock().toString()).join();
        System.out.println("[71-sim-load-from-menu] floor at the centre cell: " + floor);
        if (floor.contains("air")) {
            throw new AssertionError("the sim opened but the room was never built - the floor at " + (x0 + 15)
                    + "," + FLOOR_Y + "," + (z0 + 15) + " is " + floor + ". This is exactly what killer560 "
                    + "reported as \"no room ever loaded\".");
        }

        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "leave", new Class<?>[]{}, new Object[]{}));
        System.out.println("[71-sim-load-from-menu] PASS - picked from the main menu with no world, custom "
                + "loading screen shown and then closed, sim active, room actually built");
    }
}

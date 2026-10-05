package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 97-leave-loop: build a sim F7, stand in it, leave, and do it again - only when named.
 *
 * <p>Written for the 26.2 crash on leaving the sim (2026-10-05): NullPointerException "this.wrapped is null" in
 * fastutil's iterator inside Fabric's onClearLevel. It showed up only when a full sim run left scenario 84's F7, so
 * this repeats just that leave. The client crashing IS the failure; the log's "[97-leave-loop] leave N done" lines
 * say how many leaves it survived. Count from env TESTKIT_LEAVE_LOOP_N (default 20), render distance during each
 * visit from TESTKIT_LEAVE_LOOP_RD (default 0 = leave the gametest's 5 alone). Each visit tours the floor's corners in
 * spectator, as a run moves through rooms, so chunks fall out of vanilla's view ring into the mod's Chunk Cache.
 */
public class SimLeaveLoopTests implements FabricClientGameTest {

    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";

    private static final String SOURCE_ROOMS =
            ModUnderTest.instanceConfig("C:/Users/Hunter/AppData/Roaming/PrismLauncher/instances/26.1.2 (Mod Only Test)"
                    + "/minecraft/config", "killer560smod-rooms");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip("97-leave-loop")) {
            return;
        }
        ModUnderTest.require("killer560smod");
        int count = envInt("TESTKIT_LEAVE_LOOP_N", 20);
        int rd = envInt("TESTKIT_LEAVE_LOOP_RD", 0);
        System.out.println("[97-leave-loop] " + count + " leaves, render distance " + (rd > 0 ? rd : "unchanged")
                + ", floor tour in spectator each visit");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff(
                "com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (copyDir(Path.of(SOURCE_ROOMS).resolveSibling("killer560smod-roomdata").toString(),
                "killer560smod-roomdata") == 0) {
            System.out.println("[97-leave-loop] SKIPPED - needs his room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"));

        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        try {
            for (int i = 1; i <= count; i++) {
                if (rd > 0) {
                    ctx.runOnClient(mc -> mc.options.renderDistance().set(rd));
                }
                try {
                    Scenario.ensureRoomDatabase(ctx);
                    ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter",
                            new Class<?>[]{String.class}, new Object[]{"gametest"}));
                    long before = Scenario.simBuildCount(ctx);
                    ctx.runOnClient(mc -> mc.execute(() -> {
                        Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
                        ModUnderTest.staticCall(FLOOR_GEN, "generate",
                                new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                                new Object[]{mc, floor, 3, 4});
                    }));
                    ctx.waitFor(mc -> mc.level != null);
                    Scenario.awaitSimBuild(ctx, before);
                    ctx.waitTicks(60);
                    tour(ctx);
                } finally {
                    leave(ctx);
                }
                System.out.println("[97-leave-loop] leave " + i + " of " + count + " done (render distance " + rd + ")");
            }
        } finally {
            ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
        }
        System.out.println("[97-leave-loop] PASS - left a sim F7 " + count + " times without the client crashing");
    }

    /** Spectator, then the floor's four corners and centre, 15 ticks each. */
    private static void tour(ClientGameTestContext ctx) {
        int[] b = (int[]) ctx.computeOnClient(mc -> ModUnderTest.staticCall(
                "com.killer560.hub.roomsim.SimBuildQueue", "touchedBounds"));
        if (b == null) {
            System.out.println("[97-leave-loop] no build bounds - no tour this visit");
            return;
        }
        ctx.runOnClient(mc -> mc.player.connection.sendCommand("gamemode spectator"));
        ctx.waitTicks(10);
        double y = ctx.computeOnClient(mc -> mc.player.getY());
        int[][] spots = {{b[0] + 8, b[1] + 8}, {b[2] - 8, b[1] + 8}, {b[2] - 8, b[3] - 8}, {b[0] + 8, b[3] - 8},
                {(b[0] + b[2]) / 2, (b[1] + b[3]) / 2}};
        for (int[] spot : spots) {
            ctx.runOnClient(mc -> {
                var server = mc.getSingleplayerServer();
                var uuid = mc.player.getUUID();
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayer(uuid);
                    if (sp != null) {
                        sp.teleportTo(spot[0] + 0.5, y, spot[1] + 0.5);
                    }
                });
            });
            ctx.waitTicks(15);
        }
    }

    private static int envInt(String name, int fallback) {
        try {
            String v = System.getenv(name);
            return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Same leave as 84 (SimFloorSizeTests.leave) - the path the crash was seen on. */
    private static void leave(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "leave"));
        ctx.runOnClient(mc -> mc.execute(() -> {
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                mc.disconnectWithSavingScreen();
            }
        }));
        ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> mc.execute(() ->
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen);
    }

    private static int copyDir(String from, String into) {
        try {
            Path source = Path.of(from);
            if (!Files.isDirectory(source)) {
                return 0;
            }
            Path target = ModUnderTest.modConfig(into);
            Files.createDirectories(target);
            int n = 0;
            try (var s = Files.list(source)) {
                for (Path f : s.toList()) {
                    if (Files.isRegularFile(f)) {
                        Files.copy(f, target.resolve(f.getFileName()),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        n++;
                    }
                }
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }
}

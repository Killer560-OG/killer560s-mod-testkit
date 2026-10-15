package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The Dungeon Sim steps the video pilot (ui/VideoGeneralSuite, 1900-video-general) needs, made public from the sim
 * scenarios' own package-private helpers (SimAutoSecretTests, SimPuzzleSolveTests, SimMapTests). Nothing here asserts;
 * the video case says what it could not do and records what it can.
 */
public final class VideoSim {

    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String TELEPORT_UTILS = "com.killer560.hub.livemap.autoclear.TeleportUtils";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";

    private VideoSim() {
    }

    /** Room captures and the room database in place; false (and why, printed) when this machine has none. */
    public static boolean prepare(ClientGameTestContext ctx) {
        int rooms = SimMapTests.copyRoomsForOthers();
        if (rooms < 20) {
            System.out.println("[video] only " + rooms + " room captures - no sim footage");
            return false;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        return true;
    }

    /** From the title screen: a generated floor in the sim world, its chunks on the client. */
    public static void floor(ClientGameTestContext ctx, String floorName, int puzzles, int toBlood) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                new Object[]{"gametest"}));
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", floorName);
            ModUnderTest.staticCall(FLOOR_GEN, "generate",
                    new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, puzzles, toBlood});
        }));
        ctx.waitFor(mc -> mc.level != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        ctx.waitTicks(80);
        SimAutoSecretTests.awaitFloorChunks(ctx);
    }

    /** From the title screen: one room by itself, as Load a Room does it. */
    public static void singleRoom(ClientGameTestContext ctx, String room) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildSingleRoom",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, room})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        ctx.waitTicks(40);
    }

    /** A room of the floor: its name, type, unfound secrets and where a player can stand in its main tile. */
    public record Room(String name, String type, int unfound, Vec3 stand, Vec3 centre) {
    }

    /** Every room the map lists that has a standable block in its main tile. */
    public static List<Room> rooms(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            List<Room> out = new ArrayList<>();
            for (SimAutoSecretTests.Room r : SimAutoSecretTests.readRooms(mc)) {
                BlockPos centre = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                        new Object[]{r.mainTile()});
                BlockPos stand = (BlockPos) ModUnderTest.staticCall(TELEPORT_UTILS, "etherwarpableInTile",
                        new Class<?>[]{BlockPos.class, Vec3.class}, new Object[]{centre, Vec3.atCenterOf(centre)});
                if (stand != null) {
                    out.add(new Room(r.name(), r.type(), r.unfound(),
                            new Vec3(stand.getX() + 0.5, stand.getY() + 1, stand.getZ() + 0.5), Vec3.atCenterOf(centre)));
                }
            }
            return out;
        });
    }

    /** Leaves the sim world for the title screen. */
    public static void leave(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "leave"));
        ctx.runOnClient(mc -> mc.execute(() -> {
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("video over"));
                mc.disconnectWithSavingScreen();
            }
        }));
        ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 2400);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> mc.execute(() ->
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen, 400);
    }
}

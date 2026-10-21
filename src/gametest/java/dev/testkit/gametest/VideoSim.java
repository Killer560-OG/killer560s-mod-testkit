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

    /**
     * Night Vision on the sim's server player, for the camera only: the sim's rooms are much darker than Hypixel's
     * (even at Brightness: Bright), and footage of a near-black room shows nothing. No feature reads it.
     */
    public static void nightVision(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    sp.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                            net.minecraft.world.effect.MobEffects.NIGHT_VISION, 20 * 60 * 30, 0, false, false));
                }
            });
        });
        ctx.waitTicks(10);
    }

    /**
     * The map only lists rooms someone has been in. Stands the camera a moment in every cell of the floor's grid so the
     * map discovers them all (off camera), then puts it back where it was.
     */
    public static void discover(ClientGameTestContext ctx) {
        Vec3 back = ctx.computeOnClient(mc -> mc.player.position());
        for (int i = 0; i < 121; i++) {
            int tile = i;
            BlockPos c = ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter",
                    new Class<?>[]{int.class}, new Object[]{tile}));
            if (c == null) {
                continue;
            }
            ctx.runOnClient(mc -> mc.player.setPos(c.getX() + 0.5, c.getY() + 1, c.getZ() + 0.5));
            ctx.waitTicks(2);
        }
        ctx.runOnClient(mc -> mc.player.setPos(back.x, back.y, back.z));
        ctx.waitTicks(20);
    }

    /**
     * From the title screen: an F7 as 1016-sim-fullclear plans it (his designer filters, so with his routes from
     * {@code -PseedConfig} every ordinary room has one), built, his chunks loaded and his hotbar given. The map code, or
     * null when no floor could be planned. {@code code} replays a floor (null: a fresh one).
     */
    public static String fullClearFloor(ClientGameTestContext ctx, String code) {
        return SimFullClearTests.prepareFloor(ctx, code);
    }

    /** The rooms his routes file holds a route for. */
    public static java.util.Set<String> routedRooms(ClientGameTestContext ctx) {
        return SimFullClearTests.routed(ctx);
    }

    /**
     * From the title screen: an F7 (3 puzzles, 4 rooms to Blood, as 1020-sim-ac-speed generates it) with the sim's own
     * live starred mobs ({@code SimLiveMobs.configure}: {@code perTile} a tile of {@code hp} health, walking when
     * {@code walk}, a miniboss in each CHAMPION room) placed at GO, which also turns the sim's clear-on-entry off.
     * {@code code} replays a floor (null: a fresh one). Returns the map code; false-y (null) when the jar has no live mobs.
     */
    public static String liveMobFloor(ClientGameTestContext ctx, String code, int perTile, double hp, boolean walk) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                new Object[]{"gametest"}));
        try {
            ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimLiveMobs", "configure",
                    new Class<?>[]{int.class, double.class, boolean.class, boolean.class}, new Object[]{perTile, hp, walk, true}));
        } catch (RuntimeException | Error e) {
            System.out.println("[video] this jar has no SimLiveMobs: " + e);
            return null;
        }
        long before = Scenario.simBuildCount(ctx);
        if (code == null || code.isBlank()) {
            ctx.runOnClient(mc -> mc.execute(() -> {
                Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
                ModUnderTest.staticCall(FLOOR_GEN, "generate", new Class<?>[]{Minecraft.class, floor.getClass(), int.class,
                        int.class}, new Object[]{mc, floor, 3, 4});
            }));
        } else {
            ctx.runOnClient(mc -> mc.execute(() -> {
                ModUnderTest.staticCall(SIM_STATE, "setFloorLabel", new Class<?>[]{String.class}, new Object[]{"F7"});
                java.util.function.Consumer<Minecraft> build = c -> ModUnderTest.staticCall(BUILDER, "build",
                        new Class<?>[]{Minecraft.class, String.class}, new Object[]{c, code});
                ModUnderTest.staticCall("com.killer560.hub.roomsim.SimWorld", "open", new Class<?>[]{Minecraft.class,
                        String.class, java.util.function.Consumer.class, String.class},
                        new Object[]{mc, code, build, "Generating Floor 7"});
            }));
        }
        ctx.waitFor(mc -> mc.level != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        ctx.waitTicks(80);
        SimAutoClearTests.awaitFloorChunks(ctx);
        return ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(SIM_STATE, "mapCode")));
    }

    /** Switches the sim's live mobs off again (a jar without them: nothing). */
    public static void liveMobsOff(ClientGameTestContext ctx) {
        try {
            ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimLiveMobs", "configure",
                    new Class<?>[]{int.class, double.class, boolean.class, boolean.class}, new Object[]{0, 1.0, false, true}));
        } catch (RuntimeException | Error ignored) {
            // a jar without the option
        }
    }

    /** Auto Clear's mode and weapon by constant name (SimAutoClearTests.setMode), then waits for him on the ground. */
    public static void autoClearMode(ClientGameTestContext ctx, String mode, String weapon) {
        SimAutoClearTests.setMode(ctx, mode, weapon);
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

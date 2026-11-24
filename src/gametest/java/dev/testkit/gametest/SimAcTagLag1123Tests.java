package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * <b>1123-sim-ac-taglag</b> (mod dropaudit-acstarred, killer560 2026-11-23: Small Stairs, "tends to miss starred mobs in
 * rooms and just teleport around trying to find them but will be standing right next to them"). His 13:21 run: a starred
 * Speedy Skeleton Soldier whose name tag (a new stand, no body at its id - 1) stood beside the mob, so the ESP never tied
 * the tag to the mob and Auto Clear saw no starred mob. Here a starred zombie of a mob room has its tag replaced by a new
 * stand with a later id, 1.8 blocks to the side of the zombie. Asserted: Auto Clear acted (use packets) and the zombie
 * died, the room's clear finishing (onDone). The origin jar matches only a tag within the box under it and gives up.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimAcTagLag1123Tests implements FabricClientGameTest {

    private static final String NAME = "1123-sim-ac-taglag";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_MOBS = "com.killer560.hub.roomsim.SimMobs";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String SIM_ROOM_STATE = "com.killer560.hub.roomsim.SimRoomState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    private static final String AUTO = "com.killer560.hub.autoclear.AutoClearFeature";
    private static final String AUTO_CONFIG = "com.killer560.hub.autoclear.AutoClearConfig";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        if (!dev.testkit.gametest.mod.Mod.isCheat()) { // Auto Clear is cheat build only
            Scenario.skipped(NAME, "Auto Clear is cheat build only");
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
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"));
        ctx.runOnClient(mc -> {
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                    "setAutoPuzzlesMasterEnabled", false);
            Object map = ModUnderTest.config(LIVE_MAP_CONFIG);
            ModUnderTest.set(map, "setEnabled", true);
            ModUnderTest.set(map, "setInteractiveMapEnabled", true);
        });
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> mc.options.renderDistance().set(16));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                new Object[]{"gametest"}));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_MOBS, "setClearRoomsOnEntry", new Class<?>[]{boolean.class},
                new Object[]{false}));
        List<String> failures = new ArrayList<>();
        try {
            body(ctx, failures);
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_MOBS, "setClearRoomsOnEntry", new Class<?>[]{boolean.class},
                    new Object[]{true}));
            ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO, "cancel"));
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AUTO_CONFIG), "setEnabled", false));
            ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
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
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
        System.out.println("[" + NAME + "] PASS");
    }

    private static void body(ClientGameTestContext ctx, List<String> failures) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            ModUnderTest.staticCall(FLOOR_GEN, "generate", new Class<?>[]{Minecraft.class, floor.getClass(), int.class,
                    int.class}, new Object[]{mc, floor, 3, 4});
        }));
        ctx.waitFor(mc -> mc.level != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitTicks(80);
        SimAutoClearTests.awaitFloorChunks(ctx);
        println("MAP CODE: " + ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(SIM_STATE, "mapCode"))));
        AtomicReference<Boolean> given = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp == null) {
                    given.set(false);
                    return;
                }
                var inv = sp.getInventory();
                for (int i = 0; i < 36; i++) {
                    inv.setItem(i, ItemStack.EMPTY);
                }
                String[] ids = {"ASPECT_OF_THE_VOID", "HYPERION"};
                for (int i = 0; i < ids.length; i++) {
                    inv.setItem(i, (ItemStack) ModUnderTest.staticCall(SIM_ITEMS, "build", new Class<?>[]{String.class},
                            new Object[]{ids[i]}));
                }
                given.set(true);
            });
        });
        ctx.waitFor(mc -> given.get() != null, 200);
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
        for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomsim.SimRun", "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(20);
        Vec3 me = ctx.computeOnClient(mc -> mc.player.position());
        List<SimAutoClearTests.Room> mobRooms = new ArrayList<>();
        for (SimAutoClearTests.Room r : SimAutoClearTests.floor(ctx)) {
            if (r.mob()) {
                mobRooms.add(r);
            }
        }
        mobRooms.sort((a, b) -> Double.compare(SimAutoClearTests.dist(me, a), SimAutoClearTests.dist(me, b)));
        // Every mob room cleared first (A must read cleared on the map); the case un-clears its own room B.
        for (SimAutoClearTests.Room r : mobRooms) {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "markCleared", new Class<?>[]{String.class},
                    new Object[]{r.name()}));
        }
        ctx.waitTicks(5);
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(AUTO_CONFIG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setHyperionHops", true);
        });
        SimAutoClearTests.setMode(ctx, "ANY_MOB_ROOM", "HYPERION");
        String standing = SimAutoClear1020Tests.standingIn(ctx);
        for (SimAutoClearTests.Room room : mobRooms) {
            if (room.name().equals(standing)) {
                continue;
            }
            // {outX, y, outZ, inX, inZ}: 3.5 past one of B's tiles into another room A, and a spot inside B by that edge.
            double[] p = ctx.computeOnClient(mc -> crossing(room.name()));
            if (p == null) {
                continue;
            }
            AtomicReference<BlockPos[]> spots = new AtomicReference<>();
            ctx.runOnClient(mc -> {
                var server = mc.getSingleplayerServer();
                server.execute(() -> spots.set(new BlockPos[]{
                        SimAutoClear1020Tests.floorNear(server.overworld(), p[3], p[4], (int) p[1], 0, 0),
                        SimAutoClear1020Tests.floorNear(server.overworld(), p[0], p[2], (int) p[1], 0, 0)}));
            });
            ctx.waitFor(mc -> spots.get() != null, 200);
            BlockPos in = spots.get()[0];
            BlockPos out = spots.get()[1];
            if (in == null || out == null) {
                continue;
            }
            String a = ctx.computeOnClient(mc -> SimAutoClear1020Tests.roomNameAt(out.getX() + 0.5, out.getZ() + 0.5));
            String b = ctx.computeOnClient(mc -> SimAutoClear1020Tests.roomNameAt(in.getX() + 0.5, in.getZ() + 0.5));
            if (a == null || a.equals(room.name()) || !room.name().equals(b)) {
                continue;
            }
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "clearRoom", new Class<?>[]{String.class},
                    new Object[]{room.name()}));
            int pairs = SimAutoClear1020Tests.starredPairCount(ctx);
            ctx.runOnClient(mc -> {
                Object k = ModUnderTest.enumValue(SIM_MOBS + "$Kind", "ZOMBIE");
                ModUnderTest.staticCall(SIM_MOBS, "spawnStarred", new Class<?>[]{Minecraft.class, BlockPos.class,
                        k.getClass()}, new Object[]{mc, in, k});
            });
            ctx.waitTicks(3);
            UUID[] pair = SimAutoClear1020Tests.newestPair(ctx, pairs);
            if (pair == null) {
                failures.add("could not spawn the zombie in " + room.name());
                return;
            }
            ctx.waitTicks(20);
            // Hypixel's tag for a fast mob: a NEW stand with a later id (no body at id - 1), trailing 1.8 blocks sideways.
            AtomicReference<String> swapped = new AtomicReference<>();
            ctx.runOnClient(mc -> {
                var server = mc.getSingleplayerServer();
                server.execute(() -> {
                    var level = server.overworld();
                    var mob = level.getEntity(pair[0]);
                    var old = level.getEntity(pair[1]);
                    if (mob == null || old == null) {
                        swapped.set("missing");
                        return;
                    }
                    var tag = new net.minecraft.world.entity.decoration.ArmorStand(level, mob.getX() + 1.8,
                            mob.getY() + mob.getBbHeight() + 0.1, mob.getZ());
                    tag.setInvisible(true);
                    tag.setNoGravity(true);
                    tag.getEntityData().set(net.minecraft.world.entity.decoration.ArmorStand.DATA_CLIENT_FLAGS,
                            (byte) (tag.getEntityData().get(net.minecraft.world.entity.decoration.ArmorStand.DATA_CLIENT_FLAGS)
                                    | net.minecraft.world.entity.decoration.ArmorStand.CLIENT_FLAG_MARKER));
                    tag.setCustomName(old.getCustomName());
                    tag.setCustomNameVisible(true);
                    old.discard();
                    level.addFreshEntity(tag);
                    swapped.set("tag " + old.getId() + " -> " + tag.getId() + " mob " + mob.getId());
                    pair[1] = tag.getUUID();
                });
            });
            ctx.waitFor(mc -> swapped.get() != null, 100);
            println("taglag: " + swapped.get() + " in " + room.name());
            if (swapped.get() == null || swapped.get().equals("missing")) {
                failures.add("could not swap the star tag of the zombie in " + room.name());
                return;
            }
            ctx.waitTicks(5);
            AtomicReference<String> done = new AtomicReference<>();
            AtomicReference<String> gaveUp = new AtomicReference<>();
            SimAutoClearTests.Result r = SimAutoClear1020Tests.runCaseWith(ctx, "taglag", () -> ModUnderTest.staticCall(
                    AUTO, "clearRoom", new Class<?>[]{String.class, Runnable.class, Consumer.class},
                    new Object[]{room.name(), (Runnable) () -> done.set("done"), (Consumer<String>) gaveUp::set}), t -> {
            });
            Map<UUID, String> alive = SimAutoClearTests.aliveReport(ctx, List.of(new SimAutoClearTests.Placed(room.name(),
                    false, pair[0], in)));
            String line = String.format(Locale.US, "taglag (%s): mob %s, onDone=%s onGiveUp=%s, %d tick(s), uses %d",
                    room.name(), alive.get(pair[0]), done.get(), gaveUp.get(), r.ticks, r.uses);
            println(line);
            if (!"dead".equals(alive.get(pair[0])) || done.get() == null) {
                failures.add("the zombie with the trailing tag in " + room.name() + " was not killed: " + line);
            }
            if (r.uses == 0) {
                failures.add("Auto Clear sent no use packet: " + line);
            }
            return;
        }
        failures.add("setup: no mob room with a floor spot 3.5 blocks into a neighbor room");
    }

    /** {outX, y, outZ, inX, inZ}: 3.5 blocks past a tile of {@code roomName} into another room, and 3 inside it. */
    private static double[] crossing(String roomName) {
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        int room = SimAutoClear1020Tests.roomIndex(layout, roomName);
        if (room < 0) {
            return null;
        }
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int tile : (int[]) ModUnderTest.call(layout, "tiles", new Class<?>[]{int.class}, new Object[]{room})) {
            BlockPos c = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                    new Object[]{tile});
            for (int[] d : dirs) {
                double x = c.getX() + 0.5 + d[0] * 19.5;
                double z = c.getZ() + 0.5 + d[1] * 19.5;
                int there = (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class, double.class},
                        new Object[]{x, z});
                if (there >= 0 && there != room) {
                    return new double[]{x, c.getY(), z, c.getX() + 0.5 + d[0] * 12.0, c.getZ() + 0.5 + d[1] * 12.0};
                }
            }
        }
        return null;
    }

    static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}

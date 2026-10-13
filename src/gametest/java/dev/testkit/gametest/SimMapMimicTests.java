package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 1013-sim-map-mimic: the mimic's room stays red on the map for the rest of the run.
 *
 * <p>killer560, 2026-10-13: "If i get a mimic in a room do not have that room go back to being grey it should stay red
 * on my map." The map's mimic tint (cheat build) was a once-a-second SNAPSHOT of whether a {@code trapped_chest} stood
 * at one of the room's database secret positions. The mimic springing replaces that chest (Hypixel, and the sim with
 * air), so the tint went the moment he found it.
 *
 * <p>A real F7 floor from a map code (SimFloorGen.generate - SimBuilder.build), the run begun with SimRun.begin. The
 * mimic is moved to a secret chest whose nearest cell centre is not one of its room's cells, because on main the sim
 * also had a sim-only branch that painted the room from that NEAREST cell centre - which hid the bug for chests near
 * the middle of their room; the case is about the live check, which is what Hypixel runs. Then: stand in the room, see the
 * tint, open the chest on the server (the packet path's own handler), kill the mimic, walk to the entrance, clear the
 * mimic room and three others, and sample the room's fill colour for six seconds (six map scans). Cheat: every sample
 * must be the tinted colour. Legit (killer560, 2026-10-13): plain while the chest is shut and while the mimic is out
 * alive (no advance warning), then tinted in every sample after HIS kill.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimMapMimicTests implements FabricClientGameTest {

    static final String CASE = "1013-sim-map-mimic";

    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_RUN = "com.killer560.hub.roomsim.SimRun";
    private static final String SIM_MIMIC = "com.killer560.hub.roomsim.SimMimic";
    private static final String SIM_SECRETS = "com.killer560.hub.roomsim.SimSecrets";
    private static final String SIM_ROOM_STATE = "com.killer560.hub.roomsim.SimRoomState";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String LIVE = "com.killer560.hub.livemap.LiveMapFeature";
    private static final String PAINTER = "com.killer560.hub.livemap.MapPainter";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    private static final int GRID = 11;
    private static final int MIMIC_TINT = 0x33FF0000;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(CASE)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(CASE, "needs real room captures and room database");
            return;
        }
        boolean cheat;
        try {
            cheat = Class.forName("com.killer560.hub.BuildVariant").getField("CHEAT_FEATURES_ENABLED").getBoolean(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        try {
            run(ctx, cheat);
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "leave"));
            ctx.runOnClient(mc -> mc.execute(() -> {
                if (mc.level != null) {
                    mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                    mc.disconnectWithSavingScreen();
                }
            }));
            ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null);
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> mc.execute(() -> dev.testkit.compat.McCompat.setScreen(mc,
                    new net.minecraft.client.gui.screens.TitleScreen())));
            ctx.waitFor(mc -> dev.testkit.compat.McCompat.screen(mc)
                    instanceof net.minecraft.client.gui.screens.TitleScreen);
        }
    }

    private static void run(ClientGameTestContext ctx, boolean cheat) {
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"));
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(CONFIG), "setEnabled", true));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter",
                new Class<?>[]{String.class}, new Object[]{"gametest"}));
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            ModUnderTest.staticCall(FLOOR_GEN, "generate",
                    new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, 3, 3});
        }));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> mc.player.onGround(), 1800);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_RUN, "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall(BUILDER, "entranceDoor")}));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(SIM_RUN, "hasStarted"), 400);
        BlockPos entrance = ctx.computeOnClient(mc -> mc.player.blockPosition());

        Object picked = ctx.computeOnClient(mc -> ModUnderTest.staticCall(SIM_MIMIC, "position"));
        if (picked == null) {
            throw new AssertionError("the F7 floor has no mimic - the sim's one-per-run rule broke, nothing to test");
        }
        // The secret chest to make the mimic: in an eligible, identified room, off its tile centre if any is.
        Object[] choice = ctx.computeOnClient(mc -> chooseChest());
        if (choice == null) {
            throw new AssertionError("no secret chest in an eligible identified room on this floor");
        }
        BlockPos chest = (BlockPos) choice[0];
        int tile = (Integer) choice[1];
        String roomName = (String) choice[2];
        boolean offCentre = (Boolean) choice[3];
        System.out.println("[" + CASE + "] sim picked " + picked + "; moving the mimic to " + chest.toShortString()
                + " in " + roomName + " (tile " + tile + ", " + (offCentre ? "outside" : "INSIDE") + " the room's cells by nearest cell centre)");
        BlockPos old = (BlockPos) picked;
        serverRun(ctx, (server, sp) -> {
            var level = server.overworld();
            var was = level.getBlockState(old);
            if (was.is(Blocks.TRAPPED_CHEST)) {
                level.setBlockAndUpdate(old, Blocks.CHEST.defaultBlockState()
                        .setValue(ChestBlock.FACING, was.getValue(ChestBlock.FACING)));
            }
            var now = level.getBlockState(chest);
            var facing = now.hasProperty(ChestBlock.FACING) ? now.getValue(ChestBlock.FACING)
                    : net.minecraft.core.Direction.NORTH;
            level.setBlockAndUpdate(chest, Blocks.TRAPPED_CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing));
            try {
                Class<?> c = Class.forName(SIM_MIMIC);
                var f = c.getDeclaredField("mimic");
                f.setAccessible(true);
                f.set(null, chest);
                var found = c.getDeclaredField("found");
                found.setAccessible(true);
                found.set(null, false);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            sp.teleportTo(chest.getX() + 0.5, chest.getY() + 1, chest.getZ() + 0.5);
        });
        // The map may already have latched the sim's own pick in the second before the move; the test moved the
        // mimic, which Hypixel never does, so it clears that memory (a new jar's hook; main has no latch).
        ctx.runOnClient(mc -> {
            try {
                Class.forName(PAINTER).getDeclaredMethod("forgetMimics").setAccessible(true);
                ModUnderTest.staticCall(PAINTER, "forgetMimics", new Class<?>[0], new Object[0]);
            } catch (ReflectiveOperationException ignored) {
                // main: nothing latched
            }
        });
        int[] plainAndTinted = ctx.computeOnClient(mc -> colours(tile));
        int plain = plainAndTinted[0];
        int tinted = plainAndTinted[1];
        int expected = cheat ? tinted : plain;
        System.out.println(String.format("[%s] %s build: room plain #%08X, mimic-tinted #%08X, expecting #%08X",
                CASE, cheat ? "cheat" : "legit", plain, tinted, expected));

        // Before the chest is opened: in the room, chunk loaded, the scan sees the trapped chest.
        if (cheat) {
            ctx.waitFor(mc -> roomFill(tile) == tinted, 400);
        } else {
            ctx.waitTicks(60);
        }
        int beforeOpen = ctx.computeOnClient(mc -> roomFill(tile));
        System.out.println(String.format("[%s] in the room, chest shut: #%08X", CASE, beforeOpen));
        if (beforeOpen != expected) {
            throw new AssertionError(String.format("before the mimic was found the room read #%08X, expected #%08X",
                    beforeOpen, expected));
        }

        // Open it as the server answers the use packet, then kill the mimic.
        AtomicReference<Object> opened = new AtomicReference<>();
        serverRun(ctx, (server, sp) -> opened.set(ModUnderTest.staticCall(SIM_MIMIC, "openOnServer",
                new Class<?>[]{net.minecraft.server.level.ServerLevel.class, BlockPos.class},
                new Object[]{server.overworld(), chest})));
        Object mob = ctx.computeOnClient(mc -> ModUnderTest.staticCall(SIM_MIMIC, "mimicMob"));
        if (!Boolean.TRUE.equals(opened.get()) || mob == null) {
            throw new AssertionError("opening the chest did not spring the mimic (opened=" + opened.get()
                    + ", mob=" + mob + ") - nothing below would mean anything");
        }
        // Legit: the mimic is out but alive - still no tint (no advance warning, only after HIS kill).
        if (!cheat) {
            ctx.waitTicks(30);
            int sprung = ctx.computeOnClient(mc -> roomFill(tile));
            System.out.println(String.format("[%s] legit, mimic out and alive: #%08X", CASE, sprung));
            if (sprung != plain) {
                throw new AssertionError(String.format("legit tinted the room before the mimic died: #%08X", sprung));
            }
        }
        serverRun(ctx, (server, sp) -> {
            var e = server.overworld().getEntity((java.util.UUID) mob);
            if (e instanceof net.minecraft.world.entity.LivingEntity le) {
                le.hurtServer(server.overworld(), server.overworld().damageSources().playerAttack(sp), 1000f);
            }
        });
        ctx.waitFor(mc -> ModUnderTest.staticCall(SIM_MIMIC, "mimicMob") == null, 200);
        // The client's copy of the block can trail the server by more than a few ticks (26.2, 2026-10-13: still a chest
        // 12 ticks after the server set air), so wait for it rather than read it once.
        for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> mc.level.getBlockState(chest).isAir()); i++) {
            ctx.waitTicks(1);
        }
        boolean air = ctx.computeOnClient(mc -> mc.level.getBlockState(chest).isAir());
        System.out.println("[" + CASE + "] mimic " + mob + " spawned and killed; chest block now air on the client: "
                + air);
        if (!air) {
            throw new AssertionError("the mimic's chest is still there after it sprang - the case never removed what"
                    + " the old snapshot keyed on");
        }

        // Leave the room, and the map moves on: four rooms (the mimic's among them) get their checkmark.
        serverRun(ctx, (server, sp) -> sp.teleportTo(entrance.getX() + 0.5, entrance.getY(), entrance.getZ() + 0.5));
        List<String> cleared = ctx.computeOnClient(mc -> clearRooms(roomName));
        System.out.println("[" + CASE + "] cleared on the map: " + cleared);
        if (cleared.size() < 2) {
            throw new AssertionError("could not clear other rooms - the map never changed under the mimic room");
        }
        int clearedPlain = ctx.computeOnClient(mc -> colours(tile)[0]);
        int clearedTinted = ctx.computeOnClient(mc -> colours(tile)[1]);
        int expectAfter = clearedTinted;   // both builds: after his kill the room is red
        int bad = 0;
        StringBuilder samples = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            ctx.waitTicks(20);
            int fill = ctx.computeOnClient(mc -> roomFill(tile));
            samples.append(String.format(" #%08X", fill));
            if (fill != expectAfter) {
                bad++;
            }
        }
        String tiles = ctx.computeOnClient(mc -> {
            try {
                return java.util.Arrays.toString((int[]) ModUnderTest.staticCall(PAINTER, "mimicTiles",
                        new Class<?>[0], new Object[0]));
            } catch (Throwable t) {
                return "n/a on this jar";
            }
        });
        System.out.println(String.format("[%s] after: expecting #%08X, samples%s; latched tiles %s", CASE,
                expectAfter, samples, tiles));
        // Every OTHER room must read untinted: one mimic per run, one red room.
        List<String> otherRed = ctx.computeOnClient(mc -> otherTintedRooms(tile));
        System.out.println("[" + CASE + "] other rooms drawn with the mimic tint: " + otherRed);
        if (!otherRed.isEmpty()) {
            throw new AssertionError("rooms other than the mimic's are drawn with the mimic tint: " + otherRed);
        }
        if (bad > 0) {
            throw new AssertionError(String.format("%d of 6 samples after the mimic died were not #%08X (%s):%s",
                    bad, expectAfter, "the room is not mimic red after his kill", samples));
        }
        System.out.println("[" + CASE + "] PASS - " + (cheat
                ? "the mimic room stayed red through the kill, leaving it and four rooms clearing"
                : "legit: plain until his kill, then red through leaving it and four rooms clearing"));
    }

    /** {chest, tile, room name, off-centre} or null. Client thread. */
    private static Object[] chooseChest() {
        @SuppressWarnings("unchecked")
        List<BlockPos> placed;
        try {
            placed = (List<BlockPos>) Class.forName(SIM_SECRETS).getField("PLACED_CHESTS").get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        Object[] fallback = null;
        for (BlockPos p : placed) {
            int tile = tileOf(p);
            Object group = group(tile);
            if (group == null) {
                continue;
            }
            Object entry = field(group, "entry");
            String name = entry == null ? null : (String) field(entry, "name");
            if (name == null || !(Boolean) ModUnderTest.staticCall(SIM_MIMIC, "roomEligible",
                    new Class<?>[]{String.class}, new Object[]{name})) {
                continue;
            }
            // "Off" = the nearest cell centre of ALL 121 (what main's sim-only branch used) is not one of this room's
            // cells, so main has nothing but the live check to keep the room red.
            int nearest = -1;
            long bestD = Long.MAX_VALUE;
            for (int idx = 0; idx < GRID * GRID; idx++) {
                BlockPos c = cellCenter(idx);
                long dx = c.getX() - p.getX();
                long dz = c.getZ() - p.getZ();
                if (dx * dx + dz * dz < bestD) {
                    bestD = dx * dx + dz * dz;
                    nearest = idx;
                }
            }
            boolean off = true;
            for (int cell : (int[]) field(group, "cells")) {
                if (cell == nearest) {
                    off = false;
                    break;
                }
            }
            Object[] pick = {p, tile, name, off};
            if (off) {
                return pick;
            }
            if (fallback == null) {
                fallback = pick;
            }
        }
        return fallback;
    }

    /** Marks the mimic's room and up to three other rooms cleared. */
    private static List<String> clearRooms(String mimicRoom) {
        List<String> out = new ArrayList<>();
        ModUnderTest.staticCall(SIM_ROOM_STATE, "markCleared", new Class<?>[]{String.class}, new Object[]{mimicRoom});
        out.add(mimicRoom);
        @SuppressWarnings("unchecked")
        List<Object> groups = (List<Object>) ModUnderTest.staticCall(LIVE, "groupsView", new Class<?>[0], new Object[0]);
        for (Object g : groups) {
            if (out.size() >= 4) {
                break;
            }
            Object entry = field(g, "entry");
            String name = entry == null ? null : (String) field(entry, "name");
            if (name != null && !out.contains(name)) {
                ModUnderTest.staticCall(SIM_ROOM_STATE, "markCleared", new Class<?>[]{String.class}, new Object[]{name});
                out.add(name);
            }
        }
        return out;
    }

    /** Names (with their tiles) of every room other than the one on {@code mimicTile} whose fill is the tinted colour. */
    private static List<String> otherTintedRooms(int mimicTile) {
        List<String> out = new ArrayList<>();
        Object mine = group(mimicTile);
        Object cfg = ModUnderTest.config(CONFIG);
        for (Object g : (List<?>) ModUnderTest.staticCall(LIVE, "groupsView", new Class<?>[0], new Object[0])) {
            if (g == mine) {
                continue;
            }
            int fill = (Integer) ModUnderTest.staticCall(PAINTER, "roomColor", new Class<?>[]{g.getClass(), cfgClass()},
                    new Object[]{g, cfg});
            int t = ((int[]) field(g, "tiles"))[0];
            if (fill == colours(t)[1] && fill != colours(t)[0]) {
                Object entry = field(g, "entry");
                out.add((entry == null ? "?" : field(entry, "name")) + "@" + t);
            }
        }
        return out;
    }

    private static int tileOf(BlockPos p) {
        int best = -1;
        long bestD = Long.MAX_VALUE;
        for (int idx = 0; idx < GRID * GRID; idx++) {
            if ((idx % GRID) % 2 != 0 || (idx / GRID) % 2 != 0) {
                continue;
            }
            BlockPos c = cellCenter(idx);
            long dx = c.getX() - p.getX();
            long dz = c.getZ() - p.getZ();
            if (dx * dx + dz * dz < bestD) {
                bestD = dx * dx + dz * dz;
                best = idx;
            }
        }
        return best;
    }

    private static BlockPos cellCenter(int idx) {
        return (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class}, new Object[]{idx});
    }

    private static Object group(int tile) {
        int gid = (Integer) ModUnderTest.staticCall(LIVE, "groupIdAt", new Class<?>[]{int.class}, new Object[]{tile});
        if (gid < 0) {
            return null;
        }
        return ((List<?>) ModUnderTest.staticCall(LIVE, "groupsView", new Class<?>[0], new Object[0])).get(gid);
    }

    /** The fill the map draws for the room on this tile - what MapPainter.drawRoom paints. */
    private static int roomFill(int tile) {
        Object g = group(tile);
        if (g == null) {
            return 0;
        }
        return (Integer) ModUnderTest.staticCall(PAINTER, "roomColor", new Class<?>[]{g.getClass(), cfgClass()},
                new Object[]{g, ModUnderTest.config(CONFIG)});
    }

    /** {plain type colour, the same with the mimic tint} for the room on this tile. */
    private static int[] colours(int tile) {
        Object g = group(tile);
        Object cfg = ModUnderTest.config(CONFIG);
        String type = (String) ModUnderTest.staticCall(PAINTER, "roomType", new Class<?>[]{g.getClass()}, new Object[]{g});
        int plain = (Integer) ModUnderTest.staticCall(PAINTER, "typeColor", new Class<?>[]{String.class, cfgClass()},
                new Object[]{type, cfg});
        int tinted = (Integer) ModUnderTest.staticCall(PAINTER, "mix", new Class<?>[]{int.class, int.class},
                new Object[]{plain, MIMIC_TINT});
        return new int[]{plain, tinted};
    }

    private static Class<?> cfgClass() {
        try {
            return Class.forName(CONFIG);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    private static Object field(Object target, String name) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void serverRun(ClientGameTestContext ctx, java.util.function.BiConsumer<net.minecraft.server.MinecraftServer,
            net.minecraft.server.level.ServerPlayer> task) {
        AtomicReference<Boolean> done = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                try {
                    task.accept(server, server.getPlayerList().getPlayer(uuid));
                } catch (Throwable t) {
                    err.set(t);
                } finally {
                    done.set(true);
                }
            });
        });
        ctx.waitFor(mc -> done.get() != null, 200);
        if (err.get() != null) {
            throw new AssertionError("server task failed", err.get());
        }
        ctx.waitTicks(2);
    }
}

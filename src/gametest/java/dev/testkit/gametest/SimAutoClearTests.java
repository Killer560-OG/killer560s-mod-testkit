package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.PacketWatch;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Auto Clear (mod 2026-10-06, cheat build) on a generated sim F7: it must travel to the starred mobs of the rooms it is
 * meant to clear, kill them with its weapon's ability (no melee - no entity interaction packet at all), get the room
 * flipped cleared on the map, leave the rooms its mode excludes alone, and kill a starred mob standing INSIDE a wall
 * within the ability's blast.
 *
 * <p>One floor, three cases in this order:
 * <ol>
 *   <li><b>rush</b> - Blood Rush Split, Hyperion: a mob room on the Entrance-to-Blood path (two zombies, one in a wall)
 *       and two mob rooms off it (one zombie each). It must clear the path room and finish while both off-path rooms
 *       keep their mobs and stay uncleared.</li>
 *   <li><b>room</b> - the Auto Secret API, {@code clearRoom(name, onDone, onGiveUp)}, Spirit Sceptre, on the first
 *       off-path room: onDone, never onGiveUp.</li>
 *   <li><b>any</b> - Any Mob Room, Hyperion: the last room (its zombie in a wall) cleared, then "nothing left".</li>
 * </ol>
 * Every other mob room is marked cleared first ({@code SimRoomState.markCleared}, what the map would show), so the
 * mode's choice is between exactly the rooms set up here. Sim mobs have 1 HP and never move.
 */
public class SimAutoClearTests implements FabricClientGameTest {

    private static final String NAME = "131-sim-auto-clear";
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
    private static final int GRID = 11;
    private static final int CASE_TICKS = 2400;

    /** One room of the floor as Auto Clear reports it. */
    private record Room(String name, String type, boolean mob, boolean cleared, boolean rush, int x, int z) {
        static Room parse(String line) {
            String[] p = line.split("\\|");
            return new Room(p[0], p[1], p[2].equals("1"), p[3].equals("1"), p[4].equals("1"),
                    Integer.parseInt(p[5]), Integer.parseInt(p[6]));
        }
    }

    /** A starred mob placed here: which room, whether in a wall, its id. */
    private record Placed(String room, boolean inWall, UUID id, BlockPos at) {
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        // Only when named (a whole sim floor and three clears, several minutes).
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            System.out.println("[" + NAME + "] SKIPPED - needs his real rooms and room database");
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

        List<String> failures = new ArrayList<>();
        try {
            body(ctx, failures);
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
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
        // ---- a floor with a mob room on the blood rush and two off it --------------------------------------------
        List<Room> rooms = null;
        Room rush = null;
        List<Room> off = new ArrayList<>();
        for (int attempt = 1; attempt <= 4 && rush == null; attempt++) {
            long before = Scenario.simBuildCount(ctx);
            ctx.runOnClient(mc -> mc.execute(() -> {
                Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
                ModUnderTest.staticCall(FLOOR_GEN, "generate",
                        new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                        new Object[]{mc, floor, 3, 4});
            }));
            ctx.waitFor(mc -> mc.level != null);
            Scenario.awaitSimBuild(ctx, before);
            ctx.waitTicks(80);
            awaitFloorChunks(ctx);
            rooms = floor(ctx);
            off.clear();
            Vec3 me = ctx.computeOnClient(mc -> mc.player.position());
            List<Room> offAll = new ArrayList<>();
            for (Room r : rooms) {
                if (r.mob() && !r.cleared() && r.rush() && rush == null) {
                    rush = r;
                } else if (r.mob() && !r.cleared() && !r.rush()) {
                    offAll.add(r);
                }
            }
            offAll.sort((a, b) -> Double.compare(dist(me, a), dist(me, b)));
            for (int i = 0; i < Math.min(2, offAll.size()); i++) {
                off.add(offAll.get(i));
            }
            println("floor " + attempt + ": " + rooms.size() + " rooms; rush path "
                    + rooms.stream().filter(Room::rush).map(Room::name).toList() + "; mob room on it: "
                    + (rush == null ? "none" : rush.name()) + "; off it: " + off.stream().map(Room::name).toList());
            if (off.size() < 2) {
                rush = null;
            }
        }
        if (rush == null) {
            failures.add("no generated floor in 4 had a mob room on the blood rush and two off it");
            return;
        }

        // ---- loadout: AOTV for the map's etherwarps, Hyperion, Spirit Sceptre ------------------------------------
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
                String[] ids = {"ASPECT_OF_THE_VOID", "HYPERION", "BAT_WAND"};
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

        // ---- the run starts (the entrance gate opens) -----------------------------------------------------------
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
        for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomsim.SimRun", "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(20);

        // ---- every other mob room cleared, so the modes choose between exactly these three ----------------------
        List<String> keep = List.of(rush.name(), off.get(0).name(), off.get(1).name());
        int marked = 0;
        for (Room r : rooms) {
            if (r.mob() && !keep.contains(r.name())) {
                ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "markCleared",
                        new Class<?>[]{String.class}, new Object[]{r.name()}));
                marked++;
            }
        }
        ctx.waitTicks(5);
        println(marked + " other mob room(s) marked cleared; targets " + keep);

        // ---- starred mobs ------------------------------------------------------------------------------------
        List<Placed> placed = new ArrayList<>();
        placed.addAll(spawnIn(ctx, rush, 1, true));
        placed.addAll(spawnIn(ctx, off.get(0), 1, false));
        placed.addAll(spawnIn(ctx, off.get(1), 0, true));
        println("placed " + placed.size() + " starred mob(s): " + placed);
        long wallMobs = placed.stream().filter(Placed::inWall).count();
        if (placed.size() < 4 || wallMobs < 2) {
            failures.add("could not place the mobs (" + placed.size() + " placed, " + wallMobs + " in a wall)");
            return;
        }
        ctx.waitTicks(40);
        Map<UUID, String> alive = aliveReport(ctx, placed);
        println("alive before: " + alive);
        for (Placed p : placed) {
            if (!"alive".equals(alive.get(p.id()))) {
                failures.add("a placed mob is not alive before the run (" + p + ": " + alive.get(p.id()) + ")");
            }
        }
        if (!failures.isEmpty()) {
            return;
        }
        int visible = ctx.computeOnClient(mc -> ((List<?>) ModUnderTest.staticCall("com.killer560.hub.mobesp.MobEspFeature",
                "starredMobs", new Class<?>[]{Minecraft.class}, new Object[]{mc})).size());
        println("starred mobs the client resolves: " + visible);

        // ---- Auto Clear on -------------------------------------------------------------------------------------
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(AUTO_CONFIG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setHyperionHops", true);
            ModUnderTest.set(cfg, "setStatusHud", true);
        });

        // ==== case 1: Blood Rush Split, Hyperion ====
        setMode(ctx, "BLOOD_RUSH_SPLIT", "HYPERION");
        Result r1 = runCase(ctx, "rush", () -> ModUnderTest.staticCall(AUTO, "start"));
        Map<UUID, String> after1 = aliveReport(ctx, placed);
        List<Room> floor1 = floor(ctx);
        println("rush: " + r1 + "; mobs " + after1);
        check(failures, "rush", r1, placed, after1, floor1, List.of(rush.name()),
                List.of(off.get(0).name(), off.get(1).name()));
        if (r1.lastLine == null || !r1.lastLine.contains("nothing left")) {
            failures.add("rush: did not finish with 'nothing left' (" + r1.lastLine + ")");
        }

        // ==== case 2: clearRoom (Auto Secret's API), Spirit Sceptre ====
        setMode(ctx, "ANY_MOB_ROOM", "SPIRIT_SCEPTRE");
        AtomicReference<String> done = new AtomicReference<>();
        AtomicReference<String> gaveUp = new AtomicReference<>();
        String roomTwo = off.get(0).name();
        Result r2 = runCase(ctx, "room", () -> ModUnderTest.staticCall(AUTO, "clearRoom",
                new Class<?>[]{String.class, Runnable.class, Consumer.class},
                new Object[]{roomTwo, (Runnable) () -> done.set("done"), (Consumer<String>) gaveUp::set}));
        Map<UUID, String> after2 = aliveReport(ctx, placed);
        List<Room> floor2 = floor(ctx);
        println("room: " + r2 + "; onDone=" + done.get() + " onGiveUp=" + gaveUp.get() + "; mobs " + after2);
        check(failures, "room", r2, placed, after2, floor2, List.of(roomTwo), List.of(off.get(1).name()));
        if (done.get() == null || gaveUp.get() != null) {
            failures.add("room: clearRoom called onDone=" + done.get() + " onGiveUp=" + gaveUp.get());
        }

        // ==== case 3: Any Mob Room, Hyperion ====
        setMode(ctx, "ANY_MOB_ROOM", "HYPERION");
        Result r3 = runCase(ctx, "any", () -> ModUnderTest.staticCall(AUTO, "start"));
        Map<UUID, String> after3 = aliveReport(ctx, placed);
        List<Room> floor3 = floor(ctx);
        println("any: " + r3 + "; mobs " + after3);
        check(failures, "any", r3, placed, after3, floor3, List.of(off.get(1).name()), List.of());
        if (r3.lastLine == null || !r3.lastLine.contains("nothing left")) {
            failures.add("any: did not finish with 'nothing left' (" + r3.lastLine + ")");
        }
        for (Placed p : placed) {
            if (p.inWall() && !"dead".equals(after3.get(p.id()))) {
                failures.add("the starred mob inside a wall in " + p.room() + " at " + p.at().toShortString()
                        + " is " + after3.get(p.id()));
            }
        }

        // ==== case 4: a near mob in the open - Hyperion hops, not an etherwarp ====
        // He stands in the room case 3 cleared. A new starred zombie 9-16 blocks away with a clear line from his eye,
        // the room un-cleared (SimRoomState.clearRoom - the sim re-clears it when that zombie dies), then clearRoom.
        String here = off.get(1).name();
        Vec3 eye = ctx.computeOnClient(mc -> mc.player.getEyePosition());
        AtomicReference<BlockPos> near = new AtomicReference<>(nearSpotInRoom(ctx, eye, here));
        if (near.get().equals(BlockPos.ZERO)) {
            failures.add("hop: no open floor spot 9-16 blocks from him in " + here + " to put a mob on");
            return;
        }
        int before = starredPairCount(ctx);
        ctx.runOnClient(mc -> {
            Object k = ModUnderTest.enumValue(SIM_MOBS + "$Kind", "ZOMBIE");
            ModUnderTest.staticCall(SIM_MOBS, "spawnStarred", new Class<?>[]{Minecraft.class, BlockPos.class,
                    k.getClass()}, new Object[]{mc, near.get(), k});
        });
        ctx.waitTicks(3);
        UUID hopMob = newestStarred(ctx, before);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "clearRoom", new Class<?>[]{String.class},
                new Object[]{here}));
        ctx.waitTicks(30);
        List<Placed> hopPlaced = hopMob == null ? List.of() : List.of(new Placed(here, false, hopMob, near.get()));
        println("hop: a zombie at " + near.get().toShortString() + String.format(java.util.Locale.US,
                " (%.1f blocks from his eye)", eye.distanceTo(Vec3.atCenterOf(near.get()))));
        AtomicReference<String> done4 = new AtomicReference<>();
        AtomicReference<String> gaveUp4 = new AtomicReference<>();
        Result r4 = runCase(ctx, "hop", () -> ModUnderTest.staticCall(AUTO, "clearRoom",
                new Class<?>[]{String.class, Runnable.class, Consumer.class},
                new Object[]{here, (Runnable) () -> done4.set("done"), (Consumer<String>) gaveUp4::set}));
        Map<UUID, String> after4 = aliveReport(ctx, hopPlaced);
        println("hop: " + r4 + "; onDone=" + done4.get() + " onGiveUp=" + gaveUp4.get() + "; mob " + after4);
        if (hopPlaced.isEmpty() || !"dead".equals(after4.get(hopMob))) {
            failures.add("hop: the near zombie is " + (hopPlaced.isEmpty() ? "not placed" : after4.get(hopMob)));
        }
        if (r4.hops == 0) {
            failures.add("hop: no Hyperion hop for a mob " + String.format(java.util.Locale.US, "%.1f",
                    eye.distanceTo(Vec3.atCenterOf(near.get()))) + " blocks away in the open");
        }
        if (r4.interacts > 0) {
            failures.add("hop: " + r4.interacts + " entity interaction packet(s)");
        }
        if (r4.travelled < 3.0) {
            failures.add("hop: travelled only " + String.format(java.util.Locale.US, "%.1f", r4.travelled) + " blocks");
        }
        if (done4.get() == null || gaveUp4.get() != null) {
            failures.add("hop: clearRoom called onDone=" + done4.get() + " onGiveUp=" + gaveUp4.get());
        }

        // ==== case 5: a server correction mid-clear - a chat line and the alarm, then it carries on ====
        // Another near zombie in the same room, then two ticks after the start the SERVER moves him three or four
        // blocks sideways (a real teleport, as a correction is). It must count the correction, not stop, and still
        // kill the zombie (killer560, 2026-10-06: "Nothing in this mod should stop from server corrections ever").
        Vec3 eye5 = ctx.computeOnClient(mc -> mc.player.getEyePosition());
        AtomicReference<BlockPos> near5 = new AtomicReference<>(nearSpotInRoom(ctx, eye5, here));
        AtomicReference<BlockPos> side = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            BlockPos mobAt = near5.get();
            server.execute(() -> side.set(sideSpot(server.overworld(), eye5, mobAt)));
        });
        ctx.waitFor(mc -> side.get() != null, 200);
        if (near5.get().equals(BlockPos.ZERO) || side.get().equals(BlockPos.ZERO)) {
            failures.add("correction: no spot for the zombie (" + near5.get() + ") or the sideways move (" + side.get() + ")");
            return;
        }
        int before5 = starredPairCount(ctx);
        ctx.runOnClient(mc -> {
            Object k = ModUnderTest.enumValue(SIM_MOBS + "$Kind", "ZOMBIE");
            ModUnderTest.staticCall(SIM_MOBS, "spawnStarred", new Class<?>[]{Minecraft.class, BlockPos.class,
                    k.getClass()}, new Object[]{mc, near5.get(), k});
        });
        ctx.waitTicks(3);
        UUID mob5 = newestStarred(ctx, before5);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "clearRoom", new Class<?>[]{String.class},
                new Object[]{here}));
        ctx.waitTicks(30);
        AtomicReference<String> done5 = new AtomicReference<>();
        AtomicReference<String> gaveUp5 = new AtomicReference<>();
        Result r5 = runCase(ctx, "correction", () -> {
            ModUnderTest.staticCall(AUTO, "clearRoom", new Class<?>[]{String.class, Runnable.class, Consumer.class},
                    new Object[]{here, (Runnable) () -> done5.set("done"), (Consumer<String>) gaveUp5::set});
            Minecraft mc = Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            UUID me = mc.player.getUUID();
            BlockPos to = side.get();
            // Two server ticks later, so it lands while the clear is already deciding/aiming.
            server.execute(() -> server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(me);
                if (sp != null) {
                    sp.teleportTo(server.overworld(), to.getX() + 0.5, to.getY(), to.getZ() + 0.5,
                            java.util.Set.of(net.minecraft.world.entity.Relative.Y_ROT,
                                    net.minecraft.world.entity.Relative.X_ROT), 0f, 0f, false);
                }
            }));
        });
        List<Placed> p5 = mob5 == null ? List.of() : List.of(new Placed(here, false, mob5, near5.get()));
        Map<UUID, String> after5 = aliveReport(ctx, p5);
        println("correction: " + r5 + "; onDone=" + done5.get() + " onGiveUp=" + gaveUp5.get() + "; mob " + after5
                + "; moved sideways to " + side.get().toShortString());
        if (r5.corrections < 1) {
            failures.add("correction: the server's teleport was not taken as a correction (no '[AutoClear] server "
                    + "correction' line)");
        }
        if (done5.get() == null || gaveUp5.get() != null) {
            failures.add("correction: clearRoom called onDone=" + done5.get() + " onGiveUp=" + gaveUp5.get()
                    + " - it must carry on after a correction");
        }
        if (p5.isEmpty() || !"dead".equals(after5.get(mob5))) {
            failures.add("correction: the zombie is " + (p5.isEmpty() ? "not placed" : after5.get(mob5)));
        }
    }

    /**
     * A standable floor spot 3-4 blocks from {@code eye}'s feet, as square-on to the line toward {@code mob} as there
     * is - so the move cannot be mistaken for a Wither Impact hop toward the mob landing. ZERO if none.
     */
    private static BlockPos sideSpot(ServerLevel level, Vec3 eye, BlockPos mob) {
        int ex = (int) Math.floor(eye.x);
        int ez = (int) Math.floor(eye.z);
        int fy = (int) Math.floor(eye.y - 1.62);
        double mx = mob.getX() + 0.5 - eye.x;
        double mz = mob.getZ() + 0.5 - eye.z;
        double ml = Math.max(1e-6, Math.sqrt(mx * mx + mz * mz));
        BlockPos best = BlockPos.ZERO;
        double bestDot = Double.MAX_VALUE;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                double r = Math.sqrt(dx * dx + dz * dz);
                if (r < 3 || r > 4.5) {
                    continue;
                }
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos feet = new BlockPos(ex + dx, fy + dy, ez + dz);
                    if (solid(level, feet.below()) && !solid(level, feet) && !solid(level, feet.above())) {
                        double dot = Math.abs((dx * mx + dz * mz) / (r * ml));
                        if (dot < bestDot) {
                            bestDot = dot;
                            best = feet;
                        }
                        break;
                    }
                }
            }
        }
        return best;
    }

    /**
     * {@link #openSpots} filtered on the client to the ones the live map puts in {@code room} - a spot 12 blocks off can
     * be in the next room, whose mob would never clear this one (2026-10-06's first correction run: "already cleared").
     */
    private static BlockPos nearSpotInRoom(ClientGameTestContext ctx, Vec3 eye, String room) {
        AtomicReference<List<BlockPos>> spots = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID me = mc.player.getUUID();
            server.execute(() -> spots.set(openSpots(server.overworld(), eye, server.getPlayerList().getPlayer(me))));
        });
        ctx.waitFor(mc -> spots.get() != null, 200);
        return ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            for (BlockPos p : spots.get()) {
                int r = (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class, double.class},
                        new Object[]{p.getX() + 0.5, p.getZ() + 0.5});
                String name = (String) ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r});
                if (room.equals(name)) {
                    return p;
                }
            }
            return BlockPos.ZERO;
        });
    }

    /** Standable floor spots 9-16 blocks (horizontally) from {@code eye} with a clear line to them, nearest 12 first. */
    private static List<BlockPos> openSpots(ServerLevel level, Vec3 eye, Entity viewer) {
        List<double[]> scored = new ArrayList<>();
        int ex = (int) Math.floor(eye.x);
        int ez = (int) Math.floor(eye.z);
        int fy = (int) Math.floor(eye.y - 1.62);
        for (int dx = -16; dx <= 16; dx++) {
            for (int dz = -16; dz <= 16; dz++) {
                double h = Math.sqrt(dx * dx + dz * dz);
                if (h < 9 || h > 16) {
                    continue;
                }
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos feet = new BlockPos(ex + dx, fy + dy, ez + dz);
                    if (!solid(level, feet.below()) || solid(level, feet) || solid(level, feet.above())
                            || solid(level, feet.above(2))) {
                        continue;
                    }
                    Vec3 target = new Vec3(feet.getX() + 0.5, feet.getY() + 1.0, feet.getZ() + 0.5);
                    var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, target,
                            net.minecraft.world.level.ClipContext.Block.COLLIDER,
                            net.minecraft.world.level.ClipContext.Fluid.NONE, viewer));
                    if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
                        continue;
                    }
                    scored.add(new double[]{Math.abs(h - 12.0), feet.getX(), feet.getY(), feet.getZ()});
                }
            }
        }
        scored.sort((a, b) -> Double.compare(a[0], b[0]));
        List<BlockPos> out = new ArrayList<>();
        for (double[] d : scored) {
            out.add(new BlockPos((int) d[1], (int) d[2], (int) d[3]));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------- a case

    private static final class Result {
        double travelled;
        int ticks;
        int uses;
        int interacts;
        float maxUseJump;
        int hops;
        int etherwarps;
        int corrections;
        boolean finished;
        String lastLine;

        @Override
        public String toString() {
            return String.format(java.util.Locale.US, "%s in %.1f s, travelled %.1f blocks, %d use(s), %d entity "
                            + "interaction(s), largest use-vs-movement yaw jump %.1f, %d hop(s), %d etherwarp trip(s), "
                            + "%d correction(s), last line: %s", finished ? "finished" : "STILL RUNNING", ticks / 20.0,
                    travelled, uses, interacts, maxUseJump, hops, etherwarps, corrections, lastLine);
        }
    }

    private static Result runCase(ClientGameTestContext ctx, String label, Runnable start) {
        Result r = new Result();
        long mark = LogTap.mark();
        PacketWatch.start();
        ctx.runOnClient(mc -> start.run());
        Vec3 last = ctx.computeOnClient(mc -> mc.player.position());
        for (int t = 0; t < CASE_TICKS; t++) {
            ctx.waitTicks(1);
            Vec3 now = ctx.computeOnClient(mc -> mc.player.position());
            r.travelled += now.distanceTo(last);
            last = now;
            r.ticks = t + 1;
            if (!(Boolean) ctx.computeOnClient(mc -> ModUnderTest.staticCall(AUTO, "isBusy"))) {
                r.finished = true;
                break;
            }
            if (t % 100 == 99) {
                println(label + " t=" + (t + 1) + ": " + ctx.computeOnClient(mc -> ModUnderTest.staticCall(AUTO, "status")));
            }
        }
        if (!r.finished) {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO, "cancel"));
        }
        PacketWatch.stop();
        r.uses = PacketWatch.itemUses();
        r.interacts = PacketWatch.entityInteracts();
        r.maxUseJump = PacketWatch.maxUseRotationJump();
        for (String l : LogTap.since(mark)) {
            if (l.contains("[AutoClear] hop toward")) {
                r.hops++;
            } else if (l.contains("[AutoClear] etherwarp toward") || l.contains("[AutoClear] trip to room")) {
                r.etherwarps++;
            } else if (l.contains("[AutoClear] server correction")) {
                r.corrections++;
            }
            if (l.contains("[AutoClear] done:") || l.contains("[AutoClear] stopped:")) {
                r.lastLine = l.replaceAll("^.*\\[AutoClear\\] ", "");
            }
        }
        for (String l : LogTap.since(mark)) {
            if (l.contains("[AutoClear]") || l.contains("[Sim] room cleared")) {
                println("  log: " + l.replaceAll("^.*?(\\[AutoClear\\]|\\[Sim\\])", "$1"));
            }
        }
        return r;
    }

    private static void check(List<String> failures, String label, Result r, List<Placed> placed, Map<UUID, String> alive,
                              List<Room> floor, List<String> cleared, List<String> untouched) {
        if (!r.finished) {
            failures.add(label + ": still running after " + CASE_TICKS / 20 + " s");
        }
        if (r.travelled < 5.0) {
            failures.add(label + ": travelled only " + String.format(java.util.Locale.US, "%.1f", r.travelled)
                    + " blocks - nothing moved, so nothing here measured the feature");
        }
        if (r.interacts > 0) {
            failures.add(label + ": " + r.interacts + " entity interaction packet(s) - a melee hit or an entity click");
        }
        if (r.uses == 0) {
            failures.add(label + ": no use-item packet at all, so no ability was used");
        }
        for (Placed p : placed) {
            if (cleared.contains(p.room()) && !"dead".equals(alive.get(p.id()))) {
                failures.add(label + ": the starred mob in " + p.room() + " at " + p.at().toShortString()
                        + (p.inWall() ? " (in a wall)" : "") + " is " + alive.get(p.id()));
            }
            if (untouched.contains(p.room()) && !"alive".equals(alive.get(p.id()))) {
                failures.add(label + ": the starred mob in " + p.room() + ", a room this case must leave alone, is "
                        + alive.get(p.id()));
            }
        }
        for (Room room : floor) {
            if (cleared.contains(room.name()) && !room.cleared()) {
                failures.add(label + ": " + room.name() + " never flipped cleared on the map");
            }
            if (untouched.contains(room.name()) && room.cleared()) {
                failures.add(label + ": " + room.name() + " was cleared, but this case must leave it alone");
            }
        }
    }

    private static void setMode(ClientGameTestContext ctx, String mode, String weapon) {
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(AUTO_CONFIG);
            ModUnderTest.call(cfg, "setMode", new Class<?>[]{ModUnderTest.enumValue(AUTO_CONFIG + "$Mode", mode).getClass()},
                    new Object[]{ModUnderTest.enumValue(AUTO_CONFIG + "$Mode", mode)});
            ModUnderTest.call(cfg, "setWeapon", new Class<?>[]{ModUnderTest.enumValue(AUTO_CONFIG + "$Weapon", weapon).getClass()},
                    new Object[]{ModUnderTest.enumValue(AUTO_CONFIG + "$Weapon", weapon)});
        });
        // Wait on the ground, so the start is not refused mid-air.
        for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> mc.player.onGround()); i++) {
            ctx.waitTicks(1);
        }
    }

    // ------------------------------------------------------------------------------------------------- the world

    @SuppressWarnings("unchecked")
    private static List<Room> floor(ClientGameTestContext ctx) {
        List<String> lines = ctx.computeOnClient(mc -> (List<String>) ModUnderTest.staticCall(AUTO, "floorReport"));
        List<Room> out = new ArrayList<>();
        for (String l : lines) {
            out.add(Room.parse(l));
        }
        return out;
    }

    private static double dist(Vec3 me, Room r) {
        double dx = me.x - r.x();
        double dz = me.z - r.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Places {@code open} starred zombies on the room's floor and, if asked, one standing INSIDE a wall (feet and head in
     * solid blocks) two to four blocks from a floor spot - in reach of a Wither Impact / Guided Bat from that spot.
     */
    private static List<Placed> spawnIn(ClientGameTestContext ctx, Room room, int open, boolean wall) {
        AtomicReference<List<BlockPos[]>> spots = new AtomicReference<>();
        int yHint = ctx.computeOnClient(mc -> ((BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter",
                new Class<?>[]{int.class}, new Object[]{0})).getY());
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                ServerLevel level = server.overworld();
                List<BlockPos[]> out = new ArrayList<>();
                List<BlockPos> floors = floorSpots(level, room.x(), room.z(), yHint, wall ? 16 : 4);
                for (BlockPos f : floors) {
                    if (out.size() < open) {
                        out.add(new BlockPos[]{f, null});
                    }
                }
                if (wall) {
                    for (BlockPos f : floors) {
                        BlockPos w = wallNear(level, f);
                        // Well inside the tile, so the wall is this room's own and not the one it shares with a
                        // neighbour (whose mob the map would file under the other room).
                        if (w != null && Math.abs(w.getX() - room.x()) <= 12 && Math.abs(w.getZ() - room.z()) <= 12) {
                            out.add(new BlockPos[]{w, f});
                            break;
                        }
                    }
                }
                spots.set(out);
            });
        });
        ctx.waitFor(mc -> spots.get() != null, 200);
        List<Placed> placed = new ArrayList<>();
        for (BlockPos[] s : spots.get()) {
            int before = starredPairCount(ctx);
            ctx.runOnClient(mc -> {
                Object k = ModUnderTest.enumValue(SIM_MOBS + "$Kind", "ZOMBIE");
                ModUnderTest.staticCall(SIM_MOBS, "spawnStarred", new Class<?>[]{Minecraft.class, BlockPos.class,
                        k.getClass()}, new Object[]{mc, s[0], k});
            });
            ctx.waitTicks(3);
            UUID id = newestStarred(ctx, before);
            if (id != null) {
                placed.add(new Placed(room.name(), s[1] != null, id, s[0]));
            }
        }
        return placed;
    }

    private static boolean solid(ServerLevel level, BlockPos p) {
        return !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    /** Standable floor spots (solid below, two air) around the room tile's centre, spread out. */
    private static List<BlockPos> floorSpots(ServerLevel level, int cx, int cz, int yHint, int want) {
        List<BlockPos> out = new ArrayList<>();
        for (int ring = 2; ring <= 12 && out.size() < want; ring += 2) {
            for (int dx = -ring; dx <= ring && out.size() < want; dx += 2) {
                for (int dz = -ring; dz <= ring && out.size() < want; dz += 2) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    for (int y = yHint - 6; y <= yHint + 6; y++) {
                        BlockPos feet = new BlockPos(cx + dx, y, cz + dz);
                        if (solid(level, feet.below()) && !solid(level, feet) && !solid(level, feet.above())
                                && !solid(level, feet.above(2))) {
                            boolean far = true;
                            for (BlockPos o : out) {
                                far &= o.distSqr(feet) >= 9;
                            }
                            if (far) {
                                out.add(feet);
                            }
                            break;
                        }
                    }
                }
            }
        }
        return out;
    }

    /** Feet and head both in solid blocks, 2-4 blocks from a floor spot along an axis - a mob inside a wall. */
    private static BlockPos wallNear(ServerLevel level, BlockPos floor) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int d = 1; d <= 4; d++) {
            for (int[] dir : dirs) {
                BlockPos p = floor.offset(dir[0] * d, 0, dir[1] * d);
                if (solid(level, p) && solid(level, p.above())) {
                    return p;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static int starredPairCount(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> ((List<UUID[]>) ModUnderTest.staticCall(SIM_MOBS, "starredPairs")).size());
    }

    @SuppressWarnings("unchecked")
    private static UUID newestStarred(ClientGameTestContext ctx, int before) {
        for (int i = 0; i < 40; i++) {
            List<UUID[]> pairs = ctx.computeOnClient(mc -> (List<UUID[]>) ModUnderTest.staticCall(SIM_MOBS, "starredPairs"));
            if (pairs.size() > before) {
                return pairs.get(pairs.size() - 1)[0];
            }
            ctx.waitTicks(1);
        }
        return null;
    }

    /** "alive", "dead", or "unknown" (not readable: its section is not entity-ticking) for each placed mob. */
    private static Map<UUID, String> aliveReport(ClientGameTestContext ctx, List<Placed> placed) {
        AtomicReference<Map<UUID, String>> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                ServerLevel level = server.overworld();
                Map<UUID, String> m = new LinkedHashMap<>();
                for (Placed p : placed) {
                    Entity e = level.getEntity(p.id());
                    if (e != null) {
                        m.put(p.id(), e.isAlive() ? "alive" : "dead");
                    } else {
                        m.put(p.id(), level.isPositionEntityTicking(p.at()) ? "dead" : "unknown");
                    }
                }
                out.set(m);
            });
        });
        ctx.waitFor(mc -> out.get() != null, 200);
        return out.get();
    }

    private static void awaitFloorChunks(ClientGameTestContext ctx) {
        int missing = -1;
        int waited = 0;
        for (; waited < 1800; waited += 10) {
            missing = ctx.computeOnClient(mc -> {
                BlockPos first = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                        new Object[]{0});
                BlockPos last = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                        new Object[]{GRID * GRID - 1});
                int n = 0;
                for (int cx = (first.getX() - 16) >> 4; cx <= (last.getX() + 16) >> 4; cx++) {
                    for (int cz = (first.getZ() - 16) >> 4; cz <= (last.getZ() + 16) >> 4; cz++) {
                        if (!mc.level.hasChunk(cx, cz)) {
                            n++;
                        }
                    }
                }
                return n;
            });
            if (missing == 0) {
                break;
            }
            ctx.waitTicks(10);
        }
        println("floor chunks on the client: " + (missing == 0 ? "all, after " + waited / 20.0 + " s"
                : missing + " still missing after 90 s"));
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}

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
        // He must stand on a FULL block: from a carpet, slab or stair top the mod's dash model (SimAbilities.dashTarget,
        // the sim server's too) finds no landing at all, so neither the sim nor Auto Clear's planner has a hop from there
        // (2026-10-07: case 3's etherwarp left him on a brown carpet in Three Floors, "straight dash lands null", hops
        // -1). And the room must have an open floor spot 9-16 blocks from him (Silver Sword had none from where case 3
        // left him). Both are set up before the case, by moving him within the room if need be - not during it.
        AtomicReference<BlockPos> near = new AtomicReference<>(placeForHop(ctx, here));
        if (near.get().equals(BlockPos.ZERO)) {
            failures.add("hop: no full block of " + here + " with an open floor spot 9-16 blocks from it to put a mob on");
            return;
        }
        Vec3 eye = ctx.computeOnClient(mc -> mc.player.getEyePosition());
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
        println("hop: before the start - " + hopDiagnosis(ctx, near.get()));
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
        // Another near zombie in the same room, then the SERVER moves him three to five blocks BEHIND him (away from
        // the zombie: a real teleport, as a correction is, and one no Wither Impact hop toward the zombie can land on).
        // It must count the correction, not stop, and still kill the zombie (killer560, 2026-10-06: "Nothing in this
        // mod should stop from server corrections ever"). Twice: (a) a tick after the start, while Auto Clear itself
        // decides, aims or hops - its own check; (b) with hops off, once its etherwarp trip is under way - the
        // Interactive Map runner's check, which Auto Clear must count too. (b) is what failed on 2026-10-07: the move
        // landed while the trip was being planned and nothing called it a correction.
        correctionCase(ctx, failures, "correction", here, true, false);
        correctionCase(ctx, failures, "correction-trip", here, false, false);
        // (c) hops off, the teleport asked for in the same task as the start - the timing of the 2026-10-07 failure,
        // which lands it while the trip is being planned or just after.
        correctionCase(ctx, failures, "correction-plan", here, false, true);
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AUTO_CONFIG), "setHyperionHops", true));

        // ==== case 6: order - rooms off the blood rush before the rooms on it ====
        // killer560 (2026-10-06): "prioritize moving away from blood rush and taking whatever the longest split is ...
        // then, once it eventually needs to go down the blood split ..." The rush room and an off-path room are both
        // un-cleared with a zombie each; Any Mob Room must take the off-path one first.
        Room offRoom = off.get(0);
        List<Placed> p6 = new ArrayList<>();
        for (Room r : List.of(rush, offRoom)) {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "clearRoom", new Class<?>[]{String.class},
                    new Object[]{r.name()}));
            p6.addAll(spawnIn(ctx, r, 1, false));
        }
        ctx.waitTicks(30);
        setMode(ctx, "ANY_MOB_ROOM", "HYPERION");
        long mark6 = LogTap.mark();
        Result r6 = runCase(ctx, "order", () -> ModUnderTest.staticCall(AUTO, "start"));
        Map<UUID, String> after6 = aliveReport(ctx, p6);
        List<String> picks = new ArrayList<>();
        for (String l : LogTap.since(mark6)) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[AutoClear\\] next room (.+?) \\((on|off) the blood rush\\)")
                    .matcher(l);
            if (m.find()) {
                picks.add(m.group(1) + "/" + m.group(2));
            }
        }
        println("order: " + r6 + "; picks " + picks + "; mobs " + after6);
        check(failures, "order", r6, p6, after6, floor(ctx), List.of(rush.name(), offRoom.name()), List.of());
        int offAt = picks.indexOf(offRoom.name() + "/off");
        int rushAt = picks.indexOf(rush.name() + "/on");
        if (offAt < 0 || rushAt < 0 || offAt > rushAt) {
            failures.add("order: the off-path room " + offRoom.name() + " was not taken before the blood-rush room "
                    + rush.name() + " (picks " + picks + ")");
        }

        // ==== case 7: a room only reachable through a wither door ====
        // Every door of the other off-path room becomes a shut wither door (SimDoors.witherDoorsAround: coal in the world,
        // a wither door on the map). With no key it must go to the door and WAIT (not stop, not give up); given a key it
        // must click the door open, go in and clear the room.
        // The room: one whose every door to another room is an ordinary door (so all of them become wither doors and
        // it is really shut off). A room reached only through the Entrance's own door, or with a blood door, has no
        // ordinary door to turn (2026-10-06: Carpets, filled in "through Entrance", got 0 wither doors).
        String standingIn = ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            int cur = (Integer) ModUnderTest.call(layout, "currentRoom", new Class<?>[]{}, new Object[]{});
            return (String) ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{cur});
        });
        List<Room> lockCandidates = new ArrayList<>(List.of(off.get(1), offRoom));
        for (Room r : rooms) {
            if (r.mob() && !lockCandidates.contains(r) && !r.name().equals(rush.name())) {
                lockCandidates.add(r);
            }
        }
        lockCandidates.add(rush);
        Room locked = null;
        List<String> refused = new ArrayList<>();
        for (Room r : lockCandidates) {
            if (r.name().equals(standingIn)) {
                continue;
            }
            String doorsOf = ctx.computeOnClient(mc -> roomDoors(r.name()));
            if (doorsOf.startsWith("ok")) {
                locked = r;
                break;
            }
            refused.add(r.name() + " (" + doorsOf + ")");
        }
        if (locked == null) {
            failures.add("door: no mob room on this floor has only ordinary doors to lock - refused " + refused);
            return;
        }
        Room lockedRoom = locked;
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "clearRoom", new Class<?>[]{String.class},
                new Object[]{lockedRoom.name()}));
        List<Placed> p7 = spawnIn(ctx, lockedRoom, 1, false);
        int doors = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimDoors",
                "witherDoorsAround", new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, lockedRoom.name()}));
        ctx.waitTicks(40);
        boolean shutOff = ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            int cur = (Integer) ModUnderTest.call(layout, "currentRoom", new Class<?>[]{}, new Object[]{});
            int target = -1;
            for (int i = 0; i < (Integer) ModUnderTest.call(layout, "roomCount", new Class<?>[]{}, new Object[]{}); i++) {
                if (lockedRoom.name().equals(ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{i}))) {
                    target = i;
                }
            }
            return target >= 0 && ModUnderTest.staticCall("com.killer560.hub.livemap.autoclear.DungeonMapPathfinder",
                    "findPath", new Class<?>[]{layout.getClass(), int.class, int.class, boolean.class},
                    new Object[]{layout, cur, target, false}) == null;
        });
        println("door: " + doors + " wither door(s) put round " + lockedRoom.name() + " (he is in " + standingIn
                + "; refused first " + refused + "); shut off from him on the map: " + shutOff);
        if (doors <= 0 || !shutOff) {
            failures.add("door: could not shut " + lockedRoom.name() + " off with wither doors (" + doors + " door(s), shut off "
                    + shutOff + ")");
            return;
        }
        long mark7 = LogTap.mark();
        PacketWatch.start();
        ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO, "start"));
        boolean waiting = false;
        for (int t = 0; t < 600 && !waiting; t++) {
            ctx.waitTicks(1);
            for (String l : LogTap.since(mark7)) {
                waiting |= l.contains("with no key - waiting");
            }
        }
        ctx.waitTicks(40);
        boolean busyWithoutKey = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AUTO, "isBusy"));
        println("door: waiting without a key: " + waiting + ", still running 2 s later: " + busyWithoutKey + " - "
                + ctx.computeOnClient(mc -> ModUnderTest.staticCall(AUTO, "status")));
        if (!waiting || !busyWithoutKey) {
            failures.add("door: without a key it did not wait at the door (waiting " + waiting + ", running "
                    + busyWithoutKey + ")");
        }
        AtomicReference<Boolean> keyGiven = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID me = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(me);
                if (sp != null) {
                    sp.getInventory().setItem(3, (ItemStack) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimDoors",
                            "createWitherKey"));
                }
                keyGiven.set(sp != null);
            });
        });
        ctx.waitFor(mc -> keyGiven.get() != null, 100);
        Result r7 = runCase(ctx, "door", () -> { });
        Map<UUID, String> after7 = aliveReport(ctx, p7);
        boolean clicked = false;
        for (String l : LogTap.since(mark7)) {
            clicked |= l.contains("clicked wither door");
        }
        int keysLeft = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimDoors",
                "keysHeld"));
        println("door: " + r7 + "; clicked " + clicked + ", keys left " + keysLeft + "; mob " + after7);
        if (!clicked) {
            failures.add("door: never clicked the wither door with the key");
        }
        if (keysLeft != 0) {
            failures.add("door: the key was not used (" + keysLeft + " left) - the door never opened");
        }
        check(failures, "door", r7, p7, after7, floor(ctx), List.of(lockedRoom.name()), List.of());
    }

    /**
     * A spot for case 4's zombie: an open floor spot of {@code room} 9-16 blocks from him with a clear line from his eye
     * ({@link #openSpots}), while he stands on a full block of the room. Where he stands first; failing that the nearest
     * full block of the room (within 20 blocks) that has one, where he is then put by a server teleport, waiting until
     * the client stands there on the ground. ZERO if none.
     */
    private static BlockPos placeForHop(ClientGameTestContext ctx, String room) {
        Vec3 feet = ctx.computeOnClient(mc -> mc.player.position());
        boolean onFull = ctx.computeOnClient(mc -> {
            BlockPos under = BlockPos.containing(mc.player.getX(), mc.player.getY() - 0.01, mc.player.getZ());
            return mc.player.onGround() && mc.player.getY() == Math.floor(mc.player.getY())
                    && mc.level.getBlockState(under).isCollisionShapeFullBlock(mc.level, under);
        });
        if (onFull) {
            BlockPos here = nearSpotInRoom(ctx, ctx.computeOnClient(mc -> mc.player.getEyePosition()), room);
            if (!here.equals(BlockPos.ZERO)) {
                return here;
            }
        }
        // Standing spots on full blocks round him, nearest first, each with its open spots (server thread).
        AtomicReference<List<BlockPos[]>> found = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID me = mc.player.getUUID();
            server.execute(() -> {
                ServerLevel level = server.overworld();
                var viewer = server.getPlayerList().getPlayer(me);
                List<double[]> scored = new ArrayList<>();
                int fx = (int) Math.floor(feet.x);
                int fy = (int) Math.floor(feet.y);
                int fz = (int) Math.floor(feet.z);
                for (int dx = -20; dx <= 20; dx++) {
                    for (int dz = -20; dz <= 20; dz++) {
                        for (int dy = -3; dy <= 3; dy++) {
                            BlockPos at = new BlockPos(fx + dx, fy + dy, fz + dz);
                            if (fullBlock(level, at.below()) && !solid(level, at) && !solid(level, at.above())
                                    && !solid(level, at.above(2))) {
                                scored.add(new double[]{dx * dx + dz * dz + dy * dy, at.getX(), at.getY(), at.getZ()});
                            }
                        }
                    }
                }
                scored.sort((a, b) -> Double.compare(a[0], b[0]));
                List<BlockPos[]> out = new ArrayList<>();
                for (int i = 0; i < scored.size() && out.size() < 40; i += 3) {   // every third: spread, and bounded
                    double[] d = scored.get(i);
                    BlockPos at = new BlockPos((int) d[1], (int) d[2], (int) d[3]);
                    List<BlockPos> open = openSpots(level, new Vec3(at.getX() + 0.5, at.getY() + 1.62, at.getZ() + 0.5),
                            viewer);
                    List<BlockPos> row = new ArrayList<>();
                    row.add(at);
                    row.addAll(open);
                    out.add(row.toArray(new BlockPos[0]));
                }
                found.set(out);
            });
        });
        ctx.waitFor(mc -> found.get() != null, 400);
        BlockPos[] pick = ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            java.util.function.Predicate<BlockPos> inRoom = p -> room.equals(ModUnderTest.call(layout, "name",
                    new Class<?>[]{int.class}, new Object[]{ModUnderTest.call(layout, "roomAtWorld",
                            new Class<?>[]{double.class, double.class}, new Object[]{p.getX() + 0.5, p.getZ() + 0.5})}));
            for (BlockPos[] row : found.get()) {
                if (!inRoom.test(row[0])) {
                    continue;
                }
                for (int i = 1; i < row.length; i++) {
                    if (inRoom.test(row[i])) {
                        return new BlockPos[]{row[0], row[i]};
                    }
                }
            }
            return null;
        });
        if (pick == null) {
            return BlockPos.ZERO;
        }
        Vec3 target = new Vec3(pick[0].getX() + 0.5, pick[0].getY(), pick[0].getZ() + 0.5);
        String was = ctx.computeOnClient(mc -> {
            BlockPos under = BlockPos.containing(mc.player.getX(), mc.player.getY() - 0.01, mc.player.getZ());
            return String.format(java.util.Locale.US, "%.2f %.2f %.2f on %s", mc.player.getX(), mc.player.getY(),
                    mc.player.getZ(), mc.level.getBlockState(under).getBlock().getDescriptionId());
        });
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID me = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(me);
                if (sp != null) {
                    sp.teleportTo(server.overworld(), target.x, target.y, target.z,
                            java.util.Set.of(net.minecraft.world.entity.Relative.Y_ROT,
                                    net.minecraft.world.entity.Relative.X_ROT), 0f, 0f, false);
                }
            });
        });
        for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> mc.player.onGround()
                && mc.player.position().distanceTo(target) < 0.1); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(5);
        boolean there = ctx.computeOnClient(mc -> mc.player.onGround() && mc.player.position().distanceTo(target) < 0.1);
        println("hop: he stood at " + was + " - put on the full block under " + pick[0].toShortString()
                + (there ? "" : " (NOT there)"));
        return there ? pick[1] : BlockPos.ZERO;
    }

    private static boolean fullBlock(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).isCollisionShapeFullBlock(level, p);
    }

    /**
     * "ok N" when every door of {@code roomName} to another room is an ordinary door (N of them, N >= 1), otherwise why
     * not - the same cells {@code SimDoors.witherDoorsAround} turns. Client thread.
     */
    private static String roomDoors(String roomName) {
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        int count = (Integer) ModUnderTest.call(layout, "roomCount", new Class<?>[]{}, new Object[]{});
        int room = -1;
        for (int i = 0; i < count; i++) {
            if (roomName.equals(ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{i}))) {
                room = i;
            }
        }
        if (room < 0) {
            return "not on the map";
        }
        int normal = 0;
        List<String> other = new ArrayList<>();
        int[][] dirs = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}};
        for (int tile : (int[]) ModUnderTest.call(layout, "tiles", new Class<?>[]{int.class}, new Object[]{room})) {
            int x = tile % GRID;
            int z = tile / GRID;
            for (int[] d : dirs) {
                int nx = x + d[0] * 2;
                int nz = z + d[1] * 2;
                if (nx < 0 || nx >= GRID || nz < 0 || nz >= GRID) {
                    continue;
                }
                int idx = (z + d[1]) * GRID + (x + d[0]);
                int next = (Integer) ModUnderTest.call(layout, "roomOfCell", new Class<?>[]{int.class},
                        new Object[]{nz * GRID + nx});
                int type = (Integer) ModUnderTest.call(layout, "doorType", new Class<?>[]{int.class}, new Object[]{idx});
                if (next < 0 || next == room || type == 0) {
                    continue;
                }
                if (type == 1) {
                    normal++;
                } else {
                    other.add("door type " + type + " at cell " + idx);
                }
            }
        }
        return !other.isEmpty() ? String.join(", ", other) : normal == 0 ? "no door" : "ok " + normal;
    }

    /**
     * What he stands on and what lies between his feet and the mob, block by block, plus the mod's own hop plan for it
     * ({@code WeaponReach.hyperionHopsNeeded}, -1 = no hop) and where its straight dash would land. Client thread.
     */
    private static String hopDiagnosis(ClientGameTestContext ctx, BlockPos mobFeet) {
        return ctx.computeOnClient(mc -> {
            var level = mc.level;
            var player = mc.player;
            Vec3 feet = player.position();
            BlockPos under = BlockPos.containing(feet.x, feet.y - 0.01, feet.z);
            StringBuilder sb = new StringBuilder(String.format(java.util.Locale.US,
                    "feet %.2f %.2f %.2f on %s (%s), onGround %s; mob feet %s", feet.x, feet.y, feet.z,
                    under.toShortString(), level.getBlockState(under).getBlock().getDescriptionId(), player.onGround(),
                    mobFeet.toShortString()));
            List<String> solidOnLine = new ArrayList<>();
            Vec3 to = new Vec3(mobFeet.getX() + 0.5, mobFeet.getY(), mobFeet.getZ() + 0.5);
            int steps = (int) Math.ceil(feet.distanceTo(to) / 0.2);
            java.util.Set<BlockPos> seen = new java.util.HashSet<>();
            for (int i = 0; i <= steps; i++) {
                Vec3 p = feet.lerp(to, i / (double) steps);
                for (int up = 0; up <= 1; up++) {
                    BlockPos b = BlockPos.containing(p.x, p.y + 0.01 + up, p.z);
                    if (seen.add(b) && !level.getBlockState(b).getCollisionShape(level, b).isEmpty()) {
                        solidOnLine.add(b.toShortString() + "=" + level.getBlockState(b).getBlock().getDescriptionId());
                    }
                }
            }
            sb.append("; solid on the feet line ").append(solidOnLine.isEmpty() ? "none" : solidOnLine);
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(mobFeet.getX() + 0.2, mobFeet.getY(),
                    mobFeet.getZ() + 0.2, mobFeet.getX() + 0.8, mobFeet.getY() + 1.95, mobFeet.getZ() + 0.8);
            try {
                Object hops = ModUnderTest.staticCall("com.killer560.hub.autoclear.WeaponReach", "hyperionHopsNeeded",
                        new Class<?>[]{net.minecraft.world.level.Level.class, net.minecraft.world.entity.player.Player.class,
                                Vec3.class, net.minecraft.world.phys.AABB.class, int.class},
                        new Object[]{level, player, feet, box, 4});
                Vec3 eyeNow = player.getEyePosition();
                Vec3 look = box.getCenter().subtract(eyeNow).normalize();
                Object land = ModUnderTest.staticCall("com.killer560.hub.roomsim.SimAbilities", "dashTarget",
                        new Class<?>[]{net.minecraft.world.level.Level.class, net.minecraft.world.entity.player.Player.class,
                                Vec3.class, Vec3.class, double.class},
                        new Object[]{level, player, feet, look, 10.0});
                sb.append("; mod's hop plan ").append(hops).append(" hop(s), straight dash lands ").append(land);
            } catch (RuntimeException | AssertionError e) {
                sb.append("; mod's hop plan unreadable: ").append(e);
            }
            return sb.toString();
        });
    }

    /**
     * One correction case: a starred zombie 9-16 blocks off in {@code here}, clearRoom on it, and a server teleport
     * 3-5 blocks BEHIND him - (hops) a tick after the start, or (no hops: an etherwarp trip) on the first tick the
     * Interactive Map runner is busy with the trip. The teleport must arrive while Auto Clear runs, be counted as a
     * correction, and the zombie must still die with onDone.
     */
    private static void correctionCase(ClientGameTestContext ctx, List<String> failures, String label, String here,
                                       boolean hops, boolean withStart) {
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AUTO_CONFIG), "setHyperionHops", hops));
        // A zombie spot in this room with a floor spot behind him to be moved to (pickCorrection). Where he stands, or,
        // when he stands at the room's edge with nothing of the room behind him (2026-10-07, Scaffolding: 789 pairs,
        // none in the room), from the middle of the room, where he is first put - before the case, not during it.
        BlockPos[] pick = pickCorrection(ctx, here);
        if (pick == null) {
            String moved = toRoomMiddle(ctx, here);
            println(label + ": nothing to pick from where he stands - " + (moved == null ? "put in the middle of " + here
                    : moved));
            pick = moved == null ? pickCorrection(ctx, here) : null;
        }
        if (pick == null) {
            failures.add(label + ": no zombie spot 9-16 blocks off in " + here + " with a floor spot of the room behind him"
                    + " the map can plan from, from where he stood or from the middle of the room");
            return;
        }
        BlockPos mobAt = pick[0];
        BlockPos to = pick[1];
        int before = starredPairCount(ctx);
        ctx.runOnClient(mc -> {
            Object k = ModUnderTest.enumValue(SIM_MOBS + "$Kind", "ZOMBIE");
            ModUnderTest.staticCall(SIM_MOBS, "spawnStarred", new Class<?>[]{Minecraft.class, BlockPos.class,
                    k.getClass()}, new Object[]{mc, mobAt, k});
        });
        ctx.waitTicks(3);
        UUID mob = newestStarred(ctx, before);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "clearRoom", new Class<?>[]{String.class},
                new Object[]{here}));
        ctx.waitTicks(30);
        AtomicReference<String> done = new AtomicReference<>();
        AtomicReference<String> gaveUp = new AtomicReference<>();
        AtomicReference<Boolean> applied = new AtomicReference<>();
        int[] sentAt = {-1};
        int[] arrivedAt = {-1};
        boolean[] runningAtArrival = {false};
        Vec3 target = new Vec3(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
        Runnable teleport = () -> {
            Minecraft mc = Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            UUID me = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(me);
                if (sp != null) {
                    sp.teleportTo(server.overworld(), target.x, target.y, target.z,
                            java.util.Set.of(net.minecraft.world.entity.Relative.Y_ROT,
                                    net.minecraft.world.entity.Relative.X_ROT), 0f, 0f, false);
                }
                applied.set(sp != null);
            });
        };
        long mark = LogTap.mark();
        Result r = runCase(ctx, label, () -> {
            ModUnderTest.staticCall(AUTO, "clearRoom", new Class<?>[]{String.class, Runnable.class, Consumer.class},
                    new Object[]{here, (Runnable) () -> done.set("done"), (Consumer<String>) gaveUp::set});
            if (withStart) {
                sentAt[0] = 0;
                teleport.run();
            }
        }, t -> {
            if (sentAt[0] < 0 && (hops ? t >= 1 : ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                    "com.killer560.hub.livemap.autoclear.ClearExecutor", "isBusy")))) {
                sentAt[0] = t;
                ctx.runOnClient(mc -> teleport.run());
            }
            if (sentAt[0] >= 0 && arrivedAt[0] < 0 && Boolean.TRUE.equals(applied.get())
                    && ctx.computeOnClient(mc -> mc.player.position().distanceTo(target) < 0.5)) {
                arrivedAt[0] = t;
                runningAtArrival[0] = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AUTO, "isBusy"));
            }
        });
        List<Placed> p = mob == null ? List.of() : List.of(new Placed(here, false, mob, mobAt));
        Map<UUID, String> after = aliveReport(ctx, p);
        String caughtBy = "nothing";
        for (String l : LogTap.since(mark)) {
            if (l.contains("[AutoClear] server correction") && l.contains("during the trip")) {
                caughtBy = "the Interactive Map runner";
            } else if (l.contains("[AutoClear] server correction")) {
                caughtBy = "Auto Clear itself";
            }
            if (l.contains("[Path] the server moved you") || l.contains("[Path] off the plan")) {
                println(label + ": runner said " + l.replaceAll("^.*?\\[Path\\]", "[Path]"));
            }
        }
        println(label + ": the correction was caught by " + caughtBy);
        println(label + ": " + r + "; onDone=" + done.get() + " onGiveUp=" + gaveUp.get() + "; mob " + after
                + "; zombie at " + mobAt.toShortString() + ", moved behind him to " + to.toShortString()
                + " - sent on tick " + sentAt[0] + ", seen on the client on tick " + arrivedAt[0]
                + (arrivedAt[0] >= 0 ? (runningAtArrival[0] ? " with Auto Clear running" : " AFTER Auto Clear ended") : ""));
        if (sentAt[0] < 0 || !Boolean.TRUE.equals(applied.get())) {
            failures.add(label + ": the server's teleport was never made (sent on tick " + sentAt[0] + ", applied "
                    + applied.get() + ") - nothing here measured a correction");
            return;
        }
        if (arrivedAt[0] >= 0 && !runningAtArrival[0]) {
            failures.add(label + ": the teleport reached the client only after Auto Clear had ended - nothing here measured"
                    + " a correction");
            return;
        }
        if (r.corrections < 1) {
            failures.add(label + ": the server's teleport was not taken as a correction (no '[AutoClear] server "
                    + "correction' line)");
        }
        if (done.get() == null || gaveUp.get() != null) {
            failures.add(label + ": clearRoom called onDone=" + done.get() + " onGiveUp=" + gaveUp.get()
                    + " - it must carry on after a correction");
        }
        if (p.isEmpty() || !"dead".equals(after.get(mob))) {
            failures.add(label + ": the zombie is " + (p.isEmpty() ? "not placed" : after.get(mob)));
        }
    }

    /**
     * For a correction case, from where he stands: {zombie spot, spot to move him to}, or null. The zombie on an open
     * floor spot of {@code room} 9-16 blocks off ({@link #spotsInRoom}); the move onto a floor spot of the same room
     * beside or behind him ({@link #behindSpots}) - his own floor height first: a spot in a doorway or on a ledge can be
     * one no map path starts from (2026-10-07: moved onto a spot by a door, every later trip found "no way" and the run
     * stalled) - and one the map can plan the trip to the zombie from ({@link #mapCanPlan}): a correction puts him back
     * where he has been, and the case is about what Auto Clear does with one, not about the planner's reach (twice on
     * 2026-10-07 a spot by a wall had "no way" to a zombie 13 blocks off in the same room).
     */
    private static BlockPos[] pickCorrection(ClientGameTestContext ctx, String room) {
        Vec3 eye = ctx.computeOnClient(mc -> mc.player.getEyePosition());
        AtomicReference<List<BlockPos[]>> pairs = new AtomicReference<>();
        List<BlockPos> spots = spotsInRoom(ctx, eye, room);
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                List<BlockPos[]> out = new ArrayList<>();
                for (BlockPos mob : spots) {
                    for (BlockPos behind : behindSpots(server.overworld(), eye, mob)) {
                        out.add(new BlockPos[]{mob, behind});
                    }
                }
                pairs.set(out);
            });
        });
        ctx.waitFor(mc -> pairs.get() != null, 200);
        List<BlockPos[]> inRoom = ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            List<BlockPos[]> out = new ArrayList<>();
            for (BlockPos[] pr : pairs.get()) {
                int r = (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class, double.class},
                        new Object[]{pr[1].getX() + 0.5, pr[1].getZ() + 0.5});
                if (room.equals(ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r}))) {
                    out.add(pr);
                }
            }
            return out;
        });
        int asked = 0;
        for (BlockPos[] pr : inRoom) {
            if (asked++ >= 12) {
                break;
            }
            if (mapCanPlan(ctx, new Vec3(pr[1].getX() + 0.5, pr[1].getY(), pr[1].getZ() + 0.5), pr[0])) {
                return pr;
            }
        }
        println("  (correction spots from " + String.format(java.util.Locale.US, "%.1f %.1f %.1f", eye.x, eye.y, eye.z)
                + ": " + spots.size() + " zombie spot(s), " + pairs.get().size() + " pair(s), " + inRoom.size()
                + " in the room, " + Math.min(asked, 12) + " asked the planner)");
        return null;
    }

    /**
     * Puts him (server teleport, then waits until the client stands there on the ground) on the block the mod's own
     * {@code TeleportUtils.etherwarpableInTile} picks nearest the centre of {@code room}'s first map tile.
     * @return null when he stands there, else why not
     */
    private static String toRoomMiddle(ClientGameTestContext ctx, String room) {
        BlockPos stand = ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            int count = (Integer) ModUnderTest.call(layout, "roomCount", new Class<?>[]{}, new Object[]{});
            for (int i = 0; i < count; i++) {
                if (room.equals(ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{i}))) {
                    int tile = ((int[]) ModUnderTest.call(layout, "tiles", new Class<?>[]{int.class}, new Object[]{i}))[0];
                    BlockPos centre = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                            new Object[]{tile});
                    return (BlockPos) ModUnderTest.staticCall("com.killer560.hub.livemap.autoclear.TeleportUtils",
                            "etherwarpableInTile", new Class<?>[]{BlockPos.class, Vec3.class},
                            new Object[]{centre, Vec3.atCenterOf(centre)});
                }
            }
            return null;
        });
        if (stand == null) {
            return "no standable block in the middle of " + room;
        }
        Vec3 target = new Vec3(stand.getX() + 0.5, stand.getY() + 1, stand.getZ() + 0.5);
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID me = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(me);
                if (sp != null) {
                    sp.teleportTo(server.overworld(), target.x, target.y, target.z,
                            java.util.Set.of(net.minecraft.world.entity.Relative.Y_ROT,
                                    net.minecraft.world.entity.Relative.X_ROT), 0f, 0f, false);
                }
            });
        });
        for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> mc.player.onGround()
                && mc.player.position().distanceTo(target) < 0.1); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(5);
        return ctx.computeOnClient(mc -> mc.player.onGround() && mc.player.position().distanceTo(target) < 0.1)
                ? null : "could not put him on " + stand.toShortString();
    }

    /**
     * Whether the Interactive Map's planner (on its own thread, as every map path is planned) finds a path from
     * {@code feet} to the block Auto Clear would etherwarp onto for a zombie standing at {@code mob}.
     */
    private static boolean mapCanPlan(ClientGameTestContext ctx, Vec3 feet, BlockPos mob) {
        AtomicReference<String> result = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(mob.getX() + 0.2, mob.getY(),
                    mob.getZ() + 0.2, mob.getX() + 0.8, mob.getY() + 1.95, mob.getZ() + 0.8);
            Object weapon = ModUnderTest.enumValue(AUTO_CONFIG + "$Weapon", "HYPERION");
            BlockPos stand = (BlockPos) ModUnderTest.staticCall("com.killer560.hub.autoclear.WeaponReach", "standSpot",
                    new Class<?>[]{net.minecraft.world.level.Level.class, net.minecraft.world.entity.player.Player.class,
                            net.minecraft.world.phys.AABB.class, weapon.getClass()},
                    new Object[]{mc.level, mc.player, box, weapon});
            if (stand == null) {
                result.set("no stand spot");
                return;
            }
            String exec = "com.killer560.hub.livemap.autoclear.ClearExecutor";
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            Object cfg = ModUnderTest.staticCall(exec, "pathConfig");
            double range = (Double) ModUnderTest.staticCall(exec, "hopRange");
            Runnable task = () -> {
                try {
                    Object path = ModUnderTest.staticCall("com.killer560.hub.livemap.autoclear.EtherwarpPathfinder",
                            "findDungeonPath", new Class<?>[]{Vec3.class, BlockPos.class, cfg.getClass(), double.class,
                                    layout.getClass()}, new Object[]{feet, stand, cfg, range, layout});
                    result.set(path != null ? "ok" : "none");
                } catch (RuntimeException | AssertionError e) {
                    result.set("threw " + e);
                }
            };
            ModUnderTest.staticCall(exec, "onPlanner", new Class<?>[]{Runnable.class}, new Object[]{task});
        });
        for (int i = 0; i < 400 && result.get() == null; i++) {
            ctx.waitTicks(1);
        }
        if (!"ok".equals(result.get())) {
            println("  (no map path from " + String.format(java.util.Locale.US, "%.1f %.1f %.1f", feet.x, feet.y, feet.z)
                    + " for a zombie at " + mob.toShortString() + ": " + result.get() + ")");
        }
        return "ok".equals(result.get());
    }

    /**
     * Standable floor spots 3-5 blocks from {@code eye}'s feet and at least 90 degrees round from the direction of
     * {@code mob}: beside or behind him. A Wither Impact hop toward the mob looks within 40 degrees of it, so such a spot
     * is at least 50 degrees off any hop's look and 3+ blocks out, over 2.3 blocks from its line - outside Auto Clear's
     * own-landing band (1.6 blocks). His own floor height first, then a block up or down; the most directly behind first.
     */
    private static List<BlockPos> behindSpots(ServerLevel level, Vec3 eye, BlockPos mob) {
        int ex = (int) Math.floor(eye.x);
        int ez = (int) Math.floor(eye.z);
        int fy = (int) Math.floor(eye.y - 1.62);
        double mx = mob.getX() + 0.5 - eye.x;
        double mz = mob.getZ() + 0.5 - eye.z;
        double ml = Math.max(1e-6, Math.sqrt(mx * mx + mz * mz));
        List<double[]> scored = new ArrayList<>();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                double r = Math.sqrt(dx * dx + dz * dz);
                if (r < 3 || r > 5) {
                    continue;
                }
                double dot = (dx * mx + dz * mz) / (r * ml);
                if (dot > 0.0) {   // 90 degrees round or more
                    continue;
                }
                for (int dy : new int[]{0, -1, 1}) {
                    BlockPos feet = new BlockPos(ex + dx, fy + dy, ez + dz);
                    if (solid(level, feet.below()) && !solid(level, feet) && !solid(level, feet.above())
                            && !solid(level, feet.above(2))) {
                        scored.add(new double[]{Math.abs(dy) * 10 + dot, feet.getX(), feet.getY(), feet.getZ()});
                        break;
                    }
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

    /**
     * {@link #openSpots} filtered on the client to the ones the live map puts in {@code room} - a spot 12 blocks off can
     * be in the next room, whose mob would never clear this one (2026-10-06's first correction run: "already cleared").
     */
    private static BlockPos nearSpotInRoom(ClientGameTestContext ctx, Vec3 eye, String room) {
        List<BlockPos> in = spotsInRoom(ctx, eye, room);
        return in.isEmpty() ? BlockPos.ZERO : in.get(0);
    }

    /** Every {@link #openSpots} spot the live map puts in {@code room}, nearest 12 blocks first. */
    private static List<BlockPos> spotsInRoom(ClientGameTestContext ctx, Vec3 eye, String room) {
        AtomicReference<List<BlockPos>> spots = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID me = mc.player.getUUID();
            server.execute(() -> spots.set(openSpots(server.overworld(), eye, server.getPlayerList().getPlayer(me))));
        });
        ctx.waitFor(mc -> spots.get() != null, 200);
        return ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            List<BlockPos> in = new ArrayList<>();
            for (BlockPos p : spots.get()) {
                int r = (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class, double.class},
                        new Object[]{p.getX() + 0.5, p.getZ() + 0.5});
                String name = (String) ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r});
                if (room.equals(name)) {
                    in.add(p);
                }
            }
            return in;
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
        return runCase(ctx, label, start, null);
    }

    /** @param perTick called on the test thread after each tick, before the "still running?" check, with the tick. */
    private static Result runCase(ClientGameTestContext ctx, String label, Runnable start,
                                  java.util.function.IntConsumer perTick) {
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
            if (perTick != null) {
                perTick.accept(t + 1);
            }
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

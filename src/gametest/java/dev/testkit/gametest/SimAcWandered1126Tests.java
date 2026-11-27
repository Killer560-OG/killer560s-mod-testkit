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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * <b>1126-sim-ac-wandered</b> (mod wandered-mob-1126, killer560 2026-11-26: "a mob has wandered out of leaves into quiz
 * but it cannot find the mob to clear the room"). His run: a starred mob of Leaves had chased him into the Quiz, Auto
 * Clear first saw it there (a puzzle, so it counted for the Quiz), roamed Leaves and gave it up with the mob in sight.
 * Three parts on one F7 sim floor, each on its own mob room B with an open door to a neighbor A:
 * <ul>
 *   <li><b>cleared</b> - A is a cleared mob room or no mob room; B's only starred zombie stands 5 blocks past the door
 *       in A from before {@code clearRoom(B)} starts and never comes back. Asserted: the zombie dies and B's clear ends
 *       (onDone), with use packets sent. The origin jar gives B up with the zombie alive.</li>
 *   <li><b>known</b> - A is an uncleared mob room (its own zombie deep in it); B's zombie is seen in B by the running
 *       clear and moved 5 blocks into A at tick 2. Asserted: B's zombie dies, B finishes, A's own zombie is alive.</li>
 *   <li><b>guard</b> - A is an uncleared mob room whose own zombie stands 5 blocks past the door; B's zombie was moved
 *       20 blocks into A before the clear (nothing on the client says it is B's). Asserted: A's zombie is not taken
 *       (alive), B is given up, and the give-up line says a mob may have left the room.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimAcWandered1126Tests implements FabricClientGameTest {

    private static final String NAME = "1126-sim-ac-wandered";
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

    /** One room B and its door into neighbor A: floor spots in B by the door, and 5 and 20 blocks past it in A. */
    private record Pair(String b, String a, boolean aMob, BlockPos in, BlockPos out, BlockPos deep) {
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
        // Every mob room cleared first; each part un-clears the rooms it uses.
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
        Set<String> used = new java.util.HashSet<>();
        if (standing != null) {
            used.add(standing);
        }
        Pair cleared = pick(ctx, mobRooms, used, false);
        if (cleared == null) {
            failures.add("setup: no mob room with an open door and floor 5 blocks past it");
            return;
        }
        clearedPart(ctx, failures, cleared);
        Pair known = pick(ctx, mobRooms, used, true);
        if (known == null) {
            failures.add("setup: no second mob room whose open door leads into another mob room");
            return;
        }
        knownPart(ctx, failures, known);
        Pair guard = pick(ctx, mobRooms, used, true);
        if (guard == null) {
            failures.add("setup: no third mob room whose open door leads into another mob room");
            return;
        }
        guardPart(ctx, failures, guard);
    }

    private static void clearedPart(ClientGameTestContext ctx, List<String> failures, Pair p) {
        UUID[] zombie = spawn(ctx, p.b(), p.in());
        if (zombie == null) {
            failures.add("cleared: could not spawn the zombie in " + p.b());
            return;
        }
        ctx.waitTicks(20);
        SimAutoClear1020Tests.moveMob(ctx, zombie, Vec3.atBottomCenterOf(p.out()));   // it walked out before the clear
        ctx.waitTicks(10);
        boolean aCleared = isCleared(ctx, p.a());
        println(String.format(Locale.US, "cleared: zombie of %s moved to %s in %s (mob room %s, cleared %s)", p.b(),
                p.out().toShortString(), p.a(), p.aMob(), aCleared));
        if (p.aMob() && !aCleared) {
            failures.add("cleared: setup: " + p.a() + " is an uncleared mob room, so the part proves nothing");
        }
        Outcome o = clear(ctx, p.b(), t -> { });
        Map<UUID, String> alive = SimAutoClearTests.aliveReport(ctx, List.of(new SimAutoClearTests.Placed(p.b(), false,
                zombie[0], p.in())));
        String line = "cleared (" + p.b() + " -> " + p.a() + "): zombie " + alive.get(zombie[0]) + ", " + o;
        println(line);
        if (!"dead".equals(alive.get(zombie[0])) || o.done == null) {
            failures.add("cleared: the zombie that wandered out of " + p.b() + " was not killed: " + line);
        }
        if (o.r.uses == 0) {
            failures.add("cleared: Auto Clear sent no use packet: " + line);
        }
        if (!o.took) {
            failures.add("cleared: no 'counts as " + p.b() + "'s mob (wandered out)' line: " + line);
        }
    }

    private static void knownPart(ClientGameTestContext ctx, List<String> failures, Pair p) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "clearRoom", new Class<?>[]{String.class},
                new Object[]{p.a()}));
        UUID[] own = spawn(ctx, p.a(), p.deep());   // A's own: keeps A uncleared
        UUID[] zombie = spawn(ctx, p.b(), p.in());
        if (own == null || zombie == null) {
            failures.add("known: could not spawn the zombies in " + p.a() + " / " + p.b());
            return;
        }
        ctx.waitTicks(20);
        boolean aCleared = isCleared(ctx, p.a());
        println(String.format(Locale.US, "known: zombie of %s at %s moves to %s in %s (uncleared %s) at tick 2", p.b(),
                p.in().toShortString(), p.out().toShortString(), p.a(), !aCleared));
        if (aCleared) {
            failures.add("known: setup: " + p.a() + " reads cleared");
        }
        Outcome o = clear(ctx, p.b(), t -> {
            if (t == 2) {
                SimAutoClear1020Tests.moveMob(ctx, zombie, Vec3.atBottomCenterOf(p.out()));
            }
        });
        Map<UUID, String> alive = SimAutoClearTests.aliveReport(ctx, List.of(
                new SimAutoClearTests.Placed(p.b(), false, zombie[0], p.in()),
                new SimAutoClearTests.Placed(p.a(), false, own[0], p.deep())));
        String line = "known (" + p.b() + " -> " + p.a() + "): zombie " + alive.get(zombie[0]) + ", " + p.a()
                + "'s own " + alive.get(own[0]) + ", " + o;
        println(line);
        if (!"dead".equals(alive.get(zombie[0])) || o.done == null) {
            failures.add("known: " + p.b() + "'s zombie seen there and then in " + p.a() + " was not killed: " + line);
        }
        if ("dead".equals(alive.get(own[0]))) {
            failures.add("known: " + p.a() + "'s own zombie was killed by " + p.b() + "'s clear: " + line);
        }
        if (o.r.uses == 0) {
            failures.add("known: Auto Clear sent no use packet: " + line);
        }
        discard(ctx, own);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "markCleared", new Class<?>[]{String.class},
                new Object[]{p.a()}));
    }

    private static void guardPart(ClientGameTestContext ctx, List<String> failures, Pair p) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "clearRoom", new Class<?>[]{String.class},
                new Object[]{p.a()}));
        UUID[] own = spawn(ctx, p.a(), p.out());    // A's own, by the door
        UUID[] zombie = spawn(ctx, p.b(), p.in());
        if (own == null || zombie == null) {
            failures.add("guard: could not spawn the zombies in " + p.a() + " / " + p.b());
            return;
        }
        ctx.waitTicks(20);
        SimAutoClear1020Tests.moveMob(ctx, zombie, Vec3.atBottomCenterOf(p.deep()));
        ctx.waitTicks(10);
        println(String.format(Locale.US, "guard: %s's own zombie at %s by the door; %s's zombie moved to %s deep in %s",
                p.a(), p.out().toShortString(), p.b(), p.deep().toShortString(), p.a()));
        Outcome o = clear(ctx, p.b(), t -> { });
        Map<UUID, String> alive = SimAutoClearTests.aliveReport(ctx, List.of(
                new SimAutoClearTests.Placed(p.a(), false, own[0], p.out()),
                new SimAutoClearTests.Placed(p.b(), false, zombie[0], p.deep())));
        String line = "guard (" + p.b() + " -> " + p.a() + "): " + p.a() + "'s own " + alive.get(own[0]) + ", "
                + p.b() + "'s " + alive.get(zombie[0]) + ", " + o;
        println(line);
        if ("dead".equals(alive.get(own[0]))) {
            failures.add("guard: " + p.a() + "'s own zombie by the door was taken for " + p.b() + ": " + line);
        }
        if (o.took) {
            failures.add("guard: a mob of the uncleared " + p.a() + " was counted for " + p.b() + ": " + line);
        }
        if (o.done != null || o.gaveUp == null || !o.gaveUp.contains("a mob may have left the room")) {
            failures.add("guard: " + p.b() + " was not given up with 'a mob may have left the room': " + line);
        }
    }

    /** What one clearRoom did. */
    private static final class Outcome {
        SimAutoClearTests.Result r;
        String done;
        String gaveUp;
        boolean took;

        @Override
        public String toString() {
            return String.format(Locale.US, "onDone=%s onGiveUp=%s, %d tick(s), uses %d, adopted %s", done, gaveUp, r.ticks,
                    r.uses, took);
        }
    }

    private static Outcome clear(ClientGameTestContext ctx, String room, java.util.function.IntConsumer perTick) {
        Outcome o = new Outcome();
        AtomicReference<String> done = new AtomicReference<>();
        AtomicReference<String> gaveUp = new AtomicReference<>();
        long mark = LogTap.mark();
        o.r = SimAutoClear1020Tests.runCaseWith(ctx, "wandered " + room, () -> ModUnderTest.staticCall(AUTO, "clearRoom",
                new Class<?>[]{String.class, Runnable.class, Consumer.class},
                new Object[]{room, (Runnable) () -> done.set("done"), (Consumer<String>) gaveUp::set}), perTick);
        o.done = done.get();
        o.gaveUp = gaveUp.get();
        for (String l : LogTap.since(mark)) {
            o.took |= l.contains("counts as " + room + "'s mob (wandered out)");
        }
        return o;
    }

    private static UUID[] spawn(ClientGameTestContext ctx, String room, BlockPos at) {
        int pairs = SimAutoClear1020Tests.starredPairCount(ctx);
        ctx.runOnClient(mc -> {
            Object k = ModUnderTest.enumValue(SIM_MOBS + "$Kind", "ZOMBIE");
            ModUnderTest.staticCall(SIM_MOBS, "spawnStarred", new Class<?>[]{Minecraft.class, BlockPos.class,
                    k.getClass()}, new Object[]{mc, at, k});
        });
        ctx.waitTicks(3);
        return SimAutoClear1020Tests.newestPair(ctx, pairs);
    }

    private static void discard(ClientGameTestContext ctx, UUID[] pair) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                for (UUID id : pair) {
                    var e = id == null ? null : server.overworld().getEntity(id);
                    if (e != null) {
                        e.discard();
                    }
                }
            });
        });
        ctx.waitTicks(5);
    }

    private static boolean isCleared(ClientGameTestContext ctx, String room) {
        return ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_ROOM_STATE, "isCleared",
                new Class<?>[]{String.class}, new Object[]{room}));
    }

    /** The next unused mob room B (not where he stands) with an open ordinary door into a room A (a mob room when asked). */
    private static Pair pick(ClientGameTestContext ctx, List<SimAutoClearTests.Room> mobRooms, Set<String> used,
                             boolean aMustBeMob) {
        for (SimAutoClearTests.Room room : mobRooms) {
            if (used.contains(room.name())) {
                continue;
            }
            List<double[]> doors = ctx.computeOnClient(mc -> crossings(room.name()));
            for (double[] c : doors) {
                AtomicReference<BlockPos[]> spots = new AtomicReference<>();
                ctx.runOnClient(mc -> {
                    var server = mc.getSingleplayerServer();
                    server.execute(() -> spots.set(new BlockPos[]{
                            SimAutoClear1020Tests.floorNear(server.overworld(), c[3], c[4], (int) c[2], 0, 0),
                            SimAutoClear1020Tests.floorNear(server.overworld(), c[0], c[1], (int) c[2], 0, 0),
                            SimAutoClear1020Tests.floorNear(server.overworld(), c[5], c[6], (int) c[2], 0, 0)}));
                });
                ctx.waitFor(mc -> spots.get() != null, 200);
                BlockPos in = spots.get()[0];
                BlockPos out = spots.get()[1];
                BlockPos deep = spots.get()[2];
                if (in == null || out == null || deep == null) {
                    continue;
                }
                String a = ctx.computeOnClient(mc -> SimAutoClear1020Tests.roomNameAt(out.getX() + 0.5, out.getZ() + 0.5));
                String aDeep = ctx.computeOnClient(mc -> SimAutoClear1020Tests.roomNameAt(deep.getX() + 0.5,
                        deep.getZ() + 0.5));
                String b = ctx.computeOnClient(mc -> SimAutoClear1020Tests.roomNameAt(in.getX() + 0.5, in.getZ() + 0.5));
                boolean aMob = mobRooms.stream().anyMatch(m -> m.name().equals(a));
                if (a == null || a.equals(room.name()) || !a.equals(aDeep) || !room.name().equals(b) || used.contains(a)
                        || aMustBeMob && !aMob) {
                    continue;
                }
                used.add(room.name());
                used.add(a);
                ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "clearRoom", new Class<?>[]{String.class},
                        new Object[]{room.name()}));
                println("picked " + room.name() + " -> " + a + " (mob room " + aMob + "): in " + in.toShortString()
                        + ", out " + out.toShortString() + ", deep " + deep.toShortString());
                return new Pair(room.name(), a, aMob, in, out, deep);
            }
        }
        return null;
    }

    /**
     * Each open ordinary door of {@code roomName} into another room: {outX, outZ, y, inX, inZ, deepX, deepZ} - 5 blocks
     * past the door, 4 inside it, and 20 past it.
     */
    private static List<double[]> crossings(String roomName) {
        List<double[]> out = new ArrayList<>();
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        int room = SimAutoClear1020Tests.roomIndex(layout, roomName);
        if (room < 0) {
            return out;
        }
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int tile : (int[]) ModUnderTest.call(layout, "tiles", new Class<?>[]{int.class}, new Object[]{room})) {
            int x = tile % GRID;
            int z = tile / GRID;
            BlockPos c = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                    new Object[]{tile});
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
                boolean locked = (Boolean) ModUnderTest.call(layout, "isLocked", new Class<?>[]{int.class},
                        new Object[]{idx});
                if (next < 0 || next == room || type != 1 || locked) {
                    continue;
                }
                double cx = c.getX() + 0.5;
                double cz = c.getZ() + 0.5;
                out.add(new double[]{cx + d[0] * 21.0, cz + d[1] * 21.0, c.getY(), cx + d[0] * 12.0, cz + d[1] * 12.0,
                        cx + d[0] * 36.0, cz + d[1] * 36.0});
            }
        }
        return out;
    }

    static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}

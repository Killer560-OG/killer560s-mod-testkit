package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
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
 * <b>131e-sim-ac-1020</b> (mod ac-1020, killer560 2026-10-20) - Auto Clear's four asks and the wither door, each measured
 * the same way on the old jar and the new so the two can be compared on one floor ({@code -PacCode=<map code>} replays
 * the floor a first run printed as "MAP CODE"):
 * <ol>
 *   <li><b>edge</b> - a starred zombie in room E, moved by the SERVER 0.7 blocks over E's edge into the next room two
 *       ticks after {@code clearRoom(E)} starts (a mob walking out by a hair). The sim still files it under E (where it
 *       spawned), so E clears only when it dies. Reported: whether it died, onDone/onGiveUp, roam lines, ticks.</li>
 *   <li><b>onetap</b> - five 1-HP starred zombies spread over room O at least 8 blocks apart, Spirit Sceptre. Reported:
 *       ticks to the clear, use packets, one-tap log lines.</li>
 *   <li><b>predict</b> - a starred zombie the SERVER walks back and forth at 0.25 blocks a tick, Spirit Sceptre.
 *       Reported: ticks to the kill and use packets (bats sent).</li>
 *   <li><b>door</b> - a room shut off by wither doors, the key in the hotbar before the start. Reported: ticks from the
 *       start to the door click, to the key being taken, and to the room clear.</li>
 * </ol>
 * A case that cannot be set up on the floor is reported as such. Verdicts are printed, not asserted, except that the new
 * jar must kill the edge mob and open the door.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimAutoClear1020Tests implements FabricClientGameTest {

    private static final String NAME = "131e-sim-ac-1020";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_MOBS = "com.killer560.hub.roomsim.SimMobs";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String SIM_ROOM_STATE = "com.killer560.hub.roomsim.SimRoomState";
    private static final String SIM_DOORS = "com.killer560.hub.roomsim.SimDoors";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    private static final String AUTO = "com.killer560.hub.autoclear.AutoClearFeature";
    private static final String AUTO_CONFIG = "com.killer560.hub.autoclear.AutoClearConfig";
    private static final int GRID = 11;

    private static final List<String> SUMMARY = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
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
        for (String s : SUMMARY) {
            println("SUMMARY " + s);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
        System.out.println("[" + NAME + "] PASS");
    }

    private static boolean newJar() {
        try {
            ModUnderTest.call(ModUnderTest.config(AUTO_CONFIG), "isPredictiveBats", new Class<?>[]{}, new Object[]{});
            return true;
        } catch (RuntimeException | Error e) {
            return false;
        }
    }

    private static void body(ClientGameTestContext ctx, List<String> failures) {
        boolean isNew = ctx.computeOnClient(mc -> newJar());
        println("jar: " + (isNew ? "NEW (has Predictive Bats / One-Tap Warp)" : "OLD"));
        // ---- the floor -------------------------------------------------------------------------------------------
        String code = System.getProperty("testkit.acCode", "");
        long before = Scenario.simBuildCount(ctx);
        if (code.isBlank()) {
            ctx.runOnClient(mc -> mc.execute(() -> {
                Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
                ModUnderTest.staticCall(FLOOR_GEN, "generate",
                        new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                        new Object[]{mc, floor, 3, 4});
            }));
        } else {
            ctx.runOnClient(mc -> mc.execute(() -> {
                ModUnderTest.staticCall(SIM_STATE, "setFloorLabel", new Class<?>[]{String.class}, new Object[]{"F7"});
                Consumer<Minecraft> build = c -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "build",
                        new Class<?>[]{Minecraft.class, String.class}, new Object[]{c, code});
                ModUnderTest.staticCall("com.killer560.hub.roomsim.SimWorld", "open", new Class<?>[]{Minecraft.class,
                        String.class, Consumer.class, String.class}, new Object[]{mc, code, build, "Generating Floor 7"});
            }));
        }
        ctx.waitFor(mc -> mc.level != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitTicks(80);
        SimAutoClearTests.awaitFloorChunks(ctx);
        String mapCode = ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(SIM_STATE, "mapCode")));
        println("MAP CODE: " + mapCode);
        SUMMARY.add("jar " + (isNew ? "NEW" : "OLD") + ", map code " + mapCode);

        // ---- loadout and the run ------------------------------------------------------------------------------------
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
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
        for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomsim.SimRun", "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(20);

        List<SimAutoClearTests.Room> rooms = SimAutoClearTests.floor(ctx);
        Vec3 me = ctx.computeOnClient(mc -> mc.player.position());
        List<SimAutoClearTests.Room> mobRooms = new ArrayList<>();
        for (SimAutoClearTests.Room r : rooms) {
            if (r.mob()) {
                mobRooms.add(r);
            }
        }
        mobRooms.sort((a, b) -> Double.compare(SimAutoClearTests.dist(me, a), SimAutoClearTests.dist(me, b)));
        // Every mob room cleared first; each case un-clears its own.
        for (SimAutoClearTests.Room r : mobRooms) {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "markCleared", new Class<?>[]{String.class},
                    new Object[]{r.name()}));
        }
        ctx.waitTicks(5);
        println("mob rooms by distance: " + mobRooms.stream().map(SimAutoClearTests.Room::name).toList());
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(AUTO_CONFIG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setHyperionHops", true);
        });

        List<String> used = new ArrayList<>();
        edgeCase(ctx, failures, mobRooms, used, isNew);
        oneTapCase(ctx, mobRooms, used);
        predictCase(ctx, mobRooms, used);
        doorCase(ctx, failures, rooms, mobRooms, used, isNew);
    }

    // ------------------------------------------------------------------------------------------------ edge

    private static void edgeCase(ClientGameTestContext ctx, List<String> failures, List<SimAutoClearTests.Room> mobRooms,
                                 List<String> used, boolean isNew) {
        SimAutoClearTests.setMode(ctx, "ANY_MOB_ROOM", "HYPERION");
        String standing = standingIn(ctx);
        for (SimAutoClearTests.Room room : mobRooms) {
            if (room.name().equals(standing) || used.contains(room.name())) {
                continue;
            }
            // An edge point 0.7 past one of the room's tiles into ANOTHER room, and a floor spot inside the room near it.
            double[] edge = ctx.computeOnClient(mc -> edgePoint(room.name()));
            if (edge == null) {
                continue;
            }
            AtomicReference<BlockPos> spot = new AtomicReference<>();
            AtomicReference<Boolean> searched = new AtomicReference<>();
            ctx.runOnClient(mc -> {
                var server = mc.getSingleplayerServer();
                server.execute(() -> {
                    spot.set(floorNear(server.overworld(), edge[3], edge[4], (int) edge[5], edge[0], edge[2]));
                    searched.set(true);
                });
            });
            ctx.waitFor(mc -> searched.get() != null, 200);
            if (spot.get() == null) {
                continue;
            }
            used.add(room.name());
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "clearRoom", new Class<?>[]{String.class},
                    new Object[]{room.name()}));
            int pairs = starredPairCount(ctx);
            BlockPos at = spot.get();
            ctx.runOnClient(mc -> {
                Object k = ModUnderTest.enumValue(SIM_MOBS + "$Kind", "ZOMBIE");
                ModUnderTest.staticCall(SIM_MOBS, "spawnStarred", new Class<?>[]{Minecraft.class, BlockPos.class,
                        k.getClass()}, new Object[]{mc, at, k});
            });
            ctx.waitTicks(3);
            UUID[] pair = newestPair(ctx, pairs);
            if (pair == null) {
                SUMMARY.add("edge: could not spawn the zombie in " + room.name());
                return;
            }
            ctx.waitTicks(30);
            Vec3 target = new Vec3(edge[0], at.getY(), edge[2]);
            String roomThere = ctx.computeOnClient(mc -> roomNameAt(target.x, target.z));
            println(String.format(Locale.US, "edge: zombie at %s in %s; moved %d ticks after the start to %.2f %.2f %.2f"
                    + " (%.2f outside %s, in %s)", at.toShortString(), room.name(), 2, target.x, target.y, target.z,
                    edge[6], room.name(), roomThere));
            AtomicReference<String> done = new AtomicReference<>();
            AtomicReference<String> gaveUp = new AtomicReference<>();
            long mark = LogTap.mark();
            int[] t0 = {0};
            SimAutoClearTests.Result r = runCaseWith(ctx, "edge", () -> ModUnderTest.staticCall(AUTO, "clearRoom",
                    new Class<?>[]{String.class, Runnable.class, Consumer.class},
                    new Object[]{room.name(), (Runnable) () -> done.set("done"), (Consumer<String>) gaveUp::set}), t -> {
                if (t == 2) {
                    moveMob(ctx, pair, target);
                }
            });
            Map<UUID, String> alive = SimAutoClearTests.aliveReport(ctx, List.of(new SimAutoClearTests.Placed(room.name(),
                    false, pair[0], at)));
            int roams = 0;
            int edgeKept = 0;
            for (String l : LogTap.since(mark)) {
                roams += l.contains("[AutoClear] roam") && !l.contains("candidate spot") ? 1 : 0;
                edgeKept += l.contains("still counted as its mob") ? 1 : 0;
            }
            String line = String.format(Locale.US, "edge (%s): mob %s, onDone=%s onGiveUp=%s, %d tick(s), %d roam line(s),"
                            + " edge rule %d, uses %d", room.name(), alive.get(pair[0]), done.get(), gaveUp.get(), r.ticks,
                    roams, edgeKept, r.uses);
            println(line);
            SUMMARY.add(line);
            if (isNew && !"dead".equals(alive.get(pair[0]))) {
                failures.add("edge: the zombie walked out by a hair is " + alive.get(pair[0]));
            }
            // Whatever happened, the room is out of the later cases (a jar that gave it up would go back for it).
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "markCleared", new Class<?>[]{String.class},
                    new Object[]{room.name()}));
            ctx.waitTicks(5);
            return;
        }
        SUMMARY.add("edge: no mob room with an edge into another room and a floor spot by it");
    }

    /** {edgeX, y, edgeZ, tileCentreX, tileCentreZ, y hint, outside} - 0.7 past a tile of {@code room} into another room. */
    private static double[] edgePoint(String roomName) {
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        int room = roomIndex(layout, roomName);
        if (room < 0) {
            return null;
        }
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int tile : (int[]) ModUnderTest.call(layout, "tiles", new Class<?>[]{int.class}, new Object[]{room})) {
            BlockPos c = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                    new Object[]{tile});
            for (int[] d : dirs) {
                double x = c.getX() + 0.5 + d[0] * 16.7;
                double z = c.getZ() + 0.5 + d[1] * 16.7;
                int there = (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class, double.class},
                        new Object[]{x, z});
                if (there >= 0 && there != room) {
                    // Not another tile of the same room either (an L's own tiles are the room).
                    return new double[]{x, c.getY(), z, c.getX() + 0.5 + d[0] * 13.0, c.getZ() + 0.5 + d[1] * 13.0,
                            c.getY(), 0.7};
                }
            }
        }
        return null;
    }

    /** A standable floor spot nearest (x, z) within 3 blocks, y within 6 of the hint, no further from the edge than (ex, ez). */
    private static BlockPos floorNear(ServerLevel level, double x, double z, int yHint, double ex, double ez) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int y = yHint - 6; y <= yHint + 6; y++) {
                    BlockPos feet = BlockPos.containing(x + dx, y, z + dz);
                    if (solid(level, feet.below()) && !solid(level, feet) && !solid(level, feet.above())
                            && !solid(level, feet.above(2))) {
                        double d = Math.abs(dx) + Math.abs(dz) + Math.abs(y - yHint) * 0.1;
                        if (d < bestD) {
                            bestD = d;
                            best = feet;
                        }
                        break;
                    }
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------------ one tap

    private static void oneTapCase(ClientGameTestContext ctx, List<SimAutoClearTests.Room> mobRooms, List<String> used) {
        SimAutoClearTests.setMode(ctx, "ANY_MOB_ROOM", "SPIRIT_SCEPTRE");
        String standing = standingIn(ctx);
        for (SimAutoClearTests.Room room : mobRooms) {
            if (room.name().equals(standing) || used.contains(room.name())) {
                continue;
            }
            List<BlockPos> spots = spreadSpots(ctx, room, 5, 8.0);
            if (spots.size() < 4) {
                continue;
            }
            used.add(room.name());
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "clearRoom", new Class<?>[]{String.class},
                    new Object[]{room.name()}));
            List<SimAutoClearTests.Placed> placed = spawnAt(ctx, room.name(), spots);
            ctx.waitTicks(30);
            AtomicReference<String> done = new AtomicReference<>();
            AtomicReference<String> gaveUp = new AtomicReference<>();
            long mark = LogTap.mark();
            int movesBefore = oneTapMoves(ctx);
            SimAutoClearTests.Result r = SimAutoClearTests.runCase(ctx, "onetap", () -> ModUnderTest.staticCall(AUTO,
                    "clearRoom", new Class<?>[]{String.class, Runnable.class, Consumer.class},
                    new Object[]{room.name(), (Runnable) () -> done.set("done"), (Consumer<String>) gaveUp::set}));
            Map<UUID, String> alive = SimAutoClearTests.aliveReport(ctx, placed);
            int trusted = 0;
            int survived = 0;
            for (String l : LogTap.since(mark)) {
                trusted += l.contains("[AutoClear] one-tap:") && l.contains("trusting the bat") ? 1 : 0;
                survived += l.contains("still alive") && l.contains("one-tap") ? 1 : 0;
            }
            long dead = alive.values().stream().filter("dead"::equals).count();
            String line = String.format(Locale.US, "onetap (%s): %d/%d mobs dead, onDone=%s onGiveUp=%s, %d tick(s), uses %d,"
                            + " trusted %d, survivors %d, moves on %d, traveled %.1f", room.name(), dead, placed.size(),
                    done.get(), gaveUp.get(), r.ticks, r.uses, trusted, survived, oneTapMoves(ctx) - movesBefore,
                    r.travelled);
            println(line);
            SUMMARY.add(line);
            return;
        }
        SUMMARY.add("onetap: no mob room with 4 open floor spots 8 blocks apart");
    }

    private static int oneTapMoves(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            try {
                return (Integer) ModUnderTest.staticCall(AUTO, "oneTapMoves");
            } catch (RuntimeException | Error e) {
                return 0;
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ predict

    private static void predictCase(ClientGameTestContext ctx, List<SimAutoClearTests.Room> mobRooms, List<String> used) {
        SimAutoClearTests.setMode(ctx, "ANY_MOB_ROOM", "SPIRIT_SCEPTRE");
        String standing = standingIn(ctx);
        int tried = 0;
        List<String> lines = new ArrayList<>();
        for (SimAutoClearTests.Room room : mobRooms) {
            if (room.name().equals(standing) || used.contains(room.name()) || tried >= 3) {
                continue;
            }
            // A straight run of open floor 8 blocks long along x for the zombie to walk.
            AtomicReference<BlockPos[]> run = new AtomicReference<>();
            AtomicReference<Boolean> searched = new AtomicReference<>();
            int yHint = ctx.computeOnClient(mc -> ((BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter",
                    new Class<?>[]{int.class}, new Object[]{0})).getY());
            ctx.runOnClient(mc -> {
                var server = mc.getSingleplayerServer();
                server.execute(() -> {
                    run.set(walkRun(server.overworld(), room.x(), room.z(), yHint, 8));
                    searched.set(true);
                });
            });
            ctx.waitFor(mc -> searched.get() != null, 200);
            if (run.get() == null) {
                continue;
            }
            tried++;
            used.add(room.name());
            BlockPos a = run.get()[0];
            BlockPos b = run.get()[1];
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "clearRoom", new Class<?>[]{String.class},
                    new Object[]{room.name()}));
            int pairs = starredPairCount(ctx);
            ctx.runOnClient(mc -> {
                Object k = ModUnderTest.enumValue(SIM_MOBS + "$Kind", "ZOMBIE");
                ModUnderTest.staticCall(SIM_MOBS, "spawnStarred", new Class<?>[]{Minecraft.class, BlockPos.class,
                        k.getClass()}, new Object[]{mc, a, k});
            });
            ctx.waitTicks(3);
            UUID[] pair = newestPair(ctx, pairs);
            if (pair == null) {
                continue;
            }
            ctx.waitTicks(20);
            double[] pos = {a.getX() + 0.5};
            double[] dir = {0.25};
            AtomicReference<String> done = new AtomicReference<>();
            AtomicReference<String> gaveUp = new AtomicReference<>();
            long mark = LogTap.mark();
            SimAutoClearTests.Result r = runCaseWith(ctx, "predict", () -> ModUnderTest.staticCall(AUTO, "clearRoom",
                    new Class<?>[]{String.class, Runnable.class, Consumer.class},
                    new Object[]{room.name(), (Runnable) () -> done.set("done"), (Consumer<String>) gaveUp::set}), t -> {
                pos[0] += dir[0];
                if (pos[0] >= b.getX() + 0.5 || pos[0] <= a.getX() + 0.5) {
                    dir[0] = -dir[0];
                }
                moveMob(ctx, pair, new Vec3(pos[0], a.getY(), a.getZ() + 0.5));
            });
            Map<UUID, String> alive = SimAutoClearTests.aliveReport(ctx, List.of(new SimAutoClearTests.Placed(room.name(),
                    false, pair[0], a)));
            int led = 0;
            for (String l : LogTap.since(mark)) {
                led += l.contains("leading the bat") ? 1 : 0;
            }
            String line = String.format(Locale.US, "predict (%s): walking zombie %s, onDone=%s onGiveUp=%s, %d tick(s), uses"
                            + " %d, lead lines %d, traveled %.1f", room.name(), alive.get(pair[0]), done.get(), gaveUp.get(),
                    r.ticks, r.uses, led, r.travelled);
            println(line);
            lines.add(line);
        }
        if (lines.isEmpty()) {
            SUMMARY.add("predict: no room with an 8-block straight run of open floor");
        }
        SUMMARY.addAll(lines);
    }

    /** Two ends of a straight 8-block run of open floor along +x near the tile centre (same y), or null. */
    private static BlockPos[] walkRun(ServerLevel level, int cx, int cz, int yHint, int len) {
        for (int ring = 0; ring <= 10; ring += 2) {
            for (int dz = -ring; dz <= ring; dz += 2) {
                for (int dx = -ring; dx <= ring; dx += 2) {
                    for (int y = yHint - 4; y <= yHint + 4; y++) {
                        boolean ok = true;
                        for (int i = 0; i <= len && ok; i++) {
                            BlockPos f = new BlockPos(cx + dx + i, y, cz + dz);
                            ok = solid(level, f.below()) && !solid(level, f) && !solid(level, f.above())
                                    && !solid(level, f.above(2));
                        }
                        if (ok) {
                            return new BlockPos[]{new BlockPos(cx + dx, y, cz + dz), new BlockPos(cx + dx + len, y, cz + dz)};
                        }
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------ door

    private static void doorCase(ClientGameTestContext ctx, List<String> failures, List<SimAutoClearTests.Room> rooms,
                                 List<SimAutoClearTests.Room> mobRooms, List<String> used, boolean isNew) {
        SimAutoClearTests.setMode(ctx, "ANY_MOB_ROOM", "HYPERION");
        String standing = standingIn(ctx);
        SimAutoClearTests.Room locked = null;
        for (SimAutoClearTests.Room r : mobRooms) {
            if (r.name().equals(standing) || used.contains(r.name())) {
                continue;
            }
            String doors = ctx.computeOnClient(mc -> roomDoors(r.name()));
            if (doors.startsWith("ok")) {
                locked = r;
                break;
            }
        }
        if (locked == null) {
            SUMMARY.add("door: no unused mob room with only ordinary doors");
            return;
        }
        SimAutoClearTests.Room room = locked;
        used.add(room.name());
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "clearRoom", new Class<?>[]{String.class},
                new Object[]{room.name()}));
        List<SimAutoClearTests.Placed> placed = SimAutoClearTests.spawnIn(ctx, room, 1, false);
        int doors = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_DOORS, "witherDoorsAround",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, room.name()}));
        ctx.waitTicks(40);
        AtomicReference<Boolean> keyGiven = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID me = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(me);
                if (sp != null) {
                    sp.getInventory().setItem(3, (ItemStack) ModUnderTest.staticCall(SIM_DOORS, "createWitherKey"));
                }
                keyGiven.set(sp != null);
            });
        });
        ctx.waitFor(mc -> keyGiven.get() != null, 100);
        ctx.waitTicks(5);
        int keysBefore = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_DOORS, "keysHeld"));
        println("door: " + doors + " wither door(s) round " + room.name() + ", he is in " + standing + ", keys " + keysBefore);
        long mark = LogTap.mark();
        int[] keyGone = {-1};
        int[] cleared = {-1};
        int[] doorStart = {-1};
        SimAutoClearTests.Result r = runCaseWith(ctx, "door", () -> ModUnderTest.staticCall(AUTO, "start"), t -> {
            if (doorStart[0] < 0) {
                for (String l : LogTap.since(mark)) {
                    if (l.contains("[AutoClear] only a wither door")) {
                        doorStart[0] = t;
                    }
                }
            }
            if (keyGone[0] < 0 && ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_DOORS, "keysHeld")) == 0) {
                keyGone[0] = t;
            }
            if (cleared[0] < 0 && ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_ROOM_STATE, "isCleared",
                    new Class<?>[]{String.class}, new Object[]{room.name()}))) {
                cleared[0] = t;
            }
        });
        int tripLine = -1;
        int clickLine = -1;
        int clicks = 0;
        int walked = 0;
        List<String> since = LogTap.since(mark);
        for (int i = 0; i < since.size(); i++) {
            String l = since.get(i);
            if (l.contains("[AutoClear] trip to wither door") && tripLine < 0) {
                tripLine = i;
            }
            if (l.contains("[AutoClear] clicked wither door")) {
                clicks++;
                if (clickLine < 0) {
                    clickLine = i;
                }
            }
            walked += l.contains("move to the wither door: walk") && l.contains("- walking") ? 1 : 0;
        }
        Map<UUID, String> alive = SimAutoClearTests.aliveReport(ctx, placed);
        String line = String.format(Locale.US, "door (%s, %d door(s)): door decided at tick %d, key taken at tick %d (%d"
                        + " after the decision), room cleared at tick %d, run %d tick(s), %d click(s), walked %d, mob %s,"
                        + " last: %s", room.name(), doors, doorStart[0], keyGone[0], keyGone[0] - doorStart[0], cleared[0],
                r.ticks, clicks, walked, alive.values(), r.lastLine);
        println(line);
        SUMMARY.add(line);
        if (isNew && keyGone[0] < 0) {
            failures.add("door: the key was never used");
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** {@link SimAutoClearTests#runCase} with a per-tick hook (its own overload with one is private). */
    private static SimAutoClearTests.Result runCaseWith(ClientGameTestContext ctx, String label, Runnable start,
                                                       java.util.function.IntConsumer perTick) {
        SimAutoClearTests.Result r = new SimAutoClearTests.Result();
        long mark = LogTap.mark();
        dev.testkit.harness.PacketWatch.start();
        ctx.runOnClient(mc -> start.run());
        Vec3 last = ctx.computeOnClient(mc -> mc.player.position());
        for (int t = 0; t < 2400; t++) {
            ctx.waitTicks(1);
            Vec3 now = ctx.computeOnClient(mc -> mc.player.position());
            r.travelled += now.distanceTo(last);
            last = now;
            r.ticks = t + 1;
            perTick.accept(t + 1);
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
        dev.testkit.harness.PacketWatch.stop();
        r.uses = dev.testkit.harness.PacketWatch.itemUses();
        r.interacts = dev.testkit.harness.PacketWatch.entityInteracts();
        for (String l : LogTap.since(mark)) {
            if (l.contains("[AutoClear] done:") || l.contains("[AutoClear] stopped:")) {
                r.lastLine = l.replaceAll("^.*\\[AutoClear\\] ", "");
            }
            if (l.contains("[AutoClear]") || l.contains("[Sim] room cleared")) {
                println("  log: " + l.replaceAll("^.*?(\\[AutoClear\\]|\\[Sim\\])", "$1"));
            }
        }
        return r;
    }

    /** Moves a sim mob and its star tag on the server (the harness, not the mod: a walking mob). */
    private static void moveMob(ClientGameTestContext ctx, UUID[] pair, Vec3 to) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                ServerLevel level = server.overworld();
                Entity mob = level.getEntity(pair[0]);
                if (mob == null || !mob.isAlive()) {
                    return;
                }
                mob.snapTo(to.x, to.y, to.z, mob.getYRot(), mob.getXRot());
                Entity tag = pair.length > 1 && pair[1] != null ? level.getEntity(pair[1]) : null;
                if (tag != null) {
                    tag.snapTo(to.x, to.y + mob.getBbHeight() + 0.1, to.z, 0f, 0f);
                }
            });
        });
    }

    private static List<SimAutoClearTests.Placed> spawnAt(ClientGameTestContext ctx, String room, List<BlockPos> spots) {
        List<SimAutoClearTests.Placed> placed = new ArrayList<>();
        for (BlockPos s : spots) {
            int pairs = starredPairCount(ctx);
            ctx.runOnClient(mc -> {
                Object k = ModUnderTest.enumValue(SIM_MOBS + "$Kind", "ZOMBIE");
                ModUnderTest.staticCall(SIM_MOBS, "spawnStarred", new Class<?>[]{Minecraft.class, BlockPos.class,
                        k.getClass()}, new Object[]{mc, s, k});
            });
            ctx.waitTicks(3);
            UUID[] pair = newestPair(ctx, pairs);
            if (pair != null) {
                placed.add(new SimAutoClearTests.Placed(room, false, pair[0], s));
            }
        }
        return placed;
    }

    /** Up to {@code want} floor spots in the room's first tile, each at least {@code apart} from the others. */
    private static List<BlockPos> spreadSpots(ClientGameTestContext ctx, SimAutoClearTests.Room room, int want,
                                              double apart) {
        int yHint = ctx.computeOnClient(mc -> ((BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter",
                new Class<?>[]{int.class}, new Object[]{0})).getY());
        AtomicReference<List<BlockPos>> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                ServerLevel level = server.overworld();
                List<BlockPos> got = new ArrayList<>();
                for (int dx = -13; dx <= 13 && got.size() < want; dx += 1) {
                    for (int dz = -13; dz <= 13 && got.size() < want; dz += 1) {
                        for (int y = yHint - 6; y <= yHint + 6; y++) {
                            BlockPos f = new BlockPos(room.x() + dx, y, room.z() + dz);
                            if (solid(level, f.below()) && !solid(level, f) && !solid(level, f.above())
                                    && !solid(level, f.above(2))) {
                                boolean far = true;
                                for (BlockPos o : got) {
                                    far &= Math.sqrt(o.distSqr(f)) >= apart;
                                }
                                if (far) {
                                    got.add(f);
                                }
                                break;
                            }
                        }
                    }
                }
                out.set(got);
            });
        });
        ctx.waitFor(mc -> out.get() != null, 200);
        // Only the ones the live map files under this room.
        return ctx.computeOnClient(mc -> {
            List<BlockPos> in = new ArrayList<>();
            for (BlockPos p : out.get()) {
                if (room.name().equals(roomNameAt(p.getX() + 0.5, p.getZ() + 0.5))) {
                    in.add(p);
                }
            }
            return in;
        });
    }

    private static String standingIn(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> roomNameAt(mc.player.getX(), mc.player.getZ()));
    }

    private static String roomNameAt(double x, double z) {
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        int r = (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class, double.class},
                new Object[]{x, z});
        return r < 0 ? null : (String) ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r});
    }

    private static int roomIndex(Object layout, String name) {
        int count = (Integer) ModUnderTest.call(layout, "roomCount", new Class<?>[]{}, new Object[]{});
        for (int i = 0; i < count; i++) {
            if (name.equals(ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{i}))) {
                return i;
            }
        }
        return -1;
    }

    /** SimAutoClearTests' roomDoors: "ok N" when every door to another room is an ordinary one. */
    private static String roomDoors(String roomName) {
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        int room = roomIndex(layout, roomName);
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
                    other.add("door type " + type);
                }
            }
        }
        return !other.isEmpty() ? String.join(", ", other) : normal == 0 ? "no door" : "ok " + normal;
    }

    private static boolean solid(ServerLevel level, BlockPos p) {
        return !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static int starredPairCount(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> ((List<UUID[]>) ModUnderTest.staticCall(SIM_MOBS, "starredPairs")).size());
    }

    @SuppressWarnings("unchecked")
    private static UUID[] newestPair(ClientGameTestContext ctx, int before) {
        for (int i = 0; i < 40; i++) {
            List<UUID[]> pairs = ctx.computeOnClient(mc -> (List<UUID[]>) ModUnderTest.staticCall(SIM_MOBS, "starredPairs"));
            if (pairs.size() > before) {
                return pairs.get(pairs.size() - 1);
            }
            ctx.waitTicks(1);
        }
        return null;
    }

    static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}

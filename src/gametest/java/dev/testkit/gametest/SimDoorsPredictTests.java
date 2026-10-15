package dev.testkit.gametest;

import com.mojang.authlib.GameProfile;
import dev.testkit.compat.McCompat;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 1720-1731 sim-doors-predict: Wither Doors from the held map, the Fairy door inference, Predictive Rooms and the
 * Special Room Guess (mod doors-predict-1015). killer560's item, 2026-10-15 (wither doors only from the normal map,
 * waypoint style, nothing before Mort's countdown; the Blood door without Show All Doors; the Fairy door only once it is
 * known and the door after it hidden until Fairy is opened; line-of-sight room prediction that never scans; a split
 * colour for a one-tile room that is likely a puzzle, yellow or trap). Only when named.
 *
 * <p>One generated sim F7 (3 puzzles, 4 rooms to blood). The sim sends no map item, so the mod's held-map stand-in
 * answers ({@code HeldMap}): rooms are opened when someone stands in them, which this test does by placing the player
 * or by {@code HeldMap.simVisit}. Show All Doors is OFF throughout: every row is the no-cheat-ESP behaviour, on both
 * jars. Rows:
 * <ul>
 * <li>1721 not before start: coal doors round the first room after the Entrance; through the whole {@code /start}
 *     countdown nothing is drawn and the status says it is waiting; after GO the Entrance's door is drawn with a label.</li>
 * <li>1722 map only: coal doors round a room no opened room touches, the player standing in one of its doorways: none
 *     drawn; once a neighbour is opened, that neighbour's door is.</li>
 * <li>1723 blood: the Blood door is drawn without Show All Doors once the room before it is opened, not before.</li>
 * <li>1724 fairy: with the room before Fairy opened and the player out of sight, the Fairy door is what the map says (a
 *     wither door); once he looks at its empty doorway it is the Fairy door (box, label); a teammate in Fairy clears it.</li>
 * <li>1725 after fairy: the door from Fairy to the next room, made coal, is not drawn while Fairy is unopened; it is
 *     once Fairy is opened (its block is loaded).</li>
 * <li>1726 named: looking through doorways into unopened rooms, at least two are named right, and NO prediction ever
 *     names a tile for a room that is not there.</li>
 * <li>1727 partial: every prediction's certain tiles are that room's tiles; unsure tiles are reported.</li>
 * <li>1728 sight only: every seen block, re-cast along its recorded ray from its recorded eye right after the look, is
 *     the first block that ray meets.</li>
 * <li>1729 hidden block: an emerald sealed under a room's floor is never seen while a gold block standing in the open
 *     in the same view is.</li>
 * <li>1730 guess: a one-tile room whose neighbours are all opened gets a guess with Puzzle in it; a room with an
 *     unopened neighbour gets none; an opened Trap takes Trap out.</li>
 * <li>1731 settings: Predictive Rooms, Special Room Guess and Door Labels survive a save and reload.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimDoorsPredictTests implements FabricClientGameTest {

    private static final String NAME = "1720-sim-doors-predict";
    private static final String R_BEFORE = "1721-sim-wither-doors-not-before-start";
    private static final String R_MAPONLY = "1722-sim-wither-doors-map-only";
    private static final String R_BLOOD = "1723-sim-wither-doors-blood-without-show-all";
    private static final String R_FAIRY = "1724-sim-wither-doors-fairy-inferred";
    private static final String R_AFTER = "1725-sim-wither-doors-after-fairy-hidden";
    private static final String R_NAMED = "1726-sim-predict-rooms-named";
    private static final String R_PARTIAL = "1727-sim-predict-rooms-partial";
    private static final String R_SIGHT = "1728-sim-predict-rooms-sight-only";
    private static final String R_HIDDEN = "1729-sim-predict-rooms-hidden-block";
    private static final String R_GUESS = "1730-sim-special-room-guess";
    private static final String R_SETTINGS = "1731-sim-predict-settings-persist";

    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String SIM_DOORS = "com.killer560.hub.roomsim.SimDoors";
    private static final String SIM_RUN = "com.killer560.hub.roomsim.SimRun";
    private static final String SIM_BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String HELD = "com.killer560.hub.livemap.HeldMap";
    private static final String FEATURE = "com.killer560.hub.witherdoors.WitherDoorsFeature";
    private static final String CFG = "com.killer560.hub.witherdoors.WitherDoorsConfig";
    private static final String PREDICTOR = "com.killer560.hub.livemap.predict.RoomPredictor";
    private static final String PCFG = "com.killer560.hub.livemap.predict.PredictiveRoomsConfig";
    private static final String GUESS = "com.killer560.hub.livemap.SpecialRoomGuess";
    private static final String PAINTER = "com.killer560.hub.livemap.PredictedRoomsPainter";
    private static final String VARIANT = "com.killer560.hub.BuildVariant";
    private static final Path SHOTS = Path.of("C:/Users/Hunter/killer560s-mod-logs/doors-predict-shots");
    private static final UUID MATE_ID = UUID.fromString("6c1b4d2f-5a3e-4b9c-8d4e-3f2a1b0c9d8e");
    private static final String MATE_NAME = "FairyMate2";
    private static final int MATE_ENTITY = 931720;
    private static final int KIND_WITHER = 0;
    private static final int KIND_BLOOD = 1;
    private static final int KIND_FAIRY = 2;

    private final List<String> all = new ArrayList<>();
    private boolean cheat;
    private String jarTag = "";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        try {
            cheat = Class.forName(VARIANT).getField("CHEAT_FEATURES_ENABLED").getBoolean(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        jarTag = (cheat ? "cheat" : "legit") + "-" + net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();
        println("jar: " + jarTag);
        try {
            body(ctx);
        } catch (Throwable t) {
            all.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(SimDoorsPredictTests::removeMate);
            ctx.runOnClient(mc -> {
                try {
                    Object c = ModUnderTest.config(CFG);
                    ModUnderTest.set(c, "setEnabled", false);
                    inv(c, "save");
                } catch (Throwable ignored) {
                    // cleanup never replaces a verdict
                }
            });
            leave(ctx);
        }
        if (!all.isEmpty()) {
            throw new AssertionError(String.join(" | ", all));
        }
        println("PASS - every doors/predict case passed");
    }

    // ------------------------------------------------------------------------------------------------- body

    private void body(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(CFG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setShowAllDoors", false);
            ModUnderTest.set(cfg, "setFairyDoor", true);
            ModUnderTest.set(cfg, "setDoorLabels", true);
            ModUnderTest.set(cfg, "setRenderDistance", 256);
            Object p = ModUnderTest.config(PCFG);
            ModUnderTest.set(p, "setEnabled", true);
            ModUnderTest.set(p, "setSpecialGuess", true);
            mc.options.guiScale().set(2);
        });
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                new Object[]{"gametest"}));
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            ModUnderTest.staticCall(FLOOR_GEN, "generate",
                    new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, 3, 4});
        }));
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        ctx.waitFor(mc -> mc.player.onGround() && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir(), 2400);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withSuppressedOutput(), "time set noon"));
        });
        Floor f = ctx.computeOnClient(SimDoorsPredictTests::readFloor);
        println("floor: " + f);
        if (f.problem != null) {
            row(R_BEFORE, false, "premise: " + f.problem);
            return;
        }
        creativeFlying(ctx);

        doorsBeforeStart(ctx, f);
        doorsMapOnly(ctx, f);
        doorsBlood(ctx, f);
        doorsFairy(ctx, f);
        doorsAfterFairy(ctx, f);
        predict(ctx, f);
        guess(ctx, f);
        settings(ctx);
    }

    // ---- 1721 -----------------------------------------------------------------------------------------------------

    private void doorsBeforeStart(ClientGameTestContext ctx, Floor f) {
        Report.caseStarted(R_BEFORE);
        // Two rooms off the Entrance-to-Blood path, joined by a door: coal round the second, the player in the first.
        int[] pair = f.offPathPair(Set.of());
        if (pair == null) {
            row(R_BEFORE, false, "premise: no two joined rooms off the path");
            return;
        }
        int first = pair[0];
        int coalRoom = pair[1];
        int door = f.doorBetween(first, coalRoom);
        f.used.add(first);
        f.used.add(coalRoom);
        int made = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_DOORS, "witherDoorsAround",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, f.names.get(coalRoom)}));
        BlockPos db = f.doorBlock(door);
        waitCoal(ctx, db, made);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(HELD, "resetSimVisits"));
        double[] inFirst = f.tileCentre(f.tiles.get(first)[0], 0.5);
        place(ctx, inFirst, null);
        ctx.waitTicks(20);
        boolean startedBefore = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_RUN, "hasStarted"));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_RUN, "begin", new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall(SIM_BUILDER, "entranceDoor")}));
        int countdownTicks = 0;
        int drawnDuring = 0;
        Set<String> statuses = new java.util.LinkedHashSet<>();
        for (int i = 0; i < 400; i++) {
            int left = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_RUN, "countdownTicks"));
            boolean started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_RUN, "hasStarted"));
            if (started) {
                break;
            }
            if (left > 0) {
                countdownTicks++;
                int boxes = ctx.computeOnClient(mc -> ((List<?>) ModUnderTest.staticCall(FEATURE, "cachedBoxes")).size());
                if (boxes > 0) {
                    drawnDuring++;
                }
                statuses.add(status());
            }
            ctx.waitTick();
        }
        ctx.waitTicks(30);
        List<double[]> boxes = ctx.computeOnClient(mc -> boxes());
        List<String> labels = labels(ctx);
        boolean boxAtDoor = boxAt(boxes, f.doorCentre(door)) != null;
        String detail = made + " coal door(s) round " + f.names.get(coalRoom) + "; run started before /start: " + startedBefore
                + "; " + countdownTicks + " countdown tick(s), " + drawnDuring + " with a box drawn, statuses " + statuses
                + "; after GO: box at the door " + door + " from " + f.names.get(first) + " " + boxAtDoor + ", " + boxes.size() + " box(es), labels "
                + labels + ", status '" + statusSafe(ctx) + "'";
        row(R_BEFORE, made > 0 && !startedBefore && countdownTicks >= 60 && drawnDuring == 0
                && statuses.contains("waiting for the run to start") && boxAtDoor && labels.contains("Wither Door"), detail);
    }

    // ---- 1722 -----------------------------------------------------------------------------------------------------

    private void doorsMapOnly(ClientGameTestContext ctx, Floor f) {
        Report.caseStarted(R_MAPONLY);
        // A room no opened room touches: not the Entrance, not next to it, not on the first steps of the path.
        ctx.runOnClient(mc -> ModUnderTest.staticCall(HELD, "resetSimVisits"));
        // A pair off the path, neither next to the Entrance (opened) nor to the rooms 1721 used.
        Set<Integer> near = new HashSet<>(f.neighbours(f.entrance));
        for (int u : f.used) {
            near.add(u);
            near.addAll(f.neighbours(u));
        }
        int[] pair = f.offPathPair(near);
        if (pair == null) {
            row(R_MAPONLY, false, "premise: no room away from the Entrance with a free neighbour");
            return;
        }
        int room = pair[1];
        int neighbour = pair[0];
        f.used.add(room);
        f.used.add(neighbour);
        final int fr = room;
        int made = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_DOORS, "witherDoorsAround",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, f.names.get(fr)}));
        int door = f.doorBetween(room, neighbour);
        BlockPos db = f.doorBlock(door);
        waitCoal(ctx, db, made);
        // In the doorway between the two: inside neither room, so neither opens.
        // High over the doorway (the doorway itself is coal now and would push him out into a room).
        double[] gap = f.doorCentre(door);
        place(ctx, new double[]{gap[0], gap[1] + 30, gap[2]}, null);
        ctx.runOnClient(mc -> invalidate());
        ctx.waitTicks(30);
        List<double[]> before = ctx.computeOnClient(mc -> boxes());
        int[] visited = ctx.computeOnClient(mc -> (int[]) ModUnderTest.staticCall(HELD, "simVisitedRooms"));
        boolean anyAtRoom = false;
        for (int d : f.doorsOf(room)) {
            anyAtRoom |= boxAt(before, f.doorCentre(d)) != null;
        }
        final int nb = neighbour;
        ctx.runOnClient(mc -> ModUnderTest.staticCall(HELD, "simVisit", new Class<?>[]{int.class}, new Object[]{nb}));
        ctx.runOnClient(mc -> invalidate());
        ctx.waitTicks(30);
        List<double[]> after = ctx.computeOnClient(mc -> boxes());
        boolean atDoor = boxAt(after, f.doorCentre(door)) != null;
        row(R_MAPONLY, made > 0 && !anyAtRoom && atDoor && !contains(visited, room) && !contains(visited, neighbour),
                made + " coal door(s) round " + f.names.get(room) + " (room " + room + ", neighbour " + neighbour
                        + ", visited " + names(f, java.util.Arrays.stream(visited).boxed().toList()) + "); standing in its doorway " + door + " to "
                        + f.names.get(neighbour) + ", opened rooms " + Arrays.toString(visited) + ": a box on any of its doors "
                        + anyAtRoom + " (" + before.size() + " box(es) in all); " + f.names.get(neighbour)
                        + " opened: box at that door " + atDoor);
    }

    // ---- 1723 -----------------------------------------------------------------------------------------------------

    private void doorsBlood(ClientGameTestContext ctx, Floor f) {
        Report.caseStarted(R_BLOOD);
        int bd = f.bloodDoor;
        int prev = f.path.get(f.path.size() - 2);
        if (bd < 0) {
            row(R_BLOOD, false, "premise: no blood door on the layout");
            return;
        }
        // High over the blood doorway: in neither room, so neither opens, and close enough for any render distance.
        ctx.runOnClient(mc -> ModUnderTest.staticCall(HELD, "resetSimVisits"));
        double[] bc = f.doorCentre(bd);
        place(ctx, new double[]{bc[0], bc[1] + 30, bc[2]}, null);
        ctx.runOnClient(mc -> invalidate());
        ctx.waitTicks(30);
        boolean terracotta = ctx.computeOnClient(mc -> mc.level.isLoaded(f.doorBlock(bd))
                && !mc.level.getBlockState(f.doorBlock(bd)).isAir());
        boolean opened = ctx.computeOnClient(mc -> contains((int[]) ModUnderTest.staticCall(HELD, "simVisitedRooms"), prev));
        boolean before = boxAt(ctx.computeOnClient(mc -> boxes()), f.doorCentre(bd)) != null;
        ctx.runOnClient(mc -> ModUnderTest.staticCall(HELD, "simVisit", new Class<?>[]{int.class}, new Object[]{prev}));
        ctx.runOnClient(mc -> invalidate());
        ctx.waitTicks(30);
        boolean after = boxAt(ctx.computeOnClient(mc -> boxes()), f.doorCentre(bd)) != null;
        List<String> labels = labels(ctx);
        List<int[]> doors = ctx.computeOnClient(mc -> mapDoors());
        boolean kindBlood = kindAt(doors, bd) == KIND_BLOOD;
        shot(ctx, "blood-door");
        row(R_BLOOD, terracotta && !opened && !before && after && kindBlood && labels.contains("Blood Door"),
                "blood door " + bd + " solid " + terracotta + "; " + f.names.get(prev) + " not opened (" + !opened
                        + "): box " + before + "; opened: box " + after + ", kind " + kindAt(doors, bd) + ", labels " + labels);
    }

    // ---- 1724 -----------------------------------------------------------------------------------------------------

    private void doorsFairy(ClientGameTestContext ctx, Floor f) {
        Report.caseStarted(R_FAIRY);
        int at = f.path.indexOf(f.fairy);
        int before = f.path.get(at - 1);
        int fd = f.doorBetween(before, f.fairy);
        boolean air = ctx.computeOnClient(mc -> mc.level.getBlockState(f.doorBlock(fd)).isAir());
        ctx.runOnClient(mc -> ModUnderTest.staticCall(HELD, "resetSimVisits"));
        // Out of sight of the doorway: high above the floor, where the rays meet the roofs.
        double[] far = f.tileCentre(f.tiles.get(f.entrance)[0], 60);
        place(ctx, far, null);
        ctx.runOnClient(mc -> resetDoorsRun());
        ctx.runOnClient(mc -> ModUnderTest.staticCall(HELD, "simVisit", new Class<?>[]{int.class}, new Object[]{before}));
        ctx.runOnClient(mc -> invalidate());
        ctx.waitTicks(30);
        List<int[]> unseen = ctx.computeOnClient(mc -> mapDoors());
        boolean seenEarly = ctx.computeOnClient(mc -> seen().containsKey(fd));
        int kindUnseen = kindAt(unseen, fd);
        // He looks into the doorway from the room before Fairy.
        double[] view = f.approach(before, fd, 7);
        place(ctx, view, f.doorCentre(fd));
        ctx.runOnClient(mc -> invalidate());
        ctx.waitTicks(30);
        List<int[]> seenDoors = ctx.computeOnClient(mc -> mapDoors());
        Map<Integer, Boolean> seen = ctx.computeOnClient(mc -> seen());
        int kindSeen = kindAt(seenDoors, fd);
        boolean box = boxAt(ctx.computeOnClient(mc -> boxes()), f.doorCentre(fd)) != null;
        List<String> labels = labels(ctx);
        shot(ctx, "fairy-door");
        double[] fairyMid = f.tileCentre(f.tiles.get(f.fairy)[0], 0.5);
        ctx.runOnClient(mc -> spawnMate(mc, fairyMid));
        ctx.runOnClient(mc -> invalidate());
        ctx.waitTicks(30);
        int kindMate = kindAt(ctx.computeOnClient(mc -> mapDoors()), fd);
        boolean boxMate = boxAt(ctx.computeOnClient(mc -> boxes()), f.doorCentre(fd)) != null;
        ctx.runOnClient(SimDoorsPredictTests::removeMate);
        row(R_FAIRY, air && !seenEarly && kindUnseen == KIND_WITHER && Boolean.TRUE.equals(seen.get(fd))
                        && kindSeen == KIND_FAIRY && box && labels.contains("Fairy Door") && kindMate == -1 && !boxMate,
                "door " + fd + " into Fairy from " + f.names.get(before) + ", doorway air " + air + "; out of sight: seen "
                        + seenEarly + ", kind " + kindUnseen + " (0 wither); looked in from 7 blocks: seen " + seen.get(fd)
                        + ", kind " + kindSeen + " (2 fairy), box " + box + ", labels " + labels + "; teammate in Fairy: kind "
                        + kindMate + ", box " + boxMate);
    }

    // ---- 1725 -----------------------------------------------------------------------------------------------------

    private void doorsAfterFairy(ClientGameTestContext ctx, Floor f) {
        Report.caseStarted(R_AFTER);
        int at = f.path.indexOf(f.fairy);
        int after = f.path.get(at + 1);
        int before = f.path.get(at - 1);
        int afd = f.doorBetween(f.fairy, after);
        if (after == f.blood) {
            row(R_AFTER, false, "premise: the room after Fairy is Blood on this floor");
            return;
        }
        int made = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_DOORS, "witherDoorsAround",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, f.names.get(after)}));
        BlockPos db = f.doorBlock(afd);
        waitCoal(ctx, db, made);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(HELD, "resetSimVisits"));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(HELD, "simVisit", new Class<?>[]{int.class}, new Object[]{before}));
        // In the Fairy door's gap, right by Fairy: the nearest coal door is the one after it.
        int fd = f.doorBetween(before, f.fairy);
        double[] gap = f.doorCentre(fd);
        place(ctx, new double[]{gap[0], gap[1] + 0.2, gap[2]}, null);
        ctx.runOnClient(mc -> invalidate());
        ctx.waitTicks(30);
        boolean fairyOpen = ctx.computeOnClient(mc -> contains((int[]) ModUnderTest.staticCall(HELD, "simVisitedRooms"), f.fairy));
        boolean hidden = kindAt(ctx.computeOnClient(mc -> mapDoors()), afd) == -1
                && boxAt(ctx.computeOnClient(mc -> boxes()), f.doorCentre(afd)) == null;
        ctx.runOnClient(mc -> ModUnderTest.staticCall(HELD, "simVisit", new Class<?>[]{int.class}, new Object[]{f.fairy}));
        ctx.runOnClient(mc -> invalidate());
        ctx.waitTicks(30);
        boolean loaded = ctx.computeOnClient(mc -> mc.level.isLoaded(db));
        int kindOpen = kindAt(ctx.computeOnClient(mc -> mapDoors()), afd);
        boolean boxOpen = boxAt(ctx.computeOnClient(mc -> boxes()), f.doorCentre(afd)) != null;
        row(R_AFTER, made > 0 && !fairyOpen && hidden && loaded && kindOpen == KIND_WITHER && boxOpen,
                made + " coal door(s) round " + f.names.get(after) + "; door " + afd + " from Fairy, Fairy unopened (" + !fairyOpen
                        + "): hidden " + hidden + "; Fairy opened, door loaded " + loaded + ": kind " + kindOpen + ", box " + boxOpen);
    }

    // ---- 1726-1729 -------------------------------------------------------------------------------------------------

    private void predict(ClientGameTestContext ctx, Floor f) {
        Report.caseStarted(R_NAMED);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(HELD, "resetSimVisits"));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(PREDICTOR, "reset"));
        // Doorways from one room into another that is neither the Entrance nor Blood, with nothing in the doorway.
        List<int[]> tries = new ArrayList<>();
        for (Map.Entry<Integer, int[]> e : f.doorRooms.entrySet()) {
            int d = e.getKey();
            int a = e.getValue()[0];
            int b = e.getValue()[1];
            for (int[] ab : new int[][]{{a, b}, {b, a}}) {
                if (ab[1] != f.entrance && ab[1] != f.blood && ab[0] != f.blood) {
                    tries.add(new int[]{d, ab[0], ab[1]});
                }
            }
        }
        java.util.Collections.shuffle(tries, new java.util.Random(1720));
        List<String> wrong = new ArrayList<>();
        List<String> right = new ArrayList<>();
        List<String> partial = new ArrayList<>();
        List<String> tried = new ArrayList<>();
        Set<Integer> usedTargets = new HashSet<>();
        int hiddenDoor = -1;
        int hiddenFrom = -1;
        int hiddenInto = -1;
        for (int[] t : tries) {
            int d = t[0];
            int from = t[1];
            int into = t[2];
            if (usedTargets.contains(into) || usedTargets.contains(from)) {
                continue;
            }
            boolean open = ctx.computeOnClient(mc -> mc.level.getBlockState(f.doorBlock(d)).isAir()
                    && mc.level.getBlockState(f.doorBlock(d).above()).isAir());
            if (!open) {
                continue;
            }
            if (hiddenDoor < 0) {
                hiddenDoor = d;
                hiddenFrom = from;
                hiddenInto = into;
                usedTargets.add(into);
                usedTargets.add(from);
                continue; // kept for 1729
            }
            if (right.size() >= 3 || tried.size() >= 7) {
                continue;
            }
            usedTargets.add(into);
            usedTargets.add(from);
            lookInto(ctx, f, from, into, d);
            List<Object> preds = ctx.computeOnClient(mc -> new ArrayList<>((List<?>) ModUnderTest.staticCall(PREDICTOR, "predictions")));
            int evidence = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(PREDICTOR, "evidence",
                    new Class<?>[]{int.class}, new Object[]{f.tiles.get(into)[0]}));
            String named = null;
            for (Object p : preds) {
                String pname = (String) inv(p, "name");
                int[] certain = (int[]) inv(p, "certainTiles");
                int[] unsure = (int[]) inv(p, "uncertainTiles");
                for (int tile : certain) {
                    int actual = f.roomAt(tile);
                    if (actual < 0 || !f.names.get(actual).equalsIgnoreCase(pname)) {
                        wrong.add(pname + " on tile " + tile + " (really " + (actual < 0 ? "nothing" : f.names.get(actual)) + ")");
                    }
                }
                if (contains(f.tiles.get(into), (Integer) inv(p, "tile")) && pname.equalsIgnoreCase(f.names.get(into))) {
                    named = pname + " " + certain.length + "/" + f.tiles.get(into).length + " tile(s) certain, " + unsure.length
                            + " unsure, turn known " + inv(p, "turnKnown");
                    if (unsure.length > 0 || certain.length < f.tiles.get(into).length) {
                        partial.add(named);
                    }
                }
            }
            tried.add(f.names.get(from) + " -> " + f.names.get(into) + " (" + f.tiles.get(into).length + " tile(s), " + evidence
                    + " seen): " + (named == null ? "no prediction" : named));
            if (named != null) {
                right.add(f.names.get(into));
            }
        }
        shot(ctx, "predict-last-view");
        int obs = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(PREDICTOR, "observationCount"));
        String status = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(PREDICTOR, "status"));
        String detail = obs + " block(s) seen, status '" + status + "'; tried " + tried + "; wrong " + wrong;
        row(R_NAMED, right.size() >= 2 && wrong.isEmpty(), right.size() + " named right; " + detail);

        Report.caseStarted(R_PARTIAL);
        row(R_PARTIAL, wrong.isEmpty() && !tried.isEmpty(), "partial predictions " + partial + "; wrong certain tiles " + wrong);

        // ---- 1728: every seen block is visible from where it was seen.
        Report.caseStarted(R_SIGHT);
        int[] audit = auditTotals;
        row(R_SIGHT, audit[0] >= 500 && audit[1] == 0, audit[0] + " seen block(s) audited right after each look, " + audit[1]
                + " whose recorded ray, cast again from the recorded eye, meets something else first (" + audit[2] + " unloaded); "
                + "first blind ones " + blindExamples);

        // ---- 1729: a block nobody could see is never seen.
        Report.caseStarted(R_HIDDEN);
        if (hiddenDoor < 0) {
            row(R_HIDDEN, false, "premise: no free doorway left");
            return;
        }
        final int hd = hiddenDoor;
        final int hInto = hiddenInto;
        final int hFrom = hiddenFrom;
        double[] centre = f.tileCentre(f.tiles.get(hInto)[0], 0);
        AtomicReference<BlockPos> emerald = new AtomicReference<>();
        AtomicReference<BlockState> emeraldWas = new AtomicReference<>();
        AtomicReference<BlockPos> gold = new AtomicReference<>();
        AtomicReference<BlockState> goldWas = new AtomicReference<>();
        int doorY = f.doorBlock(hd).getY();
        double[] u = f.toward(hFrom, hd);
        double[] dc = f.doorCentre(hd);
        server(ctx, level -> {
            // Sealed: every neighbour solid. Searched through the room's tile - under its floor first, then up through
            // its walls and pillars - anywhere more than six blocks from the doorway.
            outer:
            for (int k = 0; k < 24; k++) {
                int dy = k < 8 ? -2 - k : k - 8;
                for (int dx = -13; dx <= 13; dx++) {
                    for (int dz = -13; dz <= 13; dz++) {
                        BlockPos p = new BlockPos((int) Math.floor(centre[0]) + dx, doorY - 1 + dy, (int) Math.floor(centre[2]) + dz);
                        if (Math.abs(p.getX() + 0.5 - dc[0]) + Math.abs(p.getZ() + 0.5 - dc[2]) < 7) {
                            continue;
                        }
                        boolean sealed = !level.getBlockState(p).isAir();
                        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                            BlockState n = level.getBlockState(p.relative(dir));
                            sealed &= !n.isAir() && n.canOcclude();
                        }
                        if (sealed) {
                            emerald.set(p);
                            emeraldWas.set(level.getBlockState(p));
                            level.setBlock(p, Blocks.EMERALD_BLOCK.defaultBlockState(), 3);
                            break outer;
                        }
                    }
                }
            }
            // In the open, five blocks inside the room straight through the doorway, at eye height.
            BlockPos g = BlockPos.containing(dc[0] + u[0] * 5, doorY + 1, dc[2] + u[1] * 5);
            gold.set(g);
            goldWas.set(level.getBlockState(g));
            level.setBlock(g, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        });
        if (emerald.get() == null) {
            row(R_HIDDEN, false, "premise: no sealed spot under " + f.names.get(hInto));
            return;
        }
        // At the doorway first, so the room is loaded on the client, then the two blocks must have arrived.
        place(ctx, f.approach(hFrom, hd, 5), null);
        ctx.waitFor(mc -> mc.level.getBlockState(gold.get()).is(Blocks.GOLD_BLOCK)
                && mc.level.getBlockState(emerald.get()).is(Blocks.EMERALD_BLOCK), 600);
        lookInto(ctx, f, hFrom, hInto, hd);
        String atGold = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(PREDICTOR, "observedAt",
                new Class<?>[]{BlockPos.class}, new Object[]{gold.get()}));
        String atEmerald = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(PREDICTOR, "observedAt",
                new Class<?>[]{BlockPos.class}, new Object[]{emerald.get()}));
        int emeraldsSeen = ctx.computeOnClient(mc -> {
            int n = 0;
            for (Object o : (List<?>) ModUnderTest.staticCall(PREDICTOR, "observations")) {
                double[] b = (double[]) o;
                if (mc.level.getBlockState(BlockPos.containing(b[0], b[1], b[2])).is(Blocks.EMERALD_BLOCK)) {
                    n++;
                }
            }
            return n;
        });
        server(ctx, level -> {
            level.setBlock(emerald.get(), emeraldWas.get(), 3);
            level.setBlock(gold.get(), goldWas.get(), 3);
        });
        row(R_HIDDEN, "minecraft:gold_block".equals(atGold) && atEmerald == null && emeraldsSeen == 0,
                "looking into " + f.names.get(hInto) + ": gold block in the open at " + gold.get().toShortString() + " seen as "
                        + atGold + "; emerald sealed at " + emerald.get().toShortString() + " seen as " + atEmerald
                        + "; emerald blocks among everything seen: " + emeraldsSeen);
    }

    /** Stands in {@code from} and looks through door {@code d} into {@code into} from three distances and five angles. */
    /** Running {audited, blind, unloaded} over every look, each audited before anything else changes the world. */
    private final int[] auditTotals = new int[3];
    private final List<String> blindExamples = new ArrayList<>();

    private void lookInto(ClientGameTestContext ctx, Floor f, int from, int into, int d) {
        int start = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(PREDICTOR, "observationCount"));
        lookIntoOnly(ctx, f, from, into, d);
        Object[] r = ctx.computeOnClient(mc -> auditSight(mc, start));
        int[] a = (int[]) r[0];
        for (int i = 0; i < 3; i++) {
            auditTotals[i] += a[i];
        }
        @SuppressWarnings("unchecked")
        List<String> ex = (List<String>) r[1];
        for (String e : ex) {
            if (blindExamples.size() < 6) {
                blindExamples.add(e);
            }
        }
    }

    private void lookIntoOnly(ClientGameTestContext ctx, Floor f, int from, int into, int d) {
        double[] dc = f.doorCentre(d);
        double[] u = f.toward(from, d);
        for (double back : new double[]{5, 2, 0}) {
            double[] at = new double[]{dc[0] - u[0] * back, dc[1] + 0.2, dc[2] - u[1] * back};
            for (double[] aim : new double[][]{{0, 0}, {-0.35, 0}, {0.35, 0}, {0, -0.3}, {0, 0.25}}) {
                // A point 12 blocks into the room, turned sideways by aim[0] and up/down by aim[1].
                double[] target = new double[]{dc[0] + u[0] * 12 + u[1] * aim[0] * 12, dc[1] + 1.6 + aim[1] * 12,
                        dc[2] + u[1] * 12 - u[0] * aim[0] * 12};
                place(ctx, at, target, true);
                ctx.waitTicks(10);
            }
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(PREDICTOR, "settle"));
    }

    /** {audited, not visible, unloaded}: each seen block against 27 rays from its recorded eye to points on its box. */
    private static Object[] auditSight(Minecraft mc, int from) {
        List<?> obs = (List<?>) ModUnderTest.staticCall(PREDICTOR, "observations");
        int audited = 0;
        int blind = 0;
        int unloaded = 0;
        List<String> examples = new ArrayList<>();
        for (int i = from; i < obs.size(); i++) {
            double[] o = (double[]) obs.get(i);
            BlockPos p = BlockPos.containing(o[0], o[1], o[2]);
            if (!mc.level.isLoaded(p)) {
                unloaded++;
                continue;
            }
            audited++;
            // The recorded ray again, from the recorded eye through the point it met the block: the first block it
            // meets must be that block, in the world as it stands now (audited before anything else changes it).
            Vec3 eye = new Vec3(o[3], o[4], o[5]);
            Vec3 at = new Vec3(o[6], o[7], o[8]);
            Vec3 past = at.add(at.subtract(eye).normalize().scale(0.05));
            HitResult again = mc.level.clip(new ClipContext(eye, past, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE,
                    mc.player));
            boolean visible = again instanceof BlockHitResult br && again.getType() == HitResult.Type.BLOCK
                    && br.getBlockPos().equals(p);
            if (!visible) {
                blind++;
                if (examples.size() < 6) {
                    examples.add(mc.level.getBlockState(p).getBlock() + " at " + p.toShortString() + " from "
                            + String.format(Locale.ROOT, "%.1f,%.1f,%.1f", o[3], o[4], o[5]));
                }
            }
        }
        return new Object[]{new int[]{audited, blind, unloaded}, examples};
    }

    // ---- 1730 -----------------------------------------------------------------------------------------------------

    private void guess(ClientGameTestContext ctx, Floor f) {
        Report.caseStarted(R_GUESS);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(HELD, "resetSimVisits"));
        // A one-tile room every in-floor neighbour tile of which is another room it has a door to at least one of.
        int pick = -1;
        List<Integer> around = new ArrayList<>();
        for (int r = 0; r < f.names.size(); r++) {
            if (f.tiles.get(r).length != 1 || r == f.entrance || f.neighbours(r).isEmpty()) {
                continue;
            }
            List<Integer> rooms = f.roomsAroundTile(f.tiles.get(r)[0]);
            if (rooms == null || rooms.contains(f.entrance) && rooms.size() == 1) {
                continue;
            }
            pick = r;
            around = rooms;
            break;
        }
        if (pick < 0) {
            row(R_GUESS, false, "premise: no one-tile room with every neighbour tile a room");
            return;
        }
        final int p = pick;
        int tile = f.tiles.get(pick)[0];
        // Before: its neighbours are not opened, so it is not known to be one tile.
        List<String> none = guessAt(ctx, tile);
        for (int n : around) {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(HELD, "simVisit", new Class<?>[]{int.class}, new Object[]{n}));
        }
        int state = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(HELD, "roomState",
                new Class<?>[]{int.class}, new Object[]{tile}));
        List<String> guessed = guessAt(ctx, tile);
        @SuppressWarnings("unchecked")
        Map<Integer, String> plan = ctx.computeOnClient(mc -> (Map<Integer, String>) ModUnderTest.staticCall(PAINTER, "plan",
                new Class<?>[]{boolean.class}, new Object[]{true}));
        // An opened trap takes Trap out.
        int trap = f.firstOfType("TRAP", p);
        List<String> afterTrap = null;
        if (trap >= 0) {
            final int tr = trap;
            ctx.runOnClient(mc -> ModUnderTest.staticCall(HELD, "simVisit", new Class<?>[]{int.class}, new Object[]{tr}));
            afterTrap = guessAt(ctx, tile);
        }
        boolean ok = none.isEmpty() && state == 4 && guessed.contains("PUZZLE")
                && plan.getOrDefault(tile, "").startsWith("guess:") && (afterTrap == null || !afterTrap.contains("TRAP"));
        row(R_GUESS, ok, f.names.get(pick) + " (tile " + tile + ", really " + f.types.get(pick) + "), neighbours "
                + names(f, around) + ": before they open " + none + "; opened, its state " + state + " (4 unopened), guess "
                + guessed + ", painter plan '" + plan.get(tile) + "'; " + (trap < 0 ? "no trap room on this floor"
                : "trap " + f.names.get(trap) + " opened: " + afterTrap));
    }

    @SuppressWarnings("unchecked")
    private static List<String> guessAt(ClientGameTestContext ctx, int tile) {
        return ctx.computeOnClient(mc -> new ArrayList<>((List<String>) ModUnderTest.staticCall(GUESS, "candidates",
                new Class<?>[]{int.class}, new Object[]{tile})));
    }

    // ---- 1731 -----------------------------------------------------------------------------------------------------

    private void settings(ClientGameTestContext ctx) {
        Report.caseStarted(R_SETTINGS);
        String result = ctx.computeOnClient(mc -> {
            Object p = ModUnderTest.config(PCFG);
            ModUnderTest.set(p, "setEnabled", false);
            ModUnderTest.set(p, "setSpecialGuess", false);
            inv(p, "save");
            ModUnderTest.staticCall(PCFG, "load", new Class<?>[]{}, new Object[]{});
            Object p2 = ModUnderTest.config(PCFG);
            boolean off = !(Boolean) inv(p2, "isEnabledRaw") && !(Boolean) inv(p2, "isSpecialGuessRaw");
            ModUnderTest.set(p2, "setEnabled", true);
            ModUnderTest.set(p2, "setSpecialGuess", true);
            inv(p2, "save");
            Object w = ModUnderTest.config(CFG);
            ModUnderTest.set(w, "setDoorLabels", false);
            inv(w, "save");
            ModUnderTest.staticCall(CFG, "load", new Class<?>[]{}, new Object[]{});
            Object w2 = ModUnderTest.config(CFG);
            boolean labelsOff = !(Boolean) inv(w2, "isDoorLabels");
            ModUnderTest.set(w2, "setDoorLabels", true);
            inv(w2, "save");
            return off + "," + labelsOff;
        });
        row(R_SETTINGS, "true,true".equals(result), "predictive + guess off survived reload, door labels off survived reload: " + result);
    }

    // ------------------------------------------------------------------------------------------------- reading

    /** Waits (bounded) for coal at a door block; says what is there if it never comes. */
    private static boolean waitCoal(ClientGameTestContext ctx, BlockPos db, int made) {
        for (int i = 0; i < 200; i++) {
            if (ctx.computeOnClient(mc -> mc.level.getBlockState(db).is(Blocks.COAL_BLOCK))) {
                return true;
            }
            ctx.waitTick();
        }
        String around = ctx.computeOnClient(mc -> {
            StringBuilder sb = new StringBuilder();
            for (int dy = -2; dy <= 3; dy++) {
                sb.append(dy).append('=').append(mc.level.getBlockState(db.above(dy)).getBlock()).append(' ');
            }
            return sb.toString();
        });
        println("no coal at " + db.toShortString() + " (made " + made + "): " + around);
        return false;
    }

    private static List<double[]> boxes() {
        List<double[]> out = new ArrayList<>();
        for (Object o : (List<?>) ModUnderTest.staticCall(FEATURE, "cachedBoxes")) {
            out.add((double[]) o);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> labels(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> new ArrayList<>((List<String>) ModUnderTest.staticCall(FEATURE, "cachedLabels")));
    }

    private static List<int[]> mapDoors() {
        List<int[]> out = new ArrayList<>();
        for (Object o : (List<?>) ModUnderTest.staticCall(FEATURE, "mapDoorsNow")) {
            out.add((int[]) o);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Boolean> seen() {
        return (Map<Integer, Boolean>) ModUnderTest.staticCall(FEATURE, "seenDoorways");
    }

    private static int kindAt(List<int[]> doors, int idx) {
        for (int[] d : doors) {
            if (d[0] == idx) {
                return d[1];
            }
        }
        return -1;
    }

    private static double[] boxAt(List<double[]> boxes, double[] centre) {
        for (double[] b : boxes) {
            if (Math.abs((b[0] + b[3]) / 2 - centre[0]) < 0.6 && Math.abs((b[2] + b[5]) / 2 - centre[2]) < 0.6) {
                return b;
            }
        }
        return null;
    }

    private static String status() {
        return (String) ModUnderTest.staticCall(FEATURE, "status");
    }

    private static String statusSafe(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> status());
    }

    private static void invalidate() {
        ModUnderTest.staticCall(FEATURE, "invalidateCache");
    }

    /** MapDoors' per-run memory (seen doorways) starts again: the mod's own new-floor path. */
    private static void resetDoorsRun() {
        try {
            Method m = Class.forName("com.killer560.hub.witherdoors.MapDoors").getDeclaredMethod("reset");
            m.setAccessible(true);
            m.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not reset MapDoors", e);
        }
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    private static String names(Floor f, List<Integer> rooms) {
        List<String> out = new ArrayList<>();
        for (int r : rooms) {
            out.add(f.names.get(r));
        }
        return out.toString();
    }

    /** The published floor as this test reads it, with its own Entrance-to-Blood search. */
    private static Floor readFloor(Minecraft mc) {
        Floor f = new Floor();
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        int rooms = (Integer) inv(layout, "roomCount");
        for (int r = 0; r < rooms; r++) {
            f.names.add((String) inv(layout, "name", r));
            f.tiles.add((int[]) inv(layout, "tiles", r));
            Object entry = inv(layout, "entry", r);
            String type = "";
            if (entry != null) {
                try {
                    Object t = entry.getClass().getField("type").get(entry);
                    type = t == null ? "" : t.toString().toUpperCase(Locale.ROOT);
                } catch (ReflectiveOperationException ignored) {
                    // no type
                }
            }
            f.types.add(type);
            if ("ENTRANCE".equals(type) || "Entrance".equalsIgnoreCase(f.names.get(r))) f.entrance = r;
            if ("BLOOD".equals(type) || "Blood".equalsIgnoreCase(f.names.get(r))) f.blood = r;
            if ("FAIRY".equals(type) || "Fairy".equalsIgnoreCase(f.names.get(r))) f.fairy = r;
        }
        for (int idx = 0; idx < 121; idx++) {
            f.roomOf[idx] = (Integer) inv(layout, "roomOfCell", idx);
        }
        for (int idx = 0; idx < 121; idx++) {
            int gx = idx % 11;
            int gz = idx / 11;
            if ((gx % 2 == 1) == (gz % 2 == 1) || !(Boolean) inv(layout, "isDoor", idx)) {
                continue;
            }
            int a = gx % 2 == 1 ? f.roomOf[gz * 11 + gx - 1] : f.roomOf[(gz - 1) * 11 + gx];
            int b = gx % 2 == 1 ? f.roomOf[gz * 11 + gx + 1] : f.roomOf[(gz + 1) * 11 + gx];
            if (a >= 0 && b >= 0 && a != b) {
                f.doorRooms.put(idx, new int[]{a, b});
            }
        }
        f.bloodDoor = (Integer) inv(layout, "bloodDoor");
        BlockPos any = (BlockPos) ModUnderTest.staticCall(LAYOUT, "doorBlock", new Class<?>[]{int.class}, new Object[]{1});
        f.doorY = any.getY();
        if (f.entrance < 0 || f.blood < 0 || f.fairy < 0) {
            f.problem = "entrance " + f.entrance + ", blood " + f.blood + ", fairy " + f.fairy + " among " + rooms + " rooms";
            return f;
        }
        int[] prev = new int[rooms];
        Arrays.fill(prev, -2);
        prev[f.entrance] = -1;
        ArrayDeque<Integer> q = new ArrayDeque<>(List.of(f.entrance));
        while (!q.isEmpty()) {
            int r = q.poll();
            for (int n : f.neighbours(r)) {
                if (prev[n] == -2) {
                    prev[n] = r;
                    q.add(n);
                }
            }
        }
        if (prev[f.blood] == -2) {
            f.problem = "blood not reachable";
            return f;
        }
        for (int r = f.blood; r != -1; r = prev[r]) {
            f.path.add(0, r);
        }
        int at = f.path.indexOf(f.fairy);
        if (at <= 0 || at >= f.path.size() - 1) {
            f.problem = "the path does not pass through Fairy: " + names(f, f.path);
        }
        return f;
    }

    private static final class Floor {
        String problem;
        final List<String> names = new ArrayList<>();
        final List<String> types = new ArrayList<>();
        final List<int[]> tiles = new ArrayList<>();
        final int[] roomOf = new int[121];
        final Map<Integer, int[]> doorRooms = new LinkedHashMap<>();
        final List<Integer> path = new ArrayList<>();
        int entrance = -1;
        int blood = -1;
        int fairy = -1;
        int bloodDoor = -1;
        int doorY;

        final Set<Integer> used = new HashSet<>();

        /** {a, b}: two rooms joined by a door, neither on the Entrance-to-Blood path nor next to Fairy, neither in
         *  {@code avoid}, b not touching a path room (so coal round b never lands on a path door). */
        int[] offPathPair(Set<Integer> avoid) {
            Set<Integer> bad = new HashSet<>(path);
            bad.addAll(avoid);
            bad.addAll(used);
            for (int a = 0; a < names.size(); a++) {
                if (bad.contains(a)) continue;
                for (int b : neighbours(a)) {
                    if (bad.contains(b)) continue;
                    boolean touchesPath = false;
                    for (int n : neighbours(b)) touchesPath |= path.contains(n);
                    if (!touchesPath) return new int[]{a, b};
                }
            }
            return null;
        }

        List<Integer> neighbours(int room) {
            List<Integer> out = new ArrayList<>();
            for (int[] ab : doorRooms.values()) {
                if (ab[0] == room && !out.contains(ab[1])) out.add(ab[1]);
                if (ab[1] == room && !out.contains(ab[0])) out.add(ab[0]);
            }
            return out;
        }

        List<Integer> doorsOf(int room) {
            List<Integer> out = new ArrayList<>();
            for (Map.Entry<Integer, int[]> e : doorRooms.entrySet()) {
                if (e.getValue()[0] == room || e.getValue()[1] == room) out.add(e.getKey());
            }
            return out;
        }

        int doorBetween(int a, int b) {
            for (Map.Entry<Integer, int[]> e : doorRooms.entrySet()) {
                int[] ab = e.getValue();
                if ((ab[0] == a && ab[1] == b) || (ab[0] == b && ab[1] == a)) return e.getKey();
            }
            throw new AssertionError("no door between " + names.get(a) + " and " + names.get(b));
        }

        int roomAt(int tile) {
            return tile >= 0 && tile < 121 ? roomOf[tile] : -1;
        }

        int firstOfType(String type, int not) {
            for (int r = 0; r < types.size(); r++) {
                if (r != not && type.equals(types.get(r))) return r;
            }
            return -1;
        }

        /** The rooms on the in-floor tiles round a tile, or null when one of them is no room or the tile's own room. */
        List<Integer> roomsAroundTile(int tile) {
            int gx = tile % 11;
            int gz = tile / 11;
            List<Integer> out = new ArrayList<>();
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : dirs) {
                int nx = gx + 2 * d[0];
                int nz = gz + 2 * d[1];
                if (nx < 0 || nz < 0 || nx > 10 || nz > 10) continue;
                int r = roomOf[nz * 11 + nx];
                if (r < 0 || r == roomOf[tile]) return null;
                if (!out.contains(r)) out.add(r);
            }
            return out;
        }

        BlockPos doorBlock(int idx) {
            return (BlockPos) ModUnderTest.staticCall(LAYOUT, "doorBlock", new Class<?>[]{int.class}, new Object[]{idx});
        }

        double[] doorCentre(int idx) {
            BlockPos b = doorBlock(idx);
            return new double[]{b.getX() + 0.5, b.getY(), b.getZ() + 0.5};
        }

        /** Unit step {x, z} from room {@code from}'s side of door {@code d} towards the other side. */
        double[] toward(int from, int d) {
            int gx = d % 11;
            int gz = d / 11;
            int side = gx % 2 == 1 ? roomOf[gz * 11 + gx - 1] : roomOf[(gz - 1) * 11 + gx];
            int sign = side == from ? 1 : -1;
            return gx % 2 == 1 ? new double[]{sign, 0} : new double[]{0, sign};
        }

        /** {@code back} blocks short of door {@code d} on room {@code from}'s side, at the door's floor. */
        double[] approach(int from, int d, double back) {
            double[] c = doorCentre(d);
            double[] u = toward(from, d);
            return new double[]{c[0] - u[0] * back, c[1] + 0.2, c[2] - u[1] * back};
        }

        double[] tileCentre(int tile, double up) {
            BlockPos c = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class}, new Object[]{tile});
            return new double[]{c.getX() + 0.5, doorY + up, c.getZ() + 0.5};
        }

        @Override
        public String toString() {
            return problem != null ? "PROBLEM " + problem : names.size() + " rooms, path " + path.stream().map(names::get).toList()
                    + ", " + doorRooms.size() + " doors, blood door " + bloodDoor;
        }
    }

    // ------------------------------------------------------------------------------------------------- acting

    private static void creativeFlying(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    sp.setGameMode(GameType.CREATIVE);
                    sp.getAbilities().mayfly = true;
                    sp.getAbilities().flying = true;
                    sp.onUpdateAbilities();
                }
            });
        });
        ctx.waitFor(mc -> mc.player.getAbilities().flying, 200);
    }

    private static void place(ClientGameTestContext ctx, double[] at, double[] look) {
        place(ctx, at, look, false);
    }

    /** Server teleport to {@code at}; with {@code look}, he faces it (exact point when {@code exact}, else 2 above). */
    private static void place(ClientGameTestContext ctx, double[] at, double[] look, boolean exact) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    sp.teleportTo(at[0], at[1], at[2]);
                }
            });
        });
        ctx.waitFor(mc -> mc.player.position().distanceToSqr(at[0], at[1], at[2]) < 0.01, 200);
        ctx.runOnClient(mc -> {
            mc.player.getAbilities().flying = true;
            mc.player.onUpdateAbilities();
            if (look != null) {
                double dx = look[0] - mc.player.getX();
                double dy = (exact ? look[1] : look[1] + 2.0) - mc.player.getEyeY();
                double dz = look[2] - mc.player.getZ();
                mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
                mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
            }
        });
    }

    private static void spawnMate(Minecraft mc, double[] at) {
        Entity existing = mc.level.getEntity(MATE_ENTITY);
        if (existing != null) {
            existing.setPos(at[0], at[1], at[2]);
            return;
        }
        RemotePlayer p = new RemotePlayer(mc.level, new GameProfile(MATE_ID, MATE_NAME));
        p.setId(MATE_ENTITY);
        p.setPos(at[0], at[1], at[2]);
        mc.level.addEntity(p);
    }

    private static void removeMate(Minecraft mc) {
        if (mc.level != null && mc.level.getEntity(MATE_ENTITY) != null) {
            mc.level.removeEntity(MATE_ENTITY, Entity.RemovalReason.DISCARDED);
        }
    }

    private interface LevelJob {
        void run(net.minecraft.server.level.ServerLevel level);
    }

    private static void server(ClientGameTestContext ctx, LevelJob job) {
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                job.run(server.overworld());
                done.set(true);
            });
        });
        ctx.waitFor(mc -> done.get(), 200);
    }

    private void shot(ClientGameTestContext ctx, String what) {
        String name = NAME + "-" + jarTag + "-" + what;
        Path taken = ctx.takeScreenshot(Report.fileName(name));
        Report.screenshot(NAME, taken);
        try {
            Files.createDirectories(SHOTS);
            Files.copy(taken, SHOTS.resolve(Report.fileName(name) + ".png"), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            println("could not copy screenshot " + taken + ": " + e);
        }
    }

    // ------------------------------------------------------------------------------------------------- plumbing

    private void row(String name, boolean pass, String detail) {
        System.out.println("[" + name + "] " + (pass ? "PASS" : "FAIL") + " - " + detail);
        Report.caseFinished(name, pass ? "PASS" : "FAIL", "n/a (singleplayer)", detail, List.of(), List.of());
        SuiteVerdict.finished(name);
        if (!pass) {
            all.add(name + ": " + detail);
        }
    }

    private static Object inv(Object target, String method, Object... args) {
        try {
            for (Method m : target.getClass().getDeclaredMethods()) {
                if (m.getName().equals(method) && m.getParameterCount() == args.length) {
                    m.setAccessible(true);
                    return m.invoke(target, args);
                }
            }
            throw new AssertionError("no method " + method + "/" + args.length + " on " + target.getClass().getName());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not call " + method, e);
        }
    }

    private static void leave(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            try {
                ModUnderTest.staticCall(SIM_STATE, "leave", new Class<?>[]{}, new Object[]{});
            } catch (Throwable ignored) {
                // cleanup never replaces a verdict
            }
        });
        ctx.runOnClient(mc -> mc.execute(() -> {
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                mc.disconnectWithSavingScreen();
            }
        }));
        ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 1200);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> mc.execute(() ->
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen, 400);
    }

    private static void println(String line) {
        System.out.println("[" + NAME + "] " + line);
    }
}

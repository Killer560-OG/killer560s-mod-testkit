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
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 531-538 sim-fairy-door: Wither Doors' Fairy Door (mod fairy-door, 2026-10-07). killer560: "For wither doors it should
 * also show the door between my room and Fairy on the blood rush path as a highlight till I enter Fairy", then "or till
 * anyone enters fairy". Only when named.
 *
 * <p>One generated sim F7 (3 puzzles, 4 rooms to blood: the sim always puts the Fairy on the Entrance-to-Blood path),
 * Wither Doors on with Fill Opacity 100, Filled Outline, Through Walls, Fairy Door colour pure magenta. Rows:
 * <ul>
 * <li>531 premise: this test's OWN breadth-first search from the Entrance to Blood over the published layout passes
 *     the Fairy room, and the mod's {@code FairyDoor.door()} is the door that search enters Fairy through (and one of
 *     the sim's theoretical wither doors, i.e. on its Entrance-to-Blood path).</li>
 * <li>532 nothing before the layout: sampled every tick through the build, the Fairy Door never holds a door and never
 *     draws while the layout has no rooms - and it must have ticked through at least one such tick (not vacuous).</li>
 * <li>533 draws: a box at the door cell at the door block's height, and in a screenshot from inside the room before
 *     Fairy the frame differs from a Fairy-Door-off frame of the same view by hundreds of magenta pixels.</li>
 * <li>534 avoids: teleported into the room AFTER Fairy (blood side), his blood path no longer goes through Fairy, so no
 *     door; teleported back, it returns (a teleport past Fairy is not an entry).</li>
 * <li>535 teammate: a client player entity (v4 UUID, no party known - the map takes every real player as a teammate)
 *     next to him changes nothing; put inside Fairy it clears the highlight while he stays outside, and the magenta is
 *     gone from the frame.</li>
 * <li>536 door opened: with the door's anchor block made solid and then air on the server, it clears.</li>
 * <li>537 self: he flies into Fairy; it clears.</li>
 * <li>538 wither doors unchanged: wither doors made round the room after Fairy ({@code SimDoors.witherDoorsAround});
 *     the wither boxes are the same with the Fairy Door on and off, and the nearest sits at the door block's height.</li>
 * </ul>
 * The legit jar has no Fairy Door (cheat build only): there 533-537 assert that nothing is drawn and the state stays
 * empty. Between 535, 536 and 537 the run latch is reset by setting {@code FairyDoor.generation} back, which is the
 * mod's own "new run" path. Screenshots: report screens/ and C:/Users/Hunter/killer560s-mod-logs/fairy-door-shots/.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimFairyDoorTests implements FabricClientGameTest {

    private static final String NAME = "531-sim-fairy-door";
    private static final String R_PREMISE = "531-sim-fairy-door-premise";
    private static final String R_BEFORE = "532-sim-fairy-door-none-before-layout";
    private static final String R_DRAWS = "533-sim-fairy-door-draws";
    private static final String R_AVOIDS = "534-sim-fairy-door-path-avoids";
    private static final String R_MATE = "535-sim-fairy-door-teammate-enters";
    private static final String R_OPENED = "536-sim-fairy-door-door-opened";
    private static final String R_SELF = "537-sim-fairy-door-self-enters";
    private static final String R_WITHER = "538-sim-fairy-door-wither-unchanged";

    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String SIM_DOORS = "com.killer560.hub.roomsim.SimDoors";
    private static final String SIM_WITHER = "com.killer560.hub.roomsim.SimWitherDoors";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String FAIRY = "com.killer560.hub.witherdoors.FairyDoor";
    private static final String FEATURE = "com.killer560.hub.witherdoors.WitherDoorsFeature";
    private static final String CFG = "com.killer560.hub.witherdoors.WitherDoorsConfig";
    private static final String VARIANT = "com.killer560.hub.BuildVariant";
    private static final int MAGENTA = 0xFFFF00FF;
    private static final Path SHOTS = Path.of("C:/Users/Hunter/killer560s-mod-logs/fairy-door-shots");
    private static final UUID MATE_ID = UUID.fromString("5b0a3c1e-4f2d-4a8b-9c3d-2e1f0a9b8c7d");
    private static final String MATE_NAME = "FairyMate";
    private static final int MATE_ENTITY = 931531;

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
        cheat = staticBool(VARIANT, "CHEAT_FEATURES_ENABLED");
        jarTag = (cheat ? "cheat" : "legit") + "-" + net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();
        println("jar: " + (cheat ? "cheat" : "legit"));
        String saved = ctx.computeOnClient(mc -> snapshotConfig());
        int gui = ctx.computeOnClient(mc -> mc.options.guiScale().get());
        try {
            body(ctx);
        } catch (Throwable t) {
            all.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> {
                restoreConfig(saved);
                mc.options.guiScale().set(gui);
                removeMate(mc);
            });
            leave(ctx);
        }
        if (!all.isEmpty()) {
            throw new AssertionError(String.join(" | ", all));
        }
        println("PASS - every Fairy Door case passed");
    }

    // ------------------------------------------------------------------------------------------------- body

    private void body(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(CFG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setFairyDoor", true);
            ModUnderTest.set(cfg, "setThroughWalls", true);
            ModUnderTest.set(cfg, "setShowAllDoors", false);
            ModUnderTest.set(cfg, "setCustomFillColor", false);
            ModUnderTest.set(cfg, "setFillOpacity", 100);
            ModUnderTest.set(cfg, "setFairyDoorColor", MAGENTA);
            ModUnderTest.set(cfg, "setRenderDistance", 256);
            ModUnderTest.call(cfg, "setStyle", new Class<?>[]{ModUnderTest.enumValue(CFG + "$Style", "FILL").getClass()},
                    new Object[]{ModUnderTest.enumValue(CFG + "$Style", "FILLED_OUTLINE")});
            mc.options.guiScale().set(2);
        });

        // ---- 532: sampled through the build ---------------------------------------------------------------------
        Report.caseStarted(R_BEFORE);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                new Object[]{"gametest"}));
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            ModUnderTest.staticCall(FLOOR_GEN, "generate",
                    new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, 3, 4});
        }));
        int noLayoutTicks = 0;
        int violations = 0;
        String firstViolation = null;
        java.util.Set<String> statuses = new java.util.LinkedHashSet<>();
        for (int i = 0; i < 24_000; i++) {
            String[] s = ctx.computeOnClient(SimFairyDoorTests::sampleBeforeLayout);
            if (s != null) {
                statuses.add(s[0]);
                boolean ticked = !s[0].equals("idle") && !s[0].equals("off");
                boolean noRooms = s[1].equals("0");
                if (ticked && noRooms) {
                    noLayoutTicks++;
                    if (!s[2].equals("-1") || !s[3].equals("0")) {
                        violations++;
                        if (firstViolation == null) {
                            firstViolation = Arrays.toString(s);
                        }
                    }
                }
            }
            long built = ctx.computeOnClient(mc -> (Long) ModUnderTest.staticCall(
                    "com.killer560.hub.roomsim.SimBuildQueue", "buildsFinished"));
            if (built > before) {
                break;
            }
            ctx.waitTick();
        }
        Scenario.awaitSimBuild(ctx, before);
        String beforeDetail = noLayoutTicks + " tick(s) with the Fairy Door ticking and no rooms on the layout, "
                + violations + " with a door or a box; statuses seen " + statuses;
        if (noLayoutTicks == 0) {
            row(R_BEFORE, false, "premise: the Fairy Door never ticked before the layout existed - " + beforeDetail);
        } else {
            row(R_BEFORE, violations == 0, beforeDetail + (firstViolation == null ? "" : "; first " + firstViolation));
        }

        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        ctx.waitFor(mc -> mc.player.onGround() && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir(), 2400);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withSuppressedOutput(), "time set noon"));
        });

        // ---- 531: premise and the mod's choice against this test's own search ----------------------------------
        Report.caseStarted(R_PREMISE);
        Floor f = ctx.computeOnClient(SimFairyDoorTests::readFloor);
        println("floor: " + f);
        if (f.problem != null) {
            row(R_PREMISE, false, "premise: " + f.problem);
            return;
        }
        ctx.waitTicks(30);
        int modDoor = ctx.computeOnClient(mc -> fairyDoor());
        String status = ctx.computeOnClient(mc -> fairyStatus());
        boolean theoretical = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_WITHER, "isTheoretical",
                new Class<?>[]{int.class}, new Object[]{f.entryDoor}));
        if (cheat) {
            row(R_PREMISE, modDoor == f.entryDoor && theoretical, "his room " + f.startRoomName + "; test's search: "
                    + f.pathNames + ", enters Fairy through door " + f.entryDoor + " (theoretical wither door "
                    + theoretical + "); mod: door " + modDoor + ", status '" + status + "'");
        } else {
            row(R_PREMISE, modDoor == -1 && theoretical, "legit jar: test's search enters Fairy through door "
                    + f.entryDoor + " (theoretical " + theoretical + "); mod door " + modDoor + " (must be -1), status '"
                    + status + "'");
        }

        // ---- 533: the box and the pixels --------------------------------------------------------------------------
        Report.caseStarted(R_DRAWS);
        creativeFlying(ctx);
        double[] view = viewPoint(f);
        place(ctx, view, f.doorCentre);
        ctx.waitTicks(30);
        double[] box = ctx.computeOnClient(mc -> fairyBox(f));
        Path on = shot(ctx, "draws-on");
        setFairyDoor(ctx, false);
        ctx.waitTicks(15);
        double[] boxOff = ctx.computeOnClient(mc -> fairyBox(f));
        Path off = shot(ctx, "draws-off");
        setFairyDoor(ctx, true);
        ctx.waitTicks(15);
        int[] px = magenta(on, off);
        String pxText = px[0] + " magenta px changed against the Fairy-Door-off frame, centroid " + px[1] + "," + px[2]
                + " of " + px[3] + "x" + px[4];
        if (cheat) {
            boolean centred = px[0] > 0 && Math.abs(px[1] - px[3] / 2) < px[3] / 3 && Math.abs(px[2] - px[4] / 2) < px[4] / 3;
            row(R_DRAWS, box != null && boxOff == null && px[0] >= 300 && centred,
                    "box " + fmt(box) + " (door block y " + f.doorY + "), with the option off " + fmt(boxOff) + "; " + pxText);
        } else {
            row(R_DRAWS, box == null && px[0] < 30, "legit jar: box " + fmt(box) + "; " + pxText);
        }

        // ---- 534: from the room after Fairy the blood path avoids it --------------------------------------------
        Report.caseStarted(R_AVOIDS);
        double[] after = roomCentre(f, f.afterRoomTile);
        place(ctx, after, null);
        ctx.waitTicks(30);
        int doorAfter = ctx.computeOnClient(mc -> fairyDoor());
        String statusAfter = ctx.computeOnClient(mc -> fairyStatus());
        double[] boxAfter = ctx.computeOnClient(mc -> fairyBox(f));
        place(ctx, view, f.doorCentre);
        ctx.waitTicks(30);
        int doorBack = ctx.computeOnClient(mc -> fairyDoor());
        if (cheat) {
            row(R_AVOIDS, doorAfter == -1 && boxAfter == null && statusAfter.contains("avoids") && doorBack == f.entryDoor,
                    "in " + f.afterRoomName + ": door " + doorAfter + ", status '" + statusAfter + "', box " + fmt(boxAfter)
                            + "; back before Fairy: door " + doorBack);
        } else {
            row(R_AVOIDS, doorAfter == -1 && doorBack == -1, "legit jar: doors " + doorAfter + " / " + doorBack);
        }

        // ---- 535: a teammate enters Fairy -------------------------------------------------------------------------
        Report.caseStarted(R_MATE);
        List<?> party = ctx.computeOnClient(mc -> (List<?>) ModUnderTest.staticCall("com.killer560.hub.leapmenu.PartyTracker", "teammates"));
        note(R_MATE, "party tracker teammates: " + party + " (empty = every real player counts as a teammate)");
        double[] beside = new double[]{view[0], view[1] + 1, view[2]};
        ctx.runOnClient(mc -> spawnMate(mc, beside));
        ctx.waitTicks(30);
        boolean clearedByNeighbour = ctx.computeOnClient(mc -> fairyCleared());
        int doorWithNeighbour = ctx.computeOnClient(mc -> fairyDoor());
        double[] fairyMid = roomCentre(f, f.fairyTile);
        ctx.runOnClient(mc -> spawnMate(mc, fairyMid));
        boolean mateCleared = waitCleared(ctx, 100);
        String mateStatus = ctx.computeOnClient(mc -> fairyStatus());
        double[] himNow = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        double[] boxMate = ctx.computeOnClient(mc -> fairyBox(f));
        ctx.waitTicks(15);
        Path mateShot = shot(ctx, "teammate-entered");
        int[] pxMate = magenta(mateShot, off);
        if (cheat) {
            row(R_MATE, !clearedByNeighbour && doorWithNeighbour == f.entryDoor && mateCleared
                            && mateStatus.contains(MATE_NAME) && boxMate == null && pxMate[0] < 30,
                    "mate beside him: cleared " + clearedByNeighbour + ", door " + doorWithNeighbour + "; mate in Fairy: "
                            + "status '" + mateStatus + "', box " + fmt(boxMate) + ", him at " + fmt(himNow) + " (outside), "
                            + pxMate[0] + " magenta px left");
        } else {
            row(R_MATE, boxMate == null && pxMate[0] < 30, "legit jar: box " + fmt(boxMate) + ", " + pxMate[0] + " magenta px");
        }
        ctx.runOnClient(SimFairyDoorTests::removeMate);

        // ---- 536: the door's block goes solid, then air --------------------------------------------------------
        Report.caseStarted(R_OPENED);
        resetRun(ctx);
        ctx.waitTicks(30);
        int doorReset = ctx.computeOnClient(mc -> fairyDoor());
        BlockPos anchor = f.doorBlock;
        AtomicReference<BlockState> was = new AtomicReference<>();
        server(ctx, level -> {
            was.set(level.getBlockState(anchor));
            level.setBlock(anchor, Blocks.COAL_BLOCK.defaultBlockState(), 3);
        });
        ctx.waitFor(mc -> mc.level.getBlockState(anchor).is(Blocks.COAL_BLOCK), 200);
        ctx.waitTicks(10);
        boolean clearedSolid = ctx.computeOnClient(mc -> fairyCleared());
        server(ctx, level -> level.setBlock(anchor, Blocks.AIR.defaultBlockState(), 3));
        boolean openedCleared = waitCleared(ctx, 100);
        String openedStatus = ctx.computeOnClient(mc -> fairyStatus());
        server(ctx, level -> level.setBlock(anchor, was.get(), 3));
        if (cheat) {
            row(R_OPENED, doorReset == f.entryDoor && !clearedSolid && openedCleared && openedStatus.contains("opened"),
                    "after the run reset: door " + doorReset + "; anchor " + anchor.toShortString() + " (was " + was.get()
                            + ") solid: cleared " + clearedSolid + "; air: status '" + openedStatus + "'");
        } else {
            row(R_OPENED, doorReset == -1, "legit jar: door " + doorReset);
        }

        // ---- 537: he flies into Fairy ------------------------------------------------------------------------------
        Report.caseStarted(R_SELF);
        resetRun(ctx);
        ctx.waitTicks(30);
        int doorSelf = ctx.computeOnClient(mc -> fairyDoor());
        place(ctx, fairyMid, null);
        boolean selfCleared = waitCleared(ctx, 100);
        String selfStatus = ctx.computeOnClient(mc -> fairyStatus());
        place(ctx, view, f.doorCentre);
        ctx.waitTicks(30);
        int doorStillClear = ctx.computeOnClient(mc -> fairyDoor());
        if (cheat) {
            row(R_SELF, doorSelf == f.entryDoor && selfCleared && selfStatus.contains("you entered") && doorStillClear == -1,
                    "before: door " + doorSelf + "; in Fairy: status '" + selfStatus + "'; back outside: door " + doorStillClear);
        } else {
            row(R_SELF, doorSelf == -1, "legit jar: door " + doorSelf);
        }

        // ---- 538: wither doors are unchanged -----------------------------------------------------------------------
        Report.caseStarted(R_WITHER);
        int made = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_DOORS, "witherDoorsAround",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, f.afterRoomName}));
        ctx.waitTicks(10);
        resetRun(ctx);
        resetScanner(ctx);
        ctx.waitTicks(40);
        List<double[]> withFairy = ctx.computeOnClient(mc -> witherBoxes(f));
        setFairyDoor(ctx, false);
        ctx.waitTicks(15);
        List<double[]> withoutFairy = ctx.computeOnClient(mc -> witherBoxes(f));
        setFairyDoor(ctx, true);
        boolean same = describe(withFairy).equals(describe(withoutFairy));
        boolean heightOk = !withFairy.isEmpty() && Math.abs(withFairy.get(0)[1] - f.doorY) < 0.01;
        row(R_WITHER, made > 0 && same && heightOk, made + " wither door(s) made round " + f.afterRoomName
                + "; wither boxes with the Fairy Door on " + describe(withFairy) + ", off " + describe(withoutFairy)
                + " (door block y " + f.doorY + ")");
    }

    // ------------------------------------------------------------------------------------------------- reading

    /** {status, rooms on the layout, door, boxes drawn} - or null before there is a player. */
    private static String[] sampleBeforeLayout(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return null;
        }
        Object layout = ModUnderTest.staticCall(LAYOUT, "current");
        int rooms = (Integer) inv(layout, "roomCount");
        return new String[]{fairyStatus(), String.valueOf(rooms), String.valueOf(fairyDoor()),
                String.valueOf(((List<?>) ModUnderTest.staticCall(FEATURE, "cachedBoxes")).size())};
    }

    private static int fairyDoor() {
        return (Integer) ModUnderTest.staticCall(FAIRY, "door");
    }

    private static boolean fairyCleared() {
        return (Boolean) ModUnderTest.staticCall(FAIRY, "isCleared");
    }

    private static String fairyStatus() {
        return (String) ModUnderTest.staticCall(FAIRY, "status");
    }

    /** The drawn box over the Fairy door cell, or null. */
    private static double[] fairyBox(Floor f) {
        for (Object o : (List<?>) ModUnderTest.staticCall(FEATURE, "cachedBoxes")) {
            double[] b = (double[]) o;
            double cx = (b[0] + b[3]) / 2;
            double cz = (b[2] + b[5]) / 2;
            if (Math.abs(cx - f.doorCentre[0]) < 0.6 && Math.abs(cz - f.doorCentre[2]) < 0.6 && Math.abs(b[1] - f.doorY) < 0.01) {
                return b;
            }
        }
        return null;
    }

    /** Every drawn box that is not the Fairy door's. */
    private static List<double[]> witherBoxes(Floor f) {
        List<double[]> out = new ArrayList<>();
        for (Object o : (List<?>) ModUnderTest.staticCall(FEATURE, "cachedBoxes")) {
            double[] b = (double[]) o;
            double cx = (b[0] + b[3]) / 2;
            double cz = (b[2] + b[5]) / 2;
            if (!(Math.abs(cx - f.doorCentre[0]) < 0.6 && Math.abs(cz - f.doorCentre[2]) < 0.6)) {
                out.add(b);
            }
        }
        return out;
    }

    /** What the published floor is, from the layout, with this test's own search from the Entrance to Blood. */
    private static Floor readFloor(Minecraft mc) {
        Floor f = new Floor();
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        int rooms = (Integer) inv(layout, "roomCount");
        int entrance = -1, blood = -1, fairy = -1;
        for (int r = 0; r < rooms; r++) {
            String type = type(layout, r);
            String name = (String) inv(layout, "name", r);
            if ("ENTRANCE".equals(type) || "Entrance".equalsIgnoreCase(name)) entrance = r;
            if ("BLOOD".equals(type) || "Blood".equalsIgnoreCase(name)) blood = r;
            if ("FAIRY".equals(type) || "Fairy".equalsIgnoreCase(name)) fairy = r;
        }
        int start = (Integer) inv(layout, "currentRoom");
        f.startRoomName = start >= 0 ? (String) inv(layout, "name", start) : "none";
        if (entrance < 0 || blood < 0 || fairy < 0) {
            f.problem = "entrance " + entrance + ", blood " + blood + ", fairy " + fairy + " among " + rooms + " rooms";
            return f;
        }
        // Breadth-first over rooms: neighbours through a door cell between two room tiles.
        int[] prevRoom = new int[rooms];
        int[] prevDoor = new int[rooms];
        Arrays.fill(prevRoom, -2);
        prevRoom[entrance] = -1;
        ArrayDeque<Integer> queue = new ArrayDeque<>(List.of(entrance));
        int[][] dirs = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}};
        while (!queue.isEmpty()) {
            int room = queue.poll();
            for (int tile : (int[]) inv(layout, "tiles", room)) {
                int x = tile % 11, z = tile / 11;
                for (int[] d : dirs) {
                    int nx = x + 2 * d[0], nz = z + 2 * d[1];
                    if (nx < 0 || nx > 10 || nz < 0 || nz > 10) continue;
                    int door = (z + d[1]) * 11 + (x + d[0]);
                    int next = (Integer) inv(layout, "roomOfCell", nz * 11 + nx);
                    if (!(Boolean) inv(layout, "isDoor", door) || next < 0 || next == room || prevRoom[next] != -2) continue;
                    prevRoom[next] = room;
                    prevDoor[next] = door;
                    queue.add(next);
                }
            }
        }
        if (prevRoom[blood] == -2) {
            f.problem = "blood not reachable from the entrance";
            return f;
        }
        List<Integer> path = new ArrayList<>();
        for (int r = blood; r != -1; r = prevRoom[r]) {
            path.add(0, r);
        }
        StringBuilder names = new StringBuilder();
        for (int r : path) {
            names.append(names.length() == 0 ? "" : " > ").append((String) inv(layout, "name", r));
        }
        f.pathNames = names.toString();
        int at = path.indexOf(fairy);
        if (at <= 0 || at >= path.size() - 1) {
            f.problem = "the Entrance-to-Blood search does not pass the Fairy: " + f.pathNames;
            return f;
        }
        f.entryDoor = prevDoor[fairy];
        f.beforeRoomTile = ((int[]) inv(layout, "tiles", path.get(at - 1)))[0];
        f.fairyTile = ((int[]) inv(layout, "tiles", fairy))[0];
        int afterRoom = path.get(at + 1);
        f.afterRoomName = (String) inv(layout, "name", afterRoom);
        f.afterRoomTile = ((int[]) inv(layout, "tiles", afterRoom))[0];
        BlockPos db = (BlockPos) ModUnderTest.staticCall(LAYOUT, "doorBlock", new Class<?>[]{int.class},
                new Object[]{f.entryDoor});
        f.doorY = db.getY();
        f.doorBlock = db;
        f.doorCentre = new double[]{db.getX() + 0.5, db.getY(), db.getZ() + 0.5};
        // The neighbour tile of the door on the "before" side: the door cell minus the step into Fairy.
        int dx = (f.fairyTile % 11) - (f.entryDoor % 11);
        int dz = (f.fairyTile / 11) - (f.entryDoor / 11);
        f.towardFairy = new int[]{dx, dz};
        return f;
    }

    private static String type(Object layout, int room) {
        Object entry = inv(layout, "entry", room);
        if (entry == null) return "";
        try {
            Object t = entry.getClass().getField("type").get(entry);
            return t == null ? "" : t.toString().toUpperCase(Locale.ROOT);
        } catch (ReflectiveOperationException e) {
            return "";
        }
    }

    /** Seven blocks from the door on the side away from Fairy, a block over the door's floor. */
    private static double[] viewPoint(Floor f) {
        return new double[]{f.doorCentre[0] - f.towardFairy[0] * 7, f.doorY + 0.5, f.doorCentre[2] - f.towardFairy[1] * 7};
    }

    private static double[] roomCentre(Floor f, int tile) {
        BlockPos c = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class}, new Object[]{tile});
        return new double[]{c.getX() + 0.5, f.doorY + 0.5, c.getZ() + 0.5};
    }

    // ------------------------------------------------------------------------------------------------- acting

    private static void creativeFlying(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    // Creative: the view point can be inside a room's furniture, and survival would suffocate there.
                    sp.setGameMode(GameType.CREATIVE);
                    sp.getAbilities().mayfly = true;
                    sp.getAbilities().flying = true;
                    sp.onUpdateAbilities();
                }
            });
        });
        ctx.waitFor(mc -> mc.player.getAbilities().flying, 200);
    }

    /** Server teleport to {@code at}; with {@code look} set, he faces it (client rotation, as 143 does). */
    private static void place(ClientGameTestContext ctx, double[] at, double[] look) {
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
                double dy = look[1] + 2.0 - mc.player.getEyeY();
                double dz = look[2] - mc.player.getZ();
                mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
                mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
            }
        });
    }

    private static void setFairyDoor(ClientGameTestContext ctx, boolean on) {
        ctx.runOnClient(mc -> {
            ModUnderTest.set(ModUnderTest.config(CFG), "setFairyDoor", on);
            ModUnderTest.staticCall(FEATURE, "invalidateCache");
        });
    }

    /** The mod's own new-run path: FairyDoor sees a generation it has not seen. */
    private static void resetRun(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> setStaticInt(FAIRY, "generation", Integer.MIN_VALUE));
    }

    /** Wither Doors' own new-floor path: rescan every door cell (a door made wither after it was scanned). */
    private static void resetScanner(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> setStaticInt(FEATURE, "lastGeneration", Integer.MIN_VALUE));
    }

    private static boolean waitCleared(ClientGameTestContext ctx, int ticks) {
        for (int i = 0; i < ticks; i++) {
            if (ctx.computeOnClient(mc -> fairyCleared())) {
                return true;
            }
            ctx.waitTick();
        }
        return false;
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

    private Path shot(ClientGameTestContext ctx, String what) {
        String name = NAME + "-" + jarTag + "-" + what;
        Path taken = ctx.takeScreenshot(Report.fileName(name));
        Report.screenshot(NAME, taken);
        try {
            Files.createDirectories(SHOTS);
            Path copy = SHOTS.resolve(Report.fileName(name) + ".png");
            Files.copy(taken, copy, StandardCopyOption.REPLACE_EXISTING);
            println("screenshot " + copy);
        } catch (Exception e) {
            println("could not copy screenshot " + taken + ": " + e);
        }
        return taken;
    }

    /** {count, centroid x, centroid y, width, height}: pixels that changed from {@code base} towards magenta. */
    private static int[] magenta(Path shot, Path base) {
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(shot.toFile());
            java.awt.image.BufferedImage was = javax.imageio.ImageIO.read(base.toFile());
            int n = 0;
            long sx = 0, sy = 0;
            int w = Math.min(img.getWidth(), was.getWidth());
            int h = Math.min(img.getHeight(), was.getHeight());
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int a = img.getRGB(x, y), b = was.getRGB(x, y);
                    int r = (a >> 16) & 0xFF, g = (a >> 8) & 0xFF, bl = a & 0xFF;
                    int delta = Math.abs(r - ((b >> 16) & 0xFF)) + Math.abs(g - ((b >> 8) & 0xFF)) + Math.abs(bl - (b & 0xFF));
                    if (delta > 60 && r > 140 && bl > 140 && g + 60 < Math.min(r, bl)) {
                        n++;
                        sx += x;
                        sy += y;
                    }
                }
            }
            return new int[]{n, n == 0 ? -1 : (int) (sx / n), n == 0 ? -1 : (int) (sy / n), w, h};
        } catch (Exception e) {
            return new int[]{-1, -1, -1, 0, 0};
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

    private static void note(String name, String text) {
        Report.note(name, text);
    }

    private static String describe(List<double[]> boxes) {
        StringBuilder sb = new StringBuilder("[");
        for (double[] b : boxes) {
            sb.append(sb.length() == 1 ? "" : ", ").append(fmt(b));
        }
        return sb.append("]").toString();
    }

    private static String fmt(double[] b) {
        if (b == null) return "none";
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < b.length; i++) {
            sb.append(i == 0 ? "" : ",").append(String.format(Locale.US, "%.1f", b[i]));
        }
        return sb.append(")").toString();
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

    private static boolean staticBool(String cls, String field) {
        try {
            return Class.forName(cls).getField(field).getBoolean(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no " + cls + "." + field, e);
        }
    }

    private static void setStaticInt(String cls, String field, int value) {
        try {
            Field f = Class.forName(cls).getDeclaredField(field);
            f.setAccessible(true);
            f.setInt(null, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not set " + cls + "." + field, e);
        }
    }

    /** The Wither Doors settings this scenario touches, as one string, so the finally block puts them back. */
    private static String snapshotConfig() {
        Object c = ModUnderTest.config(CFG);
        return ModUnderTest.getBoolean(c, "isEnabled") + ";" + inv(c, "isFairyDoorRaw") + ";" + inv(c, "isThroughWallsRaw")
                + ";" + inv(c, "isShowAllDoorsRaw") + ";" + inv(c, "isCustomFillColor") + ";" + inv(c, "getFillOpacity")
                + ";" + inv(c, "getFairyDoorColor") + ";" + inv(c, "getRenderDistance") + ";" + inv(c, "getStyle");
    }

    private static void restoreConfig(String saved) {
        try {
            String[] p = saved.split(";");
            Object c = ModUnderTest.config(CFG);
            ModUnderTest.set(c, "setEnabled", Boolean.parseBoolean(p[0]));
            ModUnderTest.set(c, "setFairyDoor", Boolean.parseBoolean(p[1]));
            ModUnderTest.set(c, "setThroughWalls", Boolean.parseBoolean(p[2]));
            ModUnderTest.set(c, "setShowAllDoors", Boolean.parseBoolean(p[3]));
            ModUnderTest.set(c, "setCustomFillColor", Boolean.parseBoolean(p[4]));
            ModUnderTest.set(c, "setFillOpacity", Integer.parseInt(p[5]));
            ModUnderTest.set(c, "setFairyDoorColor", Integer.parseInt(p[6]));
            ModUnderTest.set(c, "setRenderDistance", Integer.parseInt(p[7]));
            Object style = ModUnderTest.enumValue(CFG + "$Style", p[8]);
            ModUnderTest.call(c, "setStyle", new Class<?>[]{style.getClass()}, new Object[]{style});
            inv(c, "save");
        } catch (Throwable t) {
            println("could not restore the Wither Doors settings: " + t);
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

    /** The published floor as this test reads it. */
    private static final class Floor {
        String problem;
        String startRoomName;
        String pathNames;
        String afterRoomName;
        int entryDoor = -1;
        int beforeRoomTile = -1;
        int fairyTile = -1;
        int afterRoomTile = -1;
        int doorY;
        BlockPos doorBlock;
        double[] doorCentre;
        int[] towardFairy;

        @Override
        public String toString() {
            return problem != null ? "PROBLEM " + problem : "path " + pathNames + ", fairy entered through door " + entryDoor
                    + " at " + Arrays.toString(doorCentre) + ", after Fairy: " + afterRoomName + ", started in " + startRoomName;
        }
    }
}

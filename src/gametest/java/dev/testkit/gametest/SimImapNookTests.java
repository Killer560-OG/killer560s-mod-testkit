package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * killer560 (2026-11-27 1:41 AM): "for some reason it couldnt find a route even through there is one." In Pirate (an L,
 * rotation 180, clay -10,-74) on the high spots an Auto Routes PATH node had left him on, (-64, 84, -132) and
 * (-57, 84, -131), six Interactive Map presses out of the room failed in 3-15 ms: the start-node warps ("Room hop 1 of 4
 * failed: no warp chain ... to -41,68,-89 (room 1, door 75)") and the room clicks ("no way to tile, then its block ... on
 * the floor graph - not trying room by room"), while a press to a block inside Pirate from the same spot flew 6 warps
 * (the fine room search) and presses from other spots flew 4-7.
 *
 * <p>The case rebuilds his floor around Pirate (same tiles, same rotation, so the same blocks): Pirate at tiles (8,4),
 * (10,4), (10,6), Beams west of its south tile through the same door cell 75, Entrance south, then Dueces and Fairy west
 * of Beams (his Tomioka and Diagonal are not captured) and Long Hall south of Beams (for his Melon). From each spot:
 * a start-node style press ({@code ClearExecutor.etherPathArrive}, exact) onto a block of a room three doors away, and a
 * room click ({@code AutoClearUtils.pathToRoom}) on another. Each must fly him there: he traveled, ended in that room,
 * no "[Interactive Map] Failed" line, no refused warp.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimImapNookTests implements FabricClientGameTest {

    static final String NAME = "1127-sim-imap-nook";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    private static final String EXECUTOR = "com.killer560.hub.livemap.autoclear.ClearExecutor";
    private static final String CLEAR_UTILS = "com.killer560.hub.livemap.autoclear.AutoClearUtils";
    private static final String TELEPORT_UTILS = "com.killer560.hub.livemap.autoclear.TeleportUtils";
    private static final int GRID = 11;

    /** His two spots (his log: "from (-63.50, 84.00, -131.50)" and the block -57, 84, -131), y as Hypixel's. */
    private static final double[][] SPOTS = {{-63.5, 84.0, -131.5}, {-56.5, 84.0, -130.5}};
    private static final int FAIRY_TILE = 6 * GRID + 4;
    private static final int LONG_HALL_TILE = 8 * GRID + 8;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
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
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> {
            mc.options.renderDistance().set(16);
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                    "setAutoPuzzlesMasterEnabled", false);
            Object map = ModUnderTest.config(LIVE_MAP_CONFIG);
            ModUnderTest.set(map, "setEnabled", true);
            ModUnderTest.set(map, "setInteractiveMapEnabled", false);
            ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class}, new Object[]{"gametest"});
        });
        List<String> failures = new ArrayList<>();
        try {
            String code = ctx.computeOnClient(mc -> floorCode());
            println("floor code " + code);
            SimPlannerGapsTests.buildFloor(ctx, NAME, code);
            body(ctx, failures);
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(EXECUTOR, "cancel"));
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
        if (failures.isEmpty()) {
            System.out.println("[" + NAME + "] PASS");
        } else {
            System.out.println("[" + NAME + "] FAIL - " + String.join("; ", failures));
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
    }

    /** His floor around Pirate, as a map code. Pirate is pasted so its DATABASE rotation is 180, as on his floor. */
    private static String floorCode() {
        String[] names = {"Entrance", "Pirate", "Beams", "Dueces", "Fairy", "Long Hall"};
        int cells = GRID * GRID;
        int[] room = new int[cells];
        int[] door = new int[cells];
        int[] rot = new int[cells];
        java.util.Arrays.fill(room, -1);
        Object pirate = ModUnderTest.staticCall(ROOM_LIBRARY, "get", new Class<?>[]{String.class}, new Object[]{"Pirate"});
        int capture = (Integer) ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomCaptureRotation", "of",
                new Class<?>[]{pirate.getClass()}, new Object[]{pirate});
        int pirateRot = Math.floorMod(180 - capture, 360);
        int[][] rooms = {{10, 8, 0}, {8, 4, 1}, {9, 4, 1}, {10, 4, 1}, {10, 5, 1}, {10, 6, 1}, {8, 6, 2}, {6, 6, 3},
                {4, 6, 4}, {8, 8, 5}};
        for (int[] r : rooms) {
            int i = r[1] * GRID + r[0];
            room[i] = r[2];
            rot[i] = r[2] == 1 ? pirateRot : 0;
        }
        // Entrance -> Pirate, Pirate -> Beams (his door 75), Beams -> Dueces -> Fairy, Beams -> Long Hall.
        door[7 * GRID + 10] = 4;
        door[6 * GRID + 9] = 1;
        door[6 * GRID + 7] = 1;
        door[6 * GRID + 5] = 1;
        door[7 * GRID + 8] = 1;
        try {
            Class<?> dec = Class.forName("com.killer560.hub.roomsim.MapCode$Decoded");
            Object d = dec.getConstructors()[0].newInstance(names, room, door, rot);
            return (String) ModUnderTest.staticCall("com.killer560.hub.roomsim.MapCode", "encodeDecoded",
                    new Class<?>[]{dec}, new Object[]{d});
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void body(ClientGameTestContext ctx, List<String> failures) {
        int off = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(LAYOUT, "simYOffset"));
        String clay = ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            int r = roomNamed(layout, "Pirate");
            if (r < 0) {
                return "not on the map";
            }
            int[] c = (int[]) ModUnderTest.call(layout, "clayRotation", new Class<?>[]{int.class}, new Object[]{r});
            return c == null ? "no clay" : c[0] + "," + c[1] + " rotation " + c[2];
        });
        println("sim y offset " + off + "; Pirate at clay " + clay + " (his floor: -10,-74 rotation 180)");
        if (!"-10,-74 rotation 180".equals(clay)) {
            failures.add("Pirate is at clay " + clay + ", not -10,-74 rotation 180 - not a reproduction");
            return;
        }
        for (int s = 0; s < SPOTS.length; s++) {
            Vec3 feet = new Vec3(SPOTS[s][0], SPOTS[s][1] + off, SPOTS[s][2]);
            String label = String.format(Locale.US, "spot %.1f,%.0f,%.1f", SPOTS[s][0], SPOTS[s][1], SPOTS[s][2]);
            // Spot 1: a start-node press into Fairy and a click on Long Hall; spot 2 the other way round.
            int exactTile = s == 0 ? FAIRY_TILE : LONG_HALL_TILE;
            int clickTile = s == 0 ? LONG_HALL_TILE : FAIRY_TILE;
            String exactRoom = s == 0 ? "Fairy" : "Long Hall";
            String clickRoom = s == 0 ? "Long Hall" : "Fairy";
            if (!SimPlannerGapsTests.place(ctx, feet)) {
                failures.add(label + ": could not put him there (" + ctx.computeOnClient(mc -> mc.player.position())
                        + ") - the Pirate capture differs from his room");
                continue;
            }
            BlockPos target = ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(TELEPORT_UTILS,
                    "etherwarpableInTile", new Class<?>[]{BlockPos.class, Vec3.class},
                    new Object[]{ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                            new Object[]{exactTile}), feet}));
            if (target == null) {
                failures.add(label + ": no etherwarpable block in " + exactRoom);
            } else {
                trip(ctx, label + " start-node press onto " + target.toShortString() + " in " + exactRoom, feet,
                        exactRoom, () -> ModUnderTest.staticCall(EXECUTOR, "etherPathArrive",
                                new Class<?>[]{BlockPos.class, Runnable.class}, new Object[]{target, null}), failures);
            }
            if (!SimPlannerGapsTests.place(ctx, feet)) {
                failures.add(label + ": could not put him back there");
                continue;
            }
            trip(ctx, label + " room click on " + clickRoom, feet, clickRoom, () -> {
                Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
                int r = roomNamed(layout, clickRoom);
                Object ok = ModUnderTest.staticCall(CLEAR_UTILS, "pathToRoom",
                        new Class<?>[]{layout.getClass(), int.class, int.class, int.class},
                        new Object[]{layout, r, clickTile, 0});
                if (!Boolean.TRUE.equals(ok)) {
                    System.out.println("[" + NAME + "] pathToRoom refused the click on " + clickRoom);
                }
            }, failures);
        }
    }

    private static int roomNamed(Object layout, String name) {
        int n = (Integer) ModUnderTest.call(layout, "roomCount", new Class<?>[]{}, new Object[]{});
        for (int r = 0; r < n; r++) {
            if (name.equals(ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r}))) {
                return r;
            }
        }
        return -1;
    }

    /** One press, judged on what happened: the executor ran, he traveled out of Pirate into {@code room}. */
    private static void trip(ClientGameTestContext ctx, String label, Vec3 feet, String room, Runnable press,
                             List<String> failures) {
        long mark = LogTap.mark();
        ctx.runOnClient(mc -> press.run());
        boolean busySeen = false;
        int ticks = 0;
        for (; ticks < 400; ticks++) {
            ctx.waitTicks(1);
            boolean busy = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isBusy"));
            busySeen |= busy;
            if (busySeen && !busy) {
                break;
            }
        }
        ctx.waitTicks(10);
        Vec3 end = ctx.computeOnClient(mc -> mc.player.position());
        AtomicReference<String> endRoom = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            int r = (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class, double.class},
                    new Object[]{end.x, end.z});
            endRoom.set(r < 0 ? "no room" : (String) ModUnderTest.call(layout, "name", new Class<?>[]{int.class},
                    new Object[]{r}));
        });
        int refused = 0;
        boolean failed = false;
        int running = 0;
        for (String l : LogTap.since(mark)) {
            refused += l.contains("[Sim] etherwarp refused") ? 1 : 0;
            failed |= l.contains("[Interactive Map] Failed") || l.contains("not trying room by room");
            running += l.contains("[Path] running ") ? 1 : 0;
            if (l.contains("[Path]") || l.contains("[Interactive Map]")) {
                String p = l.replaceAll("^.*?\\[(Path|Interactive Map)\\]", "[$1]");
                println("    " + label + " log: " + (p.length() > 400 ? p.substring(0, 400) + "..." : p));
            }
        }
        double travelled = feet.distanceTo(end);
        println(String.format(Locale.US, "%s: busy seen %s, %d tick(s), %d run line(s), traveled %.1f blocks, ended %s"
                + " in %s, %d refusal(s)", label, busySeen, ticks, running, travelled, end, endRoom.get(), refused));
        if (failed) {
            failures.add(label + ": the planner gave up (\"Failed\" / \"not trying room by room\")");
        }
        if (running == 0 || travelled < 10.0) {
            failures.add(String.format(Locale.US, "%s: no trip flew (%d run line(s), traveled %.1f blocks)", label,
                    running, travelled));
        }
        if (!room.equals(endRoom.get())) {
            failures.add(label + ": ended in " + endRoom.get() + ", not " + room);
        }
        if (refused > 0) {
            failures.add(label + ": the server refused " + refused + " warp(s)");
        }
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}

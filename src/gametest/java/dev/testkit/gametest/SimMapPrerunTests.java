package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 1101-map-prerun (mod doc-map-1101): killer560's to-do Doc, 2026-11-01, "PREDICTIVE MAP MODE": "If you dig the door in
 * green room before the run starts ... Sometimes i can break enough to see into the first room early. The map will
 * recognize this and update. Then once the run starts it bugs out the map entirely", "the name should be a bit more grey",
 * "If i can see through 1 room into another that middle room will stay grey".
 *
 * <p>A generated F7 sim floor. The sim gives no map before its run starts (HeldMap's stand-in is down during the
 * countdown, as Hypixel hands the map over at Mort's first line), which is the state the bug lives in.
 * <ul>
 * <li>1101-map-prerun-peek: countdown re-armed so the run has not started, no map; he stands in the Entrance (the start
 *     tile is recorded), then in the Entrance's doorway gap looking into the first room for up to 40 ticks, then looking
 *     back into the Entrance for 20. Pass: the predictor saw the first room and named it right, recorded the Entrance as
 *     the start tile, and NO prediction ever covered the Entrance tile.</li>
 * <li>1101-map-prerun-start: the countdown runs out (GO, the stand-in map arrives). Pass: within 3 ticks no prediction
 *     covers a tile the map shows opened, and the first room is still named right.</li>
 * <li>1101-map-seethrough: every set of rooms A, B, C in a straight line through two open doors (up to 10 middles); only A
 *     opened. From A, 3 blocks short of the A-B doorway, looking through B into C. Pass: every B is named right within 40
 *     ticks, and nothing is named wrong.</li>
 * <li>1101-map-predicted-gray: with a Hypixel-style map item (Entrance opened, the first room an unopened gray room), a
 *     fresh glance names the first room; the Dungeon Map HUD draws that name in #8A8A8A, grayer than the #AAAAAA of a
 *     room he has entered.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimMapPrerunTests implements FabricClientGameTest {

    private static final String NAME = "1101-map-prerun";
    private static final String R_PEEK = "1101-map-prerun-peek";
    private static final String R_START = "1101-map-prerun-start";
    private static final String R_SEE = "1101-map-seethrough";
    private static final String R_GRAY = "1101-map-predicted-gray";

    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_RUN = "com.killer560.hub.roomsim.SimRun";
    private static final String SIM_BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String HELD = "com.killer560.hub.livemap.HeldMap";
    private static final String PREDICTOR = "com.killer560.hub.livemap.predict.RoomPredictor";
    private static final String PCFG = "com.killer560.hub.livemap.predict.PredictiveRoomsConfig";
    private static final String MAP = "livemap.LiveMapConfig";
    private static final int PREDICTED_GRAY = 0xFF8A8A8A;

    private final List<String> all = new ArrayList<>();

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
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        List<AutoCloseable> restore = new ArrayList<>();
        try {
            ctx.runOnClient(mc -> {
                restore.add(0, Mod.with("hud.HudConfig", "AutoScale", false));
                restore.add(0, Mod.with(MAP, "Enabled", true));
                restore.add(0, Mod.with(MAP, "RoomLabels", 1));
                restore.add(0, Mod.with("mapping.MappingConfig", "ExtraInfoEnabled", false));
                restore.add(0, Mod.with("autopuzzles.AutoPuzzlesConfig", "AutoPuzzlesMasterEnabled", false));
                Object p = ModUnderTest.config(PCFG);
                ModUnderTest.set(p, "setEnabled", true);
                mc.options.guiScale().set(2);
            });
            body(ctx);
        } catch (Throwable t) {
            all.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> {
                for (AutoCloseable r : restore) {
                    try {
                        r.close();
                    } catch (Exception e) {
                        println("restore failed: " + e);
                    }
                }
            });
            leave(ctx);
        }
        if (!all.isEmpty()) {
            throw new AssertionError(String.join(" | ", all));
        }
        println("PASS - every case passed");
    }

    private void body(ClientGameTestContext ctx) {
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
        Floor f = ctx.computeOnClient(SimMapPrerunTests::readFloor);
        println("floor: " + f.names.size() + " rooms; Entrance " + (f.entrance < 0 ? "none" : f.names.get(f.entrance)
                + " " + java.util.Arrays.toString(f.tiles.get(f.entrance))));
        creativeFlying(ctx);
        if (f.entrance < 0) {
            row(R_PEEK, false, "premise: no Entrance on the floor");
            return;
        }
        int entranceTile = f.tiles.get(f.entrance)[0];
        int startTile6 = (entranceTile / 11 / 2) * 6 + (entranceTile % 11) / 2;
        // The Entrance's door into its first room (not Blood).
        int door = -1;
        int first = -1;
        for (Map.Entry<Integer, int[]> e : f.doorRooms.entrySet()) {
            int[] ab = e.getValue();
            int other = ab[0] == f.entrance ? ab[1] : ab[1] == f.entrance ? ab[0] : -1;
            if (other >= 0 && other != f.blood) {
                door = e.getKey();
                first = other;
                break;
            }
        }
        if (door < 0) {
            row(R_PEEK, false, "premise: the Entrance has no door into another room");
            return;
        }
        final int d0 = door;
        final int firstRoom = first;
        println("first room through door " + d0 + ": " + f.names.get(firstRoom));

        // ---- before the run: stood in the Entrance, the run re-armed (no map), the predictor fresh ----
        Report.caseStarted(R_PEEK);
        BlockPos ec = f.cellCenter(entranceTile);
        place(ctx, new double[]{ec.getX() + 0.5, f.doorY + 0.2, ec.getZ() + 0.5},
                new double[]{ec.getX() + 0.5, f.doorY + 1.0, ec.getZ() + 10.5});
        ctx.runOnClient(mc -> {
            ModUnderTest.staticCall(SIM_RUN, "reset");
            rearm(mc);
            ModUnderTest.staticCall(PREDICTOR, "reset");
        });
        ctx.waitTicks(3);
        boolean noMap = !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(HELD, "available"));
        Object st = ctx.computeOnClient(mc -> {
            try {
                return ModUnderTest.staticCall(PREDICTOR, "startTile");
            } catch (Throwable old) {
                return "no hook";
            }
        });
        println("pre-run: held map available " + !noMap + ", start tile " + st + " (Entrance is " + startTile6 + ")");
        double[] dc = f.doorCentre(d0);
        double[] u = f.toward(f.entrance, d0);
        Watch w = new Watch(f, entranceTile, firstRoom);
        // He digs the gate: on Hypixel the blocks break on his client and the server puts them back a tick or two later.
        // Client-side only, the same: the gate's infested chiseled stone bricks become air here, the server keeps them.
        List<BlockPos> dug = ctx.computeOnClient(mc -> {
            List<BlockPos> out = new ArrayList<>();
            BlockPos c = f.doorBlock(d0);
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    for (int dy = -2; dy <= 5; dy++) {
                        BlockPos p = c.offset(dx, dy, dz);
                        if (mc.level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.INFESTED_CHISELED_STONE_BRICKS)) {
                            out.add(p);
                            mc.level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                        }
                    }
                }
            }
            return out;
        });
        println("dug " + dug.size() + " gate block(s) client-side (the server keeps them)");
        // In the doorway gap (the eye outside every tile), looking into the first room.
        place(ctx, new double[]{dc[0], dc[1] + 0.2, dc[2]}, new double[]{dc[0] + u[0] * 14, dc[1] + 1.0, dc[2] + u[1] * 14});
        for (int t = 1; t <= 40; t++) {
            ctx.waitTick();
            if (t % 20 == 0) {
                ctx.runOnClient(SimMapPrerunTests::rearm);
            }
            w.sample(ctx);
            if (w.namedAt >= 0 && t >= w.namedAt + 5) {
                break;
            }
        }
        int namedPre = w.namedAt;
        // And back into the Entrance from the gap.
        place(ctx, new double[]{dc[0], dc[1] + 0.2, dc[2]}, new double[]{dc[0] - u[0] * 14, dc[1] + 0.5, dc[2] - u[1] * 14});
        for (int t = 1; t <= 20; t++) {
            ctx.waitTick();
            if (t % 10 == 0) {
                ctx.runOnClient(SimMapPrerunTests::rearm);
            }
            w.sample(ctx);
        }
        boolean stillPre = !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_RUN, "hasStarted"));
        // The server puts the gate back.
        ctx.runOnClient(mc -> {
            for (BlockPos p : dug) {
                mc.level.setBlock(p, net.minecraft.world.level.block.Blocks.INFESTED_CHISELED_STONE_BRICKS.defaultBlockState(), 3);
            }
        });
        int seen = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(PREDICTOR, "observationCount"));
        boolean startOk = st instanceof Integer i && i == startTile6;
        row(R_PEEK, noMap && stillPre && seen > 0 && namedPre >= 0 && w.wrong == null && w.onStart == null && startOk,
                "no map " + noMap + ", still before the run " + stillPre + ", " + seen + " block(s) seen; "
                        + f.names.get(firstRoom) + " named at tick " + namedPre + "; start tile " + st + " (want "
                        + startTile6 + "); prediction on the Entrance tile: " + (w.onStart == null ? "none" : w.onStart)
                        + (w.wrong == null ? "" : "; WRONG: " + w.wrong));

        // ---- the run starts: the map arrives ----
        Report.caseStarted(R_START);
        place(ctx, new double[]{ec.getX() + 0.5, f.doorY + 0.2, ec.getZ() + 0.5},
                new double[]{dc[0], dc[1] + 1.0, dc[2]});
        int go = -1;
        for (int t = 1; t <= 120; t++) {
            ctx.waitTick();
            w.sample(ctx);
            if (ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_RUN, "hasStarted"))) {
                go = t;
                break;
            }
        }
        ctx.waitTicks(3);
        Object[] after = ctx.computeOnClient(mc -> {
            boolean avail = (Boolean) ModUnderTest.staticCall(HELD, "available");
            String onOpened = null;
            String firstName = null;
            for (Object p : (List<?>) ModUnderTest.staticCall(PREDICTOR, "predictions")) {
                String pname = (String) inv(p, "name");
                List<Integer> tiles = new ArrayList<>();
                for (int t : (int[]) inv(p, "certainTiles")) {
                    tiles.add(t);
                }
                for (int t : (int[]) inv(p, "uncertainTiles")) {
                    tiles.add(t);
                }
                for (int t : tiles) {
                    if ((Boolean) ModUnderTest.staticCall(HELD, "roomOpened", new Class<?>[]{int.class}, new Object[]{t})) {
                        onOpened = pname + " on opened tile " + t;
                    }
                    if (f.roomAt(t) == firstRoom && f.names.get(firstRoom).equalsIgnoreCase(pname)) {
                        firstName = pname;
                    }
                }
            }
            return new Object[]{avail, onOpened, firstName};
        });
        boolean avail = (Boolean) after[0];
        row(R_START, go > 0 && avail && after[1] == null && after[2] != null && w.onStart == null,
                "GO at tick " + go + ", map available " + avail + "; prediction on an opened tile: "
                        + (after[1] == null ? "none" : after[1]) + "; " + f.names.get(firstRoom) + " still named: "
                        + (after[2] != null) + "; on the Entrance tile at any point: "
                        + (w.onStart == null ? "none" : w.onStart));

        seeThrough(ctx, f);
        grayName(ctx, f, entranceTile, d0, firstRoom);
    }

    // ------------------------------------------------------------------------------------------------- see-through

    private void seeThrough(ClientGameTestContext ctx, Floor f) {
        Report.caseStarted(R_SEE);
        // Every A, B, C in a straight line: from door A-B along its axis through B's tiles (and B's own connectors) to a
        // door B-C. Only A is opened - he stands in it - so B and C are both rooms he has not been in, as on Hypixel when
        // he looks through the room ahead into the one beyond it.
        List<int[]> lines = new ArrayList<>();
        for (Map.Entry<Integer, int[]> e1 : f.doorRooms.entrySet()) {
            for (int side = 0; side < 2; side++) {
                int a = e1.getValue()[side];
                int b = e1.getValue()[1 - side];
                if (b == f.blood || b == f.entrance || a == f.blood) {
                    continue;
                }
                int d1 = e1.getKey();
                int step = d1 % 11 % 2 == 1 ? (f.roomAt(d1 + 1) == b ? 1 : -1) : (f.roomAt(d1 + 11) == b ? 11 : -11);
                int cell = d1 + step;
                int across = -1;
                while (cell >= 0 && cell < 121 && f.roomAt(cell) == b) {
                    int next = cell + step;
                    if (next < 0 || next >= 121 || Math.abs((next % 11) - (cell % 11)) > 1) {
                        break;
                    }
                    if (f.doorRooms.containsKey(next)) {
                        across = next;
                        break;
                    }
                    cell = next;
                }
                if (across < 0) {
                    continue;
                }
                int[] cd = f.doorRooms.get(across);
                int c = cd[0] == b ? cd[1] : cd[0];
                if (c == a || c == f.blood || c == f.entrance) {
                    continue;
                }
                final int dd = across;
                boolean open = ctx.computeOnClient(mc -> mc.level.getBlockState(f.doorBlock(d1)).isAir()
                        && mc.level.getBlockState(f.doorBlock(dd)).isAir());
                if (open) {
                    lines.add(new int[]{a, b, c, d1, across});
                }
            }
        }
        if (lines.isEmpty()) {
            row(R_SEE, false, "premise: no three rooms in a line through two open doors on this floor");
            return;
        }
        int tried = 0;
        int named = 0;
        List<String> notNamed = new ArrayList<>();
        List<String> wrong = new ArrayList<>();
        java.util.Set<Integer> middles = new java.util.HashSet<>();
        for (int[] pick : lines) {
            if (tried >= 10 || !middles.add(pick[1])) {
                continue;
            }
            tried++;
            int a = pick[0];
            int b = pick[1];
            int c = pick[2];
            ctx.runOnClient(mc -> {
                ModUnderTest.staticCall(HELD, "resetSimVisits");
                ModUnderTest.staticCall(HELD, "simVisit", new Class<?>[]{int.class}, new Object[]{a});
                ModUnderTest.staticCall(PREDICTOR, "reset");
            });
            double[] dc = f.doorCentre(pick[3]);
            double[] dc2 = f.doorCentre(pick[4]);
            double[] u = f.toward(a, pick[3]);
            Watch w = new Watch(f, -1, b);
            Watch wc = new Watch(f, -1, c);
            place(ctx, new double[]{dc[0] - u[0] * 3, dc[1] + 0.2, dc[2] - u[1] * 3},
                    new double[]{dc2[0] + u[0] * 12, dc2[1] + 1.0, dc2[2] + u[1] * 12});
            for (int t = 1; t <= 40; t++) {
                ctx.waitTick();
                w.sample(ctx);
                wc.sample(ctx);
                if (w.namedAt >= 0 && wc.namedAt >= 0) {
                    break;
                }
            }
            int bt = f.tiles.get(b)[0];
            Object[] why = ctx.computeOnClient(mc -> new Object[]{
                    ModUnderTest.staticCall(PREDICTOR, "evidence", new Class<?>[]{int.class}, new Object[]{bt}),
                    ModUnderTest.staticCall(PREDICTOR, "standing", new Class<?>[]{int.class}, new Object[]{bt}),
                    ModUnderTest.staticCall(HELD, "roomState", new Class<?>[]{int.class}, new Object[]{bt})});
            List<?> standing = (List<?>) why[1];
            String line = f.names.get(a) + " -> " + f.names.get(b) + " (" + f.tiles.get(b).length + " tile(s)) -> "
                    + f.names.get(c) + ": middle named at tick " + w.namedAt + ", beyond at tick " + wc.namedAt + " (middle: map state "
                    + why[2] + ", " + why[0] + " block(s) seen, " + standing.size() + " placement(s) standing"
                    + (standing.isEmpty() ? "" : ", e.g. " + standing.subList(0, Math.min(3, standing.size()))) + ")";
            println("see-through " + line);
            if (w.namedAt >= 0) {
                named++;
            } else {
                notNamed.add(line);
            }
            if (w.wrong != null) {
                wrong.add(w.wrong);
            }
            if (wc.wrong != null) {
                wrong.add(wc.wrong);
            }
        }
        row(R_SEE, named == tried && wrong.isEmpty(), named + " of " + tried + " middle room(s) named right"
                + (notNamed.isEmpty() ? "" : "; not named: " + notNamed) + (wrong.isEmpty() ? "" : "; WRONG: " + wrong));
    }

    // ------------------------------------------------------------------------------------------------- gray name

    private void grayName(ClientGameTestContext ctx, Floor f, int entranceTile, int door, int firstRoom) {
        Report.caseStarted(R_GRAY);
        // A Hypixel-style map: the Entrance opened, the door and the first room unopened gray. Rooms of 16 at a pitch of
        // 20 from corner (5, 5) - the sim reports M7, whose corner the scanner takes from its table.
        byte[] px = new byte[128 * 128];
        int ecol = (entranceTile % 11) / 2;
        int erow = (entranceTile / 11) / 2;
        SimMapItemTests.room(px, ecol, erow, 30);
        for (int t : f.tiles.get(firstRoom)) {
            SimMapItemTests.room(px, (t % 11) / 2, (t / 11) / 2, 85);
        }
        int gx = door % 11;
        int gz = door / 11;
        if (gx % 2 == 1) {
            int[] o = SimMapItemTests.origin(gx / 2, gz / 2);
            for (int y = 5; y <= 10; y++) {
                for (int x = 16; x < 20; x++) {
                    SimMapItemTests.set(px, o[0] + x, o[1] + y, 85);
                }
            }
        } else {
            int[] o = SimMapItemTests.origin(gx / 2, gz / 2);
            for (int y = 16; y < 20; y++) {
                for (int x = 5; x <= 10; x++) {
                    SimMapItemTests.set(px, o[0] + x, o[1] + y, 85);
                }
            }
        }
        SimMapItemTests.giveMap(ctx, px);
        Boolean calibrated = false;
        for (int i = 0; i < 60 && !Boolean.TRUE.equals(calibrated); i++) {
            ctx.waitTicks(5);
            calibrated = SimMapItemTests.hook(ctx, "livemap.MapPainter", "mapItemCalibrated");
        }
        if (!Boolean.TRUE.equals(calibrated)) {
            row(R_GRAY, false, "premise: the painted dungeon map was never read");
            return;
        }
        ctx.runOnClient(mc -> {
            ModUnderTest.staticCall(PREDICTOR, "reset");
            Object p = ModUnderTest.config(PCFG);
            ModUnderTest.set(p, "setEnabled", true);
        });
        BlockPos ec = f.cellCenter(entranceTile);
        double[] dc = f.doorCentre(door);
        double[] u = f.toward(f.entrance, door);
        place(ctx, new double[]{dc[0] - u[0] * 2, dc[1] + 0.2, dc[2] - u[1] * 2},
                new double[]{dc[0] + u[0] * 14, dc[1] + 1.0, dc[2] + u[1] * 14});
        Watch w = new Watch(f, entranceTile, firstRoom);
        for (int t = 1; t <= 60 && w.namedAt < 0; t++) {
            ctx.waitTick();
            w.sample(ctx);
        }
        ctx.waitTicks(2);
        String firstWord = f.names.get(firstRoom).split(" ")[0];
        List<Object[]> texts = ctx.computeOnClient(SimMapPrerunTests::hudTexts);
        Integer colour = null;
        StringBuilder drawn = new StringBuilder();
        for (Object[] t : texts) {
            drawn.append('\'').append(t[0]).append('\'').append(String.format(Locale.ROOT, "#%08X ", (Integer) t[1]));
            if (firstWord.equals(t[0])) {
                colour = (Integer) t[1];
            }
        }
        println("HUD text: " + drawn);
        row(R_GRAY, w.namedAt >= 0 && colour != null && colour == PREDICTED_GRAY,
                f.names.get(firstRoom) + " named at tick " + w.namedAt + "; its name drawn in "
                        + (colour == null ? "nothing (not drawn)" : String.format(Locale.ROOT, "#%08X", colour))
                        + " (want #8A8A8A; an entered room's is #AAAAAA, the old predicted name #E0E0E0)");
    }

    /** Every text run the Dungeon Map HUD draws: {plain text, argb}. */
    private static List<Object[]> hudTexts(Minecraft mc) {
        Object element = Mod.staticCall("hud.HudElementRegistry", "byId", "live_map");
        try {
            Mod.staticCall("livemap.LiveMapFeature$LiveMapHudElement", "invalidateDrawCache");
        } catch (AssertionError old) {
            // no cache on this jar
        }
        GuiRenderState state = new GuiRenderState();
        GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
        g.pose().pushMatrix();
        try {
            g.pose().translate(20, 20);
            g.pose().scale(2f, 2f);
            Method render = Mod.cls("hud.HudElement").getMethod("render", GuiGraphicsExtractor.class, int.class, int.class);
            render.invoke(element, g, 0, 0);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("render of live_map threw: " + e.getCause(), e);
        } finally {
            g.pose().popMatrix();
        }
        List<Object[]> out = new ArrayList<>();
        int[] elements = new int[1];
        state.forEachElement(el -> elements[0]++, GuiRenderState.TraverseRange.ALL);
        out.add(new Object[]{"(elements " + elements[0] + ")", 0});
        state.forEachText(t -> {
            try {
                Field cf = GuiTextRenderState.class.getDeclaredField("color");
                cf.setAccessible(true);
                out.add(new Object[]{TextRuns.string(t), cf.getInt(t)});
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("GuiTextRenderState has no color", e);
            }
        });
        return out;
    }

    // ------------------------------------------------------------------------------------------------- watching

    /** Per tick: when {@code target} got named right, any wrong name on it, and any prediction on {@code startTile}. */
    private static final class Watch {
        final Floor f;
        final int startTile;
        final int target;
        int ticks;
        int namedAt = -1;
        String wrong;
        String onStart;

        Watch(Floor f, int startTile, int target) {
            this.f = f;
            this.startTile = startTile;
            this.target = target;
        }

        void sample(ClientGameTestContext ctx) {
            ticks++;
            Object[] r = ctx.computeOnClient(mc -> {
                String named = null;
                String bad = null;
                String start = null;
                for (Object p : (List<?>) ModUnderTest.staticCall(PREDICTOR, "predictions")) {
                    String pname = (String) inv(p, "name");
                    for (int t : (int[]) inv(p, "certainTiles")) {
                        int actual = f.roomAt(t);
                        if (actual == target && f.names.get(target).equalsIgnoreCase(pname)) {
                            named = pname;
                        } else if (actual == target) {
                            bad = pname + " on tile " + t + " (really " + f.names.get(target) + ")";
                        }
                        if (t == startTile) {
                            start = pname + " (certain)";
                        }
                    }
                    for (int t : (int[]) inv(p, "uncertainTiles")) {
                        if (t == startTile) {
                            start = pname + " (unsure)";
                        }
                    }
                }
                return new Object[]{named, bad, start};
            });
            if (r[0] != null && namedAt < 0) {
                namedAt = ticks;
            }
            if (r[1] != null && wrong == null) {
                wrong = (String) r[1];
            }
            if (r[2] != null && onStart == null) {
                onStart = r[2] + " at tick " + ticks;
            }
        }
    }

    private static void rearm(Minecraft mc) {
        ModUnderTest.staticCall(SIM_RUN, "begin", new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall(SIM_BUILDER, "entranceDoor")});
    }

    // ------------------------------------------------------------------------------------------------- floor

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
            if ("ENTRANCE".equals(type) || "Entrance".equalsIgnoreCase(f.names.get(r))) f.entrance = r;
            if ("BLOOD".equals(type) || "Blood".equalsIgnoreCase(f.names.get(r))) f.blood = r;
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
        BlockPos any = (BlockPos) ModUnderTest.staticCall(LAYOUT, "doorBlock", new Class<?>[]{int.class}, new Object[]{1});
        f.doorY = any.getY();
        return f;
    }

    private static final class Floor {
        final List<String> names = new ArrayList<>();
        final List<int[]> tiles = new ArrayList<>();
        final int[] roomOf = new int[121];
        final Map<Integer, int[]> doorRooms = new LinkedHashMap<>();
        int entrance = -1;
        int blood = -1;
        int doorY;

        int roomAt(int tile) {
            return tile >= 0 && tile < 121 ? roomOf[tile] : -1;
        }

        BlockPos doorBlock(int idx) {
            return (BlockPos) ModUnderTest.staticCall(LAYOUT, "doorBlock", new Class<?>[]{int.class}, new Object[]{idx});
        }

        BlockPos cellCenter(int idx) {
            return (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class}, new Object[]{idx});
        }

        double[] doorCentre(int idx) {
            BlockPos b = doorBlock(idx);
            return new double[]{b.getX() + 0.5, b.getY(), b.getZ() + 0.5};
        }

        /** Unit step {x, z} from room {@code from}'s side of door {@code d} toward the other side. */
        double[] toward(int from, int d) {
            int gx = d % 11;
            int gz = d / 11;
            int side = gx % 2 == 1 ? roomOf[gz * 11 + gx - 1] : roomOf[(gz - 1) * 11 + gx];
            int sign = side == from ? 1 : -1;
            return gx % 2 == 1 ? new double[]{sign, 0} : new double[]{0, sign};
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

    /** Server teleport to {@code at}, facing the exact point {@code look}. */
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
            double dx = look[0] - mc.player.getX();
            double dy = look[1] - mc.player.getEyeY();
            double dz = look[2] - mc.player.getZ();
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
            mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        });
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

package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.LeverClickJudge;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * killer560, 2026-10-18 (Mod Only Test, a real Hypixel run): "The room guesser worked perfect for regular rooms but it
 * didnt work on water board when I tested it. Also my auto water board failed to flick a lever during the test I just did."
 *
 * <ul>
 * <li>{@code 1018-waterboard-predict}: Predictive Rooms must name Water Board from a neighbouring room's doorway, as the
 *     sim builds it (three colour layers under the walkway pushed out, unlike the capture's frame).</li>
 * <li>{@code 1018-waterboard-predict-board}: the same after the board behind the glass has been changed the way another
 *     run's board is: every lever's gates moved ({@code SimWaterPuzzle.toggleGate}, the sim's own piston slots) and the
 *     board planes (relative z 26..29) mirrored across the room's middle - the same blocks in another layout, with the
 *     identifier block where a mirrored board has it. His log: "every placement ruled out - 330 block(s) seen, 0 of 412
 *     placement(s) standing"; every Water Board capture there is (bundled, Mod Only Test's, Map Logger's) holds the same
 *     frame of board 0, so a run on another board is what the sim never showed.</li>
 * <li>{@code 1018-waterboard-lever}: Auto Water Board with a server that judges a lever click by the look it last heard
 *     ({@link LeverClickJudge}, GrimAC's RotationPlace) and drops the first warp after the third lever click (his
 *     19:25:14 warp, which got no landing). Every lever click the solver counted must have flipped that lever on the server
 *     (its powered state changed), none refused, and after the dropped warp the auto must click the next lever and go on
 *     to the one after it. His log: the warp re-sent 42 ticks later, and on the tick after its landing the lever clicked
 *     with the server still holding the warp's look - "22 tick(s) late", then "43".</li>
 * </ul>
 */
public final class SimWaterBoard1018Tests {

    private SimWaterBoard1018Tests() {
    }

    private static final String SOLVER = "com.killer560.hub.puzzlesolvers.WaterSolverFeature";

    // ============================================================================================ lever

    public static final class Lever extends SimPuzzleSolveTests.Base {
        public Lever() {
            super(new SimPuzzleSolveTests.Spec("waterboard-lever", "Water Board", "setAutoWaterEnabled",
                    "isAutoWaterEnabled", "WaterSolverConfig", "SimWaterPuzzle", 0, false, 60,
                    SimPuzzleSolveTests.Approach.NONE, "WaterSolverFeature.nextClick",
                    "WaterSolverFeature.getCountedClicks", "WaterSolverFeature.getOpenedWaterTick",
                    "WaterSolverFeature.getTickCounter"));
        }

        @Override
        String scenarioName() {
            return "1018-waterboard-lever";
        }

        @Override
        SolveExtra extra() {
            return new LeverExtra();
        }
    }

    /** One lever click as the solver counted it: the client tick, where, and its time. */
    private record Click(long tick, String pos, double time) {
    }

    private static volatile boolean recording;
    private static boolean hooked;
    private static long ended;
    private static int prevCount;
    private static Object prevNext;
    private static final List<Click> CLICKS = new ArrayList<>();

    private static void onEnd() {
        if (!recording) {
            return;
        }
        ended++;
        int count;
        Object next;
        try {
            count = (Integer) ModUnderTest.staticCall(SOLVER, "getCountedClicks");
            next = ModUnderTest.staticCall(SOLVER, "nextClick");
        } catch (Throwable t) {
            return;
        }
        if (count > prevCount && prevNext != null) {
            BlockPos pos = (BlockPos) ModUnderTest.call(prevNext, "pos", new Class<?>[0], new Object[0]);
            double time = (Double) ModUnderTest.call(prevNext, "time", new Class<?>[0], new Object[0]);
            synchronized (CLICKS) {
                CLICKS.add(new Click(ended, pos.toShortString(), time));
            }
        }
        prevCount = count;
        prevNext = next;
    }

    private static final class LeverExtra extends SolveExtra {
        private boolean installed;
        private String summary = "not evaluated";

        @Override
        void beforeAutoOn(ClientGameTestContext ctx) {
            if (installed) {
                return;
            }
            installed = true;
            ctx.runOnClient(mc -> {
                if (!hooked) {
                    hooked = true;
                    ClientTickEvents.END_CLIENT_TICK.register(c -> onEnd());
                }
                synchronized (CLICKS) {
                    CLICKS.clear();
                }
                ended = 0;
                prevCount = (Integer) ModUnderTest.staticCall(SOLVER, "getCountedClicks");
                prevNext = ModUnderTest.staticCall(SOLVER, "nextClick");
                LeverClickJudge.reset();
                LeverClickJudge.judgeLook = true;
                LeverClickJudge.dropUseAfterLevers = 3;
                LeverClickJudge.enabled = true;
                recording = true;
            });
        }

        @Override
        String verdict(ClientGameTestContext ctx, int solvedAt) {
            List<Click> clicks;
            List<LeverClickJudge.Flip> flips;
            List<String> refused;
            int dropped;
            int droppedAt;
            synchronized (CLICKS) {
                clicks = new ArrayList<>(CLICKS);
            }
            synchronized (LeverClickJudge.class) {
                recording = false;
                LeverClickJudge.enabled = false;
                flips = new ArrayList<>(LeverClickJudge.FLIPS);
                refused = new ArrayList<>(LeverClickJudge.REFUSED);
                dropped = LeverClickJudge.usesDropped;
                droppedAt = LeverClickJudge.droppedAtServerTick;
            }
            List<String> resends = LogTap.since(mark).stream().filter(l -> l.contains("sending the use again")).toList();
            for (Click c : clicks) {
                SimPuzzleRoundTests.say(name, String.format(Locale.US, "solver counted a click at %s (time %.1f s) on tick %d",
                        c.pos(), c.time(), c.tick()));
            }
            for (LeverClickJudge.Flip f : flips) {
                SimPuzzleRoundTests.say(name, String.format(Locale.US, "server: lever %s %s -> %s (server tick %d)%s",
                        f.pos().toShortString(), f.before() ? "on" : "off", f.after() ? "on" : "off", f.serverTick(),
                        droppedAt >= 0 && f.serverTick() > droppedAt ? " [after the dropped warp]" : ""));
            }
            for (String r : refused) {
                SimPuzzleRoundTests.say(name, "server: " + r);
            }
            for (String r : resends) {
                SimPuzzleRoundTests.say(name, "mod: " + r.trim());
            }
            List<String> bad = new ArrayList<>();
            if (dropped != 1) {
                bad.add("premise: " + dropped + " warp(s) dropped (wanted the one after the third lever click)");
            }
            for (String r : refused) {
                bad.add("REFUSED " + r);
            }
            // Every counted click flipped its lever, in order.
            int n = Math.min(clicks.size(), flips.size());
            for (int i = 0; i < clicks.size(); i++) {
                Click c = clicks.get(i);
                if (i >= flips.size()) {
                    bad.add("click " + (i + 1) + " at " + c.pos() + " never flipped a lever on the server");
                    continue;
                }
                LeverClickJudge.Flip f = flips.get(i);
                if (!f.pos().toShortString().equals(c.pos())) {
                    bad.add("click " + (i + 1) + " at " + c.pos() + " but the server's flip " + (i + 1) + " was "
                            + f.pos().toShortString());
                } else if (f.before() == f.after()) {
                    bad.add("click " + (i + 1) + " at " + c.pos() + " left the lever " + (f.after() ? "on" : "off"));
                }
            }
            if (flips.size() > clicks.size()) {
                bad.add(flips.size() + " server flips for " + clicks.size() + " counted click(s)");
            }
            // After the dropped warp: the next lever flipped, and the auto went on to another one after it.
            int afterDrop = 0;
            List<String> leversAfter = new ArrayList<>();
            for (LeverClickJudge.Flip f : flips) {
                if (droppedAt >= 0 && f.serverTick() > droppedAt) {
                    afterDrop++;
                    leversAfter.add(f.pos().toShortString());
                }
            }
            boolean movedOn = leversAfter.size() >= 2 && !leversAfter.get(0).equals(leversAfter.get(1));
            if (dropped == 1 && !movedOn) {
                bad.add("after the dropped warp the server flipped " + leversAfter + " - not the next lever and then another");
            }
            if (clicks.size() < 4) {
                bad.add("only " + clicks.size() + " click(s) counted - nothing to judge");
            }
            summary = String.format(Locale.US, "%d counted click(s), %d server flip(s) (%d after the dropped warp: %s), "
                            + "%d refused, %d warp(s) dropped, %d resend line(s)", clicks.size(), flips.size(), afterDrop,
                    leversAfter, refused.size(), dropped, resends.size());
            SimPuzzleRoundTests.say(name, summary);
            return bad.isEmpty() ? null : String.join("; ", bad);
        }

        @Override
        String summary() {
            return summary;
        }
    }

    // ============================================================================================ predict

    @dev.testkit.harness.RequiresMod("killer560smod")
    public static final class Predict implements FabricClientGameTest {
        private static final String NAME = "1018-waterboard-predict";
        private static final String R_BOARD = "1018-waterboard-predict-board";
        private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
        private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
        private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
        private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
        private static final String HELD = "com.killer560.hub.livemap.HeldMap";
        private static final String PREDICTOR = "com.killer560.hub.livemap.predict.RoomPredictor";
        private static final String PCFG = "com.killer560.hub.livemap.predict.PredictiveRoomsConfig";
        private static final String WATER = "com.killer560.hub.roomsim.puzzles.SimWaterPuzzle";
        private static final String ROOM = "Water Board";
        /** A glance: half a second from 2 blocks short of the doorway, one second from 10. */
        private static final int NEAR_TICKS = 10;
        private static final int FAR_TICKS = 20;
        private static final int WATCH_TICKS = 100;

        private final List<String> failures = new ArrayList<>();
        private final AtomicInteger mirrored = new AtomicInteger(-1);
        /** The right placement's key ("Water Board@rot@ax,az"), from the as-built glance. */
        private String wbKey;

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
            ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
            try {
                body(ctx);
            } catch (Throwable t) {
                failures.add("scenario threw: " + t);
                t.printStackTrace(System.out);
            } finally {
                leave(ctx);
            }
            if (!failures.isEmpty()) {
                throw new AssertionError(String.join(" | ", failures));
            }
            System.out.println("[" + NAME + "] PASS - every case passed");
        }

        private void body(ClientGameTestContext ctx) {
            ctx.runOnClient(mc -> {
                ModUnderTest.set(ModUnderTest.config(PCFG), "setEnabled", true);
                ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                        "setAutoPuzzlesMasterEnabled", false);
            });
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                    new Object[]{"gametest"}));
            // A floor with Water Board pinned to a middle cell (the designer's pin), laid out until the pin is kept.
            String code = null;
            for (int attempt = 0; attempt < 12 && code == null; attempt++) {
                final int cell = attempt % 2 == 0 ? 2 * 6 + 2 : 3 * 6 + 3;
                code = ctx.computeOnClient(mc -> {
                    Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
                    Map<Integer, String> pins = new HashMap<>();
                    pins.put(cell, ROOM);
                    Object p = ModUnderTest.staticCall(FLOOR_GEN, "plan",
                            new Class<?>[]{floor.getClass(), int.class, int.class, Map.class},
                            new Object[]{floor, 3, 4, pins});
                    if (p == null) {
                        return null;
                    }
                    Object decoded = ModUnderTest.call(p, "decoded", new Class<?>[]{}, new Object[]{});
                    String[] names = (String[]) ModUnderTest.call(decoded, "nameTable", new Class<?>[]{}, new Object[]{});
                    for (String n : names) {
                        if (ROOM.equals(n)) {
                            return (String) ModUnderTest.call(p, "code", new Class<?>[]{}, new Object[]{});
                        }
                    }
                    return null;
                });
            }
            if (code == null) {
                row(NAME, false, "premise: no floor kept Water Board in 12 plans");
                return;
            }
            final String floorCode = code;
            long before = Scenario.simBuildCount(ctx);
            ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimWorld", "open",
                    new Class<?>[]{Minecraft.class, String.class, java.util.function.Consumer.class, String.class},
                    new Object[]{mc, floorCode, (java.util.function.Consumer<Minecraft>) c -> ModUnderTest.staticCall(
                            "com.killer560.hub.roomsim.SimBuilder", "build", new Class<?>[]{Minecraft.class, String.class},
                            new Object[]{c, floorCode}), "1018-waterboard-predict"})));
            Scenario.awaitSimBuild(ctx, before);
            ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
            ctx.waitFor(mc -> mc.player != null && mc.player.onGround()
                    && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir(), 2400);
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> {
                var server = mc.getSingleplayerServer();
                server.execute(() -> server.getCommands().performPrefixedCommand(
                        server.createCommandSourceStack().withSuppressedOutput(), "time set noon"));
            });
            Floor f = ctx.computeOnClient(Predict::readFloor);
            int wb = f.names.indexOf(ROOM);
            String armed = LogTap.since(0).stream().filter(l -> l.contains("puzzle room(s) armed")).reduce((a, b) -> b)
                    .orElse("(no 'armed' line)");
            say("floor: " + f.names.size() + " rooms; Water Board is room " + wb + " on tile(s) "
                    + (wb < 0 ? "-" : java.util.Arrays.toString(f.tiles.get(wb))) + "; " + armed.trim());
            if (wb < 0) {
                row(NAME, false, "premise: Water Board is not on the built floor");
                return;
            }
            List<int[]> doors = new ArrayList<>();
            for (Map.Entry<Integer, int[]> e : f.doorRooms.entrySet()) {
                int a = e.getValue()[0];
                int b = e.getValue()[1];
                int other = a == wb ? b : b == wb ? a : -1;
                if (other >= 0 && other != f.blood) {
                    doors.add(new int[]{e.getKey(), other, wb});
                }
            }
            if (doors.isEmpty()) {
                row(NAME, false, "premise: Water Board has no doorway to a room other than Blood");
                return;
            }
            creativeFlying(ctx);
            for (int[] d : doors) {
                openDoor(ctx, f.doorBlock(d[0]));
            }
            ctx.waitTicks(5);
            // The room has one real entrance; a floor may join it to another room through a wall the room keeps (the
            // sim leaves it shut), and nothing of the room shows through that. The doorway a look gets in through is the
            // one to judge: a short look from each, the one that sees the most of the room.
            int[] door = null;
            int best = -1;
            for (int[] d : doors) {
                ctx.runOnClient(mc -> ModUnderTest.staticCall(HELD, "resetSimVisits"));
                ctx.runOnClient(mc -> ModUnderTest.staticCall(PREDICTOR, "reset"));
                double[] dc = f.doorCentre(d[0]);
                double[] u = f.toward(d[1], d[0]);
                double[] lk = new double[]{dc[0] + u[0] * 12, dc[1] + 1.0, dc[2] + u[1] * 12};
                place(ctx, new double[]{dc[0] - u[0] * 2, dc[1] + 0.2, dc[2] - u[1] * 2}, lk);
                ctx.waitFor(mc -> mc.level.isLoaded(BlockPos.containing(lk[0], lk[1], lk[2])), 400);
                ctx.waitTicks(10);
                int seen = 0;
                for (int tile : f.tiles.get(wb)) {
                    seen += ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(PREDICTOR, "evidence",
                            new Class<?>[]{int.class}, new Object[]{tile}));
                }
                say("door " + d[0] + " to " + f.names.get(d[1]) + ": " + seen + " of the room's block(s) seen in 10 ticks");
                if (seen > best) {
                    best = seen;
                    door = d;
                }
            }
            if (best <= 0) {
                row(NAME, false, "premise: no doorway of Water Board shows any of it (" + doors.size() + " tried)");
                return;
            }
            Report.caseStarted(NAME);
            String near = glances(ctx, f, door);
            row(NAME, near == null, near == null ? "named from the doorway (as built)" : near);
            boardCase(ctx, f, door);
        }

        /** The door block opened, as a player clearing to it finds it (a wither door would stop every ray). */
        private static void openDoor(ClientGameTestContext ctx, BlockPos db) {
            ctx.runOnClient(mc -> {
                var server = mc.getSingleplayerServer();
                server.execute(() -> {
                    for (int dy = 0; dy <= 3; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                BlockPos p = db.offset(dx, dy, dz);
                                var st = server.overworld().getBlockState(p);
                                String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
                                if (id.equals("coal_block") || id.equals("red_terracotta") || id.equals("barrier")) {
                                    server.overworld().setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
                                }
                            }
                        }
                    }
                });
            });
        }

        /** Another run's board: every gate moved and the board planes mirrored; then the same glances. */
        private void boardCase(ClientGameTestContext ctx, Floor f, int[] door) {
            Report.caseStarted(R_BOARD);
            String moved = ctx.computeOnClient(mc -> {
                var server = mc.getSingleplayerServer();
                StringBuilder sb = new StringBuilder();
                try {
                    Class<?> lever = Class.forName("com.killer560.hub.puzzlesolvers.WaterSolverFeature$LeverBlock");
                    Method toggle = Class.forName(WATER).getDeclaredMethod("toggleGate",
                            net.minecraft.server.MinecraftServer.class, lever);
                    toggle.setAccessible(true);
                    for (Object l : lever.getEnumConstants()) {
                        if (!"WATER".equals(String.valueOf(l))) {
                            toggle.invoke(null, server, l);
                            sb.append(l).append(' ');
                        }
                    }
                    // The board planes behind the front glass (relative z 26..29, y 59..89) mirrored across the room's
                    // middle (relative x 15): the same blocks laid out another way, as another layout is - the four
                    // identifier spots themselves sit mirrored, (14, 7x, 27) and (16, 78, 27).
                    Method at = Class.forName(WATER).getDeclaredMethod("at", int.class, int.class, int.class);
                    at.setAccessible(true);
                    List<BlockPos[]> swaps = new ArrayList<>();
                    for (int rz = 26; rz <= 29; rz++) {
                        for (int ry = 59; ry <= 89; ry++) {
                            for (int rx = 1; rx < 15; rx++) {
                                swaps.add(new BlockPos[]{(BlockPos) at.invoke(null, rx, ry, rz),
                                        (BlockPos) at.invoke(null, 30 - rx, ry, rz)});
                            }
                        }
                    }
                    AtomicInteger changed = new AtomicInteger();
                    server.execute(() -> {
                        var level = server.overworld();
                        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                        for (BlockPos[] s : swaps) {
                            var a = level.getBlockState(s[0]);
                            var b = level.getBlockState(s[1]);
                            // The board's water is left out (the predictor never reads fluids): mirrored, its sources
                            // flowed out through the room and the doorway and carried the player off his spot, so a
                            // later glance saw nothing (fix jar, 2026-10-18 20:29).
                            if (!a.getFluidState().isEmpty()) {
                                a = air;
                            }
                            if (!b.getFluidState().isEmpty()) {
                                b = air;
                            }
                            if (a != b) {
                                changed.incrementAndGet();
                            }
                            level.setBlock(s[0], b, 2 | 16);
                            level.setBlock(s[1], a, 2 | 16);
                        }
                        mirrored.set(changed.get());
                    });
                    sb.append("gates toggled; board planes mirrored across relative x 15 (")
                            .append(swaps.size()).append(" pairs)");
                } catch (ReflectiveOperationException e) {
                    return "could not change the board: " + e;
                }
                return sb.toString();
            });
            ctx.waitFor(mc -> mirrored.get() >= 0, 200);
            say("board changed: " + moved + "; " + mirrored.get() + " pair(s) differed and were swapped");
            if (mirrored.get() < 50) {
                row(R_BOARD, false, "premise: the mirror changed only " + mirrored.get() + " block pair(s)");
                return;
            }
            ctx.waitTicks(20);
            String board = moved.startsWith("could not") ? moved : glances(ctx, f, door);
            row(R_BOARD, board == null, board == null ? "named from the doorway with another board behind the glass" : board);
        }

        /** A near and a far glance; null when both named Water Board in time. */
        private String glances(ClientGameTestContext ctx, Floor f, int[] door) {
            List<String> bad = new ArrayList<>();
            for (double[] g : new double[][]{{2, NEAR_TICKS}, {10, FAR_TICKS}}) {
                ctx.runOnClient(mc -> ModUnderTest.staticCall(HELD, "resetSimVisits"));
                ctx.runOnClient(mc -> ModUnderTest.staticCall(PREDICTOR, "reset"));
                String r = glance(ctx, f, door, g[0], (int) g[1]);
                if (r != null) {
                    bad.add(r);
                }
            }
            return bad.isEmpty() ? null : String.join("; ", bad);
        }

        /** Stands {@code back} blocks short of the doorway in the neighbour, looks into Water Board; null when named in time. */
        private String glance(ClientGameTestContext ctx, Floor f, int[] t, double back, int limit) {
            double[] dc = f.doorCentre(t[0]);
            double[] u = f.toward(t[1], t[0]);
            double[] at = new double[]{dc[0] - u[0] * back, dc[1] + 0.2, dc[2] - u[1] * back};
            double[] look = new double[]{dc[0] + u[0] * 12, dc[1] + 1.0, dc[2] + u[1] * 12};
            place(ctx, at, look);
            ctx.waitFor(mc -> mc.level.isLoaded(BlockPos.containing(look[0], look[1], look[2])), 400);
            int[] tiles = f.tiles.get(t[2]);
            int named = -1;
            String wrong = null;
            int evidence = 0;
            List<?> standing = List.of();
            for (int tick = 1; tick <= limit + WATCH_TICKS && named < 0; tick++) {
                ctx.waitTick();
                Object[] r = ctx.computeOnClient(mc -> {
                    int ev = 0;
                    for (int tile : tiles) {
                        ev += (Integer) ModUnderTest.staticCall(PREDICTOR, "evidence", new Class<?>[]{int.class},
                                new Object[]{tile});
                    }
                    String hit = null;
                    String bad = null;
                    for (Object p : (List<?>) ModUnderTest.staticCall(PREDICTOR, "predictions")) {
                        String pname = (String) inv(p, "name");
                        for (int tile : (int[]) inv(p, "certainTiles")) {
                            if (f.roomAt(tile) == t[2]) {
                                if (ROOM.equalsIgnoreCase(pname)) {
                                    hit = pname;
                                } else {
                                    bad = pname;
                                }
                            }
                        }
                    }
                    return new Object[]{ev, hit, bad, ModUnderTest.staticCall(PREDICTOR, "standing",
                            new Class<?>[]{int.class}, new Object[]{tiles[0]})};
                });
                evidence = (Integer) r[0];
                standing = (List<?>) r[3];
                if (r[2] != null && wrong == null) {
                    wrong = (String) r[2];
                }
                if (r[1] != null) {
                    named = tick;
                }
            }
            String line = String.format(Locale.US, "glance from %.0f block(s) inside %s: %d of Water Board's block(s) seen, "
                            + "named at tick %s (must be by %d), %d placement(s) standing %s%s", back, f.names.get(t[1]),
                    evidence, named < 0 ? "never" : String.valueOf(named), limit, standing.size(),
                    standing.size() <= 4 ? standing.toString() : "", wrong == null ? "" : ", WRONG: " + wrong);
            say(line);
            if (standing.size() == 1) {
                String s0 = String.valueOf(standing.get(0));
                wbKey = s0.substring(0, s0.lastIndexOf(' ')); // "Water Board@rot@ax,az matches/mismatches"
            }
            boolean pass = wrong == null && named >= 0 && named <= limit;
            if (!pass && wbKey != null) {
                // What the right placement disagreed on (mod hook, absent on older jars).
                try {
                    List<?> dis = ctx.computeOnClient(mc -> (List<?>) ModUnderTest.staticCall(PREDICTOR, "disagreements",
                            new Class<?>[]{String.class, int.class}, new Object[]{wbKey, 30}));
                    say(wbKey + " disagrees on " + dis.size() + " block(s):");
                    for (Object d : dis) {
                        say("  " + d);
                    }
                } catch (Throwable e) {
                    say("(no disagreements hook on this jar)");
                }
            }
            return pass ? null : line;
        }

        // -------------------------------------------------------------------------------------- floor

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
                        Object tp = entry.getClass().getField("type").get(entry);
                        type = tp == null ? "" : tp.toString().toUpperCase(Locale.ROOT);
                    } catch (ReflectiveOperationException ignored) {
                        // no type
                    }
                }
                if ("BLOOD".equals(type) || "Blood".equalsIgnoreCase(f.names.get(r))) {
                    f.blood = r;
                }
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
            return f;
        }

        private static final class Floor {
            final List<String> names = new ArrayList<>();
            final List<int[]> tiles = new ArrayList<>();
            final int[] roomOf = new int[121];
            final Map<Integer, int[]> doorRooms = new LinkedHashMap<>();
            int blood = -1;

            int roomAt(int tile) {
                return tile >= 0 && tile < 121 ? roomOf[tile] : -1;
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
        }

        // -------------------------------------------------------------------------------------- acting

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

        private void row(String name, boolean pass, String detail) {
            System.out.println("[" + name + "] " + (pass ? "PASS" : "FAIL") + " - " + detail);
            Report.caseFinished(name, pass ? "PASS" : "FAIL", "n/a (singleplayer)", detail, List.of(), List.of());
            SuiteVerdict.finished(name);
            if (!pass) {
                failures.add(name + ": " + detail);
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

        private static void say(String line) {
            System.out.println("[" + NAME + "] " + line);
        }
    }
}

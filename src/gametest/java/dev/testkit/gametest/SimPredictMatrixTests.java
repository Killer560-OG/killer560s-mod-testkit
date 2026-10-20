package dev.testkit.gametest;

import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 1020-predict-matrix: Predictive Rooms measured over a matrix of glances (killer560, 2026-10-20: predict "as fast as
 * possible" and "from as far away as possible"). On each of {@code -PpredictFloors} (default 3) freshly generated F7 sim
 * floors, every doorway between two rooms is looked through from both sides, from several distances back
 * ({@link #BACKS}) and two sideways offsets, with the eye placed by the predictor's own {@code tickFrom} test hook - the
 * player is parked on a seam where four tiles meet and never moves, so one client tick of the predictor runs per glance
 * tick and a whole floor's glances fit in a few minutes. Each glance starts from a reset predictor with the room he stands
 * in and the Entrance opened ("fresh"), or with every room but the one looked into opened ("late"), and runs
 * {@link #TICKS} predictor ticks.
 *
 * <p>Per glance: the tick its first block is seen, the tick it is named right, any wrong name anywhere on the map during
 * it, and the nanoseconds each predictor tick took. Every variant of {@code -PpredictVariants} (";"-separated, each
 * "label:KEY=value,KEY=value" through {@code RoomPredictor.tune}, "base" = as shipped) runs over the SAME glances, so the
 * variants are paired. A CSV of every glance goes to {@code -PpredictOut} (default the report dir).
 *
 * <p>Verdict (for the shipped tuning, the first variant): no wrong name in any glance; every room whose doorway is looked
 * through from two blocks back, straight, named within {@link #NEAR_LIMIT} ticks in at least {@link #NEAR_MIN_RATE} of
 * those glances.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimPredictMatrixTests implements FabricClientGameTest {

    private static final String NAME = "1020-predict-matrix";
    private static final int[] BACKS = {2, 5, 9, 14, 20, 27};
    private static final double[] LATERALS = {0, 3.5};
    private static final int TICKS = 40;
    private static final int NEAR_LIMIT = 20;
    private static final double NEAR_MIN_RATE = 0.85;

    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String HELD = "com.killer560.hub.livemap.HeldMap";
    private static final String PREDICTOR = "com.killer560.hub.livemap.predict.RoomPredictor";
    private static final String PCFG = "com.killer560.hub.livemap.predict.PredictiveRoomsConfig";

    private final List<String> all = new ArrayList<>();

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
        try {
            Class.forName(PREDICTOR).getMethod("tickFrom", Minecraft.class, Vec3.class,
                    Vec3.class);
        } catch (ReflectiveOperationException e) {
            Scenario.skipped(NAME, "this jar's RoomPredictor has no tickFrom test hook (added 2026-10-20)");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        List<String[]> variants = variants();
        int floors = Integer.getInteger("testkit.predictFloors", 3);
        String contexts = System.getProperty("testkit.predictContexts", "fresh,late");
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> mc.options.renderDistance().set(12));
        List<Row> rows = new ArrayList<>();
        try {
            for (int fl = 1; fl <= floors; fl++) {
                try {
                    oneFloor(ctx, fl, variants, contexts, rows);
                } catch (Throwable t) {
                    all.add("floor " + fl + " threw: " + t);
                    t.printStackTrace(System.out);
                } finally {
                    leave(ctx);
                }
            }
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(PREDICTOR, "tune", new Class<?>[]{String.class, double.class},
                    new Object[]{"defaults", 0.0}));
            ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
        }
        writeCsv(rows);
        String base = variants.get(0)[0];
        for (String[] v : variants) {
            for (String c : contexts.split(",")) {
                println(summary(rows, v[0], c.trim()));
            }
            long[] idle = IDLE.get(v[0]);
            if (idle != null && idle[1] > 0) {
                println(String.format(Locale.US, "[%s] idle (in a room, looking at its floor): %.0f us/tick over %d tick(s)",
                        v[0], idle[0] / 1000.0 / idle[1], idle[1]));
            }
        }
        // ---- verdict on the first variant
        List<Row> mine = new ArrayList<>();
        for (Row r : rows) {
            if (r.variant.equals(base)) {
                mine.add(r);
            }
        }
        List<String> wrong = new ArrayList<>();
        int near = 0;
        int nearNamed = 0;
        for (Row r : mine) {
            if (r.wrong != null && wrong.size() < 8) {
                wrong.add(r.ctx + " " + r.from + "->" + r.into + " back " + r.back + ": " + r.wrong);
            }
            if (r.back == 2 && r.lateral == 0 && r.firstObs >= 0) {
                near++;
                nearNamed += r.named >= 0 && r.named <= NEAR_LIMIT ? 1 : 0;
            }
        }
        long wrongCount = mine.stream().filter(r -> r.wrong != null).count();
        boolean pass = wrongCount == 0 && near > 0 && nearNamed >= NEAR_MIN_RATE * near && rows.size() > 0;
        String detail = mine.size() + " glance(s) on " + floors + " floor(s) [" + base + "]: " + wrongCount
                + " with a wrong name " + wrong + "; from 2 back, straight: " + nearNamed + "/" + near + " named by tick "
                + NEAR_LIMIT + " (need " + Math.round(NEAR_MIN_RATE * 100) + "%)";
        row(NAME, pass && all.isEmpty(), detail + (all.isEmpty() ? "" : " | " + String.join(" | ", all)));
        if (!pass || !all.isEmpty()) {
            throw new AssertionError(detail);
        }
        println("PASS - every case passed");
    }

    /** {label, "KEY=v,KEY=v"} per variant; the first is the one the verdict reads. */
    private static List<String[]> variants() {
        List<String[]> out = new ArrayList<>();
        for (String s : System.getProperty("testkit.predictVariants", "base").split(";")) {
            s = s.trim();
            if (s.isEmpty()) {
                continue;
            }
            int colon = s.indexOf(':');
            out.add(colon < 0 ? new String[]{s, ""} : new String[]{s.substring(0, colon), s.substring(colon + 1)});
        }
        if (out.isEmpty()) {
            out.add(new String[]{"base", ""});
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------- one floor

    private void oneFloor(ClientGameTestContext ctx, int fl, List<String[]> variants, String contexts, List<Row> rows) {
        ctx.runOnClient(mc -> {
            Object p = ModUnderTest.config(PCFG);
            // Off, so the real per-tick predictor never runs between the glances (they reset it and tick it themselves).
            ModUnderTest.set(p, "setEnabled", false);
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
        ctx.waitFor(mc -> mc.level != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> dev.testkit.compat.McCompat.screen(mc) == null, 1200);
        ctx.waitTicks(40);
        Floor f = ctx.computeOnClient(SimPredictMatrixTests::readFloor);
        String code = ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(SIM_STATE, "mapCode")));
        println("floor " + fl + ": " + f.names.size() + " rooms, " + f.doorRooms.size() + " doors, code " + code);
        creativeFlying(ctx);
        // Parked on the seam where four middle tiles meet: inside no room, so the sim's map opens none for him.
        BlockPos c = f.cellCenter(2 * 11 + 2);
        double[] park = {c.getX() + 16.5, f.doorY + 30, c.getZ() + 16.5};
        place(ctx, park);
        awaitFloorChunks(ctx, f);

        List<Glance> glances = new ArrayList<>();
        for (Map.Entry<Integer, int[]> e : f.doorRooms.entrySet()) {
            int a = e.getValue()[0];
            int b = e.getValue()[1];
            boolean open = ctx.computeOnClient(mc -> mc.level.getBlockState(f.doorBlock(e.getKey())).isAir()
                    && mc.level.getBlockState(f.doorBlock(e.getKey()).above()).isAir());
            if (!open) {
                continue;
            }
            for (int[] ab : new int[][]{{a, b}, {b, a}}) {
                if (ab[1] == f.entrance || f.isTrap(ab[1]) || f.isTrap(ab[0])) {
                    continue; // the Entrance is opened from the start; trap rooms are out of scope (2026-10-20)
                }
                for (int back : BACKS) {
                    for (double lat : LATERALS) {
                        Glance g = f.glance(e.getKey(), ab[0], ab[1], back, lat);
                        if (g != null && ctx.computeOnClient(mc -> standable(mc, g.eye))) {
                            g.phase = glances.size() % 4;
                            glances.add(g);
                        }
                    }
                }
            }
        }
        println("floor " + fl + ": " + glances.size() + " glance(s) x " + variants.size() + " variant(s)");
        for (String cx : contexts.split(",")) {
            String context = cx.trim();
            for (String[] v : variants) {
                ctx.runOnClient(mc -> applyVariant(v[1]));
                long yielded = System.nanoTime();
                for (Glance g : glances) {
                    // A few real frames now and then, or Windows calls the window frozen and the watcher closes it.
                    if (System.nanoTime() - yielded > 150_000_000L) {
                        ctx.waitTicks(1);
                        yielded = System.nanoTime();
                    }
                    Row r = ctx.computeOnClient(mc -> run(mc, f, g, context));
                    r.floor = fl;
                    r.variant = v[0];
                    rows.add(r);
                }
                // Idle cost: standing in each room's middle looking at its own floor ahead, which every sweep still
                // casts its rays for while unopened rooms lie within their reach.
                long[] idle = ctx.computeOnClient(mc -> idle(mc, f));
                IDLE.computeIfAbsent(v[0], k -> new long[2]);
                IDLE.get(v[0])[0] += idle[0];
                IDLE.get(v[0])[1] += idle[1];
            }
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(PREDICTOR, "tune", new Class<?>[]{String.class, double.class},
                new Object[]{"defaults", 0.0}));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(PREDICTOR, "reset"));
    }

    private static void applyVariant(String spec) {
        ModUnderTest.staticCall(PREDICTOR, "tune", new Class<?>[]{String.class, double.class},
                new Object[]{"defaults", 0.0});
        for (String kv : spec.split(",")) {
            int eq = kv.indexOf('=');
            if (eq > 0) {
                ModUnderTest.staticCall(PREDICTOR, "tune", new Class<?>[]{String.class, double.class},
                        new Object[]{kv.substring(0, eq).trim(), Double.parseDouble(kv.substring(eq + 1).trim())});
            }
        }
        println("tuning: " + ModUnderTest.staticCall(PREDICTOR, "tuning"));
    }

    /** Variant -> {nanoseconds, ticks} of {@link #idle}. */
    private static final Map<String, long[]> IDLE = new LinkedHashMap<>();

    /** {ns, ticks}: {@link #TICKS} predictor ticks from each room's first tile, its own room opened, looking 50 degrees
     *  down towards +x, then +z. */
    private static long[] idle(Minecraft mc, Floor f) {
        long ns = 0;
        long ticks = 0;
        try {
            Method tickFrom = Class.forName(PREDICTOR).getMethod("tickFrom", Minecraft.class, Vec3.class, Vec3.class);
            for (int r = 0; r < f.names.size(); r++) {
                if (f.isTrap(r)) {
                    continue;
                }
                ModUnderTest.staticCall(PREDICTOR, "reset");
                ModUnderTest.staticCall(HELD, "resetSimVisits");
                ModUnderTest.staticCall(HELD, "simVisit", new Class<?>[]{int.class}, new Object[]{r});
                BlockPos c = f.cellCenter(f.tiles.get(r)[0]);
                Vec3 eye = new Vec3(c.getX() + 0.5, f.doorY + 1.82, c.getZ() + 0.5);
                for (Vec3 view : new Vec3[]{new Vec3(0.64, -0.77, 0), new Vec3(0, -0.77, 0.64)}) {
                    for (int t = 0; t < TICKS; t++) {
                        ns += (Long) tickFrom.invoke(null, mc, eye, view);
                        ticks++;
                    }
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return new long[]{ns, ticks};
    }

    private static boolean standable(Minecraft mc, Vec3 eye) {
        BlockPos e = BlockPos.containing(eye);
        return mc.level.getBlockState(e).isAir() && mc.level.getBlockState(e.below()).isAir();
    }

    /** One glance, on the client thread: reset, open the context's rooms, {@link #TICKS} predictor ticks. */
    private static Row run(Minecraft mc, Floor f, Glance g, String context) {
        ModUnderTest.staticCall(PREDICTOR, "reset");
        ModUnderTest.staticCall(HELD, "resetSimVisits");
        if ("late".equals(context)) {
            for (int r = 0; r < f.names.size(); r++) {
                if (r != g.into) {
                    ModUnderTest.staticCall(HELD, "simVisit", new Class<?>[]{int.class}, new Object[]{r});
                }
            }
        } else {
            ModUnderTest.staticCall(HELD, "simVisit", new Class<?>[]{int.class}, new Object[]{g.from});
        }
        Row row = new Row();
        row.ctx = context;
        row.from = f.names.get(g.from);
        row.into = f.names.get(g.into);
        row.intoTiles = f.tiles.get(g.into).length;
        row.back = g.back;
        row.lateral = g.lateral;
        int[] intoTiles = f.tiles.get(g.into);
        Method tickFrom;
        try {
            tickFrom = Class.forName(PREDICTOR).getMethod("tickFrom",
                    Minecraft.class, Vec3.class, Vec3.class);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        // A room comes into his view at any point of the sweep cadence, not just after a reset: 0-3 ticks first, looking
        // up into the sky from far above the floor (nothing to see, nothing recorded), by the glance's own phase.
        try {
            for (int k = 0; k < g.phase; k++) {
                tickFrom.invoke(null, mc, g.eye.add(0, 300, 0), new Vec3(0, 1, 0));
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        long total = 0;
        long max = 0;
        for (int tick = 1; tick <= TICKS; tick++) {
            long ns;
            try {
                ns = (Long) tickFrom.invoke(null, mc, g.eye, g.view);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            total += ns;
            max = Math.max(max, ns);
            int ev = 0;
            for (int t : intoTiles) {
                ev += (Integer) ModUnderTest.staticCall(PREDICTOR, "evidence", new Class<?>[]{int.class},
                        new Object[]{t});
            }
            if (row.firstObs < 0 && ev > 0) {
                row.firstObs = tick;
            }
            row.evidence = ev;
            for (Object p : (List<?>) ModUnderTest.staticCall(PREDICTOR, "predictions")) {
                String pname = (String) inv(p, "name");
                for (int tile : (int[]) inv(p, "certainTiles")) {
                    int actual = f.roomAt(tile);
                    boolean right = actual >= 0 && f.names.get(actual).equalsIgnoreCase(pname);
                    if (!right && row.wrong == null) {
                        row.wrong = pname + " on tile " + tile + " (really "
                                + (actual < 0 ? "nothing" : f.names.get(actual)) + ") at tick " + tick;
                    }
                    if (right && actual == g.into && row.named < 0) {
                        row.named = tick;
                        row.namedEvidence = ev;
                        row.nsToNamed = total;
                    }
                }
            }
        }
        row.ns = total;
        row.maxNs = max;
        row.obs = (Integer) ModUnderTest.staticCall(PREDICTOR, "observationCount");
        return row;
    }

    // ------------------------------------------------------------------------------------------------- results

    private static final class Row {
        int floor;
        String variant;
        String ctx;
        String from;
        String into;
        int intoTiles;
        int back;
        double lateral;
        int firstObs = -1;
        int named = -1;
        int namedEvidence;
        int evidence;
        int obs;
        long ns;
        long maxNs;
        long nsToNamed;
        String wrong;

        String csv() {
            return String.join(",", String.valueOf(floor), variant, ctx, q(from), q(into), String.valueOf(intoTiles),
                    String.valueOf(back), String.valueOf(lateral), String.valueOf(firstObs), String.valueOf(named),
                    String.valueOf(namedEvidence), String.valueOf(evidence), String.valueOf(obs), String.valueOf(ns),
                    String.valueOf(maxNs), String.valueOf(nsToNamed), q(wrong == null ? "" : wrong));
        }

        private static String q(String s) {
            return "\"" + s.replace("\"", "'") + "\"";
        }
    }

    private static String summary(List<Row> rows, String variant, String context) {
        List<Row> vs = new ArrayList<>();
        for (Row r : rows) {
            if (r.variant.equals(variant) && r.ctx.equals(context)) {
                vs.add(r);
            }
        }
        if (vs.isEmpty()) {
            return "[" + variant + "/" + context + "] no glances";
        }
        int seen = 0;
        int named = 0;
        int named10 = 0;
        int wrong = 0;
        List<Integer> ticks = new ArrayList<>();
        long ns = 0;
        long ticksRun = 0;
        long maxNs = 0;
        List<Long> perTick = new ArrayList<>();
        Map<String, Integer> maxBack = new TreeMap<>();
        Map<Integer, int[]> byBack = new TreeMap<>();
        for (Row r : vs) {
            wrong += r.wrong != null ? 1 : 0;
            ns += r.ns;
            ticksRun += TICKS;
            maxNs = Math.max(maxNs, r.maxNs);
            perTick.add(r.ns / TICKS);
            int[] b = byBack.computeIfAbsent(r.back, k -> new int[2]);
            if (r.firstObs >= 0) {
                seen++;
                b[0]++;
            }
            if (r.named >= 0) {
                named++;
                b[1]++;
                named10 += r.named <= 10 ? 1 : 0;
                ticks.add(r.named);
                String door = r.floor + ":" + r.from + "->" + r.into;
                maxBack.merge(door, r.back, Math::max);
            }
        }
        // Per doorway (both laterals), the furthest back it was named from.
        Map<String, Integer> doors = new TreeMap<>();
        for (Row r : vs) {
            if (r.firstObs >= 0) {
                doors.put(r.floor + ":" + r.from + "->" + r.into, 0);
            }
        }
        double meanMax = 0;
        for (String d : doors.keySet()) {
            meanMax += maxBack.getOrDefault(d, 0);
        }
        meanMax = doors.isEmpty() ? 0 : meanMax / doors.size();
        ticks.sort(null);
        double mean = ticks.stream().mapToInt(Integer::intValue).average().orElse(-1);
        int median = ticks.isEmpty() ? -1 : ticks.get(ticks.size() / 2);
        int p90 = ticks.isEmpty() ? -1 : ticks.get(Math.min(ticks.size() - 1, (int) (ticks.size() * 0.9)));
        StringBuilder bb = new StringBuilder();
        for (Map.Entry<Integer, int[]> e : byBack.entrySet()) {
            bb.append(' ').append(e.getKey()).append(':').append(e.getValue()[1]).append('/').append(e.getValue()[0]);
        }
        return String.format(Locale.US, "[%s/%s] %d glance(s), %d saw the room, named %d (%.1f%% of seen; %d by tick 10), "
                        + "tick to name mean %.1f median %d p90 %d; furthest back named per doorway mean %.1f over %d "
                        + "doorway(s); named/seen by back:%s; WRONG %d (%.2f%%); predictor %.0f us/tick mean, max %.2f ms",
                variant, context, vs.size(), seen, named, seen == 0 ? 0 : 100.0 * named / seen, named10, mean, median, p90,
                meanMax, doors.size(), bb, wrong, 100.0 * wrong / vs.size(), ns / 1000.0 / Math.max(1, ticksRun),
                maxNs / 1.0e6);
    }

    private static void writeCsv(List<Row> rows) {
        String dir = System.getProperty("testkit.predictOut", System.getProperty("testkit.reportDir", "."));
        Path p = Path.of(dir, "predict-matrix.csv");
        StringBuilder sb = new StringBuilder("floor,variant,ctx,from,into,intoTiles,back,lateral,firstObsTick,namedTick,"
                + "namedEvidence,evidenceAtEnd,observations,ns,maxTickNs,nsToNamed,wrong\n");
        for (Row r : rows) {
            sb.append(r.csv()).append('\n');
        }
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
            println("wrote " + rows.size() + " row(s) to " + p.toAbsolutePath());
        } catch (IOException e) {
            println("could not write " + p + ": " + e);
        }
    }

    // ------------------------------------------------------------------------------------------------- floor

    private static final class Glance {
        int door;
        int from;
        int into;
        int back;
        double lateral;
        int phase;
        Vec3 eye;
        Vec3 view;
    }

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
        }
        Arrays.fill(f.roomOf, -1);
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
        final List<String> types = new ArrayList<>();
        final List<int[]> tiles = new ArrayList<>();
        final int[] roomOf = new int[121];
        final Map<Integer, int[]> doorRooms = new LinkedHashMap<>();
        int entrance = -1;
        int doorY;

        boolean isTrap(int r) {
            return "TRAP".equals(types.get(r));
        }

        int roomAt(int tile) {
            return tile >= 0 && tile < 121 ? roomOf[tile] : -1;
        }

        BlockPos doorBlock(int idx) {
            return (BlockPos) ModUnderTest.staticCall(LAYOUT, "doorBlock", new Class<?>[]{int.class}, new Object[]{idx});
        }

        BlockPos cellCenter(int idx) {
            return (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class}, new Object[]{idx});
        }

        /** Whether world x/z lies inside one of room {@code r}'s tiles (its 31 blocks, not the seam). */
        boolean inside(int r, double x, double z) {
            for (int t : tiles.get(r)) {
                BlockPos c = cellCenter(t);
                if (Math.abs(x - (c.getX() + 0.5)) < 15 && Math.abs(z - (c.getZ() + 0.5)) < 15) {
                    return true;
                }
            }
            return false;
        }

        /** A look from {@code back} blocks short of door {@code d} inside {@code from}, {@code lat} to one side, at
         *  the point 12 blocks beyond the doorway; null when that spot is not inside {@code from}. */
        Glance glance(int d, int from, int into, int back, double lat) {
            BlockPos b = doorBlock(d);
            double[] dc = {b.getX() + 0.5, b.getY(), b.getZ() + 0.5};
            int gx = d % 11;
            int gz = d / 11;
            int side = gx % 2 == 1 ? roomOf[gz * 11 + gx - 1] : roomOf[(gz - 1) * 11 + gx];
            int sign = side == from ? 1 : -1;
            double ux = gx % 2 == 1 ? sign : 0;
            double uz = gx % 2 == 1 ? 0 : sign;
            // Sideways alternates with the door's index, so both sides of doorways get looked from.
            double s = (d % 4 < 2 ? 1 : -1) * lat;
            double ex = dc[0] - ux * back + uz * s;
            double ez = dc[2] - uz * back - ux * s;
            if (!inside(from, ex, ez)) {
                return null;
            }
            Glance g = new Glance();
            g.door = d;
            g.from = from;
            g.into = into;
            g.back = back;
            g.lateral = lat;
            g.eye = new Vec3(ex, dc[1] + 0.2 + 1.62, ez);
            Vec3 target = new Vec3(dc[0] + ux * 12, dc[1] + 1.0, dc[2] + uz * 12);
            g.view = target.subtract(g.eye).normalize();
            return g;
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

    /** Server teleport (the harness parking the player; the mod never moves him). */
    private static void place(ClientGameTestContext ctx, double[] at) {
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
        });
    }

    /** Waits (at most 60 s) for the client to hold every chunk of the floor's 6x6 tiles. */
    private static void awaitFloorChunks(ClientGameTestContext ctx, Floor f) {
        BlockPos lo = f.cellCenter(0);
        BlockPos hi = f.cellCenter(120);
        int missing = -1;
        int waited = 0;
        for (; waited < 1200; waited += 10) {
            missing = ctx.computeOnClient(mc -> {
                int n = 0;
                for (int cx = (lo.getX() - 17) >> 4; cx <= (hi.getX() + 17) >> 4; cx++) {
                    for (int cz = (lo.getZ() - 17) >> 4; cz <= (hi.getZ() + 17) >> 4; cz++) {
                        if (!mc.level.getChunkSource().hasChunk(cx, cz)) {
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
        println("floor chunks: " + (missing == 0 ? "all, after " + waited / 20.0 + " s" : missing + " missing"));
        ctx.waitTicks(40);
    }

    // ------------------------------------------------------------------------------------------------- plumbing

    private void row(String name, boolean pass, String detail) {
        System.out.println("[" + name + "] " + (pass ? "PASS" : "FAIL") + " - " + detail);
        Report.caseFinished(name, pass ? "PASS" : "FAIL", "n/a (singleplayer)", detail, List.of(), List.of());
        SuiteVerdict.finished(name);
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
                dev.testkit.compat.McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> dev.testkit.compat.McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen,
                400);
    }

    private static void println(String line) {
        System.out.println("[" + NAME + "] " + line);
    }
}

package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1016-sim-fullclear: killer560's own routes and dungeon settings (fed with {@code -PseedConfig}) on a generated sim
 * floor, Auto Secret started at GO and left to finish on its own, as he plays it (killer560, 2026-10-16: "Do not have it
 * stop the auto routes/auto clear testing till it can full clear a sim map").
 *
 * <p>Per run it writes a tally ({@code -PfullclearOut=<dir>}, file {@code run-NNN.md}, next free number) and a copy of
 * the client log: per room the sim's own secret count (placed, found by the sim SERVER - {@code SimScore.foundInRoom}),
 * the map's count, whether he stood in it, whether he has a route for it and whether that route started and finished;
 * puzzles by the map; stalls (Auto Secret running and him not moving for 3 s or more), Auto Routes stops other than its
 * own end, start nodes that did not arm, warps that never landed, corrections, mod ERROR lines, and the distance he
 * travelled. A run that never moved, never started a route or never counted a secret is reported as NOT RUN.
 *
 * <p>The verdict: every room he has a route for (not a puzzle, trap, blood, fairy or entrance) visited and fully found,
 * at most {@code MAX_MISSED_ROOMS} rooms short; every puzzle done or failed by the map; Auto Secret ended by "nothing
 * left to do"; no Auto Routes stop, no stall over {@code STALL_SECONDS}, no disconnect. GrimAC does not run inside the
 * sim's integrated server, so this scenario says nothing about it.
 *
 * <p>{@code -PfullclearRuns=N} runs N fresh floors in one client; {@code -PfullclearCode=<map code>} replays one floor.
 * Only when named.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimFullClearTests implements FabricClientGameTest {

    private static final String NAME = "1016-sim-fullclear";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_WORLD = "com.killer560.hub.roomsim.SimWorld";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String SIM_SCORE = "com.killer560.hub.roomsim.SimScore";
    private static final String SIM_INDEX = "com.killer560.hub.roomsim.SimRoomIndex";
    private static final String SIM_RUN = "com.killer560.hub.roomsim.SimRun";
    private static final String ROOM_STATUS = "com.killer560.hub.livemap.RoomStatus";
    private static final String AS = "com.killer560.hub.autosecret.AutoSecretFeature";
    private static final String STORE = "com.killer560.hub.autoroutes.RouteStore";
    private static final String DUNGEON_INFO = "com.killer560.hub.dungeoninfo.DungeonInfoFeature";

    /** Rooms with a route he may finish short of every secret (killer560: "only 1-2 rooms it shouldnt get a secret in"). */
    private static final int MAX_MISSED_ROOMS = 2;
    private static final double STALL_SECONDS = 4.0;
    private static final int RUN_LIMIT_TICKS = 20 * 60 * 8;
    private static final Set<String> SKIP_TYPES = Set.of("PUZZLE", "TRAP", "BLOOD", "FAIRY", "ENTRANCE");

    private static final Pattern PLACED = Pattern.compile("Sim secrets for (.+?): (\\d+) placed");
    private static final Pattern PLACED_ONE = Pattern.compile("Sim secret placed: (\\w+) at (-?\\d+, -?\\d+, -?\\d+)");
    private static final Pattern COUNTED = Pattern.compile("Sim secret counted: (-?\\d+, -?\\d+, -?\\d+) in (.+?) \\(");
    private static final Pattern STARTED = Pattern.compile("\\[AutoSecret\\] route (.+?) started");
    private static final Pattern FINISHED = Pattern.compile("\\[AutoSecret\\] route (.+?) finished");
    private static final Pattern AR_STOP = Pattern.compile("\\[AutoRoutes\\] Stopped: (.*)$");
    private static final Pattern AS_STOP = Pattern.compile("\\[AutoSecret\\] stopped: (.*)$");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank()) {
            return;
        }
        boolean fullClear = !Scenario.skip(NAME);
        boolean highBlaze = !Scenario.skip(HIGH_NAME);
        if (!fullClear && !highBlaze) {
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
        int routes = ctx.computeOnClient(mc -> routedRooms().size());
        println("routes file holds " + routes + " room(s)");
        if (routes < 20) {
            throw new AssertionError("only " + routes + " routed room(s) - run with -PseedConfig=<his config>");
        }
        if (highBlaze) {
            String why = null;
            try {
                why = highBlazeOut(ctx);
            } catch (Throwable e) {
                why = "threw " + e;
                e.printStackTrace(System.out);
            } finally {
                leave(ctx);
                ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(
                        "com.killer560.hub.autopuzzles.AutoPuzzlesConfig"), "setAutoPuzzlesMasterEnabled", true));
            }
            if (why != null) {
                println("[" + HIGH_NAME + "] FAIL: " + why);
                if (!fullClear) {
                    throw new AssertionError(why);
                }
            } else {
                println("[" + HIGH_NAME + "] PASS");
            }
            if (!fullClear) {
                return;
            }
        }
        int runs = Integer.getInteger("testkit.fullclearRuns", 1);
        String code = System.getProperty("testkit.fullclearCode", "");
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> mc.options.renderDistance().set(10));
        List<String> verdicts = new ArrayList<>();
        int failed = 0;
        try {
            for (int run = 1; run <= runs; run++) {
                Tally t = new Tally();
                try {
                    oneRun(ctx, t, code.isBlank() ? null : code);
                } catch (Throwable e) {
                    t.problems.add("scenario threw: " + e);
                    e.printStackTrace(System.out);
                } finally {
                    leave(ctx);
                }
                String file = write(t);
                String v = String.format(Locale.US, "run %d/%d: %s - %s", run, runs, t.verdict(), file);
                verdicts.add(v);
                println(v);
                if (!t.clean()) {
                    failed++;
                }
            }
        } finally {
            ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
        }
        for (String v : verdicts) {
            println(v);
        }
        if (failed > 0) {
            throw new AssertionError(failed + " of " + runs + " run(s) did not full clear: " + String.join("; ", verdicts));
        }
        println("PASS");
    }

    // ================================================================================================ one run

    static final class Tally {
        String code = "";
        final Map<String, Room> rooms = new LinkedHashMap<>();
        final List<String> problems = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        final List<String> stalls = new ArrayList<>();
        final List<String> arStops = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        String asStop = "(still running)";
        double travelled;
        double seconds;
        int secretsFound;
        int secretsTotal;
        double mapPercent;
        int corrections;
        int noLanding;
        int unarmed;
        boolean ran;

        String verdict() {
            return !ran ? "NOT RUN" : clean() ? "CLEAN" : "BROKE (" + problems.size() + " problem(s))";
        }

        boolean clean() {
            return ran && problems.isEmpty();
        }
    }

    static final class Room {
        String name;
        String type = "";
        boolean routed;
        int placed = -1;
        int found;
        int mapSecrets;
        int mapUnfound;
        boolean mapCleared;
        boolean mapFailed;
        boolean visited;
        int started;
        /** "kind x, y, z" per secret the sim placed, and the positions it counted. */
        final List<String> placedList = new ArrayList<>();
        final List<int[]> counted = new ArrayList<>();

        /** The placed secrets nothing counted: each counted position takes the nearest placed one of its room within 6
         *  blocks (a bat dies away from its spot, so a bat takes any bat left). */
        List<String> missing() {
            List<String> left = new ArrayList<>(placedList);
            for (int[] c : counted) {
                String best = null;
                double bestD = 6.0;
                for (String s : left) {
                    String[] k = s.split(" ", 2);
                    String[] xyz = k[1].split(", ");
                    double d = Math.sqrt(Math.pow(c[0] - Integer.parseInt(xyz[0]), 2)
                            + Math.pow(c[1] - Integer.parseInt(xyz[1]), 2) + Math.pow(c[2] - Integer.parseInt(xyz[2]), 2));
                    if (d <= bestD) {
                        bestD = d;
                        best = s;
                    }
                }
                if (best == null) {
                    for (String s : left) {
                        if (s.startsWith("bat ")) {
                            best = s;
                            break;
                        }
                    }
                }
                if (best != null) {
                    left.remove(best);
                }
            }
            return left;
        }
        int finished;
    }

    private static void oneRun(ClientGameTestContext ctx, Tally t, String code) {
        long mark0 = LogTap.mark();
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                new Object[]{"gametest"}));
        long before = Scenario.simBuildCount(ctx);
        // As he generates them (the map designer's Generate then Play): an F7, the sliders at "pick for me" (drawn inside
        // their ranges), the room pool narrowed by his designer filters - "routes: HAS", so every room but Entrance,
        // Blood, Fairy and the trap is one he has a route for.
        String floorCode = code != null ? code : ctx.computeOnClient(mc -> planFloor());
        if (floorCode == null) {
            t.problems.add("no floor could be planned");
            return;
        }
        ctx.runOnClient(mc -> mc.execute(() -> {
            ModUnderTest.staticCall(SIM_STATE, "setFloorLabel", new Class<?>[]{String.class}, new Object[]{"F7"});
            java.util.function.Consumer<Minecraft> build = c -> ModUnderTest.staticCall(
                    "com.killer560.hub.roomsim.SimBuilder", "build", new Class<?>[]{Minecraft.class, String.class},
                    new Object[]{c, floorCode});
            ModUnderTest.staticCall(SIM_WORLD, "open", new Class<?>[]{Minecraft.class, String.class,
                    java.util.function.Consumer.class, String.class}, new Object[]{mc, floorCode, build, "Generating Floor 7"});
        }));
        ctx.waitFor(mc -> mc.level != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitTicks(60);
        t.code = ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(SIM_STATE, "mapCode")));
        awaitClientChunks(ctx);
        giveLoadout(ctx);
        Set<String> routed = ctx.computeOnClient(mc -> routedRooms());

        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_RUN, "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
        for (int i = 0; i < 600 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_RUN, "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        // The floor as the sim placed it, once its build lines are in.
        List<String> pending = new ArrayList<>();
        for (String l : LogTap.since(mark0)) {
            Matcher p = PLACED_ONE.matcher(l);
            if (p.find()) {
                pending.add(p.group(1) + " " + p.group(2));
                continue;
            }
            Matcher m = PLACED.matcher(l);
            if (m.find()) {
                Room r = room(t, m.group(1));
                r.placed = Integer.parseInt(m.group(2));
                r.placedList.addAll(pending);
                pending.clear();
            }
        }
        ctx.computeOnClient(mc -> {
            for (Object p : (List<?>) ModUnderTest.staticCall(SIM_INDEX, "placed")) {
                String n = (String) ModUnderTest.call(p, "name", new Class<?>[0], new Object[0]);
                Room r = room(t, n);
                r.type = String.valueOf(ModUnderTest.staticCall(FLOOR_GEN, "typeOf", new Class<?>[]{String.class},
                        new Object[]{n})).toUpperCase(Locale.ROOT);
                r.routed = routed.contains(n);
            }
            return null;
        });

        long mark = LogTap.mark();
        Boolean started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "start"));
        if (!Boolean.TRUE.equals(started)) {
            t.problems.add("Auto Secret refused to start: " + String.join(" | ", LogTap.since(mark)));
            return;
        }
        Vec3 last = ctx.computeOnClient(mc -> mc.player.position());
        long readMark = mark;
        int stillTicks = 0;
        Vec3 stillAt = last;
        String stillPhase = "";
        int ticks = 0;
        for (; ticks < RUN_LIMIT_TICKS; ticks += 5) {
            ctx.waitTicks(5);
            boolean world = ctx.computeOnClient(mc -> mc.level != null && mc.player != null);
            if (!world) {
                t.problems.add(String.format(Locale.US, "the world went away %.1f s in (disconnect or crash)", ticks / 20.0));
                break;
            }
            long m2 = LogTap.mark();
            List<String> fresh = LogTap.since(readMark);
            readMark = m2;
            t.log.addAll(fresh);
            Vec3 at = ctx.computeOnClient(mc -> mc.player.position());
            t.travelled += at.distanceTo(last);
            last = at;
            // The live map's room (SimScore.roomAt rounds to a tile and named Gold for a spot in Quiz).
            String here = ctx.computeOnClient(mc -> {
                Object e = ModUnderTest.staticCall("com.killer560.hub.livemap.LiveMapFeature", "currentRoomEntry");
                if (e == null) {
                    return null;
                }
                try {
                    return (String) e.getClass().getField("name").get(e);
                } catch (ReflectiveOperationException x) {
                    return null;
                }
            });
            if (here != null) {
                room(t, here).visited = true;
            }
            String phase = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(AS, "phaseName"));
            boolean running = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "isRunning"));
            if (at.distanceTo(stillAt) < 0.3) {
                stillTicks += 5;
            } else {
                if (stillTicks / 20.0 >= 3.0) {
                    t.stalls.add(String.format(Locale.US, "%.1f s still at %.1f %.1f %.1f in %s (phase %s) ending at %.1f s",
                            stillTicks / 20.0, stillAt.x, stillAt.y, stillAt.z, here, stillPhase, ticks / 20.0));
                }
                stillTicks = 0;
                stillAt = at;
                stillPhase = phase;
            }
            if (!running) {
                break;
            }
        }
        if (stillTicks / 20.0 >= 3.0) {
            t.stalls.add(String.format(Locale.US, "%.1f s still at %.1f %.1f %.1f (phase %s) at the end", stillTicks / 20.0,
                    stillAt.x, stillAt.y, stillAt.z, stillPhase));
        }
        t.seconds = ticks / 20.0;
        boolean stillRunning = ctx.computeOnClient(mc -> mc.level != null
                && (Boolean) ModUnderTest.staticCall(AS, "isRunning"));
        if (stillRunning) {
            t.problems.add(String.format(Locale.US, "Auto Secret still running after %.0f s", t.seconds));
            ctx.runOnClient(mc -> ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class},
                    new Object[]{"test over", true}));
        }
        ctx.waitTicks(40);
        t.log.addAll(LogTap.since(readMark));
        if (ctx.computeOnClient(mc -> mc.level == null)) {
            return;
        }
        // ---- read the result -------------------------------------------------------------------------------------
        ctx.computeOnClient(mc -> {
            t.secretsFound = (Integer) ModUnderTest.staticCall(SIM_SCORE, "secretsFound");
            t.secretsTotal = (Integer) ModUnderTest.staticCall(SIM_SCORE, "secretsTotal");
            t.mapPercent = (Double) ModUnderTest.staticCall(DUNGEON_INFO, "secretsFoundPercent");
            for (Room r : t.rooms.values()) {
                r.found = (Integer) ModUnderTest.staticCall(SIM_SCORE, "foundInRoom", new Class<?>[]{String.class},
                        new Object[]{r.name});
            }
            for (Object o : (List<?>) ModUnderTest.staticCall(ROOM_STATUS, "rooms")) {
                String n = (String) ModUnderTest.call(o, "name", new Class<?>[0], new Object[0]);
                Room r = t.rooms.get(n);
                if (r == null) {
                    continue;
                }
                r.mapSecrets = (Integer) ModUnderTest.call(o, "secrets", new Class<?>[0], new Object[0]);
                r.mapUnfound = (Integer) ModUnderTest.call(o, "unfound", new Class<?>[0], new Object[0]);
                r.mapCleared = (Boolean) ModUnderTest.call(o, "cleared", new Class<?>[0], new Object[0]);
                r.mapFailed = (Boolean) ModUnderTest.call(o, "failed", new Class<?>[0], new Object[0]);
                if (r.type.isEmpty()) {
                    r.type = (String) ModUnderTest.call(o, "type", new Class<?>[0], new Object[0]);
                }
            }
            return null;
        });
        judge(t);
    }

    private static void judge(Tally t) {
        for (String l : t.log) {
            Matcher c = COUNTED.matcher(l);
            if (c.find()) {
                String[] xyz = c.group(1).split(", ");
                room(t, c.group(2)).counted.add(new int[]{Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]),
                        Integer.parseInt(xyz[2])});
            }
            Matcher m = STARTED.matcher(l);
            if (m.find()) {
                room(t, m.group(1)).started++;
                t.events.add(clip(l));
            }
            m = FINISHED.matcher(l);
            if (m.find()) {
                room(t, m.group(1)).finished++;
                t.events.add(clip(l));
            }
            m = AR_STOP.matcher(l);
            if (m.find()) {
                String why = m.group(1).trim();
                if (!why.equals("null") && !why.isEmpty()) {
                    t.arStops.add(clip(l));
                }
            }
            m = AS_STOP.matcher(l);
            if (m.find()) {
                t.asStop = m.group(1).trim();
            }
            if (l.contains("[Correction]")) {
                t.corrections++;
                t.events.add(clip(l));
            }
            if (l.contains("no landing after") || l.contains("never landed")) {
                t.noLanding++;
                t.events.add(clip(l));
            }
            if (l.contains("Kill Mimic done: timed out")) {
                t.problems.add("Kill Mimic never killed the mimic: " + clip(l));
            }
            if (l.contains("start node did not arm")) {
                t.unarmed++;
                t.events.add(clip(l));
            }
            if (l.contains("[AutoSecret] endgame") || l.contains("[AutoSecret] puzzle ") || l.contains("No route for")
                    || l.contains("[AutoSecret] stopped")) {
                t.events.add(clip(l));
            }
            String[] parts = l.split(" ", 4);
            if (parts.length >= 3 && parts[1].equals("ERROR") && parts[2].startsWith("(")) {
                t.errors.add(clip(l));
            }
        }
        t.ran = t.travelled > 20 && t.secretsFound > 0 && t.rooms.values().stream().anyMatch(r -> r.started > 0);
        if (!t.ran) {
            t.problems.add(String.format(Locale.US, "the clear never ran (travelled %.1f, %d secret(s), routes started %d)",
                    t.travelled, t.secretsFound, t.rooms.values().stream().mapToInt(r -> r.started).sum()));
        }
        if (!t.asStop.startsWith("nothing left to do")) {
            t.problems.add("Auto Secret ended with \"" + t.asStop + "\", not \"nothing left to do\"");
        }
        for (String s : t.arStops) {
            if (!s.contains("Stopped: Auto Secret stopped") || !t.asStop.equals("test over")) {
                t.problems.add("Auto Routes stopped: " + s);
            }
        }
        int missed = 0;
        for (Room r : t.rooms.values()) {
            if (SKIP_TYPES.contains(r.type)) {
                if (r.type.equals("PUZZLE") && !r.mapCleared && !r.mapFailed) {
                    t.problems.add("puzzle " + r.name + " is neither done nor failed on the map");
                }
                continue;
            }
            if (!r.routed) {
                if (r.placed > 0) {
                    t.notes.add(r.name + " has no route (" + r.placed + " secret(s) placed, " + r.found + " found)");
                }
                continue;
            }
            if (!r.visited) {
                t.problems.add(r.name + " (routed) was never visited");
            }
            if (r.started == 0) {
                t.problems.add(r.name + "'s route never started");
            } else if (r.finished == 0) {
                t.problems.add(r.name + "'s route started but never finished");
            }
            if (r.placed > 0 && r.found < r.placed) {
                missed++;
                t.notes.add(String.format(Locale.US, "%s: %d/%d secrets (map %d/%d), missing %s", r.name, r.found, r.placed,
                        r.mapSecrets - r.mapUnfound, r.mapSecrets, r.missing()));
            }
        }
        if (missed > MAX_MISSED_ROOMS) {
            t.problems.add(missed + " routed room(s) short of their secrets (at most " + MAX_MISSED_ROOMS + ")");
        }
        for (String s : t.stalls) {
            double secs = Double.parseDouble(s.substring(0, s.indexOf(' ')));
            if (secs > STALL_SECONDS && !s.contains("PUZZLE")) {
                t.problems.add("stall: " + s);
            }
        }
        if (!t.errors.isEmpty()) {
            t.problems.add(t.errors.size() + " mod ERROR line(s), first: " + t.errors.get(0));
        }
    }

    // ================================================================================================ high blaze

    static final String HIGH_NAME = "1016-sim-highblaze-out";
    /** The floor of fullclear run-004 (his designer filters): Higher Blaze among 23 rooms. */
    private static final String HIGH_CODE = "MC2:CxgIRW50cmFuY2UEQ2FnZQNFbmQFRmxhZ3MFTXVyYWwIUGFpbnRpbmcLQmx1ZSBTa3VsbHMGTXVz"
            + "ZXVtBUZhaXJ5DFNtYWxsIFN0YWlycwVCbG9vZAlDYXRoZWRyYWwLV2F0ZXIgQm9hcmQIT2xkIFRyYXAHSGFsbHdheQREb21lDUNyZWVwZXIg"
            + "QmVhbXMMSGlnaGVyIEJsYXplDVRlbGVwb3J0IE1hemUERHJvcAdDYXJwZXRzBlJpdHVhbAtTY2FmZm9sZGluZw5Eb3VibGUgRGlhbW9uZAALAw"
            + "AAAAAEAwAEAwAEAwEAAAADAAEAAAACAwQAAAABAgMAAAAAAAAEAwAEAwAEAwAAAAAAAAAAAAAAAAAAAAAAAAAKAwAAAAAEAwAEAwAEAwEAAAAM"
            + "AAAMAAAMAAEAAAANAwEAAAAAAAEAAAAAAAAAAAAAAAAMAAAMAAAMAAAAAAAAAAAJAgAAAAAFAAEAAAAGAwAAAAAMAAAMAAAMAAEAAAAVAAEAAA"
            + "AAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAEAAAAIAAAIAAAIAAEAAAAHAQAAAAARAQEAAAAQAAAAAAAWAAAIAAAIAAAIAAAAAAAAAAAAAAAA"
            + "AAAAAAEAAAAAAAAAAAAIAAAIAAAIAAEAAAAPAQAPAQAPAQAPAQAPAQAPAQAPAQEAAAAAAAEAAAAAAAEAAAAAAAEAAAAAAAEAAAAAAAAAAAAOAw"
            + "AAAAAUAAAAAAASAQAAAAATAAAAAAAXAAEAAAAYAg";

    /**
     * fullclear-1016 run 004: after Auto Blaze took Higher Blaze's reward chest from its stand at the top of the room
     * (relative 6, 116, 25 - 47 over the floor), every Interactive Map trip out ("no way to exact ... from here on the
     * floor graph", "the fine room search found nothing either") failed and Teleport Maze, Creeper Beams and Water Board
     * were skipped. The same floor, him on that stand, a trip asked to the Entrance. Null on arrival, else why not.
     */
    private static String highBlazeOut(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                new Object[]{"gametest"}));
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            ModUnderTest.staticCall(SIM_STATE, "setFloorLabel", new Class<?>[]{String.class}, new Object[]{"F7"});
            java.util.function.Consumer<Minecraft> build = c -> ModUnderTest.staticCall(
                    "com.killer560.hub.roomsim.SimBuilder", "build", new Class<?>[]{Minecraft.class, String.class},
                    new Object[]{c, HIGH_CODE});
            ModUnderTest.staticCall(SIM_WORLD, "open", new Class<?>[]{Minecraft.class, String.class,
                    java.util.function.Consumer.class, String.class}, new Object[]{mc, HIGH_CODE, build, "Generating Floor 7"});
        }));
        ctx.waitFor(mc -> mc.level != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitTicks(60);
        awaitClientChunks(ctx);
        giveLoadout(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_RUN, "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
        for (int i = 0; i < 600 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_RUN, "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        // Wait for the FULL floor graph to know the floor, as a real run does long before its puzzles (graphWarm is true
        // on the quick graph already; the first try planned on it and was refused as impossible).
        // The warm-up runs again whenever the world changes (the gate, then his own teleport into a new area), so wait until
        // a warm line is followed by 10 s with no warm-up running.
        awaitGraphQuiet(ctx);
        // Auto Blaze would start on the stand (the puzzle arms when he is in the room) and move him.
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                "setAutoPuzzlesMasterEnabled", false));
        BlockPos[] spots = ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall("com.killer560.hub.livemap.DungeonLayout", "capture");
            BlockPos stand = null;
            BlockPos goal = null;
            for (Object o : (List<?>) ModUnderTest.staticCall(ROOM_STATUS, "rooms")) {
                String n = (String) ModUnderTest.call(o, "name", new Class<?>[0], new Object[0]);
                int id = (Integer) ModUnderTest.call(o, "room", new Class<?>[0], new Object[0]);
                int main = (Integer) ModUnderTest.call(o, "mainTile", new Class<?>[0], new Object[0]);
                if (n.equals("Higher Blaze")) {
                    int[] cr = (int[]) ModUnderTest.call(layout, "clayRotation", new Class<?>[]{int.class}, new Object[]{id});
                    if (cr != null) {
                        try {
                            Object frame = Class.forName("com.killer560.hub.autoroutes.RouteCoords$Frame")
                                    .getConstructor(String.class, int.class, int.class, int.class)
                                    .newInstance(n, cr[0], cr[1], cr[2]);
                            stand = (BlockPos) ModUnderTest.staticCall("com.killer560.hub.autoroutes.RouteCoords",
                                    "toRealBlock", new Class<?>[]{frame.getClass(), BlockPos.class},
                                    new Object[]{frame, new BlockPos(6, 116, 25)});
                        } catch (ReflectiveOperationException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
            // Auto Secret's goal for Teleport Maze in run 004, the first trip that failed (this floor code builds at the
            // same height every time: his stand reads -130, 301, -15 as it did then).
            goal = new BlockPos(-89, 253, -42);
            return new BlockPos[]{stand, goal};
        });
        println("[" + HIGH_NAME + "] his Higher Blaze stand " + spots[0] + ", Teleport Maze goal " + spots[1]);
        if (spots[0] == null || spots[1] == null) {
            return "no Higher Blaze stand or Teleport Maze goal on this floor";
        }
        Vec3 at = new Vec3(spots[0].getX() + 0.5, spots[0].getY() + 1, spots[0].getZ() + 0.5);
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> server.getPlayerList().getPlayer(uuid).teleportTo(server.overworld(), at.x, at.y, at.z,
                    java.util.Set.<net.minecraft.world.entity.Relative>of(), 0f, 0f, false));
        });
        ctx.waitFor(mc -> mc.player.position().distanceTo(at) < 0.3, 200);
        awaitGraphQuiet(ctx);
        BlockPos goal = spots[1];
        long mark = LogTap.mark();
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.livemap.autoclear.ClearExecutor", "etherPathExact",
                new Class<?>[]{BlockPos.class, Runnable.class}, new Object[]{goal, null}));
        Vec3 want = new Vec3(goal.getX() + 0.5, goal.getY() + 1, goal.getZ() + 0.5);
        boolean arrived = false;
        for (int i = 0; i < 600 && !arrived; i++) {
            ctx.waitTicks(1);
            arrived = ctx.computeOnClient(mc -> mc.player.position().distanceTo(want) < 1.0);
        }
        for (String l : LogTap.since(mark)) {
            if (l.contains("[Path]")) {
                println("[" + HIGH_NAME + "]   " + clip(l));
            }
        }
        boolean noWay = LogTap.since(mark).stream().anyMatch(l -> l.contains("no way to"));
        println("[" + HIGH_NAME + "] from " + spots[0].toShortString() + " to Teleport Maze: arrived " + arrived
                + ", \"no way\" " + noWay);
        return arrived ? null : "the Interactive Map never took him from Higher Blaze's chest stand to Teleport Maze"
                + (noWay ? " (\"no way ... from here\")" : "");
    }

    /** Until the floor graph's warm-up has said nothing for 10 s (at most 2 minutes). */
    private static void awaitGraphQuiet(ClientGameTestContext ctx) {
        long warmMark = LogTap.mark();
        int quiet = 0;
        for (int i = 0; i < 20 * 120 && quiet < 200; i += 10) {
            ctx.waitTicks(10);
            long m2 = LogTap.mark();
            boolean busy = LogTap.since(warmMark).stream().anyMatch(l -> l.contains("warm-up") || l.contains("graph warm"));
            warmMark = m2;
            quiet = busy ? 0 : quiet + 10;
        }
    }

    // ================================================================================================ helpers

    private static final java.util.Random RNG = new java.util.Random();

    /** A planned F7 the way the designer's Generate makes one with his filters; its map code, or null. */
    private static String planFloor() {
        String filters = "com.killer560.hub.roomsim.SimRoomFilters";
        ModUnderTest.staticCall(filters, "load");
        Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
        int minBlood = (Integer) staticField(FLOOR_GEN, "MIN_ROOMS_TO_BLOOD");
        int minPuzzles = (Integer) staticField(FLOOR_GEN, "MIN_PUZZLES");
        int maxBlood = (Integer) ModUnderTest.call(floor, "maxRoomsToBlood", new Class<?>[0], new Object[0]);
        int blood = minBlood + RNG.nextInt(Math.max(1, maxBlood - minBlood + 1));
        int maxPuzzles = (Integer) ModUnderTest.call(floor, "maxPuzzles", new Class<?>[]{int.class}, new Object[]{blood});
        int puzzles = minPuzzles + RNG.nextInt(Math.max(1, maxPuzzles - minPuzzles + 1));
        boolean isDefault = (Boolean) ModUnderTest.staticCall(filters, "isDefault");
        java.util.function.Predicate<String> allow = isDefault ? null : n -> (Boolean) ModUnderTest.staticCall(filters,
                "generatorAllows", new Class<?>[]{String.class}, new Object[]{n});
        Object planned = ModUnderTest.staticCall(FLOOR_GEN, "plan", new Class<?>[]{floor.getClass(), int.class, int.class,
                Map.class, java.util.function.Predicate.class}, new Object[]{floor, puzzles, blood, null, allow});
        if (planned == null) {
            return null;
        }
        println(String.format(Locale.US, "planned an F7: %d puzzle(s) asked, %d to blood, filters %s, note %s", puzzles,
                blood, isDefault ? "none" : "his", ModUnderTest.call(planned, "filterNote", new Class<?>[0], new Object[0])));
        return (String) ModUnderTest.call(planned, "code", new Class<?>[0], new Object[0]);
    }

    private static Object staticField(String cls, String name) {
        try {
            return Class.forName(cls).getField(name).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Room room(Tally t, String name) {
        return t.rooms.computeIfAbsent(name, n -> {
            Room r = new Room();
            r.name = n;
            return r;
        });
    }

    @SuppressWarnings("unchecked")
    private static Set<String> routedRooms() {
        Set<String> out = new LinkedHashSet<>();
        try {
            Path file = (Path) ModUnderTest.staticCall(STORE, "routesFile");
            var root = com.google.gson.JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (var e : root.getAsJsonObject("routes").entrySet()) {
                if (e.getValue().isJsonObject() && e.getValue().getAsJsonObject().has("nodes")
                        && e.getValue().getAsJsonObject().getAsJsonArray("nodes").size() > 0) {
                    out.add(e.getKey());
                }
            }
        } catch (Exception e) {
            println("could not read the routes file: " + e);
        }
        return out;
    }

    /** His hotbar as his logs show it (AOTV slot 2, Spirit Sceptre 3, Terminator 4, Dungeon Breaker 8), plus a Hyperion
     *  and Superbooms for the boom nodes. */
    private static void giveLoadout(ClientGameTestContext ctx) {
        AtomicReference<Boolean> given = new AtomicReference<>();
        String[] kit = {"HYPERION", "ASPECT_OF_THE_VOID", "BAT_WAND", "TERMINATOR", "SUPERBOOM_TNT", "ENDER_PEARL", null,
                "DUNGEONBREAKER", null};
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
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
                for (int i = 0; i < kit.length; i++) {
                    if (kit[i] == null) {
                        continue;
                    }
                    ItemStack s = (ItemStack) ModUnderTest.staticCall(SIM_ITEMS, "build", new Class<?>[]{String.class},
                            new Object[]{kit[i]});
                    if (kit[i].equals("SUPERBOOM_TNT")) {
                        s.setCount(64);
                    }
                    inv.setItem(i, s);
                }
                given.set(true);
            });
        });
        ctx.waitFor(mc -> given.get() != null, 200);
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(1));
        ctx.waitTicks(10);
    }

    /** Waits (at most 60 s) for the client to hold every chunk round the player within render distance - the real
     *  chunk source, since {@code ClientLevel.hasChunk} is always true. */
    private static void awaitClientChunks(ClientGameTestContext ctx) {
        int missing = -1;
        int waited = 0;
        for (; waited < 1200; waited += 10) {
            missing = ctx.computeOnClient(mc -> {
                int r = Math.min(6, mc.options.renderDistance().get() - 2);
                int pcx = mc.player.blockPosition().getX() >> 4;
                int pcz = mc.player.blockPosition().getZ() >> 4;
                int n = 0;
                for (int cx = pcx - r; cx <= pcx + r; cx++) {
                    for (int cz = pcz - r; cz <= pcz + r; cz++) {
                        if (!mc.level.getChunkSource().hasChunk(cx, cz)) {
                            n++;
                        }
                    }
                }
                return n;
            });
            if (missing == 0 && ctx.computeOnClient(mc -> mc.player.onGround())) {
                break;
            }
            ctx.waitTicks(10);
        }
        println("client chunks round him: " + (missing == 0 ? "all, after " + waited / 20.0 + " s" : missing + " missing"));
    }

    private static void leave(ClientGameTestContext ctx) {
        try {
            ctx.runOnClient(mc -> {
                if (mc.level != null && (Boolean) ModUnderTest.staticCall(AS, "isRunning")) {
                    ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class},
                            new Object[]{"test over", true});
                }
            });
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "leave"));
            ctx.runOnClient(mc -> mc.execute(() -> {
                if (mc.level != null) {
                    mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                    mc.disconnectWithSavingScreen();
                }
            }));
            ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 2400);
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> mc.execute(() ->
                    McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
            ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen);
        } catch (Throwable e) {
            println("leaving the sim threw: " + e);
        }
    }

    private static String write(Tally t) {
        String dir = System.getProperty("testkit.fullclearOut", "");
        Path out;
        try {
            Path base = dir.isBlank() ? Path.of(System.getProperty("testkit.reportDir", "build")) : Path.of(dir);
            Files.createDirectories(base);
            int n = 1;
            while (Files.exists(base.resolve(String.format(Locale.US, "run-%03d.md", n)))) {
                n++;
            }
            out = base.resolve(String.format(Locale.US, "run-%03d.md", n));
            StringBuilder sb = new StringBuilder();
            sb.append("# ").append(out.getFileName()).append(" - ").append(t.verdict()).append("\n\n");
            sb.append("Map code: `").append(t.code).append("`\n\n");
            sb.append(String.format(Locale.US, "Ran %.1f s, travelled %.0f blocks, secrets %d/%d (sim), tab/map %.1f%%, "
                            + "Auto Secret ended: %s. Corrections %d, warps never landed %d, start nodes not armed %d.\n\n",
                    t.seconds, t.travelled, t.secretsFound, t.secretsTotal, t.mapPercent, t.asStop, t.corrections,
                    t.noLanding, t.unarmed));
            sb.append("GrimAC: not applicable (the sim's integrated server runs no anticheat).\n\n");
            sb.append("## Problems\n\n");
            for (String p : t.problems) {
                sb.append("- ").append(p).append('\n');
            }
            if (t.problems.isEmpty()) {
                sb.append("- none\n");
            }
            sb.append("\n## Notes\n\n");
            for (String p : t.notes) {
                sb.append("- ").append(p).append('\n');
            }
            sb.append("\n## Rooms\n\n| room | type | route | visited | started/finished | sim found/placed | map found/total | map |\n");
            sb.append("|---|---|---|---|---|---|---|---|\n");
            for (Room r : new TreeMap<>(t.rooms).values()) {
                sb.append(String.format(Locale.US, "| %s | %s | %s | %s | %d/%d | %d/%d | %d/%d | %s |\n", r.name, r.type,
                        r.routed ? "yes" : "-", r.visited ? "yes" : "NO", r.started, r.finished, r.found, r.placed,
                        r.mapSecrets - r.mapUnfound, r.mapSecrets, r.mapFailed ? "failed" : r.mapCleared ? "cleared" : "-"));
            }
            sb.append("\n## Stalls (3 s or more still)\n\n");
            for (String s : t.stalls) {
                sb.append("- ").append(s).append('\n');
            }
            sb.append("\n## Auto Routes stops\n\n");
            for (String s : t.arStops) {
                sb.append("- ").append(s).append('\n');
            }
            sb.append("\n## Events\n\n");
            for (String s : t.events) {
                sb.append("- ").append(s).append('\n');
            }
            if (!t.errors.isEmpty()) {
                sb.append("\n## Mod errors\n\n");
                for (String s : t.errors) {
                    sb.append("- ").append(s).append('\n');
                }
            }
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            Path logCopy = base.resolve(out.getFileName().toString().replace(".md", ".log"));
            Files.write(logCopy, t.log, StandardCharsets.UTF_8);
            return out.toString();
        } catch (Exception e) {
            println("could not write the tally: " + e);
            return "(not written)";
        }
    }

    private static String clip(String l) {
        return l.length() > 400 ? l.substring(0, 400) + "..." : l;
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}

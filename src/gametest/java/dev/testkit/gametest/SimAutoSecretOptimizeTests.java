package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto Secret's hand-over from one route to the next room, and the order it takes the rooms in, on a generated sim F7
 * (mod branch ac-optimize-1015). killer560, 2026-10-15: "it needs to predict when it is going to finish auto routing a room
 * that way the very next tick it starts etherwarping to the next room. It needs a wait timeout at the last node of 6
 * ticks at max if all secrets are not collected by the time it hits that node. If during those 6 ticks the room is fully
 * cleared then it should leave the next tick. Also you need to focus more on not etherwarping all the way across the
 * map." Floors, routes and run set up as 102-sim-autosecret does (one-node start routes seeded into chosen rooms).
 *
 * <ul>
 *   <li><b>144-sim-autosecret-handoff</b>: four routed rooms, taken in whatever order it picks. The first one's secrets
 *       all count before its route ends: the first warp to the next room goes the tick after the route finished. The
 *       second's never count: the first warp goes exactly {@code 6} ticks after. The third's count two ticks into the
 *       wait: the warp goes on the tick they count. Every hand-over runs the path planned while he travelled ("planned
 *       ahead"), with no search.</li>
 *   <li><b>145-sim-autosecret-order</b>: three routed rooms - two small ones beside the Entrance and one with more
 *       unfound secrets across the floor. Run twice from the Entrance: the old order (most unfound first) crosses the map
 *       first and comes back; the new one takes the two beside him first. The new run must not start across the map,
 *       and must finish all three in fewer ticks.</li>
 * </ul>
 * Only when named.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimAutoSecretOptimizeTests implements FabricClientGameTest {

    private static final String HANDOFF = "144-sim-autosecret-handoff";
    private static final String ORDER = "145-sim-autosecret-order";
    private static final String ARRIVAL = "146-sim-autosecret-arrival";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String SIM_SCORE = "com.killer560.hub.roomsim.SimScore";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String LIVE_MAP = "com.killer560.hub.livemap.LiveMapFeature";
    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    private static final String EXECUTOR = "com.killer560.hub.livemap.autoclear.ClearExecutor";
    private static final String AR_CONFIG = "com.killer560.hub.autoroutes.AutoRoutesConfig";
    private static final String AS = "com.killer560.hub.autosecret.AutoSecretFeature";
    private static final String AS_CONFIG = "com.killer560.hub.autosecret.AutoSecretConfig";
    private static final String MEMORY = "com.killer560.hub.autoroutes.RouteRunMemory";
    private static final String DB = "com.killer560.hub.roomdatabase.RoomDatabase";

    private static final Pattern TARGET = Pattern.compile("\\[AutoSecret\\] target (.+?): (\\d+) unfound");
    private static final Pattern FINISHED = Pattern.compile("\\[AutoSecret\\] route (.+?) finished \\(tick (\\d+)\\)");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank()) {
            return;
        }
        boolean handoff = !Scenario.skip(HANDOFF);
        boolean order = !Scenario.skip(ORDER);
        boolean arrival = !Scenario.skip(ARRIVAL);
        if (!handoff && !order && !arrival) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            if (handoff) {
                Scenario.skipped(HANDOFF, "needs real room captures and room database");
            }
            if (order) {
                Scenario.skipped(ORDER, "needs real room captures and room database");
            }
            if (arrival) {
                Scenario.skipped(ARRIVAL, "needs real room captures and room database");
            }
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        configure(ctx);
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> mc.options.renderDistance().set(16));
        List<String> failed = new ArrayList<>();
        if (handoff) {
            try {
                runCase(ctx, HANDOFF, SimAutoSecretOptimizeTests::handoffBody);
            } catch (AssertionError e) {
                failed.add(e.getMessage());   // the other case still runs
            }
        }
        if (order) {
            try {
                runCase(ctx, ORDER, SimAutoSecretOptimizeTests::orderBody);
            } catch (AssertionError e) {
                failed.add(e.getMessage());
            }
        }
        if (arrival) {
            try {
                runCase(ctx, ARRIVAL, SimAutoSecretOptimizeTests::arrivalBody);
            } catch (AssertionError e) {
                failed.add(e.getMessage());
            }
        }
        ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
        if (!failed.isEmpty()) {
            throw new AssertionError(String.join(" | ", failed));
        }
    }

    private interface Body {
        void run(ClientGameTestContext ctx, List<String> failures);
    }

    /** Set by a body that called {@code Scenario.skipped}: its case ends SKIPPED, not PASS. */
    private static boolean caseSkipped;

    private static void runCase(ClientGameTestContext ctx, String name, Body body) {
        List<String> failures = new ArrayList<>();
        caseSkipped = false;
        try {
            body.run(ctx, failures);
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> {
                if ((Boolean) ModUnderTest.staticCall(AS, "isRunning")) {
                    ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class},
                            new Object[]{"test over", true});
                }
                try {
                    ModUnderTest.staticCall(AS, "testLegacyOrder", new Class<?>[]{boolean.class}, new Object[]{false});
                } catch (Throwable ignored) {
                    // not in this jar (ModUnderTest throws an AssertionError for a missing call)
                }
            });
            SimAutoSecretTests.writeRoutes(ctx, Map.of());
            leaveSim(ctx);
        }
        for (String f : failures) {
            println(name, "FAIL: " + f);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(name + ": " + failures.size() + " problem(s): " + String.join("; ", failures));
        }
        println(name, caseSkipped ? "SKIPPED (see above)" : "PASS");
    }

    // ================================================================================================ 144

    private static void handoffBody(ClientGameTestContext ctx, List<String> failures) {
        enterFloor(ctx);
        awaitWarmGraph(ctx, HANDOFF);
        List<SimAutoSecretTests.Room> rooms = ctx.computeOnClient(SimAutoSecretTests::readRooms);
        List<SimAutoSecretTests.Room> eligible = eligible(rooms);
        eligible.sort((a, b) -> Integer.compare(b.unfound(), a.unfound()));
        Map<String, JsonObject> routes = new LinkedHashMap<>();
        for (SimAutoSecretTests.Room r : eligible) {
            if (routes.size() >= 4) {
                break;
            }
            JsonObject route = SimAutoSecretTests.routeFor(ctx, r);
            if (route != null) {
                routes.put(r.name(), route);
            }
        }
        println(HANDOFF, "routed: " + routes.keySet() + " (of " + SimAutoSecretTests.describe(eligible) + ")");
        if (routes.size() < 4) {
            failures.add("only " + routes.size() + " room(s) could be routed - need four");
            return;
        }
        SimAutoSecretTests.writeRoutes(ctx, routes);
        long mark = LogTap.mark();
        Boolean started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "start"));
        if (!Boolean.TRUE.equals(started)) {
            failures.add("Auto Secret refused to start");
            return;
        }
        // Each target in turn: 1 = all its secrets counted before its route ends, 2 = never, 3 = two ticks into the wait.
        List<String> targets = new ArrayList<>();
        Map<String, Long> finishedAt = new HashMap<>();
        Map<String, Long> markedAt = new HashMap<>();
        Map<String, Integer> modeOf = new HashMap<>();
        for (int t = 0; t < 20 * 150 && finishedAt.size() < 3; t++) {
            ctx.waitTicks(1);
            List<String> log = LogTap.since(mark);
            for (String l : log) {
                Matcher m = TARGET.matcher(l);
                if (m.find() && !targets.contains(m.group(1))) {
                    String room = m.group(1);
                    targets.add(room);
                    int mode = targets.size();
                    modeOf.put(room, mode);
                    if (mode == 1) {
                        markedAt.put(room, markAllFound(ctx, room));
                        println(HANDOFF, "target 1 " + room + ": every secret marked found while he travels there");
                    } else {
                        println(HANDOFF, "target " + mode + " " + room + (mode == 2 ? ": left unfound"
                                : ": marked found two ticks into its wait"));
                    }
                }
                Matcher f = FINISHED.matcher(l);
                if (f.find() && !finishedAt.containsKey(f.group(1))) {
                    finishedAt.put(f.group(1), Long.parseLong(f.group(2)));
                    Integer mode = modeOf.get(f.group(1));
                    if (mode != null && mode == 3) {
                        ctx.waitTicks(1);
                        markedAt.put(f.group(1), markAllFound(ctx, f.group(1)));
                    }
                }
            }
            if (!(Boolean) ctx.computeOnClient(mc -> ModUnderTest.staticCall(AS, "isRunning"))) {
                break;
            }
        }
        ctx.waitTicks(40);
        @SuppressWarnings("unchecked")
        List<Long> hops = ctx.computeOnClient(mc -> (List<Long>) ModUnderTest.staticCall(EXECUTOR, "hopSendTicks"));
        List<String> log = LogTap.since(mark);
        for (String l : log) {
            if (l.contains("[AutoSecret]") || l.contains("[Path] running") || l.contains("[Path] planned ahead")
                    || l.contains("[Path] the path planned ahead")) {
                println(HANDOFF, "  " + l.replaceAll("^.*?(\\[AutoSecret\\]|\\[Path\\])", "$1"));
            }
        }
        println(HANDOFF, "targets " + targets + ", finished at " + finishedAt + ", marked at " + markedAt + ", hop ticks "
                + hops);
        if (targets.size() < 3 || finishedAt.size() < 3) {
            failures.add("fewer than three routes ran to their end (" + finishedAt.keySet() + ")");
            return;
        }
        for (int k = 0; k < 3; k++) {
            String room = targets.get(k);
            Long end = finishedAt.get(room);
            if (end == null) {
                failures.add(room + "'s route never finished");
                continue;
            }
            long first = -1;
            for (long h : hops) {
                if (h > end) {
                    first = h;
                    break;
                }
            }
            boolean planned = false;
            for (int i = 0; i < log.size(); i++) {
                if (log.get(i).contains("[AutoSecret] route " + room + " finished")) {
                    for (int j = i; j < Math.min(log.size(), i + 40) && !planned; j++) {
                        planned = log.get(j).contains("planned ahead from") && log.get(j).contains("no search");
                    }
                }
            }
            long after = first < 0 ? -1 : first - end;
            int mode = k + 1;
            String what = switch (mode) {
                case 1 -> "every secret counted at the end: the first warp the next tick";
                case 2 -> "none counted: the first warp at the 6-tick cap";
                default -> "counted two ticks into the wait: the first warp on the tick they count";
            };
            println(HANDOFF, String.format(Locale.US, "%s (%s): route finished on tick %d, first warp on tick %d (%+d), path"
                    + " planned ahead and run with no search: %s", room, what, end, first, after, planned));
            if (!planned) {
                failures.add(room + ": the trip after its route was searched again, not the one planned ahead");
            }
            if (mode == 1 && after != 1) {
                failures.add(room + ": every secret counted, but the first warp went " + after + " tick(s) after the route"
                        + " (want 1)");
            }
            if (mode == 2 && after != 6) {
                failures.add(room + ": nothing counted, but the first warp went " + after + " tick(s) after the route (want"
                        + " the 6-tick cap)");
            }
            if (mode == 3) {
                Long m = markedAt.get(room);
                long fromMark = m == null || first < 0 ? -1 : first - m;
                println(HANDOFF, room + ": secrets counted on tick " + m + ", first warp " + fromMark + " tick(s) later");
                if (after >= 6 || fromMark != 1) {
                    failures.add(room + ": counted on tick " + m + " (route end " + end + "), first warp on " + first
                            + " - want the tick they count, before the cap");
                }
            }
        }
        boolean capSaid = log.stream().anyMatch(l -> l.contains("the 6-tick cap"));
        boolean earlySaid = log.stream().anyMatch(l -> l.contains("every secret counted after"));
        if (!capSaid || !earlySaid) {
            failures.add("the wait's lines are missing (cap " + capSaid + ", early " + earlySaid + ")");
        }
    }

    // ================================================================================================ 146

    /** At most this many ticks from the last warp of the trip onto a start node to its one-node route ending there. */
    private static final int ARRIVAL_MAX_TICKS = 4;

    /**
     * 146-sim-autosecret-arrival (mod ac-optimize-2-1015; killer560 wants routes as fast as possible): four rooms with a
     * one-node start route each, every secret marked found as each becomes the target (so no last-node wait). For each
     * room, the ticks from the last warp of the trip into it to its route finishing - a one-node route finishes on the
     * tick it arms, so this is the warp's landing plus everything the route waited for before it could arm. The
     * Interactive Map used to keep the trip "busy" for ~9 ticks after the landing (its arrival settle) and Auto Routes
     * does not arm while it is: about 11 ticks. Now the trip ends on the tick the server confirms the landing.
     */
    private static void arrivalBody(ClientGameTestContext ctx, List<String> failures) {
        enterFloor(ctx);
        awaitWarmGraph(ctx, ARRIVAL);
        List<SimAutoSecretTests.Room> eligible = eligible(ctx.computeOnClient(SimAutoSecretTests::readRooms));
        eligible.sort((a, b) -> Integer.compare(b.unfound(), a.unfound()));
        Map<String, JsonObject> routes = new LinkedHashMap<>();
        for (SimAutoSecretTests.Room r : eligible) {
            if (routes.size() >= 4) {
                break;
            }
            JsonObject route = SimAutoSecretTests.routeFor(ctx, r);
            if (route != null) {
                routes.put(r.name(), route);
            }
        }
        if (routes.size() < 4) {
            failures.add("only " + routes.size() + " room(s) could be routed - need four");
            return;
        }
        SimAutoSecretTests.writeRoutes(ctx, routes);
        long mark = LogTap.mark();
        long t0 = tick(ctx);
        Boolean started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "start"));
        if (!Boolean.TRUE.equals(started)) {
            failures.add("Auto Secret refused to start");
            return;
        }
        List<String> targets = new ArrayList<>();
        Map<String, Long> finishedAt = new LinkedHashMap<>();
        for (int t = 0; t < 20 * 150 && finishedAt.size() < routes.size(); t++) {
            ctx.waitTicks(1);
            for (String l : LogTap.since(mark)) {
                Matcher m = TARGET.matcher(l);
                if (m.find() && !targets.contains(m.group(1))) {
                    targets.add(m.group(1));
                    markAllFound(ctx, m.group(1));
                }
                Matcher f = FINISHED.matcher(l);
                if (f.find() && !finishedAt.containsKey(f.group(1))) {
                    finishedAt.put(f.group(1), Long.parseLong(f.group(2)));
                }
            }
            if (!(Boolean) ctx.computeOnClient(mc -> ModUnderTest.staticCall(AS, "isRunning"))) {
                break;
            }
        }
        @SuppressWarnings("unchecked")
        List<Long> hops = ctx.computeOnClient(mc -> (List<Long>) ModUnderTest.staticCall(EXECUTOR, "hopSendTicks"));
        for (String l : LogTap.since(mark)) {
            if (l.contains("[Path] arrived") || l.contains("[AutoSecret] route") || l.contains("[AutoSecret] target")
                    || l.contains("settling after the map")) {
                println(ARRIVAL, "  " + l.replaceAll("^.*?(\\[AutoSecret\\]|\\[Path\\]|\\[AutoRoutes\\])", "$1"));
            }
        }
        println(ARRIVAL, "finished at " + finishedAt + ", hop ticks " + hops);
        if (finishedAt.size() < 3) {
            failures.add("fewer than three routes ran to their end (" + finishedAt.keySet() + ")");
            return;
        }
        long from = t0;
        List<Long> gaps = new ArrayList<>();
        for (Map.Entry<String, Long> e : finishedAt.entrySet()) {
            long end = e.getValue();
            long last = -1;
            for (long h : hops) {
                if (h > from && h <= end) {
                    last = h;
                }
            }
            if (last < 0) {
                println(ARRIVAL, e.getKey() + ": no warp of a trip into it (it was where he stood) - not counted");
            } else {
                gaps.add(end - last);
                println(ARRIVAL, String.format(Locale.US, "%s: last warp of the trip on tick %d, route finished on tick"
                        + " %d: %d tick(s) from the warp to the route", e.getKey(), last, end, end - last));
                if (end - last > ARRIVAL_MAX_TICKS) {
                    failures.add(e.getKey() + ": " + (end - last) + " ticks from the trip's last warp to its start node's"
                            + " route - the arrival settle is still there (want at most " + ARRIVAL_MAX_TICKS + ")");
                }
            }
            from = end;
        }
        if (gaps.size() < 2) {
            failures.add("fewer than two trips into a routed room were measured (" + gaps + ")");
        }
        println(ARRIVAL, "warp-to-route ticks per room: " + gaps);
    }

    // ================================================================================================ 145

    private static void orderBody(ClientGameTestContext ctx, List<String> failures) {
        SimAutoSecretTests.Room n1 = null;
        SimAutoSecretTests.Room n2 = null;
        SimAutoSecretTests.Room far = null;
        for (int attempt = 0; attempt < 12 && far == null; attempt++) {
            if (attempt > 0) {
                leaveSim(ctx);
            }
            enterFloor(ctx);
            List<SimAutoSecretTests.Room> eligible = eligible(ctx.computeOnClient(SimAutoSecretTests::readRooms));
            // Distances are ROOMS on the map's path (a trip goes round by the doors: on 2026-10-15 two rooms 45 blocks
            // apart in a straight line were 13 etherwarps, six rooms, apart).
            Map<String, Integer> fromHere = new HashMap<>();
            Map<String, Map<String, Integer>> between = new HashMap<>();
            ctx.runOnClient(mc -> {
                Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
                int here = (Integer) ModUnderTest.call(layout, "currentRoom", new Class<?>[]{}, new Object[]{});
                for (SimAutoSecretTests.Room a : eligible) {
                    fromHere.put(a.name(), steps(layout, here, a.id()));
                    Map<String, Integer> row = new HashMap<>();
                    for (SimAutoSecretTests.Room b : eligible) {
                        row.put(b.name(), steps(layout, a.id(), b.id()));
                    }
                    between.put(a.name(), row);
                }
            });
            eligible.sort((a, b) -> Integer.compare(fromHere.get(a.name()), fromHere.get(b.name())));
            List<String> shown = new ArrayList<>();
            for (SimAutoSecretTests.Room r : eligible) {
                shown.add(r.name() + "=" + r.unfound() + "@" + fromHere.get(r.name()));
            }
            println(ORDER, "floor " + (attempt + 1) + ": eligible (unfound@rooms away) " + shown);
            // His example, as near as a floor has it: two small rooms beside him (1 or 2 unfound), a room across the floor
            // with more than either but at most 3 - most unfound first crosses the map for it and comes back.
            awaitWarmGraph(ctx, ORDER);
            Vec3 here = ctx.computeOnClient(mc -> mc.player.position());
            int tried = 0;
            for (int i = 0; i < eligible.size() && far == null && tried < 10; i++) {
                for (int j = 0; j < eligible.size() && far == null && tried < 10; j++) {
                    SimAutoSecretTests.Room a = eligible.get(i);
                    SimAutoSecretTests.Room b = eligible.get(j);
                    if (i == j || a.unfound() > 2 || b.unfound() > 2 || fromHere.get(a.name()) > 3
                            || between.get(a.name()).get(b.name()) > 3) {
                        continue;
                    }
                    for (SimAutoSecretTests.Room f : eligible) {
                        if (f.unfound() <= Math.max(a.unfound(), b.unfound()) || f.unfound() > 3 || fromHere.get(f.name()) < 3
                                || between.get(a.name()).get(f.name()) < 3 || between.get(b.name()).get(f.name()) < 3) {
                            continue;
                        }
                        // The rooms on the map are a guess at the trips: what the mod's own planner takes decides.
                        tried++;
                        BlockPos sa = standBlock(ctx, a);
                        BlockPos sb = standBlock(ctx, b);
                        BlockPos sf = standBlock(ctx, f);
                        if (sa == null || sb == null || sf == null) {
                            continue;
                        }
                        int hA = warps(ctx, here, sa);
                        int aB = warps(ctx, feet(sa), sb);
                        int bA = warps(ctx, feet(sb), sa);
                        int hF = warps(ctx, here, sf);
                        int aF = warps(ctx, feet(sa), sf);
                        int bF = warps(ctx, feet(sb), sf);
                        println(ORDER, String.format(Locale.US, "candidates %s=%d, %s=%d, %s=%d: warps here->%s %d, %s<->%s"
                                        + " %d/%d, here->%s %d, %s->%s %d, %s->%s %d", a.name(), a.unfound(), b.name(),
                                b.unfound(), f.name(), f.unfound(), a.name(), hA, a.name(), b.name(), aB, bA, f.name(), hF,
                                a.name(), f.name(), aF, b.name(), f.name(), bF));
                        if (hA <= 4 && aB <= 4 && bA <= 4 && hF >= hA + 3 && aF >= aB + 3 && bF >= aB + 3) {
                            n1 = a;
                            n2 = b;
                            far = f;
                            println(ORDER, "beside him: " + a.name() + " and " + b.name() + "; across the floor: "
                                    + f.name());
                            break;
                        }
                        if (tried >= 10) {
                            break;
                        }
                    }
                }
            }
        }
        if (far == null) {
            // The floors are random: a run of floors with no such three rooms is the dice, not the mod - said loudly.
            Scenario.skipped(ORDER, "no floor in 12 had two small rooms beside the Entrance and a bigger (at most 3) one"
                    + " across it, by the planner's own warps - run it again");
            caseSkipped = true;
            return;
        }
        Map<String, JsonObject> routes = new LinkedHashMap<>();
        for (SimAutoSecretTests.Room r : List.of(n1, n2, far)) {
            JsonObject route = SimAutoSecretTests.routeFor(ctx, r);
            if (route == null) {
                failures.add("no standable block for a route in " + r.name());
                return;
            }
            routes.put(r.name(), route);
        }
        SimAutoSecretTests.writeRoutes(ctx, routes);
        Vec3 start = ctx.computeOnClient(mc -> mc.player.position());
        Map<String, Integer> unfound = Map.of(n1.name(), n1.unfound(), n2.name(), n2.unfound(), far.name(), far.unfound());
        Run fresh = runOrder(ctx, false, start, routes.keySet(), unfound);
        Run old = runOrder(ctx, true, start, routes.keySet(), unfound);
        println(ORDER, "new order " + fresh.order + ": all three in " + fresh.ticks + " ticks, secret-weighted wait "
                + fresh.secretWait + ", " + String.format(Locale.US, "%.0f", fresh.travelled) + " blocks; old order " + old.order
                + ": " + old.ticks + " ticks, wait " + old.secretWait + ", " + String.format(Locale.US, "%.0f", old.travelled)
                + " blocks");
        if (fresh.order.size() < 3 || old.order.size() < 3) {
            failures.add("a run did not take all three rooms (new " + fresh.order + ", old " + old.order + ")");
            return;
        }
        if (!old.order.get(0).equals(far.name())) {
            failures.add("the old order did not cross the map first (" + old.order + ") - the floor does not test it");
        }
        if (fresh.order.get(0).equals(far.name()) || !fresh.order.get(2).equals(far.name())) {
            failures.add("the new order crossed the map for " + far.name() + " before the rooms beside him: " + fresh.order);
        }
        if (fresh.ticks >= old.ticks) {
            failures.add("the new order took " + fresh.ticks + " ticks, the old " + old.ticks + " - not faster");
        }
        // The secret-weighted wait in real ticks is printed, not judged: every trip pays the same ~20 ticks of search,
        // sneak and arrival settle in the sim whatever its length, so by real ticks the big room first can wait a few
        // secret-ticks less while taking longer in all (2026-10-15: 193 against 189, 67 ticks against 75). The planner
        // weighs secrets per tick of TRAVEL - killer560's own measure - and total ticks are judged above.
    }

    /** @param wait each room's unfound secrets times the ticks until its route finished, summed */
    private record Run(List<String> order, long ticks, double travelled, long secretWait) {
    }

    private static Run runOrder(ClientGameTestContext ctx, boolean legacy, Vec3 start, java.util.Set<String> routed,
                                Map<String, Integer> unfound) {
        String label = legacy ? "old" : "new";
        teleport(ctx, start);
        boolean hooked = ctx.computeOnClient(mc -> {
            ModUnderTest.staticCall(MEMORY, "reset");
            try {
                ModUnderTest.staticCall(AS, "testLegacyOrder", new Class<?>[]{boolean.class}, new Object[]{legacy});
                return true;
            } catch (Throwable t) {
                return false;
            }
        });
        if (!hooked) {
            // Thrown on the test thread: a throw inside a client task ends the whole gametest run.
            throw new AssertionError("this jar has no AutoSecretFeature.testLegacyOrder - the old order cannot be run"
                    + " beside the new one");
        }
        long mark = LogTap.mark();
        long t0 = tick(ctx);
        Vec3 last = ctx.computeOnClient(mc -> mc.player.position());
        double travelled = 0;
        Boolean started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "start"));
        List<String> order = new ArrayList<>();
        long end = -1;
        Map<String, Long> doneAt = new HashMap<>();
        for (int t = 0; t < 20 * 120 && Boolean.TRUE.equals(started); t++) {
            ctx.waitTicks(1);
            Vec3 now = ctx.computeOnClient(mc -> mc.player.position());
            travelled += now.distanceTo(last);
            last = now;
            order.clear();
            int finished = 0;
            for (String l : LogTap.since(mark)) {
                Matcher m = TARGET.matcher(l);
                if (m.find() && routed.contains(m.group(1)) && !order.contains(m.group(1))) {
                    order.add(m.group(1));
                }
                Matcher f = FINISHED.matcher(l);
                if (f.find() && routed.contains(f.group(1))) {
                    finished++;
                    end = Long.parseLong(f.group(2));
                    doneAt.put(f.group(1), end - t0);
                }
            }
            if (finished >= routed.size()) {
                break;
            }
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class},
                new Object[]{"run over", true}));
        for (String l : LogTap.since(mark)) {
            if (l.contains("[AutoSecret] room order") || l.contains("[AutoSecret] target") || l.contains("finished (tick")) {
                println(ORDER, "  " + label + ": " + l.replaceAll("^.*?\\[AutoSecret\\]", "[AutoSecret]"));
            }
        }
        ctx.waitTicks(20);
        long wait = 0;
        for (Map.Entry<String, Long> e : doneAt.entrySet()) {
            wait += unfound.getOrDefault(e.getKey(), 0) * e.getValue();
        }
        return new Run(new ArrayList<>(order), end < 0 ? Long.MAX_VALUE : end - t0, travelled,
                doneAt.size() < routed.size() ? Long.MAX_VALUE : wait);
    }

    // ================================================================================================ helpers

    private static List<SimAutoSecretTests.Room> eligible(List<SimAutoSecretTests.Room> rooms) {
        List<SimAutoSecretTests.Room> out = new ArrayList<>();
        for (SimAutoSecretTests.Room r : rooms) {
            if (!r.name().equals("Unknown") && !r.type().equals("PUZZLE") && !r.type().equals("TRAP")
                    && !r.type().equals("BLOOD") && !r.type().equals("ENTRANCE") && r.unfound() > 0 && r.reachable()
                    && SimAutoSecretTests.pathsOut(r)) {
                out.add(r);
            }
        }
        return out;
    }

    /** The block a seeded route's start node stands on ({@code SimAutoSecretTests.routeFor}'s choice). */
    private static BlockPos standBlock(ClientGameTestContext ctx, SimAutoSecretTests.Room r) {
        return ctx.computeOnClient(mc -> {
            BlockPos c = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                    new Object[]{r.mainTile()});
            return (BlockPos) ModUnderTest.staticCall("com.killer560.hub.livemap.autoclear.TeleportUtils",
                    "etherwarpableInTile", new Class<?>[]{BlockPos.class, Vec3.class}, new Object[]{c, Vec3.atCenterOf(c)});
        });
    }

    private static Vec3 feet(BlockPos stand) {
        return new Vec3(stand.getX() + 0.5, stand.getY() + 1.05, stand.getZ() + 0.5);
    }

    /** The warps the mod's own map planner takes from {@code from} onto {@code to} (999 for none), on its planner thread. */
    private static int warps(ClientGameTestContext ctx, Vec3 from, BlockPos to) {
        AtomicReference<Integer> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            Object cfg = ModUnderTest.staticCall(EXECUTOR, "pathConfig");
            double range = (Double) ModUnderTest.staticCall(EXECUTOR, "hopRange");
            Runnable job = () -> {
                try {
                    Object path = ModUnderTest.staticCall("com.killer560.hub.livemap.autoclear.EtherwarpPathfinder",
                            "findDungeonPath", new Class<?>[]{Vec3.class, BlockPos.class, cfg.getClass(), double.class,
                                    layout.getClass(), boolean.class}, new Object[]{from, to, cfg, range, layout, false});
                    out.set(path == null ? 999 : ((List<?>) path).size());
                } catch (RuntimeException e) {
                    out.set(999);
                }
            };
            ModUnderTest.staticCall(EXECUTOR, "onPlanner", new Class<?>[]{Runnable.class}, new Object[]{job});
        });
        ctx.waitFor(mc -> out.get() != null, 600);
        return out.get();
    }

    /** Rooms on the map's path from room {@code a} to {@code b} (doors open or not), 99 with none. Client thread. */
    private static int steps(Object layout, int a, int b) {
        if (a == b) {
            return 0;
        }
        Object path = ModUnderTest.staticCall("com.killer560.hub.livemap.autoclear.DungeonMapPathfinder", "findPath",
                new Class<?>[]{layout.getClass(), int.class, int.class, boolean.class}, new Object[]{layout, a, b, true});
        return path == null ? 99 : ((List<?>) path).size() - 1;
    }

    private static double flat(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    private static long tick(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> clientTick());
    }

    /** {@code ClearExecutor.clientTick()}, or -1 on a jar without it (main before ac-optimize-1015). Client thread. */
    private static long clientTick() {
        try {
            return (Long) ModUnderTest.staticCall(EXECUTOR, "clientTick");
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Every secret of {@code room} found: on the sim's server (so its action bar says so from now on) and in the live
     * map's per-room count at once (the action bar comes every ten server ticks; this is the map fact the test supplies,
     * as 102 supplies a wither door).
     */
    @SuppressWarnings("unchecked")
    private static long markAllFound(ClientGameTestContext ctx, String room) {
        AtomicReference<Boolean> done = new AtomicReference<>();
        AtomicReference<Long> at = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            Object entry = ModUnderTest.staticCall(DB, "lookupByName", new Class<?>[]{String.class}, new Object[]{room});
            int secrets;
            try {
                secrets = entry.getClass().getField("secrets").getInt(entry);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            int id = -1;
            int count = (Integer) ModUnderTest.call(layout, "roomCount", new Class<?>[]{}, new Object[]{});
            for (int i = 0; i < count; i++) {
                if (room.equals(ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{i}))) {
                    id = i;
                }
            }
            int[] tiles = (int[]) ModUnderTest.call(layout, "tiles", new Class<?>[]{int.class}, new Object[]{id});
            BlockPos c = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                    new Object[]{tiles[0]});
            int n = secrets;
            mc.getSingleplayerServer().execute(() -> {
                for (int i = 0; i < n; i++) {
                    ModUnderTest.staticCall(SIM_SCORE, "secretFound", new Class<?>[]{BlockPos.class}, new Object[]{c});
                }
                done.set(true);
            });
            try {
                java.lang.reflect.Field f = Class.forName(LIVE_MAP).getDeclaredField("foundSecretsByRoom");
                f.setAccessible(true);
                ((Map<String, Integer>) f.get(null)).put(room, n);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            at.set(clientTick());
        });
        ctx.waitFor(mc -> done.get() != null, 200);
        return at.get();
    }

    /**
     * Waits (at most 120 s) for the Interactive Map's floor graph to be warm, as it is a while into a real run: on a cold
     * graph one search takes a second, and a one-node route ends long before it.
     */
    private static void awaitWarmGraph(ClientGameTestContext ctx, String name) {
        int waited = 0;
        boolean warm = false;
        for (; waited < 2400 && !warm; waited += 10) {
            int w = waited;
            warm = ctx.computeOnClient(mc -> {
                try {
                    return (Boolean) ModUnderTest.staticCall("com.killer560.hub.livemap.autoclear.EtherwarpPathfinder",
                            "graphWarm");
                } catch (Throwable e) {
                    return w >= 400;   // a jar without the call (main): the 20 s a floor graph takes to warm
                }
            });
            if (!warm) {
                ctx.waitTicks(10);
            }
        }
        println(name, "floor graph warm: " + warm + " after " + waited / 20.0 + " s");
    }

    private static void teleport(ClientGameTestContext ctx, Vec3 to) {
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    sp.teleportTo(to.x, to.y, to.z);
                }
                done.set(true);
            });
        });
        ctx.waitFor(mc -> done.get() != null, 200);
        for (int i = 0; i < 100 && ctx.computeOnClient(mc -> mc.player.position().distanceTo(to) > 1.0
                || !mc.player.onGround()); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(20);
    }

    private static void configure(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            Object auto = ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig");
            ModUnderTest.set(auto, "setAutoPuzzlesMasterEnabled", false);
            Object map = ModUnderTest.config(LIVE_MAP_CONFIG);
            ModUnderTest.set(map, "setEnabled", true);
            ModUnderTest.set(map, "setInteractiveMapEnabled", true);
            Object ar = ModUnderTest.config(AR_CONFIG);
            ModUnderTest.set(ar, "setEnabled", true);
            ModUnderTest.set(ar, "setLegitMode", false);
            ModUnderTest.set(ar, "setStartFromStartNodeOnly", true);
            ModUnderTest.set(ar, "setChatFeedback", true);
            ModUnderTest.call(ar, "setHeight", new Class<?>[]{float.class}, new Object[]{1.0f});
            Object as = ModUnderTest.config(AS_CONFIG);
            ModUnderTest.set(as, "setChatFeedback", true);
            ModUnderTest.set(as, "setPuzzleWaitSeconds", 0);
            ModUnderTest.set(as, "setRouteStallSeconds", 10);
            ModUnderTest.set(as, "setInstaClear", false);
            ModUnderTest.set(as, "setIceFillFirst", false);
        });
    }

    /** A generated sim F7 (as 102), the floor's chunks on the client, an AOTV in hand, the run begun, on the ground. */
    private static void enterFloor(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                new Object[]{"gametest"}));
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
        SimAutoSecretTests.awaitFloorChunks(ctx);
        SimAutoSecretTests.giveAotv(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
        for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomsim.SimRun", "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(40);
        for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> mc.player.onGround()); i++) {
            ctx.waitTicks(1);
        }
    }

    private static void leaveSim(ClientGameTestContext ctx) {
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

    private static void println(String name, String s) {
        System.out.println("[" + name + "] " + s);
    }
}

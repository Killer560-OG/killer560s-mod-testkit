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
 *       all count before its route ends; the second's never count; the third's count a tick after its route ends. No
 *       route stands by a secret, so since 2026-10-20 (killer560: wait at most 20 ticks, only when a secret is close,
 *       else leave at once) every first warp to the next room goes the tick after its route finished and no last-node
 *       wait runs (it was a 6-tick cap for the second and third). Every hand-over runs the path planned while he
 *       traveled ("planned ahead"), with no search.</li>
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
    private static final String LASTSECRET = "147-sim-autosecret-lastsecret";
    private static final String PREBLOOD = "1018-sim-autosecret-preblood";
    private static final String SMALL = "1018-autosecret-small-rooms";
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
    private static final Pattern GOES_NOW = Pattern.compile(
            "\\[AutoSecret\\] (.+?): .*of the last-node wait \\(tick (\\d+)\\) - the next warp goes now");
    private static final Pattern LEAVES_AT_END = Pattern.compile(
            "\\[AutoSecret\\] (.+?): every secret counted at the route's end.* - leaving this tick");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank()) {
            return;
        }
        boolean handoff = !Scenario.skip(HANDOFF);
        boolean order = !Scenario.skip(ORDER);
        boolean arrival = !Scenario.skip(ARRIVAL);
        boolean last = !Scenario.skip(LASTSECRET);
        boolean preblood = !Scenario.skip(PREBLOOD);
        boolean small = !Scenario.skip(SMALL);
        if (!handoff && !order && !arrival && !last && !preblood && !small) {
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
            if (last) {
                Scenario.skipped(LASTSECRET, "needs real room captures and room database");
            }
            if (preblood) {
                Scenario.skipped(PREBLOOD, "needs real room captures and room database");
            }
            if (small) {
                Scenario.skipped(SMALL, "needs real room captures and room database");
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
                runCase(ctx, ORDER, (c, f) -> orderBody(c, f, false));
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
        if (last) {
            for (int f = 1; f <= LAST_FLOORS; f++) {
                lastFloor = f;
                try {
                    runCase(ctx, LASTSECRET, SimAutoSecretOptimizeTests::lastSecretBody);
                } catch (AssertionError e) {
                    failed.add(e.getMessage());
                }
            }
        }
        if (small) {
            try {
                runCase(ctx, SMALL, (c, f) -> orderBody(c, f, true));
            } catch (AssertionError e) {
                failed.add(e.getMessage());
            }
        }
        if (preblood) {
            // Under each of the two Room Order settings: the room before blood is last either way.
            for (boolean largest : new boolean[]{false, true}) {
                try {
                    runCase(ctx, PREBLOOD, (c, f) -> preBloodBody(c, f, largest));
                } catch (AssertionError e) {
                    failed.add(e.getMessage());
                }
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
                // lastsecret turns Secret Aura and Auto Close Chest on (both default OFF); left on, the aura clicked the
                // chests of every scenario after it in the client - 96-ar-1015-reaim's control met its await before
                // the test's own click and read met=false, acted=-1 (2026-10-16).
                ModUnderTest.turnOff(CHEAT_CFG, "setSecretAuraEnabled");
                ModUnderTest.turnOff("com.killer560.hub.autoclosechest.AutoCloseChestConfig", "setEnabled");
                setOrder(false);   // the mod's default, Least Etherwarps
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
            // killer560, 2026-10-20: wait at most 20 ticks and only when a secret is close; with none close leave at
            // once. These routes stand by no secret, so modes 2 and 3 leave the next tick like mode 1 (the 6-tick cap
            // they once measured is gone; 147 measures the wait for a secret that IS close).
            String what = switch (mode) {
                case 1 -> "every secret counted at the end: the first warp the next tick";
                case 2 -> "none counted, none close: the first warp the next tick";
                default -> "counted only after the route ended, none close: the first warp the next tick all the same";
            };
            println(HANDOFF, String.format(Locale.US, "%s (%s): route finished on tick %d, first warp on tick %d (%+d), path"
                    + " planned ahead and run with no search: %s", room, what, end, first, after, planned));
            if (!planned) {
                failures.add(room + ": the trip after its route was searched again, not the one planned ahead");
            }
            if (after < 0 || after > 1) {
                failures.add(room + ": " + what + ", but the first warp went " + after + " tick(s) after the route"
                        + " (want at most 1)");
            }
            if (mode == 3) {
                Long m = markedAt.get(room);
                println(HANDOFF, room + ": secrets counted on tick " + m + ", first warp on tick " + first);
            }
        }
        boolean allSaid = log.stream().anyMatch(l -> l.contains("every secret counted at the route's end"));
        boolean noneCloseSaid = log.stream().anyMatch(l -> l.contains("and none close - leaving this tick"));
        boolean waited = log.stream().anyMatch(l -> l.contains("of the last-node wait"));
        if (!allSaid || !noneCloseSaid) {
            failures.add("the hand-over's lines are missing (all counted " + allSaid + ", none close " + noneCloseSaid
                    + ")");
        }
        if (waited) {
            failures.add("a last-node wait ran with no secret close by");
        }
    }

    // ================================================================================================ 147

    /** Floors (each a fresh random F7) 147 runs. */
    private static final int LAST_FLOORS = 3;
    /** Rooms 147 routes per floor. */
    private static final int LAST_ROOMS = 4;
    private static int lastFloor;
    private static final String CHEAT_CFG = "com.killer560.hub.cheatutils.CheatUtilsConfig";

    /** One 147 room: its last secret, of what kind, the block its one-node route stands on, its total secrets. */
    private record LastCase(String room, int id, String kind, BlockPos secret, BlockPos stand, int total) {
    }

    /**
     * 147-sim-autosecret-lastsecret (mod autoclear-leave-1015). killer560, 2026-10-15: "it is still having an issue for
     * auto clear where it will leave a room before a secret is grabbed and registered, at least on sim." His Gold and
     * Basement (22:42:48 / 22:42:56): a one-node DUNGEON_BREAKER route with no blocks standing beside the room's one
     * wither essence, left for Secret Aura; "0/1 secret(s) counted at the route's end", "the 6-tick cap", the next warp,
     * the essence never taken. Scaffolding the same with the chest by its last landing.
     * <p>
     * Each floor: up to four rooms, each with ONE secret left (the rest marked found on the sim's server and the map, as
     * 144 does), of a kind Secret Aura or a pickup takes - a wither essence, a chest, an item - and a one-node
     * DUNGEON_BREAKER route (no blocks) on a block that secret is in reach of. Secret Aura on, his settings (range 4.5,
     * skull 4.5, cooldown 150 ms). Asserted per room, against the sim SERVER's own count for the room
     * ({@code SimScore.foundInRoom}): the room's last secret registered, and no later than the tick the first warp out of
     * the room was sent; and the warp went at most 2 ticks after it registered (no fixed wait). Three floors.
     */
    @SuppressWarnings("unchecked")
    private static void lastSecretBody(ClientGameTestContext ctx, List<String> failures) {
        String name = LASTSECRET + " floor " + lastFloor;
        enterFloor(ctx);
        awaitWarmGraph(ctx, LASTSECRET);
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(CHEAT_CFG);
            ModUnderTest.set(cfg, "setSecretAuraEnabled", true);
            ModUnderTest.set(cfg, "setAuraChests", true);
            ModUnderTest.set(cfg, "setAuraLevers", true);
            ModUnderTest.set(cfg, "setAuraEssence", true);
            ModUnderTest.set(cfg, "setAuraPauseWhileSneaking", false);
            ModUnderTest.set(cfg, "setAuraRange", 4.5);
            ModUnderTest.set(cfg, "setAuraSkullRange", 4.5);
            ModUnderTest.set(cfg, "setAuraCooldownMs", 150);
            ModUnderTest.call(cfg, "setAuraPauseHolding", new Class<?>[]{String.class}, new Object[]{""});
            // His setting too: the chest's window never opens, its lid does (and no warp goes under a window).
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autoclosechest.AutoCloseChestConfig"), "setEnabled",
                    true);
        });
        List<SimAutoSecretTests.Room> eligible = eligible(ctx.computeOnClient(SimAutoSecretTests::readRooms));
        Map<Integer, SimAutoSecretTests.Room> byId = new HashMap<>();
        for (SimAutoSecretTests.Room r : eligible) {
            byId.put(r.id(), r);
        }
        // Every placed secret of an eligible room, by kind, from the sim's own lists.
        List<Object[]> secrets = ctx.computeOnClient(mc -> {
            List<Object[]> out = new ArrayList<>();
            try {
                Class<?> ss = Class.forName("com.killer560.hub.roomsim.SimSecrets");
                for (BlockPos p : (java.util.Set<BlockPos>) ss.getField("PLACED_WITHER").get(null)) {
                    out.add(new Object[]{"essence", p});
                }
                for (BlockPos p : (List<BlockPos>) ss.getField("PLACED_CHESTS").get(null)) {
                    out.add(new Object[]{"chest", p});
                }
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            for (BlockPos p : (List<BlockPos>) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimSecretItems",
                    "pendingPositions")) {
                out.add(new Object[]{"item", p});
            }
            return out;
        });
        Vec3 here = ctx.computeOnClient(mc -> mc.player.position());
        List<LastCase> cases = new ArrayList<>();
        java.util.Set<String> kindsTaken = new java.util.HashSet<>();
        // Two passes: one room per kind first, so a floor tries every kind it has, then any.
        for (int pass = 0; pass < 2 && cases.size() < LAST_ROOMS; pass++) {
            java.util.Collections.shuffle(secrets, new java.util.Random(lastFloor * 31L + pass));
            for (Object[] s : secrets) {
                if (cases.size() >= LAST_ROOMS) {
                    break;
                }
                String kind = (String) s[0];
                BlockPos pos = (BlockPos) s[1];
                if (pass == 0 && kindsTaken.contains(kind)) {
                    continue;
                }
                int id = ctx.computeOnClient(mc -> (Integer) ModUnderTest.call(ModUnderTest.staticCall(LAYOUT, "capture"),
                        "roomAtWorld", new Class<?>[]{double.class, double.class},
                        new Object[]{pos.getX() + 0.5, pos.getZ() + 0.5}));
                SimAutoSecretTests.Room r = byId.get(id);
                if (r == null || cases.stream().anyMatch(c -> c.id() == id)) {
                    continue;
                }
                BlockPos stand = standFor(ctx, kind, pos, id);
                // ...and a way OUT of it: a stand in a nook no warp leaves strands the run there (2026-10-25 batch, Red
                // Green: every later trip "no etherwarp path"), which judges the planner, not the last-node wait.
                if (stand == null || !landsOn(ctx, here, stand)
                        || warps(ctx, feet(stand), BlockPos.containing(here.x, here.y - 0.5, here.z)) >= 999) {
                    continue;
                }
                int total = ctx.computeOnClient(mc -> {
                    Object entry = ModUnderTest.staticCall(DB, "lookupByName", new Class<?>[]{String.class},
                            new Object[]{r.name()});
                    try {
                        return entry.getClass().getField("secrets").getInt(entry);
                    } catch (ReflectiveOperationException e) {
                        throw new RuntimeException(e);
                    }
                });
                cases.add(new LastCase(r.name(), id, kind, pos, stand, total));
                kindsTaken.add(kind);
            }
        }
        println(name, "cases: " + cases);
        if (cases.size() < 2) {
            failures.add("floor " + lastFloor + ": only " + cases.size() + " room(s) with a secret a route can stand by");
            return;
        }
        Map<String, JsonObject> routes = new LinkedHashMap<>();
        for (LastCase c : cases) {
            routes.put(c.room(), breakerRoute(ctx, c));
            leaveOneUnfound(ctx, c);
        }
        SimAutoSecretTests.writeRoutes(ctx, routes);
        Map<String, Integer> found0 = new HashMap<>();
        for (LastCase c : cases) {
            found0.put(c.room(), serverFound(c.room()));
        }
        println(name, "server count before the run: " + found0);
        long mark = LogTap.mark();
        Boolean started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "start"));
        if (!Boolean.TRUE.equals(started)) {
            failures.add("Auto Secret refused to start");
            return;
        }
        Map<String, Long> finishedAt = new HashMap<>();
        Map<String, Long> releasedAt = new HashMap<>();
        Map<String, Long> registeredAt = new HashMap<>();
        for (int t = 0; t < 20 * 150; t++) {
            ctx.waitTicks(1);
            long now = tick(ctx);
            for (LastCase c : cases) {
                if (!registeredAt.containsKey(c.room()) && serverFound(c.room()) >= c.total()) {
                    registeredAt.put(c.room(), now);
                }
            }
            for (String l : LogTap.since(mark)) {
                Matcher f = FINISHED.matcher(l);
                if (f.find() && !finishedAt.containsKey(f.group(1))) {
                    finishedAt.put(f.group(1), Long.parseLong(f.group(2)));
                }
                Matcher g = GOES_NOW.matcher(l);
                if (g.find() && !releasedAt.containsKey(g.group(1))) {
                    releasedAt.put(g.group(1), Long.parseLong(g.group(2)));
                }
                Matcher e = LEAVES_AT_END.matcher(l);
                if (e.find() && !releasedAt.containsKey(e.group(1)) && finishedAt.containsKey(e.group(1))) {
                    releasedAt.put(e.group(1), finishedAt.get(e.group(1)));
                }
            }
            boolean allDone = cases.stream().allMatch(c -> finishedAt.containsKey(c.room()));
            if (allDone && t > 0 && finishedAt.values().stream().mapToLong(Long::longValue).max().orElse(0) + 120 < now) {
                break;
            }
            if (!(Boolean) ctx.computeOnClient(mc -> ModUnderTest.staticCall(AS, "isRunning"))) {
                ctx.waitTicks(40);
                break;
            }
        }
        List<Long> hops = ctx.computeOnClient(mc -> (List<Long>) ModUnderTest.staticCall(EXECUTOR, "hopSendTicks"));
        List<String> log = LogTap.since(mark);
        for (String l : log) {
            if (l.contains("[AutoSecret]") && (l.contains("route's end") || l.contains("last-node wait")
                    || l.contains("to register") || l.contains("finished") || l.contains("target ")
                    || l.contains("stopped"))
                    || l.contains("Wither essence collected") || l.contains("item picked up")) {
                println(name, "  " + l.replaceAll("^.*?(\\[AutoSecret\\]|\\[AutoRoutes\\]|\\[System\\])", "$1"));
            }
        }
        println(name, "finished " + finishedAt + ", registered " + registeredAt + ", hops " + hops);
        int checked = 0;
        for (LastCase c : cases) {
            Long end = finishedAt.get(c.room());
            if (end == null) {
                println(name, c.room() + " (" + c.kind() + "): its route never ran to its end - not judged");
                continue;
            }
            checked++;
            long out = -1;
            for (long h : hops) {
                if (h > end) {
                    out = h;
                    break;
                }
            }
            Long reg = registeredAt.get(c.room());
            Long rel = releasedAt.get(c.room());
            println(name, String.format(Locale.US, "%s (%s at %s, stand %s): route end %d, last secret registered %s,"
                            + " warp let go %s, first warp out %s%s", c.room(), c.kind(), c.secret().toShortString(),
                    c.stand().toShortString(), end, reg == null ? "NEVER" : reg + " (" + (reg - end) + " after the end)",
                    rel == null ? "?" : rel + (reg != null ? " (" + (rel - reg) + " after it registered)" : ""),
                    out < 0 ? "none" : out + " (" + (out - end) + " after the end)",
                    reg != null && out >= 0 ? ", " + (out - reg) + " tick(s) after it registered" : ""));
            if (reg == null) {
                failures.add(c.room() + " (" + c.kind() + "): its last secret never registered"
                        + (out >= 0 ? " - the warp out went " + (out - end) + " tick(s) after the route" : ""));
            } else if (out >= 0 && out < reg) {
                failures.add(c.room() + " (" + c.kind() + "): left on tick " + out + ", before its last secret registered"
                        + " on " + reg);
            } else if (rel != null && rel < reg) {
                // Let go before it registered; that the trip then searched long enough for it to register first is luck.
                failures.add(c.room() + " (" + c.kind() + "): let the warp out go on tick " + rel + ", before its last"
                        + " secret registered on " + reg);
            } else if (rel != null && rel - reg > 1) {
                // The client sees a server registration a tick later at the earliest; the hold must go on that tick. (The
                // trip itself may still take a search when it was not planned ahead - not this wait's doing.)
                failures.add(c.room() + " (" + c.kind() + "): registered on " + reg + " but the held warp was let go only"
                        + " on " + rel);
            }
        }
        if (checked < 2) {
            failures.add("only " + checked + " route(s) ran to their end");
        }
    }

    /**
     * A standable, warpable block in room {@code id}, with {@code secret} in reach from it: a chest within 4.0 of both
     * the standing and the sneaking eye (box), an essence within 4.2 (center), an item within 1.5 of the body. Closest
     * first. Client thread via computeOnClient.
     */
    private static BlockPos standFor(ClientGameTestContext ctx, String kind, BlockPos secret, int id) {
        return ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            BlockPos best = null;
            double bestD = Double.MAX_VALUE;
            for (BlockPos b : BlockPos.betweenClosed(secret.offset(-5, -5, -5), secret.offset(5, 3, 5))) {
                // Never under the secret's own block: a sim chest is not there until he has been near it a moment (mod
                // sim-secrets-1018), so its spot reads as head room now, and once it spawns the stand has none - the start
                // node is inside the chest, no etherwarp lands on it, and Auto Secret (rightly) skips the room as "did not
                // arm" (2026-10-25 batch: Mossy, Doors, Hallway under their chests). A skull is a block too.
                if (!kind.equals("item") && (b.above().equals(secret) || b.above(2).equals(secret))) {
                    continue;
                }
                if (b.equals(secret) || (Integer) ModUnderTest.call(layout, "roomAtWorld",
                        new Class<?>[]{double.class, double.class}, new Object[]{b.getX() + 0.5, b.getZ() + 0.5}) != id) {
                    continue;
                }
                if (!(Boolean) ModUnderTest.staticCall("com.killer560.hub.livemap.autoclear.TeleportUtils", "landable",
                        new Class<?>[]{BlockPos.class}, new Object[]{b})
                        || !(Boolean) ModUnderTest.staticCall("com.killer560.hub.livemap.autoclear.TeleportUtils",
                        "underCover", new Class<?>[]{BlockPos.class}, new Object[]{b})) {
                    continue;
                }
                Vec3 feet = new Vec3(b.getX() + 0.5, b.getY() + 1.0, b.getZ() + 0.5);
                double d;
                if (kind.equals("item")) {
                    double dx = Math.max(0, Math.max(secret.getX() - (feet.x + 0.3), (feet.x - 0.3) - (secret.getX() + 1)));
                    double dy = Math.max(0, Math.max(secret.getY() - (feet.y + 1.8), feet.y - (secret.getY() + 1)));
                    double dz = Math.max(0, Math.max(secret.getZ() - (feet.z + 0.3), (feet.z - 0.3) - (secret.getZ() + 1)));
                    d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (d > 1.5) {
                        continue;
                    }
                } else {
                    double worst = 0;
                    for (double eyeH : new double[]{1.62, 1.27}) {
                        Vec3 eye = feet.add(0, eyeH, 0);
                        double box = Math.sqrt(boxDistSq(eye, secret));
                        double centre = eye.distanceTo(Vec3.atCenterOf(secret));
                        worst = Math.max(worst, kind.equals("chest") ? box : Math.max(box, centre));
                    }
                    if (worst > (kind.equals("chest") ? 4.0 : 4.2)) {
                        continue;
                    }
                    d = worst;
                }
                if (d < bestD) {
                    bestD = d;
                    best = b.immutable();
                }
            }
            return best;
        });
    }

    private static double boxDistSq(Vec3 p, BlockPos b) {
        double dx = Math.max(0, Math.max(b.getX() - p.x, p.x - (b.getX() + 1)));
        double dy = Math.max(0, Math.max(b.getY() - p.y, p.y - (b.getY() + 1)));
        double dz = Math.max(0, Math.max(b.getZ() - p.z, p.z - (b.getZ() + 1)));
        return dx * dx + dy * dy + dz * dz;
    }

    /** His Gold: one DUNGEON_BREAKER start node with no blocks, standing on {@code c.stand()}. */
    private static JsonObject breakerRoute(ClientGameTestContext ctx, LastCase c) {
        return ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            int[] cr = (int[]) ModUnderTest.call(layout, "clayRotation", new Class<?>[]{int.class}, new Object[]{c.id()});
            Object frame;
            try {
                frame = Class.forName("com.killer560.hub.autoroutes.RouteCoords$Frame")
                        .getConstructor(String.class, int.class, int.class, int.class)
                        .newInstance(c.room(), cr[0], cr[1], cr[2]);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            Vec3 rel = (Vec3) ModUnderTest.staticCall("com.killer560.hub.autoroutes.RouteCoords", "toRelative",
                    new Class<?>[]{frame.getClass(), Vec3.class}, new Object[]{frame,
                            new Vec3(c.stand().getX() + 0.5, c.stand().getY() + 1, c.stand().getZ() + 0.5)});
            JsonObject node = new JsonObject();
            node.addProperty("type", "DUNGEON_BREAKER");
            node.addProperty("x", rel.x);
            node.addProperty("y", rel.y);
            node.addProperty("z", rel.z);
            node.addProperty("yaw", 0f);
            node.addProperty("pitch", 0f);
            node.addProperty("start", true);
            node.add("blocks", new com.google.gson.JsonArray());
            com.google.gson.JsonArray nodes = new com.google.gson.JsonArray();
            nodes.add(node);
            JsonObject route = new JsonObject();
            route.add("nodes", nodes);
            return route;
        });
    }

    /** All but one of the room's secrets found, on the sim's server and on the map (as {@link #markAllFound}). */
    @SuppressWarnings("unchecked")
    private static void leaveOneUnfound(ClientGameTestContext ctx, LastCase c) {
        int n = c.total() - 1;
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            mc.getSingleplayerServer().execute(() -> {
                for (int i = 0; i < n; i++) {
                    ModUnderTest.staticCall(SIM_SCORE, "secretFound", new Class<?>[]{BlockPos.class},
                            new Object[]{c.secret()});
                }
                done.set(true);
            });
            try {
                java.lang.reflect.Field f = Class.forName(LIVE_MAP).getDeclaredField("foundSecretsByRoom");
                f.setAccessible(true);
                ((Map<String, Integer>) f.get(null)).put(c.room(), n);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        ctx.waitFor(mc -> done.get() != null, 200);
    }

    /** The sim server's own count of found secrets in the room (static, thread-safe). */
    private static int serverFound(String room) {
        return (Integer) ModUnderTest.staticCall(SIM_SCORE, "foundInRoom", new Class<?>[]{String.class},
                new Object[]{room});
    }

    // ================================================================================================ 146

    /** At most this many ticks from the last warp of the trip onto a start node to its one-node route ending there. */
    private static final int ARRIVAL_MAX_TICKS = 2;
    /**
     * Ticks from the last warp to the tick its arrival is counted, with the server answering at once (measured
     * 2026-10-26: 2 on every trip of an unloaded run, with the route ending on that same tick).
     */
    private static final int ARRIVAL_ANSWER_TICKS = 2;

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
        // The ticks (ClearExecutor's clock, read after each client tick) on which a trip's arrival was counted - the
        // server's answer to its last landing. Under load (9 clients on one machine) the sim's server thread can answer a
        // tick or more late, which is not the mod waiting (2026-10-26 batch: Waterfall 3 ticks from warp to route, its
        // landing answered a tick late); what the mod owns is the time from that answer to the route.
        List<Long> arrivals = new ArrayList<>();
        int seq = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EXECUTOR, "arrivalSeq"));
        for (int t = 0; t < 20 * 150 && finishedAt.size() < routes.size(); t++) {
            ctx.waitTicks(1);
            long[] now = ctx.computeOnClient(mc -> new long[]{(Integer) ModUnderTest.staticCall(EXECUTOR, "arrivalSeq"),
                    clientTick()});
            if (now[0] != seq) {
                seq = (int) now[0];
                arrivals.add(now[1]);
            }
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
        println(ARRIVAL, "finished at " + finishedAt + ", arrivals seen on ticks " + arrivals + ", hop ticks " + hops);
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
                if (h > from && h < end) {   // a warp ON the route's end tick is already the next trip's (hand-over)
                    last = h;
                }
            }
            long arrived = -1;
            for (long a : arrivals) {
                if (a > last && a <= end) {
                    arrived = a;
                }
            }
            if (last < 0) {
                println(ARRIVAL, e.getKey() + ": no warp of a trip into it (it was where he stood) - not counted");
            } else {
                gaps.add(end - last);
                // The server's own delay past the normal answer (counted two ticks after the warp) is not the mod's;
                // with no arrival seen in the window it is taken as none.
                long serverLate = arrived < 0 ? 0 : Math.max(0, arrived - last - ARRIVAL_ANSWER_TICKS);
                println(ARRIVAL, String.format(Locale.US, "%s: last warp of the trip on tick %d, its arrival counted on"
                        + " tick %d, route finished on tick %d: %d tick(s) from the warp to the route (the server's answer"
                        + " %d tick(s) late)", e.getKey(), last, arrived, end, end - last, serverLate));
                if (end - last - serverLate > ARRIVAL_MAX_TICKS) {
                    failures.add(e.getKey() + ": " + (end - last) + " ticks from the trip's last warp to its start node's"
                            + " route, " + serverLate + " of them the server answering late - the arrival settle is still"
                            + " there (want at most " + ARRIVAL_MAX_TICKS + " plus the server's lateness)");
                }
            }
            from = end;
        }
        if (gaps.size() < 2) {
            failures.add("fewer than two trips into a routed room were measured (" + gaps + ")");
        }
        println(ARRIVAL, "warp-to-route ticks per room: " + gaps);
    }

    /** Auto Secret's Room Order setting: Largest To Smallest ({@code true}) or Least Etherwarps. Call on the client thread. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setOrder(boolean largest) {
        try {
            Class<? extends Enum> e = (Class<? extends Enum>) Class.forName(AS_CONFIG + "$SecretOrder");
            Object v = Enum.valueOf(e, largest ? "LARGEST_TO_SMALLEST" : "LEAST_ETHERWARPS");
            ModUnderTest.call(ModUnderTest.config(AS_CONFIG), "setSecretOrder", new Class<?>[]{e}, new Object[]{v});
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("this jar has no AutoSecretConfig.SecretOrder LARGEST_TO_SMALLEST / LEAST_ETHERWARPS: " + ex);
        }
    }

    // ================================================================================================ 145

    /**
     * @param small {@code false}: 145, the far room holds MORE than 3 unfound, so Largest To Smallest crosses the map first
     *              and Least Etherwarps does not. {@code true}: 1018-autosecret-small-rooms, every room holds 3 or fewer,
     *              so with Largest To Smallest SELECTED the mod must still take Least Etherwarps' order (killer560,
     *              2026-10-18: "once only rooms with 3 or less secrets remain it will use the least total etherwarp mode
     *              no matter what is selected").
     */
    private static void orderBody(ClientGameTestContext ctx, List<String> failures, boolean small) {
        final String name = small ? SMALL : ORDER;
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
            println(name, "floor " + (attempt + 1) + ": eligible (unfound@rooms away) " + shown);
            // His example, as near as a floor has it: two small rooms beside him (1 or 2 unfound), a room across the floor
            // with more than either but at most 3 - most unfound first crosses the map for it and comes back.
            awaitWarmGraph(ctx, name);
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
                        if (f.unfound() <= Math.max(a.unfound(), b.unfound()) || (small ? f.unfound() > 3 : f.unfound() < 4)
                                || fromHere.get(f.name()) < 3
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
                        println(name, String.format(Locale.US, "candidates %s=%d, %s=%d, %s=%d: warps here->%s %d, %s<->%s"
                                        + " %d/%d, here->%s %d, %s->%s %d, %s->%s %d", a.name(), a.unfound(), b.name(),
                                b.unfound(), f.name(), f.unfound(), a.name(), hA, a.name(), b.name(), aB, bA, f.name(), hF,
                                a.name(), f.name(), aF, b.name(), f.name(), bF));
                        if (hA <= 4 && aB <= 4 && bA <= 4 && hF >= hA + 3 && aF >= aB + 3 && bF >= aB + 3) {
                            n1 = a;
                            n2 = b;
                            far = f;
                            println(name, "beside him: " + a.name() + " and " + b.name() + "; across the floor: "
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
            Scenario.skipped(name, "no floor in 12 had two small rooms beside the Entrance and a bigger ("
                    + (small ? "at most 3" : "4 or more") + ") one across it, by the planner's own warps - run it again");
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
        if (small) {
            // Largest To Smallest was selected for "old": with every room at 3 or fewer it must have taken the same
            // order as Least Etherwarps, the far room last. (The control is 145: with the far room above 3 the same
            // setting crosses the map first.)
            println(name, "Least Etherwarps took " + fresh.order + " in " + fresh.ticks + " ticks; Largest To Smallest"
                    + " selected took " + old.order + " in " + old.ticks + " ticks");
            if (fresh.order.size() < 3 || old.order.size() < 3) {
                failures.add("a run did not take all three rooms (Least Etherwarps " + fresh.order + ", Largest To Smallest "
                        + old.order + ")");
                return;
            }
            if (!old.order.get(2).equals(far.name()) || old.order.get(0).equals(far.name())) {
                failures.add("with Largest To Smallest selected and only rooms of 3 or fewer unfound, the mod crossed the"
                        + " map for " + far.name() + " first: " + old.order + " - the least-etherwarps order is not used");
            }
            if (!old.order.equals(fresh.order)) {
                failures.add("Largest To Smallest selected took " + old.order + ", Least Etherwarps " + fresh.order
                        + " - not the same order");
            }
            return;
        }
        println(name, "new order " + fresh.order + ": all three in " + fresh.ticks + " ticks, secret-weighted wait "
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
        // Not slower, rather than faster (2026-10-18): once the rooms left hold 3 or fewer the old order's own setting
        // switches to least etherwarps too, so after its first (far) room the two runs can tie on real ticks (35 and 35).
        // The order itself is what is judged above; the distance walked is printed.
        if (fresh.ticks > old.ticks) {
            failures.add("the new order took " + fresh.ticks + " ticks, the old " + old.ticks + " - slower");
        }
        // The secret-weighted wait in real ticks is printed, not judged: every trip pays the same ~20 ticks of search,
        // sneak and arrival settle in the sim whatever its length, so by real ticks the big room first can wait a few
        // secret-ticks less while taking longer in all (2026-10-15: 193 against 189, 67 ticks against 75). The planner
        // weighs secrets per tick of TRAVEL - killer560's own measure - and total ticks are judged above.
    }

    /** @param wait each room's unfound secrets times the ticks until its route finished, summed */
    private record Run(List<String> order, long ticks, double travelled, long secretWait) {
    }

    /** @param legacy {@code true}: Largest To Smallest is selected ("old"); {@code false}: Least Etherwarps ("new") */
    private static Run runOrder(ClientGameTestContext ctx, boolean legacy, Vec3 start, java.util.Set<String> routed,
                                Map<String, Integer> unfound) {
        String label = legacy ? "old" : "new";
        teleport(ctx, start);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(MEMORY, "reset"));
        ctx.runOnClient(mc -> setOrder(legacy));
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

    // ================================================================================================ 1018

    /**
     * 1018-sim-autosecret-preblood, killer560 (2026-10-18): "never have it secret the room before blood until very last
     * under any circumstance for auto secret." On a generated F7, the room on the near side of the blood door (read off the
     * map here, not asked of the mod) and the two other routed rooms with the fewest unfound secrets - so the room before
     * blood holds the most, and both the value-weighted order and most-unfound-first would take it early. Auto Secret runs
     * from the Entrance: all three must be targeted and the room before blood must be the last. On main it is not.
     */
    private static void preBloodBody(ClientGameTestContext ctx, List<String> failures, boolean largest) {
        ctx.runOnClient(mc -> setOrder(largest));
        println(PREBLOOD, "Room Order: " + (largest ? "Largest To Smallest" : "Least Etherwarps"));
        SimAutoSecretTests.Room pre = null;
        List<SimAutoSecretTests.Room> others = new ArrayList<>();
        for (int attempt = 0; attempt < 12 && pre == null; attempt++) {
            if (attempt > 0) {
                leaveSim(ctx);
            }
            enterFloor(ctx);
            List<SimAutoSecretTests.Room> eligible = eligible(ctx.computeOnClient(SimAutoSecretTests::readRooms));
            String preName = ctx.computeOnClient(SimAutoSecretTests::preBloodName);
            SimAutoSecretTests.Room p = null;
            for (SimAutoSecretTests.Room r : eligible) {
                if (r.name().equals(preName)) {
                    p = r;
                }
            }
            List<SimAutoSecretTests.Room> rest = new ArrayList<>(eligible);
            rest.remove(p);
            rest.sort((a, b) -> Integer.compare(a.unfound(), b.unfound()));
            println(PREBLOOD, "floor " + (attempt + 1) + ": room before blood " + preName + " ("
                    + (p == null ? "not eligible" : p.unfound() + " unfound") + "); eligible " + SimAutoSecretTests.describe(eligible));
            if (p == null || rest.size() < 2 || rest.get(1).unfound() >= p.unfound()) {
                continue;
            }
            pre = p;
            others = List.of(rest.get(0), rest.get(1));
        }
        if (pre == null) {
            Scenario.skipped(PREBLOOD, "no floor in 12 had an eligible room before blood holding more unfound secrets than"
                    + " two other rooms - run it again");
            caseSkipped = true;
            return;
        }
        awaitWarmGraph(ctx, PREBLOOD);
        Map<String, JsonObject> routes = new LinkedHashMap<>();
        for (SimAutoSecretTests.Room r : List.of(pre, others.get(0), others.get(1))) {
            JsonObject route = SimAutoSecretTests.routeFor(ctx, r);
            if (route == null) {
                failures.add("no standable block for a route in " + r.name());
                return;
            }
            routes.put(r.name(), route);
        }
        SimAutoSecretTests.writeRoutes(ctx, routes);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(MEMORY, "reset"));
        long mark = LogTap.mark();
        Boolean started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "start"));
        if (!Boolean.TRUE.equals(started)) {
            failures.add("Auto Secret refused to start");
            return;
        }
        List<String> order = new ArrayList<>();
        int finished = 0;
        for (int t = 0; t < 20 * 150 && finished < 3; t++) {
            ctx.waitTicks(1);
            order.clear();
            finished = 0;
            for (String l : LogTap.since(mark)) {
                Matcher m = TARGET.matcher(l);
                if (m.find() && routes.containsKey(m.group(1)) && !order.contains(m.group(1))) {
                    order.add(m.group(1));
                }
                Matcher f = FINISHED.matcher(l);
                if (f.find() && routes.containsKey(f.group(1))) {
                    finished++;
                }
            }
            if (!(Boolean) ctx.computeOnClient(mc -> ModUnderTest.staticCall(AS, "isRunning"))) {
                break;
            }
        }
        boolean held = false;
        for (String l : LogTap.since(mark)) {
            if (l.contains("[AutoSecret] room order") || l.contains("[AutoSecret] target") || l.contains("finished (tick")
                    || l.contains("in front of the blood door")) {
                println(PREBLOOD, "  " + l.replaceAll("^.*?\\[AutoSecret\\]", "[AutoSecret]"));
            }
            held |= l.contains("in front of the blood door - held");
        }
        println(PREBLOOD, "room before blood " + pre.name() + " (" + pre.unfound() + " unfound), others " + others.get(0).name()
                + " (" + others.get(0).unfound() + "), " + others.get(1).name() + " (" + others.get(1).unfound()
                + "); targets in order " + order + ", routes finished " + finished + ", hold said " + held);
        if (order.size() < 3) {
            failures.add("only " + order.size() + " of the three routed rooms were targeted: " + order);
            return;
        }
        if (!order.get(2).equals(pre.name())) {
            failures.add("the room before blood (" + pre.name() + ") was not the last secreted: " + order);
        }
        if (!held) {
            failures.add("Auto Secret never said it held the room before blood");
        }
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

    /**
     * Whether some chain of etherwarps from {@code from} ends ON {@code to} itself - the mod's own test for a path node
     * ({@code EtherwarpPathfinder.findOptimalPath}, null when "the block itself cannot be reached by etherwarp"). A start
     * node's warp is exact ({@code AutoRoutesFeature.warpToStartNode}); the plain search above also counts a plan that only
     * lands NEAR the block, and a route stood on such a block never arms (147, 2026-10-25: Double Diamond, Gravel).
     */
    private static boolean landsOn(ClientGameTestContext ctx, Vec3 from, BlockPos to) {
        AtomicReference<Boolean> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            Object cfg = ModUnderTest.staticCall(EXECUTOR, "pathConfig");
            double range = (Double) ModUnderTest.staticCall(EXECUTOR, "hopRange");
            Runnable job = () -> {
                try {
                    Object path = ModUnderTest.staticCall("com.killer560.hub.livemap.autoclear.EtherwarpPathfinder",
                            "findOptimalPath", new Class<?>[]{Vec3.class, BlockPos.class, cfg.getClass(), double.class,
                                    layout.getClass(), long.class}, new Object[]{from, to, cfg, range, layout, 200_000_000L});
                    out.set(path != null);
                } catch (RuntimeException e) {
                    out.set(false);
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

package dev.testkit.gametest.perf;

import dev.testkit.gametest.Scenario;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.perf.PerfBench.Key;
import dev.testkit.gametest.perf.PerfBench.Phase;
import dev.testkit.gametest.perf.PerfBench.Switch;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 95-perf-livemap: the Dungeon Map HUD's draw cache (mod {@code livemap/MapDrawCache}, 2026-10-15), ~3 min, only when
 * named. In {@link PerfBench}'s scene (the seeded sim F7, camera turning), first with only the Dungeon Map (and its Extra
 * Info) on, then with the bench's ALL ON set (Predictive Rooms and the rest) plus the map:
 * <ol>
 *   <li><b>cost</b>: the map draws every frame (asserted), and its render-thread allocation per frame stays under
 *       {@link #ALLOC_BUDGET} plus {@link #ALLOC_TICK_BUDGET} a tick (main painted everything afresh every frame: 61.7-65.6 KB a frame with only the
 *       map on, 84.5 KB under ALL ON; the cache 2.6 KB and 14.8 KB at 1.5 frames a tick, 2026-10-16),
 *       with the cache actually replaying (hits counted by the mod);</li>
 *   <li><b>same picture</b>: with the mod's verify mode on, every replayed frame is also painted afresh, unsubmitted, and
 *       compared element by element (rectangles, text, pose, colours, scissor). While that runs, a change is made every
 *       {@link #STEP} ticks to something the map is painted from - settings, a sim room's state, a door, Predictive
 *       Rooms, custom checkmarks, the background - and each must make the cache paint again (asserted, so the test cannot
 *       pass by never changing anything). A replay that differs from the fresh paint after the tick it was painted in
 *       is a missed input and fails the run; one within its own tick is state that changed between two frames of a
 *       tick and is shown at the next one (counted, reported).</li>
 * </ol>
 * Fails on mod main 69505e45: there is no cache to ask, and the map allocates ten times the budget.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class PerfLiveMapTest implements FabricClientGameTest {

    private static final String NAME = "95-perf-livemap";
    private static final String CACHE = "livemap.MapDrawCache";
    /** Bytes per frame the map may allocate on the render thread while nothing changes. */
    private static final double ALLOC_BUDGET = 2500;
    /** And per client tick: the players' part, Extra Info's lines and the per-tick inputs are worked out once a tick,
     *  which a scene drawing ~1.5 frames a tick (ALL ON) spreads over few frames. */
    private static final double ALLOC_TICK_BUDGET = 25_000;
    private static final int STEP = 30;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String filter = System.getProperty("testkit.scenario", "");
        if (filter.isBlank() || Scenario.skip(NAME)) {
            return;
        }
        List<String> report = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        if (!PerfBench.open(ctx, NAME, report)) {
            return;
        }
        Map<Switch, Boolean> defaults = new LinkedHashMap<>();
        Map<String, Object[]> restore = new LinkedHashMap<>();
        try {
            if (!PerfBench.probeOn(ctx)) {
                throw new AssertionError("[" + NAME + "] the mod's PerfProbe is off: run with -PperfProbe and a dev jar");
            }
            boolean cached = ctx.computeOnClient(mc -> Mod.has(CACHE));
            double[] cal = PerfBench.calibrate(ctx);
            List<Switch> all = ctx.computeOnClient(mc -> PerfBench.switches());
            defaults.putAll(ctx.computeOnClient(mc -> PerfBench.states(all)));
            Map<Switch, Boolean> on = PerfBench.allOff(all);
            for (Switch s : all) {
                if (s.label().equals("livemap.LiveMapConfig")) {
                    on.put(s, true);
                }
            }
            ctx.runOnClient(mc -> {
                PerfBench.apply(on);
                remember(restore, "livemap.LiveMapConfig", "getRoomLabels", "setRoomLabels");
                remember(restore, "livemap.LiveMapConfig", "isTextShadow", "setTextShadow");
                remember(restore, "livemap.LiveMapConfig", "getDarkenUnopened", "setDarkenUnopened");
                remember(restore, "livemap.LiveMapConfig", "isColourByType", "setColourByType");
                remember(restore, "livemap.LiveMapConfig", "isCustomCheckmarks", "setCustomCheckmarks");
                remember(restore, "livemap.LiveMapConfig", "getMapTheme", "setMapTheme");
                remember(restore, "mapping.MappingConfig", "isExtraInfoEnabled", "setExtraInfoEnabled");
                remember(restore, "livemap.LiveMapConfig", "getMapBackground", "setMapBackground");
                remember(restore, "livemap.predict.PredictiveRoomsConfig", "isEnabledRaw", "setEnabled");
                remember(restore, "livemap.predict.PredictiveRoomsConfig", "isSpecialGuessRaw", "setSpecialGuess");
                Mod.set("livemap.LiveMapConfig", "setRoomLabels", 4);   // names and secrets: the most text
                Mod.set("mapping.MappingConfig", "setExtraInfoEnabled", true);
            });
            ctx.waitTicks(40);

            // ---- 1. cost, verify off ----
            if (cached) {
                ctx.runOnClient(mc -> {
                    Mod.staticCall(CACHE, "setVerify", false);
                    Mod.staticCall(CACHE, "resetStats");
                });
            }
            costPhase(ctx, "Dungeon Map ON", cal, cached, report, failures);
            // ALL ON (the bench's set, the map's extras included): what else is on must not make the map repaint.
            Map<Switch, Boolean> allOn = PerfBench.allOn(defaults);
            for (Switch s : all) {
                if (s.label().equals("livemap.LiveMapConfig")) {
                    allOn.put(s, true);
                }
            }
            ctx.runOnClient(mc -> {
                PerfBench.apply(allOn);
                Mod.set("livemap.LiveMapConfig", "setRoomLabels", 4);
                Mod.set("mapping.MappingConfig", "setExtraInfoEnabled", true);
            });
            ctx.waitTicks(40);
            if (cached) {
                ctx.runOnClient(mc -> Mod.staticCall(CACHE, "resetStats"));
            }
            costPhase(ctx, "ALL ON", cal, cached, report, failures);

            if (!cached) {
                throw new AssertionError("[" + NAME + "] the mod has no " + CACHE + ": the Dungeon Map is painted afresh"
                        + " every frame" + (failures.isEmpty() ? "" : "; " + String.join("; ", failures)));
            }

            // ---- 2. same picture, verify on, with changes ----
            ctx.runOnClient(mc -> {
                Mod.staticCall(CACHE, "setVerify", true);
                Mod.staticCall(CACHE, "resetStats");
            });
            List<String[]> changes = new ArrayList<>();
            for (Map.Entry<String, Consumer<Object>> change : changes().entrySet()) {
                long builds0 = ctx.computeOnClient(mc -> ((long[]) Mod.staticCall(CACHE, "stats"))[1]);
                ctx.waitTicks(STEP);
                long builds1 = ctx.computeOnClient(mc -> ((long[]) Mod.staticCall(CACHE, "stats"))[1]);
                String[] what = ctx.computeOnClient(mc -> {
                    try {
                        change.getValue().accept(mc);
                        return new String[]{change.getKey(), ""};
                    } catch (Throwable t) {
                        return new String[]{change.getKey(), t.toString()};
                    }
                });
                ctx.waitTicks(STEP);
                long builds2 = ctx.computeOnClient(mc -> ((long[]) Mod.staticCall(CACHE, "stats"))[1]);
                // The kept layout snapshot (DungeonLayout.current) against one taken afresh, after every change.
                String layoutDiff = ctx.computeOnClient(mc -> Mod.has("livemap.DungeonLayout")
                        && hasMethod("livemap.DungeonLayout", "currentMismatch")
                        ? (String) Mod.staticCall("livemap.DungeonLayout", "currentMismatch") : "no currentMismatch hook");
                if (!layoutDiff.isEmpty()) {
                    failures.add("after '" + what[0] + "' the kept dungeon layout differs from a fresh one: " + layoutDiff);
                }
                if (!what[1].isEmpty()) {
                    failures.add("change '" + what[0] + "' could not be made: " + what[1]);
                    continue;
                }
                // Painting without a change happens once a tick (players); a change must add the other parts' paints.
                long idle = builds1 - builds0;
                long after = builds2 - builds1;
                changes.add(new String[]{what[0], String.valueOf(idle), String.valueOf(after)});
                if (after <= idle) {
                    failures.add("change '" + what[0] + "' made the cache paint nothing extra (" + after + " paints in "
                            + STEP + " ticks after it, " + idle + " before)");
                }
            }
            ctx.waitTicks(STEP);
            long[] v = ctx.computeOnClient(mc -> (long[]) Mod.staticCall(CACHE, "stats"));
            String stale = ctx.computeOnClient(mc -> (String) Mod.staticCall(CACHE, "lastMismatch"));
            String sameTick = ctx.computeOnClient(mc -> (String) Mod.staticCall(CACHE, "lastSameTickMismatch"));
            for (String[] c : changes) {
                report.add(String.format(Locale.ROOT, "change %-34s paints: %s in %d ticks before, %s after",
                        c[0], c[1], STEP, c[2]));
            }
            report.add(String.format(Locale.ROOT, "verify: %d replays compared with a fresh paint, %d differed after"
                            + " their tick (missed input), %d within their own tick%s", v[2], v[4], v[3],
                    sameTick.isEmpty() ? "" : " (last: " + sameTick + ")"));
            if (v[2] < 200) {
                failures.add("only " + v[2] + " replays were compared with a fresh paint - nothing was verified");
            }
            if (v[4] > 0) {
                failures.add(v[4] + " replay(s) differed from a fresh paint after the tick they were painted in: "
                        + stale);
            }
        } finally {
            try {
                ctx.runOnClient(mc -> {
                    if (Mod.has(CACHE)) {
                        Mod.staticCall(CACHE, "setVerify", false);
                    }
                    for (Map.Entry<String, Object[]> e : restore.entrySet()) {
                        Object[] r = e.getValue();
                        Mod.call(r[0], (String) r[1], r[2]);
                    }
                    Mod.staticCall("roomsim.SimRoomState", "clear");
                });
                if (!defaults.isEmpty()) {
                    ctx.runOnClient(mc -> PerfBench.apply(defaults));
                }
            } catch (Throwable ignored) {
                // restoring config must not mask the real failure
            }
            for (String line : report) {
                System.out.println("[" + NAME + "] " + line);
            }
            PerfBench.close(ctx);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("[" + NAME + "] " + failures.size() + " failure(s):\n  " + String.join("\n  ", failures));
        }
        System.out.println("[" + NAME + "] PASS");
    }

    /** 400 ticks with the probe: the map draws every frame, allocates under budget, and (with the cache) replays
     *  every frame while painting no more than the players' part once a tick. */
    private static void costPhase(ClientGameTestContext ctx, String label, double[] cal, boolean cached,
                                  List<String> report, List<String> failures) {
        Phase p = PerfBench.measure(ctx, label, 400, cal);
        long[] cost = cached ? ctx.computeOnClient(mc -> (long[]) Mod.staticCall(CACHE, "stats")) : new long[5];
        report.add(p.line());
        Key map = null;
        for (Key k : p.keys().values()) {
            if (k.key().contains("HUD[live_map]")) {
                map = k;
            }
        }
        if (map == null || map.calls() < p.frames() / 2) {
            failures.add(label + ": the Dungeon Map did not draw every frame (" + (map == null ? 0 : map.calls())
                    + " draws in " + p.frames() + " frames) - nothing was measured");
            return;
        }
        double bytes = map.bytes() / Math.max(1, p.frames());
        double budget = ALLOC_BUDGET + ALLOC_TICK_BUDGET * p.ticks() / Math.max(1, p.frames());
        String line = String.format(Locale.ROOT, "%s: HUD[live_map] %.2f us/frame, %.0f B/frame over %d frames,"
                        + " %d ticks; cache %d replays, %d paints (budget %.0f B/frame + %.0f B/tick = %.0f B/frame)", label,
                map.ns() / 1e3 / Math.max(1, p.frames()), bytes, p.frames(), p.ticks(), cost[0], cost[1], ALLOC_BUDGET,
                ALLOC_TICK_BUDGET, budget);
        report.add(line);
        if (bytes > budget) {
            failures.add(line);
        }
        if (!cached) {
            return;
        }
        // Players are painted once a tick; the rooms, labels and Extra Info only when they change.
        if (cost[0] < p.frames()) {
            failures.add(label + ": the draw cache replayed only " + cost[0] + " part(s) in " + p.frames() + " frames");
        }
        if (cost[1] > p.ticks() * 1.5 + 20) {
            failures.add(label + ": the draw cache painted " + cost[1] + " part(s) in " + p.ticks()
                    + " ticks: more than the players once a tick");
        }
    }

    private static boolean hasMethod(String cls, String name) {
        for (java.lang.reflect.Method m : Mod.cls(cls).getMethods()) {
            if (m.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static void remember(Map<String, Object[]> restore, String cfgClass, String getter, String setter) {
        Object cfg = Mod.cfg(cfgClass);
        restore.put(cfgClass + "#" + setter, new Object[]{cfg, setter, Mod.call(cfg, getter)});
    }

    /** Each change the map must notice, made on the client thread. */
    private static Map<String, Consumer<Object>> changes() {
        Map<String, Consumer<Object>> out = new LinkedHashMap<>();
        out.put("Room Labels: Secrets", mc -> Mod.set("livemap.LiveMapConfig", "setRoomLabels", 2));
        out.put("Room Labels: Room Name", mc -> Mod.set("livemap.LiveMapConfig", "setRoomLabels", 3));
        out.put("Text Shadow toggled", mc -> Mod.set("livemap.LiveMapConfig", "setTextShadow",
                !(Boolean) Mod.get("livemap.LiveMapConfig", "isTextShadow")));
        out.put("Darken Unexplored 0.5", mc -> Mod.set("livemap.LiveMapConfig", "setDarkenUnopened", 0.5f));
        out.put("Theme: Ocean", mc -> Mod.call(Mod.cfg("livemap.LiveMapConfig"), "setMapThemeName", "OCEAN"));
        out.put("Color By Type off", mc -> Mod.set("livemap.LiveMapConfig", "setColourByType", false));
        out.put("sim room failed", mc -> Mod.staticCall("roomsim.SimRoomState", "markFailed", roomName(1)));
        out.put("sim room back to normal", mc -> Mod.staticCall("roomsim.SimRoomState", "clearRoom", roomName(1)));
        out.put("sim door made a wither door", mc -> Mod.staticCall("livemap.LiveMapFeature", "setSimDoorWither",
                normalDoor()));
        out.put("Custom Checkmark Sprite toggled", mc -> Mod.set("livemap.LiveMapConfig", "setCustomCheckmarks",
                !(Boolean) Mod.get("livemap.LiveMapConfig", "isCustomCheckmarks")));
        out.put("Predictive Rooms toggled", mc -> Mod.set("livemap.predict.PredictiveRoomsConfig", "setEnabled",
                !(Boolean) Mod.get("livemap.predict.PredictiveRoomsConfig", "isEnabledRaw")));
        out.put("Map Background color", mc -> Mod.set("livemap.LiveMapConfig", "setMapBackground", 0xC0102030));
        out.put("Room Labels: Name + Secrets", mc -> Mod.set("livemap.LiveMapConfig", "setRoomLabels", 4));
        out.put("walked into another room", mc -> moveToRoom((net.minecraft.client.Minecraft) mc, 3));
        return out;
    }

    /** Puts him (server side) on the floor at the middle of the current layout's room {@code room}'s first tile. */
    private static void moveToRoom(net.minecraft.client.Minecraft mc, int room) {
        Object layout = Mod.staticCall("livemap.DungeonLayout", "current");
        int tile = ((int[]) Mod.call(layout, "tiles", room))[0];
        net.minecraft.core.BlockPos c = (net.minecraft.core.BlockPos) Mod.staticCall("livemap.DungeonLayout", "cellCenter", tile);
        int x = c.getX();
        int z = c.getZ();
        int fromY = (int) Math.floor(mc.player.getY()) + 12;
        var server = mc.getSingleplayerServer();
        java.util.UUID uuid = mc.player.getUUID();
        server.execute(() -> {
            var sp = server.getPlayerList().getPlayer(uuid);
            if (sp == null) {
                return;
            }
            var level = sp.level();
            for (int y = fromY; y > fromY - 40 && y > level.getMinY(); y--) {
                net.minecraft.core.BlockPos b = new net.minecraft.core.BlockPos(x, y, z);
                if (!level.getBlockState(b).isAir() && level.getBlockState(b.above()).isAir()
                        && level.getBlockState(b.above(2)).isAir()) {
                    sp.teleportTo(x + 0.5, y + 1, z + 0.5);
                    return;
                }
            }
        });
    }

    /** The name of the n-th room of the current layout that has one. */
    private static String roomName(int n) {
        Object layout = Mod.staticCall("livemap.DungeonLayout", "current");
        int rooms = (Integer) Mod.call(layout, "roomCount");
        int seen = 0;
        for (int r = 0; r < rooms; r++) {
            String name = (String) Mod.call(layout, "name", r);
            if (name != null && !name.equals("Unknown") && !name.equalsIgnoreCase("Entrance") && seen++ == n) {
                return name;
            }
        }
        throw new AssertionError("the sim floor has fewer than " + (n + 1) + " named rooms");
    }

    /** A cell holding an ordinary door on the current layout. */
    private static int normalDoor() {
        Object layout = Mod.staticCall("livemap.DungeonLayout", "current");
        for (int idx = 0; idx < 121; idx++) {
            if ((Integer) Mod.call(layout, "doorType", idx) == 1) {
                return idx;
            }
        }
        throw new AssertionError("the sim floor has no ordinary door");
    }
}

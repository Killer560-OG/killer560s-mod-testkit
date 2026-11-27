package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * <b>1127-sim-ac-tripfirstuse</b> (mod tl1127-trip-firstuse, from killer560's 11-27 "look at how every single tick is spent.
 * there are lots of dead ticks waiting on thing"): an Interactive Map trip's first warp goes out soon after the trip starts.
 * <p>
 * His 11-27 10:42-11:07 session (killer560s-mod-logs/autoclear-tick-ledger-1127-state.md) stood still 404 ticks before the
 * first warp of Auto Clear's etherwarp trips, 1-13 ticks a trip. The same fixed sim F7 and Hyperion loadout as
 * 1124-sim-ac-presneak; a probe reads, at the END of every tick, when a queue started ({@code ClearExecutor.lastPressTick})
 * and the tick its first hop went out ({@code hopSendTicks}), and for every tick between them the executor's own wait state
 * (its wait reason, the sneak the server heard, the delays). Asserts the floor cleared, at least {@link #MIN_TRIPS} trips
 * sent a first warp, and the ticks from trip start to first warp summed over them are at most {@link #MAX_DEAD_PER_TRIP}
 * per trip. Gives its own PASS row on a legit jar (Auto Clear refuses to start there).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimTripFirstUseTests implements FabricClientGameTest {

    private static final String NAME = "1127-sim-ac-tripfirstuse";
    private static final String AUTO = "com.killer560.hub.autoclear.AutoClearFeature";
    private static final String EXEC = "com.killer560.hub.livemap.autoclear.ClearExecutor";
    /** Trips with a first warp the floor must run, at least. */
    private static final int MIN_TRIPS = 25;
    /** Ticks from a trip's start to its first warp, averaged over the floor's trips, at most (set from the origin run). */
    private static final double MAX_DEAD_PER_TRIP = Double.parseDouble(System.getProperty("testkit.tripFirstUseMax", "0.6"));
    private static final String[] FIELDS = {"waitReason", "syncDelay", "globalDelay", "compDelay", "sendNow", "gateHold",
            "hold", "pendingInteract", "swappedOnTick", "pathPending", "tripQueued"};

    private static boolean registered;
    private static final Map<String, Field> F = new java.util.HashMap<>();
    private static Field acPhase;
    private static Field acAction;
    /** Auto Clear's phase and action at the END of the tick before (what it was doing just before a trip started). */
    private static String prevAc = "";
    private static String startAc = "";
    private static final Map<String, Integer> BEFORE = new TreeMap<>();
    private static long lastPress = -1;
    private static long openStart = -1;
    private static boolean prevShift;
    private static final int[] GAPS = new int[6];
    private static int trips;
    private static int noHop;
    private static long deadTicks;
    private static final Map<String, Integer> WHY = new TreeMap<>();
    private static final List<String> SAMPLES = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        if (!Ap3Crystal1109Cases.cheatBuild()) {
            legitRow(ctx);
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.runOnClient(mc -> install());
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "isReady"), 2400);
        ctx.runOnClient(mc -> {
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                    "setAutoPuzzlesMasterEnabled", false);
            Object map = ModUnderTest.config("com.killer560.hub.livemap.LiveMapConfig");
            ModUnderTest.set(map, "setEnabled", true);
            ModUnderTest.set(map, "setInteractiveMapEnabled", true);
        });
        String[][] props = {{"testkit.acsHype", "true"}, {"testkit.acsHypeWeapon", "true"}, {"testkit.acsMobs", "3"},
                {"testkit.acsMinutes", "4"}};
        String[] old = new String[props.length];
        for (int i = 0; i < props.length; i++) {
            old[i] = System.getProperty(props[i][0]);
            System.setProperty(props[i][0], props[i][1]);
        }
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> {
            mc.options.renderDistance().set(16);
            java.util.Arrays.fill(GAPS, 0);
            trips = 0;
            noHop = 0;
            deadTicks = 0;
            WHY.clear();
            BEFORE.clear();
            SAMPLES.clear();
            openStart = -1;
            lastPress = -1;
        });
        List<SimAutoClearSpeedTests.RoomTime> rooms = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        try {
            println("SUMMARY " + SimAutoClearSpeedTests.oneFloor(ctx, 1, SimAcPreSneakTests.CODE, rooms, problems));
        } finally {
            SimAutoClearSpeedTests.leave(ctx);
            ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
            for (int i = 0; i < props.length; i++) {
                if (old[i] == null) {
                    System.clearProperty(props[i][0]);
                } else {
                    System.setProperty(props[i][0], old[i]);
                }
            }
        }
        String report = ctx.computeOnClient(mc -> {
            StringBuilder b = new StringBuilder();
            b.append("trips ").append(trips).append(", no first warp ").append(noHop).append(", start-to-first-warp gaps 0:")
                    .append(GAPS[0]).append(" 1:").append(GAPS[1]).append(" 2:").append(GAPS[2]).append(" 3:").append(GAPS[3])
                    .append(" 4:").append(GAPS[4]).append(" 5+:").append(GAPS[5]).append(", ticks before first warps ")
                    .append(deadTicks).append("; states:");
            WHY.entrySet().stream().sorted((a, c) -> c.getValue() - a.getValue()).limit(25)
                    .forEach(e -> b.append(" [").append(e.getValue()).append("] ").append(e.getKey()).append(";"));
            b.append(" by what Auto Clear did the tick before:");
            BEFORE.entrySet().stream().sorted((a, c) -> c.getValue() - a.getValue()).limit(30)
                    .forEach(e -> b.append(" [").append(e.getValue()).append("] ").append(e.getKey()).append(";"));
            return b.toString();
        });
        println("RESULT " + report);
        for (String s : ctx.computeOnClient(mc -> new ArrayList<>(SAMPLES))) {
            println("SAMPLE " + s);
        }
        int total = rooms.stream().mapToInt(SimAutoClearSpeedTests.RoomTime::ticks).sum();
        println("RESULT room ticks " + total + " over " + rooms.size() + " rooms");
        long notCleared = rooms.stream().filter(r -> !r.cleared()).count();
        if (rooms.size() < 10 || notCleared > 0) {
            problems.add(rooms.size() + " room line(s), " + notCleared + " not cleared - the floor was not cleared");
        }
        int t = ctx.computeOnClient(mc -> trips);
        long dead = ctx.computeOnClient(mc -> deadTicks);
        if (t < MIN_TRIPS) {
            problems.add(t + " trip(s) sent a first warp, want at least " + MIN_TRIPS);
        } else if ((double) dead / t > MAX_DEAD_PER_TRIP) {
            problems.add(dead + " tick(s) from trip start to first warp over " + t + " trips ("
                    + String.format(java.util.Locale.ROOT, "%.2f", (double) dead / t) + " a trip), want at most "
                    + MAX_DEAD_PER_TRIP + " a trip");
        }
        if (!problems.isEmpty()) {
            throw new AssertionError(problems.size() + " problem(s): " + String.join("; ", problems));
        }
        println("PASS");
    }

    private static void install() {
        if (registered) {
            return;
        }
        try {
            Class<?> c = Class.forName(EXEC);
            for (String n : FIELDS) {
                try {
                    Field f = c.getDeclaredField(n);
                    f.setAccessible(true);
                    F.put(n, f);
                } catch (NoSuchFieldException ignored) {
                    // an older or newer jar without this one
                }
            }
            Class<?> a = Class.forName(AUTO);
            acPhase = a.getDeclaredField("phase");
            acPhase.setAccessible(true);
            acAction = a.getDeclaredField("action");
            acAction.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(NAME + ": " + e);
        }
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(SimTripFirstUseTests::probe);
    }

    private static Object read(String n) {
        Field f = F.get(n);
        try {
            return f == null ? "?" : f.get(null);
        } catch (IllegalAccessException e) {
            return "?";
        }
    }

    /**
     * END of each tick: a queue started this tick (its press tick moved) opens a trip; the first hop sent at or after it
     * closes it with its gap. Every tick in between is tallied by the executor's own wait state.
     */
    @SuppressWarnings("unchecked")
    private static void probe(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        long tick;
        long press;
        List<Long> hops;
        boolean busy;
        try {
            tick = (Long) ModUnderTest.staticCall(EXEC, "clientTick");
            press = (Long) ModUnderTest.staticCall(EXEC, "lastPressTick");
            hops = (List<Long>) ModUnderTest.staticCall(EXEC, "hopSendTicks");
            busy = (Boolean) ModUnderTest.staticCall(EXEC, "isBusy");
        } catch (RuntimeException e) {
            return;
        }
        boolean shiftHeard = prevShift;
        prevShift = mc.player.getLastSentInput().shift();
        String ac;
        try {
            String act = String.valueOf(acAction.get(null));
            ac = acPhase.get(null) + ":" + act.replaceAll("[0-9.,-]+", "#").replaceAll(" \\(.*", "");
        } catch (IllegalAccessException e) {
            ac = "?";
        }
        String before = prevAc;
        prevAc = ac;
        if (press != lastPress) {
            if (openStart >= 0) {
                noHop++;   // replaced by a new queue before its first warp
            }
            openStart = press;
            lastPress = press;
            startAc = "before=" + before + " heardAtStart=" + shiftHeard;
        }
        if (openStart < 0) {
            return;
        }
        long first = -1;
        for (int i = hops.size() - 1; i >= 0 && hops.get(i) >= openStart; i--) {
            first = hops.get(i);
        }
        if (first >= 0) {
            int gap = (int) (first - openStart);
            GAPS[Math.min(5, gap)]++;
            BEFORE.merge("gap " + Math.min(5, gap) + " " + startAc, 1, Integer::sum);
            trips++;
            deadTicks += gap;
            openStart = -1;
            return;
        }
        if (!busy) {
            noHop++;
            openStart = -1;
            return;
        }
        String key = "t+" + Math.min(5, tick - openStart) + " why=" + read("waitReason") + " heard=" + shiftHeard
                + " sent=" + prevShift + " sync=" + read("syncDelay") + " gd=" + read("globalDelay") + " send="
                + read("sendNow") + " pend=" + (read("pendingInteract") != null) + " swapT=" + (tick - asLong(read("swappedOnTick")))
                + " gate=" + (read("gateHold") != null) + " hold=" + read("hold") + " search=" + read("pathPending");
        WHY.merge(key, 1, Integer::sum);
        if (SAMPLES.size() < 40) {
            SAMPLES.add("tick " + tick + " start " + openStart + " " + key);
        }
    }

    private static long asLong(Object o) {
        return o instanceof Long l ? l : Long.MIN_VALUE / 2;
    }

    private static void legitRow(ClientGameTestContext ctx) {
        ModUnderTest.require("killer560smod");
        boolean busy = ctx.computeOnClient(mc -> {
            ModUnderTest.staticCall(AUTO, "start");
            return (Boolean) ModUnderTest.staticCall(AUTO, "isBusy");
        });
        if (busy) {
            throw new AssertionError("legit jar: Auto Clear started");
        }
        println("legit jar: Auto Clear is cheat-only and did not start, nothing to judge");
        println("PASS");
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}

package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import dev.testkit.compat.McCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 1124-sim-roomdrills (idea-roomdrills-1124): Weak-Room Drills. Every room loaded on its own in the sim is timed from the
 * end of its loading to its clear (the sim's own cleared state, which the sim sets when the last starred mob dies; the
 * test sets it with the same call) at the sim's game-tick pace, and the time is kept per room in the shared record.
 *
 * <p>Flow: with no data the Weak Rooms button says "not enough data yet" and builds nothing; All Rooms' first five rooms
 * are each cleared after a known number of ticks (2, 6, 10, 14, 18 s); the timer must be running before the clear, and
 * the measured time must be at least the wait; the record (re-read from disk) must hold five rooms; Weak Rooms must then
 * hold exactly those five, slowest first (the oracle is the test's own waits); the first drill room cleared faster must
 * say "New best!", keep the old best out of the way and make two runs; the timer HUD must report itself on.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimRoomDrillsTests implements FabricClientGameTest {

    private static final String NAME = "1124-sim-roomdrills";
    private static final String DRILLS = "com.killer560.hub.roomsim.SimRoomDrills";
    private static final String CYCLE = "com.killer560.hub.roomsim.SimRoomCycle";
    private static final String STATE = "com.killer560.hub.roomsim.SimRoomState";
    private static final int[] WAIT = {40, 120, 200, 280, 360};

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
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "isReady"), 2400);
        ctx.runOnClient(mc -> ModUnderTest.call(field("com.killer560.hub.roomsim.SimRoomFilters", "CYCLE"),
                "clear", new Class<?>[]{}, new Object[]{}));

        List<String> failures = new ArrayList<>();
        long mark = LogTap.mark();
        try {
            // ---- no data: the button says so and builds nothing -----------------------------------------
            SimRoomCycleTests.openCycleMenu(ctx);
            check(failures, SimRoomCycleTests.buttonLabels(ctx).stream().anyMatch(l -> l.startsWith("Weak Rooms (")),
                    "no Weak Rooms button on " + SimRoomCycleTests.buttonLabels(ctx));
            // ---- mobs on (the Map Designer's remembered Mobs choice, which it re-applies each time it opens): without
            // ---- them the sim clears a room on entry and a load is not timed
            ctx.runOnClient(mc -> {
                try {
                    Object prefs = ModUnderTest.staticCall("com.killer560.hub.roomsim.SimMenuPrefs", "load");
                    prefs.getClass().getField("mobs").setBoolean(prefs, true);
                    ModUnderTest.call(prefs, "save", new Class<?>[]{}, new Object[]{});
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
                ModUnderTest.staticCall("com.killer560.hub.roomsim.SimMobFloor", "setEnabled",
                        new Class<?>[]{boolean.class}, new Object[]{true});
            });
            // ---- time five rooms from All Rooms ----------------------------------------------------------
            long before = Scenario.simBuildCount(ctx);
            SimRoomCycleTests.press(ctx, l -> l.startsWith("All Rooms (") && Character.isDigit(l.charAt(11)), "All Rooms");
            Scenario.awaitSimBuild(ctx, before);
            ctx.waitFor(mc -> dev.testkit.compat.McCompat.screen(mc) == null, 1200);
            @SuppressWarnings("unchecked")
            List<String> all = ctx.computeOnClient(mc -> new ArrayList<>(
                    (List<String>) ModUnderTest.staticCall(CYCLE, "currentRooms")));
            List<String> timed = new ArrayList<>();
            for (int i = 0; i < WAIT.length; i++) {
                String room = all.get(i);
                if (i > 0) {
                    long b = Scenario.simBuildCount(ctx);
                    ctx.runOnClient(mc -> mc.player.connection.sendCommand("next"));
                    Scenario.awaitSimBuild(ctx, b);
                }
                SimRoomCycleTests.awaitLoaded(ctx, room);
                check(failures, !(Boolean) ctx.computeOnClient(mc -> ModUnderTest.staticCall(STATE, "isCleared",
                        new Class<?>[]{String.class}, new Object[]{room})), room + " was already cleared on load");
                ctx.waitFor(mc -> stats(mc).contains("started=true"), 400);
                int placedMobs = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(
                        "com.killer560.hub.roomsim.SimLiveMobs", "placedIn", new Class<?>[]{String.class}, new Object[]{room}));
                System.out.println("[" + NAME + "] " + room + ": starred mobs placed " + placedMobs);
                if (i == 0) {
                    check(failures, placedMobs > 0, "no mobs were placed in " + room + " by the timer start");
                }
                if (i == 0) {
                    // no times saved yet: Weak Rooms says so and builds nothing (chat needs a world, so in the sim)
                    SimRoomCycleTests.openCycleMenu(ctx);
                    long nb = Scenario.simBuildCount(ctx);
                    long m0 = LogTap.mark();
                    SimRoomCycleTests.press(ctx, l -> l.startsWith("Weak Rooms ("), "Weak Rooms");
                    ctx.waitTicks(20);
                    check(failures, LogTap.since(m0).stream().anyMatch(l -> l.contains("not enough data yet")),
                            "Weak Rooms with no times did not say 'not enough data yet': " + LogTap.since(m0));
                    check(failures, Scenario.simBuildCount(ctx) == nb, "Weak Rooms with no times built a room");
                    ctx.runOnClient(mc -> mc.execute(() -> McCompat.setScreen(mc, null)));
                    ctx.waitTicks(5);
                }
                check(failures, stats(ctx).contains("finished=false") && stats(ctx).contains("room=" + room),
                        "timer not running for " + room + ": " + stats(ctx));
                ctx.waitTicks(WAIT[i]);
                long m = LogTap.mark();
                clear(ctx, room, placedMobs > 0, failures);
                long ms = lastMs(stats(ctx));
                System.out.println("[" + NAME + "] " + room + ": waited " + WAIT[i] + " ticks, timer says " + ms + " ms; "
                        + stats(ctx));
                check(failures, stats(ctx).contains("finished=true"), room + " not finished: " + stats(ctx));
                check(failures, ms >= WAIT[i] * 50L && ms < WAIT[i] * 50L + 4000, room + " time " + ms + " ms for "
                        + WAIT[i] * 50 + " ms waited");
                check(failures, LogTap.since(m).stream().anyMatch(l -> l.contains(room) && l.contains("First time!")),
                        "no 'First time!' line for " + room + ": " + LogTap.since(m));
                timed.add(room);
            }
            ctx.runOnClient(mc -> ModUnderTest.staticCall(DRILLS, "reloadForTest"));
            for (int i = 0; i < timed.size(); i++) {
                String room = timed.get(i);
                long avg = ctx.computeOnClient(mc -> (Long) ModUnderTest.staticCall(DRILLS, "averageMs",
                        new Class<?>[]{String.class}, new Object[]{room}));
                check(failures, avg >= WAIT[i] * 50L, "record lost " + room + " after a re-read: " + avg);
            }

            // ---- Weak Rooms: the five, slowest first ----------------------------------------------------
            List<String> expect = new ArrayList<>(timed);
            java.util.Collections.reverse(expect);
            SimRoomCycleTests.openCycleMenu(ctx);
            check(failures, SimRoomCycleTests.buttonLabels(ctx).contains("Weak Rooms (5)"),
                    "Weak Rooms count: " + SimRoomCycleTests.buttonLabels(ctx));
            long b2 = Scenario.simBuildCount(ctx);
            SimRoomCycleTests.press(ctx, l -> l.startsWith("Weak Rooms ("), "Weak Rooms");
            Scenario.awaitSimBuild(ctx, b2);
            ctx.waitFor(mc -> dev.testkit.compat.McCompat.screen(mc) == null, 1200);
            SimRoomCycleTests.awaitLoaded(ctx, expect.get(0));
            @SuppressWarnings("unchecked")
            List<String> drill = ctx.computeOnClient(mc -> new ArrayList<>(
                    (List<String>) ModUnderTest.staticCall(CYCLE, "currentRooms")));
            check(failures, drill.equals(expect), "drill rooms " + drill + ", expected slowest first " + expect);
            check(failures, (Boolean) ctx.computeOnClient(mc -> ModUnderTest.staticCall(CYCLE, "isActive")),
                    "the drill is not stepping (isActive false)");

            // ---- a faster clear of the slowest room is a new best and a second run -------------------------
            String slow = expect.get(0);
            long oldBest = ctx.computeOnClient(mc -> (Long) ModUnderTest.staticCall(DRILLS, "pbMs",
                    new Class<?>[]{String.class}, new Object[]{slow}));
            ctx.waitFor(mc -> stats(mc).contains("started=true"), 400);
            ctx.waitTicks(40);
            long m = LogTap.mark();
            clear(ctx, slow, true, failures);
            long fast = lastMs(stats(ctx));
            check(failures, LogTap.since(m).stream().anyMatch(l -> l.contains(slow) && l.contains("New best!")),
                    "no 'New best!' for " + slow + " (best was " + oldBest + ", this " + fast + "): " + LogTap.since(m));
            ctx.runOnClient(mc -> ModUnderTest.staticCall(DRILLS, "reloadForTest"));
            long newBest = ctx.computeOnClient(mc -> (Long) ModUnderTest.staticCall(DRILLS, "pbMs",
                    new Class<?>[]{String.class}, new Object[]{slow}));
            long avg = ctx.computeOnClient(mc -> (Long) ModUnderTest.staticCall(DRILLS, "averageMs",
                    new Class<?>[]{String.class}, new Object[]{slow}));
            System.out.println("[" + NAME + "] " + slow + ": best " + oldBest + " -> " + newBest + ", last " + fast
                    + ", average " + avg);
            check(failures, newBest == fast && fast < oldBest, "best did not drop to the faster clear: " + oldBest + " -> "
                    + newBest + " (last " + fast + ")");
            check(failures, avg > fast && avg < oldBest, "two runs not averaged: " + avg + " between " + fast + " and " + oldBest);

            // ---- the timer HUD reports itself on while a room is timed --------------------------------------
            check(failures, ctx.computeOnClient(mc -> {
                Object hud = field(DRILLS, "HUD");
                return (Boolean) ModUnderTest.call(hud, "isEnabledInSettings", new Class<?>[]{}, new Object[]{});
            }), "the Room Drill Timer HUD is off while a room is timed");

            for (String e : LogTap.modErrorsSince(mark)) {
                failures.add("mod ERROR: " + e);
            }
            if (!failures.isEmpty()) {
                throw new AssertionError(failures.size() + " problem(s): " + failures);
            }
            System.out.println("[" + NAME + "] PASS - no data says so; five rooms timed at the waited ticks and kept in the "
                    + "record across a re-read; Weak Rooms holds them slowest first; a faster clear is a new best and "
                    + "a second run; the timer HUD is on");
        } finally {
            SimRoomCycleTests.teardown(ctx);
        }
    }

    /** Clears {@code room} the real way: every starred mob the timer start placed dies through the sim's own death hook
     *  (Entity.kill on the server thread), then the sim's own poll paints the clear. Without mobs it marks the room. */
    private static void clear(ClientGameTestContext ctx, String room, boolean real, List<String> failures) {
        if (real) {
            java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
            ctx.runOnClient(mc -> {
                var server = mc.getSingleplayerServer();
                server.execute(() -> {
                    try {
                        var f = Class.forName("com.killer560.hub.roomsim.SimMobs").getDeclaredField("STARRED");
                        f.setAccessible(true);
                        for (Object id : new ArrayList<>((List<?>) f.get(null))) {
                            var e = server.overworld().getEntity((java.util.UUID) id);
                            if (e != null && e.isAlive()) {
                                e.kill(server.overworld());
                                n.incrementAndGet();
                            }
                        }
                    } catch (ReflectiveOperationException ex) {
                        throw new AssertionError(ex);
                    }
                });
            });
            ctx.waitTicks(4);
            int killed = n.get();
            System.out.println("[" + NAME + "] " + room + ": killed " + killed + " starred mob(s)");
            check(failures, killed > 0, "no live starred mob to kill in " + room);
            try {
                ctx.waitFor(mc -> stats(mc).contains("finished=true"), 100);
            } catch (AssertionError ae) {
                System.out.println("[" + NAME + "] " + room + ": NO CLEAR, stats " + stats(ctx) + " sizes=" + sizes() + " state=" + ctx.computeOnClient(mc -> ModUnderTest.staticCall(STATE, "stateFor", new Class<?>[]{String.class}, new Object[]{room})));
                throw ae;
            }
        } else {
            System.out.println("[" + NAME + "] " + room + ": no mobs placed, cleared with markCleared");
            ctx.runOnClient(mc -> ModUnderTest.staticCall(STATE, "markCleared", new Class<?>[]{String.class},
                    new Object[]{room}));
            ctx.waitTicks(6);
        }
    }

    private static String sizes() {
        StringBuilder b = new StringBuilder();
        for (String n : new String[]{"STARRED", "STARRED_AT", "STARRED_DEAD", "KILLED"}) {
            try {
                var f = Class.forName("com.killer560.hub.roomsim.SimMobs").getDeclaredField(n);
                f.setAccessible(true);
                Object o = f.get(null);
                b.append(n).append("=").append(o instanceof java.util.Map<?, ?> m ? m.size() : ((java.util.Collection<?>) o).size()).append(" ");
            } catch (ReflectiveOperationException e) {
                b.append(n).append("=?").append(e).append(" ");
            }
        }
        return b.toString();
    }

    private static Object field(String cls, String name) {
        try {
            return Class.forName(cls).getField(name).get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no " + cls + "." + name + " in the mod under test: " + e);
        }
    }

    private static String stats(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> stats(mc));
    }

    private static String stats(net.minecraft.client.Minecraft mc) {
        return (String) ModUnderTest.staticCall(DRILLS, "stats");
    }

    private static long lastMs(String stats) {
        int i = stats.indexOf("lastMs=");
        return Long.parseLong(stats.substring(i + 7, stats.indexOf(' ', i)));
    }

    private static void check(List<String> failures, boolean ok, String what) {
        if (!ok) {
            failures.add(what);
            System.out.println("[" + NAME + "] FAIL: " + what);
        }
    }
}

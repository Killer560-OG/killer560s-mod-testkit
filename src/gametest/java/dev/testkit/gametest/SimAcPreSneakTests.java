package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>1124-sim-ac-presneak</b> (mod acoptimal-next-1124, from killer560's 11-21 "rank every single gap"): after an in-place
 * Wither Impact, the etherwarp trip to the next mob group leaves the tick the death is seen.
 * <p>
 * Origin pressed the sneak only on the tick the death came back, and a warp waits until the server has heard the sneak,
 * so every trip after a kill started a tick later (1020-sim-ac-speed on the 6 floors: 178 of 229 kill-to-warp gaps were
 * one tick, none zero). The fix holds the sneak down while the cast's deaths come back when only an etherwarp can follow.
 * <p>
 * One fixed sim F7 (floor 1 of codes.txt in killer560s-mod-logs/acslow-1121-state.md's 11-24 section), live starred mobs
 * on the rooms' spawn boxes, Auto Clear alone with the Hyperion (1020-sim-ac-speed's Hyperion loadout). A probe reads, each
 * tick, the kills Auto Clear counted and the ticks its warps went out on: a kill-to-first-warp gap of 0 is the fix acting.
 * Asserts the floor cleared, at least {@link #MIN_SAME_TICK} trips left on the kill's own tick, and
 * {@code AutoClearFeature.primedCastTicks} went up. Cheat only.
 * <p>
 * <b>1125-sim-ac-searchtick</b> (mod acoptimal-next2-1125), the same floor and probe: a trip whose path is SEARCHED (more than
 * one warp) also leaves on the kill's own tick. Origin handed the planner thread's result to the client only after the tick
 * ended (137 of 251 kill-to-warp gaps on the 6 floors were such trips, a tick each). Asserts the floor cleared,
 * {@code ClearExecutor.searchesInTick} went up by at least {@link #MIN_SEARCHED} and at least {@link #MIN_SAME_TICK_SEARCH}
 * trips left on the kill's own tick.
 * <p>
 * <b>1125-sim-ac-warmyield</b> (mod acoptimal-next3-1125), the same floor: a searched trip no longer waits in the planner
 * thread's queue behind a floor-graph warm-up slice. The full graph warms for 12-20 s of every floor, in 10 ms slices that
 * gave way only at their end, so origin's searched trips waited 5-12 ms in the queue (37 of 213 on the 6 floors, 15 of 37 on this one; the fix: 0-1) and many
 * left a tick late. The probe reads each "[Path] running ... queued X ms" line; asserts the floor cleared, at least
 * {@link #MIN_QUEUED_LINES} searched trips ran and at most {@link #MAX_SLOW_QUEUED} of them queued {@link #SLOW_QUEUE_MS} ms or more.
 * <p>
 * <b>1125-sim-ac-nearprime</b> (mod acoptimal-next4-1125), the same floor: the sneak is down before the kill also when a mob
 * left stands within a cast across. Origin primed it only when every mob left was beyond one (or the plan's next step was a
 * warp), so 59 of the 67 one-tick-late warps on the 6 floors waited for a sneak pressed on the kill's tick. Asserts the floor
 * cleared, at least {@link #MIN_KILL_TRIPS} trips after a kill, the sneak primed after casts, and at most {@link #MAX_ONE_LATE}
 * trips after a kill one tick late. It also prints, for reading only, the trips that found the sneak not yet heard at the
 * kill (the input of the tick before) and the casts after a kill by gap.
 * <p>
 * <b>1126-sim-ac-settletrip</b> (mod acoptimal-1126), the same floor: a trip after a kill no longer waits for him to stand
 * when he is coming down a short way onto a block (the half block onto a slab or carpet after an etherwarp's 1.05 landing or
 * a Wither Impact in place). Origin waited for the ground: 25 of the 6 floors' kill-to-warp gaps were 3-4 ticks, all
 * falls onto slabs and carpets. Asserts the floor cleared, {@code AutoClearFeature.settlingTrips} went up by at least
 * {@link #MIN_SETTLE} and at most {@link #MAX_LATE3} trips after a kill left 3 or more ticks late.
 * <p>
 * <b>1126-sim-ac-preturn</b> (mod acoptimal-1126), the same floor: the tick after the room plan's cast in place, while its
 * deaths come back, the body turns to the plan's cast across that follows, so that cast goes on the deaths' own tick. Origin
 * turned on that tick and cast the next (24-27 casts a run one tick late). Asserts the floor cleared,
 * {@code AutoClearFeature.preTurns} went up by at least {@link #MIN_PRETURN} and at least {@link #MIN_CAST_SAME_TICK} casts
 * went on a kill's own tick. Both cases give their own PASS row on a legit jar (Auto Clear refuses to start there).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimAcPreSneakTests implements FabricClientGameTest {

    private static final String PRESNEAK = "1124-sim-ac-presneak";
    private static final String SEARCHTICK = "1125-sim-ac-searchtick";
    private static final String WARMYIELD = "1125-sim-ac-warmyield";
    private static final String NEARPRIME = "1125-sim-ac-nearprime";
    private static final String SETTLE = "1126-sim-ac-settletrip";
    private static final String PRETURN = "1126-sim-ac-preturn";
    /** Trips started while still coming down onto a block, at least (settletrip). */
    private static final int MIN_SETTLE = 2;
    /** Trips after a kill 3 or more ticks late, at most (settletrip). */
    private static final int MAX_LATE3 = 2;
    /** Turns toward the plan's next cast while a cast in place's deaths came back, at least (preturn). */
    private static final int MIN_PRETURN = 3;
    /** Casts on a kill's own tick, at least (preturn). */
    private static final int MIN_CAST_SAME_TICK = 3;
    /** Trips after a kill the floor must run, at least. */
    private static final int MIN_KILL_TRIPS = 25;
    /** Trips after a kill allowed to leave one tick late (origin 3-7 on this floor over four runs, the fix 0-2). */
    private static final int MAX_ONE_LATE = 2;
    private static boolean prevShift;
    private static boolean killUnsneaked;
    private static int killTrips;
    private static int unsneakedTrips;
    private static final int[] CAST_GAPS = new int[3];
    /** Searched trips (lines with a queue time) the floor must run, at least. */
    private static final int MIN_QUEUED_LINES = 10;
    /** A queue wait this long is a warm-up slice in the way (a search alone is 1-4 ms). */
    private static final double SLOW_QUEUE_MS = 5.0;
    /** Searched trips allowed to queue that long. */
    private static final int MAX_SLOW_QUEUED = 4;
    private static final java.util.regex.Pattern QUEUED = java.util.regex.Pattern.compile("\\[Path\\] running .*\\(queued ([\\d.]+) ms");
    private static long logMark;
    private static int queuedLines;
    private static int slowQueued;
    private static double worstQueued;
    private static String NAME = PRESNEAK;
    private static final String AUTO = "com.killer560.hub.autoclear.AutoClearFeature";
    private static final String EXEC = "com.killer560.hub.livemap.autoclear.ClearExecutor";
    private static final String CODE = "MC2:CxcIRW50cmFuY2UFTWluZXMFRmFpcnkFTW9zc3kSQ29iYmxlIFdhbGwgUGlsbGFyCEJhc2VtZW50BUJsb29kCE5ldyBUcmFwBk1pcnJvcglXYXRlcmZhbGwDRGlwC1ByaXNvbiBDZWxsB0J1dHRvbnMJT3Zlcmdyb3duBUNyeXB0B0JvdWxkZXIGRHVuY2FuC1RpYyBUYWMgVG9lDkxvdHMgT2YgRmxvb3JzDUNyZWVwZXIgQmVhbXMQT3Zlcmdyb3duIENoYWlucw5SYXJlIE92ZXJncm93bgdUb21pb2thABACAAAAAA0CAA0CAA0CAQAAABEBAAAAAAcCAwAAAAYBAQAAAAAAAA0CAA0CAA0CAAAAAAAAAAAAAAAAAAAAAQAAAA8DAAAAAA0CAA0CAA0CAAAAAAgCAAAAAAkAAAAAAAUBAA8DAAAAAAAAAAAAAQAAAAAAAQAAAAAAAQAAAAAAAQAAAA8DAAAAABcAAQAAAAQCAAQCAAQCAAQCAAQCAAQCAAQCAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAA4CAQAAABIBAAAAABQBAQAAABMCAAAAAAMCAAAAAAsBAQAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAQAAAAAAAQAAAAoAAAoAAAoAAAoAAAoAAAoAAAoAAQAAAAIDAAIDAAIDAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIDAAIDAAIDAAwDAQAAABUAAQAAABYCAAAAAAEABAAAAAIDAAIDAAID";
    /** Trips that left on the kill's own tick, at least (origin: 0). */
    private static final int MIN_SAME_TICK = 8;
    /** Searched trips taken in the tick (origin: no counter, -1). */
    private static final int MIN_SEARCHED = 5;
    /** Same-tick trips with the searched ones in the tick too (presneak's floor: 11 before). */
    private static final int MIN_SAME_TICK_SEARCH = 14;

    private static boolean registered;
    private static Field killsF;
    private static int lastKills = -1;
    private static long killTick = -1;
    private static int lastUses = -1;
    private static int lastHops = -1;
    private static final int[] GAPS = new int[6];

    @Override
    public void runTest(ClientGameTestContext ctx) {
        for (String name : new String[]{PRESNEAK, SEARCHTICK, WARMYIELD, NEARPRIME, SETTLE, PRETURN}) {
            if (!System.getProperty("testkit.scenario", "").isBlank() && !Scenario.skip(name)) {
                NAME = name;
                if ((name.equals(SETTLE) || name.equals(PRETURN)) && !Ap3Crystal1109Cases.cheatBuild()) {
                    legitRow(ctx);
                    continue;
                }
                runOne(ctx, name.equals(SEARCHTICK), name.equals(WARMYIELD) || name.equals(NEARPRIME));
            }
        }
    }

    private void runOne(ClientGameTestContext ctx, boolean searchTick, boolean warmYield) {
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
        ctx.runOnClient(mc -> mc.options.renderDistance().set(16));
        int primedBefore = primed(ctx);
        int searchedBefore = searched(ctx);
        int settleBefore = counter(ctx, "settlingTrips");
        int preturnBefore = counter(ctx, "preTurns");
        ctx.runOnClient(mc -> {
            java.util.Arrays.fill(GAPS, 0);
            java.util.Arrays.fill(CAST_GAPS, 0);
            killTrips = 0;
            unsneakedTrips = 0;
            killTick = -1;
            logMark = LogTap.mark();
            queuedLines = 0;
            slowQueued = 0;
            worstQueued = 0;
        });
        List<SimAutoClearSpeedTests.RoomTime> rooms = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        try {
            println("SUMMARY " + SimAutoClearSpeedTests.oneFloor(ctx, 1, CODE, rooms, problems));
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
        int primedAfter = primed(ctx);
        int searchedAfter = searched(ctx);
        int settleAfter = counter(ctx, "settlingTrips");
        int preturnAfter = counter(ctx, "preTurns");
        int[] gaps = ctx.computeOnClient(mc -> {
            readQueued();
            return GAPS.clone();
        });
        int total = rooms.stream().mapToInt(SimAutoClearSpeedTests.RoomTime::ticks).sum();
        println("RESULT room ticks " + total + " over " + rooms.size() + " rooms; kill-to-first-warp gaps 0:" + gaps[0]
                + " 1:" + gaps[1] + " 2:" + gaps[2] + " 3:" + gaps[3] + " 4:" + gaps[4] + " 5+:" + gaps[5]
                + "; primed sneak ticks " + primedBefore + " -> " + primedAfter + "; searched trips taken in the tick "
                + searchedBefore + " -> " + searchedAfter + "; searched trips " + queuedLines + ", queued >= " + SLOW_QUEUE_MS
                + " ms " + slowQueued + " (worst " + worstQueued + " ms); trips after a kill " + killTrips
                + ", unsneaked at the kill " + unsneakedTrips + "; casts after a kill gap 0:" + CAST_GAPS[0] + " 1:"
                + CAST_GAPS[1] + " 2+:" + CAST_GAPS[2] + "; settling trips " + settleBefore + " -> " + settleAfter
                + "; pre-turns " + preturnBefore + " -> " + preturnAfter);
        long notCleared = rooms.stream().filter(r -> !r.cleared()).count();
        if (rooms.size() < 10 || notCleared > 0) {
            problems.add(rooms.size() + " room line(s), " + notCleared + " not cleared - the floor was not cleared");
        }
        int want = searchTick ? MIN_SAME_TICK_SEARCH : MIN_SAME_TICK;
        int late3 = gaps[3] + gaps[4] + gaps[5];
        if (NAME.equals(SETTLE)) {
            if (settleBefore < 0 || settleAfter - settleBefore < MIN_SETTLE) {
                problems.add("trips started while coming down onto a block " + settleBefore + " -> " + settleAfter
                        + ", want at least " + MIN_SETTLE + " more");
            }
            if (late3 > MAX_LATE3) {
                problems.add(late3 + " trip(s) after a kill left 3 or more ticks late, want at most " + MAX_LATE3
                        + " (gaps 0:" + gaps[0] + " 1:" + gaps[1] + " 2:" + gaps[2] + ")");
            }
        } else if (NAME.equals(PRETURN)) {
            if (preturnBefore < 0 || preturnAfter - preturnBefore < MIN_PRETURN) {
                problems.add("turns toward the plan's next cast " + preturnBefore + " -> " + preturnAfter + ", want at least "
                        + MIN_PRETURN + " more");
            }
            if (CAST_GAPS[0] < MIN_CAST_SAME_TICK) {
                problems.add(CAST_GAPS[0] + " cast(s) on a kill's own tick, want at least " + MIN_CAST_SAME_TICK
                        + " (gap 1:" + CAST_GAPS[1] + " 2+:" + CAST_GAPS[2] + ")");
            }
        } else if (NAME.equals(NEARPRIME)) {
            if (killTrips < MIN_KILL_TRIPS) {
                problems.add(killTrips + " trip(s) after a kill, want at least " + MIN_KILL_TRIPS);
            }
            if (gaps[1] > MAX_ONE_LATE) {
                problems.add(gaps[1] + " trip(s) after a kill left one tick late, want at most " + MAX_ONE_LATE
                        + " (gaps 0:" + gaps[0] + " 2:" + gaps[2] + "; unsneaked at the kill " + unsneakedTrips + " of " + killTrips + ")");
            }
        } else if (warmYield) {
            if (queuedLines < MIN_QUEUED_LINES) {
                problems.add(queuedLines + " searched trip(s) ran, want at least " + MIN_QUEUED_LINES);
            }
            if (slowQueued > MAX_SLOW_QUEUED) {
                problems.add(slowQueued + " searched trip(s) queued " + SLOW_QUEUE_MS + " ms or more on the planner thread"
                        + " (worst " + worstQueued + " ms), want at most " + MAX_SLOW_QUEUED);
            }
        } else if (gaps[0] < want) {
            problems.add(gaps[0] + " trip(s) left on the kill's own tick, want at least " + want
                    + " (gaps 1:" + gaps[1] + " 2:" + gaps[2] + " 3+:" + (gaps[3] + gaps[4] + gaps[5]) + ")");
        }
        if (searchTick && (searchedBefore < 0 || searchedAfter - searchedBefore < MIN_SEARCHED)) {
            problems.add("searched trips taken in the tick " + searchedBefore + " -> " + searchedAfter + ", want at least "
                    + MIN_SEARCHED + " more");
        }
        if (!searchTick && !NAME.equals(SETTLE) && !NAME.equals(PRETURN) && (primedBefore < 0 || primedAfter <= primedBefore)) {
            problems.add("the sneak was never primed after a cast (primed ticks " + primedBefore + " -> " + primedAfter + ")");
        }
        if (!problems.isEmpty()) {
            throw new AssertionError(problems.size() + " problem(s): " + String.join("; ", problems));
        }
        println("PASS");
    }

    /** Registers the kill-to-warp probe once (client thread). */
    private static void install() {
        if (registered) {
            return;
        }
        try {
            killsF = Class.forName(AUTO).getDeclaredField("mobsKilled");
            killsF.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(NAME + ": " + e);
        }
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(SimAcPreSneakTests::probe);
    }

    /**
     * END of each tick: a kill Auto Clear counted this tick opens a gap; the first warp after it closes it at its send
     * tick; any other use first (a cast, a bat) closes it as not a trip.
     */
    @SuppressWarnings("unchecked")
    private static void probe(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        if (mc.player.tickCount % 20 == 0) {
            readQueued();
        }
        int kills;
        long tick;
        int uses;
        List<Long> hops;
        try {
            kills = killsF.getInt(null);
            tick = (Long) ModUnderTest.staticCall(EXEC, "clientTick");
            uses = (Integer) ModUnderTest.staticCall("com.killer560.hub.util.RightClick", "itemUsesSent");
            hops = (List<Long>) ModUnderTest.staticCall(EXEC, "hopSendTicks");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return;
        }
        int hopCount = hops.size();
        long newHop = -1;
        if (lastHops >= 0 && hopCount > 0 && (hopCount != lastHops || hops.get(hopCount - 1) >= tick)) {
            for (Long t : hops) {
                if (t > killTick && killTick >= 0) {
                    newHop = t;
                    break;
                }
            }
        }
        if (killTick >= 0) {
            if (newHop >= 0) {
                GAPS[(int) Math.min(5, newHop - killTick)]++;
                killTrip();
                killTick = -1;
            } else if (lastUses >= 0 && uses > lastUses) {
                CAST_GAPS[(int) Math.min(2, tick - killTick)]++;
                killTick = -1;   // a cast or a bat went first: not a trip after the kill
            }
        }
        if (lastKills >= 0 && kills > lastKills) {
            killTick = tick;
            killUnsneaked = !prevShift;   // the input of the tick before: what the server heard when this tick's use goes
            if (!hops.isEmpty() && hops.get(hops.size() - 1) == tick) {
                GAPS[0]++;   // the warp went in the kill's own tick
                killTrip();
                killTick = -1;
            } else if (lastUses >= 0 && uses > lastUses) {
                CAST_GAPS[0]++;
                killTick = -1;
            }
        }
        prevShift = mc.player.getLastSentInput().shift();
        lastKills = kills;
        lastUses = uses;
        lastHops = hopCount;
    }

    private static void killTrip() {
        killTrips++;
        if (killUnsneaked) {
            unsneakedTrips++;
        }
    }

    /** Reads the queue time of every searched trip logged since the last read (client thread; the ring holds 4000 lines). */
    private static void readQueued() {
        long m = LogTap.mark();
        for (String line : LogTap.between(logMark, m)) {
            java.util.regex.Matcher q = QUEUED.matcher(line);
            if (q.find()) {
                double ms = Double.parseDouble(q.group(1));
                queuedLines++;
                worstQueued = Math.max(worstQueued, ms);
                if (ms >= SLOW_QUEUE_MS) {
                    slowQueued++;
                }
            }
        }
        logMark = m;
    }

    /** A legit jar: Auto Clear is cheat-only, so a start does nothing and the case passes on that. */
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

    /** An int counter of AutoClearFeature's, or -1 when this jar has none (origin). */
    private static int counter(ClientGameTestContext ctx, String name) {
        return ctx.computeOnClient(mc -> {
            try {
                return (Integer) ModUnderTest.staticCall(AUTO, name);
            } catch (RuntimeException | Error e) {
                return -1;
            }
        });
    }

    private static int primed(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            try {
                return (Integer) ModUnderTest.staticCall(AUTO, "primedCastTicks");
            } catch (RuntimeException | Error e) {
                return -1;   // origin: no primed sneak
            }
        });
    }

    private static int searched(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            try {
                return (Integer) ModUnderTest.staticCall(EXEC, "searchesInTick");
            } catch (RuntimeException | Error e) {
                return -1;   // origin: a searched trip's result waited for the next tick
            }
        });
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}

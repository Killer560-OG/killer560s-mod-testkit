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
 * falls onto slabs and carpets. The probe reads the log each tick: a death line ("is dead") seen while he is coming down
 * onto a block opens a gap that the next AOTV use closes. Asserts the floor cleared, {@code AutoClearFeature.settlingTrips}
 * went up by at least {@link #MIN_SETTLE}, at least {@link #MIN_SETTLE} such trips and at most {@link #MAX_LATE3} of them 3 or
 * more ticks after the death.
 * <p>
 * <b>1126-sim-ac-preturn</b> (mod acoptimal-1126), the same floor: the tick after the room plan's cast in place, while its
 * deaths come back, the body turns to the plan's cast across that follows, so that cast goes on the deaths' own tick. Origin
 * turned on that tick and cast the next (24-27 casts a run one tick late). The probe pairs each death line with the next
 * Hyperion use that follows a "Wither Impact (room plan)" decision. Asserts the floor cleared, {@code AutoClearFeature.preTurns}
 * went up by at least {@link #MIN_PRETURN}, at least {@link #MIN_CAST_SAME_TICK} such casts went on the death's own tick and
 * no more went a tick late than on it. Both cases give their own PASS row on a legit jar (Auto Clear refuses to start there).
 * <p>
 * <b>1126-sim-ac-sightleave</b> (mod acoptimal-1126b), the same floor: a room is left at its last death when every tile of it
 * has been within sight (48 blocks of its center) at some tick of the visit, not only when every tile is in sight at the
 * death. Origin stood "looking" in Mossy, Waterfall and Crypt (2x2 and long rooms) until the map's checkmark, 5-10 ticks a
 * room (72 a run on 1020-sim-ac-speed). Asserts the floor cleared, {@code AutoClearFeature.sightSeenLeaves} went up by at
 * least {@link #MIN_SIGHT}, and no room had to be gone back into (the map not clearing it after a leave).
 * <p>
 * <b>1126-sim-ac-prehop</b> (mod acoptimal-1126b), the same floor: the tick after a cast in place (old choice, no room plan),
 * while its deaths come back, the body turns to the one-hop Hyperion hop toward the mob the pick takes next, so the hop goes
 * on the deaths' own tick. Origin turned on that tick and hopped the next (about 12 hops a run). Asserts the floor cleared and
 * {@code AutoClearFeature.preHopUses} (hops that went along such a turn with no turn of their own) went up by at least
 * {@link #MIN_PREHOP}.
 * <p>
 * <b>1126-sim-ac-settlehop</b> (mod acoptimal-1126b), the same floor: coming down onto a block after a kill, the Hyperion
 * hop count for the next mob is worked out from where he will stand, not from the air. From the air the dash lands elsewhere,
 * so origin chose a hop, waited 3 ticks to land, and the trip went then anyway (1-3 times a floor, 4 a run). Asserts the
 * floor cleared and {@code AutoClearFeature.settledHopCounts} (counts from the landing that differ from the count from the
 * air) went up by at least {@link #MIN_SETTLEHOP}. All three new cases give their own PASS row on a legit jar.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimAcPreSneakTests implements FabricClientGameTest {

    private static final String PRESNEAK = "1124-sim-ac-presneak";
    private static final String SEARCHTICK = "1125-sim-ac-searchtick";
    private static final String WARMYIELD = "1125-sim-ac-warmyield";
    private static final String NEARPRIME = "1125-sim-ac-nearprime";
    private static final String SETTLE = "1126-sim-ac-settletrip";
    private static final String PRETURN = "1126-sim-ac-preturn";
    private static final String SIGHT = "1126-sim-ac-sightleave";
    private static final String PREHOP = "1126-sim-ac-prehop";
    private static final String SETTLEHOP = "1126-sim-ac-settlehop";
    /** Hop counts the landing changed, at least (settlehop). */
    private static final int MIN_SETTLEHOP = 1;
    /** Last-death leaves only the tiles seen earlier in the visit allowed, at least (sightleave). */
    private static final int MIN_SIGHT = 1;
    /** Hops that went on a pre-turned look, at least (prehop). */
    private static final int MIN_PREHOP = 1;
    /** Trips started while still coming down onto a block, at least (settletrip). */
    private static final int MIN_SETTLE = 2;
    /** Turns toward the plan's next cast while a cast in place's deaths came back, at least (preturn). */
    private static final int MIN_PRETURN = 2;
    /** Planned casts on a death's own tick, at least (preturn). */
    private static final int MIN_CAST_SAME_TICK = 2;
    private static final java.util.regex.Pattern DEAD = java.util.regex.Pattern.compile("\\[AutoClear\\] .+ in .+ is dead");
    /** Death line -> next AOTV use while he was settling at the death, by gap 0, 1, 2, 3+ (settletrip). */
    private static final int[] SETTLE_GAPS = new int[4];
    /** Death line -> next planned Hyperion cast, by gap 0, 1, 2+ (preturn). */
    private static final int[] PLAN_GAPS = new int[3];
    private static long lineMark = -1;
    /** "is still not cleared on the map" lines seen this floor (sightleave). */
    private static int backInLines;
    private static long deadTick = -1;
    private static long flushes;
    private static boolean deadSettling;
    private static boolean planCastSeen;
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
        for (String name : new String[]{PRESNEAK, SEARCHTICK, WARMYIELD, NEARPRIME, SETTLE, PRETURN, SIGHT, PREHOP, SETTLEHOP}) {
            if (!System.getProperty("testkit.scenario", "").isBlank() && !Scenario.skip(name)) {
                NAME = name;
                if ((name.equals(SETTLE) || name.equals(PRETURN) || name.equals(SIGHT) || name.equals(PREHOP)
                        || name.equals(SETTLEHOP))
                        && !Ap3Crystal1109Cases.cheatBuild()) {
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
        int sightBefore = counter(ctx, "sightSeenLeaves");
        int prehopBefore = counter(ctx, "preHopUses");
        int preturnHopBefore = counter(ctx, "preHopTurns");
        int settleHopBefore = counter(ctx, "settledHopCounts");
        ctx.runOnClient(mc -> {
            java.util.Arrays.fill(GAPS, 0);
            java.util.Arrays.fill(CAST_GAPS, 0);
            java.util.Arrays.fill(SETTLE_GAPS, 0);
            java.util.Arrays.fill(PLAN_GAPS, 0);
            lineMark = LogTap.mark();
            backInLines = 0;
            deadTick = -1;
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
        int sightAfter = counter(ctx, "sightSeenLeaves");
        int prehopAfter = counter(ctx, "preHopUses");
        int preturnHopAfter = counter(ctx, "preHopTurns");
        int settleHopAfter = counter(ctx, "settledHopCounts");
        // Rooms the map still had not cleared after a last-death leave ("goes back in"): a leave that missed a mob.
        int backIn = ctx.computeOnClient(mc -> backInLines);
        int afterDeath = rooms.stream().filter(r -> r.lastDeath() >= 0).mapToInt(r -> r.ticks() - r.lastDeath()).sum();
        println("RESULT sight leaves " + sightBefore + " -> " + sightAfter + ", ticks after the rooms' last deaths "
                + afterDeath + ", rooms gone back into " + backIn + "; hop turns " + preturnHopBefore + " -> "
                + preturnHopAfter + ", hops on them " + prehopBefore + " -> " + prehopAfter + "; hop counts the landing changed "
                + settleHopBefore + " -> " + settleHopAfter);
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
                + "; pre-turns " + preturnBefore + " -> " + preturnAfter + "; death-to-warp while settling 0:" + SETTLE_GAPS[0]
                + " 1:" + SETTLE_GAPS[1] + " 2:" + SETTLE_GAPS[2] + " 3+:" + SETTLE_GAPS[3] + "; death-to-planned-cast 0:"
                + PLAN_GAPS[0] + " 1:" + PLAN_GAPS[1] + " 2+:" + PLAN_GAPS[2]);
        long notCleared = rooms.stream().filter(r -> !r.cleared()).count();
        if (rooms.size() < 10 || notCleared > 0) {
            problems.add(rooms.size() + " room line(s), " + notCleared + " not cleared - the floor was not cleared");
        }
        int want = searchTick ? MIN_SAME_TICK_SEARCH : MIN_SAME_TICK;
        int settled = SETTLE_GAPS[0] + SETTLE_GAPS[1] + SETTLE_GAPS[2] + SETTLE_GAPS[3];
        if (NAME.equals(SETTLE)) {
            if (settleBefore < 0 || settleAfter - settleBefore < MIN_SETTLE) {
                problems.add("trips started while coming down onto a block " + settleBefore + " -> " + settleAfter
                        + ", want at least " + MIN_SETTLE + " more");
            }
            // The death-to-use gaps in the RESULT line are info only: read in this END tick they put a same-tick death and
            // use 2+ apart on both jars (k1.py over the log shows the change), so only the mod's own counter judges.
            if (settled < MIN_SETTLE) {
                problems.add(settled + " trip(s) after a death seen while he settled, want at least " + MIN_SETTLE);
            }
        } else if (NAME.equals(PRETURN)) {
            if (preturnBefore < 0 || preturnAfter - preturnBefore < MIN_PRETURN) {
                problems.add("turns toward the plan's next cast " + preturnBefore + " -> " + preturnAfter + ", want at least "
                        + MIN_PRETURN + " more");
            }
            int planned = PLAN_GAPS[0] + PLAN_GAPS[1] + PLAN_GAPS[2];
            if (planned < MIN_CAST_SAME_TICK) {
                problems.add(planned + " planned cast(s) after a death, want at least " + MIN_CAST_SAME_TICK);
            }
        } else if (NAME.equals(SIGHT)) {
            if (sightBefore < 0 || sightAfter - sightBefore < MIN_SIGHT) {
                problems.add("rooms left at a death on tiles seen earlier " + sightBefore + " -> " + sightAfter
                        + ", want at least " + MIN_SIGHT + " more");
            }
            if (backIn > 0) {
                problems.add(backIn + " room(s) left at a death were not cleared on the map and had to be gone back into");
            }
        } else if (NAME.equals(PREHOP)) {
            if (prehopBefore < 0 || prehopAfter - prehopBefore < MIN_PREHOP) {
                problems.add("hops on a look turned while the cast in place's deaths came back " + prehopBefore + " -> "
                        + prehopAfter + " (turns " + preturnHopBefore + " -> " + preturnHopAfter + "), want at least "
                        + MIN_PREHOP + " more");
            }
        } else if (NAME.equals(SETTLEHOP)) {
            if (settleHopBefore < 0 || settleHopAfter - settleHopBefore < MIN_SETTLEHOP) {
                problems.add("hop counts worked out from the landing that differ from the air's " + settleHopBefore + " -> "
                        + settleHopAfter + ", want at least " + MIN_SETTLEHOP + " more");
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
        if (!searchTick && !NAME.equals(SETTLE) && !NAME.equals(PRETURN) && !NAME.equals(SIGHT) && !NAME.equals(PREHOP)
                && !NAME.equals(SETTLEHOP)
                && (primedBefore < 0 || primedAfter <= primedBefore)) {
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
        readDeaths(mc);
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

    /** This tick's log lines: a death opens a gap (noting whether he is settling onto a block), the next use closes it. */
    private static void readDeaths(Minecraft mc) {
        long m = LogTap.mark();
        if (lineMark < 0 || mc.level == null) {
            lineMark = m;
            return;
        }
        // One call per END tick reads that tick's lines, so a death and a use of the same tick share a count (the executor's
        // clientTick read here put them 2 apart, and the "[digflush]" lines k1.py counts do not reach LogTap).
        flushes++;
        for (String line : LogTap.between(lineMark, m)) {
            if (line.contains("is still not cleared on the map")) {
                backInLines++;
            }
            if (line.contains("room time:")) {
                deadTick = -1;
            } else if (DEAD.matcher(line).find()) {
                if (deadTick < 0) {
                    deadTick = flushes;
                    deadSettling = !mc.player.onGround() && mc.player.getDeltaMovement().y <= 0.0
                            && !mc.level.noCollision(mc.player, mc.player.getBoundingBox().move(0.0, -1.25, 0.0));
                    planCastSeen = false;
                }
            } else if (deadTick >= 0 && line.contains("Wither Impact (room plan)")) {
                planCastSeen = true;
            } else if (deadTick >= 0 && line.contains("[RightClick] use seq")) {
                int g = (int) (flushes - deadTick);
                if (line.contains("with HYPERION") && planCastSeen) {
                    PLAN_GAPS[Math.min(2, g)]++;
                } else if (line.contains("with ASPECT_OF_THE_VOID") && deadSettling) {
                    SETTLE_GAPS[Math.min(3, g)]++;
                }
                deadTick = -1;
            }
        }
        lineMark = m;
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

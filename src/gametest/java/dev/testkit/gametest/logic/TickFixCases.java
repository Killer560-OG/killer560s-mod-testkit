package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Mod branch tickfix-1104 (killer560, 2026-11-04: "the timers are still going off wrong for tick timers. it is better but
 * still happens ... i mainly notice it during goldor" and "run splits never shows lag now").
 *
 * <ul>
 *   <li>1104-logic-goldor-trusted: on a trusted per-tick clock (Hypixel) the Goldor phase, once locked, is not walked by
 *       hits near the prediction (his 2026-11-03 23:55 log: Spirit, Phoenix, Bonzo procs exactly 3 s apart and the third
 *       read 16 ticks off), two stray health drops a period apart do not re-lock it, hits read late out of bunched pings
 *       move nothing, and a wrong first lock is put right by two mask procs a period apart.</li>
 *   <li>1104-logic-split-lag: Split Timers' lag, driven at real wall-clock pace through the clock's own pings: a steady
 *       20 TPS reads 0, arrival jitter reads 0, and a one-second stall still reads about a second after the server's
 *       catch-up burst (until 2026-11-04 the burst paid it back and the Lag row read 0.00s).</li>
 * </ul>
 */
final class TickFixCases {

    private static final String CLOCK = "util.PerTickServerClock";
    private static final String TT = "ticktimers.TickTimersFeature";
    private static final String GT = "ticktimers.GoldorTick";
    private static final String LAG = "splittimers.SplitLagClock";

    private TickFixCases() {
    }

    private static void tick(int pings) {
        for (int i = 0; i < pings; i++) {
            Mod.staticCall(CLOCK, "pingForTest");
        }
        Mod.staticCall(CLOCK, "tickForTest");
        Mod.staticCall(GT, "endOfTick");
    }

    private static void ticks(int n) {
        for (int i = 0; i < n; i++) {
            tick(1);
        }
    }

    private static long clock() {
        return (Long) Mod.staticCall(CLOCK, "now");
    }

    private static int remaining() {
        return (Integer) Mod.staticCall(GT, "remaining");
    }

    private static int relocks() {
        return (Integer) Mod.staticCall(GT, "testRelocks");
    }

    /** A hit of one kind; a jar before tickfix-1104 has no kinds, so a proc or a multi hit is its "strong" hit there. */
    private static void hit(String kind) {
        try {
            Mod.staticCall(GT, "testHitKindNow", kind);
        } catch (AssertionError e) {
            if (!String.valueOf(e.getMessage()).contains("no static method")) {
                throw e;
            }
            Mod.staticCall(GT, "testHitNow", !"SELF".equals(kind));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> offsets() {
        return (List<Integer>) Mod.staticCall(GT, "testOffsetsAtHits");
    }

    /** Goldor's line, then the clock (trusted) and the line read together: returns the line's clock tick. */
    private static long goldorLine() {
        Mod.staticCall(TT, "resetAll");
        Mod.staticCall(TT, "onChatMessage", Component.literal("[BOSS] Goldor: Who dares trespass into my domain?"));
        Mod.staticCall(GT, "endOfTick");
        return clock();
    }

    static void goldorTrusted(LogicCase c) throws Exception {
        Object simWas = R.get("secrets.DungeonState", "simOverrideActive");
        boolean gateWas = (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled");
        Mod.staticCall("util.SkyblockGate", "setEnabled", false);
        R.set("secrets.DungeonState", "simOverrideActive", true);
        try (AutoCloseable on = Mod.with("ticktimers.TickTimersConfig", "Enabled", true);
             AutoCloseable g = Mod.with("ticktimers.TickTimersConfig", "GoldorTimer", true)) {
            Mod.staticCall(CLOCK, "hypixelForTest", Boolean.TRUE);
            Mod.staticCall(CLOCK, "untrustForTest");
            ticks(40);
            if (!c.check("trusted after two good windows", (Boolean) Mod.staticCall(CLOCK, "trusted"), "not trusted")) {
                return;
            }

            // (a) Hits a few ticks off the locked phase do not walk it (his 23:55:05-11 procs: the third read 16 off).
            long line = goldorLine();
            ticks(30);
            hit("SELF"); // the first hit locks the phase at line+30 (that lock counts as one re-lock)
            c.eq("(a) the first hit locks: 0 on its tick", 0, remaining());
            int r0 = relocks();
            ticks(66);
            hit("SELF"); // 6 late
            ticks(61);
            hit("SELF"); // 7 late
            ticks(53);
            c.eq("(a) clock at line+210", line + 210, clock());
            c.eq("(a) three periods after the lock the Tick reads 0, not walked by hits 6 and 7 ticks off", 0, remaining());
            c.eq("(a) no re-lock after the first hit", 0, relocks() - r0);

            // (b) Two stray health drops exactly a period apart are not Goldor (a mob, fire): no re-lock. A mask proc in
            // phase with them is: re-lock there.
            line = goldorLine();
            ticks(30);
            hit("SELF");
            r0 = relocks();
            ticks(20);
            hit("SELF"); // line+50, 20 off
            ticks(60);
            hit("SELF"); // line+110, 20 off, a period after the first stray
            ticks(40);
            c.eq("(b) clock at line+150", line + 150, clock());
            c.eq("(b) two health-only strays a period apart move nothing: 0 at line+150", 0, remaining());
            c.eq("(b) no re-lock on two health-only strays", 0, relocks() - r0);
            ticks(20);
            hit("PROC"); // line+170, in phase with both strays
            c.eq("(b) a mask proc in phase with them re-locks", 1, relocks() - r0);
            ticks(60);
            c.eq("(b) 0 a period after the proc", 0, remaining());

            // (c) A wrong first lock (a mob hit 25 ticks after the line), then his procs on Goldor's real tick (line+42,
            // line+102): the second proc re-locks onto them.
            line = goldorLine();
            ticks(25);
            hit("SELF");
            r0 = relocks();
            ticks(17);
            hit("PROC");
            ticks(60);
            hit("PROC");
            c.eq("(c) the second proc a period after the first re-locked", 1, relocks() - r0);
            ticks(60);
            c.eq("(c) clock at line+162", line + 162, clock());
            c.eq("(c) the Tick reads 0 on Goldor's next real hit", 0, remaining());

            // (d) Bunched pings: before each of Goldor's real hits one client tick carries none and the next carries three
            // server ticks, the hit being the first of them, so it is read 2 ticks late. The phase must stay put.
            line = goldorLine();
            ticks(30);
            hit("SELF");
            r0 = relocks();
            long hitPhase = clock();
            for (int k = 1; k <= 4; k++) {
                long target = hitPhase + 60L * k;
                int i = 0;
                while (clock() < target - 1) {
                    tick(i++ % 3 == 0 ? 0 : 1);
                }
                tick(3); // target, target+1, target+2
                hit("SELF");
            }
            while (Math.floorMod(clock() - hitPhase, 60L) != 0) {
                tick(1);
            }
            c.eq("(d) on Goldor's real tick after the bunched hits the Tick reads 0", 0, remaining());
            c.eq("(d) no re-lock after the first hit", 0, relocks() - r0);
            List<Integer> off = offsets();
            List<Integer> later = off.subList(Math.max(0, off.size() - 4), off.size());
            c.check("(d) the four bunched hits read 2 ticks after the phase", later.size() == 4
                    && later.stream().allMatch(o -> o == 2), off.toString());
        } finally {
            Mod.staticCall(CLOCK, "hypixelForTest", (Object) null);
            Mod.staticCall(CLOCK, "untrustForTest");
            Mod.staticCall(TT, "resetAll");
            R.set("secrets.DungeonState", "simOverrideActive", simWas);
            Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas);
        }
    }

    // ---- 1104-logic-split-lag --------------------------------------------------------------------------------------

    private static long lag() {
        return (Long) Mod.staticCall(LAG, "accumulatedLagMs");
    }

    /** Waits (on this thread) until wall time {@code atMs}. */
    private static void waitUntil(long atMs) throws InterruptedException {
        long d;
        while ((d = atMs - System.currentTimeMillis()) > 0) {
            Thread.sleep(Math.min(d, 5L));
        }
    }

    /** {@code n} client ticks 50 ms apart from {@code startMs}, each carrying the given pings (cycled). Returns the end. */
    private static long paced(long startMs, int n, int[] pings) throws InterruptedException {
        long t = startMs;
        for (int i = 0; i < n; i++) {
            t += 50L;
            waitUntil(t);
            tick(pings[i % pings.length]);
        }
        return t;
    }

    static void splitLag(LogicCase c) throws Exception {
        try {
            Mod.staticCall(CLOCK, "hypixelForTest", Boolean.TRUE);
            Mod.staticCall(CLOCK, "untrustForTest");
            ticks(40);
            if (!c.check("trusted after two good windows", (Boolean) Mod.staticCall(CLOCK, "trusted"), "not trusted")) {
                return;
            }
            // Settle on the wall clock first (the 40 hand ticks above took no time at all).
            long t = paced(System.currentTimeMillis(), 20, new int[]{1});
            long l0 = lag();
            t = paced(t, 60, new int[]{1});
            long steady = lag() - l0;
            c.check("steady 20 TPS for 3 s: under 150 ms of lag", steady < 150L, steady + " ms");

            // Jitter: pings arriving in twos and threes and late, but at 20 a second on average.
            long l1 = lag();
            t = paced(t, 60, new int[]{0, 2, 1, 0, 0, 3, 1, 1, 0, 2});
            long jitter = lag() - l1;
            c.check("jitter at 20 TPS on average: under 150 ms of lag", jitter < 150L, jitter + " ms");

            // A one-second stall: the server sends nothing, then catches up with 20 pings at once, then runs on.
            long l2 = lag();
            t += 1000L;
            waitUntil(t);
            long during = lag() - l2;
            c.check("during the stall the lag shows live (about 950 ms)", during >= 850L && during <= 1200L, during + " ms");
            tick(21);
            t = paced(t, 40, new int[]{1});
            long after = lag() - l2;
            c.check("the stall still reads about a second after the catch-up burst (it read ~0 before 2026-11-04)",
                    after >= 850L && after <= 1300L, after + " ms");
            long l3 = lag();
            paced(t, 40, new int[]{1});
            long tail = lag() - l3;
            c.check("steady again after it: no more lag", tail < 150L, tail + " ms");
        } finally {
            Mod.staticCall(CLOCK, "hypixelForTest", (Object) null);
            Mod.staticCall(CLOCK, "untrustForTest");
        }
    }
}

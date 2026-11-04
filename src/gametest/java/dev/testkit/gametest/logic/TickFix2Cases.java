package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

/**
 * Mod branch tickfix2-1104 (follow-ups of tickfix-1104).
 *
 * <ul>
 *   <li>1104-logic-stall-burst: {@code PerTickServerClock.stallTicksSince} on a trusted clock reads 0 through a steady
 *       server and through arrival jitter, about 20 through a 20-tick stall, and still about 20 after the server's
 *       catch-up burst (before tickfix2-1104 the burst paid it back to 0); {@code deficitTicksSince} is the net that the
 *       burst does pay back.</li>
 *   <li>1104-logic-serverlag-grim: {@code util.ServerLag} measures nothing from GrimAC-shaped transaction pings (15+ a
 *       second, gaps of 100-150 ms, never one on 19 of 20 ticks) even while {@code ServerTickClock} calls the server
 *       ping-driven; it measures a one-second stall on a per-tick server; and off Hypixel it still measures a 2.5 s stall
 *       during which the per-tick clock dropped its trust (sim case 1460's shape).</li>
 * </ul>
 */
final class TickFix2Cases {

    private static final String CLOCK = "util.PerTickServerClock";
    private static final String STC = "witherdragons.ServerTickClock";
    private static final String LAG = "util.ServerLag";

    private TickFix2Cases() {
    }

    private static void tick(int pings) {
        for (int i = 0; i < pings; i++) {
            Mod.staticCall(CLOCK, "pingForTest");
        }
        Mod.staticCall(CLOCK, "tickForTest");
    }

    private static long stall(long[] mark) {
        return (Long) Mod.staticCall(CLOCK, "stallTicksSince", (Object) mark);
    }

    private static long deficit(long[] mark) {
        return (Long) Mod.staticCall(CLOCK, "deficitTicksSince", (Object) mark);
    }

    // ---- 1104-logic-stall-burst ------------------------------------------------------------------------------------

    static void stallBurst(LogicCase c) {
        try {
            Mod.staticCall(CLOCK, "hypixelForTest", Boolean.TRUE);
            Mod.staticCall(CLOCK, "untrustForTest");
            for (int i = 0; i < 40; i++) {
                tick(1);
            }
            if (!c.check("trusted after two good windows", (Boolean) Mod.staticCall(CLOCK, "trusted"), "not trusted")) {
                return;
            }
            long[] mark = (long[]) Mod.staticCall(CLOCK, "mark");
            for (int i = 0; i < 40; i++) {
                tick(1);
            }
            c.eq("steady server: no stall", 0L, stall(mark));
            int[] jitter = {0, 2, 1, 0, 0, 3, 1, 1, 0, 2};
            for (int i = 0; i < 60; i++) {
                tick(jitter[i % jitter.length]);
            }
            c.eq("arrival jitter (late pings landing in twos and threes): no stall", 0L, stall(mark));

            long[] before = (long[]) Mod.staticCall(CLOCK, "mark");
            for (int i = 0; i < 20; i++) {
                tick(0);
            }
            long during = stall(before);
            c.check("a 20-tick stall reads 20 ticks", during == 20L, during + " ticks");
            c.eq("the net deficit reads 20 too", 20L, deficit(before));
            tick(21);
            for (int i = 0; i < 40; i++) {
                tick(1);
            }
            long after = stall(before);
            c.check("the stall still reads 20 ticks after the catch-up burst (read 0 before tickfix2-1104)", after == 20L,
                    after + " ticks");
            c.eq("the net deficit is paid back by the burst", 0L, deficit(before));
            c.eq("and the whole span since the first mark holds just that stall", 20L, stall(mark));
        } finally {
            Mod.staticCall(CLOCK, "hypixelForTest", (Object) null);
            Mod.staticCall(CLOCK, "untrustForTest");
        }
    }

    // ---- 1104-logic-serverlag-grim ---------------------------------------------------------------------------------

    private static long lagMs() {
        return (Long) Mod.staticCall(LAG, "lagMs");
    }

    private static int nextId = 1;

    /** One ping through the real entry point (ServerTickClock -> PerTickServerClock + ServerLag), {@code grim} ids
     *  shaped as GrimAC's ({@code -(counter & 0x7FFF)}). */
    private static void ping(boolean grim) {
        int id = nextId++;
        Mod.staticCall(STC, "onPing", grim ? -id : id);
    }

    /** {@code n} client ticks 50 ms apart from {@code startMs}, each with the given pings (cycled). Returns the end. */
    private static long paced(long startMs, int n, int[] pings, boolean grim) throws InterruptedException {
        long t = startMs;
        for (int i = 0; i < n; i++) {
            t += 50L;
            LogicCase.waitUntil(t);
            for (int p = 0; p < pings[i % pings.length]; p++) {
                ping(grim);
            }
            Mod.staticCall(CLOCK, "tickForTest");
        }
        return t;
    }

    static void serverLagGrim(LogicCase c) throws Exception {
        Object wasDriven = R.get(STC, "pingDriven");
        try {
            // GrimAC: ServerTickClock's 15-a-second rule calls it ping-driven (the old ServerLag gate), but the pings
            // come on about 16 of 20 ticks, two at a time now and then, so the per-tick clock never trusts it.
            R.set(STC, "pingDriven", Boolean.TRUE);
            Mod.staticCall(CLOCK, "hypixelForTest", Boolean.FALSE);
            Mod.staticCall(CLOCK, "untrustForTest");
            long t = paced(System.currentTimeMillis(), 2, new int[]{1}, true);
            long l0 = lagMs();
            t = paced(t, 80, new int[]{1, 1, 0, 2, 1, 0, 1, 1, 1, 0, 2, 1, 0, 1, 1, 1, 0, 1, 2, 1}, true);
            long grim = lagMs() - l0;
            c.check("GrimAC-shaped pings: the per-tick clock never trusts them",
                    !(Boolean) Mod.staticCall(CLOCK, "trusted"), "trusted");
            c.check("GrimAC-shaped pings: ServerLag measures no stall (each 100-150 ms gap read as one before)",
                    grim == 0L, grim + " ms");
            c.check("GrimAC-shaped pings: ServerLag is not measuring", !(Boolean) Mod.staticCall(LAG, "measuring"), "measuring");

            // A per-tick server (Hypixel): a one-second stall, then the catch-up burst.
            Mod.staticCall(CLOCK, "hypixelForTest", Boolean.TRUE);
            Mod.staticCall(CLOCK, "untrustForTest");
            t = paced(t, 45, new int[]{1}, false);
            c.check("per-tick server: trusted", (Boolean) Mod.staticCall(CLOCK, "trusted"), "not trusted");
            c.check("per-tick server: ServerLag measuring", (Boolean) Mod.staticCall(LAG, "measuring"), "not measuring");
            long l1 = lagMs();
            t = paced(t, 20, new int[]{1}, false);
            long steady = lagMs() - l1;
            c.check("per-tick server, steady: under 100 ms of lag", steady < 100L, steady + " ms");
            long l2 = lagMs();
            t = paced(t, 20, new int[]{0}, false);
            long live = lagMs() - l2;
            c.check("per-tick server, 1 s stall: shows live", live >= 850L && live <= 1200L, live + " ms");
            for (int i = 0; i < 20; i++) {
                ping(false);
            }
            t = paced(t, 20, new int[]{1}, false);
            long stall = lagMs() - l2;
            c.check("per-tick server, 1 s stall: measured about 950 ms", stall >= 850L && stall <= 1250L, stall + " ms");

            // Off Hypixel (the sim) a stall past two windows drops the per-tick clock's trust before it ends; the gap was
            // opened by a trusted ping, so it still counts.
            Mod.staticCall(CLOCK, "hypixelForTest", Boolean.FALSE);
            Mod.staticCall(CLOCK, "untrustForTest");
            t = paced(t, 45, new int[]{1}, false);
            c.check("sim-like server: trusted", (Boolean) Mod.staticCall(CLOCK, "trusted"), "not trusted");
            long l3 = lagMs();
            t = paced(t, 50, new int[]{0}, false);
            c.check("sim-like server: a 2.5 s stall drops the per-tick clock's trust",
                    !(Boolean) Mod.staticCall(CLOCK, "trusted"), "still trusted");
            ping(false);
            long simStall = lagMs() - l3;
            c.check("sim-like server: the 2.5 s stall is still measured", simStall >= 2300L && simStall <= 2700L,
                    simStall + " ms");
        } finally {
            R.set(STC, "pingDriven", wasDriven);
            Mod.staticCall(CLOCK, "hypixelForTest", (Object) null);
            Mod.staticCall(CLOCK, "untrustForTest");
        }
    }
}

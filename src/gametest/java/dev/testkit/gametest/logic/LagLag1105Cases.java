package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 1105-logic-split-jitter and 1105-logic-split-slow (mod branch laglag-1105; killer560, 2026-11-05, ship 8: "It does feel
 * like the lag timer is going up while the server isnt lagging sometimes though ... these tiny 1-2 tick adjustments").
 *
 * <p>His ship 8 Goldor phase: Split Timers counted 6452 ms of "[SplitLag] stall" lines (33 of them, most with a ping gap
 * of 90-250 ms) while the server's own ticks said 1545 ms (2100 ticks in 106545 ms, held to its game time). The old rule
 * counted the deficit's rise over its all-time low at 150 ms, and the low then fell back with the jitter, so each late
 * ping was counted again. A hypixel.net stream on the test's own wall clock (PerTickServerClock.wallForTest): one chain
 * ping per server tick, 10% extra chain pings (his census 1.069 chain pings per 50 ms), the time packet every 20 ticks,
 * 30-35 ms of network delay with one ping in 40 delayed a further 60-100 ms, delivered in order (a late ping holds the
 * ones behind it, which then land together). Three seeds each.
 * <ul>
 * <li>jitter: a true 20 TPS server for 120 s reads under 150 ms of lag (the old jar read 330-700 ms in a sim of it); then
 * a 1 s stall the server catches up at once reads 900-1200 ms, and still does after the burst and 20 s more.</li>
 * <li>slow: a true 19.8 TPS server for 120 s (1200 ms behind the wall clock) reads 1000-1400 ms (the old jar ~2400).</li>
 * </ul>
 * Both: the lag never goes down and never climbs faster than the wall clock (the lagless time only climbs), and the clock
 * really counted the server's ticks (it acted).
 */
final class LagLag1105Cases {

    private static final String CLOCK = "util.PerTickServerClock";
    private static final String LAG = "splittimers.SplitLagClock";
    private static final long BASE = 20_000_000L;

    private LagLag1105Cases() {
    }

    /** One packet of the stream: arrival time (ms), a chain ping (true) or a time packet with this game time. */
    private record Packet(long atMs, boolean ping, long gameTime) {
    }

    /** {@code secs} of a server at {@code tps}; a stall of {@code stallMs} after server tick {@code stallTick} (0 = none),
     *  caught up at once (the next ticks are sent the moment it ends, as vanilla's catch-up does). */
    private static List<Packet> stream(double tps, int secs, long seed, int stallTick, long stallMs) {
        Random rnd = new Random(seed);
        double period = 1000.0 / tps;
        int n = (int) (secs * tps);
        List<double[]> sends = new ArrayList<>(); // {send ms, 1 = ping / 0 = time, game time}
        for (int k = 1; k <= n; k++) {
            double s = k * period;
            if (stallTick > 0 && k > stallTick) {
                s = Math.max(s, stallTick * period + stallMs); // the ticks due during the stall go out in one burst
            }
            sends.add(new double[]{s, 1, k});
            if (rnd.nextDouble() < 0.10) {
                sends.add(new double[]{s + 1, 1, k});
            }
            if (k % 20 == 0) {
                sends.add(new double[]{s + 2, 0, k});
            }
        }
        sends.sort((a, b) -> Double.compare(a[0], b[0]));
        List<Packet> out = new ArrayList<>();
        double arrive = 0.0;
        int i = 0;
        for (double[] s : sends) {
            double delay = 30.0 + rnd.nextDouble() * 5.0;
            if (++i % 40 == 0) {
                delay += 60.0 + rnd.nextDouble() * 40.0;
            }
            arrive = Math.max(arrive, s[0] + delay); // in order: a late packet holds the ones behind it
            out.add(new Packet(BASE + (long) arrive, s[1] == 1, (long) s[2]));
        }
        return out;
    }

    /** Result of feeding a stream: lag over the span from {@code fromMs}, the clock's ticks, and the monotonic checks. */
    private record Fed(long lag, long ticks, boolean wentDown, boolean tooFast, String detail) {
    }

    private static long lag() {
        return (Long) Mod.staticCall(LAG, "accumulatedLagMs");
    }

    /** Feeds {@code packets} whose arrival is at or after {@code fromMs} and before {@code toMs}. */
    private static Fed feed(List<Packet> packets, long fromMs, long toMs) {
        Mod.staticCall(CLOCK, "wallForTest", fromMs);
        long l0 = lag();
        long c0 = (Long) Mod.staticCall(CLOCK, "now");
        long prevLag = l0;
        long prevAt = fromMs;
        boolean down = false;
        boolean fast = false;
        String detail = "";
        for (Packet p : packets) {
            if (p.atMs < fromMs || p.atMs >= toMs) {
                continue;
            }
            Mod.staticCall(CLOCK, "wallForTest", p.atMs);
            if (p.ping) {
                Mod.staticCall(CLOCK, "pingForTestTimed");
            } else {
                Mod.staticCall(CLOCK, "serverTimeForTest", p.gameTime);
            }
            long l = lag();
            if (l < prevLag && !down) {
                down = true;
                detail += " down " + prevLag + "->" + l + " at +" + (p.atMs - BASE);
            }
            if (l - prevLag > p.atMs - prevAt && !fast) {
                fast = true;
                detail += " +" + (l - prevLag) + " ms in " + (p.atMs - prevAt) + " ms at +" + (p.atMs - BASE);
            }
            prevLag = l;
            prevAt = p.atMs;
        }
        Mod.staticCall(CLOCK, "wallForTest", toMs);
        long end = lag();
        return new Fed(end - l0, (Long) Mod.staticCall(CLOCK, "now") - c0, down, fast, detail);
    }

    private static void start() {
        Mod.staticCall(CLOCK, "hypixelForTest", Boolean.TRUE);
        Mod.staticCall(CLOCK, "untrustForTest");
        Mod.staticCall(LAG, "resetForTest");
    }

    private static void end() {
        Mod.staticCall(CLOCK, "hypixelForTest", (Object) null);
        Mod.staticCall(CLOCK, "untrustForTest");
        Mod.staticCall(LAG, "resetForTest");
    }

    static void jitter(LogicCase c) {
        try {
            for (long seed = 0; seed < 3; seed++) {
                start();
                List<Packet> s = stream(20.0, 160, seed, 2800, 1000L);
                feed(s, BASE, BASE + 2000L); // settle: the first ping, the first time packet
                c.check("seed " + seed + ": Split Timers' lag is trustworthy on the stream",
                        (Boolean) Mod.staticCall(LAG, "isTrustworthy"), "hidden");
                Fed f = feed(s, BASE + 2000L, BASE + 122_000L);
                c.check("seed " + seed + ": the clock counted the server's ticks over 120 s (2400, within 5)",
                        Math.abs(f.ticks - 2400L) <= 5L, f.ticks + " ticks");
                c.check("seed " + seed + ": a true 20 TPS server with late pings reads under 150 ms of lag in 120 s",
                        f.lag < 150L, f.lag + " ms");
                c.check("seed " + seed + ": the lag never went down and never climbed faster than the wall clock",
                        !f.wentDown && !f.tooFast, f.detail.isEmpty() ? "ok" : f.detail);
                Fed st = feed(s, BASE + 122_000L, BASE + 152_000L + 1000L);
                c.check("seed " + seed + ": a 1 s stall caught up at once still reads 900-1200 ms 20 s after its burst",
                        st.lag >= 900L && st.lag <= 1200L, st.lag + " ms");
                c.check("seed " + seed + ": the stall span never went down or climbed faster than the wall clock",
                        !st.wentDown && !st.tooFast, st.detail.isEmpty() ? "ok" : st.detail);
                end();
            }
        } finally {
            end();
        }
    }

    static void slow(LogicCase c) {
        try {
            for (long seed = 0; seed < 3; seed++) {
                start();
                List<Packet> s = stream(19.8, 124, seed, 0, 0L);
                feed(s, BASE, BASE + 2000L);
                Fed f = feed(s, BASE + 2000L, BASE + 122_000L);
                c.check("seed " + seed + ": the clock counted the server's ticks over 120 s (2376, within 5)",
                        Math.abs(f.ticks - 2376L) <= 5L, f.ticks + " ticks");
                c.check("seed " + seed + ": a true 19.8 TPS server (1200 ms behind in 120 s) reads 1000-1400 ms of lag",
                        f.lag >= 1000L && f.lag <= 1400L, f.lag + " ms");
                c.check("seed " + seed + ": the lag never went down and never climbed faster than the wall clock",
                        !f.wentDown && !f.tooFast, f.detail.isEmpty() ? "ok" : f.detail);
                end();
            }
        } finally {
            end();
        }
    }
}

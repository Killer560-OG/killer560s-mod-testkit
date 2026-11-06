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

    // ---- 1105-logic-bcstall-jitter / -stall (mod branch bcstall-1105) ----------------------------------------------

    /**
     * Feeds the packets in [fromMs, toMs) with the client's own ticks between them, every 50 ms (each 0-8 ms late, as a
     * frame lands), so {@code PerTickServerClock.stallTicksSince} (Blood Camp's camp lag, PingTracker's clean round trip)
     * sees the stream as the game does. Before bcstall-1105 it counted the client-tick deficit's rise over its all-time
     * low, the same ratchet laglag-1105 took out of Split Timers. Returns the clock's ticks over the span.
     */
    private static long feedWithTicks(List<Packet> packets, long fromMs, long toMs, Random rnd) {
        long c0 = (Long) Mod.staticCall(CLOCK, "now");
        long nextTick = fromMs + 50L;
        long tickAt = nextTick + rnd.nextInt(9);
        int i = 0;
        while (true) {
            Packet p = null;
            while (i < packets.size() && (packets.get(i).atMs < fromMs)) i++;
            if (i < packets.size() && packets.get(i).atMs < toMs) p = packets.get(i);
            if (p != null && p.atMs < tickAt) {
                Mod.staticCall(CLOCK, "wallForTest", p.atMs);
                if (p.ping) {
                    Mod.staticCall(CLOCK, "pingForTestTimed");
                } else {
                    Mod.staticCall(CLOCK, "serverTimeForTest", p.gameTime);
                }
                i++;
                continue;
            }
            if (tickAt >= toMs) break;
            Mod.staticCall(CLOCK, "wallForTest", tickAt - 50L); // tickForTest moves the test clocks 50 ms on
            Mod.staticCall(CLOCK, "tickForTest");
            Mod.staticCall(CLOCK, "wallForTest", tickAt);
            nextTick += 50L;
            tickAt = nextTick + rnd.nextInt(9);
        }
        Mod.staticCall(CLOCK, "wallForTest", toMs);
        return (Long) Mod.staticCall(CLOCK, "now") - c0;
    }

    private static long stallTicks(long[] mark) {
        return (Long) Mod.staticCall(CLOCK, "stallTicksSince", (Object) mark);
    }

    /** A true 20 TPS server with late pings: under 3 stall ticks over 120 s (Blood Camp's camp lag stays near 0). */
    static void bcJitter(LogicCase c) {
        try {
            for (long seed = 0; seed < 3; seed++) {
                start();
                Random rnd = new Random(100 + seed);
                List<Packet> s = stream(20.0, 125, seed, 0, 0L);
                feedWithTicks(s, BASE, BASE + 2000L, rnd);
                c.check("seed " + seed + ": the clock is trusted on the stream", (Boolean) Mod.staticCall(CLOCK, "trusted"),
                        "not trusted");
                long[] mark = (long[]) Mod.staticCall(CLOCK, "mark");
                long ticks = feedWithTicks(s, BASE + 2000L, BASE + 122_000L, rnd);
                c.check("seed " + seed + ": the clock counted the server's ticks over 120 s (2400, within 5)",
                        Math.abs(ticks - 2400L) <= 5L, ticks + " ticks");
                long st = stallTicks(mark);
                System.out.println("[bcstall-jitter] seed " + seed + ": " + st + " stall ticks, " + ticks + " clock ticks");
                c.check("seed " + seed + ": a true 20 TPS server with late pings reads under 3 stall ticks in 120 s",
                        st < 3L, st + " ticks");
                end();
            }
        } finally {
            end();
        }
    }

    /** A real 1 s stall the server catches up at once, with the same late pings around it: about 14 stall ticks 0.7 s in
     *  (live), and 18-23 for the whole 70 s span, so it is not paid back by its burst and the jitter adds nothing (the old
     *  ratchet added its jitter count, ~5 ticks a minute, on top). */
    static void bcStall(LogicCase c) {
        try {
            for (long seed = 0; seed < 3; seed++) {
                start();
                Random rnd = new Random(200 + seed);
                List<Packet> s = stream(20.0, 95, seed, 600, 1000L); // the stall starts 30 s in
                feedWithTicks(s, BASE, BASE + 20_000L, rnd);
                long[] mark = (long[]) Mod.staticCall(CLOCK, "mark");
                feedWithTicks(s, BASE + 20_000L, BASE + 30_000L + 800L, rnd); // 0.7 s into the stall
                long during = stallTicks(mark);
                System.out.println("[bcstall-stall] seed " + seed + ": " + during + " stall ticks 0.7 s in");
                c.check("seed " + seed + ": 0.7 s into a 1 s stall reads 10-16 stall ticks (counted live)",
                        during >= 10L && during <= 16L, during + " ticks");
                long ticks = feedWithTicks(s, BASE + 30_000L + 800L, BASE + 90_000L, rnd);
                c.check("seed " + seed + ": the clock ran again after the stall (its burst and 59 s: ~1180 ticks)",
                        ticks >= 1170L, ticks + " ticks");
                long after = stallTicks(mark);
                System.out.println("[bcstall-stall] seed " + seed + ": " + after + " stall ticks over 70 s, " + ticks + " clock ticks after");
                c.check("seed " + seed + ": the 1 s stall reads 18-23 stall ticks over 70 s with jitter, its catch-up burst 59 s back",
                        after >= 18L && after <= 23L, after + " ticks");
                end();
            }
        } finally {
            end();
        }
    }
}

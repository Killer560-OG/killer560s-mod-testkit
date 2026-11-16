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
        return stream(tps, secs, seed, stallTick, stallMs, true);
    }

    /** As above; {@code catchUp} false is Hypixel's way: the server just carries on after the stall, its game time
     *  staying behind the wall clock by the stall. */
    private static List<Packet> stream(double tps, int secs, long seed, int stallTick, long stallMs, boolean catchUp) {
        Random rnd = new Random(seed);
        double period = 1000.0 / tps;
        int n = (int) (secs * tps);
        List<double[]> sends = new ArrayList<>(); // {send ms, 1 = ping / 0 = time, game time}
        for (int k = 1; k <= n; k++) {
            double s = k * period;
            if (stallTick > 0 && k > stallTick) {
                // caught up: the ticks due during the stall go out in one burst; not: every later tick is late by the stall
                s = catchUp ? Math.max(s, stallTick * period + stallMs) : s + stallMs;
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
                // splitlag-1116: the burst pays it back (the server ran those ticks); 1105 to 1116 read 900-1200 ms here
                c.check("seed " + seed + ": a 1 s stall caught up at once reads under 150 ms 20 s after its burst",
                        st.lag < 150L, st.lag + " ms");
                c.check("seed " + seed + ": the stall span never climbed faster than the wall clock",
                        !st.tooFast, st.detail.isEmpty() ? "ok" : st.detail);
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

    /** A real 1 s stall with the same late pings around it: about 14 stall ticks 0.7 s in (live); over the whole 70 s
     *  span 18-23 when the server never catches up (Hypixel's way) and at most 3 when it does at once (the burst pays it
     *  back, splitlag-1116; 1105 to 1116 read 18-23 for both), so the jitter adds nothing (the old ratchet added its jitter
     *  count, ~5 ticks a minute, on top). */
    static void bcStall(LogicCase c) {
        try {
            for (long run = 0; run < 6; run++) {
                long seed = run % 3;
                boolean catchUp = run >= 3;
                start();
                Random rnd = new Random(200 + seed);
                List<Packet> s = stream(20.0, 95, seed, 600, 1000L, catchUp); // the stall starts 30 s in
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
                System.out.println("[bcstall-stall] seed " + seed + (catchUp ? " caught up" : " not caught up") + ": " + after
                        + " stall ticks over 70 s, " + ticks + " clock ticks after");
                if (catchUp) {
                    c.check("seed " + seed + ": a 1 s stall caught up at once reads at most 3 stall ticks over 70 s with jitter",
                            after <= 3L, after + " ticks");
                } else {
                    c.check("seed " + seed + ": a 1 s stall never caught up reads 18-23 stall ticks over 70 s with jitter",
                            after >= 18L && after <= 23L, after + " ticks");
                }
                end();
            }
        } finally {
            end();
        }
    }

    // ---- 1116-logic-splitlag-* (mod branch splitlag-1116; killer560, 2026-11-16: "it also counts lag going up if I
    // myself am lagging; it should only count lag as the server lag, not my client-side lag") ---------------------------

    /** The stream as a client handles it when nothing is handled in [fromMs, toMs) (a frozen client, or a connection
     *  holding its inbound packets): every packet that arrived meanwhile is handled at {@code toMs}, in order. */
    private static List<Packet> heldUntil(List<Packet> s, long fromMs, long toMs) {
        List<Packet> out = new ArrayList<>(s.size());
        for (Packet p : s) {
            out.add(p.atMs >= fromMs && p.atMs < toMs ? new Packet(toMs, p.ping, p.gameTime) : p);
        }
        return out;
    }

    private static int stallsSeen() {
        return (Integer) Mod.staticCall("util.StallLag", "stallsSeen");
    }

    private static long paidBack() {
        return (Long) Mod.staticCall("util.StallLag", "paidBackMs");
    }

    /** 1116-logic-splitlag-server-stall: a real 1.5 s server stall (Hypixel's way, never caught up) still reads ~1.5 s. */
    static void splitServerStall(LogicCase c) {
        try {
            for (long seed = 0; seed < 3; seed++) {
                start();
                List<Packet> s = stream(20.0, 62, seed, 600, 1500L, false);
                feed(s, BASE, BASE + 20_000L);
                Fed f = feed(s, BASE + 20_000L, BASE + 61_500L);
                c.check("seed " + seed + ": a 1.5 s server stall reads 1350-1700 ms of lag", f.lag >= 1350L && f.lag <= 1700L,
                        f.lag + " ms");
                c.check("seed " + seed + ": the feature saw the stall", stallsSeen() >= 1, stallsSeen() + " stalls");
                c.check("seed " + seed + ": the clock ran on after it (~800 ticks)", f.ticks >= 780L, f.ticks + " ticks");
                end();
            }
        } finally {
            end();
        }
    }

    /** 1116-logic-splitlag-client-freeze: the client freezes 1.5 s (no client ticks, nothing handled) while the server ticks
     *  on: the queued pings and time packets are handled at once when it wakes, which pays the gap back: ~0 lag. */
    static void splitClientFreeze(LogicCase c) {
        try {
            for (long seed = 0; seed < 3; seed++) {
                start();
                List<Packet> s = heldUntil(stream(20.0, 62, seed, 0, 0L), BASE + 30_000L, BASE + 31_500L);
                feed(s, BASE, BASE + 20_000L);
                Fed f = feed(s, BASE + 20_000L, BASE + 60_000L);
                c.check("seed " + seed + ": a 1.5 s client freeze on a 20 TPS server reads under 150 ms of lag", f.lag < 150L,
                        f.lag + " ms");
                c.check("seed " + seed + ": the feature saw the gap as a stall", stallsSeen() >= 1, stallsSeen() + " stalls");
                c.check("seed " + seed + ": and the queued ticks paid it back (1200+ ms)", paidBack() >= 1200L,
                        paidBack() + " ms");
                c.check("seed " + seed + ": the clock counted every server tick (800, within 5)", Math.abs(f.ticks - 800L) <= 5L,
                        f.ticks + " ticks");
                end();
            }
        } finally {
            end();
        }
    }

    /** 1116-logic-splitlag-conn-stall: the connection holds inbound packets 1.5 s and then releases them in one burst while
     *  the client keeps ticking: ~0 lag on Split Timers and on the stall ticks (Blood Camp, clean round trips). */
    static void splitConnStall(LogicCase c) {
        try {
            for (long seed = 0; seed < 3; seed++) {
                start();
                Random rnd = new Random(300 + seed);
                List<Packet> s = heldUntil(stream(20.0, 62, seed, 0, 0L), BASE + 30_000L, BASE + 31_500L);
                feedWithTicks(s, BASE, BASE + 20_000L, rnd);
                long l0 = lag();
                long[] mark = (long[]) Mod.staticCall(CLOCK, "mark");
                long ticks = feedWithTicks(s, BASE + 20_000L, BASE + 60_000L, rnd);
                long l = lag() - l0;
                long st = stallTicks(mark);
                c.check("seed " + seed + ": a 1.5 s connection stall on a 20 TPS server reads under 150 ms of lag", l < 150L,
                        l + " ms");
                c.check("seed " + seed + ": and under 3 stall ticks", st < 3L, st + " ticks");
                c.check("seed " + seed + ": the feature saw the gap as a stall", stallsSeen() >= 1, stallsSeen() + " stalls");
                c.check("seed " + seed + ": and the released ticks paid it back (1200+ ms)", paidBack() >= 1200L,
                        paidBack() + " ms");
                c.check("seed " + seed + ": the clock counted every server tick (800, within 5)", Math.abs(ticks - 800L) <= 5L,
                        ticks + " ticks");
                end();
            }
        } finally {
            end();
        }
    }

    /** 1116-logic-splitlag-freeze-in-stall: a 1 s server stall (never caught up) inside a 1.5 s client freeze reads only
     *  the server's 1 s: the ticks the server did run in the frozen time pay back the rest. */
    static void splitFreezeInStall(LogicCase c) {
        try {
            for (long seed = 0; seed < 3; seed++) {
                start();
                List<Packet> s = heldUntil(stream(20.0, 62, seed, 600, 1000L, false), BASE + 29_800L, BASE + 31_300L);
                feed(s, BASE, BASE + 20_000L);
                Fed f = feed(s, BASE + 20_000L, BASE + 61_000L);
                c.check("seed " + seed + ": a 1 s server stall inside a 1.5 s client freeze reads 850-1200 ms of lag",
                        f.lag >= 850L && f.lag <= 1200L, f.lag + " ms");
                c.check("seed " + seed + ": the feature saw the gap as a stall", stallsSeen() >= 1, stallsSeen() + " stalls");
                c.check("seed " + seed + ": the server's ticks in the frozen time paid back some (300+ ms)", paidBack() >= 300L,
                        paidBack() + " ms");
                end();
            }
        } finally {
            end();
        }
    }
}

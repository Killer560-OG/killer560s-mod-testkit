package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import net.minecraft.network.chat.Component;

/**
 * 1104-logic-hypixel-pings (mod branch tickfix3-1104; killer560, 2026-11-04: "the timers were also off for storm pads by a
 * bit" and "split timers no longer show lag at all"). Real Hypixel's pings, as his 2026-11-04 log shows them: one a tick
 * whose id steps down by one (-642, -643, -644, ...) and, between them, pings with random ids (first six of one world:
 * [-642, -643, -26967, -380, -9038, -644]), about 1.13 non-zero pings per 50 ms over a 48 s Goldor phase. goldfix-1104
 * read the random ids as "pings ahead of the wall clock" and untrusted Hypixel for the whole world, so Split Timers hid
 * their lag and every timer ran on client ticks; counting every non-zero ping (Odin's rule) would run them 13% fast.
 *
 * <p>Every ping goes in through the mod's real entry, {@code witherdragons.ServerTickClock.onPing(id)} (what the ping
 * mixin calls), on the real wall clock. Checks: the clock is trusted from the FIRST ping after a join; over 200 ticks with
 * 13% random-id extras it counts exactly the 200 per-tick pings (the extras were sent: the raw count shows them), Goldor's
 * "Tick:" and Storm's "Lightning:" moved 200 ticks (within 1); and Split Timers' lag, at wall pace from a fresh join, is
 * trustworthy and reads a 500 ms stall, Lag Display's TPS and !tps show a number, and ServerLag measures the stall.
 */
final class TickFix3Cases {

    private static final String CLOCK = "util.PerTickServerClock";
    private static final String PINGS = "witherdragons.ServerTickClock";
    private static final String TT = "ticktimers.TickTimersFeature";
    private static final String GT = "ticktimers.GoldorTick";
    private static final String SL = "ticktimers.StormLine";
    private static final String LAG = "splittimers.SplitLagClock";

    private TickFix3Cases() {
    }

    /** A Hypixel-shaped stream: its per-tick chain (ids stepping down by one) and seeded random ids beside it. */
    private static final class Stream {
        private int chain = -642;
        private final java.util.Random random = new java.util.Random(1104L);
        private int tick;
        int sent;

        /** One client tick: the chain's ping, a random-id extra on about 13% of ticks (two on some), then the tick. */
        void tick(boolean chainPing) {
            tick++;
            if (chainPing) {
                ping(chain--);
            }
            if (tick % 8 == 3 || tick % 40 == 17) {
                ping(-1 - random.nextInt(32766));
            }
            Mod.staticCall(CLOCK, "tickForTest");
            Mod.staticCall(GT, "endOfTick");
            Mod.staticCall(SL, "endOfTick");
        }

        void ping(int id) {
            Mod.staticCall(PINGS, "onPing", id);
            sent++;
        }
    }

    static void hypixelPings(LogicCase c) throws Exception {
        Object simWas = R.get("secrets.DungeonState", "simOverrideActive");
        boolean gateWas = (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled");
        Mod.staticCall("util.SkyblockGate", "setEnabled", false);
        R.set("secrets.DungeonState", "simOverrideActive", true);
        try (AutoCloseable on = Mod.with("ticktimers.TickTimersConfig", "Enabled", true)) {
            Mod.staticCall(CLOCK, "hypixelForTest", Boolean.TRUE);
            Mod.staticCall(CLOCK, "untrustForTest");
            Stream s = new Stream();
            // His log's first six ids of a world: chain, chain, three random, chain.
            s.ping(s.chain--);
            c.check("hypixel.net: trusted at the first ping after a join (two 20-tick windows before tickfix3)",
                    (Boolean) Mod.staticCall(CLOCK, "trusted"), "not trusted");
            s.ping(s.chain--);
            s.ping(-26967);
            s.ping(-380);
            s.ping(-9038);
            s.ping(s.chain--);
            Mod.staticCall(CLOCK, "tickForTest");

            Mod.staticCall(TT, "resetAll");
            Mod.staticCall(TT, "onChatMessage", Component.literal("[BOSS] Storm: Pathetic Maxor, just like expected."));
            Mod.staticCall(TT, "onChatMessage", Component.literal("[BOSS] Goldor: Who dares trespass into my domain?"));
            s.tick(true);
            long clock0 = (Long) Mod.staticCall(CLOCK, "now");
            long raw0 = (Long) Mod.staticCall(CLOCK, "rawPings");
            int sent0 = s.sent;
            int goldor0 = (Integer) Mod.staticCall(GT, "remaining");
            int storm0 = (Integer) Mod.staticCall(SL, "remaining");
            boolean untrusted = false;
            for (int i = 0; i < 200; i++) {
                s.tick(true);
                untrusted |= !(Boolean) Mod.staticCall(CLOCK, "trusted");
            }
            long ran = (Long) Mod.staticCall(CLOCK, "now") - clock0;
            long raw = (Long) Mod.staticCall(CLOCK, "rawPings") - raw0;
            int extras = s.sent - sent0 - 200;
            c.check("the stream really carried random-id extras (about 13%)", raw == s.sent - sent0 && extras >= 25,
                    "raw " + raw + ", sent " + (s.sent - sent0) + ", extras " + extras);
            c.check("trusted through all 200 ticks", !untrusted, "trust was dropped");
            c.check("the clock counted the 200 per-tick pings, not the extras (within 1)", Math.abs(ran - 200) <= 1,
                    "ran " + ran + " for 200 chain + " + extras + " random pings");
            int goldor = (Integer) Mod.staticCall(GT, "remaining");
            long gOff = Math.floorMod((goldor0 - goldor) - 200L, 60L);
            c.check("Goldor's Tick moved 200 server ticks (within 1)", gOff <= 1 || gOff >= 59,
                    goldor0 + " -> " + goldor + " (" + gOff + " off mod 60)");
            int storm = (Integer) Mod.staticCall(SL, "remaining");
            c.check("Storm's Lightning moved 200 server ticks (within 1)", Math.abs((storm0 - storm) - 200) <= 1,
                    storm0 + " -> " + storm);

            // Split Timers, at wall pace from a fresh join: trustworthy at once, and a 500 ms stall reads as lag.
            Mod.staticCall(LAG, "resetForTest");
            Mod.staticCall(CLOCK, "untrustForTest");
            long t = System.currentTimeMillis();
            for (int i = 0; i < 20; i++) {
                t += 50L;
                LogicCase.waitUntil(t);
                s.tick(true);
            }
            c.check("Split Timers' lag is trustworthy after 20 Hypixel ticks", (Boolean) Mod.staticCall(LAG, "isTrustworthy"),
                    "hidden (isTrustworthy false)");
            // The TPS readouts (killer560, 2026-11-04: "my tps counter as a whole isnt working either"): a number, not "-".
            double tps = (Double) Mod.staticCall("lagdisplay.LagDisplayFeature", "tps");
            c.check("Lag Display TPS shows a number on hypixel.net (it read \"-\" while untrusted)", tps > 15.0 && tps <= 20.0,
                    "tps() " + tps);
            String reply = (String) Mod.staticCall("partycommands.InfoReplies", "tpsReply");
            c.check("!tps answers with a number", reply != null && reply.matches("TPS: \\d+\\.\\d"), String.valueOf(reply));
            c.check("ServerLag measures on hypixel.net", (Boolean) Mod.staticCall("util.ServerLag", "measuring"), "not measuring");
            long sl0 = (Long) Mod.staticCall("util.ServerLag", "lagMs");
            long l0 = (Long) Mod.staticCall(LAG, "accumulatedLagMs");
            for (int i = 0; i < 10; i++) {
                t += 50L;
                LogicCase.waitUntil(t);
                s.tick(false); // the server stands still: random ids may come, the chain does not
            }
            for (int i = 0; i < 10; i++) {
                s.ping(s.chain--); // the catch-up burst
            }
            for (int i = 0; i < 10; i++) {
                t += 50L;
                LogicCase.waitUntil(t);
                s.tick(true);
            }
            long lag = (Long) Mod.staticCall(LAG, "accumulatedLagMs") - l0;
            long shown = (Long) Mod.staticCall(LAG, "shownLag", lag);
            long serverLag = (Long) Mod.staticCall("util.ServerLag", "lagMs") - sl0;
            c.check("ServerLag measured the 500 ms stall (random-id pings inside it do not split it)",
                    serverLag >= 350L && serverLag <= 800L, serverLag + " ms");
            c.check("Split Timers show the 500 ms stall as lag", shown >= 350L && shown <= 800L, "lag " + lag + " ms, shown " + shown);
        } finally {
            Mod.staticCall(CLOCK, "hypixelForTest", (Object) null);
            Mod.staticCall(CLOCK, "untrustForTest");
            Mod.staticCall(LAG, "resetForTest");
            Mod.staticCall(TT, "resetAll");
            R.set("secrets.DungeonState", "simOverrideActive", simWas);
            Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas);
        }
    }
}

package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import net.minecraft.network.chat.Component;

import java.util.Map;

/**
 * 1103-logic-timer-lag (mod branch timerlag-1103; killer560, 2026-11-03: "i think the timer issue may be because it
 * doesnt factor lag as well because all timers seem to go off sometimes"). Every countdown that Hypixel runs on SERVER
 * ticks must follow {@code util.PerTickServerClock}: a stalled server (no pings for 20 client ticks) moves none of
 * them, and each reaches its end on the 83rd server tick, not the 83rd client tick. Driven by hand like
 * {@code 1101-logic-bloodcamp-clock}: a per-tick pinger is trusted after two good windows, the 20-tick stall is exactly
 * one failing window (still trusted), and the pings after it are good windows again.
 *
 * <p>Each timer is checked three times on purpose, because a frozen timer would pass "not ahead": it MOVED after the
 * first 20 server ticks, it did NOT move in the stall, and it stands exactly where 63 server ticks put it when 83
 * client ticks have passed (a client-tick counter would already be at its end).
 */
final class TimerLagCases {

    private static final String CLOCK = "util.PerTickServerClock";
    private static final String TT = "ticktimers.TickTimersFeature";
    private static final String PG = "miningqol.PowderGhast";
    private static final String MASK = "maskinvincibility.MaskInvincibilityFeature";
    private static final String DRAGON = "witherdragons.WitherDragon";
    private static final String MAXOR = "maxor.MaxorCrystalsFeature";

    private TimerLagCases() {
    }

    /** One simulated client tick carrying {@code pings} server pings, then the Step-driven timers' own client tick. */
    private static void tick(int pings) {
        for (int i = 0; i < pings; i++) {
            Mod.staticCall(CLOCK, "pingForTest");
        }
        Mod.staticCall(CLOCK, "tickForTest");
        Mod.staticCall(MASK, "tick");
    }

    private static void ticks(int n, int pings) {
        for (int i = 0; i < n; i++) {
            tick(pings);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object maskCooldown(Object type) {
        return ((Map<Object, Object>) R.get(MASK, "cooldownRemaining")).get(type);
    }

    @SuppressWarnings("unchecked")
    static void timerLag(LogicCase c) throws Exception {
        Object simWas = R.get("secrets.DungeonState", "simOverrideActive");
        boolean gateWas = (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled");
        Object bonzo = Mod.enumValue(MASK + "$Type", "BONZO");
        Object dragon = Mod.enumValue(DRAGON, "RED");
        Object dragonStateWas = R.get(dragon, "state");
        Mod.staticCall("util.SkyblockGate", "setEnabled", false);
        R.set("secrets.DungeonState", "simOverrideActive", true);
        try (AutoCloseable on = Mod.with("ticktimers.TickTimersConfig", "Enabled", true)) {
            Mod.staticCall(CLOCK, "untrustForTest");
            ticks(40, 1);
            if (!c.check("a server pinging every tick is trusted after two windows", (Boolean) Mod.staticCall(CLOCK, "trusted"),
                    "not trusted")) {
                return;
            }
            Mod.staticCall(TT, "resetAll");
            Mod.staticCall(TT, "onChatMessage", Component.literal("[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"));
            Mod.staticCall(TT, "onChatMessage", Component.literal("[BOSS] Necron: You went further than any human before, congratulations."));
            Mod.staticCall(TT, "onChatMessage", Component.literal("[BOSS] Storm: I should have known that I stood no chance."));
            long clock0 = (Long) Mod.staticCall(CLOCK, "now");
            Mod.staticCall(PG, "handle", "The sound of pickaxes clashing against the rock has attracted the attention of the POWDER GHAST!", clock0);
            ((Map<Object, Object>) R.get(MASK, "cooldownRemaining")).put(bonzo, 600);
            R.set(dragon, "timeToSpawn", 100);
            R.set(dragon, "state", Mod.enumValue(DRAGON + "$State", "DEAD"));
            R.set(MAXOR, "nextSetTicks", 100);
            c.eq("Maxor Start armed", 83, R.get(TT, "maxorStartTime"));
            c.eq("Necron drop armed", 60, R.get(TT, "necronTicks"));
            c.eq("Goldor Start armed", 17, R.get(TT, "goldorStartTime"));

            // 20 server ticks: every timer moved.
            ticks(20, 1);
            c.eq("Maxor Start moved 20 server ticks", 63, R.get(TT, "maxorStartTime"));
            c.eq("Necron drop moved 20 server ticks", 40, R.get(TT, "necronTicks"));
            c.eq("Goldor Start ran out (17 ticks are up; -1 is how it leaves the HUD)", -1, R.get(TT, "goldorStartTime"));
            c.eq("Powder Ghast minute moved 20 server ticks", 59L, Mod.staticCall(PG, "secondsLeft"));
            c.eq("mask cooldown moved 20 server ticks", 580, maskCooldown(bonzo));
            c.eq("dragon spawn countdown moved 20 server ticks", 80, R.get(dragon, "timeToSpawn"));
            c.eq("next crystal set backstop moved 20 server ticks", 80, R.get(MAXOR, "nextSetTicks"));

            // The server stands still for 20 client ticks: nothing moves.
            ticks(20, 0);
            c.check("still trusted after one failed window", (Boolean) Mod.staticCall(CLOCK, "trusted"), "untrusted");
            c.eq("stall: Maxor Start did not move", 63, R.get(TT, "maxorStartTime"));
            c.eq("stall: Necron drop did not move", 40, R.get(TT, "necronTicks"));
            c.eq("stall: Powder Ghast minute did not move", 59L, Mod.staticCall(PG, "secondsLeft"));
            c.eq("stall: mask cooldown did not move", 580, maskCooldown(bonzo));
            c.eq("stall: dragon spawn countdown did not move", 80, R.get(dragon, "timeToSpawn"));
            c.eq("stall: next crystal set backstop did not move", 80, R.get(MAXOR, "nextSetTicks"));

            // 83 client ticks in all have passed: a client-tick counter would be at its end, the server's is at tick 63.
            ticks(43, 1);
            c.eq("Maxor Start after 83 client / 63 server ticks", 20, R.get(TT, "maxorStartTime"));
            c.eq("Necron drop ran out after 63 server ticks", -1, R.get(TT, "necronTicks"));
            c.eq("mask cooldown after 63 server ticks", 537, maskCooldown(bonzo));
            c.eq("dragon spawn countdown after 63 server ticks", 37, R.get(dragon, "timeToSpawn"));

            // The 83rd server tick ends Maxor Start exactly.
            ticks(19, 1);
            c.eq("Maxor Start one tick before its end", 1, R.get(TT, "maxorStartTime"));
            ticks(1, 1);
            c.eq("Maxor Start ends on the 83rd server tick", 0, R.get(TT, "maxorStartTime"));
            c.eq("mask cooldown after 83 server ticks", 517, maskCooldown(bonzo));
            c.eq("Powder Ghast minute after 83 server ticks", 55L, Mod.staticCall(PG, "secondsLeft"));
            c.eq("dragon spawn countdown after 83 server ticks", 17, R.get(dragon, "timeToSpawn"));
            c.eq("next crystal set backstop after 83 server ticks", 17, R.get(MAXOR, "nextSetTicks"));
        } finally {
            Mod.staticCall(CLOCK, "untrustForTest");
            Mod.staticCall(TT, "resetAll");
            Mod.staticCall(PG, "expireNow");
            ((Map<Object, Object>) R.get(MASK, "cooldownRemaining")).put(bonzo, 0);
            R.set(dragon, "timeToSpawn", 100);
            R.set(dragon, "state", dragonStateWas);
            R.set(MAXOR, "nextSetTicks", -1);
            R.set("secrets.DungeonState", "simOverrideActive", simWas);
            Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas);
        }
    }

    /**
     * 1103-logic-timer-monotonic (mod branch timers2-1103; killer560, 2026-11-03: "they seem to normally count down then they
     * will randomly kind of jump back up and they keep getting off"). The reference mods (Odin, NoammAddons) count one tick
     * per non-zero ping and never move a countdown back. A realistic Hypixel pattern is fed to the clock by hand: single
     * pings, a burst after a network spike, stalls of 20 / 60 / 120 client ticks (the last six failing windows), pings of
     * 2 and 3 a tick. The clock must never go down, must stay trusted through the stalls, and must end exactly on the number
     * of pings sent; Goldor's "Tick:" and Storm's "Lightning:" must read exactly what the clock says (a whole number of
     * server ticks lower each time, wrapping only at Goldor's hit), and an ordinary hit one tick off the prediction must
     * not move the Goldor line.
     */
    static void timerMonotonic(LogicCase c) throws Exception {
        Object simWas = R.get("secrets.DungeonState", "simOverrideActive");
        boolean gateWas = (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled");
        Mod.staticCall("util.SkyblockGate", "setEnabled", false);
        R.set("secrets.DungeonState", "simOverrideActive", true);
        String gt = "ticktimers.GoldorTick";
        String sl = "ticktimers.StormLine";
        try (AutoCloseable on = Mod.with("ticktimers.TickTimersConfig", "Enabled", true)) {
            Mod.staticCall(CLOCK, "untrustForTest");
            ticks(40, 1);
            if (!c.check("trusted after two good windows", (Boolean) Mod.staticCall(CLOCK, "trusted"), "not trusted")) {
                return;
            }
            Mod.staticCall(TT, "resetAll");
            Mod.staticCall(TT, "onChatMessage", Component.literal("[BOSS] Storm: Pathetic Maxor, just like expected."));
            Mod.staticCall(TT, "onChatMessage", Component.literal("[BOSS] Goldor: Who dares trespass into my domain?"));
            ticks(1, 1);
            Mod.staticCall(gt, "endOfTick");
            Mod.staticCall(sl, "endOfTick");
            long clock0 = (Long) Mod.staticCall(CLOCK, "now");
            long sent = 0;
            long prevClock = clock0;
            int prevGoldor = (Integer) Mod.staticCall(gt, "remaining");
            int prevStorm = (Integer) Mod.staticCall(sl, "remaining");
            // {client ticks, pings per tick}: steady, burst, stalls of 20/60/120, doubles and triples, steady.
            int[][] plan = {{30, 1}, {1, 12}, {20, 1}, {20, 0}, {25, 1}, {60, 0}, {1, 40}, {15, 1}, {120, 0}, {1, 90}, {20, 2},
                    {10, 3}, {40, 1}};
            boolean everDown = false;
            boolean goldorOff = false;
            boolean stormOff = false;
            boolean untrusted = false;
            for (int[] step : plan) {
                for (int i = 0; i < step[0]; i++) {
                    tick(step[1]);
                    sent += step[1];
                    Mod.staticCall(gt, "endOfTick");
                    Mod.staticCall(sl, "endOfTick");
                    long now = (Long) Mod.staticCall(CLOCK, "now");
                    long d = now - prevClock;
                    everDown |= d < 0;
                    int g = (Integer) Mod.staticCall(gt, "remaining");
                    int st = (Integer) Mod.staticCall(sl, "remaining");
                    goldorOff |= Math.floorMod((prevGoldor - g) - d, 60L) != 0;
                    stormOff |= st != Math.max(0L, prevStorm - d);
                    untrusted |= !(Boolean) Mod.staticCall(CLOCK, "trusted");
                    prevClock = now;
                    prevGoldor = g;
                    prevStorm = st;
                }
            }
            c.check("the clock never went down", !everDown, "it did");
            c.check("trusted through every stall and burst", !untrusted, "trust was dropped");
            c.eq("the clock counted exactly the pings sent", sent, prevClock - clock0);
            c.check("Goldor's Tick moved exactly the clock's ticks (mod 60) each tick, never up", !goldorOff, "it did not");
            c.check("Storm's Lightning moved exactly the clock's ticks each tick, never up", !stormOff, "it did not");

            // A hit one tick after the prediction (the observation noise) moves nothing once the phase is locked.
            Mod.staticCall(gt, "testHitNow", false); // first hit: locks wherever it lands
            int before = (Integer) Mod.staticCall(gt, "remaining");
            c.eq("the first hit reads 0", 0, before);
            ticks(60, 1);
            ticks(1, 1);
            Mod.staticCall(gt, "testHitNow", false); // 1 tick late
            c.eq("a hit 1 tick late moved nothing: the line still reads 59 on it", 59, Mod.staticCall(gt, "remaining"));
            ticks(59, 1);
            Mod.staticCall(gt, "testHitNow", false); // exact
            c.eq("the next hit reads 0", 0, Mod.staticCall(gt, "remaining"));
        } finally {
            Mod.staticCall(CLOCK, "untrustForTest");
            Mod.staticCall(TT, "resetAll");
            R.set("secrets.DungeonState", "simOverrideActive", simWas);
            Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas);
        }
    }
}

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
}

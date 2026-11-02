package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 362-logic-boss-timers: the SkyBlock 0.27.2 timings ported from NoammAddons 1.2.9 (mod branch noamm-129), driven
 * through the mod's own chat handlers so each check proves the handler ACTED (a field moved), not just that a constant
 * holds a number. Runs in the throwaway singleplayer world: Blood Camp reads the level's game time and Auto i4 reads the
 * device wall's blocks, which are set client-side here and put back afterwards.
 */
final class BossTimerCases {

    private static final String TT = "ticktimers.TickTimersFeature";
    private static final String BC = "bloodcamp.BloodCampMoveTimer";
    private static final String I4 = "i4sensors.AutoI4Feature";

    private BossTimerCases() {
    }

    static void bossTimers(LogicCase c) throws Exception {
        tickTimers(c);
        bloodCamp(c);
        i4Predictions(c);
    }

    // ---- Tick Timers ----------------------------------------------------------------------------------------------

    private static void tickTimers(LogicCase c) throws Exception {
        boolean gateWas = (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled");
        Mod.staticCall("util.SkyblockGate", "setEnabled", false);
        try (AutoCloseable on = Mod.with("ticktimers.TickTimersConfig", "Enabled", true);
             AutoCloseable maxor = Mod.with("ticktimers.TickTimersConfig", "MaxorStartTimer", true);
             AutoCloseable start = Mod.with("ticktimers.TickTimersConfig", "GoldorStartTimer", true)) {
            Mod.staticCall(TT, "resetAll");
            chat(TT, "onChatMessage", "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!");
            c.eq("Maxor Start armed at 83", 83, R.get(TT, "maxorStartTime"));
            List<String> lines = hudLines();
            c.check("HUD shows the Maxor Start line", lines.stream().anyMatch(l -> l.contains("Maxor Start")), lines.toString());
            c.check("HUD has no Death tick line", lines.stream().noneMatch(l -> l.contains("Death")), lines.toString());

            chat(TT, "onChatMessage", "[BOSS] Storm: I should have known that I stood no chance.");
            c.eq("Goldor Start armed at 17 on Storm's death", 17, R.get(TT, "goldorStartTime"));
            chat(TT, "onChatMessage", "[BOSS] Goldor: Who dares trespass into my domain?");
            c.eq("Goldor's line ends Start", -1, R.get(TT, "goldorStartTime"));
            c.eq("Goldor's line starts the 60-tick Tick", 60, R.get(TT, "goldorTickTime"));

            chat(TT, "onChatMessage", "[BOSS] Storm: ENERGY HEED MY CALL!");
            c.eq("PY window 62", 62, R.get(TT, "pyTickTime"));

            chat(TT, "onChatMessage", "[BOSS] Necron: I'm afraid, your journey ends now.");
            c.eq("old Necron line no longer starts the drop timer", -1, R.get(TT, "necronTicks"));
            chat(TT, "onChatMessage", "[BOSS] Necron: You went further than any human before, congratulations.");
            c.eq("Necron drop 60 from \"You went further\"", 60, R.get(TT, "necronTicks"));
            chat(TT, "onChatMessage", "[MVP+] Bob: [BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!");
            c.eq("a player's copy of Maxor's line does not re-arm", 83, R.get(TT, "maxorStartTime"));
        } finally {
            Mod.staticCall(TT, "resetAll");
            Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> hudLines() {
        Object hud = R.construct(TT + "$TickTimersHudElement");
        return (List<String>) Mod.call(hud, "activeLines");
    }

    // ---- Blood Camp -----------------------------------------------------------------------------------------------

    private static void bloodCamp(LogicCase c) {
        try {
            Mod.staticCall(BC, "reset");
            chat(BC, "onChat", "[MVP+] Bob: [BOSS] The Watcher: hello");
            c.eq("a player's line does not arm the clock", -1L, R.get(BC, "bloodOpenTick"));
            chat(BC, "onChat", "[BOSS] The Watcher: A greeting no list knows yet.");
            long open = (Long) R.get(BC, "bloodOpenTick");
            c.check("the run's first Watcher line arms the clock", open >= 0, "bloodOpenTick " + open);

            // The throwaway world's game time is only a few ticks, so the greeting is placed at tick 0 and the last
            // wave comes "now": a gap under 18 s, the fast Watcher -> kill at greeting + 400 ticks (Noamm 1.2.9). The
            // slow-pacing table is unchanged code and needs a world 18 s old, so it is not exercised here.
            R.set(BC, "bloodOpenTick", 0L);
            chat(BC, "onChat", "[BOSS] The Watcher: Let's see how you can handle this.");
            // moveAtElapsed (mod doc-blood-1101): lag-free ticks after the greeting; the floor table gives 400 off a floor.
            c.eq("fast Watcher predicts greeting + 400", 400L, R.get(BC, "moveAtElapsed"));

            // A later Watcher line must not move the clock.
            Mod.staticCall(BC, "reset");
            chat(BC, "onChat", "[BOSS] The Watcher: Ah, you've finally arrived.");
            c.check("a known greeting arms the clock", (Long) R.get(BC, "bloodOpenTick") >= 0, "" + R.get(BC, "bloodOpenTick"));
            R.set(BC, "bloodOpenTick", 0L);
            chat(BC, "onChat", "[BOSS] The Watcher: Something he says later.");
            c.eq("a second, unknown Watcher line keeps the first", 0L, R.get(BC, "bloodOpenTick"));
        } finally {
            Mod.staticCall(BC, "reset");
        }
    }

    // ---- Auto i4 predictions --------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void i4Predictions(LogicCase c) throws Exception {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            c.check("a level exists for the i4 wall", false, "no client level");
            return;
        }
        List<BlockPos> dev = (List<BlockPos>) R.get("i4sensors.I4SensorsFeature", "DEV_BLOCKS");
        BlockState blue = ((Block) Mod.field("compat.McBlocks", "BLUE_TERRACOTTA")).defaultBlockState();
        List<BlockState> before = new ArrayList<>();
        for (BlockPos p : dev) {
            before.add(level.getBlockState(p));
        }
        Map<Object, Object> pinned = (Map<Object, Object>) R.get(I4, "pinnedAims");
        Set<Object> done = (Set<Object>) R.get(I4, "doneTargets");
        try (AutoCloseable weapon = Mod.with("i4sensors.I4SensorsConfig", "AutoI4Weapon",
                Mod.enumValue("i4sensors.I4SensorsConfig$Weapon", "TERMINATOR"))) {
            for (BlockPos p : dev) {
                level.setBlock(p, blue, 19);
            }
            boolean wall = c.check("the wall reads blue terracotta",
                    "blue_terracotta".equals(Mod.staticCall("i4sensors.I4SensorsFeature", "blockId", level.getBlockState(dev.get(0)))),
                    "block 0 is " + level.getBlockState(dev.get(0)));
            if (!wall) {
                return; // no wall, no candidates: every check below would pass or fail for the wrong reason
            }

            // Forced: lit = top-left (aim x 67.5), only top-middle left, its right neighbor done -> its aim is 67.5 too.
            // Noamm 1.2.9 re-rolls it away, and with nothing else left there is no prediction.
            BlockPos lit = dev.get(0);
            Mod.staticCall(I4, "resetDevice", "362");
            for (int i = 2; i < dev.size(); i++) {
                done.add(dev.get(i));
            }
            pinned.put(lit, Mod.staticCall(I4, "aimPointFor", lit));
            Object sameAim = Mod.staticCall(I4, "aimPointFor", dev.get(1));
            c.eq("top-middle aims where top-left does (the case under test)", pinned.get(lit), sameAim);
            c.eq("a prediction sharing the lit target's aim is re-rolled away", null,
                    Mod.staticCall(I4, "choosePrediction", lit));

            // Open wall: 200 draws, never one sharing the lit target's aim, never the lit block, never empty.
            int shared = 0;
            int none = 0;
            for (int n = 0; n < 200; n++) {
                Mod.staticCall(I4, "resetDevice", "362");
                pinned.put(lit, Mod.staticCall(I4, "aimPointFor", lit));
                Object pick = Mod.staticCall(I4, "choosePrediction", lit);
                if (pick == null || pick.equals(lit)) {
                    none++;
                } else if (pinned.get(pick).equals(pinned.get(lit))) {
                    shared++;
                }
            }
            c.eq("open wall: no prediction shares the lit target's aim (200 draws)", 0, shared);
            c.eq("open wall: every draw predicted another block", 0, none);
        } finally {
            Mod.staticCall(I4, "resetDevice", "362");
            for (int i = 0; i < dev.size(); i++) {
                level.setBlock(dev.get(i), before.get(i), 19);
            }
        }
    }

    // ---- Blood Camp's per-tick server clock (doc-blood-1101) ------------------------------------------------------

    private static final String CLOCK = "util.PerTickServerClock";

    /** One simulated client tick carrying {@code pings} server pings. */
    private static void simTick(int pings) {
        for (int i = 0; i < pings; i++) {
            Mod.staticCall(CLOCK, "pingForTest");
        }
        Mod.staticCall(CLOCK, "tickForTest");
    }

    /**
     * killer560, 2026-11-01: blood camp "if it lags", "sometimes the triggerbot comes out really late". Blood Camp's
     * countdowns and kill timer count server ticks. Driven by hand (no server here): a server pinging every tick is
     * trusted after two windows; a 20-tick stall (no pings) then stops the kill timer's clock and is measured as 1000 ms of
     * camp lag; an anticheat-like 15 pings per 20 ticks is never trusted, so the clock is the client's ticks.
     */
    static void bloodCampClock(LogicCase c) {
        try {
            Mod.staticCall(CLOCK, "untrustForTest");
            for (int i = 0; i < 40; i++) {
                simTick(1);
            }
            c.check("a server pinging every tick is trusted after two windows",
                    (Boolean) Mod.staticCall(CLOCK, "trusted"), "trusted=" + Mod.staticCall(CLOCK, "trusted"));
            Mod.staticCall(BC, "reset");
            chat(BC, "onChat", "[BOSS] The Watcher: Ah, you've finally arrived.");
            for (int i = 0; i < 10; i++) {
                simTick(1);
            }
            long beforeStall = (Long) Mod.staticCall(BC, "elapsedSinceGreeting");
            for (int i = 0; i < 20; i++) {
                simTick(0); // the server stood still
            }
            long afterStall = (Long) Mod.staticCall(BC, "elapsedSinceGreeting");
            long lagMs = (Long) Mod.staticCall(BC, "lagMsSinceGreeting");
            for (int i = 0; i < 5; i++) {
                simTick(1);
            }
            long resumed = (Long) Mod.staticCall(BC, "elapsedSinceGreeting");
            c.eq("10 server ticks after the greeting", 10L, beforeStall);
            c.eq("a 20-tick stall does not move the kill timer's clock", 10L, afterStall);
            c.eq("the stall is the camp's lag", 1000L, lagMs);
            c.eq("the clock runs again with the pings", 15L, resumed);

            // An anticheat's transactions: 15 pings in 20 ticks, unevenly. Never trusted: the clock is client ticks.
            Mod.staticCall(CLOCK, "untrustForTest");
            Mod.staticCall(BC, "reset");
            chat(BC, "onChat", "[BOSS] The Watcher: Ah, you've finally arrived.");
            for (int i = 0; i < 100; i++) {
                simTick(i % 4 == 3 ? 0 : 1);
            }
            c.check("15 pings per 20 ticks is not a per-tick server", !(Boolean) Mod.staticCall(CLOCK, "trusted"),
                    "trusted=" + Mod.staticCall(CLOCK, "trusted"));
            c.eq("untrusted: 100 client ticks are 100 ticks", 100L, Mod.staticCall(BC, "elapsedSinceGreeting"));
            c.eq("untrusted: no camp lag", 0L, Mod.staticCall(BC, "lagMsSinceGreeting"));
        } finally {
            Mod.staticCall(CLOCK, "untrustForTest");
            Mod.staticCall(BC, "reset");
        }
    }

    private static void chat(String cls, String method, String line) {
        Mod.staticCall(cls, method, Component.literal(line));
    }
}

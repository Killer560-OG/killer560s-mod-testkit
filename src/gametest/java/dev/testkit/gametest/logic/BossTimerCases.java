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
    private static final String GT = "ticktimers.GoldorTick";
    private static final String SL = "ticktimers.StormLine";

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
            c.eq("Goldor Start armed at GOLDOR_START_TICKS (64 since tickfix2-1104) on Storm's death",
                    R.get(TT, "GOLDOR_START_TICKS"), R.get(TT, "goldorStartTime"));
            chat(TT, "onChatMessage", "[BOSS] Goldor: Who dares trespass into my domain?");
            c.eq("Goldor's line ends Start", -1, R.get(TT, "goldorStartTime"));
            // f7hud-1102: Goldor's tick is GoldorTick now (a remembered server tick, not a counter).
            c.check("Goldor's line starts the Tick", (Boolean) Mod.staticCall(GT, "active"), "GoldorTick inactive");
            c.eq("the Tick reads 42 on Goldor's line (his first hit comes 42 ticks after it, 2026-11-03)", 42, Mod.staticCall(GT, "remaining"));

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

    /** {@link #simTick} with the pings timed on the test's wall clock (goldfix-1104's pace check sees them). */
    private static void timedTick(int pings) {
        for (int i = 0; i < pings; i++) {
            Mod.staticCall(CLOCK, "pingForTestTimed");
        }
        Mod.staticCall(CLOCK, "tickForTest");
    }

    private static void timedTicks(int n) {
        for (int i = 0; i < n; i++) {
            timedTick(1);
        }
    }

    /** goldfix-1104's pattern: one ping a tick plus 2 extra every 20 ticks (a quiet GrimAC server, hit every second). */
    private static long grimLike(int n) {
        long g0 = clock();
        for (int i = 0; i < n; i++) {
            timedTick(i % 20 == 10 ? 3 : 1);
        }
        return clock() - g0;
    }

    /**
     * {@link #simTick} plus one tick of the level's game time. Since bctrig-1104 the camp's clock is the LOWER of the ping
     * clock and the game time, and this case runs inside one client frame, where the game time would never move: so it is
     * moved by hand, one tick per client tick (it runs on through a stall, as on a real server between set-time packets).
     */
    private static void campTick(int pings) {
        simTick(pings);
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            level.setTimeFromServer(level.getGameTime() + 1);
        }
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
                campTick(1);
            }
            long beforeStall = (Long) Mod.staticCall(BC, "elapsedSinceGreeting");
            for (int i = 0; i < 20; i++) {
                campTick(0); // the server stood still
            }
            long afterStall = (Long) Mod.staticCall(BC, "elapsedSinceGreeting");
            long lagMs = (Long) Mod.staticCall(BC, "lagMsSinceGreeting");
            for (int i = 0; i < 5; i++) {
                campTick(1);
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
                campTick(i % 4 == 3 ? 0 : 1);
            }
            c.check("15 pings per 20 ticks is not a per-tick server", !(Boolean) Mod.staticCall(CLOCK, "trusted"),
                    "trusted=" + Mod.staticCall(CLOCK, "trusted"));
            c.eq("untrusted: 100 client ticks are 100 ticks", 100L, Mod.staticCall(BC, "elapsedSinceGreeting"));
            c.eq("untrusted: no camp lag", 0L, Mod.staticCall(BC, "lagMsSinceGreeting"));

            // goldfix-1104: a quiet GrimAC server pings once a tick and was trusted; each hit then added transactions and
            // ran the clock ahead (1102-hx-goldor-tick on 26.3 legit: hits read 5, 3, 7, 11). One ping a tick plus 2
            // extra every 20 ticks: caught once the pings run more than 6 ahead of the wall clock, and not trusted again in
            // that world, while a plain per-tick server (above, and after a new world) still is.
            Mod.staticCall(CLOCK, "untrustForTest");
            timedTicks(40);
            c.check("a quiet per-tick-looking server is trusted at first", (Boolean) Mod.staticCall(CLOCK, "trusted"),
                    "trusted=" + Mod.staticCall(CLOCK, "trusted"));
            long ran = grimLike(100);
            c.check("pings faster than the server can tick end the trust", !(Boolean) Mod.staticCall(CLOCK, "trusted")
                    && (Boolean) Mod.staticCall(CLOCK, "notPerTick"), "trusted=" + Mod.staticCall(CLOCK, "trusted")
                    + " notPerTick=" + Mod.staticCall(CLOCK, "notPerTick"));
            // 6 extra pings counted before the 7th is caught, plus the switch tick (its ping and its client tick): 107.
            c.check("the clock ran at most 7 extra ticks in 100 (every extra ping counted would be 10)", ran >= 100 && ran <= 107,
                    "ran " + ran);
            timedTicks(60);
            c.check("not trusted again in the same world", !(Boolean) Mod.staticCall(CLOCK, "trusted"),
                    "trusted=" + Mod.staticCall(CLOCK, "trusted"));
            Mod.staticCall(CLOCK, "untrustForTest");
            timedTicks(40);
            c.check("a new world starts trusting afresh", (Boolean) Mod.staticCall(CLOCK, "trusted"),
                    "trusted=" + Mod.staticCall(CLOCK, "trusted"));
            // (tickfix3-1104: real hypixel.net no longer uses this rule, see 1104-logic-hypixel-pings; the testkit's
            // mc.hypixel.net label on its loopback GrimAC server is not real hypixel.net, so Grim is judged as above.)
            // A stall and its catch-up burst are not pings ahead of the wall clock: trusted again two windows after it (off
            // hypixel.net two failing windows end the trust; until tickfix3 this ran as hypixel.net, where it was sticky).
            Mod.staticCall(CLOCK, "untrustForTest");
            timedTicks(40);
            for (int i = 0; i < 40; i++) {
                timedTick(0);
            }
            timedTick(41);
            timedTicks(60); // the burst's window fails (41 pings), then two good ones
            c.check("a 2 s stall and its 41-ping burst: trusted again, never caught pinging ahead", (Boolean) Mod.staticCall(CLOCK, "trusted")
                    && !(Boolean) Mod.staticCall(CLOCK, "notPerTick"), "trusted=" + Mod.staticCall(CLOCK, "trusted")
                    + " notPerTick=" + Mod.staticCall(CLOCK, "notPerTick"));
        } finally {
            Mod.staticCall(CLOCK, "hypixelForTest", (Object) null);
            Mod.staticCall(CLOCK, "untrustForTest");
            Mod.staticCall(BC, "reset");
        }
    }

    // ---- f7hud-1102: Goldor's tick, Storm's one line, old config keys ---------------------------------------------

    /** n client ticks of a server that pings every tick (the clock trusted): n server ticks. */
    private static void serverTicks(int n) {
        for (int i = 0; i < n; i++) {
            simTick(1);
        }
    }

    private static long clock() {
        return (Long) Mod.staticCall(CLOCK, "now");
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> offsets() {
        return (List<Integer>) Mod.staticCall(GT, "testOffsetsAtHits");
    }

    private static String tickLine() {
        for (String l : hudLines()) {
            String plain = l.replaceAll("§.", "");
            if (plain.startsWith("Tick:")) {
                return plain;
            }
        }
        return null;
    }

    /**
     * killer560, 2026-11-02: Goldor's "Tick:" "procced my mask at like 1.5s instead of at 0". Scripted server ticks, the
     * clock driven by hand as a per-tick pinger: (a) hits on Goldor's 60-tick cycle read 0 at every hit and the line shows
     * "Tick: 0.0s" on each; (b) hits 18 ticks off the start phase (his real 2026-11-03 run) re-lock on the FIRST hit, after
     * which every hit reads 0, while a hit 10 ticks after the line locks nothing; (c) a stray hit between two real ones moves nothing; (d) two players hurt at once re-lock
     * at once; a hit 2 ticks late snaps the phase; a mask proc line is a hit, a player's copy of it is not.
     */
    static void goldorTick(LogicCase c) throws Exception {
        boolean gateWas = (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled");
        Mod.staticCall("util.SkyblockGate", "setEnabled", false);
        try (AutoCloseable on = Mod.with("ticktimers.TickTimersConfig", "Enabled", true);
             AutoCloseable g = Mod.with("ticktimers.TickTimersConfig", "GoldorTimer", true);
             AutoCloseable start = Mod.with("ticktimers.TickTimersConfig", "GoldorStartTimer", false);
             AutoCloseable total = Mod.with("ticktimers.TickTimersConfig", "GoldorShowTotal", false)) {
            Mod.staticCall(CLOCK, "untrustForTest");
            serverTicks(40);
            c.check("the hand-driven server is trusted", (Boolean) Mod.staticCall(CLOCK, "trusted"), "not trusted");

            // (a) in phase.
            Mod.staticCall(TT, "resetAll");
            chat(TT, "onChatMessage", "[BOSS] Goldor: Who dares trespass into my domain?");
            long line = clock();
            c.eq("(a) 42 on the line", 42, Mod.staticCall(GT, "remaining"));
            List<String> atHits = new ArrayList<>();
            for (int k = 0; k < 4; k++) {
                serverTicks(k == 0 ? 42 : 60);
                c.eq("(a) hit " + (k + 1) + " at line+" + (42 + 60 * k) + ": the Tick reads 0 before the hit is seen", 0,
                        Mod.staticCall(GT, "remaining"));
                Mod.staticCall(GT, "testHitNow", false);
                atHits.add(tickLine());
            }
            c.eq("(a) clock moved 222 server ticks", line + 222, clock());
            c.eq("(a) offsets at the 4 hits", List.of(0, 0, 0, 0), offsets());
            c.check("(a) the HUD read Tick: 0.0s at every hit", atHits.stream().allMatch("Tick: 0.0s"::equals), atHits.toString());
            serverTicks(1);
            c.eq("(a) 59 the tick after a hit", 59, Mod.staticCall(GT, "remaining"));

            // (b) His 2026-11-03 log replayed: hits 18 ticks off the start phase (line + 60). The FIRST hit re-locks, then 0.
            Mod.staticCall(TT, "resetAll");
            chat(TT, "onChatMessage", "[BOSS] Goldor: Who dares trespass into my domain?");
            serverTicks(60);
            Mod.staticCall(GT, "testHitNow", false);
            c.eq("(b) one hit locked the phase: 0 on its tick", 0, Mod.staticCall(GT, "remaining"));
            for (int k = 0; k < 3; k++) {
                serverTicks(60);
                c.eq("(b) hit " + (k + 2) + " reads 0", 0, Mod.staticCall(GT, "remaining"));
                Mod.staticCall(GT, "testHitNow", false);
            }
            List<Integer> off = offsets();
            c.check("(b) the first hit was 18 off the start phase", off.size() == 4 && off.get(0) == 18, off.toString());
            c.check("(b) after it every hit is on 0", off.size() == 4 && off.get(1) == 0 && off.get(2) == 0 && off.get(3) == 0, off.toString());
            c.eq("(b) one re-lock", 1, Mod.staticCall(GT, "testRelocks"));

            // (b2) a hit 10 ticks after the line is not Goldor's first (fall damage, a mob): it locks nothing.
            Mod.staticCall(TT, "resetAll");
            chat(TT, "onChatMessage", "[BOSS] Goldor: Who dares trespass into my domain?");
            serverTicks(10);
            Mod.staticCall(GT, "testHitNow", false);
            c.eq("(b2) not locked by a hit 10 ticks after the line", false, Mod.staticCall(GT, "testLocked"));
            serverTicks(32);
            c.eq("(b2) the Tick still reads 0 at line+42", 0, Mod.staticCall(GT, "remaining"));
            Mod.staticCall(GT, "testHitNow", false);
            c.eq("(b2) the real first hit locks", true, Mod.staticCall(GT, "testLocked"));
            c.eq("(b2) no re-lock was needed", 0, Mod.staticCall(GT, "testRelocks"));

            // (c) a stray hit 25 ticks after a real one moves nothing.
            Mod.staticCall(TT, "resetAll");
            chat(TT, "onChatMessage", "[BOSS] Goldor: Who dares trespass into my domain?");
            serverTicks(42);
            Mod.staticCall(GT, "testHitNow", false);
            serverTicks(25);
            Mod.staticCall(GT, "testHitNow", false);
            serverTicks(35);
            c.eq("(c) the next real hit still reads 0", 0, Mod.staticCall(GT, "remaining"));
            Mod.staticCall(GT, "testHitNow", false);
            c.eq("(c) offsets: real, stray, real", List.of(0, 25, 0), offsets());
            c.eq("(c) no re-lock", 0, Mod.staticCall(GT, "testRelocks"));

            // (d) two players hurt in one tick re-lock at once.
            Mod.staticCall(TT, "resetAll");
            chat(TT, "onChatMessage", "[BOSS] Goldor: Who dares trespass into my domain?");
            serverTicks(17);
            Mod.staticCall(GT, "testHitNow", true);
            c.eq("(d) a two-player hit re-locks: 0 on its tick", 0, Mod.staticCall(GT, "remaining"));
            serverTicks(60);
            c.eq("(d) 0 again one period later", 0, Mod.staticCall(GT, "remaining"));

            // Small drift: a hit 2 ticks late snaps the phase onto it.
            Mod.staticCall(TT, "resetAll");
            chat(TT, "onChatMessage", "[BOSS] Goldor: Who dares trespass into my domain?");
            serverTicks(44);
            Mod.staticCall(GT, "testHitNow", false);
            serverTicks(60);
            c.eq("a hit 2 ticks late moves the phase onto it", 0, Mod.staticCall(GT, "remaining"));

            // A mask proc line is a hit.
            Mod.staticCall(TT, "resetAll");
            chat(TT, "onChatMessage", "[BOSS] Goldor: Who dares trespass into my domain?");
            serverTicks(40);
            chat(TT, "onChatMessage", "[MVP+] Bob: Second Wind Activated! Your Spirit Mask saved your life!");
            Mod.staticCall(GT, "endOfTick");
            c.eq("a player's copy of the proc line is not a hit", List.of(), offsets());
            serverTicks(3);
            chat(TT, "onChatMessage", "Second Wind Activated! Your Spirit Mask saved your life!");
            Mod.staticCall(GT, "endOfTick"); // a proc is counted at the end of the client tick, with the health drops
            c.eq("a Spirit Mask proc counts as a hit", List.of(1), offsets());

            // The core opening ends it.
            chat(TT, "onChatMessage", "The Core entrance is opening!");
            c.check("the core opening stops the Tick", !(Boolean) Mod.staticCall(GT, "active"), "still active");
            c.check("no Tick line after the core opens", tickLine() == null, hudLines().toString());
        } finally {
            Mod.staticCall(TT, "resetAll");
            Mod.staticCall(CLOCK, "untrustForTest");
            Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas);
        }
    }

    private static String stormLine() {
        List<String> out = new ArrayList<>();
        for (String l : hudLines()) {
            String plain = l.replaceAll("§.", "");
            if (plain.startsWith("Lightning:") || plain.startsWith("Purple:") || plain.startsWith("Yellow:")
                    || plain.startsWith("Pad:") || plain.startsWith("Storm:") || plain.startsWith("PY:") || plain.startsWith("Crush")) {
                out.add(plain);
            }
        }
        return out.size() == 1 ? out.get(0) : out.isEmpty() ? null : out.toString();
    }

    /**
     * killer560, 2026-11-02: Storm's line is ONE line: Lightning counts down from Storm's opening line, the lightning line
     * switches it to the purple pad timer (0 every 20 server ticks from the opening line), the first crush to yellow on
     * the same cycle, Storm's death ends it. No Pad / Storm / PY / Crush line exists any more.
     */
    static void stormLineCase(LogicCase c) throws Exception {
        boolean gateWas = (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled");
        Mod.staticCall("util.SkyblockGate", "setEnabled", false);
        try (AutoCloseable on = Mod.with("ticktimers.TickTimersConfig", "Enabled", true);
             AutoCloseable st = Mod.with("ticktimers.TickTimersConfig", "StormLine", true);
             AutoCloseable ticks = Mod.with("ticktimers.TickTimersConfig", "DisplayInTicks", true)) {
            Mod.staticCall(CLOCK, "untrustForTest");
            serverTicks(40);
            Mod.staticCall(TT, "resetAll");
            chat(TT, "onChatMessage", "[BOSS] Storm: Pathetic Maxor, just like expected.");
            c.eq("Lightning 555t on Storm's line", "Lightning: 555t", stormLine());
            serverTicks(100);
            c.eq("Lightning 455t 100 ticks on", "Lightning: 455t", stormLine());
            serverTicks(470);
            c.eq("Lightning holds at 0 until the lightning goes off", "Lightning: 0t", stormLine());
            // Purple is ONE 62-tick countdown from the lightning line (NoammAddons), then the line goes away (timers2-1103).
            chat(TT, "onChatMessage", "[BOSS] Storm: ENERGY HEED MY CALL!");
            c.eq("the lightning line switches to the purple countdown", "Purple: 62t", stormLine());
            serverTicks(62);
            c.eq("purple reads 0 on the 62nd server tick", "Purple: 0t", stormLine());
            serverTicks(11);
            c.eq("the purple line goes away after its hold", null, stormLine());
            chat(TT, "onChatMessage", "[BOSS] Storm: Oof");
            String y = stormLine();
            c.check("the first crush keeps the same cycle for yellow", y != null && y.startsWith("Yellow: ")
                    && y.equals("Yellow: " + Mod.staticCall(SL, "remaining") + "t"), "" + y);
            chat(TT, "onChatMessage", "[BOSS] Storm: Ouch, that hurt!");
            c.check("a second crush stays on yellow", String.valueOf(stormLine()).startsWith("Yellow: "), "" + stormLine());
            chat(TT, "onChatMessage", "[BOSS] Storm: I should have known that I stood no chance.");
            c.eq("Storm's death ends the line", null, stormLine());

            // A crush with the lightning line missed: straight to yellow.
            chat(TT, "onChatMessage", "[BOSS] Storm: Pathetic Maxor, just like expected.");
            chat(TT, "onChatMessage", "[BOSS] Storm: Oof");
            c.check("a crush before any lightning line goes to yellow", String.valueOf(stormLine()).startsWith("Yellow: "), "" + stormLine());
            chat(TT, "onChatMessage", "[MVP+] Bob: [BOSS] Storm: Pathetic Maxor, just like expected.");
            c.check("a player's copy of Storm's line restarts nothing", String.valueOf(stormLine()).startsWith("Yellow: "), "" + stormLine());

            try (AutoCloseable off = Mod.with("ticktimers.TickTimersConfig", "StormLine", false)) {
                c.eq("Storm Line off: no line", null, stormLine());
            }
        } finally {
            Mod.staticCall(TT, "resetAll");
            Mod.staticCall(CLOCK, "untrustForTest");
            Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas);
        }
    }

    /**
     * killer560's 2026-11-02 screenshots (1920x1080, GUI 2 = 960x540 GUI px): the Storm line over Maxor's "Active", Team
     * Melody over the Split Timers, the P3 list over chat. The F7 HUDs' DEFAULT boxes (their largest real content: Tick
     * Timers 3 lines of 12, Maxor 3 of 10, P3 Status 4 of 10, Team Melody 120 with boards, 160 wide) must miss each other (Maxor and
     * P3 Status never show together), the chat (x < 330, y 320-500 at 540 high), the Split Timers' default column (x 10)
     * and the vanilla sidebar (right edge, y 200-340 at 540 high: Team Melody goes under it). Positions are read from the elements on this client's screen.
     */
    static void f7Slots(LogicCase c) {
        int screenW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        String[][] els = {{"tick_timers", "36"}, {"maxor_crystals", "30"}, {"p3_status", "40"}, {"team_melody", "120"}};
        int[][] box = new int[els.length][];
        for (int i = 0; i < els.length; i++) {
            Object e = Mod.staticCall("hud.HudElementRegistry", "byId", els[i][0]);
            if (!c.check(els[i][0] + " is registered", e != null)) {
                return;
            }
            int x = (Integer) Mod.call(e, "defaultX");
            int y = (Integer) Mod.call(e, "defaultY");
            box[i] = new int[]{x, y, x + 160, y + Integer.parseInt(els[i][1])};
            c.note(els[i][0] + " default " + x + "," + y + " on a " + screenW + " wide screen");
            c.eq(els[i][0] + " sits in the right column", screenW - 170, x);
            c.check(els[i][0] + " is clear of the chat and the Split Timers (x >= 330 at 960 wide)", screenW < 500 || x >= 330, "x " + x);
            boolean melody = els[i][0].equals("team_melody");
            c.check(els[i][0] + (melody ? " starts below the vanilla sidebar (y >= 345)" : " ends above the vanilla sidebar (y <= 200)"),
                    melody ? y >= 345 : box[i][3] <= 200, "top " + y + ", bottom " + box[i][3]);
        }
        for (int a = 0; a < els.length; a++) {
            for (int b = a + 1; b < els.length; b++) {
                boolean together = !(els[a][0].equals("maxor_crystals") && els[b][0].equals("p3_status"));
                boolean meet = box[a][0] < box[b][2] && box[b][0] < box[a][2] && box[a][1] < box[b][3] && box[b][1] < box[a][3];
                if (together) {
                    c.check(els[a][0] + " and " + els[b][0] + " do not overlap", !meet,
                            java.util.Arrays.toString(box[a]) + " vs " + java.util.Arrays.toString(box[b]));
                }
            }
        }
    }

    /**
     * Old config files with the removed keys load without a fuss: Tick Timers' "stormTimer"/"padCycleTimer" become
     * "stormLine" (ON if either was), the crush countdown keys are dropped by the next save, the pad outline keys are kept;
     * P3 Status' "showWhoIsAt" is ignored and dropped.
     */
    static void oldConfigs(LogicCase c) throws Exception {
        java.nio.file.Path tt = (java.nio.file.Path) R.get("ticktimers.TickTimersConfig", "CONFIG_PATH");
        java.nio.file.Path p3 = (java.nio.file.Path) R.get("p3status.P3StatusConfig", "CONFIG_PATH");
        byte[] ttWas = java.nio.file.Files.exists(tt) ? java.nio.file.Files.readAllBytes(tt) : null;
        byte[] p3Was = java.nio.file.Files.exists(p3) ? java.nio.file.Files.readAllBytes(p3) : null;
        try {
            java.nio.file.Files.createDirectories(tt.getParent());
            String[][] cases = {{"false", "true", "true"}, {"true", "false", "true"}, {"false", "false", "false"}};
            for (String[] k : cases) {
                java.nio.file.Files.writeString(tt, "{\"enabled\": true, \"stormTimer\": " + k[0] + ", \"padCycleTimer\": " + k[1]
                        + ", \"crushTimer\": true, \"crushTitle\": true, \"crushIntervalSeconds\": 12.5,"
                        + " \"crushWarnSeconds\": 4.0, \"crushExtraTrigger\": \"zap\", \"crushPadHighlight\": true,"
                        + " \"crushPadColor\": 123456, \"goldorFrenzyMigrated\": true, \"f7spotsCrushMigrated\": true}");
                Mod.staticCall("ticktimers.TickTimersConfig", "load");
                Object cfg = Mod.cfg("ticktimers.TickTimersConfig");
                c.eq("stormTimer " + k[0] + " + padCycleTimer " + k[1] + " -> Storm Line " + k[2],
                        Boolean.parseBoolean(k[2]), Mod.call(cfg, "isStormLine"));
                c.eq("pad highlight kept", true, Mod.call(cfg, "isCrushPadHighlightEnabled"));
                c.eq("pad color kept", 123456, Mod.call(cfg, "getCrushPadColor"));
            }
            Mod.call(Mod.cfg("ticktimers.TickTimersConfig"), "save");
            String saved = java.nio.file.Files.readString(tt);
            for (String gone : new String[]{"stormTimer", "padCycleTimer", "crushTimer", "crushTitle", "crushIntervalSeconds",
                    "crushWarnSeconds", "crushExtraTrigger"}) {
                c.check("the save drops \"" + gone + "\"", !saved.contains("\"" + gone + "\""), saved);
            }
            c.check("the save writes stormLine", saved.contains("\"stormLine\": false"), saved);
            Mod.staticCall("ticktimers.TickTimersConfig", "load");
            c.eq("stormLine survives a reload", false, Mod.call(Mod.cfg("ticktimers.TickTimersConfig"), "isStormLine"));

            java.nio.file.Files.createDirectories(p3.getParent());
            java.nio.file.Files.writeString(p3, "{\"enabled\": true, \"showHud\": false, \"showWhoIsAt\": true, \"titleSound\": false}");
            Mod.staticCall("p3status.P3StatusConfig", "load");
            Object pc = Mod.cfg("p3status.P3StatusConfig");
            c.eq("P3 Status loads with the old key: showHud", false, Mod.call(pc, "getShowHudRaw"));
            c.eq("P3 Status loads with the old key: titleSound", false, Mod.call(pc, "getTitleSoundRaw"));
            Mod.call(pc, "save");
            String p3saved = java.nio.file.Files.readString(p3);
            c.check("the save drops showWhoIsAt", !p3saved.contains("showWhoIsAt"), p3saved);
        } finally {
            if (ttWas == null) {
                java.nio.file.Files.deleteIfExists(tt);
            } else {
                java.nio.file.Files.write(tt, ttWas);
            }
            if (p3Was == null) {
                java.nio.file.Files.deleteIfExists(p3);
            } else {
                java.nio.file.Files.write(p3, p3Was);
            }
            Mod.staticCall("ticktimers.TickTimersConfig", "load");
            Mod.staticCall("p3status.P3StatusConfig", "load");
        }
    }

    private static void chat(String cls, String method, String line) {
        Mod.staticCall(cls, method, Component.literal(line));
    }
}

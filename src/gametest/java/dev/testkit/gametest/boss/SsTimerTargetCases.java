package dev.testkit.gametest.boss;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.HxKit;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mod branch ss4-1027 (killer560, 2026-10-27: "for simon says the timer based solver needs to be adjusted down a
 * button"). Auto Solve's Timer Target pacing plans the device's real round count from the first click: 4 rounds and 10
 * clicks on Hypixel's device, 5 rounds and 15 on the old one (p3sim.net still runs it), and a remembered count the device
 * contradicts is corrected instead of trusted.
 *
 * <p>Judged from the SERVER's record (hx/boss/HxSimonSays, Hypixel's reveal pacing): the device completes, every press is
 * correct, the number of presses is the device's, and the start-press to completion time lands on the mod's aim. The
 * mod aims SERVER_TIMER_COMPENSATION_MS (330 ms) under the Target, because Hypixel's own device timer reads that much
 * longer than the client's clock; on this local server the two clocks agree, so the aim here is Target - 330.
 * The test presses start itself (the mod anchors the Target on any start press) and then turns Auto Solve on, as 1018.
 */
final class SsTimerTargetCases {

    private static final String SS_CFG = SimonSaysCases.CONFIG;
    private static final String F = SimonSaysCases.FEATURE;
    private static final String LAYOUTS = SimonSaysCases.LAYOUTS;
    private static final int COMPENSATION_MS = 330;
    private static final int TOLERANCE_MS = 300;

    private SsTimerTargetCases() {
    }

    static void register(Session s) {
        if (!Mod.isCheat()) {
            return;
        }
        // Hypixel's device, round count not seen yet this session (the first device of a session): 10 clicks at Target.
        s.test("1027-ss-target-4rounds", c -> play(c, 4, 0, 71, true, 12_000, false));
        // The old device on a server the mod knows runs it (noted 5 first, as p3sim.net is known): 15 clicks at Target.
        // At Hypixel's pace a 5-round device from round 1 cannot be done in the Target slider's 13 s maximum (its four
        // transitions alone are about 9.2 s; full speed came to 14.05-14.9 s in the first runs). p3sim, which runs this
        // device, is quicker and is played with a skip; here the device reveals twice as fast (2 ticks lit, 1 dark,
        // transitions about 1.4-1.85 s) so a 13 s Target is reachable with time to pace. The mod's estimates for this
        // server start at Hypixel's table, so this also proves the per-device reveal scale catches up. No Rotate: 15 turned
        // clicks in 13 s left round 5 under a second for its five, and the turns' own pace decided the finish (-20 ms in
        // one run, +430 in the next); this case is about the round count, the 4-round case keeps Rotate.
        s.test("1027-ss-target-5rounds", c -> play(c, 5, 5, 72, true, 13_000, true));
        // A remembered 5 the device contradicts (p3sim, then Hypixel): round 4 must still end it - no restart, no break.
        s.test("1027-ss-stale5-on-4rounds", c -> play(c, 4, 5, 73, false, 12_000, false));
    }

    /**
     * @param rounds   the device the server builds
     * @param preNote  0 to start with nothing seen, else the round count noted on this server before the device
     * @param judgeTime whether the Target is judged (the stale-5 case plans 5 for a 4-round device, so it cannot land)
     */
    private static void play(Session c, int rounds, int preNote, int seed, boolean judgeTime, int targetMs,
            boolean fastReveal)
            throws Exception {
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        HxKit.Settings on = new HxKit.Settings(c)
                .custom(() -> Mod.staticCall("util.SkyblockGate", "setEnabled", false),
                        () -> Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas))
                .with(SS_CFG, "SolverEnabled", true).with(SS_CFG, "AutoRestartEnabled", true)
                .with(SS_CFG, "AnnounceProgress", true)
                .with(SS_CFG, "AutoStartEnabled", false).with(SS_CFG, "TriggerBotEnabled", false)
                .with(SS_CFG, "AutoSolveRotate", !fastReveal).with(SS_CFG, "AutoSolveEnabled", false)
                .with(SS_CFG, "AutoSolveFixedDelayMode", false).with(SS_CFG, "AutoSolveStim", false)
                .with(SS_CFG, "AutoSolveMomentum", 0).with(SS_CFG, "DiagnosticLoggingEnabled", true)
                .with(SS_CFG, "ClickTimerTargetMs", targetMs).with(SS_CFG, "ClickTimerVarianceMs", 0);
        try (on) {
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> "F7".equals(Mod.staticCall("secrets.DungeonState", "getFloor")), 100);
            c.onClient(mc -> {
                Mod.set(SS_CFG, "setEnabled", false);
                Mod.staticCall(LAYOUTS, "resetSeen");
                if (preNote > 0) {
                    Mod.staticCall(LAYOUTS, "noteSimonRounds", preNote, "testkit 1027: noted before the device");
                }
                return null;
            });
            int planned = c.onClient(mc -> (Integer) Mod.staticCall(LAYOUTS, "simonRounds"));
            if (fastReveal) {
                c.hx().call("boss.ss.build", "rounds", rounds, "line", true, "seed", seed, "revealTicks", 2, "gapTicks", 1);
            } else {
                c.hx().call("boss.ss.build", "rounds", rounds, "line", true, "seed", seed);
            }
            // Let the rebuild reach the client (and GrimAC's copy of the world) before the wait below: the previous case
            // left a start button at the same spot, so the client view can pass the wait on stale blocks, and a start
            // press sent then hit air in Grim's world (AirLiquidPlace, the 2026-10-27 final run of this block).
            c.ctx().waitTicks(10);
            c.hx().call("dungeon.tp", "x", 107.5, "y", 120.0, "z", 93.5, "yaw", -90f, "pitch", 0f);
            c.waitUntil("the client to have the device", mc ->
                    mc.level.getBlockState(new BlockPos(111, 120, 92)).is(net.minecraft.world.level.block.Blocks.OBSIDIAN)
                            && mc.level.getBlockState(SimonSaysCases.START).is(
                            net.minecraft.world.level.block.Blocks.STONE_BUTTON), 200);
            c.ctx().waitTicks(5);
            c.onClient(mc -> {
                Mod.set(SS_CFG, "setEnabled", true);
                return null;
            });
            c.waitUntil("Simon Says at the device", mc -> (Boolean) Mod.field(F, "wasActive"), 200);
            c.ctx().waitTicks(5);

            long since = c.hx().head();
            SimonSaysCases.click(c, SimonSaysCases.START);
            c.waitUntil("the device to start (Hx)", mc -> !"IDLE".equals(SimonSaysCases.state(c).get("phase").getAsString()), 60);
            c.onClient(mc -> {
                Mod.set(SS_CFG, "setAutoSolveEnabled", true);
                return null;
            });
            boolean done = false;
            try {
                c.waitUntil("ss.completed (Hx) with Auto Solve at Target " + targetMs + " ms",
                        mc -> !c.events("ss.completed").isEmpty(), 1200);
                done = true;
            } finally {
                c.onClient(mc -> {
                    Mod.set(SS_CFG, "setAutoSolveEnabled", false);
                    return null;
                });
            }
            // The verdict timeout (100 ticks), the break window (30) and a restart burst, as 501-503.
            c.ctx().waitTicks(160);

            List<JsonObject> presses = c.events("ss.press");
            int wrong = 0;
            for (JsonObject p : presses) {
                if (!p.get("correct").getAsBoolean()) {
                    wrong++;
                }
            }
            List<JsonObject> starts = c.events("ss.start");
            List<JsonObject> completed = c.events("ss.completed");
            long startTick = starts.isEmpty() ? 0 : starts.get(0).get("tick").getAsLong();
            long doneTick = completed.isEmpty() ? 0 : completed.get(0).get("tick").getAsLong();
            long serverMs = (doneTick - startTick) * 50L;
            long aimMs = targetMs - COMPENSATION_MS;
            int uses = c.hx().events(since, "use_block").size();
            List<String> announces = new ArrayList<>();
            for (String cmd : c.commands()) {
                if (cmd.startsWith("pc SS ")) {
                    announces.add(cmd.substring(3));
                }
            }
            int learned = c.onClient(mc -> (Integer) Mod.staticCall(LAYOUTS, "simonRounds"));
            boolean pending = c.onClient(mc -> (Boolean) Mod.field(F, "roundVerdictPending"));
            int expectedPresses = rounds * (rounds + 1) / 2;
            String record = String.format(Locale.ROOT, "%d-round device, planned %d rounds at the first click: completed %s,"
                            + " presses %d (wrong %d, expected %d), start presses %d, server use_block %d, start to completed"
                            + " %d ms (aim %d = Target %d - %d, off by %+d), breaks %d, announces %s, learned %d rounds,"
                            + " verdict pending %s",
                    rounds, planned, done, presses.size(), wrong, expectedPresses, starts.size(), uses, serverMs, aimMs,
                    targetMs, COMPENSATION_MS, serverMs - aimMs, c.events("ss.broke").size(), announces, learned, pending);
            c.note(record);
            System.out.println("[1027-ss] " + record);
            c.check(done, "the device did not complete");
            c.check(c.events("ss.broke").isEmpty(), "the device broke: " + c.events("ss.broke"));
            c.check(wrong == 0, wrong + " wrong press(es)");
            c.check(presses.size() == expectedPresses, presses.size() + " grid presses, expected " + expectedPresses);
            c.check(uses == expectedPresses + 1, uses + " use_block packets reached the server, expected "
                    + (expectedPresses + 1) + " (the presses + the test's start press)");
            c.check(starts.size() == 1, "the start button was pressed " + starts.size()
                    + " times - Auto Restart restarted a device that had finished");
            c.check(learned == rounds, "the mod holds " + learned + " rounds after a " + rounds + "-round device");
            c.check(!pending, "a round verdict is still pending after the device completed");
            if (judgeTime) {
                c.check(planned == rounds, "the mod planned " + planned + " rounds at the first click for a "
                        + rounds + "-round device");
                c.check(Math.abs(serverMs - aimMs) <= TOLERANCE_MS, "start to completed took " + serverMs
                        + " ms, more than " + TOLERANCE_MS + " ms from the aim " + aimMs);
            }
        } finally {
            c.onClient(mc -> {
                Mod.set(SS_CFG, "setEnabled", false);
                Mod.staticCall(LAYOUTS, "resetSeen");
                return null;
            });
            c.hx().call("boss.ss.clear");
            c.hx().sidebarClear();
            c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
        }
    }
}

package dev.testkit.gametest.boss;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.HxKit;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Locale;

/**
 * Mod branch ss-stim-1018 (killer560's to-do, 2026-10-18: "For autoss add a stim option where it will trace the path it
 * just saw after doing lets say the first two set it would 'practice' those two buttons again. Also can you have it
 * carry more momentum through clicks"). Auto Solve in ROTATE mode plays a whole 4-round device (hx/boss/HxSimonSays)
 * facing it, the same set-up as 831, under GrimAC.
 *
 * <p>Judged from the SERVER's record: the device completes, every press is correct (a wrong one breaks it), and exactly
 * 10 grid use_block packets plus the test's own start press reach the server - so Stim, which only looks, sent nothing.
 * Then the mod's own counters prove the new path acted: Stim reached buttons between rounds; Momentum fired on the face,
 * glided on after clicks and carried speed. The new settings do not exist on main, so those cases fail there.
 * {@code 1018-ss-rotate-fast-off} is the control: Momentum off (never touched, so it runs on main too), same pacing.
 */
final class SsStimMomentumCases {

    private static final String SS_CFG = SimonSaysCases.CONFIG;
    private static final String F = SimonSaysCases.FEATURE;

    private SsStimMomentumCases() {
    }

    static void register(Session s) {
        if (!Mod.isCheat()) {
            return;
        }
        s.test("1018-ss-rotate-fast-off", c -> play(c, 61, true, -1, false, 0));
        s.test("1018-ss-momentum-high", c -> play(c, 62, true, 100, false, 0));
        s.test("1018-ss-momentum-target", c -> play(c, 63, false, 50, false, 0));
        s.test("1018-ss-stim", c -> play(c, 64, true, -1, true, 0));
        s.test("1018-ss-stim-momentum", c -> play(c, 65, true, 100, true, 0));
    }

    private static int counter(Session c, String field) {
        return c.onClient(mc -> (Integer) Mod.field(F, field));
    }

    /**
     * @param fixedDelay Fixed Delay pacing at 150 ms between clicks (the demanding case), else the default Target pacing
     * @param momentum   0-100, or -1 to leave the setting alone (the control, which must also run on main)
     */
    private static void play(Session c, int seed, boolean fixedDelay, int momentum, boolean stim, int stimButtons)
            throws Exception {
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        HxKit.Settings on = new HxKit.Settings(c)
                .custom(() -> Mod.staticCall("util.SkyblockGate", "setEnabled", false),
                        () -> Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas))
                .with(SS_CFG, "SolverEnabled", true).with(SS_CFG, "AutoRestartEnabled", false)
                .with(SS_CFG, "AutoStartEnabled", false).with(SS_CFG, "TriggerBotEnabled", false)
                .with(SS_CFG, "AutoSolveRotate", true).with(SS_CFG, "AutoSolveEnabled", false)
                .with(SS_CFG, "AutoSolveFixedDelayMode", fixedDelay).with(SS_CFG, "AutoSolveFixedDelayMs", 150);
        try (on) {
            if (momentum >= 0) {
                on.with(SS_CFG, "AutoSolveMomentum", momentum);
            }
            if (stim) {
                // Since mod doc-puzzles-1101 Stim retraces the whole round (no Stim Buttons setting).
                on.with(SS_CFG, "AutoSolveStim", true);
            }
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> "F7".equals(Mod.staticCall("secrets.DungeonState", "getFloor")), 100);
            c.onClient(mc -> {
                Mod.set(SS_CFG, "setEnabled", false);
                return null;
            });
            c.hx().call("boss.ss.build", "rounds", 4, "line", true, "seed", seed);
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

            boolean newPaths = momentum > 0 || stim;
            int queued0 = stim ? counter(c, "stimButtonsQueued") : 0;
            // A session's first device ends round 4 with its round count not yet seen: unless "completed a device!" lands
            // with the last click, the mod waits for a verdict instead of calling the device done, and round 4 gets a
            // rehearsal too (1 + 3 + 4 + 5 = 13 buttons). Which one happens is timing (3 on one run, 4 on another).
            boolean roundsKnown = c.onClient(mc -> (Boolean) Mod.staticCall(SimonSaysCases.LAYOUTS, "simonRoundsSeen"));
            int[] before = newPaths ? new int[] {counter(c, "stimRehearsalsStarted"), counter(c, "stimButtonsTraced"),
                    counter(c, "stimCutShort"), counter(c, "momentumFaceFires"), counter(c, "momentumGlideFrames"),
                    counter(c, "momentumCarryFrames")} : null;

            long since = c.hx().head();
            SimonSaysCases.click(c, SimonSaysCases.START);
            c.waitUntil("the device to start (Hx)", mc -> !"IDLE".equals(SimonSaysCases.state(c).get("phase").getAsString()), 60);
            // Auto Solve only after the test's own start press (831's reason: idle look would turn the camera off it).
            c.onClient(mc -> {
                Mod.set(SS_CFG, "setAutoSolveEnabled", true);
                return null;
            });
            boolean done = false;
            try {
                c.waitUntil("ss.completed (Hx) with Auto Solve (Rotate) playing", mc -> !c.events("ss.completed").isEmpty(), 1600);
                done = true;
            } finally {
                c.onClient(mc -> {
                    Mod.set(SS_CFG, "setAutoSolveEnabled", false);
                    return null;
                });
            }
            // A few ticks for the last press's echo, then the record.
            c.ctx().waitTicks(10);
            List<JsonObject> presses = c.events("ss.press");
            int wrong = 0;
            for (JsonObject p : presses) {
                if (!p.get("correct").getAsBoolean()) {
                    wrong++;
                }
            }
            int uses = c.hx().events(since, "use_block").size();
            long firstTick = presses.isEmpty() ? 0 : presses.get(0).get("tick").getAsLong();
            List<JsonObject> completed = c.events("ss.completed");
            long doneTick = completed.isEmpty() ? 0 : completed.get(0).get("tick").getAsLong();
            String record = String.format(Locale.ROOT, "pacing %s, momentum %s, stim %s: completed %s, presses %d (wrong %d),"
                            + " server use_block %d (1 by the test), first press to completed %d ticks, breaks %d",
                    fixedDelay ? "fixed 150ms" : "target", momentum < 0 ? "untouched" : momentum,
                    stim ? "on/" + stimButtons : "off", done, presses.size(), wrong, uses, doneTick - firstTick,
                    c.events("ss.broke").size());
            c.note(record);
            System.out.println("[1018-ss] " + record);
            c.check(done, "the device did not complete");
            c.check(c.events("ss.broke").isEmpty(), "the device broke: " + c.events("ss.broke"));
            c.check(wrong == 0, wrong + " wrong press(es)");
            c.check(presses.size() == 10, presses.size() + " grid presses for a 4-round device, expected 10");
            // Exactly: Stim looks and never clicks, and Momentum never sends a second (early or retried) click.
            c.check(uses == 11, uses + " use_block packets reached the server, expected exactly 11 (10 + the start press)");

            if (newPaths) {
                int[] after = {counter(c, "stimRehearsalsStarted"), counter(c, "stimButtonsTraced"),
                        counter(c, "stimCutShort"), counter(c, "momentumFaceFires"), counter(c, "momentumGlideFrames"),
                        counter(c, "momentumCarryFrames")};
                String acted = String.format(Locale.ROOT, "stim rehearsals %d, buttons traced %d, cut short %d; momentum"
                                + " face fires %d, glide frames %d, carry frames %d", after[0] - before[0],
                        after[1] - before[1], after[2] - before[2], after[3] - before[3], after[4] - before[4],
                        after[5] - before[5]);
                c.note(acted);
                System.out.println("[1018-ss] " + acted);
                if (stim) {
                    // Rounds 1-3 each end with a rehearsal (not the last round, once the device's round count is known).
                    int rehearsals = after[0] - before[0];
                    int wantRehearsals = roundsKnown || rehearsals == 3 ? 3 : 4;
                    c.check(rehearsals == wantRehearsals, (after[0] - before[0]) + " stim rehearsals, expected "
                            + wantRehearsals + " (round count known before: " + roundsKnown + ")");
                    c.check(after[1] - before[1] >= 3, "stim reached only " + (after[1] - before[1]) + " button(s)");
                    // The whole round, first to last, then back to the first: rounds 1-3 queue 1, 2+1 and 3+1.
                    int queued = counter(c, "stimButtonsQueued") - queued0;
                    c.note("stim queued " + queued + " button(s) over rounds 1-3");
                    int wantQueued = wantRehearsals == 3 ? 8 : 13;
                    c.check(queued == wantQueued, queued + " buttons queued, expected " + wantQueued
                            + " (each round whole, first to last, then its first again)");
                }
                if (momentum > 0) {
                    c.check(after[3] - before[3] == 10, (after[3] - before[3]) + " momentum clicks, expected all 10");
                    c.check(after[5] - before[5] > 0, "momentum never carried any speed into a turn");
                    if (fixedDelay) {
                        c.check(after[4] - before[4] > 0, "momentum never glided on after a click");
                    }
                }
            }
        } finally {
            c.onClient(mc -> {
                Mod.set(SS_CFG, "setEnabled", false);
                return null;
            });
            // Off the device before it is cleared (a fall through the cleared floor drew GroundSpoof + Simulation at
            // the next case's start, 1018-ss-stim-momentum on 26.3, 2026-11-01).
            c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
            c.ctx().waitTicks(5);
            c.hx().call("boss.ss.clear");
            c.hx().sidebarClear();
        }
    }
}

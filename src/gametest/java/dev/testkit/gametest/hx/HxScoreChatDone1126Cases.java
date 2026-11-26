package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.util.List;
import java.util.Map;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * 1126-hx-scorecalc-chatdone (mod branch score-puzzle-chatdone-1126, killer560 2026-11-26: Blaze and Tic Tac Toe finish
 * as the party enters the boss room but the tab list shows them a few seconds later). With "Count Blaze And Tic Tac Toe
 * From Chat" ON, Hypixel's own PUZZLE SOLVED line counts that puzzle as completed for the score the tick it arrives; the
 * tab list's later checkmark adds nothing; a tab list failure after the line takes it back. OFF (the default) changes
 * nothing. A fresh config has the switch OFF and it survives a save and load.
 */
final class HxScoreChatDone1126Cases {

    private static final String SCF = "scorecalc.ScoreCalculatorFeature";
    private static final String CFG = "scorecalc.ScoreCalculatorConfig";
    private static final String TTT_LINE = "PUZZLE SOLVED! HxMateA tied Tic Tac Toe! Good job!";
    private static final String SIM_BLAZE_LINE = "PUZZLE SOLVED! HxMateB completed Higher Blaze! Good job!";
    private static final String PARTY_BLAZE = "§9Party §8> §b[MVP§c+§b] HxMateA§f: Blaze puzzle solved!";
    private static final String ALL_BLAZE = "§7[VIP] HxEvil§f: Blaze puzzle solved!";
    private static final String FAIL_BLAZE = "PUZZLE FAIL! HxMateA killed a Blaze in the wrong order! Yikes!";
    private static final String RESET_BLAZE = "You used the Architect's First Draft to reset Higher Or Lower!";
    private static final String WEIRDOS_LINE = "PUZZLE SOLVED! HxMateB completed Three Weirdos! Good job!";
    private static final String OPEN = "§r§e✦";
    private static final String DONE = "§r§a✔";
    private static final String FAIL = "§r§c✖";

    private HxScoreChatDone1126Cases() {
    }

    static void register(Session s) {
        s.test("1126-hx-scorecalc-chatdone", HxScoreChatDone1126Cases::run);
    }

    private static void tab(Session c, String ttt, String blaze) {
        dungeon(c, "secrets", 20, "secretsPercent", "40.0", "crypts", 2, "rooms", 10, "deaths", 0, "puzzles",
                List.of(Map.of("name", "Tic Tac Toe", "state", ttt), Map.of("name", "Higher Or Lower", "state", blaze)));
    }

    private static int done() {
        Object in = Mod.staticCall(SCF, "currentInputs");
        return in == null ? -1 : ((Number) Mod.call(in, "puzzlesCompleted")).intValue();
    }

    private static int failed() {
        Object in = Mod.staticCall(SCF, "currentInputs");
        return in == null ? -1 : ((Number) Mod.call(in, "puzzlesFailed")).intValue();
    }

    private static void newRun(Session c) {
        c.ctx().runOnClient(mc -> {
            try {
                Object tracker = Mod.field(SCF, "RUN");
                java.lang.reflect.Field f = tracker.getClass().getDeclaredField("level");
                f.setAccessible(true);
                f.set(tracker, null);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        c.ctx().waitTicks(25);
    }

    private static void run(Session c) {
        boolean fresh = c.onClient(mc -> !(Boolean) Mod.call(Mod.staticCall(CFG, "getInstance"), "isChatPuzzleDone"));
        c.check(fresh, "a default config has Count Blaze And Tic Tac Toe From Chat ON");

        try (Settings set = new Settings(c).with(CFG, "Enabled", true).with(CFG, "ChatPuzzleDone", false)) {
            tab(c, OPEN, OPEN);
            newRun(c);
            c.waitUntil("a score estimate", mc -> done() >= 0, 100);
            c.ctx().waitTicks(12);
            int d0 = c.onClient(mc -> done());
            c.hx().chat(TTT_LINE);
            c.hx().chat(PARTY_BLAZE);
            c.ctx().runOnClient(mc -> Mod.staticCall(SCF, "onBlazeCleared"));
            c.ctx().waitTicks(25);
            c.check(c.onClient(mc -> done()) == d0, "switch OFF: the Tic Tac Toe line, a party Blaze line or a last-blaze kill moved completed " + d0 + " -> "
                    + c.onClient(mc -> done()));
        }

        try (Settings set = new Settings(c).with(CFG, "Enabled", true).with(CFG, "ChatPuzzleDone", true)) {
            tab(c, OPEN, OPEN);
            newRun(c);
            c.waitUntil("a score estimate", mc -> done() >= 0, 100);
            c.ctx().waitTicks(12);
            int d0 = c.onClient(mc -> done());
            // A puzzle that is neither of the two, a forged chat line and a fail line count nothing.
            c.hx().chat(WEIRDOS_LINE);
            c.hx().chat("§7[VIP] HxEvil§f: " + TTT_LINE);
            c.hx().chat("PUZZLE FAIL! HxMateA failed Tic Tac Toe! Good job!");
            c.ctx().waitTicks(25);
            c.check(c.onClient(mc -> done()) == d0, "Weirdos/forged/fail lines moved completed to " + c.onClient(mc -> done()));

            c.hx().chat(TTT_LINE);
            c.waitUntil("completed " + (d0 + 1) + " as the Tic Tac Toe line arrives (tab still open)",
                    mc -> done() == d0 + 1, 10);
            // Hypixel never sends a PUZZLE SOLVED line for Blaze (the sim's wording counts nothing); a party line does.
            c.hx().chat(SIM_BLAZE_LINE);
            c.hx().chat(ALL_BLAZE);
            c.ctx().waitTicks(25);
            c.check(c.onClient(mc -> done()) == d0 + 1, "a sim-style solved line or an All-chat Blaze line counted: " + c.onClient(mc -> done()));
            c.hx().chat(PARTY_BLAZE);
            c.waitUntil("completed " + (d0 + 2) + " after the party Blaze line", mc -> done() == d0 + 2, 10);
            char st = c.onClient(mc -> (Character) Mod.staticCall(SCF, "puzzleState", "Higher Blaze"));
            c.check(st == '✔', "puzzleState(Higher Blaze) is '" + st + "' after the line, want done");

            // The tab list catches up: still d0 + 2, not d0 + 4.
            tab(c, DONE, DONE);
            c.ctx().waitTicks(30);
            c.check(c.onClient(mc -> done()) == d0 + 2, "tab list caught up: completed " + c.onClient(mc -> done())
                    + ", want " + (d0 + 2));

            // Open again, the line, then the tab list says FAILED: the tab list wins.
            tab(c, OPEN, OPEN);
            newRun(c);
            int e0 = c.onClient(mc -> done());
            c.hx().chat(TTT_LINE);
            c.waitUntil("completed " + (e0 + 1), mc -> done() == e0 + 1, 10);
            tab(c, FAIL, OPEN);
            c.ctx().waitTicks(30);
            c.check(c.onClient(mc -> done()) == e0 && c.onClient(mc -> failed()) == 1,
                    "tab FAILED after the line: completed " + c.onClient(mc -> done()) + " (want " + e0 + "), failed "
                            + c.onClient(mc -> failed()) + " (want 1)");

            // A new run forgets a counted line.
            c.hx().chat(PARTY_BLAZE);
            c.waitUntil("blaze counted", mc -> done() == e0 + 1, 10);
            newRun(c);
            tab(c, OPEN, OPEN);
            c.ctx().waitTicks(30);
            c.check(c.onClient(mc -> done()) == 0, "a new run kept a chat-counted puzzle: " + c.onClient(mc -> done()));

            // Tic Tac Toe by party line (both wordings), All chat nothing, the solver's win/draw hook counts.
            tab(c, OPEN, OPEN);
            newRun(c);
            int g0 = c.onClient(mc -> done());
            c.hx().chat("§7[VIP] HxEvil§f: ttt solved");
            c.hx().chat("§9Party §8> §b[MVP§c+§b] HxMateA§f: ttt failed");
            c.ctx().waitTicks(20);
            c.check(c.onClient(mc -> done()) == g0, "an All-chat ttt line or a party ttt failed line counted");
            c.hx().chat("§9Party §8> §b[MVP§c+§b] HxMateA§f: TTT done!");
            c.waitUntil("completed " + (g0 + 1) + " after a party ttt line", mc -> done() == g0 + 1, 10);
            newRun(c);
            c.ctx().runOnClient(mc -> Mod.staticCall(SCF, "onTicTacToeEnded"));
            c.waitUntil("completed 1 after the solver's own game end", mc -> done() == g0 + 1, 10);

            // Blaze: a fail line takes the party line back and blocks the next one; Architect's reset unblocks.
            tab(c, OPEN, OPEN);
            newRun(c);
            int f0 = c.onClient(mc -> done());
            c.hx().chat(PARTY_BLAZE);
            c.waitUntil("completed " + (f0 + 1) + " (party Blaze line)", mc -> done() == f0 + 1, 10);
            c.hx().chat(FAIL_BLAZE);
            c.waitUntil("completed back to " + f0 + " after PUZZLE FAIL Blaze", mc -> done() == f0, 10);
            c.hx().chat(PARTY_BLAZE);
            c.ctx().runOnClient(mc -> Mod.staticCall(SCF, "onBlazeCleared"));
            c.ctx().waitTicks(25);
            c.check(c.onClient(mc -> done()) == f0, "a party line or a last-blaze kill after PUZZLE FAIL counted: " + c.onClient(mc -> done()));
            c.hx().chat(RESET_BLAZE);
            c.ctx().waitTicks(10);
            c.ctx().runOnClient(mc -> Mod.staticCall(SCF, "onBlazeCleared"));
            c.waitUntil("completed " + (f0 + 1) + " (own last blaze killed after the reset)", mc -> done() == f0 + 1, 10);
            c.hx().chat(RESET_BLAZE);
            c.waitUntil("completed " + f0 + " after the Architect reset line", mc -> done() == f0, 10);

            boolean kept = c.onClient(mc -> {
                Mod.call(Mod.staticCall(CFG, "getInstance"), "save");
                Mod.staticCall(CFG, "load");
                return (Boolean) Mod.call(Mod.staticCall(CFG, "getInstance"), "isChatPuzzleDone");
            });
            c.check(kept, "ChatPuzzleDone ON did not survive save + load");
            c.note("OFF: no change; ON: Tic Tac Toe line and party Blaze line +1 each at once, All-chat/sim lines nothing, fail line and reset line take Blaze back, last-blaze kill counts, tab catch-up adds nothing,"
                    + " tab FAIL wins, new run clears, save/load keeps the switch");
        }
    }
}

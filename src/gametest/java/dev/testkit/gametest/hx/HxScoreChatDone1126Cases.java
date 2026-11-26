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
    private static final String BLAZE_LINE = "PUZZLE SOLVED! HxMateB completed Higher Or Lower! Good job!";
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
            c.ctx().waitTicks(25);
            c.check(c.onClient(mc -> done()) == d0, "switch OFF: the Tic Tac Toe line moved completed " + d0 + " -> "
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
            c.hx().chat(BLAZE_LINE);
            c.waitUntil("completed " + (d0 + 2) + " after the Higher Or Lower line", mc -> done() == d0 + 2, 10);
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
            c.hx().chat(BLAZE_LINE);
            c.waitUntil("blaze counted", mc -> done() == e0 + 1, 10);
            newRun(c);
            tab(c, OPEN, OPEN);
            c.ctx().waitTicks(30);
            c.check(c.onClient(mc -> done()) == 0, "a new run kept a chat-counted puzzle: " + c.onClient(mc -> done()));

            boolean kept = c.onClient(mc -> {
                Mod.call(Mod.staticCall(CFG, "getInstance"), "save");
                Mod.staticCall(CFG, "load");
                return (Boolean) Mod.call(Mod.staticCall(CFG, "getInstance"), "isChatPuzzleDone");
            });
            c.check(kept, "ChatPuzzleDone ON did not survive save + load");
            c.note("OFF: no change; ON: Tic Tac Toe and Higher Or Lower lines +1 each at once, tab catch-up adds nothing,"
                    + " tab FAIL wins, new run clears, save/load keeps the switch");
        }
    }
}

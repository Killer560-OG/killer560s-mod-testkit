package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

/**
 * 1122-logic-quizgap-pick (mod ap-quizfill-1122): which room Dungeons Autopilot fills a Quiz countdown with, on the round
 * trips (there + route + back) his 11-22 F7 logged. At 20:03:21 (94 ticks to question 2) Steps' 79 was "too long" for the
 * 20-tick spare, at 20:03:29 (86 to question 3) Duncan's 95 was over by 9, and both times it went back and stood 83 and 73
 * ticks at the Quiz. Now: a room that fits with the spare is still chosen as before (most unfound, then quickest); with none,
 * the longest that still keeps 10 ticks in hand is taken instead of standing there; past that, none.
 */
public final class QuizGap1122Cases {

    private static final String AS = "autosecret.AutoSecretFeature";

    private QuizGap1122Cases() {
    }

    private static int pick(double[] totals, int[] unfound, long left, boolean loose) {
        return ((Number) Mod.staticCall(AS, "quizGapPickForTest", totals, unfound, left, loose)).intValue();
    }

    static void pick(LogicCase c) {
        // 20:03:19, 138 ticks to question 2: Bridges, Atlas, Big Red Flag, Mines, Steps, Duncan, Archway, New Trap.
        double[] q2a = {192, 173, 106, 320, 87, 95, 130, 146};
        int[] u2a = {6, 6, 2, 10, 1, 1, 3, 3};
        c.eq("138 left: Big Red Flag (fits with the spare, most unfound) as his run picked", 2, pick(q2a, u2a, 138, false));
        // 20:03:21, 94 ticks: Bridges, Atlas, Mines, Steps, Duncan, Archway, New Trap.
        double[] q2 = {206, 185, 324, 79, 109, 146, 152};
        int[] u2 = {6, 6, 10, 1, 1, 3, 3};
        c.eq("94 left: nothing fits with the 20-tick spare", -1, pick(q2, u2, 94, false));
        c.eq("94 left: Steps (79) is taken rather than standing 83 ticks at the Quiz", 3, pick(q2, u2, 94, true));
        // 20:03:29, 86 ticks: Bridges, Atlas, Mines, Duncan, Archway, New Trap.
        double[] q3 = {196, 181, 320, 95, 142, 146};
        int[] u3 = {6, 6, 10, 1, 3, 3};
        c.eq("86 left: nothing fits with the spare", -1, pick(q3, u3, 86, false));
        c.eq("86 left: Duncan (95) is over the countdown and never taken: a route is not run late", -1, pick(q3, u3, 86, true));
        // The loose pick still keeps half the spare (10 ticks): 1017 sim 11-22, an exact 95-of-95 fit came back 22 late.
        c.eq("95 left, shortest 95: none, an exact fit is too tight", -1, pick(new double[]{95, 120}, new int[]{1, 4}, 95, true));
        c.eq("the longest that keeps 10 ticks wins", 1,
                pick(new double[]{115, 102, 98}, new int[]{9, 1, 1}, 120, true));
    }
}

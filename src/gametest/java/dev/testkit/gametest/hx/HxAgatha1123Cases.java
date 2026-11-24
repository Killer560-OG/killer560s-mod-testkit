package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.util.List;

/**
 * Mod beeh-sweep-agatha-1123 (killer560, 2026-11-23: "a contest overlay option for how i am doing in an agatha contest").
 * 1123-foraging-agatha feeds the sidebar lines of a contest over time to AgathaContest.onSidebar and asserts the HUD
 * lines: time, score and tier, the next tier's need, the rate after 30 s, a fast pace that projects the next tier and a
 * slow one that does not (with the rate it needs), the Starlyn wording, and that no contest block shows nothing (and the
 * final result stays only while Show Final Result is on).
 */
final class HxAgatha1123Cases {

    private static final String A = "foraging.AgathaContest";

    private HxAgatha1123Cases() {
    }

    static void register(Session s) {
        s.test("1123-foraging-agatha", HxAgatha1123Cases::agatha);
    }

    private static List<String> feed(Session c, long at, String... sidebar) {
        return c.onClient(mc -> {
            Mod.staticCall(A, "onSidebar", List.of(sidebar), at);
            @SuppressWarnings("unchecked") List<String> l = (List<String>) Mod.staticCall(A, "lines");
            return l.stream().map(x -> x.replaceAll("§.", "")).toList();
        });
    }

    private static boolean has(List<String> l, String part) {
        return l.stream().anyMatch(x -> x.contains(part));
    }

    private static void agatha(Session c) {
        boolean wasOn = c.onClient(mc -> (Boolean) Mod.call(Mod.field(A, "CFG"), "isEnabledRaw"));
        try {
            c.ctx().runOnClient(mc -> {
                Mod.call(Mod.field(A, "CFG"), "setEnabled", true);
                Mod.call(Mod.field(A, "CFG"), "set", "final", false);
            });
            String whispers = "Whispers: 10.8k (+24)";
            // No contest: nothing.
            List<String> l = feed(c, 1_000, whispers, "Purse: 5");
            c.check(l.isEmpty(), "no contest but lines shown: " + l);
            // Start: time, score, tier, need; no rate yet.
            l = feed(c, 10_000, whispers, "Agatha's Contest 4m00s", " EPIC with 2k", " Legendary requires +999");
            c.check(has(l, "4m00s") && has(l, "Score: 2k") && has(l, "(EPIC)") && has(l, "LEGENDARY: +999"), "start lines: " + l);
            c.check(!has(l, "Rate:"), "a rate before any change: " + l);
            // 30 s later, fast: 2000 -> 2600 = 1200/min, projected past 2999 -> LEGENDARY, needs 114/min.
            l = feed(c, 40_000, whispers, "Agatha's Contest 3m30s", " EPIC with 2.6k", " Legendary requires +399");
            c.check(has(l, "Rate: 1.2k/min") && has(l, "Projected: 6.8k (LEGENDARY)") && has(l, "needs 114/min"), "fast lines: " + l);
            // Slow: restart the contest, 2000 -> 2100 in 30 s = 200/min, projected 2800 < 2999: still EPIC, needs 257/min.
            feed(c, 100_000, "Agatha's Contest 4m00s", " EPIC with 2k", " Legendary requires +999");
            l = feed(c, 130_000, "Agatha's Contest 3m30s", " EPIC with 2.1k", " Legendary requires +899");
            c.check(has(l, "Rate: 200/min") && has(l, "Projected: 2.8k (EPIC)") && has(l, "needs 257/min"), "slow lines: " + l);
            // Starlyn wording: time from "Ends in", tier lines the same.
            feed(c, 200_000, "Starlyn Contests", " Ends in: 2m 37s", " RARE with 900", " Epic requires +100");
            l = feed(c, 200_500, "Starlyn Contests", " Ends in: 2m 37s", " RARE with 900", " Epic requires +100");
            c.check(has(l, "2m37s") && has(l, "(RARE)") && has(l, "EPIC: +100"), "starlyn lines: " + l);
            // Contest over with Show Final Result OFF: nothing; ON: a "Contest over" block with the last numbers.
            l = feed(c, 210_000, whispers);
            c.check(l.isEmpty(), "final OFF but lines stayed: " + l);
            c.ctx().runOnClient(mc -> Mod.call(Mod.field(A, "CFG"), "set", "final", true));
            feed(c, 300_000, "Agatha's Contest 0m05s", " EPIC with 3k", " Legendary requires +10");
            l = feed(c, 306_000, whispers);
            c.check(has(l, "Contest over") && has(l, "Score: 3k"), "final ON lines: " + l);
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.call(Mod.field(A, "CFG"), "setEnabled", wasOn);
                Mod.call(Mod.field(A, "CFG"), "load");
            });
        }
    }
}

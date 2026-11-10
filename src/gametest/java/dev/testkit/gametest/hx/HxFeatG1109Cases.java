package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod branch featG-1109 (feature sweep item 17): Carry Tracker. Each case drives the real signal and
 * asserts the feature acted: a run counted and saved (and still there after a reload).
 */
final class HxFeatG1109Cases {

    private static final String CT = "carrytracker.CarryTracker";
    private static final String CT_CFG = "carrytracker.CarryTrackerConfig";

    private HxFeatG1109Cases() {
    }

    static void register(Session s) {
        HxCarryHud1109Cases.register(s);
        s.test("1109-carry-count", HxFeatG1109Cases::carryCount);
        s.test("1109-carry-run", HxFeatG1109Cases::carryRun);
    }

    private static long count(Session c, String name) {
        return ((Number) c.onClient(mc -> Mod.staticCall(CT, "count", name))).longValue();
    }

    private static List<?> recent(Session c, int n) {
        return (List<?>) c.onClient(mc -> Mod.staticCall(CT, "recent", n));
    }

    private static void track(Session c, String name, boolean on) {
        run(c, () -> Mod.call(Mod.cfg(CT_CFG), on ? "add" : "remove", name));
    }

    private static void record(Session c, long totalMs, int score, List<String> party) {
        run(c, () -> Mod.staticCall(CT, "recordForTest", "F7", totalMs, score, party));
        run(c, () -> Mod.staticCall(CT, "drainForTest"));
    }

    private static void carryCount(Session c) {
        try (Settings set = new Settings(c).with(CT_CFG, "Enabled", true)) {
            track(c, "HxMateA", true);
            long before = count(c, "HxMateA");
            record(c, 600_000L, 300, List.of("hxmatea", "Zed"));
            c.check(count(c, "HxMateA") == before + 1, "tracked HxMateA (party spelled hxmatea) was not counted: " + count(c, "HxMateA"));
            c.check(count(c, "Zed") == 0, "an untracked name was counted: " + count(c, "Zed"));
            record(c, 500_000L, 310, List.of("Zed"));
            c.check(count(c, "HxMateA") == before + 1, "a run without HxMateA changed his count");
            record(c, 400_000L, 305, List.of("HxMateA"));
            long two = count(c, "HxMateA");
            c.check(two == before + 2, "second run: " + two + " not " + (before + 2));
            List<?> hist = recent(c, 5);
            c.check(hist.size() >= 2 && "HxMateA".equals(Mod.call(hist.get(0), "player"))
                    && ((Number) Mod.call(hist.get(0), "totalMs")).longValue() == 400_000L, "history newest first: " + hist);
            // Saved to the shared store: a fresh load reads the same count.
            run(c, () -> Mod.staticCall(CT, "reloadForTest"));
            c.check(count(c, "HxMateA") == two, "after a reload the count is " + count(c, "HxMateA") + ", saved " + two);
            c.note("count " + before + " -> " + two + ", history " + hist.size() + ", reload kept it");
            track(c, "HxMateA", false);
        }
    }

    private static void playRun(Session c) {
        int before = c.onClient(mc -> ((Number) Mod.staticCall("runsummary.RunHistoryStore", "size")).intValue());
        run(c, () -> Mod.staticCall("runsummary.RunSummaryFeature", "resetRun"));
        send(c, "chat.run-start");
        c.waitUntil("a run is tracked", mc -> (Boolean) Mod.staticCall("runsummary.RunSummaryFeature", "isTrackingRun"), 40);
        c.ctx().waitTicks(5);
        send(c, "chat.extra-stats");
        c.hx().chat("             Team Score: 300 (S+)");
        c.waitUntil("the run was stored", mc -> ((Number) Mod.staticCall("runsummary.RunHistoryStore", "size")).intValue() > before, 400);
        run(c, () -> Mod.staticCall(CT, "drainForTest"));
    }

    private static void carryRun(Session c) {
        String me = self(c);
        try (Settings set = new Settings(c).with("runsummary.RunSummaryConfig", "Enabled", true)) {
            dungeon(c);
            track(c, me, true);
            long before = count(c, me);
            // Switch OFF: a real finished run counts nothing.
            playRun(c);
            c.check(count(c, me) == before, "Carry Tracker OFF counted a run: " + count(c, me));
            run(c, () -> Mod.set(CT_CFG, "setEnabled", true));
            playRun(c);
            c.waitUntil("the finished run counted for " + me, mc -> ((Number) Mod.staticCall(CT, "count", me)).longValue() == before + 1, 100);
            Object e = recent(c, 1).get(0);
            c.check(me.equals(Mod.call(e, "player")) && ((Number) Mod.call(e, "score")).intValue() == 300, "history entry " + e);
            c.note("real run end counted " + me + ": " + before + " -> " + count(c, me) + ", " + e);
            run(c, () -> Mod.set(CT_CFG, "setEnabled", false));
            track(c, me, false);
            run(c, () -> Mod.staticCall("runsummary.RunSummaryFeature", "resetRun"));
        }
    }
}

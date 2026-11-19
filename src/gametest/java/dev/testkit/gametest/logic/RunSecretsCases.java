package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

/**
 * 1118-logic-runsecrets (playersecrets-1118): replays API timelines through Run Stats' secret counting
 * ({@code runstats.SecretRun}). The owner's own row is the Stats block's exact "Secrets Found"; everyone else is a
 * lifetime-total delta shown only once own + deltas == the tab list's team total. In his 36 logged runs the old
 * delta was wrong for him in 18 (stale start/end reads, failed requests read as 0).
 */
final class RunSecretsCases {

    private RunSecretsCases() {
    }

    private static Object fresh(String me) {
        Mod.staticCall("runstats.RunStatsTracker", "resetRun");
        Object run = Mod.staticCall("runstats.RunStatsTracker", "secretRun");
        Mod.call(run, "setSelf", me);
        return run;
    }

    private static int secrets(Object run, String name) {
        return ((Number) Mod.call(run, "secrets", name)).intValue();
    }

    private static boolean flag(Object run, String m) {
        return (Boolean) Mod.call(run, m);
    }

    static void run(LogicCase c) {
        // 1. A stale end read first (the API still holds the pre-run total), then the fresh one.
        Object r = fresh("PsMe1");
        Mod.call(r, "gotStart", "PsA1", 100L);
        Mod.call(r, "gotStart", "PsB1", 200L);
        Mod.call(r, "markEnded", 9);
        Mod.call(r, "setOwn", 5);
        Mod.call(r, "gotEnd", "PsA1", 100L);
        Mod.call(r, "gotEnd", "PsB1", 200L);
        c.check("stale end reads are pending, not shown as 0", flag(r, "pending") && secrets(r, "PsA1") == -1);
        c.eq("the player's own count is exact at once", 5, secrets(r, "PsMe1"));
        Mod.call(r, "gotEnd", "PsA1", 103L);
        c.check("one player caught up, the sum still falls short", flag(r, "pending") && secrets(r, "PsB1") == -1);
        Mod.call(r, "gotEnd", "PsB1", 201L);
        c.check("own 5 + 3 + 1 == team 9 reconciles", flag(r, "reconciled") && !flag(r, "pending"));
        c.eq("player A", 3, secrets(r, "PsA1"));
        c.eq("player B", 1, secrets(r, "PsB1"));
        c.eq("late line lists everyone", "PsMe1 5 | PsA1 3 | PsB1 1", Mod.call(r, "line"));

        // 2. A start read that missed the previous run's secrets is lifted to the total already seen for that player.
        Object prev = fresh("PsMe2");
        Mod.call(prev, "gotEnd", "PsA2", 150L);
        Object r2 = fresh("PsMe2");
        Mod.call(r2, "gotStart", "PsA2", 120L);
        Mod.call(r2, "markEnded", 6);
        Mod.call(r2, "setOwn", 4);
        Mod.call(r2, "gotEnd", "PsA2", 152L);
        c.check("stale start lifted: 4 + (152 - 150) == 6", flag(r2, "reconciled") && secrets(r2, "PsA2") == 2);

        // 3. A start that stayed stale-low can never be fixed by an end read: hopeless, shown as "?", never a wrong number.
        Object r3 = fresh("PsMe3");
        Mod.call(r3, "gotStart", "PsA3", 100L);
        Mod.call(r3, "markEnded", 9);
        Mod.call(r3, "setOwn", 5);
        Mod.call(r3, "gotEnd", "PsA3", 130L);
        c.check("sum over the team total is hopeless and not pending", flag(r3, "hopeless") && !flag(r3, "pending")
                && secrets(r3, "PsA3") == -1 && secrets(r3, "PsMe3") == 5);
        c.eq("late line says ? for the unmeasurable", "PsMe3 5 | PsA3 ?", Mod.call(r3, "line"));

        // 4. A late joiner / failed start (no start read) is never reconciled, however the sums fall.
        Object r4 = fresh("PsMe4");
        Mod.call(r4, "gotStart", "PsA4", 10L);
        c.check("asks a never-read player", (Boolean) Mod.call(r4, "wantStart", "PsC4", 0L));
        Mod.call(r4, "markEnded", 7);
        Mod.call(r4, "setOwn", 5);
        Mod.call(r4, "gotEnd", "PsA4", 12L);
        c.check("5 + 2 == 7 but PsC4 was never measured: not reconciled", !flag(r4, "reconciled") && secrets(r4, "PsA4") == -1);
        Mod.call(r4, "gotStart", "PsC4", 5L);
        c.check("a start read after the run ended is refused", secrets(r4, "PsC4") == -1 && !flag(r4, "reconciled"));

        // 5. A failed request is retried 30 s later, at most 6 times.
        Object r5 = fresh("PsMe5");
        boolean t0 = (Boolean) Mod.call(r5, "wantStart", "PsA5", 0L);
        boolean t10 = (Boolean) Mod.call(r5, "wantStart", "PsA5", 10_000L);
        boolean t30 = (Boolean) Mod.call(r5, "wantStart", "PsA5", 30_000L);
        c.check("retry spacing: now yes, +10 s no, +30 s yes", t0 && !t10 && t30);
        int asked = 2;
        for (int i = 2; i < 12; i++) {
            if ((Boolean) Mod.call(r5, "wantStart", "PsA5", i * 30_000L)) asked++;
        }
        c.eq("six tries and no more", 6, asked);

        // 6. Back to back: the next run starts empty and the finished one keeps its numbers.
        Object done = fresh("PsMe6");
        Mod.call(done, "gotStart", "PsA6", 1L);
        Mod.call(done, "markEnded", 4);
        Mod.call(done, "setOwn", 3);
        Mod.call(done, "gotEnd", "PsA6", 2L);
        Object next = fresh("PsMe6");
        c.check("old run stays reconciled, new run knows nothing", flag(done, "reconciled") && secrets(done, "PsA6") == 1
                && !flag(next, "reconciled") && secrets(next, "PsA6") == -1 && secrets(next, "PsMe6") == -1);

        // 7. A player who found nothing reconciles (start == end is a real 0 once the sum matches).
        Object r7 = fresh("PsMe7");
        Mod.call(r7, "gotStart", "PsA7", 50L);
        Mod.call(r7, "markEnded", 3);
        Mod.call(r7, "setOwn", 3);
        Mod.call(r7, "gotEnd", "PsA7", 50L);
        c.check("0 + 3 == 3 shows the real 0", flag(r7, "reconciled") && secrets(r7, "PsA7") == 0);
    }
}

package dev.testkit.gametest.menu;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Death Patterns (deathpatterns-1124): 1124-menu-deathpatterns feeds real death lines through the mod's own line reader with a
 * pretend run context and asserts what it kept: only YOUR deaths, grouped by where and cause over the last 20 runs of one floor,
 * a reminder at a repeated place once, and the shared record on disk.
 */
final class DeathPatternsCases {

    private static final String F = "deathpatterns.DeathPatterns";

    private DeathPatternsCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1124-menu-deathpatterns", DeathPatternsCases::patterns);
    }

    private static void patterns(Session c) throws Exception {
        long t0 = System.currentTimeMillis() - 10_000_000L;
        String you = "☠ You were killed by Goldor and became a ghost.";
        Object[] r = c.onClient(mc -> {
            Mod.call(Mod.field(F, "CFG"), "set", "remind", true);
            Mod.staticCall(F, "resetForTest");
            // Outside a run nothing is kept, whatever the line says.
            Mod.staticCall(F, "onLine", you, t0);
            int outside = (Integer) Mod.staticCall(F, "deathsSeen");
            // Own death lines only: a teammate's, a boss kill and a plain line are not.
            String c1 = (String) Mod.staticCall(F, "causeOf", you);
            String c2 = (String) Mod.staticCall(F, "causeOf", "☠ You fell into the void and became a ghost.");
            Object c3 = Mod.staticCall(F, "causeOf", "☠ Bob was killed by Goldor and became a ghost.");
            Object c4 = Mod.staticCall(F, "causeOf", "☠ Defeated Maxor, Storm, Goldor, and Necron in 12m 34s");
            // 25 older M7 runs with one Goldor death each at the Terminals (outside the 20-run window), then 3 newer runs.
            for (int i = 0; i < 25; i++) {
                run("M7", t0 + i * 1000L, "Terminals", "Mage", t0 + i * 1000L + 500, you);
            }
            long base = t0 + 100_000L;
            run("M7", base, "Terminals", "Mage", base + 90_000, you);
            Mod.staticCall(F, "onLine", "☠ You were killed by Necron and became a ghost.", base + 91_000);
            run("M7", base + 200_000L, "Terminals", "Mage", base + 290_000, you);
            run("M7", base + 400_000L, "Clear", "Mage", base + 410_000, "☠ You fell into the void and became a ghost.");
            // An F7 run: its own floor.
            run("F7", base + 600_000L, "Maxor", "Healer", base + 650_000, "☠ You were killed by Maxor and became a ghost.");
            Object m7 = Mod.staticCall(F, "summarize", "M7");
            Object f7 = Mod.staticCall(F, "summarize", "F7");
            @SuppressWarnings("unchecked")
            List<String> lines = (List<String>) Mod.staticCall(F, "lines", "M7");
            @SuppressWarnings("unchecked")
            List<String> floors = (List<String>) Mod.staticCall(F, "floors");
            int before = (Integer) Mod.staticCall(F, "reminders");
            Mod.staticCall(F, "remindForTest", "M7", "Clear");
            int clear = (Integer) Mod.staticCall(F, "reminders");
            Mod.staticCall(F, "remindForTest", "M7", "Terminals");
            int terminals = (Integer) Mod.staticCall(F, "reminders");
            return new Object[]{outside, c1, c2, c3, c4, Mod.call(m7, "runs"), Mod.call(m7, "deaths"), Mod.call(m7, "runsWithDeath"),
                    Mod.call(f7, "runs"), Mod.call(f7, "deaths"), lines, floors, clear - before, terminals - before,
                    Mod.call(m7, "classes").toString()};
        });
        c.note("outside=" + r[0] + " causes=" + r[1] + "|" + r[2] + "|" + r[3] + "|" + r[4] + " M7 runs/deaths/withDeath=" + r[5] + "/" + r[6] + "/" + r[7]
                + " F7 " + r[8] + "/" + r[9] + " lines=" + r[10] + " floors=" + r[11] + " reminders clear/terminals=" + r[12] + "/" + r[13]);
        c.check((Integer) r[0] == 0, "a death line outside a run was kept");
        c.check("Goldor".equals(r[1]) && "fell into the void".equals(r[2]), "own death causes read wrong: " + r[1] + " / " + r[2]);
        c.check(r[3] == null && r[4] == null, "a teammate's death or a boss kill line was read as your death");
        c.check((Integer) r[5] == 20, "the M7 window holds " + r[5] + " runs, not 20");
        // Window: the newest 20 M7 runs are the 3 new ones plus the 17 newest of the older 25 (each with one Goldor death).
        c.check((Integer) r[6] == 3 + 1 + 17, "M7 deaths in the window: " + r[6] + ", expected 21 (4 new + 17 old)");
        c.check((Integer) r[7] == 3 + 17, "M7 runs with a death: " + r[7] + ", expected 20");
        c.check((Integer) r[8] == 1 && (Integer) r[9] == 1, "F7 was mixed into M7 or lost: " + r[8] + "/" + r[9]);
        List<?> lines = (List<?>) r[10];
        c.check(lines.size() > 1 && lines.get(1).toString().equals("19x Terminals: Goldor"),
                "top pattern line was " + (lines.size() > 1 ? lines.get(1) : "none"));
        c.check(((List<?>) r[11]).get(0).equals("F7"), "floors should list the newest run's floor first: " + r[11]);
        c.check((Integer) r[12] == 0, "a reminder fired at a place with no repeated death");
        c.check((Integer) r[13] == 1, "no reminder at the repeated Terminals death");
        // The record on disk: written off the render thread, merged, and holding what was kept.
        Mod.staticCall("shared.SharedWriter", "drain", 5000L);
        String json = c.onClient(mc -> {
            try {
                Object kind = Mod.field("shared.RecordCatalog", "DEATH_PATTERNS");
                Path p = (Path) Mod.staticCall("shared.SharedRecords", "pathOf", kind);
                return Files.exists(p) ? Files.readString(p) : "";
            } catch (Exception e) {
                return "ERR " + e;
            }
        });
        c.note("record bytes " + json.length());
        String flat = json.replace(" ", "");
        c.check(flat.contains("\"deaths\"") && flat.contains("\"cause\":\"Goldor\"") && flat.contains("\"where\":\"Terminals\"")
                && flat.contains("\"cls\":\"Mage\""), "the shared record did not hold the deaths: " + (json.length() > 120 ? json.substring(0, 120) : json));
        c.onClient(mc -> {
            Mod.staticCall(F, "resetForTest");
            return null;
        });
    }

    /** One pretend run: starts its record, then the given death line at {@code deathAt}. */
    private static void run(String floor, long start, String where, String cls, long deathAt, String line) {
        Mod.staticCall(F, "testContext", floor, where, cls, start);
        Mod.staticCall(F, "newRunForTest", floor, start);
        Mod.staticCall(F, "onLine", line, deathAt);
    }
}

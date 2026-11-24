package dev.testkit.gametest.menu;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;

/**
 * 1124-menu-profit-bestfloor (idea bestfloor-1124): Best Floor To Farm inside the /profit menu. Real RunRecords and
 * Croesus Claims go through FloorRates and the numbers are checked by hand: claims map to the latest run of their
 * floor, a run counts only once claimed, a gap over 20 minutes is a break, a floor under 3 claimed runs has no rate.
 * Then /profit floors resolves to the tracker and its screen opens with the profit scope switches.
 */
final class BestFloorCases {

    private static final String RATES = "gui.profit.FloorRates";
    private static final long MIN = 60_000L;

    private BestFloorCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1124-menu-profit-bestfloor", BestFloorCases::bestFloor);
    }

    private static Object run(String floor, long start, long totalMs) {
        try {
            Class<?> k = Mod.cls("runsummary.RunRecord");
            java.lang.reflect.RecordComponent[] comps = k.getRecordComponents();
            Object[] args = new Object[comps.length];
            Class<?>[] types = new Class<?>[comps.length];
            for (int i = 0; i < comps.length; i++) {
                Class<?> t = comps[i].getType();
                types[i] = t;
                String n = comps[i].getName();
                Object v;
                if (t == boolean.class) {
                    v = false;
                } else if (t == int.class) {
                    v = -1;
                } else if (t == long.class) {
                    v = n.equals("startedAtMs") ? start : n.equals("endedAtMs") ? start + totalMs
                            : n.equals("totalMs") ? totalMs : 0L;
                } else if (t == List.class) {
                    v = List.of();
                } else if (t == String.class) {
                    v = n.equals("floor") ? floor : null;
                } else {
                    v = null;
                }
                args[i] = v;
            }
            return k.getDeclaredConstructor(types).newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object claim(String floor, long ts, long profit) {
        try {
            Class<?> k = Mod.cls("croesus.CroesusProfitLog$Claim");
            java.lang.reflect.RecordComponent[] comps = k.getRecordComponents();
            Object[] args = new Object[comps.length];
            Class<?>[] types = new Class<?>[comps.length];
            for (int i = 0; i < comps.length; i++) {
                Class<?> t = comps[i].getType();
                types[i] = t;
                String n = comps[i].getName();
                args[i] = t == int.class ? 1 : t == long.class ? (n.equals("timestampMs") ? ts : n.equals("profit") ? profit : 0L)
                        : t == String.class ? (n.equals("floor") ? floor : "x") : t == List.class ? List.of() : null;
            }
            return k.getDeclaredConstructor(types).newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object row(List<?> rows, String floor) {
        for (Object r : rows) {
            if (floor.equals(Mod.call(r, "floor"))) {
                return r;
            }
        }
        return null;
    }

    private static long num(Object o, String m) {
        return ((Number) Mod.call(o, m)).longValue();
    }

    static void bestFloor(Session c) throws Exception {
        long t0 = 1_700_000_000_000L;
        long runMs = 6 * MIN;
        List<Object> runs = new ArrayList<>();
        List<Object> claims = new ArrayList<>();
        // M7: four 6 min runs, 1 min apart, claimed 10 s after each ends (run 1 claimed twice: 1M and 0.5M); a fifth
        // run 2 h later is never claimed and its gap is a break.
        for (int i = 0; i < 4; i++) {
            long start = t0 + i * (runMs + MIN);
            runs.add(run("M7", start, runMs));
            claims.add(claim("M7", start + runMs + 10_000L, 1_000_000L));
        }
        claims.add(claim("M7", t0 + runMs + 20_000L - 15_000L, 500_000L));
        runs.add(run("M7", t0 + 4 * (runMs + MIN) + 120 * MIN, runMs));
        // F7: two claimed runs only, so no rate yet.
        runs.add(run("F7", t0 + 300 * MIN, runMs));
        runs.add(run("F7", t0 + 310 * MIN, runMs));
        claims.add(claim("F7", t0 + 300 * MIN + runMs + 1000L, 400_000L));
        claims.add(claim("F7", t0 + 310 * MIN + runMs + 1000L, 400_000L));
        // A claim for a floor with no run in the history maps to nothing.
        claims.add(claim("M3", t0 + 400 * MIN, 9_999_999L));

        List<?> rows = c.onClient(mc -> (List<?>) Mod.staticCall(RATES, "compute", runs, claims));
        Object m7 = row(rows, "M7");
        Object f7 = row(rows, "F7");
        c.check(m7 != null && f7 != null && row(rows, "M3") == null, "rows: " + rows);
        c.check(num(m7, "claimedRuns") == 4, "M7 claimed runs " + num(m7, "claimedRuns") + ", expected 4 (the unclaimed fifth must not count)");
        c.check(num(m7, "profitPerRun") == 1_125_000L, "M7 profit per run " + num(m7, "profitPerRun") + ", expected 1125000");
        c.check(num(m7, "avgRunMs") == runMs, "M7 average run " + num(m7, "avgRunMs"));
        c.check(num(m7, "gapSamples") == 3 && num(m7, "avgGapMs") == MIN,
                "M7 gaps " + num(m7, "gapSamples") + " x " + num(m7, "avgGapMs") + ", expected 3 x 60000 (the 2 h break left out)");
        long expect = Math.round(1_125_000L * 3_600_000.0 / (runMs + MIN));
        c.check(num(m7, "perHour") == expect, "M7 per hour " + num(m7, "perHour") + ", expected " + expect);
        c.check((Boolean) Mod.call(m7, "enough"), "M7 should have enough data");
        c.check(!(Boolean) Mod.call(f7, "enough") && num(f7, "perHour") == 0 && num(f7, "claimedRuns") == 2,
                "F7 with two claimed runs must say not enough data: " + f7);
        c.check(rows.get(0) == m7, "the floor with a rate is not listed first");

        // The menu side: /profit floors resolves, and the screen opens with the profit scope switches.
        Object tracker = c.onClient(mc -> Mod.staticCall("gui.profit.ProfitTracker", "byWord", "floors"));
        c.check(tracker != null && "Best Floor To Farm".equals(Mod.field(tracker, "label")), "/profit floors is not a tracker");
        c.ctx().runOnClient(mc -> {
            try {
                Screen s = (Screen) Mod.cls("gui.profit.BestFloorProfitScreen").getConstructor(Screen.class)
                        .newInstance((Object) null);
                McCompat.setScreen(mc, s);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        c.ctx().waitTicks(5);
        String labels = c.onClient(mc -> TrackerCases.labels(McCompat.screen(mc)));
        c.check(labels.contains("Account") && labels.contains("Reset"), "scope switches missing: " + labels);
        c.note("M7 " + num(m7, "perHour") + "/h from 4 claimed runs (1125000 each, run 6:00 + gap 1:00); F7 2 runs: not enough data");
    }
}

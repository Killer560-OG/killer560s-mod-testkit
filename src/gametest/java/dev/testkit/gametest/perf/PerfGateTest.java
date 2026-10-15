package dev.testkit.gametest.perf;

import dev.testkit.gametest.Scenario;
import dev.testkit.gametest.perf.PerfBench.Phase;
import dev.testkit.gametest.perf.PerfBench.Switch;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 95-perf-gate: fails when the mod got heavier than the stored baseline (2026-10-15). About 5 minutes; only when named.
 *
 * <p>In {@link PerfBench}'s fixed scene, {@code testkit.perf.rounds} (3) rounds of DEFAULTS and ALL ON
 * ({@code testkit.perf.gateTicks}, 400 ticks each). Measured with the mod's PerfProbe (callback self time on the
 * render thread, per frame at 144 fps / 20 tps), the MINIMUM over rounds, because load from other processes only ever
 * adds time. Fails when
 * <ul>
 *   <li>the DEFAULTS total, or the ALL ON total, exceeds baseline x (1 + {@code testkit.perf.budgetPct}/100, 25) +
 *       {@code testkit.perf.budgetFloorUs} (10 us), or</li>
 *   <li>any one feature package's ALL ON cost exceeds baseline x (1 + {@code testkit.perf.featurePct}/100, 50) +
 *       {@code testkit.perf.featureFloorUs} (5 us); a feature not in the baseline gets the floor alone.</li>
 * </ul>
 * The baseline is {@code perf/perf-baseline.properties} in the checkout ({@code -PperfBaseline=<file>} for another).
 * {@code -PperfGateWrite} writes a fresh baseline from this run instead of checking, to the report directory and to the
 * baseline file. Frame-time numbers are printed but never gate: they move with the machine's load.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class PerfGateTest implements FabricClientGameTest {

    private static final String NAME = "95-perf-gate";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String filter = System.getProperty("testkit.scenario", "");
        if (filter.isBlank() || Scenario.skip(NAME)) {
            return;
        }
        int ticks = Integer.getInteger("testkit.perf.gateTicks", 400);
        int rounds = Integer.getInteger("testkit.perf.rounds", 3);
        boolean write = Boolean.getBoolean("testkit.perf.gateWrite");
        Path baselineFile = baselinePath();
        List<String> report = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        if (!PerfBench.open(ctx, NAME, report)) {
            return;
        }
        Map<Switch, Boolean> defaults = new LinkedHashMap<>();
        try {
            if (!PerfBench.probeOn(ctx)) {
                throw new AssertionError("[" + NAME + "] the mod's PerfProbe is off or wrapped nothing: run with "
                        + "-PperfProbe (automatic for -Pscenario=95-perf...) and a dev jar, not a -Prelease one");
            }
            double[] cal = PerfBench.calibrate(ctx);
            List<Switch> all = ctx.computeOnClient(mc -> PerfBench.switches());
            defaults.putAll(ctx.computeOnClient(mc -> PerfBench.states(all)));
            Map<Switch, Boolean> on = PerfBench.allOn(defaults);
            ctx.runOnClient(mc -> PerfBench.apply(on));
            ctx.waitTicks(300);

            List<Phase> defs = new ArrayList<>();
            List<Phase> ons = new ArrayList<>();
            for (int r = 1; r <= rounds; r++) {
                ctx.runOnClient(mc -> PerfBench.apply(defaults));
                ctx.waitTicks(40);
                defs.add(PerfBench.measure(ctx, "DEFAULTS r" + r, ticks, cal));
                report.add(defs.get(defs.size() - 1).line());
                ctx.runOnClient(mc -> PerfBench.apply(on));
                ctx.waitTicks(40);
                ons.add(PerfBench.measure(ctx, "ALL ON r" + r, ticks, cal));
                report.add(ons.get(ons.size() - 1).line());
            }
            if (ons.get(0).ticks() < ticks / 2 || ons.get(0).keys().isEmpty()) {
                throw new AssertionError("[" + NAME + "] nothing was measured: " + ons.get(0).ticks() + " ticks, "
                        + ons.get(0).keys().size() + " probe keys");
            }

            Properties now = new Properties();
            now.setProperty("defaults.total", f(min(defs, Phase::at144Us)));
            now.setProperty("allon.total", f(min(ons, Phase::at144Us)));
            Map<String, Double> feat = new TreeMap<>();
            for (String name : names(ons)) {
                double m = Double.MAX_VALUE;
                for (Phase p : ons) {
                    m = Math.min(m, p.byFeature().getOrDefault(name, 0.0));
                }
                feat.put(name, m);
                now.setProperty("allon.feature." + name, f(m));
            }
            now.setProperty("info.defaults.frameMs", f(min(defs, p -> p.frame().mean())));
            now.setProperty("info.allon.frameMs", f(min(ons, p -> p.frame().mean())));

            if (write) {
                store(now, PerfBench.outDir().resolve("perf-baseline.properties"));
                store(now, baselineFile);
                report.add("baseline WRITTEN to " + baselineFile + " (and the report directory)");
            } else {
                Properties base = new Properties();
                if (!Files.isRegularFile(baselineFile)) {
                    throw new AssertionError("[" + NAME + "] no baseline at " + baselineFile
                            + " - run once with -PperfGateWrite on a known-good jar");
                }
                try (Reader r = Files.newBufferedReader(baselineFile)) {
                    base.load(r);
                } catch (java.io.IOException e) {
                    throw new AssertionError("[" + NAME + "] could not read " + baselineFile + ": " + e, e);
                }
                double pct = Double.parseDouble(System.getProperty("testkit.perf.budgetPct", "25")) / 100.0;
                double floor = Double.parseDouble(System.getProperty("testkit.perf.budgetFloorUs", "10"));
                double fPct = Double.parseDouble(System.getProperty("testkit.perf.featurePct", "50")) / 100.0;
                double fFloor = Double.parseDouble(System.getProperty("testkit.perf.featureFloorUs", "5"));
                for (String total : List.of("defaults.total", "allon.total")) {
                    check(report, failures, total, num(base, total), Double.parseDouble(now.getProperty(total)), pct, floor);
                }
                TreeSet<String> names = new TreeSet<>(feat.keySet());
                for (String k : base.stringPropertyNames()) {
                    if (k.startsWith("allon.feature.")) {
                        names.add(k.substring("allon.feature.".length()));
                    }
                }
                for (String name : names) {
                    String key = "allon.feature." + name;
                    check(report, failures, key, num(base, key), feat.getOrDefault(name, 0.0), fPct, fFloor);
                }
                store(now, PerfBench.outDir().resolve("perf-gate-measured.properties"));
            }
        } finally {
            try {
                if (!defaults.isEmpty()) {
                    ctx.runOnClient(mc -> PerfBench.apply(defaults));
                }
            } catch (Throwable ignored) {
                // restoring config must not mask the real failure
            }
            PerfBench.close(ctx);
            for (String line : report) {
                System.out.println("[" + NAME + "] " + line);
            }
            try {
                Files.createDirectories(PerfBench.outDir());
                Files.writeString(PerfBench.outDir().resolve("perf-gate.txt"),
                        String.join(System.lineSeparator(), report) + System.lineSeparator());
            } catch (Exception e) {
                System.out.println("[" + NAME + "] could not write perf-gate.txt: " + e);
            }
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("[" + NAME + "] over budget (us per frame at 144 fps):\n  "
                    + String.join("\n  ", failures));
        }
        System.out.println("[" + NAME + "] PASS" + (write ? " (baseline written)" : ""));
    }

    private static void check(List<String> report, List<String> failures, String key, double base, double now,
                              double pct, double floor) {
        double limit = (Double.isNaN(base) ? 0 : base) * (1 + pct) + floor;
        String line = String.format(Locale.ROOT, "%-44s baseline %8.2f  now %8.2f  limit %8.2f  %s", key,
                Double.isNaN(base) ? 0 : base, now, limit, now > limit ? "OVER" : "ok");
        report.add(line);
        if (now > limit) {
            failures.add(line);
        }
    }

    private static Path baselinePath() {
        String p = System.getProperty("testkit.perf.baseline", "");
        if (!p.isBlank()) {
            return Path.of(p);
        }
        return Path.of(System.getProperty("testkit.checkout", ".")).resolve("perf").resolve("perf-baseline.properties");
    }

    private static TreeSet<String> names(List<Phase> ps) {
        TreeSet<String> s = new TreeSet<>();
        for (Phase p : ps) {
            s.addAll(p.byFeature().keySet());
        }
        return s;
    }

    private static double min(List<Phase> ps, java.util.function.ToDoubleFunction<Phase> f) {
        return ps.stream().mapToDouble(f).min().orElse(0);
    }

    private static double num(Properties p, String key) {
        String v = p.getProperty(key);
        return v == null ? Double.NaN : Double.parseDouble(v);
    }

    private static void store(Properties p, Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            TreeMap<String, String> sorted = new TreeMap<>();
            for (String k : p.stringPropertyNames()) {
                sorted.put(k, p.getProperty(k));
            }
            try (Writer w = Files.newBufferedWriter(file)) {
                w.write("# 95-perf-gate baseline: mod callback cost, us per frame at 144 fps / 20 tps (min of rounds).\n");
                w.write("# Written by -PperfGateWrite. Machine-specific: regenerate on a new machine.\n");
                for (Map.Entry<String, String> e : sorted.entrySet()) {
                    w.write(e.getKey() + "=" + e.getValue() + "\n");
                }
            }
        } catch (Exception e) {
            throw new AssertionError("[" + NAME + "] could not write " + file + ": " + e, e);
        }
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}

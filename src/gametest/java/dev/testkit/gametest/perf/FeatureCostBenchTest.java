package dev.testkit.gametest.perf;

import dev.testkit.gametest.Scenario;
import dev.testkit.gametest.perf.PerfBench.Key;
import dev.testkit.gametest.perf.PerfBench.Phase;
import dev.testkit.gametest.perf.PerfBench.Switch;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 95-perf-features: what each of the mod's features costs, ranked (2026-10-15). About 45 minutes; only when named.
 *
 * <p>In {@link PerfBench}'s fixed scene (sim F7, fixed window, render distance and camera path) it measures, with the
 * mod's {@code PerfProbe} on:
 * <ol>
 *   <li><b>OFF</b> (every switch off: the nearest thing to vanilla the sim allows, since the sim IS the mod),
 *       <b>DEFAULTS</b> (the config a fresh install has, after the testkit's Quiet profile) and <b>ALL ON</b> (defaults
 *       plus every switch that does not act on the world or reach the OS/network, see {@link PerfBench#NEVER_ON}),
 *       alternated for {@code testkit.perf.rounds} (2) rounds of {@code testkit.perf.bigTicks} (1200) ticks;</li>
 *   <li>each bench-safe switch <b>alone</b> (all else off) for {@code testkit.perf.aloneTicks} (200) ticks, with a fresh
 *       OFF reference every ten switches; {@code -Dtestkit.perf.only=a,b} limits it to switches whose label contains one
 *       of those, {@code testkit.perf.aloneTicks=0} skips it;</li>
 *   <li>one ALL ON phase under JFR ({@code perf-allon.jfr}) for the mod's mixins, which the probe does not see.</li>
 * </ol>
 * Results: {@code perf-features.txt} (ranked tables) and {@code perf-features.tsv} in the report directory.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class FeatureCostBenchTest implements FabricClientGameTest {

    private static final String NAME = "95-perf-features";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String filter = System.getProperty("testkit.scenario", "");
        if (filter.isBlank() || Scenario.skip(NAME)) {
            return;
        }
        int big = Integer.getInteger("testkit.perf.bigTicks", 1200);
        int small = Integer.getInteger("testkit.perf.aloneTicks", 200);
        int rounds = Integer.getInteger("testkit.perf.rounds", 2);
        String only = System.getProperty("testkit.perf.only", "");
        List<String> report = new ArrayList<>();
        List<String> tsv = new ArrayList<>();
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
            report.add(String.format(Locale.ROOT, "probe overhead per call: %.0f ns added to the frame, %.0f ns / %.0f B "
                    + "inside the timed window (subtracted from every key)", cal[0], cal[1], cal[2]));
            List<Switch> all = ctx.computeOnClient(mc -> PerfBench.switches());
            defaults.putAll(ctx.computeOnClient(mc -> PerfBench.states(all)));
            List<Switch> safe = new ArrayList<>();
            List<String> never = new ArrayList<>();
            for (Switch s : all) {
                if (s.neverOn()) {
                    never.add(s.label());
                } else if (only.isBlank() || containsAny(s.label(), only)) {
                    safe.add(s);
                }
            }
            long onByDefault = defaults.values().stream().filter(b -> b).count();
            report.add(all.size() + " switches; " + onByDefault + " on by default; " + never.size()
                    + " never switched on (automation/OS/network): " + never);
            Map<Switch, Boolean> off = PerfBench.allOff(all);
            Map<Switch, Boolean> on = PerfBench.allOn(defaults);

            ctx.runOnClient(mc -> PerfBench.apply(on));
            ctx.waitTicks(300);
            ctx.runOnClient(mc -> PerfBench.apply(off));
            ctx.waitTicks(300);

            List<Phase> offs = new ArrayList<>();
            List<Phase> defs = new ArrayList<>();
            List<Phase> ons = new ArrayList<>();
            for (int r = 1; r <= rounds; r++) {
                offs.add(phase(ctx, off, "OFF r" + r, big, cal, report));
                defs.add(phase(ctx, defaults, "DEFAULTS r" + r, big, cal, report));
                ons.add(phase(ctx, on, "ALL ON r" + r, big, cal, report));
            }

            List<String[]> aloneRows = new ArrayList<>();
            if (small > 0) {
                Phase ref = null;
                for (int i = 0; i < safe.size(); i++) {
                    if (i % 10 == 0) {
                        ref = phase(ctx, off, "OFF ref " + (i / 10 + 1), small, cal, report);
                    }
                    Switch s = safe.get(i);
                    Phase p = phase(ctx, PerfBench.alone(all, s), s.label(), small, cal, report);
                    aloneRows.add(aloneRow(s.label(), p, ref));
                }
            }

            if (!Boolean.getBoolean("testkit.bench.noJfr")) {
                ctx.runOnClient(mc -> PerfBench.apply(on));
                ctx.waitTicks(40);
                ctx.runOnClient(mc -> dev.testkit.harness.BenchCamera.start(0f, 10f, PerfBench.PERIOD));
                FpsBenchTest.jfrPhase(ctx, big, PerfBench.outDir().resolve("perf-allon.jfr"));
                report.add("JFR (ALL ON, " + big + " ticks): " + PerfBench.outDir().resolve("perf-allon.jfr"));
            }

            // ---- the bench must have measured something ----
            Phase on1 = ons.get(0);
            if (on1.frames() < big / 2 || on1.ticks() < big / 2) {
                throw new AssertionError("[" + NAME + "] the frame clock recorded " + on1.frames() + " frames / "
                        + on1.ticks() + " ticks for " + big + " - its mixins did not run");
            }
            boolean hud = on1.keys().keySet().stream().anyMatch(k -> k.contains(" HUD["));
            boolean tick = on1.keys().values().stream().anyMatch(k -> k.kind().equals("tick") && k.calls() >= big / 2);
            boolean render = on1.keys().values().stream().anyMatch(k -> k.kind().equals("frame") && !k.key().contains(" HUD["));
            if (!hud || !tick || !render) {
                throw new AssertionError("[" + NAME + "] the probe saw HUD elements=" + hud + ", tick listeners=" + tick
                        + ", render listeners=" + render + " with everything on - a probe hook did not apply");
            }

            summarize(report, tsv, offs, defs, ons, aloneRows);
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
                Path out = PerfBench.outDir();
                Files.createDirectories(out);
                Files.writeString(out.resolve("perf-features.txt"), String.join(System.lineSeparator(), report)
                        + System.lineSeparator());
                Files.writeString(out.resolve("perf-features.tsv"), String.join("\n", tsv) + "\n");
            } catch (Exception e) {
                System.out.println("[" + NAME + "] could not write the report: " + e);
            }
        }
    }

    private static Phase phase(ClientGameTestContext ctx, Map<Switch, Boolean> state, String label, int ticks,
                               double[] cal, List<String> report) {
        ctx.runOnClient(mc -> PerfBench.apply(state));
        ctx.waitTicks(40);
        Phase p = PerfBench.measure(ctx, label, ticks, cal);
        report.add(p.line());
        System.out.println("[" + NAME + "] " + p.line());
        return p;
    }

    private static boolean containsAny(String label, String csv) {
        for (String part : csv.split(",")) {
            if (!part.isBlank() && label.contains(part.trim())) {
                return true;
            }
        }
        return false;
    }

    /** label, probe delta us/frame@144, frame-mean delta ms, alloc delta KB/frame, top keys. */
    private static String[] aloneRow(String label, Phase p, Phase ref) {
        Map<String, Double> refKeys = new TreeMap<>();
        for (Key k : ref.keys().values()) {
            refKeys.put(k.key(), ref.keyAt144(k));
        }
        List<Map.Entry<String, Double>> deltas = new ArrayList<>();
        for (Key k : p.keys().values()) {
            if (!k.kind().equals("server")) {
                deltas.add(Map.entry(k.key(), p.keyAt144(k) - refKeys.getOrDefault(k.key(), 0.0)));
            }
        }
        deltas.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        StringBuilder top = new StringBuilder();
        for (int i = 0; i < Math.min(3, deltas.size()); i++) {
            if (deltas.get(i).getValue() < 0.5) {
                break;
            }
            top.append(top.isEmpty() ? "" : "; ").append(String.format(Locale.ROOT, "%s %.1f",
                    deltas.get(i).getKey(), deltas.get(i).getValue()));
        }
        return new String[]{label,
                String.format(Locale.ROOT, "%.1f", p.at144Us() - ref.at144Us()),
                String.format(Locale.ROOT, "%.1f", p.frameUs() - ref.frameUs()),
                String.format(Locale.ROOT, "%.1f", p.tickUs() - ref.tickUs()),
                String.format(Locale.ROOT, "%+.3f", p.frame().mean() - ref.frame().mean()),
                String.format(Locale.ROOT, "%+.3f", p.tick().mean() - ref.tick().mean()),
                String.format(Locale.ROOT, "%+.2f", (p.allocPerFrame() - ref.allocPerFrame()) / 1024.0),
                top.toString()};
    }

    private static void summarize(List<String> report, List<String> tsv, List<Phase> offs, List<Phase> defs,
                                  List<Phase> ons, List<String[]> alone) {
        report.add("");
        report.add("== SUMMARY (mean of rounds; frame/tick in ms CPU, mod cost from the probe in us) ==");
        report.add(String.format(Locale.ROOT, "%-9s %8s %8s %8s | %8s %8s %8s | %9s %9s %10s | %9s %8s",
                "config", "frame", "p95", "p99", "tick", "p95", "p99", "mod us/f", "mod us/t", "us/f@144",
                "KB/frame", "MB/s"));
        for (List<Phase> ps : List.of(offs, defs, ons)) {
            String label = ps.get(0).label().replaceAll(" r\\d+$", "");
            report.add(String.format(Locale.ROOT, "%-9s %8.3f %8.3f %8.3f | %8.3f %8.3f %8.3f | %9.1f %9.1f %10.1f | %9.1f %8.1f",
                    label, avg(ps, p -> p.frame().mean()), avg(ps, p -> p.frame().p95()), avg(ps, p -> p.frame().p99()),
                    avg(ps, p -> p.tick().mean()), avg(ps, p -> p.tick().p95()), avg(ps, p -> p.tick().p99()),
                    avg(ps, Phase::frameUs), avg(ps, Phase::tickUs), avg(ps, Phase::at144Us),
                    avg(ps, p -> p.allocPerFrame() / 1024.0), avg(ps, Phase::allocMBs)));
            tsv.add(String.join("\t", "summary", label, f(avg(ps, p -> p.frame().mean())), f(avg(ps, p -> p.frame().p95())),
                    f(avg(ps, p -> p.frame().p99())), f(avg(ps, p -> p.tick().mean())), f(avg(ps, Phase::at144Us)),
                    f(avg(ps, p -> p.allocPerFrame() / 1024.0))));
        }
        report.add(String.format(Locale.ROOT, "DEFAULTS - OFF: frame %+.3f ms, tick %+.3f ms, probe %+.1f us/frame@144;  "
                        + "ALL ON - OFF: frame %+.3f ms, tick %+.3f ms, probe %+.1f us/frame@144",
                avg(defs, p -> p.frame().mean()) - avg(offs, p -> p.frame().mean()),
                avg(defs, p -> p.tick().mean()) - avg(offs, p -> p.tick().mean()),
                avg(defs, Phase::at144Us) - avg(offs, Phase::at144Us),
                avg(ons, p -> p.frame().mean()) - avg(offs, p -> p.frame().mean()),
                avg(ons, p -> p.tick().mean()) - avg(offs, p -> p.tick().mean()),
                avg(ons, Phase::at144Us) - avg(offs, Phase::at144Us)));

        for (Object[] cfg : new Object[][]{{"ALL ON", ons}, {"DEFAULTS", defs}, {"OFF", offs}}) {
            @SuppressWarnings("unchecked")
            List<Phase> ps = (List<Phase>) cfg[1];
            Map<String, double[]> feat = new TreeMap<>();
            for (Phase p : ps) {
                for (Key k : p.keys().values()) {
                    if (k.kind().equals("server")) {
                        continue;
                    }
                    double[] a = feat.computeIfAbsent(k.feature(), x -> new double[4]);
                    a[0] += p.keyAt144(k) / ps.size();
                    if (k.kind().equals("frame")) {
                        a[1] += k.ns() / 1e3 / Math.max(1, p.frames()) / ps.size();
                    } else {
                        a[2] += k.ns() / 1e3 / Math.max(1, p.ticks()) / ps.size();
                    }
                    a[3] += k.bytes() / Math.max(1, p.frames()) / ps.size();
                }
            }
            List<Map.Entry<String, double[]>> rows = new ArrayList<>(feat.entrySet());
            rows.sort(Comparator.comparingDouble((Map.Entry<String, double[]> e) -> e.getValue()[0]).reversed());
            report.add("");
            report.add("== " + cfg[0] + ": mod callbacks by feature package, heaviest first (probe self time) ==");
            report.add(String.format(Locale.ROOT, "%-22s %10s %10s %10s %12s", "feature", "us/f@144", "frame us",
                    "tick us", "B/frame"));
            for (Map.Entry<String, double[]> e : rows) {
                double[] a = e.getValue();
                if (a[0] < 0.05 && a[3] < 1) {
                    continue;
                }
                report.add(String.format(Locale.ROOT, "%-22s %10.2f %10.2f %10.2f %12.0f", e.getKey(), a[0], a[1], a[2], a[3]));
                tsv.add(String.join("\t", "feature", (String) cfg[0], e.getKey(), f(a[0]), f(a[1]), f(a[2]), f(a[3])));
            }
        }

        Phase on = ons.get(ons.size() - 1);
        List<Key> keys = new ArrayList<>(on.keys().values());
        keys.removeIf(k -> k.kind().equals("server"));
        keys.sort(Comparator.comparingDouble(on::keyAt144).reversed());
        report.add("");
        report.add("== ALL ON (last round): top 40 callbacks ==");
        report.add(String.format(Locale.ROOT, "%-70s %-6s %9s %9s %10s %9s", "key", "kind", "us/f@144", "calls/t",
                "B/call", "max us"));
        for (int i = 0; i < Math.min(40, keys.size()); i++) {
            Key k = keys.get(i);
            report.add(String.format(Locale.ROOT, "%-70s %-6s %9.2f %9.2f %10.0f %9.0f", k.key(), k.kind(), on.keyAt144(k),
                    k.calls() / (double) Math.max(1, on.ticks()), k.bytes() / Math.max(1, k.calls()), k.maxNs() / 1e3));
        }
        List<Key> server = new ArrayList<>(on.keys().values());
        server.removeIf(k -> !k.kind().equals("server"));
        server.sort(Comparator.comparingDouble(Key::ns).reversed());
        report.add("");
        report.add("== ALL ON: off-render-thread callbacks (integrated server = the sim itself; not a Hypixel cost) ==");
        for (int i = 0; i < Math.min(10, server.size()); i++) {
            Key k = server.get(i);
            report.add(String.format(Locale.ROOT, "%-70s %9.1f us/tick", k.key(), k.ns() / 1e3 / Math.max(1, on.ticks())));
        }

        if (!alone.isEmpty()) {
            alone.sort(Comparator.comparingDouble((String[] r) -> Double.parseDouble(r[1])).reversed());
            report.add("");
            report.add("== EACH SWITCH ALONE vs the OFF reference, heaviest first ==");
            report.add("(probe = the mod's own callbacks; frame/tick = whole-frame CPU, includes mixin and vanilla work the"
                    + " feature causes, noisy at +-0.05 ms; alloc = render thread)");
            report.add(String.format(Locale.ROOT, "%-52s %9s %8s %8s %9s %9s %9s  %s", "switch", "us/f@144", "frame us",
                    "tick us", "frame ms", "tick ms", "KB/frame", "top callbacks (us/f@144)"));
            for (String[] r : alone) {
                report.add(String.format(Locale.ROOT, "%-52s %9s %8s %8s %9s %9s %9s  %s", (Object[]) r));
                tsv.add("alone\t" + String.join("\t", r));
            }
        }
    }

    private static double avg(List<Phase> ps, java.util.function.ToDoubleFunction<Phase> f) {
        return ps.stream().mapToDouble(f).average().orElse(0);
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}

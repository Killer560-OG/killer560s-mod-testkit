package dev.testkit.gametest.perf;

import dev.testkit.gametest.Scenario;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.perf.PerfBench.Key;
import dev.testkit.gametest.perf.PerfBench.Phase;
import dev.testkit.gametest.perf.PerfBench.Switch;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 95-perf-predict (fps-1104, ~3 min, only when named): Predictive Rooms' ray sweep, alone, in PerfBench's scene (seeded
 * sim F7, the camera turning a full circle per 200 ticks, every other switch OFF).
 *
 * <p>1. Cost: the probe's {@code RoomPredictor} END tick, us and bytes per tick over 400 ticks, and how many rays it cast.
 * 2. Same answer: with {@code RoomPredictor.VERIFY_CLIP} on (mod fps-1104 and later) every ray is cast a second time
 * with vanilla's {@code level.clip}, and each answer must equal the mod's own walk ({@code predict/PredictClip}): the
 * same block, hit point, face and inside flag, or for a ray the walk ended early, a vanilla hit in a tile that cannot
 * count. Fails on any difference, or when fewer than 2,000 rays were compared (nothing was checked).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class PerfPredictTest implements FabricClientGameTest {

    private static final String NAME = "95-perf-predict";
    private static final String PREDICTOR = "livemap.predict.RoomPredictor";
    private static final int TICKS = 400;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String filter = System.getProperty("testkit.scenario", "");
        if (filter.isBlank() || Scenario.skip(NAME)) {
            return;
        }
        List<String> report = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        if (!PerfBench.open(ctx, NAME, report)) {
            return;
        }
        Map<Switch, Boolean> defaults = new LinkedHashMap<>();
        boolean verifiable = false;
        try {
            if (!PerfBench.probeOn(ctx)) {
                throw new AssertionError("[" + NAME + "] the mod's PerfProbe is off: run with -PperfProbe and a dev jar");
            }
            double[] cal = PerfBench.calibrate(ctx);
            List<Switch> all = ctx.computeOnClient(mc -> PerfBench.switches());
            defaults.putAll(ctx.computeOnClient(mc -> PerfBench.states(all)));
            Map<Switch, Boolean> on = PerfBench.allOff(all);
            for (Switch s : all) {
                if (s.label().equals("livemap.LiveMapConfig") || s.label().equals("livemap.predict.PredictiveRoomsConfig")) {
                    on.put(s, true);
                }
            }
            ctx.runOnClient(mc -> {
                PerfBench.apply(on);
                Mod.set("livemap.predict.PredictiveRoomsConfig", "setEnabled", true);
            });
            verifiable = ctx.computeOnClient(mc -> hasField(PREDICTOR, "VERIFY_CLIP"));
            ctx.waitTicks(40);

            // ---- 1. cost ----
            Phase p = PerfBench.measure(ctx, "Predictive Rooms alone", TICKS, cal);
            report.add(p.line());
            Key k = null;
            for (Key key : p.keys().values()) {
                if (key.key().startsWith(PREDICTOR + " ") && key.key().contains("EndTick")) {
                    k = key;
                }
            }
            int obs = ctx.computeOnClient(mc -> (Integer) Mod.staticCall(PREDICTOR, "observationCount"));
            String status = ctx.computeOnClient(mc -> String.valueOf(Mod.staticCall(PREDICTOR, "status")));
            if (k == null || k.calls() < TICKS / 2) {
                failures.add("the predictor's tick did not run (" + (k == null ? 0 : k.calls()) + " calls in " + p.ticks()
                        + " ticks)");
            } else {
                report.add(String.format(Locale.ROOT, "RoomPredictor END tick: %.1f us/tick, %.0f B/tick, max %.0f us over"
                                + " %d ticks; %d block(s) observed, status '%s'", k.ns() / 1e3 / Math.max(1, p.ticks()),
                        k.bytes() / Math.max(1, p.ticks()), k.maxNs() / 1e3, p.ticks(), obs, status));
            }

            // ---- 2. same answer as vanilla's clip ----
            if (verifiable) {
                long[] before = ctx.computeOnClient(mc -> (long[]) Mod.staticCall(PREDICTOR, "clipVerifyCounts"));
                ctx.runOnClient(mc -> Mod.setField(PREDICTOR, "VERIFY_CLIP", true));
                ctx.waitTicks(TICKS);
                ctx.runOnClient(mc -> Mod.setField(PREDICTOR, "VERIFY_CLIP", false));
                long[] after = ctx.computeOnClient(mc -> (long[]) Mod.staticCall(PREDICTOR, "clipVerifyCounts"));
                long rays = after[0] - before[0];
                long bad = after[1] - before[1];
                report.add(String.format(Locale.ROOT, "verify: %d ray(s) cast both ways, %d differed", rays, bad));
                if (rays < 2000) {
                    failures.add("only " + rays + " ray(s) were compared with vanilla's clip - nothing was verified");
                }
                if (bad > 0) {
                    failures.add(bad + " ray(s) answered differently from vanilla's clip (the client log has the first five)");
                }
            } else {
                report.add("verify: n/a (this jar has no RoomPredictor.VERIFY_CLIP: it casts with vanilla's clip)");
            }
        } finally {
            try {
                if (verifiable) {
                    ctx.runOnClient(mc -> Mod.setField(PREDICTOR, "VERIFY_CLIP", false));
                }
                if (!defaults.isEmpty()) {
                    ctx.runOnClient(mc -> PerfBench.apply(defaults));
                }
            } catch (Throwable ignored) {
                // restoring config must not mask the real failure
            }
            for (String line : report) {
                System.out.println("[" + NAME + "] " + line);
            }
            try {
                java.nio.file.Files.write(PerfBench.outDir().resolve("perf-predict.txt"), report);
            } catch (java.io.IOException ignored) {
                // the console has it
            }
            PerfBench.close(ctx);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("[" + NAME + "] " + failures.size() + " failure(s):\n  " + String.join("\n  ", failures));
        }
        System.out.println("[" + NAME + "] PASS");
    }

    private static boolean hasField(String cls, String name) {
        try {
            Mod.cls(cls).getField(name);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}

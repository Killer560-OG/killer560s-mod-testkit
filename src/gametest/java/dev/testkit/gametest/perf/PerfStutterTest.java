package dev.testkit.gametest.perf;

import dev.testkit.gametest.Scenario;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 95-perf-stutter: what the Performance HUD's frame timing costs (mod branch stutter-1026, {@code lagdisplay/FrameStats},
 * {@code ModCallbackTimes}). About 6 minutes, only when named; no probe needed.
 *
 * <p>In {@link PerfBench}'s scene (sim F7, camera circling, unlimited FPS):
 * <ol>
 *   <li><b>per call</b>, on the render thread with measuring on: 200,000 {@code FrameStats.frameStart()} (closes a frame:
 *       GC counters, the ring, the shown values) and 200,000 phase begin/end pairs and {@code ModCallbackTimes.add}
 *       calls, each with its allocation (must be 0 B). The per-frame cost is frameStart + 13 phase pairs + the mod's
 *       tick handlers' adds at 20 tps / 144 fps.</li>
 *   <li><b>whole frames</b>: three rounds of OFF (Performance HUD on, Frame / 1% Low / Log Stutters off: nothing measured)
 *       and ON (Log Stutters on: every hook live), 400 ticks each: FrameClock's extract+render CPU per frame, wall time
 *       per frame and render-thread bytes per frame. ON - OFF bytes per frame is held to a budget.</li>
 * </ol>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class PerfStutterTest implements FabricClientGameTest {

    private static final String NAME = "95-perf-stutter";
    private static final String CFG = "lagdisplay.LagDisplayConfig";
    private static final String STATS = "lagdisplay.FrameStats";
    private static final String CALLBACKS = "lagdisplay.ModCallbackTimes";
    /** ON - OFF render-thread bytes per frame allowed: a CallbackInfo the JIT did not scalar-replace is 24 B a hook. */
    private static final int ALLOC_BUDGET = Integer.getInteger("testkit.perf.stutterAlloc", 64);

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
        try {
            // ---- 1. per call ----
            set(ctx, true, true);
            ctx.waitTicks(40);
            double[] call = ctx.computeOnClient(mc -> perCall());
            String line = String.format(Locale.ROOT, "per call (measuring on): frameStart %.3f us %.1f B, phase begin+end"
                            + " %.3f us %.1f B, ModCallbackTimes add (nanoTime + add) %.3f us %.1f B",
                    call[0], call[1], call[2], call[3], call[4], call[5]);
            report.add(line);
            double perFrame = call[0] + 13 * call[2];
            int handlers = ctx.computeOnClient(mc -> handlerCount());
            double perTick = handlers * call[4];
            report.add(String.format(Locale.ROOT, "=> %.2f us per frame (frameStart + 13 phases) + %.1f us per tick (%d"
                            + " guarded tick handlers) = %.2f us per frame at 144 fps / 20 tps", perFrame, perTick, handlers,
                    perFrame + perTick * 20.0 / PerfBench.REF_FPS));
            if (call[1] > 0.5 || call[3] > 0.5 || call[5] > 0.5) {
                failures.add(line + " (want 0 B per call)");
            }
            ctx.runOnClient(mc -> Mod.staticCall(STATS, "clear"));

            // ---- 2. whole frames ----
            double[] offB = new double[3];
            double[] onB = new double[3];
            double[] offCpu = new double[3];
            double[] onCpu = new double[3];
            double[] offWall = new double[3];
            double[] onWall = new double[3];
            for (int r = 0; r < 3; r++) {
                for (boolean on : new boolean[]{false, true}) {
                    set(ctx, true, on);
                    ctx.waitTicks(20);
                    PerfBench.Phase p = PerfBench.measure(ctx, (on ? "ON " : "OFF") + " round " + (r + 1), 400,
                            new double[3]);
                    report.add(p.line());
                    double wall = p.wallNs() / 1e3 / Math.max(1, p.frames());
                    (on ? onB : offB)[r] = p.allocPerFrame();
                    (on ? onCpu : offCpu)[r] = p.frame().mean() * 1e3;
                    (on ? onWall : offWall)[r] = wall;
                }
            }
            double dB = mean(onB) - mean(offB);
            String sum = String.format(Locale.ROOT, "ON - OFF: %+.1f B/frame (budget %d), extract+render CPU %+.2f us/frame"
                            + " (OFF %.1f us), wall %+.2f us/frame (OFF %.1f us); per round CPU deltas %+.1f %+.1f %+.1f us",
                    dB, ALLOC_BUDGET, mean(onCpu) - mean(offCpu), mean(offCpu), mean(onWall) - mean(offWall),
                    mean(offWall), onCpu[0] - offCpu[0], onCpu[1] - offCpu[1], onCpu[2] - offCpu[2]);
            report.add(sum);
            if (dB > ALLOC_BUDGET) {
                failures.add(sum);
            }
            long measured = ctx.computeOnClient(mc -> (Long) Mod.staticCall(STATS, "frames"));
            if (measured < 1000) {
                failures.add("FrameStats measured only " + measured + " frames - the ON phases did not measure");
            }
            String stutters = ctx.computeOnClient(mc -> String.valueOf(Mod.staticCall(STATS, "stutterTotal")));
            report.add("frames measured " + measured + ", stutters recorded during the bench " + stutters);
        } finally {
            try {
                set(ctx, false, false);
            } catch (Throwable ignored) {
                // restoring config must not mask the real failure
            }
            for (String l : report) {
                System.out.println("[" + NAME + "] " + l);
            }
            PerfBench.close(ctx);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("[" + NAME + "] " + failures.size() + " failure(s):\n  " + String.join("\n  ", failures));
        }
        System.out.println("[" + NAME + "] PASS");
    }

    private static void set(ClientGameTestContext ctx, boolean hud, boolean measure) {
        ctx.runOnClient(mc -> {
            Object cfg = Mod.cfg(CFG);
            Mod.call(cfg, "setEnabled", hud);
            Mod.call(cfg, "setShowFrame", false);
            Mod.call(cfg, "setShowLowFps", false);
            Mod.call(cfg, "setLogStutters", measure);
            Mod.call(cfg, "setStutterThresholdMs", 50);
        });
    }

    private static double mean(double[] a) {
        double s = 0;
        for (double v : a) {
            s += v;
        }
        return s / a.length;
    }

    private static int handlerCount() {
        try {
            java.lang.reflect.Field f = Mod.cls(CALLBACKS).getDeclaredField("slotCount");
            f.setAccessible(true);
            return f.getInt(null);
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    /** Render thread, measuring on: {frameStart us, B, begin+end us, B, add us, B} per call. */
    private static double[] perCall() {
        try {
            Class<?> stats = Mod.cls(STATS);
            java.lang.reflect.Method frameStart = stats.getMethod("frameStart");
            java.lang.reflect.Method begin = stats.getMethod("begin", int.class);
            java.lang.reflect.Method end = stats.getMethod("end", int.class);
            Class<?> cb = Mod.cls(CALLBACKS);
            java.lang.reflect.Method add = cb.getMethod("add", int.class, long.class);
            int slot = (Integer) cb.getMethod("slot", String.class).invoke(null, "TestkitBenchSlot");
            // Direct calls through MethodHandles so reflection's boxing is not what is measured.
            java.lang.invoke.MethodHandles.Lookup l = java.lang.invoke.MethodHandles.publicLookup();
            java.lang.invoke.MethodHandle fs = l.unreflect(frameStart);
            java.lang.invoke.MethodHandle b = l.unreflect(begin);
            java.lang.invoke.MethodHandle e = l.unreflect(end);
            java.lang.invoke.MethodHandle a = l.unreflect(add);
            int n = 200_000;
            double[] out = new double[6];
            for (int pass = 0; pass < 2; pass++) { // pass 0 warms the JIT
                long b0 = PerfBench.threadAllocated();
                long t0 = System.nanoTime();
                for (int i = 0; i < n; i++) {
                    fs.invokeExact();
                }
                long t1 = System.nanoTime();
                long b1 = PerfBench.threadAllocated();
                for (int i = 0; i < n; i++) {
                    b.invokeExact(5);
                    e.invokeExact(5);
                }
                long t2 = System.nanoTime();
                long b2 = PerfBench.threadAllocated();
                for (int i = 0; i < n; i++) {
                    a.invokeExact(slot, System.nanoTime());
                }
                long t3 = System.nanoTime();
                long b3 = PerfBench.threadAllocated();
                out = new double[]{(t1 - t0) / 1e3 / n, (b1 - b0) / (double) n, (t2 - t1) / 1e3 / n,
                        (b2 - b1) / (double) n, (t3 - t2) / 1e3 / n, (b3 - b2) / (double) n};
            }
            return out;
        } catch (Throwable t) {
            throw new AssertionError("per-call bench failed: " + t, t);
        }
    }
}

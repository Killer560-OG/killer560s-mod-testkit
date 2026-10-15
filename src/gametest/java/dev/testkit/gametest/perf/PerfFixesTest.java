package dev.testkit.gametest.perf;

import dev.testkit.gametest.Scenario;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.perf.PerfBench.Key;
import dev.testkit.gametest.perf.PerfBench.Phase;
import dev.testkit.gametest.perf.PerfBench.Switch;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 95-perf-fixes: the hot spots fixed on 2026-10-15 stay fixed (~4 min, only when named). Judged on the render thread's
 * ALLOCATION per call (deterministic, unlike time on a loaded machine) and on whether a callback ran at all, with the
 * mod's PerfProbe, in {@link PerfBench}'s scene. Fails on mod main d3263fb6:
 * <ul>
 *   <li>Health and Mana Bars OFF: no stat element is laid out or drawn (main built the whole Predefined StatLayout every
 *       frame, ~2 KB);</li>
 *   <li>Performance HUD, Real Time and Mask Invincibility Timers ON: each draws every frame (asserted) and allocates
 *       under its budget per draw (main rebuilt their text every frame: String.format, a pattern string, a cloned
 *       TimeZone, concatenation);</li>
 *   <li>{@code HudTextCache}'s left-to-right fast path gives the same characters and styles as vanilla's Bidi
 *       {@code getVisualOrder} for formatted ASCII lines, and is not taken for right-to-left text.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class PerfFixesTest implements FabricClientGameTest {

    private static final String NAME = "95-perf-fixes";

    /** HUD elements whose text is now built once per change: bytes allowed per width() call, which on main rebuilt
     *  the text each time (String.format, a pattern string and a cloned TimeZone, concatenations). What remains is
     *  Font.width's own lambda; main measured several hundred to a few thousand bytes per call (2026-10-15). */
    private static final Map<String, Integer> WIDTH_BUDGET_BYTES = new LinkedHashMap<>();

    static {
        WIDTH_BUDGET_BYTES.put("lag_display", 300);
        WIDTH_BUDGET_BYTES.put("real_time", 150);
        WIDTH_BUDGET_BYTES.put("mask_invincibility", 300);
        WIDTH_BUDGET_BYTES.put("dungeon_info", 150);
    }

    private static final String[] LTR = {
            "Secrets: 3/7", "TPS: 19.9", "Ping: 42ms", "§6X: §f-24.50000", "§6Yaw §f725.12345 (5.12345) §8body 3.0",
            "§lBold §r§cred §ktext§r end", "Real Time: 09:41:07 PM CDT", "Spirit Mask: 2.5s", "", "a§", "§", "50% <>[]{}",
    };
    private static final String[] RTL = {"שלום 123", "مرحبا: 5", "abc א"};

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
        try {
            if (!PerfBench.probeOn(ctx)) {
                throw new AssertionError("[" + NAME + "] the mod's PerfProbe is off: run with -PperfProbe and a dev jar");
            }
            double[] cal = PerfBench.calibrate(ctx);
            List<Switch> all = ctx.computeOnClient(mc -> PerfBench.switches());
            defaults.putAll(ctx.computeOnClient(mc -> PerfBench.states(all)));

            // ---- text: fast path == vanilla ----
            failures.addAll(ctx.computeOnClient(mc -> textChecks(report)));

            // ---- Health and Mana Bars off: nothing laid out ----
            Map<Switch, Boolean> off = PerfBench.allOff(all);
            ctx.runOnClient(mc -> PerfBench.apply(off));
            ctx.waitTicks(40);
            Phase p = PerfBench.measure(ctx, "OFF", 200, cal);
            report.add(p.line());
            long statCalls = 0;
            double statBytes = 0;
            for (Key k : p.keys().values()) {
                if (k.key().contains("HUD[statbar_") || k.key().contains("HUD[stat_")) {
                    statCalls += k.calls();
                    statBytes += k.bytes();
                }
            }
            String statLine = String.format(Locale.ROOT, "stat readouts with Health and Mana Bars OFF: %d draw call(s), %.0f B/frame",
                    statCalls, statBytes / Math.max(1, p.frames()));
            report.add(statLine);
            if (statBytes / Math.max(1, p.frames()) > 64) {
                failures.add(statLine + " (budget 64 B/frame)");
            }

            // ---- the three text HUDs on ----
            Map<Switch, Boolean> on = PerfBench.allOff(all);
            for (Switch s : all) {
                if (s.label().startsWith("lagdisplay.") || s.label().startsWith("realtime.")
                        || s.label().startsWith("maskinvincibility.")) {
                    on.put(s, true);
                }
            }
            ctx.runOnClient(mc -> PerfBench.apply(on));
            ctx.waitTicks(40);
            Phase q = PerfBench.measure(ctx, "three HUDs ON", 400, cal);
            report.add(q.line());
            for (String id : WIDTH_BUDGET_BYTES.keySet()) {
                Key k = null;
                for (Key c : q.keys().values()) {
                    if (c.key().contains("HUD[" + id + "]")) {
                        k = c;
                    }
                }
                // dungeon_info is drawn by GuiOverlays whenever the Secrets HUD is on, which this phase leaves off.
                if (!id.equals("dungeon_info") && (k == null || k.calls() < q.frames() / 2)) {
                    failures.add(id + " did not draw every frame (" + (k == null ? 0 : k.calls()) + " draws in "
                            + q.frames() + " frames) - nothing was measured");
                }
            }
            ctx.runOnClient(mc -> Mod.call(Mod.cfg("dungeoninfo.DungeonInfoConfig"), "setSecretsHudEnabled", true));
            Map<String, double[]> widths = ctx.computeOnClient(mc -> widthAllocations());
            for (Map.Entry<String, double[]> e : widths.entrySet()) {
                int budget = WIDTH_BUDGET_BYTES.get(e.getKey());
                String line = String.format(Locale.ROOT, "%-20s width(): %7.0f B/call, %6.2f us/call (budget %d B)",
                        e.getKey(), e.getValue()[0], e.getValue()[1], budget);
                report.add(line);
                if (e.getValue()[0] > budget) {
                    failures.add(line);
                }
            }
        } finally {
            try {
                if (!defaults.isEmpty()) {
                    ctx.runOnClient(mc -> PerfBench.apply(defaults));
                }
            } catch (Throwable ignored) {
                // restoring config must not mask the real failure
            }
            for (String line : report) {
                System.out.println("[" + NAME + "] " + line);
            }
            PerfBench.close(ctx);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("[" + NAME + "] " + failures.size() + " failure(s):\n  " + String.join("\n  ", failures));
        }
        System.out.println("[" + NAME + "] PASS");
    }

    /** Render thread: each element's width() 2000 times (after a warm-up), allocation and time per call. */
    private static Map<String, double[]> widthAllocations() {
        Map<String, double[]> out = new LinkedHashMap<>();
        for (String id : WIDTH_BUDGET_BYTES.keySet()) {
            Object element = Mod.staticCall("hud.HudElementRegistry", "byId", id);
            if (element == null) {
                out.put(id, new double[]{Double.MAX_VALUE, 0});
                continue;
            }
            for (int i = 0; i < 200; i++) {
                Mod.call(element, "width");
            }
            java.lang.reflect.Method width;
            try {
                width = element.getClass().getMethod("width");
                width.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            int n = 2000;
            long b0 = PerfBench.threadAllocated();
            long t0 = System.nanoTime();
            try {
                for (int i = 0; i < n; i++) {
                    width.invoke(element);
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            long t1 = System.nanoTime();
            long b1 = PerfBench.threadAllocated();
            out.put(id, new double[]{(b1 - b0) / (double) n, (t1 - t0) / 1e3 / n});
        }
        return out;
    }

    /** Client thread. */
    private static List<String> textChecks(List<String> report) {
        List<String> failures = new ArrayList<>();
        int fast = 0;
        for (String s : LTR) {
            boolean ltr;
            try {
                ltr = (Boolean) Mod.staticCall("hud.HudTextCache", "leftToRightOnly", s);
            } catch (AssertionError e) {
                failures.add("HudTextCache has no left-to-right fast path (leftToRightOnly): " + e.getMessage());
                return failures;
            }
            String vanilla = glyphs(Language.getInstance().getVisualOrder(FormattedText.of(s)));
            String cached = glyphs((FormattedCharSequence) Mod.staticCall("hud.HudTextCache", "visualOrder", s));
            if (!vanilla.equals(cached)) {
                failures.add("HudTextCache differs from vanilla for '" + s + "':\n    vanilla " + vanilla + "\n    cache   " + cached);
            }
            if (ltr) {
                fast++;
            }
        }
        for (String s : RTL) {
            if ((Boolean) Mod.staticCall("hud.HudTextCache", "leftToRightOnly", s)) {
                failures.add("the left-to-right fast path accepted right-to-left text '" + s + "'");
            }
            String vanilla = glyphs(Language.getInstance().getVisualOrder(FormattedText.of(s)));
            String cached = glyphs((FormattedCharSequence) Mod.staticCall("hud.HudTextCache", "visualOrder", s));
            if (!vanilla.equals(cached)) {
                failures.add("HudTextCache differs from vanilla for RTL '" + s + "'");
            }
        }
        if (fast < LTR.length - 2) {
            failures.add("the fast path was taken for only " + fast + " of " + LTR.length + " left-to-right lines");
        }
        report.add("text: " + LTR.length + " LTR lines (" + fast + " on the fast path) and " + RTL.length
                + " RTL lines compared with vanilla's visual order");
        return failures;
    }

    private static String glyphs(FormattedCharSequence seq) {
        StringBuilder sb = new StringBuilder();
        seq.accept((index, style, cp) -> {
            sb.appendCodePoint(cp).append('{').append(style).append('}');
            return true;
        });
        return sb.toString();
    }
}

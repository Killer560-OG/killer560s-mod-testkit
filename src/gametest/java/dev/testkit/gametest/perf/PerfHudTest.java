package dev.testkit.gametest.perf;

import dev.testkit.gametest.Scenario;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.perf.PerfBench.Key;
import dev.testkit.gametest.perf.PerfBench.Phase;
import dev.testkit.gametest.perf.PerfBench.Switch;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 95-perf-hud (fps-hud-1015): the HUD text caches of 2026-10-15 stay correct and stay cheap (~3 min, only when named).
 * In {@link PerfBench}'s scene (seeded sim F7, camera turning), with the mod's PerfProbe. Correctness first, each check
 * comparing the cached answer with a fresh build of the same text, across changing inputs:
 * <ul>
 *   <li>Score Calculator: the cached lines equal {@code buildLines} after each of its inputs changes;</li>
 *   <li>Advanced Position: every frame's lines equal the text formatted from scratch while the camera turns, and the
 *       X/Y/Z lines are the same String objects from frame to frame (the cache acted);</li>
 *   <li>Health and Mana Bars text readouts follow their numbers; Ability Cooldowns' row equals String.format for every
 *       millisecond from 0 to 12 s; Tick Timers' seconds text equals String.format for 0-3000 ticks;</li>
 *   <li>the Custom Scoreboard's colour-code scan returns what its regex did, for a corpus and 20,000 random strings.</li>
 * </ul>
 * Then allocation (deterministic, unlike time): Score Calculator's and Advanced Position's draws and width(), a
 * never-moved element's resolvePosition, and Split Timers' layoutPosition, each under a budget that mod main 69505e45
 * exceeds. Fails on main (missing caches, over budget), passes on fps-hud-1015.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class PerfHudTest implements FabricClientGameTest {

    private static final String NAME = "95-perf-hud";

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

            // ---- correctness, render thread ----
            failures.addAll(ctx.computeOnClient(mc -> guarded("colour codes", () -> colourCodeChecks(report))));
            failures.addAll(ctx.computeOnClient(mc -> guarded("ability cooldowns", () -> abilityChecks(report))));
            failures.addAll(ctx.computeOnClient(mc -> guarded("tick timers", () -> tickTimerChecks(report))));
            failures.addAll(ctx.computeOnClient(mc -> guarded("stat text", () -> statTextChecks(report))));
            failures.addAll(ctx.computeOnClient(mc -> guarded("inventory hud", () -> inventoryChecks(mc, report))));

            Map<Switch, Boolean> on = PerfBench.allOff(all);
            for (Switch s : all) {
                if (s.label().startsWith("scorecalc.") || s.label().startsWith("position.")
                        || s.label().startsWith("splittimers.")) {
                    on.put(s, true);
                }
            }
            ctx.runOnClient(mc -> PerfBench.apply(on));
            ctx.runOnClient(mc -> {
                Mod.call(Mod.cfg("position.PositionConfig"), "setShowFacing", true);
                Mod.call(Mod.cfg("scorecalc.ScoreCalculatorConfig"), "setShowBreakdown", true);
            });
            ctx.waitTicks(40);
            failures.addAll(ctx.computeOnClient(mc -> guarded("score calculator", () -> scoreChecks(report))));

            // Advanced Position, frame by frame while turning.
            List<String> posFails = new ArrayList<>();
            int[] reused = {0, 0};
            for (int f = 0; f < 60; f++) {
                posFails.addAll(ctx.computeOnClient(mc -> guarded("advanced position", () -> positionCheck(reused))));
                ctx.waitTicks(1);
            }
            report.add("Advanced Position: 60 ticks compared with a fresh format; X line reused " + reused[0] + " of "
                    + reused[1] + " checks after the first");
            if (!posFails.isEmpty()) {
                failures.add(posFails.get(0) + (posFails.size() > 1 ? " (+" + (posFails.size() - 1) + " more)" : ""));
            }
            if (reused[1] > 0 && reused[0] < reused[1] / 2) {
                failures.add("Advanced Position's X line was rebuilt in " + (reused[1] - reused[0]) + " of " + reused[1]
                        + " ticks while only the view turned (the per-line cache never acted)");
            }

            // ---- allocation ----
            Phase p = PerfBench.measure(ctx, "HUD ON", 300, cal);
            report.add(p.line());
            // Most of a draw is vanilla's glyphs (per character, every frame). Main 69505e45 measured 13,375 B per Score
            // Calculator draw (its lines rebuilt each frame) against 11,712 here; Advanced Position's draw is nearly all
            // glyphs either way (7,607 / 7,476), so it is only required to draw - its cache is checked above.
            budget(p, "score_calculator", 12500, failures, report);
            budget(p, "advanced_position", 20000, failures, report);

            Map<String, double[]> direct = ctx.computeOnClient(mc -> directAllocations());
            Map<String, Integer> budgets = Map.of("score_calculator width()", 64, "advanced_position width()", 64,
                    "score_calculator resolvePosition()", 8, "split_timers layoutPosition()", 8);
            for (Map.Entry<String, double[]> e : direct.entrySet()) {
                int b = budgets.get(e.getKey());
                String line = String.format(Locale.ROOT, "%-36s %7.1f B/call, %6.2f us/call (budget %d B)", e.getKey(),
                        e.getValue()[0], e.getValue()[1], b);
                report.add(line);
                if (e.getValue()[0] > b) {
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

    private interface Check {
        List<String> run();
    }

    /** A check whose mod method is missing (main) is one failure, not a crash of the whole scenario. */
    private static List<String> guarded(String what, Check c) {
        try {
            return c.run();
        } catch (AssertionError | RuntimeException e) {
            return List.of(what + ": " + e.getMessage());
        }
    }

    private static void budget(Phase p, String id, int bytes, List<String> failures, List<String> report) {
        Key k = null;
        for (Key c : p.keys().values()) {
            if (c.key().contains("HUD[" + id + "]")) {
                k = c;
            }
        }
        if (k == null || k.calls() < p.frames() / 2) {
            failures.add(id + " did not draw every frame (" + (k == null ? 0 : k.calls()) + " draws in " + p.frames()
                    + " frames) - nothing was measured");
            return;
        }
        String line = String.format(Locale.ROOT, "HUD[%s] %7.0f B/draw, %6.2f us/draw (budget %d B)", id,
                k.bytes() / k.calls(), k.ns() / k.calls() / 1e3, bytes);
        report.add(line);
        if (k.bytes() / k.calls() > bytes) {
            failures.add(line);
        }
    }

    // ---- Custom Scoreboard colour codes ----

    private static final Pattern COLOR_CODES = Pattern.compile("(?:§[0-9a-fk-orA-FK-OR])+");

    private static String regexLast(String s) {
        Matcher m = COLOR_CODES.matcher(s);
        String last = null;
        while (m.find()) {
            last = m.group();
        }
        return last;
    }

    private static List<String> colourCodeChecks(List<String> report) {
        List<String> fails = new ArrayList<>();
        List<String> corpus = new ArrayList<>(List.of("", "§", "§§", "§§a", "a§", "§a", "§a§l", "§8- §c§aApex Dra",
                "§x§a", "§a§x§b", "§A§K§R§r§z", "Copper: §c114,25§i0", "§7Purse: §6123,456", "§e§lSKYBLOCK §b§lCO-OP",
                "§f§r§f§r", "§a§b§c§d§e§f§0§1§2§3§4§5§6§7§8§9§k§l§m§n§o§r"));
        Random rng = new Random(9520261015L);
        String alphabet = "§§§§aflrkoxzR09 :,.-";
        for (int i = 0; i < 20000; i++) {
            StringBuilder b = new StringBuilder();
            int n = rng.nextInt(14);
            for (int j = 0; j < n; j++) {
                b.append(alphabet.charAt(rng.nextInt(alphabet.length())));
            }
            corpus.add(b.toString());
        }
        int checked = 0;
        for (String s : corpus) {
            Object got = Mod.staticCall("scoreboard.ScoreboardData", "lastColorCode", s);
            String want = regexLast(s);
            checked++;
            if (!java.util.Objects.equals(got, want)) {
                fails.add("lastColorCode('" + s + "') = " + got + ", the regex says " + want);
                if (fails.size() > 5) {
                    break;
                }
            }
        }
        report.add("colour codes: " + checked + " strings, scan == regex");
        return fails;
    }

    // ---- Ability Cooldowns ----

    private static List<String> abilityChecks(List<String> report) {
        List<String> fails = new ArrayList<>();
        Object ability = Mod.cls("abilitycooldown.ItemAbility").getEnumConstants()[0];
        String label = (String) Mod.call(ability, "label");
        String cls = "abilitycooldown.AbilityCooldownFeature$CooldownHudElement";
        int colour = 0xFFFFFFFF;
        for (long ms = 0; ms <= 12_000; ms++) {
            double remaining = ms / 1000.0;
            Object line = Mod.staticCall(cls, "cachedLine", ability, ms, remaining, colour);
            String got = (String) Mod.call(line, "text");
            String want = String.format(Locale.US, "%s: %.1fs", label, remaining);
            if (!got.equals(want)) {
                fails.add("ability row at " + ms + " ms: '" + got + "', String.format gives '" + want + "'");
                break;
            }
        }
        // A colour change inside one tenth must not keep the old colour.
        Object a = Mod.staticCall(cls, "cachedLine", ability, 1000L, 1.0, 0xFFFF5555);
        Object b = Mod.staticCall(cls, "cachedLine", ability, 1001L, 1.001, 0xFFFFAA00);
        if ((Integer) Mod.call(b, "color") != 0xFFFFAA00 || (Integer) Mod.call(a, "color") != 0xFFFF5555) {
            fails.add("ability row kept a stale colour across the 1.0 s boundary");
        }
        report.add("ability cooldowns: 12,001 ms values == String.format");
        return fails;
    }

    // ---- Tick Timers ----

    private static List<String> tickTimerChecks(List<String> report) {
        List<String> fails = new ArrayList<>();
        Object cfg = Mod.cfg("ticktimers.TickTimersConfig");
        boolean ticks = (Boolean) Mod.call(cfg, "isDisplayInTicks");
        boolean symbol = (Boolean) Mod.call(cfg, "isShowSymbol");
        try {
            Mod.call(cfg, "setDisplayInTicks", false);
            for (boolean sym : new boolean[]{true, false, true}) {
                Mod.call(cfg, "setShowSymbol", sym);
                for (int t = 0; t <= 3000; t++) {
                    String got = (String) Mod.staticCall("ticktimers.TickTimersFeature", "value", t, cfg);
                    String want = String.format(Locale.US, "%.1f%s", t / 20f, sym ? "s" : "");
                    if (!got.equals(want)) {
                        fails.add("tick timer " + t + " (symbol " + sym + "): '" + got + "', expected '" + want + "'");
                        return fails;
                    }
                }
            }
        } finally {
            Mod.call(cfg, "setDisplayInTicks", ticks);
            Mod.call(cfg, "setShowSymbol", symbol);
        }
        report.add("tick timers: 3 x 3,001 values == String.format");
        return fails;
    }

    // ---- Health and Mana Bars text ----

    private static List<String> statTextChecks(List<String> report) {
        List<String> fails = new ArrayList<>();
        String f = "playerstats.PlayerStatsFeature";
        Object health = Mod.enumValue("playerstats.StatElements$Readout", "HEALTH_TEXT");
        Object ehp = Mod.enumValue("playerstats.StatElements$Readout", "EFFECTIVE_HEALTH_TEXT");
        long hc = (Long) Mod.field(f, "healthCur");
        long hm = (Long) Mod.field(f, "healthMax");
        long def = (Long) Mod.field(f, "defenceValue");
        try {
            long[][] values = {{1234, 5000, 100}, {1234, 5000, 100}, {1235, 5000, 100}, {1235, 6000, 250}, {-1, 6000, 250}};
            for (long[] v : values) {
                Mod.setField(f, "healthCur", v[0]);
                Mod.setField(f, "healthMax", v[1]);
                Mod.setField(f, "defenceValue", v[2]);
                Object got = Mod.staticCall("playerstats.StatElements", "text", health, false);
                String want = v[0] < 0 ? null : "❤ " + String.format(Locale.US, "%,d", v[0]) + "/"
                        + String.format(Locale.US, "%,d", v[1]);
                if (!java.util.Objects.equals(got, want)) {
                    fails.add("Health Text for " + v[0] + "/" + v[1] + ": '" + got + "', expected '" + want + "'");
                }
                Object gotE = Mod.staticCall("playerstats.StatElements", "text", ehp, false);
                String wantE = v[0] < 0 ? null : "EHP " + String.format(Locale.US, "%,d",
                        Math.round(v[0] * (1.0 + v[2] / 100.0)));
                if (!java.util.Objects.equals(gotE, wantE)) {
                    fails.add("EHP Text for " + v[0] + "/" + v[2] + ": '" + gotE + "', expected '" + wantE + "'");
                }
            }
            Object preview = Mod.staticCall("playerstats.StatElements", "text", health, true);
            if (!"Health Text".equals(preview)) {
                fails.add("Health Text preview with no value is '" + preview + "', expected its label");
            }
            // Repeated asks for an unchanged value cost no String.format.
            Mod.setField(f, "healthCur", 4321L);
            java.lang.reflect.Method text;
            try {
                text = Mod.cls("playerstats.StatElements").getDeclaredMethod("text", health.getClass(), boolean.class);
                text.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            double per = perCall(() -> invoke(text, null, health, false))[0];
            report.add(String.format(Locale.ROOT, "stat text: values followed; %.0f B per unchanged ask (reflection included)", per));
            if (per > 200) {
                fails.add(String.format(Locale.ROOT, "Health Text rebuilt for an unchanged value: %.0f B per ask (budget 200)", per));
            }
        } finally {
            Mod.setField(f, "healthCur", hc);
            Mod.setField(f, "healthMax", hm);
            Mod.setField(f, "defenceValue", def);
        }
        return fails;
    }

    // ---- Inventory HUD: the panel's fills, batched ----

    /** Every fill record the panel puts in a fresh render state, in draw order (a GuiRects batch flattened), and how many
     *  elements carried them. */
    private static List<String> panelFills(net.minecraft.client.Minecraft mc, int[] elements) throws ReflectiveOperationException {
        net.minecraft.client.renderer.state.gui.GuiRenderState state = new net.minecraft.client.renderer.state.gui.GuiRenderState();
        net.minecraft.client.gui.GuiGraphicsExtractor g = new net.minecraft.client.gui.GuiGraphicsExtractor(mc, state, -1, -1);
        Class<?> feature = Mod.cls("inventoryhud.InventoryHudFeature");
        java.lang.reflect.Method draw = null;
        for (java.lang.reflect.Method m : feature.getDeclaredMethods()) {
            if (m.getName().equals("drawPanel")) {
                draw = m;
            }
        }
        if (draw == null) {
            throw new AssertionError("no InventoryHudFeature.drawPanel");
        }
        draw.setAccessible(true);
        g.pose().pushMatrix();
        g.pose().translate(40, 40);
        g.pose().scale(2f, 2f);
        draw.invoke(null, g, 0, 0, mc.player, Mod.cfg("inventoryhud.InventoryHudConfig"), 0f);
        g.pose().popMatrix();
        List<String> out = new ArrayList<>();
        state.forEachElement(el -> {
            elements[0]++;
            if (el.getClass().getName().endsWith("GuiRects$Batch")) {
                try {
                    var parts = el.getClass().getDeclaredMethod("parts");
                    parts.setAccessible(true);
                    for (Object part : (Object[]) parts.invoke(el)) {
                        out.add(part.toString());
                    }
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            } else {
                out.add(el.toString());
            }
        }, net.minecraft.client.renderer.state.gui.GuiRenderState.TraverseRange.ALL);
        return out;
    }

    /** One vanilla fill's record, as {@code GuiGraphicsExtractor.fill} builds it under the panel's pose. */
    private static String vanillaFill(net.minecraft.client.Minecraft mc, int x0, int y0, int x1, int y1, int color) {
        net.minecraft.client.renderer.state.gui.GuiRenderState state = new net.minecraft.client.renderer.state.gui.GuiRenderState();
        net.minecraft.client.gui.GuiGraphicsExtractor g = new net.minecraft.client.gui.GuiGraphicsExtractor(mc, state, -1, -1);
        g.pose().translate(40, 40);
        g.pose().scale(2f, 2f);
        g.fill(x0, y0, x1, y1, color);
        List<String> one = new ArrayList<>();
        state.forEachElement(el -> one.add(el.toString()), net.minecraft.client.renderer.state.gui.GuiRenderState.TraverseRange.ALL);
        return one.isEmpty() ? null : one.get(0);
    }

    private static int withAlpha(int rgb, int opacity) {
        return (Math.round(opacity * 2.55f) << 24) | (rgb & 0xFFFFFF);
    }

    /** The fills the old drawPanel made one by one (panel, outline, slot backdrops, slot grid, then the dim quad), each
     *  built by vanilla's fill, against what the panel records now: the same records in the same order, and (fails on
     *  main 69505e45) one element for all of them. */
    private static List<String> inventoryChecks(net.minecraft.client.Minecraft mc, List<String> report) {
        List<String> fails = new ArrayList<>();
        Object cfg = Mod.cfg("inventoryhud.InventoryHudConfig");
        boolean mini0 = (Boolean) Mod.call(cfg, "isMiniMode");
        boolean vert0 = (Boolean) Mod.call(cfg, "isVertical");
        int opacity0 = (Integer) Mod.call(cfg, "getBackgroundOpacity");
        Object bg0 = Mod.call(cfg, "getBackground");
        Object slots = Mod.enumValue("inventoryhud.InventoryHudConfig$Background", "SLOTS");
        int combos = 0;
        try {
            Mod.call(cfg, "setBackground", slots);
            for (boolean mini : new boolean[]{false, true}) {
                for (boolean vert : new boolean[]{false, true}) {
                    for (int opacity : new int[]{100, 60}) {
                        Mod.call(cfg, "setMiniMode", mini);
                        Mod.call(cfg, "setVertical", vert);
                        Mod.call(cfg, "setBackgroundOpacity", opacity);
                        combos++;
                        String what = "Inventory HUD (mini " + mini + ", vertical " + vert + ", opacity " + opacity + ")";
                        List<String> want = expectedPanel(mc, mini, vert, opacity);
                        int[] elements = {0};
                        List<String> got;
                        try {
                            got = panelFills(mc, elements);
                        } catch (ReflectiveOperationException e) {
                            throw new AssertionError(e);
                        }
                        // Order: within the batch it is the submission order; on main the separate elements come back
                        // in the render state's own order, so main is compared as a set.
                        boolean same = elements[0] <= 2 ? got.equals(want)
                                : new java.util.HashSet<>(got).equals(new java.util.HashSet<>(want)) && got.size() == want.size();
                        if (!same) {
                            fails.add(what + ": " + got.size() + " fill records differ from the old code's " + want.size()
                                    + (got.isEmpty() || want.isEmpty() ? "" : "\n    first got  " + got.get(0)
                                    + "\n    first want " + want.get(0)));
                        }
                        int allowed = opacity < 100 ? 2 : 1;
                        if (elements[0] > allowed) {
                            fails.add(what + ": " + elements[0] + " render-state elements for an empty inventory's panel,"
                                    + " want " + allowed + " (one batch" + (allowed == 2 ? " plus the dim quad" : "") + ")");
                        }
                    }
                }
            }
        } finally {
            Mod.call(cfg, "setMiniMode", mini0);
            Mod.call(cfg, "setVertical", vert0);
            Mod.call(cfg, "setBackgroundOpacity", opacity0);
            Mod.call(cfg, "setBackground", bg0);
        }
        report.add("inventory hud: " + combos + " layouts, panel fills == the old code's vanilla fills");
        return fails;
    }

    private static List<String> expectedPanel(net.minecraft.client.Minecraft mc, boolean mini, boolean vert, int opacity) {
        int cell = mini ? 9 : 18;
        int pad = mini ? 1 : 2;
        int w = (vert ? 3 : 9) * cell + pad * 2;
        int h = (vert ? 9 : 3) * cell + pad * 2;
        Object menu = Mod.staticCall("gui.OverlayThemes", "of", "inventory_hud");
        int panel = menu == null ? 0x101010 : (Integer) Mod.call(menu, "panel");
        int accent = menu == null ? 0xCC6600 : (Integer) Mod.call(menu, "accent");
        int card = menu == null ? 0x262626 : (Integer) Mod.call(menu, "card");
        int border = menu == null ? 0x3D2A14 : (Integer) Mod.call(menu, "border");
        List<String> out = new ArrayList<>();
        out.add(vanillaFill(mc, 0, 0, w, h, withAlpha(panel, opacity)));
        int o = withAlpha(accent, opacity);
        out.add(vanillaFill(mc, 0, 0, w, 1, o));
        out.add(vanillaFill(mc, 0, h - 1, w, h, o));
        out.add(vanillaFill(mc, 0, 1, 1, h - 1, o));
        out.add(vanillaFill(mc, w - 1, 1, w, h - 1, o));
        int slotBg = withAlpha(card, opacity);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int cx = pad + (vert ? row : col) * cell;
                int cy = pad + (vert ? col : row) * cell;
                out.add(mini ? vanillaFill(mc, cx, cy, cx + cell - 1, cy + cell - 1, slotBg)
                        : vanillaFill(mc, cx + 1, cy + 1, cx + cell - 1, cy + cell - 1, slotBg));
            }
        }
        if (!mini) {
            int color = withAlpha(border, opacity);
            int cols = vert ? 3 : 9;
            int rows = vert ? 9 : 3;
            int x0 = pad;
            int y0 = pad;
            int right = x0 + cols * cell;
            int bottom = y0 + rows * cell;
            out.add(vanillaFill(mc, x0, y0, right, y0 + 1, color));
            for (int r = 1; r < rows; r++) {
                int ly = y0 + r * cell;
                out.add(vanillaFill(mc, x0, ly - 1, right, ly + 1, color));
            }
            out.add(vanillaFill(mc, x0, bottom - 1, right, bottom, color));
            for (int r = 0; r < rows; r++) {
                int top = y0 + r * cell + 1;
                int bot = y0 + (r + 1) * cell - 1;
                out.add(vanillaFill(mc, x0, top, x0 + 1, bot, color));
                for (int c = 1; c < cols; c++) {
                    int lx = x0 + c * cell;
                    out.add(vanillaFill(mc, lx - 1, top, lx + 1, bot, color));
                }
                out.add(vanillaFill(mc, right - 1, top, right, bot, color));
            }
        }
        if (opacity < 100) {
            out.add(vanillaFill(mc, 0, 0, w, h, withAlpha(0x000000, (100 - opacity) * 4 / 5)));
        }
        out.removeIf(java.util.Objects::isNull);
        return out;
    }

    // ---- Score Calculator ----

    private static List<String> scoreChecks(List<String> report) {
        List<String> fails = new ArrayList<>();
        String f = "scorecalc.ScoreCalculatorFeature";
        Object cfg = Mod.cfg("scorecalc.ScoreCalculatorConfig");
        Object result = Mod.field(f, "lastResult");
        if (result == null) {
            // The bench's sim run may not be scored yet; give the HUD a result (kept for the draw phase below).
            try {
                var ctor = Mod.cls("scorecalc.ScoreCalculator$Result").getDeclaredConstructors()[0];
                ctor.setAccessible(true);
                Mod.setField(f, "lastResult", ctor.newInstance(287, 100, 90, 60, 30, 100, 0, 30, 50, 45, 9, "S"));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
        int found = (Integer) Mod.field(f, "secretsFound");
        int crypts = (Integer) Mod.field(f, "crypts");
        int deaths = (Integer) Mod.field(f, "deaths");
        boolean prince = (Boolean) Mod.field(f, "princeKilled");
        int compared = 0;
        try {
            Runnable[] steps = {
                    () -> { },
                    () -> Mod.setField(f, "secretsFound", found + 3),
                    () -> Mod.setField(f, "crypts", crypts + 2),
                    () -> Mod.setField(f, "deaths", deaths + 1),
                    () -> Mod.setField(f, "princeKilled", !prince),
                    () -> Mod.call(cfg, "setShowBreakdown", false),
                    () -> Mod.call(cfg, "setShowBreakdown", true),
            };
            Object previous = null;
            for (Runnable step : steps) {
                step.run();
                Object cached = Mod.staticCall(f, "lines", cfg, false);
                Object fresh = Mod.staticCall(f, "buildLines", cfg, false);
                compared++;
                if (!cached.equals(fresh)) {
                    fails.add("Score Calculator's cached lines differ from a fresh build after step " + compared
                            + ":\n    cached " + cached + "\n    fresh  " + fresh);
                }
                Object again = Mod.staticCall(f, "lines", cfg, false);
                if (again != cached) {
                    fails.add("Score Calculator rebuilt its lines with nothing changed (step " + compared + ")");
                }
                if (previous != null && previous == cached && step != steps[0]) {
                    fails.add("Score Calculator kept its old lines after an input changed (step " + compared + ")");
                }
                previous = cached;
            }
        } finally {
            Mod.setField(f, "secretsFound", found);
            Mod.setField(f, "crypts", crypts);
            Mod.setField(f, "deaths", deaths);
            Mod.setField(f, "princeKilled", prince);
        }
        report.add("score calculator: " + compared + " input changes, cached == buildLines each time");
        return fails;
    }

    // ---- Advanced Position ----

    private static String fmt(double v, int places) {
        StringBuilder pattern = new StringBuilder("0");
        if (places > 0) {
            pattern.append('.');
            pattern.append("0".repeat(places));
        }
        DecimalFormat d = new DecimalFormat(pattern.toString(), DecimalFormatSymbols.getInstance(Locale.ROOT));
        d.setGroupingUsed(false);
        return d.format(v);
    }

    private static List<String> positionCheck(int[] reused) {
        List<String> fails = new ArrayList<>();
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        var player = mc.player;
        Object cfg = Mod.cfg("position.PositionConfig");
        int places = (Integer) Mod.call(cfg, "getDecimalPlaces");
        Object prevX = Mod.field("position.PositionFeature", "xLine");
        String[] lines = (String[]) Mod.staticCall("position.PositionFeature", "lines", cfg, player);
        float view = (Float) Mod.staticCall("util.ViewFreeze", "viewYaw");
        float viewP = (Float) Mod.staticCall("util.ViewFreeze", "viewPitch");
        boolean frozen = !Float.isNaN(view) && !Float.isNaN(viewP);
        float yaw = frozen ? view : player.getYRot();
        float pitch = frozen ? viewP : player.getXRot();
        List<String> want = new ArrayList<>();
        want.add("§6X: §f" + fmt(player.getX(), places));
        want.add("§6Y: §f" + fmt(player.getY(), places));
        want.add("§6Z: §f" + fmt(player.getZ(), places));
        if ((Boolean) Mod.call(cfg, "isShowFacing")) {
            want.add("§6Yaw §f" + fmt(yaw, places) + " (" + fmt(net.minecraft.util.Mth.wrapDegrees(yaw), places) + ")"
                    + (frozen ? " §8body " + fmt(player.getYRot(), places) : ""));
            want.add("§6Pitch §f" + fmt(pitch, places));
        }
        if ((Boolean) Mod.call(cfg, "isShowBlock")) {
            var b = player.blockPosition();
            want.add("§6Block: §f" + b.getX() + ", " + b.getY() + ", " + b.getZ());
        }
        if ((Boolean) Mod.call(cfg, "isShowVelocity")) {
            var v = player.getDeltaMovement();
            want.add("§6Velocity: §fX " + fmt(v.x, places) + " Y " + fmt(v.y, places) + " Z " + fmt(v.z, places));
        }
        if (!List.of(lines).equals(want)) {
            fails.add("Advanced Position lines " + List.of(lines) + " differ from a fresh format " + want);
        }
        if (prevX != null) {
            reused[1]++;
            if (prevX == lines[0]) {
                reused[0]++;
            }
        }
        return fails;
    }

    // ---- direct allocation ----

    private static Map<String, double[]> directAllocations() {
        Map<String, double[]> out = new LinkedHashMap<>();
        Object score = Mod.staticCall("hud.HudElementRegistry", "byId", "score_calculator");
        Object position = Mod.staticCall("hud.HudElementRegistry", "byId", "advanced_position");
        Object splits = Mod.staticCall("hud.HudElementRegistry", "byId", "split_timers");
        Class<?> reg = Mod.cls("hud.HudElementRegistry");
        java.lang.reflect.Method resolve;
        java.lang.reflect.Method layout;
        java.lang.reflect.Method scoreWidth;
        java.lang.reflect.Method positionWidth;
        try {
            resolve = reg.getMethod("resolvePosition", Mod.cls("hud.HudElement"));
            layout = splits.getClass().getMethod("layoutPosition");
            layout.setAccessible(true);
            scoreWidth = score.getClass().getMethod("width");
            scoreWidth.setAccessible(true);
            positionWidth = position.getClass().getMethod("width");
            positionWidth.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        out.put("score_calculator width()", perCall(() -> invoke(scoreWidth, score)));
        out.put("advanced_position width()", perCall(() -> invoke(positionWidth, position)));
        out.put("score_calculator resolvePosition()", perCall(() -> invoke(resolve, null, score)));
        out.put("split_timers layoutPosition()", perCall(() -> invoke(layout, splits)));
        return out;
    }

    private static Object invoke(java.lang.reflect.Method m, Object target, Object... args) {
        try {
            return m.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** Bytes and microseconds per call, after a warm-up, on this thread. Reflection's own cost is measured on an empty
     *  call first and subtracted. */
    private static double[] perCall(Runnable r) {
        for (int i = 0; i < 300; i++) {
            r.run();
        }
        int n = 3000;
        long b0 = PerfBench.threadAllocated();
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            r.run();
        }
        long t1 = System.nanoTime();
        long b1 = PerfBench.threadAllocated();
        double base = baseline();
        return new double[]{Math.max(0, (b1 - b0) / (double) n - base), (t1 - t0) / 1e3 / n};
    }

    private static double baseline() {
        Object o = new Object();
        java.lang.reflect.Method m;
        try {
            m = Object.class.getMethod("hashCode");
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
        for (int i = 0; i < 300; i++) {
            invoke(m, o);
        }
        long b0 = PerfBench.threadAllocated();
        for (int i = 0; i < 3000; i++) {
            invoke(m, o);
        }
        return (PerfBench.threadAllocated() - b0) / 3000.0;
    }
}

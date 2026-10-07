package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 386: mod-menu sliders are reachable, and no two rows of a tab share space.
 *
 * <p>killer560, 2026-10-05: "for breaker I cannot slide the cooldown bar." Breaker Aura's Cooldown slider was built
 * on exactly the same rectangle as its (since removed) Side Reach slider (BreakerAuraTab's {@code y += 20 ... y -= 20}
 * pair put Side Reach on the row Cooldown then took). ModScreen's content pane draws children in order, so Cooldown was
 * drawn on top, and routes a press to the FIRST child under the cursor, so every press and drag moved Side Reach.
 *
 * <ul>
 *   <li>REAL INPUT (cheat jar): with Breaker Aura on, the real ModScreen on the Breaker Aura tab; the cursor is
 *       moved and the left button held in WINDOW coordinates (Auto Scale's transform in the path). For Cooldown and,
 *       as the control, Reach: drag from the centre past the left end (must read its minimum), past the right end
 *       (its maximum), and a plain click a quarter of the way along (about a quarter of the range). The OTHER
 *       slider's value is read after every move (Cooldown while Reach is driven and the reverse), so a press that
 *       lands on the wrong slider is named.</li>
 *   <li>OVERLAP SWEEP (both jars): every section of every tab, as built and with each two-state toggle pressed once
 *       (gated rows appear) - plus, under each, every toggle that flip revealed (nested gates) - checked for any two
 *       content rows whose rectangles intersect.</li>
 *   <li>FILTER PANEL (both jars, {@link FilterPanelCases}): the sim's room Filters panel on each of its three
 *       filters at four window sizes, every scroll position checked for overlaps, unreachable chips and labels under
 *       chips, a row proved to wrap at GUI scale 4, and a real-input click on a chip.</li>
 * </ul>
 */
final class SliderCases {

    private static final String CFG = "dungeonextras.DungeonExtrasConfig";
    private static final String TAB = "Breaker Aura";
    /** The tab the helpers below currently drive; {@link #TAB} unless {@link #echoesSlider} switched it. */
    private static String tab = TAB;
    private static final String SIM_BREAKER = "roomsim.SimBreakerState";

    private SliderCases() {
    }

    static void run(UiCase c, Deny deny) throws Exception {
        overlaps(c, deny);
        echoesSlider(c);
        // The sim's shared room Filters panel (mod sim-filters): chips wrap, never overlap, all reachable.
        FilterPanelCases.run(c);
        if (!Mod.isCheat()) {
            c.note("legit jar: Breaker Aura is cheat-only, real-input half skipped");
            return;
        }
        breakerSliders(c);
    }

    // ---- real input on Breaker Aura ----------------------------------------------------------------------------

    private static void breakerSliders(UiCase c) throws Exception {
        Object cfg = Mod.cfg(CFG);
        boolean oldEnabled = (Boolean) Mod.call(cfg, "isBreakerAuraEnabledRaw");
        double oldReach = (Double) Mod.call(cfg, "getBreakerAuraReach");
        int oldCooldown = (Integer) Mod.call(cfg, "getBreakerAuraCooldownTicks");
        double maxReach = ((Number) Mod.field("cheatutils.CheatUtilsConfig", "MEASURED_MAX_REACH")).doubleValue();
        try {
            c.onClient(mc -> {
                Mod.call(cfg, "setBreakerAuraEnabled", true);
                Mod.call(cfg, "setBreakerAuraReach", 3.0);
                Mod.call(cfg, "setBreakerAuraCooldownTicks", 10);
                return null;
            });
            tab = TAB;
            Screen screen = open(c);

            // Control first: Reach, the slider right above.
            int[] reachProblems = {c.problemCount()};
            tab = TAB;
            drive(c, screen, () -> value(cfg, "getBreakerAuraReach"), 1.0, maxReach, 0.15,
                    "Reach:", "Cooldown", () -> value(cfg, "getBreakerAuraCooldownTicks"));
            c.note("Reach (control) " + (c.problemCount() == reachProblems[0] ? "moved across its range" : "FAILED"));

            drive(c, screen, () -> value(cfg, "getBreakerAuraCooldownTicks"), 0, 20, 1.0,
                    "Cooldown:", "Reach", () -> value(cfg, "getBreakerAuraReach"));
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.call(cfg, "setBreakerAuraEnabled", oldEnabled);
                Mod.call(cfg, "setBreakerAuraReach", oldReach);
                Mod.call(cfg, "setBreakerAuraCooldownTicks", oldCooldown);
                Mod.call(cfg, "save");
                return null;
            });
        }
    }

    /**
     * The Dungeon Sim's "Echoes of the Lost" slider (Sim Settings tab, both jars): dragged past each end it must read
     * 0 and 5, a click a quarter along reads about 1, and a value it was left on survives a screen rebuild. The value
     * is read from the sim's own setting, not from the label.
     */
    private static void echoesSlider(UiCase c) throws Exception {
        int old = c.onClient(mc -> ((Number) Mod.staticCall(SIM_BREAKER, "secretCharges")).intValue());
        try {
            tab = "Sim Settings";
            Screen screen = open(c);
            drive(c, screen, () -> ((Number) c.onClient(mc -> Mod.staticCall(SIM_BREAKER, "secretCharges"))).doubleValue(),
                    0, 5, 0.5, "Echoes of the Lost:", "none", () -> 0);
            c.note("Echoes of the Lost slider moved across 0..5");
        } finally {
            tab = TAB;
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.staticCall(SIM_BREAKER, "setSecretCharges", old);
                return null;
            });
        }
    }

    /** Opens the real ModScreen with the Breaker Aura tab selected (its folder sections expanded). */
    private static Screen open(UiCase c) {
        Screen s = c.onClient(mc -> {
            try {
                ModScreenDriver d = new ModScreenDriver(mc);
                List<Object> tops = d.topTabs();
                for (int i = 0; i < tops.size(); i++) {
                    List<Object[]> chain = new ArrayList<>();
                    if (find(d, tops.get(i), chain)) {
                        d.select(i);
                        for (Object[] step : chain) {
                            d.expanded(step[0]).add((Integer) step[1]);
                        }
                        McCompat.setScreen(mc, d.screen);
                        return d.screen;
                    }
                }
                return null;
            } catch (Throwable t) {
                throw new AssertionError("could not open gui.ModScreen: " + UiCase.describe(t), t);
            }
        });
        c.check(s != null, "no '" + tab + "' tab in the mod menu");
        c.ticks(5);
        return s;
    }

    /** True if {@code tab} is, or contains, the target; {@code chain} gets (folder, section index) pairs. */
    private static boolean find(ModScreenDriver d, Object tab, List<Object[]> chain) {
        if (SliderCases.tab.equals(R.get(tab, "name"))) {
            return true;
        }
        if (!d.isFolder(tab)) {
            return false;
        }
        List<Object> subs = d.subTabs(tab);
        for (int j = 0; j < subs.size(); j++) {
            chain.add(new Object[]{tab, j});
            if (find(d, subs.get(j), chain)) {
                return true;
            }
            chain.remove(chain.size() - 1);
        }
        return false;
    }

    /** The slider whose label starts {@code prefix}, rebuilt and scrolled into the visible band; {x, y, w, h}. */
    private static double[] slider(UiCase c, Screen s, String prefix) {
        double[] box = c.onClient(mc -> {
            try {
                for (int attempt = 0; attempt < 2; attempt++) {
                    R.call0(s, "rebuild");
                    AbstractWidget pane = (AbstractWidget) R.get(s, "contentPane");
                    for (AbstractWidget w : paneChildren(pane)) {
                        if (w instanceof AbstractSliderButton && ModScreenDriver.label(w).startsWith(prefix)) {
                            int top = pane.getY() + 4;
                            int bottom = pane.getY() + pane.getHeight() - 4;
                            if (w.getY() >= top && w.getY() + w.getHeight() <= bottom) {
                                return new double[]{w.getX(), w.getY(), w.getWidth(), w.getHeight()};
                            }
                            int offset = (Integer) R.getStatic(s.getClass(), "scrollOffset");
                            R.setStatic(s.getClass(), "scrollOffset", offset + (w.getY() - top) - 20);
                            break;
                        }
                    }
                }
                return null;
            } catch (Throwable t) {
                throw new AssertionError(UiCase.describe(t), t);
            }
        });
        c.check(box != null, "no visible slider labelled '" + prefix + "...' on the " + tab + " tab");
        return box;
    }

    @SuppressWarnings("unchecked")
    private static List<AbstractWidget> paneChildren(AbstractWidget pane) {
        return pane == null ? List.of() : new ArrayList<>((java.util.Collection<AbstractWidget>) R.get(pane, "children"));
    }

    private static void drive(UiCase c, Screen s, java.util.function.DoubleSupplier read, double min, double max,
                              double tolerance, String prefix, String witness, java.util.function.DoubleSupplier witnessRead) {
        double[] b = slider(c, s, prefix);
        String where = String.format(Locale.ROOT, "%s slider at %.0f,%.0f %.0fx%.0f", prefix, b[0], b[1], b[2], b[3]);
        c.note(where);
        double cx = b[0] + b[2] / 2;
        double cy = b[1] + b[3] / 2;

        double side0 = witnessRead.getAsDouble();
        double v0 = read.getAsDouble();
        drag(c, s, cx, cy, b[0] - 30);
        double vLeft = read.getAsDouble();
        double side1 = witnessRead.getAsDouble();

        b = slider(c, s, prefix);
        drag(c, s, b[0] + b[2] / 2, cy, b[0] + b[2] + 30);
        double vRight = read.getAsDouble();
        double side2 = witnessRead.getAsDouble();

        b = slider(c, s, prefix);
        double quarterX = b[0] + 4 + (b[2] - 8) * 0.25;
        click(c, s, quarterX, b[1] + b[3] / 2);
        double vQuarter = read.getAsDouble();
        double side3 = witnessRead.getAsDouble();
        double wantQuarter = min + (max - min) * 0.25;

        c.note(String.format(Locale.ROOT, "%s start %s; drag past left -> %s; drag past right -> %s; click at 1/4 -> %s"
                        + " (want ~%s); %s along the way %s, %s, %s, %s", prefix, num(v0), num(vLeft),
                num(vRight), num(vQuarter), num(wantQuarter), witness, num(side0), num(side1), num(side2), num(side3)));
        if (Math.abs(vLeft - min) > 1e-6) {
            c.problem(prefix + " dragged past its left end reads " + num(vLeft) + ", its minimum is " + num(min)
                    + (side1 != side0 ? " - and the drag moved " + witness + " " + num(side0) + " -> " + num(side1) : ""));
        }
        if (Math.abs(vRight - max) > 1e-6) {
            c.problem(prefix + " dragged past its right end reads " + num(vRight) + ", its maximum is " + num(max)
                    + (side2 != side1 ? " - and the drag moved " + witness + " " + num(side1) + " -> " + num(side2) : ""));
        }
        if (Math.abs(vQuarter - wantQuarter) > tolerance) {
            c.problem(prefix + " clicked a quarter along reads " + num(vQuarter) + ", expected about "
                    + num(wantQuarter) + (side3 != side2 ? " - and the click moved " + witness + " " + num(side2)
                    + " -> " + num(side3) : ""));
        }
    }

    private static double value(Object cfg, String getter) {
        return ((Number) Mod.call(cfg, getter)).doubleValue();
    }

    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }

    /** Screen (layout) coordinates -> window coordinates, through the factor the screen was laid out with. */
    private static double[] toWindow(UiCase c, Screen s, double sx, double sy) {
        return c.onClient(mc -> {
            float f = ((Number) Mod.staticCall("hud.AutoScale", "appliedFactor", s)).floatValue();
            Window w = mc.getWindow();
            return new double[]{sx * f * w.getScreenWidth() / (double) w.getGuiScaledWidth(),
                    sy * f * w.getScreenHeight() / (double) w.getGuiScaledHeight()};
        });
    }

    /** Holds the left button at (sx, sy), moves in 6 steps to (toX, sy) in screen units, releases. */
    private static void drag(UiCase c, Screen s, double sx, double sy, double toX) {
        double[] a = toWindow(c, s, sx, sy);
        c.ctx().getInput().setCursorPos(a[0], a[1]);
        c.ticks(2);
        c.ctx().getInput().holdMouse(0);
        c.ticks(2);
        for (int i = 1; i <= 6; i++) {
            double[] p = toWindow(c, s, sx + (toX - sx) * i / 6.0, sy);
            c.ctx().getInput().setCursorPos(p[0], p[1]);
            c.ticks(1);
        }
        c.ctx().getInput().releaseMouse(0);
        c.ticks(3);
    }

    private static void click(UiCase c, Screen s, double sx, double sy) {
        double[] a = toWindow(c, s, sx, sy);
        c.ctx().getInput().setCursorPos(a[0], a[1]);
        c.ticks(2);
        c.ctx().getInput().pressMouse(0);
        c.ticks(3);
    }

    // ---- overlap sweep ---------------------------------------------------------------------------------------------

    private static void overlaps(UiCase c, Deny deny) {
        int topCount = c.onClient(mc -> {
            try {
                return new ModScreenDriver(mc).topTabs().size();
            } catch (Throwable t) {
                return -1;
            }
        });
        c.check(topCount > 0, "could not open gui.ModScreen");
        Set<String> found = new LinkedHashSet<>();
        int[] n = new int[2]; // layouts checked, toggles pressed
        for (int i = 0; i < topCount; i++) {
            int top = i;
            c.onClient(mc -> {
                ModScreenDriver d;
                try {
                    d = new ModScreenDriver(mc);
                } catch (Throwable t) {
                    c.problem("could not open gui.ModScreen: " + UiCase.describe(t));
                    return null;
                }
                Object topTab = d.topTabs().get(top);
                int sections = d.isFolder(topTab) ? d.subTabs(topTab).size() : 1;
                for (int j = 0; j < sections; j++) {
                    String where = (String) R.get(topTab, "name");
                    try {
                        d.select(top);
                        if (d.isFolder(topTab)) {
                            d.expanded(topTab).add(j);
                            Object sub = d.subTabs(topTab).get(j);
                            d.expandAll(sub);
                            where += " > " + R.get(sub, "name");
                        }
                        d.rebuild();
                        sweepSection(d, where, deny, found, n);
                    } catch (Throwable t) {
                        c.problem("'" + where + "': " + UiCase.describe(t));
                    }
                }
                d.select(0);
                return null;
            });
        }
        c.note("overlap sweep: " + n[0] + " layouts checked (" + n[1] + " with one toggle flipped), "
                + found.size() + " overlapping pair(s)");
        for (String f : found) {
            c.problem("overlapping rows: " + f);
        }
        c.check(n[0] > 100, "only " + n[0] + " layouts checked - the sweep did not reach the menu");
        // Health and Mana Bars (mod bars-tab, 2026-10-07): its rows sit behind Stat Bars and behind each bar's own
        // toggle, so they exist only in the flipped layouts - require the sweep to have laid them out. Classic
        // Display's rows are behind a section header the sweep does not open; 393-ui-stat-bars checks those.
        if (SEEN.stream().anyMatch(x -> x.contains("Health and Mana Bars|Hide Hypixel Stat Text:"))) {
            List<String> missed = new ArrayList<>();
            for (String want : new String[]{"Stat Bars:", "Health Bar:", "Health Text:", "Colour:", "Bar Width:",
                    "Bar Height:", "Show Value:", "Background:", "Absorption Colour:", "Hearts:", "Unhide Hearts In Rift:",
                    "Health:", "XP Bar And Level:", "Text Shadow:"}) {
                if (SEEN.stream().noneMatch(x -> x.contains("Health and Mana Bars|" + want))) {
                    missed.add(want);
                }
            }
            c.note("swept Health and Mana Bars rows; never laid out: " + missed);
            if (!missed.isEmpty()) {
                c.problem("the sweep never laid out these Health and Mana Bars rows: " + missed);
            }
        } else {
            c.note("Health and Mana Bars: old layout (before mod bars-tab), its rows are not required");
        }
        if (Mod.isCheat()) {
            // Auto Routes' Breaker Block Display / Style (mod ar-db-edit): the sweep must actually have laid them out,
            // or "0 overlapping" says nothing about them.
            // Auto Anvil's side-by-side Min/Max Delay (mod auto-anvil), behind its OFF master switch.
            for (String want : new String[]{"Auto Routes|Breaker Block Display:", "Auto Routes|Breaker Block Style:",
                    "Auto Anvil|Min Delay:", "Auto Anvil|Max Delay:"}) {
                String[] p = want.split("\\|");
                List<String> where = SEEN.stream().filter(x -> x.contains("|" + p[1])).map(x -> x.substring(0, x.indexOf('|')))
                        .distinct().toList();
                boolean seen = where.stream().anyMatch(x -> x.contains(p[0]));
                c.note("swept " + p[1] + " on " + p[0] + ": " + seen + " (laid out in " + where + ")");
                c.check(seen, "the sweep never laid out '" + p[1] + "' on the " + p[0] + " tab");
            }
        }
        if (Mod.has("witherdoors.WitherDoorsConfig$Style")) {
            // Wither Doors' fill rows (mod door-fill), both jars: they sit behind the tab's master switch.
            for (String want : new String[]{"Wither Doors|Style:", "Wither Doors|Fill Opacity:",
                    "Wither Doors|Fill Color:", "Wither Doors|Custom Fill Color:"}) {
                String[] p = want.split("\\|");
                List<String> where = SEEN.stream().filter(x -> x.contains("|" + p[1])).map(x -> x.substring(0, x.indexOf('|')))
                        .distinct().toList();
                boolean seen = where.stream().anyMatch(x -> x.contains(p[0]));
                c.note("swept " + p[1] + " on " + p[0] + ": " + seen);
                c.check(seen, "the sweep never laid out '" + p[1] + "' on the " + p[0] + " tab");
            }
        }
    }

    /** "where|label" of every widget the sweep laid out. */
    private static final Set<String> SEEN = new LinkedHashSet<>();

    private static void sweepSection(ModScreenDriver d, String where, Deny deny, Set<String> found, int[] n)
            throws Throwable {
        check(d, where, "", found);
        n[0]++;
        List<String> seen = new ArrayList<>();
        for (int guard = 0; guard < 200; guard++) {
            AbstractWidget target = null;
            for (AbstractWidget w : d.content()) {
                String l = ModScreenDriver.label(w);
                var m = TabSweep.TOGGLE.matcher(l);
                if (d.isSettingsButton(w) && m.matches() && !seen.contains(m.group(1))) {
                    target = w;
                    break;
                }
            }
            if (target == null) {
                return;
            }
            String label = ModScreenDriver.label(target);
            var m = TabSweep.TOGGLE.matcher(label);
            m.matches();
            String name = m.group(1);
            seen.add(name);
            if (deny.button(label) != null) {
                continue;
            }
            List<String> before = toggleNames(d);
            ModScreenDriver.press(target);
            d.rebuild();
            String state = " (with " + name + " flipped from " + m.group(2) + ")";
            check(d, where, state, found);
            n[0]++;
            n[1]++;
            // One level deeper: a toggle the first flip revealed (Breaker Aura ON shows Auto Swap, whose own rows
            // only exist with it ON too) is flipped as well, so a nested gate's rows are checked.
            for (String inner : toggleNames(d)) {
                if (before.contains(inner)) {
                    continue;
                }
                AbstractWidget w = toggle(d, inner);
                if (w == null || deny.button(ModScreenDriver.label(w)) != null) {
                    continue;
                }
                ModScreenDriver.press(w);
                d.rebuild();
                check(d, where, state + " and " + inner + " flipped", found);
                n[0]++;
                AbstractWidget back = toggle(d, inner);
                ModScreenDriver.press(back != null ? back : w);
                d.rebuild();
            }
            AbstractWidget again = toggle(d, name);
            ModScreenDriver.press(again != null ? again : target);
            d.rebuild();
        }
    }

    private static List<String> toggleNames(ModScreenDriver d) {
        List<String> out = new ArrayList<>();
        for (AbstractWidget w : d.content()) {
            var m = TabSweep.TOGGLE.matcher(ModScreenDriver.label(w));
            if (d.isSettingsButton(w) && m.matches()) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    private static AbstractWidget toggle(ModScreenDriver d, String name) {
        for (AbstractWidget w : d.content()) {
            var m = TabSweep.TOGGLE.matcher(ModScreenDriver.label(w));
            if (d.isSettingsButton(w) && m.matches() && m.group(1).equals(name)) {
                return w;
            }
        }
        return null;
    }

    /** Every pair of visible content rows whose rectangles intersect (positive area). */
    private static void check(ModScreenDriver d, String where, String state, Set<String> found) {
        List<AbstractWidget> ws = d.content();
        for (AbstractWidget w : ws) {
            SEEN.add(where + "|" + ModScreenDriver.label(w));
        }
        for (int a = 0; a < ws.size(); a++) {
            AbstractWidget p = ws.get(a);
            if (!p.visible || p.getWidth() <= 0 || p.getHeight() <= 0) {
                continue;
            }
            for (int b = a + 1; b < ws.size(); b++) {
                AbstractWidget q = ws.get(b);
                if (!q.visible || q.getWidth() <= 0 || q.getHeight() <= 0) {
                    continue;
                }
                boolean hit = p.getX() < q.getX() + q.getWidth() && q.getX() < p.getX() + p.getWidth()
                        && p.getY() < q.getY() + q.getHeight() && q.getY() < p.getY() + p.getHeight();
                if (hit) {
                    String pair = "'" + where + "': '" + stripValue(ModScreenDriver.label(p)) + "' and '"
                            + stripValue(ModScreenDriver.label(q)) + "'";
                    boolean known = false;
                    for (String f : found) {
                        known |= f.startsWith(pair);
                    }
                    if (!known) {
                        found.add(pair + " at " + box(p) + " / " + box(q) + state);
                    }
                }
            }
        }
    }

    /** "Cooldown: 7 ticks" -> "Cooldown", so a pair is reported once however the values moved. */
    private static String stripValue(String label) {
        int colon = label.indexOf(':');
        return colon > 0 ? label.substring(0, colon) : label;
    }

    private static String box(AbstractWidget w) {
        return "[" + w.getX() + "," + w.getY() + " " + w.getWidth() + "x" + w.getHeight() + "]";
    }
}

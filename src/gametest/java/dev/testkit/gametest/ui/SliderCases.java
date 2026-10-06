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
 * on exactly the same rectangle as its Side Reach slider (BreakerAuraTab's {@code y += 20 ... y -= 20} pair put
 * Side Reach on the row Cooldown then took). ModScreen's content pane draws children in order, so Cooldown was drawn
 * on top, and routes a press to the FIRST child under the cursor, so every press and drag moved Side Reach.
 *
 * <ul>
 *   <li>REAL INPUT (cheat jar): with Breaker Aura on, the real ModScreen on the Breaker Aura tab; the cursor is
 *       moved and the left button held in WINDOW coordinates (Auto Scale's transform in the path). For Cooldown and,
 *       as the control, Reach: drag from the centre past the left end (must read its minimum), past the right end
 *       (its maximum), and a plain click a quarter of the way along (about a quarter of the range). Side Reach is
 *       read after every move, so a press that lands on the wrong slider is named.</li>
 *   <li>OVERLAP SWEEP (both jars): every section of every tab, as built and with each two-state toggle pressed once
 *       (gated rows appear) - plus, under each, every toggle that flip revealed (nested gates) - checked for any two
 *       content rows whose rectangles intersect.</li>
 * </ul>
 */
final class SliderCases {

    private static final String CFG = "dungeonextras.DungeonExtrasConfig";
    private static final String TAB = "Breaker Aura";

    private SliderCases() {
    }

    static void run(UiCase c, Deny deny) throws Exception {
        overlaps(c, deny);
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
        double oldSide = (Double) Mod.call(cfg, "getBreakerAuraSideReach");
        int oldCooldown = (Integer) Mod.call(cfg, "getBreakerAuraCooldownTicks");
        double maxReach = ((Number) Mod.field("cheatutils.CheatUtilsConfig", "MEASURED_MAX_REACH")).doubleValue();
        try {
            c.onClient(mc -> {
                Mod.call(cfg, "setBreakerAuraEnabled", true);
                Mod.call(cfg, "setBreakerAuraReach", 3.0);
                Mod.call(cfg, "setBreakerAuraSideReach", 1.0);
                Mod.call(cfg, "setBreakerAuraCooldownTicks", 10);
                return null;
            });
            Screen screen = open(c);

            // Control first: Reach, the slider right above.
            int[] reachProblems = {c.problemCount()};
            drive(c, screen, cfg, "Reach:", "getBreakerAuraReach", 1.0, maxReach, 0.15);
            c.note("Reach (control) " + (c.problemCount() == reachProblems[0] ? "moved across its range" : "FAILED"));

            drive(c, screen, cfg, "Cooldown:", "getBreakerAuraCooldownTicks", 0, 20, 1.0);
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.call(cfg, "setBreakerAuraEnabled", oldEnabled);
                Mod.call(cfg, "setBreakerAuraReach", oldReach);
                Mod.call(cfg, "setBreakerAuraSideReach", oldSide);
                Mod.call(cfg, "setBreakerAuraCooldownTicks", oldCooldown);
                Mod.call(cfg, "save");
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
        c.check(s != null, "no '" + TAB + "' tab in the mod menu");
        c.ticks(5);
        return s;
    }

    /** True if {@code tab} is, or contains, the target; {@code chain} gets (folder, section index) pairs. */
    private static boolean find(ModScreenDriver d, Object tab, List<Object[]> chain) {
        if (TAB.equals(R.get(tab, "name"))) {
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
        c.check(box != null, "no visible slider labelled '" + prefix + "...' on the " + TAB + " tab");
        return box;
    }

    @SuppressWarnings("unchecked")
    private static List<AbstractWidget> paneChildren(AbstractWidget pane) {
        return pane == null ? List.of() : new ArrayList<>((java.util.Collection<AbstractWidget>) R.get(pane, "children"));
    }

    private static void drive(UiCase c, Screen s, Object cfg, String prefix, String getter, double min, double max,
                              double tolerance) {
        double[] b = slider(c, s, prefix);
        String where = String.format(Locale.ROOT, "%s slider at %.0f,%.0f %.0fx%.0f", prefix, b[0], b[1], b[2], b[3]);
        c.note(where);
        double cx = b[0] + b[2] / 2;
        double cy = b[1] + b[3] / 2;

        double side0 = side(cfg);
        double v0 = value(cfg, getter);
        drag(c, s, cx, cy, b[0] - 30);
        double vLeft = value(cfg, getter);
        double side1 = side(cfg);

        b = slider(c, s, prefix);
        drag(c, s, b[0] + b[2] / 2, cy, b[0] + b[2] + 30);
        double vRight = value(cfg, getter);
        double side2 = side(cfg);

        b = slider(c, s, prefix);
        double quarterX = b[0] + 4 + (b[2] - 8) * 0.25;
        click(c, s, quarterX, b[1] + b[3] / 2);
        double vQuarter = value(cfg, getter);
        double side3 = side(cfg);
        double wantQuarter = min + (max - min) * 0.25;

        c.note(String.format(Locale.ROOT, "%s start %s; drag past left -> %s; drag past right -> %s; click at 1/4 -> %s"
                        + " (want ~%s); Side Reach along the way %s, %s, %s, %s", prefix, num(v0), num(vLeft),
                num(vRight), num(vQuarter), num(wantQuarter), num(side0), num(side1), num(side2), num(side3)));
        if (Math.abs(vLeft - min) > 1e-6) {
            c.problem(prefix + " dragged past its left end reads " + num(vLeft) + ", its minimum is " + num(min)
                    + (side1 != side0 ? " - and the drag moved Side Reach " + num(side0) + " -> " + num(side1) : ""));
        }
        if (Math.abs(vRight - max) > 1e-6) {
            c.problem(prefix + " dragged past its right end reads " + num(vRight) + ", its maximum is " + num(max)
                    + (side2 != side1 ? " - and the drag moved Side Reach " + num(side1) + " -> " + num(side2) : ""));
        }
        if (Math.abs(vQuarter - wantQuarter) > tolerance) {
            c.problem(prefix + " clicked a quarter along reads " + num(vQuarter) + ", expected about "
                    + num(wantQuarter));
        }
    }

    private static double value(Object cfg, String getter) {
        return ((Number) Mod.call(cfg, getter)).doubleValue();
    }

    private static double side(Object cfg) {
        return value(cfg, "getBreakerAuraSideReach");
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
    }

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

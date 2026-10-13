package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 806: every cycling setting in the mod menu steps BACK one value on a right click and forward one on a left click,
 * wrapping at both ends (killer560, 2026-10-11: "for any setting that you click a few times to toggle through different
 * things make it so if i left click it goes forward and right click it goes backward").
 *
 * <p>The menu is walked the way 512 walks it: every category, every section with its collapsibles open, in the default
 * layout and with each ON/OFF switch flipped in turn (so a cycler a switch reveals is reached). A cycling button is a
 * {@code gui.SettingsButtonWidget} whose {@code isCycling()} is true (mod cycle-back: every cycler is built through
 * {@code SettingsButtonWidget.cycling} or carries a right-click handler). For each one: left clicks through the screen
 * record its whole ring of labels until it comes back round; then REAL clicks through the window (cursor placed on the
 * button, {@code TestInput.pressMouse}, so the press goes through MouseHandler, Auto Scale and ModScreen's own routing)
 * - right, right, left, left - must show the ring's last value, the one before it, the last again and the start. A
 * ring longer than {@link #MAX_RING} is checked as right-then-left and left-then-right both coming back to the start.
 * A right click must also leave the menu open (no other action took it). Every switch flipped is flipped back and
 * every cycler ends on the value it started on.
 *
 * <p>Since mod doc-menu (killer560, 2026-10-13) a cycler in the menu steps only from its value pill: a left click on the
 * pill's right half (›) goes forward, on its left half (‹) back, a right click on the pill back, and a click on the label
 * changes nothing. So the ring is walked with left clicks on the right half, the real clicks are right / left-on-left /
 * left-on-right / left-on-right (wanted: last, one before, last, start), and a fifth real left click on the label must
 * leave the value where it is. Positions come from the row's own {@code controlBounds()}.
 *
 * <p>Labels that look like "Name: value" with a value other than ON/OFF on a button that is NOT marked cycling are
 * listed as a note (keybinds and one-shot actions land there too), so a cycler that was missed shows up in the log.
 */
final class CycleButtonCases {

    private CycleButtonCases() {
    }

    static final int MAX_RING = 60;
    /**
     * Cyclers whose LABEL hits the deny list but whose click only changes a setting: Auto Update's channel ("update":
     * the deny is for the Check button), Blood Camp's Start Sound Type ("start "), Video Browser's Site (its label names
     * YouTube Shorts; the browser only launches from the Open button), RNG Meter's Bazaar Pricing ("bazaar": it flips
     * one saved boolean and polls nothing). Read in the mod at cycle-back 357cee13 and its follow-up.
     */
    private static final Set<String> ALLOWED = Set.of("Auto Update", "Start Sound Type", "Site", "Bazaar Pricing");
    private static final Pattern VALUE_LABEL = Pattern.compile("^([^:]{2,40}): (.+)$");

    /** One cycling button found in a layout. {@code absY} is its content y with the scroll added back. */
    private record Target(String key, String where, String label, int x, int absY, int width) {
    }

    /** Where a target is right now: its label, the click point in GUI units and in window pixels. */
    private record Spot(String label, double gx, double gy, double wx, double wy) {
    }

    /** Where on the row a click lands: the pill's right half (forward), its left half (back), or the label. */
    private enum Side { RIGHT, LEFT, LABEL }

    static void cycleButtons(UiCase c, Deny deny) throws Exception {
        try (AutoCloseable all = MenuLayoutCases.allTested(c)) {
            ModScreenDriver d = c.onClient(mc -> {
                try {
                    R.setStatic(R.cls(ModScreenDriver.MOD_SCREEN), "tabs", null);
                    ModScreenDriver dd = new ModScreenDriver(mc);
                    McCompat.setScreen(mc, dd.screen);
                    return dd;
                } catch (Throwable t) {
                    throw new AssertionError("could not open gui.ModScreen: " + UiCase.describe(t), t);
                }
            });
            c.ticks(3);
            List<ModScreenDriver.Node> tree = c.onClient(mc -> d.tree());
            Set<String> done = new LinkedHashSet<>();
            Set<String> unmarked = new LinkedHashSet<>();
            int[] stats = new int[6]; // 0 found, 1 full ring passed, 2 long ring inverse passed, 3 single value, 4 failed, 5 layouts
            List<String> couldNot = new ArrayList<>();
            try {
                for (int i = 0; i < tree.size(); i++) {
                    ModScreenDriver.Node topNode = tree.get(i);
                    int sections = topNode.folder() ? topNode.children().size() : 1;
                    for (int j = 0; j < sections; j++) {
                        int top = i;
                        int sec = j;
                        String where = topNode.name() + (topNode.folder() ? " > " + topNode.children().get(j).name() : "");
                        List<String> toggles = c.onClient(mc -> {
                            open(d, top, sec, deny);
                            List<String> names = new ArrayList<>();
                            for (AbstractWidget w : d.content()) {
                                var m = TabSweep.TOGGLE.matcher(ModScreenDriver.label(w));
                                if (d.isSettingsButton(w) && m.matches() && !names.contains(m.group(1))
                                        && deny.button(ModScreenDriver.label(w)) == null) {
                                    names.add(m.group(1));
                                }
                            }
                            return names;
                        });
                        List<String> layouts = new ArrayList<>();
                        layouts.add(null);
                        layouts.addAll(toggles);
                        for (String flip : layouts) {
                            List<Target> targets = c.onClient(mc -> {
                                open(d, top, sec, deny);
                                if (flip != null) {
                                    AbstractWidget t = toggle(d, flip);
                                    if (t == null) {
                                        return List.<Target>of();
                                    }
                                    ModScreenDriver.press(t);
                                    rebuild(d);
                                }
                                return collect(d, where, done, unmarked);
                            });
                            stats[5]++;
                            for (Target t : targets) {
                                done.add(t.key());
                                stats[0]++;
                                if (deny.button(t.label()) != null && !ALLOWED.contains(t.label().split(":")[0])) {
                                    couldNot.add(t.key() + " (deny list: " + deny.button(t.label()) + ")");
                                    continue;
                                }
                                testOne(c, d, t, stats, couldNot);
                            }
                            if (flip != null) {
                                c.onClient(mc -> {
                                    open(d, top, sec, deny);
                                    AbstractWidget t = toggle(d, flip);
                                    // Opening re-reads the config, so the switch shows its flipped state: flip it back.
                                    if (t != null) {
                                        ModScreenDriver.press(t);
                                        rebuild(d);
                                    }
                                    return null;
                                });
                            }
                        }
                    }
                }
            } finally {
                c.onClient((Minecraft mc) -> {
                    McCompat.setScreen(mc, null);
                    d.select(0);
                    return null;
                });
            }
            c.note("layouts walked " + stats[5] + "; cycling buttons found " + stats[0] + ": full ring checked "
                    + stats[1] + ", long ring (>" + MAX_RING + ") checked both ways " + stats[2] + ", single value "
                    + stats[3] + ", failed " + stats[4] + ", not handled " + couldNot.size());
            for (String s : couldNot) {
                c.note("not handled: " + s);
            }
            c.note("value-style labels on buttons NOT marked cycling (" + unmarked.size() + "): " + unmarked);
            c.check(stats[0] >= (Mod.isCheat() ? 60 : 40), "only " + stats[0] + " cycling buttons found - the walk is broken");
            c.check(stats[5] > 100, "only " + stats[5] + " layouts walked");
            c.check(stats[1] + stats[2] > 0, "no cycler was actually stepped");
        }
    }

    private static void testOne(UiCase c, ModScreenDriver d, Target t, int[] stats, List<String> couldNot) {
        Spot s0 = c.onClient(mc -> locate(d, t, true, Side.RIGHT));
        if (s0 == null) {
            couldNot.add(t.key() + " (could not scroll it into the pane)");
            return;
        }
        String start = s0.label();
        // The ring, by left clicks through the screen's own routing.
        List<String> ring = c.onClient(mc -> {
            List<String> seen = new ArrayList<>();
            seen.add(start);
            for (int k = 0; k < MAX_RING; k++) {
                Spot s = locate(d, t, false, Side.RIGHT);
                if (s == null) {
                    seen.add(null);
                    return seen;
                }
                d.screen.mouseClicked(new MouseButtonEvent(s.gx(), s.gy(), new MouseButtonInfo(0, 0)), false);
                d.screen.mouseReleased(new MouseButtonEvent(s.gx(), s.gy(), new MouseButtonInfo(0, 0)));
                rebuild(d);
                Spot after = locate(d, t, false, Side.RIGHT);
                if (after == null) {
                    seen.add(null);
                    return seen;
                }
                if (after.label().equals(start)) {
                    return seen;
                }
                seen.add(after.label());
            }
            seen.add("<ring longer than " + MAX_RING + ">");
            return seen;
        });
        if (ring.get(ring.size() - 1) == null) {
            couldNot.add(t.key() + " (the button moved or vanished after a left click; ring so far " + ring + ")");
            stats[4]++;
            c.problem(t.key() + ": lost the button while stepping it forward; ring so far " + ring);
            return;
        }
        boolean longRing = ring.get(ring.size() - 1).startsWith("<ring longer");
        if (longRing) {
            // Walk back to the start before the real clicks: it is MAX_RING forward of it.
            String back = c.onClient(mc -> {
                for (int k = 0; k < MAX_RING; k++) {
                    Spot s = locate(d, t, false, Side.RIGHT);
                    if (s == null) {
                        return null;
                    }
                    d.screen.mouseClicked(new MouseButtonEvent(s.gx(), s.gy(), new MouseButtonInfo(1, 0)), false);
                    d.screen.mouseReleased(new MouseButtonEvent(s.gx(), s.gy(), new MouseButtonInfo(1, 0)));
                    rebuild(d);
                }
                Spot s = locate(d, t, false, Side.RIGHT);
                return s == null ? null : s.label();
            });
            if (!start.equals(back)) {
                stats[4]++;
                c.problem(t.key() + ": " + MAX_RING + " right clicks did not undo " + MAX_RING + " left clicks (start '"
                        + start + "', now '" + back + "')");
                return;
            }
            String r = realClick(c, d, t, 1, Side.RIGHT);
            String l = realClick(c, d, t, 0, Side.RIGHT);
            String l2 = realClick(c, d, t, 0, Side.LEFT);
            String r2 = realClick(c, d, t, 0, Side.RIGHT);
            String lbl = labelClick(c, d, t);
            boolean ok = r != null && !r.equals(start) && start.equals(l) && l2 != null && !l2.equals(start)
                    && start.equals(r2) && (lbl == null || start.equals(lbl));
            if (ok) {
                stats[2]++;
            } else {
                stats[4]++;
                c.problem(t.key() + ": long ring, real clicks right / left-on-right / left-on-left / left-on-right / "
                        + "left-on-label read '" + r + "', '" + l + "', '" + l2 + "', '" + r2 + "', '" + lbl + "' from '"
                        + start + "'");
            }
            return;
        }
        int n = ring.size();
        if (n == 1) {
            stats[3]++;
            couldNot.add(t.key() + " (single value '" + start + "', nothing to step)");
            return;
        }
        String[] want = {ring.get(n - 1), ring.get(Math.floorMod(n - 2, n)), ring.get(n - 1), start, start};
        int[] buttons = {1, 0, 0, 0};
        Side[] sides = {Side.RIGHT, Side.LEFT, Side.RIGHT, Side.RIGHT};
        String[] got = new String[5];
        boolean ok = true;
        for (int k = 0; k < 4; k++) {
            got[k] = realClick(c, d, t, buttons[k], sides[k]);
            ok &= want[k].equals(got[k]);
        }
        // A left click on the label must change nothing (null: the pill covers the whole row, no label to click).
        got[4] = labelClick(c, d, t);
        ok &= got[4] == null || start.equals(got[4]);
        if (ok) {
            stats[1]++;
            c.note(t.key() + ": ring of " + n + " ok");
        } else {
            stats[4]++;
            c.problem(t.key() + ": ring " + ring + "; real right, left-on-left, left-on-right, left-on-right, left-on-label read "
                    + java.util.Arrays.toString(got) + ", wanted " + java.util.Arrays.toString(want));
        }
    }

    /** A real left press on the target's label; what it shows afterwards, or null when the row has no label to click. */
    private static String labelClick(UiCase c, ModScreenDriver d, Target t) {
        Spot s = c.onClient(mc -> locate(d, t, true, Side.LABEL));
        if (s == null) {
            return null;
        }
        return realClick(c, d, t, 0, Side.LABEL);
    }

    /** A real press of {@code button} on the target's {@code side} through the window; the label it shows afterwards
     *  (null if lost). */
    private static String realClick(UiCase c, ModScreenDriver d, Target t, int button, Side side) {
        Spot s = c.onClient(mc -> locate(d, t, true, side));
        if (s == null) {
            return null;
        }
        c.ctx().getInput().setCursorPos(s.wx(), s.wy());
        c.ticks(1);
        c.ctx().getInput().pressMouse(button);
        c.ticks(1);
        return c.onClient(mc -> {
            if (McCompat.screen(mc) != d.screen) {
                return "<the menu closed or another screen opened>";
            }
            rebuild(d);
            Spot after = locate(d, t, false, Side.RIGHT);
            return after == null ? null : after.label();
        });
    }

    /**
     * Finds the target in the current content by its x, width and content y (scroll added back); with {@code scroll},
     * first scrolls it into the pane's visible rectangle. Null when it is not there.
     */
    private static Spot locate(ModScreenDriver d, Target t, boolean scroll, Side side) {
        AbstractWidget w = find(d, t);
        if (w == null) {
            return null;
        }
        AbstractWidget pane = (AbstractWidget) R.get(d.screen, "contentPane");
        if (scroll && pane != null) {
            int top = pane.getY();
            int bottom = pane.getY() + pane.getHeight();
            if (w.getY() < top + 2 || w.getY() + w.getHeight() > bottom - 2) {
                int offset = (Integer) R.getStatic(d.screenCls, "scrollOffset");
                int want = Math.max(0, Math.min(d.maxScroll(), offset + w.getY() - top - 8));
                d.scrollTo(want);
                rebuild(d);
                w = find(d, t);
                pane = (AbstractWidget) R.get(d.screen, "contentPane");
                if (w == null || pane == null || w.getY() < pane.getY() || w.getY() + w.getHeight() > pane.getY() + pane.getHeight()) {
                    return null;
                }
            }
        }
        double gx = w.getX() + w.getWidth() / 2.0;
        double gy = w.getY() + w.getHeight() / 2.0;
        int[] pill = ModScreenDriver.forwardPill(w);
        if (pill != null) {
            gy = pill[1] + pill[3] / 2.0;
            if (side == Side.RIGHT) {
                gx = pill[0] + pill[2] * 0.75;
            } else if (side == Side.LEFT) {
                gx = pill[0] + pill[2] * 0.25;
            } else {
                if (pill[0] - w.getX() < 12) {
                    return null; // the pill fills the row: there is no label to click
                }
                gx = w.getX() + Math.min(6, (pill[0] - w.getX()) / 2.0);
            }
        } else if (side == Side.LABEL) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        double factor = ((Number) Mod.staticCall("hud.AutoScale", "openScreenFactor")).doubleValue();
        double scale = mc.getWindow().getGuiScale() * factor;
        return new Spot(ModScreenDriver.label(w), gx, gy, gx * scale, gy * scale);
    }

    private static AbstractWidget find(ModScreenDriver d, Target t) {
        int offset = (Integer) R.getStatic(d.screenCls, "scrollOffset");
        for (AbstractWidget w : d.content()) {
            // Not by width: Auto Door Opener's Mode button is half width under one mode and full under the other.
            if (d.isSettingsButton(w) && w.getX() == t.x() && w.getY() + offset == t.absY() && isCycling(w)) {
                return w;
            }
        }
        return null;
    }

    private static List<Target> collect(ModScreenDriver d, String where, Set<String> done, Set<String> unmarked) {
        List<Target> out = new ArrayList<>();
        int offset = (Integer) R.getStatic(d.screenCls, "scrollOffset");
        for (AbstractWidget w : d.content()) {
            if (!d.isSettingsButton(w) || !w.visible) {
                continue;
            }
            String label = ModScreenDriver.label(w);
            var m = VALUE_LABEL.matcher(label);
            String name = m.matches() ? m.group(1) : label;
            if (!isCycling(w)) {
                if (m.matches() && !TabSweep.TOGGLE.matcher(label).matches()) {
                    unmarked.add(where + " | " + label);
                }
                continue;
            }
            String key = where + " | " + name + (m.matches() ? "" : " @" + w.getX() + "," + (w.getY() + offset));
            if (done.contains(key)) {
                continue;
            }
            boolean dup = false;
            for (Target t : out) {
                dup |= t.key().equals(key);
            }
            if (!dup) {
                out.add(new Target(key, where, label, w.getX(), w.getY() + offset, w.getWidth()));
            }
        }
        return out;
    }

    private static boolean isCycling(AbstractWidget w) {
        try {
            return (Boolean) w.getClass().getMethod("isCycling").invoke(w);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("gui.SettingsButtonWidget has no isCycling() - a mod jar from before cycle-back", e);
        }
    }

    private static Object open(ModScreenDriver d, int top, int sec, Deny deny) {
        try {
            Object topTab = d.topTabs().get(top);
            d.select(top);
            if (d.isFolder(topTab)) {
                d.expanded(topTab).add(sec);
                d.expandAll(d.subTabs(topTab).get(sec));
            }
            d.rebuild();
            d.openCollapsibles(deny, new ArrayList<>());
            return null;
        } catch (Throwable t) {
            throw new AssertionError(UiCase.describe(t), t);
        }
    }

    private static void rebuild(ModScreenDriver d) {
        try {
            d.rebuild();
        } catch (Throwable t) {
            throw new AssertionError(UiCase.describe(t), t);
        }
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
}

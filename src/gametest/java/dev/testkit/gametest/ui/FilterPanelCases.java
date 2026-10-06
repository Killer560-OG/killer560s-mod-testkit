package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 386 (part): the sim's shared room Filters panel ({@code roomsim.SimRoomFilterScreen}) - the one widget the Map
 * Designer, Load a Room and All Rooms all open (mod sim-filters, killer560 2026-10-07: "for the room filters it should
 * look kind of like this", a mockup of chip rows).
 *
 * <p>For each of the three filters, at four window sizes (GUI scale 2 to 4, Auto Scale on and off - the narrowest is
 * 213 GUI units wide), and at every scroll position the panel can take:
 * <ul>
 *   <li>no two visible controls intersect, and none leaves the panel or the screen;</li>
 *   <li>no chip sits on a row label that is drawn;</li>
 *   <li>every chip of every row is reachable - visible at some scroll position - and the chip count is the panel's
 *       rows (6 sizes, 7 kinds, 3, 4, 3, the puzzles, 4);</li>
 *   <li>at the narrowest size at least one row WRAPS (chips of one row on two lines), so "no overlap" was measured on
 *       a layout that actually had to wrap.</li>
 * </ul>
 * Then real input: the cursor is moved and the button pressed in WINDOW coordinates on the Load a Room filter's
 * "1x2" chip, and the filter must have it chosen afterwards (and not after a second press).
 */
final class FilterPanelCases {

    private static final String SCREEN = "roomsim.SimRoomFilterScreen";
    private static final String FILTERS = "roomsim.SimRoomFilters";

    private FilterPanelCases() {
    }

    static void run(UiCase c) {
        int[] oldWindow = c.onClient(mc -> new int[]{mc.getWindow().getScreenWidth(), mc.getWindow().getScreenHeight()});
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        Object hud = c.onClient(mc -> Mod.cfg("hud.HudConfig"));
        boolean oldAuto = c.onClient(mc -> (Boolean) Mod.call(hud, "isAutoScale"));
        // {width, height, GUI scale, Auto Scale}
        int[][] sizes = {{854, 480, 2, 1}, {854, 480, 4, 0}, {1280, 720, 3, 1}, {1920, 1080, 3, 0}};
        int checkedTotal = 0;
        int wrappedAtNarrowest = -1;
        try {
            for (int[] sz : sizes) {
                c.onClient(mc -> {
                    Mod.call(hud, "setAutoScale", sz[3] == 1);
                    return null;
                });
                c.ctx().getInput().resizeWindow(sz[0], sz[1]);
                c.ticks(3);
                c.onClient(mc -> {
                    mc.options.guiScale().set(sz[2]);
                    mc.resizeGui();
                    return null;
                });
                c.ticks(3);
                String tag = sz[0] + "x" + sz[1] + " gui" + sz[2] + (sz[3] == 1 ? "" : " no-autoscale");
                for (String which : new String[]{"DESIGNER", "PICKER", "CYCLE"}) {
                    int[] r = sweep(c, which, tag);
                    checkedTotal += r[0];
                    if (sz[2] == 4) {
                        wrappedAtNarrowest = Math.max(wrappedAtNarrowest, r[1]);
                    }
                }
                if (sz[0] == 854 && sz[2] == 2) {
                    realClick(c);
                }
            }
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.call(hud, "setAutoScale", oldAuto);
                return null;
            });
            c.ctx().getInput().resizeWindow(oldWindow[0], oldWindow[1]);
            c.ticks(3);
            c.onClient(mc -> {
                mc.options.guiScale().set(oldGui);
                mc.resizeGui();
                return null;
            });
            c.ticks(3);
        }
        c.note("filter panel: " + checkedTotal + " chip placements checked over " + sizes.length
                + " sizes x 3 filters; " + wrappedAtNarrowest + " row(s) wrapped at GUI scale 4");
        c.check(checkedTotal > 300, "the filter panel sweep checked only " + checkedTotal + " chip placements");
        c.check(wrappedAtNarrowest > 0, "no row wrapped at GUI scale 4 - the wrap was never exercised");
    }

    /** Opens the panel on one filter and checks every scroll position. {visible chips checked, rows wrapped}. */
    private static int[] sweep(UiCase c, String which, String tag) {
        Object filter = c.onClient(mc -> Mod.field(FILTERS, which));
        Screen s = c.onClient(mc -> {
            try {
                Screen scr = (Screen) Mod.cls(SCREEN).getConstructor(Screen.class, Mod.cls("roomsim.SimRoomFilter"),
                        String.class).newInstance(null, filter, "testkit 386");
                McCompat.setScreen(mc, scr);
                return scr;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not open the filter panel: " + e, e);
            }
        });
        c.ticks(3);
        int puzzles = c.onClient(mc -> ((List<?>) Mod.staticCall(FILTERS, "puzzleNames")).size());
        int want = 6 + 7 + 3 + 4 + 3 + puzzles + 4;
        Set<String> seen = new HashSet<>();
        int[] checked = {0};
        int[] wrapped = {0};
        List<String> problems = new ArrayList<>();
        int total = -1;
        for (int step = 0; step < 40; step++) {
            int[] r = c.onClient(mc -> layoutCheck(s, which + " " + tag, seen, problems, wrapped));
            total = r[0];
            checked[0] += r[1];
            boolean moved = c.onClient(mc -> {
                int before = (Integer) R.get(s, "scroll");
                s.mouseScrolled(s.width / 2.0, s.height / 2.0, 0, -1);
                return (Integer) R.get(s, "scroll") != before;
            });
            if (!moved) {
                break;
            }
        }
        c.note(which + " at " + tag + ": " + total + " chips (want " + want + "), " + seen.size()
                + " reached by scrolling, " + wrapped[0] + " wrapped row(s), " + problems.size() + " problem(s)");
        for (String p : problems) {
            c.problem(p);
        }
        c.check(total == want, which + " at " + tag + ": " + total + " chips, the rows hold " + want);
        c.check(seen.size() == total, which + " at " + tag + ": only " + seen.size() + " of " + total
                + " chips were ever visible - some cannot be reached");
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        return new int[]{checked[0], wrapped[0]};
    }

    /** One scroll position: {chips in the screen, visible widgets checked}. Runs on the client thread. */
    private static int[] layoutCheck(Screen s, String where, Set<String> seen, List<String> problems, int[] wrapped) {
        int px = (Integer) R.get(s, "panelX");
        int py = (Integer) R.get(s, "panelY");
        int pw = (Integer) R.get(s, "panelW");
        int ph = (Integer) R.get(s, "panelH");
        int scroll = (Integer) R.get(s, "scroll");
        List<AbstractWidget> vis = new ArrayList<>();
        int chips = 0;
        Map<String, Set<Integer>> rowLines = new HashMap<>();
        @SuppressWarnings("unchecked")
        Map<AbstractWidget, Integer> base = (Map<AbstractWidget, Integer>) R.get(s, "baseY");
        for (var ch : s.children()) {
            if (!(ch instanceof AbstractWidget w)) {
                continue;
            }
            String row = row(w);
            if (row != null && !row.isEmpty()) {
                chips++;
                rowLines.computeIfAbsent(row, k -> new HashSet<>()).add(base.get(w));
            }
            if (w.visible && w.getWidth() > 0 && w.getHeight() > 0) {
                vis.add(w);
                if (row != null && !row.isEmpty()) {
                    seen.add(row + "/" + label(w));
                }
            }
        }
        int wraps = 0;
        for (Set<Integer> lines : rowLines.values()) {
            wraps += lines.size() > 1 ? 1 : 0;
        }
        wrapped[0] = Math.max(wrapped[0], wraps);
        for (int a = 0; a < vis.size(); a++) {
            AbstractWidget p = vis.get(a);
            if (p.getX() < px || p.getY() < py || p.getX() + p.getWidth() > px + pw || p.getY() + p.getHeight() > py + ph) {
                add(problems, where + ": '" + label(p) + "' " + box(p) + " leaves the panel [" + px + "," + py + " "
                        + pw + "x" + ph + "]");
            }
            if (p.getX() < 0 || p.getY() < 0 || p.getX() + p.getWidth() > s.width || p.getY() + p.getHeight() > s.height) {
                add(problems, where + ": '" + label(p) + "' " + box(p) + " runs off the screen");
            }
            for (int b = a + 1; b < vis.size(); b++) {
                AbstractWidget q = vis.get(b);
                if (hit(p.getX(), p.getY(), p.getX() + p.getWidth(), p.getY() + p.getHeight(),
                        q.getX(), q.getY(), q.getX() + q.getWidth(), q.getY() + q.getHeight())) {
                    add(problems, where + ": '" + label(p) + "' " + box(p) + " overlaps '" + label(q) + "' " + box(q));
                }
            }
        }
        // Row labels, as the screen draws them: {text, x, base y}, shown when wholly inside the band.
        int top = (Integer) R.get(s, "contentTop");
        int bottom = (Integer) R.get(s, "contentBottom");
        var font = net.minecraft.client.Minecraft.getInstance().font;
        @SuppressWarnings("unchecked")
        List<Object[]> labels = (List<Object[]>) R.get(s, "labels");
        for (Object[] l : labels) {
            int lx = (Integer) l[1];
            int ly = (Integer) l[2] - scroll;
            if (ly < top || ly + 9 > bottom) {
                continue;
            }
            int lw = font.width((String) l[0]);
            for (AbstractWidget w : vis) {
                if (hit(lx, ly, lx + lw, ly + 9, w.getX(), w.getY(), w.getX() + w.getWidth(), w.getY() + w.getHeight())) {
                    add(problems, where + ": label '" + l[0] + "' [" + lx + "," + ly + " " + lw + "x9] is under '"
                            + label(w) + "' " + box(w));
                }
            }
        }
        return new int[]{chips, vis.size()};
    }

    /** Real cursor and button, in window coordinates, on Load a Room's "1x2" chip. */
    private static void realClick(UiCase c) {
        Object filter = c.onClient(mc -> Mod.field(FILTERS, "PICKER"));
        boolean before = c.onClient(mc -> (Boolean) Mod.call(filter, "hasSize", "1x2"));
        for (int press = 1; press <= 2; press++) {
            Screen s = c.onClient(mc -> {
                try {
                    Screen scr = (Screen) Mod.cls(SCREEN).getConstructor(Screen.class,
                            Mod.cls("roomsim.SimRoomFilter"), String.class).newInstance(null, filter, "testkit 386");
                    McCompat.setScreen(mc, scr);
                    return scr;
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            c.ticks(3);
            double[] at = c.onClient(mc -> {
                for (var ch : s.children()) {
                    if (ch instanceof AbstractWidget w && "Size".equals(row(w)) && "1x2".equals(label(w)) && w.visible) {
                        float f = ((Number) Mod.staticCall("hud.AutoScale", "appliedFactor", s)).floatValue();
                        Window win = mc.getWindow();
                        double sx = w.getX() + w.getWidth() / 2.0;
                        double sy = w.getY() + w.getHeight() / 2.0;
                        return new double[]{sx * f * win.getScreenWidth() / (double) win.getGuiScaledWidth(),
                                sy * f * win.getScreenHeight() / (double) win.getGuiScaledHeight()};
                    }
                }
                return null;
            });
            c.check(at != null, "no visible '1x2' chip in the Size row");
            if (at == null) {
                return;
            }
            c.ctx().getInput().setCursorPos(at[0], at[1]);
            c.ticks(2);
            c.ctx().getInput().pressMouse(0);
            c.ticks(3);
            boolean now = c.onClient(mc -> (Boolean) Mod.call(filter, "hasSize", "1x2"));
            Boolean drawnSelected = c.onClient(mc -> {
                Screen cur = McCompat.screen(mc);
                for (var ch : cur.children()) {
                    if (ch instanceof AbstractWidget w && "Size".equals(row(w)) && "1x2".equals(label(w))) {
                        return (Boolean) Mod.call(w, "selected");
                    }
                }
                return null;
            });
            boolean want = press == 1 ? !before : before;
            c.note(String.format(Locale.ROOT, "real click %d on Load a Room's 1x2 chip at window %.0f,%.0f: chosen %s"
                    + " (want %s), chip drawn selected %s", press, at[0], at[1], now, want, drawnSelected));
            c.check(now == want, "real click " + press + " on the 1x2 chip left it chosen=" + now + ", expected " + want);
            c.check(drawnSelected != null && drawnSelected == now, "the rebuilt 1x2 chip is drawn selected="
                    + drawnSelected + " while the filter says " + now);
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
        }
    }

    /** A chip's row label, "" for an action chip, null for anything that is not a chip. */
    private static String row(AbstractWidget w) {
        if (!w.getClass().getName().endsWith("SimRoomFilterScreen$Chip")) {
            return null;
        }
        return (String) Mod.call(w, "row");
    }

    private static String label(AbstractWidget w) {
        return w.getMessage().getString();
    }

    private static boolean hit(int ax0, int ay0, int ax1, int ay1, int bx0, int by0, int bx1, int by1) {
        return ax0 < bx1 && bx0 < ax1 && ay0 < by1 && by0 < ay1;
    }

    private static String box(AbstractWidget w) {
        return "[" + w.getX() + "," + w.getY() + " " + w.getWidth() + "x" + w.getHeight() + "]";
    }

    private static void add(List<String> problems, String p) {
        if (!problems.contains(p) && problems.size() < 40) {
            problems.add(p);
        }
    }
}

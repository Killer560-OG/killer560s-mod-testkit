package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Finds a Health and Mana Bars bar on a screenshot whatever its style (mod bars-2, 2026-10-12: Classic, the flat bar the
 * older bar cases measured by exact color, was removed; every bar is now Bold or Sleek: framed, a gradient fill, an icon).
 *
 * <p>Two steps, both from what is on screen plus the painter's own geometry:
 * <ol>
 *   <li>The FILL is every pixel of the bar color's TINT: the color with any amount of white or black mixed in (the
 *       gradient, the light top edge, the inner shadow and Bold's notches all mix with white or black, so they keep the
 *       tint exactly, to 8-bit rounding). Exact pixels of the enabled TEXT readouts' colors and their shadows are left
 *       out (Health Text's color shares Health Bar's tint in these cases).</li>
 *   <li>The BOX: the fill area is the element's box less the painter's insets (icon, frame, Beside number;
 *       {@code BarPainter.lastInset*}), at the element's scale. The fill box is mapped from the track onto the box
 *       linearly, so a full fill comes back as exactly the element's box and a part fill as the share of it the flat bar
 *       drew - the numbers the older cases were written for. An overflow color (absorption, overflow mana) is mapped by
 *       its bar's track.</li>
 * </ol>
 * On a jar without {@code BarPainter} (before bars-style) it is the old exact-color box.
 */
final class BarPixels {

    private static final String PS = "playerstats.PlayerStatsConfig";
    private static final String READOUT = "playerstats.StatElements$Readout";
    private static final String PAINTER = "playerstats.BarPainter";

    /** A bar color's geometry: element scale, insets (units), track length (units) and the color of its bar. */
    record Info(float scale, int left, int top, int right, int bottom, int track, int barColour, float[] window) {
    }

    /** What the mod is drawing now: color (RGB) -> geometry, and the text readouts' colors to leave out. */
    record Scene(Map<Integer, Info> bars, Set<Integer> exclude) {
    }

    private BarPixels() {
    }

    /** For cases about where bars draw, not how they look: no icon, no easing, no damage ghost (891-896 test those), so a
     *  value set is drawn at once. A jar without the settings is left as it is. Client thread. */
    static void plain(Object ps) {
        for (String setter : new String[]{"setBarIcons", "setBarSmooth", "setBarDamageGhost"}) {
            for (var m : ps.getClass().getMethods()) {
                if (m.getName().equals(setter)) {
                    Mod.call(ps, setter, false);
                    break;
                }
            }
        }
    }

    /** Waits (up to 60 ticks) until every bar draws the same fill, overflow and no ghost two ticks running: the value
     *  set has been eased to, whatever the settings. */
    static void settle(UiCase c) {
        if (!c.onClient(mc -> Mod.has(PAINTER))) {
            return;
        }
        String last = null;
        for (int i = 0; i < 30; i++) {
            String now = c.onClient(mc -> {
                Class<?> p = R.cls(PAINTER);
                int[] ghost = (int[]) R.getStatic(p, "lastGhost");
                for (int g : ghost) {
                    if (g != 0) {
                        return "ghost";
                    }
                }
                return java.util.Arrays.toString((int[]) R.getStatic(p, "lastFill"))
                        + java.util.Arrays.toString((int[]) R.getStatic(p, "lastOverflow"));
            });
            if (now.equals(last)) {
                return;
            }
            last = now;
            c.ticks(2);
        }
    }

    /** Reads the scene on the client thread. */
    static Scene scene(UiCase c) {
        return c.onClient(mc -> {
            Map<Integer, Info> bars = new HashMap<>();
            Set<Integer> exclude = new HashSet<>();
            if (!Mod.has(PAINTER) || !hasField(R.cls(PAINTER), "lastScreenBox")) {
                return new Scene(bars, exclude);
            }
            Object ps = Mod.cfg(PS);
            Class<?> p = R.cls(PAINTER);
            for (Object r : R.cls(READOUT).getEnumConstants()) {
                boolean on = (Boolean) Mod.call(ps, "isReadoutOn", r);
                int colour = ((Integer) Mod.call(ps, "getReadoutColor", r)) & 0xFFFFFF;
                if (!on) {
                    continue;
                }
                if (!(Boolean) R.get(r, "bar")) {
                    exclude.add(colour);
                    exclude.add((colour & 0xFCFCFC) >> 2);
                    continue;
                }
                int i = ((Enum<?>) r).ordinal();
                Object e = Mod.staticCall("hud.HudElementRegistry", "byId", (String) R.get(r, "hudId"));
                float s = e == null ? 1f : ((Number) Mod.staticCall("hud.HudElementRegistry", "resolveScale", e))
                        .floatValue();
                float[] sb = (float[]) R.getStatic(p, "lastScreenBox");
                Info info = new Info(s, ((int[]) R.getStatic(p, "lastInsetLeft"))[i],
                        ((int[]) R.getStatic(p, "lastInsetTop"))[i], ((int[]) R.getStatic(p, "lastInsetRight"))[i],
                        ((int[]) R.getStatic(p, "lastInsetBottom"))[i], ((int[]) R.getStatic(p, "lastTrack"))[i], colour,
                        new float[]{sb[i * 4], sb[i * 4 + 1], sb[i * 4 + 2], sb[i * 4 + 3]});
                bars.put(colour, info);
                String name = ((Enum<?>) r).name();
                if (name.equals("HEALTH_BAR")) {
                    bars.putIfAbsent(((Integer) Mod.call(ps, "getAbsorptionColor")) & 0xFFFFFF, info);
                } else if (name.equals("MANA_BAR")) {
                    bars.putIfAbsent(((Integer) Mod.call(ps, "getOverflowManaColor")) & 0xFFFFFF, info);
                }
            }
            return new Scene(bars, exclude);
        });
    }

    /**
     * The box of {@code color} in {@code img}, in pixels: {x0, y0, x1, y1 (inclusive), solid (1/0), n}; for a bar color
     * the element-box equivalent (see the class doc), else the exact-color box. Null when nothing has the color.
     */
    static double[] box(BufferedImage img, int colour, int gs, Scene scene) {
        int rgb = colour & 0xFFFFFF;
        Info info = scene.bars().get(rgb);
        if (info == null) {
            return raw(img, rgb, false, scene.exclude(), null);
        }
        // Only inside the fill area the painter drew this bar in: a tint search over the whole screen also finds terrain,
        // sky and hotbar items of a similar hue (yellow and the superflat ground's olive are 0.09 apart).
        int[] win = window(info, gs);
        double[] b = raw(img, rgb, true, scene.exclude(), win);
        if (b == null) {
            return null;
        }
        double[] track = info.barColour() == rgb ? b : raw(img, info.barColour(), true, scene.exclude(), win);
        if (track == null || info.track() <= 0) {
            return b;
        }
        double unit = info.scale() * gs;
        double tl = track[0];
        double tt = track[1];
        double th = track[3] + 1 - track[1];
        double ih = th / unit;
        double w = info.left() + info.track() + info.right();
        double h = info.top() + ih + info.bottom();
        double sx = w / info.track();
        double sy = ih > 0 ? h / ih : 1;
        double bl = tl - info.left() * unit;
        double bt = tt - info.top() * unit;
        double x0 = bl + (b[0] - tl) * sx;
        double x1 = bl + (b[2] + 1 - tl) * sx - 1;
        double y0 = bt + (b[1] - tt) * sy;
        double y1 = bt + (b[3] + 1 - tt) * sy - 1;
        // "Solid" leaves out one unit at each corner of the fill: rounded bars (mod bars-menu) paint the track's corner
        // unit in the frame color, over the fill.
        double solid = solidBut(img, rgb, b, (int) Math.ceil(unit - 1e-3), scene.exclude()) ? 1 : 0;
        return new double[]{Math.round(x0), Math.round(y0), Math.round(x1), Math.round(y1), solid, b[5]};
    }

    /** The fill area on screen in pixels: the painter's box less its insets (frame, icon, Beside number), so neither the
     *  world through a rounded corner nor the icon is searched. */
    private static int[] window(Info info, int gs) {
        double u = info.scale();
        return new int[]{(int) Math.floor((info.window()[0] + info.left() * u) * gs + 1e-3),
                (int) Math.floor((info.window()[1] + info.top() * u) * gs + 1e-3),
                (int) Math.ceil((info.window()[2] - info.right() * u) * gs - 1e-3),
                (int) Math.ceil((info.window()[3] - info.bottom() * u) * gs - 1e-3)};
    }

    /** The whole element box of the bar drawn in {@code color}, in pixels {x0, y0, x1, y1} (inclusive): its track, from
     *  the fill's left/top/bottom on screen and the painter's track length, plus the insets. Null when no fill shows or
     *  the color is not a bar's. */
    static double[] elementBox(BufferedImage img, int colour, int gs, Scene scene) {
        Info info = scene.bars().get(colour & 0xFFFFFF);
        if (info == null) {
            return null;
        }
        double[] t = raw(img, info.barColour(), true, scene.exclude(), window(info, gs));
        if (t == null) {
            return null;
        }
        double unit = info.scale() * gs;
        double x0 = t[0] - info.left() * unit;
        double x1 = t[0] + (info.track() + info.right()) * unit - 1;
        double y0 = t[1] - info.top() * unit;
        double y1 = t[3] + info.bottom() * unit;
        return new double[]{Math.round(x0), Math.round(y0), Math.round(x1), Math.round(y1)};
    }

    /** Whether every pixel of box {@code b} is {@code rgb}'s tint, except a {@code corner}-pixel square at each corner. */
    private static boolean solidBut(BufferedImage img, int rgb, double[] b, int corner, Set<Integer> exclude) {
        int x0 = (int) b[0], y0 = (int) b[1], x1 = (int) b[2], y1 = (int) b[3];
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                boolean cx = x < x0 + corner || x > x1 - corner;
                boolean cy = y < y0 + corner || y > y1 - corner;
                if (cx && cy) {
                    continue;
                }
                int p = img.getRGB(x, y) & 0xFFFFFF;
                if (exclude.contains(p) || !sameTint(p, rgb)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static double[] raw(BufferedImage img, int rgb, boolean tint, Set<Integer> exclude, int[] win) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1, n = 0;
        int wx0 = win == null ? 0 : Math.max(0, win[0]);
        int wy0 = win == null ? 0 : Math.max(0, win[1]);
        int wx1 = win == null ? img.getWidth() : Math.min(img.getWidth(), win[2]);
        int wy1 = win == null ? img.getHeight() : Math.min(img.getHeight(), win[3]);
        for (int y = wy0; y < wy1; y++) {
            for (int x = wx0; x < wx1; x++) {
                int p = img.getRGB(x, y) & 0xFFFFFF;
                boolean hit = tint ? !exclude.contains(p) && sameTint(p, rgb) : p == rgb;
                if (hit) {
                    n++;
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
        }
        if (n == 0) {
            return null;
        }
        boolean solid = n == (x1 - x0 + 1) * (y1 - y0 + 1);
        return new double[]{x0, y0, x1, y1, solid ? 1 : 0, n};
    }

    private static boolean hasField(Class<?> c, String name) {
        try {
            c.getField(name);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    /** Whether {@code p} is {@code c} with white or black mixed in: the same direction once the gray part (the smallest
     *  channel) is taken out, within 8-bit rounding, and with enough color left to tell. */
    static boolean sameTint(int p, int c) {
        int pr = (p >> 16) & 0xFF, pg = (p >> 8) & 0xFF, pb = p & 0xFF;
        int cr = (c >> 16) & 0xFF, cg = (c >> 8) & 0xFF, cb = c & 0xFF;
        int pm = Math.min(pr, Math.min(pg, pb));
        int px = Math.max(pr, Math.max(pg, pb));
        int cm = Math.min(cr, Math.min(cg, cb));
        int cx = Math.max(cr, Math.max(cg, cb));
        int pc = px - pm;
        int cc = cx - cm;
        if (pc < 20 || cc == 0) {
            return false;
        }
        double d = Math.abs((pr - pm) / (double) pc - (cr - cm) / (double) cc)
                + Math.abs((pg - pm) / (double) pc - (cg - cm) / (double) cc)
                + Math.abs((pb - pm) / (double) pc - (cb - cm) / (double) cc);
        return d <= 0.2;
    }
}

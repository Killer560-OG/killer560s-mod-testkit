package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * killer560's 2026-10-15 item: "Make the custom trail work with etherwarps or teleports. It can have the trail break or
 * draw a trail between the two points or toggles for which" (mod trail-hud-1015). A jump of more than 4 blocks in one
 * frame (the ribbon) or one tick (the squares) is a teleport, and the new On Teleport setting says what the trail does
 * across it: Break leaves a gap, Connect bridges it with a straight line, Arc with a curve. Default Connect.
 *
 * <p>Judged on the geometry the renderers hand to the GPU, not on pixels: the ribbon's last drawn vertices
 * ({@code RibbonTrail.lastVertices}, with its gap and quad counts) and the squares ring ({@code SquareTrail.position}).
 * The player walks 3 blocks, is moved 30 blocks along +x in one step (his old position moved with him, as a server
 * teleport does), then walks on a little. Each mode is judged on what lies between the two places:
 * <ul>
 *   <li>1042 ribbon: Break: one gap, the joined quads stop short of the jump, and the one long step is the gap itself;
 *       Connect: no gap, every step short, points all along the 30 blocks, flat; Arc: no gap, short steps, and the band
 *       rises several blocks above both ends between them. Exactly one teleport is counted in each run (walking is never
 *       mistaken for one).</li>
 *   <li>1043 squares: the same three over the squares ring.</li>
 *   <li>1044 settings: default Connect in both configs, round trip through the files, a null falls back to Connect, the
 *       profile reload reads it, and both tabs have an On Teleport cycling row with a tooltip.</li>
 * </ul>
 * All three fail on a jar from before the setting (no {@code trail.TeleportMode}).
 */
final class TrailTeleportCases {

    private static final String TP = "trail.TeleportMode";
    private static final String RIBBON_TAB = "gui.tab.RibbonTrailTab";
    private static final String SQUARES_TAB = "gui.tab.TrailTab";
    /** Blocks the player is moved in one step. */
    private static final double JUMP = 30.0;
    private static final int RED = 0xFFFF0000;
    private static final int YELLOW = 0xFFFFFF00;
    private static final int BLUE = 0xFF0000FF;

    private TrailTeleportCases() {
    }

    private static boolean ready(UiCase c) {
        if (!Mod.has(TP)) {
            c.problem("this jar has no On Teleport setting (mod before trail-hud-1015)");
            return false;
        }
        return true;
    }

    private static void mode(UiCase c, String cfgClass, String mode) {
        c.onClient(mc -> {
            Mod.call(Mod.cfg(cfgClass), "setTeleportMode", Mod.enumValue(TP, mode));
            return null;
        });
    }

    /** Moves the player to (x, z) in one step with his old position moved too, as a server teleport does. */
    private static void teleport(UiCase c, double x, double z) {
        c.onClient(mc -> {
            var p = mc.player;
            double y = TrailRibbonCases.origin[1];
            p.setPos(x, y, z);
            p.xo = x;
            p.yo = y;
            p.zo = z;
            p.xOld = x;
            p.yOld = y;
            p.zOld = z;
            p.setDeltaMovement(Vec3.ZERO);
            return null;
        });
    }

    /** Points of the ribbon's last frame (the bottom vertex of each), oldest first. */
    private static double[][] points(float[] v) {
        double[][] out = new double[v.length / 6][];
        for (int i = 0; i < out.length; i++) {
            out[i] = new double[]{v[6 * i], v[6 * i + 1], v[6 * i + 2]};
        }
        return out;
    }

    private static double dist(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** {largest step, second largest step, highest point above the lower end's height, lowest x, highest x}. */
    private static double[] shape(double[][] p) {
        double first = 0, second = 0, maxY = Double.NEGATIVE_INFINITY, minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double endY = Math.min(p[0][1], p[p.length - 1][1]);
        for (int i = 0; i < p.length; i++) {
            if (i > 0) {
                double d = dist(p[i - 1], p[i]);
                if (d > first) {
                    second = first;
                    first = d;
                } else if (d > second) {
                    second = d;
                }
            }
            maxY = Math.max(maxY, p[i][1]);
            minX = Math.min(minX, p[i][0]);
            maxX = Math.max(maxX, p[i][0]);
        }
        return new double[]{first, second, maxY - endY, minX, maxX};
    }

    // ---- 1042 ribbon ----------------------------------------------------------------------------------------------

    static void ribbon(UiCase c) {
        if (!ready(c)) {
            return;
        }
        try {
            for (String m : new String[]{"BREAK", "CONNECT", "ARC"}) {
                ribbonRun(c, m);
            }
        } finally {
            mode(c, TrailRibbonCases.CFG, "CONNECT");
            TrailRibbonCases.restore(c);
        }
    }

    private static void ribbonRun(UiCase c, String m) {
        TrailRibbonCases.scene(c);
        TrailRibbonCases.configure(c, RED, YELLOW, BLUE, 3, 4.0f);
        mode(c, TrailRibbonCases.CFG, m);
        c.onClient(mc -> {
            Mod.staticCall(TrailRibbonCases.RIBBON, "clear");
            return null;
        });
        double[] o = TrailRibbonCases.origin;
        TrailRibbonCases.place(c, o[0], o[2], 0f);
        c.ticks(3);
        TrailRibbonCases.walk(c, TrailRibbonCases.line(o[0], o[2], 1, 0, 20), 0f);
        double landX = o[0] + TrailRibbonCases.STEP * 20 + JUMP;
        long framesBefore = c.onClient(mc -> ((Number) Mod.staticCall(TrailRibbonCases.RIBBON, "framesDrawn")).longValue());
        teleport(c, landX, o[2]);
        c.ticks(2);
        TrailRibbonCases.walk(c, TrailRibbonCases.line(landX, o[2], 1, 0, 8), 0f);
        c.ticks(2);

        float[] v = c.onClient(mc -> (float[]) Mod.staticCall(TrailRibbonCases.RIBBON, "lastVertices"));
        int breaks = c.onClient(mc -> ((Number) Mod.staticCall(TrailRibbonCases.RIBBON, "lastBreaks")).intValue());
        int segments = c.onClient(mc -> ((Number) Mod.staticCall(TrailRibbonCases.RIBBON, "lastSegments")).intValue());
        int teleports = c.onClient(mc -> ((Number) Mod.staticCall(TrailRibbonCases.RIBBON, "teleportsSeen")).intValue());
        long framesAfter = c.onClient(mc -> ((Number) Mod.staticCall(TrailRibbonCases.RIBBON, "framesDrawn")).longValue());
        c.check(framesAfter > framesBefore + 3, m + ": the ribbon drew only " + (framesAfter - framesBefore)
                + " frame(s) across the teleport, so nothing here measured it");
        c.check(v != null && v.length >= 24, m + ": no ribbon vertices (" + (v == null ? "null" : v.length / 3) + ")");
        if (v == null || v.length < 24) {
            return;
        }
        double[][] p = points(v);
        double[] s = shape(p);
        c.note(String.format(Locale.ROOT, "%s: %d points, %d quads, %d gap(s), %d teleport(s) seen; longest step %.2f, "
                        + "next %.2f, rise %.2f, x %.1f..%.1f (origin %.1f, landing %.1f)", m, p.length, segments, breaks,
                teleports, s[0], s[1], s[2], s[3], s[4], o[0], landX));
        c.check(teleports == 1, m + ": " + teleports + " teleport(s) counted, wanted exactly 1 (walking must not count)");
        c.check(s[3] < o[0] + 4 && s[4] > landX - 1, m + ": the ribbon does not span both places (x " + s[3] + ".." + s[4] + ")");
        switch (m) {
            case "BREAK" -> {
                c.check(breaks == 1, "BREAK: " + breaks + " gap(s), wanted 1");
                c.check(segments == p.length - 2, "BREAK: " + segments + " quads for " + p.length
                        + " points, wanted one fewer than a joined band (" + (p.length - 2) + ")");
                c.check(s[0] > JUMP * 0.8, "BREAK: the long step is " + s[0] + ", the jump is gone from the geometry");
                c.check(s[1] < 0.8, "BREAK: a second long step (" + s[1] + ") - something besides the gap is stretched");
            }
            case "CONNECT" -> {
                c.check(breaks == 0, "CONNECT: " + breaks + " gap(s), wanted none");
                c.check(segments == p.length - 1, "CONNECT: " + segments + " quads for " + p.length + " points");
                c.check(s[0] < 0.8, "CONNECT: a step of " + s[0] + " - the bridge has holes");
                c.check(p.length > 100, "CONNECT: only " + p.length + " points over " + JUMP + " blocks");
                c.check(s[2] < 0.4, "CONNECT: the bridge bows " + s[2] + " blocks - it should be straight");
            }
            default -> {
                c.check(breaks == 0, "ARC: " + breaks + " gap(s), wanted none");
                c.check(segments == p.length - 1, "ARC: " + segments + " quads for " + p.length + " points");
                c.check(s[0] < 1.0, "ARC: a step of " + s[0] + " - the bridge has holes");
                c.check(s[2] > 3.6, "ARC: it rises only " + s[2] + " blocks between the two places (wanted 3.6 or more)");
            }
        }
    }

    // ---- 1043 squares ---------------------------------------------------------------------------------------------

    static void squares(UiCase c) {
        if (!ready(c)) {
            return;
        }
        try {
            for (String m : new String[]{"BREAK", "CONNECT", "ARC"}) {
                squaresRun(c, m);
            }
        } finally {
            mode(c, TrailRibbonCases.SQ, "CONNECT");
            TrailRibbonCases.restore(c);
        }
    }

    private static void squaresRun(UiCase c, String m) {
        TrailRibbonCases.scene(c);
        c.onClient(mc -> {
            Object sq = Mod.cfg(TrailRibbonCases.SQ);
            Mod.call(sq, "setEnabled", true);
            Mod.call(sq, "setLength", 100);
            Mod.call(sq, "setTeleportMode", Mod.enumValue(TP, m));
            Mod.call(Mod.field("trail.TrailFeature", "SQUARES"), "clear");
            return null;
        });
        double[] o = TrailRibbonCases.origin;
        TrailRibbonCases.place(c, o[0], o[2], 0f);
        c.ticks(3);
        TrailRibbonCases.walk(c, TrailRibbonCases.line(o[0], o[2], 1, 0, 10), 0f);
        double landX = o[0] + TrailRibbonCases.STEP * 10 + JUMP;
        int before = squareCount(c);
        teleport(c, landX, o[2]);
        c.ticks(2);
        TrailRibbonCases.walk(c, TrailRibbonCases.line(landX, o[2], 1, 0, 4), 0f);
        c.ticks(1);

        int size = squareCount(c);
        int teleports = c.onClient(mc -> ((Number) Mod.call(Mod.field("trail.TrailFeature", "SQUARES"), "teleports")).intValue());
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            final int k = i;
            pts.add(c.onClient(mc -> (double[]) Mod.call(Mod.field("trail.TrailFeature", "SQUARES"), "position", k)));
        }
        double[][] p = new double[pts.size()][];
        for (int i = 0; i < p.length; i++) {
            p[i] = pts.get(p.length - 1 - i);   // oldest first, like the ribbon
        }
        c.check(before >= 8, m + ": only " + before + " squares laid before the jump - the walk did not run");
        c.check(p.length >= 12, m + ": " + p.length + " squares held");
        if (p.length < 12) {
            return;
        }
        double[] s = shape(p);
        c.note(String.format(Locale.ROOT, "%s: %d squares (%d before the jump), %d teleport(s) seen; longest step %.2f, "
                + "next %.2f, rise %.2f, x %.1f..%.1f", m, p.length, before, teleports, s[0], s[1], s[2], s[3], s[4]));
        c.check(teleports == 1, m + ": " + teleports + " teleport(s) counted, wanted exactly 1");
        c.check(s[3] < o[0] + 3 && s[4] > landX - 1, m + ": the squares do not span both places");
        switch (m) {
            case "BREAK" -> {
                c.check(s[0] > JUMP * 0.8, "BREAK: the long step is " + s[0] + ", the gap is gone");
                c.check(s[1] < 0.8, "BREAK: a second long step (" + s[1] + ")");
                c.check(p.length < 30, "BREAK: " + p.length + " squares - something was laid across the jump");
            }
            case "CONNECT" -> {
                c.check(s[0] < 1.0, "CONNECT: a step of " + s[0] + " - the line of squares has a hole");
                c.check(p.length > 45, "CONNECT: only " + p.length + " squares over " + JUMP + " blocks");
                c.check(s[2] < 0.4, "CONNECT: the line bows " + s[2] + " blocks");
            }
            default -> {
                c.check(s[0] < 1.4, "ARC: a step of " + s[0] + " - the arc of squares has a hole");
                c.check(s[2] > 4.5, "ARC: it rises only " + s[2] + " blocks between the two places (wanted 4.5 or more)");
            }
        }
    }

    private static int squareCount(UiCase c) {
        return c.onClient(mc -> ((Number) Mod.call(Mod.field("trail.TrailFeature", "SQUARES"), "size")).intValue());
    }

    // ---- 1044 settings --------------------------------------------------------------------------------------------

    static void settings(UiCase c) throws Exception {
        if (!ready(c)) {
            return;
        }
        Path ribbonFile = c.onClient(mc -> mc.gameDirectory.toPath()
                .resolve("config/killer560/interface/ribbontrail/killer560smod-ribbontrail.json"));
        Path squaresFile = c.onClient(mc -> mc.gameDirectory.toPath()
                .resolve("config/killer560/interface/trail/killer560smod-trail.json"));
        String savedRibbon = Files.exists(ribbonFile) ? Files.readString(ribbonFile) : null;
        String savedSquares = Files.exists(squaresFile) ? Files.readString(squaresFile) : null;
        try {
            // No file: both default to Connect.
            Files.deleteIfExists(ribbonFile);
            Files.deleteIfExists(squaresFile);
            c.onClient(mc -> {
                Mod.staticCall(TrailRibbonCases.SQ, "load");
                Mod.staticCall(TrailRibbonCases.CFG, "load");
                return null;
            });
            String d1 = String.valueOf(Mod.call(Mod.cfg(TrailRibbonCases.CFG), "getTeleportMode"));
            String d2 = String.valueOf(Mod.call(Mod.cfg(TrailRibbonCases.SQ), "getTeleportMode"));
            c.note("defaults: ribbon " + d1 + ", squares " + d2);
            c.check(d1.equals("CONNECT") && d2.equals("CONNECT"), "default On Teleport is not Connect: " + d1 + " / " + d2);

            // Round trip through the files.
            c.onClient(mc -> {
                Mod.call(Mod.cfg(TrailRibbonCases.CFG), "setTeleportMode", Mod.enumValue(TP, "ARC"));
                Mod.call(Mod.cfg(TrailRibbonCases.CFG), "save");
                Mod.call(Mod.cfg(TrailRibbonCases.SQ), "setTeleportMode", Mod.enumValue(TP, "BREAK"));
                Mod.call(Mod.cfg(TrailRibbonCases.SQ), "save");
                Mod.staticCall(TrailRibbonCases.CFG, "load");
                Mod.staticCall(TrailRibbonCases.SQ, "load");
                return null;
            });
            String r1 = String.valueOf(Mod.call(Mod.cfg(TrailRibbonCases.CFG), "getTeleportMode"));
            String r2 = String.valueOf(Mod.call(Mod.cfg(TrailRibbonCases.SQ), "getTeleportMode"));
            c.note("after save + load: ribbon " + r1 + ", squares " + r2);
            c.check(r1.equals("ARC") && r2.equals("BREAK"), "On Teleport did not survive save + load: " + r1 + " / " + r2);

            // A profile reload reads it from the file.
            Boolean reloaded = c.onClient(mc -> {
                Mod.call(Mod.cfg(TrailRibbonCases.CFG), "setTeleportMode", Mod.enumValue(TP, "CONNECT"));   // memory only
                Mod.call(Mod.cfg(TrailRibbonCases.SQ), "setTeleportMode", Mod.enumValue(TP, "CONNECT"));
                Mod.staticCall("profiles.ProfileManager", "reloadAllConfigs");
                return "ARC".equals(String.valueOf(Mod.call(Mod.cfg(TrailRibbonCases.CFG), "getTeleportMode")))
                        && "BREAK".equals(String.valueOf(Mod.call(Mod.cfg(TrailRibbonCases.SQ), "getTeleportMode")));
            });
            c.check(reloaded, "a profile reload did not reload On Teleport");

            // A bad value in the file falls back to Connect.
            Files.writeString(ribbonFile, "{\"enabled\": false, \"teleportMode\": \"WARP\"}");
            c.onClient(mc -> Mod.staticCall(TrailRibbonCases.CFG, "load"));
            String bad = String.valueOf(Mod.call(Mod.cfg(TrailRibbonCases.CFG), "getTeleportMode"));
            c.check(bad.equals("CONNECT"), "a bad teleportMode in the file gave " + bad);
            c.onClient(mc -> {
                Mod.call(Mod.cfg(TrailRibbonCases.CFG), "setTeleportMode", (Object) null);
                return null;
            });
            c.check("CONNECT".equals(String.valueOf(Mod.call(Mod.cfg(TrailRibbonCases.CFG), "getTeleportMode"))),
                    "setTeleportMode(null) did not fall back to Connect");

            // Both tabs: an On Teleport cycling row stepping Break -> Connect -> Arc, with a tooltip.
            for (String[] t : new String[][]{{RIBBON_TAB, TrailRibbonCases.CFG, "Ribbon Trail"},
                    {SQUARES_TAB, TrailRibbonCases.SQ, "Trail"}}) {
                List<String> rows = TrailRibbonCases.tabRows(c, t[0], () -> {
                    Mod.call(Mod.cfg(t[1]), "setEnabled", true);
                    Mod.call(Mod.cfg(t[1]), "setTeleportMode", Mod.enumValue(TP, "CONNECT"));
                });
                c.note(t[2] + " rows: " + rows);
                c.check(rows.contains("On Teleport: Connect *"), t[2] + " tab has no cycling 'On Teleport: Connect' row: " + rows);
                String tip = c.onClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "describe", t[2], "On Teleport: Connect"));
                c.check(tip != null && !tip.isEmpty(), "no tooltip for " + t[2] + "'s On Teleport");
                String seen = c.onClient(mc -> stepRow(t[0], t[1]));
                c.note(t[2] + " left, left, right: " + seen);
                c.check(seen.equals("On Teleport: Arc|On Teleport: Break|On Teleport: Arc"),
                        t[2] + " On Teleport stepped wrongly: " + seen);
            }
        } finally {
            if (savedRibbon != null) {
                Files.writeString(ribbonFile, savedRibbon);
            } else {
                Files.deleteIfExists(ribbonFile);
            }
            if (savedSquares != null) {
                Files.writeString(squaresFile, savedSquares);
            } else {
                Files.deleteIfExists(squaresFile);
            }
            c.onClient(mc -> {
                Mod.staticCall(TrailRibbonCases.SQ, "load");
                Mod.staticCall(TrailRibbonCases.CFG, "load");
                return null;
            });
        }
    }

    /** Builds the tab, then presses its On Teleport row forward twice and back once; the labels after each. */
    @SuppressWarnings("unchecked")
    private static String stepRow(String tabClass, String cfgClass) {
        try {
            Mod.call(Mod.cfg(cfgClass), "setTeleportMode", Mod.enumValue(TP, "CONNECT"));
            Object tab = Mod.cls(tabClass).getDeclaredConstructor().newInstance();
            List<AbstractWidget> ws = (List<AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0, 300, (Runnable) () -> {
            });
            AbstractWidget row = null;
            for (AbstractWidget w : ws) {
                if (w.getMessage().getString().startsWith("On Teleport")) {
                    row = w;
                }
            }
            if (row == null) {
                return "no row";
            }
            Object forward = Mod.field(row, "onPress");
            Object back = Mod.field(row, "onSecondaryPress");
            List<String> out = new ArrayList<>();
            press(forward, row);
            out.add(row.getMessage().getString());
            press(forward, row);
            out.add(row.getMessage().getString());
            press(back, row);
            out.add(row.getMessage().getString());
            return String.join("|", out);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void press(Object handler, AbstractWidget row) {
        for (java.lang.reflect.Method m : handler.getClass().getDeclaredMethods()) {
            if (m.getName().equals("onPress") && m.getParameterCount() == 1) {
                try {
                    m.setAccessible(true);
                    m.invoke(handler, row);
                    return;
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            }
        }
        throw new AssertionError("no onPress on " + handler.getClass());
    }
}

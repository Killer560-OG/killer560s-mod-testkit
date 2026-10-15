package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.world.phys.Vec3;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * killer560's Ribbon Trail (mod trail-ribbon 2026-10-13, its own Cosmetics tab since ribbon-cosmetic 2026-10-14): one
 * smooth band streaming behind the player, head colour at the body to tail colour at the end, fading to nothing there,
 * shrinking away when he stands still. Since ribbon-cosmetic it is a WORLD-FIXED vertical band (killer560: "I want the
 * ribbon to be vertical behind them, not horizontal or any angle"): every point's two vertices share x and z. Judged from F5 screenshots: each frame is diffed against a frame of the SAME scene with
 * the trail off (a pixel counts as changed above a summed RGB delta of 30, the {@code CrosshairCases} rule), and the
 * changed pixels' largest 8-connected component is the band. Distances along the band are a breadth-first walk from
 * its pixel nearest the player, so "near the player" and "further back" follow the band round a curve too. Colours are
 * read from the SIGN of each pixel's change against the trail-off frame (red raises R and lowers G, yellow raises both,
 * blue raises B), which holds however much of the ground shows through.
 * <ul>
 *   <li>1031 line: a sideways walk seen from behind; one continuous band (largest component holds 90%+ of the changed
 *       pixels, no column gap), red-led near the player, yellow in the middle, leaning further to blue toward the end, the change falling toward
 *       the tail, flat (no curve); then a forward walk seen from behind.</li>
 *   <li>1032 curve: a quarter-circle walk; still one band, red at the player and blue at the end, and it bends (its
 *       height on screen is several times its thickness, where the line's is not).</li>
 *   <li>1033 still and switch: standing still the band shrinks (half way through Ribbon Length it is shorter) and is
 *       gone after it; turning the Ribbon Trail off removes it at once; it and the squares Trail draw together; Show In
 *       First Person OFF draws nothing in first person, ON draws.</li>
 *   <li>1034 settings: every ribbon field survives save + load in config/killer560/interface/ribbontrail/, an old Trail
 *       file with Style Ribbon becomes the Ribbon Trail (on, its settings) with the squares off, Style Squares changes
 *       nothing visible, clamps, the tab's rows, cycling buttons and tooltips.</li>
 *   <li>1040 tabs: the Cosmetics category has a Ribbon Trail tab; the Trail tab has no Style, ribbon or share rows.</li>
 *   <li>1041 camera: the band's world-space vertices are identical from six camera angles at one player position,
 *       every point's bottom and top share x and z, and on screen it keeps a vertical extent from every angle.</li>
 *   <li>1035 gallery: the default red / orange / yellow from the side, from behind and round a curve, and Chroma.</li>
 * </ul>
 */
final class TrailRibbonCases {

    private static final String CFG = "trail.RibbonTrailConfig";
    private static final String SQ = "trail.TrailConfig";
    private static final String MODE = "trail.RibbonTrailConfig$ColorMode";
    private static final String RIBBON = "trail.RibbonTrail";
    private static final float HEIGHT = 0.8f;

    private static final int RED = 0xFFFF0000;
    private static final int YELLOW = 0xFFFFFF00;
    private static final int BLUE = 0xFF0000FF;
    /** Blocks per tick of the scripted walk (3 blocks a second). */
    private static final double STEP = 0.15;
    private static final float PITCH = 35f;

    private TrailRibbonCases() {
    }

    // ---- scene ----------------------------------------------------------------------------------------------------

    private static double[] origin;
    private static float savedYaw;
    private static float savedPitch;

    private static boolean ready(UiCase c) {
        if (!Mod.has(CFG)) {
            c.problem("this jar has no Ribbon Trail cosmetic (mod before ribbon-cosmetic)");
            return false;
        }
        return true;
    }

    /** F5 behind, HUD hidden, pitch 35 (only ground behind the band), 3 blocks above the grass, trail OFF. */
    private static void scene(UiCase c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            McCompat.clearChatAndToasts(mc);
            McCompat.setHudHidden(mc, true);
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            if (origin == null) {
                savedYaw = mc.player.getYRot();
                savedPitch = mc.player.getXRot();
                origin = new double[]{Math.floor(mc.player.getX()) + 0.5, Math.floor(mc.player.getY()) + 3,
                        Math.floor(mc.player.getZ()) + 0.5};
            }
            Mod.call(Mod.cfg(CFG), "setEnabled", false);
            Mod.call(Mod.cfg(SQ), "setEnabled", false);
            return null;
        });
        place(c, origin[0], origin[2], 0f);
        c.ticks(10);
    }

    private static void configure(UiCase c, int head, int mid, int tail, int colors, float seconds) {
        c.onClient(mc -> {
            Object cfg = Mod.cfg(CFG);
            Mod.call(cfg, "setColorMode", Mod.enumValue(MODE, "GRADIENT"));
            Mod.call(cfg, "setColorCount", colors);
            Mod.call(cfg, "setHeadColor", head);
            Mod.call(cfg, "setMidColor", mid);
            Mod.call(cfg, "setTailColor", tail);
            Mod.call(cfg, "setRibbonHeight", HEIGHT);
            Mod.call(cfg, "setRibbonSeconds", seconds);
            Mod.call(cfg, "setOpacityPercent", 100);
            Mod.call(cfg, "setShowInFirstPerson", true);
            Mod.call(cfg, "setEnabled", true);
            return null;
        });
    }

    private static void enabled(UiCase c, boolean on) {
        c.onClient(mc -> {
            Mod.call(Mod.cfg(CFG), "setEnabled", on);
            return null;
        });
    }

    private static void restore(UiCase c) {
        c.onClient(mc -> {
            Object cfg = Mod.cfg(CFG);
            Mod.call(cfg, "setEnabled", false);
            Mod.call(cfg, "setColorMode", Mod.enumValue(MODE, "GRADIENT"));
            Mod.call(cfg, "save");
            Mod.call(Mod.cfg(SQ), "setEnabled", false);
            Mod.call(Mod.cfg(SQ), "save");
            McCompat.setHudHidden(mc, false);
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            if (origin != null) {
                mc.player.setPos(origin[0], origin[1] - 3, origin[2]);
                mc.player.setYRot(savedYaw);
                mc.player.setXRot(savedPitch);
                origin = null;
            }
            return null;
        });
    }

    /** Puts the (flying) player at x, origin y, z facing {@code yaw}, pitch {@link #PITCH}. */
    private static void place(UiCase c, double x, double z, float yaw) {
        c.onClient(mc -> {
            mc.player.getAbilities().flying = true;
            mc.player.setPos(x, origin[1], z);
            mc.player.setDeltaMovement(Vec3.ZERO);
            mc.player.setYRot(yaw);
            mc.player.setXRot(PITCH);
            return null;
        });
    }

    /** One tick per point, facing {@code yaw}. */
    private static void walk(UiCase c, List<double[]> path, float yaw) {
        for (double[] p : path) {
            place(c, p[0], p[1], yaw);
            c.ticks(1);
        }
    }

    private static List<double[]> line(double x0, double z0, double dx, double dz, int ticks) {
        List<double[]> out = new ArrayList<>();
        for (int i = 1; i <= ticks; i++) {
            out.add(new double[]{x0 + dx * STEP * i, z0 + dz * STEP * i});
        }
        return out;
    }

    private static long frames() {
        return ((Number) Mod.staticCall(RIBBON, "framesDrawn")).longValue();
    }

    private static int points() {
        return ((Number) Mod.staticCall(RIBBON, "lastPointCount")).intValue();
    }

    /** Squares the local squares Trail holds right now. */
    private static int squareCount() {
        return ((Number) Mod.call(Mod.field("trail.TrailFeature", "SQUARES"), "size")).intValue();
    }

    /**
     * Null when {@code v} (x y z per vertex, bottom then top for each point) is a vertical strip {@code height} tall:
     * every point's two vertices share x and z exactly and differ in y by the height; else what is wrong.
     */
    static String verticalProblem(float[] v, float height) {
        if (v == null || v.length < 12) {
            return "no vertices (" + (v == null ? "null" : v.length / 3 + " vertices") + ")";
        }
        for (int i = 0; i + 5 < v.length; i += 6) {
            float bx = v[i], by = v[i + 1], bz = v[i + 2], tx = v[i + 3], ty = v[i + 4], tz = v[i + 5];
            if (bx != tx || bz != tz) {
                return String.format(Locale.ROOT, "point %d leans: bottom (%.4f, %.4f) top (%.4f, %.4f)", i / 6, bx, bz,
                        tx, tz);
            }
            if (Math.abs((ty - by) - height) > 1e-3) {
                return String.format(Locale.ROOT, "point %d is %.4f tall, not %.2f", i / 6, ty - by, height);
            }
        }
        return null;
    }

    private static double length() {
        return ((Number) Mod.staticCall(RIBBON, "lastLength")).doubleValue();
    }

    // ---- frames ---------------------------------------------------------------------------------------------------

    private static BufferedImage shot(UiCase c, String label, int waitTicks) {
        c.ticks(waitTicks);
        String name = c.name() + "-" + label;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        Path kept = dev.testkit.harness.Report.screenshot(name, taken);
        c.note(label + " -> " + (kept != null ? kept : taken));
        try {
            return javax.imageio.ImageIO.read(taken.toFile());
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read " + taken, e);
        }
    }

    private static int delta(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF));
    }

    /** What a band measured. */
    record Band(int changed, int size, double share, int reach, int columnGaps, int height, int thickness,
                double[] nearD, double[] midD, double[] farD, double nearMag, double midMag, double farMag) {
        String describe() {
            return String.format(Locale.ROOT,
                    "changed %d, band %d px (%.0f%%), reach %d px, column gaps %d, height %d px, thickness %d px; "
                            + "near dRGB %s |%.0f|, middle %s |%.0f|, far %s |%.0f|",
                    changed, size, share * 100, reach, columnGaps, height, thickness, rgb(nearD), nearMag, rgb(midD),
                    midMag, rgb(farD), farMag);
        }
    }

    private static String rgb(double[] d) {
        return String.format(Locale.ROOT, "(%+.0f,%+.0f,%+.0f)", d[0], d[1], d[2]);
    }

    /**
     * The band in {@code on} against {@code off}: the largest 8-connected component of changed pixels outside the
     * player's own box (centre column +-{@code skip} px), walked breadth-first from its pixel nearest the player.
     */
    private static Band band(BufferedImage off, BufferedImage on, int skip) {
        int w = on.getWidth(), h = on.getHeight();
        int cx = w / 2;
        boolean[] m = new boolean[w * h];
        int changed = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (Math.abs(x - cx) < skip) {
                    continue;
                }
                if (delta(on.getRGB(x, y), off.getRGB(x, y)) > 30) {
                    m[y * w + x] = true;
                    changed++;
                }
            }
        }
        int[] comp = new int[w * h];
        int best = 0, bestSize = 0, label = 0;
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int i = 0; i < m.length; i++) {
            if (!m[i] || comp[i] != 0) {
                continue;
            }
            label++;
            int size = 0;
            comp[i] = label;
            q.add(i);
            while (!q.isEmpty()) {
                int p = q.poll();
                size++;
                int px = p % w, py = p / w;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = px + dx, ny = py + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
                            continue;
                        }
                        int n = ny * w + nx;
                        if (m[n] && comp[n] == 0) {
                            comp[n] = label;
                            q.add(n);
                        }
                    }
                }
            }
            if (size > bestSize) {
                bestSize = size;
                best = label;
            }
        }
        if (bestSize == 0) {
            return new Band(changed, 0, 0, 0, 0, 0, 0, new double[3], new double[3], new double[3], 0, 0, 0);
        }
        // Seed: the band's pixels in the column nearest the player.
        int seedCol = -1;
        for (int i = 0; i < m.length; i++) {
            if (comp[i] == best) {
                int x = i % w;
                if (seedCol < 0 || Math.abs(x - cx) < Math.abs(seedCol - cx)) {
                    seedCol = x;
                }
            }
        }
        int[] dist = new int[w * h];
        java.util.Arrays.fill(dist, -1);
        int minY = h, maxY = -1;
        for (int y = 0; y < h; y++) {
            int i = y * w + seedCol;
            if (comp[i] == best) {
                dist[i] = 0;
                q.add(i);
            }
        }
        int seedRows = q.size();
        int reach = 0;
        boolean[] colHas = new boolean[w];
        while (!q.isEmpty()) {
            int p = q.poll();
            int px = p % w, py = p / w;
            colHas[px] = true;
            minY = Math.min(minY, py);
            maxY = Math.max(maxY, py);
            reach = Math.max(reach, dist[p]);
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = px + dx, ny = py + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
                        continue;
                    }
                    int n = ny * w + nx;
                    if (comp[n] == best && dist[n] < 0) {
                        dist[n] = dist[p] + 1;
                        q.add(n);
                    }
                }
            }
        }
        int minX = w, maxX = -1;
        for (int x = 0; x < w; x++) {
            if (colHas[x]) {
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
            }
        }
        int gaps = 0;
        for (int x = minX; x <= maxX; x++) {
            if (!colHas[x]) {
                gaps++;
            }
        }
        double[][] sum = new double[3][3];
        double[] mag = new double[3];
        int[] n = new int[3];
        for (int i = 0; i < m.length; i++) {
            if (comp[i] != best || dist[i] < 0) {
                continue;
            }
            double f = dist[i] / (double) Math.max(1, reach);
            int seg = f < 0.2 ? 0 : (f >= 0.4 && f < 0.6) ? 1 : f >= 0.8 ? 2 : -1;
            if (seg < 0) {
                continue;
            }
            int a = on.getRGB(i % w, i / w), b = off.getRGB(i % w, i / w);
            sum[seg][0] += ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
            sum[seg][1] += ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
            sum[seg][2] += (a & 0xFF) - (b & 0xFF);
            mag[seg] += delta(a, b);
            n[seg]++;
        }
        double[][] mean = new double[3][3];
        for (int s = 0; s < 3; s++) {
            for (int k = 0; k < 3; k++) {
                mean[s][k] = n[s] == 0 ? 0 : sum[s][k] / n[s];
            }
            mag[s] = n[s] == 0 ? 0 : mag[s] / n[s];
        }
        return new Band(changed, bestSize, bestSize / (double) Math.max(1, changed), reach, gaps, maxY - minY + 1,
                seedRows, mean[0], mean[1], mean[2], mag[0], mag[1], mag[2]);
    }

    /** Changed pixels within {@code half} px of the centre column (the player's body in F5). */
    private static int overPlayer(BufferedImage off, BufferedImage on, int half) {
        int n = 0, cx = on.getWidth() / 2;
        for (int y = 0; y < on.getHeight(); y++) {
            for (int x = cx - half; x <= cx + half; x++) {
                if (delta(on.getRGB(x, y), off.getRGB(x, y)) > 30) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Red-led: R rises well above G (the head is red, so the band's near end runs red into orange). */
    private static boolean reddish(double[] d) {
        return d[0] > 60 && d[0] - d[1] > 60;
    }

    private static boolean yellowish(double[] d) {
        return d[0] > 15 && d[1] > 15 && d[2] < Math.min(d[0], d[1]);
    }

    /** How far a change leans to blue over red. The tail is blue, but faded over the green grass, so this is judged as
     *  a trend along the band rather than a colour. */
    private static double blueLean(double[] d) {
        return d[2] - d[0];
    }

    /** Head colour at the player, tail colour further back: red over green falls and blue over red rises along the band. */
    private static boolean headToTail(Band b) {
        return b.nearD()[0] - b.nearD()[1] > b.midD()[0] - b.midD()[1] + 40
                && blueLean(b.farD()) > blueLean(b.midD()) + 30 && blueLean(b.midD()) >= blueLean(b.nearD());
    }

    // ---- 1031 line ------------------------------------------------------------------------------------------------

    static void line(UiCase c) {
        if (!ready(c)) {
            return;
        }
        try {
            scene(c);
            configure(c, RED, YELLOW, BLUE, 3, 1.5f);
            long f0 = frames();
            // Facing south (yaw 0) and walking east: the band streams out to one side of the player.
            walk(c, line(origin[0], origin[2], 1, 0, 40), 0f);
            long drawn = frames() - f0;
            double len = length();
            int pts = points();
            String vertical = verticalProblem(c.onClient(mc -> (float[]) Mod.staticCall(RIBBON, "lastVertices")), HEIGHT);
            c.note("vertical band check: " + (vertical == null ? "every point's bottom and top share x and z, "
                    + HEIGHT + " apart" : vertical));
            c.check(vertical == null, "the band is not a vertical strip: " + vertical);
            BufferedImage on = shot(c, "side-on", 2);
            enabled(c, false);
            BufferedImage off = shot(c, "side-off", 4);
            Band b = band(off, on, 50);
            c.note(String.format(Locale.ROOT, "walk: %d ribbon frames drawn, %d points, %.2f blocks long", drawn, pts,
                    len));
            c.note("side band: " + b.describe());
            c.check(drawn > 20, "the ribbon drew only " + drawn + " frames during a 40-tick walk");
            c.check(len > 3.0, "the drawn ribbon was only " + len + " blocks long after walking 6 blocks");
            c.check(b.size() > 1500, "no band on screen: " + b.describe());
            c.check(b.share() >= 0.9, "the changed pixels are not one band: " + b.describe());
            c.check(b.columnGaps() == 0, "the band has " + b.columnGaps() + " empty column(s): " + b.describe());
            c.check(b.reach() > 120, "the band is short on screen: " + b.describe());
            c.check(reddish(b.nearD()), "near the player the band is not the head color (red): " + b.describe());
            c.check(yellowish(b.midD()), "the band's middle is not the middle color (yellow): " + b.describe());
            c.check(headToTail(b), "the band does not run red at the player to blue at the end: " + b.describe());
            c.check(b.nearMag() > b.farMag() * 1.5, "the band does not fade toward the tail: " + b.describe());
            c.check(b.height() < b.thickness() * 1.6, "a straight walk drew a bent band: " + b.describe());
            // The band starts behind him (against his movement), not inside his body: nothing drawn over him.
            // His model is one block wide arm to arm (+-30 px here), so the band's head 0.3 blocks behind his centre
            // sits about 18 px out, beside his arm: +-20 px reached into it (67 px on 26.2, where the arm does not
            // cover it). +-12 px is his torso and legs.
            int overBody = overPlayer(off, on, 12);
            c.note("changed pixels over the player's torso and legs (center +-12 px): " + overBody);
            c.check(overBody < 50, "the ribbon is drawn over the player (" + overBody + " px in front of his body)");
            // From behind: walking forward (south), the band streams back toward the camera and down the screen. A
            // vertical band seen EXACTLY from behind a dead-straight walk is edge-on (the F5 camera sits in its plane),
            // so the camera looks 30 degrees off the line of the walk, as it does whenever he turns or strafes.
            place(c, origin[0], origin[2], 30f);
            c.ticks(40); // the side walk's band shrinks away (1.5 s)
            enabled(c, true);
            walk(c, line(origin[0], origin[2], 0, 1, 30), 30f);
            BufferedImage behindOn = shot(c, "behind-on", 2);
            enabled(c, false);
            BufferedImage behindOff = shot(c, "behind-off", 4);
            Band bb = band(behindOff, behindOn, 0);
            c.note("behind band: " + bb.describe());
            c.check(bb.size() > 1500 && bb.share() >= 0.9, "no single band from behind: " + bb.describe());
            c.check(reddish(bb.nearD()), "from behind the band nearest the player is not red: " + bb.describe());
        } finally {
            restore(c);
        }
    }

    // ---- 1032 curve -----------------------------------------------------------------------------------------------

    static void curve(UiCase c) {
        if (!ready(c)) {
            return;
        }
        try {
            scene(c);
            configure(c, RED, YELLOW, BLUE, 3, 2.5f);
            // A quarter circle of radius 3.5 that ENDS here: the band leaves the player sideways (east) and bends
            // away from the camera (south).
            double r = 3.5;
            double ex = origin[0], ez = origin[2];
            List<double[]> path = new ArrayList<>();
            int steps = 37;
            for (int i = 0; i <= steps; i++) {
                double phi = (Math.PI / 2) * (1 - i / (double) steps);
                path.add(new double[]{ex + r * Math.sin(phi), ez + r * (1 - Math.cos(phi))});
            }
            place(c, path.get(0)[0], path.get(0)[1], 0f);
            c.ticks(60); // anything from the placement shrinks away (2.5 s ribbon)
            long f0 = frames();
            walk(c, path, 0f);
            long drawn = frames() - f0;
            BufferedImage on = shot(c, "on", 2);
            enabled(c, false);
            BufferedImage off = shot(c, "off", 4);
            Band b = band(off, on, 50);
            c.note(String.format(Locale.ROOT, "curve: %d frames, %d points, %.2f blocks", drawn, points(), length()));
            c.note("curve band: " + b.describe());
            c.check(drawn > 15, "the ribbon drew only " + drawn + " frames during the curve");
            c.check(b.size() > 1500 && b.share() >= 0.9, "the curve is not one band: " + b.describe());
            c.check(b.reach() > 120, "the curve's band is short: " + b.describe());
            c.check(reddish(b.nearD()), "near the player the curve is not red: " + b.describe());
            c.check(headToTail(b), "the curve does not run red at the player to blue at the end: " + b.describe());
            c.check(b.nearMag() > b.farMag() * 1.5, "the curve does not fade toward the tail: " + b.describe());
            c.check(b.height() > b.thickness() * 1.8, "the band did not bend with the walk: " + b.describe());
        } finally {
            restore(c);
        }
    }

    // ---- 1033 still, switch back, first person --------------------------------------------------------------------

    static void stillAndSwitch(UiCase c) {
        if (!ready(c)) {
            return;
        }
        try {
            scene(c);
            configure(c, RED, YELLOW, BLUE, 3, 2.0f);
            walk(c, line(origin[0], origin[2], 1, 0, 40), 0f);
            BufferedImage t0 = shot(c, "still-0s", 1);
            double len0 = length();
            BufferedImage t1 = shot(c, "still-1s", 19);
            double len1 = length();
            BufferedImage t3 = shot(c, "still-3s", 40);
            int pts3 = points();
            long fr3 = frames();
            enabled(c, false);
            BufferedImage off = shot(c, "still-off", 4);
            Band b0 = band(off, t0, 50), b1 = band(off, t1, 50), b3 = band(off, t3, 50);
            c.note(String.format(Locale.ROOT, "standing still: length %.2f -> %.2f blocks -> %d points", len0, len1,
                    pts3));
            c.note("0 s: " + b0.describe());
            c.note("1 s: " + b1.describe());
            c.note("3 s: " + b3.describe());
            c.check(b0.size() > 1500, "no band right after stopping: " + b0.describe());
            c.check(len1 < len0 * 0.75 && b1.reach() < b0.reach() * 0.8,
                    "the band did not shrink while standing still: " + len0 + " -> " + len1 + " blocks");
            c.check(b1.size() > 100, "the band was gone after 1 s of a 2 s ribbon: " + b1.describe());
            c.check(pts3 == 0 && b3.changed() < 30, "the band is still there 3 s after stopping (2 s ribbon): "
                    + pts3 + " points, " + b3.describe());
            c.check(frames() == fr3, "the ribbon drew while the trail was off");

            // With the squares Trail on as well, both draw; turning the Ribbon Trail off removes the band at once and
            // leaves the squares.
            place(c, origin[0], origin[2], 0f);
            c.onClient(mc -> {
                Mod.call(Mod.cfg(SQ), "setEnabled", true);
                return null;
            });
            enabled(c, true);
            long frB = frames();
            walk(c, line(origin[0], origin[2], 1, 0, 40), 0f);
            long bothRibbon = frames() - frB;
            int bothSquares = squareCount();
            BufferedImage ribbon = shot(c, "both-on", 1);
            enabled(c, false);
            long frS = frames();
            BufferedImage squares = shot(c, "ribbon-off-squares-on", 3);
            long drawnAfter = frames() - frS;
            int ptsS = points();
            int squaresAfter = squareCount();
            c.onClient(mc -> {
                Mod.call(Mod.cfg(SQ), "setEnabled", false);
                return null;
            });
            BufferedImage off2 = shot(c, "both-off", 4);
            Band br = band(off2, ribbon, 50), bs = band(off2, squares, 50);
            c.note("both on: ribbon frames " + bothRibbon + ", squares " + bothSquares + "; band " + br.describe());
            c.note("Ribbon Trail off, squares on: " + bs.describe() + "; ribbon frames since " + drawnAfter
                    + ", squares " + squaresAfter);
            c.check(bothRibbon > 20 && bothSquares >= 25, "with both on they did not both draw: ribbon " + bothRibbon
                    + " frames, " + bothSquares + " squares");
            c.check(br.size() > 1500, "no band with both on: " + br.describe());
            c.check(drawnAfter == 0 && ptsS == 0, "the ribbon is still drawn after turning it off: "
                    + drawnAfter + " frames, " + ptsS + " points");
            c.check(squaresAfter >= 25 && bs.changed() > 30 && bs.changed() < br.changed(),
                    "turning the Ribbon Trail off took the squares with it (or left the band): " + squaresAfter
                            + " squares, " + bs.describe());

            // Show In First Person.
            c.onClient(mc -> {
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                Object cfg = Mod.cfg(CFG);
                Mod.call(cfg, "setShowInFirstPerson", false);
                Mod.call(cfg, "setEnabled", true);
                return null;
            });
            long fp0 = frames();
            walk(c, line(origin[0] + 6, origin[2], -1, 0, 20), 0f);
            long fpOff = frames() - fp0;
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setShowInFirstPerson", true);
                return null;
            });
            long fp1 = frames();
            walk(c, line(origin[0], origin[2], 1, 0, 20), 0f);
            long fpOn = frames() - fp1;
            c.note("first person: ribbon frames with Show In First Person OFF " + fpOff + ", ON " + fpOn);
            c.check(fpOff == 0, "Show In First Person OFF still drew " + fpOff + " frames in first person");
            c.check(fpOn > 10, "Show In First Person ON drew only " + fpOn + " frames in first person");
        } finally {
            restore(c);
        }
    }

    // ---- 1034 settings --------------------------------------------------------------------------------------------

    static void settings(UiCase c) throws Exception {
        if (!ready(c)) {
            return;
        }
        Path file = c.onClient(mc -> mc.gameDirectory.toPath()
                .resolve("config/killer560/interface/ribbontrail/killer560smod-ribbontrail.json"));
        Path trailFile = c.onClient(mc -> mc.gameDirectory.toPath()
                .resolve("config/killer560/interface/trail/killer560smod-trail.json"));
        String savedTrail = Files.exists(trailFile) ? Files.readString(trailFile) : null;
        String savedRibbon = Files.exists(file) ? Files.readString(file) : null;
        try {
            Object cfg = Mod.cfg(CFG);
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setRibbonHeight", 1.25f);
                Mod.call(cfg, "setRibbonSeconds", 2.5f);
                Mod.call(cfg, "setOpacityPercent", 45);
                Mod.call(cfg, "setColorMode", Mod.enumValue(MODE, "CHROMA"));
                Mod.call(cfg, "setColorCount", 2);
                Mod.call(cfg, "setHeadColor", 0xFF123456);
                Mod.call(cfg, "setMidColor", 0xFF654321);
                Mod.call(cfg, "setTailColor", 0xFFABCDEF);
                Mod.call(cfg, "setShowInFirstPerson", false);
                Mod.call(cfg, "save");
                Mod.staticCall(CFG, "load");
                return null;
            });
            Object back = Mod.cfg(CFG);
            String got = ribbonState(back);
            c.note("after save + load: " + got + "; file " + file + " exists " + Files.exists(file));
            c.check(back != cfg, "load() did not make a new instance, so the round trip proved nothing");
            c.check(got.equals("true 1.25 2.5 45 CHROMA 2 ff123456 ff654321 ffabcdef false"), "round trip gave " + got);
            c.check(Files.exists(file), "no settings file at " + file);

            // An old Trail file with Style Ribbon: the Ribbon Trail takes its ribbon settings and its on/off, the squares
            // keep theirs but go off (they were not showing), and the style key is gone from the Trail file afterwards.
            Files.deleteIfExists(file);
            Files.createDirectories(trailFile.getParent());
            Files.writeString(trailFile, "{\"enabled\": true, \"length\": 55, \"squareSize\": 0.4, \"opacityPercent\": 40, "
                    + "\"fadeOut\": false, \"style\": \"RIBBON\", \"showInFirstPerson\": false, \"ribbonWidth\": 1.1, "
                    + "\"ribbonSeconds\": 3.0, \"colorMode\": \"NAME_FADE\", \"colorCount\": 2, \"headColor\": -16711936, "
                    + "\"midColor\": -256, \"tailColor\": -16776961}");
            c.onClient(mc -> {
                Mod.staticCall(SQ, "load");
                Mod.staticCall(CFG, "load");
                return null;
            });
            String mig = ribbonState(Mod.cfg(CFG));
            String sq = squaresState(Mod.cfg(SQ));
            String after = Files.readString(trailFile);
            c.note("Style Ribbon migrated: ribbon " + mig + "; squares " + sq + "; trail file now " + after.replace('\n', ' '));
            c.check(mig.equals("true 1.1 3.0 40 NAME_FADE 2 ff00ff00 ffffff00 ff0000ff false"),
                    "the Ribbon Trail did not take the old ribbon settings: " + mig);
            c.check(sq.equals("false 55 0.4 40 false false"), "the squares Trail did not keep its settings (off): " + sq);
            c.check(!after.contains("style") && !after.contains("ribbon"), "the Trail file still has ribbon keys: " + after);
            c.check(Files.exists(file), "the migration wrote no Ribbon Trail file");
            // Loading again changes nothing (it ran once).
            c.onClient(mc -> {
                Mod.staticCall(SQ, "load");
                Mod.staticCall(CFG, "load");
                return null;
            });
            String again = ribbonState(Mod.cfg(CFG)) + " / " + squaresState(Mod.cfg(SQ));
            c.check(again.equals(mig + " / " + sq), "a second load changed the migrated settings: " + again);

            // Style Squares: the squares stay exactly as they were and the existing Ribbon Trail is untouched.
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setEnabled", false);
                Mod.call(Mod.cfg(CFG), "save");
                return null;
            });
            Files.writeString(trailFile, "{\"enabled\": true, \"length\": 30, \"squareSize\": 0.3, \"opacityPercent\": 60, "
                    + "\"fadeOut\": true, \"style\": \"SQUARES\", \"showInFirstPerson\": true, \"ribbonWidth\": 0.6, "
                    + "\"ribbonSeconds\": 1.0, \"colorMode\": \"GRADIENT\", \"colorCount\": 3}");
            c.onClient(mc -> {
                Mod.staticCall(SQ, "load");
                Mod.staticCall(CFG, "load");
                return null;
            });
            String sq2 = squaresState(Mod.cfg(SQ));
            String rb2 = ribbonState(Mod.cfg(CFG));
            c.note("Style Squares: squares " + sq2 + "; ribbon " + rb2);
            c.check(sq2.equals("true 30 0.3 60 true true"), "Style Squares changed the squares Trail: " + sq2);
            c.check(rb2.equals(mig.replaceFirst("^true", "false")), "Style Squares changed the Ribbon Trail: " + rb2);

            // A file from before either (no style key) loads as the squares Trail it always was.
            Files.writeString(trailFile, "{\"enabled\": true, \"length\": 40, \"squareSize\": 0.3, \"opacityPercent\": 60, "
                    + "\"fadeOut\": true}");
            c.onClient(mc -> Mod.staticCall(SQ, "load"));
            String sq3 = squaresState(Mod.cfg(SQ));
            c.note("oldest file: squares " + sq3);
            c.check(sq3.equals("true 40 0.3 60 true true"), "an old settings file did not load as the squares Trail: " + sq3);

            // A profile reload reads the Ribbon Trail's file too (ProfileManager.reloadAllConfigs).
            Boolean reloaded = c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setOpacityPercent", 77); // memory only
                Mod.staticCall("profiles.ProfileManager", "reloadAllConfigs");
                return ((Number) Mod.call(Mod.cfg(CFG), "getOpacityPercent")).intValue() == 40;
            });
            c.check(reloaded, "a profile reload did not reload the Ribbon Trail's settings");

            // Clamps.
            Object rc = Mod.cfg(CFG);
            Mod.call(rc, "setRibbonHeight", 50f);
            Mod.call(rc, "setRibbonSeconds", 0f);
            Mod.call(rc, "setColorCount", 9);
            String clamped = Mod.call(rc, "getRibbonHeight") + " " + Mod.call(rc, "getRibbonSeconds") + " "
                    + Mod.call(rc, "getColorCount");
            c.note("clamped: " + clamped);
            c.check(clamped.equals("2.0 0.2 3"), "clamps gave " + clamped);

            // The Ribbon Trail tab's rows, in Gradient / 3 colours and in Chroma.
            List<String> ribbonRows = tabRows(c, "gui.tab.RibbonTrailTab", () -> {
                Mod.call(rc, "setEnabled", true);
                Mod.call(rc, "setColorMode", Mod.enumValue(MODE, "GRADIENT"));
                Mod.call(rc, "setColorCount", 3);
            });
            List<String> chromaRows = tabRows(c, "gui.tab.RibbonTrailTab", () ->
                    Mod.call(rc, "setColorMode", Mod.enumValue(MODE, "CHROMA")));
            c.note("ribbon rows: " + ribbonRows);
            c.note("chroma rows: " + chromaRows);
            for (String want : new String[]{"Ribbon Trail: ON", "Ribbon Height: 2.00", "Ribbon Length: 0.2s", "Opacity: 40%",
                    "Color Mode: Gradient *", "Colors: 3 *", "Head Color", "Middle Color", "Tail Color",
                    "Show In First Person", "Share Trail", "Show Others' Trails"}) {
                c.check(ribbonRows.stream().anyMatch(r -> r.startsWith(want)), "Ribbon Trail tab has no row '" + want + "': " + ribbonRows);
            }
            c.check(ribbonRows.stream().noneMatch(r -> r.startsWith("Style")), "the Ribbon Trail tab has a Style row");
            c.check(chromaRows.stream().noneMatch(r -> r.startsWith("Head Color")), "Chroma still shows color pickers");
            for (String label : new String[]{"Ribbon Trail: ON", "Ribbon Height: 2.00", "Ribbon Length: 0.2s", "Opacity: 40%",
                    "Color Mode: Gradient", "Colors: 3", "Head Color", "Middle Color", "Tail Color",
                    "Show In First Person: ON", "Share Trail: ON", "Show Others' Trails: ON"}) {
                String tip = c.onClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "describe", "Ribbon Trail", label));
                c.note("tooltip '" + label + "': " + tip);
                c.check(tip != null && !tip.isEmpty(), "no tooltip for '" + label + "'");
            }
        } finally {
            if (savedTrail != null) {
                Files.writeString(trailFile, savedTrail);
            } else {
                Files.deleteIfExists(trailFile);
            }
            if (savedRibbon != null) {
                Files.writeString(file, savedRibbon);
            } else {
                Files.deleteIfExists(file);
            }
            c.onClient(mc -> {
                Mod.staticCall(SQ, "load");
                Mod.staticCall(CFG, "load");
                Mod.call(Mod.cfg(CFG), "setEnabled", false);
                Mod.call(Mod.cfg(SQ), "setEnabled", false);
                return null;
            });
        }
    }

    /** "enabled height seconds opacity mode count head mid tail firstPerson" (colours as hex). */
    private static String ribbonState(Object r) {
        return Mod.call(r, "isEnabledRaw") + " " + Mod.call(r, "getRibbonHeight") + " " + Mod.call(r, "getRibbonSeconds")
                + " " + Mod.call(r, "getOpacityPercent") + " " + Mod.call(r, "getColorMode") + " "
                + Mod.call(r, "getColorCount") + " " + Integer.toHexString((Integer) Mod.call(r, "getHeadColor")) + " "
                + Integer.toHexString((Integer) Mod.call(r, "getMidColor")) + " "
                + Integer.toHexString((Integer) Mod.call(r, "getTailColor")) + " " + Mod.call(r, "isShowInFirstPerson");
    }

    /** "enabled length squareSize opacity fadeOut firstPerson". */
    private static String squaresState(Object t) {
        return Mod.call(t, "isEnabledRaw") + " " + Mod.call(t, "getLength") + " " + Mod.call(t, "getSquareSize") + " "
                + Mod.call(t, "getOpacityPercent") + " " + Mod.call(t, "isFadeOut") + " " + Mod.call(t, "isShowInFirstPerson");
    }

    /** A fresh {@code tabClass}'s rows (message, " *" when it is a cycling button) after {@code setup} runs. */
    private static List<String> tabRows(UiCase c, String tabClass, Runnable setup) {
        return c.onClient(mc -> {
            setup.run();
            Object tab;
            try {
                tab = Mod.cls(tabClass).getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not make a " + tabClass + ": " + e, e);
            }
            @SuppressWarnings("unchecked")
            List<AbstractWidget> ws = (List<AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0, 300, (Runnable) () -> {
            });
            List<String> rows = new ArrayList<>();
            Class<?> sbw = Mod.cls("gui.SettingsButtonWidget");
            for (AbstractWidget w : ws) {
                String msg = net.minecraft.ChatFormatting.stripFormatting(w.getMessage().getString());
                boolean cycling = sbw.isInstance(w) && Boolean.TRUE.equals(Mod.call(w, "isCycling"));
                rows.add(msg + (cycling ? " *" : ""));
            }
            return rows;
        });
    }

    // ---- 1040 tabs ------------------------------------------------------------------------------------------------

    static void tabs(UiCase c) {
        if (!ready(c)) {
            return;
        }
        List<String> names = c.onClient(mc -> {
            Object folder;
            try {
                folder = Mod.cls("gui.tab.CosmeticsCategoryTab").getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not make the Cosmetics category: " + e, e);
            }
            List<String> out = new ArrayList<>();
            for (Object t : (List<?>) Mod.call(folder, "subTabs")) {
                out.add(String.valueOf(Mod.field(t, "name")));
            }
            return out;
        });
        c.note("Cosmetics tabs: " + names);
        c.check(names.contains("Ribbon Trail"), "the Cosmetics category has no Ribbon Trail tab: " + names);
        c.check(names.contains("Trail"), "the Cosmetics category lost the Trail tab: " + names);
        boolean wasOn = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(SQ), "isEnabledRaw"));
        try {
            List<String> rows = tabRows(c, "gui.tab.TrailTab", () -> Mod.call(Mod.cfg(SQ), "setEnabled", true));
            c.note("Trail tab rows: " + rows);
            for (String want : new String[]{"Trail: ON", "Length", "Square Size", "Opacity", "Fade Toward Tail",
                    "Show In First Person"}) {
                c.check(rows.stream().anyMatch(r -> r.startsWith(want)), "the Trail tab lost its row '" + want + "': " + rows);
            }
            c.check(rows.stream().noneMatch(r -> r.startsWith("Style") || r.contains("Ribbon") || r.contains("Share")
                            || r.contains("Others' Trails") || r.contains("Color")),
                    "the Trail tab still has Style, ribbon, color or share rows: " + rows);
            for (String label : new String[]{"Trail: ON", "Length: 40", "Square Size: 0.30", "Opacity: 60%",
                    "Fade Toward Tail: ON", "Show In First Person: ON"}) {
                String tip = c.onClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "describe", "Trail", label));
                c.note("tooltip '" + label + "': " + tip);
                c.check(tip != null && !tip.isEmpty() && !tip.toLowerCase(Locale.ROOT).contains("ribbon"),
                        "the Trail tooltip for '" + label + "' is missing or still talks about the ribbon: " + tip);
            }
        } finally {
            c.onClient(mc -> {
                Mod.call(Mod.cfg(SQ), "setEnabled", wasOn);
                return null;
            });
        }
    }

    // ---- 1041 camera angles ---------------------------------------------------------------------------------------

    /** Camera angles for 1041: label, yaw, pitch, camera type, whether the band must keep a vertical extent. The band
     *  runs west of the player (he walked east), so yaw 90 would look straight down it, edge-on. */
    private static final Object[][] ANGLES = {
            {"side", 0f, 35f, CameraType.THIRD_PERSON_BACK, true},
            {"side-level", 0f, 5f, CameraType.THIRD_PERSON_BACK, true},
            {"behind-oblique", 55f, 25f, CameraType.THIRD_PERSON_BACK, true},
            {"swung-left", -40f, 35f, CameraType.THIRD_PERSON_BACK, true},
            {"swung-right", 40f, 35f, CameraType.THIRD_PERSON_BACK, true},
            {"above", 0f, 75f, CameraType.THIRD_PERSON_BACK, false},
            {"front", 180f, 30f, CameraType.THIRD_PERSON_FRONT, true},
    };

    static void cameraAngles(UiCase c) {
        if (!ready(c)) {
            return;
        }
        try {
            scene(c);
            configure(c, RED, YELLOW, BLUE, 3, 4.0f);
            double ex = origin[0] + STEP * 30, ez = origin[2];
            // The trail-off frames first, from where the walk will end.
            List<BufferedImage> offs = new ArrayList<>();
            enabled(c, false);
            for (Object[] a : ANGLES) {
                offs.add(angleShot(c, ex, ez, a, "off"));
            }
            c.onClient(mc -> {
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                return null;
            });
            place(c, origin[0], origin[2], 0f);
            c.ticks(2);
            enabled(c, true);
            walk(c, line(origin[0], origin[2], 1, 0, 30), 0f);
            float[] first = null;
            for (int i = 0; i < ANGLES.length; i++) {
                Object[] a = ANGLES[i];
                BufferedImage on = angleShot(c, ex, ez, a, "on");
                float[] v = c.onClient(mc -> (float[]) Mod.staticCall(RIBBON, "lastVertices"));
                String vertical = verticalProblem(v, HEIGHT);
                int[] ext = extent(offs.get(i), on);
                c.note(String.format(Locale.ROOT, "%s (yaw %.0f, pitch %.0f): %d vertices; on screen %d changed px, "
                        + "tallest column %d px, %d columns", a[0], (Float) a[1], (Float) a[2], v == null ? 0 : v.length / 3,
                        ext[0], ext[1], ext[2]));
                c.check(vertical == null, a[0] + ": the band is not a vertical strip: " + vertical);
                if (first == null) {
                    first = v;
                } else {
                    c.check(sameGeometry(first, v), a[0] + ": the band's world-space vertices changed with the camera ("
                            + (first.length / 3) + " vs " + (v == null ? 0 : v.length / 3) + " vertices, max move "
                            + maxMove(first, v) + ")");
                }
                c.check(ext[0] > 300, a[0] + ": the band is (nearly) invisible from here: " + ext[0] + " px");
                if ((Boolean) a[4]) {
                    c.check(ext[1] >= 12, a[0] + ": the band collapsed to a flat line on screen (tallest column "
                            + ext[1] + " px)");
                }
            }
        } finally {
            restore(c);
        }
    }

    /** Places the player at (x, z) with the angle's yaw, pitch and camera, then takes a shot. */
    private static BufferedImage angleShot(UiCase c, double x, double z, Object[] a, String suffix) {
        c.onClient(mc -> {
            mc.options.setCameraType((CameraType) a[3]);
            mc.player.getAbilities().flying = true;
            mc.player.setPos(x, origin[1], z);
            mc.player.setDeltaMovement(Vec3.ZERO);
            mc.player.setYRot((Float) a[1]);
            mc.player.setXRot((Float) a[2]);
            mc.player.yRotO = (Float) a[1];
            mc.player.xRotO = (Float) a[2];
            return null;
        });
        return shot(c, a[0] + "-" + suffix, 3);
    }

    /** {changed pixels, tallest run of changed pixels in one column (top to bottom), columns with any change}. */
    private static int[] extent(BufferedImage off, BufferedImage on) {
        int changed = 0, tallest = 0, cols = 0;
        for (int x = 0; x < on.getWidth(); x++) {
            int top = -1, bottom = -1;
            for (int y = 0; y < on.getHeight(); y++) {
                if (delta(on.getRGB(x, y), off.getRGB(x, y)) > 30) {
                    changed++;
                    if (top < 0) {
                        top = y;
                    }
                    bottom = y;
                }
            }
            if (top >= 0) {
                cols++;
                tallest = Math.max(tallest, bottom - top + 1);
            }
        }
        return new int[]{changed, tallest, cols};
    }

    private static boolean sameGeometry(float[] a, float[] b) {
        return b != null && a.length == b.length && maxMove(a, b) < 1e-4;
    }

    private static double maxMove(float[] a, float[] b) {
        if (b == null || a.length != b.length) {
            return Double.NaN;
        }
        double m = 0;
        for (int i = 0; i < a.length; i++) {
            m = Math.max(m, Math.abs(a[i] - b[i]));
        }
        return m;
    }

    // ---- 1035 gallery ---------------------------------------------------------------------------------------------

    static void gallery(UiCase c) {
        if (!ready(c)) {
            return;
        }
        try {
            scene(c);
            configure(c, 0xFFFF2A1A, 0xFFFF8C1A, 0xFFFFE01A, 3, 1.5f);
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setOpacityPercent", 85);
                return null;
            });
            walk(c, line(origin[0], origin[2], 1, 0, 40), 0f);
            Band side = band(shotPair(c, "default-side"), lastOn, 50);
            place(c, origin[0], origin[2], 30f);
            c.ticks(40);
            enabled(c, true);
            walk(c, line(origin[0], origin[2], 0, 1, 30), 30f); // 30 degrees off the walk: dead behind is edge-on
            Band behind = band(shotPair(c, "default-behind"), lastOn, 0);
            place(c, origin[0], origin[2], 0f);
            c.ticks(40);
            enabled(c, true);
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setColorMode", Mod.enumValue(MODE, "CHROMA"));
                return null;
            });
            walk(c, line(origin[0], origin[2], 1, 0, 40), 0f);
            Band chroma = band(shotPair(c, "chroma-side"), lastOn, 50);
            c.note("default side: " + side.describe());
            c.note("default behind: " + behind.describe());
            c.note("chroma side: " + chroma.describe());
            c.check(side.size() > 1500 && behind.size() > 1500 && chroma.size() > 1500,
                    "a gallery shot has no band (sizes " + side.size() + ", " + behind.size() + ", " + chroma.size() + ")");
        } finally {
            restore(c);
        }
    }

    private static BufferedImage lastOn;

    /** Takes "label" with the trail on, then "label-off" with it off; returns the off frame, {@link #lastOn} the on. */
    private static BufferedImage shotPair(UiCase c, String label) {
        lastOn = shot(c, label, 2);
        enabled(c, false);
        return shot(c, label + "-off", 4);
    }
}

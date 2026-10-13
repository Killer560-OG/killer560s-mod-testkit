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
 * killer560's 2026-10-13 Ribbon trail (mod trail-ribbon): Trail's new Style "Ribbon", one smooth band streaming behind
 * the player from waist height, head colour at the body to tail colour at the end, fading to nothing there, shrinking
 * away when he stands still. Judged from F5 screenshots: each frame is diffed against a frame of the SAME scene with
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
 *       gone after it; switching Style back to Squares removes it at once; Show In First Person OFF draws nothing in
 *       first person, ON draws.</li>
 *   <li>1034 settings: every ribbon field survives save + load, an old settings file loads as Squares, clamps, the file
 *       is in config/killer560/interface/trail/, the tab's rows, cycling buttons and tooltips.</li>
 *   <li>1035 gallery: the default red / orange / yellow from the side, from behind and round a curve, and Chroma.</li>
 * </ul>
 */
final class TrailRibbonCases {

    private static final String CFG = "trail.TrailConfig";
    private static final String STYLE = "trail.TrailConfig$Style";
    private static final String MODE = "trail.TrailConfig$ColorMode";
    private static final String RIBBON = "trail.RibbonTrail";

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
        if (!Mod.has(RIBBON)) {
            c.problem("this jar has no Ribbon trail (mod before trail-ribbon)");
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
            Object cfg = Mod.cfg(CFG);
            Mod.call(cfg, "setEnabled", false);
            return null;
        });
        place(c, origin[0], origin[2], 0f);
        c.ticks(10);
    }

    private static void configure(UiCase c, int head, int mid, int tail, int colors, float seconds) {
        c.onClient(mc -> {
            Object cfg = Mod.cfg(CFG);
            Mod.call(cfg, "setStyle", Mod.enumValue(STYLE, "RIBBON"));
            Mod.call(cfg, "setColorMode", Mod.enumValue(MODE, "GRADIENT"));
            Mod.call(cfg, "setColorCount", colors);
            Mod.call(cfg, "setHeadColor", head);
            Mod.call(cfg, "setMidColor", mid);
            Mod.call(cfg, "setTailColor", tail);
            Mod.call(cfg, "setRibbonWidth", 0.8f);
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
            Mod.call(cfg, "setStyle", Mod.enumValue(STYLE, "SQUARES"));
            Mod.call(cfg, "setColorMode", Mod.enumValue(MODE, "GRADIENT"));
            Mod.call(cfg, "save");
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
            c.check(reddish(b.nearD()), "near the player the band is not the head colour (red): " + b.describe());
            c.check(yellowish(b.midD()), "the band's middle is not the middle colour (yellow): " + b.describe());
            c.check(headToTail(b), "the band does not run red at the player to blue at the end: " + b.describe());
            c.check(b.nearMag() > b.farMag() * 1.5, "the band does not fade toward the tail: " + b.describe());
            c.check(b.height() < b.thickness() * 1.6, "a straight walk drew a bent band: " + b.describe());
            // The band starts behind him (against his movement), not inside his body: nothing drawn over him.
            int overBody = overPlayer(off, on, 20);
            c.note("changed pixels over the player's torso and legs (centre +-20 px): " + overBody);
            c.check(overBody < 50, "the ribbon is drawn over the player (" + overBody + " px in front of his body)");
            // From behind: walking forward (south), the band streams back toward the camera and down the screen.
            place(c, origin[0], origin[2], 0f);
            c.ticks(40); // the side walk's band shrinks away (1.5 s)
            enabled(c, true);
            walk(c, line(origin[0], origin[2], 0, 1, 30), 0f);
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

            // Switching back to Squares removes it at once.
            place(c, origin[0], origin[2], 0f);
            enabled(c, true);
            walk(c, line(origin[0], origin[2], 1, 0, 40), 0f);
            BufferedImage ribbon = shot(c, "switch-ribbon", 1);
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setStyle", Mod.enumValue(STYLE, "SQUARES"));
                return null;
            });
            long frS = frames();
            BufferedImage squares = shot(c, "switch-squares", 3);
            long drawnAfter = frames() - frS;
            int ptsS = points();
            enabled(c, false);
            BufferedImage off2 = shot(c, "switch-off", 4);
            Band br = band(off2, ribbon, 50), bs = band(off2, squares, 50);
            c.note("ribbon before switch: " + br.describe());
            c.note("after switching to Squares: " + bs.describe() + "; ribbon frames since " + drawnAfter);
            c.check(br.size() > 1500, "no band before switching: " + br.describe());
            c.check(drawnAfter == 0 && bs.changed() < 30, "the ribbon is still drawn after switching to Squares: "
                    + drawnAfter + " frames, " + bs.describe());
            c.check(ptsS == 0 || drawnAfter == 0, "ribbon points kept after the switch: " + ptsS);

            // Show In First Person.
            c.onClient(mc -> {
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                Object cfg = Mod.cfg(CFG);
                Mod.call(cfg, "setStyle", Mod.enumValue(STYLE, "RIBBON"));
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
        Path file = c.onClient(mc -> mc.gameDirectory.toPath().resolve("config/killer560/interface/trail/killer560smod-trail.json"));
        try {
            Object cfg = Mod.cfg(CFG);
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setStyle", Mod.enumValue(STYLE, "RIBBON"));
                Mod.call(cfg, "setRibbonWidth", 1.25f);
                Mod.call(cfg, "setRibbonSeconds", 2.5f);
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
            String got = Mod.call(back, "isEnabledRaw") + " " + Mod.call(back, "getStyle") + " "
                    + Mod.call(back, "getRibbonWidth") + " " + Mod.call(back, "getRibbonSeconds") + " "
                    + Mod.call(back, "getColorMode") + " " + Mod.call(back, "getColorCount") + " "
                    + Integer.toHexString((Integer) Mod.call(back, "getHeadColor")) + " "
                    + Integer.toHexString((Integer) Mod.call(back, "getMidColor")) + " "
                    + Integer.toHexString((Integer) Mod.call(back, "getTailColor")) + " "
                    + Mod.call(back, "isShowInFirstPerson");
            c.note("after save + load: " + got + "; file " + file + " exists " + Files.exists(file));
            c.check(back != cfg, "load() did not make a new instance, so the round trip proved nothing");
            c.check(got.equals("true RIBBON 1.25 2.5 CHROMA 2 ff123456 ff654321 ffabcdef false"), "round trip gave " + got);
            c.check(Files.exists(file), "no settings file at " + file);

            // A file from before the Ribbon (no style key) loads as the old Squares trail.
            Files.writeString(file, "{\"enabled\": true, \"length\": 40, \"squareSize\": 0.3, \"opacityPercent\": 60, "
                    + "\"fadeOut\": true}");
            c.onClient(mc -> Mod.staticCall(CFG, "load"));
            Object old = Mod.cfg(CFG);
            c.note("old file loads as: " + Mod.call(old, "getStyle") + ", first person " + Mod.call(old, "isShowInFirstPerson"));
            c.check("SQUARES".equals(String.valueOf(Mod.call(old, "getStyle"))), "an old settings file did not load as Squares");
            c.check(Boolean.TRUE.equals(Mod.call(old, "isShowInFirstPerson")), "an old file lost first-person drawing");

            // Clamps.
            Mod.call(old, "setRibbonWidth", 50f);
            Mod.call(old, "setRibbonSeconds", 0f);
            Mod.call(old, "setColorCount", 9);
            String clamped = Mod.call(old, "getRibbonWidth") + " " + Mod.call(old, "getRibbonSeconds") + " "
                    + Mod.call(old, "getColorCount");
            c.note("clamped: " + clamped);
            c.check(clamped.equals("2.0 0.2 3"), "clamps gave " + clamped);

            // The tab's rows, in Ribbon / Gradient / 3 colours, and in Squares.
            List<String> ribbonRows = tabRows(c, old, "RIBBON", "GRADIENT", 3);
            List<String> chromaRows = tabRows(c, old, "RIBBON", "CHROMA", 3);
            List<String> squareRows = tabRows(c, old, "SQUARES", "GRADIENT", 3);
            c.note("ribbon rows: " + ribbonRows);
            c.note("chroma rows: " + chromaRows);
            c.note("squares rows: " + squareRows);
            for (String want : new String[]{"Style: Ribbon *", "Ribbon Width: 2.00", "Ribbon Length: 0.2s", "Color Mode: Gradient *",
                    "Colors: 3 *", "Head Color", "Middle Color", "Tail Color", "Show In First Person"}) {
                c.check(ribbonRows.stream().anyMatch(r -> r.startsWith(want)), "Ribbon tab has no row '" + want + "': " + ribbonRows);
            }
            c.check(chromaRows.stream().noneMatch(r -> r.startsWith("Head Color")), "Chroma still shows colour pickers");
            c.check(squareRows.stream().noneMatch(r -> r.startsWith("Ribbon")), "Squares shows ribbon rows: " + squareRows);
            c.check(squareRows.stream().anyMatch(r -> r.startsWith("Square Size")), "Squares lost its rows: " + squareRows);
            for (String label : new String[]{"Style: Ribbon", "Ribbon Width: 2.00", "Ribbon Length: 0.2s", "Opacity: 60%",
                    "Color Mode: Gradient", "Colors: 3", "Head Color", "Middle Color", "Tail Color",
                    "Show In First Person: ON"}) {
                String tip = c.onClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "describe", "Trail", label));
                c.note("tooltip '" + label + "': " + tip);
                c.check(tip != null && !tip.isEmpty(), "no tooltip for '" + label + "'");
            }
        } finally {
            c.onClient(mc -> {
                Object cfg = Mod.cfg(CFG);
                Mod.call(cfg, "setEnabled", false);
                Mod.call(cfg, "setStyle", Mod.enumValue(STYLE, "SQUARES"));
                Mod.call(cfg, "setColorMode", Mod.enumValue(MODE, "GRADIENT"));
                Mod.call(cfg, "setColorCount", 3);
                Mod.call(cfg, "setRibbonWidth", 0.6f);
                Mod.call(cfg, "setRibbonSeconds", 1.0f);
                Mod.call(cfg, "setHeadColor", 0xFFFF2A1A);
                Mod.call(cfg, "setMidColor", 0xFFFF8C1A);
                Mod.call(cfg, "setTailColor", 0xFFFFE01A);
                Mod.call(cfg, "setShowInFirstPerson", true);
                Mod.call(cfg, "save");
                return null;
            });
        }
    }

    /** The Trail tab's rows (message, " *" when it is a cycling button) for a style / colour mode / count. */
    private static List<String> tabRows(UiCase c, Object cfg, String style, String mode, int colors) {
        return c.onClient(mc -> {
            Mod.call(cfg, "setEnabled", true);
            Mod.call(cfg, "setStyle", Mod.enumValue(STYLE, style));
            Mod.call(cfg, "setColorMode", Mod.enumValue(MODE, mode));
            Mod.call(cfg, "setColorCount", colors);
            Object tab;
            try {
                tab = Mod.cls("gui.tab.TrailTab").getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not make a TrailTab: " + e, e);
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
            place(c, origin[0], origin[2], 0f);
            c.ticks(40);
            enabled(c, true);
            walk(c, line(origin[0], origin[2], 0, 1, 30), 0f);
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

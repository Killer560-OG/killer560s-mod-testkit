package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.CameraType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * killer560's 2026-10-09 Held Item Outline (mod held-outline): an outline / fill / filled outline around the
 * FIRST-PERSON held item model, never the hotbar or third person. Judged from screenshots, every frame diffed against a
 * frame of the same scene with the feature OFF (a pixel counts as changed above a summed RGB delta of 30, the
 * {@code CrosshairCases} rule), plus the renderer's own counters so a frame that never ran the pass cannot read as
 * "nothing changed".
 * <ul>
 *   <li>671 controls: OFF draws nothing and runs nothing; two OFF frames agree (the diff can be trusted); with Skyblock
 *       Only ON outside Skyblock it still draws (killer560, 2026-10-11).</li>
 *   <li>672 styles: opaque blue Fill is the item's silhouette S (no change outside it); red Outline lies outside S, next
 *       to it, within the width, and leaves the item itself untouched; width 1 vs 6; Filled Outline draws both colours;
 *       a half-alpha fill blends instead of painting.</li>
 *   <li>673 hands: an empty hand draws and runs nothing; Off Hand adds the off-hand item (2 items) and pixels on the
 *       left; Off Hand OFF keeps it at 1.</li>
 *   <li>674 HUD and camera: the hotbar (its slot with the held item's icon) never changes; third person draws and runs
 *       nothing.</li>
 *   <li>675 settings: every field survives save + load, the file lands in config/killer560/interface/heldoutline/, and
 *       the tab is in the Cosmetics category.</li>
 * </ul>
 */
final class HeldOutlineCases {

    private static final String CFG = "heldoutline.HeldOutlineConfig";
    private static final String STYLE = "heldoutline.HeldOutlineConfig$Style";
    private static final String RENDERER = "heldoutline.HeldOutlineRenderer";

    private HeldOutlineCases() {
    }

    // ---- shared scene ---------------------------------------------------------------------------------------------

    private static ItemStack[] savedInv;
    private static float savedPitch;
    private static float savedYaw;

    private static boolean ready(UiCase c) {
        if (!Mod.has(CFG)) {
            c.problem("this jar has no Held Item Outline (mod before held-outline)");
            return false;
        }
        return true;
    }

    /** Pitch 90 onto the superflat grass, HUD up, nothing else drawn, sword in slot 0, feature OFF with known looks. */
    private static void scene(UiCase c, ItemStack main, ItemStack off) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            McCompat.clearChatAndToasts(mc);
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            if (savedInv == null) {
                var inv = mc.player.getInventory();
                savedInv = new ItemStack[42];
                for (int i = 0; i < 41; i++) {
                    savedInv[i] = inv.getItem(i).copy();
                }
                savedInv[41] = new ItemStack(Items.STICK, inv.getSelectedSlot() + 1);
                savedPitch = mc.player.getXRot();
                savedYaw = mc.player.getYRot();
            }
            mc.player.setXRot(90f);
            mc.player.setYRot(0f);
            Object cfg = Mod.cfg(CFG);
            Mod.call(cfg, "setEnabled", false);
            Mod.call(cfg, "setMainHand", true);
            Mod.call(cfg, "setOffHand", false);
            Mod.call(cfg, "setWidth", 3f);
            return null;
        });
        setHands(c, main, off);
    }

    /** Puts the stacks in slot 0 and the off hand on the client AND the integrated server, so no sync undoes it. */
    private static void setHands(UiCase c, ItemStack main, ItemStack off) {
        c.onClient(mc -> {
            var inv = mc.player.getInventory();
            for (int i = 0; i < 41; i++) {
                inv.setItem(i, ItemStack.EMPTY);
            }
            inv.setItem(0, main.copy());
            inv.setItem(40, off.copy());
            inv.setSelectedSlot(0);
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            if (server != null) {
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayer(uuid);
                    if (sp != null) {
                        var sinv = sp.getInventory();
                        for (int i = 0; i < 41; i++) {
                            sinv.setItem(i, ItemStack.EMPTY);
                        }
                        sinv.setItem(0, main.copy());
                        sinv.setItem(40, off.copy());
                        sinv.setSelectedSlot(0);
                    }
                });
            }
            return null;
        });
        c.ticks(90); // equip animation, hand sway and the hotbar item-name popup (about 3 s) settle
    }

    private static void restore(UiCase c) {
        c.onClient(mc -> {
            Object cfg = Mod.cfg(CFG);
            Mod.call(cfg, "setEnabled", false);
            Mod.call(cfg, "save");
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            if (savedInv != null) {
                var inv = mc.player.getInventory();
                for (int i = 0; i < 41; i++) {
                    inv.setItem(i, savedInv[i]);
                }
                inv.setSelectedSlot(savedInv[41].getCount() - 1);
                mc.player.setXRot(savedPitch);
                mc.player.setYRot(savedYaw);
                savedInv = null;
            }
            return null;
        });
    }

    private static void configure(UiCase c, boolean on, String style, int outline, int fill, float width) {
        c.onClient(mc -> {
            Object cfg = Mod.cfg(CFG);
            Mod.call(cfg, "setEnabled", on);
            Mod.call(cfg, "setStyle", Mod.enumValue(STYLE, style));
            Mod.call(cfg, "setOutlineColor", outline);
            Mod.call(cfg, "setFillColor", fill);
            Mod.call(cfg, "setWidth", width);
            return null;
        });
    }

    private static long frames() {
        return ((Number) Mod.staticCall(RENDERER, "framesDrawn")).longValue();
    }

    private static int items() {
        return ((Number) Mod.staticCall(RENDERER, "lastItemCount")).intValue();
    }

    // ---- frames ---------------------------------------------------------------------------------------------------

    private static BufferedImage shot(UiCase c, String label) {
        c.ticks(6);
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

    /** Changed-pixel mask of {@code img} against {@code base}. */
    private static boolean[][] changed(BufferedImage base, BufferedImage img) {
        boolean[][] m = new boolean[img.getHeight()][img.getWidth()];
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                m[y][x] = delta(img.getRGB(x, y), base.getRGB(x, y)) > 30;
            }
        }
        return m;
    }

    private static int count(boolean[][] m) {
        int n = 0;
        for (boolean[] row : m) {
            for (boolean b : row) {
                if (b) {
                    n++;
                }
            }
        }
        return n;
    }

    private static boolean pure(int rgb, int channel) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return switch (channel) {
            case 0 -> r > 200 && g < 60 && b < 60;
            case 1 -> g > 200 && r < 60 && b < 60;
            default -> b > 200 && r < 60 && g < 60;
        };
    }

    /** Pixels of {@code img} that changed AND are the pure colour. */
    private static boolean[][] colour(BufferedImage base, BufferedImage img, int channel) {
        boolean[][] m = changed(base, img);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                m[y][x] = m[y][x] && pure(img.getRGB(x, y), channel);
            }
        }
        return m;
    }

    private static String box(boolean[][] m) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        for (int y = 0; y < m.length; y++) {
            for (int x = 0; x < m[y].length; x++) {
                if (m[y][x]) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return maxX < 0 ? "(none)" : (maxX - minX + 1) + "x" + (maxY - minY + 1) + " at " + minX + "," + minY;
    }

    /** For each set pixel of {@code m}, the distance to the nearest pixel of {@code s} (searched up to 12 px). */
    private static double[] distances(boolean[][] m, boolean[][] s) {
        List<Double> out = new ArrayList<>();
        int h = m.length, w = m[0].length;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!m[y][x]) {
                    continue;
                }
                double best = 99;
                for (int dy = -12; dy <= 12; dy++) {
                    for (int dx = -12; dx <= 12; dx++) {
                        int yy = y + dy, xx = x + dx;
                        if (yy >= 0 && yy < h && xx >= 0 && xx < w && s[yy][xx]) {
                            best = Math.min(best, Math.sqrt(dx * dx + dy * dy));
                        }
                    }
                }
                out.add(best);
            }
        }
        double[] d = new double[out.size()];
        for (int i = 0; i < d.length; i++) {
            d[i] = out.get(i);
        }
        return d;
    }

    private static double max(double[] d) {
        double m = 0;
        for (double v : d) {
            m = Math.max(m, v);
        }
        return m;
    }

    private static int overlap(boolean[][] a, boolean[][] b) {
        int n = 0;
        for (int y = 0; y < a.length; y++) {
            for (int x = 0; x < a[y].length; x++) {
                if (a[y][x] && b[y][x]) {
                    n++;
                }
            }
        }
        return n;
    }

    /** The silhouette S of the main-hand sword: an opaque blue fill, which must change nothing outside itself. */
    private static boolean[][] silhouette(UiCase c, BufferedImage base, String label) {
        configure(c, true, "FILL", 0xFFFF0000, 0xFF0000FF, 3f);
        BufferedImage img = shot(c, label);
        boolean[][] s = colour(base, img, 2);
        int all = count(changed(base, img));
        int n = count(s);
        c.note(label + ": silhouette " + n + " blue px, box " + box(s) + "; " + (all - n) + " other changed px");
        if (n < 300) {
            c.problem(label + ": an opaque blue Fill gave only " + n + " blue px - the held item was not filled");
        }
        if (all - n > Math.max(20, n / 50)) {
            c.problem(label + ": Fill changed " + (all - n) + " px that are not the fill colour");
        }
        return s;
    }

    // ---- 671 ------------------------------------------------------------------------------------------------------

    static void controls(UiCase c) {
        if (!ready(c)) {
            return;
        }
        try {
            scene(c, new ItemStack(Items.DIAMOND_SWORD), ItemStack.EMPTY);
            BufferedImage base = shot(c, "off-a");
            long f0 = frames();
            BufferedImage again = shot(c, "off-b");
            long f1 = frames();
            int noise = count(changed(base, again));
            c.note("two OFF frames differ in " + noise + " px; frames drawn " + f0 + " -> " + f1 + "; frame "
                    + base.getWidth() + "x" + base.getHeight());
            if (noise > 0) {
                c.problem("two OFF frames of the same scene differ in " + noise + " px - the diff is not trustworthy");
            }
            if (f1 != f0) {
                c.problem("the outline pass ran " + (f1 - f0) + " time(s) with the feature OFF");
            }
            // ON then OFF again: the OFF frame must be back to the base exactly.
            configure(c, true, "FILLED_OUTLINE", 0xFFFF0000, 0xFF0000FF, 3f);
            long f2 = frames();
            BufferedImage on = shot(c, "on");
            long f3 = frames();
            int onChanged = count(changed(base, on));
            configure(c, false, "FILLED_OUTLINE", 0xFFFF0000, 0xFF0000FF, 3f);
            BufferedImage offAgain = shot(c, "off-c");
            long f4 = frames();
            int back = count(changed(base, offAgain));
            c.note("ON: " + onChanged + " changed px, frames " + f2 + " -> " + f3 + ", items " + items()
                    + "; OFF again: " + back + " changed px, frames " + f3 + " -> " + f4);
            if (f3 <= f2 || onChanged < 300) {
                c.problem("ON did not act (" + onChanged + " px, " + (f3 - f2) + " frames) - the OFF control proves nothing");
            }
            // The renderer's test views: 1 = its composite pass paints the frame magenta, 2 = the mask's silhouette.
            // These split "the pass never wrote to the frame" from "the item never reached the mask".
            configure(c, true, "FILLED_OUTLINE", 0xFFFF0000, 0xFF0000FF, 3f);
            Mod.setField(RENDERER, "debugView", 1);
            BufferedImage magenta = shot(c, "debug-pass");
            Mod.setField(RENDERER, "debugView", 2);
            BufferedImage mask = shot(c, "debug-mask");
            Mod.setField(RENDERER, "debugView", 0);
            int pink = 0, white = 0;
            for (int y = 0; y < magenta.getHeight(); y++) {
                for (int x = 0; x < magenta.getWidth(); x++) {
                    int p = magenta.getRGB(x, y);
                    if (((p >> 16) & 0xFF) > 240 && ((p >> 8) & 0xFF) < 15 && (p & 0xFF) > 240) {
                        pink++;
                    }
                    if ((mask.getRGB(x, y) & 0xFF) > 240 && ((mask.getRGB(x, y) >> 8) & 0xFF) > 240) {
                        white++;
                    }
                }
            }
            c.note("test views: the pass painted " + pink + " magenta px of " + (magenta.getWidth() * magenta.getHeight())
                    + "; the mask holds " + white + " silhouette px");
            if (white < 300) {
                c.problem("the mask holds only " + white + " silhouette px - the held item never reached it");
            }
            if (back > 0 || f4 != f3) {
                c.problem("switching OFF left " + back + " changed px and " + (f4 - f3) + " pass run(s)");
            }

            // killer560 (2026-10-11): "It should ALWAYS show when it's on" - Skyblock Only ON, in this singleplayer
            // world (not Skyblock), must not hide it. The gate is proved to be refusing first, or the check is vacuous.
            c.onClient(mc -> {
                Mod.staticCall("util.SkyblockGate", "setEnabled", true);
                return null;
            });
            try {
                boolean gateAllows = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "allows"));
                configure(c, true, "FILLED_OUTLINE", 0xFFFF0000, 0xFF0000FF, 3f);
                long g0 = frames();
                BufferedImage gated = shot(c, "skyblock-only-on");
                long g1 = frames();
                int gatedChanged = count(changed(base, gated));
                c.note("Skyblock Only ON outside Skyblock: SkyblockGate.allows() = " + gateAllows + "; " + gatedChanged
                        + " changed px, pass ran " + (g1 - g0) + "x");
                if (gateAllows) {
                    c.problem("SkyblockGate allows this world with Skyblock Only ON, so the check proves nothing");
                }
                if (g1 <= g0 || gatedChanged < 300) {
                    c.problem("with Skyblock Only ON outside Skyblock the outline did not draw (" + gatedChanged + " px, "
                            + (g1 - g0) + " pass runs)");
                }
            } finally {
                c.onClient(mc -> {
                    Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                    return null;
                });
            }
        } finally {
            restore(c);
        }
    }

    // ---- 672 ------------------------------------------------------------------------------------------------------

    static void styles(UiCase c) {
        if (!ready(c)) {
            return;
        }
        try {
            scene(c, new ItemStack(Items.DIAMOND_SWORD), ItemStack.EMPTY);
            BufferedImage base = shot(c, "off");
            long f0 = frames();
            boolean[][] s = silhouette(c, base, "fill-blue");
            if (frames() <= f0 || items() != 1) {
                c.problem("Fill: the pass ran " + (frames() - f0) + " time(s) with " + items() + " item(s), expected 1 item");
            }

            // Red outline, width 3.
            configure(c, true, "OUTLINE", 0xFFFF0000, 0xFF0000FF, 3f);
            BufferedImage o3img = shot(c, "outline-red-w3");
            boolean[][] o3 = colour(base, o3img, 0);
            boolean[][] o3all = changed(base, o3img);
            double[] d3 = distances(o3, s);
            int inItem = overlap(o3all, s);
            c.note(String.format(Locale.ROOT, "outline w3: %d red px, box %s, max distance to the item %.2f, %d changed px "
                    + "inside the item, %d changed px in all", count(o3), box(o3), max(d3), inItem, count(o3all)));
            if (count(o3) < 150) {
                c.problem("Outline: only " + count(o3) + " red px around the held item");
            }
            if (overlap(o3, s) > 0) {
                c.problem("Outline: " + overlap(o3, s) + " red px are ON the item (should only be around it)");
            }
            if (inItem > count(s) / 100) {
                c.problem("Outline: " + inItem + " px of the item itself changed");
            }
            if (max(d3) > 4.0) {
                c.problem(String.format(Locale.ROOT, "Outline w3: a red px %.2f px from the item", max(d3)));
            }

            // Width 1 vs 6.
            configure(c, true, "OUTLINE", 0xFFFF0000, 0xFF0000FF, 1f);
            boolean[][] o1 = colour(base, shot(c, "outline-red-w1"), 0);
            configure(c, true, "OUTLINE", 0xFFFF0000, 0xFF0000FF, 6f);
            boolean[][] o6 = colour(base, shot(c, "outline-red-w6"), 0);
            double[] d1 = distances(o1, s);
            double[] d6 = distances(o6, s);
            c.note(String.format(Locale.ROOT, "width 1: %d red px, max distance %.2f; width 6: %d red px, max distance %.2f",
                    count(o1), max(d1), count(o6), max(d6)));
            if (count(o1) < 100 || !(count(o1) < count(o3) && count(o3) < count(o6))) {
                c.problem("Outline width does not grow the outline: w1 " + count(o1) + ", w3 " + count(o3) + ", w6 "
                        + count(o6) + " px");
            }
            if (max(d1) > 2.0 || max(d6) > 7.0 || max(d6) < 5.5) {
                c.problem(String.format(Locale.ROOT, "Outline reach: w1 %.2f px (want <= 2), w6 %.2f px (want 5.5-7)",
                        max(d1), max(d6)));
            }

            // Filled outline: green line + blue fill.
            configure(c, true, "FILLED_OUTLINE", 0xFF00FF00, 0xFF0000FF, 3f);
            BufferedImage fo = shot(c, "filled-outline-green-blue");
            boolean[][] g = colour(base, fo, 1);
            boolean[][] b = colour(base, fo, 2);
            c.note("filled outline: " + count(g) + " green px (box " + box(g) + "), " + count(b) + " blue px (silhouette "
                    + count(s) + ")");
            if (count(g) < 150) {
                c.problem("Filled Outline: only " + count(g) + " outline px");
            }
            if (Math.abs(count(b) - count(s)) > Math.max(10, count(s) / 50)) {
                c.problem("Filled Outline: the fill is " + count(b) + " px, the silhouette " + count(s));
            }
            if (overlap(g, s) > 0) {
                c.problem("Filled Outline: " + overlap(g, s) + " outline px lie on the item");
            }

            // Colour change: a red fill paints the same pixels red, none blue.
            configure(c, true, "FILL", 0xFF00FF00, 0xFFFF0000, 3f);
            BufferedImage fr = shot(c, "fill-red");
            int red = count(colour(base, fr, 0));
            int blueLeft = count(colour(base, fr, 2));
            c.note("red fill: " + red + " red px, " + blueLeft + " blue px");
            if (Math.abs(red - count(s)) > Math.max(10, count(s) / 50) || blueLeft > 0) {
                c.problem("Fill colour change: " + red + " red px (silhouette " + count(s) + "), " + blueLeft + " blue px left");
            }

            // Alpha: a half-transparent blue fill blends with the item instead of painting it.
            configure(c, true, "FILL", 0xFF00FF00, 0x800000FF, 3f);
            BufferedImage half = shot(c, "fill-blue-half-alpha");
            boolean[][] halfChanged = changed(base, half);
            int halfIn = overlap(halfChanged, s);
            int halfPure = count(colour(base, half, 2));
            double blueGain = 0;
            int n = 0;
            for (int y = 0; y < s.length; y++) {
                for (int x = 0; x < s[y].length; x++) {
                    if (s[y][x]) {
                        blueGain += (half.getRGB(x, y) & 0xFF) - (base.getRGB(x, y) & 0xFF);
                        n++;
                    }
                }
            }
            blueGain = n == 0 ? 0 : blueGain / n;
            c.note(String.format(Locale.ROOT, "half-alpha fill: %d of %d item px changed, %d pure blue, mean blue gain %.1f",
                    halfIn, count(s), halfPure, blueGain));
            if (halfIn < count(s) / 2 || halfPure > count(s) / 10 || blueGain < 40) {
                c.problem("Fill alpha 50%: did not blend (" + halfIn + " item px changed, " + halfPure + " pure blue, gain "
                        + String.format(Locale.ROOT, "%.1f", blueGain) + ")");
            }
        } finally {
            restore(c);
        }
    }

    // ---- 673 ------------------------------------------------------------------------------------------------------

    static void hands(UiCase c) {
        if (!ready(c)) {
            return;
        }
        try {
            // Empty hand: nothing recorded, nothing drawn.
            scene(c, ItemStack.EMPTY, ItemStack.EMPTY);
            BufferedImage emptyOff = shot(c, "empty-off");
            configure(c, true, "FILLED_OUTLINE", 0xFFFF0000, 0xFF0000FF, 3f);
            long f0 = frames();
            BufferedImage emptyOn = shot(c, "empty-on");
            long f1 = frames();
            int diff = count(changed(emptyOff, emptyOn));
            c.note("empty hand: " + diff + " changed px, pass ran " + (f1 - f0) + " time(s)");
            if (diff > 0 || f1 != f0) {
                c.problem("with an empty hand the feature changed " + diff + " px and ran " + (f1 - f0) + " time(s)");
            }

            // Main hand sword + off-hand apple: Off Hand OFF -> 1 item, ON -> 2 items and pixels on the left half.
            configure(c, false, "FILL", 0xFFFF0000, 0xFF0000FF, 3f);
            setHands(c, new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.APPLE));
            BufferedImage base = shot(c, "two-off");
            configure(c, true, "FILL", 0xFFFF0000, 0xFF0000FF, 3f);
            BufferedImage mainOnly = shot(c, "two-main-only");
            int itemsMain = items();
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setOffHand", true);
                return null;
            });
            long f2 = frames();
            BufferedImage both = shot(c, "two-both");
            int itemsBoth = items();
            long f3 = frames();
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setMainHand", false);
                return null;
            });
            BufferedImage offOnly = shot(c, "two-off-only");
            int itemsOff = items();
            int w = base.getWidth();
            boolean[][] bm = colour(base, mainOnly, 2);
            boolean[][] bb = colour(base, both, 2);
            boolean[][] bo = colour(base, offOnly, 2);
            int mainLeft = half(bm, w, true), mainRight = half(bm, w, false);
            int bothLeft = half(bb, w, true), bothRight = half(bb, w, false);
            int offLeft = half(bo, w, true), offRight = half(bo, w, false);
            c.note("main only: items " + itemsMain + ", blue left/right " + mainLeft + "/" + mainRight
                    + "; both: items " + itemsBoth + " (frames " + f2 + " -> " + f3 + "), " + bothLeft + "/" + bothRight
                    + "; off only: items " + itemsOff + ", " + offLeft + "/" + offRight);
            if (itemsMain != 1 || mainRight < 300 || mainLeft > 0) {
                c.problem("Main Hand only: " + itemsMain + " item(s), " + mainRight + " px right, " + mainLeft + " px left");
            }
            if (itemsBoth != 2 || bothLeft < 100 || bothRight < 300) {
                c.problem("Main + Off Hand: " + itemsBoth + " item(s), " + bothLeft + " px left, " + bothRight + " px right");
            }
            if (itemsOff != 1 || offLeft < 100 || offRight > 0) {
                c.problem("Off Hand only: " + itemsOff + " item(s), " + offLeft + " px left, " + offRight + " px right");
            }
        } finally {
            restore(c);
        }
    }

    private static int half(boolean[][] m, int w, boolean left) {
        int n = 0;
        for (boolean[] row : m) {
            for (int x = left ? 0 : w / 2; x < (left ? w / 2 : w); x++) {
                if (row[x]) {
                    n++;
                }
            }
        }
        return n;
    }

    // ---- 674 ------------------------------------------------------------------------------------------------------

    static void hudAndCamera(UiCase c) {
        if (!ready(c)) {
            return;
        }
        try {
            scene(c, new ItemStack(Items.DIAMOND_SWORD), ItemStack.EMPTY);
            BufferedImage base = shot(c, "off");
            configure(c, true, "FILLED_OUTLINE", 0xFFFF0000, 0xFF0000FF, 6f);
            long f0 = frames();
            BufferedImage on = shot(c, "on");
            long f1 = frames();
            boolean[][] ch = changed(base, on);
            // The hotbar, in screen pixels: 182 x 22 GUI units centred at the bottom; slot 0 (holding the sword's icon)
            // is its leftmost 20 x 20.
            int[] hb = c.onClient(mc -> {
                double s = mc.getWindow().getGuiScale();
                int gw = mc.getWindow().getGuiScaledWidth();
                int gh = mc.getWindow().getGuiScaledHeight();
                return new int[]{(int) Math.floor((gw / 2 - 91) * s), (int) Math.floor((gh - 22) * s),
                        (int) Math.ceil(182 * s), (int) Math.ceil(22 * s), (int) Math.ceil(22 * s)};
            });
            int slot0 = 0, hotbar = 0, hotbarTotal = 0;
            int itemMinX = Integer.MAX_VALUE;
            for (int y = 0; y < ch.length; y++) {
                for (int x = 0; x < ch[y].length; x++) {
                    if (ch[y][x]) {
                        itemMinX = Math.min(itemMinX, x);
                    }
                }
            }
            // Only the part of the hotbar left of anything the held item drew can be judged pixel for pixel.
            int judgeTo = Math.min(hb[0] + hb[2], itemMinX - 1);
            for (int y = hb[1]; y < Math.min(ch.length, hb[1] + hb[3]); y++) {
                for (int x = hb[0]; x < judgeTo; x++) {
                    hotbarTotal++;
                    if (ch[y][x]) {
                        hotbar++;
                        if (x < hb[0] + hb[4]) {
                            slot0++;
                        }
                    }
                }
            }
            c.note("ON: " + count(ch) + " changed px (box " + box(ch) + "), pass ran " + (f1 - f0) + "x; hotbar at "
                    + hb[0] + "," + hb[1] + " " + hb[2] + "x" + hb[3] + ", judged " + hotbarTotal + " px up to x " + judgeTo
                    + ": " + hotbar + " changed, slot 0 (sword icon) " + slot0 + " changed");
            if (f1 <= f0 || count(ch) < 300) {
                c.problem("the feature did not act in first person, so the hotbar check proves nothing");
            }
            if (hotbarTotal < 1000) {
                c.problem("only " + hotbarTotal + " hotbar px could be judged (the held item drew over most of it)");
            }
            if (hotbar > 0) {
                c.problem(hotbar + " hotbar px changed (" + slot0 + " in the slot with the sword's icon)");
            }

            // Third person: the sword in the player's hand must not be outlined, and the pass must not run.
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setEnabled", false);
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                return null;
            });
            c.ticks(10);
            BufferedImage tpOff = shot(c, "third-person-off");
            BufferedImage tpOff2 = shot(c, "third-person-off-2");
            int tpNoise = count(changed(tpOff, tpOff2));
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setEnabled", true);
                return null;
            });
            long f2 = frames();
            BufferedImage tpOn = shot(c, "third-person-on");
            long f3 = frames();
            // The third-person model idles (arm sway), so two OFF frames already differ a little: judge by the
            // feature's own colours (pure red outline, pure blue fill) and by the pass counter instead.
            int tp = count(changed(tpOff, tpOn));
            int tpRed = count(colour(tpOff, tpOn, 0));
            int tpBlue = count(colour(tpOff, tpOn, 2));
            c.note("third person: OFF/OFF " + tpNoise + " changed px (idle animation), OFF/ON " + tp + "; red " + tpRed
                    + ", blue " + tpBlue + "; pass ran " + (f3 - f2) + "x");
            if (tpRed > 0 || tpBlue > 0 || f3 != f2) {
                c.problem("third person: " + tpRed + " red + " + tpBlue + " blue px, pass ran " + (f3 - f2) + " time(s)");
            }
        } finally {
            restore(c);
        }
    }

    // ---- 675 ------------------------------------------------------------------------------------------------------

    static void settings(UiCase c) {
        if (!ready(c)) {
            return;
        }
        Object cfg = Mod.cfg(CFG);
        Path file = c.onClient(mc -> mc.gameDirectory.toPath().resolve("config/killer560/interface/heldoutline/killer560smod-heldoutline.json"));
        try {
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setStyle", Mod.enumValue(STYLE, "FILLED_OUTLINE"));
                Mod.call(cfg, "setOutlineColor", 0x7F123456);
                Mod.call(cfg, "setFillColor", 0x40ABCDEF);
                Mod.call(cfg, "setWidth", 6.5f);
                Mod.call(cfg, "setMainHand", false);
                Mod.call(cfg, "setOffHand", true);
                Mod.call(cfg, "save");
                Mod.staticCall(CFG, "load");
                return null;
            });
            Object back = Mod.cfg(CFG);
            String got = Mod.call(back, "isEnabledRaw") + " " + Mod.call(back, "getStyle") + " "
                    + Integer.toHexString((Integer) Mod.call(back, "getOutlineColor")) + " "
                    + Integer.toHexString((Integer) Mod.call(back, "getFillColor")) + " " + Mod.call(back, "getWidth") + " "
                    + Mod.call(back, "isMainHand") + " " + Mod.call(back, "isOffHand");
            c.note("after save + load: " + got + "; file " + file + " exists " + Files.exists(file));
            c.check(back != cfg, "load() did not make a new instance, so the round trip proved nothing");
            c.check(got.equals("true FILLED_OUTLINE 7f123456 40abcdef 6.5 false true"), "round trip gave " + got);
            c.check(Files.exists(file), "no settings file at " + file);
            // Defaults and clamps.
            Mod.call(back, "setWidth", 50f);
            c.check(((Number) Mod.call(back, "getWidth")).floatValue() == 10f, "width 50 was not clamped to 10");
            Mod.call(back, "setWidth", 0f);
            c.check(((Number) Mod.call(back, "getWidth")).floatValue() == 1f, "width 0 was not clamped to 1");
            // The tab: its own tab in Cosmetics.
            c.onClient(mc -> {
                McCompat.setScreen(mc, (net.minecraft.client.gui.screens.Screen) Mod.staticCall("gui.ModScreen", "atTab",
                        null, "Cosmetics"));
                return null;
            });
            c.ticks(4);
            List<String> cosmetics = c.onClient(mc -> {
                List<String> names = new ArrayList<>();
                @SuppressWarnings("unchecked")
                List<Object> tabs = (List<Object>) Mod.field("gui.ModScreen", "tabs");
                for (Object t : tabs) {
                    if (Mod.cls("gui.tab.FolderTab").isInstance(t) && "Cosmetics".equals(Mod.field(t, "name"))) {
                        for (Object sub : (List<?>) Mod.call(t, "subTabs")) {
                            names.add((String) Mod.field(sub, "name"));
                        }
                    }
                }
                return names;
            });
            c.note("Cosmetics tabs: " + cosmetics);
            c.check(cosmetics.contains("Held Item Outline"), "Held Item Outline is not a tab in Cosmetics: " + cosmetics);
            for (String label : new String[]{"Held Item Outline: OFF", "Style: Outline", "Outline Color", "Outline Width: 3.0 px",
                    "Fill Color", "Main Hand: ON", "Off Hand: OFF"}) {
                String tip = c.onClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "describe", "Held Item Outline",
                        label));
                c.note("tooltip '" + label + "': " + tip);
                c.check(tip != null && !tip.isEmpty(), "no tooltip for '" + label + "'");
            }
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
        } finally {
            c.onClient(mc -> {
                Object now = Mod.cfg(CFG);
                Mod.call(now, "setEnabled", false);
                Mod.call(now, "setStyle", Mod.enumValue(STYLE, "OUTLINE"));
                Mod.call(now, "setOutlineColor", 0xFFFF8C1A);
                Mod.call(now, "setFillColor", 0x55FF8C1A);
                Mod.call(now, "setWidth", 3f);
                Mod.call(now, "setMainHand", true);
                Mod.call(now, "setOffHand", false);
                Mod.call(now, "save");
                return null;
            });
        }
    }
}

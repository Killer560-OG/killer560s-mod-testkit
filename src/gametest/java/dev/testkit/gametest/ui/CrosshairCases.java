package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.phys.HitResult;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 389-ui-crosshair: the mod's Custom Crosshair (mod 2026-10-06, custom-crosshair) replaces vanilla's, draws where and
 * how it is told, pixel for pixel, judged from screenshots.
 *
 * <p>The scene: the player looks straight down at the superflat (a static texture: no clouds, no sky drift), the HUD
 * on (F1 is not touched: {@code options.hideGui} does not exist on 26.2, and the UI world starts with the HUD
 * shown). Frame 0 has the custom crosshair ON with a fully transparent colour, so NOTHING is drawn at the centre - the
 * world alone. Pixels count as changed above a summed RGB delta of 30: the grass brightens a little between shots. Every later frame is diffed against it, so only what the crosshair changed is counted.
 * <ul>
 *   <li>Vanilla control: feature OFF must change pixels at the centre (vanilla's own crosshair) - proof this check can
 *       see a crosshair at all.</li>
 *   <li>Green cross, Screen Pixels mode (one unit = one screen pixel): length 10, thickness 2, gap 4 must be EXACTLY
 *       4 x 10 x 2 = 80 green pixels in a 30 x 30 box centred on the screen, and nothing else changed - vanilla's cross
 *       (inverted, 30 px wide here) would show as non-green changes.</li>
 *   <li>Red: the same 80 pixels, red, none green. Bigger (length 20, thickness 3): 240 pixels, a 51 x 51 box.</li>
 *   <li>T-Shape: nothing above the centre; rotated 90: nothing right of it. Dot 6: 36 pixels. Circle: a ring with an
 *       untouched centre. Colour On Block (looking at the ground): the cross turns blue.</li>
 *   <li>The editor: the Crosshair tab opens and its live preview draws the crosshair.</li>
 * </ul>
 */
final class CrosshairCases {

    private static final String CFG = "crosshair.CustomCrosshairConfig";
    private static final String STYLE = "crosshair.CustomCrosshairConfig$Style";
    private static final String MODE = "crosshair.CustomCrosshairConfig$SizeMode";
    private static final String FEATURE = "crosshair.CustomCrosshairFeature";

    private CrosshairCases() {
    }

    private static BufferedImage base;
    private static int width;
    private static int height;

    static void run(UiCase c) throws Exception {
        if (!Mod.has(CFG)) {
            c.problem("this jar has no Custom Crosshair (mod before custom-crosshair)");
            return;
        }
        Object cfg = Mod.cfg(CFG);
        boolean wasEnabled = (Boolean) Mod.call(cfg, "isEnabledRaw");
        String wasCode = (String) Mod.call(cfg, "toShareCode");
        float pitchWas = c.onClient(mc -> mc.player.getXRot());
        float yawWas = c.onClient(mc -> mc.player.getYRot());
        try {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                // The HUD stays up (the crosshair is part of it), so chat lines from earlier cases fading out
                // and toasts cycling would be counted as crosshair pixels: 26.2's first run had 8,616 "other"
                // pixels, all of them two "[AP3] ..." chat lines inside the 240 px window (2026-10-06).
                McCompat.clearChatAndToasts(mc);
                mc.player.setXRot(90f);
                mc.player.setYRot(0f);
                Mod.call(cfg, "resetLook");
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setAttackIndicator", false);
                Mod.call(cfg, "setOutline", false);
                Mod.call(cfg, "setDot", false);
                Mod.call(cfg, "setSizeMode", Mod.enumValue(MODE, "PIXELS"));
                Mod.call(cfg, "setScale", 1f);
                Mod.call(cfg, "setColor", 0x00FFFFFF);
                return null;
            });
            c.ticks(10);
            base = null;
            shot(c, "base");
            c.note("frame " + width + "x" + height + ", window " + c.onClient(mc -> mc.getWindow().getWidth() + "x"
                    + mc.getWindow().getHeight() + " GUI " + mc.getWindow().getGuiScale()));

            // Vanilla control.
            long vanillaBefore = ((Number) Mod.field(FEATURE, "vanillaFrames")).longValue();
            set(c, "setEnabled", false);
            Count vanilla = shot(c, "vanilla");
            long vanillaAfter = ((Number) Mod.field(FEATURE, "vanillaFrames")).longValue();
            c.note("vanilla (feature OFF): " + vanilla.changed + " changed px at the centre, box " + vanilla.box()
                    + "; vanilla layer frames " + vanillaBefore + " -> " + vanillaAfter);
            if (vanilla.changed < 20) {
                c.problem("with the feature OFF the centre changed by only " + vanilla.changed
                        + " px - this check cannot see vanilla's crosshair, so its suppression proves nothing");
            }
            if (vanillaAfter <= vanillaBefore) {
                c.problem("vanilla's crosshair layer was not called with the feature OFF");
            }

            // Green cross in screen pixels.
            long customBefore = ((Number) Mod.field(FEATURE, "customFrames")).longValue();
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setStyle", Mod.enumValue(STYLE, "CROSS"));
                Mod.call(cfg, "setLength", 10f);
                Mod.call(cfg, "setThickness", 2f);
                Mod.call(cfg, "setGap", 4f);
                Mod.call(cfg, "setColor", 0xFF00FF00);
                return null;
            });
            long vanillaMid = ((Number) Mod.field(FEATURE, "vanillaFrames")).longValue();
            Count green = shot(c, "green");
            long customAfter = ((Number) Mod.field(FEATURE, "customFrames")).longValue();
            long vanillaEnd = ((Number) Mod.field(FEATURE, "vanillaFrames")).longValue();
            c.note("green cross: " + green.green + " green px, " + green.other + " other changed px, box " + green.box()
                    + "; custom frames " + customBefore + " -> " + customAfter + ", vanilla frames " + vanillaMid
                    + " -> " + vanillaEnd);
            expect(c, "green cross", green.green, 80);
            expectBox(c, "green cross", green, 30, 30);
            if (green.other > 4) {
                c.problem("green cross: " + green.other + " changed pixels are not the crosshair's green - vanilla's "
                        + "crosshair (or something else) is still drawing at the centre");
            }
            if (customAfter <= customBefore) {
                c.problem("the custom crosshair layer never ran");
            }
            if (vanillaEnd != vanillaMid) {
                c.problem("vanilla's crosshair layer still ran " + (vanillaEnd - vanillaMid) + " time(s) with the "
                        + "feature ON");
            }

            set(c, "setColor", 0xFFFF0000);
            Count red = shot(c, "red");
            c.note("red cross: " + red.red + " red px, " + red.green + " green px, box " + red.box());
            expect(c, "red cross", red.red, 80);
            if (red.green > 0) {
                c.problem("red cross still has " + red.green + " green pixels - the colour change did not draw");
            }

            c.onClient(mc -> {
                Mod.call(cfg, "setOutline", true);
                Mod.call(cfg, "setOutlineThickness", 1f);
                Mod.call(cfg, "setOutlineColor", 0xFF000000);
                return null;
            });
            Count outlined = shot(c, "outline");
            c.note("red cross, 1 px black outline: " + outlined.red + " red px, " + outlined.other
                    + " other changed px, box " + outlined.box());
            expect(c, "outlined cross (red)", outlined.red, 80);
            // Each 10x2 arm grows to 12x4: 28 outline pixels an arm, four arms apart from each other.
            expect(c, "outline", outlined.other, 112);
            expectBox(c, "outlined cross", outlined, 32, 32);
            set(c, "setOutline", false);

            c.onClient(mc -> {
                Mod.call(cfg, "setLength", 20f);
                Mod.call(cfg, "setThickness", 3f);
                return null;
            });
            Count big = shot(c, "big");
            c.note("bigger red cross (length 20, thickness 3): " + big.red + " red px, box " + big.box());
            expect(c, "bigger cross", big.red, 240);
            expectBox(c, "bigger cross", big, 51, 51);

            c.onClient(mc -> {
                Mod.call(cfg, "setLength", 10f);
                Mod.call(cfg, "setThickness", 2f);
                Mod.call(cfg, "setStyle", Mod.enumValue(STYLE, "T_SHAPE"));
                return null;
            });
            Count tee = shot(c, "t-shape");
            c.note("T-Shape: " + tee.red + " red px, " + tee.above + " above the centre, box " + tee.box());
            expect(c, "T-Shape", tee.red, 60);
            if (tee.above > 0) {
                c.problem("T-Shape drew " + tee.above + " px above the centre - it has no top arm");
            }
            set(c, "setRotation", 90f);
            Count teeTurned = shot(c, "t-shape-rot90");
            c.note("T-Shape rotated 90: " + teeTurned.red + " red px, " + teeTurned.right + " right of the centre, "
                    + teeTurned.above + " above, box " + teeTurned.box());
            expect(c, "T-Shape rotated 90", teeTurned.red, 60);
            if (teeTurned.right > 0 || teeTurned.above == 0) {
                c.problem("T-Shape rotated 90 should have its missing arm on the right (right " + teeTurned.right
                        + ", above " + teeTurned.above + ")");
            }
            set(c, "setRotation", 0f);

            c.onClient(mc -> {
                Mod.call(cfg, "setStyle", Mod.enumValue(STYLE, "DOT"));
                Mod.call(cfg, "setDotSize", 6f);
                return null;
            });
            Count dot = shot(c, "dot");
            c.note("Dot 6: " + dot.red + " red px, box " + dot.box());
            expect(c, "Dot", dot.red, 36);
            expectBox(c, "Dot", dot, 6, 6);

            c.onClient(mc -> {
                Mod.call(cfg, "setStyle", Mod.enumValue(STYLE, "CIRCLE"));
                Mod.call(cfg, "setCircleRadius", 12f);
                Mod.call(cfg, "setCircleThickness", 2f);
                return null;
            });
            Count ring = shot(c, "circle");
            c.note("Circle r12 t2: " + ring.red + " red px, " + ring.centreChanged + " changed in the middle 9x9, box "
                    + ring.box());
            // Area of the annulus 11..13 is pi*(169-121) ~ 151.
            if (ring.red < 120 || ring.red > 185) {
                c.problem("Circle drew " + ring.red + " red px, expected about 151 (a 2 px ring of radius 12)");
            }
            expectBox(c, "Circle", ring, 26, 26);
            if (ring.centreChanged > 0) {
                c.problem("Circle changed " + ring.centreChanged + " px in its middle - the ring is not hollow");
            }

            // Colour On Block, aiming at the ground.
            boolean onBlock = c.onClient(mc -> mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK);
            if (onBlock) {
                c.onClient(mc -> {
                    Mod.call(cfg, "setStyle", Mod.enumValue(STYLE, "CROSS"));
                    Mod.call(cfg, "setColorOnBlock", true);
                    Mod.call(cfg, "setBlockColor", 0xFF0000FF);
                    return null;
                });
                Count blue = shot(c, "on-block");
                c.note("Colour On Block (aiming at the ground): " + blue.blue + " blue px, " + blue.red + " red px");
                expect(c, "Colour On Block", blue.blue, 80);
                set(c, "setColorOnBlock", false);
            } else {
                c.note("Colour On Block not checked: the crosshair is not on a block here");
            }

            editor(c, cfg);
        } finally {
            try {
                c.onClient(mc -> {
                    McCompat.setScreen(mc, null);
                    Mod.call(cfg, "applyShareCode", wasCode);
                    Mod.call(cfg, "setEnabled", wasEnabled);
                    Mod.call(cfg, "save");
                    mc.player.setXRot(pitchWas);
                    mc.player.setYRot(yawWas);
                    return null;
                });
            } catch (Throwable t) {
                c.note("cleanup: " + UiCase.describe(t));
            }
        }
    }

    /** The Crosshair tab in the real ModScreen: it opens with every optional row showing and none overlapping, and its
     *  preview draws the crosshair. */
    private static void editor(UiCase c, Object cfg) {
        c.onClient(mc -> {
            Mod.call(cfg, "resetLook");
            Mod.call(cfg, "setColor", 0xFF00FF00);
            Mod.call(cfg, "setStyle", Mod.enumValue(STYLE, "CROSS_CIRCLE"));
            for (String s : new String[]{"setDot", "setChroma", "setSeparateDotColor", "setOutline", "setSpreadMoving",
                    "setRecoil", "setColorOnEntity", "setColorOnBlock"}) {
                Mod.call(cfg, s, true);
            }
            return null;
        });
        Screen s = c.onClient(mc -> {
            try {
                ModScreenDriver d = new ModScreenDriver(mc);
                List<Object> tops = d.topTabs();
                for (int i = 0; i < tops.size(); i++) {
                    if ("Crosshair".equals(R.get(tops.get(i), "name"))) {
                        d.select(i);
                        McCompat.setScreen(mc, d.screen);
                        return d.screen;
                    }
                }
                return null;
            } catch (Throwable t) {
                throw new AssertionError("could not open gui.ModScreen: " + UiCase.describe(t), t);
            }
        });
        c.check(s != null, "no 'Crosshair' tab in the mod menu");
        List<String> overlaps = new java.util.ArrayList<>();
        int rows = c.onClient(mc -> {
            Object pane = R.get(s, "contentPane");
            @SuppressWarnings("unchecked")
            List<net.minecraft.client.gui.components.AbstractWidget> ws = pane == null ? List.of()
                    : new java.util.ArrayList<>((java.util.Collection<net.minecraft.client.gui.components.AbstractWidget>)
                    R.get(pane, "children"));
            for (int a = 0; a < ws.size(); a++) {
                var p = ws.get(a);
                for (int b = a + 1; b < ws.size(); b++) {
                    var q = ws.get(b);
                    if (p.getX() < q.getX() + q.getWidth() && q.getX() < p.getX() + p.getWidth()
                            && p.getY() < q.getY() + q.getHeight() && q.getY() < p.getY() + p.getHeight()) {
                        overlaps.add(ModScreenDriver.label(p) + " / " + ModScreenDriver.label(q));
                    }
                }
            }
            return ws.size();
        });
        c.note("editor with every optional row showing: " + rows + " rows, " + overlaps.size() + " overlapping pair(s)");
        if (rows < 50) {
            c.problem("the Crosshair tab built only " + rows + " rows with every option on");
        }
        for (String o : overlaps) {
            c.problem("Crosshair tab rows overlap: " + o);
        }
        c.onClient(mc -> {
            Mod.setField("crosshair.CrosshairRenderer", "lastFillCount", 0);
            return null;
        });
        c.ticks(6);
        String name = c.name() + "-editor";
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        Path kept = dev.testkit.harness.Report.screenshot(name, taken);
        int fills = ((Number) Mod.field("crosshair.CrosshairRenderer", "lastFillCount")).intValue();
        boolean open = c.onClient(mc -> McCompat.screen(mc) == s);
        c.note("editor: preview drew " + fills + " fill(s); screenshot " + (kept != null ? kept : taken));
        c.check(open, "the mod menu did not stay open on the Crosshair tab");
        if (fills <= 0) {
            c.problem("the Crosshair tab's live preview drew nothing");
        }
    }

    private static void set(UiCase c, String setter, Object value) {
        Object cfg = Mod.cfg(CFG);
        c.onClient(mc -> {
            Mod.call(cfg, setter, value);
            return null;
        });
    }

    private static void expect(UiCase c, String what, int got, int want) {
        if (got != want) {
            c.problem(what + ": " + got + " px, expected exactly " + want);
        }
    }

    private static void expectBox(UiCase c, String what, Count n, int w, int h) {
        if (n.maxX < 0) {
            c.problem(what + ": nothing drawn");
            return;
        }
        int bw = n.maxX - n.minX + 1;
        int bh = n.maxY - n.minY + 1;
        double cx = (n.minX + n.maxX + 1) / 2.0;
        double cy = (n.minY + n.maxY + 1) / 2.0;
        if (bw != w || bh != h) {
            c.problem(what + ": box " + bw + "x" + bh + ", expected " + w + "x" + h);
        }
        if (Math.abs(cx - width / 2.0) > 1.0 || Math.abs(cy - height / 2.0) > 1.0) {
            c.problem(String.format(Locale.ROOT, "%s: centred at %.1f,%.1f, screen centre %.1f,%.1f", what, cx, cy,
                    width / 2.0, height / 2.0));
        }
    }

    /** Pixels changed from the base frame within 120 px of the centre, by colour. */
    static final class Count {
        int changed, green, red, blue, other, above, right, centreChanged;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;

        String box() {
            return maxX < 0 ? "(none)" : (maxX - minX + 1) + "x" + (maxY - minY + 1) + " at " + minX + "," + minY;
        }
    }

    private static Count shot(UiCase c, String label) {
        c.ticks(6);
        String name = c.name() + "-" + label;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        Path kept = dev.testkit.harness.Report.screenshot(name, taken);
        Count n = new Count();
        try {
            BufferedImage img = javax.imageio.ImageIO.read(taken.toFile());
            if (base == null) {
                base = img;
                width = img.getWidth();
                height = img.getHeight();
                c.note(label + " -> " + (kept != null ? kept : taken));
                return n;
            }
            int cx = width / 2, cy = height / 2;
            for (int y = Math.max(0, cy - 120); y < Math.min(height, cy + 120); y++) {
                for (int x = Math.max(0, cx - 120); x < Math.min(width, cx + 120); x++) {
                    int rgb = img.getRGB(x, y);
                    int was = base.getRGB(x, y);
                    // The grass brightens by a few levels between frames as the day goes on (the first run counted
                    // 5,000 "changed" pixels at an exact compare); anything the crosshair draws is a big jump.
                    int delta = Math.abs(((rgb >> 16) & 0xFF) - ((was >> 16) & 0xFF))
                            + Math.abs(((rgb >> 8) & 0xFF) - ((was >> 8) & 0xFF)) + Math.abs((rgb & 0xFF) - (was & 0xFF));
                    if (delta <= 30) {
                        continue;
                    }
                    n.changed++;
                    int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                    if (g > 220 && r < 40 && b < 40) {
                        n.green++;
                    } else if (r > 220 && g < 40 && b < 40) {
                        n.red++;
                    } else if (b > 220 && r < 40 && g < 40) {
                        n.blue++;
                    } else {
                        n.other++;
                    }
                    n.minX = Math.min(n.minX, x);
                    n.maxX = Math.max(n.maxX, x);
                    n.minY = Math.min(n.minY, y);
                    n.maxY = Math.max(n.maxY, y);
                    if (y < cy - 3) {
                        n.above++;
                    }
                    if (x >= cx + 3) {
                        n.right++;
                    }
                    if (Math.abs(x - cx) <= 4 && Math.abs(y - cy) <= 4) {
                        n.centreChanged++;
                    }
                }
            }
        } catch (java.io.IOException e) {
            c.problem("could not read " + taken + ": " + e);
        }
        c.note(label + " -> " + (kept != null ? kept : taken));
        return n;
    }
}

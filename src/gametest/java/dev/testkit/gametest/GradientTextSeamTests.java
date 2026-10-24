package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 1436-ui-gradient-text-seam: no line across the mod's gradient text at any GUI scale (mod deuces-door-2-1017; killer560,
 * 2026-10-17: "there is a line through Full Toggle. I have seen this before, make sure it is fixed modwide").
 *
 * <p>{@code MenuStyle.gradientText} is the one place the mod draws gradient text (the Profiles tab's active profile name
 * at 1.5x, the menu's brand at 4/3, ScreenChrome titles and the Profile Viewer's at 1x - every call site goes through it). It
 * draws the text in three horizontal scissor bands of color. Scissor edges round to whole pixels and a scaled band edge
 * does not land on one, so a one-pixel row between two bands could draw nothing and show the shadow underneath: a dark
 * line across the glyphs.
 *
 * <p>A screen draws each site's exact call (text, scale) on a flat gray background, plain and again inside an extra
 * 1.25x pose (the menu's HUD Scale draws the whole menu scaled), at GUI scales 1, 2, 3 and 4. Beside each, a CONTROL: the
 * same text at the same pose, shadow then one plain color, with no scissor. Every pixel row of the gradient copy must
 * hold about as many lit glyph pixels as the control's same row - a seam is a row that is lit above and below and empty in
 * between. Proof the feature drew: every gradient copy has lit pixels, and its top rows are lighter than its bottom rows
 * (the gradient). Screenshots in {@code killer560s-mod-logs/deuces-door-2/gradient-text/}.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class GradientTextSeamTests implements FabricClientGameTest {

    private static final String NAME = "1436-ui-gradient-text-seam";
    private static final String MENU_STYLE = "com.killer560.hub.gui.MenuStyle";
    private static final Path SHOTS = Path.of(System.getProperty("user.home"), "killer560s-mod-logs", "deuces-door-2",
            "gradient-text");
    /** BRAND_TOP / BRAND_BOTTOM of the default (Amber) theme, passed explicitly so the theme cannot change the test. */
    private static final int TOP = 0xFFFFB15A;
    private static final int BOTTOM = 0xFFFF7A0A;
    private static final int BG = 0xFF303030;
    /** Each site's call: text and scale. */
    private static final Object[][] SITES = {
            {"Full Toggle", 1.5f},          // ProfilesTab.paintHeader, the active profile
            {"Killer560's Mod", 4f / 3f},   // ModScreen's brand
            {"Profile Viewer", 1f},         // ProfileViewerScreen's title
            {"Storage Search", 1f},         // ScreenChrome titles
            {"Full Toggle", 2f},
    };
    /** The extra pose round each draw: none, and the menu's HUD Scale. Control x offset is integral at every one. */
    private static final float[] OUTER = {1f, 1.25f};
    private static final int CONTROL_DX = 160;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        List<String> failures = new ArrayList<>();
        int oldGui = ctx.computeOnClient(mc -> mc.options.guiScale().get());
        int[] oldWindow = ctx.computeOnClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        int casesChecked = 0;
        try {
            ctx.waitTicks(20);
            ctx.getInput().resizeWindow(1920, 1080);
            ctx.waitTicks(10);
            Files.createDirectories(SHOTS);
            Method draw = Class.forName(MENU_STYLE).getMethod("gradientText", GuiGraphicsExtractor.class, String.class,
                    int.class, int.class, float.class, int.class, int.class);
            boolean shadow = (Boolean) Class.forName(MENU_STYLE).getField("TEXT_SHADOW").get(null);
            for (int gui = 1; gui <= 4; gui++) {
                final int g = gui;
                List<int[]> boxes = new ArrayList<>();   // {x, y, w, h} in GUI units, the gradient copy; control at +CONTROL_DX
                Canvas canvas = ctx.computeOnClient(mc -> {
                    mc.options.guiScale().set(g);
                    mc.resizeGui();
                    Canvas c = new Canvas((gfx, scr) -> paint(gfx, scr, draw, shadow, null));
                    McCompat.setScreen(mc, c);
                    return c;
                });
                ctx.waitTicks(5);
                ctx.runOnClient(mc -> paint(null, canvas, draw, shadow, boxes));
                ctx.waitTicks(3);
                Path shot = ctx.takeScreenshot(dev.testkit.harness.Report.fileName(NAME + "-gui" + g));
                Files.copy(shot, SHOTS.resolve("gui" + g + ".png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                BufferedImage img = javax.imageio.ImageIO.read(shot.toFile());
                int guiW = ctx.computeOnClient(mc -> canvas.width);
                double px = img.getWidth() / (double) guiW;
                int k = 0;
                for (int[] b : boxes) {
                    String label = "GUI " + g + ", " + SITES[k / OUTER.length][0] + " at " + SITES[k / OUTER.length][1]
                            + " in " + OUTER[k % OUTER.length] + "x";
                    k++;
                    String why = judge(img, px, b, label);
                    casesChecked++;
                    if (why != null) {
                        failures.add(why);
                    }
                }
            }
            System.out.println("[" + NAME + "] " + casesChecked + " draw(s) judged, screenshots in " + SHOTS);
            if (casesChecked < 4 * SITES.length * OUTER.length) {
                failures.add("premise: only " + casesChecked + " draw(s) judged");
            }
        } catch (Throwable t) {
            failures.add("threw: " + t);
            t.printStackTrace();
        } finally {
            ctx.runOnClient(mc -> {
                McCompat.setScreen(mc, null);
                mc.options.guiScale().set(oldGui);
            });
            ctx.getInput().resizeWindow(oldWindow[0], oldWindow[1]);
            ctx.waitTicks(5);
            ctx.runOnClient(mc -> mc.resizeGui());
            if (failures.isEmpty()) {
                System.out.println("[" + NAME + "] PASS - no row across the gradient text is darker than the control's");
            } else {
                String why = String.join("; ", failures);
                System.out.println("[" + NAME + "] FAIL: " + why);
                SuiteVerdict.failCase(NAME, new AssertionError(why));
            }
        }
    }

    /**
     * Draws every case (gfx non-null), or only lays them out into {@code boxes} (gfx null). Rows down the screen, the
     * gradient copy at x 6 and its control CONTROL_DX GUI units to the right, inside the outer pose.
     */
    private static void paint(GuiGraphicsExtractor gfx, Canvas scr, Method draw, boolean shadow, List<int[]> boxes) {
        int y = 4;
        for (Object[] site : SITES) {
            String s = (String) site[0];
            float scale = (Float) site[1];
            for (float outer : OUTER) {
                int x = 6;
                int yy = Math.round(y / outer);
                if (gfx != null) {
                    gfx.pose().pushMatrix();
                    gfx.pose().scale(outer, outer);
                    try {
                        draw.invoke(null, gfx, s, x, yy, scale, TOP, BOTTOM);
                    } catch (ReflectiveOperationException e) {
                        throw new RuntimeException(e);
                    }
                    // The control: the same text through the same pose, no scissor.
                    gfx.pose().pushMatrix();
                    gfx.pose().translate(x + CONTROL_DX, yy);
                    gfx.pose().scale(scale, scale);
                    if (shadow) {
                        gfx.text(scr.font(), s, 1, 1, 0xFF000000, false);
                    }
                    gfx.text(scr.font(), s, 0, 0, 0xFFFF9A30, false);
                    gfx.pose().popMatrix();
                    gfx.pose().popMatrix();
                }
                if (boxes != null) {
                    int w = scr.font().width(s);
                    // GUI-unit box of the gradient copy in screen space: origin (x, yy) scaled by outer.
                    boxes.add(new int[]{Math.round(x * outer * 1000), Math.round(yy * outer * 1000),
                            Math.round((w + 2) * scale * outer * 1000), Math.round(10 * scale * outer * 1000),
                            Math.round(CONTROL_DX * outer * 1000)});
                }
                y += (int) Math.ceil(10 * scale * outer) + 3;
            }
        }
    }

    /** Lit glyph pixel: the brand oranges and the control's (red channel high, well above the gray background). */
    private static boolean lit(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        return r > 170 && g > 70;
    }

    /** Null when the gradient copy's rows match the control's; otherwise why not. Box values are GUI units x 1000. */
    private static String judge(BufferedImage img, double px, int[] b, String label) {
        int x0 = (int) Math.floor(b[0] / 1000.0 * px) - 1;
        int y0 = (int) Math.floor(b[1] / 1000.0 * px) - 1;
        int x1 = (int) Math.ceil((b[0] + b[2]) / 1000.0 * px) + 1;
        int y1 = (int) Math.ceil((b[1] + b[3]) / 1000.0 * px) + 1;
        int dx = (int) Math.round(b[4] / 1000.0 * px);
        if (x1 + dx >= img.getWidth() || y1 >= img.getHeight() || x0 < 0 || y0 < 0) {
            return label + ": premise - the draw does not fit the screenshot";
        }
        int rows = y1 - y0;
        int[] grad = new int[rows];
        int[] ctrl = new int[rows];
        long gTop = 0;
        long nTop = 0;
        long gBottom = 0;
        long nBottom = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int a = img.getRGB(x, y);
                if (lit(a)) {
                    grad[y - y0]++;
                }
                if (lit(img.getRGB(x + dx, y))) {
                    ctrl[y - y0]++;
                }
            }
        }
        int first = -1;
        int last = -1;
        int total = 0;
        for (int i = 0; i < rows; i++) {
            if (ctrl[i] > 0) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
            total += grad[i];
        }
        if (first < 0 || total == 0) {
            return label + ": premise - nothing lit (gradient " + total + " px, control rows " + first + ")";
        }
        // The gradient: green channel of lit pixels in the top third against the bottom third.
        int third = Math.max(1, (last - first + 1) / 3);
        for (int y = y0 + first; y <= y0 + last; y++) {
            for (int x = x0; x < x1; x++) {
                int a = img.getRGB(x, y);
                if (!lit(a)) {
                    continue;
                }
                int gch = (a >> 8) & 0xFF;
                if (y < y0 + first + third) {
                    gTop += gch;
                    nTop++;
                } else if (y > y0 + last - third) {
                    gBottom += gch;
                    nBottom++;
                }
            }
        }
        if (nTop == 0 || nBottom == 0 || gTop / (double) nTop <= gBottom / (double) nBottom + 10) {
            return label + ": premise - no top-to-bottom gradient (top G " + (nTop == 0 ? -1 : gTop / nTop) + ", bottom G "
                    + (nBottom == 0 ? -1 : gBottom / nBottom) + ")";
        }
        StringBuilder bad = new StringBuilder();
        // A seam: a run of one or more rows the control lights and the gradient copy (nearly) does not, between rows where
        // they agree. At GUI 4 a rounded band edge is several pixels tall, so a run, not a single row.
        int i = first;
        while (i <= last) {
            boolean low = ctrl[i] >= 3 && grad[i] * 2 < ctrl[i];
            if (!low) {
                i++;
                continue;
            }
            int j = i;
            while (j + 1 <= last && ctrl[j + 1] >= 3 && grad[j + 1] * 2 < ctrl[j + 1]) {
                j++;
            }
            boolean bounded = i > first && j < last && grad[i - 1] * 2 >= ctrl[i - 1] && grad[j + 1] * 2 >= ctrl[j + 1];
            if (bounded) {
                bad.append(" rows ").append(i - first).append("..").append(j - first).append(" (").append(grad[i])
                        .append(" lit vs control ").append(ctrl[i]).append(')');
            }
            i = j + 1;
        }
        System.out.println("[" + NAME + "] " + label + ": rows " + (last - first + 1) + ", lit per row gradient "
                + java.util.Arrays.toString(java.util.Arrays.copyOfRange(grad, first, last + 1)) + " control "
                + java.util.Arrays.toString(java.util.Arrays.copyOfRange(ctrl, first, last + 1)));
        return bad.length() == 0 ? null : label + ": a darker row across the glyphs at" + bad;
    }

    static final class Canvas extends Screen {
        private final java.util.function.BiConsumer<GuiGraphicsExtractor, Canvas> body;

        Canvas(java.util.function.BiConsumer<GuiGraphicsExtractor, Canvas> body) {
            super(Component.literal("gradient seam"));
            this.body = body;
        }

        net.minecraft.client.gui.Font font() {
            return this.font;
        }

        @Override
        public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            g.fill(0, 0, this.width, this.height, BG);
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            super.extractRenderState(g, mouseX, mouseY, delta);
            body.accept(g, this);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}

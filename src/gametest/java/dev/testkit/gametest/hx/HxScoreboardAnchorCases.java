package dev.testkit.gametest.hx;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.util.FormattedCharSequence;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;
import static dev.testkit.gametest.hx.HxScoreboardCases.*;

/**
 * Custom Scoreboard: the anchored wall never moves (mod branch scoreboard-anchor-1015, killer560 2026-10-15: "have it
 * sense what side of my screen it is on. If it is on the right side then have text generate such that the furthest right
 * point of any line of text is anchored, so the right wall will never be moved. On the left it should be the left side.").
 * 1668-1670, on the Hx server's real sidebar:
 * <ul>
 *   <li>1668 in game: for each side (right, left), each Text Align (Left, Center, Right) and each HUD scale (1.0 and a
 *       non-integer 0.9), plus a Min Width wider than the text, the board is drawn through five sidebar states (purse
 *       999,999, 1,000,000, 12,345,678,901 with a longer area name, a longer Bits line, and that line gone with a Show
 *       Number Changes popup). The box's anchored edge and the outermost text point on that side are measured from the
 *       layer's own render state, in GUI units, and must not move by more than 0.01 between states; the box edge must sit
 *       exactly {@code gap} from the screen edge. With chat open, the hover layout must find the widest line under its
 *       own right (or left) end. At GUI 2 and GUI 3.</li>
 *   <li>1669 the Edit HUD preview, drawn the way the editor draws it, holds the same edge through the same states.</li>
 *   <li>1670 the side is sensed: a drop in the right half anchors right, the left half left, either side of the middle
 *       picks the nearer edge; an old saved position near the left edge converts to a left anchor and one near the right
 *       to a right anchor.</li>
 * </ul>
 */
final class HxScoreboardAnchorCases {

    private static final String CFG = "scoreboard.CustomScoreboardConfig";
    private static final String FEATURE = "scoreboard.CustomScoreboardFeature";
    private static final String ID = "custom_scoreboard";
    private static final int GAP = 20;
    private static final double EPS = 0.01;

    private HxScoreboardAnchorCases() {
    }

    static void register(Session s) {
        s.test("1668-hx-sb-anchor-edges", c -> edges(c, false));
        s.test("1669-hx-sb-anchor-preview", c -> edges(c, true));
        s.test("1670-hx-sb-anchor-side", HxScoreboardAnchorCases::side);
    }

    // ---- measuring --------------------------------------------------------------------------------------------------

    /** {bgLeft, bgRight, textLeft, textRight, screenW} in GUI units, of the board as the in-game layer (or, with
     *  {@code preview}, the Edit HUD preview at the editor's transform) draws it now. Client thread. */
    static double[] measure(Minecraft mc, boolean preview) {
        GuiRenderState state = new GuiRenderState();
        GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
        if (preview) {
            Object el = element();
            int[] pos = (int[]) Mod.staticCall("hud.HudElementRegistry", "resolvePosition", el);
            float scale = ((Number) Mod.staticCall("hud.HudElementRegistry", "resolveScale", el)).floatValue();
            g.pose().pushMatrix();
            try {
                g.pose().translate(pos[0], pos[1]);
                g.pose().scale(scale, scale);
                Mod.call(el, "render", g, 0, 0);
            } finally {
                g.pose().popMatrix();
            }
        } else {
            Mod.staticCall(FEATURE, "drawInGame", g);
        }
        double[] bg = {Double.NaN, Double.NaN};
        long[] bestArea = {-1};
        state.forEachElement(el -> {
            ScreenRectangle r = el.bounds();
            if (r == null) {
                return;
            }
            long area = (long) r.width() * r.height();
            if (area > bestArea[0]) {
                bestArea[0] = area;
                bg[0] = r.left();
                bg[1] = r.right();
            }
        }, GuiRenderState.TraverseRange.ALL);
        double[] text = {Double.MAX_VALUE, -Double.MAX_VALUE};
        state.forEachText(t -> {
            float[] lr = textLR(t);
            if (lr == null) {
                return;
            }
            text[0] = Math.min(text[0], lr[0]);
            text[1] = Math.max(text[1], lr[1]);
        });
        return new double[]{bg[0], bg[1], text[0], text[1], mc.getWindow().getGuiScaledWidth()};
    }

    /** A text run's advance box left/right through its pose, unrounded; null for a blank run. */
    private static float[] textLR(GuiTextRenderState t) {
        Font font = (Font) get(t, "font");
        FormattedCharSequence seq = (FormattedCharSequence) get(t, "text");
        int w = font.width(seq);
        if (w <= 0) {
            return null;
        }
        int x = (Integer) get(t, "x");
        int y = (Integer) get(t, "y");
        org.joml.Vector2f a = t.pose.transformPosition(new org.joml.Vector2f(x, y));
        org.joml.Vector2f b = t.pose.transformPosition(new org.joml.Vector2f(x + w, y));
        return new float[]{Math.min(a.x, b.x), Math.max(a.x, b.x)};
    }

    private static Object get(GuiTextRenderState t, String name) {
        try {
            Field f = GuiTextRenderState.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(t);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // ---- states -----------------------------------------------------------------------------------------------------

    private static final String[][] STATES = {
            {"purse 999,999", "999,999", "Village", ""},
            {"purse 1,000,000", "1,000,000", "Village", ""},
            {"purse 12,345,678,901, long area", "12,345,678,901", "Colosseum Arena Long", ""},
            {"a longer Bits line appears", "12,345,678,901", "Colosseum Arena Long", "Bits: §b1,234,567,890,123"},
            {"Bits line gone, purse +1", "12,345,678,902", "Village", ""},
    };

    private static void apply(Session c, String[] st) {
        List<String> extra = new ArrayList<>();
        extra.add("Purse: §6" + st[1]);
        if (!st[3].isEmpty()) {
            extra.add(st[3]);
        }
        sidebar(c, "template", "hub", "area", st[2], "extra", extra);
        c.waitUntil("the board shows " + st[0], mc -> String.valueOf(Mod.staticCall(FEATURE, "currentTexts")).contains(st[1])
                && (st[3].isEmpty() || String.valueOf(Mod.staticCall(FEATURE, "currentTexts")).contains("1,234,567,890,123")), 100);
        c.ctx().waitTicks(2);
    }

    private static void configure(Session c, String side, String align, float scale, int minWidth) {
        c.onClient(mc -> {
            Object cfg = Mod.cfg(CFG);
            Mod.call(cfg, "setAnchor", Mod.enumValue(CFG + "$Side", side), GAP, 0);
            Mod.call(cfg, "setTextAlignment", Mod.enumValue(CFG + "$Align", align));
            Mod.call(cfg, "setMinWidth", minWidth);
            Mod.call(cfg, "setShowNumberDifference", true);
            Mod.call(cfg, "save");
            Object hud = Mod.staticCall("hud.HudConfig", "getInstance");
            Mod.call(hud, "setScale", ID, scale);
            return null;
        });
    }

    // ---- 1668 / 1669 ------------------------------------------------------------------------------------------------

    private static void edges(Session c, boolean preview) {
        try (Saved saved = new Saved(c)) {
            skyblock(c);
            enable(c);
            List<String> bad = new ArrayList<>();
            int combos = 0;
            int hoverChecks = 0;
            for (int gui : new int[]{2, 3}) {
                setWindow(c, 1366, 768, gui);
                for (String side : new String[]{"RIGHT", "LEFT"}) {
                    for (Object[] v : new Object[][]{{"LEFT", 1.0f, 0}, {"CENTER", 1.0f, 0}, {"RIGHT", 1.0f, 0},
                            {"LEFT", 0.9f, 0}, {"CENTER", 0.9f, 0}, {"LEFT", 1.0f, 260}, {"RIGHT", 0.9f, 260}}) {
                        String align = (String) v[0];
                        float scale = (Float) v[1];
                        int minW = (Integer) v[2];
                        if (gui == 3 && minW > 0) {
                            continue; // a 260-wide minimum does not fit beside the GUI 3 screen's own width test
                        }
                        configure(c, side, align, scale, minW);
                        String combo = "GUI " + gui + " " + side + " anchor, Text Align " + align + ", scale " + scale
                                + (minW > 0 ? ", Min Width " + minW : "");
                        boolean right = side.equals("RIGHT");
                        Double boxEdge = null;
                        Double textEdge = null;
                        StringBuilder trace = new StringBuilder();
                        for (String[] st : STATES) {
                            apply(c, st);
                            if (preview) {
                                c.onClient(mc -> {
                                    if (!(McCompat.screen(mc) != null && McCompat.screen(mc).getClass().getSimpleName().equals("HudEditorScreen"))) {
                                        try {
                                            McCompat.setScreen(mc, (Screen) Mod.cls("hud.HudEditorScreen")
                                                    .getConstructor(Screen.class).newInstance((Object) null));
                                        } catch (ReflectiveOperationException e) {
                                            throw new AssertionError(e);
                                        }
                                    }
                                    return null;
                                });
                                c.ctx().waitTicks(2);
                            }
                            double[] m = c.onClient(mc -> measure(mc, preview));
                            double box = right ? m[1] : m[0];
                            double text = right ? m[3] : m[2];
                            trace.append(String.format(" [%s: box %.2f..%.2f text %.3f..%.3f]", st[0], m[0], m[1], m[2], m[3]));
                            if (Double.isNaN(box) || m[3] < m[2]) {
                                bad.add(combo + ", " + st[0] + ": nothing drawn");
                                continue;
                            }
                            double expect = right ? m[4] - GAP : GAP;
                            if (Math.abs(box - expect) > EPS) {
                                bad.add(String.format("%s, %s: box edge %.2f, want %.2f", combo, st[0], box, expect));
                            }
                            if (boxEdge != null && Math.abs(box - boxEdge) > EPS) {
                                bad.add(String.format("%s, %s: box edge moved %.3f -> %.3f", combo, st[0], boxEdge, box));
                            }
                            if (textEdge != null && Math.abs(text - textEdge) > EPS) {
                                bad.add(String.format("%s, %s: outermost text point moved %.3f -> %.3f", combo, st[0],
                                        textEdge, text));
                            }
                            boxEdge = box;
                            textEdge = text;
                        }
                        if (preview) {
                            c.onClient(mc -> {
                                McCompat.setScreen(mc, null);
                                return null;
                            });
                        } else if (scale != 1.0f && align.equals("LEFT") && minW == 0) {
                            // Hover with chat open: the widest line is found under its own outer end.
                            String hit = c.onClient(mc -> hoverAtOuterEnd(mc, right));
                            hoverChecks++;
                            if (hit == null || !hit.startsWith("ok")) {
                                bad.add(combo + ": hover layout " + hit);
                            }
                        }
                        combos++;
                        c.note(combo + ":" + trace);
                    }
                }
            }
            c.note(combos + " combinations x " + STATES.length + " states, " + hoverChecks + " hover checks; problems "
                    + bad.size());
            c.check(combos == 24, "only " + combos + " of 24 combinations ran");
            c.check(preview || hoverChecks == 4, "only " + hoverChecks + " hover checks ran");
            c.check(bad.isEmpty(), String.join("; ", bad.subList(0, Math.min(12, bad.size())))
                    + (bad.size() > 12 ? " ... (" + bad.size() + " in all)" : ""));
        }
    }

    /** Opens chat, draws the layer, and asks its hover hit test for the line at the widest text run's outer end. */
    private static String hoverAtOuterEnd(Minecraft mc, boolean right) {
        McCompat.setScreen(mc, new net.minecraft.client.gui.screens.ChatScreen("", false));
        try {
            GuiRenderState state = new GuiRenderState();
            GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
            Mod.staticCall(FEATURE, "drawInGame", g);
            Object layout = Mod.field(FEATURE, "lastLayout");
            if (layout == null) {
                return "no layout with chat open";
            }
            float[] best = null;
            String bestText = null;
            List<GuiTextRenderState> runs = new ArrayList<>();
            state.forEachText(runs::add);
            for (GuiTextRenderState t : runs) {
                float[] lr = textLR(t);
                if (lr == null) {
                    continue;
                }
                if (best == null || (right ? lr[1] > best[1] : lr[0] < best[0])) {
                    best = lr;
                    bestText = dev.testkit.gametest.TextRuns.string(t);
                    org.joml.Vector2f top = t.pose.transformPosition(new org.joml.Vector2f(0, (Integer) get(t, "y")));
                    best = new float[]{lr[0], lr[1], top.y};
                }
            }
            if (best == null) {
                return "no text";
            }
            double mx = right ? best[1] - 1.5 : best[0] + 1.5;
            double my = best[2] + 3;
            Object line = Mod.staticCall(FEATURE, "lineAt", layout, mx, my);
            // The widest run can be the line's own number-change popup, drawn as a run after its text.
            String got = line == null ? null : Mod.call(line, "text") + (Mod.call(line, "popup") == null ? ""
                    : String.valueOf(Mod.call(line, "popup")));
            String plain = got == null ? null : net.minecraft.ChatFormatting.stripFormatting(got);
            return plain != null && bestText != null && plain.contains(bestText.trim()) ? "ok " + plain
                    : "at " + mx + "," + my + " found '" + plain + "', widest run is '" + bestText + "'";
        } finally {
            McCompat.setScreen(mc, null);
        }
    }

    // ---- 1670 -------------------------------------------------------------------------------------------------------

    private static String anchor(Minecraft mc) {
        Object cfg = Mod.cfg(CFG);
        return Mod.call(cfg, "getAnchorSide") + " gap " + Mod.call(cfg, "getEdgeGap");
    }

    private static void side(Session c) {
        try (Saved saved = new Saved(c)) {
            skyblock(c);
            enable(c);
            setWindow(c, 1366, 768, 2);
            waitDrawn(c, "side sensing");
            int[] b = c.onClient(HxScoreboardCases::box);
            int sw = b[4];
            int sh = b[5];
            int w = b[2];
            int h = b[3];
            List<String> bad = new ArrayList<>();
            // {x of the drop, expected side}
            int mid = (sw - w) / 2;
            int[][] drops = {{sw - w - 5, 1}, {5, 0}, {mid + 3, 1}, {mid - 3, 0}, {sw / 2 + 10, 1}, {sw / 2 - w - 10, 0}};
            for (int[] d : drops) {
                String got = c.onClient(mc -> {
                    Mod.call(element(), "storeEditorPosition", d[0], 50, w, h, sw, sh);
                    return anchor(mc);
                });
                String want = d[1] == 1 ? "RIGHT" : "LEFT";
                int nearGap = d[1] == 1 ? sw - d[0] - w : d[0];
                c.note("drop at x " + d[0] + " (box " + w + " wide on " + sw + "): " + got);
                if (!got.equals(want + " gap " + nearGap)) {
                    bad.add("drop at x " + d[0] + " gave " + got + ", want " + want + " gap " + nearGap);
                }
            }
            // Old saved positions (snapping off, HudConfig x; Auto Scale off so x is used as it is).
            for (int[] old : new int[][]{{12, 0}, {sw - w + 30, 1}, {sw / 2 - w + 20, 0}}) {
                String json = "{\"enabled\": true, \"horizontalSnap\": \"NONE\", \"verticalSnap\": \"NONE\", \"margin\": 0}";
                String got = c.onClient(mc -> {
                    write((Path) Mod.field(CFG, "CONFIG_PATH"), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    Object hud = Mod.staticCall("hud.HudConfig", "getInstance");
                    Mod.call(hud, "setAutoScale", false);
                    Mod.call(hud, "setPosition", ID, old[0], 60);
                    Mod.staticCall(CFG, "load");
                    return null;
                }) == null ? null : null;
                waitDrawn(c, "old position x " + old[0]);
                got = c.onClient(HxScoreboardAnchorCases::anchor);
                c.note("old saved x " + old[0] + ": " + got);
                String want = old[1] == 1 ? "RIGHT" : "LEFT";
                if (!got.startsWith(want)) {
                    bad.add("old saved x " + old[0] + " converted to " + got + ", want " + want);
                }
            }
            c.check(bad.isEmpty(), String.join("; ", bad));
        }
    }
}

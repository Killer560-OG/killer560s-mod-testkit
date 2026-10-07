package dev.testkit.gametest;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.util.FormattedCharSequence;

import java.lang.reflect.Field;

/**
 * A recorded text run's string and footprint. {@code GuiTextRenderState}'s font, text, x and y are public on 26.1.2
 * and private on 26.2 (javap, 2026-10-07), so they are read by name here for both.
 */
public final class TextRuns {

    private TextRuns() {
    }

    /** The run's plain text (formatting codes already resolved into styles, so not in the string). */
    public static String string(GuiTextRenderState t) {
        FormattedCharSequence seq = (FormattedCharSequence) get(t, "text");
        StringBuilder sb = new StringBuilder();
        seq.accept((index, style, codePoint) -> {
            sb.appendCodePoint(codePoint);
            return true;
        });
        return sb.toString();
    }

    /**
     * The run's advance box, {@code font.width(text)} by {@code font.lineHeight}, through its pose.
     *
     * <p>Not {@code bounds()}: that is the union of the glyph QUADS, which overhang the pixels they draw - 2 to 3
     * units right of the advance on every text HUD element measured, shadow or not, and 6 on one - so every
     * correctly sized text box read as spilling past its edge (390-ui-hud-boxes, 2026-10-07). The advance includes
     * the one-pixel gap after the last glyph, which is where a drop shadow lands.
     */
    public static ScreenRectangle box(GuiTextRenderState t) {
        Font font = (Font) get(t, "font");
        FormattedCharSequence seq = (FormattedCharSequence) get(t, "text");
        int x = (Integer) get(t, "x");
        int y = (Integer) get(t, "y");
        int w = font.width(seq);
        int h = font.lineHeight;
        org.joml.Vector2f a = t.pose.transformPosition(new org.joml.Vector2f(x, y));
        org.joml.Vector2f c = t.pose.transformPosition(new org.joml.Vector2f(x + w, y + h));
        int x0 = (int) Math.floor(Math.min(a.x, c.x));
        int y0 = (int) Math.floor(Math.min(a.y, c.y));
        int x1 = (int) Math.ceil(Math.max(a.x, c.x));
        int y1 = (int) Math.ceil(Math.max(a.y, c.y));
        return new ScreenRectangle(x0, y0, x1 - x0, y1 - y0);
    }

    private static Object get(GuiTextRenderState t, String name) {
        try {
            Field f = GuiTextRenderState.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(t);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("GuiTextRenderState has no field " + name, e);
        }
    }
}

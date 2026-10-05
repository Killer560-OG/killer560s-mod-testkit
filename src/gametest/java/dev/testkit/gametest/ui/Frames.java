package dev.testkit.gametest.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

import java.util.ArrayList;
import java.util.List;

/**
 * Off-screen frames: a screen's whole extract pass (where every line of a mod screen's drawing code runs) into a
 * throwaway {@link GuiRenderState}, inside a try. On screen, a screen that throws while rendering takes the client
 * down with a crash report and every case after it is lost; off screen the throw is a problem in one case's report.
 * A case extracts first and only puts a screen on the real window (real frames) once its extract was clean.
 *
 * <p>The result counts what was drawn, so "rendered" is a measurement: a screen that drew nothing is not a pass.
 * Call on the client thread.
 */
public final class Frames {

    /** What one extract produced. */
    public record Drawn(int elements, int texts, int items) {
        public int total() {
            return elements + texts + items;
        }
    }

    private Frames() {
    }

    /** Run {@code screen}'s full extract (background, widgets, tooltip) once with the mouse at (mx, my). */
    public static Drawn extract(Minecraft mc, Screen screen, int mx, int my) {
        GuiRenderState state = new GuiRenderState();
        GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, mx, my);
        screen.extractRenderStateWithTooltipAndSubtitles(g, mx, my, 0.5f);
        int[] n = new int[3];
        state.forEachElement(e -> n[0]++, GuiRenderState.TraverseRange.ALL);
        state.forEachText(t -> n[1]++);
        state.forEachItem(i -> n[2]++);
        return new Drawn(n[0], n[1], n[2]);
    }

    /**
     * {@code count} extracts: the first with the mouse off every widget, then one over the centre of each of the
     * first visible widgets in turn (hover states and tooltips run too). Returns the smallest frame drawn.
     */
    public static Drawn extractFrames(Minecraft mc, Screen screen, int count) {
        List<int[]> points = new ArrayList<>();
        points.add(new int[]{-1, -1});
        for (AbstractWidget w : widgets(screen)) {
            if (points.size() >= count) {
                break;
            }
            if (w.visible) {
                points.add(new int[]{w.getX() + w.getWidth() / 2, w.getY() + w.getHeight() / 2});
            }
        }
        while (points.size() < count) {
            points.add(new int[]{screen.width / 2, screen.height / 2});
        }
        Drawn least = null;
        for (int[] p : points) {
            Drawn d = extract(mc, screen, p[0], p[1]);
            if (least == null || d.total() < least.total()) {
                least = d;
            }
        }
        return least;
    }

    /** Every AbstractWidget among the screen's children, descending into container widgets. */
    public static List<AbstractWidget> widgets(Screen screen) {
        List<AbstractWidget> out = new ArrayList<>();
        collect(screen.children(), out, 0);
        return out;
    }

    private static void collect(List<? extends GuiEventListener> children, List<AbstractWidget> out, int depth) {
        if (depth > 6) {
            return;
        }
        for (GuiEventListener child : children) {
            if (child instanceof AbstractWidget w) {
                out.add(w);
            }
            if (child instanceof net.minecraft.client.gui.components.events.ContainerEventHandler c) {
                collect(c.children(), out, depth + 1);
            }
        }
    }
}

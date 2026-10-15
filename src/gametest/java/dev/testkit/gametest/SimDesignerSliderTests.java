package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;

/**
 * 75-sim-designer-sliders: Design a Map's Rooms to blood and Puzzles sliders stop at what the floor can hold (mod
 * sim-designer-1015).
 *
 * <p>Coordinator (2026-10-15): "limit the Rooms-to-blood and Puzzles sliders per floor to what that floor's real size can
 * hold (clamp on floor change, slider max follows the floor), so nothing silently under-delivers".
 *
 * <ol>
 *   <li>On Floor 7 both sliders are set to their tops with the real mouse (8 and 5); the real floor button then steps
 *       through every floor, and on each the two values must be within the sliders' new tops, the tops must be the
 *       floor's (read from the widgets), and Puzzles' top must be the floor's limit at the Rooms to blood now set.</li>
 *   <li>On every floor, Generate (real click) at the sliders' tops - the longest path with the most puzzles that floor
 *       allows - and at Rooms to blood 2 with its top Puzzles, 3 floors each: every plan must have exactly the path and
 *       exactly the puzzles the sliders say. That is the "nothing under-delivers" half. (On main it passes only because
 *       main's small floors were not held to their real size; there the tops in step 1 are 8 and 5 everywhere.)</li>
 *   <li>On Entrance, Rooms to blood is moved with the mouse from 2 to its top: Puzzles' top must follow it down and a
 *       Puzzles value above the new top must come down with it.</li>
 * </ol>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimDesignerSliderTests implements FabricClientGameTest {

    private static final String NAME = "75-sim-designer-sliders";
    private static final String SLIDER = "com.killer560.hub.roomsim.SimSlider";

    private final List<String> failures = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        DesignerKit.prepare(ctx);
        try {
            run(ctx);
        } finally {
            DesignerKit.closeScreen(ctx);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s):\n  " + String.join("\n  ", failures));
        }
        println("PASS");
    }

    private void run(ClientGameTestContext ctx) {
        DesignerKit.openEditor(ctx);
        check("Floor 7".equals(DesignerKit.floorLabel(ctx)), "the designer did not open on Floor 7");
        // ---- 1. tops on F7, then every floor ---------------------------------------------------------------------
        setSlider(ctx, "Rooms to blood", top(ctx, "Rooms to blood"));
        setSlider(ctx, "Puzzles", top(ctx, "Puzzles"));
        println("Floor 7 tops: Rooms to blood " + value(ctx, "Rooms to blood") + "/" + top(ctx, "Rooms to blood")
                + ", Puzzles " + value(ctx, "Puzzles") + "/" + top(ctx, "Puzzles"));
        check(value(ctx, "Rooms to blood") == 8 && value(ctx, "Puzzles") == 5,
                "could not set Floor 7's sliders to 8 and 5 with the mouse");
        for (int i = 0; i < DesignerKit.FLOOR_LABELS.length; i++) {
            DesignerKit.click(ctx, DesignerKit.floorLabel(ctx));
            String now = DesignerKit.floorLabel(ctx);
            int idx = indexOf(now);
            int blood = value(ctx, "Rooms to blood");
            int puzzles = value(ctx, "Puzzles");
            int bloodTop = top(ctx, "Rooms to blood");
            int puzzlesTop = top(ctx, "Puzzles");
            int wantBloodTop = maxBlood(idx);
            println(String.format("%s: Rooms to blood %d (top %d, want %d), Puzzles %d (top %d, want %d)", now, blood,
                    bloodTop, wantBloodTop, puzzles, puzzlesTop, maxPuzzles(idx, blood)));
            check(bloodTop == wantBloodTop, now + ": Rooms to blood stops at " + bloodTop + ", not " + wantBloodTop);
            check(puzzlesTop == maxPuzzles(idx, blood), now + ": Puzzles stops at " + puzzlesTop + " with Rooms to blood "
                    + blood + ", not " + maxPuzzles(idx, blood));
            check(blood <= bloodTop && puzzles <= puzzlesTop, now + ": the values (" + blood + ", " + puzzles
                    + ") were not clamped to the floor's tops (" + bloodTop + ", " + puzzlesTop + ")");
        }

        // ---- 2. generate at the limits on every floor ---------------------------------------------------------------
        for (int i = 0; i < DesignerKit.FLOOR_LABELS.length; i++) {
            String now = DesignerKit.floorLabel(ctx);
            for (int pass = 0; pass < 2; pass++) {
                setSlider(ctx, "Rooms to blood", pass == 0 ? top(ctx, "Rooms to blood") : 2);
                setSlider(ctx, "Puzzles", top(ctx, "Puzzles"));
                int blood = value(ctx, "Rooms to blood");
                int puzzles = value(ctx, "Puzzles");
                for (int n = 0; n < 3; n++) {
                    DesignerKit.click(ctx, "Generate");
                    Object plan = DesignerKit.generated(ctx);
                    if (plan == null) {
                        check(false, now + " " + blood + "/" + puzzles + ": Generate left no plan");
                        continue;
                    }
                    int placed = (Integer) call(ctx, plan, "placedPuzzles");
                    int path = (Integer) call(ctx, plan, "bloodDistance");
                    if (n == 0) {
                        println(String.format("  %s at Rooms to blood %d, Puzzles %d: path %d, %d puzzles - '%s'",
                                now, blood, puzzles, path, placed, DesignerKit.field(ctx, "status")));
                    }
                    check(placed == puzzles, now + " at " + blood + "/" + puzzles + ": " + placed + " puzzles placed");
                    check(path == blood, now + " at " + blood + "/" + puzzles + ": " + path + " rooms to blood");
                }
            }
            DesignerKit.click(ctx, now);   // on to the next floor
        }

        // ---- 3. on Entrance, Puzzles follows Rooms to blood ----------------------------------------------------------
        while (!"Entrance".equals(DesignerKit.floorLabel(ctx))) {
            DesignerKit.click(ctx, DesignerKit.floorLabel(ctx));
        }
        setSlider(ctx, "Rooms to blood", 2);
        setSlider(ctx, "Puzzles", top(ctx, "Puzzles"));
        int before = value(ctx, "Puzzles");
        int topBefore = top(ctx, "Puzzles");
        setSlider(ctx, "Rooms to blood", top(ctx, "Rooms to blood"));
        int blood = value(ctx, "Rooms to blood");
        println("Entrance: Puzzles " + before + " (top " + topBefore + ") at Rooms to blood 2 -> " + value(ctx, "Puzzles")
                + " (top " + top(ctx, "Puzzles") + ") at Rooms to blood " + blood);
        check(topBefore == maxPuzzles(0, 2) && before == topBefore, "Entrance at Rooms to blood 2: Puzzles "
                + before + " of " + topBefore + ", want " + maxPuzzles(0, 2));
        check(top(ctx, "Puzzles") == maxPuzzles(0, blood), "Puzzles' top did not follow Rooms to blood " + blood);
        check(value(ctx, "Puzzles") <= maxPuzzles(0, blood), "Puzzles stayed at " + value(ctx, "Puzzles")
                + " above the new top");
    }

    /**
     * The scenario's own copy of the measured limits (tools/layoutsim -Dbounded=true -Dmatrix=true, seeds 1-4): Entrance
     * 6 rooms to blood; the rest 8.
     */
    private static int maxBlood(int floorIdx) {
        return floorIdx == 0 ? 6 : 8;
    }

    private static int maxPuzzles(int floorIdx, int blood) {
        return switch (floorIdx) {
            case 0 -> blood <= 3 ? 4 : blood <= 5 ? 3 : 2;
            case 1 -> blood <= 4 ? 5 : blood <= 6 ? 4 : blood == 7 ? 3 : 2;
            case 2, 3 -> blood >= 8 ? 4 : 5;
            default -> 5;
        };
    }

    private static int indexOf(String label) {
        for (int i = 0; i < DesignerKit.FLOOR_LABELS.length; i++) {
            if (DesignerKit.FLOOR_LABELS[i].equals(label)) {
                return i;
            }
        }
        return -1;
    }

    /** The SimSlider whose label starts with {@code prefix}. */
    private static AbstractWidget slider(Screen s, String prefix) {
        for (var ch : s.children()) {
            if (ch instanceof AbstractWidget w && w.getClass().getName().equals(SLIDER)
                    && DesignerKit.label(w).startsWith(prefix)) {
                return w;
            }
        }
        return null;
    }

    private static int top(ClientGameTestContext ctx, String prefix) {
        return ctx.computeOnClient(mc -> {
            AbstractWidget w = slider(McCompat.screen(mc), prefix);
            if (w == null) {
                return -1;
            }
            try {
                var f = w.getClass().getDeclaredField("max");
                f.setAccessible(true);
                return f.getInt(w);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("no max on the slider", e);
            }
        });
    }

    private static int value(ClientGameTestContext ctx, String prefix) {
        return ctx.computeOnClient(mc -> {
            AbstractWidget w = slider(McCompat.screen(mc), prefix);
            return w == null ? -1 : (Integer) ModUnderTest.call(w, "intValue", new Class<?>[]{}, new Object[]{});
        });
    }

    /**
     * Sets a slider to {@code v} with the real mouse: a click where the mod's themed slider maps the cursor to that stop
     * (along its drawn track, {@code trackRange()}, with one extra stop past the top for Random).
     */
    private void setSlider(ClientGameTestContext ctx, String prefix, int v) {
        double[] at = ctx.computeOnClient(mc -> {
            AbstractWidget w = slider(McCompat.screen(mc), prefix);
            if (w == null) {
                return null;
            }
            int min;
            int max;
            try {
                var fMin = w.getClass().getDeclaredField("min");
                var fMax = w.getClass().getDeclaredField("max");
                fMin.setAccessible(true);
                fMax.setAccessible(true);
                min = fMin.getInt(w);
                max = fMax.getInt(w);
            } catch (ReflectiveOperationException e) {
                return null;
            }
            // The mod's themed slider maps a click onto its drawn track (trackRange), not the whole row.
            int[] track;
            try {
                track = (int[]) w.getClass().getMethod("trackRange").invoke(w);   // public, on ThemedSliderButton
            } catch (ReflectiveOperationException e) {
                return null;
            }
            double frac = (double) (v - min) / (max + 1 - min);
            return new double[]{track[0] + frac * (track[1] - track[0]) + 0.5, w.getY() + w.getHeight() / 2.0};
        });
        if (at == null) {
            check(false, "no '" + prefix + "' slider");
            return;
        }
        DesignerKit.clickAt(ctx, at[0], at[1], 0);
        if (value(ctx, prefix) != v) {
            check(false, "clicking '" + prefix + "' for " + v + " set " + value(ctx, prefix));
        }
    }

    private static Object call(ClientGameTestContext ctx, Object target, String method) {
        return ctx.computeOnClient(mc -> ModUnderTest.call(target, method, new Class<?>[]{}, new Object[]{}));
    }

    private void check(boolean ok, String problem) {
        if (!ok) {
            failures.add(problem);
            println("PROBLEM: " + problem);
        }
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}

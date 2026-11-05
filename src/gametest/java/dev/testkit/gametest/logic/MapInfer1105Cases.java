package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mod branch mapinfer-1105 (killer560, 2026-11-05, his screenshot: Pit, a 1x4 room, showed 3 tiles above the blank
 * bottom-left slot, Old Trap beside that slot: "you should know that pit is a 4 long so you should be able to infer that
 * that portion also has to be connected to pit ... there has to be a pathway going into old trap there").
 * Drives {@code livemap.MapInference} on floors written as text (its test hooks), no world needed. Every floor is a 6x6
 * one-string-per-grid-row: letters are rooms, '.' an unseen tile, 'x' a tile seen empty; in the gaps ' '/'?' unknown,
 * 'D' a door, '#' a wall, '=' a join.
 *
 * <ul>
 *   <li>1105-logic-mapinfer-pit: his floor. Pit's 4th tile and the join to it are inferred, and Old Trap's door into it
 *       (its other sides are walls). Also: Gold's second tile unseen too - Gold (1x2) can only go down, which leaves Pit
 *       only one way.</li>
 *   <li>1105-logic-mapinfer-ambiguous: Pit fits two ways (up and down both unseen): nothing guessed for Pit; on a full
 *       floor the two tiles are unknown rooms ("?"); two rooms whose only placements take one tile: neither.</li>
 *   <li>1105-logic-mapinfer-override: once the tile and the gap are seen, nothing is inferred; a tile seen as no room:
 *       Pit is not completed over it and no door is forced towards it.</li>
 *   <li>1105-logic-mapinfer-draw: the map's own painter draws the inferred tile, join and door, darkened, and nothing
 *       else.</li>
 *   <li>1105-logic-mapinfer-live: the same floor loaded into the live map (LiveMapFeature's grid, grouped by the real
 *       grouping code): the live inference names Pit; after the real tile arrives it says nothing.</li>
 * </ul>
 */
final class MapInfer1105Cases {

    private static final String MI = "livemap.MapInference";
    private static final String[] SHAPES = {"1x4", "1x1", "1x2", "1x4", "2x2", "2x2", "2x2", "2x2", "1x4", "1x4", "1x1"};
    private static final String[] ROOMS = {"Pit|1x4|NORMAL", "Old Trap|1x1|TRAP", "Gold|1x2|NORMAL", "Slime|1x4|NORMAL",
            "Rails|2x2|NORMAL", "Spider|2x2|NORMAL", "Supertall|2x2|NORMAL", "Temple|2x2|NORMAL", "Doors|1x4|NORMAL",
            "Scaffolding|1x4|NORMAL", "Default|1x1|NORMAL"};

    /** His floor: a = Pit (0,2)-(0,4), (0,5) unseen, b = Old Trap (1,5) with walls up and right, c = Gold (0,0)-(0,1). */
    static final String[] PIT = {
            "cDe=eDf=fDi",
            "= = = = = =",
            "c#e=e#f=f#i",
            "# # # # # =",
            "aDg=gDh=h#i",
            "= = = = = =",
            "a#g=g#h=h#i",
            "= D # # # #",
            "a#j=j=j=j#k",
            "? # # # # D",
            ".?b#d=d=d=d"};

    private MapInfer1105Cases() {
    }

    private static String infer(String[] rows, String[] shapes, boolean full) {
        return (String) Mod.staticCall(MI, "testInfer", rows, shapes, full);
    }

    private static String[] with(String[] rows, int gz, String row) {
        String[] out = rows.clone();
        out[gz] = row;
        return out;
    }

    static void pit(LogicCase c) {
        c.eq("his floor, full", "a@0,9 a@0,10 door@1,10", infer(PIT, SHAPES, true));
        c.eq("his floor, not known full", "a@0,9 a@0,10 door@1,10", infer(PIT, SHAPES, false));
        // Gold's second tile unseen as well: Gold can only run down, and then Pit only down.
        String[] chain = with(with(with(PIT, 1, "? = = = = ="), 2, ".?e=e#f=f#i"), 3, "? # # # # =");
        c.eq("Gold settles first, then Pit", "c@0,1 c@0,2 a@0,9 a@0,10 door@1,10", infer(chain, SHAPES, true));
    }

    static void ambiguous(LogicCase c) {
        String[] twoWays = with(with(with(PIT, 1, "? = = = = ="), 2, ".?e=e#f=f#i"), 3, "? # # # # =");
        String[] shapes = SHAPES.clone();
        shapes[2] = "1x1"; // Gold one tile: the unseen tile above Pit is free, so Pit fits up or down
        String notFull = infer(twoWays, shapes, false);
        c.eq("two placements: no Pit tile, only Old Trap's door", "door@1,10", notFull);
        String full = infer(twoWays, shapes, true);
        c.eq("two placements on a full floor: both unseen tiles are unknown rooms", "?@0,2 ?@0,10 door@1,10", full);
        c.check("never a Pit tile while two placements fit", !notFull.contains("a@") && !full.contains("a@"),
                notFull + " / " + full);
        shapes = SHAPES.clone();
        shapes[1] = "1x2"; // Old Trap two tiles: its only way is into the unseen tile Pit's only placement wants too
        c.eq("two rooms want one tile: neither is guessed", "", infer(PIT, shapes, false));
    }

    static void override(LogicCase c) {
        c.eq("the tile and the door seen", "", infer(with(with(PIT, 9, "= # # # # D"), 10, "aDb#d=d=d=d"), SHAPES, true));
        c.eq("the tile seen, Old Trap's door seen elsewhere", "",
                infer(with(with(PIT, 9, "= D # # # D"), 10, "a#b#d=d=d=d"), SHAPES, true));
        String empty = infer(with(PIT, 10, "x?b#d=d=d=d"), SHAPES, true);
        c.eq("the tile seen empty: no Pit tile over it, no door towards it", "", empty);
    }

    static void draw(LogicCase c) {
        int pit = 0xFF8B5A2B;
        int[] colors = new int[SHAPES.length];
        java.util.Arrays.fill(colors, 0xFF6B4A2B);
        colors[0] = pit;
        Minecraft mc = Minecraft.getInstance();
        GuiRenderState state = new GuiRenderState();
        GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
        Mod.staticCall(MI, "testDraw", g, PIT, SHAPES, true, colors, 0f, 0f, 1f);
        int dark = (Integer) Mod.staticCall("livemap.MapPainter", "multiply", pit, 0.6f);
        List<String> fills = new ArrayList<>();
        for (Object el : fillElements(state)) {
            ScreenRectangle b = ((net.minecraft.client.renderer.state.gui.ScreenArea) el).bounds();
            int col;
            try {
                col = (Integer) el.getClass().getMethod("col1").invoke(el);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("cannot read a fill's color", e);
            }
            fills.add(String.format(Locale.ROOT, "%d,%d-%d,%d#%08X", b.left(), b.top(), b.right(), b.bottom(), col));
        }
        String want = String.format(Locale.ROOT, "0,100-16,116#%08X 0,96-16,100#%08X 16,105-20,111#%08X", dark, dark, dark);
        java.util.Collections.sort(fills);
        c.eq("the join, the tile and the door, each darkened from Pit's color, and nothing else", want,
                String.join(" ", fills));
        c.check("darkened is not Pit's own color", dark != pit, String.format(Locale.ROOT, "%08X", dark));
    }

    static void live(LogicCase c) {
        Object before = null;
        try {
            Mod.staticCall("livemap.LiveMapFeature", "testLoadFloor", PIT, ROOMS);
            // The map item map draws only what the map item shows, and at the title screen it shows nothing: the
            // world-scanned floor must not leak into its inference.
            c.eq("map item map with no map item: nothing inferred", "", Mod.staticCall(MI, "testCurrent", "F7"));
            before = Mod.staticCall("livemap.MapPainter", "beginFullScan");
            c.eq("scanned-floor map: Pit's tile, its join and Old Trap's door", "Pit@0,9 Pit@0,10 door@1,10",
                    Mod.staticCall(MI, "testCurrent", "F7"));
            Mod.staticCall("livemap.LiveMapFeature", "testLoadFloor",
                    with(with(PIT, 9, "= # # # # D"), 10, "aDb#d=d=d=d"), ROOMS);
            c.eq("live map after the real tile and door arrive: nothing inferred", "",
                    Mod.staticCall(MI, "testCurrent", "F7"));
        } finally {
            if (before != null) {
                Mod.staticCall("livemap.MapPainter", "endFullScan", before);
            }
            Mod.staticCall("livemap.LiveMapFeature", "testLoadFloor", new String[0], new String[0]);
            Mod.staticCall(MI, "testCurrent", (Object) null);
        }
    }

    /** Every solid fill in the render state: plain fills and the parts of each GuiRects batch (as SimMapHudTests). */
    private static List<Object> fillElements(GuiRenderState state) {
        List<Object> out = new ArrayList<>();
        state.forEachElement(el -> {
            if (el.getClass().getSimpleName().contains("ColoredRectangle")) {
                out.add(el);
                return;
            }
            if (el.getClass().getName().equals("com.killer560.hub.hud.GuiRects$Batch")) {
                try {
                    java.lang.reflect.Method parts = el.getClass().getDeclaredMethod("parts");
                    parts.setAccessible(true);
                    out.addAll(java.util.Arrays.asList((Object[]) parts.invoke(el)));
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("cannot read the parts of a GuiRects batch", e);
                }
            }
        }, GuiRenderState.TraverseRange.ALL);
        return out;
    }
}

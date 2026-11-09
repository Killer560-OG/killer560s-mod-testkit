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
 *   <li>1105-logic-mapinfer-ambiguous: Pit fits two ways (up and down both unseen): nothing guessed for Pit; two rooms
 *       whose only placements take one tile: neither.</li>
 *   <li>1105-logic-mapinfer-override: once the tile and the gap are seen, nothing is inferred; a tile seen as no room:
 *       Pit is not completed over it and no door is forced towards it.</li>
 *   <li>1105-logic-mapinfer-draw: the map's own painter draws the inferred tile, join and door in the room's own color
 *       (mod mapq-1105, killer560: "the guess is perfect just have it as the same color as the rest of pipes"; until
 *       then darkened), and nothing else.</li>
 *   <li>1105-logic-mapinfer-live: the same floor loaded into the live map (LiveMapFeature's grid, grouped by the real
 *       grouping code): the live inference names Pit; after the real tile arrives it says nothing.</li>
 *   <li>1105-logic-mapq-infer-noquestion (mod mapq-1105, killer560 2026-11-05 on his F7 Funny Map's top row: "dont show
 *       those question mark rooms"): the ambiguous floor drawn as a full floor paints only Old Trap's door - no gray tile
 *       and no "?" on the two blank slots (the old jar drew both).</li>
 *   <li>1105-logic-mapq-predict-unsure: a Predictive Rooms prediction with certain and unsure tiles, drawn by
 *       PredictedRoomsPainter: its certain tiles are filled, nothing touches its unsure tiles (they were gray with a
 *       "?"), and the painter's plan names no "?" tile.</li>
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

    private static String infer(String[] rows, String[] shapes) {
        return (String) Mod.staticCall(MI, "testInfer", rows, shapes);
    }

    /** The two-ways floor: Gold one tile, so Pit fits up or down; both blank slots are unseen. */
    private static final String[] TWO_WAYS = with(with(with(PIT, 1, "? = = = = ="), 2, ".?e=e#f=f#i"), 3, "? # # # # =");

    private static String[] with(String[] rows, int gz, String row) {
        String[] out = rows.clone();
        out[gz] = row;
        return out;
    }

    static void pit(LogicCase c) {
        c.eq("his floor", "a@0,9 a@0,10 door@1,10", infer(PIT, SHAPES));
        // Gold's second tile unseen as well: Gold can only run down, and then Pit only down.
        c.eq("Gold settles first, then Pit", "c@0,1 c@0,2 a@0,9 a@0,10 door@1,10", infer(TWO_WAYS, SHAPES));
    }

    static void ambiguous(LogicCase c) {
        c.eq("two placements: no Pit tile, no unknown room, only Old Trap's door", "door@1,10",
                infer(TWO_WAYS, goldOneTile()));
        String[] shapes = SHAPES.clone();
        shapes[1] = "1x2"; // Old Trap two tiles: its only way is into the unseen tile Pit's only placement wants too
        c.eq("two rooms want one tile: neither is guessed", "", infer(PIT, shapes));
    }

    /** Gold one tile: the unseen tile above Pit is free, so Pit fits up or down. */
    private static String[] goldOneTile() {
        String[] shapes = SHAPES.clone();
        shapes[2] = "1x1";
        return shapes;
    }

    static void override(LogicCase c) {
        c.eq("the tile and the door seen", "", infer(with(with(PIT, 9, "= # # # # D"), 10, "aDb#d=d=d=d"), SHAPES));
        c.eq("the tile seen, Old Trap's door seen elsewhere", "",
                infer(with(with(PIT, 9, "= D # # # D"), 10, "a#b#d=d=d=d"), SHAPES));
        String empty = infer(with(PIT, 10, "x?b#d=d=d=d"), SHAPES);
        c.eq("the tile seen empty: no Pit tile over it, no door towards it", "", empty);
    }

    private static final int PIT_COLOR = 0xFF8B5A2B;

    static void draw(LogicCase c) {
        String want = String.format(Locale.ROOT, "0,100-16,116#%08X 0,96-16,100#%08X 16,105-20,111#%08X",
                PIT_COLOR, PIT_COLOR, PIT_COLOR);
        c.eq("the join, the tile and the door, in Pit's own color, and nothing else", want,
                String.join(" ", inferFills(PIT, SHAPES)));
    }

    static void noQuestion(LogicCase c) {
        c.eq("two placements on a full floor: only Old Trap's door is drawn, no gray \"?\" room on either blank slot",
                String.format(Locale.ROOT, "16,105-20,111#%08X", PIT_COLOR), String.join(" ", inferFills(TWO_WAYS, goldOneTile())));
    }

    /** The inference's fills for a floor drawn as a FULL floor (on an old jar too, whose hooks still took "full"), sorted;
     *  rooms in a brown, room a (and an inferred door) in {@link #PIT_COLOR}. */
    private static List<String> inferFills(String[] rows, String[] shapes) {
        int[] colors = new int[SHAPES.length];
        java.util.Arrays.fill(colors, 0xFF6B4A2B);
        colors[0] = PIT_COLOR;
        Minecraft mc = Minecraft.getInstance();
        GuiRenderState state = new GuiRenderState();
        GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
        boolean oldHooks;
        try {
            oldHooks = java.util.Arrays.stream(Class.forName("com.killer560.hub.livemap.MapInference").getDeclaredMethods())
                    .anyMatch(m -> m.getName().equals("testDraw") && m.getParameterCount() == 8);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("no MapInference in the mod", e);
        }
        if (oldHooks) {
            Mod.staticCall(MI, "testDraw", g, rows, shapes, true, colors, 0f, 0f, 1f);
        } else {
            Mod.staticCall(MI, "testDraw", g, rows, shapes, colors, 0f, 0f, 1f);
        }
        List<String> fills = fills(state);
        java.util.Collections.sort(fills);
        return fills;
    }

    private static List<String> fills(GuiRenderState state) {
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
        return fills;
    }

    /** A Pipes prediction in column 4 (grid): certain at rows 2, 4, 6, unsure at rows 0 and 8 (it fits up or down). */
    @SuppressWarnings("unchecked")
    static void predictUnsure(LogicCase c) throws Exception {
        Class<?> rp = Class.forName("com.killer560.hub.livemap.predict.RoomPredictor");
        java.lang.reflect.Field field = rp.getDeclaredField("PREDICTIONS");
        field.setAccessible(true);
        java.util.Map<Integer, Object> preds = (java.util.Map<Integer, Object>) field.get(null);
        java.util.Map<Integer, Object> saved = new java.util.HashMap<>(preds);
        Object lm = Mod.staticCall("livemap.LiveMapConfig", "getInstance");
        Object pc = Mod.staticCall("livemap.predict.PredictiveRoomsConfig", "getInstance");
        boolean funny = (Boolean) Mod.call(lm, "isFunnyMapRaw");
        boolean enabled = (Boolean) Mod.call(pc, "isEnabledRaw");
        boolean guess = (Boolean) Mod.call(pc, "isSpecialGuessRaw");
        boolean gate = (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled");
        try {
            Mod.call(lm, "setFunnyMap", false);
            Mod.call(pc, "setEnabled", true);
            Mod.call(pc, "setSpecialGuess", false);
            Mod.staticCall("util.SkyblockGate", "setEnabled", false);
            Class<?> pr = Class.forName("com.killer560.hub.livemap.predict.RoomPredictor$Prediction");
            Object p = pr.getDeclaredConstructors()[0].newInstance(26, "Pipes", "NORMAL", new int[]{26, 48, 70},
                    new int[]{4, 92}, false, 2, 40);
            preds.clear();
            preds.put(26, p);
            GuiRenderState state = new GuiRenderState();
            Minecraft mc = Minecraft.getInstance();
            GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
            Mod.staticCall("livemap.PredictedRoomsPainter", "draw", g, mc.font, lm, 0f, 0f, 1f);
            int x0 = (Integer) Mod.staticCall("livemap.MapPainter", "cellPos", 4);
            int x1 = x0 + (Integer) Mod.staticCall("livemap.MapPainter", "cellSize", 4);
            int[][] unsure = {rows(0), rows(8)};
            int[] certain = rows(4);
            int onCertain = 0;
            List<String> onUnsure = new ArrayList<>();
            for (Object el : fillElements(state)) {
                ScreenRectangle b = ((net.minecraft.client.renderer.state.gui.ScreenArea) el).bounds();
                boolean inColumn = b.left() < x1 && b.right() > x0;
                for (int[] u : unsure) {
                    if (inColumn && b.top() < u[1] && b.bottom() > u[0]) {
                        onUnsure.add(b.left() + "," + b.top() + "-" + b.right() + "," + b.bottom());
                    }
                }
                if (inColumn && b.top() < certain[1] && b.bottom() > certain[0]) {
                    onCertain++;
                }
            }
            c.check("the prediction drew its certain tiles (the painter acted)", onCertain > 0, onCertain + " fill(s)");
            c.check("nothing is drawn on its unsure tiles (no gray, no \"?\")", onUnsure.isEmpty(), onUnsure.toString());
            java.util.Map<Integer, String> plan = (java.util.Map<Integer, String>) Mod.staticCall(
                    "livemap.PredictedRoomsPainter", "plan", true);
            c.eq("the painter's plan: the certain tiles only", "{26=Pipes, 48=Pipes, 70=Pipes}", String.valueOf(plan));
        } finally {
            preds.clear();
            preds.putAll(saved);
            Mod.call(lm, "setFunnyMap", funny);
            Mod.call(pc, "setEnabled", enabled);
            Mod.call(pc, "setSpecialGuess", guess);
            Mod.staticCall("util.SkyblockGate", "setEnabled", gate);
        }
    }

    /** A grid row's {top, bottom} in map units (ppu 1). */
    private static int[] rows(int gz) {
        int top = (Integer) Mod.staticCall("livemap.MapPainter", "cellPos", gz);
        return new int[]{top, top + (Integer) Mod.staticCall("livemap.MapPainter", "cellSize", gz)};
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

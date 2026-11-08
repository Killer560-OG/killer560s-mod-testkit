package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

/**
 * Mod branch maplabel-1106 (killer560, 2026-11-06, his Cathedral screenshot: "that text should become recentered"). A
 * Predictive Rooms name was centered on the prediction's certain tiles alone; Cathedral (2x2) with only its left column
 * certain was labeled over that column while the map already showed all four tiles as one room.
 * <ul>
 *   <li>1106-maplabel-recenter: the floor holds only Cathedral's left column; the prediction's label sits on it. The same
 *       prediction after the floor grows to the whole 2x2 (the same certain tiles): the label moves half a tile pitch
 *       right and is a full room wide.</li>
 * </ul>
 */
final class MapLabel1106Cases {

    private static final String[] ROOMS = {"Cathedral|2x2|NORMAL"};

    private MapLabel1106Cases() {
    }

    private static float[] box(Object prediction) {
        return (float[]) Mod.staticCall("livemap.PredictedRoomsPainter", "testLabelBox", prediction);
    }

    static void recenter(LogicCase c) {
        Object before = null;
        try {
            before = Mod.staticCall("livemap.MapPainter", "beginFullScan");
            Mod.staticCall("livemap.LiveMapFeature", "testLoadFloor", new String[]{"a", "=", "a"}, ROOMS);
            int[] certain = {0, 2 * 11};
            Object p = Mod.staticCall("livemap.PredictedRoomsPainter", "testPrediction", "Cathedral", certain);
            float[] column = box(p);
            Mod.staticCall("livemap.LiveMapFeature", "testLoadFloor", new String[]{"a=a", "===", "a=a"}, ROOMS);
            float[] whole = box(p);
            c.check("the full 2x2 is wider than the left column (" + column[2] + " -> " + whole[2] + ")", whole[2] > column[2]);
            c.near("the label moved right by half the added width", column[0] + (whole[2] - column[2]) / 2f, whole[0], 0.01);
            c.near("the label's height is unchanged", column[3], whole[3], 0.01);
        } finally {
            if (before != null) {
                Mod.staticCall("livemap.MapPainter", "endFullScan", before);
            }
            Mod.staticCall("livemap.LiveMapFeature", "testLoadFloor", new String[0], new String[0]);
        }
    }
}

package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

/**
 * Mod branch mapclickpred-1118 (killer560, 2026-11-18, Interactive Map: "I cannot click on the rightmost part of hallway
 * even though it knows it is there because of the predictive thing but i still cant click it"). A Predictive Rooms
 * tile the scan has not reached is drawn, but the click resolved it to a bare cell with no room, so the press did not
 * go to the room. The press acts on {@code LiveMapFeature.mapCellAt}'s cell and needs a room group there.
 * <ul>
 *   <li>1118-logic-mapclick-predicted: Pit's 4th tile (and the join to it) are predicted only; a press on either, and on
 *       the scanned tiles and joins, resolves to a tile of Pit's own group (the room the Go acts on); with no prediction
 *       the unscanned cells stay unresolved (the test sees the old behavior, so it proves the prediction acted).</li>
 * </ul>
 */
final class MapClick1118Cases {

    private static final String LM = "livemap.LiveMapFeature";

    private MapClick1118Cases() {
    }

    private static int cellAt(int gx, int gz) {
        double ux = (Integer) Mod.staticCall("livemap.MapPainter", "cellPos", gx)
                + (Integer) Mod.staticCall("livemap.MapPainter", "cellSize", gx) / 2.0;
        double uz = (Integer) Mod.staticCall("livemap.MapPainter", "cellPos", gz)
                + (Integer) Mod.staticCall("livemap.MapPainter", "cellSize", gz) / 2.0;
        return (Integer) Mod.staticCall(LM, "mapCellAt", ux, uz);
    }

    private static int group(int cell) {
        return (Integer) Mod.staticCall(LM, "groupIdAt", cell);
    }

    @SuppressWarnings("unchecked")
    static void predicted(LogicCase c) throws Exception {
        Class<?> rp = Class.forName("com.killer560.hub.livemap.predict.RoomPredictor");
        java.lang.reflect.Field field = rp.getDeclaredField("PREDICTIONS");
        field.setAccessible(true);
        java.util.Map<Integer, Object> preds = (java.util.Map<Integer, Object>) field.get(null);
        java.util.Map<Integer, Object> saved = new java.util.HashMap<>(preds);
        Object lm = Mod.staticCall("livemap.LiveMapConfig", "getInstance");
        Object pc = Mod.staticCall("livemap.predict.PredictiveRoomsConfig", "getInstance");
        boolean funny = (Boolean) Mod.call(lm, "isFunnyMapRaw");
        boolean enabled = (Boolean) Mod.call(pc, "isEnabledRaw");
        boolean gate = (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled");
        try {
            Mod.call(lm, "setFunnyMap", false);
            Mod.call(pc, "setEnabled", true);
            Mod.staticCall("util.SkyblockGate", "setEnabled", false);
            // Pit is the 1x4 in grid column 0, rows 4/6/8 scanned; row 10 and the strip at row 9 are not.
            Mod.staticCall(LM, "testLoadFloor", MapInfer1105Cases.PIT, MapInfer1105Cases.ROOMS);
            int pit = group(4 * 11);
            c.check("Pit is a scanned room group", pit >= 0, "group " + pit);
            c.eq("unscanned tile (0,10): no group of its own", -1, group(10 * 11));
            c.eq("no prediction: a press on tile (0,10) is a bare cell", 10 * 11, cellAt(0, 10));
            c.eq("no prediction: a press on strip (0,9) is a bare cell", 9 * 11, cellAt(0, 9));

            Class<?> pr = Class.forName("com.killer560.hub.livemap.predict.RoomPredictor$Prediction");
            Object p = pr.getDeclaredConstructors()[0].newInstance(44, "Pit", "NORMAL",
                    new int[]{44, 66, 88, 110}, new int[0], false, 1, 40);
            preds.clear();
            preds.put(44, p);
            int tile = cellAt(0, 10);
            int strip = cellAt(0, 9);
            c.check("predicted tile (0,10) resolves to a tile of Pit", tile != 10 * 11 && group(tile) == pit,
                    "cell " + tile + " group " + group(tile));
            c.check("strip (0,9) to the predicted tile resolves to a tile of Pit", strip != 9 * 11 && group(strip) == pit,
                    "cell " + strip + " group " + group(strip));
            c.check("scanned tile (0,8) still acts as itself", cellAt(0, 8) == 8 * 11);
            c.check("scanned strip (0,7) resolves to Pit", group(cellAt(0, 7)) == pit);
            c.check("a cell outside the prediction is not Pit", group(cellAt(5, 9)) != pit);
            preds.clear();
            c.eq("prediction gone: back to a bare cell", 10 * 11, cellAt(0, 10));
        } finally {
            preds.clear();
            preds.putAll(saved);
            Mod.call(lm, "setFunnyMap", funny);
            Mod.call(pc, "setEnabled", enabled);
            Mod.staticCall("util.SkyblockGate", "setEnabled", gate);
        }
    }
}

package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 1121-apmenu-logic-migrate (mod apmenu-1121): Dungeons Autopilot's one Toggle Keybind takes the first bound old start key
 * (Dungeon Autopilot, then Auto Secret, then Auto Clear, then Blood Rush) and clears them all; an old Auto Secret file takes
 * its Mode from Run Mode / Room Plan; the new settings survive save + load.
 */
final class ApMenu1121Cases {
    private static final String AS = "autosecret.AutoSecretConfig";
    private static final String AC = "autoclear.AutoClearConfig";
    private static final String LM = "livemap.LiveMapConfig";

    private ApMenu1121Cases() {
    }

    private static void keys(int pilot, int secret, int clear, int rush) {
        Mod.call(Mod.cfg(AS), "setAutopilotKey", pilot);
        Mod.call(Mod.cfg(AS), "setToggleKey", secret);
        Mod.call(Mod.cfg(AC), "setKeybind", clear);
        Mod.call(Mod.cfg(LM), "setBloodRushKeyCode", rush);
    }

    private static void load(Path p, String json) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, json);
        Mod.staticCall(AS, "load");
    }

    static void migrate(LogicCase c) throws Exception {
        if (!Mod.isCheat()) {
            c.skip("migrate", "legit jar: no Dungeons Autopilot");
            return;
        }
        Path p = (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-autosecret.json");
        byte[] saved = Files.exists(p) ? Files.readAllBytes(p) : null;
        int oldClear = (Integer) Mod.call(Mod.cfg(AC), "getKeybindRaw");
        int oldRush = (Integer) Mod.call(Mod.cfg(LM), "getBloodRushKeyCode");
        try {
            int[][] rows = {{300, 301, 302, 303, 300}, {-1, 301, 302, 303, 301}, {-1, -1, 302, 303, 302},
                    {-1, -1, -1, 303, 303}, {-1, -1, -1, -1, -1}};
            for (int[] r : rows) {
                keys(r[0], r[1], r[2], r[3]);
                int got = (Integer) Mod.staticCall("autosecret.DungeonsAutopilot", "takeOldBind");
                String tag = r[0] + "/" + r[1] + "/" + r[2] + "/" + r[3];
                c.eq("bind from " + tag, r[4], got);
                c.eq("old keys cleared after " + tag, "-1/-1/-1",
                        Mod.call(Mod.cfg(AS), "oldStartKey") + "/" + Mod.call(Mod.cfg(AC), "getKeybindRaw") + "/"
                                + Mod.call(Mod.cfg(LM), "getBloodRushKeyCode"));
            }
            load(p, "{\"runMode\":\"PARTY\"}");
            c.eq("Run Mode Party", "PARTY_SECRETS", String.valueOf(Mod.get(AS, "getMode")));
            load(p, "{\"roomPlan\":\"SECRETS_THEN_CLEARS\"}");
            c.eq("Room Plan Secrets Then Clears", "SECRET_THEN_CLEAR", String.valueOf(Mod.get(AS, "getMode")));
            load(p, "{\"runMode\":\"SOLO\",\"roomPlan\":\"FASTEST_TOTAL\"}");
            c.eq("Solo", "SOLO_300", String.valueOf(Mod.get(AS, "getMode")));
            load(p, "{\"runMode\":\"PARTY\",\"mode\":\"CLEAR_THEN_SECRET\"}");
            c.eq("a saved Mode wins", "CLEAR_THEN_SECRET", String.valueOf(Mod.get(AS, "getMode")));
            // the new settings survive a restart
            Object cfg = Mod.cfg(AS);
            Mod.call(cfg, "setMode", Mod.enumValue(AS + "$Mode", "SECRET_THEN_CLEAR"));
            Mod.call(cfg, "setBloodRush", Mod.enumValue(AS + "$BloodRush", "CLEAR"));
            Mod.call(cfg, "setRoomBeforeBlood", Mod.enumValue(AS + "$RoomBeforeBlood", "SKIP"));
            Mod.call(cfg, "setDisableAfterBloodRush", true);
            Mod.call(cfg, "setClearFirst", true);
            Mod.call(cfg, "setClearBeforeSecret", true);
            Mod.call(cfg, "save");
            Mod.staticCall(AS, "load");
            c.eq("saved settings", "SECRET_THEN_CLEAR CLEAR SKIP true true true", Mod.get(AS, "getMode") + " "
                    + Mod.get(AS, "getBloodRush") + " " + Mod.get(AS, "getRoomBeforeBlood") + " "
                    + Mod.get(AS, "isDisableAfterBloodRush") + " " + Mod.get(AS, "isClearFirst") + " "
                    + Mod.get(AS, "isClearBeforeSecret"));
        } finally {
            if (saved != null) {
                Files.write(p, saved);
            } else {
                Files.deleteIfExists(p);
            }
            Mod.staticCall(AS, "load");
            Mod.call(Mod.cfg(AC), "setKeybind", oldClear);
            Mod.call(Mod.cfg(AC), "save");
            Mod.call(Mod.cfg(LM), "setBloodRushKeyCode", oldRush);
            Mod.call(Mod.cfg(LM), "save");
        }
    }
}

package dev.testkit.gametest.hx;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.InvWalkCarryTests;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;

import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod branch featG-1109 (feature sweep items 17 and 21): Carry Tracker and Room Preview. Each case drives the real signal and
 * asserts the feature acted: a run counted and saved (and still there after a reload), a panel drawn under a real cursor.
 */
final class HxFeatG1109Cases {

    private static final String CT = "carrytracker.CarryTracker";
    private static final String CT_CFG = "carrytracker.CarryTrackerConfig";
    private static final String RP = "livemap.RoomPreview";
    private static final String RP_CFG = "livemap.RoomPreviewConfig";

    private HxFeatG1109Cases() {
    }

    static void register(Session s) {
        HxCarryHud1109Cases.register(s);
        s.test("1109-carry-count", HxFeatG1109Cases::carryCount);
        s.test("1109-carry-run", HxFeatG1109Cases::carryRun);
        s.test("1109-roompreview-lines", HxFeatG1109Cases::previewLines);
        s.test("1109-roompreview-hover", HxFeatG1109Cases::previewHover);
    }

    private static long count(Session c, String name) {
        return ((Number) c.onClient(mc -> Mod.staticCall(CT, "count", name))).longValue();
    }

    private static List<?> recent(Session c, int n) {
        return (List<?>) c.onClient(mc -> Mod.staticCall(CT, "recent", n));
    }

    private static void track(Session c, String name, boolean on) {
        run(c, () -> Mod.call(Mod.cfg(CT_CFG), on ? "add" : "remove", name));
    }

    private static void record(Session c, long totalMs, int score, List<String> party) {
        run(c, () -> Mod.staticCall(CT, "recordForTest", "F7", totalMs, score, party));
        run(c, () -> Mod.staticCall(CT, "drainForTest"));
    }

    private static void carryCount(Session c) {
        try (Settings set = new Settings(c).with(CT_CFG, "Enabled", true)) {
            track(c, "HxMateA", true);
            long before = count(c, "HxMateA");
            record(c, 600_000L, 300, List.of("hxmatea", "Zed"));
            c.check(count(c, "HxMateA") == before + 1, "tracked HxMateA (party spelled hxmatea) was not counted: " + count(c, "HxMateA"));
            c.check(count(c, "Zed") == 0, "an untracked name was counted: " + count(c, "Zed"));
            record(c, 500_000L, 310, List.of("Zed"));
            c.check(count(c, "HxMateA") == before + 1, "a run without HxMateA changed his count");
            record(c, 400_000L, 305, List.of("HxMateA"));
            long two = count(c, "HxMateA");
            c.check(two == before + 2, "second run: " + two + " not " + (before + 2));
            List<?> hist = recent(c, 5);
            c.check(hist.size() >= 2 && "HxMateA".equals(Mod.call(hist.get(0), "player"))
                    && ((Number) Mod.call(hist.get(0), "totalMs")).longValue() == 400_000L, "history newest first: " + hist);
            // Saved to the shared store: a fresh load reads the same count.
            run(c, () -> Mod.staticCall(CT, "reloadForTest"));
            c.check(count(c, "HxMateA") == two, "after a reload the count is " + count(c, "HxMateA") + ", saved " + two);
            c.note("count " + before + " -> " + two + ", history " + hist.size() + ", reload kept it");
            track(c, "HxMateA", false);
        }
    }

    private static void playRun(Session c) {
        int before = c.onClient(mc -> ((Number) Mod.staticCall("runsummary.RunHistoryStore", "size")).intValue());
        run(c, () -> Mod.staticCall("runsummary.RunSummaryFeature", "resetRun"));
        send(c, "chat.run-start");
        c.waitUntil("a run is tracked", mc -> (Boolean) Mod.staticCall("runsummary.RunSummaryFeature", "isTrackingRun"), 40);
        c.ctx().waitTicks(5);
        send(c, "chat.extra-stats");
        c.hx().chat("             Team Score: 300 (S+)");
        c.waitUntil("the run was stored", mc -> ((Number) Mod.staticCall("runsummary.RunHistoryStore", "size")).intValue() > before, 400);
        run(c, () -> Mod.staticCall(CT, "drainForTest"));
    }

    private static void carryRun(Session c) {
        String me = self(c);
        try (Settings set = new Settings(c).with("runsummary.RunSummaryConfig", "Enabled", true)) {
            dungeon(c);
            track(c, me, true);
            long before = count(c, me);
            // Switch OFF: a real finished run counts nothing.
            playRun(c);
            c.check(count(c, me) == before, "Carry Tracker OFF counted a run: " + count(c, me));
            run(c, () -> Mod.set(CT_CFG, "setEnabled", true));
            playRun(c);
            c.waitUntil("the finished run counted for " + me, mc -> ((Number) Mod.staticCall(CT, "count", me)).longValue() == before + 1, 100);
            Object e = recent(c, 1).get(0);
            c.check(me.equals(Mod.call(e, "player")) && ((Number) Mod.call(e, "score")).intValue() == 300, "history entry " + e);
            c.note("real run end counted " + me + ": " + before + " -> " + count(c, me) + ", " + e);
            run(c, () -> Mod.set(CT_CFG, "setEnabled", false));
            track(c, me, false);
            run(c, () -> Mod.staticCall("runsummary.RunSummaryFeature", "resetRun"));
        }
    }

    private static void previewLines(Session c) {
        @SuppressWarnings("unchecked")
        List<String> full = (List<String>) c.onClient(mc -> Mod.staticCall(RP, "linesForTest", "Water Board", "1x2", "PUZZLE", 2, 1, 1, 1));
        c.check(full.equals(List.of("Water Board", "Puzzle, 1x2", "Secrets: 4", "Chest 2, Item 1, Bat 1", "Crypts: 1")), "panel text " + full);
        @SuppressWarnings("unchecked")
        List<String> bare = (List<String>) c.onClient(mc -> Mod.staticCall(RP, "linesForTest", "Pit", "1x1", "NORMAL", 0, 0, 0, 0));
        c.check(bare.equals(List.of("Pit", "Normal, 1x1")), "a room with nothing in it: " + bare);
    }

    private static void moveTo(Session c, float guiX, float guiY) {
        run(c, () -> {
            Minecraft mc = Minecraft.getInstance();
            var w = mc.getWindow();
            InvWalkCarryTests.mouseTo(mc, guiX * w.getScreenWidth() / (double) w.getGuiScaledWidth(),
                    guiY * w.getScreenHeight() / (double) w.getGuiScaledHeight());
        });
    }

    private static int panels(Session c) {
        return ((Number) c.onClient(mc -> Mod.staticCall(RP, "panelsDrawnForTest"))).intValue();
    }

    private static void previewHover(Session c) {
        Object scan = null;
        try (Settings set = new Settings(c).with("livemap.LiveMapConfig", "Enabled", true).with(RP_CFG, "Enabled", false)) {
            dungeon(c);
            // Earlier cases of a full session leave him inside the F7 boss box, where the map HUD hides: step out of it first.
            c.server().command("tp @a -419.5 151 -419.5");
            c.ctx().waitTicks(10);
            scan = c.onClient(mc -> Mod.staticCall("livemap.MapPainter", "beginFullScan"));
            run(c, () -> Mod.staticCall("livemap.LiveMapFeature", "testLoadFloor", new String[]{"a"}, new String[]{"Blaze|1x1|PUZZLE"}));
            run(c, () -> McCompat.setScreen(Minecraft.getInstance(), new ChatScreen("", false)));
            // OFF: the cursor sits near the map's corner and nothing is drawn.
            c.ctx().waitTicks(20);
            moveTo(c, 5, 5);
            c.ctx().waitTicks(10);
            int off = panels(c);
            c.check(off == 0, "Room Preview OFF drew " + off + " panels");
            run(c, () -> Mod.set(RP_CFG, "setEnabled", true));
            c.waitUntil("the map was drawn with the switch on", mc -> ((float[]) Mod.staticCall(RP, "lastMapForTest"))[2] > 0f, 60);
            float[] m = (float[]) c.onClient(mc -> Mod.staticCall(RP, "lastMapForTest"));
            // The middle of tile (0,0): the room is 16 map units wide.
            moveTo(c, m[0] + m[2] * 8, m[1] + m[2] * 8);
            c.waitUntil("the panel was drawn over the room", mc -> ((Number) Mod.staticCall(RP, "panelsDrawnForTest")).intValue() > off, 80);
            @SuppressWarnings("unchecked")
            List<String> lines = (List<String>) c.onClient(mc -> Mod.staticCall(RP, "cachedLinesForTest"));
            c.check(lines.equals(List.of("Blaze", "Puzzle, 1x1")), "panel text under the cursor " + lines);
            // Cursor far off the map: no more panels.
            moveTo(c, m[0] + m[2] * 400, m[1] + m[2] * 400);
            c.ctx().waitTicks(10);
            int idle = panels(c);
            c.ctx().waitTicks(10);
            c.check(panels(c) == idle, "panels kept drawing with the cursor off the map");
            c.note("OFF drew " + off + " panels; ON over the room drew " + (idle - off) + "; text " + lines);
        } finally {
            if (scan != null) {
                Object s = scan;
                run(c, () -> Mod.staticCall("livemap.MapPainter", "endFullScan", s));
            }
            run(c, () -> Mod.staticCall("livemap.LiveMapFeature", "testLoadFloor", new String[0], new String[0]));
            run(c, () -> McCompat.setScreen(Minecraft.getInstance(), null));
        }
    }
}

package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 888-890 (croesus-track, 2026-10-12). killer560: "if it moves it still needs to be tracked." A run is known by party,
 * floor and its expiry instant (from "Chests expire in ..."), and the list's order decides between same-party runs whose
 * expiry text is the same; what was learned is kept in killer560smod-croesus-runs.json across restarts.
 * <ul>
 *   <li>888: run 10 (unclaimed) expires and every later run moves one slot back - two same-party (Bravo) runs, one worth
 *       a key, one not, and a key-used run: each keeps its state in its new slot from the first frame.</li>
 *   <li>889: two Bravo runs with the very same "expire in 2d 3h" stay distinct, before and after the shift.</li>
 *   <li>890: after looking at the runs, the memory is reloaded from disk (a restart) and the shifted list still reads
 *       needs-a-key / opened; the file holds the two runs.</li>
 * </ul>
 */
final class CroesusTrackCases {

    private CroesusTrackCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "888-menu-croesus-track-shift", c -> shift(c, false, "1d 20h!"));
        MenuSuite.test(s, "889-menu-croesus-track-same-expiry", c -> shift(c, false, "2d 3h!"));
        MenuSuite.test(s, "890-menu-croesus-track-restart", c -> shift(c, true, "1d 20h!"));
    }

    static JsonObject head(String player, String stateLine, String expiry) {
        return FlipCroesusCases.item("minecraft:player_head", "§cThe Catacombs", "§7Floor VII", "", "§7Players:",
                " §a" + player + "§f: §eMage (39)", "", "§7Completed: §a1 day ago", "", stateLine,
                "§8Chests expire in " + expiry, "", "§eClick to view chests!");
    }

    static void shift(Session c, boolean restart, String secondExpiry) throws Exception {
        try (AutoCloseable w = CroesusStableCases.world(c)) {
            JsonObject bravoA = head("Bravo", "§7Opened Chest: §4Bedrock", "2d 3h!");
            JsonObject bravoB = head("Bravo", "§7Opened Chest: §4Bedrock", secondExpiry);
            JsonObject done = head("Charlie", "§aNo more chests to open!", "1d 2h!");
            Map<Integer, JsonObject> before = new LinkedHashMap<>();
            before.put(10, head("Alpha", "§cNo chests opened yet!", "2d 5h!"));
            before.put(11, bravoA);
            before.put(12, bravoB);
            before.put(13, done);
            JsonObject list = CroesusStableCases.list(before, Map.of(
                    11, FlipCroesusCases.open(CroesusKeyCases.runView("Testkit Idol")),
                    12, FlipCroesusCases.open(CroesusKeyCases.runView("Testkit Bead"))));
            CroesusStableCases.showList(c, list, 10);
            CroesusStableCases.press(c, 11, CroesusKeyCases.RUN);
            CroesusStableCases.showList(c, list, 10);
            CroesusStableCases.press(c, 12, CroesusKeyCases.RUN);
            CroesusStableCases.recording = true;
            CroesusStableCases.showList(c, list, 15);
            List<CroesusStableCases.Frame> pre = CroesusStableCases.take(c).stream()
                    .filter(f -> f.list() && f.items().equals("xxxx")).toList();
            c.check(!pre.isEmpty() && pre.stream().allMatch(f -> f.states().equals("1234")),
                    "before the shift: " + CroesusStableCases.log(pre) + " (want 1 2 3 4)");
            String file = "";
            if (restart) {
                Path p = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-croesus-runs.json"));
                file = Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
                int runs = file.split("\"sig\"", -1).length - 1;
                c.check(runs == 2, "the run file holds " + runs + " run(s), want 2: " + file);
                c.ctx().runOnClient(mc -> Mod.staticCall("croesus.ChestProfitFeature", "reloadRunMemoryForTest"));
            }
            // Run 10 expires: every later run moves one slot back.
            Map<Integer, JsonObject> after = new LinkedHashMap<>();
            after.put(10, bravoA);
            after.put(11, bravoB);
            after.put(12, done);
            CroesusStableCases.recording = true;
            CroesusStableCases.showList(c, CroesusStableCases.list(after, Map.of()), 15);
            List<CroesusStableCases.Frame> post = CroesusStableCases.take(c).stream()
                    .filter(f -> f.list() && f.items().equals("xxx.")).toList();
            c.check(!post.isEmpty() && post.stream().allMatch(f -> f.states().startsWith("234") && f.hidden().startsWith("-HH")),
                    "after the shift" + (restart ? " and a restart" : "") + ": " + CroesusStableCases.log(post)
                            + " (want 2 3 4, hidden -HH, from the first frame)");
            c.note("before: " + CroesusStableCases.log(pre) + "; after the shift" + (restart ? " + reload from disk" : "")
                    + ": " + CroesusStableCases.log(post) + (restart ? "; file " + file.replaceAll("\\s+", " ") : ""));
        }
    }
}

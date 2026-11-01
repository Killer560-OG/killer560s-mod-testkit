package dev.testkit.gametest.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 1101 shared-records (mod shared-stats-1101): the profit trackers and stats live in one store shared by every instance.
 * In the real client this checks that (1) a test client's store is its own sandbox in the game folder, never his real
 * %APPDATA% one; (2) an instance's old record file is folded into the store on load, without counting a claim twice,
 * and moved out of the config folder; (3) a save merges onto what another instance wrote meanwhile instead of
 * overwriting it. Each step asserts the mod acted (entry counts, the moved file), so a pass cannot be vacuous.
 */
final class SharedRecordsCases {

    private SharedRecordsCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1101-menu-shared-records", SharedRecordsCases::croesusLog);
    }

    static JsonObject claim(String ts, String chest, long profit) {
        JsonObject o = new JsonObject();
        o.addProperty("timestamp", ts);
        o.addProperty("account", "Testkit");
        o.addProperty("floor", "F7");
        o.addProperty("chest", chest);
        o.addProperty("source", "testkit");
        o.add("items", new JsonArray());
        o.addProperty("cost", 0);
        o.addProperty("value", profit);
        o.addProperty("profit", profit);
        return o;
    }

    static JsonObject log(JsonObject... claims) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 2);
        JsonArray a = new JsonArray();
        for (JsonObject c : claims) {
            a.add(c);
        }
        root.add("entries", a);
        root.add("resets", new JsonArray());
        return root;
    }

    static void croesusLog(Session c) throws Exception {
        Object kind = Mod.field("shared.RecordCatalog", "CROESUS_LOG");
        Path shared = c.onClient(mc -> (Path) Mod.staticCall("shared.SharedRecords", "pathOf", kind));
        Path gameDir = c.onClient(mc -> mc.gameDirectory.toPath().toAbsolutePath().normalize());
        Path legacy = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-croesus-log.json"));
        c.check(shared.toAbsolutePath().normalize().startsWith(gameDir),
                "the test client's shared store is inside its game folder (" + shared + "), not the real one");

        // Another instance already wrote A and B; this instance's old file holds B (the same claim) and C.
        JsonObject a = claim("2026-11-01T10:00:00Z", "Bedrock", 1000);
        JsonObject b = claim("2026-11-01T10:05:00Z", "Obsidian", 200);
        JsonObject cc = claim("2026-11-01T10:10:00Z", "Gold", 30);
        drain();
        Files.createDirectories(shared.getParent());
        Files.writeString(shared, log(a, b).toString(), StandardCharsets.UTF_8);
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, log(b, cc).toString(), StandardCharsets.UTF_8);

        c.onClient(mc -> Mod.staticCall("croesus.CroesusProfitLog", "load"));
        int count = (Integer) c.onClient(mc -> Mod.staticCall("croesus.CroesusProfitLog", "entryCount"));
        c.check(count == 3, "after folding the old file in, the log holds A, B, C once each: " + count + " claim(s)");
        c.check(!Files.exists(legacy), "the old file left the config folder: " + legacy);
        Path moved = gameDir.resolve("killer560smod-records-moved").resolve(gameDir.relativize(legacy.toAbsolutePath().normalize()));
        c.check(Files.exists(moved), "the old file is kept at " + moved);

        // Another instance adds D while this one runs; this one's next save must keep it.
        JsonObject onDisk = JsonParser.parseString(Files.readString(shared)).getAsJsonObject();
        onDisk.getAsJsonArray("entries").add(claim("2026-11-01T10:20:00Z", "Diamond", 4000));
        Files.writeString(shared, onDisk.toString(), StandardCharsets.UTF_8);
        c.onClient(mc -> Mod.staticCall("croesus.CroesusProfitLog", "saveView"));
        drain();
        JsonObject after = JsonParser.parseString(Files.readString(shared)).getAsJsonObject();
        List<String> chests = new ArrayList<>();
        for (JsonElement e : after.getAsJsonArray("entries")) {
            chests.add(e.getAsJsonObject().get("chest").getAsString() + "#" + e.getAsJsonObject().get("run").getAsInt());
        }
        c.check(chests.equals(List.of("Bedrock#1", "Obsidian#2", "Gold#3", "Diamond#4")),
                "a save kept the other instance's claim and numbered all in time order: " + chests);

        // Restart: what this instance reads now is all four.
        c.onClient(mc -> Mod.staticCall("croesus.CroesusProfitLog", "load"));
        int reread = (Integer) c.onClient(mc -> Mod.staticCall("croesus.CroesusProfitLog", "entryCount"));
        c.check(reread == 4, "a reload sees every instance's claims: " + reread);
    }

    static void drain() throws Exception {
        ((java.util.concurrent.ExecutorService) Mod.field("croesus.CroesusProfitLog", "WRITER")).submit(() -> { })
                .get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
}

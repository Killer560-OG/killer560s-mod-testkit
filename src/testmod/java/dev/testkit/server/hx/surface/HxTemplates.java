package dev.testkit.server.hx.surface;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/**
 * Hypixel-shaped sidebars and tab lists, RENDERED to the argument objects the core {@code sidebar.set} /
 * {@code tab.set} ops take. Every line here is shaped from a regex, literal or quoted comment in killer560s-mod
 * (paths relative to {@code src/main/java/com/killer560/hub/}, at mod commit f40ec89); nothing is invented beyond
 * the values a template parameter fills in. Lines no reader in the mod looks at are left out rather than guessed.
 *
 * <h2>Sidebars</h2>
 * <ul>
 *   <li>Title {@code §e§lSKYBLOCK}: util/SkyblockGate.java:96-97 (title, stripped and de-spaced, contains SKYBLOCK).</li>
 *   <li>Lobby line {@code §710/04/26 §8m12AB}: scoreboard/ScoreboardPattern.java:36 LOBBY_CODE.</li>
 *   <li>Area line {@code  §7⏣ §b<area>}: pathfinding/IslandDetector.java:37 SIDEBAR_AREA, ScoreboardPattern.java:35.</li>
 *   <li>Dungeon floor {@code  §7⏣ §cThe Catac§combs §7(F7)}: secrets/DungeonState.java:43 and the mid-word colour code
 *       its comment at :209-212 quotes.</li>
 *   <li>{@code Keys: §c■ §c✗ §6■ §a0x}: ScoreboardPattern.java:55 KEYS.</li>
 *   <li>{@code Time Elapsed: §a01m 23s}: scorecalc/ScoreCalculatorFeature.java:97, ScoreboardPattern.java:49.</li>
 *   <li>{@code Cleared: §c12% §8(31)}: ScoreCalculatorFeature.java:96, ScoreboardPattern.java:56.</li>
 *   <li>Queue: any line containing "Queue" nulls the floor - DungeonState.java:214 and its comment at :25.</li>
 *   <li>Footer {@code §ewww.hypixel.net}: ScoreboardPattern.java:39 FOOTER.</li>
 * </ul>
 *
 * <h2>Tab list</h2>
 * <ul>
 *   <li>{@code §r§b§lParty §r§f(N)} header then {@code [lvl] Name (Class Lvl)}: leapmenu/PartyTracker.java:45
 *       TAB_REGEX (also dungeonalerts/ClassColors.java:40, runsummary/RunSummaryFeature.java:84).</li>
 *   <li>{@code Dungeon: Catacombs} / {@code Area: <name>}: IslandDetector.java:35, ScoreboardPattern.java:197.</li>
 *   <li>{@code Secrets Found: N} / {@code Secrets Found: P%} / {@code Crypts: N} / {@code Completed Rooms: N} /
 *       {@code Team Deaths: N} / {@code Puzzles: (N)} / {@code Name: [✔]}: ScoreCalculatorFeature.java:86-93,
 *       dungeoninfo/DungeonInfoFeature.java:61-62.</li>
 *   <li>Footer blessings {@code Blessing of Power V}: blessings/Blessing.java:37, parsed off the FOOTER per
 *       blessings/BlessingTracker.java:6-8.</li>
 * </ul>
 * Entries are column-major (20 rows per column, like the core op): the party block fills column one.
 */
final class HxTemplates {

    private static final String TITLE = "§e§lSKYBLOCK";
    private static final String LOBBY = "§710/04/26 §8m12AB";
    private static final String FOOTER = "§ewww.hypixel.net";

    private HxTemplates() {
    }

    static JsonArray names() {
        JsonArray a = new JsonArray();
        for (String n : new String[]{"sidebar:dungeon", "sidebar:queue", "sidebar:hub", "sidebar:crystal_hollows",
                "sidebar:dwarven_mines", "tab:dungeon", "tab:area"}) {
            a.add(n);
        }
        return a;
    }

    // ---- sidebars --------------------------------------------------------------------------------------------

    static JsonElement sidebar(MinecraftServer s, JsonObject a) {
        String template = str(a, "template", "dungeon");
        List<String> lines = new ArrayList<>();
        lines.add(LOBBY);
        lines.add("");
        switch (template) {
            case "dungeon", "queue" -> {
                String floor = str(a, "floor", "F7");
                lines.add(" §7⏣ §cThe Catac§combs §7(" + floor + ")");
                if (template.equals("queue")) {
                    lines.add("§fQueue: §a" + str(a, "queue", "1/5"));
                }
                lines.add("");
                lines.add("Keys: §c■ §c✗ §6■ §a" + str(a, "keys", "0x"));
                lines.add("Time Elapsed: §a" + str(a, "time", "01m 23s"));
                lines.add("Cleared: §c" + integer(a, "cleared", 12) + "% §8(" + integer(a, "score", 31) + ")");
                if (a.has("room")) {
                    lines.add("Room: " + a.get("room").getAsString());
                }
            }
            case "hub" -> lines.add(" §7⏣ §b" + str(a, "area", "Village"));
            case "crystal_hollows" -> lines.add(" §7⏣ §5" + str(a, "area", "Crystal Nucleus"));
            case "dwarven_mines" -> lines.add(" §7⏣ §b" + str(a, "area", "Royal Mines"));
            default -> throw new IllegalArgumentException("no sidebar template '" + template + "' (have " + names() + ")");
        }
        if (a.has("extra")) {
            for (JsonElement e : a.getAsJsonArray("extra")) {
                lines.add(e.getAsString());
            }
        }
        lines.add("");
        lines.add(FOOTER);
        JsonObject out = new JsonObject();
        out.addProperty("title", str(a, "title", TITLE));
        JsonArray arr = new JsonArray();
        lines.forEach(arr::add);
        out.add("lines", arr);
        return out;
    }

    // ---- tab list --------------------------------------------------------------------------------------------

    static JsonElement tab(MinecraftServer s, JsonObject a) {
        String template = str(a, "template", "dungeon");
        int latency = integer(a, "latency", 42);
        JsonArray entries = new JsonArray();
        String footer = null;
        switch (template) {
            case "dungeon" -> {
                JsonArray players = a.has("players") ? a.getAsJsonArray("players") : new JsonArray();
                List<String> col = new ArrayList<>();
                col.add("§r§b§lParty §r§f(" + players.size() + ")");
                for (JsonElement el : players) {
                    JsonObject p = el.getAsJsonObject();
                    String rank = p.has("rank") ? p.get("rank").getAsString() + " " : "";
                    col.add("§r[" + integer(p, "lvl", 42) + "] §r" + rank + "§a" + p.get("name").getAsString()
                            + " §r§f(§r§d" + str(p, "cls", "Mage") + " " + str(p, "level", "L") + "§r§f)");
                }
                pad(col, 20);
                List<String> info = new ArrayList<>();
                info.add("§r§b§lDungeon: §r§7Catacombs");
                if (a.has("secrets")) {
                    info.add(" Secrets Found: §r§b" + a.get("secrets").getAsInt());
                }
                if (a.has("secretsPercent")) {
                    info.add(" Secrets Found: §r§b" + a.get("secretsPercent").getAsString() + "%");
                }
                if (a.has("crypts")) {
                    info.add(" Crypts: §r§6" + a.get("crypts").getAsInt());
                }
                if (a.has("rooms")) {
                    info.add(" Completed Rooms: §r§d" + a.get("rooms").getAsInt());
                }
                if (a.has("deaths")) {
                    info.add(" Team Deaths: §r§c" + a.get("deaths").getAsInt());
                }
                if (a.has("puzzles")) {
                    JsonArray puzzles = a.getAsJsonArray("puzzles");
                    info.add("§r§b§lPuzzles: §r§f(" + puzzles.size() + ")");
                    for (JsonElement el : puzzles) {
                        JsonObject p = el.getAsJsonObject();
                        info.add(" " + p.get("name").getAsString() + ": §r§7[" + str(p, "state", "✦") + "§r§7]");
                    }
                }
                pad(info, 20);
                for (String e : col) {
                    entries.add(entry(e, latency));
                }
                for (String e : info) {
                    entries.add(entry(e, latency));
                }
                if (a.has("blessings")) {
                    StringBuilder f = new StringBuilder("§r§d§lDungeon Buffs");
                    for (JsonElement b : a.getAsJsonArray("blessings")) {
                        f.append("\n§r§7").append(b.getAsString());
                    }
                    footer = f.toString();
                }
            }
            case "area" -> {
                entries.add(entry("§r§b§lArea: §r§7" + str(a, "area", "Hub"), latency));
                if (a.has("extra")) {
                    for (JsonElement e : a.getAsJsonArray("extra")) {
                        entries.add(entry(e.getAsString(), latency));
                    }
                }
            }
            default -> throw new IllegalArgumentException("no tab template '" + template + "' (have " + names() + ")");
        }
        JsonObject out = new JsonObject();
        out.add("entries", entries);
        out.addProperty("header", "§bYou are playing on §e§lMC.HYPIXEL.NET");
        out.addProperty("footer", footer != null ? footer : str(a, "footer", "§aRanks, Boosters & MORE! §c§lSTORE.HYPIXEL.NET"));
        return out;
    }

    private static JsonObject entry(String text, int latency) {
        JsonObject o = new JsonObject();
        o.addProperty("text", text);
        o.addProperty("latency", latency);
        return o;
    }

    private static void pad(List<String> col, int size) {
        if (col.size() > size) {
            throw new IllegalArgumentException("a tab column holds " + size + " rows, got " + col.size());
        }
        while (col.size() < size) {
            col.add("");
        }
    }

    private static String str(JsonObject a, String key, String fallback) {
        return a.has(key) && !a.get(key).isJsonNull() ? a.get(key).getAsString() : fallback;
    }

    private static int integer(JsonObject a, String key, int fallback) {
        return a.has(key) ? a.get(key).getAsInt() : fallback;
    }
}

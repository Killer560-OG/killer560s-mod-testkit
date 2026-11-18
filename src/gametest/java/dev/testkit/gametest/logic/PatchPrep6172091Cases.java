package dev.testkit.gametest.logic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.testkit.gametest.mod.Mod;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 1118-logic-patchprep-6172091: the mod tolerates what Hypixel's Wolf Slayer Tier V (alpha 2026-11-18) can send, with
 * today's data unchanged. A profile with a fifth Wolf tier and unknown higher tiers parses, an XP total past the table
 * stays at the top level, the sidebar reads "Sven Packmaster V" and an unknown boss name reads as no quest, and the
 * announced Bazaar renames find the old row while every current name still finds its own.
 */
public final class PatchPrep6172091Cases {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private PatchPrep6172091Cases() {
    }

    private static JsonObject profile(String wolf) {
        String key = PLAYER.toString().replace("-", "");
        return JsonParser.parseString("{\"profiles\":[{\"profile_id\":\"p1\",\"cute_name\":\"Kiwi\",\"selected\":true,"
                + "\"members\":{\"" + key + "\":{\"slayer\":{\"slayer_bosses\":{\"wolf\":" + wolf + ",\"packmaster\":{\"xp\":5},"
                + "\"zombie\":\"broken\"}}}}}]}").getAsJsonObject();
    }

    @SuppressWarnings("unchecked")
    static void run(LogicCase c) throws Exception {
        List<?> parsed = (List<?>) Mod.staticCall("profileviewer.data.SbProfile", "parseAll",
                profile("{\"xp\":99999999,\"boss_kills_tier_0\":3,\"boss_kills_tier_1\":2,\"boss_kills_tier_2\":1,"
                        + "\"boss_kills_tier_3\":4,\"boss_kills_tier_4\":7,\"boss_kills_tier_9\":1,\"boss_kills_tier_x\":5,"
                        + "\"boss_kills_tier_5\":\"many\"}"), PLAYER);
        c.check("the profile parsed (the feature acted)", parsed.size() == 1, parsed.size() + " profile(s)");
        Map<String, ?> slayers = (Map<String, ?>) Mod.field(parsed.get(0), "slayers");
        Object wolf = slayers.get("wolf");
        c.check("wolf is read and the unknown boss and a non-object boss do not break it", wolf != null
                && slayers.containsKey("packmaster") && !slayers.containsKey("zombie"), slayers.keySet().toString());
        Map<Integer, Integer> kills = (Map<Integer, Integer>) Mod.call(wolf, "kills");
        c.check("a fifth Wolf tier (key 4) is kept with its count", Integer.valueOf(7).equals(kills.get(4)), kills.toString());
        c.check("an unknown higher tier is kept, a non-number key is left out", Integer.valueOf(1).equals(kills.get(9))
                && !kills.containsKey(-1) && kills.size() >= 6, kills.toString());

        Object sven = null;
        for (Object s : (List<?>) Mod.staticCall("profileviewer.data.LevelTables", "slayers")) {
            if ("wolf".equals(Mod.call(s, "apiId"))) {
                sven = s;
            }
        }
        c.check("the Wolf slayer table is loaded", sven != null, "no wolf row");
        if (sven != null) {
            Object top = Mod.staticCall("profileviewer.data.LevelTables", "slayerLevel", sven, 5_000_000_000L);
            c.check("XP far past the table stays at the top level, maxed", (boolean) Mod.call(top, "maxed")
                    && (int) Mod.call(top, "level") >= 9, "level " + Mod.call(top, "level"));
            Object zero = Mod.staticCall("profileviewer.data.LevelTables", "slayerLevel", sven, 0L);
            c.check("zero XP is level 0", (int) Mod.call(zero, "level") == 0, "level " + Mod.call(zero, "level"));
        }

        c.check("Sven Packmaster V on the sidebar is slayer:sven:5", "slayer:sven:5".equals(Mod.staticCall("luck.LuckDetector",
                "questFrom", List.of("Slayer Quest", "Sven Packmaster V", "(0/40,000,000) Combat XP"))), "");
        c.check("Sven Packmaster IV still reads as tier 4", "slayer:sven:4".equals(Mod.staticCall("luck.LuckDetector", "questFrom",
                List.of("Slayer Quest", "Sven Packmaster IV"))), "");
        c.check("an unknown T5 boss name reads as no quest, not a crash", Mod.staticCall("luck.LuckDetector", "questFrom",
                List.of("Slayer Quest", "Packmaster Soul V")) == null, "");

        String[][] renames = {{"Epic Bait", "DARK_BAIT"}, {"Dark Bait", "DARK_BAIT"}, {"Legendary Bait", "LIGHT_BAIT"},
                {"Light Bait", "LIGHT_BAIT"}, {"Enchanted Clay", "ENCHANTED_CLAY_BLOCK"},
                {"enchanted clay block ", "ENCHANTED_CLAY_BLOCK"}};
        for (String[] r : renames) {
            Object id = Mod.staticCall("auction.BazaarCatalog", "idForName", r[0]);
            c.check("Bazaar name '" + r[0].trim() + "' finds " + r[1], r[1].equals(id), String.valueOf(id));
        }
        c.check("an unknown name and null find nothing", Mod.staticCall("auction.BazaarCatalog", "idForName", "No Such Product") == null
                && Mod.staticCall("auction.BazaarCatalog", "idForName", (Object) null) == null, "");
    }
}

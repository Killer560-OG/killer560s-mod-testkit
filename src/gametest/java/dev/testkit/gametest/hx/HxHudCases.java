package dev.testkit.gametest.hx;

import com.google.gson.JsonObject;

import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * HUD features (action bar, items, sidebar/tab readers, mining) and the command responders: 135-140.
 * Action bars are sent as overlay SYSTEM CHAT ({@code hx.overlay}), the channel docs/wp/hx.md settled.
 */
final class HxHudCases {

    /** The Hypixel commands WP2's plan lists, grepped from the mod's send sites (ServerCommands.toServer and
     *  sendCommand, killer560s-mod f40ec89) plus the ones a player types that the mod's own features reply to. */
    static final List<String> HYPIXEL_ROOTS = List.of("pc", "p", "joininstance", "instancerequeue", "warp", "skyblock",
            "pets", "trades", "bz", "ah", "gfs", "fl", "friend", "g", "dh", "storage", "wardrobe", "showextrastats", "pl");

    private HxHudCases() {
    }

    static void register(Session s) {
        playerStatsAndAbility(s);
        creeperVeil(s);
        quiver(s);
        scoreboard(s);
        nucleus(s);
        responders(s);
    }

    // ---- 135 playerstats + abilitycooldown on one action bar ------------------------------------------------------

    private static void playerStatsAndAbility(Session s) {
        s.test("135-hx-playerstats-abilitycooldown-overlay", c -> {
            Object impact = c.onClient(mc -> Mod.enumValue("abilitycooldown.ItemAbility", "ENDER_BOW"));
            Object abilityCfg = c.onClient(mc -> Mod.cfg("abilitycooldown.AbilityCooldownConfig"));
            boolean shown = c.onClient(mc -> (Boolean) Mod.call(abilityCfg, "shows", impact));
            try (Settings set = new Settings(c).with("playerstats.PlayerStatsConfig", "Enabled", true)
                    .with("abilitycooldown.AbilityCooldownConfig", "Enabled", true)
                    .with("abilitycooldown.AbilityCooldownConfig", "ActionBarDetection", true)
                    .with("abilitycooldown.AbilityCooldownConfig", "DungeonOnly", false)
                    .custom(() -> Mod.call(abilityCfg, "setAbilityEnabled", impact, true),
                            () -> Mod.call(abilityCfg, "setAbilityEnabled", impact, shown))) {
                run(c, () -> Mod.staticCall("abilitycooldown.AbilityCooldownState", "reset"));
                c.hx().overlay("§c1/1❤");
                c.waitUntil("health reset to 1/1", mc -> "1/1".equals(Mod.field("playerstats.PlayerStatsFeature", "health")), 40);
                // Forged: the same stats as normal chat from another player (not the action bar).
                c.hx().chat("§7[VIP] HxEvil§f: §c9999/9999❤ §b-50 Mana (§6Ender Warp§b)");
                c.ctx().waitTicks(10);
                c.check("1/1".equals(c.onClient(mc -> Mod.field("playerstats.PlayerStatsFeature", "health"))),
                        "chat (not the action bar) changed PlayerStats health");
                c.check(!c.onClient(mc -> (Boolean) Mod.staticCall("abilitycooldown.AbilityCooldownState", "isOnCooldown", impact)),
                        "chat (not the action bar) started Ender Warp's cooldown");
                send(c, "overlay.ability-ender-warp");
                c.waitUntil("PlayerStatsFeature.health == 1500/1500",
                        mc -> "1500/1500".equals(Mod.field("playerstats.PlayerStatsFeature", "health")), 40);
                c.waitUntil("AbilityCooldownState.isOnCooldown(ENDER_BOW) from the SAME action bar line",
                        mc -> (Boolean) Mod.staticCall("abilitycooldown.AbilityCooldownState", "isOnCooldown", impact), 40);
                Object mana = c.onClient(mc -> Mod.field("playerstats.PlayerStatsFeature", "mana"));
                c.check("900/1000".equals(mana), "mana " + mana);
                c.note("chat-borne stats ignored; one overlay line -> health 1500/1500, mana " + mana
                        + " AND the Ender Warp cooldown (both readers saw it)");
            }
        });
    }

    // ---- 136 abilitycooldown chat ---------------------------------------------------------------------------------

    private static void creeperVeil(Session s) {
        s.test("136-hx-abilitycooldown-creeper-veil", c -> {
            Object cloak = c.onClient(mc -> Mod.enumValue("abilitycooldown.ItemAbility", "WITHER_CLOAK"));
            Object abilityCfg = c.onClient(mc -> Mod.cfg("abilitycooldown.AbilityCooldownConfig"));
            boolean shown = c.onClient(mc -> (Boolean) Mod.call(abilityCfg, "shows", cloak));
            try (Settings set = new Settings(c).with("abilitycooldown.AbilityCooldownConfig", "Enabled", true)
                    .with("abilitycooldown.AbilityCooldownConfig", "DungeonOnly", false)
                    .custom(() -> Mod.call(abilityCfg, "setAbilityEnabled", cloak, true),
                            () -> Mod.call(abilityCfg, "setAbilityEnabled", cloak, shown))) {
                run(c, () -> Mod.staticCall("abilitycooldown.AbilityCooldownState", "reset"));
                send(c, "hostile.allchat-creeper-veil");
                c.ctx().waitTicks(10);
                c.check(!c.onClient(mc -> (Boolean) Mod.staticCall("abilitycooldown.AbilityCooldownState", "isOnCooldown", cloak)),
                        "a forged all-chat 'Creeper Veil Activated!' started Wither Cloak's cooldown");
                send(c, "chat.creeper-veil");
                c.waitUntil("isOnCooldown(WITHER_CLOAK)",
                        mc -> (Boolean) Mod.staticCall("abilitycooldown.AbilityCooldownState", "isOnCooldown", cloak), 40);
                long left = c.onClient(mc -> ((Number) Mod.staticCall("abilitycooldown.AbilityCooldownState", "remainingMs", cloak)).longValue());
                c.check(left > 0 && left <= 10_000, "remaining " + left + " ms (table cooldown 10 s)");
                c.note("forged ignored; real line -> Wither Cloak cooldown, " + left + " ms left");
            }
        });
    }

    // ---- 137 quiver ------------------------------------------------------------------------------------------

    private static void quiver(Session s) {
        s.test("137-hx-quiver-lore", c -> {
            try (Settings set = new Settings(c).with("quiver.QuiverDisplayConfig", "Enabled", true)) {
                // Forged: the count in the NAME, not the lore - not what the reader reads (:84-95).
                c.hx().give(5, "minecraft:feather[custom_name='Arrows Remaining: 5']");
                c.ctx().waitTicks(20);
                Object none = c.onClient(mc -> Mod.field("quiver.QuiverDisplayFeature", "cachedCount"));
                c.check(none == null, "a name-only feather produced a count " + none);
                send(c, "item.quiver-feather");
                c.hx().give(5, "minecraft:air");
                c.waitUntil("QuiverDisplayFeature.cachedCount == 1,234",
                        mc -> "1,234".equals(Mod.field("quiver.QuiverDisplayFeature", "cachedCount")), 40);
                c.note("count in the name ignored; lore 'Arrows Remaining: 1,234' -> 1,234");
            } finally {
                c.hx().give(0, "minecraft:air");
                c.hx().give(5, "minecraft:air");
            }
        });
    }

    // ---- 138 scoreboard (Custom Scoreboard's data) -----------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void scoreboard(Session s) {
        s.test("138-hx-scoreboard-data", c -> {
            try (Settings set = new Settings(c).with("scoreboard.CustomScoreboardConfig", "Enabled", true)) {
                sidebar(c, "template", "hub");
                tab(c, "template", "area", "area", "Crystal Hollows");
                c.waitUntil("ScoreboardData.island() == Crystal Hollows (tab Area:)",
                        mc -> "Crystal Hollows".equals(Mod.staticCall("scoreboard.ScoreboardData", "island")), 60);
                List<String> lines = c.onClient(mc -> new ArrayList<>((List<String>) Mod.staticCall("scoreboard.ScoreboardData", "sidebar")));
                c.check(lines.stream().anyMatch(l -> l.contains("Village")), "sidebar() lacks the area line: " + lines);
                String title = c.onClient(mc -> (String) Mod.staticCall("scoreboard.ScoreboardData", "objectiveTitle"));
                c.check(title.contains("SKYBLOCK"), "objectiveTitle " + title);
                c.hx().overlay("§bHx action bar line");
                c.waitUntil("ScoreboardData.actionBar() reads the overlay",
                        mc -> String.valueOf(Mod.staticCall("scoreboard.ScoreboardData", "actionBar")).contains("Hx action bar line"), 40);
                // Forged: the same text as CHAT is not the action bar, and an 'Area:' chat line is not the tab.
                c.hx().chat("§7[VIP] HxEvil§f: Hx forged bar");
                c.hx().chat("Area: Hub");
                c.ctx().waitTicks(20);
                String bar = c.onClient(mc -> String.valueOf(Mod.staticCall("scoreboard.ScoreboardData", "actionBar")));
                c.check(!bar.contains("forged"), "chat reached actionBar(): " + bar);
                String island = c.onClient(mc -> String.valueOf(Mod.staticCall("scoreboard.ScoreboardData", "island")));
                c.check("Crystal Hollows".equals(island), "a chat 'Area: Hub' moved the island to " + island);
                c.note("island Crystal Hollows from tab; " + lines.size() + " sidebar lines; title " + title
                        + "; overlay -> actionBar; chat never does");
            }
        });
    }

    // ---- 139 mining (Crystal Nucleus run tracker) -------------------------------------------------------------------

    private static void nucleus(Session s) {
        s.test("139-hx-mining-nucleus-runs", c -> {
            try (Settings set = new Settings(c).with("mining.nucleus.NucleusRunProfitConfig", "Enabled", true)) {
                sidebar(c, "template", "crystal_hollows");
                tab(c, "template", "area", "area", "Crystal Hollows");
                c.waitUntil("IslandDetector.graphIsland() == CRYSTAL_HOLLOWS",
                        mc -> "CRYSTAL_HOLLOWS".equals(Mod.staticCall("pathfinding.IslandDetector", "graphIsland")), 60);
                run(c, () -> Mod.setField("mining.nucleus.NucleusRunProfitTracker", "inLootBlock", false));
                long before = c.onClient(mc -> ((Number) Mod.staticCall("mining.nucleus.NucleusRunProfitTracker", "getRunsCompleted")).longValue());
                send(c, "hostile.allchat-nucleus-start");
                c.ctx().waitTicks(6);
                send(c, "hostile.allchat-nucleus-end");
                c.ctx().waitTicks(10);
                boolean open = c.onClient(mc -> (Boolean) Mod.field("mining.nucleus.NucleusRunProfitTracker", "inLootBlock"));
                long mid = c.onClient(mc -> ((Number) Mod.staticCall("mining.nucleus.NucleusRunProfitTracker", "getRunsCompleted")).longValue());
                c.check(!open && mid == before, "forged loot lines opened a block (" + open + ") or counted a run (" + before + " -> " + mid + ")");
                send(c, "chat.nucleus-loot-start");
                c.ctx().waitTicks(6);
                send(c, "chat.nucleus-loot-item");
                c.ctx().waitTicks(6);
                send(c, "chat.nucleus-loot-end");
                c.waitUntil("NucleusRunProfitTracker.getRunsCompleted() went up by one",
                        mc -> ((Number) Mod.staticCall("mining.nucleus.NucleusRunProfitTracker", "getRunsCompleted")).longValue() == before + 1, 40);
                c.note("forged loot block ignored; real block -> runs " + before + " -> " + (before + 1));
            } finally {
                sidebar(c, "template", "hub");
            }
        });
    }

    // ---- 140 Hypixel command responders -------------------------------------------------------------------------

    private static void responders(Session s) {
        s.test("140-hx-command-responders", c -> {
            // Every Hypixel root gets a responder; each answers with its own marker line. The client sends each
            // the way the mod does (below the client dispatcher, ServerCommands.toServer) and must see the answer.
            for (String root : HYPIXEL_ROOTS) {
                respond(c, root, null, "§7[hx] answered /" + root);
            }
            JsonObject status = c.hx().call("surface.status").getAsJsonObject();
            c.check(status.getAsJsonArray("responders").size() >= HYPIXEL_ROOTS.size(), "responders not installed: " + status);
            long mark = dev.testkit.gametest.LogTap.mark();
            // Spaced: vanilla kicks a client for command spam ("Kicked for spamming") at ~10 in a burst.
            for (String root : HYPIXEL_ROOTS) {
                String cmd = root.equals("p") ? "p list" : root;
                run(c, () -> Mod.staticCall("util.ServerCommands", "toServer", cmd));
                c.ctx().waitTicks(15);
            }
            c.waitUntil("a surface.reply for every root", mc -> c.events("surface.reply").size() >= HYPIXEL_ROOTS.size(), 100);
            c.ctx().waitTicks(10);
            List<String> missing = new ArrayList<>();
            for (String root : HYPIXEL_ROOTS) {
                if (logLines(mark, "[hx] answered /" + root).isEmpty()) {
                    missing.add(root);
                }
            }
            c.check(missing.isEmpty(), "answers never reached the client's chat for " + missing);
            // A rule with a 'match' only answers matching commands; 'times' expires it.
            respond(c, "pets", "pets hx(\\d+)", "§a[hx] pets page");
            c.hx().call("surface.respond", Hx.args("root", "trades", "replies", List.of("§a[hx] once"), "times", 1));
            for (String cmd : List.of("pets hx7", "trades", "trades")) {
                run(c, () -> Mod.staticCall("util.ServerCommands", "toServer", cmd));
                c.ctx().waitTicks(15);
            }
            c.waitUntil("pets page + trades once + trades fallback", mc -> c.events("surface.reply").size() >= HYPIXEL_ROOTS.size() + 3, 60);
            c.ctx().waitTicks(10);
            c.check(!logLines(mark, "[hx] pets page").isEmpty(), "the match rule did not answer /pets hx7");
            c.check(logLines(mark, "[hx] once").size() == 1, "a times=1 rule answered " + logLines(mark, "[hx] once").size() + " times");
            c.note(HYPIXEL_ROOTS.size() + " roots answered; match/times rules behave; replies seen in client chat");
            c.hx().call("surface.respond.clear");
        });
    }
}

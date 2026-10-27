package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.hx.Hx;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Map;

/**
 * 1092-1094 (mod branch clickguards-1025, dungeon-gaps-1025 item 5): Croesus extras. All legit - they drop his own click or
 * show a number. The server's own record of menu clicks ({@code menu.click}) is the proof a click did or did not go out.
 * <ul>
 *   <li>1092 Reroll Protection: a Bedrock Chest screen whose chest makes 6M (an 8M reward, 2M cost). A real mouse press on
 *   the Kismet "Reroll Chest" button (slot 50) with Protect Above at 5M never reaches the server and one chat line says
 *   why; the same press with Ctrl held does; with Protect Above at 10M a plain press does; with the setting off it does.
 *   A button saying "You already rerolled a chest!" is never blocked.</li>
 *   <li>1093 Kismet Count: three Kismet Feathers in his inventory are drawn as "3" on the reroll button; none, "0".</li>
 *   <li>1094 Croesus Chests HUD: Hypixel's tab line " Unclaimed chests: 57" shows as 57/60 on the HUD and, with Chest
 *   Warning At 55, warns once in chat; 50 shows and does not warn; 58 warns again.</li>
 * </ul>
 */
final class CroesusRerollCases {

    private CroesusRerollCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1092-menu-croesus-reroll-protect", CroesusRerollCases::protect);
        MenuSuite.test(s, "1093-menu-croesus-kismet-count", CroesusRerollCases::kismets);
        MenuSuite.test(s, "1094-menu-croesus-chests-hud", CroesusRerollCases::chestsHud);
    }

    static final String CFG = "croesus.CroesusConfig";
    static final String EXTRAS = "croesus.CroesusExtras";
    static final String TITLE = "Bedrock Chest";

    static JsonObject chestScreen(String rerollLastLine) {
        return FlipCroesusCases.menu(TITLE, 6, Map.of(
                13, FlipCroesusCases.item("minecraft:nether_star[minecraft:custom_data={id:\"TESTKIT_RELIC\"}]", "§aTestkit Relic"),
                31, FlipCroesusCases.item("minecraft:chest", "§aOpen Reward Chest", "§7Cost", "§62,000,000 Coins"),
                49, FlipCroesusCases.item("minecraft:arrow", "§aGo Back"),
                50, FlipCroesusCases.item("minecraft:feather", "§aReroll Chest", "§7Rerolls the contents of this chest.", "",
                        rerollLastLine)),
                Map.of(50, new JsonObject(), 31, new JsonObject()));
    }

    static AutoCloseable prices(Session c) {
        c.ctx().runOnClient(mc -> Mod.call(Mod.field("rngmeter.RngMeterEngine", "PRICES"), "putBazaarPriceForTest",
                "TESTKIT_RELIC", 8_000_000L, 8_000_000L));
        return () -> c.ctx().runOnClient(mc -> Mod.call(Mod.field("rngmeter.RngMeterEngine", "PRICES"),
                "removeBazaarPriceForTest", "TESTKIT_RELIC"));
    }

    static void open(Session c, JsonObject screen) {
        MenuKit.reset(c);
        MenuKit.show(c, screen);
        MenuKit.awaitScreen(c, TITLE, 100);
        c.ctx().waitTicks(8);
    }

    /** A real mouse press on slot 50; returns how many clicks the server recorded on it. */
    static int press(Session c) {
        int before = FlipCroesusCases.clicksOn(c, TITLE).stream().filter(s -> s == 50).toList().size();
        CroesusUiCases.hover(c, 50);
        c.ctx().getInput().pressMouse(0);
        c.ctx().waitTicks(10);
        return (int) FlipCroesusCases.clicksOn(c, TITLE).stream().filter(s -> s == 50).count() - before;
    }

    static int blocked(Session c) {
        return c.onClient(mc -> (Integer) Mod.staticCall(EXTRAS, "blockedRerolls"));
    }

    static long chat(long mark, String needle) {
        return LogTap.since(mark).stream().filter(l -> l.contains("[CHAT]") && l.contains(needle)).count();
    }

    static void protect(Session c) throws Exception {
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c); AutoCloseable p = prices(c)) {
            cfg.set(CFG, "PriceMode", Mod.enumValue("croesus.CroesusConfig$PriceMode", "INSTA_SELL"))
                    .set(CFG, "RerollProtection", true).set(CFG, "RerollProtectAboveK", 5_000);
            open(c, chestScreen("§eClick to reroll this chest!"));
            long profit = c.onClient(mc -> {
                var s = (net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) dev.testkit.compat.McCompat.screen(mc);
                Object type = Mod.staticCall("croesus.DungeonChestValuer$ChestType", "fromName", TITLE);
                Object v = Mod.staticCall("croesus.DungeonChestValuer", "fromChestScreen", type,
                        Mod.staticCall("croesus.ChestProfitFeature", "containerStacks", s));
                return (Long) Mod.call(v, "profit");
            });
            c.note("the chest's profit as the mod values it: " + profit);
            c.check(profit == 6_000_000L, "the fixture chest is worth " + profit + ", want 6M (8M reward, 2M cost)");
            int b0 = blocked(c);
            long mark = LogTap.mark();
            int first = press(c);
            int second = press(c);
            int afterBlocked = blocked(c) - b0;
            long lines = chat(mark, "Reroll blocked");
            c.note("Protect Above 5M: presses reached the server " + first + " + " + second + " time(s); blocked "
                    + afterBlocked + "; chat lines " + lines);
            c.check(first == 0 && second == 0, "a reroll of a 6M chest reached the server with Protect Above at 5M: "
                    + FlipCroesusCases.allClicks(c));
            c.check(afterBlocked == 2, "blocked " + afterBlocked + ", want 2");
            c.check(lines == 1, lines + " chat lines for two blocked presses, want 1");

            // Ctrl held: through.
            c.ctx().getInput().holdKey(GLFW.GLFW_KEY_LEFT_CONTROL);
            c.ctx().waitTicks(2);
            int ctrl;
            try {
                ctrl = press(c);
            } finally {
                c.ctx().getInput().releaseKey(GLFW.GLFW_KEY_LEFT_CONTROL);
            }
            c.note("with Ctrl held: " + ctrl + " click(s) reached the server");
            c.check(ctrl == 1, "Ctrl did not let the reroll through (" + ctrl + " clicks)");

            // Threshold above the profit: through.
            open(c, chestScreen("§eClick to reroll this chest!"));
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setRerollProtectAboveK", 10_000));
            int under = press(c);
            c.check(under == 1, "a 6M chest's reroll was blocked with Protect Above at 10M (" + under + " clicks)");

            // A reroll the button already refuses is left alone.
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setRerollProtectAboveK", 5_000));
            open(c, chestScreen("§cYou already rerolled a chest!"));
            int used = press(c);
            c.check(used == 1, "an already-used reroll button was blocked (" + used + " clicks)");

            // Off: through.
            open(c, chestScreen("§eClick to reroll this chest!"));
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setRerollProtection", false));
            int off = press(c);
            c.check(off == 1, "with Reroll Protection off the press did not reach the server (" + off + " clicks)");
            // The claim button beside it was never touched by the guard.
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setRerollProtection", true));
            int claimBefore = (int) FlipCroesusCases.clicksOn(c, TITLE).stream().filter(s -> s == 31).count();
            CroesusUiCases.hover(c, 31);
            c.ctx().getInput().pressMouse(0);
            c.ctx().waitTicks(10);
            int claim = (int) FlipCroesusCases.clicksOn(c, TITLE).stream().filter(s -> s == 31).count() - claimBefore;
            c.check(claim == 1, "the claim button's click did not reach the server with Reroll Protection on");
        } finally {
            MenuKit.reset(c);
        }
    }

    static void kismets(Session c) throws Exception {
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CFG, "KismetCount", true);
            c.hx().call("give", "slot", 20, "stack", "minecraft:feather[minecraft:custom_data={id:\"KISMET_FEATHER\"}]",
                    "count", 3);
            c.waitUntil("three Kismet Feathers counted", mc -> (Integer) Mod.staticCall(EXTRAS, "kismets") == 3, 60);
            open(c, chestScreen("§eClick to reroll this chest!"));
            c.ctx().runOnClient(mc -> Mod.setField(Mod.ROOT + EXTRAS, "lastDrawnKismetText", null));
            c.waitUntil("the count drawn on the reroll button",
                    mc -> Mod.staticCall(EXTRAS, "lastDrawnKismetText") != null, 40);
            String drawn = c.onClient(mc -> (String) Mod.staticCall(EXTRAS, "lastDrawnKismetText"));
            c.note("drawn on the reroll button with 3 feathers: \"" + drawn + "\"");
            c.check("3".equals(drawn), "drew \"" + drawn + "\", want 3");
            CroesusUiCases.shot(c, "kismet-3");
            MenuKit.reset(c);
            c.hx().give(20, "minecraft:air");
            c.waitUntil("no Kismet Feathers counted", mc -> (Integer) Mod.staticCall(EXTRAS, "kismets") == 0, 60);
            open(c, chestScreen("§eClick to reroll this chest!"));
            c.ctx().runOnClient(mc -> Mod.setField(Mod.ROOT + EXTRAS, "lastDrawnKismetText", null));
            c.waitUntil("the count drawn again", mc -> Mod.staticCall(EXTRAS, "lastDrawnKismetText") != null, 40);
            String none = c.onClient(mc -> (String) Mod.staticCall(EXTRAS, "lastDrawnKismetText"));
            c.check("0".equals(none), "drew \"" + none + "\" with no feathers, want 0");
            // Off: nothing drawn.
            c.ctx().runOnClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setKismetCount", false);
                Mod.setField(Mod.ROOT + EXTRAS, "lastDrawnKismetText", null);
            });
            c.ctx().waitTicks(10);
            Object offDrawn = c.onClient(mc -> Mod.staticCall(EXTRAS, "lastDrawnKismetText"));
            c.check(offDrawn == null, "with Kismet Count off it still drew \"" + offDrawn + "\"");
        } finally {
            c.hx().give(20, "minecraft:air");
            MenuKit.reset(c);
        }
    }

    static List<String> hud(Session c) {
        @SuppressWarnings("unchecked")
        List<String> l = c.onClient(mc -> (List<String>) Mod.staticCall(EXTRAS, "lastHudLines"));
        return l;
    }

    /** On the client thread already (a waitUntil predicate): read it directly. */
    @SuppressWarnings("unchecked")
    static List<String> hudNow() {
        return (List<String>) Mod.staticCall(EXTRAS, "lastHudLines");
    }

    static void chestsHud(Session c) throws Exception {
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            MenuKit.reset(c);
            cfg.set(CFG, "ChestCountHud", true).set(CFG, "ChestWarnAt", 55);
            c.ctx().runOnClient(mc -> {
                Mod.setField(Mod.ROOT + EXTRAS, "warnedAt", -1);
                Mod.setField(Mod.ROOT + EXTRAS, "lastHudLines", List.of());
            });
            long mark = LogTap.mark();
            c.hx().tab(List.of("§r§b§lDungeons:§r", "§r Unclaimed chests: §r§e57§r", "§r Kismet Feathers: ?"), null, null);
            c.waitUntil("the HUD to show 57", mc -> hudNow().stream().anyMatch(l -> l.contains("57")), 100);
            c.ctx().waitTicks(30);
            long warns57 = chat(mark, "57/60 unclaimed chests");
            List<String> lines57 = hud(c);
            c.note("tab 57: HUD " + lines57 + ", warnings " + warns57);
            c.check(lines57.stream().anyMatch(l -> l.contains("§c57§7/60")), "57 is not drawn red as over the warning: " + lines57);
            c.check(warns57 == 1, warns57 + " warnings at 57, want exactly 1 (it reads the tab twice a second)");

            c.hx().tab(List.of("§r Unclaimed chests: §r§e50§r"), null, null);
            c.waitUntil("the HUD to show 50", mc -> hudNow().stream().anyMatch(l -> l.contains("50")), 100);
            long warns50 = chat(mark, "50/60 unclaimed chests");
            c.check(warns50 == 0, "warned at 50 with the warning at 55");
            c.check(hud(c).stream().anyMatch(l -> l.contains("§a50§7/60")), "50 is not drawn green: " + hud(c));

            c.hx().tab(List.of("§r Unclaimed chests: §r§e58§r"), null, null);
            c.waitUntil("the HUD to show 58", mc -> hudNow().stream().anyMatch(l -> l.contains("58")), 100);
            c.ctx().waitTicks(10);
            long warns58 = chat(mark, "58/60 unclaimed chests");
            c.check(warns58 == 1, warns58 + " warnings at 58, want 1");
            // A player-shaped tab name cannot set it.
            c.hx().tab(List.of("§r[MVP+] Bob: Unclaimed chests: 3"), null, null);
            c.ctx().waitTicks(30);
            int now = c.onClient(mc -> (Integer) Mod.staticCall(EXTRAS, "unclaimed"));
            c.check(now == 58, "a tab entry not shaped like Hypixel's line moved the count to " + now);
            c.check(hud(c).stream().anyMatch(l -> l.startsWith("§6Kismets: ")), "no Kismets line: " + hud(c));
        } finally {
            c.hx().tabClear();
        }
    }
}

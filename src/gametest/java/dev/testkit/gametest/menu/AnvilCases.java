package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Auto Anvil (mod {@code autoanvil.AutoAnvilFeature}) against the fake Hypixel Anvil ({@code menu.anvil}, server
 * {@code hx/menu/HxAnvil}). The fake anvil combines ANY two items it is given and records each as an
 * {@code anvil.combine} event naming both inputs, so these cases assert on what the server actually combined: the only
 * combines allowed are two identical single-enchant books at the same tier where the anvil makes the next level.
 *
 * <ul>
 *   <li>292 pairs: exact pairs combined (Sharpness IV, Ultimate Wise I); a different enchant, a different tier, a
 *       multi-enchant book (mismatched and identical), a plain item, a vanilla book with no SkyBlock data, a paper
 *       carrying book data under another id, capped pairs (Sharpness V, Looting III), a protected book and a
 *       recombobulated pair are never put in the anvil</li>
 *   <li>293 cascade: four Sharpness III become one V (III+III, III+III, IV+IV); with Combine Results Again off they
 *       stop at two IV</li>
 *   <li>294 direct: a server that hands the result straight to the inventory and shows no preview - no claim click
 *       is sent</li>
 *   <li>295 preview mismatch: the anvil previews a different book - nothing is combined, the books are returned</li>
 *   <li>296 close: the anvil closes mid-run - the feature stops and clicks nothing more</li>
 *   <li>297 off / legit: setting off sends nothing; on the legit jar, setting on still sends nothing</li>
 * </ul>
 */
final class AnvilCases {

    private static final String CFG = "autoanvil.AutoAnvilConfig";
    private static final String FEATURE = "autoanvil.AutoAnvilFeature";

    private AnvilCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "292-menu-anvil-pairs", AnvilCases::pairs);
        MenuSuite.test(s, "293-menu-anvil-cascade", AnvilCases::cascade);
        MenuSuite.test(s, "294-menu-anvil-direct-no-preview", AnvilCases::direct);
        MenuSuite.test(s, "295-menu-anvil-preview-mismatch", AnvilCases::previewMismatch);
        MenuSuite.test(s, "296-menu-anvil-close-stops", AnvilCases::closeStops);
        MenuSuite.test(s, "297-menu-anvil-off-and-legit", AnvilCases::offAndLegit);
    }

    // ---- stacks --------------------------------------------------------------------------------------------------

    private static int uuid = 0;

    /** A SkyBlock enchanted book as Hypixel sends it: ExtraAttributes as custom_data, id ENCHANTED_BOOK. */
    static String book(String enchants) {
        return book(enchants, "", "b" + (++uuid));
    }

    static String book(String enchants, String extra, String id) {
        return "minecraft:enchanted_book[custom_data={id:\"ENCHANTED_BOOK\",enchantments:{" + enchants + "},uuid:\""
                + id + "\"" + extra + "},custom_name={text:\"Enchanted Book\",italic:false}]";
    }

    static void give(Session c, int slot, String stack) {
        c.hx().call("give", "slot", slot, "stack", stack);
    }

    static MenuKit.Cfg on(Session c) {
        MenuKit.Cfg cfg = new MenuKit.Cfg(c);
        cfg.set(CFG, "Enabled", true).set(CFG, "MinDelayMs", 60).set(CFG, "MaxDelayMs", 100)
                .set(CFG, "Cascade", true).set(CFG, "CloseWhenDone", false);
        return cfg;
    }

    static void openAnvil(Session c, String argsJson) {
        c.hx().call("menu.anvil", MenuKit.obj(argsJson));
        MenuKit.awaitScreen(c, "Anvil", 100);
    }

    static boolean running(Session c) {
        return c.onClient(mc -> (Boolean) Mod.staticCall(FEATURE, "isRunning"));
    }

    /** Wait for {@code n} combines, then for the feature to finish, then 30 more ticks to catch any stray click. */
    static void awaitCombines(Session c, int n, int ticks) {
        c.waitUntil(n + " anvil.combine event(s)", mc -> c.events("anvil.combine").size() >= n, ticks);
        c.waitUntil("Auto Anvil to finish", mc -> !(Boolean) Mod.staticCall(FEATURE, "isRunning"), 200);
        c.ctx().waitTicks(30);
    }

    static List<String> combines(Session c) {
        List<String> out = new ArrayList<>();
        for (JsonObject e : c.events("anvil.combine")) {
            out.add(e.get("left").getAsString() + "+" + e.get("right").getAsString() + "=" + e.get("result").getAsString());
        }
        return out;
    }

    static List<String> inserted(Session c) {
        List<String> out = new ArrayList<>();
        c.events("anvil.insert").forEach(e -> out.add(e.get("item").getAsString()));
        return out;
    }

    /** Every SkyBlock book left in the inventory, as "sharpness5" etc., sorted. */
    static List<String> booksInInventory(Session c) {
        List<String> out = c.onClient(mc -> {
            List<String> l = new ArrayList<>();
            for (int i = 0; i < 36; i++) {
                ItemStack s = mc.player.getInventory().getItem(i);
                var d = s.get(DataComponents.CUSTOM_DATA);
                if (d == null) {
                    continue;
                }
                var t = d.copyTag();
                if (!"ENCHANTED_BOOK".equals(t.getStringOr("id", ""))) {
                    continue;
                }
                var e = t.getCompoundOrEmpty("enchantments");
                List<String> parts = new ArrayList<>();
                for (String k : new java.util.TreeSet<>(e.keySet())) {
                    parts.add(k + e.getIntOr(k, 0));
                }
                l.add(String.join("+", parts));
            }
            return l;
        });
        Collections.sort(out);
        return out;
    }

    // ---- cases ---------------------------------------------------------------------------------------------------

    static void pairs(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: covered by 297");
            return;
        }
        Object protectCfg = c.onClient(mc -> Mod.cfg("itemprotect.ItemProtectConfig"));
        try (MenuKit.Cfg cfg = on(c)) {
            cfg.set("itemprotect.ItemProtectConfig", "Enabled", true).set("itemprotect.ItemProtectConfig", "ProtectItemEnabled", true);
            boolean had = c.onClient(mc -> (Boolean) Mod.call(protectCfg, "hasProtectedKey", "anvil-prot-1"));
            if (!had) {
                c.ctx().runOnClient(mc -> Mod.call(protectCfg, "toggleProtectedKey", "anvil-prot-1"));
            }
            int s = 0;
            // the two exact pairs
            give(c, s++, book("sharpness:4"));
            give(c, s++, book("ultimate_wise:1"));
            give(c, s++, book("sharpness:4"));
            give(c, s++, book("ultimate_wise:1"));
            // different enchant, same tier as the pair above: one Smite IV alone
            give(c, s++, book("smite:4"));
            // different tier of the same enchant: one Sharpness III alone
            give(c, s++, book("sharpness:3"));
            // multi-enchant: a mismatched pair and an identical pair - neither is a candidate
            give(c, s++, book("sharpness:2,smite:2"));
            give(c, s++, book("sharpness:2,smite:1"));
            give(c, s++, book("critical:2,cubism:2"));
            give(c, s++, book("critical:2,cubism:2"));
            // over the anvil's cap: two Sharpness V, two Looting III
            give(c, s++, book("sharpness:5"));
            give(c, s++, book("sharpness:5"));
            give(c, s++, book("looting:3"));
            give(c, s++, book("looting:3"));
            // a pair whose one half is protected by Item Protect (uuid)
            give(c, s++, book("power:2", "", "anvil-prot-1"));
            give(c, s++, book("power:2"));
            // a recombobulated pair
            give(c, s++, book("growth:2", ",rarity_upgrades:1", "rg1"));
            give(c, s++, book("growth:2", ",rarity_upgrades:1", "rg2"));
            // not books: a plain item, a vanilla enchanted book with no SkyBlock data, and paper carrying book data
            // under another id (all twice)
            give(c, s++, "minecraft:diamond");
            give(c, s++, "minecraft:diamond");
            give(c, s++, "minecraft:enchanted_book");
            give(c, s++, "minecraft:enchanted_book");
            give(c, s++, "minecraft:paper[custom_data={id:\"NOT_A_BOOK\",enchantments:{sharpness:4}}]");
            give(c, s++, "minecraft:paper[custom_data={id:\"NOT_A_BOOK\",enchantments:{sharpness:4}}]");
            c.ctx().waitTicks(5);
            List<String> before = booksInInventory(c);
            openAnvil(c, "{}");
            awaitCombines(c, 2, 600);
            List<String> combined = combines(c);
            List<String> ins = inserted(c);
            List<String> after = booksInInventory(c);
            List<String> sorted = new ArrayList<>(combined);
            Collections.sort(sorted);
            c.check(sorted.equals(List.of("sharpness4+sharpness4=sharpness5", "ultimate_wise1+ultimate_wise1=ultimate_wise2")),
                    "server-side combines " + combined + ", expected exactly Sharpness IV x2 and Ultimate Wise I x2");
            List<String> insSorted = new ArrayList<>(ins);
            Collections.sort(insSorted);
            c.check(insSorted.equals(List.of("sharpness4", "sharpness4", "ultimate_wise1", "ultimate_wise1")),
                    "books put in the anvil " + ins + " - something other than the two exact pairs went in");
            c.check(after.contains("sharpness5") && after.contains("ultimate_wise2"), "results not in the inventory: " + after);
            int claims = c.events("anvil.claim").size();
            c.check(claims == 2, "claims " + claims + ", expected one per combine");
            c.note("before " + before.size() + " books; combined " + combined + "; put in " + ins + "; left untouched: "
                    + "smite4, sharpness3, both multi-enchant pairs, sharpness5 x2, looting3 x2, protected power2, "
                    + "recombobulated growth2 x2, diamonds, vanilla books, NOT_A_BOOK paper; " + MenuKit.cadence(c));
            if (!had) {
                c.ctx().runOnClient(mc -> Mod.call(protectCfg, "toggleProtectedKey", "anvil-prot-1"));
            }
        } finally {
            MenuKit.reset(c);
            InventoryCases.clearInv(c);
        }
    }

    static void cascade(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: covered by 297");
            return;
        }
        try (MenuKit.Cfg cfg = on(c)) {
            for (int i = 0; i < 4; i++) {
                give(c, i * 3, book("sharpness:3"));
            }
            c.ctx().waitTicks(5);
            openAnvil(c, "{}");
            awaitCombines(c, 3, 900);
            List<String> on = combines(c);
            List<String> invOn = booksInInventory(c);
            c.check(on.equals(List.of("sharpness3+sharpness3=sharpness4", "sharpness3+sharpness3=sharpness4",
                    "sharpness4+sharpness4=sharpness5")), "cascade ON combines " + on);
            c.check(invOn.equals(List.of("sharpness5")), "cascade ON left " + invOn + ", expected one Sharpness V");
            c.note("Combine Results Again ON: " + on + " -> " + invOn + "; " + MenuKit.cadence(c));
        } finally {
            MenuKit.reset(c);
            InventoryCases.clearInv(c);
        }
        // Off: the two IV it makes are not fed back in.
        try (MenuKit.Cfg cfg = on(c)) {
            cfg.set(CFG, "Cascade", false);
            for (int i = 0; i < 4; i++) {
                give(c, i * 3, book("sharpness:3"));
            }
            c.ctx().waitTicks(5);
            int before = c.events("anvil.combine").size();
            openAnvil(c, "{}");
            awaitCombines(c, before + 2, 900);
            List<String> all = combines(c);
            List<String> off = all.subList(before, all.size());
            List<String> invOff = booksInInventory(c);
            c.check(off.equals(List.of("sharpness3+sharpness3=sharpness4", "sharpness3+sharpness3=sharpness4")),
                    "cascade OFF combines " + off);
            c.check(invOff.equals(List.of("sharpness4", "sharpness4")), "cascade OFF left " + invOff);
            c.note("Combine Results Again OFF: " + off + " -> " + invOff);
        } finally {
            MenuKit.reset(c);
            InventoryCases.clearInv(c);
        }
    }

    static void direct(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: covered by 297");
            return;
        }
        try (MenuKit.Cfg cfg = on(c)) {
            give(c, 0, book("ultimate_soul_eater:3"));
            give(c, 5, book("ultimate_soul_eater:3"));
            c.ctx().waitTicks(5);
            openAnvil(c, "{\"claim\":\"direct\",\"preview\":false}");
            awaitCombines(c, 1, 600);
            List<String> combined = combines(c);
            int clicks = c.events("container.click").size();
            c.check(combined.equals(List.of("ultimate_soul_eater3+ultimate_soul_eater3=ultimate_soul_eater4")),
                    "combines " + combined);
            c.check(booksInInventory(c).equals(List.of("ultimate_soul_eater4")), "inventory " + booksInInventory(c));
            c.check(clicks == 3, clicks + " clicks for one pair delivered straight to the inventory, expected 3 (two shift-clicks, one combine)");
            c.note("direct delivery, no preview: " + combined + " in " + clicks + " clicks; " + MenuKit.cadence(c));
        } finally {
            MenuKit.reset(c);
            InventoryCases.clearInv(c);
        }
    }

    static void previewMismatch(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: covered by 297");
            return;
        }
        try (MenuKit.Cfg cfg = on(c)) {
            give(c, 0, book("sharpness:4"));
            give(c, 1, book("sharpness:4"));
            c.ctx().waitTicks(5);
            JsonObject args = MenuKit.obj("{}");
            args.addProperty("previewOverride", book("smite:5", "", "preview"));
            c.hx().call("menu.anvil", args);
            MenuKit.awaitScreen(c, "Anvil", 100);
            c.waitUntil("both books in the anvil", mc -> c.events("anvil.insert").size() >= 2, 300);
            c.waitUntil("Auto Anvil to stop", mc -> !(Boolean) Mod.staticCall(FEATURE, "isRunning"), 200);
            c.ctx().waitTicks(30);
            List<String> combined = combines(c);
            long combineClicks = c.events("container.click").stream().filter(e -> e.get("slot").getAsInt() == 22).count();
            c.check(combined.isEmpty(), "combined despite a mismatching preview: " + combined);
            c.check(combineClicks == 0, combineClicks + " click(s) on Combine Items despite a mismatching preview");
            MenuKit.reset(c);
            List<String> back = booksInInventory(c);
            c.check(back.equals(List.of("sharpness4", "sharpness4")), "books after closing the anvil: " + back);
            c.note("preview showed smite5 for two sharpness4: stopped before Combine Items, 0 combines, books returned on close");
        } finally {
            MenuKit.reset(c);
            InventoryCases.clearInv(c);
        }
    }

    static void closeStops(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: covered by 297");
            return;
        }
        try (MenuKit.Cfg cfg = on(c)) {
            cfg.set(CFG, "MinDelayMs", 300).set(CFG, "MaxDelayMs", 400);
            String[] kinds = {"protection:1", "protection:2", "smite:1", "smite:2", "power:1"};
            int s = 0;
            for (String k : kinds) {
                give(c, s++, book(k));
                give(c, s++, book(k));
            }
            c.ctx().waitTicks(5);
            openAnvil(c, "{}");
            c.waitUntil("the first combine", mc -> !c.events("anvil.combine").isEmpty(), 600);
            c.hx().call("menu.close");
            c.waitUntil("Auto Anvil to stop", mc -> !(Boolean) Mod.staticCall(FEATURE, "isRunning"), 100);
            int clicksAtStop = c.events("container.click").size();
            c.ctx().waitTicks(60);
            int clicksLater = c.events("container.click").size();
            int combinesLater = c.events("anvil.combine").size();
            c.check(clicksLater == clicksAtStop, (clicksLater - clicksAtStop) + " click(s) after the anvil closed");
            c.check(combinesLater < kinds.length, "every pair combined although the anvil closed after the first");
            c.note("anvil closed after combine 1: stopped; " + clicksAtStop + " clicks total, none after; "
                    + combinesLater + " of " + kinds.length + " pairs combined");
        } finally {
            MenuKit.reset(c);
            InventoryCases.clearInv(c);
        }
    }

    static void offAndLegit(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            boolean cheat = MenuKit.cheat();
            // cheat jar: the toggle OFF. legit jar: the toggle ON, which must still do nothing.
            cfg.set(CFG, "Enabled", !cheat);
            give(c, 0, book("sharpness:4"));
            give(c, 1, book("sharpness:4"));
            c.ctx().waitTicks(5);
            openAnvil(c, "{}");
            c.ctx().waitTicks(100);
            int clicks = c.events("container.click").size();
            c.check(clicks == 0, clicks + " click(s) in the anvil with " + (cheat ? "Auto Anvil off" : "the legit jar"));
            c.check(c.events("anvil.combine").isEmpty(), "combined with " + (cheat ? "Auto Anvil off" : "the legit jar"));
            if (!cheat) {
                boolean registered = c.onClient(mc -> (Boolean) Mod.staticCall(FEATURE, "isRunning"));
                c.check(!registered, "legit jar reports a running Auto Anvil session");
            }
            c.note((cheat ? "cheat jar, Auto Anvil OFF" : "legit jar, Auto Anvil toggled ON") + ": 0 clicks in 100 ticks with a pair present");
        } finally {
            MenuKit.reset(c);
            InventoryCases.clearInv(c);
        }
    }
}

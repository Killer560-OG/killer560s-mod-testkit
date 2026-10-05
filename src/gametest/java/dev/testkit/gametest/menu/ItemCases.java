package dev.testkit.gametest.menu;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Items as the server hands them out (custom_data, lore, names - every format from an items/ fixture citing its
 * reader), read back through the mod's own readers on the client.
 */
final class ItemCases {

    private ItemCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "260-menu-item-rarity", ItemCases::rarity);
        MenuSuite.test(s, "261-menu-item-enchantcolors", ItemCases::enchantColors);
        MenuSuite.test(s, "262-menu-item-revertmasterstars", ItemCases::masterStars);
        MenuSuite.test(s, "263-menu-item-armourdye", ItemCases::armourDye);
        MenuSuite.test(s, "264-menu-item-helditem-swing", ItemCases::heldItem);
        MenuSuite.test(s, "265-menu-item-tooltipscroll", ItemCases::tooltipScroll);
        MenuSuite.test(s, "266-menu-item-inventorytheme", ItemCases::inventoryTheme);
        MenuSuite.test(s, "267-menu-item-inventoryhud", ItemCases::inventoryHud);
        MenuSuite.test(s, "268-menu-item-inventorysearch", ItemCases::inventorySearch);
        MenuSuite.test(s, "269-menu-item-readers", ItemCases::readers);
        MenuSuite.test(s, "270-menu-item-protect-starred-name", ItemCases::starredName);
    }

    /** Put a fixture's stack in hotbar slot {@code slot} and return the client's copy once it arrived. */
    static ItemStack give(Session c, int slot, String fixtureId) {
        c.hx().give(slot, MenuKit.item(fixtureId));
        c.waitUntil(fixtureId + " in slot " + slot, mc -> !mc.player.getInventory().getItem(slot).isEmpty(), 60);
        c.ctx().waitTicks(2);
        return c.onClient(mc -> mc.player.getInventory().getItem(slot));
    }

    static void clear(Session c, int... slots) {
        for (int s : slots) {
            c.hx().call("give", "slot", s, "stack", "minecraft:air");
        }
    }

    static void rarity(Session c) throws Exception {
        Map<String, String> expect = new LinkedHashMap<>();
        expect.put("items.hyperion-legendary-lore", "LEGENDARY");
        expect.put("items.pet-golden-dragon-tierboost", "LEGENDARY");
        expect.put("items.pet-name-lvl-gold", "LEGENDARY");
        expect.put("items.tooltip-style-mythic", "MYTHIC");
        List<String> got = new ArrayList<>();
        try {
            for (var e : expect.entrySet()) {
                ItemStack stack = give(c, 0, e.getKey());
                String r = c.onClient(mc -> String.valueOf(Mod.staticCall("itemrarity.ItemRarityFeature", "getRarity", stack)));
                got.add(e.getKey().substring(6) + "=" + r);
                c.check(e.getValue().equals(r), e.getKey() + ": getRarity = " + r + ", expected " + e.getValue());
                clear(c, 0);
            }
            c.hx().give(0, "minecraft:stone");
            c.ctx().waitTicks(5);
            Object none = c.onClient(mc -> Mod.staticCall("itemrarity.ItemRarityFeature", "getRarity", mc.player.getInventory().getItem(0)));
            c.check(none == null, "plain stone has rarity " + none);
            c.note("ItemRarityFeature.getRarity: " + got + "; plain stone -> null");
        } finally {
            clear(c, 0);
        }
    }

    /** Every literal segment of a component with its colour. */
    static Map<String, Integer> segments(List<Component> lines) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Component line : lines) {
            line.visit((Style style, String text) -> {
                if (!text.isBlank()) {
                    TextColor col = style.getColor();
                    out.put(text + (style.isBold() ? "[b]" : ""), col == null ? -1 : col.getValue());
                }
                return Optional.empty();
            }, Style.EMPTY);
        }
        return out;
    }

    static Integer colourOf(Map<String, Integer> segs, String contains) {
        for (var e : segs.entrySet()) {
            if (e.getKey().contains(contains)) {
                return e.getValue();
            }
        }
        return null;
    }

    static void enchantColors(Session c) throws Exception {
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("enchantcolors.EnchantColorsConfig", "Enabled", true);
            ItemStack stack = give(c, 0, "items.sword-enchants-sharp6-wise5");
            Map<String, Integer> segs = c.onClient(mc -> {
                Mod.staticCall("enchantcolors.EnchantColorsFeature", "clearCache");
                return segments(Screen.getTooltipFromItem(mc, stack));
            });
            Integer sharp = colourOf(segs, "Sharpness");
            Integer wise = colourOf(segs, "Ultimate Wise");
            c.check(sharp != null && sharp == 0xFFAA00, "Sharpness VI (level 6, good 5 / max 7) coloured "
                    + (sharp == null ? "null" : Integer.toHexString(sharp)) + ", expected GREAT ffaa00; segments " + segs);
            c.check(wise != null && wise == 0xFF55FF, "Ultimate Wise V coloured " + (wise == null ? "null" : Integer.toHexString(wise))
                    + ", expected ULTIMATE ff55ff");
            c.note("enchant colours: Sharpness VI -> " + Integer.toHexString(sharp) + ", Ultimate Wise V -> " + Integer.toHexString(wise));
        } finally {
            clear(c, 0);
        }
    }

    static void masterStars(Session c) throws Exception {
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("revertmasterstars.RevertMasterStarsConfig", "Enabled", true);
            ItemStack stack = give(c, 0, "items.hyperion-master-3");
            String plain = c.onClient(mc -> stack.getHoverName().getString());
            Map<String, Integer> segs = c.onClient(mc -> segments(List.of(stack.getHoverName())));
            c.check(plain.equals("Hyperion ✪✪✪✪✪"), "hover name '" + plain + "', expected the pip removed");
            int red = 0;
            for (var e : segs.entrySet()) {
                if (e.getKey().contains("✪") && e.getValue() == 0xFF5555) {
                    red += (int) e.getKey().chars().filter(ch -> ch == '✪').count();
                }
            }
            c.check(red == 3, "red stars " + red + " of the pip's 3; segments " + segs);
            c.note("master stars: '" + plain + "', " + red + " red star(s)");
        } finally {
            clear(c, 0);
        }
    }

    static void armourDye(Session c) throws Exception {
        Object cfgObj = c.onClient(mc -> Mod.cfg("armourdye.ArmourDyeConfig"));
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("armourdye.ArmourDyeConfig", "Enabled", true);
            c.ctx().runOnClient(mc -> {
                Object entry = Mod.call(cfgObj, "getOrCreate", "NECRON_CHESTPLATE", "hx");
                setPublic(entry, "colorEnabled", true);
                setPublic(entry, "color", 0xFF00FF00);
                Mod.staticCall("armourdye.ArmourDye", "invalidate");
            });
            ItemStack stack = give(c, 38, "items.necron-chestplate-starred");
            String id = c.onClient(mc -> (String) Mod.staticCall("armourdye.ArmourDye", "identityOf", stack));
            int colour = c.onClient(mc -> DyedItemColor.getOrDefault(stack, 0));
            c.check("NECRON_CHESTPLATE".equals(id), "identityOf = " + id);
            c.check(colour == 0xFF00FF00, "dye colour " + Integer.toHexString(colour) + ", entry says ff00ff00");
            c.note("armour dye: identity " + id + ", DyedItemColor -> " + Integer.toHexString(colour));
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.call(cfgObj, "remove", "NECRON_CHESTPLATE");
                Mod.staticCall("armourdye.ArmourDye", "invalidate");
            });
            clear(c, 38);
        }
    }

    static void setPublic(Object target, String field, Object value) {
        try {
            target.getClass().getField(field).set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot set " + field, e);
        }
    }

    static void heldItem(Session c) throws Exception {
        Object cfgObj = c.onClient(mc -> Mod.cfg("helditem.HeldItemConfig"));
        float oldSpeed = c.onClient(mc -> (Float) Mod.call(cfgObj, "getSwingSpeed"));
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            int vanilla = c.onClient(mc -> (Integer) Mod.call(mc.player, "getCurrentSwingDuration"));
            cfg.set("helditem.HeldItemConfig", "Enabled", true);
            c.ctx().runOnClient(mc -> Mod.call(cfgObj, "setSwingSpeed", 2.0f));
            int fast = c.onClient(mc -> (Integer) Mod.call(mc.player, "getCurrentSwingDuration"));
            c.check(fast == Math.max(1, Math.round(vanilla / 2.0f)), "swing duration " + fast + " at speed 2, vanilla " + vanilla);
            c.note("held item swing speed 2: getCurrentSwingDuration " + vanilla + " -> " + fast);
        } finally {
            c.ctx().runOnClient(mc -> Mod.call(cfgObj, "setSwingSpeed", oldSpeed));
        }
    }

    static void tooltipScroll(Session c) throws Exception {
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("tooltipscroll.TooltipScrollConfig", "Enabled", true);
            ItemStack stack = give(c, 0, "items.hyperion-legendary-lore");
            boolean consumed = c.onClient(mc -> {
                Mod.staticCall("tooltipscroll.TooltipScrollFeature", "setHovered", stack);
                return (Boolean) Mod.staticCall("tooltipscroll.TooltipScrollFeature", "onMouseScrolled", 1.0);
            });
            Object nudge = c.onClient(mc -> Mod.field("tooltipscroll.TooltipScrollFeature", "nudgeOffset"));
            boolean none = c.onClient(mc -> {
                Mod.staticCall("tooltipscroll.TooltipScrollFeature", "setHovered", (Object) null);
                return (Boolean) Mod.staticCall("tooltipscroll.TooltipScrollFeature", "onMouseScrolled", 1.0);
            });
            c.check(consumed, "a scroll over a hovered item was not consumed");
            c.check(!none, "a scroll with nothing hovered was consumed");
            c.note("tooltip scroll: hovered -> consumed, nudgeOffset " + nudge + "; nothing hovered -> passed through");
        } finally {
            clear(c, 0);
        }
    }

    static void inventoryTheme(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("inventorytheme.InventoryThemeConfig", "Enabled", true).set("inventorytheme.InventoryThemeConfig", "HypixelOnly", true);
            MenuKit.show(c, MenuKit.obj("{\"title\":\"Hx Themed\",\"rows\":3,\"fill\":true}"));
            MenuKit.awaitScreen(c, "Hx Themed", 100);
            boolean themed = c.onClient(mc -> (Boolean) Mod.staticCall("inventorytheme.InventoryThemeFeature", "shouldTheme", McCompat.screen(mc)));
            c.check(themed, "shouldTheme false on a server chest with the connection labelled mc.hypixel.net");
            c.note("inventory theme applies to a server chest (hypixelOnly on, labelled mc.hypixel.net)");
        } finally {
            MenuKit.reset(c);
        }
    }

    static void inventoryHud(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("inventoryhud.InventoryHudConfig", "Enabled", true);
            c.hx().give(9, "minecraft:diamond");
            c.ctx().runOnClient(mc -> Mod.staticCall("hud.HudSeen", "forget", "inventory_hud"));
            c.waitUntil("the inventory HUD to be drawn", mc -> (Boolean) Mod.staticCall("hud.HudSeen", "drawnRecently", "inventory_hud"), 100);
            c.note("inventory HUD drawn (HudSeen) with an item in slot 9 and no screen open");
        } finally {
            clear(c, 9);
        }
    }

    static void inventorySearch(Session c) throws Exception {
        Object cfgObj = c.onClient(mc -> Mod.cfg("inventorysearch.InventorySearchConfig"));
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("inventorysearch.InventorySearchConfig", "Enabled", true).set("inventorysearch.InventorySearchConfig", "SearchLore", true);
            ItemStack stack = give(c, 0, "items.hyperion-wither-impact-lore");
            boolean lore = c.onClient(mc -> (Boolean) Mod.staticCall("inventorysearch.InventorySearchFeature", "matches", stack, "wither", cfgObj));
            boolean name = c.onClient(mc -> (Boolean) Mod.staticCall("inventorysearch.InventorySearchFeature", "matches", stack, "hyper", cfgObj));
            boolean miss = c.onClient(mc -> (Boolean) Mod.staticCall("inventorysearch.InventorySearchFeature", "matches", stack, "terminator", cfgObj));
            c.ctx().runOnClient(mc -> Mod.call(cfgObj, "setSearchLore", false));
            boolean loreOff = c.onClient(mc -> (Boolean) Mod.staticCall("inventorysearch.InventorySearchFeature", "matches", stack, "wither", cfgObj));
            c.check(lore && name && !miss && !loreOff, "matches: lore " + lore + ", name " + name + ", miss " + miss + ", lore-off " + loreOff);
            c.note("inventory search: 'wither' (lore) " + lore + ", 'hyper' (name) " + name + ", 'terminator' " + miss
                    + ", 'wither' with lore search off " + loreOff);
        } finally {
            clear(c, 0);
        }
    }

    static void readers(Session c) throws Exception {
        try {
            ItemStack aotv = give(c, 0, "items.aotv-ethermerge-tuned3");
            double range = c.onClient(mc -> (Double) Mod.staticCall("pathfinding.EtherwarpHopper", "range"));
            String aotvId = c.onClient(mc -> (String) Mod.staticCall("cheatutils.CheatUtils", "skyblockId", aotv));
            ItemStack starred = give(c, 1, "items.necron-chestplate-starred");
            boolean isStarred = c.onClient(mc -> (Boolean) Mod.staticCall("itemprotect.ItemProtect", "isStarred", starred));
            ItemStack diamond = give(c, 2, "items.enchanted-diamond");
            String identity = c.onClient(mc -> String.valueOf(Mod.staticCall("autoroutes.ItemIdentity", "of", diamond)));
            c.check(range == 60.0, "EtherwarpHopper.range() " + range + " for tuned_transmission 3 (57 + 3)");
            c.check("ASPECT_OF_THE_VOID".equals(aotvId), "skyblockId " + aotvId);
            c.check(isStarred, "ItemProtect.isStarred false for upgrade_level 5");
            c.check(identity.contains("ENCHANTED_DIAMOND"), "ItemIdentity.of = " + identity);
            c.note("readers: skyblockId " + aotvId + ", etherwarp range " + range + ", isStarred(upgrade_level) " + isStarred
                    + ", ItemIdentity " + identity);
        } finally {
            clear(c, 0, 1, 2);
        }
    }

    /** ItemProtect.isStarred's NAME fallback: its comment (ItemProtect.java:114) promises the master pips. */
    static void starredName(Session c) throws Exception {
        try {
            ItemStack pip = give(c, 0, "items.master-pip-only-name");
            boolean starred = c.onClient(mc -> (Boolean) Mod.staticCall("itemprotect.ItemProtect", "isStarred", pip));
            String name = c.onClient(mc -> pip.getHoverName().getString());
            c.note("isStarred('" + name + "') = " + starred + " (comment: master pips ➊-➎ count; code checks ➀-➄)");
            c.check(starred, "ItemProtect.isStarred('" + name + "') is false: the name fallback checks ➀-➄ "
                    + "(U+2780-2784), not the master pips ➊-➎ (U+278A-278E) its comment and RevertMasterStarsFeature use");
        } finally {
            clear(c, 0);
        }
    }

    static Minecraft mc() {
        return Minecraft.getInstance();
    }
}

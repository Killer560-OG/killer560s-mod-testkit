package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Bazaar's product icons (mod {@code auction/BazaarIcons}).
 *
 * <ul>
 *   <li>425-ui-bazaar-icons: every product id resolved through {@code BazaarIcons} in this client (no Hypixel resource
 *       pack, which is his screenshot's case); the ones still on the paper fallback are named and counted, and the
 *       items from his screenshot (essences, seeds, Umber, Tungsten, enchanted carrot) must each have a real icon.</li>
 * </ul>
 * 424-ui-bazaar-browser (the old browser screen's layout) was retired on 2026-10-07 with that screen: the unified Bazaar
 * screen is checked by 481-489 ({@code menu/BazaarV3Cases}).
 */
final class BazaarBrowserCases {

    /** Items that drew as paper in killer560's screenshot (2026-10-07). */
    private static final String[] HIS_PAPER = {"ESSENCE_FOREST", "ESSENCE_SAFARI", "ESSENCE_WITHER", "ESSENCE_UNDEAD",
            "SEEDS", "UMBER", "TUNGSTEN", "ENCHANTED_SEEDS", "ENCHANTED_CARROT"};

    private BazaarBrowserCases() {
    }

    // ---- 425 ------------------------------------------------------------------------------------------------------

    static void icons(UiCase c) {
        @SuppressWarnings("unchecked")
        Set<String> ids = c.onClient(mc -> (Set<String>) Mod.staticCall("auction.BazaarCatalog", "ids"));
        @SuppressWarnings("unchecked")
        List<String> fallbacks = c.onClient(mc -> (List<String>) Mod.staticCall("auction.BazaarIcons", "fallbacks",
                (Collection<String>) ids));
        java.util.Map<String, Integer> bySource = new java.util.TreeMap<>();
        c.onClient(mc -> {
            for (String id : ids) {
                bySource.merge(String.valueOf(Mod.staticCall("auction.BazaarIcons", "source", id)), 1, Integer::sum);
            }
            return null;
        });
        c.note("icons for " + ids.size() + " products by source: " + bySource);
        c.note("still the paper fallback (" + fallbacks.size() + "): " + String.join(", ", fallbacks));
        c.check(ids.size() > 2000, "bundled table has " + ids.size() + " products");
        c.check(fallbacks.size() <= 60, fallbacks.size() + " products still fall back to paper (47 before the shared "
                + "item table and Pack Disabler's own textures, 0 since)");
        for (String id : HIS_PAPER) {
            String src = c.onClient(mc -> String.valueOf(Mod.staticCall("auction.BazaarIcons", "source", id)));
            String item = c.onClient(mc -> {
                ItemStack s = (ItemStack) Mod.staticCall("auction.BazaarIcons", "icon", id);
                return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString()
                        + (s.has(DataComponents.PROFILE) ? " (textured head)" : "");
            });
            c.note(id + " -> " + item + " via " + src);
            c.check(!"FALLBACK".equals(src) && !item.equals("minecraft:paper"), id + " still draws as " + item);
        }
        // A head must carry a texture: an untextured player_head is Steve, not the item.
        int untextured = c.onClient(mc -> {
            int n = 0;
            for (String id : ids) {
                ItemStack s = (ItemStack) Mod.staticCall("auction.BazaarIcons", "icon", id);
                if (s.is(Items.PLAYER_HEAD) && !s.has(DataComponents.PROFILE)) {
                    n++;
                }
            }
            return n;
        });
        c.check(untextured == 0, untextured + " products draw an untextured (Steve) head");
        c.note(String.format(Locale.ROOT, "untextured heads: %d", untextured));
    }
}

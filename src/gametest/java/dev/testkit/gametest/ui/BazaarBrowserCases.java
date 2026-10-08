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
 * The redone Bazaar browser (mod {@code auction/screen/BazaarScreen}, 2026-10-07).
 *
 * <ul>
 *   <li>424-ui-bazaar-browser: a synthetic {@code /v2/skyblock/bazaar} answer for every product in the mod's bundled
 *       table goes through the mod's real parser ({@code BazaarApi.applyResponseForTest}); the screen is opened in six
 *       views (All, a category, a group, a search, My Orders seeded through {@code BazaarOrders.readContainer}, and two
 *       product details) at four window/GUI-scale combinations. In every frame each text must lie inside its own region
 *       and the screen, no two texts and no text and widget may intersect, and widgets may not overlap each other.
 *       "Rendered" is measured: the list view must draw item icons and dozens of texts. Screenshots at GUI 2 and GUI 4.
 *       The detail view's /bz button must carry Hypixel's own search for the product's name.</li>
 *   <li>425-ui-bazaar-icons: every product id resolved through {@code BazaarIcons} in this client (no Hypixel resource
 *       pack, which is his screenshot's case); the ones still on the paper fallback are named and counted, and the
 *       items from his screenshot (essences, seeds, Umber, Tungsten, enchanted carrot) must each have a real icon.</li>
 * </ul>
 */
final class BazaarBrowserCases {

    private static final String SCREEN = "auction.screen.BazaarScreen";
    /** Items that drew as paper in killer560's screenshot (2026-10-07). */
    private static final String[] HIS_PAPER = {"ESSENCE_FOREST", "ESSENCE_SAFARI", "ESSENCE_WITHER", "ESSENCE_UNDEAD",
            "SEEDS", "UMBER", "TUNGSTEN", "ENCHANTED_SEEDS", "ENCHANTED_CARROT"};

    private BazaarBrowserCases() {
    }

    // ---- 424 ------------------------------------------------------------------------------------------------------

    static void browser(UiCase c) throws Exception {
        Object auction = Mod.cfg("auction.AuctionConfig");
        Object hud = Mod.cfg("hud.HudConfig");
        boolean oldAuto = (Boolean) Mod.call(hud, "isAutoScale");
        String oldSel = (String) Mod.call(auction, "getLastBazaarCategoryFilter");
        int[] oldWindow = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            int n = feedProducts(c);
            seedOrders(c);
            // {window w, h, GUI scale, Auto Scale, screenshot}
            int[][] sizes = {{854, 480, 2, 1, 0}, {1920, 1080, 2, 0, 1}, {1920, 1080, 4, 0, 1}, {1280, 720, 4, 0, 0}};
            for (int[] sz : sizes) {
                setWindow(c, hud, sz);
                String size = sz[0] + "x" + sz[1] + "-gui" + sz[2] + (sz[3] == 1 ? "-auto" : "");
                Screen s = open(c);
                int[] wh = c.onClient(mc -> new int[]{s.width, s.height});
                c.note(size + ": screen laid out at " + wh[0] + "x" + wh[1]);

                view(c, s, size, "all", () -> Mod.call(s, "selectForTest", ""), sz[4] == 1, true);
                int all = c.onClient(mc -> (Integer) Mod.call(s, "shownCountForTest"));
                c.check(all == n, size + ": All shows " + all + " of " + n + " products");
                view(c, s, size, "mining", () -> Mod.call(s, "selectForTest", "Mining"), false, true);
                view(c, s, size, "gemstones", () -> Mod.call(s, "selectForTest", "Mining/Gemstones"), false, true);
                int gems = c.onClient(mc -> (Integer) Mod.call(s, "shownCountForTest"));
                c.check(gems >= 60, size + ": Mining > Gemstones shows " + gems + " products (12 gems x 5 grades = 60)");
                view(c, s, size, "orders", () -> Mod.call(s, "selectForTest", "@orders"), sz[4] == 1, false);
                view(c, s, size, "detail", () -> {
                    Mod.call(s, "selectForTest", "");
                    c.check((Boolean) Mod.call(s, "openDetailForTest", "ENCHANTED_DIAMOND"), "detail opens");
                    return null;
                }, sz[4] == 1, false);
                String cmd = c.onClient(mc -> (String) Mod.call(s, "bzCommandForTest"));
                c.check("bz Enchanted Diamond".equals(cmd), size + ": /bz button sends '" + cmd + "'");
                view(c, s, size, "detail-ultimate", () -> {
                    Mod.call(s, "selectForTest", "");
                    c.check((Boolean) Mod.call(s, "openDetailForTest", "ENCHANTMENT_ULTIMATE_WISE_5"), "detail opens");
                    return null;
                }, false, false);
                c.onClient(mc -> {
                    McCompat.setScreen(mc, null);
                    return null;
                });
            }
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.call(hud, "setAutoScale", oldAuto);
                Mod.call(auction, "setLastBazaarCategoryFilter", oldSel);
                mc.options.guiScale().set(oldGui);
                return null;
            });
            c.ctx().getInput().resizeWindow(oldWindow[0], oldWindow[1]);
            c.ticks(5);
            c.onClient(mc -> {
                mc.resizeGui();
                return null;
            });
        }
    }

    private interface Step {
        Object run() throws Exception;
    }

    private static void view(UiCase c, Screen s, String size, String name, Step select, boolean screenshot,
            boolean expectItems) throws Exception {
        c.onClient(mc -> {
            try {
                select.run();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            return null;
        });
        c.ticks(3);
        Frames.Drawn drawn = c.onClient(mc -> Frames.extract(mc, s, -1, -1));
        c.ticks(1);
        @SuppressWarnings("unchecked")
        List<String> report = c.onClient(mc -> (List<String>) Mod.call(s, "layoutReport"));
        int[] wh = c.onClient(mc -> new int[]{s.width, s.height});
        String tag = size + "/" + name;
        int texts = checkLayout(c, tag, report, wh[0], wh[1]);
        c.note(tag + ": " + texts + " texts, drawn " + drawn);
        c.check(texts >= 10, tag + ": only " + texts + " texts recorded - did the screen draw?");
        if (expectItems) {
            c.check(drawn.items() >= 5, tag + ": only " + drawn.items() + " item icons drawn");
        }
        if (screenshot) {
            HudEditorCases.screenshot(c, size + "-" + name);
        }
    }

    /** Returns how many texts the frame recorded; every violation is a problem. */
    static int checkLayout(UiCase c, String tag, List<String> report, int sw, int sh) {
        java.util.Map<String, int[]> regions = new java.util.HashMap<>();
        List<int[]> texts = new ArrayList<>();
        List<String> textNames = new ArrayList<>();
        List<String> textRegions = new ArrayList<>();
        List<int[]> widgets = new ArrayList<>();
        List<String> widgetNames = new ArrayList<>();
        for (String line : report) {
            String[] p = line.split(" ", 7);
            switch (p[0]) {
                case "region" -> regions.put(p[1], box(p, 2));
                case "text" -> {
                    texts.add(box(p, 2));
                    textRegions.add(p[1]);
                    textNames.add(p.length > 6 ? p[6] : "");
                }
                case "widget" -> {
                    widgets.add(box(p, 2));
                    widgetNames.add(p[1]);
                }
                default -> { }
            }
        }
        int problems = 0;
        int[] screen = {0, 0, sw, sh};
        for (int i = 0; i < texts.size(); i++) {
            int[] t = texts.get(i);
            int[] r = regions.get(textRegions.get(i));
            if (!inside(t, screen) && problems++ < 12) {
                c.problem(tag + ": text '" + textNames.get(i) + "' " + str(t) + " leaves the screen " + sw + "x" + sh);
            }
            if (r == null) {
                if (problems++ < 12) {
                    c.problem(tag + ": text '" + textNames.get(i) + "' names unknown region " + textRegions.get(i));
                }
            } else if (!inside(t, r) && problems++ < 12) {
                c.problem(tag + ": text '" + textNames.get(i) + "' " + str(t) + " runs out of its region "
                        + textRegions.get(i) + " " + str(r));
            }
            for (int j = i + 1; j < texts.size(); j++) {
                if (intersects(t, texts.get(j)) && problems++ < 12) {
                    c.problem(tag + ": text '" + textNames.get(i) + "' " + str(t) + " overlaps '" + textNames.get(j)
                            + "' " + str(texts.get(j)));
                }
            }
            for (int j = 0; j < widgets.size(); j++) {
                if (intersects(t, widgets.get(j)) && problems++ < 12) {
                    c.problem(tag + ": text '" + textNames.get(i) + "' " + str(t) + " is under widget "
                            + widgetNames.get(j) + " " + str(widgets.get(j)));
                }
            }
        }
        for (int i = 0; i < widgets.size(); i++) {
            if (!inside(widgets.get(i), screen) && problems++ < 12) {
                c.problem(tag + ": widget " + widgetNames.get(i) + " " + str(widgets.get(i)) + " leaves the screen");
            }
            for (int j = i + 1; j < widgets.size(); j++) {
                if (intersects(widgets.get(i), widgets.get(j)) && problems++ < 12) {
                    c.problem(tag + ": widget " + widgetNames.get(i) + " overlaps widget " + widgetNames.get(j));
                }
            }
        }
        String[] panes = {"header", "sidebar", "main"};
        for (int i = 0; i < panes.length; i++) {
            for (int j = i + 1; j < panes.length; j++) {
                int[] a = regions.get(panes[i]);
                int[] b = regions.get(panes[j]);
                if (a != null && b != null && intersects(a, b)) {
                    c.problem(tag + ": region " + panes[i] + " overlaps " + panes[j]);
                }
            }
        }
        if (problems > 12) {
            c.problem(tag + ": ... " + (problems - 12) + " more layout problems");
        }
        return texts.size();
    }

    private static int[] box(String[] p, int from) {
        return new int[]{Integer.parseInt(p[from]), Integer.parseInt(p[from + 1]), Integer.parseInt(p[from + 2]),
                Integer.parseInt(p[from + 3])};
    }

    private static boolean inside(int[] a, int[] r) {
        return a[0] >= r[0] && a[1] >= r[1] && a[0] + a[2] <= r[0] + r[2] && a[1] + a[3] <= r[1] + r[3];
    }

    private static boolean intersects(int[] a, int[] b) {
        return a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
    }

    private static String str(int[] b) {
        return "[" + b[0] + "," + b[1] + " " + b[2] + "x" + b[3] + "]";
    }

    private static void setWindow(UiCase c, Object hud, int[] sz) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            Mod.call(hud, "setAutoScale", sz[3] == 1);
            return null;
        });
        c.ctx().getInput().resizeWindow(sz[0], sz[1]);
        c.ticks(3);
        c.onClient(mc -> {
            mc.options.guiScale().set(sz[2]);
            mc.resizeGui();
            return null;
        });
        c.ticks(3);
    }

    private static Screen open(UiCase c) {
        Screen s = c.onClient(mc -> {
            try {
                Screen screen = (Screen) Mod.cls(SCREEN).getConstructor(Screen.class).newInstance((Screen) null);
                McCompat.setScreen(mc, screen);
                return screen;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        c.ticks(5);
        return s;
    }

    /** Every id in the bundled table, priced deterministically, through the mod's own response parser. */
    static int feedProducts(UiCase c) {
        @SuppressWarnings("unchecked")
        Set<String> ids = c.onClient(mc -> (Set<String>) Mod.staticCall("auction.BazaarCatalog", "ids"));
        JsonObject products = new JsonObject();
        for (String id : ids) {
            int h = Math.abs(id.hashCode());
            double sell = 1 + (h % 100000) / 10.0;
            double buy = Math.round(sell * (1.02 + (h % 37) / 100.0) * 10) / 10.0;
            if (id.equals("ENCHANTED_DIAMOND")) {
                sell = 1275.2;
                buy = 1319.1;
            }
            JsonObject qs = new JsonObject();
            qs.addProperty("productId", id);
            qs.addProperty("sellPrice", sell);
            qs.addProperty("buyPrice", buy);
            qs.addProperty("sellVolume", 1000 + h % 900000);
            qs.addProperty("buyVolume", 500 + h % 400000);
            qs.addProperty("sellMovingWeek", 10000 + h % 9000000);
            qs.addProperty("buyMovingWeek", 9000 + h % 8000000);
            qs.addProperty("sellOrders", 1 + h % 90);
            qs.addProperty("buyOrders", 1 + h % 200);
            JsonArray buySummary = new JsonArray();
            JsonArray sellSummary = new JsonArray();
            for (int i = 0; i < 8; i++) {
                buySummary.add(level(Math.round((buy + i * 0.1) * 10) / 10.0, 64 * (i + 1) + h % 50, 1 + i % 3));
                sellSummary.add(level(Math.round((sell - i * 0.1) * 10) / 10.0, 128 * (i + 1) + h % 70, 1 + i % 2));
            }
            JsonObject p = new JsonObject();
            p.addProperty("product_id", id);
            p.add("quick_status", qs);
            p.add("buy_summary", buySummary);
            p.add("sell_summary", sellSummary);
            products.add(id, p);
        }
        JsonObject root = new JsonObject();
        root.addProperty("success", true);
        root.addProperty("lastUpdated", System.currentTimeMillis());
        root.add("products", products);
        String body = root.toString();
        int n = c.onClient(mc -> (Integer) Mod.staticCall("auction.BazaarApi", "applyResponseForTest", body));
        c.note("fed " + ids.size() + " product ids, the parser kept " + n);
        c.check(n == ids.size() && n > 2000, "parsed " + n + " of " + ids.size() + " products");
        return n;
    }

    private static JsonObject level(double price, long amount, int orders) {
        JsonObject o = new JsonObject();
        o.addProperty("pricePerUnit", price);
        o.addProperty("amount", amount);
        o.addProperty("orders", orders);
        return o;
    }

    /** Four orders as Hypixel's Manage Orders shows them, through the mod's real container reader. */
    static void seedOrders(UiCase c) {
        int read = c.onClient(mc -> {
            Mod.staticCall("auction.BazaarOrders", "clearForTest");
            List<ItemStack> stacks = new ArrayList<>();
            stacks.add(named(Items.GLASS_PANE, " ", List.of()));
            stacks.add(named(Items.DIAMOND, "§a§lBUY §aEnchanted Diamond", List.of("§8Worth 204k coins", "",
                    "§7Order amount: §a160§7x", "§7Filled: §664§7/160 §a(40%)", "", "§7Price per unit: §61,275.3 coins",
                    "", "§7You have §a64 items §7to claim!", "", "§eClick to claim!")));
            stacks.add(named(Items.WHEAT, "§a§lBUY §fWheat", List.of("§8Worth 4.2k coins", "",
                    "§7Order amount: §a2,000§7x", "", "§7Price per unit: §62.1 coins", "", "§7Expires in §e6d 23h")));
            stacks.add(named(Items.PLAYER_HEAD, "§6§lSELL §dWither Essence", List.of("§8Worth 4.1M coins", "",
                    "§7Offer amount: §a1,000§7x", "§7Filled: §61k§7/1k §a§l100%!", "",
                    "§7Price per unit: §64,100.5 coins", "", "§eClick to claim!")));
            stacks.add(named(Items.PAPER, "§6§lSELL §fRough Ruby Gemstone", List.of("",
                    "§7Offer amount: §a640§7x", "§7Filled: §634§7/640 §a(5.3%)", "", "§7Price per unit: §699,999.0 coins",
                    "", "Expired!")));
            return (Integer) Mod.staticCall("auction.BazaarOrders", "readContainer", "Your Bazaar Orders", stacks);
        });
        c.check(read == 4, "seeded orders read: " + read);
    }

    static ItemStack named(net.minecraft.world.item.Item item, String name, List<String> lore) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        List<Component> lines = new ArrayList<>();
        for (String l : lore) {
            lines.add(Component.literal(l));
        }
        if (!lines.isEmpty()) {
            s.set(DataComponents.LORE, new ItemLore(lines, lines));
        }
        return s;
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

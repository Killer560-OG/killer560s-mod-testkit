package dev.testkit.gametest.menu;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Hx;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The unified Bazaar screen (mod {@code bazaar/}, branch bazaar-v3, 2026-10-07): one screen for browsing (drawn from the
 * public Bazaar API) and for Hypixel's real Bazaar menus (reskinned into the same frame). Every case asserts what was
 * DRAWN (the view's own layout report, its per-frame log, screenshots) or SENT (the server's container.click and cmd
 * events), never a flag alone.
 *
 * <ul>
 *   <li>481-menu-bazaar-v3-render: the menu-less screen (/killer560bz's) from a synthetic answer for every product, in
 *       five views (two categories, a search, a product, an empty search) at six window/GUI sizes: every text inside its
 *       region and the screen, no two texts and no text and the search box overlapping, the panes never overlapping,
 *       five category tabs, exactly the three bottom buttons, items drawn. Screenshots at GUI 2 and the small window.</li>
 *   <li>482-menu-bazaar-v3-recents: real presses on three products put them in Recent, newest first; the list survives a
 *       reload from its file, is per SkyBlock profile, and a press on a recent sends Hypixel's {@code /bz <name>} for it
 *       and shows that product.</li>
 *   <li>483-menu-bazaar-v3-tabs: a press on each category tab lists only that category's products (checked against the
 *       bundled table) under its groups and remembers the tab; typing in the search box lists only matching products from
 *       every category, Backspace edits it, a tab press clears it; a column heading sorts.</li>
 *   <li>484-menu-bazaar-v3-bottom: on Hypixel's main page the bottom bar is exactly Sell Inventory Now, Sell Sacks Now and
 *       Manage Orders, each one slot click on its real button (and nothing else sent); on the product page Manage Orders
 *       is its own slot; from the menu-less screen Manage Orders sends {@code /bz} and presses Manage Orders once as the
 *       follow-up, landing on the orders list.</li>
 *   <li>485-menu-bazaar-v3-huds: two probe HUD layers of the testkit's own (one added last, one attached after
 *       MISC_OVERLAYS) and the mod's own layers draw with no screen; with the Bazaar open (menu-less and reskinned) and
 *       Hide HUDs in Bazaar on, neither probe is called and the mod's gate counts skipped draws; with it off the probes
 *       draw again; closing the Bazaar brings them back.</li>
 *   <li>486-menu-bazaar-v3-seamless: three page switches recorded frame by frame - a reskinned page to the next, a next
 *       page whose items trickle in a slot a tick, and the menu-less screen to Hypixel's product page through /bz and the
 *       follow-up - none may draw Hypixel's chest, no screen, or an empty panel; screenshots taken while the next page is
 *       still empty must show no chest grey; the panel, header, tabs, recents, bottom bar, product header and card
 *       positions must be identical before and after.</li>
 *   <li>487-menu-bazaar-v3-product-click: with a Booster Cookie a product press sends {@code /bz <name>} and lands on the
 *       product (one follow-up click); at the NPC without a Cookie no command is sent and each press is one click towards
 *       the product (its group, then the product; another category's tab first).</li>
 *   <li>488-menu-bazaar-v3-followup: exactly one click on a single match, none on zero or two matches, none without his
 *       product press, none after the timeout, none with Follow-up Click off (the results show with the product
 *       highlighted), and never a second one when the same results come back. Run on the legit AND the cheat jar.</li>
 *   <li>489-menu-bazaar-v3-themes: Amber, Dark and Light (the Inventory Theme setting) on the browse view and a
 *       reskinned product page, at GUI 2 and the small window: no layout problems, the panel drawn in that theme's
 *       colour; screenshots of each.</li>
 *   <li>490-menu-bazaar-v3-inventory: his inventory in the screen (killer560: "it needs a way for me to click on items in
 *       my inventory as well so I can list them"). Over Hypixel's main page the panel shows the menu's own 36 player
 *       slots (each mapped to its container slot index), Bazaar items live, the rest dimmed: presses on a non-Bazaar item
 *       (a Terminator, a plain stick) send nothing, a shift-press on Enchanted Diamond sends ONE QUICK_MOVE on its slot, a
 *       press on Wheat ONE PICKUP on its slot - and the product page the server opens for it arrives reskinned. With no
 *       menu open (the menu-less screen) the same press sends {@code /bz Wheat} and the bow still does nothing.</li>
 * </ul>
 * Retired: 424-ui-bazaar-browser (the old browser screen, replaced by 481). Updated for the new design: 461 (the main
 * page is drawn from the live data, Close/Search/History/Settings/Graphs/mode buttons are left out on purpose), 462
 * (content region), 464 (the Hypixel menu button's key), 466 (the screen's new class).
 */
final class BazaarV3Cases {

    static final String VIEW = "bazaar.BazaarView";
    static final String RESKIN = "bazaar.BazaarReskin";
    static final String HUD = "bazaar.BazaarHud";
    static final String FOLLOW = "bazaar.BazaarFollowUp";
    static final int CHEST_GREY = 0xC6C6C6;
    static final String COOKIE_ON = "§a§lActive Effects\n§7No effects active.\n\n§d§lCookie Buff\n"
            + "§f3d 17h 2m\n\n§aRanks, Boosters & MORE! §c§lSTORE.HYPIXEL.NET";
    static final String COOKIE_OFF = "§a§lActive Effects\n§7No effects active.\n\n§d§lCookie Buff\n"
            + "§7Not active! Obtain booster cookies from the community\n§7shop in the hub.\n\n§aRanks, Boosters & MORE! §c§lSTORE.HYPIXEL.NET";
    static final Set<String> CHROME = Set.of("panel", "header", "tabs", "rail", "content", "bottom");

    private BazaarV3Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "481-menu-bazaar-v3-render", BazaarV3Cases::render);
        MenuSuite.test(s, "482-menu-bazaar-v3-recents", BazaarV3Cases::recents);
        MenuSuite.test(s, "483-menu-bazaar-v3-tabs", BazaarV3Cases::tabs);
        MenuSuite.test(s, "484-menu-bazaar-v3-bottom", BazaarV3Cases::bottom);
        MenuSuite.test(s, "485-menu-bazaar-v3-huds", BazaarV3Cases::huds);
        MenuSuite.test(s, "486-menu-bazaar-v3-seamless", BazaarV3Cases::seamless);
        MenuSuite.test(s, "487-menu-bazaar-v3-product-click", BazaarV3Cases::productClick);
        MenuSuite.test(s, "488-menu-bazaar-v3-followup", BazaarV3Cases::followUp);
        MenuSuite.test(s, "489-menu-bazaar-v3-themes", BazaarV3Cases::themes);
        MenuSuite.test(s, "490-menu-bazaar-v3-inventory", BazaarV3Cases::inventory);
    }

    // ---- environment ----------------------------------------------------------------------------------------------

    /** Everything a case changes, put back on close. */
    private static final class Env implements AutoCloseable {
        final Session c;
        final MenuKit.Cfg cfg;
        final boolean gate;
        final int[] window;
        final String oldSort;
        final String oldFilter;

        Env(Session c) {
            this.c = c;
            MenuKit.reset(c);
            cfg = new MenuKit.Cfg(c);
            cfg.set("auction.AuctionConfig", "ReskinRealBazaar", true);
            cfg.set("auction.AuctionConfig", "BazaarEnabled", true);
            cfg.set("auction.AuctionConfig", "BazaarVanillaKeyCode", 342);
            cfg.set("bazaar.BazaarConfig", "FollowUpClick", true);
            cfg.set("bazaar.BazaarConfig", "HideHuds", true);
            cfg.set("hud.HudConfig", "AutoScale", false);
            gate = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
            window = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                    mc.options.guiScale().get()});
            oldSort = c.onClient(mc -> String.valueOf(Mod.get("auction.AuctionConfig", "getLastBazaarSort")));
            oldFilter = c.onClient(mc -> (String) Mod.get("auction.AuctionConfig", "getLastBazaarCategoryFilter"));
            c.ctx().runOnClient(mc -> {
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                Mod.staticCall("bazaar.BazaarRecents", "clearForTest");
                Mod.staticCall(VIEW, "resetForTest");
            });
            onCommand(c, null);
        }

        @Override
        public void close() {
            try {
                c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
                MenuKit.reset(c);
                onCommand(c, null);
                c.hx().tab(List.of(), null, "");
                c.ctx().runOnClient(mc -> {
                    Mod.set("auction.AuctionConfig", "setLastBazaarSort",
                            Mod.enumValue("auction.AuctionConfig$BazaarSortMode", oldSort));
                    Mod.set("auction.AuctionConfig", "setLastBazaarCategoryFilter", oldFilter);
                    Mod.staticCall(HUD, "recordFramesForTest", false);
                });
                setWindow(c, window[0], window[1], window[2]);
            } finally {
                cfg.close();
                c.ctx().runOnClient(mc -> Mod.staticCall("util.SkyblockGate", "setEnabled", gate));
            }
        }
    }

    /** What the server opens for /bz (bare or with a search); null: nothing (the command is still recorded). */
    static void onCommand(Session c, JsonObject menu) {
        c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", menu == null ? JsonNull.INSTANCE : menu));
    }

    static void cookie(Session c, boolean active) {
        c.hx().tab(List.of("§b§lArea: §7Hub"), null, active ? COOKIE_ON : COOKIE_OFF);
        c.ctx().waitTicks(5);
        c.ctx().runOnClient(mc -> Mod.staticCall(VIEW, "forgetCookieForTest"));
        String state = c.onClient(mc -> String.valueOf(Mod.staticCall("auction.BoosterCookie", "state", mc)));
        c.check(state.equals(active ? "ACTIVE" : "INACTIVE"), "Booster Cookie reads " + state);
    }

    static void setWindow(Session c, int w, int h, int gui) {
        c.ctx().getInput().resizeWindow(w, h);
        c.ctx().waitTicks(3);
        c.ctx().runOnClient(mc -> {
            mc.options.guiScale().set(gui);
            mc.resizeGui();
        });
        c.ctx().waitTicks(3);
    }

    static int[] guiSize(Session c) {
        return c.onClient(mc -> new int[]{mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()});
    }

    static void openScreen(Session c) {
        c.ctx().runOnClient(mc -> {
            try {
                Screen s = (Screen) Mod.cls("bazaar.BazaarScreen").getConstructor(Screen.class).newInstance((Screen) null);
                McCompat.setScreen(mc, s);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        c.ctx().waitTicks(5);
        c.check(c.onClient(mc -> McCompat.screen(mc) != null
                && McCompat.screen(mc).getClass().getName().endsWith("bazaar.BazaarScreen")), "the Bazaar screen did not open");
    }

    @SuppressWarnings("unchecked")
    static List<String> layout(Session c) {
        return c.onClient(mc -> (List<String>) Mod.staticCall(VIEW, "layoutReportForTest"));
    }

    static String summary(Session c) {
        return c.onClient(mc -> (String) Mod.staticCall(VIEW, "frameSummaryForTest"));
    }

    static String mode(Session c) {
        return c.onClient(mc -> (String) Mod.staticCall(VIEW, "modeForTest"));
    }

    static String follow(Session c) {
        return c.onClient(mc -> (String) Mod.staticCall(FOLLOW, "stateForTest"));
    }

    static String kind(Session c) {
        return c.onClient(mc -> (String) Mod.staticCall(RESKIN, "kindForTest"));
    }

    /** The box of the hotspot whose slot or key is {@code key} ({@code tab:Farming}, {@code 47}, {@code back}...). */
    static int[] hot(List<String> layout, String key) {
        for (String line : layout) {
            String[] p = line.split(" ", 7);
            if (!p[0].equals("hotspot")) {
                continue;
            }
            boolean match = p[1].equals(key) || (p.length > 6 && p[6].split(" ", 2)[0].equals(key));
            if (match) {
                return new int[]{Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5])};
            }
        }
        return null;
    }

    /** Every hotspot line whose key starts with {@code prefix}: {key, slot-or-key}. */
    static List<String[]> hots(List<String> layout, String prefix) {
        List<String[]> out = new ArrayList<>();
        for (String line : layout) {
            String[] p = line.split(" ", 7);
            if (!p[0].equals("hotspot")) {
                continue;
            }
            String key = p[1].matches("\\d+") && p.length > 6 ? p[6].split(" ", 2)[0] : p[1];
            if (key.startsWith(prefix)) {
                out.add(new String[]{key, p[1]});
            }
        }
        return out;
    }

    static void press(Session c, List<String> layout, String key) {
        int[] box = hot(layout, key);
        c.check(box != null, "no hotspot '" + key + "' in the frame");
        BazaarReskinCases.pressAt(c, box, 0);
    }

    static int clicks(Session c) {
        return c.events("container.click").size();
    }

    static JsonObject lastClick(Session c) {
        List<JsonObject> e = c.events("container.click");
        return e.isEmpty() ? null : e.get(e.size() - 1);
    }

    /** Every frame problem of a layout: texts outside regions/screen, overlapping texts, a text under the search box,
     *  overlapping chrome panes. */
    static List<String> problems(List<String> layout, int sw, int sh) {
        List<String> p = new ArrayList<>(BazaarReskinCases.layoutProblems(layout, sw, sh));
        int[] search = null;
        Map<String, int[]> panes = new HashMap<>();
        for (String line : layout) {
            String[] q = line.split(" ", 7);
            if (q[0].equals("widget") && q[1].equals("search")) {
                search = box(q);
            } else if (q[0].equals("region") && CHROME.contains(q[1]) && !q[1].equals("panel")) {
                panes.put(q[1], box(q));
            }
        }
        if (search != null) {
            for (String line : layout) {
                String[] q = line.split(" ", 7);
                if (q[0].equals("text") && intersects(box(q), search)) {
                    p.add("text under the search box: " + line);
                }
            }
            if (search[0] < 0 || search[0] + search[2] > sw) {
                p.add("search box off screen");
            }
        }
        List<String> names = new ArrayList<>(panes.keySet());
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                if (intersects(panes.get(names.get(i)), panes.get(names.get(j)))) {
                    p.add("pane " + names.get(i) + " overlaps " + names.get(j));
                }
            }
        }
        return p;
    }

    static int[] box(String[] q) {
        return new int[]{Integer.parseInt(q[2]), Integer.parseInt(q[3]), Integer.parseInt(q[4]), Integer.parseInt(q[5])};
    }

    static boolean intersects(int[] a, int[] b) {
        return a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
    }

    /** "region <name> ..." lines of the chrome and of the product view, for before/after comparisons. */
    static List<String> fixedRegions(List<String> layout, Set<String> names) {
        List<String> out = new ArrayList<>();
        for (String line : layout) {
            String[] q = line.split(" ", 3);
            if (q[0].equals("region") && names.contains(q[1])) {
                out.add(line);
            }
        }
        return out;
    }

    /** Every product id in the mod's bundled table, priced deterministically, through the mod's own parser. */
    static int feedAll(Session c) {
        @SuppressWarnings("unchecked")
        Set<String> ids = c.onClient(mc -> (Set<String>) Mod.staticCall("auction.BazaarCatalog", "ids"));
        JsonObject products = new JsonObject();
        for (String id : ids) {
            int h = Math.abs(id.hashCode());
            double sell = 1 + (h % 100000) / 10.0;
            double buy = Math.round(sell * (1.02 + (h % 37) / 100.0) * 10) / 10.0;
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
            for (int i = 0; i < 6; i++) {
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
        c.check(n == ids.size() && n > 2000, "the mod's parser kept " + n + " of " + ids.size() + " products");
        return n;
    }

    private static JsonObject level(double price, long amount, int orders) {
        JsonObject o = new JsonObject();
        o.addProperty("pricePerUnit", price);
        o.addProperty("amount", amount);
        o.addProperty("orders", orders);
        return o;
    }

    /** The category a product id sits under in the mod's bundled table, or null. */
    static String categoryOf(Session c, String id) {
        return c.onClient(mc -> {
            Object e = Mod.staticCall("auction.BazaarCatalog", "get", id);
            if (e == null) {
                return null;
            }
            Object g = Mod.staticCall("auction.BazaarCatalog", "group", (Integer) Mod.call(e, "group"));
            return g == null ? null : (String) Mod.call(g, "category");
        });
    }

    static String nameOf(Session c, String id) {
        return c.onClient(mc -> {
            Object e = Mod.staticCall("auction.BazaarCatalog", "get", id);
            return e == null ? id : (String) Mod.call(e, "name");
        });
    }

    /** Hypixel's search results for "Wheat" holding these product names (slots 10...), Go Back at 31. */
    static JsonObject results(String... names) {
        JsonObject spec = new JsonObject();
        spec.addProperty("title", "Bazaar ➜ \"Wheat\"");
        spec.addProperty("rows", 4);
        spec.addProperty("fill", true);
        JsonObject slots = new JsonObject();
        for (int i = 0; i < names.length; i++) {
            JsonObject it = new JsonObject();
            it.addProperty("item", "minecraft:wheat");
            it.addProperty("name", "§f" + names[i]);
            JsonArray lore = new JsonArray();
            for (String l : new String[]{"§8Common commodity", "", "§7Buy price: §64.8 coins", "§7Sell price: §64.3 coins", "",
                    "§eClick to view details!"}) {
                lore.add(l);
            }
            it.add("lore", lore);
            slots.add(String.valueOf(10 + i), it);
        }
        JsonObject back = new JsonObject();
        back.addProperty("item", "minecraft:arrow");
        back.addProperty("name", "§aGo Back");
        JsonArray bl = new JsonArray();
        bl.add("§7To Bazaar");
        back.add("lore", bl);
        slots.add("31", back);
        spec.add("slots", slots);
        return spec;
    }

    static JsonObject on(JsonObject spec, int slot, JsonObject open) {
        JsonObject on = spec.has("on") ? spec.getAsJsonObject("on") : new JsonObject();
        JsonObject act = new JsonObject();
        act.add("open", open);
        on.add(String.valueOf(slot), act);
        spec.add("on", on);
        return spec;
    }

    static void searchFor(Session c, String q) {
        c.ctx().runOnClient(mc -> Mod.staticCall(VIEW, "setQueryForTest", q));
        c.ctx().waitTicks(3);
    }

    static BufferedImage shot(Session c, String label) {
        return BazaarReskinCases.shot(c, label);
    }

    // ---- 481 ------------------------------------------------------------------------------------------------------

    static void render(Session c) throws Exception {
        try (Env env = new Env(c)) {
            int n = feedAll(c);
            c.ctx().runOnClient(mc -> {
                for (String id : new String[]{"ENCHANTMENT_ULTIMATE_WISE_5", "ESSENCE_WITHER", "ROUGH_RUBY_GEM",
                        "ENCHANTED_DIAMOND", "SEEDS", "WHEAT"}) {
                    Mod.staticCall("bazaar.BazaarRecents", "add", id);
                }
            });
            // {window w, h, GUI scale, screenshot}
            int[][] sizes = {{854, 480, 2, 1}, {1280, 720, 2, 0}, {1920, 1080, 2, 1}, {1920, 1080, 3, 0}, {1920, 1080, 4, 1},
                    {2560, 1440, 3, 0}};
            String[] views = {"farming", "mining", "search", "product", "no-results"};
            int frames = 0;
            for (int[] sz : sizes) {
                setWindow(c, sz[0], sz[1], sz[2]);
                openScreen(c);
                int[] gui = guiSize(c);
                String size = sz[0] + "x" + sz[1] + "-gui" + sz[2];
                for (String v : views) {
                    c.ctx().runOnClient(mc -> {
                        switch (v) {
                            case "farming" -> Mod.staticCall(VIEW, "selectTabForTest", "Farming");
                            case "mining" -> Mod.staticCall(VIEW, "selectTabForTest", "Mining");
                            case "search" -> Mod.staticCall(VIEW, "setQueryForTest", "diamond");
                            case "product" -> Mod.staticCall(VIEW, "previewForTest", "ENCHANTED_DIAMOND");
                            default -> Mod.staticCall(VIEW, "setQueryForTest", "zzqqxx");
                        }
                    });
                    c.ctx().getInput().setCursorPos(2, 2);
                    c.ctx().waitTicks(3);
                    List<String> layout = layout(c);
                    String sum = summary(c);
                    String tag = size + " " + v;
                    long texts = layout.stream().filter(l -> l.startsWith("text ")).count();
                    c.check(texts >= 12, tag + ": only " + texts + " texts drawn (" + sum + ")");
                    List<String> probs = problems(layout, gui[0], gui[1]);
                    c.check(probs.isEmpty(), tag + ": " + probs.size() + " layout problem(s): "
                            + String.join(" | ", probs.subList(0, Math.min(6, probs.size()))));
                    List<String[]> tabs = hots(layout, "tab:");
                    List<String[]> bottom = hots(layout, "bottom:");
                    Set<String> bottomKeys = new HashSet<>();
                    bottom.forEach(b -> bottomKeys.add(b[0]));
                    c.check(tabs.size() == 5, tag + ": " + tabs.size() + " category tabs");
                    c.check(bottom.size() == 3 && bottomKeys.equals(Set.of("bottom:Sell_Inventory_Now",
                            "bottom:Sell_Sacks_Now", "bottom:Manage_Orders")), tag + ": bottom bar " + bottomKeys);
                    c.check(hot(layout, "search") != null, tag + ": no search box");
                    if (gui[0] >= 400) {
                        c.check(hots(layout, "recent:").size() >= 3, tag + ": Recent shows " + hots(layout, "recent:").size());
                    }
                    int items = Integer.parseInt(sum.replaceAll(".*items=(\\d+).*", "$1"));
                    if (!v.equals("no-results")) {
                        c.check(items >= (v.equals("product") ? 5 : 4), tag + ": only " + items + " content items (" + sum + ")");
                    }
                    if (v.equals("farming") || v.equals("mining")) {
                        String cat = v.equals("farming") ? "Farming" : "Mining";
                        List<String[]> rows = hots(layout, "product:");
                        c.check(rows.size() >= 4, tag + ": " + rows.size() + " product rows");
                        for (String[] r : rows) {
                            String id = r[0].substring("product:".length());
                            c.check(cat.equals(categoryOf(c, id)), tag + ": " + id + " listed under " + cat);
                        }
                    }
                    if (sz[3] == 1) {
                        shot(c, "v3-" + size + "-" + v);
                    }
                    frames++;
                }
                c.note(size + ": GUI " + gui[0] + "x" + gui[1] + ", 5 views clean");
            }
            c.note(n + " products fed; " + frames + " frames checked, no layout problem");
        }
    }

    // ---- 482 ------------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static List<String> recentsList(Session c) {
        return c.onClient(mc -> (List<String>) Mod.staticCall("bazaar.BazaarRecents", "list"));
    }

    static void recents(Session c) throws Exception {
        try (Env env = new Env(c)) {
            feedAll(c);
            setWindow(c, 1920, 1080, 2);
            cookie(c, true);
            openScreen(c);
            List<String> pressed = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                c.ctx().runOnClient(mc -> Mod.staticCall(VIEW, "selectTabForTest", "Farming"));
                c.ctx().waitTicks(3);
                List<String> layout = layout(c);
                List<String[]> rows = hots(layout, "product:");
                String key = rows.get(i * 2)[0];
                String id = key.substring("product:".length());
                int cmds = c.commands().size();
                press(c, layout, key);
                c.waitUntil("/bz for " + id, mc -> c.commands().size() > cmds, 40);
                String cmd = c.commands().get(c.commands().size() - 1);
                c.check(cmd.equals("bz " + nameOf(c, id)), "press on " + id + " sent '" + cmd + "'");
                c.check(mode(c).startsWith("PREVIEW|") && mode(c).contains("|" + id + "|"), "after the press: " + mode(c));
                pressed.add(0, id);
                c.check(recentsList(c).subList(0, pressed.size()).equals(pressed), "Recent is " + recentsList(c)
                        + ", want " + pressed);
            }
            c.note("pressed 3 products: Recent " + recentsList(c));

            // Persisted: forget memory, read the file back.
            c.ctx().runOnClient(mc -> {
                Mod.setField("bazaar.BazaarRecents", "loaded", false);
                ((Map<?, ?>) Mod.field("bazaar.BazaarRecents", "BY_PROFILE")).clear();
            });
            c.check(recentsList(c).equals(pressed), "after a reload from the file Recent is " + recentsList(c));
            c.note("reloaded from the file: " + recentsList(c));

            // Per profile.
            String oldProfile = c.onClient(mc -> (String) Mod.field("pathfinding.ProfileTracker", "profileName"));
            try {
                c.ctx().runOnClient(mc -> Mod.setField("pathfinding.ProfileTracker", "profileName", "Banana"));
                c.check(recentsList(c).isEmpty(), "profile Banana sees " + recentsList(c));
            } finally {
                c.ctx().runOnClient(mc -> Mod.setField("pathfinding.ProfileTracker", "profileName", oldProfile));
            }
            c.check(recentsList(c).equals(pressed), "back on the first profile Recent is " + recentsList(c));
            c.note("another SkyBlock profile has its own (empty) list");

            // A press on a recent opens it.
            c.ctx().runOnClient(mc -> Mod.staticCall(VIEW, "selectTabForTest", "Mining"));
            c.ctx().waitTicks(3);
            String second = pressed.get(1);
            int cmds = c.commands().size();
            press(c, layout(c), "recent:" + second);
            c.waitUntil("/bz for the recent " + second, mc -> c.commands().size() > cmds, 40);
            String cmd = c.commands().get(c.commands().size() - 1);
            c.check(cmd.equals("bz " + nameOf(c, second)), "press on recent " + second + " sent '" + cmd + "'");
            c.check(mode(c).contains("|" + second + "|"), "after the recent press: " + mode(c));
            c.check(recentsList(c).get(0).equals(second), "the opened recent did not move to the top: " + recentsList(c));
            c.note("press on recent " + second + " -> '" + cmd + "', now first in Recent");
            c.check(clicks(c) == 0, "container clicks with no menu open: " + clicks(c));
        }
    }

    // ---- 483 ------------------------------------------------------------------------------------------------------

    static void tabs(Session c) throws Exception {
        try (Env env = new Env(c)) {
            feedAll(c);
            setWindow(c, 1920, 1080, 2);
            openScreen(c);
            for (String cat : List.of("Mining", "Combat", "Woods & Fishes", "Oddities", "Farming")) {
                press(c, layout(c), "tab:" + cat.replace(' ', '_'));
                c.ctx().waitTicks(3);
                List<String> layout = layout(c);
                String sum = summary(c);
                c.check(sum.startsWith("BROWSE BROWSE " + cat) || sum.startsWith("BROWSE " + cat), "after the " + cat
                        + " tab: " + sum);
                List<String[]> rows = hots(layout, "product:");
                c.check(rows.size() >= 5, cat + ": " + rows.size() + " product rows");
                for (String[] r : rows) {
                    String id = r[0].substring("product:".length());
                    c.check(cat.equals(categoryOf(c, id)), cat + " tab lists " + id + " (" + categoryOf(c, id) + ")");
                }
                String saved = c.onClient(mc -> (String) Mod.get("auction.AuctionConfig", "getLastBazaarCategoryFilter"));
                c.check(cat.equals(saved), "tab not remembered: " + saved);
                c.note(cat + ": " + rows.size() + " rows on screen, all " + cat);
            }

            // Search: a press on the box focuses it; typing filters every category.
            press(c, layout(c), "search");
            c.ctx().getInput().typeChars("enchanted dia");
            c.ctx().waitTicks(4);
            String m = mode(c);
            c.check(m.contains("|enchanted dia|"), "query after typing: " + m);
            List<String[]> hits = hots(layout(c), "product:");
            c.check(!hits.isEmpty(), "no results for 'enchanted dia'");
            Set<String> cats = new HashSet<>();
            for (String[] r : hits) {
                String id = r[0].substring("product:".length());
                String name = nameOf(c, id).toLowerCase(Locale.ROOT);
                c.check(name.contains("enchanted dia") || id.toLowerCase(Locale.ROOT).contains("enchanted dia"),
                        "search result " + id + " (" + name + ")");
                cats.add(categoryOf(c, id));
            }
            c.note("typed 'enchanted dia': " + hits.size() + " results from " + cats);
            c.ctx().getInput().pressKey(259); // Backspace
            c.ctx().waitTicks(3);
            c.check(mode(c).contains("|enchanted di|"), "Backspace: " + mode(c));
            // A key that would close the menu types into the focused box instead.
            c.ctx().getInput().typeChars("e");
            c.ctx().waitTicks(3);
            c.check(mode(c).contains("|enchanted die|") && c.onClient(mc -> McCompat.screen(mc) != null),
                    "'e' in the search box: " + mode(c));
            press(c, layout(c), "tab:Mining");
            c.ctx().waitTicks(3);
            c.check(mode(c).contains("|Mining||"), "a tab press did not clear the search: " + mode(c));
            c.note("Backspace edits, 'e' types, a tab clears the search");

            // Sort by a column heading.
            press(c, layout(c), "sort:BUY_PRICE_HIGH");
            c.ctx().waitTicks(3);
            String sort = c.onClient(mc -> String.valueOf(Mod.get("auction.AuctionConfig", "getLastBazaarSort")));
            c.check(sort.equals("BUY_PRICE_HIGH"), "sort after the Buy heading: " + sort);
            c.check(clicks(c) == 0 && c.commands().isEmpty(), "browsing sent " + clicks(c) + " clicks, commands "
                    + c.commands());
            c.note("Buy heading -> sort " + sort + "; nothing sent to the server while browsing");
        }
    }

    // ---- 484 ------------------------------------------------------------------------------------------------------

    static void bottom(Session c) throws Exception {
        try (Env env = new Env(c)) {
            feedAll(c);
            setWindow(c, 1920, 1080, 2);
            cookie(c, false);
            // Hypixel's main page (the NPC's).
            BazaarReskinCases.open(c, "menus.reskin-bazaar-category", "CATEGORY");
            List<String> layout = layout(c);
            List<String[]> bottom = hots(layout, "bottom:");
            Map<String, String> slots = new HashMap<>();
            bottom.forEach(b -> slots.put(b[0], b[1]));
            c.check(slots.equals(Map.of("bottom:Sell_Inventory_Now", "47", "bottom:Sell_Sacks_Now", "48",
                    "bottom:Manage_Orders", "50")), "main page bottom bar: " + slots);
            for (String other : new String[]{"45", "49", "51", "52"}) {
                c.check(hot(layout, other) == null, "Hypixel's slot " + other + " (Search/Close/History/Settings) is drawn");
            }
            int base = clicks(c);
            int n = 0;
            for (String key : List.of("bottom:Sell_Inventory_Now", "bottom:Sell_Sacks_Now", "bottom:Manage_Orders")) {
                int before = clicks(c);
                press(c, layout, key);
                c.waitUntil("a click from " + key, mc -> clicks(c) > before, 40);
                c.ctx().waitTicks(4);
                n++;
                JsonObject k = lastClick(c);
                c.check(clicks(c) == base + n, key + ": " + (clicks(c) - base) + " clicks after " + n + " presses");
                c.check(k.get("slot").getAsString().equals(slots.get(key)) && k.get("input").getAsString().equals("PICKUP"),
                        key + " sent slot " + k.get("slot") + " " + k.get("input"));
                c.note(key + " -> one PICKUP on slot " + k.get("slot"));
            }
            c.check(c.commands().isEmpty(), "the bottom bar sent commands: " + c.commands());

            // The product page: Manage Orders is its own slot 32.
            MenuKit.reset(c);
            BazaarReskinCases.open(c, "menus.reskin-bazaar-product", "PRODUCT");
            List<String> pl = layout(c);
            int[] mo = hot(pl, "32");
            c.check(mo != null, "product page: Manage Orders (32) not in the bottom bar");
            int before = clicks(c);
            press(c, pl, "bottom:Manage_Orders");
            c.waitUntil("a click on the product page's Manage Orders", mc -> clicks(c) > before, 40);
            c.ctx().waitTicks(4);
            c.check(clicks(c) == before + 1 && lastClick(c).get("slot").getAsInt() == 32, "product page Manage Orders: "
                    + (clicks(c) - before) + " clicks, last slot " + lastClick(c).get("slot"));
            c.note("product page: Manage Orders -> one click on slot 32");

            // Remote: the menu-less screen sends /bz and presses Manage Orders once, as the follow-up.
            MenuKit.reset(c);
            cookie(c, true);
            JsonObject main = on(MenuKit.menu("menus.reskin-bazaar-category"), 50, MenuKit.menu("menus.bazaar-orders"));
            onCommand(c, main);
            openScreen(c);
            int cmds = c.commands().size();
            int b2 = clicks(c);
            press(c, layout(c), "bottom:Manage_Orders");
            c.waitUntil("Manage Orders reskinned", mc -> "ORDERS".equals(Mod.staticCall(RESKIN, "kindForTest")), 100);
            c.ctx().waitTicks(20);
            List<String> sent = c.commands().subList(cmds, c.commands().size());
            c.check(sent.equals(List.of("bz")), "remote Manage Orders sent " + sent);
            c.check(clicks(c) == b2 + 1 && lastClick(c).get("slot").getAsInt() == 50, "remote Manage Orders: "
                    + (clicks(c) - b2) + " clicks, last " + lastClick(c));
            c.note("remote: '/bz' then one follow-up click on slot 50 -> " + kind(c) + "; " + follow(c));
        }
    }

    // ---- 485 ------------------------------------------------------------------------------------------------------

    private static final AtomicInteger PROBE_LAST = new AtomicInteger();
    private static final AtomicInteger PROBE_MISC = new AtomicInteger();
    private static boolean probesAdded;

    private static void addProbes(Session c) {
        if (probesAdded) {
            return;
        }
        c.ctx().runOnClient(mc -> {
            net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                    Identifier.fromNamespaceAndPath("testkit", "bazaar_probe_last"), (g, d) -> {
                        PROBE_LAST.incrementAndGet();
                        g.fill(0, 0, 8, 8, 0xFFFF00FF);
                    });
            net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.attachElementAfter(
                    net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.MISC_OVERLAYS,
                    Identifier.fromNamespaceAndPath("testkit", "bazaar_probe_misc"), (g, d) -> {
                        PROBE_MISC.incrementAndGet();
                        g.fill(0, 10, 8, 18, 0xFFFF00FF);
                    });
        });
        probesAdded = true;
    }

    /** Probe calls over {@code ticks}: {last, misc, the mod's skipped count}. */
    private static int[] probeOver(Session c, int ticks) {
        c.ctx().waitTicks(3);
        int skipped = c.onClient(mc -> (Integer) Mod.staticCall(HUD, "skippedForTest"));
        PROBE_LAST.set(0);
        PROBE_MISC.set(0);
        c.ctx().waitTicks(ticks);
        int skippedAfter = c.onClient(mc -> (Integer) Mod.staticCall(HUD, "skippedForTest"));
        return new int[]{PROBE_LAST.get(), PROBE_MISC.get(), skippedAfter - skipped};
    }

    static void huds(Session c) throws Exception {
        try (Env env = new Env(c)) {
            feedAll(c);
            addProbes(c);
            int[] none = probeOver(c, 20);
            c.check(none[0] > 0 && none[1] > 0, "with no screen the probe layers drew last=" + none[0] + " misc=" + none[1]
                    + " - the probes themselves do not run");
            c.check(none[2] == 0, "the gate skipped " + none[2] + " draws with no Bazaar open");
            c.note("no screen: probes drew " + none[0] + "/" + none[1] + " times, gate skipped 0");

            openScreen(c);
            int[] open = probeOver(c, 20);
            c.check(open[0] == 0 && open[1] == 0, "Bazaar screen, Hide HUDs on: probes drew last=" + open[0] + " misc="
                    + open[1]);
            c.check(open[2] > 20, "Bazaar screen: the gate skipped only " + open[2] + " layer draws in 20 ticks");
            BufferedImage img = shot(c, "v3-huds-hidden");
            int magenta = 0;
            for (int y = 0; y < 60; y++) {
                for (int x = 0; x < 60; x++) {
                    int p = img.getRGB(x, y);
                    if (((p >> 16) & 0xFF) > 60 && (p & 0xFF) > 60 && ((p >> 8) & 0xFF) < 20) {
                        magenta++;
                    }
                }
            }
            c.check(magenta == 0, magenta + " magenta probe pixels in the corner with HUDs hidden");
            c.note("Bazaar screen, Hide HUDs on: probes 0/0, gate skipped " + open[2] + " draws (mod's layers + probes), "
                    + "0 probe pixels");

            c.ctx().runOnClient(mc -> Mod.set("bazaar.BazaarConfig", "setHideHuds", false));
            int[] off = probeOver(c, 20);
            c.check(off[0] > 0 && off[1] > 0, "Hide HUDs off: probes drew last=" + off[0] + " misc=" + off[1]);
            c.check(off[2] == 0, "Hide HUDs off: the gate still skipped " + off[2]);
            c.note("Hide HUDs off: probes drew " + off[0] + "/" + off[1] + " (the gate is what hid them)");
            c.ctx().runOnClient(mc -> Mod.set("bazaar.BazaarConfig", "setHideHuds", true));

            // A reskinned Hypixel menu.
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
            BazaarReskinCases.open(c, "menus.reskin-bazaar-product", "PRODUCT");
            int[] reskin = probeOver(c, 20);
            c.check(reskin[0] == 0 && reskin[1] == 0, "reskin, Hide HUDs on: probes drew last=" + reskin[0] + " misc="
                    + reskin[1]);
            c.check(reskin[2] > 20, "reskin: the gate skipped only " + reskin[2]);
            c.ctx().runOnClient(mc -> Mod.set("bazaar.BazaarConfig", "setHideHuds", false));
            int[] reskinOff = probeOver(c, 20);
            c.check(reskinOff[0] > 0 && reskinOff[1] > 0, "reskin, Hide HUDs off: probes drew last=" + reskinOff[0]
                    + " misc=" + reskinOff[1]);
            c.ctx().runOnClient(mc -> Mod.set("bazaar.BazaarConfig", "setHideHuds", true));
            c.note("reskin: probes 0/0 with Hide HUDs on (gate skipped " + reskin[2] + "); off, they drew "
                    + reskinOff[0] + "/" + reskinOff[1]);

            MenuKit.reset(c);
            int[] after = probeOver(c, 20);
            c.check(after[0] > 0 && after[1] > 0 && after[2] == 0, "after closing: probes " + after[0] + "/" + after[1]
                    + ", skipped " + after[2]);
            c.note("closed: probes drew again " + after[0] + "/" + after[1]);
        }
    }

    // ---- 486 ------------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static List<String> frames(Session c) {
        return c.onClient(mc -> (List<String>) Mod.staticCall(HUD, "framesForTest"));
    }

    static void record(Session c, boolean on) {
        c.ctx().runOnClient(mc -> Mod.staticCall(HUD, "recordFramesForTest", on));
    }

    /** Frame problems: Hypixel's chest, no screen, another screen, or an empty content panel. */
    static List<String> badFrames(List<String> frames) {
        List<String> bad = new ArrayList<>();
        for (String f : frames) {
            String what = f.substring(f.indexOf(' ') + 1);
            if (!(what.startsWith("screen ") || what.startsWith("reskin "))) {
                bad.add(f);
            } else if (what.matches(".*items=0$")) {
                bad.add(f + " (empty panel)");
            }
        }
        return bad;
    }

    static String sequence(List<String> frames) {
        List<String> out = new ArrayList<>();
        String last = null;
        int run = 0;
        for (String f : frames) {
            String what = f.substring(f.indexOf(' ') + 1).replaceAll(" items=\\d+", "");
            if (what.equals(last)) {
                run++;
                continue;
            }
            if (last != null) {
                out.add(last + " x" + run);
            }
            last = what;
            run = 1;
        }
        if (last != null) {
            out.add(last + " x" + run);
        }
        return String.join(" -> ", out);
    }

    static void seamless(Session c) throws Exception {
        try (Env env = new Env(c)) {
            feedAll(c);
            setWindow(c, 1920, 1080, 2);
            Set<String> fixed = Set.of("panel", "header", "tabs", "rail", "content", "bottom", "product", "cards", "card",
                    "book");

            // A: a reskinned page to the next one.
            JsonObject product = on(MenuKit.menu("menus.reskin-bazaar-product"), 15, MenuKit.menu("menus.reskin-bazaar-order-amount"));
            BazaarReskinCases.open(c, product, "PRODUCT");
            List<String> chromeBefore = fixedRegions(layout(c), CHROME);
            record(c, true);
            press(c, layout(c), "15");
            c.waitUntil("the amount step", mc -> "ORDER_AMOUNT".equals(Mod.staticCall(RESKIN, "kindForTest")), 60);
            c.ctx().waitTicks(10);
            record(c, false);
            List<String> fa = frames(c);
            c.check(fa.size() >= 10, "only " + fa.size() + " frames recorded");
            c.check(badFrames(fa).isEmpty(), "A: bad frames: " + badFrames(fa));
            c.check(fixedRegions(layout(c), CHROME).equals(chromeBefore), "A: the chrome moved: " + chromeBefore + " vs "
                    + fixedRegions(layout(c), CHROME));
            c.note("A product -> amount step: " + fa.size() + " frames: " + sequence(fa));

            // B: the next page opens empty and its items trickle in, one slot a tick, its signature item last.
            MenuKit.reset(c);
            BazaarReskinCases.open(c, "menus.reskin-bazaar-product", "PRODUCT");
            JsonObject amount = MenuKit.menu("menus.reskin-bazaar-order-amount");
            JsonObject empty = new JsonObject();
            empty.addProperty("title", amount.get("title").getAsString());
            empty.addProperty("rows", amount.get("rows").getAsInt());
            empty.add("slots", new JsonObject());
            record(c, true);
            MenuKit.show(c, empty);
            MenuKit.awaitScreen(c, "How many do you want?", 60);
            c.ctx().waitTicks(4);
            BufferedImage during = shot(c, "v3-seamless-empty-next-page");
            int grey = BazaarReskinCases.count(during, CHEST_GREY);
            String midSummary = summary(c);
            c.check(grey < 50, "B: " + grey + " chest-grey pixels while the next page is empty");
            c.check(midSummary.contains("frozen") && midSummary.contains("PRODUCT"), "B: while empty the panel shows "
                    + midSummary);
            List<Map.Entry<String, com.google.gson.JsonElement>> items = new ArrayList<>(amount.getAsJsonObject("slots")
                    .entrySet());
            items.sort((x, y) -> Boolean.compare(x.getValue().toString().contains("Custom Amount"),
                    y.getValue().toString().contains("Custom Amount")));
            for (Map.Entry<String, com.google.gson.JsonElement> e : items) {
                JsonObject a = new JsonObject();
                a.addProperty("slot", Integer.parseInt(e.getKey()));
                a.add("item", e.getValue());
                c.hx().call("menu.set", a);
                c.ctx().waitTicks(1);
            }
            c.waitUntil("the trickled amount step reskinned", mc -> "ORDER_AMOUNT".equals(Mod.staticCall(RESKIN,
                    "kindForTest")), 60);
            c.ctx().waitTicks(8);
            record(c, false);
            List<String> fb = frames(c);
            c.check(badFrames(fb).isEmpty(), "B: bad frames: " + badFrames(fb));
            c.note("B empty next page + " + items.size() + " slots trickled in: " + fb.size() + " frames, chest grey "
                    + grey + " mid-way (" + midSummary + "): " + sequence(fb));

            // C: the menu-less screen to Hypixel's product page, through /bz and the follow-up click.
            MenuKit.reset(c);
            cookie(c, true);
            onCommand(c, on(results("Wheat"), 10, MenuKit.menu("menus.reskin-bazaar-product")));
            openScreen(c);
            searchFor(c, "Wheat");
            int base = clicks(c);
            record(c, true);
            press(c, layout(c), "product:WHEAT");
            c.ctx().waitTicks(1);
            List<String> preview = fixedRegions(layout(c), fixed);
            BufferedImage early = shot(c, "v3-seamless-opening-product");
            c.waitUntil("Hypixel's product page", mc -> "PRODUCT".equals(Mod.staticCall(RESKIN, "kindForTest")), 120);
            c.ctx().waitTicks(10);
            record(c, false);
            List<String> fc = frames(c);
            List<String> productRegions = fixedRegions(layout(c), fixed);
            c.check(badFrames(fc).isEmpty(), "C: bad frames: " + badFrames(fc));
            c.check(fc.stream().noneMatch(f -> f.contains("CONTAINER SEARCH")), "C: the search results flashed: "
                    + sequence(fc));
            c.check(clicks(c) == base + 1, "C: " + (clicks(c) - base) + " clicks");
            c.check(preview.equals(productRegions), "C: the product layout moved between the preview and Hypixel's page:\n"
                    + preview + "\n" + productRegions);
            c.check(BazaarReskinCases.count(early, CHEST_GREY) < 50, "C: chest grey while opening");
            c.note("C screen -> /bz -> results (held) -> follow-up -> product: " + fc.size() + " frames: " + sequence(fc));
        }
    }

    // ---- 487 ------------------------------------------------------------------------------------------------------

    static void productClick(Session c) throws Exception {
        try (Env env = new Env(c)) {
            feedAll(c);
            setWindow(c, 1920, 1080, 2);

            // With a Booster Cookie, from Hypixel's own main page.
            cookie(c, true);
            onCommand(c, on(results("Wheat", "Enchanted Wheat"), 10, MenuKit.menu("menus.reskin-bazaar-product")));
            BazaarReskinCases.open(c, "menus.reskin-bazaar-category", "CATEGORY");
            searchFor(c, "Wheat");
            int cmds = c.commands().size();
            int base = clicks(c);
            press(c, layout(c), "product:WHEAT");
            c.waitUntil("the product page", mc -> "PRODUCT".equals(Mod.staticCall(RESKIN, "kindForTest")), 120);
            c.ctx().waitTicks(6);
            List<String> sent = c.commands().subList(cmds, c.commands().size());
            c.check(sent.equals(List.of("bz Wheat")), "with a Cookie the press sent " + sent);
            c.check(clicks(c) == base + 1 && lastClick(c).get("slot").getAsInt() == 10, "with a Cookie: "
                    + (clicks(c) - base) + " clicks, last " + lastClick(c));
            c.note("Cookie: press on Wheat -> '/bz Wheat', one follow-up click on result slot 10 -> PRODUCT");

            // At the NPC without one: each press is one click towards the product, no command.
            MenuKit.reset(c);
            cookie(c, false);
            c.ctx().runOnClient(mc -> Mod.staticCall(VIEW, "resetForTest"));
            JsonObject group = on(MenuKit.menu("menus.reskin-bazaar-group"), 10, MenuKit.menu("menus.reskin-bazaar-product"));
            JsonObject main = on(MenuKit.menu("menus.reskin-bazaar-category"), 11, group);
            BazaarReskinCases.open(c, main, "CATEGORY");
            searchFor(c, "Wheat");
            cmds = c.commands().size();
            base = clicks(c);
            press(c, layout(c), "product:WHEAT");
            c.waitUntil("the group page", mc -> "GROUP".equals(Mod.staticCall(RESKIN, "kindForTest")), 60);
            c.ctx().waitTicks(4);
            c.check(clicks(c) == base + 1 && lastClick(c).get("slot").getAsInt() == 11, "no Cookie, first press: "
                    + (clicks(c) - base) + " clicks, last " + lastClick(c));
            List<String> gl = layout(c);
            c.check(gl.stream().anyMatch(l -> l.startsWith("hotspot 10 ")), "the group page has no Wheat row");
            c.check(mode(c).contains("|WHEAT|"), "Wheat is not the highlighted target: " + mode(c));
            press(c, gl, "10");
            c.waitUntil("the product page", mc -> "PRODUCT".equals(Mod.staticCall(RESKIN, "kindForTest")), 60);
            c.ctx().waitTicks(4);
            c.check(clicks(c) == base + 2 && lastClick(c).get("slot").getAsInt() == 10, "no Cookie, second press: "
                    + (clicks(c) - base) + " clicks, last " + lastClick(c));
            c.check(c.commands().size() == cmds, "no Cookie: commands were sent " + c.commands().subList(cmds,
                    c.commands().size()));
            c.note("no Cookie: press 1 -> one click on group slot 11 (Wheat & Seeds), press 2 -> one click on Wheat (10);"
                    + " no command");

            // Another category's product: the first press is that category's tab in Hypixel's menu.
            MenuKit.reset(c);
            c.ctx().runOnClient(mc -> Mod.staticCall(VIEW, "resetForTest"));
            BazaarReskinCases.open(c, "menus.reskin-bazaar-category", "CATEGORY");
            searchFor(c, "Enchanted Diamond");
            int b3 = clicks(c);
            press(c, layout(c), "product:ENCHANTED_DIAMOND");
            c.waitUntil("a click", mc -> clicks(c) > b3, 40);
            c.ctx().waitTicks(4);
            c.check(clicks(c) == b3 + 1 && lastClick(c).get("slot").getAsInt() == 9, "no Cookie, Mining product: "
                    + (clicks(c) - b3) + " clicks, last " + lastClick(c));
            c.check(c.commands().size() == cmds, "no Cookie: a command was sent");
            c.note("no Cookie, Enchanted Diamond from Farming: one click on Mining (slot 9); status '"
                    + mode(c).replaceAll(".*\\|", "") + "'");
        }
    }

    // ---- 488 ------------------------------------------------------------------------------------------------------

    /** One follow-up sub-case: the server answers /bz with {@code answer}; returns the container clicks made. */
    private static int followCase(Session c, String what, JsonObject answer, boolean press, int waitTicks) {
        MenuKit.reset(c);
        c.ctx().runOnClient(mc -> Mod.staticCall(VIEW, "resetForTest"));
        onCommand(c, answer);
        openScreen(c);
        searchFor(c, "Wheat");
        int base = clicks(c);
        if (press) {
            int cmds = c.commands().size();
            press(c, layout(c), "product:WHEAT");
            c.waitUntil(what + ": /bz sent", mc -> c.commands().size() > cmds, 40);
        }
        c.ctx().waitTicks(waitTicks);
        int n = clicks(c) - base;
        c.note(what + ": " + n + " follow-up click(s); kind " + kind(c) + "; " + follow(c));
        return n;
    }

    static void followUp(Session c) throws Exception {
        try (Env env = new Env(c)) {
            feedAll(c);
            setWindow(c, 1920, 1080, 2);
            cookie(c, true);
            boolean cheat = Mod.isCheat();
            c.note((cheat ? "cheat" : "legit") + " jar");

            int one = followCase(c, "one match", on(results("Wheat", "Enchanted Wheat"), 10,
                    MenuKit.menu("menus.reskin-bazaar-product")), true, 40);
            c.check(one == 1 && lastClick(c).get("slot").getAsInt() == 10, "one match: " + one + " clicks, last "
                    + lastClick(c));
            c.check("PRODUCT".equals(kind(c)), "one match landed on " + kind(c));

            int zero = followCase(c, "no match", results("Enchanted Wheat", "Wheat Seeds"), true, 40);
            c.check(zero == 0, "no match: " + zero + " clicks");
            c.check("SEARCH".equals(kind(c)), "no match: the results are not shown (" + kind(c) + ")");

            int two = followCase(c, "two matches", results("Wheat", "Wheat"), true, 40);
            c.check(two == 0, "two matches: " + two + " clicks");
            c.check("SEARCH".equals(kind(c)), "two matches: the results are not shown (" + kind(c) + ")");

            // No product press: the same results arrive on their own.
            followCase(c, "no press", null, false, 0);
            int beforeUnasked = clicks(c);
            MenuKit.show(c, results("Wheat"));
            MenuKit.awaitScreen(c, "Bazaar ➜ \"Wheat\"", 60);
            c.ctx().waitTicks(30);
            c.check(clicks(c) == beforeUnasked, "results with no press of his: " + (clicks(c) - beforeUnasked) + " clicks");
            c.check("SEARCH".equals(kind(c)), "unasked results are not shown as results: " + kind(c));
            c.note("no press: the same single-match results arrive -> 0 clicks; " + follow(c));

            // Timeout: Hypixel answers after the follow-up has expired.
            int beforeTimeout = clicks(c);
            followCase(c, "timeout", null, true, 0);
            c.waitUntil("the follow-up to time out", mc -> String.valueOf(Mod.staticCall(FOLLOW, "stateForTest"))
                    .contains("|idle|"), 160);
            c.check(follow(c).endsWith("timeout"), "follow-up after the wait: " + follow(c));
            MenuKit.show(c, results("Wheat"));
            MenuKit.awaitScreen(c, "Bazaar ➜ \"Wheat\"", 60);
            c.ctx().waitTicks(30);
            c.check(clicks(c) == beforeTimeout, "results after the timeout: " + (clicks(c) - beforeTimeout) + " clicks");
            c.note("timeout: results after " + 5 + " s -> 0 clicks; " + follow(c));

            // Setting off: the results show with Wheat highlighted, and he clicks.
            c.ctx().runOnClient(mc -> Mod.set("bazaar.BazaarConfig", "setFollowUpClick", false));
            int off = followCase(c, "setting off", results("Wheat", "Enchanted Wheat"), true, 40);
            c.check(off == 0, "Follow-up Click off: " + off + " clicks");
            c.check("SEARCH".equals(kind(c)) && mode(c).contains("|WHEAT|"), "setting off: " + kind(c) + " " + mode(c));
            c.ctx().runOnClient(mc -> Mod.set("bazaar.BazaarConfig", "setFollowUpClick", true));

            // Never twice: the result's click brings the same results back.
            JsonObject again = results("Wheat");
            on(again, 10, results("Wheat"));
            int twice = followCase(c, "same results again", again, true, 60);
            c.check(twice == 1, "the same results twice: " + twice + " clicks");
            c.note((cheat ? "cheat" : "legit") + " jar: 1 / 0 / 0 / 0 / 0 / 0 / 1 clicks (one match, none, two, unasked, "
                    + "timeout, off, repeated)");
        }
    }

    // ---- 489 ------------------------------------------------------------------------------------------------------

    static void themes(Session c) throws Exception {
        try (Env env = new Env(c)) {
            feedAll(c);
            c.ctx().runOnClient(mc -> {
                for (String id : new String[]{"ESSENCE_WITHER", "ENCHANTED_DIAMOND", "WHEAT"}) {
                    Mod.staticCall("bazaar.BazaarRecents", "add", id);
                }
            });
            Object old = c.onClient(mc -> Mod.get("inventorytheme.InventoryThemeConfig", "getTheme"));
            try {
                for (String theme : List.of("AMBER", "DARK", "LIGHT")) {
                    c.ctx().runOnClient(mc -> Mod.set("inventorytheme.InventoryThemeConfig", "setTheme",
                            Mod.enumValue("gui.PanelTheme", theme)));
                    for (int[] sz : new int[][]{{1920, 1080, 2}, {854, 480, 2}}) {
                        setWindow(c, sz[0], sz[1], sz[2]);
                        String size = sz[0] + "x" + sz[1] + "-gui" + sz[2];
                        int[] gui = guiSize(c);
                        MenuKit.reset(c);
                        openScreen(c);
                        c.ctx().runOnClient(mc -> Mod.staticCall(VIEW, "selectTabForTest", "Farming"));
                        c.ctx().getInput().setCursorPos(2, 2);
                        c.ctx().waitTicks(3);
                        List<String> probs = problems(layout(c), gui[0], gui[1]);
                        c.check(probs.isEmpty(), theme + " " + size + " browse: " + probs);
                        BufferedImage img = shot(c, "v3-theme-" + theme.toLowerCase(Locale.ROOT) + "-" + size + "-browse");
                        int[] panel = null;
                        for (String l : layout(c)) {
                            if (l.startsWith("region rail ")) {
                                panel = box(l.split(" ", 7));
                            }
                        }
                        if (panel != null) {
                            double scale = c.onClient(mc -> mc.getWindow().getGuiScale());
                            int px = (int) ((panel[0] + panel[2] / 2.0) * scale);
                            int py = (int) ((panel[1] + panel[3] - 6) * scale);
                            int rgb = img.getRGB(px, py) & 0xFFFFFF;
                            int lum = ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
                            c.check(theme.equals("LIGHT") ? lum > 450 : lum < 150, theme + ": the recents rail reads "
                                    + Integer.toHexString(rgb));
                        }
                        c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
                        BazaarReskinCases.open(c, "menus.reskin-bazaar-product", "PRODUCT");
                        c.ctx().waitTicks(3);
                        List<String> rp = problems(layout(c), gui[0], gui[1]);
                        c.check(rp.isEmpty(), theme + " " + size + " product page: " + rp);
                        shot(c, "v3-theme-" + theme.toLowerCase(Locale.ROOT) + "-" + size + "-product");
                        c.note(theme + " " + size + ": browse and product page clean");
                    }
                }
            } finally {
                c.ctx().runOnClient(mc -> Mod.set("inventorytheme.InventoryThemeConfig", "setTheme", old));
            }
        }
    }

    // ---- 490 ------------------------------------------------------------------------------------------------------

    /** "inv" lines of a frame: index -> {x, y, w, h, containerSlot}; {@code ids} gets index -> product id / none / empty. */
    static Map<Integer, int[]> invBoxes(List<String> layout, Map<Integer, String> ids) {
        Map<Integer, int[]> out = new HashMap<>();
        for (String line : layout) {
            String[] p = line.split(" ");
            if (p[0].equals("inv")) {
                int i = Integer.parseInt(p[1]);
                out.put(i, new int[]{Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]),
                        Integer.parseInt(p[5]), Integer.parseInt(p[6])});
                ids.put(i, p[7]);
            }
        }
        return out;
    }

    static int[] box4(int[] b) {
        return new int[]{b[0], b[1], b[2], b[3]};
    }

    static void inventory(Session c) throws Exception {
        try (Env env = new Env(c)) {
            feedAll(c);
            setWindow(c, 1920, 1080, 2);
            c.hx().call("give", "slot", 9, "count", 64, "stack", "minecraft:wheat[custom_data={id:\"WHEAT\"}]");
            c.hx().call("give", "slot", 10, "count", 3, "stack", "minecraft:diamond[custom_data={id:\"ENCHANTED_DIAMOND\"}]");
            c.hx().call("give", "slot", 11, "count", 1, "stack", "minecraft:stick");
            c.hx().call("give", "slot", 0, "count", 1, "stack", "minecraft:bow[custom_data={id:\"TERMINATOR\"}]");
            c.ctx().waitTicks(5);
            try {
                // Hypixel's main page (6 rows: his slots are 54..89); a click on Wheat (54) opens its product page.
                JsonObject main = on(MenuKit.menu("menus.reskin-bazaar-category"), 54, MenuKit.menu("menus.reskin-bazaar-product"));
                BazaarReskinCases.open(c, main, "CATEGORY");
                List<String> layout = layout(c);
                Map<Integer, String> ids = new HashMap<>();
                Map<Integer, int[]> inv = invBoxes(layout, ids);
                c.check(inv.size() == 36, "the inventory panel drew " + inv.size() + " slots, not 36");
                c.check(inv.get(0)[4] == 54 && "WHEAT".equals(ids.get(0)), "slot 9 (Wheat) -> " + inv.get(0)[4] + " " + ids.get(0));
                c.check(inv.get(1)[4] == 55 && "ENCHANTED_DIAMOND".equals(ids.get(1)), "slot 10 -> " + inv.get(1)[4] + " "
                        + ids.get(1));
                c.check(inv.get(2)[4] == 56 && "none".equals(ids.get(2)), "the stick -> " + inv.get(2)[4] + " " + ids.get(2));
                c.check(inv.get(27)[4] == 81 && "none".equals(ids.get(27)), "the hotbar bow -> " + inv.get(27)[4] + " "
                        + ids.get(27));
                c.check(hot(layout, "54") != null && hot(layout, "55") != null, "no hotspot on the Bazaar items");
                c.check(hot(layout, "56") == null && hot(layout, "81") == null, "a non-Bazaar item has a hotspot");
                c.note("panel: 36 slots; Wheat -> container slot 54, Enchanted Diamond -> 55, stick 56 and Terminator 81 dimmed");
                shot(c, "v3-inventory-main-page");

                int base = clicks(c);
                BazaarReskinCases.pressAt(c, box4(inv.get(2)), 0);
                BazaarReskinCases.pressAt(c, box4(inv.get(27)), 0);
                c.ctx().waitTicks(10);
                c.check(clicks(c) == base, "presses on the stick and the Terminator sent " + (clicks(c) - base) + " clicks");
                c.note("presses on the dimmed stick and Terminator: 0 clicks");

                BazaarReskinCases.shiftPressAt(c, box4(inv.get(1)));
                c.waitUntil("a click from the shift press", mc -> clicks(c) > base, 40);
                c.ctx().waitTicks(4);
                JsonObject k = lastClick(c);
                c.check(clicks(c) == base + 1 && k.get("slot").getAsInt() == 55 && "QUICK_MOVE".equals(k.get("input")
                        .getAsString()), "shift press on Enchanted Diamond: " + (clicks(c) - base) + " clicks, last " + k);
                c.note("shift press on Enchanted Diamond -> one QUICK_MOVE on slot 55");

                int[] w = invBoxes(layout(c), new HashMap<>()).get(0);
                BazaarReskinCases.pressAt(c, box4(w), 0);
                c.waitUntil("the product page", mc -> "PRODUCT".equals(Mod.staticCall(RESKIN, "kindForTest")), 60);
                c.ctx().waitTicks(4);
                JsonObject k2 = lastClick(c);
                c.check(clicks(c) == base + 2 && k2.get("slot").getAsInt() == 54 && "PICKUP".equals(k2.get("input")
                        .getAsString()), "press on Wheat: " + (clicks(c) - base) + " clicks, last " + k2);
                c.check(summary(c).contains("CONTAINER PRODUCT") && !summary(c).contains("frozen"), "after Wheat: " + summary(c));
                shot(c, "v3-inventory-product-page");
                c.note("press on Wheat -> one PICKUP on slot 54; the server's product page is reskinned (" + summary(c) + ")");

                // No menu open: the menu-less screen.
                MenuKit.reset(c);
                cookie(c, true);
                openScreen(c);
                List<String> api = layout(c);
                Map<Integer, String> apiIds = new HashMap<>();
                Map<Integer, int[]> apiInv = invBoxes(api, apiIds);
                c.check(apiInv.size() == 36 && apiInv.get(0)[4] == -1 && "WHEAT".equals(apiIds.get(0)),
                        "menu-less panel: " + apiInv.size() + " slots, Wheat " + apiIds.get(0));
                int cmds = c.commands().size();
                BazaarReskinCases.pressAt(c, box4(apiInv.get(27)), 0);
                c.ctx().waitTicks(10);
                c.check(c.commands().size() == cmds, "a press on the Terminator sent " + c.commands().subList(cmds,
                        c.commands().size()));
                press(c, api, "inv:0");
                c.waitUntil("/bz Wheat", mc -> c.commands().size() > cmds, 40);
                String cmd = c.commands().get(c.commands().size() - 1);
                c.check(cmd.equals("bz Wheat") && mode(c).contains("|WHEAT|"), "menu-less Wheat press: '" + cmd + "' "
                        + mode(c));
                c.check(clicks(c) == base + 2, "the menu-less screen clicked " + (clicks(c) - base - 2));
                c.note("menu-less screen: Terminator press -> nothing; Wheat press -> '" + cmd + "', product shown");
            } finally {
                for (int slot : new int[]{9, 10, 11, 0}) {
                    c.hx().call("give", "slot", slot, "stack", "minecraft:air");
                }
            }
        }
    }
}

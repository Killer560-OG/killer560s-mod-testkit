package dev.testkit.gametest.menu;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.inventory.Slot;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The unified Auction House (mod {@code auction/screen/AuctionHouseScreen}, {@code auction/ah/AhReskin},
 * {@code auction/ah/AhPages}, 2026-10-07): an API-drawn browser and Hypixel's real AH menus reskinned in the same frame.
 * <p>
 * API data is REAL: {@code testkit-ah/auctions-page.json} is 223 auctions trimmed from {@code /v2/skyblock/auctions}
 * pages 0-1 and {@code testkit-ah/auctions-ended.json} the whole {@code /v2/skyblock/auctions_ended} answer, both fetched
 * with curl on 2026-10-07 (end/start times are shifted to "now" before feeding, so nothing has expired). Menus come from
 * {@code menus/auction-house.json} - titles and slots from Skyblocker/SkyHanni/the wiki, {@code verifiedBy: regex}, NOT
 * captures: they prove the mod and the fixtures agree, not that Hypixel still sends exactly this.
 *
 * <ul>
 *   <li>491-menu-ah-browser-layout: the browser drawn from the API fixture in six views at five window/GUI/Auto Scale
 *       combinations; every text inside its region and the screen, no two texts intersect, cards drawn and clickable;
 *       screenshots at GUI 2 and the small window.</li>
 *   <li>492-menu-ah-filters: tabs, search, rarity, BIN/auction, price range and sort give the counts and order computed
 *       here from the raw JSON; a tab and the Type button work through a real mouse press; recents survive a reload from
 *       disk; lowest BIN per item matches the fixture (ids decoded here from item_bytes); auctions_ended feeds the sales
 *       history.</li>
 *   <li>493-menu-ah-open-listing: a real press on a listing sends exactly {@code /viewauction <uuid>} (the API's 32-hex
 *       id) to the server, once, and no container click; a right press sends nothing.</li>
 *   <li>494-menu-ah-reskin-pages: every mapped AH menu reskins as its kind; every non-filler slot has a clickable element
 *       (scrolling through long pages); layout rules; no chest-grey pixels at 1920x1080 GUI 2; drawing sends nothing.</li>
 *   <li>495-menu-ah-reskin-click: idle = 0 clicks; one press = one click on the right slot (left, right, shift; a
 *       listing card, a category tab, a filter, the page arrow, Buy Item Right Now -> the server's Confirm Purchase ->
 *       Confirm, an inventory item on Create BIN Auction); empty panel space = nothing.</li>
 *   <li>496-menu-ah-huds-hidden: with the reskin and with the browser open, the screen's margins are the opaque
 *       backdrop (no HUD or world pixel); with Hypixel's GUI (Left Alt) the same strip shows the hotbar (positive
 *       control).</li>
 *   <li>497-menu-ah-seamless: a page change through the reskin never shows the chest or a blank panel - while the next
 *       menu's items are missing the previous page stays drawn (screenshot: no chest grey, the old page's texts), and the
 *       mod's frame counters stay at 0 chest/0 blank.</li>
 *   <li>498-menu-ah-fallback: Auction Stats and a malformed Auctions Browser stay Hypixel's GUI (chest pixels, vanilla
 *       click); Reskin Real Auction House off likewise; Left Alt shows Hypixel's GUI and releasing brings the reskin
 *       back; the header's "Hypixel menu" button switches to Hypixel's GUI sending nothing.</li>
 *   <li>499-menu-ah-handoff: from the browser to Hypixel and back: the listing's API data is drawn while its Auction View
 *       loads (no chest, no blank), the real view then shows reskinned and lands in Recently viewed; a header tab on the
 *       reskinned view closes the menu (no click) and opens the browser on that category.</li>
 * </ul>
 */
final class AuctionHouseCases {

    private static final String RESKIN = "auction.ah.AhReskin";
    private static final String SCREEN = "auction.screen.AuctionHouseScreen";
    private static final int CHEST_GREY = 0xC6C6C6;
    private static final int LEFT_ALT = 342;
    private static final Set<String> MAIN_CATS = Set.of("weapon", "armor", "accessories", "consumables", "blocks");

    private AuctionHouseCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "491-menu-ah-browser-layout", AuctionHouseCases::browserLayout);
        MenuSuite.test(s, "492-menu-ah-filters", AuctionHouseCases::filters);
        MenuSuite.test(s, "493-menu-ah-open-listing", AuctionHouseCases::openListing);
        MenuSuite.test(s, "494-menu-ah-reskin-pages", AuctionHouseCases::pages);
        MenuSuite.test(s, "495-menu-ah-reskin-click", AuctionHouseCases::click);
        MenuSuite.test(s, "496-menu-ah-huds-hidden", AuctionHouseCases::hudsHidden);
        MenuSuite.test(s, "497-menu-ah-seamless", AuctionHouseCases::seamless);
        MenuSuite.test(s, "498-menu-ah-fallback", AuctionHouseCases::fallback);
        MenuSuite.test(s, "499-menu-ah-handoff", AuctionHouseCases::handoff);
    }

    // ---- shared ---------------------------------------------------------------------------------------------------

    /** Reskin on, AH on, Hypixel Menu key = Left Alt, Skyblock gate off; restored when the case ends. */
    private static final class Env implements AutoCloseable {
        final Session c;
        final MenuKit.Cfg cfg;
        final boolean gate;
        final boolean autoScale;
        final int[] window;

        Env(Session c, boolean autoScaleOn) {
            this.c = c;
            MenuKit.reset(c);
            gate = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
            c.ctx().runOnClient(mc -> Mod.staticCall("util.SkyblockGate", "setEnabled", false));
            cfg = new MenuKit.Cfg(c);
            cfg.set("auction.AuctionConfig", "AhEnabled", true);
            cfg.set("auction.AuctionHouseConfig", "ReskinRealAh", true);
            cfg.set("auction.AuctionHouseConfig", "VanillaKeyCode", LEFT_ALT);
            Object hud = Mod.cfg("hud.HudConfig");
            autoScale = (Boolean) Mod.call(hud, "isAutoScale");
            c.ctx().runOnClient(mc -> Mod.call(hud, "setAutoScale", autoScaleOn));
            window = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(), mc.options.guiScale().get()});
        }

        @Override
        public void close() {
            try {
                MenuKit.reset(c);
                c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
                cfg.close();
                Object hud = Mod.cfg("hud.HudConfig");
                c.ctx().runOnClient(mc -> Mod.call(hud, "setAutoScale", autoScale));
                setWindow(c, window[0], window[1], window[2]);
            } finally {
                c.ctx().runOnClient(mc -> Mod.staticCall("util.SkyblockGate", "setEnabled", gate));
            }
        }
    }

    static void setWindow(Session c, int w, int h, int gui) {
        c.ctx().getInput().resizeWindow(w, h);
        c.ctx().waitTicks(3);
        c.ctx().runOnClient(mc -> {
            mc.options.guiScale().set(gui);
            mc.resizeGui();
        });
        c.ctx().waitTicks(4);
    }

    private static String resource(String path) {
        try (InputStream in = AuctionHouseCases.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new AssertionError("missing resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    /** The real API page with its times moved so the auctions end the same distance from now as from its capture. */
    static JsonObject apiPage() {
        JsonObject root = JsonParser.parseString(resource("/testkit-ah/auctions-page.json")).getAsJsonObject();
        long captured = root.get("lastUpdated").getAsLong();
        long now = System.currentTimeMillis();
        for (JsonElement e : root.getAsJsonArray("auctions")) {
            JsonObject a = e.getAsJsonObject();
            a.addProperty("end", now + (a.get("end").getAsLong() - captured));
            a.addProperty("start", now + (a.get("start").getAsLong() - captured));
        }
        root.addProperty("lastUpdated", now);
        return root;
    }

    /** Feeds the API page through the mod's real parser; returns the auctions fed. */
    static JsonArray feedApi(Session c) {
        JsonObject page = apiPage();
        String body = page.toString();
        int n = c.onClient(mc -> (Integer) Mod.staticCall("auction.AuctionHouseApi", "applyPagesForTest", List.of(body)));
        JsonArray auctions = page.getAsJsonArray("auctions");
        int live = 0;
        for (JsonElement e : auctions) {
            if (e.getAsJsonObject().get("end").getAsLong() > System.currentTimeMillis()) {
                live++;
            }
        }
        c.check(n == live, "the mod's AH parser kept " + n + " of " + live + " live auctions");
        return auctions;
    }

    static long price(JsonObject a) {
        if (!a.get("bin").getAsBoolean()) {
            long top = a.has("highest_bid_amount") ? a.get("highest_bid_amount").getAsLong() : 0;
            for (JsonElement b : a.getAsJsonArray("bids")) {
                top = Math.max(top, b.getAsJsonObject().get("amount").getAsLong());
            }
            if (top > 0) {
                return top;
            }
        }
        return a.get("starting_bid").getAsLong();
    }

    /** ExtraAttributes.id from item_bytes, decoded here (independent of the mod). */
    static String skyblockId(JsonObject a) {
        try {
            byte[] bytes = Base64.getDecoder().decode(a.get("item_bytes").getAsString());
            CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
            ListTag list = root.getListOrEmpty("i");
            return list.getCompoundOrEmpty(0).getCompoundOrEmpty("tag").getCompoundOrEmpty("ExtraAttributes").getStringOr("id", "");
        } catch (Exception e) {
            return "";
        }
    }

    static Screen openBrowser(Session c) {
        Screen s = c.onClient(mc -> {
            try {
                Screen screen = (Screen) Mod.cls(SCREEN).getConstructor(Screen.class).newInstance((Object) null);
                McCompat.setScreen(mc, screen);
                return screen;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        c.ctx().waitTicks(4);
        return s;
    }

    @SuppressWarnings("unchecked")
    static List<String> browserLayout(Session c, Screen s) {
        c.ctx().waitTicks(2);
        return c.onClient(mc -> (List<String>) Mod.call(s, "layoutReport"));
    }

    static String kind(Session c) {
        return c.onClient(mc -> (String) Mod.staticCall(RESKIN, "kindForTest"));
    }

    @SuppressWarnings("unchecked")
    static List<String> reskinLayout(Session c) {
        return c.onClient(mc -> (List<String>) Mod.staticCall(RESKIN, "layoutReportForTest"));
    }

    static float factor(Session c) {
        return c.onClient(mc -> (Float) Mod.staticCall(RESKIN, "factorForTest"));
    }

    static JsonObject show(Session c, String fixture, String kind) {
        JsonObject spec = MenuKit.menu(fixture);
        return show(c, spec, kind);
    }

    static JsonObject show(Session c, JsonObject spec, String kind) {
        MenuKit.show(c, spec);
        MenuKit.awaitScreen(c, plainTitle(spec), 100);
        c.waitUntil("the AH reskin to show '" + plainTitle(spec) + "' as " + kind,
                mc -> kind.equals(Mod.staticCall(RESKIN, "kindForTest")), 60);
        c.ctx().waitTicks(3);
        return spec;
    }

    static String plainTitle(JsonObject spec) {
        return spec.get("title").getAsString().replaceAll("§.", "");
    }

    /** {x, y, w, h} of the first hotspot with this id, or null. */
    static int[] hotspot(List<String> layout, String id) {
        for (String line : layout) {
            String[] p = line.split(" ", 7);
            if (p[0].equals("hotspot") && p[1].equals(id)) {
                return new int[]{Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5])};
            }
        }
        return null;
    }

    static List<String> hotspotIds(List<String> layout) {
        List<String> out = new ArrayList<>();
        for (String line : layout) {
            String[] p = line.split(" ", 7);
            if (p[0].equals("hotspot")) {
                out.add(p[1]);
            }
        }
        return out;
    }

    /** Presses a layout box: layout units * {@code factor} = GUI units; * GUI scale = window pixels. */
    static void press(Session c, int[] box, int button, float factor) {
        cursor(c, box, factor);
        c.ctx().getInput().pressMouse(button);
        c.ctx().waitTicks(3);
    }

    static void cursor(Session c, int[] box, float factor) {
        double[] at = c.onClient(mc -> {
            double scale = mc.getWindow().getGuiScale();
            return new double[]{(box[0] + box[2] / 2.0) * factor * scale, (box[1] + box[3] / 2.0) * factor * scale};
        });
        c.ctx().getInput().setCursorPos(at[0], at[1]);
        c.ctx().waitTicks(2);
    }

    static void shiftPress(Session c, int[] box, float factor) {
        cursor(c, box, factor);
        c.ctx().runOnClient(mc -> {
            long window = mc.getWindow().handle();
            Mod.call(mc.mouseHandler, "onButton", window, new net.minecraft.client.input.MouseButtonInfo(0, 1), 1);
            Mod.call(mc.mouseHandler, "onButton", window, new net.minecraft.client.input.MouseButtonInfo(0, 1), 0);
        });
        c.ctx().waitTicks(3);
    }

    /** "frame W H" of a layout report, or the given fallback. */
    static int[] frameSize(List<String> layout, int[] fallback) {
        for (String line : layout) {
            if (line.startsWith("frame ")) {
                String[] p = line.split(" ");
                return new int[]{Integer.parseInt(p[1]), Integer.parseInt(p[2])};
            }
        }
        return fallback;
    }

    /** Texts inside the frame and a region of their name; no two texts intersect. */
    static List<String> problems(List<String> layout) {
        int[] size = frameSize(layout, new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE});
        return BazaarReskinCases.layoutProblems(layout, size[0], size[1]);
    }

    static int texts(List<String> layout) {
        return (int) layout.stream().filter(l -> l.startsWith("text ")).count();
    }

    static int clicks(Session c) {
        return c.events("container.click").size();
    }

    static JsonObject lastClick(Session c) {
        List<JsonObject> e = c.events("container.click");
        return e.isEmpty() ? null : e.get(e.size() - 1);
    }

    static String describe(JsonObject k) {
        return k == null ? "none" : "slot " + k.get("slot") + " button " + k.get("button") + " " + k.get("input");
    }

    static BufferedImage shot(Session c, String label) {
        return BazaarReskinCases.shot(c, label);
    }

    static int count(BufferedImage img, int rgb) {
        return BazaarReskinCases.count(img, rgb);
    }

    static int backdrop(Session c) {
        return c.onClient(mc -> {
            Object theme = Mod.staticCall("auction.ah.AhTheme", "current");
            return (Integer) Mod.call(theme, "backdrop");
        }) & 0xFFFFFF;
    }

    // ---- 491 ------------------------------------------------------------------------------------------------------

    static void browserLayout(Session c) throws Exception {
        try (Env env = new Env(c, false)) {
            feedApi(c);
            String oldCat = c.onClient(mc -> (String) Mod.get("auction.AuctionConfig", "getLastCategoryFilter"));
            // {window w, h, GUI scale, Auto Scale, screenshot}
            int[][] sizes = {{854, 480, 2, 0, 1}, {1920, 1080, 2, 0, 1}, {1920, 1080, 4, 0, 0}, {1280, 720, 3, 0, 0},
                    {1920, 1080, 2, 1, 0}};
            String[][] views = {{"all", "", ""}, {"weapons", "weapon", ""}, {"accessories", "accessories", ""},
                    {"search", "", "ring"}, {"misc", "*misc", ""}, {"blocks-empty", "blocks", ""}};
            try {
                for (int[] sz : sizes) {
                    Object hud = Mod.cfg("hud.HudConfig");
                    c.ctx().runOnClient(mc -> Mod.call(hud, "setAutoScale", sz[3] == 1));
                    setWindow(c, sz[0], sz[1], sz[2]);
                    String size = sz[0] + "x" + sz[1] + "-gui" + sz[2] + (sz[3] == 1 ? "-auto" : "");
                    for (String[] v : views) {
                        Screen s = openBrowser(c);
                        c.ctx().runOnClient(mc -> {
                            Mod.call(s, "preset", v[1], v[2], false);
                        });
                        List<String> layout = browserLayout(c, s);
                        int[] wh = c.onClient(mc -> new int[]{s.width, s.height});
                        int shown = c.onClient(mc -> (Integer) Mod.call(s, "shownCountForTest"));
                        List<String> problems = BazaarReskinCases.layoutProblems(layout, wh[0], wh[1]);
                        c.check(problems.isEmpty(), size + "/" + v[0] + ": " + problems.size() + " layout problem(s): "
                                + String.join(" | ", problems.subList(0, Math.min(6, problems.size()))));
                        int t = texts(layout);
                        c.check(t >= 10, size + "/" + v[0] + ": only " + t + " texts drawn");
                        long cards = hotspotIds(layout).stream().filter(id -> id.startsWith("listing:")).count();
                        if (v[0].equals("blocks-empty")) {
                            c.check(shown == 0 && cards == 0, size + ": Blocks has no listings in the fixture, shows " + shown);
                            c.check(layout.stream().anyMatch(l -> l.startsWith("text content ") && l.contains("No listings match")),
                                    size + ": the empty Blocks tab says nothing");
                        } else {
                            c.check(shown > 0 && cards >= Math.min(2, shown), size + "/" + v[0] + ": " + shown + " shown, "
                                    + cards + " cards clickable");
                        }
                        long tabs = hotspotIds(layout).stream().filter(id -> id.startsWith("tab:")).count();
                        c.check(tabs == 7, size + "/" + v[0] + ": " + tabs + " of 7 category tabs drawn");
                        c.note(size + "/" + v[0] + ": screen " + wh[0] + "x" + wh[1] + ", " + shown + " listings, " + cards
                                + " cards on screen, " + t + " texts, layout clean");
                        if (sz[4] == 1 && (v[0].equals("all") || v[0].equals("search") || sz[0] == 1920)) {
                            shot(c, "browser-" + size + "-" + v[0]);
                        }
                    }
                }
                // The mod's theme setting (Inventory Theme: Amber, Dark, Light) drives the look; each must lay out cleanly.
                Object hud = Mod.cfg("hud.HudConfig");
                c.ctx().runOnClient(mc -> Mod.call(hud, "setAutoScale", false));
                setWindow(c, 1920, 1080, 2);
                Object theme = Mod.cfg("inventorytheme.InventoryThemeConfig");
                Object oldTheme = c.onClient(mc -> Mod.call(theme, "getTheme"));
                try {
                    for (String t : new String[]{"DARK", "LIGHT"}) {
                        c.ctx().runOnClient(mc -> Mod.call(theme, "setTheme", Mod.enumValue("gui.PanelTheme", t)));
                        Screen s = openBrowser(c);
                        c.ctx().runOnClient(mc -> Mod.call(s, "preset", "", "", false));
                        List<String> layout = browserLayout(c, s);
                        List<String> problems = BazaarReskinCases.layoutProblems(layout, 960, 540);
                        c.check(problems.isEmpty(), "theme " + t + ": layout problems " + problems);
                        int bd = backdrop(c);
                        BufferedImage img = shot(c, "browser-theme-" + t.toLowerCase(Locale.ROOT));
                        c.check((img.getRGB(2, img.getHeight() - 2) & 0xFFFFFF) == bd, "theme " + t
                                + ": the corner is not the theme's backdrop #" + Integer.toHexString(bd));
                        c.note("theme " + t + ": backdrop #" + Integer.toHexString(bd) + ", layout clean");
                    }
                } finally {
                    c.ctx().runOnClient(mc -> Mod.call(theme, "setTheme", oldTheme));
                }
            } finally {
                c.ctx().runOnClient(mc -> Mod.set("auction.AuctionConfig", "setLastCategoryFilter", oldCat));
            }
        }
    }

    // ---- 492 ------------------------------------------------------------------------------------------------------

    private record Expect(String name, String cat, String rarity, String type, long min, long max, String query) {
    }

    static void filters(Session c) throws Exception {
        try (Env env = new Env(c, false)) {
            JsonArray api = feedApi(c);
            Object ah = Mod.cfg("auction.AuctionHouseConfig");
            Object auction = Mod.cfg("auction.AuctionConfig");
            Expect[] cases = {
                    new Expect("all", "", "", "ALL", 0, 0, ""),
                    new Expect("weapons", "weapon", "", "ALL", 0, 0, ""),
                    new Expect("tools&misc", "*misc", "", "ALL", 0, 0, ""),
                    new Expect("bin only", "", "", "BIN", 0, 0, ""),
                    new Expect("auctions only", "", "", "AUCTION", 0, 0, ""),
                    new Expect("legendary armor", "armor", "LEGENDARY", "ALL", 0, 0, ""),
                    new Expect("1M-10M", "", "", "ALL", 1_000_000, 10_000_000, ""),
                    new Expect("search 'ring'", "", "", "ALL", 0, 0, "ring"),
            };
            Screen s = openBrowser(c);
            for (Expect e : cases) {
                int want = 0;
                int byName = 0;
                for (JsonElement el : api) {
                    JsonObject a = el.getAsJsonObject();
                    String cat = a.get("category").getAsString();
                    if (e.cat().equals("*misc") ? MAIN_CATS.contains(cat) : !e.cat().isEmpty() && !e.cat().equals(cat)) {
                        continue;
                    }
                    boolean bin = a.get("bin").getAsBoolean();
                    if (e.type().equals("BIN") && !bin || e.type().equals("AUCTION") && bin) {
                        continue;
                    }
                    if (!e.rarity().isEmpty() && !e.rarity().equals(a.get("tier").getAsString())) {
                        continue;
                    }
                    long p = price(a);
                    if (e.min() > 0 && p < e.min() || e.max() > 0 && p > e.max()) {
                        continue;
                    }
                    if (!e.query().isEmpty()) {
                        String name = a.get("item_name").getAsString().replaceAll("§.", "").toLowerCase(Locale.ROOT);
                        if (name.contains(e.query())) {
                            byName++;
                        }
                        continue;
                    }
                    want++;
                }
                c.ctx().runOnClient(mc -> {
                    Mod.call(auction, "setLastCategoryFilter", e.cat());
                    Mod.call(auction, "setLastRarityFilter", e.rarity());
                    Mod.call(auction, "setLastSort", Mod.enumValue("auction.AuctionConfig$SortMode", "PRICE_LOW"));
                    Mod.call(ah, "setTypeFilter", Mod.enumValue("auction.AuctionHouseConfig$TypeFilter", e.type()));
                    Mod.call(ah, "setMinPrice", e.min());
                    Mod.call(ah, "setMaxPrice", e.max());
                    Mod.call(s, "setQueryForTest", e.query());
                });
                int shown = c.onClient(mc -> (Integer) Mod.call(s, "shownCountForTest"));
                if (e.query().isEmpty()) {
                    c.check(shown == want, e.name() + ": browser shows " + shown + ", the fixture has " + want);
                } else {
                    // The mod also matches the item catalog's name and the id; never fewer than the name matches.
                    c.check(shown >= byName && byName > 0, e.name() + ": browser shows " + shown + ", " + byName
                            + " fixture names contain it");
                    want = byName;
                }
                long[] prices = c.onClient(mc -> (long[]) Mod.call(s, "shownPricesForTest", 200));
                for (int i = 1; i < prices.length; i++) {
                    c.check(prices[i - 1] <= prices[i], e.name() + ": not sorted low to high at " + i + ": " + prices[i - 1]
                            + " > " + prices[i]);
                }
                c.note(e.name() + ": " + shown + " shown (fixture: " + want + "), sorted low to high");
            }
            // Sort high to low reverses it.
            c.ctx().runOnClient(mc -> {
                Mod.call(auction, "setLastCategoryFilter", "");
                Mod.call(auction, "setLastRarityFilter", "");
                Mod.call(ah, "setTypeFilter", Mod.enumValue("auction.AuctionHouseConfig$TypeFilter", "ALL"));
                Mod.call(ah, "setMinPrice", 0L);
                Mod.call(ah, "setMaxPrice", 0L);
                Mod.call(s, "setQueryForTest", "");
                Mod.call(auction, "setLastSort", Mod.enumValue("auction.AuctionConfig$SortMode", "PRICE_HIGH"));
            });
            long[] high = c.onClient(mc -> (long[]) Mod.call(s, "shownPricesForTest", 300));
            for (int i = 1; i < high.length; i++) {
                c.check(high[i - 1] >= high[i], "high-to-low not sorted at " + i);
            }
            c.note("sort high to low: " + high.length + " prices descending, top " + high[0]);
            c.ctx().runOnClient(mc -> Mod.call(auction, "setLastSort", Mod.enumValue("auction.AuctionConfig$SortMode", "PRICE_LOW")));

            // A real press on the Weapons tab and on the Type button.
            List<String> layout = browserLayout(c, s);
            press(c, hotspot(layout, "tab:Weapons"), 0, 1f);
            String cat = c.onClient(mc -> (String) Mod.call(auction, "getLastCategoryFilter"));
            c.check("weapon".equals(cat), "press on the Weapons tab left the category at '" + cat + "'");
            press(c, hotspot(browserLayout(c, s), "type"), 0, 1f);
            String type = c.onClient(mc -> String.valueOf(Mod.call(ah, "getTypeFilter")));
            c.check("BIN".equals(type), "press on Type (All) gave " + type + ", not BIN");
            press(c, hotspot(browserLayout(c, s), "type"), 1, 1f);
            type = c.onClient(mc -> String.valueOf(Mod.call(ah, "getTypeFilter")));
            c.check("ALL".equals(type), "right press on Type went to " + type + ", not back to ALL");
            c.note("real presses: Weapons tab -> category 'weapon'; Type -> BIN; right press -> back to ALL");
            c.ctx().runOnClient(mc -> Mod.call(auction, "setLastCategoryFilter", ""));

            // Recents persist: a committed search and a viewed listing survive a reload from disk.
            c.ctx().runOnClient(mc -> {
                Mod.call(ah, "clearRecents");
                Mod.call(s, "setQueryForTest", "necron");
                Mod.call(s, "commitSearchForTest");
                Object viewed = newViewed("HYPERION", "Hyperion", "LEGENDARY");
                Mod.call(ah, "addRecentViewed", viewed);
                Mod.call(ah, "save");
                Mod.staticCall("auction.AuctionHouseConfig", "load");
            });
            Object reloaded = Mod.cfg("auction.AuctionHouseConfig");
            @SuppressWarnings("unchecked")
            List<String> searches = c.onClient(mc -> (List<String>) Mod.call(reloaded, "getRecentSearches"));
            @SuppressWarnings("unchecked")
            List<Object> viewed = c.onClient(mc -> (List<Object>) Mod.call(reloaded, "getRecentViewed"));
            c.check(searches.contains("necron"), "recent searches after reload: " + searches);
            c.check(viewed.stream().anyMatch(v -> String.valueOf(v).contains("HYPERION")), "recently viewed after reload: " + viewed);
            List<String> railLayout = browserLayout(c, openBrowser(c));
            boolean railDrawn = railLayout.stream().anyMatch(l -> l.startsWith("text rail ") && l.endsWith(" necron"));
            int guiW = c.onClient(mc -> mc.getWindow().getGuiScaledWidth());
            c.check(railDrawn || guiW < 460, "the rail does not show the recent search (gui width " + guiW + ")");
            c.note("recents after a reload from disk: searches " + searches + ", viewed " + viewed.size() + (railDrawn
                    ? ", drawn in the rail" : ", rail hidden at this width (" + guiW + ")"));

            // Lowest BIN per item, against ids decoded here from item_bytes.
            Map<String, Long> lowest = new HashMap<>();
            Map<String, Integer> binCount = new HashMap<>();
            for (JsonElement el : api) {
                JsonObject a = el.getAsJsonObject();
                if (!a.get("bin").getAsBoolean()) {
                    continue;
                }
                String id = skyblockId(a);
                if (id.isEmpty()) {
                    continue;
                }
                lowest.merge(id, a.get("starting_bid").getAsLong(), Math::min);
                binCount.merge(id, 1, Integer::sum);
            }
            int checked = 0;
            for (Map.Entry<String, Long> e : lowest.entrySet()) {
                long got = c.onClient(mc -> (Long) Mod.staticCall("auction.ah.AhMarket", "lowestBin", e.getKey()));
                c.check(got == e.getValue(), "lowest BIN of " + e.getKey() + ": mod " + got + ", fixture " + e.getValue());
                checked++;
            }
            long multi = binCount.values().stream().filter(n -> n > 1).count();
            c.check(checked >= 50 && multi >= 3, "only " + checked + " ids / " + multi + " with competition checked");
            c.note("lowest BIN matches the fixture for all " + checked + " ids (" + multi + " with 2+ BINs)");

            // auctions_ended -> recent sales.
            String ended = resource("/testkit-ah/auctions-ended.json");
            int added = c.onClient(mc -> (Integer) Mod.staticCall("auction.ah.AhMarket", "applyEnded", ended));
            int again = c.onClient(mc -> (Integer) Mod.staticCall("auction.ah.AhMarket", "applyEnded", ended));
            int total = JsonParser.parseString(ended).getAsJsonObject().getAsJsonArray("auctions").size();
            c.check(added >= total * 9 / 10 && again == 0, "auctions_ended: " + added + " of " + total + " recorded, "
                    + again + " on a repeat (must be 0)");
            c.note("auctions_ended: " + added + " of " + total + " real sales recorded; the same answer again adds " + again);
        }
    }

    private static Object newViewed(String id, String name, String tier) {
        try {
            Class<?> v = Mod.cls("auction.AuctionHouseConfig$Viewed");
            return v.getConstructor(String.class, String.class, String.class, String.class, long.class)
                    .newInstance(id, name, tier, "", System.currentTimeMillis());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // ---- 493 ------------------------------------------------------------------------------------------------------

    static void openListing(Session c) throws Exception {
        try (Env env = new Env(c, false)) {
            feedApi(c);
            c.hx().stub(null, "viewauction", "ah");
            Screen s = openBrowser(c);
            c.ctx().runOnClient(mc -> Mod.call(s, "preset", "", "", false));
            List<String> layout = browserLayout(c, s);
            String first = c.onClient(mc -> (String) Mod.call(s, "listingIdForTest", 0));
            int[] box = hotspot(layout, "listing:" + first);
            c.check(box != null, "no card for the first listing " + first);
            int cmds = c.commands().size();
            int clicks = clicks(c);
            // A right press does nothing.
            press(c, box, 1, 1f);
            c.ctx().waitTicks(10);
            c.check(c.commands().size() == cmds, "a right press sent " + c.commands().subList(cmds, c.commands().size()));
            press(c, box, 0, 1f);
            c.waitUntil("the /viewauction at the server", mc -> c.commands().size() > cmds, 60);
            c.ctx().waitTicks(10);
            List<String> got = c.commands().subList(cmds, c.commands().size());
            c.check(got.equals(List.of("viewauction " + first)), "one press sent " + got + ", want [viewauction " + first + "]");
            c.check(first.matches("[0-9a-f]{32}"), "the id is not the API's 32-hex form: " + first);
            c.check(clicks(c) == clicks, "opening a listing sent " + (clicks(c) - clicks) + " container click(s)");
            String last = c.onClient(mc -> (String) Mod.staticCall("auction.ah.AhNav", "lastCommandForTest"));
            c.note("left press on the first card -> server got exactly " + got + " (mod: '" + last + "'); right press: nothing; 0 clicks");
        }
    }

    // ---- 494 ------------------------------------------------------------------------------------------------------

    private static final String[][] MAPPED = {
            {"menus.ah-main", "MAIN"}, {"menus.ah-browser", "BROWSER"}, {"menus.ah-search", "BROWSER"},
            {"menus.ah-view-bin", "VIEW"}, {"menus.ah-view-auction", "VIEW"}, {"menus.ah-confirm-purchase", "CONFIRM"},
            {"menus.ah-confirm-bid", "CONFIRM"}, {"menus.ah-manage", "MANAGE"}, {"menus.ah-bids", "BIDS"},
            {"menus.ah-create-bin", "CREATE"}, {"menus.ah-duration", "DURATION"}};

    static List<String> realSlots(JsonObject spec) {
        return BazaarReskinCases.realSlots(spec);
    }

    static void pages(Session c) throws Exception {
        try (Env env = new Env(c, false)) {
            feedApi(c);
            for (int pass = 0; pass < 3; pass++) {
                if (pass == 1) {
                    setWindow(c, 1920, 1080, 2);
                } else if (pass == 2) {
                    Object hud = Mod.cfg("hud.HudConfig");
                    c.ctx().runOnClient(mc -> Mod.call(hud, "setAutoScale", true));
                }
                int[] gui = c.onClient(mc -> new int[]{mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()});
                String size = (pass == 0 ? "window-" : pass == 1 ? "1920x1080-gui2-" : "1920x1080-gui2-auto-") + gui[0] + "x" + gui[1];
                for (String[] m : MAPPED) {
                    MenuKit.reset(c);
                    int before = clicks(c);
                    JsonObject spec = show(c, m[0], m[1]);
                    List<String> layout = reskinLayout(c);
                    int t = texts(layout);
                    c.check(t >= 8, size + " " + m[1] + ": only " + t + " texts - did the reskin draw?");
                    // Every non-filler slot must be reachable: scroll through the content in 30-unit steps.
                    Set<String> seen = new LinkedHashSet<>(hotspotIds(layout));
                    List<String> problems = new ArrayList<>(problems(layout));
                    for (int px = 30; px <= 900 && !seen.containsAll(realSlots(spec)); px += 30) {
                        int y = px;
                        c.ctx().runOnClient(mc -> Mod.staticCall(RESKIN, "scrollForTest", y));
                        c.ctx().waitTicks(2);
                        List<String> l2 = reskinLayout(c);
                        seen.addAll(hotspotIds(l2));
                        problems.addAll(problems(l2));
                    }
                    c.ctx().runOnClient(mc -> Mod.staticCall(RESKIN, "scrollForTest", 0));
                    List<String> missing = new ArrayList<>();
                    for (String slot : realSlots(spec)) {
                        if (!seen.contains(slot)) {
                            missing.add(slot);
                        }
                    }
                    c.check(missing.isEmpty(), size + " " + m[1] + " (" + m[0] + "): no element for slot(s) " + missing);
                    c.check(problems.isEmpty(), size + " " + m[1] + " (" + m[0] + "): " + problems.size() + " layout problem(s): "
                            + String.join(" | ", problems.subList(0, Math.min(6, problems.size()))));
                    String line = size + " " + m[0] + " -> " + m[1] + ": " + t + " texts, " + realSlots(spec).size()
                            + " slots all clickable";
                    if (pass == 1) {
                        c.ctx().waitTicks(3);
                        BufferedImage img = shot(c, "reskin-" + m[0].replace("menus.", ""));
                        int grey = count(img, CHEST_GREY);
                        c.check(grey < 50, size + " " + m[1] + ": " + grey + " chest-grey pixels - Hypixel's chest is showing");
                        line += ", chest-grey px " + grey;
                    } else if (pass == 0 && (m[1].equals("BROWSER") || m[1].equals("VIEW"))) {
                        shot(c, "reskin-small-" + m[0].replace("menus.", ""));
                    }
                    if (pass == 2) {
                        line += ", Auto Scale factor " + factor(c);
                    }
                    c.check(clicks(c) == before, m[1] + ": opening and drawing sent " + (clicks(c) - before) + " click(s)");
                    c.note(line);
                }
            }
        }
    }

    // ---- 495 ------------------------------------------------------------------------------------------------------

    static void click(Session c) throws Exception {
        try (Env env = new Env(c, false)) {
            feedApi(c);
            JsonObject browser = MenuKit.menu("menus.ah-browser");
            show(c, browser, "BROWSER");
            float f = factor(c);
            int base = clicks(c);
            c.ctx().getInput().setCursorPos(2, 2);
            c.ctx().waitTicks(40);
            c.check(clicks(c) == base, "40 idle ticks sent " + (clicks(c) - base) + " click(s)");
            c.note("idle 40 ticks on the reskinned Auctions Browser: 0 clicks");
            List<String> layout = reskinLayout(c);
            int n = base;
            String[][] presses = {{"11", "0", "PICKUP", "listing card (left)"}, {"12", "1", "PICKUP", "listing card (right)"},
                    {"9", "0", "PICKUP", "Armor tab"}, {"50", "0", "PICKUP", "Sort"}, {"51", "1", "PICKUP", "Item Tier (right)"},
                    {"52", "0", "PICKUP", "BIN Filter"}, {"46", "0", "PICKUP", "Previous Page"}};
            for (String[] p : presses) {
                int[] box = hotspot(layout, p[0]);
                c.check(box != null, "no element for slot " + p[0] + " (" + p[3] + ")");
                int want = n + 1;
                press(c, box, Integer.parseInt(p[1]), f);
                c.waitUntil("one click from " + p[3], mc -> clicks(c) >= want, 40);
                c.ctx().waitTicks(4);
                JsonObject k = lastClick(c);
                c.check(clicks(c) == want, p[3] + ": one press sent " + (clicks(c) - n) + " clicks");
                c.check(k.get("slot").getAsInt() == Integer.parseInt(p[0]) && k.get("button").getAsInt() == Integer.parseInt(p[1])
                        && p[2].equals(k.get("input").getAsString()), p[3] + " sent " + describe(k));
                c.note(p[3] + " -> " + describe(k));
                n = want;
            }
            int[] card = hotspot(layout, "13");
            int want = n + 1;
            shiftPress(c, card, f);
            c.waitUntil("one click from the shift press", mc -> clicks(c) >= want, 40);
            c.ctx().waitTicks(4);
            JsonObject sh = lastClick(c);
            c.check(clicks(c) == want && sh.get("slot").getAsInt() == 13 && "QUICK_MOVE".equals(sh.get("input").getAsString()),
                    "shift press on a card sent " + describe(sh));
            c.note("shift press on a listing card -> " + describe(sh));
            n = want;
            // Empty panel space (the panel's top-left corner, above the header).
            int[] content = null;
            for (String line : layout) {
                if (line.startsWith("region panel ")) {
                    String[] p = line.split(" ");
                    content = new int[]{Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5])};
                }
            }
            c.check(content != null, "no panel region");
            int[] corner = {content[0] + 1, content[1] + 1, 2, 2};
            for (String line : layout) {
                String[] p = line.split(" ", 7);
                if (p[0].equals("hotspot")) {
                    int hx = Integer.parseInt(p[2]), hy = Integer.parseInt(p[3]), hw = Integer.parseInt(p[4]), hh = Integer.parseInt(p[5]);
                    c.check(!(corner[0] >= hx && corner[0] < hx + hw && corner[1] >= hy && corner[1] < hy + hh),
                            "the empty-space probe sits on hotspot " + line);
                }
            }
            press(c, corner, 0, f);
            c.ctx().waitTicks(10);
            int stay = n;
            c.check(clicks(c) == stay, "a press on empty space sent " + (clicks(c) - stay) + " click(s)");
            c.note("press on empty panel space: 0 clicks");

            // Buy Item Right Now -> the server opens Confirm Purchase -> Confirm.
            MenuKit.reset(c);
            JsonObject view = MenuKit.menu("menus.ah-view-bin");
            JsonObject confirm = MenuKit.menu("menus.ah-confirm-purchase");
            JsonObject onView = new JsonObject();
            JsonObject toConfirm = new JsonObject();
            toConfirm.add("open", confirm);
            onView.add("31", toConfirm);
            view.add("on", onView);
            show(c, view, "VIEW");
            int b0 = clicks(c);
            press(c, hotspot(reskinLayout(c), "31"), 0, factor(c));
            c.waitUntil("the click on Buy Item Right Now", mc -> clicks(c) > b0, 40);
            MenuKit.awaitScreen(c, "Confirm Purchase", 60);
            c.waitUntil("Confirm Purchase reskinned", mc -> "CONFIRM".equals(Mod.staticCall(RESKIN, "kindForTest")), 60);
            c.ctx().waitTicks(3);
            JsonObject buy = lastClick(c);
            c.check(clicks(c) == b0 + 1 && buy.get("slot").getAsInt() == 31, "Buy Item Right Now sent " + describe(buy));
            press(c, hotspot(reskinLayout(c), "11"), 0, factor(c));
            c.waitUntil("the click on Confirm", mc -> clicks(c) > b0 + 1, 40);
            c.ctx().waitTicks(5);
            JsonObject conf = lastClick(c);
            c.check(clicks(c) == b0 + 2 && conf.get("slot").getAsInt() == 11, "Confirm sent " + describe(conf));
            c.note("Buy Item Right Now -> " + describe(buy) + "; server opened Confirm Purchase (reskinned CONFIRM); Confirm -> "
                    + describe(conf) + "; 2 presses = 2 clicks");

            // Create BIN Auction: an item in his inventory is drawn and one press clicks its player slot.
            MenuKit.reset(c);
            c.hx().give(0, "minecraft:diamond");
            c.ctx().waitTicks(5);
            show(c, "menus.ah-create-bin", "CREATE");
            int menuSlots = c.onClient(mc -> ((AbstractContainerScreen<?>) McCompat.screen(mc)).getMenu().slots.size());
            String hotbarSlot = String.valueOf(menuSlots - 9); // hotbar slot 0 is the first of the last nine
            int[] inv = hotspot(reskinLayout(c), hotbarSlot);
            c.check(inv != null, "the diamond in hotbar slot 0 has no element (menu slot " + hotbarSlot + ")");
            int c0 = clicks(c);
            press(c, inv, 0, factor(c));
            c.waitUntil("the click on the inventory item", mc -> clicks(c) > c0, 40);
            c.ctx().waitTicks(4);
            JsonObject ic = lastClick(c);
            c.check(clicks(c) == c0 + 1 && ic.get("slot").getAsInt() == Integer.parseInt(hotbarSlot), "inventory press sent " + describe(ic));
            c.note("Create BIN Auction: press on his diamond -> " + describe(ic));
            c.hx().give(0, "minecraft:air");
        }
    }

    // ---- 496 ------------------------------------------------------------------------------------------------------

    /** Pixels in the bottom strip (where the hotbar and stat bars draw) that are not the backdrop. */
    static int nonBackdropBottom(BufferedImage img, int backdrop, int stripPx) {
        int n = 0;
        for (int y = img.getHeight() - stripPx; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) != backdrop) {
                    n++;
                }
            }
        }
        return n;
    }

    static void hudsHidden(Session c) throws Exception {
        try (Env env = new Env(c, false)) {
            feedApi(c);
            setWindow(c, 1920, 1080, 2);
            int backdrop = backdrop(c);
            int strip = 12; // 6 GUI units at GUI 2: inside the 8-unit margin, over the bottom of the hotbar
            show(c, "menus.ah-browser", "BROWSER");
            BufferedImage reskin = shot(c, "huds-reskin");
            int dirty = nonBackdropBottom(reskin, backdrop, strip);
            c.ctx().getInput().holdAlt();
            int vanillaDirty;
            try {
                c.waitUntil("Hypixel's GUI while Left Alt is held", mc -> "NONE".equals(Mod.staticCall(RESKIN, "kindForTest")), 20);
                BufferedImage vanilla = shot(c, "huds-vanilla-control");
                vanillaDirty = nonBackdropBottom(vanilla, backdrop, strip);
            } finally {
                c.ctx().getInput().releaseAlt();
            }
            c.check(vanillaDirty > 1000, "positive control: with Hypixel's GUI only " + vanillaDirty
                    + " non-backdrop pixels in the bottom strip - the strip cannot see the HUD");
            c.check(dirty == 0, "reskin: " + dirty + " pixels in the bottom strip are not the backdrop (#"
                    + Integer.toHexString(backdrop) + ") - something shows through");
            MenuKit.reset(c);
            openBrowser(c);
            c.ctx().waitTicks(4);
            BufferedImage browser = shot(c, "huds-browser");
            int bDirty = nonBackdropBottom(browser, backdrop, strip);
            c.check(bDirty == 0, "browser: " + bDirty + " pixels in the bottom strip are not the backdrop");
            c.note("bottom " + strip + "px strip: reskin 0 and browser 0 non-backdrop pixels; Hypixel's GUI (control) "
                    + vanillaDirty);
        }
    }

    // ---- 497 ------------------------------------------------------------------------------------------------------

    static void seamless(Session c) throws Exception {
        try (Env env = new Env(c, false)) {
            feedApi(c);
            setWindow(c, 1920, 1080, 2);
            JsonObject browser = MenuKit.menu("menus.ah-browser");
            // Next Page opens an Auctions Browser whose items have not arrived (Hypixel's slowest case).
            JsonObject empty = new JsonObject();
            empty.addProperty("title", "Auctions Browser");
            empty.addProperty("rows", 6);
            JsonObject on = new JsonObject();
            JsonObject next = new JsonObject();
            next.add("open", empty);
            on.add("53", next);
            browser.add("on", on);
            show(c, browser, "BROWSER");
            List<String> before = reskinLayout(c);
            c.ctx().runOnClient(mc -> Mod.staticCall(RESKIN, "resetFramesForTest"));
            press(c, hotspot(before, "53"), 0, factor(c));
            c.waitUntil("the empty next page", mc -> "CARRIED".equals(Mod.staticCall(RESKIN, "kindForTest")), 40);
            BufferedImage img = shot(c, "seamless-carried");
            List<String> carried = reskinLayout(c);
            int grey = count(img, CHEST_GREY);
            int t = texts(carried);
            boolean oldTexts = carried.stream().anyMatch(l -> l.startsWith("text card ") && l.contains("Hyperion"));
            c.check(grey < 50, "while the next page loads " + grey + " chest-grey pixels show");
            c.check(t >= 20 && oldTexts, "while the next page loads only " + t + " texts (old cards drawn: " + oldTexts + ")");
            c.check(carried.contains("state carried"), "layout state is not 'carried'");
            // The server's items arrive: a full page in a new container.
            JsonObject page3 = MenuKit.menu("menus.ah-browser");
            MenuKit.show(c, page3);
            c.waitUntil("page 3 reskinned", mc -> "BROWSER".equals(Mod.staticCall(RESKIN, "kindForTest")), 60);
            c.ctx().waitTicks(5);
            int[] frames = c.onClient(mc -> (int[]) Mod.staticCall(RESKIN, "loadingFramesForTest"));
            c.check(frames[0] > 0, "no loading frame was drawn - the transition was not exercised");
            c.check(frames[1] == 0 && frames[2] == 0, "frames: " + frames[1] + " blank, " + frames[2] + " showing the chest");
            c.note("page change: " + frames[0] + " frames drew the previous page while loading (screenshot " + grey
                    + " chest-grey px, " + t + " texts), " + frames[1] + " blank, " + frames[2] + " chest");
        }
    }

    // ---- 498 ------------------------------------------------------------------------------------------------------

    static void fallback(Session c) throws Exception {
        try (Env env = new Env(c, false)) {
            for (String id : new String[]{"menus.ah-stats", "menus.ah-browser-broken"}) {
                MenuKit.reset(c);
                JsonObject spec = MenuKit.menu(id);
                MenuKit.show(c, spec);
                MenuKit.awaitScreen(c, plainTitle(spec), 100);
                c.ctx().waitTicks(50); // past the settle window and the 2 s load timeout
                c.check("NONE".equals(kind(c)), id + ": reskin shows " + kind(c) + " - it must stay Hypixel's GUI");
                vanillaClick(c, spec, id);
            }
            MenuKit.reset(c);
            c.ctx().runOnClient(mc -> Mod.set("auction.AuctionHouseConfig", "setReskinRealAh", false));
            JsonObject spec = MenuKit.menu("menus.ah-browser");
            MenuKit.show(c, spec);
            MenuKit.awaitScreen(c, plainTitle(spec), 100);
            c.ctx().waitTicks(20);
            c.check("NONE".equals(kind(c)), "Reskin Real Auction House off: reskin shows " + kind(c));
            vanillaClick(c, spec, "browser with the reskin off");
            c.ctx().runOnClient(mc -> Mod.set("auction.AuctionHouseConfig", "setReskinRealAh", true));

            MenuKit.reset(c);
            JsonObject view = show(c, "menus.ah-view-bin", "VIEW");
            c.ctx().getInput().holdAlt();
            try {
                c.waitUntil("Hypixel's GUI while Left Alt is held", mc -> "NONE".equals(Mod.staticCall(RESKIN, "kindForTest")), 20);
                vanillaClick(c, view, "left alt held");
            } finally {
                c.ctx().getInput().releaseAlt();
            }
            c.waitUntil("the reskin back after Left Alt", mc -> "VIEW".equals(Mod.staticCall(RESKIN, "kindForTest")), 20);
            c.ctx().waitTicks(3);
            int[] button = hotspot(reskinLayout(c), "action");
            c.check(button != null, "no Hypixel menu button in the header");
            int before = clicks(c);
            press(c, button, 0, factor(c));
            c.waitUntil("Hypixel's GUI after the header button", mc -> "NONE".equals(Mod.staticCall(RESKIN, "kindForTest")), 20);
            c.ctx().waitTicks(10);
            c.check(clicks(c) == before, "the Hypixel menu button sent " + (clicks(c) - before) + " click(s)");
            c.check("NONE".equals(kind(c)), "the reskin came back after the Hypixel menu button: " + kind(c));
            c.note("Left Alt: Hypixel's GUI while held, reskin back on release; header 'Hypixel menu': Hypixel's GUI, 0 clicks");
        }
    }

    /** Hypixel's GUI is really drawn (chest grey) and a press on a real slot is vanilla's own click on it. */
    static void vanillaClick(Session c, JsonObject spec, String what) {
        BufferedImage img = shot(c, "ah-vanilla-" + what.replaceAll("[^a-z0-9]+", "-"));
        int grey = count(img, CHEST_GREY);
        c.check(grey > 1000, what + ": only " + grey + " chest-grey pixels - Hypixel's GUI is not showing");
        int slot = Integer.parseInt(realSlots(spec).get(0));
        double[] at = c.onClient(mc -> {
            AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
            Slot sl = s.getMenu().slots.get(slot);
            int left = (Integer) Mod.field(s, "leftPos");
            int top = (Integer) Mod.field(s, "topPos");
            double scale = mc.getWindow().getGuiScale();
            return new double[]{(left + sl.x + 8) * scale, (top + sl.y + 8) * scale};
        });
        int before = clicks(c);
        c.ctx().getInput().setCursorPos(at[0], at[1]);
        c.ctx().waitTicks(2);
        c.ctx().getInput().pressMouse(0);
        c.waitUntil(what + ": vanilla's click", mc -> clicks(c) > before, 40);
        JsonObject k = lastClick(c);
        c.check(k.get("slot").getAsInt() == slot, what + ": vanilla press sent " + describe(k));
        c.note(what + ": Hypixel's GUI (" + grey + " chest-grey px), press on slot " + slot + " -> " + describe(k));
    }

    // ---- 499 ------------------------------------------------------------------------------------------------------

    static void handoff(Session c) throws Exception {
        try (Env env = new Env(c, false)) {
            feedApi(c);
            setWindow(c, 1920, 1080, 2);
            c.hx().stub(null, "viewauction");
            Object ah = Mod.cfg("auction.AuctionHouseConfig");
            c.ctx().runOnClient(mc -> Mod.call(ah, "clearRecents"));
            Screen s = openBrowser(c);
            c.ctx().runOnClient(mc -> Mod.call(s, "preset", "", "", false));
            List<String> layout = browserLayout(c, s);
            String first = c.onClient(mc -> (String) Mod.call(s, "listingIdForTest", 0));
            int cmds = c.commands().size();
            press(c, hotspot(layout, "listing:" + first), 0, 1f);
            c.waitUntil("the /viewauction", mc -> c.commands().size() > cmds, 60);
            c.ctx().runOnClient(mc -> Mod.staticCall(RESKIN, "resetFramesForTest"));
            // Hypixel answers with its Auction View; its items are slow (an empty container first).
            JsonObject empty = new JsonObject();
            empty.addProperty("title", "BIN Auction View");
            empty.addProperty("rows", 6);
            MenuKit.show(c, empty);
            c.waitUntil("the opening frame", mc -> "OPENING".equals(Mod.staticCall(RESKIN, "kindForTest")), 40);
            c.ctx().waitTicks(3);
            BufferedImage img = shot(c, "handoff-opening");
            List<String> opening = reskinLayout(c);
            int grey = count(img, CHEST_GREY);
            boolean hero = opening.stream().anyMatch(l -> l.startsWith("text hero "));
            c.check(grey < 50 && hero, "opening frame: " + grey + " chest-grey px, hero drawn " + hero);
            MenuKit.show(c, MenuKit.menu("menus.ah-view-bin"));
            c.waitUntil("the real view reskinned", mc -> "VIEW".equals(Mod.staticCall(RESKIN, "kindForTest")), 60);
            c.ctx().waitTicks(5);
            int[] frames = c.onClient(mc -> (int[]) Mod.staticCall(RESKIN, "loadingFramesForTest"));
            c.check(frames[0] > 0 && frames[1] == 0 && frames[2] == 0, "browser -> view frames: " + frames[0] + " loading, "
                    + frames[1] + " blank, " + frames[2] + " chest");
            @SuppressWarnings("unchecked")
            List<Object> viewed = c.onClient(mc -> (List<Object>) Mod.call(ah, "getRecentViewed"));
            c.check(viewed.size() >= 2, "recently viewed after opening one listing and its view: " + viewed);
            // Back to the browser through a header tab: the menu closes (no slot click) and the browser opens on it.
            int clicks = clicks(c);
            press(c, hotspot(reskinLayout(c), "tab:Weapons"), 0, factor(c));
            c.waitUntil("the browser", mc -> McCompat.screen(mc) != null
                    && McCompat.screen(mc).getClass().getName().endsWith("AuctionHouseScreen"), 40);
            String cat = c.onClient(mc -> (String) Mod.get("auction.AuctionConfig", "getLastCategoryFilter"));
            c.check("weapon".equals(cat) && clicks(c) == clicks, "tab from the view: category '" + cat + "', "
                    + (clicks(c) - clicks) + " click(s)");
            c.note("browser -> /viewauction -> opening frame (" + grey + " chest px, hero from API data) -> VIEW reskinned; "
                    + frames[0] + " loading frames, 0 blank, 0 chest; recents " + viewed.size()
                    + "; Weapons tab on the view -> browser on 'weapon', 0 clicks");
        }
    }
}

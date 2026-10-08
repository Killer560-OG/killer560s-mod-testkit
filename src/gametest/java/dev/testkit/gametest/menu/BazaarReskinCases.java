package dev.testkit.gametest.menu;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reskin Real Bazaar (mod {@code auction/screen/BazaarReskin}, {@code auction/BazaarPages}, 2026-10-07): Hypixel's real
 * Bazaar menus drawn in the Bazaar browser's look, every click a real slot click. The server opens each menu from the
 * {@code menus/bazaar-reskin.json} fixtures (titles, slots, names and lore from the hypixelskyblock wiki's Bazaar UI pages,
 * SkyHanni and Skyblocker - {@code verifiedBy: regex}, NOT captures: they prove the mod and the fixtures agree, not that
 * Hypixel still sends exactly this).
 *
 * <ul>
 *   <li>461-menu-bazaar-reskin-pages: each of the twelve mapped menus opens as the reskin of its kind; every non-filler
 *       slot of the fixture has a clickable element; every text lies inside its region and the screen and no two texts
 *       intersect; at 1920x1080 GUI 2 a screenshot of each must show no chest-grey pixels (the chest is hidden) and the
 *       reskin's texts; the same layout checks at the small gametest window.</li>
 *   <li>462-menu-bazaar-reskin-click: nothing is sent without a press (40 ticks idle on the product page = 0 clicks);
 *       a real mouse press on a card sends exactly one container click on that card's slot (left PICKUP 0, right PICKUP
 *       1, shift QUICK_MOVE); a click on the panel's empty space sends nothing; Create Buy Order navigates (the server
 *       opens "How many do you want?" and it is reskinned) and the bottom bar's Go Back navigates back.</li>
 *   <li>463-menu-bazaar-reskin-fallback: Bazaar Settings, Confirm Instant Buy, a non-Bazaar "A ➜ B" menu and a category
 *       page missing its Manage Orders button stay Hypixel's GUI (chest pixels drawn, a press on a slot is vanilla's own
 *       click); a mapped page with Reskin Real Bazaar off stays Hypixel's too.</li>
 *   <li>464-menu-bazaar-reskin-escape: holding the Hypixel Menu key (Left Alt) shows Hypixel's GUI, whose slot click
 *       goes through as vanilla's; releasing it brings the reskin back; the header's "Hypixel menu" button switches that
 *       menu to Hypixel's GUI without sending anything.</li>
 *   <li>465-menu-bazaar-reskin-orders: Manage Orders ({@code menus.bazaar-orders}) is the live order list (names, BUY/SELL
 *       and statuses drawn from the open container), and a press on an order sends one click on its slot.</li>
 *   <li>466-menu-bazaar-cookie-gate: {@code /killer560bz} opens the browser only with an active Cookie Buff in the tab
 *       footer; with the footer's "Not active!" block, and with no Cookie Buff anywhere, it does not open and chat says
 *       why; bare and argued {@code /bz} reach the server as Hypixel's command.</li>
 *   <li>467-menu-bazaar-reskin-stale: while the server keeps changing the item under the cursor (every tick), a press
 *       sends nothing - the menu is never clicked from a page that is not what he sees; once it settles, a press sends
 *       exactly one click.</li>
 * </ul>
 */
final class BazaarReskinCases {

    private static final String RESKIN = "auction.screen.BazaarReskin";
    /** The vanilla chest panel's grey (198,198,198): thousands of pixels when Hypixel's GUI shows, none under the reskin. */
    private static final int CHEST_GREY = 0xC6C6C6;
    private static final int LEFT_ALT = 342;

    private BazaarReskinCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "461-menu-bazaar-reskin-pages", BazaarReskinCases::pages);
        MenuSuite.test(s, "462-menu-bazaar-reskin-click", BazaarReskinCases::click);
        MenuSuite.test(s, "463-menu-bazaar-reskin-fallback", BazaarReskinCases::fallback);
        MenuSuite.test(s, "464-menu-bazaar-reskin-escape", BazaarReskinCases::escape);
        MenuSuite.test(s, "465-menu-bazaar-reskin-orders", BazaarReskinCases::orders);
        MenuSuite.test(s, "466-menu-bazaar-cookie-gate", BazaarReskinCases::cookieGate);
        MenuSuite.test(s, "467-menu-bazaar-reskin-stale", BazaarReskinCases::stale);
    }

    /** {fixture id, expected kind}. */
    private static final String[][] MAPPED = {
            {"menus.reskin-bazaar-category", "CATEGORY"},
            {"menus.reskin-bazaar-group", "GROUP"},
            {"menus.reskin-bazaar-product", "PRODUCT"},
            {"menus.reskin-bazaar-instant-buy", "INSTANT_BUY"},
            {"menus.reskin-bazaar-order-amount", "ORDER_AMOUNT"},
            {"menus.reskin-bazaar-order-price", "ORDER_PRICE"},
            {"menus.reskin-bazaar-confirm-buy", "CONFIRM_BUY"},
            {"menus.reskin-bazaar-offer-price", "OFFER_PRICE"},
            {"menus.reskin-bazaar-confirm-sell", "CONFIRM_SELL"},
            {"menus.reskin-bazaar-sell-inventory", "CONFIRM_SELL_INVENTORY"},
            {"menus.reskin-bazaar-order-options", "ORDER_OPTIONS"},
            {"menus.bazaar-orders", "ORDERS"},
    };

    // ---- shared ---------------------------------------------------------------------------------------------------

    /** Reskin on, Skyblock gate off (the test server is not Hypixel), Hypixel Menu key = Left Alt. */
    private static MenuKit.Cfg setup(Session c) {
        MenuKit.Cfg cfg = new MenuKit.Cfg(c);
        cfg.set("auction.AuctionConfig", "ReskinRealBazaar", true);
        cfg.set("auction.AuctionConfig", "BazaarVanillaKeyCode", LEFT_ALT);
        return cfg;
    }

    private static boolean gateOff(Session c) {
        boolean was = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        c.ctx().runOnClient(mc -> Mod.staticCall("util.SkyblockGate", "setEnabled", false));
        return was;
    }

    private static void gateRestore(Session c, boolean was) {
        c.ctx().runOnClient(mc -> Mod.staticCall("util.SkyblockGate", "setEnabled", was));
    }

    static String kind(Session c) {
        return c.onClient(mc -> (String) Mod.staticCall(RESKIN, "kindForTest"));
    }

    @SuppressWarnings("unchecked")
    static List<String> layout(Session c) {
        return c.onClient(mc -> (List<String>) Mod.staticCall(RESKIN, "layoutReportForTest"));
    }

    private static String plainTitle(JsonObject spec) {
        return spec.get("title").getAsString().replaceAll("§.", "");
    }

    /** Open a fixture menu and wait for the reskin to show it as {@code kind}. */
    static JsonObject open(Session c, String fixture, String kind) {
        JsonObject spec = MenuKit.menu(fixture);
        return open(c, spec, kind);
    }

    static JsonObject open(Session c, JsonObject spec, String kind) {
        MenuKit.show(c, spec);
        MenuKit.awaitScreen(c, plainTitle(spec), 100);
        c.waitUntil("the reskin to show '" + plainTitle(spec) + "' as " + kind, mc -> kind.equals(
                (String) Mod.staticCall(RESKIN, "kindForTest")), 60);
        c.ctx().waitTicks(3); // one more frame so the layout report is of this page
        return spec;
    }

    /** {x, y, w, h} of the hotspot for {@code slot} in the last frame, or null. */
    static int[] hotspot(List<String> layout, String slot) {
        for (String line : layout) {
            String[] p = line.split(" ", 7);
            if (p[0].equals("hotspot") && p[1].equals(slot)) {
                return new int[]{Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5])};
            }
        }
        return null;
    }

    /** Window pixel position of a GUI point. */
    static double[] toWindow(Session c, double gx, double gy) {
        return c.onClient(mc -> {
            double scale = mc.getWindow().getGuiScale();
            return new double[]{gx * scale, gy * scale};
        });
    }

    /** Moves the real cursor to the middle of a hotspot (a GUI box). */
    static void cursorTo(Session c, int[] box) {
        double[] at = toWindow(c, box[0] + box[2] / 2.0, box[1] + box[3] / 2.0);
        c.ctx().getInput().setCursorPos(at[0], at[1]);
        c.ctx().waitTicks(2);
    }

    static void pressAt(Session c, int[] box, int button) {
        cursorTo(c, box);
        c.ctx().getInput().pressMouse(button);
        c.ctx().waitTicks(3);
    }

    /** A shift-click: Fabric's pressMouse builds MouseButtonInfo(button, 0) with no modifiers (see InventoryCases). */
    static void shiftPressAt(Session c, int[] box) {
        cursorTo(c, box);
        c.ctx().runOnClient(mc -> {
            long window = mc.getWindow().handle();
            Mod.call(mc.mouseHandler, "onButton", window, new net.minecraft.client.input.MouseButtonInfo(0, 1), 1);
            Mod.call(mc.mouseHandler, "onButton", window, new net.minecraft.client.input.MouseButtonInfo(0, 1), 0);
        });
        c.ctx().waitTicks(3);
    }

    /** Slots of a fixture that are not the black filler (every one must have an element in the reskin). */
    static List<String> realSlots(JsonObject spec) {
        List<String> out = new ArrayList<>();
        for (var e : spec.getAsJsonObject("slots").entrySet()) {
            if (e.getValue().isJsonObject() && e.getValue().getAsJsonObject().has("name")) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /**
     * Layout rules for one frame: every text inside the screen and inside a region of its name (cards and the like
     * repeat a region name: inside ANY of them), and no two texts intersecting.
     */
    static List<String> layoutProblems(List<String> layout, int sw, int sh) {
        List<String> problems = new ArrayList<>();
        List<int[]> regions = new ArrayList<>();
        List<String> regionNames = new ArrayList<>();
        List<int[]> texts = new ArrayList<>();
        List<String> textLines = new ArrayList<>();
        for (String line : layout) {
            String[] p = line.split(" ", 7);
            if (p[0].equals("region")) {
                regionNames.add(p[1]);
                regions.add(new int[]{Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5])});
            } else if (p[0].equals("text")) {
                texts.add(new int[]{Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5])});
                textLines.add(line);
            }
        }
        for (int i = 0; i < texts.size(); i++) {
            int[] t = texts.get(i);
            String region = textLines.get(i).split(" ", 3)[1];
            if (t[0] < 0 || t[1] < 0 || t[0] + t[2] > sw || t[1] + t[3] > sh) {
                problems.add("off screen: " + textLines.get(i));
            }
            boolean inside = false;
            for (int r = 0; r < regions.size(); r++) {
                int[] b = regions.get(r);
                if (regionNames.get(r).equals(region) && t[0] >= b[0] && t[1] >= b[1] && t[0] + t[2] <= b[0] + b[2]
                        && t[1] + t[3] <= b[1] + b[3]) {
                    inside = true;
                    break;
                }
            }
            if (!inside) {
                problems.add("outside its region: " + textLines.get(i));
            }
            for (int j = i + 1; j < texts.size(); j++) {
                int[] u = texts.get(j);
                if (t[0] < u[0] + u[2] && u[0] < t[0] + t[2] && t[1] < u[1] + u[3] && u[1] < t[1] + t[3]) {
                    problems.add("overlap: [" + textLines.get(i) + "] and [" + textLines.get(j) + "]");
                }
            }
        }
        return problems;
    }

    static BufferedImage shot(Session c, String label) {
        c.ctx().getInput().setCursorPos(2, 2);
        c.ctx().waitTicks(6);
        String name = c.name() + "-" + label;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        Path kept = dev.testkit.harness.Report.screenshot(name, taken);
        c.note(label + " -> " + (kept != null ? kept : taken));
        try {
            return javax.imageio.ImageIO.read(taken.toFile());
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read " + taken, e);
        }
    }

    static int count(BufferedImage img, int rgb) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) == rgb) {
                    n++;
                }
            }
        }
        return n;
    }

    private static int clicks(Session c) {
        return c.events("container.click").size();
    }

    private static JsonObject lastClick(Session c) {
        List<JsonObject> e = c.events("container.click");
        return e.isEmpty() ? null : e.get(e.size() - 1);
    }

    private static String describe(JsonObject click) {
        return click == null ? "none" : "slot " + click.get("slot") + " button " + click.get("button") + " " + click.get("input");
    }

    private static int[] windowSize(Session c) {
        return c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(), mc.options.guiScale().get()});
    }

    private static void setWindow(Session c, int w, int h, int gui) {
        c.ctx().getInput().resizeWindow(w, h);
        c.ctx().waitTicks(3);
        c.ctx().runOnClient(mc -> {
            mc.options.guiScale().set(gui);
            mc.resizeGui();
        });
        c.ctx().waitTicks(3);
    }

    // ---- 461 ------------------------------------------------------------------------------------------------------

    static void pages(Session c) throws Exception {
        MenuKit.reset(c);
        boolean gate = gateOff(c);
        int[] old = windowSize(c);
        try (MenuKit.Cfg cfg = setup(c)) {
            Object hud = Mod.cfg("hud.HudConfig");
            boolean oldAuto = (Boolean) Mod.call(hud, "isAutoScale");
            c.ctx().runOnClient(mc -> Mod.call(hud, "setAutoScale", false));
            try {
                for (int pass = 0; pass < 2; pass++) {
                    boolean big = pass == 1;
                    if (big) {
                        setWindow(c, 1920, 1080, 2);
                        feedLive(c); // the second pass also draws the live Bazaar data (margin, 7d volume, product stats)
                    }
                    int[] gui = c.onClient(mc -> new int[]{mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()});
                    String size = big ? "1920x1080-gui2" : "window-" + gui[0] + "x" + gui[1];
                    for (String[] m : MAPPED) {
                        MenuKit.reset(c);
                        int before = clicks(c);
                        JsonObject spec = open(c, m[0], m[1]);
                        List<String> layout = layout(c);
                        int texts = (int) layout.stream().filter(l -> l.startsWith("text ")).count();
                        c.check(texts >= 6, size + " " + m[1] + ": only " + texts + " texts drawn - did the reskin draw?");
                        List<String> missing = new ArrayList<>();
                        for (String slot : realSlots(spec)) {
                            if (hotspot(layout, slot) == null) {
                                missing.add(slot);
                            }
                        }
                        c.check(missing.isEmpty(), size + " " + m[1] + ": no element for slot(s) " + missing);
                        List<String> problems = layoutProblems(layout, gui[0], gui[1]);
                        c.check(problems.isEmpty(), size + " " + m[1] + ": " + problems.size() + " layout problem(s): "
                                + String.join(" | ", problems.subList(0, Math.min(6, problems.size()))));
                        String line = size + " " + m[1] + ": " + texts + " texts, " + realSlots(spec).size()
                                + " slots all clickable";
                        if (big && m[1].equals("PRODUCT")) {
                            c.check(layout.stream().anyMatch(l -> l.startsWith("text product ") && l.contains("Instant buy")),
                                    "PRODUCT with live data: no 'Instant buy' line in the product header");
                        }
                        if (big && m[1].equals("GROUP")) {
                            long vols = layout.stream().filter(l -> l.startsWith("text list ") && l.matches(".* [\\d.,]+[kM]?$")
                                    && !l.contains(" -")).count();
                            c.check(layout.stream().anyMatch(l -> l.startsWith("text list ") && l.endsWith("%")),
                                    "GROUP: no margin column drawn");
                            List<String> dashes = layout.stream().filter(l -> l.startsWith("text list ") && l.endsWith(" -")).toList();
                            c.check(dashes.isEmpty(), "GROUP with live data: cells without a value: " + dashes);
                            line += ", " + vols + " numeric cells";
                        }
                        if (big) {
                            BufferedImage img = shot(c, m[1].toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
                            int grey = count(img, CHEST_GREY);
                            c.check(grey < 50, size + " " + m[1] + ": " + grey + " chest-grey pixels - Hypixel's chest is showing");
                            line += ", chest-grey pixels " + grey;
                        }
                        c.check(clicks(c) == before, m[1] + ": opening and drawing sent " + (clicks(c) - before) + " click(s)");
                        c.note(line);
                    }
                }
            } finally {
                MenuKit.reset(c);
                c.ctx().runOnClient(mc -> Mod.call(hud, "setAutoScale", oldAuto));
            }
        } finally {
            setWindow(c, old[0], old[1], old[2]);
            gateRestore(c, gate);
        }
    }

    /** A synthetic /v2/skyblock/bazaar answer for the fixtures' products, through the mod's real parser. */
    static void feedLive(Session c) {
        String[] ids = {"WHEAT", "SEEDS", "ENCHANTED_WHEAT", "ENCHANTED_HAY_BALE", "ENCHANTED_SEEDS", "HAY_BLOCK",
                "TIGHTLY_TIED_HAY_BALE", "ENCHANTED_DIAMOND", "ESSENCE_WITHER", "ENCHANTED_GOLD", "ROUGH_RUBY_GEM"};
        JsonObject products = new JsonObject();
        for (String id : ids) {
            int h = Math.abs(id.hashCode());
            double sell = 1 + (h % 100000) / 10.0;
            double buy = Math.round(sell * 1.05 * 10) / 10.0;
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
            for (int i = 0; i < 3; i++) {
                JsonObject b = new JsonObject();
                b.addProperty("amount", 64 * (i + 1));
                b.addProperty("pricePerUnit", buy + i * 0.1);
                b.addProperty("orders", 1 + i);
                buySummary.add(b);
                JsonObject sl = new JsonObject();
                sl.addProperty("amount", 128 * (i + 1));
                sl.addProperty("pricePerUnit", sell - i * 0.1);
                sl.addProperty("orders", 1 + i);
                sellSummary.add(sl);
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
        c.check(n == ids.length, "the mod's bazaar parser kept " + n + " of " + ids.length + " products");
    }

    // ---- 462 ------------------------------------------------------------------------------------------------------

    static void click(Session c) throws Exception {
        MenuKit.reset(c);
        boolean gate = gateOff(c);
        try (MenuKit.Cfg cfg = setup(c)) {
            JsonObject product = MenuKit.menu("menus.reskin-bazaar-product");
            JsonObject amount = MenuKit.menu("menus.reskin-bazaar-order-amount");
            // Create Buy Order (15) opens the amount step; the amount step's Go Back (31) opens the product again.
            JsonObject amountOn = new JsonObject();
            JsonObject back = new JsonObject();
            back.add("open", product.deepCopy());
            amountOn.add("31", back);
            amount.add("on", amountOn);
            JsonObject productOn = new JsonObject();
            JsonObject toAmount = new JsonObject();
            toAmount.add("open", amount);
            productOn.add("15", toAmount);
            product.add("on", productOn);

            open(c, product, "PRODUCT");
            int base = clicks(c);
            c.ctx().getInput().setCursorPos(2, 2);
            c.ctx().waitTicks(40);
            c.check(clicks(c) == base, "40 idle ticks on the reskinned product page sent " + (clicks(c) - base) + " click(s)");
            c.note("idle 40 ticks: 0 clicks");
            List<String> layout = layout(c);

            // Right click on Sell Instantly (11): Hypixel's "Right-Click to pick amount!".
            pressAt(c, hotspot(layout, "11"), 1);
            c.waitUntil("a click from the right press on Sell Instantly", mc -> clicks(c) > base, 40);
            JsonObject r = lastClick(c);
            c.check(clicks(c) == base + 1, "one right press sent " + (clicks(c) - base) + " clicks");
            c.check(r.get("slot").getAsInt() == 11 && r.get("button").getAsInt() == 1 && "PICKUP".equals(r.get("input").getAsString()),
                    "right press on Sell Instantly sent " + describe(r));
            c.note("right press on Sell Instantly -> " + describe(r));

            // Shift click on Buy Instantly (10): vanilla's QUICK_MOVE, as a shift-click on the chest sends.
            shiftPressAt(c, hotspot(layout, "10"));
            c.waitUntil("a click from the shift press on Buy Instantly", mc -> clicks(c) > base + 1, 40);
            JsonObject sh = lastClick(c);
            c.check(clicks(c) == base + 2, "one shift press sent " + (clicks(c) - base - 1) + " clicks");
            c.check(sh.get("slot").getAsInt() == 10 && sh.get("button").getAsInt() == 0
                    && "QUICK_MOVE".equals(sh.get("input").getAsString()), "shift press on Buy Instantly sent " + describe(sh));
            c.note("shift press on Buy Instantly -> " + describe(sh));

            // A press on the panel where nothing is drawn: nothing sent.
            int[] panel = null;
            for (String line : layout) {
                if (line.startsWith("region main ")) {
                    String[] p = line.split(" ");
                    panel = new int[]{Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5])};
                }
            }
            c.check(panel != null, "no main region in the layout");
            int[] corner = {panel[0] + panel[2] - 6, panel[1] + panel[3] - 6, 2, 2};
            boolean onHotspot = false;
            for (String line : layout) {
                String[] p = line.split(" ", 7);
                if (p[0].equals("hotspot")) {
                    int hx = Integer.parseInt(p[2]), hy = Integer.parseInt(p[3]), hw = Integer.parseInt(p[4]), hh = Integer.parseInt(p[5]);
                    onHotspot |= corner[0] >= hx && corner[0] < hx + hw && corner[1] >= hy && corner[1] < hy + hh;
                }
            }
            c.check(!onHotspot, "the empty-space probe sits on a hotspot; pick another point");
            pressAt(c, corner, 0);
            c.ctx().waitTicks(10);
            c.check(clicks(c) == base + 2, "a press on empty panel space sent " + (clicks(c) - base - 2) + " click(s)");
            c.note("press on empty panel space: 0 clicks");

            // Left press on Create Buy Order (15): one PICKUP on 15, and the server's next menu is reskinned.
            pressAt(c, hotspot(layout, "15"), 0);
            c.waitUntil("a click from the press on Create Buy Order", mc -> clicks(c) > base + 2, 40);
            JsonObject l = lastClick(c);
            c.check(l.get("slot").getAsInt() == 15 && l.get("button").getAsInt() == 0 && "PICKUP".equals(l.get("input").getAsString()),
                    "press on Create Buy Order sent " + describe(l));
            MenuKit.awaitScreen(c, "How many do you want?", 60);
            c.waitUntil("the amount step reskinned", mc -> "ORDER_AMOUNT".equals(Mod.staticCall(RESKIN, "kindForTest")), 60);
            c.ctx().waitTicks(3);
            c.check(clicks(c) == base + 3, "after Create Buy Order " + (clicks(c) - base) + " clicks, not 3");
            c.note("press on Create Buy Order -> " + describe(l) + "; server opened 'How many do you want?', reskinned");

            // Bottom bar Go Back (31) on the amount step: back to the product page.
            List<String> amountLayout = layout(c);
            pressAt(c, hotspot(amountLayout, "31"), 0);
            c.waitUntil("a click from Go Back", mc -> clicks(c) > base + 3, 40);
            JsonObject g = lastClick(c);
            c.check(g.get("slot").getAsInt() == 31 && "PICKUP".equals(g.get("input").getAsString()), "Go Back sent " + describe(g));
            MenuKit.awaitScreen(c, plainTitle(product), 60);
            c.waitUntil("the product page reskinned again", mc -> "PRODUCT".equals(Mod.staticCall(RESKIN, "kindForTest")), 60);
            c.ctx().waitTicks(10);
            c.check(clicks(c) == base + 4, "4 presses, " + (clicks(c) - base) + " clicks");
            c.note("bottom-bar Go Back -> " + describe(g) + "; product page reskinned again; 4 presses = 4 clicks");
        } finally {
            MenuKit.reset(c);
            gateRestore(c, gate);
        }
    }

    // ---- 463 ------------------------------------------------------------------------------------------------------

    static void fallback(Session c) throws Exception {
        MenuKit.reset(c);
        boolean gate = gateOff(c);
        try (MenuKit.Cfg cfg = setup(c)) {
            String[] unmapped = {"menus.reskin-bazaar-settings", "menus.reskin-bazaar-confirm-instant", "menus.reskin-bazaar-other-arrow",
                    "menus.reskin-bazaar-category-no-manage"};
            for (String id : unmapped) {
                MenuKit.reset(c);
                JsonObject spec = MenuKit.menu(id);
                MenuKit.show(c, spec);
                MenuKit.awaitScreen(c, plainTitle(spec), 100);
                c.ctx().waitTicks(30); // well past the settle window and the 1.5 s load timeout's start
                String k = kind(c);
                c.check("NONE".equals(k), id + ": reskin shows " + k + " - it must stay Hypixel's GUI");
                vanillaClick(c, spec, id);
            }
            // A mapped page with the toggle off.
            MenuKit.reset(c);
            c.ctx().runOnClient(mc -> Mod.set("auction.AuctionConfig", "setReskinRealBazaar", false));
            JsonObject spec = MenuKit.menu("menus.reskin-bazaar-product");
            MenuKit.show(c, spec);
            MenuKit.awaitScreen(c, plainTitle(spec), 100);
            c.ctx().waitTicks(20);
            c.check("NONE".equals(kind(c)), "Reskin Real Bazaar off: reskin shows " + kind(c));
            vanillaClick(c, spec, "product with the reskin off");
        } finally {
            MenuKit.reset(c);
            gateRestore(c, gate);
        }
    }

    /** Hypixel's GUI is really drawn (chest grey) and a press on a real slot is vanilla's own click on it. */
    private static void vanillaClick(Session c, JsonObject spec, String what) {
        BufferedImage img = shot(c, "vanilla-" + what.replaceAll("[^a-z0-9]+", "-"));
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

    // ---- 464 ------------------------------------------------------------------------------------------------------

    static void escape(Session c) throws Exception {
        MenuKit.reset(c);
        boolean gate = gateOff(c);
        try (MenuKit.Cfg cfg = setup(c)) {
            JsonObject spec = open(c, "menus.reskin-bazaar-product", "PRODUCT");
            c.ctx().getInput().holdAlt(); // Fabric TestInput: the left Alt key
            try {
                c.waitUntil("Hypixel's GUI while Left Alt is held", mc -> "NONE".equals(Mod.staticCall(RESKIN, "kindForTest")), 20);
                vanillaClick(c, spec, "left alt held");
            } finally {
                c.ctx().getInput().releaseAlt();
            }
            c.waitUntil("the reskin back after Left Alt is released", mc -> "PRODUCT".equals(Mod.staticCall(RESKIN, "kindForTest")), 20);
            c.note("Left Alt released: reskin back (PRODUCT)");

            c.ctx().waitTicks(3);
            int[] button = hotspot(layout(c), "action");
            c.check(button != null, "no Hypixel menu button in the header");
            int before = clicks(c);
            pressAt(c, button, 0);
            c.waitUntil("Hypixel's GUI after the header button", mc -> "NONE".equals(Mod.staticCall(RESKIN, "kindForTest")), 20);
            c.ctx().waitTicks(10);
            c.check(clicks(c) == before, "the Hypixel menu button sent " + (clicks(c) - before) + " container click(s)");
            c.check("NONE".equals(kind(c)), "the reskin came back after the Hypixel menu button: " + kind(c));
            c.note("header 'Hypixel menu' button: Hypixel's GUI for this menu, 0 clicks sent");
        } finally {
            MenuKit.reset(c);
            gateRestore(c, gate);
        }
    }

    // ---- 465 ------------------------------------------------------------------------------------------------------

    static void orders(Session c) throws Exception {
        MenuKit.reset(c);
        boolean gate = gateOff(c);
        try (MenuKit.Cfg cfg = setup(c)) {
            open(c, "menus.bazaar-orders", "ORDERS");
            List<String> layout = layout(c);
            String all = String.join("\n", layout);
            for (String want : new String[]{"Enchanted Diamond", "Wheat", "Wither Essence", "Rough Ruby Gemstone",
                    "Enchanted Gold Ingot", "BUY", "SELL", "Expired", "Claimable", "CoopFriend", "5 orders"}) {
                c.check(all.contains(want), "Manage Orders reskin does not show '" + want + "'");
            }
            c.note("Manage Orders reskin shows the 5 orders (BUY/SELL, Claimable, Expired, co-op owner)");
            int before = clicks(c);
            pressAt(c, hotspot(layout, "10"), 0);
            c.waitUntil("a click on the claimable order", mc -> clicks(c) > before, 40);
            c.ctx().waitTicks(5);
            JsonObject k = lastClick(c);
            c.check(clicks(c) == before + 1 && k.get("slot").getAsInt() == 10 && "PICKUP".equals(k.get("input").getAsString()),
                    "press on the Enchanted Diamond order sent " + (clicks(c) - before) + " click(s), last " + describe(k));
            c.note("press on the claimable order -> " + describe(k));
        } finally {
            MenuKit.reset(c);
            gateRestore(c, gate);
        }
    }

    // ---- 466 ------------------------------------------------------------------------------------------------------

    static void cookieGate(Session c) throws Exception {
        MenuKit.reset(c);
        boolean gate = gateOff(c);
        LogTap.install();
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("auction.AuctionConfig", "BazaarEnabled", true);
            c.hx().stub(null, "bz");
            String inactive = "§a§lActive Effects\n§7No effects active.\n\n§d§lCookie Buff\n"
                    + "§7Not active! Obtain booster cookies from the community\n§7shop in the hub.\n\n§aRanks, Boosters & MORE! §c§lSTORE.HYPIXEL.NET";
            String active = "§a§lActive Effects\n§7No effects active.\n\n§d§lCookie Buff\n"
                    + "§f3d 17h 2m\n\n§aRanks, Boosters & MORE! §c§lSTORE.HYPIXEL.NET";
            String none = "§aRanks, Boosters & MORE! §c§lSTORE.HYPIXEL.NET";

            gateCase(c, "footer 'Not active!'", inactive, false, "You need an active Booster Cookie");
            gateCase(c, "no Cookie Buff anywhere", none, false, "Couldn't find an active Cookie Buff");
            gateCase(c, "footer '3d 17h 2m'", active, true, null);

            // /bz is Hypixel's: bare and argued, both reach the server.
            int cmds = c.commands().size();
            c.ctx().runOnClient(mc -> mc.player.connection.sendCommand("bz"));
            c.ctx().runOnClient(mc -> mc.player.connection.sendCommand("bz Enchanted Diamond"));
            c.waitUntil("both /bz lines at the server", mc -> c.commands().size() >= cmds + 2, 60);
            List<String> got = c.commands().subList(cmds, c.commands().size());
            c.check(got.contains("bz") && got.contains("bz Enchanted Diamond"), "server got " + got);
            c.check(!(c.onClient(mc -> McCompat.screen(mc) != null
                    && McCompat.screen(mc).getClass().getName().endsWith("BazaarScreen"))), "/bz opened the mod's browser");
            c.note("/bz and /bz Enchanted Diamond reached the server as typed: " + got);
        } finally {
            c.hx().tab(List.of(), null, "");
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
            gateRestore(c, gate);
        }
    }

    // ---- 467 ------------------------------------------------------------------------------------------------------

    static void stale(Session c) throws Exception {
        MenuKit.reset(c);
        boolean gate = gateOff(c);
        try (MenuKit.Cfg cfg = setup(c)) {
            open(c, "menus.reskin-bazaar-product", "PRODUCT");
            cursorTo(c, hotspot(layout(c), "10"));
            int base = clicks(c);
            for (int i = 0; i < 12; i++) {
                JsonObject item = new JsonObject();
                item.addProperty("item", "minecraft:golden_horse_armor");
                item.addProperty("name", "§aBuy Instantly");
                JsonArray lore = new JsonArray();
                lore.add("§7Price per unit: §6" + (5 + i) + ".0 coins");
                item.add("lore", lore);
                JsonObject a = new JsonObject();
                a.addProperty("slot", 10);
                a.add("item", item);
                c.hx().call("menu.set", a);
                if (i == 6) {
                    c.ctx().getInput().pressMouse(0);
                }
                c.ctx().waitTicks(1);
            }
            c.ctx().waitTicks(4);
            c.check(clicks(c) == base, "a press while slot 10 changed every tick sent " + (clicks(c) - base) + " click(s)");
            c.note("press while the server changed slot 10 every tick: 0 clicks");
            c.ctx().waitTicks(10);
            String lore = c.onClient(mc -> {
                AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
                return s.getMenu().slots.get(10).getItem().getHoverName().getString();
            });
            c.check(lore.contains("Buy Instantly"), "slot 10 now reads " + lore);
            c.ctx().getInput().pressMouse(0);
            c.waitUntil("one click once the menu settled", mc -> clicks(c) > base, 40);
            c.ctx().waitTicks(5);
            JsonObject k = lastClick(c);
            c.check(clicks(c) == base + 1 && k.get("slot").getAsInt() == 10, "after settling: " + (clicks(c) - base)
                    + " click(s), last " + describe(k));
            c.note("settled, press -> " + describe(k));
        } finally {
            MenuKit.reset(c);
            gateRestore(c, gate);
        }
    }

    private static void gateCase(Session c, String what, String footer, boolean opens, String chat) {
        c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
        c.hx().tab(List.of("§b§lArea: §7Hub"), null, footer);
        c.ctx().waitTicks(5);
        long mark = LogTap.mark();
        c.ctx().runOnClient(mc -> mc.player.connection.sendCommand("killer560bz"));
        c.ctx().waitTicks(10);
        boolean open = c.onClient(mc -> McCompat.screen(mc) != null
                && McCompat.screen(mc).getClass().getName().endsWith("auction.screen.BazaarScreen"));
        c.check(open == opens, what + ": /killer560bz " + (open ? "opened" : "did not open") + " the browser");
        if (chat != null) {
            boolean said = false;
            for (String line : LogTap.since(mark)) {
                if (line.contains("[CHAT]") && line.contains(chat)) {
                    said = true;
                }
            }
            c.check(said, what + ": chat never said '" + chat + "'");
        }
        c.note(what + ": /killer560bz " + (open ? "opened the browser" : "stayed closed, chat: " + chat));
        c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
        c.ctx().waitTicks(2);
    }
}

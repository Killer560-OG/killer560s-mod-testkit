package dev.testkit.gametest.menu;

import com.sun.net.httpserver.HttpServer;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 1750-1754 Price Tooltip (mod pricetooltip/, killer560 2026-10-15): Skyblocker-style price lines on item tooltips.
 *
 * <p>Every price comes from an in-client HTTP fake on {@code testkit.fakeHttp.port} (server port + 6, the listener
 * 400/406/410 use), which the case points the mod's {@code hypixel} and {@code hysky} services at for its own duration
 * (the Price Tooltip resolves its URLs at fetch time). The case then runs one real fetch ({@code PriceData.refreshNow})
 * and asserts the fake was asked, so a green line can never come from an empty price table.
 * <ol>
 *   <li>1750 lines: a Hyperion shows exactly "NPC Sell Price: 56,000.0 Coins", "Lowest BIN Price: 5,900,000.0 Coins",
 *       "3 Day Avg. Price: 4,790,000.0 Coins", "Obtained: April 24, 2026", "Museum (Combat): Donated" with yellow labels,
 *       aqua values and a red date; a Bazaar item shows Buy/Sell, and with Shift the whole stack (64x).</li>
 *   <li>1751 value: Est. Item Value of a built Hyperion (recomb, 15 potato books, two enchants, 7 stars, a gem and its
 *       slot, Art of War, a scroll) is 191,500,000.0 at full price and 178,637,500.0 networth-weighted, and Shift lists
 *       the parts.</li>
 *   <li>1752 obtained: the long-millis timestamp and the old "M/d/yy h:mm a" string both read April 24, 2026.</li>
 *   <li>1753 off: switched off no line is added; a plain vanilla item never gets one; the settings survive a reload.</li>
 *   <li>1754 shot: the real inventory screen hovering the Hyperion draws the lines (screenshot in the report).</li>
 * </ol>
 */
final class PriceTooltipCases {

    private PriceTooltipCases() {
    }

    static final String CFG = "pricetooltip.PriceTooltipConfig";
    static final String DATA = "pricetooltip.PriceData";
    static final String FEATURE = "pricetooltip.PriceTooltipFeature";
    static final String MUSEUM = "pricetooltip.MuseumDonations";

    static final String HYPERION = "minecraft:iron_sword[custom_data={id:\"HYPERION\",timestamp:1777032000000L}]";
    static final String HYPERION_BUILT = "minecraft:iron_sword[custom_data={id:\"HYPERION\",timestamp:1777032000000L,"
            + "rarity_upgrades:1,hot_potato_count:15,enchantments:{ultimate_wise:5,sharpness:6},upgrade_level:7,"
            + "art_of_war_count:1,ability_scroll:[\"WITHER_SHIELD_SCROLL\"],"
            + "gems:{COMBAT_0:\"PERFECT\",COMBAT_0_gem:\"SAPPHIRE\",unlocked_slots:[\"COMBAT_0\"]}}]";
    static final String DIAMONDS = "minecraft:diamond[custom_data={id:\"ENCHANTED_DIAMOND\"}]";
    static final String LEGACY_DATE = "minecraft:iron_sword[custom_data={id:\"HYPERION\",timestamp:\"4/24/26 12:00 PM\"}]";

    static void register(Session s) {
        MenuSuite.test(s, "1750-menu-pricetip-lines", PriceTooltipCases::lines);
        MenuSuite.test(s, "1751-menu-pricetip-value", PriceTooltipCases::value);
        MenuSuite.test(s, "1752-menu-pricetip-obtained", PriceTooltipCases::obtained);
        MenuSuite.test(s, "1753-menu-pricetip-off", PriceTooltipCases::off);
        MenuSuite.test(s, "1754-menu-pricetip-shot", PriceTooltipCases::shot);
    }

    // ---- the fake -----------------------------------------------------------------------------------------------

    static final class Fake implements AutoCloseable {
        final HttpServer server;
        final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
        private final String oldHypixel;
        private final String oldHysky;

        Fake() throws Exception {
            int port = Integer.getInteger("testkit.fakeHttp.port", 0);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/", ex -> {
                String path = ex.getRequestURI().toString();
                hits.computeIfAbsent(path.replaceAll("\\?.*", ""), k -> new AtomicInteger()).incrementAndGet();
                String body = route(path);
                byte[] out = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(body == null ? 404 : 200, out.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(out);
                }
            });
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            oldHypixel = System.getProperty("killer560.net.hypixel");
            oldHysky = System.getProperty("killer560.net.hysky");
            System.setProperty("killer560.net.hypixel", base + "/hx");
            System.setProperty("killer560.net.hysky", base + "/hy");
        }

        static String route(String path) {
            if (path.startsWith("/hx/v2/resources/skyblock/items")) {
                return ITEMS;
            }
            if (path.startsWith("/hx/v2/skyblock/bazaar")) {
                return BAZAAR;
            }
            if (path.startsWith("/hy/api/auctions/lowestbins/average")) {
                return path.contains("days=3") ? "{\"HYPERION\":4790000}" : "{\"HYPERION\":4500000}";
            }
            if (path.startsWith("/hy/api/auctions/lowestbins")) {
                return "{\"HYPERION\":5900000,\"SHINY_HYPERION\":9000000}";
            }
            return null;
        }

        int hits(String prefix) {
            int n = 0;
            for (var e : hits.entrySet()) {
                if (e.getKey().startsWith(prefix)) {
                    n += e.getValue().get();
                }
            }
            return n;
        }

        @Override
        public void close() {
            restore("killer560.net.hypixel", oldHypixel);
            restore("killer560.net.hysky", oldHysky);
            server.stop(0);
        }

        private static void restore(String key, String old) {
            if (old == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, old);
            }
        }
    }

    static final String ITEMS = "{\"success\":true,\"items\":["
            + "{\"id\":\"HYPERION\",\"name\":\"Hyperion\",\"material\":\"IRON_SWORD\",\"npc_sell_price\":56000,"
            + "\"museum_data\":{\"donation_xp\":5,\"category\":\"COMBAT\",\"parent\":{}},"
            + "\"upgrade_costs\":[[{\"type\":\"ESSENCE\",\"essence_type\":\"WITHER\",\"amount\":150}],"
            + "[{\"type\":\"ESSENCE\",\"essence_type\":\"WITHER\",\"amount\":300}],"
            + "[{\"type\":\"ESSENCE\",\"essence_type\":\"WITHER\",\"amount\":500}],"
            + "[{\"type\":\"ESSENCE\",\"essence_type\":\"WITHER\",\"amount\":900}],"
            + "[{\"type\":\"ESSENCE\",\"essence_type\":\"WITHER\",\"amount\":1500}]],"
            + "\"gemstone_slots\":[{\"slot_type\":\"SAPPHIRE\",\"costs\":[{\"type\":\"COINS\",\"coins\":250000}]},"
            + "{\"slot_type\":\"COMBAT\",\"costs\":[{\"type\":\"COINS\",\"coins\":250000},"
            + "{\"type\":\"ITEM\",\"item_id\":\"FLAWLESS_JASPER_GEM\",\"amount\":1},"
            + "{\"type\":\"ITEM\",\"item_id\":\"FLAWLESS_SAPPHIRE_GEM\",\"amount\":1},"
            + "{\"type\":\"ITEM\",\"item_id\":\"FLAWLESS_RUBY_GEM\",\"amount\":1},"
            + "{\"type\":\"ITEM\",\"item_id\":\"FLAWLESS_AMETHYST_GEM\",\"amount\":1}]}]},"
            + "{\"id\":\"ENCHANTED_DIAMOND\",\"name\":\"Enchanted Diamond\",\"material\":\"DIAMOND\",\"npc_sell_price\":1280}"
            + "]}";

    static final String BAZAAR = bazaar(new Object[][]{
            {"ENCHANTED_DIAMOND", 1700.5, 1600.5},
            {"RECOMBOBULATOR_3000", 10_000_000, 9_000_000},
            {"HOT_POTATO_BOOK", 80_000, 70_000},
            {"FUMING_POTATO_BOOK", 1_500_000, 1_400_000},
            {"ENCHANTMENT_ULTIMATE_WISE_5", 1_000_000, 900_000},
            {"ENCHANTMENT_SHARPNESS_6", 2_000_000, 1_900_000},
            {"ESSENCE_WITHER", 3_000, 2_500},
            {"FIRST_MASTER_STAR", 10_000_000, 9_000_000},
            {"SECOND_MASTER_STAR", 20_000_000, 19_000_000},
            {"THE_ART_OF_WAR", 4_000_000, 3_500_000},
            {"WITHER_SHIELD_SCROLL", 100_000_000, 90_000_000},
            {"PERFECT_SAPPHIRE_GEM", 12_000_000, 11_000_000},
            {"FLAWLESS_JASPER_GEM", 2_000_000, 1_000_000},
            {"FLAWLESS_SAPPHIRE_GEM", 2_000_000, 1_000_000},
            {"FLAWLESS_RUBY_GEM", 2_000_000, 1_000_000},
            {"FLAWLESS_AMETHYST_GEM", 2_000_000, 1_000_000},
    });

    static String bazaar(Object[][] rows) {
        StringBuilder sb = new StringBuilder("{\"success\":true,\"products\":{");
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(rows[i][0]).append("\":{\"product_id\":\"").append(rows[i][0])
                    .append("\",\"quick_status\":{\"buyPrice\":").append(rows[i][1]).append(",\"sellPrice\":")
                    .append(rows[i][2]).append("}}");
        }
        return sb.append("}}").toString();
    }

    /** Fresh state, the fakes up, one real fetch; returns the fake (close it). */
    static Fake loadPrices(Session c, MenuKit.Cfg cfg) throws Exception {
        cfg.set(CFG, "Enabled", true);
        cfg.set(CFG, "NumberFormat", Mod.enumValue(CFG + "$NumberFormat", "EXACT"));
        cfg.set(CFG, "AverageDays", 3);
        cfg.set(CFG, "ValueMode", Mod.enumValue(CFG + "$ValueMode", "FULL"));
        cfg.set(CFG, "StackPrice", Mod.enumValue(CFG + "$When", "SHIFT"));
        cfg.set(CFG, "Breakdown", Mod.enumValue(CFG + "$When", "SHIFT"));
        Fake fake = new Fake();
        Mod.staticCall(DATA, "markStartedForTest");
        Mod.staticCall(DATA, "clearForTest");
        boolean ok = (Boolean) Mod.staticCall(DATA, "refreshNow", true);
        c.check(ok, "PriceData.refreshNow against the fake fetched nothing (" + Mod.staticCall(DATA, "lastError") + ")");
        c.check(fake.hits("/hx/v2/resources/skyblock/items") == 1 && fake.hits("/hx/v2/skyblock/bazaar") == 1
                        && fake.hits("/hy/api/auctions/lowestbins") >= 2,
                "the fake was not asked for every source: " + fake.hits);
        int bins = (Integer) Mod.staticCall(DATA, "lowestBinCount");
        int bz = (Integer) Mod.staticCall(DATA, "bazaarCount");
        c.check(bins == 2 && bz == BAZAAR_ROWS, "loaded " + bins + " BIN and " + bz + " Bazaar prices from the fake, expected 2 and " + BAZAAR_ROWS);
        c.note("fake asked: " + fake.hits + "; loaded " + bins + " BIN, " + bz + " Bazaar");
        return fake;
    }

    static final int BAZAAR_ROWS = 16;

    /** The lines the feature added (raw text, section codes kept), from the vanilla tooltip builder. */
    static List<String> priceLines(Session c, ItemStack stack) {
        return c.onClient(mc -> {
            // The feature's lines are the only ones starting with a yellow label or the breakdown's dash; the vanilla
            // lines of these test items (name, attack stats) carry no section codes.
            List<String> out = new ArrayList<>();
            for (Component line : Screen.getTooltipFromItem(mc, stack)) {
                String s = line.getString();
                if (s.startsWith("§e") || s.startsWith(" §8- ")) {
                    out.add(s);
                }
            }
            return out;
        });
    }

    /** Put a literal stack in hotbar slot {@code slot} and return the client's copy once it arrived. */
    static ItemStack give(Session c, int slot, String stack) {
        return give(c, slot, stack, 1);
    }

    static ItemStack give(Session c, int slot, String stack, int count) {
        c.hx().call("give", "slot", slot, "stack", stack, "count", count);
        c.waitUntil(stack + " in slot " + slot, mc -> !mc.player.getInventory().getItem(slot).isEmpty(), 60);
        c.ctx().waitTicks(2);
        return c.onClient(mc -> mc.player.getInventory().getItem(slot));
    }

    static void shift(Boolean down) {
        Mod.staticCall(FEATURE, "setShiftForTest", down);
    }

    // ---- cases --------------------------------------------------------------------------------------------------

    static void lines(Session c) throws Exception {
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c); Fake fake = loadPrices(c, cfg)) {
            Mod.staticCall(MUSEUM, "setDonated", Set.of("HYPERION"));
            shift(false);
            ItemStack hype = give(c, 0, HYPERION);
            List<String> got = priceLines(c, hype);
            List<String> want = List.of(
                    "§eNPC Sell Price: §b56,000.0 Coins",
                    "§eLowest BIN Price: §b5,900,000.0 Coins",
                    "§e3 Day Avg. Price: §b4,790,000.0 Coins",
                    "§eEst. Item Value: §b5,900,000.0 Coins",
                    "§eObtained: §cApril 24, 2026",
                    "§eMuseum (Combat): §bDonated");
            c.note("Hyperion lines: " + got);
            c.check(got.equals(want), "Hyperion lines " + got + ", expected " + want);

            Mod.staticCall(MUSEUM, "setDonated", Set.of());
            List<String> notDonated = priceLines(c, hype);
            c.check(notDonated.contains("§eMuseum (Combat): §cNot Donated"), "empty museum: " + notDonated);

            ItemStack dia = give(c, 1, DIAMONDS, 64);
            List<String> one = priceLines(c, dia);
            c.check(one.equals(List.of("§eNPC Sell Price: §b1,280.0 Coins", "§eBazaar Buy/Sell: §b1,700.5 / 1,600.5 Coins")),
                    "64 Enchanted Diamonds without Shift: " + one);
            shift(true);
            List<String> stack = priceLines(c, dia);
            c.check(stack.equals(List.of("§eNPC Sell Price: §b81,920.0 Coins §8(64x)",
                            "§eBazaar Buy/Sell: §b108,832.0 / 102,432.0 Coins §8(64x)")),
                    "64 Enchanted Diamonds with Shift: " + stack);
            c.note("diamonds: " + one + " / shift " + stack);
        } finally {
            shift(null);
            Mod.staticCall(MUSEUM, "clearForTest");
            ItemCases.clear(c, 0, 1);
        }
    }

    static void value(Session c) throws Exception {
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c); Fake fake = loadPrices(c, cfg)) {
            cfg.set(CFG, "NpcPrice", false);
            cfg.set(CFG, "Obtained", false);
            cfg.set(CFG, "Museum", false);
            shift(false);
            ItemStack built = give(c, 0, HYPERION_BUILT);
            List<String> full = priceLines(c, built);
            c.check(full.contains("§eEst. Item Value: §b191,500,000.0 Coins"), "full price: " + full);
            c.check(full.size() == 3, "without Shift no breakdown is listed: " + full);

            shift(true);
            List<String> parts = priceLines(c, built);
            List<String> want = List.of(
                    " §8- §7Base: §b5,900,000.0",
                    " §8- §7Recombobulator: §b10,000,000.0",
                    " §8- §7Hot Potato Books (10): §b800,000.0",
                    " §8- §7Fuming Potato Books (5): §b7,500,000.0",
                    " §8- §7Enchantments (2): §b3,000,000.0",
                    " §8- §7Wither Essence (3350): §b10,050,000.0",
                    " §8- §7Master Stars (2): §b30,000,000.0",
                    " §8- §7Gemstones (1): §b12,000,000.0",
                    " §8- §7Gemstone Slots (1): §b8,250,000.0",
                    " §8- §7Art of War: §b4,000,000.0",
                    " §8- §7Ability Scrolls (1): §b100,000,000.0");
            c.note("breakdown: " + parts);
            c.check(parts.containsAll(want) && parts.size() == 3 + want.size(), "Shift breakdown " + parts + ", expected " + want);

            cfg.set(CFG, "ValueMode", Mod.enumValue(CFG + "$ValueMode", "NETWORTH"));
            shift(false);
            List<String> nw = priceLines(c, built);
            c.check(nw.contains("§eEst. Item Value: §b178,637,500.0 Coins"), "networth: " + nw);
            c.note("Est. Item Value full 191,500,000.0, networth " + nw);
        } finally {
            shift(null);
            ItemCases.clear(c, 0);
        }
    }

    static void obtained(Session c) throws Exception {
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CFG, "Enabled", true);
            ItemStack modern = give(c, 0, HYPERION);
            ItemStack legacy = give(c, 1, LEGACY_DATE);
            String a = c.onClient(mc -> String.valueOf(Mod.staticCall(FEATURE, "obtainedDate", customData(modern))));
            String b = c.onClient(mc -> String.valueOf(Mod.staticCall(FEATURE, "obtainedDate", customData(legacy))));
            c.check("April 24, 2026".equals(a), "long timestamp 1777032000000 read as " + a);
            c.check("April 24, 2026".equals(b), "string timestamp \"4/24/26 12:00 PM\" read as " + b);
            List<String> lines = priceLines(c, legacy);
            c.check(lines.contains("§eObtained: §cApril 24, 2026"), "legacy item lines: " + lines);
            c.note("obtained: long -> " + a + ", string -> " + b);
        } finally {
            ItemCases.clear(c, 0, 1);
        }
    }

    static Object customData(ItemStack stack) {
        return stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
    }

    static void off(Session c) throws Exception {
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c); Fake fake = loadPrices(c, cfg)) {
            ItemStack hype = give(c, 0, HYPERION);
            List<String> on = priceLines(c, hype);
            c.check(on.size() >= 4, "switched on, the Hyperion should have lines: " + on);
            cfg.set(CFG, "Enabled", false);
            List<String> off = priceLines(c, hype);
            c.check(off.isEmpty(), "switched off, lines were still added: " + off);
            cfg.set(CFG, "Enabled", true);
            c.hx().give(1, "minecraft:diamond");
            c.ctx().waitTicks(5);
            ItemStack plain = c.onClient(mc -> mc.player.getInventory().getItem(1));
            List<String> none = priceLines(c, plain);
            c.check(none.isEmpty(), "a vanilla diamond got lines: " + none);

            // Survives a restart: save non-defaults, re-read the file.
            Object cfgObj = Mod.cfg(CFG);
            Mod.call(cfgObj, "setAverageDays", 1);
            Mod.call(cfgObj, "setRefreshMinutes", 15);
            Mod.call(cfgObj, "setMuseum", false);
            Mod.call(cfgObj, "save");
            Mod.staticCall(CFG, "load");
            Object reloaded = Mod.cfg(CFG);
            int days = (Integer) Mod.call(reloaded, "getAverageDays");
            int mins = (Integer) Mod.call(reloaded, "getRefreshMinutes");
            boolean museum = (Boolean) Mod.call(reloaded, "isMuseum");
            c.check(days == 1 && mins == 15 && !museum, "after a reload: days " + days + ", minutes " + mins + ", museum " + museum);
            Mod.call(reloaded, "setAverageDays", 3);
            Mod.call(reloaded, "setRefreshMinutes", 5);
            Mod.call(reloaded, "setMuseum", true);
            Mod.call(reloaded, "save");
            c.note("off: no lines; vanilla diamond: no lines; settings survive load()");
        } finally {
            ItemCases.clear(c, 0, 1);
        }
    }

    static void shot(Session c) throws Exception {
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c); Fake fake = loadPrices(c, cfg)) {
            Mod.staticCall(MUSEUM, "setDonated", Set.of("HYPERION"));
            shift(false);
            give(c, 0, HYPERION);
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, new InventoryScreen(mc.player)));
            c.waitUntil("inventory open", mc -> McCompat.screen(mc) instanceof InventoryScreen, 40);
            c.ctx().waitTicks(3);
            double[] at = c.onClient(mc -> {
                AbstractContainerScreen<?> scr = (AbstractContainerScreen<?>) McCompat.screen(mc);
                Slot slot = scr.getMenu().slots.get(36); // hotbar slot 0
                int left = (Integer) Mod.field(scr, "leftPos");
                int top = (Integer) Mod.field(scr, "topPos");
                double scale = mc.getWindow().getScreenWidth() / (double) scr.width;
                return new double[]{(left + slot.x + 8) * scale, (top + slot.y + 8) * scale};
            });
            c.ctx().getInput().setCursorPos(at[0], at[1]);
            c.ctx().waitTicks(5);
            // The container path (getTooltipFromContainerItem) must carry the lines, not only Screen.getTooltipFromItem.
            List<String> containerLines = c.onClient(mc -> {
                AbstractContainerScreen<?> scr = (AbstractContainerScreen<?>) McCompat.screen(mc);
                ItemStack st = scr.getMenu().slots.get(36).getItem();
                @SuppressWarnings("unchecked")
                List<Component> l = (List<Component>) Mod.call(scr, "getTooltipFromContainerItem", st);
                List<String> s = new ArrayList<>();
                for (Component x : l) {
                    s.add(x.getString());
                }
                return s;
            });
            c.check(containerLines.contains("§eLowest BIN Price: §b5,900,000.0 Coins"), "container tooltip: " + containerLines);
            int[] on = colourCounts(c, c.name() + "-hyperion");
            // The same screen with the feature off: toasts and the diamond icon count the same in both, so only the
            // difference is the price lines.
            cfg.set(CFG, "Enabled", false);
            c.ctx().waitTicks(3);
            int[] off = colourCounts(c, c.name() + "-off");
            c.note("aqua/yellow px: on " + on[0] + "/" + on[1] + ", off " + off[0] + "/" + off[1]);
            c.check(on[0] - off[0] > 150 && on[1] - off[1] > 150, "the drawn tooltip gained only " + (on[0] - off[0])
                    + " aqua and " + (on[1] - off[1]) + " yellow pixels with the feature on - the price lines were not drawn");
        } finally {
            shift(null);
            Mod.staticCall(MUSEUM, "clearForTest");
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
            ItemCases.clear(c, 0);
        }
    }
    /** Screenshot (kept in the report) and its {aqua, yellow} text-pixel counts. */
    static int[] colourCounts(Session c, String name) throws Exception {
        Path taken = c.ctx().takeScreenshot(Report.fileName(name));
        Report.screenshot(name, taken);
        BufferedImage img = javax.imageio.ImageIO.read(taken.toFile());
        int aqua = 0;
        int yellow = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (r < 0x70 && g > 0xE8 && b > 0xE8) {
                    aqua++;
                } else if (r > 0xE8 && g > 0xE8 && b < 0x70) {
                    yellow++;
                }
            }
        }
        return new int[]{aqua, yellow};
    }
}

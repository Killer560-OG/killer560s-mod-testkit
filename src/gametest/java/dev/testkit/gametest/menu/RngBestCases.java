package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * rngbest-1118: RNG Meter "Highlight Best" (default ON, acts only while the overlay is on). A fake RNG Meter menu holds
 * rewards of known price and pity; the highlight must sit on the slot with the most coins per meter point (the number the
 * ranking shows), never on an unpriced one, move when a price moves, and send no click. Measured as pixels changed in each
 * slot rectangle against a screenshot taken with the switch off.
 */
final class RngBestCases {

    private static final String CFG = "rngmeter.RngMeterConfig";
    private static final String TITLE = "Highlight Best RNG Meter";
    // name, slot, price, pity, id
    private static final Object[][] ITEMS = {
            {"Cleave VI", 10, 1_000_000L, 2_000L, "ENCHANTMENT_CLEAVE_6"},
            {"Critical VII", 11, 3_000_000L, 10_000L, "ENCHANTMENT_CRITICAL_7"},
            {"Soul Eater V", 12, 0L, 100L, "ENCHANTMENT_SOUL_EATER_5"},
            {"Lethality VI", 13, 1_000_000L, 2_000L, "ENCHANTMENT_LETHALITY_6"},
    };

    private RngBestCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1118-menu-rng-highlight-best", RngBestCases::best);
    }

    private static JsonObject menu(String title) {
        Map<Integer, JsonObject> slots = new LinkedHashMap<>();
        for (Object[] it : ITEMS) {
            slots.put((Integer) it[1], FlipCroesusCases.item("minecraft:enchanted_book", "§9" + it[0],
                    "§7Dungeon Score: §a0§7/§a" + String.format("%,d", it[3])));
        }
        return FlipCroesusCases.menu(title, 3, slots, Map.of());
    }

    /** Puts the prices in the cache (Critical VII at {@code critical}); {@code all} false removes every product. */
    private static void prices(Session c, long critical, boolean all) {
        c.ctx().runOnClient(mc -> {
            Object prices = Mod.field("rngmeter.RngMeterEngine", "PRICES");
            for (Object[] it : ITEMS) {
                long p = !all ? 0 : it[0].equals("Critical VII") ? critical : (Long) it[2];
                if (p > 0) {
                    Mod.call(prices, "putBazaarPriceForTest", it[4], p, p);
                } else {
                    Mod.call(prices, "removeBazaarPriceForTest", it[4]);
                }
            }
        });
    }

    /** Per top slot: did more than a few pixels of its 16x16 box change between the two shots? */
    private static boolean[] changed(Session c, BufferedImage base, BufferedImage now) {
        double[] g = c.onClient(mc -> new double[]{mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()});
        List<int[]> slots = c.onClient(mc -> {
            AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
            int left = ((Number) Mod.field(s, "leftPos")).intValue();
            int top = ((Number) Mod.field(s, "topPos")).intValue();
            List<int[]> out = new ArrayList<>();
            for (int i = 0; i < s.getMenu().slots.size() - 36; i++) {
                Slot sl = s.getMenu().slots.get(i);
                out.add(new int[]{left + sl.x, top + sl.y});
            }
            return out;
        });
        double rx = now.getWidth() / g[0];
        double ry = now.getHeight() / g[1];
        boolean[] out = new boolean[slots.size()];
        for (int i = 0; i < out.length; i++) {
            int n = 0;
            int x0 = (int) (slots.get(i)[0] * rx);
            int y0 = (int) (slots.get(i)[1] * ry);
            for (int y = y0; y < y0 + (int) (16 * ry); y++) {
                for (int x = x0; x < x0 + (int) (16 * rx); x++) {
                    if ((base.getRGB(x, y) & 0xFFFFFF) != (now.getRGB(x, y) & 0xFFFFFF)) {
                        n++;
                    }
                }
            }
            out[i] = n > 40 * rx * ry;
        }
        return out;
    }

    private static String which(boolean[] ch) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int i = 0; i < ch.length; i++) {
            if (ch[i]) {
                sb.append(i).append(' ');
                n++;
            }
        }
        return n + " changed [" + sb.toString().trim() + "]";
    }

    private static int count(boolean[] a) {
        int n = 0;
        for (boolean b : a) {
            n += b ? 1 : 0;
        }
        return n;
    }

    private static void set(Session c, String setter, boolean v) {
        c.ctx().runOnClient(mc -> Mod.set(CFG, setter, v));
    }

    private static void open(Session c, String title) {
        MenuKit.reset(c);
        MenuKit.show(c, menu(title));
        MenuKit.awaitScreen(c, title, 100);
        c.ctx().waitTicks(30);
    }

    private static void best(Session c) throws Exception {
        Path file = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-rngmeter.json"));
        byte[] was = Files.exists(file) ? Files.readAllBytes(file) : null;
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CFG, "Enabled", true).set(CFG, "HighlightBest", false);
            c.hx().sidebar("§e§lSKYBLOCK", "§710/31/26 §8m12AB", "", " §7⏣ §bHub", "", "§ewww.hypixel.net");

            // A config with no file reads Highlight Best ON and the overlay OFF.
            Files.deleteIfExists(file);
            List<Object> d = c.onClient(mc -> {
                Mod.staticCall(CFG, "load");
                return List.of(Mod.call(Mod.cfg(CFG), "isHighlightBest"), Mod.call(Mod.cfg(CFG), "isEnabled"));
            });
            c.check(Boolean.TRUE.equals(d.get(0)) && Boolean.FALSE.equals(d.get(1)),
                    "no-file defaults (highlightBest, enabled) were " + d + ", want [true, false]");
            set(c, "setEnabled", true);
            set(c, "setHighlightBest", false);

            prices(c, 3_000_000L, true);
            int clicks0 = c.events("container.click").size();
            open(c, TITLE);
            BufferedImage off = BazaarReskinCases.shot(c, "switch-off");

            // 10 is 500/pt, 11 is 300/pt, 12 has no price, 13 ties 10 (500/pt) and loses to the first slot.
            set(c, "setHighlightBest", true);
            c.ctx().waitTicks(30);
            boolean[] on = changed(c, off, BazaarReskinCases.shot(c, "best-10"));
            c.check(on[10] && count(on) == 1, "default prices: only slot 10 should change, " + which(on));

            prices(c, 9_000_000L, true); // Critical VII is now 900/pt
            c.ctx().waitTicks(30);
            boolean[] moved = changed(c, off, BazaarReskinCases.shot(c, "best-11"));
            c.check(moved[11] && count(moved) == 1, "after a price rise only slot 11 should change, " + which(moved));

            prices(c, 0L, false); // nothing priced
            c.ctx().waitTicks(30);
            boolean[] none = changed(c, off, BazaarReskinCases.shot(c, "unpriced"));
            c.check(count(none) == 0, "no price known, " + which(none));

            prices(c, 9_000_000L, true);
            c.ctx().waitTicks(30);
            set(c, "setEnabled", false); // the overlay's own switch off: nothing, with Highlight Best on
            c.ctx().waitTicks(30);
            boolean[] ovOff = changed(c, off, BazaarReskinCases.shot(c, "overlay-off"));
            c.check(count(ovOff) == 0, "overlay off, " + which(ovOff));
            c.check(c.events("container.click").size() == clicks0, "the highlight sent a click");

            // A title that is no RNG Meter gets nothing.
            set(c, "setEnabled", true);
            set(c, "setHighlightBest", false);
            open(c, "Plain Chest");
            BufferedImage plainOff = BazaarReskinCases.shot(c, "plain-off");
            set(c, "setHighlightBest", true);
            c.ctx().waitTicks(30);
            boolean[] plain = changed(c, plainOff, BazaarReskinCases.shot(c, "plain-on"));
            c.check(count(plain) == 0, "a non-RNG title: " + which(plain));
        } finally {
            prices(c, 0L, false);
            MenuKit.reset(c);
            if (was == null) {
                Files.deleteIfExists(file);
            } else {
                Files.write(file, was);
            }
            c.ctx().runOnClient(mc -> Mod.staticCall(CFG, "load"));
        }
    }
}

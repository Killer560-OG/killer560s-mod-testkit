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

    /** Per top slot: is the highlight's 1 px border (the theme accent, opaque) along the top edge of its 16x16 box? */
    private static boolean[] marked(Session c, BufferedImage now) {
        int accent = (Integer) c.onClient(mc -> Mod.staticCall("gui.ThemeAccent", "light", 0xFFFFAA00)) & 0xFFFFFF;
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
            int y = (int) (slots.get(i)[1] * ry);
            for (int x = x0; x < x0 + (int) (16 * rx); x++) {
                if ((now.getRGB(x, y) & 0xFFFFFF) == accent) {
                    n++;
                }
            }
            out[i] = n >= (int) (12 * rx);
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

    private static final String OVERLAY = "rngmeter.RngMeterOverlay";

    private static void clock(Session c, long ms) {
        c.ctx().runOnClient(mc -> Mod.setField(OVERLAY, "pulseClockForTest", ms));
    }

    /** RGB of the top edge pixel of slot {@code idx} (the highlight's border row), inside the box. */
    private static int edge(Session c, int idx, String label) {
        BufferedImage now = BazaarReskinCases.shot(c, label);
        double[] g = c.onClient(mc -> new double[]{mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()});
        int[] xy = c.onClient(mc -> {
            AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
            Slot sl = s.getMenu().slots.get(idx);
            return new int[]{((Number) Mod.field(s, "leftPos")).intValue() + sl.x, ((Number) Mod.field(s, "topPos")).intValue() + sl.y};
        });
        return now.getRGB((int) ((xy[0] + 6) * now.getWidth() / g[0]), (int) (xy[1] * now.getHeight() / g[1])) & 0xFFFFFF;
    }

    private static int sum(int rgb) {
        return 255 - (rgb & 255); // the accent has no blue, so less blue = stronger highlight over the grey slot
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
            boolean[] off = marked(c, BazaarReskinCases.shot(c, "switch-off"));
            c.check(count(off) == 0, "Highlight Best off, " + which(off));

            // 10 is 500/pt, 11 is 300/pt, 12 has no price, 13 ties 10 (500/pt) and loses to the first slot.
            set(c, "setHighlightBest", true);
            clock(c, 300L); // pulse at full strength, so the opaque-border check below holds
            c.ctx().waitTicks(30);
            boolean[] on = marked(c, BazaarReskinCases.shot(c, "best-10"));
            c.check(on[10] && count(on) == 1, "default prices: only slot 10 should change, " + which(on));

            // Pulse: the border alpha eases 45%..100% over 1200 ms and never reaches 0 (slot 10 is still best here).
            int[] t = {300, 0, 600, 900, 1500};
            int[] px = new int[t.length];
            for (int i = 0; i < t.length; i++) {
                clock(c, t[i]);
                c.ctx().waitTicks(3);
                px[i] = sum(edge(c, 10, "pulse-" + t[i]));
            }
            c.check(px[0] > px[1] && px[1] > px[3] && px[2] > px[3] && px[4] == px[0],
                    "pulse samples at 300/0/600/900/1500 ms should be peak > mid > trough and wrap: " + java.util.Arrays.toString(px));
            c.check(px[3] > 0 && px[3] < px[0] * 0.9, "trough must be dimmer than the peak but still drawn: " + java.util.Arrays.toString(px));
            clock(c, 300L);
            c.ctx().waitTicks(3);
            prices(c, 9_000_000L, true); // Critical VII is now 900/pt
            c.ctx().waitTicks(30);
            boolean[] moved = marked(c, BazaarReskinCases.shot(c, "best-11"));
            c.check(moved[11] && count(moved) == 1, "after a price rise only slot 11 should change, " + which(moved));

            prices(c, 0L, false); // nothing priced
            c.ctx().waitTicks(30);
            boolean[] none = marked(c, BazaarReskinCases.shot(c, "unpriced"));
            c.check(count(none) == 0, "no price known, " + which(none));

            prices(c, 9_000_000L, true);
            c.ctx().waitTicks(30);
            set(c, "setEnabled", false); // the overlay's own switch off: nothing, with Highlight Best on
            c.ctx().waitTicks(30);
            boolean[] ovOff = marked(c, BazaarReskinCases.shot(c, "overlay-off"));
            c.check(count(ovOff) == 0, "overlay off, " + which(ovOff));
            c.check(c.events("container.click").size() == clicks0, "the highlight sent a click");

            // A title that is no RNG Meter gets nothing.
            set(c, "setEnabled", true);
            set(c, "setHighlightBest", false);
            open(c, "Plain Chest");
            set(c, "setHighlightBest", true);
            c.ctx().waitTicks(30);
            boolean[] plain = marked(c, BazaarReskinCases.shot(c, "plain-on"));
            c.check(count(plain) == 0, "a non-RNG title: " + which(plain));
        } finally {
            clock(c, -1L);
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

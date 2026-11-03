package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 2802-2804 (mod ahbuttons-1103): the AH Price Buttons, which replaced the AH Price Copier (killer560 2026-11-03: "Instead
 * have it as buttons in the menu. It should work if you are using custom ah or normal. Have one for lbin one for average
 * and one for estimated value").
 *
 * <p>Known prices: the lowest BIN of a plain fixture item from the AH fixture scan ({@link Flip1101MenuCases#seedMarket}),
 * and the Price Tooltip's fake ({@link PriceTooltipCases.Fake}: Hyperion 3-day average lowest BIN 4,790,000, Est. Item Value
 * of the built Hyperion at full price 191,500,000). Every press is a real mouse press at the button the mod drew; every
 * case checks the server saw no container click, sign update, command or chat line.
 * <ul>
 *   <li>2802 Hypixel's own menu (Auction House reskin off): putting an item in copies nothing by itself (the old copier is
 *       gone); Copy LBIN of 3 copies 3 x lowest - 1; Copy Average and Copy Estimated Value of the built Hyperion copy
 *       4790000 and 191500000; an unlisted item grays all four with a reason and a press copies nothing; after "BIN Auction
 *       started" Copy My Last Price copies the price just listed at.</li>
 *   <li>2803 the reskinned Auction House: the same buttons in its toolbar, Average and Value copy the same numbers, the
 *       grayed Copy LBIN does nothing, and the reskin sent no click.</li>
 *   <li>2804 the switch: off, neither screen draws a button; the setting survives a reload.</li>
 * </ul>
 */
final class AhPriceButtonsCases {

    private AhPriceButtonsCases() {
    }

    static final String BUTTONS = "ahflip.AhPriceButtons";
    static final String CFG = "ahflip.AhFlipConfig";
    static final String RESKIN = "auction.ah.AhReskin";
    static final String TITLE = "Create BIN Auction";
    static final String UNLISTED = "minecraft:paper[minecraft:custom_data={id:\"TESTKIT_UNLISTED_THING\"}]";

    static void register(Session s) {
        MenuSuite.test(s, "2802-menu-ah-price-buttons-vanilla", AhPriceButtonsCases::vanilla);
        MenuSuite.test(s, "2803-menu-ah-price-buttons-reskin", AhPriceButtonsCases::reskin);
        MenuSuite.test(s, "2804-menu-ah-price-buttons-off", AhPriceButtonsCases::off);
    }

    static boolean present(Session c) {
        if (!Mod.has(BUTTONS)) {
            c.note("mod before ahbuttons-1103: nothing to check");
            return false;
        }
        return true;
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    static JsonObject createMenu(String stack, String name, int count, String... lore31) {
        JsonObject item = FlipCroesusCases.item(stack, name);
        item.addProperty("count", count);
        Map<Integer, JsonObject> slots = new HashMap<>();
        slots.put(13, item);
        if (lore31.length > 0) {
            slots.put(31, FlipCroesusCases.item("minecraft:gold_ingot", "§6Item Price", lore31));
        }
        return FlipCroesusCases.menu(TITLE, 6, slots, Map.of());
    }

    static void show(Session c, JsonObject spec) {
        MenuKit.show(c, spec);
        MenuKit.awaitScreen(c, TITLE, 100);
        c.ctx().waitTicks(8);
    }

    /** KIND -> coins (-1 grayed), fresh. */
    @SuppressWarnings("unchecked")
    static Map<String, Long> quotes(Session c) {
        List<String> lines = c.onClient(mc -> (List<String>) Mod.staticCall(BUTTONS, "quotesForTest"));
        Map<String, Long> out = new HashMap<>();
        for (String l : lines) {
            String[] p = l.split(" ", 3);
            out.put(p[0], Long.parseLong(p[1]));
        }
        c.note("quotes: " + lines);
        return out;
    }

    @SuppressWarnings("unchecked")
    static String quoteLine(Session c, String kind) {
        List<String> lines = c.onClient(mc -> (List<String>) Mod.staticCall(BUTTONS, "quotesForTest"));
        for (String l : lines) {
            if (l.startsWith(kind + " ")) {
                return l;
            }
        }
        return "";
    }

    /** The vanilla panel's rect of a button {x, y, w, h} in GUI units, or null. */
    @SuppressWarnings("unchecked")
    static int[] vanillaRect(Session c, String kind) {
        c.ctx().waitTicks(2);
        List<String> rects = c.onClient(mc -> (List<String>) Mod.staticCall(BUTTONS, "vanillaRectsForTest"));
        for (String r : rects) {
            String[] p = r.split(" ");
            if (p[0].equals(kind)) {
                return new int[]{Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4])};
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static List<String> vanillaRects(Session c) {
        return c.onClient(mc -> (List<String>) Mod.staticCall(BUTTONS, "vanillaRectsForTest"));
    }

    static String clip(Session c) {
        return c.onClient(mc -> mc.keyboardHandler.getClipboard());
    }

    /** Presses a vanilla button and returns the clipboard after it. */
    static String pressVanilla(Session c, String kind) {
        int[] r = vanillaRect(c, kind);
        c.check(r != null, "no " + kind + " button drawn beside the chest: " + vanillaRects(c));
        if (r == null) {
            return clip(c);
        }
        AuctionHouseCases.press(c, r, 0, 1f);
        c.ctx().waitTicks(2);
        return clip(c);
    }

    /** Nothing a button press could have sent reached the server. */
    static void nothingSent(Session c) {
        c.check(c.events("container.click").isEmpty(), "the server saw container clicks: " + c.events("container.click"));
        c.check(c.events("sign.update").isEmpty(), "the server saw a sign update: " + c.events("sign.update"));
        c.check(c.events("cmd").isEmpty(), "the server saw a command: " + c.events("cmd"));
        c.check(c.events("chat").isEmpty(), "the server saw chat: " + c.events("chat"));
    }

    /** The switch's own (ungated) value: Mod.with would read the Skyblock-gated getter and could restore OFF. */
    static boolean buttonsRaw(Session c) {
        return c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(CFG), "isPriceButtonsEnabledRaw"));
    }

    static void setButtons(Session c, boolean on) {
        c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setPriceButtonsEnabled", on));
    }

    static void setup(Session c, MenuKit.Cfg cfg, boolean reskin) {
        setButtons(c, true);
        cfg.set(CFG, "CopierUndercut", 1L).set("auction.AuctionConfig", "AhEnabled", reskin);
        c.ctx().runOnClient(mc -> {
            Mod.staticCall("util.SkyblockGate", "setEnabled", false);
            Mod.staticCall("ahflip.AhPriceCopier", "resetForTest");
            Mod.staticCall(BUTTONS, "resetForTest");
            mc.keyboardHandler.setClipboard("before");
        });
    }

    static void restore(Session c, boolean gate, String oldClip, boolean buttons) {
        c.ctx().runOnClient(mc -> {
            Object inst = Mod.cfg(CFG);
            Mod.call(inst, "setPriceButtonsEnabled", buttons);
            Mod.call(inst, "save");
            Mod.staticCall("util.SkyblockGate", "setEnabled", gate);
            Mod.staticCall("ahflip.AhPriceCopier", "resetForTest");
            Mod.staticCall(BUTTONS, "resetForTest");
            mc.keyboardHandler.setClipboard(oldClip == null ? "" : oldClip);
            if (McCompat.screen(mc) != null) {
                McCompat.setScreen(mc, null);
            }
        });
        MenuKit.reset(c);
    }

    // ---- 2802 -------------------------------------------------------------------------------------------------------

    static void vanilla(Session c) throws Exception {
        if (!present(c)) {
            return;
        }
        MenuKit.reset(c);
        boolean gate = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        String oldClip = clip(c);
        boolean buttons = buttonsRaw(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c); PriceTooltipCases.Fake fake = PriceTooltipCases.loadPrices(c, cfg)) {
            setup(c, cfg, false);
            Object[] m = Flip1101MenuCases.seedMarket(c, 1, true);
            c.check(m != null, "no plain item with a BIN in the fixture");
            if (m == null) {
                return;
            }
            String key = (String) m[0];
            long lowest = (Long) m[1];
            String stackA = "minecraft:paper[minecraft:custom_data={id:\"" + key + "\"}]";

            // An item going in copies nothing by itself any more.
            show(c, createMenu(stackA, "§aTest Item", 3, "§7Buy it now: §61,234,567 coins"));
            c.ctx().waitTicks(20);
            c.check("before".equals(clip(c)), "the clipboard changed with no press: \"" + clip(c) + "\"");
            c.check("NONE".equals(c.onClient(mc -> (String) Mod.staticCall(RESKIN, "kindForTest"))),
                    "the reskin is drawing the menu with the Auction House off");

            Map<String, Long> q = quotes(c);
            long wantLbin = lowest * 3 - 1;
            c.check(q.getOrDefault("LBIN", -2L) == wantLbin, "LBIN quote " + q.get("LBIN") + ", want " + wantLbin
                    + " (3 x " + lowest + " - 1)");
            c.check(q.getOrDefault("AVERAGE", 0L) < 0 && q.getOrDefault("VALUE", 0L) < 0 && q.getOrDefault("LAST", 0L) < 0,
                    "a fixture item the price fake has no price for should gray Average/Value/Last: " + q);
            String got = pressVanilla(c, "LBIN");
            c.check(Long.toString(wantLbin).equals(got), "Copy LBIN put \"" + got + "\" on the clipboard, want " + wantLbin);
            String kind = c.onClient(mc -> (String) Mod.staticCall(BUTTONS, "lastCopiedKindForTest"));
            c.check("LBIN".equals(kind), "last copy came from " + kind);
            // A grayed button: the press is swallowed and copies nothing.
            String after = pressVanilla(c, "AVERAGE");
            c.check(Long.toString(wantLbin).equals(after), "a grayed Copy Average changed the clipboard to \"" + after + "\"");
            c.check(c.onClient(mc -> (Long) Mod.staticCall(BUTTONS, "lastCopiedForTest")) == wantLbin,
                    "a grayed press copied something");

            // He lists it: Copy My Last Price offers that price.
            c.server().command("tellraw @a {\"text\":\"BIN Auction started for Test Item!\"}");
            c.ctx().waitTicks(10);
            MenuKit.reset(c);
            show(c, createMenu(stackA, "§aTest Item", 3));
            long wantLast = (1_234_567L / 3) * 3;
            Map<String, Long> q2 = quotes(c);
            c.check(q2.getOrDefault("LAST", -2L) == wantLast, "LAST quote " + q2.get("LAST") + ", want " + wantLast);
            got = pressVanilla(c, "LAST");
            c.check(Long.toString(wantLast).equals(got), "Copy My Last Price put \"" + got + "\", want " + wantLast);

            // The built Hyperion: average lowest BIN and Est. Item Value from the fake.
            MenuKit.reset(c);
            show(c, createMenu(PriceTooltipCases.HYPERION_BUILT, "§dHyperion", 1));
            Map<String, Long> q3 = quotes(c);
            c.check(q3.getOrDefault("AVERAGE", -2L) == 4_790_000L, "AVERAGE quote " + q3.get("AVERAGE") + ", want 4790000");
            c.check(q3.getOrDefault("VALUE", -2L) == 191_500_000L, "VALUE quote " + q3.get("VALUE") + ", want 191500000");
            got = pressVanilla(c, "AVERAGE");
            c.check("4790000".equals(got), "Copy Average put \"" + got + "\", want 4790000 (3-day average lowest BIN)");
            got = pressVanilla(c, "VALUE");
            c.check("191500000".equals(got), "Copy Estimated Value put \"" + got + "\", want 191500000");

            // An item nothing knows: all four grayed, each with its reason, and a press copies nothing.
            MenuKit.reset(c);
            c.ctx().runOnClient(mc -> mc.keyboardHandler.setClipboard("untouched"));
            show(c, createMenu(UNLISTED, "§fUnlisted Thing", 1));
            Map<String, Long> q4 = quotes(c);
            for (String k : List.of("LBIN", "AVERAGE", "VALUE", "LAST")) {
                c.check(q4.getOrDefault(k, 0L) < 0, k + " is not grayed for an unlisted item: " + q4);
                String line = quoteLine(c, k);
                c.check(line.contains("(no price)") && line.split("\\|").length >= 3,
                        k + " grayed without a reason: " + line);
                String g = pressVanilla(c, k);
                c.check("untouched".equals(g), "grayed " + k + " changed the clipboard to \"" + g + "\"");
            }
            c.note("LBIN " + key + " x3 -> " + wantLbin + "; last listed 1,234,567 for 3 -> " + wantLast
                    + "; Hyperion average 4790000, value 191500000; unlisted: " + quoteLine(c, "LBIN"));
            nothingSent(c);
        } finally {
            restore(c, gate, oldClip, buttons);
        }
    }

    // ---- 2803 -------------------------------------------------------------------------------------------------------

    static void reskin(Session c) throws Exception {
        if (!present(c)) {
            return;
        }
        MenuKit.reset(c);
        boolean gate = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        String oldClip = clip(c);
        boolean buttons = buttonsRaw(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c); PriceTooltipCases.Fake fake = PriceTooltipCases.loadPrices(c, cfg)) {
            setup(c, cfg, true);
            Flip1101MenuCases.seedMarket(c, 1, true);
            AuctionHouseCases.show(c, createMenu(PriceTooltipCases.HYPERION_BUILT, "§dHyperion", 1), "CREATE");
            c.ctx().waitTicks(10);
            List<String> layout = AuctionHouseCases.reskinLayout(c);
            float f = AuctionHouseCases.factor(c);
            int[] avg = AuctionHouseCases.hotspot(layout, "price-average");
            int[] val = AuctionHouseCases.hotspot(layout, "price-value");
            c.check(avg != null && val != null, "no Average/Value buttons in the reskin: " + AuctionHouseCases.hotspotIds(layout));
            int[] lbinOff = null;
            for (String line : layout) {
                String[] p = line.split(" ", 7);
                if (p[0].equals("disabled") && p[1].equals("price-lbin")) {
                    lbinOff = new int[]{Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]),
                            Integer.parseInt(p[5])};
                }
            }
            c.check(lbinOff != null, "Copy LBIN is not grayed for a Hyperion the scan has no BIN of: "
                    + layout.stream().filter(l -> l.contains("price-")).toList());
            // Clear of the search field: every price button lies right of it in the toolbar.
            int[] search = AuctionHouseCases.hotspot(layout, "search");
            if (search != null && avg != null) {
                c.check(avg[0] >= search[0] + search[2], "Copy Average overlaps the search field: " + java.util.Arrays.toString(avg)
                        + " vs " + java.util.Arrays.toString(search));
            }
            if (avg == null || val == null) {
                return;
            }
            AuctionHouseCases.press(c, avg, 0, f);
            c.check("4790000".equals(clip(c)), "reskin Copy Average put \"" + clip(c) + "\", want 4790000");
            AuctionHouseCases.press(c, val, 0, f);
            c.check("191500000".equals(clip(c)), "reskin Copy Estimated Value put \"" + clip(c) + "\", want 191500000");
            if (lbinOff != null) {
                AuctionHouseCases.press(c, lbinOff, 0, f);
                c.check("191500000".equals(clip(c)), "the grayed reskin Copy LBIN changed the clipboard to \"" + clip(c) + "\"");
            }
            String clicks = AuctionHouseCases.clicks(c) + "";
            c.check(AuctionHouseCases.clicks(c) == 0, "the reskin sent container clicks: " + clicks);
            c.check(vanillaRects(c).isEmpty(), "the vanilla panel is drawn under the reskin: " + vanillaRects(c));
            c.note("reskin toolbar: " + layout.stream().filter(l -> l.contains("price-")).toList());
            nothingSent(c);
        } finally {
            restore(c, gate, oldClip, buttons);
        }
    }

    // ---- 2804 -------------------------------------------------------------------------------------------------------

    static void off(Session c) throws Exception {
        if (!present(c)) {
            return;
        }
        MenuKit.reset(c);
        boolean gate = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        String oldClip = clip(c);
        boolean buttons = buttonsRaw(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            setup(c, cfg, false);
            setButtons(c, false);
            show(c, createMenu(UNLISTED, "§fUnlisted Thing", 1));
            c.check(vanillaRects(c).isEmpty(), "buttons drawn with the switch off: " + vanillaRects(c));
            MenuKit.reset(c);
            c.ctx().runOnClient(mc -> Mod.set("auction.AuctionConfig", "setAhEnabled", true));
            AuctionHouseCases.show(c, createMenu(UNLISTED, "§fUnlisted Thing", 1), "CREATE");
            c.ctx().waitTicks(5);
            List<String> layout = AuctionHouseCases.reskinLayout(c);
            c.check(layout.stream().noneMatch(l -> l.contains(" price-")), "reskin buttons with the switch off: "
                    + layout.stream().filter(l -> l.contains("price-")).toList());
            // Saved and read back.
            boolean[] back = c.onClient(mc -> {
                Object inst = Mod.staticCall(CFG, "getInstance");
                Mod.call(inst, "setPriceButtonsEnabled", false);
                Mod.call(inst, "save");
                Mod.staticCall(CFG, "load");
                boolean offRead = (Boolean) Mod.call(Mod.staticCall(CFG, "getInstance"), "isPriceButtonsEnabledRaw");
                Mod.call(Mod.staticCall(CFG, "getInstance"), "setPriceButtonsEnabled", true);
                Mod.call(Mod.staticCall(CFG, "getInstance"), "save");
                Mod.staticCall(CFG, "load");
                boolean onRead = (Boolean) Mod.call(Mod.staticCall(CFG, "getInstance"), "isPriceButtonsEnabledRaw");
                return new boolean[]{offRead, onRead};
            });
            c.check(!back[0] && back[1], "the switch did not survive a reload: OFF read back " + back[0] + ", ON read back "
                    + back[1]);
            nothingSent(c);
        } finally {
            restore(c, gate, oldClip, buttons);
        }
    }
}

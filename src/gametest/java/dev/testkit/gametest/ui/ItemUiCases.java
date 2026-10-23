package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 701-707, 709-712: killer560's 2026-10-11 item / inventory UI list (mod branch item-ui). 708 is in the menu suite
 * ({@code menu/ItemUiMenuCases}).
 * <ul>
 *   <li>701 Inventory Search fills a matching slot solid in the highlight colour (screenshot, GUI 2) and nothing else.</li>
 *   <li>702 the search bar's NEU-style calculator: a table of sums, plain text left alone, and "= result" after a sum
 *       typed into the bar with real key presses.</li>
 *   <li>703 Slot Binds by the bind key alone: press on one slot, press on the other; a left click in between binds
 *       nothing; an illegal pair binds nothing.</li>
 *   <li>704 Pet Wheel: no Slices setting, no "pet(s) on the wheel" line, one page for 14 pets, and the label grows
 *       with Scale (label pixels at 200% are ~4x those at 100%).</li>
 *   <li>705 Item Protect: the tiny star stays on a HOVERED protected slot; Drops To Force 2-4 takes that many presses.</li>
 *   <li>706 GIFs/DVDs are not drawn while a container is open: a DVD box moves with no screen and stands still with the
 *       inventory open.</li>
 *   <li>707 Storage Search reads the Equipment Wardrobe ("Equipment Sets (n/m)") pages it has cached.</li>
 *   <li>709 Item Browser: hovering an item draws its tooltip (NEU lore, its red line found in the picture).</li>
 *   <li>710 Item Browser: a minion's tiers are one cell; clicking it opens the tier picker; clicking a tier opens it.</li>
 *   <li>711 Item Browser: NEU recipe JSON parsed (grid, counts, forge line) and drawn in the popup.</li>
 *   <li>712 Item Browser: no Default sort (value high-low by default, an old CATALOG reads back as that), no header box,
 *       the bar stays bottom-middle with only the Item Browser on, and the tab carries the note.</li>
 * </ul>
 */
final class ItemUiCases {

    private static final String ISC = "inventorysearch.InventorySearchConfig";
    private static final String ISF = "inventorysearch.InventorySearchFeature";
    private static final String CALC = "inventorysearch.SearchCalculator";
    private static final String IBC = "itembrowser.ItemBrowserConfig";
    private static final String IBF = "itembrowser.ItemBrowserFeature";
    private static final String REPO = "itembrowser.SkyblockItemRepository";
    private static final String NEU = "itembrowser.NeuItemData";
    private static final String CRAFT = "itembrowser.ItemCraftView";
    private static final String IPC = "itemprotect.ItemProtectConfig";
    private static final String SBC = "slotbinds.SlotBindsConfig";
    private static final String PWC = "petwheel.PetWheelConfig";
    private static final int MAGENTA = 0xFF00FF;

    private ItemUiCases() {
    }

    // ==== shared =======================================================================================================

    private static boolean pinkish(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return rgb >= 0 && r >= 180 && g <= 100 && b >= 180;
    }

    /** Star colour under the hovered slot's highlight: magenta possibly lightened, never green-dominant. */
    private static boolean pinkLoose(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return rgb >= 0 && r >= 180 && b >= 180 && r - g >= 60;
    }

    private static int countPink(BufferedImage img, int x0, int y0, int x1, int y1) {
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if (pinkish(InvBatchCases.rgb(img, x, y))) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Pixels whose summed RGB delta between two frames is above 30 (docs: a fixed-colour window is fragile). */
    private static int changed(BufferedImage a, BufferedImage b, int[] skipRect) {
        int n = 0;
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (skipRect != null && x >= skipRect[0] && x < skipRect[2] && y >= skipRect[1] && y < skipRect[3]) {
                    continue;
                }
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                int d = Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF))
                        + Math.abs((p & 0xFF) - (q & 0xFF));
                if (d > 30) {
                    n++;
                }
            }
        }
        return n;
    }

    private static int changedIn(BufferedImage a, BufferedImage b, int x0, int y0, int x1, int y1) {
        int n = 0;
        for (int y = Math.max(0, y0); y < Math.min(y1, Math.min(a.getHeight(), b.getHeight())); y++) {
            for (int x = Math.max(0, x0); x < Math.min(x1, Math.min(a.getWidth(), b.getWidth())); x++) {
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                int d = Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF))
                        + Math.abs((p & 0xFF) - (q & 0xFF));
                if (d > 30) {
                    n++;
                }
            }
        }
        return n;
    }

    private static void setQuery(UiCase c, String q) {
        c.onClient(mc -> {
            StringBuilder sb = (StringBuilder) Mod.field(ISF, "QUERY");
            sb.setLength(0);
            sb.append(q);
            return null;
        });
    }

    private static void openInventory(UiCase c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, new InventoryScreen(mc.player));
            return null;
        });
        c.ticks(5);
    }

    private static void closeScreen(UiCase c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        c.ticks(2);
    }

    /** GUI position {x, y} of the player-inventory slot with container index {@code invIndex} on the open screen. */
    private static int[] invSlotPos(UiCase c, int invIndex) {
        return c.onClient(mc -> {
            AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
            int left = (Integer) Mod.field(s, "leftPos");
            int top = (Integer) Mod.field(s, "topPos");
            for (Slot slot : s.getMenu().slots) {
                if (slot.container == mc.player.getInventory() && slot.getContainerSlot() == invIndex) {
                    return new int[]{left + slot.x, top + slot.y, slot.index};
                }
            }
            throw new AssertionError("no slot for inventory index " + invIndex);
        });
    }

    private static void hoverGui(UiCase c, double gx, double gy) {
        double scale = c.onClient(mc -> (double) mc.getWindow().getGuiScale());
        c.ctx().getInput().setCursorPos(gx * scale, gy * scale);
        c.ticks(3);
    }

    private static Path staticPath(UiCase c, String cls, String field) {
        return c.onClient(mc -> (Path) R.getStatic(R.cls(cls), field));
    }

    private static void writeBack(Path p, byte[] old) {
        try {
            if (old == null) {
                Files.deleteIfExists(p);
            } else {
                Files.write(p, old);
            }
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static Object entry(String id, String name, String material, String tier) {
        try {
            Class<?> cls = Mod.cls("itembrowser.SkyblockItemEntry");
            // By record component NAME (as menu/FlipMarket.itemEntry does), not position: mod 92cc8e87 ("Leather armour in
            // the catalog keeps its colour") added a 15th component, Integer dyeColor, and the positional call broke with
            // "wrong number of arguments: 14 expected: 15". Every component not named here is empty: null, 0 for an int,
            // List.of() for obtainLines (recipeIngredients stays null, as before).
            java.lang.reflect.RecordComponent[] rc = cls.getRecordComponents();
            Class<?>[] types = new Class<?>[rc.length];
            Object[] args = new Object[rc.length];
            for (int i = 0; i < rc.length; i++) {
                types[i] = rc[i].getType();
                args[i] = switch (rc[i].getName()) {
                    case "id" -> id;
                    case "name" -> name;
                    case "material" -> material;
                    case "tier" -> tier;
                    case "category" -> "SWORD";
                    case "obtainLines" -> List.of();
                    default -> types[i] == int.class ? (Object) 0 : null;
                };
            }
            Constructor<?> k = cls.getDeclaredConstructor(types);
            k.setAccessible(true);
            return k.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** Puts a small catalog in the repository (no disk load, no refresh); returns the previous list. */
    private static Object seedCatalog(UiCase c, List<Object> items) {
        return c.onClient(mc -> {
            Object old = Mod.field(REPO, "items");
            Mod.setField(REPO, "triedCache", true);
            Mod.setField(REPO, "lastFetchAtMs", System.currentTimeMillis());
            Mod.staticCall(REPO, "setItems", new ArrayList<>(items));
            return old;
        });
    }

    private static void restoreCatalog(UiCase c, Object old) {
        try {
            c.onClient(mc -> {
                Mod.staticCall(REPO, "setItems", old == null ? List.of() : old);
                return null;
            });
        } catch (Throwable t) {
            c.note("restore catalog: " + UiCase.describe(t));
        }
    }

    private static com.google.gson.JsonObject neuJson() {
        return com.google.gson.JsonParser.parseString("{"
                + "\"internalname\":\"IU_TEST_BLADE\",\"displayname\":\"\\u00a76Test Blade\","
                + "\"lore\":[\"\\u00a77Damage: \\u00a7c+100\",\"\",\"\\u00a76\\u00a7lLEGENDARY SWORD\"],"
                + "\"recipe\":{\"A1\":\"IU_GEM:32\",\"A2\":\"IU_GEM:32\",\"A3\":\"\",\"B1\":\"\",\"B2\":\"STICK:1\","
                + "\"B3\":\"\",\"C1\":\"\",\"C2\":\"STICK:1\",\"C3\":\"\",\"count\":1},"
                + "\"recipes\":[{\"type\":\"forge\",\"inputs\":[\"IU_GEM:4\",\"SKYBLOCK_COIN:1000\"],\"count\":1,\"duration\":3600}]"
                + "}").getAsJsonObject();
    }

    private static List<Object> catalog() {
        List<Object> items = new ArrayList<>();
        items.add(entry("IU_TEST_BLADE", "Test Blade", "DIAMOND_SWORD", "LEGENDARY"));
        items.add(entry("IU_GEM", "Iu Gem", "DIAMOND", "RARE"));
        items.add(entry("BLAZE_GENERATOR_1", "Blaze Minion I", "BLAZE_ROD", "RARE"));
        items.add(entry("BLAZE_GENERATOR_2", "Blaze Minion II", "BLAZE_ROD", "RARE"));
        items.add(entry("BLAZE_GENERATOR_3", "Blaze Minion III", "BLAZE_ROD", "RARE"));
        return items;
    }

    // ==== 701 red fill =================================================================================================

    static void redFill(UiCase c) throws Exception {
        Path isPath = InvBatchCases.configPath(c, ISC);
        Path ibPath = InvBatchCases.configPath(c, IBC);
        byte[] oldIs = InvBatchCases.read(isPath);
        byte[] oldIb = InvBatchCases.read(ibPath);
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            InvBatchCases.survival(c);
            Map<Integer, ItemStack> items = new LinkedHashMap<>();
            items.put(9, new ItemStack(Items.DIAMOND_SWORD));
            items.put(10, new ItemStack(Items.GOLDEN_SWORD));
            InvBatchCases.inventory(c, items);
            c.onClient(mc -> {
                Object is = Mod.cfg(ISC);
                Mod.call(is, "setEnabled", true);
                Mod.call(is, "setSearchLore", false);
                Mod.call(is, "setHighlightColor", 0xFF000000 | MAGENTA);
                Mod.call(Mod.cfg(IBC), "setEnabled", false);
                return null;
            });
            HudEditorCases.setWindow(c, 1280, 720, 2);
            int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
            setQuery(c, "");
            openInventory(c);
            c.ctx().getInput().setCursorPos(2, 2);
            c.ticks(3);
            int[] p9 = invSlotPos(c, 9);
            int[] p10 = invSlotPos(c, 10);
            BufferedImage none = InvBatchCases.shot(c, "no-query");
            setQuery(c, "diamond sword");
            c.ticks(3);
            BufferedImage match = InvBatchCases.shot(c, "diamond-sword");
            ItemStack s9 = c.onClient(mc -> mc.player.getInventory().getItem(9));
            ItemStack s10 = c.onClient(mc -> mc.player.getInventory().getItem(10));
            boolean m9 = c.onClient(mc -> (Boolean) Mod.staticCall(ISF, "slotMatches", s9));
            boolean m10 = c.onClient(mc -> (Boolean) Mod.staticCall(ISF, "slotMatches", s10));
            int pinkNone = countPink(none, p9[0] * gs, p9[1] * gs, (p9[0] + 16) * gs, (p9[1] + 16) * gs);
            int pink9 = countPink(match, p9[0] * gs, p9[1] * gs, (p9[0] + 16) * gs, (p9[1] + 16) * gs);
            int pink10 = countPink(match, p10[0] * gs, p10[1] * gs, (p10[0] + 16) * gs, (p10[1] + 16) * gs);
            int pinkAll = countPink(match, 0, 0, match.getWidth(), match.getHeight());
            c.note(String.format(Locale.ROOT, "GUI %d: pink px in the Diamond Sword slot %d (of %d), Golden Sword slot %d, "
                    + "whole screen %d, no-query frame %d; slotMatches %s/%s", gs, pink9, 256 * gs * gs, pink10, pinkAll,
                    pinkNone, m9, m10));
            c.check(m9 && !m10, "slotMatches: diamond " + m9 + ", golden " + m10);
            // A filled slot: well over a third of the 16x16 is the colour around the sword (an outline is 60 of 256).
            c.check(pink9 >= 100 * gs * gs, "the matching slot is not filled: " + pink9 + " pink px");
            c.check(pink10 == 0, "the non-matching slot is colored: " + pink10);
            c.check(pinkNone == 0, "color drawn with no query: " + pinkNone);
            c.check(pinkAll - pink9 <= 4 * gs * gs, "the color spills outside the matching slot: " + (pinkAll - pink9));
        } finally {
            closeScreen(c);
            setQuery(c, "");
            HudEditorCases.restoreWindow(c, window, oldGui);
            InvBatchCases.restoreConfig(c, ISC, isPath, oldIs);
            InvBatchCases.restoreConfig(c, IBC, ibPath, oldIb);
            InvBatchCases.inventory(c, Map.of());
        }
    }

    // ==== 702 calculator ===============================================================================================

    static void calculator(UiCase c) throws Exception {
        String[][] table = {
                {"1+2*3", "7"}, {"2^3^2", "64"}, {"2**3", "8"}, {"2x3", "6"}, {"5k*3", "15,000"}, {"2s+10", "138"},
                {"1e", "160"}, {"(1.5m-200k)/4", "325,000"}, {"50%*200", "100"}, {"1,5*2", "3"}, {"10/3", "3.33333"},
                {"-3+5", "2"}, {"(2+3)k", "5,000"}, {"1b/1m", "1,000"}, {"1/0", null}, {"diamond", null}, {"64", null},
                {"box", null}, {"2+", null}, {"(1+2", null}, {"stack", null}};
        List<String> wrong = new ArrayList<>();
        for (String[] row : table) {
            String got = c.onClient(mc -> (String) Mod.staticCall(CALC, "resultFor", row[0]));
            if (!java.util.Objects.equals(got, row[1])) {
                wrong.add(row[0] + " -> " + got + " (want " + row[1] + ")");
            }
        }
        c.note(table.length + " sums checked against NEU's rules; wrong: " + wrong);
        c.check(wrong.isEmpty(), "calculator results: " + wrong);

        // Typed into the real bar: click it, type, read what it shows.
        Path isPath = InvBatchCases.configPath(c, ISC);
        byte[] oldIs = InvBatchCases.read(isPath);
        try {
            InvBatchCases.survival(c); // creative turns InventoryScreen into the creative inventory
            c.onClient(mc -> {
                Mod.call(Mod.cfg(ISC), "setEnabled", true);
                return null;
            });
            setQuery(c, "");
            openInventory(c);
            int[] box = c.onClient(mc -> (int[]) Mod.field(ISF, "lastBoxBounds"));
            c.check(box != null, "the search bar was not drawn");
            hoverGui(c, box[0] + box[2] / 2.0, box[1] + box[3] / 2.0);
            c.ctx().getInput().pressMouse(0);
            c.ticks(2);
            boolean listening = c.onClient(mc -> (Boolean) Mod.staticCall(ISF, "isListening"));
            c.ctx().getInput().typeChars("5k*3");
            c.ticks(3);
            String bar = c.onClient(mc -> (String) Mod.staticCall(ISF, "barText"));
            String query = c.onClient(mc -> (String) Mod.staticCall(ISF, "getQuery"));
            c.note("clicked the bar (listening " + listening + "), typed 5k*3: query \"" + query + "\", bar \"" + bar + "\"");
            c.check(listening, "a click on the bar did not start typing");
            c.check("5k*3".equals(query), "typed query reads \"" + query + "\"");
            c.check(bar.endsWith("= \u00a7a15,000"), "the bar does not show the result: " + bar);
        } finally {
            closeScreen(c);
            setQuery(c, "");
            InvBatchCases.restoreConfig(c, ISC, isPath, oldIs);
        }
    }

    // ==== 703 slot binds by key ========================================================================================

    static void slotBindsKey(UiCase c) throws Exception {
        Path p = InvBatchCases.configPath(c, SBC);
        byte[] old = InvBatchCases.read(p);
        try {
            InvBatchCases.survival(c);
            InvBatchCases.inventory(c, Map.of());
            int keyB = 66;
            c.onClient(mc -> {
                Object cfg = Mod.cfg(SBC);
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setBindKey", keyB);
                for (int i = 5; i < 45; i++) {
                    Mod.call(cfg, "removeBind", i);
                }
                return null;
            });
            openInventory(c);
            // 1: key on slot 9, key on hotbar 0 (menu slot 36).
            int[] s9 = invSlotPos(c, 9);
            int[] h0 = invSlotPos(c, 0);
            hoverGui(c, s9[0] + 8, s9[1] + 8);
            c.ctx().getInput().pressKey(keyB);
            c.ticks(2);
            hoverGui(c, h0[0] + 8, h0[1] + 8);
            c.ctx().getInput().pressKey(keyB);
            c.ticks(2);
            Map<?, ?> after1 = c.onClient(mc -> new LinkedHashMap<>((Map<?, ?>) Mod.call(Mod.cfg(SBC), "getBinds")));
            // 2: key on slot 10, LEFT CLICK on slot 11 (must not bind), then key on hotbar 1.
            int[] s10 = invSlotPos(c, 10);
            int[] s11 = invSlotPos(c, 11);
            int[] h1 = invSlotPos(c, 1);
            hoverGui(c, s10[0] + 8, s10[1] + 8);
            c.ctx().getInput().pressKey(keyB);
            c.ticks(2);
            hoverGui(c, s11[0] + 8, s11[1] + 8);
            c.ctx().getInput().pressMouse(0);
            c.ticks(2);
            Map<?, ?> afterClick = c.onClient(mc -> new LinkedHashMap<>((Map<?, ?>) Mod.call(Mod.cfg(SBC), "getBinds")));
            hoverGui(c, h1[0] + 8, h1[1] + 8);
            c.ctx().getInput().pressKey(keyB);
            c.ticks(2);
            Map<?, ?> after2 = c.onClient(mc -> new LinkedHashMap<>((Map<?, ?>) Mod.call(Mod.cfg(SBC), "getBinds")));
            // 3: two non-hotbar slots: no bind.
            int[] s12 = invSlotPos(c, 12);
            int[] s13 = invSlotPos(c, 13);
            hoverGui(c, s12[0] + 8, s12[1] + 8);
            c.ctx().getInput().pressKey(keyB);
            c.ticks(2);
            hoverGui(c, s13[0] + 8, s13[1] + 8);
            c.ctx().getInput().pressKey(keyB);
            c.ticks(2);
            Map<?, ?> after3 = c.onClient(mc -> new LinkedHashMap<>((Map<?, ?>) Mod.call(Mod.cfg(SBC), "getBinds")));
            c.note("binds after key/key 9->hotbar0: " + after1 + "; after key on 10 + click on 11: " + afterClick
                    + "; after key on hotbar1: " + after2 + "; after key on 12 and 13: " + after3);
            int m9 = s9[2];
            int m36 = h0[2];
            int m10 = s10[2];
            int m11 = s11[2];
            int m37 = h1[2];
            c.check(Integer.valueOf(m36).equals(after1.get(m9)) && Integer.valueOf(m9).equals(after1.get(m36)),
                    "two presses of the key did not bind " + m9 + "<->" + m36 + ": " + after1);
            c.check(!afterClick.containsKey(m10) && !afterClick.containsKey(m11),
                    "a left click finished the bind: " + afterClick);
            c.check(Integer.valueOf(m37).equals(after2.get(m10)), "the second key press did not bind " + m10 + "<->" + m37
                    + ": " + after2);
            c.check(!after3.containsKey(s12[2]) && !after3.containsKey(s13[2]), "an illegal pair was bound: " + after3);
        } finally {
            closeScreen(c);
            InvBatchCases.restoreConfig(c, SBC, p, old);
        }
    }

    // ==== 704 pet wheel ================================================================================================

    private static Object pet(String uuid, String name) {
        try {
            Constructor<?> k = Mod.cls("petwheel.PetEntry").getDeclaredConstructors()[0];
            k.setAccessible(true);
            return k.newInstance(uuid, name, "LEGENDARY", 100, null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Screen wheel(List<Object> pets, float scale) {
        try {
            Class<?> cls = Mod.cls("petwheel.PetWheelScreen");
            Class<?> mode = Mod.cls("petwheel.PetWheelConfig$InteractionMode");
            Constructor<?> k = cls.getConstructor(List.class, float.class, mode);
            return (Screen) k.newInstance(pets, scale, Mod.enumValue("petwheel.PetWheelConfig$InteractionMode", "PRESS_CLICK"));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    static void petWheel(UiCase c) throws Exception {
        Path p = InvBatchCases.configPath(c, PWC);
        byte[] old = InvBatchCases.read(p);
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            // The setting is gone from the config API and the tab.
            boolean setter = false;
            for (java.lang.reflect.Method m : Mod.cls(PWC).getDeclaredMethods()) {
                setter |= m.getName().equals("setSliceCount");
            }
            boolean constant = false;
            for (java.lang.reflect.Field f : Mod.cls(PWC).getDeclaredFields()) {
                constant |= f.getName().contains("SLICES");
            }
            c.check(!setter && !constant, "PetWheelConfig still has the slice setting (setter " + setter + ", constants " + constant + ")");
            List<String> labels = c.onClient(mc -> {
                Mod.call(Mod.cfg(PWC), "setEnabled", true);
                List<String> out = new ArrayList<>();
                try {
                    Object tab = Mod.cls("gui.tab.PetWheelTab").getConstructor().newInstance();
                    List<?> ws = (List<?>) Mod.call(tab, "buildWidgets", 0, 0, 300, (Runnable) () -> { });
                    for (Object w : ws) {
                        out.add(((AbstractWidget) w).getMessage().getString());
                    }
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
                return out;
            });
            c.note("Pet Wheel tab rows: " + labels);
            c.check(labels.stream().noneMatch(l -> l.startsWith("Slices")), "the tab still has a Slices row");
            c.check(labels.stream().noneMatch(l -> l.contains("on the wheel")), "the tab still has the pet count line");
            c.check(labels.stream().anyMatch(l -> l.startsWith("Scale")), "the tab lost its Scale slider: " + labels);

            // 14 pets lay out on one page.
            List<Object> many = new ArrayList<>();
            for (int i = 0; i < 14; i++) {
                many.add(pet("iu-pet-" + i, "Pet" + i));
            }
            int pages = c.onClient(mc -> (Integer) Mod.call(wheel(many, 1.0f), "pageCount"));
            c.check(pages == 1, "14 pets take " + pages + " pages");

            // The label grows with Scale: label pixels = (name shown) vs (name and level hidden), at 100% and 200%.
            HudEditorCases.setWindow(c, 1280, 720, 2);
            List<Object> one = List.of(pet("iu-pet-x", "Zebra"));
            int[] px = new int[2];
            float[] scales = {1.0f, 2.0f};
            for (int i = 0; i < 2; i++) {
                float sc = scales[i];
                c.onClient(mc -> {
                    Object cfg = Mod.cfg(PWC);
                    Mod.call(cfg, "setHideName", false);
                    Mod.call(cfg, "setHideLevel", false);
                    McCompat.setScreen(mc, wheel(one, sc));
                    return null;
                });
                c.ctx().getInput().setCursorPos(640, 360); // the dead zone: nothing hovered, label in plain white
                c.ticks(4);
                int[] rect = c.onClient(mc -> {
                    Screen s = McCompat.screen(mc);
                    int tile = (Integer) Mod.call(s, "currentTile");
                    Object lay = Mod.call(s, "layout", 1, tile);
                    float cx = (Float) Mod.call(lay, "centerX");
                    float cy = (Float) Mod.call(lay, "centerY");
                    float rad = (Float) Mod.call(lay, "radius");
                    // The wheel lays out on Auto Scale's canvas (factor f), so GUI = layout * f.
                    float f = (Float) Mod.staticCall("hud.AutoScale", "menuFactor");
                    float top = cy - rad + tile / 2f;
                    return new int[]{Math.round((cx - 80 * sc) * f), Math.round((top - 2) * f), Math.round((cx + 80 * sc) * f),
                            Math.round((top + 16 * sc) * f)};
                });
                int gsw = c.onClient(mc -> mc.getWindow().getGuiScale());
                BufferedImage shown = InvBatchCases.shot(c, "label-" + (int) (sc * 100));
                c.onClient(mc -> {
                    Object cfg = Mod.cfg(PWC);
                    Mod.call(cfg, "setHideName", true);
                    Mod.call(cfg, "setHideLevel", true);
                    McCompat.setScreen(mc, wheel(one, sc));
                    return null;
                });
                c.ticks(4);
                BufferedImage hidden = InvBatchCases.shot(c, "nolabel-" + (int) (sc * 100));
                px[i] = changedIn(shown, hidden, rect[0] * gsw, rect[1] * gsw, rect[2] * gsw, rect[3] * gsw);
            }
            double ratio = px[0] == 0 ? 0 : px[1] / (double) px[0];
            c.note(String.format(Locale.ROOT, "label pixels: %d at 100%%, %d at 200%% (ratio %.2f, text scaled 2x = 4x the pixels)",
                    px[0], px[1], ratio));
            c.check(px[0] > 40, "no label drawn at 100%: " + px[0]);
            // Not exactly 4: at 100% the glyphs land on a 1.5-pixel grid (Auto Scale 0.75 x GUI 2) and partly blur under
            // the 30-delta threshold; at 200% they are crisp 3-pixel blocks (measured 418 -> 2847 on the first run).
            c.check(ratio >= 3.0 && ratio <= 8.0, "the label did not scale with the wheel: ratio " + ratio);
        } finally {
            closeScreen(c);
            HudEditorCases.restoreWindow(c, window, oldGui);
            InvBatchCases.restoreConfig(c, PWC, p, old);
        }
    }

    // ==== 705 item protect: hovered star, drops to force ===============================================================

    static void protectHoverAndDrops(UiCase c) throws Exception {
        Path p = InvBatchCases.configPath(c, IPC);
        byte[] old = InvBatchCases.read(p);
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            InvBatchCases.survival(c);
            Map<Integer, ItemStack> items = new LinkedHashMap<>();
            items.put(0, InvBatchCases.sb(Items.DIAMOND_SWORD, "HYPERION", "iu-prot-1", 0));
            items.put(9, InvBatchCases.sb(Items.DIAMOND_SWORD, "HYPERION", "iu-prot-1", 0));
            InvBatchCases.inventory(c, items);
            c.onClient(mc -> {
                Object cfg = Mod.cfg(IPC);
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setProtectItemEnabled", true);
                Mod.call(cfg, "setLockInPlace", false);
                Mod.call(cfg, "setProtectedColor", 0xFF000000 | MAGENTA);
                Mod.call(cfg, "addProtectedKey", "iu-prot-1");
                Mod.call(cfg, "setPreventHotbarDropEnabled", true);
                Mod.call(cfg, "setConfirmToForce", true);
                return null;
            });
            String[] star = (String[]) Mod.field("itemprotect.ItemProtectFeature", "STAR");
            int cells = 0;
            for (String row : star) {
                cells += (int) row.chars().filter(ch -> ch == '#').count();
            }
            c.check(star.length <= 5 && star[0].length() <= 5, "the star is not small: " + star.length + "x" + star[0].length());

            HudEditorCases.setWindow(c, 1280, 720, 2);
            int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
            openInventory(c);
            int[] s9 = invSlotPos(c, 9);
            // Cursor mid-slot: the tooltip opens at cursor+12 (box from +9), right of the slot, clear of the star.
            hoverGui(c, s9[0] + 8, s9[1] + 8);
            Object hovered = c.onClient(mc -> Mod.call(McCompat.screen(mc), "killer560smod$getHoveredSlot"));
            BufferedImage img = InvBatchCases.shot(c, "hovered-protected");
            int x0 = s9[0] + 16 - star.length;
            int y0 = s9[1];
            int hit = 0;
            for (int r = 0; r < star.length; r++) {
                for (int col = 0; col < star[r].length(); col++) {
                    if (star[r].charAt(col) == '#' && pinkLoose(InvBatchCases.rgb(img, (x0 + col) * gs + gs / 2, (y0 + r) * gs + gs / 2))) {
                        hit++;
                    }
                }
            }
            c.note("hovered slot " + (hovered == null ? "none" : ((Slot) hovered).index) + ": " + hit + " of " + cells
                    + " star cells drawn in the star color");
            c.check(hovered != null && ((Slot) hovered).index == s9[2], "the cursor is not over the protected slot");
            c.check(hit >= cells - 1, "the star disappears on the hovered slot: " + hit + " of " + cells + " cells");
            closeScreen(c);

            // Drops To Force: 2, 3, 4 presses; clamped at both ends.
            c.onClient(mc -> {
                mc.player.getInventory().setSelectedSlot(0);
                return null;
            });
            c.ticks(2);
            List<String> runs = new ArrayList<>();
            boolean ok = true;
            for (int n = 2; n <= 4; n++) {
                int need = n;
                List<Boolean> blocks = c.onClient(mc -> {
                    Mod.call(Mod.cfg(IPC), "setForceDrops", need);
                    List<Boolean> out = new ArrayList<>();
                    for (int i = 0; i < need; i++) {
                        out.add((Boolean) Mod.staticCall("itemprotect.ItemProtect", "shouldBlockHotbarDrop"));
                    }
                    return out;
                });
                runs.add(n + ": " + blocks);
                for (int i = 0; i < need; i++) {
                    ok &= blocks.get(i) == (i < need - 1);
                }
            }
            int lo = c.onClient(mc -> {
                Mod.call(Mod.cfg(IPC), "setForceDrops", 1);
                return (Integer) Mod.call(Mod.cfg(IPC), "getForceDrops");
            });
            int hi = c.onClient(mc -> {
                Mod.call(Mod.cfg(IPC), "setForceDrops", 9);
                return (Integer) Mod.call(Mod.cfg(IPC), "getForceDrops");
            });
            List<String> rows = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                try {
                    Object tab = Mod.cls("gui.tab.ItemProtectTab").getConstructor().newInstance();
                    for (Object w : (List<?>) Mod.call(tab, "buildWidgets", 0, 0, 300, (Runnable) () -> { })) {
                        out.add(((AbstractWidget) w).getMessage().getString());
                    }
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
                return out;
            });
            c.note("drop presses (true = blocked) " + runs + "; clamp 1->" + lo + ", 9->" + hi + "; slider row "
                    + rows.stream().filter(r -> r.startsWith("Drops To Force")).toList());
            c.check(ok, "Drops To Force did not take exactly N presses: " + runs);
            c.check(lo == 2 && hi == 4, "Drops To Force not clamped to 2-4: " + lo + "/" + hi);
            c.check(rows.stream().anyMatch(r -> r.startsWith("Drops To Force")), "no Drops To Force slider: " + rows);
        } finally {
            closeScreen(c);
            HudEditorCases.restoreWindow(c, window, oldGui);
            InvBatchCases.restoreConfig(c, IPC, p, old);
            InvBatchCases.inventory(c, Map.of());
        }
    }

    // ==== 706 GIF / DVD hidden in containers ===========================================================================

    static void overlaysHidden(UiCase c) throws Exception {
        Path p = staticPath(c, "dvd.DvdConfig", "CONFIG_PATH");
        byte[] old = InvBatchCases.read(p);
        try {
            closeScreen(c);
            boolean openNone = c.onClient(mc -> (Boolean) Mod.staticCall("hud.GuiOverlays", "containerOpen"));
            String id = c.onClient(mc -> {
                Object e = Mod.call(Mod.staticCall("dvd.DvdConfig", "getInstance"), "addNew");
                Mod.staticCall("dvd.DvdFeature", "reload");
                return (String) Mod.field(e, "id");
            });
            c.ticks(10);
            float[] a = dvdPos(c, id);
            c.ticks(10);
            float[] b = dvdPos(c, id);
            openInventory(c);
            boolean openInv = c.onClient(mc -> (Boolean) Mod.staticCall("hud.GuiOverlays", "containerOpen"));
            c.ticks(3);
            float[] d = dvdPos(c, id);
            c.ticks(20);
            float[] e = dvdPos(c, id);
            closeScreen(c);
            c.ticks(10);
            float[] f = dvdPos(c, id);
            c.note(String.format(Locale.ROOT, "containerOpen: no screen %s, inventory %s; DVD box no screen (%.1f,%.1f)->(%.1f,%.1f), "
                    + "inventory open (%.1f,%.1f)->(%.1f,%.1f) over 20 ticks, closed again (%.1f,%.1f)", openNone, openInv,
                    a[0], a[1], b[0], b[1], d[0], d[1], e[0], e[1], f[0], f[1]));
            c.check(!openNone && openInv, "containerOpen() wrong: " + openNone + "/" + openInv);
            c.check(a[0] != b[0] || a[1] != b[1], "the DVD box never moved with no screen open - nothing was drawn");
            c.check(d[0] == e[0] && d[1] == e[1], "the DVD box kept being drawn with the inventory open");
            c.check(e[0] != f[0] || e[1] != f[1], "the DVD box did not come back after the inventory closed");
        } finally {
            closeScreen(c);
            writeBack(p, old);
            c.onClient(mc -> {
                Mod.staticCall("dvd.DvdConfig", "load");
                Mod.staticCall("dvd.DvdFeature", "reload");
                return null;
            });
        }
    }

    private static float[] dvdPos(UiCase c, String id) {
        return c.onClient(mc -> {
            Map<?, ?> rts = (Map<?, ?>) Mod.field("dvd.DvdFeature", "runtimes");
            for (Object rt : rts.values()) {
                Object entry = Mod.field(rt, "entry");
                if (id.equals(Mod.field(entry, "id"))) {
                    return new float[]{(Float) Mod.field(rt, "x"), (Float) Mod.field(rt, "y")};
                }
            }
            throw new AssertionError("no DVD runtime for " + id + " (" + rts.size() + " runtimes)");
        });
    }

    // ==== 707 storage search: Equipment Wardrobe =======================================================================

    static void equipmentWardrobeIndex(UiCase c) throws Exception {
        Path p = staticPath(c, "storagesearch.StorageSearchExtraCache", "CACHE_PATH");
        byte[] old = InvBatchCases.read(p);
        try {
            String key1 = c.onClient(mc -> (String) Mod.staticCall("storagesearch.StorageSearchFeature", "extraKeyForTitle", "Equipment Sets (1/2)"));
            String key2 = c.onClient(mc -> (String) Mod.staticCall("storagesearch.StorageSearchFeature", "extraKeyForTitle", "Equipment Sets (2/2)"));
            String wardrobe = c.onClient(mc -> (String) Mod.staticCall("storagesearch.StorageSearchFeature", "extraKeyForTitle", "Wardrobe (1/2)"));
            String forged = c.onClient(mc -> (String) Mod.staticCall("storagesearch.StorageSearchFeature", "extraKeyForTitle", "Equipment Sets (1/2) hi"));
            String label = c.onClient(mc -> (String) Mod.staticCall("storagesearch.StorageSearchIndex", "extraLabel", "eqwardrobe_2"));
            c.note("keys: " + key1 + " / " + key2 + " / wardrobe " + wardrobe + " / forged " + forged + "; label " + label);
            c.check(key1 != null && key1.endsWith("|eqwardrobe_1"), "Equipment Sets (1/2) is not captured: " + key1);
            c.check(key2 != null && key2.endsWith("|eqwardrobe_2"), "Equipment Sets (2/2) key " + key2);
            c.check(forged == null, "a longer title matched: " + forged);
            c.check("Equipment Wardrobe p2".equals(label), "label " + label);

            List<?> hits = c.onClient(mc -> {
                List<ItemStack> page = new ArrayList<>();
                for (int i = 0; i < 54; i++) {
                    page.add(ItemStack.EMPTY);
                }
                ItemStack necklace = new ItemStack(Items.PLAYER_HEAD);
                necklace.set(DataComponents.CUSTOM_NAME, Component.literal("Iu Moonglade Necklace"));
                page.set(10, necklace);
                Mod.call(Mod.staticCall("storagesearch.StorageSearchExtraCache", "getInstance"), "put", key1, page);
                Object index = Mod.staticCall("storagesearch.StorageSearchIndex", "build", false);
                return (List<?>) Mod.call(index, "filter", "moonglade", false, false);
            });
            String found = c.onClient(mc -> hits.isEmpty() ? "none" : Mod.call(hits.get(0), "type") + " @ " + Mod.call(hits.get(0), "location"));
            c.note("search 'moonglade' -> " + hits.size() + " hit(s): " + found);
            c.check(hits.size() == 1, "the Equipment Wardrobe item is not found: " + hits.size());
            c.check(found.startsWith("EQUIPMENT @ Equipment Wardrobe p1"), "hit reads " + found);
        } finally {
            writeBack(p, old);
            c.onClient(mc -> {
                Mod.staticCall("storagesearch.StorageSearchExtraCache", "load");
                return null;
            });
        }
    }

    // ==== 709-712 item browser =========================================================================================

    private interface Body {
        void run(int gs) throws Exception;
    }

    private static void withBrowser(UiCase c, Body body) throws Exception {
        Path isPath = InvBatchCases.configPath(c, ISC);
        Path ibPath = InvBatchCases.configPath(c, IBC);
        byte[] oldIs = InvBatchCases.read(isPath);
        byte[] oldIb = InvBatchCases.read(ibPath);
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        Object oldCatalog = seedCatalog(c, catalog());
        try {
            InvBatchCases.survival(c);
            InvBatchCases.inventory(c, Map.of());
            c.onClient(mc -> {
                Object ib = Mod.cfg(IBC);
                Mod.call(ib, "setEnabled", true);
                Mod.call(ib, "setColumns", 5);
                Mod.call(ib, "setScale", 1.0f);
                Mod.call(ib, "setHorizontal", true);
                Mod.call(ib, "setAlign", Mod.enumValue(IBC + "$HorizontalAlign", "RIGHT"));
                Mod.call(Mod.cfg(ISC), "setEnabled", true);
                Mod.staticCall(NEU, "put", "IU_TEST_BLADE", neuJson());
                Mod.staticCall(CRAFT, "close");
                return null;
            });
            HudEditorCases.setWindow(c, 1280, 720, 2);
            int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
            body.run(gs);
        } finally {
            c.onClient(mc -> {
                Mod.staticCall(CRAFT, "close");
                return null;
            });
            closeScreen(c);
            setQuery(c, "");
            HudEditorCases.restoreWindow(c, window, oldGui);
            restoreCatalog(c, oldCatalog);
            InvBatchCases.restoreConfig(c, ISC, isPath, oldIs);
            InvBatchCases.restoreConfig(c, IBC, ibPath, oldIb);
        }
    }

    /** {screenX, screenY, screenW, screenH} of the panel and the GUI centre of grid cell 0. */
    private static double[] panel(UiCase c) {
        return c.onClient(mc -> {
            Object layout = Mod.staticCall(IBF, "layout", McCompat.screen(mc), Mod.cfg(IBC));
            int x = (Integer) Mod.call(layout, "screenX");
            int y = (Integer) Mod.call(layout, "screenY");
            int w = (Integer) Mod.call(layout, "screenW");
            int h = (Integer) Mod.call(layout, "screenH");
            float sc = (Float) Mod.call(layout, "scale");
            int pad = (Integer) Mod.field(IBF, "PADDING");
            int head = (Integer) Mod.field(IBF, "HEADER_HEIGHT");
            return new double[]{x, y, w, h, x + (pad + 9) * sc, y + (head + 9) * sc, head};
        });
    }

    static void browserTooltip(UiCase c) throws Exception {
        withBrowser(c, gs -> {
            setQuery(c, "test blade");
            openInventory(c);
            double[] pl = panel(c);
            int[] skip = {(int) (pl[0] * gs), (int) (pl[1] * gs), (int) ((pl[0] + pl[2]) * gs), (int) ((pl[1] + pl[3]) * gs)};
            c.ctx().getInput().setCursorPos(2, 2);
            c.ticks(4);
            BufferedImage off = InvBatchCases.shot(c, "browser-no-hover");
            hoverGui(c, pl[4], pl[5]);
            c.ticks(3);
            BufferedImage on = InvBatchCases.shot(c, "browser-hover");
            int outside = changed(off, on, skip);
            int red = InvBatchCases.count(on, 0, 0, on.getWidth(), on.getHeight(), 0xFF5555)
                    - InvBatchCases.count(off, 0, 0, off.getWidth(), off.getHeight(), 0xFF5555);
            c.note("hovering the Test Blade cell at GUI (" + (int) pl[4] + "," + (int) pl[5] + "): " + outside
                    + " px changed outside the panel; +" + red + " px of the lore's red (\u00a7c) text");
            c.check(outside > 2000, "no tooltip appeared outside the panel: " + outside + " px changed");
            c.check(red > 10, "the tooltip is not the NEU lore (no \u00a7c text): " + red);
        });
    }

    static void browserMinions(UiCase c) throws Exception {
        withBrowser(c, gs -> {
            List<String> collapsed = c.onClient(mc -> {
                List<?> all = (List<?>) Mod.staticCall(REPO, "getItems");
                List<?> out = (List<?>) Mod.staticCall(IBF, "collapseMinions", all, all);
                List<String> ids = new ArrayList<>();
                for (Object e : out) {
                    ids.add((String) Mod.call(e, "id"));
                }
                return ids;
            });
            c.note("catalog of 5 (3 Blaze Minion tiers) collapses to " + collapsed);
            List<String> handlers = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                for (java.lang.reflect.Method m : AbstractContainerScreen.class.getDeclaredMethods()) {
                    if (m.getName().contains("renderItemBrowser") || m.getName().contains("renderStorageOverlay")
                            || m.getName().contains("hideTooltipUnderPopup")) {
                        out.add(m.getName());
                    }
                }
                return out;
            });
            c.note("handlers merged into AbstractContainerScreen: " + handlers);
            c.check(collapsed.size() == 3 && collapsed.contains("BLAZE_GENERATOR_1") && !collapsed.contains("BLAZE_GENERATOR_2"),
                    "minion tiers not stacked: " + collapsed);
            setQuery(c, "blaze minion");
            openInventory(c);
            double[] pl = panel(c);
            hoverGui(c, pl[4], pl[5]);
            c.ctx().getInput().pressMouse(0);
            c.ticks(3);
            boolean picker = c.onClient(mc -> (Boolean) Mod.staticCall(CRAFT, "isTierPickerOpen"));
            List<?> tiers = c.onClient(mc -> (List<?>) Mod.staticCall(CRAFT, "openTierList"));
            InvBatchCases.shot(c, "minion-tiers");
            List<?> cells = c.onClient(mc -> new ArrayList<>((List<?>) Mod.field(CRAFT, "tierCells")));
            c.check(picker && tiers != null && tiers.size() == 3, "the tier picker did not open with 3 tiers: " + picker + " " + tiers);
            c.check(cells.size() == 3, "tier cells drawn: " + cells.size());
            int[] cell = (int[]) cells.get(2);
            hoverGui(c, cell[0] + cell[2] / 2.0, cell[1] + cell[3] / 2.0);
            c.ctx().getInput().pressMouse(0);
            c.ticks(3);
            String opened = c.onClient(mc -> {
                Object e = Mod.staticCall(CRAFT, "openEntry");
                return e == null ? null : (String) Mod.call(e, "id");
            });
            String label = c.onClient(mc -> (String) Mod.staticCall(IBF, "minionTierLabel", tiers.get(2)));
            c.note("clicked the Blaze Minion cell: tier picker with " + tiers.size() + " tiers; clicked tier " + label
                    + " -> craft view of " + opened);
            c.check("BLAZE_GENERATOR_3".equals(opened), "clicking tier III opened " + opened);
            c.check("III".equals(label), "tier label " + label);
        });
    }

    static void browserRecipe(UiCase c) throws Exception {
        withBrowser(c, gs -> {
            String parsed = c.onClient(mc -> {
                Object info = Mod.staticCall(NEU, "parse", neuJson());
                return Mod.call(info, "grid") + " " + Mod.call(info, "gridCounts") + " x" + Mod.call(info, "outputCount")
                        + " " + Mod.call(info, "otherRecipes") + " lore " + ((List<?>) Mod.call(info, "lore")).size();
            });
            c.note("NEU JSON parsed: " + parsed);
            c.check(parsed.startsWith("[IU_GEM, IU_GEM, null, null, STICK, null, null, STICK, null] [32, 32, 0, 0, 1, 0, 0, 1, 0] x1"),
                    "grid/counts: " + parsed);
            c.check(parsed.contains("Forge (1h): 4x Iu Gem, 1000x Coins"), "forge line: " + parsed);
            String stick = c.onClient(mc -> ((ItemStack) Mod.staticCall(CRAFT, "vanillaIcon", "STICK")).getItem().toString());
            String cocoa = c.onClient(mc -> ((ItemStack) Mod.staticCall(CRAFT, "vanillaIcon", "INK_SACK-3")).getItem().toString());
            c.note("vanilla ingredient icons: STICK -> " + stick + ", INK_SACK-3 -> " + cocoa);
            c.check(stick.equals("minecraft:stick") && cocoa.equals("minecraft:cocoa_beans"), "vanilla icons " + stick + " / " + cocoa);
            setQuery(c, "test blade");
            openInventory(c);
            double[] pl = panel(c);
            hoverGui(c, pl[4], pl[5]);
            c.ctx().getInput().pressMouse(0);
            c.ticks(3);
            String opened = c.onClient(mc -> {
                Object e = Mod.staticCall(CRAFT, "openEntry");
                return e == null ? null : (String) Mod.call(e, "id");
            });
            c.check("IU_TEST_BLADE".equals(opened), "left click opened " + opened);
            int[] bounds = c.onClient(mc -> (int[]) Mod.field(CRAFT, "panelBounds"));
            BufferedImage img = InvBatchCases.shot(c, "recipe-popup");
            // The grid: nine dark cells with brown outlines inside the popup; count the outline colour there.
            int brown = InvBatchCases.count(img, bounds[0] * gs, bounds[1] * gs, (bounds[0] + bounds[2]) * gs,
                    (bounds[1] + bounds[3]) * gs, 0x553311);
            c.note("popup at " + java.util.Arrays.toString(bounds) + ": " + brown + " px of grid outline color");
            c.check(brown > 1500, "the popup drew no crafting grid: " + brown);
        });
    }

    static void browserSortAndBar(UiCase c) throws Exception {
        Path ibPath = InvBatchCases.configPath(c, IBC);
        byte[] oldIb = InvBatchCases.read(ibPath);
        try {
            List<String> modes = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                for (Object o : Mod.cls(IBC + "$SortMode").getEnumConstants()) {
                    out.add(((Enum<?>) o).name());
                }
                return out;
            });
            String fresh = c.onClient(mc -> {
                try {
                    Files.deleteIfExists(ibPath);
                } catch (java.io.IOException e) {
                    throw new AssertionError(e);
                }
                Mod.staticCall(IBC, "load");
                return String.valueOf(Mod.call(Mod.cfg(IBC), "getSortMode"));
            });
            String legacy = c.onClient(mc -> {
                try {
                    Files.createDirectories(ibPath.getParent());
                    Files.writeString(ibPath, "{\"enabled\":true,\"sortMode\":\"CATALOG\"}");
                } catch (java.io.IOException e) {
                    throw new AssertionError(e);
                }
                Mod.staticCall(IBC, "load");
                return String.valueOf(Mod.call(Mod.cfg(IBC), "getSortMode"));
            });
            c.note("sort modes " + modes + "; fresh config " + fresh + "; a saved CATALOG loads as " + legacy);
            c.check(!modes.contains("CATALOG"), "the Default sort is still there: " + modes);
            c.check("VALUE_HIGH_LOW".equals(fresh), "default sort " + fresh);
            c.check("VALUE_HIGH_LOW".equals(legacy), "old CATALOG loads as " + legacy);
            List<String> rows = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                try {
                    Object tab = Mod.cls("gui.tab.ItemBrowserTab").getConstructor().newInstance();
                    for (Object w : (List<?>) Mod.call(tab, "buildWidgets", 0, 0, 300, (Runnable) () -> { })) {
                        out.add(((AbstractWidget) w).getMessage().getString());
                    }
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
                return out;
            });
            // The tab's three-line note about the Inventory Search bar (killer560, 2026-10-11) was removed on purpose in mod
            // 511e04c2 ("Item Browser: remove the note lines", 2026-10-18), so the check is now that it stays gone and the
            // tab still builds, its on/off switch first. The tandem itself (the bar shows with only the Item Browser on) is
            // still measured below.
            c.note("Item Browser tab rows: " + rows);
            c.check(!rows.isEmpty() && rows.get(0).startsWith("Item Browser"), "the tab's first row is not its switch: " + rows);
            c.check(rows.stream().noneMatch(r -> r.contains("Inventory Search bar")),
                    "the removed note about the search bar is back: " + rows);
        } finally {
            InvBatchCases.restoreConfig(c, IBC, ibPath, oldIb);
        }
        withBrowser(c, gs -> {
            c.onClient(mc -> {
                Mod.call(Mod.cfg(ISC), "setEnabled", false); // only the Item Browser on
                return null;
            });
            setQuery(c, "");
            openInventory(c);
            int[] box = c.onClient(mc -> (int[]) Mod.field(ISF, "lastBoxBounds"));
            int[] size = c.onClient(mc -> new int[]{McCompat.screen(mc).width, McCompat.screen(mc).height});
            double[] pl = panel(c);
            c.note("screen " + size[0] + "x" + size[1] + "; search bar " + java.util.Arrays.toString(box) + "; panel header "
                    + (int) pl[6] + " px");
            c.check(box != null, "no search bar with only the Item Browser on");
            c.check(Math.abs(box[0] + box[2] / 2 - size[0] / 2) <= 1 && box[1] > size[1] / 2,
                    "the bar is not at the bottom middle: " + java.util.Arrays.toString(box));
            c.check(pl[6] <= 6, "the panel still reserves a header box: " + pl[6]);
        });
    }
}

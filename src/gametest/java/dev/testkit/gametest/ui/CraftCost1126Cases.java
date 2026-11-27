package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 1126-ui-craftcost (craftcost-hud-1126): a fake "Omega Recipe" container (6 rows) with the grid Alpha 3, Beta 4, Gamma 1,
 * Alpha 2, Delta 1 and Omega in the result slot. Fixed prices: Alpha sell 99.9 / buy 120 (buy order 100.0 each), Beta
 * sell 49.9 / buy 60 (50.0), Delta only a lowest BIN of 30, Gamma nothing. Worked out by hand: Alpha 5x500, Beta 4x200,
 * Delta 30 BIN, Gamma not on Bazaar, order total 730+, instant buy 870+ (no "Makes" line since craftcost-layout-1126, which
 * also checks full long names, one cost column and the /bz and /ahs clicks on the wire). Checks a fresh config is OFF and
 * draws nothing, the panel text, no recompute without a slot change, a change recomputing once, a non-recipe title showing
 * nothing and the panel gone after closing. The feature is in both builds, so the same case passes on a legit jar.
 */
final class CraftCost1126Cases {

    private static final String CFG = "craftcost.CraftCostConfig";
    private static final String CC = "craftcost.CraftCost";

    private CraftCost1126Cases() {
    }

    private static ItemStack item(String id, String name, int count) {
        ItemStack s = new ItemStack(Items.PLAYER_HEAD, count);
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return s;
    }

    /** The recipe view as Hypixel lays it out (assumed: crafting table in 23, panes around the arrow). */
    private static void open(UiCase c, String title) {
        open(c, title, 0);
    }

    /** mode 0 recipe view, 1 the same items with no marker and no panes (an ordinary container), 2 a "x" Recipes search list. */
    private static void open(UiCase c, String title, int mode) {
        c.onClient(mc -> {
            SimpleContainer box = new SimpleContainer(54);
            if (mode == 2) {
                for (int i : new int[]{10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25}) {
                    box.setItem(i, item("ALPHA", "Alpha", 1));
                }
            } else {
                box.setItem(10, item("ALPHA", "Alpha", 3));
                box.setItem(11, item("BETA", "Beta", 4));
                box.setItem(12, item("GAMMA", "Gamma", 1));
                box.setItem(19, item("ALPHA", "Alpha", 2));
                box.setItem(20, item("DELTA", "Delta", 1));
                box.setItem(25, item("OMEGA", "Omega", 1));
                if (mode == 0) {
                    box.setItem(23, new ItemStack(Items.CRAFTING_TABLE));
                    for (int i : new int[]{14, 15, 16, 22, 24}) {
                        box.setItem(i, new ItemStack(Items.STICK));
                    }
                }
            }
            McCompat.setScreen(mc, new ContainerScreen(ChestMenu.sixRows(0, mc.player.getInventory(), box),
                    mc.player.getInventory(), Component.literal(title)));
            return null;
        });
        c.ticks(6);
    }

    private static void close(UiCase c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        c.ticks(2);
    }

    @SuppressWarnings("unchecked")
    private static List<String> lines(UiCase c) {
        return ((List<String>) c.onClient(mc -> Mod.staticCall(CC, "linesForTest"))).stream()
                .map(l -> l.replaceAll("§.", "")).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<int[]> rows(UiCase c) {
        return (List<int[]>) c.onClient(mc -> Mod.staticCall(CC, "rowsForTest"));
    }

    private static boolean click(UiCase c, double x, double y) {
        return (Boolean) c.onClient(mc -> Mod.staticCall(CC, "clickForTest", x, y));
    }

    /** The Terminator recipe: full long names, one cost column, no Makes line, and the click commands on the wire. */
    private static void layout(UiCase c, Class<?> price) throws Exception {
        R.setStatic(price, "bazaarSell", Map.of("TEP", 3999.9, "BGF", 999.9));
        R.setStatic(price, "bazaarBuy", Map.of("TEP", 4100.0, "BGF", 1100.0));
        R.setStatic(price, "lowestBin", Map.of("JCORE", 112_000_000.0));
        c.onClient(mc -> {
            SimpleContainer box = new SimpleContainer(54);
            box.setItem(10, item("TEP", "Tessellated Ender Pearl", 8));
            box.setItem(11, item("BGF", "Braided Griffin Feather", 1));
            box.setItem(12, item("JCORE", "Judgement Core", 1));
            box.setItem(25, item("TERMINATOR", "Terminator", 1));
            box.setItem(23, new ItemStack(Items.CRAFTING_TABLE));
            for (int i : new int[]{14, 15, 16, 22, 24}) {
                box.setItem(i, new ItemStack(Items.STICK));
            }
            McCompat.setScreen(mc, new ContainerScreen(ChestMenu.sixRows(0, mc.player.getInventory(), box),
                    mc.player.getInventory(), Component.literal("Terminator")));
            return null;
        });
        c.ticks(8);
        List<String> got = lines(c);
        c.note("terminator lines: " + got);
        c.check(got.stream().anyMatch(l -> l.contains("8x Tessellated Ender Pearl")), "Tessellated Ender Pearl not in full: " + got);
        c.check(got.stream().anyMatch(l -> l.contains("1x Braided Griffin Feather")), "Braided Griffin Feather not in full: " + got);
        c.check(got.stream().noneMatch(l -> l.contains("Makes") || l.contains("...")), "a Makes line or a cut name: " + got);
        List<int[]> rs = rows(c);
        c.check(rs.size() == got.size() && rs.size() == 5, "row count " + rs.size() + " vs " + got.size());
        int end = rs.get(0)[0];
        c.check(rs.stream().allMatch(r -> r[0] == end), "cost texts do not end at one x: "
                + rs.stream().map(r -> r[0]).toList());
        int nameX = rs.get(1)[2];
        c.check(rs.stream().skip(1).limit(3).allMatch(r -> r[2] == nameX), "ingredient names do not start at one x");
        c.check(rs.get(1)[3] == 1 && rs.get(3)[3] == 1 && rs.get(0)[3] == 0, "which rows click is wrong");
        // Most expensive first: Judgement Core 112M, 8x Tessellated Ender Pearl 32k, Braided Griffin Feather 1k.
        c.check(got.get(1).contains("Judgement Core") && got.get(2).contains("Tessellated Ender Pearl")
                && got.get(3).contains("Braided Griffin Feather"), "rows are not ordered by total cost, highest first: " + got);

        List<String> sent = new CopyOnWriteArrayList<>();
        java.util.function.Consumer<net.minecraft.network.protocol.Packet<?>> before = dev.testkit.harness.PacketTrace.tap;
        dev.testkit.harness.PacketTrace.tap = p -> {
            if (p instanceof ServerboundChatCommandPacket cmd) {
                sent.add(cmd.command());
            }
        };
        try {
            c.check(click(c, rs.get(2)[2] + 2, rs.get(2)[1]), "a click on the Bazaar row was not taken");
            c.ticks(4);
            c.check(sent.equals(List.of("bz Tessellated Ender Pearl")), "Bazaar row sent " + sent);
            sent.clear();
            c.check(click(c, rs.get(1)[2] + 2, rs.get(1)[1]), "a click on the BIN row was not taken");
            c.ticks(4);
            c.check(sent.equals(List.of("ahs Judgement Core")), "BIN row sent " + sent);
            sent.clear();
            c.check(!click(c, rs.get(0)[2] + 2, rs.get(0)[1]), "the title row was taken as a click");
            c.check(!click(c, rs.get(0)[2] - 40, rs.get(2)[1]), "a click left of the panel was taken");
            c.ticks(4);
            c.check(sent.isEmpty(), "a click outside the rows sent " + sent);
        } finally {
            dev.testkit.harness.PacketTrace.tap = before;
        }
        try {
            java.nio.file.Path shot = c.ctx().takeScreenshot("craftcost-layout-1126");
            java.nio.file.Files.copy(shot, java.nio.file.Path.of("C:/Users/Hunter/killer560s-mod-logs/craftcost-layout-1126.png"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            c.note("no screenshot: " + e);
        }
        close(c);
    }

    private static ItemStack supercraft(String name, String... lore) {
        ItemStack s = new ItemStack(Items.PLAYER_HEAD);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        s.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(
                java.util.Arrays.stream(lore).<Component>map(Component::literal).toList()));
        return s;
    }

    /** Puts the cursor over container slot {@code slot} (32 is the Supercraft item) or, below 0, far off every slot, then lets a frame draw. */
    private static void pointer(UiCase c, int slot) {
        boolean over = slot >= 0;
        double[] g = c.onClient(mc -> {
            ContainerScreen sc = (ContainerScreen) McCompat.screen(mc);
            net.minecraft.world.inventory.Slot sl = sc.getMenu().slots.get(Math.max(slot, 0));
            double x = over ? (sc.width - 176) / 2 + sl.x + 8 : 2, y = over ? (sc.height - 222) / 2 + sl.y + 8 : 2;
            return new double[]{x * mc.getWindow().getScreenWidth() / (double) mc.getWindow().getGuiScaledWidth(),
                    y * mc.getWindow().getScreenHeight() / (double) mc.getWindow().getGuiScaledHeight()};
        });
        c.ctx().getInput().setCursorPos(g[0], g[1]);
        c.ticks(6);
    }

    private static void setSuper(UiCase c, ItemStack s) {
        c.onClient(mc -> {
            ((ContainerScreen) McCompat.screen(mc)).getMenu().getSlot(32).set(s);
            return null;
        });
        c.ticks(4);
    }

    private static String fmt(UiCase c, long v) {
        return (String) c.onClient(mc -> Mod.staticCall("croesus.DungeonChestValuer", "formatCoins", v));
    }

    /** A parenthesized Supercraft amount such as "(256x)" or "(24.8M)"; the title's "(8 crafts)" is not one. */
    private static boolean amountParens(String l) {
        return l.matches(".*\\([0-9.,]+[kMBT]?x?\\).*");
    }

    /** Hover the Supercraft item: parenthesized N-craft amounts in rows, title and Instant Buy; none unhovered or malformed. */
    private static void supercraftCases(UiCase c, Class<?> price) throws Exception {
        R.setStatic(price, "bazaarSell", Map.of("TEP", 3999.9, "BGF", 999.9));
        R.setStatic(price, "bazaarBuy", Map.of("TEP", 4100.0, "BGF", 1100.0));
        c.onClient(mc -> {
            SimpleContainer box = new SimpleContainer(54);
            box.setItem(10, item("TEP", "Tessellated Ender Pearl", 4));
            box.setItem(11, item("BGF", "Braided Griffin Feather", 1));
            box.setItem(25, item("TERMINATOR", "Terminator", 1));
            box.setItem(23, new ItemStack(Items.CRAFTING_TABLE));
            for (int i : new int[]{14, 15, 16, 22, 24}) {
                box.setItem(i, new ItemStack(Items.STICK));
            }
            box.setItem(32, supercraft("Supercraft (8x crafts)", "✖ 0/300 Tessellated Ender Pearl", "✖ 0/8 Braided Griffin Feather", "",
                    "Ingredients are taken from your", "inventory, sacks, ender chests and", "backpacks.", "", "Right-Click to set amount!",
                    "Missing ingredients!"));
            McCompat.setScreen(mc, new ContainerScreen(ChestMenu.sixRows(0, mc.player.getInventory(), box),
                    mc.player.getInventory(), Component.literal("Terminator")));
            return null;
        });
        c.ticks(8);
        pointer(c, -1);
        List<String> plain = lines(c);
        c.note("unhovered: " + plain);
        c.check(plain.size() == 4 && plain.stream().noneMatch(CraftCost1126Cases::amountParens), "parentheses with nothing hovered: " + plain);
        pointer(c, 32);
        List<String> got = lines(c);
        c.note("hovered: " + got);
        String qa = "4x (300x) Tessellated Ender Pearl  " + fmt(c, 16000) + " (" + fmt(c, 1200000) + ")";
        c.check(got.size() == 4 && got.get(1).equals(qa), "hovered row: " + got);
        c.check(got.get(2).equals("1x (8x) Braided Griffin Feather  " + fmt(c, 1000) + " (" + fmt(c, 8000) + ")"), "second row: " + got);
        c.check(got.get(0).equals("Craft Cost  " + fmt(c, 17000) + " (" + fmt(c, 1208000) + ")"), "title total: " + got);
        c.check(got.get(3).equals("Instant Buy  " + fmt(c, 4 * 4100 + 1100) + " (" + fmt(c, 300 * 4100 + 8 * 1100) + ")"), "instant buy: " + got);
        try {
            java.nio.file.Path shot = c.ctx().takeScreenshot("craftcost-supercraft-1126");
            java.nio.file.Files.copy(shot, java.nio.file.Path.of("C:/Users/Hunter/killer560s-mod-logs/craftcost-supercraft-1126.png"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            c.note("no screenshot: " + e);
        }
        // He sets his own amount (right click): a Supercraft N that differs from the last one is the panel's amount, hovered or not.
        setSuper(c, supercraft("Supercraft", "\u00a77(2x crafts)", "0/1,024 Tessellated Ender Pearl"));
        got = lines(c);
        c.note("custom amount 2: " + got);
        c.check(got.size() == 5 && got.get(0).equals("Craft Cost (2 crafts)  " + fmt(c, 34000) + " (" + fmt(c, 4098000) + ")")
                && got.get(1).equals("8x (1024x) Tessellated Ender Pearl  " + fmt(c, 32000) + " (" + fmt(c, 4096000) + ")")
                && got.get(2).equals("2x (2x) Braided Griffin Feather  " + fmt(c, 2000) + " (" + fmt(c, 2000) + ")")
                && got.get(4).equals("Reset to This Recipe"), "live custom amount: " + got);
        pointer(c, -1);
        got = lines(c);
        c.check(got.get(0).startsWith("Craft Cost (2 crafts)") && got.get(1).equals("8x Tessellated Ender Pearl  " + fmt(c, 32000)),
                "the custom amount did not hold when not hovered: " + got);
        // The Reset line: taken by the panel, no packet of any kind.
        List<String> sent = new CopyOnWriteArrayList<>();
        java.util.function.Consumer<net.minecraft.network.protocol.Packet<?>> before = dev.testkit.harness.PacketTrace.tap;
        dev.testkit.harness.PacketTrace.tap = p -> {
            if (p instanceof ServerboundChatCommandPacket || p instanceof net.minecraft.network.protocol.game.ServerboundContainerClickPacket) {
                sent.add(p.getClass().getSimpleName());
            }
        };
        try {
            List<int[]> rs = rows(c);
            c.check(click(c, rs.get(4)[2] + 2, rs.get(4)[1]), "a click on the Reset line was not taken");
            c.ticks(4);
            c.check(sent.isEmpty(), "the Reset click sent " + sent);
        } finally {
            dev.testkit.harness.PacketTrace.tap = before;
        }
        got = lines(c);
        c.check(got.size() == 4 && got.get(0).equals("Craft Cost  " + fmt(c, 17000)) && got.get(1).equals("4x Tessellated Ender Pearl  " + fmt(c, 16000)),
                "after Reset: " + got);
        // Malformed: no amount, then no ingredient lines.
        setSuper(c, supercraft("Supercraft", "0/32 Tessellated Ender Pearl"));
        got = lines(c);
        c.check(got.size() == 4 && got.stream().noneMatch(CraftCost1126Cases::amountParens), "no-amount lore still showed parentheses: " + got);
        setSuper(c, supercraft("Supercraft (8x crafts)", "Missing ingredients!"));
        got = lines(c);
        c.check(got.size() == 4 && got.stream().noneMatch(CraftCost1126Cases::amountParens), "no-ingredient lore still showed parentheses: " + got);
        pointer(c, 32);
        setSuper(c, supercraft("Supercraft (8x crafts)", "0/32 Tessellated Ender Pearl"));
        c.check(lines(c).get(1).contains("(32x)"), "a good lore after a bad one did not show: " + lines(c));
        pointer(c, -1);
        got = lines(c);
        c.check(got.stream().noneMatch(CraftCost1126Cases::amountParens) && got.get(0).startsWith("Craft Cost (8 crafts)"),
                "unhovered after a changed N: " + got);
        close(c);
    }

    /** The Tessellated Ender Pearl recipe menu: 32 Lapis Block and 80 Absolute Pearl, the result {@code yield} pearls. */
    private static void openChild(UiCase c, int yield) {
        c.onClient(mc -> {
            SimpleContainer box = new SimpleContainer(54);
            box.setItem(10, item("ELB", "Enchanted Lapis Lazuli Block", 32));
            box.setItem(11, item("AEP", "Absolute Ender Pearl", 40));
            box.setItem(25, item("TEP", "Tessellated Ender Pearl", yield));
            box.setItem(23, new ItemStack(Items.CRAFTING_TABLE));
            for (int i : new int[]{14, 15, 16, 22, 24}) {
                box.setItem(i, new ItemStack(Items.STICK));
            }
            McCompat.setScreen(mc, new ContainerScreen(ChestMenu.sixRows(0, mc.player.getInventory(), box),
                    mc.player.getInventory(), Component.literal("Tessellated Ender Pearl")));
            return null;
        });
        c.ticks(8);
    }

    /** Click an ingredient of the Terminator recipe, land in its own recipe menu: the panel counts the crafts the parent needed. */
    private static void carryCases(UiCase c, Class<?> price) throws Exception {
        R.setStatic(price, "bazaarSell", Map.of("TEP", 3999.9, "BGF", 999.9, "ELB", 95999.9, "AEP", 11999.9));
        R.setStatic(price, "bazaarBuy", Map.of("TEP", 4100.0, "BGF", 1100.0, "ELB", 100000.0, "AEP", 12500.0));
        c.onClient(mc -> {
            SimpleContainer box = new SimpleContainer(54);
            box.setItem(10, item("TEP", "Tessellated Ender Pearl", 8));
            box.setItem(11, item("BGF", "Braided Griffin Feather", 1));
            box.setItem(25, item("TERMINATOR", "Terminator", 1));
            box.setItem(23, new ItemStack(Items.CRAFTING_TABLE));
            for (int i : new int[]{14, 15, 16, 22, 24}) {
                box.setItem(i, new ItemStack(Items.STICK));
            }
            McCompat.setScreen(mc, new ContainerScreen(ChestMenu.sixRows(0, mc.player.getInventory(), box),
                    mc.player.getInventory(), Component.literal("Terminator")));
            return null;
        });
        c.ticks(8);
        pointer(c, 10);
        c.onClient(mc -> Mod.staticCall(CC, "noteClickForTest"));
        openChild(c, 1);
        List<String> got = lines(c);
        c.note("child after a click (8 needed): " + got);
        c.check(got.size() == 5 && got.get(0).equals("Craft Cost (8 crafts)  " + fmt(c, 28416000))
                && got.get(1).equals("256x Enchanted Lapis Lazuli Block  " + fmt(c, 24576000))
                && got.get(2).equals("320x Absolute Ender Pearl  " + fmt(c, 3840000))
                && got.get(3).equals("Instant Buy  " + fmt(c, 29600000)) && got.get(4).equals("Reset to This Recipe"),
                "the carried amount: " + got);
        List<String> sent = new CopyOnWriteArrayList<>();
        java.util.function.Consumer<net.minecraft.network.protocol.Packet<?>> before = dev.testkit.harness.PacketTrace.tap;
        dev.testkit.harness.PacketTrace.tap = p -> {
            if (p instanceof ServerboundChatCommandPacket || p instanceof net.minecraft.network.protocol.game.ServerboundContainerClickPacket) {
                sent.add(p.getClass().getSimpleName());
            }
        };
        try {
            List<int[]> rs = rows(c);
            c.check(click(c, rs.get(4)[2] + 2, rs.get(4)[1]), "a click on the Reset line was not taken");
            c.ticks(4);
            c.check(sent.isEmpty(), "the Reset click sent " + sent);
        } finally {
            dev.testkit.harness.PacketTrace.tap = before;
        }
        got = lines(c);
        c.check(got.size() == 4 && got.get(0).equals("Craft Cost  " + fmt(c, 3552000)) && got.get(1).equals("32x Enchanted Lapis Lazuli Block  " + fmt(c, 3072000)),
                "after Reset the recipe is not one craft: " + got);
        close(c);
        // The same recipe opened with no ingredient click: one craft.
        openChild(c, 1);
        got = lines(c);
        c.check(got.size() == 4 && got.get(0).equals("Craft Cost  " + fmt(c, 3552000)), "a directly opened recipe is not one craft: " + got);
        close(c);
        // A sub-recipe that yields 3 per craft: ceil(8 / 3) = 3 crafts.
        c.onClient(mc -> {
            SimpleContainer box = new SimpleContainer(54);
            box.setItem(10, item("TEP", "Tessellated Ender Pearl", 8));
            box.setItem(25, item("TERMINATOR", "Terminator", 1));
            box.setItem(23, new ItemStack(Items.CRAFTING_TABLE));
            for (int i : new int[]{14, 15, 16, 22, 24}) {
                box.setItem(i, new ItemStack(Items.STICK));
            }
            McCompat.setScreen(mc, new ContainerScreen(ChestMenu.sixRows(0, mc.player.getInventory(), box),
                    mc.player.getInventory(), Component.literal("Terminator")));
            return null;
        });
        c.ticks(8);
        pointer(c, 10);
        c.onClient(mc -> Mod.staticCall(CC, "noteClickForTest"));
        openChild(c, 3);
        got = lines(c);
        c.check(got.get(0).startsWith("Craft Cost (3 crafts)") && got.get(1).startsWith("96x Enchanted Lapis Lazuli Block"),
                "a yield of 3 needs 3 crafts for 8: " + got);
        close(c);
    }

    /** A Supercraft ingredient line as Hypixel sends it: reset, one space, red "x 0/N ", reset, the name in its rarity color. */
    private static Component need(int total, String name, net.minecraft.ChatFormatting color) {
        return Component.empty().withStyle(net.minecraft.ChatFormatting.RESET).append(Component.literal(" "))
                .append(Component.empty().withStyle(net.minecraft.ChatFormatting.RESET)
                        .append(Component.literal("\u2716 0/" + total + " ").withStyle(net.minecraft.ChatFormatting.RED)))
                .append(Component.empty().withStyle(net.minecraft.ChatFormatting.RESET).append(Component.literal(name).withStyle(color)));
    }

    /**
     * The real Tessellated Ender Pearl recipe menu, from the logger's packets of 11-26 (win=6): the result and the Supercraft item
     * (slot 32, "Supercraft (8x crafts)", lore with the ingredient lines above, no custom data) arrive BEFORE the content packet,
     * which then fills the grid (Lapis Block 4 x 8, Absolute Pearl 5 x 16) and the panes. Hover slot 32: the parenthesized amounts.
     */
    static void realHover(UiCase c) throws Exception {
        Object cfg = Mod.cfg(CFG);
        boolean wasOn = (Boolean) Mod.call(cfg, "isEnabled");
        Class<?> price = Mod.cls("pricetooltip.PriceData");
        try {
            Mod.staticCall("pricetooltip.PriceData", "markStartedForTest");
            Mod.staticCall("pricetooltip.PriceData", "clearForTest");
            R.setStatic(price, "bazaarSell", Map.of("ELB", 95999.9, "AEP", 11999.9));
            R.setStatic(price, "bazaarBuy", Map.of("ELB", 100000.0, "AEP", 12500.0));
            R.setStatic(price, "lowestBin", Map.of());
            Mod.set(CFG, "setEnabled", true);
            c.onClient(mc -> {
                SimpleContainer box = new SimpleContainer(54);
                box.setItem(25, item("TEP", "Tessellated Ender Pearl", 1));
                ItemStack sup = new ItemStack(Items.GOLDEN_PICKAXE);
                sup.set(DataComponents.CUSTOM_NAME, Component.literal("Supercraft").withStyle(net.minecraft.ChatFormatting.GREEN)
                        .append(Component.literal(" (8x crafts)").withStyle(net.minecraft.ChatFormatting.AQUA)));
                sup.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(List.of(
                        need(256, "Enchanted Lapis Lazuli Block", net.minecraft.ChatFormatting.BLUE),
                        need(640, "Absolute Ender Pearl", net.minecraft.ChatFormatting.BLUE), Component.empty(),
                        Component.literal("Ingredients are taken from your").withStyle(net.minecraft.ChatFormatting.GRAY),
                        Component.literal("inventory, sacks, ender chests and").withStyle(net.minecraft.ChatFormatting.GRAY),
                        Component.literal("backpacks.").withStyle(net.minecraft.ChatFormatting.GRAY), Component.empty(),
                        Component.literal("Right-Click to set amount!").withStyle(net.minecraft.ChatFormatting.AQUA),
                        Component.literal("Missing ingredients!").withStyle(net.minecraft.ChatFormatting.RED))));
                box.setItem(32, sup);
                McCompat.setScreen(mc, new ContainerScreen(ChestMenu.sixRows(0, mc.player.getInventory(), box),
                        mc.player.getInventory(), Component.literal("Tessellated Ender Pearl")));
                return null;
            });
            c.ticks(3);
            // The content packet: grid, crafting table and panes.
            c.onClient(mc -> {
                net.minecraft.world.inventory.AbstractContainerMenu m = ((ContainerScreen) McCompat.screen(mc)).getMenu();
                for (int i : new int[]{10, 12, 28, 30}) {
                    m.getSlot(i).set(item("ELB", "Enchanted Lapis Lazuli Block", 8));
                }
                for (int i : new int[]{11, 19, 20, 21, 29}) {
                    m.getSlot(i).set(item("AEP", "Absolute Ender Pearl", 16));
                }
                m.getSlot(23).set(new ItemStack(Items.CRAFTING_TABLE));
                for (int i : new int[]{14, 15, 16, 22, 24}) {
                    m.getSlot(i).set(new ItemStack(Items.STICK));
                }
                return null;
            });
            c.ticks(4);
            pointer(c, -1);
            List<String> plain = lines(c);
            c.note("real menu, not hovered: " + plain);
            c.check(plain.size() == 4 && plain.stream().noneMatch(CraftCost1126Cases::amountParens), "real menu unhovered: " + plain);
            pointer(c, 32);
            List<String> got = lines(c);
            c.note("real menu, Supercraft hovered: " + got);
            c.check(got.size() == 4
                    && got.get(0).equals("Craft Cost  " + fmt(c, 4032000) + " (" + fmt(c, 32256000) + ")")
                    && got.get(1).equals("32x (256x) Enchanted Lapis Lazuli Block  " + fmt(c, 3072000) + " (" + fmt(c, 24576000) + ")")
                    && got.get(2).equals("80x (640x) Absolute Ender Pearl  " + fmt(c, 960000) + " (" + fmt(c, 7680000) + ")")
                    && got.get(3).equals("Instant Buy  " + fmt(c, 4200000) + " (" + fmt(c, 33600000) + ")"), "real menu hovered: " + got);
            close(c);
        } finally {
            Mod.set(CFG, "setEnabled", wasOn);
            Mod.staticCall("pricetooltip.PriceData", "clearForTest");
        }
    }

    static void craftCost(UiCase c) throws Exception {
        Object cfg = Mod.cfg(CFG);
        boolean wasOn = (Boolean) Mod.call(cfg, "isEnabled");
        Class<?> price = Mod.cls("pricetooltip.PriceData");
        try {
            c.check(!wasOn, "a fresh config has Craft Cost ON; a feature never defaults on");
            Mod.staticCall("pricetooltip.PriceData", "markStartedForTest");
            Mod.staticCall("pricetooltip.PriceData", "clearForTest");
            R.setStatic(price, "bazaarSell", Map.of("ALPHA", 99.9, "BETA", 49.9));
            R.setStatic(price, "bazaarBuy", Map.of("ALPHA", 120.0, "BETA", 60.0));
            R.setStatic(price, "lowestBin", Map.of("DELTA", 30.0));

            Mod.set(CFG, "setEnabled", false);
            open(c, "Omega Recipe");
            c.check(lines(c).isEmpty(), "the panel was built with the switch off: " + lines(c));
            close(c);

            Mod.set(CFG, "setEnabled", true);
            open(c, "Chest", 1);
            c.check(lines(c).isEmpty(), "the panel showed on a plain Chest: " + lines(c));
            close(c);
            open(c, "Terminator", 1);
            c.check(lines(c).isEmpty(), "the panel showed on an item-titled container with no recipe layout: " + lines(c));
            close(c);
            open(c, "\"term\" Recipes", 2);
            c.check(lines(c).isEmpty(), "the panel showed on the recipe search list: " + lines(c));
            close(c);

            open(c, "Terminator");
            c.check(lines(c).size() == 6 && lines(c).get(0).equals("Craft Cost  730+"),
                    "the recipe view titled by its item showed no panel: " + lines(c));
            close(c);

            open(c, "Omega Recipe");
            List<String> got = lines(c);
            c.check(!got.isEmpty(), "the panel never built on the recipe menu - the feature never acted");
            c.note("lines: " + got);
            c.check(got.equals(List.of("Craft Cost  730+", "5x Alpha  500", "4x Beta  200", "1x Delta  30 BIN",
                    "1x Gamma  not on Bazaar", "Instant Buy  870+")), "panel text (no Makes line): " + got);

            int n0 = (Integer) c.onClient(mc -> Mod.staticCall(CC, "computesForTest"));
            c.ticks(20);
            int n1 = (Integer) c.onClient(mc -> Mod.staticCall(CC, "computesForTest"));
            c.check(n1 == n0, "recomputed " + (n1 - n0) + " times with no slot change");
            c.onClient(mc -> {
                ((ContainerScreen) McCompat.screen(mc)).getMenu().getSlot(11).set(item("BETA", "Beta", 8));
                return null;
            });
            c.ticks(6);
            int n2 = (Integer) c.onClient(mc -> Mod.staticCall(CC, "computesForTest"));
            c.check(n2 == n1 + 1, "a slot change caused " + (n2 - n1) + " computes, expected 1");
            got = lines(c);
            c.check(got.size() > 2 && got.get(0).equals("Craft Cost  930+") && got.get(2).equals("8x Beta  400"),
                    "after the change: " + got);
            close(c);
            c.check(lines(c).isEmpty(), "the panel stayed after closing the menu: " + lines(c));

            open(c, "Craft Item");
            c.check(!lines(c).isEmpty(), "the Craft Item menu showed no panel");
            close(c);

            layout(c, price);
            supercraftCases(c, price);
            carryCases(c, price);
        } finally {
            Mod.set(CFG, "setEnabled", wasOn);
            Mod.staticCall("pricetooltip.PriceData", "clearForTest");
        }
    }
}

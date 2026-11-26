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

        List<String> sent = new CopyOnWriteArrayList<>();
        java.util.function.Consumer<net.minecraft.network.protocol.Packet<?>> before = dev.testkit.harness.PacketTrace.tap;
        dev.testkit.harness.PacketTrace.tap = p -> {
            if (p instanceof ServerboundChatCommandPacket cmd) {
                sent.add(cmd.command());
            }
        };
        try {
            c.check(click(c, rs.get(1)[2] + 2, rs.get(1)[1]), "a click on the Bazaar row was not taken");
            c.ticks(4);
            c.check(sent.equals(List.of("bz Tessellated Ender Pearl")), "Bazaar row sent " + sent);
            sent.clear();
            c.check(click(c, rs.get(3)[2] + 2, rs.get(3)[1]), "a click on the BIN row was not taken");
            c.ticks(4);
            c.check(sent.equals(List.of("ahs Judgement Core")), "BIN row sent " + sent);
            sent.clear();
            c.check(!click(c, rs.get(0)[2] + 2, rs.get(0)[1]), "the title row was taken as a click");
            c.check(!click(c, rs.get(0)[2] - 40, rs.get(1)[1]), "a click left of the panel was taken");
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
            c.check(got.equals(List.of("Craft Cost  730+", "5x Alpha  500", "4x Beta  200", "1x Gamma  not on Bazaar",
                    "1x Delta  30 BIN", "Instant Buy  870+")), "panel text (no Makes line): " + got);

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
        } finally {
            Mod.set(CFG, "setEnabled", wasOn);
            Mod.staticCall("pricetooltip.PriceData", "clearForTest");
        }
    }
}

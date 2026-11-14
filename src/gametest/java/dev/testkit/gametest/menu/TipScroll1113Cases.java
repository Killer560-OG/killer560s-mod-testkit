package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 1113-tipscroll-*: Scrollable Tooltips with the REAL mouse wheel (MouseHandler.onScroll, the same entry the window
 * callback uses) over a 60-line tooltip (killer560, 2026-11-13: "when tooltips get really long i cant really scroll them").
 * Each case asserts that the feature acted: its line offset moved AND the pixels of the tooltip changed.
 * container = a server menu, inventory = the player inventory screen, screen = any other screen's tooltip (the mod's
 * own menu, Profile Viewer), sideways = shift + wheel on a tooltip wider than the screen is covered by the offset check.
 */
final class TipScroll1113Cases {

    private static final String F = "tooltipscroll.TooltipScrollFeature";
    private static final String CFG = "tooltipscroll.TooltipScrollConfig";

    private TipScroll1113Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1113-tipscroll-container", TipScroll1113Cases::container);
        MenuSuite.test(s, "1113-tipscroll-inventory", TipScroll1113Cases::inventory);
        MenuSuite.test(s, "1113-tipscroll-screen", TipScroll1113Cases::plainScreen);
    }

    static List<String> lines() {
        List<String> l = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            l.add("§7Line number " + i + " of a very long lore");
        }
        return l;
    }

    static void container(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CFG, "Enabled", true);
            Map<Integer, JsonObject> slots = new LinkedHashMap<>();
            slots.put(21, FlipCroesusCases.item("minecraft:diamond", "§6Tall Item", lines().toArray(new String[0])));
            JsonObject m = FlipCroesusCases.menu("Tall Tips", 6, slots, new LinkedHashMap<>());
            MenuKit.show(c, m);
            MenuKit.awaitScreen(c, "Tall Tips", 100);
            c.ctx().waitTicks(10);
            CroesusUiCases.hover(c, 21);
            scrollAndCheck(c, "container", () -> c.ctx().runOnClient(mc -> {
                // Hypixel resends a menu's slots all the time: a NEW ItemStack object for the same item.
                var slot = ((net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) McCompat.screen(mc)).getMenu().slots.get(21);
                slot.set(slot.getItem().copy());
            }));
        } finally {
            MenuKit.reset(c);
        }
    }

    static void inventory(Session c) throws Exception {
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CFG, "Enabled", true);
            c.ctx().runOnClient(mc -> {
                ItemStack st = new ItemStack(Items.DIAMOND);
                List<Component> lore = new ArrayList<>();
                lines().forEach(x -> lore.add(Component.literal(x)));
                st.set(DataComponents.LORE, new ItemLore(lore));
                mc.player.getInventory().setItem(9, st);
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
            });
            c.ctx().waitTicks(10);
            c.ctx().runOnClient(mc -> {
                var s = (net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) McCompat.screen(mc);
                var sl = s.getMenu().slots.stream().filter(x -> x.getContainerSlot() == 9 && x.container == mc.player.getInventory()).findFirst().get();
                double sc = mc.getWindow().getGuiScale();
                int lx = ((Number) Mod.field(s, "leftPos")).intValue(), ty = ((Number) Mod.field(s, "topPos")).intValue();
                cursor = new double[]{(lx + sl.x + 8) * sc, (ty + sl.y + 8) * sc};
            });
            c.ctx().getInput().setCursorPos(cursor[0], cursor[1]);
            c.ctx().waitTicks(6);
            scrollAndCheck(c, "inventory", null);
        } finally {
            c.ctx().runOnClient(mc -> {
                mc.player.getInventory().setItem(9, ItemStack.EMPTY);
                McCompat.setScreen(mc, null);
            });
        }
    }

    static volatile double[] cursor;

    static final class Tall extends Screen {
        Tall() {
            super(Component.literal("tall"));
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
            super.extractRenderState(g, mx, my, delta);
            List<Component> l = new ArrayList<>();
            lines().forEach(x -> l.add(Component.literal(x)));
            g.setComponentTooltipForNextFrame(this.font, l, mx, my);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    static void plainScreen(Session c) throws Exception {
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CFG, "Enabled", true);
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, new Tall()));
            c.ctx().waitTicks(10);
            c.ctx().getInput().setCursorPos(300, 200);
            c.ctx().waitTicks(6);
            scrollAndCheck(c, "screen", null);
        } finally {
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
        }
    }

    private static void wheel(Session c, double dy) {
        c.ctx().runOnClient(mc -> mc.mouseHandler.onScroll(mc.getWindow().handle(), 0, dy));
        c.ctx().waitTicks(4);
    }

    private static int off(Session c) {
        return ((Number) c.onClient(mc -> Mod.field(F, "offset"))).intValue();
    }

    private static void scrollAndCheck(Session c, String label, Runnable resend) {
        boolean sawRender = c.onClient(mc -> (Boolean) Mod.staticCall(F, "renderHookSeen"));
        c.check(sawRender, label + ": the tooltip render hook never ran");
        BufferedImage before = CroesusUiCases.shot(c, label + "-before");
        int o0 = off(c);
        wheel(c, -1);
        wheel(c, -1);
        wheel(c, -1);
        int o3 = off(c);
        BufferedImage after = CroesusUiCases.shot(c, label + "-after");
        int diff = 0;
        for (int y = 0; y < before.getHeight(); y += 2) {
            for (int x = 0; x < before.getWidth(); x += 2) {
                if (CroesusUiCases.changed(before.getRGB(x, y), after.getRGB(x, y))) {
                    diff++;
                }
            }
        }
        if (resend != null) {
            resend.run();
            c.ctx().waitTicks(6);
            int kept = off(c);
            c.check(kept == o3, label + ": the server resending the slot reset the scroll (" + o3 + " -> " + kept + ")");
        }
        wheel(c, 1);
        wheel(c, 1);
        wheel(c, 1);
        int back = off(c);
        c.check(o0 == 0, label + ": offset " + o0 + " before any scroll");
        c.check(o3 > 0, label + ": three wheel notches down left the line offset at " + o3);
        c.check(diff > 200, label + ": the tooltip pixels did not change after scrolling (" + diff + " sampled)");
        c.check(back < o3, label + ": wheel up did not scroll back (" + o3 + " -> " + back + ")");
        c.note(label + ": offset 0 -> " + o3 + " -> " + back + ", " + diff + " sampled pixels changed");
    }
}

package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 1114-tipscroll-tall / -slots: Scrollable Tooltips on a tooltip TALLER than the screen (killer560, 2026-11-14: "if the thing
 * already exceeds the top of the screen i cannot move it down at all. it also inverts the scrolling direction in that case").
 * Vanilla's DefaultTooltipPositioner puts a too-tall tooltip with its BOTTOM at the screen bottom, so its first lines start
 * above the screen. The first line is red, the last light purple; every check reads those pixels off a screenshot:
 * (a) a tall tooltip starts with its first line fully on screen, the wheel brings the last line fully on screen and back;
 * (b) wheel up moves the content UP, as it moves a tooltip that fits (and Invert flips both alike);
 * (c) at each end the content is exactly at the screen edge (no stop while that side is still cut off, no blank past it);
 * (d) a tooltip that fits draws where vanilla puts it (both lines fully on screen).
 * Heights: just under the screen, 1.5x and 3x; hover at the top, middle and bottom; GUI scale 1 and 2 (the test window
 * is ~480 px tall, so 3 is capped to 2); a plain screen (tall), a server menu slot and an inventory slot (slots). Outside a
 * container a tooltip that fits keeps the wheel off by design (tipscroll-1113), so the fitting direction reference is a
 * menu slot.
 */
final class TipScroll1114Cases {

    private static final String CFG = "tooltipscroll.TooltipScrollConfig";
    private static final String FIRST = "§cFIRST LINE TOP";
    private static final String LAST = "§dLAST LINE END";

    private TipScroll1114Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1114-tipscroll-tall", TipScroll1114Cases::tall);
        MenuSuite.test(s, "1114-tipscroll-slots", TipScroll1114Cases::slots);
    }

    /** Lines for a tooltip of about {@code factor} screen heights (10 GUI px a line). */
    static List<String> lines(int guiHeight, double factor) {
        int n = Math.max(3, (int) (guiHeight * factor / 10.0));
        List<String> l = new ArrayList<>();
        l.add(FIRST);
        for (int i = 2; i < n; i++) {
            l.add("§7Body line " + i);
        }
        l.add(LAST);
        return l;
    }

    static volatile List<Component> tip = List.of();

    static final class Tall extends Screen {
        Tall() {
            super(Component.literal("tall1114"));
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
            super.extractRenderState(g, mx, my, delta);
            g.setComponentTooltipForNextFrame(this.font, tip, mx, my);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    static int guiScale(Session c, int scale) {
        return c.onClient(mc -> {
            int old = mc.options.guiScale().get();
            mc.options.guiScale().set(scale);
            mc.resizeGui();
            return old;
        });
    }

    static int guiHeight(Session c) {
        return c.onClient(mc -> mc.getWindow().getGuiScaledHeight());
    }

    // ---- tall: a plain screen's tooltip, every height x hover x scale, plus Invert --------------------------------------

    static void tall(Session c) throws Exception {
        int oldScale = guiScale(c, 2);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CFG, "Enabled", true).set(CFG, "LinesPerScroll", 3).set(CFG, "Invert", false);
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, new Tall()));
            c.ctx().waitTicks(5);
            int winH = c.onClient(mc -> mc.getWindow().getHeight());
            c.note("window height " + winH + " px");
            for (int scale : new int[]{1, 2}) {
                guiScale(c, scale);
                c.ctx().waitTicks(3);
                int gh = guiHeight(c);
                for (double factor : new double[]{0.85, 1.5, 3.0}) {
                    for (double at : new double[]{0.1, 0.5, 0.9}) {
                        List<Component> l = new ArrayList<>();
                        lines(gh, factor).forEach(x -> l.add(Component.literal(x)));
                        tip = l;
                        // a new tooltip: move away (no draw for 300 ms resets the feature) and come back
                        c.ctx().getInput().setCursorPos(5, 5);
                        tip = List.of();
                        c.ctx().waitTicks(8);
                        tip = l;
                        c.ctx().getInput().setCursorPos(300, winH * at);
                        c.ctx().waitTicks(4);
                        String label = "s" + scale + "-h" + factor + "-at" + at;
                        if (factor < 1) {
                            fitting(c, label, false, false);
                        } else {
                            tallChecks(c, label, gh);
                        }
                    }
                }
            }
            // Invert flips both alike: wheel DOWN now moves the content up, for a tall and for a fitting tooltip
            guiScale(c, 2);
            c.ctx().waitTicks(3);
            cfg.set(CFG, "Invert", true);
            int gh = guiHeight(c);
            for (double factor : new double[]{1.5}) {
                List<Component> l = new ArrayList<>();
                lines(gh, factor).forEach(x -> l.add(Component.literal(x)));
                c.ctx().getInput().setCursorPos(5, 5);
                tip = List.of();
                c.ctx().waitTicks(8);
                tip = l;
                c.ctx().getInput().setCursorPos(300, winH * 0.5);
                c.ctx().waitTicks(4);
                String label = "invert-h" + factor;
                {
                    Line r0 = find(shot(c, label + "-0"), true);
                    c.check(r0.full(2), label + ": first line not fully on screen at the start (" + r0 + ")");
                    wheel(c, -1, 1);
                    Line r1 = find(shot(c, label + "-1"), true);
                    c.check(movedUp(r0, r1), label + ": with Invert, wheel down did not move the content up (" + r0 + " -> " + r1 + ")");
                }
            }
        } finally {
            tip = List.of();
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
            guiScale(c, oldScale);
        }
    }

    /** (d) + the direction reference: a tooltip that fits is drawn whole; over a container slot ({@code nudges}) wheel up
     *  (down with Invert) moves it up, elsewhere the wheel leaves it alone. */
    private static void fitting(Session c, String label, boolean invert, boolean nudges) {
        BufferedImage s0 = shot(c, label + "-0");
        int scale = scale(c);
        Line r0 = find(s0, true);
        Line g0 = find(s0, false);
        c.check(r0.full(scale) && g0.full(scale), label + ": a tooltip that fits is not whole on screen (first " + r0 + ", last " + g0 + ")");
        wheel(c, invert ? -1 : 1, 1);
        Line r1 = find(shot(c, label + "-1"), true);
        if (nudges) {
            c.check(movedUp(r0, r1), label + ": wheel " + (invert ? "down (Invert)" : "up") + " did not move a fitting tooltip up (" + r0 + " -> " + r1 + ")");
        } else {
            c.check(r1.minY == r0.minY, label + ": the wheel moved a fitting tooltip outside a container (" + r0 + " -> " + r1 + ")");
        }
        wheel(c, invert ? 1 : -1, 1);
        c.note(label + ": fits, first line y " + r0.minY + " -> " + r1.minY);
    }

    /** (a)-(c) on a tooltip taller than the screen. */
    private static void tallChecks(Session c, String label, int guiH) {
        int scale = scale(c);
        BufferedImage s0 = shot(c, label + "-0");
        int imgH = s0.getHeight();
        Line r0 = find(s0, true);
        c.check(r0.full(scale), label + ": a tall tooltip does not start with its first line on screen (" + r0 + ")");
        c.check(r0.minY >= 0 && r0.minY <= 12 * scale, label + ": first line not at the top at the start (" + r0 + ")");
        // (b) wheel up moves the content up, like the fitting tooltip
        wheel(c, 1, 1);
        Line r1 = find(shot(c, label + "-1"), true);
        c.check(movedUp(r0, r1), label + ": wheel up did not move the tall tooltip's content up (" + r0 + " -> " + r1 + ")");
        // (a)+(c) keep going up to the end: the last line comes fully on screen at the bottom and stops there
        int notches = guiH * 3 / 30 + 4;
        wheel(c, 1, notches);
        BufferedImage s2 = shot(c, label + "-2");
        Line g2 = find(s2, false);
        c.check(g2.full(scale), label + ": the last line never came fully on screen (" + g2 + ")");
        c.check(g2.maxY >= imgH - 14 * scale, label + ": scrolling stopped with blank space under the last line (" + g2 + ", screen " + imgH + ")");
        wheel(c, 1, 2);
        Line g3 = find(shot(c, label + "-3"), false);
        c.check(g3.minY == g2.minY, label + ": the wheel moved the content past its end (" + g2 + " -> " + g3 + ")");
        // and all the way back: the first line returns to the top and stops there
        wheel(c, -1, notches + 3);
        Line r4 = find(shot(c, label + "-4"), true);
        c.check(r4.full(scale) && r4.minY <= 12 * scale, label + ": wheel down did not bring the first line back to the top (" + r4 + ")");
        c.note(label + ": first " + r0 + " -> up1 " + r1 + "; end last " + g2 + "; back first " + r4);
    }

    // ---- slots: a server menu slot (top and bottom rows) and inventory slots, 1.5x and 3x, scale 2 and 3 ----------------

    static void slots(Session c) throws Exception {
        int oldScale = guiScale(c, 2);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CFG, "Enabled", true).set(CFG, "LinesPerScroll", 3).set(CFG, "Invert", false);
            for (int scale : new int[]{1, 2}) {
                guiScale(c, scale);
                c.ctx().waitTicks(3);
                int gh = guiHeight(c);
                for (double factor : new double[]{1.5, 3.0}) {
                    List<String> l = lines(gh, factor);
                    String[] lore = l.subList(1, l.size()).toArray(new String[0]);
                    // server menu: slot 4 (top row) and 49 (bottom row)
                    MenuKit.reset(c);
                    Map<Integer, JsonObject> items = new LinkedHashMap<>();
                    items.put(4, FlipCroesusCases.item("minecraft:diamond", FIRST, lore));
                    items.put(49, FlipCroesusCases.item("minecraft:diamond", FIRST, lore));
                    items.put(22, FlipCroesusCases.item("minecraft:diamond", FIRST, "§7short", LAST));
                    MenuKit.show(c, FlipCroesusCases.menu("Tall Tips 1114", 6, items, new LinkedHashMap<>()));
                    MenuKit.awaitScreen(c, "Tall Tips 1114", 100);
                    c.ctx().waitTicks(6);
                    if (factor == 1.5 && gh >= 225) { // a tooltip that fits over a slot: the direction reference, Invert both ways
                        for (boolean inv : new boolean[]{false, true}) {
                            cfg.set(CFG, "Invert", inv);
                            c.ctx().getInput().setCursorPos(2, 2);
                            c.ctx().waitTicks(8);
                            CroesusUiCases.hover(c, 22);
                            fitting(c, "menu-s" + scale + "-fit-invert" + inv, inv, true);
                            if (inv) { // and a tall one with Invert: wheel down moves it up
                                c.ctx().getInput().setCursorPos(2, 2);
                                c.ctx().waitTicks(8);
                                CroesusUiCases.hover(c, 4);
                                Line r0 = find(shot(c, "menu-s" + scale + "-tall-invert-0"), true);
                                wheel(c, -1, 1);
                                Line r1 = find(shot(c, "menu-s" + scale + "-tall-invert-1"), true);
                                c.check(movedUp(r0, r1), "menu tall Invert: wheel down did not move the content up (" + r0 + " -> " + r1 + ")");
                            }
                        }
                        cfg.set(CFG, "Invert", false);
                    }
                    for (int slot : new int[]{4, 49}) {
                        if (gh < 225) { // a 6-row menu does not fit this GUI scale's screen
                            c.note("menu s" + scale + ": GUI height " + gh + " < 225, menu slots skipped");
                            break;
                        }
                        c.ctx().getInput().setCursorPos(2, 2);
                        c.ctx().waitTicks(8);
                        CroesusUiCases.hover(c, slot);
                        tallChecks(c, "menu-s" + scale + "-h" + factor + "-slot" + slot, gh);
                    }
                    MenuKit.reset(c);
                    // inventory: slot 9 (upper inventory) and hotbar slot 0
                    c.ctx().runOnClient(mc -> {
                        for (int idx : new int[]{9, 0}) {
                            ItemStack st = new ItemStack(Items.DIAMOND);
                            st.set(DataComponents.CUSTOM_NAME, Component.literal(FIRST));
                            List<Component> lc = new ArrayList<>();
                            for (String x : lore) {
                                lc.add(Component.literal(x));
                            }
                            st.set(DataComponents.LORE, new ItemLore(lc));
                            mc.player.getInventory().setItem(idx, st);
                        }
                        McCompat.setScreen(mc, new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
                    });
                    c.ctx().waitTicks(8);
                    for (int idx : new int[]{9, 0}) {
                        if (gh < 170) {
                            c.note("inventory s" + scale + ": GUI height " + gh + " < 170, inventory slots skipped");
                            break;
                        }
                        c.ctx().getInput().setCursorPos(2, 2);
                        c.ctx().waitTicks(8);
                        double[] p = c.onClient(mc -> {
                            var s = (net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) McCompat.screen(mc);
                            var sl = s.getMenu().slots.stream().filter(x -> x.getContainerSlot() == idx && x.container == mc.player.getInventory()).findFirst().get();
                            double sc = mc.getWindow().getGuiScale();
                            int lx = ((Number) dev.testkit.gametest.mod.Mod.field(s, "leftPos")).intValue();
                            int ty = ((Number) dev.testkit.gametest.mod.Mod.field(s, "topPos")).intValue();
                            return new double[]{(lx + sl.x + 8) * sc, (ty + sl.y + 8) * sc};
                        });
                        c.ctx().getInput().setCursorPos(p[0], p[1]);
                        c.ctx().waitTicks(6);
                        tallChecks(c, "inv-s" + scale + "-h" + factor + "-idx" + idx, gh);
                    }
                    c.ctx().runOnClient(mc -> {
                        mc.player.getInventory().setItem(9, ItemStack.EMPTY);
                        mc.player.getInventory().setItem(0, ItemStack.EMPTY);
                        McCompat.setScreen(mc, null);
                    });
                    c.ctx().waitTicks(4);
                }
            }
        } finally {
            MenuKit.reset(c);
            c.ctx().runOnClient(mc -> {
                mc.player.getInventory().setItem(9, ItemStack.EMPTY);
                mc.player.getInventory().setItem(0, ItemStack.EMPTY);
                McCompat.setScreen(mc, null);
            });
            guiScale(c, oldScale);
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    /** {@code n} real wheel notches through MouseHandler.onScroll (the window callback's entry), then a few frames. */
    private static void wheel(Session c, double dy, int n) {
        for (int i = 0; i < n; i++) {
            c.ctx().runOnClient(mc -> {
                try { // private on 26.2, public on 26.3
                    var m = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onScroll", long.class, double.class, double.class);
                    m.setAccessible(true);
                    m.invoke(mc.mouseHandler, mc.getWindow().handle(), 0.0, dy);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("onScroll: " + e);
                }
            });
            c.ctx().waitTicks(1);
        }
        c.ctx().waitTicks(3);
    }

    private static int scale(Session c) {
        return c.onClient(mc -> (int) Math.round(mc.getWindow().getGuiScale()));
    }

    private static BufferedImage shot(Session c, String label) {
        c.ctx().runOnClient(McCompat::clearChatAndToasts);
        c.ctx().waitTicks(2);
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-" + label));
        try {
            BufferedImage img = javax.imageio.ImageIO.read(taken.toFile());
            Files.deleteIfExists(taken);
            return img;
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read " + taken, e);
        }
    }

    /** The rows a colored line's bright text covers on screen; minY -1 = not on screen at all. */
    record Line(int minY, int maxY, int imgH) {
        boolean visible() {
            return minY >= 0;
        }

        /** Whole glyph height (caps: 7 GUI px) on screen, not touching either edge. */
        boolean full(int scale) {
            return visible() && minY > 0 && maxY < imgH - 1 && maxY - minY + 1 >= 7 * scale - 1;
        }

        @Override
        public String toString() {
            return visible() ? "y " + minY + ".." + maxY : "off screen";
        }
    }

    /** {@code red} = the first line (§c), else the last (§d light purple: no item or GUI texture in these screens has it). */
    static Line find(BufferedImage img, boolean red) {
        int min = -1, max = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int p = img.getRGB(x, y);
                int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                boolean hit = red ? r > 200 && g < 120 && b < 120 : r > 200 && b > 200 && g < 120;
                if (hit) {
                    if (min < 0) {
                        min = y;
                    }
                    max = y;
                    break;
                }
            }
        }
        return new Line(min, max, img.getHeight());
    }

    /** The first line went up the screen (or up off its top). */
    private static boolean movedUp(Line before, Line after) {
        return before.visible() && (!after.visible() ? before.minY < before.imgH / 2 : after.minY < before.minY);
    }
}

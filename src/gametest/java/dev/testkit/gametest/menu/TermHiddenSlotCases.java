package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * killer560, 2026-10-15: "In terminals if you can see my mouse it still lights up squares from the old gui. Those old
 * squares should be unclickable and not show up when hovered." With the Terminal Solver's Custom GUI on, the real chest
 * slots are hidden and the terminal is redrawn as a compact panel, but vanilla still worked out a hovered slot under the
 * cursor: its white hover highlight drew over the hidden slot, and its keys (hotbar numbers, drop, pick block) acted on
 * it.
 * <ul>
 *   <li>1015-menu-term-hidden-slots: for every terminal type (Correct Panes, Rubix, Click in Order, Starts With, Select
 *       Colour, Melody), the cursor on a hidden slot that no custom square covers - one in the terminal grid and one in
 *       the player inventory - leaves {@code hoveredSlot} null and changes no pixel of that slot; left, right, middle and
 *       shift clicks and the hotbar 1, drop and pick-block keys there reach the server as nothing; a real click on a
 *       custom square still sends exactly the click the solver's own target names (slot, button, input).</li>
 *   <li>1015-menu-term-hidden-slots-off: with Custom GUI off the same hover is vanilla's again (a hovered slot, a drawn
 *       highlight) and a click on a terminal slot reaches the server - the control that the checks above can see.</li>
 * </ul>
 * Clicks are judged from the SERVER's record ({@code container.click}), never from the client.
 */
final class TermHiddenSlotCases {

    static final String FEATURE = TerminalCases.FEATURE;
    static final String CONFIG = TerminalCases.CONFIG;

    private TermHiddenSlotCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1015-menu-term-hidden-slots", TermHiddenSlotCases::hiddenSlots);
        MenuSuite.test(s, "1015-menu-term-hidden-slots-off", TermHiddenSlotCases::customGuiOff);
    }

    // ---- geometry ---------------------------------------------------------------------------------------------------

    /** {leftPos, topPos, gui scale}. */
    static double[] screenGeometry(Session c) {
        return c.onClient(mc -> {
            Object s = McCompat.screen(mc);
            return new double[]{((Number) Mod.field(s, "leftPos")).doubleValue(), ((Number) Mod.field(s, "topPos")).doubleValue(),
                    mc.getWindow().getGuiScale()};
        });
    }

    /** The Custom GUI panel's box in GUI units {x0, y0, x1, y1}, padding included, or null when it is not laid out. */
    static double[] panelBox(Session c) {
        return c.onClient(mc -> {
            Object layout = Mod.staticCall(FEATURE, "computeCustomGuiLayout", McCompat.screen(mc));
            if (layout == null) {
                return null;
            }
            double scale = (Float) Mod.field(layout, "scale");
            double pad = (Integer) Mod.field(FEATURE, "PANEL_PADDING") * scale;
            double ox = (Integer) Mod.field(layout, "originX");
            double oy = (Integer) Mod.field(layout, "originY");
            return new double[]{ox - pad, oy - pad, ox + (Integer) Mod.field(layout, "panelWidth") * scale + pad,
                    oy + (Integer) Mod.field(layout, "panelHeight") * scale + pad};
        });
    }

    /** Centre of a custom square for a real slot index, in GUI units. */
    static double[] cellCentre(Session c, int slot) {
        return c.onClient(mc -> {
            Object layout = Mod.staticCall(FEATURE, "computeCustomGuiLayout", McCompat.screen(mc));
            double scale = (Float) Mod.field(layout, "scale");
            double x0 = (Integer) Mod.field(layout, "originX")
                    + ((slot % 9) - (Integer) Mod.field(layout, "minCol")) * TerminalCases.CELL * scale;
            double y0 = (Integer) Mod.field(layout, "originY")
                    + ((slot / 9) - (Integer) Mod.field(layout, "minRow")) * TerminalCases.CELL * scale;
            return new double[]{x0 + TerminalCases.SQUARE * scale / 2, y0 + TerminalCases.SQUARE * scale / 2};
        });
    }

    /** {slot index, slot.x, slot.y} of the first slot in [from, to) whose whole 16x16 box lies outside the panel box. */
    static int[] hiddenSlotOutsidePanel(Session c, int from, int to) {
        double[] g = screenGeometry(c);
        double[] p = panelBox(c);
        return c.onClient(mc -> {
            List<Slot> slots = ((AbstractContainerScreen<?>) McCompat.screen(mc)).getMenu().slots;
            for (int i = from; i < Math.min(to, slots.size()); i++) {
                Slot sl = slots.get(i);
                double x0 = g[0] + sl.x, y0 = g[1] + sl.y;
                boolean clear = p == null || x0 + 16 <= p[0] || x0 >= p[2] || y0 + 16 <= p[1] || y0 >= p[3];
                if (clear) {
                    return new int[]{i, sl.x, sl.y};
                }
            }
            return null;
        });
    }

    static void cursorGui(Session c, double gx, double gy) {
        double scale = screenGeometry(c)[2];
        c.ctx().getInput().setCursorPos(gx * scale, gy * scale);
        c.ctx().waitTicks(4);
    }

    static Integer hoveredIndex(Session c) {
        return c.onClient(mc -> {
            Object h = Mod.field(McCompat.screen(mc), "hoveredSlot");
            return h == null ? null : ((Slot) h).index;
        });
    }

    static BufferedImage shot(Session c, String label) {
        String name = c.name() + "-" + label;
        c.ctx().runOnClient(McCompat::clearChatAndToasts);
        c.ctx().waitTicks(2);
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        dev.testkit.harness.Report.screenshot(name, taken);
        try {
            return javax.imageio.ImageIO.read(taken.toFile());
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read " + taken, e);
        }
    }

    /** Changed pixels between two shots inside a GUI box {x, y, w, h}. */
    static int changedIn(BufferedImage a, BufferedImage b, double guiScale, double x, double y, double w, double h) {
        double px = guiScale; // the screenshot is the framebuffer: guiScale pixels per GUI unit
        int x0 = (int) Math.floor(x * px), y0 = (int) Math.floor(y * px);
        int x1 = Math.min(a.getWidth(), (int) Math.ceil((x + w) * px)), y1 = Math.min(a.getHeight(), (int) Math.ceil((y + h) * px));
        int n = 0;
        for (int yy = Math.max(0, y0); yy < y1; yy++) {
            for (int xx = Math.max(0, x0); xx < x1; xx++) {
                if (CroesusUiCases.changed(a.getRGB(xx, yy), b.getRGB(xx, yy))) {
                    n++;
                }
            }
        }
        return n;
    }

    static void pressAtGui(Session c, double gx, double gy, int button, int mods) {
        cursorGui(c, gx, gy);
        c.ctx().runOnClient(mc -> {
            long window = mc.getWindow().handle();
            Mod.call(mc.mouseHandler, "onButton", window, new net.minecraft.client.input.MouseButtonInfo(button, mods), 1);
            Mod.call(mc.mouseHandler, "onButton", window, new net.minecraft.client.input.MouseButtonInfo(button, mods), 0);
        });
        c.ctx().waitTicks(4);
    }

    static String clicks(List<JsonObject> ev) {
        List<String> out = new ArrayList<>();
        for (JsonObject e : ev) {
            out.add(e.get("slot") + "/" + e.get("button") + "/" + e.get("input").getAsString());
        }
        return out.toString();
    }

    // ---- 1015-menu-term-hidden-slots ---------------------------------------------------------------------------------

    static void hiddenSlots(Session c) throws Exception {
        MenuKit.reset(c);
        List<String> failures = new ArrayList<>();
        int typesChecked = 0;
        int squareClicks = 0;
        // The Custom GUI draws no backdrop, so the world shows round the panel and the pixel compare below sees it. On the
        // menu run of 2026-10-22 an earlier case had left him under water, and the moving water and seagrass "changed" up to
        // 2889 pixels at every hidden slot with the mod doing nothing. Up at y 300 looking straight up there is only sky
        // (824's fix, LeapBatchMenuCases); each compare also measures that floor with two cursor-away frames.
        double[] posWas = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                mc.player.getYRot(), mc.player.getXRot()});
        c.hx().call("dungeon.tp", "x", posWas[0], "y", 300.0, "z", posWas[2], "yaw", (float) posWas[3], "pitch", -90f);
        c.ctx().waitTicks(5);
        c.onClient(mc -> {
            mc.player.setXRot(-90f);
            return null;
        });
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", false)
                    .set(CONFIG, "MelodyEnabled", true).set(CONFIG, "CustomGuiEnabled", true);
            boolean on = c.onClient(mc -> (Boolean) Mod.get(CONFIG, "isCustomGuiEnabled"));
            c.check(on, "Custom GUI did not switch on (isCustomGuiEnabled false - Skyblock gate?)");
            for (TerminalCases.Spec spec : TerminalCases.SPECS) {
                c.note("---- " + spec.type() + " ----");
                DungeonFixTerminalCases.rebase(c);
                JsonObject t = spec.type().equals("MELODY")
                        ? c.hx().call("menu.terminal", "type", "MELODY", "seed", 31, "target", 2, "freeze", true,
                        "bandRows", 3).getAsJsonObject()
                        : TerminalCases.open(c, spec);
                MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
                c.waitUntil("TerminalSolverFeature.currentType == " + spec.type(),
                        mc -> spec.type().equals(TerminalCases.currentType()), 100);
                c.waitUntil("the Custom GUI to be drawn (hasStabilizedOnce)",
                        mc -> Boolean.TRUE.equals(Mod.field(FEATURE, "hasStabilizedOnce")), 100);
                if (!spec.type().equals("MELODY")) {
                    c.waitUntil("the solver to highlight something", mc -> !TerminalCases.highlights().isEmpty(), 100);
                }
                c.ctx().waitTicks(10);
                int termSlots = c.onClient(mc -> (Integer) Mod.staticCall(FEATURE, "getCurrentTerminalSlotCount"));
                int[] gridSlot = hiddenSlotOutsidePanel(c, 0, termSlots);
                int[] invSlot = hiddenSlotOutsidePanel(c, termSlots, 1000);
                double guiScale = screenGeometry(c)[2];
                for (int[] hs : new int[][]{gridSlot, invSlot}) {
                    if (hs == null) {
                        continue;
                    }
                    String where = spec.type() + " slot " + hs[0] + (hs[0] < termSlots ? " (terminal grid)" : " (inventory)");
                    double[] g = screenGeometry(c);
                    double cx = g[0] + hs[1] + 8, cy = g[1] + hs[2] + 8;
                    cursorGui(c, 2, 2);
                    BufferedImage away = shot(c, spec.type().toLowerCase() + "-" + hs[0] + "-away");
                    BufferedImage away2 = shot(c, spec.type().toLowerCase() + "-" + hs[0] + "-away-2");
                    // The noise floor: the same two boxes in two frames with the cursor away. Anything here is the
                    // background moving, not the hover, and would make the compare below mean nothing.
                    int noise = changedIn(away, away2, guiScale, g[0] + hs[1], g[1] + hs[2], 16, 16)
                            + changedIn(away, away2, guiScale, cx + 8, cy - 40, 160, 60);
                    if (noise > 0) {
                        failures.add(where + ": the background moved between two cursor-away frames (" + noise
                                + " px) - the hover compare means nothing");
                    }
                    cursorGui(c, cx, cy);
                    Integer hovered = hoveredIndex(c);
                    BufferedImage over = shot(c, spec.type().toLowerCase() + "-" + hs[0] + "-hover");
                    int changedSlot = changedIn(away, over, guiScale, g[0] + hs[1], g[1] + hs[2], 16, 16);
                    // The tooltip (if any) is drawn up and to the right of the cursor.
                    int changedTip = changedIn(away, over, guiScale, cx + 8, cy - 40, 160, 60);
                    DungeonFixTerminalCases.rebase(c);
                    pressAtGui(c, cx, cy, 0, 0);
                    pressAtGui(c, cx, cy, 1, 0);
                    pressAtGui(c, cx, cy, 2, 0);
                    pressAtGui(c, cx, cy, 0, 1); // shift-click
                    c.ctx().getInput().pressKey(o -> o.keyHotbarSlots[0]);
                    c.ctx().waitTicks(3);
                    c.ctx().getInput().pressKey(o -> o.keyDrop);
                    c.ctx().waitTicks(3);
                    c.ctx().getInput().pressKey(o -> o.keyPickItem);
                    c.ctx().waitTicks(6);
                    List<JsonObject> sent = c.events("container.click");
                    String line = where + ": hoveredSlot " + hovered + ", " + changedSlot + " px changed in the slot, "
                            + changedTip + " px in the tooltip area; 4 clicks + 1/drop/pick keys sent " + clicks(sent);
                    System.out.println("[menu] 1015-hidden " + line);
                    c.note(line);
                    if (hovered != null || changedSlot > 0 || changedTip > 0 || !sent.isEmpty()) {
                        failures.add(line);
                    }
                    cursorGui(c, 2, 2);
                }
                if (gridSlot == null) {
                    failures.add(spec.type() + ": no terminal-grid slot lies outside the panel (layout changed?)");
                }
                if (invSlot == null) {
                    failures.add(spec.type() + ": no inventory slot lies outside the panel (layout changed?)");
                }
                // A real click on a custom square: exactly the click the solver's own target names.
                DungeonFixTerminalCases.rebase(c);
                int slot;
                int button;
                String input;
                if (spec.type().equals("MELODY")) {
                    slot = 16; // row 1's button
                    button = 0;
                    input = ContainerInput.CLONE.name();
                } else {
                    Object typeEnum = Mod.enumValue("terminals.TerminalType", spec.type());
                    Object target = c.onClient(mc -> Mod.staticCall(FEATURE, "pickAutoClickTarget", typeEnum,
                            TerminalCases.highlights()));
                    slot = (Integer) Mod.call(target, "slot");
                    button = (Integer) Mod.call(target, "button");
                    input = ((ContainerInput) Mod.call(target, "clickType")).name();
                }
                double[] cell = cellCentre(c, slot);
                pressAtGui(c, cell[0], cell[1], button, 0);
                c.ctx().waitTicks(4);
                List<JsonObject> got = c.events("container.click");
                String expect = slot + "/" + button + "/" + input;
                String line = spec.type() + " custom square " + slot + ": expected " + expect + ", server saw " + clicks(got);
                System.out.println("[menu] 1015-hidden " + line);
                c.note(line);
                if (got.size() != 1 || !clicks(got).equals("[" + expect + "]")) {
                    failures.add(line);
                } else {
                    squareClicks++;
                }
                typesChecked++;
                MenuKit.reset(c);
            }
        } finally {
            c.ctx().getInput().setCursorPos(2, 2);
            MenuKit.reset(c);
            c.hx().call("dungeon.tp", "x", posWas[0], "y", posWas[1], "z", posWas[2], "yaw", (float) posWas[3],
                    "pitch", (float) posWas[4]);
        }
        c.check(typesChecked == TerminalCases.SPECS.size(), "only " + typesChecked + " terminal types were checked");
        c.check(squareClicks == TerminalCases.SPECS.size(), "only " + squareClicks + " custom-square clicks matched");
        c.check(failures.isEmpty(), failures.size() + " problem(s): " + failures);
    }

    // ---- 1015-menu-term-hidden-slots-off -----------------------------------------------------------------------------

    static void customGuiOff(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", false).set(CONFIG, "CustomGuiEnabled", false);
            TerminalCases.Spec spec = TerminalCases.SPECS.get(0); // PANES
            JsonObject t = TerminalCases.open(c, spec);
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            c.waitUntil("TerminalSolverFeature.currentType == PANES", mc -> "PANES".equals(TerminalCases.currentType()), 100);
            c.waitUntil("the solver to highlight something", mc -> !TerminalCases.highlights().isEmpty(), 100);
            c.ctx().waitTicks(10);
            int slot = c.onClient(mc -> TerminalCases.highlights().keySet().iterator().next());
            int[] xy = c.onClient(mc -> {
                Slot sl = ((AbstractContainerScreen<?>) McCompat.screen(mc)).getMenu().slots.get(slot);
                return new int[]{sl.x, sl.y};
            });
            double[] g = screenGeometry(c);
            double cx = g[0] + xy[0] + 8, cy = g[1] + xy[1] + 8;
            cursorGui(c, 2, 2);
            BufferedImage away = shot(c, "off-away");
            cursorGui(c, cx, cy);
            Integer hovered = hoveredIndex(c);
            BufferedImage over = shot(c, "off-hover");
            int changed = changedIn(away, over, g[2], g[0] + xy[0], g[1] + xy[1], 16, 16);
            DungeonFixTerminalCases.rebase(c);
            pressAtGui(c, cx, cy, 0, 0);
            c.ctx().waitTicks(4);
            List<JsonObject> got = c.events("container.click");
            c.note("Custom GUI off, PANES slot " + slot + ": hoveredSlot " + hovered + ", " + changed
                    + " px changed by the hover, a click sent " + clicks(got));
            c.check(hovered != null && hovered == slot, "Custom GUI off: hoveredSlot is " + hovered + ", not " + slot);
            c.check(changed > 0, "Custom GUI off: vanilla's hover highlight changed no pixel of slot " + slot);
            c.check(!got.isEmpty() && got.get(0).get("slot").getAsInt() == slot,
                    "Custom GUI off: a click on slot " + slot + " reached the server as " + clicks(got));
        } finally {
            c.ctx().getInput().setCursorPos(2, 2);
            MenuKit.reset(c);
        }
    }
}

package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * killer560, 2026-11-01 (26.3): in the Storage Overlay, hovering BLANK overlay space still hovered the real (hidden) slot of
 * Hypixel's menu under it, and a click there picked up his Spirit Sceptre from it. Then: "make that happen for all guis we
 * draw over". The mod's {@code containercover.ContainerCover} is the one rule: under a full-screen reskin, or under a panel
 * drawn over a menu, a vanilla slot never hovers and never takes a click, a key or a drag.
 * <ul>
 *   <li>1101-menu-cover-storage-page / -overview: the overlay on "Ender Chest (1/9)" and on the "Storage" overview. An item
 *       is put in the player-inventory slot whose hidden vanilla square lies under blank overlay space (no panel, label,
 *       side button or Inventory panel there - computed from the overlay's own last-frame geometry); the real cursor goes
 *       there: {@code hoveredSlot} must be null; left, right, middle, shift and double clicks, a drag to another blank
 *       point, hotbar key 1, drop and swap-offhand must reach the server as NOTHING ({@code container.click},
 *       the server's record) and the item must stay in its slot. A blank point outside the chest image (the
 *       vanilla "clicked outside" -999 path) gets the clicks too. Control, so the case cannot pass vacuously: a real click
 *       on the same item's square in the overlay's Inventory panel DOES send a click and picks it up (then puts it back).</li>
 *   <li>1101-menu-cover-reskins: AH browser, Bazaar category and Spirit Leap reskins and the sorter's locked inventory:
 *       each reports covering the whole screen, and a hidden inventory slot holding an item under the cursor leaves
 *       {@code hoveredSlot} null; hotbar key 9 and drop send nothing.</li>
 *   <li>1101-menu-cover-panels: panels drawn over a plain chest. The Item Browser centered over the chest, and the Inventory
 *       Search bar: each panel reports its rect; a real slot under it does not hover, and right/middle/shift clicks (the
 *       item browser passes right clicks on), key 9 and drop send nothing; a slot outside every panel still hovers (the
 *       control). The Listing Helper and the Croesus profit panel must report their rect when drawn; the profit panel is
 *       then moved over the chest's slots in the HUD config and probed like the others.</li>
 * </ul>
 * Clicks are judged from the SERVER's record only: the client's PacketWatch count is not running in a menu session (it
 * read 0 while the server logged nine clicks on the unfixed jar, 2026-11-02).
 */
final class ContainerCoverCases {

    static final String COVER = "containercover.ContainerCover";
    static final String SFEAT = "storageoverlay.StorageOverlayFeature";
    static final String SCFG = "storageoverlay.StorageOverlayConfig";

    private ContainerCoverCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1101-menu-cover-storage-page", c -> storage(c, "menus.ender-chest-1", "Ender Chest (1/9)"));
        MenuSuite.test(s, "1101-menu-cover-storage-overview", c -> storage(c, "menus.storage-overview-locked-empty", "Storage"));
        MenuSuite.test(s, "1101-menu-cover-reskins", ContainerCoverCases::reskins);
        MenuSuite.test(s, "1101-menu-cover-panels", ContainerCoverCases::panels);
    }

    // ---- shared --------------------------------------------------------------------------------------------------

    /** {leftPos, topPos, imageWidth, imageHeight, gui width, gui height}. */
    static double[] geom(Session c) {
        return c.onClient(mc -> {
            Object s = McCompat.screen(mc);
            return new double[]{((Number) Mod.field(s, "leftPos")).doubleValue(), ((Number) Mod.field(s, "topPos")).doubleValue(),
                    ((Number) Mod.field(s, "imageWidth")).doubleValue(), ((Number) Mod.field(s, "imageHeight")).doubleValue(),
                    mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()};
        });
    }

    /** GUI center of a menu slot. */
    static double[] slotCentre(Session c, int index) {
        return c.onClient(mc -> {
            AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
            Slot sl = s.getMenu().slots.get(index);
            return new double[]{((Number) Mod.field(s, "leftPos")).doubleValue() + sl.x + 8,
                    ((Number) Mod.field(s, "topPos")).doubleValue() + sl.y + 8};
        });
    }

    /** Moves the real cursor to a GUI point (window pixels = GUI x screen/gui ratio, as HudEditorCases does). */
    static void cursor(Session c, double gx, double gy) {
        double[] w = c.onClient(mc -> new double[]{gx * mc.getWindow().getScreenWidth() / (double) mc.getWindow().getGuiScaledWidth(),
                gy * mc.getWindow().getScreenHeight() / (double) mc.getWindow().getGuiScaledHeight()});
        c.ctx().getInput().setCursorPos(w[0], w[1]);
        c.ctx().waitTicks(4);
    }

    static Integer hovered(Session c) {
        return TermHiddenSlotCases.hoveredIndex(c);
    }

    static String itemAt(Session c, int index) {
        return c.onClient(mc -> {
            ItemStack st = ((AbstractContainerScreen<?>) McCompat.screen(mc)).getMenu().slots.get(index).getItem();
            return st.isEmpty() ? "empty" : st.getItem() + "x" + st.getCount();
        });
    }

    static boolean carrying(Session c) {
        return c.onClient(mc -> !((AbstractContainerScreen<?>) McCompat.screen(mc)).getMenu().getCarried().isEmpty());
    }


    /** A press+release through the real mouse handler at the cursor's current place, with modifier bits (1 = Shift). */
    static void press(Session c, int button, int mods) {
        c.ctx().runOnClient(mc -> {
            long window = mc.getWindow().handle();
            Mod.call(mc.mouseHandler, "onButton", window, new net.minecraft.client.input.MouseButtonInfo(dev.testkit.compat.McKeys.mouse(button), mods), 1);
            Mod.call(mc.mouseHandler, "onButton", window, new net.minecraft.client.input.MouseButtonInfo(dev.testkit.compat.McKeys.mouse(button), mods), 0);
        });
        c.ctx().waitTicks(4);
    }

    static boolean hasCover(Session c) {
        return Mod.has(COVER);
    }

    static String covering(Session c, double x, double y) {
        if (!hasCover(c)) {
            return "(no ContainerCover in this jar)";
        }
        return String.valueOf(c.<Object>onClient(mc -> Mod.staticCall(COVER, "coveringOwners", McCompat.screen(mc), x, y)));
    }

    /**
     * Cursor on {@code (x, y)} (over hidden/covered slot {@code index}): no hover, and every input sends nothing.
     * {@code clicks} false = keys and hover only (a reskin whose own listener owns every click there).
     * Adds a failure line per problem; returns a one-line summary.
     */
    static String probe(Session c, String where, int index, double x, double y, boolean clicks, double[] dragTo,
                        List<String> failures) {
        String before = itemAt(c, index);
        int server0 = c.events("container.click").size();
        cursor(c, x, y);
        Integer h = hovered(c);
        String cov = covering(c, x, y);
        if (clicks) {
            press(c, 0, 0);
            press(c, 1, 0);
            press(c, 2, 0);
            press(c, 0, 1); // shift-click
            // double-click: two presses well inside the 250 ms window
            c.ctx().runOnClient(mc -> {
                long window = mc.getWindow().handle();
                for (int i = 0; i < 2; i++) {
                    Mod.call(mc.mouseHandler, "onButton", window, new net.minecraft.client.input.MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0), 1);
                    Mod.call(mc.mouseHandler, "onButton", window, new net.minecraft.client.input.MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0), 0);
                }
            });
            c.ctx().waitTicks(4);
            if (dragTo != null) {
                c.ctx().getInput().holdMouse(dev.testkit.compat.McKeys.mouse(0));
                c.ctx().waitTicks(2);
                cursor(c, (x + dragTo[0]) / 2, (y + dragTo[1]) / 2);
                cursor(c, dragTo[0], dragTo[1]);
                c.ctx().getInput().releaseMouse(dev.testkit.compat.McKeys.mouse(0));
                c.ctx().waitTicks(3);
                cursor(c, x, y);
            }
        }
        c.ctx().getInput().pressKey(o -> o.keyHotbarSlots[8]);
        c.ctx().waitTicks(3);
        c.ctx().getInput().pressKey(o -> o.keyDrop);
        c.ctx().waitTicks(3);
        if (clicks) {
            c.ctx().getInput().pressKey(o -> o.keyHotbarSlots[0]);
            c.ctx().waitTicks(3);
            c.ctx().getInput().pressKey(o -> o.keySwapOffhand);
            c.ctx().waitTicks(3);
        }
        c.ctx().waitTicks(4);
        List<JsonObject> sent = c.events("container.click");
        List<JsonObject> mine = sent.subList(Math.min(server0, sent.size()), sent.size());
        String after = itemAt(c, index);
        boolean carried = carrying(c);
        String line = where + " (slot " + index + " at " + Math.round(x) + "," + Math.round(y) + ", covered by " + cov
                + "): hoveredSlot " + h + "; " + (clicks ? "left/right/middle/shift/double clicks" + (dragTo != null ? ", a drag" : "")
                + ", keys 1/9/drop/offhand" : "keys 9/drop") + " -> server " + TermHiddenSlotCases.clicks(mine)
                + "; item " + before + " -> " + after + (carried ? ", CARRYING" : "");
        System.out.println("[menu] cover " + line);
        c.note(line);
        if (h != null) {
            failures.add(where + ": slot " + h + " hovers under the cover");
        }
        if (!mine.isEmpty()) {
            failures.add(where + ": input reached the hidden slot: server " + TermHiddenSlotCases.clicks(mine));
        }
        if (!before.equals(after) || carried) {
            failures.add(where + ": the item moved: " + before + " -> " + after + (carried ? " (on the cursor)" : ""));
        }
        return line;
    }

    /** Puts {@code stack} into player-inventory slot {@code invSlot} (0-35) and waits until the open menu shows it. */
    static void giveInto(Session c, int invSlot, int menuIndex, String stack) {
        c.hx().give(invSlot, stack);
        c.waitUntil("the item in menu slot " + menuIndex, mc -> McCompat.screen(mc) instanceof AbstractContainerScreen<?> s
                && !s.getMenu().slots.get(menuIndex).getItem().isEmpty(), 60);
    }

    /** Player inventory index (0-35) of a menu slot, or -1. Container menus put main inventory 9-35 then hotbar 0-8 last. */
    static int invIndex(Session c, int menuIndex) {
        return c.onClient(mc -> {
            AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
            Slot sl = s.getMenu().slots.get(menuIndex);
            return sl.container == mc.player.getInventory() ? sl.getContainerSlot() : -1;
        });
    }

    // ---- storage overlay ----------------------------------------------------------------------------------------

    /** Whether the Storage Overlay claims GUI point (x, y): a grid panel or label, a side button, or the Inventory panel. */
    static boolean storageClaims(Session c, double x, double y) {
        return c.onClient(mc -> {
            int[] pos = (int[]) Mod.field(SFEAT, "lastPos");
            float scale = ((Number) Mod.field(SFEAT, "lastScale")).floatValue();
            float scroll = ((Number) Mod.field(SFEAT, "scrollOffset")).floatValue();
            int vw = ((Number) Mod.field(SFEAT, "lastViewportWidthPx")).intValue();
            int vh = ((Number) Mod.field(SFEAT, "lastViewportHeightPx")).intValue();
            for (String f : new String[]{"lastBackButton", "lastSearchButton", "lastScanButton"}) {
                int[] r = (int[]) Mod.field(SFEAT, f);
                if (r != null && x >= r[0] - 1 && x < r[0] + r[2] + 1 && y >= r[1] - 1 && y < r[1] + r[3] + 1) {
                    return true;
                }
            }
            int[] io = (int[]) Mod.field(SFEAT, "lastInventoryOrigin");
            int[] ib = (int[]) Mod.field(SFEAT, "lastInventoryBounds");
            float is = ((Number) Mod.field(SFEAT, "lastInventoryScale")).floatValue();
            int panelW = ((Number) Mod.field(SFEAT, "PANEL_WIDTH")).intValue();
            if (io != null && ib != null && x >= io[0] - 2 && x < io[0] + panelW * is + 2 && y >= io[1] - 2
                    && y < io[1] + (ib[1] + ib[3] + 4) * is) {
                return true;
            }
            if (pos != null && x >= pos[0] && x <= pos[0] + vw && y >= pos[1] && y <= pos[1] + vh) {
                double lx = (x - pos[0]) / scale;
                double ly = (y - pos[1]) / scale + scroll;
                for (String f : new String[]{"lastPanelBounds", "lastLabelBounds"}) {
                    @SuppressWarnings("unchecked")
                    Map<String, int[]> m = (Map<String, int[]>) Mod.field(SFEAT, f);
                    for (int[] b : m.values()) {
                        if (lx >= b[0] - 2 && lx <= b[0] + b[2] + 2 && ly >= b[1] - 2 && ly <= b[1] + b[3] + 2) {
                            return true;
                        }
                    }
                }
            }
            return false;
        });
    }

    static void storage(Session c, String fixture, String title) throws Exception {
        MenuKit.reset(c);
        int[] win = c.onClient(mc -> new int[]{mc.getWindow().getScreenWidth(), mc.getWindow().getScreenHeight(),
                mc.options.guiScale().get()});
        InventoryCases.clearInv(c);
        List<String> failures = new ArrayList<>();
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(SCFG, "Enabled", true);
            // His window, not the 427x240 GUI test window: there the Inventory panel sits on top of every hidden
            // inventory slot. At 1280x720 / GUI 2 the hidden inventory rows lie in the blank viewport, as in his shot.
            AuctionHouseCases.setWindow(c, 1280, 720, 2);
            c.onClient(mc -> {
                Mod.setField(SFEAT, "lastPos", null);
                return null;
            });
            // The test window is ~180 GUI units tall and earlier cases left many storages known, so the grid would fill
            // every blank pixel. Focus it on Ender Chest page 1 (what Storage Search's "show me" does): one panel, and the
            // blank viewport below it is the space killer560 clicked through.
            String focus = c.onClient(mc -> Mod.staticCall(SFEAT, "accountProfilePrefix") + "|enderchest_1");
            c.onClient(mc -> {
                if (!(Boolean) Mod.call(Mod.staticCall("storageoverlay.StorageOverlayCache", "getInstance"), "hasContents", focus)) {
                    List<ItemStack> items = new ArrayList<>();
                    for (int i = 0; i < 45; i++) {
                        items.add(i == 0 ? new ItemStack(net.minecraft.world.item.Items.DIAMOND, 5) : ItemStack.EMPTY);
                    }
                    Mod.call(Mod.staticCall("storageoverlay.StorageOverlayCache", "getInstance"), "put", focus, items);
                }
                Mod.staticCall(SFEAT, "setFocus", focus);
                return null;
            });
            MenuKit.show(c, MenuKit.menu(fixture));
            MenuKit.awaitScreen(c, title, 100);
            c.waitUntil("the Storage Overlay to draw over '" + title + "'", mc -> Mod.field(SFEAT, "lastPos") != null
                    && Mod.field(SFEAT, "lastInventoryOrigin") != null, 100);
            c.ctx().waitTicks(6);
            int size = c.onClient(mc -> ((AbstractContainerScreen<?>) McCompat.screen(mc)).getMenu().slots.size());
            int base = size - 36;
            // A player-inventory slot whose hidden square is under blank overlay space: its whole 16x16 box unclaimed.
            int pick = -1;
            for (int i = base; i < size && pick < 0; i++) {
                double[] ctr = slotCentre(c, i);
                boolean clear = true;
                for (double dx : new double[]{-8, 0, 7.9}) {
                    for (double dy : new double[]{-8, 0, 7.9}) {
                        if (storageClaims(c, ctr[0] + dx, ctr[1] + dy)) {
                            clear = false;
                        }
                    }
                }
                if (clear) {
                    pick = i;
                }
            }
            if (pick < 0) {
                String geo = c.onClient(mc -> "lastPos " + java.util.Arrays.toString((int[]) Mod.field(SFEAT, "lastPos"))
                        + " viewport " + Mod.field(SFEAT, "lastViewportWidthPx") + "x" + Mod.field(SFEAT, "lastViewportHeightPx")
                        + " scroll " + Mod.field(SFEAT, "scrollOffset") + " panels " + ((Map<?, ?>) Mod.field(SFEAT, "lastPanelBounds")).size()
                        + " focus " + Mod.field(SFEAT, "focusKey")
                        + " inventory " + java.util.Arrays.toString((int[]) Mod.field(SFEAT, "lastInventoryOrigin"))
                        + " gui " + mc.getWindow().getGuiScaledWidth() + "x" + mc.getWindow().getGuiScaledHeight());
                c.note("overlay geometry: " + geo + "; chest " + java.util.Arrays.toString(geom(c)));
            }
            c.check(pick >= 0, "no player-inventory slot of '" + title + "' lies under blank overlay space (layout changed?)");
            int inv = invIndex(c, pick);
            c.check(inv >= 0, "menu slot " + pick + " is not a player-inventory slot");
            giveInto(c, inv, pick, "minecraft:blaze_rod");
            double[] at = slotCentre(c, pick);
            // A second blank point for the drag, and one outside the chest image (vanilla's -999 "clicked outside" path).
            double[] g = geom(c);
            double[] dragTo = null;
            double[] outside = null;
            for (double yy = 4; yy < g[5] - 4 && (dragTo == null || outside == null); yy += 6) {
                for (double xx = 4; xx < g[4] - 4; xx += 6) {
                    if (storageClaims(c, xx, yy)) {
                        continue;
                    }
                    boolean inImage = xx >= g[0] && xx < g[0] + g[2] && yy >= g[1] && yy < g[1] + g[3];
                    if (dragTo == null && inImage && Math.hypot(xx - at[0], yy - at[1]) > 20) {
                        dragTo = new double[]{xx, yy};
                    }
                    if (outside == null && !inImage) {
                        outside = new double[]{xx, yy};
                    }
                }
            }
            probe(c, title + " blank space", pick, at[0], at[1], true, dragTo, failures);
            if (outside != null) {
                // Same probe at a blank point outside the chest image; the watched item is still the one above.
                probe(c, title + " blank space outside the chest image", pick, outside[0], outside[1], true, null, failures);
            } else {
                c.note("no blank point outside the chest image at this window size");
            }

            // Control: the item's own square in the overlay's Inventory panel takes a real click.
            int baseIdx = c.onClient(mc -> ((Number) Mod.field(SFEAT, "lastInventorySlotBase")).intValue());
            int i = pick - baseIdx;
            double[] panelCell = c.onClient(mc -> {
                int[] io = (int[]) Mod.field(SFEAT, "lastInventoryOrigin");
                int[] ib = (int[]) Mod.field(SFEAT, "lastInventoryBounds");
                float is = ((Number) Mod.field(SFEAT, "lastInventoryScale")).floatValue();
                int slotSize = ((Number) Mod.field(SFEAT, "SLOT_SIZE")).intValue();
                return new double[]{io[0] + (ib[0] + (i % 9 + 0.5) * slotSize) * is, io[1] + (ib[1] + (i / 9 + 0.5) * slotSize) * is};
            });
            int s0 = c.events("container.click").size();
            cursor(c, panelCell[0], panelCell[1]);
            final int pickF = pick;
            String diag = c.onClient(mc -> "cell " + Math.round(panelCell[0]) + "," + Math.round(panelCell[1]) + " i " + i
                    + " origin " + java.util.Arrays.toString((int[]) Mod.field(SFEAT, "lastInventoryOrigin"))
                    + " bounds " + java.util.Arrays.toString((int[]) Mod.field(SFEAT, "lastInventoryBounds"))
                    + " scale " + Mod.field(SFEAT, "lastInventoryScale") + " base " + Mod.field(SFEAT, "lastInventorySlotBase")
                    + " item " + ((AbstractContainerScreen<?>) McCompat.screen(mc)).getMenu().slots.get(pickF).getItem()
                    + " gui " + mc.getWindow().getGuiScaledWidth() + "x" + mc.getWindow().getGuiScaledHeight()
                    + " mouse " + mc.mouseHandler.xpos() + "," + mc.mouseHandler.ypos());
            c.note("control geometry: " + diag);
            System.out.println("[menu] cover control geometry: " + diag);
            press(c, 0, 0);
            if (c.events("container.click").size() == s0 && !carrying(c)) {
                // One retry through the testkit's own press, in case onButton's synthetic event was not delivered.
                c.ctx().getInput().pressMouse(dev.testkit.compat.McKeys.mouse(0));
                c.ctx().waitTicks(4);
                c.note("control: the first press sent nothing; retried with getInput().pressMouse -> carrying " + carrying(c));
                System.out.println("[menu] cover control retry -> carrying " + carrying(c));
            }
            c.ctx().waitTicks(6);
            // The Hx menu answers like Hypixel's: it records the click and resyncs the slot, so the item does not stay on
            // the cursor. The proof the overlay acted is the server's own record of exactly that click.
            if (carrying(c)) {
                press(c, 0, 0); // put it back if this menu did let it be taken
            }
            List<JsonObject> all = c.events("container.click");
            List<JsonObject> ctl = all.subList(Math.min(s0, all.size()), all.size());
            String ctlLine = "control: Inventory panel square of slot " + pick + " at " + Math.round(panelCell[0]) + ","
                    + Math.round(panelCell[1]) + " -> server " + TermHiddenSlotCases.clicks(ctl) + ", item now " + itemAt(c, pick);
            System.out.println("[menu] cover " + ctlLine);
            c.note(ctlLine);
            c.check(!ctl.isEmpty() && ctl.get(0).get("slot").getAsInt() == pick && ctl.get(0).get("button").getAsInt() == 0
                    && ctl.get(0).get("input").getAsString().equals("PICKUP"),
                    "the overlay's own Inventory panel did not send the click on slot " + pick + ": " + ctlLine);
        } finally {
            c.ctx().getInput().setCursorPos(2, 2);
            c.onClient(mc -> Mod.staticCall(SFEAT, "setFocus", (Object) null));
            AuctionHouseCases.setWindow(c, win[0], win[1], win[2]);
            MenuKit.reset(c);
            InventoryCases.clearInv(c);
        }
        c.check(failures.isEmpty(), failures.size() + " problem(s): " + failures);
    }

    // ---- full-screen reskins ------------------------------------------------------------------------------------

    /** The first player-inventory menu slot, given an item; probes hover + keys there. */
    static void probeReskin(Session c, String where, String isHidingClass, List<String> failures) {
        boolean hiding = c.onClient(mc -> (Boolean) Mod.staticCall(isHidingClass, "isHiding", McCompat.screen(mc)));
        if (!hiding) {
            failures.add(where + ": the reskin is not drawn (isHiding false) - nothing was tested");
            return;
        }
        if (hasCover(c)) {
            boolean all = c.onClient(mc -> (Boolean) Mod.staticCall(COVER, "coversAll", McCompat.screen(mc)));
            if (!all) {
                failures.add(where + ": ContainerCover.coversAll is false while the reskin hides the menu");
            }
        }
        int size = c.onClient(mc -> ((AbstractContainerScreen<?>) McCompat.screen(mc)).getMenu().slots.size());
        int menuIndex = size - 36 + 4; // main inventory, inventory slot 13
        int inv = invIndex(c, menuIndex);
        giveInto(c, inv, menuIndex, "minecraft:blaze_rod");
        double[] at = slotCentre(c, menuIndex);
        probe(c, where, menuIndex, at[0], at[1], false, null, failures);
    }

    static void reskins(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        List<String> failures = new ArrayList<>();
        int checked = 0;
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("spiritleap.SpiritLeapOverlayConfig", "Enabled", true).set("auction.AuctionConfig", "AhEnabled", true)
                    .set("auction.AuctionConfig", "BazaarEnabled", true);
            // AH browser
            AuctionHouseCases.show(c, "menus.ah-browser", "BROWSER");
            probeReskin(c, "AH reskin (Auctions Browser)", "auction.ah.AhReskin", failures);
            checked++;
            MenuKit.reset(c);
            InventoryCases.clearInv(c);
            // Bazaar category
            BazaarReskinCases.open(c, "menus.reskin-bazaar-category", "CATEGORY");
            probeReskin(c, "Bazaar reskin (category)", "bazaar.BazaarReskin", failures);
            checked++;
            MenuKit.reset(c);
            InventoryCases.clearInv(c);
            // Spirit Leap
            MenuKit.show(c, MenuKit.menu("menus.spirit-leap"));
            MenuKit.awaitScreen(c, "Spirit Leap", 100);
            c.waitUntil("the leap overlay to hide the menu", mc ->
                    (Boolean) Mod.staticCall("spiritleap.SpiritLeapOverlayFeature", "isHiding", McCompat.screen(mc)), 100);
            c.ctx().waitTicks(10);
            probeReskin(c, "Spirit Leap overlay", "spiritleap.SpiritLeapOverlayFeature", failures);
            checked++;
            MenuKit.reset(c);
            InventoryCases.clearInv(c);
            // The sorter's locked inventory (a client screen over the player's own inventory menu).
            c.hx().give(13, "minecraft:blaze_rod");
            c.waitUntil("the blaze rod in inventory slot 13", mc -> !mc.player.getInventory().getItem(13).isEmpty(), 40);
            // Control first: the plain vanilla inventory, same slot, must hover - else the sorter probe could not see one.
            c.onClient(mc -> {
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
                return null;
            });
            c.waitUntil("the vanilla inventory", mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen, 40);
            c.ctx().waitTicks(6);
            int vIdx = c.onClient(mc -> {
                for (Slot sl : ((AbstractContainerScreen<?>) McCompat.screen(mc)).getMenu().slots) {
                    if (sl.container == mc.player.getInventory() && sl.getContainerSlot() == 13) {
                        return sl.index;
                    }
                }
                return -1;
            });
            double[] vAt = slotCentre(c, vIdx);
            cursor(c, vAt[0], vAt[1]);
            Integer vHover = hovered(c);
            c.note("control: vanilla inventory slot " + vIdx + " at " + Math.round(vAt[0]) + "," + Math.round(vAt[1]) + " hovers as " + vHover);
            if (vHover == null || vHover != vIdx) {
                failures.add("sorter control: the vanilla inventory's slot " + vIdx + " does not hover (" + vHover + ") - the sorter probe would mean nothing");
            }
            c.onClient(mc -> {
                Object screen;
                try {
                    screen = Mod.cls("invsort.SortingInventoryScreen").getConstructor(net.minecraft.world.entity.player.Player.class)
                            .newInstance(mc.player);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("cannot open SortingInventoryScreen: " + e, e);
                }
                McCompat.setScreen(mc, (net.minecraft.client.gui.screens.Screen) screen);
                return null;
            });
            c.waitUntil("the sorting inventory", mc -> McCompat.screen(mc) != null
                    && McCompat.screen(mc).getClass().getSimpleName().equals("SortingInventoryScreen"), 40);
            c.ctx().waitTicks(6);
            int sortIdx = c.onClient(mc -> {
                List<Slot> slots = ((AbstractContainerScreen<?>) McCompat.screen(mc)).getMenu().slots;
                for (Slot sl : slots) {
                    if (sl.container == mc.player.getInventory() && sl.getContainerSlot() == 13) {
                        return sl.index;
                    }
                }
                return -1;
            });
            if (hasCover(c)) {
                boolean all = c.onClient(mc -> (Boolean) Mod.staticCall(COVER, "coversAll", McCompat.screen(mc)));
                if (!all) {
                    failures.add("sorter: ContainerCover.coversAll is false on SortingInventoryScreen");
                }
            }
            double[] at = slotCentre(c, sortIdx);
            String sg = c.onClient(mc -> { Object s = McCompat.screen(mc); return "sorter left " + Mod.field(s, "leftPos") + " top " + Mod.field(s, "topPos") + " gui " + mc.getWindow().getGuiScaledWidth(); });
            c.note("sorter geometry: " + sg + "; slot centre " + Math.round(at[0]) + "," + Math.round(at[1]) + "; probing at the vanilla point " + Math.round(vAt[0]) + "," + Math.round(vAt[1]));
            at = vAt;
            probe(c, "Sorting inventory", sortIdx, at[0], at[1], false, null, failures);
            checked++;
        } finally {
            c.ctx().getInput().setCursorPos(2, 2);
            c.ctx().runOnClient(mc -> {
                if (McCompat.screen(mc) != null && McCompat.screen(mc).getClass().getSimpleName().equals("SortingInventoryScreen")) {
                    McCompat.setScreen(mc, null);
                }
            });
            MenuKit.reset(c);
            InventoryCases.clearInv(c);
        }
        c.check(checked == 4, "only " + checked + " of 4 reskins were probed");
        c.check(failures.isEmpty(), failures.size() + " problem(s): " + failures);
    }

    // ---- panels over a menu ---------------------------------------------------------------------------------------

    static double[] moveAndRead(Session c, Object hud, String id, int x, int y) {
        c.onClient(mc -> {
            Mod.call(hud, "setPosition", id, x, y);
            return null;
        });
        c.ctx().waitTicks(4);
        return rectOf(c, "hud." + id);
    }

    static double[] rectOf(Session c, String owner) {
        return c.onClient(mc -> (double[]) Mod.staticCall(COVER, "rectOf", owner));
    }

    /** First menu slot whose center lies inside {@code r} (1 unit in from its edge), or -1. */
    static double[] slotInside(Session c, double[] r) {
        return c.onClient(mc -> {
            AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
            double l = ((Number) Mod.field(s, "leftPos")).doubleValue();
            double t = ((Number) Mod.field(s, "topPos")).doubleValue();
            for (Slot sl : s.getMenu().slots) {
                double x0 = Math.max(l + sl.x, r[0]), x1 = Math.min(l + sl.x + 16, r[2]);
                double y0 = Math.max(t + sl.y, r[1]), y1 = Math.min(t + sl.y + 16, r[3]);
                if (x1 - x0 >= 3 && y1 - y0 >= 3) {
                    return new double[]{sl.index, (x0 + x1) / 2, (y0 + y1) / 2};
                }
            }
            String first = s.getMenu().slots.isEmpty() ? "none" : "slot0 at " + (l + s.getMenu().slots.get(0).x) + ","
                    + (t + s.getMenu().slots.get(0).y) + " of " + s.getMenu().slots.size() + " (left " + l + ", top " + t + ")";
            return new double[]{-1, 0, 0, first.length()};
        });
    }

    /** First menu slot whose box touches none of {@code rects}, or -1. */
    static int slotOutside(Session c, List<double[]> rects) {
        return c.onClient(mc -> {
            AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
            double l = ((Number) Mod.field(s, "leftPos")).doubleValue();
            double t = ((Number) Mod.field(s, "topPos")).doubleValue();
            for (Slot sl : s.getMenu().slots) {
                boolean clear = true;
                for (double[] r : rects) {
                    if (l + sl.x < r[2] && l + sl.x + 16 > r[0] && t + sl.y < r[3] && t + sl.y + 16 > r[1]) {
                        clear = false;
                    }
                }
                if (clear) {
                    return sl.index;
                }
            }
            return -1;
        });
    }

    /** Probes a panel: waits for its rect, finds a slot under it, probes right/middle/shift clicks and keys there. */
    static double[] probePanel(Session c, String owner, String where, List<String> failures) {
        c.waitUntil(owner + " to report its rect", mc -> Mod.staticCall(COVER, "rectOf", owner) != null, 60);
        double[] r = rectOf(c, owner);
        double[] hit = slotInside(c, r);
        int idx = (int) hit[0];
        String rect = Math.round(r[0]) + "," + Math.round(r[1]) + ".." + Math.round(r[2]) + "," + Math.round(r[3]);
        if (idx < 0) {
            String slots = c.onClient(mc -> {
                AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
                return s.getClass().getSimpleName() + " '" + s.getTitle().getString() + "' left " + Mod.field(s, "leftPos")
                        + " top " + Mod.field(s, "topPos") + ", " + s.getMenu().slots.size() + " slots, slot0 at "
                        + (s.getMenu().slots.isEmpty() ? "-" : s.getMenu().slots.get(0).x + "," + s.getMenu().slots.get(0).y);
            });
            failures.add(where + ": no slot lies under the panel " + rect + " (" + slots + ") - nothing to probe");
            return r;
        }
        int inv = invIndex(c, idx);
        if (inv >= 0 && "empty".equals(itemAt(c, idx))) {
            giveInto(c, inv, idx, "minecraft:blaze_rod");
        }
        double[] at = {hit[1], hit[2]};
        c.note(where + ": panel " + rect + ", slot " + idx + " under it, probed at " + Math.round(at[0]) + "," + Math.round(at[1]));
        // Left clicks belong to the panel itself (the browser opens an item there); right, middle and shift are the ones
        // that used to fall through to the slot.
        String before = itemAt(c, idx);
        int server0 = c.events("container.click").size();
        cursor(c, at[0], at[1]);
        Integer h = hovered(c);
        press(c, 1, 0);
        press(c, 2, 0);
        press(c, 1, 1);
        c.ctx().getInput().pressKey(o -> o.keyHotbarSlots[8]);
        c.ctx().waitTicks(3);
        c.ctx().getInput().pressKey(o -> o.keyDrop);
        c.ctx().waitTicks(6);
        List<JsonObject> sent = c.events("container.click");
        List<JsonObject> mine = sent.subList(Math.min(server0, sent.size()), sent.size());
        String line = where + " (slot " + idx + ", covered by " + covering(c, at[0], at[1]) + "): hoveredSlot " + h
                + "; right/middle/shift-right clicks, keys 9/drop -> server " + TermHiddenSlotCases.clicks(mine)
                + "; item " + before + " -> " + itemAt(c, idx);
        System.out.println("[menu] cover " + line);
        c.note(line);
        if (h != null || !mine.isEmpty() || !before.equals(itemAt(c, idx)) || carrying(c)) {
            failures.add(line);
        }
        return r;
    }

    static void panels(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        List<String> failures = new ArrayList<>();
        if (!hasCover(c)) {
            c.check(false, "this jar has no " + COVER + " (from before the fix); the panel cases need its rect reports");
            return;
        }
        Object hud = c.onClient(mc -> Mod.cfg("hud.HudConfig"));
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(SCFG, "Enabled", false)
                    .set("itembrowser.ItemBrowserConfig", "Enabled", true).set("itembrowser.ItemBrowserConfig", "InventoryOnly", false)
                    .set("itembrowser.ItemBrowserConfig", "Align", Mod.enumValue("itembrowser.ItemBrowserConfig$HorizontalAlign", "CENTER"))
                    .set("inventorysearch.InventorySearchConfig", "Enabled", true);
            MenuKit.show(c, MenuKit.menu("menus.ender-chest-1"));
            MenuKit.awaitScreen(c, "Ender Chest (1/9)", 100);
            c.ctx().waitTicks(6);
            List<double[]> rects = new ArrayList<>();
            rects.add(probePanel(c, "itembrowser.panel", "Item Browser (centered over the chest)", failures));
            rects.add(probePanel(c, "inventorysearch.bar", "Inventory Search bar", failures));
            // Control: a slot no panel touches still hovers, so the probes above could have seen a hover.
            int free = slotOutside(c, rects);
            if (free < 0) {
                failures.add("no slot lies outside every panel - the hover control could not run");
            } else {
                double[] at = slotCentre(c, free);
                cursor(c, at[0], at[1]);
                Integer h = hovered(c);
                c.note("control: slot " + free + " outside the panels hovers as " + h);
                if (h == null || h != free) {
                    failures.add("control: slot " + free + " outside every panel does not hover (" + h + ")");
                }
            }
            MenuKit.reset(c);

            // Listing Helper: drawn beside the chest; must report where it is.
            cfg.set("auction.AuctionConfig", "ListingHelperEnabled", true)
                    .set("itembrowser.ItemBrowserConfig", "Enabled", false)
                    .set("inventorysearch.InventorySearchConfig", "Enabled", false);
            MenuKit.show(c, MenuKit.menu("menus.create-bin-auction"));
            MenuKit.awaitScreen(c, "Create BIN Auction", 100);
            c.waitUntil("the Listing Helper to report its rect", mc -> Mod.staticCall(COVER, "rectOf", "auction.listinghelper") != null, 60);
            double[] lh = rectOf(c, "auction.listinghelper");
            c.note("Listing Helper panel " + Math.round(lh[0]) + "," + Math.round(lh[1]) + ".." + Math.round(lh[2]) + "," + Math.round(lh[3]));
            MenuKit.reset(c);
        } finally {
            c.ctx().getInput().setCursorPos(2, 2);
            MenuKit.reset(c);
            InventoryCases.clearInv(c);
        }

        // Croesus profit panel (a ContainerOverlayElement, as the Chocolate Factory line is): moved over the run view's slots.
        int[] oldPos = c.onClient(mc -> (int[]) Mod.call(hud, "getPosition", "croesus_profit_panel", 0, 0));
        try (AutoCloseable w = CroesusPriceCases.priced(c)) {
            MenuKit.show(c, CroesusPriceCases.runView("§62,000,000 Coins"));
            MenuKit.awaitScreen(c, CroesusPriceCases.RUN_TITLE, 100);
            c.waitUntil("the profit panel to report its rect", mc -> Mod.staticCall(COVER, "rectOf", "hud.croesus_profit_panel") != null, 80);
            c.ctx().waitTicks(4);
            double[] g = geom(c);
            // A saved HUD position is not GUI units one to one (the HUD's own scaling), so measure how the drawn rect
            // answers two positions and solve for the one that puts the panel's corner on slot 0's box.
            double[] r1 = moveAndRead(c, hud, "croesus_profit_panel", 20, 20);
            double[] r2 = moveAndRead(c, hud, "croesus_profit_panel", 120, 120);
            double kx = (r2[0] - r1[0]) / 100.0, ky = (r2[1] - r1[1]) / 100.0;
            double tx = g[0] + 8 + 2, ty = g[1] + 18 + 2;
            int px = (int) Math.round(20 + (tx - r1[0]) / (kx == 0 ? 1 : kx));
            int py = (int) Math.round(20 + (ty - r1[1]) / (ky == 0 ? 1 : ky));
            double[] r3 = moveAndRead(c, hud, "croesus_profit_panel", px, py);
            c.note("profit panel: position 20,20 -> rect " + Math.round(r1[0]) + "," + Math.round(r1[1]) + "; 120,120 -> "
                    + Math.round(r2[0]) + "," + Math.round(r2[1]) + "; " + px + "," + py + " -> " + Math.round(r3[0]) + ","
                    + Math.round(r3[1]) + " (target " + Math.round(tx) + "," + Math.round(ty) + ")");
            probePanel(c, "hud.croesus_profit_panel", "Croesus profit panel (moved over the run view)", failures);
        } finally {
            c.onClient(mc -> {
                // Back where it was (the client's config is the testkit's own, wiped each run).
                Mod.call(hud, "setPosition", "croesus_profit_panel", oldPos[0], oldPos[1]);
                return null;
            });
            c.ctx().getInput().setCursorPos(2, 2);
            MenuKit.reset(c);
        }
        c.check(failures.isEmpty(), failures.size() + " problem(s): " + failures);
    }
}

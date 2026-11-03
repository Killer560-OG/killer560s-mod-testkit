package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 1103-ui-protect-show-star (mod protectstar-1102): killer560, 2026-11-03: "for item protect add an option to not show
 * the star as well." A protected item in the hotbar (slot 0) and one in the main inventory (slot 9), unprotected
 * controls beside each. Show Star ON: both protected slots carry the star's exact pixels and nothing else on screen
 * is star-colored. Show Star OFF: not one star-colored pixel on the screen, and protection still acts with the star
 * hidden - Q over the protected inventory slot (real key, vanilla's THROW path) keeps it while the same Q drops the
 * control, the drop key in the hotbar keeps the protected item while it drops the control, and a Salvage menu's
 * shift-click on it is blocked while the control's is not. The switch survives a save and a reload from disk.
 * Own class and block for merges.
 */
final class ProtectStarCases {

    static final String SHOW_STAR = "1103-ui-protect-show-star";
    static final String[] CASES = {SHOW_STAR};

    private static final String IPC = "itemprotect.ItemProtectConfig";
    private static final int MAGENTA = 0xFF00FF;
    private static final int KEY_Q = 81; // GLFW_KEY_Q, vanilla's default drop key

    private ProtectStarCases() {
    }

    private static int magentaTotal(BufferedImage img) {
        int total = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (InvBatchCases.rgb(img, x, y) == MAGENTA) {
                    total++;
                }
            }
        }
        return total;
    }

    private static boolean has(UiCase c, int slot, net.minecraft.world.item.Item item) {
        return c.onClient(mc -> mc.player.getInventory().getItem(slot).is(item));
    }

    static void showStar(UiCase c) throws Exception {
        Path cfgPath = InvBatchCases.configPath(c, IPC);
        byte[] old = InvBatchCases.read(cfgPath);
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        // inventory slot, label, protected?
        int[] invSlot = {0, 1, 9, 10};
        String[] labels = {"protected hotbar item", "hotbar control", "protected inventory item", "inventory control"};
        boolean[] prot = {true, false, true, false};
        int n = invSlot.length;
        try {
            InvBatchCases.survival(c);
            Map<Integer, ItemStack> items = new LinkedHashMap<>();
            items.put(0, InvBatchCases.sb(Items.DIAMOND_SWORD, "HYPERION", "ps-hot-1", 0));
            items.put(1, InvBatchCases.sb(Items.DIAMOND, "DIAMOND", "ps-hot-ctrl", 0));
            items.put(9, InvBatchCases.sb(Items.GOLDEN_SWORD, "LIVID_DAGGER", "ps-inv-1", 0));
            items.put(10, InvBatchCases.sb(Items.EMERALD, "EMERALD", "ps-inv-ctrl", 0));
            InvBatchCases.inventory(c, items);
            c.onClient(mc -> {
                Object cfg = Mod.cfg(IPC);
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setProtectItemEnabled", true);
                Mod.call(cfg, "setProtectStarredEnabled", false);
                Mod.call(cfg, "setUseItemIdFallback", false);
                Mod.call(cfg, "setLockInPlace", false);
                Mod.call(cfg, "setPreventHotbarDropEnabled", true);
                Mod.call(cfg, "setConfirmToForce", false);
                Mod.call(cfg, "setBlockEveryHotbarDrop", false);
                Mod.call(cfg, "setBlockSound", false);
                Mod.call(cfg, "addProtectedKey", "ps-hot-1");
                Mod.call(cfg, "addProtectedKey", "ps-inv-1");
                Mod.call(cfg, "setProtectedColor", 0xFF000000 | MAGENTA);
                Mod.call(cfg, "setShowStar", true);
                return null;
            });
            for (int i = 0; i < n; i++) {
                final int s = invSlot[i];
                boolean p = c.onClient(mc -> (Boolean) Mod.staticCall("itemprotect.ItemProtect", "isExplicitlyProtected",
                        mc.player.getInventory().getItem(s)));
                c.check(p == prot[i], labels[i] + ": isExplicitlyProtected " + p + ", want " + prot[i]);
            }
            String[] star = (String[]) Mod.field("itemprotect.ItemProtectFeature", "STAR");
            int cells = 0;
            for (String row : star) {
                cells += (int) row.chars().filter(ch -> ch == '#').count();
            }
            HudEditorCases.setWindow(c, 1280, 720, 2);

            // The in-game hotbar (no screen): recorded only - the star is drawn by menu slots, never the HUD hotbar.
            c.ctx().getInput().setCursorPos(2, 2);
            c.ticks(5);
            int hudOn = magentaTotal(InvBatchCases.shot(c, "hud-star-on"));
            c.note("HUD hotbar with Show Star ON: star-colored px " + hudOn);

            c.onClient(mc -> {
                McCompat.setScreen(mc, new InventoryScreen(mc.player));
                return null;
            });
            c.ticks(10);
            int[][] pos = c.onClient(mc -> {
                AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
                int left = (Integer) Mod.field(s, "leftPos");
                int top = (Integer) Mod.field(s, "topPos");
                int[][] out = new int[n][];
                for (Slot slot : s.getMenu().slots) {
                    for (int i = 0; i < n; i++) {
                        if (slot.container == mc.player.getInventory() && slot.getContainerSlot() == invSlot[i]) {
                            out[i] = new int[]{left + slot.x, top + slot.y};
                        }
                    }
                }
                return out;
            });
            for (int i = 0; i < n; i++) {
                c.check(pos[i] != null, "no screen slot for inventory slot " + invSlot[i]);
            }

            // ---- Show Star ON: today's look, exact pixels ----
            BufferedImage on = InvBatchCases.shot(c, "inventory-star-on");
            double ppu = on.getWidth() / (double) c.onClient(mc -> McCompat.screen(mc).width);
            int want = (int) Math.round(cells * ppu * ppu);
            int protectedSlots = 0;
            for (int i = 0; i < n; i++) {
                int got = LockStarHeadCases.starCount(on, pos[i], ppu, false);
                int wrong = LockStarHeadCases.wrongCells(on, pos[i], ppu, star, false);
                c.note(String.format(Locale.ROOT, "Show Star ON, %s: star px %d (want %d), cells not the star %d",
                        labels[i], got, prot[i] ? want : 0, prot[i] ? wrong : 0));
                if (prot[i]) {
                    protectedSlots++;
                    if (got != want || wrong != 0) {
                        c.problem("Show Star ON, " + labels[i] + ": star " + got + " px, want " + want + "; " + wrong + " cell(s) wrong");
                    }
                } else if (got != 0) {
                    c.problem("Show Star ON, " + labels[i] + " shows a star (" + got + " px)");
                }
            }
            int totalOn = magentaTotal(on);
            c.note("Show Star ON: star-colored px on screen " + totalOn + " (want " + protectedSlots * want + ")");
            if (totalOn != protectedSlots * want) {
                c.problem("Show Star ON: star-colored px on screen " + totalOn + ", want " + protectedSlots * want);
            }

            // ---- Show Star OFF: no star anywhere ----
            c.onClient(mc -> {
                Mod.call(Mod.cfg(IPC), "setShowStar", false);
                return null;
            });
            c.ticks(3);
            BufferedImage off = InvBatchCases.shot(c, "inventory-star-off");
            int totalOff = magentaTotal(off);
            c.note("Show Star OFF: star-colored px on screen " + totalOff + " (want 0)");
            if (totalOff != 0) {
                c.problem("Show Star OFF: " + totalOff + " star-colored px still on screen");
            }
            for (int i = 0; i < n; i++) {
                if (prot[i]) {
                    int got = LockStarHeadCases.starCount(off, pos[i], ppu, false);
                    if (got != 0) {
                        c.problem("Show Star OFF, " + labels[i] + ": star still drawn (" + got + " px)");
                    }
                }
            }

            // ---- protection still acts with the star hidden: Q over the slot (the real key, vanilla's THROW) ----
            long mark = LogTap.mark();
            LockStarHeadCases.hover(c, pos[2]);
            c.ctx().getInput().pressKey(dev.testkit.compat.McKeys.nat(KEY_Q));
            c.ticks(10);
            boolean keptInv = has(c, 9, Items.GOLDEN_SWORD);
            List<String> blockChat = LogTap.since(mark).stream().filter(l -> l.contains("Protect")).toList();
            c.note("Q over the protected inventory item (star hidden): kept " + keptInv + "; chat " + blockChat);
            c.check(keptInv, "Q over the protected inventory item dropped it with Show Star OFF");
            LockStarHeadCases.hover(c, pos[3]);
            c.ctx().getInput().pressKey(dev.testkit.compat.McKeys.nat(KEY_Q));
            c.ticks(10);
            boolean ctrlGone = c.onClient(mc -> mc.player.getInventory().getItem(10).isEmpty());
            c.note("Q over the inventory control: dropped " + ctrlGone);
            c.check(ctrlGone, "Q over the unprotected control did not drop it - the Q path never acted, the block above proves nothing");

            // ---- a Salvage menu's shift-click (a loss screen): the guard's own decision, no packet sent ----
            boolean[] salvage = c.onClient(mc -> {
                ChestMenu menu = ChestMenu.sixRows(0, mc.player.getInventory(), new net.minecraft.world.SimpleContainer(54));
                ContainerScreen s = new ContainerScreen(menu, mc.player.getInventory(), Component.literal("Salvage Items"));
                boolean[] out = new boolean[2];
                for (Slot slot : menu.slots) {
                    if (slot.container == mc.player.getInventory() && slot.getContainerSlot() == 0) {
                        out[0] = (Boolean) Mod.staticCall("itemprotect.ItemProtect", "shouldBlockSlotClick",
                                s, slot, slot.index, 0, ContainerInput.QUICK_MOVE);
                    }
                    if (slot.container == mc.player.getInventory() && slot.getContainerSlot() == 1) {
                        out[1] = (Boolean) Mod.staticCall("itemprotect.ItemProtect", "shouldBlockSlotClick",
                                s, slot, slot.index, 0, ContainerInput.QUICK_MOVE);
                    }
                }
                return out;
            });
            c.note("Salvage shift-click (star hidden): protected blocked " + salvage[0] + ", control blocked " + salvage[1]);
            c.check(salvage[0], "Salvage shift-click of the protected hotbar item not blocked with Show Star OFF");
            c.check(!salvage[1], "Salvage shift-click of the control was blocked");

            // ---- the drop key in the hotbar ----
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                mc.player.getInventory().setSelectedSlot(0);
                return null;
            });
            c.ticks(3);
            c.onClient(mc -> {
                McCompat.dropSelected(mc, false);
                return null;
            });
            c.ticks(10);
            boolean keptHot = has(c, 0, Items.DIAMOND_SWORD);
            c.onClient(mc -> {
                mc.player.getInventory().setSelectedSlot(1);
                return null;
            });
            c.ticks(3);
            c.onClient(mc -> {
                McCompat.dropSelected(mc, false);
                return null;
            });
            c.ticks(10);
            boolean hotCtrlGone = c.onClient(mc -> mc.player.getInventory().getItem(1).isEmpty());
            c.note("drop key (star hidden): protected hotbar item kept " + keptHot + ", control dropped " + hotCtrlGone);
            c.check(keptHot, "the drop key dropped the protected hotbar item with Show Star OFF");
            c.check(hotCtrlGone, "the drop key did not drop the control - the drop path never acted");

            // ---- the switch survives a save and a reload from disk ----
            boolean reloadedOff = c.onClient(mc -> {
                Mod.call(Mod.cfg(IPC), "save");
                Mod.staticCall(IPC, "load");
                return (Boolean) Mod.call(Mod.cfg(IPC), "isShowStar");
            });
            String fileOff = new String(Files.readAllBytes(cfgPath), StandardCharsets.UTF_8);
            boolean keyOff = fileOff.replaceAll("\\s", "").contains("\"showStar\":false");
            c.note("after save + load with Show Star OFF: isShowStar " + reloadedOff + ", file has showStar:false " + keyOff);
            c.check(!reloadedOff && keyOff, "Show Star OFF did not survive a reload (isShowStar " + reloadedOff + ", key " + keyOff + ")");
            boolean reloadedOn = c.onClient(mc -> {
                Mod.call(Mod.cfg(IPC), "setShowStar", true);
                Mod.call(Mod.cfg(IPC), "save");
                Mod.staticCall(IPC, "load");
                return (Boolean) Mod.call(Mod.cfg(IPC), "isShowStar");
            });
            c.check(reloadedOn, "Show Star ON did not survive a reload");
            // A file without the key (every file from before the switch) loads with the star shown.
            boolean legacy = c.onClient(mc -> {
                try {
                    String s = new String(Files.readAllBytes(cfgPath), StandardCharsets.UTF_8)
                            .replaceAll("\"showStar\"\\s*:\\s*(true|false)\\s*,?", "");
                    Files.write(cfgPath, s.getBytes(StandardCharsets.UTF_8));
                } catch (java.io.IOException e) {
                    throw new AssertionError(e);
                }
                Mod.staticCall(IPC, "load");
                return (Boolean) Mod.call(Mod.cfg(IPC), "isShowStar");
            });
            c.note("a file without showStar loads isShowStar " + legacy);
            c.check(legacy, "a file from before the switch loads with the star hidden");
            c.check(c.problemCount() == 0, c.problemCount() + " problem(s), see above");
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            HudEditorCases.restoreWindow(c, window, oldGui);
            InvBatchCases.restoreConfig(c, IPC, cfgPath, old);
            InvBatchCases.inventory(c, Map.of());
        }
    }
}

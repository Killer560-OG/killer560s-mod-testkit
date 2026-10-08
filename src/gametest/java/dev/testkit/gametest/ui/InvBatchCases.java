package dev.testkit.gametest.ui;

import com.mojang.blaze3d.platform.Window;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.GameType;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 581-587: killer560's 2026-10-08 inventory batch (mod branch inv-batch).
 * <ul>
 *   <li>581 Item Protect's star: exactly the star's pixels, in the top-right corner of a protected slot, and nothing on
 *       a slot protected only for being dungeon-starred (screenshot at GUI 2).</li>
 *   <li>582 Slot Lock is gone (API, tab rows, tooltips) and an old file's locked slots become protected UUIDs.</li>
 *   <li>584 the Inventory Sorter's layouts menu by real mouse and keys: swap, drag, type a name, save, select, rename,
 *       bind, delete; the tab's rows; the pacing settings' persistence and the ms-to-ticks migration. Legit: no
 *       /invsort, no executor.</li>
 *   <li>586 Custom Items by real clicks: pick an item, colour every copy then this item only (DyedItemColor and the
 *       drawn pixels), Use Colour, Look, Armor Skin, head texture, remove, Reset All; an old Armour Recolour file
 *       loads and its removed keys are dropped.</li>
 *   <li>587 the Cosmetics category: what moved in and out, and the moved rows' tooltips.</li>
 * </ul>
 * GUI 2 screenshots are copied to {@code TESTKIT_INVBATCH_SHOTS} when it is set.
 */
final class InvBatchCases {

    private static final String IPC = "itemprotect.ItemProtectConfig";
    private static final String ISC = "invsort.InventorySorterConfig";
    private static final String ADC = "armourdye.ArmourDyeConfig";
    private static final int MAGENTA = 0xFF00FF;

    private InvBatchCases() {
    }

    // ==== shared =======================================================================================================

    static BufferedImage shot(UiCase c, String label) {
        InvStorageCases.awaitNoLoadingOverlay(c);
        c.onClient(mc -> {
            McCompat.clearChatAndToasts(mc);
            return null;
        });
        c.ticks(2);
        String name = c.name() + "-" + label;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        Path kept = dev.testkit.harness.Report.screenshot(name, taken);
        c.note("picture " + label + " -> " + (kept != null ? kept : taken));
        String dir = System.getenv("TESTKIT_INVBATCH_SHOTS");
        int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
        if (dir != null && !dir.isBlank() && gs == 2) {
            try {
                String mcv = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft")
                        .orElseThrow().getMetadata().getVersion().getFriendlyString();
                Path out = Path.of(dir).resolve(name + "-" + mcv + "-" + (Mod.isCheat() ? "cheat" : "legit") + ".png");
                Files.createDirectories(out.getParent());
                Files.copy(taken, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                c.note("copied to " + out);
            } catch (Exception | Error e) {
                c.note("could not copy " + name + ": " + e);
            }
        }
        try {
            return javax.imageio.ImageIO.read(taken.toFile());
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read screenshot " + taken, e);
        }
    }

    static int rgb(BufferedImage img, int x, int y) {
        if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) {
            return -1;
        }
        return img.getRGB(x, y) & 0xFFFFFF;
    }

    static int count(BufferedImage img, int x0, int y0, int x1, int y1, int colour) {
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if (rgb(img, x, y) == colour) {
                    n++;
                }
            }
        }
        return n;
    }

    static void serverPlayer(UiCase c, java.util.function.Consumer<net.minecraft.server.level.ServerPlayer> f) {
        c.onClient(mc -> {
            var server = mc.getSingleplayerServer();
            if (server == null || mc.player == null) {
                return null;
            }
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    f.accept(sp);
                }
            });
            return null;
        });
        c.ticks(5);
    }

    static void survival(UiCase c) {
        serverPlayer(c, sp -> {
            sp.setGameMode(GameType.SURVIVAL);
            sp.getAbilities().invulnerable = true;
            sp.getAbilities().mayfly = true;
            sp.getAbilities().flying = true;
            sp.onUpdateAbilities();
        });
        for (int i = 0; i < 100 && !c.onClient(mc -> mc.gameMode != null && mc.gameMode.canHurtPlayer()); i++) {
            c.ticks(1);
        }
    }

    /** A Skyblock-looking stack: custom_data {id, uuid?, upgrade_level?}. */
    static ItemStack sb(Item item, String id, String uuid, int upgradeLevel) {
        ItemStack s = new ItemStack(item);
        CompoundTag t = new CompoundTag();
        t.putString("id", id);
        if (uuid != null) {
            t.putString("uuid", uuid);
        }
        if (upgradeLevel > 0) {
            t.putInt("upgrade_level", upgradeLevel);
        }
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        return s;
    }

    /** Clears slots 0-40 and puts {@code items} in, on the server, and waits for the client to have them. */
    static void inventory(UiCase c, Map<Integer, ItemStack> items) {
        serverPlayer(c, sp -> {
            var inv = sp.getInventory();
            for (int i = 0; i < 41; i++) {
                inv.setItem(i, ItemStack.EMPTY);
            }
            items.forEach((slot, stack) -> inv.setItem(slot, stack.copy()));
            sp.inventoryMenu.broadcastChanges();
        });
        for (int i = 0; i < 60; i++) {
            boolean ok = c.onClient(mc -> {
                for (Map.Entry<Integer, ItemStack> e : items.entrySet()) {
                    if (!mc.player.getInventory().getItem(e.getKey()).is(e.getValue().getItem())) {
                        return false;
                    }
                }
                return true;
            });
            if (ok) {
                return;
            }
            c.ticks(1);
        }
        throw new AssertionError("[" + c.name() + "] the client never got the test items");
    }

    static Path configPath(UiCase c, String cls) {
        return c.onClient(mc -> (Path) R.getStatic(R.cls(cls), "CONFIG_PATH"));
    }

    static byte[] read(Path p) {
        try {
            return Files.exists(p) ? Files.readAllBytes(p) : null;
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    static void restoreConfig(UiCase c, String cls, Path p, byte[] old) {
        try {
            c.onClient(mc -> {
                try {
                    if (old == null) {
                        Files.deleteIfExists(p);
                    } else {
                        Files.write(p, old);
                    }
                } catch (java.io.IOException e) {
                    throw new AssertionError(e);
                }
                Mod.staticCall(cls, "load");
                return null;
            });
        } catch (Throwable t) {
            c.note("restore " + cls + ": " + UiCase.describe(t));
        }
    }

    static String text(Path p) {
        try {
            return Files.readString(p).replaceAll("\\s+", "");
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    /** A real left click at a mod screen's layout coordinates (through its Auto Scale factor). */
    static void click(UiCase c, Screen s, double lx, double ly) {
        cursor(c, s, lx, ly);
        c.ctx().getInput().pressMouse(0);
        c.ticks(3);
    }

    static void cursor(UiCase c, Screen s, double lx, double ly) {
        double[] a = c.onClient(mc -> {
            float f = ((Number) Mod.staticCall("hud.AutoScale", "appliedFactor", s)).floatValue();
            Window win = mc.getWindow();
            return new double[]{lx * f * win.getScreenWidth() / (double) win.getGuiScaledWidth(),
                    ly * f * win.getScreenHeight() / (double) win.getGuiScaledHeight()};
        });
        c.ctx().getInput().setCursorPos(a[0], a[1]);
        c.ticks(2);
    }

    static void clickWidget(UiCase c, Screen s, AbstractWidget w) {
        c.check(w != null, "no such control");
        click(c, s, w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0);
    }

    /** The first widget on {@code s} whose plain label starts with {@code prefix}. */
    static AbstractWidget widget(UiCase c, Screen s, String prefix) {
        return c.onClient(mc -> {
            for (GuiEventListener l : s.children()) {
                if (l instanceof AbstractWidget w && ModScreenDriver.label(w).startsWith(prefix)) {
                    return w;
                }
            }
            return null;
        });
    }

    static EditBox editBox(UiCase c, Screen s) {
        return c.onClient(mc -> {
            for (GuiEventListener l : s.children()) {
                if (l instanceof EditBox e) {
                    return e;
                }
            }
            return null;
        });
    }

    static List<String> tabLabels(UiCase c, String tabClass) {
        return c.onClient(mc -> {
            try {
                Object tab = Mod.cls(tabClass).getConstructor().newInstance();
                @SuppressWarnings("unchecked")
                List<AbstractWidget> ws = (List<AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0, 300,
                        (Runnable) () -> { });
                List<String> out = new ArrayList<>();
                for (AbstractWidget w : ws) {
                    out.add(ModScreenDriver.label(w));
                }
                return out;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    static Screen screen(UiCase c) {
        return c.onClient(McCompat::screen);
    }

    static void awaitScreen(UiCase c, String simpleName) {
        for (int i = 0; i < 60; i++) {
            Screen s = screen(c);
            if (s != null && s.getClass().getSimpleName().equals(simpleName)) {
                return;
            }
            c.ticks(1);
        }
        Screen s = screen(c);
        throw new AssertionError("[" + c.name() + "] " + simpleName + " never opened (screen: "
                + (s == null ? "none" : s.getClass().getName()) + ")");
    }

    static String identity(UiCase c, ItemStack s) {
        return c.onClient(mc -> (String) Mod.staticCall("autoroutes.ItemIdentity", "of", s));
    }

    // ==== 581 the star =================================================================================================

    static void star(UiCase c) throws Exception {
        Path cfgPath = configPath(c, IPC);
        byte[] old = read(cfgPath);
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            survival(c);
            Map<Integer, ItemStack> items = new LinkedHashMap<>();
            items.put(9, sb(Items.DIAMOND_SWORD, "HYPERION", "ib-star-1", 0));       // protected by UUID
            items.put(10, sb(Items.IRON_SWORD, "STARRED_GIANTS_SWORD", "ib-star-2", 5)); // starred only
            items.put(11, sb(Items.GOLDEN_SWORD, "ASPECT_OF_THE_END", "ib-star-3", 0)); // not protected
            inventory(c, items);
            c.onClient(mc -> {
                Object cfg = Mod.cfg(IPC);
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setProtectItemEnabled", true);
                Mod.call(cfg, "setProtectStarredEnabled", true);
                Mod.call(cfg, "setLockInPlace", false);
                Mod.call(cfg, "setProtectedColor", 0xFF000000 | MAGENTA);
                Mod.call(cfg, "addProtectedKey", "ib-star-1");
                return null;
            });
            ItemStack starred = c.onClient(mc -> mc.player.getInventory().getItem(10));
            boolean starredProtected = c.onClient(mc -> (Boolean) Mod.staticCall("itemprotect.ItemProtect", "isProtectedItem", starred));
            boolean starredExplicit = c.onClient(mc -> (Boolean) Mod.staticCall("itemprotect.ItemProtect", "isExplicitlyProtected", starred));
            c.check(starredProtected, "the starred item is not protected - Auto-Protect Starred is not on, the no-marker check would be vacuous");
            c.check(!starredExplicit, "the starred item counts as explicitly protected");

            String[] star = (String[]) Mod.field("itemprotect.ItemProtectFeature", "STAR");
            int cells = 0;
            for (String row : star) {
                cells += (int) row.chars().filter(ch -> ch == '#').count();
            }
            HudEditorCases.setWindow(c, 1280, 720, 2);
            int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
            c.check(gs == 2, "GUI scale " + gs);
            c.onClient(mc -> {
                McCompat.setScreen(mc, new InventoryScreen(mc.player));
                return null;
            });
            c.ticks(5);
            c.ctx().getInput().setCursorPos(2, 2); // hover nothing: the hovered slot's star is skipped under its tooltip
            c.ticks(3);
            int[][] pos = c.onClient(mc -> {
                AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
                int left = (Integer) Mod.field(s, "leftPos");
                int top = (Integer) Mod.field(s, "topPos");
                int[][] out = new int[3][];
                for (Slot slot : s.getMenu().slots) {
                    if (slot.container == mc.player.getInventory() && slot.getContainerSlot() >= 9
                            && slot.getContainerSlot() <= 11) {
                        out[slot.getContainerSlot() - 9] = new int[]{left + slot.x, top + slot.y};
                    }
                }
                return out;
            });
            BufferedImage img = shot(c, "inventory-gui2");
            int total = count(img, 0, 0, img.getWidth(), img.getHeight(), MAGENTA);
            int[] inSlot = new int[3];
            for (int i = 0; i < 3; i++) {
                inSlot[i] = count(img, pos[i][0] * gs, pos[i][1] * gs, (pos[i][0] + 16) * gs, (pos[i][1] + 16) * gs, MAGENTA);
            }
            // Cell by cell: the star's '#' cells are the colour, its '.' cells are not, in the slot's top-right corner.
            int x0 = pos[0][0] + 16 - star.length;
            int y0 = pos[0][1];
            int wrong = 0;
            for (int r = 0; r < star.length; r++) {
                for (int col = 0; col < star[r].length(); col++) {
                    boolean want = star[r].charAt(col) == '#';
                    boolean got = rgb(img, (x0 + col) * gs + gs / 2, (y0 + r) * gs + gs / 2) == MAGENTA;
                    if (want != got) {
                        wrong++;
                    }
                }
            }
            c.note(String.format(Locale.ROOT, "star colour pixels: whole screen %d, protected slot %d, starred-only slot %d, "
                    + "unprotected slot %d; star cells %d (expect %d px at GUI 2); cells drawn wrong: %d",
                    total, inSlot[0], inSlot[1], inSlot[2], cells, cells * gs * gs, wrong));
            c.check(inSlot[0] == cells * gs * gs, "protected slot has " + inSlot[0] + " star pixels, want " + cells * gs * gs);
            c.check(wrong == 0, wrong + " star cell(s) in the top-right corner are not the star's shape");
            c.check(inSlot[1] == 0, "the starred-only item has a marker (" + inSlot[1] + " px)");
            c.check(inSlot[2] == 0, "the unprotected item has a marker");
            c.check(total == cells * gs * gs, "star-coloured pixels elsewhere on screen: " + (total - inSlot[0]));
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            HudEditorCases.restoreWindow(c, window, oldGui);
            restoreConfig(c, IPC, cfgPath, old);
            inventory(c, Map.of());
        }
    }

    // ==== 582 Slot Lock gone + migration ===============================================================================

    static void slotLockGone(UiCase c) throws Exception {
        Path cfgPath = configPath(c, IPC);
        byte[] old = read(cfgPath);
        long mark = LogTap.mark();
        try {
            // The API.
            List<String> leftovers = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                for (java.lang.reflect.Method m : Mod.cls(IPC).getDeclaredMethods()) {
                    String n = m.getName().toLowerCase(Locale.ROOT);
                    if (n.contains("slotlock") || n.contains("slotlocked") || n.contains("lockstyle")
                            || n.contains("lockcolor") || n.contains("peekkey") || n.contains("protectediconenabled")) {
                        out.add(m.getName());
                    }
                }
                if (Mod.has("itemprotect.ItemProtectConfig$LockStyle")) {
                    out.add("ItemProtectConfig$LockStyle");
                }
                return out;
            });
            c.note("Slot Lock API left: " + leftovers);
            c.check(leftovers.isEmpty(), "Slot Lock API still in the jar: " + leftovers);

            // The tab, with every section open.
            c.onClient(mc -> {
                Object cfg = Mod.cfg(IPC);
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setProtectItemEnabled", true);
                Mod.call(cfg, "setPreventHotbarDropEnabled", true);
                return null;
            });
            List<String> labels = tabLabels(c, "gui.tab.ItemProtectTab");
            c.note("Item Protection rows: " + labels);
            for (String gone : new String[]{"Slot Lock", "Lock Key", "Marker", "Lock Color", "Clear Locks",
                    "Show Protected Key", "Lock Icon", "Highlight Color"}) {
                c.check(labels.stream().noneMatch(l -> l.startsWith(gone)), "the tab still has '" + gone + "'");
            }
            c.check(labels.stream().anyMatch(l -> l.startsWith("Lock In Place")), "no Lock In Place row");
            c.check(labels.stream().anyMatch(l -> l.startsWith("Star Color")), "no Star Color row");
            String tipLock = c.onClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "describe", "Item Protection", "Lock In Place: OFF"));
            String tipStar = c.onClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "describe", "Item Protection", "Star Color"));
            String tipOld = c.onClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "describe", "Item Protection", "Slot Lock: ON"));
            c.check(tipLock != null && tipStar != null, "new rows lack tooltips: " + tipLock + " / " + tipStar);
            c.check(tipOld == null, "a Slot Lock tooltip is still registered: " + tipOld);

            // Migration: an old file with Slot Lock on and three locked slots.
            Map<Integer, ItemStack> items = new LinkedHashMap<>();
            items.put(0, sb(Items.DIAMOND_SWORD, "HYPERION", "mig-0", 0));
            items.put(9, sb(Items.BOW, "TERMINATOR", "mig-9", 0));
            items.put(12, new ItemStack(Items.STONE));
            inventory(c, items);
            Files.writeString(cfgPath, "{\"enabled\":true,\"slotLockEnabled\":true,\"slotLockKey\":76,\"lockStyle\":\"BOTH\","
                    + "\"lockColor\":-43691,\"lockedSlots\":[0,9,12],\"protectItemEnabled\":false,\"protectKey\":-1,"
                    + "\"peekKey\":80,\"protectedIconEnabled\":true,\"protectedKeys\":[\"old-key\"],\"protectedNames\":[]}");
            String afterLoad = c.onClient(mc -> {
                Mod.staticCall(IPC, "load");
                Object cfg = Mod.cfg(IPC);
                return Mod.call(cfg, "getPendingSlotMigration") + "|" + Mod.call(cfg, "isProtectItemEnabledRaw") + "|"
                        + Mod.call(cfg, "isLockInPlace");
            });
            String rewritten = text(cfgPath);
            c.note("old file loaded: pending|protectItem|lockInPlace = " + afterLoad + "; rewritten file: " + rewritten);
            c.check(rewritten.contains("\"pendingSlotMigration\":[0,9,12]") && !rewritten.contains("slotLock")
                    && !rewritten.contains("lockedSlots") && !rewritten.contains("peekKey"),
                    "the rewritten file still has old keys or lost the pending list: " + rewritten);
            // The migration runs on the next client ticks, now that items with UUIDs are in the inventory.
            String keys = null;
            for (int i = 0; i < 40; i++) {
                c.ticks(1);
                keys = c.onClient(mc -> {
                    Object cfg = Mod.cfg(IPC);
                    return ((List<?>) Mod.call(cfg, "getPendingSlotMigration")).isEmpty()
                            ? String.valueOf(Mod.call(cfg, "getProtectedKeys")) : null;
                });
                if (keys != null) {
                    break;
                }
            }
            String finalFile = text(cfgPath);
            List<String> log = LogTap.since(mark).stream().filter(l -> l.contains("Slot Lock migration")).toList();
            c.note("after the migration tick: protected keys " + keys + "; log " + log + "; file " + finalFile);
            c.check(afterLoad.startsWith("[0, 9, 12]|true|true"), "load did not set pending/Protect Item/Lock In Place: " + afterLoad);
            c.check(keys != null, "the migration never ran");
            c.check(keys.contains("mig-0") && keys.contains("mig-9") && keys.contains("old-key"), "protected keys " + keys);
            c.check(!finalFile.contains("pendingSlotMigration"), "the pending list was not cleared from the file");
            c.check(log.stream().anyMatch(l -> l.contains("2 of 3")), "no '2 of 3' migration log line: " + log);

            // An old file whose Slot Lock was OFF migrates nothing.
            Files.writeString(cfgPath, "{\"enabled\":true,\"slotLockEnabled\":false,\"lockedSlots\":[0],\"protectItemEnabled\":false}");
            String off = c.onClient(mc -> {
                Mod.staticCall(IPC, "load");
                Object cfg = Mod.cfg(IPC);
                return Mod.call(cfg, "getPendingSlotMigration") + "|" + Mod.call(cfg, "isProtectItemEnabledRaw") + "|"
                        + Mod.call(cfg, "isLockInPlace");
            });
            c.note("old file with Slot Lock OFF: " + off);
            c.check(off.equals("[]|false|false"), "a switched-off Slot Lock migrated: " + off);
        } finally {
            restoreConfig(c, IPC, cfgPath, old);
            inventory(c, Map.of());
        }
    }

    // ==== 584 invsort editor ===========================================================================================

    static void invsortEditor(UiCase c) throws Exception {
        if (!Mod.isCheat()) {
            boolean root = c.onClient(mc -> {
                var d = net.fabricmc.fabric.impl.command.client.ClientCommandInternals.getActiveDispatcher();
                return d != null && d.getRoot().getChild("invsort") != null;
            });
            Object layout = c.onClient(mc -> {
                try {
                    return Mod.cls("invsort.InventoryLayout").getConstructor(String.class, Map.class)
                            .newInstance("x", Map.of(0, "DIAMOND"));
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            boolean started = c.onClient(mc -> (Boolean) Mod.staticCall("invsort.InventorySorterExecutor", "start", layout));
            c.note("legit jar: /invsort registered " + root + ", executor start " + started);
            c.check(!root, "the legit jar registers /invsort");
            c.check(!started, "the legit jar's sorter started");
            return;
        }
        Path cfgPath = configPath(c, ISC);
        byte[] old = read(cfgPath);
        Path dir = c.onClient(mc -> (Path) Mod.staticCall("invsort.InventoryLayoutStore", "directory"));
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            // Settings persist; the old ms pair converts once.
            String round = c.onClient(mc -> {
                Object cfg = Mod.cfg(ISC);
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setTicksBetweenMoves", 7);
                Mod.call(cfg, "setRandomExtraTicks", 3);
                Mod.call(cfg, "setLayoutKey", "ib584 Bound", 75);
                Mod.call(cfg, "save");
                Mod.staticCall(ISC, "load");
                Object fresh = Mod.cfg(ISC);
                return Mod.call(fresh, "getTicksBetweenMoves") + "," + Mod.call(fresh, "getRandomExtraTicks") + ","
                        + Mod.call(fresh, "getLayoutKey", "ib584 Bound") + "," + Mod.call(fresh, "isEnabledRaw");
            });
            c.check(round.equals("7,3,75,true"), "settings after save+load: " + round);
            Files.writeString(cfgPath, "{\"enabled\":true,\"minDelayMs\":120,\"maxDelayMs\":280}");
            String conv = c.onClient(mc -> {
                Mod.staticCall(ISC, "load");
                Object cfg = Mod.cfg(ISC);
                return Mod.call(cfg, "getTicksBetweenMoves") + "," + Mod.call(cfg, "getRandomExtraTicks");
            });
            c.note("settings round trip " + round + "; old minDelayMs 120 / maxDelayMs 280 -> ticks,random " + conv);
            c.check(conv.equals("2,3"), "old ms delays converted to " + conv + ", want 2,3");

            // The tab.
            List<String> labels = tabLabels(c, "gui.tab.InventorySorterTab");
            c.note("Inventory Sorter rows: " + labels);
            for (String gone : new String[]{"Not sorting", "Sorting...", "Open Layouts Folder", "/invsort", "saved layout"}) {
                c.check(labels.stream().noneMatch(l -> l.contains(gone)), "the tab still shows '" + gone + "'");
            }
            for (String want : new String[]{"Auto Inventory Sorter: ON", "Open Layouts", "Ticks Between Moves: ",
                    "Random Extra Ticks: "}) {
                c.check(labels.stream().anyMatch(l -> l.startsWith(want)), "no '" + want + "' row");
            }

            // The menu, by /invsort, with real input.
            survival(c);
            Map<Integer, ItemStack> items = new LinkedHashMap<>();
            items.put(0, new ItemStack(Items.DIAMOND));
            items.put(1, new ItemStack(Items.EMERALD));
            items.put(9, new ItemStack(Items.GOLD_INGOT));
            inventory(c, items);
            String dia = identity(c, new ItemStack(Items.DIAMOND));
            String eme = identity(c, new ItemStack(Items.EMERALD));
            String gold = identity(c, new ItemStack(Items.GOLD_INGOT));
            HudEditorCases.setWindow(c, 1280, 720, 2);
            c.onClient(mc -> {
                mc.player.connection.sendCommand("invsort");
                return null;
            });
            awaitScreen(c, "InventorySorterScreen");
            Screen s = screen(c);
            int[] p0 = (int[]) c.onClient(mc -> Mod.call(s, "slotPos", 0));
            int[] p1 = (int[]) c.onClient(mc -> Mod.call(s, "slotPos", 1));
            int[] p9 = (int[]) c.onClient(mc -> Mod.call(s, "slotPos", 9));
            int[] p10 = (int[]) c.onClient(mc -> Mod.call(s, "slotPos", 10));
            // Click slot 0, click slot 1: swapped.
            click(c, s, p0[0] + 9, p0[1] + 9);
            click(c, s, p1[0] + 9, p1[1] + 9);
            // Drag slot 9 onto slot 10.
            cursor(c, s, p9[0] + 9, p9[1] + 9);
            c.ctx().getInput().holdMouse(0);
            c.ticks(2);
            for (int i = 1; i <= 4; i++) {
                cursor(c, s, p9[0] + 9 + (p10[0] - p9[0]) * i / 4.0, p9[1] + 9);
            }
            c.ctx().getInput().releaseMouse(0);
            c.ticks(3);
            String grid = c.onClient(mc -> {
                StringBuilder b = new StringBuilder();
                for (int i : new int[]{0, 1, 9, 10}) {
                    ItemStack g = (ItemStack) Mod.call(s, "gridAt", i);
                    b.append(i).append('=').append(g.isEmpty() ? "-" : Mod.staticCall("autoroutes.ItemIdentity", "of", g)).append(' ');
                }
                return b.toString().trim();
            });
            c.note("grid after click-swap 0/1 and drag 9->10: " + grid);
            c.check(grid.equals("0=" + eme + " 1=" + dia + " 9=- 10=" + gold), "grid " + grid);
            boolean realUntouched = c.onClient(mc -> mc.player.getInventory().getItem(0).is(Items.DIAMOND)
                    && mc.player.getInventory().getItem(9).is(Items.GOLD_INGOT));
            c.check(realUntouched, "moving items in the menu changed the real inventory");

            // Type a name, Save Layout.
            EditBox box = editBox(c, s);
            clickWidget(c, s, box);
            c.ctx().getInput().typeChars("ib584 Kit");
            c.ticks(2);
            clickWidget(c, s, widget(c, s, "Save"));
            String saved = c.onClient(mc -> {
                Object store = Mod.staticCall("invsort.InventoryLayoutStore", "getInstance");
                Object l = Mod.call(store, "get", "ib584 Kit");
                return l == null ? null : String.valueOf(Mod.call(l, "entries"));
            });
            c.note("saved 'ib584 Kit': " + saved + "; status " + c.onClient(mc -> Mod.call(s, "status")));
            c.check(saved != null && saved.equals("{0=" + eme + ", 1=" + dia + ", 10=" + gold + "}"), "saved layout " + saved);
            c.check(Files.exists(dir.resolve("ib584 Kit.json")), "no layout file");
            shot(c, "editor-gui2");

            // Select it in the list (by its row), rename to the typed name.
            List<?> names = c.onClient(mc -> (List<?>) Mod.call(Mod.staticCall("invsort.InventoryLayoutStore", "getInstance"), "listNames"));
            int row = names.indexOf("ib584 Kit");
            c.check(row >= 0 && row < 7, "'ib584 Kit' is not on the first page of the list: " + names);
            int listX = (Integer) Mod.field(s, "listX");
            int listY = (Integer) Mod.field(s, "listY");
            click(c, s, listX + 20, listY + 1 + row * 13 + 6);
            c.check("ib584 Kit".equals(c.onClient(mc -> Mod.call(s, "selectedLayout"))), "the list click did not select it");
            c.onClient(mc -> {
                box.setValue("ib584 Renamed");
                return null;
            });
            clickWidget(c, s, widget(c, s, "Rename"));
            boolean renamed = c.onClient(mc -> {
                Object store = Mod.staticCall("invsort.InventoryLayoutStore", "getInstance");
                return Mod.call(store, "get", "ib584 Renamed") != null && Mod.call(store, "get", "ib584 Kit") == null;
            });
            c.check(renamed && Files.exists(dir.resolve("ib584 Renamed.json")) && !Files.exists(dir.resolve("ib584 Kit.json")),
                    "rename: store " + renamed + ", files " + Files.exists(dir.resolve("ib584 Renamed.json")));

            // Bind a key: press the Key button, then K.
            clickWidget(c, s, widget(c, s, "Key"));
            c.ctx().getInput().pressKey(75);
            c.ticks(2);
            int bound = c.onClient(mc -> (Integer) Mod.call(Mod.cfg(ISC), "getLayoutKey", "ib584 Renamed"));
            c.check(bound == 75, "Key button bound " + bound + ", want 75 (K)");

            // Delete.
            clickWidget(c, s, widget(c, s, "Delete"));
            boolean gone = c.onClient(mc -> Mod.call(Mod.staticCall("invsort.InventoryLayoutStore", "getInstance"), "get", "ib584 Renamed") == null);
            int keyAfter = c.onClient(mc -> (Integer) Mod.call(Mod.cfg(ISC), "getLayoutKey", "ib584 Renamed"));
            c.note("renamed, bound K, deleted: store gone " + gone + ", file gone " + !Files.exists(dir.resolve("ib584 Renamed.json"))
                    + ", key after delete " + keyAfter);
            c.check(gone && !Files.exists(dir.resolve("ib584 Renamed.json")), "Delete left the layout");
            c.check(keyAfter == -1, "Delete left the keybind");
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            HudEditorCases.restoreWindow(c, window, oldGui);
            try {
                for (String n : new String[]{"ib584 Kit.json", "ib584 Renamed.json"}) {
                    Files.deleteIfExists(dir.resolve(n));
                }
            } catch (Exception ignored) {
                // nothing to clean
            }
            c.onClient(mc -> {
                Mod.staticCall("invsort.InventoryLayoutStore", "reload");
                return null;
            });
            restoreConfig(c, ISC, cfgPath, old);
            inventory(c, Map.of());
        }
    }

    // ==== 586 Custom Items =============================================================================================

    static void customItems(UiCase c) throws Exception {
        Path cfgPath = configPath(c, ADC);
        byte[] old = read(cfgPath);
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            // The tab: the switch, Open Custom Items, Reset All - nothing else.
            c.onClient(mc -> {
                Mod.call(Mod.cfg(ADC), "clear");
                Mod.call(Mod.cfg(ADC), "setEnabled", true);
                Mod.call(Mod.cfg(ADC), "save");
                return null;
            });
            List<String> labels = tabLabels(c, "gui.tab.CustomItemsTab");
            c.note("Custom Items rows: " + labels);
            c.check(labels.equals(List.of("Custom Items: ON", "Open Custom Items", "Reset All")), "tab rows " + labels);

            survival(c);
            Map<Integer, ItemStack> items = new LinkedHashMap<>();
            items.put(9, sb(Items.LEATHER_CHESTPLATE, "IB_CHEST", "ci-1", 0));
            items.put(10, sb(Items.LEATHER_CHESTPLATE, "IB_CHEST", "ci-2", 0));
            items.put(11, sb(Items.DIAMOND_SWORD, "IB_SWORD", "ci-3", 0));
            inventory(c, items);
            HudEditorCases.setWindow(c, 1280, 720, 2);
            c.onClient(mc -> {
                mc.player.connection.sendCommand("customitems");
                return null;
            });
            awaitScreen(c, "CustomItemsScreen");
            Screen s = screen(c);
            int[] cell9 = (int[]) c.onClient(mc -> Mod.call(s, "cellPos", 4));   // inventory slot 9
            int[] cell10 = (int[]) c.onClient(mc -> Mod.call(s, "cellPos", 5));  // inventory slot 10
            int[] cell11 = (int[]) c.onClient(mc -> Mod.call(s, "cellPos", 6));  // inventory slot 11
            click(c, s, cell9[0] + 9, cell9[1] + 9);
            String key = c.onClient(mc -> (String) Mod.call(s, "selectedKey"));
            c.check("IB_CHEST".equals(key), "clicking the chestplate selected " + key + ", want IB_CHEST (every copy)");
            c.onClient(mc -> {
                Mod.call(s, "setColour", 0xFF00FF00);
                return null;
            });
            int[] dyeAll = dyes(c);
            c.note("every copy, green: slot 9 " + Integer.toHexString(dyeAll[0]) + ", slot 10 " + Integer.toHexString(dyeAll[1]));
            c.check(dyeAll[0] == 0xFF00FF00 && dyeAll[1] == 0xFF00FF00, "every-copy colour not on both copies");

            Screen s2 = screen(c); // the menu rebuilds itself on every change; the screen object is the same
            clickWidget(c, s2, widget(c, s2, "Applies To"));
            key = c.onClient(mc -> (String) Mod.call(s2, "selectedKey"));
            int[] dyeOne = dyes(c);
            c.note("this item only (" + key + "): slot 9 " + Integer.toHexString(dyeOne[0]) + ", slot 10 " + Integer.toHexString(dyeOne[1]));
            c.check("UUID:CI-1".equals(key), "Applies To switched the key to " + key);
            c.check(dyeOne[0] == 0xFF00FF00 && dyeOne[1] == 0xFF123456, "this-item colour: " + Integer.toHexString(dyeOne[0])
                    + " / " + Integer.toHexString(dyeOne[1]));

            // The drawn pixels: green in slot 9's cell, none in slot 10's (same item, its own look).
            c.ctx().getInput().setCursorPos(2, 2);
            c.ticks(3);
            float f = c.onClient(mc -> ((Number) Mod.staticCall("hud.AutoScale", "appliedFactor", s2)).floatValue());
            int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
            BufferedImage img = shot(c, "menu-green-gui2");
            int g9 = green(img, cell9, f, gs);
            int g10 = green(img, cell10, f, gs);
            c.note("green pixels drawn: slot 9 cell " + g9 + ", slot 10 cell " + g10);
            c.check(g9 > 40 && g10 < 5, "drawn colour: slot 9 " + g9 + " green px, slot 10 " + g10);

            // Use Colour off and on.
            clickWidget(c, s2, widget(c, s2, "Use Colour"));
            int off = dyes(c)[0];
            clickWidget(c, s2, widget(c, s2, "Use Colour"));
            int on = dyes(c)[0];
            c.check(off == 0xFF123456 && on == 0xFF00FF00, "Use Colour off/on: " + Integer.toHexString(off) + "/" + Integer.toHexString(on));

            // Armor Skin: None -> Leather -> ...; the worn asset changes, the icon does not.
            clickWidget(c, s2, widget(c, s2, "Skin"));
            String asset = c.onClient(mc -> {
                Equippable e = mc.player.getInventory().getItem(9).get(DataComponents.EQUIPPABLE);
                return e == null ? "none" : e.assetId().map(Object::toString).orElse("none");
            });
            String icon = c.onClient(mc -> String.valueOf(mc.player.getInventory().getItem(9).get(DataComponents.ITEM_MODEL)));
            c.note("Armor Skin once: equippable asset " + asset + ", item model " + icon);
            c.check(asset.contains("leather"), "Armor Skin did not set the leather asset: " + asset);
            c.check(icon.equals("minecraft:leather_chestplate"), "the skin changed the inventory icon: " + icon);

            // Look by typing an id.
            EditBox look = editBox(c, s2);
            clickWidget(c, s2, look);
            c.ctx().getInput().typeChars("diamond_sword");
            c.ticks(2);
            clickWidget(c, s2, widget(c, s2, "Set"));
            String[] models = c.onClient(mc -> new String[]{
                    String.valueOf(mc.player.getInventory().getItem(9).get(DataComponents.ITEM_MODEL)),
                    String.valueOf(mc.player.getInventory().getItem(10).get(DataComponents.ITEM_MODEL))});
            c.note("Look 'diamond_sword': slot 9 model " + models[0] + ", slot 10 " + models[1]);
            c.check(models[0].equals("minecraft:diamond_sword") && models[1].equals("minecraft:leather_chestplate"),
                    "Look models " + models[0] + " / " + models[1]);
            shot(c, "menu-look-gui2");

            // Copy Look From Item: the sword's own model onto the chestplate.
            clickWidget(c, s2, widget(c, s2, "Clear Look"));
            clickWidget(c, s2, widget(c, s2, "Copy Look"));
            click(c, s2, cell11[0] + 9, cell11[1] + 9);
            String copied = c.onClient(mc -> String.valueOf(mc.player.getInventory().getItem(9).get(DataComponents.ITEM_MODEL)));
            String still = c.onClient(mc -> (String) Mod.call(s2, "selectedKey"));
            c.check(copied.equals("minecraft:diamond_sword") && "UUID:CI-1".equals(still),
                    "copy look: model " + copied + ", selection " + still);

            // The saved list selects by row.
            int listY = (Integer) Mod.field(s2, "listY");
            int gridX = (Integer) Mod.field(s2, "gridX");
            click(c, s2, cell10[0] + 9, cell10[1] + 9);   // select another item first
            click(c, s2, gridX + 20, listY + 1 + 6);       // row 0: the only saved look
            c.check("UUID:CI-1".equals(c.onClient(mc -> Mod.call(s2, "selectedKey"))), "the saved-list row did not select the look");

            // Remove.
            clickWidget(c, s2, widget(c, s2, "Remove"));
            String afterRemove = c.onClient(mc -> Mod.call(Mod.cfg(ADC), "entryCount") + " "
                    + mc.player.getInventory().getItem(9).get(DataComponents.ITEM_MODEL) + " "
                    + Integer.toHexString(DyedItemColor.getOrDefault(mc.player.getInventory().getItem(9), 0xFF123456)));
            c.note("after Remove: " + afterRemove);
            c.check(afterRemove.equals("0 minecraft:leather_chestplate ff123456"), "Remove left " + afterRemove);

            // A head texture (set from text, checked without drawing it - no skin download in a test).
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            String tex = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvaWJ0ZXN0In19fQ==";
            String head = c.onClient(mc -> {
                try {
                    Object menu = Mod.cls("armourdye.CustomItemsScreen").getConstructor(Screen.class).newInstance((Object) null);
                    Mod.call(menu, "select", mc.player.getInventory().getItem(11));
                    Mod.call(menu, "setLook", tex);
                    ItemStack sw = mc.player.getInventory().getItem(11);
                    var prof = sw.get(DataComponents.PROFILE);
                    String got = prof == null ? "none" : prof.partialProfile().properties().get("textures").iterator().next().value();
                    String model = String.valueOf(sw.get(DataComponents.ITEM_MODEL));
                    Mod.call(menu, "removeSelected");
                    return model + "|" + got.equals(tex) + "|" + sw.get(DataComponents.PROFILE);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            c.note("head texture Look on the sword: model|texture matches|profile after remove = " + head);
            c.check(head.equals("minecraft:player_head|true|null"), "head look " + head);

            // Reset All needs two presses.
            c.onClient(mc -> {
                Object e = Mod.call(Mod.cfg(ADC), "getOrCreate", "IB_SWORD", "x");
                ItemCasesAccess.setPublic(e, "colorEnabled", true);
                Mod.call(Mod.cfg(ADC), "save");
                return null;
            });
            String reset = c.onClient(mc -> {
                try {
                    Object tab = Mod.cls("gui.tab.CustomItemsTab").getConstructor().newInstance();
                    @SuppressWarnings("unchecked")
                    List<AbstractWidget> ws = (List<AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0, 300, (Runnable) () -> { });
                    AbstractWidget btn = ws.stream().filter(w -> ModScreenDriver.label(w).startsWith("Reset All")).findFirst().orElseThrow();
                    ModScreenDriver.press(btn);
                    int afterOne = (Integer) Mod.call(Mod.cfg(ADC), "entryCount");
                    ModScreenDriver.press(btn);
                    int afterTwo = (Integer) Mod.call(Mod.cfg(ADC), "entryCount");
                    return afterOne + "," + afterTwo;
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            c.note("Reset All: entries after one press, after two: " + reset);
            c.check(reset.equals("1,0"), "Reset All " + reset);

            // An Armour Recolour file from before the rename.
            Files.writeString(cfgPath, "{\"enabled\":true,\"skinInventoryIcons\":true,\"captureKey\":71,\"entries\":[{\"itemId\":"
                    + "\"IB_CHEST\",\"label\":\"Old Chest\",\"enabled\":true,\"colorEnabled\":true,\"color\":-16776961,\"skin\":\"IRON\","
                    + "\"skinAsset\":\"\",\"iconModel\":\"\",\"trimMaterial\":\"\",\"trimPattern\":\"\"}]}");
            String migrated = c.onClient(mc -> {
                Mod.staticCall(ADC, "load");
                Mod.call(Mod.cfg(ADC), "save");
                ItemStack st = mc.player.getInventory().getItem(10);
                Equippable e = st.get(DataComponents.EQUIPPABLE);
                return Integer.toHexString(DyedItemColor.getOrDefault(st, 0)) + "|"
                        + (e == null ? "none" : e.assetId().map(Object::toString).orElse("none")) + "|"
                        + st.get(DataComponents.ITEM_MODEL);
            });
            String file = text(cfgPath);
            c.note("old Armour Recolour file: colour|asset|model = " + migrated + "; saved again: " + file);
            c.check(migrated.startsWith("ff0000ff|") && migrated.contains("iron") && migrated.endsWith("|minecraft:leather_chestplate"),
                    "old entry " + migrated);
            c.check(!file.contains("captureKey") && !file.contains("skinInventoryIcons") && file.contains("\"IB_CHEST\""),
                    "the saved file " + file);
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            HudEditorCases.restoreWindow(c, window, oldGui);
            restoreConfig(c, ADC, cfgPath, old);
            inventory(c, Map.of());
        }
    }

    /** DyedItemColor for inventory slots 9 and 10, default 0xFF123456. */
    private static int[] dyes(UiCase c) {
        return c.onClient(mc -> new int[]{
                DyedItemColor.getOrDefault(mc.player.getInventory().getItem(9), 0xFF123456),
                DyedItemColor.getOrDefault(mc.player.getInventory().getItem(10), 0xFF123456)});
    }

    /** Clearly green pixels inside a menu cell (layout coords, through the Auto Scale factor). */
    private static int green(BufferedImage img, int[] cell, float f, int gs) {
        int x0 = Math.round((cell[0] + 1) * f * gs);
        int y0 = Math.round((cell[1] + 1) * f * gs);
        int x1 = Math.round((cell[0] + 17) * f * gs);
        int y1 = Math.round((cell[1] + 17) * f * gs);
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int p = rgb(img, x, y);
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                if (g > 60 && g > 2 * r && g > 2 * b) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Public-field setter (ItemCases' helper lives in the menu package). */
    private static final class ItemCasesAccess {
        static void setPublic(Object target, String field, Object value) {
            try {
                target.getClass().getField(field).set(target, value);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("cannot set " + field, e);
            }
        }
    }

    // ==== 587 the Cosmetics category ===================================================================================

    static void cosmeticsCategory(UiCase c) throws Exception {
        c.onClient(mc -> {
            McCompat.setScreen(mc, (Screen) Mod.staticCall("gui.ModScreen", "atTab", null, "Cosmetics"));
            return null;
        });
        c.ticks(4);
        Map<String, List<String>> tree = c.onClient(mc -> {
            Map<String, List<String>> out = new LinkedHashMap<>();
            @SuppressWarnings("unchecked")
            List<Object> tabs = (List<Object>) Mod.field("gui.ModScreen", "tabs");
            for (Object t : tabs) {
                String name = (String) Mod.field(t, "name");
                List<String> subs = new ArrayList<>();
                if (Mod.cls("gui.tab.FolderTab").isInstance(t)) {
                    for (Object sub : (List<?>) Mod.call(t, "subTabs")) {
                        subs.add((String) Mod.field(sub, "name"));
                    }
                }
                out.put(name, subs);
            }
            return out;
        });
        c.note("top-level tabs: " + tree);
        c.check(tree.containsKey("Cosmetics"), "no Cosmetics category");
        c.check(tree.get("Cosmetics").equals(List.of("Player Cosmetics", "Custom Items", "Nickhider", "Trail")),
                "Cosmetics holds " + tree.get("Cosmetics"));
        for (Map.Entry<String, List<String>> e : tree.entrySet()) {
            if (e.getKey().equals("Cosmetics")) {
                continue;
            }
            for (String moved : new String[]{"Cosmetics", "Player Cosmetics", "Custom Items", "Nickhider", "Trail", "Armor Recolour"}) {
                c.check(!e.getValue().contains(moved), "'" + moved + "' is still in " + e.getKey());
            }
        }
        String mySize = c.onClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "describe", "Player Cosmetics", "My Size: 1.00x"));
        String trail = c.onClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "describe", "Trail", "Trail: OFF"));
        String cat = c.onClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "describe", null, "Cosmetics"));
        c.note("tooltips: Player Cosmetics/My Size = " + mySize + "; Trail/Trail = " + trail + "; category = " + cat);
        c.check(mySize != null && mySize.startsWith("Your own player model's size"), "My Size tooltip " + mySize);
        c.check(trail != null && cat != null, "Trail / category tooltip missing");
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            HudEditorCases.setWindow(c, 1280, 720, 2);
            c.onClient(mc -> {
                McCompat.setScreen(mc, (Screen) Mod.staticCall("gui.ModScreen", "atTab", null, "Cosmetics"));
                return null;
            });
            c.ticks(5);
            shot(c, "cosmetics-category-gui2");
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            HudEditorCases.restoreWindow(c, window, oldGui);
        }
    }
}

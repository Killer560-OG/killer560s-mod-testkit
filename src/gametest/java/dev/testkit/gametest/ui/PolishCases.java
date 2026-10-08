package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
 * 471, 473-476: killer560's 2026-10-07 22:08 polish report (mod branch polish), each judged on screenshots in real screen
 * pixels after proving the thing under test was drawn. (472, the Storage Overlay's Scan All button, needs Hypixel's
 * Storage menu and is a menu-suite case: {@code menu/PolishMenuCases}.) {@code TESTKIT_POLISH_SHOTS=<dir>} copies the
 * GUI-scale-2 pictures there, named with {@code TESTKIT_POLISH_TAG} (before / after) - a jar from before the branch runs
 * the same cases for the "before" pictures and fails them.
 *
 * <ul>
 *   <li>471-ui-held-item-name: "make an option to hide that text that pops up saying what item I am holding". Vanilla's
 *       held-item name measured as the pixels that change between a frame right after a slot switch and one after it
 *       faded: drawn with Held Item Name Shown (over a bar), hidden with Hidden Over Bars while a bar covers its spot,
 *       drawn with Hidden Over Bars while the bars are elsewhere, hidden with Hidden; the mod's frame counters agree;
 *       the setting survives save + load and an unknown value reads as the default.</li>
 *   <li>473-ui-inventory-line-pixels: "right now 1 is far too large". Line Width 1 / 2 / 3 draws exactly that many screen
 *       pixels at GUI 2 and GUI 3 (a chest slot's top line, the panel border, the hotbar border); a file saved in the old
 *       GUI units converts to the same pixels at the scale it is first drawn at; the clamp equals the slider.</li>
 *   <li>474-ui-inventory-shared-lines: "two touching slots have their lines touching each other". Between two touching
 *       slots there is one line of Line Width pixels, not two, across and down, in a chest, the player inventory (main
 *       grid, its hotbar row, the crafting grid) and the in-game hotbar; each group keeps its outer line.</li>
 *   <li>475-ui-inventory-armour-model: "make the player's model box just a hair larger so they are even". The armour
 *       column's outline and the player-model frame start and end on the same pixel rows, at GUI 2 and 3, lines 1 and 3.</li>
 *   <li>476-ui-bars-text-centred: "center the text for my custom health bars". The number's white pixels centred in its
 *       bar within one pixel across and down (the digit body - the comma's tail is not the number's height), at GUI 2
 *       and 4, Predefined and Custom, with and without the absorption segment.</li>
 * </ul>
 */
final class PolishCases {

    private static final String ICFG = "inventorytheme.InventoryThemeConfig";
    private static final String PS = "playerstats.PlayerStatsConfig";
    private static final String HUD = "hud.HudConfig";
    private static final String FEATURE = "playerstats.PlayerStatsFeature";
    private static final String READOUT = "playerstats.StatElements$Readout";
    private static final String AREA = "playerstats.StatLayout$Area";
    private static final String HELD = "playerstats.PlayerStatsConfig$HeldItemName";
    private static final String THEME = "gui.PanelTheme";
    private static final int AMBER = 0xCC6600;

    private PolishCases() {
    }

    // ==== shared =======================================================================================================

    private static BufferedImage shot(UiCase c, String label) {
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
        int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
        if (gs == 2) {
            copy(c, taken, name);
        }
        try {
            return javax.imageio.ImageIO.read(taken.toFile());
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read screenshot " + taken, e);
        }
    }

    private static void copy(UiCase c, Path taken, String name) {
        String dir = System.getenv("TESTKIT_POLISH_SHOTS");
        if (dir == null || dir.isBlank()) {
            return;
        }
        try {
            String tag = System.getenv("TESTKIT_POLISH_TAG");
            String jar = Mod.isCheat() ? "cheat" : "legit";
            String mc = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                    .getMetadata().getVersion().getFriendlyString();
            Path out = Path.of(dir).resolve((tag == null || tag.isBlank() ? "" : tag + "-") + name + "-" + mc + "-" + jar
                    + ".png");
            Files.createDirectories(out.getParent());
            Files.copy(taken, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception | Error e) {
            c.note("could not copy " + name + ": " + e);
        }
    }

    private static int rgb(BufferedImage img, int x, int y) {
        if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) {
            return -1;
        }
        return img.getRGB(x, y) & 0xFFFFFF;
    }

    /** Pixels of {@code colour} on the row {@code y} from {@code x0} (inclusive) to {@code x1} (exclusive). */
    private static int rowCount(BufferedImage img, int y, int x0, int x1, int colour) {
        int n = 0;
        for (int x = x0; x < x1; x++) {
            if (rgb(img, x, y) == colour) {
                n++;
            }
        }
        return n;
    }

    private static int colCount(BufferedImage img, int x, int y0, int y1, int colour) {
        int n = 0;
        for (int y = y0; y < y1; y++) {
            if (rgb(img, x, y) == colour) {
                n++;
            }
        }
        return n;
    }

    /** The theme on for every container, hotbar themed, Amber, no custom colours, the given line width. */
    private static void theme(UiCase c, int line) {
        c.onClient(mc -> {
            Object cfg = Mod.cfg(ICFG);
            Mod.call(cfg, "setEnabled", true);
            Mod.call(cfg, "setHypixelOnly", false);
            Mod.call(cfg, "setThemeHotbar", true);
            Mod.call(cfg, "setHotbarScale", 1.0f);
            Mod.call(cfg, "setLineWidth", line);
            if (Mod.has(THEME)) {
                Mod.call(cfg, "setTheme", Mod.enumValue(THEME, "AMBER"));
            }
            Mod.call(cfg, "setUseCustomAccent", false);
            Mod.call(cfg, "setUseCustomSlotColor", false);
            Mod.call(cfg, "setBackgroundOpacity", 0.85f);
            return null;
        });
    }

    /** The config files of {@code classes} (their CONFIG_PATH), to put back afterwards. */
    private static Map<Path, byte[]> snapshot(UiCase c, String... classes) {
        Map<Path, byte[]> out = new LinkedHashMap<>();
        for (String cls : classes) {
            Path p = c.onClient(mc -> (Path) R.getStatic(R.cls(cls), "CONFIG_PATH"));
            try {
                out.put(p, Files.exists(p) ? Files.readAllBytes(p) : null);
            } catch (java.io.IOException e) {
                c.note("snapshot " + p + ": " + e);
            }
        }
        return out;
    }

    private static void restore(UiCase c, Map<Path, byte[]> saved, String... classes) {
        try {
            c.onClient(mc -> {
                try {
                    for (Map.Entry<Path, byte[]> e : saved.entrySet()) {
                        if (e.getValue() == null) {
                            Files.deleteIfExists(e.getKey());
                        } else {
                            Files.write(e.getKey(), e.getValue());
                        }
                    }
                } catch (java.io.IOException e) {
                    throw new AssertionError(e);
                }
                for (String cls : classes) {
                    try {
                        Mod.staticCall(cls, "load");
                    } catch (RuntimeException | AssertionError ignored) {
                        // a config this jar lacks
                    }
                }
                McCompat.setScreen(mc, null);
                return null;
            });
            c.ticks(2);
        } catch (Throwable t) {
            c.note("config restore: " + UiCase.describe(t));
        }
    }

    private static ItemStack[] saveInventory(UiCase c) {
        return c.onClient(mc -> {
            var inv = mc.player.getInventory();
            ItemStack[] o = new ItemStack[42];
            for (int i = 0; i < 41; i++) {
                o[i] = inv.getItem(i).copy();
            }
            o[41] = new ItemStack(Items.STICK, inv.getSelectedSlot() + 1);
            return o;
        });
    }

    private static void restoreInventory(UiCase c, ItemStack[] old) {
        c.onClient(mc -> {
            var inv = mc.player.getInventory();
            for (int i = 0; i < 41; i++) {
                inv.setItem(i, old[i]);
            }
            inv.setSelectedSlot(old[41].getCount() - 1);
            return null;
        });
    }

    private static void clearInventory(UiCase c) {
        c.onClient(mc -> {
            var inv = mc.player.getInventory();
            for (int i = 0; i < 41; i++) {
                inv.setItem(i, ItemStack.EMPTY);
            }
            inv.setSelectedSlot(0);
            return null;
        });
    }

    private static void serverPlayer(UiCase c, java.util.function.Consumer<net.minecraft.server.level.ServerPlayer> f) {
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

    /** Survival (the real inventory screen; creative swaps it for the creative one) but invulnerable and flying. */
    private static void survival(UiCase c) {
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

    /** {gui scale, gui width, gui height}. */
    private static int[] gui(UiCase c) {
        return c.onClient(mc -> new int[]{mc.getWindow().getGuiScale(), mc.getWindow().getGuiScaledWidth(),
                mc.getWindow().getGuiScaledHeight()});
    }

    private static int lineWidth(UiCase c) {
        return c.onClient(mc -> ((Number) Mod.call(Mod.cfg(ICFG), "getLineWidth")).intValue());
    }

    // ==== 471 held item name ===========================================================================================

    static void heldItemName(UiCase c) throws Exception {
        Map<Path, byte[]> saved = snapshot(c, PS, HUD);
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        ItemStack[] inv = saveInventory(c);
        boolean hasSetting = Mod.has(HELD);
        try {
            HudEditorCases.setWindow(c, 1280, 720, 2);
            clearInventory(c);
            c.onClient(mc -> {
                mc.player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
                mc.player.getInventory().setItem(1, new ItemStack(Items.APPLE));
                return null;
            });
            int[] g = gui(c);
            int gs = g[0];
            // Vanilla's box (javap, both versions): text centred at guiHeight - 59, +14 where the player cannot be hurt
            // (this world is creative), with textWithBackdrop's two units round it.
            int nameW = c.onClient(mc -> mc.font.width(new ItemStack(Items.DIAMOND_SWORD).getHoverName()));
            boolean creative = c.onClient(mc -> !mc.gameMode.canHurtPlayer());
            int ny = g[2] - 59 + (creative ? 14 : 0);
            int nx = (g[1] - nameW) / 2;
            int[] box = {(nx - 2) * gs, (ny - 2) * gs, (nx + nameW + 2) * gs, (ny + 11) * gs};
            c.note(String.format(Locale.ROOT, "GUI %d, %dx%d units; the name 'Diamond Sword' is %d wide, its box %d,%d..%d,%d"
                    + " units (%s)", gs, g[1], g[2], nameW, nx - 2, ny - 2, nx + nameW + 2, ny + 11,
                    creative ? "creative: +14" : "survival"));

            // A Health Bar on the name's spot (Custom layout, saved exactly there), and a Mana Bar out of the way.
            bars(c, true);
            placeBar(c, "statbar_health", g[1] / 2 - 50, ny - 3);
            placeBar(c, "statbar_mana", 10, 10);
            c.onClient(mc -> {
                Class<?> f = R.cls(FEATURE);
                R.setStatic(f, "healthCur", 1000L);
                R.setStatic(f, "healthMax", 1000L);
                R.setStatic(f, "manaCur", 500L);
                R.setStatic(f, "manaMax", 1000L);
                return null;
            });
            c.ticks(3);
            boolean over = hasSetting && c.onClient(mc -> (Boolean) Mod.staticCall("playerstats.StatElements", "drawnOver",
                    (float) (nx - 2), (float) (ny - 2), (float) (nx + nameW + 2), (float) (ny + 11)));
            c.note("the mod reports a readout drawn over the name's box: " + over);
            if (hasSetting) {
                c.check(over, "the Health Bar was placed on the name's spot but the mod does not see it there - every "
                        + "'hidden over bars' result below would be vacuous");
            }

            int shownOverBar = nameDrawn(c, "shown-over-bar", hasSetting ? "SHOWN" : null, box);
            if (!hasSetting) {
                c.problem("this jar has no PlayerStatsConfig.HeldItemName - no setting to hide the held item name "
                        + "(name drew " + shownOverBar + " px over the bar)");
                return;
            }
            int autoOverBar = nameDrawn(c, "auto-over-bar", "OVER_BARS", box);
            int hiddenOverBar = nameDrawn(c, "hidden-over-bar", "HIDDEN", box);
            placeBar(c, "statbar_health", 10, 30);
            int autoClear = nameDrawn(c, "auto-bars-elsewhere", "OVER_BARS", box);
            int hiddenClear = nameDrawn(c, "hidden-bars-elsewhere", "HIDDEN", box);
            int shownClear = nameDrawn(c, "shown-bars-elsewhere", "SHOWN", box);
            c.note(String.format(Locale.ROOT, "name pixels drawn - over a bar: Shown %d, Hidden Over Bars %d, Hidden %d; "
                    + "bars elsewhere: Shown %d, Hidden Over Bars %d, Hidden %d", shownOverBar, autoOverBar, hiddenOverBar,
                    shownClear, autoClear, hiddenClear));
            int min = 30 * gs; // "Diamond Sword" is ~70 units of text; a drawn name changes hundreds of pixels
            c.check(shownOverBar >= min && shownClear >= min, "Shown does not draw the name (" + shownOverBar + " / "
                    + shownClear + " px) - the measurement cannot tell drawn from hidden");
            c.check(autoOverBar <= 4, "Hidden Over Bars still draws the name over the Health Bar (" + autoOverBar + " px)");
            c.check(autoClear >= min, "Hidden Over Bars hides the name with no bar under it (" + autoClear + " px)");
            c.check(hiddenOverBar <= 4 && hiddenClear <= 4, "Hidden still draws the name (" + hiddenOverBar + " / "
                    + hiddenClear + " px)");

            // Saved and read back; an unknown value is the default.
            Path file = c.onClient(mc -> (Path) R.getStatic(R.cls(PS), "CONFIG_PATH"));
            String back = c.onClient(mc -> {
                Mod.call(Mod.cfg(PS), "setHeldItemName", Mod.enumValue(HELD, "HIDDEN"));
                Mod.call(Mod.cfg(PS), "save");
                Mod.staticCall(PS, "load");
                return String.valueOf(Mod.call(Mod.cfg(PS), "getHeldItemName"));
            });
            String text = Files.readString(file);
            c.note("after save + load: " + back + "; file holds " + (text.contains("\"heldItemName\": \"hidden\"")
                    ? "\"heldItemName\": \"hidden\"" : "no heldItemName"));
            c.check(back.equals("HIDDEN") && text.contains("\"heldItemName\": \"hidden\""),
                    "Held Item Name did not survive a reload: " + back);
            Files.writeString(file, text.replace("\"heldItemName\": \"hidden\"", "\"heldItemName\": \"nonsense\""));
            String bad = c.onClient(mc -> {
                Mod.staticCall(PS, "load");
                return String.valueOf(Mod.call(Mod.cfg(PS), "getHeldItemName"));
            });
            c.note("an unknown saved value loads as " + bad);
            c.check(bad.equals("OVER_BARS"), "an unknown Held Item Name loads as " + bad + ", want the default OVER_BARS");
        } finally {
            c.onClient(mc -> {
                Class<?> f = R.cls(FEATURE);
                R.setStatic(f, "healthCur", -1L);
                R.setStatic(f, "healthMax", -1L);
                R.setStatic(f, "manaCur", -1L);
                R.setStatic(f, "manaMax", -1L);
                return null;
            });
            restoreInventory(c, inv);
            HudEditorCases.restoreWindow(c, window, oldGui);
            restore(c, saved, HUD, PS);
        }
    }

    /**
     * Switches to the apple and back to the sword (vanilla's Gui.tick starts the name's timer when the held stack
     * changes), and returns how many pixels of the name's box change between a frame 3 ticks after the switch and one
     * after the name has faded (80 ticks). With {@code mode} null the setting is left alone (an old jar).
     */
    private static int nameDrawn(UiCase c, String label, String mode, int[] box) {
        if (mode != null) {
            c.onClient(mc -> {
                Mod.call(Mod.cfg(PS), "setHeldItemName", Mod.enumValue(HELD, mode));
                return null;
            });
        }
        c.onClient(mc -> {
            mc.player.getInventory().setSelectedSlot(1);
            return null;
        });
        c.ticks(60);
        long[] before = counters(c);
        c.onClient(mc -> {
            mc.player.getInventory().setSelectedSlot(0);
            return null;
        });
        c.ticks(3);
        BufferedImage on = shot(c, label);
        long[] after = counters(c);
        c.ticks(80);
        BufferedImage off = shot(c, label + "-faded");
        int n = InvStorageCases.changed(on, off, box[0], box[1], box[2], box[3], 30);
        if (before != null && after != null) {
            c.note(String.format(Locale.ROOT, "%s: name layer frames hidden +%d, shown +%d; %d px of the name's box "
                    + "changed", label, after[0] - before[0], after[1] - before[1], n));
            if (mode != null && after[0] - before[0] + after[1] - before[1] == 0) {
                c.problem(label + ": the held-item name layer never ran - the wrap is not installed");
            }
        }
        return n;
    }

    private static long[] counters(UiCase c) {
        try {
            return new long[]{((Number) Mod.field(FEATURE, "heldNameHiddenFrames")).longValue(),
                    ((Number) Mod.field(FEATURE, "heldNameShownFrames")).longValue()};
        } catch (Throwable t) {
            return null;
        }
    }

    /** Stat Bars on, Health and Mana Bars on with their numbers, Custom layout unless {@code predefined}, own scale 1,
     *  Auto Scale off, bar 100 x 14, the colours {@link HudFixCases} measures. */
    private static void bars(UiCase c, boolean custom) {
        c.onClient(mc -> {
            Object ps = Mod.cfg(PS);
            Mod.call(ps, "setEnabled", true);
            Mod.call(ps, "setPredefinedLayout", !custom);
            for (Object rr : R.cls(READOUT).getEnumConstants()) {
                Mod.call(ps, "setReadoutOn", rr, false);
            }
            Mod.call(ps, "setReadoutOn", Mod.enumValue(READOUT, "HEALTH_BAR"), true);
            Mod.call(ps, "setReadoutOn", Mod.enumValue(READOUT, "MANA_BAR"), true);
            Mod.call(ps, "setReadoutColor", Mod.enumValue(READOUT, "HEALTH_BAR"), HudFixCases.HEALTH);
            Mod.call(ps, "setReadoutColor", Mod.enumValue(READOUT, "MANA_BAR"), HudFixCases.MANA);
            Mod.call(ps, "setBarBackground", HudFixCases.BACKGROUND);
            Mod.call(ps, "setAbsorptionColor", HudFixCases.ABSORB);
            Mod.call(ps, "setBarShowValue", true);
            Mod.call(ps, "setTextShadow", true);
            Mod.call(ps, "setBarWidth", 100);
            Mod.call(ps, "setBarHeight", 14);
            Mod.call(ps, "setPredefinedScale", 1.0f);
            Mod.call(ps, "save");
            Object hud = Mod.cfg(HUD);
            for (Object rr : R.cls(READOUT).getEnumConstants()) {
                Mod.call(hud, "setScale", (String) R.get(rr, "hudId"), 1.0f);
            }
            Mod.call(hud, "setGlobalScale", 1.0f);
            Mod.call(hud, "setAutoScale", false);
            Mod.call(hud, "save");
            McCompat.setScreen(mc, null);
            return null;
        });
        c.ticks(3);
    }

    private static void placeBar(UiCase c, String id, int x, int y) {
        c.onClient(mc -> {
            Mod.call(Mod.cfg(HUD), "setPosition", id, x, y);
            Mod.call(Mod.cfg(HUD), "save");
            return null;
        });
        c.ticks(3);
    }

    // ==== 473 line pixels ==============================================================================================

    static void linePixels(UiCase c) throws Exception {
        Map<Path, byte[]> saved = snapshot(c, ICFG);
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        ItemStack[] inv = saveInventory(c);
        try {
            clearInventory(c);
            int[][] sizes = {{1280, 720, 2}, {1920, 1080, 3}};
            for (int[] sz : sizes) {
                HudEditorCases.setWindow(c, sz[0], sz[1], sz[2]);
                int[] g = gui(c);
                int gs = g[0];
                c.check(gs == sz[2], "GUI scale is " + gs + ", not " + sz[2]);
                for (int w : new int[]{1, 2, 3}) {
                    theme(c, w);
                    int set = lineWidth(c);
                    int left = (g[1] - 176) / 2;
                    int top = (g[2] - 222) / 2;
                    openChest(c);
                    BufferedImage img = shot(c, "chest-gui" + gs + "-line" + w);
                    // Slot 0's top line, down its centre column; its left line along its centre row; the panel's top
                    // border down the panel's middle.
                    int slotTop = InvStorageCases.runDown(img, (left + 16) * gs, (top + 17) * gs, AMBER);
                    int slotLeft = runRight(img, (left + 7) * gs, (top + 26) * gs, AMBER);
                    int panelTop = InvStorageCases.runDown(img, (left + 88) * gs, top * gs, AMBER);
                    c.onClient(mc -> {
                        McCompat.setScreen(mc, null);
                        return null;
                    });
                    BufferedImage hb = shot(c, "hotbar-gui" + gs + "-line" + w);
                    // The hotbar's top border over the middle of slot 4's cell (slot 0 is selected; not a separator).
                    int hotTop = InvStorageCases.runDown(hb, (g[1] / 2) * gs, (g[2] - 22) * gs, AMBER);
                    String line = String.format(Locale.ROOT, "GUI %d, Line Width %d (getLineWidth %d): chest slot top %d px, "
                            + "slot left %d px, panel border %d px, hotbar border %d px", gs, w, set, slotTop, slotLeft,
                            panelTop, hotTop);
                    c.note(line);
                    if (slotTop != w || slotLeft != w || panelTop != w || hotTop != w) {
                        c.problem("Line Width " + w + " is not " + w + " screen pixel(s) at GUI " + gs + ": " + line);
                    }
                }
            }

            // Clamp = slider bounds.
            int min = ((Number) Mod.field(ICFG, "MIN_LINE_WIDTH")).intValue();
            int max = ((Number) Mod.field(ICFG, "MAX_LINE_WIDTH")).intValue();
            String clamps = c.onClient(mc -> {
                Object cfg = Mod.cfg(ICFG);
                Mod.call(cfg, "setLineWidth", max + 7);
                Object hi = Mod.call(cfg, "getLineWidth");
                Mod.call(cfg, "setLineWidth", min - 7);
                Object lo = Mod.call(cfg, "getLineWidth");
                return hi + "," + lo;
            });
            c.note("Line Width range " + min + ".." + max + "; setter clamps " + clamps);
            c.check(clamps.equals(max + "," + min) && min == 0 && max >= 8, "Line Width clamps " + clamps + " vs slider "
                    + min + ".." + max);

            // A file from before pixels (lineWidth in GUI units, no lineWidthPx) keeps its look.
            Path file = c.onClient(mc -> (Path) R.getStatic(R.cls(ICFG), "CONFIG_PATH"));
            int[][] legacy = {{2, 1, 2}, {3, 1, 3}, {3, 2, 6}, {2, 0, 0}};
            for (int[] l : legacy) {
                HudEditorCases.setWindow(c, l[0] == 2 ? 1280 : 1920, l[0] == 2 ? 720 : 1080, l[0]);
                Files.writeString(file, "{\"enabled\":true,\"hypixelOnly\":false,\"lineWidth\":" + l[1] + "}");
                int got = c.onClient(mc -> {
                    Mod.staticCall(ICFG, "load");
                    return ((Number) Mod.call(Mod.cfg(ICFG), "getLineWidth")).intValue();
                });
                String after = Files.readString(file).replaceAll("\\s+", "");
                c.note("old file lineWidth " + l[1] + " unit(s) at GUI " + l[0] + " -> " + got + " px; saved: "
                        + (after.contains("\"lineWidthPx\":" + l[2]) ? "lineWidthPx " + l[2] : after));
                c.check(got == l[2], "an old Line Width of " + l[1] + " unit(s) at GUI " + l[0] + " became " + got
                        + " px, want " + l[2]);
                c.check(after.contains("\"lineWidthPx\":" + l[2]), "the converted width was not saved: " + after);
            }
        } finally {
            restoreInventory(c, inv);
            HudEditorCases.restoreWindow(c, window, oldGui);
            restore(c, saved, ICFG);
        }
    }

    private static int runRight(BufferedImage img, int x, int y, int colour) {
        int n = 0;
        while (rgb(img, x + n, y) == colour) {
            n++;
        }
        return n;
    }

    private static void openChest(UiCase c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, new ContainerScreen(ChestMenu.sixRows(0, mc.player.getInventory(),
                    new SimpleContainer(54)), mc.player.getInventory(), Component.literal("Polish Test")));
            return null;
        });
        c.ticks(6);
    }

    // ==== 474 shared lines =============================================================================================

    static void sharedLines(UiCase c) throws Exception {
        Map<Path, byte[]> saved = snapshot(c, ICFG);
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        ItemStack[] inv = saveInventory(c);
        try {
            clearInventory(c);
            HudEditorCases.setWindow(c, 1280, 720, 2);
            int[] g = gui(c);
            int gs = g[0];
            for (int w : new int[]{1, 2}) {
                theme(c, w);
                int t = lineWidth(c);
                // -- chest: slot (col, row) at (8 + 18 col, 18 + 18 row) --
                int left = (g[1] - 176) / 2;
                int top = (g[2] - 222) / 2;
                openChest(c);
                BufferedImage chest = shot(c, "chest-line" + w);
                between(c, chest, "chest", left, top, 8, 18, gs, t);
                between(c, chest, "chest bottom row", left, top, 8 * 18 + 8 - 18, 18 + 5 * 18 - 18, gs, t);
                outer(c, chest, "chest", left, top, 8, 18, gs, t);
                // -- player inventory: main grid (8, 84), its hotbar row (8, 142), crafting grid (98, 18) --
                survival(c);
                c.onClient(mc -> {
                    McCompat.setScreen(mc, new InventoryScreen(mc.player));
                    return null;
                });
                c.ticks(6);
                boolean isInv = c.onClient(mc -> McCompat.screen(mc) instanceof InventoryScreen);
                c.check(isInv, "the survival inventory screen did not open");
                int ileft = (g[1] - 176) / 2;
                int itop = (g[2] - 166) / 2;
                BufferedImage invImg = shot(c, "inventory-line" + w);
                between(c, invImg, "inventory main grid", ileft, itop, 8, 84, gs, t);
                betweenAcross(c, invImg, "inventory hotbar row", ileft, itop, 8, 142, gs, t);
                between(c, invImg, "crafting grid", ileft, itop, 98, 18, gs, t);
                outer(c, invImg, "inventory main grid", ileft, itop, 8, 84, gs, t);
                c.onClient(mc -> {
                    McCompat.setScreen(mc, null);
                    return null;
                });
                SafeWorld.apply(c.ctx());
                // -- the in-game hotbar: between items 0 and 1 (items at cx - 88 + 20i, 16 wide); slot 4 selected, so its
                // heavier frame is nowhere near the lines measured --
                c.onClient(mc -> {
                    mc.player.getInventory().setSelectedSlot(4);
                    return null;
                });
                BufferedImage hb = shot(c, "hotbar-line" + w);
                int cx = g[1] / 2;
                int y = (g[2] - 11) * gs;
                int sep = rowCount(hb, y, (cx - 88 + 16) * gs, (cx - 68) * gs, AMBER);
                int sep7 = rowCount(hb, y, (cx - 88 + 7 * 20 + 16) * gs, (cx - 88 + 8 * 20) * gs, AMBER);
                int edge = runRight(hb, (cx - 91) * gs, y, AMBER);
                c.note(String.format(Locale.ROOT, "line %d px: hotbar between items 0|1 %d accent px, 7|8 %d, left border %d",
                        t, sep, sep7, edge));
                c.check(sep == t && sep7 == t, "the hotbar draws " + sep + " / " + sep7 + " px of line between two slots, "
                        + "want one line of " + t);
                c.check(edge == t, "the hotbar's outer border is " + edge + " px, want " + t);
            }
        } finally {
            SafeWorld.apply(c.ctx());
            restoreInventory(c, inv);
            HudEditorCases.restoreWindow(c, window, oldGui);
            restore(c, saved, ICFG);
        }
    }

    /** One line between the slot at (sx, sy) and its right neighbour, and between it and the one below. */
    private static void between(UiCase c, BufferedImage img, String what, int left, int top, int sx, int sy, int gs,
                                int t) {
        betweenAcross(c, img, what, left, top, sx, sy, gs, t);
        int x = (left + sx + 8) * gs;
        int down = colCount(img, x, (top + sy + 8) * gs, (top + sy + 18 + 8) * gs, AMBER);
        c.note(what + ": " + down + " accent px down the column between two stacked slots (want " + t + ")");
        c.check(down == t, what + ": " + down + " px of line between two stacked slots, want one line of " + t);
    }

    private static void betweenAcross(UiCase c, BufferedImage img, String what, int left, int top, int sx, int sy,
                                      int gs, int t) {
        int y = (top + sy + 8) * gs;
        int across = rowCount(img, y, (left + sx + 8) * gs, (left + sx + 18 + 8) * gs, AMBER);
        c.note(what + ": " + across + " accent px along the row between two side-by-side slots (want " + t + ")");
        c.check(across == t, what + ": " + across + " px of line between two side-by-side slots, want one line of " + t);
    }

    /** The group's outer line left of the slot at (sx, sy): exactly t pixels between the panel's inside and the item. */
    private static void outer(UiCase c, BufferedImage img, String what, int left, int top, int sx, int sy, int gs, int t) {
        int y = (top + sy + 8) * gs;
        int n = rowCount(img, y, (left + sx - 4) * gs, (left + sx) * gs, AMBER);
        c.note(what + ": " + n + " accent px left of the first slot (its outer line, want " + t + ")");
        c.check(n == t, what + ": the outer line is " + n + " px, want " + t);
    }

    // ==== 475 armour column against the model frame ====================================================================

    static void armourModel(UiCase c) throws Exception {
        Map<Path, byte[]> saved = snapshot(c, ICFG);
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        ItemStack[] inv = saveInventory(c);
        try {
            clearInventory(c);
            survival(c);
            int[][] sizes = {{1280, 720, 2}, {1920, 1080, 3}};
            for (int[] sz : sizes) {
                HudEditorCases.setWindow(c, sz[0], sz[1], sz[2]);
                survival(c);
                int[] g = gui(c);
                int gs = g[0];
                for (int w : new int[]{1, 3}) {
                    theme(c, w);
                    c.onClient(mc -> {
                        McCompat.setScreen(mc, new InventoryScreen(mc.player));
                        return null;
                    });
                    c.ticks(6);
                    int left = (g[1] - 176) / 2;
                    int top = (g[2] - 166) / 2;
                    BufferedImage img = shot(c, "gui" + gs + "-line" + w);
                    // Between the panel's top border and the main grid's first line (row 84, its square from 83).
                    int y0 = (top + 3) * gs;
                    int y1 = (top + 82) * gs;
                    int[] armour = span(img, (left + 16) * gs, y0, y1);
                    int[] model = span(img, (left + 50) * gs, y0, y1);
                    int[] modelRight = span(img, (left + 74) * gs, y0, y1);
                    String line = String.format(Locale.ROOT, "GUI %d line %d: armour column rows %s, model frame rows %s "
                                    + "(and %s near its right side)", gs, w, show(armour), show(model), show(modelRight));
                    c.note(line);
                    if (armour == null || model == null) {
                        c.problem("no outline found (" + line + ") - nothing to compare");
                    } else if (armour[0] != model[0] || armour[1] != model[1]) {
                        c.problem(String.format(Locale.ROOT, "the armour column's outline runs %d..%d, the model frame's "
                                + "%d..%d - top differs by %d px, bottom by %d px", armour[0], armour[1], model[0],
                                model[1], model[0] - armour[0], armour[1] - model[1]));
                    }
                    c.onClient(mc -> {
                        McCompat.setScreen(mc, null);
                        return null;
                    });
                }
            }
        } finally {
            SafeWorld.apply(c.ctx());
            restoreInventory(c, inv);
            HudEditorCases.restoreWindow(c, window, oldGui);
            restore(c, saved, ICFG);
        }
    }

    /** {first, last} accent row down column x in [y0, y1), or null. */
    private static int[] span(BufferedImage img, int x, int y0, int y1) {
        int first = -1;
        int last = -1;
        for (int y = y0; y < y1; y++) {
            if (rgb(img, x, y) == AMBER) {
                if (first < 0) {
                    first = y;
                }
                last = y;
            }
        }
        return first < 0 ? null : new int[]{first, last};
    }

    private static String show(int[] s) {
        return s == null ? "none" : s[0] + ".." + s[1];
    }

    // ==== 476 bar text centred =========================================================================================

    static void barTextCentred(UiCase c) throws Exception {
        Map<Path, byte[]> saved = snapshot(c, PS, HUD);
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            int[][] windows = {{854, 480, 2}, {1708, 960, 4}};
            for (int[] w : windows) {
                HudEditorCases.setWindow(c, w[0], w[1], w[2]);
                for (boolean predefined : new boolean[]{true, false}) {
                    bars(c, !predefined);
                    if (predefined) {
                        c.onClient(mc -> {
                            Object ps = Mod.cfg(PS);
                            Mod.call(ps, "moveTo", Mod.enumValue(READOUT, "HEALTH_BAR"), Mod.enumValue(AREA, "ABOVE_HOTBAR"),
                                    null);
                            Mod.call(ps, "moveTo", Mod.enumValue(READOUT, "MANA_BAR"), Mod.enumValue(AREA, "ABOVE_HOTBAR"),
                                    null);
                            Mod.call(ps, "save");
                            return null;
                        });
                    } else {
                        placeBar(c, "statbar_health", 40, 60);
                        placeBar(c, "statbar_mana", 40, 100);
                    }
                    for (boolean absorption : new boolean[]{false, true}) {
                        c.onClient(mc -> {
                            Class<?> f = R.cls(FEATURE);
                            R.setStatic(f, "healthCur", absorption ? 12000L : 10464L);
                            R.setStatic(f, "healthMax", 10464L);
                            R.setStatic(f, "manaCur", 12699L);
                            R.setStatic(f, "manaMax", 12699L);
                            return null;
                        });
                        c.ticks(3);
                        String tag = "gui" + w[2] + "-" + (predefined ? "predefined" : "custom")
                                + (absorption ? "-absorption" : "");
                        BufferedImage img = shot(c, tag);
                        centred(c, tag + " Health", img, w[2], HudFixCases.HEALTH & 0xFFFFFF,
                                absorption ? HudFixCases.ABSORB & 0xFFFFFF : -2);
                        centred(c, tag + " Mana", img, w[2], HudFixCases.MANA & 0xFFFFFF, -2);
                    }
                }
            }
        } finally {
            c.onClient(mc -> {
                Class<?> f = R.cls(FEATURE);
                R.setStatic(f, "healthCur", -1L);
                R.setStatic(f, "healthMax", -1L);
                R.setStatic(f, "manaCur", -1L);
                R.setStatic(f, "manaMax", -1L);
                return null;
            });
            HudEditorCases.restoreWindow(c, window, oldGui);
            restore(c, saved, HUD, PS);
        }
    }

    /**
     * The bar's box (its fill colour, and the absorption colour on the same rows) against the WHITE pixels of its number:
     * the gap left of the leftmost white pixel against the gap right of the rightmost, and the gap above the digits'
     * top row against the gap below their bottom row. The digits' rows are those holding at least a quarter of the
     * busiest row's white pixels, so a comma's one-unit tail is not counted as the number's height.
     */
    private static void centred(UiCase c, String tag, BufferedImage img, int gs, int fill, int absorb) {
        int bx0 = Integer.MAX_VALUE, by0 = Integer.MAX_VALUE, bx1 = -1, by1 = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (rgb(img, x, y) == fill) {
                    bx0 = Math.min(bx0, x);
                    by0 = Math.min(by0, y);
                    bx1 = Math.max(bx1, x);
                    by1 = Math.max(by1, y);
                }
            }
        }
        if (bx1 < 0) {
            c.problem(tag + ": the bar drew no pixel of its colour");
            return;
        }
        // The absorption segment on the same rows widens the box (the white number sits over both).
        if (absorb != -2) {
            for (int y = by0; y <= by1; y++) {
                for (int x = Math.max(0, bx0 - 400); x < Math.min(img.getWidth(), bx1 + 400); x++) {
                    if (rgb(img, x, y) == absorb) {
                        bx0 = Math.min(bx0, x);
                        bx1 = Math.max(bx1, x);
                    }
                }
            }
        }
        int[] rows = new int[by1 - by0 + 1];
        int tx0 = Integer.MAX_VALUE, tx1 = -1;
        for (int y = by0; y <= by1; y++) {
            for (int x = bx0; x <= bx1; x++) {
                if (rgb(img, x, y) == 0xFFFFFF) {
                    rows[y - by0]++;
                    tx0 = Math.min(tx0, x);
                    tx1 = Math.max(tx1, x);
                }
            }
        }
        int busiest = 0;
        for (int r : rows) {
            busiest = Math.max(busiest, r);
        }
        if (busiest == 0) {
            c.problem(tag + ": no number drawn on the bar - nothing to centre");
            return;
        }
        int ty0 = -1;
        int ty1 = -1;
        for (int i = 0; i < rows.length; i++) {
            if (rows[i] * 4 >= busiest) {
                if (ty0 < 0) {
                    ty0 = by0 + i;
                }
                ty1 = by0 + i;
            }
        }
        int left = tx0 - bx0;
        int right = bx1 - tx1;
        int above = ty0 - by0;
        int below = by1 - ty1;
        double offX = (left - right) / 2.0;
        double offY = (above - below) / 2.0;
        c.note(String.format(Locale.ROOT, "%s: bar %dx%d px, number %dx%d px (digit rows); room left %d right %d, above %d "
                        + "below %d px -> off centre by %.1f px across, %.1f px down", tag, bx1 - bx0 + 1, by1 - by0 + 1,
                tx1 - tx0 + 1, ty1 - ty0 + 1, left, right, above, below, offX, offY));
        c.check(Math.abs(offX) <= 1.0 && Math.abs(offY) <= 1.0, String.format(Locale.ROOT,
                "%s: the number is %.1f px off centre across and %.1f px down (room left %d / right %d, above %d / below "
                        + "%d) - want within 1 px", tag, offX, offY, left, right, above, below));
    }
}

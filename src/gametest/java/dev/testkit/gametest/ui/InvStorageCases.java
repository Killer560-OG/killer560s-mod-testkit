package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntPredicate;

/**
 * 451-454: the Inventory Theme on the hotbar and its new settings, and the Storage Overlay's centring and themes
 * (killer560, 2026-10-07). Every judgement is made on screenshots (GUI scale 2 unless a size says otherwise) and on the
 * mod's own frame counters, so a pass means the thing was drawn - not that a setter returned.
 *
 * <ul>
 *   <li>451-ui-hotbar-theme: vanilla vs themed hotbar (exact accent pixels, the dark panel between slots), vanilla's own
 *       overlays still drawn through the theme (stack count, durability bar, offhand item, cooldown, hotbar attack
 *       indicator - each measured as a pixel change), Line Width 0/1/3 as the border's pixel thickness, a custom accent
 *       and slot colour, and Hotbar Scale 50/100/200% as the bar's drawn top and right edge.</li>
 *   <li>452-ui-inventory-theme-settings: every new setting saved, reloaded from disk and read back; the setters' clamps
 *       equal the sliders' MIN/MAX; the Storage Overlay's old {@code darkMode} migrates (true to Amber, false to Light);
 *       and Line Width / Slot Color change the drawn slot squares of a real chest screen.</li>
 *   <li>453-ui-storage-overlay-centre: with killer560's own saved position ({@code storage_overlay x 0, y 20}) the grid
 *       and the Inventory panel are measured against the screen centre at six window sizes / GUI scales, and on the GUI 2
 *       screenshot from the drawn border pixels.</li>
 *   <li>454-ui-storage-overlay-themes: Amber / Dark / Light rendered for the overlay and the hotbar; Amber draws the old
 *       dark colours exactly, Dark and Light draw (almost) no orange pixels.</li>
 * </ul>
 */
final class InvStorageCases {

    private static final String ICFG = "inventorytheme.InventoryThemeConfig";
    private static final String HOTBAR = "inventorytheme.HotbarTheme";
    private static final String SCFG = "storageoverlay.StorageOverlayConfig";
    private static final String SFEAT = "storageoverlay.StorageOverlayFeature";
    private static final String THEME = "gui.PanelTheme";
    private static final int AMBER_ACCENT = 0xCC6600;

    private InvStorageCases() {
    }

    // ==== shared ======================================================================================================

    /**
     * Waits out a resource reload's loading overlay (the Pack Disabler cases before these reload resources; the first
     * suite run took every 451 picture through the red Mojang fade and read none of the accent). 26.1.2 has
     * {@code Minecraft.getOverlay()}; 26.2 has no such method (javap), so there only the settle applies.
     */
    static void awaitNoLoadingOverlay(UiCase c) {
        java.lang.reflect.Method m;
        try {
            m = Minecraft.class.getMethod("getOverlay");
        } catch (NoSuchMethodException e) {
            m = null;
        }
        if (m != null) {
            java.lang.reflect.Method getter = m;
            c.ctx().waitFor(mc -> {
                try {
                    return getter.invoke(mc) == null;
                } catch (ReflectiveOperationException e) {
                    return true;
                }
            }, 1200);
        }
        c.ticks(20);
    }

    private static boolean modHasThemes(UiCase c) {
        awaitNoLoadingOverlay(c);
        if (!Mod.has(THEME) || !Mod.has(HOTBAR)) {
            c.problem("this jar has no gui.PanelTheme / inventorytheme.HotbarTheme (a jar from before inv-storage)");
            return false;
        }
        return true;
    }

    /** Takes a screenshot (after {@code settle} ticks), keeps a copy in the report, returns the pixels. */
    static BufferedImage shot(UiCase c, String label, int settle) {
        c.ticks(settle);
        String name = c.name() + "-" + label;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        Path kept = dev.testkit.harness.Report.screenshot(name, taken);
        c.note("picture " + label + " -> " + (kept != null ? kept : taken));
        try {
            return javax.imageio.ImageIO.read(taken.toFile());
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read screenshot " + taken, e);
        }
    }

    /** Pixels in [x0,x1) x [y0,y1) (screen pixels, clipped to the image) that satisfy {@code p} (on 0xRRGGBB). */
    static int count(BufferedImage img, int x0, int y0, int x1, int y1, IntPredicate p) {
        int n = 0;
        for (int y = Math.max(0, y0); y < Math.min(img.getHeight(), y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(img.getWidth(), x1); x++) {
                if (p.test(img.getRGB(x, y) & 0xFFFFFF)) {
                    n++;
                }
            }
        }
        return n;
    }

    static IntPredicate exact(int rgb) {
        return v -> v == (rgb & 0xFFFFFF);
    }

    /** Orange: hue 15-45 degrees, saturation at least 0.5, value at least 0.35. */
    static boolean orange(int rgb) {
        float[] hsb = java.awt.Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
        float hue = hsb[0] * 360f;
        return hue >= 15f && hue <= 45f && hsb[1] >= 0.5f && hsb[2] >= 0.35f;
    }

    /** Pixels whose summed RGB differs by more than {@code threshold} between two frames. */
    static int changed(BufferedImage a, BufferedImage b, int x0, int y0, int x1, int y1, int threshold) {
        int n = 0;
        for (int y = Math.max(0, y0); y < Math.min(a.getHeight(), y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(a.getWidth(), x1); x++) {
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                int d = Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF))
                        + Math.abs((p & 0xFF) - (q & 0xFF));
                if (d > threshold) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Length of the run of {@code rgb} pixels going down column {@code x} from {@code y}. */
    static int runDown(BufferedImage img, int x, int y, int rgb) {
        int n = 0;
        while (y + n < img.getHeight() && (img.getRGB(x, y + n) & 0xFFFFFF) == (rgb & 0xFFFFFF)) {
            n++;
        }
        return n;
    }

    /** Getter/setter pairs to put back at the end of a case. */
    private static final class Restore {
        private final List<Runnable> undo = new ArrayList<>();

        void save(Object target, String property) {
            String getter = "get" + property;
            Object old;
            try {
                old = Mod.call(target, "is" + property);
            } catch (AssertionError e) {
                old = Mod.call(target, getter);
            }
            Object value = old;
            undo.add(() -> Mod.call(target, "set" + property, value));
        }

        void run(UiCase c) {
            c.onClient(mc -> {
                for (int i = undo.size() - 1; i >= 0; i--) {
                    try {
                        undo.get(i).run();
                    } catch (Throwable t) {
                        System.out.println("[inv-storage] restore failed: " + t);
                    }
                }
                return null;
            });
        }
    }

    private static Restore saveTheme(Object cfg) {
        Restore r = new Restore();
        for (String p : new String[]{"Enabled", "HypixelOnly", "ThemeHotbar", "HotbarScale", "LineWidth", "Theme",
                "UseCustomAccent", "CustomAccentColor", "UseCustomSlotColor", "CustomSlotColor", "BackgroundOpacity"}) {
            r.save(cfg, p);
        }
        return r;
    }

    /** The theme on, every container, hotbar themed, Amber, line 1, scale 1, no custom colours, opacity 0.85. */
    private static void themeDefaults(UiCase c, Object cfg) {
        c.onClient(mc -> {
            Mod.call(cfg, "setEnabled", true);
            Mod.call(cfg, "setHypixelOnly", false);
            Mod.call(cfg, "setThemeHotbar", true);
            Mod.call(cfg, "setHotbarScale", 1.0f);
            Mod.call(cfg, "setLineWidth", 1);
            Mod.call(cfg, "setTheme", Mod.enumValue(THEME, "AMBER"));
            Mod.call(cfg, "setUseCustomAccent", false);
            Mod.call(cfg, "setUseCustomSlotColor", false);
            Mod.call(cfg, "setBackgroundOpacity", 0.85f);
            return null;
        });
    }

    private static void set(UiCase c, Object cfg, String setter, Object value) {
        c.onClient(mc -> Mod.call(cfg, setter, value));
    }

    /** Saves hotbar slots 0-8, the offhand and the selected slot; returns the undo. */
    private static Runnable saveInventory(UiCase c) {
        ItemStack[] old = c.onClient(mc -> {
            ItemStack[] o = new ItemStack[11];
            for (int i = 0; i < 9; i++) {
                o[i] = mc.player.getInventory().getItem(i).copy();
            }
            o[9] = mc.player.getInventory().getItem(40).copy();
            o[10] = new ItemStack(Items.STICK, mc.player.getInventory().getSelectedSlot() + 1);
            return o;
        });
        return () -> c.onClient(mc -> {
            for (int i = 0; i < 9; i++) {
                mc.player.getInventory().setItem(i, old[i]);
            }
            mc.player.getInventory().setItem(40, old[9]);
            mc.player.getInventory().setSelectedSlot(old[10].getCount() - 1);
            return null;
        });
    }

    private static void clearHotbar(UiCase c) {
        c.onClient(mc -> {
            for (int i = 0; i < 9; i++) {
                mc.player.getInventory().setItem(i, ItemStack.EMPTY);
            }
            mc.player.getInventory().setItem(40, ItemStack.EMPTY);
            mc.player.getInventory().setSelectedSlot(0);
            McCompat.clearChatAndToasts(mc);
            McCompat.setScreen(mc, null);
            return null;
        });
    }

    /** Hotbar geometry in screen pixels for a GUI-scaled size {@code gw x gh} at scale {@code s}. */
    private record Bar(int s, int gw, int gh) {
        int cx() {
            return gw / 2;
        }

        int px(double gui) {
            return (int) Math.round(gui * s);
        }

        /** The 182x22 bar at hotbar scale 1. */
        int[] rect() {
            return new int[]{px(cx() - 91), px(gh - 22), px(cx() + 91), px(gh)};
        }

        /** Item i's 16x16 rect (i = 9: the left offhand). */
        int[] item(int i) {
            double x = i == 9 ? cx() - 91 - 26 : cx() - 90 + i * 20 + 2;
            double y = gh - 19;
            return new int[]{px(x), px(y), px(x + 16), px(y + 16)};
        }
    }

    private static Bar bar(UiCase c) {
        return c.onClient(mc -> new Bar(mc.getWindow().getGuiScale(), mc.getWindow().getGuiScaledWidth(),
                mc.getWindow().getGuiScaledHeight()));
    }

    // ==== 451 =========================================================================================================

    static void hotbar(UiCase c) {
        if (!modHasThemes(c)) {
            return;
        }
        Object cfg = Mod.cfg(ICFG);
        Restore restore = saveTheme(cfg);
        Runnable inv = saveInventory(c);
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        AttackIndicatorStatus oldAttack = c.onClient(mc -> mc.options.attackIndicator().get());
        try {
            HudEditorCases.setWindow(c, 1280, 720, 2);
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                McCompat.clearChatAndToasts(mc);
                mc.options.attackIndicator().set(AttackIndicatorStatus.HOTBAR);
                var in = mc.player.getInventory();
                for (int i = 0; i < 9; i++) {
                    in.setItem(i, ItemStack.EMPTY);
                }
                in.setItem(0, new ItemStack(Items.DIAMOND, 5));
                ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
                sword.setDamageValue(sword.getMaxDamage() / 2);
                in.setItem(1, sword);
                in.setItem(2, new ItemStack(Items.ENDER_PEARL, 16));
                in.setItem(3, new ItemStack(Items.NETHERITE_AXE));
                in.setItem(40, new ItemStack(Items.APPLE, 3));
                in.setSelectedSlot(3);
                return null;
            });
            Bar b = bar(c);
            c.note("window " + window[0] + "x" + window[1] + " -> 1280x720, GUI " + b.s() + ", " + b.gw() + "x" + b.gh()
                    + " GUI units");
            c.check(b.s() == 2, "GUI scale is " + b.s() + ", not 2");
            int[] r = b.rect();
            // The offhand square sits left of the bar; the attack indicator right of it.
            int rx0 = b.px(b.cx() - 91 - 30);
            int rx1 = b.px(b.cx() + 91 + 30);

            // -- vanilla --
            set(c, cfg, "setEnabled", false);
            long van0 = ((Number) Mod.field(HOTBAR, "vanillaFrames")).longValue();
            BufferedImage van = shot(c, "vanilla-gui2", 6);
            long van1 = ((Number) Mod.field(HOTBAR, "vanillaFrames")).longValue();
            c.check(van1 > van0, "the hotbar layer was never called with the theme off (vanillaFrames " + van0 + " -> "
                    + van1 + ") - the layer wrap is not installed");

            // -- Amber --
            themeDefaults(c, cfg);
            long th0 = ((Number) Mod.field(HOTBAR, "themedFrames")).longValue();
            BufferedImage amb = shot(c, "amber-gui2", 6);
            long th1 = ((Number) Mod.field(HOTBAR, "themedFrames")).longValue();
            c.check(th1 > th0, "the themed hotbar never ran (themedFrames " + th0 + " -> " + th1 + ")");
            int accVan = count(van, rx0, r[1] - 4, rx1, r[3], exact(AMBER_ACCENT));
            int accAmb = count(amb, rx0, r[1] - 4, rx1, r[3], exact(AMBER_ACCENT));
            c.note("Amber accent #CC6600 pixels in the hotbar area: vanilla " + accVan + ", themed " + accAmb);
            if (accAmb < 600 || accVan > 10) {
                c.problem("the themed hotbar does not draw the theme's accent: " + accAmb + " px themed, " + accVan
                        + " vanilla");
            }
            // Between slot 0 and 1 (GUI x cx-70), mid height: vanilla's grey sprite, or the theme's near-black panel.
            int gx = b.px(b.cx() - 70);
            int gy = b.px(b.gh() - 11);
            int gapVan = van.getRGB(gx, gy) & 0xFFFFFF;
            int gapAmb = amb.getRGB(gx, gy) & 0xFFFFFF;
            c.note(String.format(Locale.ROOT, "between slots 0 and 1: vanilla #%06X, themed #%06X", gapVan, gapAmb));
            if (((gapAmb >> 16) & 0xFF) > 50 || ((gapAmb >> 8) & 0xFF) > 50 || (gapAmb & 0xFF) > 50) {
                c.problem(String.format(Locale.ROOT, "the themed panel is not dark between the slots (#%06X) - the "
                        + "vanilla hotbar sprite is still drawn", gapAmb));
            }
            if (gapVan == gapAmb) {
                c.problem("the pixel between slots is identical with the theme on and off");
            }

            // -- vanilla's overlays through the theme --
            int[] s0 = b.item(0);
            IntPredicate white = v -> ((v >> 16) & 0xFF) >= 235 && ((v >> 8) & 0xFF) >= 235 && (v & 0xFF) >= 235;
            int countVan = count(van, s0[0] + b.px(6), s0[1] + b.px(8), s0[2] + b.px(2), s0[3] + b.px(1), white);
            int countAmb = count(amb, s0[0] + b.px(6), s0[1] + b.px(8), s0[2] + b.px(2), s0[3] + b.px(1), white);
            c.note("stack count '5' white pixels: vanilla " + countVan + ", themed " + countAmb);
            if (countAmb < 6 || countVan < 6) {
                c.problem("the stack count is not drawn (white px vanilla " + countVan + ", themed " + countAmb + ")");
            }
            int[] s1 = b.item(1);
            IntPredicate barColour = v -> (v & 0xFF) < 70 && Math.max((v >> 16) & 0xFF, (v >> 8) & 0xFF) >= 150;
            int durVan = count(van, s1[0], s1[1] + b.px(13), s1[2], s1[1] + b.px(15), barColour);
            int durAmb = count(amb, s1[0], s1[1] + b.px(13), s1[2], s1[1] + b.px(15), barColour);
            c.note("durability bar pixels under the half-used sword: vanilla " + durVan + ", themed " + durAmb);
            if (durAmb < 6 || durVan < 6) {
                c.problem("the durability bar is not drawn (vanilla " + durVan + ", themed " + durAmb + ")");
            }
            int[] off = b.item(9);
            IntPredicate red = v -> ((v >> 16) & 0xFF) >= 150 && ((v >> 8) & 0xFF) <= 90 && (v & 0xFF) <= 90;
            int appleVan = count(van, off[0], off[1], off[2], off[3], red);
            int appleAmb = count(amb, off[0], off[1], off[2], off[3], red);
            int offAcc = count(amb, b.px(b.cx() - 91 - 30), r[1], b.px(b.cx() - 91 - 2), r[3], exact(AMBER_ACCENT));
            c.note("offhand apple red pixels: vanilla " + appleVan + ", themed " + appleAmb + "; themed offhand square "
                    + offAcc + " accent px");
            if (appleAmb < 10 || appleVan < 10) {
                c.problem("the offhand item is not drawn (vanilla " + appleVan + ", themed " + appleAmb + ")");
            }
            if (offAcc < 60) {
                c.problem("the offhand slot is not themed (" + offAcc + " accent px)");
            }

            // Cooldown and the hotbar attack indicator: a frame right after each starts against one after both ended.
            c.onClient(mc -> {
                mc.player.getCooldowns().addCooldown(mc.player.getInventory().getItem(2), 80);
                mc.player.resetAttackStrengthTicker();
                return null;
            });
            BufferedImage busy = shot(c, "amber-cooldown-attack", 2);
            c.ticks(90);
            BufferedImage idle = shot(c, "amber-idle", 2);
            int[] s2 = b.item(2);
            int cool = changed(busy, idle, s2[0], s2[1], s2[2], s2[3], 60);
            int ax = b.px(b.cx() + 91 + 6);
            int ay = b.px(b.gh() - 20);
            int attack = changed(busy, idle, ax, ay, ax + b.px(18), ay + b.px(18), 60);
            c.note("cooldown: " + cool + " px of the pearl's slot changed while it ran; attack indicator: " + attack
                    + " px changed right of the bar while recharging");
            if (cool < 40) {
                c.problem("the item cooldown overlay is not drawn through the theme (" + cool + " px changed)");
            }
            if (attack < 20) {
                c.problem("the hotbar attack indicator is not drawn through the theme (" + attack + " px changed)");
            }

            // -- Line Width: the panel's top border down the column between slots 0 and 1 --
            int top = r[1];
            int run1 = runDown(amb, gx, top, AMBER_ACCENT);
            set(c, cfg, "setLineWidth", 3);
            BufferedImage l3 = shot(c, "amber-line3", 4);
            int run3 = runDown(l3, gx, top, AMBER_ACCENT);
            set(c, cfg, "setLineWidth", 0);
            BufferedImage l0 = shot(c, "amber-line0", 4);
            int run0 = runDown(l0, gx, top, AMBER_ACCENT);
            c.note("top border thickness at line width 1/3/0: " + run1 + "/" + run3 + "/" + run0 + " px (want "
                    + b.px(1) + "/" + b.px(3) + "/0)");
            if (run1 != b.px(1) || run3 != b.px(3) || run0 != 0) {
                c.problem("Line Width does not set the border thickness: " + run1 + "/" + run3 + "/" + run0 + " px");
            }
            set(c, cfg, "setLineWidth", 1);

            // -- custom colours --
            c.onClient(mc -> {
                Mod.call(cfg, "setUseCustomAccent", true);
                Mod.call(cfg, "setCustomAccentColor", 0xFF00FF00);
                Mod.call(cfg, "setUseCustomSlotColor", true);
                Mod.call(cfg, "setCustomSlotColor", 0xFF0000FF);
                return null;
            });
            BufferedImage col = shot(c, "custom-colours", 4);
            int green = count(col, rx0, r[1] - 4, rx1, r[3], exact(0x00FF00));
            int blue = count(col, rx0, r[1] - 4, rx1, r[3], exact(0x0000FF));
            int amber = count(col, rx0, r[1] - 4, rx1, r[3], exact(AMBER_ACCENT));
            c.note("custom accent green " + green + " px, custom slot blue " + blue + " px, Amber left " + amber + " px");
            if (green < 600 || blue < 2000 || amber > 0) {
                c.problem("custom colours not drawn: green " + green + ", blue " + blue + ", amber " + amber);
            }
            c.onClient(mc -> {
                Mod.call(cfg, "setUseCustomAccent", false);
                Mod.call(cfg, "setUseCustomSlotColor", false);
                return null;
            });

            // -- Hotbar Scale: the drawn top edge and right edge of the bar --
            for (float scale : new float[]{0.5f, 1.0f, 2.0f}) {
                set(c, cfg, "setHotbarScale", scale);
                BufferedImage img = shot(c, "scale" + Math.round(scale * 100), 4);
                int topY = -1;
                int rightX = -1;
                for (int y = 0; y < img.getHeight() && topY < 0; y++) {
                    for (int x = b.px(b.cx()); x < img.getWidth(); x++) {
                        if ((img.getRGB(x, y) & 0xFFFFFF) == AMBER_ACCENT) {
                            topY = y;
                            break;
                        }
                    }
                }
                for (int x = img.getWidth() - 1; x >= 0 && rightX < 0; x--) {
                    for (int y = b.px(b.gh() - 2); y < img.getHeight(); y++) {
                        if ((img.getRGB(x, y) & 0xFFFFFF) == AMBER_ACCENT) {
                            rightX = x;
                            break;
                        }
                    }
                }
                int wantTop = b.px(b.gh() - 22 * scale);
                int wantRight = b.px(b.cx() + 91 * scale) - 1;
                c.note(String.format(Locale.ROOT, "scale %.0f%%: bar top %d (want %d), right edge %d (want %d)",
                        scale * 100, topY, wantTop, rightX, wantRight));
                if (Math.abs(topY - wantTop) > 2 || Math.abs(rightX - wantRight) > 2) {
                    c.problem(String.format(Locale.ROOT, "Hotbar Scale %.0f%% draws the bar top %d / right %d, want %d / %d",
                            scale * 100, topY, rightX, wantTop, wantRight));
                }
            }
        } finally {
            restore.run(c);
            inv.run();
            c.onClient(mc -> {
                mc.options.attackIndicator().set(oldAttack);
                return null;
            });
            HudEditorCases.restoreWindow(c, window, oldGui);
        }
    }

    // ==== 452 =========================================================================================================

    static void settings(UiCase c) throws Exception {
        if (!modHasThemes(c)) {
            return;
        }
        Object cfg0 = Mod.cfg(ICFG);
        Restore restore = saveTheme(cfg0);
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        Path storageFile = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config",
                "killer560smod-storageoverlay.json"));
        byte[] storageWas = Files.exists(storageFile) ? Files.readAllBytes(storageFile) : null;
        try {
            // -- round trip through the file --
            c.onClient(mc -> {
                Mod.call(cfg0, "setTheme", Mod.enumValue(THEME, "LIGHT"));
                Mod.call(cfg0, "setThemeHotbar", false);
                Mod.call(cfg0, "setHotbarScale", 1.37f);
                Mod.call(cfg0, "setLineWidth", 3);
                Mod.call(cfg0, "setUseCustomSlotColor", true);
                Mod.call(cfg0, "setCustomSlotColor", 0xFF123456);
                Mod.call(cfg0, "save");
                Mod.staticCall(ICFG, "load");
                return null;
            });
            Object cfg = Mod.cfg(ICFG);
            String back = c.onClient(mc -> Mod.call(cfg, "getTheme") + " hotbar=" + Mod.call(cfg, "isThemeHotbar")
                    + " scale=" + Mod.call(cfg, "getHotbarScale") + " line=" + Mod.call(cfg, "getLineWidth")
                    + " slotCustom=" + Mod.call(cfg, "isUseCustomSlotColor") + " slot="
                    + Integer.toHexString((Integer) Mod.call(cfg, "getCustomSlotColor")));
            c.note("after save + load from disk: " + back);
            c.check(back.equals("LIGHT hotbar=false scale=1.37 line=3 slotCustom=true slot=ff123456"),
                    "settings did not survive a reload: " + back);
            c.check(cfg != cfg0, "load() did not replace the instance - the reload proved nothing");

            // -- clamps equal the sliders' bounds --
            int minLine = ((Number) Mod.field(ICFG, "MIN_LINE_WIDTH")).intValue();
            int maxLine = ((Number) Mod.field(ICFG, "MAX_LINE_WIDTH")).intValue();
            float minScale = ((Number) Mod.field(ICFG, "MIN_HOTBAR_SCALE")).floatValue();
            float maxScale = ((Number) Mod.field(ICFG, "MAX_HOTBAR_SCALE")).floatValue();
            String clamps = c.onClient(mc -> {
                Mod.call(cfg, "setLineWidth", maxLine + 5);
                Object hi = Mod.call(cfg, "getLineWidth");
                Mod.call(cfg, "setLineWidth", minLine - 5);
                Object lo = Mod.call(cfg, "getLineWidth");
                Mod.call(cfg, "setHotbarScale", maxScale + 3f);
                Object shi = Mod.call(cfg, "getHotbarScale");
                Mod.call(cfg, "setHotbarScale", minScale - 3f);
                Object slo = Mod.call(cfg, "getHotbarScale");
                Mod.call(cfg, "setHotbarScale", Float.NaN);
                Object nan = Mod.call(cfg, "getHotbarScale");
                return hi + "," + lo + "," + shi + "," + slo + "," + nan;
            });
            String want = maxLine + "," + minLine + "," + maxScale + "," + minScale + ",1.0";
            c.note("clamps (line hi, lo, scale hi, lo, NaN): " + clamps + " - slider bounds " + minLine + ".." + maxLine
                    + ", " + minScale + ".." + maxScale);
            c.check(clamps.equals(want), "setter clamps " + clamps + " differ from the slider bounds " + want);
            c.check(minLine == 0 && maxLine >= 3, "line width range " + minLine + ".." + maxLine);

            // -- Storage Overlay: old darkMode migrates, theme round-trips --
            String[][] files = {
                    {"{\"enabled\":true,\"darkMode\":true,\"scale\":1.0,\"columns\":3}", "AMBER"},
                    {"{\"enabled\":true,\"darkMode\":false,\"scale\":1.0,\"columns\":3}", "LIGHT"},
                    {"{\"enabled\":true,\"scale\":1.0,\"columns\":3}", "AMBER"},
                    {"{\"enabled\":true,\"darkMode\":true,\"theme\":\"DARK\"}", "DARK"},
                    {"{\"enabled\":true,\"darkMode\":false,\"theme\":\"nonsense\"}", "LIGHT"},
                    // An older jar flipped darkMode after this one wrote AMBER: its choice wins.
                    {"{\"enabled\":true,\"darkMode\":false,\"theme\":\"AMBER\"}", "LIGHT"}};
            for (String[] f : files) {
                Files.writeString(storageFile, f[0], StandardCharsets.UTF_8);
                String got = c.onClient(mc -> {
                    Mod.staticCall(SCFG, "load");
                    return String.valueOf(Mod.call(Mod.cfg(SCFG), "getTheme"));
                });
                c.note("storage overlay " + f[0] + " -> " + got);
                if (!got.equals(f[1])) {
                    c.problem("storage overlay file " + f[0] + " loads as " + got + ", expected " + f[1]);
                }
            }
            c.onClient(mc -> {
                Object s = Mod.cfg(SCFG);
                Mod.call(s, "setTheme", Mod.enumValue(THEME, "DARK"));
                Mod.call(s, "save");
                return null;
            });
            String saved = Files.readString(storageFile, StandardCharsets.UTF_8);
            c.note("storage overlay saved as: " + saved.replaceAll("\\s+", " "));
            c.check(saved.contains("\"theme\": \"DARK\"") && saved.contains("\"darkMode\": true"),
                    "the storage overlay file does not hold theme DARK (and darkMode true for older jars): " + saved);

            // -- Line Width and Slot Color on a real chest screen --
            HudEditorCases.setWindow(c, 1280, 720, 2);
            themeDefaults(c, cfg);
            Bar b = bar(c);
            int left = (b.gw() - 176) / 2;
            int topPos = (b.gh() - 222) / 2;
            int colX = b.px(left + 7 + 9);
            int rowY = b.px(topPos + 17);
            int[] runs = new int[3];
            int[] slotPx = new int[3];
            int[] widths = {1, 3, 0};
            for (int i = 0; i < widths.length; i++) {
                int w = widths[i];
                c.onClient(mc -> {
                    Mod.call(cfg, "setLineWidth", w);
                    Mod.call(cfg, "setUseCustomSlotColor", true);
                    Mod.call(cfg, "setCustomSlotColor", 0xFF0000FF);
                    return null;
                });
                Screen s = c.onClient(mc -> {
                    ContainerScreen screen = new ContainerScreen(ChestMenu.sixRows(0, mc.player.getInventory(),
                            new SimpleContainer(54)), mc.player.getInventory(), Component.literal("Theme Test"));
                    McCompat.setScreen(mc, screen);
                    return screen;
                });
                BufferedImage img = shot(c, "chest-line" + w, 8);
                runs[i] = runDown(img, colX, rowY, AMBER_ACCENT);
                slotPx[i] = img.getRGB(colX, b.px(topPos + 17 + 9)) & 0xFFFFFF;
                c.onClient(mc -> {
                    McCompat.setScreen(mc, null);
                    return null;
                });
            }
            c.note(String.format(Locale.ROOT, "chest slot 0 top line at width 1/3/0: %d/%d/%d px; slot centre #%06X",
                    runs[0], runs[1], runs[2], slotPx[0]));
            if (runs[0] != b.px(1) || runs[1] != b.px(3) || runs[2] != 0) {
                c.problem("Line Width does not set the chest's slot lines: " + runs[0] + "/" + runs[1] + "/" + runs[2]);
            }
            if (slotPx[0] != 0x0000FF) {
                c.problem(String.format(Locale.ROOT, "Slot Color blue is not drawn in the chest (#%06X)", slotPx[0]));
            }
        } finally {
            if (storageWas != null) {
                Files.write(storageFile, storageWas);
            } else {
                Files.deleteIfExists(storageFile);
            }
            c.onClient(mc -> {
                Mod.staticCall(SCFG, "load");
                return null;
            });
            restore.run(c);
            c.onClient(mc -> {
                Mod.call(Mod.cfg(ICFG), "save");
                return null;
            });
            HudEditorCases.restoreWindow(c, window, oldGui);
        }
    }

    // ==== 453 / 454: the Storage Overlay ===============================================================================

    /** Fills the overlay's cache with three Ender Chest pages and three placeholder backpacks, so the grid has three
     *  columns and two rows; page 1 is the one the test screen opens (the active page). */
    private static void seedStorages(UiCase c) {
        c.onClient(mc -> {
            String prefix = (String) Mod.staticCall(SFEAT, "accountProfilePrefix");
            Object cache = Mod.staticCall("storageoverlay.StorageOverlayCache", "getInstance");
            for (int page = 2; page <= 3; page++) {
                List<ItemStack> items = new ArrayList<>();
                for (int i = 0; i < 45; i++) {
                    items.add(i % 7 == 0 ? new ItemStack(page == 2 ? Items.DIAMOND : Items.EMERALD, 1 + i % 5)
                            : ItemStack.EMPTY);
                }
                Mod.call(cache, "put", prefix + "|enderchest_" + page, items);
            }
            for (int n = 1; n <= 3; n++) {
                Mod.call(cache, "markKnown", prefix + "|backpack_" + n);
            }
            return null;
        });
    }

    /** Opens a client-side "Ender Chest (1/9)" (Hypixel's title; the chrome row holds a barrier so it is captured). */
    private static void openEnderChest(UiCase c) {
        c.onClient(mc -> {
            SimpleContainer box = new SimpleContainer(54);
            box.setItem(0, new ItemStack(Items.BARRIER));
            box.setItem(9, new ItemStack(Items.DIAMOND, 5));
            box.setItem(10, new ItemStack(Items.EMERALD));
            ContainerScreen screen = new ContainerScreen(ChestMenu.sixRows(0, mc.player.getInventory(), box),
                    mc.player.getInventory(), Component.literal("Ender Chest (1/9)"));
            Mod.setField(SFEAT, "lastPos", null);
            McCompat.setScreen(mc, screen);
            return null;
        });
        c.ctx().waitFor(mc -> Mod.field(SFEAT, "lastPos") != null, 60);
        c.ticks(4);
    }

    /** {grid x, grid width, inventory x, inventory width} in GUI units, from the overlay's last frame. */
    private static float[] overlayBox(UiCase c) {
        return c.onClient(mc -> {
            int[] pos = (int[]) Mod.field(SFEAT, "lastPos");
            int vw = ((Number) Mod.field(SFEAT, "lastViewportWidthPx")).intValue();
            int[] inv = (int[]) Mod.field(SFEAT, "lastInventoryOrigin");
            float invScale = ((Number) Mod.field(SFEAT, "lastInventoryScale")).floatValue();
            int panelW = ((Number) Mod.field(SFEAT, "PANEL_WIDTH")).intValue();
            return new float[]{pos[0], vw, inv == null ? -1 : inv[0], panelW * invScale, pos[1]};
        });
    }

    static void storageCentre(UiCase c) throws Exception {
        awaitNoLoadingOverlay(c);
        Object scfg = Mod.cfg(SCFG);
        Object hud = Mod.staticCall("hud.HudConfig", "getInstance");
        boolean oldEnabled = c.onClient(mc -> (Boolean) Mod.call(scfg, "isEnabled"));
        float oldScale = c.onClient(mc -> ((Number) Mod.call(scfg, "getScale")).floatValue());
        int oldColumns = c.onClient(mc -> ((Number) Mod.call(scfg, "getColumns")).intValue());
        boolean hadPos = c.onClient(mc -> (Boolean) Mod.call(hud, "hasPosition", "storage_overlay"));
        int[] oldPos = c.onClient(mc -> (int[]) Mod.call(hud, "getPosition", "storage_overlay", 0, 0));
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        int[][] sizes = {{1280, 720, 2}, {854, 480, 2}, {1920, 1080, 3}, {2560, 1440, 3}, {1600, 900, 4},
                {1920, 1080, 2}};
        try {
            seedStorages(c);
            c.onClient(mc -> {
                Mod.call(scfg, "setEnabled", true);
                Mod.call(scfg, "setScale", 1.0f);
                Mod.call(scfg, "setColumns", 3);
                // His own HUD config, 2026-10-07 20:26: the editor had saved the overlay at x 0.
                Mod.call(hud, "setPosition", "storage_overlay", 0, 20);
                return null;
            });
            for (int pass = 0; pass < 2; pass++) {
                float sliderScale = pass == 0 ? 1.0f : 1.25f;
                c.onClient(mc -> Mod.call(scfg, "setScale", sliderScale));
                for (int[] sz : sizes) {
                    HudEditorCases.setWindow(c, sz[0], sz[1], sz[2]);
                    openEnderChest(c);
                    Bar b = bar(c);
                    float[] box = overlayBox(c);
                    float gridCentre = box[0] + box[1] / 2f;
                    float invCentre = box[2] + box[3] / 2f;
                    float screenCentre = b.gw() / 2f;
                    String line = String.format(Locale.ROOT, "%dx%d GUI %d (%dx%d units), Scale %.0f%%: grid x %.0f w %.0f"
                                    + " centre %.1f, inventory centre %.1f, screen centre %.1f",
                            sz[0], sz[1], b.s(), b.gw(), b.gh(), sliderScale * 100, box[0], box[1], gridCentre, invCentre,
                            screenCentre);
                    c.note(line);
                    if (Math.abs(gridCentre - screenCentre) > 1.0f) {
                        c.problem("grid not centred: " + line);
                    }
                    if (box[2] < 0 || Math.abs(invCentre - screenCentre) > 1.0f) {
                        c.problem("inventory panel not centred: " + line);
                    }
                    if (pass == 0 && sz[0] == 1280 && sz[2] == 2) {
                        // On the picture: the outer border pixels of the panels' top row.
                        BufferedImage img = shot(c, "overlay-gui2", 4);
                        // 30 units into the grid: below the Search button beside it (16 tall, same top), through the
                        // left and right borders of the first row of panels (each at least 70 tall).
                        int y = b.px(box[4] + 30);
                        // A jar from before the themes (the "before" run) draws the old dark colours.
                        int border = Mod.has(THEME)
                                ? c.onClient(mc -> (Integer) themeField(Mod.call(scfg, "getTheme"), "border")) : 0xFF553311;
                        int active = Mod.has(THEME)
                                ? c.onClient(mc -> (Integer) themeField(Mod.call(scfg, "getTheme"), "activeBorder"))
                                : 0xFFCC6600;
                        int lx = -1;
                        int rx = -1;
                        for (int x = 0; x < img.getWidth(); x++) {
                            int v = img.getRGB(x, y) & 0xFFFFFF;
                            if (v == (border & 0xFFFFFF) || v == (active & 0xFFFFFF)) {
                                if (lx < 0) {
                                    lx = x;
                                }
                                rx = x;
                            }
                        }
                        double centrePx = (lx + rx + 1) / 2.0;
                        c.note(String.format(Locale.ROOT, "screenshot: panel borders on row %d span x %d..%d, centre %.1f px "
                                + "of a %d px wide frame", y, lx, rx, centrePx, img.getWidth()));
                        if (lx < 0 || Math.abs(centrePx - img.getWidth() / 2.0) > 2.5) {
                            c.problem("on the screenshot the panels span " + lx + ".." + rx + " of " + img.getWidth()
                                    + " px - not centred");
                        }
                    }
                    c.onClient(mc -> {
                        McCompat.setScreen(mc, null);
                        return null;
                    });
                }
            }
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.call(scfg, "setEnabled", oldEnabled);
                Mod.call(scfg, "setScale", oldScale);
                Mod.call(scfg, "setColumns", oldColumns);
                if (hadPos) {
                    Mod.call(hud, "setPosition", "storage_overlay", oldPos[0], oldPos[1]);
                }
                return null;
            });
            HudEditorCases.restoreWindow(c, window, oldGui);
        }
    }

    private static Object themeField(Object theme, String field) {
        return Mod.field(theme, field);
    }

    static void storageThemes(UiCase c) throws Exception {
        if (!modHasThemes(c)) {
            return;
        }
        Object scfg = Mod.cfg(SCFG);
        Object icfg = Mod.cfg(ICFG);
        Restore restore = saveTheme(icfg);
        Object oldTheme = c.onClient(mc -> Mod.call(scfg, "getTheme"));
        boolean oldEnabled = c.onClient(mc -> (Boolean) Mod.call(scfg, "isEnabled"));
        Runnable inv = saveInventory(c);
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            seedStorages(c);
            clearHotbar(c);
            HudEditorCases.setWindow(c, 1280, 720, 2);
            themeDefaults(c, icfg);
            c.onClient(mc -> {
                Mod.call(scfg, "setEnabled", true);
                Mod.call(scfg, "setScale", 1.0f);
                Mod.call(scfg, "setColumns", 3);
                return null;
            });
            Bar b = bar(c);
            int[] r = b.rect();
            for (String name : new String[]{"AMBER", "DARK", "LIGHT"}) {
                Object theme = Mod.enumValue(THEME, name);
                String lower = name.toLowerCase(Locale.ROOT);
                c.onClient(mc -> {
                    Mod.call(scfg, "setTheme", theme);
                    Mod.call(icfg, "setTheme", theme);
                    return null;
                });
                // The overlay.
                openEnderChest(c);
                float[] box = overlayBox(c);
                BufferedImage img = shot(c, "overlay-" + lower + "-gui2", 4);
                int x0 = b.px(box[0]);
                int y0 = b.px(box[4]);
                int x1 = b.px(box[0] + box[1]);
                int y1 = Math.min(img.getHeight(), y0 + b.px(150));
                int orange = count(img, x0, y0, x1, y1, InvStorageCases::orange);
                int border = (Integer) themeField(theme, "border");
                int cellLine = (Integer) themeField(theme, "cellLine");
                int borders = count(img, x0, y0, x1, y1, exact(border));
                int lines = count(img, x0, y0, x1, y1, exact(cellLine));
                int oldDark = count(img, x0, y0, x1, y1, exact(0x553311));
                c.note(String.format(Locale.ROOT, "%s overlay: %d orange px, %d border #%06X px, %d cell line #%06X px, "
                        + "%d old-dark-border #553311 px", name, orange, borders, border & 0xFFFFFF, lines,
                        cellLine & 0xFFFFFF, oldDark));
                if (borders < 200 || lines < 200) {
                    c.problem(name + ": the overlay's border/cell-line colours are not on screen (" + borders + "/" + lines
                            + " px) - nothing to judge");
                }
                if (name.equals("AMBER")) {
                    // Exactly the old dark mode's colours: border #553311, cell line #37373C, active #CC6600.
                    if (border != 0xFF553311 || cellLine != 0xFF37373C
                            || (Integer) themeField(theme, "activeBorder") != 0xFFCC6600
                            || (Integer) themeField(theme, "panelBg") != 0xCC101010
                            || (Integer) themeField(theme, "cellBg") != 0xFF1E1E22
                            || (Integer) themeField(theme, "viewportBg") != 0xD0000000) {
                        c.problem("Amber's colours are not the old dark mode's");
                    }
                    if (orange < 300) {
                        c.problem("Amber shows only " + orange + " orange px - the active page's orange border is missing");
                    }
                } else {
                    if (orange > 12) {
                        c.problem(name + " still draws " + orange + " orange px in the overlay");
                    }
                    if (oldDark > 0) {
                        c.problem(name + " still draws the Amber border colour (" + oldDark + " px)");
                    }
                }
                c.onClient(mc -> {
                    McCompat.setScreen(mc, null);
                    return null;
                });
                // The hotbar in the same theme.
                BufferedImage hb = shot(c, "hotbar-" + lower + "-gui2", 6);
                int hbOrange = count(hb, r[0], r[1], r[2], r[3], InvStorageCases::orange);
                int accent = (Integer) themeField(theme, "invAccent");
                int hbAccent = count(hb, r[0], r[1], r[2], r[3], exact(accent));
                c.note(String.format(Locale.ROOT, "%s hotbar: %d orange px, %d accent #%06X px", name, hbOrange, hbAccent,
                        accent & 0xFFFFFF));
                if (hbAccent < 600) {
                    c.problem(name + ": the hotbar's accent is not drawn (" + hbAccent + " px)");
                }
                if (name.equals("AMBER") ? hbOrange < 600 : hbOrange > 12) {
                    c.problem(name + ": hotbar orange pixels " + hbOrange);
                }
            }
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.call(scfg, "setTheme", oldTheme);
                Mod.call(scfg, "setEnabled", oldEnabled);
                return null;
            });
            restore.run(c);
            inv.run();
            HudEditorCases.restoreWindow(c, window, oldGui);
        }
    }
}

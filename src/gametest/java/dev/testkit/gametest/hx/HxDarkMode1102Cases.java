package dev.testkit.gametest.hx;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Dark Mode (mod branch darkmode-1102, killer560 2026-11-02: "implement noamm's dark mode"), 1102-darkmode-*:
 * <ul>
 *   <li>world: with Tint HUD off the world's pixels are darkened by the Opacity (25 % and 60 %: framebuffer ratio 0.75 /
 *       0.40), the hotbar's opaque pixels and the mod's own overlay text are unchanged, and nothing changes with it off.</li>
 *   <li>hud: with Tint HUD on the same hotbar pixels and the mod's overlay text are darkened by the Opacity too.</li>
 *   <li>screens: an open inventory's panel and the mod's own settings menu are not tinted in either mode.</li>
 *   <li>reload: every setting survives a save and a fresh load, and the opacity is clamped to 1..80.</li>
 * </ul>
 * Screenshots are copied to {@code -Dtestkit.darkmodeShots=<dir>} when given.
 */
final class HxDarkMode1102Cases {

    private static final String CFG = "darkmode.DarkModeConfig";
    private static final String FEATURE = "darkmode.DarkModeFeature";
    private static final String MESSAGE = "DARK MODE CHECK";

    private HxDarkMode1102Cases() {
    }

    static void register(Session s) {
        s.test("1102-darkmode-world", HxDarkMode1102Cases::world);
        s.test("1102-darkmode-hud", HxDarkMode1102Cases::hud);
        s.test("1102-darkmode-screens", HxDarkMode1102Cases::screens);
        s.test("1102-darkmode-reload", HxDarkMode1102Cases::reload);
    }

    // ---- shared -----------------------------------------------------------------------------------------------------

    static void settings(Session c, boolean enabled, int opacity, boolean tintHud) {
        c.onClient(mc -> {
            Object cfg = Mod.cfg(CFG);
            Mod.call(cfg, "setEnabled", enabled);
            Mod.call(cfg, "setOpacity", opacity);
            Mod.call(cfg, "setTintHud", tintHud);
            return null;
        });
    }

    /** A still frame: looking straight down at the platform, chat and titles cleared, the overlay message up. */
    static void still(Session c) {
        c.onClient(mc -> {
            if (McCompat.screen(mc) != null) {
                McCompat.setScreen(mc, null);
            }
            McCompat.clearChatAndToasts(mc);
            try {
                Mod.call(mc.gui, "clearTitles");
            } catch (Throwable t) {
                try {
                    Mod.call(Mod.field(mc.gui, "hud"), "clearTitles");
                } catch (Throwable ignored) {
                    // no titles API reachable: nothing showed one in this session
                }
            }
            mc.player.setXRot(90f);
            mc.player.setYRot(0f);
            mc.player.getInventory().setSelectedSlot(0);
            Mod.staticCall("notify.ModOverlayMessage", "show", MESSAGE, 120_000L);
            return null;
        });
        c.ctx().waitTicks(10);
    }

    static BufferedImage shot(Session c, String tag) throws Exception {
        c.ctx().waitTicks(4);
        Path p = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-" + tag));
        String dir = System.getProperty("testkit.darkmodeShots", "");
        if (!dir.isBlank()) {
            Path d = Path.of(dir);
            Files.createDirectories(d);
            Files.copy(p, d.resolve(System.getProperty("testkit.darkmodeShotPrefix", "") + c.name() + "-" + tag + ".png"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        return javax.imageio.ImageIO.read(p.toFile());
    }

    static int lum(int rgb) {
        return ((rgb >> 16) & 255) + ((rgb >> 8) & 255) + (rgb & 255);
    }

    private static boolean same(int a, int b, int tol) {
        return Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) <= tol && Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) <= tol
                && Math.abs((a & 255) - (b & 255)) <= tol;
    }

    /** {x0, y0, x1, y1} in window pixels. */
    static int[] worldBox(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        return new int[]{w * 30 / 100, h * 12 / 100, w * 70 / 100, h * 35 / 100};
    }

    /** The hotbar, inset by two GUI pixels. */
    private static int[] hotbarBox(Session c) {
        return c.onClient(mc -> {
            int s = (int) mc.getWindow().getGuiScale();
            int gw = mc.getWindow().getGuiScaledWidth(), gh = mc.getWindow().getGuiScaledHeight();
            return new int[]{(gw / 2 - 89) * s, (gh - 20) * s, (gw / 2 + 89) * s, (gh - 2) * s};
        });
    }

    /** The overlay message's band (centered, one text line), wide. */
    private static int[] messageBox(Session c) {
        return c.onClient(mc -> {
            int s = (int) mc.getWindow().getGuiScale();
            int gw = mc.getWindow().getGuiScaledWidth(), gh = mc.getWindow().getGuiScaledHeight();
            return new int[]{(gw / 2 - 80) * s, (gh / 2 - 12) * s, (gw / 2 + 80) * s, (gh / 2 + 12) * s};
        });
    }

    /** Sum of luminance of {@code img} over the pixels of {@code box} whose base luminance is at least {@code min}. */
    private static double ratio(BufferedImage base, BufferedImage img, int[] box, int min) {
        long a = 0, b = 0;
        for (int y = box[1]; y < box[3]; y++) {
            for (int x = box[0]; x < box[2]; x++) {
                int o = lum(base.getRGB(x, y));
                if (o >= min) {
                    b += o;
                    a += lum(img.getRGB(x, y));
                }
            }
        }
        return b == 0 ? -1 : (double) a / b;
    }

    private static double unchangedShare(BufferedImage base, BufferedImage img, int[] box, int min) {
        long n = 0, same = 0;
        for (int y = box[1]; y < box[3]; y++) {
            for (int x = box[0]; x < box[2]; x++) {
                if (lum(base.getRGB(x, y)) >= min) {
                    n++;
                    if (same(base.getRGB(x, y), img.getRGB(x, y), 3)) {
                        same++;
                    }
                }
            }
        }
        return n == 0 ? -1 : (double) same / n;
    }

    /** Pixels of {@code box} that an under-HUD tint left exactly as they were: the opaque HUD pixels. */
    private static boolean[][] opaqueHud(BufferedImage base, BufferedImage tintedWorld, int[] box) {
        boolean[][] m = new boolean[box[3] - box[1]][box[2] - box[0]];
        for (int y = box[1]; y < box[3]; y++) {
            for (int x = box[0]; x < box[2]; x++) {
                m[y - box[1]][x - box[0]] = lum(base.getRGB(x, y)) >= 60 && same(base.getRGB(x, y), tintedWorld.getRGB(x, y), 2);
            }
        }
        return m;
    }

    private static double maskedRatio(BufferedImage base, BufferedImage img, int[] box, boolean[][] mask, int[] count) {
        long a = 0, b = 0;
        int n = 0;
        for (int y = box[1]; y < box[3]; y++) {
            for (int x = box[0]; x < box[2]; x++) {
                if (mask[y - box[1]][x - box[0]]) {
                    n++;
                    b += lum(base.getRGB(x, y));
                    a += lum(img.getRGB(x, y));
                }
            }
        }
        count[0] = n;
        return b == 0 ? -1 : (double) a / b;
    }

    private static int fills(String field) {
        return ((Number) Mod.field(FEATURE, field)).intValue();
    }

    static String f3(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    // ---- world ------------------------------------------------------------------------------------------------------

    private static void world(Session c) throws Exception {
        hub(c);
        byte[] saved = saveFile(c);
        try {
            settings(c, false, 25, false);
            still(c);
            BufferedImage off = shot(c, "off");
            BufferedImage off2 = shot(c, "off-again");
            int w0 = fills("worldFills"), h0 = fills("hudFills");
            settings(c, true, 25, false);
            BufferedImage on25 = shot(c, "on25-tinthud-off");
            int w1 = fills("worldFills"), h1 = fills("hudFills");
            settings(c, true, 60, false);
            BufferedImage on60 = shot(c, "on60-tinthud-off");
            settings(c, false, 60, false);
            BufferedImage offAfter = shot(c, "off-after");

            int[] world = worldBox(off);
            int[] hotbar = hotbarBox(c);
            int[] msg = messageBox(c);
            double still = ratio(off, off2, world, 40);
            double r25 = ratio(off, on25, world, 40);
            double r60 = ratio(off, on60, world, 40);
            double rBack = ratio(off, offAfter, world, 40);
            boolean[][] hudMask = opaqueHud(off, on60, hotbar);
            int[] n = new int[1];
            double hud25 = maskedRatio(off, on25, hotbar, hudMask, n);
            int hudPixels = n[0];
            int hotbarArea = (hotbar[2] - hotbar[0]) * (hotbar[3] - hotbar[1]);
            boolean[][] msgMask = opaqueHud(off, on60, msg);
            double msg25 = maskedRatio(off, on25, msg, msgMask, n);
            int msgPixels = n[0];
            double hotbarSame25 = unchangedShare(off, on25, hotbar, 60);
            c.note("world luminance ratio: still " + f3(still) + ", 25% " + f3(r25) + " (want 0.749), 60% " + f3(r60)
                    + " (want 0.400), off again " + f3(rBack));
            c.note("hotbar: " + hudPixels + " opaque pixels of " + hotbarArea + ", ratio at 25% " + f3(hud25)
                    + ", unchanged share " + f3(hotbarSame25) + "; overlay text: " + msgPixels + " pixels, ratio " + f3(msg25));
            c.note("fills: world +" + (w1 - w0) + ", hud +" + (h1 - h0) + " over the 25% frame");
            c.check(Math.abs(still - 1.0) < 0.01, "the scene is not still between two off frames: " + f3(still));
            c.check(Math.abs(r25 - 0.749) < 0.03, "25% darkened the world by " + f3(r25) + ", expected 0.749");
            c.check(Math.abs(r60 - 0.400) < 0.03, "60% darkened the world by " + f3(r60) + ", expected 0.400");
            c.check(Math.abs(rBack - 1.0) < 0.01, "switched off, the world is still darkened: " + f3(rBack));
            c.check(hudPixels > hotbarArea / 4, "only " + hudPixels + " of the hotbar's " + hotbarArea
                    + " pixels were left alone by the under-HUD tint");
            c.check(Math.abs(hud25 - 1.0) < 0.01, "Tint HUD off changed the hotbar: ratio " + f3(hud25));
            c.check(msgPixels > 50, "the mod's overlay text was tinted with Tint HUD off (" + msgPixels + " untouched pixels)");
            c.check(Math.abs(msg25 - 1.0) < 0.01, "Tint HUD off changed the mod's overlay text: ratio " + f3(msg25));
            c.check(w1 > w0, "the under-HUD layer never filled");
            c.check(h1 == h0, "the over-HUD layer filled with Tint HUD off");
        } finally {
            restore(c, saved);
        }
    }

    // ---- hud --------------------------------------------------------------------------------------------------------

    private static void hud(Session c) throws Exception {
        hub(c);
        byte[] saved = saveFile(c);
        try {
            settings(c, false, 25, false);
            still(c);
            BufferedImage off = shot(c, "off");
            settings(c, true, 60, false);
            BufferedImage worldOnly = shot(c, "on60-tinthud-off");
            int w0 = fills("worldFills"), h0 = fills("hudFills");
            settings(c, true, 25, true);
            BufferedImage on25 = shot(c, "on25-tinthud-on");
            int w1 = fills("worldFills"), h1 = fills("hudFills");
            settings(c, true, 60, true);
            BufferedImage on60 = shot(c, "on60-tinthud-on");

            int[] world = worldBox(off);
            int[] hotbar = hotbarBox(c);
            int[] msg = messageBox(c);
            boolean[][] hudMask = opaqueHud(off, worldOnly, hotbar);
            boolean[][] msgMask = opaqueHud(off, worldOnly, msg);
            int[] n = new int[1];
            double hud25 = maskedRatio(off, on25, hotbar, hudMask, n);
            int hudPixels = n[0];
            double hud60 = maskedRatio(off, on60, hotbar, hudMask, n);
            double msg25 = maskedRatio(off, on25, msg, msgMask, n);
            int msgPixels = n[0];
            double msg60 = maskedRatio(off, on60, msg, msgMask, n);
            double r25 = ratio(off, on25, world, 40);
            c.note("Tint HUD on: hotbar (" + hudPixels + " px) " + f3(hud25) + " at 25%, " + f3(hud60) + " at 60%; overlay text ("
                    + msgPixels + " px) " + f3(msg25) + " / " + f3(msg60) + "; world " + f3(r25));
            c.note("fills: world +" + (w1 - w0) + ", hud +" + (h1 - h0) + " over the 25% frame");
            c.check(hudPixels > 100 && msgPixels > 50, "too few opaque HUD pixels to judge: hotbar " + hudPixels + ", text " + msgPixels);
            c.check(Math.abs(hud25 - 0.749) < 0.03, "Tint HUD on darkened the hotbar by " + f3(hud25) + ", expected 0.749");
            c.check(Math.abs(hud60 - 0.400) < 0.03, "Tint HUD on at 60% darkened the hotbar by " + f3(hud60) + ", expected 0.400");
            c.check(Math.abs(msg25 - 0.749) < 0.04, "Tint HUD on darkened the mod's overlay text by " + f3(msg25) + ", expected 0.749");
            c.check(Math.abs(r25 - 0.749) < 0.03, "Tint HUD on darkened the world by " + f3(r25) + ", expected 0.749 (once, not twice)");
            c.check(h1 > h0 && w1 == w0, "Tint HUD on: world layer +" + (w1 - w0) + ", hud layer +" + (h1 - h0));
        } finally {
            restore(c, saved);
        }
    }

    // ---- screens ----------------------------------------------------------------------------------------------------

    private static Screen modScreen() {
        try {
            Mod.setField("gui.ModScreen", "searchQuery", "");
            Mod.setField("gui.ModScreen", "scrollOffset", 0);
            Mod.setField("gui.ModScreen", "selectedTab", 0);
            return (Screen) Mod.cls("gui.ModScreen").getConstructor(Screen.class).newInstance((Object) null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** The open inventory's 176x166 panel, inset by 8 GUI pixels. */
    private static int[] inventoryBox(Session c) {
        return c.onClient(mc -> {
            int s = (int) mc.getWindow().getGuiScale();
            int gw = mc.getWindow().getGuiScaledWidth(), gh = mc.getWindow().getGuiScaledHeight();
            int x = (gw - 176) / 2, y = (gh - 166) / 2;
            return new int[]{(x + 8) * s, (y + 8) * s, (x + 168) * s, (y + 158) * s};
        });
    }

    private static void screens(Session c) throws Exception {
        hub(c);
        byte[] saved = saveFile(c);
        try {
            int[] inv = inventoryBox(c);
            BufferedImage[] invShots = new BufferedImage[3];
            BufferedImage[] menuShots = new BufferedImage[3];
            boolean[][] cases = {{false, false}, {true, false}, {true, true}};
            String[] tags = {"off", "on50-tinthud-off", "on50-tinthud-on"};
            for (int i = 0; i < 3; i++) {
                settings(c, cases[i][0], 50, cases[i][1]);
                still(c);
                c.onClient(mc -> {
                    McCompat.setScreen(mc, new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
                    return null;
                });
                c.ctx().waitTicks(10);
                invShots[i] = shot(c, "inventory-" + tags[i]);
                c.onClient(mc -> {
                    McCompat.setScreen(mc, modScreen());
                    return null;
                });
                c.ctx().waitTicks(20);
                menuShots[i] = shot(c, "menu-" + tags[i]);
                c.onClient(mc -> {
                    McCompat.setScreen(mc, null);
                    return null;
                });
            }
            int w = menuShots[0].getWidth(), h = menuShots[0].getHeight();
            // The menu's own panel: the blurred, dimmed world shows around its frame (and is tinted there, which is the
            // world, not the menu), so judge the middle 70% of the window, all inside the panel.
            int[] full = {w * 15 / 100, h * 15 / 100, w * 85 / 100, h * 85 / 100};
            double invOff = unchangedShare(invShots[0], invShots[1], inv, 60);
            double invOn = unchangedShare(invShots[0], invShots[2], inv, 60);
            double invOffR = ratio(invShots[0], invShots[1], inv, 60);
            double invOnR = ratio(invShots[0], invShots[2], inv, 60);
            double menuOff = unchangedShare(menuShots[0], menuShots[1], full, 0);
            double menuOn = unchangedShare(menuShots[0], menuShots[2], full, 0);
            double menuOffR = ratio(menuShots[0], menuShots[1], full, 0);
            double menuOnR = ratio(menuShots[0], menuShots[2], full, 0);
            c.note("inventory panel unchanged share / ratio: Tint HUD off " + f3(invOff) + " / " + f3(invOffR) + ", on "
                    + f3(invOn) + " / " + f3(invOnR));
            c.note("mod menu (middle 70%, every pixel) unchanged share / ratio: Tint HUD off " + f3(menuOff) + " / "
                    + f3(menuOffR) + ", on " + f3(menuOn) + " / " + f3(menuOnR));
            c.check(invOff > 0.97 && Math.abs(invOffR - 1) < 0.01, "the inventory panel was tinted (Tint HUD off): "
                    + f3(invOff) + " / " + f3(invOffR));
            c.check(invOn > 0.97 && Math.abs(invOnR - 1) < 0.01, "the inventory panel was tinted (Tint HUD on): "
                    + f3(invOn) + " / " + f3(invOnR));
            c.check(menuOff > 0.97 && Math.abs(menuOffR - 1) < 0.01, "the mod's menu was tinted (Tint HUD off): "
                    + f3(menuOff) + " / " + f3(menuOffR));
            c.check(menuOn > 0.97 && Math.abs(menuOnR - 1) < 0.01, "the mod's menu was tinted (Tint HUD on): "
                    + f3(menuOn) + " / " + f3(menuOnR));
        } finally {
            restore(c, saved);
        }
    }

    // ---- reload -----------------------------------------------------------------------------------------------------

    private static void reload(Session c) throws Exception {
        byte[] saved = saveFile(c);
        try {
            Object[] r = c.onClient(mc -> {
                Object cfg = Mod.cfg(CFG);
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setOpacity", 37);
                Mod.call(cfg, "setTintHud", true);
                Mod.call(cfg, "save");
                Mod.staticCall(CFG, "load");
                Object fresh = Mod.cfg(CFG);
                Object[] out = {fresh != cfg, Mod.call(fresh, "isEnabled"), Mod.call(fresh, "getOpacity"),
                        Mod.call(fresh, "isTintHud"), null, null, null};
                Mod.call(fresh, "setOpacity", 0);
                out[4] = Mod.call(fresh, "getOpacity");
                Mod.call(fresh, "setOpacity", 200);
                out[5] = Mod.call(fresh, "getOpacity");
                try {
                    out[6] = Files.readString((Path) Mod.field(CFG, "CONFIG_PATH"));
                } catch (Exception e) {
                    out[6] = "unreadable: " + e;
                }
                return out;
            });
            c.note("after load: new instance " + r[0] + ", enabled " + r[1] + ", opacity " + r[2] + ", tintHud " + r[3]
                    + "; setOpacity(0) -> " + r[4] + ", setOpacity(200) -> " + r[5]);
            c.note("file: " + String.valueOf(r[6]).replace('\n', ' '));
            c.check(Boolean.TRUE.equals(r[0]), "load did not build a new instance");
            c.check(Boolean.TRUE.equals(r[1]) && Integer.valueOf(37).equals(r[2]) && Boolean.TRUE.equals(r[3]),
                    "settings did not survive the reload: " + r[1] + " " + r[2] + " " + r[3]);
            c.check(Integer.valueOf(1).equals(r[4]) && Integer.valueOf(80).equals(r[5]),
                    "opacity not clamped to 1..80: " + r[4] + " / " + r[5]);
            // A profile apply rewrites the files and calls ProfileManager.reloadAllConfigs: Dark Mode must be in it, or
            // its next save would undo the profile.
            Object viaProfiles = c.onClient(mc -> {
                try {
                    Files.writeString((Path) Mod.field(CFG, "CONFIG_PATH"),
                            "{\"enabled\": false, \"opacity\": 42, \"tintHud\": false}");
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                Mod.staticCall("profiles.ProfileManager", "reloadAllConfigs");
                Object cfg = Mod.cfg(CFG);
                return Mod.call(cfg, "getOpacity") + " " + Mod.call(cfg, "isEnabled") + " " + Mod.call(cfg, "isTintHud");
            });
            c.note("after ProfileManager.reloadAllConfigs on a rewritten file: " + viaProfiles);
            c.check("42 false false".equals(viaProfiles), "ProfileManager.reloadAllConfigs did not reload Dark Mode: " + viaProfiles);
        } finally {
            restore(c, saved);
        }
    }

    // ---- file snapshot ----------------------------------------------------------------------------------------------

    static byte[] saveFile(Session c) throws Exception {
        Path p = c.onClient(mc -> (Path) Mod.field(CFG, "CONFIG_PATH"));
        return Files.exists(p) ? Files.readAllBytes(p) : null;
    }

    static void restore(Session c, byte[] saved) {
        c.onClient(mc -> {
            try {
                if (McCompat.screen(mc) != null) {
                    McCompat.setScreen(mc, null);
                }
                Path p = (Path) Mod.field(CFG, "CONFIG_PATH");
                if (saved == null) {
                    Files.deleteIfExists(p);
                } else {
                    Files.write(p, saved);
                }
                Mod.staticCall(CFG, "load");
                Mod.staticCall("notify.ModOverlayMessage", "show", "", 1L);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            return null;
        });
    }

    static void hub(Session c) {
        HxKit.hub(c);
    }
}

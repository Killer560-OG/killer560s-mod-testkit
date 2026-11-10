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
 * Dark Mode (mod branch darkmode-1102, reworked darkall-1110, killer560 2026-11-10: "that dark mode should affect literally
 * everything"), 1102-darkmode-*: ONE black fill at Opacity %, drawn once at the end of every GUI frame.
 * <ul>
 *   <li>world: the world's pixels, the hotbar's opaque pixels and the mod's overlay text all darken by the same ratio
 *       (25 % and 60 %: 0.75 / 0.40), nothing changes with it off, and exactly one fill happens per GUI frame.</li>
 *   <li>hud: opening the chat does not change how dark the world is (same ratio as with no screen), one fill per frame
 *       with the chat open.</li>
 *   <li>screens: an open inventory's panel and the mod's own settings menu darken by the same ratio, and a frame of the
 *       title screen, the pause menu and the inventory each add exactly one fill.</li>
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

    static void settings(Session c, boolean enabled, int opacity) {
        c.onClient(mc -> {
            Object cfg = Mod.cfg(CFG);
            Mod.call(cfg, "setEnabled", enabled);
            Mod.call(cfg, "setOpacity", opacity);
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

    private static int fills() {
        return ((Number) Mod.field(FEATURE, "frameFills")).intValue();
    }

    /** Runs {@code n} full GUI frames (HUD, screen, tooltips) on the client thread and returns the fills they added. */
    private static int fillsOver(Session c, int n) {
        return c.onClient(mc -> {
            int before = fills();
            for (int i = 0; i < n; i++) {
                mc.gui.extractRenderState(mc.getDeltaTracker(), false, false);
            }
            return fills() - before;
        });
    }

    static String f3(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    // ---- world ------------------------------------------------------------------------------------------------------

    private static void world(Session c) throws Exception {
        hub(c);
        byte[] saved = saveFile(c);
        try {
            settings(c, false, 25);
            still(c);
            BufferedImage off = shot(c, "off");
            BufferedImage off2 = shot(c, "off-again");
            int offFills = fillsOver(c, 3);
            settings(c, true, 25);
            BufferedImage on25 = shot(c, "on25");
            int onFills = fillsOver(c, 3);
            settings(c, true, 60);
            BufferedImage on60 = shot(c, "on60");
            settings(c, false, 60);
            BufferedImage offAfter = shot(c, "off-after");

            int[] world = worldBox(off);
            int[] hotbar = hotbarBox(c);
            int[] msg = messageBox(c);
            double still = ratio(off, off2, world, 40);
            double r25 = ratio(off, on25, world, 40);
            double r60 = ratio(off, on60, world, 40);
            double rBack = ratio(off, offAfter, world, 40);
            double hud25 = ratio(off, on25, hotbar, 60);
            double hud60 = ratio(off, on60, hotbar, 60);
            double msg25 = ratio(off, on25, msg, 60);
            c.note("luminance ratio at 25%: world " + f3(r25) + ", hotbar " + f3(hud25) + ", overlay text " + f3(msg25)
                    + " (want 0.749 each); 60%: world " + f3(r60) + ", hotbar " + f3(hud60) + " (want 0.400); still " + f3(still)
                    + ", off again " + f3(rBack));
            c.note("fills over 3 frames: off +" + offFills + ", on +" + onFills);
            c.check(Math.abs(still - 1.0) < 0.01, "the scene is not still between two off frames: " + f3(still));
            c.check(Math.abs(r25 - 0.749) < 0.03, "25% darkened the world by " + f3(r25) + ", expected 0.749");
            c.check(Math.abs(r60 - 0.400) < 0.03, "60% darkened the world by " + f3(r60) + ", expected 0.400");
            c.check(Math.abs(rBack - 1.0) < 0.01, "switched off, the world is still darkened: " + f3(rBack));
            c.check(Math.abs(hud25 - 0.749) < 0.03, "the hotbar darkened by " + f3(hud25) + ", expected 0.749 (same as the world)");
            c.check(Math.abs(hud60 - 0.400) < 0.03, "the hotbar darkened by " + f3(hud60) + " at 60%, expected 0.400");
            c.check(Math.abs(msg25 - 0.749) < 0.04, "the mod's overlay text darkened by " + f3(msg25) + ", expected 0.749");
            c.check(onFills == 3, "Dark Mode on: " + onFills + " fills in 3 GUI frames, expected exactly 3");
            c.check(offFills == 0, "Dark Mode off still filled " + offFills + " times");
        } finally {
            restore(c, saved);
        }
    }

    // ---- hud (chat) -------------------------------------------------------------------------------------------------

    private static void hud(Session c) throws Exception {
        hub(c);
        byte[] saved = saveFile(c);
        try {
            settings(c, false, 25);
            still(c);
            BufferedImage off = shot(c, "off");
            settings(c, true, 25);
            BufferedImage noChat = shot(c, "on25-nochat");
            c.onClient(mc -> {
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.ChatScreen("", false));
                return null;
            });
            c.ctx().waitTicks(10);
            BufferedImage chat = shot(c, "on25-chat");
            int chatFills = fillsOver(c, 3);
            settings(c, false, 25);
            BufferedImage chatOff = shot(c, "off-chat");

            int[] world = worldBox(off);
            double rNo = ratio(off, noChat, world, 40);
            double rChat = ratio(chatOff, chat, world, 40);
            c.note("world ratio at 25%: no screen " + f3(rNo) + ", chat open " + f3(rChat) + " (want 0.749 both); fills over 3 chat frames +" + chatFills);
            c.check(Math.abs(rNo - 0.749) < 0.03, "25% darkened the world by " + f3(rNo) + ", expected 0.749");
            c.check(Math.abs(rChat - 0.749) < 0.03, "opening the chat changed how dark the world is: " + f3(rChat) + ", expected 0.749");
            c.check(chatFills == 3, "chat open: " + chatFills + " fills in 3 GUI frames, expected exactly 3");
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
            BufferedImage[] invShots = new BufferedImage[2];
            BufferedImage[] menuShots = new BufferedImage[2];
            for (int i = 0; i < 2; i++) {
                settings(c, i == 1, 50);
                still(c);
                c.onClient(mc -> {
                    McCompat.setScreen(mc, new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
                    return null;
                });
                c.ctx().waitTicks(10);
                invShots[i] = shot(c, "inventory-" + i);
                c.onClient(mc -> {
                    McCompat.setScreen(mc, modScreen());
                    return null;
                });
                c.ctx().waitTicks(20);
                menuShots[i] = shot(c, "menu-" + i);
                c.onClient(mc -> {
                    McCompat.setScreen(mc, null);
                    return null;
                });
            }
            int w = menuShots[0].getWidth(), h = menuShots[0].getHeight();
            int[] full = {w * 15 / 100, h * 15 / 100, w * 85 / 100, h * 85 / 100};
            double invR = ratio(invShots[0], invShots[1], inv, 60);
            double menuR = ratio(menuShots[0], menuShots[1], full, 30);
            c.note("50% ratio: inventory panel " + f3(invR) + ", mod menu " + f3(menuR) + " (want 0.498 both)");
            c.check(Math.abs(invR - 0.498) < 0.03, "the inventory panel darkened by " + f3(invR) + ", expected 0.498");
            c.check(Math.abs(menuR - 0.498) < 0.03, "the mod's menu darkened by " + f3(menuR) + ", expected 0.498");
            // One fill per GUI frame on the title screen, the pause menu and the inventory (frame-level, not pixels).
            settings(c, true, 50);
            String[] names = {"title", "pause", "inventory"};
            for (String name : names) {
                c.onClient(mc -> {
                    McCompat.setScreen(mc, switch (name) {
                        case "title" -> new net.minecraft.client.gui.screens.TitleScreen();
                        case "pause" -> new net.minecraft.client.gui.screens.PauseScreen(true);
                        default -> new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player);
                    });
                    return null;
                });
                c.ctx().waitTicks(6);
                int f = fillsOver(c, 3);
                c.note(name + " screen: " + f + " fills in 3 GUI frames");
                c.check(f == 3, name + " screen: " + f + " fills in 3 GUI frames, expected exactly 3");
            }
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
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
                Mod.call(cfg, "save");
                Mod.staticCall(CFG, "load");
                Object fresh = Mod.cfg(CFG);
                Object[] out = {fresh != cfg, Mod.call(fresh, "isEnabled"), Mod.call(fresh, "getOpacity"),
                        null, null, null, null};
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
            c.note("after load: new instance " + r[0] + ", enabled " + r[1] + ", opacity " + r[2]
                    + "; setOpacity(0) -> " + r[4] + ", setOpacity(200) -> " + r[5]);
            c.note("file: " + String.valueOf(r[6]).replace('\n', ' '));
            c.check(Boolean.TRUE.equals(r[0]), "load did not build a new instance");
            c.check(Boolean.TRUE.equals(r[1]) && Integer.valueOf(37).equals(r[2]),
                    "settings did not survive the reload: " + r[1] + " " + r[2]);
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
                return Mod.call(cfg, "getOpacity") + " " + Mod.call(cfg, "isEnabled");
            });
            c.note("after ProfileManager.reloadAllConfigs on a rewritten file: " + viaProfiles);
            c.check("42 false".equals(viaProfiles), "ProfileManager.reloadAllConfigs did not reload Dark Mode: " + viaProfiles);
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

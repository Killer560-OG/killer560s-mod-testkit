package dev.testkit.gametest.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ChestMenu;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * 1119-ui-search-caret (searchcaret-1119). killer560: "in the inventory search bar add the blinking _ if i am in the box
 * to type." The bar is drawn by InventorySearchFeature (not an EditBox) and had no caret. Opens a chest, focuses the bar
 * by click and by Ctrl+F, and compares real screenshots of the bar: the caret phase differs from the off phase by pixels
 * that sit right after the typed text ("abc"), an empty focused bar loses its placeholder, an unfocused bar never draws
 * one, and Escape stops it. The bar has no cursor movement (the caret is always at the end of the text).
 */
final class SearchCaret1119Cases {

    private static final String ISC = "inventorysearch.InventorySearchConfig";
    private static final String ISF = "inventorysearch.InventorySearchFeature";

    private SearchCaret1119Cases() {
    }

    static void caret(UiCase c) throws Exception {
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, ISC);
        try {
            c.onClient(mc -> {
                Object is = Mod.cfg(ISC);
                Mod.call(is, "setEnabled", true);
                Mod.call(is, "setBoxScale", 1.0f);
                ((StringBuilder) Mod.field(ISF, "QUERY")).setLength(0);
                McCompat.setScreen(mc, new ContainerScreen(ChestMenu.threeRows(0, mc.player.getInventory()),
                        mc.player.getInventory(), Component.literal("Chest")));
                return null;
            });
            c.ticks(5);
            int[] box = c.onClient(mc -> (int[]) Mod.field(ISF, "lastBoxBounds"));
            c.check(box != null, "the search bar was not drawn");
            if (box == null) {
                return;
            }
            c.check(!caretOn(c), "an unfocused bar reports a caret");
            shot(c, "warm"); // the first capture can predate the first full frame
            c.ticks(4);
            BufferedImage idle = shot(c, "idle");
            c.ticks(8);
            c.check(!differs(idle, shot(c, "idle2"), c, box), "the unfocused bar changed between two frames");

            cursorGui(c, box[0] + box[2] / 2.0, box[1] + box[3] / 2.0);
            c.ctx().getInput().pressMouse(dev.testkit.compat.McKeys.mouse(0));
            c.ticks(2);
            c.check(listening(c), "a click on the bar did not focus it");
            BufferedImage[] pair = phases(c);
            c.check(pair != null, "could not get a caret-on and a caret-off frame (focused, empty)");
            if (pair != null) {
                c.check(differs(pair[0], pair[1], c, box), "focused empty bar: no caret in any phase");
                c.check(whiteCount(pair[1], c, box) < whiteCount(idle, c, box),
                        "the placeholder stayed while focused and empty");
            }

            c.ctx().getInput().typeChars("abc");
            c.ticks(3);
            String q = c.onClient(mc -> (String) Mod.staticCall(ISF, "getQuery"));
            c.check("abc".equals(q), "typed abc, the bar holds \"" + q + "\"");
            int textW = c.onClient(mc -> mc.font.width("abc"));
            pair = phases(c);
            c.check(pair != null, "no on/off frames with abc typed");
            if (pair != null) {
                int[] span = diffSpan(pair[0], pair[1], c, box);
                c.check(span != null, "abc typed: the caret phases look the same");
                if (span != null) {
                    c.note("caret pixels x " + span[0] + ".." + span[1] + " (text ends at " + (4 + textW) + ")");
                    c.check(span[0] >= 4 + textW - 1 && span[1] <= 4 + textW + 7,
                            "caret at x " + span[0] + ".." + span[1] + ", text ends at " + (4 + textW));
                }
            }

            realKey(c, InputConstants.KEY_ESCAPE, 0);
            c.check(!listening(c), "Escape left the bar focused");
            BufferedImage a = shot(c, "esc1");
            c.ticks(8);
            BufferedImage b = shot(c, "esc2");
            c.check(!differs(a, b, c, box), "the bar still blinks after Escape");

            realKey(c, InputConstants.KEY_F, InputConstants.MOD_CONTROL);
            c.check(listening(c), "Ctrl+F did not focus the bar");
            pair = phases(c);
            c.check(pair != null && differs(pair[0], pair[1], c, box), "after Ctrl+F no caret blinks");
            realKey(c, InputConstants.KEY_ESCAPE, 0);
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                ((StringBuilder) Mod.field(ISF, "QUERY")).setLength(0);
                return null;
            });
            DungeonB2Cases.restoreConfig(c, saved, ISC);
        }
    }

    private static boolean caretOn(UiCase c) {
        return c.onClient(mc -> (Boolean) Mod.staticCall(ISF, "caretOn"));
    }

    private static boolean listening(UiCase c) {
        return c.onClient(mc -> (Boolean) Mod.staticCall(ISF, "isListening"));
    }

    /** Returns {caret on frame, caret off frame}: a shot counts only when the phase read before and after it agree. */
    private static BufferedImage[] phases(UiCase c) throws Exception {
        BufferedImage on = null;
        BufferedImage off = null;
        for (int i = 0; i < 40 && (on == null || off == null); i++) {
            boolean before = caretOn(c);
            BufferedImage img = shot(c, "ph");
            if (before == caretOn(c)) {
                if (before) {
                    on = img;
                } else {
                    off = img;
                }
            }
            c.ticks(2);
        }
        return on == null || off == null ? null : new BufferedImage[]{on, off};
    }

    private static BufferedImage shot(UiCase c, String tag) throws Exception {
        Path p = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName("1119-search-caret-" + tag));
        return ImageIO.read(p.toFile());
    }

    private static double k(BufferedImage img, UiCase c) {
        return img.getWidth() / (double) c.onClient(mc -> mc.getWindow().getGuiScaledWidth());
    }

    private static int whiteCount(BufferedImage img, UiCase c, int[] box) {
        double k = k(img, c);
        int n = 0;
        for (int y = (int) (box[1] * k); y < (int) ((box[1] + box[3]) * k); y++) {
            for (int x = (int) (box[0] * k); x < (int) ((box[0] + box[2]) * k); x++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) == 0xFFFFFF) {
                    n++;
                }
            }
        }
        return n;
    }

    private static boolean differs(BufferedImage a, BufferedImage b, UiCase c, int[] box) {
        return diffSpan(a, b, c, box) != null;
    }

    /** The x range (GUI units from the bar's left edge) of pixels inside the bar that differ, or null. */
    private static int[] diffSpan(BufferedImage a, BufferedImage b, UiCase c, int[] box) {
        double k = k(a, c);
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (int y = (int) (box[1] * k); y < (int) ((box[1] + box[3]) * k); y++) {
            for (int x = (int) (box[0] * k); x < (int) ((box[0] + box[2]) * k); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    int gx = (int) (x / k - box[0]);
                    lo = Math.min(lo, gx);
                    hi = Math.max(hi, gx);
                }
            }
        }
        return lo == Integer.MAX_VALUE ? null : new int[]{lo, hi};
    }

    private static void realKey(UiCase c, int key, int mods) {
        c.onClient(mc -> {
            try {
                var m = net.minecraft.client.KeyboardHandler.class.getDeclaredMethod("keyPress", long.class, int.class,
                        net.minecraft.client.input.KeyEvent.class);
                m.setAccessible(true);
                long window = mc.getWindow().handle();
                for (int action : new int[]{InputConstants.PRESS, InputConstants.RELEASE}) {
                    mc.execute(() -> {
                        try {
                            m.invoke(mc.keyboardHandler, window, action, dev.testkit.compat.McKeys.keyEvent(key, mods));
                        } catch (ReflectiveOperationException e) {
                            throw new AssertionError(e);
                        }
                    });
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            return null;
        });
        c.ticks(2);
    }

    private static void cursorGui(UiCase c, double gx, double gy) {
        double[] f = c.onClient(mc -> new double[]{mc.getWindow().getScreenWidth() / (double) mc.getWindow().getGuiScaledWidth(),
                mc.getWindow().getScreenHeight() / (double) mc.getWindow().getGuiScaledHeight()});
        c.ctx().getInput().setCursorPos(gx * f[0], gy * f[1]);
        c.ticks(3);
    }
}

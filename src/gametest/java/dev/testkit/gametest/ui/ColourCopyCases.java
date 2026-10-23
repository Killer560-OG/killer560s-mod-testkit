package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 955: colour Copy / Paste / Recent in every colour picker (mod color-copy, killer560 2026-10-13: "for all color gui menus
 * add a button to copy the hex id of that color ... and an easy way to paste it between menus"). Every colour setting
 * opens {@code gui.ColorPickerScreen}, so the case drives that screen from two different settings, through the real menu
 * rows and real presses on the picker's own buttons and swatches:
 * <ol>
 *   <li>the parser: the accepted forms ({@code #RRGGBB}, {@code RRGGBB}, {@code #RGB}, {@code 0xRRGGBB},
 *       {@code #AARRGGBB}, whitespace) give the right ARGB, and junk gives null (never a throw);</li>
 *   <li>Menu Theme's Accent picker: Copy puts the accent's hex on the system clipboard and says "Copied";</li>
 *   <li>Real Time's Text Color picker (a DIFFERENT setting): junk on the clipboard is refused (red line, nothing changes,
 *       the file on disk untouched), then Paste applies the copied colour, proven in killer560smod-realtime.json; Ctrl+C
 *       copies, Ctrl+V pastes, a typed hex in the hex box applies;</li>
 *   <li>Recent: back in the Accent picker the Recent row holds those colours newest first; a real click on a swatch applies
 *       it to the menu's accent; after a restart (the file re-read) the row is the same and is DRAWN in a fresh picker
 *       (screenshot pixel at the swatch).</li>
 * </ol>
 * The OS clipboard, Real Time, the menu theme and Recent are put back in finally.
 */
final class ColourCopyCases {

    static final String PICKER = "ColorPickerScreen";

    private ColourCopyCases() {
    }

    static int parsed(String text) {
        Object p = Mod.staticCall("gui.ColourHex", "parse", text);
        if (p == null) {
            throw new AssertionError("parse(\"" + text + "\") gave null");
        }
        return (Integer) Mod.call(p, "argb");
    }

    static boolean parsedAlpha(String text) {
        return (Boolean) Mod.call(Mod.staticCall("gui.ColourHex", "parse", text), "hasAlpha");
    }

    static Object recentCfg() {
        return Mod.staticCall("gui.RecentColours", "getInstance");
    }

    @SuppressWarnings("unchecked")
    static List<Integer> recent() {
        return new ArrayList<>((List<Integer>) Mod.call(recentCfg(), "getRecent"));
    }

    /** Opens {@code tab}'s drawer in a fresh menu and presses the row whose label starts with {@code row}. */
    static Screen openPicker(Minecraft mc, String tab, String row) throws Exception {
        Screen menu = MenuACases.open(mc);
        Mod.call(menu, "openFeature", MenuACases.leaf(tab));
        AbstractWidget hit = null;
        for (AbstractWidget w : MenuACases.paneRows(menu)) {
            if (ModScreenDriver.label(w).startsWith(row)) {
                hit = w;
            }
        }
        if (hit == null) {
            throw new AssertionError("no \"" + row + "\" row in the " + tab + " drawer");
        }
        ModScreenDriver.press(hit);
        Screen picker = McCompat.screen(mc);
        if (picker == null || !picker.getClass().getSimpleName().equals(PICKER)) {
            throw new AssertionError(tab + "'s \"" + row + "\" row opened " + picker + ", not the colour picker");
        }
        return picker;
    }

    /** A real press at the centre of one of the picker's widgets, through the screen. */
    static void pressWidget(Screen picker, String field) {
        AbstractWidget w = (AbstractWidget) Mod.field(picker, field);
        MenuACases.press(picker, w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0);
    }

    static int argb(Screen picker) {
        return (Integer) Mod.call(picker, "currentArgb");
    }

    static String status(Screen picker) {
        return (String) Mod.field(picker, "status");
    }

    static boolean statusBad(Screen picker) {
        return (Boolean) Mod.field(picker, "statusBad");
    }

    static int realTimeOnDisk() throws Exception {
        Path p = (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-realtime.json");
        return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject().get("textColor").getAsInt();
    }

    static String hex(int c) {
        return String.format("#%08X", c);
    }

    static void copyPaste(UiCase c) throws Exception {
        // ---- the parser --------------------------------------------------------------------------------------------
        c.onClient(mc -> {
            c.check(parsed("#3A7BD5") == 0xFF3A7BD5 && !parsedAlpha("#3A7BD5"), "#3A7BD5 parsed wrong");
            c.check(parsed("3a7bd5") == 0xFF3A7BD5, "3a7bd5 parsed wrong");
            c.check(parsed("#3AB") == 0xFF33AABB, "#3AB parsed wrong");
            c.check(parsed("0x3A7BD5") == 0xFF3A7BD5, "0x3A7BD5 parsed wrong");
            c.check(parsed("#803A7BD5") == 0x803A7BD5 && parsedAlpha("#803A7BD5"), "#803A7BD5 parsed wrong");
            c.check(parsed("  # 3A 7B D5 \n") == 0xFF3A7BD5, "whitespace was not ignored");
            for (String bad : new String[]{"", "   ", "#12345", "zzzzzz", "#GGGGGG", "3AB", "red", "#1234567890",
                    "0x", "#", "rgb(1,2,3)", "#3A7BD5;"}) {
                c.check(Mod.staticCall("gui.ColourHex", "parse", bad) == null, "\"" + bad + "\" was read as a colour");
            }
            c.check(Mod.staticCall("gui.ColourHex", "format", 0xFF3A7BD5).equals("#3A7BD5"), "an opaque color is not #RRGGBB");
            c.check(Mod.staticCall("gui.ColourHex", "format", 0x803A7BD5).equals("#803A7BD5"), "a translucent one is not #AARRGGBB");
            return null;
        });

        Object rt = Mod.cfg("realtime.RealTimeConfig");
        Object menuCfg = Mod.cfg("gui.MenuConfig");
        boolean oldRtOn = (Boolean) Mod.call(rt, "isEnabled");
        int oldRtColour = (Integer) Mod.call(rt, "getTextColor");
        String oldTheme = MenuACases.themeName();
        List<Integer> oldRecent = recent();
        String oldClip = c.onClient(mc -> mc.keyboardHandler.getClipboard());
        try {
            c.onClient(mc -> {
                Mod.call(rt, "setEnabled", true);
                Mod.call(rt, "setTextColor", 0xFFFFFFFF);
                Mod.call(rt, "save");
                Mod.call(menuCfg, "resetCustom");
                Mod.call(menuCfg, "setTheme", Mod.enumValue("gui.MenuTheme", "AMBER"));
                Mod.call(menuCfg, "save");
                Mod.call(recentCfg(), "clear");
                return null;
            });

            // ---- Copy in the Accent picker -------------------------------------------------------------------------
            int accent = c.onClient(mc -> ((int[]) Mod.call(menuCfg, "activeColours"))[0]);
            String copied = c.onClient(mc -> {
                try {
                    Screen a = openPicker(mc, "MenuThemeTab", "Accent:");
                    c.check((argb(a) & 0xFFFFFF) == (accent & 0xFFFFFF), "the Accent picker opened on " + hex(argb(a))
                            + ", the accent is " + hex(accent));
                    mc.keyboardHandler.setClipboard("before copy");
                    pressWidget(a, "copyButton");
                    return mc.keyboardHandler.getClipboard();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            String want = (String) Mod.staticCall("gui.ColourHex", "format", 0xFF000000 | accent);
            c.note("Copy in the Accent picker put \"" + copied + "\" on the clipboard (accent " + hex(accent) + ")");
            c.check(want.equals(copied), "Copy put \"" + copied + "\" on the clipboard, wanted " + want);
            Screen a0 = c.onClient(McCompat::screen);
            c.check(String.valueOf(status(a0)).startsWith("Copied " + want), "no \"Copied\" line: \"" + status(a0) + "\"");
            c.check(MenuACases.themeName().equals("AMBER"), "Copy changed the theme to " + MenuACases.themeName());
            shot(c, "copied");
            c.onClient(mc -> {
                McCompat.screen(mc).onClose();
                return null;
            });

            // ---- Paste into Real Time's Text Color: junk first, then the copied colour ------------------------------
            int diskBefore = realTimeOnDisk();
            Screen b = c.onClient(mc -> {
                try {
                    return openPicker(mc, "RealTimeTab", "Text Colour:");
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            int bBefore = argb(b);
            for (String junk : new String[]{"not a color", "#12345", "", "   \n"}) {
                Boolean took = c.onClient(mc -> {
                    mc.keyboardHandler.setClipboard(junk);
                    pressWidget(b, "pasteButton");
                    return argb(b) != bBefore;
                });
                c.check(!took, "pasting \"" + junk + "\" changed the colour to " + hex(argb(b)));
                c.check(statusBad(b) && status(b).toLowerCase(java.util.Locale.ROOT).contains("not a colour"),
                        "pasting \"" + junk + "\" showed \"" + status(b) + "\", not a red not-a-colour line");
            }
            c.check(c.onClient(mc -> McCompat.screen(mc) == b), "a bad paste closed the picker");
            c.check(realTimeOnDisk() == diskBefore, "a bad paste wrote " + hex(realTimeOnDisk()) + " to disk");
            shot(c, "refused");
            c.onClient(mc -> {
                mc.keyboardHandler.setClipboard(copied);
                pressWidget(b, "pasteButton");
                return null;
            });
            int pastedOnDisk = realTimeOnDisk();
            c.note("Paste in Real Time's picker: picker " + hex(argb(b)) + ", killer560smod-realtime.json textColor "
                    + hex(pastedOnDisk) + " (was " + hex(diskBefore) + ")");
            c.check((argb(b) & 0xFFFFFF) == (accent & 0xFFFFFF), "Paste left the picker on " + hex(argb(b)));
            c.check((pastedOnDisk & 0xFFFFFF) == (accent & 0xFFFFFF) && pastedOnDisk != diskBefore,
                    "Paste did not reach the Real Time config on disk: " + hex(pastedOnDisk));
            c.check(!statusBad(b) && status(b).startsWith("Pasted"), "no Pasted line: \"" + status(b) + "\"");

            // Ctrl+V (no box focused) and Ctrl+C
            int ctrl = 2; // GLFW_MOD_CONTROL
            c.onClient(mc -> {
                mc.keyboardHandler.setClipboard("0x2E C4 B6");
                MenuACases.key(b, InputConstants.KEY_V, ctrl);
                return null;
            });
            c.check(argb(b) == (0xFF2EC4B6) && (realTimeOnDisk() & 0xFFFFFF) == 0x2EC4B6,
                    "Ctrl+V of 0x2E C4 B6 gave " + hex(argb(b)) + ", disk " + hex(realTimeOnDisk()));
            String ctrlC = c.onClient(mc -> {
                mc.keyboardHandler.setClipboard("x");
                MenuACases.key(b, InputConstants.KEY_C, ctrl);
                return mc.keyboardHandler.getClipboard();
            });
            c.check("#2EC4B6".equals(ctrlC), "Ctrl+C put \"" + ctrlC + "\" on the clipboard");
            // the hex box: typed text applies; the box shows the current colour
            c.onClient(mc -> {
                EditBox box = (EditBox) Mod.field(b, "hexBox");
                c.check("#2EC4B6".equals(box.getValue()), "the hex box shows \"" + box.getValue() + "\"");
                b.setFocused(box);
                box.setValue("#123");
                return null;
            });
            c.check(argb(b) == 0xFF112233 && (realTimeOnDisk() & 0xFFFFFF) == 0x112233,
                    "typing #123 in the hex box gave " + hex(argb(b)) + ", disk " + hex(realTimeOnDisk()));
            // Ctrl+V while the box has focus is the box's own paste, not the picker's
            c.onClient(mc -> {
                mc.keyboardHandler.setClipboard("#FFFFFF");
                MenuACases.key(b, InputConstants.KEY_V, ctrl);
                return null;
            });
            String boxNow = c.onClient(mc -> ((EditBox) Mod.field(b, "hexBox")).getValue());
            c.note("Ctrl+V with the hex box focused: box \"" + boxNow + "\", colour " + hex(argb(b)));
            c.check(argb(b) == 0xFF112233, "Ctrl+V in a focused hex box was taken by the picker: " + hex(argb(b)));
            c.onClient(mc -> {
                EditBox box = (EditBox) Mod.field(b, "hexBox");
                box.setValue("#123");
                b.setFocused(null);
                b.onClose();
                return null;
            });
            int finalB = realTimeOnDisk();
            c.check((finalB & 0xFFFFFF) == 0x112233, "Real Time's color after closing is " + hex(finalB));

            // ---- Recent in another picker ---------------------------------------------------------------------------
            List<Integer> row = recent();
            c.note("Recent after the round: " + fmt(row));
            c.check(!row.isEmpty() && row.get(0) == 0xFF112233, "Recent does not start with the last pick #112233: " + fmt(row));
            c.check(row.contains(0xFF2EC4B6) && row.contains(0xFF000000 | accent), "Recent is missing a copied/pasted color: "
                    + fmt(row));
            Screen a2 = c.onClient(mc -> {
                try {
                    return openPicker(mc, "MenuThemeTab", "Accent:");
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            shot(c, "recent");
            c.onClient(mc -> {
                int[] sw = (int[]) Mod.call(a2, "recentBounds", 0);
                MenuACases.press(a2, sw[0] + sw[2] / 2.0, sw[1] + sw[3] / 2.0);
                return null;
            });
            int newAccent = (Integer) Mod.call(menuCfg, "getCustomColour", Mod.enumValue("gui.MenuTheme$Slot", "ACCENT"));
            c.note("a click on Recent swatch 0 in the Accent picker: picker " + hex(argb(a2)) + ", theme "
                    + MenuACases.themeName() + ", custom accent " + hex(newAccent));
            c.check(argb(a2) == 0xFF112233, "the Recent click left the Accent picker on " + hex(argb(a2)));
            c.check(MenuACases.themeName().equals("CUSTOM") && (newAccent & 0xFFFFFF) == 0x112233,
                    "the Recent click did not set the menu accent: " + MenuACases.themeName() + " " + hex(newAccent));
            c.onClient(mc -> {
                a2.onClose();
                McCompat.setScreen(mc, null);
                return null;
            });

            // ---- restart: Recent read back from disk and drawn ------------------------------------------------------
            List<Integer> before = recent();
            Path file = (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-colours.json");
            String onDisk = Files.readString(file, StandardCharsets.UTF_8);
            c.check(onDisk.contains("#112233"), "killer560smod-colours.json does not hold #112233: " + onDisk);
            c.onClient(mc -> {
                Mod.setField("gui.RecentColours", "instance", null);
                Mod.staticCall("gui.RecentColours", "load");
                return null;
            });
            List<Integer> after = recent();
            c.check(before.equals(after), "after a restart Recent is " + fmt(after) + ", was " + fmt(before));
            Screen b2 = c.onClient(mc -> {
                try {
                    return openPicker(mc, "RealTimeTab", "Text Colour:");
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            c.ticks(3);
            Path taken = c.ctx().takeScreenshot(Report.fileName(c.name() + "-after-restart"));
            Report.screenshot(c.name() + "-after-restart", taken);
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(taken.toFile());
            double sc = img.getWidth() / (double) b2.width;
            for (int i = 0; i < Math.min(2, after.size()); i++) {
                int[] sw = (int[]) Mod.call(b2, "recentBounds", i);
                int px = img.getRGB((int) ((sw[0] + sw[2] / 2.0) * sc), (int) ((sw[1] + sw[3] / 2.0) * sc)) | 0xFF000000;
                c.note("after restart, Recent swatch " + i + " drawn " + hex(px) + " (saved " + hex(after.get(i)) + ")");
                c.check(MenuACases.near(px, after.get(i)), "Recent swatch " + i + " is drawn " + hex(px) + ", saved "
                        + hex(after.get(i)));
            }
            c.onClient(mc -> {
                b2.onClose();
                return null;
            });
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.call(rt, "setTextColor", oldRtColour);
                Mod.call(rt, "setEnabled", oldRtOn);
                Mod.call(rt, "save");
                Mod.call(menuCfg, "resetCustom");
                Mod.call(menuCfg, "setTheme", Mod.enumValue("gui.MenuTheme", oldTheme));
                Mod.call(menuCfg, "save");
                Mod.call(recentCfg(), "setRecent", oldRecent);
                mc.keyboardHandler.setClipboard(oldClip == null ? "" : oldClip);
                return null;
            });
        }
    }

    static String fmt(List<Integer> row) {
        List<String> out = new ArrayList<>();
        for (int v : row) {
            out.add(hex(v));
        }
        return out.toString();
    }

    /** Keeps a screenshot of the picker on screen now. */
    static void shot(UiCase c, String label) throws Exception {
        c.ticks(4);
        String why = c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            return s != null && s.getClass().getSimpleName().equals(PICKER) ? null : "the picker is not on screen (" + s + ")";
        });
        if (why != null) {
            c.problem(label + ": " + why);
            return;
        }
        String name = c.name() + "-" + label;
        Path taken = c.ctx().takeScreenshot(Report.fileName(name));
        c.check(Report.screenshot(name, taken) != null, "no screenshot for " + name);
    }
}

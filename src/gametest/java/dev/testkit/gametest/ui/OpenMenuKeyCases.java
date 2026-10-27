package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 1100: the Open Menu Key (mod open-key-1026, killer560 2026-10-26: "a keybind to open our mod ... default to the \ key").
 * A real held key (the gametest input, polled by the mod like the Edit HUD key): backslash by default; with no screen it
 * opens gui.ModScreen; with chat open (typing) it does nothing; Home > Mod &amp; HUD's "Open Menu Key" row, clicked and
 * then given a real key press, rebinds it (saved to the HUD config file), after which the new key opens the menu and
 * backslash does not.
 */
final class OpenMenuKeyCases {

    private static final String HUD = "hud.HudConfig";
    private static final int BACKSLASH = 92; // GLFW_KEY_BACKSLASH
    private static final int KP_7 = 327; // GLFW_KEY_KP_7

    private OpenMenuKeyCases() {
    }

    static void openKey(UiCase c) throws Exception {
        Object hud = Mod.cfg(HUD);
        int oldKey = (Integer) Mod.call(hud, "getOpenMenuKeyCode");
        Path file = (Path) Mod.field(HUD, "CONFIG_PATH");
        byte[] before = Files.exists(file) ? Files.readAllBytes(file) : null;
        try {
            c.note("default Open Menu Key code " + oldKey + " (backslash is " + BACKSLASH + ")");
            c.check(oldKey == BACKSLASH, "the Open Menu Key does not default to backslash: " + oldKey);
            close(c);

            // No screen: the key opens the menu.
            String none = press(c, BACKSLASH);
            c.note("no screen, backslash -> " + none);
            c.check(none.equals("ModScreen"), "with no screen open backslash opened " + none + ", not the mod menu");
            close(c);

            // Typing in chat: nothing.
            c.onClient(mc -> {
                McCompat.setScreen(mc, new ChatScreen("", false));
                return null;
            });
            c.ticks(3);
            boolean typing = c.onClient(mc -> (Boolean) Mod.staticCall("util.TypingDetector", "isTyping", mc));
            String chat = press(c, BACKSLASH);
            c.note("chat open (typing detector " + typing + "), backslash -> " + chat);
            c.check(chat.equals("ChatScreen"), "with chat open backslash left " + chat);
            close(c);
            // The key also waits a tick after typing ends, so let the poll see "not typing" before the control press.
            c.ticks(3);
            String control = press(c, BACKSLASH);
            c.note("after chat closed, backslash -> " + control);
            c.check(control.equals("ModScreen"), "after chat closed backslash opened " + control);

            // Rebind through Home > Mod & HUD: click the row, press a real key.
            String row = c.onClient(mc -> {
                Screen s;
                try {
                    s = MenuACases.open(mc);
                } catch (Exception ex) {
                    throw new AssertionError("could not open gui.ModScreen: " + UiCase.describe(ex), ex);
                }
                Mod.call(s, "openFeature", MenuACases.leaf("HomeMainTab"));
                AbstractWidget w = null;
                for (AbstractWidget x : MenuACases.paneRows(s)) {
                    if (ModScreenDriver.label(x).startsWith("Open Menu Key")) {
                        w = x;
                    }
                }
                if (w == null) {
                    return null;
                }
                String label = ModScreenDriver.label(w);
                MouseButtonEvent e = new MouseButtonEvent(w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0,
                        new MouseButtonInfo(0, 0));
                s.mouseClicked(e, false);
                s.mouseReleased(e);
                return label;
            });
            c.note("Home > Mod & HUD row: " + row);
            c.check(row != null, "no 'Open Menu Key' row in Home > Mod & HUD");
            c.ticks(2);
            boolean listening = c.onClient(mc -> (Boolean) Mod.call(McCompat.screen(mc), "isCapturingKey"));
            c.note("after clicking the row the menu waits for a key: " + listening);
            c.check(listening, "clicking the Open Menu Key row did not start a key capture");
            c.ctx().getInput().holdKey(KP_7);
            c.ticks(3);
            c.ctx().getInput().releaseKey(KP_7);
            c.ticks(2);
            int bound = c.onClient(mc -> (Integer) Mod.call(hud, "getOpenMenuKeyCode"));
            String newLabel = c.onClient(mc -> {
                for (AbstractWidget x : MenuACases.paneRows(McCompat.screen(mc))) {
                    if (ModScreenDriver.label(x).startsWith("Open Menu Key")) {
                        return ModScreenDriver.label(x);
                    }
                }
                return "(no row)";
            });
            String saved = Files.exists(file) ? Files.readString(file) : "";
            c.note("after pressing keypad 7: code " + bound + ", row '" + newLabel + "', saved file has openMenuKeyCode "
                    + (saved.contains("\"openMenuKeyCode\": " + KP_7) || saved.contains("\"openMenuKeyCode\":" + KP_7)));
            c.check(bound == KP_7, "the key press did not rebind the Open Menu Key (code " + bound + ")");
            c.check(saved.matches("(?s).*\"openMenuKeyCode\"\\s*:\\s*" + KP_7 + "\\b.*"),
                    "the new Open Menu Key was not saved to " + file.getFileName());
            close(c);
            c.ticks(3);
            String oldGone = press(c, BACKSLASH);
            c.note("rebound: backslash -> " + oldGone);
            c.check(oldGone.equals("(no screen)"), "after rebinding, backslash still opened " + oldGone);
            close(c);
            String fresh = press(c, KP_7);
            c.note("rebound: keypad 7 -> " + fresh);
            c.check(fresh.equals("ModScreen"), "the rebound key opened " + fresh + ", not the mod menu");
        } finally {
            c.ctx().getInput().releaseKey(BACKSLASH);
            c.ctx().getInput().releaseKey(KP_7);
            c.onClient(mc -> {
                Mod.call(hud, "setOpenMenuKeyCode", oldKey);
                McCompat.setScreen(mc, null);
                return null;
            });
            if (before == null) {
                Files.deleteIfExists(file);
            } else {
                Files.write(file, before);
            }
        }
    }

    private static void close(UiCase c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        c.ticks(2);
    }

    private static String press(UiCase c, int key) {
        c.ctx().getInput().holdKey(key);
        c.ticks(4);
        c.ctx().getInput().releaseKey(key);
        c.ticks(3);
        return c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            return s == null ? "(no screen)" : s.getClass().getSimpleName();
        });
    }
}

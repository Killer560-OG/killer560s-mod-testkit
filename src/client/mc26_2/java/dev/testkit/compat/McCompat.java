package dev.testkit.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Minecraft API that moved between versions, for 26.2. See the 26.1 copy for the rule: identical public
 * signatures in both.
 *
 * <p>26.2 takes the screen off {@code Minecraft}: the field and {@code setScreen} are gone and the screen stack
 * belongs to {@code Minecraft.gui} ({@code Gui.screen()} / {@code Gui.setScreen(Screen)}, javap'd on the 26.2
 * jar). {@code Minecraft.setScreenAndShow} is NOT the replacement - it also forces a frame render.
 */
public final class McCompat {

    private McCompat() {
    }

    /** The object holding the HUD's title/subtitle/action-bar state: {@code Gui} on 26.1.2, {@code Gui.hud} on 26.2. */
    public static Object hud(Minecraft mc) {
        return mc.gui.hud;
    }

    /** The screen that is open, or null for none. 26.2: {@code Minecraft.gui.screen()}. */
    public static Screen screen(Minecraft mc) {
        return mc.gui.screen();
    }

    /** Opens a screen, or closes the current one when given null. 26.2: {@code Minecraft.gui.setScreen}. */
    public static void setScreen(Minecraft mc, Screen screen) {
        mc.gui.setScreen(screen);
    }

    /** Whether the HUD is hidden (F1). 26.2: {@code Options.hideGui} is gone; {@code Hud.isHidden()}. */
    public static boolean hudHidden(Minecraft mc) {
        return mc.gui.hud.isHidden();
    }

    /** Hides or shows the HUD (F1). 26.2 has no setter, only {@code Hud.toggle()} (javap 26.2). */
    public static void setHudHidden(Minecraft mc, boolean hidden) {
        if (mc.gui.hud.isHidden() != hidden) {
            mc.gui.hud.toggle();
        }
    }

    /**
     * Empties the chat window and drops every toast (see the 26.1 copy). 26.2: {@code Hud.getChat()},
     * {@code Gui.toastManager()} (javap 26.2).
     */
    public static void clearChatAndToasts(Minecraft mc) {
        mc.gui.hud.getChat().clearMessages(false);
        mc.gui.toastManager().clear();
    }
}

package dev.testkit.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Minecraft API that moved between versions, for 26.1.x.
 *
 * <p>build.gradle puts exactly one of {@code src/client/mc26_1/java} / {@code src/client/mc26_2/java} on the
 * client source path, chosen by {@code minecraft_version}, the same way killer560s-mod does. The two copies must
 * keep IDENTICAL public signatures: a method in one and not the other compiles on one Minecraft and breaks the
 * other. Code under {@code src/*}{@code /java} calls these instead of the API directly.
 */
public final class McCompat {

    private McCompat() {
    }

    /** The object holding the HUD's title/subtitle/action-bar state: {@code Gui} on 26.1.2, {@code Gui.hud} on 26.2. */
    public static Object hud(Minecraft mc) {
        return mc.gui;
    }

    /** The screen that is open, or null for none. 26.1: {@code Minecraft.screen}. */
    public static Screen screen(Minecraft mc) {
        return mc.screen;
    }

    /** Opens a screen, or closes the current one when given null. 26.1: {@code Minecraft.setScreen}. */
    public static void setScreen(Minecraft mc, Screen screen) {
        mc.setScreen(screen);
    }
}

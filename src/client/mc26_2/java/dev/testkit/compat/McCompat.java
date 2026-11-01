package dev.testkit.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Minecraft API that moved between versions, for 26.2. Rule: identical public
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

    /** The chat window ({@code mc.gui.hud.getChat()}). */
    public static net.minecraft.client.gui.components.ChatComponent chat(Minecraft mc) {
        return mc.gui.hud.getChat();
    }

    /** The player list overlay ({@code mc.gui.hud.getTabList()}). */
    public static net.minecraft.client.gui.components.PlayerTabOverlay tabList(Minecraft mc) {
        return mc.gui.hud.getTabList();
    }

    /** The client's chat listener, where a system line enters ({@code mc.gui.chatListener()}). */
    public static net.minecraft.client.multiplayer.chat.ChatListener chatListener(Minecraft mc) {
        return mc.gui.chatListener();
    }

    // ---- added 2026-11-01 for the 26.3 port (identical signatures in the 26.3 copy) ----

    /** An arm swing as a left click sends it. 26.2: {@code LocalPlayer.swing(hand)} animates and sends ServerboundSwingPacket. */
    public static void swingArm(net.minecraft.client.player.LocalPlayer player) {
        player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
    }

    /** The drop key (Q, or Ctrl+Q with {@code fullStack}). 26.2: {@code LocalPlayer.drop(boolean)}. */
    public static void dropSelected(Minecraft mc, boolean fullStack) {
        mc.player.drop(fullStack);
    }

    /** {@code EntityRenderDispatcher.shouldRender}; 26.2 takes no partial tick. */
    public static boolean shouldRender(net.minecraft.client.renderer.entity.EntityRenderDispatcher d,
            net.minecraft.world.entity.Entity e, net.minecraft.client.renderer.culling.Frustum frustum, double x, double y,
            double z) {
        return d.shouldRender(e, frustum, x, y, z);
    }

    /** The vanilla Options screen. 26.2: {@code OptionsScreen(Screen, Options, boolean inGame=false)}. */
    public static Screen optionsScreen(Screen parent, net.minecraft.client.Options options) {
        return new net.minecraft.client.gui.screens.options.OptionsScreen(parent, options, false);
    }

    /** A sign editor for the front or back text. 26.2: {@code SignEditScreen(sign, boolean front, boolean filtered)}. */
    public static Screen signEditScreen(net.minecraft.world.level.block.entity.SignBlockEntity sign, boolean front,
            boolean filtered) {
        return new net.minecraft.client.gui.screens.inventory.SignEditScreen(sign, front, filtered);
    }

    /** Whether authlib still has the Yggdrasil service (26.2: authlib 7, yes). */
    public static boolean hasYggdrasilAuth() {
        return true;
    }

    /** A user API service signed in with {@code token}, as the launcher's account gets (26.2: Yggdrasil). */
    public static com.mojang.authlib.minecraft.UserApiService createUserApiService(String token) {
        return new com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService(java.net.Proxy.NO_PROXY)
                .createUserApiService(token);
    }
}

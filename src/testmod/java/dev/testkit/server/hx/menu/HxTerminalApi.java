package dev.testkit.server.hx.menu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.server.MinecraftServer;

/**
 * Lets another Hx module open a terminal menu the way {@code menu.terminal} does, from the server thread - for the P3
 * terminal stand fake ({@code hx/boss/HxTerminalStand}), which opens one when the stand is right-clicked.
 */
public final class HxTerminalApi {

    private HxTerminalApi() {
    }

    /** Same arguments as the {@code menu.terminal} op ({@code type}, {@code seed}, ...). */
    public static JsonElement open(MinecraftServer server, JsonObject args) {
        return HxTerminals.open(server, args);
    }
}

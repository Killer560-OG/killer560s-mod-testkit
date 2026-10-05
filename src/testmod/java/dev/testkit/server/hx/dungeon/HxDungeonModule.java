package dev.testkit.server.hx.dungeon;

import com.google.gson.JsonPrimitive;

import dev.testkit.server.hx.HxBridge;
import dev.testkit.server.hx.HxModule;

/**
 * WP6's Hx module: the dungeon sim's server rules ported onto a real floor (etherwarp, secrets, doors).
 *
 * <p>A STUB left by WP1 so {@code HxModules} never has to change. WP6 owns this package ({@code hx/dungeon/**}) and
 * registers its ops here, each named {@code dungeon.<verb>}. Until then it answers {@code dungeon.status} with "stub".
 */
public final class HxDungeonModule implements HxModule {

    @Override
    public String name() {
        return "dungeon";
    }

    @Override
    public void register() {
        HxBridge.register("dungeon.status", (server, args) -> new JsonPrimitive("stub"));
    }
}

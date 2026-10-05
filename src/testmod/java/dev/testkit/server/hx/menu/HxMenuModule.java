package dev.testkit.server.hx.menu;

import com.google.gson.JsonPrimitive;

import dev.testkit.server.hx.HxBridge;
import dev.testkit.server.hx.HxModule;

/**
 * WP3's Hx module: Hypixel menus (HxMenu extends AbstractContainerMenu), terminal and experiment scripts.
 *
 * <p>A STUB left by WP1 so {@code HxModules} never has to change. WP3 owns this package ({@code hx/menu/**}) and
 * registers its ops here, each named {@code menu.<verb>}. Until then it answers {@code menu.status} with "stub".
 */
public final class HxMenuModule implements HxModule {

    @Override
    public String name() {
        return "menu";
    }

    @Override
    public void register() {
        HxBridge.register("menu.status", (server, args) -> new JsonPrimitive("stub"));
    }
}

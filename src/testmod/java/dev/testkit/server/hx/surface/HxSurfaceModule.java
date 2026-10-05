package dev.testkit.server.hx.surface;

import com.google.gson.JsonPrimitive;

import dev.testkit.server.hx.HxBridge;
import dev.testkit.server.hx.HxModule;

/**
 * WP2's Hx module: Hypixel chat/HUD templates (sidebars, tab lists, footers) and command responders.
 *
 * <p>A STUB left by WP1 so {@code HxModules} never has to change. WP2 owns this package ({@code hx/surface/**}) and
 * registers its ops here, each named {@code surface.<verb>}. Until then it answers {@code surface.status} with "stub".
 */
public final class HxSurfaceModule implements HxModule {

    @Override
    public String name() {
        return "surface";
    }

    @Override
    public void register() {
        HxBridge.register("surface.status", (server, args) -> new JsonPrimitive("stub"));
    }
}

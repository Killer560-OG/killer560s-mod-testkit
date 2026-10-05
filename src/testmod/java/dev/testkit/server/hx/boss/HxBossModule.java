package dev.testkit.server.hx.boss;

import com.google.gson.JsonPrimitive;

import dev.testkit.server.hx.HxBridge;
import dev.testkit.server.hx.HxModule;

/**
 * WP7's Hx module: the boss and P3 arena (terminal stands, levers, Simon Says, i4, gates).
 *
 * <p>A STUB left by WP1 so {@code HxModules} never has to change. WP7 owns this package ({@code hx/boss/**}) and
 * registers its ops here, each named {@code boss.<verb>}. Until then it answers {@code boss.status} with "stub".
 */
public final class HxBossModule implements HxModule {

    @Override
    public String name() {
        return "boss";
    }

    @Override
    public void register() {
        HxBridge.register("boss.status", (server, args) -> new JsonPrimitive("stub"));
    }
}

package dev.testkit.server.hx.boss;

import com.google.gson.JsonPrimitive;

import dev.testkit.server.hx.HxBridge;
import dev.testkit.server.hx.HxModule;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * WP7's Hx module: the boss and P3 arena (terminal stands, levers, Simon Says, i4, gates).
 *
 * <p>Left by WP1 as a stub so {@code HxModules} never has to change; ops are named {@code boss.<verb>}. So far it has
 * the P3 Simon Says device ({@link HxSimonSays}: {@code boss.ss.build}, {@code boss.ss.state}, {@code boss.ss.clear}),
 * added 2026-10-06 for the 4-round device of Hypixel's update. {@code boss.status} still answers "stub" for the rest.
 */
public final class HxBossModule implements HxModule {

    @Override
    public String name() {
        return "boss";
    }

    @Override
    public void register() {
        HxBridge.register("boss.status", (server, args) -> new JsonPrimitive("stub"));
        HxBridge.register("boss.ss.build", HxSimonSays::build);
        HxBridge.register("boss.ss.state", (server, args) -> HxSimonSays.describe());
        HxBridge.register("boss.ss.clear", HxSimonSays::clear);
        ServerTickEvents.END_SERVER_TICK.register(HxSimonSays::tick);
        HxBridge.register("boss.term.build", HxTerminalStand::build);
        HxBridge.register("boss.term.spawn", HxTerminalStand::spawn);
        HxBridge.register("boss.term.config", HxTerminalStand::config);
        HxBridge.register("boss.term.state", (server, args) -> HxTerminalStand.describe());
        HxBridge.register("boss.term.clear", HxTerminalStand::clear);
        HxTerminalStand.register();
    }
}

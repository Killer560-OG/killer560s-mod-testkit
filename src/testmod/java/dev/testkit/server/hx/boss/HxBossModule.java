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
        HxBridge.register("boss.grim.block", HxBossModule::grimBlock);
        ServerTickEvents.END_SERVER_TICK.register(HxSimonSays::tick);
        HxBridge.register("boss.term.build", HxTerminalStand::build);
        HxBridge.register("boss.term.spawn", HxTerminalStand::spawn);
        HxBridge.register("boss.term.config", HxTerminalStand::config);
        HxBridge.register("boss.term.state", (server, args) -> HxTerminalStand.describe());
        HxBridge.register("boss.term.clear", HxTerminalStand::clear);
        HxTerminalStand.register();
        HxBridge.register("boss.relic.build", HxRelic::build);
        HxBridge.register("boss.relic.spawn", HxRelic::spawn);
        HxBridge.register("boss.relic.state", (server, args) -> HxRelic.describe());
        HxBridge.register("boss.relic.clear", HxRelic::clear);
        HxRelic.register();
        HxBridge.register("boss.timeline", HxTimeline::start);
        HxBridge.register("boss.timeline.state", (server, args) -> HxTimeline.state());
        HxBridge.register("boss.pertickping", HxTimeline::perTickPing);
        ServerTickEvents.START_SERVER_TICK.register(HxTimeline::tick); // before the levels and the connections, like a plugin scheduler
        // Boss bars (2026-10-25, the mod's Boss Health): HxBossBar.
        HxBridge.register("boss.bar.set", HxBossBar::set);
        HxBridge.register("boss.bar.clear", HxBossBar::clear);
    }

    /**
     * The block state id GrimAC's own copy of the world holds at x/y/z (0 = air, -1 above or below the world, -2 no chunk, -3 no player yet, -9 no anticheat on this server). A client
     * that has a block is no proof the anticheat does: it applies a chunk or block change only when the client's answer to
     * the next transaction arrives, and a use sent before that is judged against air (AirLiquidPlace, 786 on a loaded PC).
     */
    private static com.google.gson.JsonElement grimBlock(net.minecraft.server.MinecraftServer server,
                                                         com.google.gson.JsonObject a) {
        try {
            Object api = Class.forName("ac.grim.grimac.GrimAPI").getField("INSTANCE").get(null);
            Object mgr = api.getClass().getMethod("getPlayerDataManager").invoke(api);
            Object gp = mgr.getClass().getMethod("getPlayer", java.util.UUID.class)
                    .invoke(mgr, dev.testkit.server.hx.HxPrimitives.player(server, a).getUUID());
            if (gp == null) {
                return new JsonPrimitive(-1);
            }
            Object world = gp.getClass().getField("compensatedWorld").get(gp);
            return new JsonPrimitive((Integer) world.getClass().getMethod("getBlockStateId", int.class, int.class, int.class)
                    .invoke(world, a.get("x").getAsInt(), a.get("y").getAsInt(), a.get("z").getAsInt()));
        } catch (ReflectiveOperationException | LinkageError e) {
            return new JsonPrimitive(-1);
        }
    }
}

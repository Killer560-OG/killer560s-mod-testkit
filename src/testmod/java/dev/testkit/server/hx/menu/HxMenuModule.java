package dev.testkit.server.hx.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.arguments.StringArgumentType;

import dev.testkit.server.hx.HxBridge;
import dev.testkit.server.hx.HxChestMenu;
import dev.testkit.server.hx.HxEvents;
import dev.testkit.server.hx.HxModule;
import dev.testkit.server.hx.HxPrimitives;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WP3's Hx module: Hypixel menus, terminals and experiments. Ops (all {@code menu.*}):
 *
 * <pre>
 * menu.status                          -&gt; "wp3"
 * menu.terminal {type, seed=1, count?, letter?, color?, target?, interval=10, closeOnSolve=true}
 *                                      -&gt; {containerId, title, grid, remaining, board, ...}   (HxTerminals)
 * menu.terminal.state                  -&gt; the last terminal: solved, clicks, wrong, maxClicksPerTick, remaining
 * menu.experiment {game, tier, seed, rounds, watch, hold, pairs, clicks, rewards?}   (HxExperiments)
 * menu.experiment.state                -&gt; the last experiment
 * menu.show {title|titleJson, rows=6, slots, fill?, on?, sell?}   -&gt; {containerId}       (HxMenuSpec)
 * menu.set {slot, stack|{item,...}}    replace one slot of the open Hx menu
 * menu.close                           close the player's open container server-side
 * menu.current                         -&gt; {containerId, title, hx}
 * menu.onUse {id, menu}                using an item whose custom_data.id is {@code id} opens {@code menu} (a
 *                                      menu.show spec) - e.g. a Spirit Leap. {id:null} clears it.
 * menu.onCommand {name, menu}          /name (with any args) opens {@code menu}; the command is still recorded
 * </pre>
 * Events added to the stream: menu.opened, terminal.opened/click/solved, experiment.opened/click/round/pair/over,
 * menu.action, sell.sold, menu.use.
 */
public final class HxMenuModule implements HxModule {

    private static final Map<String, JsonObject> ON_USE = new ConcurrentHashMap<>();
    private static final Map<String, JsonObject> ON_COMMAND = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "menu";
    }

    @Override
    public void register() {
        HxTerminals.register();
        HxExperiments.register();
        HxMenuSpec.register();
        ServerTickEvents.END_SERVER_TICK.register(HxMenus::tick);
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (player instanceof ServerPlayer sp && !level.isClientSide()) {
                ItemStack stack = player.getItemInHand(hand);
                var data = stack.get(DataComponents.CUSTOM_DATA);
                String id = data == null ? "" : data.copyTag().getStringOr("id", "");
                JsonObject spec = id.isEmpty() ? null : ON_USE.get(id);
                if (spec != null) {
                    JsonObject e = new JsonObject();
                    e.addProperty("id", id);
                    HxEvents.custom("menu.use", sp, e);
                    HxMenus.later(() -> HxMenuSpec.open(sp.level().getServer(), sp, spec));
                }
            }
            return InteractionResult.PASS;
        });

        HxBridge.register("menu.status", (s, a) -> new JsonPrimitive("wp3"));
        HxBridge.register("menu.terminal", HxTerminals::open);
        HxBridge.register("menu.terminal.state", HxTerminals::state);
        HxBridge.register("menu.experiment", HxExperiments::open);
        HxBridge.register("menu.experiment.state", HxExperiments::state);
        HxBridge.register("menu.show", HxMenuSpec::show);
        HxBridge.register("menu.set", (s, a) -> {
            ServerPlayer p = HxPrimitives.player(s, a);
            HxChestMenu m = HxMenus.current(p);
            if (m == null) {
                throw new IllegalStateException("no Hx menu open");
            }
            JsonElement stack = a.has("stack") ? a.get("stack") : a.get("item");
            m.setItem(a.get("slot").getAsInt(), HxMenuSpec.slot(s, stack));
            return new JsonPrimitive(m.containerId);
        });
        HxBridge.register("menu.close", (s, a) -> {
            ServerPlayer p = HxPrimitives.player(s, a);
            int id = p.containerMenu.containerId;
            p.closeContainer();
            return new JsonPrimitive(id);
        });
        HxBridge.register("menu.current", (s, a) -> {
            ServerPlayer p = HxPrimitives.player(s, a);
            JsonObject o = new JsonObject();
            o.addProperty("containerId", p.containerMenu.containerId);
            HxChestMenu m = HxMenus.current(p);
            o.addProperty("hx", m != null);
            o.addProperty("title", m == null ? "" : m.title());
            return o;
        });
        HxBridge.register("menu.onUse", (s, a) -> {
            String id = a.get("id").getAsString();
            if (a.has("menu") && !a.get("menu").isJsonNull()) {
                ON_USE.put(id, a.getAsJsonObject("menu"));
            } else {
                ON_USE.remove(id);
            }
            return new JsonPrimitive(ON_USE.size());
        });
        HxBridge.register("menu.onCommand", HxMenuModule::onCommand);
    }

    private static JsonElement onCommand(MinecraftServer s, JsonObject a) {
        String name = a.get("name").getAsString();
        if (a.has("menu") && !a.get("menu").isJsonNull()) {
            ON_COMMAND.put(name, a.getAsJsonObject("menu"));
        } else {
            ON_COMMAND.remove(name);
        }
        var dispatcher = s.getCommands().getDispatcher();
        com.mojang.brigadier.Command<CommandSourceStack> answer = ctx -> {
            JsonObject spec = ON_COMMAND.get(name);
            ServerPlayer p = ctx.getSource().getPlayer();
            if (spec != null && p != null) {
                HxMenus.later(() -> HxMenuSpec.open(s, p, spec));
            }
            return 1;
        };
        dispatcher.register(Commands.literal(name).executes(answer)
                .then(Commands.argument("args", StringArgumentType.greedyString()).executes(answer)));
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            s.getCommands().sendCommands(p);
        }
        JsonArray out = new JsonArray();
        ON_COMMAND.keySet().forEach(out::add);
        return out;
    }
}

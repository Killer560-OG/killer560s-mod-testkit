package dev.testkit.server.hx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;

/**
 * What the client did on this server, in order, for a gametest to read back with {@code events.poll}.
 *
 * <p>Recorded: player chat ({@code chat}), every command a player sends including ones nothing handles
 * ({@code cmd}, also printed as {@code [hx] cmd /<full>} to the console), container clicks and closes
 * ({@code container.click}, {@code container.close}), use/attack interactions ({@code use_item}, {@code use_block},
 * {@code use_entity}, {@code attack_entity}, {@code attack_block}), and joins/disconnects. Each event is
 * {@code {seq, type, tick, player, ...}}; {@code events.poll {since}} returns those with a larger seq plus
 * {@code next} to pass as the following {@code since}. A ring of {@link #CAPACITY}: {@code dropped} says when a poll
 * came too late to see everything.
 *
 * <p>Commands and container packets come from mixins ({@code dev.testkit.server.mixin}); the rest from Fabric events,
 * which fire on the server for the real player's packets. Only real players are recorded - not the console, not the
 * harness's fake bots (whose interactions are not packets).
 */
public final class HxEvents {

    public static final int CAPACITY = 4096;

    private static final ArrayDeque<JsonObject> RING = new ArrayDeque<>();
    private static long seq;
    private static long oldestSeq = 1;

    private HxEvents() {
    }

    static void register() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            JsonObject e = base("chat", sender);
            e.addProperty("text", message.signedContent());
            add(e);
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (real(player)) {
                JsonObject e = base("use_item", (ServerPlayer) player);
                e.addProperty("hand", hand.name());
                e.addProperty("item", itemId(player.getItemInHand(hand)));
                add(e);
            }
            return InteractionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (real(player)) {
                JsonObject e = base("use_block", (ServerPlayer) player);
                e.addProperty("hand", hand.name());
                e.add("pos", pos(hit.getBlockPos()));
                e.addProperty("face", hit.getDirection().getName());
                e.addProperty("item", itemId(player.getItemInHand(hand)));
                add(e);
            }
            return InteractionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (real(player)) {
                JsonObject e = base("use_entity", (ServerPlayer) player);
                e.addProperty("hand", hand.name());
                entity(e, entity);
                add(e);
            }
            return InteractionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (real(player)) {
                JsonObject e = base("attack_entity", (ServerPlayer) player);
                entity(e, entity);
                add(e);
            }
            return InteractionResult.PASS;
        });
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (real(player)) {
                JsonObject e = base("attack_block", (ServerPlayer) player);
                e.add("pos", pos(pos));
                e.addProperty("face", direction.getName());
                add(e);
            }
            return InteractionResult.PASS;
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> add(base("join", handler.player)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> add(base("disconnect", handler.player)));
    }

    /** From the Commands mixin: a player sent {@code /command} (without the slash). */
    public static void command(ServerPlayer player, String command) {
        String full = command.startsWith("/") ? command.substring(1) : command;
        JsonObject e = base("cmd", player);
        e.addProperty("command", full);
        String root = full.contains(" ") ? full.substring(0, full.indexOf(' ')) : full;
        e.addProperty("root", root);
        add(e);
        System.out.println("[hx] cmd /" + full);
    }

    /** From the packet-listener mixin, on the server thread. */
    public static void containerClick(ServerPlayer player, int containerId, int slot, int button, String input) {
        JsonObject e = base("container.click", player);
        e.addProperty("containerId", containerId);
        e.addProperty("slot", slot);
        e.addProperty("button", button);
        e.addProperty("input", input);
        e.addProperty("menu", player.containerMenu == null ? "none" : player.containerMenu.getClass().getSimpleName());
        add(e);
    }

    public static void containerClose(ServerPlayer player, int containerId) {
        JsonObject e = base("container.close", player);
        e.addProperty("containerId", containerId);
        add(e);
    }

    /** Anything a module wants a test to see in the same stream (e.g. "a terminal was solved"). */
    public static void custom(String type, ServerPlayer player, JsonObject data) {
        JsonObject e = base(type, player);
        for (var entry : data.entrySet()) {
            e.add(entry.getKey(), entry.getValue());
        }
        add(e);
    }

    /** {@code events.poll}: everything after {@code since}. */
    static JsonElement poll(long since) {
        JsonObject out = new JsonObject();
        JsonArray events = new JsonArray();
        synchronized (RING) {
            for (JsonObject e : RING) {
                if (e.get("seq").getAsLong() > since) {
                    events.add(e.deepCopy());
                }
            }
            out.addProperty("next", seq);
            out.addProperty("dropped", since + 1 < oldestSeq);
        }
        out.add("events", events);
        return out;
    }

    /** The newest seq, so a test can start polling from "now". */
    static long head() {
        synchronized (RING) {
            return seq;
        }
    }

    private static void add(JsonObject e) {
        synchronized (RING) {
            e.addProperty("seq", ++seq);
            RING.addLast(e);
            while (RING.size() > CAPACITY) {
                RING.removeFirst();
                oldestSeq = RING.peekFirst().get("seq").getAsLong();
            }
        }
    }

    private static JsonObject base(String type, ServerPlayer player) {
        JsonObject e = new JsonObject();
        e.addProperty("type", type);
        var server = HxBridge.server();
        e.addProperty("tick", server == null ? -1 : server.getTickCount());
        e.addProperty("player", player == null ? "" : player.getGameProfile().name());
        return e;
    }

    private static boolean real(Player player) {
        // The harness's bots are plain ServerPlayers named SicoKaleb... (TestEnemy); they are not the client.
        return player instanceof ServerPlayer sp && !sp.getGameProfile().name().startsWith("SicoKaleb")
                && !player.level().isClientSide();
    }

    private static String itemId(ItemStack stack) {
        return stack == null || stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static void entity(JsonObject e, Entity entity) {
        e.addProperty("entityId", entity.getId());
        e.addProperty("uuid", entity.getUUID().toString());
        e.addProperty("entityType", entity.getType().toShortString());
        e.addProperty("name", entity.getCustomName() == null ? "" : entity.getCustomName().getString());
    }

    private static JsonArray pos(net.minecraft.core.BlockPos p) {
        JsonArray a = new JsonArray();
        a.add(p.getX());
        a.add(p.getY());
        a.add(p.getZ());
        return a;
    }
}

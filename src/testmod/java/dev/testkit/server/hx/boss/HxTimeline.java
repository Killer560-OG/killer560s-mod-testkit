package dev.testkit.server.hx.boss;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.testkit.server.hx.HxPrimitives;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code boss.timeline} (f7hud-1102): boss events at exact SERVER ticks, for timers that must line up with the server's
 * own tick (Goldor's 60-tick damage tick, Storm's 20-tick pad cycle). Steps are {@code {at: <ticks after the op>, chat:
 * "<line>"}} or {@code {at, damage: <hp>}} (vanilla {@code damage <player> <hp> minecraft:generic}); each runs at the START of
 * server tick {@code base + at} (as a Bukkit-style scheduler would: before the levels tick and before the connections send
 * the health packet, so a hit and its health update leave on the same tick), base being the tick the op ran on. {@code boss.timeline.state} answers each fired step's
 * {@code at} and the server tick it ran on, so a case can check the schedule really held.
 */
final class HxTimeline {

    private record Step(int at, String chat, float damage) {
    }

    private static final List<Step> PENDING = new ArrayList<>();
    private static final JsonArray FIRED = new JsonArray();
    private static long base;
    private static String player;
    /** boss.pertickping: a ClientboundPingPacket to this player every server tick, as Hypixel sends (only meaningful with
     *  -Pnogrim: GrimAC's own transaction pings would sit beside these). Null = off. */
    private static String pinging;
    private static int pingId = 1_000_000;

    private HxTimeline() {
    }

    static JsonElement start(MinecraftServer server, JsonObject args) {
        ServerPlayer p = HxPrimitives.player(server, args);
        player = p.getGameProfile().name();
        PENDING.clear();
        while (!FIRED.isEmpty()) {
            FIRED.remove(0);
        }
        base = server.getTickCount();
        for (JsonElement e : args.getAsJsonArray("steps")) {
            JsonObject o = e.getAsJsonObject();
            PENDING.add(new Step(o.get("at").getAsInt(), o.has("chat") ? o.get("chat").getAsString() : null,
                    o.has("damage") ? o.get("damage").getAsFloat() : 0f));
        }
        JsonObject out = new JsonObject();
        out.addProperty("base", base);
        out.addProperty("steps", PENDING.size());
        return out;
    }

    static JsonElement state() {
        JsonObject out = new JsonObject();
        out.addProperty("base", base);
        out.addProperty("pending", PENDING.size());
        out.add("fired", FIRED.deepCopy());
        return out;
    }

    static JsonElement perTickPing(MinecraftServer server, JsonObject args) {
        boolean on = !args.has("on") || args.get("on").getAsBoolean();
        pinging = on ? HxPrimitives.player(server, args).getGameProfile().name() : null;
        return new com.google.gson.JsonPrimitive(on);
    }

    static void tick(MinecraftServer server) {
        if (pinging != null) {
            ServerPlayer pp = server.getPlayerList().getPlayerByName(pinging);
            if (pp != null) {
                pp.connection.send(new net.minecraft.network.protocol.common.ClientboundPingPacket(pingId++));
            }
        }
        if (PENDING.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        ServerPlayer p = player == null ? null : server.getPlayerList().getPlayerByName(player);
        for (int i = 0; i < PENDING.size(); i++) {
            Step s = PENDING.get(i);
            if (now - base < s.at()) {
                continue;
            }
            PENDING.remove(i--);
            if (p != null) {
                if (s.chat() != null) {
                    p.connection.send(new ClientboundSystemChatPacket(Component.literal(s.chat()), false));
                }
                if (s.damage() > 0f) {
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                            "damage " + player + " " + s.damage() + " minecraft:generic");
                }
            }
            JsonObject f = new JsonObject();
            f.addProperty("at", s.at());
            f.addProperty("tick", now - base);
            FIRED.add(f);
        }
    }
}

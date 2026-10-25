package dev.testkit.server.hx.boss;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import dev.testkit.server.hx.HxPrimitives;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A boss bar the way Hypixel shows one over a boss (F4 Thorn, the F7 withers): {@code boss.bar.set} {key, name (section
 * signs kept) or nameJson, progress 0..1} creates or updates the bar named {@code key} and shows it to the player;
 * {@code boss.bar.clear} {key?} removes one, or every bar. Added 2026-10-25 for the mod's Boss Health (cases 1025-bosshp-*).
 */
final class HxBossBar {

    private static final Map<String, ServerBossEvent> BARS = new LinkedHashMap<>();

    private HxBossBar() {
    }

    static JsonElement set(MinecraftServer server, JsonObject a) {
        ServerPlayer p = HxPrimitives.player(server, a);
        String key = a.has("key") ? a.get("key").getAsString() : "default";
        Component name = HxPrimitives.text(server, a, "name");
        float progress = a.has("progress") ? a.get("progress").getAsFloat() : 1f;
        ServerBossEvent bar = BARS.get(key);
        if (bar == null) {
            bar = new ServerBossEvent(UUID.randomUUID(), name, BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS);
            BARS.put(key, bar);
        } else {
            bar.setName(name);
        }
        bar.setProgress(Math.max(0f, Math.min(1f, progress)));
        bar.addPlayer(p);
        return new JsonPrimitive(name.getString());
    }

    static JsonElement clear(MinecraftServer server, JsonObject a) {
        int removed = 0;
        if (a.has("key")) {
            ServerBossEvent bar = BARS.remove(a.get("key").getAsString());
            if (bar != null) {
                bar.removeAllPlayers();
                removed++;
            }
        } else {
            for (ServerBossEvent bar : BARS.values()) {
                bar.removeAllPlayers();
                removed++;
            }
            BARS.clear();
        }
        return new JsonPrimitive(removed);
    }
}

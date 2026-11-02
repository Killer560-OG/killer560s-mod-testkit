package dev.testkit.server.hx.dungeon;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hypixel's sleeping Fel and dropped Wither Key on the dedicated server, for the Auto Clear GrimAC cases (simmobs2-1101).
 * Both copy the mod's sim (roomsim/SimMobs, SimKeys) as the reference mods read Hypixel:
 * <ul>
 *   <li>{@code dungeon.fel} x y z [wake]: a starred Fel asleep - an enderman named Dinnerbone (name hidden), invisible and
 *       untouchable, under a " (star) Fel 1(heart)" marker stand - that wakes (shows, can be hurt) once a player is within
 *       {@code wake} blocks (9: Hypixel's 4, plus 5 since SkyBlock 0.27.2). 1 HP, no AI, never teleports.</li>
 *   <li>{@code dungeon.witherKey} x y z [range]: a "Wither Key" stand. A player within {@code range} (the sim's pickup
 *       range, 6) takes it: the stand goes and every player gets "&lt;name&gt; has obtained Wither Key!".</li>
 *   <li>{@code dungeon.felKeyState}: per Fel asleep/awake/dead and the Wither Impacts the server had counted when it woke;
 *       per key whether it was picked up and how far away.</li>
 * </ul>
 */
public final class HxFelKey {

    private static final Map<UUID, Fel> FELS = new ConcurrentHashMap<>();
    private static final Map<UUID, Key> KEYS = new ConcurrentHashMap<>();
    private static boolean registered;

    private static final class Fel {
        final UUID stand;
        volatile double wake;
        volatile boolean awake;
        volatile int impactsAtWake = -1;
        volatile double wokeAt = -1;

        Fel(UUID stand, double wake) {
            this.stand = stand;
            this.wake = wake;
        }
    }

    private static final class Key {
        volatile double range;
        volatile String takenBy;
        volatile double takenAt = -1;

        Key(double range) {
            this.range = range;
        }
    }

    private HxFelKey() {
    }

    static void register() {
        dev.testkit.server.hx.HxBridge.register("dungeon.fel", HxFelKey::fel);
        dev.testkit.server.hx.HxBridge.register("dungeon.witherKey", HxFelKey::witherKey);
        dev.testkit.server.hx.HxBridge.register("dungeon.felKeyState", (server, a) -> state(server));
        // Arg id (a Fel's mob UUID or a key's stand UUID) and value: the Fel's wake range or the key's pickup range.
        dev.testkit.server.hx.HxBridge.register("dungeon.felKeySet", (server, a) -> {
            UUID id = UUID.fromString(a.get("id").getAsString());
            double v = a.get("value").getAsDouble();
            Fel f = FELS.get(id);
            if (f != null) {
                f.wake = v;
            }
            Key k = KEYS.get(id);
            if (k != null) {
                k.range = v;
            }
            return null;
        });
        dev.testkit.server.hx.HxBridge.register("dungeon.felKeyReset", (server, a) -> {
            forget();
            return null;
        });
        if (!registered) {
            registered = true;
            ServerTickEvents.END_SERVER_TICK.register(HxFelKey::tick);
        }
    }

    private static JsonElement fel(MinecraftServer server, JsonObject a) {
        ServerLevel level = server.overworld();
        double x = a.get("x").getAsDouble();
        double y = a.get("y").getAsDouble();
        double z = a.get("z").getAsDouble();
        double wake = a.has("wake") ? a.get("wake").getAsDouble() : 9.0;
        var standType = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:armor_stand"));
        var fel = dev.testkit.server.compat.FelMob.create(level);
        fel.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1.0);
        fel.setHealth(1.0f);
        fel.setNoAi(true);
        fel.setPersistenceRequired();
        fel.setPos(x, y, z);
        fel.setInvisible(true);
        var stand = (ArmorStand) standType.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        stand.snapTo(x, y + fel.getBbHeight() + 0.1, z, 0f, 0f);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        dev.testkit.server.compat.McServer.setInvulnerable(stand, true);
        byte flags = stand.getEntityData().get(ArmorStand.DATA_CLIENT_FLAGS);
        stand.getEntityData().set(ArmorStand.DATA_CLIENT_FLAGS, (byte) (flags | ArmorStand.CLIENT_FLAG_MARKER));
        stand.setCustomName(Component.literal(" ✯ Fel 1❤"));
        stand.setCustomNameVisible(true);
        level.addFreshEntity(fel);
        level.addFreshEntity(stand);
        FELS.put(fel.getUUID(), new Fel(stand.getUUID(), wake));
        JsonObject out = new JsonObject();
        out.addProperty("mob", fel.getUUID().toString());
        out.addProperty("stand", stand.getUUID().toString());
        out.addProperty("mobId", fel.getId());
        out.addProperty("standId", stand.getId());
        return out;
    }

    private static JsonElement witherKey(MinecraftServer server, JsonObject a) {
        ServerLevel level = server.overworld();
        double x = a.get("x").getAsDouble();
        double y = a.get("y").getAsDouble();
        double z = a.get("z").getAsDouble();
        double range = a.has("range") ? a.get("range").getAsDouble() : 6.0;
        var standType = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:armor_stand"));
        var stand = (ArmorStand) standType.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        stand.snapTo(x, y, z, 0f, 0f);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setNoBasePlate(true);
        dev.testkit.server.compat.McServer.setInvulnerable(stand, true);
        stand.setCustomName(Component.literal("Wither Key"));
        stand.setCustomNameVisible(true);
        level.addFreshEntity(stand);
        KEYS.put(stand.getUUID(), new Key(range));
        JsonObject out = new JsonObject();
        out.addProperty("stand", stand.getUUID().toString());
        out.addProperty("standId", stand.getId());
        return out;
    }

    private static JsonElement state(MinecraftServer server) {
        ServerLevel level = server.overworld();
        JsonObject out = new JsonObject();
        JsonObject fels = new JsonObject();
        for (var e : FELS.entrySet()) {
            Entity m = level.getEntity(e.getKey());
            JsonObject f = new JsonObject();
            f.addProperty("state", m == null || !m.isAlive() ? "dead" : e.getValue().awake ? "awake" : "asleep");
            f.addProperty("impactsAtWake", e.getValue().impactsAtWake);
            f.addProperty("wokeAt", e.getValue().wokeAt);
            fels.add(e.getKey().toString(), f);
        }
        out.add("fels", fels);
        JsonObject keys = new JsonObject();
        for (var e : KEYS.entrySet()) {
            JsonObject k = new JsonObject();
            k.addProperty("takenBy", e.getValue().takenBy == null ? "" : e.getValue().takenBy);
            k.addProperty("takenAt", e.getValue().takenAt);
            keys.add(e.getKey().toString(), k);
        }
        out.add("keys", keys);
        return out;
    }

    private static void tick(MinecraftServer server) {
        if (FELS.isEmpty() && KEYS.isEmpty()) {
            return;
        }
        try {
            ServerLevel level = server.overworld();
            var players = server.getPlayerList().getPlayers();
            for (var e : FELS.entrySet()) {
                Fel f = e.getValue();
                Entity m = level.getEntity(e.getKey());
                if (m == null || !m.isAlive()) {
                    Entity s = level.getEntity(f.stand);
                    if (s != null) {
                        s.discard();
                    }
                    continue;
                }
                if (f.awake || !dev.testkit.server.compat.FelMob.isFel(m)) {
                    continue;
                }
                for (ServerPlayer sp : players) {
                    if (sp.getGameProfile().name().startsWith("SicoKaleb")) {
                        continue;   // the positive control's fake player
                    }
                    double d = sp.position().distanceTo(m.position());
                    if (d <= f.wake) {
                        f.awake = true;
                        f.wokeAt = d;
                        f.impactsAtWake = HxAbilities.stats().getAsJsonObject().get("witherImpacts").getAsInt();
                        dev.testkit.server.compat.FelMob.setDormant(m, false);
                        break;
                    }
                }
            }
            for (var e : KEYS.entrySet()) {
                Key k = e.getValue();
                if (k.takenBy != null) {
                    continue;
                }
                Entity s = level.getEntity(e.getKey());
                if (s == null) {
                    continue;
                }
                for (ServerPlayer sp : players) {
                    if (sp.getGameProfile().name().startsWith("SicoKaleb")) {
                        continue;
                    }
                    double d = sp.position().distanceTo(s.position());
                    if (d <= k.range) {
                        k.takenBy = sp.getGameProfile().name();
                        k.takenAt = d;
                        s.discard();
                        Component line = Component.literal(k.takenBy + " has obtained Wither Key!");
                        for (ServerPlayer to : players) {
                            to.sendSystemMessage(line);
                        }
                        break;
                    }
                }
            }
        } catch (RuntimeException ex) {
            // never stall the server tick
        }
    }

    /** Forgets every Fel and key (the arena is rebuilt per case). */
    static void forget() {
        FELS.clear();
        KEYS.clear();
    }
}

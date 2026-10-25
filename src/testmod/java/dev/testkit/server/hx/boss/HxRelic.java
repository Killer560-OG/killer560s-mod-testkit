package dev.testkit.server.hx.boss;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.testkit.server.hx.HxEvents;
import dev.testkit.server.hx.HxPrimitives;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The M7 Phase 5 relic floor as the mod's Relic Triggerbot and Relic Look see it: the five relic cauldrons at Hypixel's
 * real positions (y 7, Odin / NoammAddons coordinates, the mod's {@code witherdragons.KingRelic}) on a stone floor at
 * y 6, and relics - invisible, non-marker armor stands wearing a player head named "Corrupted &lt;Color&gt; Relic" with
 * Skyblock id {@code <COLOR>_KING_RELIC}.
 *
 * <p>The server's rules, a stand-in for Hypixel's: a main-hand interact on a relic stand from within 3.0 blocks (eye to
 * box) gives the relic into hotbar slot 9, removes the stand and sends "&lt;name&gt; picked the Corrupted &lt;Color&gt;
 * Relic!"; a main-hand use on a relic cauldron while the relic is in the inventory removes it and, when the cauldron is
 * that relic's own, puts a placed relic stand on the cauldron's x/z.
 *
 * <p>Ops: {@code boss.relic.build}, {@code boss.relic.spawn {color, x, y, z}}, {@code boss.relic.state},
 * {@code boss.relic.clear}. Events: {@code relic.pickup {color, dist, taken}}, {@code relic.place {color, cauldron,
 * placed, heldSlot}}.
 */
final class HxRelic {

    /** color -> {cauldron x, y, z, placed x, placed z}. */
    static final Map<String, int[]> RELICS = new LinkedHashMap<>();

    static {
        RELICS.put("Green", new int[]{49, 7, 44, 50, 45});
        RELICS.put("Purple", new int[]{54, 7, 41, 55, 42});
        RELICS.put("Blue", new int[]{59, 7, 44, 60, 45});
        RELICS.put("Orange", new int[]{57, 7, 42, 58, 43});
        RELICS.put("Red", new int[]{51, 7, 42, 52, 43});
    }

    private static final Map<ArmorStand, String> STANDS = new LinkedHashMap<>();
    private static int pickups;
    private static int places;

    private HxRelic() {
    }

    static void register() {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp) || !(entity instanceof ArmorStand stand)
                    || !STANDS.containsKey(stand)) {
                return InteractionResult.PASS;
            }
            if (hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.SUCCESS;
            }
            String color = STANDS.get(stand);
            double dist = Math.sqrt(boxDistanceSq(sp.getEyePosition(), stand.getBoundingBox()));
            boolean taken = dist <= 3.0 && heldRelic(sp) == null;
            if (taken) {
                pickups++;
                try {
                    sp.getInventory().setItem(8, relic(sp.level().getServer(), color));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                sp.inventoryMenu.broadcastChanges();
                sp.containerMenu.broadcastChanges();
                STANDS.remove(stand);
                stand.discard();
                sp.connection.send(new ClientboundSystemChatPacket(
                        Component.literal(sp.getGameProfile().name() + " picked the Corrupted " + color + " Relic!"), false));
            }
            JsonObject d = new JsonObject();
            d.addProperty("color", color);
            d.addProperty("dist", Math.round(dist * 1000) / 1000.0);
            d.addProperty("taken", taken);
            HxEvents.custom("relic.pickup", sp, d);
            return InteractionResult.SUCCESS;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp)) {
                return InteractionResult.PASS;
            }
            String cauldron = cauldronAt(hit.getBlockPos());
            if (cauldron == null) {
                return InteractionResult.PASS;
            }
            if (hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.SUCCESS;
            }
            String held = heldRelic(sp);
            boolean placed = cauldron.equals(held);
            int slot = -1;
            if (held != null) {
                for (int i = 0; i < sp.getInventory().getContainerSize(); i++) {
                    if (held.equals(colorOf(sp.getInventory().getItem(i)))) {
                        slot = i;
                    }
                }
            }
            if (placed) {
                places++;
                sp.getInventory().setItem(slot, ItemStack.EMPTY);
                sp.inventoryMenu.broadcastChanges();
                sp.containerMenu.broadcastChanges();
                int[] r = RELICS.get(cauldron);
                ServerLevel sl = (ServerLevel) sp.level();
                try {
                    ArmorStand s = stand(sl, r[3], 7, r[4], cauldron);
                    STANDS.remove(s); // a placed relic is not one to pick up again
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            JsonObject d = new JsonObject();
            d.addProperty("color", held);
            d.addProperty("cauldron", cauldron);
            d.addProperty("placed", placed);
            d.addProperty("relicSlot", slot);
            d.addProperty("selectedSlot", sp.getInventory().getSelectedSlot());
            HxEvents.custom("relic.place", sp, d);
            return InteractionResult.SUCCESS;
        });
    }

    static JsonElement build(MinecraftServer server, JsonObject a) {
        clear(server, a);
        ServerLevel level = server.overworld();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int x = 40; x <= 70; x++) {
            for (int z = 30; z <= 62; z++) {
                level.setBlockAndUpdate(new BlockPos(x, 6, z), stone);
                for (int y = 7; y <= 14; y++) {
                    level.setBlockAndUpdate(new BlockPos(x, y, z), air);
                }
            }
        }
        for (int[] r : RELICS.values()) {
            level.setBlockAndUpdate(new BlockPos(r[0], r[1], r[2]), Blocks.CAULDRON.defaultBlockState());
        }
        pickups = 0;
        places = 0;
        return describe();
    }

    static JsonElement spawn(MinecraftServer server, JsonObject a) throws Exception {
        String color = cap(a.get("color").getAsString());
        if (!RELICS.containsKey(color)) {
            throw new IllegalArgumentException("no relic color " + color);
        }
        ArmorStand s = stand(server.overworld(), a.get("x").getAsDouble(), a.get("y").getAsDouble(), a.get("z").getAsDouble(), color);
        JsonObject o = describe();
        o.addProperty("standId", s.getId());
        return o;
    }

    private static ArmorStand stand(ServerLevel level, double x, double y, double z, String color) throws Exception {
        ArmorStand s = new ArmorStand(level, x, y, z);
        s.setNoGravity(true);
        s.setInvisible(true);
        s.setItemSlot(EquipmentSlot.HEAD, relic(level.getServer(), color));
        if (!level.addFreshEntity(s)) {
            throw new IllegalStateException("the level refused the relic stand");
        }
        STANDS.put(s, color);
        return s;
    }

    static JsonElement clear(MinecraftServer server, JsonObject a) {
        for (ArmorStand s : STANDS.keySet()) {
            s.discard();
        }
        STANDS.clear();
        // Placed stands were dropped from STANDS; sweep every relic-headed stand in the arena too.
        ServerLevel level = server.overworld();
        for (ArmorStand s : level.getEntitiesOfClass(ArmorStand.class, new AABB(35, 0, 25, 75, 20, 67))) {
            if (colorOf(s.getItemBySlot(EquipmentSlot.HEAD)) != null) {
                s.discard();
            }
        }
        return describe();
    }

    static JsonObject describe() {
        JsonObject o = new JsonObject();
        o.addProperty("stands", STANDS.size());
        o.addProperty("pickups", pickups);
        o.addProperty("places", places);
        return o;
    }

    static ItemStack relic(MinecraftServer server, String color) throws Exception {
        return HxPrimitives.stack(server, "minecraft:player_head[custom_name=\"Corrupted " + color + " Relic\",custom_data={id:\""
                + color.toUpperCase(Locale.ROOT) + "_KING_RELIC\"}]", 1);
    }

    private static String heldRelic(ServerPlayer sp) {
        for (int i = 0; i < sp.getInventory().getContainerSize(); i++) {
            String c = colorOf(sp.getInventory().getItem(i));
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    private static String colorOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String name = stack.getHoverName().getString();
        for (String c : RELICS.keySet()) {
            if (name.equals("Corrupted " + c + " Relic")) {
                return c;
            }
        }
        return null;
    }

    private static String cauldronAt(BlockPos pos) {
        for (Map.Entry<String, int[]> e : RELICS.entrySet()) {
            int[] r = e.getValue();
            if (pos.getX() == r[0] && pos.getY() == r[1] && pos.getZ() == r[2]) {
                return e.getKey();
            }
        }
        return null;
    }

    private static String cap(String s) {
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    private static double boxDistanceSq(Vec3 p, AABB b) {
        double dx = Math.max(0, Math.max(b.minX - p.x, p.x - b.maxX));
        double dy = Math.max(0, Math.max(b.minY - p.y, p.y - b.maxY));
        double dz = Math.max(0, Math.max(b.minZ - p.z, p.z - b.maxZ));
        return dx * dx + dy * dy + dz * dz;
    }
}

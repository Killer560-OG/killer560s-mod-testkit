package dev.testkit.server.hx.dungeon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import dev.testkit.server.hx.HxBridge;
import dev.testkit.server.hx.HxModule;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * WP6's Hx module: the dungeon sim's server rules on a real floor - here, what Auto Routes needs on the GrimAC server.
 *
 * <p>Ops:
 * <ul>
 *   <li>{@code dungeon.status} - "stub" until {@code dungeon.abilities} turns the abilities on, then "abilities".</li>
 *   <li>{@code dungeon.paste} - one captured room (the mod's {@code RoomLibrary.Room}, sent by the client as palette +
 *       base64 big-endian shorts) at a dungeon grid cell, with the whole column cleared so the live map's real scan
 *       (core hash over y 140..12, the blue terracotta roof marker) sees exactly the capture.</li>
 *   <li>{@code dungeon.blocks} - set blocks ("x y z state" strings) with neighbour updates.</li>
 *   <li>{@code dungeon.abilities} - turn the Hypixel ability emulation ({@link HxAbilities}) on or off.</li>
 *   <li>{@code dungeon.stats} / {@code dungeon.reset} - what the abilities did, so a scenario can prove a warp was
 *       the SERVER's.</li>
 * </ul>
 */
public final class HxDungeonModule implements HxModule {

    /** Grid origin of a Catacombs floor - the mod's {@code LiveMapFeature.START_X/START_Z}. */
    private static final int START = -185;
    private static final int HALF_ROOM = 16;
    private static final int TILE = 31;

    @Override
    public String name() {
        return "dungeon";
    }

    @Override
    public void register() {
        HxAbilities.register();
        HxBridge.register("dungeon.status", (server, args) -> new JsonPrimitive(HxAbilities.enabled() ? "abilities" : "stub"));
        HxBridge.register("dungeon.abilities", (server, args) -> {
            HxAbilities.setEnabled(!args.has("enabled") || args.get("enabled").getAsBoolean());
            return new JsonPrimitive(HxAbilities.enabled());
        });
        HxBridge.register("dungeon.stats", (server, args) -> HxAbilities.stats());
        HxBridge.register("dungeon.reset", (server, args) -> {
            HxAbilities.resetStats();
            return null;
        });
        HxBridge.register("dungeon.paste", HxDungeonModule::paste);
        HxBridge.register("dungeon.blocks", HxDungeonModule::blocks);
        HxBridge.register("dungeon.tp", HxDungeonModule::tp);
    }

    /** A server teleport to x, y, z with an absolute yaw/pitch - what {@code /tp} does, but answered synchronously. */
    private static JsonElement tp(MinecraftServer server, JsonObject a) {
        net.minecraft.server.level.ServerPlayer sp = dev.testkit.server.hx.HxPrimitives.player(server, a);
        sp.teleportTo(server.overworld(), a.get("x").getAsDouble(), a.get("y").getAsDouble(), a.get("z").getAsDouble(),
                java.util.Set.<net.minecraft.world.entity.Relative>of(), a.get("yaw").getAsFloat(),
                a.get("pitch").getAsFloat(), false);
        return null;
    }

    /**
     * Pastes a captured room at grid cell (cellX, cellZ) exactly as the sim's {@code RoomPlacer.paste} lays it out
     * (origin = cell centre - TILE/2 - margin, no rotation, no altitude shift: relative y IS world y on Hypixel), and
     * writes AIR everywhere in the footprint from {@code clearMinY} to {@code clearMaxY} the capture does not cover,
     * so the dedicated server's own terrain cannot leak into the core hash.
     */
    private static JsonElement paste(MinecraftServer server, JsonObject a) {
        ServerLevel level = server.overworld();
        int sizeX = a.get("sizeX").getAsInt();
        int sizeZ = a.get("sizeZ").getAsInt();
        int minY = a.get("minY").getAsInt();
        int maxY = a.get("maxY").getAsInt();
        int margin = a.get("margin").getAsInt();
        int cellX = a.get("cellX").getAsInt();
        int cellZ = a.get("cellZ").getAsInt();
        int clearMinY = a.has("clearMinY") ? a.get("clearMinY").getAsInt() : 0;
        int clearMaxY = a.has("clearMaxY") ? a.get("clearMaxY").getAsInt() : 160;
        JsonArray paletteJson = a.getAsJsonArray("palette");
        BlockState[] palette = new BlockState[paletteJson.size()];
        int unresolved = 0;
        for (int i = 0; i < palette.length; i++) {
            palette[i] = resolve(paletteJson.get(i).getAsString());
            if (palette[i] == null) {
                unresolved++;
            }
        }
        byte[] raw = java.util.Base64.getDecoder().decode(a.get("blocks").getAsString());
        int layers = maxY - minY + 1;
        if (raw.length != 2L * sizeX * sizeZ * layers) {
            throw new IllegalArgumentException("blocks is " + raw.length + " bytes, expected "
                    + (2L * sizeX * sizeZ * layers));
        }
        int originX = START + cellX * HALF_ROOM * 2;
        int originZ = START + cellZ * HALF_ROOM * 2;
        int x0 = originX - TILE / 2 - margin;
        int z0 = originZ - TILE / 2 - margin;
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int placed = 0;
        int lo = Math.min(clearMinY, minY);
        int hi = Math.max(clearMaxY, maxY);
        for (int y = lo; y <= hi; y++) {
            for (int x = 0; x < sizeX; x++) {
                for (int z = 0; z < sizeZ; z++) {
                    BlockState state = air;
                    if (y >= minY && y <= maxY) {
                        int i = ((y - minY) * sizeX * sizeZ + z * sizeX + x) * 2;
                        short idx = (short) (((raw[i] & 0xff) << 8) | (raw[i + 1] & 0xff));
                        if (idx >= 0 && idx < palette.length && palette[idx] != null) {
                            state = palette[idx];
                        }
                    }
                    level.setBlock(pos.set(x0 + x, y, z0 + z), state, flags);
                    if (!state.isAir()) {
                        placed++;
                    }
                }
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("placed", placed);
        out.addProperty("unresolved", unresolved);
        out.addProperty("x0", x0);
        out.addProperty("z0", z0);
        out.addProperty("centreX", originX);
        out.addProperty("centreZ", originZ);
        System.out.println("[testkit] dungeon.paste: " + placed + " block(s) at cell " + cellX + "," + cellZ
                + " (x " + x0 + ".." + (x0 + sizeX - 1) + ", z " + z0 + ".." + (z0 + sizeZ - 1) + ", y " + lo + ".." + hi
                + "), " + unresolved + " unresolved palette entr(ies)");
        return out;
    }

    /** {@code blocks}: ["x y z state", ...], each set with a full update (neighbours, clients). */
    private static JsonElement blocks(MinecraftServer server, JsonObject a) {
        ServerLevel level = server.overworld();
        int n = 0;
        for (JsonElement e : a.getAsJsonArray("blocks")) {
            String[] parts = e.getAsString().trim().split("\\s+", 4);
            BlockState state = resolve(parts[3]);
            if (state == null) {
                throw new IllegalArgumentException("no such block state: " + parts[3]);
            }
            level.setBlockAndUpdate(new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])), state);
            n++;
        }
        return new JsonPrimitive(n);
    }

    /** The full state string ({@code minecraft:lever[face=floor,...]}) or a bare id - RoomPlacer.resolve's parse. */
    static BlockState resolve(String entry) {
        try {
            return net.minecraft.commands.arguments.blocks.BlockStateParser
                    .parseForBlock(BuiltInRegistries.BLOCK, entry, false)
                    .blockState();
        } catch (Exception e) {
            return null;
        }
    }
}

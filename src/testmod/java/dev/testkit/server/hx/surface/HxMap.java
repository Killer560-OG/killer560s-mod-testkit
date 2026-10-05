package dev.testkit.server.hx.surface;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import dev.testkit.server.hx.HxPrimitives;

import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.List;
import java.util.Optional;

/**
 * {@code surface.map {id=9001, fill=0, rects: [[x, y, w, h, colorId], ...]}}: sends the client a full 128x128
 * {@link ClientboundMapItemDataPacket} for map {@code id}, painted with the given rectangles of raw map colour ids
 * (later rectangles over earlier ones). Pair it with {@code give {slot: 8, stack: "minecraft:filled_map[map_id=<id>]"}}
 * - Hypixel's dungeon map lives in hotbar slot 9, which is where livemap/DungeonMapScanner.java:326-335 reads it.
 *
 * <p>The server keeps NO saved data for that id, so vanilla's own map syncing ({@code MapItem.getUpdatePacket})
 * finds nothing and never overwrites what this sent. The colour ids that mean something to the mod are listed in
 * DungeonMapScanner.java:37-38 (30 entrance, 66 puzzle) and :236-254 (room/door colours).
 *
 * <p>Constructor checked with javap (2026-10-04): {@code ClientboundMapItemDataPacket(MapId, byte, boolean,
 * Optional<List<MapDecoration>>, Optional<MapItemSavedData.MapPatch>)}, {@code MapPatch(int, int, int, int, byte[])}.
 */
final class HxMap {

    private static final int SIZE = 128;

    private HxMap() {
    }

    static JsonElement send(MinecraftServer s, JsonObject a) {
        int id = a.has("id") ? a.get("id").getAsInt() : 9001;
        byte[] colors = new byte[SIZE * SIZE];
        byte fill = (byte) (a.has("fill") ? a.get("fill").getAsInt() : 0);
        java.util.Arrays.fill(colors, fill);
        int painted = 0;
        if (a.has("rects")) {
            for (JsonElement el : a.getAsJsonArray("rects")) {
                var r = el.getAsJsonArray();
                int x0 = r.get(0).getAsInt();
                int y0 = r.get(1).getAsInt();
                int w = r.get(2).getAsInt();
                int h = r.get(3).getAsInt();
                byte c = (byte) r.get(4).getAsInt();
                for (int y = Math.max(0, y0); y < Math.min(SIZE, y0 + h); y++) {
                    for (int x = Math.max(0, x0); x < Math.min(SIZE, x0 + w); x++) {
                        colors[y * SIZE + x] = c;
                        painted++;
                    }
                }
            }
        }
        ClientboundMapItemDataPacket packet = new ClientboundMapItemDataPacket(new MapId(id), (byte) 0, true,
                Optional.of(List.<MapDecoration>of()),
                Optional.of(new MapItemSavedData.MapPatch(0, 0, SIZE, SIZE, colors)));
        HxPrimitives.send(s, a, packet);
        return new JsonPrimitive(painted);
    }
}

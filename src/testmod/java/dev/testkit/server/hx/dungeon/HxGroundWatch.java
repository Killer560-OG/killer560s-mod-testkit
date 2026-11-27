package dev.testkit.server.hx.dungeon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.testkit.server.hx.HxBridge;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * What a server that checks movement against its OWN world (Hypixel) corrects, and GrimAC, which judges each move
 * against the world the client has acknowledged, never sees (newtrap-fall-1127): every server tick while armed, a
 * player whose last move claimed ground with no block under him in the server's world ("ground over server air":
 * the client still stands on a block the server has already broken), or whose box sits inside a block the server
 * still has. {@code dungeon.groundWatch} with {@code on} arms and clears it; without, it returns the counts.
 */
final class HxGroundWatch {

    private static boolean armed;
    private static int ticks;
    private static int overAir;
    private static int inside;
    private static final JsonArray FIRST = new JsonArray();

    private HxGroundWatch() {
    }

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!armed) {
                return;
            }
            ticks++;
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                AABB box = p.getBoundingBox();
                boolean under = solid(p, box.move(0.0, -0.05, 0.0));
                boolean in = solid(p, box.deflate(0.002));
                if (p.onGround() && !under) {
                    overAir++;
                    note("over air", p);
                }
                if (in) {
                    inside++;
                    note("inside", p);
                }
            }
        });
        HxBridge.register("dungeon.groundWatch", (server, args) -> {
            if (args != null && args.has("on")) {
                armed = args.get("on").getAsBoolean();
                ticks = 0;
                overAir = 0;
                inside = 0;
                while (!FIRST.isEmpty()) {
                    FIRST.remove(0);
                }
                return null;
            }
            return stats();
        });
    }

    private static boolean solid(ServerPlayer p, AABB box) {
        for (VoxelShape s : p.level().getBlockCollisions(p, box)) {
            if (!s.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static void note(String what, ServerPlayer p) {
        if (FIRST.size() < 12) {
            FIRST.add(String.format(java.util.Locale.ROOT, "%s at tick %d y %.3f", what, ticks, p.getY()));
        }
    }

    private static JsonElement stats() {
        JsonObject o = new JsonObject();
        o.addProperty("ticks", ticks);
        o.addProperty("overAir", overAir);
        o.addProperty("inside", inside);
        o.add("first", FIRST.deepCopy());
        return o;
    }
}

package dev.testkit.server.hx.dungeon;

import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Bonzo's Staff, the Jerry-chine Gun and Spring Boots as Hypixel's server answers them (simitems-1105), the same model the
 * mod's sim uses ({@code roomsim.SimLaunchItems}), rebuilt here because the testmod cannot load the mod. Every launch is a
 * server motion packet, as Hypixel sends it, so GrimAC judges the client's flight against a real velocity. Bonzo: a balloon
 * flies 0.75 a tick from feet + 1 + 0.2 look and bursts inside a block; within 3.5 of feet + 1.5 his motion becomes 1.5 flat
 * away from it and 0.5 up; one balloon per 4 ticks. Jerry: bullet k at feet + 1.1 + (0.25 + 0.75 k) look, bursting inside
 * a block; within 4 of feet + 1.62 his motion becomes 0.5 flat away and 0.6 up; 3 a tick at most. Spring Boots: sneaking on
 * the ground counts a note every 3 ticks (44 at most); releasing on the ground launches him straight up to the height of
 * the Spring Boots table for that many notes.
 */
final class HxLaunchItems {

    private static final float[] HEIGHTS = {
            0.0f, 3.0f, 6.5f, 9.0f, 11.5f, 13.5f, 16.0f, 18.0f, 19.0f,
            20.5f, 22.5f, 25.0f, 26.5f, 28.0f, 29.0f, 30.0f, 31.0f, 33.0f,
            34.0f, 35.5f, 37.0f, 38.0f, 39.5f, 40.0f, 41.0f, 42.5f, 43.5f,
            44.0f, 45.0f, 46.0f, 47.0f, 48.0f, 49.0f, 50.0f, 51.0f, 52.0f,
            53.0f, 54.0f, 55.0f, 56.0f, 57.0f, 58.0f, 59.0f, 60.0f, 61.0f};

    private record Shot(ServerPlayer owner, boolean bonzo, Vec3 origin, Vec3 dir, int[] age) {
    }

    private static final List<Shot> SHOTS = new ArrayList<>();
    private static int lastBonzo = -100, jerryTick = -1, jerryThisTick;
    private static int springNotes, springTicks;
    static int bonzoShots, jerryShots, launches, springLaunches;
    private static boolean registered;

    private HxLaunchItems() {
    }

    static void register() {
        if (!registered) {
            registered = true;
            ServerTickEvents.END_SERVER_TICK.register(HxLaunchItems::tick);
        }
    }

    /** A use with one of the two guns; false when the id is neither. */
    static boolean use(ServerPlayer sp, String id) {
        int now = sp.level().getServer().getTickCount();
        if ("JERRY_STAFF".equals(id)) {
            if (jerryTick != now) {
                jerryTick = now;
                jerryThisTick = 0;
            }
            if (++jerryThisTick <= 3 && SHOTS.size() < 48) {
                SHOTS.add(new Shot(sp, false, sp.position().add(0, 1.1, 0), sp.getLookAngle(), new int[]{0}));
                jerryShots++;
            }
            return true;
        }
        if (!"BONZO_STAFF".equals(id) && !"STARRED_BONZO_STAFF".equals(id)) {
            return false;
        }
        if (now - lastBonzo >= 4 && SHOTS.size() < 48) {
            lastBonzo = now;
            Vec3 dir = sp.getLookAngle();
            SHOTS.add(new Shot(sp, true, sp.position().add(0, 1.0, 0).add(dir.scale(0.2)), dir, new int[]{0}));
            bonzoShots++;
        }
        return true;
    }

    static void stats(JsonObject o) {
        o.addProperty("bonzoShots", bonzoShots);
        o.addProperty("jerryShots", jerryShots);
        o.addProperty("launches", launches);
        o.addProperty("springLaunches", springLaunches);
    }

    static void reset() {
        bonzoShots = jerryShots = launches = springLaunches = 0;
        SHOTS.clear();
        springNotes = springTicks = 0;
    }

    private static void tick(MinecraftServer server) {
        if (!HxAbilities.enabled()) {
            SHOTS.clear();
            return;
        }
        SHOTS.removeIf(HxLaunchItems::step);
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            spring(sp);
        }
    }

    private static boolean step(Shot s) {
        if (s.owner.isRemoved()) {
            return true;
        }
        ServerLevel level = (ServerLevel) s.owner.level();
        int j = ++s.age[0];
        if (s.bonzo) {
            Vec3 at = s.origin.add(s.dir.scale(0.75 * (j - 1)));
            if (j - 1 >= 1 && solid(level, at)) {
                burst(s, at);
                return true;
            }
            return j > 170;
        }
        Vec3 at = s.origin.add(s.dir.scale(0.25 + 0.75 * j));
        if (j < 30 && !solid(level, at)) {
            return false;
        }
        burst(s, at);
        return true;
    }

    private static void burst(Shot s, Vec3 at) {
        ServerPlayer sp = s.owner;
        Vec3 feet = sp.position();
        Vec3 push = null;
        if (s.bonzo) {
            if (at.distanceTo(feet.add(0, 1.5, 0)) <= 3.5) {
                double dx = feet.x - at.x, dz = feet.z - at.z, h = Math.sqrt(dx * dx + dz * dz);
                push = h > 0 ? new Vec3(1.5 * dx / h, 0.5, 1.5 * dz / h) : new Vec3(0, 0.5, 0);
            }
        } else {
            Vec3 d = feet.add(0, 1.62, 0).subtract(at);
            double len = d.length();
            if (len <= 4.0 && len > 0) {
                push = new Vec3(0.5 * d.x / len, 0.6, 0.5 * d.z / len);
            }
        }
        if (push != null) {
            launches++;
            push(sp, push);
        }
    }

    private static void spring(ServerPlayer sp) {
        if (!sp.isShiftKeyDown() && springNotes == 0) {
            springTicks = 0;
            return;
        }
        if (!"SPRING_BOOTS".equals(HxAbilities.idOf(sp.getItemBySlot(EquipmentSlot.FEET))) || !sp.onGround()) {
            springNotes = springTicks = 0;
            return;
        }
        if (sp.isShiftKeyDown()) {
            if (++springTicks % 3 == 0 && springNotes < 44) {
                springNotes++;
            }
            return;
        }
        double vy = launchSpeed(HEIGHTS[Math.min(HEIGHTS.length - 1, springNotes)]);
        System.out.println("[testkit] spring boots: " + springNotes + " notes, vy " + vy);
        springNotes = springTicks = 0;
        springLaunches++;
        push(sp, new Vec3(0, vy, 0));
    }

    private static void push(ServerPlayer sp, Vec3 v) {
        sp.setDeltaMovement(v);
        sp.connection.send(new ClientboundSetEntityMotionPacket(sp.getId(), v));
    }

    private static double launchSpeed(double height) {
        double lo = 0, hi = 8;
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2;
            double y = 0, v = mid;
            while (v > 0) {
                y += v;
                v = (v - 0.08) * 0.98;
            }
            if (y < height) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return hi;
    }

    private static boolean solid(ServerLevel level, Vec3 at) {
        BlockPos pos = BlockPos.containing(at);
        BlockState st = level.getBlockState(pos);
        if (st.isAir()) {
            return false;
        }
        for (AABB box : st.getCollisionShape(level, pos, CollisionContext.empty()).toAabbs()) {
            if (box.move(pos).contains(at)) {
                return true;
            }
        }
        return false;
    }
}

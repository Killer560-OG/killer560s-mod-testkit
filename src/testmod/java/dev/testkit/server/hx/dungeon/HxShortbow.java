package dev.testkit.server.hx.dungeon;

import com.google.gson.JsonObject;

import dev.testkit.server.hx.HxEvents;

import net.fabricmc.fabric.api.event.player.UseItemCallback;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;

/**
 * Hypixel's shortbows as SERVER behavior (mod branch i4-release-1022): a use packet with a Terminator
 * ({@code TERMINATOR}, three arrows, the outer two 5 degrees either side) or a Machine Gun Shortbow
 * ({@code MACHINE_GUN_BOW}, one arrow) fires at once - no draw - along the rotation the use packet carried, at a fully
 * drawn bow's 3.0, unless it lands inside the shortbow cooldown ({@code cooldownTicks}; Hypixel's
 * {@code ceil(10 / (1 + AS/100))}, the mod's {@code roomsim.SimAttackSpeed}), which drops it. The mod's own sim model,
 * {@code roomsim.SimTerminator}, rebuilt here because the testmod cannot load the mod. Vanilla's own bow use is left
 * alone (PASS), so GrimAC sees the client's real use and release. Off until {@code dungeon.shortbow} turns it on.
 *
 * <p>Events: {@code shortbow.shot} (yaw, pitch, arrows, ids) and {@code shortbow.cooldown} (yaw, pitch, ticksLeft).
 */
public final class HxShortbow {

    private static volatile boolean enabled;
    private static volatile int cooldownTicks = 5;
    private static boolean registered;
    private static int lastShotTick = Integer.MIN_VALUE / 2;
    private static int shots;
    private static int dropped;

    private HxShortbow() {
    }

    static void register() {
        if (registered) {
            return;
        }
        registered = true;
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (enabled && player instanceof ServerPlayer sp && !level.isClientSide()) {
                use(sp, sp.getItemInHand(hand));
            }
            return InteractionResult.PASS;
        });
    }

    static JsonObject set(boolean on, int cooldown) {
        enabled = on;
        cooldownTicks = Math.max(0, cooldown);
        lastShotTick = Integer.MIN_VALUE / 2;
        shots = 0;
        dropped = 0;
        System.out.println("[testkit] shortbow emulation " + (on ? "ON, cooldown " + cooldownTicks + " ticks" : "off"));
        return stats();
    }

    static JsonObject stats() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.addProperty("cooldownTicks", cooldownTicks);
        o.addProperty("shots", shots);
        o.addProperty("dropped", dropped);
        return o;
    }

    private static void use(ServerPlayer sp, ItemStack held) {
        CustomData data = held.get(DataComponents.CUSTOM_DATA);
        String id = data == null ? null : data.copyTag().getStringOr("id", null);
        int arrows = "TERMINATOR".equals(id) ? 3 : "MACHINE_GUN_BOW".equals(id) ? 1 : 0;
        if (arrows == 0 || !(sp.level() instanceof ServerLevel level)) {
            return;
        }
        int now = level.getServer().getTickCount();
        float yaw = sp.getYRot();
        float pitch = sp.getXRot();
        JsonObject e = new JsonObject();
        e.addProperty("yaw", yaw);
        e.addProperty("pitch", pitch);
        if (now - lastShotTick < cooldownTicks) {
            dropped++;
            e.addProperty("ticksLeft", cooldownTicks - (now - lastShotTick));
            HxEvents.custom("shortbow.cooldown", sp, e);
            return;
        }
        lastShotTick = now;
        shots++;
        Vec3 eye = sp.getEyePosition();
        com.google.gson.JsonArray ids = new com.google.gson.JsonArray();
        for (int i = 0; i < arrows; i++) {
            double offset = (i - (arrows - 1) / 2.0) * 5.0;
            Vec3 dir = Vec3.directionFromRotation(pitch, yaw + (float) offset);
            Arrow arrow = new Arrow(level, sp, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
            arrow.setPos(eye.x, eye.y - 0.1, eye.z);
            arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
            arrow.shoot(dir.x, dir.y, dir.z, 3.0f, 0.0f);
            if (level.addFreshEntity(arrow)) {
                ids.add(arrow.getId());
            }
        }
        e.addProperty("arrows", ids.size());
        e.add("ids", ids);
        HxEvents.custom("shortbow.shot", sp, e);
    }
}

package dev.testkit.server.mixin;

import dev.testkit.server.hx.HxBridge;

import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Attack probe (witheraim-1113): one server-log line per attack packet, on the server thread, with the server tick, the
 * server-side player position, the distance to the target and the first reason vanilla's handleAttack (javap 26.3)
 * would return early (not loaded, spectator, no target / outside border, out of the 3.0 range). Read it with grep
 * "[atkprobe]" in the test server's log.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MixinAttackProbe {

    private static final Logger TESTKIT$LOG = LoggerFactory.getLogger("testkit-atkprobe");

    @Shadow
    public ServerPlayer player;

    @Shadow
    public abstract boolean hasClientLoaded();

    @Inject(method = "handleAttack", at = @At("HEAD"))
    private void testkit$attackProbe(ServerboundAttackPacket packet, CallbackInfo ci) {
        if (!HxBridge.onServerThread()) {
            return;
        }
        Entity target = player.level().getEntityOrPart(packet.entityId());
        String why = "none";
        double dist = Double.NaN;
        if (!hasClientLoaded()) {
            why = "notLoaded";
        } else if (player.isSpectator()) {
            why = "spectator";
        } else if (target == null || !player.level().getWorldBorder().isWithinBounds(target.blockPosition())) {
            why = "noTarget";
        } else {
            AABB box = target.getBoundingBox();
            dist = Math.sqrt(box.distanceToSqr(player.getEyePosition()));
            if (!player.isWithinAttackRange(player.getMainHandItem(), box, 3.0D)) {
                why = "outOfRange";
            }
        }
        TESTKIT$LOG.info("[atkprobe] tick {} pos {} {} {} eyeDist {} drop {}", player.level().getServer().getTickCount(),
                String.format(java.util.Locale.ROOT, "%.3f", player.getX()), String.format(java.util.Locale.ROOT, "%.3f", player.getY()),
                String.format(java.util.Locale.ROOT, "%.3f", player.getZ()), String.format(java.util.Locale.ROOT, "%.3f", dist), why);
    }
}

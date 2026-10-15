package dev.testkit.harness;

import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * Hypixel's packet ORDER on a teleport, for the integrated server: the item he lands on is picked up in the same
 * server step that moves him, so its {@code ClientboundTakeItemEntityPacket} goes out right behind the position packet
 * and the client handles both before its next tick sees the landing.
 *
 * <p>killer560's Mod Only Test log of a real Hypixel run (2026-10-14 17:22:43, Red Blue): "await secret 1: item picked
 * up by you" is logged while the etherwarp that landed him on it is still the active node, before "Etherwarp: landed".
 * A vanilla integrated server picks the item up in a later server tick instead, a frame or a tick behind the position
 * packet, so a test that leaves it to vanilla (or to an older mod jar's sim) is measuring a different order.
 *
 * <p>Fed by {@code dev.testkit.mixin.MixinServerPlayerTeleport} at RETURN of {@code ServerPlayer.teleportTo}. Off unless
 * a scenario sets {@link #mode}; {@link #reset} turns it off again.
 */
public final class HypixelOrder {

    public enum Mode {
        /** Nothing: vanilla's own timing. */
        OFF,
        /** He picks up every item in vanilla's pickup box (his box inflated 1.0, 0.5, 1.0) the moment he lands. */
        SELF,
        /** Someone else does: a take packet naming another collector, then the item is removed - a teammate's pickup. */
        OTHER
    }

    public static volatile Mode mode = Mode.OFF;
    /** Items taken by the hook since the last {@link #reset}. */
    public static volatile int taken;

    private HypixelOrder() {
    }

    public static void reset() {
        mode = Mode.OFF;
        taken = 0;
    }

    /** Server thread, right after a teleport of {@code sp} returned. */
    public static void afterTeleport(ServerPlayer sp) {
        Mode m = mode;
        if (m == Mode.OFF || sp == null || !sp.isAlive()) {
            return;
        }
        ServerLevel level = (ServerLevel) sp.level();
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, sp.getBoundingBox().inflate(1.0, 0.5, 1.0),
                ItemEntity::isAlive)) {
            if (m == Mode.SELF) {
                item.playerTouch(sp);
                if (!item.isAlive()) {
                    taken++;
                }
            } else {
                // An id that is not his: the client's await must read it as somebody else's pickup.
                sp.connection.send(new ClientboundTakeItemEntityPacket(item.getId(), sp.getId() + 100_000, 1));
                item.discard();
                taken++;
            }
        }
    }
}

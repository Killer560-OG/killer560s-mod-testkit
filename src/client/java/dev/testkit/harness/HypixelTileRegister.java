package dev.testkit.harness;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Ice Fill's tile registration arriving AFTER the landing, as on Hypixel (killer560, 2026-10-15: "it needs to wait for the
 * first block to register for each section before it can move on"). The sim's server marks a tile packed ice in the same
 * step as the teleport that lands him on it, so the client sees both together and an auto that hops the tick it stands
 * on a tile never shows the difference. With {@link #delayTicks} set, every ice-to-packed-ice block update the CLIENT
 * receives is held that many client ticks before it is applied - only what the client sees; the server's judge is
 * untouched. Works on any mod jar (fed by {@code MixinClientPacketListener}); off unless a scenario turns it on.
 */
public final class HypixelTileRegister {

    /** Client ticks a packed-ice update is held; 0 = off. */
    public static volatile int delayTicks = 0;
    /** Updates held since the last {@link #reset}. */
    public static volatile int held = 0;

    private record Pending(BlockPos pos, BlockState state, long due) {
    }

    private static final ArrayDeque<Pending> PENDING = new ArrayDeque<>();
    private static long tick = 0;
    private static boolean registered = false;

    private HypixelTileRegister() {
    }

    public static void reset() {
        delayTicks = 0;
        held = 0;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(HypixelTileRegister::flushAll);
    }

    /** Render thread: true when the update was taken and will be applied later. */
    public static boolean hold(BlockPos pos, BlockState state) {
        int d = delayTicks;
        Minecraft mc = Minecraft.getInstance();
        if (d <= 0 || mc.level == null || !state.is(Blocks.PACKED_ICE) || !mc.level.getBlockState(pos).is(Blocks.ICE)) {
            return false;
        }
        if (!registered) {
            registered = true;
            net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(c -> release());
        }
        PENDING.add(new Pending(pos.immutable(), state, tick + d));
        held++;
        return true;
    }

    private static void release() {
        tick++;
        Minecraft mc = Minecraft.getInstance();
        for (Iterator<Pending> it = PENDING.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.due() > tick) {
                continue;
            }
            it.remove();
            if (mc.level != null) {
                mc.level.setServerVerifiedBlockState(p.pos(), p.state(), 19);
            }
        }
    }

    private static void flushAll() {
        Minecraft mc = Minecraft.getInstance();
        for (Pending p : PENDING) {
            if (mc.level != null) {
                mc.level.setServerVerifiedBlockState(p.pos(), p.state(), 19);
            }
        }
        PENDING.clear();
    }
}

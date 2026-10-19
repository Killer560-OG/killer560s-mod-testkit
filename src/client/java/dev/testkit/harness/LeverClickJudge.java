package dev.testkit.harness;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Server half of the 1018-waterboard lever cases, on the sim's integrated server (mixin
 * {@code MixinServerGamePacketListenerUse}, server thread only).
 *
 * <ul>
 *   <li><b>Look judge</b> ({@link #judgeLook}): a lever click whose look the server last HEARD (the server player's
 *       rotation, which only movement packets set) does not meet the lever from the server player's eye is refused - not
 *       handled, its sequence acknowledged. That is GrimAC's RotationPlace ("and dropped", mod docs/LESSONS-AUTOMATION.md)
 *       and what a server that checks a click against the reported look does; a vanilla server (the sim's) does not check,
 *       so without this every such click would flip the lever and nothing would show.</li>
 *   <li><b>Dropped warp</b> ({@link #dropUseAfterLevers}): once that many lever clicks have reached the server (refused
 *       or not, so a broken jar and a fixed one drop the same warp), the next
 *       item use is dropped (no ability, sequence acknowledged): a warp that gets no landing, as two of Auto Water's warps
 *       did a tick after a lever click in killer560's Hypixel run of 2026-10-18 (19:25:14 and 19:25:17).</li>
 *   <li><b>Flips</b>: every lever click the server handled, with the lever's powered state before and after.</li>
 * </ul>
 */
public final class LeverClickJudge {

    public static volatile boolean enabled;
    public static volatile boolean judgeLook;
    /** Lever clicks handled before the next use is dropped; negative: never. One drop per arming. */
    public static volatile int dropUseAfterLevers = -1;

    public record Flip(BlockPos pos, boolean before, boolean after, int serverTick) {
    }

    public static final List<Flip> FLIPS = new ArrayList<>();
    public static final List<String> REFUSED = new ArrayList<>();
    public static volatile int leverClicksHandled;
    /** Lever clicks that reached the server, refused or not (what {@link #dropUseAfterLevers} counts). */
    public static volatile int leverClicksSeen;
    public static volatile int usesDropped;
    public static volatile int droppedAtServerTick = -1;

    private static BlockPos pendingPos;
    private static Boolean pendingBefore;
    private static boolean dropDone;

    private LeverClickJudge() {
    }

    public static synchronized void reset() {
        enabled = false;
        judgeLook = false;
        dropUseAfterLevers = -1;
        FLIPS.clear();
        REFUSED.clear();
        leverClicksHandled = 0;
        leverClicksSeen = 0;
        usesDropped = 0;
        droppedAtServerTick = -1;
        pendingPos = null;
        pendingBefore = null;
        dropDone = false;
    }

    /** Server thread, HEAD of handleUseItemOn. True when the click must be refused. */
    public static synchronized boolean onClickHead(ServerPlayer sp, ServerboundUseItemOnPacket packet) {
        pendingPos = null;
        pendingBefore = null;
        if (!enabled || sp == null) {
            return false;
        }
        BlockPos pos = packet.getHitResult().getBlockPos();
        BlockState state = sp.level().getBlockState(pos);
        if (!(state.getBlock() instanceof LeverBlock)) {
            return false;
        }
        leverClicksSeen++;
        if (judgeLook && !lookMeets(sp, pos, state)) {
            String line = String.format(Locale.US, "lever click at %s refused: heard look %.2f/%.2f from eye (%.2f, %.2f, "
                            + "%.2f) does not meet it (server tick %d)", pos.toShortString(),
                    net.minecraft.util.Mth.wrapDegrees(sp.getYRot()), sp.getXRot(), sp.getX(), sp.getEyeY(), sp.getZ(),
                    sp.level().getServer().getTickCount());
            REFUSED.add(line);
            System.out.println("[LeverClickJudge] " + line);
            return true;
        }
        pendingPos = pos.immutable();
        pendingBefore = state.getValue(LeverBlock.POWERED);
        return false;
    }

    /** Server thread, every RETURN of handleUseItemOn (not reached for a refused click). */
    public static synchronized void onClickReturn(ServerPlayer sp) {
        if (!enabled || sp == null || pendingPos == null) {
            return;
        }
        BlockState state = sp.level().getBlockState(pendingPos);
        boolean after = state.getBlock() instanceof LeverBlock && state.getValue(LeverBlock.POWERED);
        FLIPS.add(new Flip(pendingPos, pendingBefore, after, sp.level().getServer().getTickCount()));
        leverClicksHandled++;
        pendingPos = null;
        pendingBefore = null;
    }

    /** Server thread, HEAD of handleUseItem. True when the use must be dropped. */
    public static synchronized boolean onUse(ServerPlayer sp) {
        if (!enabled || sp == null || dropDone || dropUseAfterLevers < 0 || leverClicksSeen < dropUseAfterLevers) {
            return false;
        }
        dropDone = true;
        usesDropped++;
        droppedAtServerTick = sp.level().getServer().getTickCount();
        System.out.println("[LeverClickJudge] use dropped after " + leverClicksSeen + " lever click(s) (server tick "
                + droppedAtServerTick + ") - a warp with no landing");
        return true;
    }

    /** The heard look from the server player's eye, along the lever's outline (its box when it has none), 6 blocks. */
    private static boolean lookMeets(ServerPlayer sp, BlockPos pos, BlockState state) {
        Vec3 eye = sp.getEyePosition();
        Vec3 end = eye.add(Vec3.directionFromRotation(sp.getXRot(), sp.getYRot()).scale(6.0));
        VoxelShape shape = state.getShape(sp.level(), pos);
        if (shape.isEmpty()) {
            return new AABB(pos).clip(eye, end).isPresent();
        }
        BlockHitResult hit = shape.clip(eye, end, pos);
        return hit != null;
    }
}

package dev.testkit.harness;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Hypixel's 1.8 server pairing a bare item use with the right click on a block before it, for the integrated server.
 *
 * <p>CraftBukkit 1.8 ({@code PlayerInteractManager.firedInteract/interactResult} and the face-255 branch of
 * {@code PlayerConnection.a(PacketPlayInBlockPlace)}): a right click on a block within 8 blocks stores its verdict. A bare
 * use then traces {@link #TRACE} blocks from the eye along the look the server last HEARD - the last movement packet's;
 * a 1.8 use carries no rotation of its own - and when that ray meets a block while a verdict is stored, the use is taken as
 * the click's second half: it fires nothing, replays the verdict and clears it. A ray that meets nothing is a right click in
 * the air: it fires, and the verdict stays stored.
 *
 * <p>killer560's Mod Only Test log of a real run (2026-10-14 17:22-17:29): six Auto Routes etherwarps a tick after a chest
 * or lever click with the AOTV in hand did nothing ("no landing after 61 ticks", no chat line), every retry 3 s later
 * landed, and along the warps' own looks the rays meet nothing within 4.5 (Red Blue #5 and #6 against the room capture) -
 * it was the look the chest was clicked with that the server traced. The verdict modeled here is the harsh one: any click
 * with an item in hand denied the item (an empty hand did not).
 *
 * <p>Fed by {@code dev.testkit.mixin.MixinServerGamePacketListenerUse} at HEAD of {@code handleUseItemOn} /
 * {@code handleUseItem} on the server thread - before vanilla snaps the player to the use packet's rotation, so the
 * player's rotation there IS the heard look. A use taken as a denied click's second half is acknowledged and otherwise
 * ignored. A use that pairs with a click from the same client tick (no tick-end packet between them) is a vanilla right
 * click's two halves ( the block half fired whatever it fires): counted in {@link #pairedSameTick}, not {@link #dropped}. Off unless a scenario sets
 * {@link #enabled}; {@link #reset} turns it off again.
 */
public final class HypixelUseAfterClick {

    /** CraftBukkit 1.8's survival trace for a bare use. */
    public static final double TRACE = 4.5;
    /** 1.8's sneaking eye height (1.62 - 0.08), which a 1.8 server traces from. */
    public static final double SNEAK_EYE = 1.54;
    /** A 1.8 server stores a block click only within this distance (squared 64, to the block's center). */
    public static final double CLICK_STORE_RANGE = 8.0;

    public static volatile boolean enabled;
    /** Block clicks stored, since the last {@link #reset}. */
    public static volatile int clicks;
    /** Uses taken as a stored (denied) click's second half and ignored. */
    public static volatile int dropped;
    /** Uses that paired with a click from their own server tick (a vanilla right click's two halves). */
    public static volatile int pairedSameTick;
    /** Uses whose ray met nothing while a click was stored (they fired). */
    public static volatile int airUses;
    /** The last ignored use, for the scenario's output. */
    public static volatile String lastDrop = "-";

    private static boolean stored;
    private static boolean denied;
    private static int storedTick;
    private static BlockPos storedPos;
    /** A client tick ended since the stored click: a use now is not that click's own second half. */
    private static boolean movedSinceClick;

    private HypixelUseAfterClick() {
    }

    public static void reset() {
        enabled = false;
        clicks = 0;
        dropped = 0;
        pairedSameTick = 0;
        airUses = 0;
        lastDrop = "-";
        stored = false;
        denied = false;
        storedPos = null;
        forgetModModel();
    }

    /**
     * The mod's own model of what the server holds ({@code util/RightClick}, mod ar-nexttick-1015 on) and its sim's stored
     * clicks are forgotten with the hook's: a scenario's earlier clicks must not decide a later control. Absent on older jars.
     */
    private static void forgetModModel() {
        try {
            Class.forName("com.killer560.hub.util.RightClick").getMethod("reset").invoke(null);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // an older jar: nothing to forget
        }
        try {
            Class.forName("com.killer560.hub.roomsim.SimAbilities").getMethod("forgetStoredClicks").invoke(null);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // an older jar's sim has no stored clicks
        }
    }

    /** Server thread: a block click arrived. */
    public static void onUseItemOn(ServerPlayer sp, ServerboundUseItemOnPacket packet) {
        if (!enabled || sp == null) {
            return;
        }
        BlockPos pos = packet.getHitResult().getBlockPos();
        // A 1.8 server stores only a click within 8 blocks of a solid block; the distance is not held to here (harsher: the
        // primers click blocks out of THIS server's reach, which a moving player can carry past 8 by the time it arrives).
        if (sp.level().getBlockState(pos).isAir()) {
            return;
        }
        clicks++;
        stored = true;
        denied = !sp.getItemInHand(packet.getHand()).isEmpty();
        storedTick = sp.level().getServer().getTickCount();
        storedPos = pos.immutable();
        movedSinceClick = false;
    }

    /** Server thread: a client tick ended (its tick-end packet arrived). */
    public static void onMove() {
        movedSinceClick = true;
    }

    /** Server thread: a use arrived (the player still has the heard look). True when it must be ignored. */
    public static boolean dropUse(ServerPlayer sp, ServerboundUseItemPacket packet) {
        if (!enabled || sp == null || !stored) {
            return false;
        }
        Vec3 eye = new Vec3(sp.getX(), sp.getY() + (sp.isShiftKeyDown() ? SNEAK_EYE : 1.62), sp.getZ());
        Vec3 look = Vec3.directionFromRotation(sp.getXRot(), sp.getYRot());
        BlockHitResult ray = sp.level().clip(new ClipContext(eye, eye.add(look.scale(TRACE)), ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, sp));
        if (ray == null || ray.getType() != HitResult.Type.BLOCK) {
            airUses++;
            return false;
        }
        stored = false;
        int gap = sp.level().getServer().getTickCount() - storedTick;
        if (!movedSinceClick) {
            pairedSameTick++;
            return denied;
        }
        if (!denied) {
            return false;
        }
        dropped++;
        lastDrop = String.format(java.util.Locale.US, "use seq %d rot %.2f/%.2f: heard look %.2f/%.2f meets %s at %s, click "
                        + "at %s %d server tick(s) earlier", packet.getSequence(), packet.getYRot(), packet.getXRot(),
                net.minecraft.util.Mth.wrapDegrees(sp.getYRot()), sp.getXRot(),
                sp.level().getBlockState(ray.getBlockPos()).getBlock().getName().getString(),
                ray.getBlockPos().toShortString(), storedPos == null ? "?" : storedPos.toShortString(), gap);
        System.out.println("[HypixelUseAfterClick] use ignored - taken as the block click's second half: " + lastDrop);
        return true;
    }
}

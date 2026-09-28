package dev.testkit.harness;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Counts what the client actually SENDS, grouped by client tick.
 *
 * <p>Why this exists: "the anticheat did not object" is the weakest possible result about an automation
 * module. It says nothing about whether the module sent one interaction per tick or five, how far away the
 * block was, or whether a swing went with it - and those are the numbers a server-side check is built
 * from. A scenario that reads them can state what the module did in the units an anticheat measures, so a
 * clean verdict is attached to a described behaviour instead of standing in for one.
 *
 * <p>The tap is {@code ClientCommonPacketListenerImpl#send}, the one method every outbound packet in the
 * game and in every loaded mod passes through - so this sees a mod's automation whether or not the mod
 * cooperates. Signature verified with javap against the 26.1.2 mapped jar: a mixin written against a
 * guessed name fails, and a counter that silently counts nothing reports zeroes that read as findings.
 *
 * <p>Off by default. An always-live packet tap changes the timing of every other scenario, so a test that
 * wants numbers turns it on and back off.
 */
public final class PacketWatch {

    private static volatile boolean active;

    private static int breaksThisTick;
    private static int swingsThisTick;
    private static int movesThisTick;
    private static int usesThisTick;

    private static int ticksObserved;
    private static int totalBreaks;
    private static int totalSwings;
    private static int totalHotbarSwaps;
    private static int ticksWithABreak;
    private static int maxBreaksOnOneTick;
    private static int tickOfMaxBreaks;
    private static double maxBoxReach;
    private static double maxCentreReach;
    private static int breaksWithoutSwing;
    private static int breaksAfterMove;
    private static int totalUses;
    private static int maxUsesOnOneTick;
    private static int usesAfterMove;
    private static int moveCount;
    private static int collisionTicks;

    private static boolean sawRotation;
    private static float lastYaw;
    private static float maxYawStep;
    private static float minPitchSent = Float.NaN;
    private static float maxPitchSent = Float.NaN;
    private static float maxAbsYawSent;

    private PacketWatch() {
    }

    public static void start() {
        reset();
        active = true;
    }

    public static void stop() {
        active = false;
    }

    public static synchronized void reset() {
        breaksThisTick = 0;
        swingsThisTick = 0;
        ticksObserved = 0;
        totalBreaks = 0;
        totalSwings = 0;
        totalHotbarSwaps = 0;
        ticksWithABreak = 0;
        maxBreaksOnOneTick = 0;
        tickOfMaxBreaks = -1;
        maxBoxReach = 0;
        maxCentreReach = 0;
        breaksWithoutSwing = 0;
        breaksAfterMove = 0;
        movesThisTick = 0;
        moveCount = 0;
        collisionTicks = 0;
        usesThisTick = 0;
        totalUses = 0;
        maxUsesOnOneTick = 0;
        usesAfterMove = 0;
        sawRotation = false;
        maxYawStep = 0;
        minPitchSent = Float.NaN;
        maxPitchSent = Float.NaN;
        maxAbsYawSent = 0;
    }

    /** Called from a {@code Minecraft#tick} hook: closes the tick just counted. */
    public static synchronized void endTick() {
        if (!active) {
            return;
        }
        ticksObserved++;
        if (breaksThisTick > 0) {
            ticksWithABreak++;
            if (breaksThisTick > maxBreaksOnOneTick) {
                maxBreaksOnOneTick = breaksThisTick;
                tickOfMaxBreaks = ticksObserved;
            }
            // A break with no swing on the same tick is the shape of automation that forgot to animate.
            // Counted rather than judged: some servers check it, some do not.
            if (swingsThisTick == 0) {
                breaksWithoutSwing += breaksThisTick;
            }
        }
        if (usesThisTick > maxUsesOnOneTick) {
            maxUsesOnOneTick = usesThisTick;
        }
        breaksThisTick = 0;
        swingsThisTick = 0;
        movesThisTick = 0;
        usesThisTick = 0;
    }

    public static synchronized void record(Packet<?> packet) {
        if (!active) {
            return;
        }
        if (packet instanceof ServerboundPlayerActionPacket action) {
            if (action.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
                breaksThisTick++;
                totalBreaks++;
                // ORDER WITHIN THE TICK, which is a check in its own right. A vanilla client decides what
                // to dig before it reports where it is, so the dig goes out BEFORE the movement packet.
                // Automation hung on an end-of-tick hook is after it, and that is not a sequence a vanilla
                // client produces - Grim calls the check "Post". Counted here so the ordering can be
                // measured and re-measured rather than deduced from where the hook happens to live.
                if (movesThisTick > 0) {
                    breaksAfterMove++;
                }
                measureReach(action.getPos());
            }
        } else if (packet instanceof ServerboundUseItemOnPacket) {
            // Right-click-on-block, which is what every one of this mod's auras actually sends: levers,
            // chests, wither doors, terminals. The ordering question is identical to digging - a vanilla
            // client decides what to use before it reports where it is.
            usesThisTick++;
            totalUses++;
            if (movesThisTick > 0) {
                usesAfterMove++;
            }
        } else if (packet instanceof ServerboundSwingPacket) {
            swingsThisTick++;
            totalSwings++;
        } else if (packet instanceof ServerboundSetCarriedItemPacket) {
            totalHotbarSwaps++;
        } else if (packet instanceof ServerboundMovePlayerPacket move) {
            movesThisTick++;
            moveCount++;
            // DID HE RUN INTO THE WALL. The client reports its own horizontal collision in the movement packet,
            // so this is not inferred from speed or position - it is the game saying it was stopped by
            // something. killer560 (2026-09-27): "the only breaker command thing I care about is that i never
            // am able to run into a wall while it is breaking." This is that sentence as a number.
            if (move.horizontalCollision()) {
                collisionTicks++;
            }
            if (!move.hasRotation()) {
                return;
            }
            float yaw = move.getYRot(0f);
            float pitch = move.getXRot(0f);
            if (sawRotation) {
                maxYawStep = Math.max(maxYawStep, Math.abs(yaw - lastYaw));
            }
            lastYaw = yaw;
            sawRotation = true;
            maxAbsYawSent = Math.max(maxAbsYawSent, Math.abs(yaw));
            minPitchSent = Float.isNaN(minPitchSent) ? pitch : Math.min(minPitchSent, pitch);
            maxPitchSent = Float.isNaN(maxPitchSent) ? pitch : Math.max(maxPitchSent, pitch);
        }
    }

    /**
     * Both distances, because they are not the same number and the difference has already mattered once: a
     * reach measured to the block's CENTRE reads up to half a block longer than the one a server computes
     * to the nearest point of its box, and a module tuned against the centre figure reads as out of reach
     * when it is not.
     */
    private static void measureReach(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        Vec3 eye = mc.player.getEyePosition();
        maxCentreReach = Math.max(maxCentreReach, eye.distanceTo(Vec3.atCenterOf(pos)));
        AABB box = new AABB(pos);
        double dx = Math.max(0, Math.max(box.minX - eye.x, eye.x - box.maxX));
        double dy = Math.max(0, Math.max(box.minY - eye.y, eye.y - box.maxY));
        double dz = Math.max(0, Math.max(box.minZ - eye.z, eye.z - box.maxZ));
        maxBoxReach = Math.max(maxBoxReach, Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    public static synchronized int maxBreaksOnOneTick() {
        return maxBreaksOnOneTick;
    }

    public static synchronized int totalBreaks() {
        return totalBreaks;
    }

    public static synchronized int totalUses() {
        return totalUses;
    }

    public static synchronized int maxUsesOnOneTick() {
        return maxUsesOnOneTick;
    }

    /** Uses that went out AFTER that tick's movement packet - the shape Grim's Post check looks for. */
    public static synchronized int usesAfterMove() {
        return usesAfterMove;
    }

    public static synchronized int breaksWithoutSwing() {
        return breaksWithoutSwing;
    }

    /** Breaks that went out AFTER that tick's movement packet - the shape Grim's Post check looks for. */
    public static synchronized int breaksAfterMove() {
        return breaksAfterMove;
    }

    /** Ticks on which the client told the server it was stopped by something horizontally. */
    public static synchronized int collisionTicks() {
        return collisionTicks;
    }

    public static synchronized int moveCount() {
        return moveCount;
    }

    public static synchronized double maxBoxReach() {
        return maxBoxReach;
    }

    public static synchronized float maxAbsYawSent() {
        return maxAbsYawSent;
    }

    public static synchronized float maxYawStep() {
        return maxYawStep;
    }

    /** True if a pitch outside the legal range ever went out - malformed, and trivially detectable. */
    public static synchronized boolean sentIllegalPitch() {
        return (!Float.isNaN(minPitchSent) && minPitchSent < -90.0001f)
                || (!Float.isNaN(maxPitchSent) && maxPitchSent > 90.0001f);
    }

    public static synchronized String summary() {
        return String.format(Locale.ROOT,
                "%d ticks: %d break-starts over %d ticks (max %d on one tick, at tick %d), %d block-uses "
                        + "(max %d on one tick, %d after that tick's movement packet), %d swings, "
                        + "%d hotbar swaps, %d breaks with no swing that tick, collided with something on "
                        + "%d of %d movement packets, %d breaks sent AFTER that "
                        + "tick's movement packet, furthest reach %.2f to box "
                        + "/ %.2f to centre; rotation sent: max abs yaw %.1f, biggest one-tick yaw step "
                        + "%.1f, pitch %.1f..%.1f%s",
                ticksObserved, totalBreaks, ticksWithABreak, maxBreaksOnOneTick, tickOfMaxBreaks,
                totalUses, maxUsesOnOneTick, usesAfterMove, totalSwings, totalHotbarSwaps, breaksWithoutSwing, collisionTicks, moveCount,
                breaksAfterMove, maxBoxReach, maxCentreReach,
                maxAbsYawSent, maxYawStep,
                Float.isNaN(minPitchSent) ? 0f : minPitchSent,
                Float.isNaN(maxPitchSent) ? 0f : maxPitchSent,
                sentIllegalPitch() ? "  <-- ILLEGAL PITCH" : "");
    }
}

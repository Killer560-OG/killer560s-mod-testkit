package dev.testkit.harness;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
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
    private static int clicksThisTick;

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
    private static int totalContainerClicks;
    private static int maxClicksOnOneTick;
    private static int clicksAfterMove;
    private static int moveCount;
    private static int collisionTicks;

    private static boolean sawRotation;
    private static float lastYaw;
    private static float maxYawStep;
    private static float minPitchSent = Float.NaN;
    private static float maxPitchSent = Float.NaN;
    private static float maxAbsYawSent;
    private static int itemUses;
    private static int entityInteracts;
    private static double maxEntityReach;
    private static String farthestEntity = "none";
    private static float maxUseRotationJump;
    private static String biggestJump = "none";
    /** Every block a START_DESTROY_BLOCK named, in send order (so a scenario can say WHICH blocks were dug). */
    private static final List<BlockPos> digPositions = new ArrayList<>();
    /**
     * From the FIRST dig on: the dig, then each movement packet with what it claimed (y, onGround) and whether the
     * client's own world still held the dug block when it went out. A packet claiming onGround while the client still
     * stands on a block the anticheat already counts as broken is the GroundSpoof shape (breaker-floor, 2026-10-06).
     */
    private static final List<String> digTimeline = new ArrayList<>();
    private static BlockPos firstDig;
    /** Digs whose eye-to-centre line hit a DIFFERENT block first (client's view of the world at send time). */
    private static int occludedDigs;
    /** Largest angle, degrees, between where the player was looking and the block a dig named. */
    private static double maxDigLookAngle;

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
        clicksThisTick = 0;
        totalContainerClicks = 0;
        maxClicksOnOneTick = 0;
        clicksAfterMove = 0;
        sawRotation = false;
        maxYawStep = 0;
        minPitchSent = Float.NaN;
        maxPitchSent = Float.NaN;
        maxAbsYawSent = 0;
        itemUses = 0;
        entityInteracts = 0;
        maxEntityReach = 0;
        farthestEntity = "none";
        maxUseRotationJump = 0;
        biggestJump = "none";
        digPositions.clear();
        digTimeline.clear();
        firstDig = null;
        occludedDigs = 0;
        maxDigLookAngle = 0;
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
        if (clicksThisTick > maxClicksOnOneTick) {
            maxClicksOnOneTick = clicksThisTick;
        }
        breaksThisTick = 0;
        swingsThisTick = 0;
        movesThisTick = 0;
        usesThisTick = 0;
        clicksThisTick = 0;
    }

    public static synchronized void record(Packet<?> packet) {
        if (!active) {
            return;
        }
        // THE TICK BOUNDARY IS VANILLA'S OWN MARKER.
        //
        // A vanilla client sends exactly one client_tick_end at the end of each tick (26.1.2), so the boundary
        // needs no mixin and no guess about the order two injections at the same point happen to run in. This
        // replaces a Minecraft#tick hook that was wrong in the one case it existed to measure: at TAIL it sat
        // BEFORE Fabric's END_CLIENT_TICK handlers, filed a module's packets under the following tick, and
        // reported zero ordering problems while the anticheat reported 808. Credit to upstream's PacketTrace
        // for the marker; its per-tick sequence is the authoritative record, and this class only survives for
        // the things a name-only trace cannot give: reach measured to the block box, and collisions.
        if (packet instanceof ServerboundClientTickEndPacket) {
            endTick();
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
                measureDig(action.getPos());
                if (firstDig == null) {
                    firstDig = action.getPos().immutable();
                    digTimeline.add("t" + ticksObserved + " dig " + action.getDirection() + clientState(firstDig));
                }
            }
        } else if (packet instanceof ServerboundUseItemPacket use) {
            // A use-item packet carries its OWN yaw and pitch, which is how this mod's puzzle solvers aim
            // without turning the camera or sending a movement packet. That makes a number worth watching:
            // how far the rotation claimed inside the use is from the rotation the client last actually
            // reported moving at. A hand cannot produce a large gap - you have to turn to aim, and turning
            // sends movement packets on the way.
            itemUses++;
            usesThisTick++;
            totalUses++;
            if (movesThisTick > 0) {
                usesAfterMove++;
            }
            if (sawRotation) {
                float jump = Math.abs(use.getYRot() - lastYaw);
                if (jump > maxUseRotationJump) {
                    maxUseRotationJump = jump;
                    biggestJump = String.format(Locale.ROOT,
                            "use claimed yaw %.1f / pitch %.1f while the last movement packet said yaw %.1f",
                            use.getYRot(), use.getXRot(), lastYaw);
                }
            }
        } else if (packet instanceof ServerboundInteractPacket interact) {
            // Clicking an ENTITY, which is a different limit from clicking a block: vanilla allows 4.5 blocks
            // to a block's box but only 3.0 to an entity's. Terminal Aura, Arrow Align and Goldor Triggerbot
            // all interact with entities, and several of them default above 3.0 - so this measures the
            // distance the server would check, eye to the entity's own bounding box.
            entityInteracts++;
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.player != null) {
                var target = mc.level.getEntity(interact.entityId());
                if (target != null) {
                    Vec3 eye = mc.player.getEyePosition();
                    AABB box = target.getBoundingBox();
                    double dx = Math.max(0, Math.max(box.minX - eye.x, eye.x - box.maxX));
                    double dy = Math.max(0, Math.max(box.minY - eye.y, eye.y - box.maxY));
                    double dz = Math.max(0, Math.max(box.minZ - eye.z, eye.z - box.maxZ));
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (d > maxEntityReach) {
                        maxEntityReach = d;
                        farthestEntity = String.format(Locale.ROOT, "%s at %.2f blocks",
                                target.getType().toShortString(), d);
                    }
                }
            }
        } else if (packet instanceof ServerboundContainerClickPacket) {
            // Inventory and menu automation - the sorter, the seller, Croesus, the experiment table. A third
            // packet class, and worth measuring separately: a server validates a window click against its own
            // copy of the menu, so it is not obvious in advance whether the ordering check applies here at all.
            clicksThisTick++;
            totalContainerClicks++;
            if (movesThisTick > 0) {
                clicksAfterMove++;
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
            if (firstDig != null && digTimeline.size() < 12) {
                digTimeline.add(String.format(Locale.ROOT, "t%d move%s ground=%b%s", ticksObserved,
                        move.hasPosition() ? String.format(Locale.ROOT, " y=%.4f", move.getY(0)) : " (no pos)",
                        move.isOnGround(), clientState(firstDig)));
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

    /**
     * What a dig looks like from the player's own eyes at the moment it is sent: whether anything else stands
     * between the eye and the block (a server checking line of sight would see a dig through a wall), and how far
     * off the player's view the block is (a dig behind or beside him while looking ahead is not something a hand
     * does). Neither is something GrimAC 2.3.74 is known to object to; both are things another anticheat could.
     */
    private static void measureDig(BlockPos pos) {
        digPositions.add(pos.immutable());
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        Vec3 eye = mc.player.getEyePosition();
        Vec3 centre = Vec3.atCenterOf(pos);
        Vec3 to = centre.subtract(eye).normalize();
        Vec3 look = mc.player.getViewVector(1f).normalize();
        double cos = Math.max(-1.0, Math.min(1.0, look.dot(to)));
        maxDigLookAngle = Math.max(maxDigLookAngle, Math.toDegrees(Math.acos(cos)));
        HitResult hit = mc.level.clip(new ClipContext(eye, centre, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, mc.player));
        if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK && !b.getBlockPos().equals(pos)) {
            occludedDigs++;
        }
    }

    private static String clientState(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return "";
        }
        return mc.level.getBlockState(pos).isAir() ? " [client: air]" : " [client: block]";
    }

    /** The first dig and the movement packets after it, see {@link #digTimeline}. */
    public static synchronized List<String> digTimeline() {
        return new ArrayList<>(digTimeline);
    }

    /** Every block a dig named since {@link #start}, in order. */
    public static synchronized List<BlockPos> digPositions() {
        return new ArrayList<>(digPositions);
    }

    public static synchronized int occludedDigs() {
        return occludedDigs;
    }

    public static synchronized double maxDigLookAngle() {
        return maxDigLookAngle;
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

    /** Use-item packets, which carry their own aim. */
    public static synchronized int itemUses() {
        return itemUses;
    }

    public static synchronized int entityInteracts() {
        return entityInteracts;
    }

    /** Furthest entity interaction, eye to the entity's box. Vanilla's own limit is 3.0. */
    public static synchronized double maxEntityReach() {
        return maxEntityReach;
    }

    public static synchronized String farthestEntity() {
        return farthestEntity;
    }

    /** The largest gap between a use packet's claimed yaw and the last yaw actually reported moving at. */
    public static synchronized float maxUseRotationJump() {
        return maxUseRotationJump;
    }

    public static synchronized String biggestRotationJump() {
        return biggestJump;
    }

    public static synchronized int maxUsesOnOneTick() {
        return maxUsesOnOneTick;
    }

    public static synchronized int totalContainerClicks() {
        return totalContainerClicks;
    }

    public static synchronized int maxClicksOnOneTick() {
        return maxClicksOnOneTick;
    }

    public static synchronized int clicksAfterMove() {
        return clicksAfterMove;
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
                        + "(max %d on one tick, %d after that tick's movement packet), %d container "
                        + "clicks (max %d on one tick, %d after that tick's movement packet), %d entity "
                        + "interact(s) reaching %.2f blocks, %d swings, "
                        + "%d hotbar swaps, %d breaks with no swing that tick, collided with something on "
                        + "%d of %d movement packets, %d breaks sent AFTER that "
                        + "tick's movement packet, furthest reach %.2f to box "
                        + "/ %.2f to centre; rotation sent: max abs yaw %.1f, biggest one-tick yaw step "
                        + "%.1f, pitch %.1f..%.1f%s",
                ticksObserved, totalBreaks, ticksWithABreak, maxBreaksOnOneTick, tickOfMaxBreaks,
                totalUses, maxUsesOnOneTick, usesAfterMove, totalContainerClicks, maxClicksOnOneTick,
                clicksAfterMove, entityInteracts, maxEntityReach, totalSwings, totalHotbarSwaps, breaksWithoutSwing, collisionTicks, moveCount,
                breaksAfterMove, maxBoxReach, maxCentreReach,
                maxAbsYawSent, maxYawStep,
                Float.isNaN(minPitchSent) ? 0f : minPitchSent,
                Float.isNaN(maxPitchSent) ? 0f : maxPitchSent,
                sentIllegalPitch() ? "  <-- ILLEGAL PITCH" : "");
    }
}

package dev.testkit.gametest;

import dev.testkit.harness.PacketWatch;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;

import java.util.Locale;

/**
 * The aim the auto puzzles send, tested directly at the primitive rather than through a puzzle room.
 *
 * <p>{@code AutoPuzzleUtil.useItemRotated} is how Auto Blaze, Auto Beams, Auto Ice Fill and Auto Reposition
 * all shoot: it sets the player's real yaw and pitch, fires a use, and restores them in a finally. The camera
 * never moves, and no movement packet carries the aim - {@code ServerboundUseItemPacket} carries its own yaw
 * and pitch, so the aim travels inside the use. That is what makes the trick work and it is also the thing
 * worth measuring, because it means a use can claim a rotation arbitrarily far from the last rotation the
 * client reported moving at, with nothing in between. A hand cannot do that: to aim somewhere you have to
 * turn, and turning sends movement packets on the way.
 *
 * <p>Driven at the primitive on purpose. Every feature that uses it is gated on a puzzle room the live map has
 * to recognise, which needs real dungeon data; the primitive is what produces the packet, and it is
 * {@code public static}, so this tests the real shipped code path with none of the scaffolding.
 *
 * <p>Two sizes, because the interesting question is whether there is a threshold: a small correction of the
 * kind a real shot needs, and a deliberate 150-degree snap.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class AimSnapTests implements FabricClientGameTest {

    private static final String MOD_ID = "killer560smod";
    private static final String UTIL = "com.killer560.hub.autopuzzles.AutoPuzzleUtil";

    private static final int SURFACE_Y = 151;
    private static final int BASE_X = -300;
    private static final int BASE_Z = -300;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        aimSnap(ctx, "63-aim-small-correction", 12f, -5f);
        aimSnap(ctx, "64-aim-150-degree-snap", 150f, -40f);
        aimControl(ctx);
        // Only meaningful once the control (65) shows the rotation itself is what draws the flag - narrows
        // which component. Pitch held at the player's real, restored-to value (0, since spawn pitch is 0 and
        // useItemRotated restores it every time) so "yaw only" really changes nothing about pitch, and yaw
        // held at 0 delta so "pitch only" really changes nothing about yaw.
        aimSnap(ctx, "67-aim-yaw-only-150", 150f, 0f);
        aimSnap(ctx, "68-aim-pitch-only-40", 0f, -40f);
    }

    /**
     * THE CONTROL. Scenarios 63/64 flagged exactly once per use at both a 12-degree correction and a
     * 150-degree snap - the same rate regardless of angle, which does not by itself say the ROTATION is what
     * drew the flag. This scenario is identical in every other respect (arena, item, timing, ten uses) but
     * calls {@code mc.gameMode.useItem} straight, with no rotation at all - the mod's real code path is not
     * involved here on purpose, only the same raw vanilla call {@code useItemRotated} itself makes.
     *
     * <p>If this is clean, 63/64's flag is attributable to the rotation. If this also flags, the use itself
     * draws it in this setup and 63/64 prove nothing about aim specifically - that must be reported as
     * uncertain, not quietly dropped.
     */
    private void aimControl(ClientGameTestContext ctx) {
        String name = "65-aim-control-no-rotation";
        if (Scenario.skip(name)) {
            return;
        }
        Scenario.runExpectingFlags(ctx, name,
                (server, scenario) -> TestMap.on(server)
                        .platform(BASE_X, SURFACE_Y - 1, BASE_Z, 12)
                        .walls(4)
                        .catchFloor(120)
                        .survival()
                        .clearInventory()
                        .give("snowball", 64)
                        .spawn(BASE_X + 0.5, BASE_Z + 0.5, 0f)
                        .build(),
                (server, scenario) -> {
                    scenario.assertDetectorWorks();
                    ctx.waitTicks(40);

                    PacketWatch.start();
                    int fired = 0;
                    for (int i = 0; i < 10; i++) {
                        // Identical footwork to aimSnap: face a known direction, report it by moving, then
                        // act - the only difference is what happens at the "act" step.
                        ctx.getInput().holdKey(options -> options.keyUp);
                        ctx.waitTicks(4);
                        ctx.getInput().releaseKey(options -> options.keyUp);
                        ctx.waitTicks(2);
                        ctx.runOnClient(mc -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND));
                        fired++;
                        ctx.waitTicks(8);
                    }
                    ctx.waitTicks(20);
                    PacketWatch.stop();

                    long post = scenario.flags().stream().filter(l -> l.contains("failed Post")).count();
                    long other = scenario.flags().size() - post;
                    scenario.log(PacketWatch.summary());
                    scenario.log(String.format(Locale.ROOT,
                            "RESULT control (no rotation, direct mc.gameMode.useItem): %d of 10 uses sent, "
                                    + "%d use-item packet(s) recorded -> anticheat said %d Post, %d other",
                            fired, PacketWatch.itemUses(), post, other));

                    if (PacketWatch.itemUses() == 0) {
                        throw new AssertionError("no use-item packets went out in " + fired + " attempted "
                                + "uses, so the control measured nothing and cannot be compared to 63/64.");
                    }
                    if (other + post > 0) {
                        scenario.log("FINDING: the anticheat objected to a plain, UNROTATED use in this "
                                + "identical setup. 63/64 do not by themselves show the rotation is what "
                                + "flags - the use itself may be what this check dislikes here.");
                        for (String flag : scenario.flags().stream().distinct().limit(3).toList()) {
                            scenario.log("    " + flag);
                        }
                    } else {
                        scenario.log("Control clean: an unrotated use in this identical setup drew nothing, "
                                + "so 63/64's flag is attributable to the rotation useItemRotated adds.");
                    }
                });
    }

    private void aimSnap(ClientGameTestContext ctx, String name, float yawDelta, float pitch) {
        if (Scenario.skip(name)) {
            return;
        }
        ModUnderTest.require(MOD_ID);
        Scenario.runExpectingFlags(ctx, name,
                (server, scenario) -> TestMap.on(server)
                        .platform(BASE_X, SURFACE_Y - 1, BASE_Z, 12)
                        .walls(4)
                        .catchFloor(120)
                        .survival()
                        .clearInventory()
                        // Something whose use actually sends a packet.
                        .give("snowball", 64)
                        .spawn(BASE_X + 0.5, BASE_Z + 0.5, 0f)
                        .build(),
                (server, scenario) -> {
                    scenario.assertDetectorWorks();
                    // The gate stands every actor down for a while after a world change; let that lapse, or
                    // the calls below are refused and the run measures nothing.
                    ctx.waitTicks(40);

                    PacketWatch.start();
                    int fired = 0;
                    for (int i = 0; i < 10; i++) {
                        // Face a known direction, report it by moving, then aim somewhere else entirely.
                        ctx.getInput().holdKey(options -> options.keyUp);
                        ctx.waitTicks(4);
                        ctx.getInput().releaseKey(options -> options.keyUp);
                        ctx.waitTicks(2);
                        if (rotatedUse(ctx, yawDelta, pitch)) {
                            fired++;
                        }
                        ctx.waitTicks(8);
                    }
                    ctx.waitTicks(20);
                    PacketWatch.stop();

                    long post = scenario.flags().stream().filter(l -> l.contains("failed Post")).count();
                    long other = scenario.flags().size() - post;
                    scenario.log(PacketWatch.summary());
                    scenario.log(String.format(Locale.ROOT,
                            "RESULT aim %+.0f degrees: %d of 10 shots accepted by the gate, %d use-item "
                                    + "packet(s) sent, biggest gap between the aim in a use and the last "
                                    + "reported movement rotation %.1f degrees -> anticheat said %d Post, "
                                    + "%d other",
                            yawDelta, fired, PacketWatch.itemUses(), PacketWatch.maxUseRotationJump(),
                            post, other));
                    scenario.log("  " + PacketWatch.biggestRotationJump());

                    if (PacketWatch.itemUses() == 0) {
                        throw new AssertionError("no use-item packets went out in " + fired + " accepted "
                                + "shots, so nothing about the aim was measured. If the gate refused every "
                                + "one, the world-change settle window may still have been open.");
                    }
                    if (PacketWatch.sentIllegalPitch()) {
                        scenario.log("FINDING: a pitch outside -90..90 went out, which is malformed and "
                                + "trivially detectable.");
                    }
                    if (other + post > 0) {
                        scenario.log("FINDING: the anticheat objected to the aim.");
                        for (String flag : scenario.flags().stream().distinct().limit(3).toList()) {
                            scenario.log("    " + flag);
                        }
                    } else {
                        scenario.log("This anticheat did not object. Note what that does and does not say: the "
                                + "gap above is a static, per-packet signal - it needs no timing analysis to "
                                + "spot, only a comparison a server may or may not make.");
                    }
                });
    }

    /** Calls the mod's real shooting primitive. Returns false when its own gate refused the tick. */
    private boolean rotatedUse(ClientGameTestContext ctx, float yawDelta, float pitch) {
        return ctx.computeOnClient(mc -> {
            try {
                Class<?> util = Class.forName(UTIL);
                var m = util.getMethod("useItemRotated", Minecraft.class, LocalPlayer.class,
                        float.class, float.class);
                float target = mc.player.getYRot() + yawDelta;
                return (Boolean) m.invoke(null, mc, mc.player, target, pitch);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not call " + UTIL + ".useItemRotated - it is the primitive "
                        + "every auto puzzle shoots through, so a rename here means this scenario is dead", e);
            }
        });
    }
}

package dev.testkit.gametest;

import dev.testkit.harness.PacketWatch;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.player.LocalPlayer;

import java.util.Locale;

/**
 * {@code AutoPuzzleUtil.rotateCamera} - the other rotation primitive, and a different shape of risk.
 *
 * <p>Where {@code useItemRotated} hides its aim inside a use packet and restores the player's rotation
 * immediately, this one sets the real yaw and pitch and <b>leaves them there</b>: no restore, no
 * interpolation, no per-tick cap. Auto Teleport Maze calls it while walking pad to pad, so the rotation
 * reaches the server through the ordinary movement packet - which is the channel an anticheat watches most
 * closely, because a real mouse cannot produce an arbitrary instant turn.
 *
 * <p>Two sizes and a control, for the same reason as the aim scenarios: a flag that appears at 150 degrees
 * and not at 8 says there is a threshold, and a flag that appears at both says the check is about something
 * other than the angle. The control turns the player by the same total amount in small per-tick steps, which
 * is what a mouse actually looks like, so a difference between them is attributable to the snap rather than to
 * the turning.
 */
public class CameraRotationTests implements FabricClientGameTest {

    private static final String MOD_ID = "killer560smod";
    private static final String UTIL = "com.killer560.hub.autopuzzles.AutoPuzzleUtil";

    private static final int SURFACE_Y = 151;
    private static final int BASE_X = -300;
    private static final int BASE_Z = -300;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        snap(ctx, "70-camera-snap-8", 8f, false);
        snap(ctx, "71-camera-snap-150", 150f, false);
        snap(ctx, "72-camera-smooth-control-150", 150f, true);
    }

    /**
     * @param smooth when true the same total turn is spread over ticks instead of applied at once - the control
     */
    private void snap(ClientGameTestContext ctx, String name, float degrees, boolean smooth) {
        if (Scenario.skip(name)) {
            return;
        }
        ModUnderTest.require(MOD_ID);
        Scenario.runExpectingFlags(ctx, name,
                (server, scenario) -> TestMap.on(server)
                        .platform(BASE_X, SURFACE_Y - 1, BASE_Z, 16)
                        .walls(4)
                        .catchFloor(120)
                        .survival()
                        .clearInventory()
                        .spawn(BASE_X + 0.5, BASE_Z + 0.5, 0f)
                        .build(),
                (server, scenario) -> {
                    scenario.assertDetectorWorks();
                    ctx.waitTicks(40);

                    PacketWatch.start();
                    // Walking throughout, so the rotation actually leaves in a movement packet. Standing
                    // still, a client sends rotation-only packets and the turn would be measured in a quieter
                    // channel than the one Auto Teleport Maze really uses.
                    ctx.getInput().holdKey(options -> options.keyUp);
                    for (int i = 0; i < 8; i++) {
                        float sign = (i % 2 == 0) ? 1f : -1f;
                        if (smooth) {
                            // A mouse-shaped turn: eight steps of the same total, one per tick.
                            for (int step = 0; step < 8; step++) {
                                turn(ctx, sign * degrees / 8f);
                                ctx.waitTicks(1);
                            }
                        } else {
                            turn(ctx, sign * degrees);
                        }
                        ctx.waitTicks(10);
                    }
                    ctx.getInput().releaseKey(options -> options.keyUp);
                    ctx.waitTicks(20);
                    PacketWatch.stop();

                    long post = scenario.flags().stream().filter(l -> l.contains("failed Post")).count();
                    long other = scenario.flags().size() - post;
                    scenario.log(PacketWatch.summary());
                    scenario.log(String.format(Locale.ROOT,
                            "RESULT %s turn of %.0f degrees: biggest one-tick yaw step the server saw %.1f, "
                                    + "max absolute yaw %.1f -> anticheat said %d Post, %d other",
                            smooth ? "SMOOTH control" : "instant", degrees, PacketWatch.maxYawStep(),
                            PacketWatch.maxAbsYawSent(), post, other));

                    if (PacketWatch.maxYawStep() < 0.5f) {
                        throw new AssertionError("the server never saw the rotation change (biggest step "
                                + PacketWatch.maxYawStep() + "), so nothing about turning was measured - the "
                                + "primitive may not have run, or the gate refused it");
                    }
                    if (PacketWatch.sentIllegalPitch()) {
                        scenario.log("FINDING: a pitch outside -90..90 was sent, which is malformed.");
                    }
                    if (other + post > 0) {
                        scenario.log("FINDING: the anticheat objected to this turn.");
                        for (String flag : scenario.flags().stream().distinct().limit(3).toList()) {
                            scenario.log("    " + flag);
                        }
                    }
                });
    }

    /** The mod's real primitive, relative to wherever the player is looking now. */
    private void turn(ClientGameTestContext ctx, float deltaYaw) {
        ctx.runOnClient(mc -> {
            try {
                var m = Class.forName(UTIL).getMethod("rotateCamera", LocalPlayer.class,
                        float.class, float.class);
                m.invoke(null, mc.player, mc.player.getYRot() + deltaYaw, mc.player.getXRot());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not call " + UTIL + ".rotateCamera - Auto Teleport Maze turns "
                        + "through it, so a rename here means this scenario is dead", e);
            }
        });
    }
}

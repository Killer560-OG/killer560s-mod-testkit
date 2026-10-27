package dev.testkit.gametest;

import dev.testkit.gametest.hx.Session;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

import static dev.testkit.gametest.Ap3RuntimeTests.FLOOR;
import static dev.testkit.gametest.Ap3RuntimeTests.LANE_X0;
import static dev.testkit.gametest.Ap3RuntimeTests.onClient;

/**
 * mod ap3-levers-1027, option (a) measured: a lever clicked with NO turn at all (killer560, 2026-10-27: "is there a way
 * to make it get levers without actually looking at them?"). RSA's Secret Aura does exactly this (it sends the block
 * click with a hit on the box and never rotates). Each case stands him 2 blocks from a lever at eye height, turns him
 * (server tp) so his look is {@code off} degrees of yaw away from the lever, and sends one block click on the lever's
 * face nearest the eye - the packet a no-turn aura sends - through the game mode, so the lever really toggles. Recorded,
 * not judged (testExpectingFlags): the report's flag lines are the measurement. 0 degrees is the control.
 */
final class Ap3Levers1027Cases {

    private Ap3Levers1027Cases() {
    }

    static void register(Session s) {
        for (int off : new int[]{0, 10, 30, 60, 90, 180}) {
            s.testExpectingFlags("1027-ap3-lever-noturn-" + off, c -> noTurn(c, off));
        }
    }

    private static void noTurn(Session c, int off) {
        ClientGameTestContext ctx = c.ctx();
        double z = Ap3RuntimeTests.lane();
        int zc = (int) Math.floor(z);
        // A stone post 2 blocks east of him with a lever on its west face at eye height (y FLOOR+2).
        BlockPos post = new BlockPos(LANE_X0 + 3, FLOOR + 2, zc);
        BlockPos lever = post.west();
        c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", post.getX(), FLOOR + 1, zc,
                post.getX(), FLOOR + 3, zc));
        c.server().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:lever[face=wall,facing=west,powered=false]",
                lever.getX(), lever.getY(), lever.getZ()));
        onClient(ctx, mc -> {
            Ap3RuntimeTests.staticCallStop();
            return null;
        });
        // Facing east (yaw -90) looks at the lever; turn away by `off` degrees.
        float yaw = -90f + off;
        c.server().command(String.format(Locale.ROOT, "tp @p %.2f %d %.2f %.1f 0", LANE_X0 + 0.5, FLOOR + 1, z, yaw));
        c.server().sync(20);
        ctx.waitTicks(20);
        Object[] r = onClient(ctx, mc -> {
            Vec3 eye = mc.player.getEyePosition();
            var shape = mc.level.getBlockState(lever).getShape(mc.level, lever);
            Vec3 centre = shape.bounds().getCenter().add(lever.getX(), lever.getY(), lever.getZ());
            Vec3 dir = centre.subtract(eye).normalize();
            BlockHitResult hit = shape.clip(eye, eye.add(dir.scale(eye.distanceTo(centre) + 1.5)), lever);
            if (hit == null) {
                return new Object[]{false, "no surface hit", false};
            }
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            return new Object[]{true, String.format(Locale.ROOT, "hit %s face %s at %.3f, %.3f, %.3f from eye %.2f, %.2f, %.2f,"
                            + " body yaw %.1f pitch %.1f", hit.getBlockPos().toShortString(), hit.getDirection(),
                    hit.getLocation().x, hit.getLocation().y, hit.getLocation().z, eye.x, eye.y, eye.z, mc.player.getYRot(),
                    mc.player.getXRot()), false};
        });
        ctx.waitTicks(20);
        boolean powered = onClient(ctx, mc -> {
            var st = mc.level.getBlockState(lever);
            return st.getBlock() instanceof LeverBlock && st.getValue(LeverBlock.POWERED);
        });
        c.note("look " + off + " degrees off the lever: " + r[1] + "; lever powered after the click: " + powered);
        c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", lever.getX(), FLOOR + 1, zc,
                post.getX(), FLOOR + 3, zc));
    }
}

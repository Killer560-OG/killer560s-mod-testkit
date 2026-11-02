package dev.testkit.gametest;

import dev.testkit.gametest.hx.Session;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

import static dev.testkit.gametest.Ap3RuntimeTests.FLOOR;
import static dev.testkit.gametest.Ap3RuntimeTests.LANE_X0;
import static dev.testkit.gametest.Ap3RuntimeTests.onClient;

/**
 * mod doc-routes-1101, two to-do Doc items checked in the AP3 session's lanes (a flat floor on the GrimAC server):
 * <ul>
 *   <li>1101-ew-carpet ("ETHERWARP OVERLAY ... doesnt work right on carpets"): a carpet lies on the floor 6 blocks ahead and
 *       he looks at it, sneaking with an ethermerged AOTV. The ray must pass the carpet like a snow layer (Hypixel's 1.8 feet
 *       list has carpet, id 171) and land on the floor block under it, which the carpet does not block: the overlay and
 *       TeleportUtils (Auto Routes, ZPEW) must both say floor block, ok. The base jar says the carpet block.</li>
 *   <li>1101-ew-crosshair ("If i am on p3sim and holding etherwarp make it adjust my crosshair to the spot that the server
 *       recognizes"): the same look at a bare floor block, with the connection labeled p3sim (the server casts from 1.54)
 *       the crosshair must move UP onto the server's aim by the projected amount; labeled anything else (Hypixel's 1.27
 *       equals the camera) it must not move.</li>
 *   <li>1101-advpos-vy ("ADVANCED POSITion - never sets the y velocity all the way back to 0"): standing still the HUD's
 *       y velocity must read exactly 0 while vanilla's delta movement holds -0.0784; in a jump it is not 0; landed, 0 again.</li>
 * </ul>
 */
final class Doc1101EtherPosCases {

    private Doc1101EtherPosCases() {
    }

    private static final String EW = "com.killer560.hub.etherwarpoverlay.EtherwarpOverlayFeature";
    private static final String EWC = "com.killer560.hub.etherwarpoverlay.EtherwarpOverlayConfig";
    private static final String TP = "com.killer560.hub.livemap.autoclear.TeleportUtils";
    private static final String POS = "com.killer560.hub.position.PositionFeature";

    static void register(Session s) {
        s.test("1101-ew-carpet", Doc1101EtherPosCases::carpet);
        s.test("1101-ew-crosshair", Doc1101EtherPosCases::crosshair);
        s.test("1101-advpos-vy", Doc1101EtherPosCases::velocity);
    }

    /** Puts him at the lane start sneaking with an ethermerged AOTV in slot 0, looking at (tx + 0.5, ty, z) from the
     *  sneaking eye (1.27). Returns the lane's z. */
    private static double aimFromLane(Session c, double z, int tx, double ty) {
        ClientGameTestContext ctx = c.ctx();
        onClient(ctx, mc -> {
            Ap3RuntimeTests.staticCallStop();
            return null;
        });
        c.server().command("item replace entity @p hotbar.0 with minecraft:diamond_shovel[custom_data={id:\"ASPECT_OF_THE_VOID\",ethermerge:1}]");
        double ex = LANE_X0 + 0.5;
        double eye = FLOOR + 1 + 1.27;
        double pitch = Math.toDegrees(Math.atan2(eye - ty, tx + 0.5 - ex));
        c.server().command(String.format(Locale.ROOT, "tp @p %.2f %d %.2f -90 %.4f", ex, FLOOR + 1, z, pitch));
        c.server().sync(20);
        ctx.waitTicks(10);
        onClient(ctx, mc -> {
            mc.player.getInventory().setSelectedSlot(0);
            return null;
        });
        ctx.getInput().holdKey(o -> o.keyShift);
        c.waitUntil("crouching", mc -> mc.player.isCrouching(), 40);
        ctx.waitTicks(8); // the camera's eye eases down to 1.27
        return z;
    }

    private static void carpet(Session c) {
        ClientGameTestContext ctx = c.ctx();
        double z = Ap3RuntimeTests.lane();
        int tx = LANE_X0 + 6;
        int bz = (int) Math.floor(z);
        Object was = onClient(ctx, mc -> ModUnderTest.call(ModUnderTest.config(EWC), "isEnabled", new Class<?>[]{}, new Object[]{}));
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(EWC), "setEnabled", true));
        try {
            aimFromLane(c, z, tx, FLOOR + 1 + 0.0625);
            c.server().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:red_carpet", tx, FLOOR + 1, bz));
            c.server().sync(10);
            ctx.waitTicks(5);
            String outcome = onClient(ctx, mc -> (String) ModUnderTest.staticCall(EW, "lastOutcome"));
            String routes = onClient(ctx, mc -> {
                Vec3 eye = new Vec3(mc.player.getX(), mc.player.getY() + 1.27, mc.player.getZ());
                Object r = ModUnderTest.staticCall(TP, "getEtherPos", new Class<?>[]{Vec3.class, float.class, float.class,
                        double.class}, new Object[]{eye, mc.player.getYRot(), mc.player.getXRot(), 57.0});
                return String.valueOf(r);
            });
            String block = onClient(ctx, mc -> mc.level.getBlockState(new net.minecraft.core.BlockPos(tx, FLOOR + 1, bz)).toString());
            c.note("carpet at " + tx + ", " + (FLOOR + 1) + ", " + bz + " (" + block + "): overlay '" + outcome
                    + "', TeleportUtils " + routes);
            String floor = tx + ", " + FLOOR + ", " + bz;
            c.check(block.contains("carpet"), "the carpet is not there: " + block);
            c.check(outcome.equals("drawn " + floor + " ok"), "the overlay did not land on the floor under the carpet ("
                    + floor + "): " + outcome);
            c.check(routes.contains("succeeded=true") && routes.contains("x=" + tx + ", y=" + FLOOR + ", z=" + bz),
                    "TeleportUtils (Auto Routes / ZPEW) did not land on the floor under the carpet: " + routes);
        } finally {
            ctx.getInput().releaseKey(o -> o.keyShift);
            c.server().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:air", tx, FLOOR + 1, bz));
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(EWC), "setEnabled", (boolean) (Boolean) was));
        }
    }

    private static void crosshair(Session c) {
        ClientGameTestContext ctx = c.ctx();
        double z = Ap3RuntimeTests.lane();
        int tx = LANE_X0 + 4;
        Object was = onClient(ctx, mc -> ModUnderTest.call(ModUnderTest.config(EWC), "isEnabled", new Class<?>[]{}, new Object[]{}));
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(EWC), "setEnabled", true));
        String ip = onClient(ctx, mc -> mc.getCurrentServer() == null ? null : mc.getCurrentServer().ip);
        try {
            aimFromLane(c, z, tx, FLOOR + 1);
            float[] hyp = measure(c, "not p3sim");
            onClient(ctx, mc -> {
                mc.getCurrentServer().ip = "localhost.p3sim.net:25565";
                return null;
            });
            ctx.waitTicks(3);
            float[] sim = measure(c, "p3sim");
            // What it should be: the 0.27 between the 1.54 server eye and the 1.27 camera, seen at the aim's depth.
            double expected = onClient(ctx, mc -> {
                Vec3 aim = (Vec3) ModUnderTest.staticCall(EW, "serverAim");
                var cam = mc.gameRenderer.mainCamera();
                Vec3 d = aim.subtract(cam.position());
                var f = cam.forwardVector();
                double depth = d.x * f.x() + d.y * f.y() + d.z * f.z();
                double pitch = Math.asin(-f.y());
                double upDot = 0.27 * Math.cos(pitch); // the offset is straight up: its part along the camera's up
                double tanHalf = Math.tan(Math.toRadians(cam.getFov()) / 2.0);
                return -(upDot / depth / tanHalf) * mc.getWindow().getGuiScaledHeight() / 2.0;
            });
            c.note(String.format(Locale.ROOT, "crosshair dy: not p3sim %.3f (moves %d), p3sim %.3f (moves %d), expected"
                    + " p3sim %.3f gui px", hyp[0], (int) hyp[1], sim[0], (int) sim[1], expected));
            c.check(Math.abs(hyp[0]) < 0.05f, "the crosshair moved off p3sim: " + hyp[0]);
            c.check(sim[1] > 0 && sim[0] < -1.0f, "the crosshair did not move up on p3sim: " + sim[0]);
            c.check(Math.abs(sim[0] - expected) < 0.25 + Math.abs(expected) * 0.05,
                    String.format(Locale.ROOT, "p3sim crosshair dy %.3f, expected %.3f", sim[0], expected));
            java.nio.file.Path shot = ctx.takeScreenshot(dev.testkit.harness.Report.fileName("1101-ew-crosshair-p3sim"));
            dev.testkit.harness.Report.screenshot("1101-ew-crosshair-p3sim", shot);
        } finally {
            ctx.getInput().releaseKey(o -> o.keyShift);
            onClient(ctx, mc -> {
                if (mc.getCurrentServer() != null) {
                    mc.getCurrentServer().ip = ip;
                }
                ModUnderTest.set(ModUnderTest.config(EWC), "setEnabled", (boolean) (Boolean) was);
                return null;
            });
        }
    }

    /** {last dy, frames moved since the mark} after a few frames. */
    private static float[] measure(Session c, String what) {
        ClientGameTestContext ctx = c.ctx();
        int m0 = onClient(ctx, mc -> (Integer) ModUnderTest.staticCall(EW, "crosshairMoves"));
        ctx.waitTicks(6);
        float dy = onClient(ctx, mc -> (Float) ModUnderTest.staticCall(EW, "lastCrosshairDy"));
        int m1 = onClient(ctx, mc -> (Integer) ModUnderTest.staticCall(EW, "crosshairMoves"));
        String outcome = onClient(ctx, mc -> (String) ModUnderTest.staticCall(EW, "lastOutcome"));
        c.note(what + ": overlay '" + outcome + "', aim " + onClient(ctx, mc -> String.valueOf(ModUnderTest.staticCall(EW, "serverAim"))));
        c.check(outcome.startsWith("drawn"), what + ": the overlay drew nothing (" + outcome + ")");
        return new float[]{dy, m1 - m0};
    }

    private static void velocity(Session c) {
        ClientGameTestContext ctx = c.ctx();
        double z = Ap3RuntimeTests.lane();
        onClient(ctx, mc -> {
            Ap3RuntimeTests.staticCallStop();
            return null;
        });
        c.server().command(String.format(Locale.ROOT, "tp @p %.2f %d %.2f -90 0", LANE_X0 + 0.5, FLOOR + 1, z));
        c.server().sync(20);
        ctx.waitTicks(15);
        double[] still = onClient(ctx, mc -> new double[]{mc.player.getDeltaMovement().y,
                ((Vec3) ModUnderTest.staticCall(POS, "shownVelocity", new Class<?>[]{net.minecraft.client.player.LocalPlayer.class},
                        new Object[]{mc.player})).y, mc.player.onGround() ? 1 : 0});
        ctx.getInput().holdKey(o -> o.keyJump);
        ctx.waitTicks(2);
        ctx.getInput().releaseKey(o -> o.keyJump);
        double airShown = 0;
        int airTicks = 0;
        for (int t = 0; t < 10; t++) {
            ctx.waitTick();
            double[] r = onClient(ctx, mc -> new double[]{mc.player.onGround() ? 1 : 0,
                    ((Vec3) ModUnderTest.staticCall(POS, "shownVelocity", new Class<?>[]{net.minecraft.client.player.LocalPlayer.class},
                            new Object[]{mc.player})).y});
            if (r[0] == 0) {
                airTicks++;
                airShown = Math.max(airShown, Math.abs(r[1]));
            }
        }
        ctx.waitTicks(20);
        double[] landed = onClient(ctx, mc -> new double[]{mc.player.getDeltaMovement().y,
                ((Vec3) ModUnderTest.staticCall(POS, "shownVelocity", new Class<?>[]{net.minecraft.client.player.LocalPlayer.class},
                        new Object[]{mc.player})).y, mc.player.onGround() ? 1 : 0});
        c.note(String.format(Locale.ROOT, "standing: vanilla y %.6f shown %.6f (ground %s); jump: %d air tick(s), largest"
                        + " shown |y| %.4f; landed: vanilla y %.6f shown %.6f", still[0], still[1], still[2] == 1, airTicks,
                airShown, landed[0], landed[1]));
        c.check(still[2] == 1 && still[0] < -0.07, "the control is gone: standing, vanilla y velocity is " + still[0]);
        c.check(still[1] == 0.0, "standing, the HUD's y velocity is " + still[1] + ", not 0");
        c.check(airTicks > 0 && airShown > 0.05, "the jump showed no y velocity (" + airTicks + " air ticks)");
        c.check(landed[2] == 1 && landed[1] == 0.0, "landed, the HUD's y velocity is " + landed[1]);
    }
}

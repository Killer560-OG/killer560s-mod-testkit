package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod branch blazewater-1103 (killer560, 2026-11-03: the Blaze solver's boxes are "messed up with water behind them").
 * 1103-blaze-water-*: one Blaze Solver fill box (forced through BlazeSolverFeature.setForcedBlazesForTest) with a sheet of
 * water behind it, in front of it, all around it, and none (control). The fill is green at alpha 0.5; a pixel counts as
 * tinted when its greenness (g - max(r, b)) rose by at least 25 against the same frame with the box off. The control
 * decides what the scene should look like; water behind the box must tint as many pixels as the control.
 * Improved Transparency off and on.
 */
final class HxBlazeWater1103Cases {

    private static final String CFG = "puzzlesolvers.BlazeSolverConfig";
    private static final String FEATURE = "puzzlesolvers.BlazeSolverFeature";

    private HxBlazeWater1103Cases() {
    }

    static void register(Session s) {
        s.test("1103-blaze-water", HxBlazeWater1103Cases::run);
    }

    private static int[] project(net.minecraft.client.Minecraft mc, Vec3 p) {
        var cam = mc.gameRenderer.mainCamera();
        Vec3 d = p.subtract(cam.position());
        var fw = cam.forwardVector();
        var up = cam.upVector();
        var left = cam.leftVector();
        double depth = d.x * fw.x() + d.y * fw.y() + d.z * fw.z();
        if (depth <= 0.05) {
            return null;
        }
        double u = -(d.x * left.x() + d.y * left.y() + d.z * left.z());
        double v = d.x * up.x() + d.y * up.y() + d.z * up.z();
        double fpx = (mc.getWindow().getHeight() / 2.0) / Math.tan(Math.toRadians(cam.getFov()) / 2.0);
        return new int[]{(int) Math.round(mc.getWindow().getWidth() / 2.0 + u / depth * fpx),
                (int) Math.round(mc.getWindow().getHeight() / 2.0 - v / depth * fpx)};
    }

    private static void setImprovedTransparency(net.minecraft.client.Minecraft mc, boolean on) {
        try {
            Object opt = mc.options.getClass().getMethod("improvedTransparency").invoke(mc.options);
            opt.getClass().getMethod("set", Object.class).invoke(opt, on);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("no improvedTransparency option on this version", e);
        }
    }

    private static Boolean improvedTransparency(net.minecraft.client.Minecraft mc) {
        try {
            Object opt = mc.options.getClass().getMethod("improvedTransparency").invoke(mc.options);
            return (Boolean) opt.getClass().getMethod("get").invoke(opt);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static void fill(Session c, BlockPos f, int x0, int y0, int z0, int x1, int y1, int z1, String block) {
        c.server().command("fill " + (f.getX() + x0) + " " + (f.getY() + y0) + " " + (f.getZ() + z0) + " "
                + (f.getX() + x1) + " " + (f.getY() + y1) + " " + (f.getZ() + z1) + " " + block);
    }

    /** A water sheet from z0 to z1 (relative to feet), x -3..3, y 0..7, sealed with barriers so it never flows. */
    private static void water(Session c, BlockPos f, int z0, int z1) {
        int lo = Math.min(z0, z1), hi = Math.max(z0, z1);
        fill(c, f, -4, 0, lo - 1, 4, 8, hi + 1, "minecraft:barrier");
        fill(c, f, -3, 0, lo, 3, 7, hi, "minecraft:water");
    }

    private static int greenness(int a) {
        return ((a >> 8) & 255) - Math.max((a >> 16) & 255, a & 255);
    }

    private static void run(Session c) throws Exception {
        BlockPos f = c.onClient(mc -> mc.player.blockPosition());
        boolean hideGui = c.onClient(mc -> dev.testkit.compat.McCompat.hudHidden(mc));
        Boolean itBefore = c.onClient(mc -> improvedTransparency(mc));
        String[] scenes = {"control", "behind", "front", "around"};
        // Everything the scene touches is cleared afterwards.
        try (Settings set = new Settings(c).with(CFG, "Enabled", true).with(CFG, "FillBox", true)
                .with(CFG, "ShowLines", false)) {
            fill(c, f, -6, -1, -14, 6, -1, 2, "minecraft:stone");
            fill(c, f, -6, 0, -14, 6, 9, 2, "minecraft:air");
            c.server().command("kill @e[type=minecraft:blaze]");
            c.server().command("summon minecraft:blaze " + (f.getX() + 0.5) + " " + (f.getY() + 2) + " "
                    + (f.getZ() - 5.5) + " {NoAI:1b,NoGravity:1b,Silent:1b,Invulnerable:1b}");
            c.ctx().waitTicks(10);
            c.ctx().runOnClient(mc -> {
                dev.testkit.compat.McCompat.setHudHidden(mc, true);
                dev.testkit.compat.McCompat.clearChatAndToasts(mc);
            });
            List<String> modes = itBefore == null ? List.of("off") : List.of("off", "on");
            for (String m : modes) {
                if (itBefore != null) {
                    c.ctx().runOnClient(mc -> setImprovedTransparency(mc, m.equals("on")));
                }
                int[] control = null;
                for (String scene : scenes) {
                    fill(c, f, -6, 0, -14, 6, 9, 2, "minecraft:air");
                    switch (scene) {
                        case "behind" -> water(c, f, -10, -10);
                        case "front" -> water(c, f, -2, -2);
                        case "around" -> water(c, f, -8, -4);
                        default -> {
                        }
                    }
                    c.ctx().waitTicks(30);
                    // Aim at the box centre; one fixed view for baseline and box frames.
                    c.ctx().runOnClient(mc -> {
                        Entity blaze = mc.level.getEntities((Entity) null, mc.player.getBoundingBox().inflate(12),
                                e -> net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())
                                        .getPath().equals("blaze")).get(0);
                        AABB box = blaze.getBoundingBox().inflate(0.5, 1.0, 0.5).move(0.0, -1.0, 0.0);
                        Vec3 eye = mc.player.getEyePosition();
                        Vec3 d = box.getCenter().subtract(eye);
                        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
                        mc.player.setXRot((float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z))));
                    });
                    c.ctx().waitTicks(5);
                    Object[] info = c.onClient(mc -> {
                        Entity blaze = mc.level.getEntities((Entity) null, mc.player.getBoundingBox().inflate(12),
                                e -> net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())
                                        .getPath().equals("blaze")).get(0);
                        AABB box = blaze.getBoundingBox().inflate(0.5, 1.0, 0.5).move(0.0, -1.0, 0.0);
                        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
                        for (int i = 0; i < 8; i++) {
                            Vec3 p = new Vec3((i & 1) == 0 ? box.minX : box.maxX, (i & 2) == 0 ? box.minY : box.maxY,
                                    (i & 4) == 0 ? box.minZ : box.maxZ);
                            int[] s = project(mc, p);
                            if (s == null) {
                                return null;
                            }
                            minX = Math.min(minX, s[0]);
                            maxX = Math.max(maxX, s[0]);
                            minY = Math.min(minY, s[1]);
                            maxY = Math.max(maxY, s[1]);
                        }
                        return new Object[]{new int[]{minX, minY, maxX, maxY}, mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                                blaze};
                    });
                    c.check(info != null, "box corner behind the camera");
                    int[] r = (int[]) info[0];
                    // Baseline: the same view with the box off.
                    c.ctx().runOnClient(mc -> Mod.staticCall(FEATURE, "setForcedBlazesForTest", (Object) null));
                    c.ctx().waitTicks(3);
                    java.nio.file.Path p0 = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(
                            c.name() + "-" + m + "-" + scene + "-base"));
                    c.ctx().runOnClient(mc -> Mod.staticCall(FEATURE, "setForcedBlazesForTest", List.of(info[3])));
                    c.ctx().waitTicks(3);
                    BufferedImage base = javax.imageio.ImageIO.read(p0.toFile());
                    int frames = 6;
                    double sum = 0;
                    int worst = 100;
                    for (int i = 0; i < frames; i++) {
                        c.ctx().waitTicks(1 + (i % 2));
                        java.nio.file.Path p1 = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(
                                c.name() + "-" + m + "-" + scene + "-" + i));
                        BufferedImage img = javax.imageio.ImageIO.read(p1.toFile());
                        double sx = img.getWidth() / (double) (int) info[1];
                        double sy = img.getHeight() / (double) (int) info[2];
                        int x0 = (int) ((r[0] + (r[2] - r[0]) * 0.2) * sx), x1 = (int) ((r[2] - (r[2] - r[0]) * 0.2) * sx);
                        int y0 = (int) ((r[1] + (r[3] - r[1]) * 0.2) * sy), y1 = (int) ((r[3] - (r[3] - r[1]) * 0.2) * sy);
                        x0 = Math.max(0, x0);
                        y0 = Math.max(0, y0);
                        x1 = Math.min(img.getWidth() - 1, x1);
                        y1 = Math.min(img.getHeight() - 1, y1);
                        int tinted = 0, total = 0;
                        for (int y = y0; y <= y1; y += 2) {
                            for (int x = x0; x <= x1; x += 2) {
                                total++;
                                if (greenness(img.getRGB(x, y)) - greenness(base.getRGB(x, y)) >= 25) {
                                    tinted++;
                                }
                            }
                        }
                        int pct = total == 0 ? 0 : 100 * tinted / total;
                        sum += pct;
                        worst = Math.min(worst, pct);
                    }
                    int avg = (int) Math.round(sum / frames);
                    c.note(String.format(Locale.ROOT, "Improved Transparency %s, %s: box interior tinted avg %d%% (worst %d%%)",
                            m, scene, avg, worst));
                    if (scene.equals("control")) {
                        control = new int[]{avg, worst};
                        c.check(avg >= 80, "control (no water) only " + avg + "% tinted: the scene or the box is wrong");
                    } else if (scene.equals("behind") || scene.equals("around")) {
                        c.check(control != null && worst >= control[1] - 15, "Improved Transparency " + m + ", water "
                                + scene + ": box interior tinted " + avg + "% (worst " + worst + "%), control " + control[0] + "%");
                    }
                }
            }
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.staticCall(FEATURE, "setForcedBlazesForTest", (Object) null);
                dev.testkit.compat.McCompat.setHudHidden(mc, hideGui);
                if (itBefore != null) {
                    setImprovedTransparency(mc, itBefore);
                }
            });
            c.server().command("kill @e[type=minecraft:blaze]");
            fill(c, f, -6, 0, -14, 6, 9, 2, "minecraft:air");
            hub(c);
        }
    }
}

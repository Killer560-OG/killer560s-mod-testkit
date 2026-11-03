package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod branch espdepth-1103 (killer560, 2026-11-03: "make sure that blaze fix applies to all things that could have a similar
 * issue"). 1103-esp-depth-<family>: one green test shape drawn through a shared highlight helper family
 * (util/EspDepthTestHook) with a sheet of water behind it, in front of it, all around it, and none (control), as
 * 1103-blaze-water does for the Blaze Solver. A pixel counts as tinted when its greenness rose by at least 25 against the
 * same frame with the shape off; the control decides what the scene should look like. Improved Transparency off and on
 * (26.3), a few frames each, so the "on" pass is also the no-crash pass for every family's OIT pipelines.
 * Families: thru-fill (mob ESP / P3 Nav / waypoints / Etherwarp through walls), depth-fill, block-fill (BoxBatch, the
 * titanium / carpet / raffle highlights), waypoint-fill (Secret Waypoints' own type), thru-outline, depth-outline,
 * thru-line (tracers), depth-line.
 */
final class HxEspDepth1103Cases {

    private static final String HOOK = "util.EspDepthTestHook";
    private static final String[] FAMILIES = {"thru-fill", "depth-fill", "block-fill", "waypoint-fill", "thru-outline",
            "depth-outline", "thru-line", "depth-line"};

    private HxEspDepth1103Cases() {
    }

    static void register(Session s) {
        for (String fam : FAMILIES) {
            s.test("1103-esp-depth-" + fam, c -> run(c, fam));
        }
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

    private static void water(Session c, BlockPos f, int z0, int z1) {
        int lo = Math.min(z0, z1), hi = Math.max(z0, z1);
        fill(c, f, -4, 0, lo - 1, 4, 8, hi + 1, "minecraft:barrier");
        fill(c, f, -3, 0, lo, 3, 7, hi, "minecraft:water");
    }

    private static int greenness(int a) {
        return ((a >> 8) & 255) - Math.max((a >> 16) & 255, a & 255);
    }

    private static void run(Session c, String family) throws Exception {
        BlockPos f = c.onClient(mc -> mc.player.blockPosition());
        boolean hideGui = c.onClient(mc -> dev.testkit.compat.McCompat.hudHidden(mc));
        Boolean itBefore = c.onClient(mc -> improvedTransparency(mc));
        String[] scenes = {"control", "behind", "front", "around"};
        List<String> fails = new ArrayList<>();
        boolean oitClass;
        try {
            Class.forName("net.minecraft.client.renderer.oit.OitStage");
            oitClass = true;
        } catch (ClassNotFoundException e) {
            oitClass = false;
        }
        final AABB box = new AABB(f.getX() - 0.75, f.getY() + 1.25, f.getZ() - 6.25, f.getX() + 0.75, f.getY() + 2.75, f.getZ() - 4.75);
        boolean line = family.endsWith("line");
        boolean outline = family.endsWith("outline");
        try {
            fill(c, f, -6, -1, -14, 6, -1, 2, "minecraft:stone");
            fill(c, f, -6, 0, -14, 6, 9, 2, "minecraft:air");
            c.ctx().waitTicks(10);
            c.ctx().runOnClient(mc -> {
                dev.testkit.compat.McCompat.setHudHidden(mc, true);
                dev.testkit.compat.McCompat.clearChatAndToasts(mc);
            });
            // A jar from before the fix (no util.EspTypes) throws every frame with Improved Transparency on: only "off" there.
            boolean fixed;
            try {
                Mod.staticCall("util.EspTypes", "init");
                fixed = true;
            } catch (Throwable t) {
                fixed = false;
            }
            List<String> modes = itBefore == null || !fixed ? List.of("off") : List.of("off", "on");
            for (String m : modes) {
                c.ctx().runOnClient(mc -> Mod.staticCall(HOOK, "setForced", (Object) null, (Object) null));
                c.ctx().waitTicks(5);
                if (itBefore != null) {
                    c.ctx().runOnClient(mc -> setImprovedTransparency(mc, m.equals("on")));
                    c.ctx().waitTicks(80);
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
                    c.ctx().runOnClient(mc -> {
                        Vec3 eye = mc.player.getEyePosition();
                        Vec3 d = box.getCenter().subtract(eye);
                        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
                        mc.player.setXRot((float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z))));
                    });
                    c.ctx().waitTicks(5);
                    Object[] info = c.onClient(mc -> {
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
                        return new Object[]{new int[]{minX, minY, maxX, maxY}, mc.getWindow().getWidth(), mc.getWindow().getHeight()};
                    });
                    c.check(info != null, "box corner behind the camera");
                    int[] r = (int[]) info[0];
                    c.ctx().runOnClient(mc -> Mod.staticCall(HOOK, "setForced", (Object) null, (Object) null));
                    c.ctx().waitTicks(3);
                    java.nio.file.Path p0 = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(
                            c.name() + "-" + m + "-" + scene + "-base"));
                    c.ctx().runOnClient(mc -> Mod.staticCall(HOOK, "setForced", family, box));
                    c.ctx().waitTicks(3);
                    BufferedImage base = javax.imageio.ImageIO.read(p0.toFile());
                    int frames = 6;
                    double sum = 0;
                    int worst = Integer.MAX_VALUE;
                    for (int i = 0; i < frames; i++) {
                        c.ctx().waitTicks(1 + (i % 2));
                        java.nio.file.Path p1 = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(
                                c.name() + "-" + m + "-" + scene + "-" + i));
                        BufferedImage img = javax.imageio.ImageIO.read(p1.toFile());
                        double sx = img.getWidth() / (double) (int) info[1];
                        double sy = img.getHeight() / (double) (int) info[2];
                        int x0, x1;
                        if (line) {
                            int cx = (r[0] + r[2]) / 2;
                            x0 = (int) ((cx - 2) * sx);
                            x1 = (int) ((cx + 2) * sx);
                        } else if (outline) {
                            x0 = (int) (r[0] * sx);
                            x1 = (int) (r[2] * sx);
                        } else {
                            x0 = (int) ((r[0] + (r[2] - r[0]) * 0.2) * sx);
                            x1 = (int) ((r[2] - (r[2] - r[0]) * 0.2) * sx);
                        }
                        double inset = outline ? 0.0 : 0.2;
                        int y0 = (int) ((r[1] + (r[3] - r[1]) * inset) * sy), y1 = (int) ((r[3] - (r[3] - r[1]) * inset) * sy);
                        x0 = Math.max(0, x0);
                        y0 = Math.max(0, y0);
                        x1 = Math.min(img.getWidth() - 1, x1);
                        y1 = Math.min(img.getHeight() - 1, y1);
                        int tinted = 0, total = 0;
                        for (int y = y0; y <= y1; y += outline ? 1 : 2) {
                            for (int x = x0; x <= x1; x += 1) {
                                total++;
                                if (greenness(img.getRGB(x, y)) - greenness(base.getRGB(x, y)) >= 25) {
                                    tinted++;
                                }
                            }
                        }
                        // An outline's pixels are a few percent of its rectangle: counted in hundredths of a percent of it.
                        int pct = total == 0 ? 0 : (outline ? (int) Math.round(10000.0 * tinted / total) : 100 * tinted / total);
                        sum += pct;
                        worst = Math.min(worst, pct);
                    }
                    int avg = (int) Math.round(sum / frames);
                    c.note(String.format(Locale.ROOT, "%s, Improved Transparency %s, %s: tinted avg %d%% (worst %d%%)",
                            family, m, scene, avg, worst));
                    if (scene.equals("control")) {
                        control = new int[]{avg, worst};
                        c.check(avg >= (outline ? 30 : 60), "control (no water) only " + avg + "% tinted: the scene or the shape is wrong");
                    } else if (scene.equals("behind") || (scene.equals("around") && (m.equals("off") || oitClass))) {
                        if (!(control != null && (outline ? worst >= control[1] * 0.85 : worst >= control[1] - 15))) {
                            fails.add(family + ", Improved Transparency " + m + ", water " + scene + ": tinted " + avg
                                    + "% (worst " + worst + "%), control " + control[0] + "%");
                        }
                    }
                }
            }
            c.check(fails.isEmpty(), String.join("; ", fails));
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.staticCall(HOOK, "setForced", (Object) null, (Object) null);
                dev.testkit.compat.McCompat.setHudHidden(mc, hideGui);
                if (itBefore != null) {
                    setImprovedTransparency(mc, itBefore);
                }
            });
            fill(c, f, -6, 0, -14, 6, 9, 2, "minecraft:air");
            hub(c);
        }
    }
}

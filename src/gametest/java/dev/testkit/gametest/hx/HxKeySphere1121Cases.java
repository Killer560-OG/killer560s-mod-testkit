package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.world.phys.Vec3;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod branch keysphere-patchy-1121 (killer560, 2026-11-21: "see how patchy the circle is every time i move the stuff changes",
 * the Door Keys Sphere range style). 1121-doorkeys-sphere-uniform puts a Blood Key stand in front of a wall of one concrete
 * color and screenshots the Sphere style from five views: 2 radii and 3.5 radii out, 30 degrees to the side, 2 radii out
 * moved 0.37 block sideways, and from inside the ball. Each view samples a grid of pixels inside the ball's projected disc
 * (80% of its radius; from inside, the middle 40% of the screen) and takes the largest per-channel deviation from the median.
 * Pass: that deviation is at most {@link #TOLERANCE} (of 255) in every view, and the ball is drawn (its center pixel differs
 * from the same view with Door Keys off by at least {@link #DRAWN}). Run with Improved Transparency off and on (26.3).
 * The old FILLED draw (both windings, depth written, sorted per quad) scored far above the tolerance.
 */
final class HxKeySphere1121Cases {

    private static final String KEYS = "doorkeys.DoorKeysConfig";
    private static final String FEATURE = "doorkeys.DoorKeysFeature";
    /** Largest per-channel deviation from the median allowed inside one view's samples (0-255). */
    private static final int TOLERANCE = 8;
    /** Smallest summed-RGB difference between the ball's center pixel and the backdrop there. */
    private static final int DRAWN = 40;
    private static final double CX = -300.5, CY = 150.0, CZ = -300.5;

    private HxKeySphere1121Cases() {
    }

    static void register(Session s) {
        s.test("1121-doorkeys-sphere-uniform", HxKeySphere1121Cases::body);
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
        double fpx = fpx(mc);
        return new int[]{(int) Math.round(mc.getWindow().getWidth() / 2.0 + u / depth * fpx),
                (int) Math.round(mc.getWindow().getHeight() / 2.0 - v / depth * fpx)};
    }

    private static double fpx(net.minecraft.client.Minecraft mc) {
        return (mc.getWindow().getHeight() / 2.0) / Math.tan(Math.toRadians(mc.gameRenderer.mainCamera().getFov()) / 2.0);
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

    private static void cmd(Session c, String s) {
        c.server().command(s);
    }

    private static void body(Session c) throws Exception {
        Vec3 home = c.onClient(mc -> mc.player.position());
        Boolean itBefore = c.onClient(mc -> improvedTransparency(mc));
        boolean hideGui = c.onClient(mc -> dev.testkit.compat.McCompat.hudHidden(mc));
        net.minecraft.server.level.ParticleStatus particlesBefore = c.onClient(mc -> mc.options.particles().get());
        double range = c.onClient(mc -> (Double) Mod.call(Mod.cfg("autosecret.AutoSecretConfig"), "keyPickupRange"));
        int rr = (int) Math.ceil(range);
        int wallZ = (int) Math.floor(CZ) + rr + 4;
        int fx0 = (int) Math.floor(CX) - 45, fx1 = (int) Math.floor(CX) + 45;
        cmd(c, "forceload add " + fx0 + " " + (int) (CZ - 20) + " " + fx1 + " " + (wallZ + 4));
        cmd(c, "gamemode creative @p");
        cmd(c, "tp @p " + CX + " " + (CY - 1.62) + " " + (CZ - 4) + " 0 0");
        c.ctx().runOnClient(mc -> mc.player.getAbilities().flying = true);
        c.ctx().waitTicks(60);   // the forceloaded chunks load, or the fills below fail
        run(c, () -> Mod.setField("secrets.DungeonState", "bossPhaseActive", false));
        cmd(c, "time set noon");
        cmd(c, "weather clear");
        // Only air between the camera and a wall of one concrete color, which fills the view behind the ball.
        cmd(c, "fill " + fx0 + " " + (int) (CY - 25) + " " + (wallZ - 6) + " " + fx1 + " " + (int) (CY + 25) + " " + (wallZ - 1) + " minecraft:air");
        cmd(c, "fill " + fx0 + " " + (int) (CY - 25) + " " + (int) (CZ - 20) + " " + fx1 + " " + (int) (CY + 25) + " " + (wallZ - 7) + " minecraft:air");
        cmd(c, "fill " + fx0 + " " + (int) (CY - 25) + " " + wallZ + " " + fx1 + " " + (int) (CY + 25) + " " + wallZ + " minecraft:white_concrete");
        cmd(c, "kill @e[tag=ksphere]");
        cmd(c, "summon armor_stand " + CX + " " + CY + " " + CZ
                + " {Tags:[\"ksphere\"],NoGravity:1b,Invisible:1b,CustomNameVisible:0b,CustomName:\"Blood Key\"}");
        c.ctx().runOnClient(mc -> {
            dev.testkit.compat.McCompat.setHudHidden(mc, true);
            dev.testkit.compat.McCompat.clearChatAndToasts(mc);
            mc.options.particles().set(net.minecraft.server.level.ParticleStatus.MINIMAL);
        });
        List<String> fails = new ArrayList<>();
        // name, camera distance in radii, yaw (degrees), sideways shift in blocks
        Object[][] views = {{"near-2R", 2.0, 0.0, 0.0}, {"far-3.5R", 3.5, 0.0, 0.0}, {"angle-30", 2.0, 30.0, 0.0},
                {"shift-0.37", 2.0, 0.0, 0.37}, {"inside", 0.0, 0.0, 0.0}};
        List<String> modes = itBefore == null ? List.of("off") : List.of("off", "on");
        try (Settings set = new Settings(c).with(KEYS, "Enabled", true).with(KEYS, "HighlightBlood", true)
                .with(KEYS, "ShowTracer", false).with(KEYS, "ThroughWalls", false).with(KEYS, "RangeStyle", 1)
                .custom(() -> Mod.staticCall("secrets.DungeonState", "setRoomSim", true),
                        () -> Mod.staticCall("secrets.DungeonState", "setRoomSim", false))) {
            for (String m : modes) {
                if (itBefore != null) {
                    c.ctx().runOnClient(mc -> setImprovedTransparency(mc, m.equals("on")));
                    c.ctx().waitTicks(80);
                }
                for (Object[] v : views) {
                    String name = (String) v[0];
                    double d = (double) v[1] * range, yaw = (double) v[2], shift = (double) v[3];
                    double rad = Math.toRadians(yaw);
                    double dx = -Math.sin(rad), dz = Math.cos(rad);
                    double ex = CX - d * dx + shift, ez = CZ - d * dz;
                    if (d == 0.0) {
                        ex = CX + 0.3 * range;
                        ez = CZ - 0.25 * range;
                    }
                    cmd(c, "tp @p " + ex + " " + (CY - 1.62) + " " + ez + " " + yaw + " 0");
                    c.ctx().runOnClient(mc -> mc.player.getAbilities().flying = true);
                    c.ctx().waitTicks(12);
                    c.note("before " + name + ": inDungeon " + c.onClient(mc -> Mod.staticCall("secrets.DungeonState", "isInDungeon"))
                            + ", boss phase " + c.onClient(mc -> Mod.staticCall("secrets.DungeonState", "isBossPhaseActive"))
                            + ", stand " + c.onClient(mc -> mc.level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class,
                            new net.minecraft.world.phys.AABB(CX - 2, CY - 2, CZ - 2, CX + 2, CY + 2, CZ + 2)).size())
                            + ", wall " + c.onClient(mc -> mc.level.getBlockState(new net.minecraft.core.BlockPos((int) Math.floor(CX), (int) CY, wallZ)).getBlock()));
                    c.waitUntil("the sphere is drawn", mc -> {
                        Object sp = Mod.staticCall(FEATURE, "pickupSphere");
                        return sp != null && "sphere".equals(Mod.staticCall(FEATURE, "lastDrawnStyle"));
                    }, 100);
                    // The same view with the feature off: the backdrop alone.
                    run(c, () -> Mod.set(KEYS, "setEnabled", false));
                    c.ctx().waitTicks(4);
                    BufferedImage base = ImageIOHelper.read(c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-" + m + "-" + name + "-base")));
                    run(c, () -> Mod.set(KEYS, "setEnabled", true));
                    c.waitUntil("the sphere is back", mc -> Mod.staticCall(FEATURE, "pickupSphere") != null
                            && "sphere".equals(Mod.staticCall(FEATURE, "lastDrawnStyle")), 100);
                    c.ctx().waitTicks(4);
                    // Pixel grid inside the disc, from the live camera.
                    final boolean inside = d == 0.0;
                    Object[] grid = c.onClient(mc -> {
                        int[][] pts = new int[13 * 13][];
                        int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
                        int[] ctr = project(mc, new Vec3(CX, CY, CZ));
                        double eyeD = mc.gameRenderer.mainCamera().position().distanceTo(new Vec3(CX, CY, CZ));
                        double discR = inside ? 0 : fpx(mc) * Math.tan(Math.asin(range / eyeD)) * 0.8;
                        int n = 0;
                        for (int gy = 0; gy < 13; gy++) {
                            for (int gx = 0; gx < 13; gx++) {
                                double u = (gx - 6) / 6.0, vv = (gy - 6) / 6.0;
                                int x, y;
                                if (inside) {
                                    x = (int) (w / 2.0 + u * w * 0.2);
                                    y = (int) (h / 2.0 + vv * h * 0.2);
                                } else {
                                    if (ctr == null || u * u + vv * vv > 1.0) {
                                        continue;
                                    }
                                    x = (int) (ctr[0] + u * discR);
                                    y = (int) (ctr[1] + vv * discR);
                                }
                                pts[n++] = new int[]{x, y};
                            }
                        }
                        return new Object[]{java.util.Arrays.copyOf(pts, n), w, h, ctr == null ? new int[]{w / 2, h / 2} : ctr, discR};
                    });
                    int[][] pts = (int[][]) grid[0];
                    int[] ctr = (int[]) grid[3];
                    c.note(name + ": camera " + c.onClient(mc -> mc.gameRenderer.mainCamera().position() + " yaw " + mc.player.getYRot()
                            + " pitch " + mc.player.getXRot()) + ", sphere center pixel " + ctr[0] + "," + ctr[1] + ", window "
                            + grid[1] + "x" + grid[2] + ", " + pts.length + " grid points, disc radius " + grid[4]);
                    for (int shot = 0; shot < 2; shot++) {
                        c.ctx().waitTicks(2);
                        java.nio.file.Path p = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-" + m + "-" + name + "-" + shot));
                        BufferedImage img = ImageIOHelper.read(p);
                        double sx = img.getWidth() / (double) (int) grid[1], sy = img.getHeight() / (double) (int) grid[2];
                        int[][] ch = new int[3][];
                        int cnt = 0;
                        for (int k = 0; k < 3; k++) {
                            ch[k] = new int[pts.length];
                        }
                        for (int[] pt : pts) {
                            int x = (int) (pt[0] * sx), y = (int) (pt[1] * sy);
                            if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) {
                                continue;
                            }
                            int px = img.getRGB(x, y);
                            ch[0][cnt] = (px >> 16) & 255;
                            ch[1][cnt] = (px >> 8) & 255;
                            ch[2][cnt] = px & 255;
                            cnt++;
                        }
                        int dev = 0;
                        for (int k = 0; k < 3; k++) {
                            int[] a = java.util.Arrays.copyOf(ch[k], cnt);
                            java.util.Arrays.sort(a);
                            int med = a.length == 0 ? 0 : a[a.length / 2];
                            for (int q : a) {
                                dev = Math.max(dev, Math.abs(q - med));
                            }
                        }
                        int cx = Math.max(0, Math.min(img.getWidth() - 1, (int) (ctr[0] * sx)));
                        int cy = Math.max(0, Math.min(img.getHeight() - 1, (int) (ctr[1] * sy)));
                        int cp = img.getRGB(cx, cy), bp = base.getRGB(cx, cy);
                        int diff = Math.abs(((cp >> 16) & 255) - ((bp >> 16) & 255)) + Math.abs(((cp >> 8) & 255) - ((bp >> 8) & 255))
                                + Math.abs((cp & 255) - (bp & 255));
                        String msg = String.format(Locale.ROOT, "sphere, Improved Transparency %s, %s shot %d (%d samples): max channel deviation %d, "
                                + "center differs from backdrop by %d", m, name, shot, cnt, dev, diff);
                        c.note(msg);
                        if (cnt < 100 || dev > TOLERANCE || diff < DRAWN) {
                            fails.add(msg);
                        }
                    }
                }
            }
            c.check(fails.isEmpty(), String.join("; ", fails));
        } finally {
            cmd(c, "kill @e[tag=ksphere]");
            cmd(c, "gamemode survival @p");
            cmd(c, "tp @p " + home.x + " " + home.y + " " + home.z);
            cmd(c, "forceload remove " + fx0 + " " + (int) (CZ - 20) + " " + fx1 + " " + (wallZ + 4));
            c.ctx().runOnClient(mc -> {
                dev.testkit.compat.McCompat.setHudHidden(mc, hideGui);
                mc.options.particles().set(particlesBefore);
                if (itBefore != null) {
                    setImprovedTransparency(mc, itBefore);
                }
            });
        }
    }

    /** ImageIO.read with the checked exception turned into an assertion. */
    private static final class ImageIOHelper {
        static BufferedImage read(java.nio.file.Path p) {
            try {
                return javax.imageio.ImageIO.read(p.toFile());
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        }
    }
}

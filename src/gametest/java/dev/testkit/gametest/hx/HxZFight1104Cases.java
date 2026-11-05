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
 * Mod branch zfight-1104 (killer560, 2026-11-04: "almost all waypoints and highlight things are doing the bug that we had
 * with the portal door. fix it for all waypoints"). 1104-zfight-&lt;family&gt;: a highlight drawn exactly on a solid block's
 * bounds through one of the depth-tested highlight paths, the portal rim check (1103-preboss-portal-pixels) applied to it.
 * Each sample point on a face of the box that the eye sees at 15 degrees or more is compared with the same view with the
 * highlight off; a highlight face lying on a block face z-fights it, so part of its samples show the bare block. Pass: in
 * every view, Improved Transparency off and on (26.3), at most {@link #MAX_BARE}% of the samples bare, over four frames.
 * Families: depth-fill (WorldRenderUtils' boxes), block-fill (BoxBatch: mining highlights), waypoint-depth (Secret
 * Waypoints' own fill), depth-outline (an outline-only box; samples on its edges) and chest (the real Storage Search chest
 * highlight, StorageSearchEsp.mark, on a real chest). Views: front at 4 blocks, 45 degrees at 6, a floor patch from above
 * at 3, far at 24 and 48 (the chest: the first, second and fourth). Auto Routes nodes, AP3 nodes and the solver highlights
 * draw through the same two depth-tested types (util/EspTypes FILLED / LINES).
 */
final class HxZFight1104Cases {

    private static final String HOOK = "util.EspDepthTestHook";
    private static final String SS_CFG = "storagesearch.StorageSearchConfig";
    private static final String[] FAMILIES = {"depth-fill", "block-fill", "waypoint-depth", "depth-outline", "chest"};
    /** Largest share of bare samples in one view. The fixed render scores 0-2 %. */
    private static final int MAX_BARE = 8;

    private HxZFight1104Cases() {
    }

    static void register(Session s) {
        for (String fam : FAMILIES) {
            s.test("1104-zfight-" + fam, c -> body(c, fam));
        }
    }

    private static double[] projectD(net.minecraft.client.Minecraft mc, Vec3 p) {
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
        return new double[]{mc.getWindow().getWidth() / 2.0 + u / depth * fpx, mc.getWindow().getHeight() / 2.0 - v / depth * fpx};
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

    private static void fill(Session c, int x0, int y0, int z0, int x1, int y1, int z1, String block) {
        c.server().command("fill " + x0 + " " + y0 + " " + z0 + " " + x1 + " " + y1 + " " + z1 + " " + block);
    }

    private static int greenness(int a) {
        return ((a >> 8) & 255) - Math.max((a >> 16) & 255, a & 255);
    }

    private static int luma(int a) {
        return (((a >> 16) & 255) * 3 + ((a >> 8) & 255) * 6 + (a & 255)) / 10;
    }

    /** Where the case builds: away from the session's rooms and the portal case's spot. */
    private static final int AT_X = -360, AT_Y = 120, AT_Z = -360;

    private static void body(Session c, String family) throws Exception {
        Vec3 home = c.onClient(mc -> mc.player.position());
        c.server().command("forceload add " + (AT_X - 16) + " " + (AT_Z - 16) + " " + (AT_X + 16) + " " + (AT_Z + 64));
        fill(c, AT_X - 12, AT_Y - 1, AT_Z - 2, AT_X + 12, AT_Y - 1, AT_Z + 54, "minecraft:stone");
        c.server().command("tp @a " + (AT_X + 0.5) + " " + AT_Y + " " + (AT_Z + 0.5) + " 0 0");
        c.waitUntil("standing at the z-fight spot", mc -> mc.player.blockPosition().equals(new BlockPos(AT_X, AT_Y, AT_Z)), 100);
        c.ctx().waitTicks(20);
        try {
            bodyAt(c, family);
        } finally {
            c.server().command("tp @a " + home.x + " " + home.y + " " + home.z);
            c.server().command("forceload remove " + (AT_X - 16) + " " + (AT_Z - 16) + " " + (AT_X + 16) + " " + (AT_Z + 64));
        }
    }

    private static void bodyAt(Session c, String family) throws Exception {
        BlockPos f = c.onClient(mc -> mc.player.blockPosition());
        boolean hideGui = c.onClient(mc -> dev.testkit.compat.McCompat.hudHidden(mc));
        Boolean itBefore = c.onClient(mc -> improvedTransparency(mc));
        boolean chest = family.equals("chest");
        boolean outline = family.endsWith("outline");
        // name, x offset of the block's west edge from the player's block, distance in z to its north face, floor patch
        Object[][] views = chest
                ? new Object[][]{{"front-4", 0, 4, false}, {"angle-6", 4, 4, false}, {"far-24", 2, 24, false}}
                : new Object[][]{{"front-4", -1, 4, false}, {"angle-6", 3, 3, false}, {"floor-3", -1, 2, true},
                        {"far-24", 2, 24, false}, {"far-48", 4, 48, false}};
        List<String> modes = itBefore == null ? List.of("off") : List.of("off", "on");
        List<String> fails = new ArrayList<>();
        int y0 = f.getY();
        Settings set = new Settings(c);
        if (chest) {
            set.with(SS_CFG, "Enabled", true).with(SS_CFG, "SearchChests", true).with(SS_CFG, "ChestEsp", true)
                    .with(SS_CFG, "EspThroughWalls", false);
        }
        try (set) {
            // Cleared once: refilling the whole area for every view stalled the server into GrimAC TimerLimit flags.
            fill(c, f.getX() - 12, y0, f.getZ() - 2, f.getX() + 12, y0 + 6, f.getZ() + 54, "minecraft:air");
            fill(c, f.getX() - 12, y0 - 1, f.getZ() - 2, f.getX() + 12, y0 - 1, f.getZ() + 54, "minecraft:stone");
            c.ctx().runOnClient(mc -> {
                dev.testkit.compat.McCompat.setHudHidden(mc, true);
                dev.testkit.compat.McCompat.clearChatAndToasts(mc);
                mc.options.particles().set(net.minecraft.server.level.ParticleStatus.MINIMAL);
            });
            for (String m : modes) {
                if (itBefore != null) {
                    c.ctx().runOnClient(mc -> setImprovedTransparency(mc, m.equals("on")));
                    c.ctx().waitTicks(80);
                }
                for (Object[] v : views) {
                    String name = (String) v[0];
                    int bx = f.getX() + (int) v[1];
                    int bz = f.getZ() + (int) v[2];
                    boolean floor = (boolean) v[3];
                    final AABB box;
                    final BlockPos chestPos = new BlockPos(bx, y0, bz);
                    if (chest) {
                        c.server().command("setblock " + bx + " " + y0 + " " + bz + " minecraft:chest[facing=south]");
                        box = new AABB(bx + 0.0625, y0, bz + 0.0625, bx + 0.9375, y0 + 0.875, bz + 0.9375);
                    } else if (floor) {
                        fill(c, bx, y0 - 1, bz, bx + 2, y0 - 1, bz + 2, "minecraft:white_concrete");
                        box = new AABB(bx, y0 - 1, bz, bx + 3, y0, bz + 3);
                    } else {
                        fill(c, bx, y0, bz, bx + 2, y0 + 2, bz + 2, "minecraft:white_concrete");
                        box = new AABB(bx, y0, bz, bx + 3, y0 + 3, bz + 3);
                    }
                    final Vec3 aim = floor ? new Vec3(box.getCenter().x, box.maxY, box.getCenter().z) : box.getCenter();
                    c.ctx().waitTicks(20);
                    c.ctx().runOnClient(mc -> {
                        Mod.staticCall(HOOK, "setForced", (Object) null, (Object) null);
                        Vec3 d = aim.subtract(mc.player.getEyePosition());
                        float want = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
                        float cur = mc.player.getYRot();
                        mc.player.setYRot(cur + net.minecraft.util.Mth.wrapDegrees(want - cur));
                        mc.player.setXRot((float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z))));
                    });
                    if (chest) {
                        run(c, () -> Mod.staticCall("storagesearch.StorageSearchEsp", "clear"));
                    }
                    c.ctx().waitTicks(8);
                    // Sample pixels from the live camera: {x, y} on every face (or, for an outline, every edge of a face) seen at
                    // 15 degrees or more, inset from the face's own edges.
                    Object[] grid = c.onClient(mc -> {
                        Vec3 eye = mc.player.getEyePosition();
                        List<int[]> pts = new ArrayList<>();
                        for (int fi = 0; fi < 6; fi++) {
                            int axis = fi / 2;
                            boolean max = fi % 2 == 1;
                            Vec3 n = new Vec3(axis == 0 ? (max ? 1 : -1) : 0, axis == 1 ? (max ? 1 : -1) : 0, axis == 2 ? (max ? 1 : -1) : 0);
                            double fx = axis == 0 ? (max ? box.maxX : box.minX) : 0;
                            double fy = axis == 1 ? (max ? box.maxY : box.minY) : 0;
                            double fz = axis == 2 ? (max ? box.maxZ : box.minZ) : 0;
                            Vec3 centre = new Vec3(axis == 0 ? fx : box.getCenter().x, axis == 1 ? fy : box.getCenter().y,
                                    axis == 2 ? fz : box.getCenter().z);
                            // Seen at 15 degrees or more, and not lying against another block (the floor patch's sides).
                            if (eye.subtract(centre).normalize().dot(n) < 0.26
                                    || !mc.level.getBlockState(BlockPos.containing(centre.add(n.scale(0.5)))).isAir()) {
                                continue;
                            }
                            // In-face coordinates (a, b) in 0..1: a grid inset from the face's edges, or for an outline points
                            // along its four edges.
                            List<double[]> ab = new ArrayList<>();
                            for (int i = 0; i < 8; i++) {
                                double t = 0.08 + 0.84 * (i + 0.5) / 8;
                                if (outline) {
                                    // The line's center, which lies at exactly the faces' depth: the z-fight spot. Off center a
                                    // thick line's constant-depth half rightly goes behind a face that comes toward the camera.
                                    ab.add(new double[]{0, t});
                                    ab.add(new double[]{1, t});
                                    ab.add(new double[]{t, 0});
                                    ab.add(new double[]{t, 1});
                                } else {
                                    for (int j = 0; j < 8; j++) {
                                        ab.add(new double[]{t, 0.08 + 0.84 * (j + 0.5) / 8});
                                    }
                                }
                            }
                            for (double[] q : ab) {
                                    double a = q[0], b = q[1];
                                    double u0, u1, w0, w1;
                                    // The face's two in-plane axes.
                                    if (axis == 0) {
                                        u0 = box.minY; u1 = box.maxY; w0 = box.minZ; w1 = box.maxZ;
                                    } else if (axis == 1) {
                                        u0 = box.minX; u1 = box.maxX; w0 = box.minZ; w1 = box.maxZ;
                                    } else {
                                        u0 = box.minX; u1 = box.maxX; w0 = box.minY; w1 = box.maxY;
                                    }
                                    double uu = u0 + (u1 - u0) * a, ww = w0 + (w1 - w0) * b;
                                    Vec3 w = axis == 0 ? new Vec3(fx, uu, ww) : axis == 1 ? new Vec3(uu, fy, ww) : new Vec3(uu, ww, fz);
                                    if (outline) {
                                        // Skip an edge lying against a block (the cube's foot on the floor): that block is
                                        // nearer than the line's center there and rightly hides it.
                                        double ou = a == 0 ? -1 : a == 1 ? 1 : 0, ow = b == 0 ? -1 : b == 1 ? 1 : 0;
                                        Vec3 out = axis == 0 ? new Vec3(0, ou, ow) : axis == 1 ? new Vec3(ou, 0, ow) : new Vec3(ou, ow, 0);
                                        if (!mc.level.getBlockState(BlockPos.containing(w.add(n.scale(0.5)).add(out.scale(0.5)))).isAir()) {
                                            continue;
                                        }
                                    }
                                    double[] p = projectD(mc, w);
                                    if (p != null) {
                                        pts.add(new int[]{(int) Math.round(p[0]), (int) Math.round(p[1])});
                                    }
                            }
                        }
                        return new Object[]{pts, mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                                Math.toDegrees(Math.atan2(aim.x - eye.x, aim.z - eye.z)), eye.distanceTo(aim)};
                    });
                    @SuppressWarnings("unchecked")
                    List<int[]> pts = (List<int[]>) grid[0];
                    java.nio.file.Path p0 = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-" + m + "-" + name + "-base"));
                    BufferedImage base = javax.imageio.ImageIO.read(p0.toFile());
                    if (chest) {
                        run(c, () -> Mod.staticCall("storagesearch.StorageSearchEsp", "mark", chestPos, 600_000L));
                    } else {
                        c.ctx().runOnClient(mc -> Mod.staticCall(HOOK, "setForced", family, box));
                    }
                    c.ctx().waitTicks(5);
                    List<Integer> deltas = new ArrayList<>();
                    for (int i = 0; i < 4; i++) {
                        c.ctx().waitTicks(1 + (i % 2));
                        java.nio.file.Path p1 = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-" + m + "-" + name + "-" + i));
                        BufferedImage img = javax.imageio.ImageIO.read(p1.toFile());
                        double sx = img.getWidth() / (double) (int) grid[1], sy = img.getHeight() / (double) (int) grid[2];
                        for (int[] p : pts) {
                            int x = (int) (p[0] * sx), y = (int) (p[1] * sy);
                            if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) {
                                continue;
                            }
                            int a = img.getRGB(x, y), b = base.getRGB(x, y);
                            // The chest's tint is its own color at a quarter: any change of color or brightness counts.
                            deltas.add(chest ? Math.abs(greenness(a) - greenness(b)) + Math.abs(luma(a) - luma(b))
                                    : greenness(a) - greenness(b));
                        }
                    }
                    c.ctx().runOnClient(mc -> Mod.staticCall(HOOK, "setForced", (Object) null, (Object) null));
                    if (chest) {
                        run(c, () -> Mod.staticCall("storagesearch.StorageSearchEsp", "clear"));
                    }
                    fill(c, bx, floor ? y0 - 1 : y0, bz, bx + 2, floor ? y0 - 1 : y0 + 2, bz + 2, floor ? "minecraft:stone" : "minecraft:air");
                    List<Integer> sorted = new ArrayList<>(deltas);
                    java.util.Collections.sort(sorted);
                    int tint = sorted.isEmpty() ? 0 : sorted.get(sorted.size() * 9 / 10);
                    // Bare: under a third of the tint (calibrated on 26.3 cheat, zfight-1104: the green fill over white concrete
                    // changes greenness by 128, the outline by 245-255, the chest's quarter-strength tint by 36-46).
                    int limit = chest ? 12 : outline ? 80 : 40;
                    int bare = 0;
                    for (int d : deltas) {
                        if (d < limit) {
                            bare++;
                        }
                    }
                    int pct = deltas.isEmpty() ? 100 : 100 * bare / deltas.size();
                    String msg = String.format(Locale.ROOT, "%s, Improved Transparency %s, %s (%.0f deg, %.1f blocks, %d samples): "
                                    + "tint p90 %d, bare %d%%", family, m, name, Math.abs((double) grid[3]), (double) grid[4],
                            deltas.size(), tint, pct);
                    c.note(msg);
                    if (deltas.size() < 40 || tint < 15) {
                        fails.add(msg + " (highlight not drawn)");
                    } else if (pct > MAX_BARE) {
                        fails.add(msg);
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
            if (chest) {
                run(c, () -> Mod.staticCall("storagesearch.StorageSearchEsp", "clear"));
            }
            fill(c, f.getX() - 12, y0, f.getZ() - 2, f.getX() + 12, y0 + 6, f.getZ() + 54, "minecraft:air");
            hub(c);
        }
    }
}

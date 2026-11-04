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
 * Mod branch portalpx-1103 (killer560, 2026-11-03: the Color Portal tint covered only part of the portal, split on a
 * diagonal). 1103-preboss-portal-pixels builds a real obsidian frame with nether_portal blocks, fires the Watcher's line
 * and screenshots the portal interior from four views (straight at 4 blocks, ~35 degrees to the side, close at 2 blocks,
 * far at 8 blocks), Improved Transparency off and on (26.3). Each sample point of the interior is checked against the same
 * view with the tint off: a pixel is tinted when its redness (red minus the larger of green and blue) rose by 25 and is
 * positive. Sample points whose line of sight crosses the obsidian frame are skipped. Pass: at least 85% of the samples (portal particles drifting in front of the pane are the untinted rest; the pre-fix render scored 24-83%)
 * tinted and no row or column of samples 60% untinted.
 * <p>
 * Rim (owner 2026-11-03 23:13, "on the very edges it changes a lot if I change how I am looking"): samples on the frame's four
 * inner faces across the pane's depth band, from every view but far-8, must hold one steady color (redness p90 - p10 at most
 * {@link #RIM_SPREAD} per face): a pane rim face lying on a frame face z-fights it into stripes.
 */
final class HxPortalPixels1103Cases {

    private static final String SCORE_CFG = HxDocDhud1101Cases.SCORE_CFG;
    private static final String WARN = HxDocDhud1101Cases.WARN;

    private HxPortalPixels1103Cases() {
    }

    static void register(Session s) {
        s.test("1103-preboss-portal-pixels", HxPortalPixels1103Cases::body);
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

    private static void fill(Session c, int x0, int y0, int z0, int x1, int y1, int z1, String block) {
        c.server().command("fill " + x0 + " " + y0 + " " + z0 + " " + x1 + " " + y1 + " " + z1 + " " + block);
    }

    /**
     * Largest redness spread (p90 - p10) allowed across one rim face's samples in a view. Calibrated on 26.3 cheat
     * (portalit-1103): the old slab, whose rim faces lay on the frame faces, scored 51-168 with Improved Transparency off (the
     * band striped between the sheet's 110 and the rim face's 163); the two-face pane scores 4-6 off and 5-29 on.
     */
    private static final int RIM_SPREAD = 35;

    /**
     * Pixels of sample points on the frame's four inner faces, across the depth band the tint pane spans (0.345-0.655 of
     * the portal block), that face the eye and are not hidden by another frame piece: {x, y, face} with face 0 west, 1 east,
     * 2 bottom, 3 top.
     */
    private static List<int[]> rimPoints(net.minecraft.client.Minecraft mc, Vec3 eye, AABB[] solids, double ix0, double ix1,
                                         double iy0, double iy1, int pz) {
        List<int[]> out = new ArrayList<>();
        double[] zs = {pz + 0.37, pz + 0.43, pz + 0.5, pz + 0.57, pz + 0.63};
        for (int fi = 0; fi < 4; fi++) {
            boolean side = fi < 2;
            Vec3 n = switch (fi) {
                case 0 -> new Vec3(1, 0, 0);
                case 1 -> new Vec3(-1, 0, 0);
                case 2 -> new Vec3(0, 1, 0);
                default -> new Vec3(0, -1, 0);
            };
            int steps = side ? 16 : 10;
            for (int i = 0; i < steps; i++) {
                for (double z : zs) {
                    Vec3 w = side
                            ? new Vec3(fi == 0 ? ix0 : ix1, iy0 + (iy1 - iy0) * (i + 0.5) / steps, z)
                            : new Vec3(ix0 + (ix1 - ix0) * (i + 0.5) / steps, fi == 2 ? iy0 : iy1, z);
                    // Faces seen nearly edge-on leave the band a pixel or three wide, where a one-pixel projection error lands
                    // on the frame's front: only faces seen at 15 degrees or more count.
                    if (eye.subtract(w).normalize().dot(n) < 0.26) {
                        continue;
                    }
                    Vec3 off = w.add(n.scale(0.01));
                    boolean hidden = false;
                    for (AABB b : solids) {
                        if (b.clip(eye, off).isPresent()) {
                            hidden = true;
                        }
                    }
                    int[] p = hidden ? null : project(mc, w);
                    if (p != null) {
                        out.add(new int[]{p[0], p[1], fi});
                    }
                }
            }
        }
        return out;
    }

    private static int redness(int a) {
        return ((a >> 16) & 255) - Math.max((a >> 8) & 255, a & 255);
    }

    private static int portalColor(Session c) {
        return c.onClient(mc -> ((Number) Mod.staticCall(WARN, "portalColor")).intValue());
    }

    private static void body(Session c) throws Exception {
        BlockPos f = c.onClient(mc -> mc.player.blockPosition());
        boolean hideGui = c.onClient(mc -> dev.testkit.compat.McCompat.hudHidden(mc));
        Boolean itBefore = c.onClient(mc -> improvedTransparency(mc));
        // Particles off for the run: portal particles drifting in front of the pane are noise, not the tint (at far-8 they cost
        // 2-11 % of the samples, and one 26.2 run fell to 84 %).
        net.minecraft.server.level.ParticleStatus particlesBefore = c.onClient(mc -> mc.options.particles().get());
        c.ctx().runOnClient(mc -> mc.options.particles().set(net.minecraft.server.level.ParticleStatus.MINIMAL));
        // name, x offset of the frame's west edge from the player's block, distance in z to the portal plane
        Object[][] views = {{"straight-4", -1, 4}, {"angle-35", 1, 4}, {"close-2", -1, 2}, {"far-8", -1, 8}};
        List<String> modes = itBefore == null ? List.of("off") : List.of("off", "on");
        List<String> fails = new ArrayList<>();
        int y0 = f.getY();
        try (Settings set = new Settings(c).with(SCORE_CFG, "Enabled", false)
                .with(SCORE_CFG, "CryptReminder", false)
                .with(SCORE_CFG, "PreBossWarning", true)
                .with(SCORE_CFG, "PreBossMageOnly", false)
                .with(SCORE_CFG, "PreBossPermanent", false)
                .with(SCORE_CFG, "PreBossPortalColor", true)
                .with(SCORE_CFG, "ScoreWarningSound", false)) {
            fill(c, f.getX() - 8, y0 - 1, f.getZ() - 2, f.getX() + 10, y0 - 1, f.getZ() + 12, "minecraft:stone");
            fill(c, f.getX() - 8, y0, f.getZ() - 2, f.getX() + 10, y0 + 8, f.getZ() + 12, "minecraft:air");
            c.ctx().waitTicks(10);
            c.ctx().runOnClient(mc -> {
                dev.testkit.compat.McCompat.setHudHidden(mc, true);
                dev.testkit.compat.McCompat.clearChatAndToasts(mc);
            });
            dungeon(c, "crypts", 2, "secrets", 10, "secretsPercent", "30.0", "rooms", 20);
            sidebar(c, "template", "dungeon", "floor", "F7", "time", "05m 00s", "cleared", 80);
            run(c, () -> {
                Mod.staticCall(WARN, "reset");
                Mod.setField("livemap.LiveMapFeature", "bossLatched", false);
                Mod.setField("scorecalc.ScoreCalculatorFeature", "inBoss", false);
                Mod.setField("scorecalc.ScoreCalculatorFeature", "bloodDone", false);
                Mod.setField("scorecalc.ScoreCalculatorFeature", "expectingBloodUpdate", false);
            });
            c.hx().chat(HxDocDhud1101Cases.WATCHER_LINE);
            for (String m : modes) {
                if (itBefore != null) {
                    c.ctx().runOnClient(mc -> setImprovedTransparency(mc, m.equals("on")));
                    c.ctx().waitTicks(80);
                }
                for (Object[] v : views) {
                    String name = (String) v[0];
                    int px = f.getX() + (int) v[1];
                    int pz = f.getZ() + (int) v[2];
                    fill(c, f.getX() - 8, y0, f.getZ() - 2, f.getX() + 10, y0 + 8, f.getZ() + 12, "minecraft:air");
                    fill(c, px, y0, pz, px + 3, y0 + 4, pz, "minecraft:obsidian");
                    fill(c, px + 1, y0 + 1, pz, px + 2, y0 + 3, pz, "minecraft:nether_portal[axis=x]");
                    final double cx = px + 2.0, cy = y0 + 2.5, cz = pz + 0.5;
                    final AABB[] solids = {new AABB(px, y0, pz, px + 1, y0 + 5, pz + 1),
                            new AABB(px + 3, y0, pz, px + 4, y0 + 5, pz + 1),
                            new AABB(px, y0, pz, px + 4, y0 + 1, pz + 1),
                            new AABB(px, y0 + 4, pz, px + 4, y0 + 5, pz + 1)};
                    final double ix0 = px + 1, ix1 = px + 3, iy0 = y0 + 1, iy1 = y0 + 4;
                    // Tint off: the base frame.
                    run(c, () -> Mod.set(SCORE_CFG, "setPreBossPortalColor", false));
                    c.waitUntil("tint off", mc -> ((Number) Mod.staticCall(WARN, "portalColor")).intValue() == 0, 60);
                    c.ctx().runOnClient(mc -> {
                        Vec3 d = new Vec3(cx, cy, cz).subtract(mc.player.getEyePosition());
                        float want = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
                        float cur = mc.player.getYRot();
                        mc.player.setYRot(cur + net.minecraft.util.Mth.wrapDegrees(want - cur));
                        mc.player.setXRot((float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z))));
                    });
                    c.ctx().waitTicks(8);
                    int cols = 14, rows = 18;
                    // Pixel of every sample point (null when hidden by the frame), from the live camera.
                    Object[] grid = c.onClient(mc -> {
                        int[][] pts = new int[rows * cols][];
                        Vec3 eye = mc.player.getEyePosition();
                        for (int r = 0; r < rows; r++) {
                            for (int q = 0; q < cols; q++) {
                                Vec3 w = new Vec3(ix0 + (ix1 - ix0) * (q + 0.5) / cols, iy0 + (iy1 - iy0) * (r + 0.5) / rows, cz);
                                boolean hidden = false;
                                for (AABB b : solids) {
                                    if (b.clip(eye, w).isPresent()) {
                                        hidden = true;
                                    }
                                }
                                pts[r * cols + q] = hidden ? null : project(mc, w);
                            }
                        }
                        double ang = Math.toDegrees(Math.atan2(cx - eye.x, cz - eye.z));
                        return new Object[]{pts, mc.getWindow().getWidth(), mc.getWindow().getHeight(), ang, Math.hypot(cx - eye.x, cz - eye.z),
                                rimPoints(mc, eye, solids, ix0, ix1, iy0, iy1, pz)};
                    });
                    int[][] pts = (int[][]) grid[0];
                    java.nio.file.Path p0 = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-" + m + "-" + name + "-base"));
                    BufferedImage base = javax.imageio.ImageIO.read(p0.toFile());
                    run(c, () -> Mod.set(SCORE_CFG, "setPreBossPortalColor", true));
                    c.waitUntil("tint on", mc -> ((Number) Mod.staticCall(WARN, "portalColor")).intValue() != 0
                            && ((Number) Mod.staticCall(WARN, "portalBlockCount")).intValue() == 6, 100);
                    int color = portalColor(c);
                    c.ctx().waitTicks(5);
                    double sumPct = 0;
                    int worstPct = 100, worstLine = 0, valid = 0;
                    @SuppressWarnings("unchecked")
                    List<int[]> rim = (List<int[]>) grid[5];
                    List<List<Integer>> rimRed = new ArrayList<>();
                    for (int i = 0; i < 4; i++) {
                        rimRed.add(new ArrayList<>());
                    }
                    for (int i = 0; i < 4; i++) {
                        c.ctx().waitTicks(1 + (i % 2));
                        java.nio.file.Path p1 = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-" + m + "-" + name + "-" + i));
                        BufferedImage img = javax.imageio.ImageIO.read(p1.toFile());
                        double sx = img.getWidth() / (double) (int) grid[1], sy = img.getHeight() / (double) (int) grid[2];
                        boolean[] tint = new boolean[rows * cols];
                        int tinted = 0, total = 0;
                        int[] rowUn = new int[rows], rowTot = new int[rows], colUn = new int[cols], colTot = new int[cols];
                        for (int r = 0; r < rows; r++) {
                            for (int q = 0; q < cols; q++) {
                                int[] p = pts[r * cols + q];
                                if (p == null) {
                                    continue;
                                }
                                int x = (int) (p[0] * sx), y = (int) (p[1] * sy);
                                if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) {
                                    continue;
                                }
                                int rn = redness(img.getRGB(x, y));
                                boolean t = rn - redness(base.getRGB(x, y)) >= 25 && rn > 0;
                                total++;
                                rowTot[r]++;
                                colTot[q]++;
                                if (t) {
                                    tinted++;
                                } else {
                                    rowUn[r]++;
                                    colUn[q]++;
                                }
                            }
                        }
                        int line = 0;
                        for (int r = 0; r < rows; r++) {
                            if (rowTot[r] >= 4) {
                                line = Math.max(line, 100 * rowUn[r] / rowTot[r]);
                            }
                        }
                        for (int q = 0; q < cols; q++) {
                            if (colTot[q] >= 4) {
                                line = Math.max(line, 100 * colUn[q] / colTot[q]);
                            }
                        }
                        for (int[] rp : rim) {
                            int x = (int) (rp[0] * sx), y = (int) (rp[1] * sy);
                            if (x >= 0 && y >= 0 && x < img.getWidth() && y < img.getHeight()) {
                                rimRed.get(rp[2]).add(redness(img.getRGB(x, y)));
                            }
                        }
                        valid = total;
                        int pct = total == 0 ? 0 : 100 * tinted / total;
                        sumPct += pct;
                        worstPct = Math.min(worstPct, pct);
                        worstLine = Math.max(worstLine, line);
                    }
                    String msg = String.format(Locale.ROOT, "%s, Improved Transparency %s, %s (%.0f deg, %.1f blocks, %d samples): tinted avg %d%% worst %d%%, "
                            + "worst untinted row/column %d%%, tint %s", "portal", m, name, Math.abs((double) grid[3]), (double) grid[4], valid,
                            Math.round(sumPct / 4), worstPct, worstLine, Integer.toHexString(color));
                    c.note(msg);
                    if (valid < 40 || worstPct < 85 || worstLine >= 60) {
                        fails.add(msg);
                    }
                    // The rim: the frame's inner faces across the pane's depth band. One steady color there in every view; a
                    // pane face lying on a frame face z-fights it and the band breaks into stripes (owner 2026-11-03 23:13).
                    String[] faceName = {"west", "east", "bottom", "top"};
                    for (int fi = 0; fi < 4; fi++) {
                        List<Integer> red = rimRed.get(fi);
                        // far-8 leaves the band a pixel or two wide, so a one-pixel projection error reads as a stripe: skip it.
                        if (red.size() < 100 || name.equals("far-8")) {
                            continue;
                        }
                        java.util.Collections.sort(red);
                        int p10 = red.get(red.size() / 10), p50 = red.get(red.size() / 2), p90 = red.get(red.size() * 9 / 10);
                        String rm = String.format(Locale.ROOT, "portal rim, Improved Transparency %s, %s, %s face (%d samples): redness p10 %d median %d p90 %d, spread %d",
                                m, name, faceName[fi], red.size(), p10, p50, p90, p90 - p10);
                        c.note(rm);
                        if (p90 - p10 > RIM_SPREAD) {
                            fails.add(rm);
                        }
                    }
                }
            }
            c.check(fails.isEmpty(), String.join("; ", fails));
        } finally {
            c.ctx().runOnClient(mc -> {
                dev.testkit.compat.McCompat.setHudHidden(mc, hideGui);
                mc.options.particles().set(particlesBefore);
                if (itBefore != null) {
                    setImprovedTransparency(mc, itBefore);
                }
            });
            fill(c, f.getX() - 8, y0, f.getZ() - 2, f.getX() + 10, y0 + 8, f.getZ() + 12, "minecraft:air");
            hub(c);
        }
    }
}

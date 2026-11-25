package dev.testkit.gametest.hx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * invibug-tracer-1125 (killer560 11-25: "for invibug add an option to add a tracer line to one if it is there. not the hidden
 * ones only real ones. and then if that isnt there then a tracer line to pathfind between spots to scan", and "instead of
 * having teh circle with the line up have it just a circle ... it will be only 1 spot but it just moves a hair and then i get
 * like 10 circles in 1 spot"):
 * <ul>
 *   <li>1125-invisibug-tracer-real: with a remembered spot (a hidden candidate) and look-alike crits about, the tracer is never
 *       a real-bug line; with two real bugs it goes to the nearer one (drawn: tracer frames count up), to the other once the
 *       nearer is caught, and off the bugs once their particles stop</li>
 *   <li>1125-invisibug-tracer-scan: no bug: to the nearest spot not scanned and on through the next two; it moves on as each
 *       spot is reached (teleported within 16 blocks), and starts over after the last; off with the setting off</li>
 *   <li>1125-invisibug-spot-merge: ten bugs a hair apart are one spot whose circle grew; one 8 blocks off is a second; every
 *       drawn circle is flat (no post); spots saved twice by an older version merge on load and are saved back merged</li>
 * </ul>
 */
final class HxInvisibug1125Cases {

    private static final String K = "foraging.InvisibugHighlight";
    // circle B of 11-23 (as in HxInvisibug1122Cases): x, z per point at y 118.90; center -592.90, 87.60
    private static final double[] B = {-592.62, 88.01, -592.78, 88.09, -592.95, 88.10, -593.12, 88.05, -593.26, 87.95,
            -593.36, 87.80, -593.40, 87.63, -593.38, 87.46, -593.30, 87.31, -593.18, 87.19, -593.02, 87.11, -592.85, 87.10,
            -592.68, 87.15, -592.54, 87.25, -592.44, 87.40, -592.40, 87.57, -592.42, 87.74, -592.50, 87.89};
    private static final double BX = -592.90 + 611.8 + 0.5, BY = 118.90 - 118.25 + 1, BZ = 87.60 - 85.57 + 0.5;

    private HxInvisibug1125Cases() {
    }

    static void register(Session s) {
        s.test("1125-invisibug-tracer-real", HxInvisibug1125Cases::tracerReal);
        s.test("1125-invisibug-tracer-scan", HxInvisibug1125Cases::tracerScan);
        s.test("1125-invisibug-spot-merge", HxInvisibug1125Cases::spotMerge);
    }

    private static Settings on(Session c, boolean tracer) {
        Object cfg = c.onClient(mc -> Mod.field(K, "CFG"));
        boolean was = c.onClient(mc -> (Boolean) Mod.call(cfg, "isEnabledRaw"));
        boolean wasTracer = c.onClient(mc -> (Boolean) Mod.call(cfg, "on", "tracer"));
        Settings s = new Settings(c).custom(() -> Mod.call(cfg, "set", "enabled", true), () -> Mod.call(cfg, "set", "enabled", was))
                .custom(() -> Mod.call(cfg, "set", "tracer", tracer), () -> Mod.call(cfg, "set", "tracer", wasTracer));
        sidebar(c, "template", "hub");
        tab(c, "template", "area", "area", "Galatea");
        c.waitUntil("IslandDetector reads GALATEA", mc -> "GALATEA".equals(Mod.staticCall("pathfinding.IslandDetector",
                "graphIsland")), 100);
        return s;
    }

    private static void spots(Session c, double... xyz) {
        c.onClient(mc -> Mod.staticCall(K, "useSpots", (Object) xyz));
    }

    private static int mode(Session c) {
        return c.onClient(mc -> ((Number) Mod.staticCall(K, "tracerMode")).intValue());
    }

    private static int frames(Session c) {
        return c.onClient(mc -> ((Number) Mod.staticCall(K, "tracerFrames")).intValue());
    }

    private static Vec3[] points(Session c) {
        return c.onClient(mc -> (Vec3[]) Mod.staticCall(K, "tracerPoints"));
    }

    private static boolean at(Vec3 v, double x, double y, double z, double tol) {
        return v != null && Math.abs(v.x - x) < tol && Math.abs(v.y - y) < tol && Math.abs(v.z - z) < tol;
    }

    private static String fmt(Vec3[] pts) {
        StringBuilder b = new StringBuilder();
        for (Vec3 v : pts) {
            b.append(String.format(java.util.Locale.ROOT, "(%.1f %.1f %.1f)", v.x, v.y, v.z));
        }
        return b.toString();
    }

    /** Point i of circle B with its center at (cx, cy, cz). */
    private static void send(Session c, int i, double cx, double cy, double cz) {
        c.server().command(String.format(java.util.Locale.ROOT, "particle crit %.3f %.3f %.3f 0 0 0 0 1 force",
                B[2 * (i % (B.length / 2))] + 592.90 + cx, cy, B[2 * (i % (B.length / 2)) + 1] - 87.60 + cz));
    }

    private static void tracerReal(Session c) {
        BlockPos p = c.onClient(mc -> mc.player.blockPosition());
        double nx = p.getX() + 2.5, ny = p.getY() + BY, nz = p.getZ() + 2.5;
        double fx = p.getX() + 30.5, fz = p.getZ() + 0.5;
        try (Settings set = on(c, true)) {
            // a hidden candidate: a remembered spot 30 blocks off (out of scan range), no bug there
            spots(c, p.getX() + 0.5, p.getY() + 1.0, p.getZ() - 30.5);
            c.ctx().waitTicks(3);
            c.check(mode(c) == 2, "with no bug the tracer is not the scan line: mode " + mode(c));
            boolean real = false;
            for (int i = 0; i < 12; i++) {
                // look-alikes: a hit's crits, a lone crit at a random height, a straight line of single crits
                c.server().command("particle crit " + (p.getX() + 3.5) + " " + (p.getY() + 1) + " " + (p.getZ() + 0.5)
                        + " 0.3 0.4 0.3 0.1 12 force");
                c.server().command("particle crit " + (p.getX() + 2.5 + 0.1 * i) + " " + (p.getY() + 1 + 0.37 * i) + " "
                        + (p.getZ() + 0.5) + " 0 0 0 0 1 force");
                c.server().command("particle crit " + (p.getX() - 3.5 + 0.15 * i) + " " + (p.getY() + 1) + " "
                        + (p.getZ() - 2.5) + " 0 0 0 0 1 force");
                c.ctx().waitTicks(5);
                real |= mode(c) == 1;
            }
            c.check(!real, "the tracer went to a look-alike crit as if it were a bug");
            Vec3[] pts = points(c);
            c.check(mode(c) == 2 && pts.length > 0 && at(pts[0], p.getX() + 0.5, p.getY() + 1.0, p.getZ() - 30.5, 0.01),
                    "the scan line does not go to the remembered spot: mode " + mode(c) + " " + fmt(pts));
            int f0 = frames(c);
            for (int i = 0; i < 12; i++) {
                send(c, i, nx, ny, nz);
                send(c, i + 5, fx, ny, fz);
                c.ctx().waitTicks(5);
            }
            pts = points(c);
            c.check(mode(c) == 1, "with two bugs boxed the tracer is not a real-bug line: mode " + mode(c));
            c.check(pts.length == 1 && at(pts[0], nx, ny, nz, 0.25), "the tracer is not on the nearer bug ("
                    + String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", nx, ny, nz) + "): " + fmt(pts));
            int f1 = frames(c);
            c.note("tracer frames drawn while boxed: " + (f1 - f0));
            c.check(f1 > f0, "the tracer was never drawn (no line vertices submitted)");
            c.hx().call("chat", "text", "The Invisibug was more visible than it thought...", "overlay", false);
            c.ctx().waitTicks(2);
            pts = points(c);
            c.check(mode(c) == 1 && pts.length == 1 && at(pts[0], fx, ny, fz, 0.25),
                    "after the catch the tracer did not move to the other bug: mode " + mode(c) + " " + fmt(pts));
            send(c, 17, fx, ny, fz);
            c.ctx().waitTicks(20);
            c.check(mode(c) != 1, "the tracer still points at a bug 20 ticks after the last particle: " + fmt(points(c)));
        }
    }

    /** Teleports the player onto a barrier block there (the server moves him; nothing written client-side). */
    private static void tp(Session c, String name, double x, double y, double z) {
        c.server().command(String.format(java.util.Locale.ROOT, "setblock %d %d %d barrier", (int) Math.floor(x),
                (int) Math.floor(y) - 1, (int) Math.floor(z)));
        c.server().command(String.format(java.util.Locale.ROOT, "tp %s %.1f %.1f %.1f", name, x, y, z));
    }

    private static void unbarrier(Session c, double x, double y, double z) {
        c.server().command(String.format(java.util.Locale.ROOT, "setblock %d %d %d air", (int) Math.floor(x),
                (int) Math.floor(y) - 1, (int) Math.floor(z)));
    }

    private static void tracerScan(Session c) {
        BlockPos p = c.onClient(mc -> mc.player.blockPosition());
        String name = self(c);
        double y = p.getY() + 1.0, x = p.getX() + 0.5, z = p.getZ() + 0.5;
        double[] s1 = {x + 30, y, z}, s2 = {x + 60, y, z}, s3 = {x - 40, y, z};
        try (Settings set = on(c, true)) {
            spots(c, s1[0], s1[1], s1[2], s2[0], s2[1], s2[2], s3[0], s3[1], s3[2]);
            c.ctx().waitTicks(3);
            Vec3[] pts = points(c);
            c.note("start: " + fmt(pts));
            c.check(mode(c) == 2 && pts.length == 3 && at(pts[0], s1[0], y, z, 0.01) && at(pts[1], s2[0], y, z, 0.01)
                    && at(pts[2], s3[0], y, z, 0.01), "not nearest first (+30, then +60, then -40): mode " + mode(c) + " " + fmt(pts));
            int f0 = frames(c);
            c.ctx().waitTicks(4);
            c.check(frames(c) > f0, "the scan line was never drawn (no line vertices submitted)");
            tp(c, name, s1[0], p.getY(), z);
            c.waitUntil("+30 scanned, the line goes on to +60", mc -> {
                Vec3[] q = (Vec3[]) Mod.staticCall(K, "tracerPoints");
                return q.length == 2 && at(q[0], s2[0], y, z, 0.01) && at(q[1], s3[0], y, z, 0.01);
            }, 60);
            tp(c, name, s2[0], p.getY(), z);
            c.waitUntil("+60 scanned, the line goes on to -40", mc -> {
                Vec3[] q = (Vec3[]) Mod.staticCall(K, "tracerPoints");
                return q.length == 1 && at(q[0], s3[0], y, z, 0.01);
            }, 60);
            tp(c, name, s3[0], p.getY(), z);
            c.waitUntil("all scanned: a new round from -40, to +30 then +60", mc -> {
                Vec3[] q = (Vec3[]) Mod.staticCall(K, "tracerPoints");
                return q.length == 2 && at(q[0], s1[0], y, z, 0.01) && at(q[1], s2[0], y, z, 0.01);
            }, 60);
            c.note("after the wrap: " + fmt(points(c)));
            Object cfg = c.onClient(mc -> Mod.field(K, "CFG"));
            c.onClient(mc -> Mod.call(cfg, "set", "tracer", false));
            c.ctx().waitTicks(3);
            c.check(mode(c) == 0, "the tracer stayed with Tracer Line off: mode " + mode(c));
        } finally {
            c.server().command(String.format(java.util.Locale.ROOT, "tp %s %.1f %d %.1f", name, x, p.getY(), z));
            c.ctx().waitTicks(5);
            for (double[] s : new double[][]{s1, s2, s3}) {
                unbarrier(c, s[0], p.getY(), z);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void spotMerge(Session c) {
        BlockPos p = c.onClient(mc -> mc.player.blockPosition());
        double cx = p.getX() + 6.5, cy = p.getY() + BY, cz = p.getZ() + 0.5;
        try (Settings set = on(c, false)) {
            spots(c);
            for (int k = 0; k < 10; k++) {
                // a hair apart: each bug's circle 0.15 further along x (1.35 blocks in all)
                for (int i = 0; i < 7; i++) {
                    send(c, i + k, cx + 0.15 * k, cy, cz);
                    c.ctx().waitTicks(5);
                }
                c.ctx().waitTicks(14);
            }
            double[][] got = c.onClient(mc -> (double[][]) Mod.staticCall(K, "spotCenters"));
            double[] radii = c.onClient(mc -> (double[]) Mod.staticCall(K, "spotRadii"));
            c.note("after 10 bugs a hair apart: " + got.length + " spot(s), radius " + (radii.length > 0 ? radii[0] : -1));
            c.check(got.length == 1, "10 bugs a hair apart made " + got.length + " spots (want 1)");
            c.check(radii.length == 1 && radii[0] > 0.9 && radii[0] <= 3.0, "the merged circle did not grow to cover them: "
                    + java.util.Arrays.toString(radii));
            c.check(got.length == 1 && Math.abs(got[0][0] - (cx + 0.675)) < 0.35 && Math.abs(got[0][2] - cz) < 0.35,
                    "the merged spot is not at the middle of its sightings: " + (got.length > 0 ? java.util.Arrays.toString(got[0]) : "none"));
            for (int i = 0; i < 7; i++) {
                send(c, i, cx + 8, cy, cz);
                c.ctx().waitTicks(5);
            }
            c.ctx().waitTicks(14);
            got = c.onClient(mc -> (double[][]) Mod.staticCall(K, "spotCenters"));
            c.check(got.length == 2, "a bug 8 blocks off did not make a second spot: " + got.length);
            Vec3[][] marks = c.onClient(mc -> (Vec3[][]) Mod.staticCall(K, "markPoints"));
            boolean flat = marks.length == 2;
            for (Vec3[] m : marks) {
                for (Vec3 v : m) {
                    flat &= Math.abs(v.y - m[0].y) < 1e-6;
                }
            }
            c.check(flat, "the spot marks are not two flat circles (a post or a missing mark): " + marks.length + " marks");
            // spots saved twice by an older version (no "n"): three a hair apart and one 20 blocks off
            double qx = p.getX() + 200.5, qy = p.getY() + 3.0, qz = p.getZ() + 200.5;
            Object file = c.onClient(mc -> Mod.staticCall("shared.SharedRecords", "file",
                    Mod.field("shared.RecordCatalog", "INVISIBUG_SPOTS")));
            JsonObject disk = c.onClient(mc -> (JsonObject) Mod.staticCall("shared.SharedRecords", "load", file));
            JsonArray list = disk.has("spots") ? disk.getAsJsonArray("spots") : new JsonArray();
            double[][] old = {{qx, qy, qz}, {qx + 0.9, qy, qz + 0.4}, {qx + 1.7, qy + 0.3, qz}, {qx + 20, qy, qz}};
            for (double[] o : old) {
                JsonObject e = new JsonObject();
                e.addProperty("k", Math.round(o[0] * 10) / 10.0 + "," + Math.round(o[1] * 10) / 10.0 + "," + Math.round(o[2] * 10) / 10.0);
                e.addProperty("x", o[0]);
                e.addProperty("y", o[1]);
                e.addProperty("z", o[2]);
                list.add(e);
            }
            JsonObject mine = new JsonObject();
            mine.add("spots", list);
            c.onClient(mc -> Mod.staticCall("shared.SharedRecords", "save", file, mine));
            spots(c);
            c.onClient(mc -> Mod.staticCall(K, "reloadSpots"));
            c.waitUntil("the saved spots are read", mc -> ((double[][]) Mod.staticCall(K, "spotCenters")).length >= 4, 100);
            c.ctx().waitTicks(20);
            got = c.onClient(mc -> (double[][]) Mod.staticCall(K, "spotCenters"));
            int nearQ = 0;
            for (double[] g : got) {
                nearQ += Math.hypot(g[0] - qx, g[2] - qz) < 4 ? 1 : 0;
            }
            c.check(nearQ == 1, "three old saved spots a hair apart loaded as " + nearQ + " spots (want 1)");
            int nearFar = 0;
            for (double[] g : got) {
                nearFar += Math.hypot(g[0] - qx - 20, g[2] - qz) < 1 ? 1 : 0;
            }
            c.check(nearFar == 1, "the old saved spot 20 blocks off loaded as " + nearFar + " spots (want 1)");
            JsonObject back = c.onClient(mc -> (JsonObject) Mod.staticCall("shared.SharedRecords", "load", file));
            int savedNearQ = 0;
            for (JsonElement e : back.getAsJsonArray("spots")) {
                JsonObject o = e.getAsJsonObject();
                savedNearQ += Math.hypot(o.get("x").getAsDouble() - qx, o.get("z").getAsDouble() - qz) < 4 ? 1 : 0;
            }
            c.check(savedNearQ == 1, "the merge was not saved back: " + savedNearQ + " saved spots near the old trio");
        }
    }
}

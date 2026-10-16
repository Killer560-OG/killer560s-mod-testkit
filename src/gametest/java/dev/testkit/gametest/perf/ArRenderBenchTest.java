package dev.testkit.gametest.perf;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.Scenario;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.perf.PerfBench.Key;
import dev.testkit.gametest.perf.PerfBench.Phase;
import dev.testkit.gametest.perf.PerfBench.Switch;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 95-perf-ar-render (2026-10-16, ar-render-1016): what Auto Routes' node renderer costs, and that it draws the same.
 * About 4 minutes; only when named; run it through perf-lock.py.
 *
 * <p>In {@link PerfBench}'s scene (sim F7, camera turning) a 30-node route is seeded in the player's room, two nodes of
 * every {@code RouteNode.Type}, 3-13 blocks in front of him: a start node, await modifiers, a stacked tile, a node of
 * its own colour and radius, three breaker blocks and a recorded path. Phases: <b>OFF</b> (every switch off but the
 * Dungeon Map, which gives the room), then Auto Routes on in each render style <b>BOX</b>, <b>CYLINDER</b>,
 * <b>FILLED</b>, and <b>EDIT</b> (edit mode, Box), {@code testkit.perf.rounds} (1) x {@code testkit.perf.bigTicks}
 * (400) ticks. Per phase: the probe's Auto Routes keys (us/f@144, bytes per frame).
 *
 * <p>FAILS when the render listener allocates more than {@link #FRAME_BYTES_BUDGET} bytes a frame in any Auto Routes
 * phase (main 4b8d18da: tens of KB), when the renderer rebuilt its geometry per frame rather than per change, when it
 * drew nothing (no frame drawn, no label) or culled nothing while the camera turned a full circle - read from the mod's
 * {@code AutoRoutesRenderer.renderStats()} (absent before ar-render-1016, which fails too).
 *
 * <p>Then still shots, the camera held on the route with the HUD hidden and the time set to noon: no route, then each
 * style, normal and edit. They go to {@code ar-render-shots/}. With {@code -PperfArRef=<dir of another jar's shots>} each
 * is compared with that jar's: a pixel counts as changed above a summed RGB delta of 30, and a shot FAILS when more than
 * {@link #SHOT_PIXEL_BUDGET} pixels changed (against "how many pixels the route itself changed", printed beside it).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class ArRenderBenchTest implements FabricClientGameTest {

    private static final String NAME = "95-perf-ar-render";
    private static final String AR = "autoroutes.AutoRoutesConfig";
    private static final String MAP = "livemap.LiveMapConfig";

    /** Render-thread bytes per frame of the Auto Routes render listener without its labels (main 4b8d18da measured far
     *  above it). */
    static final int FRAME_BYTES_BUDGET = 1500;
    /**
     * Allowed per node label drawn. A label is {@code Font.drawInBatch}: vanilla prepares the glyphs and writes each
     * glyph vertex through {@code VertexConsumer.addVertex(Matrix4fc, ...)}, which allocates a Vector3f per vertex -
     * about 1.8 KB for an "#12 Etherwarp" (ar-render-1016, BOX vs BOX bare). That is the same call before and after.
     */
    static final int LABEL_BYTES = 2500;
    /** Pixels a shot may differ from the reference jar's (summed RGB delta over 30). A fire texture in rows 0-2 of the
     *  view animates between runs: 19 and 32 px in the two edit shots of the first A/B, nothing of the route. */
    static final int SHOT_PIXEL_BUDGET = 40;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String filter = System.getProperty("testkit.scenario", "");
        if (filter.isBlank() || Scenario.skip(NAME)) {
            return;
        }
        int ticks = Integer.getInteger("testkit.perf.bigTicks", 400);
        int rounds = Integer.getInteger("testkit.perf.rounds", 1);
        String ref = System.getProperty("testkit.perf.arRef", "");
        List<String> report = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        if (!PerfBench.open(ctx, NAME, report)) {
            return;
        }
        Map<Switch, Boolean> defaults = new LinkedHashMap<>();
        try {
            if (!PerfBench.probeOn(ctx)) {
                throw new AssertionError("[" + NAME + "] the mod's PerfProbe is off or wrapped nothing: a dev jar is needed");
            }
            double[] cal = PerfBench.calibrate(ctx);
            List<Switch> all = ctx.computeOnClient(mc -> PerfBench.switches());
            defaults.putAll(ctx.computeOnClient(mc -> PerfBench.states(all)));
            Map<Switch, Boolean> off = PerfBench.allOff(all);
            Map<Switch, Boolean> on = PerfBench.allOff(all);
            boolean sawAr = false;
            for (Switch s : all) {
                if (s.label().equals(MAP)) {
                    off.put(s, true);
                    on.put(s, true);
                } else if (s.label().equals(AR)) {
                    on.put(s, true);
                    sawAr = true;
                }
            }
            if (!sawAr) {
                throw new AssertionError("[" + NAME + "] no switch " + AR + " (a cheat jar is needed)");
            }
            ctx.runOnClient(mc -> PerfBench.apply(on));
            ctx.waitTicks(20);
            String seeded = ctx.computeOnClient(ArRenderBenchTest::seedRoute);
            report.add(seeded);
            if (!seeded.startsWith("route seeded")) {
                throw new AssertionError("[" + NAME + "] " + seeded);
            }

            // ---- the cost, per style ----
            // {style, edit mode, node numbers}. "BOX bare" has no labels (numbers off, not editing): the shapes alone.
            String[][] arPhases = {{"BOX", "false", "true"}, {"BOX", "false", "false"}, {"CYLINDER", "false", "true"},
                    {"FILLED", "false", "true"}, {"BOX", "true", "true"}};
            List<Phase> offs = new ArrayList<>();
            Map<String, List<Phase>> byPhase = new LinkedHashMap<>();
            Map<String, long[]> statsByPhase = new LinkedHashMap<>();
            for (int r = 1; r <= rounds; r++) {
                ctx.runOnClient(mc -> PerfBench.apply(off));
                ctx.waitTicks(40);
                offs.add(PerfBench.measure(ctx, "OFF r" + r, ticks, cal));
                report.add(offs.get(offs.size() - 1).line());
                for (String[] p : arPhases) {
                    String label = (Boolean.parseBoolean(p[1]) ? "EDIT " : "") + p[0]
                            + (Boolean.parseBoolean(p[2]) ? "" : " bare");
                    ctx.runOnClient(mc -> {
                        PerfBench.apply(on);
                        style(p[0]);
                        Mod.set(AR, "setShowNodeNumbers", Boolean.parseBoolean(p[2]));
                        Mod.staticCall("autoroutes.AutoRoutesFeature", "setEditMode", Boolean.parseBoolean(p[1]));
                    });
                    ctx.waitTicks(40);
                    long[] s0 = ctx.computeOnClient(mc -> stats());
                    Phase ph = PerfBench.measure(ctx, label + " r" + r, ticks, cal);
                    long[] s1 = ctx.computeOnClient(mc -> stats());
                    byPhase.computeIfAbsent(label, k -> new ArrayList<>()).add(ph);
                    long[] d = s1 == null || s0 == null ? null : delta(s1, s0);
                    statsByPhase.put(label + " r" + r, d);
                    report.add(ph.line());
                    report.add("   renderer: " + (d == null ? "no AutoRoutesRenderer.renderStats() (pre-ar-render jar)"
                            : String.format(Locale.ROOT, "%d mesh build(s), %d palette build(s), %d path build(s), "
                                    + "%d frame(s) drawn, %d node draw(s), %d node cull(s), %.1f labels/frame",
                            d[0], d[1], d[2], d[3], d[4], d[5], d.length > 9 ? d[9] / (double) Math.max(1, d[3]) : -1.0)));
                }
            }
            ctx.runOnClient(mc -> {
                Mod.staticCall("autoroutes.AutoRoutesFeature", "setEditMode", false);
                Mod.set(AR, "setShowNodeNumbers", true);
            });

            // ---- budgets ----
            report.add("");
            report.add("== Auto Routes keys (probe self time, mean of rounds) ==");
            report.add(String.format(Locale.ROOT, "%-10s %-78s %-6s %9s %10s %10s", "phase", "key", "kind", "us/f@144",
                    "B/frame", "B/tick"));
            for (Map.Entry<String, List<Phase>> e : byPhase.entrySet()) {
                Map<String, double[]> keys = new LinkedHashMap<>();
                Map<String, String> kinds = new LinkedHashMap<>();
                for (Phase p : e.getValue()) {
                    for (Key k : p.keys().values()) {
                        if (!k.key().startsWith("autoroutes.")) {
                            continue;
                        }
                        double[] a = keys.computeIfAbsent(k.key(), x -> new double[3]);
                        int n = e.getValue().size();
                        a[0] += p.keyAt144(k) / n;
                        a[1] += k.bytes() / Math.max(1, p.frames()) / n;
                        a[2] += k.bytes() / Math.max(1, p.ticks()) / n;
                        kinds.put(k.key(), k.kind());
                    }
                }
                boolean sawRender = false;
                for (Map.Entry<String, double[]> k : keys.entrySet()) {
                    double[] a = k.getValue();
                    report.add(String.format(Locale.ROOT, "%-10s %-78s %-6s %9.2f %10.0f %10.0f", e.getKey(), k.getKey(),
                            kinds.get(k.getKey()), a[0], a[1], a[2]));
                    if (isRenderKey(k.getKey(), kinds.get(k.getKey()))) {
                        sawRender = true;
                        double labels = labelsPerFrame(statsByPhase, e.getKey());
                        double budget = FRAME_BYTES_BUDGET + LABEL_BYTES * Math.max(0, labels);
                        report.add(String.format(Locale.ROOT, "%-10s   budget %.0f B/frame (%d + %d x %.1f labels)",
                                e.getKey(), budget, FRAME_BYTES_BUDGET, LABEL_BYTES, Math.max(0, labels)));
                        if (a[1] > budget) {
                            failures.add(String.format(Locale.ROOT, "%s: %s allocates %.0f B/frame (budget %.0f)",
                                    e.getKey(), k.getKey(), a[1], budget));
                        }
                    }
                }
                if (!sawRender) {
                    failures.add(e.getKey() + ": the probe saw no Auto Routes render listener");
                }
            }
            for (Map.Entry<String, long[]> e : statsByPhase.entrySet()) {
                long[] d = e.getValue();
                if (d == null) {
                    failures.add(e.getKey() + ": no AutoRoutesRenderer.renderStats() - the renderer has no geometry cache");
                    continue;
                }
                if (d[3] < ticks / 2) {
                    failures.add(e.getKey() + ": the renderer drew only " + d[3] + " frame(s) in " + ticks + " ticks");
                }
                if (d[0] > 2) {
                    failures.add(e.getKey() + ": " + d[0] + " mesh rebuilds with nothing changing (one per frame is the bug)");
                }
                if (d[1] > 2) {
                    failures.add(e.getKey() + ": " + d[1] + " palette rebuilds with no active node");
                }
                if (d[4] == 0) {
                    failures.add(e.getKey() + ": no node drawn");
                }
                if (d[5] == 0) {
                    failures.add(e.getKey() + ": nothing culled in a full turn of the camera (the cull never acted)");
                }
            }

            // ---- still shots, and the comparison with another jar's ----
            failures.addAll(shots(ctx, on, ref, report));
            ctx.runOnClient(mc -> PerfBench.apply(off));
            ctx.runOnClient(mc -> unseedRoute());
            if (!failures.isEmpty()) {
                for (String f : failures) {
                    report.add("FAIL " + f);
                }
                throw new AssertionError("[" + NAME + "] " + failures.size() + " failure(s): " + failures);
            }
            report.add("PASS");
        } finally {
            try {
                ctx.runOnClient(mc -> {
                    Mod.staticCall("autoroutes.AutoRoutesFeature", "setEditMode", false);
                    McCompat.setHudHidden(mc, false);
                });
                if (!defaults.isEmpty()) {
                    ctx.runOnClient(mc -> PerfBench.apply(defaults));
                }
            } catch (Throwable ignored) {
                // restoring config must not mask the real failure
            }
            for (String line : report) {
                System.out.println("[" + NAME + "] " + line);
            }
            try {
                Path out = PerfBench.outDir();
                Files.createDirectories(out);
                Files.writeString(out.resolve("perf-ar-render.txt"), String.join(System.lineSeparator(), report)
                        + System.lineSeparator());
            } catch (Exception e) {
                System.out.println("[" + NAME + "] could not write the report: " + e);
            }
            PerfBench.close(ctx);
        }
    }

    /** Mean labels a frame over the phase's rounds, from renderStats; -1 when the jar has none. */
    private static double labelsPerFrame(Map<String, long[]> stats, String phase) {
        long labels = 0;
        long frames = 0;
        for (Map.Entry<String, long[]> e : stats.entrySet()) {
            if (e.getKey().startsWith(phase + " r") && e.getValue() != null && e.getValue().length > 9) {
                labels += e.getValue()[9];
                frames += e.getValue()[3];
            }
        }
        return frames == 0 ? -1 : labels / (double) frames;
    }

    private static boolean isRenderKey(String key, String kind) {
        return "frame".equals(kind) && key.startsWith("autoroutes.AutoRoutesFeature") && !key.contains("HUD[");
    }

    private static long[] stats() {
        try {
            return (long[]) Mod.staticCall("autoroutes.AutoRoutesRenderer", "renderStats");
        } catch (Throwable t) {
            return null;
        }
    }

    private static long[] delta(long[] a, long[] b) {
        long[] d = new long[a.length];
        for (int i = 0; i < a.length; i++) {
            d[i] = a[i] - b[i];
        }
        return d;
    }

    private static void style(String name) {
        Object cfg = Mod.cfg(AR);
        Mod.call(cfg, "setRenderStyle", Mod.enumValue("autoroutes.AutoRoutesConfig$RenderStyle", name));
    }

    // ---- shots ----

    private static List<String> shots(ClientGameTestContext ctx, Map<Switch, Boolean> on, String ref, List<String> report) {
        List<String> fails = new ArrayList<>();
        Path dir = PerfBench.outDir().resolve("ar-render-shots");
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            fails.add("could not make " + dir + ": " + e);
            return fails;
        }
        report.add("");
        report.add("== shots (" + dir + ")" + (ref.isBlank() ? ", no reference given" : ", against " + ref) + " ==");
        ctx.runOnClient(mc -> {
            dev.testkit.harness.BenchCamera.stop();
            PerfBench.apply(on);
            McCompat.setHudHidden(mc, true);
            McCompat.clearChatAndToasts(mc);
        });
        // name, Auto Routes on, style, edit mode
        String[][] shots = {{"none", "false", "BOX", "false"}, {"box", "true", "BOX", "false"},
                {"cylinder", "true", "CYLINDER", "false"}, {"filled", "true", "FILLED", "false"},
                {"box-edit", "true", "BOX", "true"}, {"cylinder-edit", "true", "CYLINDER", "true"},
                {"filled-edit", "true", "FILLED", "true"}};
        BufferedImage none = null;
        for (String[] s : shots) {
            ctx.runOnClient(mc -> {
                Mod.set(AR, "setEnabled", Boolean.parseBoolean(s[1]));
                style(s[2]);
                if (Boolean.parseBoolean(s[1])) {
                    Mod.staticCall("autoroutes.AutoRoutesFeature", "setEditMode", Boolean.parseBoolean(s[3]));
                }
                aim(mc);
                var server = mc.getSingleplayerServer();
                server.execute(() -> server.getCommands().performPrefixedCommand(
                        server.createCommandSourceStack().withSuppressedOutput(), "time set noon"));
            });
            ctx.waitTicks(10);
            ctx.runOnClient(ArRenderBenchTest::aim);
            ctx.waitTicks(2);
            Path shot = ctx.takeScreenshot(dev.testkit.harness.Report.fileName(NAME + "-" + s[0]));
            Path kept = dir.resolve(s[0] + ".png");
            try {
                Files.copy(shot, kept, StandardCopyOption.REPLACE_EXISTING);
                BufferedImage img = javax.imageio.ImageIO.read(kept.toFile());
                if (s[0].equals("none")) {
                    none = img;
                    report.add("none: " + img.getWidth() + "x" + img.getHeight());
                    continue;
                }
                int drawn = none == null ? -1 : changed(none, img, null);
                String line = String.format(Locale.ROOT, "%-14s the route changed %6d px of the no-route shot", s[0], drawn);
                if (drawn >= 0 && drawn < 500) {
                    fails.add("shot " + s[0] + ": the route changed only " + drawn + " px - it was not on screen");
                }
                if (!ref.isBlank()) {
                    Path other = Path.of(ref).resolve(s[0] + ".png");
                    if (!Files.exists(other)) {
                        fails.add("shot " + s[0] + ": no reference " + other);
                    } else {
                        BufferedImage o = javax.imageio.ImageIO.read(other.toFile());
                        BufferedImage diff = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
                        int d = changed(o, img, diff);
                        javax.imageio.ImageIO.write(diff, "png", dir.resolve(s[0] + "-diff.png").toFile());
                        int dRefNone = none == null ? -1 : refVsNone(other, dir);
                        line += String.format(Locale.ROOT, "; vs reference: %d px differ (ref's own no-route diff %d)", d,
                                dRefNone);
                        if (d > SHOT_PIXEL_BUDGET) {
                            fails.add(String.format(Locale.ROOT, "shot %s: %d px differ from the reference (budget %d)", s[0],
                                    d, SHOT_PIXEL_BUDGET));
                        }
                    }
                }
                report.add(line);
            } catch (Exception e) {
                fails.add("shot " + s[0] + ": " + e);
            }
        }
        if (!ref.isBlank()) {
            try {
                BufferedImage o = javax.imageio.ImageIO.read(Path.of(ref).resolve("none.png").toFile());
                int d = none == null ? -1 : changed(o, none, null);
                report.add("none vs reference none: " + d + " px differ (the scene itself; should be ~0)");
            } catch (Exception e) {
                report.add("none vs reference none: " + e);
            }
        }
        ctx.runOnClient(mc -> {
            Mod.staticCall("autoroutes.AutoRoutesFeature", "setEditMode", false);
            Mod.set(AR, "setEnabled", true);
            style("BOX");
            McCompat.setHudHidden(mc, false);
        });
        return fails;
    }

    private static int refVsNone(Path other, Path dir) {
        try {
            BufferedImage o = javax.imageio.ImageIO.read(other.toFile());
            BufferedImage n = javax.imageio.ImageIO.read(other.getParent().resolve("none.png").toFile());
            return changed(n, o, null);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Pixels whose summed |dR|+|dG|+|dB| exceeds 30; marks them red in {@code diff} when given. */
    private static int changed(BufferedImage a, BufferedImage b, BufferedImage diff) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return Integer.MAX_VALUE;
        }
        int n = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                int d = Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF))
                        + Math.abs((p & 0xFF) - (q & 0xFF));
                if (d > 30) {
                    n++;
                    if (diff != null) {
                        diff.setRGB(x, y, 0xFF0000);
                    }
                } else if (diff != null) {
                    int g = (((q >> 16) & 0xFF) + ((q >> 8) & 0xFF) + (q & 0xFF)) / 6;
                    diff.setRGB(x, y, (g << 16) | (g << 8) | g);
                }
            }
        }
        return n;
    }

    // ---- the route ----

    private static String seededRoom;
    private static double[] origin;

    /** The fixed view of the shots: from where he stands, facing the route (south, +z), 25 degrees down. */
    private static void aim(Minecraft mc) {
        if (mc.player == null || origin == null) {
            return;
        }
        mc.player.setYRot(0f);
        mc.player.setXRot(25f);
        mc.player.yRotO = 0f;
        mc.player.xRotO = 25f;
    }

    /**
     * Client thread. Two nodes of every type 3-13 blocks south of the player (in memory, not saved): node 1 the start
     * node, a few with await modifiers, one of its own colour and radius, the last two on one tile (stacked labels), the
     * breaker nodes with three blocks each, and a 60-sample recorded path through them.
     */
    private static String seedRoute(Minecraft mc) {
        try {
            Object frame = Mod.staticCall("autoroutes.RouteCoords$Frame", "current");
            if (frame == null) {
                return "route NOT seeded: the live map has no room frame here";
            }
            String room = (String) Mod.call(frame, "roomName");
            Object store = Mod.staticCall("autoroutes.RouteStore", "getInstance");
            Object route = Mod.call(store, "forRoomOrCreate", room);
            @SuppressWarnings("unchecked")
            List<Object> nodes = (List<Object>) Mod.call(route, "nodes");
            if (!nodes.isEmpty()) {
                return "route NOT seeded: " + room + " already has " + nodes.size() + " node(s)";
            }
            Class<?> nodeCls = Mod.cls("autoroutes.RouteNode");
            Class<?> typeCls = Mod.cls("autoroutes.RouteNode$Type");
            Class<?> coords = Mod.cls("autoroutes.RouteCoords");
            java.lang.reflect.Constructor<?> ctor = nodeCls.getConstructor(typeCls, double.class, double.class,
                    double.class, float.class, float.class, int.class);
            java.lang.reflect.Method toRel = coords.getMethod("toRelative", frame.getClass(), double.class, double.class,
                    double.class);
            java.lang.reflect.Method toRelBlock = coords.getMethod("toRelativeBlock", frame.getClass(), BlockPos.class);
            Object[] types = typeCls.getEnumConstants();
            double px = Math.floor(mc.player.getX()) + 0.5;
            double py = Math.floor(mc.player.getY() + 0.001);
            double pz = Math.floor(mc.player.getZ()) + 0.5;
            origin = new double[]{px, py, pz};
            Object secret = Mod.enumValue("autoroutes.RouteNode$AwaitCondition", "SECRET");
            Object delay = Mod.enumValue("autoroutes.RouteNode$AwaitCondition", "DELAY");
            List<Vec3> pathPts = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                int col = i % 6;
                int row = i / 6;
                double x = px - 5 + col * 2;
                double z = pz + 3 + row * 2;
                if (i == 29) { // the same tile as #29: a stack of two, labels lifted
                    x = px - 5 + (28 % 6) * 2;
                    z = pz + 3 + (28 / 6) * 2;
                }
                Vec3 rel = (Vec3) toRel.invoke(null, frame, x, py, z);
                Object node = ctor.newInstance(types[i % types.length], rel.x, rel.y, rel.z, 0f, 0f, i * 2);
                if (i == 0) {
                    setOn(node, "start", true);
                }
                if (i % 7 == 3) {
                    setOn(node, "awaitEnabled", true);
                    setOn(node, "awaitCondition", i % 2 == 0 ? secret : delay);
                    setOn(node, "awaitAmount", i % 2 == 0 ? 2 : 500);
                }
                if (i == 5) {
                    setOn(node, "radius", 2.0);
                    setOn(node, "colour", Integer.valueOf(0xFF33CCFF));
                }
                if (i == 11) {
                    setOn(node, "radius", 0.6);
                }
                if ("DUNGEON_BREAKER".equals(((Enum<?>) types[i % types.length]).name())) {
                    @SuppressWarnings("unchecked")
                    List<Object> blocks = (List<Object>) Mod.field(node, "breakerBlocks");
                    for (int b = 0; b < 3; b++) {
                        BlockPos real = BlockPos.containing(x + b - 1, py - 1, z + 1);
                        blocks.add(toRelBlock.invoke(null, frame, real));
                    }
                }
                nodes.add(node);
                pathPts.add(rel);
            }
            // A recorded path: 60 samples along the nodes' rows.
            Class<?> pathCls = Mod.cls("autoroutes.RoutePath");
            Class<?> sampleCls = Mod.cls("autoroutes.RoutePath$Sample");
            Object path = pathCls.getConstructor().newInstance();
            java.lang.reflect.Constructor<?> sc = sampleCls.getConstructor(double.class, double.class, double.class,
                    float.class, float.class, int.class, boolean.class);
            for (int i = 0; i < 60; i++) {
                Vec3 a = pathPts.get(Math.min(29, i / 2));
                Vec3 b = pathPts.get(Math.min(29, i / 2 + 1));
                double t = (i % 2) / 2.0;
                Mod.call(path, "add", sc.newInstance(a.x + (b.x - a.x) * t, a.y, a.z + (b.z - a.z) * t, 0f, 0f, 1, true));
            }
            Mod.call(route, "setPath", path);
            seededRoom = room;
            return "route seeded: 30 nodes (" + types.length + " types) in " + room + ", 3-13 blocks south of "
                    + String.format(Locale.ROOT, "%.1f %.1f %.1f", px, py, pz);
        } catch (Throwable t) {
            return "route NOT seeded: " + t;
        }
    }

    private static void setOn(Object target, String field, Object value) throws ReflectiveOperationException {
        target.getClass().getField(field).set(target, value);
    }

    private static void unseedRoute() {
        if (seededRoom == null) {
            return;
        }
        try {
            Object store = Mod.staticCall("autoroutes.RouteStore", "getInstance");
            Object route = Mod.call(store, "forRoom", seededRoom);
            if (route != null) {
                ((List<?>) Mod.call(route, "nodes")).clear();
                Mod.call(route, "setPath", (Object) null);
            }
        } catch (Throwable ignored) {
            // the client config is thrown away after the run anyway
        }
        seededRoom = null;
    }
}

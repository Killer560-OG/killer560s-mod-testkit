package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 1321: the Etherwarp Overlay box draws every face the camera can see, at every angle and distance, Filled and Outline.
 *
 * <p>killer560 (2026-10-13, from a friend): "for the box it doesnt draw on the face close to the player". The player
 * sneaks with an Aspect of the Void and looks at an isolated target block from four vantage points (a one-high step at
 * his feet, the top block of a two-high wall at eye level, from a pillar above looking down, from under a floating
 * ledge looking up), 0/30/45/60 degrees off the face's normal, at gaps from 3.2 blocks down to touching. Each view is
 * shot three times - Filled, Outline, overlay off - and every camera-facing face of the box is sampled where it lands on
 * screen: a Filled face's interior (a 7x7 grid, inset 12%), an Outline face's four edges. A sample counts only when it
 * is on screen and nothing but the target lies between the camera and it (a client raycast); a face is present when at
 * least 90% (Filled) / 80% (Outline) of its usable samples changed against the overlay-off shot (summed RGB delta > 30).
 */
final class EtherwarpFaceCases {

    private EtherwarpFaceCases() {
    }

    private static final String EW = "etherwarpoverlay.EtherwarpOverlayFeature";
    private static final String EWC = "etherwarpoverlay.EtherwarpOverlayConfig";
    private static final String STYLE = "etherwarpoverlay.EtherwarpOverlayConfig$Style";

    /** Arena: x/z 330..350, floor top y 100. Own coordinates (981 uses 294..312). */
    private static final int CX = 340, CZ = 340;
    private static final double[] GAPS = {3.2, 2.0, 1.0, 0.4, 0.0};
    /** From the pillar a gap under 1 puts the pillar's own top edge in the ray (it would target the pillar). */
    private static final double[] ABOVE_GAPS = {3.2, 2.0, 1.0};
    private static final int VIEWS = 4 * 5 + 4 * 5 + 2 * 3 + 2 * 5;

    private enum View {
        STEP(100, new int[]{0, 30, 45, 60}, GAPS),
        WALL(101, new int[]{0, 30, 45, 60}, GAPS),
        ABOVE(100, new int[]{0, 45}, ABOVE_GAPS),
        BELOW(102, new int[]{0, 45}, GAPS);

        final int ty;
        final int[] angles;
        final double[] gaps;

        View(int ty, int[] angles, double[] gaps) {
            this.ty = ty;
            this.angles = angles;
            this.gaps = gaps;
        }
    }

    /** The six faces: outward normal, then two in-plane axes. */
    private static final int[][] FACES = {
            {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
    private static final String[] FACE_NAMES = {"-x", "+x", "-y", "+y", "-z", "+z"};

    static void run(UiCase c) throws Exception {
        List<AutoCloseable> restore = new ArrayList<>();
        boolean hideWas = c.onClient(mc -> dev.testkit.compat.McCompat.hudHidden(mc));
        double fovEffectWas = c.onClient(mc -> mc.options.fovEffectScale().get());
        boolean bobWas = c.onClient(mc -> mc.options.bobView().get());
        List<String> missing = new ArrayList<>();
        tintedTotal = 0;
        int views = 0;
        int facesChecked = 0;
        try {
            c.onClient(mc -> {
                restore.add(0, Mod.with(EWC, "Enabled", true));
                restore.add(0, Mod.with(EWC, "ShowWhenFailed", false));
                restore.add(0, Mod.with(EWC, "FullBlock", true));
                restore.add(0, Mod.with(EWC, "SafeColor", 0xC0FF00FF));
                restore.add(0, Mod.with(EWC, "Style", Mod.enumValue(STYLE, "OUTLINE")));
                dev.testkit.compat.McCompat.setHudHidden(mc, true);
                mc.options.fovEffectScale().set(0.0);
                mc.options.bobView().set(false);
                return null;
            });
            // the arena's chunks must be loaded before the first fill (981 waits the same)
            DocDungeonsCases.command(c, "tp @a " + CX + " 120 " + CZ);
            c.ticks(20);
            for (View v : View.values()) {
                for (int angle : v.angles) {
                    for (double gap : v.gaps) {
                        Result r = view(c, v, angle, gap);
                        views++;
                        facesChecked += r.checked;
                        c.note(r.line);
                        missing.addAll(r.missing);
                    }
                }
            }
        } finally {
            c.onClient(mc -> {
                mc.options.keyShift.setDown(false);
                dev.testkit.compat.McCompat.setHudHidden(mc, hideWas);
                mc.options.fovEffectScale().set(fovEffectWas);
                mc.options.bobView().set(bobWas);
                return null;
            });
            for (AutoCloseable r : restore) {
                r.close();
            }
            DocDungeonsCases.command(c, "fill 330 99 330 350 108 350 minecraft:air");
            DocDungeonsCases.command(c, "clear @a");
        }
        c.note(String.format(Locale.ROOT, "%d view(s), %d camera-facing face check(s), %d pressed-against face(s), %d missing", views,
                facesChecked, tintedTotal, missing.size()));
        c.check(views == VIEWS, "only " + views + " of " + VIEWS + " views ran");
        c.check(facesChecked >= 120, "only " + facesChecked + " face checks had enough on-screen samples");
        c.check(tintedTotal >= 3, "only " + tintedTotal + " view(s) pressed him against a face - the close case never ran");
        c.check(missing.isEmpty(), missing.size() + " camera-facing face(s) not drawn: " + missing);
    }

    /**
     * 1322, killer560 (2026-10-13, 26.2): "When i am holding crouch and walking forward into a block it is almost like the
     * overlay shifts faster than I walk so it is further behind the block and I cannot see the outline on the front face."
     * Crouch-walks from 3 blocks into the eye-level block of a two-high wall, Outline style, magenta, and every 2 ticks
     * while moving shoots the screen and looks for magenta along the near face's projected edges (any pixel within 3 px),
     * and reports where the magenta actually is (its bounding box against the projected face).
     */
    static void walk(UiCase c) throws Exception {
        List<AutoCloseable> restore = new ArrayList<>();
        boolean hideWas = c.onClient(mc -> dev.testkit.compat.McCompat.hudHidden(mc));
        boolean bobWas = c.onClient(mc -> mc.options.bobView().get());
        List<String> bad = new ArrayList<>();
        int frames = 0;
        edgeChecks = 0;
        try {
            c.onClient(mc -> {
                restore.add(0, Mod.with(EWC, "Enabled", true));
                restore.add(0, Mod.with(EWC, "ShowWhenFailed", true));
                restore.add(0, Mod.with(EWC, "FullBlock", true));
                restore.add(0, Mod.with(EWC, "SafeColor", 0xFFFF00FF));
                restore.add(0, Mod.with(EWC, "FailedColor", 0xFFFF00FF));
                restore.add(0, Mod.with(EWC, "Style", Mod.enumValue(STYLE, "OUTLINE")));
                dev.testkit.compat.McCompat.setHudHidden(mc, true);
                mc.options.bobView().set(false);
                return null;
            });
            DocDungeonsCases.command(c, "tp @a " + CX + " 120 " + CZ);
            c.ticks(20);
            DocDungeonsCases.command(c, "fill 330 99 330 350 99 350 minecraft:stone");
            DocDungeonsCases.command(c, "fill 330 100 330 350 108 350 minecraft:air");
            DocDungeonsCases.command(c, "fill " + CX + " 100 " + (CZ - 3) + " " + CX + " 101 " + (CZ + 3) + " minecraft:stone");
            // the target in gold, so the world's own render of its face is visible to compare the outline against
            DocDungeonsCases.command(c, "setblock " + CX + " 101 " + CZ + " minecraft:gold_block");
            DocDungeonsCases.command(c, "clear @a");
            DocDungeonsCases.command(c,
                    "give @a minecraft:diamond_shovel[custom_data={id:\"ASPECT_OF_THE_VOID\",ethermerge:1}]");
            DocDungeonsCases.flightOff(c);
            DocDungeonsCases.command(c, String.format(Locale.ROOT, "tp @a %.4f 100 %.4f -90 0", CX - 3.5, CZ + 0.5));
            c.onClient(mc -> {
                mc.player.getInventory().setSelectedSlot(0);
                mc.options.keyShift.setDown(true);
                return null;
            });
            DocDungeonsCases.await(c, "crouching", mc -> mc.player.onGround() && mc.player.isCrouching(), 100);
            c.ticks(40); // the gold block's placement settles first
            c.onClient(mc -> {
                mc.options.keyUp.setDown(true);
                return null;
            });
            c.onClient(mc -> {
                if (!probeHooked) {
                    probeHooked = true;
                    net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES
                            .register(ctx -> {
                                Minecraft m = Minecraft.getInstance();
                                if (m.player == null) {
                                    return;
                                }
                                Vec3 overlayCam = (Vec3) Mod.staticCall("compat.McRender", "cameraPos", ctx);
                                Cam main = camera(m);
                                float pt = -1;
                                try {
                                    Object dt = Minecraft.class.getMethod("getDeltaTracker").invoke(m);
                                    pt = (Float) dt.getClass().getMethod("getGameTimeDeltaPartialTick", boolean.class)
                                            .invoke(dt, false);
                                } catch (ReflectiveOperationException ignored) {
                                    // left at -1
                                }
                                PROBE = String.format(Locale.ROOT, "overlayCam.x %.4f mainCam.x %.4f player.xo %.4f player.x %.4f"
                                        + " partialTick %.3f", overlayCam.x, main.pos.x, m.player.xo, m.player.getX(), pt);
                            });
                }
                return null;
            });
            double lastX = -1e9;
            for (int i = 0; i < 40; i++) {
                c.ticks(1);
                double px = c.onClient(mc -> mc.player.getX());
                boolean moving = Math.abs(px - lastX) > 0.01;
                lastX = px;
                String outcome = c.onClient(mc -> (String) Mod.staticCall(EW, "lastOutcome"));
                String label = String.format(Locale.ROOT, "walk-%02d", i);
                BufferedImage img = grab(c, label, false);
                Cam cam = c.onClient(EtherwarpFaceCases::camera);
                Vec3 eye = c.onClient(mc -> mc.player.getEyePosition());
                c.note(label + " probe at the overlay's render: " + PROBE);
                int[] n = FACES[0]; // the near face: -x of block (CX, 101, CZ)
                int hit = 0, usable = 0;
                for (double[] e : new double[][]{{0, -1}, {1, -1}, {-1, 0}, {-1, 1}}) {
                    for (int k = 1; k <= 9; k++) {
                        double t = k / 10.0;
                        Vec3 pt = facePoint(CX, 101, CZ, n, e[0] < 0 ? t : e[0], e[1] < 0 ? t : e[1]);
                        int[] sp = cam.project(pt, img.getWidth(), img.getHeight());
                        if (sp == null) {
                            continue;
                        }
                        usable++;
                        if (magentaNear(img, sp, 3)) {
                            hit++;
                        }
                    }
                }
                int[] bb = magentaBox(img);
                int[] gold = goldBox(img);
                // Where the gold face really starts and ends on the middle row (the world's own render), and whether the
                // outline is there: a magenta pixel within 4 px of that column, anywhere in the 40 rows around it.
                int w = img.getWidth(), mid = img.getHeight() / 2;
                int left = -1, right = -1;
                for (int x = 0; x < w; x++) {
                    if (goldish(img.getRGB(x, mid))) {
                        if (left < 0) {
                            left = x;
                        }
                        right = x;
                    }
                }
                int edges = 0, onEdge = 0;
                for (int ex : new int[]{left, right}) {
                    if (ex > 6 && ex < w - 7) {
                        edges++;
                        boolean found = false;
                        for (int y = mid - 20; y <= mid + 20 && !found; y++) {
                            found = magentaNear(img, new int[]{ex, y}, 4);
                        }
                        if (found) {
                            onEdge++;
                        }
                    }
                }
                int worst = edges - onEdge;
                edgeChecks += edges;
                double[] tl = cam.projectRaw(facePoint(CX, 101, CZ, n, 1, 0), img.getWidth(), img.getHeight());
                double[] br = cam.projectRaw(facePoint(CX, 101, CZ, n, 0, 1), img.getWidth(), img.getHeight());
                String line = String.format(Locale.ROOT, "%s x %.3f moving %s cam %.3f,%.3f,%.3f eye %.3f,%.3f,%.3f %s |"
                                + " near-face edges magenta %d/%d | magenta box %s, gold face %s (side edges on screen %d, outlined %d), face projects"
                        + " %s..%s", label, px, moving,
                        cam.pos.x, cam.pos.y, cam.pos.z, eye.x, eye.y, eye.z, outcome, hit, usable,
                        bb == null ? "none" : bb[0] + "," + bb[1] + ".." + bb[2] + "," + bb[3],
                        gold == null ? "none" : gold[0] + "," + gold[1] + ".." + gold[2] + "," + gold[3], edges, onEdge,
                        tl == null ? "-" : String.format(Locale.ROOT, "%.0f,%.0f", tl[0], tl[1]),
                        br == null ? "-" : String.format(Locale.ROOT, "%.0f,%.0f", br[0], br[1]));
                c.note(line);
                frames++;
                dev.testkit.harness.Report.screenshot(c.name() + "-" + label, TAKEN.get(c.name() + "-" + label));
                // Pressed against it (gold fills the screen, no edge of it on screen): nothing may be drawn - no
                // smaller outline, no shading (killer560, 2026-10-13: "I don't want some sort of smaller outline").
                boolean filled = gold != null && gold[0] <= 2 && gold[2] >= w - 3 && gold[1] <= 2 && gold[3] >= img.getHeight() - 3;
                if (filled && bb != null) {
                    worst++;
                }
                if (worst > 0) {
                    bad.add(line);
                }
                if (!moving && i > 3) {
                    break;
                }
            }
        } finally {
            c.onClient(mc -> {
                mc.options.keyShift.setDown(false);
                mc.options.keyUp.setDown(false);
                dev.testkit.compat.McCompat.setHudHidden(mc, hideWas);
                mc.options.bobView().set(bobWas);
                return null;
            });
            for (AutoCloseable r : restore) {
                r.close();
            }
            DocDungeonsCases.command(c, "fill 330 99 330 350 108 350 minecraft:air");
            DocDungeonsCases.command(c, "clear @a");
        }
        c.note(frames + " frame(s), " + edgeChecks + " on-screen side edge(s) of the gold face checked, " + bad.size() + " bad");
        c.check(frames >= 15, "only " + frames + " frame(s) while walking");
        c.check(edgeChecks >= 30, "only " + edgeChecks + " side edges were on screen - the walk never showed the face");
        c.check(bad.isEmpty(), bad.size() + " frame(s) with the near face's outline off its edges: " + bad);
    }

    /**
     * 1323, killer560 (2026-10-13, both versions): aiming at a stair, the full-block box's back edges showed through the
     * stair's open corner and changed shape as he walked in ("there is a visible shift ingame when walking forward"). A
     * bottom slab on a pillar is the same case (the box is a full block, its top half is air): standing 2 blocks off its
     * face, Outline style, the four front-face edges must be drawn and the back top edge (seen through the air above the
     * slab) must not, at three distances.
     */
    static void backEdges(UiCase c) throws Exception {
        List<AutoCloseable> restore = new ArrayList<>();
        boolean hideWas = c.onClient(mc -> dev.testkit.compat.McCompat.hudHidden(mc));
        double fovEffectWas = c.onClient(mc -> mc.options.fovEffectScale().get());
        boolean bobWas = c.onClient(mc -> mc.options.bobView().get());
        List<String> bad = new ArrayList<>();
        int backSamples = 0, frontSamples = 0;
        try {
            c.onClient(mc -> {
                restore.add(0, Mod.with(EWC, "Enabled", true));
                restore.add(0, Mod.with(EWC, "ShowWhenFailed", true));
                restore.add(0, Mod.with(EWC, "FullBlock", true));
                restore.add(0, Mod.with(EWC, "SafeColor", 0xFFFF00FF));
                restore.add(0, Mod.with(EWC, "FailedColor", 0xFFFF00FF));
                restore.add(0, Mod.with(EWC, "Style", Mod.enumValue(STYLE, "OUTLINE")));
                dev.testkit.compat.McCompat.setHudHidden(mc, true);
                mc.options.fovEffectScale().set(0.0);
                mc.options.bobView().set(false);
                return null;
            });
            DocDungeonsCases.command(c, "tp @a " + CX + " 120 " + CZ);
            c.ticks(20);
            DocDungeonsCases.command(c, "fill 330 99 330 350 99 350 minecraft:stone");
            DocDungeonsCases.command(c, "fill 330 100 330 350 108 350 minecraft:air");
            DocDungeonsCases.command(c, "setblock " + CX + " 100 " + CZ + " minecraft:stone");
            DocDungeonsCases.command(c, "setblock " + CX + " 101 " + CZ + " minecraft:stone_brick_slab[type=bottom]");
            DocDungeonsCases.command(c, "clear @a");
            DocDungeonsCases.command(c,
                    "give @a minecraft:diamond_shovel[custom_data={id:\"ASPECT_OF_THE_VOID\",ethermerge:1}]");
            DocDungeonsCases.flightOff(c);
            c.onClient(mc -> {
                mc.player.getInventory().setSelectedSlot(0);
                mc.options.keyShift.setDown(true);
                return null;
            });
            for (double gap : new double[]{3.0, 2.0, 1.2}) {
                double px = CX - gap - 0.3;
                DocDungeonsCases.command(c, String.format(Locale.ROOT, "tp @a %.4f 100 %.4f -90 0", px, CZ + 0.5));
                DocDungeonsCases.await(c, "crouching", mc -> mc.player.onGround() && mc.player.isCrouching(), 100);
                c.ticks(5);
                Vec3 aim = new Vec3(CX + 0.1, 101.25, CZ + 0.5);
                c.onClient(mc -> {
                    Vec3 d = aim.subtract(mc.player.getEyePosition());
                    float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
                    float pitch = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
                    mc.player.setYRot(yaw);
                    mc.player.setXRot(pitch);
                    mc.player.yRotO = yaw;
                    mc.player.xRotO = pitch;
                    return null;
                });
                c.ticks(3);
                String outcome = c.onClient(mc -> (String) Mod.staticCall(EW, "lastOutcome"));
                String label = String.format(Locale.ROOT, "slab-g%.1f", gap);
                BufferedImage img = grab(c, label, false);
                dev.testkit.harness.Report.screenshot(c.name() + "-" + label, TAKEN.get(c.name() + "-" + label));
                Cam cam = c.onClient(EtherwarpFaceCases::camera);
                int front = 0, frontOn = 0, back = 0, backOn = 0;
                for (int k = 2; k <= 8; k++) {
                    double t = k / 10.0;
                    // front face (x = CX): its four edges
                    for (Vec3 p : new Vec3[]{new Vec3(CX, 101, CZ + t), new Vec3(CX, 102, CZ + t),
                            new Vec3(CX, 101 + t, CZ), new Vec3(CX, 101 + t, CZ + 1)}) {
                        int[] sp = cam.project(p, img.getWidth(), img.getHeight());
                        if (sp != null) {
                            front++;
                            frontOn += magentaNear(img, sp, 3) ? 1 : 0;
                        }
                    }
                    // the back top edge (x = CX + 1, y = 102), in plain view through the air above the slab
                    int[] sp = cam.project(new Vec3(CX + 1, 102, CZ + t), img.getWidth(), img.getHeight());
                    if (sp != null) {
                        back++;
                        backOn += magentaNear(img, sp, 1) ? 1 : 0;
                    }
                }
                frontSamples += front;
                backSamples += back;
                String line = String.format(Locale.ROOT, "%s %s: front edges magenta %d/%d, back top edge magenta %d/%d",
                        label, outcome, frontOn, front, backOn, back);
                c.note(line);
                if (!outcome.startsWith("drawn " + CX + ", 101, " + CZ) || front < 10 || frontOn < 0.8 * front
                        || backOn > 0) {
                    bad.add(line);
                }
            }
        } finally {
            c.onClient(mc -> {
                mc.options.keyShift.setDown(false);
                dev.testkit.compat.McCompat.setHudHidden(mc, hideWas);
                mc.options.fovEffectScale().set(fovEffectWas);
                mc.options.bobView().set(bobWas);
                return null;
            });
            for (AutoCloseable r : restore) {
                r.close();
            }
            DocDungeonsCases.command(c, "fill 330 99 330 350 108 350 minecraft:air");
            DocDungeonsCases.command(c, "clear @a");
        }
        c.check(backSamples >= 15, "only " + backSamples + " back-edge samples were on screen");
        c.check(bad.isEmpty(), bad.size() + " view(s) wrong: " + bad);
    }

    /**
     * 1324, killer560 (2026-10-15): "etherwarp on chest corners does not really show up" - on top of a chest on his island
     * only faint green slivers at its corners. The target is each non-full or block-entity block in turn, on a stone floor
     * (a chest, ender chest, trapped chest, a bottom slab, a stair, a hopper, a cauldron, a carpet, stone with a player head
     * on it - etherwarp passes through heads, so the box is on the stone and red - and a full stone block as the control),
     * seen from a one-high step 2 blocks off at two angles. Outline and Filled, magenta, are each shot against the overlay
     * off. Outline: every on-screen, unoccluded sample along the box's camera-facing edges (the block's own shape, as the
     * overlay draws it) needs at least 7 magenta pixels in the 5x5 around it (a whole line, not a sliver); at least 85% must. Filled: at least 85% of the samples inside those faces
     * must change. The chest is also shot with Full Block on.
     */
    static void nonFull(UiCase c) throws Exception {
        List<AutoCloseable> restore = new ArrayList<>();
        boolean hideWas = c.onClient(mc -> dev.testkit.compat.McCompat.hudHidden(mc));
        double fovEffectWas = c.onClient(mc -> mc.options.fovEffectScale().get());
        boolean bobWas = c.onClient(mc -> mc.options.bobView().get());
        List<String> bad = new ArrayList<>();
        int views = 0;
        // {block to place at the target, block placed above it (or null), full block on, kept as a picture}
        Object[][] targets = {
                {"minecraft:stone", null, false, true},
                {"minecraft:chest[facing=west]", null, false, true},
                {"minecraft:chest[facing=west]", null, true, false},
                {"minecraft:ender_chest[facing=west]", null, false, false},
                {"minecraft:trapped_chest[facing=west]", null, false, false},
                {"minecraft:stone_brick_slab[type=bottom]", null, false, true},
                {"minecraft:oak_stairs[facing=east]", null, false, false},
                {"minecraft:hopper", null, false, false},
                {"minecraft:cauldron", null, false, false},
                {"minecraft:white_carpet", null, false, false},
                {"minecraft:stone", "minecraft:player_head", false, true},
        };
        try {
            c.onClient(mc -> {
                restore.add(0, Mod.with(EWC, "Enabled", true));
                restore.add(0, Mod.with(EWC, "ShowWhenFailed", true));
                restore.add(0, Mod.with(EWC, "FullBlock", false));
                restore.add(0, Mod.with(EWC, "SafeColor", 0xFFFF00FF));
                restore.add(0, Mod.with(EWC, "FailedColor", 0xFFFF00FF));
                restore.add(0, Mod.with(EWC, "Style", Mod.enumValue(STYLE, "OUTLINE")));
                dev.testkit.compat.McCompat.setHudHidden(mc, true);
                mc.options.fovEffectScale().set(0.0);
                mc.options.bobView().set(false);
                return null;
            });
            DocDungeonsCases.command(c, "tp @a " + CX + " 120 " + CZ);
            c.ticks(20);
            for (Object[] t : targets) {
                for (int angle : new int[]{0, 40}) {
                    String block = (String) t[0];
                    String above = (String) t[1];
                    boolean full = (Boolean) t[2];
                    String name = block.replace("minecraft:", "").replaceAll("\\[.*", "")
                            + (above != null ? "+" + above.replace("minecraft:", "") : "") + (full ? "-fullblock" : "");
                    String label = String.format(Locale.ROOT, "%s-a%d", name, angle);
                    String line = nonFullView(c, block, above, full, angle, label, (Boolean) t[3] && angle == 0);
                    views++;
                    c.note(line);
                    if (line.contains("BAD")) {
                        bad.add(line);
                    }
                }
            }
        } finally {
            c.onClient(mc -> {
                mc.options.keyShift.setDown(false);
                dev.testkit.compat.McCompat.setHudHidden(mc, hideWas);
                mc.options.fovEffectScale().set(fovEffectWas);
                mc.options.bobView().set(bobWas);
                return null;
            });
            for (AutoCloseable r : restore) {
                r.close();
            }
            DocDungeonsCases.command(c, "fill 330 99 330 350 108 350 minecraft:air");
            DocDungeonsCases.command(c, "clear @a");
        }
        c.check(views == targets.length * 2, "only " + views + " views ran");
        c.check(bad.isEmpty(), bad.size() + " view(s) where the box did not show: " + bad);
    }

    private static String nonFullView(UiCase c, String block, String above, boolean full, int angle, String label,
                                      boolean keep) throws Exception {
        DocDungeonsCases.command(c, "tp @a " + CX + " 120 " + CZ);
        c.ticks(2);
        DocDungeonsCases.command(c, "fill 330 99 330 350 99 350 minecraft:stone");
        DocDungeonsCases.command(c, "fill 330 100 330 350 108 350 minecraft:air");
        DocDungeonsCases.command(c, "setblock " + CX + " 100 " + CZ + " " + block);
        if (above != null) {
            DocDungeonsCases.command(c, "setblock " + CX + " 101 " + CZ + " " + above);
        }
        // a one-high step 2 blocks off (horizontal distance from the block's centre ~2.6), turned `angle` towards +z
        double th = Math.toRadians(angle);
        double px = CX + 0.5 - 2.6 * Math.cos(th);
        double pz = CZ + 0.5 + 2.6 * Math.sin(th);
        int sx = (int) Math.floor(px), sz = (int) Math.floor(pz);
        DocDungeonsCases.command(c, "setblock " + sx + " 100 " + sz + " minecraft:stone");
        DocDungeonsCases.command(c, "clear @a");
        DocDungeonsCases.command(c,
                "give @a minecraft:diamond_shovel[custom_data={id:\"ASPECT_OF_THE_VOID\",ethermerge:1}]");
        DocDungeonsCases.flightOff(c);
        DocDungeonsCases.command(c, String.format(Locale.ROOT, "tp @a %.4f 101 %.4f -90 30", px, pz));
        c.onClient(mc -> {
            Mod.set(EWC, "setFullBlock", full);
            mc.player.getInventory().setSelectedSlot(0);
            mc.options.keyShift.setDown(true);
            return null;
        });
        DocDungeonsCases.await(c, label + ": crouching on the step", mc -> mc.player != null && mc.player.onGround()
                && mc.player.isCrouching() && Math.abs(mc.player.getY() - 101) < 1e-6, 100);
        net.minecraft.core.BlockPos target = new net.minecraft.core.BlockPos(CX, 100, CZ);
        // the box the overlay draws: the block's own shape bounds (or the full block), at the target
        net.minecraft.world.phys.AABB box = c.onClient(mc -> {
            var shape = mc.level.getBlockState(target).getShape(mc.level, target);
            return full || shape.isEmpty() ? new net.minecraft.world.phys.AABB(target) : shape.bounds().move(target);
        });
        Vec3 aim = new Vec3((box.minX + box.maxX) / 2, Math.min(box.maxY - 0.02, box.minY + 0.5),
                (box.minZ + box.maxZ) / 2);
        if (above != null) {
            aim = new Vec3(CX + 0.02, 100.5, CZ + 0.5); // the stone's near face, under the head
        }
        final Vec3 aimAt = aim;
        for (int i = 0; i < 10; i++) {
            c.onClient(mc -> {
                Vec3 d = aimAt.subtract(mc.player.getEyePosition());
                float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
                float pitch = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
                mc.player.setYRot(yaw);
                mc.player.setXRot(pitch);
                mc.player.yRotO = yaw;
                mc.player.xRotO = pitch;
                mc.player.setYHeadRot(yaw);
                return null;
            });
            c.ticks(1);
        }
        c.ticks(10); // a block entity's first frames
        String outcome = c.onClient(mc -> (String) Mod.staticCall(EW, "lastOutcome"));
        if (!outcome.startsWith("drawn " + CX + ", 100, " + CZ)) {
            return label + ": BAD overlay not on the target: " + outcome;
        }
        BufferedImage outline = shot(c, label, "OUTLINE");
        BufferedImage filled = shot(c, label, "FILLED");
        c.onClient(mc -> {
            Mod.set(EWC, "setEnabled", false);
            return null;
        });
        c.ticks(2);
        BufferedImage off = grab(c, label + "-off", false);
        c.onClient(mc -> {
            Mod.set(EWC, "setEnabled", true);
            Mod.set(EWC, "setStyle", Mod.enumValue(STYLE, "OUTLINE"));
            return null;
        });
        if (keep) {
            for (String s : new String[]{label + "-outline", label + "-filled", label + "-off"}) {
                dev.testkit.harness.Report.screenshot(c.name() + "-" + s, TAKEN.get(c.name() + "-" + s));
            }
        }
        Cam cam = c.onClient(EtherwarpFaceCases::camera);
        int[] edge = boxEdgeSamples(c, cam, outline, box);
        int[] fill = boxFillSamples(c, cam, filled, off, box);
        boolean ok = edge[1] >= 12 && edge[0] >= 0.85 * edge[1] && fill[1] >= 12 && fill[0] >= 0.85 * fill[1];
        return String.format(Locale.ROOT, "%s: %s %s | box %.3f..%.3f x %.3f..%.3f y %.3f..%.3f z | outline edges magenta %s,"
                        + " filled faces changed %s", label, ok ? "ok" : "BAD", outcome, box.minX - CX, box.maxX - CX,
                box.minY - 100, box.maxY - 100, box.minZ - CZ, box.maxZ - CZ, frac(edge), frac(fill));
    }

    /** {magenta within 2 px, usable} along every edge of {@code box} that bounds a face turned towards the camera. */
    private static int[] boxEdgeSamples(UiCase c, Cam cam, BufferedImage img, net.minecraft.world.phys.AABB box) {
        double[] xs = {box.minX, box.maxX}, ys = {box.minY, box.maxY}, zs = {box.minZ, box.maxZ};
        boolean[] faces = {cam.pos.x < box.minX, cam.pos.x > box.maxX, cam.pos.y < box.minY, cam.pos.y > box.maxY,
                cam.pos.z < box.minZ, cam.pos.z > box.maxZ};
        int hit = 0, usable = 0;
        // the 12 edges: along x (y,z fixed), along y (x,z fixed), along z (x,y fixed)
        for (int axis = 0; axis < 3; axis++) {
            for (int i = 0; i < 2; i++) {
                for (int j = 0; j < 2; j++) {
                    boolean front;
                    if (axis == 0) {
                        front = faces[2 + i] || faces[4 + j];
                    } else if (axis == 1) {
                        front = faces[i] || faces[4 + j];
                    } else {
                        front = faces[i] || faces[2 + j];
                    }
                    if (!front) {
                        continue;
                    }
                    for (int k = 1; k <= 9; k++) {
                        double t = k / 10.0;
                        Vec3 p = axis == 0 ? new Vec3(box.minX + t * (box.maxX - box.minX), ys[i], zs[j])
                                : axis == 1 ? new Vec3(xs[i], box.minY + t * (box.maxY - box.minY), zs[j])
                                : new Vec3(xs[i], ys[j], box.minZ + t * (box.maxZ - box.minZ));
                        int[] px = cam.project(p, img.getWidth(), img.getHeight());
                        Vec3 towards = p.add(cam.pos.subtract(p).normalize().scale(0.04));
                        if (px == null || !clear(c, cam.pos, towards)) {
                            continue;
                        }
                        usable++;
                        // a whole line, not the half of it that sticks out past a model drawn over it: a 2 px line
                        // across a 5x5 window colours about 10 of its pixels, the outer half alone about 5
                        if (magentaCount(img, px, 2) >= 7) {
                            hit++;
                        }
                    }
                }
            }
        }
        return new int[]{hit, usable};
    }

    /** {changed, usable} over a 5x5 grid inside each face of {@code box} turned towards the camera. */
    private static int[] boxFillSamples(UiCase c, Cam cam, BufferedImage on, BufferedImage off,
                                        net.minecraft.world.phys.AABB box) {
        int changed = 0, usable = 0;
        for (int f = 0; f < 6; f++) {
            int[] n = FACES[f];
            double[] lo = {box.minX, box.minY, box.minZ}, hi = {box.maxX, box.maxY, box.maxZ};
            int axis = n[0] != 0 ? 0 : n[1] != 0 ? 1 : 2;
            double plane = n[axis] > 0 ? hi[axis] : lo[axis];
            double camC = axis == 0 ? cam.pos.x : axis == 1 ? cam.pos.y : cam.pos.z;
            if ((camC - plane) * n[axis] <= 1e-3) {
                continue;
            }
            int a1 = (axis + 1) % 3, a2 = (axis + 2) % 3;
            for (int i = 0; i < 5; i++) {
                for (int j = 0; j < 5; j++) {
                    double[] p = new double[3];
                    p[axis] = plane;
                    p[a1] = lo[a1] + (0.15 + 0.7 * i / 4.0) * (hi[a1] - lo[a1]);
                    p[a2] = lo[a2] + (0.15 + 0.7 * j / 4.0) * (hi[a2] - lo[a2]);
                    Vec3 v = new Vec3(p[0], p[1], p[2]);
                    int[] px = cam.project(v, on.getWidth(), on.getHeight());
                    Vec3 out = v.add(n[0] * 0.03, n[1] * 0.03, n[2] * 0.03);
                    if (px == null || !clear(c, cam.pos, out)) {
                        continue;
                    }
                    usable++;
                    if (dev.testkit.gametest.ui.MapHeadCases.changed(on.getRGB(px[0], px[1]), off.getRGB(px[0], px[1]))) {
                        changed++;
                    }
                }
            }
        }
        return new int[]{changed, usable};
    }

    private static int edgeChecks;
    private static boolean probeHooked;
    private static volatile String PROBE = "none";

    private static boolean magenta(int rgb) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        return r > 170 && b > 170 && g < 90;
    }

    private static boolean magentaNear(BufferedImage img, int[] p, int rad) {
        for (int dy = -rad; dy <= rad; dy++) {
            for (int dx = -rad; dx <= rad; dx++) {
                int x = p[0] + dx, y = p[1] + dy;
                if (x >= 0 && y >= 0 && x < img.getWidth() && y < img.getHeight() && magenta(img.getRGB(x, y))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int magentaCount(BufferedImage img, int[] p, int rad) {
        int n = 0;
        for (int dy = -rad; dy <= rad; dy++) {
            for (int dx = -rad; dx <= rad; dx++) {
                int x = p[0] + dx, y = p[1] + dy;
                if (x >= 0 && y >= 0 && x < img.getWidth() && y < img.getHeight() && magenta(img.getRGB(x, y))) {
                    n++;
                }
            }
        }
        return n;
    }

    private static boolean goldish(int rgb) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        return r > 70 && g > 50 && r - b > 40;
    }

    private static int[] goldBox(BufferedImage img) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
                if (r > 80 && g > 65 && b < 60 && r - b > 50) {
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
        }
        return x1 < 0 ? null : new int[]{x0, y0, x1, y1};
    }

    private static int[] magentaBox(BufferedImage img) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (magenta(img.getRGB(x, y))) {
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
        }
        return x1 < 0 ? null : new int[]{x0, y0, x1, y1};
    }

    /** Faces the camera was pressed against (tint expected), over the whole run: must not be zero. */
    private static int tintedTotal;

    private record Result(String line, int checked, List<String> missing) {
    }

    private static Result view(UiCase c, View v, int angle, double gap) throws Exception {
        String label = String.format(Locale.ROOT, "%s-a%d-g%.1f", v.name().toLowerCase(Locale.ROOT), angle, gap);
        // horizontal: from the -x side, turned `angle` degrees towards +z; r puts his box `gap` from the target's
        double th = Math.toRadians(angle);
        double rr = (0.8 + gap + 1e-3) / Math.max(Math.cos(th), Math.sin(th));
        double px = CX + 0.5 - rr * Math.cos(th);
        double pz = CZ + 0.5 + rr * Math.sin(th);
        double py = v == View.ABOVE ? 103 : 100;

        DocDungeonsCases.command(c, "tp @a " + CX + " 120 " + CZ);
        c.ticks(2);
        DocDungeonsCases.command(c, "fill 330 99 330 350 99 350 minecraft:stone");
        DocDungeonsCases.command(c, "fill 330 100 330 350 108 350 minecraft:air");
        DocDungeonsCases.command(c, "setblock " + CX + " " + v.ty + " " + CZ + " minecraft:stone");
        if (v == View.WALL) {
            DocDungeonsCases.command(c, "setblock " + CX + " 100 " + CZ + " minecraft:stone");
        }
        if (v == View.ABOVE) {
            int bx = (int) Math.floor(px), bz = (int) Math.floor(pz);
            DocDungeonsCases.command(c, "fill " + bx + " 100 " + bz + " " + bx + " 102 " + bz + " minecraft:stone");
        }
        DocDungeonsCases.command(c, "clear @a");
        DocDungeonsCases.command(c,
                "give @a minecraft:diamond_shovel[custom_data={id:\"ASPECT_OF_THE_VOID\",ethermerge:1}]");
        DocDungeonsCases.flightOff(c);
        DocDungeonsCases.command(c, String.format(Locale.ROOT, "tp @a %.4f %.4f %.4f -90 0", px, py, pz));
        c.onClient(mc -> {
            mc.player.getInventory().setSelectedSlot(0);
            mc.options.keyShift.setDown(true);
            return null;
        });
        DocDungeonsCases.await(c, label + ": crouching on the ground", mc -> mc.player != null && mc.player.onGround()
                && mc.player.isCrouching() && Math.abs(mc.player.getY() - py) < 1e-6, 100);
        Vec3 aim = new Vec3(CX + 0.5, v.ty + 0.5, CZ + 0.5);
        for (int i = 0; i < 15; i++) {
            c.onClient(mc -> {
                Vec3 eye = mc.player.getEyePosition();
                Vec3 d = aim.subtract(eye);
                float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
                float pitch = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
                mc.player.setYRot(yaw);
                mc.player.setXRot(pitch);
                mc.player.yRotO = yaw;
                mc.player.xRotO = pitch;
                mc.player.setYHeadRot(yaw);
                return null;
            });
            c.ticks(1);
        }
        String want = "drawn " + CX + ", " + v.ty + ", " + CZ + " ok";
        String outcome = c.onClient(mc -> (String) Mod.staticCall(EW, "lastOutcome"));
        if (!outcome.startsWith(want)) {
            return new Result(label + ": NOT TARGETED, overlay said " + outcome, 0,
                    List.of(label + " overlay not on the target: " + outcome));
        }

        BufferedImage filled = shot(c, label, "FILLED");
        BufferedImage outline = shot(c, label, "OUTLINE");
        c.onClient(mc -> {
            Mod.set(EWC, "setEnabled", false);
            return null;
        });
        c.ticks(2);
        BufferedImage off = grab(c, label + "-off", false);
        c.onClient(mc -> {
            Mod.set(EWC, "setEnabled", true);
            return null;
        });

        Cam cam = c.onClient(EtherwarpFaceCases::camera);
        int bx = CX, by = v.ty, bz = CZ;
        StringBuilder line = new StringBuilder(String.format(Locale.ROOT,
                "%s: cam %.2f,%.2f,%.2f yaw %.1f pitch %.1f |", label, cam.pos.x, cam.pos.y, cam.pos.z, cam.yaw, cam.pitch));
        List<String> miss = new ArrayList<>();
        int checked = 0;
        int tinted = 0;
        for (int f = 0; f < 6; f++) {
            int[] n = FACES[f];
            Vec3 faceCentre = new Vec3(bx + 0.5 + n[0] * 0.5, by + 0.5 + n[1] * 0.5, bz + 0.5 + n[2] * 0.5);
            if (cam.pos.subtract(faceCentre).dot(new Vec3(n[0], n[1], n[2])) <= 1e-3) {
                continue; // faces away from the camera
            }
            int[] fs = filledSamples(c, cam, filled, off, bx, by, bz, f);
            int[] os = outlineSamples(c, cam, outline, off, bx, by, bz, f);
            // the Outline shot's face interior: tinted only when he is pressed against that face (mod ew-nearface)
            int[] oi = filledSamples(c, cam, outline, off, bx, by, bz, f);
            boolean tintExpected = pressedAgainst(cam.pos, bx, by, bz, f);
            int[] clean = tintExpected ? null : filledSamples(c, cam, outline, off, bx, by, bz, f, 5);
            line.append(String.format(Locale.ROOT, " %s filled %s outline %s (inside %s);", FACE_NAMES[f], frac(fs),
                    frac(os), frac(oi)));
            if (tintExpected) {
                // Pressed against it (within 0.85 of the face, inside its footprint): just the box's own edges, no
                // shading and nothing extra. killer560 (2026-10-13): "I want the full outline and if you can't see an
                // Edge then don't draw that part of it" (after first a red tint, then a smaller outline on the face).
                tinted++;
                tintedTotal++;
                checked++;
                int[] ch = changedPixels(outline, off);
                double frac = ch[0] / (double) ch[1];
                if (frac > 0.03) {
                    miss.add(label + " " + FACE_NAMES[f] + " outline: pressed against the face, " + ch[0] + " pixel(s) changed ("
                            + String.format(Locale.ROOT, "%.1f%%", 100 * frac) + ") - want only the box's own edges, no shading");
                }
            } else if (clean[1] >= 4 && clean[0] > 0.05 * clean[1]) {
                miss.add(label + " " + FACE_NAMES[f] + " outline: face tinted while not pressed against it "
                        + frac(clean) + " (the outline must look as it always did)");
            }
            if (fs[1] >= 4) {
                checked++;
                if (fs[0] < 0.9 * fs[1]) {
                    miss.add(label + " " + FACE_NAMES[f] + " filled " + frac(fs));
                }
            }
            if (os[1] >= 4) {
                checked++;
                if (os[0] < 0.8 * os[1]) {
                    miss.add(label + " " + FACE_NAMES[f] + " outline " + frac(os));
                }
            }
        }
        if (!miss.isEmpty() || angle == 0 && (gap == 0.0 || gap == 2.0)) {
            keep(c, label + "-filled", filled);
            keep(c, label + "-outline", outline);
            keep(c, label + "-off", off);
        }
        line.append(" tinted ").append(tinted);
        return new Result(line.toString(), checked, miss);
    }

    /** {changed, total} pixels between two shots. */
    private static int[] changedPixels(BufferedImage on, BufferedImage off) {
        int changed = 0;
        for (int y = 0; y < on.getHeight(); y++) {
            for (int x = 0; x < on.getWidth(); x++) {
                if (dev.testkit.gametest.ui.MapHeadCases.changed(on.getRGB(x, y), off.getRGB(x, y))) {
                    changed++;
                }
            }
        }
        return new int[]{changed, on.getWidth() * on.getHeight()};
    }

    /** Pressed against face f: within 0.85 of its plane, in front of it, inside its footprint. */
    private static boolean pressedAgainst(Vec3 cam, int bx, int by, int bz, int f) {
        int[] n = FACES[f];
        double[] c = {cam.x - bx, cam.y - by, cam.z - bz};
        for (int axis = 0; axis < 3; axis++) {
            if (n[axis] != 0) {
                double dist = n[axis] > 0 ? c[axis] - 1 : -c[axis];
                if (dist <= 0 || dist > 0.85) {
                    return false;
                }
            } else if (c[axis] < 0 || c[axis] > 1) {
                return false;
            }
        }
        return true;
    }

    private static String frac(int[] s) {
        return s[1] == 0 ? "n/a" : String.format(Locale.ROOT, "%d/%d", s[0], s[1]);
    }

    // ---- sampling ---------------------------------------------------------------------------------------------

    /** {changed, usable} over a 7x7 grid inside face f. */
    private static int[] filledSamples(UiCase c, Cam cam, BufferedImage on, BufferedImage off, int bx, int by, int bz,
                                       int f) {
        return filledSamples(c, cam, on, off, bx, by, bz, f, 0);
    }

    /** As above, skipping samples within {@code edgePx} pixels of the face's projected edges (where the outline is). */
    private static int[] filledSamples(UiCase c, Cam cam, BufferedImage on, BufferedImage off, int bx, int by, int bz,
                                       int f, int edgePx) {
        int[] n = FACES[f];
        double[][] corners = new double[4][];
        double[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int k = 0; k < 4; k++) {
            corners[k] = cam.projectRaw(facePoint(bx, by, bz, n, uv[k][0], uv[k][1]), on.getWidth(), on.getHeight());
        }
        int changed = 0, usable = 0;
        for (int i = 0; i < 7; i++) {
            for (int j = 0; j < 7; j++) {
                double a = 0.12 + 0.76 * i / 6.0, b = 0.12 + 0.76 * j / 6.0;
                Vec3 p = facePoint(bx, by, bz, n, a, b);
                Vec3 out = p.add(n[0] * 0.02, n[1] * 0.02, n[2] * 0.02);
                int[] px = cam.project(p, on.getWidth(), on.getHeight());
                if (px == null || !clear(c, cam.pos, out) || edgePx > 0 && nearEdge(corners, px, edgePx)) {
                    continue;
                }
                usable++;
                if (dev.testkit.gametest.ui.MapHeadCases.changed(on.getRGB(px[0], px[1]), off.getRGB(px[0], px[1]))) {
                    changed++;
                }
            }
        }
        return new int[]{changed, usable};
    }

    /** {changed, usable} along face f's four edges (any changed pixel within 2 px of the projected edge). */
    private static int[] outlineSamples(UiCase c, Cam cam, BufferedImage on, BufferedImage off, int bx, int by, int bz,
                                        int f) {
        int[] n = FACES[f];
        int changed = 0, usable = 0;
        double[][] edges = {{0, -1}, {1, -1}, {-1, 0}, {-1, 1}}; // a fixed at 0/1 or b fixed at 0/1
        for (double[] e : edges) {
            for (int k = 1; k <= 9; k++) {
                double t = k / 10.0;
                double a = e[0] < 0 ? t : e[0];
                double b = e[1] < 0 ? t : e[1];
                Vec3 p = facePoint(bx, by, bz, n, a, b);
                // just outside the box at that edge: out along this face's normal and the neighbouring face's
                Vec3 centre = new Vec3(bx + 0.5, by + 0.5, bz + 0.5);
                Vec3 out = p.add(p.subtract(centre).normalize().scale(0.03));
                int[] px = cam.project(p, on.getWidth(), on.getHeight());
                if (px == null || !clear(c, cam.pos, out)) {
                    continue;
                }
                usable++;
                boolean hit = false;
                for (int dy = -2; dy <= 2 && !hit; dy++) {
                    for (int dx = -2; dx <= 2 && !hit; dx++) {
                        int x = px[0] + dx, y = px[1] + dy;
                        if (x >= 0 && y >= 0 && x < on.getWidth() && y < on.getHeight()
                                && dev.testkit.gametest.ui.MapHeadCases.changed(on.getRGB(x, y), off.getRGB(x, y))) {
                            hit = true;
                        }
                    }
                }
                if (hit) {
                    changed++;
                }
            }
        }
        return new int[]{changed, usable};
    }

    private static boolean nearEdge(double[][] corners, int[] px, int edgePx) {
        for (int k = 0; k < 4; k++) {
            double[] a = corners[k], b = corners[(k + 1) % 4];
            if (a == null || b == null) {
                continue;
            }
            double dx = b[0] - a[0], dy = b[1] - a[1];
            double len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px[0] - a[0]) * dx + (px[1] - a[1]) * dy) / len2));
            double ex = a[0] + t * dx - px[0], ey = a[1] + t * dy - px[1];
            if (ex * ex + ey * ey <= edgePx * edgePx) {
                return true;
            }
        }
        return false;
    }

    /** A point on face f of block (bx,by,bz), at in-plane coordinates (a, b) in 0..1. */
    private static Vec3 facePoint(int bx, int by, int bz, int[] n, double a, double b) {
        if (n[0] != 0) {
            return new Vec3(bx + (n[0] > 0 ? 1 : 0), by + a, bz + b);
        }
        if (n[1] != 0) {
            return new Vec3(bx + a, by + (n[1] > 0 ? 1 : 0), bz + b);
        }
        return new Vec3(bx + a, by + b, bz + (n[2] > 0 ? 1 : 0));
    }

    /** Nothing solid between the camera and {@code to} (a point just outside the target). */
    private static boolean clear(UiCase c, Vec3 from, Vec3 to) {
        return c.onClient(mc -> mc.level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                mc.player)).getType() == HitResult.Type.MISS);
    }

    // ---- camera -----------------------------------------------------------------------------------------------

    private record Cam(Vec3 pos, float yaw, float pitch, double fov) {
        /** Screen pixel of a world point, or null if behind the camera or within 4 px of the edge. */
        double[] projectRaw(Vec3 p, int w, int h) {
            Vec3 f = Vec3.directionFromRotation(pitch, yaw);
            Vec3 r = f.cross(new Vec3(0, 1, 0)).normalize();
            Vec3 u = r.cross(f);
            Vec3 d = p.subtract(pos);
            double z = d.dot(f);
            if (z < 0.06) {
                return null;
            }
            double t = Math.tan(Math.toRadians(fov) / 2);
            return new double[]{(d.dot(r) / (z * t * ((double) w / h)) + 1) / 2 * w, (1 - d.dot(u) / (z * t)) / 2 * h};
        }

        int[] project(Vec3 p, int w, int h) {
            Vec3 f = Vec3.directionFromRotation(pitch, yaw);
            Vec3 r = f.cross(new Vec3(0, 1, 0)).normalize();
            Vec3 u = r.cross(f);
            Vec3 d = p.subtract(pos);
            double z = d.dot(f);
            if (z < 0.06) {
                return null;
            }
            double t = Math.tan(Math.toRadians(fov) / 2);
            double nx = d.dot(r) / (z * t * ((double) w / h));
            double ny = d.dot(u) / (z * t);
            int sx = (int) Math.round((nx + 1) / 2 * w);
            int sy = (int) Math.round((1 - ny) / 2 * h);
            return sx < 4 || sy < 4 || sx >= w - 4 || sy >= h - 4 ? null : new int[]{sx, sy};
        }
    }

    private static Cam camera(Minecraft mc) {
        Object camera = null;
        for (String name : new String[]{"getMainCamera", "mainCamera"}) {
            try {
                camera = mc.gameRenderer.getClass().getMethod(name).invoke(mc.gameRenderer);
                break;
            } catch (NoSuchMethodException ignored) {
                // the other version's name
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
        if (camera == null) {
            throw new AssertionError("GameRenderer has no camera accessor");
        }
        try {
            Vec3 pos = (Vec3) camera.getClass().getMethod("position").invoke(camera);
            float yaw = (Float) camera.getClass().getMethod("yRot").invoke(camera);
            float pitch = (Float) camera.getClass().getMethod("xRot").invoke(camera);
            return new Cam(pos, yaw, pitch, mc.options.fov().get());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no Camera.position()/yRot()/xRot()", e);
        }
    }

    // ---- shots ------------------------------------------------------------------------------------------------

    private static BufferedImage shot(UiCase c, String label, String style) {
        c.onClient(mc -> {
            Mod.set(EWC, "setStyle", Mod.enumValue(STYLE, style));
            return null;
        });
        c.ticks(2);
        return grab(c, label + "-" + style.toLowerCase(Locale.ROOT), false);
    }

    private static final java.util.Map<String, Path> TAKEN = new java.util.HashMap<>();

    private static BufferedImage grab(UiCase c, String label, boolean keep) {
        String name = c.name() + "-" + label;
        Path taken = SODIUM ? vanillaShot(c, name) : c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        TAKEN.put(name, taken);
        try {
            return javax.imageio.ImageIO.read(taken.toFile());
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read " + taken + ": " + e);
        }
    }

    /**
     * With Sodium loaded the gametest API's screenshot (a level render outside the frame) throws "Global terrain uniforms
     * have not been updated" (Sodium 0.9.2-alpha.4, 2026-10-13), so read back the main target the last real frame drew.
     */
    private static final boolean SODIUM = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");

    private static Path vanillaShot(UiCase c, String name) {
        c.ticks(2);
        Path out;
        try {
            out = java.nio.file.Files.createTempFile("ew-face-", ".png");
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<String> err = new java.util.concurrent.atomic.AtomicReference<>();
        c.onClient(mc -> {
            // 26.1.2 only (the Sodium jar here is 26.1.2's): 26.2's Minecraft has no getMainRenderTarget (javap)
            com.mojang.blaze3d.pipeline.RenderTarget target;
            try {
                target = (com.mojang.blaze3d.pipeline.RenderTarget) Minecraft.class.getMethod("getMainRenderTarget")
                        .invoke(mc);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("no Minecraft.getMainRenderTarget on this version", e);
            }
            net.minecraft.client.Screenshot.takeScreenshot(target, img -> {
                try (img) {
                    img.writeToFile(out);
                } catch (Exception e) {
                    err.set(e.toString());
                } finally {
                    done.set(true);
                }
            });
            return null;
        });
        c.ctx().waitFor(mc -> done.get(), 100);
        if (err.get() != null) {
            throw new AssertionError("screenshot of " + name + " failed: " + err.get());
        }
        return out;
    }

    private static void keep(UiCase c, String label, BufferedImage img) {
        String name = c.name() + "-" + label;
        Path taken = TAKEN.get(name);
        if (taken != null) {
            dev.testkit.harness.Report.screenshot(name, taken);
        }
    }
}

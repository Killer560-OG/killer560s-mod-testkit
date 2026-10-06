package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 388-ui-breaker-display: Breaker Aura's Display (Highlight / Waypoint) and Box Style (Outline / Fill / Filled
 * Outline) each actually draw, judged from screenshots (mod 2026-10-06, breaker-render).
 *
 * <p>The scene, built beside the player on the superflat, facing +x: pick A three blocks ahead (in reach, in sight),
 * a stone wall at +7, pick B behind it at +9 (hidden), and pick C forty blocks out (in sight, far out of reach, well
 * inside the client's render distance of 5 chunks). Breaker Aura stays OFF and Edit Mode ON, which draws the picks
 * and breaks nothing; the dungeon gate is the mod's own /killer560 sim override, as in BreakerAuraTests.
 *
 * <p>Counted: orange pixels over the whole frame with the GUI hidden, minus a frame with no picks. What must hold:
 * every display x style draws; Waypoint shows B through the wall and Highlight does not (Highlight is the original,
 * depth-tested look); Waypoint draws C; Fill and Filled Outline cover more than Outline.
 */
final class BreakerDisplayCases {

    private static final String CFG = "dungeonextras.DungeonExtrasConfig";
    private static final String DISPLAY = "dungeonextras.DungeonExtrasConfig$BreakerDisplay";
    private static final String STYLE = "dungeonextras.DungeonExtrasConfig$BreakerStyle";
    private static final String STORE = "dungeonextras.BreakerAuraStore";
    private static final String[] STYLES = {"OUTLINE", "FILL", "FILLED_OUTLINE"};

    private BreakerDisplayCases() {
    }

    static void run(UiCase c) throws Exception {
        if (!Mod.isCheat()) {
            c.note("legit jar: Breaker Aura is not in it - nothing to draw");
            return;
        }
        if (!Mod.has(DISPLAY)) {
            c.problem("this jar has no Breaker Aura Display setting (mod before breaker-render)");
            return;
        }
        // Where to build: the block column the player is over, standing on the first solid block below him. Read on
        // the SERVER thread: a ServerLevel read from the render thread can load a chunk and deadlock the lockstep
        // (testkit CLAUDE.md, 99-sim-im) - the first version of this case froze the client exactly there.
        BlockPos at = c.onClient(mc -> mc.player.blockPosition());
        int[] base = new int[3];
        server(c, level -> {
            int y = at.getY();
            while (y > level.getMinY() && level.getBlockState(new BlockPos(at.getX(), y - 1, at.getZ())).isAir()) {
                y--;
            }
            base[0] = at.getX();
            base[1] = y;
            base[2] = at.getZ();
        });
        int bx = base[0], gy = base[1], bz = base[2];
        BlockPos a = new BlockPos(bx + 3, gy + 1, bz - 1);
        BlockPos b = new BlockPos(bx + 9, gy + 1, bz + 1);
        // 37 degrees left of the view line: clear of A (7-27 degrees left) and of the wall (right), so it is in open
        // sight. At -10 it sat exactly behind A and Highlight rightly drew nothing of it.
        BlockPos far = new BlockPos(bx + 40, gy + 1, bz - 30);
        List<BlockPos> built = new ArrayList<>(List.of(a, b, far));
        for (int y = gy; y <= gy + 3; y++) {
            for (int z = bz; z <= bz + 3; z++) {
                built.add(new BlockPos(bx + 7, y, z));
            }
        }
        double[] startPos = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        boolean hideGuiWas = c.onClient(mc -> mc.options.hideGui);
        c.note("scene at " + bx + "," + gy + "," + bz + ": pick A " + a.toShortString() + " (in sight, in reach), wall x="
                + (bx + 7) + ", pick B " + b.toShortString() + " (behind it), pick C " + far.toShortString()
                + " (40 out)");
        Object cfg = Mod.cfg(CFG);
        Object displayWas = Mod.call(cfg, "getBreakerAuraDisplay");
        Object styleWas = Mod.call(cfg, "getBreakerAuraStyle");
        boolean overrideOn = false;
        try {
            server(c, level -> {
                for (BlockPos p : built) {
                    level.setBlockAndUpdate(p, Blocks.STONE.defaultBlockState());
                }
            });
            c.onClient(mc -> {
                var server = mc.getSingleplayerServer();
                var uuid = mc.player.getUUID();
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayer(uuid);
                    sp.teleportTo(server.overworld(), bx + 0.5, gy, bz + 0.5,
                            java.util.Set.<net.minecraft.world.entity.Relative>of(), -90f, 0f, false);
                });
                mc.player.setYRot(-90f);
                mc.player.setXRot(0f);
                mc.options.hideGui = true;
                return null;
            });
            c.ctx().waitFor(mc -> Math.abs(mc.player.getX() - (bx + 0.5)) < 0.01
                    && Math.abs(mc.player.getZ() - (bz + 0.5)) < 0.01, 100);
            c.ctx().waitFor(mc -> !mc.level.getBlockState(far).isAir() && !mc.level.getBlockState(b).isAir(), 200);
            c.onClient(mc -> {
                Mod.call(cfg, "setBreakerAuraEnabled", false);
                Mod.call(cfg, "setBreakerAuraEditMode", true);
                return null;
            });
            overrideOn = c.onClient(mc -> {
                if (!(Boolean) Mod.staticCall("secrets.DungeonState", "isInDungeon")) {
                    Mod.staticCall("secrets.DungeonState", "toggleSimOverride");
                    return true;
                }
                return false;
            });
            c.check(c.onClient(mc -> (Boolean) Mod.staticCall("secrets.DungeonState", "isInDungeon")),
                    "DungeonState.isInDungeon() is still false - the picks would never draw");

            baseImg = null;
            int baseline = shot(c, "none", List.of(), "HIGHLIGHT", "OUTLINE");
            c.note("baseline (no picks): " + baseline + " orange px");
            int[][] all = new int[2][3];
            String[] displays = {"HIGHLIGHT", "WAYPOINT"};
            for (int d = 0; d < 2; d++) {
                for (int s = 0; s < 3; s++) {
                    all[d][s] = shot(c, displays[d] + "-" + STYLES[s], List.of(a, b, far), displays[d], STYLES[s])
                            - baseline;
                    c.note(String.format(Locale.ROOT, "%s / %s, all three picks: %d orange px over baseline",
                            displays[d], STYLES[s], all[d][s]));
                    if (all[d][s] < 40) {
                        c.problem(displays[d] + " / " + STYLES[s] + " drew " + all[d][s] + " px - it did not draw");
                    }
                }
                if (all[d][1] <= all[d][0] || all[d][2] <= all[d][0]) {
                    c.problem(displays[d] + ": Fill (" + all[d][1] + ") and Filled Outline (" + all[d][2]
                            + ") should each cover more than Outline (" + all[d][0] + ")");
                }
            }
            int hlHidden = shot(c, "HIGHLIGHT-hidden-only", List.of(b), "HIGHLIGHT", "OUTLINE") - baseline;
            int wpHidden = shot(c, "WAYPOINT-hidden-only", List.of(b), "WAYPOINT", "OUTLINE") - baseline;
            int wpHiddenFill = shot(c, "WAYPOINT-hidden-only-fill", List.of(b), "WAYPOINT", "FILL") - baseline;
            int hlFar = shot(c, "HIGHLIGHT-far-only", List.of(far), "HIGHLIGHT", "OUTLINE") - baseline;
            int wpFar = shot(c, "WAYPOINT-far-only", List.of(far), "WAYPOINT", "OUTLINE") - baseline;
            c.note("pick B behind the wall alone: Highlight " + hlHidden + " px, Waypoint " + wpHidden
                    + " px (Fill " + wpHiddenFill + ")");
            c.note("pick C 40 blocks out alone: Highlight " + hlFar + " px, Waypoint " + wpFar + " px");
            if (wpHidden < 10 || wpHiddenFill < 10) {
                c.problem("Waypoint did not draw the pick behind the wall (" + wpHidden + " / " + wpHiddenFill + " px)");
            }
            if (hlHidden > 5) {
                c.problem("Highlight drew the pick behind the wall (" + hlHidden + " px) - it should be depth-tested");
            }
            if (hlFar < 5) {
                c.problem("Highlight did not draw the pick 40 blocks out, in open sight (" + hlFar + " px)");
            }
            if (wpFar < 5) {
                c.problem("Waypoint did not draw the pick 40 blocks out (" + wpFar + " px)");
            }
        } finally {
            boolean off = overrideOn;
            try {
                c.onClient(mc -> {
                    setPicks(List.of());
                    Mod.call(cfg, "setBreakerAuraEditMode", false);
                    Mod.call(cfg, "setBreakerAuraDisplay", displayWas);
                    Mod.call(cfg, "setBreakerAuraStyle", styleWas);
                    if (off && (Boolean) Mod.staticCall("secrets.DungeonState", "isInDungeon")) {
                        Mod.staticCall("secrets.DungeonState", "toggleSimOverride");
                    }
                    mc.options.hideGui = hideGuiWas;
                    var server = mc.getSingleplayerServer();
                    var uuid = mc.player.getUUID();
                    server.execute(() -> {
                        for (BlockPos p : built) {
                            server.overworld().setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
                        }
                        var sp = server.getPlayerList().getPlayer(uuid);
                        sp.teleportTo(startPos[0], startPos[1], startPos[2]);
                    });
                    return null;
                });
                c.ticks(5);
            } catch (Throwable t) {
                c.note("cleanup: " + UiCase.describe(t));
            }
        }
    }

    /** Set picks + display + style, let a few frames draw, screenshot, and count the orange pixels in it. */
    private static int shot(UiCase c, String label, List<BlockPos> picks, String display, String style) {
        c.onClient(mc -> {
            setPicks(picks);
            Object cfg = Mod.cfg(CFG);
            Mod.call(cfg, "setBreakerAuraDisplay", Mod.enumValue(DISPLAY, display));
            Mod.call(cfg, "setBreakerAuraStyle", Mod.enumValue(STYLE, style));
            mc.player.setYRot(-90f);
            mc.player.setXRot(0f);
            return null;
        });
        c.ticks(6);
        String name = c.name() + "-" + label;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        Path kept = dev.testkit.harness.Report.screenshot(name, taken);
        int n = countOrange(c, taken);
        c.note(label + ": " + n + " orange px -> " + (kept != null ? kept : taken));
        return n;
    }

    /** The no-picks frame every later one is compared against (set by the first shot of a run). */
    private static java.awt.image.BufferedImage baseImg;

    /**
     * Pixels that CHANGED from the no-picks frame and moved towards Breaker Aura's orange (1, 0.55 or 0.30, 0).
     * A fixed colour window was tried first and missed the dim fill over dark stone entirely (it reads as a dull
     * red-brown) while the sunrise sky behind the scene is itself peach, so the frame is diffed instead.
     */
    private static int countOrange(UiCase c, Path shot) {
        int n = 0;
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(shot.toFile());
            if (baseImg == null || baseImg.getWidth() != img.getWidth() || baseImg.getHeight() != img.getHeight()) {
                baseImg = img;
                return 0;
            }
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int rgb = img.getRGB(x, y);
                    int was = baseImg.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, bl = rgb & 0xFF;
                    int r0 = (was >> 16) & 0xFF, g0 = (was >> 8) & 0xFF, b0 = was & 0xFF;
                    int dr = r - r0, db = bl - b0;
                    // Redder against blue than it was, by a clear margin, and green under 3/4 of red now: a 1 px
                    // shift of the far stone block's edge uncovers peach sunrise sky (198,169,139, g/r 0.85), while
                    // the faintest fill (0.3 over stone, ~140,95,60) is 0.68 and a line 0.55.
                    if (dr - db > 40 && g * 4 < r * 3 && r - bl > 30 && Math.abs(dr) + Math.abs(g - g0) + Math.abs(db) > 40) {
                        n++;
                    }
                }
            }
        } catch (java.io.IOException e) {
            c.problem("could not read " + shot + ": " + e);
        }
        return n;
    }

    /** Replace the active Breaker Aura config's picks (client thread). */
    private static void setPicks(List<BlockPos> picks) {
        Object store = Mod.staticCall(STORE, "getInstance");
        Mod.call(store, "clear");
        for (BlockPos p : picks) {
            Mod.call(store, "addPick", p.getX() + "," + p.getY() + "," + p.getZ());
        }
    }

    private interface LevelJob {
        void run(net.minecraft.server.level.ServerLevel level);
    }

    /** Run on the integrated server and wait for it (never join() from here - see the testkit CLAUDE.md). */
    private static void server(UiCase c, LevelJob job) {
        AtomicBoolean done = new AtomicBoolean();
        c.onClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                try {
                    job.run(server.overworld());
                } finally {
                    done.set(true);
                }
            });
            return null;
        });
        c.ctx().waitFor(mc -> done.get(), 200);
    }
}

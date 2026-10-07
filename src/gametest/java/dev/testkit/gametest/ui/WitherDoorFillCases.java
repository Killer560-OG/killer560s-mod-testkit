package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 390-ui-wither-doors-fill: Wither Doors' Style (Outline / Fill / Filled Outline), Fill Opacity and Fill Color (mod
 * 2026-10-07, door-fill), judged from screenshots.
 *
 * <p>The scene: a real locked wither door where the mod's door scanner looks for one - grid cell (1, 0), anchor
 * (-169, 69, -185), coal 3 wide x 4 high x 3 deep with a roof block at y 73 so the column's top layer reads 73 - and
 * the player 5.5 blocks in front of it, facing +x straight at its face. Wither Doors gates on a Hypixel/p3sim address
 * and on being in a dungeon (not the boss): the singleplayer connection's ServerData is labelled "mc.hypixel.net" by
 * reflection for the case, and DungeonState.setRoomSim(true) says "in a clear". Both are put back in the finally.
 *
 * <p>Every frame is diffed against a no-draw frame (Wither Doors off) of the same scene; a pixel counts only above a
 * summed RGB delta of 30 (testkit CLAUDE.md, 388/389). The door's face on screen is the bounding box of the Fill
 * frame's changed pixels; its INTERIOR is the middle half of that box, which no outline edge reaches. Checked:
 * Outline changes edge pixels and (almost) nothing inside; Fill and Filled Outline change nearly every interior pixel,
 * towards the door's colour, at the chosen opacity (the least-squares alpha of each pixel against that colour,
 * averaged); the fill follows the key state (green with a Wither Key) and a Custom Fill Color; Through Walls (cheat
 * jar) draws the fill through a stone wall that hides it otherwise; and the settings survive a config reload.
 */
final class WitherDoorFillCases {

    private static final String CFG = "witherdoors.WitherDoorsConfig";
    private static final String STYLE = "witherdoors.WitherDoorsConfig$Style";
    private static final String FEATURE = "witherdoors.WitherDoorsFeature";

    /** The door the scanner finds: grid (1, 0) = START + 16 on x, START on z (WitherDoorScanner). */
    private static final int DX = -169;
    private static final int DZ = -185;
    private static final int RED = 0xFF0000;
    private static final int GREEN = 0x00FF00;
    private static final int CUSTOM = 0x2060FF;

    private WitherDoorFillCases() {
    }

    static void run(UiCase c) throws Exception {
        if (!Mod.has(STYLE)) {
            c.problem("this jar has no Wither Doors Style setting (mod before door-fill)");
            return;
        }
        boolean cheat = Mod.isCheat();
        List<BlockPos> door = new ArrayList<>();
        for (int x = DX - 1; x <= DX + 1; x++) {
            for (int y = 69; y <= 72; y++) {
                for (int z = DZ - 1; z <= DZ + 1; z++) {
                    door.add(new BlockPos(x, y, z));
                }
            }
        }
        List<BlockPos> roof = new ArrayList<>();
        for (int x = DX - 1; x <= DX + 1; x++) {
            for (int z = DZ - 1; z <= DZ + 1; z++) {
                roof.add(new BlockPos(x, 73, z));
            }
        }
        // The wall for Through Walls: 2.5 blocks in front of the player, covering the door completely.
        List<BlockPos> wall = new ArrayList<>();
        for (int y = 66; y <= 76; y++) {
            for (int z = DZ - 5; z <= DZ + 5; z++) {
                wall.add(new BlockPos(DX - 4, y, z));
            }
        }
        double camX = DX - 6 + 0.5, camY = 69.0, camZ = DZ + 0.5;

        double[] startPos = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        boolean hideGuiWas = c.onClient(mc -> dev.testkit.compat.McCompat.hudHidden(mc));
        Object cfg0 = Mod.cfg(CFG);
        boolean enabledWas = (Boolean) Mod.call(cfg0, "isEnabled");
        Object styleWas = Mod.call(cfg0, "getStyle");
        int opacityWas = (Integer) Mod.call(cfg0, "getFillOpacity");
        boolean customWas = (Boolean) Mod.call(cfg0, "isCustomFillColor");
        int fillColorWas = (Integer) Mod.call(cfg0, "getFillColor");
        boolean twWas = (Boolean) Mod.call(cfg0, "isThroughWallsRaw");
        boolean showAllWas = (Boolean) Mod.call(cfg0, "isShowAllDoorsRaw");
        int lockedWas = (Integer) Mod.call(cfg0, "getWitherLockedColor");
        int readyWas = (Integer) Mod.call(cfg0, "getWitherReadyColor");
        boolean labelled = false;
        try {
            server(c, level -> {
                for (BlockPos p : door) {
                    level.setBlockAndUpdate(p, Blocks.COAL_BLOCK.defaultBlockState());
                }
                for (BlockPos p : roof) {
                    level.setBlockAndUpdate(p, Blocks.STONE.defaultBlockState());
                }
            });
            // Flying BEFORE the teleport and again after it: the first run of this case teleported a player who was not
            // flying, he dropped below the door before the shots, and the pictures were of its underside.
            placeCamera(c, camX, camY, camZ);
            c.ctx().waitFor(mc -> mc.level.getBlockState(new BlockPos(DX, 69, DZ)).is(Blocks.COAL_BLOCK)
                    && mc.level.getBlockState(new BlockPos(DX, 73, DZ)).is(Blocks.STONE), 400);
            // Twice: under load the wait for the door's blocks ran long enough for him to drop out of the shot (2 of 3
            // ui suites on 2026-10-07 measured eye y 68.92 / 68.36 instead of 70.62, falling). Place him again once
            // the scene is there; with mayfly set (as 395 does) flight is not dropped.
            placeCamera(c, camX, camY, camZ);
            labelled = c.onClient(mc -> {
                setServerData(mc, new ServerData("testkit", "mc.hypixel.net", ServerData.Type.OTHER));
                Mod.staticCall("secrets.DungeonState", "setRoomSim", true);
                mc.player.getAbilities().flying = true;
                mc.player.setYRot(-90f);
                mc.player.setXRot(0f);
                dev.testkit.compat.McCompat.setHudHidden(mc, true);
                dev.testkit.compat.McCompat.clearChatAndToasts(mc);
                Object cfg = Mod.cfg(CFG);
                Mod.call(cfg, "setEnabled", false);
                Mod.call(cfg, "setThroughWalls", false);
                Mod.call(cfg, "setShowAllDoors", false);
                Mod.call(cfg, "setCustomFillColor", false);
                Mod.call(cfg, "setWitherLockedColor", 0xFF000000 | RED);
                Mod.call(cfg, "setWitherReadyColor", 0xFF000000 | GREEN);
                Mod.call(cfg, "save");
                return true;
            });
            c.check(c.onClient(mc -> (Boolean) Mod.staticCall("cheatutils.CheatUtils", "isOnDungeonServer", mc)),
                    "CheatUtils.isOnDungeonServer is still false after labelling the connection - nothing would draw");
            c.note("door at " + DX + ",69," + DZ + " (coal 3x4x3, roof y 73); player to " + camX + "," + camY + ","
                    + camZ + " facing +x");

            BufferedImage base = shot(c, "none", false, "OUTLINE", 35);
            BufferedImage outline = shot(c, "outline", true, "OUTLINE", 35);
            BufferedImage fill = shot(c, "fill-35", true, "FILL", 35);
            BufferedImage both = shot(c, "filled-outline-35", true, "FILLED_OUTLINE", 35);
            BufferedImage fill70 = shot(c, "fill-70", true, "FILL", 70);

            int[] box = changedBox(base, fill);
            if (box == null) {
                c.problem("Fill changed no pixel at all - the door was never drawn (scanner, gate or fill)");
                return;
            }
            int w = box[2] - box[0] + 1, h = box[3] - box[1] + 1;
            int[] inner = {box[0] + w / 4, box[1] + h / 4, box[2] - w / 4, box[3] - h / 4};
            int innerArea = (inner[2] - inner[0] + 1) * (inner[3] - inner[1] + 1);
            c.note(String.format(Locale.ROOT, "door face on screen %d,%d..%d,%d (%dx%d px of %dx%d); interior %d px",
                    box[0], box[1], box[2], box[3], w, h, base.getWidth(), base.getHeight(), innerArea));
            if (w < 40 || h < 40) {
                c.problem("the door face is only " + w + "x" + h + " px - the scene is not what the case expects");
            }

            Stat o = stat(base, outline, inner, RED);
            int oAll = changedCount(base, outline);
            c.note(String.format(Locale.ROOT, "OUTLINE: %d px changed in the frame, %d of %d interior px", oAll,
                    o.changed, innerArea));
            if (oAll < 100) {
                c.problem("Outline changed only " + oAll + " px - it did not draw");
            }
            if (o.changed > innerArea / 50) {
                c.problem("Outline changed " + o.changed + " interior px - it should draw edges only");
            }

            Stat f = stat(base, fill, inner, RED);
            Stat fo = stat(base, both, inner, RED);
            Stat f7 = stat(base, fill70, inner, RED);
            int foAll = changedCount(base, both);
            int fAll = changedCount(base, fill);
            c.note(String.format(Locale.ROOT, "FILL 35%%: %d/%d interior px tinted, mean alpha %.3f (%d px in frame)",
                    f.changed, innerArea, f.alpha, fAll));
            c.note(String.format(Locale.ROOT,
                    "FILLED_OUTLINE 35%%: %d/%d interior px tinted, mean alpha %.3f (%d px in frame)",
                    fo.changed, innerArea, fo.alpha, foAll));
            c.note(String.format(Locale.ROOT, "FILL 70%%: %d/%d interior px tinted, mean alpha %.3f",
                    f7.changed, innerArea, f7.alpha));
            requireFill(c, "Fill 35%", f, innerArea, 0.35);
            requireFill(c, "Filled Outline 35%", fo, innerArea, 0.35);
            requireFill(c, "Fill 70%", f7, innerArea, 0.70);
            if (foAll <= fAll) {
                c.problem("Filled Outline (" + foAll + " px) should change more pixels than Fill (" + fAll
                        + ") - its outline did not draw");
            }

            // Key state: the default fill follows the door's state colour.
            c.onClient(mc -> {
                Mod.staticCall(FEATURE, "onChat", "Wither Key was picked up!");
                return null;
            });
            BufferedImage keyed = shot(c, "fill-35-key", true, "FILL", 35);
            Stat k = stat(base, keyed, inner, GREEN);
            c.note(String.format(Locale.ROOT, "FILL 35%% with a Wither Key: %d/%d interior px tinted green, alpha %.3f",
                    k.changed, innerArea, k.alpha));
            requireFill(c, "Fill with a Wither Key (green)", k, innerArea, 0.35);
            c.onClient(mc -> {
                Mod.staticCall(FEATURE, "onChat", "Someone opened a WITHER door!");
                return null;
            });

            // Custom Fill Color: the fill takes it, the outline keeps the door's red.
            c.onClient(mc -> {
                Object cfg = Mod.cfg(CFG);
                Mod.call(cfg, "setCustomFillColor", true);
                Mod.call(cfg, "setFillColor", 0xFF000000 | CUSTOM);
                Mod.call(cfg, "save");
                return null;
            });
            BufferedImage custom = shot(c, "filled-outline-custom", true, "FILLED_OUTLINE", 35);
            Stat cu = stat(base, custom, inner, CUSTOM);
            int edgeRed = edgeRedCount(base, custom, box, inner);
            c.note(String.format(Locale.ROOT,
                    "FILLED_OUTLINE custom #%06X: %d/%d interior px, alpha %.3f; %d red outline px outside the interior",
                    CUSTOM, cu.changed, innerArea, cu.alpha, edgeRed));
            requireFill(c, "Custom Fill Color", cu, innerArea, 0.35);
            if (edgeRed < 50) {
                c.problem("with a Custom Fill Color the outline should still be red; only " + edgeRed + " red px");
            }
            c.onClient(mc -> {
                Object cfg = Mod.cfg(CFG);
                Mod.call(cfg, "setCustomFillColor", false);
                Mod.call(cfg, "save");
                return null;
            });

            if (cheat) {
                server(c, level -> {
                    for (BlockPos p : wall) {
                        level.setBlockAndUpdate(p, Blocks.STONE.defaultBlockState());
                    }
                });
                c.ctx().waitFor(mc -> mc.level.getBlockState(new BlockPos(DX - 4, 71, DZ)).is(Blocks.STONE), 200);
                BufferedImage wallBase = shot(c, "wall-none", false, "FILL", 35);
                BufferedImage hidden = shot(c, "wall-fill", true, "FILL", 35);
                c.onClient(mc -> {
                    Mod.call(Mod.cfg(CFG), "setThroughWalls", true);
                    return null;
                });
                BufferedImage tw = shot(c, "wall-fill-through-walls", true, "FILL", 35);
                BufferedImage twOutline = shot(c, "wall-outline-through-walls", true, "OUTLINE", 35);
                c.onClient(mc -> {
                    Mod.call(Mod.cfg(CFG), "setThroughWalls", false);
                    return null;
                });
                Stat hid = stat(wallBase, hidden, inner, RED);
                Stat thr = stat(wallBase, tw, inner, RED);
                Stat thrO = stat(wallBase, twOutline, inner, RED);
                int twOAll = changedCount(wallBase, twOutline);
                c.note(String.format(Locale.ROOT,
                        "behind a stone wall: Fill %d/%d interior px; Fill + Through Walls %d/%d (alpha %.3f); "
                                + "Outline + Through Walls %d px in frame, %d interior",
                        hid.changed, innerArea, thr.changed, innerArea, thr.alpha, twOAll, thrO.changed));
                if (hid.changed > innerArea / 50) {
                    c.problem("the depth-tested Fill drew through the wall (" + hid.changed + " interior px)");
                }
                if (thr.changed < innerArea * 9 / 10) {
                    c.problem("Fill + Through Walls tinted only " + thr.changed + " of " + innerArea
                            + " interior px behind the wall");
                }
                if (twOAll < 100) {
                    c.problem("Outline + Through Walls changed only " + twOAll + " px behind the wall");
                }
            } else {
                c.note("legit jar: Through Walls is not in it - skipped the wall shots");
            }

            // Persistence: save, reload from the file, read back.
            c.onClient(mc -> {
                Object cfg = Mod.cfg(CFG);
                Mod.call(cfg, "setStyle", Mod.enumValue(STYLE, "FILLED_OUTLINE"));
                Mod.call(cfg, "setFillOpacity", 62);
                Mod.call(cfg, "setCustomFillColor", true);
                Mod.call(cfg, "setFillColor", 0xFF1234AB);
                Mod.call(cfg, "save");
                Mod.staticCall(CFG, "load");
                return null;
            });
            Object reloaded = Mod.cfg(CFG);
            boolean fresh = reloaded != cfg0;
            Object rStyle = Mod.call(reloaded, "getStyle");
            int rOpacity = (Integer) Mod.call(reloaded, "getFillOpacity");
            boolean rCustom = (Boolean) Mod.call(reloaded, "isCustomFillColor");
            int rColor = (Integer) Mod.call(reloaded, "getFillColor");
            c.note("after save + load (new instance " + fresh + "): style " + rStyle + ", opacity " + rOpacity
                    + ", custom " + rCustom + ", fill color " + String.format("%08X", rColor));
            c.check(fresh, "WitherDoorsConfig.load() kept the same instance - the reload proved nothing");
            c.check("FILLED_OUTLINE".equals(String.valueOf(rStyle)), "Style did not survive a reload: " + rStyle);
            c.check(rOpacity == 62, "Fill Opacity did not survive a reload: " + rOpacity);
            c.check(rCustom, "Fill Color = Custom did not survive a reload");
            c.check(rColor == 0xFF1234AB, "Custom Fill Color did not survive a reload: " + Integer.toHexString(rColor));
            Mod.call(reloaded, "setFillOpacity", 1);
            int low = (Integer) Mod.call(reloaded, "getFillOpacity");
            Mod.call(reloaded, "setFillOpacity", 500);
            int high = (Integer) Mod.call(reloaded, "getFillOpacity");
            c.check(low == 5 && high == 100, "Fill Opacity should clamp to 5..100, got " + low + " / " + high);
        } finally {
            boolean wasLabelled = labelled;
            try {
                c.onClient(mc -> {
                    Object cfg = Mod.cfg(CFG);
                    Mod.call(cfg, "setEnabled", enabledWas);
                    Mod.call(cfg, "setStyle", styleWas);
                    Mod.call(cfg, "setFillOpacity", opacityWas);
                    Mod.call(cfg, "setCustomFillColor", customWas);
                    Mod.call(cfg, "setFillColor", fillColorWas);
                    Mod.call(cfg, "setThroughWalls", twWas);
                    Mod.call(cfg, "setShowAllDoors", showAllWas);
                    Mod.call(cfg, "setWitherLockedColor", lockedWas);
                    Mod.call(cfg, "setWitherReadyColor", readyWas);
                    Mod.call(cfg, "save");
                    Mod.staticCall(FEATURE, "onChat", "Someone opened a WITHER door!");
                    Mod.staticCall("secrets.DungeonState", "setRoomSim", false);
                    if (wasLabelled) {
                        setServerData(mc, null);
                    }
                    dev.testkit.compat.McCompat.setHudHidden(mc, hideGuiWas);
                    var server = mc.getSingleplayerServer();
                    var uuid = mc.player.getUUID();
                    server.execute(() -> {
                        for (List<BlockPos> set : List.of(door, roof, wall)) {
                            for (BlockPos p : set) {
                                server.overworld().setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
                            }
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

    private record Stat(int changed, double alpha) {
    }

    private static void requireFill(UiCase c, String what, Stat s, int innerArea, double opacity) {
        if (s.changed < innerArea * 9 / 10) {
            c.problem(what + " tinted only " + s.changed + " of " + innerArea + " interior px");
        }
        if (Math.abs(s.alpha - opacity) > 0.08) {
            c.problem(String.format(Locale.ROOT, "%s: mean alpha %.3f, expected %.2f +- 0.08", what, s.alpha,
                    opacity));
        }
    }

    /** Wither Doors on/off, style and opacity; let the tick rebuild and a few frames draw; screenshot. */
    private static BufferedImage shot(UiCase c, String label, boolean on, String style, int opacity) {
        c.onClient(mc -> {
            Object cfg = Mod.cfg(CFG);
            Mod.call(cfg, "setEnabled", on);
            Mod.call(cfg, "setStyle", Mod.enumValue(STYLE, style));
            Mod.call(cfg, "setFillOpacity", opacity);
            Mod.call(cfg, "save");
            mc.player.setYRot(-90f);
            mc.player.setXRot(0f);
            return null;
        });
        c.ticks(10);
        double[] eye = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getEyePosition().y, mc.player.getZ(),
                mc.player.getXRot()});
        if (Math.abs(eye[1] - (69.0 + 1.62)) > 0.05 || Math.abs(eye[3]) > 0.5) {
            c.problem(String.format(Locale.ROOT, "%s: the camera moved (eye %.2f,%.2f,%.2f pitch %.1f) - not the scene"
                    + " the case measures", label, eye[0], eye[1], eye[2], eye[3]));
        }
        String name = c.name() + "-" + label;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        Path kept = dev.testkit.harness.Report.screenshot(name, taken);
        c.note(label + " -> " + (kept != null ? kept : taken));
        try {
            return javax.imageio.ImageIO.read(taken.toFile());
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read " + taken + ": " + e);
        }
    }

    private static boolean changed(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF)) > 30;
    }

    private static int changedCount(BufferedImage base, BufferedImage img) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (changed(base.getRGB(x, y), img.getRGB(x, y))) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Bounding box {x0, y0, x1, y1} of the changed pixels, or null. */
    private static int[] changedBox(BufferedImage base, BufferedImage img) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (changed(base.getRGB(x, y), img.getRGB(x, y))) {
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
        }
        return x1 < 0 ? null : new int[]{x0, y0, x1, y1};
    }

    /**
     * Changed interior pixels, and the mean blend alpha of those pixels against {@code target}: per pixel the
     * least-squares a in {@code now = was + a * (target - was)} over the three channels.
     */
    private static Stat stat(BufferedImage base, BufferedImage img, int[] r, int target) {
        int tr = (target >> 16) & 0xFF, tg = (target >> 8) & 0xFF, tb = target & 0xFF;
        int n = 0;
        double sum = 0;
        for (int y = r[1]; y <= r[3]; y++) {
            for (int x = r[0]; x <= r[2]; x++) {
                int was = base.getRGB(x, y), now = img.getRGB(x, y);
                if (!changed(was, now)) {
                    continue;
                }
                n++;
                int r0 = (was >> 16) & 0xFF, g0 = (was >> 8) & 0xFF, b0 = was & 0xFF;
                double er = tr - r0, eg = tg - g0, eb = tb - b0;
                double den = er * er + eg * eg + eb * eb;
                if (den > 0) {
                    sum += ((((now >> 16) & 0xFF) - r0) * er + (((now >> 8) & 0xFF) - g0) * eg
                            + ((now & 0xFF) - b0) * eb) / den;
                }
            }
        }
        return new Stat(n, n == 0 ? 0 : sum / n);
    }

    /** Changed pixels inside the face box but outside its interior that moved towards red (the outline). */
    private static int edgeRedCount(BufferedImage base, BufferedImage img, int[] box, int[] inner) {
        int n = 0;
        for (int y = Math.max(0, box[1] - 4); y <= Math.min(img.getHeight() - 1, box[3] + 4); y++) {
            for (int x = Math.max(0, box[0] - 4); x <= Math.min(img.getWidth() - 1, box[2] + 4); x++) {
                if (x >= inner[0] && x <= inner[2] && y >= inner[1] && y <= inner[3]) {
                    continue;
                }
                int was = base.getRGB(x, y), now = img.getRGB(x, y);
                int dr = ((now >> 16) & 0xFF) - ((was >> 16) & 0xFF);
                int db = (now & 0xFF) - (was & 0xFF);
                if (changed(was, now) && dr > 60 && dr > db) {
                    n++;
                }
            }
        }
        return n;
    }

    /** The connection's ServerData (protected final in ClientCommonPacketListenerImpl), so the mod's server gate opens. */
    private static void setServerData(net.minecraft.client.Minecraft mc, ServerData data) {
        try {
            Field f = ClientCommonPacketListenerImpl.class.getDeclaredField("serverData");
            f.setAccessible(true);
            f.set(mc.getConnection(), data);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not set the connection's ServerData: " + e, e);
        }
    }

    private interface LevelJob {
        void run(net.minecraft.server.level.ServerLevel level);
    }

    /** Run on the integrated server and wait for it (never join() from here - see the testkit CLAUDE.md). */
    /** Flying (mayfly too) on both sides, teleported to the camera spot facing +x, and waited for on the client. */
    private static void placeCamera(UiCase c, double camX, double camY, double camZ) {
        c.onClient(mc -> {
            mc.player.getAbilities().mayfly = true;
            mc.player.getAbilities().flying = true;
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                sp.getAbilities().mayfly = true;
                sp.getAbilities().flying = true;
                sp.onUpdateAbilities();
                sp.teleportTo(server.overworld(), camX, camY, camZ,
                        java.util.Set.<net.minecraft.world.entity.Relative>of(), -90f, 0f, false);
            });
            return null;
        });
        c.ctx().waitFor(mc -> Math.abs(mc.player.getX() - camX) < 0.01 && Math.abs(mc.player.getZ() - camZ) < 0.01
                && Math.abs(mc.player.getY() - camY) < 0.01, 200);
        c.onClient(mc -> {
            mc.player.getAbilities().flying = true;
            return null;
        });
    }

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

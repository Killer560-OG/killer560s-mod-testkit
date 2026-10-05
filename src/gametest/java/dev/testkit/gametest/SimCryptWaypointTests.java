package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 98-crypt-waypoints: Secret Waypoints' Show Crypts and Show Princes (mod, 2026-10-05).
 *
 * <p>killer560: "add crypt and prince waypoints as toggleables for secret waypoints, that will perform the exact
 * same as secret waypoints just for princes and crypts." The mod finds them from the room's blocks
 * ({@code secretwaypoints.CryptScanner}): a crypt is a smooth stone slab lid, a prince is gold beside a slab or a
 * sea lantern walled in polished andesite.
 *
 * <p>The room is Chambers, built on its own from the title screen. The expectation does NOT come from the mod: a
 * census of his captures against the room database (killer560s-mod-logs/crypt-waypoints.md) puts five lids and one
 * prince in Chambers, which together are the database's six crypts. Asserted:
 * <ol>
 *   <li>Toggles off: no crypt or prince waypoint, while the ordinary secret waypoints ARE drawn (so the feature ran).</li>
 *   <li>Toggles on: five crypt waypoints and one prince, each anchored on a block of the right kind in the client's
 *       world, and a screenshot aimed at one shows its colour; with the toggle off again it does not.</li>
 *   <li>Opened: the lid blocks are removed on the server and a zombie spawned in it by the sim's own
 *       {@code SimMobs.spawn} (what the sim's Superboom does). The waypoint stays past the 3 s grace while the zombie
 *       lives - proof the feature saw it - and is gone once the zombie is removed. A second lid opened with nothing
 *       in it goes after the grace. The prince is opened and cleared the same way.</li>
 * </ol>
 */
public class SimCryptWaypointTests implements FabricClientGameTest {

    private static final String NAME = "98-crypt-waypoints";
    private static final String ROOM = "Chambers";
    private static final int EXPECTED_CRYPTS = 5;
    private static final int EXPECTED_PRINCES = 1;

    private static final String CFG = "com.killer560.hub.secretwaypoints.SecretWaypointsConfig";
    private static final String FEATURE = "com.killer560.hub.secretwaypoints.SecretWaypointsFeature";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String SIM_MOBS = "com.killer560.hub.roomsim.SimMobs";

    /** Colours nothing else in a dungeon room is: magenta crypts, cyan princes. */
    private static final int CRYPT_ARGB = 0xFFFF00FF;
    private static final int PRINCE_ARGB = 0xFF00FFFF;

    private static final String SOURCE_ROOMS = ModUnderTest.instanceConfig(
            "C:/Users/Hunter/AppData/Roaming/PrismLauncher/instances/26.1.2 (Mod Only Test)/minecraft/config",
            "killer560smod-rooms");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(20);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (copyDir(SOURCE_ROOMS, "killer560smod-rooms", ".json") < 20 || Scenario.ensureRoomDatabase(ctx) == 0) {
            System.out.println("[" + NAME + "] SKIPPED - needs his real rooms and room database");
            return;
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);

        Object cfg = ModUnderTest.config(CFG);
        boolean wasEnabled = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.call(cfg, "isEnabled",
                new Class<?>[]{}, new Object[]{}));
        try {
            ctx.runOnClient(mc -> {
                ModUnderTest.set(cfg, "setEnabled", true);
                ModUnderTest.set(cfg, "setShowCrypts", false);
                ModUnderTest.set(cfg, "setShowPrinces", false);
                ModUnderTest.set(cfg, "setThroughWalls", true);
                ModUnderTest.set(cfg, "setShowNames", false);
                ModUnderTest.set(cfg, "setCryptColor", CRYPT_ARGB);
                ModUnderTest.set(cfg, "setPrinceColor", PRINCE_ARGB);
                ModUnderTest.call(cfg, "setStyle", new Class<?>[]{enumClass(CFG + "$Style")},
                        new Object[]{ModUnderTest.enumValue(CFG + "$Style", "FILL")});
            });
            play(ctx, cfg);
        } finally {
            ctx.runOnClient(mc -> {
                try {
                    ModUnderTest.set(cfg, "setShowCrypts", false);
                    ModUnderTest.set(cfg, "setShowPrinces", false);
                    ModUnderTest.set(cfg, "setEnabled", wasEnabled);
                    ModUnderTest.set(cfg, "setCryptColor", 0xFFAA00FF);
                    ModUnderTest.set(cfg, "setPrinceColor", 0xFFFFD700);
                    ModUnderTest.call(cfg, "setStyle", new Class<?>[]{enumClass(CFG + "$Style")},
                            new Object[]{ModUnderTest.enumValue(CFG + "$Style", "FILL_OUTLINE")});
                } catch (Throwable ignored) {
                    // cleanup must not replace the verdict
                }
            });
            teardown(ctx);
        }
    }

    private void play(ClientGameTestContext ctx, Object cfg) {
        // ---- the room, his way: from the title screen -------------------------------------------------
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildSingleRoom",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, ROOM})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        int landed = -1;
        for (int t = 0; t < 1800; t++) {
            boolean ok = ctx.computeOnClient(mc -> mc.player != null && mc.player.onGround()
                    && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir());
            if (ok) {
                landed = t;
                break;
            }
            ctx.waitTicks(1);
        }
        say("client stood on the room after " + landed + " tick(s)");
        if (landed < 0) {
            throw new AssertionError("the client never had " + ROOM + " under the player - nothing to scan");
        }
        ctx.waitTicks(40);

        // ---- 1. toggles off ----------------------------------------------------------------------------
        int secretsOff = secretCount(ctx);
        List<BlockPos> cryptsOff = positions(ctx, "CRYPT");
        List<BlockPos> princesOff = positions(ctx, "PRINCE");
        say("toggles OFF: " + secretsOff + " ordinary secret waypoint(s), " + cryptsOff.size() + " crypt, "
                + princesOff.size() + " prince");
        if (secretsOff == 0) {
            throw new AssertionError("Secret Waypoints drew nothing at all in " + ROOM + " - the feature never ran, so "
                    + "'no crypt waypoints with the toggle off' would prove nothing");
        }
        if (!cryptsOff.isEmpty() || !princesOff.isEmpty()) {
            throw new AssertionError("crypt/prince waypoints were drawn with both toggles OFF: " + cryptsOff
                    + " " + princesOff);
        }
        int magentaOff;
        int cyanOff;

        // ---- 2. toggles on -----------------------------------------------------------------------------
        ctx.runOnClient(mc -> {
            ModUnderTest.set(cfg, "setShowCrypts", true);
            ModUnderTest.set(cfg, "setShowPrinces", true);
            ModUnderTest.staticCall(FEATURE, "invalidateCache");
        });
        ctx.waitTicks(30);
        List<BlockPos> crypts = positions(ctx, "CRYPT");
        List<BlockPos> princes = positions(ctx, "PRINCE");
        say("toggles ON: crypts at " + crypts + ", princes at " + princes);
        for (BlockPos p : crypts) {
            String b = clientBlock(ctx, p);
            if (!b.contains("smooth_stone_slab")) {
                throw new AssertionError("a crypt waypoint is anchored on " + b + " at " + p.toShortString()
                        + ", not on a smooth stone slab lid");
            }
        }
        for (BlockPos p : princes) {
            String b = clientBlock(ctx, p);
            if (!(b.contains("gold_block") || b.contains("sea_lantern") || b.contains("polished_andesite"))) {
                throw new AssertionError("a prince waypoint is anchored on " + b + " at " + p.toShortString());
            }
        }
        if (crypts.size() != EXPECTED_CRYPTS || princes.size() != EXPECTED_PRINCES) {
            throw new AssertionError(ROOM + " should show " + EXPECTED_CRYPTS + " crypt(s) and " + EXPECTED_PRINCES
                    + " prince (the capture census), but shows " + crypts.size() + " and " + princes.size());
        }

        // Screenshot check: aim at one crypt and the prince, count their colour with the toggle on and off.
        double[] cryptBox = boxes(ctx, "CRYPT").get(0);
        double[] princeBox = boxes(ctx, "PRINCE").get(0);
        aimAt(ctx, cryptBox);
        int magentaOn = countColour(ctx, "crypt-on", 0xFF, 0x00, 0xFF);
        aimAt(ctx, princeBox);
        int cyanOn = countColour(ctx, "prince-on", 0x00, 0xFF, 0xFF);
        ctx.runOnClient(mc -> {
            ModUnderTest.set(cfg, "setShowCrypts", false);
            ModUnderTest.set(cfg, "setShowPrinces", false);
            ModUnderTest.staticCall(FEATURE, "invalidateCache");
        });
        ctx.waitTicks(30);
        aimAt(ctx, cryptBox);
        magentaOff = countColour(ctx, "crypt-off", 0xFF, 0x00, 0xFF);
        aimAt(ctx, princeBox);
        cyanOff = countColour(ctx, "prince-off", 0x00, 0xFF, 0xFF);
        say("screen: crypt colour " + magentaOn + " px on / " + magentaOff + " px off; prince colour " + cyanOn
                + " px on / " + cyanOff + " px off");
        if (magentaOn < 200 || magentaOn < 10 * Math.max(1, magentaOff)) {
            throw new AssertionError("the crypt waypoint did not reach the screen: " + magentaOn + " magenta px with "
                    + "Show Crypts on, " + magentaOff + " with it off");
        }
        if (cyanOn < 100 || cyanOn < 10 * Math.max(1, cyanOff)) {
            throw new AssertionError("the prince waypoint did not reach the screen: " + cyanOn + " cyan px on, "
                    + cyanOff + " off");
        }
        ctx.runOnClient(mc -> {
            ModUnderTest.set(cfg, "setShowCrypts", true);
            ModUnderTest.set(cfg, "setShowPrinces", true);
            ModUnderTest.staticCall(FEATURE, "invalidateCache");
        });
        ctx.waitTicks(30);

        // ---- 3. opened ---------------------------------------------------------------------------------
        BlockPos withUndead = crypts.get(0);
        BlockPos empty = crypts.get(1);
        BlockPos prince = princes.get(0);
        BlockPos undeadAt = openTomb(ctx, withUndead, true);
        BlockPos princeUndeadAt = openTomb(ctx, prince, true);
        openTomb(ctx, empty, false);
        say("opened crypt " + withUndead.toShortString() + " (zombie at " + undeadAt.toShortString() + "), crypt "
                + empty.toShortString() + " (nothing in it), prince " + prince.toShortString() + " (zombie at "
                + princeUndeadAt.toShortString() + ")");
        ctx.waitTicks(100); // 5 s: past the 3 s grace
        List<BlockPos> cryptsMid = positions(ctx, "CRYPT");
        List<BlockPos> princesMid = positions(ctx, "PRINCE");
        say("5 s after opening: crypts " + cryptsMid + ", princes " + princesMid);
        if (!cryptsMid.contains(withUndead)) {
            throw new AssertionError("the opened crypt lost its waypoint while its zombie was still alive - the "
                    + "feature did not see the undead (or never kept opened tombs)");
        }
        if (cryptsMid.contains(empty)) {
            throw new AssertionError("an opened crypt with nothing alive in it kept its waypoint 5 s later");
        }
        if (!princesMid.contains(prince)) {
            throw new AssertionError("the opened prince lost its waypoint while its zombie was still alive");
        }
        if (cryptsMid.size() != EXPECTED_CRYPTS - 1) {
            throw new AssertionError("expected " + (EXPECTED_CRYPTS - 1) + " crypt waypoints after one empty crypt "
                    + "was opened, got " + cryptsMid.size());
        }
        int removed = killNear(ctx, undeadAt) + killNear(ctx, princeUndeadAt);
        say("removed " + removed + " mob(s) at the two tombs");
        if (removed < 2) {
            throw new AssertionError("the zombies spawned into the tombs could not be found to remove (" + removed
                    + ") - the 'gone once killed' half would be untested");
        }
        ctx.waitTicks(60);
        List<BlockPos> cryptsAfter = positions(ctx, "CRYPT");
        List<BlockPos> princesAfter = positions(ctx, "PRINCE");
        say("after the undead died: crypts " + cryptsAfter + ", princes " + princesAfter);
        if (cryptsAfter.contains(withUndead) || !princesAfter.isEmpty()) {
            throw new AssertionError("a tomb kept its waypoint after it was opened and its undead died");
        }
        if (cryptsAfter.size() != EXPECTED_CRYPTS - 2) {
            throw new AssertionError("the untouched crypts should still be shown: expected " + (EXPECTED_CRYPTS - 2)
                    + ", got " + cryptsAfter.size());
        }
        System.out.println("[" + NAME + "] PASS - " + EXPECTED_CRYPTS + " crypts and " + EXPECTED_PRINCES
                + " prince shown only with their toggles, on screen, kept while their undead lives and gone after");
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private static void say(String s) {
        System.out.println("[" + NAME + "] " + s);
    }

    private static Class<?> enumClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("no class " + name, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> positions(ClientGameTestContext ctx, String kind) {
        return ctx.computeOnClient(mc -> new ArrayList<>((List<BlockPos>) ModUnderTest.staticCall(FEATURE,
                "cachedPositions", new Class<?>[]{String.class}, new Object[]{kind})));
    }

    @SuppressWarnings("unchecked")
    private static List<double[]> boxes(ClientGameTestContext ctx, String kind) {
        return ctx.computeOnClient(mc -> new ArrayList<>((List<double[]>) ModUnderTest.staticCall(FEATURE,
                "cachedBoxes", new Class<?>[]{String.class}, new Object[]{kind})));
    }

    private static int secretCount(ClientGameTestContext ctx) {
        int n = 0;
        for (String k : new String[]{"CHEST", "ITEM", "BAT", "WITHER", "LEVER"}) {
            n += positions(ctx, k).size();
        }
        return n;
    }

    private static String clientBlock(ClientGameTestContext ctx, BlockPos p) {
        return ctx.computeOnClient(mc -> mc.level.getBlockState(p).toString());
    }

    /** Points the camera at a box's centre from where the player stands. Rotation only, no movement. */
    private static void aimAt(ClientGameTestContext ctx, double[] box) {
        ctx.runOnClient(mc -> {
            var eye = mc.player.getEyePosition();
            double dx = (box[0] + box[3]) / 2 - eye.x;
            double dy = (box[1] + box[4]) / 2 - eye.y;
            double dz = (box[2] + box[5]) / 2 - eye.z;
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
        });
        ctx.waitTicks(5);
    }

    private static int countColour(ClientGameTestContext ctx, String tag, int r0, int g0, int b0) {
        Path shot = ctx.takeScreenshot(dev.testkit.harness.Report.fileName(NAME + "-" + tag));
        dev.testkit.harness.Report.screenshot(NAME + "-" + tag, shot);
        int n = 0;
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(shot.toFile());
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int rgb = img.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    if (Math.abs(r - r0) <= 24 && Math.abs(g - g0) <= 24 && Math.abs(b - b0) <= 24) {
                        n++;
                    }
                }
            }
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read the screenshot " + shot, e);
        }
        return n;
    }

    /**
     * Opens a tomb on the server the way the sim's Superboom leaves it: every block of the tomb that the waypoint
     * stands for removed (the whole slab run, or the prince's gold / lantern and ring), and, if asked, a zombie
     * spawned in it by {@code SimMobs.spawn}. Returns where the zombie was put.
     */
    private static BlockPos openTomb(ClientGameTestContext ctx, BlockPos anchor, boolean withUndead) {
        AtomicReference<BlockPos> spawnAt = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                var level = server.overworld();
                java.util.Set<BlockPos> seen = new java.util.HashSet<>();
                java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
                queue.add(anchor);
                seen.add(anchor);
                while (!queue.isEmpty() && seen.size() < 200) {
                    BlockPos here = queue.poll();
                    for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                        BlockPos next = here.relative(d);
                        var st = level.getBlockState(next);
                        boolean member = st.is(net.minecraft.world.level.block.Blocks.SMOOTH_STONE_SLAB)
                                ? d != net.minecraft.core.Direction.UP && d != net.minecraft.core.Direction.DOWN
                                : st.is(net.minecraft.world.level.block.Blocks.GOLD_BLOCK)
                                        || st.is(net.minecraft.world.level.block.Blocks.SEA_LANTERN)
                                        || st.is(net.minecraft.world.level.block.Blocks.POLISHED_ANDESITE);
                        if (member && seen.add(next)) {
                            queue.add(next);
                        }
                    }
                }
                for (BlockPos p : seen) {
                    level.setBlockAndUpdate(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                }
                spawnAt.set(anchor);
            });
        });
        ctx.waitFor(mc -> spawnAt.get() != null, 200);
        BlockPos at = spawnAt.get();
        if (withUndead) {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_MOBS, "spawn",
                    new Class<?>[]{Minecraft.class, BlockPos.class, enumClass(SIM_MOBS + "$Kind")},
                    new Object[]{mc, at, ModUnderTest.enumValue(SIM_MOBS + "$Kind", "ZOMBIE")}));
        }
        return at;
    }

    /** Removes every non-player living mob within 3 blocks of {@code at}, on the server. */
    private static int killNear(ClientGameTestContext ctx, BlockPos at) {
        AtomicReference<Integer> n = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                int k = 0;
                var box = new net.minecraft.world.phys.AABB(at.getX() - 3, at.getY() - 3, at.getZ() - 3,
                        at.getX() + 4, at.getY() + 4, at.getZ() + 4);
                for (var e : server.overworld().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, box)) {
                    if (e instanceof net.minecraft.world.entity.player.Player
                            || e instanceof net.minecraft.world.entity.decoration.ArmorStand) {
                        continue;
                    }
                    e.discard();
                    k++;
                }
                n.set(k);
            });
        });
        ctx.waitFor(mc -> n.get() != null, 200);
        return n.get();
    }

    private static void teardown(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            try {
                ModUnderTest.staticCall(SIM_STATE, "leave", new Class<?>[]{}, new Object[]{});
            } catch (Throwable ignored) {
                // never let cleanup replace the verdict
            }
        });
        ctx.runOnClient(mc -> mc.execute(() -> {
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                mc.disconnectWithSavingScreen();
            }
        }));
        ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 1200);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> mc.execute(() ->
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen, 400);
    }

    private static int copyDir(String from, String into, String suffix) {
        try {
            Path source = Path.of(from);
            if (!Files.isDirectory(source)) {
                return 0;
            }
            Path target = ModUnderTest.modConfig(into);
            Files.createDirectories(target);
            int n = 0;
            try (var s = Files.list(source)) {
                for (Path f : s.toList()) {
                    if (Files.isRegularFile(f) && f.toString().endsWith(suffix)) {
                        Files.copy(f, target.resolve(f.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        n++;
                    }
                }
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }
}

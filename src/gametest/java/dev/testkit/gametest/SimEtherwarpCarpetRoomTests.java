package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 1102-sim-ew-carpet-room (owner question 2026-11-02, see {@link SimEtherwarpCarpetTests}): the same question in REAL sim
 * rooms. Each room of {@link #ROOMS} is loaded on its own ({@code SimBuilder.buildSingleRoom}); every carpet within 40 blocks
 * of the spawn is a target, up to {@link #MAX_TARGETS} a room. For each, a stand spot 3-9 blocks off is FOUND in the
 * client's world (a full-topped block or a carpet to stand on, two free blocks above, and a sneaking-eye ray that the mod's
 * own {@code TeleportUtils.traverseVoxels} resolves onto that carpet), he is put there, sneaks with the sim's AOTV, turns
 * onto the carpet's top and presses USE. Compared: the Etherwarp Overlay's block, the client's TeleportUtils block, the
 * server's own ray from the SERVER player (the sim's inputs: its position, its look) and where the sim's server put him.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimEtherwarpCarpetRoomTests implements FabricClientGameTest {

    private static final String NAME = "1102-sim-ew-carpet-room";
    private static final String[] ROOMS = {"Carpets", "Purple Flags", "Wizard"};
    private static final int MAX_TARGETS = 12;
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String EW = "com.killer560.hub.etherwarpoverlay.EtherwarpOverlayFeature";
    private static final String EWC = "com.killer560.hub.etherwarpoverlay.EtherwarpOverlayConfig";
    private static final String TP = "com.killer560.hub.livemap.autoclear.TeleportUtils";
    private static final double SNEAK_EYE = 1.27;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                "setAutoPuzzlesMasterEnabled", false));
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(EWC), "setEnabled", true));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                new Object[]{"gametest"}));
        List<String> failures = new ArrayList<>();
        int[] totals = new int[3];
        try {
            for (String room : ROOMS) {
                room(ctx, room, failures, totals);
            }
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.getInput().releaseKey(o -> o.keyShift);
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "leave"));
            ctx.runOnClient(mc -> mc.execute(() -> {
                if (mc.level != null) {
                    mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                    mc.disconnectWithSavingScreen();
                }
            }));
            ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null);
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> mc.execute(() ->
                    McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
            ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen);
        }
        println("warps onto carpets: " + totals[0] + ", landed on the overlay's box: " + totals[1]
                + ", TeleportUtils' look differing from the overlay's: " + totals[2]);
        if (totals[0] < 10) {
            failures.add("only " + totals[0] + " carpet warp(s) were made - the case proved too little");
        }
        if (!failures.isEmpty()) {
            for (String f : failures) {
                println("FAIL " + f);
            }
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
        println("PASS");
    }

    private static void room(ClientGameTestContext ctx, String room, List<String> failures, int[] totals) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildSingleRoom",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, room})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        boolean stood = false;
        for (int t = 0; t < 1800 && !stood; t++) {
            stood = ctx.computeOnClient(mc -> mc.player != null && mc.player.onGround()
                    && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir());
            ctx.waitTicks(1);
        }
        if (!stood) {
            failures.add(room + ": the client never stood in the room");
            return;
        }
        ctx.waitTicks(60);
        AtomicReference<Boolean> given = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                var inv = sp.getInventory();
                for (int i = 0; i < 36; i++) {
                    inv.setItem(i, ItemStack.EMPTY);
                }
                inv.setItem(0, (ItemStack) ModUnderTest.staticCall(SIM_ITEMS, "build", new Class<?>[]{String.class},
                        new Object[]{"ASPECT_OF_THE_VOID"}));
                given.set(true);
            });
        });
        ctx.waitFor(mc -> given.get() != null, 200);
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));

        // Targets and stand spots, from the client's world.
        List<double[]> plans = ctx.computeOnClient(mc -> plan(mc.level, mc.player.blockPosition()));
        println(room + ": " + plans.size() + " carpet target(s) with a stand spot");
        if (plans.isEmpty()) {
            println(room + ": no carpet with a stand spot found - nothing to warp to here");
            return;
        }
        for (double[] p : plans) {
            warp(ctx, room, p, failures, totals);
        }
    }

    /** Up to MAX_TARGETS {carpet x, y, z, stand x, feet y, stand z, yaw, pitch}, one a carpet. */
    private static List<double[]> plan(Level level, BlockPos around) {
        List<double[]> out = new ArrayList<>();
        List<BlockPos> carpets = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(around.offset(-48, -30, -48), around.offset(48, 30, 48))) {
            if (level.getBlockState(p).getBlock() instanceof CarpetBlock) {
                carpets.add(p.immutable());
            }
        }
        // Spread the picks over the room: every k-th carpet.
        int step = Math.max(1, carpets.size() / (MAX_TARGETS * 2));
        for (int i = 0; i < carpets.size() && out.size() < MAX_TARGETS; i += step) {
            BlockPos c = carpets.get(i);
            double[] spot = standFor(level, c);
            if (spot != null) {
                out.add(new double[]{c.getX(), c.getY(), c.getZ(), spot[0], spot[1], spot[2], spot[3], spot[4]});
            }
        }
        return out;
    }

    private static double[] standFor(Level level, BlockPos c) {
        Vec3 aim = new Vec3(c.getX() + 0.5, c.getY() + 0.0625, c.getZ() + 0.5);
        for (int r = 3; r <= 9; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    for (int dy = -3; dy <= 3; dy++) {
                        BlockPos s = c.offset(dx, dy, dz);
                        Double feet = feetAt(level, s);
                        if (feet == null) {
                            continue;
                        }
                        Vec3 eye = new Vec3(s.getX() + 0.5, feet + SNEAK_EYE, s.getZ() + 0.5);
                        Vec3 d = aim.subtract(eye);
                        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
                        float pitch = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
                        Vec3 look = (Vec3) ModUnderTest.staticCall(TP, "getLook", new Class<?>[]{float.class, float.class},
                                new Object[]{yaw, pitch});
                        Vec3 end = eye.add(look.scale(57.0));
                        Object r2 = ModUnderTest.staticCall(TP, "traverseVoxels", new Class<?>[]{Level.class, double.class,
                                double.class, double.class, double.class, double.class, double.class, boolean.class},
                                new Object[]{level, eye.x, eye.y, eye.z, end.x, end.y, end.z, true});
                        String rs = String.valueOf(r2);
                        if (rs.contains("succeeded=true") && rs.contains("x=" + c.getX() + ", y=" + c.getY() + ", z=" + c.getZ() + "}")) {
                            return new double[]{s.getX() + 0.5, feet, s.getZ() + 0.5, yaw, pitch};
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Feet y standing in block s: on a full-topped block below (feet s.y) or on a carpet in s; two clear blocks. */
    private static Double feetAt(Level level, BlockPos s) {
        BlockState in = level.getBlockState(s);
        BlockState below = level.getBlockState(s.below());
        double feet;
        if (in.getBlock() instanceof CarpetBlock) {
            feet = s.getY() + 0.0625;
        } else if (in.getCollisionShape(level, s).isEmpty() && !below.getCollisionShape(level, s.below()).isEmpty()
                && below.getCollisionShape(level, s.below()).max(Direction.Axis.Y) == 1.0) {
            feet = s.getY();
        } else {
            return null;
        }
        if (!level.getBlockState(s.above()).getCollisionShape(level, s.above()).isEmpty()
                || !level.getBlockState(s.above(2)).getCollisionShape(level, s.above(2)).isEmpty()
                || !level.getFluidState(s).isEmpty() || !level.getFluidState(s.above()).isEmpty()) {
            return null;
        }
        return feet;
    }

    private static void warp(ClientGameTestContext ctx, String room, double[] p, List<String> failures, int[] totals) {
        BlockPos carpet = BlockPos.containing(p[0], p[1], p[2]);
        double sx = p[3], feet = p[4], sz = p[5];
        float yaw = (float) p[6], pitch = (float) p[7];
        ctx.getInput().releaseKey(o -> o.keyShift);
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                sp.teleportTo(server.overworld(), sx, feet, sz, Set.<Relative>of(), yaw, pitch, false);
            });
        });
        boolean there = false;
        for (int t = 0; t < 200 && !there; t++) {
            ctx.waitTicks(1);
            there = ctx.computeOnClient(mc -> Math.abs(mc.player.getX() - sx) < 0.01 && Math.abs(mc.player.getZ() - sz) < 0.01
                    && Math.abs(mc.player.getY() - feet) < 0.01 && mc.player.onGround());
        }
        String tag = room + " carpet " + carpet.toShortString();
        if (!there) {
            String at = ctx.computeOnClient(mc -> mc.player.position().toString());
            println(tag + ": could not stand at (" + sx + ", " + feet + ", " + sz + "), he is at " + at + " - skipped");
            return;
        }
        ctx.getInput().holdKey(o -> o.keyShift);
        for (int t = 0; t < 40; t++) {
            ctx.waitTicks(1);
            if (ctx.computeOnClient(mc -> mc.player.isCrouching())) {
                break;
            }
        }
        ctx.waitTicks(4);
        ctx.runOnClient(mc -> {
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
            mc.player.yRotO = yaw;
            mc.player.xRotO = pitch;
        });
        ctx.waitTicks(4);
        String outcome = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(EW, "lastOutcome"));
        String client = ctx.computeOnClient(mc -> ray(mc.level, mc.player.position(), mc.player.getYRot(), mc.player.getXRot()));
        // The overlay's own look: vanilla's view vector (Mth's sine table), not TeleportUtils.getLook (Math.sin).
        String clientVanilla = ctx.computeOnClient(mc -> ray(mc.level, mc.player.position(), mc.player.getLookAngle()));
        AtomicReference<String> server = new AtomicReference<>();
        AtomicReference<double[]> serverBefore = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var srv = mc.getSingleplayerServer();
            UUID uuid = mc.player.getUUID();
            srv.execute(() -> {
                var sp = srv.getPlayerList().getPlayer(uuid);
                server.set(ray(sp.level(), sp.position(), sp.getYRot(), sp.getXRot()) + " vanilla-look "
                        + ray(sp.level(), sp.position(), sp.getLookAngle()) + String.format(Locale.ROOT,
                        " from (%.4f, %.4f, %.4f) look %.4f/%.4f", sp.getX(), sp.getY(), sp.getZ(), sp.getYRot(), sp.getXRot()));
                serverBefore.set(new double[]{sp.getX(), sp.getY(), sp.getZ()});
            });
        });
        ctx.waitFor(mc -> server.get() != null, 100);
        ctx.getInput().pressKey(o -> o.keyUse);
        double[] landed = null;
        for (int t = 0; t < 20 && landed == null; t++) {
            ctx.waitTicks(1);
            AtomicReference<double[]> now = new AtomicReference<>();
            ctx.runOnClient(mc -> {
                var srv = mc.getSingleplayerServer();
                UUID uuid = mc.player.getUUID();
                srv.execute(() -> {
                    var sp = srv.getPlayerList().getPlayer(uuid);
                    now.set(new double[]{sp.getX(), sp.getY(), sp.getZ()});
                });
            });
            ctx.waitFor(mc -> now.get() != null, 100);
            double[] b = serverBefore.get();
            double[] n = now.get();
            if (Math.abs(n[0] - b[0]) + Math.abs(n[1] - b[1]) + Math.abs(n[2] - b[2]) > 0.5) {
                landed = n;
            }
        }
        ctx.waitTicks(20);
        double[] settled = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        ctx.getInput().releaseKey(o -> o.keyShift);
        // What the player is shown is the Etherwarp Overlay's box: the sim must land him on it. TeleportUtils (Auto
        // Routes, ZPEW) must agree too wherever it agrees with the overlay; where the two differ (a ray through a block
        // corner, the two look formulas a hair apart) it is noted.
        BlockPos shown = outcome.startsWith("drawn ") && outcome.endsWith(" ok") ? parse(outcome.substring(6,
                outcome.length() - 3)) : null;
        BlockPos target = shown != null ? shown : carpet;
        String want = target.toShortString();
        double wantFeet = target.getY() + 1.05;
        println(String.format(Locale.ROOT, "%s from (%.2f, %.4f, %.2f) look %.2f/%.2f | overlay '%s' | client %s (vanilla look "
                        + clientVanilla + ") | server %s"
                        + " | landed %s | settled (%.3f, %.4f, %.3f)", tag, sx, feet, sz, yaw, pitch, outcome, client, server.get(),
                landed == null ? "none" : String.format(Locale.ROOT, "(%.4f, %.4f, %.4f)", landed[0], landed[1], landed[2]),
                settled[0], settled[1], settled[2]));
        totals[0]++;
        boolean ok = true;
        if (shown == null) {
            failures.add(tag + ": the overlay showed no landing: '" + outcome + "'");
            ok = false;
        }
        if (!client.startsWith("ok " + want)) {
            totals[2]++;
            println(tag + ": NOTE TeleportUtils' look says " + client + " where the overlay shows " + want);
        }
        if (landed == null) {
            failures.add(tag + ": the sim never moved him");
            ok = false;
        } else if (Math.abs(landed[0] - (target.getX() + 0.5)) > 1e-6 || Math.abs(landed[2] - (target.getZ() + 0.5)) > 1e-6
                || Math.abs(landed[1] - wantFeet) > 1e-6) {
            failures.add(String.format(Locale.ROOT, "%s: the sim landed him at (%.4f, %.4f, %.4f), the overlay showed block %s"
                    + " (%.4f, %.4f, %.4f)", tag, landed[0], landed[1], landed[2], want, target.getX() + 0.5, wantFeet,
                    target.getZ() + 0.5));
            ok = false;
        } else if (Math.abs(settled[1] - (target.getY() + 0.0625)) > 1e-3) {
            failures.add(String.format(Locale.ROOT, "%s: settled at y %.4f, not on the carpet's top %.4f", tag, settled[1],
                    target.getY() + 0.0625));
            ok = false;
        }
        if (ok) {
            totals[1]++;
        }
    }

    private static BlockPos parse(String xyz) {
        String[] a = xyz.split(",");
        return new BlockPos(Integer.parseInt(a[0].trim()), Integer.parseInt(a[1].trim()), Integer.parseInt(a[2].trim()));
    }

    /** "ok x, y, z" / "refused x, y, z" / "none": TeleportUtils' etherwarp ray from these feet and this look. */
    private static String ray(Level level, Vec3 feet, float yaw, float pitch) {
        return ray(level, feet, (Vec3) ModUnderTest.staticCall(TP, "getLook", new Class<?>[]{float.class, float.class},
                new Object[]{yaw, pitch}));
    }

    /** The same walk (TeleportUtils.traverseVoxels) along a given look vector. */
    private static String ray(Level level, Vec3 feet, Vec3 look) {
        Vec3 eye = new Vec3(feet.x, feet.y + SNEAK_EYE, feet.z);
        Vec3 end = eye.add(look.scale(57.0));
        Object r = ModUnderTest.staticCall(TP, "traverseVoxels", new Class<?>[]{Level.class, double.class, double.class,
                double.class, double.class, double.class, double.class, boolean.class},
                new Object[]{level, eye.x, eye.y, eye.z, end.x, end.y, end.z, true});
        String s = String.valueOf(r);
        BlockPos pos = (BlockPos) ModUnderTest.call(r, "pos", new Class<?>[]{}, new Object[]{});
        if (pos == null) {
            return "none";
        }
        return (s.contains("succeeded=true") ? "ok " : "refused ") + pos.toShortString();
    }

    private static void println(String line) {
        System.out.println("[" + NAME + "] " + line);
    }
}

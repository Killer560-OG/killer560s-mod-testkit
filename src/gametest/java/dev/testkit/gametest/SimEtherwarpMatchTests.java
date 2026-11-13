package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 1112-sim-ew-match (killer560 2026-11-12: "my etherwarp overlay is off" in the sim). In real sim rooms, from several stand
 * spots (block centers and spots a quarter block off center), a mix of looks is picked with the Etherwarp Overlay's own
 * resolver: targets near and far (up to 57), slabs and stairs, aims at a top face's corner (the ray grazing block edges),
 * walls. For each he stands there sneaking with the sim's AOTV, turns, and presses USE; the block the sim's server lands
 * him on must be the block the overlay DREW that frame ({@code lastOutcome}), every time. A FLICK set turns and clicks in
 * the same tick (the server last heard the old look); a few unsneaked looks must draw nothing. Fails unless at least 40
 * warps were made and the sim moved him on every one.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimEtherwarpMatchTests implements FabricClientGameTest {

    private static final String NAME = "1112-sim-ew-match";
    private static final String[] ROOMS = {"Quartz Knight", "Wizard", "Purple Flags"};
    private static final int STANDS_PER_ROOM = 3;
    private static final int LOOKS_PER_STAND = 6;
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String EW = "com.killer560.hub.etherwarpoverlay.EtherwarpOverlayFeature";
    private static final String EWC = "com.killer560.hub.etherwarpoverlay.EtherwarpOverlayConfig";
    private static final double RANGE = 57.0;

    /** One planned warp: stand feet, look, what kind of look it is. */
    private record Plan(double x, double feet, double z, float yaw, float pitch, String kind) { }

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
        // warps, matched, flick warps, flick matched, unsneaked checks
        int[] totals = new int[5];
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
        println("warps: " + totals[0] + ", landed on the overlay's block: " + totals[1] + "; flick warps: " + totals[2]
                + ", landed on the overlay's block: " + totals[3] + "; unsneaked looks drawing nothing: " + totals[4]);
        if (totals[0] < 40) {
            failures.add("only " + totals[0] + " warp(s) were made - the case proved too little");
        }
        if (totals[2] < 6) {
            failures.add("only " + totals[2] + " flick warp(s) were made");
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
                var inv = server.getPlayerList().getPlayer(uuid).getInventory();
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
        List<Plan> plans = ctx.computeOnClient(mc -> plan(mc.level, mc.player, room.hashCode()));
        println(room + ": " + plans.size() + " planned look(s)");
        int i = 0;
        for (Plan p : plans) {
            warp(ctx, room, p, i++ % 4 == 3, failures, totals);
        }
        // Not sneaking with the AOTV: the overlay draws nothing (and the click would be an Instant Transmission).
        for (int k = 0; k < Math.min(2, plans.size()); k++) {
            Plan p = plans.get(k);
            if (!standAt(ctx, p)) {
                continue;
            }
            ctx.runOnClient(mc -> turn(mc.player, p.yaw(), p.pitch()));
            ctx.waitTicks(4);
            String outcome = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(EW, "lastOutcome"));
            if (outcome.startsWith("drawn")) {
                failures.add(room + ": unsneaked AOTV look drew '" + outcome + "'");
            } else {
                totals[4]++;
            }
        }
    }

    /** Stand spots spread over the room, and per spot a mix of looks the overlay resolves to a landing. */
    private static List<Plan> plan(Level level, Player player, long seed) {
        Random rnd = new Random(seed);
        BlockPos around = player.blockPosition();
        List<double[]> spots = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(around.offset(-14, -6, -14), around.offset(14, 6, 14))) {
            Double feet = feetAt(level, p);
            if (feet != null) {
                spots.add(new double[]{p.getX(), feet, p.getZ()});
            }
        }
        java.util.Collections.shuffle(spots, rnd);
        List<Plan> out = new ArrayList<>();
        int stands = 0;
        for (double[] s : spots) {
            if (stands >= STANDS_PER_ROOM) {
                break;
            }
            BlockPos sp = BlockPos.containing(s[0], s[1], s[2]);
            // Every other stand a quarter block off center, toward a side whose column is also clear.
            double ox = 0.5, oz = 0.5;
            if (stands % 2 == 1) {
                if (feetAt(level, sp.east()) != null) ox = 0.75;
                if (feetAt(level, sp.south()) != null) oz = 0.75;
            }
            Vec3 feet = new Vec3(s[0] + ox, s[1], s[2] + oz);
            List<Plan> near = new ArrayList<>(), far = new ArrayList<>(), partial = new ArrayList<>(), edge = new ArrayList<>();
            for (int yaw = -180; yaw < 180; yaw += 12) {
                for (int pitch : new int[]{-4, 6, 16, 28, 42, 60, 78}) {
                    float fy = yaw + rnd.nextFloat() * 6f, fp = pitch + rnd.nextFloat() * 3f;
                    BlockPos t = resolve(level, feet, player, fy, fp);
                    if (t == null) {
                        continue;
                    }
                    double d = Vec3.atCenterOf(t).distanceTo(feet);
                    Plan pl = new Plan(feet.x, feet.y, feet.z, fy, fp, String.format(Locale.ROOT, "d%.0f", d));
                    BlockState st = level.getBlockState(t);
                    var shape = st.getCollisionShape(level, t);
                    boolean part = shape.isEmpty() || shape.max(Direction.Axis.Y) < 1.0 || shape.toAabbs().size() > 1;
                    if (part) {
                        partial.add(new Plan(pl.x, pl.feet, pl.z, fy, fp, "partial " + st.getBlock().getName().getString()));
                    } else if (d > 20) {
                        far.add(pl);
                    } else {
                        near.add(pl);
                    }
                    // An aim at a corner of this block's top face, 0.02 in: the ray runs along block edges.
                    if (rnd.nextInt(6) == 0) {
                        double top = t.getY() + (shape.isEmpty() ? 1.0 : shape.max(Direction.Axis.Y));
                        double cx = t.getX() + (rnd.nextBoolean() ? 0.02 : 0.98);
                        double cz = t.getZ() + (rnd.nextBoolean() ? 0.02 : 0.98);
                        Vec3 eye = feet.add(0, 1.27, 0);
                        Vec3 dv = new Vec3(cx, top, cz).subtract(eye);
                        float ey = (float) Math.toDegrees(Math.atan2(-dv.x, dv.z));
                        float ep = (float) Math.toDegrees(-Math.atan2(dv.y, Math.sqrt(dv.x * dv.x + dv.z * dv.z)));
                        if (resolve(level, feet, player, ey, ep) != null) {
                            edge.add(new Plan(feet.x, feet.y, feet.z, ey, ep, "edge"));
                        }
                    }
                }
            }
            if (near.size() + far.size() + partial.size() < 3) {
                continue;
            }
            stands++;
            List<List<Plan>> pools = List.of(far, partial, edge, near);
            int took = 0;
            for (int round = 0; took < LOOKS_PER_STAND && round < 10; round++) {
                for (List<Plan> pool : pools) {
                    if (took < LOOKS_PER_STAND && !pool.isEmpty()) {
                        out.add(pool.remove(rnd.nextInt(pool.size())));
                        took++;
                    }
                }
            }
        }
        return out;
    }

    private static BlockPos resolve(Level level, Vec3 feet, Player player, float yaw, float pitch) {
        return (BlockPos) ModUnderTest.staticCall(EW, "resolveTargetAlong", new Class<?>[]{Level.class, Vec3.class,
                Player.class, double.class, float.class, float.class, boolean.class},
                new Object[]{level, feet, player, RANGE, yaw, pitch, true});
    }

    /** Feet y standing in block s on a full-topped block below; two clear blocks, no fluid. */
    private static Double feetAt(Level level, BlockPos s) {
        BlockState below = level.getBlockState(s.below());
        if (!level.getBlockState(s).getCollisionShape(level, s).isEmpty()
                || below.getCollisionShape(level, s.below()).isEmpty()
                || below.getCollisionShape(level, s.below()).max(Direction.Axis.Y) != 1.0
                || !level.getBlockState(s.above()).getCollisionShape(level, s.above()).isEmpty()
                || !level.getFluidState(s).isEmpty() || !level.getFluidState(s.above()).isEmpty()) {
            return null;
        }
        return (double) s.getY();
    }

    private static void turn(Player p, float yaw, float pitch) {
        p.setYRot(yaw);
        p.setXRot(pitch);
        p.yRotO = yaw;
        p.xRotO = pitch;
    }

    private static boolean standAt(ClientGameTestContext ctx, Plan p) {
        ctx.getInput().releaseKey(o -> o.keyShift);
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID uuid = mc.player.getUUID();
            server.execute(() -> server.getPlayerList().getPlayer(uuid).teleportTo(server.overworld(), p.x(), p.feet(), p.z(),
                    Set.<Relative>of(), p.yaw(), p.pitch(), false));
        });
        for (int t = 0; t < 200; t++) {
            ctx.waitTicks(1);
            if (ctx.computeOnClient(mc -> Math.abs(mc.player.getX() - p.x()) < 0.01 && Math.abs(mc.player.getZ() - p.z()) < 0.01
                    && Math.abs(mc.player.getY() - p.feet()) < 0.01 && mc.player.onGround())) {
                return true;
            }
        }
        return false;
    }

    private static double[] serverPos(ClientGameTestContext ctx) {
        AtomicReference<double[]> now = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var srv = mc.getSingleplayerServer();
            UUID uuid = mc.player.getUUID();
            srv.execute(() -> {
                var sp = srv.getPlayerList().getPlayer(uuid);
                now.set(new double[]{sp.getX(), sp.getY(), sp.getZ(), sp.getYRot(), sp.getXRot()});
            });
        });
        ctx.waitFor(mc -> now.get() != null, 100);
        return now.get();
    }

    private static void warp(ClientGameTestContext ctx, String room, Plan p, boolean flick, List<String> failures, int[] totals) {
        String tag = String.format(Locale.ROOT, "%s from (%.2f, %.2f, %.2f) look %.3f/%.3f %s%s", room, p.x(), p.feet(), p.z(),
                p.yaw(), p.pitch(), p.kind(), flick ? " FLICK" : "");
        if (!standAt(ctx, p)) {
            println(tag + ": could not stand there - skipped");
            return;
        }
        ctx.getInput().holdKey(o -> o.keyShift);
        for (int t = 0; t < 40; t++) {
            ctx.waitTicks(1);
            if (ctx.computeOnClient(mc -> mc.player.isCrouching())) {
                break;
            }
        }
        ctx.waitTicks(3);
        String outcome;
        if (flick) {
            // The server hears a look 25 degrees off for a few ticks; then the turn and the click land in ONE tick.
            ctx.runOnClient(mc -> turn(mc.player, p.yaw() + 25f, p.pitch()));
            ctx.waitTicks(4);
            AtomicReference<String> drawn = new AtomicReference<>();
            ctx.runOnClient(mc -> {
                turn(mc.player, p.yaw(), p.pitch());
                BlockPos t = (BlockPos) ModUnderTest.staticCall(EW, "resolveTarget", new Class<?>[]{Level.class, Vec3.class,
                        Player.class, double.class}, new Object[]{mc.level, mc.player.position(), mc.player, RANGE});
                drawn.set(t == null ? "none" : "drawn " + t.toShortString() + " ok");
            });
            outcome = drawn.get();
        } else {
            ctx.runOnClient(mc -> turn(mc.player, p.yaw(), p.pitch()));
            ctx.waitTicks(4);
            outcome = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(EW, "lastOutcome"));
        }
        double[] before = serverPos(ctx);
        ctx.getInput().pressKey(o -> o.keyUse);
        // A warp onto the block he stands on moves him only 0.05 up (and to its center), hence the small threshold.
        double[] landed = null;
        for (int t = 0; t < 20 && landed == null; t++) {
            ctx.waitTicks(1);
            double[] n = serverPos(ctx);
            if (Math.abs(n[0] - before[0]) + Math.abs(n[1] - before[1]) + Math.abs(n[2] - before[2]) > 0.02) {
                landed = n;
            }
        }
        ctx.waitTicks(6);
        ctx.getInput().releaseKey(o -> o.keyShift);
        totals[flick ? 2 : 0]++;
        BlockPos shown = outcome.startsWith("drawn ") && outcome.endsWith(" ok")
                ? parse(outcome.substring(6, outcome.length() - 3)) : null;
        String landedText = landed == null ? "none" : String.format(Locale.ROOT, "(%.4f, %.4f, %.4f)", landed[0], landed[1], landed[2]);
        println(tag + " | overlay '" + outcome + "' | server heard " + String.format(Locale.ROOT, "%.3f/%.3f", before[3], before[4])
                + " | landed " + landedText);
        String why = null;
        if (shown == null) {
            why = "the overlay showed no landing: '" + outcome + "'";
        } else if (landed == null) {
            why = "the sim never moved him (overlay " + shown.toShortString() + ")";
        } else {
            double wantY = ctx.computeOnClient(mc -> (Double) ModUnderTest.staticCall(EW, "standYOn",
                    new Class<?>[]{Level.class, BlockPos.class}, new Object[]{mc.level, shown})) + 0.05;
            if (Math.abs(landed[0] - (shown.getX() + 0.5)) > 1e-6 || Math.abs(landed[2] - (shown.getZ() + 0.5)) > 1e-6
                    || Math.abs(landed[1] - wantY) > 1e-6) {
                why = "landed " + landedText + ", the overlay showed block " + shown.toShortString();
            }
        }
        if (why == null) {
            totals[flick ? 3 : 1]++;
        } else {
            failures.add(tag + ": " + why);
            println(tag + ": MISMATCH " + why);
        }
    }

    private static BlockPos parse(String xyz) {
        String[] a = xyz.split(",");
        return new BlockPos(Integer.parseInt(a[0].trim()), Integer.parseInt(a[1].trim()), Integer.parseInt(a[2].trim()));
    }

    private static void println(String line) {
        System.out.println("[" + NAME + "] " + line);
    }
}

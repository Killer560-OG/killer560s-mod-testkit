package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 1102-sim-ew-carpet (owner question 2026-11-02): "the etherwarp prediction is good on sim but doesnt work right on
 * carpets" - asked, killer560 said the trouble is only on the SIM. On Hypixel a carpet is solid to the etherwarp ray (the
 * mod's TeleportUtils and Etherwarp Overlay say so and stay so); the sim, which answers as the server
 * ({@code roomsim.SimAbilities.etherwarp}), must land exactly where they predict.
 *
 * <p>A flat singleplayer world with the sim switched on and a stone lane per station. For each station he stands at the
 * lane's west end sneaking with the sim's Aspect of the Void, turned onto the target, and presses USE through the real key
 * (so a target in reach goes out as a block click plus its use half, one out of reach as a bare use). Before the press
 * the case reads the overlay's outcome ({@code EtherwarpOverlayFeature.lastOutcome}) and the planner's ray
 * ({@code TeleportUtils.traverseVoxels} from the client eye); after it, where the sim's SERVER put him on the teleport
 * tick and where he settles. Stations: a carpet on the floor, near (in reach) and far; a carpet on a one-high step, near
 * and far; the step's side under a carpet (refused: the carpet fills the feet); standing ON a carpet, a warp to plain
 * floor and to a carpet on a step; and a plain stone control. Every station must move him (or, refused, not move him),
 * and the server's landing must be the predicted block's (x + 0.5, top + 0.05, z + 0.5).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimEtherwarpCarpetTests implements FabricClientGameTest {

    private static final String NAME = "1102-sim-ew-carpet";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String EW = "com.killer560.hub.etherwarpoverlay.EtherwarpOverlayFeature";
    private static final String EWC = "com.killer560.hub.etherwarpoverlay.EtherwarpOverlayConfig";
    private static final String TP = "com.killer560.hub.livemap.autoclear.TeleportUtils";

    private static final int X0 = 2000;
    private static final int Y = 150;
    private static final int Z0 = 2000;
    private static final double SNEAK_EYE = 1.27;

    /** One lane: what is built, where he stands, what he aims at, and whether the warp is refused. */
    private record Station(String name, int dx, boolean step, boolean carpetTarget, boolean standOnCarpet,
                           boolean sideAim, boolean refused) {
    }

    private static final List<Station> STATIONS = List.of(
            new Station("stone-control", 6, false, false, false, false, false),
            new Station("floor-far", 6, false, true, false, false, false),
            new Station("floor-near", 3, false, true, false, false, false),
            new Station("step-far", 6, true, true, false, false, false),
            new Station("step-near", 3, true, true, false, false, false),
            new Station("step-side", 6, true, true, false, true, true),
            new Station("from-carpet-floor", 6, false, false, true, false, false),
            new Station("from-carpet-step", 6, true, true, true, false, false));

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(20);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        List<String> failures = new ArrayList<>();
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            try {
                body(ctx, sp, failures);
            } catch (Throwable t) {
                failures.add("scenario threw: " + t);
                t.printStackTrace(System.out);
            } finally {
                ctx.getInput().releaseKey(o -> o.keyShift);
                ctx.runOnClient(mc -> {
                    try {
                        ModUnderTest.staticCall(SIM_STATE, "leave", new Class<?>[]{}, new Object[]{});
                    } catch (Throwable ignored) {
                        // cleanup never replaces the verdict
                    }
                });
            }
        }
        if (!failures.isEmpty()) {
            for (String f : failures) {
                println("FAIL " + f);
            }
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
        println("PASS");
    }

    private static int laneZ(int i) {
        return Z0 + 4 * i;
    }

    private static void body(ClientGameTestContext ctx, TestSingleplayerContext sp, List<String> failures) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                new Object[]{"gametest"}));
        boolean canAct = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_STATE, "canAct",
                new Class<?>[]{Minecraft.class}, new Object[]{mc}));
        if (!canAct) {
            failures.add("SimState.canAct is false - the sim would not answer the etherwarp");
            return;
        }
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(EWC), "setEnabled", true));
        sp.getServer().runOnServer(server -> {
            var level = server.overworld();
            for (int x = X0 - 4; x <= X0 + 12; x++) {
                for (int z = Z0 - 3; z <= laneZ(STATIONS.size()) + 3; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, Y - 1, z), Blocks.STONE.defaultBlockState());
                    for (int y = Y; y <= Y + 5; y++) {
                        level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                }
            }
            for (int i = 0; i < STATIONS.size(); i++) {
                Station s = STATIONS.get(i);
                int z = laneZ(i);
                int tx = X0 + s.dx();
                if (s.step()) {
                    level.setBlockAndUpdate(new BlockPos(tx, Y, z), Blocks.STONE.defaultBlockState());
                }
                if (s.carpetTarget()) {
                    level.setBlockAndUpdate(new BlockPos(tx, s.step() ? Y + 1 : Y, z), carpet("red_carpet"));
                }
                if (s.standOnCarpet()) {
                    level.setBlockAndUpdate(new BlockPos(X0, Y, z), carpet("brown_carpet"));
                }
            }
        });
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ITEMS, "give",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, "ASPECT_OF_THE_VOID"}));
        ctx.waitTicks(20);
        int slot = ctx.computeOnClient(mc -> {
            for (int i = 0; i < 9; i++) {
                var data = mc.player.getInventory().getItem(i).get(DataComponents.CUSTOM_DATA);
                if (data != null && "ASPECT_OF_THE_VOID".equals(data.copyTag().getStringOr("id", ""))) {
                    mc.player.getInventory().setSelectedSlot(i);
                    return i;
                }
            }
            return -1;
        });
        if (slot < 0) {
            failures.add("the sim did not give an Aspect of the Void");
            return;
        }
        int moved = 0;
        for (int i = 0; i < STATIONS.size(); i++) {
            if (station(ctx, sp, STATIONS.get(i), laneZ(i), failures)) {
                moved++;
            }
        }
        long expectMoves = STATIONS.stream().filter(s -> !s.refused()).count();
        println("stations that moved him: " + moved + " of " + expectMoves + " expected");
        if (moved != expectMoves) {
            failures.add("only " + moved + " of " + expectMoves + " warps moved him - the case proved too little");
        }
    }

    /** Runs one station; true when the sim moved him. */
    private static boolean station(ClientGameTestContext ctx, TestSingleplayerContext sp, Station s, int z,
                                   List<String> failures) {
        double feetY = s.standOnCarpet() ? Y + 0.0625 : Y;
        double sx = X0 + 0.5;
        double sz = z + 0.5;
        ctx.getInput().releaseKey(o -> o.keyShift);
        sp.getServer().runOnServer(server -> {
            var p = server.getPlayerList().getPlayers().get(0);
            p.teleportTo(server.overworld(), sx, feetY, sz, Set.of(), -90f, 0f, false);
        });
        boolean there = false;
        for (int t = 0; t < 1200 && !there; t++) {
            ctx.waitTicks(1);
            there = ctx.computeOnClient(mc -> mc.player != null && Math.abs(mc.player.getX() - sx) < 0.05
                    && Math.abs(mc.player.getZ() - sz) < 0.05 && Math.abs(mc.player.getY() - feetY) < 0.01
                    && mc.player.onGround() && !mc.level.getBlockState(BlockPos.containing(sx, Y - 1, sz)).isAir());
        }
        if (!there) {
            failures.add(s.name() + ": never stood at the lane start");
            return false;
        }
        ctx.getInput().holdKey(o -> o.keyShift);
        boolean crouched = false;
        for (int t = 0; t < 40 && !crouched; t++) {
            ctx.waitTicks(1);
            crouched = ctx.computeOnClient(mc -> mc.player.isCrouching());
        }
        ctx.waitTicks(4);
        // Aim: the target block's top center, or (side) the middle of the step's west face.
        int tx = X0 + s.dx();
        int targetY = s.step() ? (s.carpetTarget() && !s.sideAim() ? Y + 1 : Y) : (s.carpetTarget() ? Y : Y - 1);
        double topY = s.carpetTarget() && !s.sideAim() ? targetY + 0.0625 : targetY + 1.0;
        double aimX = s.sideAim() ? tx : tx + 0.5;
        double aimY = s.sideAim() ? Y + 0.5 : topY;
        double eyeY = ctx.computeOnClient(mc -> mc.player.getY()) + SNEAK_EYE;
        float pitch = (float) Math.toDegrees(Math.atan2(eyeY - aimY, aimX - sx));
        ctx.runOnClient(mc -> {
            mc.player.setYRot(-90f);
            mc.player.setXRot(pitch);
            mc.player.yRotO = -90f;
            mc.player.xRotO = pitch;
        });
        ctx.waitTicks(5);
        String outcome = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(EW, "lastOutcome"));
        String predicted = ctx.computeOnClient(mc -> {
            Vec3 eye = new Vec3(mc.player.getX(), mc.player.getY() + SNEAK_EYE, mc.player.getZ());
            Vec3 look = (Vec3) ModUnderTest.staticCall(TP, "getLook", new Class<?>[]{float.class, float.class},
                    new Object[]{mc.player.getYRot(), mc.player.getXRot()});
            Vec3 end = eye.add(look.scale(57.0));
            Object r = ModUnderTest.staticCall(TP, "traverseVoxels", new Class<?>[]{double.class, double.class,
                    double.class, double.class, double.class, double.class, boolean.class},
                    new Object[]{eye.x, eye.y, eye.z, end.x, end.y, end.z, true});
            return String.valueOf(r);
        });
        String hitBlock = ctx.computeOnClient(mc -> String.valueOf(mc.hitResult == null ? null : mc.hitResult.getType()));
        double[] before = serverPos(sp);
        ctx.getInput().pressKey(o -> o.keyUse);
        double[] landed = null;
        for (int t = 0; t < 20 && landed == null; t++) {
            ctx.waitTicks(1);
            double[] now = serverPos(sp);
            if (Math.abs(now[0] - before[0]) + Math.abs(now[2] - before[2]) > 1.0) {
                landed = now;
            }
        }
        ctx.waitTicks(25);
        double[] settled = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        ctx.getInput().releaseKey(o -> o.keyShift);

        // Expected: the predicted block (the planner's ray), feet on its top + 0.05.
        BlockPos want = new BlockPos(tx, targetY, z);
        String wantShort = tx + ", " + targetY + ", " + z;
        double wantTop = sp.getServer().computeOnServer(server -> {
            BlockState st = server.overworld().getBlockState(want);
            var shape = st.getCollisionShape(server.overworld(), want);
            return shape.isEmpty() ? 1.0 : shape.max(net.minecraft.core.Direction.Axis.Y);
        });
        double wantFeet = want.getY() + Math.max(1.0, Math.ceil(wantTop)) + 0.05;
        double wantSettle = want.getY() + wantTop;
        println(String.format(Locale.ROOT, "%s: eye %.4f pitch %.3f pick %s | overlay '%s' | TeleportUtils %s | server "
                        + "landing %s | settled (%.3f, %.4f, %.3f) | want block %s feet %.4f settle %.4f%s", s.name(), eyeY,
                pitch, hitBlock, outcome, predicted, landed == null ? "none" : String.format(Locale.ROOT,
                        "(%.4f, %.4f, %.4f)", landed[0], landed[1], landed[2]), settled[0], settled[1], settled[2],
                wantShort, wantFeet, wantSettle, s.refused() ? " (refused expected)" : ""));
        String okTag = s.refused() ? " failed" : " ok";
        if (!outcome.equals("drawn " + wantShort + okTag) && !(s.refused() && outcome.startsWith("failed"))) {
            failures.add(s.name() + ": the overlay said '" + outcome + "', expected block " + wantShort + okTag);
        }
        if (!predicted.contains("succeeded=" + !s.refused()) || !predicted.contains("x=" + tx + ", y=" + targetY + ", z=" + z)) {
            failures.add(s.name() + ": TeleportUtils predicted " + predicted + ", expected block " + wantShort);
        }
        if (s.refused()) {
            if (landed != null) {
                failures.add(String.format(Locale.ROOT, "%s: refused by the prediction, but the sim moved him to (%.3f, "
                        + "%.3f, %.3f)", s.name(), landed[0], landed[1], landed[2]));
            }
            return false;
        }
        if (landed == null) {
            failures.add(s.name() + ": the sim never moved him (prediction " + predicted + ")");
            return false;
        }
        if (Math.abs(landed[0] - (tx + 0.5)) > 1e-6 || Math.abs(landed[2] - (z + 0.5)) > 1e-6
                || Math.abs(landed[1] - wantFeet) > 1e-6) {
            failures.add(String.format(Locale.ROOT, "%s: the sim landed him at (%.4f, %.4f, %.4f), predicted (%.4f, %.4f, "
                    + "%.4f) on block %s", s.name(), landed[0], landed[1], landed[2], tx + 0.5, wantFeet, z + 0.5, wantShort));
        }
        if (Math.abs(settled[0] - (tx + 0.5)) > 1e-3 || Math.abs(settled[2] - (z + 0.5)) > 1e-3
                || Math.abs(settled[1] - wantSettle) > 1e-3) {
            failures.add(String.format(Locale.ROOT, "%s: he settled at (%.4f, %.4f, %.4f), not on top of block %s (y %.4f)",
                    s.name(), settled[0], settled[1], settled[2], wantShort, wantSettle));
        }
        return true;
    }

    /** By id: the color-variant {@code Blocks} constants differ between 26.2 and 26.3 (testkit CLAUDE.md). */
    private static BlockState carpet(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getValue(net.minecraft.resources.Identifier.withDefaultNamespace(id)).defaultBlockState();
    }

    private static double[] serverPos(TestSingleplayerContext sp) {
        return sp.getServer().computeOnServer(server -> {
            var p = server.getPlayerList().getPlayers().get(0);
            return new double[]{p.getX(), p.getY(), p.getZ()};
        });
    }

    private static void println(String line) {
        System.out.println("[" + NAME + "] " + line);
    }
}

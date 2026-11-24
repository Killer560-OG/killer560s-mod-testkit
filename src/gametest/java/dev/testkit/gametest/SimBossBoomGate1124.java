package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 1124-boss-ee3boom-* (ee3boomcheck-1124): AP3's BOOM beside P3 gate 2 judges the gate by the gate's own arch (or the
 * gate chat line), never by any block change around the hit. His 11-24 sim run: gate 2 was already down (blown on an
 * earlier try), Breaker Aura's digs beside the boom block read as "the gate broke", and one try waited 40 ticks and
 * stopped the route "superboom didn't break anything". A chain FAST_ALIGN, BOOM, STOPWATCH on the EE3 route's y 124
 * ledge beside gate 2 (his route's node after the boom), aimed at a gate-2 block in reach.
 * <ul>
 *   <li>gatedown: gate 2 blown before the chain starts - the BOOM is skipped with one chat line and the STOPWATCH runs
 *       (no 40-tick wait, no stop).</li>
 *   <li>breakerdig: an S1 fight (the sim does not blow gate 2 before its section), a block beside the boom block dug
 *       right after the use - no "the gate broke"; the 40-tick wait ends in "nothing broke".</li>
 *   <li>gateup: an S2 fight, gate standing - the boom blows it and confirms "the gate broke" (the arch watch works).</li>
 * </ul>
 */
final class SimBossBoomGate1124 {
    static final String DOWN = "1124-boss-ee3boom-gatedown";
    static final String DIG = "1124-boss-ee3boom-breakerdig";
    static final String UP = "1124-boss-ee3boom-gateup";
    /** His route's spot right after the boom: mid-air in the capture, so the case stands on the nearest floor beside gate 2. */
    private static final double X0 = 22.09, Y0 = 124, Z0 = 124.57;
    private static final double REACH = 4.4;
    private static final String FILE = "ee3boom-1124.json";

    private SimBossBoomGate1124() {
    }

    private static void say(String line) {
        System.out.println("[1124-boss-ee3boom] " + line);
    }

    /** The standing spot nearest his post-boom spot (floor below, two free blocks) whose eye meets a gate-2 block within reach. */
    private static double[] standSpot(Minecraft mc) {
        double[] best = null;
        for (BlockPos f : BlockPos.betweenClosed(18, 106, 118, 28, 127, 132)) {
            BlockPos below = f.below();
            if (mc.level.getBlockState(below).getCollisionShape(mc.level, below).isEmpty()
                    || !mc.level.getBlockState(f).getCollisionShape(mc.level, f).isEmpty()
                    || !mc.level.getBlockState(f.above()).getCollisionShape(mc.level, f.above()).isEmpty()) {
                continue;
            }
            double sy = below.getY() + mc.level.getBlockState(below).getCollisionShape(mc.level, below).max(net.minecraft.core.Direction.Axis.Y);
            double d = Math.hypot(Math.hypot(f.getX() + 0.5 - X0, sy - Y0), f.getZ() + 0.5 - Z0);
            if (best != null && d >= best[3]) {
                continue;
            }
            Vec3 eye = new Vec3(f.getX() + 0.5, sy + 1.62, f.getZ() + 0.5);
            for (BlockPos p : BlockPos.betweenClosed(16, 114, 125, 19, 138, 139)) {
                if (mc.level.getBlockState(p).isAir() || eye.distanceTo(Vec3.atCenterOf(p)) > REACH + 0.9) {
                    continue;
                }
                BlockHitResult h = mc.level.clip(new ClipContext(eye, Vec3.atCenterOf(p), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
                if (h.getType() == HitResult.Type.BLOCK && h.getBlockPos().equals(p) && eye.distanceTo(h.getLocation()) <= REACH - 0.3) {
                    best = new double[]{f.getX() + 0.5, sy, f.getZ() + 0.5, d};
                    break;
                }
            }
        }
        return best;
    }

    /** The AP3 executor's node state, for a case that never sees the boom's result line. */
    private static String execState(Minecraft mc) {
        StringBuilder b = new StringBuilder();
        try {
            Class<?> c = Class.forName(Ap3RuntimeTests.EXEC, false, SimBossBoomGate1124.class.getClassLoader());
            for (String n : new String[]{"activeNode", "step", "stepTicks", "boomBg", "actNode", "boomGate"}) {
                try {
                    var fl = c.getDeclaredField(n);
                    fl.setAccessible(true);
                    Object v = fl.get(null);
                    b.append(n).append('=').append(v == null ? "null" : v instanceof Number || v instanceof Enum<?> ? v : v.getClass().getSimpleName()).append(' ');
                } catch (ReflectiveOperationException e) {
                    b.append(n).append("=? ");
                }
            }
        } catch (ClassNotFoundException e) {
            b.append("no executor class");
        }
        return b.toString();
    }

    static void run(ClientGameTestContext ctx, List<String> f, String name) throws Exception {
        boolean dig = name.equals(DIG);
        boolean down = name.equals(DOWN);
        SimBossTests.start(ctx, dig ? "S1" : "S2", true);
        ctx.waitTicks(40);
        if (down) {
            ctx.runOnClient(mc -> {
                var srv = mc.getSingleplayerServer();
                srv.execute(() -> ModUnderTest.staticCall("com.killer560.hub.bosssim.BossFight", "superboomGate",
                        new Class<?>[]{ServerLevel.class, BlockPos.class},
                        new Object[]{srv.getPlayerList().getPlayers().get(0).level(), new BlockPos(19, 127, 125)}));
            });
            ctx.waitTicks(10);
            if (SimBossTests.gateCells(ctx, 2)[0] != 0) {
                f.add(name + ": setup - gate 2's arch still stands after the sim's blow");
                return;
            }
        }
        SimBossTests.tp(ctx, X0, Y0, Z0, 0f, 0f);
        ctx.waitTicks(5);
        // That spot is mid-air in the capture: stand on the nearest floor beside gate 2 with a gate block in reach.
        double[] spot = ctx.computeOnClient(SimBossBoomGate1124::standSpot);
        if (spot == null) {
            f.add(name + ": setup - no floor near " + X0 + " " + Y0 + " " + Z0 + " with a gate-2 block in reach");
            return;
        }
        SimBossTests.tp(ctx, spot[0], spot[1], spot[2], 0f, 0f);
        ctx.waitTicks(10);
        double[] at = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        final double X = at[0], Y = at[1], Z = at[2];
        // A block of gate 2 the eye meets straight on within reach: the BOOM's own look.
        double[] aim = ctx.computeOnClient(mc -> {
            Vec3 eye = mc.player.getEyePosition();
            double[] best = null;
            for (BlockPos p : BlockPos.betweenClosed(16, 114, 125, 19, 138, 139)) {
                if (mc.level.getBlockState(p).isAir()) {
                    continue;
                }
                Vec3 c = Vec3.atCenterOf(p);
                double d = eye.distanceTo(c);
                if (d > REACH + 0.9 || best != null && d >= best[2]) {
                    continue;
                }
                BlockHitResult h = mc.level.clip(new ClipContext(eye, c, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
                if (h.getType() == HitResult.Type.BLOCK && h.getBlockPos().equals(p) && eye.distanceTo(h.getLocation()) <= REACH) {
                    Vec3 v = c.subtract(eye);
                    best = new double[]{Math.toDegrees(Math.atan2(-v.x, v.z)), -Math.toDegrees(Math.atan2(v.y, Math.hypot(v.x, v.z))), d,
                            p.getX(), p.getY(), p.getZ(), mc.player.onGround() ? 1 : 0};
                }
            }
            return best == null ? new double[]{0, 0, -1, 0, 0, 0, mc.player.onGround() ? 1 : 0} : best;
        });
        BlockPos target = new BlockPos((int) aim[3], (int) aim[4], (int) aim[5]);
        SimBossTests.tp(ctx, X + 3, Y, Z, 0f, 0f); // out of the chain's box while it is installed
        say(String.format(Locale.ROOT, "%s: at %.2f %.2f %.2f ground %s, boom aim %.1f/%.1f at %s", name, X, Y, Z, aim[6] > 0,
                aim[0], aim[1], aim[2] < 0 ? "nothing" : target.toShortString()));
        if (aim[2] < 0 || aim[6] == 0) {
            f.add(name + ": setup - no gate-2 block in reach from the ledge (ground " + (aim[6] > 0) + ")");
            return;
        }
        String route = String.format(Locale.ROOT, "{\"version\":2,\"chains\":{\"BOSS\":{\"class\":\"\",\"nodes\":["
                + "{\"type\":\"FAST_ALIGN\",\"x\":%.4f,\"y\":%.1f,\"z\":%.4f,\"width\":1.0,\"length\":1.0},"
                + "{\"type\":\"BOOM\",\"x\":%.4f,\"y\":%.1f,\"z\":%.4f,\"yaw\":%.3f,\"pitch\":%.3f,\"width\":1.0,\"length\":1.0},"
                + "{\"type\":\"STOPWATCH\",\"x\":%.4f,\"y\":%.1f,\"z\":%.4f,\"width\":1.0,\"length\":1.0,\"name\":\"bt\"},"
                // A later node out of reach, as in his route: the boom is never the chain's last node.
                + "{\"type\":\"STOPWATCH\",\"x\":%.4f,\"y\":%.1f,\"z\":%.4f,\"width\":1.0,\"length\":1.0,\"name\":\"far\"}]}}}",
                X, Y, Z, X, Y, Z, aim[0], aim[1], X, Y, Z, X + 8, Y, Z);
        ctx.runOnClient(mc -> {
            try {
                Object cfg = ModUnderTest.config(Ap3RuntimeTests.CONFIG);
                ModUnderTest.set(cfg, "setEnabled", true);
                ModUnderTest.staticCall(Ap3RuntimeTests.FEATURE, "setForceDungeon", new Class<?>[]{boolean.class}, new Object[]{false});
                Path dir = (Path) ModUnderTest.staticCall(Ap3RuntimeTests.STORE, "directory");
                Files.createDirectories(dir);
                Files.writeString(dir.resolve(FILE), route);
                ModUnderTest.staticCall(Ap3RuntimeTests.STORE, "select", new Class<?>[]{String.class}, new Object[]{FILE});
                ModUnderTest.staticCall(Ap3RuntimeTests.STORE, "reload");
                ModUnderTest.staticCall("com.killer560.hub.roomsim.SimItems", "give",
                        new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, "SUPERBOOM_TNT"});
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        ctx.waitTicks(10);
        long mark = LogTap.mark();
        // Into the chain's first box (all three nodes share the spot, as the EE3 route's end STOPWATCH and FAST_ALIGN do).
        SimBossTests.tp(ctx, X, Y, Z, 0f, 0f);
        int used = -1, skipped = -1, broke = -1, nothing = -1, watch = -1, stopped = -1;
        BlockPos dug = null;
        for (int t = 0; t < 120; t++) {
            ctx.waitTick();
            for (String l : LogTap.since(mark)) {
                if (used < 0 && l.contains("use sent: Superboom tap")) used = t;
                if (skipped < 0 && l.contains("skipped: gate 2 is already down")) skipped = t;
                if (broke < 0 && l.contains("result: the gate broke")) broke = t;
                if (nothing < 0 && l.contains("result: nothing broke")) nothing = t;
                if (watch < 0 && l.contains("Stopwatch bt")) watch = t;
                if (stopped < 0 && l.contains("[CHAT]") && l.contains("Stopped")) stopped = t;
            }
            if (dig && used >= 0 && dug == null) {
                // Breaker Aura's dig beside the boom block, the way his picks 19,125-126,123 sat in the old 5x5x5 watch.
                BlockPos tg = target;
                dug = ctx.computeOnClient(mc -> {
                    for (BlockPos p : BlockPos.betweenClosed(tg.offset(-2, -2, -2), tg.offset(2, 2, 2))) {
                        BlockState s = mc.level.getBlockState(p);
                        if (!s.isAir() && !s.is(Blocks.CRACKED_STONE_BRICKS) && !s.is(Blocks.STONE_BRICK_STAIRS) && !p.equals(tg)) {
                            return p.immutable();
                        }
                    }
                    return null;
                });
                if (dug == null) {
                    f.add(name + ": setup - no non-arch block beside " + target.toShortString() + " to dig");
                    break;
                }
                BlockPos d = dug;
                ctx.runOnClient(mc -> {
                    mc.level.setBlock(d, Blocks.AIR.defaultBlockState(), 11);
                    var srv = mc.getSingleplayerServer();
                    srv.execute(() -> srv.getPlayerList().getPlayers().get(0).level().setBlock(d, Blocks.AIR.defaultBlockState(), 3));
                });
                say(name + ": dug " + d.toShortString() + " at t" + t);
            }
            if (t % 20 == 19) {
                say(name + ": t" + t + " executor " + ctx.computeOnClient(SimBossBoomGate1124::execState));
            }
            if (down && (watch >= 0 || stopped >= 0) || !down && (broke >= 0 || nothing >= 0) && t > Math.max(broke, nothing) + 10) {
                break;
            }
        }
        int arch = SimBossTests.gateCells(ctx, 2)[0];
        say(String.format(Locale.ROOT, "RESULT %s: use t%d, skipped t%d, broke t%d, nothing t%d, stopwatch t%d, stopped t%d, dug %s, arch left %d",
                name, used, skipped, broke, nothing, watch, stopped, dug == null ? "-" : dug.toShortString(), arch));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(Ap3RuntimeTests.EXEC, "stop", new Class<?>[]{String.class},
                new Object[]{"test over"}));
        if (down) {
            if (skipped < 0) {
                f.add(name + ": the BOOM was not skipped with gate 2 already down (use t" + used + ", broke t" + broke + ")");
            }
            if (watch < 0) {
                f.add(name + ": the route did not go on to the STOPWATCH (stopped t" + stopped + ", nothing t" + nothing + ")");
            } else if (skipped >= 0 && watch - skipped > 5) {
                f.add(name + ": the STOPWATCH came " + (watch - skipped) + " ticks after the skip");
            }
            if (used >= 0 || nothing >= 0 || stopped >= 0) {
                f.add(name + ": a boom went out or waited anyway (use t" + used + ", nothing t" + nothing + ", stopped t" + stopped + ")");
            }
        } else if (dig) {
            if (used < 0 || dug == null) {
                f.add(name + ": the boom never went out or nothing was dug (use t" + used + ")");
            }
            if (broke >= 0) {
                f.add(name + ": a dig beside the boom block confirmed the gate (broke t" + broke + ", arch left " + arch + ")");
            }
            if (nothing < 0) {
                f.add(name + ": the boom's wait never ended in 'nothing broke'");
            }
            if (arch == 0) {
                f.add(name + ": setup - the sim blew gate 2 in S1");
            }
        } else {
            if (used < 0 || broke < 0 || arch != 0) {
                f.add(name + ": gate standing - use t" + used + ", broke t" + broke + ", arch left " + arch);
            }
            if (skipped >= 0 || nothing >= 0) {
                f.add(name + ": a standing gate was skipped or read as unbroken (skipped t" + skipped + ", nothing t" + nothing + ")");
            }
        }
    }
}

package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mod optimal-blocks-reachable-1124 (killer560, 2026-11-24: "for the blocks to break those all need to be touching air and easy
 * to break not these very edges taht are super hard to reach", and "for the best spots to mine on a tree those should always
 * show for any tree of the type that i broke last on the island I am currently on").
 * 1124-foraging-optimal-reachable builds a Galatea-like fig tree on a stone pad: a 3x3 trunk with its center column fully
 * enclosed and two long branches whose tips hang out past the pad (a tip is what the old fewest-swings plan hit first). Every
 * pick of the plan must have a face open to a no-collision block and a clear line within 4.5 blocks from an eye over a ground
 * spot (this case's own raycast, not the mod's), the plan must fell every log, and none is drawn as a hard pick.
 * 1124-foraging-optimal-all-trees: two fig trees and a mangrove; before any break only the tree he is at is planned; a fig
 * log he attacks and the server then removes puts plans on BOTH fig trees and none on the mangrove; a mangrove break moves
 * them to the mangrove; leaving Galatea clears it.
 */
final class HxSweepReach1124Cases {

    private static final String O = "foraging.SweepOverlay";
    private static final String R = "foraging.SweepRule";

    private HxSweepReach1124Cases() {
    }

    static void register(Session s) {
        s.test("1124-foraging-optimal-reachable", HxSweepReach1124Cases::reachable);
        s.test("1124-foraging-optimal-all-trees", HxSweepReach1124Cases::allTrees);
    }

    /** Sets blocks client-side, remembering the old state of each for {@link #restore}. */
    private static void put(Session c, Map<BlockPos, BlockState> saved, BlockPos p, Block b) {
        c.ctx().runOnClient(mc -> {
            saved.putIfAbsent(p, mc.level.getBlockState(p));
            mc.level.setServerVerifiedBlockState(p, b.defaultBlockState(), 3);
        });
    }

    private static void restore(Session c, Map<BlockPos, BlockState> saved) {
        c.ctx().runOnClient(mc -> saved.forEach((p, s) -> mc.level.setServerVerifiedBlockState(p, s, 3)));
    }

    private static void pad(Session c, Map<BlockPos, BlockState> saved, BlockPos feet, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                BlockPos p = feet.offset(dx, -1, dz);
                boolean air = c.onClient(mc -> mc.level.getBlockState(p).isAir());
                if (air) {
                    put(c, saved, p, Blocks.STONE);
                }
            }
        }
    }

    private static boolean free(Minecraft mc, Set<Long> tree, BlockPos p) {
        return !tree.contains(p.asLong()) && mc.level.getBlockState(p).getCollisionShape(mc.level, p).isEmpty();
    }

    /** True when some open face of {@code pick} is in reach of an eye over a ground spot with a clear line (own check). */
    private static boolean practical(Minecraft mc, Set<Long> tree, BlockPos pick) {
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                for (int dy = -7; dy <= 2; dy++) {
                    BlockPos s = pick.offset(dx, dy, dz);
                    BlockState under = mc.level.getBlockState(s.below());
                    if (!free(mc, tree, s) || !free(mc, tree, s.above()) || under.getCollisionShape(mc.level, s.below()).isEmpty()
                            || under.is(BlockTags.LOGS) || under.is(BlockTags.LEAVES)) {
                        continue;
                    }
                    Vec3 eye = new Vec3(s.getX() + 0.5, s.getY() + 1.62, s.getZ() + 0.5);
                    for (Direction d : Direction.values()) {
                        if (!free(mc, tree, pick.relative(d))) {
                            continue;
                        }
                        Vec3 n = new Vec3(d.getStepX(), d.getStepY(), d.getStepZ());
                        Vec3 f = Vec3.atCenterOf(pick).add(n.scale(0.5));
                        for (double[] o : new double[][]{{0, 0}, {0.3, 0.3}, {-0.3, 0.3}, {0.3, -0.3}, {-0.3, -0.3}}) {
                            Vec3 q = d.getAxis() == Direction.Axis.X ? f.add(0, o[0], o[1])
                                    : d.getAxis() == Direction.Axis.Y ? f.add(o[0], 0, o[1]) : f.add(o[0], o[1], 0);
                            if (eye.distanceTo(q) > 4.5 || eye.subtract(q).dot(n) <= 0) {
                                continue;
                            }
                            HitResult h = mc.level.clip(new ClipContext(eye, q.subtract(n.scale(0.05)), ClipContext.Block.COLLIDER,
                                    ClipContext.Fluid.NONE, mc.player));
                            if (h.getType() == HitResult.Type.BLOCK && ((BlockHitResult) h).getBlockPos().equals(pick)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Logs left standing after every swing of {@code plan} in order, or -1 when a swing hits a log already felled. */
    private static int leftAfter(Set<Long> tree, long[] plan, int n) {
        Set<Long> rem = new HashSet<>(tree);
        for (long p : plan) {
            if (!rem.contains(p)) {
                return -1;
            }
            for (long f : (long[]) Mod.staticCall(R, "fill", p, n, (java.util.function.LongPredicate) rem::contains)) {
                rem.remove(f);
            }
        }
        return rem.size();
    }

    private static Object[] enable(Session c) {
        Object cfg = Mod.field(O, "CFG"), opt = Mod.field(O, "OPT");
        boolean was = c.onClient(mc -> (Boolean) Mod.call(opt, "isEnabledRaw"));
        c.ctx().runOnClient(mc -> {
            Mod.call(cfg, "set", "sweep", 285);
            Mod.call(cfg, "set", "thrown", false);
            Mod.call(opt, "setEnabled", true);
            Mod.staticCall(O, "setTestIsland", true);
        });
        return new Object[]{cfg, opt, was};
    }

    private static void disable(Session c, Object[] st, Map<BlockPos, BlockState> saved) {
        c.ctx().runOnClient(mc -> {
            Mod.staticCall(O, "setTestIsland", false);
            Mod.call(st[1], "setEnabled", st[2]);
            Mod.call(st[0], "load");
            Mod.call(st[1], "load");
        });
        restore(c, saved);
    }

    private static void reachable(Session c) {
        Map<BlockPos, BlockState> saved = new HashMap<>();
        BlockPos f = c.onClient(mc -> mc.player.blockPosition());
        List<BlockPos> logs = new ArrayList<>();
        for (int x = 2; x <= 4; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = 0; y <= 4; y++) {
                    logs.add(f.offset(x, y, z));
                }
            }
        }
        logs.add(f.offset(3, 5, 0));   // cap: the center column (3, 0..4, 0) is enclosed on every side
        int[][] east = {{5, 5}, {6, 5}, {7, 6}, {8, 6}, {9, 7}, {10, 7}};
        for (int[] e : east) {
            logs.add(f.offset(e[0], e[1], 0));
        }
        int[][] north = {{-2, 5}, {-3, 5}, {-4, 6}, {-5, 6}, {-6, 7}, {-7, 7}, {-8, 8}, {-9, 8}};
        for (int[] e : north) {
            logs.add(f.offset(3, e[1], e[0]));
        }
        Set<Long> tree = new HashSet<>();
        logs.forEach(p -> tree.add(p.asLong()));
        Object[] st = enable(c);
        try {
            pad(c, saved, f, 7);
            for (BlockPos p : logs) {
                put(c, saved, p, Blocks.STRIPPED_SPRUCE_WOOD);
            }
            c.ctx().runOnClient(mc -> {
                mc.player.setYRot(-90f);
                mc.player.setXRot(20f);
            });
            c.waitUntil("a plan for the tree", mc -> ((long[]) Mod.staticCall(O, "optimalPlan")).length > 0, 120);
            c.ctx().waitTicks(10);
            long[] plan = c.onClient(mc -> (long[]) Mod.staticCall(O, "optimalPlan"));
            String shown = Arrays.toString(Arrays.stream(plan).mapToObj(l -> BlockPos.of(l).subtract(f).toShortString()).toArray());
            int bad = 0;
            for (long p : plan) {
                BlockPos b = BlockPos.of(p);
                boolean ok = c.onClient(mc -> practical(mc, tree, b));
                c.check(ok, "pick " + b.subtract(f).toShortString() + " (from his feet) has no open face in reach of the ground; plan " + shown);
                bad += ok ? 0 : 1;
            }
            int left = c.onClient(mc -> leftAfter(tree, plan, 21));
            c.check(left == 0, "the plan " + shown + " leaves " + left + " of " + tree.size() + " logs standing (-1: a pick already felled)");
            c.check(plan.length >= 3 && plan.length <= 4, "plan " + shown + " has " + plan.length + " swings, wanted 3 or 4 for " + tree.size() + " logs at 21");
            boolean hasHard = c.onClient(mc -> {
                try {
                    return (Integer) Mod.staticCall(O, "optimalHard") > 0;
                } catch (AssertionError e) {
                    return false;
                }
            });
            c.check(!hasHard, "an easy plan exists but a pick is drawn as hard");
            c.check(bad == 0 && left == 0, "plan " + shown + ": " + bad + " impractical picks");
        } finally {
            disable(c, st, saved);
        }
    }

    private static void breakLog(Session c, Map<BlockPos, BlockState> saved, BlockPos p) {
        c.ctx().runOnClient(mc -> AttackBlockCallback.EVENT.invoker().interact(mc.player, mc.level, InteractionHand.MAIN_HAND, p, Direction.UP));
        c.ctx().waitTicks(2);
        put(c, saved, p, Blocks.AIR);
    }

    /** How many of {@code plans} hold a pick in each tree, by tree index; -1 at the end for picks in no tree. */
    private static int[] owners(long[][] plans, List<Set<Long>> trees) {
        int[] out = new int[trees.size() + 1];
        for (long[] pl : plans) {
            boolean any = false;
            for (int t = 0; t < trees.size(); t++) {
                if (pl.length > 0 && trees.get(t).contains(pl[0])) {
                    out[t]++;
                    any = true;
                }
            }
            out[trees.size()] += any ? 0 : 1;
        }
        return out;
    }

    private static void allTrees(Session c) {
        Map<BlockPos, BlockState> saved = new HashMap<>();
        BlockPos f = c.onClient(mc -> mc.player.blockPosition());
        BlockPos[] roots = {f.offset(-4, 0, -4), f.offset(-4, 0, 4), f.offset(4, 0, 4)};
        Block[] kinds = {Blocks.STRIPPED_SPRUCE_LOG, Blocks.STRIPPED_SPRUCE_LOG, Blocks.MANGROVE_LOG};
        List<Set<Long>> trees = new ArrayList<>();
        Object[] st = enable(c);
        try {
            pad(c, saved, f, 7);
            for (int t = 0; t < 3; t++) {
                Set<Long> set = new HashSet<>();
                for (int y = 0; y < 6; y++) {
                    put(c, saved, roots[t].above(y), kinds[t]);
                    set.add(roots[t].above(y).asLong());
                }
                trees.add(set);
            }
            c.ctx().waitTicks(10);
            c.check(c.onClient(mc -> (Integer) Mod.staticCall(O, "forestKind")) == -1, "a kind was set before any break");
            breakLog(c, saved, roots[0].above(5));
            trees.get(0).remove(roots[0].above(5).asLong());
            c.waitUntil("plans on both fig trees", mc -> (Integer) Mod.staticCall(O, "forestKind") == 0
                    && ((long[][]) Mod.staticCall(O, "forestPlans")).length == 2, 200);
            int[] own = owners(c.onClient(mc -> (long[][]) Mod.staticCall(O, "forestPlans")), trees);
            c.check(own[0] == 1 && own[1] == 1 && own[2] == 0 && own[3] == 0, "after a fig break, plans per tree " + Arrays.toString(own) + ", wanted [1, 1, 0, 0]");
            c.check(c.onClient(mc -> (Integer) Mod.staticCall(O, "optimalBoxes")) >= 2, "fig plans not boxed");
            int frames0 = c.onClient(mc -> (Integer) Mod.staticCall(O, "boxFrames"));
            c.ctx().waitTicks(10);
            c.check(c.onClient(mc -> (Integer) Mod.staticCall(O, "boxFrames")) > frames0, "fig plans not drawn");
            breakLog(c, saved, roots[2].above(5));
            trees.get(2).remove(roots[2].above(5).asLong());
            c.waitUntil("plans move to the mangrove", mc -> (Integer) Mod.staticCall(O, "forestKind") == 1
                    && ((long[][]) Mod.staticCall(O, "forestPlans")).length == 1, 200);
            own = owners(c.onClient(mc -> (long[][]) Mod.staticCall(O, "forestPlans")), trees);
            c.check(own[0] == 0 && own[1] == 0 && own[2] == 1 && own[3] == 0, "after a mangrove break, plans per tree " + Arrays.toString(own) + ", wanted [0, 0, 1, 0]");
            // A log removed without his attack (another player, a regrowing tree) does not change the kind.
            put(c, saved, roots[1].above(5), Blocks.AIR);
            c.ctx().waitTicks(10);
            c.check(c.onClient(mc -> (Integer) Mod.staticCall(O, "forestKind")) == 1, "a log he did not attack changed the kind");
            c.ctx().runOnClient(mc -> Mod.staticCall(O, "setTestIsland", false));
            c.waitUntil("leaving the island clears it", mc -> (Integer) Mod.staticCall(O, "forestKind") == -1
                    && (Integer) Mod.staticCall(O, "optimalBoxes") == 0, 80);
        } finally {
            disable(c, st, saved);
        }
    }
}

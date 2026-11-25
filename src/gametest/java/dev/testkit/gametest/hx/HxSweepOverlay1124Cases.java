package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Mod sweepoverlay-build-1124 (killer560, 2026-11-23: Sweep Overlay and Highlight Optimal Blocks). The rule: a swing fells
 * the hit log plus one log per wiki Sweep threshold of the tree kind that the Sweep stat reaches, spreading over all 26
 * neighbors nearest first; a thrown axe halves it. 1124-foraging-sweep-rule checks thresholds, the count 21 at Sweep 285 on
 * fig, the half for a thrown axe, and three REAL swings of his 11-23 hand chopping (log offsets from the hit, in the order
 * the server removed them, from the logger session): the predicted 21 logs must lie inside the measured tree and match at
 * least 20 of the first 21 removed. 1124-foraging-sweep-overlay drives the live overlay: looking at a fake 30-log trunk
 * outlines 21 boxes, a thrown axe 10, Highlight Optimal Blocks boxes the WHOLE plan at once (30 logs at 21 a swing: 2 targets, at 3 a swing: 10, all drawn before a block is broken; breaking the first swing's logs promotes the next target), and nothing draws off Galatea.
 */
final class HxSweepOverlay1124Cases {

    private static final String O = "foraging.SweepOverlay";
    private static final String R = "foraging.SweepRule";

    /** Offsets from the hit log in the order the server removed them (swings at ticks 10791, 9381, 10606). */
    private static final String[] REAL = {
            "0,0,0 0,1,-1 0,1,0 1,1,-2 0,2,-3 1,1,-3 -1,2,-4 2,1,-4 -1,2,-5 2,2,-5 0,1,1 1,1,1 2,1,1 2,1,2 2,2,0 2,2,1 3,1,2 3,2,0 4,1,2 4,2,2 4,2,3 4,2,0",
            "0,0,0 -1,-1,1 0,-1,0 0,-1,1 0,0,1 0,1,1 1,-1,1 1,0,1 1,1,1 0,-2,2 0,-1,2 0,0,2 1,-2,2 1,-1,2 0,1,2 0,2,1 0,2,2 1,2,1 0,-3,2 0,-2,3 0,3,0 0,3,1",
            "0,0,0 -1,0,-1 -1,0,0 -1,1,-1 -1,1,0 -2,0,-2 -2,0,-1 -2,0,0 -2,1,-1 -1,0,-2 -1,1,-2 -2,2,-1 -1,2,-1 -1,2,0 -3,0,-1 -3,1,-1 -2,3,-1 -1,3,-1 -2,4,-1 -1,4,-2 -1,4,-1 -1,4,0"};

    private HxSweepOverlay1124Cases() {
    }

    static void register(Session s) {
        s.test("1124-foraging-sweep-rule", HxSweepOverlay1124Cases::rule);
        s.test("1124-foraging-sweep-overlay", HxSweepOverlay1124Cases::overlay);
    }

    private static int logs(Session c, int kind, double sweep, boolean thrown) {
        return c.onClient(mc -> (Integer) Mod.staticCall(R, "logs", kind, sweep, thrown));
    }

    private static void put(Session c, BlockPos p, Block b) {
        c.ctx().runOnClient(mc -> mc.level.setServerVerifiedBlockState(p, b.defaultBlockState(), 3));
    }

    private static long[] predict(Session c, BlockPos hit, double sweep, boolean thrown) {
        return c.onClient(mc -> (long[]) Mod.staticCall(O, "predictAt", hit.asLong(), sweep, thrown));
    }

    /** The fewest swings that fell every log of {@code logs} at n a swing (exhaustive over the swing targets; pure rule calls). */
    @SuppressWarnings("unchecked")
    private static int minSwings(Set<Long> logs, int n, int limit) {
        if (logs.isEmpty()) {
            return 0;
        }
        if (limit == 0) {
            return 99;
        }
        int best = 99;
        for (long t : logs) {
            Set<Long> rest = new HashSet<>(logs);
            for (long f : (long[]) Mod.staticCall(R, "fill", t, n, (java.util.function.LongPredicate) logs::contains)) {
                rest.remove(f);
            }
            best = Math.min(best, 1 + minSwings(rest, n, Math.min(limit, best) - 1));
        }
        return best;
    }

    private static void rule(Session c) {
        c.check(logs(c, 0, 285, false) == 21, "fig at 285 felled " + logs(c, 0, 285, false) + ", wanted 21");
        c.check(logs(c, 0, 6, false) == 1 && logs(c, 0, 7, false) == 2, "fig 6/7: " + logs(c, 0, 6, false) + "/" + logs(c, 0, 7, false));
        c.check(logs(c, 0, 233, false) == 20 && logs(c, 0, 234, false) == 21, "fig 233/234: " + logs(c, 0, 233, false) + "/" + logs(c, 0, 234, false));
        c.check(logs(c, 1, 49, false) == 1 && logs(c, 1, 50, false) == 2 && logs(c, 1, 1055, false) == 21, "mangrove edges");
        c.check(logs(c, 2, 149, false) == 1 && logs(c, 2, 2190, false) == 21 && logs(c, 2, 99999, false) == 21, "helix edges");
        c.check(logs(c, 0, 285, true) == 10 && logs(c, 0, 0, true) == 1, "thrown: " + logs(c, 0, 285, true) + "/" + logs(c, 0, 0, true));
        c.check(logs(c, -1, 285, false) == 0, "an unknown kind felled " + logs(c, -1, 285, false));
        BlockPos base = c.onClient(mc -> mc.player.blockPosition().above(14));
        for (int s = 0; s < REAL.length; s++) {
            String[] parts = REAL[s].split(" ");
            List<BlockPos> tree = new ArrayList<>();
            for (String p : parts) {
                String[] x = p.split(",");
                tree.add(base.offset(Integer.parseInt(x[0]), Integer.parseInt(x[1]), Integer.parseInt(x[2])));
            }
            try {
                c.ctx().runOnClient(mc -> Mod.staticCall(O, "setTestIsland", true));
                for (BlockPos p : tree) {
                    put(c, p, Blocks.STRIPPED_SPRUCE_WOOD);
                }
                long[] got = predict(c, base, 285, false);
                Set<Long> first21 = new HashSet<>();
                for (int i = 0; i < 21; i++) {
                    first21.add(tree.get(i).asLong());
                }
                Set<Long> all = new HashSet<>();
                tree.forEach(p -> all.add(p.asLong()));
                int inTree = 0, match = 0;
                for (long l : got) {
                    inTree += all.contains(l) ? 1 : 0;
                    match += first21.contains(l) ? 1 : 0;
                }
                c.check(got.length == 21 && inTree == 21 && match >= 20,
                        "real swing " + s + ": predicted " + got.length + ", inside the measured tree " + inTree + ", matching the first 21 removed " + match);
                long[] half = predict(c, base, 285, true);
                c.check(half.length == 10, "real swing " + s + " thrown predicted " + half.length);
                long[] three = predict(c, base, 9, false);
                c.check(three.length == 3, "fig Sweep 9 predicted " + three.length + ", wanted 3");
            } finally {
                for (BlockPos p : tree) {
                    put(c, p, Blocks.AIR);
                }
            }
        }
        // The plan: the exact fewest swings on a Y-shaped tree (5-log trunk, two 2-log arms) and on lines, at 3 and 21 a swing.
        BlockPos y0 = base.above(30);
        List<BlockPos> ytree = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ytree.add(y0.above(i));
        }
        ytree.add(y0.offset(1, 5, 0));
        ytree.add(y0.offset(2, 6, 0));
        ytree.add(y0.offset(-1, 5, 0));
        ytree.add(y0.offset(-2, 6, 0));
        for (int n : new int[]{3, 4, 21}) {
            Set<Long> set = new HashSet<>();
            ytree.forEach(p -> set.add(p.asLong()));
            long[] arr = set.stream().mapToLong(Long::longValue).toArray();
            int[] res = c.onClient(mc -> new int[]{((long[]) Mod.staticCall(R, "plan", arr, n)).length, minSwings(set, n, 6)});
            c.check(res[0] == res[1] && res[0] >= 1, "Y tree at " + n + " a swing: plan " + res[0] + " swings, exact minimum " + res[1]);
        }
        for (int len : new int[]{30, 25, 9}) {
            long[] line = new long[len];
            for (int i = 0; i < len; i++) {
                line[i] = y0.offset(10, i, 10).asLong();
            }
            int got = c.onClient(mc -> ((long[]) Mod.staticCall(R, "plan", line, 21)).length);
            c.check(got == (len + 20) / 21, "line of " + len + " at 21 a swing: plan " + got + ", wanted " + (len + 20) / 21);
        }
        // Diagonal-only neighbors count; another tree kind next to it does not.
        BlockPos a = base.above(8), diag = a.offset(1, 1, 1), other = a.east(), far = a.offset(5, 0, 0);
        try {
            put(c, a, Blocks.STRIPPED_SPRUCE_WOOD);
            put(c, diag, Blocks.STRIPPED_SPRUCE_LOG);
            put(c, other, Blocks.MANGROVE_LOG);
            put(c, far, Blocks.STRIPPED_SPRUCE_WOOD);
            Set<Long> got = new HashSet<>();
            for (long l : predict(c, a, 285, false)) {
                got.add(l);
            }
            c.check(got.size() == 2 && got.contains(a.asLong()) && got.contains(diag.asLong()),
                    "diagonal / other kind: " + got.size() + " logs, diagonal " + got.contains(diag.asLong()) + ", mangrove " + got.contains(other.asLong()) + ", far " + got.contains(far.asLong()));
        } finally {
            for (BlockPos p : new BlockPos[]{a, diag, other, far}) {
                put(c, p, Blocks.AIR);
            }
            c.ctx().runOnClient(mc -> Mod.staticCall(O, "setTestIsland", false));
        }
    }

    private static int boxes(Session c, String name) {
        return c.onClient(mc -> (Integer) Mod.staticCall(O, name));
    }

    private static void overlay(Session c) {
        Object cfg = Mod.field(O, "CFG"), opt = Mod.field(O, "OPT");
        boolean was = c.onClient(mc -> (Boolean) Mod.call(cfg, "isEnabledRaw"));
        boolean wasOpt = c.onClient(mc -> (Boolean) Mod.call(opt, "isEnabledRaw"));
        BlockPos eye = c.onClient(mc -> BlockPos.containing(mc.player.getEyePosition()));
        List<BlockPos> trunk = new ArrayList<>();
        for (int i = -10; i < 20; i++) {
            trunk.add(eye.east(2).above(i));
        }
        try {
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "set", "sweep", 285);
                Mod.call(cfg, "set", "thrown", false);
                Mod.call(opt, "setEnabled", true);
                Mod.staticCall(O, "setTestIsland", true);
            });
            for (BlockPos p : trunk) {
                put(c, p, Blocks.STRIPPED_SPRUCE_WOOD);
            }
            c.ctx().runOnClient(mc -> {
                mc.player.setYRot(-90f);
                mc.player.setXRot(0f);
            });
            c.waitUntil("21 boxes for the looked-at log", mc -> (Integer) Mod.staticCall(O, "overlayBoxes") == 21, 120);
            // The whole plan at once: 30 logs at 21 a swing = 2 targets. The trunk runs from 10 below his eye (under the platform)
            // to 19 above, so only trunk 9..14 (platform level to 4 up) can be hit from the ground (optimal-blocks-reachable-1124):
            // the first target is the lowest of those, trunk 9 (fells 0..20), and the rest is one hard target, trunk 21.
            c.waitUntil("2 plan boxes", mc -> (Integer) Mod.staticCall(O, "optimalBoxes") == 2, 60);
            c.ctx().waitTicks(20);
            c.check(boxes(c, "boxFrames") > 0, "boxes built but never drawn");
            long[] p21 = c.onClient(mc -> (long[]) Mod.staticCall(O, "optimalPlan"));
            c.check(p21.length == 2 && p21[0] == trunk.get(9).asLong() && p21[1] == trunk.get(21).asLong(),
                    "plan at 21: " + java.util.Arrays.toString(p21) + ", wanted trunk 9 then 21");
            c.check(boxes(c, "optimalHard") == 1, "hard targets at 21: " + boxes(c, "optimalHard") + ", wanted 1 (trunk 21)");
            // Sweep 9 = 3 logs a swing: 11 swings, the 3 the ground reaches first (trunk 9, 14, 11: each first takes the closed or out-of-reach logs it can), then 8 hard; all stand
            // before any block is broken.
            c.ctx().runOnClient(mc -> Mod.call(cfg, "set", "sweep", 9));
            c.waitUntil("11 plan boxes", mc -> (Integer) Mod.staticCall(O, "optimalBoxes") == 11, 60);
            long[] p3 = c.onClient(mc -> (long[]) Mod.staticCall(O, "optimalPlan"));
            int[] easy3 = {9, 14, 11};
            for (int i = 0; i < 3; i++) {
                c.check(p3.length == 11 && p3[i] == trunk.get(easy3[i]).asLong(), "plan at 3, target " + i + ": " + (p3.length > i ? BlockPos.of(p3[i]).toString() : "missing") + ", wanted trunk " + easy3[i]);
            }
            c.check(boxes(c, "optimalHard") == 8, "hard targets at 3: " + boxes(c, "optimalHard") + ", wanted 8");
            long frames0 = boxes(c, "boxFrames");
            c.ctx().waitTicks(10);
            c.check(boxes(c, "boxFrames") > frames0, "plan boxes not drawn each frame");
            c.check(trunk.stream().allMatch(p -> c.onClient(mc -> !mc.level.getBlockState(p).isAir())), "a log was broken before the plan was shown");
            // Fell the first swing's logs: the first target and its box go, the next target is promoted.
            long[] fell = c.onClient(mc -> (long[]) Mod.staticCall(O, "predictAt", p3[0], 9.0, false));
            for (long f : fell) {
                put(c, BlockPos.of(f), Blocks.AIR);
            }
            // Planned again on what stands (the felled logs open new faces, so the order may change): no felled log in it.
            c.waitUntil("a new plan after the first swing", mc -> {
                long[] a = (long[]) Mod.staticCall(O, "optimalPlan");
                return a.length > 0 && a[0] != p3[0];
            }, 80);
            long[] after = c.onClient(mc -> (long[]) Mod.staticCall(O, "optimalPlan"));
            Set<Long> felled = new HashSet<>();
            for (long f : fell) {
                felled.add(f);
            }
            c.check(after.length >= 10 && after.length <= 11 && java.util.Arrays.stream(after).noneMatch(felled::contains),
                    "plan after the first swing: " + java.util.Arrays.toString(after) + ", wanted 10 or 11 targets, none felled");
            c.check(fell.length == 3, "a swing at 9 felled " + fell.length);
            c.ctx().runOnClient(mc -> Mod.call(cfg, "set", "sweep", 285));
            c.waitUntil("2 plan boxes on the 27 logs left", mc -> (Integer) Mod.staticCall(O, "optimalBoxes") == 2, 80);
            // That swing felled the log he looks at (trunk 10); put the three back so the overlay below has a log to outline.
            for (long f : fell) {
                put(c, BlockPos.of(f), Blocks.STRIPPED_SPRUCE_WOOD);
            }
            c.waitUntil("21 boxes again for the looked-at log", mc -> (Integer) Mod.staticCall(O, "overlayBoxes") == 21, 60);
            c.ctx().runOnClient(mc -> Mod.call(cfg, "set", "thrown", true));
            c.waitUntil("10 boxes for a thrown axe", mc -> (Integer) Mod.staticCall(O, "overlayBoxes") == 10, 60);
            c.ctx().runOnClient(mc -> Mod.call(cfg, "set", "sweep", 9));
            c.ctx().runOnClient(mc -> Mod.call(cfg, "set", "thrown", false));
            c.waitUntil("3 boxes at Sweep 9", mc -> (Integer) Mod.staticCall(O, "overlayBoxes") == 3, 60);
            c.ctx().runOnClient(mc -> Mod.staticCall(O, "setTestIsland", false));
            c.waitUntil("nothing off Galatea", mc -> (Integer) Mod.staticCall(O, "overlayBoxes") == 0
                    && (Integer) Mod.staticCall(O, "optimalBoxes") == 0, 60);
        } finally {
            c.ctx().runOnClient(mc -> {
                for (BlockPos p : trunk) {
                    mc.level.setServerVerifiedBlockState(p, Blocks.AIR.defaultBlockState(), 3);
                }
                Mod.staticCall(O, "setTestIsland", false);
                Mod.call(cfg, "setEnabled", was);
                Mod.call(opt, "setEnabled", wasOpt);
                Mod.call(cfg, "load");
                Mod.call(opt, "load");
            });
        }
    }
}

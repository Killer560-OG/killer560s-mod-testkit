package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Regenerating Trees (killer560, 2026-11-24: "it should only highlight wood that is there not the wood coming back ...
 * fill, fill outline and outline as well as a mode to recolor the entire block"). A four-log trunk is the fixture. Cases
 * drive the real ClientLevel.setServerVerifiedBlockState (the path of every server block update) and assert on the
 * feature's own draw set: the standing logs of a chopped tree are drawn, a broken log's position is not, a regrown log is
 * drawn again, a tree nobody chopped and a non-Galatea chop draw nothing, an old chop expires; each Style draws its own
 * layers; the Style survives a save and load.
 */
final class HxTreeRegen1123Cases {

    private static final String T = "foraging.TreeRegen";

    private HxTreeRegen1123Cases() {
    }

    static void register(Session s) {
        s.test("1123-foraging-treeregen", HxTreeRegen1123Cases::regen);
        s.test("1124-foraging-treeregen-self-chunk", HxTreeRegen1123Cases::selfAndChunk);
        s.test("1124-foraging-treeregen-styles", HxTreeRegen1123Cases::styles);
        s.test("1124-foraging-treeregen-onstart", HxTreeRegen1123Cases::onStart);
        s.test("1124-foraging-treeregen-lingers", HxTreeRegen1123Cases::lingers);
    }

    private static int num(Session c, String m) {
        return c.onClient(mc -> (Integer) Mod.staticCall(T, m));
    }

    private static boolean draws(Session c, BlockPos p) {
        return c.onClient(mc -> (Boolean) Mod.staticCall(T, "drawsAt", p));
    }

    private static void set(Session c, BlockPos p, BlockState st) {
        c.ctx().runOnClient(mc -> mc.level.setServerVerifiedBlockState(p, st, 3));
    }

    private static void on(Session c) {
        c.ctx().runOnClient(mc -> {
            Mod.call(Mod.field(T, "CFG"), "setEnabled", true);
            Mod.staticCall(T, "setTestIsland", true);
            // Chops left by an earlier case's cleanup would add to the counts: age them out first.
            Mod.staticCall(T, "ageAll", 2000);
        });
        c.waitUntil("old chops gone", mc -> (Integer) Mod.staticCall(T, "trackedLogs") == 0, 60);
    }

    /** A four-log trunk above the player; returns its logs bottom to top. */
    private static BlockPos[] trunk(Session c) {
        BlockPos base = c.onClient(mc -> mc.player.blockPosition().above(6));
        BlockPos[] t = {base, base.above(), base.above(2), base.above(3)};
        for (BlockPos p : t) {
            set(c, p, Blocks.OAK_LOG.defaultBlockState());
        }
        return t;
    }

    private static void off(Session c, BlockPos[] t, boolean wasOn) {
        c.ctx().runOnClient(mc -> {
            for (BlockPos p : t) {
                mc.level.setServerVerifiedBlockState(p, Blocks.AIR.defaultBlockState(), 3);
            }
            Mod.staticCall(T, "setTestIsland", false);
            Mod.call(Mod.field(T, "CFG"), "setEnabled", wasOn);
            Mod.call(Mod.field(T, "CFG"), "load");
        });
    }

    private static void regen(Session c) {
        boolean wasOn = c.onClient(mc -> (Boolean) Mod.call(Mod.field(T, "CFG"), "isEnabledRaw"));
        on(c);
        BlockPos[] t = trunk(c);
        try {
            c.ctx().waitTicks(12);
            c.check(num(c, "drawnBoxes") == 0 && num(c, "trackedLogs") == 0,
                    "a tree nobody chopped was drawn: " + num(c, "drawnBoxes"));
            // Chopping logs draws nothing: the tree has not started regenerating (killer560, 11-24).
            set(c, t[1], Blocks.AIR.defaultBlockState());
            c.ctx().waitTicks(12);
            c.check(num(c, "trackedLogs") == 1 && num(c, "drawnBoxes") == 0,
                    "a tree was drawn on its first broken block: " + num(c, "drawnBoxes"));
            set(c, t[3], Blocks.AIR.defaultBlockState());
            c.ctx().waitTicks(12);
            c.check(num(c, "trackedLogs") == 2 && num(c, "drawnBoxes") == 0,
                    "a tree was drawn on its second broken block: " + num(c, "drawnBoxes"));
            // The first chopped log comes back (block update): the tree is regenerating, its standing logs are drawn.
            set(c, t[1], Blocks.OAK_LOG.defaultBlockState());
            c.waitUntil("standing logs drawn", mc -> (Integer) Mod.staticCall(T, "drawnBoxes") == 3, 60);
            c.check(draws(c, t[0]) && draws(c, t[1]) && draws(c, t[2]), "a standing log was not drawn");
            c.check(!draws(c, t[3]), "the still-missing log's position was drawn");
            c.waitUntil("frames drawn", mc -> (Integer) Mod.staticCall(T, "boxFrames") > 0, 60);
            // Break another: its mark goes at once, the others stay.
            set(c, t[2], Blocks.AIR.defaultBlockState());
            c.waitUntil("broken log undrawn", mc -> (Integer) Mod.staticCall(T, "drawnBoxes") == 2, 60);
            c.check(!draws(c, t[3]) && !draws(c, t[2]) && draws(c, t[0]) && draws(c, t[1]),
                    "after the break the draw set is wrong");
            // A non-log going to air is ignored.
            BlockPos s = t[0].east(3);
            set(c, s, Blocks.STONE.defaultBlockState());
            set(c, s, Blocks.AIR.defaultBlockState());
            c.check(num(c, "trackedLogs") == 2, "a stone block going to air was tracked: " + num(c, "trackedLogs"));
            // Off Galatea: a chop tracks nothing.
            c.ctx().runOnClient(mc -> Mod.staticCall(T, "setTestIsland", false));
            c.ctx().runOnClient(mc -> mc.level.setServerVerifiedBlockState(t[0], Blocks.AIR.defaultBlockState(), 3));
            c.check(num(c, "trackedLogs") == 2, "a chop off Galatea was tracked: " + num(c, "trackedLogs"));
            c.ctx().runOnClient(mc -> Mod.staticCall(T, "setTestIsland", true));
            // Expiry: aged past 1400 ticks, the tree is no longer regenerating and nothing is drawn.
            c.ctx().runOnClient(mc -> Mod.staticCall(T, "ageAll", 1500));
            c.waitUntil("expired", mc -> (Integer) Mod.staticCall(T, "drawnBoxes") == 0
                    && (Integer) Mod.staticCall(T, "trackedLogs") == 0, 60);
        } finally {
            off(c, t, wasOn);
        }
    }

    /** His own break is a client-side setBlock (the server's air arrives later); a return can come as chunk data. */
    private static void selfAndChunk(Session c) {
        boolean wasOn = c.onClient(mc -> (Boolean) Mod.call(Mod.field(T, "CFG"), "isEnabledRaw"));
        on(c);
        BlockPos[] t = trunk(c);
        try {
            c.ctx().runOnClient(mc -> mc.level.setBlock(t[1], Blocks.AIR.defaultBlockState(), 11));
            c.check(num(c, "trackedLogs") == 1, "a log he broke himself (client-side air first) was not tracked");
            c.ctx().waitTicks(12);
            c.check(num(c, "drawnBoxes") == 0, "a tree was drawn on his own break: " + num(c, "drawnBoxes"));
            set(c, t[1], Blocks.AIR.defaultBlockState());
            c.check(num(c, "trackedLogs") == 1, "the server's later air changed the count: " + num(c, "trackedLogs"));
            set(c, t[3], Blocks.AIR.defaultBlockState());
            c.ctx().runOnClient(mc -> mc.level.getChunkAt(t[1]).setBlockState(t[1], Blocks.OAK_LOG.defaultBlockState(), 0));
            c.waitUntil("chunk-data return drawn", mc -> (Integer) Mod.staticCall(T, "drawnBoxes") == 3, 60);
            c.check(draws(c, t[1]), "the log that came back as chunk data was not drawn");
        } finally {
            off(c, t, wasOn);
        }
    }

    /** A tree is drawn only from its first returning log: nothing while it is chopped, even to the last log. */
    private static void onStart(Session c) {
        boolean wasOn = c.onClient(mc -> (Boolean) Mod.call(Mod.field(T, "CFG"), "isEnabledRaw"));
        on(c);
        BlockPos[] t = trunk(c);
        try {
            set(c, t[1], Blocks.AIR.defaultBlockState());
            c.ctx().waitTicks(12);
            c.check(num(c, "trackedLogs") == 1 && num(c, "drawnBoxes") == 0 && !draws(c, t[0]),
                    "one broken block drew the tree: " + num(c, "drawnBoxes"));
            for (BlockPos p : new BlockPos[]{t[0], t[2], t[3]}) {
                set(c, p, Blocks.AIR.defaultBlockState());
            }
            c.ctx().waitTicks(12);
            c.check(num(c, "trackedLogs") == 4 && num(c, "drawnBoxes") == 0, "a fully chopped tree drew boxes");
            set(c, t[2], Blocks.OAK_LOG.defaultBlockState());
            c.waitUntil("first return drawn", mc -> (Integer) Mod.staticCall(T, "drawnBoxes") == 1, 60);
            c.check(draws(c, t[2]) && !draws(c, t[1]), "the returned log was not the one drawn");
            set(c, t[0], Blocks.OAK_LOG.defaultBlockState());
            c.waitUntil("second return drawn", mc -> (Integer) Mod.staticCall(T, "drawnBoxes") == 2, 60);
        } finally {
            off(c, t, wasOn);
        }
    }

    /**
     * killer560, 11-24: "the regrowing tree thing is sticking around a long time after the tree is fully regenerated". The
     * highlight is gone within 2 ticks of the last log's return (single update, section update: the same
     * setServerVerifiedBlockState path), within the 10-tick recheck when it comes back as chunk data, and a second tree
     * still missing a log nearby (same chunk) does not keep a whole one drawn.
     */
    private static void lingers(Session c) {
        boolean wasOn = c.onClient(mc -> (Boolean) Mod.call(Mod.field(T, "CFG"), "isEnabledRaw"));
        on(c);
        BlockPos[] t = trunk(c);
        BlockPos other = c.onClient(mc -> mc.player.blockPosition().above(6).east(5));
        BlockState log = Blocks.OAK_LOG.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        try {
            set(c, other, log);
            set(c, other, air);
            for (BlockPos p : t) {
                set(c, p, air);
            }
            c.ctx().waitTicks(2);
            for (int i = 0; i < 3; i++) {
                set(c, t[i], log);
            }
            c.waitUntil("standing logs drawn", mc -> (Integer) Mod.staticCall(T, "drawnBoxes") == 3, 60);
            // The last log returns as a single block update: gone within 2 ticks, though the other tree is still missing a log.
            set(c, t[3], log);
            c.ctx().waitTicks(2);
            c.check(num(c, "drawnBoxes") == 0 && !draws(c, t[0]), "the whole tree is still drawn 2 ticks after its last log returned: "
                    + num(c, "drawnBoxes"));
            c.check(num(c, "trackedLogs") == 1, "tracked chops after the tree is whole: " + num(c, "trackedLogs"));
            // Second round: the returns arrive as a section update (same path), the last one as chunk data.
            for (BlockPos p : t) {
                set(c, p, air);
            }
            c.ctx().waitTicks(2);
            for (int i = 0; i < 3; i++) {
                set(c, t[i], log);
            }
            c.waitUntil("standing logs drawn again", mc -> (Integer) Mod.staticCall(T, "drawnBoxes") == 3, 60);
            c.ctx().runOnClient(mc -> mc.level.getChunkAt(t[3]).setBlockState(t[3], log, 0));
            c.waitUntil("chunk-data last return clears", mc -> (Integer) Mod.staticCall(T, "drawnBoxes") == 0, 14);
        } finally {
            c.ctx().runOnClient(mc -> mc.level.setServerVerifiedBlockState(other, air, 3));
            off(c, t, wasOn);
        }
    }

    private static void style(Session c, int v) {
        c.ctx().runOnClient(mc -> Mod.call(Mod.field(T, "CFG"), "set", "style", v));
    }

    private static int[] layers(Session c) {
        return c.onClient(mc -> (int[]) Mod.staticCall(T, "layerFrames"));
    }

    /** Each Style draws its own layers (fill quads, outline lines, recolor layer); the Style is saved. */
    private static void styles(Session c) {
        boolean wasOn = c.onClient(mc -> (Boolean) Mod.call(Mod.field(T, "CFG"), "isEnabledRaw"));
        on(c);
        BlockPos[] t = trunk(c);
        // fill, line, recolor frames expected to grow per Style: Fill, Fill + Outline, Outline, Recolor Block.
        boolean[][] grows = {{true, false, false}, {true, true, false}, {false, true, false}, {false, false, true}};
        try {
            set(c, t[1], Blocks.AIR.defaultBlockState());
            set(c, t[3], Blocks.AIR.defaultBlockState());
            set(c, t[1], Blocks.OAK_LOG.defaultBlockState());
            c.waitUntil("drawn", mc -> (Integer) Mod.staticCall(T, "drawnBoxes") == 3, 60);
            for (int v = 0; v < 4; v++) {
                style(c, v);
                c.ctx().waitTicks(25);
                int[] a = layers(c);
                c.ctx().waitTicks(20);
                int[] b = layers(c);
                for (int i = 0; i < 3; i++) {
                    c.check((b[i] > a[i]) == grows[v][i], "style " + v + " layer " + i + " frames " + a[i] + " -> " + b[i]
                            + ", expected growth " + grows[v][i]);
                }
            }
            c.ctx().runOnClient(mc -> {
                Mod.call(Mod.field(T, "CFG"), "set", "style", 3);
                Mod.call(Mod.field(T, "CFG"), "save");
                Mod.call(Mod.field(T, "CFG"), "set", "style", 0);
                Mod.call(Mod.field(T, "CFG"), "load");
            });
            int saved = c.onClient(mc -> (Integer) Mod.call(Mod.field(T, "CFG"), "num", "style"));
            c.check(saved == 3, "the Style was not saved: " + saved);
        } finally {
            style(c, 2);
            c.ctx().runOnClient(mc -> Mod.call(Mod.field(T, "CFG"), "save"));
            off(c, t, wasOn);
        }
    }
}

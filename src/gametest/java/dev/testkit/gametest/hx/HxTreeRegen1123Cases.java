package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * Mod forage-sweep-regen-1123 (killer560, 2026-11-23: "highlight trees that are regenerating"). His recordings show a
 * chopped log becoming air and the same log returning about 40 s later. 1123-foraging-treeregen drives the real
 * ClientLevel.setServerVerifiedBlockState (the path of every server block update) and asserts: a log that becomes air is
 * tracked and outlined (boxes drawn), the same log coming back clears it, a non-log going to air is ignored, nothing is
 * tracked off Galatea, and a tracked log expires after 1400 ticks.
 */
final class HxTreeRegen1123Cases {

    private static final String T = "foraging.TreeRegen";

    private HxTreeRegen1123Cases() {
    }

    static void register(Session s) {
        s.test("1123-foraging-treeregen", HxTreeRegen1123Cases::regen);
        s.test("1124-foraging-treeregen-self-chunk", HxTreeRegen1123Cases::selfAndChunk);
    }

    private static int tracked(Session c) {
        return c.onClient(mc -> (Integer) Mod.staticCall(T, "trackedLogs"));
    }

    private static void set(Session c, BlockPos p, net.minecraft.world.level.block.state.BlockState st) {
        c.ctx().runOnClient(mc -> mc.level.setServerVerifiedBlockState(p, st, 3));
    }

    private static void regen(Session c) {
        boolean wasOn = c.onClient(mc -> (Boolean) Mod.call(Mod.field(T, "CFG"), "isEnabledRaw"));
        BlockPos base = c.onClient(mc -> mc.player.blockPosition().above(6));
        BlockPos a = base, b = base.east(2), d = base.east(4);
        try {
            c.ctx().runOnClient(mc -> {
                Mod.call(Mod.field(T, "CFG"), "setEnabled", true);
                Mod.staticCall(T, "setTestIsland", true);
            });
            // A log leaves (becomes air): tracked and outlined.
            set(c, a, Blocks.OAK_LOG.defaultBlockState());
            c.check(tracked(c) == 0, "placing a log tracked something: " + tracked(c));
            set(c, a, Blocks.AIR.defaultBlockState());
            c.check(tracked(c) == 1, "a chopped log was not tracked: " + tracked(c));
            c.ctx().waitTicks(40);
            int drawn = c.onClient(mc -> (Integer) Mod.staticCall(T, "drawnBoxes"));
            int frames = c.onClient(mc -> (Integer) Mod.staticCall(T, "boxFrames"));
            c.check(drawn == 1 && frames > 0, "boxes built " + drawn + ", drawn frames " + frames + ", tracked " + tracked(c));
            // A non-log going to air is ignored.
            set(c, b, Blocks.STONE.defaultBlockState());
            set(c, b, Blocks.AIR.defaultBlockState());
            c.check(tracked(c) == 1, "a stone block going to air was tracked: " + tracked(c));
            // The log returns: cleared.
            set(c, a, Blocks.OAK_LOG.defaultBlockState());
            c.check(tracked(c) == 0, "a returned log stayed tracked: " + tracked(c));
            c.waitUntil("boxes cleared", mc -> (Integer) Mod.staticCall(T, "drawnBoxes") == 0, 60);
            // Off Galatea: nothing.
            c.ctx().runOnClient(mc -> Mod.staticCall(T, "setTestIsland", false));
            set(c, d, Blocks.OAK_LOG.defaultBlockState());
            set(c, d, Blocks.AIR.defaultBlockState());
            c.check(tracked(c) == 0, "tracked off Galatea: " + tracked(c));
            // Expiry: tracked, then aged past 1400 ticks and gone.
            c.ctx().runOnClient(mc -> Mod.staticCall(T, "setTestIsland", true));
            set(c, d, Blocks.OAK_LOG.defaultBlockState());
            set(c, d, Blocks.AIR.defaultBlockState());
            c.check(tracked(c) == 1, "log not tracked before expiry: " + tracked(c));
            c.ctx().runOnClient(mc -> Mod.staticCall(T, "ageAll", 1500));
            c.waitUntil("expired", mc -> (Integer) Mod.staticCall(T, "trackedLogs") == 0, 60);
        } finally {
            c.ctx().runOnClient(mc -> {
                for (BlockPos p : new BlockPos[]{a, b, d}) {
                    mc.level.setServerVerifiedBlockState(p, Blocks.AIR.defaultBlockState(), 3);
                }
                Mod.staticCall(T, "setTestIsland", false);
                Mod.call(Mod.field(T, "CFG"), "setEnabled", wasOn);
                Mod.call(Mod.field(T, "CFG"), "load");
            });
        }
    }

    /** His own break is a client-side setBlock (the server's air arrives later); a return can come as chunk data. */
    private static void selfAndChunk(Session c) {
        boolean wasOn = c.onClient(mc -> (Boolean) Mod.call(Mod.field(T, "CFG"), "isEnabledRaw"));
        BlockPos a = c.onClient(mc -> mc.player.blockPosition().above(6));
        try {
            c.ctx().runOnClient(mc -> {
                Mod.call(Mod.field(T, "CFG"), "setEnabled", true);
                Mod.staticCall(T, "setTestIsland", true);
            });
            set(c, a, Blocks.OAK_LOG.defaultBlockState());
            c.ctx().runOnClient(mc -> mc.level.setBlock(a, Blocks.AIR.defaultBlockState(), 11));
            c.check(tracked(c) == 1, "a log he broke himself (client-side air first) was not tracked: " + tracked(c));
            set(c, a, Blocks.AIR.defaultBlockState());
            c.check(tracked(c) == 1, "the server's later air changed the count: " + tracked(c));
            c.ctx().runOnClient(mc -> mc.level.getChunkAt(a).setBlockState(a, Blocks.OAK_LOG.defaultBlockState(), 0));
            c.waitUntil("chunk-data return cleared", mc -> (Integer) Mod.staticCall(T, "trackedLogs") == 0, 60);
        } finally {
            c.ctx().runOnClient(mc -> {
                mc.level.setServerVerifiedBlockState(a, Blocks.AIR.defaultBlockState(), 3);
                Mod.staticCall(T, "setTestIsland", false);
                Mod.call(Mod.field(T, "CFG"), "setEnabled", wasOn);
                Mod.call(Mod.field(T, "CFG"), "load");
            });
        }
    }
}

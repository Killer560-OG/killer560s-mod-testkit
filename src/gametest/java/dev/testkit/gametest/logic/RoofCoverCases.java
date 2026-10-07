package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 363-logic-roof-cover: the Interactive Map must never land him on top of the dungeon, on Hypixel as well as in the
 * sim. killer560 (2026-10-06): "It should never take me outside of the map." An F5 map path on Hypixel stood him at
 * y 100 on the corner of four rooms, because {@code TeleportUtils.underCover} (and the floor graph's copy of it)
 * returned true for every landing outside the sim. This runs OUTSIDE the sim, in the logic suite's throwaway world:
 * a block high above floor height with open sky over it must be refused, the same block under a ceiling accepted,
 * and a landing at floor height accepted without looking. On a jar before the fix the first check fails.
 */
final class RoofCoverCases {

    private static final String TP = "livemap.autoclear.TeleportUtils";

    private RoofCoverCases() {
    }

    static void roofCover(LogicCase c) throws Exception {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            c.check("a level exists", false, "no client level");
            return;
        }
        c.eq("not in the sim (this is the Hypixel shape)", false, Mod.staticCall("roomsim.SimState", "isActive"));
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        // High in the sky over the spawn, so nothing generated is near it; the scan looks 64 blocks up from +3.
        BlockPos roof = new BlockPos(4, 200, 4);
        BlockPos ceiling = roof.above(8);
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        for (int y = roof.getY(); y <= roof.getY() + 70; y++) {
            BlockPos p = new BlockPos(roof.getX(), y, roof.getZ());
            before.put(p, level.getBlockState(p));
        }
        try {
            for (BlockPos p : before.keySet()) {
                level.setBlock(p, air, 19);
            }
            level.setBlock(roof, stone, 19);
            c.check("the roof block is stone with open sky over it", level.getBlockState(roof).is(Blocks.STONE)
                    && level.getBlockState(roof.above(3)).isAir(), "" + level.getBlockState(roof));
            c.eq("a landing high above floor height with only sky over it is refused (the roof)", false,
                    Mod.staticCall(TP, "underCover", roof));

            level.setBlock(ceiling, stone, 19);
            c.eq("the same landing under a ceiling is accepted (inside a room)", true,
                    Mod.staticCall(TP, "underCover", roof));

            c.eq("a landing at floor height is accepted without looking", true,
                    Mod.staticCall(TP, "underCover", new BlockPos(4, 70, 4)));
        } finally {
            for (Map.Entry<BlockPos, BlockState> e : before.entrySet()) {
                level.setBlock(e.getKey(), e.getValue(), 19);
            }
        }
    }
}

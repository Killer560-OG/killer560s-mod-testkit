package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * 1121-sim-witherdoor-nobreak (mod sim-witherdoor-1121, killer560 2026-11-21: "i cannot break them with dungeon breaker"):
 * the Dungeonbreaker and Superboom (left and right click) leave every block of a wither door and of the blood door in
 * place on the server AND the client, with and without a key, while the same tool still breaks a normal block (the
 * control: the tool acted). The guard Breaker Aura, Zero Ping and Auto Routes ask (BreakerGuard.protects) says protected.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimWitherDoorNoBreak1121Tests implements FabricClientGameTest {
    private static final String NAME = "1121-sim-witherdoor-nobreak";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        List<String> failures = new ArrayList<>();
        int probes = 0;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            SimWitherDoor1121.enter(ctx, sp);
            for (int type : new int[]{SimWitherDoor1121.WITHER, SimWitherDoor1121.BLOOD}) {
                for (boolean key : new boolean[]{false, true}) {
                    for (String mode : new String[]{"DUNGEONBREAKER-left", "SUPERBOOM_TNT-left", "SUPERBOOM_TNT-right"}) {
                        String item = mode.substring(0, mode.indexOf('-'));
                        boolean left = mode.endsWith("left");
                        var d = SimWitherDoor1121.fresh(ctx, sp, type);
                        String label = (type == SimWitherDoor1121.BLOOD ? "blood" : "wither") + " " + mode + (key ? " with key" : " no key");
                        if (d.blocks().isEmpty()) {
                            failures.add(label + ": no door blocks registered");
                            continue;
                        }
                        // Control blocks beside the door: stone brick (Dungeonbreaker) and cracked brick (Superboom).
                        int maxZ = d.blocks().stream().mapToInt(BlockPos::getZ).max().getAsInt();
                        BlockPos stoneCtl = new BlockPos(d.stand().getX() + 1, d.stand().getY(), d.stand().getZ() + 2);
                        BlockPos crackedCtl = new BlockPos(d.target().getX(), d.target().getY(), maxZ + 1);
                        sp.getServer().runOnServer(s -> {
                            s.overworld().setBlockAndUpdate(stoneCtl, Blocks.STONE_BRICKS.defaultBlockState());
                            s.overworld().setBlockAndUpdate(crackedCtl, Blocks.CRACKED_STONE_BRICKS.defaultBlockState());
                        });
                        SimWitherDoor1121.setKeys(key ? 1 : 0, key);
                        SimWitherDoor1121.hold(ctx, sp, item);
                        int before = SimWitherDoor1121.solid(ctx, sp, d)[0];
                        if (left) SimWitherDoor1121.leftClick(ctx, d.target());
                        else SimWitherDoor1121.rightClick(ctx, d.target());
                        ctx.waitTicks(40);
                        // Right click with a key opens the door through its own rule (a click, not a break): only the no-key
                        // right click and every left click must leave it shut.
                        boolean mayOpen = !left && (key || type == SimWitherDoor1121.BLOOD); // off a mob floor the Blood door needs no key
                        int[] after = SimWitherDoor1121.solid(ctx, sp, d);
                        if (!mayOpen && (before == 0 || after[0] != before || after[1] != before)) {
                            failures.add(label + ": door blocks " + before + " -> server " + after[0] + " client " + after[1]);
                        }
                        probes++;
                        boolean guarded = sp.getServer().computeOnServer(s -> (Boolean) ModUnderTest.staticCall(
                                "com.killer560.hub.dungeonbreaker.BreakerGuard", "protects",
                                new Class<?>[]{net.minecraft.world.level.BlockGetter.class, BlockPos.class},
                                new Object[]{s.overworld(), d.target()}));
                        if (!mayOpen && !guarded) failures.add(label + ": BreakerGuard.protects does not cover the door block");
                    }
                }
            }
            // CONTROL: the same tools still break a normal block.
            var c = SimWitherDoor1121.fresh(ctx, sp, SimWitherDoor1121.WITHER);
            BlockPos stone = new BlockPos(c.stand().getX() - 1, c.stand().getY(), c.stand().getZ());
            BlockPos cracked = new BlockPos(c.stand().getX() + 1, c.stand().getY(), c.stand().getZ() + 3);
            sp.getServer().runOnServer(s -> {
                s.overworld().setBlockAndUpdate(stone, Blocks.STONE_BRICKS.defaultBlockState());
                s.overworld().setBlockAndUpdate(cracked, Blocks.CRACKED_STONE_BRICKS.defaultBlockState());
            });
            SimWitherDoor1121.hold(ctx, sp, "DUNGEONBREAKER");
            SimWitherDoor1121.leftClick(ctx, stone);
            ctx.waitTicks(20);
            boolean dbBroke = sp.getServer().computeOnServer(s -> s.overworld().getBlockState(stone).isAir());
            SimWitherDoor1121.hold(ctx, sp, "SUPERBOOM_TNT");
            SimWitherDoor1121.leftClick(ctx, cracked);
            ctx.waitTicks(20);
            boolean boomBroke = sp.getServer().computeOnServer(s -> s.overworld().getBlockState(cracked).isAir());
            if (!dbBroke) failures.add("control: the Dungeonbreaker did not break a stone brick");
            if (!boomBroke) failures.add("control: Superboom did not break a cracked brick");
            SimWitherDoor1121.row(failures, NAME, failures.isEmpty(), probes + " door probes left every block in place; control: Dungeonbreaker broke stone brick "
                    + dbBroke + ", Superboom broke cracked brick " + boomBroke);
        } finally {
            SimWitherDoor1121.leave();
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(String.join(" | ", failures));
        }
    }
}

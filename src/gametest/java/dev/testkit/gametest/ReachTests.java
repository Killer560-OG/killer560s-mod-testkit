package dev.testkit.gametest;

import dev.testkit.harness.PacketWatch;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * How far an aura can reach before a server stops accepting it, measured one distance at a time.
 *
 * <p>Vanilla's block interaction limit is 4.5 blocks to the nearest point of the block's box. Several of this
 * mod's auras default above it - Secret Aura at 6.2, Lever Aura at 5.0 with a 6.5 maximum, Auto Door Opener at
 * 5.0 - and that is a static per-packet property, so a server can check it on every interaction without
 * modelling anything. killer560's instruction (2026-09-28): "Just use the sim as the test. [...] Just make the
 * max whatever you find the max to be that doesnt flag."
 *
 * <p>So this is a ladder: one lever, one distance, one scenario, and the answer is the largest rung that the
 * server accepts and the anticheat ignores.
 *
 * <h2>Why one lever per scenario</h2>
 * The first version put seven levers in one arena and ran two scenarios against them. The second reported zero
 * interactions, because these auras keep a done-set of secrets they have already clicked and that set is static
 * - it survives the world being rebuilt between scenarios in the same client. Every rung therefore gets its own
 * lever at its own coordinates, so no rung can be skipped because an earlier one poisoned the set.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class ReachTests implements FabricClientGameTest {

    private static final String MOD_ID = "killer560smod";
    private static final String CHEAT_CFG = "com.killer560.hub.cheatutils.CheatUtilsConfig";
    private static final String DUNGEON_LABEL = "localhost.p3sim.net:25565";

    private static final int SURFACE_Y = 151;
    private static final int BASE_Z = -300;

    /** Block offsets along z. The eye-to-box distance works out at offset minus 0.5. */
    private static final int[] RUNGS = {5, 6, 7};

    @Override
    public void runTest(ClientGameTestContext ctx) {
        for (int dz : RUNGS) {
            rung(ctx, dz);
        }
    }

    private void rung(ClientGameTestContext ctx, int dz) {
        // A distinct x per rung, so each has its own lever coordinates and the aura's static done-set cannot
        // make a later rung look like it was never attempted.
        final int baseX = -300 - dz * 40;
        final BlockPos lever = new BlockPos(baseX, SURFACE_Y + 1, BASE_Z + dz);
        String name = String.format(Locale.ROOT, "8%d-reach-at-%d-blocks", dz - 4, dz);
        if (Scenario.skip(name)) {
            return;
        }
        ModUnderTest.require(MOD_ID);
        Scenario.labelServerAs(DUNGEON_LABEL);
        Scenario.runExpectingFlags(ctx, name,
                (server, scenario) -> TestMap.on(server)
                        .platform(baseX, SURFACE_Y - 1, BASE_Z + dz / 2, 14)
                        .walls(4)
                        .catchFloor(120)
                        .survival()
                        .clearInventory()
                        .skyblockSidebar("F7")
                        .command("setblock " + (baseX + 1) + " " + (SURFACE_Y + 1) + " " + (BASE_Z + dz)
                                + " minecraft:stone")
                        .command("setblock " + baseX + " " + (SURFACE_Y + 1) + " " + (BASE_Z + dz)
                                + " minecraft:lever[face=wall,facing=west]")
                        .spawn(baseX + 0.5, BASE_Z + 0.5, 0f)
                        .build(),
                (server, scenario) -> {
                    scenario.assertDetectorWorks();
                    ctx.waitTicks(30);

                    double distance = ctx.computeOnClient(mc -> boxDistance(mc.player.getEyePosition(), lever));
                    scenario.log(String.format(Locale.ROOT,
                            "one lever at %.2f blocks from the eye, measured to its box", distance));

                    ctx.runOnClient(mc -> {
                        Object cfg = ModUnderTest.config(CHEAT_CFG);
                        ModUnderTest.set(cfg, "setSecretAuraEnabled", true);
                        ModUnderTest.set(cfg, "setAuraLevers", true);
                        // Wide open, so the aura's own range never decides the answer - the server does.
                        ModUnderTest.set(cfg, "setAuraRange", 6.5);
                        if (!ModUnderTest.getBoolean(cfg, "isSecretAuraEnabled")) {
                            throw new AssertionError("isSecretAuraEnabled() is false - needs the cheat jar and "
                                    + "a dungeon-looking server");
                        }
                    });

                    PacketWatch.start();
                    ctx.waitTicks(120);
                    PacketWatch.stop();

                    ctx.runOnClient(mc -> ModUnderTest.turnOff(CHEAT_CFG, "setSecretAuraEnabled"));

                    boolean accepted = ctx.computeOnClient(mc ->
                            mc.level.getBlockState(lever).getValue(LeverBlock.POWERED));
                    long flags = scenario.flags().size();
                    scenario.log(String.format(Locale.ROOT,
                            "RESULT at %.2f blocks: %d interaction(s) sent, server %s it, anticheat raised "
                                    + "%d flag(s)",
                            distance, PacketWatch.totalUses(),
                            accepted ? "ACCEPTED" : "REFUSED", flags));
                    // WHAT COUNTS AS RIGHT DEPENDS ON THE RUNG.
                    //
                    // The auras are now capped at the measured limit (4.5 blocks to the block's box), so a rung
                    // beyond that SHOULD send nothing - the cap declining to try is the cap working, and this
                    // scenario is what proves it still does. A rung at or inside the limit must land and must
                    // draw nothing.
                    boolean insideLimit = distance <= 4.5 + 1.0E-6;
                    if (insideLimit) {
                        if (PacketWatch.totalUses() == 0) {
                            throw new AssertionError(String.format(Locale.ROOT,
                                    "at %.2f blocks - inside the limit - the aura sent nothing. Either a gate "
                                            + "is shut, or the range is being measured to the block's CENTRE "
                                            + "again, which reads up to half a block further and silently "
                                            + "shortens the real reach.", distance));
                        }
                        if (!accepted) {
                            throw new AssertionError(String.format(Locale.ROOT,
                                    "at %.2f blocks the server refused a click it should have taken", distance));
                        }
                        if (flags > 0) {
                            throw new AssertionError(String.format(Locale.ROOT,
                                    "at %.2f blocks, inside the limit, the anticheat still objected %d time(s)",
                                    distance, flags));
                        }
                    } else if (PacketWatch.totalUses() > 0) {
                        throw new AssertionError(String.format(Locale.ROOT,
                                "at %.2f blocks - beyond the measured limit - the aura tried anyway (%d "
                                        + "interaction(s)). The range cap is not holding, and every such "
                                        + "attempt is refused by the server and flagged on the way.",
                                distance, PacketWatch.totalUses()));
                    } else {
                        scenario.log(String.format(Locale.ROOT,
                                "  correct: at %.2f blocks the cap declined to try at all", distance));
                    }
                    for (String flag : scenario.flags().stream().distinct().limit(2).toList()) {
                        scenario.log("    " + flag);
                    }
                });
    }

    private static double boxDistance(Vec3 eye, BlockPos pos) {
        double dx = Math.max(0, Math.max(pos.getX() - eye.x, eye.x - (pos.getX() + 1)));
        double dy = Math.max(0, Math.max(pos.getY() - eye.y, eye.y - (pos.getY() + 1)));
        double dz = Math.max(0, Math.max(pos.getZ() - eye.z, eye.z - (pos.getZ() + 1)));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}

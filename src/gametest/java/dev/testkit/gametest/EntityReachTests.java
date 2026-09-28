package dev.testkit.gametest;

import dev.testkit.harness.PacketWatch;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.Locale;

/**
 * Entity interactions, which are the one packet class nothing here had covered yet - and they have a TIGHTER
 * limit than blocks.
 *
 * <p>Vanilla allows 4.5 blocks to a block's box but only 3.0 to an entity's. Three of this mod's features click
 * entities: Terminal Aura on armour stands (range 4.0), Arrow Align on item frames (range 5.0, ceiling 6.0),
 * and Goldor Triggerbot on the wither. Every one of those defaults above 3.0, so the same question that the
 * block reach ladder answered needs asking again against the smaller number.
 *
 * <p><b>What this measures, precisely.</b> Not a feature - the SERVER. Arrow Align cannot be driven without a
 * real 5x5 frame grid at exact coordinates whose rotations match a known puzzle layout, which is more
 * scaffolding than the answer is worth. So this walks the harness itself outward from an armour stand and finds
 * the distance at which the server stops accepting an entity interaction and the anticheat starts objecting.
 *
 * <p>That is the number needed to cap the features: killer560 (2026-09-28) asked for "whatever you find the max
 * to be that doesnt flag", and a limit enforced by the server applies to every feature that clicks an entity
 * whether or not this scenario drove it. It is labelled this way so nobody later reads it as proof that Arrow
 * Align, Terminal Aura or Goldor Triggerbot were themselves exercised - they were not.
 */
public class EntityReachTests implements FabricClientGameTest {

    private static final String MOD_ID = "killer560smod";

    private static final String DUNGEON_LABEL = "localhost.p3sim.net:25565";

    /** The device coordinate Arrow Align anchors to; it only runs within 15 blocks of this. */
    private static final int DEV_X = 0;
    private static final int DEV_Y = 120;
    private static final int DEV_Z = 77;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String name = "85-entity-reach-limit";
        if (Scenario.skip(name)) {
            return;
        }
        Scenario.runExpectingFlags(ctx, name,
                (server, scenario) -> TestMap.on(server)
                        .platform(DEV_X, DEV_Y - 1, DEV_Z, 16)
                        .catchFloor(90)
                        .survival()
                        .clearInventory()
                        .command(String.format(Locale.ROOT,
                                "summon minecraft:armor_stand %d %d %d {NoGravity:1b,Invulnerable:1b}",
                                DEV_X, DEV_Y, DEV_Z + 8))
                        .spawn(DEV_X + 0.5, DEV_Z + 0.5, 0f)
                        .build(),
                (server, scenario) -> {
                    scenario.assertDetectorWorks();
                    ctx.waitTicks(20);

                    // Walk in from too far and interact once per tick. The distance at which the server starts
                    // accepting - and the distance at which the anticheat stops complaining - are the answers.
                    PacketWatch.start();
                    double firstClean = Double.NaN;
                    ctx.getInput().holdKey(options -> options.keyUp);
                    for (int i = 0; i < 60; i++) {
                        ctx.waitTicks(2);
                        double before = PacketWatch.maxEntityReach();
                        int flagsBefore = scenario.flags().size();
                        double d = interactOnce(ctx);
                        if (Double.isNaN(d)) {
                            continue;
                        }
                        ctx.waitTicks(3);
                        boolean clean = scenario.flags().size() == flagsBefore;
                        if (clean && (Double.isNaN(firstClean) || d > firstClean)) {
                            firstClean = d;
                        }
                        scenario.log(String.format(Locale.ROOT, "  interacted at %.2f blocks -> %s", d,
                                clean ? "no flag" : "FLAGGED"));
                        if (d < 1.0) {
                            break;
                        }
                    }
                    ctx.getInput().releaseKey(options -> options.keyUp);
                    PacketWatch.stop();

                    scenario.log(PacketWatch.summary());
                    scenario.log(String.format(Locale.ROOT,
                            "RESULT entity interaction limit: %d interact(s) sent, furthest %.2f blocks; the "
                                    + "greatest distance that drew NO flag was %.2f blocks (vanilla's own "
                                    + "entity limit is 3.0); anticheat raised %d flag(s) in total",
                            PacketWatch.entityInteracts(), PacketWatch.maxEntityReach(), firstClean,
                            scenario.flags().size()));
                    if (PacketWatch.entityInteracts() == 0) {
                        throw new AssertionError("no entity interactions were sent at all, so nothing was "
                                + "measured");
                    }
                    for (String flag : scenario.flags().stream().distinct().limit(3).toList()) {
                        scenario.log("    " + flag);
                    }
                });
    }

    /** One interact against the armour stand, whatever the distance. @return that distance, or NaN if absent. */
    private double interactOnce(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            var stands = mc.level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class,
                    mc.player.getBoundingBox().inflate(32));
            if (stands.isEmpty()) {
                return Double.NaN;
            }
            var stand = stands.get(0);
            var eye = mc.player.getEyePosition();
            var box = stand.getBoundingBox();
            double dx = Math.max(0, Math.max(box.minX - eye.x, eye.x - box.maxX));
            double dy = Math.max(0, Math.max(box.minY - eye.y, eye.y - box.maxY));
            double dz = Math.max(0, Math.max(box.minZ - eye.z, eye.z - box.maxZ));
            mc.gameMode.interact(mc.player, stand,
                    new net.minecraft.world.phys.EntityHitResult(stand, stand.position()),
                    net.minecraft.world.InteractionHand.MAIN_HAND);
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        });
    }
}

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

                    // PLACED at each distance, not walked in.
                    //
                    // Walking sampled about every 1.08 blocks, which is far too coarse to answer the question
                    // the mod actually asks of this probe: is 3.0 safe? It reported a flag at 3.26 and none at
                    // 2.18 and left everything between unmeasured - which is where MEASURED_MAX_ENTITY_REACH
                    // sits. The stand is at a known place on our own server, so the player can simply be put
                    // at each distance and the boundary found at 0.1 resolution.
                    PacketWatch.start();
                    double firstClean = Double.NaN;
                    double lowestFlagged = Double.NaN;
                    for (int step = 40; step >= 15; step--) {
                        double want = step / 10.0;
                        // The stand's box is 0.5 wide, so its face is 0.25 from its centre; place the eye that
                        // much further out to make the eye-to-BOX distance the number we asked for.
                        server.command(String.format(Locale.ROOT, "tp @p %.3f %d %.3f 0 0",
                                DEV_X + 0.5, DEV_Y, DEV_Z + 8 - 0.25 - want));
                        ctx.waitTicks(6);
                        int flagsBefore = scenario.flags().size();
                        double d = interactOnce(ctx);
                        if (Double.isNaN(d)) {
                            continue;
                        }
                        ctx.waitTicks(6);
                        boolean clean = scenario.flags().size() == flagsBefore;
                        if (clean && (Double.isNaN(firstClean) || d > firstClean)) {
                            firstClean = d;
                        }
                        if (!clean && (Double.isNaN(lowestFlagged) || d < lowestFlagged)) {
                            lowestFlagged = d;
                        }
                        scenario.log(String.format(Locale.ROOT, "  interacted at %.2f blocks -> %s", d,
                                clean ? "no flag" : "FLAGGED"));
                    }
                    PacketWatch.stop();
                    scenario.log(String.format(Locale.ROOT,
                            "  BOUNDARY: highest clean %.2f, lowest flagged %.2f -> the limit is between them; "
                                    + "MEASURED_MAX_ENTITY_REACH is 3.0",
                            firstClean, lowestFlagged));

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
            // A point ON the box, from the eye - not the entity's feet.
            //
            // This passed stand.position(), which is a point INSIDE the box that no ray from the eye
            // produces. That is the fault this harness's own notes already blame for the Hitboxes flags it
            // drew, so the probe was flagging itself and reporting the result as a distance finding. With a
            // real clipped hit, a flag here means the DISTANCE was refused, which is what the probe is for.
            var centre = box.getCenter();
            var aim = box.clip(eye, eye.add(centre.subtract(eye).normalize()
                    .scale(eye.distanceTo(centre) + 1.0))).orElse(centre);
            mc.gameMode.interact(mc.player, stand,
                    new net.minecraft.world.phys.EntityHitResult(stand, aim),
                    net.minecraft.world.InteractionHand.MAIN_HAND);
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        });
    }
}

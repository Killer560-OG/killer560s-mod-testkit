package dev.testkit.gametest;

import dev.testkit.harness.PacketWatch;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Locale;

/**
 * Full Block: the mod widens a secret's interaction shape to the whole cube, so a click that would have
 * missed the real lever lands on it.
 *
 * <p>This is the most interesting thing left to test, because unlike the ordering fault it is not about
 * timing at all - it is about sending a hit position the server's own copy of that block does not contain.
 * Whether that is detectable is a question about the server, not about the mod, and it cannot be answered by
 * reading the mod's source. There are only three possible outcomes and all three are worth knowing: the
 * server rejects the interaction outright, it accepts it and the anticheat objects, or it accepts it silently.
 *
 * <p>The scenario does not assume an aim point. It sweeps the pitch until it finds one where the crosshair
 * misses the lever with Full Block off and hits it with Full Block on, which is the only aim that actually
 * tests the feature - anything else measures an ordinary click. If no such aim exists it says so and fails,
 * rather than clicking a lever normally and reporting a clean result.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class FullBlockTests implements FabricClientGameTest {

    private static final String MOD_ID = "killer560smod";
    private static final String SECRETS = "com.killer560.hub.secrets.SecretsConfig";
    private static final String DUNGEON_LABEL = "localhost.p3sim.net:25565";

    private static final int SURFACE_Y = 151;
    // Its own corner of the world. These features keep a static done-set of secrets they have already clicked
    // and it survives the world being rebuilt between scenarios in one client, so sharing coordinates with
    // another scenario means this one silently finds nothing to do - which is exactly what happened when the
    // whole suite ran in one go (the lever was already powered before this scenario started).
    private static final int BASE_X = -700;
    private static final int BASE_Z = -700;
    private static final BlockPos LEVER = new BlockPos(BASE_X, SURFACE_Y + 1, BASE_Z);

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String name = "62-full-block-reach";
        if (Scenario.skip(name)) {
            return;
        }
        ModUnderTest.require(MOD_ID);
        Scenario.labelServerAs(DUNGEON_LABEL);
        Scenario.runExpectingFlags(ctx, name,
                (server, scenario) -> TestMap.on(server)
                        .platform(BASE_X, SURFACE_Y - 1, BASE_Z, 12)
                        .walls(4)
                        .catchFloor(120)
                        .survival()
                        .clearInventory()
                        .skyblockSidebar("F7")
                        .command("fill " + (BASE_X + 1) + " " + SURFACE_Y + " " + (BASE_Z - 1)
                                + " " + (BASE_X + 1) + " " + (SURFACE_Y + 3) + " " + (BASE_Z + 1)
                                + " minecraft:stone")
                        .command("setblock " + BASE_X + " " + (SURFACE_Y + 1) + " " + BASE_Z
                                + " minecraft:lever[face=wall,facing=west]")
                        .spawn(BASE_X - 2.5, BASE_Z + 0.5, -90f)
                        .build(),
                (server, scenario) -> {
                    scenario.assertDetectorWorks();

                    // Switch the triggerbot OFF first. Its config is a live singleton that survives between
                    // scenarios in one client, so it is still enabled from scenario 60 - and it would click this
                    // lever during the pitch sweep below, before any measurement starts. That is exactly what
                    // happened in a full-suite run: the lever was already powered, the done-set then refused the
                    // measured click, and the scenario reported having sent nothing.
                    ctx.runOnClient(mc -> ModUnderTest.set(
                            ModUnderTest.config("com.killer560.hub.secrettrigger.SecretTriggerbotConfig"),
                            "setEnabled", false));

                    setFullBlock(ctx, false);
                    scenario.log("the lever's real interaction shape: " + realShape(ctx));

                    // Find an aim that ONLY the widened shape catches.
                    float aim = Float.NaN;
                    for (float pitch = -35f; pitch <= 35f && Float.isNaN(aim); pitch += 1f) {
                        float p = pitch;
                        setFullBlock(ctx, false);
                        ctx.runOnClient(mc -> mc.player.setXRot(p));
                        ctx.waitTicks(2);
                        boolean offHit = onLever(ctx);
                        setFullBlock(ctx, true);
                        ctx.waitTicks(2);
                        boolean onHit = onLever(ctx);
                        if (!offHit && onHit) {
                            aim = p;
                        }
                    }
                    if (Float.isNaN(aim)) {
                        throw new AssertionError("no pitch was found where the crosshair misses the lever "
                                + "normally and hits it with Full Block on, so any click here would be an "
                                + "ordinary one and this scenario would prove nothing about the feature");
                    }
                    final float found = aim;
                    ctx.runOnClient(mc -> mc.player.setXRot(found));
                    ctx.waitTicks(2);

                    // How far outside the real shape the hit point actually is. This is the number a server
                    // would have to tolerate to accept the click.
                    String outside = ctx.computeOnClient(mc -> {
                        if (!(mc.hitResult instanceof BlockHitResult hit)) {
                            return "no block hit";
                        }
                        setFullBlockOn(false);
                        AABB real = mc.level.getBlockState(LEVER).getShape(mc.level, LEVER)
                                .bounds().move(LEVER);
                        setFullBlockOn(true);
                        var v = hit.getLocation();
                        double dx = Math.max(0, Math.max(real.minX - v.x, v.x - real.maxX));
                        double dy = Math.max(0, Math.max(real.minY - v.y, v.y - real.maxY));
                        double dz = Math.max(0, Math.max(real.minZ - v.z, v.z - real.maxZ));
                        return String.format(Locale.ROOT,
                                "hit %.3f,%.3f,%.3f which is %.3f blocks outside the real shape %s",
                                v.x, v.y, v.z, Math.sqrt(dx * dx + dy * dy + dz * dz), real);
                    });
                    scenario.log("aim found at pitch " + found + ": " + outside);

                    boolean flippedBefore = powered(ctx);

                    // Let the mod's own triggerbot send it, because Full Block plus a triggerbot is the real
                    // combination - the triggerbot reads the same widened hitResult.
                    // COUNTING BEFORE ENABLING, not after. Each runOnClient hands control back to the client
                    // thread, so one or more ticks pass between the switch going on and the next statement -
                    // and the triggerbot fires on the first of them. Enabling first meant the only click of the
                    // run happened before the counter was watching, which read as "sent nothing" while the
                    // mod's own log plainly said it had clicked. Same trap as scenario 60.
                    PacketWatch.start();
                    ctx.runOnClient(mc -> {
                        Object cfg = ModUnderTest.config(
                                "com.killer560.hub.secrettrigger.SecretTriggerbotConfig");
                        ModUnderTest.set(cfg, "setEnabled", true);
                    });
                    ctx.waitTicks(80);
                    PacketWatch.stop();

                    boolean flippedAfter = powered(ctx);
                    long post = scenario.flags().stream()
                            .filter(l -> l.contains("failed Post")).count();
                    long other = scenario.flags().size() - post;

                    scenario.log(PacketWatch.summary());
                    scenario.log(String.format(Locale.ROOT,
                            "RESULT Full Block: %d interaction(s) sent at a point outside the real shape; "
                                    + "lever powered %s -> %s; anticheat said %d Post, %d other",
                            PacketWatch.totalUses(), flippedBefore, flippedAfter, post, other));

                    if (PacketWatch.totalUses() == 0) {
                        throw new AssertionError("nothing was sent, so this says nothing about Full Block."
                                + (flippedBefore
                                ? " The lever was ALREADY powered before this scenario began, which means an"
                                + " earlier scenario in this same client run clicked it and the triggerbot's"
                                + " static done-set is still carrying it. Run this one on its own"
                                + " (-Pscenario=62-full-block) - it passes in isolation."
                                : " Check the triggerbot's own state line in the client log."));
                    }
                    if (flippedAfter != flippedBefore) {
                        scenario.log("FINDING: the server ACCEPTED an interaction aimed at a point its own "
                                + "copy of the block does not contain - the lever changed state. Whether an "
                                + "anticheat objects is a separate question; this one "
                                + (other + post > 0 ? "did." : "did not."));
                    } else {
                        scenario.log("The server REJECTED it: the click went out and the lever did not move. "
                                + "Full Block makes the client aim at something the server will not accept, "
                                + "so it does not work on a vanilla server - worth knowing that Hypixel's own "
                                + "acceptance is what the feature actually depends on.");
                    }
                    for (String flag : scenario.flags().stream().distinct().limit(3).toList()) {
                        scenario.log("    " + flag);
                    }
                });
    }

    /** Set on the client thread; used from inside a computeOnClient where we are already on it. */
    private static void setFullBlockOn(boolean on) {
        Object cfg = ModUnderTest.config(SECRETS);
        ModUnderTest.set(cfg, "setMasterEnabled", on);
        ModUnderTest.set(cfg, "setLeversEnabled", on);
    }

    private void setFullBlock(ClientGameTestContext ctx, boolean on) {
        ctx.runOnClient(mc -> setFullBlockOn(on));
    }

    private boolean onLever(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> mc.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(LEVER));
    }

    private boolean powered(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc ->
                mc.level.getBlockState(LEVER).getValue(LeverBlock.POWERED));
    }

    private String realShape(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc ->
                mc.level.getBlockState(LEVER).getShape(mc.level, LEVER).bounds().move(LEVER).toString());
    }
}

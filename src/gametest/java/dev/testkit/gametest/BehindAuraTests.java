package dev.testkit.gametest;

import dev.testkit.harness.PacketWatch;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.LeverBlock;

import java.util.List;
import java.util.Locale;

/**
 * 419/420: Secret Aura clicking a lever BEHIND the player (killer560s-mod, cheat build). The aura picks the nearest
 * secret in range in any direction; until mod aura-turn it sent the click with whatever rotation the player had. This
 * records what GrimAC says about a block interact whose reported look points the other way, and whether the body is
 * turned for it and given back.
 * <ul>
 *   <li>419: the lever 2.5 blocks directly behind him (yaw 180, lever to the +z). Recorded with
 *       {@code runExpectingFlags} so a flag is a result, not a crash; it still fails when nothing was clicked, and it
 *       reports the player's rotation and camera before and after.</li>
 *   <li>420: the same lever in FRONT of him (yaw 0) - the control: the same click with a look that hits it.</li>
 * </ul>
 * Arena and label as {@link ReachTests} (a dungeon-looking server, the F7 sidebar, far into negative x/z so it is not the
 * boss room); each case has its own lever coordinates because the aura's done-set is static.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class BehindAuraTests implements FabricClientGameTest {

    private static final String MOD_ID = "killer560smod";
    private static final String CHEAT_CFG = "com.killer560.hub.cheatutils.CheatUtilsConfig";
    private static final String DUNGEON_LABEL = "localhost.p3sim.net:25565";
    private static final int SURFACE_Y = 151;
    private static final int BASE_Z = -300;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        secretAuraLever(ctx, "419-behind-secret-aura-lever", -520, true);
        secretAuraLever(ctx, "420-front-secret-aura-lever", -560, false);
    }

    private static boolean cheat() {
        try {
            return Class.forName("com.killer560.hub.BuildVariant").getField("CHEAT_FEATURES_ENABLED").getBoolean(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no BuildVariant in the mod under test", e);
        }
    }

    private void secretAuraLever(ClientGameTestContext ctx, String name, int baseX, boolean behind) {
        if (Scenario.skip(name)) {
            return;
        }
        ModUnderTest.require(MOD_ID);
        BlockPos lever = new BlockPos(baseX, SURFACE_Y + 1, BASE_Z + 3);
        float yaw = behind ? 180f : 0f; // yaw 0 faces +z (the lever), 180 has it directly behind
        Scenario.labelServerAs(DUNGEON_LABEL);
        Scenario.runExpectingFlags(ctx, name,
                (server, scenario) -> TestMap.on(server)
                        .platform(baseX, SURFACE_Y - 1, BASE_Z, 8)
                        .walls(4)
                        .catchFloor(120)
                        .survival()
                        .clearInventory()
                        .skyblockSidebar("F7")
                        .command("setblock " + baseX + " " + (SURFACE_Y + 1) + " " + (BASE_Z + 4) + " minecraft:stone")
                        .command("setblock " + baseX + " " + (SURFACE_Y + 1) + " " + (BASE_Z + 3)
                                + " minecraft:lever[face=wall,facing=north]")
                        .spawn(baseX + 0.5, BASE_Z + 0.5, yaw)
                        .build(),
                (server, scenario) -> {
                    scenario.assertDetectorWorks();
                    ctx.waitTicks(30);
                    float[] start = ctx.computeOnClient(mc -> new float[]{mc.player.getYRot(), mc.player.getXRot(),
                            mc.player.getViewYRot(1f), mc.player.getViewXRot(1f)});
                    scenario.log(String.format(Locale.ROOT, "start: body yaw %.2f pitch %.2f, camera %.2f / %.2f",
                            start[0], start[1], start[2], start[3]));
                    float[] worstCamera = {0f};
                    PacketWatch.start();
                    ctx.runOnClient(mc -> {
                        Object cfg = ModUnderTest.config(CHEAT_CFG);
                        ModUnderTest.set(cfg, "setAuraLevers", true);
                        ModUnderTest.set(cfg, "setAuraRange", 4.5);
                        ModUnderTest.set(cfg, "setSecretAuraEnabled", true);
                        if (!cheat()) {
                            return; // a legit jar: Secret Aura is compiled out - judged below by nothing going out
                        }
                        if (!ModUnderTest.getBoolean(cfg, "isSecretAuraEnabled")) {
                            throw new AssertionError("isSecretAuraEnabled() is false - needs the cheat jar and a "
                                    + "dungeon-looking server");
                        }
                    });
                    for (int i = 0; i < 60; i++) {
                        ctx.waitTick();
                        float dev = ctx.computeOnClient(mc -> Math.max(
                                Math.abs(Mth.wrapDegrees(mc.player.getViewYRot(1f) - start[2])),
                                Math.abs(mc.player.getViewXRot(1f) - start[3])));
                        worstCamera[0] = Math.max(worstCamera[0], dev);
                    }
                    PacketWatch.stop();
                    ctx.runOnClient(mc -> ModUnderTest.turnOff(CHEAT_CFG, "setSecretAuraEnabled"));
                    boolean flipped = ctx.computeOnClient(mc -> mc.level.getBlockState(lever).getValue(LeverBlock.POWERED));
                    float[] end = ctx.computeOnClient(mc -> new float[]{mc.player.getYRot(), mc.player.getXRot()});
                    List<String> flags = scenario.flags();
                    scenario.log(PacketWatch.summary());
                    scenario.log(String.format(Locale.ROOT, "RESULT %s: %d use(s) sent, lever %s, camera moved at most "
                                    + "%.3f deg, body after yaw %.2f pitch %.2f (was %.2f, %.2f), GrimAC %d line(s)",
                            behind ? "behind" : "front", PacketWatch.totalUses(), flipped ? "FLIPPED" : "not flipped",
                            worstCamera[0], Mth.wrapDegrees(end[0]), end[1], Mth.wrapDegrees(start[0]), start[1], flags.size()));
                    for (String f : flags.stream().distinct().limit(6).toList()) {
                        scenario.log("    " + f);
                    }
                    if (!cheat()) {
                        if (PacketWatch.totalUses() != 0 || flipped) {
                            throw new AssertionError("a legit jar clicked the lever by itself");
                        }
                        scenario.log("legit jar: Secret Aura is compiled out and nothing was clicked, as it should be");
                        return;
                    }
                    if (PacketWatch.totalUses() == 0) {
                        throw new AssertionError("Secret Aura sent nothing - the result would mean nothing");
                    }
                    if (worstCamera[0] > 0.01f) {
                        throw new AssertionError("the camera moved " + worstCamera[0] + " degrees");
                    }
                    if (Math.abs(Mth.wrapDegrees(end[0] - start[0])) > 0.01f || Math.abs(end[1] - start[1]) > 0.01f) {
                        throw new AssertionError("the body was not given back: yaw " + end[0] + " pitch " + end[1]);
                    }
                });
    }
}

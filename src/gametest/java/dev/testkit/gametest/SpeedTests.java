package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.Locale;

/**
 * Proves the sim can run at killer560's real Hypixel speed, and that the number actually arrived.
 *
 * <p>killer560 (2026-09-28): "find a way to get custom speed so you can test on things like 550 speed or 600 speed
 * equivalent to hypixels". Everything AP3's align planner does is priced off the movement-speed attribute, so a
 * harness pinned at a normal walk is not testing the planner he runs - and a benchmark capped at 450 speed reported
 * a tick distribution that did not match his logs at all.
 *
 * <p>It asserts rather than prints, because a {@code /attribute} command that silently does nothing would otherwise
 * leave every later scenario quietly running at walking pace while claiming to be at 600.
 */
public class SpeedTests implements FabricClientGameTest {

    private static final String DUNGEON_LABEL = "localhost.p3sim.net:25565";
    private static final int SURFACE_Y = 151;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        rung(ctx, 100);
        rung(ctx, 550);
        rung(ctx, 600);
    }

    private void rung(ClientGameTestContext ctx, int speed) {
        final int baseX = -900 - speed;
        String name = String.format(Locale.ROOT, "9%d-speed-%d", speed / 100, speed);
        if (Scenario.skip(name)) {
            return;
        }
        Scenario.labelServerAs(DUNGEON_LABEL);
        Scenario.runExpectingFlags(ctx, name,
                (server, scenario) -> TestMap.on(server)
                        .platform(baseX, SURFACE_Y - 1, 0, 20)
                        .walls(4)
                        .catchFloor(120)
                        .survival()
                        .clearInventory()
                        .skyblockSidebar("F7")
                        .speed(speed)
                        .spawn(baseX + 0.5, 0.5, 0f)
                        .build(),
                (server, scenario) -> {
                    ctx.waitTicks(20);
                    double attr = ctx.computeOnClient(mc ->
                            mc.player.getAttributeValue(Attributes.MOVEMENT_SPEED));
                    double expected = speed / 1000.0;
                    scenario.log(String.format(Locale.ROOT,
                            "%d speed -> movement_speed attribute reads %.4f (wanted %.4f)", speed, attr, expected));
                    // The attribute carries the sprint modifier when sprinting; spawned standing still it should not.
                    if (Math.abs(attr - expected) > 1.0E-6) {
                        throw new AssertionError(String.format(Locale.ROOT,
                                "asked for %d speed (%.4f) but the attribute reads %.4f - the /attribute command "
                                        + "did not take, so every scenario using .speed() is really running at "
                                        + "whatever the default is", speed, expected, attr));
                    }
                });
    }
}

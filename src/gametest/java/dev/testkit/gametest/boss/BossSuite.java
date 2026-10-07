package dev.testkit.gametest.boss;

import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.TestMap;
import dev.testkit.gametest.hx.Session;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * WP7 entrypoint: boss and P3 arena. Scenario names start `5NN-boss-`; `-Psuite=boss` selects them.
 *
 * <p>So far: the P3 Simon Says device, old (5 rounds) and new (4 rounds, Hypixel's 2026-10-06 update) - see
 * {@link SimonSaysCases}. The device is server side ({@code hx/boss/HxSimonSays}) at the real coordinates
 * (x 110-111, y 120-123, z 91-95); the session's spawn platform is elsewhere and each case teleports to it.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class BossSuite implements FabricClientGameTest {

    public static final String SESSION = "500-boss-session";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        Session.run(ctx, SESSION,
                (server, s) -> TestMap.on(server)
                        .platform(-620, 150, -620, 6)
                        .catchFloor(140)
                        .survival()
                        .clearInventory()
                        .spawn(-619.5, -619.5, 0f)
                        .build(),
                s -> {
                    ModUnderTest.require("killer560smod");
                    SimonSaysCases.register(s);
                    TermLogCases.register(s);
                });
    }
}

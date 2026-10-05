package dev.testkit.gametest.hx;

import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.TestMap;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * WP2: Hx fidelity, chat and HUD (port 25570). One shared server ({@code 100-hx-session}) and {@code 1NN-hx-*} cases,
 * each driving the mod through what Hypixel would send (system chat, overlay chat, sidebar, tab list, items, a map
 * packet, command responders) and asserting the mod's own state or a command the server received.
 *
 * <p>Case groups: {@link HxDungeonCases} (101-119), {@link HxSocialCases} (120-134), {@link HxHudCases} (135-140),
 * {@link HxHostileCases} (150, last: it deliberately pollutes party state). Every package gets at least one positive
 * case and one forged/hostile case. Fixtures: {@code testkit-fixtures/{chat,sidebar,tab,hostile}/}.
 *
 * <p>The player stands on a platform at (-420, 150, -420): negative x/z keep ScoreCalculator's and LiveMap's
 * coordinate boss checks (F7 boss = x > -7 and z > -7) false, so "in a dungeon" means the clear.
 */
public class HxSuite implements FabricClientGameTest {

    public static final String SESSION = "100-hx-session";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        Session.run(ctx, SESSION,
                (server, s) -> TestMap.on(server)
                        .platform(-420, 150, -420, 6)
                        .catchFloor(140)
                        .survival()
                        .clearInventory()
                        .spawn(-419.5, -419.5, 0f)
                        .build(),
                s -> {
                    ModUnderTest.require("killer560smod");
                    HxDungeonCases.register(s);
                    HxSocialCases.register(s);
                    HxHudCases.register(s);
                    HxHostileCases.register(s);
                });
    }
}

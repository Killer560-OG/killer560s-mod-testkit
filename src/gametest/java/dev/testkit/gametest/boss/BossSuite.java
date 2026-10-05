package dev.testkit.gametest.boss;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * WP7 entrypoint: boss and P3 arena (port 25620). Scenario names start `5NN-boss-`; `-Psuite=boss` selects them.
 *
 * <p>A STUB registered by WP1 in {@code src/gametest/resources/fabric.mod.json}, so WP7 never edits that frozen
 * file: fill in {@link #runTest} (with {@code hx.Session.run} for server cases, or directly for client-only ones).
 * WP7 owns {@code src/gametest/java/dev/testkit/gametest/boss/**}. Until then it runs nothing and starts nothing.
 */
public class BossSuite implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        // Stub: no cases yet. Deliberately does not call Scenario.skip/Session.run, so it never counts as started.
    }
}

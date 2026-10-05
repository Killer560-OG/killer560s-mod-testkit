package dev.testkit.gametest.dsim;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * WP6 entrypoint: the full sim on the dedicated server (port 25610). Scenario names start `4NN-dsim-`; `-Psuite=dsim` selects them.
 *
 * <p>A STUB registered by WP1 in {@code src/gametest/resources/fabric.mod.json}, so WP6 never edits that frozen
 * file: fill in {@link #runTest} (with {@code hx.Session.run} for server cases, or directly for client-only ones).
 * WP6 owns {@code src/gametest/java/dev/testkit/gametest/dsim/**}. Until then it runs nothing and starts nothing.
 */
public class DungeonServerSuite implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        // Stub: no cases yet. Deliberately does not call Scenario.skip/Session.run, so it never counts as started.
    }
}

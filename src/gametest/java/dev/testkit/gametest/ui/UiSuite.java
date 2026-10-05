package dev.testkit.gametest.ui;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * WP4 entrypoint: client UI, commands and config (singleplayer, no server). Scenario names start `3NN-ui-`; `-Psuite=ui` selects them.
 *
 * <p>A STUB registered by WP1 in {@code src/gametest/resources/fabric.mod.json}, so WP4 never edits that frozen
 * file: fill in {@link #runTest} (with {@code hx.Session.run} for server cases, or directly for client-only ones).
 * WP4 owns {@code src/gametest/java/dev/testkit/gametest/ui/**}. Until then it runs nothing and starts nothing.
 */
public class UiSuite implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        // Stub: no cases yet. Deliberately does not call Scenario.skip/Session.run, so it never counts as started.
    }
}

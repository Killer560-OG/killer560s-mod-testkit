package dev.testkit.gametest.hx;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * WP2 entrypoint: Hx fidelity, chat and HUD (port 25570). Scenario names start `1NN-hx-`; `-Psuite=hx` selects them.
 *
 * <p>A STUB registered by WP1 in {@code src/gametest/resources/fabric.mod.json}, so WP2 never edits that frozen
 * file: fill in {@link #runTest} (with {@code hx.Session.run} for server cases, or directly for client-only ones).
 * WP2 owns {@code src/gametest/java/dev/testkit/gametest/hx/**}. Until then it runs nothing and starts nothing.
 */
public class HxSuite implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        // Stub: no cases yet. Deliberately does not call Scenario.skip/Session.run, so it never counts as started.
    }
}

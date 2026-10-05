package dev.testkit.gametest.logic;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * WP5 entrypoint: pure logic, fixture integrity and coverage tooling (no server). Scenario names start `35N-logic-`; `-Psuite=logic` selects them.
 *
 * <p>A STUB registered by WP1 in {@code src/gametest/resources/fabric.mod.json}, so WP5 never edits that frozen
 * file: fill in {@link #runTest} (with {@code hx.Session.run} for server cases, or directly for client-only ones).
 * WP5 owns {@code src/gametest/java/dev/testkit/gametest/logic/**}. Until then it runs nothing and starts nothing.
 */
public class LogicSuite implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        // Stub: no cases yet. Deliberately does not call Scenario.skip/Session.run, so it never counts as started.
    }
}

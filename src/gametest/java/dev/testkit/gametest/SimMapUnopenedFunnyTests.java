package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** 1018-map-unopened-funny: Funny Map's unopened rooms are its greyed brown, never dark grey. See {@link SimMapUnopenedTests}. */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimMapUnopenedFunnyTests implements FabricClientGameTest {

    static final String CASE = "1018-map-unopened-funny";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        SimMapUnopenedTests.run(ctx, CASE, true);
    }
}

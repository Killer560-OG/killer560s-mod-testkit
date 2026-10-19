package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** 1018-map-unopened-blood: on the regular map the unopened Blood Room is Funny Map's greyed brown once its blood door is
 *  known, and an ordinary unopened room without it. See {@link SimMapUnopenedTests}. */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimMapUnopenedBloodTests implements FabricClientGameTest {

    static final String CASE = "1018-map-unopened-blood";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        SimMapUnopenedTests.run(ctx, CASE, false);
    }
}

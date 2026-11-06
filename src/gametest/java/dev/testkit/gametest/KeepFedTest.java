package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * 1105-keepfed (killer560, 2026-11-05: "some of the tests are running out of hunger. make sure their hunger never drops on
 * the test sim"): the test server keeps a real player's food full. In survival with Hunger 255 the bar stays at 20; with
 * {@code /testkit keepfed false} the same drain empties it, which proves the effect drained and the refill is what held it.
 */
public class KeepFedTest implements FabricClientGameTest {

    static final String NAME = "1105-keepfed";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        Scenario.run(ctx, NAME, (server, scenario) -> TestMap.on(server).survival().build(), (server, scenario) -> {
            String name = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
            int fedMin = 20;
            int unfedMin;
            try {
                server.command("effect give " + name + " minecraft:hunger 10 255 true");
                for (int i = 0; i < 8; i++) {
                    ctx.waitTicks(10);
                    fedMin = Math.min(fedMin, ctx.computeOnClient(mc -> mc.player.getFoodData().getFoodLevel()));
                }
                server.command("testkit keepfed false");
                unfedMin = 20;
                for (int i = 0; i < 8; i++) {
                    ctx.waitTicks(10);
                    unfedMin = Math.min(unfedMin, ctx.computeOnClient(mc -> mc.player.getFoodData().getFoodLevel()));
                }
            } finally {
                server.command("testkit keepfed true");
                server.command("effect clear " + name);
            }
            ctx.waitTicks(10);
            int after = ctx.computeOnClient(mc -> mc.player.getFoodData().getFoodLevel());
            scenario.log("food: lowest " + fedMin + " with keepfed on, " + unfedMin + " with it off, " + after + " after it is back on");
            if (unfedMin >= 18) {
                throw new AssertionError("the Hunger effect never drained food with keepfed off (lowest " + unfedMin + "): vacuous");
            }
            if (fedMin < 20 || after < 20) {
                throw new AssertionError("food dropped with keepfed on: lowest " + fedMin + ", after " + after);
            }
        });
    }
}

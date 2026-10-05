package dev.testkit.gametest.logic;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import dev.testkit.gametest.Scenario;

/**
 * WP5: pure logic, fixture integrity and pattern coverage, at the title screen (354/357 in a throwaway singleplayer world: ItemStacks need bound item components) - no server, no anticheat.
 * Scenario names start {@code 35N-logic-}; {@code -Psuite=logic} selects them. Whole suite budget: 60 s.
 *
 * <pre>
 * 350-logic-catalog            tools/extract-patterns.py's catalog (mod SOURCE) agrees with the loaded JAR
 * 351-logic-fixture-integrity  every fixture: schema, unique id, source file:line real and related, patterns agree
 * 352-logic-pattern-coverage   % of static patterns a fixture cites; every action-gating pattern has a positive one
 * 353-logic-hostile-anchors    forged player lines vs action-gating patterns and their handlers; backtracking bound
 * 354-logic-terminals          TerminalSolverFeature.solve/pickAutoClickTarget on boards, Rubix property sweep
 * 355-logic-experiments-puzzles ExperimentSolver, tic tac toe optimality, Three Weirdos lists, Ice Fill gaps
 * 356-logic-score-blessings    ScoreCalculator tables/worked runs/S+ sufficiency, roman numeral parsers
 * 357-logic-items-ap3-routes   ItemIdentity + every skyblockId reader, AP3 push sizes and store precision, routes JSON
 * 358-logic-mapcode-floorlayout MapCode round trip/fuzz, SimFloorLayout helpers and seeded determinism
 * 359-logic-bazaar-party       Bazaar flip sizing/book direction/ranking, party-command authorisation
 * </pre>
 *
 * The catalog comes from {@code python tools/extract-patterns.py -PmodSource=<mod checkout>}; the mod source is read
 * from {@code -Dtestkit.modSource}, {@code $TESTKIT_MOD_SOURCE} or {@code C:/Users/Hunter/killer560s-mod}.
 */
public class LogicSuite implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        long t0 = System.nanoTime();
        LogicCase.run(ctx, "350-logic-catalog", FixtureCases::catalog);
        LogicCase.run(ctx, "351-logic-fixture-integrity", FixtureCases::integrity);
        LogicCase.run(ctx, "352-logic-pattern-coverage", FixtureCases::coverage);
        LogicCase.run(ctx, "353-logic-hostile-anchors", HostileCases::sweep);
        LogicCase.run(ctx, "355-logic-experiments-puzzles", SolverCases::experimentsAndPuzzles);
        LogicCase.run(ctx, "356-logic-score-blessings", DomainCases::scoreAndBlessings);
        LogicCase.run(ctx, "358-logic-mapcode-floorlayout", DomainCases::mapCodeAndLayout);
        LogicCase.run(ctx, "359-logic-bazaar-party", DomainCases::bazaarAndParty);
        // ItemStacks need item components, which are bound only once a world's registries load ("Components not
        // bound yet" at the title screen). The two cases that build stacks run in a throwaway singleplayer world.
        if (!Scenario.skip("354-logic-terminals") || !Scenario.skip("357-logic-items-ap3-routes")) {
            try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
                LogicCase.run(ctx, "354-logic-terminals", SolverCases::terminals);
                LogicCase.run(ctx, "357-logic-items-ap3-routes", DomainCases::itemsAp3Routes);
            }
        }
        double s = (System.nanoTime() - t0) / 1e9;
        System.out.println(String.format(java.util.Locale.ROOT, "[logic] suite took %.1f s (budget 60 s)", s));
        if (s > 60) {
            System.out.println("[logic] OVER BUDGET");
        }
    }
}

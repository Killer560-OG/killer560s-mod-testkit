package dev.testkit.gametest.logic;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import dev.testkit.gametest.Scenario;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

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
 * 359-logic-bazaar-party       Bazaar flip sizing/book direction/ranking, party-command authorization
 * 2775-logic-bazaarflip        cut Instant Buy titles, Bought/You sold/NPC-limit lines, Buy up to, the amount sign,
 *                              typed Target Purse, the scan's NPC-limit cap (BazaarFlipLogicCases)
 * 2797-logic-flip-1101         Bazaar stack sizes and order limits, the max-orders line, AH fees and flips, the AH
 *                              chat lines (Flip1101LogicCases)
 * 362-logic-boss-timers        0.27.2 pacing (NoammAddons 1.2.9): Tick Timers, Blood Camp kill, Auto i4 prediction re-roll
 * 402-logic-autokick-units     Auto Kick populate: fastest_time_s is milliseconds (real AntsRNG profile, 263003 -> 264 s)
 * 4101..4103-logic-banreport-* Auto Report Bans: a fake Hypixel ban screen builds one scrubbed report; off builds
 *                              nothing; other kicks, chat and other servers build nothing (BanReportCases)
 * 363-logic-roof-cover         Interactive Map landings outside the sim: an open roof refused, under a ceiling accepted
 * 776..786-logic-autoupdate-*  Auto Update against a fake GitHub (asset choice, checksum, same hash, gates, installer):
 *                              AutoUpdateCases; they run on the TEST thread (the installer cases wait seconds)
 * 991..993-logic-reboot-*     /reboot's launcher detection and its relauncher process (RebootCases), TEST thread
 * 2776-logic-mana-world-reset the etherwarp mana hold (Stat Bars' action-bar reading) clears on every world change
 *                              (ManaResetCases), TEST thread: it opens and closes a singleplayer world
 * </pre>
 *
 * The catalog comes from {@code python tools/extract-patterns.py -PmodSource=<mod checkout>}; the mod source is read
 * from modSource in testkit.properties ({@code -Dtestkit.modSource}, {@code $TESTKIT_MOD_SOURCE}); without one the source checks SKIP.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
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
        LogicCase.run(ctx, "2775-logic-bazaarflip", BazaarFlipLogicCases::run);
        LogicCase.run(ctx, "2797-logic-flip-1101", Flip1101LogicCases::run);
        LogicCase.run(ctx, "402-logic-autokick-units", AutoKickCases::units);
        LogicCase.run(ctx, "1104-logic-scanaudit", ScanAuditCases::scanAudit);
        // ---- Auto Report Bans (mod doc-ban-1101) - own block
        LogicCase.run(ctx, "4101-logic-banreport-fake-ban", BanReportCases::fakeBan);
        LogicCase.run(ctx, "4102-logic-banreport-off", BanReportCases::off);
        LogicCase.run(ctx, "4103-logic-banreport-nothing-else", BanReportCases::nothingElse);
        // ---- end Auto Report Bans
        // ---- Auto Crash Reports (mod crashrep-1104) - own block
        LogicCase.run(ctx, "1104-logic-crashreport-live", CrashReportCases::live);
        LogicCase.run(ctx, "1104-logic-crashreport-boot", CrashReportCases::boot);
        LogicCase.run(ctx, "1104-logic-crashreport-off", CrashReportCases::off);
        LogicCase.run(ctx, "1105-discordlink-payload", CrashReportCases::discordPayload);
        // Discord Link (mod discordlink-1105): off the client thread, the cases wait seconds on the stub relay.
        DiscordLinkCases.ctx = ctx;
        LogicCase.runOffClient(ctx, "1105-discordlink-card", DiscordLinkCases::card);
        LogicCase.runOffClient(ctx, "1105-discordlink-offline", DiscordLinkCases::offline);
        // ---- end Auto Crash Reports
        // ---- Auto Update (nt-auto-update, 2026-10-11) - own block
        AutoUpdateCases.ctx = ctx;
        LogicCase.runOffClient(ctx, "776-logic-autoupdate-asset", AutoUpdateCases::asset);
        LogicCase.runOffClient(ctx, "777-logic-autoupdate-channel", AutoUpdateCases::channel);
        LogicCase.runOffClient(ctx, "778-logic-autoupdate-checksum", AutoUpdateCases::checksum);
        LogicCase.runOffClient(ctx, "779-logic-autoupdate-same-hash", AutoUpdateCases::sameHash);
        LogicCase.runOffClient(ctx, "780-logic-autoupdate-staged", AutoUpdateCases::staged);
        LogicCase.runOffClient(ctx, "781-logic-autoupdate-off", AutoUpdateCases::off);
        LogicCase.runOffClient(ctx, "782-logic-autoupdate-gates", AutoUpdateCases::gates);
        LogicCase.runOffClient(ctx, "783-logic-autoupdate-source", AutoUpdateCases::source);
        LogicCase.runOffClient(ctx, "784-logic-autoupdate-helper", AutoUpdateCases::helper);
        LogicCase.runOffClient(ctx, "785-logic-autoupdate-helper-refuse", AutoUpdateCases::helperRefuse);
        LogicCase.runOffClient(ctx, "786-logic-autoupdate-stall", AutoUpdateCases::stall);
        // ---- end Auto Update
        // ---- /reboot (reboot-cmd, 2026-10-13) - own block
        LogicCase.runOffClient(ctx, "991-logic-reboot-detect", RebootCases::detect);
        LogicCase.runOffClient(ctx, "992-logic-reboot-helper", RebootCases::helper);
        LogicCase.runOffClient(ctx, "993-logic-reboot-helper-timeout", RebootCases::timeout);
        // ---- end /reboot
        // ---- mana hold reset on world change (mod sweep-fix-1021) - opens and closes its own world, TEST thread
        ManaResetCases.ctx = ctx;
        LogicCase.runOffClient(ctx, "2776-logic-mana-world-reset", ManaResetCases::worldReset);
        // ---- static scan fixes (mod scanbugs-1104): \s escapes, Revert Master Stars null name
        ScanBugsCases.ctx = ctx;
        LogicCase.runOffClient(ctx, "1104-logic-scanbugs", ScanBugsCases::scan);
        // ---- theme assets on demand (mod themedl-1104): a local release, TEST thread
        ThemeAssetsCases.ctx = ctx;
        LogicCase.runOffClient(ctx, "1104-logic-themedl-offline", ThemeAssetsCases::offline);
        LogicCase.runOffClient(ctx, "1104-logic-themedl-download", ThemeAssetsCases::download);
        LogicCase.runOffClient(ctx, "1104-logic-themedl-badsha", ThemeAssetsCases::badSha);
        LogicCase.runOffClient(ctx, "1104-logic-themedl-switch", ThemeAssetsCases::themeSwitch);
        LogicCase.runOffClient(ctx, "1104-logic-themedl-oldconfig", ThemeAssetsCases::oldConfig);
        LogicCase.runOffClient(ctx, "1104-logic-themedl-notheme", ThemeAssetsCases::noTheme);
        LogicCase.runOffClient(ctx, "1104-logic-themedl-delete", ThemeAssetsCases::delete);
        LogicCase.run(ctx, "1104-logic-leapcore", LeapCore1104Cases::leapCore);
        LogicCase.run(ctx, "1104-logic-leapcore-suppress", LeapCore1104Cases::suppress);
        LogicCase.run(ctx, "1105-logic-mapinfer-pit", MapInfer1105Cases::pit);
        LogicCase.run(ctx, "1105-logic-mapinfer-ambiguous", MapInfer1105Cases::ambiguous);
        LogicCase.run(ctx, "1105-logic-mapinfer-override", MapInfer1105Cases::override);
        LogicCase.run(ctx, "1105-logic-mapinfer-draw", MapInfer1105Cases::draw);
        LogicCase.run(ctx, "1105-logic-mapinfer-live", MapInfer1105Cases::live);
        LogicCase.run(ctx, "1105-logic-mapq-infer-noquestion", MapInfer1105Cases::noQuestion);
        LogicCase.run(ctx, "1105-logic-mapq-predict-unsure", MapInfer1105Cases::predictUnsure);
        LogicCase.run(ctx, "1106-maplabel-recenter", MapLabel1106Cases::recenter);
        // ItemStacks need item components, which are bound only once a world's registries load ("Components not
        // bound yet" at the title screen). The two cases that build stacks run in a throwaway singleplayer world.
        if (!Scenario.skip("354-logic-terminals") || !Scenario.skip("357-logic-items-ap3-routes")
                || !Scenario.skip("362-logic-boss-timers") || !Scenario.skip("363-logic-roof-cover")
                || !Scenario.skip("1102-logic-goldor-tick") || !Scenario.skip("1102-logic-storm-line")
                || !Scenario.skip("1102-logic-f7hud-config") || !Scenario.skip("1102-logic-f7hud-slots")
                || !Scenario.skip("1103-logic-timer-lag")
                || !Scenario.skip("1104-logic-goldor-trusted") || !Scenario.skip("1104-logic-split-lag")
                || !Scenario.skip("1104-logic-stall-burst") || !Scenario.skip("1104-logic-serverlag-grim")
                || !Scenario.skip("1104-logic-hypixel-pings") || !Scenario.skip("1104-logic-hypixel-goldor-drift")
                || !Scenario.skip("1105-logic-hypixel-servertime") || !Scenario.skip("1105-logic-ticklog")
                || !Scenario.skip("1105-logic-ticklog-off")
                || !Scenario.skip("1105-logic-split-jitter") || !Scenario.skip("1105-logic-split-slow")
                || !Scenario.skip("1105-logic-bcstall-jitter") || !Scenario.skip("1105-logic-bcstall-stall")) {
            try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
                LogicCase.run(ctx, "354-logic-terminals", SolverCases::terminals);
                LogicCase.run(ctx, "357-logic-items-ap3-routes", DomainCases::itemsAp3Routes);
                if (!Scenario.skip("362-logic-boss-timers")) {
                    // Auto i4's wall is at x 64-68, z 50, within render distance of the spawn at 0,0, but its chunk can
                    // arrive after the world opens (two runs read void_air there). Wait for it on the client.
                    try {
                        // void_air is what an unloaded chunk reads as; hasChunkAt said yes while it still did.
                        ctx.waitFor(mc -> mc.level != null
                                && !mc.level.getBlockState(new BlockPos(66, 128, 50)).is(Blocks.VOID_AIR), 400);
                        ctx.runOnClient(mc -> System.out.println("[362-logic-boss-timers] player at "
                                + mc.player.blockPosition() + ", wall reads " + mc.level.getBlockState(new BlockPos(66, 128, 50))));
                    } catch (Throwable t) {
                        System.out.println("[362-logic-boss-timers] i4 wall chunk not loaded: " + t);
                    }
                }
                LogicCase.run(ctx, "362-logic-boss-timers", BossTimerCases::bossTimers);
                LogicCase.run(ctx, "1101-logic-bloodcamp-clock", BossTimerCases::bloodCampClock);
                LogicCase.run(ctx, "1102-logic-goldor-tick", BossTimerCases::goldorTick);
                LogicCase.run(ctx, "1102-logic-storm-line", BossTimerCases::stormLineCase);
                LogicCase.run(ctx, "1102-logic-f7hud-config", BossTimerCases::oldConfigs);
                LogicCase.run(ctx, "1102-logic-f7hud-slots", BossTimerCases::f7Slots);
                LogicCase.run(ctx, "1103-logic-timer-lag", TimerLagCases::timerLag);
                LogicCase.run(ctx, "1103-logic-timer-monotonic", TimerLagCases::timerMonotonic);
                LogicCase.run(ctx, "1104-logic-goldor-trusted", TickFixCases::goldorTrusted);
                LogicCase.run(ctx, "1104-logic-hypixel-pings", TickFix3Cases::hypixelPings);
                LogicCase.run(ctx, "1104-logic-hypixel-goldor-drift", TickFix3Cases::hypixelGoldorDrift);
                LogicCase.run(ctx, "1105-logic-hypixel-servertime", MaskTimer1105Cases::hypixelServerTime);
                LogicCase.run(ctx, "1105-logic-ticklog", TickLogCases::ticklog);
                LogicCase.run(ctx, "1105-logic-ticklog-off", TickLogCases::ticklogOff);
                LogicCase.run(ctx, "1104-logic-split-lag", TickFixCases::splitLag);
                LogicCase.run(ctx, "1104-logic-stall-burst", TickFix2Cases::stallBurst);
                LogicCase.run(ctx, "1105-logic-split-jitter", LagLag1105Cases::jitter);
                LogicCase.run(ctx, "1105-logic-split-slow", LagLag1105Cases::slow);
                LogicCase.run(ctx, "1105-logic-bcstall-jitter", LagLag1105Cases::bcJitter);
                LogicCase.run(ctx, "1105-logic-bcstall-stall", LagLag1105Cases::bcStall);
                LogicCase.run(ctx, "1104-logic-serverlag-grim", TickFix2Cases::serverLagGrim);
                LogicCase.run(ctx, "363-logic-roof-cover", RoofCoverCases::roofCover);
            }
        }
        double s = (System.nanoTime() - t0) / 1e9;
        System.out.println(String.format(java.util.Locale.ROOT, "[logic] suite took %.1f s (budget 60 s)", s));
        if (s > 60) {
            System.out.println("[logic] OVER BUDGET");
        }
    }
}

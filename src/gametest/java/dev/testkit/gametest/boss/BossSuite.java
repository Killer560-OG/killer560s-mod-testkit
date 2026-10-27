package dev.testkit.gametest.boss;

import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.TestMap;
import dev.testkit.gametest.hx.Session;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * WP7 entrypoint: boss and P3 arena. Scenario names start `5NN-boss-`; `-Psuite=boss` selects them.
 *
 * <p>So far: the P3 Simon Says device, old (5 rounds) and new (4 rounds, Hypixel's 2026-10-06 update) - see
 * {@link SimonSaysCases}. The device is server side ({@code hx/boss/HxSimonSays}) at the real coordinates
 * (x 110-111, y 120-123, z 91-95); the session's spawn platform is elsewhere and each case teleports to it.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class BossSuite implements FabricClientGameTest {

    public static final String SESSION = "500-boss-session";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        Session.run(ctx, SESSION,
                (server, s) -> TestMap.on(server)
                        .platform(-620, 150, -620, 6)
                        .catchFloor(140)
                        .survival()
                        .clearInventory()
                        .spawn(-619.5, -619.5, 0f)
                        .build(),
                s -> {
                    ModUnderTest.require("killer560smod");
                    SimonSaysCases.register(s);
                    TerminalAuraCases.register(s);
                    Ap3TermAuraCases.register(s);
                    BossAuraTurnCases.register(s);
                    // 556-557: dungeon-fixes (2026-10-08), P3 without Goldor's line, P5 on the Wither King's.
                    DungeonFixBossCases.register(s);
                    // Per-trigger-bot delays (786-788, 799, branch nt-trigger-delays), own block.
                    BossTriggerDelayCases.register(s);
                    // Frame-to-tick clicks proven clean on GrimAC (831-835, branch nt-grim-post), own block.
                    GrimTickCases.register(s);
                    // Lever Aura CPS (846, branch nt-aura-2), own block.
                    LeverAuraCpsCases.register(s);
                    // Lights Device, its own Aura and Triggerbot (1476-1478, branch lights-aura-1015), own block.
                    LightsDeviceCases.register(s);
                    // Auto Simon Says Stim and Momentum (1018-ss-*, branch todo-ss-stim-1018), own block.
                    SsStimMomentumCases.register(s);
                    // Auto Simon Says Timer Target on 4- and 5-round devices (1027-ss-*, branch ss4-1027), own block.
                    SsTimerTargetCases.register(s);
                    // M7 P5 Relic Triggerbot and Relic Look (1025-boss-relic-*, branch relic-1025), own block.
                    RelicCases.register(s);
                    // Quiz and relic wrong-click blockers (1090-1091, branch clickguards-1025), own block.
                    ClickGuardCases.register(s);
                    // AP3 run beside the Lights levers: no backwards step under the aura's turns (1026, ap3-backwards-1026).
                    Ap3LightsRunCases.register(s);
                    // The same route twice, aura off and on: the path must not change (1027, ap3-levers-1027).
                    Ap3LightsPathCases.register(s);
                    // Blink Charge + Release + Run in one box, in the boss world (1027, ap3-s2ee3-1027).
                    Ap3BlinkComboBossCases.register(s);
                });
    }
}

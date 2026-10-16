package dev.testkit.gametest.hx;

import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.TestMap;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * WP2: Hx fidelity, chat and HUD (port 25570). One shared server ({@code 100-hx-session}) and {@code 1NN-hx-*} cases,
 * each driving the mod through what Hypixel would send (system chat, overlay chat, sidebar, tab list, items, a map
 * packet, command responders) and asserting the mod's own state or a command the server received.
 *
 * <p>Case groups: {@link HxDungeonCases} (101-119), {@link HxSocialCases} (120-134), {@link HxHudCases} (135-140),
 * {@link HxBatCases} (521-523), {@link HxHostileCases} (150, last: it deliberately pollutes party state). Every package gets at least one positive
 * case and one forged/hostile case. Fixtures: {@code testkit-fixtures/{chat,sidebar,tab,hostile}/}.
 *
 * <p>The player stands on a platform at (-420, 150, -420): negative x/z keep ScoreCalculator's and LiveMap's
 * coordinate boss checks (F7 boss = x > -7 and z > -7) false, so "in a dungeon" means the clear.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class HxSuite implements FabricClientGameTest {

    public static final String SESSION = "100-hx-session";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        Session.run(ctx, SESSION,
                (server, s) -> TestMap.on(server)
                        .platform(-420, 150, -420, 6)
                        .catchFloor(140)
                        .survival()
                        .clearInventory()
                        .spawn(-419.5, -419.5, 0f)
                        .build(),
                s -> {
                    ModUnderTest.require("killer560smod");
                    HxDungeonCases.register(s);
                    HxSocialCases.register(s);
                    HxHudCases.register(s);
                    HxBatCases.register(s); // 521-523, the Murkbat bat bonus (own block)
                    // Mod Chat + Party Commands (571-580, branch nt-modchat-party), before the hostile case that
                    // deliberately pollutes party state.
                    HxModChatPartyCases.register(s);
                    HxHostileCases.register(s);
                    // Command Shortcuts' custom rows (544), own block.
                    HxCmdShortcutsCases.register(s);
                    // Chat Keybinds with Command Shortcuts merged in (623-625), own block.
                    HxChatKeybindsCases.register(s);
                    // Profile Viewer + Nickhider changes (691-698, branch nt-pv-nick), own block.
                    HxPvNickCases.register(s);
                    // GUI/HUD items of 2026-10-11 (716-726, branch nt-gui-hud), own block.
                    HxGuiHudCases.register(s);
                    // Automation timed by the measured ping (791-796, branch nt-ping-automation), own block.
                    HxPingAutomationCases.register(s);
                    // Per-trigger-bot delays (797-798, branch nt-trigger-delays), own block.
                    HxTriggerDelayCases.register(s);
                    // Player Cosmetics names: fade, tab, nametag height, own name in F5 (811-818, branch
                    // nt-cosmetic-names), own block.
                    HxCosmeticNameCases.register(s);
                    // Auto Meow replies in the trigger's channel, and Mod Chat sends (874, branch
                    // nt-automeow-channel), own block.
                    HxAutoMeowChannelCases.register(s);
                    // Name Animation: Wave / Pulse / Off on faded names, own and shared (960-965, branch
                    // name-wave), own block.
                    HxNameWaveCases.register(s);
                    // Auto Blood Camp: offset from the kill timer, Blood Room gate, aim height, human turn, mouse
                    // takeover, miss retry, Freecam Mode (177-183, branch bloodcamp-1015), own block.
                    HxBloodCampCases.register(s);
                    // Custom Scoreboard made simpler: right-edge anchor, on-screen clamp, old configs, real lines
                    // (1661-1667, branch scoreboard-simple-1015), own block.
                    HxScoreboardCases.register(s);
                    // Staleness of the FPS pass's caches: every cached input changed, the display read back
                    // (1016-hx-stale-*, branch fps-combined-1016), own block.
                    HxStaleCases.register(s);
                });
    }
}

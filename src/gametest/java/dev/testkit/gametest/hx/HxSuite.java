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
                    // Song Tag: a supporter's Spotify song above the name, over the relay (820, branch song-tag), own
                    // block.
                    HxSongTagCases.register(s);
                    // Spotify Controls, lyric clock re-sync, My Lyrics In F5, Smooth Lyric Scroll, supporter-only
                    // Share My Song (2840-2845, branch spotify-controls-1026), own block.
                    HxSpotifyControlsCases.register(s); HxSpotifyLyrics1108Cases.register(s);
                    HxSpotifyDocCases.register(s);
                    HxSongTagParty1105Cases.register(s); // spotshare-1105: the party room survives a warp
                    HxSongFix1106Cases.register(s); // songfix-1106: a non-supporter sees a supporter's song and name
                    HxYtLyrics1108Cases.register(s);
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
                    // P3 Status HUD and titles, Chat Hider's dungeon pack (2140-2142, branch p3-chat-1025), own block.
                    HxP3ChatCases.register(s);
                    // F7 boss HUDs at exact server ticks (1102-hx-goldor-tick, 1102-hx-storm-line; mod branch f7hud-1102).
                    HxF7HudCases.register(s);
                    // Dungeon alerts of 2026-10-25: low health, Livid extras, score warning, secret feedback, breaker
                    // charges, key spawn (1025-alerts-*, branch dungeon-alerts-1025), own block.
                    HxAlerts1025Cases.register(s);
                    // featB-1108 dungeon alerts (Rare Room, Rare Drop, Duplicate Class, Wither King), mod branch featB-1108.
                    HxFeatB1108Cases.register(s);
                    // To-do Doc items of 2026-11-01: Creation Cooldown, Insta Clear Warning, Gyrokinetic Wand Overlay,
                    // Chat Tabs (1101-extras-*, mod branch doc-extras-1101), own block.
                    HxDocExtras1101Cases.register(s);
                    // To-do Doc items of 2026-11-01: Score Warning, split Counting, Core Entry, Livid, Blessings Boss
                    // Only, Trigger lines, locked chests and chest drops, Door Keys (1101-dhud-*, mod doc-dhud-1101).
                    HxDocDhud1101Cases.register(s);
                    // Split Timers chat colors from the HUD, Core Entry reads 0 for players already inside
                    // (1102-splitchat-*, mod branch splitchat-1102), own block.
                    HxSplitChat1102Cases.register(s);
                    // ScoreboardData with Custom Scoreboard off; Auto GFS's Dungeons/Kuudra gate (1026-hx-sbdata-*, mod
                    // open-key-1026), own block.
                    HxSbData1026Cases.register(s);
                    // Boss Health: name tags and boss bars to HUD lines (1025-bosshp-*, branch boss-hp-1025), own
                    // block, last: it latches the boss room and its cleanup case leaves it again.
                    // Performance HUD stutters: frame times and attribution (1026-stutter-*, branch stutter-1026).
                    // Before Boss Health, which latches the boss room until its cleanup case.
                    // The pre-2.0 mining features (1101-mining-*, mod doc-mining-1101), own block, before Boss Health.
                    HxMining1101Cases.register(s);
                    // Commission Mobs and the Emissary label's gray distance (1103-mining-*, mod commissions-1103).
                    HxCommissions1103Cases.register(s);
                    // Per-block commission areas, event-driven highlights, carpet stability (1103-mining-block-area,
                    // -highlight-latency, -carpet-stable; mod mithrilarea-1103).
                    HxMiningArea1103Cases.register(s);
                    HxCrystalWalls1104Cases.register(s);
                    HxDarkStone1104Cases.register(s);
                    // Blaze Solver boxes against water behind / in front / around (1103-blaze-water, mod blazewater-1103).
                    HxBlazeWater1103Cases.register(s);
                    HxDioriteGlass1103Cases.register(s);
                    HxEspDepth1103Cases.register(s);
                    HxPortalPixels1103Cases.register(s);
                    HxZFight1104Cases.register(s);
                    // My Lyrics In F5 on screen, real Spotify, as the player and as Play As Mob (1103-f5-lyric-pixels, mod f5lyrics2-1103).
                    HxF5LyricPixels1103Cases.register(s);
                    // Pickobulus timer and Commission HUD progress between tab refreshes (1103-mining-pick-timer,
                    // 1103-mining-com-progress*, mod mithril-interp-1103).
                    HxMiningInterp1103Cases.register(s);
                    // Commission Route, Walk / AOTE / Etherwarp (1103-mining-route-*, mod comroute-1103).
                    HxCommissionRoute1103Cases.register(s);
                    // Commission Mob Style and no range (1103-mining-mob-*, mod mithril-mobs-1103).
                    HxCommissionMobs1103Cases.register(s);
                    // Powder Ghast alert, timer, box and tracer (1103-powderghast-*, mod powderghast-1103).
                    HxPowderGhast1103Cases.register(s);
                    // Crystal Hollows powder grinding (1103-powdergrind-*, mod powdergrind-1103).
                    HxPowderGrind1103Cases.register(s);
                    // Chat Hider mining/sack hides and Hide Treasure Chest Uncovered (1104-chattidy-*, mod chattidy-1104).
                    HxChatTidy1104Cases.register(s);
                    // Treasure chest hide / tracer / countdown and the Golden Goblin (1104-chestgoblin-*, mod chestgoblin-1104).
                    HxChestGoblin1104Cases.register(s);
                    // Crystal Hollows Map HUD (1104-chmap-hud, mod chhud-1104).
                    HxChMapHud1104Cases.register(s);
                    // Structure Scanner: every loaded chunk once, named waypoints (1104-chscan-scan, mod chscan-1104).
                    HxChScan1104Cases.register(s);
                    // Treasure chest Hide Opened re-send and the chain tracer (1104-chestfx-*, mod chestfx-1104).
                    HxChestFx1104Cases.register(s);
                    // Powder Chest Triggerbot: swap to the best Gemstone Powder drill, click, rate limit (1104-chestbot-*, mod chestbot-1104).
                    HxChestBot1104Cases.register(s);
                    // Swap To Drill On Pigeon and Auto Ult, idle and with his use / attack / swap in their ticks (1105-same-*, grimgapC-1105).
                    HxSameTick1105Cases.register(s);
                    // Auto Superboom, cheat only, with his dig / use / scroll / teleport in the swap tick (1108-same-superboom-*, featD-1108).
                    HxSuperboom1108Cases.register(s);
                    // Deployables: lantern circles, in-radius and the timer HUD (1104-minedeploy, mod minedeploy-1104).
                    HxMineDeploy1104Cases.register(s);
                    // Owner requests 2026-11-03 late (1103-mask-proc-title, 1103-partycmd-self).
                    HxOwner1103Cases.register(s);
                    // Party Commands floor requests: Creation Cooldown wait, retry, your own !f (1104-partycmd-floor-*, mod partycmd-1104).
                    HxPartyFloor1104Cases.register(s);
                    // Resume Held Break After Menus and the AttackHold dump (1103-mining-hold-*, mod mithril-hold-1103).
                    HxAttackHold1103Cases.register(s);
                    // Powder Break Sounds (1104-powdersound-*, mod powdersound-1104).
                    HxPowderSound1104Cases.register(s);
                    // Owner-evidence log lines (1102-diag-*, mod diag-1101): Fast Leap stuck state, Raffle start dump.
                    HxDiag1102Cases.register(s);
                    // Online Count heartbeat against a stub relay (1102-online-*, mod onlinecount-1102), own block.
                    HxOnline1102Cases.register(s);
                    // How Lucky Are You / Full RNG Tracker (1101-luck-*, mod branch doc-rng-1101), own block.
                    HxLuckCases.register(s);
                    // Every-drop coverage: mob/sea creature kills, Looting, slayer level, RNG Meter, Kuudra, Nucleus,
                    // Experiments, Unknown-floor claims (1102-luck-*, mod branch luck2-1101).
                    HxLuck1102Cases.register(s);
                    // Dark Mode: world / HUD tint by framebuffer pixels, screens untouched, settings reload (1102-darkmode-*,
                    // mod branch darkmode-1102), own block.
                    HxDarkMode1102Cases.register(s);
                    // Tab on top: the player list over every mod HUD, the shared Ability key tap/hold rule (1106-tabtop-*).
                    HxTabTop1106Cases.register(s);
                    HxStutterCases.register(s);
                    HxBossHealthCases.register(s);
                    // 1103-kickrejoin-*: Kick Rejoin Timer (mod kickhub-1103).
                    HxKickRejoin1103Cases.register(s);
                    // mod chport-1104: the Mining features pulled from staging-mining-3 (2026-11-04).
                    HxMining3Cases.register(s);
                    // mod featC-1108: Puzzle HUD, Reparty, Watcher Helper.
                    HxFeatC1108Cases.register(s);
                });
    }
}

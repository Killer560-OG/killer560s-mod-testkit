# WP2 - Hx fidelity: chat + HUD

Plan: `C:/Users/Hunter/killer560s-mod-logs/TESTKIT-COVERAGE-PLAN.md` section 2 (WP2). Foundation and APIs: `docs/wp/wp1-foundation.md`.

- Worktree: `git worktree add C:/Users/Hunter/killer560s-mod-testkit-wt/hx -b wp/hx master` (from the main checkout)
- Port: 25570 (Hx bridge 25575)
- Scenario names: `1NN-hx-*`; suite: `-Psuite=hx` (`./run-suite.ps1 -Suite hx -Port 25570`)
- Entry point (pre-registered stub): `dev.testkit.gametest.hx.HxSuite`
- Owns: `src/testmod/.../hx/surface/**` (HxSurfaceModule), `src/gametest/.../gametest/hx/**` except Hx.java and Session.java, fixtures `chat/` `sidebar/` `tab/` `hostile/`

## Lessons

Record what was VERIFIED here, problem then fix, one or two lines each. Never a guess.

- (WP1, 2026-10-04, SessionDemoTest `099-session-demo-overlay-playerstats`, cheat jar main-8c43a6d) The ACTION BAR
  channel: `chat {overlay:true}` (ClientboundSystemChatPacket with overlay) set `PlayerStatsFeature.health` to
  1234/2345; a following `actionbar.packet` (ClientboundSetActionBarTextPacket) with 4321/4321 left it at 1234/2345.
  So the mod's action-bar parsers read overlay system chat only; send Hypixel action bars with `hx.overlay(...)`.
- (WP2, 2026-10-04, case 140) Sending ~19 commands from the client in one tick got it "Kicked for spamming" by the
  vanilla server, and every later check read as "the server never answered". Space client commands >= 15 ticks apart.
- (WP2, case 135) Only abilities built with an action-bar name are detected from the action bar
  (abilitycooldown/ItemAbility.java:160-167; e.g. ENDER_BOW "Ender Warp"); WITHER_IMPACT is sound-detected, so an
  "-150 Mana (Wither Impact)" overlay starts nothing. Pick the ability from ItemAbility, not from memory.
- (WP2, case 117) The live map calibrates from a bare `surface.map` packet (no server-side saved data) plus
  `filled_map[map_id=N]` in hotbar slot 8; vanilla never overwrites it because the server has no data for that id.
- (WP2, case 125) `c.scenario().reconnect()` mid-session re-fires the client JOIN event with the mc.hypixel.net label
  kept, and resets every per-level latch (run state keyed on `client.level`); the server sidebar survives, fake tab
  rows do not (re-send them).
- (WP2, case 101) A `Dungeon: Catacombs` tab line leaves IslandDetector.graphIsland() null (no "catacombs" entry in its
  ISLANDS map); features keyed on graphIsland do not see dungeons as an island.

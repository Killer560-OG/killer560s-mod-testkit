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

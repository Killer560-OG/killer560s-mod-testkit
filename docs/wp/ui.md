# WP4 - Client UI, commands, config

Plan: the maintainer's coverage plan (TESTKIT-COVERAGE-PLAN.md, not in this repo) section 2 (WP4). Foundation and APIs: `docs/wp/wp1-foundation.md`.

- Worktree: `git worktree add <worktreesDir>/ui -b wp/ui master` (from the main checkout)
- Port: none needed (singleplayer only); runs use `-Port 25630` so this checkout's server.properties stays distinct
- Scenario names: `3NN-ui-*`; suite: `-Psuite=ui` (`./run-suite.ps1 -Suite ui -Port 25630 -ModUnderTest <jar>`);
  the smoke group alone: `-Pscenario=ui-smoke`
- Entry point (pre-registered stub): `dev.testkit.gametest.ui.UiSuite`
- Owns: `src/gametest/.../gametest/ui/**`, `src/gametest/resources/testkit-ui/` (allow/deny lists; see Lessons)

## Cases

Title screen: 340 main menu theme (4 menus x 3 setting combos, MainMenuTheme.failed must stay false), 330
`ModPaths.migrateAll` over seeded root files (expected folders from the ModPaths table, a foreign file, a stale root
copy that must not overwrite the live one, AP3's legacy folder), 310 every `*Config` singleton's file round trip
(flip every boolean, load, save, compare), 311 every setter/getter pair (set, read back, save, load, read), 320
profiles save -> export -> import -> apply, with one boolean per config checked in memory after the apply.

Singleplayer world: 301 every ModScreen tab (folders opened section by section, collapsibles opened, content
scrolled through, frames extracted off screen at every step, then real frames on the window), 302 search for every
sub-tab name, 303 every ON/OFF toggle pressed twice with a real click, 304 HUD editor (Show Unseen on/off, every
element's render, one drag into HudConfig), 305 every concrete mod Screen in the jar (allow-screens.json;
completeness checked against the jar), 306 every client command root (parse + suggestions along every path, an
executed allowlist with the effect each must have), 350 termism practice screen per TerminalType with every cell
clicked, 360 18 visual features on for 60 rendered ticks, 380 Room Recorder capture-only must not turn into LIMBO.

End: 370 deny lists (hooks in force, accounts fixture, no child process during the whole run, ExternalOpen near
misses listed).

Off-screen frames (`ui/Frames`): `new GuiGraphicsExtractor(mc, new GuiRenderState(), mx, my)` and
`screen.extractRenderStateWithTooltipAndSubtitles(...)` run a screen's whole drawing code inside a try and count what
it drew. A screen is only put on the real window after a clean extract, because a throw while really rendering crashes
the client and loses every case after it.

## Lessons

- Fabric API for 26.1 renamed `ClientCommandManager` to `ClientCommands`; `Class.forName` of the old name throws
  ClassNotFoundException. `CommandSweep.dispatcher()` finds the static dispatcher getter by return type.
- Sim commands (`start`, `simwhere`, `summon`, ...) are `.requires(...)`-gated: outside the sim they parse to no nodes
  and execute as "Unknown or incomplete command". Check `node.canUse(source)` before calling that a bug.
- `/breakeraura list` does not exist; it is `/breakeraura config list` (dungeonextras/BreakerAuraCommands.java:75-82).
- Config round trips need VALID new values: key codes 0 load back as -1 (util/KeyUtil.sanitize, correct), 0..1
  floats stepped by 1 are clamped by load() (GifPlayerConfig volume), `*Seeded`/`*Migrated`/`*V1` booleans are
  one-shot markers load() sets again, and VoiceToText's `sendToPartyChat` is derived from `chatDestination` on save.
  None of those are lost settings.
- Ap3Config.routeScanPad is saved as a double and loaded with getInt (ap3/Ap3Config.java:397, 462), so 28.8 comes
  back 28.0 - but nothing sets a fractional pad (only load() calls the setter), so it is not reachable today.
- The plan's ">=170 tabs" was an estimate: f40ec89 builds 168 tabs in the cheat jar (10 top-level, 11 folders, 157\n  leaves) and 140 in the legit jar (10, 10, 130). My first legit floor (150) was a guess and was wrong; floors are\n  the measured values.
- The default gametest world is superflat with the player at y < 0, which the Room Recorder reads as Hypixel limbo
  (roomsim/DungeonInstanceCooldown.java:76). Anything keyed on "below the world" fires in a fresh gametest world.
- `/killer560 sim` in a singleplayer world arms the Room Recorder's capture-only mode by itself (dev builds), so
  it leaves recorder state behind for later cases; 380 stops it first.
- The gametest player DIED: 380 lifted him from the superflat spawn to y 106, he fell, and the client sat on the death
  screen (the user saw it, 2026-10-04). Every world case now starts through `SafeWorld.apply` (creative, invulnerable,
  flying, gamerule FALL_DAMAGE off), and `UiCase` fails a case that ends dead and respawns before the next one.
- A player teleported to y 107 with the server's `teleportTo` read y -60 again 20 s later even creative and flying
  (three tries); unexplained, so 380 no longer depends on lifting him.
- A Gradle run of this checkout and another WP's script can overlap in time but not in directory; when the report
  is missing, check `build/gametest-failed.txt`'s age before concluding the run died - it is only rewritten at the
  end of a run.
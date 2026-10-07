# WP3 - Menus + items

Plan: the maintainer's coverage plan (TESTKIT-COVERAGE-PLAN.md, not in this repo) section 2 (WP3). Foundation and APIs: `docs/wp/wp1-foundation.md`.

- Worktree: `git worktree add <worktreesDir>/menu -b wp/menu master` (from the main checkout)
- Port: 25580 (Hx bridge 25585)
- Scenario names: `2NN-menu-*`; suite: `-Psuite=menu` (`./run-suite.ps1 -Suite menu -Port 25580`)
- Entry point (pre-registered stub): `dev.testkit.gametest.menu.MenuSuite`
- Owns: `src/testmod/.../hx/menu/**` (HxMenuModule), `src/gametest/.../gametest/menu/**`, fixtures `items/` `menus/`

## Lessons

Record what was VERIFIED here, problem then fix, one or two lines each. Never a guess.

- (2026-10-04) `HxEvents.custom(type, player, data)` copies every key of `data` over the event, so a data key named
  `type` silently replaces the event type (terminal.solved arrived as "PANES" and every wait for it timed out while
  the server log said solved). Never put a `type` key in custom event data; HxTerminals uses `terminal`.
- (2026-10-04) `Session.run` can only select a subset of cases whose names CONTAIN the session name, and `2NN-menu-*`
  cases do not contain `200-menu-session`. MenuSuite filters with the environment variable `TESTKIT_MENU_ONLY=part,..`
  (inherited by the client through Gradle); unset runs everything. Request filed in docs/requests/menu.md.
- (2026-10-04) Fabric's `TestInput.pressMouse` always builds `MouseButtonInfo(button, 0)` (javap of TestInputImpl
  .pressOrReleaseKey): `holdShift()` never reaches `event.hasShiftDown()`, so a shift-click arrived as plain PICKUP.
  Send it through `MouseHandler.onButton(window, new MouseButtonInfo(0, 1), 1/0)` by reflection (243).
- (2026-10-04) Item names and lore must be STYLED components, not literals with section signs: the mod reads lore with
  `getString()` and its regexes (Stakes:, Rewards:, rarity, party finder) fail on a leading `§7`. `HxMenus.legacy`
  turns `§` codes into styles; menu fixtures write Hypixel's codes and get Hypixel's shape.
- (2026-10-04) A human PICKUP on an experiment note is predicted into the cursor, and Click Protection (solver-only,
  default on) answers with PICKUP at slot -999 (ExperimentsFeature.java:779-783). Count those apart from auto clicks.
- (2026-10-04) The auction listing helper only sets `copyButtonRect` when a BIN match exists, which needs auction
  listings (network, hook H1); offline, 248 checks the listed-item pick and the empty match instead.
- (2026-10-04) Terminal solver and Auto Terminals run from the screen's render pass (`TerminalSolverBackgroundMixin`),
  and the gametest client renders every tick, so they work in a Session without anything extra.

# WP3 - Menus + items

Plan: `C:/Users/Hunter/killer560s-mod-logs/TESTKIT-COVERAGE-PLAN.md` section 2 (WP3). Foundation and APIs: `docs/wp/wp1-foundation.md`.

- Worktree: `git worktree add C:/Users/Hunter/killer560s-mod-testkit-wt/menu -b wp/menu master` (from the main checkout)
- Port: 25580 (Hx bridge 25585)
- Scenario names: `2NN-menu-*`; suite: `-Psuite=menu` (`./run-suite.ps1 -Suite menu -Port 25580`)
- Entry point (pre-registered stub): `dev.testkit.gametest.menu.MenuSuite`
- Owns: `src/testmod/.../hx/menu/**` (HxMenuModule), `src/gametest/.../gametest/menu/**`, fixtures `items/` `menus/`

## Lessons

Record what was VERIFIED here, problem then fix, one or two lines each. Never a guess.

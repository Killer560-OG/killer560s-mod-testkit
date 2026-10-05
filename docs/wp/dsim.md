# WP6 - Full SIM on the dedicated server

Plan: `C:/Users/Hunter/killer560s-mod-logs/TESTKIT-COVERAGE-PLAN.md` section 2 (WP6). Foundation and APIs: `docs/wp/wp1-foundation.md`.

- Worktree: `git worktree add C:/Users/Hunter/killer560s-mod-testkit-wt/dsim -b wp/dsim master` (from the main checkout)
- Port: 25610 (Hx bridge 25615)
- Scenario names: `4NN-dsim-*`; suite: `-Psuite=dsim` (`./run-suite.ps1 -Suite dsim -Port 25610`)
- Entry point (pre-registered stub): `dev.testkit.gametest.dsim.DungeonServerSuite`
- Owns: `src/testmod/.../hx/dungeon/**` (HxDungeonModule), `src/gametest/.../gametest/dsim/**`, fixtures `dungeon/`

## Lessons

Record what was VERIFIED here, problem then fix, one or two lines each. Never a guess.

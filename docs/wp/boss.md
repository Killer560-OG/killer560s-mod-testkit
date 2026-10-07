# WP7 - Boss + P3 arena

Plan: the maintainer's coverage plan (TESTKIT-COVERAGE-PLAN.md, not in this repo) section 2 (WP7). Foundation and APIs: `docs/wp/wp1-foundation.md`.

- Worktree: `git worktree add <worktreesDir>/boss -b wp/boss master` (from the main checkout)
- Port: 25620 (Hx bridge 25625)
- Scenario names: `5NN-boss-*`; suite: `-Psuite=boss` (`./run-suite.ps1 -Suite boss -Port 25620`)
- Entry point (pre-registered stub): `dev.testkit.gametest.boss.BossSuite`
- Owns: `src/testmod/.../hx/boss/**` (HxBossModule), `src/gametest/.../gametest/boss/**`, fixtures `boss/`

## Lessons

Record what was VERIFIED here, problem then fix, one or two lines each. Never a guess.

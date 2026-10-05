# WP4 - Client UI, commands, config

Plan: `C:/Users/Hunter/killer560s-mod-logs/TESTKIT-COVERAGE-PLAN.md` section 2 (WP4). Foundation and APIs: `docs/wp/wp1-foundation.md`.

- Worktree: `git worktree add C:/Users/Hunter/killer560s-mod-testkit-wt/ui -b wp/ui master` (from the main checkout)
- Port: none (singleplayer only)
- Scenario names: `3NN-ui-*`; suite: `-Psuite=ui` (`./run-suite.ps1 -Suite ui`)
- Entry point (pre-registered stub): `dev.testkit.gametest.ui.UiSuite`
- Owns: `src/gametest/.../gametest/ui/**`, fixtures `ui/`

## Lessons

Record what was VERIFIED here, problem then fix, one or two lines each. Never a guess.

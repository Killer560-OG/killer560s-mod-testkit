# WP5 - Logic + fixture integrity + coverage tooling

Plan: `C:/Users/Hunter/killer560s-mod-logs/TESTKIT-COVERAGE-PLAN.md` section 2 (WP5). Foundation and APIs: `docs/wp/wp1-foundation.md`.

- Worktree: `git worktree add C:/Users/Hunter/killer560s-mod-testkit-wt/logic -b wp/logic master` (from the main checkout)
- Port: none (no server)
- Scenario names: `35N-logic-*`; suite: `-Psuite=logic` (`./run-suite.ps1 -Suite logic`)
- Entry point (pre-registered stub): `dev.testkit.gametest.logic.LogicSuite`
- Owns: `src/gametest/.../gametest/logic/**`, `tools/extract-patterns.py`, `tools/coverage-matrix.py`, fixtures `logic/`

## Lessons

Record what was VERIFIED here, problem then fix, one or two lines each. Never a guess.

# WP5 - Logic + fixture integrity + coverage tooling

Plan: the maintainer's coverage plan (TESTKIT-COVERAGE-PLAN.md, not in this repo) section 2 (WP5). Foundation and APIs: `docs/wp/wp1-foundation.md`.

- Worktree: `git worktree add <worktreesDir>/logic -b wp/logic master` (from the main checkout)
- Port: none (no server)
- Scenario names: `35N-logic-*`; suite: `-Psuite=logic` (`./run-suite.ps1 -Suite logic`)
- Entry point (pre-registered stub): `dev.testkit.gametest.logic.LogicSuite`
- Owns: `src/gametest/.../gametest/logic/**`, `tools/extract-patterns.py`, `tools/coverage-matrix.py`, fixtures `logic/`

## Lessons

Record what was VERIFIED here, problem then fix, one or two lines each. Never a guess.

## How to run

```
python tools/extract-patterns.py -PmodSource=<modSource>   # catalog -> src/gametest/resources/testkit-logic/
./run-suite.ps1 -Suite logic -Port 25640 -ModUnderTest <jarsDir>/main-f40ec89/killer560smod-1.1.0-26.1.2-cheat.jar
python tools/coverage-matrix.py --worktrees --report build/testkit-report     # build/coverage-matrix.md|json
```

Re-extract the catalog whenever the mod jar under test moves: 350 compares every catalogued regex with the jar's
live Pattern and fails on drift. The suite takes about 6 s (budget 60 s); 352 also writes
`build/testkit-report/pattern-coverage.md`. The mod source is read from `-Dtestkit.modSource`, `$TESTKIT_MOD_SOURCE`,
else `<modSource>` (see docs/requests/logic.md for the gradle property).

`testkit-fixtures/logic/action-gating.json` extends the schema with fields Fixtures ignores: `gates` (refs including
`Class#FIELD[i]` list elements), `sender`, `senderGroup` (the group holding the sender: a forged line may match only
if it captures the forger), `channel`, `hostileExempt` {ref: reason}, `parsedAsInt`.

## Lessons

- An `ItemStack` cannot be built at the title screen in 26.1.2: `new ItemStack(item, n)` throws "Components not bound
  yet" (item components bind when a world's registries load). 354 and 357 run inside a throwaway
  `ctx.worldBuilder().create()` world; everything else stays at the title screen.
- Fixture citations drift with the mod: f40ec89 moved AutoMeowFeature's quoted chat shapes from line 88 to 107, and
  "the line exists and is not blank" still passed. 351 now also requires the cited line to compile a pattern, name a
  cited field, or share a 3+ letter word with the payload.
- Two of the first failures were test bugs, not mod bugs: a tic-tac-toe "block" board that was really an X double
  threat (every move loses, so any answer is optimal), and an S+ sufficiency check whose odd secret total made the
  mod's own total estimate (floor(100/50*19+0.5) = 38) differ from the one the test then used (39). Build property
  inputs the mod would itself read back identically.
- `I4SensorsFeature.skyblockId` returns "" (not null) for "no id" on purpose - its callers call `.contains` on it. The
  ten skyblockId readers otherwise agree.
- `run-suite.ps1` exits 1 whenever any case fails, and a PowerShell loop around it still continues; read
  `build/testkit-report/summary.md`, not the exit code, and copy the report dir between runs (it is wiped each run).

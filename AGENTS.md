# Instructions for AI agents working in this repo

> Read this before writing a scenario or interpreting a result. It is short because the rules that matter
> are few, and every one of them exists because breaking it produced a **passing** test that meant nothing.

This repo tests Minecraft client mods against a **real anticheat** (GrimAC) on a **real dedicated
server**. `README.md` covers the API. This file covers how to behave.

---

## 1. Never state an anticheat result you have not run

- A source-level reading of a check is a **hypothesis**. A scenario result is **evidence**. Do not report
  "should pass", "is safe" or "has no anticheat surface" as if it were a run.
- `./gradlew build` passing says nothing about whether a module flags. Neither does a scenario that never
  reached the code you changed.
- Quote the actual line: `anticheat clean — no verbose lines`, with the scenario name and what the module
  was doing. If you did not run it, say so plainly.
- A flag with a check name is a **better** outcome than an untested guess. Report it as a result, not a
  setback.

## 2. Make every scenario prove its own premise

A scenario that measures the wrong thing passes just as easily as one that measures the right thing.
Before asserting anything about a module, assert the situation exists:

- Is the player standing where the map put them, at the height it built? (`playerPosition()`)
- Is there actually a gap to bridge / an opponent in range / a block to break?
- Did the thing under test do anything at all — a placement count, a hit count, a displacement?

Real examples from this codebase's history: a bridge scenario scoring 812 blocks that were a previous
scenario's platform; a knockback scenario landing ten hits on a player who never moved because the
damage was resisted; an aura scenario passing against a target that was invulnerable.

**If a run passes and you cannot say which numbers prove it, it did not pass.**

## 3. Do not weaken the detector to make a test pass

- Do not add to `FLAG_EXEMPT` or loosen `FLAG_MARKERS` in `Scenario` unless the line is provably not
  check output.
- Do not delete or skip `assertDetectorWorks()` because it is slow.
- Do not op the test player. GrimAC exempts operators; an opped client flags nothing ever.
- Do not lower an assertion threshold to turn a red run green. Fix the module, or report the failure.

## 4. Read the artifacts before guessing

When a run fails, the answer is usually already written down:

- `build/testserver-console.log` — the entire server console. Startup failures explain themselves
  hundreds of lines above wherever a tail begins.
- `testkit report` — every entity and where it is relative to the client.
- Print position, ground contact and whatever counter the module exposes **every second** during a run.
  A module that stops has usually stopped for a reason visible in three numbers.

## 5. Scope every claim

- A clean GrimAC run says nothing about behavioural/server-side detection that leaves no verbose and acts
  on a delay. Say which one your result covers.
- One clean run is not a durable claim. Say how long, on what map, doing what.
- Do not generalise from one movement pattern to "the module is safe".

## 6. Conventions

- Scenario names are `NN-slug`, so `-Pscenario=slug` selects one.
- Bots are named `Bot<Module><detail>` via `TestEnemy.named(...)`. Names are capped at 16 characters
  automatically — a longer one kicks every real client off the server.
- Build maps with `TestMap`, not raw `/fill`. Vanilla fill skips unloaded chunks and says so in a line
  nobody reads.
- New scenario classes must be registered in `src/gametest/resources/fabric.mod.json`.

## 7. When you change the harness

Run `-Pscenario=smoke` and `-Pscenario=proof` afterwards, every time. They are the only two tests that
check the harness rather than a module, and a broken harness reports everything as clean.

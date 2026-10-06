# Sharding (run-sharded.ps1)

```
./run-sharded.ps1 -Scenario "smoke,-ui-,93-solve,96-ar" -Shards 3 -ModUnderTest <jar>
./run-sharded.ps1 -Suite hx,solve -Shards 3 -Minecraft both -Jar261 <jar> -Jar262 <jar> -Max 3
./run-sharded.ps1 -Scenario "..." -Shards 3 -PlanOnly        # list mode + the split, runs nothing (~20 s per version)
```

One Minecraft client is on one server, so a faster run = N clients, each with its own dedicated server and Hx bridge
port, each in its own git worktree (Gradle locks a project dir; `run/testserver` and `build/` are per checkout). Shard
k of a version lives in `C:/Users/Hunter/killer560s-mod-testkit-shards/<mc>-<k>` (made from THIS checkout's committed
HEAD; uncommitted changes are not in them), ports `-BasePort` (25900) `+ 10g` for global shard number g, Hx `+5`. Keep
`-BasePort` clear of 25565-25599 and 25700-25850 (other checkouts and parallel-suite). `-Max` caps clients running at once
(machine guidance: 3; `-Minecraft both` queues both versions' shards under that cap, longest first). Nothing passes
`-PtestVolume`, so the clients stay muted. The first run in a new worktree builds it (a few minutes), after which it is
reused. Results: `build/sharded-<timestamp>/` with `summary.md/json` (a table per version: status, seconds and shard of
every name; MISSING = selected but no shard reported it, which fails the run), `<mc>/shard<k>/` (report, gradle log,
server console, client log, the shard's filter) and the list files.

How the split works. `-PlistScenarios=<file>` (build.gradle -> `testkit.listFile`, `harness/ScenarioList`) starts the
client, lets every test class run its OWN filter logic, and instead of running writes each name with whether it is
selected, its unit (the number of the test class run it belongs to: `Scenario.skip`, `Session.run`/cases and UiSuite
report into it) and for a Session case its session. No server starts and no world opens (~18 s total). A unit is the
indivisible piece: a Session and its cases, the UI group that shares one singleplayer world (UiSuite), the 96-ar cases that
share one room and an order, 02-seed build+load. Units are balanced longest-first onto the least loaded shard, by each
name's last measured seconds from `durations.json` (in the worktree root, refreshed from every shard's `summary.json`),
default 90 s (ui 20, logic 5, 93-solve 70, 96-ar 60, a session 360) when never measured - so the first split is rough and
the second is good. A shard's filter is exactly its names; a Session with only some cases selected is written
`<session>:,<case>,...` (a part that CONTAINS the session's name selects it without selecting all cases). The script warns
if a part would also match a name outside its shard (substring matching).
A single big Session (hx ~40 cases, menu ~55) is one unit and sets the floor for the wall time; splitting it by case would
cost another server start per shard.

## Measured (2026-10-05, 26.1.2, mod jar 649dc4ce build, 3 clients)

Set `smoke,-ui-,93-solve,96-ar` (43 names: 30 PASS + 13 RAN in every run):

- serial `run-scenario.ps1`: 538 s
- `-Shards 3`: 255 s for the shards, 272 s with the list step; shard times 255/215/205 s. The floor is the 96-ar class
  (226 s plus a client start) because it is one unit. 2.1x faster end to end.
- `-Shards 3 -SplitLarge` (96-ar cut in two): 290 s, no better - a chunk pays the class setup again and three
  clients at once run each slower. Left off by default.
- Where 96-ar's time goes (2026-10-06, 26.1.2, one client, 13/13 PASS, 283 s for the run): client start to room picked
  24 s, its one room build 42 s, the 13 cases 183 s (await 34 s, crypt 18 s, path 18 s, the rest 6-14 s each). A
  `-SplitLarge` chunk pays the client start and the 42 s build again, and the build cannot be shared between clients
  (each has its own world), so splitting saves at most ~90 s of cases per extra client. Not pursued further.
- `-Minecraft both -Shards 2` on `smoke,proof`: both versions at once, 8 PASS/FLAGGED rows each, exit 0.

## Where the time goes (server restarts)

From `testserver-console.log` and the client log of the serial run: the dedicated server takes about 7 s from process
start to "Done" (5 s Fabric loader and Grim, 2 s world), the client joins about 5 s later, and a scenario then arms,
sweeps, builds and settles. So the per-`Scenario.run` restart costs ~7 s of a 20 s scenario; the older "70 s per start"
no longer holds. Keeping one server across scenarios would save at most that, but scenarios rely on a wiped world (their
done-sets and coordinates), so no reuse was implemented: `hx/Session` stays the way to share a start where cases allow.
A smaller finding: a RAN row used to be closed at the end of the run for every name but the last of its class, so
96-ar's 13 rows read 499 s for a 226 s class; `SuiteVerdict.beginTest` now closes them when their class ends.

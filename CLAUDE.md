# killer560s-mod-testkit

Runs killer560s-mod against a real anticheat (GrimAC) on a real dedicated server, and reports what its
automation actually sends, per client tick, in the units a server-side check is built from.

Forked from [SicoKaleb/Automative](https://github.com/SicoKaleb/Automative) (CC0). The harness is his: the
test server launcher, the arena builder, the flag reader, and the positive control that refuses to call a
run clean until it has proved the anticheat can still see a violation. `origin` is killer560's GitHub fork
(github.com/Killer560-OG/killer560s-mod-testkit), `upstream` is SicoKaleb/Automative. Work happens on branches in
worktrees; the coordinator merges a round of work into master and pushes master to origin when it lands. Never
push to upstream.

## Run

Every test client must start MUTED (killer560, 2026-10-04: "have all those test instances you open have the
audio muted"). build.gradle writes every soundCategory to 0 before each run; never raise the default, and pass
-PtestVolume only when he asks to hear one. An old checkout or worktree still on a commit before this plays at 3% -
update it to master or pass -PtestVolume=0.

```
./run-suite.ps1 -Suite harness -Port 25565 -ModUnderTest <jar>          # named suite + freeze watcher + report
./run-scenario.ps1 -Scenario 60-secret -ModUnderTest <jar>              # one scenario, same watcher
./parallel-suite.ps1 -Suites hx,menu -Max 2                             # worktrees -wt/<n>, ports 25700+10n
./run-sharded.ps1 -Scenario "smoke,-ui-,93-solve,96-ar" -Shards 3      # ONE filter across 3 clients, see "Sharding"
./gradlew runClientGameTest -Pscenario=60-secret -PmodUnderTest=<jar>   # no watcher
./gradlew runClientGameTest -Psuite=demo -Pport=25575 -PmodUnderTest=<jar>
./gradlew runClientGameTest -Pnogrim -PmodUnderTest=<jar>               # anticheat removed
```

Use the snapshotted jars in `C:/Users/Hunter/killer560s-mod-testkit-jars/<mod-sha>/`, never the mod's build/libs
(it moves under a run); run-scenario.ps1 defaults to the newest snapshot. A server start is about 7 s here (measured 2026-10-05: launch to "Done"; a scenario's join, arm and settle add ~15 s);
`hx/Session` runs many cases on one start. `-Pport=N` patches run/testserver/server.properties (sticks for the
checkout) and puts the Hx bridge on N+5; concurrency needs separate checkouts (Gradle locks the project dir).
`-Psuite=<name>` reads `suites.properties`. `-PseedConfig=<dir>` copies into the client's config after the wipe.
Every run points `prismaccountswitcher.accountsFile` at an empty fixture and passes `killer560.net.offline=true`
and `killer560.test.noExternalOpen=true` (opt out: `-PnetOnline`, `-PallowExternalOpen`, or `-PnoQuiet` for all,
which also stops Session applying `mod/Quiet`). `-PnetOverride=svc=url;...` points mod services at fakes. Reports
land in `build/testkit-report/` (summary.md/json, cases/, screens/, coverage.md). WP1 foundation and every API is
described in `docs/wp/wp1-foundation.md`; change requests to frozen files go in `docs/requests/`.

Versions from `gradle.properties`: Minecraft 26.1.2, GrimAC pinned to `2.3.74-8eb5f28` downloaded from
Modrinth once into the Gradle user home (`caches/testkit-grim/`, shared by every checkout and worktree),
`cloud_version=2.0.0-beta.16` (beta.17 needs MC ≥ 26.2), sqlite-jdbc for Grim's violation store. Java 25.

## Sharding (run-sharded.ps1)

`./run-sharded.ps1 -Scenario "smoke,-ui-,93-solve,96-ar" -Shards 3 [-Minecraft 26.1.2|26.2|both] [-Max 3] [-PlanOnly]`
splits ONE filter across N clients, each with its own dedicated server and Hx port in its own worktree
(`C:/Users/Hunter/killer560s-mod-testkit-shards/<mc>-<k>`, ports `-BasePort` 25900 + 10g, Hx +5; keep clear of 25565-25599
and 25700-25850), at most `-Max` (3) clients at once, clients stay muted. Units that share a world (a Session, the UI
group, 96-ar) are never split unless `-SplitLarge`. Merged report in `build/sharded-<timestamp>/summary.md`. Measured
2026-10-05 on `smoke,-ui-,93-solve,96-ar` (26.1.2): serial 538 s, `-Shards 3` 272 s end to end. Details: [docs/sharding.md](docs/sharding.md).

## Minecraft 26.2 (runs: smoke, proof, seed and the UI group pass)

`-Pminecraft_version=26.2` switches the whole build, as in killer560s-mod. `versionsByMinecraft` in build.gradle
then supplies Fabric API `0.160.0+26.2`, loader `0.19.5` (his "26.2 mod only" Prism instance) and cloud-fabric
`2.0.0-beta.17`, unless those are passed with -P too; 26.1.2 with no flag is unchanged (dependency trees diffed
identical against master, 2026-10-04). Scripts take `-Minecraft 26.2`, which passes the flag and picks the
snapshot's `killer560smod-*-26.2-cheat.jar` (a jar named for the other version is refused):

```
./run-scenario.ps1 -Scenario "smoke,proof,02-seed" -Minecraft 26.2
./gradlew runClientGameTest -Pminecraft_version=26.2 -Pscenario=smoke -PmodUnderTest=<...-26.2-cheat.jar>
./gradlew grimServer -Pminecraft_version=26.2
```

GrimAC: the same pin, `2.3.74-8eb5f28` (its only per-version module is `grimac-fabric-mc261`). It DOES detect on 26.2:
smoke's positive control and proof's 46 verbose lines both came back on 2026-10-04.

Status 2026-10-04, first runs: `smoke,proof,02-seed` and the UI world group (301-306, 365) pass on 26.2 with the
26.2 cheat jar. What it took: `menu/*` goes through `McCompat.screen/setScreen`; `HxMenus` reads colours as the first
16 `ChatFormatting` ordinals (26.2 removed `isColor()`); the mod's own 26.2 startup crash (seven `Gui.extractRenderState`
mixins, fixed there in b0ae44cb; `365-ui-overlay-draws` proves an overlay draws); and `Scenario.connect` retries a join
that fails instantly (26.2 only: the client dials the moment the restarted server prints Done) up to 5 times, 2 s apart.

Not yet run on 26.2: everything else (sim, puzzles, menus, the 40-90 scenarios). `run/testserver` is shared by both
versions; scenarios delete the world, but a hand-played `grimServer` world opened on 26.2 cannot go back.

Version-specific API goes in `dev.testkit.compat` (`McCompat.screen/setScreen`, `McItems`, `McEntities`) under
`src/client/mc26_1/java` and `src/client/mc26_2/java`, one of which build.gradle puts on the client source path.
Both copies keep identical public signatures. Nothing in `src/*/java` may use `mc.screen`, `mc.setScreen`, a
colour-variant `Items.RED_...` constant or `EntityType.<CONSTANT>`: those are gone on 26.2 (the screen moved to
`Minecraft.gui`, colours to `ColorCollection.pick(DyeColor)`, entity constants to `EntityTypes`), and
`BlockPos.getCenter()` is gone too (use `Vec3.atCenterOf`). Before merging a branch, compile it with
`-Pminecraft_version=26.2` as well, or new code quietly breaks the 26.2 build.

## FPS bench (95-fps-bench)

```
./gradlew runClientGameTest -Pscenario=95-fps -Pport=<port> -PtestVolume=0 -PmodUnderTest=<cheat jar>
python tools/fps-jfr.py build/testkit-report/fps-on.jfr        # where the render thread's time went
python tools/fps-jfr-children.py <jfr> "HudInGameRenderer.draw" 2   # one entry point broken down by callee
```

`perf/FpsBenchTest`, about 10 minutes, only when named. Generates a sim F7, then alternates ON (defaults plus 56
HUD/ESP/map/solver switches, no automation) and OFF (every config with isEnabled/setEnabled off) for 3 x 1200 ticks.
`harness/FrameClock` (mixins on `Minecraft.tick` and `GameRenderer.extract/render`) times CPU only, swap and vsync
excluded. Results in `build/testkit-report/fps-bench.txt`, plus `fps-on.jfr`/`fps-off.jfr`. Read the ON-OFF DELTA
from one run: identical runs drifted ~0.06 ms in absolute terms (2026-10-05). The gametest loop renders ~1.5 frames
per tick and every frame here is CPU-light (~0.8 ms), so tick costs weigh far more than at his real frame rate.

## Layout

`src/gametest/java/dev/testkit/gametest/` holds the scenarios. `Scenario` runs one against the server and
asserts the anticheat said nothing; `Scenario.runExpectingFlags` records what it said instead, which is what
a sweep wants. `TestMap` builds the arena. `TestServer` launches the dedicated server. `ModUnderTest`
reaches into the mod by reflection. `src/client/java/dev/testkit/harness/PacketWatch` counts outbound
packets per tick, fed by mixins on `ClientCommonPacketListenerImpl#send` and `Minecraft#tick`.

Scenarios so far: 62-argrim (Auto Routes on GrimAC, see below), 48-56 Breaker Aura (with a by-hand control and an open-ground speed control;
picks-only since mod 9e83c4fc, so 50-52 pick the whole corridor and 53-56 cover side, floor, behind and through-wall picks), 60 Secret
Triggerbot, 99-sim-essence-aura (Secret Aura on a sim wither essence holding AOTV / Hyperion, first world and after a
rebuild; server-side click record, collection and Auto Routes' await; only when named, captures from "Map Logger" unless
`TESTKIT_SIM_INSTANCE` says otherwise).

## Auto puzzle suite (93-solve-*)

Moved to [docs/solve.md](docs/solve.md): how the puzzle suite runs, its flags and its traps.

## What a clean run means

GrimAC is not Hypixel's Watchdog. A clean run means the traffic is not obviously impossible and survives a
well-known movement and interaction model. It is a useful lower bound, not a safety certificate, and it must
never be described as one. The numbers transfer between anticheats; the verdict does not.

## Quirks and lessons

- The mod's Auto Scale (ON by default since 2026-10-05) scales its HUD and its own screens by
  `3 * min(W/2560, H/1440) / guiScale`. The default gametest window (854x480, GUI 2) is factor 0.5: mod screens are laid
  out at `guiSize / 0.5` and saved HUD positions are baseline units drawn at `saved * 0.5`, so compare a dragged element
  with `HudElementRegistry.resolvePosition`, never with `HudConfig.getPosition`. `380-ui-autoscale` resizes the window
  (`TestInput.resizeWindow`, then `options.guiScale().set(3)` + `mc.resizeGui()` - the resize alone keeps the old scale).
- The gametest client runs at render distance 5, so on a whole sim floor the far rooms' chunks never reach it: a map
  press on them says "Couldn't find goal position" and a client-side scan finds air. Scenario 95-sim-map-warp sets
  `mc.options.renderDistance()` to 16 for its run and puts it back. A sim floor's entrance is also sealed until
  `SimRun.begin` opens the gate (see 81/95), so nothing outside it is reachable before that.
- The etherwarp graphs warm whenever the Interactive Map is on in a dungeon, and behind the sealed entrance a warm-up
  sees only the entrance, so 95's "press during the first warm-up" raced both ways (full graph warm by GO, or the quick
  graph missing its 600 ms at GO under load). 95 keeps the map off until the floor's chunks are in and the gate is
  open, on until the quick graph is warm on 3,000+ nodes, then off until the press. Toggle the map to hold a graph cold.
- 95's press line used to quote the LAST plan of a press, so a 21-warp trip refused at its last hop and re-planned twice
  read as "1 warp for 175 blocks" (2026-10-06). It now lists every plan, fails on any "[Sim] no etherwarp target there"
  (a planned hop the server refused - knife-edge aims, fixed in mod im-quickgraph) and on N warps covering more than
  N x 61 + 30 blocks, and asserts a tile press uses exactly the warps of the mod's own [check] plan without the
  centre preference (build.gradle sets `killer560.test.checkFewest`), printing landing depth before vs after.
  Floors are random per run and the refusals depend on the floor: judge 95 over 8+ runs, not one.
- The mod arrives via Fabric Loader's `fabric.addMods` (`-PmodUnderTest`). `modLocalRuntime` does not exist
  in this Loom version, and a jar dropped in the run directory's `mods/` is deleted because the client
  gametest API rebuilds that directory every run.
- `PacketWatch`'s tick boundary must be at HEAD of `Minecraft#tick`, closing the previous tick. At TAIL it
  sat *before* Fabric's `END_CLIENT_TICK` handlers, so a module ticking there was counted against the next
  tick and the after-movement counter read zero while Grim reported 808 violations for exactly that. Every
  scenario now reconciles the counter against the server's own count and prints which to trust.
- A score holder name cannot contain spaces, so the sidebar line is built as a **team prefix** on a short
  holder (`TestMap.skyblockSidebar`). Quoting it in `scoreboard players set` is a parse error, and the
  failure looks like the objective existing with no lines.
- `Scenario.labelServerAs` sets the stored `ServerData` address while still dialling localhost, because
  several mod features require `getCurrentServer().ip` to contain `p3sim.net` or `hypixel.net` and have no
  override. It changes nothing the anticheat sees.
- Build the arena far into negative coordinates for anything gated on *not* being in the boss room; and use
  a real scoreboard sidebar rather than the mod's sim override, which forces boss phase on.
- Give every scenario its **own coordinates**. The done-set below is static and survives the world being
  rebuilt between scenarios in one client, so two scenarios sharing an arena means the second silently finds
  nothing to do and reports "nothing was sent". This has now cost three scenarios.
- A probe's own mistakes look exactly like findings. The entity-reach probe drew `Hitboxes` violations because
  it passed an entity's feet as the hit vector; the mod's own features aim at a point on the box and were never
  at fault. Separate the two halves in the scenario's own output, or a later reader will quote both.
  **Fixed 2026-09-29**: scenario 85 now clips a real eye-to-box hit, and it PLACES the player with a server
  `tp` at each distance instead of walking in. Walking sampled every ~1.08 blocks, which could not resolve the
  one question the mod asks of it. Placed at 0.1 resolution the boundary is exact: **3.00 clean, 3.10 flagged**,
  so `MEASURED_MAX_ENTITY_REACH = 3.0` is correct and sits right on the edge. A coarse probe is not a
  measurement, it is a range that happens to contain the answer.
- The gametest client is **java.exe**, not javaw.exe; matching javaw only left the freeze watcher and deadline cleanup
  working on an empty set while reporting success. The scripts match both, on the testkit path (see checkout markers).
- Features that click a dungeon secret keep a done-set and never click the same one twice, so one lever
  measures exactly one interaction. Use a row of them and strafe past, rather than writing yaw — a synthetic
  rotation from the harness would land in the packets being measured.
- `writeTestServerLaunch` joins launch args with NUL, written as the `\u0000` escape - build.gradle itself has
  no NUL bytes any more (counted 2026-10-04), so grep reads it as text.
- `ctx.runOnClient` WAITS for its task to finish, so a task that needs further client ticks to complete
  deadlocks the client. Opening a world from inside one killed the process outright (exit -805306369 /
  NTSTATUS 0xCFFFFFFF) rather than failing an assertion, and the frozen window had to be closed by hand.
  Queue that kind of work with `mc.execute(...)` from inside the task and then poll for the result.
  **The same trap through another door (2026-09-29):** `server.submit(...).join()` from the test thread
  deadlocks just as hard. The render thread parks in the gametest API's own `postRunTasks` waiting for the
  test thread, while the test thread waits on a future the integrated server can only complete once the client
  runs again. A thread dump shows the pair immediately — `jstack` the frozen client rather than guessing, and
  note the dump PowerShell writes is UTF-16. Use `server.execute(...)` into an `AtomicReference` and
  `ctx.waitFor` on it.
- Scenario 71 froze for an hour looking like a mod bug. It was not: the client log showed the sim had built
  the room perfectly ("Sim build finished: 77850 blocks") before the freeze. Read the client's own log first —
  this is the second time a "the sim freezes" hunt has ended at something that was not the sim.
- The auction-house scan (about 43,000 listings across 44 pages, each decoded to an ItemStack) starts as soon
  as a player exists and is heavy enough to matter in a gametest client. Every sim scenario turns it off with
  `ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled")`; nothing here tests it.
- These scenarios open a REAL Minecraft window on killer560's desktop for a couple of minutes. A hung one is
  his problem to close, so a scenario that can hang is worse than no scenario - give anything that waits an
  explicit bound, and tell him before starting a run.
- Upstream scenarios 40, 41, 42 report "built 0 block(s)", fall to the catch floor and pass as clean, and 34
  reports "naked 0, diamond-armoured 0" — four tests that go green while proving nothing. Worth telling
  SicoKaleb; his movement example guards against it with a `travelled < 20` check.
- **The instrument is broken more often than the feature.** Three times on 2026-09-29: scenario 76 reported
  20 of 29 secret chests missing because it scanned only chunks that `hasChunk` said were loaded, and the
  player stands in one corner of a six-room-wide floor (use `getChunk`, which loads it); scenario 78 reported
  four puzzles "building nothing" because its measurement box was smaller than SimBoulderPuzzle's `FLOOR_Y`
  offset of 66; and its entity counter reads 0 for everything, with `Entity.class` and with `Mob.class`
  alike, while the puzzle's own log says it spawned five blazes. Get the mod to re-read the world and say
  what it finds before believing a scenario that says the mod is wrong.
- `System.out.printf` does NOT reach the gametest log - only `println` does. Scenario 82 printed its PASS line
  and silently dropped every per-floor number behind it, so the run "passed" with nothing to read. Use
  `System.out.println(String.format(...))`.
- A movement scenario must assert DISTANCE TRAVELLED before it asserts anything about where the player ended
  up. Scenario 81 reported "a player cannot pass this doorway" twice while the player had moved 0.00 blocks,
  and once more after walking 16.2 blocks into a wall because it aimed him from the cell centre rather than
  from where he stood. Place him square on to the thing under test and measure the crossing axis only.
- A scenario that throws leaves the sim world open and the client hangs until the deadline watcher shoots it -
  a frozen Minecraft window on his desktop. Put the teardown in a `finally`.
- Name the block that stopped the player. "3 of 8 doorways impassable" reads as a floor-generation bug when a
  shut wither door (coal block) and a blood door (red terracotta) are solid on purpose.
- An overlap assertion must be a real RECTANGLE INTERSECTION, not "is A below B". Scenario 83 compared the
  room list's bottom against the topmost widget anywhere on the screen, which is the search box sitting above
  the list by design, so it failed at y 64 naming the wrong thing - and it would not have caught what was
  actually wrong at that window size, the GRID running into the settings row, because it never looked at the
  grid. Check every drawn box against every control's box.
- Run a screen scenario and read the NUMBERS, not the verdict. The gametest window is about 240 GUI units
  tall, far smaller than his, so layout that is fine on his monitor can overlap there - which is how the map
  designer's `Math.max(14, ...)` cell floor was found. That small window is a feature, not noise: it is the
  cheapest way to test a layout at its limits.
- **An entity reads back only from a section the server ticks entities in.** `ServerLevel.getEntity(UUID)` and
  `getAllEntities` do not see an entity added to a chunk the server has for BLOCKS only: right after a sim floor opens
  (client still on `void_air`) a vanilla pig added beside the player is not readable. Every 89 skip on record had that
  shape (after 88 in the sim suite, and on 26.2; the old code skipped once in two on 26.2 on 2026-10-06), never a harness
  limit. 89 now waits for `level.isPositionEntityTicking` AND the client's own block (up to 90 s; once 24.5 s on 26.2)
  and passes. Keep a vanilla positive control in any entity scenario, so a not-ready world never reads as a mod defect.
- **"Not busy" is not "finished".** Eleven sim scenarios waited on `!SimBuildQueue.isBusy()` immediately
  after asking for a floor, which is true before the build starts as much as after it ends - `generate()`
  returns while the world is still opening and the rooms are queued from a later server task. So they all
  measured an empty world. Scenario 76's "found 0 chest(s)" and 89's "sim starred mobs never spawn" were
  both this, not the mod; 89 spent six runs on it. `SimBuildQueue.buildsFinished()` only counts completions
  and only goes up: `Scenario.simBuildCount(ctx)` before the request, `Scenario.awaitSimBuild(ctx, before)`
  after. With a real wait 76 finds 36 chests and 89 finds every mob kind alive after 40 ticks.
- **A scenario without a `finally` costs the scenarios behind it, not just itself.** 76 threw, left the sim
  world open, and the client never reached the title screen; the freeze watcher shot it 16 s later and 79,
  80, 82, 83, 84 and 89 never ran. The suite reported one failure for what was actually seven scenarios lost.
- **`35-combat-hopping-target` fails on its own, and always has.** The upstream hopping bot rises about 0.79
  blocks where a vanilla jump is 1.25, so the scenario's "it is not hopping" assertion is correct and the bot
  is the thing that is broken. Measured 2026-09-30 against two different mod jars minutes apart - 0.7661 and
  0.7909 - so it is not flaky and not caused by anything in the mod. It is upstream's, like 40/41/42 reporting
  "built 0 block(s)" and passing. Do not read it as a regression; any combat scenario that uses that bot as a
  moving target is measuring something stiller than it intends.
- **The first sim world a fresh client opens takes ~27 s to get its chunks** (measured twice, 2026-10-04): the
  player hangs "airborne" at the spawn while the server already has the room. Anything switched on during that
  window reads an empty world - the Ice Path solver read a 0-wall board, and the sim's Ice Path re-spawned its
  silverfish 10 times a second because the server could not see the last one. 93-solve waits for the client to
  stand on a block before switching an auto on; do the same in any scenario that judges client-side reading.
- `ModChat.send` lines never reach `ChatWatch` (they are added to the chat window directly, not received), but
  vanilla logs every shown line as `[System] [CHAT] ...` - note the prefix, a `startsWith("[CHAT]")` filter
  silently matched nothing. `LogTap` captures them together with the mod's own logger lines.
- **Checkout markers need a trailing slash.** The scripts found "our" client by the path prefix
  `C:/Users/Hunter/killer560s-mod-testkit`, which is also the start of every sibling checkout (-pzA, -pzB, -wt/N),
  so one checkout's run moved or killed another's client (pzB's placer moved pzA's window, 2026-10-04). Both scripts
  now anchor the root with `/` and require `fabric.dli.env=client`. Older checkouts still carry the old placer.
- `powershell -File script.ps1 -Extra a b` binds only `a` to a `string[]` parameter; parallel-suite.ps1 launches its
  children with `-Command` and single-quoted values instead.

- **Pass `-PmodUnderTest` a literal `C:/...` path.** In Git Bash, `$(cygpath -m "$(ls ...)")` around a long scratchpad
  path came back empty twice (2026-10-04, 2026-10-05) and the run died at configuration with "modUnderTest not found: \\",
  before any test ran. Write the path out.

- `pattern-catalog.json` is generated, and goes stale with every mod commit that adds or moves a `Pattern.compile`:
  350/351 failed on 2026-10-05 for that alone. Regenerate it (`python -X utf8 tools/extract-patterns.py --mod-source
  <mod checkout>`) before reading a logic failure as a mod bug, and point `TESTKIT_MOD_SOURCE` at the checkout the jar
  came from - the default is `C:/Users/Hunter/killer560s-mod`, which may be on another commit.
- One Session case: `-Pscenario=200-menu-session:,226-menu` - a part that CONTAINS the session's name selects the
  session without selecting all of its cases, then the other parts pick cases.
- A scenario that only calls `Scenario.skip` writes no report row of its own; `SuiteVerdict` gives it a RAN row (under
  "other") when its class ends. RAN is not PASS: read its `[name] PASS` line in the log.
- The sim plans no floor before the mod's room database has loaded (mod 0ad55108); room types come from it, and without
  it floors had no blood room. Call `Scenario.ensureRoomDatabase(ctx)` before any `plan`/`generate`. Offline, an earlier
  failed attempt backs off 30 s, which is why it waits up to 1000 ticks.
- `build/run/clientGameTest` (crash reports included) is rebuilt by the next run. Copy `crash-reports/` out first.
- `PacketTrace` opens a tick's bucket from its own START_CLIENT_TICK hook, registered after the mod's, so a packet a mod sends
  at START_CLIENT_TICK lands in the PREVIOUS bucket - right after that bucket's input packet, which is exactly the sneak
  the server applies to it. Read buckets after the run (96-ar's sampler does); reading one at END_CLIENT_TICK misses them
  (it counted 0 etherwarps on 2026-10-05).
- 96-ar (`SimAutoRoutesTests`): an Auto Routes Go To is an Interactive Map warp, and after one only a START node may arm
  until he passes one (the map-arrival interlock). Cases that arm non-start nodes run before 96-ar-screen.
- `gameMode.attack` on a 1-HP sim zombie next to the player did not kill it (96-ar-crypt, 2026-10-05: crypts stayed put);
  the sim plays Mage, whose left click is a beam along the look, which is the likely reason (not traced). A Hyperion
  `gameMode.useItem` (Wither Impact, radius 5) does kill it - use that to kill a sim mob as the player.

- `menu.experiment` Superpairs takes `layout` (tiles from slot 9: `{item,name}`, `{powerup:true}` for Instant Find or `{powerup:"clicks",amount:N}`, no shuffle). Powerups behave as killer560 described Hypixel's (2026-10-05): turning one over costs a click and never touches the turn (the open tile stays up); an Instant Find arms the next click, and they stack (`armed` in the state); an armed click claims that tile and its partner, closing the turn if the partner is the open tile. Cases 284-287 cover those rules. The state lists every claimed pair (`claimed`), the server-side truth 229 asserts on. `clicks` sets "Remaining Clicks" (the solver reads it since mod 5d579534); 280-283 play fixed boards with a tight budget and assert what was claimed and in which order (`ExperimentCases.play`). Main's solver before 5d579534 fails 229 and 280-283.
- The sim's `/goto` (SimTeleportCommands.goTo) scanned the ServerLevel from the RENDER thread; in the gametest lockstep a
  chunk load there deadlocked the client (99-sim-im, 2026-10-05, jstack). Fixed in mod b5eee0d6 (server.execute);
  `97-sim-goto-loop` freezes on any jar before it. 99-sim-im still places the player itself (`standOn`). A sim scenario that reads the legend's Extra Info must turn Score Calculator on first - it is
  off in a fresh config, and the section only draws with a live estimate.

- Start `PacketWatch` BEFORE switching the feature on: BreakerAuraTests' `configure` waits 5 ticks with the aura live, and
  53's first run saw every pick broken in that wait with 0 digs counted (2026-10-05). The same wait made 56/59 break the
  floor with Zero Ping still OFF until 2026-10-06 (set a setting BEFORE `configure`, add the pick afterwards). The floor
  flag (GroundSpoof/NoFall) needs a movement packet between the dig and the server's update, which a still player sends
  only on the 20-tick reminder - hence "some runs"; 60 turns the player so it is deterministic. Read
  `PacketWatch.digTimeline()` for which packet claimed ground over the dug block.

- A screenshot pixel count with a FIXED colour window is fragile: 388's first window missed a translucent fill blended
  over stone and its second counted peach sunrise sky uncovered by a 1 px edge shift. Diff each frame against a no-draw
  frame of the same scene and classify only changed pixels (`ui/BreakerDisplayCases.countOrange`), and look at the PNGs.

- Fabric API 0.155 has NO `net.fabricmc.fabric.api.client.command.v2.ClientCommandManager` (ClassNotFoundException,
  97-sim-roomcycle, 2026-10-06); the client dispatcher is `net.fabricmc.fabric.impl.command.client.ClientCommandInternals
  .getActiveDispatcher()`, and `mc.player.connection.sendCommand(...)` runs a client command as typed. A reflective lookup
  that swallows the exception never runs anything (SimServerSafetyTests does this; flagged).

Auto Routes on GrimAC (62-argrim): how to run it and its traps are in [docs/argrim.md](docs/argrim.md).
AP3 runtime (63-ap3: look/use entry tick, held-walk rule per node type, stopwatch HUD): [docs/ap3.md](docs/ap3.md).

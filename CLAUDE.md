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
./gradlew runClientGameTest -Pscenario=60-secret -PmodUnderTest=<jar>   # no watcher
./gradlew runClientGameTest -Psuite=demo -Pport=25575 -PmodUnderTest=<jar>
./gradlew runClientGameTest -Pnogrim -PmodUnderTest=<jar>               # anticheat removed
```

Use the snapshotted jars in `C:/Users/Hunter/killer560s-mod-testkit-jars/<mod-sha>/`, never the mod's build/libs
(it moves under a run); run-scenario.ps1 defaults to the newest snapshot. About 70 seconds per server start;
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
26.2 cheat jar. What it took:
- `menu/*` used `mc.screen` directly; it goes through `McCompat.screen/setScreen` like everything else now.
- `HxMenus` used `ChatFormatting.isColor()`, which 26.2 removed; colours are the first 16 ordinals.
- The MOD's 26.2 jar crashed at startup (seven `Gui.extractRenderState` mixins with the 26.1.2 signature). That was
  the mod, fixed there in b0ae44cb; `365-ui-overlay-draws` now proves an overlay reaches the screen.
- On 26.2 every join after the first failed instantly ("Failed to connect to the server", no connection) because the
  client dials the moment the restarted server prints Done. `Scenario.connect` retries that case up to 5 times, 2 s
  apart, and says which screen it is stuck on if a join still times out. 26.1.2 never needed the retry.

Not yet run on 26.2: everything else (sim, puzzles, menus, the 40-90 scenarios). `run/testserver` is shared by both
versions; scenarios delete the world, but a hand-played `grimServer` world opened on 26.2 cannot go back.

Version-specific API goes in `dev.testkit.compat` (`McCompat.screen/setScreen`, `McItems`, `McEntities`) under
`src/client/mc26_1/java` and `src/client/mc26_2/java`, one of which build.gradle puts on the client source path.
Both copies keep identical public signatures. Nothing in `src/*/java` may use `mc.screen`, `mc.setScreen`, a
colour-variant `Items.RED_...` constant or `EntityType.<CONSTANT>`: those are gone on 26.2 (the screen moved to
`Minecraft.gui`, colours to `ColorCollection.pick(DyeColor)`, entity constants to `EntityTypes`), and
`BlockPos.getCenter()` is gone too (use `Vec3.atCenterOf`). Before merging a branch, compile it with
`-Pminecraft_version=26.2` as well, or new code quietly breaks the 26.2 build.

## Layout

`src/gametest/java/dev/testkit/gametest/` holds the scenarios. `Scenario` runs one against the server and
asserts the anticheat said nothing; `Scenario.runExpectingFlags` records what it said instead, which is what
a sweep wants. `TestMap` builds the arena. `TestServer` launches the dedicated server. `ModUnderTest`
reaches into the mod by reflection. `src/client/java/dev/testkit/harness/PacketWatch` counts outbound
packets per tick, fed by mixins on `ClientCommonPacketListenerImpl#send` and `Minecraft#tick`.

Scenarios so far: 48-52 Breaker Aura (with a by-hand control and an open-ground speed control), 60 Secret
Triggerbot.

## Auto puzzle suite (93-solve-*)

```
./run-scenario.ps1 -Scenario 93-solve -ModUnderTest <cheat jar> -TimeoutSeconds 1500    # all eleven, ~12 min
./run-scenario.ps1 -Scenario 93-solve-icepath -ModUnderTest <cheat jar>                 # one room
```

`SimPuzzleSolveTests`: one scenario per sim puzzle room (tictactoe, icepath, icefill, higherblaze, lowerblaze,
creeperbeams, boulder, threeweirdos, waterboard, teleportmaze, quiz), then `[93-solve] SUITE SUMMARY`. Each loads
the room with `SimBuilder.buildSingleRoom` from the title screen (his path), gives AOTV in slot 1 and Terminator in
slot 2, waits until the CLIENT has the room under his feet, then switches on this room's auto (solver, master,
Etherwarp Reposition, pathing and Interactive Map too; every other auto off) and watches for 60 s. The verdict is
the sim's own `Sim*Puzzle.isComplete()`, failed if `SimRoomState.isFailed` ever went true; Boulder's is "a
container opened", because Auto Boulder auras the reward chest instead of pushing boxes. Status lines every 5 s
carry the solver's own state (`probes`), and a failure prints the last 60 mod/chat log lines (`LogTap`).

Room captures are copied from the "26.1.2 (Mod Only Test)" instance unless `TESTKIT_SIM_INSTANCE` names another
(`$env:TESTKIT_SIM_INSTANCE = "Map Logger"` before the script). They are not the same captures: on 2026-10-04
Boulder, Ice Fill, Ice Path, Quiz and Water Board differed, and Mod Only Test's Boulder does not arm as a puzzle
at all ("best 1 of 7 expected blocks") while Map Logger's does. Say which instance a verdict was played on.

Human input it drives, only after giving the auto 10 s to do it itself, and says so in the log: Quiz is placed
between the pillars, Three Weirdos in front of the NPCs (both autos only click within reach and never move), and
Teleport Maze is walked onto the start pad with the forward key.

## What a clean run means

GrimAC is not Hypixel's Watchdog. A clean run means the traffic is not obviously impossible and survives a
well-known movement and interaction model. It is a useful lower bound, not a safety certificate, and it must
never be described as one. The numbers transfer between anticheats; the verdict does not.

## Quirks and lessons

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
- The gametest client is **java.exe**, not javaw.exe. `run-scenario.ps1` matched javaw only, so its freeze
  watcher and its deadline cleanup both operated on an empty set while reporting success - which is why
  "it doesn't close on freeze" survived two rounds of fixes to the watching logic. It now matches both names,
  still discriminating on the testkit path plus `fabric.addMods`.
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
- **Entities cannot be READ BACK at all in a gametest client.** Not `getEntitiesOfClass`, not
  `getAllEntities()`, and not `ServerLevel.getEntity(UUID)` - and the writes are fine: a vanilla pig added on
  the server thread returns `addFreshEntity=true`, `isRemoved=false`, in a chunk `hasChunkAt` calls loaded, and
  is still invisible to all three (measured 2026-09-29, scenario 89). So a scenario that counts entities is
  measuring the harness, not the mod. Expose a count from the mod instead - and if a scenario genuinely needs
  to inspect an entity, add a vanilla POSITIVE CONTROL first and SKIP with that explanation when the control
  cannot be read back, because otherwise the blind spot reads as a defect in whatever is under test. Scenario
  89 spent six runs "finding" that sim starred mobs never spawn; the mod's own counters said it had spawned
  five entities the whole time. **Corrected 2026-09-30:** the readback is NOT the blanket blind spot this
  entry first claimed - once the build wait was real (see "Not busy" below) 89 read a vanilla pig by UUID and
  counted every entity type on the floor. What it depends on is the world existing yet: in a fresh client, or
  before the build has finished, the chunk under the player has not arrived (the server has it, `hasChunkAt`
  true, the client shows `void_air`) and nothing resolves. The control is still what matters, because it is
  what tells those two apart.
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

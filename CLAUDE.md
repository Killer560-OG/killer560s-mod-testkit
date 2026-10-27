# killer560s-mod-testkit

Runs a Fabric client mod (killer560s-mod, any other mod, or none) against a real anticheat (GrimAC) on a real
dedicated server, and reports what the client actually sends, per client tick, in the units a server-side check is
built from. Forked from [SicoKaleb/Automative](https://github.com/SicoKaleb/Automative) (CC0); the harness - test server
launcher, arena builder, flag reader, and the positive control that refuses to call a run clean until it has proved the
anticheat can still see a violation - is his.

This file is for any agent working in this repo, on any machine. **If `CLAUDE.local.md` exists, read it too** - in this
checkout, or, when this is a git worktree, in the main checkout (the parent of `git rev-parse --path-format=absolute
--git-common-dir`). It is gitignored and holds the machine owner's own rules, paths and workflow; where it is stricter
than this file, it wins. Read [AGENTS.md](AGENTS.md) before writing a scenario or quoting a result.

## Setting up and running

A new machine: follow README.md "Quick start" (`doctor.ps1`, `get-mod.ps1`, warm build, smoke). No configuration is
required. Machine-specific values (jar snapshots, the mod checkout, Prism room captures, the monitor test windows go on,
worktree roots) come from `testkit.properties` (committed, portable defaults) overridden by `testkit.local.properties`
(gitignored, also read from the main checkout by a worktree) and `TESTKIT_*` variables; `doctor.ps1` prints the
resolved values. **Never write a machine path into a tracked file**: scripts ask `tools/testkit-config.ps1`, the build
asks its machine-config block, scenarios ask `dev.testkit.gametest.Machine`.

Every test client starts MUTED: build.gradle writes every soundCategory to 0 before each run. Never raise the default,
and pass -PtestVolume only when the user asks to hear one. Scenarios open a REAL Minecraft window on the user's desktop:
bound every wait, say before a run that windows will open, and kill only processes you started (the scripts match
clients by this checkout's path).

```
./doctor.ps1 [-Port N]                                                  # prerequisites, config, ports, monitors
./get-mod.ps1 [-Minecraft both]                                         # clone + build killer560s-mod into jarsDir
./run-scenario.ps1 -Scenario 60-secret [-ModUnderTest <jar> | -NoMod]   # one filter + freeze watcher + window slot
./run-suite.ps1 -Suite generic -Port 25565 [-ModUnderTest <jar>]        # named suite, prints the totals
./parallel-suite.ps1 -Suites hx,menu -Max 2                             # worktrees <worktreesDir>/<n>, ports 25700+10n
./run-sharded.ps1 -Scenario "smoke,-ui-,93-solve,96-ar" -Shards 3      # ONE filter across 3 clients, see "Sharding"
./gradlew runClientGameTest -Pscenario=60-secret -PmodUnderTest=<jar>   # no watcher
./gradlew runClientGameTest -Pnogrim -PmodUnderTest=<jar>               # anticheat removed
./run-scenario.ps1 -Scenario smoke -Extra @("-PextraMods=C:/mods")      # load other mods beside it (folder or jar,jar)
```

`-PextraMods` adds every jar it names (or every jar in a folder) to `fabric.addMods`, skipping `fabric-api*` (the testkit
runs its own) and any jar with the mod under test's file name. 306 leaves out command roots another mod registered
(Flashback's `/flashback`). Give it a path with no spaces or brackets: `run-scenario.ps1` hands gradle its arguments
through `Start-Process -ArgumentList`, which does not quote them. Lithium makes the mod's `ChunkCacheLevelMixin` fail to
apply (logged as a WARN, 2026-10-20); the smoke suite passes regardless.

The default mod jar is the newest snapshot under `jarsDir` matching `modJarPattern`, never the mod's build/libs (it
moves under a run). `-Minecraft` follows the jar's `fabric.mod.json` when not given. A server start is about 7 s (launch
to "Done"; a scenario's join, arm and settle add ~15 s); `hx/Session` runs many cases on one start. `-Pport=N` patches
run/testserver/server.properties (sticks for the checkout) and puts the Hx bridge on N+5 and an HTTP fake on N+6;
concurrency needs separate checkouts (Gradle locks the project dir). `-Psuite=<name>` reads `suites.properties`.
Never start two `run-sharded.ps1` at once: both use the same shard worktrees (`<shardsDir>/<mc>-1`...), and two started
in the same second share one `build/sharded-<timestamp>` folder - on 2026-10-07 the menu run quietly died under the ui run.
`-PseedConfig=<dir>` copies into the client's config after the wipe. Every run points `prismaccountswitcher.accountsFile`
at an empty fixture, `prismaccountswitcher.universalProxyFile` into build/ (the real one is Prism's shared file every
instance reads) and passes `killer560.net.offline=true` and `killer560.test.noExternalOpen=true` (opt out:
`-PnetOnline`, `-PallowExternalOpen`, or `-PnoQuiet` for all, which also stops Session applying `mod/Quiet`).
`-PnetOverride=svc=url;...` points mod services at fakes. Reports land in `build/testkit-report/` (summary.md/json,
cases/, screens/, coverage.md). WP1 foundation and every API: `docs/wp/wp1-foundation.md`; change requests to frozen
files go in `docs/requests/`.

**Any mod, or none.** A scenario class that drives killer560s-mod (reflection, its commands, its behavior) carries
`@RequiresMod("killer560smod")`; without that mod loaded its scenarios and Session cases become `SKIP - needs
killer560smod` rows (`harness/ModGate`), never failures. A class without the annotation is generic and must pass with
`-NoMod` and with someone else's mod (suite `generic`; `k560` is the rest). A scenario that cannot run for lack of
machine data (room captures, the mod checkout) calls `Scenario.skipped(name, why)` and returns: a SKIP row with the
reason, not a RAN row whose reason is only in the log.

Versions from `gradle.properties`: Minecraft 26.1.2, GrimAC pinned to `2.3.74-8eb5f28` downloaded from
Modrinth once into the Gradle user home (`caches/testkit-grim/`, shared by every checkout and worktree),
`cloud_version=2.0.0-beta.16` (beta.17 needs MC ≥ 26.2), sqlite-jdbc for Grim's violation store. Java 25.

## Sharding (run-sharded.ps1)

`./run-sharded.ps1 -Scenario "smoke,-ui-,93-solve,96-ar" -Shards 3 [-Minecraft 26.1.2|26.2|both] [-Max 3] [-PlanOnly]`
splits ONE filter across N clients, each with its own dedicated server and Hx port in its own worktree
(`<shardsDir>/<mc>-<k>`, ports `-BasePort` 25900 + 10g, Hx +5; keep clear of 25565-25599
and 25700-25850), at most `-Max` (3) clients at once, clients stay muted. Units that share a world (a Session, the UI
group, 96-ar) are never split unless `-SplitLarge`. Merged report in `build/sharded-<timestamp>/summary.md`. Measured
2026-10-05 on `smoke,-ui-,93-solve,96-ar` (26.1.2): serial 538 s, `-Shards 3` 272 s end to end. Details: [docs/sharding.md](docs/sharding.md).

## Minecraft 26.2

`-Pminecraft_version=26.2` switches the whole build (`versionsByMinecraft` in build.gradle supplies Fabric API
`0.160.0+26.2`, loader `0.19.5`, cloud-fabric `2.0.0-beta.17`); scripts take `-Minecraft 26.2` and pick the snapshot's
`killer560smod-*-26.2-cheat.jar`. GrimAC is the same pin and detects on 26.2. Version-specific API goes in
`dev.testkit.compat` (`src/client/mc26_1/java` vs `mc26_2/java`, identical public signatures); nothing in `src/*/java`
may use `mc.screen`, `mc.setScreen`, color-variant `Items.RED_...`, `EntityType.<CONSTANT>` or `BlockPos.getCenter()`.
Compile with `-Pminecraft_version=26.2` before merging a branch. Run status, what it took, and the full rules:
[docs/minecraft-262.md](docs/minecraft-262.md).

## FPS bench (95-fps-bench)

`perf/FpsBenchTest` (~10 min) and `perf/HubPerfBenchTest` (~18 min), only when named: [docs/fps-bench.md](docs/fps-bench.md); notes in
[docs/scenario-lessons.md](docs/scenario-lessons.md). Per-feature cost ranking `95-perf-features` (~45 min) and the
regression gate `95-perf-gate` (~5 min, baseline `perf/perf-baseline.properties`): same doc, last section.

## Layout

`src/gametest/java/dev/testkit/gametest/` holds the scenarios. `Scenario` runs one against the server and
asserts the anticheat said nothing; `Scenario.runExpectingFlags` records what it said instead, which is what
a sweep wants. `TestMap` builds the arena. `TestServer` launches the dedicated server. `ModUnderTest`
reaches into the mod by reflection. `src/client/java/dev/testkit/harness/PacketWatch` counts outbound
packets per tick, fed by mixins on `ClientCommonPacketListenerImpl#send` and `Minecraft#tick`.

The scenarios added after the upstream set, one line each: [docs/scenario-lessons.md](docs/scenario-lessons.md).

## Auto puzzle suite (93-solve-*)

Moved to [docs/solve.md](docs/solve.md): how the puzzle suite runs, its flags and its traps.

## What a clean run means

GrimAC is not Hypixel's Watchdog. A clean run means the traffic is not obviously impossible and survives a
well-known movement and interaction model. It is a useful lower bound, not a safety certificate, and it must
never be described as one. The numbers transfer between anticheats; the verdict does not.

## Quirks and lessons

- Since mod layout-fixes-1026 `ModPaths.config(name)` no longer creates the folder: a case that writes a mod config path
  before the feature ever saved must `Files.createDirectories(p.getParent())` first (981 and six others did not, 2026-10-26).
  1026-layout-* (seed `fixtures/seed-layout-fixes-1026`) covers the layout; on an old jar 1026-layout-empty lists ~83 empty folders.

The 95-sim-map-warp notes (render distance, graph warm-up, press lines) are in [docs/scenario-lessons.md](docs/scenario-lessons.md).

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
  nothing to do and reports "nothing was sent". This has now cost three scenarios. (Since mod aura-rebuild Secret
  Aura's and the Triggerbot's sets also clear on a live map reset - any sim build - but not on a TestMap rebuild.)
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
- The auction-house scan (about 43,000 listings across 44 pages, each decoded to an ItemStack) starts as soon
  as a player exists and is heavy enough to matter in a gametest client. Every sim scenario turns it off with
  `ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled")`; nothing here tests it.
- Machine data is never assumed: a scenario that needs room captures or the mod checkout asks `Machine` and SKIPs with
  `Scenario.skipped` when it is missing. Before 2026-10-07 ~40 files named one machine's Prism folder and the mod
  checkout outright, so on any other machine a sim scenario either failed for want of data or ended as a RAN row whose
  "SKIPPED" was only in the log.
- Upstream scenarios 40, 41, 42 ("built 0 block(s)") and 34 ("naked 0, diamond-armored 0") go green proving nothing.
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
- A movement scenario must assert DISTANCE TRAVELED before it asserts anything about where the player ended
  up. Scenario 81 reported "a player cannot pass this doorway" twice while the player had moved 0.00 blocks,
  and once more after walking 16.2 blocks into a wall because it aimed him from the cell center rather than
  from where he stood. Place him square on to the thing under test and measure the crossing axis only.
- Name the block that stopped the player. "3 of 8 doorways impassable" reads as a floor-generation bug when a
  shut wither door (coal block) and a blood door (red terracotta) are solid on purpose.
- An overlap assertion must be a real RECTANGLE INTERSECTION, not "is A below B". Scenario 83 compared the
  room list's bottom against the topmost widget anywhere on the screen, which is the search box sitting above
  the list by design, so it failed at y 64 naming the wrong thing - and it would not have caught what was
  actually wrong at that window size, the GRID running into the settings row, because it never looked at the
  grid. Check every drawn box against every control's box.
- Two runs of a menu screenshot case never match byte for byte: the backdrop margin around the panel shows the animated
  title panorama, and the header search box's caret blinks. To prove a change left a theme pixel-identical (green-v2,
  2026-10-15: 940/946/947 on main vs branch), diff the shots and require every differing pixel to sit in the outer 3% of
  the frame or on the caret - both were the only differences there.
- Run a screen scenario and read the NUMBERS, not the verdict. The gametest window is about 240 GUI units
  tall, far smaller than a real one, so layout that is fine on a real monitor can overlap there - which is how the map
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
  `.../killer560s-mod-testkit`, which is also the start of every sibling checkout (-pzA, -pzB, -wt/N),
  so one checkout's run moved or killed another's client (pzB's placer moved pzA's window, 2026-10-04). Both scripts
  now anchor the root with `/` and require `fabric.dli.env=client`. Older checkouts still carry the old placer.
- `powershell -File script.ps1 -Extra a b` binds only `a` to a `string[]` parameter; parallel-suite.ps1 launches its
  children with `-Command` and single-quoted values instead. `-File ... -Extra "-PtestVolume=0","-PsolveRepeat=3"` is no
  better: it arrives as one string, so the volume flag is lost and the client played sound (2026-10-06). Call the script
  from PowerShell itself with `-Extra @("-PtestVolume=0", ...)`, and check the run prints "Test client volume set to 0".

- `run-sharded.ps1 ... | Out-File x.txt` (and `run-scenario.ps1`, 2026-10-20) leaves x.txt EMPTY: the script prints with
  Write-Host, which a pipeline does not carry; redirect the whole `powershell` process's stdout from Bash instead. An `until grep -q "merged report" x.txt` wait then never ends; eight such loops were found still running
  on 2026-10-06. Read `build/sharded-<timestamp>/summary.md`, or the background task's own output file. Do not poll
  `run-sharded.log` either: a grep holding it open made the script's log write throw and the run killed its own shards
  (2026-10-07; the write now retries, but wait on the background task instead).
- **A port someone else holds** (a clip recorder, another server) on N, N+5 or N+6 kills that run in odd places: on one
  machine `-Port 26900` put the Hx bridge on a recorder's 26905 and the menu session died with MalformedJsonException at
  Hx.connect. `doctor.ps1 -Port N` lists every listener between 25500 and 27000 with its process.
- **Pass `-PmodUnderTest` a literal `C:/...` path.** In Git Bash, `$(cygpath -m "$(ls ...)")` around a long scratchpad
  path came back empty twice (2026-10-04, 2026-10-05) and the run died at configuration with "modUnderTest not found: \\",
  before any test ran. Write the path out.

- `pattern-catalog.json` is generated, and goes stale with every mod commit that adds or moves a `Pattern.compile`:
  350/351 failed on 2026-10-05 for that alone. Regenerate it (`python -X utf8 tools/extract-patterns.py --mod-source
  <mod checkout>`) before reading a logic failure as a mod bug, and point `TESTKIT_MOD_SOURCE` at the checkout the jar
  came from - the default is `<modSource>`, which may be on another commit.
- One Session case: `-Pscenario=200-menu-session:,226-menu` - a part that CONTAINS the session's name selects the
  session without selecting all of its cases, then the other parts pick cases.
- A scenario that only calls `Scenario.skip` writes no report row of its own; `SuiteVerdict` gives it a RAN row (under
  "other") when its class ends. RAN is not PASS: read its `[name] PASS` line in the log.
- The sim plans no floor before the mod's room database has loaded (mod 0ad55108); room types come from it, and without
  it floors had no blood room. Call `Scenario.ensureRoomDatabase(ctx)` before any `plan`/`generate`. Offline, an earlier
  failed attempt backs off 30, 60, 120 s...; after copying, `Scenario.loadRoomDatabaseNow()` clears that backoff (77/110, 2026-10-07).
- `build/run/clientGameTest` (crash reports included) is rebuilt by the next run. Copy `crash-reports/` out first.
- `PacketTrace` opens a tick's bucket from its own START_CLIENT_TICK hook, registered after the mod's, so a packet a mod sends
  at START_CLIENT_TICK lands in the PREVIOUS bucket - right after that bucket's input packet, which is exactly the sneak
  the server applies to it. Read buckets after the run (96-ar's sampler does); reading one at END_CLIENT_TICK misses them
  (it counted 0 etherwarps on 2026-10-05).
- Start `PacketWatch` BEFORE switching the feature on: BreakerAuraTests' `configure` waits 5 ticks with the aura live, and
  53's first run saw every pick broken in that wait with 0 digs counted (2026-10-05). The same wait made 56/59 break the
  floor with Zero Ping still OFF until 2026-10-06 (set a setting BEFORE `configure`, add the pick afterwards). The floor
  flag (GroundSpoof/NoFall) needs a movement packet between the dig and the server's update, which a still player sends
  only on the 20-tick reminder - hence "some runs"; 60 turns the player so it is deterministic. Read
  `PacketWatch.digTimeline()` for which packet claimed ground over the dug block.

- A screenshot pixel count with a FIXED color window is fragile: 388's first window missed a translucent fill blended
  over stone and its second counted peach sunrise sky uncovered by a 1 px edge shift. Diff each frame against a no-draw
  frame of the same scene and classify only changed pixels (`ui/BreakerDisplayCases.countOrange`), and look at the PNGs.
  An EXACT compare against that frame fails too: the superflat grass brightens a few levels between shots as the day
  runs, and 389-ui-crosshair's first run counted 5,000 "changed" pixels for an 80-pixel cross. Count a pixel as changed only
  above a summed RGB delta (389 uses > 30), then the exact counts hold (80, 112 outline, 240, 36).

- Fabric API 0.155 has NO `net.fabricmc.fabric.api.client.command.v2.ClientCommandManager` (ClassNotFoundException,
  97-sim-roomcycle, 2026-10-06); the client dispatcher is `net.fabricmc.fabric.impl.command.client.ClientCommandInternals
  .getActiveDispatcher()`, and `mc.player.connection.sendCommand(...)` runs a client command as typed.
  SimServerSafetyTests used the missing class inside a catch that treated any exception as a pass, so it typed
  nothing for weeks; it now checks every command is registered on the real dispatcher (a missing class or command
  throws), types them with `sendCommand`, and fails if no command produced a chat line.

- From the MAIN MENU (the map designer, any sim screen opened before a world) `ModChat.send` shows nothing: it drops the line
  while `client.player` is null, so a chat assertion there can never pass. Assert the screen's status text or the mod's log
  line through `LogTap` instead (75-sim-map-editor-filters, 2026-10-06).
- `BazaarReskinCases.pressAt`/`toWindow` multiply by the mod's Auto Scale factor (0.5 in the 854x480 GUI-2 window), which
  is right only for something drawn on Auto Scale's canvas (the Bazaar/AH reskins, the mod's own screens). A box an overlay
  draws on a vanilla container is plain GUI units: 472 pressed Search instead of Scan All once bazaar-v3 changed the helper
  (2026-10-08). Convert those with the GUI scale alone.
- To lay a mod screen out at another size, `Screen.init(w, h)`/`resize(w, h)` do not work: Auto Scale's mixin divides both
  arguments (400 came back as 800 in 1076, 2026-10-22). Set `s.width` and `Mod.call(s, "init")`, measure in that same client
  call, then restore the same way.

- A HUD editor box is drawn at the element's `resolveScale` (HUD Scale x Auto Scale, 0.5 in the GUI-2 test window), so a
  press at `pos + width/2` of the UNSCALED size lands on the box's corner handle and RESIZES it: 613's first drop "never
  moved" (2026-10-08). Press at `pos + width * resolveScale / 2`, and assert `draggingId` and `resizeEdges == 0` first.
- `mod/Quiet` leaves some configs changed only in memory (AuctionConfig, BridgeConfig, ClickTranslate, ModChat, PartyData,
  Supporters: 617's first run, 2026-10-08), so anything that reloads configs from disk (a profile load) flips them back.
  And `new InventoryScreen(player)` in a creative world becomes the creative inventory in its init: compare against the
  screen that is up afterwards, not the object you passed.

- After `getInput().resizeWindow(1600, 900)` + GUI 2 the window reported an 800-wide GUI while the screen was laid out
  854 wide (and the PNG was 1600 px), so a screenshot's pixels-per-GUI-unit from `getGuiScaledWidth` landed every rect
  below its target and 695 read 0 changed pixels (2026-10-11). Take the scale from the open screen's own `width`.

- `testkit fill` takes a bare block id: `Identifier.parse("minecraft:ladder[facing=east]")` placed nothing, so 747's ladder
  and 746's slabs were missing on the first run (2026-10-11). Use `TestMap.command("fill ... block[state]")` for states. A
  console `tp @p ~ ~ ~` is relative to the CONSOLE (world spawn), not the player: `execute as @p at @s run tp @s ~ ~ ~`.

- A `SignEditScreen` built in a test needs a sign entity with `setLevel(mc.level)` and `setAllowedPlayerEditor(player
  uuid)` at his feet: its `tick()` asks the entity whether he is too far away, which NPEs with no level and crashed the
  client ("Ticking screen", 862's first run, 2026-10-12).

- An enchanted item drawn on the HUD (the mana bar's enchanted book icon) changes between any two screenshots: its glint
  moves. 892's first run counted 192 "changed" px for overflow mana 0 against none, all in the icon (2026-10-12). Diff
  from past the icon.
- Health and Mana Bars have no flat color since mod bars-2 (Bold/Sleek gradients, frame, icon), so an exact-color pixel
  search finds nothing. Use `ui/BarPixels`: the fill by its TINT, searched only inside the painter's fill area (the
  superflat ground's olive is 0.09 from yellow's tint and came in through a rounded corner), mapped back to the element
  box by the painter's insets. 407/441-444/476/393/399 use it.
- Typing in (or clearing) the mod menu's search collapses the open folder sections, and Hud Elements holds a tab named
  "Advanced Position": after a search, reopen the tab before pressing, and match a "▶ Advanced" header exactly (898's
  first run pressed the other one, 2026-10-12).

- After a sim scenario FAILS with its world open (80-sim-playable's room fidelity, which flaps on Temple 65-66 on main too),
  the runner's keep-going `disconnectWithProgressScreen` deadlocks against the integrated server parked in the gametest
  `postRunTasks` (jstack, 2026-10-15): "Test client has stopped responding" and every scenario after it is lost until the
  deadline. Kill only that client's PID and re-run the rest.

- "could not reach localhost:N ... Failed to connect to the server" on every join, with the server up and the port free,
  was the mod's Proxy Client sending the join through killer560's REAL universal proxy (Prism's shared
  `prismaccountswitcher/universal-proxy.json`, on since 16:06 on 2026-10-15). build.gradle points
  `prismaccountswitcher.universalProxyFile` at a fixture; a jar older than mod 6c09a491 ignores it and cannot join while
  his universal proxy is on. Join failures now print the DisconnectedScreen reason (`Scenario.describeScreen`).

- A `c.waitUntil` predicate already runs on the client thread: calling `c.onClient` (or a helper that does) inside it
  fails at once and is reported as "timed out after N ticks waiting for ..." (1094's first run, 2026-10-25). Read the mod
  directly with `Mod.staticCall` in a predicate.
- A harness turn written as an absolute `atan2` yaw jumps across the +-180 seam from wherever he was looking, and GrimAC
  flags it as AimModulo360 (1091's first run, 2026-10-25). Turn by `yaw + Mth.wrapDegrees(want - yaw)`.
- GrimAC's own update check logs `Failed to check latest version.` (INFO, Grim-Async thread) at an unfixed time after the
  start; it matched the "failed" flag marker and failed 1025-blink-shot-lever as "anticheat flagged 1 time(s)" naming no
  check (2026-10-25). Exempt in `Scenario.FLAG_EXEMPT` now.
- A key test hook flipped TRUE then FALSE then TRUE with only one tick between reads as ONE press: the feature's tick never
  saw the key up. BlinkTests.pressCharge waits a tick after letting go (1025-blink-charge-arm's "second press" did nothing).
- When the freeze watcher closes a client frozen at startup, that run's test server can stay up holding the port (and its
  logs): the next run in that checkout fails "Anticheat test server exited ... before it was ready" (FAILED TO BIND). Find the
  java.exe with `fabric.dli.env=server` and that checkout's path, kill it, rerun (2026-10-26, tk-blink-rework-c).
- Blink holds are key-driven since mod blink-rework-1025: between two holds the key needs a tick up and a tick down, so a
  landing released with one hold lands in that gap unless there is latency; 1025-blink-etherwarp installs 150 ms.

- A vanilla `fill`/`setblock` from a scenario's BUILD step fails with "That position is not loaded" for chunks the player
  has not loaded yet (the arena's own builder loads its area; the rest of a 40-block radius may not be). 1026-blink-terrain's
  first run measured ice, soul sand and cobweb lanes that were never built (identical distances everywhere). Build extras in
  the body after he stands there, and check the server log for that line.
- `TestEnemy.hits()` counts the enemy's own hits on the player, not hits it took. `BlinkTests.serverPos` scans `tail(400)`:
  with a chatty client the new `data get` line can fall outside it and an older one is returned (one 33-block "gap", 2026-10-26).
- The harness LagSpike (two-way freeze) draws GrimAC TimerLimit with NO mod feature on (8 flags for 1.5 s, sprinting or
  standing; 1026-blink-controls). A frozen client (render thread asleep 2.5 s) is clean. Compare against that control.
- `release=true` jars fail 306-ui-smoke-commands on `/killer560 mage` (a dev-only subcommand), not a regression.
- A fresh test server's FIRST block place takes it 16-162 ms to process on a busy machine, and GrimAC's 15 ms window for
  lending a place the next packet's rotation counts that time: a session whose first place turns on the packet after it
  flags RotationPlace post-flying, then pre-flying on every later place ("fails on every jar", 63-ap3, 2026-10-27). Warm
  the path with a plain place first (`Ap3RuntimeTests.warmPlaces`); the server log's `[testkit-gap]` line is GrimAC's number.
- **Load is the normal state (up to nine clients, 2026-10-26).** Four shapes of test broke under it and were fixed in
  preexisting-1026: a delay counted in client ticks but read on the SERVER's clock (trigger bots; count client ticks, see
  TriggerTiming); a key press counted as "the one that worked" by when the SERVER's event arrived (823 read "press 2" on
  the cold open: count the client's own packet); "drawn within the last 500 ms" (899: the 2560x1440 screenshot took
  longer; use "drawn since the scene was set up"); and two console `data get` reads assumed to land inside a 5-tick
  Blink hold (each is a round trip of a tick or more: check the hold was still running when the answer came).
- A `dungeon.tp` above a spot followed by a console `fill` there N ticks later lets him fall INTO the blocks as they appear:
  GrimAC Simulation x3 in 787 on every jar. `forceload add`, `sync`, fill, then tp onto it (832/835/787/788 do).
- A tp to where he already stands passes its own position wait before the teleport lands, and the landing then resets the
  look: 799 waits 20 ticks after it and only then looks away from the stand (the bot had clicked it the tick P3 began).

Per-scenario lessons (96-ar, Superpairs layouts, sim /goto, insta-clear, autosecret wither doors, 62-argrim frames,
143 camera, 141/142 autopilot, the mod's Auto Scale, terminal layouts, the logic world, 386's sweep):
[docs/scenario-lessons.md](docs/scenario-lessons.md).

Auto Routes on GrimAC (62-argrim): how to run it and its traps are in [docs/argrim.md](docs/argrim.md).
AP3 runtime (63-ap3: look/use entry tick, held-walk rule per node type, stopwatch HUD): [docs/ap3.md](docs/ap3.md).

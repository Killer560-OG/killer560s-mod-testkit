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

A test client never takes OS focus and never holds his cursor (killer560, 2026-11-02: "my mouse is bugging out ... it
keeps getting put in the game instances on the left monitor"). Fabric's gametest keeps the LOGICAL state (window
"focused", mouse "grabbed") and cancels Minecraft's InputConstants grab, but the OS side still reached him: a passive
probe saw the global ClipCursor rectangle equal to a running test client's window while the Claude app was in front.
Causes: windows were shown activated (26.3 SDL flags 0x2020 = shown with SW_SHOW; 26.2 GLFW focus-on-show), so
GLFW/SDL believed they had focus; then the mod's own native calls (FakeCursor's `McWindow.setCursorHidden` -> SDL relative
mode / GLFW_CURSOR_DISABLED, Shorts' `McWindow.focus`) and Confine Mouse (user32 `ClipCursor`, gated on `isWindowActive()`,
which the gametest forces TRUE) clipped and centered his cursor in the test window. Fix (`harness/OsInputGuard`,
`MixinNoGrab*`): windows created NOT_FOCUSABLE + SDL activate hints 0 (26.3) / GLFW_FOCUSED and FOCUS_ON_SHOW false
(26.2), WS_EX_NOACTIVATE set on the HWND, the mod's focus/setCursorHidden/restore and ConfineMouseFeature.tick cancelled,
and every tick a clip inside our window is released. 1102-nograb proves it from the OS (foreground, focus, capture, clip).
Never add a mod or testkit call that reaches the real cursor or focus without the guard covering it;
`-PclientJvmArgs=-Dtestkit.osGrab=allow` turns the guard off for diagnosis only.

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
run/testserver/server.properties (pinned for the checkout: `run/testserver/port-pinned.txt`) and puts the Hx bridge on N+5
and an HTTP fake on N+6; concurrency needs separate checkouts (Gradle locks the project dir). Without `-Pport` the port is
PICKED each run (tkdebt-1101): the main checkout prefers 25565, a worktree 26000 + 10 * (hash of its path % 90), and a port
whose N, N+5 or N+6 is taken moves up by 10, so two checkouts never default onto one port (the build prints the choice). `-Psuite=<name>` reads `suites.properties`.
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

Versions from `gradle.properties`: Minecraft 26.3 (the main version since 2026-11-01; 26.2 the second, 26.1.2 dropped),
GrimAC pinned to the `2.3.74` release (was the alpha `2.3.74-8eb5f28` until 2026-11-01, which stops at `<26.3`) downloaded
from Modrinth once into the Gradle user home (`caches/testkit-grim/`, shared by every checkout and worktree),
`cloud_version=2.0.0-beta.17`, sqlite-jdbc for Grim's violation store. Java 25.

## Sharding (run-sharded.ps1)

`./run-sharded.ps1 -Scenario "smoke,-ui-,93-solve,96-ar" -Shards 3 [-Minecraft 26.3|26.2|both] [-Max 3] [-PlanOnly]`
splits ONE filter across N clients, each with its own dedicated server and Hx port in its own worktree
(`<shardsDir>/<mc>-<k>`, ports `-BasePort` 25900 + 10g, Hx +5; keep clear of 25565-25599
and 25700-25850), at most `-Max` (3) clients at once, clients stay muted. Units that share a world (a Session, the UI
group, 96-ar) are never split unless `-SplitLarge`. Merged report in `build/sharded-<timestamp>/summary.md`. Measured
2026-10-05 on `smoke,-ui-,93-solve,96-ar` (26.1.2): serial 538 s, `-Shards 3` 272 s end to end. Details: [docs/sharding.md](docs/sharding.md).

## Minecraft 26.3 and 26.2

26.3 is the default (killer560, 2026-11-01: 26.3 main, 26.2 second, 26.1.2 dropped). `-Pminecraft_version=26.2` switches
the whole build (`versionsByMinecraft` in build.gradle supplies Fabric API `0.160.0+26.2`, loader `0.19.5`); scripts take
`-Minecraft 26.2` and pick the snapshot's `killer560smod-*-26.2-cheat.jar`. GrimAC is the same pin on both. Version-specific
API goes in `dev.testkit.compat` (client: `src/client/mc26_3/java` vs `mc26_2/java`) and `dev.testkit.server.compat.McServer`
(companion mod: `src/testmod/mc26_3/java` vs `mc26_2/java`), identical public signatures. Nothing in `src/*/java` may use
`mc.screen`, `mc.setScreen`, color-variant `Items.RED_...`, `EntityType.<CONSTANT>`, `BlockPos.getCenter()`, the use
packets' getters, `ServerboundSwingPacket` or a `"swing"` trace id, `org.lwjgl.glfw`, a bare mouse-button number (`McKeys.mouse`), or a bare key-code number (use `InputConstants.KEY_*`: GLFW
codes on 26.2, SDL scancodes on 26.3; GLFW numbers in mod settings, `McKeys.nat` for a press), or a bare modifier number
in a `KeyEvent`/`MouseButtonInfo` (use `InputConstants.MOD_CONTROL` etc.: 26.3's SDL bits are Shift 3, Ctrl 0xC0, Alt 0x300,
so GLFW's Ctrl `2` reads as right Shift there and Ctrl+F/V/A silently did nothing in 944/948/955/989, 2026-11-01; GLFW's Shift
`1` happens to be SDL left Shift). Build every KeyEvent with `McKeys.keyEvent(nativeKey, mods)`, never `new KeyEvent(...)`:
26.3's record is (scancode, SDL keycode, mods) and EditBox's Backspace/Delete/Home/End and Ctrl+A/C/V switch on the keycode,
so a 0 there is a key no text box acts on (944/989, 2026-11-01). Compile both versions before merging a branch. What 26.3 moved, what changed in
GrimAC, and how to run a set on the old Grim: [docs/minecraft-263.md](docs/minecraft-263.md); the 26.2 notes:
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

- A class that runs several cases and throws ONE AssertionError at the end is filed under the LAST case it started
  (`SuiteVerdict.started`): SimImapGapTests' 851 inner-corner failure showed as "857-sim-imapgap-doorcam FAIL" while 857
  itself printed PASS (mapfail-1104). Read the message's case prefix and the `[case] PASS/FAIL` lines before blaming the row.
- Screenshot pixel samples on the Interactive Map must sit clear of the hover tooltip (12 GUI px right of and above the
  cursor, ~55 px tall for a room): 851's inner corner sampled the tooltip's background (262320 / 27221D) at zoom 1 and now
  zooms 4x on the tile first. A HUD map fill count must skip fills nested in another: the Player Head marker (12 units plus
  a black frame, default on) is room-sized and was counted as a room by 1015-noamm-map (mapfail-1104).
- Four `run-scenario.ps1` runs started from one PowerShell script with `Start-Job` (three in brand-new worktrees) all
  ended "TIMED OUT after 240 s" with no client started (mapfail-1104). The same four ran fine after
  `./gradlew compileGametestJava` in each new worktree, each started as its own PowerShell tool background command with
  `-TimeoutSeconds 900`. Which change fixed it was not isolated, so for a parallel load check do all three.

- A server `fill`/`setblock` in chunks no player has loaded does nothing (2026-11-03, 1103-mining-route arena: the first
  case's floor never appeared), and teleporting there first so he falls onto a floor placed under him drew 7 GrimAC
  `Simulation` flags. `forceload add` the area before building it, and in a Session case put him back where he stood
  before removing a floor, or the next case starts falling (two Commission Mobs cases failed that way).

- A client session that passes about 9.5 minutes OFTEN draws a steady experimental GrimAC `NegativeTimer* -1.3 s`
  (20 lines a second while the scenario runs), in whatever scenario is going at that point. 2026-11-02: all six fairy
  island sessions did, 9.5 to 10.1 minutes from the first join; one 29-minute fairy suite did too, at 9.6 minutes in
  1017-fairy-turning. Other 29-minute suites did not. It happened on 26.3 and 26.2 and on jars m10-m14, whether that was the
  7th or the 8th scenario. It is the harness, not the mod; the cause is not known. For an experimental-checks verdict,
  keep a session under about 9 minutes (five island runs, seven arrival scenarios), or rerun the scenario that drew it in
  a fresh short session.
- 1017-fairy-water (and every FairySoulTests recovery case) used to start the walker 20 ticks after the spawn, while he was
  still dropping into the pool. Once on 26.2 that drew GrimAC Simulation .020 x0.8 at t0, before any key was pressed. Since
  2026-11-02 the case waits until he has been on the ground (the pool floor counts) for 5 ticks running, up to 120 ticks.

- A run the freeze watcher closes ("FROZEN - the client was closed automatically") while the Test thread waits in
  `TestServer.start` (a slow dedicated-server start under load, not a hang) leaves that run's dedicated test server
  running: the next run on the same port then fails with "Anticheat test server exited with code 0 before it was ready"
  (its log: FAILED TO BIND TO PORT). After a FROZEN run, find java.exe processes of that checkout without
  `fabric.dli.env=client` and stop them before the next run (simmobs2-1101, 2026-11-02, three in one checkout).
- Launching run-scenario.ps1 through `powershell -NoProfile -File ...` from the Bash tool produced an empty log and no
  run (2026-11-02); the same script started from the PowerShell tool ran. `-File` also passes `-Extra "a","b"` as ONE
  string. Use the PowerShell tool, and pass extra -P arguments as one delimited string the script splits.
- 62-argrim's Auto Clear cases leave starred mobs alive (acbat's: the server has no bat); a later case that counts on its
  own mobs only must kill the leftovers first (62-argrim-1102-*: `clearLeftoverMobs`).
- A MenuSuite case (`menu/*Cases`, names like `1853-menu-...`) does NOT run from `-Scenario 1853-menu-...`: that filter
  reaches no session and reports only unrelated SKIP rows (2026-11-01). Run `-Scenario "-menu-"` with the environment
  variable `TESTKIT_MENU_ONLY=1853-menu-swap,...` (MenuSuite.selected) to pick the cases.
- Adding `100-hx-session` to `-Scenario` runs the WHOLE hx session (every hx case, 20+ minutes, and it hung past a 1500 s deadline on
  unrelated cases, 2026-11-03). Name only the hx cases wanted; the session is only partly selected by a filter part that CONTAINS its name but is not contained in it: `-Scenario "100-hx-session+,1102-hx-goldor-tick,..."` (Session.selection), and the 1102-logic-* cases run from their own names.
- A new mod tab fails 511-ui-menu-layout with "unexpected new tab gui.tab.XTab" until its class is added to
  `MenuLayoutCases.NEW_LEAVES` (bzorders-1102, 2026-11-02). Add it in the same branch as the case that tests the tab.
- `c.server().command("playsound ... @p ~ ~ ~ ...")` runs at the console's position (world origin): "The sound is too far
  away to be heard" and the client gets nothing. Use `execute at @p run playsound ...` (1101-ap3-crystal-*, 2026-11-01).
  To prove a sound did or did not PLAY, read `harness/SoundProbe.count("minecraft:<id>")` (every sound reaching
  `SoundEngine.play`, counted even though test clients are muted) and send a control sound after it, waiting for the
  control, so "0" means dropped rather than not yet arrived (1102-sound-*, 2026-11-02). `-Scenario "-sound-"` also ran
  1021-theme-sound-import and three 102x blink/iw rows (a filter part is a substring): name cases `1102-sound`.
  AutoTrap.testTrapRoom (mod doc-routes-1101) makes the 62-argrim pasted room a trap room for a case; reset it to null.
- A checkout's `run/testserver/world` written by a 26.3 server will not load on 26.2 ("IllegalStateException: Overworld
  settings missing"): the 26.2 server exits, the client reads FROZEN and the server JVM can be left holding the port. Before
  a 26.2 run in a checkout that ran 26.3, move `world` aside (doc-routes-1101, 2026-11-01).
  variable `TESTKIT_MENU_ONLY=1853-menu-swap,...` (MenuSuite.selected) to pick the cases. That filter also runs the
  whole `*-ui-menu-*` UI group (~1 min); its 511-ui-menu-layout (AutoSprintTab) and 513-ui-menu-tooltips failed on mod
  main b01a1be7 itself (2026-11-02), not on any branch's change. A long menu run needs `-TimeoutSeconds` (default 240 s
  killed a 15-case run mid-way).
- Since mod 8a39696d an Auto Routes item await counts only a pickup NAMED as a dungeon secret drop (`SecretSound`'s list;
  `SimAutoRoutesTests.secretDrop` names it "Decoy"), and not one made after a click on a chest within 4.5 blocks - with
  `AwaitEvents.chestClicks` kept for the whole world, so a chest an EARLIER 96-ar case clicked at the same frame spot made
  the next case's item "come out of the chest" (uifix-1102). Call `forgetChestClicks(ctx)` before dropping one.
- A menu change on purpose (tab merged, renamed, row hover rewritten) goes in `testkit-ui/menu-layout-merged.json`
  (goneLeaves / newLeaves / tooltips with `to`, `renamed` or `gone`) and, for a tab whose rows moved into another,
  `MenuLayoutCases.MERGED`; 412 and 513 read both. 511 stops at its first `check`, so fix and rerun until it is clean.
- In a menu (Hx) session `PacketWatch.totalContainerClicks()` read 0 while the server logged nine container clicks
  (1101-menu-cover on the unfixed jar, 2026-11-02): judge menu clicks by `c.events("container.click")` only. The Hx chest
  menus also resync a clicked slot, so a real PICKUP there leaves nothing on the cursor; prove a click by the server record.
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
  java.exe with `fabric.dli.env=server` and that checkout's path, kill it, rerun (2026-10-26, tk-blink-rework-c). Since
  tkdebt-1101 run-scenario.ps1 does that itself (at the start, after a freeze kill and at the end), and a run without
  -Pport moves off a taken port. Under
  load a server JVM can take 25+ s to boot, the client window reads "Not Responding" while the test thread waits for it,
  and the watcher kills the client mid-run with the server left behind the same way (2026-10-27, ship2 tk-ship2-1027-e).
- A power loss (2026-10-27 blue screen) zeroed `run/testserver/banned-ips.json`, `banned-players.json` and `ops.json` in a
  checkout whose server was running: every later server start died with gson "Expected a JsonArray" and the client read as
  FROZEN. After a crash, check those files parse and write `[]` into any that do not.
- GrimAC's experimental checks (MultiInteractA/B and others) are OFF in every checkout's gitignored
  `run/testserver/config/GrimAC/config.yml`. To run a set with them on, give it its own checkout and, before its first run,
  write that file with `experimental-checks: true` (a copy of another checkout's with the line changed works; GrimAC keeps
  it). Prove it took: 1027-aafast-twoframes then records MultiInteractA/B (ship3-1027, tk-ship3-1027-x). The mod owner wants
  every new setting tested this way at its highest value (2026-10-27). In a NEW worktree the file does not exist yet:
  `mkdir -p run/testserver/config/GrimAC` and copy a config.yml with the line in before the first run; GrimAC keeps it
  (gridexp-1102). An experimental check's name carries a `*` in the verbose line (`MultiBreak*`).
- A case that sets a mod setting by reflection breaks the day the mod removes it ("Could not call ...setBreakerAuraMultiBreak",
  every 1022-ap3-breaker case, gridexp-1102). Use `ModUnderTest.setIfPresent` for a setting that may go, and branch the
  assertions on whether it is there (`ModUnderTest.hasMultiBreak()`), so old and new jars both run.
- Killing a queue script mid-run (or its client) can leave that checkout's test server running; the next server's GrimAC
  then waits ~14 s for the old datastore lease ("persistent storage UUID ... is owned by startupId ...") and the freeze
  watcher closes every client at startup (campaign-blink-1027). Kill the orphan `runTestServer` java of that checkout (match
  the checkout name in the command line; `[\/]` patterns missed it) before rerunning. A freeze-killed client leaves its
  server holding the port too: the next run on that `-Port` fails "Anticheat test server exited with code 0 before it was
  ready" (FAILED TO BIND TO PORT in run/testserver/logs/latest.log) - kill the orphan or pick another port (doc-rng-1101).
- The mod reads two slayer completion lines within 5 s as one boss (SLAYER QUEST COMPLETE + NICE! SLAYER BOSS SLAIN), so a
  case killing several bosses waits 5.5 s between them, or the second kill is silently the first one's (luck2-1101).
  `ChatObserver` also drops a chat line repeated within 250 ms: two identical `hx().chat` lines back to back reach the
  mod once.
- To run only some cases of a Session suite, the filter needs a part that CONTAINS the session's name but is not in the
  case names, plus the case parts: `-Scenario "1101-luck,100-hx-session:"`. `-Scenario 1101-luck` alone selects no session
  and the run "passes" with 0 cases (doc-rng-1101; Session.selection/caseSelected).
- A test client loads the mod jar straight from the `-ModUnderTest` path (fabric.addMods), lazily: overwriting that jar while
  the client runs crashed it ("ZipFile invalid LOC header", campaign-blink-1027). Give every build its own folder.
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
- `McCompat.setScreen(mc, null)` on a container screen sends NO close packet; `screen.onClose()` (what Escape does) does.
  The 1025-invwalk cases closed that way for two days and never saw the MultiActionsD every real close drew (1027-iw-*).
  GrimAC also CANCELS a click it flags (MultiActionsC), so "the server handled 0 clicks" is the flag, not a lost packet;
  Hx `player.state` and the `serverInput` on container.click/close events give the server's own view without a tick wait.
- A key pressed the first client tick after an HxAbilities etherwarp landing draws Simulation (.068 falling to .018, 2 or 8
  lines) with no screen and no mod feature involved (1027-iw-warp's key-after-landing control, 3 of 3); at 50 ms that is
  exactly when Inventory Walk's first walking tick falls, so judge 1027-iw-warp's L50 rows against that control.
- A crafting table at 300 ms: the client's prediction of a right-click in the grid differs from the server's result with no
  mod feature on (1027-iw-craftctl, 3 of 3 on and off alike); the server's final state is the intended one.
- A tp to where he already stands passes its own position wait before the teleport lands, and the landing then resets the
  look: 799 waits 20 ticks after it and only then looks away from the stand (the bot had clicked it the tick P3 began).

- The testkit's own `fabric.mod.json` files (src/main, src/testmod) carry a minecraft range too: after the 26.3 build
  compiled, the first 26.3 client died in Fabric Loader ("Mod 'Anticheat Test Kit' requires ... <26.3", 2026-11-01), and
  that refused client's java.exe stayed alive (loader error window) until its PID was killed. Widen both ranges with a
  version move (now `>=26.2 <26.4`).
- On 26.3 a bare key number pressed is a different key: input is SDL3 and `InputConstants.KEY_*` are SDL scancodes
  (ESCAPE 41), so `pressKey(256)` silently presses something else. Mod settings keep GLFW numbers on both versions (the
  mod's own rule), so write GLFW numbers into settings and press with `McKeys.nat(glfw)`; a native `InputConstants.KEY_*`
  goes to a press as is, never through nat. Same for arm swings: 26.3 has no `ServerboundSwingPacket` (left click sends `ServerboundPunchPacket`),
  so count swings with `McPackets.isSwing`, and by trace id with `McPackets.swingId()` ("punch" on 26.3), never `"swing"`.
  Mouse buttons too: 26.3 left is 1 (SDL), so `MouseButtonInfo(0, ..)`/`pressMouse(0)` click nothing while a screen's
  mouseClicked still returns true; build every button with `McKeys.mouse(glfw)` (ar263-1101). docs/minecraft-263.md has the full list.

Per-scenario lessons (96-ar, Superpairs layouts, sim /goto, insta-clear, autosecret wither doors, 62-argrim frames,
143 camera, 141/142 autopilot, the mod's Auto Scale, terminal layouts, the logic world, 386's sweep):
[docs/scenario-lessons.md](docs/scenario-lessons.md).

Auto Routes on GrimAC (62-argrim): how to run it and its traps are in [docs/argrim.md](docs/argrim.md).
AP3 runtime (63-ap3: look/use entry tick, held-walk rule per node type, stopwatch HUD): [docs/ap3.md](docs/ap3.md).
- A scenario class missing from `src/gametest/resources/fabric.mod.json` (AGENTS.md says to register it) runs NOTHING and the
  report shows only the unrelated SKIP rows with "none failed" - a vacuous pass (doc-adm-sim-1101, 2026-11-01). Check the
  case's own row is in summary.md. Scenario `System.out` lines land in build/run/clientGameTest/logs/latest.log as [STDOUT].
- Leave the Simon Says device (`dungeon.tp` away, a few ticks) BEFORE `boss.ss.clear`: clearing first drops him through the
  floor it stood on and GrimAC flags the fall (GroundSpoof + Simulation x1-x4) at the next case's start, which reads as the
  mod (1018-ss-stim-momentum on 26.3, 1101-ss-skip-second on 26.2, 2026-11-01). DocPuzzleCases and SsStimMomentumCases do.
- 320-ui-profiles' value probe reaches only singleton `*Config` classes with a boolean `setX`/`isX` pair, so Streamer Mode,
  Legit GFS, Cape and the sim loadout/filters were never probed and never reloaded (profreload-1102). 320 now also runs
  `ProfileReloadCases.nameAudit`: every class whose bytes name a setting file profiles carry must be named in ProfileManager.class,
  or sit in `NAME_ALLOWED` with a reason. 1102-ui-profile-streamer proves a profile apply changes Streamer Mode in memory.
- `run-suite.ps1 -Suite ui` needs `-TimeoutSeconds 3600`: the default 1200 s deadline is shorter than the UI suite
  (~27 min on 26.3), and its "Deadline passed - the run is hung" kills a healthy run at about case 897 with no report
  (2026-11-01, both versions at once).
- `powershell run-scenario.ps1 ... | tr -d '\r' > log` from Bash never returns when the freeze watcher closes a client at
  startup: the run's orphan test server inherited the pipe, so `tr` waits for an EOF that never comes and a queue script
  stalls for hours (ship1101e, 2026-11-02, twice; the log stops at exactly 64 KB, tr's buffer). Write the output straight to
  the file (`> log 2>&1`), and after any FROZEN run kill that checkout's `runTestServer` java before the next one.
- Menu Session cases (2790-2796 flip, 1101-menu-*, 1018-*) are selected only with the session part first:
  `-Scenario "200-menu-session:,1101-menu-,2790-"`. Without it the filter quietly runs nothing of the session and reports
  "2 passed" (ship1101e, 2026-11-02).
- GrimAC experimental checks flag Auto Routes, AP3 and the breaker on every jar since at least ship-1101d (2026-11-02, 26.3
  and 26.2, same cases on the ship-1101d control): MultiBreak* (breaker), PacketOrderE* / DuplicateRotPlace* (mimic, breakerwarp),
  RotationBreak* (26.2 boom), BadPacketsH* (AP3 boom) and BadPacketsV* (AP3 shot). Compare against a control jar before
  blaming a branch. Reports in killer560s-mod-logs/ship1101e-*-report. On a 26.3 LEGIT jar, 501/502-boss-ss also fail on
  DuplicateRotPlace* (x1 / x2-x3, the case's own hand clicks) on main 5c125680d and posmsgleap-1102 alike (2026-11-02,
  killer560s-mod-logs/posmsgleap-run-ss-*.log); 503 passes. Not a branch regression.
- A client can sit at the title screen for an hour with no test output: the gametest Test thread parked in
  `ThreadingImpl.enterPhase` (Phaser) and the Render thread in `Minecraft.postRunTasks` (Semaphore), a startup deadlock the
  freeze watcher did not catch with TESTKIT_HUNG_POLLS=60 (ship1101e zpew 26.3, 2026-11-02). If a run's client log has no
  `[STDOUT]` line a few minutes after "Room library", jstack it; on that pair kill the client's PID and rerun.
- The ZPEW campaign (`1027-zpew`) takes well over 40 minutes per version under load: a 2400 s deadline cut 26.2 off at
  1027-zpewc-sources with no report. Give it `-TimeoutSeconds 9000`.
- The relay cannot be reached from a test (offline, and 26.3 has no Yggdrasil for `RelayAuth`'s login). `hx/FakeRelay`
  puts its own WebSocket under `RelayClient` once ModChatFeature wants a room, so everything after the login is real:
  sends land in `sent()`, `deliver()` goes through `RelayClient.handle`, `mirrorAs(name)` plays a second member echoing
  this client's data packets. Close it, or the client keeps a CONNECTED fake (1102-boss-melody-*, tkdebt-1101).
- `Floor7Tracker`'s phase (and anything gated on P3, like the Team Melody HUD) needs him INSIDE F7's boss coordinates
  (x, z > -7): the menu suite's platform at -520 never reaches P3 whatever the sidebar and Goldor's line say ("timed out
  waiting for Floor7Tracker phase P3", tkdebt-1101). Use the boss suite and a forceloaded platform of the case's own.
- The ZPEW campaign (`1027-zpewc-,1025-zpew-,1027-zpew-worksfor,1027-zpewmt-,1027-zpewlk-,1102-zpew-ecancel`, 39 cases) takes
  well over an hour on a loaded machine (~1.6 min a case), longer than a 3600 s deadline: split it in two runs (zpewc/1025/
  worksfor, then zpewmt/zpewlk/ecancel). With nine clients up, clients froze at world changes and the watcher closed them,
  leaving the checkout's server on its port (zpew2-1101, 2026-11-02); kill `<checkout>\build\loom-cache\argFiles\runTestServer`
  java before the next entry.
- `HxAbilities` treats a carpet as SOLID (the etherwarp lands on it), and so do the mod's TeleportUtils and Etherwarp
  Overlay: killer560 (2026-11-02) said the carpet trouble was only on the sim, so doc-routes-1101's passable carpets stay
  out for good (ownerq-1102). Mod and model must agree, or 1027-zpewc-shapes-* fail "carpet: MISPREDICT (err 1.414)" and
  62-argrim-1020-midas never lands its warp. 1101-ew-carpet now expects the carpet block itself; 1102-sim-ew-carpet(-room)
  checks the sim's own etherwarp lands on the overlay's box.
- With experimental checks on, Blink Charge draws `BadPacketsV*` (a still position packet within 19 ticks of the last, no
  teleport in the tick before) about once per server teleport it keeps its bank through ("teleport confirmed - bank kept ...
  answers sent back to back with the confirm", then the flag ~0.5 s later), with no ZPEW press in between: 1027-zpewlk-*
  records 1-6 a case between its shots (its aimed-shot windows are clean), and 1102-zpew-ecancel saw it after the tp onto its
  square (zpew2-1101, 26.3 and 26.2). It is Blink's kept-teleport path (owner-accepted, 2026-10-27), for the Blink owner to
  judge; 1102-zpew-ecancel accepts only that check once Blink is on, and nothing before it.
- Never start a second `run-scenario.ps1` in a checkout whose queue is between or inside runs: at its start the script kills
  that checkout's "leftover" client and server, which is the other run's live ones ("Killing a leftover test client", ship-1102h
  2026-11-02 killed a ZPEW half mid-run). One queue per checkout; make another detached worktree for a rerun.
- A hung client blocked run-scenario.ps1's whole watch loop (no deadline, no freeze kill): `Sync-SlotWindow`'s plain
  `SetWindowPos` WAITS for the window's own thread, so the first tile move after the client hung never returned (goldfix-1104:
  a suspended client sat 22 min past a 2 min watchdog until the backstop; the likely cause of gate-1104c's two-hour 26.2 hang
  at the title screen). It now passes SWP_ASYNCWINDOWPOS. Never make a synchronous window call on a test client's window.
  Since goldfix-1104 the deadline, the freeze kill and the no-progress watchdog (`-ProgressMinutes`, default 20, env
  `TESTKIT_PROGRESS_MINUTES`, 0 = off; progress = a `[Test thread/` or gametest-api line in build/run/clientGameTest/logs/latest.log,
  never supporters/relay/catalog lines) end the WHOLE tree (by parent pid plus this checkout's clients and servers, taskkill /T /F);
  a hang exits 5 (timeout 2) and writes a FAIL "hung" row into summary.md; a hidden backstop kills the tree 3 min after the
  deadline if the script itself is stuck, and jcmd gets 20 s. Force a hang to test it with killer560s-mod-logs/goldfix-suspend.ps1
  (NtSuspendProcess on the client) and `TESTKIT_HUNG_POLLS=100000` so the freeze watcher does not take it first.
- 529-ui-sidebar-scroll-spy maps menu coordinates to screenshot pixels by screenshot width / the screen's own width: the
  menu is laid out at its own Auto Scale, so Window.getGuiScale() (2) put the probe on the background (menufix2-1102).

- `effect give @a <effect> <s> <amp> true` (hideParticles) also hides that effect's HUD icon: MobEffectInstance takes showIcon
  from visible, so 1102-ui-hide-effects-hud's first runs drew no icons with the hide switch OFF. Give HUD-icon effects with
  `false`. Night Vision brightens the whole world, so a frame with it never matches a frame without (31,000 "changed" px).
  The menu search cases (1102-ui-search-queries) take their queries from EffectsSearchCases.QUERIES: add a real query there
  whenever he reports one that found nothing.
- 525-ui-menu-every-master (mod menufix-1102) switches every row a card stands for ON alone and needs the card ON. A row
  drawn inactive for the account (Spotify Share My Song, a supporter perk) cannot be pressed and is skipped. RibbonTrailTab
  is a section inside Cosmetics, not a card. A menu StringWidget built from `Component.literal("§7...")` keeps the
  section codes in `getString()`: strip with `ChatFormatting.stripFormatting` before comparing text (526, 2026-11-02).
- With the mod's Auto Scale on (the default) the menu is laid out on an 854-wide canvas whatever the window size, so a
  1920x1080 GUI 2 window still gives the narrowest drawer (362). For the wide drawer turn Auto Scale off as well
  (`HudConfig.setAutoScale(false)`; 1920x1080 GUI 2 is then 960 wide, drawer 406) and restore it (1102-ui-leapmenu-shots).
- A UI run can pass every case and then hang closing its singleplayer world: Render thread in `IntegratedServer.halt` ->
  `executeBlocking` (from the gametest's deferDisconnect), Server thread parked in the gametest `ThreadingImpl.enterPhase`
  Phaser; the run log only says "Test client has stopped responding - watching it" and no summary.md is written (leapmenu-1102,
  26.2, 15 of 15 PASS lines in the client log at 20:00, still hung at 20:21). It happened on 2 of 3 runs of that filter on 26.2 (none of 3 on 26.3); the run after a killed one in the same checkout then FROZE at startup twice, and a fresh rerun passed. jstack it, kill only the client PID; the PASS lines and build/testkit-report/cases/*.log are the evidence.

- `run-scenario.ps1 ... *>&1 | Out-File log` holds only the script's own lines: gradle and the client are started with
  `Start-Process -NoNewWindow`, so their output (and a gametest COMPILE ERROR) goes to the calling console, not the log; a
  compile failure reads as "Run finished with exit code 1" and nothing else (termismss-1102). Compile first with
  `./gradlew compileGametestJava -PmodUnderTest=<jar>`, and read `build/testkit-report/summary.md` for results. From Git Bash,
  pass Windows paths (`cygpath -w`) to `Out-File` and `-ModUnderTest`: a `/c/...` path makes Out-File fail silently.
- Termism (`termism.TermismPracticeScreen`) is a plain Screen, not a container: Hx sees none of its clicks. Its cases
  (1102-menu-termism-ss-*) read the screen's own fields (`solved`, `cachedHighlights`, `probe*`) and FakeCursor's probes.
- The default 854x480 test window at GUI scale 1 lays the mod's own screens out at Auto Scale factor 1, so a bug that
  mixes canvas units with GUI units cannot show there: the termism-ss cases passed on the broken first fix. Any check
  of a mod screen's coordinates needs a scaled pass too (`AuctionHouseCases.setWindow(c, 1920, 1080, 2)` + Auto Scale on
  = factor 1.125), asserting the factor really is not 1 (termismss-1102, `TermismStreamSafeCases.scaledPass`).
- A summoned entity's scoreboard `Tags` never reach the client: `Entity.getTags()` does not even exist on the client in
  26.3's mapping (compile error, commissions-1103). Find a test's mob on the client by its custom name or position; keep
  the tag for the server's `kill @e[tag=...]` cleanup.
- SimBossTests / SimQuizCooldownTests cases (1102-boss-maxorcrys, 1017-sim-quiz-timing) named alone report 0 passed and a vacuous pass; run them as `-Scenario "500-boss-session:,1102-boss-maxorcrys,1017-sim-quiz-timing"` and read the [STDOUT] PASS line in build/run/clientGameTest/logs/latest.log (timerlag-1103, 2026-11-03). 1103-logic-timer-lag drives PerTickServerClock by hand (a live `/tick rate` slows the client's ticks too, so it cannot make the client run ahead of the server).
- Opening a real chest from a MenuSuite case (1104-invsort-dump-*): `mc.gameMode.useItemOn` with no look flags RotationPlace (and a server `tp ... facing` did not move the client look). Set the client yaw/pitch toward the block (InvWalkCampaignTests.look), wait 3 ticks, then `getInput().pressKey(o -> o.keyUse)`.

- Map cases that read Predictive Rooms (1101-map-predicted-gray, so 1101-map-prerun as a whole) must clear `SimRoomState` first: it is
  keyed by room NAME and earlier cases of the suite leave rooms "cleared", so a floor whose first room shares a name (Museum, Mines...)
  draws no prediction, about every other run (f3f841a6, merged into prerun-1104 2026-11-04; it had sat unmerged on flaky-1104 while
  handsizeA4/sizeA gates failed on it). A case also waits for `SimRun.hasStarted()` and `HeldMap.emulated()` (no held map before GO).
- 75-sim-map-editor-filters failed once in a 4-client run and passed solo (prerun-1104): the designer's Generate is random with 12 whole-
  floor tries and a 400 ms retry budget; a 1x1-only filter is a hard layout (most tries miss cells) and under load the wall-clock budget
  stopped it at 11 floors, so the plan fell back with "filters too strict" and 3 checks failed. The mod's budget now counts thread CPU
  time (km 195e48f88) and the case presses Generate again (up to 5) when a feasible set falls back (`generateFeasible`). The log line
  to look for: "Sim floor: stopped after N whole floor(s), M ms". A case asserting a generator outcome needs this kind of retry.
- A test that demands "two different random outcomes in N runs" is flaky on a small board: Rubix has five panes and four ON runs repeated one click order by chance (1027-menu-term-human-rubix, 26.3, termfix-1104). TermHumanCases now plays extra ON runs (up to 12) until two orders differ. And the Termism fake cursor shows only while Auto Terminals drives the board (Melody idles between rows), so a pixel check that samples three fixed moments can see no cursor (1102-menu-termism-ss-melody, 26.2 and 26.3): `TermismStreamSafeCases.shot` waits, bounded, for `FakeCursor.active` first. From Bash, a `sed` replacement containing `\U` upper-cases the rest; write PowerShell run scripts with Write/Edit.
- The testkit checkout's `master` lags the ship branches (2026-11-04: it lacked b674aadb's Mining groups, so 511 failed "a folder inside a category: Mining > Crystal Hollows" on a correct mod). Cut a testkit worktree from the newest `ship-*` branch (`git for-each-ref --sort=-committerdate refs/heads`), not from master.
- A full `-ui-` run needs `-TimeoutSeconds 5400`: run-scenario.ps1's default 240 s deadline ends it after ~16 cases ("TIMED OUT after 240 s"), and the suite is ~560 cases / 48 min on one client with no hang (uihang-1104, 2026-11-05; 385 pass, 2 real fails fixed: 704 called the removed PetWheelScreen.pageCount, 1060 two card blurbs the menu cut). `1101-map-seethrough` is selected by the filter `1101-map-prerun` (its own name runs nothing); 6/6 pass in 7-16 s on a quiet PC, the freeze-watcher kill came from the ~33 s loaded run: set TESTKIT_HUNG_SECONDS=120 on a loaded PC rather than loosen the watcher.
- Port clash (portfix-1105): a checkout copied WITH run/testserver/server.properties but no port-auto.txt/port-pinned.txt kept that port unchecked, so tk-fairygold-x/powdergrind/timerlag shared 26790 with tk-ship4-x and tk-pvstyle/iw1027/menufix2/nmflake 26560 with tk-ship4-1104-m1; every Blink case failed "server exited with code 0 before it was ready". Fix: build.gradle keeps every checkout's port in `killer560s-mod-logs/testkit-ports.txt` (`port<TAB>path`, written under `testkit-ports.lock`; paths that no longer exist drop out). A carried-over port is kept only if free now (or already ours) and not owned by another living checkout; otherwise a new one is picked from the path-hash order, skipping registry ports. Two checkouts never hold one port even when no server runs. After copying a checkout nothing else is needed; delete a checkout and its port frees on the next run anywhere.
- Case leaks (ship4fu-1105): a case that runs `gamemode creative @p` must restore survival in its `finally` (HxCrystalWalls1104Cases did not): `1103-mining-hold-resume` ran after `1104-chwalls-edges` in one session, the obsidian broke instantly in creative and the case read "not digging". A case that aims with a fixed pitch depends on where the previous case left the player (a whole-number x hit the WEST face at pitch 18, `1103-mining-hold-direction`): aim with `player.lookAt(EYES, face centre)`. A breaker/dig case that calls `startDestroyBlock` directly draws experimental NoSwingBreak and RotationBreak (no swing, look off the block): aim first, then `McCompat.swingArm` (26.3 `swing` takes three arguments, `InteractionHand.MAIN_HAND` alone does not compile there). `1026-blink-` on 26.2 needs about 45 min alone: give it -Timeout 6000, not 2400.

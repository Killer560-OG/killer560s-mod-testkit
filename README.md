# killer560s-mod Anticheat Test Kit

Runs a Fabric client mod - **killer560s-mod**, your own mod, or none at all - against a real anticheat (GrimAC) on a
real dedicated server, with real Minecraft clients driven by Fabric's client gametest API, and reports what the
client actually sends in the units a server-side check is built from.

Forked from **[SicoKaleb/Automative](https://github.com/SicoKaleb/Automative)** (CC0), which is where the
harness itself comes from - the test server launcher, the map builder, the flag reader, and the positive
control that refuses to call a run clean until it has proved the anticheat can still see a violation. That
last part is why any of this is trustworthy, and it is his design.

## Quick start on a new machine

Windows 10/11 only (the scripts are Windows PowerShell 5.1 and place windows with Win32). Everything below is run
from the testkit folder in Windows PowerShell; prefix a script with `powershell -ExecutionPolicy Bypass -File` if the
execution policy refuses it. No configuration file is needed for any of it.

1. **Prerequisites**: Git, and a **JDK 25 or newer on `JAVA_HOME`** (`winget install EclipseAdoptium.Temurin.25.JDK`,
   then set `JAVA_HOME` to its folder and open a new terminal). About 5 GB free disk.
2. **Clone the testkit** (any folder; the mod and the jars go beside it):
   ```
   git clone https://github.com/Killer560-OG/killer560s-mod-testkit.git
   cd killer560s-mod-testkit
   ```
3. **Check the machine**: `.\doctor.ps1`. It prints every check and, for each `PROBLEM`, the exact fix (JDK, git,
   busy ports, the mod jar, monitors). Fix those and run it again; `WARNING` lines do not block a run.
4. **Get a mod to test**. For killer560s-mod: `.\get-mod.ps1` - clones `github.com/Killer560-OG/killer560s-mod` into
   `..\killer560s-mod` and builds its 26.1.2 cheat and legit jars into `..\<testkit folder>-jars\<branch>-<commit>\`
   (a few minutes; `-Minecraft both` adds 26.2). The scripts then default to the newest jar there. For your own mod,
   skip this and pass `-ModUnderTest <your.jar>` (see [Testing your own mod](#testing-your-own-mod)); for no mod, `-NoMod`.
5. **Warm the build once** (downloads Minecraft, Fabric and GrimAC, stages the server; several minutes the first
   time, and the run scripts' deadline would otherwise count it): `.\gradlew.bat compileGametestJava writeTestServerLaunch`
6. **Prove the harness**: `.\run-scenario.ps1 -Scenario smoke`. Expect `[00-smoke] harness OK - anticheat is live and
   detecting`, `anticheat clean` and `Run finished with exit code 0` (about a minute). Smoke cheats on purpose and
   **fails if the anticheat stays quiet** - a missing anticheat does not error, it looks exactly like a clean run.
7. **Read the report**: `build\testkit-report\summary.md` - one row per scenario (PASS, FAIL, FLAGGED, SKIP with its
   reason, RAN), and `cases\<name>.log` with the server console and client log of each.
8. Then a suite: `.\run-suite.ps1 -Suite generic` (works with any mod), `.\run-scenario.ps1 -Scenario -ui-` (the UI
   group, killer560s-mod only), `.\run-scenario.ps1 -Scenario 98-sim-insta-clear` (a dungeon-sim scenario - it SKIPs
   with its reason unless room captures are configured, see Configuration).

### What you will see

Every run starts **a real Minecraft window** and a dedicated server process. The clients are **muted** (every sound
category is written to 0 before each run; never pass `-PtestVolume` unless someone asked to hear one). Windows are
placed borderless on the configured screen (`windowScreen`: by default the first monitor that is not your primary one,
else your primary one) - one run fills a quadrant, four concurrent runs get a quadrant each, more get a finer grid.
Do not click into them while a run is going. A client that stops responding for ~16 s, or a run past its deadline, is
killed automatically, and only clients whose command line contains this checkout's path are ever touched.

### Suites and how long they take

Named filters live in [`suites.properties`](suites.properties); `-Scenario a,b` selects every scenario whose name
contains `a` or `b`. Times are one client on the maintainer's machine, build already warm.

| suite / filter | what | mod | time |
| --- | --- | --- | --- |
| `harness` | smoke, the positive control, saving and loading a seeded world | any / none | ~2 min |
| `generic` | anticheat sweeps (movement, combat, placement, speed), the join-fingerprint leak check (reads the mod id from your jar), hostile chat, entity reach | any / none | ~15 min |
| `k560` | everything that drives killer560s-mod (sim, Auto Routes, AP3, solvers, UI, Hx, menus, logic, boss) | killer560smod | hours; shard it |
| `ui` (`-ui-`) | the mod's screens, tabs, HUD editor in one singleplayer world | killer560smod | ~6 min |
| `hx`, `menu`, `boss` | Hypixel-shaped server fakes driving the mod's features | killer560smod | 3-10 min each |
| `logic` | pattern catalog, fixtures, pure logic (source checks SKIP without a mod checkout) | killer560smod | ~3 min |
| `solve` | the 11 auto-puzzle rooms in the sim (needs room captures) | killer560smod | ~15 min |

Without killer560smod loaded, every killer560smod scenario is a `SKIP` row reading `needs killer560smod`, never a
failure. Sharding a long filter over several clients: `.\run-sharded.ps1 -Suite k560 -Shards 3` (see below).

## Configuration

Nothing has to be configured. Defaults are relative to the testkit checkout, and anything optional that is missing
makes the scenarios that need it SKIP with the reason. To change a value, create **`testkit.local.properties`**
(gitignored, same keys as the committed [`testkit.properties`](testkit.properties), which documents each one), or set
the environment variable. `.\doctor.ps1` prints every resolved value and where it came from.

| key | env | default | used for |
| --- | --- | --- | --- |
| `jarsDir` | `TESTKIT_JARS_DIR` | `..\<checkout>-jars` | snapshot folders of mod jars; newest folder wins |
| `modJarPattern` | `TESTKIT_MOD_JAR_PATTERN` | `killer560smod-*-{mc}-cheat.jar` | which jar in a snapshot is the default |
| `modSource` | `TESTKIT_MOD_SOURCE` | `..\killer560s-mod` | get-mod.ps1's clone; the logic suite's source checks |
| `modRepo` | `TESTKIT_MOD_REPO` | the GitHub repo | where get-mod.ps1 clones from |
| `prismInstances` | `TESTKIT_PRISM_INSTANCES` | none | a PrismLauncher `instances` folder with killer560s-mod room captures (sim scenarios) |
| `roomsInstance` / `simInstance` | `TESTKIT_ROOMS_INSTANCE` / `TESTKIT_SIM_INSTANCE` | `26.1.2 (Mod Only Test)` / `Map Logger` | which instance's captures |
| `windowScreen` | `TESTKIT_WINDOW_SCREEN` | `auto` | `auto`, `off`, or `x,y,w,h` |
| `windowSlotsDir` | `TESTKIT_WINDOW_SLOTS_DIR` | `<jarsDir>\.window-slots` | screen slots shared by concurrent runs |
| `shardsDir` / `worktreesDir` | `TESTKIT_SHARDS_DIR` / `TESTKIT_WORKTREES_DIR` | `..\<checkout>-shards` / `-wt` | worktrees of run-sharded / parallel-suite |

A git worktree of the testkit also reads its main checkout's `testkit.local.properties`, so shard worktrees behave like
the checkout that made them. Ports: a run uses its server port P (default 25565, `-Port P` to change), the Hx bridge on
P+5 and an HTTP fake on P+6; `doctor.ps1` lists what is already listening.

## Testing your own mod

The harness knows nothing about any particular mod. Point it at your jar:

```
.\run-scenario.ps1 -Scenario smoke -ModUnderTest C:\path\to\yourmod-1.0.jar
.\run-suite.ps1 -Suite generic -ModUnderTest C:\path\to\yourmod-1.0.jar
```

The jar is loaded through Fabric Loader's `fabric.addMods`, so what runs is your shipped artifact. The Minecraft
version (26.1.2 or 26.2) is read from the jar's `fabric.mod.json` `depends.minecraft` unless you pass `-Minecraft`.
Its other dependencies must be satisfiable: Fabric API and loader are present; anything else (a Kotlin adapter, a
config library) goes in the same comma list, `-ModUnderTest a.jar,b.jar`. To make your jar the default, put it in
`<jarsDir>\<any folder>\` and set `modJarPattern=yourmod-*.jar` in `testkit.local.properties`.

**Which suites apply**: `harness` and `generic`. `65-join-fingerprint` searches every serverbound byte for your mod
id and probes your own `en_us.json` keys through a sign the way a probing server would; it compares the join against
the committed no-mod baseline, so a mod that registers a network channel or ships resolvable lang keys is reported as
detectable - that is a finding about your mod, not a harness fault. Every `k560` scenario SKIPs.

**Writing a scenario for your mod**, in `src/gametest/java/dev/testkit/gametest/`:

```java
package dev.testkit.gametest;

import dev.testkit.harness.RequiresMod;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

@RequiresMod("yourmod")                    // SKIP rows, not failures, when yourmod is not loaded
public class YourModTests implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        Scenario.run(ctx, "700-yourmod-bridge",
            (server, s) -> TestMap.on(server).platform(-700, 150, 0, 12).catchFloor(140)
                    .survival().give("white_wool", 64).spawn(-699.5, 0.5, -90f).build(),
            (server, s) -> {
                // Reach your mod by reflection: the scenarios compile without it.
                ctx.runOnClient(mc -> ModUnderTest.staticCall("com.example.yourmod.Modules", "enableBridge"));
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.waitTicks(100);
                ctx.getInput().releaseKey(o -> o.keyUp);
                // Prove it acted before the clean verdict means anything:
                double moved = ctx.computeOnClient(mc -> mc.player.position().x) + 699.5;
                if (moved < 5) throw new AssertionError("the bridge never moved the player: " + moved);
                s.log("travelled " + moved);
            });                                // the anticheat must stay silent, or this fails with its lines
    }
}
```

Register the class in [`src/gametest/resources/fabric.mod.json`](src/gametest/resources/fabric.mod.json) under
`fabric-client-gametest`, add a suite line to `suites.properties` if you want one (`yourmod=700-yourmod`), and run
`.\run-scenario.ps1 -Scenario 700-yourmod -ModUnderTest <jar>`. Give every scenario its own coordinates and name
(`NN-slug`), and read [AGENTS.md](AGENTS.md) before trusting a green run. If you would rather compile against your mod
than use reflection, add it as a `gametestImplementation` dependency in `build.gradle`.

---

## What this fork adds

- **`-PmodUnderTest=<jar>`** loads any mod into the gametest client through Fabric Loader's own
  `fabric.addMods`. The client gametest API rebuilds its game directory every run, so a jar dropped in
  `run/.../mods` is deleted before loader scans it; `addMods` takes absolute paths, and loader's
  `RuntimeModRemapper` maps a production jar from intermediary to named on the way in - so what gets tested
  is the shipped artifact, not a dev-classpath rebuild of it.
- **`PacketWatch`** taps `ClientCommonPacketListenerImpl#send` and counts, per client tick: block breaks and
  block uses, the most on any one tick, how many went out *after* that tick's movement packet, swings,
  hotbar swaps, reach to the block box *and* to its centre, rotation sent, and horizontal collisions as the
  client itself reports them.
- **`-Pport=N`** gives a separate server, world and port (written into `run/testserver/server.properties`), so
  several copies of this project can run scenarios at the same time. The Hx bridge listens on N+5.
- **Hx: the test server impersonating Hypixel.** A loopback JSON-RPC bridge in the companion server mod
  (`src/testmod/.../hx/`) sends Hypixel-shaped chat, action bars, titles, sidebars (team prefixes on invisible
  owners), tab lists (fake profiles), items, armour stands, menus and command stubs, and records what the client
  sent back (commands, chat, clicks, interactions). Client side: `gametest/hx/Hx`.
- **`hx/Session`**: one server start for many cases, each with its own verdict, anticheat check, health check
  (`mod/Mod.assertHealthy`) and report row.
- **Reports** in `build/testkit-report/`: `summary.md`, `summary.json`, `cases/<name>.log` (server console and client
  log slice per case), `screens/`, `coverage.md`.
- **Suites and worktrees**: `-Psuite=<name>` from `suites.properties`; `run-suite.ps1 -Suite <name> -Port <n>`;
  `parallel-suite.ps1 -Suites a,b -Max 2` runs each in a git worktree at
  `<worktreesDir>/<n>` on port 25700+10n.
- **Sharding one run**: `run-sharded.ps1 -Scenario "smoke,-ui-,93-solve,96-ar" -Shards 3 [-Minecraft 26.1.2|26.2|both]`
  splits ONE filter across N clients, each with its own dedicated server and Hx port in its own worktree
  (`<shardsDir>/<mc>-<k>`, ports `-BasePort` 25900 + 10g, Hx +5). A list-mode client
  start (`-PlistScenarios=<file>`, no server) asks the harness which names the filter selects and which belong to one
  unit (a Session and its cases, the UI group sharing one world, the 96-ar cases sharing one room - never split), the
  units are balanced by each name's last measured seconds (`durations.json` beside the worktrees), and every shard's
  report and logs plus one merged `summary.md` (table per version: status and shard of every name, wall time vs the
  sum of shard times) land in `build/sharded-<timestamp>/`. `-Max` caps clients at once (3). Exit code 0 only if every
  shard exited 0 and no selected name is missing from the reports.
- **Safe by default**: every run points the mod's Prism account store at an empty fixture and passes
  `killer560.net.offline=true` / `killer560.test.noExternalOpen=true` (opt out with `-PnetOnline`,
  `-PallowExternalOpen`, `-PnoQuiet`); `-PnetOverride=svc=url;...` points mod services at local fakes;
  `-PseedConfig=<dir>` seeds the client's config folder.
- **`Scenario.labelServerAs(...)`** presents the local server under a different address. killer560s-mod
  refuses to run its dungeon features unless `getCurrentServer().ip` contains `hypixel.net` or `p3sim.net`,
  and several have no override at all; that check reads the stored `ServerData`, which is separate from the
  address actually dialled. It changes nothing the anticheat sees.
- **`ModUnderTest`** drives the mod by reflection on its real public API - the same methods its own menus and
  commands call - because the scenarios compile without it. Nothing here can exercise a path the shipped mod
  does not have.

## Findings so far

**Packet ordering (fixed in killer560s-mod `825f319`).** A vanilla client decides what to dig before it
reports where it is: the dig goes out earlier in `Minecraft#tick` than the player's own movement packet.
Fabric's `END_CLIENT_TICK` runs *after* that packet. Breaker Aura ticked there and drew **808
`Post - player digging` violations in one run, one per break, at its default rate of one block a tick**. The
identical run on `START_CLIENT_TICK`: zero. A by-hand control in the same arena was clean both times.

**The slow setting is what desyncs you.** At Speed V on shipped defaults the player collided with the wall on
17 of 219 movement packets and the anticheat raised 17 movement violations - the same 17. With Multi Break on
it collided on 0 of 210 and crossed solid wall at exactly its open-ground speed.

## What a clean run does and does not mean

GrimAC is not Hypixel's Watchdog. A clean run means the traffic is not obviously impossible and survives a
well-known movement and interaction model. It is a useful lower bound, not a safety certificate. The
transferable part of any result here is the numbers, not the verdict.

---


Your gametests already prove a module does what you expected. They cannot tell you whether the server
objects, because an integrated test server has no opinion about whether a rotation was reachable or a
placement legal. This kit runs each scenario against a real dedicated server with **GrimAC** on it, reads
the anticheat's verbose output, and **fails the build on any flag**.

```
[11-example-bridge] connected as Player0
[11-example-bridge] travelled 39.4 blocks, ended at y=151.00
[11-example-bridge] anticheat clean — no verbose lines
```

Built for Minecraft **26.1.2** / Fabric. Requires **Java 25** and a JDK on `JAVA_HOME`.

**Minecraft 26.2** builds with `-Pminecraft_version=26.2` (Fabric API, loader and cloud-fabric follow from the
table in `build.gradle`); the scripts take `-Minecraft 26.2`. The server, GrimAC (it detects) and the
`smoke,proof,02-seed` and UI-group scenarios run and pass there; the rest are not yet run on 26.2. API that differs between the two versions lives in `dev.testkit.compat`
(`src/client/mc26_1/java`, `src/client/mc26_2/java`, identical public signatures).

---

## Running without the scripts

```
./gradlew runClientGameTest -Pscenario=smoke -PmodUnderTest=<jar>   # no freeze watcher, no window slots
./gradlew runClientGameTest -PmodUnderTest=<jar>                  # everything
./gradlew grimServer                                               # the same server, left running to join by hand
```

---

## Writing a scenario

```java
public class MyTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        Scenario.run(ctx, "20-my-thing",
            // 1. the map
            (server, scenario) -> TestMap.on(server)
                    .platform(0, 150, 0, 24)      // 49x49 pad, players stand at y=151
                    .walls(4)                     // barrier walls so nothing walks off
                    .catchFloor(140)              // survivable landing
                    .bridgeGap(3, 60)             // void past x=3
                    .survival()
                    .clearInventory()
                    .give("white_wool", 64)
                    .spawn(-3.5, 0.5, -90f)       // stand here, facing west
                    .build(),

            // 2. what happens
            (server, scenario) -> {
                ctx.getInput().holdKey(options -> options.keyUp);
                ctx.waitTicks(200);
                ctx.getInput().releaseKey(options -> options.keyUp);
                scenario.log("done");
            });
        // the anticheat's verdict is asserted for you
    }
}
```

Register the class in [`src/gametest/resources/fabric.mod.json`](src/gametest/resources/fabric.mod.json)
under `fabric-client-gametest`, then `./gradlew runClientGameTest -Pscenario=my-thing`.

### Examples to read

Every one of these runs as-is. Copy the closest and change the body.

| file | scenarios |
| --- | --- |
| [`ExampleTest`](src/gametest/java/dev/testkit/gametest/ExampleTest.java) | walk, bridge, duel — start here |
| [`MovementExamples`](src/gametest/java/dev/testkit/gametest/MovementExamples.java) | sprint-jump with a speed assertion, long fall, obstacle course, ice/soul-sand/slabs, a per-second sampled run |
| [`CombatExamples`](src/gametest/java/dev/testkit/gametest/CombatExamples.java) | knockback with a displacement assertion, frozen dummy, waypoint patrol, a crowd of four for target selection, an A/B armour comparison |
| [`PlacementExamples`](src/gametest/java/dev/testkit/gametest/PlacementExamples.java) | straight gap, diagonal gap, rising gap, tower, breaking a wall |
| [`SmokeTest`](src/gametest/java/dev/testkit/gametest/SmokeTest.java) / [`ProofTest`](src/gametest/java/dev/testkit/gametest/ProofTest.java) | the harness testing itself |

Read them roughly in that order — each adds one idea, and the comments say which.

### Maps

`TestMap` builds terrain through the companion server mod, which loads chunks as it goes. That matters:
vanilla `/fill` silently does nothing in unloaded chunks — it says "No blocks were filled" and carries on
— so a hand-rolled map can leave your player falling through a world that was never built.

| method | what it does |
| --- | --- |
| `platform(cx, y, cz, radius)` | square pad; sets the surface height for everything after it |
| `floor(x1, y, z1, x2, z2)` | rectangular slab |
| `walls(height)` | barrier walls around the last platform |
| `catchFloor(y)` | wide floor far below, so a fall is survivable |
| `bridgeGap(fromX, toX)` | carve void past `fromX` |
| `diagonalGap(fromX, fromZ, to)` | carve a diagonal void |
| `stairs(fromX, y, z, steps, width)` | staircase climbing in +X |
| `pillar(x, y, z, height)` / `wall(...)` | obstacles |
| `fill(x1,y1,z1,x2,y2,z2,block)` | anything else |
| `spawn(x, z, yaw)` | where the player starts — applied **after** the build |
| `survival()` / `creative()` / `give(...)` / `clearInventory()` | player setup |
| `command(...)` | any command the builder doesn't cover |

### Opponents

`TestEnemy` spawns a **real `ServerPlayer`**, not a named mob. That matters more than it sounds: modules
pick targets by entity type, so an aura filtering for players cannot see a zombie at all, and player
hitboxes, eye heights, reach and knockback all resolve differently.

```java
TestEnemy bot = TestEnemy.named("Velocity", "1dps")
        .at(4.5, 151, 0.5)
        .health(20)
        .size(1.0)                   // vanilla scale attribute — real hitbox, not just the model
        .frozen(true)                // immovable, still damageable, respawns at its anchor when killed
        .heldItem("minecraft:stick")
        .armor("minecraft:iron_helmet", null, null, null)
        .shield(true)
        .walking(90f, true, false)   // direction, sprint, constant jump
        .route(true, new double[]{0,151,0}, new double[]{10,151,10})   // patrol, repeating
        .chasing(true)               // follow the client
        .attacking(3.5f, 20, 2.0f)   // reach, ticks between hits, damage
        .spawn(ctx, server);

bot.drive(200);                      // let it act
bot.hits();                          // times it hit the client
bot.deaths();                        // times it was killed and put back
bot.remove();
```

Bots are named `Bot<Module><detail>` so a verbose line, a console log and a scenario can be tied together
afterwards. **Minecraft caps player names at 16 characters**, and a longer one is not refused politely —
the server throws while encoding `player_info_update` and kicks every real client — so the name is
abbreviated automatically.

### Driving the server directly

Anything the builders don't cover:

```java
server.command("effect give @p minecraft:jump_boost 60 3");
server.command("testkit report");        // dump every entity and where it is relative to you
server.command("testkit sweep");         // remove mobs and leftover bots
server.command("testkit arena 24");      // barrier-walled platform at y=150
```

---

## What to know before you trust a result

Six things here fail **silently** — they produce a run that passes. Each cost a debugging session to
find, and they are why the positive control exists.

1. **The anticheat exempts operators.** Never op the test player. Verbose goes to the server console
   instead, which needs no permissions. An opped client produces a spotless run for the worst reason.
2. **GrimAC cannot run in the client's JVM.** A client gametest is `EnvType.CLIENT`, and there the
   PacketEvents build it bundles installs client hooks that are incomplete on 26.1.2 — the client's own
   connection dies with *Invalid player data*. The server is a separate process for this reason; do not
   "simplify" it back to an in-process one.
3. **A dead server can look ready.** Signalling readiness when the process output closes makes a crashed
   server report itself started, and the client then fails with a bare *connection refused*.
4. **`/fill` skips unloaded chunks.** Use `TestMap`, which goes through the companion mod.
5. **Every scenario gets a fresh world.** The server deletes its world folder before each run — otherwise
   each scenario runs on the leftovers of the last, and a module gets measured against someone else's
   arena.
6. **No anticheat and no flags look identical.** Hence `scenario.assertDetectorWorks()`.

**A clean run is a statement about GrimAC only.** Server-side behavioural detection (Hypixel's Watchdog
and similar) leaves no verbose, bans on a delay, and catches input-less automation with regular timing
that Grim allows. Passing here is necessary, not sufficient — say which one your result covers.

---

## Artifacts

| file | what it is |
| --- | --- |
| `build/testserver-console.log` | the whole server console, including every verbose line |
| `build/testserver-launch.properties` | how the server was launched |
| `run/testserver/` | the staged server: mods, config, world |

---

## Updating the anticheat

Pinned in [`gradle.properties`](gradle.properties) as `grim_version` / `grim_url`. GrimAC's Fabric jar
does not bundle everything it needs, and the build supplies the rest:

- **Cloud** (`org.incendo:cloud-fabric`) — its command framework. Version matters: `2.0.0-beta.17`
  declares `minecraft >=26.2` and will not load on 26.1.x; **`beta.16`** declares `>=26.1` and does. A 26.2
  build uses beta.17, and `prepareTestServer` removes the other version's cloud jars from the staged server.
- **SQLite** (`org.xerial:sqlite-jdbc`) — its violation store. Without it Grim refuses to start.

After changing the version, run `./gradlew prepareTestServer` and then the smoke scenario. If the new
build changes its config layout or startup requirements, the positive control is what tells you.

---

## Licence

MIT. GrimAC is downloaded at build time and is not redistributed here; it is licensed separately
(GPL-3.0) by its authors.

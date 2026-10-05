# killer560s-mod Anticheat Test Kit

Runs **killer560s-mod** against a real anticheat (GrimAC) on a real dedicated server, and reports what its
automation actually sends in the units a server-side check is built from.

Forked from **[SicoKaleb/Automative](https://github.com/SicoKaleb/Automative)** (CC0), which is where the
harness itself comes from - the test server launcher, the map builder, the flag reader, and the positive
control that refuses to call a run clean until it has proved the anticheat can still see a violation. That
last part is why any of this is trustworthy, and it is his design.

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
  `C:/Users/Hunter/killer560s-mod-testkit-wt/<n>` on port 25700+10n.
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

---

## Quick start

```bash
./gradlew runClientGameTest -Pscenario=smoke   # prove the harness works, first
./gradlew runClientGameTest                    # run everything
./gradlew runTestServer                        # the same server, to join by hand
```

The smoke scenario deliberately cheats and **fails if the anticheat stays quiet**. Run it first on any
new machine: a missing anticheat does not error, it produces a run with no flags, which looks exactly
like a clean one.

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

## Using it on your own mod

The kit is standalone. To test *your* mod, add it as a dependency of the gametest source set and
enable your modules from a scenario body:

```java
ctx.runOnClient(mc -> MyMod.MODULES.get(MyModule.class).setEnabled(true));
```

Nothing in the harness knows about any particular mod, so there is no integration to write beyond that.

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
  declares `minecraft >=26.2` and will not load on 26.1.x; **`beta.16`** declares `>=26.1` and does.
- **SQLite** (`org.xerial:sqlite-jdbc`) — its violation store. Without it Grim refuses to start.

After changing the version, run `./gradlew prepareTestServer` and then the smoke scenario. If the new
build changes its config layout or startup requirements, the positive control is what tells you.

---

## Licence

MIT. GrimAC is downloaded at build time and is not redistributed here; it is licensed separately
(GPL-3.0) by its authors.

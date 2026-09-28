# killer560s-mod-testkit

Runs killer560s-mod against a real anticheat (GrimAC) on a real dedicated server, and reports what its
automation actually sends, per client tick, in the units a server-side check is built from.

Forked from [SicoKaleb/Automative](https://github.com/SicoKaleb/Automative) (CC0). The harness is his: the
test server launcher, the arena builder, the flag reader, and the positive control that refuses to call a
run clean until it has proved the anticheat can still see a violation. Local git only, no remote — do not
add one or push without asking.

## Run

```
./gradlew runClientGameTest -PmodUnderTest=C:/Users/Hunter/killer560s-mod/build/libs/killer560smod-1.1.0-cheat.jar --no-daemon
./gradlew runClientGameTest -Pscenario=60-secret -PmodUnderTest=<jar>   # one scenario
./gradlew runClientGameTest -Pnogrim -PmodUnderTest=<jar>               # anticheat muted
./gradlew runClientGameTest -Pport=25566 -PmodUnderTest=<jar>           # second concurrent instance
```

About 70 seconds per scenario. `-Pport` gives a separate server, world and port, so copies of this whole
directory can run at the same time (Gradle locks the project directory, so concurrency needs separate
checkouts, not just separate ports).

Versions from `gradle.properties`: Minecraft 26.1.2, GrimAC pinned to `2.3.74-2614909` downloaded from
Modrinth into `.gradle/grim/`, `cloud_version=2.0.0-beta.16` (beta.17 needs MC ≥ 26.2), sqlite-jdbc for
Grim's violation store. Java 25.

## Layout

`src/gametest/java/dev/testkit/gametest/` holds the scenarios. `Scenario` runs one against the server and
asserts the anticheat said nothing; `Scenario.runExpectingFlags` records what it said instead, which is what
a sweep wants. `TestMap` builds the arena. `TestServer` launches the dedicated server. `ModUnderTest`
reaches into the mod by reflection. `src/client/java/dev/testkit/harness/PacketWatch` counts outbound
packets per tick, fed by mixins on `ClientCommonPacketListenerImpl#send` and `Minecraft#tick`.

Scenarios so far: 48-52 Breaker Aura (with a by-hand control and an open-ground speed control), 60 Secret
Triggerbot.

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
- Features that click a dungeon secret keep a done-set and never click the same one twice, so one lever
  measures exactly one interaction. Use a row of them and strafe past, rather than writing yaw — a synthetic
  rotation from the harness would land in the packets being measured.
- Upstream's `build.gradle` contains two deliberate NUL bytes (a NUL separator for packed launch args), so
  `grep` calls it binary. That is not corruption.
- Upstream scenarios 40, 41, 42 report "built 0 block(s)", fall to the catch floor and pass as clean, and 34
  reports "naked 0, diamond-armoured 0" — four tests that go green while proving nothing. Worth telling
  SicoKaleb; his movement example guards against it with a `travelled < 20` check.

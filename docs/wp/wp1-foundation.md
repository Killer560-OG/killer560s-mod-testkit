# WP1 - Foundation (done 2026-10-04)

Everything WP2-WP7 build on. The plan is `C:/Users/Hunter/killer560s-mod-logs/TESTKIT-COVERAGE-PLAN.md`.

## Frozen files

After WP1 these change only through a request in `docs/requests/<wp>.md` (the integrator applies it on master):
build.gradle, gradle.properties, settings.gradle, suites.properties, both fabric.mod.json, Scenario, TestServer,
TestMap, ModUnderTest, SuiteVerdict, Report, TestKitServer, HxBridge, HxPrimitives, HxModules, CLAUDE.md, AGENTS.md,
README.md. Also treat Hx.java, Session.java, Mod.java, Quiet.java, Fixtures.java, Coverage.java and
`testkit-fixtures/SCHEMA.md` as frozen: every WP depends on them.

## Running

```
./run-suite.ps1 -Suite <name> -Port <port> [-Window x,y,w,h|off] [-ModUnderTest <jar>] [-Extra "-Pnogrim",...]
./gradlew runClientGameTest -Psuite=<name> -Pport=<port> -PmodUnderTest=<jar> --no-daemon     # same, no watcher
./parallel-suite.ps1 -Suites a,b -Max 2 [-BasePort 25700]        # worktrees C:/Users/Hunter/killer560s-mod-testkit-wt/<n>
```

Snapshotted jars: `C:/Users/Hunter/killer560s-mod-testkit-jars/main-8c43a6d/` (mod HEAD 8c43a6d with the test hooks):
`killer560smod-1.1.0-26.1.2-cheat.jar` (BuildVariant md5 c7d5d8f5, cheat+dev) and `...-26.1.2-legit.jar` (1d821df5).
Use them, never the mod's build/libs, which moves under you.

Gradle properties (all optional): `-Pport=N` (server N, Hx bridge N+5, sticks in run/testserver/server.properties),
`-Psuite=<name>` (exclusive with -Pscenario/-Pfailed), `-PseedConfig=<dir>` (copied into the client's config/ after
the wipe), `-PnetOnline` / `-PallowExternalOpen` / `-PnoQuiet` (opt-outs, see below),
`-PnetOverride=svc=url;svc2=url` (`-Dkiller560.net.<svc>`; keys in the mod's docs/TESTING-HOOKS.md).

Always on: `-Dprismaccountswitcher.accountsFile=build/testkit-fixtures/prism-accounts.json` (an empty copy of
`testkit-fixtures/prism-accounts-empty.json`), `-Dkiller560.net.offline=true` and `-Dkiller560.test.noExternalOpen=true`
unless opted out, `-Dtestkit.quiet=true` (Session applies `Quiet`), `-Dtestkit.checkout`, `-Dtestkit.hx.port`,
`-Dtestkit.reportDir`. The GrimAC jar is cached once in the Gradle user home (`caches/testkit-grim/`).

## Server side: the Hx bridge (`src/testmod/java/dev/testkit/server/hx/`)

`HxBridge` listens on 127.0.0.1:(port+5), JSON per line, every op on the server thread; prints
`[hx] bridge listening on 127.0.0.1:N with K op(s)`. Core ops are in `HxPrimitives` (its javadoc is the reference):
ping, ops, players, events.poll/head, chat {text|textJson, overlay}, actionbar.packet, title, sidebar.set/clear,
tab.set/clear, sound, particle, give, stand, entity.remove, menu.open, kick, cmd.stub. `HxEvents` records player chat,
every player command (`[hx] cmd /...` on the console too, via a mixin on `Commands.performCommand`), container
clicks/closes (mixin), use/attack interactions, joins/disconnects. `HxChestMenu` is the core menu (clicks never move
items; `HxChestMenu.registerScript(name, script)` for behaviour). A WP adds ops in ITS module
(`hx/surface/HxSurfaceModule`, `hx/menu/HxMenuModule`, `hx/dungeon/HxDungeonModule`, `hx/boss/HxBossModule`), named
`<prefix>.<verb>`; registering an existing name is a startup error. Use `HxEvents.custom(type, player, data)` to put a
module's own events in the stream. javap every vanilla constructor/method you use against
`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-common-*/26.1.2/*.jar`.

## Client side

- `dev.testkit.gametest.hx.Hx` - the RPC client (`Hx.connect()`, `call(op, k, v, ...)`, `chat`, `overlay`,
  `sidebar`, `tab`, `give`, `stand`, `menu`, `stub`, `head`, `events`, `commands`). Call from the test thread.
- `dev.testkit.gametest.hx.Session` - one server for many cases: `Session.run(ctx, "100-hx-session", setup,
  s -> { s.test("101-hx-...", c -> {...}); })`. Per case: report row, log slice, screenshot on failure, Grim-clean
  check (or `testExpectingFlags`), `Mod.assertHealthy`. Labels the server mc.hypixel.net, applies Quiet, proves the
  detector once at the end. Case helpers: `c.hx()`, `c.ctx()`, `c.waitUntil(what, pred, ticks)`, `c.check(cond, msg)`,
  `c.note(text)`, `c.events(types)`, `c.commands()`, `c.onClient(fn)`, `c.scenario()`.
- `dev.testkit.gametest.mod.Mod` - reflection v2 (`cls`, `cfg`, `set`, `get`, `with` (AutoCloseable restore),
  `field`, `setField`, `pattern`, `staticCall`, `call`, `enumValue`, `isCheat`, `isDevTools`, `guardDisabled`,
  `chatFailures`, `mark`/`assertHealthy`). Names relative to `com.killer560.hub`. Every miss throws.
- `dev.testkit.gametest.mod.Quiet` - the QUIET profile (17 setters + 1 static field; RelayClient must read OFF after it). `apply()` throws listing every
  missing switch.
- `dev.testkit.harness.Report` - `build/testkit-report/summary.md|json`, `cases/<name>.log`, `screens/<name>-N.png`;
  `Report.note(case, text)` adds a line. `dev.testkit.harness.Coverage` - `Coverage.mark(pkg, cls)` / `markClass`
  (Mod marks automatically) -> `coverage.md`.
- `dev.testkit.gametest.Fixtures` + `src/gametest/resources/testkit-fixtures/SCHEMA.md` - load/validate fixtures and
  check them against the mod's live Pattern constants.

## Positive controls (SessionDemoTest, `-Psuite=demo`)

`099-session-demo-*`: bridge (ops + stubbed and unknown commands seen as `cmd` events), sidebar.set ->
`DungeonState.getFloor()=="F7"`, chat overlay -> `PlayerStatsFeature.health`, tab.set -> `PartyTracker.teammates()`
with classes, give -> `CheatUtils.skyblockId=="TERMINATOR"`, primitives (title in the client Gui, a named stand seen and removed, menu.open + a click recorded as `container.click` and `menu.click` with the item NOT moved + `container.close`, `use_item`), Quiet (relay OFF) + accounts file + offline flags. `-Psuite=failpath` fails on purpose to prove the failure path (one FAIL row + screenshot, session carries on, kicked client re-connected). If a WP sees "the
mod ignores X", run `-Psuite=demo` first: it separates a broken primitive from a mod finding.

## Lessons

- The old scripts matched clients by the bare path prefix `C:/Users/Hunter/killer560s-mod-testkit`, which is a prefix
  of every sibling checkout; place-test-window.ps1 in pzB moved pzA's client window (2026-10-04). Both now anchor the
  checkout root with a trailing slash and require `fabric.dli.env=client`. Checkouts still on older commits (pzA, pzB,
  boot at f7d4fd3/496ca50) keep the old placer and can still close a newer checkout's client when THEY start a run.
- `powershell -File` binds a `string[]` parameter to its FIRST value only; parallel-suite uses `-Command` with
  single-quoted values instead.
- In this machine's Git Bash, a here-doc fed to `python -` lost backslashes (`'\\'` arrived as `''`, `"\\n"` as a
  newline) - edit files with backslashes through the editor tools, not python-in-heredoc.

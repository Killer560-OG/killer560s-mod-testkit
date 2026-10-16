# FPS bench (95-fps-bench)

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

## Hub bench (403-perf-hub)

`perf/HubPerfBenchTest`, about 18 minutes, only when named (`-Pscenario=403`). What 95 does not exercise: on the Hx test
server (labelled mc.hypixel.net, hub sidebar, an 80-entry tab list) with 120 named armour stands and 60 zombies around the
player, five sub-phases are timed ON vs OFF (2 rounds x 600 ticks each), then one ON and one OFF JFR per phase:
`lobby` (no screen), `tab` (player list held), `chat` (one Hypixel-shaped public/party/private line every tick), `menu`
(a 54-slot "Auctions Browser" of SkyBlock items) and `storage` (the same items on "Ender Chest (1/9)"). ON is 95's set
plus `HUB_EXTRA` (Chat Tidy, Party Finder, Smooth Teleport, Inventory Theme/Search, Item Protect, Slot Binds, Tooltip
Scroll, Custom Crosshair, emotes, Copy Chat, Motion Blur, Blessings HUD, RNG Meter, Storage Overlay). Results in
`build/testkit-report/hub-bench.txt` and `hub-<phase>-on.jfr` / `-off.jfr`.

How to read it (2026-10-07): TICK numbers are comparable everywhere (n=1200 each). FRAME counts are not - with a screen
open the gametest loop renders one frame per tick, without one it sometimes renders many (a lobby phase recorded 12,898
frames ON against 1,798 OFF), and a phase where ON replaces vanilla drawing (Custom Scoreboard hides the vanilla sidebar,
Storage Overlay replaces an Ender Chest page) can read ON cheaper than OFF. Trust `menu` (both sides draw the same chest)
and the JFR attribution; read `storage` as ON before/after a change, not as a delta. `framerateLimit` 260 is unlimited.

## Name features bench (975-perf-nametag)

`perf/NametagPerfBenchTest`, ~9 min, only when named. Names off / own fade / fade + Wave, first person and F5, in a quiet
scene and a busy one (50 named stands, six waving supporter bots, 80-row tab held, a chat line a tick). Its main number
is `NAME HOOKS us/frame`: `harness/NameProbe`, fed by optional `@Pseudo` mixins on the mod's name classes
(`testkit.k560probe.mixins.json`), times the outermost hook call and subtracts the timer's own ~45 ns. Read that, not the
frame-mean deltas: with ~1.5 frames a tick the frame means of two cells drift by +-200 us (one cell rendered 4,600 frames
where the others rendered 900). `-PnametagShots=<dir>` writes screenshots and `glyphs-*.txt` (every glyph the Font is
handed, with what of its Style reaches the pixels) per cell at a frozen animation clock; `-PnametagShotsOnly=true` skips
the timing. Compare two jars by the glyph files: screenshots from two runs are never exact (sky shading and the player's
idle pose differ by a few levels over the whole frame, even with names off). Under Sodium the gametest screenshot throws
"Global terrain uniforms have not been updated", so the bench skips shots when Sodium is loaded.

## Per-feature cost (95-perf-features) and the regression gate (95-perf-gate)

Both run in `perf/PerfBench`'s fixed scene: the seeded sim F7, window 854x480 at GUI 2, render distance 8, FOV 70, vsync
off, no frame cap, and `harness/BenchCamera` turning the view one full circle per 200 ticks at 10 degrees down, restarted
from the same yaw every phase. They read the mod's debug-only `perfprobe/PerfProbe` (mod docs/TESTING-HOOKS.md): the
build sets `-Dkiller560.perf.probe=true` for any `-Pscenario` naming `95-perf`, and the jar must be a dev build (not
`-Prelease=true`), or the bench fails saying so. The probe times every Fabric listener and HUD layer a mod class
registers, plus each mod HUD element (`HudElementRegistry.drawAt`), as self time on the render thread minus its own
calibrated per-call cost (~25 ns inside the window; ~75 ns per call is added to the frame). The mod's mixins into vanilla
are NOT in the probe; `perf-allon.jfr` (`tools/fps-jfr.py`) covers them.

```
./run-scenario.ps1 -Scenario 95-perf-features -Port <port> -TimeoutSeconds 4200 -ModUnderTest <cheat jar> -Extra @("-PtestVolume=0")
./run-scenario.ps1 -Scenario 95-perf-gate -Port <port> -TimeoutSeconds 900 -ModUnderTest <cheat jar> -Extra @("-PtestVolume=0")
```

**95-perf-features** (~45 min): OFF (every switch off), DEFAULTS (a fresh config after Quiet) and ALL ON (defaults plus
every switch except automation/OS/network ones, `PerfBench.NEVER_ON`) alternated for 2 rounds x 1200 ticks; then each
bench-safe switch ALONE for 200 ticks against an OFF reference taken every ten switches; then ALL ON under JFR. Writes
`perf-features.txt` (summary, per-feature tables, top 40 callbacks, each switch alone) and `perf-features.tsv`.
`-PperfOnly=livemap,scoreboard` limits the alone runs, `-PperfAloneTicks=0` skips them, `-PperfRounds`, `-PperfBigTicks`,
`-PperfNoJfr`. The unit that ranks features is **us/f@144**: probe cost per frame plus tick cost x 20/144, i.e. what the
callback costs per frame of a player at 144 fps. Frame and tick ms are whole-frame CPU (`FrameClock`) and swing +-0.3 ms
between runs on a loaded machine; read the probe columns for a feature, the frame columns for the total.

**95-perf-gate** (~5 min): 3 rounds x 400 ticks of DEFAULTS and ALL ON; takes the MINIMUM over rounds of each number
(other processes only ever add time), and fails when the DEFAULTS or ALL ON total exceeds the baseline by more than
25% + 10 us, or any one feature package's ALL ON cost exceeds its baseline by more than 50% + 5 us (a feature new since
the baseline gets the 5 us alone). The baseline is `perf/perf-baseline.properties` (killer560's machine; regenerate on
another one); `-PperfGateWrite` writes it from the run instead of checking (do that only on a jar you have just
measured as good, and commit the file with the change that justifies it). Budgets: `-PperfBudgetPct`,
`-PperfBudgetFloorUs`, `-PperfFeaturePct`, `-PperfFeatureFloorUs`; another baseline file: `-PperfBaseline=<file>`.
Run it before merging anything that adds a HUD element, a render listener or a per-tick handler; when it fails, the
`OVER` lines in `perf-gate.txt` name the feature package, and 95-perf-features with `-PperfOnly=<it>` says which
callback.

## Idle cost (95-perf-idle) and the unwrapped listener span

`harness/EventSpan` times every START/END_CLIENT_TICK and after-translucent-terrain/-features listener from outside, by a
testkit listener in a phase before all others and one after the default phase, so nothing is wrapped. Every
95-perf-features phase prints it ("listener span"); it is the real cost only with `-PperfNoProbe`. On 2026-10-15 it
matched the probe's total with every switch off (~570-620 us a tick), which is how the 1-2 us per-handler floor was shown
to be real cold-cache cost, not proxy overhead.

`perf/PerfIdleTest` (~4 min, only when named, no probe needed): with every switch OFF and at DEFAULTS, a chat corpus
(party, guild, all chat, NPC/boss, system, one action bar in five) through the real `ChatListener.handleSystemMessage`,
render-thread bytes and microseconds per line against `-Dtestkit.perf.idleChatOff`/`idleChatDefaults` (24/26 KB), plus
the tick span; and a check that two ChatObserver subscribers and a raw Fabric GAME listener get the SAME String for one
line. `-PperfIdleJfr=<file>` records a chat JFR (execution + allocation samples, 4000 lines, OFF).
```
python -X utf8 perf-lock.py <label> -- powershell -NoProfile -Command "& ./run-scenario.ps1 -Scenario 95-perf-idle -Port <port> -TimeoutSeconds 900 -ModUnderTest <cheat jar> -Extra @('-PtestVolume=0','-PperfNoProbe')"
```

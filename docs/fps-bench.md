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

## The mod's mixins into vanilla (95-mixin-bench, 95-mixin-fixes)

PerfProbe sees Fabric listeners and HUD layers only; a mixin handler is merged into the vanilla class it targets
(JFR names it `<vanilla class>.handler$abc000$killer560smod$name`, which `tools/fps-jfr.py` now counts as mod code).

```
./run-scenario.ps1 -Scenario 95-mixin-bench -Port <port> -TimeoutSeconds 900 -ModUnderTest <cheat jar> -Extra @("-PtestVolume=0")
./run-scenario.ps1 -Scenario 95-mixin-bench -Port <port> -TimeoutSeconds 900 -NoMod -Extra @("-PtestVolume=0")
./run-scenario.ps1 -Scenario 95-mixin-fixes -Port <port> -TimeoutSeconds 600 -ModUnderTest <jar> -Extra @("-PtestVolume=0")
```

**95-mixin-bench** (`perf/MixinBenchTest`, ~3 min, only when named; runs with or without the mod): 403's hub scene (120
named stands, 60 zombies, 80-row tab, the SkyBlock sidebar) plus a chest, button, lever and skull. DIRECT: about
twenty hot vanilla methods the mod hooks (team colour, glow, entity culling, hover name, components, dye colour, font
width, tooltips, chunk lookups, block shapes, cooldowns, view yaw, FOV, mouse scaling) called in a tight loop on the
client thread, best of 5 after 2 warm-ups, in ns per call; the mod's cost is the difference from the `-NoMod` run. FRAME:
`harness/MixinBench` `@WrapMethod` timers (`testkit.bench.mixins.json`, applied only for a filter naming `95-mixin`)
around `extractSlot`, a whole container screen, `extractEntity`, `shouldRender` and GUI text/sprite/fill calls,
during a 54-slot SkyBlock menu with slot 13 hovered and then the lobby, plus render-thread KB per frame. With the mod it
runs DEFAULTS and ALL ON (95's set, 403's hub extras, and the features whose hooks it exists for). `mixin-bench.txt/.tsv`.
`-PmixinBenchOnly=direct|frame|alone`, `-PmixinBenchFrames=300`, `-PmixinBenchIters=1000000`, `-PmixinBenchJfr` (one JFR
per in-frame phase, `mixin-<config>-<phase>.jfr`), `-PmixinBenchAlone` (the menu timed with each ALL ON switch alone,
`-PmixinBenchAloneTicks=40`). The `@WrapMethod` timers box primitive arguments (`Integer.valueOf` per call), the same
on both sides of an A/B, so read KB/frame as a difference between jars, never against another bench.

**95-mixin-fixes** (`perf/MixinFixesTest`, ~1 min): allocated bytes per call (20,000 calls after a warm-up) of the hot
paths fixed on branch fps-mixins-1015, each with a check that the feature still acted. Fails on mod main 69505e45 with
seven failures. A mixin's `CallbackInfo(Returnable)` costs 24 B per call whenever the JIT leaves its handler out of line,
and in a tight loop that changed between runs of the same jar (0 or 24 B), so the budgets sit above 24 B and below
what main allocated.
## In-world drawing (95-perf-world)

`perf/WorldRenderBenchTest`, ~5 min, only when named; run it through `perf-lock.py`. The 95-perf scene has almost
nothing for a world renderer to draw, so every LevelRenderEvents listener sits at the probe floor there. This one builds
the sim's Chambers room, spawns 20 starred zombies round the player and puts a Terminator in his hand, switches
everything else off, and measures BASE / ESP (Filled Box, through walls) / WAYPOINTS (Fill + Outline, names, crypts) /
TRAJ (shortbow arcs) / ALL, 2 rounds x 400 ticks (`-PperfWorldTicks`, `-PperfWorldRounds`; pass more than one `-Extra`
through `powershell -Command`, since `-File` hands a comma list over as ONE argument). Per phase: probe us and B per
frame of the world keys, plus `compat.McRender` (the mod's world batch, which now does the drawing the features used to
pay for), and draw calls per frame counted at `RenderType.draw` by `harness/DrawCalls` (26.1.2 only; 26.2 has no
MultiBufferSource). Fails when ALL adds more than 12 draw calls a frame over BASE or ESP allocates more than 16 KB a
frame, and when a feature drew nothing. Main 69505e45: ESP alone 40 draw calls and 112 KB a frame; fps-world-1015: 2 and
6.6 KB. Output also in `build/testkit-report/perf-world.txt`.

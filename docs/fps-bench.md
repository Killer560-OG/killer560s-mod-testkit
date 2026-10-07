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

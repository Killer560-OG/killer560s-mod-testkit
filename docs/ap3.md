# AP3 runtime on GrimAC (63-ap3)

`./run-scenario.ps1 -Scenario 63-ap3 -Port <p> -ModUnderTest <cheat jar> -Extra "-PtestVolume=0" -TimeoutSeconds 1500`
(add `-Minecraft 26.2`), about 4 minutes. One Session (`Ap3RuntimeTests`), 25 cases, positive control at both ends.
AP3 runs under Force Dungeon on a long flat floor of 32 lanes; each case gets its own lane (server `tp` to its start),
builds its nodes straight into the boss chain by reflection (never saved), then walks him into a 2x2 RUN box with a
real W press and lets go inside it - AP3 carries him from there.

- `look-*`: the camera (`getViewYRot`/`getViewXRot`, what the renderer reads) must be on the node's angle at the END of
  the first tick his position is inside the box, and still 3 ticks later.
- `use-*`: on the wire, the use must be the next thing after the movement packet that put him in the box (no movement
  packet between), carry the node's angle, and be followed by a movement packet reporting the same angle (BadPacketsJ);
  with a swap, one slot packet right before it. `PacketTrace.tap` hands the probe every serverbound packet object.
- `hold-<type>`: does a RUN keep going 15 ticks after a node of that type? Walk/run/stop/aligns end or replace it.
  Every hold case must be Grim-clean.
- `block-walk`, `boom-walk`, `block-rep-2..5`: Block / Boom on the wire - one slot change right before the place / dig,
  that the next thing after the entering movement packet, a Block's swap back sent once. On mod ec838c10 and earlier
  they drew Post (held item change, block placement, digging) and BadPacketsA; clean from 0b6107fc. One RotationPlace
  "post-flying" was seen once on 26.2 (1 of 15 places that night, not reproduced) - read the rep cases as a rate.
  Its cause (2026-10-18): GrimAC 2.3.74 (`CheckManagerListener.handleQueuedPlaces`) gives a place the NEXT movement
  packet's rotation only when that packet arrives within 15 ms of it, else judges it by the rotation before (the run's
  heading) - and one post-flying leaves its buffer at 1, so every later place in the session flags "pre-flying". AP3 sent
  the place at START_CLIENT_TICK, so a slow tick opened the gap; since mod todo-blood-ap3-grim-1018 it goes out at the
  head of `sendPosition`. Each case notes the gap; `1018-ap3-block-slow-tick` sleeps 25 ms after the mod's START work
  every tick and must stay clean (the START-sending jar flags it every time).
- `stopwatch-hud`, `cmdtree`: see the case code. `corrections`: two server moves 10 ticks apart while a RUN carries him - AP3 must keep running and enabled, report each (chat line, `[Correction] AP3:` log line) and play the alarm (`ModSounds.correctionAlarmsPlayed`); mod rule 2026-10-06, a correction never stops anything.
- `1022-ap3-breaker-*` (mod ap3-breaker-tick-1022): Breaker Aura's first dig after a landing must go out on the first tick
  a pick is in the client, standing and in reach - read at START after the mod's own START work (the S marker carries the
  position and that count). `tp` / `tp-run` (blocks loaded, AP3 RUN carrying him), `tp-late-blocks` (server places them
  after the landing), `tp-new-chunk` (a forceloaded pad 600 blocks out the client was never sent), `ew` (hand etherwarp,
  Auto Swap at Swap Delay 1: swap on that tick, dig the next), `leap-menu` (server closes a menu, then teleports). Mod
  final-1025 digs 4-6 ticks late in every one (ActionGate's teleport and screen settles). Picks that start as air read
  "all gone" before they arrive, and a setblock right after leaving the far pad says "That position is not loaded".

Traps found writing it:
- A server-console `tp @p ~0.3 ~ ~` is relative to the CONSOLE (world spawn), not the player: it moved him 176
  blocks. Use `execute as @p at @s run tp @s ~0.3 ~ ~`.
- `HudSeen.markDrawn` does nothing while the HUD editor is open, so it cannot tell whether the editor previewed an
  element. Read the preview off a screenshot instead.
- The probe's END listener is registered after the mod's, so AP3's own END tick has run when it records; a packet AP3
  sends from START_CLIENT_TICK lands after the previous tick's END marker and before this tick's START marker.

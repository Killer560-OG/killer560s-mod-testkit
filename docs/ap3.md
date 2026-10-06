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
  BLOCK and BOOM draw Post + BadPacketsA (pre-existing; they still send from END_CLIENT_TICK), so the hold cases are
  `testExpectingFlags`.
- `stopwatch-hud`, `cmdtree`, `corrections-off/on`: see the case code.

Traps found writing it:
- A server-console `tp @p ~0.3 ~ ~` is relative to the CONSOLE (world spawn), not the player: it moved him 176
  blocks. Use `execute as @p at @s run tp @s ~0.3 ~ ~`.
- `HudSeen.markDrawn` does nothing while the HUD editor is open, so it cannot tell whether the editor previewed an
  element. Read the preview off a screenshot instead.
- The probe's END listener is registered after the mod's, so AP3's own END tick has run when it records; a packet AP3
  sends from START_CLIENT_TICK lands after the previous tick's END marker and before this tick's START marker.

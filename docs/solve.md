# Auto puzzle suite (93-solve-*)

```
./run-scenario.ps1 -Scenario 93-solve -ModUnderTest <cheat jar> -TimeoutSeconds 1500    # all eleven, ~12 min
./run-scenario.ps1 -Scenario 93-solve-icepath -ModUnderTest <cheat jar>                 # one room
```

`SimPuzzleSolveTests`: one scenario per sim puzzle room (tictactoe, icepath, icefill, higherblaze, lowerblaze,
creeperbeams, boulder, threeweirdos, waterboard, teleportmaze, quiz), then `[93-solve] SUITE SUMMARY`. Each loads
the room with `SimBuilder.buildSingleRoom` from the title screen (his path), gives AOTV in slot 1 and Terminator in
slot 2, waits until the CLIENT has the room under his feet, then switches on this room's auto (solver, master,
Etherwarp Reposition, pathing and Interactive Map too; every other auto off) and watches for 60 s. The verdict is
the sim's own `Sim*Puzzle.isComplete()`, failed if `SimRoomState.isFailed` ever went true; Boulder's is the reward
chest opening plus `BoulderWatch` (below). Status lines every 5 s
carry the solver's own state (`probes`), and a failure prints the last 60 mod/chat log lines (`LogTap`).

Higher and Lower Blaze also check the free camera (mod dc5b4803, killer560 2026-10-06: "it just snaps my camera
around everywhere"). `CameraWatch` records every render frame from before the auto is switched on until 10 ticks after
it is switched off: `getViewYRot/XRot`, the body's rotation, `ViewFreeze.isHeld`, `SimTerminator.arrowsFired` and the
solver's blaze count. From the first held frame the view may not move more than 0.5 degrees (yaw compared modulo 360:
the hand-back keeps the body on its running yaw, a whole turn from the held number, same picture). It refuses to pass
unless arrows were fired and blazes died inside that window and the body turned more than 5 degrees. Main before the fix
(fe713ba7) failed it, the view moving 127 and 151 degrees with the lease lapsing onto the body; the fixed jar's line reads e.g. "30 arrow(s) and 10 blaze kill(s) ... view moved at most 0.000 deg".

Water Board checks more than the solve (mod 5cd5b881): before the auto starts, exactly three colour layers of the
walkway under the glass are fully out (x 14..16 at y 56, x 14/16 at y 57, 5 of 5 cells) and no reward chest exists;
after the solve, a chest at room-relative (15, 56, 22) equal to `SimWaterPuzzle.rewardChestPos()`, all five layers
clear, Secret Aura opening it within 15 s with `SimScore.secretsFound` unchanged, then (auto off)
`SimWaterPuzzle.reset()` removes it and refills the three layers. Positions go through `PuzzleCoords`, not the sim's
anchor. A jar before 5cd5b881 fails at the start check ("0,0,1,1,1": middle block only).

`-PsolveRepeat=N` plays each selected room N times in one launch. `-PnetStallMs=120` holds the client
connection's netty loop for that long every 13 ticks (`-PserverStallEvery`), so several packets land in one client
tick - the lag a loaded machine produces; it turned Ice Fill's one-in-dozens flake into 6/6 on 2026-10-05.
`-PserverStallMs` sleeps the integrated server instead and does nothing useful: the client gametest runs client and
server in lockstep, so both just slow down and every run is identical to the tick. `-PicefillControl=true` makes
Ice Fill prove the sim still breaks a section on a two-tile warp and on a repeated tile before the auto plays, and
then forces a mistake half way across sections 1 and 2 while it plays (`-PicefillBreaks=2` for one): the verdict
also needs that section broken, no sim landing while broken (4-tick grace), regenerated, the first landing after on
its entry tile, and the room solved. The forced step-back is judged with `SimIceFillPuzzle.onTeleport`: a plain
server teleport is only seen by the once-a-tick judge, after the auto's next hop has already moved him on.

Room captures are copied from the "26.1.2 (Mod Only Test)" instance unless `TESTKIT_SIM_INSTANCE` names another
(`$env:TESTKIT_SIM_INSTANCE = "Map Logger"` before the script). They are not the same captures: on 2026-10-04
Boulder, Ice Fill, Ice Path, Quiz and Water Board differed, and Mod Only Test's Boulder does not arm as a puzzle
at all ("best 1 of 7 expected blocks") while Map Logger's does. Say which instance a verdict was played on.

Human input it drives, only after giving the auto 10 s to do it itself, and says so in the log: Quiz is placed
between the pillars, Three Weirdos in front of the NPCs (both autos only click within reach and never move), and
Teleport Maze is walked onto the start pad with the forward key.

## Boulder (93-solve-boulder, 93-solve-boulder-aura)

Three scenarios, one per Auto Boulder mode, picked by whether Secret Aura will take the chest
(`setSecretAuraEnabled` and `setAuraChests`, set every run): `-aura` (both on), plain (aura off) and `-chestoff`
(aura on, Chests off - must play it as aura off, and fails if the auto logs `-> RUN_TO_BARS`).
`-Pscenario=93-solve-boulder` runs all three. `BoulderWatch` reads the client every tick and the sim's log, never the
auto's state. **Aura on** (`-aura`): he is placed outside the doorway and walks in with the forward key himself; then
no button press, feet never below the roof, and `isSprinting` on every tick the forward key is held after the first.
**Aura off**: the sim solved, presses equal to the most clicks the solver named, the floor reached, no landing more than
2.0 blocks below where he left the ground (a stair top to the next stair's lower half is 1.5; the roof hole is 5), and
every sim press had `mc.hitResult` on a stone button in the three ticks before it. **Both**: the auto logs
"finished", he ends at roof height within 4 blocks of the doorway and the live map no longer says Boulder, and no
rotation change over 40 degrees between two SENT ticks (the mod's own steps, `AutoBoulder.currentStep`).

Smoothness ("the boulder turning is really choppy", 2026-10-06): a level-render hook registered after the mod's records
every frame's rotation; any frame-to-frame turn faster than 900 deg/s (frames >= 2 ms apart) fails - a 28-degree
per-tick snap reads ~2900 (the merged 6afb1c6e jar failed with 28.2 deg in 9.7 ms). The mod's per-tick steps are also
resampled at 60 and 144 fps at 20 TPS (limits 12.5 / 5.5 deg per frame), and `AutoBoulder.partialFrames` must be
non-zero. The gametest renders irregularly (240 to 1900 frames a run), so read the rate, not the frame count.
Since 2026-10-06 the rate is judged only between two frames of the SAME step: at about one frame per tick (two clients
at once, or a run after 76) every pair straddles a step boundary and is a whole capped step whatever the mod does - 26.2
failed at 28.07 deg in 28.3 ms (993 deg/s) with 379 frames for 315 steps, and passed the same jar alone at 1,893 frames.
The fps-proof check is the other one: each frame drawn inside a step of 2+ deg must not be more than 25% further along it
than the time since the step began (`currentStep`'s start and draw time) allows - a snap draws the end early. It timed
196 to 1,166 frames per run on both versions with 0 ahead; it has not been seen to fire on a broken jar (no such jar kept).

A single built Boulder seals its doorway (relative z -1) with diamond blocks and has nothing outside, so a walk out
could never leave the room. The scenario clears that gap and lays stone at relative y 68, x 12..18, z -7..-1 first,
and prints what it cleared - a test fixture standing in for the next room on Hypixel.

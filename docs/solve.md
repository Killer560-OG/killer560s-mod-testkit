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

Since 2026-10-06 both blaze rooms play with Auto Blaze's Auto Secret ON and judge the secret trip on the mod's own clock
(`AutoBlaze.testProbe`: ticks, final release, walk start, aura click, resume shot): the walk must start 1-2 ticks after
the volley at the last blaze leaves the bow and before the sim calls the chain complete, `SimScore.secretsFound` must
rise (the chest's screen is not a usable signal: the per-tick container check missed it in 3 of 4 runs), and the aura click must come from within 4.5 blocks of the chest's box, not standing on
it. The camera check is judged per hold (the hand-back the tick after the final volley must not move the view; the walk
after it may). `93-solve-blazemiss-higher` / `-lower` refuse every hit on the last blaze for 10 server ticks from the
first and remove the arrow (`BlazeMiss`), and require the order secret taken, then a resume shot, then the chain
complete. A jar without `testProbe` prints the timings only ("secret timeline: last blaze dead at ..., chest opened at
...") - that is how main's wait-then-walk was compared.

Water Board checks more than the solve (mod 5cd5b881): before the auto starts, exactly three color layers of the
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
between the pillars, Three Weirdos in front of the NPCs (Auto Quiz only clicks within reach and never moves; Auto Three
Weirdos moves into reach itself since weirdos-reach-1015, so its case should read "approached by itself"), and
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

## Autopilot-shaped cases (93-solve-boulder-mapdoor, 93-solve-teleportmaze-budget)

Added 2026-10-06 for the two faults 142-sim-autopilot2 turned up; run them with `TESTKIT_SIM_INSTANCE=Map Logger` (what 142
uses) and `-PsolveRepeat=N` (each Teleport Maze run draws a new maze).

`-mapdoor` is Boulder with Secret Aura off, started the way the autopilot starts it: placed on the Interactive Map's spot for
the room (relative 15, 68, -2, which the live map files under no room) and an Interactive Map arrival FAKED there by setting
`ClearExecutor.arrivalSeq/arrivalMs/arrivalPos` by reflection (a single built room has no room next door for a real path). It
fails if Auto Boulder does not log "started - the Interactive Map", then judges like the plain Boulder case. Mod 264ea8c1:
0 of 4 ("the solver read no known arrangement in 4000 ms", then "no walk to the reward chest").

`-budget` is Teleport Maze with the autopilot's 30 s instead of 60, plus `ExitWatch`: after every maze teleport (from before
the start-pad walk) the sim's exit pad (`SimTeleportMazePuzzle.boundPads[exitPad]`, x/z) must be among
`TeleportMazeSolverFeature.getCorrectPortals()` until it has been stepped on. Mod 264ea8c1: 1 of 4 (exit lost after the first
teleport with 0-2 candidates left, one run not solved in 30 s). The watch is seeded with the solver's teleport count at the
start - it survives from the run before, and an unseeded watch judged a stale count at t=0.1s.

## Puzzles round 2026-10-15 (SimPuzzleRoundTests, 93-solve-boulder-mapwarp)

Cases on the same Base, each adding its own settings and checks through `SolveExtra`:

- `93-solve-tictactoe-grab`: his settings (Grab Secret and Walk Out on, Secret Aura on). The room's secret must be taken
  (`SimScore.secretsFound` rises) between the first and second board click, the forward key never held, and no walk-out
  line before the sim's game is over. The mod's sim now has the computer reply `SimTicTacToePuzzle.REPLY_TICKS` after his
  click, as Hypixel does; an older jar's sim replies in the same tick, so the early walk-out cannot show on it.
- `93-solve-waterboard-claim`, `-icepath-claim`, `-icefill-claim`: Secret Aura OFF; the auto itself must open the reward
  chest within 20 s of the solve (Water Board / Ice Path: the sim's `isRewardChestOpened`; Ice Fill: a container opening).
  An older jar's sim has no Ice Path chest at all.
- `93-solve-icefill-firsttile`: delay 1, adaptive off, and `harness/HypixelTileRegister` holding every ice-to-packed-ice
  block update the client gets for 3 ticks (Hypixel registers a tile after the landing). Every hop off a section's first
  tile (read from `AutoIceFill.lastIndex` / `sectionStart` by reflection) must come after that tile read packed.
- `93-solve-boulder-mapwarp`: Secret Aura on, placed on the test floor outside the doorway, then a REAL map press on Boulder
  (`AutoClearUtils.pathToRoom`); Auto Boulder must start within 15 s and pass the aura checks (sprint included).
  `-doorwarp` puts him on the doorway spot with a plain teleport instead (what his own etherwarp does; ClearExecutor's
  arrival counter never moves). Every Boulder case now fails on a forward-held tick with the SPRINT KEY up (after the
  first two ticks of a hold); whether vanilla sprints that tick (a wall) is physics, not the auto.
- The tic tac toe room's secret in Mod Only Test's capture sits in a pit behind a wall; nothing reaches it on foot or by
  map. Grab Secret now warps to a landing within SECRET AURA's own reach (standing eye to the chest's box, through walls,
  as the aura clicks) - the board side of that wall - and the case requires the secret COUNTED with no click of the auto's
  own. Mod 40562c30: PASS (secret at 1.2 s); main (380349f2 merge): FAIL, never counted.

## No-wait timing (93-solve-*-timing, 1015-grim-puzzle-nowait)

Mod branch puzzles-nowait-1015 (killer560's rule: automation acts the very next tick, no fixed safety waits).
`SimNoWaitTimingTests` plays Ice Path, Ice Fill, Higher/Lower Blaze, Creeper Beams, Tic Tac Toe (Grab Secret, Secret Aura
on) and Water Board's reward chest (Secret Aura off) on the same Base, with `PuzzleTimingExtra`: every click, use, sneak
and landing stamped with its client tick, and four gaps judged at 1 tick - click -> next use, sneak down -> warp use,
landing -> next action, ready -> click - plus the puzzle autos' `TurnFirst.foreignWait` read every tick (any waited tick
fails). Shoot cooldown is set to its 250 ms minimum and a shot inside it is not counted (his setting, not a wait). Traps in
the instrument, each found on the first runs:

- A use-on packet is not always the auto's click: `RightClick.beforeUse` sends the block half of a vanilla right click in
  the same tick as a use (dropped), and Secret Aura's chest click is another feature's (a ready moment, not a click). Told
  apart by the stack at send time; the Interactive Map's warps (`livemap.autoclear`) likewise, kept as MAP_WARP.
- A probe read at END of tick t (the solver's next move, the silverfish stopping) is first visible to an auto at START of
  t + 1, so it is stamped t + 1; a landing is seen at START of its own tick (ActionGate's settle counts from it). A click
  that needs a turn therefore reads 1 after a probe, an immediate shot 0.
- Events before the auto is switched on (the room's own spawn landing) are dropped at the base's second configure.

`1015-grim-puzzle-nowait` plays the resulting packet orders against GrimAC at START_CLIENT_TICK with the mod's primitives:
landing (server tp keeping rotation) -> sneak let go and body on a lever -> click next tick -> warp look and sneak in that
tick -> use next tick; bow rounds landing -> bow in hand -> shot next tick -> AOTV and use the tick after; and turn rounds
under a held puzzle `FreeCam` (main waits in TurnFirst's foreign wait and holds the click; the branch turns and clicks).

`93-solve-blazemiss-{higher,lower}-timing` and the two blaze timing cases (mod blaze-event-1015) also judge each volley:
the recorder's own arrow watch (arrows that appear by his eye in their first 4 ticks belong to the last shot's volley;
an arrow ends when the client loses it, it stops moving, or it is 40 blocks out) gives the latest moment its result could
be known, and the next shot or chest click must come at most 1 tick after it or after his Shoot cooldown, whichever is
later (0 or less means the auto knew earlier). A warp, a landing or a map warp in between hands the gap to the landing
check, and an Interactive Map trip's own landing is not judged (that is the map's arrival settle). Main: misses 5 to 6.

## Reward chests under Auto Close Chest (93-solve-*-chest-autoclose, 93-solve-boulder-chest-unsolved)

Mod branch chest-stall-1015 (killer560, 2026-10-15, "it gets stuck on ice fill after grabbing the chest"). `SimChestStallTests`
plays Ice Path, Ice Fill and Water Board with Secret Aura off and Auto Close Chest ON (his setting), so no chest screen ever
shows. The truth is a server-side `UseBlockCallback` in the harness that records every chest the integrated server took a
click on. Each claimed chest must be clicked once, the step must log "reward chest step over - done" within 3 ticks of the
last claim, and `AutoPuzzleActivity.isWorking` must read false within 2 ticks of that; a case where a screen showed, or the
server took no chest click, is UNTESTED. Main: 3 clicks per chest, "did not open", the step over ~9 s later, the hold 41
ticks after that. `boulder-chest-unsolved` (Secret Aura on, no button pressed): the sim's `SimPuzzles.isComplete("boulder")`
within 2 ticks of the chest opening and the tab list's Boulder ✔ within 40; main never. Every 93-solve case now starts
with Auto Close Chest off unless its extra turns it on. 102-sim-autosecret's puzzle hold alternates the fake puzzle's name
per tick ("Ice Path chest" / "Puzzles") and allows one hold line each in the log, chat and Auto Routes' arming (main: 100).
## Three Weirdos reach (93-solve-weirdos-reach)
Mod branch weirdos-reach-1015 (killer560 2026-10-15, sim: "it cannot reach the right person for auto three weirdos").
`SimWeirdosReachTests` builds Three Weirdos alone at 0, 90, 180 and 270 (`SimBuilder.setSingleRoomRotationForTest`; a jar
without it plays 0 only) and starts Auto Three Weirdos (Talk To NPCs, Etherwarp Reposition, pathing on, AOTV in hand) from
the Interactive Map's spot (relative 15, 68, 22: 1.90 / 2.85 / 3.26 to the three CLICK stands in every rotation, his
case), a spot with one in reach and one with none, plus where the build stands him by the doorway (~21 blocks off). Each case: solved within 6 s (the Autopilot's estimate), three
interacts each within 3.0 eye to box (PacketWatch), talks without a move between at most 2 ticks apart, and the auto's
"waiting" lines once per change (no repeat, at most 12). Rooms after the first are rebuilt in the open world with
`SimQuizPuzzle.reset()` first; leaving the world after every case deadlocked the client (IntegratedServer.halt under
Minecraft.disconnect against the server parked in the gametest's postRunTasks). Main d1eda658: 3 of 3 FAIL (never solved;
2 / 1 / 0 talks; 781 waiting lines in one case). Branch b77b8df9: 16 of 16 PASS, solved in 0.70-0.90 s (the doorway starts by an Interactive
Map trip: no warp line from there reaches a spot in reach). Full 93-solve on 93293970: 38 of 38 in one launch.
`1015-grim-puzzle-nowait` also plays two talk rounds: land, body onto a CLICK armor stand in the landing tick, the interact
the next tick with no turn tick, the body onto the second stand after it, its interact the tick after (4 interacts at
2.48 eye to box, GrimAC clean on the branch; a jar before weirdos-reach-1015 has no `aimBodyAt(LocalPlayer, AABB, FreeCam)`).
weirdos-fast-1016: the case also requires the chest within 3 ticks of the last talk (the Chest Open Delay default is
0). Main 93c10e55: 16 of 16 FAIL (6-7 ticks, 250 ms default); branch: 16 of 16 PASS (0.40-0.75 s).

## Speed round 2026-10-16 (SimPuzzleSpeedTests, SimPuzzleDoneTests, 1016-grim-puzzle-speed)

Mod branch puzzles-speed-1016. Base gained `rotation()` (each run of `-PsolveRepeat` builds the room at the next quarter
turn via `SimBuilder.setSingleRoomRotationForTest`), `simSpeed()` (sets the server player's movement_speed attribute, as the
sim's `/speed` does without saving it; checked on the client) and `workingMayBeInstant()` (Quiz: one click whose tick ends
the activity spell, so no watched tick reads "working").

- `93-solve-boulder-speed-aura` / `-buttons`: speed 600 (his sim's), four rotations with `-PsolveRepeat=4`. Fails on any
  "pushed off the path", more than one path walk or straight run out, a stalled run, a give-up, or no finish. Prints a
  `SPEED boulder` line (start -> chest, start -> finished, finished -> not working) and, on a failure, a per-tick trace
  (position, velocity, yaw, keys, collision). Main: 0 of 4 and 0 of 4.
- `93-solve-lowerblaze-speed` / `-higherblaze-speed`: every sim kill must be the one due, all of them, and "Blaze: done";
  `SPEED blaze` line (first shot -> last kill / done, done -> not working, shots, warps).
- `93-solve-<room>-resume` for Creeper Beams, Three Weirdos, Quiz, Tic Tac Toe, Ice Path, Ice Fill, Water Board and Teleport
  Maze: the sim's solve to `AutoPuzzleActivity.isWorking` false (when Auto Secret carries on), bound 5 ticks where nothing
  follows the solve, 40 for the reward-chest rooms, 200 for Teleport Maze's walk out; it also stamps the "PUZZLE SOLVED!"
  line (matched by room name: an earlier case's line can arrive after the next case's mark) and `solvedByHim`.
  `93-solve-icefill-done` already exists (SimPuzzleRoundTests), hence `-resume`.
- `1016-grim-puzzle-speed` (GrimAC): `AutoBoulder.testRunTo` runs the room's own run-out code 40 blocks from pressed
  against iron bars, at movement_speed 0.6 and 0.1; then three "move ahead" rounds (shot, the warp's use the next tick,
  landing, a shot 5 degrees of yaw off). Needs 35+ blocks, 80% sprinting, every use on its tick, 0 GrimAC lines.
- `PuzzleTimingExtra`: a landing whose next shot waited on a volley in flight or the Shoot cooldown is counted, and when
  every landing was one of those the "landing -> next action" pair is not required (Auto Blaze now lands during the flight).

## Creeper Beams Melee (1018-beams-melee-*)

Mod branch todo-creeper-melee-1018 (to-do item 29). `SimBeamsMeleeTests` plays Creeper Beams on the same Base with Auto
Creeper Beams' mode set to MELEE (`-solve`: the build's rotation; `-rotations`: the next quarter turn each run, use
`-PsolveRepeat=4`). Besides the sim's solve it needs every end of every pair the sim joined (`SimCreeperPuzzle.worldPairs`
/ `connected`) struck by a left click the sim counted ("struck lantern ... from N blocks"), each within 4.5 eye to box and
none refused, those lanterns prismarine on the client, no Terminator arrow, no shot line, and at least one warp. `Base`
now resets the mode to SHOOT in its configure, so a Melee case never decides how the next Creeper Beams case plays. A jar
without the mode fails on its missing `setAutoBeamsMode`. Main f0a61ba4: FAIL. Branch: 8 of 8 PASS, solved 2.3 s
(Shoot at 500 ms: 3.8 s). `1018-beams-melee-grim` (GrimAC) lands him by server teleport by six sea lanterns and calls
`AutoBeams.meleeClick` at START_CLIENT_TICK: four hits (2.2 to 4.0 eye to box), one that must turn first and hit the tick
after, one at 9.5 that must send nothing. PacketWatch's dig look angle reads ~133 degrees there: it uses `getViewVector`,
which the held puzzle camera answers, not the body's reported look; GrimAC said nothing. The `1018-beams-melee` filter
selects all three.

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
the sim's own `Sim*Puzzle.isComplete()`, failed if `SimRoomState.isFailed` ever went true; Boulder's is "a
container opened", because Auto Boulder auras the reward chest instead of pushing boxes. Status lines every 5 s
carry the solver's own state (`probes`), and a failure prints the last 60 mod/chat log lines (`LogTap`).

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

# Auto Routes on GrimAC (62-argrim)

`./run-scenario.ps1 -Scenario 62-argrim -Port 25751 -ModUnderTest <cheat jar> -Extra "-PtestVolume=0"` (add
`-Minecraft 26.2`), about 2 minutes. One Session, 16 cases, positive control before and after. The room is one of his
captured rooms sent through Hx `dungeon.paste` to grid cell (4,4) and identified by the mod's REAL world scan; the
abilities are `hx/dungeon/HxAbilities` (etherwarp, Instant Transmission, Superboom), off unless `dungeon.abilities`.
- Put the Catacombs sidebar up only once he stands in the room: the world spawn is inside F7's boss box and a dungeon
  entered there latches "boss room", which turns room matching off (no room is ever identified).
- An etherwarp START node reached by walking fires at the ring's edge and a long warp then lands a block short; reach
  one with a hand etherwarp (`warpOnto`). Aims for clicks must also tolerate the ring-edge offset.
- A harness click must LOOK at the block first (a turn, then the click on the ray's hit): a synthetic center hit while
  facing elsewhere is GrimAC RotationPlace, and it is the harness's flag, not the mod's.
- The Interactive Map's executor (`ClearExecutor`) has its own cases at the end: `62-argrim-imwarp` (the Go + Secret
  press on the room's cell with the map screen OPEN, a START-node warp across the 4-high wall), `-imwarp-run` (the same
  with Run While Map Open, so the route's START etherwarp fires under the open map) and `-goto` (the node editor's Go
  To). They run last: a map warp leaves the map-arrival interlock set and Go To leaves edit mode on. Select them with
  `-Pscenario=62-argrim-session:,62-argrim-imwarp,62-argrim-goto`. On main d667bf3a they drew BadPacketsJ on every
  warp and BadPacketsA (a swap's slot sent twice); clean from mod 09e2c304.
- Grim's check classes are in its jar under `common-*.jar` (nested twice); javap them from a SHORT path - the
  scratchpad path is too long for javap on Windows.
- `62-argrim-chain` (2026-10-06): twelve etherwarps round the arena, each landing in the next node (`ArChainMeasure.CHAIN`,
  shared with `96-ar-chain`). It reports ticks per warp from the use buckets and proves each use followed the previous
  landing: an `accept_teleportation` (sent by vanilla's position-packet handler) must sit between two uses, in the same
  bucket. Main 8957d449: 4.00 ticks per warp; ar-chain: 1.00, GrimAC silent. The sim's integrated server answers a tick
  later, so 96-ar-chain's floor is 2.00 (main: 5.00).
- `62-argrim-crypt` first holds the real use key through the harness (Hyperion, straight down) as the vanilla control, then
  requires the crypt node's use ticks to match it: every 4 ticks, the same packets per use.
- Since 2026-10-06 (ar-warprate) the chain runs at Etherwarps Per Second 20, 10 and 4 (96-ar-chain adds 3, the
  fractional pace) and asserts the average spacing: Grim 1.00 / 2.00 / 5.00 ticks per warp, clean; sim 2.00 / 2.00 / 5.00
  / 6.73 (gaps 7, 7, 6). Pacing never sends a warp before the previous landing's teleport accept.
- `62-argrim-correction` (2026-10-06, mod rule "a correction never stops anything"): `/ar add walk` sprints along +z and the
  server moves him a block back twice, 8 ticks apart (`execute as @p at @s run tp @s ~ ~ ~-1`, the same position packet a
  setback is). The route must keep running, finish the walk, log `[Correction] Auto Routes:` per move, show a chat line and
  play the alarm (`ModSounds.correctionAlarmsPlayed`). The rest of the suite must report NO correction: a report during
  pingpong/chain/path/imwarp would be the route mistaking its own landing for one (mod no-stop-corrections: none).
- `62-argrim-autoclear` (2026-10-06): Auto Clear's clearRoom with the Hyperion on three `dungeon.starred` mobs (open, inside the
  middle wall, behind it); the server emulates Wither Impact (`HxAbilities.witherImpact`: 10-block dash that settles at his
  height on a slightly-downward look, as the sim and Hypixel do - the plain transmission walk stopped dead there and every
  hop "never landed" - then a 6-block blast). Requires every mob dead, a landed hop, no entity packet. Clean on mod 27c52408.
- `62-argrim-1020-autoclearaura` (2026-10-20, mod ac-aura-memory-1020): the same three mobs with four chests (one at eye
  height on the start-to-first-mob line), two levers and a ring of floor levers round each mob, Secret Aura on. While Auto
  Clear kills: no `[SecretAura] click sent`, no "[AutoRoutes] secret click sent", every lever off, no chest or lever in
  the run memory, and the aura's "Auto Clear is killing mobs" hold must appear (the first version had no ring: Auto Clear
  hopped off the start inside the aura's 150 ms first-seen wait, nothing was ever held, a vacuous pass). use_item_on is NOT
  a secret click here: each etherwarp sends its right click's block half on the stone first (2 in a clean run). The aura is switched off inside onDone, in the tick the run
  ends. An item summoned at his feet mid-kill must be remembered by RouteRunMemory "in room <room>" with "Auto Clear
  running=true". Then chest A is opened by hand, and a WALK start node with await:1 4.5 blocks from it must log "await
  counts chest at ... already got this run" and "await satisfied at once" and run to its end with no click.
- `62-argrim-awaitskip` / `96-ar-awaitskip` (2026-10-06, mod ar-await-skip): a real left click (TestInput through
  `keyAttack`) while an await:1 etherwarp waits with no secret must fire the warp - one use, the server's etherwarp
  count +1 - with no swing and no player_action in the window, GrimAC silent. A control click with no route first must
  show a swing (3-tick hold: 4 swing + 4 player_action), so a clean window means the click was swallowed, not lost.
  A use that succeeds client-side swings the hand in its own tick, so a swing beside a use_item is the warp's; only extra
  swings count against the skip. Both cases also skip a crypt node's kill wait (no player_action). 96-ar-awaitskip adds a
  still pig: a mod-style dig + swing + attack straight through the game mode must NOT skip, and his click with the pig
  under the crosshair must (no attack packet).
  96-ar also has `-leverwp` (Secret Waypoints hides a clicked lever, not its neighbor) and `-complete` (`await:`).
- `62-argrim-1015-cryptaim` (crypt-aim-1015): two unstarred crypt mobs as Hypixel shows them (`dungeon.starred` with a
  `name`, Hypixel's "(sword) Crypt Undead 512.6k(heart)") 9 blocks either side of a Spirit Sceptre crypt node. The server emulates no
  sceptre, so nothing dies: two held uses down, then exactly two aimed `use_item` per mob (by the node's log, per mob id),
  the node moves on, use key up, GrimAC silent. Main d1eda658: never aims (fails); branch: clean.
- `62-argrim-chain`'s 20/s pace is the SERVER's answer time (fullclear-1016): on a busy machine the same jar read 1.00 and
  1.18-1.55 ticks per warp an hour apart (base fcba4045 too, over four commits, perf-lock "clean" every time), and in
  every slow run each warp still went on its landing's tick (`landingToUse` all 0) - the gaps of 2 were landings the
  dedicated server answered a tick later. The case prints each use's round trip (`roundTrips`) and judges 20/s against
  it; `firesOnLanding` is what catches a route that waits.
- `62-argrim-1016-autosecret-key` / `-autopilot-key` (grim-post-autopilot-1016): Auto Secret / Autopilot started by his
  toggle key (read at END_CLIENT_TICK), holding the empty slot 3, once `TripCache` has the trip to the start node from his
  spot, so the first trip runs precomputed and swaps to the AOTV. GrimAC Post flags a held-item change after a tick's
  `client_tick_end` only when a transaction answer follows it before the next tick packet, so on main the pair flagged one
  in two; every autosecret/autopilot case now also requires `PacketTrace.sentOutsideTick()` empty, which is the same fault
  without the luck. Both cases require the precomputed run and a `set_carried_item` (else nothing was tested).
- **The positive control's own flags are not the case's.** Every session prints `Simulation (x1) 5.99...`, `Simulation (x2)
  5.7` / `5.99` or `GroundSpoof claimed false` around its two controls; on 2026-10-16 a `62-argrim-autopilot` failure was
  first read as "Simulation 5.999436" from that line, while the case's flag was `BadPacketsJ` (time it against the case's
  PASS/FAILED lines, or read the case log's own flag list).
- `62-argrim-1016-oneuse` (grim-autopilot-flag-1016): Auto Secret's precomputed trip from rel (13.5, 11.5), a block and a
  half from the middle wall, with an unpaired Dungeon Breaker click on the floor first. Its first hop turns first, then goes
  onto the wall top with its block half (the ray meets the wall), which pairs the click; on mod main 098b6c92 the second hop
  then went from the same tick's `sendNow` slot - two `use_item` with two looks before one movement packet, BadPacketsJ
  every run (62-argrim-autopilot drew it about one run in seven). `harness/UseRotationWatch` replays GrimAC 2.3.74's
  BadPacketsJ on the outbound packets (the teleport answer is not a tick packet; a tick end is one when no movement went), and
  every autosecret/autopilot case and this one require it empty. Requires the precomputed trip and the block-half line.

# Auto Routes on GrimAC (62-argrim)

`./run-scenario.ps1 -Scenario 62-argrim -Port 25751 -ModUnderTest <cheat jar> -Extra "-PtestVolume=0"` (add
`-Minecraft 26.2`), about 2 minutes. One Session, 16 cases, positive control before and after. The room is one of his
captured rooms sent through Hx `dungeon.paste` to grid cell (4,4) and identified by the mod's REAL world scan; the
abilities are `hx/dungeon/HxAbilities` (etherwarp, Instant Transmission, Superboom), off unless `dungeon.abilities`.
- Put the Catacombs sidebar up only once he stands in the room: the world spawn is inside F7's boss box and a dungeon
  entered there latches "boss room", which turns room matching off (no room is ever identified).
- An etherwarp START node reached by walking fires at the ring's edge and a long warp then lands a block short; reach
  one with a hand etherwarp (`warpOnto`). Aims for clicks must also tolerate the ring-edge offset.
- A harness click must LOOK at the block first (a turn, then the click on the ray's hit): a synthetic centre hit while
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

# Auto Routes on GrimAC (62-argrim)

`./run-scenario.ps1 -Scenario 62-argrim -Port 25751 -ModUnderTest <cheat jar> -Extra "-PtestVolume=0"` (add
`-Minecraft 26.2`), about 2 minutes. One Session, 12 cases, positive control before and after. The room is one of his
captured rooms sent through Hx `dungeon.paste` to grid cell (4,4) and identified by the mod's REAL world scan; the
abilities are `hx/dungeon/HxAbilities` (etherwarp, Instant Transmission, Superboom), off unless `dungeon.abilities`.
- Put the Catacombs sidebar up only once he stands in the room: the world spawn is inside F7's boss box and a dungeon
  entered there latches "boss room", which turns room matching off (no room is ever identified).
- An etherwarp START node reached by walking fires at the ring's edge and a long warp then lands a block short; reach
  one with a hand etherwarp (`warpOnto`). Aims for clicks must also tolerate the ring-edge offset.
- A harness click must LOOK at the block first (a turn, then the click on the ray's hit): a synthetic centre hit while
  facing elsewhere is GrimAC RotationPlace, and it is the harness's flag, not the mod's.
- Grim's check classes are in its jar under `common-*.jar` (nested twice); javap them from a SHORT path - the
  scratchpad path is too long for javap on Windows.

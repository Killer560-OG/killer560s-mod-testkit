# Per-scenario lessons

Split out of [CLAUDE.md](../CLAUDE.md) on 2026-10-06 to keep it under its size limit. Problem, then fix; verified only.

- **1017-sim-quiz-timing / 1017-sim-quiz-autosecret** (SimQuizCooldownTests, mod quiz-cooldown-1017): the plan case
  needs Auto Close Chest on (his setting) - without it the Ice Fill reward chest's window stayed open and nothing moved
  for four minutes on the first run. Since mod quiz-entry-1018 walking in starts the Quiz: a lone Quiz room spawns him
  inside it (so it starts at spawn; the timing case re-arms with `SimQuizPuzzle.testRearm` and steps out and in), and
  room-relative spots (`rel`) must be worked out while the map still has him in the room - outside it they throw.

- 96-ar (`SimAutoRoutesTests`): an Auto Routes Go To is an Interactive Map warp, and after one only a START node may arm
  until he passes one (the map-arrival interlock). Cases that arm non-start nodes run before 96-ar-screen.
  `buildRoom` gives a room bigger than 1x1 all its map cells (Museum is 2x2; one cell left half of it outside and the live
  map never identified it). The sim's Spirit Sceptre is `BAT_WAND`, its SkyBlock id: giving "SPIRIT_SCEPTRE" left the crypt node with "no Spirit
  Sceptre in the hotbar" (2026-10-06).
  Blocks a sim Dungeon Breaker broke grow back 10 s later even after the next case's `arena()` cleared them, so they can
  land in that case's line of fire (96-ar-breakerwait's first etherwarp hit 96-ar-breaker's regrown row); and a drained
  breaker's lore reaches the client a tick after `SimBreakerState` changes, so wait for `loreCharges` before stepping on.
  Breaker edit mode picks only with the Dungeon Breaker held (mod ar-breaker-wait): select slot 2 before `rightClick`.
  A turn is judged on the CAMERA, `getViewYRot(1f)`, sampled every tick: obvious mode turns only the body and holds the
  camera (ViewFreeze), so `getYRot` reads a turn he never sees; and `tpRel` already waits 2 ticks, by which time a legit
  turn is mostly done, so a "before" read after it measured 22 degrees of a 180 (96-ar-mapopen-aim, 2026-10-07).
- `gameMode.attack` on a 1-HP sim zombie next to the player did not kill it (96-ar-crypt, 2026-10-05: crypts stayed put);
  the sim plays Mage, whose left click is a beam along the look, which is the likely reason (not traced). A Hyperion
  `gameMode.useItem` (Wither Impact, radius 5) does kill it - use that to kill a sim mob as the player.

- `menu.experiment` Superpairs takes `layout` (tiles from slot 9: `{item,name}`, `{powerup:true}` for Instant Find or `{powerup:"clicks",amount:N}`, no shuffle). Powerups behave as killer560 described Hypixel's (2026-10-05): turning one over costs a click and never touches the turn (the open tile stays up); an Instant Find arms the next click, and they stack (`armed` in the state); an armed click claims that tile and its partner, closing the turn if the partner is the open tile. Cases 284-287 cover those rules. The state lists every claimed pair (`claimed`), the server-side truth 229 asserts on. `clicks` sets "Remaining Clicks" (the solver reads it since mod 5d579534); 280-283 play fixed boards with a tight budget and assert what was claimed and in which order (`ExperimentCases.play`). Main's solver before 5d579534 fails 229 and 280-283.
- 288-menu-exp-etable-defaults / 289-menu-exp-etable-click-spacing (`EtableDefaultsCases`, mod etable-defaults-1015): 288
  writes the experiments config FILE and runs the mod's own `load()` (no file, old defaults, changed values, zero and garbage
  values, setters, save + load), restoring the file afterwards; do it outside a `MenuKit.Cfg`, whose undo holds the OLD
  instance. 289 measures click gaps in ms at the client's send (`PacketWatch.containerClickMillis`, recorded with or without a
  watch): server tick gaps are too coarse on a loaded machine (13 then 6 ticks for two 400 ms gaps). Main: defaults 200 ms,
  Click Delay 400 + Random 500 down to 167 ms, an old zero-delay file 32 ms; branch: 447/467/199/435 ms minimum.
- The sim's `/goto` (SimTeleportCommands.goTo) scanned the ServerLevel from the RENDER thread; in the gametest lockstep a
  chunk load there deadlocked the client (99-sim-im, 2026-10-05, jstack). Fixed in mod b5eee0d6 (server.execute);
  `97-sim-goto-loop` freezes on any jar before it. 99-sim-im still places the player itself (`standOn`). A sim scenario that reads the legend's Extra Info on a jar before mod hud-fixes must turn Score Calculator on
  first - it is off in a fresh config, and the section only drew with its estimate. From hud-fixes the estimate is
  tracked whenever either map's Extra Info is on; 99-sim-extra-info checks exactly that with Score Calculator off.

- `InstaClearTests` (`-Pscenario=insta-clear`): 361-logic-insta-clear feeds fake outcomes to the mod's insta-clear rule;
  98-sim-insta-clear walks/teleports into sim rooms holding real starred mobs. The sim has no dungeon map item and no
  insta-clear rule, so room states are FORCED through `InstaClearTracker.testForceMapState` - it measures the recorder,
  never Hypixel. Read results per room (`testLastFor`), not `testLast`: other rooms' observations close on their own
  window in between. A room the test forces after he is already standing in it (the spawn) is recorded too.
  361-logic-insta-clear-v2 (v1 file migration, PASS_THROUGH) and 98-sim-insta-clear-live (his 2026-10-06 live F7 cases: a
  flip before the mobs are seen, real Interactive Map paths through rooms, Entrance) came with mod branch instaclear-live;
  each case runs even if an earlier one failed, so an old jar shows which ones the fix is for. The sim rooms hold NO starred
  mobs until a test spawns them, which is how "not seen yet" is made: flip first, spawn after.
  98-sim-insta-clear-live runs on a PINNED F7 (`LIVE_FLOOR_CODE`) and presses the map only after the FULL floor graph is
  warm: on a random floor the map sometimes had no warp chain from S to T at all (Silver Sword -> Bridges, 2026-10-06),
  and every map case then measured nothing. `TESTKIT_INSTA_LIVE_CODE=random` plans a fresh floor and prints its code.
- `ModUnderTest.staticCall` reports a missing method as an AssertionError, not a RuntimeException: a `catch
  (RuntimeException)` around an optional hook let an old jar's run die at setup, so its "fail on main" said nothing
  (98-sim-insta-clear-live, 2026-10-06). Catch `RuntimeException | AssertionError`.
- A generated sim floor has NO wither doors (SimWitherDoors only draws theoretical ones), so nothing on it is ever
  "behind a closed door". 102-sim-autosecret makes one: it sets the live map's `grid` tile to DOOR_WITHER by reflection
  and puts coal on `DungeonLayout.doorBlock`, which is what the layout reads as locked.

- 62-argrim: the autopilot case walks him on to the arena's other (unidentified) map cells and stops there, where
  `RouteCoords.Frame.current()` is null, so every later case died "frame is null" (imwarp, imwarp-run, goto; old mod code
  too - 2026-10-06). A real player in an unidentified room has no frame either, so not a mod bug. `resetRoutes` now calls
  `ensureInRoom` (server `tp` back to the pad, wait for the frame). Setup also failed once to identify any of 8 rooms; a rerun passed.

- A screenshot camera must not be a spectator: spectators draw invisible entities as translucent ghosts, so 143-sim-key-look's
  first pictures showed the key's armour stand under its head (2026-10-06). Use survival with `mayfly`/`flying`, and set
  `flying` again on the client after each teleport - survival flight drops the moment he touches ground. A roofed sim room
  stays dark at noon and full gamma; 143 switches the mod's Fullbright on for its shots and restores it.
- 141-sim-autopilot re-shuts the sim blood door by putting its blocks back (`SimDoors` keeps a door registered once open);
  142-sim-autopilot2 wants `TESTKIT_SIM_INSTANCE=Map Logger` and drops keys 25-100 blocks off (further is off the client).

- 65-join-fingerprint: vanilla's server opens a sign editor only when every line is plain text (`SignBlock.hasEditableText`),
  so a right-click on a translate/keybind sign never opens it; the client's `handleOpenSignEditor` has no such gate, which is
  what anticheat probes rely on. The scenario sends the packet with the testmod's `testkit opensign x y z`. `WireCapture`
  (mixin on `PacketEncoder.encode` TAIL) is the only hook that sees handshake and login packets too. Run `-NoMod` first per
  Minecraft version (writes `build/join-fingerprint/nomod-<mc>.txt`); a -NoMod run exits 1 because Ap3RuntimeTests and
  GrimAutoRoutesTests require the mod before filtering - read the scenario's own PASS line. Grim's transaction pings arrive
  as ~15 `minecraft:pong` a second, counted not listed. On 26.2 the gametest API adds `fabric-client-gametest-api-v1:gametest_sync`
  to the register lists; it is the harness, not the mod, and is in both runs.
- Features gated on `CheatUtils.isOnDungeonServer` (Wither Doors and the like) never run in the UI suite's singleplayer world:
  `getCurrentServer()` is the connection's `serverData`, null there. 390-ui-wither-doors-fill sets that protected final field
  of `ClientCommonPacketListenerImpl` by reflection to a "mc.hypixel.net" ServerData (same name on 26.1.2 and 26.2) plus
  `DungeonState.setRoomSim(true)` (in a dungeon, not the boss), and puts both back. Its first run teleported a player who
  was not flying and photographed the door's underside while passing; set flying before the teleport and assert the eye
  height at every shot (2026-10-07). That eye check then failed 2 of 3 ui suites the same day (eye y 68.92 / 68.36, not
  70.62, the same at every shot): he lost flight during the up-to-400-tick wait for the door's blocks, dropped, and the
  `flying = true` after the wait left him hovering lower. 390 now sets mayfly too and places him again after that wait.
- `menu.anvil` (hx/menu/HxAnvil) is Hypixel's Anvil from the wiki's Anvil/UI template: inputs 29/33, Combine Items 22, result 13.
  It combines ANY two items and records each as `anvil.combine {left,right,result}` (books merge enchant by enchant, equal
  levels +1, no cap), so a wrong pair the mod sends shows up server-side instead of being refused. `claim:"direct"` puts the
  result straight into the inventory, `preview:false` hides the slot-13 preview, `previewOverride` fakes a wrong one; whether
  real Hypixel previews or how it hands the result over is NOT verified. Cases 292-297 (`AnvilCases`), selected with
  `-Scenario "200-menu-session:,-menu-anvil-"`.
- 392-ui-blood-camp drives Blood Camp with REAL move packets: armour stands wearing a blood-mob skull (`summon ... equipment:
  {head:{... "minecraft:profile":{properties:[{name:"textures",value:...}]}}}`) moved by server-side `setPos` beside a zombie
  wearing a Watcher skull. Difficulty must be EASY for the run (peaceful discards the zombie). The tag accessor on 26.1.2 is
  `Entity.entityTags()`, not `getTags()`. A muted client reports no played sounds to `SoundEventListener`s, so the sounds are
  counted by the mod (`BloodCampFeature.countdownStartSoundsPlayed/killSoundsPlayed`), like 64's alarm.
- 87-chat-tidy reads the chat WINDOW (mod `ChatTidy.testChatLines`), and offline runs drop the mod's "[ModChat] Relay unavailable"
  notice into it at random moments: the first run failed "newest line is the /say" on that notice (2026-10-07). Filter `[ModChat]`
  lines before asserting which line is newest. Lines are sent with `tellraw @a {"text":...,"color":...}`, the system-chat path.

- 393-ui-stat-bars (Health and Mana Bars tab, mod bars-tab): its "no Scale control" check first passed on the OLD jar too,
  because the old tab kept its Scale sliders inside closed dropdowns and only under readouts that were on (2026-10-07).
  A "this control is gone" check must read a layout where it would exist: every readout on from the config, every section
  header opened. Old-save size check: `TESTKIT_BARS_EXPORT=<dir>` on the old jar writes its config and the measured bar
  (`bars-tab-export.properties`); `-Extra @('-PseedConfig=<dir>')` on the new jar compares pixel for pixel. Run it alone,
  since earlier ui cases (310, 320, 330) rewrite config.
- A text run's `GuiTextRenderState.bounds()` is its glyph QUADS, which overhang the drawn pixels: 2-3 units right of the
  advance on every text HUD element (6 on one), so 394-ui-hud-boxes' first run read every correctly sized text box as
  spilling (2026-10-07). Measure text with `TextRuns.box` (advance width x line height through the pose); its fields are
  private on 26.2, so `TextRuns` reads them by name.

- 236-menu-partyfinder-style (`PartyFinderStyleCases`): for each Party Finder style it compares the REAL tooltip (`getTooltipFromContainerItem`, through the mod's mixin) with the settings preview (`renderPreviewLines`) colour run by colour run, after putting the preview's own stats into `PartyFinderStatsApi.CACHE`; and, with no stats (offline = what Hypixel's stats service gives on 2026-10-07), that every member line is still styled and draws 0x555555 brackets. Highlight is judged by pure green/red pixels per slot against a highlight-off frame. Fails on mod 0b492828, passes on pf-overlay 2aecfd9a.
  The selected dungeon class (`PartyFinderOverlay.currentRole`) is session state: 234-menu-dungeonclass-select leaves it
  on MAGE, slot 10's roster has a Mage, and the mod rightly marks it DUPE_CLASS - so in the full menu suite slot 10 drew
  red (2026-10-07 sharded run). 236 now clears it for the case and restores it.

- 397-menu-petwheel-instant times /pets against `FrameClock.tickCount()/frameCount()` (always-on counters, added for it) read
  just before a `TestInput` release; in the lockstep nothing ticks or renders between that read and the input task, so 0 ticks
  and "sent inside the input task" means the release itself sent it. `TestInput` fakes `InputConstants.isKeyDown` only, NOT
  `glfwGetMouseButton`, so a raw-polled bind under test must be a keyboard key. Wait a tick after `setCursorPos` before a
  release: a screen that resolves the hover from its last DRAWN cursor otherwise reads the stale centre (the old jar picked
  nothing on the first run, 2026-10-07).
- 396-sim-smooth-tp (`SimSmoothTeleportTests`, mod smooth-tp): anything timed in WALL time (the camera glide) needs the test
  thread to sleep between `waitTicks(1)` calls - in the gametest lockstep the client renders one frame per tick as fast as it
  can, so a 400 ms glide otherwise spans an arbitrary number of frames. It sleeps 20 ms a tick (about 30 frames per 400 ms)
  and samples `Camera.position()` in `LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES` (the accessor is `getMainCamera` on
  26.1.2, `mainCamera` on 26.2, so by reflection). `chunk_batch_received` differs between lanes with chunk loading; leave it
  out of any packet-equality check. Fails on mod batch-1007 (no config class, camera jumps 12 of 12 blocks in one frame).
- 395-ui-map-heads (`ui/MapHeadCases`): teammates in a singleplayer world are a `RemotePlayer` added to the client level
  (v4 UUID) plus, for one with a skin, a `ClientboundPlayerInfoUpdatePacket` (ADD_PLAYER, entries set by reflection) fed to
  `handlePlayerInfoUpdate` on the render thread. A head is proven by an 8x8 grid in the marker matching the skin texture's
  face+hat (read from the resource the client holds) on 56+ of 64 cells. A map arrow (base as wide as it is tall) is
  nearly equilateral and easy to misread by eye: its point is the vertex opposite the SHORTEST side. Measure the
  heading rather than eyeballing a screenshot. The single-sim-room part publishes `FlatTestRoom` through `SimBuilder.publishSingleRoomMap` with
  `SimState.active` set by reflection, then resets the grid; it fails on mod 6f3515ba (fit 7.25, a 145-unit cell).
  Two isolation traps (2026-10-07 sharded run, where `100-hx-session` ran before the ui suite in one client): the party
  tracker kept HxMateA/HxMateB from the hx cases, so the map (correctly) drew only those and none of the case's
  RemotePlayers - 395 now empties `PartyTracker.MEMBERS` for the case and restores it. And with `SimState.active` set,
  `SimSidebar` writes its "SKYBLOCK" objective `k560sim` into the UI world; switching the flag off by reflection skips
  the sim's teardown, so `SkyblockGate` read the UI world as Skyblock and 401 (next in the suite) drew the LIVE board.
  395 now removes the objective and its `k560t*` teams on the server and puts the gate's verdict back.
- 400-menu-partyfinder-stats (`PartyFinderStatsCases`): the Party Finder's member stats from a loopback HTTP fake that
  the case starts on server port + 6 (`testkit.fakeHttp.port`). build.gradle points the mod's `pv-backend`, `minecraftservices`
  and `mojang` services at it on every offline run (under `/pv`, `/mcs`, `/mojang`) unless `-PnetOverride` names them;
  nothing listens there outside the case, so other cases see the same ConnectException as offline. Fixtures are REAL
  SkyBlockPV-backend answers (AntsRNG, celybispuppy, Elysianz1, 2026-10-07) trimmed to the dungeon fields, in
  `src/gametest/resources/testkit-http/` - not `testkit-fixtures/`, whose files must follow SCHEMA.md. The mod's
  `LOOKUPS_STARTED` counter spans the session (236 starts lookups too): measure a delta. 236 skips the "? = no stats for
  <name>" footer when collecting member lines. Fails on mod 6f3515ba (all ?), passes on pf-stats a6e69519.
- 401-ui-scoreboard-editor (`ScoreboardEditorCases`, Custom Scoreboard lists, mod scoreboard-editor): real mouse input on
  the mod menu needs the target ON SCREEN first. Custom Scoreboard is many sections down the New folder, so the first run
  pressed at y 1524 of a 480-unit screen and the list never saw it; `open` now scrolls the menu until the tab's switch is at
  the top of the pane and `listVisible` fails the case if the list is outside the visible band (2026-10-07). The list keeps
  a wheel turn only while it can still move, so a test that wheels "plenty" also scrolls the page under the list; wheel one
  notch at a time until it reports its end. Cross-jar migration: `TESTKIT_SCOREBOARD_EXPORT=<dir>` on the old jar writes
  the old jar's own save plus the lines it draws; `-Extra @('-PseedConfig=<dir>')` on the new jar compares line for line.
  The drawn board is `CustomScoreboardFeature.drawBoard` over `previewLines()` (the UI world is not Skyblock). That
  holds only while `SkyblockGate.isOnSkyblock()` is false: on Skyblock `previewLines()` is the live board, which in the
  UI world shows only the title, footer and whatever extra data is cached (the "Cookie Buff" line). 395's leftover sim
  sidebar did exactly that in the full ui suite (2026-10-07); 401 now names the sidebar and stops if the world reads
  as Skyblock before it starts.
- 399-ui-hud-editor-resize / -snap / 399-ui-stat-bars-vitality-xp (`HudEditorCases`, mod hud-editor-bars): real drags are
  `setCursorPos` (GUI x * screenWidth / guiScaledWidth - the HUD editor is not auto-scaled) + `holdMouse(0)` + six moves +
  `releaseMouse`. `TestInput.holdAlt()` is seen by `InputConstants.isKeyDown(LEFT_ALT)` (the mod's Alt free-drag passed with
  it), although `pressMouse` events carry no modifiers. Only the case's own elements are kept in the editor's `shown`, so a
  default-on element elsewhere cannot be what a box snaps to. The cursor is read twice: `pendingCursor` of a frame extracted
  with the mouse at a handle, and the window's private `currentCursor` after real frames. An action bar reaches the mod's
  MODIFY_GAME through the real packet with `ServerPlayer.sendSystemMessage(Component, true)` on the integrated server.
- 96-ar-398-* (mod ar-node-proc, killer560's 2026-10-07 Museum log): `-offnode` stands him in #10's ring at spots the
  recorded look misses #11 from, one where the mod's etherwarp aim finds a ray and one where none exists, standing and
  dropped in from 0.6 up; `-regrow` loads Museum his way (`SimBuilder.buildSingleRoom`), breaks, rebuilds and breaks again so
  a DELAY await on #7 fires after the first run's 10 s regrow is due (route of 3 nodes, so #7 logs as "Node #3"); `-startawait`
  presses Go + Secret from his own spot with Secret Aura and Auto Close Chest on. All three fail on mod 6f3515ba. Two traps
  found writing them: the sim's first aura click on a chest right after a map warp does not always open it, and the aura's
  retry a second later is counted, so "await met" alone passed on the old jar - assert it was met within 10 ticks of the
  start. And wait for the node's own "acted" line, not any landing line: the previous sub-check's stack can still log one.
- 310-ui-config-file-roundtrip flips EVERY boolean, one-shot markers included, so a flipped `legacyAlertsMigrated`
  re-runs Score Calculator's migration; it is order dependent because it starts from whatever earlier cases left in
  memory (141/142 turn Score Calculator on and never restore it). Reproduce it alone with
  `-Extra "-PseedConfig=<dir>"` holding a killer560smod-scorecalc.json of enabled=true (fails on mod bb103351).
- Mod.field on a field the mod deleted throws inside waitUntil, and the case reports a TIMEOUT with the real "[mod] no field" only as a Caused by. 099/135 waited on
  PlayerStatsFeature.health, a String that went with Classic Display (mod a302cab2); read player stats through
  HxKit.playerStat, which formats the numeric healthCur/healthMax/manaCur/manaMax/defenceValue the bars use.

- Sim scenarios after real-server ones (2026-10-07): 60/62/81-83 run the live map in a dungeon sidebar with no room database
  copied, and offline each failed load doubles the backoff (30, 60, 120 s); 77 and 110 then copied the files and timed out
  waiting on a load the mod would not try for two minutes. Reproduce with `60-secret-triggerbot,62-full-block-reach,
  81-reach-at-5-blocks,82-reach-at-6-blocks,83-reach-at-7-blocks,77-lever-scan,110-sim-puzzle-reset` (fails on 0b492828 and
  4774ebd8 with the old testkit). Start the load with `Scenario.loadRoomDatabaseNow()`, once, after copying.
- 98-sim-insta-clear's walk placed him on the column's LOWEST standable block, a lower level of room A on some floors (9 and
  52 blocks under the doorway), and the walk went 0.2 blocks into a wall. `placeNear` searches down from the door's height.
- 98-sim-insta-clear-live builds its pinned floor by reopening the same sim world, and until mod fix-sim the previous case's
  starred zombies came back from disk into rooms of the new floor (U and Z read 2 stars). A U/Z failure with "stars":2 is that.
- 89's "1 spawned, 5 starred" was 96-ar-mimic's five mimics surviving into a later floor: 96-ar called `SimState.leave()` right
  after the world closed, before the mod's own deferred unload reset (likely; it now waits for `isActive` to go false), and the
  mod's builds kept the starred set until fix-sim. Reproduce with the shard's order `70-sim-flat-room,73-sim-floor-shape,74-sim-run,
  76-sim-secrets,79-sim-map,143-sim-key-look,96-ar,63-ap3-session,80-sim-playable,84-sim-floor-sizes,88-sim-server-safety,
  89-sim-starred-mobs`; `96-ar-mimic,89`, `96-ar,89` and `96-ar,88,89` (once each) did not reproduce it.

- 131-sim-auto-clear (`SimAutoClearTests`, 2026-10-07 flaky-autos). The "correction" case teleported him two nested
  `server.execute`s after the start, which lands anywhere from Auto Clear's own decide tick to the middle of its etherwarp
  trip; in the trip it was the Interactive Map runner's to judge, and Auto Clear neither counted it nor, when it landed
  while the trip was being PLANNED, did the runner call it a correction (mod fix, see the mod's LESSONS-AUTOMATION). Now
  three sub-cases: `correction` (a tick after the start, hops on), `correction-trip` (hops off, once the runner is busy)
  and `correction-plan` (hops off, sent with the start - the failing timing; fails on 799c5c2f). Each move goes beside
  or behind him (90+ degrees from the zombie: over 2.3 blocks from any hop's line, outside Auto Clear's own-landing band),
  inside the same room, onto a spot the map's planner can path from (twice a spot by a wall had "no way" to a zombie 13
  blocks off and the rest of the run stalled), and must reach the client while Auto Clear still runs; at a room's edge
  with nothing of the room behind him, he is first put in its middle. The log says which check caught it.
- 131 "hop": from a carpet, slab or stair top the mod's dash model (`SimAbilities.dashTarget`, the sim server's too)
  finds no landing at all, so Auto Clear rightly plans no hop (diagnosis line: "straight dash lands null"); case 3's
  etherwarp had left him on a brown carpet. The case now puts him on a full block of the room first, one with an open
  floor spot 9-16 blocks off (Silver Sword had none from where case 3 left him). Whether Hypixel dashes from a part
  block is not measured.
- 131 "door": a room filled in "through Entrance" has no ordinary door, so `SimDoors.witherDoorsAround` turned none
  (Carpets, 0). The case now takes the first mob room whose every door is ordinary and checks it is shut off on the map.
- 102-sim-autosecret: the main run stopped as soon as Auto Secret's phase read DOOR, which starts while he is still on
  his way; the no-key line came after (2.0 blocks short). It now waits for that line. Rooms named Maze, Boulder or Trap
  are never routed or used as the insta-clear landing: the mod starts no path from inside one past its start
  (`AutoClearUtils.canPath`), so Auto Secret waited there for the rest of the run (Arrow Trap, about one run in three).

- 404-sim-planner-* (`SimPlannerGapsTests`, mod planner-gaps): judge a planner "no way" against a ground truth before
  calling it a bug - breadth-first over real etherwarps cast on the SERVER's level with `TeleportUtils.traverseVoxels`,
  landings kept to the mod's own rules (cover, `LevelEtherGrid.blackListed`, no warp on from a trap, maze or Boulder).
  Without those rules the first sweep read 87 "reachable" no-ways; 83 were New Trap (no etherwarp from a trap). The two
  131 dead spots of 2026-10-07 are a one-block floor in the open seam between two rooms' walls, sealed except to the
  roof: "no way" is right there, and 131's `pickCorrection` already avoids them. `-gaps` and `-sweep` share the pinned
  F7; `-cracks` builds its own floor and must be named alone - a second floor built into the open sim world never
  warmed its graph (quick and full graph silent for 90 s).
- 406-menu-partyfinder-stats-coverage (`PartyFinderCoverageCases`, mod pf-stats-2): a menu of 19 REAL players
  (`testkit-http/partyfinder-stats/coverage/`, real SkyBlockPV answers of 2026-10-07 trimmed by
  killer560s-mod-logs/pf-stats-2-data/make_fixtures.py, which also computes the expected lines) through a fake that
  lets 3 profile requests through per 3 s window and 429s the rest with Retry-After - the real backend's shape, scaled
  down (real: 3-4 pass, then "Retry-After: 10"). Also a forced 429 for SmhAuto, a no-selected-profile and an
  empty-selected-profile shape, meowinging (no dungeon data), an Entrance party and an M3 party with any-score-only
  times. It reads the mod's outcome log lines through `LogTap`; an old jar logged one PF line per SESSION, so after 400 it
  shows 0. Fails on 799c5c2f and a411e650 (2 of 19 players with stats), passes on pf-stats-2.
- 410-menu-partyfinder-relay (`PartyFinderRelayCases`, mod pf-relay): the relay path (`POST /pf/stats` on the fake's
  `/pfrelay`). The fake serves `relay-golden.json`, the RELAY'S extraction of the 22 fixture players (relay repo
  `scripts/pf-golden.mjs`; regenerate it when a fixture changes), so the relay-down step comparing the direct path's
  numbers with it field by field is the parity check between the relay's TypeScript port and the mod's Java. Count
  direct work with the mod's `LOOKUPS_STARTED`, not the fake's hits: the Profile Viewer keeps fetched profiles 5 minutes,
  so after 400/406 a direct lookup often never reaches the fake. 400/406 answer `/pf/stats` 404, which is their fallback.
  408-menu-partyfinder-stats-live runs a 25-player menu against the REAL backend: only with `TESTKIT_PF_LIVE=1` and
  `-Extra @("-PnetOnline")`; it prints coverage at 30/60/120/180 s and asserts nothing.
- 411-menu-partyfinder-speed / 412-menu-partyfinder-background (`PartyFinderSpeedCases`, mod pf-speed-1015): a 15-player
  menu against BOTH upstreams rate limited as his own logs show them, at 1/3 time: backend 3 per 10 s window then 429 with
  the window's Retry-After, first request for 6 players a 500; relay pending until its pump reaches the name (one per 9 s,
  first asked first, running whether polled or not) while promising 1.1 s per queue place. 411 times all-filled with the
  menu open (limit 60 s); 412 closes after 5 s, waits 60 s, and needs all 15 within 1.5 s of reopening. Both clear the
  Profile Viewer's own profile/name caches, or a direct lookup after 400/406/410 never reaches the fake.

- 407-ui-stat-bars-layout (`StatBarsLayoutCases`, mod bars-anchors): Health and Mana Bars' Layout. Geometry is measured on
  screenshots by exact colour (each bar its own opaque colour, Show Value and text shadow off) in SURVIVAL with XP level 5,
  so the hearts/hunger/XP rows really draw; the case puts the suite's creative world back with `SafeWorld.apply`. A real
  press in the mod menu (`ModScreenDriver.press`) must run inside ONE `c.onClient` task, as 393 does: from the gametest
  thread it reaches `Minecraft.getInstance()` and throws. Cross-jar old config: `TESTKIT_BARSLAYOUT_EXPORT=<dir>` on the old
  jar, then `-Extra @('-PseedConfig=<dir>')` on the new one (the case deletes the props file once it has compared). Since
  that mod branch a fresh config is Predefined, so 399's resize/snap cases switch to Custom before placing bars.

- 96-ar-405-* (mod ar-crypt-mapopen, killer560's 2026-10-07 Map Logger log). `-crypt-mapopen` plays his Museum stack from #10
  twice, no screen and with `InteractiveMapScreen(true)` open throughout, and compares the crypt node's hold (ticks, use
  ticks, gaps); on 799c5c2f the map pass sent 0 uses in 101 ticks. `-startwarp` does 20 Go + Secret warps to #1 from his
  spot (his mimic chest, Secret Aura, sceptre in slot 3, `/speed` 600 via `SimSpeed.set`, half with the map open): all 20
  pass on the old jar too - the sim never made his 1-block move - so 10 more tries make that move with a server `tp`
  during #1's await (rewritten as a 600 ms DELAY). The route must be stopped with Auto Routes OFF before he is put back:
  stopped on #2 it re-arms there at once, and the next press then logs "Stopped: Interactive Map" (count "Stopped" only
  after the arming).

- The gametest client runs at render distance 5, so on a whole sim floor the far rooms' chunks never reach it: a map
  press on them says "Couldn't find goal position" and a client-side scan finds air. Scenario 95-sim-map-warp sets
  `mc.options.renderDistance()` to 16 for its run and puts it back. A sim floor's entrance is also sealed until
  `SimRun.begin` opens the gate (see 81/95), so nothing outside it is reachable before that.

- The etherwarp graphs warm whenever the Interactive Map is on in a dungeon, and behind the sealed entrance a warm-up
  sees only the entrance, so 95's "press during the first warm-up" raced both ways (full graph warm by GO, or the quick
  graph missing its 600 ms at GO under load). 95 keeps the map off until the floor's chunks are in and the gate is
  open, on until the quick graph is warm on 3,000+ nodes, then off until the press. Toggle the map to hold a graph cold.

- 95's press line used to quote the LAST plan of a press, so a 21-warp trip refused at its last hop and re-planned twice
  read as "1 warp for 175 blocks" (2026-10-06). It now lists every plan, fails on any "[Sim] no etherwarp target there"
  (a planned hop the server refused - knife-edge aims, fixed in mod im-quickgraph) and on N warps covering more than
  N x 61 + 30 blocks, and asserts a tile press uses exactly the warps of the mod's own [check] plan without the
  centre preference (build.gradle sets `killer560.test.checkFewest`), printing landing depth before vs after.
  Floors are random per run and the refusals depend on the floor: judge 95 over 8+ runs, not one.

## Moved from CLAUDE.md (2026-10-07, when it became a guide for any machine)

## FPS benches (95-fps-bench, 403-perf-hub)

`perf/FpsBenchTest`, about 10 minutes, only when named (`-Pscenario=95-fps`). Commands, method and how to read the
ON-OFF delta: [docs/fps-bench.md](docs/fps-bench.md). `403-perf-hub` (`perf/HubPerfBenchTest`, ~18 min, only when named)
is the hub counterpart: tab list, entities, chat, an open chest and an Ender Chest page; read its TICK and `menu` numbers
(frame counts differ between ON and OFF, see the doc). 95 needs `Scenario.ensureRoomDatabase` like every sim floor since
mod 0ad55108 - without it the generate was refused and the bench hung on a build that never started (fixed 2026-10-07).

## Scenarios added after the upstream set

Scenarios so far: 62-argrim (Auto Routes on GrimAC, see below), 48-56 Breaker Aura (with a by-hand control and an open-ground speed control;
picks-only since mod 9e83c4fc, so 50-52 pick the whole corridor and 53-56 cover side, floor, behind and through-wall picks), 60 Secret
Triggerbot, 99-sim-essence-aura (Secret Aura on a sim wither essence holding AOTV / Hyperion, first world and after a
rebuild; server-side click record, collection and Auto Routes' await; only when named, captures from "Map Logger" unless
`TESTKIT_SIM_INSTANCE` says otherwise), 99-sim-aura-rebuild (Secret Aura on the same chest and lever of Museum over three
builds of the room in one sim world; server-side click record; fails on mod 5008e4d6, only when named),
143-sim-key-look (pictures of a dropped sim Wither Key and Blood Key, front-on and from above, plus their stand's
flags on the client; only when named, `TESTKIT_SIM_INSTANCE=Map Logger`),
110-sim-puzzle-reset (player reset rules of mod 35a663ba: only failed puzzles, never Water Board, Boulder when built, a draft kept when nothing resets),
99-sim-map-fit / 99-sim-extra-info / 99-sim-secrets-boss (`SimMapHudTests`: the Dungeon Map fills its frame on 4x5, 5x5,
6x6 and offset sim floors and Interactive Map presses hit the drawn room; Extra Info with Score Calculator off; Secrets
HUD hidden in the boss and back on a new run - all fail on mod 0b492828), 394-ui-hud-boxes (every HUD element's box vs
what it draws, slack 3 units, centred elements judged on their other axis),
399-ui-hud-editor-resize / 399-ui-hud-editor-snap / 399-ui-stat-bars-vitality-xp (HUD editor resize handles, cursors and
snapping by real mouse drags at Auto Scale 0.5 and 1; Vitality and XP readouts; Classic Display migration; all fail on mod 6f3515ba),
407-ui-stat-bars-layout (Health and Mana Bars Layout: Predefined areas measured on screenshots at Auto Scale 0.5 and 1,
real-mouse drags between areas and onto Hidden, Custom positions untouched, old configs stay put; fails on mod a411e650),
64-correction-alarm (the mod's `killer560smod:correction_alarm` is known to the sound manager, its .ogg is in the resources,
`ModSounds.playCorrectionAlarm` plays once and rate-limits the second; run it with `-Minecraft 26.2` too),
65-join-fingerprint (every serverbound byte from handshake to play plus a sign translation probe; needs a `run-scenario.ps1 -NoMod`
baseline per Minecraft version first, see docs/scenario-lessons.md).

- The mod's Auto Scale (ON by default since 2026-10-05) scales its HUD and its own screens by
  `3 * min(W/2560, H/1440) / guiScale`. The default gametest window (854x480, GUI 2) is factor 0.5: mod screens are laid
  out at `guiSize / 0.5` and saved HUD positions are baseline units drawn at `saved * 0.5`, so compare a dragged element
  with `HudElementRegistry.resolvePosition`, never with `HudConfig.getPosition`. `380-ui-autoscale` resizes the window
  (`TestInput.resizeWindow`, then `options.guiScale().set(3)` + `mc.resizeGui()` - the resize alone keeps the old scale).

- Both terminal layouts (SkyBlock 0.27.2): `menu.terminal` takes `bandRows` (Melody, 4 or 3) and `count` (Numbers, 14 or
  10); cases 207-210, 219, 290, 291. The P3 Simon Says device is `boss.ss.*` (hx/boss/HxSimonSays, rounds 4 or 5), cases
  501-503 in `500-boss-session`; click its buttons after turning to them, or GrimAC cancels the use (RotationPlace).
  A P3 terminal stand is `boss.term.*` (hx/boss/HxTerminalStand: pillar + command block + non-marker "Inactive Terminal"
  stand, opens "Click in order!" only for an eye at or above the stand's feet); case 409-boss-termaura drives the mod's
  Terminal Aura with it (the Terminal Open Logger it used to drive was removed 2026-10-07). Spawn the stand (`boss.term.spawn`) only once the player is there, and turn him through the
  `dungeon.tp` itself: a client turn made before the teleport lands is reset to the teleport's yaw/pitch (409's first runs).

- In the logic suite's throwaway world, a block 66 blocks from spawn can read `void_air` for the first seconds even after
  `mc.level.hasChunkAt` says yes (362, 2026-10-06). Wait until the block itself stops reading `Blocks.VOID_AIR`.

- 386's overlap sweep builds each tab as the fresh config has it, so rows behind an OFF master switch (all of Auto Routes)
  exist only in the "toggle flipped" layouts. 386 now records every label it checked and requires Auto Routes' Breaker
  Block Display/Style among them (cheat jar); a new-widget check must read those layouts too (2026-10-06).

- 411-ui-mining-shelved (`MiningShelvedCases`, mod shelve-mining): mining is shelved until after 2.0. The jar has no
  `com/killer560/hub/mining/` class, the menu tree no mining tab, `/profit` only the two non-mining cards. Fails on b7528f86.
- 412-ui-no-new-tab (`NoNewTabCases`): the New category is gone. `testkit-ui/new-tab-tooltips-<variant>.json` was recorded
  by 412 itself from b7528f86 (a jar that still has New writes the file to the report folder and fails); on a newer jar every
  former New tab must be in the tree and every row must show the same tooltip. Re-keying "new/..." tooltips is what it checks.
- 413-ui-testing-variant (`TestingVariantCases`): the mod's -PtestingBuild jars put every non-Home feature tab in an Untested
  folder; 413 presses the real "Mark tested" button, checks the tab lands in its category, survives a re-read of the marks
  file and is listed by `/k560tested list`. On a normal jar it checks none of that ships. 301, 380 and 389 know a testing
  jar's menu is different (leaf floor, fewer sidebar rows, Crosshair marked tested first).
- 416-418 (boss): AP3's Term Aura node with the Terminal Aura setting off - TERM_AURA + TERMINAL on one spot, terminal behind
  (416, must be clean, chain must reach the TERMINAL node and finish on the completion line, camera never moves) or in front
  (417), and behind without the turn (418, flags recorded). The case taps `PacketTrace` and notes the wire order; a turned
  interact sent before any movement packet reported the turn passed alone but never reached the server straight after
  415's Hitboxes flag. 421 Lever Aura (S2 section lever at its real coordinates, `forceload` first - a fill into an unloaded
  chunk silently does nothing and he fell 75 blocks), 422/423 Simon Says Auto Solve No Rotate facing away / facing the
  device (the server's ss.completed is the proof). `BehindAuraTests` 419/420: Secret Aura, lever behind / in front.
- 62-full-block-reach left Secret Triggerbot ON, and a sharded run that put it first in a shard failed 62-argrim-hand/-legit/
  -play: the triggerbot clicked each lever as the legit route turned the crosshair onto it (two use_item_on in one tick, the
  lever back off). Same on the old jar, so not a mod regression (2026-10-07). A shard reorders the k560 list: any scenario
  that switches a clicking feature on must switch it off, and a session that judges clicks switches the others off itself.
- 425-ui-bazaar-icons (`BazaarBrowserCases`, mod bazaar-ui) and 426-menu-bazaar-orders (`BazaarOrderCases`; 424, the old
  browser screen's layout, was retired with that screen on 2026-10-07 - 481 replaced it): 424 fed a synthetic bazaar answer for every id in the mod's bundled table through
  `BazaarApi.applyResponseForTest`, then reads the screen's own `layoutReport()` (every region, text and widget box of
  the last frame) in six views at four window/GUI scales; the screen records its texts, so a rectangle check needs no
  pixel work. 425 counts `BazaarIcons` sources (no Hypixel pack in a test client: 47 paper fallbacks on 2026-10-07).
  426 shows Hypixel's Manage Orders (`menus.bazaar-orders`, formats from SkyHanni/Skyblocker, not a live capture) and
  waits for the mod's TICK reader to store the orders; it also requires zero container clicks.
- 427-432-ui-pack-disabler (`PackDisablerCases`, mod pack-disabler): judge an item's look by what vanilla's own
  `ItemModelResolver.updateForTopItem` puts in an `ItemStackRenderState` - `pickParticleMaterial(...).sprite()
  .contents().name()` for the texture, and for a head the layer's private `argumentForSpecialRendering` (a
  `PlayerSkinRenderCache$RenderInfo`) for the skin hash. A test client has no Hypixel pack, so a Hypixel model reads
  `minecraft:missingno`: that is the "unchanged" control for OFF. To prove a player's resource pack wins, write a pack
  into `getResourcePackDirectory()` with solid magenta/green PNGs (same int in ARGB and ABGR) and read the sprite's
  `originalImage` pixels after `reloadResourcePacks()`; its pack.mcmeta format comes from the client's own version.json.
  428 sends the pack push through `handleResourcePackPush` on the render thread (no ServerData in singleplayer, so
  vanilla shows `PackConfirmScreen`), not over a socket. A new mod command root must go in deny-commands.json or 306
  fails; a new config class missing from `ProfileManager.reloadAllConfigs` fails 320 (both caught here, 2026-10-07).
- 441-447 (`HudFixCases`, mod hud-fixes): killer560's 2026-10-07 HUD report, each measured on a GUI-2/4 screenshot or the
  element's own text runs, with Auto Scale off so a unit is exactly guiScale px. With a screen open, judge "behind the
  screen" by the bar's RAW colour beside the panel (0 px) and by the ON/OFF diff under it, not by "changed" alone: a bar on
  Fabric's addLast layer is drawn past the dim (vanilla defers SUBTITLES and what follows into extractBackground) and still
  reads "changed". The survival inventory's player model idles between frames (~350 px of diff elsewhere), and the vanilla
  vignette shifts a HUD colour by 1-2 levels in survival, so exact-colour boxes are for creative frames. Map Extra Info
  text is read inside its opaque panel only: the map's player arrow and a wandering mob beside it changed between frames.
  446 injects a finished F7 run into `SplitTimersFeature.run` and sets `SplitLagClock.pingClockSeen`.

- 491-499 `-menu-ah-` (`menu/AuctionHouseCases`, mod ah-v2): the unified Auction House. The API data is real
  (`testkit-ah/`: 223 auctions trimmed from `/v2/skyblock/auctions` pages 0-1 and one `/v2/skyblock/auctions_ended`
  answer, curl 2026-10-07); `feedApi` moves every `end`/`start` to the same distance from now, or the mod's decode drops
  them as expired the day after capture. The AH menus (`menus/auction-house.json`) are NOT captures. 497/499 hold a page
  in its loading state by opening a container with NO items (the reskin never settles an all-empty menu), which is the
  only way to screenshot the carried/opening frame: a real page settles in ~110 ms. Browser listings sit in rows 1-4 x
  columns 2-7, so the first listing is slot 11, not 10.
- 481-489-menu-bazaar-v3 (`BazaarV3Cases`, mod bazaar-v3): the unified Bazaar screen. Its layout report
  (`bazaar.BazaarView.layoutReportForTest`) keys every hotspot - `hotspot <slot> x y w h <key> <name>` for a real slot,
  `hotspot <key> x y w h <label>` for an action (`tab:Woods_&_Fishes`, `product:WHEAT`, `recent:WHEAT`, `bottom:Manage_Orders`,
  `back`, `hypixel-menu`, `search`, `sort:BUY_PRICE_HIGH`; spaces become `_`). The layout is in Auto Scale's canvas units,
  for the reskin too, so a press converts with GUI scale x `hud.AutoScale.current()` (`BazaarReskinCases.toWindow`); 462,
  464, 465 and 467 pressed beside every hotspot until it did, because 462 leaves Auto Scale on. 486 judges a page switch
  from `bazaar.BazaarHud`'s frame log (one line per drawn frame: `screen|reskin <mode> <content> [frozen] items=N`,
  `vanilla '<title>'` for a chest drawn as Hypixel's, `none`/`other` with no screen or another one) - stopping the
  recording keeps the log. 485's probe HUD layers are the testkit's own, added at run time through Fabric's registry, so the
  mod's gate wraps them like any other mod's.
- 531-538 sim-fairy-door (Wither Doors' Fairy Door): a no-draw frame must be taken at the SAME moment as the frame it is
  compared with. 535 first diffed against 533's older off frame, whose chat lines had since faded and uncovered the Fairy
  room's own pink: 128 "magenta" pixels with nothing drawn (2026-10-08). Each comparison now takes its own off frame.
  When the room after Fairy is the one the Blood door opens from, the mod says "already at the blood door", not "avoids".

- 581-587 `-ui-` (`ui/InvBatchCases`) and 583/585 `-menu-` (`menu/InvBatchMenuCases`), mod inv-batch (2026-10-08). 581 counts the
  Item Protect star's exact colour pixels in a protected slot's top-right corner, cell by cell against `ItemProtectFeature.STAR`,
  and 0 on a starred-only item (Auto-Protect Starred on, so the "no marker" is not vacuous). 582: Slot Lock gone from the API,
  tab and tooltips; an old file's locked slots become protected UUIDs on the next tick (a slot without a UUID is named, not
  carried). 583 runs every action Slot Lock blocked against the menu server and counts `container.click`s, each with an
  unprotected control that does arrive; give the server ~20 ticks to apply a control drop before `give` puts the item back,
  or the give lands first and the drop empties the slot again. 584/586 drive the layouts menu and Custom Items with real mouse
  and keys at the screen's Auto Scale factor. 585 reads the sorter's clicks server-side: a hotbar destination is ONE `SWAP`
  (button = hotbar index), others `PICKUP`; gaps are server ticks, so allow +-1 per gap. Set `TESTKIT_INVBATCH_SHOTS` to copy
  the GUI 2 screenshots out. 472-menu-storage-scanall-button fails on main be6452a6 too (not this branch).

- 571-580 (`hx/HxModChatPartyCases`, Mod Chat + Party Commands, 2026-10-08). Relay callbacks are driven through
  `relay.RelayClient.listener`; methods Mod Chat no longer overrides (onPresence, onConnected) are only reachable through
  the `relay.RelayListener` interface, so `Mod.call` on the listener object says "no method". 577 times the reply from the
  client's own receive event to `PacketTrace.tap` seeing the `pc` packet (wall clock); its receive hook is registered after
  the mod's, so the measured gap can only be shorter than the real one. Party Commands' rate limiter (2 s between any two
  commands, now shared by the old info replies) outlives cases: `HxSocialCases.resetPartyCommandLimits` before each send.
  579 serves Mojang's `/player/certificates` and the relay's `/challenge` + `/auth` from one loopback fake on 28950, points
  authlib at it with `minecraft.api.{services,session,profiles}.host` (all three or authlib ignores them) and the relay with
  `killer560.net.relay`, and restores Minecraft's user/userApiService/profileKeyPairManager fields afterwards.

- Integration of 2026-10-08 (mod 3744aa32, testkit integ-fix). 511/513 judge the menu against the 6a61dfa8 fixtures plus
  `testkit-ui/menu-layout-merged.json`, which lists what the branches merged beside menu-layout changed on purpose (tabs
  gone/added with their path, tooltips changed/moved/removed, each with its commit); a listed change that no longer differs
  fails as stale. When a branch renames a tab or rewrites a tooltip, add it there, not to the 6a61dfa8 fixtures. 569/587 wrap
  their walk in `MenuLayoutCases.allTested` like 511-514, or a -PtestingBuild jar shows their tabs only under Untested.
  553: every ui case starts creative and FLYING (SafeWorld), and a flying player put on a floor with no fall speed is never
  onGround; with 565-601 ahead of it he hovered at y 100.000 and the wait timed out. It turns flight off and drops him half
  a block. 578 after 122: Party Commands' leader tracker reads lines only while the feature is ON, and 122 sent its closing
  "You left the party." after Settings had switched it back OFF, so HxLead stayed leader and 578's teammate !warp was refused
  "you are not the party leader" (reproduced with `100-hx-session:,122-hx-party-list-responder,578-hx-mcp-party-channels`).
  122 now leaves while it is on and asserts the tracker is empty; 578 clears `PartyLeaderTracker.leader` itself.
- An entity attack is protocol id `attack` on 26.1.2, not `interact`: 686's (then 676) first run counted 0 "interact" packets while
  the zombie it hit lost 3 health on the server (2026-10-11). Count both (`PacketTrace.sent("interact") + sent("attack")`).
- A freshly loaded chunk section can render as sky for a second after a teleport or a setblock: 687 (then 677) and 679's first
  pictures showed no floor and no board while every number passed. Wait ~60 ticks before a world screenshot, and look.
- 631-636 *-sim-pathopt-* (`SimPathOptimalTests`, mod path-optimal): the exact fewest-warps planner and the bottom-slab
  rule, each answer judged on the SERVER's level (real `traverseVoxels` at 32 x 32 points a face, kept only if it holds at
  +-0.01 degrees). An etherwarp onto a bottom slab teleports to slab + 1.05 and he DROPS to its top: judge a warp from a
  slab from the top (+0.5), and a "stand on it" check at +0.5. A ring wall hides its inside from an eye only if the far
  edge of its TOP is above the sight line - measure at the far edge, not the near one (632's first ring was a block too
  high and hid the goal from the slab as well). Wall tops above floor + 6 ARE landings where the room has a roof somewhere over them (the cover rule): cap an arena's high walls with a cobblestone wall (blacklisted). Arena cases share one built 1x1 room; 634 builds Slabs, Three Floors,
  Museum and Scaffolding one after another (-Dtestkit.pathopt.pairs, .threechecks).
- 641-650 (`SimWorldTests`, sim-world 2026-10-11): each item of his 2026-10-09 sim list read back from the integrated
  server's own level - pickup reach measured at 0.1-block steps, one Superboom against two lids + a crypt wall in reach and
  a lid + decorative brick out of it, a secret bat far then near (with an await window opened by `AwaitEvents.begin` then
  `window`: begin also replays his own pickups from just before, here 641's dirt, which read as 9 bat secrets), a
  straight-down Hyperion from an off-centre spot, Big Red Flag's and Leaves' princes, and four lever doors flicked as the
  player. 645-650 screenshot at GUI 2 into the report's screens. 96-ar-644-mimic-warp (in `SimAutoRoutesTests`) plays the
  mimic route in eight variants and prints every non-movement packet per tick.
- 776-785 `-logic-autoupdate-` (`logic/AutoUpdateCases`, mod auto-update 2026-10-11): Auto Update against an in-process fake
  GitHub on the first free port in 32100-32299 (mod services `github-api` and `github-download` set at run time and
  restored). Every case counts the fake's requests, so "Off/testing/marker do nothing" means zero requests next to a
  control that stages on the same fake. 784/785 run the mod's real installer process (`AutoUpdateFeature.spawnHelper`)
  in a temp folder against a dummy parent (this JVM's java running a one-line Sleeper.java). They run on the TEST
  thread (`LogicCase.runOffClient`) and wait with `ctx.waitTicks`: a plain `Thread.sleep`/`Future.get` there made the
  window "Not Responding" and both installer cases time out at 60 s (first run, 2026-10-11).
- **746-750 Auto Fairy Souls (`FairySoulTests`), 754-755 Chocolate Factory (`menu/ChocolateCases`).** Each fairy case
  writes its own SkyHanni-format HUB graph into `GraphRepository.dataDir()`, drops `HUB` from its `LOADED`/`NEXT_ATTEMPT`
  maps, labels the island through the tab list (`Area: Hub`), and answers each soul stand the server sees used with
  Hypixel's SOUL line; proof is the server's use_entity events, distance travelled and take-offs counted from the client's
  motion. On a legit jar 746 checks nothing walks and 747-750 SKIP. 754 lays the current factory out with traps on the old
  reader's slots (39 Shrine, 42 a menu button).
- **791-796 (ping automation, Hx session)**: every case adds a real round trip with `harness/Latency` and reads the mod's own
  timing (Blood Camp's killable/trigger moment from a real Watcher + skull stand moved through the new Hx op `entity.move`,
  Terminal Protection's window from a server-opened "Click in order!" menu, the rest from their timing functions). A pong
  injected into `PingTracker.onPong` must be stamped with `Util.getMillis()` (called through `Mod.staticCall`), not
  `System.nanoTime()/1e6`: the first run's 2000 ms spike was silently rejected as an out-of-range round trip.
- **786-788, 797-799 (trigger-bot delays)**: `hx/TriggerTiming` turns the view onto the target and reads the click on the
  server's clock (Hx `server.tick` against the event's tick); each case compares delay 0 with a longer one. Traps met on
  2026-10-11: a console `fill` silently does nothing in an unloaded area (`forceload add` first, or be there); the Hx world
  has terrain at (-169, -185), so clear above a built structure; a second `dungeon.tp` to the SAME spot passes a
  position wait before it lands and then resets the look; `summon item_frame {Facing:5b}` did not stick (Hx `frame` op);
  a wither summoned into a dying one is shoved away. The frame-driven trigger bots (Simon Says, Arrow Align, Goldor)
  draw GrimAC Post on every click, so those three run expecting flags.
- 811-818 (cosmetic names): a translucent background (tab list, chat, nametag box) over sky is itself blue-ish and passed a
  "green-blue text" colour test, so the whole tab read as one smeared run; drop pixels that are a uniform darkening of the
  base frame before classifying. ModScreen's content widgets sit in a PRIVATE nested pane: reach them through
  `ContainerEventHandler.children()`, not reflection on the pane's class (IllegalAccessException, silently no widgets).
  A literal carrying legacy codes keeps the `§` characters in `getString()`, so match with `contains`, not `startsWith`.
  drew GrimAC Post on every click; since mod grim-post they click on the tick and 786-788 require clean.
- 831-835 (GrimTickCases, nt-grim-post, 2026-10-11): the other frame-driven clicks, moved to the tick - Simon Says Rotate
  Auto Solve, Arrow Align Aura, Auto i4 Rotate / No Rotate, Goldor's CPS kept on the tick (15.9 of 16). Traps: with Rotate
  on, Simon Says' idle look turns the camera off the start button during the test's own press (RotationPlace) - switch
  Auto Solve on after the start; a tp above an unbuilt platform and a wait had him falling as it appeared (AntiKB +
  Simulation) - forceload, build, then tp; an empty hand's use_item is not recorded - hold something.
  832 since aura-cps (2026-10-12) also times the Aura CPS setting on a frame under the crosshair needing 7 clicks (Hx
  `frame` with `rotation` 2): 20.0 at 20, 5.2 at 5. A console `kill` of old frames right before Hx spawned new ones
  killed the NEW ones (console commands and Hx calls are not ordered) - remove Hx frames with `removeStands` only.
- Hotbar swaps (mod hotbar-select-rest-1016, every slot change through `util/HotbarSelect`): 63-breaker-aura-autoswap,
  836-boss-grim-autoi4-swap, 837-boss-grim-autodebuff-swap, 1887-menu-automask-rod (Phoenix's rod route) and
  60-secret-triggerbot-swap each prove the swap reached the server (`held` events or `set_carried_item`), the feature
  acted with the new item in the server's hand (Hx `use_item` names the vanilla item, so each fake Skyblock item rides
  on a different one), no use/dig in a swap's trace bucket, at most one slot a tick, and `PacketTrace.sentOutsideTick()`
  empty; 50/51/53/55-61, 60 and 833/834 require that last one too. 837 fakes M7 P5 the mod's own way: the M7 sidebar,
  y 30 at x, z >= 0, and Hypixel's spawn burst `particle minecraft:flame 27.0 19.0 59.0 2 3 2 0 20 force @a` over two
  statues. Write the coordinates as DECIMALS: a whole `27` is centred to 27.5 by the command and the mod's
  whole-block check rejects the packet (837's first run: no dragon ever spawning).
- A pixel compare of a screen drawn over the WORLD is only as still as the world behind it: 824's legit run counted
  2474 changed pixels at the cursor with no carried item anywhere, because the camera view moved between frames
  (2026-10-12). Look straight up at the sky first and take two clean frames to prove the noise floor is 0.
- 841-843 (AuraQuoiTests, SimAuraSkipTests, nt-aura-quoi, 2026-10-12): Secret Aura's QUOI items. 841 changes world with
  `execute in minecraft:the_nether run tp` and back (the nether pad needs `forceload add` first) and times the first use from
  the tick the client's level object is replaced: 21 ticks, against 4-5 beside a lever and 7 after a same-world tp. 842 places
  Hypixel's essence skull with `player_head{profile:{id:[I;...],name:...}}` and stands at 4.8 / 4.6 eye-to-centre (0 / 2 uses).
  843 puts a server chest in the sim's Water Board (none mid-solve, clicked once `SimRoomState.markCleared`). The three ran
  past run-scenario's default 240 s deadline together with 81-83/419/420/99-sim-*: pass `-TimeoutSeconds`.
- 846-847 (LeverAuraCpsCases in the boss session, AuraQuoiTests; nt-aura-2, 2026-10-12). 846 builds the six S2 Lights
  levers at z 142 and reads the rate off each use packet's client tick over 3 rounds (Goldor's line + unpowered levers
  start a round): 5 -> 5.17, 10 -> 10.00, 20 -> 10.00 - six different levers each need a body-turn tick, so 20 cannot be
  reached there. On the mod before aura-2's fix the turn tick was added to every gap (4.48 and 7.89). 847: the testmod
  now removes a Redstone Key head (profile fed95410-...) on a right click, as Hypixel does (`RedstoneKeyPickup`), and
  logs "redstone key at x,y,z picked up"; a decorative head is left alone. 421 sets Lever Aura `Cps` (Min/Max Delay are gone).
- 848 (AuraQuoiTests, nt-key-place, 2026-10-12): the testmod's `RedstoneKeyPickup` sets the key's head ON TOP of the
  redstone block it is placed on - which is itself a redstone key skull, so the aura's first version picked it straight
  back up (2 uses, nothing on the block). Count uses per phase, and check the head is still there afterwards.
  Since nt-key-place-2: the node only takes the key on an AIR-facing face and logs every use with its face; 848 covers
  the eye-to-centre face with stone so the open-face rule is what is measured, and counts key detection at 9 vs 11.
- 940-945 (mod menu-a, the Option A mod menu, 2026-10-12): a category is a grid of feature cards and a tab's rows live in
  its drawer, one feature at a time. `ModScreenDriver.rebuild()` still honours the old way of asking (a section index in
  the folder's `expanded`, `expandAll` on a group): one opened feature is its real drawer, several are opened in turn and
  their rows stacked and re-scoped. A row's own press works on any of them, but a REAL-MOUSE press through the screen only
  reaches the drawer opened last - a real-input case opens one feature with `d.open(leaf)` (or `openChain`). Tooltips are
  judged with `ModScreen.tooltipCategory(w)` (each tab's pre-regroup category), header rows by `ModScreen.cardTooltip`.
  A slider is clicked on its drawn track (`trackRange()`), not across the whole row.
- **980-985 profiles carry everything, across jars (mod profiles-all).** 983 and 984/985 hand files between runs of DIFFERENT
  jars through `<checkout>/build/profile-xfer/` (outside the run dir the next run rebuilds): run 983 once on a jar from before
  profiles-all (it saves `old-profile/`), 984 on the first jar, then 985 on each other jar - it imports every zip there not made
  by itself and re-exports it once as `<tag>__from__<source>.zip`, so a last 985 on the first jar proves the round trip. A jar
  tag is a PREFIX of another (`26.1.2-cheat` of `26.1.2-cheat-testing`): match `<tag>.zip` / `<tag>__` exactly, or the
  cheat run silently skips the testing jar's exports (it did once, 2026-10-13).

- 1314 (Peek Chat): the held item on the right sways between two screenshots (2,053 changed px in one run), and the
  chat sits wherever the window puts it (top-left at 854x480). Diff a strip LEFT of the hotbar and judge by share of
  the area; find a chat line on the picture by its colour, not by a fixed y (2026-10-13).
- The Hx test server SPENDS the `items.spirit-leap` fixture on every use (it is a plain item on a vanilla server), so a
  second leap in one case finds nothing ("Could not find INFINITE_SPIRIT_LEAP, SPIRIT_LEAP"): give it again before each
  leap, and zero `LeapManager.leapCdTicks` (983, 2026-10-13).
- 1321-ui-etherwarp-box-faces samples each camera-facing face of the Etherwarp box where it projects (camera from
  `Camera.position()/yRot()/xRot()`, vertical FOV from options with FOV effects 0), skipping samples a client raycast says
  are occluded. Sodium (0.9.2-alpha.4, `-ModUnderTest "mod.jar,sodium.jar"`) breaks the gametest `takeScreenshot` with
  "Global terrain uniforms have not been updated"; the case reads the main target back with `Screenshot.takeScreenshot`
  then (26.1.2 only: 26.2's `Minecraft` has no `getMainRenderTarget`). A compile error in a -Minecraft 26.2 run makes
  run-scenario exit 1 in seconds and leaves the PREVIOUS run's report in place: check the log's "Loading Minecraft" line.
- 1324-ui-etherwarp-nonfull (mod ew-chest-1015, 2026-10-15): the Etherwarp box on a chest, ender chest, trapped chest, slab, stair,
  hopper, cauldron, carpet, stone under a player head and plain stone, from a one-high step at 0 and 40 degrees. An outline sample
  needs 7 magenta pixels in its 5x5 (a whole 2 px line, about 10): 'any magenta within 2 px' passed main's chest at 54/63,
  because the half of each line outside the chest's silhouette survived. Main 26.1.2: chest Outline 0/63, Filled 0/50; 26.2
  and the fix: all pass. Etherwarp passes through heads, so the head case's box is the stone below it, red.
- 1401-1403 (`SimWorldTests`, sim-superboom-radius 2026-10-14): the sim Superboom's 3-block radius (blast at the hit FACE's centre, measured to each block's box) and whole groups. 1401 Big Red Flag's prince tomb (86 blocks, wall z 27 / x 18 and floor kept) plus a floor cracked chain; 1402 Arrow Trap's slab crypt from an overhang 2 above the lid (out of the old y +-2 cube); 1403 a cracked chain with one end in reach beside a stone brick wall that stays. All three fail on main (46/86 tomb left, 0 broken, 8/8 chain left). 642/645 now expect the decorative brick in reach, the eave and the wall under the lid to go.
- 96-ar-1014-jumpcd / 62-argrim-1014-jumpcd (ar-jump-cooldown, 2026-10-14): a finished jump node's tile does not arm for
  3 s. Count take-offs on the CLIENT's ground flag and only after the node has pressed: the integrated server's flag drops
  once for the teleport onto the node (first run: 4 server take-offs for 3 presses, 545 ms apart), and a teleport can drop
  the client's too (jump 3: 7 take-offs for 6 presses). Auto Routes fires nothing in a legit jar, so the case skips there.
- 1410-sim-water-static (SimStaticWaterTests, mod sim-water-static, 2026-10-14): builds Museum then Water Board as single rooms
  and, on the server at an open floor spot beside him, writes a lone level-4 flowing water block (40 ticks) and then a source
  (60 ticks), counting water in a 17x5x17 box. Museum must keep both still with `SimStaticWater.frozenTicks` rising; Water
  Board must drain the lone block and spread the source with `allowedTicks` rising - the same writes flowing there is the
  control for the Museum half. First runs, 26.1.2 and 26.2 alike: Museum 9 -> 9 (frozen +2), Water Board 1 -> 7 (allowed +24).
- 96-ar-1015-landpickup / -earlyskip / -mimicskip (ar-mainserver, 2026-10-15). landpickup: #1 warps onto #2 (await 1) where
  an item lies; `harness/HypixelOrder` (mixin at RETURN of `ServerPlayer.teleportTo`) takes it in the teleport's own step, as
  Hypixel does - a vanilla integrated server picks it up a tick later, after the client has seen the landing, so without the
  hook neither jar shows the bug. Run 1 (OTHER: a take packet naming another collector) must leave #2 waiting; run 2 (SELF)
  must log the pickup BEFORE "Landed in node #2" or it proves nothing. Drop the item with him off the tile: the last run
  leaves him standing on it and vanilla took it the tick it appeared (first run, "items taken 0"). earlyskip: `Latency` 250 ms
  makes #1's flight ~7 ticks so a real left click lands inside it. mimicskip: a plain trapped chest keeps Kill Mimic's
  Spirit Sceptre going for 5 s; it outlives the route, so the case waits it out. Old jar 3/3 FAIL, new 3/3 PASS.
- `harness/HypixelUseAfterClick` (rewritten on ar-nexttick-1015, 2026-10-15) is Hypixel's 1.8 pairing, not a timer: a block
  click (HEAD of `handleUseItemOn`) stores a verdict (denied when an item is in hand); a use (HEAD of `handleUseItem`, BEFORE
  vanilla snaps him to the packet's rotation, so his rotation there is the look the server HEARD) traces 4.5 from the 1.8 eye
  (1.54 sneaking) along that look, and if it meets a block the use is the stored click's second half: dropped when denied. A
  ray in the air fires and leaves the click stored. A use from the same client tick as its click (no `client_tick_end`
  between - a standing client sends no movement packet, and server ticks do not line up with client ticks) is a vanilla right
  click's own two halves: `pairedSameTick`, not `dropped`. `reset()` also forgets the mod's `util/RightClick` model and the
  sim's stored clicks (`SimAbilities.forgetStoredClicks`), or one case's clicks decide the next case's control. The cause it
  models: his 2026-10-14 Mod Only Test run, six warps after an AOTV chest/lever click "no landing after 61 ticks"; Red Blue
  #5/#6's own looks meet nothing within 4.5 in the room capture, the look the chest was clicked with does.
- 96-ar-1015-warpclick: an await:1 etherwarp node, armed looking at the floor (pitch 80 - the heard look meets a block, as
  Secret Aura's turn to a chest does). Controls: a left-click skip lands; the lever clicked with an empty hand lands; a raw use
  a tick after a click with the body NOT turned is ignored (rule live); the human stream (look at the lever, click; next tick
  look at the warp's block + sneak; next tick a real use key press) lands. The case: the lever clicked with the AOTV held must
  land with the warp 1 tick after firing; an item pickup (no click) must warp and land with no turn. 2026-10-15: new jar
  (nexttick) acted 1 tick after firing, landed 3 ticks after the click, 0 ignored, PASS; the click-gap jar (95e50d7c) held 4
  ticks and was STILL ignored (never landed), FAIL; the jar before it (8cf017b7) ignored at 1 tick, FAIL. `waitLanded(ctx, x, z, 0)` used to check NOTHING (its loop ran `ticks` times) and always
  returned null - the first run of this case read every landing as "never"; earlyskip's in-flight checks and mimicskip's
  "not fired yet" check passed 0 and so could not fail. Fixed 2026-10-15 (click-gap-all-1015): it looks now, then after each
  of `ticks` ticks, so 0 is one look and `(.., 1)` looks twice. Proven able to fail: earlyskip with `Latency.install(0)` fails
  "the warp landed before the click was made"; mimicskip with its node's await switched off fails "the node fired before any
  click" (only the landing look can catch that - no "secrets met" line exists). No other caller passes 0.
- 1015-sim-clickgap and 96-ar-1015-puzzleclick: `harness/ClickPrimer` plays Secret Aura after every tick that sent an item use
  (slot-limited): body turned straight down at END, and a click on a solid block a 1.8 server would store but this server's
  reach (4.5 + 1) drops (`storableOutOfReach`: >6 from the eye, <7.7 from the feet) - NOT a lever: a sneaking AOTV click on one
  warps in the sim, and NOT 30 blocks east (outside the 8 a 1.8 server stores from, and often air in a generated floor).
  1015-sim-clickgap (cheat jar, sim F7, room captures): raw control (looking down, click, a tick later a use: ignored), an Auto
  Clear trip and an Interactive Map press with the primer, against the same press without it; asserts every hop was followed by a
  click the server saw, 0 uses ignored, no more `[Path] off the plan:` than the control. New: 0 ignored, 0 off-plan, PASS. Click-gap
  jar: Auto Clear 80 ignored and gave up, map 20 of 21 ignored; pre-gap jar: 13 and 4; both FAIL. 96-ar-1015-puzzleclick (arena):
  `AutoPuzzleUtil.useItemRotated` called as an auto calls it, heard look on the floor: no click sends at tick 0; an AOTV-held
  click turns first and sends at tick 1, lands; the raw use after the click is ignored. Click-gap jar sent at tick 5 and was
  ignored; pre-gap at tick 0, ignored; both FAIL.
- 1015-sim-pathland (cheat jar, sim F7 x `-PpathlandFloors` (3), room captures; ~15 min): per room a route PATH S0->S1, ETHERWARP
  S1->S2, PATH S2->S3, ... so every pair after the first fires on the etherwarp's landing tick (0.05 up, the run-time re-aim).
  Pass 1 plans (he settles while it plans, so no re-aim), 2 flies the saved plans, 3 adds `ClickPrimer`. Each hop is judged
  against its saved plan's target block (RouteNode.PathHop.target), not the mod's 2-block "landed"; a correction during a
  path warp is a warp landed elsewhere. Plus an aim probe: the mod's `getEtherwarpDirection` from 0.05 over a block centre
  to landable blocks, fragile = lands for the exact float look but not within 0.01 degrees. Mod main d1eda658: 3600 aims,
  260 fragile, 67 with a firm one to be had - FAIL; path-wrongblock-1015: 8 fragile, 0 avoidable, no saved look fired from
  above its spot, PASS. End-to-end wrong blocks are rare (0 in 552 main hops one run; one 5.4-block miss on Overgrown in
  another), which is why the probe is the gate. Floors are random per run (SimFloorGen has no seed).
- 62-argrim-1015-clickwarp (GrimAC session): an await met by a lever click made as Secret Aura makes it (body turned to the
  lever for a few ticks, then the click with the AOTV), far (8 blocks) and near (3 blocks, which sends the click on the target
  block first): each must land with one etherwarp, the warp at most 1 tick after firing, Grim silent.
- 385-ui-interactive-map (2026-10-15): no drag with any button pans, and a right/middle drag's release acts; the view is moved
  by scroll zoom for the Reset view check. Old jar: RMB drag panned 75.5.
- 1412-sim-lever-cobble, 1414-sim-lever-golden-oasis, 1415-sim-redstone-key (SimWorldTests, mod sim-levers-1015,
  2026-10-15): 1412/1414 reuse `caseLeverDoors` on the two levers the sim PLACES (Cobble Wall Pillar's door box includes the
  12 barriers behind its 12 bars - count both). 1415 clicks Golden Oasis' node with no key first (the control: nothing may
  change), then the key (block to air, not a secret), then the node (node and the 3-block strip gone, `SimScore.foundInRoom`
  +1, and an armor stand wearing the key profile visible to the CLIENT at node y - 2). Main jar: 0 levers armed, and the key
  position holds a lever, not a key head.
- **A server lag spike cannot be made by blocking the integrated server's thread.** The client gametest API ticks client
  and server in LOCKSTEP, so a `server.execute(() -> Thread.sleep(2500))` stops the client's ticks too and no client-side
  timeout ever runs: 1460's first version passed in 26 client ticks across a 2.5 s block, on any jar (2026-10-15). Use
  `harness/LagSpike` (the connection frozen both ways for N ms, then the client's packets reach the server in order and
  its answers follow); `armOnNextUse(ms)` starts it on the next `use_item`, so that use's answer is inside the spike.
  Inbound-only was tried first and is wrong: the server answered every queued warp in real time while the client was
  blind, and the burst produced a correction no stalled server would.

- 1042-ui-trail-teleport-ribbon, 1043-ui-trail-teleport-squares, 1044-ui-trail-teleport-settings (TrailTeleportCases, mod
  trail-hud-1015, 2026-10-15): the player walks 3 blocks, is moved 30 blocks along +x in one step (position AND old position,
  as a server teleport does), then walks on. Per On Teleport mode (Break / Connect / Arc) the geometry the renderers hand
  to the GPU is judged: the ribbon's `lastVertices` with `lastBreaks` / `lastSegments` / `teleportsSeen`, the squares ring's
  `position(i)`. Break: one gap, joined quads stop short, the longest step is the jump and the second longest is short;
  Connect: no gap, every step short, 100+ points, flat; Arc: no gap, short steps, rises 3.6+ blocks (squares 4.5+); exactly
  one teleport counted (walking never is). 1044 is the config: default Connect, file round trip, profile reload, a bad file
  value, and both tabs' cycling row stepping Connect -> Arc -> Break -> Arc. Main jar: no `trail.TeleportMode`, all three
  report a problem (and its ribbon dropped the whole trail past 6 blocks).
- 1045-ui-routes-progress-content, 1046-ui-routes-progress-editor, 1047-ui-routes-progress-settings (RoutesProgressHudCases,
  mod trail-hud-1015): drives the HUD's two real inputs, `RouteRunMemory.noteProgress` and `RunPlan.publish`, and reads
  `AutoRoutesProgressHud.shownText`: the one-room line (43% for 3/7, 100% for a route that ran to its end), a plan's header
  percent, the `>` row with its route percent, exactly the configured number of upcoming rows, `+N more` only when rooms are
  left out, and the Autopilot > Auto Secret > Auto Clear priority. 1046: the HUD editor lists it only while the setting is on
  (and a legit jar has no such element). 394 now switches the setting on and fails if the element draws nothing. The
  planners' REAL queues are checked inside the sims: 131 case 6 (Auto Clear's logged `order` = the published plan = the
  rows), 102 (Auto Secret's first target, only routed rooms, percent math, the executor reporting node progress) and the
  Autopilot sim (every plan the HUD drew is one a logged decision ranked).
- **177-183 Auto Blood Camp (hx session, mod bloodcamp-1015, 2026-10-15)**: offset from the kill timer, Blood Room gate, aim
  height, human turn, mouse takeover, miss retry, Freecam Mode; each Grim-clean. Blood Camp only tracks a mob stand within
  20 blocks of the Watcher, and the session is in the FIRST wave (no Watcher line), whose trip starts 16.1 + 1 blocks back:
  with the Watcher at (-416.5, -412.5) nothing was ever tracked. (-424.5, -412.5) is in his room tile (-7, -7) and close
  enough; 792 uses it too. An invulnerable husk is now a MISS the Trigger Bot retries, so 792 uses a hurtable one.
  Main jar: every case fails on the missing Trigger Offset / Freecam API.
- 1433-sim-deuces-doorway (SimDeucesDoorwayTests, mod sim-deuces-1015, 2026-10-15): Altar (2x2, cells x 2..4 z 4..6) and
  Deuces (6,6) unrotated, door at (5,6). Compares both doorway ends to `RoomLibrary.get(...)` by block id (trapped_chest
  counts as chest: the sim's mimic turns one secret chest into a trapped one, at random), checks fire on Deuces' four
  braziers and saves a screenshot from his viewpoint to killer560s-mod-logs/deuces-doorway.png. Main jar: Altar's three
  iron bars (capture 62,72,47..49) carved to air and 0 of 4 braziers lit. The screenshot needs a wait until the client has
  the chest block, or it is all sky.
  Since mod sim-deuces-door-1016 it also sweeps Hypixel's door piece (`DoorPieceSweep`): the 5x3 box over each standard
  doorway's trim, wall plane and one in, must be the recess (cyan terracotta, air) on a world east/south wall and the
  fill (stone, bricks + four stairs facing out) on a world west/north one - on the two-room floor at Deuces 0 and 180,
  three generated F7s (premise: >= 30 doorways crossed to the other side's piece) and a solo Deuces at 0 and 180. Main
  fcba4045: 0 and the unrotated floor pass, every turned case fails (24/19/10 wrong on the F7s). Its screenshots now go
  to killer560s-mod-logs/sim-deuces-door-1016/: the old deuces-doorway.png, a SIM shot, had been taken for his Hypixel
  reference picture.
  Since mod deuces-exact-1018 Deuces' chest doorway (W0) is not a door piece: `DoorPieceSweep` exempts it (counted as
  `exempt`) and 1433's chest-doorway check is the table check of 1435; its capture comparison skips the table's blocks.
- 1435-sim-deuces-exact (SimDeucesExactTests, mod deuces-exact-1018, 2026-10-18): Deuces' chest doorway against the mod's
  own table (`DeucesChestDoorway.overrides`, via `DeucesExact`), every block, solo and on the 1433 floor at 0/90/180/270;
  the door-piece sweep on each build; the passage into Altar at floor 0. The table states are turned by the paste's
  rotation; a wall is compared by block only (its arms follow its neighbours). Premise: >= 20 of the table's blocks
  differ from the capture, so a paste that ignored the table fails.
- 1434-sim-deuces-hypixelview takes `-PdeucesVariants=spec=<file>` (lines `x y z block`, ranges `a..b`, capture frame,
  cumulative), `-PdeucesZs`, `-PdeucesOut`. The test client FLIES, and flying multiplies FOV by 1.1: his FOV 110 is
  `-PdeucesFovs=100`.
- 1561-1567 (ui/FontsMainMenuSuite, mod fonts-mainmenu-1015, 2026-10-15): the Font setting is measured by each GUI text
  render state's PREPARED bounds after an off-screen extract (a state replays the font scope it was made in), not by
  `Font.width` alone: Mod Menu Only changed 49/53 menu texts and 0/13 Options texts; 1562 also reloads resources with a font
  on (the set borrows the default providers). The title-screen chip is read through `NameReplacer.replace`, as the font sees
  it; on the main jar it says "FontTester" (Name Changer's name). 1566 needs a 30-tick gap for Soft Light to move 0.1%.
- 1475-1478 (LightsDeviceUiCases in the UI suite, LightsDeviceCases in the boss session; lights-aura-1015, 2026-10-15). The
  S2 Lights device is its own cheat (Lights Aura + Lights Triggerbot); 846 now drives LightsDeviceConfig and is named
  846-boss-lights-aura-cps. 1476-1478 reuse 846's arena (wall at z 142, him at 60.5,133,139.5 yaw 0, so the crosshair is
  on 60,134,142) and read who flicked what off `LightsDeviceFeature.recentFlicks`/`flickCount` (count first: the list
  keeps only 32). Branch: 1476 3 pre-flicks + 1 activation, 1477 six uses one per lever with Secret Aura + In Boss on and
  no Secret Aura attempt on a Lights lever, 1478 only the looked-at lever after the 2-tick delay, all GrimAC clean. Main
  jar: all three fail (no LightsDeviceConfig). Turning onto a second lever is a server `dungeon.tp` with yaw/pitch, never a
  harness rotation.
- **1071-1075, 1077 (mod leap-queue-1015, 2026-10-15):** `EditBox.setValue` from the test thread throws "Rendersystem called from
  wrong thread" - type through `c.onClient` (1075's first run). A tab built by reflection (`buildWidgets(0,0,300,...)`) is
  pressed with `ModScreenDriver.press`, left click forward, a `MouseButtonInfo(1,0)` event for back. The mod's screens lay out
  on Auto Scale's canvas, so 1073 reads 854x480 at the test window AND at 1920x1080 GUI 2 with Auto Scale on; take every
  extent from the open screen's `width`/`height`. 983 fails after 829-834 in one menu session (a stale "LrB (Tank)" leaps
  first) on the pre-change jar and tests too - run 983 alone. 1077 clicks a vanilla container screen's overlay in plain GUI units
  (`pos x getGuiScale`, no Auto Scale).
- 1446-sim-door-height (SimDoorHeightTests, mod sim-doorheight-1015, 2026-10-15): 30 F7s planned with Mage, then Quad Lava,
  pinned at room cell (2,2); counts floors whose door tree uses Mage N1 / Quad Lava E0 (capture frame, turned through the
  mod's RoomDoors.rotate/doorCells) - openings above the doorway floor that are not doors - and Entrance W0 as a control.
  Builds the first floor that used the edge (else the first that kept the room) and checks every door of the room in the
  server level: 3x7 passage clear three high and floored at y69, walk on 4..6 blocks into each room, and solid capture
  blocks the carve removed (capture lined up through the paste rotation, self-checked at >= 70% agreement). Main jar: 21-25
  and 20-23 of 30 floors used the edges, Mage's door a dead end in stone with 25 blocks carved, Quad Lava's 23. A walk-on
  probe that allows drops must require a clear shaft, or it drops through stone into a cave and passes a dead end.
  Reworked for mod sim-doorblock-1015 (2026-10-15): the /mage re-capture is 33x65 where the stitched one was 65x33, so
  "Mage N1" was no edge of it and pointed at the cell east of Mage (main's "4 of 30" were doors between two other rooms);
  a case edge must now be on the room's tile grid and not measured as a doorway, and Mage is pinned only to build and walk.
  A join through Quad Lava E0 is the fill pass's carve (printed, walk-checked). New TUNNEL phase: 1000 F7s from fixed seeds
  (SimFloorGen.RNG seeded, SimFloorLayout.RECENT cleared - plain reflection, both private), summing the solid capture blocks
  every off-doorway door cuts behind its wall plane, read from the capture by the scenario: main 2,505, branch 2,041, limit
  2,270. A capture-frame edge (side, index) silently changes meaning when a room is re-captured at another turn.

- **184-187 Auto Blood Camp, round 2 (mod bloodcamp-2-1015, 2026-10-15)**: walls (a stone row between him and the spot:
  no Aura aim, no Trigger Bot click; control without it), mobs already on the floor (the Aura kills them), the attack-speed
  cap, Never Miss (no swing before the mob exists, first attack on the tick it appears). The hx session's server READS AS
  HYPIXEL (its default label), so the cap there is Hypixel's round(10 / (1 + AS/100)), 10 ticks with no Attack Speed;
  186 clears the label for its vanilla part (iron sword, 13). Server attack gaps at a 5-tick cap came 4/6 alternating
  while the client sent exactly 5 - check the client's own click ticks (`recentClickTicks`). Main: 184 aura aimed through
  the wall, 186 retries 5-6 ticks apart; 185/187 fail on missing API.

- **188-189 Auto Blood Camp, round 3 (mod bloodcamp-3-1015, 2026-10-15)**: 188 a husk 6 blocks out (NoGravity, it is off
  the platform): a mage weapon (blaze rod) gets swings and no attack, a lore-"Shortbow" bow gets uses (use_item at the
  server even with no arrows - the packet goes before the item's own check), nothing behind a wall, and the Trigger Bot
  with a shortbow sends uses. 189 the clicks between registering hits: the cases before it switch them off in `base()`
  (`setHumanClicks(false)`), since every attack count there would include them. Main: both fail on missing API.
- **190-192 Auto Blood Camp, round 4 (mod bloodcamp-4-1015, 2026-10-15)**: blood mobs are now known by IDENTITY (the mob on
  a trip's landing spot as it lands), so a husk summoned with no trip is not a blood mob and the Aura ignores it - 185,
  186, 188 and 189 were rewritten to use trip husks (named "blood", NoGravity). 190 Dungeon ESP's Blood Mobs: the trip
  husk is drawn red, a plain husk is not, a starred zombie keeps its own colour, and the husk stays red after a `tp` out
  of the room tile (Blood Camp itself off - the ESP tracks on its own). 191 a plain husk in reach in front of him is never
  attacked or aimed at; 192 a plain husk standing on the line to the blood mob holds every click (control: clicks).
  `MobEspConfig`'s Blood Mobs/Starred/Room Scoped have no bean getter, so 190 sets them with `.custom`.

- 1015-sim-map-item-only (SimMapItemTests) gives the player a Hypixel-style dungeon map in slot 9 built from bytes the case
  paints itself: a client-side `MapItemSavedData.createForClient` put in with `ClientLevel.overrideMapData` under an id the
  server has no data for, and the same `filled_map` stack set on the SERVER player too, or the next inventory sync takes it
  back. Rooms at corner (5, 5) and a pitch of 20 calibrate on the sim's M7 floor number (the default branch of the
  scanner's start-corner table); a floor reporting F1-F3 would need that table's fixed corners instead.
- 1015-ui-switch-align judges a row lone only when NOTHING else shares its height band, so a half-width row beside a slider
  or a text box is fine; it opens every leaf's real drawer (`ModScreenDriver.open`), not the stacked folder view.

- **1720-1731 sim-doors-predict** (mod doors-predict-1015, 2026-10-15). The sim has no map item, so the mod's `HeldMap`
  stand-in decides what "the map shows": a room is opened once someone stands within 14 blocks of one of its tile centres
  (x/z only - hovering high over a room opens it too). So a test that must keep a room unopened waits HIGH OVER A DOORWAY
  GAP, never in it once the doorway is coal: placed inside a coal doorway the server pushes him out into a room, which
  then counts as opened (1722's first runs, "Black Flag opened (you at -24, -71)"). And a door block read right after
  `witherDoorsAround` is `void_air` while its chunk has not reached the client (it was 160 blocks off): stand near it before
  reading it. 1728 audits sight by re-casting each seen block's RECORDED ray (eye to the point the ray met it) right after
  each look: 27 rays to points inside the block called 7-11% "blind" that were grazing views, and an audit run at the end
  failed blocks that later coal doors had hidden. 538/678 (Show All Doors' coal-door scan) fail on main's cheat jar too:
  WitherDoorScanner finds no coal door on a sim floor at all - not this branch.
- 1490-sim-mage-capture (SimMageCaptureTests, mod mage-capture-1015, 2026-10-15): `/mage` typed for real in a sim Mage.
  The integrated server never forgot a chunk here, even at render distance 2 (the client still held chunks 8 out), so the
  unloaded case hands the client `ClientboundForgetLevelChunkPacket` through the real handler and runs `/mage` in the SAME
  client task (the server sends it straight back), and it runs last. Ask `mc.level.getChunkSource().hasChunk`, never
  `mc.level.hasChunk`: ClientLevel's is hard-wired to true. The stitched tile is made sound by copying the good tile's
  centre column over it on the server, so the save path can be tested on the shipped capture.
- **188-189 Auto Blood Camp, round 3 (mod bloodcamp-3-1015, 2026-10-15)**: 188 a husk 6 blocks out (NoGravity, it is off
  the platform): a mage weapon (blaze rod) gets swings and no attack, a lore-"Shortbow" bow gets uses (use_item at the
  server even with no arrows - the packet goes before the item's own check), nothing behind a wall, and the Trigger Bot
  with a shortbow sends uses. 189 the clicks between registering hits: the cases before it switch them off in `base()`
  (`setHumanClicks(false)`), since every attack count there would include them. Main: both fail on missing API.
- 144-sim-autosecret-handoff / 145-sim-autosecret-order / 364-logic-room-tour (mod ac-optimize-1015, 2026-10-15): routes
  are one-node starts, so a route ends on its arming tick - before the trip after it can be searched on a COLD floor graph
  (one search took 1.2 s there). Both sim cases wait for `EtherwarpPathfinder.graphWarm` first, as a real run is warm long
  before its first route ends. Tick comparisons use `ClearExecutor.clientTick()` read in the SAME client task that changes
  the world (a `ctx.waitFor` after it lets ticks pass). 145 picks its rooms by the mod planner's own warp counts
  (`EtherwarpPathfinder.findDungeonPath` on the planner thread): straight-line distance and even map room steps misjudge
  trips (45 blocks apart was 13 warps), and on such floors the "new order crossed the map" failure was the test's guess
  being wrong, not the order. Its real-tick secret-weighted wait is printed, not judged: every sim trip pays ~20 ticks of
  search/sneak/arrival settle whatever its length.
- 131-sim-auto-clear case 9 (fewest repositions) counts `[Path] running` lines (trips really run), not `[AutoClear]
  etherwarp toward`: before mod ac-optimize-1015 that line was logged again when the body had to be given back first
  (Phase.RELEASE) and a 3-trip clear logged 5. Case 8 leaves a live zombie in the room he stands in; case 9 avoids it.
- 1015-noamm-map (SimNoammMapTests, mod noamm-map-compare-1015) reuses SimMapItemTests' floor and painted map: the gray
  (85) door's drawn colour, four fake dungeon tab entries (`PlayerInfo`s put straight into the connection's
  `playerInfoMap`/`listedPlayers`, sorted by their `!B-x` profile names) against four PLAYER decorations, a marker moved
  three rooms and sampled per tick, then a map with a stray green run at (16,5). Empty slot 9 before a sim REBUILD: the
  rebuild resets the map reading and the old map still in slot 9 recalibrates first (the old jar measured (16,5) off the
  previous map and every later part read 11 px off). Old jar fails all four parts; new passes (cheat and legit).
- 1491-sim-chunk-loaded (SimChunkLoadedTests, mod chunk-loaded-1015, 2026-10-15): seven readers against a chunk the client
  was told to forget (`handleForgetLevelChunk`, as 1490). The server did NOT send it back on its own here (5 s of waiting,
  checked after every "while gone" reading), so the chunk is handed back by building
  `ClientboundLevelChunkWithLightPacket(level.getChunk(cx, cz), lightEngine, null, null)` on the server thread and calling
  `handleLevelChunkWithLight` on the client. Do not `reset()` a door scanner after forgetting: the reset is a new run, and
  a rescan rightly skips the unloaded cell, so the first version "failed" on the fixed jar too. Main's cheat jar fails all
  seven parts (premises pass); the branch passes all seven.
- 1590-1596 (`ui/MenuAnimSuite`, mod menu-anim-1015): the mod menu's animations. Every run passes
  `killer560.test.menuAnimClock=instant` (build.gradle), so an undriven menu animation is instant and any case may read the
  frame right after a click; these cases drive the clock with `gui.MenuAnim.setClockForTest(ms)` and render ONE frame per
  clock step (`frame(c, ms)`). A change caused by a press is stamped at the press, so set the clock BEFORE pressing.
  1594 reads `ModScreen.allocProbe` (bytes the menu's draw allocates per frame; ~750 KB at rest, the same with Smooth and
  Off) - an allocation that DROPS is a finding too: it was how a page drawn at opacity 0 showed up. 1596 only runs when
  named and writes `<report>/menu-anim-video/frame_NNNNN.png` (1/60 s of clock each) for
  `ffmpeg -framerate 60 -i frame_%05d.png -c:v libx264 -pix_fmt yuv420p -crf 18`.

- **193 named blood mobs (mod bloodcamp-5-1015, 2026-10-15)**: a husk with a name-tag stand "Revoker 1.4M❤" summoned just
  above it (no trip - Blood Camp never saw it land) is drawn red by Dungeon ESP's Blood Mobs and attacked by the Aura; a
  husk whose stand reads "[VIP] Revoker: hi ❤" is neither. Main jar: the named husk is not highlighted (fails).
- 1597-1598 (`ui/MenuAnim2Cases`, mod menu-anim-2-1015): Animation Speed (durations ordered Extra Fast < Fast < Normal < Slow,
  a drawer slide measured at 120 ms, the four stops pressed and reloaded), and every slider of every drawer - closed
  sections opened, each off switch turned on in turn to show what it gates, all inside one client task so nothing it
  turns on gets a tick - dragged to 41 between-step positions; the knob must equal what a freshly opened drawer puts it
  at for the saved value, with the same text. Read the knob from the `value` field: the old jar has no accessor. On the
  pre-change jar it named 186 knobs resting between two values.
- 147-sim-autosecret-lastsecret (mod autoclear-leave-1015, 2026-10-15): judge a room's last secret against the sim
  SERVER's own count (`SimScore.foundInRoom`), never the mod's: the mod's count was the bug (it counted the leaving room
  from the next room's start). Judge the hold's RELEASE tick (the "the next warp goes now (tick N)" line), not the first
  warp out: a trip that was not planned ahead searches for up to a second after the release, and a secret can register in
  that gap by luck. Turn Auto Close Chest on as killer560 has it: with it off the sim opens the chest's window and the
  warp goes under it. Stands are chosen within 4.0 (chest box) / 4.2 (essence) of BOTH the standing and sneaking eye; the
  Interactive Map sometimes ends a trip 5 blocks off a start node ("did not arm"), so some rooms go unjudged - 2 judged rooms a floor
  is the minimum the case accepts.
- 146-sim-autosecret-arrival (mod ac-optimize-2-1015): ticks from a trip's last warp to its one-node start route ending
  (main: 11 every room, the Interactive Map's arrival settle; branch: 2). A warp sent on the route's own end tick is the
  NEXT trip's (the hand-over can be that fast), so only warps strictly before the end belong to the trip into the room.
- 147-sim-autostatus-hud (mod autoclear-hud-1015, 2026-10-15): the shared Status HUD on a Solo Autopilot run, in 141's
  class and world setup (`StatusHudCase`). The run is short (one zombie, two one-node routes: ~5 s, ~30 samples), so it
  samples every 5 ticks. Clear the chat before a screenshot: in the 854x480 window the run's chat lines cover the HUD.
- 131-sim-auto-clear's correction-plan case is a race on main (2026-10-16, autoclear-hud-1015): the teleport is sent with
  the clearRoom call, and it counts only when the Interactive Map runner is already on its trip when it lands ("caught by
  the Interactive Map runner"); when the trip's search is still running Auto Clear files it as the trip's own and nothing
  counts it. main's cheat jar passed 2 of 6 runs, the branch 2 of 9. A failure there also leaves the sim world open and
  the client hangs until the deadline (the keep-going disconnect deadlock above).
- Mod main's sim clears every room on entry (2026-10-14). A case that judges which rooms its clears killed their way to
  (141) turns `SimMobs.setClearRoomsOnEntry(false)` for its run, or any trip passing through a room "clears" it.
- **1080-1083 (TrajectoryCases, mod shortbow-traj-1015, 2026-10-15):** Trajectories for shortbows. Each draw is judged by the
  feature's own views (`TrajectoriesFeature.lastArcs/lastKind/lastSpeed/renderCalls`) AND by cyan arc pixels against a frame
  with the feature OFF, so main fails on what it draws, not on a missing method (main: 0 px for a held shortbow, 885 px for
  an idle plain bow). Measure an arc's first step LEVEL: at pitch 50 it hits the superflat inside one tick and reads 1.98
  blocks, not 3.0 (1080's first run). The arcs start at the eye, so on screen mostly the impact boxes show.
- **1640-1644 menu-profit-*** (mod profit-accounts-1015, 2026-10-15): profit per Minecraft account (UUID) with Show
  Profit (This / All Accounts) and Profit Data (Shared / Per Mod Profile). Fake accounts come from the mod's
  `ProfitScope.setAccountForTest(name, uuid)`; always clear it (nulls) in `finally`, or every later profit case records
  under a fake account. The settings file (`ProfitConfig.PATH`) is outside profiles, so a case snapshots and restores it
  and calls `ProfitConfig.load()`. 733 now drives the account switch through that setting, not the view filter.
- 1850-1851 (mod swap-accounts-ui-1015): the Swap Accounts screen. The Universal switch writes Prism's SHARED
  `universal-proxy.json`, so 1851 presses it only when the mod's `ProxyConfig.UNIVERSAL_PATH` is the
  `prismaccountswitcher.universalProxyFile` build.gradle sets (main ignores the property: 1851 then reports it and leaves the
  switch alone). A row press and Copy ID fail offline; the failure line names the account, which is what proves the press
  reached that account's handler. Rows are found by their label (the account name), their buttons by sitting in its band.
- 1571-1573 (mod vanilla-themes-1015, 2026-10-15; first numbered 1568-1570, which Amber already used): 1572 walks the vanilla menus by real clicks from the title screen and
  leaves each by its own Done/Back/Cancel. Never press `selectWorld.create` on CreateWorldScreen: with no saved worlds
  Singleplayer opens it directly and that key is its "Create New World" button - the first run made a world and the client
  hung. A theme check measures a button's mean colour in two screenshots (old jar: 30,30,30 in both themes). 1566 counts
  scene motion only in the side strips: the splash text pulsed, so a whole-screen count moved 0.1% on Plain too (the themed menu draws no splash since mod no-splash-1015; 1568 embers moved 0.23% with it, 0.10% without).
- **93-solve-waterboard-timing, 1015-sim-water-mappress, 1015-grim-water-clickwarp** (mod water-late-1015, 2026-10-15):
  Auto Water's timing. The timing case (`SimWaterTimingTests`, on `SimPuzzleSolveTests.Base`) reads Water Board Solver's
  clock at END_CLIENT_TICK and stamps item uses through `PacketTrace.tap` with the same tick; a timed click must land at
  most 1 tick after the solver's CLICK ME (the auto ticks before the solver's counter, so on time reads 1 - which is also
  exactly time*20 ticks after the water lever's own click) and every lever click followed by a warp must have its use the
  next tick. Main: 12 late, gaps of 7 (the same 11/7 as his Hypixel log). The base calls a `SolveExtra.configure` three
  times (solver only, auto on, after the verdict): install once. The map press case calls `AutoClearUtils.pathToRoom` on
  the single-room build from the spawn (doorway, relative y 69) and needs the trip to end on the lever floor (y 59); main
  does not move him at all. The Grim case replays the auto's tick order with the mod's own primitives on a vanilla server.
- **97-sim-menu-redo (SimMenuRedoTests, mod sim-menu-redo-1015, 2026-10-15):** the Dungeon Sim menu redo. Presses the
  title screen's real Dungeon Sim button (designer first, last page after), reads text runs for a room count or header
  page title, checks the Routes sort against SimRoomRoutes per row, and samples chip faces from screenshots. Two traps
  found while writing it: at the gametest's own window `takeScreenshot` returned only the top-left 854x480 of the frame
  (the panel half off the shot), so it resizes to 1920x1080 GUI 2 first; and a GUI point maps to the shot through the
  mod's `AutoScale.appliedFactor` (`x * f * window / guiScaledWidth`), not `img.width / screen.width` - that sampled
  the backdrop and read every chip as #040302 on both jars.
- **93-solve-{icepath,icefill,higherblaze,lowerblaze,creeperbeams,tictactoe,waterboard-chest}-timing, 1015-grim-puzzle-nowait**
  (mod puzzles-nowait-1015, 2026-10-15): the no-wait rule for every other auto. `PuzzleTimingExtra` stamps clicks, uses,
  sneaks and landings with the client tick and judges click -> use, sneak -> warp use, landing -> next action and ready ->
  click at 1, plus TurnFirst's foreign-wait counter. Main: sneak -> use 2 everywhere, landing -> shot 6 in Blaze/Beams/Ice
  Path (the gate's teleport settle), Tic Tac Toe click -> warp 3 and landing -> click 7; the branch 1 throughout. In the sim
  the camera wait never showed on main (the counter read 0 in every room), so the Grim case's turn rounds are what prove it:
  main held the click with foreignWait counting, the branch turned and clicked the next tick. Waterboard-chest passes on
  main too (a regression guard, not a failing-first case). See docs/solve.md for the recorder's traps.
- 1900-video-general (`ui/VideoGeneralSuite`, `ui/VideoRecorder`, `VideoSim`): footage for the YouTube pilot, only when
  named, `-PvideoOut=<dir>`. One frame per 1/60 s of game time: the client ticks on every THIRD frame and the two between
  are the screenshot re-rendered at `withDeltaTicks(1/3, 2/3)` (TestScreenshotOptions), so the world runs at real speed
  at 60 fps; the camera is posed per frame with xo/yRotO equal to the new values so any partial tick shows that pose.
  `gui.MenuAnim` and `mainmenu.MainMenuScenes` clocks are stepped 1/60 s a frame; the sim run's timer and the vanilla
  title fade run on the wall clock and are kept out of shot. A screenshot never contains the OS cursor: positions go to
  each clip's `cursor.csv` and the edit draws it. The Menu Theme is picked on Home's chips (there is no Menu Theme card,
  2026-10-15); in the HUD editor, Show Unseen puts the Storage Overlay's large preview over others, so a scroll "over" a
  small element scaled the overlay - film with Show Unseen off, and put the dragged element back (later clips film it).
  Load the room database before filming the sim menu, or Load a Room's Type/Secrets/Crypts columns read "-". The sim's
  rooms are near black on camera even at Brightness: Bright, so the sim clips give the camera Night Vision. Capture runs
  ~9 frames a second at 1080p. Typing /killer560 now uses `MenuOpenSuite.realKey` (see 1920); the FALLBACK note stays
  as a guard. Each recorder starts its clock a second past `MenuAnim.now()` (see 1921).
- 1920-ui-killer560-typed / 1921-ui-video-clock (`ui/MenuOpenSuite`): the two 1900 oddities, both the harness. 1920:
  Fabric's `TestInput.pressKey` calls `KeyboardHandler.keyPress` straight from `runOnClient`, but GLFW's callback wraps it
  in `Minecraft.execute`, so in the real game the chat Enter runs as a task and the command's own `client.execute` is
  QUEUED (ReentrantBlockableEventLoop.runningTask) until after ChatScreen's unconditional `setScreen(null)`. Through
  TestInput the execute runs at once, the menu opens and ChatScreen closes it (and keeps '/killer560' as a draft for
  the next T). `realKey` delivers a key the GLFW way; use it for any typed command that opens a screen. 1921: every
  `VideoRecorder` restarted the driven clock at 1,000,000 ms, behind where the previous clip left it; the next clip's
  menu, opened before its recorder, stamped its open fade at the later time, and `MenuAnim.progress` reads a start 0-1 s
  ahead as 0, so the whole menu vanished for a second once the new clock came within 1 s of the old end (Home 14.6 s
  after the 15.6 s sim-run clip, Profiles 24.6 s after the 25.6 s HUD editor clip). Failed 70/90 frames before the fix.
- 97-sim-menu-tidy (2026-10-15) reads a table's text runs by row band. A row scrolled half under the headings has its TOP
  in the heading band, so classing runs by where they start called it a heading and "found" it overlapping one, on the
  fixed jar; the screen's scissor hides that part. Class a run as a heading only if it ENDS above the rows, and clip row
  runs to the visible band. The menu's gradient title is one string drawn three times plus a shadow: same text at the
  same x is one run, not an overlap.
- **115-hx-autogfs-unknown-command** (2026-10-15): the Hx server has no `/gfs` until a case registers that responder root
  (140, later), so vanilla answers "Unknown or incomplete command" exactly as p3sim did. Auto GFS (pearls, skip-if-none off,
  5 s interval) must send once and stop, logging the stop once. Main sent 5 in 20 s. Run one Hx case with
  `-Scenario "100-hx-session:,115-hx-autogfs"`; the case resets `util.SackCommands.dead` because 115-hx-architect-draft's
  `/gfs` gets the same answer just before it.
- 1015-menu-term-hidden-slots(-off) (term-hover-1015): with the terminal Custom GUI on, every type x {a hidden grid slot,
  a hidden inventory slot} outside the panel: hoveredSlot null, 0 px changed by the hover, 4 clicks + 1/drop/pick keys
  reach the server as nothing; a real click on a custom square = exactly the solver's target (slot/button/input). Main
  failed all 12 (1024 px highlight, SWAP/THROW sent). The -off control proves the checks can see vanilla's hover.
- 96-ar session order (ar-merge-regress-1015, 2026-10-15). A case inherits util/RightClick's state from the one before:
  96-ar-awaitskip's crypt Hyperion clicks were left unpaired (the sim's ability hook ended vanilla's right click after the
  block half) and 96-ar-chain after it waited a tick at every corner, on the deployed jars too; alone, chain passed. And the
  sim's chest window arrives 1-2 ticks after a click, racing the warp an await fires: 96-ar-1015-reaim's control read 2 for 1
  in a session, so it reruns a side whose log says the window arrived after the await was met. A case that only passes alone
  is not passing.
- 147-sim-map-precompute (mod map-precompute-1015): three generated F7s, four one-node start routes each; phase 1
  readiness after the gate opens, the same press with TripCache on and off (its `testDisabled` hook; it has no setting, it runs whenever the Interactive Map is on) from the same spot with the planner's own
  fresh plan before each, phase 2, a block across a cached trip's first warp with TripCache's drop and searches held off
  (`testNoCorridorDrop`, `testPaused`), a doorway walled up and opened, and the Interactive Map switched off (cache empty,
  no search, no tick work) and on again with the map screen closed (fills). A press on a room with a recorded spot (Tic
  Tac Toe, Quiz, the other `AutoClearUtils` overrides) is a BLOCK trip to `overrideGoal`, not a tile trip: the first
  fresh-plan comparator planned the tile and read 19/5/5 warps beside the press's 20/6/7 (the OFF press agreed with the
  cache every time). Resolve the goal the way `pathToRoom` does before comparing.

- The title-scene allocation probe (`MainMenuScenes.allocBytes/allocQuads`) counts only the GuiRects batch's parts, so a
  picture scene's ripple tiles (each a blit, allocated like a part) inflate bytes-per-quad: 1680's Milky Way with its cape read
  242.8 B/quad against STARS' 163.7 until the tiles joined the denominator (176.4 then, 2026-10-16).
- **Count the executor's hops after a tick mark, never by the record's size** (predeploy-1016). `ClearExecutor.hopSendTicks`
  keeps only the last 200, so once 147-sim-map-precompute, 141 and 147-lastsecret have run in the same client the size
  stays 200 and a size delta reads 0: 1015-sim-clickgap reported "no press made 2+ hops" for a 222-block trip whose
  every click the server saw. `SimEtherTicksTests.hopMark()` / `hopsSince(mark)`.
- **A scenario that switches a feature on must switch it off in its `finally`.** 147-sim-autosecret-lastsecret left
  Secret Aura (and Auto Close Chest) on, and the aura then clicked 96-ar-1015-reaim's chest in its 10-tick settle, so the
  await was met before the test's mark and the run read met=false, acted=-1 - which looked like a regression in Auto
  Routes. The 96-ar session now turns the aura off at its start as well.
- 96-ar-1015-reaim's chest-window race can hold one phase for a whole stretch (5 of 5 mismatch attempts). The window
  costs both sides the same tick (control 1 -> 2, mismatch 2 -> 3), so a side that only ran with the window is paired
  with a window run of the other.
- 95-perf-sim (fps-sim-1015): a few seconds of work in `ctx.computeOnClient` (a block walk over a whole sim floor) froze
  the client long enough for run-scenario's "Not Responding" watcher to kill it, exit -1, twice, with no assertion in the
  log. Off the server thread `getChunkNow` found no chunks, so the hash read as "0 non-air". Queue such work with
  `server.execute` and poll for the result with `ctx.waitFor`; never join it from the test thread.
- 513-ui-menu-tooltips depends on what ran before it (fps-combined-1016, 2026-10-16): in the menu set
  (301,303,305,306,311,320,511-518) it fails on main 2231f84d as on the fps branch, "lists 'Bazaar/hypixel menu key' as
  changed, but it shows the fixture's text", because an earlier case leaves the Bazaar Browser on and that row only shows
  while it is on; run alone it passes on both. Compare a failure in that set with main's run of the same set, never with
  a lone run.
- 1016-hx-stale-* (fps-combined-1016): a cached value checked against a "fresh" build must get the fresh one off the
  client thread (the mod caches only there), but the gametest thread may not call `Minecraft.getInstance()` at all
  ("cannot be called from the gametest thread"). Build it on a plain thread started and joined inside `computeOnClient`.
- 95-perf-ar-render (ar-render-1016): on 26.2 the same jar draws overlapping translucent world shapes in a different order
  from one launch to the next. Mod main's Auto Routes shots against themselves differed by up to 21,066 px (Filled, edit
  mode) while two launches of 26.1.2 differ only by an animated fire texture (31-39 px). Never judge a 26.2 world-render
  pixel compare from one pair of launches; compare 26.1.2, and on 26.2 look for a launch that matches.
- 1771-1775 (sim-reset-1016, `SimResetTests`): the sim's `/reset`. A room's blocks are compared against the load's over the
  load's own box (`SimBuildQueue.touchedBounds` grows with every build, so re-reading it after the reset compares two different
  boxes). 1775 needs the testmod's server-side `/reset`; it reads the client's command tree (`getConnection().getCommands()`)
  for whose node `reset` is: a client command copied in by Fabric has a command lambda from `ClientCommandInternals`.
  96-ar-chain and 96-ar-398-regrow fail together in one run of `96-ar-398-regrow,96-ar-breakerwait,96-ar-complete,96-ar-play,
  96-ar-chain,96-ar-mapopen,96-ar-920-nostart-mapwarp,96-ar-398-offnode,96-ar-405-startwarp` on mod main 8a9f9549 as well,
  and pass run alone: an order effect, not a regression.
- 1961-1979 (sim-grassruin-lever-1016, `SimLeverGateTests`): room levers drop their gates. Gates are written against the
  mod's BUNDLED captures, but the library prefers a local capture, and Mod Only Test's Overgrown is the bundled one turned
  180 degrees: each gate is turned to the quarter turn that lands its lever on a lever before anything is read. Stand in
  reach by distance to the lever's block BOX, not its centre (Purple Flags' hanging lever is 4.4 / 4.9). The numbers
  1781-1798 were taken first and collided with 1790-sim-map-leave-secrets - a filter of bare digits ran another class's
  cases; grep the testkit for a number before using it.
- 1980-1994 (sim-rails-spider-1017, same class): 1980 checks `/simwhere` (`SimWhere.roomNameAt`) on every column of each
  solo room's two outer rings, and reads his own /simwhere points back - Rails' world (-81,-57,-95) must be capture
  (56,51,42), which also proves a solo build lands where his did, so his world coordinates can be turned into capture ones
  this way. 1981-1994 are the wall-lattice gates (Rails, Spider), Buttons' two levers and the sweep's rooms. 1408 now
  asks for at least its levers armed, since Buttons has a second one.
- 1790-sim-map-leave-secrets (`SimMapLeaveSecretsTests`, mod sim-map-secrets-1016, 2026-10-16): a room's last secret
  collected as he leaves must still show on the map. Two-room map code (R with the secret, NORMAL door, 1x1 B); the server
  is told all but one of R's secrets, the map must read (N-1)/N, then the last is collected for real and an END_SERVER_TICK
  hook moves him out the moment `SimScore.foundInRoom` reaches N. The hook needs TWO phases: before the mod's listeners for
  a secret counted while the tick's packets ran (chest, essence, key, a bat counted by SimMobs' queued task), after them for
  one the mod counts in its own END_SERVER_TICK (an item) - otherwise "same tick" is one tick late for half the types.
  Main fcba4045: 18 of 20 fail; the two passes are one-tick cases where the ten-tick action bar fell on that tick, so a
  one-tick pass on an old jar proves nothing.
- **96-ar-1016-keyawait / -keyaura / -keyreach** (aura-skull-place-1016): the Redstone Key in the 96-ar arena, answered by
  the sim server through `SimRedstoneKey.armForTest` (a jar without it SKIPs). The aura's done-set is per BLOCK for the
  whole run, so each case needs its own key spot: keyreach first reused keyaura's (13, F, 14) and the aura never clicked it
  ("premise: Secret Aura did not take the key"). 1415 and 848 also open an `AwaitEvents` window and require exactly one
  secret for the placing and none for the pickup.
- 93-solve-boulder-speed-*, -*blaze-speed, -<room>-resume, 1016-grim-puzzle-speed (2026-10-16): his sim speed 600, every
  rotation, done-to-resume per puzzle, and the Boulder run out and Blaze move-ahead on GrimAC - see docs/solve.md "Speed round".
  An earlier case's "PUZZLE SOLVED!" line can reach LogTap after the next case's mark: match chat by the room's name.
- **96-ar-1016-auranoawait / -auraawait, 62-argrim-1016-auraawait** (aura-await-only-1016): Secret Aura only clicks on a
  route node waiting for secrets. noawait sprints a WALK route past a lever, a chest and an essence skull (each 2.3 from
  the eye) and over an item: 0 use_item_on, item picked up; then, no route running, the same blocks are taken (the
  control). On the fcba4045 jar the aura flipped the lever mid-walk and the walk never reached its end node. auraawait:
  an await:3 WALK start node, the aura on once it waits: 3/3 met, the walk goes on, the route completes. The grim case is
  the lever half of both on GrimAC (it fails on fcba4045 too: "the walk route did not run to its end"). Close a chest's
  window in the poll: the aura is gated while a container is open. The first noawait sat its lever on keyreach's key spot
  (14, F, 13): alone it passed, after keyreach the done-set held the block and the control failed; its blocks moved.
- **2761-2772-menu-bzflip-*, 2775-logic-bazaarflip** (bazaarflip-custom-1016): Hypixel's cut Instant Buy title and
  Custom Amount's sign (`HxSigns`: a `sign` slot action opens a sign prompt, `MixinServerSignUpdate` hands the update
  back and records `sign.update` with its lines). A sign sent to the client as packets only (no block in the server's
  world) was dropped a tick later - its BlockEntity.isRemoved went true and the editor closed with nothing typed - so
  HxSigns places a real sign at the player's feet and puts the old block back on the update. Cases that stop with units
  unsold must `clear @a` on the SERVER: a client-side clearContent left 2768's stack for 2769 to sell.
- **1017-sim-dummy-lever-r0 / -r90** (`SimDummyLeverTests`): Red Blue on a two-room floor (horizontal at 0, vertical at 90),
  him inside the cage with both dummy levers and the real one in reach. A floor build can make the nook's chest the mimic
  (a trapped chest), so the premise takes either. The waypoint and packet checks are held to the end so a failing jar still
  shows what the aura did (main 098b6c92: both dummies listed as waypoints, 2 of its 3 use_item_on at the dummies).
- **96-ar-1017-bridges / -mimicaim, 62-argrim-1017-breakerwarp / -mimicaim** (ar-bridges-bat-1017). Bridges plays
  killer560's route exact; the sim's server drops the mod's own Dungeon Breaker use on purpose (that use IS the pairing),
  so only an `ASPECT_OF_THE_VOID` "dropped, as on Hypixel" line counts as an eaten warp. 96-ar-1016-eaten now clears the
  breaker slot, or the route pairs the chest click first and the resend it tests never happens. The first Grim mimicaim
  probe stood the chest 2.5 blocks across and its own hand click drew FarPlace + RotationPlace: keep a probe's clicks in reach.
- **148-sim-autosecret-bloodfirst / 366-logic-secret-bloodfirst / 62-argrim-1017-autosecret-bloodfirst**
  (autosecret-bloodfirst-1017): Auto Secret's Secret Blood First, one fresh F7 per mode (a route that ran this run counts
  its room as secreted for the rest of the run, so one floor cannot be reused across modes); 141's setup with one rush mob
  room R left uncleared. The sim's blood door opens on a plain click and it builds no wither doors, so on the sim Door Camp
  is one door; the Grim arena has no blood door, so 62's case only proves the "not on the map yet" fallback is clean.
- **1017-fairy-* Auto Fairy Souls rework (`FairySoulTests`, fairy-souls-1017).** -overshoot runs one L route (a step 4 before
  the corner) at walk, sprint, Speed II, Skyblock 400 and 600, and Jump Boost II with sprint and 600, and logs how far past
  the corner and past the soul he ever got (bounds 1.0 / 0.5); -turning compares two identical runs' per-tick body turns;
  -freecam checks the held view against the turning body, the Start/Stop key, a mouse move (no stop), J and a left click
  (stops, by name); -soulsoff runs with the Fairy Souls switch off and the graph unloaded; -settings reads both tabs' rows
  in reading order, saves and reloads every setting and migrates a version-1 file (the config file is backed up and put
  back); -water/-ledge/-pit/-lava/-stuck build each place to get stuck in and need a way back planned and the soul; the
  -escape-* trio is one sealed pit with an AOTV (Hx `dungeon.abilities`), with pearls, and with neither. Traps: reset the
  found log only after `ProfileTracker` reads "Profile: Test", or the reset clears "unknown profile" and the run finds every
  soul already logged; a soul answers nothing here unless the case's loop does, so a run left near one gives up after its
  clicks; a recovery case that ends at the soul may never "complete" its way back (the click cuts it short); and a player
  spawned straight into lava dies before the run starts - stand him on a glass block in the pool and let the walk take him in.
  On the 26.1.2 cheat jar of fairy-souls-1017 all 21 fairy cases pass, GrimAC clean; on the legit jar 746 and -settings.
- 1018-pf-*: the mod's `ChatObserver` drops a line identical to one delivered in the last 250 ms (real time) as a duplicate, and
  the lockstep runs two ticks far faster than that, so a case that sends the same chat line twice must sleep ~400 ms between
  them or the second never reaches any listener (1018-pf-dual-list's first run read "not dual-listed" for exactly this).
- 1018-mob-own/scale/share/settings/all (ui/MobSkinCases, mod todo-mobskin-1018): Play as Mob. They read the render state
  `extractEntity` hands the level renderer and the mod's swap counter during real F5 frames, and count pig-pink pixels
  (r>=100, r-g>=30, g<=b<=r: the shaded pig in the test world is about (120,70,90), so a bright-pink rule found 30 px).
  Named outside the -ui- tag on purpose; suites ui and k560 list 1018-mob-. All 5 fail on main f0a61ba4, pass on 26.1.2 and 26.2.

- **1018-ui-* (menu-hud-1018, 2026-10-18; `ui/Doc1018MenuHudCases`, 21 cases in the UiSuite block `DOC_1018_MENU_HUD_CASES`).**
  Drawer rows are read by building the registered tab (`build(MenuACases.leaf(...))`, label = `ModScreenDriver.label`) and
  pressed with `ModScreenDriver.press`; a feature that is not there makes `MenuACases.leaf`/`openTab` throw "no feature tab X",
  which is the main-jar failure of the new-tab cases. Pixel probes are relative (a row's brightness range, a same-row
  reference, the HUD off vs on over the panel's box) so no absolute colour is assumed; each has a positive control or was
  seen failing on the main jar (c099a9fa). A new settings row must not reuse another row's label: the tooltip table is keyed
  by label, and the Solver Highlight rows first clashed with Tic Tac Toe's own "Highlight Style" and Breaker Aura's tooltip.
  Run: `./run-scenario.ps1 -Scenario 1018-ui -ModUnderTest <jar> -Port 27803` (about a minute with the build warm).
- 1018-ss-* (SsStimMomentumCases, todo-ss-stim-1018, 2026-10-18): Auto Solve Rotate mode with Stim and Momentum on a 4-round
  device, 831's set-up. Judged by the server: 10 correct presses and EXACTLY 11 use_block (Stim must send nothing), then the
  mod's counters prove the path ran (stimRehearsalsStarted/stimButtonsTraced, momentumFaceFires/GlideFrames/CarryFrames).
  1018-ss-rotate-fast-off never touches the new settings, so it is the control that also passes on main; the other four fail
  there (no setter). The fake's gap between rounds (~20 ticks) cuts some rehearsals short at the next reveal - expected.
- **1018 blood-ap3** (mod blood-ap3-1018). `63-ap3-session:,1018-ap3` adds two AP3 cases to the 63 session: 1018-ap3-ss-wait
  (a LOOK with the `ss` modifier, stood in by a STOP; Goldor's line then a real "completed a device!" tellraw; needs
  `DungeonState.setRoomSim(true)` for the P3 tracker's F7 check) and 1018-ap3-run-centre (two overlapping RUN boxes, B takes
  over at the tie point, not its entry edge; sample from before B - with 4-block boxes the first sample after walkIntoRun was
  already inside B, so a jar that fired B on entry would have looked right). `100-hx-session:,1018-bloodcamp` drives the Blood
  Room gate with the map type forced to NORMAL through `BloodCampFeature.setTestMapRoomType`. `1018-autopilot-eta` is a logic
  case in SimAutopilotTests; `1018-autoclear-eta` (SimAutoClearEtaTests) runs Auto Clear on two sim rooms and reads the Auto
  Clear Status HUD's "Done in ~m:ss" line counting down.
- 1018-freelook-setting/-toggle/-hold/-pan (`FreelookTests`): Freelook's F5 rear view (Toggle, and Hold via `holdKey(o -> o.keyTogglePerspective)`) and pan key (`FreelookFeature.panHeldForTest`), each checking the camera type and the rendered `Camera.yRot/xRot` moved while the player's yaw and pitch did not, GrimAC clean. The first `moveCursor` after joining a world is dropped by vanilla (`MouseHandler.ignoreFirstMove`, set when the mouse is grabbed), so a case that measures a mouse turn spends one move first; without it the pan read "camera 0.0". Main fails all four (no `FreelookConfig`); the fix passes 4/4 on 26.1.2 cheat and legit and 26.2.
- 131-sim-midas-1020 (`SimAutoClearMidasTests`) and 62-argrim-1020-midas (mod midas-1020): Auto Clear in King Midas must
  superboom the altar (35-slab lid) before the mob exists. The sim one builds King Midas alone; scan for the lid around his
  feet, never at a fixed y (the sim's altitude put it at y -58 and a 40..130 band read 0 slabs). The Grim case pastes King
  Midas over the session room, so it runs last, and must leave and re-enter the dungeon (sidebar) for the live map to
  re-identify the cell; `HxAbilities.superboom` now blows a smooth stone slab lid whole, and the test places the starred
  mob only once the lid is gone (Hypixel's part). Run one case with `62-argrim-session:,62-argrim-1020-midas`.
- Menu suite fixes of 2026-10-22 (mod menu-fix-1022). The menu session is ONE world, so state that the mod clears only on
  a world change leaks from case to case: Fast Leap's Teammates kept 829-834's LrA-LrD, and 983's "leap to the Tank" went
  to LrB (clear it first). A case that writes a mod file the mod also saves on a writer thread must drain that writer
  before writing (732 read a half-swapped Croesus log and saw a fresh one). A pixel compare over a screen with no
  backdrop (Terminal Custom GUI) sees the world: stand at y 300 looking up and measure two cursor-away frames first (1015
  read moving water as 2889 hover pixels). 513 dumps every row's tooltip to menu-tooltips-now-<variant>.json; write
  menu-layout-merged.json entries from that, and use "renamed" for a label rename so the new key's text is still checked.
- Menu suite stability, 2026-10-22 (later the same day). Teleporting to y 300 is not standing at y 300: he fell back into
  the water during 824/1015, so `MenuKit.stillSky` puts a barrier block under him and waits until the client sees him
  standing on it. Leap cases leave him off the platform (in the lake beside it); `MenuSuite.home` puts him back before
  every case, but only while the client has the platform's chunk - a 520-block teleport into unloaded chunks drew
  GroundSpoof + Simulation flags (1850). 2762's flake was a mod bug (the flipper bought twice through its own clicked
  Confirm page) and 890's half was one too (a refused Windows file replace dropped the Croesus save): a flaky case is
  worth reading for a real race before the test gets the blame.
- 1021-theme-music/-font/-bg-import/-sound-import (`ui/ThemeExtrasSuite`, mod themes-1021): each menu theme's own music
  (the sound engine's channel and its answer, not the muted speakers), font (built id and the menu's text widths), and
  imported GIF/video background (draw and upload counters plus the title screen's own pixels) and music (the mod's
  stream hook opened the file). ffmpeg makes the test media; without it on the PATH those halves note SKIPPED.

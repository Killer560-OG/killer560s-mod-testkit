# Per-scenario lessons

Split out of [CLAUDE.md](../CLAUDE.md) on 2026-10-06 to keep it under its size limit. Problem, then fix; verified only.

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

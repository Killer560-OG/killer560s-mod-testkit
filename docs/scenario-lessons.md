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
  height at every shot (2026-10-07).
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

- 96-ar-398-* (mod ar-node-proc, killer560's 2026-10-07 Museum log): `-offnode` stands him in #10's ring at spots the
  recorded look misses #11 from, one where the mod's etherwarp aim finds a ray and one where none exists, standing and
  dropped in from 0.6 up; `-regrow` loads Museum his way (`SimBuilder.buildSingleRoom`), breaks, rebuilds and breaks again so
  a DELAY await on #7 fires after the first run's 10 s regrow is due (route of 3 nodes, so #7 logs as "Node #3"); `-startawait`
  presses Go + Secret from his own spot with Secret Aura and Auto Close Chest on. All three fail on mod 6f3515ba. Two traps
  found writing them: the sim's first aura click on a chest right after a map warp does not always open it, and the aura's
  retry a second later is counted, so "await met" alone passed on the old jar - assert it was met within 10 ticks of the
  start. And wait for the node's own "acted" line, not any landing line: the previous sub-check's stack can still log one.

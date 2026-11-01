# Minecraft 26.3 (the main version since 2026-11-01)

killer560, 2026-11-01: 26.3 is the main version, 26.2 the second, 26.1.2 is dropped. The testkit follows: `gradle.properties`
builds 26.3, `-Pminecraft_version=26.2` switches the whole build (build.gradle's `versionsByMinecraft`), and every script
defaults to `-Minecraft 26.3`. The 26.1 compatibility layers are gone.

| | 26.3 (default) | 26.2 |
|---|---|---|
| Fabric API | 0.165.0+26.3 | 0.160.0+26.2 |
| Fabric Loader | 0.19.5 | 0.19.5 |
| cloud-fabric (GrimAC needs it) | 2.0.0-beta.17 | 2.0.0-beta.17 |
| GrimAC | 2.3.74 (release) | 2.3.74 (release) |

```
./run-scenario.ps1 -Scenario smoke                         # 26.3, the newest *-26.3-cheat.jar snapshot
./run-scenario.ps1 -Scenario smoke -Minecraft 26.2
./gradlew runClientGameTest -Pscenario=smoke -PmodUnderTest=<...-26.3-cheat.jar>
./gradlew grimServer [-Pminecraft_version=26.2]
```

## GrimAC: 2.3.74-8eb5f28 (alpha) -> 2.3.74 (release)

The old pin was a Modrinth ALPHA dev build of 2026-09-10 whose `fabric.mod.json` declares minecraft `>=26.1.2 <26.3`, so it
cannot start a 26.3 server. 2.3.74 is the stable release of 2026-10-21 (Modrinth version `FNJlsq5U`, sha512 starting
`26dd646ec414ab59`), declaring `>=26.1.2 <26.4`; it was the newest stable release on 2026-11-01 (newer builds, 2.3.75-*, are
all alpha). 26.3 support landed in the alpha builds from 2.3.74-8897f37 (2026-10-20, "Publish Fabric builds through Minecraft
26.3"; "Support 26.3 (#2888)").

What changed between the two jars (checked by unzipping both, 2026-11-01):
- `ac/grim/grimac/checks/**`: byte-identical. No check class was added, removed or changed.
- `ac/grim/grimac/predictionengine/**`: changed - `MovementCheckRunner`, `PlayerBaseTick`, `PointThreeEstimator`,
  `UncertaintyHandler`, `EntityFluidInteraction` (+ a new `CurrentAccumulator`), `GrimProcessor`. The changelogs name: honey
  slide read last tick's x/z (#2948), powdered snow falses since 1.21.5, collision velocity reset order, canGlide rejecting
  non-elytra gliders (#2915), pistons tracked from client block-action packets (#2958), block update only when the block
  changed (#2932), Reach/Hitboxes falses with ViaBackwards' emulated tick end (pre-1.21.2 clients only).
- PacketEvents 2.13.1 -> 2.14.1 (Adventure 5 serializers), GrimAPI 1.6.0.12 -> 1.6.0.13, datastore lease recovery fixes
  ("Recover logging after ownership lease expiry", "Retry incomplete session recovery").
- Default config: one new key, `experimental-chunk-sharing: false`. `experimental-checks` still defaults to false.
- The testkit's GrimAC probes (`grimprobe/GrimPlayerAccess`, `MixinGrimQueuedPlaces`, the `[testkit-gap]` line) target
  fields and a method that are unchanged (javap of both `common` jars).
Full changelog text between the two: `killer560s-mod-logs/grim-changelog-8eb5f28-to-2374.txt` (machine-local).

Measured 2026-11-01 (26.2, mod main 67b77105 cheat, both Grims in parallel checkouts; per case status and per-check line
counts): smoke, 62-argrim (51 rows), 63-ap3 (120), 1025/1026-blink (74), 1027-blink-c (12), 1027-iw + 1025-invwalk (17) and the
zpew sets (41) behave the same on both builds. The only differences were one-off load events that did not reproduce
(1027-zpewc-refused-300 after a 4.2 s server stall: 3/3 clean on each Grim on rerun) and Grim's own controls with no mod
feature on (1027-blink-c-menus g0, tpsprint Charge-off rows). Details: killer560s-mod-logs/tkport263-1101.md.
On 26.3 the server console prints a player's alert as `System chat: Grim » ...`; the flag reader matches it as before.

To run a set on the OLD Grim for comparison (26.2 only; it refuses 26.3):
`-Extra @("-Pgrim_version=2.3.74-8eb5f28","-Pgrim_url=https://cdn.modrinth.com/data/LJNGWSvH/versions/enIsH6sX/grimac-fabric-2.3.74-8eb5f28.jar")`.
`prepareTestServer` removes any other `grimac-*` jar from the staged server, so a checkout switches cleanly both ways.

## What 26.3 moved (javap of the 26.3 merged jar; all behind compat now)

- **GLFW is gone; input is SDL3** (`lwjgl-sdl`). `InputConstants.KEY_*` are SDL scancodes on 26.3 (ESCAPE 41, KEY_1 30,
  RIGHT 79, LSHIFT 225) where 26.2 has GLFW codes (256, 49, 262, 340). The mod's 26.3 port keeps every STORED key code in
  GLFW numbering on both versions (its `util/Keys`, translated at the boundary by `compat/McInput`), so the testkit does
  the same: a key code written into a mod setting (`setHoldKeyCode`, `setKeyCode`, config JSON) stays a GLFW number, and
  only a press through the input API (`pressKey`/`holdKey`/`releaseKey`/`holdKeyFor`) or a `new KeyEvent(...)` takes the
  NATIVE code, `McKeys.nat(glfw)` (identity on 26.2, a GLFW->SDL table on 26.3 that throws for a key it cannot map, e.g.
  keypad divide 331). Code that already passes `InputConstants.KEY_*` to a press (MenuACases.key, MenuOpenSuite) is native
  and must NOT go through nat. `KEY_F25`, `KEY_LSUPER` and `KEY_RSUPER` exist only on 26.2.
- **`ServerboundSwingPacket` is gone.** A left click sends `ServerboundPunchPacket.INSTANCE` (a field-less record, no hand)
  after a local `swing(hand, item.getAttackAnimation(), false)`; the server answers it with its own swing
  (`ServerGamePacketListenerImpl.handlePunch`). `McPackets.isSwing(packet)` and `McCompat.swingArm(player)` cover both.
  PacketTrace records it as `"punch"`, so a count by id uses `McPackets.swingId()` ("swing" / "punch"), never the literal
  `"swing"`: 62-argrim/96-ar awaitskip, dbedit and 657 failed on 26.3 for that alone (ar263-1101; the vanilla control
  click sent 4 punch + 2 player_action exactly where 26.2 sends 4 swing + 2 player_action).
- **Mouse buttons are SDL's, 1-based**: `InputConstants.MOUSE_BUTTON_LEFT` 1, MIDDLE 2, RIGHT 3 (26.2: 0, 2, 1).
  `AbstractWidget.isValidClickButton` is `button == 1`, Fabric's `pressMouse(n)` passes n through raw, and
  `ContainerEventHandler.mouseClicked` returns true for any child under the cursor even when the child refused the click
  (javap 26.3). So `new MouseButtonInfo(0, 0)` or `pressMouse(0)` clicks NOTHING on 26.3 and a "took" check still passes.
  Every scenario builds buttons with `McKeys.mouse(glfwButton)` (identity on 26.2). This was 96-ar-path's missing "1",
  96-ar-screen's await dropdown and 62-argrim-goto's Go To (ar263-1101).
- The use packets are records: `ServerboundUseItemPacket.yRot()/xRot()/sequence()/hand()`,
  `ServerboundUseItemOnPacket.hitResult()/hand()/sequence()` (`McPackets.useItem*`).
- `com.mojang.blaze3d.pipeline.RenderPipeline` -> `com.mojang.renderpearl.api.pipeline.RenderPipeline`: the bench mixins
  `BenchGuiGraphics` and `BenchEntityRenderDispatcher` have one copy per version under `src/client/mc26_X/java`.
- `EntityRenderDispatcher.shouldRender` takes a trailing partial tick (`McCompat.shouldRender`).
- `LivingEntity.swing(hand)` -> `swing(hand, SwingAnimation, boolean)`; `LocalPlayer.drop(boolean)` ->
  `MultiPlayerGameMode.dropItem(player, boolean)` (`McCompat.dropSelected`).
- `OptionsScreen(Screen, Options)` (no boolean); `SignEditScreen(sign, SignTextSlot, boolean)`; `SignText` is built through
  `SignText.EMPTY.asMutable().setLine(...)`; `SignBlockEntity.setText(text, SignTextSlot)`;
  `ClientboundOpenSignEditorPacket(pos, SignTextSlot)`; `ServerboundSignUpdatePacket` is a record (`pos()`, `lines()`).
- `ClientboundMoveEntityPacket.Pos(id, VecDelta, onGround)` with `VecDelta.Linear(short, short, short)` (still 1/4096).
- `Entity.setInvulnerable` -> `setPermanentlyInvulnerable`; `monster.EnderMan` -> `monster.Enderman`.
- The 26.3 dedicated server writes `white-list=true` into a new server.properties (26.2 wrote false): every join was
  refused "You are not white-listed on this server!". `prepareTestServer` writes and patches it to false.
- The `sign_update` wire layout is pos, four strings (fixed list), then the slot (26.2: pos, front flag, strings):
  65-join-fingerprint decodes it with `McPackets.signUpdateBytesBeforeLines()`.
- authlib 10: `YggdrasilAuthenticationService` is gone (service discovery instead). `579-hx-mcp-account-swap-key`'s fake
  Mojang login is not ported: on 26.3 it is a SKIP row saying so.

The companion server mod (`src/testmod`) now has its own layer, `dev.testkit.server.compat.McServer`, under
`src/testmod/mc26_2/java` and `src/testmod/mc26_3/java` (identical public signatures, one on the source path).

Before merging a branch, compile it on both: `./gradlew compileGametestJava compileTestmodJava` and the same with
`-Pminecraft_version=26.2`.

## Machine data

The room-capture instance default is now `26.3 Mod Only Test` (`roomsInstance`), the instance that takes over everything
in `26.1.2 (Mod Only Test)`. A machine's `testkit.local.properties` that names the old instance keeps it until changed.

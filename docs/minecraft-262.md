# Minecraft 26.2 (runs: smoke, proof, seed and the UI group pass)

`-Pminecraft_version=26.2` switches the whole build, as in killer560s-mod. `versionsByMinecraft` in build.gradle
then supplies Fabric API `0.160.0+26.2`, loader `0.19.5` (his "26.2 mod only" Prism instance) and cloud-fabric
`2.0.0-beta.17`, unless those are passed with -P too; 26.1.2 with no flag is unchanged (dependency trees diffed
identical against master, 2026-10-04). Scripts take `-Minecraft 26.2`, which passes the flag and picks the
snapshot's `killer560smod-*-26.2-cheat.jar` (a jar named for the other version is refused):

```
./run-scenario.ps1 -Scenario "smoke,proof,02-seed" -Minecraft 26.2
./gradlew runClientGameTest -Pminecraft_version=26.2 -Pscenario=smoke -PmodUnderTest=<...-26.2-cheat.jar>
./gradlew grimServer -Pminecraft_version=26.2
```

GrimAC: the same pin, `2.3.74-8eb5f28` (its only per-version module is `grimac-fabric-mc261`). It DOES detect on 26.2:
smoke's positive control and proof's 46 verbose lines both came back on 2026-10-04.

Status 2026-10-04, first runs: `smoke,proof,02-seed` and the UI world group (301-306, 365) pass on 26.2 with the
26.2 cheat jar. What it took: `menu/*` goes through `McCompat.screen/setScreen`; `HxMenus` reads colours as the first
16 `ChatFormatting` ordinals (26.2 removed `isColor()`); the mod's own 26.2 startup crash (seven `Gui.extractRenderState`
mixins, fixed there in b0ae44cb; `365-ui-overlay-draws` proves an overlay draws); and `Scenario.connect` retries a join
that fails instantly (26.2 only: the client dials the moment the restarted server prints Done) up to 5 times, 2 s apart.

Not yet run on 26.2: everything else (sim, puzzles, menus, the 40-90 scenarios). `run/testserver` is shared by both
versions; scenarios delete the world, but a hand-played `grimServer` world opened on 26.2 cannot go back.

F1 on 26.2 is `Hud.isHidden()`/`toggle()` (no `Options.hideGui`); use `McCompat.hudHidden/setHudHidden`. Reflection on
`Options.hideGui` was a silent no-op there, and with the HUD up a fading chat line read as 149 px of Breaker Highlight
through a wall (388) and 8,616 stray crosshair pixels (389) on 2026-10-06 - the mod was right both times. Chat and toasts
are not hidden by F1 on either version; a screenshot diff calls `McCompat.clearChatAndToasts` first.

Version-specific API goes in `dev.testkit.compat` (`McCompat.screen/setScreen`, `McItems`, `McEntities`) under
`src/client/mc26_1/java` and `src/client/mc26_2/java`, one of which build.gradle puts on the client source path.
Both copies keep identical public signatures. Nothing in `src/*/java` may use `mc.screen`, `mc.setScreen`, a
colour-variant `Items.RED_...` constant or `EntityType.<CONSTANT>`: those are gone on 26.2 (the screen moved to
`Minecraft.gui`, colours to `ColorCollection.pick(DyeColor)`, entity constants to `EntityTypes`), and
`BlockPos.getCenter()` is gone too (use `Vec3.atCenterOf`). Before merging a branch, compile it with
`-Pminecraft_version=26.2` as well, or new code quietly breaks the 26.2 build.

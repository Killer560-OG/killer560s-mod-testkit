# Change requests from WP4 (UI, commands, config)

## Suite for the smoke group
- file(s): suites.properties
- change: add `ui-smoke=ui-smoke` (selects 301-306, the under-3-minute smoke on both jars)
- why: the plan's "ui-300-smoke-all" is a group of six cases named `30N-ui-smoke-*`; today it is selected with
  `-Pscenario=ui-smoke`, which run-suite.ps1 cannot take (it only accepts names from suites.properties)
- workaround in use: `./gradlew runClientGameTest -Pscenario=ui-smoke -Pport=25630 -PmodUnderTest=<jar>`

## WP4's allow/deny lists cannot live in testkit-fixtures/ui/
- file(s): src/gametest/resources/testkit-fixtures/SCHEMA.md, src/gametest/java/dev/testkit/gametest/Fixtures.java
- change: either drop `ui/` from SCHEMA.md's area list, or make `Fixtures.load` skip files whose root is a JSON
  object (today it throws "must be a JSON array of fixtures", Fixtures.java:119)
- why: the plan puts `ui/{deny-commands,deny-buttons,allow-screens}.json` under testkit-fixtures, but
  `Fixtures.load("")` walks every `.json` there as a Hypixel-input fixture array, so WP5's integrity sweep would fail
  on them
- workaround in use: they live in `src/gametest/resources/testkit-ui/` (read by `ui/Deny.java`)

## SimServerSafetyTests dispatches to a class that no longer exists
- file(s): src/gametest/java/dev/testkit/gametest/SimServerSafetyTests.java:94-110 (not WP4's file)
- change: look the dispatcher up on `net.fabricmc.fabric.api.client.command.v2.ClientCommands` (Fabric API for
  26.1 renamed `ClientCommandManager`; the mod imports `ClientCommands`, autokick/AutoKickCommands.java:7); find the
  getter by return type as `ui/CommandSweep.dispatcher()` does
- why: `Class.forName("...ClientCommandManager")` throws ClassNotFoundException (seen in WP4 run 1), the scenario
  catches it as "a command refusing loudly is a PASS", so it has executed no command at all and passes vacuously
- workaround in use: none (not WP4's scenario)

## Clipboard tripwire
- file(s): src/client/java/dev/testkit/harness/ (a new mixin) + src/client fabric.mod.json mixins list
- change: a mixin on `net.minecraft.client.KeyboardHandler#setClipboard(String)` that, when
  `-Dtestkit.denyClipboard=true`, logs `[testkit] clipboard write blocked: <first 40 chars>` and cancels
- why: three mod paths write the real clipboard through GLFW (auction/ListingHelperFeature.java:114,
  accounts/gui/AccountSwitcherScreen.java:370, routes/WaypointRoutesFeature.java:143); they start no process, so
  WP4's ProcWatch (370) cannot see them
- workaround in use: deny-buttons.json keeps every clipboard/copy/import/export label unpressed; 370 watches child
  processes, which catches the PowerShell clipboard writers only

## mod: clipboard hook
- change: `-Dkiller560.test.noClipboard=true` routing every clipboard write (the three GLFW sites above plus
  copychat/CopyChatFeature.java:146 and screenshotcopy/ScreenshotCopyFeature.java:65, which shell out to
  PowerShell) to a `[TestHook] would copy ...` WARN line, like ExternalOpen's `noExternalOpen`
- why: the OS deny list is only enforced by never pressing those buttons; with the hook the UI sweeps could press
  them and assert the line instead
- workaround in use: deny list

## mod: a count of FeatureGuard-style failures in screen render
- change: none required; noted that `HudEditorScreen.extractRenderState` swallows element render throws
  (hud/HudEditorScreen.java:149) and `MainMenuTheme.fail` disables itself with an ERROR. WP4 calls every
  HudElement.render directly to see the throw; a counter (like ChatObserver.failures) would let any WP assert it
- workaround in use: 304 renders each element off screen itself

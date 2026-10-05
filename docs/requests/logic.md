# WP5 (logic) change requests

## -PmodSource gradle property
- file(s): build.gradle
- change: `if (project.hasProperty("modSource")) systemProperty "testkit.modSource", project.property("modSource")` on runClientGameTest
- why: 351 checks every fixture's source file:line in the mod checkout; LogicSuite reads `testkit.modSource`
- workaround in use: `$TESTKIT_MOD_SOURCE`, else the default `C:/Users/Hunter/killer560s-mod`

## Fixtures: list-element refs
- file(s): src/gametest/java/dev/testkit/gametest/Fixtures.java, testkit-fixtures/SCHEMA.md
- change: let `matches`/`mustNotMatch` accept `Class#FIELD[i]` (element i of a static `List<Pattern>`/`Pattern[]`), as
  `logic.R.pattern` already does; document WP5's extra fields (gates, sender, senderGroup, channel, hostileExempt,
  parsedAsInt) in SCHEMA.md
- why: 43 catalogued patterns live in lists (PartyLeaderTracker.CLEARED, DungeonQueueFeature.PARTY_LEAVE,
  ArchitectDraftFeature.FAIL_PATTERNS, WeirdosSolverFeature.SOLUTIONS/WRONG ...) and Fixtures.checkPatterns cannot
  reach them, so they can never count as covered by other WPs' fixtures
- workaround in use: WP5 fixtures list them under `gates`, which only LogicSuite reads

## Docs: LogicSuite runs two cases in a world
- file(s): README.md / docs/wp/wp1-foundation.md (plan text says "LogicSuite at the title screen")
- change: note that 354/357 open a throwaway singleplayer world because ItemStacks need bound components
- workaround in use: documented in LogicSuite's javadoc and docs/wp/logic.md

## mod: PartyTracker.CLEARED accepts other players' chat
- file(s): killer560s-mod src/main/java/com/killer560/hub/leapmenu/PartyTracker.java:60-61 (onChat :138)
- change: anchor the disband alternative to a name, as PartyLeaderTracker.CLEARED[0] already does:
  `^(?:\[[^]]+] )?[A-Za-z0-9_]{1,16} has disbanded the party!$` in place of `.+ has disbanded the party!`
- why: `.+` swallows a chat prefix, so any player typing "x has disbanded the party!" in all/party/guild/DM/co-op chat
  matches and `MEMBERS.clear()` runs: the teammate list Party Commands authorises against (isTeammate) and the leap
  menu use is wiped. Repro: PartyTracker.MEMBERS = {Killer560}; PartyTracker.onChat(Component.literal("[VIP] Eve:
  Killer560 has disbanded the party!")) -> MEMBERS = []. Denial of service, not escalation (it removes teammates).
  Case 353-logic-hostile-anchors.

## mod: Auto Routes saves node yaw/pitch to 1 decimal
- file(s): killer560s-mod src/main/java/com/killer560/hub/autoroutes/RouteStore.java:412-413 (writeNode)
- change: `round(n.yaw, 5)` / `round(n.pitch, 5)`, the precision Ap3Store.writeNode moved to for the same reason
  (docs/AP3.md "A node's angle was being quantised on every save")
- why: an etherwarp node aims with its stored yaw/pitch (RouteExecutor.java:1581, AutoRoutesEditScreen.java:444);
  after a save/load 37.123456 -> 37.1 and -12.987654 -> -13.0, up to 0.05 degrees, about 0.05 blocks at 60 blocks of
  etherwarp range. RouteNode.equals (RouteNode.java:304) also stops matching the in-memory node. Case
  357-logic-items-ap3-routes.

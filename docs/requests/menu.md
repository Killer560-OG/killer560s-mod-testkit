# WP3 (menu) change requests

## Session: select cases without the session-name prefix
- file(s): src/gametest/java/dev/testkit/gametest/hx/Session.java (selection / caseSelected)
- change: a filter part that matches no session name but is contained in a CASE name should run that session with
  only those cases (today `part.contains(name)` is required first, so `-Pscenario=243-menu-slotbinds` runs nothing).
- why: `2NN-menu-*` cases do not contain `200-menu-session`, so a single failing case cannot be re-run with -Pscenario
  or -Pfailed (the -Pfailed list names cases, which then select nothing).
- workaround in use: MenuSuite reads `TESTKIT_MENU_ONLY=part,part` from the environment.

## HxEvents.custom: do not let data overwrite type/seq/tick/player
- file(s): src/testmod/java/dev/testkit/server/hx/HxEvents.java (custom)
- change: skip (or prefix with `data.`) keys `type`, `seq`, `tick`, `player` when copying `data` into the event.
- why: a module whose data had a `type` key lost every event of that kind (see docs/wp/menu.md).
- workaround in use: WP3 never uses those keys in custom data.

## Docs: HxChestMenu is the plan's "HxMenu"
- file(s): docs/wp/wp1-foundation.md
- change: note that WP3 builds on `HxChestMenu` (+ `registerScript`) rather than a second `HxMenu` class; per-menu
  state lives in `hx/menu/HxMenus` keyed by container id.
- workaround in use: none needed.

## mod: ItemProtect.isStarred name fallback checks the wrong characters
- file(s): killer560s-mod src/main/java/com/killer560/hub/itemprotect/ItemProtect.java:115-117
- change: compare against the master-star pips U+278A-278E (`'➊'..'➎'`, as RevertMasterStarsFeature.java:21-22 and
  autoroutes/ItemIdentity.java:129 do), not U+2780-2784 (`➀..➄`). The comment on :114 already says ➊-➎.
- why: case 270-menu-item-protect-starred-name: `isStarred` of a stack named "Hyperion ➌" (no NBT) is false.
  Low impact on Hypixel today because a master-starred name also carries ✪, which the same fallback accepts.
- workaround in use: 270 fails on purpose until the mod is fixed.

## mod: auction listing helper and Croesus prices need H1
- file(s): killer560s-mod util/ModNet (hook H1 of the plan)
- change: none beyond H1; noted so WP3 can extend 230/248 to priced profit and the copy button once
  `-Dkiller560.net.hypixel=<fake>` is served by a testkit fake.
- workaround in use: 230 asserts costs and unpriced count; 248 asserts the listed item and no match.

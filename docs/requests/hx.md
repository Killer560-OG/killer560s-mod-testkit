# WP2 (hx) change requests

## Server modules need to apply a sidebar / tab list
- file(s): src/testmod/java/dev/testkit/server/hx/HxPrimitives.java
- change: make `sidebarSet(MinecraftServer, JsonObject)` and `tabSet(MinecraftServer, JsonObject)` public static
  (or add `public static JsonElement HxBridge.dispatch(String op, JsonObject args)` that runs a registered op on the
  calling server thread).
- why: `surface.sidebar` / `surface.tab` (WP2 templates) can only RENDER the args today; the client then has to make a
  second call to `sidebar.set`/`tab.set`. WP6 wants a sidebar and tab driven from a server-side counter (secrets found,
  cleared %), which has no client round trip at all.
- workaround in use: templates render only; `HxKit.sidebar(...)` / `HxKit.tab(...)` render then apply from the client.

## Fake tab profiles should carry version-2 UUIDs like Hypixel's
- file(s): src/testmod/java/dev/testkit/server/hx/HxPrimitives.java (static block, `TAB_IDS[i] = UUID.nameUUIDFromBytes(...)`)
- change: build the 80 ids as version 2, e.g. `UUID u = UUID.nameUUIDFromBytes(...); TAB_IDS[i] = new UUID((u.getMostSignificantBits() & ~0xF000L) | 0x2000L, u.getLeastSignificantBits());`
- why: the mod tells Hypixel's fake tab/NPC entries apart from players by UUID version 2 (players/PlayerNames.java:131-133,
  namechanger/NameChangerFeature.java:99-102, teammates/TeammatesFeature.java:119). With v3 ids the fake "!A-a" column
  profiles look like offline-mode players, so `PlayerNames` caches them (case 131 notes `uuidFor("!A-a")`), which Hypixel
  never causes. Fidelity, not a crash.
- workaround in use: case 131 records the value instead of asserting on it.

## suites.properties: a muted-by-default note is enough
- nothing to change (master 4dd3082 already defaults the client to volume 0).

## mod: MagicFindTracker reads other players' chat
- file(s): killer560s-mod src/main/java/com/killer560/hub/rngmeter/MagicFindTracker.java:19-20, :37-38
- change: anchor MAGIC_FIND_PATTERN on the drop line's real start (or require the line not to carry a "Name: " chat
  prefix) instead of an unanchored find() on every line.
- why: case 109-hx-rngmeter-magicfind-forged: after a real drop set 164, the all-chat line
  `[VIP] HxEvil: (99999% Magic Find)` set getLastMagicFind() to 99999 (cheat and legit jars, f40ec89). Nothing in the
  mod reads getLastMagicFind() today (grep), so the impact is the tracked value only - low severity.
- workaround in use: none; 109 stays red until the mod changes.

# testkit fixtures

A fixture is one piece of Hypixel-shaped input (a chat line, a sidebar, a tab list, an item, a menu, ...) together
with WHERE IT CAME FROM and WHICH OF THE MOD'S PATTERNS IT SHOULD AND SHOULD NOT MATCH. Scenarios replay fixtures
through Hx; WP5's LogicSuite checks every fixture against the mod's own patterns and source.

Read by `dev.testkit.gametest.Fixtures`. Frozen after WP1; changes go to `docs/requests/<wp>.md`.

## Files

`src/gametest/resources/testkit-fixtures/<area>/<name>.json`, each a JSON ARRAY of fixture objects. Areas are owned
by work packages (see `docs/wp/`): `chat/` `sidebar/` `tab/` `hostile/` (WP2), `items/` `menus/` (WP3),
`ui/` (WP4), `logic/` (WP5), `dungeon/` (WP6), `boss/` (WP7). `prism-accounts-empty.json` at the top level is not a
fixture file (it is the empty Prism accounts store every run points the mod at).

## Fields

| field | required | meaning |
|---|---|---|
| `id` | yes | unique across ALL fixture files: `<area>.<slug>`, e.g. `chat.party-joined` |
| `kind` | yes | one of `chat`, `overlay`, `title`, `sidebar`, `tab`, `item`, `menu`, `stand`, `map` |
| `payload` | yes | what Hx sends. `chat`/`overlay`/`title`: a string (section-sign codes allowed) or `{"json": <component>}`. `sidebar`: `{"title": "...", "lines": ["..."]}`. `tab`: `{"entries": ["..."], "header"?, "footer"?}`. `item`: `{"stack": "<vanilla item syntax>"}`. `menu`: `{"title", "rows", "slots": {"13": "<stack>"}}`. `stand`: `{"name", "small"?, "marker"?, "helmet"?}`. `map`: free-form, documented by the WP that uses it. |
| `source` | yes | `{"file": "src/main/java/com/killer560/hub/...java", "line": 123}` in the MOD repo: the regex, literal, or quoted comment the payload was derived from. **Never invented.** A line nobody can point at in the mod's source (or in a real capture) is not a fixture. |
| `matches` | no | `["leapmenu.PartyTracker#TAB_REGEX", ...]`: `Class#FIELD` of `Pattern` constants (class relative to `com.killer560.hub`) the payload's text MUST match |
| `mustNotMatch` | no | same shape: patterns it must NOT match (forged lines, near misses) |
| `mode` | no | `find` (default) or `matches`: which `Matcher` call the mod itself uses for that pattern |
| `strip` | no | default `true`: strip section-sign formatting before matching, as most of the mod does (`ChatFormatting.stripFormatting`). `false` to match the raw text. |
| `verifiedBy` | yes | `"regex"` (agrees with the mod's own pattern - proves agreement, not Hypixel truth) or `"capture:<ref>"` (seen in a real log/capture; `<ref>` says where, e.g. `capture:26.1.2 (Dungeons)/logs/2026-09-29-3.log.gz:1234`) |
| `notes` | no | anything a reader needs |

The text a pattern is checked against is: the string payload for `chat`/`overlay`/`title`; each line for `sidebar`
(a fixture matches if ANY line matches); each entry for `tab` (same). Other kinds have no text and may not list
`matches`/`mustNotMatch`.

## Example

```json
[
  {
    "id": "tab.dungeon-teammate-mage",
    "kind": "tab",
    "payload": {"entries": ["[42] HxMateA (Mage L)"]},
    "source": {"file": "src/main/java/com/killer560/hub/leapmenu/PartyTracker.java", "line": 45},
    "matches": ["leapmenu.PartyTracker#TAB_REGEX"],
    "mode": "matches",
    "verifiedBy": "regex",
    "notes": "TAB_REGEX is matched against the stripped, trimmed display name"
  }
]
```

## Rules

- `verifiedBy: "regex"` fixtures prove the testkit and the mod agree. They say nothing about whether Hypixel sends
  that line. Say so whenever a result built on one is quoted.
- Hostile fixtures (`hostile/`) are lines another PLAYER could produce; their `mustNotMatch` lists every
  action-gating pattern they must not trigger.
- Keep payloads byte-exact, including section signs and private-use icons (write them as `\u` escapes in JSON).

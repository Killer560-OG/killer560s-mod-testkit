# Shelved: mining tests (until after the mod's 2.0 release)

killer560 shelved every mining feature of killer560s-mod on 2026-10-07 (mod commit aa09fd82,
`shelved/mining/README.md` in the mod repo). The mining classes are no longer in the jar, so the tests that drove
them are parked here or skip, and `411-ui-mining-shelved` asserts the jar, the menu and `/profit` have no mining left.

## What changed here

- `139-hx-mining-nucleus-runs` (hx/HxHudCases): kept in place, registered with `Session.shelved(...)` instead of
  `Session.test(...)`, so a selected run prints `SKIPPED - shelved until after 2.0` and records a SKIP row. The body is
  unchanged.
- `hx/HxHostileCases` state snapshot: the `nucleus.inLootBlock` entry (`mining.nucleus.NucleusRunProfitTracker`)
  replaced by a comment.
- `ui/ProfitCases` (307): the Mining Profit and Nucleus Runs rows, the `mining`/`nucleus` words, the two direct
  `/profit mining|nucleus` opens and the `/profit n` completion (now `/profit d` -> `dungeon`).
- `testkit-ui/allow-screens.json`: three entries removed -
  `{"class": "gui.profit.MiningProfitScreen", "args": ["null"], "source": "gui/profit/MiningProfitScreen.java (/profit mining)"}`,
  `{"class": "gui.profit.NucleusProfitScreen", "args": ["null"], "source": "gui/profit/NucleusProfitScreen.java (/profit nucleus)"}`,
  `{"class": "mining.chmap.CrystalHollowsMapScreen", "args": [], "source": "mining/chmap/CrystalHollowsMapScreen.java"}`.
- Fixtures moved here (the logic suite checks every fixture's source and pattern against the jar):
  `fixtures/chat/mining.json` (was `src/gametest/resources/testkit-fixtures/chat/mining.json`, git mv) and
  `fixtures/hostile/nucleus.json` (the `hostile.allchat-nucleus-start` / `-end` objects cut from
  `testkit-fixtures/hostile/lines.json`, where they sat before `hostile.allchat-magic-find`).
- `testkit-logic/pattern-catalog.json` regenerated from the shelved mod (5 mining patterns gone).

## Restoring

Put both fixture files back (append the two hostile objects to `lines.json`), switch 139 back to `s.test`, restore the
snapshot line, the ProfitCases rows and the three allow-screens entries, regenerate the pattern catalog against the
restored mod, and delete `ui/MiningShelvedCases` (411) with its UiSuite entries.

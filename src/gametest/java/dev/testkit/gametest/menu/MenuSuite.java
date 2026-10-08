package dev.testkit.gametest.menu;

import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.TestMap;
import dev.testkit.gametest.hx.Session;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * WP3: menus and items (port 25580, Hx 25585). One shared server ({@code 200-menu-session}); every case is a
 * {@code 2NN-menu-*} row. Server-side menus are WP3's Hx module ({@code dev.testkit.server.hx.menu}): terminals with
 * Hypixel's click mechanics, experiments, and data-driven menus whose specs live in the {@code menus/} fixtures.
 *
 * <ul>
 *   <li>201-217 terminals: solver vs the server board, solved end-to-end; Auto Terminals per type (clicks/tick,
 *       Grim), and nothing clicked on the legit jar ({@link TerminalCases})</li>
 *   <li>220-229 experiments: solver state, Auto E-Table end-to-end, profit tracker ({@link ExperimentCases})</li>
 *   <li>230-239 dungeon menus: Croesus, reward chests, auto close chest, Spirit Leap, fast leap, class select,
 *       party finder ({@link DungeonMenuCases})</li>
 *   <li>240-259 inventory menus: Sell, Item Protect, sorter, slot binds, storage, pets, loadout, auction helper,
 *       bazaar dump, Chocolate Factory ({@link InventoryCases})</li>
 *   <li>260-279 items: rarity, enchant colours, master stars, dye, held item, tooltip scroll, theme, HUD, search,
 *       readers ({@link ItemCases})</li>
 *   <li>280-289 more experiments: Superpairs deductions and reward priority ({@link ExperimentCases})</li>
 *   <li>292-297 Auto Anvil in Hypixel's Anvil ({@code menu.anvil}): only exact book pairs combined, cascade, direct
 *       delivery, preview mismatch, close, off/legit ({@link AnvilCases})</li>
 *   <li>472 the Storage Overlay's Scan All button: real presses start / stop it, Escape stops it, a full run skips
 *       locked pages and empty slots ({@link PolishMenuCases})</li>
 *   <li>491-499 the unified Auction House: the API-drawn browser, Hypixel's AH menus reskinned with one press = one
 *       slot click, /viewauction hand-off, hidden HUDs, seamless page changes, fallback ({@link AuctionHouseCases})</li>
 *   <li>461-466 Reskin Real Bazaar: every mapped Bazaar menu reskinned, one press = one real slot click, unmapped menus
 *       left to Hypixel's GUI, the hold-key escape, live Manage Orders, the Booster Cookie gate ({@link BazaarReskinCases})</li>
 *   <li>481-489 the unified Bazaar screen: render at six sizes, recents, tabs and search, the three-button bottom bar,
 *       HUDs hidden, seamless page switches (frame log), product clicks with and without a Booster Cookie, the follow-up
 *       click's rules, themes ({@link BazaarV3Cases})</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class MenuSuite implements FabricClientGameTest {

    static final String SESSION = "200-menu-session";

    /**
     * Session.run can only select a whole session or cases whose names contain the session name, so a subset of
     * these cases is chosen with the environment variable {@code TESTKIT_MENU_ONLY=part,part} (each case whose name
     * contains a part runs). Unset: every case.
     */
    static void test(Session s, String name, Session.Case body) {
        if (selected(name)) {
            s.test(name, body);
        }
    }

    static void testExpectingFlags(Session s, String name, Session.Case body) {
        if (selected(name)) {
            s.testExpectingFlags(name, body);
        }
    }

    static boolean selected(String name) {
        String only = System.getenv("TESTKIT_MENU_ONLY");
        if (only == null || only.isBlank()) {
            return true;
        }
        for (String part : only.split(",")) {
            if (!part.isBlank() && name.contains(part.trim())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        Session.run(ctx, SESSION,
                (server, s) -> TestMap.on(server)
                        .platform(-520, 150, -520, 6)
                        .catchFloor(140)
                        .survival()
                        .clearInventory()
                        .spawn(-519.5, -519.5, 0f)
                        .build(),
                s -> {
                    ModUnderTest.require("killer560smod");
                    TerminalCases.register(s);
                    ExperimentCases.register(s);
                    DungeonMenuCases.register(s);
                    InventoryCases.register(s);
                    ItemCases.register(s);
                    AnvilCases.register(s);
                    BazaarReskinCases.register(s);
                    PolishMenuCases.register(s); // 472, polish
                    // 491-499: the unified Auction House (API browser + reskinned real AH menus).
                    AuctionHouseCases.register(s);
                    BazaarV3Cases.register(s);
                });
    }
}

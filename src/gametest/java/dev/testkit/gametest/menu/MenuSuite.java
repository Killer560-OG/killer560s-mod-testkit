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
 *   <li>1015-menu-term-hidden-slots(-off): with the terminal Custom GUI on, the hidden chest slots take no hover, click
 *       or key; the panel's squares click as before ({@link TermHiddenSlotCases})</li>
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
 *   <li>898 every Croesus run state distinct over green and red heads, also for protanopia/deuteranopia, and the
 *       states that need action far more salient than the quiet ones ({@link CroesusColorCases})</li>
 *   <li>903-905 a run's chest view: the free claim green and one key chest orange, the same chest after a claim, nothing
 *       after a key, always agreeing with the run list ({@link CroesusChestViewCases})</li>
 *   <li>888-890 a Croesus run keeps its state when it moves a slot, between same-party runs, and across a restart
 *       ({@link CroesusTrackCases})</li>
 *   <li>884-887 Croesus run states stay put: runs with the same party, list/run/list navigation, a head lagging a
 *       claim, refreshed lore; never a hidden slot in a run view or chest ({@link CroesusStableCases})</li>
 *   <li>883 opening the Croesus menu never draws a frame of stale or unhidden runs ({@link CroesusFlashCases})</li>
 *   <li>879-882 Croesus run states (unclaimed / needs a key / opened / key used / not looked at), their colours, Hide
 *       Opened Runs never hiding a run that needs a key, the live key floor ({@link CroesusKeyCases})</li>
 *   <li>875-878 the Croesus menu: Highlight Runs behind the items and under the tooltip, Hide Opened Runs (empty, no
 *       tooltip, no click; Auto Croesus unaffected), unclaimed/claimed totals across all pages ({@link CroesusUiCases})</li>
 *   <li>773-776 Croesus valuation of killer560's M4 chest in both Price Modes (run view, chest screen, Auto Croesus's
 *       decision) and the Price Mode setting ({@link CroesusPriceCases})</li>
 *   <li>768-769 the reskin drawing while the flipper runs (a key stops it), the one Bazaar/AH switch's config
 *       migration ({@link ReskinSwitchCases})</li>
 *   <li>461-466 the Bazaar reskin: every mapped Bazaar menu reskinned, one press = one real slot click, unmapped menus
 *       left to Hypixel's GUI, the hold-key escape, live Manage Orders, the Booster Cookie gate ({@link BazaarReskinCases})</li>
 *   <li>481-489 the unified Bazaar screen: render at six sizes, recents, tabs and search, the three-button bottom bar,
 *       HUDs hidden, seamless page switches (frame log), product clicks with and without a Booster Cookie, the follow-up
 *       click's rules, themes ({@link BazaarV3Cases})</li>
 *   <li>591-596 social: Best Friends' Sort/filter labels inside their buttons at six sizes, compact and to-the-second
 *       times counting up live, party time against two minutes of wall clock across a save/reload; the Friends List
 *       tab's removed lines, "Syncing... page X/Y" while a 3-page /fl walks, the status line clear of the list
 *       ({@link SocialCases})</li>
 *   <li>583, 585 the 2026-10-08 inventory batch: Item Protect blocks each action Slot Lock blocked (with a control that
 *       gets through), the sorter's SWAP/PICKUP clicks and tick pacing ({@link InvBatchMenuCases})</li>
 *   <li>670 the custom leap menu follows the Leap Order's default order and pins ({@link LeapOrderMenuCases})</li>
 *   <li>821-825 the 2026-10-11 leap-menu batch: faces, map in clear, open delay, no carried head, frozen layout ({@link LeapBatchMenuCases})</li>
 *   <li>829-834 how long class picks and pins last: same party, one swap, a run without X, 3 leave, 2 leave, guards ({@link LeapRunMemoryCases})</li>
 *   <li>899-902 the Leap Order editor: Reset Classes, Reset Positions, a party refresh on opening, the removed button and hint ({@link LeapEditorCases})</li>
 *   <li>754-755 the Chocolate Factory on its current layout: Auto Chocolate Factory's clicks, the legit helper's
 *       reading and alerts ({@link ChocolateCases})</li>
 *   <li>982-984 killer560's 2026-10-13 list: Auto E-Table stops on any input but a mouse move, Fast/Auto Leap modes
 *       and targets (a Leap Order class pick), Storage Scan All's slower varied pace ({@link DocDungeonsMenuCases})</li>
 *   <li>1341-1342 Fast/Auto Leap v2: the Auto sequence (swap, use, click, Return To) one step a tick, Fast and Auto
 *       together, and each failure's chat line ({@link LeapV2MenuCases})</li>
 *   <li>1361-1364 Loadout Keybinds' per-key mode: Only In Menu, Open Swap Close, its timeout, a wrong menu and chat
 *       typing ({@link LoadoutModeCases})</li>
 *   <li>1850-1852 the Swap Accounts screen: full-width rows, Active / proxy status, search, scroll, small windows,
 *       every control's job by real presses, and (1852) the signed-in card standing out with no hint line
 *       ({@link SwapAccountsCases})</li>
 *   <li>761-767 the Bazaar flipper's full buy/sell cycle with the reskin off and on, its Cookie and price guards, and
 *       Auto Croesus going back into a claimed run for a keyed chest ({@link FlipCroesusCases})</li>
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
                    PriceTooltipCases.register(s); // 1750-1754: Price Tooltip (price-tooltip-1015)
                    AnvilCases.register(s);
                    BazaarReskinCases.register(s);
                    PolishMenuCases.register(s); // 472, polish
                    // 491-499: the unified Auction House (API browser + reskinned real AH menus).
                    AuctionHouseCases.register(s);
                    BazaarV3Cases.register(s);
                    // ---- 591-596 social (Best Friends, Friends List), killer560 2026-10-08 ----
                    SocialCases.register(s);
                    // 554-555: dungeon-fixes (2026-10-08), terminals behind a decorated title.
                    DungeonFixTerminalCases.register(s);
                    TermHiddenSlotCases.register(s); // 1015: Custom GUI hides vanilla's hover and keys on hidden slots
                    InvBatchMenuCases.register(s); // 583, 585: inventory batch 2026-10-08
                    LeapOrderMenuCases.register(s); // 670: leap menu follows the Leap Order defaults/pins, 2026-10-09
                    LeapBatchMenuCases.register(s); // 821-825: leap-menu batch 2026-10-11 (heads, map, delay, carried, freeze)
                    LeapRunMemoryCases.register(s); // 829-834: how long class picks and pins last, 2026-10-12
                    LeapEditorCases.register(s); // 899-902: Leap Order editor resets, auto party refresh, cleanup (2026-10-12)
                    // ---- 754-755 Chocolate Factory on the current layout: the auto and the legit helper (fairy-choc, 2026-10-11) ----
                    ChocolateCases.register(s);
                    // ---- 731-736 trackers (profit scopes, red reset, Experiments tab, Superpairs Escape, RNG), 2026-10-11 ----
                    TrackerCases.register(s);
                    // ---- 1640-1644 profit by account / mod profile (profit-accounts-1015, 2026-10-15) ----
                    ProfitAccountCases.register(s);
                    // ---- 761-767 bz-croesus (2026-10-11): Bazaar flipper cycle, Auto Croesus key revisit ----
                    FlipCroesusCases.register(s);
                    // ---- 768-769 bz-reskin (2026-10-11): reskin during the flipper, one Bazaar/AH switch ----
                    ReskinSwitchCases.register(s);
                    // ---- 773-776 croesus-price (2026-10-11): M4 chest valuation, Croesus Price Mode ----
                    CroesusPriceCases.register(s);
                    // ---- 875-878 croesus-ui (2026-10-12): run highlight under the tooltip, Hide Opened Runs, totals ----
                    CroesusUiCases.register(s);
                    // ---- 879-882 croesus-key (2026-10-12): run states by chest key, Hide keeps "needs a key" ----
                    CroesusKeyCases.register(s);
                    // ---- 883 croesus-flash (2026-10-12): no stale run state on open ----
                    CroesusFlashCases.register(s);
                    // ---- 884-887 croesus-stable (2026-10-12): same-party runs, lagging heads, no hide in views ----
                    CroesusStableCases.register(s);
                    // ---- 888-890 croesus-track (2026-10-12): run state follows a run that moves, and restarts ----
                    CroesusTrackCases.register(s);
                    // ---- 898 croesus-colors (2026-10-12): run states distinct by colour (deltaE, CVD) and marks ----
                    CroesusColorCases.register(s);
                    // ---- 903-905 croesus-colors2 (2026-10-13): a run's chest view - free claim green, key chest orange ----
                    CroesusChestViewCases.register(s);
                    // ---- 950-957 sweep-a (2026-10-13): the mod's other screens in the menu theme, per-screen Theme ----
                    ScreenSweepCases.register(s);
                    // ---- 982-984 doc-dungeons (2026-10-13): Auto E-Table stops on input, leap modes/targets, Scan All pace ----
                    DocDungeonsMenuCases.register(s);
                    // ---- leap-v2 (1341-1342): Auto Leap's swap/use/click/return sequence and its failures ----
                    LeapV2MenuCases.register(s);
                    // ---- 1850-1852 swap-accounts-ui (2026-10-15, 1852 2026-10-16): the redone Swap Accounts screen, Set Universal Proxy ----
                    SwapAccountsCases.register(s);
                });
    }
}

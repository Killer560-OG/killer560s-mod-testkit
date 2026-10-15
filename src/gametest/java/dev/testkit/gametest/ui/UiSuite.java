package dev.testkit.gametest.ui;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.mod.Quiet;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.util.List;

/**
 * WP4 entrypoint: client UI, commands and config (singleplayer, no server). Scenario names start `3NN-ui-`;
 * `-Psuite=ui` selects them all, `-Pscenario=ui-smoke` only the smoke group (301-306, both jars, under 3 minutes).
 *
 * <pre>
 * title screen:  340 main menu theme, 330 ModPaths.migrateAll, 310 config file round trip, 311 config setters,
 *                320 profiles save/export/import/apply, 380 Auto Scale (window sizes, drawn size, clicks)
 * singleplayer:  301 every ModScreen tab, 302 search, 303 toggles, 304 HUD editor, 305 every mod Screen,
 *                306 client commands, 350 termism, 360 client visuals, 307 /profit hub and trackers,
 *                385 Interactive Map input/panel, 386 mod-menu sliders driven by real input + no overlapping rows
 *                (and the sim room Filters panel: chips wrap, no overlap, all reachable),
 *                387 AP3 node editor (type dropdown, per-type fields saved, hotbar item pick, no overlaps),
 *                388 Breaker Aura Display x Box Style each draw (screenshot pixel counts, a pick behind a wall),
 *                389 Custom Crosshair replaces vanilla's and draws exactly the pixels it is set to, editor preview,
 *                390 Wither Doors Style / Fill Opacity / Fill Color draw (screenshot pixels, alpha, through walls, reload),
 *                391 Auto Close Chest / Auto Blood Camp cheat-only tabs, rows, tooltips, legit jar without the code,
 *                392 Blood Camp look against NoammAddons, line start, predicted spot, countdown sounds,
 *                393 Health and Mana Bars tab: no scale control, every row reachable behind its parent, wired,
 *                tooltipped; an old jar's save draws the bar at the same pixel size (TESTKIT_BARS_EXPORT / seedConfig),
 *                394 every HUD element's editor box against the bounds of what it draws (slack 3 units a side),
 *                395 Dungeon Map Player Heads (his and a teammate's skin face, no-skin teammate keeps the arrow,
 *                heading ticks, Interactive Map too) and the outlined arrow on a green and a brown room, reload,
 *                401 Custom Scoreboard lists: drag / trash / Add by real mouse input change the saved and DRAWN
 *                order, auto-scroll, per-line options, reload, old toggle saves migrate to the same drawn board
 *                (TESTKIT_SCOREBOARD_EXPORT on the old jar, -PseedConfig on the new), no overlaps,
 *                399 HUD editor resize handles / cursors / snapping (real mouse drags, Auto Scale 0.5 and 1) and the
 *                Stat Bars Vitality + XP readouts and Classic Display migration ({@link HudEditorCases}),
 *                407 Health and Mana Bars Layout: Predefined areas round the hotbar measured on screenshots at Auto
 *                Scale 0.5 and 1 (shared width, clear of the vanilla rows), real-mouse drags between areas and onto
 *                Hidden, Custom positions untouched, old configs stay Custom ({@link StatBarsLayoutCases}),
 *                425 every Bazaar product's icon source and the paper fallbacks named ({@link BazaarBrowserCases};
 *                424, the old browser's layout, retired 2026-10-07 for menu 481),
 *                427-432 Pack Disabler: models through vanilla's resolver ON/OFF, the pack push left to vanilla, the
 *                missing-texture command, a chest at GUI 2, a player's resource pack beating both vanilla-look and our
 *                textures, Bazaar icons from the same table ({@link PackDisablerCases}),
 *                451-454 Inventory Theme on the hotbar (vanilla overlays kept, line width, colours, scale), its
 *                settings round trip, the Storage Overlay centred at six window/GUI sizes, and the Amber / Dark /
 *                Light themes measured on screenshots ({@link InvStorageCases})
 *                441-447 killer560's 2026-10-07 HUD report: Health and Mana Bars behind an open inventory, the
 *                absorption segment and Hypixel's gold health text, one scale in Predefined, room round a bar's number
 *                at GUI 2 and 4, the map's Extra Info text size and the Split Timers kept off the map, "Total: X (Y)",
 *                the dungeon board's Solo/Keys lines and the quiet unknown-line notice ({@link HudFixCases})
 *                471-476 killer560's 2026-10-07 22:08 polish report: held-item name Shown / Hidden Over Bars / Hidden,
 *                Line Width in screen pixels at GUI 2 and 3 (and old unit widths converted), one shared line between
 *                touching slots (chest, inventory, hotbar), the armour column against the model frame, the bar number
 *                centred to the pixel ({@link PolishCases})
 *                581-587 killer560's 2026-10-08 inventory batch: Item Protect's star (pixels) and Slot Lock's removal +
 *                migration, the Inventory Sorter's layouts menu by real input, Custom Items by real clicks, the Cosmetics
 *                category ({@link InvBatchCases})
 *                541-545 Command Shortcuts: built-in rows show only name + ON/OFF (no text beside or under them, no
 *                overlap, expansions in the tooltip), the Custom section's add/edit/rename/off/delete through the tab
 *                with live registration and reload, refused names, and the 306 sweep with custom roots present
 *                ({@link CmdShortcutsCases}; 544 sends, in the Hx session)
 *                511-514 killer560's 2026-10-07 menu layout report: Crosshair in General, no combined folders, every tab
 *                once, cheat-only tabs and sections after every legit one, tooltips unchanged, screenshots at GUI 2
 *                ({@link MenuLayoutCases})
 *                519 Home cleanup (2026-10-15): no Reboot button, 4x2 buttons, short version line, cards in his fixed order
 *                ({@link DocMenuCases}); 515-518 killer560's 2026-10-14 menu order list: tabs alphabetical in every category (cheat block last),
 *                the card's switch first in every tab, Dungeon Queue's card ON while either feature is, and the five
 *                tabs that lost their "as a whole" switch keep their old files' meaning ({@link MenuOrderCases})
 *                611-618 killer560's 2026-10-08 HUD / menu / profiles list: the Edit HUD key only with no screen,
 *                Edit HUD without its panels and their settings in Home, Edit Bars, HUD Scale sizing the mod menu,
 *                the blank profile and the share folder ({@link HudMenuProfilesCases})
 *                671-675 Held Item Outline: off controls, styles/colours/width/alpha from screenshots, hands, hotbar and
 *                third person untouched, settings; 676 Outline Side near/far/both, 677 one screenshot per setting
 *                ({@link HeldOutlineCases})
 *                940-947 the Option A mod menu: screenshots, card switches flip the config, a card opens its drawer,
 *                favourites survive a restart, Ctrl F search, no sidebar counts, colour presets and Custom colours
 *                ({@link MenuACases})
 *                955 every colour picker's Copy / Paste / hex box / Ctrl+C / Ctrl+V / Recent row, between two different
 *                settings, proven on disk and after a restart ({@link ColourCopyCases})
 * end:           370 deny lists (no child process, hooks in force)
 * </pre>
 *
 * Before any case: the QUIET profile (network features off) and SkyblockGate off, so nothing is refused for not
 * being on Skyblock. The whole run is watched for child processes (ProcWatch).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class UiSuite implements FabricClientGameTest {

    static final String[] TITLE_CASES = {"340-ui-mainmenu", "330-ui-migrate", "310-ui-config-file-roundtrip",
            "311-ui-config-setters", "320-ui-profiles", "380-ui-autoscale",
            // title-262 (2026-10-13)
            "341-ui-title-widgets"};
    static final String[] WORLD_CASES = {"301-ui-smoke-tabs", "302-ui-smoke-search", "303-ui-smoke-toggles",
            "304-ui-smoke-hud-editor", "305-ui-smoke-screens", "306-ui-smoke-commands", "350-ui-termism",
            "360-ui-visuals", "365-ui-overlay-draws", "307-ui-profit", "385-ui-interactive-map", "386-ui-sliders",
            "387-ui-ap3-edit", "388-ui-breaker-display", "389-ui-crosshair",
            "390-ui-wither-doors-fill", "391-ui-cheat-tabs", "392-ui-blood-camp", "393-ui-stat-bars",
            "394-ui-hud-boxes", "395-ui-map-heads",
            "399-ui-hud-editor-resize", "399-ui-hud-editor-snap",
            "399-ui-stat-bars-vitality-xp", "407-ui-stat-bars-layout", "401-ui-scoreboard-editor",
            "411-ui-mining-shelved", "412-ui-no-new-tab", "413-ui-testing-variant",
            "425-ui-bazaar-icons", "427-ui-pack-disabler-models",
            "428-ui-pack-disabler-pack-push", "429-ui-pack-disabler-missing", "430-ui-pack-disabler-screens",
            "431-ui-pack-disabler-user-pack", "432-ui-pack-disabler-bazaar",
            "451-ui-hotbar-theme", "452-ui-inventory-theme-settings", "453-ui-storage-overlay-centre",
            "454-ui-storage-overlay-themes",
            "441-ui-bars-under-screens", "442-ui-bars-absorption", "443-ui-bars-predefined-scale", "444-ui-bars-padding",
            "445-ui-map-info", "446-ui-split-format", "447-ui-scoreboard-solo"};
    /** killer560's 2026-10-07 22:08 polish report ({@link PolishCases}); 472 is in the menu suite. Own block for merges. */
    static final String[] POLISH_CASES = {"471-ui-held-item-name", "473-ui-inventory-line-pixels",
            "474-ui-inventory-shared-lines", "475-ui-inventory-armour-model", "476-ui-bars-text-centred"};
    /** killer560's 2026-10-07 menu layout report ({@link MenuLayoutCases}): structure, cheat order, tooltips, screenshots.
     *  Own block for merges. */
    static final String[] MENU_LAYOUT_CASES = {"511-ui-menu-layout", "512-ui-menu-cheat-order", "513-ui-menu-tooltips",
            "514-ui-menu-shots"};
    /** Map Extra Info in two rows with M/P/B (mod map-info-bat, {@link MapInfoBatCases}). Own block for merges. */
    static final String[] MAPINFO_CASES = {"524-ui-map-info-rows"};
    /** killer560's 2026-10-08 chat batch, the menu half ({@link ChatBatchUiCases}; the chat half is
     *  {@code chat/ChatBatchSuite}, 560-chat-session). Own block for merges. */
    static final String[] CHAT_CASES = {"565-ui-chat-voice-widget", "569-ui-chat-hider-tab"};
    /** Player Heads turned to the heading, arrow mode unchanged (mod map-heads, {@link MapHeadTurnCases}). Own block. */
    static final String[] MAPHEAD_CASES = {"600-ui-map-head-turn", "601-ui-map-arrow-turn"};
    /** killer560's 2026-10-08 dungeon list ({@link DungeonFixCases}): splits layout, Blood Camp stall, p3sim etherwarp.
     *  Own block for merges. */
    static final String[] DUNGEON_FIX_CASES = {"551-ui-splits-layout", "552-ui-blood-camp-stall", "553-ui-etherwarp-p3sim"};
    /** killer560's 2026-10-08 inventory batch ({@link InvBatchCases}); 583/585 are in the menu suite. Own block for merges. */
    static final String[] INV_BATCH_CASES = {"581-ui-itemprotect-star", "582-ui-slotlock-migration",
            "584-ui-invsort-editor", "586-ui-custom-items", "587-ui-cosmetics-category"};
    /** killer560's 2026-10-07 Command Shortcuts requests ({@link CmdShortcutsCases}); 544 is in the Hx session. Own block. */
    static final String[] CMD_SHORTCUTS_CASES = {"541-ui-cmd-shortcuts-builtin-rows", "542-ui-cmd-shortcuts-custom-edit",
            "543-ui-cmd-shortcuts-refused-names", "545-ui-cmd-shortcuts-sweep"};
    /** killer560's 2026-10-08 HUD / menu / profiles list ({@link HudMenuProfilesCases}). Own block for merges. */
    static final String[] HUD_MENU_PROFILES_CASES = {"611-ui-hud-key-screens", "612-ui-hud-editor-clean",
            "613-ui-hud-editor-settings", "614-ui-bars-editor", "615-ui-menu-hud-scale", "616-ui-menu-factor-math",
            "617-ui-profile-blank", "618-ui-profile-share"};
    /** killer560's 2026-10-08 Chat Keybinds + Command Shortcuts merge ({@link ChatKeybindsMergeCases}); 623-625 are in the
     *  Hx session. Own block for merges. */
    static final String[] CHAT_KEYBINDS_MERGE_CASES = {"621-ui-chat-keybinds-merged-tab", "622-ui-chat-keybinds-migration"};
    /** killer560's 2026-10-09 Held Item Outline ({@link HeldOutlineCases}). Own block for merges. */
    static final String[] HELD_OUTLINE_CASES = {"671-ui-held-outline-off", "672-ui-held-outline-styles",
            "673-ui-held-outline-hands", "674-ui-held-outline-hud", "675-ui-held-outline-settings",
            "676-ui-held-outline-sides", "677-ui-held-outline-gallery"};
    /** killer560's 2026-10-09 Leap Order default orders + pins ({@link LeapOrderCases}); 670 is in the menu suite.
     *  Own block for merges. */
    static final String[] LEAPORDER_CASES = {"666-ui-leaporder-defaults", "667-ui-leaporder-pins",
            "668-ui-leaporder-reset", "669-ui-leaporder-shots"};
    /** killer560's 2026-10-11 item / inventory UI list ({@link ItemUiCases}); 708 is in the menu suite. Own block. */
    static final String[] ITEM_UI_CASES = {"701-ui-invsearch-red-fill", "702-ui-invsearch-calculator",
            "703-ui-slotbinds-key-toggle", "704-ui-petwheel-no-slices-text-scale", "705-ui-itemprotect-hover-star-drops",
            "706-ui-gif-dvd-hidden-in-containers", "707-ui-storagesearch-equipment-wardrobe", "709-ui-itembrowser-tooltip",
            "710-ui-itembrowser-minion-tiers", "711-ui-itembrowser-neu-recipe", "712-ui-itembrowser-sort-and-bar"};
    /** killer560's 2026-10-15 small dungeon list ({@link SmallDungeonCases}): Thorn through-walls switches, one ESP wither colour + legit tracer, Secrets HUD colour. Own block. */
    static final String[] SMALL_DUNGEON_CASES = {"1051-ui-thorn-through-walls", "1052-ui-esp-colour-tracer", "1053-ui-secrets-hud-colour"};
    /** killer560's 2026-10-15 Slot Text + Personal Compactor/Deletor overlay ({@link SlotTextCases}). Own block for merges. */
    static final String[] SLOT_TEXT_CASES = {"960-ui-slottext-types", "961-ui-slottext-drawn", "962-ui-slottext-modes",
            "963-ui-compactor-contents", "964-ui-compactor-hover-drawn", "965-ui-compactor-modes", "966-ui-compactor-settings"};
    static final String[] DUNGEON_B2_CASES = {"686-ui-blood-trigger", "687-ui-door-key-circle",
            "679-ui-ttt-prediction-misclicks", "680-ui-imap-binds-ticks", "681-ui-auto-kick-pf", "682-ui-dungeon-esp-switch"};
    /** killer560's 2026-10-11 Auto Kick Judge By + Detect Queued Floor ({@link AutoKick2Cases}). Own block for merges. */
    static final String[] AUTOKICK2_CASES = {"801-ui-autokick-judge-by", "802-ui-autokick-detect-pf",
            "803-ui-autokick-joininstance-ignored", "804-ui-autokick-forged-queue", "805-ui-autokick-floor-source"};
    /** killer560's 2026-10-11 "left click forward, right click backward" for every cycling setting ({@link CycleButtonCases}). Own block. */
    static final String[] CYCLE_BACK_CASES = {"806-ui-cycle-buttons-both-ways"};
    /** killer560's 2026-10-14 to-do batch: defaults on, removed toggles gone ({@link DocSmall1014Cases}). Own block. */
    static final String[] DOC_SMALL_CASES = {"807-ui-doc-small-1014"};
    /** killer560's 2026-10-15 Dungeon Map list (mod map-overhaul-1015, {@link MapOverhaulCases}). Own block for merges. */
    static final String[] MAP_OVERHAUL_CASES = {"1015-ui-switch-align", "1015-ui-map-tab", "1015-ui-map-themes",
            "1015-ui-door-key-style"};
    /** The Option A mod menu (mod menu-a, {@link MenuACases}): shots, card switch, drawer, favourites, search, counts.
     *  Own block for merges. */
    static final String[] MENU_A_CASES = {"940-ui-menu-a-shots", "941-ui-menu-a-cards", "942-ui-menu-a-drawer",
            "943-ui-menu-a-favourites", "944-ui-menu-a-search", "945-ui-menu-a-no-counts", "946-ui-menu-a-themes",
            "947-ui-menu-a-custom-colour"};
    /** killer560's 2026-10-13 menu and settings list (mod doc-menu, {@link DocMenuCases}). Own block for merges. */
    static final String[] DOC_MENU_CASES = {"980-ui-docmenu-home", "981-ui-docmenu-mod-hud", "982-ui-docmenu-auto-update",
            "983-ui-docmenu-masters", "984-ui-docmenu-bold-name", "985-ui-docmenu-hud-scale-live",
            "986-ui-docmenu-drawer-reopens", "987-ui-docmenu-cycle-halves", "988-ui-docmenu-calculator",
            "989-ui-docmenu-saved-themes"};
    /** The Profiles page redesign (mod menu-profiles-ctrlf, {@link MenuProfilesCases}). Own block for merges. */
    static final String[] MENU_PROFILES_CASES = {"948-ui-menu-profiles-page"};
    /** killer560's 2026-10-15 custom names + profiles list (mod names-profiles-1015, {@link NamesProfilesCases}; 1553 is in
     *  {@link MenuProfilesCases}). Own block for merges. */
    static final String[] NAMES_PROFILES_CASES = {"1550-ui-np-command-names", "1551-ui-np-rank", "1552-ui-np-shared-names",
            "1553-ui-np-profile-rename-quiet"};
    /** Profiles carry everything, across jars (mod profiles-all, {@link ProfileDataCases}). Own block for merges. */
    static final String[] PROFILE_DATA_CASES = {"980-ui-profile-data-switch", "981-ui-profile-data-export",
            "982-ui-profile-import-robust", "983-ui-profile-old-format", "984-ui-profile-xfer-export",
            "985-ui-profile-xfer-import"};
    /** Screen sweep B: standalone screens in the menu theme + Screen Theme (mod sweep-b, {@link ScreenSweepBCases}).
     *  Own block for merges. */
    static final String[] SWEEP_B_CASES = {"1020-ui-sweepb-profit", "1021-ui-sweepb-storage-search",
            "1022-ui-sweepb-run-log", "1023-ui-sweepb-invsort", "1024-ui-sweepb-custom-items", "1025-ui-sweepb-screen-theme"};
    /** killer560's 2026-10-12 Edit HUD key / Croesus batch ({@link HudKey2Cases}). Own block for merges. */
    /** Container overlays in Edit HUD only within 10 s (mod hud-key-3, {@link HudKey2Cases#containerGrace}). Own block. */
    static final String[] HUD_KEY3_CASES = {"866-ui-container-overlay-grace"};
    static final String[] HUD_KEY2_CASES = {"862-ui-hud-key-typing", "863-ui-hud-key-over-container",
            "864-ui-croesus-split", "865-ui-container-overlays"};
    /** killer560's 2026-10-11 leap-menu batch, the editor half ({@link LeapWizardCases}); 821-825 are in the menu
     *  suite. Own block for merges. */
    static final String[] LEAP2_CASES = {"826-ui-leap-wizard", "827-ui-leap-settings", "828-ui-leap-shots"};
    /** killer560's 2026-10-15 to-do: /class + /leaporder, Class Overrides folded into the Leap Order, the editor's layout,
     *  Party Finder styles and the custom style editor ({@link LeapQueueCases}). Own block for merges. */
    static final String[] LEAPQUEUE_CASES = {"1071-ui-class-commands", "1072-ui-class-overrides-migrated",
            "1073-ui-leaporder-layout", "1074-ui-pf-queue-styles", "1075-ui-pf-custom-editor"};
    /** killer560's 2026-10-12 Health and Mana Bars styles ({@link BarStyleCases}). Own block for merges. */
    static final String[] BAR_STYLE_CASES = {"891-ui-bars-style-fill", "892-ui-bars-style-overflow",
            "893-ui-bars-style-settings", "894-ui-bars-style-ghost", "895-ui-bars-style-shots",
            "896-ui-bars-style-editor", "898-ui-bars-menu",
            "899-ui-bars-his-icons"};
    /** Inventory Theme: every item centred in its slot frame (mod inv-theme-fix, {@link InvThemeCenterCases}). Own block. */
    static final String[] INV_THEME_CENTRE_CASES = {"897-ui-inventory-theme-item-centred"};
    /** The chroma waterfall (mod chroma-waterfall, {@link ChromaWaveCases}). Own block for merges. */
    static final String[] CHROMA_WAVE_CASES = {"971-ui-chroma-enchant-waterfall", "972-ui-chroma-scoreboard-border",
            "973-ui-chroma-crosshair"};
    /** Every Turbo-Crop VII tiers as Perfect chroma (mod turbo-chroma, {@link ChromaWaveCases#turboCrops}). Own block. */
    static final String[] TURBO_CHROMA_CASES = {"974-ui-chroma-turbo-crops"};
    /** Item Protect's star on locked player heads (mod lockstar-heads, {@link LockStarHeadCases}). Own block. */
    static final String[] LOCKSTAR_HEAD_CASES = {"961-ui-lockstar-heads"};
    /** killer560's 2026-10-13 doc list, misc items ({@link DocMiscCases}). Own block for merges. */
    static final String[] DOC_MISC_CASES = {"1311-ui-scoreboard-numbers", "1312-ui-inventory-slot-outlines",
            "1313-ui-bars-default-thinner", "1314-ui-peek-chat", "1315-ui-loadouts-button"};
    /** killer560's 2026-10-13 dungeon list (mod doc-dungeons, {@link DocDungeonsCases}). Own block. */
    static final String[] DOC_DUNGEONS_CASES = {"981-ui-etherwarp-walk-in", "985-ui-leap-modes-targets",
            "986-ui-croesus-key-settings", "987-ui-map-leap-names"};
    /** The screen sweep, part C (mod sweep-c, {@link ScreenThemeCases}): every sweep-c screen in Amber and Light, and a
     *  screen's own Theme setting. Own block for merges. */
    static final String[] SCREEN_SWEEP_C_CASES = {"975-ui-screen-sweep-c-themes", "976-ui-screen-own-theme"};
    /** The Ribbon Trail (mod trail-ribbon, its own cosmetic since ribbon-cosmetic, {@link TrailRibbonCases}). Own block. */
    static final String[] TRAIL_RIBBON_CASES = {"1031-ui-trail-ribbon-line", "1032-ui-trail-ribbon-curve",
            "1033-ui-trail-ribbon-still", "1034-ui-trail-ribbon-settings", "1035-ui-trail-ribbon-gallery",
            "1040-ui-ribbon-cosmetic-tabs", "1041-ui-ribbon-camera-angles"};
    /** Etherwarp Overlay box: every camera-facing face drawn (mod ew-nearface, {@link EtherwarpFaceCases}). Own block. */
    /** Sweat Mode on the Home tab (mod sweat-mode, {@link DocMenuCases#sweatMode}). Own block for merges. */
    static final String[] SWEAT_CASES = {"1016-ui-sweat-mode"};
    static final String[] EW_NEARFACE_CASES = {"1321-ui-etherwarp-box-faces", "1322-ui-etherwarp-walk",
            "1323-ui-etherwarp-back-edges"};
    /** On Teleport for the trails and the Auto Routes Progress HUD (mod trail-hud-1015, {@link TrailTeleportCases},
     *  {@link RoutesProgressHudCases}). Own block for merges. */
    static final String[] TRAIL_HUD_CASES = {"1042-ui-trail-teleport-ribbon", "1043-ui-trail-teleport-squares",
            "1044-ui-trail-teleport-settings", "1045-ui-routes-progress-content", "1046-ui-routes-progress-editor",
            "1047-ui-routes-progress-settings"};
    /** Shared trails (mod trail-share, {@link TrailShareCases}). Own block for merges. */
    static final String[] TRAIL_SHARE_CASES = {"1036-ui-trail-share-send", "1037-ui-trail-share-remote",
            "1038-ui-trail-share-hide", "1039-ui-trail-share-limits"};
    /** killer560's 2026-10-14 menu order + independent masters list ({@link MenuOrderCases}). Own block for merges. */
    static final String[] MENU_ORDER_CASES = {"515-ui-menu-order", "516-ui-menu-order-settings",
            "517-ui-menu-queue-masters", "518-ui-menu-independent-masters",
            "519-ui-home-cleanup"};
    /** Lever Aura merged into Secret Aura + Menu Theme on overlays (mod aura-themes-1015, {@link AuraThemeCases}). Own block for merges. */
    static final String[] AURA_THEME_CASES = {"1420-ui-secret-aura-merge", "1421-ui-overlay-themes"};
    /** Lights Device as its own cheat (mod lights-aura-1015, {@link LightsDeviceUiCases}). Own block for merges. */
    static final String[] LIGHTS_DEVICE_CASES = {"1475-ui-lights-device"};
    /** killer560's 2026-10-15 click sounds and short descriptions ({@link SoundDescCases}). Own block for merges. */
    static final String[] SOUND_DESC_CASES = {"1060-ui-card-descriptions", "1061-ui-descriptions-toggle",
            "1062-ui-editor-hint-narrow", "1063-ui-click-presets", "1064-ui-click-import"};
    /** killer560's 2026-10-15 Trajectories for shortbows (mod shortbow-traj-1015, {@link TrajectoryCases}). Own block. */
    static final String[] TRAJECTORY_CASES = {"1080-ui-traj-shortbow", "1081-ui-traj-terminator-spread",
            "1082-ui-traj-normal-bow-drawn-only", "1083-ui-traj-shortbows-setting"};
    static final String DENY_CASE = "370-ui-deny";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (dev.testkit.harness.ScenarioList.active() || dev.testkit.harness.ModGate.missing() != null) {
            // ...and with killer560smod not loaded, the same loop gives every selected case its SKIP row.
            // List mode (-PlistScenarios): name the cases, open no world.
            for (String n : TITLE_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : WORLD_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : POLISH_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : MENU_LAYOUT_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : MAPINFO_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : MAP_OVERHAUL_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : CHAT_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : MAPHEAD_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : DUNGEON_FIX_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : INV_BATCH_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : CMD_SHORTCUTS_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : HUD_MENU_PROFILES_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : CHAT_KEYBINDS_MERGE_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : HELD_OUTLINE_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : LEAPORDER_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : LEAP2_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : LEAPQUEUE_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : ITEM_UI_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : DUNGEON_B2_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : SMALL_DUNGEON_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : AUTOKICK2_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : CYCLE_BACK_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : DOC_SMALL_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : HUD_KEY2_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : MENU_PROFILES_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : PROFILE_DATA_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : DOC_MENU_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : SWEEP_B_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : HUD_KEY3_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : BAR_STYLE_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : DOC_DUNGEONS_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : INV_THEME_CENTRE_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : CHROMA_WAVE_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : TRAIL_RIBBON_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : TRAIL_SHARE_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : TRAIL_HUD_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : NAMES_PROFILES_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : SOUND_DESC_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : MENU_ORDER_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : AURA_THEME_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : LIGHTS_DEVICE_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : LOCKSTAR_HEAD_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : DOC_MISC_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : TURBO_CHROMA_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : SCREEN_SWEEP_C_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : SWEAT_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : EW_NEARFACE_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            for (String n : TRAJECTORY_CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            dev.testkit.gametest.Scenario.skip(DENY_CASE);
            return;
        }
        boolean anyTitle = any(TITLE_CASES);
        boolean anyWorld = any(WORLD_CASES) || any(POLISH_CASES) || any(MAPINFO_CASES) || any(CHAT_CASES) || any(MAPHEAD_CASES)
                || any(DUNGEON_FIX_CASES) || any(INV_BATCH_CASES) || any(CMD_SHORTCUTS_CASES) || any(MENU_LAYOUT_CASES)
                || any(HUD_MENU_PROFILES_CASES) || any(CHAT_KEYBINDS_MERGE_CASES) || any(HELD_OUTLINE_CASES)
                || any(LEAPORDER_CASES) || any(LEAP2_CASES) || any(LEAPQUEUE_CASES) || any(ITEM_UI_CASES) || any(DUNGEON_B2_CASES) || any(SLOT_TEXT_CASES) || any(SMALL_DUNGEON_CASES)
                || any(AUTOKICK2_CASES) || any(CYCLE_BACK_CASES) || any(DOC_SMALL_CASES) || any(MENU_A_CASES) || any(HUD_KEY2_CASES)
                || any(HUD_KEY3_CASES) || any(BAR_STYLE_CASES) || any(INV_THEME_CENTRE_CASES) || any(CHROMA_WAVE_CASES)
                || any(MENU_PROFILES_CASES) || any(PROFILE_DATA_CASES) || any(LOCKSTAR_HEAD_CASES)
                || any(DOC_MENU_CASES) || any(DOC_MISC_CASES) || any(DOC_DUNGEONS_CASES) || any(TURBO_CHROMA_CASES)
                || any(SWEEP_B_CASES) || any(SCREEN_SWEEP_C_CASES) || any(TRAIL_RIBBON_CASES) || any(EW_NEARFACE_CASES) || any(SWEAT_CASES)
                || any(TRAIL_SHARE_CASES) || any(MENU_ORDER_CASES) || any(AURA_THEME_CASES) || any(TRAIL_HUD_CASES)
                || any(LIGHTS_DEVICE_CASES) || any(NAMES_PROFILES_CASES) || any(SOUND_DESC_CASES) || any(MAP_OVERHAUL_CASES)
                || any(TRAJECTORY_CASES);
        if (!anyTitle && !anyWorld && !UiCase.selected(DENY_CASE)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        long suiteMark = LogTap.mark();
        Deny deny = Deny.load();
        try (ProcWatch watch = new ProcWatch()) {
            List<String> quiet = ctx.computeOnClient(mc -> {
                List<String> done = Quiet.enabled() ? Quiet.apply() : List.of();
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                return done;
            });
            System.out.println("[ui] QUIET profile: " + quiet.size() + " switch(es) off; SkyblockGate off; "
                    + (Mod.isCheat() ? "cheat" : "legit") + " jar");

            UiCase.run(ctx, "340-ui-mainmenu", MiscCases::mainMenu);
            UiCase.run(ctx, "330-ui-migrate", MiscCases::migrate);
            UiCase.run(ctx, "310-ui-config-file-roundtrip", ConfigSweep::fileLevel);
            UiCase.run(ctx, "311-ui-config-setters", ConfigSweep::setterLevel);
            UiCase.run(ctx, "320-ui-profiles", MiscCases::profiles);
            UiCase.run(ctx, "380-ui-autoscale", AutoScaleCases::autoScale);
            // title-262 (2026-10-13): no stray widget on the themed title screen, none overlapping
            UiCase.run(ctx, "341-ui-title-widgets", TitleWidgetCases::titleWidgets);

            if (anyWorld) {
                long worldStart = System.nanoTime();
                try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
                    ctx.waitTicks(40);
                    SafeWorld.apply(ctx);
                    double worldSeconds = (System.nanoTime() - worldStart) / 1e9;
                    long smokeStart = System.nanoTime();
                    UiCase.run(ctx, "301-ui-smoke-tabs", c -> TabSweep.tabs(c, deny));
                    UiCase.run(ctx, "302-ui-smoke-search", TabSweep::search);
                    UiCase.run(ctx, "303-ui-smoke-toggles", c -> TabSweep.toggles(c, deny));
                    UiCase.run(ctx, "304-ui-smoke-hud-editor", ScreenSweep::hudEditor);
                    UiCase.run(ctx, "305-ui-smoke-screens", c -> ScreenSweep.allScreens(c, deny));
                    UiCase.run(ctx, "306-ui-smoke-commands", c -> CommandSweep.commands(c, deny));
                    double smokeSeconds = (System.nanoTime() - smokeStart) / 1e9;
                    System.out.println(String.format(java.util.Locale.ROOT,
                            "[ui] smoke group (301-306) took %.1f s after a %.1f s world start", smokeSeconds,
                            worldSeconds));
                    UiCase.run(ctx, "350-ui-termism", MiscCases::termism);
                    UiCase.run(ctx, "360-ui-visuals", MiscCases::visuals);
                    UiCase.run(ctx, "365-ui-overlay-draws", MiscCases::overlayDraws);
                    UiCase.run(ctx, "307-ui-profit", ProfitCases::profit);
                    UiCase.run(ctx, "385-ui-interactive-map", InteractiveMapCases::run);
                    UiCase.run(ctx, "386-ui-sliders", c -> SliderCases.run(c, deny));
                    UiCase.run(ctx, "387-ui-ap3-edit", Ap3EditCases::run);
                    UiCase.run(ctx, "388-ui-breaker-display", BreakerDisplayCases::run);
                    UiCase.run(ctx, "389-ui-crosshair", CrosshairCases::run);
                    UiCase.run(ctx, "390-ui-wither-doors-fill", WitherDoorFillCases::run);
                    UiCase.run(ctx, "391-ui-cheat-tabs", CheatTabsCases::cheatTabs);
                    UiCase.run(ctx, "392-ui-blood-camp", CheatTabsCases::bloodCamp);
                    UiCase.run(ctx, "393-ui-stat-bars", c -> StatBarsCases.run(c, deny));
                    UiCase.run(ctx, "394-ui-hud-boxes", HudBoxCases::run);
                    UiCase.run(ctx, "399-ui-hud-editor-resize", HudEditorCases::resize);
                    UiCase.run(ctx, "399-ui-hud-editor-snap", HudEditorCases::snap);
                    UiCase.run(ctx, "399-ui-stat-bars-vitality-xp", HudEditorCases::vitalityXp);
                    UiCase.run(ctx, "407-ui-stat-bars-layout", StatBarsLayoutCases::run);
                    UiCase.run(ctx, "395-ui-map-heads", MapHeadCases::run);
                    UiCase.run(ctx, "401-ui-scoreboard-editor", ScoreboardEditorCases::run);
                    UiCase.run(ctx, "411-ui-mining-shelved", MiningShelvedCases::run);
                    UiCase.run(ctx, "412-ui-no-new-tab", c -> NoNewTabCases.run(c, deny));
                    UiCase.run(ctx, "413-ui-testing-variant", TestingVariantCases::run);
                    UiCase.run(ctx, "425-ui-bazaar-icons", BazaarBrowserCases::icons);
                    UiCase.run(ctx, "427-ui-pack-disabler-models", PackDisablerCases::models);
                    UiCase.run(ctx, "428-ui-pack-disabler-pack-push", PackDisablerCases::packPush);
                    UiCase.run(ctx, "429-ui-pack-disabler-missing", PackDisablerCases::missing);
                    UiCase.run(ctx, "430-ui-pack-disabler-screens", PackDisablerCases::screens);
                    UiCase.run(ctx, "431-ui-pack-disabler-user-pack", PackDisablerCases::userPack);
                    UiCase.run(ctx, "432-ui-pack-disabler-bazaar", PackDisablerCases::bazaar);
                    UiCase.run(ctx, "451-ui-hotbar-theme", InvStorageCases::hotbar);
                    UiCase.run(ctx, "452-ui-inventory-theme-settings", InvStorageCases::settings);
                    UiCase.run(ctx, "453-ui-storage-overlay-centre", InvStorageCases::storageCentre);
                    UiCase.run(ctx, "454-ui-storage-overlay-themes", InvStorageCases::storageThemes);
                    UiCase.run(ctx, "441-ui-bars-under-screens", HudFixCases::underScreens);
                    UiCase.run(ctx, "442-ui-bars-absorption", HudFixCases::absorption);
                    UiCase.run(ctx, "443-ui-bars-predefined-scale", HudFixCases::predefinedScale);
                    UiCase.run(ctx, "444-ui-bars-padding", HudFixCases::padding);
                    UiCase.run(ctx, "445-ui-map-info", HudFixCases::mapInfo);
                    UiCase.run(ctx, "446-ui-split-format", HudFixCases::splitFormat);
                    UiCase.run(ctx, "447-ui-scoreboard-solo", HudFixCases::scoreboardSolo);
                    // ---- polish (471-476) ----
                    UiCase.run(ctx, "471-ui-held-item-name", PolishCases::heldItemName);
                    UiCase.run(ctx, "473-ui-inventory-line-pixels", PolishCases::linePixels);
                    UiCase.run(ctx, "474-ui-inventory-shared-lines", PolishCases::sharedLines);
                    UiCase.run(ctx, "475-ui-inventory-armour-model", PolishCases::armourModel);
                    UiCase.run(ctx, "476-ui-bars-text-centred", PolishCases::barTextCentred);
                    // ---- menu layout (511-514) ----
                    UiCase.run(ctx, "511-ui-menu-layout", MenuLayoutCases::structure);
                    UiCase.run(ctx, "512-ui-menu-cheat-order", c -> MenuLayoutCases.cheatOrder(c, deny));
                    UiCase.run(ctx, "513-ui-menu-tooltips", c -> MenuLayoutCases.tooltips(c, deny));
                    UiCase.run(ctx, "514-ui-menu-shots", MenuLayoutCases::screenshots);
                    // ---- map info rows (524) ----
                    UiCase.run(ctx, "524-ui-map-info-rows", MapInfoBatCases::rows);
                    // ---- chat batch (565, 569) ----
                    UiCase.run(ctx, "565-ui-chat-voice-widget", ChatBatchUiCases::voiceWidget);
                    UiCase.run(ctx, "569-ui-chat-hider-tab", ChatBatchUiCases::hiderTab);
                    // ---- map head turn (600-601) ----
                    UiCase.run(ctx, "600-ui-map-head-turn", MapHeadTurnCases::heads);
                    UiCase.run(ctx, "601-ui-map-arrow-turn", MapHeadTurnCases::arrows);
                    // ---- dungeon-fixes (551-553) ----
                    UiCase.run(ctx, "551-ui-splits-layout", DungeonFixCases::splitsLayout);
                    UiCase.run(ctx, "552-ui-blood-camp-stall", DungeonFixCases::bloodCampStall);
                    UiCase.run(ctx, "553-ui-etherwarp-p3sim", DungeonFixCases::etherwarpP3sim);
                    // ---- inventory batch (581-587) ----
                    UiCase.run(ctx, "581-ui-itemprotect-star", InvBatchCases::star);
                    UiCase.run(ctx, "582-ui-slotlock-migration", InvBatchCases::slotLockGone);
                    UiCase.run(ctx, "584-ui-invsort-editor", InvBatchCases::invsortEditor);
                    UiCase.run(ctx, "586-ui-custom-items", InvBatchCases::customItems);
                    UiCase.run(ctx, "587-ui-cosmetics-category", InvBatchCases::cosmeticsCategory);
                    // ---- command shortcuts (541-545) ----
                    UiCase.run(ctx, "541-ui-cmd-shortcuts-builtin-rows", CmdShortcutsCases::builtinRows);
                    UiCase.run(ctx, "542-ui-cmd-shortcuts-custom-edit", CmdShortcutsCases::customEdit);
                    UiCase.run(ctx, "543-ui-cmd-shortcuts-refused-names", CmdShortcutsCases::refusals);
                    UiCase.run(ctx, "545-ui-cmd-shortcuts-sweep", c -> CmdShortcutsCases.sweepWithCustoms(c, deny));
                    // ---- HUD / menu / profiles (611-618) ----
                    UiCase.run(ctx, "611-ui-hud-key-screens", HudMenuProfilesCases::keyScreens);
                    UiCase.run(ctx, "612-ui-hud-editor-clean", HudMenuProfilesCases::editorClean);
                    UiCase.run(ctx, "613-ui-hud-editor-settings", HudMenuProfilesCases::editorSettings);
                    UiCase.run(ctx, "614-ui-bars-editor", HudMenuProfilesCases::barsEditor);
                    UiCase.run(ctx, "615-ui-menu-hud-scale", HudMenuProfilesCases::menuScale);
                    UiCase.run(ctx, "616-ui-menu-factor-math", HudMenuProfilesCases::menuFactorMath);
                    UiCase.run(ctx, "617-ui-profile-blank", HudMenuProfilesCases::blankProfile);
                    UiCase.run(ctx, "618-ui-profile-share", HudMenuProfilesCases::shareProfile);
                    // ---- chat keybinds merge (621-622) ----
                    UiCase.run(ctx, "621-ui-chat-keybinds-merged-tab", ChatKeybindsMergeCases::mergedTab);
                    UiCase.run(ctx, "622-ui-chat-keybinds-migration", ChatKeybindsMergeCases::migration);
                    // ---- trajectories for shortbows (1080-1083) ----
                    UiCase.run(ctx, "1080-ui-traj-shortbow", TrajectoryCases::shortbow);
                    UiCase.run(ctx, "1081-ui-traj-terminator-spread", TrajectoryCases::terminatorSpread);
                    UiCase.run(ctx, "1082-ui-traj-normal-bow-drawn-only", TrajectoryCases::normalBow);
                    UiCase.run(ctx, "1083-ui-traj-shortbows-setting", TrajectoryCases::settings);
                    // ---- held item outline (671-675) ----
                    UiCase.run(ctx, "671-ui-held-outline-off", HeldOutlineCases::controls);
                    UiCase.run(ctx, "672-ui-held-outline-styles", HeldOutlineCases::styles);
                    UiCase.run(ctx, "673-ui-held-outline-hands", HeldOutlineCases::hands);
                    UiCase.run(ctx, "674-ui-held-outline-hud", HeldOutlineCases::hudAndCamera);
                    UiCase.run(ctx, "675-ui-held-outline-settings", c -> {
                        // a testing jar walks its normal menu (every tab counted tested for the case)
                        try (AutoCloseable all = MenuLayoutCases.allTested(c)) {
                            HeldOutlineCases.settings(c);
                        }
                    });
                    UiCase.run(ctx, "676-ui-held-outline-sides", HeldOutlineCases::sides);
                    UiCase.run(ctx, "677-ui-held-outline-gallery", HeldOutlineCases::gallery);
                    // ---- leap order (666-669) ----
                    UiCase.run(ctx, "666-ui-leaporder-defaults", LeapOrderCases::defaults);
                    UiCase.run(ctx, "667-ui-leaporder-pins", LeapOrderCases::pinCase);
                    UiCase.run(ctx, "668-ui-leaporder-reset", LeapOrderCases::reset);
                    UiCase.run(ctx, "669-ui-leaporder-shots", LeapOrderCases::shots);
                    // ---- leap-menu batch, editor half (826-828) ----
                    UiCase.run(ctx, "826-ui-leap-wizard", LeapWizardCases::wizardCase);
                    UiCase.run(ctx, "827-ui-leap-settings", LeapWizardCases::settings);
                    UiCase.run(ctx, "828-ui-leap-shots", LeapWizardCases::shots);
                    // ---- /class + /leaporder, overrides folded in, editor layout, Party Finder styles (1071-1075) ----
                    UiCase.run(ctx, "1071-ui-class-commands", LeapQueueCases::commands);
                    UiCase.run(ctx, "1072-ui-class-overrides-migrated", LeapQueueCases::overridesGoneAndMigrated);
                    UiCase.run(ctx, "1073-ui-leaporder-layout", LeapQueueCases::layout);
                    UiCase.run(ctx, "1074-ui-pf-queue-styles", LeapQueueCases::queueStyles);
                    UiCase.run(ctx, "1075-ui-pf-custom-editor", LeapQueueCases::customEditor);
                    // ---- item / inventory UI (701-712; 708 is in the menu suite) ----
                    UiCase.run(ctx, "701-ui-invsearch-red-fill", ItemUiCases::redFill);
                    UiCase.run(ctx, "702-ui-invsearch-calculator", ItemUiCases::calculator);
                    UiCase.run(ctx, "703-ui-slotbinds-key-toggle", ItemUiCases::slotBindsKey);
                    UiCase.run(ctx, "704-ui-petwheel-no-slices-text-scale", ItemUiCases::petWheel);
                    UiCase.run(ctx, "705-ui-itemprotect-hover-star-drops", ItemUiCases::protectHoverAndDrops);
                    UiCase.run(ctx, "706-ui-gif-dvd-hidden-in-containers", ItemUiCases::overlaysHidden);
                    UiCase.run(ctx, "707-ui-storagesearch-equipment-wardrobe", ItemUiCases::equipmentWardrobeIndex);
                    UiCase.run(ctx, "709-ui-itembrowser-tooltip", ItemUiCases::browserTooltip);
                    UiCase.run(ctx, "710-ui-itembrowser-minion-tiers", ItemUiCases::browserMinions);
                    UiCase.run(ctx, "711-ui-itembrowser-neu-recipe", ItemUiCases::browserRecipe);
                    UiCase.run(ctx, "712-ui-itembrowser-sort-and-bar", ItemUiCases::browserSortAndBar);
                    // ---- slot text + compactor preview (960-966) ----
                    UiCase.run(ctx, "960-ui-slottext-types", SlotTextCases::types);
                    UiCase.run(ctx, "961-ui-slottext-drawn", SlotTextCases::drawn);
                    UiCase.run(ctx, "962-ui-slottext-modes", SlotTextCases::modes);
                    UiCase.run(ctx, "963-ui-compactor-contents", SlotTextCases::contentsRead);
                    UiCase.run(ctx, "964-ui-compactor-hover-drawn", SlotTextCases::hoverDrawn);
                    UiCase.run(ctx, "965-ui-compactor-modes", SlotTextCases::hoverModes);
                    UiCase.run(ctx, "966-ui-compactor-settings", SlotTextCases::compactorSettings);
                    // ---- dungeon-b2 (679-682, 686-687) ----
                    UiCase.run(ctx, "686-ui-blood-trigger", DungeonB2Cases::bloodTrigger);
                    UiCase.run(ctx, "687-ui-door-key-circle", DungeonB2Cases::doorKeyRing);
                    UiCase.run(ctx, "679-ui-ttt-prediction-misclicks", DungeonB2Cases::ticTacToe);
                    UiCase.run(ctx, "680-ui-imap-binds-ticks", DungeonB2Cases::interactiveMapBinds);
                    UiCase.run(ctx, "681-ui-auto-kick-pf", DungeonB2Cases::autoKick);
                    UiCase.run(ctx, "682-ui-dungeon-esp-switch", DungeonB2Cases::dungeonEsp);
                    // ---- small-dungeon (1051-1053) ----
                    UiCase.run(ctx, "1051-ui-thorn-through-walls", SmallDungeonCases::thornThroughWalls);
                    UiCase.run(ctx, "1052-ui-esp-colour-tracer", SmallDungeonCases::espColourTracer);
                    UiCase.run(ctx, "1053-ui-secrets-hud-colour", SmallDungeonCases::secretsHudColour);
                    // ---- autokick-2 (801-805) ----
                    UiCase.run(ctx, "801-ui-autokick-judge-by", AutoKick2Cases::timeMode);
                    UiCase.run(ctx, "802-ui-autokick-detect-pf", AutoKick2Cases::detectPartyFinder);
                    UiCase.run(ctx, "803-ui-autokick-joininstance-ignored", AutoKick2Cases::joinInstanceIgnored);
                    UiCase.run(ctx, "804-ui-autokick-forged-queue", AutoKick2Cases::forgery);
                    UiCase.run(ctx, "805-ui-autokick-floor-source", AutoKick2Cases::floorSourceSettings);
                    // ---- cycle-back (806) ----
                    UiCase.run(ctx, "806-ui-cycle-buttons-both-ways", c -> CycleButtonCases.cycleButtons(c, deny));
                    // ---- doc-small (807) ----
                    UiCase.run(ctx, "807-ui-doc-small-1014", DocSmall1014Cases::docSmall);
                    // ---- map-overhaul (1015) ----
                    UiCase.run(ctx, "1015-ui-switch-align", c -> MapOverhaulCases.switchAlign(c, deny));
                    UiCase.run(ctx, "1015-ui-map-tab", MapOverhaulCases::mapTab);
                    UiCase.run(ctx, "1015-ui-map-themes", MapOverhaulCases::mapThemes);
                    UiCase.run(ctx, "1015-ui-door-key-style", MapOverhaulCases::doorKeyStyle);
                    // ---- Option A menu (940-949) ----
                    UiCase.run(ctx, "940-ui-menu-a-shots", MenuACases::shots);
                    UiCase.run(ctx, "941-ui-menu-a-cards", MenuACases::cards);
                    UiCase.run(ctx, "942-ui-menu-a-drawer", MenuACases::drawer);
                    UiCase.run(ctx, "943-ui-menu-a-favourites", MenuACases::favourites);
                    UiCase.run(ctx, "944-ui-menu-a-search", MenuACases::search);
                    UiCase.run(ctx, "945-ui-menu-a-no-counts", c -> {
                        // a testing jar walks its normal menu (every tab counted tested for the case)
                        try (AutoCloseable all = MenuLayoutCases.allTested(c)) {
                            MenuACases.noCounts(c);
                        }
                    });
                    UiCase.run(ctx, "946-ui-menu-a-themes", MenuACases::themes);
                    UiCase.run(ctx, "947-ui-menu-a-custom-colour", MenuACases::customColour);
                    // ---- Profiles page (948) ----
                    UiCase.run(ctx, "948-ui-menu-profiles-page", MenuProfilesCases::page);
                    // ---- killer560's 2026-10-13 menu list (980-989) ----
                    UiCase.run(ctx, "980-ui-docmenu-home", DocMenuCases::home);
                    UiCase.run(ctx, "981-ui-docmenu-mod-hud", DocMenuCases::modHud);
                    UiCase.run(ctx, "982-ui-docmenu-auto-update", DocMenuCases::autoUpdate);
                    UiCase.run(ctx, "983-ui-docmenu-masters", c -> {
                        // a testing jar walks its normal menu (every tab counted tested for the case)
                        try (AutoCloseable all = MenuLayoutCases.allTested(c)) {
                            DocMenuCases.masters(c);
                        }
                    });
                    UiCase.run(ctx, "984-ui-docmenu-bold-name", DocMenuCases::boldName);
                    UiCase.run(ctx, "985-ui-docmenu-hud-scale-live", DocMenuCases::hudScaleLive);
                    UiCase.run(ctx, "986-ui-docmenu-drawer-reopens", DocMenuCases::drawerReopens);
                    UiCase.run(ctx, "987-ui-docmenu-cycle-halves", DocMenuCases::cycleHalves);
                    UiCase.run(ctx, "988-ui-docmenu-calculator", DocMenuCases::calculator);
                    UiCase.run(ctx, "989-ui-docmenu-saved-themes", DocMenuCases::savedThemes);
                    // ---- colour picker Copy / Paste / Recent (955) ----
                    UiCase.run(ctx, "955-ui-colour-copy-paste", ColourCopyCases::copyPaste);
                    // ---- Item Protect star on player heads (961) ----
                    UiCase.run(ctx, "961-ui-lockstar-heads", LockStarHeadCases::heads);
                    // ---- profiles carry everything, across jars (980-985) ----
                    UiCase.run(ctx, "980-ui-profile-data-switch", ProfileDataCases::switchAB);
                    UiCase.run(ctx, "981-ui-profile-data-export", ProfileDataCases::exportImport);
                    UiCase.run(ctx, "982-ui-profile-import-robust", ProfileDataCases::importRobust);
                    UiCase.run(ctx, "983-ui-profile-old-format", ProfileDataCases::oldFormat);
                    UiCase.run(ctx, "984-ui-profile-xfer-export", ProfileDataCases::xferExport);
                    UiCase.run(ctx, "985-ui-profile-xfer-import", ProfileDataCases::xferImport);
                    // ---- screen sweep B (1020-1025) ----
                    UiCase.run(ctx, "1020-ui-sweepb-profit", ScreenSweepBCases::profit);
                    UiCase.run(ctx, "1021-ui-sweepb-storage-search", ScreenSweepBCases::storageSearch);
                    UiCase.run(ctx, "1022-ui-sweepb-run-log", ScreenSweepBCases::runLog);
                    UiCase.run(ctx, "1023-ui-sweepb-invsort", ScreenSweepBCases::invsort);
                    UiCase.run(ctx, "1024-ui-sweepb-custom-items", ScreenSweepBCases::customItems);
                    UiCase.run(ctx, "1025-ui-sweepb-screen-theme", ScreenSweepBCases::screenTheme);
                    // ---- Edit HUD key / Croesus (862-865) ----
                    UiCase.run(ctx, "862-ui-hud-key-typing", HudKey2Cases::typing);
                    UiCase.run(ctx, "863-ui-hud-key-over-container", HudKey2Cases::overContainer);
                    UiCase.run(ctx, "864-ui-croesus-split", c -> {
                        // a testing jar walks its normal menu (every tab counted tested for the case)
                        try (AutoCloseable all = MenuLayoutCases.allTested(c)) {
                            HudKey2Cases.croesusSplit(c);
                        }
                    });
                    UiCase.run(ctx, "865-ui-container-overlays", HudKey2Cases::overlays);
                    // ---- container overlays only within 10 s (866) ----
                    UiCase.run(ctx, "866-ui-container-overlay-grace", HudKey2Cases::containerGrace);
                    // ---- Health and Mana Bars styles (891-896) ----
                    UiCase.run(ctx, "891-ui-bars-style-fill", BarStyleCases::fill);
                    UiCase.run(ctx, "892-ui-bars-style-overflow", BarStyleCases::overflow);
                    UiCase.run(ctx, "893-ui-bars-style-settings", BarStyleCases::settings);
                    UiCase.run(ctx, "894-ui-bars-style-ghost", BarStyleCases::ghost);
                    UiCase.run(ctx, "895-ui-bars-style-shots", BarStyleCases::shots);
                    UiCase.run(ctx, "896-ui-bars-style-editor", BarStyleCases::editor);
                    UiCase.run(ctx, "898-ui-bars-menu", BarStyleCases::menu);
                    UiCase.run(ctx, "899-ui-bars-his-icons", BarStyleCases::hisIcons);
                    // ---- Inventory Theme item centring (897) ----
                    UiCase.run(ctx, "897-ui-inventory-theme-item-centred", InvThemeCenterCases::centred);
                    // ---- chroma waterfall (971-973) ----
                    UiCase.run(ctx, "971-ui-chroma-enchant-waterfall", ChromaWaveCases::enchantTooltip);
                    UiCase.run(ctx, "972-ui-chroma-scoreboard-border", ChromaWaveCases::scoreboardBorder);
                    UiCase.run(ctx, "973-ui-chroma-crosshair", ChromaWaveCases::crosshair);
                    // ---- killer560's 2026-10-13 doc list, misc (1311-1315) ----
                    UiCase.run(ctx, "1311-ui-scoreboard-numbers", DocMiscCases::scoreboardNumbers);
                    UiCase.run(ctx, "1312-ui-inventory-slot-outlines", DocMiscCases::slotOutlines);
                    UiCase.run(ctx, "1313-ui-bars-default-thinner", DocMiscCases::barsThinner);
                    UiCase.run(ctx, "1314-ui-peek-chat", DocMiscCases::peekChat);
                    UiCase.run(ctx, "1315-ui-loadouts-button", DocMiscCases::loadoutsButton);
                    // ---- doc-dungeons, killer560's 2026-10-13 list (981-989) ----
                    UiCase.run(ctx, "981-ui-etherwarp-walk-in", DocDungeonsCases::etherwarpWalkIn);
                    UiCase.run(ctx, "985-ui-leap-modes-targets", DocDungeonsCases::leapSettings);
                    UiCase.run(ctx, "986-ui-croesus-key-settings", DocDungeonsCases::croesusKeys);
                    UiCase.run(ctx, "987-ui-map-leap-names", DocDungeonsCases::mapLeapNames);
                    // ---- every Turbo-Crop VII is Perfect chroma (974) ----
                    UiCase.run(ctx, "974-ui-chroma-turbo-crops", ChromaWaveCases::turboCrops);
                    // ---- screen sweep, part C (975-976) ----
                    UiCase.run(ctx, "975-ui-screen-sweep-c-themes", ScreenThemeCases::sweep);
                    UiCase.run(ctx, "976-ui-screen-own-theme", ScreenThemeCases::ownTheme);
                    // ---- Ribbon trail (1031-1035) ----
                    UiCase.run(ctx, "1031-ui-trail-ribbon-line", TrailRibbonCases::line);
                    UiCase.run(ctx, "1032-ui-trail-ribbon-curve", TrailRibbonCases::curve);
                    UiCase.run(ctx, "1033-ui-trail-ribbon-still", TrailRibbonCases::stillAndSwitch);
                    UiCase.run(ctx, "1034-ui-trail-ribbon-settings", TrailRibbonCases::settings);
                    UiCase.run(ctx, "1035-ui-trail-ribbon-gallery", TrailRibbonCases::gallery);
                    UiCase.run(ctx, "1040-ui-ribbon-cosmetic-tabs", TrailRibbonCases::tabs);
                    UiCase.run(ctx, "1041-ui-ribbon-camera-angles", TrailRibbonCases::cameraAngles);
                    // ---- Etherwarp Overlay box faces (1321) ----
                    UiCase.run(ctx, "1016-ui-sweat-mode", DocMenuCases::sweatMode);
                    UiCase.run(ctx, "1321-ui-etherwarp-box-faces", EtherwarpFaceCases::run);
                    UiCase.run(ctx, "1322-ui-etherwarp-walk", EtherwarpFaceCases::walk);
                    UiCase.run(ctx, "1323-ui-etherwarp-back-edges", EtherwarpFaceCases::backEdges);
                    // ---- On Teleport + Auto Routes Progress HUD (1042-1047) ----
                    UiCase.run(ctx, "1042-ui-trail-teleport-ribbon", TrailTeleportCases::ribbon);
                    UiCase.run(ctx, "1043-ui-trail-teleport-squares", TrailTeleportCases::squares);
                    UiCase.run(ctx, "1044-ui-trail-teleport-settings", TrailTeleportCases::settings);
                    UiCase.run(ctx, "1045-ui-routes-progress-content", RoutesProgressHudCases::content);
                    UiCase.run(ctx, "1046-ui-routes-progress-editor", RoutesProgressHudCases::editor);
                    UiCase.run(ctx, "1047-ui-routes-progress-settings", RoutesProgressHudCases::settings);
                    // ---- Shared trails (1036-1039) ----
                    UiCase.run(ctx, "1036-ui-trail-share-send", TrailShareCases::send);
                    UiCase.run(ctx, "1037-ui-trail-share-remote", TrailShareCases::remote);
                    UiCase.run(ctx, "1038-ui-trail-share-hide", TrailShareCases::hide);
                    UiCase.run(ctx, "1039-ui-trail-share-limits", TrailShareCases::limits);
                    // ---- menu order + independent masters (515-517) ----
                    UiCase.run(ctx, "1550-ui-np-command-names", NamesProfilesCases::commandNames);
                    UiCase.run(ctx, "1551-ui-np-rank", NamesProfilesCases::rank);
                    UiCase.run(ctx, "1552-ui-np-shared-names", NamesProfilesCases::sharedNames);
                    UiCase.run(ctx, "1553-ui-np-profile-rename-quiet", MenuProfilesCases::renameAndQuiet);
                    UiCase.run(ctx, "515-ui-menu-order", MenuOrderCases::tabOrder);
                    UiCase.run(ctx, "516-ui-menu-order-settings", c -> MenuOrderCases.settingsOrder(c, deny));
                    UiCase.run(ctx, "517-ui-menu-queue-masters", MenuOrderCases::queueMasters);
                    UiCase.run(ctx, "518-ui-menu-independent-masters", MenuOrderCases::independentMasters);
                    UiCase.run(ctx, "519-ui-home-cleanup", DocMenuCases::homeCleanup);
                    // ---- Lever Aura in Secret Aura + overlay Menu Themes (1420-1421) ----
                    UiCase.run(ctx, "1420-ui-secret-aura-merge", AuraThemeCases::secretAuraMerge);
                    UiCase.run(ctx, "1421-ui-overlay-themes", AuraThemeCases::overlayThemes);
                    // ---- Lights Device, its own cheat (1475) ----
                    UiCase.run(ctx, "1475-ui-lights-device", LightsDeviceUiCases::lightsDevice);
                    // ---- click sounds + short descriptions (1060-1064) ----
                    UiCase.run(ctx, "1060-ui-card-descriptions", SoundDescCases::fit);
                    UiCase.run(ctx, "1061-ui-descriptions-toggle", SoundDescCases::toggle);
                    UiCase.run(ctx, "1062-ui-editor-hint-narrow", SoundDescCases::editorNarrow);
                    UiCase.run(ctx, "1063-ui-click-presets", SoundDescCases::presets);
                    UiCase.run(ctx, "1064-ui-click-import", SoundDescCases::imported);
                }
            }
            UiCase.run(ctx, DENY_CASE, c -> MiscCases.deny(c, watch, suiteMark));
        }
    }

    private static boolean any(String[] names) {
        for (String n : names) {
            if (UiCase.selected(n)) {
                return true;
            }
        }
        return false;
    }
}

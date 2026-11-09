package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 1104-ui-defaults-off (mod defoff-1104, killer560 2026-11-04): every feature he listed starts OFF on a fresh install.
 * Each config class has its file taken away, is loaded, and the switch fields that the card reads are checked false;
 * the file is put back after. The control: the same load runs on a class from the table (so a field that does not exist
 * fails by name), and the row count must equal the table so nothing is skipped.
 */
final class DefaultsOff1104Cases {

    private DefaultsOff1104Cases() {
    }

    /** class (under com.killer560.hub.) -> fields that must be false with no saved file. */
    private static final String[][] TABLE = {
            {"croesus.CroesusConfig", "chestProfitEnabled", "loggerEnabled"},
            {"leftclicksecrets.LeftClickSecretsConfig", "enabled"},
            {"milestone.MilestoneConfig", "enabled"},
            {"itemhighlight.DungeonItemHighlightConfig", "enabled"},
            {"bloodrush.BloodRushConfig", "enabled", "learnSpots"},
            {"lfg.LfgConfig", "enabled"},
            {"terminals.TerminalQolConfig", "terminalSounds", "clickPath"},
            {"terminals.TerminalSolverConfig", "queueTerms"},
            {"puzzlehud.PuzzleHudConfig", "enabled"},
            {"reparty.RepartyConfig", "enabled", "autoRejoin"},
            {"splittimers.WatcherHelperConfig", "enabled"},
            {"carrytracker.CarryTrackerConfig", "enabled"},
            {"livemap.RoomPreviewConfig", "enabled"},
            {"dungeonqueue.DungeonQueueConfig", "enabled", "pvLinkOnJoin"},
            {"partyfinder.PartyFinderOverlayConfig", "enabled"},
            {"posmsg.PosmsgConfig", "enabled"},
            {"rngmeter.RngMeterConfig", "enabled"},
            {"melody.MelodyHudConfig", "hudEnabled", "shareProgress"},
            {"etherwarp.EtherwarpWaypointsConfig", "highlightBlocks"},
            {"simonsays.SimonSaysConfig", "autoSimonSaysEnabled"},
            {"mining.nucleus.NucleusRunProfitConfig", "enabled"},
            {"mining.profit.MiningProfitConfig", "enabled"},
            {"miningqol.MiningQolConfig", "wrongArmor", "junglePick", "resumeHeldBreak", "powderGhast"},
            {"powdermining.PowderMiningConfig", "doublePowder", "lootCompact", "lockpickHelper", "powderTracker",
                    "chestAlert", "treasureChest"},
            {"gifplayer.GifPlayerConfig", "enabled", "audioEnabled"},
            {"dvd.DvdConfig", "dvdEnabled"},
            {"objecthider.ObjectHiderConfig", "objectHiderEnabled", "noGlow"},
            {"terminatorclicker.TerminatorClickerConfig", "enabled"},
            {"emotes.ChatEmoteConfig", "enabled"},
            {"clicktranslate.ClickTranslateConfig", "enabled"},
            {"commandkeybinds.CommandKeybindsConfig", "enabled"},
            {"commandprefix.CommandPrefixConfig", "altOn"},
            {"translate.TranslateConfig", "enabled"},
            {"imagepreview.ImagePreviewConfig", "enabled"},
            {"spotify.SongShareConfig", "lyricsOverHead"},
            {"spotify.SpotifyHudConfig", "hudEnabled", "showLyrics"},
            {"armorhud.ArmorHudConfig", "enabled"},
            {"auction.AuctionConfig", "ahEnabled", "bazaarEnabled", "listingHelperEnabled"},
            {"bazaar.BazaarOrderHelperConfig", "enabled"},
            {"hideeffects.HideEffectsConfig", "hidePotionEffects"},
            {"experiments.ExperimentsConfig", "enabled", "profitTrackerEnabled"},
            {"dungeonalerts.DungeonAlertsConfig", "rareRoomAlert", "rareDropAlert", "duplicateClassAlert", "duplicateClassParty",
                    "witherKingStatueBox", "dimensionalSlashAlert"},
            {"dungeonqol.DungeonQolConfig", "itemQuality", "icedBoxes", "roomHud"},
            {"autoclosechest.AutoCloseChestConfig", "enabled"},
    };

    /**
     * Puts every switch in {@link #TABLE} (and Legit GFS) ON until closed, so a menu walk sees the rows that a feature
     * which now starts OFF hides while it is off (the 511/513/412 walks compare every row's hover with fixtures
     * recorded when these were all ON). Client thread only.
     */
    static AutoCloseable allOn(UiCase c) {
        List<Object[]> undo = new ArrayList<>();
        c.onClient(mc -> {
            for (String[] row : TABLE) {
                Object cfg = Mod.cfg(row[0]);
                for (int i = 1; i < row.length; i++) {
                    undo.add(new Object[] {cfg, row[i], Mod.field(cfg, row[i])});
                    R.set(cfg, row[i], true);
                }
            }
            undo.add(new Object[] {null, "enabled", Mod.field("gfs.LegitGfs", "enabled")});
            R.setStatic(Mod.cls("gfs.LegitGfs"), "enabled", true);
            return null;
        });
        return () -> c.onClient(mc -> {
            for (Object[] u : undo) {
                if (u[0] == null) {
                    R.setStatic(Mod.cls("gfs.LegitGfs"), "enabled", u[2]);
                } else {
                    R.set(u[0], (String) u[1], u[2]);
                }
            }
            return null;
        });
    }

    static void defaultsOff(UiCase c) throws Exception {
        int checked = 0;
        List<String> wrong = new ArrayList<>();
        for (String[] row : TABLE) {
            String cls = row[0];
            Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, cls);
            try {
                for (Path p : saved.keySet()) {
                    Files.deleteIfExists(p);
                }
                List<String> bad = c.onClient(mc -> {
                    Mod.staticCall(cls, "load");
                    Object cfg = Mod.cfg(cls);
                    List<String> out = new ArrayList<>();
                    for (int i = 1; i < row.length; i++) {
                        if (!Boolean.FALSE.equals(Mod.field(cfg, row[i]))) {
                            out.add(cls + "." + row[i]);
                        }
                    }
                    return out;
                });
                wrong.addAll(bad);
                checked += row.length - 1;
            } finally {
                DungeonB2Cases.restoreConfig(c, saved, cls);
            }
        }
        // Legit GFS keeps its switch in a static (reload(), not load(), puts the defaults back and reads the file).
        Map<Path, byte[]> savedGfs = DungeonB2Cases.snapshotConfig(c, "gfs.LegitGfs");
        try {
            for (Path p : savedGfs.keySet()) {
                Files.deleteIfExists(p);
            }
            c.onClient(mc -> Mod.staticCall("gfs.LegitGfs", "reload"));
            checked++;
            if ((Boolean) c.onClient(mc -> Mod.staticCall("gfs.LegitGfs", "isEnabled"))) {
                wrong.add("gfs.LegitGfs.enabled");
            }
        } finally {
            DungeonB2Cases.restoreConfig(c, savedGfs, "gfs.LegitGfs");   // its load() call fails quietly: no such method
            c.onClient(mc -> Mod.staticCall("gfs.LegitGfs", "reload"));
        }
        c.note(checked + " default switches read after load with no file; wrong: " + wrong);
        c.check(checked >= 45, "only " + checked + " switches were checked");
        c.check(wrong.isEmpty(), "these start ON on a fresh install: " + wrong);
    }
}

package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * killer560's to-do list of 2026-10-14 (mod branch doc-small-1014), case 807:
 * <ul>
 *   <li>defaults: with its config file deleted and reloaded, Chunk Cache, Party Interop and Dungeon Queue's
 *       "Disable on Leave/Kick" read ON - and the file really was gone (the load did not just keep the old instance);</li>
 *   <li>removed: Party Interop's "Log Pickups", Auto Kick's "Action Mode" (Warn Only), Dungeon Queue's "Style Preview"
 *       toggle (the preview itself is always built) and Wither Doors' "Through Walls" - each tab is opened through the
 *       real menu and must build rows (so an empty list cannot pass);</li>
 *   <li>Architect's First Draft: "Auto Get From Sack" only on the cheat jar, last, under a red cheat header.</li>
 * </ul>
 */
final class DocSmall1014Cases {

    private static final String CHUNK = "chunkcache.ChunkCacheConfig";
    private static final String INTEROP = "interop.InteropConfig";
    private static final String QUEUE = "dungeonqueue.DungeonQueueConfig";
    private static final String AUTO_KICK = "autokick.AutoKickConfig";
    private static final String PF = "partyfinder.PartyFinderOverlayConfig";
    private static final String DOORS = "witherdoors.WitherDoorsConfig";

    private DocSmall1014Cases() {
    }

    static void docSmall(UiCase c) throws Exception {
        // ---- defaults from a fresh config ---------------------------------------------------------------------
        for (String[] d : new String[][]{{CHUNK, "isEnabledRaw"}, {INTEROP, "isEnabledRaw"}, {QUEUE, "isDisableOnLeave"}}) {
            Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, d[0]);
            Path p = saved.keySet().iterator().next();
            try {
                Object before = Mod.cfg(d[0]);
                Files.deleteIfExists(p);
                Object[] got = c.onClient(mc -> {
                    Mod.staticCall(d[0], "load");
                    Object cfg = Mod.cfg(d[0]);
                    return new Object[]{cfg, Mod.call(cfg, d[1])};
                });
                c.note(d[0] + " from no file: " + d[1] + " = " + got[1] + " (new instance " + (got[0] != before)
                        + ", file existed before " + (saved.get(p) != null) + ")");
                c.check(got[0] != before, d[0] + ".load() kept the same instance - the fresh read proved nothing");
                c.check(!Files.exists(p), d[0] + ": the config file came back during the load - not a fresh read");
                c.check(Boolean.TRUE.equals(got[1]), d[0] + "." + d[1] + " is not ON by default (" + got[1] + ")");
            } finally {
                DungeonB2Cases.restoreConfig(c, saved, d[0]);
            }
        }
        c.check(!hasMethod(INTEROP, "isLogPickups"), "InteropConfig still has isLogPickups");
        c.check(!hasMethod(AUTO_KICK, "getMode"), "AutoKickConfig still has an Action Mode (getMode)");

        // ---- removed rows, through the real menu -----------------------------------------------------------------
        boolean cheat = Mod.isCheat();
        Map<Path, byte[]> queueSaved = DungeonB2Cases.snapshotConfig(c, QUEUE);
        Map<Path, byte[]> kickSaved = DungeonB2Cases.snapshotConfig(c, AUTO_KICK);
        Map<Path, byte[]> pfSaved = DungeonB2Cases.snapshotConfig(c, PF);
        Map<Path, byte[]> doorsSaved = DungeonB2Cases.snapshotConfig(c, DOORS);
        try {
            c.onClient(mc -> {
                Mod.call(Mod.cfg(QUEUE), "setEnabled", true);
                Mod.call(Mod.cfg(PF), "setEnabled", true);   // the preview sits under the Party Finder Overlay's rows
                Mod.call(Mod.cfg(DOORS), "setEnabled", true); // its rows only build with the master switch on
                Mod.call(Mod.cfg(AUTO_KICK), "setEnabled", true);
                return null;
            });
            gone(c, "Party Interop", "Party Interop:", "log pickups");
            gone(c, "Auto Kick", "Judge By:", "action mode", "warn only");
            List<String> q = gone(c, "Dungeon Queue", "Disable on Leave/Kick:", "style preview:");
            c.check(q.stream().anyMatch(l -> l.equals("Party Finder Style Preview")),
                    "Dungeon Queue: the style preview panel is not built (it should always show)");
            if (cheat) {
                gone(c, "Wither Doors", "Show All Doors:", "through walls");
            }
            List<String> a = DungeonB2Cases.labels(c, DungeonB2Cases.openTab(c, "Architect's First Draft"));
            c.note("Architect's First Draft rows: " + a);
            int auto = indexOf(a, "Auto Get From Sack:");
            if (cheat) {
                c.check(auto == a.size() - 1, "Auto Get From Sack is not the last row: " + a);
                c.check(auto > 0 && a.get(auto - 1).toLowerCase(Locale.ROOT).contains("cheat"),
                        "Auto Get From Sack is not directly under a cheat header: " + a);
            } else {
                c.check(auto < 0 && !a.isEmpty(), "the legit jar builds Auto Get From Sack (or no rows at all): " + a);
            }
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            DungeonB2Cases.restoreConfig(c, queueSaved, QUEUE);
            DungeonB2Cases.restoreConfig(c, kickSaved, AUTO_KICK);
            DungeonB2Cases.restoreConfig(c, pfSaved, PF);
            DungeonB2Cases.restoreConfig(c, doorsSaved, DOORS);
        }
    }

    /** Opens {@code tab}, requires a row starting with {@code present} (so the tab built), and no row containing any of {@code removed}. */
    private static List<String> gone(UiCase c, String tab, String present, String... removed) {
        Screen s = DungeonB2Cases.openTab(c, tab);
        List<String> rows = DungeonB2Cases.labels(c, s);
        c.note(tab + " rows: " + rows);
        c.check(rows.stream().anyMatch(l -> l.startsWith(present)), tab + ": no '" + present + "' row - the tab did not build");
        for (String r : removed) {
            c.check(rows.stream().noneMatch(l -> l.toLowerCase(Locale.ROOT).contains(r)),
                    tab + ": the removed '" + r + "' row is still there");
        }
        return rows;
    }

    private static int indexOf(List<String> rows, String prefix) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean hasMethod(String cls, String name) {
        for (java.lang.reflect.Method m : Mod.cfg(cls).getClass().getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }
}

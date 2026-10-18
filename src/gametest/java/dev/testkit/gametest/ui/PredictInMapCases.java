package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.components.AbstractWidget;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 1018-predict-in-map (killer560, 2026-10-18: "predictive rooms should be inside of the map not its own toggle").
 * <ul>
 *   <li>The Dungeon Map drawer has a Predictive Rooms row (and Special Room Guess under it), directly after the map's
 *       own on/off row, and a tooltip under the key {@code dungeon map/predictive rooms}.</li>
 *   <li>No card called Predictive Rooms exists in the menu and the class {@code gui.tab.PredictiveRoomsTab} is gone.</li>
 *   <li>The setting is still on by default (a fresh config), the row's own press flips it and writes the file, the
 *       old config key still reads, and a reload from disk gives the saved value back.</li>
 * </ul>
 * On the parent commit (mod deffba6f) the row is not in the Dungeon Map drawer and the card exists.
 */
final class PredictInMapCases {

    private static final String PCFG = "livemap.predict.PredictiveRoomsConfig";
    private static final String TIPS = "gui.SettingTooltips";

    private PredictInMapCases() {
    }

    private static String nameOf(String label) {
        int colon = label.indexOf(':');
        return (colon > 0 ? label.substring(0, colon) : label).trim();
    }

    private static int indexOf(List<String> labels, String name) {
        for (int i = 0; i < labels.size(); i++) {
            if (nameOf(labels.get(i)).equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** The Dungeon Map drawer's settings rows in screen order, labels as shown. */
    private static List<String> mapRows(UiCase c) {
        return c.onClient(mc -> {
            try {
                ModScreenDriver d = new ModScreenDriver(mc);
                for (Object leaf : d.leaves()) {
                    if ("Dungeon Map".equals(R.get(leaf, "name"))) {
                        d.open(leaf);
                        d.rebuild();
                        List<AbstractWidget> ws = new ArrayList<>(d.content());
                        ws.sort((a, b) -> a.getY() != b.getY() ? Integer.compare(a.getY(), b.getY())
                                : Integer.compare(a.getX(), b.getX()));
                        List<String> out = new ArrayList<>();
                        for (AbstractWidget w : ws) {
                            if (d.isSettingsButton(w)) {
                                out.add(ModScreenDriver.label(w));
                            }
                        }
                        return out;
                    }
                }
                throw new AssertionError("no 'Dungeon Map' tab");
            } catch (Throwable e) {
                throw new AssertionError(e);
            }
        });
    }

    /** Presses the Dungeon Map drawer's row called {@code name} through the real widget. */
    private static boolean pressRow(UiCase c, String name) {
        return c.onClient(mc -> {
            try {
                ModScreenDriver d = new ModScreenDriver(mc);
                for (Object leaf : d.leaves()) {
                    if ("Dungeon Map".equals(R.get(leaf, "name"))) {
                        d.open(leaf);
                        d.rebuild();
                        for (AbstractWidget w : d.content()) {
                            if (d.isSettingsButton(w) && nameOf(ModScreenDriver.label(w)).equals(name)) {
                                return ModScreenDriver.press(w);
                            }
                        }
                    }
                }
                return false;
            } catch (Throwable e) {
                throw new AssertionError(e);
            }
        });
    }

    static void run(UiCase c) throws Exception {
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, PCFG);
        try (AutoCloseable all = MenuLayoutCases.allTested(c)) {
            // no card of its own
            c.check(!Mod.has("gui.tab.PredictiveRoomsTab"), "gui.tab.PredictiveRoomsTab still exists");
            ModScreenDriver d0 = MenuLayoutCases.driver(c);
            List<ModScreenDriver.Node> flat = new ArrayList<>();
            c.onClient(mc -> {
                ModScreenDriver.flatten(d0.tree(), flat);
                return null;
            });
            for (ModScreenDriver.Node n : flat) {
                c.check(!n.name().toLowerCase(java.util.Locale.ROOT).contains("predictive"),
                        "the menu still has a '" + n.path() + "' card");
            }
            MenuLayoutCases.close(c, d0);

            // a fresh config: on by default
            Path file = c.onClient(mc -> (Path) R.getStatic(R.cls(PCFG), "CONFIG_PATH"));
            Files.deleteIfExists(file);
            boolean fresh = c.onClient(mc -> {
                Mod.staticCall(PCFG, "load");
                return (Boolean) Mod.call(Mod.cfg(PCFG), "isEnabledRaw");
            });
            c.check(fresh, "Predictive Rooms is not ON by default");

            // the row sits in the Dungeon Map drawer, right after the map's own switch
            c.onClient(mc -> {
                Mod.call(Mod.cfg("livemap.LiveMapConfig"), "setEnabled", true);
                return null;
            });
            List<String> rows = mapRows(c);
            c.note("Dungeon Map drawer: " + rows);
            int map = indexOf(rows, "Dungeon Map");
            int pr = indexOf(rows, "Predictive Rooms");
            c.check(pr >= 0, "no Predictive Rooms row in the Dungeon Map drawer: " + rows);
            c.check(map >= 0 && pr == map + 1, "Predictive Rooms is not directly after the Dungeon Map switch: " + rows);
            c.check(pr >= 0 && rows.get(pr).contains("ON"), "the Predictive Rooms row does not read ON: " + rows);
            c.check(indexOf(rows, "Special Room Guess") >= 0, "no Special Room Guess row in the Dungeon Map drawer");

            // its tooltip moved with it
            @SuppressWarnings("unchecked")
            Map<String, String> tips = (Map<String, String>) R.getStatic(R.cls(TIPS), "DESCRIPTIONS");
            c.check(tips.containsKey("dungeon map/predictive rooms"), "no tooltip 'dungeon map/predictive rooms'");
            c.check(!tips.containsKey("predictive rooms/predictive rooms"), "the old 'predictive rooms/' tooltip key is still there");

            // pressing the row flips the setting and writes the file; a reload from disk keeps it
            c.check(pressRow(c, "Predictive Rooms"), "the Predictive Rooms row could not be pressed");
            boolean afterPress = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(PCFG), "isEnabledRaw"));
            c.check(!afterPress, "pressing the row did not turn Predictive Rooms off");
            c.check(Files.exists(file), "pressing the row wrote no config file");
            String text = Files.exists(file) ? Files.readString(file) : "";
            c.note("saved: " + text.replace('\n', ' '));
            boolean reloaded = c.onClient(mc -> {
                Mod.staticCall(PCFG, "load");
                return (Boolean) Mod.call(Mod.cfg(PCFG), "isEnabledRaw");
            });
            c.check(!reloaded, "Predictive Rooms came back ON after a reload from disk");
            List<String> off = mapRows(c);
            int prOff = indexOf(off, "Predictive Rooms");
            c.check(prOff >= 0 && off.get(prOff).contains("OFF"), "the row does not read OFF after the press: " + off);
            // the saved file still uses the old key name
            c.check(text.contains("\"enabled\": false") && text.contains("\"specialGuess\""),
                    "the saved file does not use the old keys: " + text);
        } finally {
            c.onClient(mc -> {
                dev.testkit.compat.McCompat.setScreen(mc, null);
                return null;
            });
            DungeonB2Cases.restoreConfig(c, saved, PCFG);
        }
    }
}

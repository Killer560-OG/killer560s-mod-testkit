package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * killer560 (2026-10-08): "Chat keybinds and command shortcuts should be combined into just chat keybinds." (mod branch
 * chat-keybinds-merge). The rows themselves are 541-545 ({@link CmdShortcutsCases}, now driving the merged tab) and the
 * sending is 623-625 in the Hx session.
 * <ul>
 *   <li>621: the menu has ONE Chat Keybinds tab and no Command Shortcuts tab anywhere; it shows its master switch and four
 *       section headers in order (Key Binds, Catacombs / Master Mode, Kuudra, Custom Shortcuts); a bind row and a custom
 *       row each have ON/OFF, a key button (same column), their boxes and Delete, nothing overlaps or leaves the column;
 *       the master switch is the shortcuts' switch too (off hides every section); every row has a tooltip under the
 *       merged tab's scope and no {@code "command shortcuts/..."} key is left orphaned.</li>
 *   <li>622: migration. Old-format fixtures of BOTH files ({@code testkit-ui/chat-keybinds-merge/}) are written to the
 *       live config paths and the config reloaded: every bind, key, text, group toggle and custom shortcut survives, a
 *       half that was switched off keeps its rows off, the merged file holds everything, the old shortcuts file is
 *       renamed to {@code .migrated}, a second load changes nothing, an old shortcuts file reappearing (an old profile)
 *       is taken, legacy formats of each file still migrate, and a migrated custom shortcut registers live.</li>
 * </ul>
 */
final class ChatKeybindsMergeCases {

    private static final String KB = CmdShortcutsCases.KB;
    private static final String CFG = CmdShortcutsCases.CFG;
    private static final String GROUP = CmdShortcutsCases.GROUP;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<String> ORDER = List.of("Key Binds", "Catacombs / Master Mode", "Kuudra", "Custom Shortcuts");

    private ChatKeybindsMergeCases() {
    }

    // ---- 621 ------------------------------------------------------------------------------------------------------

    static void mergedTab(UiCase c) throws Exception {
        c.check(CmdShortcutsCases.hasMerge(), "this jar has no merged Chat Keybinds (CommandShortcutsConfig.setCustomKey)");
        // (a) the menu: one Chat Keybinds, no Command Shortcuts.
        List<String> paths = c.onClient(mc -> {
            try {
                R.setStatic(R.cls(ModScreenDriver.MOD_SCREEN), "tabs", null);
                ModScreenDriver d = new ModScreenDriver(mc);
                List<ModScreenDriver.Node> flat = new ArrayList<>();
                ModScreenDriver.flatten(d.tree(), flat);
                List<String> out = new ArrayList<>();
                for (ModScreenDriver.Node n : flat) {
                    out.add(n.path());
                }
                return out;
            } catch (Throwable t) {
                throw new AssertionError("could not open gui.ModScreen: " + UiCase.describe(t), t);
            }
        });
        List<String> chatKeybinds = new ArrayList<>();
        List<String> shortcuts = new ArrayList<>();
        for (String p : paths) {
            String leaf = p.substring(p.lastIndexOf('>') + 1).trim();
            if (leaf.equals("Chat Keybinds")) {
                chatKeybinds.add(p);
            }
            if (leaf.toLowerCase(java.util.Locale.ROOT).contains("command shortcut")) {
                shortcuts.add(p);
            }
        }
        c.check(paths.size() > 100, "only " + paths.size() + " menu nodes - the walk is broken");
        c.check(chatKeybinds.size() == 1, "Chat Keybinds tabs: " + chatKeybinds);
        c.check(shortcuts.isEmpty(), "a Command Shortcuts tab still exists: " + shortcuts);
        c.check(!Mod.has("gui.tab.CommandShortcutsTab"), "gui.tab.CommandShortcutsTab is still in the jar");

        CmdShortcutsCases.reset(c);
        c.onClient(mc -> {
            Object kb = Mod.cfg(KB);
            Mod.call(kb, "addBind");
            @SuppressWarnings("unchecked")
            List<Object> binds = (List<Object>) Mod.call(kb, "binds");
            CmdShortcutsCases.setBind(binds.get(0), 80, "/pets");
            Object cfg = Mod.cfg(CFG);
            Object row = Mod.call(cfg, "addCustom");
            Mod.call(cfg, "setCustomName", row, "k5merge");
            Mod.call(cfg, "setCustomCommand", row, "/warp dh");
            Mod.call(cfg, "setCustomKey", row, 327);
            Mod.call(kb, "save");
            return null;
        });
        ModScreenDriver d = CmdShortcutsCases.open(c);
        try {
            List<AbstractWidget> ws = CmdShortcutsCases.content(c, d);
            // (b) master + four headers in order.
            Map<String, Integer> headerY = new LinkedHashMap<>();
            AbstractWidget master = null;
            for (AbstractWidget w : ws) {
                String l = ModScreenDriver.label(w);
                if (w instanceof StringWidget && ORDER.contains(l)) {
                    headerY.put(l, w.getY());
                } else if (l.equals("Chat Keybinds: ON")) {
                    master = w;
                }
            }
            c.check(master != null, "no 'Chat Keybinds: ON' master row: " + CmdShortcutsCases.labels(ws));
            c.check(headerY.keySet().containsAll(ORDER), "section headers " + headerY.keySet() + ", want " + ORDER);
            for (int i = 1; i < ORDER.size(); i++) {
                Integer a = headerY.get(ORDER.get(i - 1));
                Integer b = headerY.get(ORDER.get(i));
                c.check(a != null && b != null && a < b, ORDER.get(i - 1) + " (y " + a + ") is not above "
                        + ORDER.get(i) + " (y " + b + ")");
            }
            // (c) the two kinds of row.
            AbstractWidget bindKey = null;
            AbstractWidget customKey = null;
            EditBox bindText = null;
            EditBox name = null;
            EditBox command = null;
            int ons = 0;
            int deletes = 0;
            for (AbstractWidget w : ws) {
                String l = ModScreenDriver.label(w);
                if (w instanceof EditBox e) {
                    String m = w.getMessage().getString();
                    if (m.equals("Command or message")) {
                        bindText = e;
                    } else if (m.equals("Shortcut Name")) {
                        name = e;
                    } else if (m.equals("Sends Command")) {
                        command = e;
                    }
                } else if (l.startsWith("Key: ")) {
                    if (bindKey == null) {
                        bindKey = w;
                    } else {
                        customKey = w;
                    }
                } else if (l.equals("ON")) {
                    ons++;
                } else if (l.equals("Delete")) {
                    deletes++;
                }
            }
            c.check(bindText != null && "/pets".equals(bindText.getValue()), "bind row text box: "
                    + (bindText == null ? "none" : bindText.getValue()));
            c.check(name != null && "k5merge".equals(name.getValue()) && command != null
                    && "/warp dh".equals(command.getValue()), "custom row boxes missing or wrong");
            c.check(bindKey != null && customKey != null, "key buttons: bind " + bindKey + ", custom " + customKey);
            c.check(ons == 2 && deletes == 2, ons + " ON and " + deletes + " Delete buttons (want 2 and 2)");
            if (bindKey != null && customKey != null) {
                c.check(bindKey.getY() < customKey.getY(), "the bind's key button is not above the custom row's");
                c.check(bindKey.getX() == customKey.getX() && bindKey.getWidth() == customKey.getWidth(),
                        "key column does not line up: bind x " + bindKey.getX() + " w " + bindKey.getWidth()
                                + ", custom x " + customKey.getX() + " w " + customKey.getWidth());
                c.note("key buttons '" + ModScreenDriver.label(bindKey) + "' / '" + ModScreenDriver.label(customKey)
                        + "' at x " + bindKey.getX() + " width " + bindKey.getWidth());
            }
            CmdShortcutsCases.overlaps(c, ws);
            if (master != null) {
                for (AbstractWidget w : ws) {
                    c.check(w.getX() >= master.getX() && w.getX() + w.getWidth() <= master.getX() + master.getWidth(),
                            "'" + ModScreenDriver.label(w) + "' leaves the content column");
                }
                if (command != null) {
                    c.check(command.getWidth() >= 60, "the custom row's command box is only " + command.getWidth()
                            + " wide (content " + master.getWidth() + ")");
                    c.note("content width " + master.getWidth() + ", command box " + command.getWidth()
                            + ", bind text box " + (bindText == null ? -1 : bindText.getWidth()));
                }
            }
            // (d) tooltips: scoped to the merged tab, none left on the old scope.
            List<String> missing = new ArrayList<>();
            int tips = 0;
            for (AbstractWidget w : ws) {
                String l = ModScreenDriver.label(w);
                if (w instanceof StringWidget && !ORDER.contains(l)) {
                    continue;
                }
                String label = w instanceof EditBox ? w.getMessage().getString() : l;
                String tip = c.onClient(mc -> {
                    try {
                        return (String) R.cls("gui.SettingTooltips").getMethod("describe", String.class,
                                AbstractWidget.class, String.class).invoke(null, null, w, label);
                    } catch (ReflectiveOperationException e) {
                        return "threw " + e;
                    }
                });
                if (tip == null || tip.isEmpty()) {
                    missing.add(label);
                } else {
                    tips++;
                }
            }
            // The headers "Catacombs / Master Mode" and "Kuudra" never had one; everything else must.
            missing.removeAll(List.of("Catacombs / Master Mode", "Kuudra"));
            c.check(missing.isEmpty(), "rows with no tooltip: " + missing);
            @SuppressWarnings("unchecked")
            Map<String, String> all = c.onClient(mc ->
                    new LinkedHashMap<>((Map<String, String>) R.getStatic(R.cls("gui.SettingTooltips"), "DESCRIPTIONS")));
            List<String> orphaned = new ArrayList<>();
            for (String k : all.keySet()) {
                if (k.startsWith("command shortcuts/")) {
                    orphaned.add(k);
                }
            }
            c.check(orphaned.isEmpty(), "tooltips still keyed to the removed tab: " + orphaned);
            String masterTip = master == null ? null : describe(c, master);
            c.check(masterTip != null && masterTip.contains("shortcuts") && masterTip.contains("key"),
                    "master tooltip: " + masterTip);

            // (e) one switch: off hides every section and turns the shortcuts off with it.
            CmdShortcutsCases.press(c, d, "Chat Keybinds: ON");
            boolean shortcutsOn = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(CFG), "isEnabledRaw"));
            c.check(!shortcutsOn, "the shortcuts' isEnabledRaw is still on with Chat Keybinds off");
            List<AbstractWidget> off = CmdShortcutsCases.content(c, d);
            c.check(off.size() == 1 && ModScreenDriver.label(off.get(0)).equals("Chat Keybinds: OFF"),
                    "with the switch off the tab shows " + CmdShortcutsCases.labels(off));
            CmdShortcutsCases.press(c, d, "Chat Keybinds: OFF");
            c.check(c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(CFG), "isEnabledRaw")), "switch back on did not reach the shortcuts");
            c.note("one Chat Keybinds tab (" + chatKeybinds + "), no Command Shortcuts tab; headers " + headerY + "; "
                    + tips + " rows with tooltips, no 'command shortcuts/' keys; master off hid everything and switched "
                    + "the shortcuts off");
        } finally {
            CmdShortcutsCases.close(c);
            CmdShortcutsCases.reset(c);
        }
    }

    private static String describe(UiCase c, AbstractWidget w) {
        return c.onClient(mc -> {
            try {
                return (String) R.cls("gui.SettingTooltips").getMethod("describe", String.class, AbstractWidget.class,
                        String.class).invoke(null, null, w, w.getMessage().getString());
            } catch (ReflectiveOperationException e) {
                return null;
            }
        });
    }

    // ---- 622 ------------------------------------------------------------------------------------------------------

    static void migration(UiCase c) throws Exception {
        c.check(CmdShortcutsCases.hasMerge(), "this jar has no merged Chat Keybinds (CommandShortcutsConfig.setCustomKey)");
        Path kbFile = c.onClient(mc -> (Path) R.getStatic(R.cls(KB), "CONFIG_PATH"));
        Path scFile = c.onClient(mc -> (Path) R.getStatic(R.cls(KB), "OLD_SHORTCUTS_PATH"));
        Path migrated = scFile.resolveSibling(scFile.getFileName() + ".migrated");
        c.check(kbFile.getFileName().toString().equals("killer560smod-commandkeybinds.json")
                && scFile.getFileName().toString().equals("killer560smod-commandshortcuts.json"),
                "unexpected config paths " + kbFile + " / " + scFile);
        String kbBackup = Files.exists(kbFile) ? Files.readString(kbFile, StandardCharsets.UTF_8) : null;
        List<String> done = new ArrayList<>();
        try {
            // A: both halves on.
            State a = migrate(c, kbFile, scFile, migrated, "old-commandkeybinds-on.json", "old-commandshortcuts-on.json");
            c.check(a.master, "A: switch off");
            c.check(a.binds.equals(List.of("80|/pets|on", "-102|gg|on", "-1|/warp dh|on")), "A: binds " + a.binds);
            c.check(a.groupsOff.equals(List.of("CATA_F3", "KUUDRA_HOT")), "A: groups off " + a.groupsOff);
            c.check(a.customs.equals(List.of("k5mig|/warp dh|on|-1", "k5off|is hub|off|-1")), "A: customs " + a.customs);
            c.check(!Files.exists(scFile), "A: the old shortcuts file is still there");
            c.check(Files.exists(migrated) && Files.readString(migrated, StandardCharsets.UTF_8)
                    .contains("k5mig"), "A: no .migrated copy of the old file");
            JsonObject merged = JsonParser.parseString(Files.readString(kbFile, StandardCharsets.UTF_8)).getAsJsonObject();
            c.check(merged.has("shortcuts") && merged.getAsJsonObject("shortcuts").has("group.cata_f3")
                    && !merged.getAsJsonObject("shortcuts").get("group.cata_f3").getAsBoolean()
                    && merged.getAsJsonObject("shortcuts").getAsJsonArray("custom").size() == 2
                    && merged.getAsJsonArray("binds").size() == 3, "A: merged file " + merged);
            // ...and it registers live, like any edit.
            CmdShortcutsCases.awaitRoot(c, "k5mig", true);
            c.check(!CmdShortcutsCases.canUse(c, "k5off"), "A: the switched-off k5off parses");
            done.add("A both on: " + a);

            // A again: a second load of the merged file changes nothing and rewrites no .migrated.
            String mergedText = Files.readString(kbFile, StandardCharsets.UTF_8);
            long migratedTime = Files.getLastModifiedTime(migrated).toMillis();
            c.onClient(mc -> Mod.staticCall(KB, "load"));
            State a2 = state(c);
            c.check(a2.toString().equals(a.toString()), "A reload: " + a2 + " vs " + a);
            c.check(Files.readString(kbFile, StandardCharsets.UTF_8).equals(mergedText), "A reload rewrote the file");
            c.check(Files.getLastModifiedTime(migrated).toMillis() == migratedTime, "A reload touched .migrated");
            done.add("A reload unchanged");

            // An old profile applied over a merged file: its shortcuts file is taken, the binds kept.
            write(scFile, "old-commandshortcuts-profile.json");
            c.onClient(mc -> Mod.staticCall(KB, "load"));
            State p = state(c);
            c.check(p.master && p.binds.equals(a.binds), "profile: switch/binds " + p);
            c.check(p.groupsOff.equals(List.of("CATA_F7")) && p.customs.equals(List.of("k5prof|/warp dh|on|-1")),
                    "profile: " + p);
            c.check(!Files.exists(scFile), "profile: the old shortcuts file is still there");
            done.add("old profile over merged: " + p);

            // B: keys were off, shortcuts on -> switch on, every bind off.
            State b = migrate(c, kbFile, scFile, migrated, "old-commandkeybinds-off.json", "old-commandshortcuts-on.json");
            c.check(b.master, "B: switch off");
            c.check(b.binds.equals(List.of("80|/pets|off", "-102|gg|off", "-1|/warp dh|off")), "B: binds " + b.binds);
            c.check(b.groupsOff.equals(List.of("CATA_F3", "KUUDRA_HOT")) && b.customs.equals(a.customs), "B: " + b);
            done.add("B keys off: " + b);

            // C: keys on, shortcuts off -> switch on, every shortcut off.
            State cc = migrate(c, kbFile, scFile, migrated, "old-commandkeybinds-on.json", "old-commandshortcuts-off.json");
            c.check(cc.master && cc.binds.equals(a.binds), "C: " + cc);
            c.check(cc.groupsOff.size() == 13, "C: groups off " + cc.groupsOff);
            c.check(cc.customs.equals(List.of("k5mig|/warp dh|off|-1", "k5off|is hub|off|-1")), "C: customs " + cc.customs);
            CmdShortcutsCases.awaitRoot(c, "k5mig", false);
            done.add("C shortcuts off: " + cc);

            // D: both off -> switch off, rows untouched.
            State dd = migrate(c, kbFile, scFile, migrated, "old-commandkeybinds-off.json", "old-commandshortcuts-off.json");
            c.check(!dd.master, "D: switch on");
            c.check(dd.binds.equals(a.binds) && dd.groupsOff.equals(a.groupsOff) && dd.customs.equals(a.customs),
                    "D: rows changed " + dd);
            done.add("D both off: " + dd);

            // E: the legacy formats of both files (8 fixed binds; one toggle per shortcut).
            State e = migrate(c, kbFile, scFile, migrated, "old-commandkeybinds-legacy.json",
                    "old-commandshortcuts-legacy.json");
            c.check(e.master && e.binds.equals(List.of("80|/pets|on", "82|/warp dungeon_hub|on")), "E: binds " + e);
            c.check(e.groupsOff.equals(List.of("CATA_F3", "KUUDRA_FIERY")) && e.customs.isEmpty(), "E: " + e);
            done.add("E legacy: " + e);

            // F: only the shortcuts file (Chat Keybinds never touched).
            State f = migrate(c, kbFile, scFile, migrated, null, "old-commandshortcuts-on.json");
            c.check(f.master && f.binds.isEmpty() && f.groupsOff.equals(a.groupsOff) && f.customs.equals(a.customs),
                    "F: " + f);
            done.add("F shortcuts only: " + f);

            // G: neither file -> defaults, and nothing written.
            State g = migrate(c, kbFile, scFile, migrated, null, null);
            c.check(g.master && g.binds.isEmpty() && g.groupsOff.isEmpty() && g.customs.isEmpty(), "G: " + g);
            c.check(!Files.exists(kbFile), "G: a fresh load wrote a file");
            done.add("G fresh: " + g);
            c.note(String.join("; ", done));
        } finally {
            Files.deleteIfExists(scFile);
            Files.deleteIfExists(migrated);
            if (kbBackup != null) {
                Files.writeString(kbFile, kbBackup, StandardCharsets.UTF_8);
            } else {
                Files.deleteIfExists(kbFile);
            }
            c.onClient(mc -> Mod.staticCall(KB, "load"));
            CmdShortcutsCases.reset(c);
        }
    }

    /** Clears both live files, writes the named fixtures (null for none) and reloads. */
    private static State migrate(UiCase c, Path kbFile, Path scFile, Path migrated, String kbFixture, String scFixture)
            throws Exception {
        Files.deleteIfExists(kbFile);
        Files.deleteIfExists(scFile);
        Files.deleteIfExists(migrated);
        if (kbFixture != null) {
            write(kbFile, kbFixture);
        }
        if (scFixture != null) {
            write(scFile, scFixture);
        }
        c.onClient(mc -> Mod.staticCall(KB, "load"));
        State s = state(c);
        if (scFixture != null) {
            c.check(!Files.exists(scFile) && Files.exists(migrated), scFixture + ": old file " + Files.exists(scFile)
                    + ", .migrated " + Files.exists(migrated));
        }
        if (kbFixture != null || scFixture != null) {
            String text = Files.readString(kbFile, StandardCharsets.UTF_8);
            c.check(text.contains("\"shortcuts\""), "merged file has no shortcuts section: " + text);
        }
        return s;
    }

    private static void write(Path file, String fixture) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(Deny.read("chat-keybinds-merge/" + fixture)), StandardCharsets.UTF_8);
    }

    /** The live config, flattened to strings: binds "key|text|on", groups that are off, customs "name|cmd|on|key". */
    record State(boolean master, List<String> binds, List<String> groupsOff, List<String> customs) {
    }

    @SuppressWarnings("unchecked")
    private static State state(UiCase c) {
        return c.onClient(mc -> {
            try {
                Object kb = Mod.cfg(KB);
                boolean master = (Boolean) Mod.call(kb, "isEnabledRaw");
                List<String> binds = new ArrayList<>();
                for (Object b : (List<Object>) Mod.call(kb, "binds")) {
                    binds.add(b.getClass().getField("key").getInt(b) + "|" + b.getClass().getField("text").get(b) + "|"
                            + (b.getClass().getField("enabled").getBoolean(b) ? "on" : "off"));
                }
                Object sc = Mod.cfg(CFG);
                List<String> off = new ArrayList<>();
                for (Object g : Mod.cls(GROUP).getEnumConstants()) {
                    if (!(Boolean) Mod.call(sc, "isGroupOn", g)) {
                        off.add(((Enum<?>) g).name());
                    }
                }
                List<String> customs = new ArrayList<>();
                for (Object r : (List<Object>) Mod.call(sc, "customs")) {
                    customs.add(Mod.call(r, "name") + "|" + Mod.call(r, "command") + "|"
                            + ((Boolean) Mod.call(r, "isEnabled") ? "on" : "off") + "|" + Mod.call(r, "key"));
                }
                return new State(master, binds, off, customs);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("reading the config: " + e, e);
            }
        });
    }
}

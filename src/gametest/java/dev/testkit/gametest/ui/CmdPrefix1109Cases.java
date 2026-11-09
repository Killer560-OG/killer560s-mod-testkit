package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.mod.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 1109-ui-cmdprefix-* (mod cmdprefix-1109, killer560 2026-11-09: "add a toggle for prefixes ... /k or /k5 or /k560 ... a .
 * or , or whatnot"). Names: each switch opens the menu and the full subcommand tree, an off name is unknown. Alternate
 * prefix: ".leaporder" runs the command and is not chat, ".hello", ". lol" and "..." are chat. Settings persist.
 */
final class CmdPrefix1109Cases {

    private static final String CFG = "commandprefix.CommandPrefixConfig";
    private static final String[] NAMES = {"killer560", "k", "k5", "k560"};

    private CmdPrefix1109Cases() {
    }

    private static void closeScreen(UiCase c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        c.ticks(2);
    }

    private static String screenName(UiCase c) {
        return c.onClient(mc -> McCompat.screen(mc) == null ? "none" : McCompat.screen(mc).getClass().getSimpleName());
    }

    private static void setName(UiCase c, String name, boolean on) {
        c.onClient(mc -> Mod.call(Mod.cfg(CFG), "setNameOn", name, on));
    }

    private static void say(UiCase c, String line) {
        c.onClient(mc -> {
            mc.player.connection.sendChat(line);
            return null;
        });
        c.ticks(10);
    }

    private static long chat(long mark, String needle) {
        return LogTap.since(mark).stream().filter(l -> l.contains("[CHAT]") && l.contains(needle)).count();
    }

    /** Runs {@code line} as a command; the screen it opened (closed again), or the error. */
    private static String run(UiCase c, String line) {
        String err = CommandSweep.execute(c, line);
        c.ticks(3);
        String s = screenName(c);
        closeScreen(c);
        return err != null ? err : s;
    }

    static void names(UiCase c) throws Exception {
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, CFG);
        try {
            for (Path p : saved.keySet()) {
                Files.deleteIfExists(p);
            }
            c.onClient(mc -> Mod.staticCall(CFG, "load"));
            for (String n : NAMES) {
                boolean on = (Boolean) c.onClient(mc -> Mod.call(Mod.cfg(CFG), "isNameOn", n));
                c.check(on == n.equals("killer560"), "/" + n + " default is " + on);
            }
            for (String n : NAMES) {
                for (String m : NAMES) {
                    setName(c, m, m.equals(n));
                }
                for (String m : NAMES) {
                    String bare = run(c, m);
                    String sub = run(c, m + " leaporder");
                    if (m.equals(n)) {
                        c.check(bare.equals("ModScreen"), "/" + m + " is on and opened " + bare);
                        c.check(sub.equals("LeapOrderScreen"), "/" + m + " leaporder is on and opened " + sub);
                    } else {
                        c.check(bare.startsWith("syntax:"), "/" + m + " is off beside /" + n + " and gave " + bare);
                        c.check(sub.startsWith("syntax:"), "/" + m + " leaporder is off and gave " + sub);
                    }
                }
            }
            // Legit GFS owns /k gfs: it keeps working while the name /k is off.
            for (String m : NAMES) {
                setName(c, m, false);
            }
            c.check(CommandSweep.execute(c, "k gfs pearl") == null, "/k gfs stopped working while /k is off");
            c.note("each of the four names alone opened the menu and /<name> leaporder; the other three were unknown");
        } finally {
            DungeonB2Cases.restoreConfig(c, saved, CFG);
        }
    }

    static void alt(UiCase c) throws Exception {
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, CFG);
        try {
            c.onClient(mc -> {
                Object cfg = Mod.cfg(CFG);
                Mod.call(cfg, "setAltChar", ".");
                Mod.call(cfg, "setAltOn", false);
                return null;
            });
            // Off: the same line is plain chat and runs nothing.
            long mark = LogTap.mark();
            say(c, ".leaporder");
            c.check(screenName(c).equals("none"), "alternate prefix OFF ran .leaporder: " + screenName(c));
            c.check(chat(mark, ".leaporder") >= 1, "alternate prefix OFF: .leaporder never reached chat");
            c.onClient(mc -> Mod.call(Mod.cfg(CFG), "setAltOn", true));
            for (String ch : new String[]{".", ",", "!", "-", "#", "~"}) {
                c.onClient(mc -> Mod.call(Mod.cfg(CFG), "setAltChar", ch));
                long m2 = LogTap.mark();
                say(c, ch + "leaporder");
                String s = screenName(c);
                closeScreen(c);
                c.check(s.equals("LeapOrderScreen"), ch + "leaporder opened " + s);
                c.check(chat(m2, ch + "leaporder") == 0, ch + "leaporder also went out as chat");
            }
            // ".k leaporder": a name works through the prefix with its subcommand, when that name is on, not when off.
            c.onClient(mc -> Mod.call(Mod.cfg(CFG), "setAltChar", "."));
            setName(c, "k", true);
            say(c, ".k leaporder");
            String viaK = screenName(c);
            closeScreen(c);
            c.check(viaK.equals("LeapOrderScreen"), ".k leaporder opened " + viaK);
            say(c, ".killer560");
            String viaMain = screenName(c);
            closeScreen(c);
            c.check(viaMain.equals("ModScreen"), ".killer560 opened " + viaMain);
            setName(c, "k", false);
            say(c, ".k leaporder");
            c.check(screenName(c).equals("none"), ".k leaporder opened a screen while /k is off: " + screenName(c));
            // With "." chosen, lines whose first word is no command root are chat, untouched.
            c.onClient(mc -> Mod.call(Mod.cfg(CFG), "setAltChar", "."));
            for (String line : new String[]{".hello", ". lol", "...", ".", "..leaporder", "hello .leaporder"}) {
                long m3 = LogTap.mark();
                say(c, line);
                c.check(screenName(c).equals("none"), "'" + line + "' opened " + screenName(c));
                c.check(chat(m3, line) >= 1, "'" + line + "' was swallowed, not sent as chat");
                closeScreen(c);
            }
            // The setter takes one non-space character that is not "/".
            for (String bad : new String[]{"", " ", "/", "ab"}) {
                boolean ok = (Boolean) c.onClient(mc -> Mod.call(Mod.cfg(CFG), "setAltChar", bad));
                c.check(!ok, "setAltChar('" + bad + "') was accepted");
            }
        } finally {
            DungeonB2Cases.restoreConfig(c, saved, CFG);
        }
    }

    static void persist(UiCase c) throws Exception {
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, CFG);
        try {
            c.onClient(mc -> {
                Object cfg = Mod.cfg(CFG);
                Mod.call(cfg, "setNameOn", "killer560", false);
                Mod.call(cfg, "setNameOn", "k5", true);
                Mod.call(cfg, "setAltOn", true);
                Mod.call(cfg, "setAltChar", ",");
                Mod.call(cfg, "save");
                Mod.staticCall(CFG, "load");
                return null;
            });
            List<Object> got = c.onClient(mc -> {
                Object cfg = Mod.cfg(CFG);
                return List.of(Mod.call(cfg, "isNameOn", "killer560"), Mod.call(cfg, "isNameOn", "k"),
                        Mod.call(cfg, "isNameOn", "k5"), Mod.call(cfg, "isNameOn", "k560"), Mod.call(cfg, "isAltOn"),
                        Mod.call(cfg, "altChar"));
            });
            c.check(got.equals(List.of(false, false, true, false, true, ",")), "after save and load: " + got);
            Path p = saved.keySet().iterator().next();
            String json = Files.readString(p);
            c.check(json.contains("\"k5\": true") && json.contains("\"altChar\": \",\""), "file: " + json);
        } finally {
            DungeonB2Cases.restoreConfig(c, saved, CFG);
        }
    }
}

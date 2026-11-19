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

    /** Sends one typed character to the game the way GLFW does (KeyboardHandler.charTyped, private on 26.2). */
    private static void typeChar(UiCase c, int cp) {
        c.onClient(mc -> {
            try {
                java.lang.reflect.Method m = mc.keyboardHandler.getClass().getDeclaredMethod("charTyped", long.class,
                        net.minecraft.client.input.CharacterEvent.class);
                m.setAccessible(true);
                m.invoke(mc.keyboardHandler, mc.getWindow().handle(), new net.minecraft.client.input.CharacterEvent(cp));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
        c.ticks(3);
    }

    /** The open chat screen's input text, or "(" + screen name + ")" when no chat screen is open. */
    private static String chatInput(UiCase c) {
        return c.onClient(mc -> {
            if (!(McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.ChatScreen cs)) {
                return "(" + (McCompat.screen(mc) == null ? "none" : McCompat.screen(mc).getClass().getSimpleName()) + ")";
            }
            try {
                java.lang.reflect.Field f = net.minecraft.client.gui.screens.ChatScreen.class.getDeclaredField("input");
                f.setAccessible(true);
                net.minecraft.client.gui.components.EditBox box = (net.minecraft.client.gui.components.EditBox) f.get(cs);
                return box.getValue() + "|" + box.getCursorPosition();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** 1118-ui-prefixopen: Prefix Key Opens Chat. A typed prefix character with no screen open opens the chat with it typed once. */
    static void prefixOpen(UiCase c) throws Exception {
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, CFG);
        try {
            for (Path p : saved.keySet()) {
                Files.deleteIfExists(p);
            }
            c.onClient(mc -> Mod.staticCall(CFG, "load"));
            c.check((Boolean) c.onClient(mc -> Mod.call(Mod.cfg(CFG), "isOpensChat")), "Prefix Key Opens Chat is not ON by default");
            c.onClient(mc -> {
                Object cfg = Mod.cfg(CFG);
                Mod.call(cfg, "setAltOn", true);
                Mod.call(cfg, "setAltChar", ".");
                return null;
            });
            closeScreen(c);
            typeChar(c, '.');
            c.check(chatInput(c).equals(".|1"), "'.' with no screen gave " + chatInput(c) + ", wanted '.' once with the cursor after it");
            // Typed on, the line is the same one the existing path runs: ".leaporder" opens the Leap Order screen.
            c.onClient(mc -> {
                ((net.minecraft.client.gui.screens.ChatScreen) McCompat.screen(mc)).handleChatInput(".leaporder", false);
                return null;
            });
            c.ticks(5);
            String ran = screenName(c);
            closeScreen(c);
            c.check(ran.equals("LeapOrderScreen"), "the opened chat ran .leaporder as " + ran);
            // A shifted symbol: "!" is Shift+1 on a US layout, the game just receives the typed character.
            c.onClient(mc -> Mod.call(Mod.cfg(CFG), "setAltChar", "!"));
            typeChar(c, '!');
            c.check(chatInput(c).equals("!|1"), "'!' gave " + chatInput(c));
            closeScreen(c);
            // A screen already open: the hook does nothing (the same chat screen stays, no second one).
            typeChar(c, '!');
            Object first = c.onClient(mc -> McCompat.screen(mc));
            typeChar(c, '!');
            c.check(c.onClient(mc -> McCompat.screen(mc)) == first, "a '!' with a chat screen open replaced the screen");
            closeScreen(c);
            // Other characters, then OFF states, then a letter prefix and a prefix of its own character set.
            typeChar(c, 'a');
            c.check(screenName(c).equals("none"), "a character that is not the prefix opened " + screenName(c));
            c.onClient(mc -> Mod.call(Mod.cfg(CFG), "setOpensChat", false));
            typeChar(c, '!');
            c.check(screenName(c).equals("none"), "row OFF still opened " + screenName(c));
            c.onClient(mc -> {
                Object cfg = Mod.cfg(CFG);
                Mod.call(cfg, "setOpensChat", true);
                Mod.call(cfg, "setAltOn", false);
                return null;
            });
            typeChar(c, '!');
            c.check(screenName(c).equals("none"), "Alternate Prefix OFF still opened " + screenName(c));
            for (String ch : new String[]{"f", "7"}) {
                c.onClient(mc -> {
                    Object cfg = Mod.cfg(CFG);
                    Mod.call(cfg, "setAltOn", true);
                    Mod.call(cfg, "setAltChar", ch);
                    return null;
                });
                typeChar(c, ch.charAt(0));
                c.check(screenName(c).equals("none"), "letter/digit prefix '" + ch + "' opened " + screenName(c));
            }
            // Persisted.
            c.onClient(mc -> {
                Object cfg = Mod.cfg(CFG);
                Mod.call(cfg, "setOpensChat", false);
                Mod.call(cfg, "save");
                Mod.staticCall(CFG, "load");
                return null;
            });
            c.check(!(Boolean) c.onClient(mc -> Mod.call(Mod.cfg(CFG), "isOpensChat")), "Prefix Key Opens Chat OFF did not survive save and load");
            c.note("'.' and '!' typed with no screen opened the chat with the prefix once; screen open, row OFF, feature OFF, letter and digit did nothing");
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

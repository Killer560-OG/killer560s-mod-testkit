package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * killer560's 2026-11-01 command removals (mod cmdclean-1101): "remove /killer560 class, /killer560 ew ..., mapdump,
 * /killer560 profile, /killer560 sim, /class, /log (keep /runlog), /k560search (keep /search), /lag stutters, the textures
 * thing, auto clear and secret commands, blink, freezestate and rewind, /test".
 * <ul>
 *   <li>1101-cmdclean-gone: none of those roots or /killer560 subcommands is in the client dispatcher; /runlog, /search,
 *       /ew, /leaporder and /killer560 (with bugreport and leaporder) still are.</li>
 *   <li>1101-cmdclean-reach: every feature a removed command reached still works without it. Both jars: the Profiles tab
 *       saves a profile and offers Load, Export and Delete for it, and /runlog opens the run log. Cheat jars: the Auto
 *       Clear tab has the Clear Toggle Key and a Bat Test button whose press reaches the bat test (it refuses outside a
 *       dungeon, logged); the Insta Clear rows print the evidence and answer a mark, and Known After saves and reloads;
 *       the Freeze State key action freezes and resumes the player; the Blink Hold key holds and releases.</li>
 * </ul>
 */
final class CmdClean1101Cases {

    private static final List<String> GONE_ROOTS = List.of("class", "log", "k560search", "lag", "k560textures", "autoclear",
            "autosecret", "blink", "freezestate", "rewind", "test");
    private static final List<String> GONE_SUBS = List.of("class", "ew", "mapdump", "profile", "sim");

    private CmdClean1101Cases() {
    }

    // ---- 1101-cmdclean-gone ----------------------------------------------------------------------------------------------

    static void gone(UiCase c) {
        List<String> roots = children(c, null);
        List<String> sub = children(c, "killer560");
        java.util.Collections.sort(roots);
        java.util.Collections.sort(sub);
        c.note(roots.size() + " roots (" + (Mod.isCheat() ? "cheat" : "legit") + "): " + String.join(" ", roots));
        c.note("/killer560 subcommands: " + String.join(" ", sub));
        c.check(roots.size() > 30, "only " + roots.size() + " roots - the dispatcher was not read");
        for (String g : GONE_ROOTS) {
            c.check(!roots.contains(g), "/" + g + " is still registered");
        }
        for (String g : GONE_SUBS) {
            c.check(!sub.contains(g), "/killer560 " + g + " is still registered");
        }
        for (String k : List.of("runlog", "search", "ew", "leaporder", "killer560")) {
            c.check(roots.contains(k), "/" + k + " is gone");
        }
        for (String k : List.of("bugreport", "leaporder")) {
            c.check(sub.contains(k), "/killer560 " + k + " is gone");
        }
    }

    // ---- 1101-cmdclean-reach ---------------------------------------------------------------------------------------------

    static void reach(UiCase c) throws Exception {
        profiles(c);
        runLog(c);
        if (!Mod.isCheat()) {
            c.check(!Mod.has("autoclear.BatTest") || !children(c, null).contains("test"), "legit jar has /test");
            c.note("legit jar: Auto Clear, Insta Clear, Freeze State and Blink are cheat-only; nothing more to reach");
            return;
        }
        autoClearTab(c);
        freezeState(c);
        blink(c);
    }

    /** /killer560 profile: the Profiles tab saves, and lists Load / Export / Delete for the saved profile. */
    private static void profiles(UiCase c) {
        String name = "cmdclean1101";
        Object saved = c.onClient(mc -> Mod.call(Mod.staticCall("profiles.ProfileManager", "saveCurrentAsProfile", name), "success"));
        try {
            List<String> rows = c.onClient(mc -> labels(build("gui.tab.ProfilesTab")));
            String active = c.onClient(mc -> (String) Mod.staticCall("profiles.ProfileManager", "getActiveProfile"));
            c.note("save -> " + saved + " (active " + active + "); Profiles tab rows: " + rows);
            c.check(Boolean.TRUE.equals(saved), "saving a profile failed");
            List<String> wants = new ArrayList<>(List.of("Save Current As New", "New Blank Profile",
                    "Import Profile (Open Folder)", "Export", "Delete"));
            if (!name.equals(active)) {
                wants.add("Load"); // the active profile's card has no Load
            }
            for (String want : wants) {
                c.check(rows.contains(want), "the Profiles tab has no \"" + want + "\" row: " + rows);
            }
        } finally {
            c.onClient(mc -> Mod.staticCall("profiles.ProfileManager", "deleteProfile", name));
        }
    }

    /** /log: /runlog still opens the run log. */
    private static void runLog(UiCase c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        c.ticks(2);
        c.onClient(mc -> {
            mc.player.connection.sendCommand("runlog");
            return null;
        });
        c.ticks(6);
        String screen = c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            return s == null ? "none" : s.getClass().getSimpleName();
        });
        c.note("/runlog -> " + screen);
        c.check("RunLogScreen".equals(screen), "/runlog opened " + screen);
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
    }

    /** /autoclear, /test, /autosecret: the Auto Clear tab's rows. */
    private static void autoClearTab(UiCase c) {
        String cfg = "autoclear.AutoClearConfig";
        boolean was = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(cfg), "isEnabledRaw"));
        int threshold = c.onClient(mc -> (Integer) Mod.staticCall("autosecret.InstaClearTracker", "minSuccesses"));
        try {
            c.onClient(mc -> {
                Mod.set(cfg, "setEnabled", true);
                return null;
            });
            List<AbstractWidget> ws = c.onClient(mc -> build("gui.tab.AutoClearTab"));
            List<String> rows = c.onClient(mc -> labels(ws));
            c.note("Auto Clear tab rows: " + rows);
            c.check(rows.stream().anyMatch(r -> r.startsWith("Clear Toggle Key:")), "no Clear Toggle Key row (starts Auto Clear)");
            for (String want : List.of("Insta Clear Evidence", "Last Insta Clear Entry", "Mark Last Insta",
                    "Mark Last Not Insta", "Mark Last Ignore", "Mark Last Measured")) {
                c.check(rows.contains(want), "no \"" + want + "\" row");
            }
            c.check(rows.stream().anyMatch(r -> r.startsWith("Known After:")), "no Known After slider");
            c.check(rows.stream().anyMatch(r -> r.startsWith("Clear Window:")), "no Clear Window slider");

            // Bat Test: the press reaches BatTest.start, which refuses here (a flat world is no dungeon) and says why.
            long mark = LogTap.mark();
            boolean clicked = click(c, find(ws, "Bat Test:"));
            c.ticks(3);
            boolean running = c.onClient(mc -> (Boolean) Mod.staticCall("autoclear.BatTest", "isRunning"));
            List<String> bat = grep(LogTap.since(mark), "[BatTest] refused");
            c.note("Bat Test press: clicked " + clicked + ", running " + running + ", log " + bat);
            c.check(clicked, "the Bat Test row took no click");
            c.check(!bat.isEmpty(), "the Bat Test press never reached the bat test (no [BatTest] refused line)");
            c.check(!running, "the bat test started outside a dungeon");

            // Insta Clear: the evidence and a mark answer in chat.
            mark = LogTap.mark();
            click(c, find(ws, "Insta Clear Evidence"));
            click(c, find(ws, "Mark Last Insta"));
            c.ticks(3);
            List<String> log = LogTap.since(mark);
            List<String> evidence = grep(log, "entr(ies) recorded");
            List<String> marked = new ArrayList<>(grep(log, "to mark"));
            marked.addAll(grep(log, "Marked the last entry"));
            c.note("Insta Clear Evidence -> " + evidence + "; Mark Last Insta -> " + marked);
            c.check(!evidence.isEmpty(), "Insta Clear Evidence printed nothing");
            c.check(!marked.isEmpty(), "Mark Last Insta printed nothing");

            // Known After: what the slider applies, saved and read back as a restart would.
            int back = c.onClient(mc -> {
                Mod.staticCall("autosecret.InstaClearTracker", "setMinSuccesses", threshold == 7 ? 6 : 7);
                Mod.staticCall("autosecret.InstaClearTracker", "saveSettings");
                Mod.staticCall("autosecret.InstaClearTracker", "testReload");
                return (Integer) Mod.staticCall("autosecret.InstaClearTracker", "minSuccesses");
            });
            c.note("Known After " + threshold + " -> " + back + " after save and reload");
            c.check(back == (threshold == 7 ? 6 : 7), "Known After did not survive a reload: " + back);
        } finally {
            c.onClient(mc -> {
                Mod.staticCall("autosecret.InstaClearTracker", "testSetMinSuccesses", threshold);
                Mod.set(cfg, "setEnabled", was);
                if ((Boolean) Mod.staticCall("autoclear.BatTest", "isRunning")) {
                    Mod.staticCall("autoclear.BatTest", "toggleFromMenu");
                }
                return null;
            });
        }
    }

    /** /freezestate, /rewind: the Freeze State key action (what Ap3Keybinds runs) freezes and resumes; the tab's key rows
     *  no longer name a command. */
    private static void freezeState(UiCase c) {
        String cfg = "ap3.Ap3Config";
        boolean was = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(cfg), "isFreezeStateEnabled"));
        try {
            c.onClient(mc -> {
                Mod.set(cfg, "setFreezeStateEnabled", true);
                return null;
            });
            List<String> rows = c.onClient(mc -> labels(build("gui.tab.FreezeStateTab")));
            c.note("Freeze State tab rows: " + rows);
            for (String key : List.of("Freeze State Key:", "Rewind 1 Tick Key:", "Forward 1 Tick Key:")) {
                String row = rows.stream().filter(r -> r.startsWith(key)).findFirst().orElse(null);
                c.check(row != null, "no \"" + key + "\" row");
                c.check(row == null || !row.contains("/"), "the key row still names a command: " + row);
            }
            Object action = Mod.enumValue("ap3.Ap3Commands$Action", "FREEZE_STATE");
            c.onClient(mc -> Mod.call(action, "run"));
            c.ticks(2);
            boolean frozen = c.onClient(mc -> (Boolean) Mod.staticCall("ap3.Ap3FreezeState", "isFrozen"));
            c.onClient(mc -> Mod.call(action, "run"));
            c.ticks(2);
            boolean resumed = !c.onClient(mc -> (Boolean) Mod.staticCall("ap3.Ap3FreezeState", "isFrozen"));
            c.note("Freeze State key action: frozen " + frozen + ", resumed " + resumed);
            c.check(frozen, "the Freeze State key action did not freeze");
            c.check(resumed, "the Freeze State key action did not resume");
        } finally {
            c.onClient(mc -> {
                if ((Boolean) Mod.staticCall("ap3.Ap3FreezeState", "isFrozen")) {
                    Mod.call(Mod.enumValue("ap3.Ap3Commands$Action", "FREEZE_STATE"), "run");
                }
                Mod.set(cfg, "setFreezeStateEnabled", was);
                return null;
            });
        }
    }

    /** /blink: the Hold key holds while down and releases when it comes up. */
    private static void blink(UiCase c) {
        String cfg = "blink.BlinkConfig";
        boolean[] was = c.onClient(mc -> new boolean[]{(Boolean) Mod.call(Mod.cfg(cfg), "isEnabled"),
                (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled")});
        try {
            c.onClient(mc -> {
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                Mod.set(cfg, "setEnabled", true);
                Mod.setField("blink.BlinkFeature", "keyHeldForTest", Boolean.TRUE);
                return null;
            });
            c.ticks(4);
            boolean held = c.onClient(mc -> (Boolean) Mod.staticCall("blink.BlinkFeature", "isHolding"));
            c.onClient(mc -> {
                Mod.setField("blink.BlinkFeature", "keyHeldForTest", Boolean.FALSE);
                return null;
            });
            c.ticks(4);
            boolean released = !c.onClient(mc -> (Boolean) Mod.staticCall("blink.BlinkFeature", "isHolding"));
            c.note("Blink Hold key: held " + held + ", released " + released);
            c.check(held, "the Hold key did not start a hold");
            c.check(released, "the hold did not end when the key came up");
        } finally {
            c.onClient(mc -> {
                Mod.setField("blink.BlinkFeature", "keyHeldForTest", null);
                Mod.staticCall("blink.BlinkFeature", "end", "test cleanup");
                Mod.set(cfg, "setEnabled", was[0]);
                Mod.staticCall("util.SkyblockGate", "setEnabled", was[1]);
                return null;
            });
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------

    private static List<String> children(UiCase c, String under) {
        return c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            var d = net.fabricmc.fabric.impl.command.client.ClientCommandInternals.getActiveDispatcher();
            var node = d == null ? null : under == null ? d.getRoot() : d.getRoot().getChild(under);
            if (node != null) {
                for (var n : node.getChildren()) {
                    out.add(n.getName());
                }
            }
            return out;
        });
    }

    @SuppressWarnings("unchecked")
    private static List<AbstractWidget> build(String tabCls) {
        try {
            Object t = Mod.cls(tabCls).getConstructor().newInstance();
            return (List<AbstractWidget>) Mod.call(t, "buildWidgets", 0, 0, 300, (Runnable) () -> { });
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static List<String> labels(List<AbstractWidget> ws) {
        List<String> out = new ArrayList<>();
        for (AbstractWidget w : ws) {
            out.add(ModScreenDriver.label(w));
        }
        return out;
    }

    private static AbstractWidget find(List<AbstractWidget> ws, String prefix) {
        for (AbstractWidget w : ws) {
            if (ModScreenDriver.label(w).startsWith(prefix)) {
                return w;
            }
        }
        throw new AssertionError("no row starting \"" + prefix + "\"");
    }

    private static boolean click(UiCase c, AbstractWidget w) {
        return c.onClient(mc -> {
            double x = w.getX() + w.getWidth() / 2.0;
            double y = w.getY() + w.getHeight() / 2.0;
            boolean took = w.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0)), false);
            w.mouseReleased(new MouseButtonEvent(x, y, new MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0)));
            return took;
        });
    }

    private static List<String> grep(List<String> lines, String needle) {
        List<String> out = new ArrayList<>();
        for (String l : lines) {
            if (l.contains(needle)) {
                out.add(l);
            }
        }
        return out;
    }
}

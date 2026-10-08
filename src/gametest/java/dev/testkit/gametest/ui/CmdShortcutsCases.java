package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.tree.CommandNode;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * killer560's 2026-10-07 Command Shortcuts requests, against the real tab in the mod menu (mod branch cmd-shortcuts):
 * <ul>
 *   <li>541: "Remove all that extra text next to f0 f1 f2 command shortcuts and the text explaining them below it." Every
 *       built-in row is one full-width button reading just its name and ON/OFF; the only text widgets left are the three
 *       section headers (and the Custom section's status line, empty); rows are evenly spaced and no two widgets
 *       intersect; each row's hover tooltip names the {@code /joininstance} line(s) it sends. Pictures at GUI 2.</li>
 *   <li>542: "There should be a custom section on command shortcuts." Add / edit / rename / switch off / delete a custom
 *       shortcut through the tab's own widgets; each change reaches the live client dispatcher (no rejoin) and the tab
 *       completion tree; the row survives {@code CommandShortcutsConfig.load()} and is in the JSON file.</li>
 *   <li>543: a name that shadows a root is refused in the tab, with the reason shown and the box red: built-in (any
 *       case), another of his own, a Fabric/mod client root, a vanilla server root, an illegal or over-long name.</li>
 *   <li>545: the 306 command sweep, run again with one live and one switched-off custom root in the dispatcher, still
 *       passes - custom roots are his data, which {@link CommandSweep} leaves out of the deny-list coverage.</li>
 * </ul>
 * Sending is 544, in the Hx session (the testkit server records what the client sends).
 */
final class CmdShortcutsCases {

    static final String TAB = "Command Shortcuts";
    static final String FEAT = "commandshortcuts.CommandShortcutsFeature";
    static final String CFG = "commandshortcuts.CommandShortcutsConfig";
    static final String GROUP = "commandshortcuts.CommandShortcutsFeature$Group";
    static final Set<String> HEADERS = Set.of("Catacombs / Master Mode", "Kuudra", "Custom");
    private static final int REFUSED = 0xFFFF5555;
    private static final int TEXT = 0xFFE0E0E0;

    private CmdShortcutsCases() {
    }

    // ---- 541 ------------------------------------------------------------------------------------------------------

    static void builtinRows(UiCase c) throws Exception {
        reset(c);
        // Pictures first: on a jar from before the change the checks below fail, and the "before" set is still wanted.
        shots(c, hasCustomNow(c) ? "after" : "before");
        ModScreenDriver d = open(c);
        try {
            List<AbstractWidget> ws = content(c, d);
            String master = null;
            AbstractWidget masterW = null;
            List<AbstractWidget> groupButtons = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            List<String> groupLabels = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                for (Object g : Mod.cls(GROUP).getEnumConstants()) {
                    out.add((String) Mod.field(g, "label"));
                }
                return out;
            });
            for (AbstractWidget w : ws) {
                String l = ModScreenDriver.label(w);
                if (w instanceof StringWidget) {
                    texts.add(l);
                } else if (l.startsWith("Command Shortcuts: ")) {
                    master = l;
                    masterW = w;
                } else {
                    for (String g : groupLabels) {
                        if (l.equals(g + ": ON") || l.equals(g + ": OFF")) {
                            groupButtons.add(w);
                        }
                    }
                }
            }
            c.check(masterW != null, "no 'Command Shortcuts: ON' master row; rows: " + labels(ws));
            c.check(groupButtons.size() == groupLabels.size(), groupButtons.size() + " built-in rows for "
                    + groupLabels.size() + " groups " + groupLabels + "; rows: " + labels(ws));
            // The removed text: nothing but the section headers and the (empty) Custom status line.
            List<String> extra = new ArrayList<>();
            for (String t : texts) {
                if (!HEADERS.contains(t) && !t.isEmpty()) {
                    extra.add(t);
                }
            }
            c.check(extra.isEmpty(), "text still beside/under the built-in rows: " + extra);
            for (AbstractWidget w : ws) {
                String l = ModScreenDriver.label(w);
                c.check(!l.contains("joininstance") && !l.contains("catacombs_floor") && !l.contains("kuudra_")
                        && !l.contains("Takes effect") && !l.contains("re-sends"), "a row still reads '" + l + "'");
            }
            // Layout: every built-in row full width (the master row's x and width), rows evenly spaced, no overlap.
            int x0 = masterW.getX();
            int w0 = masterW.getWidth();
            groupButtons.sort(Comparator.comparingInt(AbstractWidget::getY));
            for (AbstractWidget b : groupButtons) {
                c.check(b.getX() == x0 && b.getWidth() == w0, "'" + ModScreenDriver.label(b) + "' at x " + b.getX()
                        + " width " + b.getWidth() + ", the master row is x " + x0 + " width " + w0);
            }
            List<Integer> steps = new ArrayList<>();
            for (int i = 1; i < groupButtons.size(); i++) {
                steps.add(groupButtons.get(i).getY() - groupButtons.get(i - 1).getY());
            }
            // Catacombs F0..F7 then Kuudra: one larger step where the Kuudra header sits, all others equal.
            int cata = 8;
            for (int i = 0; i < steps.size(); i++) {
                if (i == cata - 1) {
                    continue;
                }
                c.check(steps.get(i) == 21, "row step " + i + " is " + steps.get(i) + " (expected 21): " + steps);
            }
            c.check(steps.size() > cata - 1 && steps.get(cata - 1) == 21 + 6 + 16,
                    "Catacombs -> Kuudra step " + (steps.size() > cata - 1 ? steps.get(cata - 1) : -1)
                            + " (expected 43 = row + gap + header)");
            overlaps(c, ws);
            for (AbstractWidget w : ws) {
                c.check(w.getX() >= x0 && w.getX() + w.getWidth() <= x0 + w0, "'" + ModScreenDriver.label(w)
                        + "' leaves the content column: x " + w.getX() + "+" + w.getWidth());
            }
            // The expansion lives on in the tooltip only.
            int tips = c.onClient(mc -> {
                int n = 0;
                try {
                    java.lang.reflect.Method describe = R.cls("gui.SettingTooltips").getMethod("describe", String.class,
                            AbstractWidget.class, String.class);
                    for (AbstractWidget b : groupButtons) {
                        String l = ModScreenDriver.label(b);
                        String group = l.substring(0, l.indexOf(':'));
                        String tip = (String) describe.invoke(null, null, b, b.getMessage().getString());
                        Object g = null;
                        for (Object o : Mod.cls(GROUP).getEnumConstants()) {
                            if (group.equals(Mod.field(o, "label"))) {
                                g = o;
                            }
                        }
                        @SuppressWarnings("unchecked")
                        List<Object> members = (List<Object>) Mod.call(g, "members");
                        for (Object m : members) {
                            String sends = (String) Mod.call(m, "expandsTo");
                            if (tip == null || !tip.contains(sends)) {
                                c.problem("tooltip of '" + l + "' does not name " + sends + ": " + tip);
                            } else {
                                n++;
                            }
                        }
                    }
                } catch (ReflectiveOperationException e) {
                    c.problem("tooltip lookup threw " + UiCase.describe(e));
                }
                return n;
            });
            c.check(tips == 20, tips + " of 20 built-in shortcuts named in their row's tooltip");
            c.note("master " + master + "; " + groupButtons.size() + " built-in rows at x " + x0 + " width " + w0
                    + ", steps " + steps + "; text widgets " + texts + "; " + tips + " expansions in tooltips");
        } finally {
            close(c);
        }
    }

    /**
     * Pictures of the tab at GUI 2 (1920x1080 window), every scroll position. {@code label} names them; the same case
     * run on a jar from before the change gives the "before" set.
     */
    static void shots(UiCase c, String label) throws Exception {
        int[] window = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            c.ctx().getInput().resizeWindow(1920, 1080);
            c.ticks(3);
            c.onClient(mc -> {
                mc.options.guiScale().set(2);
                mc.resizeGui();
                return null;
            });
            c.ticks(3);
            if (hasCustomNow(c)) {
                // One custom row in the picture, so the Custom section is seen with content.
                c.onClient(mc -> {
                    Object cfg = Mod.cfg(CFG);
                    Object row = Mod.call(cfg, "addCustom");
                    Mod.call(cfg, "setCustomName", row, "k5warp");
                    Mod.call(cfg, "setCustomCommand", row, "/warp dh");
                    return null;
                });
            }
            ModScreenDriver d = open(c);
            int[] scroll = c.onClient(mc -> new int[]{d.maxScroll(), d.visibleContentHeight()});
            int step = Math.max(40, scroll[1] - 30);
            int n = 0;
            Path shots = Report.dir().resolve("cmd-shortcuts-shots");
            Files.createDirectories(shots);
            for (int off = 0; ; off += step) {
                int at = Math.min(off, scroll[0]);
                c.onClient(mc -> {
                    d.scrollTo(at);
                    rebuild(d);
                    return null;
                });
                c.ticks(4);
                String name = c.name() + "-" + label + "-gui2-" + (++n);
                Path taken = c.ctx().takeScreenshot(Report.fileName(name));
                Path kept = Report.screenshot(name, taken);
                Path src = kept != null ? kept : taken;
                Path copy = shots.resolve(name + ".png");
                Files.copy(src, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                c.note("picture " + name + " (scroll " + at + " of " + scroll[0] + ") -> " + copy.toAbsolutePath());
                if (at >= scroll[0] || n >= 6) {
                    break;
                }
            }
        } finally {
            close(c);
            if (hasCustomNow(c)) {
                reset(c);
            }
            c.onClient(mc -> {
                mc.options.guiScale().set(gui);
                return null;
            });
            c.ctx().getInput().resizeWindow(window[0], window[1]);
            c.ticks(5);
            c.onClient(mc -> {
                mc.resizeGui();
                return null;
            });
        }
    }

    // ---- 542 ------------------------------------------------------------------------------------------------------

    static void customEdit(UiCase c) throws Exception {
        requireCustom(c);
        reset(c);
        ModScreenDriver d = open(c);
        try {
            press(c, d, "+ Add Shortcut");
            c.check(customs(c).size() == 1, "+ Add Shortcut made " + customs(c).size() + " row(s)");
            setBox(c, d, "Shortcut Name", 0, "k5edit");
            setBox(c, d, "Sends Command", 0, "/warp dh");
            Object row = customs(c).get(0);
            c.check("k5edit".equals(name(c, row)) && "/warp dh".equals(command(c, row)),
                    "the row saved name '" + name(c, row) + "' command '" + command(c, row) + "'");
            awaitRoot(c, "k5edit", true);
            c.check(c.onClient(mc -> mc.getConnection().getCommands().getRoot().getChild("k5edit") != null),
                    "/k5edit is not in the tab-completion tree");
            String parse = parseError(c, "k5edit");
            c.check(parse == null, "/k5edit does not parse to a command: " + parse);

            // Rename: the old word stops at once, the new one registers once typing stops.
            setBox(c, d, "Shortcut Name", 0, "k5renamed");
            c.check(!canUse(c, "k5edit"), "/k5edit still parses after the rename");
            awaitRoot(c, "k5renamed", true);

            // Command edit, no slash.
            setBox(c, d, "Sends Command", 0, "is hub");
            c.check("is hub".equals(command(c, customs(c).get(0))), "command edit not saved");

            // Switch off and back on with the row's own button.
            press(c, d, "ON");
            Object offRow = customs(c).get(0);
            c.check(!(Boolean) c.onClient(mc -> Mod.call(offRow, "isEnabled")), "OFF did not save");
            c.check(!canUse(c, "k5renamed"), "/k5renamed still parses while switched off");
            press(c, d, "OFF");
            c.check(canUse(c, "k5renamed"), "/k5renamed does not parse after switching back on");

            // Persist: file, then a reload of the config.
            Path file = c.onClient(mc -> (Path) R.getStatic(R.cls(CFG), "CONFIG_PATH"));
            String json = Files.readString(file, StandardCharsets.UTF_8);
            c.check(json.contains("\"custom\"") && json.contains("k5renamed") && json.contains("is hub"),
                    "the config file does not hold the row: " + json);
            press(c, d, "ON");   // saved switched off, to see the flag survive too
            c.onClient(mc -> Mod.staticCall(CFG, "load"));
            List<Object> after = customs(c);
            c.check(after.size() == 1 && "k5renamed".equals(name(c, after.get(0)))
                            && "is hub".equals(command(c, after.get(0)))
                            && !(Boolean) c.onClient(mc -> Mod.call(after.get(0), "isEnabled")),
                    "after load(): " + describe(c, after));
            Object reloaded = customs(c).get(0);
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setCustomEnabled", reloaded, true);
                return null;
            });
            c.onClient(mc -> {
                rebuild(d);
                return null;
            });
            c.check(canUse(c, "k5renamed"), "/k5renamed does not parse after reload + on");

            // Delete through the row.
            press(c, d, "Delete");
            c.check(customs(c).isEmpty(), "Delete left " + describe(c, customs(c)));
            c.check(!canUse(c, "k5renamed"), "/k5renamed still parses after Delete");
            c.check(parseError(c, "k5renamed") != null, "/k5renamed still parses to a command after Delete");
            c.onClient(mc -> Mod.staticCall(CFG, "load"));
            c.check(customs(c).isEmpty(), "the deleted row came back on load(): " + describe(c, customs(c)));
            c.note("added k5edit -> /warp dh (registered live, in the completion tree), renamed to k5renamed (old word "
                    + "refused at once, new one live), command 'is hub', OFF/ON, saved + load() kept it switched off, Delete");
        } finally {
            close(c);
            reset(c);
        }
    }

    // ---- 543 ------------------------------------------------------------------------------------------------------

    static void refusals(UiCase c) throws Exception {
        requireCustom(c);
        reset(c);
        ModScreenDriver d = open(c);
        try {
            press(c, d, "+ Add Shortcut");
            setBox(c, d, "Shortcut Name", 0, "k5ok");
            press(c, d, "+ Add Shortcut");
            c.check(customs(c).size() == 2, "two rows expected, have " + customs(c).size());
            // A Fabric/mod root that is not a shortcut, and a vanilla server root the client tree has.
            String clientRoot = c.onClient(mc -> {
                CommandDispatcher<Object> disp = CommandSweep.dispatcher();
                for (String want : new String[]{"fcc", "ap3", "k560", "profit"}) {
                    if (disp.getRoot().getChild(want) != null) {
                        return want;
                    }
                }
                return null;
            });
            String serverRoot = c.onClient(mc -> {
                CommandDispatcher<Object> disp = CommandSweep.dispatcher();
                for (String want : new String[]{"tell", "msg", "me", "list", "trigger"}) {
                    if (mc.getConnection().getCommands().getRoot().getChild(want) != null
                            && disp.getRoot().getChild(want) == null) {
                        return want;
                    }
                }
                return null;
            });
            c.check(clientRoot != null, "no Fabric/mod client root found to shadow");
            c.check(serverRoot != null, "no vanilla server root found in the client's command tree");
            String[][] bad = {
                    {"f7", "built-in"}, {"F7", "built-in"}, {"kuudra", "built-in"}, {"m3", "built-in"},
                    {"k5ok", "already one of your shortcuts"}, {"K5OK", "already one of your shortcuts"},
                    {clientRoot, "client command"}, {serverRoot, "server command"},
                    {"bad-name", "letters, digits"}, {"two words", "letters, digits"},
            };
            List<String> seen = new ArrayList<>();
            for (String[] b : bad) {
                setBox(c, d, "Shortcut Name", 1, b[0]);
                Object row = customs(c).get(1);
                String status = status(c, d);
                int color = c.onClient(mc -> (Integer) R.get(box(d, "Shortcut Name", 1), "textColor"));
                c.check("".equals(name(c, row)), "'" + b[0] + "' was saved as the name: '" + name(c, row) + "'");
                c.check(status.contains(b[1]), "'" + b[0] + "': status '" + status + "', expected '" + b[1] + "'");
                c.check(color == REFUSED, "'" + b[0] + "': name box colour " + Integer.toHexString(color));
                seen.add(b[0] + " -> " + status);
            }
            // The refused text survives a rebuild, still red, still explained.
            setBox(c, d, "Shortcut Name", 1, "f7");
            c.onClient(mc -> {
                rebuild(d);
                return null;
            });
            String kept = c.onClient(mc -> box(d, "Shortcut Name", 1).getValue());
            c.check("f7".equals(kept) && status(c, d).contains("built-in"),
                    "after a rebuild the refused name reads '" + kept + "', status '" + status(c, d) + "'");
            // Over-long: the box stops at 32, the setter refuses 33.
            setBox(c, d, "Shortcut Name", 1, "a".repeat(40));
            String typed = c.onClient(mc -> box(d, "Shortcut Name", 1).getValue());
            c.check(typed.length() == 32, "the name box took " + typed.length() + " characters");
            Object second = customs(c).get(1);
            Object problem = c.onClient(mc -> Mod.call(Mod.cfg(CFG), "setCustomName", second, "b".repeat(33)));
            c.check(problem != null, "a 33-character name was accepted by setCustomName");
            // And a good name clears it all.
            setBox(c, d, "Shortcut Name", 1, "k5fine");
            int color = c.onClient(mc -> (Integer) R.get(box(d, "Shortcut Name", 1), "textColor"));
            c.check("k5fine".equals(name(c, customs(c).get(1))) && status(c, d).isEmpty() && color == TEXT,
                    "a good name after a refusal: saved '" + name(c, customs(c).get(1)) + "', status '"
                            + status(c, d) + "', colour " + Integer.toHexString(color));
            c.note("refused: " + seen + "; 40 typed -> " + typed.length() + " kept; 33 via setter -> " + problem
                    + "; k5fine then accepted");
        } finally {
            close(c);
            reset(c);
        }
    }

    // ---- 545 ------------------------------------------------------------------------------------------------------

    static void sweepWithCustoms(UiCase c, Deny deny) throws Exception {
        requireCustom(c);
        reset(c);
        try {
            c.onClient(mc -> {
                Object cfg = Mod.cfg(CFG);
                Object live = Mod.call(cfg, "addCustom");
                Mod.call(cfg, "setCustomName", live, "k5live");
                Mod.call(cfg, "setCustomCommand", live, "/warp dh");
                Object off = Mod.call(cfg, "addCustom");
                Mod.call(cfg, "setCustomName", off, "k5off");
                Mod.call(cfg, "setCustomCommand", off, "is hub");
                Mod.call(cfg, "save");
                Mod.staticCall(FEAT, "syncNow");
                Mod.call(cfg, "setCustomEnabled", off, false);
                return null;
            });
            c.check(c.onClient(mc -> CommandSweep.dispatcher().getRoot().getChild("k5live") != null
                    && CommandSweep.dispatcher().getRoot().getChild("k5off") != null), "custom roots not registered");
            c.check(canUse(c, "k5live") && !canUse(c, "k5off"), "k5live should parse and k5off not");
            int before = c.problemCount();
            CommandSweep.commands(c, deny);
            c.check(c.problemCount() == before, "the 306 sweep found problems with custom roots present");
            c.note("306 sweep with custom roots k5live (live) and k5off (switched off) in the dispatcher: clean");
        } finally {
            reset(c);
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static boolean hasCustom() {
        try {
            Mod.cls(CFG).getMethod("customs");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static boolean hasCustomNow(UiCase c) {
        return c.onClient(mc -> hasCustom());
    }

    private static void requireCustom(UiCase c) {
        c.check(hasCustomNow(c), "this jar has no custom command shortcuts (CommandShortcutsConfig.customs)");
    }

    /** Master on, every built-in group on, no custom rows. */
    static void reset(UiCase c) {
        c.onClient(mc -> {
            Object cfg = Mod.cfg(CFG);
            Mod.call(cfg, "setEnabled", true);
            for (Object g : Mod.cls(GROUP).getEnumConstants()) {
                Mod.call(cfg, "setGroupOn", g, true);
            }
            if (hasCustom()) {
                @SuppressWarnings("unchecked")
                List<Object> rows = new ArrayList<>((List<Object>) Mod.call(cfg, "customs"));
                for (Object r : rows) {
                    Mod.call(cfg, "removeCustom", r);
                }
            }
            Mod.call(cfg, "save");
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Object> customs(UiCase c) {
        return c.onClient(mc -> new ArrayList<>((List<Object>) Mod.call(Mod.cfg(CFG), "customs")));
    }

    private static String name(UiCase c, Object row) {
        return c.onClient(mc -> (String) Mod.call(row, "name"));
    }

    private static String command(UiCase c, Object row) {
        return c.onClient(mc -> (String) Mod.call(row, "command"));
    }

    private static String describe(UiCase c, List<Object> rows) {
        List<String> out = new ArrayList<>();
        for (Object r : rows) {
            out.add(name(c, r) + "=" + command(c, r) + (c.onClient(mc -> (Boolean) Mod.call(r, "isEnabled")) ? "" : " (off)"));
        }
        return out.toString();
    }

    /** Wait (bounded) until {@code root} is in the live dispatcher and parses for the player, as typing would. */
    private static void awaitRoot(UiCase c, String root, boolean usable) {
        for (int i = 0; i < 60; i++) {
            if (canUse(c, root) == usable) {
                return;
            }
            c.ticks(2);
        }
        c.problem("/" + root + " never became " + (usable ? "usable" : "unusable") + " in the client dispatcher"
                + " (custom roots " + c.onClient(mc -> Mod.staticCall(FEAT, "customRoots")) + ")");
    }

    private static boolean canUse(UiCase c, String root) {
        return c.onClient(mc -> {
            CommandNode<Object> n = CommandSweep.dispatcher().getRoot().getChild(root);
            return n != null && n.canUse(CommandSweep.source(mc));
        });
    }

    /** Null when {@code input} parses to a runnable command for the player; else why not. */
    private static String parseError(UiCase c, String input) {
        return c.onClient(mc -> {
            ParseResults<Object> p = CommandSweep.dispatcher().parse(input, CommandSweep.source(mc));
            if (p.getReader().canRead() || p.getContext().getCommand() == null || !p.getExceptions().isEmpty()) {
                return "stops at " + p.getReader().getCursor() + ", exceptions " + p.getExceptions();
            }
            return null;
        });
    }

    static ModScreenDriver open(UiCase c) {
        ModScreenDriver d = c.onClient(mc -> {
            try {
                ModScreenDriver drv = new ModScreenDriver(mc);
                List<Object> tops = drv.topTabs();
                for (int i = 0; i < tops.size(); i++) {
                    List<Object[]> chain = new ArrayList<>();
                    if (find(drv, tops.get(i), chain)) {
                        drv.select(i);
                        for (Object[] step : chain) {
                            drv.expanded(step[0]).add((Integer) step[1]);
                        }
                        McCompat.setScreen(mc, drv.screen);
                        drv.rebuild();
                        return drv;
                    }
                }
                return null;
            } catch (Throwable t) {
                throw new AssertionError("could not open gui.ModScreen: " + UiCase.describe(t), t);
            }
        });
        c.check(d != null, "no '" + TAB + "' tab in the mod menu");
        c.ticks(3);
        return d;
    }

    private static boolean find(ModScreenDriver d, Object tab, List<Object[]> chain) {
        if (TAB.equals(R.get(tab, "name"))) {
            return true;
        }
        if (!d.isFolder(tab)) {
            return false;
        }
        List<Object> subs = d.subTabs(tab);
        for (int j = 0; j < subs.size(); j++) {
            chain.add(new Object[]{tab, j});
            if (find(d, subs.get(j), chain)) {
                return true;
            }
            chain.remove(chain.size() - 1);
        }
        return false;
    }

    private static void close(UiCase c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        c.ticks(1);
    }

    private static void rebuild(ModScreenDriver d) {
        try {
            d.rebuild();
        } catch (Throwable t) {
            throw new AssertionError(UiCase.describe(t), t);
        }
    }

    /** What the tab built, below the folder's own accordion rows (only this tab's sub-tab is open). */
    private static List<AbstractWidget> content(UiCase c, ModScreenDriver d) {
        return c.onClient(mc -> {
            rebuild(d);
            List<AbstractWidget> out = new ArrayList<>();
            @SuppressWarnings("unchecked")
            java.util.Map<AbstractWidget, String> scopes =
                    (java.util.Map<AbstractWidget, String>) R.getStatic(R.cls("gui.SettingTooltips"), "SCOPES");
            for (AbstractWidget w : d.content()) {
                if (TAB.equals(scopes.get(w))) {
                    out.add(w);
                }
            }
            return out;
        });
    }

    private static List<String> labels(List<AbstractWidget> ws) {
        List<String> out = new ArrayList<>();
        for (AbstractWidget w : ws) {
            out.add(ModScreenDriver.label(w));
        }
        return out;
    }

    /** Every pair of this tab's widgets: their rectangles must not intersect. */
    static void overlaps(UiCase c, List<AbstractWidget> ws) {
        for (int i = 0; i < ws.size(); i++) {
            for (int j = i + 1; j < ws.size(); j++) {
                AbstractWidget a = ws.get(i);
                AbstractWidget b = ws.get(j);
                boolean hit = a.getX() < b.getX() + b.getWidth() && b.getX() < a.getX() + a.getWidth()
                        && a.getY() < b.getY() + b.getHeight() && b.getY() < a.getY() + a.getHeight();
                if (hit) {
                    c.problem("'" + ModScreenDriver.label(a) + "' " + rect(a) + " overlaps '"
                            + ModScreenDriver.label(b) + "' " + rect(b));
                }
            }
        }
    }

    private static String rect(AbstractWidget w) {
        return "[" + w.getX() + "," + w.getY() + " " + w.getWidth() + "x" + w.getHeight() + "]";
    }

    /** Press the first of this tab's buttons whose label is {@code label}, then rebuild like the tab does. */
    private static void press(UiCase c, ModScreenDriver d, String label) {
        boolean pressed = c.onClient(mc -> {
            rebuild(d);
            for (AbstractWidget w : contentNow(d)) {
                if (!(w instanceof EditBox) && label.equals(ModScreenDriver.label(w))) {
                    boolean took = ModScreenDriver.press(w);
                    rebuild(d);
                    return took;
                }
            }
            return false;
        });
        c.check(pressed, "no '" + label + "' button took the click");
        c.ticks(2);
    }

    private static List<AbstractWidget> contentNow(ModScreenDriver d) {
        @SuppressWarnings("unchecked")
        java.util.Map<AbstractWidget, String> scopes =
                (java.util.Map<AbstractWidget, String>) R.getStatic(R.cls("gui.SettingTooltips"), "SCOPES");
        List<AbstractWidget> out = new ArrayList<>();
        for (AbstractWidget w : d.content()) {
            if (TAB.equals(scopes.get(w))) {
                out.add(w);
            }
        }
        return out;
    }

    /** The {@code index}-th (top to bottom) EditBox named {@code message} in the built tab. */
    private static EditBox box(ModScreenDriver d, String message, int index) {
        List<EditBox> boxes = new ArrayList<>();
        for (AbstractWidget w : contentNow(d)) {
            if (w instanceof EditBox e && message.equals(w.getMessage().getString())) {
                boxes.add(e);
            }
        }
        boxes.sort(Comparator.comparingInt(AbstractWidget::getY));
        if (index >= boxes.size()) {
            throw new AssertionError("no '" + message + "' box #" + index + " (have " + boxes.size() + ")");
        }
        return boxes.get(index);
    }

    /** Type into a box the way the tab sees it: {@code setValue} fires the box's responder. */
    private static void setBox(UiCase c, ModScreenDriver d, String message, int index, String text) {
        c.onClient(mc -> {
            box(d, message, index).setValue(text);
            return null;
        });
        c.ticks(1);
    }

    /** The Custom section's status line: the lowest text widget of the tab. */
    private static String status(UiCase c, ModScreenDriver d) {
        return c.onClient(mc -> {
            StringWidget last = null;
            for (AbstractWidget w : contentNow(d)) {
                if (w instanceof StringWidget s && (last == null || s.getY() > last.getY())) {
                    last = s;
                }
            }
            return last == null ? "" : ModScreenDriver.label(last);
        });
    }
}

package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.components.AbstractWidget;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Carnival category (mod staging-carnival-3, 2026-10-25; shipped before 2.0 by carnival-1110). Menu and parser
 * cases only - the in-world cases (Hx sidebar, chat, entities, blocks, GrimAC) are {@code hx.CarnivalSuite}:
 * <ul>
 *   <li>3760: "Carnival" is the category right after Mining; it holds exactly its legit cards, alphabetical, then (cheat
 *       jar only) its cheat block, each marked cheat-only; a legit jar has none of the cheat ones.</li>
 *   <li>3761-3769: one per tab. From a deleted config file the switch loads OFF and is the first row; with it ON the
 *       tab builds every row in its spec, each with its own scoped tooltip; one setting survives save + load; the file
 *       sits in config/killer560/skyblock/carnival; the file is put back.</li>
 *   <li>3770 (cheat jar): every Carnival automation tab shows the "can get you banned" note under its switch, and every
 *       one of its tooltips carries it.</li>
 *   <li>3771: the parsers and the solver, fed directly: the Fruit Digging clue lines (anchored: a player's copy is not
 *       one), the reveal tag, the NPC start lines, the sidebar lines, the goals, and the solver on boards whose answer is
 *       known (a Mines 0 clears its neighbors, a corner's Mines 3 makes its three neighbors bombs, a Treasure clue puts
 *       the Dragonfruit beside it).</li>
 * </ul>
 */
final class CarnivalUiCases {

    static final String[] CASES = {"3760-ui-carnival-category", "3761-ui-carnival-tracker",
            "3762-ui-fruit-digging-solver", "3763-ui-zombie-shootout-helper", "3764-ui-catch-a-fish-helper",
            "3765-ui-zombie-shootout-triggerbot", "3766-ui-auto-zombie-shootout", "3767-ui-auto-shovel-swap",
            "3768-ui-auto-fruit-digging", "3769-ui-auto-catch-a-fish", "3770-ui-carnival-automation-note",
            "3771-ui-carnival-parsers", "3772-ui-fruit-digging-triggerbot"};

    static final String NOTE = "Automation: can get you banned; keep it off on the main server";
    static final List<String> LEGIT = List.of("Carnival Tracker", "Catch a Fish Helper", "Fruit Digging Solver",
            "Zombie Shootout Helper");
    static final List<String> CHEAT = List.of("Auto Catch a Fish", "Auto Fruit Digging", "Auto Shovel Swap",
            "Auto Zombie Shootout", "Fruit Digging Triggerbot", "Zombie Shootout Triggerbot");

    /** One tab: its config class and the static accessor giving its instance, its rows, a setting to round-trip. */
    record Spec(String tab, String cfg, String accessor, List<String> rows, String setter, String getter, Object value,
                boolean cheat) {
    }

    static final List<Spec> SPECS = List.of(
            new Spec("Carnival Tracker", "carnival.CarnivalTrackerConfig", "getInstance",
                    List.of("Carnival Tracker", "Current Game", "Session", "Best Scores", "Goals Left", "Ticket Reminder"),
                    "setShowBest", "isShowBest", false, false),
            new Spec("Fruit Digging Solver", "carnival.FruitDiggingConfig", "getInstance",
                    List.of("Fruit Digging Solver", "Best Dig", "Clue Labels", "Fruit Labels", "Tile Odds", "Solver HUD",
                            "Fruit Left", "Mines Color", "Anchor Color", "Treasure Color", "Prevent Misclicks",
                            "Prevent Wrong Shovel", "Sneak To Dig Anyway"),
                    "setAnchorColor", "getAnchorColor", 0xFF123456, false),
            new Spec("Zombie Shootout Helper", "carnival.ZombieShootoutConfig", "getInstance",
                    List.of("Zombie Shootout Helper", "Zombie Outlines", "Highest Only", "Zombie Timers", "Best Target",
                            "Lit Lamps", "Lamp Line", "Shootout HUD", "Best Target Color"),
                    "setHighestOnly", "isHighestOnly", true, false),
            new Spec("Catch a Fish Helper", "carnival.CatchAFishConfig", "getInstance",
                    List.of("Catch a Fish Helper", "Fish Outlines", "Yellow Only", "Best Fish", "Line to Fish", "Fish HUD",
                            "Yellow Fish Color", "Other Fish Color"),
                    "setYellowOnly", "isYellowOnly", true, false),
            new Spec("Zombie Shootout Triggerbot", "carnival.ShootoutShotConfig", "triggerbot",
                    List.of("Zombie Shootout Triggerbot", "Shoot With", "Dart Tube Only", "Shot Delay", "Aim Delay"),
                    "setShotDelayMs", "getShotDelayMs", 400, true),
            new Spec("Auto Zombie Shootout", "carnival.ShootoutShotConfig", "auto",
                    List.of("Auto Zombie Shootout", "Shoot With", "Dart Tube Only", "Shot Delay", "Aim Delay", "Lamps First",
                            "Aim Mode"),
                    "setShootWith", "getShootWith", 1, true),
            new Spec("Auto Shovel Swap", "carnival.AutoFruitConfig", "swap",
                    List.of("Auto Shovel Swap", "Swap Delay"), "setDelayMs", "getDelayMs", 650, true),
            new Spec("Fruit Digging Triggerbot", "carnival.AutoFruitConfig", "trigger",
                    List.of("Fruit Digging Triggerbot", "Reaction Delay"), "setDelayMs", "getDelayMs", 450, true),
            new Spec("Auto Fruit Digging", "carnival.AutoFruitConfig", "dig",
                    List.of("Auto Fruit Digging", "Dig Delay", "Free Camera"), "setFreeCamera", "isFreeCamera", true, true),
            new Spec("Auto Catch a Fish", "carnival.AutoCatchAFishConfig", "getInstance",
                    List.of("Auto Catch a Fish", "Cast Delay", "Hook Wait", "Free Camera"), "setHookWaitS", "getHookWaitS",
                    6, true));

    private CarnivalUiCases() {
    }

    private static Object cfg(Spec s) {
        return Mod.staticCall(s.cfg(), s.accessor());
    }

    // ---- 3760 ------------------------------------------------------------------------------------------------------

    static void category(UiCase c) {
        boolean cheat = Mod.isCheat();
        Map<String, Object> out = c.onClient(mc -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            try {
                ModScreenDriver d = new ModScreenDriver(mc);
                List<String> top = new ArrayList<>();
                List<String> cards = new ArrayList<>();
                List<Boolean> flags = new ArrayList<>();
                for (ModScreenDriver.Node n : d.tree()) {
                    top.add(n.name());
                    if ("Carnival".equals(n.name())) {
                        for (ModScreenDriver.Node leaf : n.children()) {
                            cards.add(leaf.name());
                            flags.add((Boolean) Mod.call(leaf.tab(), "isCheatOnly"));
                        }
                    }
                }
                m.put("top", top);
                m.put("cards", cards);
                m.put("flags", flags);
            } catch (Throwable t) {
                m.put("error", UiCase.describe(t));
            }
            McCompat.setScreen(mc, null);
            return m;
        });
        c.check(!out.containsKey("error"), "could not open gui.ModScreen: " + out.get("error"));
        @SuppressWarnings("unchecked")
        List<String> top = (List<String>) out.get("top");
        @SuppressWarnings("unchecked")
        List<String> cards = (List<String>) out.get("cards");
        @SuppressWarnings("unchecked")
        List<Boolean> flags = (List<Boolean>) out.get("flags");
        c.note("top level " + top + "; Carnival cards " + cards + " cheat-only " + flags);
        int mining = top.indexOf("Mining");
        if (mining < 0 || mining + 1 >= top.size() || !"Carnival".equals(top.get(mining + 1))) {
            c.problem("Carnival is not the category right after Mining: " + top);
        }
        List<String> want = new ArrayList<>(LEGIT);
        if (cheat) {
            want.addAll(CHEAT);
        }
        if (!want.equals(cards)) {
            c.problem("Carnival cards are " + cards + ", want " + want + " (" + (cheat ? "cheat" : "legit") + " jar)");
        }
        for (int i = 0; i < cards.size() && i < flags.size(); i++) {
            boolean shouldBeCheat = CHEAT.contains(cards.get(i));
            if (flags.get(i) != shouldBeCheat) {
                c.problem("'" + cards.get(i) + "' isCheatOnly=" + flags.get(i) + ", want " + shouldBeCheat);
            }
        }
    }

    // ---- 3761-3769 -------------------------------------------------------------------------------------------------

    static void tab(UiCase c, String name) throws Exception {
        Spec s = SPECS.stream().filter(x -> x.tab().equals(name)).findFirst().orElseThrow();
        if (s.cheat() && !Mod.isCheat()) {
            c.note(name + " is cheat-only; on a legit jar it must not be in the menu (3760 checks that)");
            return;
        }
        Path file = c.onClient(mc -> (Path) Mod.call(cfg(s), "path"));
        byte[] saved = Files.exists(file) ? Files.readAllBytes(file) : null;
        try {
            Files.deleteIfExists(file);
            Object fresh = c.onClient(mc -> {
                Mod.staticCall(s.cfg(), "load");
                return cfg(s);
            });
            c.check(!Files.exists(file), name + ": the config file came back during load - not a fresh read");
            Object off = c.onClient(mc -> Mod.call(fresh, "isEnabledRaw"));
            if (!Boolean.FALSE.equals(off)) {
                c.problem(name + ": isEnabledRaw is " + off + " from a fresh config, want OFF");
            }
            List<String> offRows = labels(c, s.tab());
            c.note(name + " rows with the switch off: " + offRows);
            if (offRows.isEmpty() || !offRows.get(0).startsWith(s.rows().get(0) + ":")) {
                c.problem(name + ": the first row is not its '" + s.rows().get(0) + "' switch: " + offRows);
            }
            c.onClient(mc -> {
                Mod.call(fresh, "setEnabled", true);
                return null;
            });
            List<String> rows = labels(c, s.tab());
            c.note(name + " rows with the switch on: " + rows);
            for (String row : s.rows()) {
                if (rows.stream().noneMatch(l -> l.equals(row) || l.startsWith(row + ":"))) {
                    c.problem(name + ": no '" + row + "' row");
                }
                tooltip(c, s.tab(), row);
            }
            c.onClient(mc -> {
                Mod.call(fresh, s.setter(), s.value());
                Mod.call(fresh, "save");
                Mod.staticCall(s.cfg(), "load");
                return null;
            });
            Object back = c.onClient(mc -> Mod.call(cfg(s), s.getter()));
            c.check(Files.exists(file), name + ": save() wrote no file at " + file);
            c.note(name + ": " + s.getter() + " after save + load = " + back + " (set " + s.value() + ")");
            if (!s.value().equals(back)) {
                c.problem(name + ": " + s.setter() + "(" + s.value() + ") did not survive a reload, read " + back);
            }
            Object switchBack = c.onClient(mc -> Mod.call(cfg(s), "isEnabledRaw"));
            if (!Boolean.TRUE.equals(switchBack)) {
                c.problem(name + ": the switch did not survive a reload (" + switchBack + ")");
            }
            String folder = file.getParent() == null ? "" : file.getParent().toString().replace('\\', '/');
            if (!folder.endsWith("skyblock/carnival")) {
                c.problem(name + ": config file is in " + folder + ", want config/killer560/skyblock/carnival");
            }
        } finally {
            if (saved == null) {
                Files.deleteIfExists(file);
            } else {
                Files.write(file, saved);
            }
            c.onClient(mc -> {
                Mod.staticCall(s.cfg(), "load");
                McCompat.setScreen(mc, null);
                return null;
            });
        }
    }

    // ---- 3770 ------------------------------------------------------------------------------------------------------

    static void automationNote(UiCase c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: no automation tabs to check");
            return;
        }
        for (Spec s : SPECS) {
            if (!s.cheat()) {
                continue;
            }
            List<String> rows = labels(c, s.tab());
            c.note(s.tab() + " rows: " + rows);
            if (rows.size() < 2 || !rows.get(1).contains(NOTE)) {
                c.problem(s.tab() + ": the row under its switch is not the automation note: " + rows);
            }
            for (String row : s.rows()) {
                String tip = c.onClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "describe", s.tab(), row));
                if (tip == null || !tip.contains(NOTE)) {
                    c.problem(s.tab() + " row '" + row + "' tooltip does not carry the automation note: " + tip);
                }
            }
        }
    }

    // ---- 3771 ------------------------------------------------------------------------------------------------------

    static void parsers(UiCase c) {
        java.util.regex.Pattern mines = Mod.pattern("carnival.FruitDigging", "MINES");
        java.util.regex.Pattern treasure = Mod.pattern("carnival.FruitDigging", "TREASURE");
        java.util.regex.Pattern none = Mod.pattern("carnival.FruitDigging", "NO_FRUIT");
        java.util.regex.Pattern reveal = Mod.pattern("carnival.FruitDigging", "REVEAL");
        c.check(mines.matcher("MINES! There are 2 bombs hidden nearby.").matches()
                        && mines.matcher("MINES! There is 1 bomb hidden nearby.").matches()
                        && !mines.matcher("[MVP+] Bob: MINES! There are 2 bombs hidden nearby.").matches()
                        && !mines.matcher("MINES! There are 99999999999 bombs hidden nearby.").matches(),
                "the Mines clue pattern is wrong: " + mines.pattern());
        c.check(treasure.matcher("TREASURE! There is a Durian nearby.").matches()
                        && treasure.matcher("TREASURE! There is an Apple nearby.").matches()
                        && !treasure.matcher("Bob: TREASURE! There is a Durian nearby.").matches(),
                "the Treasure clue pattern is wrong: " + treasure.pattern());
        c.check(none.matcher("TREASURE! There are no fruits nearby!").matches()
                && none.matcher("ANCHOR! There are no fruits nearby!").matches(), "the no-fruit pattern is wrong");
        java.util.regex.Matcher rm = reveal.matcher("Pomegranate (+300)");
        c.check(rm.matches() && "Pomegranate".equals(rm.group(1)), "the reveal tag pattern does not read 'Pomegranate (+300)'");
        java.util.regex.Pattern pirate = Mod.pattern("carnival.CarnivalState", "START_FRUIT");
        java.util.regex.Pattern cowboy = Mod.pattern("carnival.CarnivalState", "START_ZOMBIE");
        java.util.regex.Pattern end = Mod.pattern("carnival.CarnivalState", "END_TITLE");
        c.check(pirate.matcher("[NPC] Carnival Pirateman: Good luck, matey!").matches()
                        && !pirate.matcher("Party > Bob: [NPC] Carnival Pirateman: Good luck, matey!").matches()
                        && cowboy.matcher("[NPC] Carnival Cowboy: Good luck, pal!").matches(),
                "the NPC start line patterns are wrong");
        c.check(end.matcher("                             Zombie Shootout").matches()
                && !end.matcher("Zombie Shootout").matches(), "the end title pattern is wrong: " + end.pattern());
        java.util.regex.Pattern tokens = Mod.pattern("carnival.CarnivalState", "SB_TOKENS");
        java.util.regex.Pattern fruits = Mod.pattern("carnival.CarnivalState", "SB_FRUITS");
        c.check(tokens.matcher("Carnival Tokens: 1,031").matches() && fruits.matcher("Fruits: 3/34").matches(),
                "the sidebar patterns are wrong");
        java.util.regex.Pattern earned = Mod.pattern("carnival.CarnivalTracker", "EARNED");
        c.check(earned.matcher("        You earned 17 Carnival Tokens!").matches()
                && !earned.matcher("Bob: You earned 17 Carnival Tokens!").matches(), "the tokens-earned pattern is wrong");
        Object goal = c.onClient(mc -> Mod.staticCall("carnival.CarnivalGoal", "fromChat",
                " - Shoot 5 Redstone Lamps in a single game of Zombie Shootout."));
        c.check("ZOMBIE_LAMPS".equals(String.valueOf(goal)), "a goal-complete line read as " + goal);

        // The solver on known boards.
        String[] res = c.onClient(mc -> {
            try {
                Class<?> solverCls = Mod.cls("carnival.FruitDiggingSolver");
                Class<?> cellCls = Mod.cls("carnival.FruitDiggingSolver$Cell");
                Class<?> fruitCls = Mod.cls("carnival.Fruit");
                Object solver = solverCls.getConstructor(java.util.Random.class).newInstance(new java.util.Random(7));
                Object cells = java.lang.reflect.Array.newInstance(cellCls, 49);
                for (int i = 0; i < 49; i++) {
                    java.lang.reflect.Array.set(cells, i, cellCls.getConstructor().newInstance());
                }
                java.util.function.BiConsumer<Integer, Object[]> set = (i, kv) -> {
                    try {
                        Object cell = java.lang.reflect.Array.get(cells, i);
                        for (int k = 0; k < kv.length; k += 2) {
                            cellCls.getField((String) kv[k]).set(cell, kv[k + 1]);
                        }
                    } catch (ReflectiveOperationException e) {
                        throw new RuntimeException(e);
                    }
                };
                Object mango = Mod.enumValue("carnival.Fruit", "MANGO");
                Object apple = Mod.enumValue("carnival.Fruit", "APPLE");
                Object rum = Mod.enumValue("carnival.Fruit", "RUM");
                Object dragon = Mod.enumValue("carnival.Fruit", "DRAGONFRUIT");
                int bomb = (Integer) fruitCls.getMethod("ordinal").invoke(Mod.enumValue("carnival.Fruit", "BOMB"));
                int dr = (Integer) fruitCls.getMethod("ordinal").invoke(dragon);
                set.accept(24, new Object[]{"diggable", false, "content", mango, "mines", 0});
                set.accept(0, new Object[]{"diggable", false, "content", apple, "mines", 3});
                set.accept(48, new Object[]{"diggable", false, "content", rum, "treasure", dragon});
                Method solve = solverCls.getMethod("solve", cells.getClass(), int.class, double.class, boolean.class);
                Object rec = solve.invoke(solver, cells, 3, 1.0, false);
                Method belief = solverCls.getMethod("belief", int.class);
                double centerBombs = 0;
                for (int nb : new int[]{16, 17, 18, 23, 25, 30, 31, 32}) {
                    centerBombs += ((double[]) belief.invoke(solver, nb))[bomb];
                }
                double cornerBombs = 0;
                for (int nb : new int[]{1, 7, 8}) {
                    cornerBombs += ((double[]) belief.invoke(solver, nb))[bomb];
                }
                double dragonNear = 0;
                for (int nb : new int[]{40, 41, 47}) {
                    dragonNear += ((double[]) belief.invoke(solver, nb))[dr];
                }
                return new String[]{String.valueOf(rec), String.valueOf(centerBombs), String.valueOf(cornerBombs),
                        String.valueOf(dragonNear), String.valueOf(solverCls.getMethod("samples").invoke(solver))};
            } catch (Throwable t) {
                return new String[]{"THREW " + UiCase.describe(t)};
            }
        });
        c.note("solver: " + String.join(" | ", res));
        if (res.length < 5) {
            c.problem("the solver could not be run: " + res[0]);
            return;
        }
        c.check(Double.parseDouble(res[1]) < 1e-9, "Mines 0 at D4 left bomb odds on its neighbors: " + res[1]);
        c.check(Math.abs(Double.parseDouble(res[2]) - 3.0) < 1e-9, "Mines 3 at corner A1 did not make its three neighbors bombs: " + res[2]);
        c.check(Math.abs(Double.parseDouble(res[3]) - 1.0) < 1e-9, "Treasure Dragonfruit at G7 did not put the Dragonfruit beside it: " + res[3]);
        c.check(Integer.parseInt(res[4]) > 1000, "the solver drew only " + res[4] + " samples");
        c.check(!"null".equals(res[0]), "the solver gave no recommendation");
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static List<String> labels(UiCase c, String tabName) {
        return c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            try {
                ModScreenDriver d = new ModScreenDriver(mc);
                List<ModScreenDriver.Node> flat = new ArrayList<>();
                ModScreenDriver.flatten(d.tree(), flat);
                Object tab = null;
                for (ModScreenDriver.Node n : flat) {
                    if (tabName.equals(n.name()) && !n.folder()) {
                        tab = n.tab();
                    }
                }
                if (tab == null) {
                    out.add("NO TAB " + tabName);
                    return out;
                }
                Method build = TabSweep.buildMethod();
                @SuppressWarnings("unchecked")
                List<AbstractWidget> ws = (List<AbstractWidget>) build.invoke(tab, 0, 0, 300, (Runnable) () -> { });
                for (AbstractWidget w : ws) {
                    out.add(ModScreenDriver.label(w));
                }
            } catch (Throwable t) {
                out.add("THREW " + UiCase.describe(t));
            }
            McCompat.setScreen(mc, null);
            return out;
        });
    }

    private static void tooltip(UiCase c, String tab, String row) {
        String tip = c.onClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "describe", tab, row));
        String key = c.onClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "key", row));
        @SuppressWarnings("unchecked")
        Map<String, String> all = (Map<String, String>) Mod.field(Mod.ROOT + "gui.SettingTooltips", "DESCRIPTIONS");
        String scoped = all.get(tab.toLowerCase(Locale.ROOT) + "/" + key);
        String bare = all.get(key);
        if (tip == null || tip.isBlank()) {
            c.problem("no tooltip for '" + tab + "' row '" + row + "'");
        } else if (scoped == null && (bare == null || !row.equals(tab))) {
            c.problem("'" + tab + "' row '" + row + "' has no '" + tab.toLowerCase(Locale.ROOT) + "/" + key
                    + "' entry (falls back to: " + tip + ")");
        }
    }
}

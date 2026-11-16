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
            "3771-ui-carnival-parsers", "3772-ui-fruit-digging-triggerbot", "3773-ui-auto-accept-game",
            "3774-ui-carnival-fold", "3775-ui-carnival-solver-drops", "3776-ui-carnival-accept-shapes"};

    static final String NOTE = "Automation: can get you banned; keep it off on the main server";
    static final List<String> LEGIT = List.of("Carnival Tracker", "Catch a Fish Helper", "Fruit Digging Solver",
            "Zombie Shootout Helper");
    static final List<String> CHEAT = List.of("Auto Catch a Fish", "Auto Fruit Digging",
            "Auto Shovel Swap", "Auto Zombie Shootout", "Fruit Digging Triggerbot", "Zombie Shootout Triggerbot");
    /** The Carnival section of Misc (carnival-sections-1114): the legit cards as a titled run, the cheat cards in Misc's Cheat block. */
    static final String GROUP = "Carnival";

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
                            "Prevent Wrong Shovel", "Sneak To Dig Anyway", "Strategy",
                            "Percent On Every Square"),
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
                    List.of("Auto Catch a Fish", "Cast Delay", "Free Camera"), "setFreeCamera", "isFreeCamera",
                    true, true));

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
                List<String> misc = new ArrayList<>();
                List<String> inGroup = new ArrayList<>();
                List<String> cheatLeaves = new ArrayList<>();
                for (ModScreenDriver.Node n : d.tree()) {
                    top.add(n.name());
                    if ("Misc".equals(n.name())) {
                        for (ModScreenDriver.Node leaf : n.children()) {
                            misc.add(leaf.name());
                            if (leaf.folder() && GROUP.equals(leaf.name())) {
                                for (ModScreenDriver.Node k : leaf.children()) {
                                    inGroup.add(k.name());
                                }
                            } else if (!leaf.folder() && Boolean.TRUE.equals(Mod.call(leaf.tab(), "isCheatOnly"))) {
                                cheatLeaves.add(leaf.name());
                            }
                        }
                    }
                }
                m.put("top", top);
                m.put("misc", misc);
                m.put("group", inGroup);
                m.put("cheat", cheatLeaves);
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
        List<String> misc = (List<String>) out.get("misc");
        @SuppressWarnings("unchecked")
        List<String> inGroup = (List<String>) out.get("group");
        @SuppressWarnings("unchecked")
        List<String> cheatLeaves = (List<String>) out.get("cheat");
        c.note("top level " + top + "; Misc " + misc + "; Carnival section " + inGroup + "; Misc cheat cards " + cheatLeaves);
        c.check(!top.contains("Carnival"), "Carnival is a category of its own: " + top);
        c.check(misc.contains(GROUP), "no '" + GROUP + "' section in Misc: " + misc);
        c.check(inGroup.equals(LEGIT), "the Carnival section holds " + inGroup + ", want " + LEGIT);
        for (String f : LEGIT) {
            c.check(!misc.contains(f), "'" + f + "' sits loose in Misc instead of the Carnival section");
        }
        if (cheat) {
            for (String f : CHEAT) {
                c.check(cheatLeaves.contains(f), "cheat card '" + f + "' is not a cheat-only card of Misc: " + cheatLeaves);
                c.check(!inGroup.contains(f), "cheat card '" + f + "' is inside the legit Carnival section");
            }
            List<String> mine = new ArrayList<>(cheatLeaves);
            mine.retainAll(CHEAT);
            c.check(mine.equals(CHEAT), "the Carnival cheat cards are out of order or missing in Misc's cheat block: " + mine);
            c.check(!cheatLeaves.contains("Auto Accept Game") && !misc.contains("Auto Accept Game"),
                    "Auto Accept Game is still a card (it is the Carnival Games row of Auto Dialogue)");
        } else {
            for (String f : CHEAT) {
                c.check(!misc.contains(f), "a legit jar shows the cheat card '" + f + "'");
            }
        }
        // each legit card's first row is its own switch
        for (String f : LEGIT) {
            List<String> rows = labels(c, f);
            c.check(!rows.isEmpty() && indexOfRow(rows, f) == 0, f + ": first row is not its own switch: " + rows);
        }
    }

    private static int indexOfRow(List<String> rows, String name) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).equals(name) || rows.get(i).startsWith(name + ":")) {
                return i;
            }
        }
        return -1;
    }

    // ---- 3773, 3774 ------------------------------------------------------------------------------------------------

    /** Auto Dialogue's Carnival Games row (the old Auto Accept Game): cheat switch, default OFF, own tooltip, Auto Dialogue
     *  has no Outside Dungeons row; absent on a legit jar. */
    static void autoAccept(UiCase c) throws Exception {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Dialogue is compiled out");
            return;
        }
        Path file = c.onClient(mc -> (Path) Mod.call(Mod.staticCall("carnival.AutoAcceptGameConfig", "getInstance"), "path"));
        byte[] saved = Files.exists(file) ? Files.readAllBytes(file) : null;
        try {
            Files.deleteIfExists(file);
            c.onClient(mc -> Mod.staticCall("carnival.AutoAcceptGameConfig", "load"));
            Object on = c.onClient(mc -> Mod.call(Mod.staticCall("carnival.AutoAcceptGameConfig", "getInstance"), "isEnabledRaw"));
            c.check(Boolean.FALSE.equals(on), "Carnival Games is " + on + " from a fresh config, want OFF");
            List<String> rows = labels(c, "Auto Dialogue");
            c.note("Auto Dialogue rows (off): " + rows);
            c.check(indexOfRow(rows, "Carnival Games") >= 0, "Auto Dialogue has no 'Carnival Games' row: " + rows);
            c.check(indexOfRow(rows, "Outside Dungeons") < 0, "Auto Dialogue still has an 'Outside Dungeons' row: " + rows);
            tooltip(c, "Auto Dialogue", "Carnival Games");
            Object cfg = c.onClient(mc -> Mod.staticCall("carnival.AutoAcceptGameConfig", "getInstance"));
            c.onClient(mc -> Mod.call(cfg, "setEnabled", true));
            c.onClient(mc -> Mod.call(cfg, "save"));
            c.onClient(mc -> Mod.staticCall("carnival.AutoAcceptGameConfig", "load"));
            Object back = c.onClient(mc -> Mod.call(Mod.staticCall("carnival.AutoAcceptGameConfig", "getInstance"), "isEnabledRaw"));
            c.check(Boolean.TRUE.equals(back), "Carnival Games did not survive a reload: " + back);
        } finally {
            if (saved == null) {
                Files.deleteIfExists(file);
            } else {
                Files.write(file, saved);
            }
            c.onClient(mc -> Mod.staticCall("carnival.AutoAcceptGameConfig", "load"));
        }
    }

    /** Keybinds: only Carnival Games (a row, no card) needs a ModuleKeybinds EXTRAS id; every Carnival card has its own. Strategy cycles and survives a reload. */
    static void fold(UiCase c) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> extras = (Map<String, Object>) Mod.field(Mod.ROOT + "gui.ModuleKeybinds", "EXTRAS");
        if (Mod.isCheat()) {
            c.check(extras.containsKey("AutoAcceptGameTab"), "ModuleKeybinds EXTRAS has no 'AutoAcceptGameTab' (its saved toggle key would be lost)");
        }
        for (String id : List.of("CarnivalTrackerTab", "FruitDiggingTab", "ZombieShootoutTab", "CatchAFishTab", "ZombieTriggerbotTab",
                "AutoZombieShootoutTab", "AutoShovelSwapTab", "AutoFruitDiggingTab", "FruitDiggingTriggerbotTab", "AutoCatchAFishTab")) {
            c.check(!extras.containsKey(id), "'" + id + "' is still a ModuleKeybinds EXTRAS entry although it has its own card again");
        }
        Object cfg = c.onClient(mc -> Mod.staticCall("carnival.FruitDiggingConfig", "getInstance"));
        int before = (Integer) c.onClient(mc -> Mod.call(cfg, "getStrategy"));
        try {
            for (int v = 0; v < 3; v++) {
                final int vv = v;
                c.onClient(mc -> Mod.call(cfg, "setStrategy", vv));
                c.onClient(mc -> Mod.call(cfg, "save"));
                c.onClient(mc -> Mod.staticCall("carnival.FruitDiggingConfig", "load"));
                Object back = c.onClient(mc -> Mod.call(Mod.staticCall("carnival.FruitDiggingConfig", "getInstance"), "getStrategy"));
                c.check(Integer.valueOf(v).equals(back), "Strategy " + v + " did not survive a reload, read " + back);
            }
            c.check((Boolean) c.onClient(mc -> Mod.call(Mod.staticCall("carnival.FruitDiggingConfig", "getInstance"),
                    "isAllPercents")) == Boolean.FALSE, "Percent On Every Square is not OFF by default");
        } finally {
            c.onClient(mc -> Mod.call(Mod.staticCall("carnival.FruitDiggingConfig", "getInstance"), "setStrategy", before));
            c.onClient(mc -> Mod.call(Mod.staticCall("carnival.FruitDiggingConfig", "getInstance"), "save"));
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
            if (indexOfRow(offRows, s.rows().get(0)) < 0) {
                c.problem(name + ": no '" + s.rows().get(0) + "' switch row in its card: " + offRows);
            }
            if (s.rows().size() > 1 && indexOfRow(offRows, s.rows().get(1)) >= 0) {
                c.problem(name + ": '" + s.rows().get(1) + "' shows while its switch is off: " + offRows);
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
            Object cfg = cfg(s);
            c.onClient(mc -> Mod.call(cfg, "setEnabled", true));
            List<String> rows = labels(c, s.tab());
            c.note(s.tab() + " rows: " + rows);
            int sw = indexOfRow(rows, s.tab());
            if (sw < 0 || sw + 1 >= rows.size() || !rows.get(sw + 1).contains(NOTE)) {
                c.problem(s.tab() + ": the row under its switch is not the automation note: " + rows);
            }
            c.onClient(mc -> Mod.call(cfg, "setEnabled", false));
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

    // ---- 3775 ------------------------------------------------------------------------------------------------------

    private static int idx(String label) {
        return (label.charAt(0) - 'A') * 7 + (label.charAt(1) - '1');
    }

    /** One board fed to the solver: {@code setup} fills the cells; returns {samples, dropped-or-null}. */
    private static String[] solveBoard(java.util.function.BiConsumer<Integer, Object[]> unused, String name,
                                       java.util.function.Consumer<java.util.function.BiConsumer<String, Object[]>> setup) {
        try {
            Class<?> solverCls = Mod.cls("carnival.FruitDiggingSolver");
            Class<?> cellCls = Mod.cls("carnival.FruitDiggingSolver$Cell");
            Object solver = solverCls.getConstructor(java.util.Random.class).newInstance(new java.util.Random(11));
            Object cells = java.lang.reflect.Array.newInstance(cellCls, 49);
            for (int i = 0; i < 49; i++) {
                java.lang.reflect.Array.set(cells, i, cellCls.getConstructor().newInstance());
            }
            setup.accept((label, kv) -> {
                try {
                    Object cell = java.lang.reflect.Array.get(cells, idx(label));
                    for (int k = 0; k < kv.length; k += 2) {
                        cellCls.getField((String) kv[k]).set(cell, kv[k + 1]);
                    }
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            });
            Object rec = solverCls.getMethod("solve", cells.getClass(), int.class, double.class, boolean.class)
                    .invoke(solver, cells, 2, 1.0, false);
            return new String[]{String.valueOf(solverCls.getMethod("samples").invoke(solver)),
                    String.valueOf(solverCls.getMethod("dropped").invoke(solver)), String.valueOf(rec)};
        } catch (Throwable t) {
            return new String[]{"THREW " + UiCase.describe(t)};
        }
    }

    /** His 11-14 log: two bomb digs left contradicting clues and the solver played plain odds ("0 samples") to the end. */
    static void solverDrops(UiCase c) {
        Object bomb = Mod.enumValue("carnival.Fruit", "BOMB");
        Object rum = Mod.enumValue("carnival.Fruit", "RUM");
        Object mango = Mod.enumValue("carnival.Fruit", "MANGO");
        Object durian = Mod.enumValue("carnival.Fruit", "DURIAN");
        // Game 2: F1 dug and held a Bomb; the Anchor marked G1 (a fruit) and the blast then said G1 holds none.
        String[] g2 = c.onClient(mc -> solveBoard(null, "game2", set -> {
            set.accept("F1", new Object[]{"diggable", false, "content", bomb});
            set.accept("G1", new Object[]{"mustFruit", true, "notFruit", true});
        }));
        c.note("game 2 replay: samples " + g2[0] + ", dropped " + (g2.length > 1 ? g2[1] : "-"));
        c.check(g2.length >= 3, "game 2 replay threw: " + g2[0]);
        if (g2.length >= 3) {
            c.check(Integer.parseInt(g2[0]) > 0, "game 2: the solver kept " + g2[0] + " samples (plain odds)");
            c.check(!"null".equals(g2[1]), "game 2: contradicting clues were not reported as dropped");
            c.check(!"null".equals(g2[2]), "game 2: no recommendation");
        }
        // Game 1: G2 dug with a Durian Treasure clue (from F1,F3,G1,G3; F3 Rum, G3 Mango), then F1 dug a Bomb with NoFruit
        // over E1,E2,G1: G1 cannot be both the Durian and empty.
        String[] g1 = c.onClient(mc -> solveBoard(null, "game1", set -> {
            set.accept("G2", new Object[]{"diggable", false, "treasure", durian, "dugAt", 1,
                    "from", new int[]{idx("F1"), idx("F3"), idx("G1"), idx("G3")}});
            set.accept("F3", new Object[]{"diggable", false, "content", rum});
            set.accept("G3", new Object[]{"diggable", false, "content", mango});
            set.accept("F1", new Object[]{"diggable", false, "content", bomb, "noFruitNearby", true, "dugAt", 2,
                    "from", new int[]{idx("E1"), idx("E2"), idx("G1")}});
        }));
        c.note("game 1 replay: samples " + g1[0] + ", dropped " + (g1.length > 1 ? g1[1] : "-"));
        c.check(g1.length >= 3, "game 1 replay threw: " + g1[0]);
        if (g1.length >= 3) {
            c.check(Integer.parseInt(g1[0]) > 0, "game 1: the solver kept " + g1[0] + " samples (plain odds)");
            c.check(!"null".equals(g1[1]) && g1[1].contains("NoFruit"), "game 1: dropped list is " + g1[1]
                    + ", want the bomb dig's NoFruit clue named");
        }
        // A consistent board drops nothing.
        String[] ok = c.onClient(mc -> solveBoard(null, "ok", set -> set.accept("D4", new Object[]{"diggable", false, "content", mango, "mines", 0})));
        c.check(ok.length >= 3 && "null".equals(ok[1]) && Integer.parseInt(ok[0]) > 0, "a consistent board reported drops: " + String.join("|", ok));
    }

    // ---- 3776 ------------------------------------------------------------------------------------------------------

    /** The real prompt shape: one component, a plain prefix, an arrow, then the clickable option, nested one level too. */
    static void acceptShapes(UiCase c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Carnival Games is compiled out");
            return;
        }
        String[] r = c.onClient(mc -> {
            try {
                net.minecraft.network.chat.Style click = net.minecraft.network.chat.Style.EMPTY.withClickEvent(
                        new net.minecraft.network.chat.ClickEvent.RunCommand("/selectnpcoption pirate aye"));
                net.minecraft.network.chat.Style click2 = net.minecraft.network.chat.Style.EMPTY.withClickEvent(
                        new net.minecraft.network.chat.ClickEvent.RunCommand("/selectnpcoption pirate rules"));
                net.minecraft.network.chat.MutableComponent flat = net.minecraft.network.chat.Component.literal("Select an option: \n")
                        .append(net.minecraft.network.chat.Component.literal("  ➜ "))
                        .append(net.minecraft.network.chat.Component.literal("[Aye sure do!]").withStyle(click))
                        .append(net.minecraft.network.chat.Component.literal(" \n  ➜ "))
                        .append(net.minecraft.network.chat.Component.literal("[Could ye tell me the rules again, Captain?]").withStyle(click2));
                net.minecraft.network.chat.MutableComponent nested = net.minecraft.network.chat.Component.literal("")
                        .append(net.minecraft.network.chat.Component.literal("Select an option: \n")
                                .append(net.minecraft.network.chat.Component.literal("  ➜ ")
                                        .append(net.minecraft.network.chat.Component.literal("[Aye sure do!]").withStyle(click))));
                net.minecraft.network.chat.MutableComponent none = net.minecraft.network.chat.Component.literal("Select an option: ");
                return new String[]{String.valueOf(Mod.staticCall("carnival.AutoAcceptGame", "firstRunCommand", flat)),
                        String.valueOf(Mod.staticCall("carnival.AutoAcceptGame", "firstRunCommand", nested)),
                        String.valueOf(Mod.staticCall("carnival.AutoAcceptGame", "firstRunCommand", none))};
            } catch (Throwable t) {
                return new String[]{"THREW " + UiCase.describe(t)};
            }
        });
        c.note("firstRunCommand: " + String.join(" | ", r));
        c.check(r.length == 3, "could not run: " + r[0]);
        if (r.length == 3) {
            c.check("/selectnpcoption pirate aye".equals(r[0]), "flat prompt gave " + r[0] + ", want the FIRST option");
            c.check("/selectnpcoption pirate aye".equals(r[1]), "nested prompt gave " + r[1] + ", want the option");
            c.check("null".equals(r[2]), "a prompt with no option gave " + r[2]);
        }
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

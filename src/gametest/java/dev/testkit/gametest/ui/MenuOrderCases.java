package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 515-518: killer560's 2026-10-14 menu order + independent masters list (alphabetical tabs, master first, cards that stand for several features). */
final class MenuOrderCases {

    private static final String TIPS = "gui.SettingTooltips";

    private MenuOrderCases() {
    }

    /** One built row for the dump: the owning sub-tab, its position and what it is. */
    private record Line(String owner, int x, int y, String label, boolean header, boolean red, boolean sw, boolean on) {
    }

    @SuppressWarnings("unchecked")
    private static List<Line> lines(ModScreenDriver d) throws Exception {
        Map<AbstractWidget, String> scopes = (Map<AbstractWidget, String>) R.getStatic(R.cls(TIPS), "SCOPES");
        List<Line> out = new ArrayList<>();
        for (AbstractWidget w : d.content()) {
            String raw = w.getMessage().getString();
            boolean sw = false;
            boolean on = false;
            if (d.isSettingsButton(w)) {
                sw = Boolean.TRUE.equals(w.getClass().getMethod("isSwitch").invoke(w));
                on = Boolean.TRUE.equals(w.getClass().getMethod("isOn").invoke(w));
            }
            String owner = scopes.get(w);
            out.add(new Line(owner == null ? "(unscoped)" : owner, w.getX(), w.getY(), ModScreenDriver.label(w),
                    w instanceof StringWidget, raw.startsWith("§c§l"), sw, on));
        }
        out.sort(Comparator.comparingInt(Line::y).thenComparingInt(Line::x));
        return out;
    }

    // ---- 515 ------------------------------------------------------------------------------------------------------

    private static final java.util.Set<String> CATEGORIES =
            java.util.Set.of("Dungeons", "HUD", "Chat", "Items", "Social", "Cosmetics", "Misc");

    /** The order rule (mod gui.tab.MenuCategories.cheatLast): alphabetical by name ignoring case. */
    private static int byName(String a, String b) {
        int k = a.toLowerCase(java.util.Locale.ROOT).compareTo(b.toLowerCase(java.util.Locale.ROOT));
        return k != 0 ? k : a.compareTo(b);
    }

    /** Problems with one run of entries that must be alphabetical; {@code first} stays in front of the rest. */
    private static void checkRun(UiCase c, String where, List<String> names, String first) {
        List<String> rest = new ArrayList<>(names);
        if (first != null && !rest.isEmpty()) {
            if (!rest.get(0).equals(first)) {
                c.problem(where + ": should start with '" + first + "' but starts with '" + rest.get(0) + "'");
            }
            rest.remove(first);
        }
        for (int i = 1; i < rest.size(); i++) {
            if (byName(rest.get(i - 1), rest.get(i)) > 0) {
                c.problem(where + ": '" + rest.get(i) + "' is listed after '" + rest.get(i - 1) + "'");
            }
        }
    }

    /** Checks one list of cards: legit run, then the cheat run, each alphabetical. Returns how many it saw. */
    private static int checkList(UiCase c, String where, List<ModScreenDriver.Node> kids, String firstInGroup) {
        List<String> legit = new ArrayList<>();
        List<String> cheat = new ArrayList<>();
        boolean sawCheat = false;
        for (ModScreenDriver.Node k : kids) {
            boolean isCheat = MenuLayoutCases.cheatOnly(c, k);
            if (isCheat) {
                sawCheat = true;
                cheat.add(k.name());
            } else {
                if (sawCheat) {
                    c.problem(where + ": legit '" + k.name() + "' comes after a cheat-only tab");
                }
                legit.add(k.name());
            }
        }
        checkRun(c, where + " (legit)", legit, firstInGroup);
        checkRun(c, where + " (cheat)", cheat, null);
        int n = kids.size();
        for (ModScreenDriver.Node k : kids) {
            if (k.folder()) {
                // Solver Highlights, which Puzzle Solvers used to list first, is gone since mod menu-hud-1018 (killer560
                // 2026-10-18: per-solver highlight rows; mod MenuCategories): every group is plain alphabetical now.
                n += checkList(c, where + " > " + k.name(), k.children(), null);
            }
        }
        return n;
    }

    static void tabOrder(UiCase c) throws Exception {
        boolean cheat = Mod.isCheat();
        try (AutoCloseable all = MenuLayoutCases.allTested(c)) {
            ModScreenDriver d = MenuLayoutCases.driver(c);
            List<ModScreenDriver.Node> tree = c.onClient(mc -> d.tree());
            java.util.Set<String> seen = new java.util.HashSet<>();
            int checked = 0;
            ModScreenDriver.Node dungeons = null;
            for (ModScreenDriver.Node cat : tree) {
                if (!CATEGORIES.contains(cat.name())) {
                    continue;
                }
                seen.add(cat.name());
                if (cat.name().equals("Dungeons")) {
                    dungeons = cat;
                }
                List<String> names = new ArrayList<>();
                for (ModScreenDriver.Node k : cat.children()) {
                    names.add((MenuLayoutCases.cheatOnly(c, k) ? "[C] " : "") + k.name());
                }
                c.note(cat.name() + ": " + names);
                checked += checkList(c, cat.name(), cat.children(), null);
            }
            c.check(seen.equals(CATEGORIES), "categories found " + seen + ", wanted " + CATEGORIES);
            c.check(checked > (cheat ? 150 : 100), "only " + checked + " cards checked - the walk did not reach the menu");
            // Anchors: a regression that sorted by something else (class order, length) would trip these.
            List<String> dn = new ArrayList<>();
            for (ModScreenDriver.Node k : dungeons.children()) {
                dn.add(k.name());
            }
            for (String[] pair : new String[][]{{"Dungeon Alerts", "Dungeon Map"}, {"Dungeon Map", "Dungeon Queue"},
                    {"Blessings", "Croesus"}, {"Puzzle Solvers", "Secrets"}, {"Secrets", "Sharp Shooter (i4)"}}) {
                int x = dn.indexOf(pair[0]);
                int y = dn.indexOf(pair[1]);
                c.check(x >= 0 && y >= 0 && x < y, "Dungeons should list '" + pair[0] + "' before '" + pair[1] + "': " + dn);
            }
            if (cheat) {
                int firstCheat = -1;
                for (int i = 0; i < dungeons.children().size(); i++) {
                    if (MenuLayoutCases.cheatOnly(c, dungeons.children().get(i))) {
                        firstCheat = i;
                        break;
                    }
                }
                c.check(firstCheat > 40 && firstCheat < dungeons.children().size() - 10,
                        "the Dungeons cheat block should be a long run at the bottom, starts at " + firstCheat);
            }
            MenuLayoutCases.close(c, d);
        }
    }

    // ---- 516 ------------------------------------------------------------------------------------------------------

    /** A row's name before its ":" (formatting already stripped). */
    private static String rowName(String label) {
        int colon = label.indexOf(':');
        return (colon > 0 ? label.substring(0, colon) : label).trim();
    }

    /**
     * Inside every tab the card's master comes first: no ON/OFF switch sits above the row the card's switch stands for
     * (any of them, for a tab of several independent features), reading the tab top to bottom, left to right. A tab
     * with no master (the card shows no switch) is not judged. Cheat-only sections last is 512's.
     */
    static void settingsOrder(UiCase c, Deny deny) throws Exception {
        try (AutoCloseable all = MenuLayoutCases.allTested(c)) {
            ModScreenDriver d = MenuLayoutCases.driver(c);
            List<ModScreenDriver.Node> tree = c.onClient(mc -> d.tree());
            Map<String, Object> tabs = new LinkedHashMap<>();
            List<ModScreenDriver.Node> flat = new ArrayList<>();
            ModScreenDriver.flatten(tree, flat);
            for (ModScreenDriver.Node n : flat) {
                if (!n.folder()) {
                    tabs.putIfAbsent(n.name(), n.tab());
                }
            }
            int[] judged = {0};
            int[] multi = {0};
            Runnable none = () -> { };
            for (int i = 0; i < tree.size(); i++) {
                ModScreenDriver.Node topNode = tree.get(i);
                int sections = topNode.folder() ? topNode.children().size() : 1;
                for (int j = 0; j < sections; j++) {
                    int top = i;
                    int sec = j;
                    String where = topNode.name() + (topNode.folder() ? " > " + topNode.children().get(j).name() : "");
                    c.onClient(mc -> {
                        try {
                            MenuLayoutCases.open(d, top, sec, deny);
                            Map<String, List<Line>> by = new LinkedHashMap<>();
                            for (Line l : lines(d)) {
                                by.computeIfAbsent(l.owner(), k -> new ArrayList<>()).add(l);
                            }
                            for (Map.Entry<String, List<Line>> e : by.entrySet()) {
                                Object tab = tabs.get(e.getKey());
                                // Auto Clear holds two features: Auto Secret's rows (Blood First, Do Puzzles... no
                                // switch of its own) come first and Auto Clear's own switch heads the second section.
                                if (tab == null || "Auto Clear".equals(e.getKey())) {
                                    continue;
                                }
                                Object ms = Mod.staticCall("gui.FeatureCards", "masterSwitch", tab, none);
                                if (ms == null) {
                                    continue;
                                }
                                java.util.Set<String> masters = new java.util.HashSet<>();
                                for (Object part : (List<?>) Mod.call(ms, "parts")) {
                                    // A switch the tab builds but never shows (Auto Clear's) is not a row to put first.
                                    if (((AbstractWidget) part).visible) {
                                        masters.add(rowName(ModScreenDriver.label((AbstractWidget) part)));
                                    }
                                }
                                // Only a switch the tab really shows as a row is judged (Auto Clear's card switch is
                                // not a row of its tab).
                                java.util.Set<String> shown = new java.util.HashSet<>();
                                for (Line l : e.getValue()) {
                                    if (l.sw()) {
                                        shown.add(rowName(l.label()));
                                    }
                                }
                                masters.retainAll(shown);
                                if (masters.isEmpty()) {
                                    continue;
                                }
                                Line firstSwitch = null;
                                for (Line l : e.getValue()) {
                                    if (l.sw()) {
                                        firstSwitch = l;
                                        break;
                                    }
                                }
                                judged[0]++;
                                if (masters.size() > 1) {
                                    multi[0]++;
                                }
                                if (firstSwitch == null) {
                                    c.problem("'" + where + "' > " + e.getKey() + ": the card has a switch " + masters
                                            + " but the tab shows no ON/OFF row");
                                } else if (!masters.contains(rowName(firstSwitch.label()))) {
                                    c.problem("'" + where + "' > " + e.getKey() + ": the first switch is '"
                                            + firstSwitch.label() + "', not one the card stands for " + masters);
                                }
                            }
                        } catch (Throwable t) {
                            c.problem("'" + where + "': " + UiCase.describe(t));
                        }
                        return null;
                    });
                }
            }
            c.note("tabs with a card switch judged: " + judged[0] + ", of them standing for several features: " + multi[0]);
            c.check(judged[0] > (Mod.isCheat() ? 120 : 70), "only " + judged[0] + " tabs judged - the walk is broken");
            c.check(multi[0] >= (Mod.isCheat() ? 15 : 8), "only " + multi[0] + " multi-feature cards seen");
            MenuLayoutCases.close(c, d);
        }
    }

    // ---- 517 / 518 ------------------------------------------------------------------------------------------------

    private static byte[] snapshot(Path p) throws Exception {
        return Files.exists(p) ? Files.readAllBytes(p) : null;
    }

    private static void restore(Path p, byte[] bytes) throws Exception {
        if (bytes == null) {
            Files.deleteIfExists(p);
        } else {
            Files.write(p, bytes);
        }
    }

    private static Path configPath(String cls) {
        return (Path) Mod.field(cls, "CONFIG_PATH");
    }

    private static void write(String cls, String json) throws Exception {
        Path p = configPath(cls);
        Files.createDirectories(p.getParent());
        Files.writeString(p, json, StandardCharsets.UTF_8);
    }

    private static Boolean cardState(Object tab) {
        return (Boolean) Mod.staticCall("gui.FeatureCards", "refresh", tab);
    }

    private static boolean flag(String cls, String getter) {
        return (Boolean) Mod.call(Mod.cfg(cls), getter);
    }

    private static void reload(String... classes) {
        for (String cls : classes) {
            Mod.staticCall(cls, "load");
        }
    }

    private static void pressCard(Minecraft mc, Object tab) {
        net.minecraft.client.gui.screens.Screen s = McCompat.screen(mc);
        if (s == null || !s.getClass().getName().equals(Mod.ROOT + "gui.ModScreen")) {
            try {
                s = MenuACases.open(mc);
            } catch (Exception e) {
                throw new AssertionError(UiCase.describe(e), e);
            }
        }
        Mod.call(s, "selectCategory", MenuACases.name(MenuACases.category(tab)));
        int[][] b = MenuACases.reveal(s, tab);
        MenuACases.press(s, b[1][0] + b[1][2] / 2.0, b[1][1] + b[1][3] / 2.0);
    }

    /**
     * Dungeon Queue: Auto Requeue and Party Finder Overlay are two features, so the card is OFF only when both are
     * (killer560, 2026-10-14). Real files, written the way an older build wrote them (the master toggle's key alone),
     * loaded by the mod, then the card's real switch pressed.
     */
    static void queueMasters(UiCase c) throws Exception {
        String dq = "dungeonqueue.DungeonQueueConfig";
        String pf = "partyfinder.PartyFinderOverlayConfig";
        byte[] dqBefore = snapshot(configPath(dq));
        byte[] pfBefore = snapshot(configPath(pf));
        Object[] gate = new Object[1];
        try {
            gate[0] = c.onClient(mc -> {
                Object was = Mod.staticCall("util.SkyblockGate", "isEnabled");
                // Skyblock Only is off in this client, so the effective getters answer for the switches alone.
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                return was;
            });
            Object tab = c.onClient(mc -> MenuACases.leaf("DungeonQueueTab"));
            // The card stands for two rows.
            Integer parts = c.onClient(mc -> {
                Object ms = Mod.staticCall("gui.FeatureCards", "masterSwitch", tab, (Runnable) () -> { });
                return ms == null ? 0 : ((List<?>) Mod.call(ms, "parts")).size();
            });
            c.check(parts == 2, "the Dungeon Queue card should stand for 2 rows, it stands for " + parts);

            // Each file holds just "enabled", as before there was more than one thing to switch.
            String[][] cases = {
                    // dq file, pf file, expect dq raw, expect pf raw, expect card, label
                    {"{\"enabled\": true}", null, "true", "false", "true", "an old Auto Requeue file alone"},
                    {"{\"enabled\": false}", "{\"enabled\": true}", "false", "true", "true",
                            "Auto Requeue OFF, Party Finder Overlay ON"},
                    {"{\"enabled\": false}", "{\"enabled\": false}", "false", "false", "false", "both OFF"},
                    {"{\"enabled\": true}", "{\"enabled\": true}", "true", "true", "true", "both ON"},
                    {null, null, "false", "false", "false", "no files at all"},
            };
            for (String[] t : cases) {
                String why = c.onClient(mc -> {
                    try {
                        for (String[] f : new String[][]{{dq, t[0]}, {pf, t[1]}}) {
                            if (f[1] == null) {
                                Files.deleteIfExists(configPath(f[0]));
                            } else {
                                write(f[0], f[1]);
                            }
                        }
                        reload(dq, pf);
                        boolean dqRaw = flag(dq, "isEnabledRaw");
                        boolean pfRaw = flag(pf, "isEnabledRaw");
                        if (dqRaw != Boolean.parseBoolean(t[2]) || pfRaw != Boolean.parseBoolean(t[3])) {
                            return t[5] + ": loaded Auto Requeue " + dqRaw + ", Party Finder Overlay " + pfRaw;
                        }
                        // What the features read: each by its own switch alone.
                        if (flag(dq, "isEnabled") != dqRaw || flag(pf, "isEnabled") != pfRaw) {
                            return t[5] + ": the features read " + flag(dq, "isEnabled") + " / " + flag(pf, "isEnabled");
                        }
                        Boolean card = cardState(tab);
                        if (card == null || card != Boolean.parseBoolean(t[4])) {
                            return t[5] + ": the card reads " + card + ", wanted " + t[4];
                        }
                        c.note(t[5] + ": Auto Requeue " + dqRaw + ", Party Finder Overlay " + pfRaw + ", card " + card);
                        return null;
                    } catch (Exception e) {
                        return t[5] + ": " + UiCase.describe(e);
                    }
                });
                c.check(why == null, why);
            }

            // Real clicks on the card's switch.
            String clicks = c.onClient(mc -> {
                try {
                    write(dq, "{\"enabled\": false}");
                    write(pf, "{\"enabled\": true}");
                    reload(dq, pf);
                    pressCard(mc, tab);
                    if (flag(dq, "isEnabledRaw") || flag(pf, "isEnabledRaw")) {
                        return "a click with Party Finder Overlay on should turn it off; reads " + flag(dq, "isEnabledRaw")
                                + " / " + flag(pf, "isEnabledRaw");
                    }
                    if (cardState(tab)) {
                        return "the card still reads ON with both off";
                    }
                    reload(dq, pf);
                    if (flag(dq, "isEnabledRaw") || flag(pf, "isEnabledRaw")) {
                        return "the switch was not saved: after a reload " + flag(dq, "isEnabledRaw") + " / "
                                + flag(pf, "isEnabledRaw");
                    }
                    pressCard(mc, tab);
                    if (!flag(dq, "isEnabledRaw") || flag(pf, "isEnabledRaw")) {
                        return "a click with both off should turn on Auto Requeue only; reads " + flag(dq, "isEnabledRaw")
                                + " / " + flag(pf, "isEnabledRaw");
                    }
                    if (!cardState(tab)) {
                        return "the card reads OFF with Auto Requeue on";
                    }
                    // Party Finder Overlay alone, Auto Requeue off: the overlay runs and the card reads ON.
                    Object pfCfg = Mod.cfg(pf);
                    Mod.call(pfCfg, "setEnabled", true);
                    Mod.call(pfCfg, "save");
                    Mod.call(Mod.cfg(dq), "setEnabled", false);
                    Mod.call(Mod.cfg(dq), "save");
                    reload(dq, pf);
                    if (flag(dq, "isEnabled") || !flag(pf, "isEnabled") || !cardState(tab)) {
                        return "Party Finder Overlay alone should run while Auto Requeue is off and the card read ON";
                    }
                    c.note("card clicks: PF alone -> both off -> Auto Requeue only; PF alone runs with Auto Requeue off");
                    return null;
                } catch (Exception e) {
                    return UiCase.describe(e);
                }
            });
            c.check(clicks == null, clicks);
        } finally {
            restore(configPath(dq), dqBefore);
            restore(configPath(pf), pfBefore);
            c.onClient(mc -> {
                reload(dq, pf);
                if (gate[0] != null) {
                    Mod.staticCall("util.SkyblockGate", "setEnabled", gate[0]);
                }
                McCompat.setScreen(mc, null);
                return null;
            });
        }
    }

    /** One tab that had an "as a whole" switch until 2026-10-15: its file keys and what its parts read. */
    private record Whole(String tab, String cfg, String master, String[] subs, String[] reads) {
    }

    private static final Whole[] WHOLES = {
            new Whole("DungeonAlertsTab", "dungeonalerts.DungeonAlertsConfig", "dungeonAlertsEnabled",
                    new String[]{"shadowAssassinEnabled", "roomAlertsEnabled"},
                    new String[]{"shadowAssassinOn", "roomAlertsOn"}),
            new Whole("F7SpotsTab", "f7spots.F7SpotsConfig", "f7SpotsEnabled",
                    new String[]{"walkWaypoints", "aimSpots"},
                    new String[]{"isWalkWaypointsEnabled", "isAimSpotsEnabled"}),
            new Whole("MaxorTab", "maxor.MaxorConfig", "maxorCrystalsEnabled",
                    new String[]{"spawnTimer", "placeTimer", "placeAlert", "activeCounter", "highlight"},
                    new String[]{"isSpawnTimerEnabled", "isPlaceTimerEnabled", "isPlaceAlertEnabled",
                            "isActiveCounterEnabled", "isHighlightEnabled"}),
            new Whole("P3NavTab", "p3nav.P3NavConfig", "p3NavEnabled",
                    new String[]{"gateHighlight", "terminalEsp", "deviceEsp"},
                    new String[]{"isGateHighlightEnabled", "isTerminalEspEnabled", "isDeviceEspEnabled"}),
            new Whole("ThornTab", "thorn.ThornConfig", "thornEnabled",
                    new String[]{"bearHud", "thornEsp", "stunSpots"},
                    new String[]{"isBearHudEnabled", "isThornEspEnabled", "isStunSpotsEnabled"}),
    };

    private static String json(Map<String, Boolean> keys) {
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<String, Boolean> e : keys.entrySet()) {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append('"').append(e.getKey()).append("\": ").append(e.getValue());
        }
        return sb.append('}').toString();
    }

    private static List<Boolean> reads(Whole w) {
        List<Boolean> out = new ArrayList<>();
        for (String r : w.reads()) {
            out.add(flag(w.cfg(), r));
        }
        return out;
    }

    /**
     * The five tabs that lost their "as a whole" switch (killer560, 2026-10-14): each part works by itself, the card is
     * OFF only when all parts are, and a file an older build wrote keeps its meaning - the whole switch ON leaves every
     * part as saved, the whole switch OFF loads every part OFF, and a file with only that switch turns nothing on.
     */
    static void independentMasters(UiCase c) throws Exception {
        Map<Path, byte[]> files = new LinkedHashMap<>();
        for (Whole w : WHOLES) {
            files.put(configPath(w.cfg()), snapshot(configPath(w.cfg())));
        }
        Object[] gate = new Object[1];
        try {
            gate[0] = c.onClient(mc -> {
                Object was = Mod.staticCall("util.SkyblockGate", "isEnabled");
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                return was;
            });
            for (Whole w : WHOLES) {
                String why = c.onClient(mc -> {
                    try {
                        Object tab = MenuACases.leaf(w.tab());
                        int n = w.subs().length;
                        Object ms = Mod.staticCall("gui.FeatureCards", "masterSwitch", tab, (Runnable) () -> { });
                        int parts = ms == null ? 0 : ((List<?>) Mod.call(ms, "parts")).size();
                        if (parts < 2) {
                            return w.tab() + ": the card stands for " + parts + " row(s)";
                        }
                        // 1. old file, whole switch ON and every part ON: all ON
                        Map<String, Boolean> k = new LinkedHashMap<>();
                        k.put(w.master(), true);
                        for (String s : w.subs()) {
                            k.put(s, true);
                        }
                        write(w.cfg(), json(k));
                        reload(w.cfg());
                        if (reads(w).contains(false)) {
                            return w.tab() + ": old file, whole ON, parts ON: the parts read " + reads(w);
                        }
                        if (!Boolean.TRUE.equals(cardState(tab))) {
                            return w.tab() + ": card OFF with every part on";
                        }
                        // 2. old file, whole switch OFF, parts saved ON: everything stays off
                        k.put(w.master(), false);
                        write(w.cfg(), json(k));
                        reload(w.cfg());
                        if (reads(w).contains(true)) {
                            return w.tab() + ": old file, whole OFF: the parts read " + reads(w) + ", wanted all off";
                        }
                        if (Boolean.TRUE.equals(cardState(tab))) {
                            return w.tab() + ": card ON though the old whole switch was OFF";
                        }
                        // 3. old file with only the whole switch, ON: nothing turns on by itself
                        write(w.cfg(), json(Map.of(w.master(), true)));
                        reload(w.cfg());
                        if (reads(w).contains(true) || Boolean.TRUE.equals(cardState(tab))) {
                            return w.tab() + ": a file with only the whole switch ON turned something on: " + reads(w);
                        }
                        // 4. each part on its own works, the others stay off, the card is ON
                        for (int i = 0; i < n; i++) {
                            Map<String, Boolean> one = new LinkedHashMap<>();
                            one.put(w.subs()[i], true);
                            write(w.cfg(), json(one));
                            reload(w.cfg());
                            List<Boolean> r = reads(w);
                            for (int j = 0; j < n; j++) {
                                if (r.get(j) != (i == j)) {
                                    return w.tab() + ": only " + w.subs()[i] + " on: " + w.reads()[j] + " reads " + r.get(j);
                                }
                            }
                            if (!Boolean.TRUE.equals(cardState(tab))) {
                                return w.tab() + ": card OFF with " + w.subs()[i] + " on and the rest off";
                            }
                        }
                        // 5. a save drops the old key and keeps the parts
                        Map<String, Boolean> two = new LinkedHashMap<>();
                        two.put(w.master(), true);
                        two.put(w.subs()[0], true);
                        write(w.cfg(), json(two));
                        reload(w.cfg());
                        Mod.call(Mod.cfg(w.cfg()), "save");
                        String saved = Files.readString(configPath(w.cfg()), StandardCharsets.UTF_8);
                        if (saved.contains(w.master())) {
                            return w.tab() + ": the old key '" + w.master() + "' is still written";
                        }
                        reload(w.cfg());
                        List<Boolean> kept = reads(w);
                        if (!kept.get(0) || kept.subList(1, n).contains(true)) {
                            return w.tab() + ": parts changed over a save and a load: " + kept;
                        }
                        // 6. the card switch: with everything off it turns the first part on, then everything off
                        write(w.cfg(), "{}");
                        reload(w.cfg());
                        pressCard(mc, tab);
                        List<Boolean> r = reads(w);
                        if (!r.get(0) || r.subList(1, n).contains(true)) {
                            return w.tab() + ": a click on the card with all off should turn only the first part on: " + r;
                        }
                        if (!cardState(tab)) {
                            return w.tab() + ": card OFF after its switch turned a part on";
                        }
                        pressCard(mc, tab);
                        if (reads(w).contains(true) || cardState(tab)) {
                            return w.tab() + ": a second click left " + reads(w) + " on";
                        }
                        c.note(w.tab() + ": " + n + " parts; old file ON keeps them, OFF turns all off, each works alone, card clicks ok");
                        return null;
                    } catch (Exception e) {
                        return w.tab() + ": " + UiCase.describe(e);
                    }
                });
                c.check(why == null, why);
            }
        } finally {
            for (Map.Entry<Path, byte[]> e : files.entrySet()) {
                restore(e.getKey(), e.getValue());
            }
            c.onClient(mc -> {
                for (Whole w : WHOLES) {
                    reload(w.cfg());
                }
                if (gate[0] != null) {
                    Mod.staticCall("util.SkyblockGate", "setEnabled", gate[0]);
                }
                McCompat.setScreen(mc, null);
                return null;
            });
        }
    }
}

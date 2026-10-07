package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * 393-ui-stat-bars: the Health and Mana Bars tab as redone on mod branch bars-tab (killer560, 2026-10-07: "redo the bar
 * section. It is way too complicated looking. Also no one needs to adjust scale there, they just use the edit hud menu
 * portion").
 *
 * <ul>
 *   <li>NO SCALE: no row of the tab, in any layout the case builds, mentions a scale.</li>
 *   <li>REACHABLE: from everything off, each parent is pressed for real (Stat Bars, a bar, a text, Hearts, an XP
 *       readout) and its children must appear, and with everything on every expected row is there.</li>
 *   <li>GROUPED (mod hud-editor-bars, killer560: "Make the menu have text next to text and bars next to bars"): every
 *       bar readout sits above every text readout, and each grid row holds two of the same kind.</li>
 *   <li>NO CLASSIC DISPLAY (same branch: "remove the classic display option, that is not needed"): no layout shows
 *       Classic Display or any of its five rows.</li>
 *   <li>WIRED: every toggle, pressed, flips the config field it always wrote (and is pressed back).</li>
 *   <li>TOOLTIPS: every button and slider of the tab has a non-empty {@code SettingTooltips} description under the
 *       tab's own scope; no two rows overlap with everything open.</li>
 *   <li>OLD SAVES: with {@code TESTKIT_BARS_EXPORT=<dir>} the case sets a 2.0x Health Bar scale the way the jar's own
 *       tab does (the old jar's Scale slider; the HUD editor's {@code HudConfig.setScale} on a jar without one), draws
 *       the bar and measures it in a screenshot, and writes the config files plus the measurement to {@code <dir>}. Run
 *       a NEW jar with {@code -PseedConfig=<dir>} and the case finds {@code bars-tab-export.properties} in its config,
 *       draws the bar from the seeded files untouched, and requires the same pixel width and height.</li>
 *   <li>PICTURES: the tab with Stat Bars and a few readouts on and every section open, at the default window and at
 *       2560x1440 GUI 3, every scroll position, kept as {@code 393-ui-stat-bars-<old|new>-<size>-<n>.png} ("old" when
 *       the tab still has a Scale slider).</li>
 * </ul>
 */
final class StatBarsCases {

    static final String TAB = "Health and Mana Bars";
    private static final String PS = "playerstats.PlayerStatsConfig";
    private static final String OH = "objecthider.ObjectHiderConfig";
    private static final String HUD = "hud.HudConfig";
    private static final String READOUT = "playerstats.StatElements$Readout";
    private static final String TAB_CLASS = "gui.tab.HealthAndManaBarsTab";
    private static final String EXPORT_FILE = "bars-tab-export.properties";
    private static final String HEALTH_BAR_ID = "statbar_health";
    /** A colour nothing else on a superflat HUD has: the measured bar is every pixel of exactly it. */
    private static final int MAGENTA = 0xFFFF00FF;

    /** Readout label -> enum constant (the readouts' labels are the enum's own). */
    private static final Map<String, String> READOUTS = new LinkedHashMap<>();
    /** Toggle label -> {config class, getter}. */
    private static final Map<String, String[]> TOGGLES = new LinkedHashMap<>();

    static {
        String[][] r = {{"Health Bar", "HEALTH_BAR"}, {"Mana Bar", "MANA_BAR"}, {"Defence Bar", "DEFENCE_BAR"},
                {"Other Resource Bar", "OTHER_BAR"}, {"Health Text", "HEALTH_TEXT"}, {"Mana Text", "MANA_TEXT"},
                {"Overflow Mana Text", "OVERFLOW_TEXT"}, {"Intelligence Text", "INTELLIGENCE_TEXT"},
                {"Defence Text", "DEFENCE_TEXT"}, {"Effective Health Text", "EFFECTIVE_HEALTH_TEXT"},
                {"Other Resource Text", "OTHER_TEXT"}, {"Vitality Bar", "VITALITY_BAR"}, {"XP Bar", "XP_BAR"},
                {"Vitality Text", "VITALITY_TEXT"}, {"XP Text", "XP_TEXT"}};
        for (String[] p : r) {
            READOUTS.put(p[0], p[1]);
        }
        String[][] t = {{"Stat Bars", PS, "isEnabledRaw"}, {"Show Value", PS, "isBarShowValue"},
                {"Hearts", PS, "isHideVanillaHearts"}, {"Hunger Bar", PS, "isHideVanillaHunger"},
                {"Armour Bar", PS, "isHideVanillaArmour"}, {"Air Bar", PS, "isHideVanillaAir"},
                {"Unhide Hearts In Rift", PS, "isShowHeartsInRift"}, {"Health", OH, "getHideHealthBarRaw"},
                {"Absorption", OH, "getHideAbsorptionHeartsRaw"}, {"Mount Health", OH, "getHideMountHealthBarRaw"},
                {"Regeneration Bounce", OH, "getHideRegenBounceRaw"}, {"Armour", OH, "getHideArmorBarRaw"},
                {"Hunger", OH, "getHideHungerBarRaw"}, {"XP Bar And Level", PS, "isHideXpBar"},
                {"Hypixel Stat Text", PS, "isHideHypixelStatText"}, {"Text Shadow", PS, "isTextShadow"},
                {"Hide Vanilla XP Bar", PS, "isHideXpBar"}};
        for (String[] p : t) {
            TOGGLES.put(p[0], new String[]{p[1], p[2]});
        }
    }

    /** Every row the tab must be able to show (label before any ':'), besides the readouts and their colours. */
    private static final String[] OTHER_ROWS = {"Bar Width", "Bar Height", "Background", "Absorption Colour"};
    /** Classic Display and its rows: gone from every layout since mod hud-editor-bars. */
    private static final String[] CLASSIC = {"Classic Display", "Show Health", "Show Mana", "Show Defense", "Show Text",
            "Show Bar"};

    private StatBarsCases() {
    }

    static void run(UiCase c, Deny deny) throws Exception {
        // A jar before mod hud-editor-bars has no Vitality / XP readouts: judge (and photograph) what it has, and say so.
        List<String> missing = new ArrayList<>();
        Object[] constants = R.cls(READOUT).getEnumConstants();
        READOUTS.entrySet().removeIf(e -> {
            for (Object k : constants) {
                if (((Enum<?>) k).name().equals(e.getValue())) {
                    return false;
                }
            }
            missing.add(e.getKey());
            return true;
        });
        if (!missing.isEmpty()) {
            c.problem("this jar has no readout for: " + missing);
        }
        Path configDir = c.onClient(mc -> mc.gameDirectory.toPath().resolve("config"));
        Map<Path, byte[]> saved = snapshot(c);
        int[] window = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            Path seeded = configDir.resolve(EXPORT_FILE);
            if (Files.exists(seeded)) {
                importCheck(c, seeded);
            }
            String export = System.getenv("TESTKIT_BARS_EXPORT");
            if (export != null && !export.isBlank()) {
                exportOld(c, configDir, Path.of(export));
            }
            layoutChecks(c, deny);
            shots(c, deny, "default");
            resize(c, 2560, 1440, 3);
            shots(c, deny, "2560x1440");
        } finally {
            try {
                c.onClient(mc -> {
                    McCompat.setScreen(mc, null);
                    mc.options.guiScale().set(gui);
                    return null;
                });
                c.ctx().getInput().resizeWindow(window[0], window[1]);
                c.ticks(5);
                c.onClient(mc -> {
                    mc.resizeGui();
                    return null;
                });
            } catch (Throwable t) {
                c.note("window restore: " + UiCase.describe(t));
            }
            restore(c, saved);
        }
    }

    // ---- layout ----------------------------------------------------------------------------------------------------

    private static void layoutChecks(UiCase c, Deny deny) throws Exception {
        allOff(c);
        ModScreenDriver d = open(c);
        Set<String> everSeen = new LinkedHashSet<>();
        boolean[] old = {false};
        c.onClient(mc -> {
            try {
                // From everything off.
                List<AbstractWidget> ws = ours(d);
                record(ws, everSeen, old);
                Set<String> l = labels(ws);
                c.note("everything off: " + l);
                for (String r : READOUTS.keySet()) {
                    absent(c, l, r, "Stat Bars is OFF");
                }
                for (String s : new String[]{"Hearts", "Text Shadow", "Bar Width", "Colour", "Hide Vanilla XP Bar"}) {
                    absent(c, l, s, "Stat Bars is OFF");
                }
                // Independent of Stat Bars: they must stay reachable with it off.
                for (String s : new String[]{"Health", "Absorption", "Mount Health", "Regeneration Bounce", "Armour",
                        "Hunger", "XP Bar And Level", "Hypixel Stat Text"}) {
                    present(c, l, s, "Stat Bars is OFF (it never depended on Stat Bars)");
                }

                press(c, d, "Stat Bars");
                ws = ours(d);
                record(ws, everSeen, old);
                l = labels(ws);
                for (String r : READOUTS.keySet()) {
                    present(c, l, r, "Stat Bars is ON");
                }
                for (String s : new String[]{"Hearts", "Hunger Bar", "Armour Bar", "Air Bar", "Text Shadow"}) {
                    present(c, l, s, "Stat Bars is ON");
                }
                for (String s : new String[]{"Bar Width", "Bar Height", "Show Value", "Background", "Colour",
                        "Absorption Colour", "Unhide Hearts In Rift", "Hide Vanilla XP Bar"}) {
                    absent(c, l, s, "no bar on, Hearts off, no XP readout on");
                }
                grouped(c, ws);

                press(c, d, "Health Bar");
                ws = ours(d);
                record(ws, everSeen, old);
                l = labels(ws);
                for (String s : new String[]{"Bar Width", "Bar Height", "Show Value", "Background",
                        "Absorption Colour"}) {
                    present(c, l, s, "Health Bar is ON");
                }
                expectColours(c, ws, 1, "Health Bar ON");

                press(c, d, "Mana Bar");
                press(c, d, "Health Bar");
                ws = ours(d);
                record(ws, everSeen, old);
                l = labels(ws);
                present(c, l, "Bar Width", "Mana Bar is ON");
                absent(c, l, "Absorption Colour", "Health Bar is OFF");
                expectColours(c, ws, 1, "Mana Bar ON only");

                int on = 1;
                for (String r : READOUTS.keySet()) {
                    if (!r.endsWith("Text")) {
                        continue;
                    }
                    press(c, d, r);
                    on++;
                    ws = ours(d);
                    record(ws, everSeen, old);
                    expectColours(c, ws, on, r + " ON");
                }

                press(c, d, "Hearts");
                l = labels(ours(d));
                present(c, l, "Unhide Hearts In Rift", "Hearts is ON");

                // Every text readout is on by now, XP Text with them; the XP Bar is off.
                l = labels(ours(d));
                present(c, l, "Hide Vanilla XP Bar", "XP Text is ON");
                press(c, d, "XP Text");
                l = labels(ours(d));
                absent(c, l, "Hide Vanilla XP Bar", "no XP readout is on");
                press(c, d, "XP Bar");
                l = labels(ours(d));
                present(c, l, "Hide Vanilla XP Bar", "XP Bar is ON");
                press(c, d, "XP Bar");
                press(c, d, "XP Text");

                // Everything on: every row.
                for (String r : READOUTS.keySet()) {
                    if (!(Boolean) Mod.call(Mod.cfg(PS), "isReadoutOn", Mod.enumValue(READOUT, READOUTS.get(r)))) {
                        press(c, d, r);
                    }
                }
                ws = ours(d);
                record(ws, everSeen, old);
                l = labels(ws);
                Set<String> want = new LinkedHashSet<>(READOUTS.keySet());
                want.addAll(TOGGLES.keySet());
                want.addAll(List.of(OTHER_ROWS));
                List<String> missing = new ArrayList<>();
                for (String s : want) {
                    if (!l.contains(s)) {
                        missing.add(s);
                    }
                }
                c.note("everything on: " + ws.size() + " rows " + l);
                if (!missing.isEmpty()) {
                    c.problem("rows missing with everything on: " + missing);
                }
                expectColours(c, ws, READOUTS.size(), "every readout ON");
                grouped(c, ws);
                tooltips(c, ws);
                overlaps(c, ws);
                wiring(c, d);
                // Every readout on (from the config, so a tab whose rows could not be pressed is judged too) and every
                // section header open (the old tab kept its rows, Scale sliders included, in dropdowns).
                for (String r : READOUTS.values()) {
                    Mod.call(Mod.cfg(PS), "setReadoutOn", Mod.enumValue(READOUT, r), true);
                }
                Mod.call(Mod.cfg(PS), "setEnabled", true);
                d.rebuild();
                d.openCollapsibles(deny, new ArrayList<>());
                record(ours(d), everSeen, old);
            } catch (Throwable t) {
                c.problem("layout: " + UiCase.describe(t));
            }
            McCompat.setScreen(mc, null);
            return null;
        });
        List<String> scale = everSeen.stream().filter(s -> s.toLowerCase(Locale.ROOT).contains("scale")).toList();
        c.note("labels seen across every layout: " + everSeen.size() + "; mentioning scale: " + scale);
        if (!scale.isEmpty()) {
            c.problem("the tab still has scale control(s): " + scale);
        }
        c.note("this tab " + (old[0] ? "HAS" : "has no") + " Scale slider (" + (old[0] ? "old" : "new") + " layout)");
        List<String> classic = new ArrayList<>();
        for (String s : everSeen) {
            if (List.of(CLASSIC).contains(key(s))) {
                classic.add(s);
            }
        }
        if (!classic.isEmpty()) {
            c.problem("Classic Display is still in the tab: " + classic);
        }
        closeSections(c);
    }

    /** Bars next to bars, text next to text: every bar readout row is above every text readout row, and a row (same
     *  y) of readouts never mixes the two kinds. */
    private static void grouped(UiCase c, List<AbstractWidget> ws) {
        int lowestBar = Integer.MIN_VALUE;
        int highestText = Integer.MAX_VALUE;
        Map<Integer, Set<Boolean>> kinds = new LinkedHashMap<>();
        int n = 0;
        for (AbstractWidget w : ws) {
            String k = key(ModScreenDriver.label(w));
            if (!READOUTS.containsKey(k)) {
                continue;
            }
            n++;
            boolean bar = k.endsWith("Bar");
            if (bar) {
                lowestBar = Math.max(lowestBar, w.getY());
            } else {
                highestText = Math.min(highestText, w.getY());
            }
            kinds.computeIfAbsent(w.getY(), y -> new LinkedHashSet<>()).add(bar);
        }
        List<Integer> mixed = new ArrayList<>();
        for (Map.Entry<Integer, Set<Boolean>> e : kinds.entrySet()) {
            if (e.getValue().size() > 1) {
                mixed.add(e.getKey());
            }
        }
        c.note("grouping: " + n + " readout rows, last bar at y " + lowestBar + ", first text at y " + highestText
                + ", rows mixing bar and text: " + mixed);
        if (lowestBar >= highestText) {
            c.problem("a bar readout (y " + lowestBar + ") sits at or below a text readout (y " + highestText
                    + ") - bars and texts are not grouped");
        }
        if (!mixed.isEmpty()) {
            c.problem("rows putting a bar beside a text (y " + mixed + ")");
        }
    }

    private static void record(List<AbstractWidget> ws, Set<String> seen, boolean[] old) {
        for (AbstractWidget w : ws) {
            String l = ModScreenDriver.label(w);
            seen.add(l);
            if (w instanceof AbstractSliderButton && l.startsWith("Scale")) {
                old[0] = true;
            }
        }
    }

    /** Rows our tab built (scoped to it by FolderTab), in build order. */
    @SuppressWarnings("unchecked")
    private static List<AbstractWidget> ours(ModScreenDriver d) {
        Map<AbstractWidget, String> scopes = (Map<AbstractWidget, String>) R.getStatic(R.cls("gui.SettingTooltips"),
                "SCOPES");
        List<AbstractWidget> out = new ArrayList<>();
        for (AbstractWidget w : d.content()) {
            if (TAB.equals(scopes.get(w))) {
                out.add(w);
            }
        }
        return out;
    }

    private static Set<String> labels(List<AbstractWidget> ws) {
        Set<String> out = new LinkedHashSet<>();
        for (AbstractWidget w : ws) {
            out.add(key(ModScreenDriver.label(w)));
        }
        return out;
    }

    /** "▶ Classic Display" -> "Classic Display", "Bar Width: 100" -> "Bar Width". */
    private static String key(String label) {
        String s = label;
        if (s.startsWith("▶ ") || s.startsWith("▼ ")) {
            s = s.substring(2);
        }
        int colon = s.indexOf(':');
        return (colon > 0 ? s.substring(0, colon) : s).trim();
    }

    private static void present(UiCase c, Set<String> labels, String row, String why) {
        if (!labels.contains(row)) {
            c.problem("'" + row + "' is not shown while " + why);
        }
    }

    private static void absent(UiCase c, Set<String> labels, String row, String why) {
        if (labels.contains(row)) {
            c.problem("'" + row + "' is shown while " + why + " - it should be hidden behind its parent");
        }
    }

    private static void expectColours(UiCase c, List<AbstractWidget> ws, int want, String when) {
        int n = 0;
        for (AbstractWidget w : ws) {
            if (key(ModScreenDriver.label(w)).equals("Colour")) {
                n++;
            }
        }
        if (n != want) {
            c.problem(when + ": " + n + " Colour swatch(es), expected " + want + " (one per readout that is on)");
        }
    }

    /** Press the row whose label key is {@code row} (real click on the widget) and rebuild. */
    private static void press(UiCase c, ModScreenDriver d, String row) throws Throwable {
        for (AbstractWidget w : ours(d)) {
            if (key(ModScreenDriver.label(w)).equals(row)) {
                ModScreenDriver.press(w);
                d.rebuild();
                return;
            }
        }
        c.problem("no '" + row + "' row to press");
    }

    private static void tooltips(UiCase c, List<AbstractWidget> ws) {
        List<String> none = new ArrayList<>();
        int checked = 0;
        for (AbstractWidget w : ws) {
            if (!(R.cls(ModScreenDriver.SETTINGS_BUTTON).isInstance(w) || w instanceof AbstractSliderButton)) {
                continue;
            }
            String label = ModScreenDriver.label(w);
            Object d = Mod.staticCall("gui.SettingTooltips", "describe", "Hud Elements", w, w.getMessage().getString());
            checked++;
            if (d == null || d.toString().isBlank()) {
                none.add(label);
            }
        }
        c.note("tooltips: " + checked + " rows checked, " + none.size() + " without one");
        if (!none.isEmpty()) {
            c.problem("rows with no tooltip: " + none);
        }
    }

    private static void overlaps(UiCase c, List<AbstractWidget> ws) {
        int pairs = 0;
        for (int a = 0; a < ws.size(); a++) {
            for (int b = a + 1; b < ws.size(); b++) {
                AbstractWidget p = ws.get(a);
                AbstractWidget q = ws.get(b);
                if (p.getX() < q.getX() + q.getWidth() && q.getX() < p.getX() + p.getWidth()
                        && p.getY() < q.getY() + q.getHeight() && q.getY() < p.getY() + p.getHeight()) {
                    pairs++;
                    c.problem("overlapping rows: '" + ModScreenDriver.label(p) + "' and '" + ModScreenDriver.label(q)
                            + "'");
                }
            }
        }
        c.note("everything open: " + pairs + " overlapping pair(s) among " + ws.size() + " rows");
    }

    /** Every toggle flips the config field it always wrote, and back. */
    private static void wiring(UiCase c, ModScreenDriver d) throws Throwable {
        int ok = 0;
        Map<String, String[]> all = new LinkedHashMap<>(TOGGLES);
        for (String r : READOUTS.keySet()) {
            all.put(r, null);
        }
        for (Map.Entry<String, String[]> e : all.entrySet()) {
            String row = e.getKey();
            if (row.equals("Stat Bars")) {
                continue; // flipping it off hides the rest; it is pressed for real at the top of the case
            }
            boolean before = read(e);
            press(c, d, row);
            boolean after = read(e);
            String shown = null;
            for (AbstractWidget w : ours(d)) {
                if (key(ModScreenDriver.label(w)).equals(row)) {
                    shown = ModScreenDriver.label(w);
                }
            }
            if (after == before) {
                c.problem("pressing '" + row + "' did not change " + (e.getValue() == null ? "its readout"
                        : e.getValue()[0] + "." + e.getValue()[1]));
            } else if (shown == null || !shown.endsWith(after ? "ON" : "OFF")) {
                c.problem("'" + row + "' reads '" + shown + "' after the press, the setting is " + after);
            } else {
                ok++;
            }
            press(c, d, row);
            if (read(e) != before) {
                c.problem("pressing '" + row + "' again did not put it back");
            }
        }
        c.note("wiring: " + ok + " of " + (all.size() - 1) + " toggles flip their own config field and back");
    }

    private static boolean read(Map.Entry<String, String[]> e) {
        if (e.getValue() == null) {
            return (Boolean) Mod.call(Mod.cfg(PS), "isReadoutOn", Mod.enumValue(READOUT, READOUTS.get(e.getKey())));
        }
        return (Boolean) Mod.call(Mod.cfg(e.getValue()[0]), e.getValue()[1]);
    }

    // ---- pictures -------------------------------------------------------------------------------------------------

    private static void shots(UiCase c, Deny deny, String size) throws Exception {
        c.onClient(mc -> {
            Object ps = Mod.cfg(PS);
            Mod.call(ps, "setEnabled", true);
            for (String r : new String[]{"HEALTH_BAR", "MANA_BAR", "HEALTH_TEXT", "MANA_TEXT"}) {
                Mod.call(ps, "setReadoutOn", Mod.enumValue(READOUT, r), true);
            }
            return null;
        });
        ModScreenDriver d = open(c);
        boolean[] old = {false};
        int[] scroll = c.onClient(mc -> {
            try {
                List<String> denied = new ArrayList<>();
                d.openCollapsibles(deny, denied);
                d.rebuild();
                record(ours(d), new LinkedHashSet<>(), old);
                return new int[]{d.maxScroll(), d.visibleContentHeight()};
            } catch (Throwable t) {
                throw new AssertionError(UiCase.describe(t), t);
            }
        });
        String prefix = old[0] ? "old" : "new";
        int step = Math.max(40, scroll[1] - 30);
        int n = 0;
        for (int off = 0; ; off += step) {
            int at = Math.min(off, scroll[0]);
            c.onClient(mc -> {
                d.scrollTo(at);
                try {
                    d.rebuild();
                } catch (Throwable t) {
                    throw new AssertionError(UiCase.describe(t), t);
                }
                return null;
            });
            c.ticks(4);
            String name = c.name() + "-" + prefix + "-" + size + "-" + (++n);
            Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
            Path kept = dev.testkit.harness.Report.screenshot(name, taken);
            c.note("picture " + name + " (scroll " + at + " of " + scroll[0] + ") -> " + (kept != null ? kept : taken));
            if (at >= scroll[0] || n >= 8) {
                break;
            }
        }
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        closeSections(c);
    }

    /** The tab's own session-only section flags, back to closed (whichever exist in this jar). */
    private static void closeSections(UiCase c) {
        c.onClient(mc -> {
            Class<?> tab = R.cls(TAB_CLASS);
            for (String f : new String[]{"hideOpen", "barsOpen", "textOpen", "classicOpen"}) {
                try {
                    R.setStatic(tab, f, false);
                } catch (AssertionError ignored) {
                    // not in this jar
                }
            }
            return null;
        });
    }

    private static ModScreenDriver open(UiCase c) {
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

    private static void resize(UiCase c, int w, int h, int gui) {
        c.ctx().getInput().resizeWindow(w, h);
        c.ticks(3);
        c.onClient(mc -> {
            mc.options.guiScale().set(gui);
            mc.resizeGui();
            return null;
        });
        c.ticks(3);
    }

    // ---- old saves -------------------------------------------------------------------------------------------------

    /** Set everything this case's layout check starts from: Stat Bars off, every readout off, Hearts off. */
    private static void allOff(UiCase c) {
        c.onClient(mc -> {
            Object ps = Mod.cfg(PS);
            Mod.call(ps, "setEnabled", false);
            for (String r : READOUTS.values()) {
                Mod.call(ps, "setReadoutOn", Mod.enumValue(READOUT, r), false);
            }
            Mod.call(ps, "setHideVanillaHearts", false);
            closeSectionsNow();
            return null;
        });
    }

    private static void closeSectionsNow() {
        Class<?> tab = R.cls(TAB_CLASS);
        for (String f : new String[]{"hideOpen", "barsOpen", "textOpen", "classicOpen"}) {
            try {
                R.setStatic(tab, f, false);
            } catch (AssertionError ignored) {
                // not in this jar
            }
        }
    }

    private static Path pathOf(String cfgClass) {
        return (Path) R.getStatic(R.cls(cfgClass), "CONFIG_PATH");
    }

    /**
     * Old-jar half: the Health Bar at 2.0x set through the tab's own Scale slider when the tab has one, magenta, no
     * number on it; saved; measured; files and measurement written to {@code out}.
     */
    private static void exportOld(UiCase c, Path configDir, Path out) throws Exception {
        allOff(c);
        c.onClient(mc -> {
            Object ps = Mod.cfg(PS);
            Mod.call(ps, "setEnabled", true);
            Mod.call(ps, "setReadoutOn", Mod.enumValue(READOUT, "HEALTH_BAR"), true);
            Mod.call(ps, "setReadoutColor", Mod.enumValue(READOUT, "HEALTH_BAR"), MAGENTA);
            Mod.call(ps, "setBarShowValue", false);
            Mod.call(ps, "save");
            Object hud = Mod.cfg(HUD);
            Mod.call(hud, "setPosition", HEALTH_BAR_ID, 200, 200);
            Mod.call(hud, "setScale", HEALTH_BAR_ID, 1.0f);
            Mod.call(hud, "save");
            // The old tab kept its Bars dropdown closed; open it so the Scale slider is built.
            try {
                R.setStatic(R.cls(TAB_CLASS), "barsOpen", true);
            } catch (AssertionError ignored) {
                // new tab: no dropdown
            }
            return null;
        });
        ModScreenDriver d = open(c);
        String how = c.onClient(mc -> {
            for (AbstractWidget w : ours(d)) {
                if (w instanceof AbstractSliderButton && ModScreenDriver.label(w).startsWith("Scale")) {
                    // The old slider: 0.5 + v * 3.5, snapped to 0.05. v = 1.5 / 3.5 is 2.0x.
                    R.set(w, "value", 1.5 / 3.5);
                    try {
                        R.call0(w, "applyValue");
                    } catch (Throwable t) {
                        throw new AssertionError(UiCase.describe(t), t);
                    }
                    return "the tab's own Scale slider (now '" + ModScreenDriver.label(w) + "' before refresh)";
                }
            }
            Object hud = Mod.cfg(HUD);
            Mod.call(hud, "setScale", HEALTH_BAR_ID, 2.0f);
            Mod.call(hud, "save");
            return "HudConfig.setScale (no Scale slider in this tab - the HUD editor's path)";
        });
        closeSections(c);
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        float scale = c.onClient(mc -> ((Number) Mod.call(Mod.cfg(HUD), "getScale", HEALTH_BAR_ID, 1.0f)).floatValue());
        c.note("export: Health Bar scale set to " + scale + " via " + how);
        c.check(Math.abs(scale - 2.0f) < 1e-4, "the export scale is " + scale + ", not 2.0");
        int[] box = measureBar(c, "export");
        Files.createDirectories(out);
        for (String cls : new String[]{PS, HUD}) {
            Path src = pathOf(cls);
            Path rel = configDir.relativize(src);
            Path dst = out.resolve(rel);
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            c.note("export: " + rel + " -> " + dst);
        }
        Properties p = new Properties();
        p.setProperty("width", String.valueOf(box[2] - box[0] + 1));
        p.setProperty("height", String.valueOf(box[3] - box[1] + 1));
        p.setProperty("scale", String.valueOf(scale));
        p.setProperty("window", c.onClient(mc -> mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight()
                + " gui " + mc.getWindow().getGuiScale()));
        p.setProperty("via", how);
        try (var o = Files.newBufferedWriter(out.resolve(EXPORT_FILE), StandardCharsets.UTF_8)) {
            p.store(o, "393-ui-stat-bars export");
        }
        c.note("export: bar " + p.getProperty("width") + "x" + p.getProperty("height") + " px at " + p.getProperty("window")
                + " -> " + out.resolve(EXPORT_FILE));
    }

    /** New-jar half: the seeded files, untouched, must draw the bar at the measured size. */
    private static void importCheck(UiCase c, Path props) throws Exception {
        Properties p = new Properties();
        try (var in = Files.newBufferedReader(props, StandardCharsets.UTF_8)) {
            p.load(in);
        }
        int wantW = Integer.parseInt(p.getProperty("width"));
        int wantH = Integer.parseInt(p.getProperty("height"));
        float scale = c.onClient(mc -> ((Number) Mod.call(Mod.cfg(HUD), "getScale", HEALTH_BAR_ID, 1.0f)).floatValue());
        boolean on = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(PS), "isEnabledRaw")
                && (Boolean) Mod.call(Mod.cfg(PS), "isReadoutOn", Mod.enumValue(READOUT, "HEALTH_BAR")));
        String window = c.onClient(mc -> mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight() + " gui "
                + mc.getWindow().getGuiScale());
        c.note("import: seeded save from " + p.getProperty("via") + " - bar " + wantW + "x" + wantH + " px at "
                + p.getProperty("window") + ", scale " + p.getProperty("scale") + "; this jar reads scale " + scale
                + ", Stat Bars + Health Bar on: " + on + ", window " + window);
        c.check(on, "the seeded save's Stat Bars / Health Bar did not load as ON");
        c.check(window.equals(p.getProperty("window")), "window differs from the export's (" + window + " vs "
                + p.getProperty("window") + ") - the pixel sizes are not comparable");
        if (Math.abs(scale - Float.parseFloat(p.getProperty("scale"))) > 1e-4) {
            c.problem("the seeded Health Bar scale reads " + scale + ", the old jar saved " + p.getProperty("scale"));
        }
        int[] box = measureBar(c, "import");
        int w = box[2] - box[0] + 1;
        int h = box[3] - box[1] + 1;
        c.note(String.format(Locale.ROOT, "import: old jar drew the bar %dx%d px, this jar draws it %dx%d px", wantW,
                wantH, w, h));
        if (w != wantW || h != wantH) {
            c.problem("the bar changed size on update: old " + wantW + "x" + wantH + " px, new " + w + "x" + h + " px");
        }
    }

    /** Draws the Health Bar full (1000/1000) on the HUD and returns its magenta bounding box {x0, y0, x1, y1}. */
    private static int[] measureBar(UiCase c, String label) throws Exception {
        Class<?> feature = R.cls("playerstats.PlayerStatsFeature");
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            McCompat.clearChatAndToasts(mc);
            R.setStatic(feature, "healthCur", 1000L);
            R.setStatic(feature, "healthMax", 1000L);
            return null;
        });
        c.ticks(6);
        String name = c.name() + "-bar-" + label;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        Path kept = dev.testkit.harness.Report.screenshot(name, taken);
        BufferedImage img = javax.imageio.ImageIO.read(taken.toFile());
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1, n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y) & 0xFFFFFF;
                if (rgb == (MAGENTA & 0xFFFFFF)) {
                    n++;
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
        }
        c.onClient(mc -> {
            R.setStatic(feature, "healthCur", -1L);
            R.setStatic(feature, "healthMax", -1L);
            return null;
        });
        c.check(n > 0, label + ": no magenta pixel in " + (kept != null ? kept : taken) + " - the bar did not draw");
        int area = (x1 - x0 + 1) * (y1 - y0 + 1);
        c.note(String.format(Locale.ROOT, "%s: bar box %d,%d..%d,%d = %dx%d px, %d of %d box pixels magenta -> %s",
                label, x0, y0, x1, y1, x1 - x0 + 1, y1 - y0 + 1, n, area, kept != null ? kept : taken));
        if (n != area) {
            c.problem(label + ": the magenta box is not solid (" + n + " of " + area + ") - something else is in it");
        }
        return new int[]{x0, y0, x1, y1};
    }

    // ---- config snapshot ----------------------------------------------------------------------------------------

    private static Map<Path, byte[]> snapshot(UiCase c) {
        Map<Path, byte[]> out = new LinkedHashMap<>();
        for (String cls : new String[]{PS, OH, HUD}) {
            Path p = c.onClient(mc -> pathOf(cls));
            try {
                out.put(p, Files.exists(p) ? Files.readAllBytes(p) : null);
            } catch (java.io.IOException e) {
                c.note("snapshot " + p + ": " + e);
            }
        }
        return out;
    }

    private static void restore(UiCase c, Map<Path, byte[]> saved) {
        try {
            c.onClient(mc -> {
                try {
                    for (Map.Entry<Path, byte[]> e : saved.entrySet()) {
                        if (e.getValue() == null) {
                            Files.deleteIfExists(e.getKey());
                        } else {
                            Files.write(e.getKey(), e.getValue());
                        }
                    }
                } catch (java.io.IOException e) {
                    throw new AssertionError(e);
                }
                for (String cls : new String[]{PS, OH, HUD}) {
                    Mod.staticCall(cls, "load");
                }
                closeSectionsNow();
                return null;
            });
        } catch (Throwable t) {
            c.note("config restore: " + UiCase.describe(t));
        }
    }
}

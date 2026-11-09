package dev.testkit.gametest.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.components.AbstractWidget;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fast/Auto Leap menu redesign (mod leapmenu-1102, killer560 2026-11-02: "first keep the main toggle. then have two
 * buttons. one for universal and one to set class. if you click universal then it takes you to the universal page if it
 * is set class then set class page. you should have a toggle to use universal or to use set class. right now everything
 * feels very hard to read").
 * <ul>
 *   <li>1102-ui-leapmenu-shots: pictures of the tab in the real menu drawer, in the test window (the narrow drawer) and at
 *       1920x1080 GUI 2 (the widest drawer), top and scrolled to the bottom; on a jar with the pages, the Universal and
 *       Set Class pages too. Runs on any jar (the "before" pictures came from mod main). {@code TESTKIT_LEAPMENU_SHOTS}
 *       copies them to a folder, {@code TESTKIT_LEAPMENU_TAG} names the set.</li>
 *   <li>1102-ui-leapmenu-pages: the front page's first rows are the Fast Leap switch, Targets and the two page buttons;
 *       each button opens its page (switch first, then "&lt; Back") and Back returns; Targets flips the highlight and is
 *       saved; the card switch is the Fast Leap switch on every page; Targets really changes who a leap goes to.</li>
 *   <li>1102-ui-leapmenu-oldconfig: a file saved by the old jar loads with every value the same and saves them back
 *       unchanged; Targets comes from whether per-class targets were set.</li>
 *   <li>1102-ui-leapmenu-search: the menu search finds a row on each sub-page, and opening the hit shows that page.</li>
 * </ul>
 */
final class LeapMenuCases {

    private static final String FLC = "fastleap.FastLeapConfig";
    private static final String FLS = "fastleap.FastLeapSection";
    private static final String TAB = "FastLeapTab";

    private LeapMenuCases() {
    }

    static boolean hasPages() {
        return Mod.has(FLS + "$Page");
    }

    private static Path file(UiCase c) {
        return c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-fastleap.json"));
    }

    /** Saves the config file and the party list, sets up a readable config (Fast Leap on, a few targets). */
    private static final class State implements AutoCloseable {
        final UiCase c;
        final Path file;
        final byte[] was;
        final java.util.Set<String> members;
        final List<String> membersWas;

        @SuppressWarnings("unchecked")
        State(UiCase c) throws Exception {
            this.c = c;
            file = file(c);
            was = Files.exists(file) ? Files.readAllBytes(file) : null;
            members = (java.util.Set<String>) Mod.field("leapmenu.PartyTracker", "MEMBERS");
            membersWas = new ArrayList<>(members);
            c.onClient(mc -> {
                Mod.staticCall(FLC, "load");
                Object cfg = Mod.staticCall(FLC, "getInstance");
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setTargetPickClass", Mod.enumValue(FLC + "$LeapTarget", "P1"),
                        Mod.enumValue("dungeonclass.DungeonClass", "HEALER"));
                Mod.call(cfg, "setTargetPickName", Mod.enumValue(FLC + "$LeapTarget", "STORM_DEATH"), "LeapMate");
                Mod.call(cfg, "setLeapFast", Mod.enumValue(FLC + "$LeapGroup", "P1"), true);
                Mod.call(cfg, "setLeapAuto", Mod.enumValue(FLC + "$LeapGroup", "P3"), true);
                for (Object t : Mod.cls(FLC + "$LeapTarget").getEnumConstants()) {
                    for (Object k : Mod.cls("dungeonclass.DungeonClass").getEnumConstants()) {
                        Mod.call(cfg, "clearClassTarget", t, k);
                    }
                }
                members.clear();
                members.add("LeapMate");
                Mod.staticCall("dungeonclass.ClassOverrides", "set", "LeapMate", Mod.enumValue("dungeonclass.DungeonClass", "ARCHER"));
                if (hasPages()) {
                    Mod.staticCall(FLS, "openPage", Mod.enumValue(FLS + "$Page", "FRONT"));
                }
                return null;
            });
        }

        @Override
        public void close() throws Exception {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            if (was != null) {
                Files.write(file, was);
            } else {
                Files.deleteIfExists(file);
            }
            c.onClient(mc -> {
                Mod.staticCall(FLC, "load");
                Mod.staticCall("dungeonclass.ClassOverrides", "clear", "LeapMate");
                members.clear();
                members.addAll(membersWas);
                if (hasPages()) {
                    Mod.staticCall(FLS, "openPage", Mod.enumValue(FLS + "$Page", "FRONT"));
                }
                return null;
            });
        }
    }

    /** The real menu with the Fast/Auto Leap drawer open. */
    private static ModScreenDriver open(UiCase c) {
        return c.onClient(mc -> {
            try {
                ModScreenDriver d = new ModScreenDriver(mc);
                Object leaf = d.leaf(TAB);
                if (leaf == null) {
                    return null;
                }
                McCompat.setScreen(mc, d.screen);
                d.open(leaf);
                d.rebuild();
                return d;
            } catch (Throwable t) {
                throw new AssertionError("could not open the Fast/Auto Leap drawer: " + UiCase.describe(t), t);
            }
        });
    }

    private static List<AbstractWidget> rows(UiCase c, ModScreenDriver d) {
        return c.onClient(mc -> {
            try {
                d.rebuild();
            } catch (Throwable t) {
                throw new AssertionError(UiCase.describe(t), t);
            }
            List<AbstractWidget> out = new ArrayList<>(d.content());
            out.sort(java.util.Comparator.comparingInt(AbstractWidget::getY).thenComparingInt(AbstractWidget::getX));
            return out;
        });
    }

    private static List<String> labels(List<AbstractWidget> ws) {
        List<String> out = new ArrayList<>();
        for (AbstractWidget w : ws) {
            out.add(ModScreenDriver.label(w).trim());
        }
        return out;
    }

    private static AbstractWidget find(List<AbstractWidget> ws, String prefix) {
        for (AbstractWidget w : ws) {
            if (ModScreenDriver.label(w).trim().startsWith(prefix)) {
                return w;
            }
        }
        return null;
    }

    private static void press(UiCase c, ModScreenDriver d, AbstractWidget w) {
        c.onClient(mc -> {
            ModScreenDriver.press(w);
            return null;
        });
        c.ticks(2);
    }

    private static String page(UiCase c) {
        return c.onClient(mc -> String.valueOf(Mod.staticCall(FLS, "page")));
    }

    // ---- 1102-ui-leapmenu-shots ---------------------------------------------------------------------------------

    static void shots(UiCase c) throws Exception {
        if (!Mod.isCheat()) {
            c.skipCase("legit jar: Fast/Auto Leap is cheat-only (1102-ui-leapmenu-pages checks the tab is absent)");
            return;
        }
        int[] oldWindow = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        String tag = System.getenv().getOrDefault("TESTKIT_LEAPMENU_TAG", hasPages() ? "after" : "before");
        Object hud = Mod.cfg("hud.HudConfig");
        boolean oldAuto = (Boolean) Mod.call(hud, "isAutoScale");
        int pictures = 0;
        try (State st = new State(c); AutoCloseable all = MenuLayoutCases.allTested(c)) {
            // narrow: the test window under Auto Scale (an 854-wide canvas, the narrowest docked drawer, 362);
            // normal: 1920x1080 at GUI 2 with Auto Scale off (a 960-wide screen, the widest drawer)
            for (String size : new String[]{"narrow", "normal"}) {
                if (size.equals("normal")) {
                    c.onClient(mc -> {
                        McCompat.setScreen(mc, null);
                        Mod.call(hud, "setAutoScale", false);
                        return null;
                    });
                    c.ctx().getInput().resizeWindow(1920, 1080);
                    c.ticks(3);
                }
                c.onClient(mc -> {
                    mc.options.guiScale().set(2);
                    mc.resizeGui();
                    return null;
                });
                c.ticks(3);
                ModScreenDriver d = open(c);
                c.check(d != null, "no Fast/Auto Leap tab in the menu");
                if (d == null) {
                    return;
                }
                int drawerW = c.onClient(mc -> (Integer) Mod.field(d.screen, "drawerW"));
                c.note(size + ": window " + c.onClient(mc -> mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight())
                        + ", GUI width " + d.screen.width + ", drawer " + drawerW);
                pictures += pageShots(c, d, tag + "-" + size + "-front");
                if (hasPages()) {
                    for (String[] p : new String[][]{{"Universal Targets", "universal"}, {"Set Class Targets", "setclass"}}) {
                        AbstractWidget b = find(rows(c, d), p[0]);
                        c.check(b != null, "no " + p[0] + " button");
                        if (b == null) {
                            continue;
                        }
                        press(c, d, b);
                        pictures += pageShots(c, d, tag + "-" + size + "-" + p[1]);
                        AbstractWidget back = find(rows(c, d), "< Back");
                        c.check(back != null, p[0] + ": no < Back");
                        if (back != null) {
                            press(c, d, back);
                        }
                    }
                }
            }
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.call(hud, "setAutoScale", oldAuto);
                mc.options.guiScale().set(oldGui);
                return null;
            });
            c.ctx().getInput().resizeWindow(oldWindow[0], oldWindow[1]);
            c.ticks(3);
            c.onClient(mc -> {
                mc.resizeGui();
                return null;
            });
        }
        c.check(pictures > 0, "no pictures taken");
        c.note(pictures + " picture(s), set " + tag);
    }

    /** The page at the top and (when it scrolls) at the bottom; also checks no row's text runs past its own box. */
    private static int pageShots(UiCase c, ModScreenDriver d, String name) throws Exception {
        c.onClient(mc -> {
            d.scrollTo(0);
            return null;
        });
        List<AbstractWidget> ws = rows(c, d);
        c.note(name + " rows: " + labels(ws));
        List<String> cut = c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            for (AbstractWidget w : ws) {
                if (w instanceof net.minecraft.client.gui.components.StringWidget) {
                    int tw = mc.font.width(w.getMessage());
                    if (tw > w.getWidth() + 2) {
                        out.add("'" + ModScreenDriver.label(w) + "' " + tw + " px in " + w.getWidth());
                    }
                }
            }
            return out;
        });
        if (hasPages()) {
            c.check(cut.isEmpty(), name + ": text cut off: " + cut);
        } else if (!cut.isEmpty()) {
            c.note(name + ": text cut off (old jar): " + cut);
        }
        shot(c, name + "-top");
        int max = c.onClient(mc -> d.maxScroll());
        if (max <= 0) {
            return 1;
        }
        c.onClient(mc -> {
            d.scrollTo(max);
            return null;
        });
        rows(c, d);
        shot(c, name + "-bottom");
        c.onClient(mc -> {
            d.scrollTo(0);
            return null;
        });
        return 2;
    }

    private static void shot(UiCase c, String suffix) throws Exception {
        c.ticks(3);
        String name = c.name() + "-" + suffix;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        dev.testkit.harness.Report.screenshot(name, taken);
        String dir = System.getenv("TESTKIT_LEAPMENU_SHOTS");
        if (dir != null && !dir.isBlank()) {
            Path out = Path.of(dir).resolve(suffix + ".png");
            Files.createDirectories(out.getParent());
            Files.copy(taken, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ---- 1102-ui-leapmenu-pages ---------------------------------------------------------------------------------

    static void pages(UiCase c) throws Exception {
        if (!Mod.isCheat()) {
            ModScreenDriver d = c.onClient(mc -> {
                try {
                    return new ModScreenDriver(mc);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            boolean listed = c.onClient(mc -> d.leaf(TAB) != null);
            c.check(!listed, "legit jar: the Fast/Auto Leap tab is in the menu");
            c.note("legit jar: no Fast/Auto Leap tab in the menu (" + (listed ? "listed!" : "absent") + ")");
            return;
        }
        if (!hasPages()) {
            c.skipCase("this jar has no Fast/Auto Leap pages (mod leapmenu-1102)");
            return;
        }
        try (State st = new State(c); AutoCloseable all = MenuLayoutCases.allTested(c)) {
            c.onClient(mc -> {
                Mod.call(Mod.staticCall(FLC, "getInstance"), "setUseClassTargets", false);
                return null;
            });
            ModScreenDriver d = open(c);
            c.check(d != null, "no Fast/Auto Leap tab in the menu");
            if (d == null) {
                return;
            }
            // ---- front page: switch, Targets, the two page buttons, in that order ----
            List<AbstractWidget> ws = rows(c, d);
            List<String> ls = labels(ws);
            c.note("front page: " + ls);
            List<String> controls = new ArrayList<>();
            for (AbstractWidget w : ws) {
                if (!(w instanceof net.minecraft.client.gui.components.StringWidget)) {
                    controls.add(ModScreenDriver.label(w).trim());
                }
            }
            c.check(controls.size() >= 4 && controls.get(0).equals("Fast Leap: ON") && controls.get(1).equals("Targets: Universal")
                            && controls.get(2).equals("Universal Targets (in use) >") && controls.get(3).equals("Set Class Targets >"),
                    "front page starts " + controls.subList(0, Math.min(4, controls.size())));
            AbstractWidget uni = find(ws, "Universal Targets");
            AbstractWidget cls = find(ws, "Set Class Targets");
            boolean[] lit = c.onClient(mc -> new boolean[]{(Boolean) Mod.field(uni, "primary"), (Boolean) Mod.field(cls, "primary")});
            c.check(lit[0] && !lit[1], "with Targets Universal: Universal lit " + lit[0] + ", Set Class lit " + lit[1]);
            c.check(uni.getY() == cls.getY() || uni.getY() < cls.getY(), "the page buttons are out of order");
            c.check(ls.stream().noneMatch(l -> l.startsWith("Crystal:") || l.startsWith("S1:")),
                    "a target row is on the front page: " + ls);
            // untestbugs-1109: each leap's Off/Fast/Auto sits on the Universal / Class pages, not on the front page.
            c.check(ls.stream().noneMatch(l -> l.startsWith("Crystal Leap")) && ls.contains("Block Inputs: OFF")
                            && find(ws, "Return To") != null,
                    "the front page has leap switches or lost Click Behavior: " + ls);
            if (oneSwitch()) {
                // round 3 (killer560, 2026-11-03): one Off / Fast / Auto row per leap
                long rowsN = ls.stream().filter(l -> l.matches(".+ Leap: (Off|Fast|Auto)")).count();
                c.check(rowsN == 0 && ls.stream().noneMatch(l -> l.startsWith("Fast: ") || l.startsWith("Auto: ")),
                        rowsN + " leap rows on the front page (want 0: they are on the Universal page): " + ls);
                // the per-leap rows live on the Universal page now (untestbugs-1109)
                press(c, d, uni);
                List<String> uls = labels(rows(c, d));
                c.check(uls.contains("Crystal Leap: Fast") && uls.contains("P3 Leap: Auto") && uls.contains("Relic Leap: Off"),
                        "Crystal (set Fast), P3 (set Auto) and Relic (unset, Off) read wrong: " + uls);
                List<String> walk = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    press(c, d, find(rows(c, d), "Door Opener Leap:"));
                    String[] stt = c.onClient(mc -> {
                        Object cfg = Mod.staticCall(FLC, "getInstance");
                        Object g = Mod.enumValue(FLC + "$LeapGroup", "DOOR");
                        return new String[]{String.valueOf(Mod.call(cfg, "getLeapMode", g)),
                                Mod.call(cfg, "isLeapFast", g) + "/" + Mod.call(cfg, "isLeapAuto", g)};
                    });
                    JsonObject saved = JsonParser.parseString(Files.readString(st.file)).getAsJsonObject()
                            .getAsJsonObject("groups").getAsJsonObject("DOOR");
                    walk.add(ModScreenDriver.label(find(rows(c, d), "Door Opener Leap:")) + " " + stt[0] + " " + stt[1]
                            + " saved " + saved.get("fast") + "/" + saved.get("auto"));
                }
                c.note("Door Opener row presses (forward): " + walk);
                c.check(walk.get(0).equals("Door Opener Leap: Fast FAST true/false saved true/false")
                                && walk.get(1).equals("Door Opener Leap: Auto AUTO false/true saved false/true")
                                && walk.get(2).equals("Door Opener Leap: Off OFF false/false saved false/false"),
                        "Door Opener did not step Off -> Fast -> Auto -> Off (saved): " + walk);
                List<AbstractWidget> now = rows(c, d);
                AbstractWidget doorRow = find(now, "Door Opener Leap:");
                AbstractWidget blood = find(now, "After Blood Off");
                c.check(blood != null && blood.getY() > doorRow.getY(), "After Blood Off is not under the Door Opener row");
                // right click steps back: Off -> Auto
                c.onClient(mc -> {
                    int[] cb = (int[]) Mod.call(doorRow, "controlBounds");
                    double px = cb[0] + cb[2] * 0.5;
                    double py = cb[1] + cb[3] / 2.0;
                    doorRow.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(px, py,
                            new net.minecraft.client.input.MouseButtonInfo(dev.testkit.compat.McKeys.mouse(1), 0)), false);
                    return null;
                });
                String back = c.onClient(mc -> String.valueOf(Mod.call(Mod.staticCall(FLC, "getInstance"), "getLeapMode",
                        Mod.enumValue(FLC + "$LeapGroup", "DOOR"))));
                c.note("Door Opener after a right click from Off: " + back);
                c.check("AUTO".equals(back), "a right click on Door Opener: Off gave " + back + ", want Auto (back, wrapping)");
                press(c, d, find(rows(c, d), "< Back"));
            }
            cardSwitch(c, d, "front");

            // ---- Universal page and Back ----
            press(c, d, uni);
            c.check("UNIVERSAL".equals(page(c)), "the Universal button opened " + page(c));
            ws = rows(c, d);
            ls = labels(ws);
            c.note("Universal page: " + ls);
            c.check(ls.size() > 2 && ls.get(0).equals("Fast Leap: ON") && ls.get(1).equals("< Back"),
                    "the Universal page starts " + ls.subList(0, Math.min(3, ls.size())));
            c.check(ls.stream().anyMatch(l -> l.startsWith("Universal Targets (in use)")), "the Universal page's title is not 'in use'");
            c.check(ls.contains("Crystal: Healer") && ls.stream().anyMatch(l -> l.startsWith("Storm Death: LeapMate"))
                            && ls.stream().filter(l -> l.matches("S[1-4]: .*")).count() == 4,
                    "the Universal page's targets: " + ls);
            cardSwitch(c, d, "Universal");
            press(c, d, find(ws, "< Back"));
            c.check("FRONT".equals(page(c)), "Back from Universal left " + page(c));
            c.check(find(rows(c, d), "Targets:") != null, "Back did not show the front page");

            // ---- Targets flips the highlight and is saved ----
            press(c, d, find(rows(c, d), "Targets:"));
            ws = rows(c, d);
            AbstractWidget uni2 = find(ws, "Universal Targets");
            AbstractWidget cls2 = find(ws, "Set Class Targets");
            boolean[] lit2 = c.onClient(mc -> new boolean[]{(Boolean) Mod.field(uni2, "primary"), (Boolean) Mod.field(cls2, "primary")});
            boolean saved = JsonParser.parseString(Files.readString(st.file)).getAsJsonObject().get("useClassTargets").getAsBoolean();
            c.note("after a Targets press: " + labels(ws).subList(0, 4) + "; saved useClassTargets " + saved);
            c.check(find(ws, "Targets: Set Class") != null && !lit2[0] && lit2[1]
                            && ModScreenDriver.label(cls2).equals("Set Class Targets (in use) >")
                            && ModScreenDriver.label(uni2).equals("Universal Targets >"),
                    "Targets Set Class did not move the highlight: " + labels(ws).subList(0, 4));
            c.check(saved, "Targets: Set Class was not saved (useClassTargets false in the file)");

            // ---- Set Class page: class picker, same structure, Back ----
            press(c, d, cls2);
            c.check("CLASS".equals(page(c)), "the Set Class button opened " + page(c));
            ws = rows(c, d);
            ls = labels(ws);
            c.note("Set Class page: " + ls);
            c.check(ls.size() > 3 && ls.get(0).equals("Fast Leap: ON") && ls.get(1).equals("< Back")
                            && ls.stream().anyMatch(l -> l.startsWith("Set Class Targets (in use)")),
                    "the Set Class page starts " + ls.subList(0, Math.min(3, ls.size())));
            AbstractWidget play = find(ws, "When You Play:");
            c.check(play != null, "no When You Play row on the Set Class page");
            c.check(ls.stream().filter(l -> l.endsWith(": Same as Universal")).count() == 14,
                    "want 14 'Same as Universal' targets (10 leaps + S1-S4): " + ls);
            String before = ModScreenDriver.label(play);
            press(c, d, play);
            String after = ModScreenDriver.label(find(rows(c, d), "When You Play:"));
            c.note("When You Play: " + before + " -> " + after);
            c.check(!before.equals(after), "When You Play did not step: " + before);
            // a Crystal pick for this class is saved under that class
            AbstractWidget crystal = find(rows(c, d), "Crystal:");
            press(c, d, crystal);
            String mine = after.substring(after.indexOf(':') + 1).trim().toUpperCase(java.util.Locale.ROOT);
            String pick = c.onClient(mc -> String.valueOf(Mod.call(Mod.staticCall(FLC, "getInstance"), "getClassTarget",
                    Mod.enumValue(FLC + "$LeapTarget", "P1"), Mod.enumValue("dungeonclass.DungeonClass", mine))));
            c.note("Crystal as " + mine + " after one press: " + pick + " (" + ModScreenDriver.label(find(rows(c, d), "Crystal:")) + ")");
            c.check(pick.contains("clazz=MAGE"), "a press on Crystal: Same as Universal for " + mine + " gave " + pick + ", want Mage");
            cardSwitch(c, d, "Set Class");
            press(c, d, find(rows(c, d), "< Back"));
            c.check("FRONT".equals(page(c)), "Back from Set Class left " + page(c));

            // ---- Targets decides whether the per-class pick is used ----
            String self = c.onClient(mc -> mc.player == null ? null : mc.player.getGameProfile().name());
            c.note("leap resolution is covered by 983 (Set Class used / Universal ignores the pick); player " + self);
            // ---- turning Fast Leap off on a sub-page goes back to the front page ----
            press(c, d, find(rows(c, d), "Universal Targets"));
            press(c, d, find(rows(c, d), "Fast Leap:"));
            List<String> off = labels(rows(c, d));
            c.check("FRONT".equals(page(c)) && off.get(0).equals("Fast Leap: OFF") && find(rows(c, d), "< Back") == null,
                    "Fast Leap off on the Universal page left " + page(c) + " " + off);
        }
    }

    /** The drawer's card switch stands for the Fast Leap switch on this page. */
    private static void cardSwitch(UiCase c, ModScreenDriver d, String where) {
        String m = c.onClient(mc -> {
            Object ms = Mod.field(d.screen, "drawerMaster");
            if (ms == null) {
                return "none";
            }
            @SuppressWarnings("unchecked")
            List<Object> parts = (List<Object>) Mod.call(ms, "parts");
            StringBuilder sb = new StringBuilder();
            for (Object p : parts) {
                sb.append(ModScreenDriver.label((AbstractWidget) p)).append(';');
            }
            return sb.toString();
        });
        // Leap Takes Target's Look (leaprot-1104, cheat) is its own feature on the front page, so the card stands for it too.
        String rest = m.startsWith("Fast Leap: ") ? m.substring(m.indexOf(';') + 1) : "?";
        c.check(rest.isEmpty() || rest.startsWith("Leap Takes Target's Look: ") && rest.indexOf(';') == rest.length() - 1,
                where + " page: the card switch stands for " + m + ", want the Fast Leap switch (and Leap Takes Target's Look)");
    }

    // ---- 1102-ui-leapmenu-oldconfig -----------------------------------------------------------------------------

    /** The file mod main (before leapmenu-1102) saves, with every setting off its default. */
    private static final String OLD = "{\"enabled\":true,\"targetMode\":\"POSMSG\",\"clickDelayMs\":300,\"timeoutMs\":2750,"
            + "\"blockInputs\":true,\"fastMode\":true,\"returnToSlot\":4,\"disableAfterBloodOpen\":true,\"onlyWhenGateBlown\":true,"
            + "\"groups\":{\"DOOR\":{\"fast\":true,\"auto\":false},\"P1\":{\"fast\":false,\"auto\":true},"
            + "\"P3\":{\"fast\":true,\"auto\":true},\"RELIC\":{\"fast\":false,\"auto\":false}},"
            + "\"targets\":{\"P1\":{\"name\":\"Bob\",\"class\":\"HEALER\",\"keywords\":\"p2, storm, x\",\"byClass\":false,"
            + "\"classTargets\":{\"MAGE\":{\"name\":\"Alice\"},\"TANK\":{\"class\":\"ARCHER\"}}},"
            + "\"S1\":{\"name\":\"\",\"class\":\"BERSERKER\",\"keywords\":\"ee2\",\"byClass\":true,\"classTargets\":{}},"
            + "\"RELIC\":{\"name\":\"Carol\",\"class\":\"\",\"keywords\":\"relic\",\"byClass\":false,\"classTargets\":{}}}}";

    static void oldConfig(UiCase c) throws Exception {
        if (!Mod.isCheat()) {
            c.skipCase("legit jar: Fast/Auto Leap is cheat-only");
            return;
        }
        if (!hasPages()) {
            c.skipCase("this jar has no Targets setting (mod leapmenu-1102)");
            return;
        }
        try (State st = new State(c)) {
            Files.createDirectories(st.file.getParent());
            Files.writeString(st.file, OLD);
            boolean use = c.onClient(mc -> {
                Mod.staticCall(FLC, "load");
                Object cfg = Mod.staticCall(FLC, "getInstance");
                boolean u = (Boolean) Mod.call(cfg, "isUseClassTargets");
                Mod.call(cfg, "save");
                return u;
            });
            JsonObject old = JsonParser.parseString(OLD).getAsJsonObject();
            JsonObject now = JsonParser.parseString(Files.readString(st.file)).getAsJsonObject();
            List<String> diff = new ArrayList<>();
            if (oneSwitch()) {
                // the leap modes are checked combination by combination below (one switch since round 2)
                old.remove("groups");
            }
            // untestbugs-1109: no leap goes by a player's name (a name with no known class is dropped, its saved class stays),
            // and the one global targetMode is each leap's own "mode" now.
            old.remove("targetMode");
            for (String t : new String[]{"P1", "RELIC"}) {
                JsonObject o = old.getAsJsonObject("targets").getAsJsonObject(t);
                o.addProperty("name", "");
                o.addProperty("byClass", true);
            }
            old.getAsJsonObject("targets").getAsJsonObject("P1").getAsJsonObject("classTargets").remove("MAGE");
            for (String key : new ArrayList<>(now.getAsJsonObject("targets").keySet())) {
                now.getAsJsonObject("targets").getAsJsonObject(key).remove("mode");
            }
            compare("", old, now, diff);
            c.note("old file with per-class targets: Targets Set Class " + use + "; keys that changed after load + save: " + diff);
            c.check(use, "an old file with per-class targets set loads as Targets Universal (it applied them before)");
            c.check(diff.isEmpty(), "an old file's values changed on load + save: " + diff);
            c.check(now.has("useClassTargets") && now.get("useClassTargets").getAsBoolean(), "useClassTargets not saved: " + now);
            // the getters read the same values the file holds
            String vals = c.onClient(mc -> {
                Object cfg = Mod.staticCall(FLC, "getInstance");
                Object p1 = Mod.enumValue(FLC + "$LeapTarget", "P1");
                return Mod.call(cfg, "getTargetMode") + "/" + Mod.call(cfg, "getClickDelayMs") + "/" + Mod.call(cfg, "getTimeoutMs")
                        + "/" + Mod.call(cfg, "getReturnToSlot") + "/" + Mod.call(cfg, "isTargetByClass", p1) + "/"
                        + Mod.call(cfg, "getTargetName", p1) + "/" + Mod.call(cfg, "getClassTarget", p1,
                        Mod.enumValue("dungeonclass.DungeonClass", "TANK"));
            });
            c.note("read back: " + vals);
            c.check(vals.equals("POSMSG/300/2750/4/true//ClassPick[clazz=ARCHER, name=null]"), "old values read back as " + vals);
            // no per-class targets: Universal; an explicit false stays false whatever is set
            Files.writeString(st.file, OLD.replace("\"classTargets\":{\"MAGE\":{\"name\":\"Alice\"},\"TANK\":{\"class\":\"ARCHER\"}}",
                    "\"classTargets\":{}"));
            boolean none = c.onClient(mc -> {
                Mod.staticCall(FLC, "load");
                return (Boolean) Mod.call(Mod.staticCall(FLC, "getInstance"), "isUseClassTargets");
            });
            Files.writeString(st.file, OLD.replace("{\"enabled\":true,", "{\"enabled\":true,\"useClassTargets\":false,"));
            boolean explicit = c.onClient(mc -> {
                Mod.staticCall(FLC, "load");
                return (Boolean) Mod.call(Mod.staticCall(FLC, "getInstance"), "isUseClassTargets");
            });
            c.note("old file without per-class targets: Set Class " + none + "; saved false with targets: Set Class " + explicit);
            c.check(!none, "an old file with no per-class targets loads as Set Class");
            c.check(!explicit, "a saved Targets Universal came back as Set Class");
            if (oneSwitch()) {
                leapModeMigration(c, st.file);
            }
        }
    }

    /** Round 2 (one switch per leap: on = Auto, off = Fast). */
    static boolean oneSwitch() {
        for (java.lang.reflect.Method m : Mod.cls(FLC).getMethods()) {
            if (m.getName().equals("getLeapMode")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every old per-leap combination loads as the one switch and saves back consistently: the two-switch files (2026-10-13
     * to 11-02) by their auto key, the Mode files (before 10-13) Auto only when the leap was on in Mode Auto. Both off
     * (the leap unused) reads Fast.
     */
    private static void leapModeMigration(UiCase c, Path file) throws Exception {
        String[][] combos = {
                // groups JSON for P1, want auto
                {"{\"fast\":false,\"auto\":false}", "OFF"},
                {"{\"fast\":true,\"auto\":false}", "FAST"},
                {"{\"fast\":false,\"auto\":true}", "AUTO"},
                {"{\"fast\":true,\"auto\":true}", "AUTO"},
                {"{\"enabled\":true,\"auto\":true}", "AUTO"},
                {"{\"enabled\":true,\"auto\":false}", "FAST"},
                {"{\"enabled\":false,\"auto\":true}", "OFF"},
                {"{\"enabled\":false,\"auto\":false}", "OFF"},
        };
        List<String> bad = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        Object p1 = Mod.enumValue(FLC + "$LeapGroup", "P1");
        for (String[] k : combos) {
            Files.writeString(file, "{\"enabled\":true,\"groups\":{\"P1\":" + k[0] + "}}");
            String[] st = c.onClient(mc -> {
                Mod.staticCall(FLC, "load");
                Object cfg = Mod.staticCall(FLC, "getInstance");
                Mod.call(cfg, "save");
                return new String[]{String.valueOf(Mod.call(cfg, "getLeapMode", p1)),
                        Mod.call(cfg, "isLeapFast", p1) + "/" + Mod.call(cfg, "isLeapAuto", p1)};
            });
            JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject()
                    .getAsJsonObject("groups").getAsJsonObject("P1");
            String want = k[1];
            String line = k[0] + " -> " + st[0] + " (fast/auto " + st[1] + "), saved " + saved;
            seen.add(line);
            // the saved keys read the same mode in an older jar: fast only for Fast, auto only for Auto
            boolean savedFast = saved.get("fast").getAsBoolean();
            boolean savedAuto = saved.get("auto").getAsBoolean();
            if (!st[0].equals(want) || !st[1].equals(want.equals("FAST") + "/" + want.equals("AUTO"))
                    || savedFast != want.equals("FAST") || savedAuto != want.equals("AUTO")) {
                bad.add(line + "; want " + want);
            }
        }
        c.note("old leap combinations: " + seen);
        c.check(bad.isEmpty(), "old leap combinations migrated wrong: " + bad);
    }

    /** Every value in {@code old} must be in {@code now} unchanged (now may hold more). */
    private static void compare(String path, JsonObject old, JsonObject now, List<String> diff) {
        for (Map.Entry<String, JsonElement> e : old.entrySet()) {
            String p = path + "/" + e.getKey();
            JsonElement n = now.get(e.getKey());
            if (n == null) {
                diff.add(p + " missing");
            } else if (e.getValue().isJsonObject() && n.isJsonObject()) {
                compare(p, e.getValue().getAsJsonObject(), n.getAsJsonObject(), diff);
            } else if (!e.getValue().equals(n)) {
                diff.add(p + " " + e.getValue() + " -> " + n);
            }
        }
    }

    // ---- 1102-ui-leapmenu-search --------------------------------------------------------------------------------

    static void search(UiCase c) throws Exception {
        if (!Mod.isCheat()) {
            c.skipCase("legit jar: Fast/Auto Leap is cheat-only");
            return;
        }
        if (!hasPages()) {
            c.skipCase("this jar has no Fast/Auto Leap pages (mod leapmenu-1102)");
            return;
        }
        try (State st = new State(c); AutoCloseable all = MenuLayoutCases.allTested(c)) {
            ModScreenDriver d = open(c);
            c.check(d != null, "no Fast/Auto Leap tab in the menu");
            if (d == null) {
                return;
            }
            String[][] wants = {{"Storm Death", "UNIVERSAL", "Storm Death: "}, {"When You Play", "CLASS", "When You Play: "},
                    {"Return To", "FRONT", "Return To: "}};
            for (String[] w : wants) {
                String got = c.onClient(mc -> {
                    try {
                        Class<?> sc = Mod.cls("gui.ModScreen");
                        R.setStatic(sc, "searchQuery", w[0]);
                        R.call0(d.screen, "computeResults");
                        @SuppressWarnings("unchecked")
                        List<Object> results = (List<Object>) R.get(d.screen, "results");
                        Object hit = null;
                        List<String> seen = new ArrayList<>();
                        for (Object r : results) {
                            Object tab = Mod.call(r, "tab");
                            String label = (String) Mod.call(r, "label");
                            if (tab.getClass().getSimpleName().equals(TAB)) {
                                seen.add(label);
                                if (label.equalsIgnoreCase(w[0])) {
                                    hit = r;
                                }
                            }
                        }
                        if (hit == null) {
                            return "no hit; Fast/Auto Leap hits " + seen;
                        }
                        Mod.staticCall(FLS, "openPage", Mod.enumValue(FLS + "$Page", "FRONT"));
                        Mod.call(d.screen, "activate", hit);
                        return "hits " + seen;
                    } catch (Throwable t) {
                        throw new AssertionError(UiCase.describe(t), t);
                    }
                });
                c.ticks(2);
                String pg = page(c);
                List<String> ls = labels(c.onClient(mc -> new ArrayList<>(d.content())));
                boolean shown = ls.stream().anyMatch(l -> l.startsWith(w[2]));
                c.note("search '" + w[0] + "': " + got + "; opened page " + pg + ", row shown " + shown);
                c.check(got.startsWith("hits"), "search '" + w[0] + "': " + got);
                c.check(w[1].equals(pg) && shown, "opening the '" + w[0] + "' hit showed page " + pg + " (want " + w[1]
                        + "), row there " + shown);
            }
            // every sub-page row is findable: each target label is a hit
            List<String> missing = c.onClient(mc -> {
                Object tab = d.leaf(TAB);
                List<String> out = new ArrayList<>();
                for (String l : new String[]{"Crystal", "Predev", "Green Pad", "Yellow Pad", "Purple Pad", "PY Healer",
                        "Storm Death", "Middle", "P4", "Relic", "S1", "S2", "S3", "S4", "When You Play", "S1 Keywords",
                        "Click Delay", "Fast Mode", "Mode"}) {
                    @SuppressWarnings("unchecked")
                    List<String> hits = (List<String>) Mod.call(tab, "searchHits", l);
                    if (hits.stream().noneMatch(h -> h.equalsIgnoreCase(l))) {
                        out.add(l + " " + hits);
                    }
                }
                return out;
            });
            c.check(missing.isEmpty(), "search does not reach: " + missing);
        } finally {
            c.onClient(mc -> {
                R.setStatic(Mod.cls("gui.ModScreen"), "searchQuery", "");
                return null;
            });
        }
    }
}

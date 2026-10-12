package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * killer560's 2026-10-11 leap-menu batch, the editor/settings half (mod branch leap-2; the real-menu half is
 * {@code menu/LeapBatchMenuCases} 821-825).
 * <ul>
 *   <li>826: the class wizard. Party LoAnna/LoBen/LoCara/LoDev with nothing known but LoCara's tab-list Healer: picking
 *       Archer starts it on LoAnna; her Class button steps forward with a left click and back with a right click; LoBen
 *       is set with a class button; LoCara's page is pre-filled Healer (no override written); Back goes back a page;
 *       Finish shows the editor laid out by the Default Order (Odin) from those classes. With every class known, picking
 *       Archer goes straight to the editor, and Set Classes starts the wizard again.</li>
 *   <li>827: the two new Leap Menu settings: Show Player Heads and Leap Style rows in the tab, left click forward, right
 *       click back, saved to the file and read back by a reload, each with a tooltip.</li>
 *   <li>828: pictures at GUI 2 of a wizard page and the editor after it (to {@code TESTKIT_LEAP2_SHOTS}).</li>
 * </ul>
 */
final class LeapWizardCases {

    private static final List<String> PARTY = LeapOrderCases.PARTY;

    private LeapWizardCases() {
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    static Screen openPage1(UiCase c) {
        Screen s = c.onClient(mc -> {
            try {
                Screen scr = (Screen) Mod.cls("leapmenu.LeapOrderScreen").getConstructor(Screen.class)
                        .newInstance((Object) null);
                McCompat.setScreen(mc, scr);
                return scr;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        c.ticks(3);
        return s;
    }

    static void clickArcher(UiCase c, Screen s) {
        InvBatchCases.clickWidget(c, s, InvBatchCases.widget(c, s, "Archer"));
    }

    @SuppressWarnings("unchecked")
    static List<String> wizard(UiCase c, Screen s) {
        Object w = c.onClient(mc -> Mod.field(s, "wizard"));
        return w == null ? null : List.copyOf((List<String>) w);
    }

    static int index(UiCase c, Screen s) {
        return c.onClient(mc -> (Integer) Mod.field(s, "wizardIndex"));
    }

    static String cls(UiCase c, String name) {
        return c.onClient(mc -> String.valueOf(Mod.staticCall("leapmenu.PartyTracker", "classOf", name)));
    }

    static boolean override(UiCase c, String name) {
        return c.onClient(mc -> (Boolean) Mod.staticCall("dungeonclass.ClassOverrides", "has", name));
    }

    static void rightClick(UiCase c, Screen s, AbstractWidget w) {
        c.check(w != null, "no such control");
        InvBatchCases.cursor(c, s, w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0);
        c.ctx().getInput().pressMouse(1);
        c.ticks(3);
    }

    static void press(UiCase c, Screen s, String label) {
        InvBatchCases.clickWidget(c, s, InvBatchCases.widget(c, s, label));
    }

    @SuppressWarnings("unchecked")
    static void detect(UiCase c, String name, String cls) {
        c.onClient(mc -> {
            Map<String, Object> m = (Map<String, Object>) Mod.field("leapmenu.PartyTracker", "CLASSES");
            if (cls == null) {
                m.remove(name.toLowerCase(java.util.Locale.US));
            } else {
                m.put(name.toLowerCase(java.util.Locale.US), Mod.enumValue("dungeonclass.DungeonClass", cls));
            }
            return null;
        });
    }

    // ---- 826 --------------------------------------------------------------------------------------------------------

    static void wizardCase(UiCase c) throws Exception {
        LeapOrderCases.State st = LeapOrderCases.setUp(c);
        try {
            detect(c, "LoCara", "HEALER");
            Screen s = openPage1(c);
            clickArcher(c, s);
            List<String> w = wizard(c, s);
            c.check(PARTY.equals(w), "picking Archer with unknown classes: wizard " + w + ", want " + PARTY);
            c.check(index(c, s) == 0, "the wizard starts on teammate " + index(c, s));
            List<String> trail = new ArrayList<>();

            // LoAnna: the Class button, left forward, right back.
            AbstractWidget btn = InvBatchCases.widget(c, s, "Class:");
            c.check(ModScreenDriver.label(btn).contains("?"), "LoAnna's Class button reads " + ModScreenDriver.label(btn));
            InvBatchCases.clickWidget(c, s, btn);
            c.check(cls(c, "LoAnna").equals("MAGE"), "a left click on Class gave LoAnna " + cls(c, "LoAnna"));
            InvBatchCases.clickWidget(c, s, InvBatchCases.widget(c, s, "Class:"));
            c.check(cls(c, "LoAnna").equals("TANK"), "a second left click gave LoAnna " + cls(c, "LoAnna"));
            rightClick(c, s, InvBatchCases.widget(c, s, "Class:"));
            c.check(cls(c, "LoAnna").equals("MAGE"), "a right click went to " + cls(c, "LoAnna") + ", want back to MAGE");
            trail.add("LoAnna: ? -> Mage -> Tank -> (right) Mage");
            press(c, s, "Next");
            c.check(index(c, s) == 1, "Next went to teammate " + index(c, s));

            // LoBen: a class button; Back returns to LoAnna, Next comes back.
            press(c, s, "Tank");
            c.check(cls(c, "LoBen").equals("TANK"), "the Tank button gave LoBen " + cls(c, "LoBen"));
            press(c, s, "Back");
            c.check(index(c, s) == 0, "Back went to teammate " + index(c, s));
            press(c, s, "Next");
            press(c, s, "Next");
            c.check(index(c, s) == 2, "two Nexts went to teammate " + index(c, s));
            trail.add("LoBen: Tank button; Back to LoAnna and on again");

            // LoCara: pre-filled from the tab list, no override written.
            String caraBtn = ModScreenDriver.label(InvBatchCases.widget(c, s, "Class:"));
            c.check(caraBtn.contains("Healer"), "LoCara's page is not pre-filled from the tab list: " + caraBtn);
            c.check(!override(c, "LoCara"), "LoCara got an override just by being shown");
            press(c, s, "Next");
            trail.add("LoCara: pre-filled '" + caraBtn + "', no override");

            // LoDev: the last page says Finish.
            c.check(InvBatchCases.widget(c, s, "Finish") != null, "the last page has no Finish button");
            press(c, s, "Berserker");
            c.check(cls(c, "LoDev").equals("BERSERKER"), "the Berserker button gave LoDev " + cls(c, "LoDev"));
            press(c, s, "Finish");
            c.check(wizard(c, s) == null, "Finish left the wizard running");
            List<String> shown = LeapOrderCases.shown(c, s);
            Map<String, String> classes = new HashMap<>(Map.of("LoAnna", "MAGE", "LoBen", "TANK", "LoCara", "HEALER",
                    "LoDev", "BERSERKER"));
            List<String> want = LeapOrderCases.expected("ODIN", PARTY, List.of(), classes);
            c.check(shown.equals(want), "after Finish the editor shows " + shown + ", want Odin's " + want);
            trail.add("LoDev: Berserker; Finish -> editor " + shown);

            // Everyone known: page 1 goes straight to the editor; Set Classes starts the wizard again.
            press(c, s, "Back");
            c.check(c.onClient(mc -> Mod.field(s, "editing")) == null, "Back did not return to page 1");
            clickArcher(c, s);
            c.check(wizard(c, s) == null, "with every class known the wizard started anyway");
            press(c, s, "Set Classes");
            List<String> again = wizard(c, s);
            c.check(PARTY.equals(again), "Set Classes started " + again);
            String annaAgain = ModScreenDriver.label(InvBatchCases.widget(c, s, "Class:"));
            c.check(annaAgain.contains("Mage"), "Set Classes shows LoAnna as " + annaAgain);
            press(c, s, "Skip");
            c.check(wizard(c, s) == null, "Skip left the wizard running");
            trail.add("all known: straight to the editor; Set Classes -> wizard (LoAnna '" + annaAgain + "'); Skip");
            c.note(String.join("; ", trail));
        } finally {
            detect(c, "LoCara", null);
            LeapOrderCases.restore(c, st);
        }
    }

    // ---- 827 --------------------------------------------------------------------------------------------------------

    static final String TAB = "Leap Menu";

    static ModScreenDriver openTab(UiCase c) {
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

    static AbstractWidget row(UiCase c, ModScreenDriver d, String prefix) {
        return c.onClient(mc -> {
            try {
                d.rebuild();
            } catch (Throwable t) {
                throw new AssertionError(UiCase.describe(t), t);
            }
            for (AbstractWidget w : d.content()) {
                if (ModScreenDriver.label(w).startsWith(prefix)) {
                    return w;
                }
            }
            return null;
        });
    }

    static void clickRow(UiCase c, AbstractWidget w, int button) {
        c.onClient(mc -> {
            double x = w.getX() + w.getWidth() / 2.0;
            double y = w.getY() + w.getHeight() / 2.0;
            w.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0)), false);
            w.mouseReleased(new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0)));
            return null;
        });
        c.ticks(1);
    }

    static void settings(UiCase c) throws Exception {
        String cfgCls = "spiritleap.SpiritLeapOverlayConfig";
        Path path = (Path) Mod.field(cfgCls, "CONFIG_PATH");
        byte[] saved = Files.exists(path) ? Files.readAllBytes(path) : null;
        try {
            c.onClient(mc -> {
                Object cfg = Mod.cfg(cfgCls);
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setShowHeads", true);
                Mod.call(cfg, "setStyle", Mod.enumValue(cfgCls + "$LeapStyle", "BOXES"));
                Mod.call(cfg, "save");
                return null;
            });
            ModScreenDriver d = openTab(c);
            AbstractWidget heads = row(c, d, "Show Player Heads:");
            AbstractWidget style = row(c, d, "Leap Style:");
            c.check(heads != null && style != null, "rows: heads " + heads + ", style " + style);
            List<String> trail = new ArrayList<>();
            clickRow(c, heads, 0);
            boolean h1 = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(cfgCls), "isShowHeads"));
            clickRow(c, row(c, d, "Show Player Heads:"), 1);
            boolean h2 = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(cfgCls), "isShowHeads"));
            c.check(!h1 && h2, "Show Player Heads: left " + h1 + ", then right " + h2 + " (want false, true)");
            trail.add("Show Player Heads left -> " + h1 + ", right -> " + h2);
            clickRow(c, style, 0);
            String s1 = c.onClient(mc -> String.valueOf(Mod.call(Mod.cfg(cfgCls), "getStyle")));
            clickRow(c, row(c, d, "Leap Style:"), 1);
            String s2 = c.onClient(mc -> String.valueOf(Mod.call(Mod.cfg(cfgCls), "getStyle")));
            clickRow(c, row(c, d, "Leap Style:"), 1);
            String s3 = c.onClient(mc -> String.valueOf(Mod.call(Mod.cfg(cfgCls), "getStyle")));
            c.check(s1.equals("MAP_IN_CLEAR") && s2.equals("BOXES") && s3.equals("MAP_IN_CLEAR"),
                    "Leap Style left/right/right: " + s1 + ", " + s2 + ", " + s3);
            String label = ModScreenDriver.label(row(c, d, "Leap Style:"));
            c.check(label.contains("Map in Clear"), "the row reads " + label);
            trail.add("Leap Style left -> " + s1 + ", right -> " + s2 + ", right -> " + s3 + " ('" + label + "')");
            // Persisted: off and Map in Clear survive a reload from the file.
            c.onClient(mc -> {
                Mod.call(Mod.cfg(cfgCls), "setShowHeads", false);
                Mod.call(Mod.cfg(cfgCls), "save");
                Mod.staticCall(cfgCls, "load");
                return null;
            });
            boolean hr = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(cfgCls), "isShowHeads"));
            String sr = c.onClient(mc -> String.valueOf(Mod.call(Mod.cfg(cfgCls), "getStyle")));
            c.check(!hr && sr.equals("MAP_IN_CLEAR"), "after a reload: heads " + hr + ", style " + sr);
            String json = Files.readString(path);
            c.check(json.contains("\"showHeads\": false") && json.contains("\"style\": \"MAP_IN_CLEAR\""), "file: " + json);
            trail.add("reload: heads " + hr + ", style " + sr);
            for (String l : List.of("Show Player Heads: ON", "Leap Style: Boxes")) {
                Object tip = c.onClient(mc -> Mod.staticCall("gui.SettingTooltips", "describe", TAB, l));
                c.check(tip != null && !String.valueOf(tip).isBlank(), "no tooltip for '" + l + "'");
            }
            c.note(String.join("; ", trail) + "; both rows have tooltips");
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                try {
                    if (saved != null) {
                        Files.write(path, saved);
                    } else {
                        Files.deleteIfExists(path);
                    }
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                Mod.staticCall(cfgCls, "load");
                return null;
            });
        }
    }

    // ---- 828 --------------------------------------------------------------------------------------------------------

    static void shots(UiCase c) throws Exception {
        LeapOrderCases.State st = LeapOrderCases.setUp(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            detect(c, "LoCara", "HEALER");
            c.onClient(mc -> {
                mc.options.guiScale().set(2);
                mc.resizeGui();
                return null;
            });
            Screen s = openPage1(c);
            clickArcher(c, s);
            c.check(wizard(c, s) != null, "the wizard did not start");
            press(c, s, "Mage");
            InvBatchCases.cursor(c, s, 2, 2);
            shot(c, "wizard-anna");
            press(c, s, "Next");
            press(c, s, "Tank");
            press(c, s, "Next");
            InvBatchCases.cursor(c, s, 2, 2);
            shot(c, "wizard-cara-prefilled");
            press(c, s, "Next");
            press(c, s, "Berserker");
            press(c, s, "Finish");
            InvBatchCases.cursor(c, s, 2, 2);
            shot(c, "editor-after-wizard");
            int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
            c.check(gs == 2, "pictures at GUI " + gs);
        } finally {
            c.onClient(mc -> {
                mc.options.guiScale().set(oldGui);
                mc.resizeGui();
                return null;
            });
            detect(c, "LoCara", null);
            LeapOrderCases.restore(c, st);
        }
    }

    private static void shot(UiCase c, String suffix) throws Exception {
        c.ticks(3);
        String name = c.name() + "-" + suffix;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        dev.testkit.harness.Report.screenshot(name, taken);
        c.note("picture " + name);
        String dir = System.getenv("TESTKIT_LEAP2_SHOTS");
        if (dir != null && !dir.isBlank()) {
            Path out = Path.of(dir).resolve(name + "-" + (Mod.isCheat() ? "cheat" : "legit") + ".png");
            Files.createDirectories(out.getParent());
            Files.copy(taken, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

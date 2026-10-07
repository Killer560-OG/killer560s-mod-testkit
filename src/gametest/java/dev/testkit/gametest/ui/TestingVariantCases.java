package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.mod.Mod;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.client.gui.components.AbstractWidget;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 413: the private testing variants (killer560, 2026-10-07; mod build.gradle -PtestingBuild=true, cheat and legit).
 *
 * <p>On a testing jar: the menu has an "Untested" category right after Home holding every feature tab of the normal
 * menu except Home's (Home holds the HUD editor and stays as it is), each removed from its normal category; the
 * build's variant matches the jar's cheat flag; pressing a tab's real "Mark tested" button moves it into its normal
 * category, the mark is saved to the config file and survives a re-read, {@code /k560tested list} prints it, and
 * "Mark untested" puts it back.
 *
 * <p>On a normal jar: no "Untested" category, no class under {@code com/killer560/hub/testing/}, no testing resource
 * and no {@code /k560tested} command.
 */
final class TestingVariantCases {

    private static final String TESTING_PKG = "com.killer560.hub.testing.";
    private static final String MENU = "testing.TestingMenu";
    private static final String TESTED = "testing.TestedFeatures";
    private static final String UNTESTED = "Untested";
    private static final String HOME = "Home";

    private TestingVariantCases() {
    }

    static void run(UiCase c) throws Exception {
        boolean testing = JarIndex.peek(TESTING_PKG + "TestingBuild") != null;
        c.note("jar is a " + (Mod.isCheat() ? "cheat" : "legit") + (testing ? " TESTING" : " normal") + " build");
        if (testing) {
            testingJar(c);
        } else {
            normalJar(c);
        }
    }

    // ---- a normal jar has none of it ------------------------------------------------------------------------------

    private static void normalJar(UiCase c) {
        List<String> bad = new ArrayList<>();
        for (String fq : JarIndex.classNames(s -> true)) {
            if (fq.startsWith(TESTING_PKG)) {
                bad.add(fq);
            }
        }
        if (!bad.isEmpty()) {
            c.problem("a normal jar ships testing classes: " + bad);
        }
        boolean resource = JarIndex.class.getResource("/killer560smod-testing/tested-cheat.txt") != null
                || JarIndex.class.getResource("/killer560smod-testing/tested-legit.txt") != null;
        if (resource) {
            c.problem("a normal jar ships the tested-feature list");
        }
        List<String> top = topNames(c);
        c.note("top level: " + top);
        if (top.contains(UNTESTED)) {
            c.problem("a normal jar shows an '" + UNTESTED + "' category: " + top);
        }
        boolean command = c.onClient(mc -> {
            CommandDispatcher<Object> d = CommandSweep.dispatcher();
            return d.getRoot().getChild("k560tested") != null;
        });
        if (command) {
            c.problem("a normal jar registers /k560tested");
        }
        c.note("normal jar: testing classes " + bad.size() + ", tested list bundled " + resource + ", /k560tested "
                + command);
    }

    // ---- a testing jar ------------------------------------------------------------------------------------------

    private static void testingJar(UiCase c) throws Exception {
        String variant = (String) Mod.field("testing.TestingBuild", "VARIANT");
        boolean enabled = (Boolean) Mod.field("testing.TestingBuild", "ENABLED");
        c.check(enabled, "TestingBuild.ENABLED is false in a jar that ships the testing package");
        c.check(variant.equals(Mod.isCheat() ? "cheat" : "legit"), "TestingBuild.VARIANT is " + variant
                + " but BuildVariant.CHEAT_FEATURES_ENABLED is " + Mod.isCheat());
        c.onClient(mc -> {
            Mod.staticCall(TESTED, "reload");
            return null;
        });
        Path marks = c.onClient(mc -> (Path) Mod.staticCall(TESTED, "marksFile"));
        Files.deleteIfExists(marks);

        Menu m = menu(c);
        c.note("top level: " + m.top);
        c.check(m.top.size() >= 2 && m.top.get(0).equals(HOME) && m.top.get(1).equals(UNTESTED),
                "expected [" + HOME + ", " + UNTESTED + ", ...] at the top, got " + m.top);

        @SuppressWarnings("unchecked")
        Map<String, String> normal = new LinkedHashMap<>((Map<String, String>) c.onClient(mc ->
                Mod.staticCall(MENU, "normalLeaves")));
        Set<String> expected = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : normal.entrySet()) {
            if (!e.getValue().startsWith(HOME + " > ") && !e.getValue().equals(HOME)) {
                expected.add(e.getKey());
            }
        }
        c.note("normal menu: " + normal.size() + " feature tabs, " + (normal.size() - expected.size()) + " in Home");
        c.check(expected.size() > 100, "only " + expected.size() + " feature tabs in the normal menu");
        Set<String> repo = c.onClient(mc -> new LinkedHashSet<>((Set<String>) Mod.staticCall(TESTED, "repoList")));
        c.note("repo tested list (" + variant + "): " + repo);

        Set<String> inUntested = m.leaves.getOrDefault(UNTESTED, new LinkedHashSet<>());
        Set<String> outside = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> e : m.leaves.entrySet()) {
            if (!e.getKey().equals(UNTESTED) && !e.getKey().equals(HOME)) {
                outside.addAll(e.getValue());
            }
        }
        for (String id : expected) {
            boolean tested = repo.contains(id);
            if (!tested && !inUntested.contains(id)) {
                c.problem("untested '" + normal.get(id) + "' (" + id + ") is not in " + UNTESTED);
            }
            if (tested && !outside.contains(id)) {
                c.problem("tested '" + normal.get(id) + "' (" + id + ") is not in its category");
            }
        }
        for (String id : inUntested) {
            if (!expected.contains(id)) {
                c.problem(UNTESTED + " holds '" + id + "', which is not a normal feature tab outside Home");
            }
        }
        c.check(m.leaves.getOrDefault(HOME, Set.of()).equals(leavesUnder(normal, HOME)),
                "Home changed: " + m.leaves.get(HOME) + " vs normal " + leavesUnder(normal, HOME));
        c.note(UNTESTED + ": " + inUntested.size() + " of " + expected.size() + " feature tabs; in categories: "
                + outside.size());

        // Mark one tested through its real button; it must land in its normal category and stay there.
        String pick = inUntested.contains("LiveMapTab") ? "LiveMapTab" : inUntested.iterator().next();
        String home = normal.get(pick);
        String category = home.substring(0, home.indexOf(" > ") < 0 ? home.length() : home.indexOf(" > "));
        String label = press(c, UNTESTED, pick, "Mark tested");
        c.note("pressed '" + label + "' on " + pick + " in " + UNTESTED);
        Menu after = menu(c);
        c.check(!after.leaves.getOrDefault(UNTESTED, Set.of()).contains(pick), pick + " is still in " + UNTESTED);
        c.check(after.leaves.getOrDefault(category, Set.of()).contains(pick), pick + " did not appear in " + category
                + " (top level now " + after.top + ")");
        String text = Files.exists(marks) ? Files.readString(marks) : "";
        c.check(text.contains(pick), "the marks file " + marks + " does not name " + pick + ": " + text);
        c.onClient(mc -> {
            Mod.staticCall(TESTED, "reload");
            return null;
        });
        Menu reread = menu(c);
        c.check(reread.leaves.getOrDefault(category, Set.of()).contains(pick), pick + " left " + category
                + " after the marks were re-read from disk");
        c.note(pick + " -> " + category + " (" + home + "); saved to " + marks);

        long mark = LogTap.mark();
        String err = CommandSweep.execute(c, "k560tested list");
        c.check(err == null, "/k560tested list threw: " + err);
        c.ticks(3);
        boolean listed = false;
        for (String l : LogTap.since(mark)) {
            if (l.contains("[CHAT]") && l.contains("Marked tested") && l.contains(pick)) {
                listed = true;
            }
        }
        c.check(listed, "/k560tested list did not print " + pick + ": " + LogTap.since(mark));

        // And back.
        press(c, category, pick, "Mark untested");
        Menu back = menu(c);
        c.check(back.leaves.getOrDefault(UNTESTED, Set.of()).contains(pick), pick + " did not return to " + UNTESTED);
        c.note(pick + " marked untested again: back in " + UNTESTED);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private record Menu(List<String> top, Map<String, Set<String>> leaves) {
    }

    private static Set<String> leavesUnder(Map<String, String> normal, String top) {
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : normal.entrySet()) {
            if (e.getValue().equals(top) || e.getValue().startsWith(top + " > ")) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    private static List<String> topNames(UiCase c) {
        return menu(c).top;
    }

    /** A fresh menu (tabs rebuilt): top names, and each top's leaf tab class simple names. */
    private static Menu menu(UiCase c) {
        return c.onClient(mc -> {
            try {
                R.setStatic(R.cls(ModScreenDriver.MOD_SCREEN), "tabs", null);
                ModScreenDriver d = new ModScreenDriver(mc);
                List<String> top = new ArrayList<>();
                Map<String, Set<String>> leaves = new LinkedHashMap<>();
                for (ModScreenDriver.Node n : d.tree()) {
                    top.add(n.name());
                    List<ModScreenDriver.Node> flat = new ArrayList<>();
                    ModScreenDriver.flatten(List.of(n), flat);
                    Set<String> ids = new LinkedHashSet<>();
                    for (ModScreenDriver.Node f : flat) {
                        if (!f.folder()) {
                            ids.add(f.tab().getClass().getSimpleName());
                        }
                    }
                    leaves.put(n.name(), ids);
                }
                McCompat.setScreen(mc, null);
                return new Menu(top, leaves);
            } catch (Throwable t) {
                throw new AssertionError("could not open gui.ModScreen: " + UiCase.describe(t), t);
            }
        });
    }

    /** Opens {@code top}, expands the section path down to tab {@code id}, presses the row labelled {@code label}. */
    private static String press(UiCase c, String top, String id, String label) {
        return c.onClient(mc -> {
            try {
                R.setStatic(R.cls(ModScreenDriver.MOD_SCREEN), "tabs", null);
                ModScreenDriver d = new ModScreenDriver(mc);
                List<ModScreenDriver.Node> tree = d.tree();
                int topIndex = -1;
                for (int i = 0; i < tree.size(); i++) {
                    if (tree.get(i).name().equals(top)) {
                        topIndex = i;
                    }
                }
                if (topIndex < 0) {
                    throw new AssertionError("no top-level '" + top + "'");
                }
                d.select(topIndex);
                if (!openPath(d, tree.get(topIndex), id)) {
                    throw new AssertionError(id + " is not under " + top);
                }
                d.rebuild();
                List<AbstractWidget> hits = new ArrayList<>();
                for (AbstractWidget w : d.content()) {
                    if (ModScreenDriver.label(w).equals(label)) {
                        hits.add(w);
                    }
                }
                if (hits.size() != 1) {
                    throw new AssertionError("expected one '" + label + "' row with only " + id + " open, found "
                            + hits.size());
                }
                ModScreenDriver.press(hits.get(0));
                McCompat.setScreen(mc, null);
                return ModScreenDriver.label(hits.get(0));
            } catch (AssertionError e) {
                throw e;
            } catch (Throwable t) {
                throw new AssertionError("pressing '" + label + "' on " + id + ": " + UiCase.describe(t), t);
            }
        });
    }

    /** Expands every folder section on the way to leaf {@code id}; true if found. */
    private static boolean openPath(ModScreenDriver d, ModScreenDriver.Node node, String id) {
        if (!node.folder()) {
            return node.tab().getClass().getSimpleName().equals(id);
        }
        List<ModScreenDriver.Node> kids = node.children();
        for (int i = 0; i < kids.size(); i++) {
            if (openPath(d, kids.get(i), id)) {
                d.expanded(node.tab()).add(i);
                return true;
            }
        }
        return false;
    }
}

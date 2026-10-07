package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 412: the mod menu has no "New" category any more (killer560, 2026-10-07: every feature in it moves to the category it
 * will live in for the release). Checked against the real menu:
 * <ul>
 *   <li>no top-level tab is named "New";</li>
 *   <li>every tab that was in New ({@code testkit-ui/new-tab-tooltips-<variant>.json}, recorded from the jar before the
 *       move, mod 24671ffc) is in the tree, under some other category;</li>
 *   <li>every row those tabs build shows the SAME tooltip it showed in New. A moved row loses its tooltip silently when
 *       its key was scoped by the old top-level name ("new/mode"), so the tooltip text is compared row by row.</li>
 * </ul>
 * On a jar that still HAS the New category, the case records that file (to {@code build/testkit-report/}) and fails -
 * which is how the fixture was made. The 386 overlap sweep walks the whole tree, so it covers the moved rows too.
 */
final class NoNewTabCases {

    private static final String NEW = "New";
    private static final String TIPS = "gui.SettingTooltips";
    /** Rows a folder draws itself (accordion headers) carry no sub-tab scope; they are filed under this owner. */
    private static final String UNSCOPED = "(unscoped)";

    private NoNewTabCases() {
    }

    /** owner (the sub-tab that built the row) -> label key -> tooltip ("" = none). */
    private record Seen(Map<String, Map<String, String>> rows, Map<String, String> leaves, List<String> denied) {
    }

    static void run(UiCase c, Deny deny) throws Exception {
        boolean cheat = Mod.isCheat();
        String variant = cheat ? "cheat" : "legit";
        Map<String, Object> tree = c.onClient(mc -> {
            Map<String, Object> m = new LinkedHashMap<>();
            try {
                ModScreenDriver d = new ModScreenDriver(mc);
                List<String> top = new ArrayList<>();
                for (Object t : d.topTabs()) {
                    top.add((String) R.get(t, "name"));
                }
                m.put("driver", d);
                m.put("top", top);
            } catch (Throwable t) {
                m.put("error", UiCase.describe(t));
            }
            return m;
        });
        c.check(!tree.containsKey("error"), "could not open gui.ModScreen: " + tree.get("error"));
        ModScreenDriver d = (ModScreenDriver) tree.get("driver");
        @SuppressWarnings("unchecked")
        List<String> top = (List<String>) tree.get("top");
        c.note("top level (" + variant + "): " + top);

        int newIndex = top.indexOf(NEW);
        if (newIndex >= 0) {
            Seen seen = collect(c, d, List.of(newIndex), deny);
            JsonObject out = new JsonObject();
            out.addProperty("_doc", "Recorded by 412-ui-no-new-tab from a jar that still had the New category: every"
                    + " leaf tab under New (class -> path) and the tooltip each row showed there (owner sub-tab ->"
                    + " label key -> tooltip). Variant " + variant + ".");
            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            out.add("leaves", gson.toJsonTree(new TreeMap<>(seen.leaves())));
            out.add("rows", gson.toJsonTree(sorted(seen.rows())));
            Path file = Report.dir().resolve("new-tab-tooltips-" + variant + ".json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, gson.toJson(out), StandardCharsets.UTF_8);
            c.note("recorded " + seen.leaves().size() + " New leaf tabs and their rows to " + file);
            c.problem("the menu still has a top-level '" + NEW + "' category (index " + newIndex + ")");
            close(c, d);
            return;
        }

        JsonObject fixture = Deny.read("new-tab-tooltips-" + variant + ".json");
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < top.size(); i++) {
            all.add(i);
        }
        Seen now = collect(c, d, all, deny);
        close(c, d);
        if (!now.denied().isEmpty()) {
            c.note("section headers NOT pressed (deny list): " + now.denied());
        }

        // Every former New tab is in the tree, outside any New category.
        int moved = 0;
        for (Map.Entry<String, JsonElement> e : fixture.getAsJsonObject("leaves").entrySet()) {
            String cls = e.getKey();
            String was = e.getValue().getAsString();
            String path = now.leaves().get(cls);
            if (path == null) {
                c.problem("'" + was + "' (" + cls + ") is no longer anywhere in the menu");
                continue;
            }
            moved++;
            c.note(was + "  ->  " + path);
        }
        c.check(moved > 20, "only " + moved + " former New tabs found - the walk is broken");

        // Every row shows the tooltip it showed in New.
        int compared = 0;
        int lost = 0;
        for (Map.Entry<String, JsonElement> o : fixture.getAsJsonObject("rows").entrySet()) {
            String owner = o.getKey();
            for (Map.Entry<String, JsonElement> r : o.getValue().getAsJsonObject().entrySet()) {
                String key = r.getKey();
                String was = r.getValue().getAsString();
                if (was.isEmpty()) {
                    continue;
                }
                compared++;
                String got;
                if (owner.equals(UNSCOPED)) {
                    // A header row of New itself; now drawn by whichever folder holds the tab. Any row with this key
                    // showing the same text counts.
                    got = null;
                    for (Map<String, String> m : now.rows().values()) {
                        String t = m.get(key);
                        if (was.equals(t)) {
                            got = t;
                            break;
                        }
                    }
                } else {
                    Map<String, String> m = now.rows().get(owner);
                    got = m == null ? null : m.get(key);
                }
                if (!was.equals(got)) {
                    lost++;
                    c.problem("tooltip changed for '" + owner + "' row '" + key + "': was \"" + was + "\", now "
                            + (got == null ? "none" : "\"" + got + "\""));
                }
            }
        }
        c.note("former New tabs found: " + moved + "; rows with a tooltip compared: " + compared + ", changed: " + lost);
        c.check(compared > 100, "only " + compared + " tooltips compared - the walk is broken");
    }

    private static Map<String, Map<String, String>> sorted(Map<String, Map<String, String>> in) {
        Map<String, Map<String, String>> out = new TreeMap<>();
        for (Map.Entry<String, Map<String, String>> e : in.entrySet()) {
            out.put(e.getKey(), new TreeMap<>(e.getValue()));
        }
        return out;
    }

    /** Every section of the given top-level tabs, fully opened and scrolled through; what each row's tooltip is. */
    private static Seen collect(UiCase c, ModScreenDriver d, List<Integer> tops, Deny deny) {
        return c.onClient(mc -> {
            Map<String, Map<String, String>> rows = new LinkedHashMap<>();
            Map<String, String> leaves = new LinkedHashMap<>();
            List<String> denied = new ArrayList<>();
            try {
                Method describe = R.cls(TIPS).getMethod("describe", String.class, AbstractWidget.class, String.class);
                Method key = R.cls(TIPS).getMethod("key", String.class);
                List<ModScreenDriver.Node> tree = d.tree();
                for (int top : tops) {
                    ModScreenDriver.Node topNode = tree.get(top);
                    List<ModScreenDriver.Node> flat = new ArrayList<>();
                    ModScreenDriver.flatten(List.of(topNode), flat);
                    for (ModScreenDriver.Node n : flat) {
                        if (!n.folder()) {
                            leaves.put(n.tab().getClass().getName().replace(Mod.ROOT, ""), n.path());
                        }
                    }
                    int sections = topNode.folder() ? topNode.children().size() : 1;
                    for (int j = 0; j < sections; j++) {
                        d.select(top);
                        if (topNode.folder()) {
                            d.expanded(topNode.tab()).add(j);
                            d.expandAll(topNode.children().get(j).tab());
                        }
                        d.rebuild();
                        d.openCollapsibles(deny, denied);
                        int max = d.maxScroll();
                        int step = Math.max(20, d.visibleContentHeight() - 10);
                        for (int off = 0; ; off += step) {
                            d.scrollTo(Math.min(off, max));
                            d.rebuild();
                            @SuppressWarnings("unchecked")
                            Map<AbstractWidget, String> scopes = (Map<AbstractWidget, String>) R.getStatic(R.cls(TIPS), "SCOPES");
                            for (AbstractWidget w : d.content()) {
                                String label = w.getMessage().getString();
                                String k = (String) key.invoke(null, label);
                                if (k.isEmpty()) {
                                    continue;
                                }
                                String owner = scopes.get(w);
                                String tip = (String) describe.invoke(null, topNode.name(), w, label);
                                rows.computeIfAbsent(owner == null ? UNSCOPED : owner, x -> new TreeMap<>())
                                        .putIfAbsent(k, tip == null ? "" : tip);
                            }
                            if (off >= max) {
                                break;
                            }
                        }
                        d.scrollTo(0);
                    }
                }
            } catch (Throwable t) {
                c.problem("walking the menu threw " + UiCase.describe(t));
            }
            return new Seen(rows, leaves, denied);
        });
    }

    private static void close(UiCase c, ModScreenDriver d) {
        c.onClient((Minecraft mc) -> {
            McCompat.setScreen(mc, null);
            d.select(0);
            return null;
        });
    }
}

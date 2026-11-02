package dev.testkit.gametest.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 1102-ui-ap3-crystal-height (mod zpew2-1101, cheat jar): the AP3 node editor's Height box on a Crystal node (doc-audit P6:
 * the height was set by {@code /ap3 add crystal <n>} only).
 * <ul>
 *   <li>A Crystal with {@code "crystalHeight": 30} shows "30"; one with none shows a blank box; a Stop has no Height box.</li>
 *   <li>12.5 typed into the blank one and saved by a real click writes {@code "crystalHeight": 12.5}; the other keeps 30.</li>
 *   <li>70 (over the 61 limit) and "abc" are refused: the screen stays open, says why, and the file keeps 30.</li>
 *   <li>The box cleared and saved drops the key (the crystal above the node again).</li>
 *   <li>Reloaded from disk, the node reads 12.5 and its box "12.5".</li>
 * </ul>
 */
final class Ap3CrystalEditCases {

    private static final String SCREEN = "ap3.Ap3EditScreen";
    private static final String BOX = "Height";
    private static final double X0 = 940.5;

    private Ap3CrystalEditCases() {
    }

    static void run(UiCase c) throws Exception {
        if (!Mod.isCheat()) {
            c.note("legit jar: AP3 is cheat-only, skipped");
            return;
        }
        Object cfg = Mod.cfg("ap3.Ap3Config");
        String oldFile = (String) Mod.call(cfg, "getChainsFile");
        boolean oldForce = (Boolean) Mod.staticCall("ap3.Ap3Feature", "isForceDungeon");
        Path file = null;
        try {
            String content = "{ \"version\": 2, \"chains\": { \"BOSS\": { \"class\": \"\", \"nodes\": [\n"
                    + "  { \"type\": \"CRYSTAL\", \"x\": " + X0 + ", \"y\": 70.0, \"z\": 20.5, \"width\": 1.0,"
                    + " \"length\": 1.0, \"crystalHeight\": 30.0 },\n"
                    + "  { \"type\": \"CRYSTAL\", \"x\": " + (X0 + 3) + ", \"y\": 70.0, \"z\": 20.5,"
                    + " \"width\": 1.0, \"length\": 1.0 },\n"
                    + "  { \"type\": \"STOP\", \"x\": " + (X0 + 6) + ", \"y\": 70.0, \"z\": 20.5,"
                    + " \"width\": 1.0, \"length\": 1.0 }\n"
                    + "] } } }";
            file = c.onClient(mc -> {
                Mod.call(cfg, "setChainsFile", "testkit-ap3-crystal-1102");
                Mod.staticCall("ap3.Ap3Feature", "setForceDungeon", true);
                Path f = (Path) Mod.staticCall("ap3.Ap3Store", "file");
                try {
                    Files.createDirectories(f.getParent());
                    Files.writeString(f, content, StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new AssertionError("cannot write " + f, e);
                }
                Mod.staticCall("ap3.Ap3Store", "reload");
                return f;
            });
            List<Object> ns = nodes(c);
            c.check(ns.size() == 3, "expected 3 nodes after load, got " + ns.size());
            c.check(((Number) Mod.field(ns.get(0), "crystalHeight")).doubleValue() == 30.0, "the first crystal did not load 30");

            Screen s = open(c, 0);
            String b0 = boxText(c, s, BOX);
            c.note("crystal 30 editor Height box: '" + b0 + "'");
            c.check("30".equals(b0), "the Height box reads '" + b0 + "', expected 30");
            s = open(c, 1);
            String b1 = boxText(c, s, BOX);
            c.note("crystal (above) editor Height box: '" + b1 + "'");
            c.check("".equals(b1), "the no-height crystal's Height box reads '" + b1 + "', expected blank");
            setBox(c, s, BOX, "12.5");
            click(c, s, button(c, s, "Save"));
            JsonObject saved1 = node(file, 1);
            JsonObject saved0 = node(file, 0);
            c.note("saved: " + saved0 + " / " + saved1);
            c.check(saved1.has("crystalHeight") && saved1.get("crystalHeight").getAsDouble() == 12.5,
                    "crystalHeight 12.5 not saved: " + saved1);
            c.check("CRYSTAL".equals(saved1.get("type").getAsString()), "the node saved as " + saved1);
            c.check(saved0.has("crystalHeight") && saved0.get("crystalHeight").getAsDouble() == 30.0,
                    "the other crystal changed: " + saved0);

            // Out of range and not a number: refused, the screen stays, nothing changes.
            for (String bad : new String[]{"70", "abc", "-3"}) {
                s = open(c, 0);
                setBox(c, s, BOX, bad);
                click(c, s, button(c, s, "Save"));
                Screen now = c.onClient(McCompat::screen);
                Screen fs = s;
                String status = c.onClient(mc -> String.valueOf(Mod.field(fs, "status")));
                JsonObject after = node(file, 0);
                c.note("'" + bad + "' saved: screen still open " + (now == s) + ", status '" + status + "', file " + after);
                c.check(now == s, "'" + bad + "' closed the editor (it should refuse)");
                c.check(after.get("crystalHeight").getAsDouble() == 30.0, "'" + bad + "' changed the file: " + after);
                c.check(!status.isBlank(), "'" + bad + "' was refused with no status line");
            }

            // Cleared: back to the crystal above (no key).
            s = open(c, 0);
            setBox(c, s, BOX, "");
            click(c, s, button(c, s, "Save"));
            JsonObject cleared = node(file, 0);
            c.note("cleared: " + cleared);
            c.check(!cleared.has("crystalHeight"), "a cleared Height box still saved " + cleared);

            // A Stop has no Height box.
            s = open(c, 2);
            c.check(boxText(c, s, BOX) == null, "a Stop's editor shows a Height box");

            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.staticCall("ap3.Ap3Store", "reload");
                return null;
            });
            Object n1 = nodes(c).get(1);
            double h1 = ((Number) Mod.field(n1, "crystalHeight")).doubleValue();
            s = open(c, 1);
            String again = boxText(c, s, BOX);
            c.note("after reload: node 12.5 -> " + h1 + ", box '" + again + "'");
            c.check(h1 == 12.5 && "12.5".equals(again), "after reload node " + h1 + " box '" + again + "'");
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.call(cfg, "setChainsFile", oldFile);
                Mod.staticCall("ap3.Ap3Feature", "setForceDungeon", oldForce);
                Mod.staticCall("ap3.Ap3Store", "reload");
                return null;
            });
            if (file != null) {
                Files.deleteIfExists(file);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> nodes(UiCase c) {
        return c.onClient(mc -> new ArrayList<>((List<Object>) Mod.staticCall("ap3.Ap3Feature", "currentChainNodes")));
    }

    private static JsonObject node(Path file, int i) throws Exception {
        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray nodes = root.getAsJsonObject("chains").getAsJsonObject("BOSS").getAsJsonArray("nodes");
        return nodes.get(i).getAsJsonObject();
    }

    private static Screen open(UiCase c, int index) {
        Screen s = c.onClient(mc -> {
            try {
                Constructor<?> k = Mod.cls(SCREEN).getConstructor(Screen.class, int.class);
                Screen screen = (Screen) k.newInstance(null, index);
                McCompat.setScreen(mc, screen);
                return screen;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("cannot open " + SCREEN + ": " + UiCase.describe(e), e);
            }
        });
        c.ticks(3);
        c.check(c.onClient(McCompat::screen) == s, "the AP3 editor did not stay open");
        return s;
    }

    private static List<AbstractWidget> widgets(Screen s) {
        List<AbstractWidget> out = new ArrayList<>();
        for (Object o : s.children()) {
            if (o instanceof AbstractWidget w && w.visible) {
                out.add(w);
            }
        }
        return out;
    }

    private static AbstractWidget button(UiCase c, Screen s, String prefix) {
        return c.onClient(mc -> {
            for (AbstractWidget w : widgets(s)) {
                if (!(w instanceof EditBox) && ModScreenDriver.label(w).trim().startsWith(prefix)) {
                    return w;
                }
            }
            return null;
        });
    }

    private static String boxText(UiCase c, Screen s, String name) {
        return c.onClient(mc -> {
            for (AbstractWidget w : widgets(s)) {
                if (w instanceof EditBox e && ModScreenDriver.label(w).equals(name)) {
                    return e.getValue();
                }
            }
            return null;
        });
    }

    private static void setBox(UiCase c, Screen s, String name, String value) {
        boolean ok = c.onClient(mc -> {
            for (AbstractWidget w : widgets(s)) {
                if (w instanceof EditBox e && ModScreenDriver.label(w).equals(name)) {
                    e.setValue(value);
                    return true;
                }
            }
            return false;
        });
        c.check(ok, "no '" + name + "' box on the editor");
    }

    private static void click(UiCase c, Screen s, AbstractWidget w) {
        c.check(w != null, "no Save button on the editor");
        Ap3EditCases.clickFor1026(c, s, w);
    }
}

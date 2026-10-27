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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 1026-ui-ap3-blink-nodes (mod ap3-blink-1026, cheat jar): AP3's Blink Charge and Blink Release nodes save, reload and
 * edit (killer560, 2026-10-26: "/ap3 add blinkcharge 3 ... /blinkrelease 3 ... if no number have it go to the max").
 * <ul>
 *   <li>A file with a BLINK_CHARGE of 3 ticks and a BLINK_RELEASE with no number loads as those (0 = the max/all).</li>
 *   <li>The editor shows a "Blink ticks" box on both ("3" and blank); 4 typed into the release's and saved by a real click
 *       writes {@code "blinkTicks": 4}; reloaded, the node and the box read 4. A node with no number writes no key.</li>
 *   <li>{@code /ap3 add}'s parser takes the type words and a bare count (3), refuses 0 and 25; tab-complete and help list
 *       both types; describe() and the list line show the count.</li>
 * </ul>
 */
final class Ap3BlinkNodeCases {

    private static final String SCREEN = "ap3.Ap3EditScreen";
    private static final double X0 = 920.5;

    private Ap3BlinkNodeCases() {
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
                    + "  { \"type\": \"BLINK_CHARGE\", \"x\": " + X0 + ", \"y\": 70.0, \"z\": 20.5, \"width\": 1.0,"
                    + " \"length\": 1.0, \"blinkTicks\": 3 },\n"
                    + "  { \"type\": \"BLINK_RELEASE\", \"x\": " + (X0 + 3) + ", \"y\": 70.0, \"z\": 20.5,"
                    + " \"width\": 1.0, \"length\": 1.0, \"leapCounter\": 2 },\n"
                    + "  { \"type\": \"STOP\", \"x\": " + (X0 + 6) + ", \"y\": 70.0, \"z\": 20.5,"
                    + " \"width\": 1.0, \"length\": 1.0, \"blinkTicks\": 7 }\n"
                    + "] } } }";
            file = c.onClient(mc -> {
                Mod.call(cfg, "setChainsFile", "testkit-ap3-blink-1026");
                Mod.staticCall("ap3.Ap3Feature", "setForceDungeon", true);
                Path f = (Path) Mod.staticCall("ap3.Ap3Store", "file");
                try {
                    Files.writeString(f, content, StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new AssertionError("cannot write " + f, e);
                }
                Mod.staticCall("ap3.Ap3Store", "reload");
                return f;
            });
            List<Object> ns = nodes(c);
            c.check(ns.size() == 3, "expected 3 nodes after load, got " + ns.size());
            c.note("loaded: " + Mod.field(ns.get(0), "type") + " blinkTicks " + Mod.field(ns.get(0), "blinkTicks") + ", "
                    + Mod.field(ns.get(1), "type") + " blinkTicks " + Mod.field(ns.get(1), "blinkTicks") + " leapCounter "
                    + Mod.field(ns.get(1), "leapCounter") + ", STOP blinkTicks " + Mod.field(ns.get(2), "blinkTicks"));
            c.check("BLINK_CHARGE".equals(String.valueOf(Mod.field(ns.get(0), "type")))
                    && (Integer) Mod.field(ns.get(0), "blinkTicks") == 3, "the charge did not load as BLINK_CHARGE 3");
            c.check("BLINK_RELEASE".equals(String.valueOf(Mod.field(ns.get(1), "type")))
                    && (Integer) Mod.field(ns.get(1), "blinkTicks") == 0
                    && (Integer) Mod.field(ns.get(1), "leapCounter") == 2, "the release did not load as BLINK_RELEASE (all) "
                    + "with leapcounter:2");
            c.check((Integer) Mod.field(ns.get(2), "blinkTicks") == 0, "a Stop kept a blinkTicks it never reads");
            String d0 = c.onClient(mc -> String.valueOf(Mod.call(ns.get(0), "describe")));
            String d1 = c.onClient(mc -> String.valueOf(Mod.call(ns.get(1), "describe")));
            String l0 = c.onClient(mc -> String.valueOf(Mod.staticCall("ap3.Ap3Commands", "describe", ns.get(0))));
            c.note("describe: '" + d0 + "', '" + d1 + "'; list: '" + l0 + "'");
            c.check(d0.startsWith("Blink Charge [3 ticks]"), "charge describes as " + d0);
            c.check(d1.startsWith("Blink Release [all]") && d1.contains("leapcounter:2"), "release describes as " + d1);
            c.check(l0.contains("3 ticks"), "the list line does not show the count: " + l0);

            Screen s = open(c, 0);
            String b0 = boxText(c, s, "Blink ticks");
            c.note("charge editor Blink ticks box: '" + b0 + "'");
            c.check("3".equals(b0), "the charge's Blink ticks box reads '" + b0 + "', expected 3");
            s = open(c, 1);
            String b1 = boxText(c, s, "Blink ticks");
            c.note("release editor Blink ticks box: '" + b1 + "'");
            c.check("".equals(b1), "the release's Blink ticks box reads '" + b1 + "', expected blank (all)");
            setBox(c, s, "Blink ticks", "4");
            click(c, s, button(c, s, "Save"));
            JsonObject saved = node(file, 1);
            JsonObject savedCharge = node(file, 0);
            JsonObject savedStop = node(file, 2);
            c.note("saved: " + savedCharge + " / " + saved + " / " + savedStop);
            c.check(saved.has("blinkTicks") && saved.get("blinkTicks").getAsInt() == 4, "blinkTicks 4 not saved: " + saved);
            c.check("BLINK_RELEASE".equals(saved.get("type").getAsString()) && saved.get("leapCounter").getAsInt() == 2,
                    "the release saved as " + saved);
            c.check(savedCharge.has("blinkTicks") && savedCharge.get("blinkTicks").getAsInt() == 3, "the charge saved as "
                    + savedCharge);
            c.check(!savedStop.has("blinkTicks"), "a Stop saved a blinkTicks: " + savedStop);
            c.onClient(mc -> {
                Mod.staticCall("ap3.Ap3Store", "reload");
                return null;
            });
            Object n1 = nodes(c).get(1);
            c.check((Integer) Mod.field(n1, "blinkTicks") == 4, "after reload the release has blinkTicks "
                    + Mod.field(n1, "blinkTicks"));
            s = open(c, 1);
            String again = boxText(c, s, "Blink ticks");
            c.note("after reload the release's Blink ticks box reads '" + again + "'");
            c.check("4".equals(again), "after reload the box reads '" + again + "'");
            // A Stop has no Blink ticks box.
            s = open(c, 2);
            c.check(boxText(c, s, "Blink ticks") == null, "a Stop's editor shows a Blink ticks box");
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });

            Object chargeType = Mod.enumValue("ap3.Ap3Node$Type", "BLINK_CHARGE");
            Object releaseType = Mod.enumValue("ap3.Ap3Node$Type", "BLINK_RELEASE");
            Object[] parsed = c.onClient(mc -> new Object[]{parse(chargeType, "3"), parse(chargeType, ""),
                    parse(releaseType, "6 wait:100 leapcounter:1"), parse(chargeType, "25"), parse(releaseType, "0"),
                    Mod.staticCall("ap3.Ap3Node$Type", "parse", "blinkcharge"),
                    Mod.staticCall("ap3.Ap3Node$Type", "parse", "blinkrelease")});
            c.check(parsed[0] != null && Integer.valueOf(3).equals(Mod.field(parsed[0], "blinkTicks")), "'3' parsed as "
                    + (parsed[0] == null ? "null" : Mod.field(parsed[0], "blinkTicks")));
            c.check(parsed[1] != null && Mod.field(parsed[1], "blinkTicks") == null, "no number did not mean the max");
            c.check(parsed[2] != null && Integer.valueOf(6).equals(Mod.field(parsed[2], "blinkTicks"))
                    && Integer.valueOf(1).equals(Mod.field(parsed[2], "leapCounter")), "'6 wait:100 leapcounter:1' parsed wrong");
            c.check(parsed[3] == null, "25 ticks (over 20) was accepted");
            c.check(parsed[4] == null, "0 ticks was accepted");
            c.check(chargeType.equals(parsed[5]) && releaseType.equals(parsed[6]), "the type words parse as " + parsed[5]
                    + " / " + parsed[6]);
            List<String> words = staticList("TYPE_WORDS");
            c.note("tab-complete type words: " + words);
            c.check(words.contains("blinkcharge") && words.contains("blinkrelease"), "tab-complete does not offer both");
            String[][] help = (String[][]) staticField("ADD_HELP");
            StringBuilder hb = new StringBuilder();
            for (String[] h : help) {
                hb.append(h[0]).append(" | ");
            }
            c.note("help: " + hb);
            c.check(hb.toString().contains("/ap3 add blinkcharge") && hb.toString().contains("/ap3 add blinkrelease"),
                    "help does not list both");
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

    private static Object staticField(String name) {
        try {
            Field f = Mod.cls("ap3.Ap3Commands").getDeclaredField(name);
            f.setAccessible(true);
            return f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Ap3Commands." + name + ": " + UiCase.describe(e), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> staticList(String name) {
        return (List<String>) staticField(name);
    }

    private static Object parse(Object type, String mods) {
        try {
            Method m = Mod.cls("ap3.Ap3Commands").getDeclaredMethod("parseSpec", Mod.cls("ap3.Ap3Node$Type"), String.class);
            m.setAccessible(true);
            return m.invoke(null, type, mods);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Ap3Commands.parseSpec: " + UiCase.describe(e), e);
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
        Ap3EditCases.clickFor1026(c, s, w);
    }
}

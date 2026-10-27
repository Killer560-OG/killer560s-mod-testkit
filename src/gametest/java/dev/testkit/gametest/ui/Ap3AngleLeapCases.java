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
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 1026-ui-ap3-angle-leapcounter (mod ap3-backwards-1026, cheat jar). killer560, 2026-10-26: "it tends to round of digits
 * after editing to be something like 167.899994 instead of .9 flat", and "the leapcounter should be a suffix option".
 * <ul>
 *   <li>A file already holding the noisy 167.899994 loads as the float of 167.9 and the editor's Yaw box reads "167.9".</li>
 *   <li>167.9 typed into the real editor and saved by a real click: the file's yaw is the JSON number 167.9 (not a float
 *       widened to 167.899994); reloaded, the node and the box read 167.9 again.</li>
 *   <li>An old Leap Counter NODE (count 2) loads as a Stop with leapcounter:2 and saves that way; a Boom's "leapCounter": 3
 *       loads; the editor's "Leap counter" box shows and saves it, and it survives a reload.</li>
 *   <li>{@code /ap3 add}'s parser takes {@code leapcounter:2} and {@code lc 3} and refuses {@code leapcounter:9}.</li>
 * </ul>
 * Mechanics (opening the editor, setting a box, a real click) follow {@link Ap3EditCases}.
 */
final class Ap3AngleLeapCases {

    private static final String SCREEN = "ap3.Ap3EditScreen";
    private static final double X0 = 900.5;

    private Ap3AngleLeapCases() {
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
                    + "  { \"type\": \"LOOK\", \"x\": " + X0 + ", \"y\": 70.0, \"z\": 20.5, \"yaw\": 167.899994,"
                    + " \"pitch\": 4.659372, \"width\": 1.0, \"length\": 1.0 },\n"
                    + "  { \"type\": \"LEAP_COUNTER\", \"x\": " + (X0 + 3) + ", \"y\": 70.0, \"z\": 20.5,"
                    + " \"width\": 1.0, \"length\": 1.0, \"leapCount\": 2 },\n"
                    + "  { \"type\": \"BOOM\", \"x\": " + (X0 + 6) + ", \"y\": 70.0, \"z\": 20.5, \"yaw\": 40.732506,"
                    + " \"pitch\": 4.659372, \"width\": 1.0, \"length\": 1.0, \"leapCounter\": 3 }\n"
                    + "] } } }";
            file = c.onClient(mc -> {
                Mod.call(cfg, "setChainsFile", "testkit-ap3-1026");
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
            float yaw0 = (Float) Mod.field(ns.get(0), "yaw");
            c.note("a file holding 167.899994 loads as the float " + yaw0);
            c.check(yaw0 == 167.9f, "the noisy 167.899994 loaded as " + yaw0);
            c.check("STOP".equals(String.valueOf(Mod.field(ns.get(1), "type")))
                            && (Integer) Mod.field(ns.get(1), "leapCounter") == 2,
                    "the old Leap Counter (2) loaded as " + Mod.field(ns.get(1), "type") + " leapCounter "
                            + Mod.field(ns.get(1), "leapCounter"));
            c.check((Integer) Mod.field(ns.get(2), "leapCounter") == 3, "the Boom's leapCounter 3 loaded as "
                    + Mod.field(ns.get(2), "leapCounter"));

            Screen s = open(c, 0);
            String shown = boxText(c, s, "Yaw");
            c.note("editor Yaw box for a file holding 167.899994: '" + shown + "'");
            c.check("167.9".equals(shown), "the Yaw box reads '" + shown + "', expected 167.9");
            setBox(c, s, "Yaw", "167.9");
            setBox(c, s, "Leap counter", "2");
            click(c, s, button(c, s, "Save"));
            JsonObject saved = node(file, 0);
            c.note("saved: " + saved);
            c.check("167.9".equals(saved.get("yaw").toString()), "the file's yaw is " + saved.get("yaw") + ", expected 167.9");
            c.check(saved.has("leapCounter") && saved.get("leapCounter").getAsInt() == 2, "leapCounter not saved: " + saved);
            JsonObject migrated = node(file, 1);
            c.note("migrated node as saved: " + migrated);
            c.check("STOP".equals(str(migrated, "type")) && migrated.has("leapCounter")
                    && migrated.get("leapCounter").getAsInt() == 2, "the migrated node saved as " + migrated);
            c.check(!migrated.has("leapCount"), "the migrated node still carries the old leapCount: " + migrated);
            JsonObject boomSaved = node(file, 2);
            c.check(boomSaved.has("leapCounter") && boomSaved.get("leapCounter").getAsInt() == 3
                    && "40.732506".equals(boomSaved.get("yaw").toString()), "the Boom saved as " + boomSaved);
            c.onClient(mc -> {
                Mod.staticCall("ap3.Ap3Store", "reload");
                return null;
            });
            Object n0 = nodes(c).get(0);
            c.check((Float) Mod.field(n0, "yaw") == 167.9f && (Integer) Mod.field(n0, "leapCounter") == 2,
                    "after reload: yaw " + Mod.field(n0, "yaw") + " leapCounter " + Mod.field(n0, "leapCounter"));
            s = open(c, 0);
            String yawAgain = boxText(c, s, "Yaw");
            String lcAgain = boxText(c, s, "Leap counter");
            c.note("after reload the editor reads Yaw '" + yawAgain + "', Leap counter '" + lcAgain + "'");
            c.check("167.9".equals(yawAgain) && "2".equals(lcAgain), "after reload: Yaw '" + yawAgain + "' Leap counter '"
                    + lcAgain + "'");
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            String described = c.onClient(mc -> String.valueOf(Mod.call(nodes(c).get(2), "describe")));
            c.note("the Boom describes as: " + described);
            c.check(described.contains("leapcounter:3"), "describe() does not show the suffix: " + described);

            Object boom = Mod.enumValue("ap3.Ap3Node$Type", "BOOM");
            c.onClient(mc -> {
                Object a = parse(boom, "leapcounter:2 wait:100");
                Object b = parse(boom, "lc 3");
                Object bad = parse(boom, "leapcounter:9");
                c.check(a != null && Integer.valueOf(2).equals(Mod.field(a, "leapCounter")), "leapcounter:2 parsed as "
                        + (a == null ? "null" : Mod.field(a, "leapCounter")));
                c.check(b != null && Integer.valueOf(3).equals(Mod.field(b, "leapCounter")), "lc 3 parsed as "
                        + (b == null ? "null" : Mod.field(b, "leapCounter")));
                c.check(bad == null, "leapcounter:9 (over the maximum) was accepted");
                return null;
            });
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

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
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

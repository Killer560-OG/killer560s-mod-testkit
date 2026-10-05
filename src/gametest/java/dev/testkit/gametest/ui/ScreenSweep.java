package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * Cases 304 (HUD editor) and 305 (every mod Screen).
 */
final class ScreenSweep {

    /** Measured on mod 8c43a6d: HudElementRegistry.all() after joining a world (see docs/wp/ui.md). */
    static final int HUD_ELEMENT_FLOOR = 20;

    private ScreenSweep() {
    }

    // ---- 304 ------------------------------------------------------------------------------------------------

    /**
     * HudEditorScreen with Show Unseen ON must list exactly the elements whose own setting is on
     * (hud/HudEditorScreen.java:104-113), and with it OFF no more than that. Every registered element's own render()
     * is called off screen too: the editor calls it for every listed element whether or not it has anything to show,
     * so a throw there is reachable for an enabled element. Then one real drag, which must land in HudConfig.
     */
    static void hudEditor(UiCase c) {
        Map<String, Object> r = c.onClient(mc -> {
            Map<String, Object> out = new LinkedHashMap<>();
            Object hudCfg = dev.testkit.gametest.mod.Mod.cfg("hud.HudConfig");
            boolean before = (Boolean) dev.testkit.gametest.mod.Mod.call(hudCfg, "isEditorShowAll");
            try {
                Class<?> reg = R.cls("hud.HudElementRegistry");
                @SuppressWarnings("unchecked")
                List<Object> all = (List<Object>) dev.testkit.gametest.mod.Mod.staticCall("hud.HudElementRegistry", "all");
                int enabled = 0;
                List<String> renderThrows = new ArrayList<>();
                List<String> enabledIds = new ArrayList<>();
                for (Object e : all) {
                    boolean on = (Boolean) reg.getMethod("isEnabledInSettings", R.cls("hud.HudElement")).invoke(null, e);
                    String id = (String) dev.testkit.gametest.mod.Mod.call(e, "id");
                    if (on) {
                        enabled++;
                        enabledIds.add(id);
                    }
                    try {
                        net.minecraft.client.renderer.state.gui.GuiRenderState state =
                                new net.minecraft.client.renderer.state.gui.GuiRenderState();
                        var g = new net.minecraft.client.gui.GuiGraphicsExtractor(mc, state, -1, -1);
                        Method render = R.cls("hud.HudElement").getMethod("render",
                                net.minecraft.client.gui.GuiGraphicsExtractor.class, int.class, int.class);
                        render.invoke(e, g, 10, 10);
                    } catch (InvocationTargetException ex) {
                        renderThrows.add(id + (on ? " (ENABLED)" : " (disabled)") + ": "
                                + UiCase.describe(ex.getCause()));
                    }
                }
                out.put("registered", all.size());
                out.put("enabled", enabled);
                out.put("enabledIds", enabledIds);
                out.put("renderThrows", renderThrows);

                dev.testkit.gametest.mod.Mod.call(hudCfg, "setEditorShowAll", true);
                Screen on = (Screen) R.cls("hud.HudEditorScreen").getConstructor(Screen.class).newInstance((Object) null);
                on.init(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
                List<?> shownOn = (List<?>) R.get(on, "shown");
                out.put("shownUnseenOn", shownOn.size());
                out.put("drawnOn", Frames.extractFrames(mc, on, 5).total());

                dev.testkit.gametest.mod.Mod.call(hudCfg, "setEditorShowAll", false);
                Screen off = (Screen) R.cls("hud.HudEditorScreen").getConstructor(Screen.class).newInstance((Object) null);
                off.init(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
                out.put("shownUnseenOff", ((List<?>) R.get(off, "shown")).size());
                Frames.extractFrames(mc, off, 2);

                // Box sizes as the editor computes them (element.width()/height(), hud/HudEditorScreen.java:169-183).
                // A listed element with an empty box cannot be grabbed: elementAt (line 186) never hits it.
                List<String> sizes = new ArrayList<>();
                List<String> zero = new ArrayList<>();
                Object grab = null;
                for (Object el : shownOn) {
                    String id = (String) dev.testkit.gametest.mod.Mod.call(el, "id");
                    int w = (Integer) dev.testkit.gametest.mod.Mod.call(el, "width");
                    int h = (Integer) dev.testkit.gametest.mod.Mod.call(el, "height");
                    sizes.add(id + " " + w + "x" + h);
                    if (w <= 0 || h <= 0) {
                        zero.add(id + " " + w + "x" + h);
                    } else if (grab == null && w > 2 && h > 2) {
                        grab = el;
                    }
                }
                out.put("sizes", sizes);
                out.put("zero", zero);
                // one real drag, on the centre of the first listed element with a real box (Show Unseen on)
                if (grab != null) {
                    Object el = grab;
                    String id = (String) dev.testkit.gametest.mod.Mod.call(el, "id");
                    @SuppressWarnings("unchecked")
                    Map<String, int[]> live = (Map<String, int[]>) R.get(on, "livePositions");
                    int cx = live.get(id)[0] + 2;
                    int cy = live.get(id)[1] + 2;
                    var info = new net.minecraft.client.input.MouseButtonInfo(0, 0);
                    boolean took = on.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(cx, cy, info), false);
                    // The editor grabs the TOPMOST box under the cursor (elementAt walks shown backwards,
                    // hud/HudEditorScreen.java:186); default positions overlap, so that may be another element.
                    Object grabbed = R.get(on, "draggingId");
                    if (grabbed != null) {
                        id = (String) grabbed;
                    }
                    int[] p = live.get(id).clone();
                    on.mouseDragged(new net.minecraft.client.input.MouseButtonEvent(cx + 10, cy + 6, info), 10, 6);
                    int[] moved = live.get(id).clone();
                    on.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(cx + 10, cy + 6, info));
                    out.put("dragTrace", "click took=" + took + " grabbed " + grabbed + ", live after drag "
                            + moved[0] + "," + moved[1]);
                    int[] saved = (int[]) dev.testkit.gametest.mod.Mod.call(hudCfg, "getPosition", id, -999, -999);
                    out.put("drag", id + " from " + p[0] + "," + p[1] + " -> HudConfig " + saved[0] + "," + saved[1]);
                    out.put("dragOk", saved[0] == p[0] + 10 && saved[1] == p[1] + 6);
                }
                McCompat.setScreen(mc, on);
            } catch (Throwable t) {
                out.put("error", UiCase.describe(t));
            } finally {
                dev.testkit.gametest.mod.Mod.call(hudCfg, "setEditorShowAll", true);
            }
            out.put("restoreShowAll", before);
            return out;
        });
        c.ticks(5);
        c.onClient(mc -> {
            dev.testkit.gametest.mod.Mod.set("hud.HudConfig", "setEditorShowAll", r.get("restoreShowAll"));
            McCompat.setScreen(mc, null);
            return null;
        });
        c.check(!r.containsKey("error"), "HUD editor: " + r.get("error"));
        int registered = (Integer) r.get("registered");
        int enabled = (Integer) r.get("enabled");
        c.note("HUD elements registered " + registered + ", enabled in settings " + enabled + " " + r.get("enabledIds")
                + "; editor lists " + r.get("shownUnseenOn") + " with Show Unseen ON, " + r.get("shownUnseenOff")
                + " with it OFF; drew " + r.get("drawnOn") + " element(s) in its smallest frame");
        @SuppressWarnings("unchecked")
        List<String> throwsList = (List<String>) r.get("renderThrows");
        for (String t : throwsList) {
            if (t.contains("(ENABLED)")) {
                c.problem("HudElement.render threw for an element the editor lists: " + t);
            } else {
                c.note("HudElement.render threw for a switched-off element (the editor never lists it): " + t);
            }
        }
        if (registered < HUD_ELEMENT_FLOOR) {
            c.problem("only " + registered + " HUD elements registered; floor " + HUD_ELEMENT_FLOOR);
        }
        if ((Integer) r.get("shownUnseenOn") != enabled) {
            c.problem("Show Unseen ON lists " + r.get("shownUnseenOn") + " elements but " + enabled
                    + " have their setting on (hud/HudEditorScreen.java:104-113 says those are the same set)");
        }
        if ((Integer) r.get("shownUnseenOff") > enabled) {
            c.problem("Show Unseen OFF lists more elements than are enabled");
        }
        c.note("listed box sizes: " + r.get("sizes"));
        @SuppressWarnings("unchecked")
        List<String> zero = (List<String>) r.get("zero");
        if (zero != null && !zero.isEmpty()) {
            c.problem("the HUD editor lists element(s) with an empty box, which no click can grab or resize "
                    + "(hud/HudEditorScreen.java:169-196 uses element.width()/height() as-is): " + zero);
        }
        if (r.containsKey("drag")) {
            c.note("drag: " + r.get("drag") + " (" + r.get("dragTrace") + ")");
            if (!(Boolean) r.get("dragOk")) {
                c.problem("a 10,6 drag in the HUD editor did not land in HudConfig: " + r.get("drag"));
            }
        } else {
            c.note("no HUD element enabled in settings, so no drag was made");
        }
    }

    // ---- 305 ------------------------------------------------------------------------------------------------

    record Built(String label, Screen screen) {
    }

    static void allScreens(UiCase c, Deny deny) {
        JsonObject spec = deny.screens;
        Map<String, JsonObject> allow = new LinkedHashMap<>();
        for (JsonElement e : spec.getAsJsonArray("screens")) {
            allow.put(e.getAsJsonObject().get("class").getAsString(), e.getAsJsonObject());
        }
        Map<String, String> denied = new LinkedHashMap<>();
        for (JsonElement e : spec.getAsJsonArray("deny")) {
            denied.put(e.getAsJsonObject().get("class").getAsString(), e.getAsJsonObject().get("why").getAsString());
        }
        // Completeness: every concrete Screen in the jar is in one list or the other.
        List<Class<?>> inJar = JarIndex.concreteSubclasses(Screen.class, s -> s.endsWith("Screen"));
        List<String> missing = new ArrayList<>();
        for (Class<?> k : inJar) {
            String rel = JarIndex.rel(k.getName());
            if (!allow.containsKey(rel) && !denied.containsKey(rel)) {
                missing.add(rel);
            }
        }
        if (!missing.isEmpty()) {
            c.problem("mod Screen class(es) in neither allow-screens.json list: " + missing);
        }
        c.note(inJar.size() + " concrete Screen classes in the jar; " + allow.size() + " allowed, " + denied.size()
                + " denied: " + denied);

        int[] n = new int[3]; // constructed, extracted clean, real-framed
        List<String> realOk = new ArrayList<>();
        java.util.Set<String> jarClasses = new java.util.HashSet<>(JarIndex.classNames(s -> s.endsWith("Screen")));
        for (Map.Entry<String, JsonObject> entry : allow.entrySet()) {
            String rel = entry.getKey();
            if (!jarClasses.contains(JarIndex.ROOT + rel)) {
                c.note(rel + ": not in this jar (variant), skipped");
                continue;
            }
            List<Built> built = c.onClient(mc -> construct(c, mc, rel, entry.getValue()));
            for (Built b : built) {
                n[0]++;
                boolean clean = c.onClient(mc -> {
                    try {
                        b.screen().init(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
                        Frames.Drawn d = Frames.extractFrames(mc, b.screen(), 5);
                        if (d.total() == 0) {
                            c.problem(b.label() + ": drew nothing at all");
                        }
                        return true;
                    } catch (Throwable t) {
                        c.problem(b.label() + ": init/render threw " + UiCase.describe(t));
                        return false;
                    }
                });
                if (!clean) {
                    continue;
                }
                n[1]++;
                c.onClient(mc -> {
                    McCompat.setScreen(mc, b.screen());
                    return null;
                });
                c.ticks(3);
                boolean stillUp = c.onClient(mc -> McCompat.screen(mc) == b.screen());
                c.onClient(mc -> {
                    McCompat.setScreen(mc, null);
                    return null;
                });
                c.ticks(1);
                n[2]++;
                realOk.add(b.label() + (stillUp ? "" : " (closed itself)"));
            }
        }
        c.note("constructed " + n[0] + ", extracted clean " + n[1] + ", shown on the real window " + n[2]);
        c.note("shown: " + realOk);
        c.check(n[0] >= allow.size() - 2, "constructed only " + n[0] + " of " + allow.size() + " allowed screens");
    }

    private static List<Built> construct(UiCase c, Minecraft mc, String rel, JsonObject spec) {
        List<Built> out = new ArrayList<>();
        Class<?> k = R.cls(rel);
        List<String> args = Deny.strings(spec, "args");
        String factory = spec.has("factory") ? spec.get("factory").getAsString() : null;
        Executable exe = null;
        for (Executable e : factory != null ? List.of(k.getMethods()).stream()
                .filter(m -> m.getName().equals(factory) && Modifier.isStatic(m.getModifiers()))
                .map(m -> (Executable) m).toList() : List.of((Executable[]) k.getConstructors())) {
            if (e.getParameterCount() == args.size()) {
                exe = e;
                break;
            }
        }
        if (exe == null) {
            c.problem(rel + ": no public " + (factory != null ? "static " + factory : "constructor") + " with "
                    + args.size() + " parameter(s) - the screen's signature changed; update allow-screens.json");
            return out;
        }
        // enum:* expands into one screen per constant
        List<Object[]> calls = new ArrayList<>();
        calls.add(new Object[args.size()]);
        Class<?>[] types = exe.getParameterTypes();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("enum:*")) {
                List<Object[]> expanded = new ArrayList<>();
                for (Object[] call : calls) {
                    for (Object constant : types[i].getEnumConstants()) {
                        Object[] copy = call.clone();
                        copy[i] = constant;
                        expanded.add(copy);
                    }
                }
                calls = expanded;
            } else {
                Object v = value(a, types[i]);
                for (Object[] call : calls) {
                    call[i] = v;
                }
            }
        }
        for (Object[] call : calls) {
            String label = rel + (calls.size() > 1 ? "(" + enumNames(call) + ")" : "");
            try {
                Object s = exe instanceof Constructor<?> ctor ? ctor.newInstance(call) : ((Method) exe).invoke(null, call);
                out.add(new Built(label, (Screen) s));
            } catch (InvocationTargetException e) {
                c.problem(label + ": constructor threw " + UiCase.describe(e.getCause()));
            } catch (ReflectiveOperationException e) {
                c.problem(label + ": " + e);
            }
        }
        return out;
    }

    private static String enumNames(Object[] call) {
        List<String> names = new ArrayList<>();
        for (Object o : call) {
            if (o instanceof Enum<?> e) {
                names.add(e.name());
            }
        }
        return String.join(",", names);
    }

    private static Object value(String a, Class<?> type) {
        if (a.equals("null")) {
            return null;
        }
        if (a.startsWith("int:")) {
            return Integer.parseInt(a.substring(4));
        }
        if (a.startsWith("bool:")) {
            return Boolean.parseBoolean(a.substring(5));
        }
        if (a.startsWith("str:")) {
            return a.substring(4);
        }
        if (a.startsWith("float:")) {
            return Float.parseFloat(a.substring(6));
        }
        if (a.equals("intconsumer")) {
            return (IntConsumer) v -> { };
        }
        if (a.equals("pvtarget")) {
            try {
                return type.getConstructor(String.class, UUID.class)
                        .newInstance("Notch", UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("[ui] cannot build " + type.getName(), e);
            }
        }
        throw new AssertionError("[ui] unknown arg spec '" + a + "' in allow-screens.json");
    }
}

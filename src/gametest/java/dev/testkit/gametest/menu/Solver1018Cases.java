package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * killer560's 2026-10-18 dungeon-doc batch "solver-sound" (mod branch worktree-agent-afda21ebbc4d90d20): items 12, 14, 18, 23,
 * 24, 33 and 45 of his list. Every case first proves its premise (the thing under test acted), then asserts the new behaviour.
 * Run with {@code -Pscenario=-menu-} and {@code TESTKIT_MENU_ONLY=1018}.
 *
 * <ul>
 *   <li>1018-croesus-page-match (14): two pages of the same party's runs read back to back must not share run memory</li>
 *   <li>1018-magebeam-shape (18): the beam's faces are fixed to the world, not turned to the camera; Square is the default</li>
 *   <li>1018-doorkeys-circle (23): the circle sits higher, has its own thickness and colours, all saved</li>
 *   <li>1018-roomalerts-current-room (24): only the room he is in, no room name, colours, no On-screen Text switch</li>
 *   <li>1018-scorecalc-toggles-colors (33): Crypts and Deaths separate, the two lines gone, the HUD colours</li>
 *   <li>1018-clicksound-volume (12): 100-500% by layering, on every screen, config clamps and survives a reload</li>
 *   <li>1018-clicksound-slot-click (12): a click on a slot of a chest-style Skyblock menu plays the click</li>
 *   <li>1018-clicksound-import (12): a .wav imports and decodes; an mp3 through ffmpeg when ffmpeg is installed</li>
 *   <li>1018-customitems-color (45): changing a Custom Items colour through the real picker draws without a crash</li>
 * </ul>
 */
final class Solver1018Cases {

    private Solver1018Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1018-croesus-page-match", Solver1018Cases::croesus);
        MenuSuite.test(s, "1018-magebeam-shape", Solver1018Cases::mageBeam);
        MenuSuite.test(s, "1018-doorkeys-circle", Solver1018Cases::doorKeys);
        MenuSuite.test(s, "1018-roomalerts-current-room", Solver1018Cases::roomAlerts);
        MenuSuite.test(s, "1018-scorecalc-toggles-colors", Solver1018Cases::scoreCalc);
        MenuSuite.test(s, "1018-clicksound-volume", Solver1018Cases::clickVolume);
        MenuSuite.test(s, "1018-clicksound-slot-click", Solver1018Cases::slotClick);
        MenuSuite.test(s, "1018-clicksound-import", Solver1018Cases::clickImport);
        MenuSuite.test(s, "1018-customitems-color", Solver1018Cases::customItems);
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    /** Every button/label text a tab builds, with the Skyblock gate off so the tab shows all its rows. */
    @SuppressWarnings("unchecked")
    private static List<String> tabTexts(Session c, String tabClass, String enableCfg, String enableProp) throws Exception {
        try (AutoCloseable gate = gateOff()) {
            return c.onClient(mc -> {
                try (AutoCloseable on = enableCfg == null ? () -> { } : Mod.with(enableCfg, enableProp, true)) {
                    Object tab = Mod.cls(tabClass).getConstructor().newInstance();
                    List<Object> widgets = (List<Object>) Mod.call(tab, "buildWidgets", 10, 10, 300, (Runnable) () -> { });
                    List<String> out = new ArrayList<>();
                    for (Object w : widgets) {
                        out.add(((AbstractWidget) w).getMessage().getString());
                    }
                    return out;
                } catch (Exception e) {
                    throw new AssertionError("building " + tabClass + ": " + e, e);
                }
            });
        }
    }

    /** Skyblock Only off (the test server is not Skyblock), put back on close. */
    private static AutoCloseable gateOff() {
        boolean was = (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled");
        Mod.staticCall("util.SkyblockGate", "setEnabled", false);
        return () -> Mod.staticCall("util.SkyblockGate", "setEnabled", was);
    }

    private static boolean anyStarts(List<String> texts, String prefix) {
        return texts.stream().anyMatch(t -> t.startsWith(prefix));
    }

    private static boolean anyContains(List<String> texts, String part) {
        return texts.stream().anyMatch(t -> t.contains(part));
    }

    // ---- 14: Croesus page memory -----------------------------------------------------------------------------------

    private static final String MEM = "croesus.CroesusRunMemory";

    private static List<Object> entries(long now, int firstOrder, int count, int orderOffset) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // The head's lore as Croesus gives it: hours only, so every run of the day is inside every other's window.
            out.add(Mod.staticCall(MEM, "entry", i, firstOrder + i + orderOffset, "The Catacombs|Floor VII|Bravo",
                    List.of("Chests expire in 1d 16h!"), now));
        }
        return out;
    }

    /** 28 entries ten minutes apart, expiry given to the minute, the first at {@code orderOffset}. */
    private static List<Object> distinctEntries(long now, int orderOffset) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < 28; i++) {
            int total = 40 * 60 + i * 10;
            String text = "Chests expire in " + total / 1440 + "d " + (total % 1440) / 60 + "h " + total % 60 + "m!";
            out.add(Mod.staticCall(MEM, "entry", i, i + orderOffset, "The Catacombs|Floor VII|Bravo", List.of(text), now));
        }
        return out;
    }

    private static int matched(Object[] runs) {
        int n = 0;
        for (Object r : runs) {
            if (r != null) {
                n++;
            }
        }
        return n;
    }

    static void croesus(Session c) throws Exception {
        c.onClient(mc -> {
            Mod.staticCall(MEM, "clearForTest");
            return null;
        });
        try {
            long now = System.currentTimeMillis();
            // Page 1 is read and Auto Croesus opens each of its 28 runs: 28 remembered runs at places 0..27.
            Object[] runs = c.onClient(mc -> {
                List<Object> page1 = entries(now, 0, 28, 0);
                Object[] made = new Object[28];
                for (int i = 0; i < 28; i++) {
                    made[i] = Mod.staticCall(MEM, "create", page1.get(i));
                }
                return made;
            });
            // Premise: the same page read again is matched run for run (the memory works at all).
            Object[] again = c.onClient(mc -> (Object[]) Mod.staticCall(MEM, "match", entries(now, 0, 28, 0)));
            c.check(matched(again) == 28, "premise: re-reading page 1 matched " + matched(again) + "/28 remembered runs");
            for (int i = 0; i < 28; i++) {
                c.check(again[i] == runs[i], "premise: page 1 entry " + i + " matched another run");
            }
            // Page 2: 28 different runs of the same party, expiring inside page 1's windows. None is remembered, and a
            // run last seen on page 1 a moment ago cannot have moved a whole page.
            Object[] page2 = c.onClient(mc -> (Object[]) Mod.staticCall(MEM, "match", entries(now, 28, 28, 0)));
            // A run at the foot of page 1 can honestly have been pushed onto page 2 by a run or two joining the list, so the
            // two boundary runs may follow; the rest of the page may not (the old matcher took all 28).
            c.check(matched(page2) <= 2, "page 2 took on " + matched(page2) + " of page 1's remembered runs (want at most 2)");
            for (int i = 0; i < 26; i++) {
                Number order = (Number) Mod.field(runs[i], "order");
                c.check(order.intValue() == i, "page 1's run " + i + " was moved to place " + order + " by reading page 2");
            }
            // A real shift still follows. Runs whose expiry texts differ (to the minute, ten apart) are re-read with a new run
            // in front: every run moves back one place and is followed there.
            c.onClient(mc -> {
                Mod.staticCall(MEM, "clearForTest");
                return null;
            });
            Object[] distinct = c.onClient(mc -> {
                List<Object> first = distinctEntries(now, 0);
                Object[] made = new Object[28];
                for (int i = 0; i < 28; i++) {
                    made[i] = Mod.staticCall(MEM, "create", first.get(i));
                }
                return made;
            });
            Object[] shifted = c.onClient(mc -> (Object[]) Mod.staticCall(MEM, "match", distinctEntries(now, 1)));
            c.check(matched(shifted) == 28, "after a new run joined the list " + matched(shifted) + " of 28 runs were followed (want 28)");
            Number moved = (Number) Mod.field(distinct[3], "order");
            c.check(moved.intValue() == 4, "run 3 should now be at place 4, was " + moved);
            c.note("page 1 matched 28/28, page 2 matched " + matched(page2) + "/28, a one-place shift followed " + matched(shifted) + "/28");
        } finally {
            c.onClient(mc -> {
                Mod.staticCall(MEM, "clearForTest");
                return null;
            });
        }
    }

    // ---- 18: Mage beam ----------------------------------------------------------------------------------------------

    private static final String BEAM = "dungeonextras.MageBeamFeature";

    @SuppressWarnings("unchecked")
    private static List<Vec3[]> quads(Vec3 a, Vec3 b, int shape, float width) {
        return (List<Vec3[]>) Mod.staticCall(BEAM, "beamQuads", a, b, shape, width);
    }

    private static Vec3 normal(Vec3[] q) {
        return q[1].subtract(q[0]).cross(q[3].subtract(q[0])).normalize();
    }

    private static double distToAxis(Vec3 p, Vec3 a, Vec3 dir) {
        Vec3 rel = p.subtract(a);
        return rel.subtract(dir.scale(rel.dot(dir))).length();
    }

    static void mageBeam(Session c) throws Exception {
        Vec3 a = new Vec3(0, 64, 0);
        Vec3 b = new Vec3(10, 64, 5);   // a horizontal beam running off at an angle
        Vec3 dir = b.subtract(a).normalize();
        float w = 0.4f;
        List<Vec3[]> square = quads(a, b, 0, w);
        c.check(square.size() == 4, "Square has " + square.size() + " faces (want 4)");
        double half = w / 2.0;
        int up = 0;
        int side = 0;
        for (Vec3[] q : square) {
            for (Vec3 p : q) {
                c.check(Math.abs(distToAxis(p, a, dir) - half * Math.sqrt(2)) < 1e-6,
                        "a Square corner is " + distToAxis(p, a, dir) + " from the axis");
            }
            Vec3 n = normal(q);
            if (Math.abs(Math.abs(n.y) - 1.0) < 1e-6) {
                up++;
            } else if (Math.abs(n.y) < 1e-6) {
                side++;
            }
        }
        // Fixed to the world: one face straight up, one straight down, two vertical. Nothing here knows the camera,
        // so walking round the beam cannot turn it (the old one was a quad turned to face the camera every frame).
        c.check(up == 2 && side == 2, "Square faces: " + up + " horizontal and " + side + " vertical (want 2 and 2)");
        // The same call is the same beam, wherever he stands.
        List<Vec3[]> again = quads(a, b, 0, w);
        for (int i = 0; i < 4; i++) {
            for (int k = 0; k < 4; k++) {
                c.check(again.get(i)[k].distanceTo(square.get(i)[k]) < 1e-9, "the beam's corners changed between calls");
            }
        }
        List<Vec3[]> flat = quads(a, b, 1, w);
        c.check(flat.size() == 1, "Flat has " + flat.size() + " faces (want 1)");
        if (flat.size() == 1) {
            c.check(Math.abs(Math.abs(normal(flat.get(0)).y) - 1.0) < 1e-6, "Flat is not a horizontal ribbon");
        }
        List<Vec3[]> cyl = quads(a, b, 2, w);
        c.check(cyl.size() == 8, "Cylinder has " + cyl.size() + " faces (want 8)");
        for (Vec3[] q : cyl) {
            for (Vec3 p : q) {
                c.check(Math.abs(distToAxis(p, a, dir) - half) < 1e-6, "a Cylinder corner is off the radius");
            }
        }
        // A vertical beam has no horizontal side to call up: it must still make a square, not a degenerate sliver.
        List<Vec3[]> vertical = quads(new Vec3(0, 60, 0), new Vec3(0, 70, 0), 0, w);
        c.check(vertical.size() == 4 && vertical.stream().allMatch(q -> normal(q).length() > 0.99), "a vertical Square beam is degenerate");

        // Square is the default, and the shape is saved.
        String cfgClass = "dungeonextras.DungeonExtrasConfig";
        try (AutoCloseable keep = Mod.with(cfgClass, "MageBeamShape", 0)) {
            Mod.staticCall(cfgClass, "load");
            Object fresh = Mod.get(cfgClass, "getMageBeamShape");
            Mod.set(cfgClass, "setMageBeamShape", 2);
            Mod.call(Mod.cfg(cfgClass), "save");
            Mod.staticCall(cfgClass, "load");
            c.check(((Number) Mod.get(cfgClass, "getMageBeamShape")).intValue() == 2, "the beam shape did not survive a reload");
            Mod.set(cfgClass, "setMageBeamShape", 9);
            c.check(((Number) Mod.get(cfgClass, "getMageBeamShape")).intValue() == 0, "an unknown shape did not fall back to Square");
            c.note("fresh shape " + fresh + " (0 = Square)");
        }
        List<String> tab = tabTexts(c, "gui.tab.CustomMageBeamTab", cfgClass, "MageBeamEnabled");
        c.check(anyStarts(tab, "Shape:"), "the Custom Mage Beam tab has no Shape row: " + tab);
    }

    // ---- 23: Door Keys circle ---------------------------------------------------------------------------------------

    static void doorKeys(Session c) throws Exception {
        String cfgClass = "doorkeys.DoorKeysConfig";
        String feature = "doorkeys.DoorKeysFeature";
        double stand = 64.0;
        double y = (Double) Mod.staticCall(feature, "circleY", stand, 0.5, 0.5f);
        c.check(y > stand + 0.5, "the circle is at " + y + ", not above the stand's feet (" + stand + ")");
        // Premise: the points really are built at that height.
        Vec3[] pts = (Vec3[]) Mod.staticCall(feature, "ringPoints", new double[]{10, stand, 10, 5}, y);
        c.check(pts.length > 8 && java.util.Arrays.stream(pts).allMatch(p -> Math.abs(p.y - y) < 1e-9),
                "the ring points are not all at the circle's height");
        float[] auto = (float[]) Mod.staticCall(feature, "resolveColor", 0, new float[]{0.1f, 0.2f, 0.3f}, 1f);
        c.check(auto[0] == 0.1f && auto[1] == 0.2f && auto[2] == 0.3f && auto[3] == 1f, "Auto did not follow the key colour");
        float[] custom = (float[]) Mod.staticCall(feature, "resolveColor", 0xFF336699, new float[]{0.1f, 0.2f, 0.3f}, 1f);
        c.check(Math.abs(custom[0] - 0x33 / 255f) < 0.01f && Math.abs(custom[2] - 0x99 / 255f) < 0.01f, "a chosen colour was not used");
        try (AutoCloseable t = Mod.with(cfgClass, "CircleThickness", 2f);
             AutoCloseable l = Mod.with(cfgClass, "CircleLift", 0.5f);
             AutoCloseable a = Mod.with(cfgClass, "TracerColor", 0);
             AutoCloseable b = Mod.with(cfgClass, "CircleColor", 0);
             AutoCloseable f = Mod.with(cfgClass, "FillColor", 0)) {
            Mod.set(cfgClass, "setCircleThickness", 99f);
            c.check((Float) Mod.get(cfgClass, "getCircleThickness") == 10f, "thickness is not clamped to 10");
            Mod.set(cfgClass, "setCircleThickness", 6f);
            Mod.set(cfgClass, "setCircleLift", 1.5f);
            Mod.set(cfgClass, "setTracerColor", 0xFF112233);
            Mod.set(cfgClass, "setCircleColor", 0xFF445566);
            Mod.set(cfgClass, "setFillColor", 0x80778899);
            Mod.call(Mod.cfg(cfgClass), "save");
            Mod.staticCall(cfgClass, "load");
            c.check((Float) Mod.get(cfgClass, "getCircleThickness") == 6f, "thickness did not survive a reload");
            c.check((Float) Mod.get(cfgClass, "getCircleLift") == 1.5f, "height did not survive a reload");
            c.check((Integer) Mod.get(cfgClass, "getTracerColor") == 0xFF112233, "tracer colour did not survive a reload");
            c.check((Integer) Mod.get(cfgClass, "getCircleColor") == 0xFF445566, "circle colour did not survive a reload");
            c.check((Integer) Mod.get(cfgClass, "getFillColor") == 0x80778899, "fill colour did not survive a reload");
        }
        try (AutoCloseable sphere = Mod.with(cfgClass, "RangeStyle", 1)) {
            List<String> texts = tabTexts(c, "gui.tab.DoorKeysTab", cfgClass, "Enabled");
            c.check(anyStarts(texts, "Circle Thickness:"), "no Circle Thickness slider: " + texts);
            // Labels are British since mod 1c10b812 (settings sweep: "colour", not "color").
            c.check(anyStarts(texts, "Circle Colour:") && anyStarts(texts, "Tracer Colour:"), "no circle/tracer colour rows: " + texts);
            c.check(anyStarts(texts, "Sphere Fill:"), "Sphere style has no fill colour row: " + texts);
        }
        try (AutoCloseable radius = Mod.with(cfgClass, "RangeStyle", 0)) {
            List<String> texts = tabTexts(c, "gui.tab.DoorKeysTab", cfgClass, "Enabled");
            c.check(anyStarts(texts, "Circle Height:"), "Radius style has no Circle Height slider: " + texts);
            c.check(!anyStarts(texts, "Sphere Fill:"), "Radius style shows a sphere fill row");
        }
    }

    // ---- 24: Room Alerts --------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static void roomAlerts(Session c) throws Exception {
        String alerts = "dungeonalerts.RoomAlerts";
        String cfgClass = "dungeonalerts.DungeonAlertsConfig";
        Set<Integer> armed = new HashSet<>();
        Set<Integer> done = new HashSet<>();
        // Walking into a room that is already cleared (a teammate did it) is silent, and stays silent.
        c.check(!(Boolean) Mod.staticCall(alerts, "clearedAlertDue", 7, true, armed, done), "an already-cleared room alerted on entry");
        c.check(!(Boolean) Mod.staticCall(alerts, "clearedAlertDue", 7, false, armed, done), "a settled room alerted later");
        // A room he sees uncleared and then sees clear alerts, once.
        c.check(!(Boolean) Mod.staticCall(alerts, "clearedAlertDue", 8, false, armed, done), "an uncleared room alerted");
        c.check((Boolean) Mod.staticCall(alerts, "clearedAlertDue", 8, true, armed, done), "a room cleared in front of him did not alert");
        c.check(!(Boolean) Mod.staticCall(alerts, "clearedAlertDue", 8, true, armed, done), "a room alerted twice");
        c.check((Boolean) Mod.staticCall(alerts, "isInRoom", 5, 7, new int[]{7, 8, 5}), "a cell of a multi-tile room is not in its room");
        c.check(!(Boolean) Mod.staticCall(alerts, "isInRoom", 6, 7, new int[]{7, 8, 5}), "a cell of another room is in the room");

        c.check("Room Cleared!".equals(Mod.field(alerts, "CLEARED_TEXT")), "the cleared text is " + Mod.field(alerts, "CLEARED_TEXT"));
        c.check("Secrets Done!".equals(Mod.field(alerts, "SECRETS_TEXT")), "the secrets text is " + Mod.field(alerts, "SECRETS_TEXT"));

        // Colours: defaults, a raised alert carrying the chosen one, and the shadow assassin's purple subtitle.
        Object cfg = Mod.cfg(cfgClass);
        int sa = (Integer) Mod.field(cfg, "shadowAssassinColor");
        int r = (sa >> 16) & 255;
        int g = (sa >> 8) & 255;
        int b = sa & 255;
        c.check(b > g && r > g && b >= r, String.format("the shadow assassin default #%06X is not a purple", sa));
        int oldCleared = (Integer) Mod.field(cfg, "roomAlertsClearedColor");
        int oldSecrets = (Integer) Mod.field(cfg, "roomAlertsSecretsColor");
        int oldSa = sa;
        try {
            c.onClient(mc -> {
                Mod.staticCall(alerts, "fireAlert", cfg, "Room Cleared!", 0x12AB34);
                return null;
            });
            c.check("Room Cleared!".equals(Mod.staticCall(alerts, "lastAlertText")), "the alert text is not the one raised");
            c.check((Integer) Mod.staticCall(alerts, "lastAlertColor") == 0x12AB34, "the alert did not carry the chosen colour");
            setField(cfg, "shadowAssassinColor", 0x336699);
            Component sub = (Component) Mod.staticCall("dungeonalerts.ShadowAssassinAlert", "subtitle", cfg);
            c.check("Shadow Assassin!".equals(sub.getString()), "the subtitle reads " + sub.getString());
            c.check(sub.getStyle().getColor() != null && sub.getStyle().getColor().getValue() == 0x336699,
                    "the subtitle is not in the chosen colour: " + sub.getStyle().getColor());
            // Saved.
            setField(cfg, "roomAlertsClearedColor", 0x010203);
            setField(cfg, "roomAlertsSecretsColor", 0x040506);
            Mod.call(cfg, "save");
            Mod.staticCall(cfgClass, "load");
            Object back = Mod.cfg(cfgClass);
            c.check((Integer) Mod.field(back, "roomAlertsClearedColor") == 0x010203, "cleared colour did not survive a reload");
            c.check((Integer) Mod.field(back, "roomAlertsSecretsColor") == 0x040506, "secrets colour did not survive a reload");
            c.check((Integer) Mod.field(back, "shadowAssassinColor") == 0x336699, "shadow assassin colour did not survive a reload");
        } finally {
            Object back = Mod.cfg(cfgClass);
            setField(back, "roomAlertsClearedColor", oldCleared);
            setField(back, "roomAlertsSecretsColor", oldSecrets);
            setField(back, "shadowAssassinColor", oldSa);
            Mod.call(back, "save");
        }
        Object live = Mod.cfg(cfgClass);
        boolean wasRoom = (Boolean) Mod.field(live, "roomAlertsEnabled");
        boolean wasSa = (Boolean) Mod.field(live, "shadowAssassinEnabled");
        try {
            setField(live, "roomAlertsEnabled", true);
            setField(live, "shadowAssassinEnabled", true);
            List<String> texts = tabTexts(c, "gui.tab.DungeonAlertsTab", null, null);
            c.check(!anyContains(texts, "On-screen Text"), "the On-screen Text switch is still there: " + texts);
            c.check(anyStarts(texts, "Cleared Colour") && anyStarts(texts, "Secrets Colour"), "no room alert colour rows: " + texts);
            c.check(anyStarts(texts, "Alert Colour"), "no shadow assassin colour row: " + texts);
        } finally {
            setField(live, "roomAlertsEnabled", wasRoom);
            setField(live, "shadowAssassinEnabled", wasSa);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no field " + name + " on " + target.getClass().getName(), e);
        }
    }

    // ---- 33: Score Calculator ---------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<List<Object>> hudLines(Object cfg, boolean demo) {
        Class<?> feature = Mod.cls("scorecalc.ScoreCalculatorFeature");
        for (java.lang.reflect.Method m : feature.getDeclaredMethods()) {
            if (m.getName().equals("buildLines") && m.getParameterCount() == 2) {
                m.setAccessible(true);
                try {
                    return (List<List<Object>>) m.invoke(null, cfg, demo);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("buildLines threw: " + e, e);
                }
            }
        }
        throw new AssertionError("no buildLines on ScoreCalculatorFeature");
    }

    private static String textOf(Object segment) {
        return (String) Mod.call(segment, "text");
    }

    private static int colorOf(Object segment) {
        return (Integer) Mod.call(segment, "color");
    }

    private static Object findSegment(List<List<Object>> lines, String text) {
        for (List<Object> line : lines) {
            for (Object s : line) {
                if (textOf(s).equals(text)) {
                    return s;
                }
            }
        }
        return null;
    }

    static void scoreCalc(Session c) throws Exception {
        String cfgClass = "scorecalc.ScoreCalculatorConfig";
        Path file = (Path) Mod.field(cfgClass, "CONFIG_PATH");
        byte[] saved = Files.exists(file) ? Files.readAllBytes(file) : null;
        try {
            // An old file with the single switch OFF turns both new ones OFF; with them both there, each stands alone.
            Files.createDirectories(file.getParent());
            Files.writeString(file, "{\"enabled\":true,\"showCryptsDeaths\":false}", StandardCharsets.UTF_8);
            Mod.staticCall(cfgClass, "load");
            c.check(!(Boolean) Mod.get(cfgClass, "isShowCrypts") && !(Boolean) Mod.get(cfgClass, "isShowDeaths"),
                    "an old Crypts & Deaths OFF did not carry into both");
            Files.writeString(file, "{\"enabled\":true,\"showCryptsDeaths\":true,\"showCrypts\":false}", StandardCharsets.UTF_8);
            Mod.staticCall(cfgClass, "load");
            c.check(!(Boolean) Mod.get(cfgClass, "isShowCrypts") && (Boolean) Mod.get(cfgClass, "isShowDeaths"),
                    "Crypts OFF / Deaths ON did not load as written");

            Object cfg = Mod.cfg(cfgClass);
            // Premise: with both on, the demo HUD has a Crypts and a Deaths segment (the HUD built at all).
            Mod.call(cfg, "setShowCrypts", true);
            Mod.call(cfg, "setShowDeaths", true);
            List<List<Object>> both = hudLines(cfg, true);
            c.check(findSegment(both, "Crypts: ") != null && findSegment(both, "  Deaths: ") != null,
                    "premise: the demo HUD has no Crypts / Deaths segments");
            Mod.call(cfg, "setShowCrypts", false);
            List<List<Object>> deathsOnly = hudLines(cfg, true);
            c.check(findSegment(deathsOnly, "Crypts: ") == null, "Crypts is OFF but the HUD still shows it");
            c.check(findSegment(deathsOnly, "Deaths: ") != null, "Deaths is ON but the HUD does not show it");
            Mod.call(cfg, "setShowCrypts", true);
            Mod.call(cfg, "setShowDeaths", false);
            List<List<Object>> cryptsOnly = hudLines(cfg, true);
            c.check(findSegment(cryptsOnly, "Crypts: ") != null && findSegment(cryptsOnly, "  Deaths: ") == null
                    && findSegment(cryptsOnly, "Deaths: ") == null, "Deaths is OFF but the HUD still shows it");

            // Colours.
            Mod.call(cfg, "setShowDeaths", true);
            Mod.call(cfg, "setLabelColor", 0x112233);
            Mod.call(cfg, "setBadColor", 0x445566);
            Mod.call(cfg, "setGoodColor", 0x778899);
            List<List<Object>> coloured = hudLines(cfg, true);
            c.check(colorOf(findSegment(coloured, "Crypts: ")) == 0x112233, "the Crypts label is not in the Label Color");
            c.check(colorOf(findSegment(coloured, "Score: ")) == 0x112233, "the Score label is not in the Label Color");
            Mod.call(cfg, "save");
            Mod.staticCall(cfgClass, "load");
            c.check((Integer) Mod.get(cfgClass, "getLabelColor") == 0x112233 && (Integer) Mod.get(cfgClass, "getBadColor") == 0x445566
                    && (Integer) Mod.get(cfgClass, "getGoodColor") == 0x778899, "the HUD colours did not survive a reload");
            int key1 = (Integer) Mod.get(cfgClass, "colorsKey");
            Mod.set(cfgClass, "setValueColor", 0x0A0B0C);
            c.check((Integer) Mod.get(cfgClass, "colorsKey") != key1, "the HUD cache key ignores a colour change");

            // The tab: the two lines are gone, Crypts and Deaths are two rows, colour rows exist.
            List<String> texts = tabTexts(c, "gui.tab.ScoreCalculatorTab", cfgClass, "Enabled");
            c.check(!anyContains(texts, "Estimates the dungeon score"), "the 'Estimates the dungeon score' line is still there");
            c.check(!anyContains(texts, "at most once per run"), "the 'send at most once per run' line is still there");
            c.check(!anyContains(texts, "Crypts & Deaths"), "Crypts & Deaths is still one switch");
            c.check(anyStarts(texts, "Crypts:") && anyStarts(texts, "Deaths:"), "no separate Crypts / Deaths rows: " + texts);
            c.check(anyStarts(texts, "Label Colour") && anyStarts(texts, "Good Colour") && anyStarts(texts, "Bad Colour"),
                    "no HUD colour rows: " + texts);
        } finally {
            if (saved == null) {
                Files.deleteIfExists(file);
            } else {
                Files.write(file, saved);
            }
            Mod.staticCall(cfgClass, "load");
        }
    }

    // ---- 12: click sounds -------------------------------------------------------------------------------------------

    private static final String CLICKS = "clicksound.ClickSounds";
    private static final String CLICK_CFG = "clicksound.ClickSoundConfig";

    /** A real SoundManager.play of vanilla's button click: whatever the mixin hands the engine is recorded. */
    private static String click(Session c) {
        c.onClient(mc -> {
            mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 1.0f));
            return null;
        });
        return (String) Mod.staticCall(CLICKS, "lastPlayedId");
    }

    private static int layers() {
        return (Integer) Mod.staticCall(CLICKS, "layersPlayed");
    }

    static void clickVolume(Session c) throws Exception {
        Path cfgFile = (Path) Mod.field(CLICK_CFG, "CONFIG_PATH");
        byte[] saved = Files.exists(cfgFile) ? Files.readAllBytes(cfgFile) : null;
        try (AutoCloseable a = Mod.with(CLICK_CFG, "Choice", "default");
             AutoCloseable b = Mod.with(CLICK_CFG, "Volume", 1.0f)) {
            // The layering arithmetic.
            c.check(((float[]) Mod.staticCall(CLICKS, "extraLayers", 1.0f)).length == 0, "100% has extra layers");
            c.check(((float[]) Mod.staticCall(CLICKS, "extraLayers", 0.5f)).length == 0, "50% has extra layers");
            float[] three = (float[]) Mod.staticCall(CLICKS, "extraLayers", 3.0f);
            c.check(three.length == 2 && three[0] == 1f && three[1] == 1f, "300% is not two more full copies: " + java.util.Arrays.toString(three));
            float[] twoHalf = (float[]) Mod.staticCall(CLICKS, "extraLayers", 2.5f);
            c.check(twoHalf.length == 2 && twoHalf[0] == 1f && Math.abs(twoHalf[1] - 0.5f) < 1e-6,
                    "250% is not a full copy and a half: " + java.util.Arrays.toString(twoHalf));
            c.check(((float[]) Mod.staticCall(CLICKS, "extraLayers", 5.0f)).length == 4, "500% is not four more copies");
            // The setting: 500% is the top, and it is saved.
            Mod.set(CLICK_CFG, "setVolume", 9.0f);
            c.check((Float) Mod.get(CLICK_CFG, "getVolume") == 5.0f, "volume is not clamped at 500%");
            Mod.set(CLICK_CFG, "setVolume", 3.0f);
            Mod.set(CLICK_CFG, "setChoice", "pop");
            Mod.call(Mod.cfg(CLICK_CFG), "save");
            Mod.staticCall(CLICK_CFG, "load");
            c.check((Float) Mod.get(CLICK_CFG, "getVolume") == 3.0f && "pop".equals(Mod.get(CLICK_CFG, "getChoice")),
                    "volume / choice did not survive a reload");
            // A Minecraft (non-mod) screen, no Vanilla Screens switch to turn on.
            c.onClient(mc -> {
                McCompat.setScreen(mc, new Screen(Component.literal("vanilla stand-in")) { });
                return null;
            });
            c.ctx().waitTicks(2);
            int replacedBefore = (Integer) Mod.staticCall(CLICKS, "clicksReplaced");
            int layersBefore = layers();
            String got = click(c);
            c.check("killer560smod:click_pop".equals(got), "a click on a non-mod screen played " + got + ", not the Pop preset");
            c.check((Integer) Mod.staticCall(CLICKS, "clicksReplaced") == replacedBefore + 1, "the click was not swapped");
            c.check(layers() == layersBefore + 2, "300% played " + (layers() - layersBefore) + " extra copies (want 2)");
            // Control: 100% plays no extra copy.
            Mod.set(CLICK_CFG, "setVolume", 1.0f);
            layersBefore = layers();
            click(c);
            c.check(layers() == layersBefore, "100% played extra copies");
            // Default sound, loud: still the vanilla click, layered.
            Mod.set(CLICK_CFG, "setChoice", "default");
            Mod.set(CLICK_CFG, "setVolume", 2.0f);
            layersBefore = layers();
            String def = click(c);
            c.check("minecraft:ui.button.click".equals(def), "Default played " + def);
            c.check(layers() == layersBefore + 1, "Default at 200% played " + (layers() - layersBefore) + " extra copies (want 1)");
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            // The tab: the Vanilla Screens switch is gone and the volume reads up to 500%.
            List<String> texts = tabTexts(c, "gui.tab.ClickSoundsTab", null, null);
            c.check(!anyContains(texts, "Vanilla Screens"), "the Vanilla Screens switch is still there: " + texts);
            c.check(anyContains(texts, "Click Volume"), "no Click Volume slider: " + texts);
        } finally {
            if (saved == null) {
                Files.deleteIfExists(cfgFile);
            } else {
                Files.write(cfgFile, saved);
            }
            Mod.staticCall(CLICK_CFG, "load");
        }
    }

    static void slotClick(Session c) throws Exception {
        try (AutoCloseable a = Mod.with(CLICK_CFG, "Choice", "pop");
             AutoCloseable b = Mod.with(CLICK_CFG, "Volume", 1.0f)) {
            MenuKit.reset(c);
            Map<Integer, JsonObject> slots = new LinkedHashMap<>();
            slots.put(10, FlipCroesusCases.item("minecraft:diamond", "§aA Button"));
            Map<Integer, JsonObject> actions = new LinkedHashMap<>();
            actions.put(10, new JsonObject());
            JsonObject menu = FlipCroesusCases.menu("Testkit Sound Menu", 3, slots, actions);
            menu.addProperty("fill", false);
            MenuKit.show(c, menu);
            MenuKit.awaitScreen(c, "Testkit Sound Menu", 100);
            c.ctx().getInput().setCursorPos(2, 2);
            c.ctx().waitTicks(4);
            int before = (Integer) Mod.staticCall(CLICKS, "slotClicks");
            // Control: a click on an empty slot makes no sound.
            CroesusUiCases.hover(c, 0);
            c.ctx().getInput().pressMouse(0);
            c.ctx().waitTicks(4);
            c.check((Integer) Mod.staticCall(CLICKS, "slotClicks") == before, "a click on an empty slot played the click");
            // A click on the button in the menu plays it.
            CroesusUiCases.hover(c, 10);
            c.ctx().getInput().pressMouse(0);
            c.ctx().waitTicks(4);
            c.check((Integer) Mod.staticCall(CLICKS, "slotClicks") == before + 1,
                    "a click on a menu button played " + ((Integer) Mod.staticCall(CLICKS, "slotClicks") - before) + " clicks (want 1)");
            // Default: nothing, as in vanilla.
            Mod.set(CLICK_CFG, "setChoice", "default");
            CroesusUiCases.hover(c, 10);
            c.ctx().getInput().pressMouse(0);
            c.ctx().waitTicks(4);
            c.check((Integer) Mod.staticCall(CLICKS, "slotClicks") == before + 1, "Default played a slot click");
        } finally {
            MenuKit.reset(c);
        }
    }

    private static byte[] wavBytes(int hz, double seconds) throws Exception {
        int n = (int) (hz * seconds);
        byte[] pcm = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            short v = (short) (Math.sin(i * 2 * Math.PI * 440 / hz) * 12000);
            pcm[2 * i] = (byte) (v & 255);
            pcm[2 * i + 1] = (byte) ((v >> 8) & 255);
        }
        javax.sound.sampled.AudioFormat fmt = new javax.sound.sampled.AudioFormat(hz, 16, 1, true, false);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.sound.sampled.AudioSystem.write(new javax.sound.sampled.AudioInputStream(
                new java.io.ByteArrayInputStream(pcm), fmt, n), javax.sound.sampled.AudioFileFormat.Type.WAVE, out);
        return out.toByteArray();
    }

    static void clickImport(Session c) throws Exception {
        Path dir = (Path) Mod.staticCall(CLICKS, "importDir");
        Path wav = Files.createTempFile("k560-1018-", ".wav");
        Path bad = Files.createTempFile("k560-1018-bad-", ".wav");
        Path mp3 = null;
        Path mp4 = null;
        String savedMp4 = null;
        String savedWav = null;
        String savedMp3 = null;
        try (AutoCloseable a = Mod.with(CLICK_CFG, "Choice", "default")) {
            Files.write(wav, wavBytes(22050, 0.3));
            Files.writeString(bad, "this is not a wav file");
            boolean refused = false;
            try {
                Mod.staticCall(CLICKS, "importFile", bad);
            } catch (Throwable t) {
                refused = true;
            }
            c.check(refused, "a text file renamed .wav was imported");
            savedWav = (String) Mod.staticCall(CLICKS, "importFile", wav);
            c.check(Files.isRegularFile(dir.resolve(savedWav)), "the .wav did not reach the import folder");
            @SuppressWarnings("unchecked")
            List<String> files = (List<String>) Mod.staticCall(CLICKS, "importedFiles");
            c.check(files.contains(savedWav), "the .wav is not among the choices: " + files);
            // Selected, it plays, and the engine's own buffer lookup decodes it to real samples.
            Mod.set(CLICK_CFG, "setChoice", "import:" + savedWav);
            String got = click(c);
            c.check(got.startsWith("killer560smod:imported/"), "an imported .wav played " + got);
            Object buffer = c.onClient(mc -> {
                try {
                    var id = net.minecraft.resources.Identifier.parse("killer560smod:sounds/" + got.substring("killer560smod:".length()) + ".ogg");
                    var lib = new net.minecraft.client.sounds.SoundBufferLibrary(mc.getResourceManager());
                    return lib.getCompleteBuffer(id).get(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new AssertionError("the engine could not read the imported .wav: " + e, e);
                }
            });
            c.check(buffer != null, "no buffer for the imported .wav");

            // Any other type. An mp3 is read natively (the mod bundles the decoder); a video needs ffmpeg for its audio.
            String ffmpeg = ffmpegPath();
            if (ffmpeg == null) {
                c.note("ffmpeg is not on this PATH: the mp3 and video imports were not exercised");
            } else {
                mp3 = Files.createTempFile("k560-1018-", ".mp3");
                Process p = new ProcessBuilder(ffmpeg, "-y", "-v", "quiet", "-i", wav.toString(), mp3.toString())
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                c.check(p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0 && Files.size(mp3) > 0,
                        "premise: ffmpeg could not make the test mp3");
                savedMp3 = (String) Mod.staticCall(CLICKS, "importFile", mp3);
                c.check(savedMp3.endsWith(".mp3") && Files.isRegularFile(dir.resolve(savedMp3)),
                        "the mp3 was not kept as it is: " + savedMp3);
                Mod.set(CLICK_CFG, "setChoice", "import:" + savedMp3);
                String got2 = click(c);
                c.check(got2.startsWith("killer560smod:imported/"), "an imported mp3 played " + got2);
                Object buffer2 = c.onClient(mc -> {
                    try {
                        var id = net.minecraft.resources.Identifier.parse("killer560smod:sounds/" + got2.substring("killer560smod:".length()) + ".ogg");
                        var lib = new net.minecraft.client.sounds.SoundBufferLibrary(mc.getResourceManager());
                        return lib.getCompleteBuffer(id).get(10, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new AssertionError("the engine could not read the imported mp3: " + e, e);
                    }
                });
                c.check(buffer2 != null, "no buffer for the imported mp3");
                // A video: a tone with a picture, in an mp4. Only its audio is used.
                mp4 = Files.createTempFile("k560-1018-", ".mp4");
                Process v = new ProcessBuilder(ffmpeg, "-y", "-v", "quiet", "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
                        "-f", "lavfi", "-i", "color=c=blue:s=64x64:d=1", "-shortest", "-pix_fmt", "yuv420p", mp4.toString())
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                c.check(v.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) && v.exitValue() == 0 && Files.size(mp4) > 0,
                        "premise: ffmpeg could not make the test mp4");
                savedMp4 = (String) Mod.staticCall(CLICKS, "importFile", mp4);
                // ffmpeg's output is .ogg (libvorbis) since mod 9c437b1d, the engine's own format; it was .wav before.
                c.check(savedMp4.endsWith(".ogg") && Files.isRegularFile(dir.resolve(savedMp4)),
                        "the video's audio did not come in as an .ogg: " + savedMp4);
                Mod.set(CLICK_CFG, "setChoice", "import:" + savedMp4);
                c.check(click(c).startsWith("killer560smod:imported/"), "the video's audio did not play");
                c.note("mp3 kept as " + savedMp3 + ", video imported as " + savedMp4);
            }
        } finally {
            Files.deleteIfExists(wav);
            Files.deleteIfExists(bad);
            if (mp3 != null) {
                Files.deleteIfExists(mp3);
            }
            if (mp4 != null) {
                Files.deleteIfExists(mp4);
            }
            if (savedMp4 != null) {
                Files.deleteIfExists(dir.resolve(savedMp4));
            }
            if (savedWav != null) {
                Files.deleteIfExists(dir.resolve(savedWav));
            }
            if (savedMp3 != null) {
                Files.deleteIfExists(dir.resolve(savedMp3));
            }
        }
    }

    private static String ffmpegPath() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0 ? "ffmpeg" : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- 45: Custom Items colour ------------------------------------------------------------------------------------

    static void customItems(Session c) throws Exception {
        String cfgClass = "armourdye.ArmourDyeConfig";
        try (AutoCloseable on = Mod.with(cfgClass, "Enabled", true);
             AutoCloseable gate = gateOff()) {
            Mod.call(Mod.cfg(cfgClass), "clear");
            ItemStack stack = c.onClient(mc -> {
                ItemStack s = new ItemStack(Items.LEATHER_CHESTPLATE);
                CompoundTag tag = new CompoundTag();
                tag.putString("id", "TESTKIT_PLATE");
                tag.putString("uuid", "1018-0000-test");
                s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                mc.player.getInventory().setItem(9, s);
                return s;
            });
            Screen screen = c.onClient(mc -> {
                try {
                    Screen sc = (Screen) Mod.cls("armourdye.CustomItemsScreen").getConstructor(Screen.class).newInstance((Object) null);
                    McCompat.setScreen(mc, sc);
                    return sc;
                } catch (Exception e) {
                    throw new AssertionError("could not open Custom Items: " + e, e);
                }
            });
            c.ctx().waitTicks(4);
            c.onClient(mc -> {
                Mod.call(screen, "select", stack);
                return null;
            });
            c.ctx().waitTicks(4);
            Object key = c.onClient(mc -> Mod.field(screen, "selectedKey"));
            c.check(key != null, "premise: the item could not be selected (no key)");
            // The real colour button, pressed.
            AbstractWidget colour = c.onClient(mc -> screen.children().stream()
                    .filter(w -> w instanceof AbstractWidget aw && aw.getMessage().getString().startsWith("Color"))
                    .map(w -> (AbstractWidget) w).findFirst().orElse(null));
            c.check(colour != null, "premise: no Color button on the Custom Items screen");
            c.check(colour != null && colour.active, "premise: the Color button is not active for the selected item");
            c.onClient(mc -> {
                colour.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(colour.getX() + 3, colour.getY() + 3,
                        new net.minecraft.client.input.MouseButtonInfo(0, 0)), false);
                return null;
            });
            c.ctx().waitTicks(4);
            Screen picker = c.onClient(mc -> McCompat.screen(mc));
            c.check(picker != null && picker.getClass().getSimpleName().equals("ColorPickerScreen"),
                    "premise: the colour picker did not open: " + (picker == null ? null : picker.getClass().getName()));
            if (picker == null || !picker.getClass().getSimpleName().equals("ColorPickerScreen")) {
                return;
            }
            // Drag the square and the hue bar the way the mouse does, drawing frames between the moves.
            int[] colours = new int[1];
            for (int i = 0; i < 40; i++) {
                final int step = i;
                c.onClient(mc -> {
                    Mod.call(picker, "updateSv", 40.0 + step * 3, 60.0 + step * 2);
                    Mod.call(picker, "updateHue", 20.0 + step * 4);
                    colours[0] = (Integer) Mod.call(picker, "currentArgb");
                    return null;
                });
                c.ctx().waitTicks(1);
            }
            Object entryAfter = c.onClient(mc -> Mod.call(screen, "entry"));
            c.check(entryAfter != null, "the colour was never kept: no entry for the item");
            if (entryAfter != null) {
                int saved = (Integer) Mod.field(entryAfter, "color");
                c.check(saved == colours[0], String.format("the item's colour is #%08X, the picker's is #%08X", saved, colours[0]));
                c.check((Boolean) Mod.field(entryAfter, "colorEnabled"), "choosing a colour did not turn Use Color on");
            }
            // Back to Custom Items, rendered with the dyed item on screen.
            c.onClient(mc -> {
                picker.onClose();
                return null;
            });
            c.ctx().waitTicks(6);
            Screen now = c.onClient(mc -> McCompat.screen(mc));
            c.check(now == screen || (now != null && now.getClass().getSimpleName().equals("CustomItemsScreen")),
                    "closing the picker did not return to Custom Items: " + (now == null ? null : now.getClass().getName()));
            // The dye hook ran for the stack and gave the chosen opaque colour.
            int tint = c.onClient(mc -> (Integer) Mod.staticCall("armourdye.ArmourDye", "dyeColor", stack, 0xFFA06540));
            c.check(tint == (0xFF000000 | (colours[0] & 0xFFFFFF)), String.format("the dye hook gave #%08X for #%08X", tint, colours[0]));
            c.check(!(Boolean) Mod.staticCall("armourdye.ArmourDye", "hasFailed"), "ArmourDye latched itself off after a render failure");
            // The extremes: fully see-through and white, black.
            for (int argb : new int[]{0x00000000, 0xFFFFFFFF, 0xFF000000, 0x01FFFFFF}) {
                c.onClient(mc -> {
                    Mod.call(screen, "setColour", argb);
                    return null;
                });
                c.ctx().waitTicks(3);
            }
            c.check(!(Boolean) Mod.staticCall("armourdye.ArmourDye", "hasFailed"), "ArmourDye failed on an extreme colour");
            // Worn: the piece on his chest drawn in third person and in the inventory while the colour keeps changing, then the
            // same colour on a Look (another item model) and on a head texture.
            c.onClient(mc -> {
                mc.player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, stack);
                mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
                return null;
            });
            for (int i = 0; i < 30; i++) {
                final int step = i;
                c.onClient(mc -> {
                    Mod.call(screen, "setColour", 0xFF000000 | (step * 0x081F3B & 0xFFFFFF));
                    return null;
                });
                c.ctx().waitTicks(1);
            }
            c.onClient(mc -> {
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
                return null;
            });
            for (int i = 0; i < 10; i++) {
                final int step = i;
                c.onClient(mc -> {
                    Mod.call(screen, "setColour", 0x80000000 | (step * 0x1F3B5D & 0xFFFFFF));
                    return null;
                });
                c.ctx().waitTicks(1);
            }
            c.onClient(mc -> {
                McCompat.setScreen(mc, screen);
                Mod.call(screen, "setLook", "diamond_sword");
                return null;
            });
            for (int i = 0; i < 10; i++) {
                final int step = i;
                c.onClient(mc -> {
                    Mod.call(screen, "setColour", 0xFF000000 | (step * 0x2B1F0D & 0xFFFFFF));
                    return null;
                });
                c.ctx().waitTicks(1);
            }
            c.check(!(Boolean) Mod.staticCall("armourdye.ArmourDye", "hasFailed"), "ArmourDye failed while the piece was worn");
            c.onClient(mc -> {
                mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                mc.player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, ItemStack.EMPTY);
                return null;
            });
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.call(Mod.cfg("armourdye.ArmourDyeConfig"), "clear");
                return null;
            });
        }
    }
}

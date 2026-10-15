package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;

/**
 * killer560's 2026-10-15 item: "For auto routes add a toggle to show what percent done with a rooms auto routes you are as
 * a hud. If using auto clear have a list of the rooms it is going to do in order with a total percent at the top and a
 * configurable amount of rooms it shows it is about to do" (mod trail-hud-1015).
 *
 * <p>The HUD reads two things the automation already keeps: {@code RouteRunMemory}'s per-room node progress (written by
 * the route executor every tick) and {@code RunPlan} (each planner's own queue, published when it picks). These cases
 * drive both through their real entry points and read what the HUD would draw ({@code AutoRoutesProgressHud.shownText},
 * the lines the element's render and box both use):
 * <ul>
 *   <li>1045 content: one room line with the node percent and count; completion reads 100%; a plan shows the planner's
 *       name and total percent first, the room under way with its own route percent, exactly the configured number of
 *       upcoming rooms and a "+N more" line for the rest (none when all fit); Autopilot outranks Auto Secret outranks
 *       Auto Clear; clearing the plans empties it.</li>
 *   <li>1046 editor: the element is in the HUD editor's list exactly while the setting is on (cheat jar), movable and
 *       scalable like any element, and absent from a legit jar.</li>
 *   <li>1047 settings: the toggle and room count survive save + load, clamp, and the Auto Routes tab has both rows.</li>
 * </ul>
 * The planners' real queues are checked against the HUD inside the sim scenarios (131 for Auto Clear, 102 for Auto Secret).
 */
final class RoutesProgressHudCases {

    private static final String HUD = "autoroutes.AutoRoutesProgressHud";
    private static final String PLAN = "autoroutes.RunPlan";
    private static final String SOURCE = "autoroutes.RunPlan$Source";
    private static final String MEMORY = "autoroutes.RouteRunMemory";
    private static final String CFG = "autoroutes.AutoRoutesConfig";

    private RoutesProgressHudCases() {
    }

    private static boolean ready(UiCase c) {
        if (!Mod.has(HUD) || !Mod.has(PLAN)) {
            c.problem("this jar has no Auto Routes Progress HUD (mod before trail-hud-1015)");
            return false;
        }
        return true;
    }

    private static void publish(UiCase c, String source, List<String> rooms, List<String> tags, int done) {
        c.onClient(mc -> {
            Mod.staticCall(PLAN, "publish", Mod.enumValue(SOURCE, source), rooms, tags, done);
            return null;
        });
    }

    private static void clearPlans(UiCase c) {
        c.onClient(mc -> {
            for (String s : new String[]{"AUTOPILOT", "AUTO_SECRET", "AUTO_CLEAR"}) {
                Mod.staticCall(PLAN, "clear", Mod.enumValue(SOURCE, s));
            }
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private static List<String> shown(UiCase c) {
        return c.onClient(mc -> (List<String>) Mod.staticCall(HUD, "shownText"));
    }

    private static void rooms(UiCase c, int n) {
        c.onClient(mc -> {
            Mod.call(Mod.cfg(CFG), "setProgressHudRooms", n);
            return null;
        });
    }

    private static void expect(UiCase c, List<String> got, List<String> want, String what) {
        c.note(what + ": " + got);
        c.check(got.equals(want), what + ": the HUD shows " + got + ", wanted " + want);
    }

    // ---- 1045 content ---------------------------------------------------------------------------------------------

    static void content(UiCase c) {
        if (!ready(c)) {
            return;
        }
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Routes is cheat-only, its HUD is not registered (1046 checks that)");
            return;
        }
        int roomsBefore = c.onClient(mc -> ((Number) Mod.call(Mod.cfg(CFG), "getProgressHudRooms")).intValue());
        try {
            clearPlans(c);
            c.onClient(mc -> {
                Mod.staticCall(MEMORY, "reset");
                return null;
            });
            expect(c, shown(c), List.of(), "nothing running, no room with a route");

            // The route executor's report for a room: 3 of 7 nodes. (RouteExecutor calls this every tick while it plays;
            // 102-sim-autosecret checks that it does.)
            c.onClient(mc -> {
                Mod.staticCall(MEMORY, "noteProgress", "Mushroom", 3, 7);
                return null;
            });
            int[] p = c.onClient(mc -> (int[]) Mod.staticCall(MEMORY, "progressOf", "Mushroom"));
            c.check(p != null && p[0] == 3 && p[1] == 7, "progressOf(Mushroom) is not {3, 7}: " + java.util.Arrays.toString(p));
            List<String> line = c.onClient(mc -> lineText("Mushroom"));
            expect(c, line, List.of("Auto Routes: Mushroom 43%  (3/7 nodes)"), "one room's route, 3 of 7");
            c.onClient(mc -> {
                Mod.staticCall(MEMORY, "noteProgress", "Mushroom", 7, 7);
                return null;
            });
            expect(c, c.onClient(mc -> lineText("Mushroom")), List.of("Auto Routes: Mushroom 100%  (7/7 nodes)"),
                    "one room's route, all nodes");
            c.onClient(mc -> {
                Mod.staticCall(MEMORY, "noteProgress", "Crypt", 2, 8);
                Mod.staticCall(MEMORY, "testMarkRouted", "Crypt");
                return null;
            });
            expect(c, c.onClient(mc -> lineText("Crypt")), List.of("Auto Routes: Crypt 100%  (8/8 nodes)"),
                    "a route that ran to its end reads complete whatever the last tick said");

            // A plan: Auto Clear, 2 rooms done, 7 left, 3 upcoming shown.
            rooms(c, 3);
            List<String> queue = List.of("Mushroom", "Zombie Room", "Spider Room", "Lava Ravine", "Tomb", "Crypt", "Withermancer");
            c.onClient(mc -> {
                Mod.staticCall(MEMORY, "reset");
                Mod.staticCall(MEMORY, "noteProgress", "Mushroom", 3, 7);
                return null;
            });
            publish(c, "AUTO_CLEAR", queue, null, 2);
            expect(c, shown(c), List.of("Auto Clear 22%  (2/9)", "> Mushroom  43%", "  Zombie Room", "  Spider Room",
                    "  Lava Ravine", "+3 more"), "Auto Clear plan, 3 upcoming of 6");
            rooms(c, 1);
            expect(c, shown(c), List.of("Auto Clear 22%  (2/9)", "> Mushroom  43%", "  Zombie Room", "+5 more"),
                    "Auto Clear plan, 1 upcoming");
            rooms(c, 15);
            expect(c, shown(c), List.of("Auto Clear 22%  (2/9)", "> Mushroom  43%", "  Zombie Room", "  Spider Room",
                    "  Lava Ravine", "  Tomb", "  Crypt", "  Withermancer"), "Auto Clear plan, all fit: no '+N more' line");
            rooms(c, 6);
            expect(c, shown(c), List.of("Auto Clear 22%  (2/9)", "> Mushroom  43%", "  Zombie Room", "  Spider Room",
                    "  Lava Ravine", "  Tomb", "  Crypt", "  Withermancer"), "Auto Clear plan, exactly as many as are left");
            rooms(c, 5);
            expect(c, shown(c), List.of("Auto Clear 22%  (2/9)", "> Mushroom  43%", "  Zombie Room", "  Spider Room",
                    "  Lava Ravine", "  Tomb", "  Crypt", "+1 more"), "Auto Clear plan, one more than shown");

            // Nothing done yet, one room left: 0%; everything done: 100% with no rooms.
            rooms(c, 3);
            publish(c, "AUTO_CLEAR", List.of("Mushroom"), null, 0);
            expect(c, shown(c), List.of("Auto Clear 0%  (0/1)", "> Mushroom  43%"), "first room of the run");
            publish(c, "AUTO_CLEAR", List.of(), null, 4);
            expect(c, shown(c), List.of("Auto Clear 100%  (4/4)"), "every room done");

            // Priority: Autopilot, then Auto Secret, then Auto Clear; its tags name what is done in each room.
            publish(c, "AUTO_CLEAR", List.of("A", "B"), null, 1);
            publish(c, "AUTO_SECRET", List.of("C", "D", "E"), null, 3);
            expect(c, shown(c), List.of("Auto Secret 50%  (3/6)", "> C", "  D", "  E"), "Auto Secret outranks Auto Clear");
            publish(c, "AUTOPILOT", List.of("Mushroom", "Mushroom", "Crypt"), List.of("Secret", "Clear", "Puzzle"), 1);
            expect(c, shown(c), List.of("Autopilot 25%  (1/4)", "> Secret Mushroom  43%", "  Clear Mushroom", "  Puzzle Crypt"),
                    "Autopilot outranks both and names each room's job");
            c.onClient(mc -> {
                Mod.staticCall(PLAN, "clear", Mod.enumValue(SOURCE, "AUTOPILOT"));
                return null;
            });
            expect(c, shown(c).subList(0, 1), List.of("Auto Secret 50%  (3/6)"), "clearing Autopilot falls back to Auto Secret");
            clearPlans(c);
            expect(c, shown(c), List.of(), "every plan cleared");
        } finally {
            clearPlans(c);
            c.onClient(mc -> {
                Mod.staticCall(MEMORY, "reset");
                Mod.call(Mod.cfg(CFG), "setProgressHudRooms", roomsBefore);
                return null;
            });
        }
    }

    /** The single-room line for a room, from the run memory (the HUD's own builder). */
    @SuppressWarnings("unchecked")
    private static List<String> lineText(String room) {
        int[] p = (int[]) Mod.staticCall(MEMORY, "progressOf", room);
        List<?> lines = (List<?>) Mod.staticCall(HUD, "routeLines", room, p);
        List<String> out = new ArrayList<>();
        for (Object l : lines) {
            out.add((String) Mod.call(l, "text"));
        }
        return out;
    }

    // ---- 1046 editor ----------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static void editor(UiCase c) throws Exception {
        if (!ready(c)) {
            return;
        }
        Object element = c.onClient(mc -> Mod.staticCall("hud.HudElementRegistry", "byId", "autoroutes_progress"));
        if (!Mod.isCheat()) {
            c.check(element == null, "a legit jar registers the Auto Routes Progress HUD (Auto Routes is cheat-only)");
            return;
        }
        c.check(element != null, "the Auto Routes Progress HUD is not registered with the HUD editor's registry");
        if (element == null) {
            return;
        }
        c.check("Auto Routes Progress".equals(Mod.call(element, "displayName")), "display name is "
                + Mod.call(element, "displayName"));
        boolean showAll = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg("hud.HudConfig"), "isEditorShowAll"));
        boolean progress = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(CFG), "isProgressHud"));
        boolean master = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(CFG), "isEnabledRaw"));
        try {
            c.onClient(mc -> {
                Mod.call(Mod.cfg("hud.HudConfig"), "setEditorShowAll", true);
                Mod.call(Mod.cfg(CFG), "setEnabled", true);
                Mod.call(Mod.cfg(CFG), "setProgressHud", false);
                return null;
            });
            boolean off = listed(c);
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setProgressHud", true);
                return null;
            });
            boolean on = listed(c);
            c.note("listed in the HUD editor: setting off " + off + ", on " + on);
            c.check(!off, "the HUD editor lists the Auto Routes Progress HUD while its setting is OFF");
            c.check(on, "the HUD editor does not list the Auto Routes Progress HUD while its setting is ON");

            // A box with a size, at a resolvable position; the sample it draws in the editor fills it.
            int w = boxSeen[0];
            int h = boxSeen[1];
            c.note("editor box " + w + "x" + h + " (HudBoxCases 394 judges it against what is drawn)");
            c.check(w > 40 && h >= 30, "the editor box is only " + w + "x" + h);
            int[] pos = c.onClient(mc -> (int[]) Mod.staticCall("hud.HudElementRegistry", "resolvePosition", element));
            c.check(pos != null && pos.length == 2, "no resolvable position");
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.call(Mod.cfg("hud.HudConfig"), "setEditorShowAll", showAll);
                Mod.call(Mod.cfg(CFG), "setProgressHud", progress);
                Mod.call(Mod.cfg(CFG), "setEnabled", master);
                return null;
            });
        }
    }

    /** {width, height} of the element with the editor open, set by {@link #listed}. */
    private static final int[] boxSeen = new int[2];

    @SuppressWarnings("unchecked")
    private static boolean listed(UiCase c) throws Exception {
        Screen s = c.onClient(mc -> {
            try {
                Screen editor = (Screen) R.cls("hud.HudEditorScreen").getConstructor(Screen.class).newInstance((Object) null);
                McCompat.setScreen(mc, editor);
                return editor;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        c.ticks(3);
        // Measured with the editor open: its sample is what the box must hold.
        boxSeen[0] = c.onClient(mc -> (Integer) Mod.call(Mod.staticCall("hud.HudElementRegistry", "byId", "autoroutes_progress"), "width"));
        boxSeen[1] = c.onClient(mc -> (Integer) Mod.call(Mod.staticCall("hud.HudElementRegistry", "byId", "autoroutes_progress"), "height"));
        boolean in = c.onClient(mc -> {
            for (Object e : (List<Object>) R.get(s, "shown")) {
                if ("autoroutes_progress".equals(Mod.call(e, "id"))) {
                    return true;
                }
            }
            return false;
        });
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        c.ticks(2);
        return in;
    }

    // ---- 1047 settings --------------------------------------------------------------------------------------------

    static void settings(UiCase c) throws Exception {
        if (!ready(c)) {
            return;
        }
        java.nio.file.Path file = c.onClient(mc -> mc.gameDirectory.toPath()
                .resolve("config/killer560/dungeons/autoroutes/killer560smod-autoroutes-settings.json"));
        c.note("settings file " + file + " exists " + java.nio.file.Files.exists(file));
        Object cfg = Mod.cfg(CFG);
        boolean was = (Boolean) Mod.call(cfg, "isProgressHud");
        int rooms = ((Number) Mod.call(cfg, "getProgressHudRooms")).intValue();
        boolean master = (Boolean) Mod.call(cfg, "isEnabledRaw");
        try {
            c.onClient(mc -> {
                Mod.call(cfg, "setProgressHud", true);
                Mod.call(cfg, "setProgressHudRooms", 9);
                Mod.call(cfg, "save");
                Mod.staticCall(CFG, "load");
                return null;
            });
            Object back = Mod.cfg(CFG);
            c.check(back != cfg, "load() did not make a new instance, so the round trip proved nothing");
            c.check(Boolean.TRUE.equals(Mod.call(back, "isProgressHud")) && ((Number) Mod.call(back, "getProgressHudRooms")).intValue() == 9,
                    "the Progress HUD settings did not survive save + load: " + Mod.call(back, "isProgressHud") + " "
                            + Mod.call(back, "getProgressHudRooms"));
            Mod.call(back, "setProgressHudRooms", 0);
            c.check(((Number) Mod.call(back, "getProgressHudRooms")).intValue() == 1, "rooms 0 did not clamp to 1");
            Mod.call(back, "setProgressHudRooms", 99);
            c.check(((Number) Mod.call(back, "getProgressHudRooms")).intValue() == 15, "rooms 99 did not clamp to 15");
            Boolean reloaded = c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setProgressHud", false);   // memory only
                Mod.staticCall("profiles.ProfileManager", "reloadAllConfigs");
                return (Boolean) Mod.call(Mod.cfg(CFG), "isProgressHud");
            });
            c.check(reloaded, "a profile reload did not reload the Progress HUD setting");

            if (Mod.isCheat()) {
                List<String> rows = TrailRibbonCases.tabRows(c, "gui.tab.AutoRoutesTab", () -> {
                    Mod.call(Mod.cfg(CFG), "setEnabled", true);
                    Mod.call(Mod.cfg(CFG), "setProgressHud", true);
                    Mod.call(Mod.cfg(CFG), "setProgressHudRooms", 7);
                });
                c.note("Auto Routes rows (Progress): " + rows.stream().filter(r -> r.contains("Progress")).toList());
                c.check(rows.stream().anyMatch(r -> r.startsWith("Progress HUD: ON")), "no 'Progress HUD: ON' row in the Auto Routes tab");
                c.check(rows.stream().anyMatch(r -> r.startsWith("Progress Rooms: 7")), "no 'Progress Rooms: 7' slider in the Auto Routes tab");
                List<String> off = TrailRibbonCases.tabRows(c, "gui.tab.AutoRoutesTab", () ->
                        Mod.call(Mod.cfg(CFG), "setProgressHud", false));
                c.check(off.stream().anyMatch(r -> r.startsWith("Progress HUD: OFF")), "no 'Progress HUD: OFF' row");
                c.check(off.stream().noneMatch(r -> r.startsWith("Progress Rooms")), "the rooms slider shows while the HUD is off");
                for (String label : new String[]{"Progress HUD: ON", "Progress Rooms: 7"}) {
                    String tip = c.onClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "describe", "Auto Routes", label));
                    c.check(tip != null && !tip.isEmpty(), "no tooltip for '" + label + "'");
                }
            }
        } finally {
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setProgressHud", was);
                Mod.call(Mod.cfg(CFG), "setProgressHudRooms", rooms);
                Mod.call(Mod.cfg(CFG), "setEnabled", master);
                Mod.call(Mod.cfg(CFG), "save");
                return null;
            });
        }
    }
}

package dev.testkit.gametest.menu;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Auto Trap offers only Just Cleared out of the box (mod newtrap-1121; killer560, 2026-11-21: no bundled Full Trap route,
 * Full Trap only once he captured his own; his New Trap and Old Trap routes are the bundled Just Cleared ones).
 * <ul>
 *   <li>1121-menu-autotrap-cleared: with no file, both traps read Just Cleared with a bundled route (8 and 4 nodes) and no
 *       Full Trap route; the tab shows no Mode and no Full Trap row, but Capture Full Trap. A file that says FULL with no
 *       capture of his loads as CLEARED. A file holding his own Full Trap capture for New Trap keeps FULL, and the tab then
 *       shows New Trap's Full Trap row and one Mode button (Old Trap still none). A mode set then survives save + load.</li>
 * </ul>
 */
final class AutoTrap1121MenuCases {

    private static final String AT = "autotrap.AutoTrap";
    private static final String TAB = "com.killer560.hub.gui.tab.AutoTrapTab";

    private AutoTrap1121MenuCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1121-menu-autotrap-cleared", AutoTrap1121MenuCases::cleared);
    }

    private static Object mode(String m) {
        return Mod.enumValue(AT + "$Mode", m);
    }

    private static String sel(Session c, String trap) {
        return c.onClient(mc -> String.valueOf(Mod.staticCall(AT, "selectedMode", trap)));
    }

    /** Every label the Auto Trap tab builds now. */
    private static List<String> labels(Session c) {
        return c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            try {
                Object tab = Class.forName(TAB).getConstructor().newInstance();
                List<?> w = (List<?>) tab.getClass().getMethod("buildWidgets", int.class, int.class, int.class,
                        Runnable.class).invoke(tab, 0, 0, 360, (Runnable) () -> { });
                for (Object o : w) {
                    Object msg = o.getClass().getMethod("getMessage").invoke(o);
                    out.add(String.valueOf(msg.getClass().getMethod("getString").invoke(msg)).replaceAll("§.", ""));
                }
            } catch (ReflectiveOperationException e) {
                out.add("ERROR " + e);
            }
            return out;
        });
    }

    private static long count(List<String> labels, String prefix) {
        return labels.stream().filter(l -> l.trim().startsWith(prefix)).count();
    }

    private static void load(Session c) {
        c.ctx().runOnClient(mc -> Mod.staticCall(AT, "load"));
    }

    static void cleared(Session c) throws Exception {
        if (!MenuKit.cheat()) {
            c.note("Auto Trap is cheat build only");
            return;
        }
        Path file = c.onClient(mc -> (Path) Mod.staticCall(AT, "file"));
        byte[] was = Files.exists(file) ? Files.readAllBytes(file) : null;
        try {
            // ---- no file: Just Cleared only, bundled -----------------------------------------------------------------
            Files.deleteIfExists(file);
            load(c);
            for (String trap : new String[]{"New Trap", "Old Trap"}) {
                c.check(sel(c, trap).equals("CLEARED"), trap + " out of the box should be CLEARED, is " + sel(c, trap));
                String info = c.onClient(mc -> {
                    Object e = Mod.staticCall(AT, "entry", trap, mode("CLEARED"));
                    Object f = Mod.staticCall(AT, "entry", trap, mode("FULL"));
                    if (e == null) {
                        return "no Just Cleared entry, full " + (f != null);
                    }
                    Object route = Mod.call(e, "route");
                    int n = ((List<?>) Mod.call(route, "nodes")).size();
                    return Mod.call(e, "source") + " " + n + " full " + (f != null);
                });
                String want = "bundled " + (trap.equals("New Trap") ? 8 : 4) + " full false";
                c.note(trap + ": " + info);
                c.check(info.equals(want), trap + " should hold its bundled Just Cleared route and no Full Trap (" + want
                        + "), has " + info);
            }
            List<String> l0 = labels(c);
            c.note("tab, no capture: " + l0);
            c.check(count(l0, "Mode:") == 0, "a Mode button shows with no Full Trap capture: " + l0);
            c.check(count(l0, "Full Trap:") == 0, "a Full Trap row shows with no Full Trap capture: " + l0);
            c.check(count(l0, "Capture Full Trap") == 2, "Capture Full Trap must stay reachable for both traps: " + l0);
            c.check(count(l0, "Just Cleared: bundled") == 2, "both Just Cleared rows should read bundled: " + l0);

            // ---- a saved FULL with no capture of his migrates to CLEARED -------------------------------------------
            Files.createDirectories(file.getParent());
            Files.writeString(file, "{\"enabled\": false, \"modes\": {\"New Trap\": \"FULL\", \"Old Trap\": \"FULL\"},"
                    + " \"routes\": {}}", StandardCharsets.UTF_8);
            load(c);
            c.check(sel(c, "New Trap").equals("CLEARED") && sel(c, "Old Trap").equals("CLEARED"),
                    "a saved FULL with no capture should load as CLEARED, reads " + sel(c, "New Trap") + "/" + sel(c, "Old Trap"));

            // ---- his own Full Trap capture: FULL kept, Mode and Full Trap row show for that trap only ---------------
            String routeJson = c.onClient(mc -> {
                Object e = Mod.staticCall(AT, "entry", "New Trap", mode("CLEARED"));
                Object route = Mod.call(e, "route");
                return String.valueOf(Mod.staticCall("autoroutes.RouteStore", "routeToJson",
                        route));
            });
            Files.writeString(file, "{\"enabled\": false, \"modes\": {\"New Trap\": \"FULL\", \"Old Trap\": \"FULL\"},"
                    + " \"routes\": {\"New Trap\": {\"FULL\": {\"savedMs\": 1, \"route\": " + routeJson + "}}}}",
                    StandardCharsets.UTF_8);
            load(c);
            boolean own = c.onClient(mc -> (Boolean) Mod.staticCall(AT, "hasOwnFull", "New Trap"));
            c.check(own, "his own Full Trap capture was not read");
            c.check(sel(c, "New Trap").equals("FULL"), "New Trap with his own Full Trap should keep FULL, reads " + sel(c, "New Trap"));
            c.check(sel(c, "Old Trap").equals("CLEARED"), "Old Trap has no capture and should read CLEARED, reads " + sel(c, "Old Trap"));
            List<String> l1 = labels(c);
            c.note("tab, New Trap Full Trap captured: " + l1);
            c.check(count(l1, "Mode: Full Trap") == 1 && count(l1, "Mode:") == 1,
                    "exactly one Mode button (New Trap's, on Full Trap) should show: " + l1);
            c.check(count(l1, "Full Trap: captured") == 1, "New Trap's own Full Trap row should show: " + l1);

            // ---- a chosen mode survives save + load ---------------------------------------------------------------
            c.ctx().runOnClient(mc -> Mod.staticCall(AT, "setSelectedMode", "New Trap", mode("CLEARED")));
            load(c);
            c.check(sel(c, "New Trap").equals("CLEARED"), "Just Cleared chosen for New Trap did not survive a reload, reads "
                    + sel(c, "New Trap"));
        } finally {
            if (was == null) {
                Files.deleteIfExists(file);
            } else {
                Files.write(file, was);
            }
            load(c);
        }
    }
}

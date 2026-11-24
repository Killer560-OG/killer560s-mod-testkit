package dev.testkit.gametest.menu;

import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Mod gaps-colors-1124 (killer560 11-23: the missing color and line-width rows):
 * <ul>
 *   <li>1124-menu-gaps-colors-rows: every new row is built by its tab ("Solver ... Color" per solver, "Solver Highlight Line
 *       Width" on all of them, Inventory Search Highlight Color, Chest ESP Color, Waypoint Show Distance and line width rows,
 *       Door Line Width, Arc Color).</li>
 *   <li>1124-menu-gaps-colors-saved: every new value is set, saved, the config reloaded from disk and read back; the
 *       defaults are the old fixed values (a solver draws the call's own color and width until a row is changed); the draw
 *       helpers the renderers call (SolverEspRender.ch / width, StorageSearchEsp.espRgb) return the picked color and width.</li>
 * </ul>
 */
final class GapsColors1124Cases {

    private static final String TABS = "com.killer560.hub.gui.tab.";
    private static final String ESP = "puzzlesolvers.SolverEspConfig";
    private static final String RENDER = "puzzlesolvers.SolverEspRender";

    /** solver enum name, its tab, its config, rows that must exist. */
    private static final String[][] SOLVERS = {
            {"BOULDER", "BoulderSolverTab", "BoulderSolverConfig", "Solver Highlight Color"},
            {"QUIZ", "QuizSolverTab", "QuizSolverConfig", "Solver Answer Color"},
            {"WEIRDOS", "WeirdosSolverTab", "WeirdosSolverConfig", "Solver Correct Chest Color,Solver Wrong Chest Color"},
            {"ICE_PATH", "IcePathSolverTab", "IcePathSolverConfig", "Solver Path Color,Solver Next Box Color"},
            {"WATER", "WaterSolverTab", "WaterSolverConfig", "Solver Ready Lever Color,Solver Waiting Lever Color,Solver Lever Line Color"},
            {"BLAZE", "BlazeSolverTab", "BlazeSolverConfig",
                    "Solver First Blaze Color,Solver Second Blaze Color,Solver Third Blaze Color,Solver Other Blazes Color"},
            {"TIC_TAC_TOE", "TicTacToeSolverTab", "TicTacToeSolverConfig", "Solver Best Move Color,Solver Predicted Move Color"},
            {"BEAMS", "BeamsSolverTab", "BeamsSolverConfig", "Solver Misaligned Pair Color"},
    };

    private GapsColors1124Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1124-menu-gaps-colors-rows", GapsColors1124Cases::rows);
        MenuSuite.test(s, "1124-menu-gaps-colors-saved", GapsColors1124Cases::saved);
    }

    private static List<String> labels(Session c, String tab) {
        return c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            try {
                Object t = Class.forName(TABS + tab).getConstructor().newInstance();
                List<?> w = (List<?>) t.getClass().getMethod("buildWidgets", int.class, int.class, int.class, Runnable.class)
                        .invoke(t, 0, 0, 360, (Runnable) () -> { });
                for (Object o : w) {
                    Object msg = o.getClass().getMethod("getMessage").invoke(o);
                    out.add(String.valueOf(msg.getClass().getMethod("getString").invoke(msg)).replaceAll("§.", "").trim());
                }
            } catch (ReflectiveOperationException e) {
                out.add("ERROR " + e);
            }
            return out;
        });
    }

    private static boolean has(List<String> l, String prefix) {
        return l.stream().anyMatch(x -> x.startsWith(prefix));
    }

    private static void need(Session c, String tab, List<String> l, String... rows) {
        for (String r : rows) {
            c.check(has(l, r + ":"), tab + " has no '" + r + "' row: " + l);
        }
    }

    private static void on(Session c, String cfgClass, String setter) {
        c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(cfgClass), setter, true));
    }

    static void rows(Session c) throws Exception {
        for (String[] s : SOLVERS) {
            on(c, "puzzlesolvers." + s[2], "setEnabled");
            List<String> l = labels(c, s[1]);
            c.note(s[1] + ": " + l);
            need(c, s[1], l, s[3].split(","));
            need(c, s[1], l, "Solver Highlight Line Width");
        }
        // The two solvers that already had a color row keep it and gain the width row.
        for (String[] t : new String[][]{{"IceFillSolverTab", "puzzlesolvers.IceFillSolverConfig"},
                {"TeleportMazeSolverTab", "puzzlesolvers.TeleportMazeSolverConfig"}}) {
            on(c, t[1], "setEnabled");
            List<String> l = labels(c, t[0]);
            need(c, t[0], l, "Solver Highlight Line Width");
        }
        on(c, "inventorysearch.InventorySearchConfig", "setEnabled");
        need(c, "InventorySearchTab", labels(c, "InventorySearchTab"), "Highlight Color");
        on(c, "storagesearch.StorageSearchConfig", "setEnabled");
        need(c, "StorageSearchTab", labels(c, "StorageSearchTab"), "Chest ESP Color");
        c.ctx().runOnClient(mc -> Mod.call(Mod.cfg("etherwarp.EtherwarpWaypointsConfig"), "setHighlightBlocks", true));
        need(c, "EtherwarpTab", labels(c, "EtherwarpTab"), "Waypoint Show Distance", "Waypoint Box Line Width");
        on(c, "secretwaypoints.SecretWaypointsConfig", "setEnabled");
        need(c, "SecretWaypointsTab", labels(c, "SecretWaypointsTab"), "Waypoint Line Width");
        on(c, "witherdoors.WitherDoorsConfig", "setEnabled");
        need(c, "WitherDoorsTab", labels(c, "WitherDoorsTab"), "Door Line Width");
        on(c, "trajectories.TrajectoriesConfig", "setEnabled");
        need(c, "TrajectoriesTab", labels(c, "TrajectoriesTab"), "Arc Color");
    }

    private static Object solver(String name) {
        return Mod.enumValue(ESP + "$Solver", name);
    }

    static void saved(Session c) throws Exception {
        // ---- every solver: color slots and width survive a save + reload; defaults draw as before ----
        Object esp = c.onClient(mc -> Mod.cfg(ESP));
        for (String[] s : SOLVERS) {
            Object sv = solver(s[0]);
            int slots = s[3].split(",").length;
            c.check((Integer) c.onClient(mc -> Mod.call(esp, "getColor", sv, 0)) == 0, s[0] + " starts with a color override");
            c.check((Float) c.onClient(mc -> Mod.call(esp, "getLineWidth", sv)) == 0f, s[0] + " starts with a width override");
            for (int i = 0; i < slots; i++) {
                int slot = i;
                int argb = 0xFF102030 + slot * 0x010101 + sv.hashCode() % 7;
                c.ctx().runOnClient(mc -> Mod.call(esp, "setColor", sv, slot, argb));
            }
            c.ctx().runOnClient(mc -> Mod.call(esp, "setLineWidth", sv, 4.5f));
        }
        c.ctx().runOnClient(mc -> Mod.call(esp, "save"));
        c.ctx().runOnClient(mc -> Mod.staticCall(ESP, "load"));
        Object esp2 = c.onClient(mc -> Mod.cfg(ESP));
        for (String[] s : SOLVERS) {
            Object sv = solver(s[0]);
            int slots = s[3].split(",").length;
            for (int i = 0; i < slots; i++) {
                int slot = i;
                int want = 0xFF102030 + slot * 0x010101 + sv.hashCode() % 7;
                int got = (Integer) c.onClient(mc -> Mod.call(esp2, "getColor", sv, slot));
                c.check(got == want, s[0] + " slot " + slot + " came back " + Integer.toHexString(got) + ", want " + Integer.toHexString(want));
            }
            float w = (Float) c.onClient(mc -> Mod.call(esp2, "getLineWidth", sv));
            c.check(w == 4.5f, s[0] + " width came back " + w);
        }
        // The draw helpers: a picked slot returns its channel, no pick returns the call's own value; the width override wins.
        Object boulder = solver("BOULDER");
        float red = (Float) c.onClient(mc -> Mod.staticCall(RENDER, "ch", Mod.call(esp2, "getColor", boulder, 0), 16, 0.5f));
        float dflt = (Float) c.onClient(mc -> Mod.staticCall(RENDER, "ch", 0, 16, 0.5f));
        float width = (Float) c.onClient(mc -> Mod.staticCall(RENDER, "width", boulder, 2f));
        int boulderColor = (Integer) c.onClient(mc -> Mod.call(esp2, "getColor", boulder, 0));
        c.check(Math.abs(red - ((boulderColor >> 16 & 255) / 255f)) < 0.001f, "the renderer's channel for a picked color is " + red);
        c.check(dflt == 0.5f, "an unset color must give the call's own channel, got " + dflt);
        c.check(width == 4.5f, "the renderer's width for a set solver is " + width + ", want 4.5");
        // Put everything back to the defaults.
        for (String[] s : SOLVERS) {
            Object sv = solver(s[0]);
            for (int i = 0; i < s[3].split(",").length; i++) {
                int slot = i;
                c.ctx().runOnClient(mc -> Mod.call(esp2, "setColor", sv, slot, 0));
            }
            c.ctx().runOnClient(mc -> Mod.call(esp2, "setLineWidth", sv, 0f));
        }
        c.ctx().runOnClient(mc -> Mod.call(esp2, "save"));
        float back = (Float) c.onClient(mc -> Mod.staticCall(RENDER, "width", boulder, 2f));
        c.check(back == 2f, "after reset the width is " + back + ", want the call's 2");

        // ---- Inventory Search highlight color ----
        roundTrip(c, "inventorysearch.InventorySearchConfig", "setHighlightColor", "getHighlightColor", 0xFF3366CC, 0xFFFF5555);
        // ---- Cape pattern colors ----
        roundTrip(c, "cape.CapeConfig", "setCustomColourA", "getCustomColourA", 0x123456, 0x2D6CDF);
        roundTrip(c, "cape.CapeConfig", "setCustomColourB", "getCustomColourB", 0x654321, 0xF2B134);
        // ---- Storage Search Chest ESP color: unset follows the theme, a pick wins; the draw helper agrees ----
        String ss = "storagesearch.StorageSearchConfig";
        Object ssCfg = c.onClient(mc -> Mod.cfg(ss));
        c.check((Integer) c.onClient(mc -> Mod.call(ssCfg, "getChestEspColor")) == 0, "Chest ESP color starts set instead of following the theme");
        int theme = (Integer) c.onClient(mc -> Mod.staticCall("storagesearch.StorageSearchEsp", "espRgb", ssCfg));
        c.note("Chest ESP unset draws " + Integer.toHexString(theme) + " (the menu theme's accent)");
        c.ctx().runOnClient(mc -> Mod.call(ssCfg, "setChestEspColor", 0xFF224466));
        int picked = (Integer) c.onClient(mc -> Mod.staticCall("storagesearch.StorageSearchEsp", "espRgb", ssCfg));
        c.check((picked & 0xFFFFFF) == 0x224466, "Chest ESP drew " + Integer.toHexString(picked) + " after picking 224466");
        roundTrip(c, ss, "setChestEspColor", "getChestEspColor", 0xFF224466, 0);
        // ---- Etherwarp waypoints show distance and widths ----
        String ew = "etherwarp.EtherwarpWaypointsConfig";
        roundTrip(c, ew, "setHighlightDistance", "getHighlightDistance", 100.0, 64.0);
        roundTrip(c, ew, "setLineWidth", "getLineWidth", 4f, 2f);
        roundTrip(c, "secretwaypoints.SecretWaypointsConfig", "setLineWidth", "getLineWidth", 4f, 2f);
        roundTrip(c, "witherdoors.WitherDoorsConfig", "setLineWidth", "getLineWidth", 4f, 2f);
        // ---- Trajectories arc color ----
        roundTrip(c, "trajectories.TrajectoriesConfig", "setColor", "getColor", 0xFF884422, 0);
    }

    /** Sets, saves, reloads from disk, reads back (the value must match), then restores {@code restore} and saves. */
    private static void roundTrip(Session c, String cls, String setter, String getter, Object value, Object restore) {
        Object cfg = c.onClient(mc -> Mod.cfg(cls));
        c.ctx().runOnClient(mc -> Mod.call(cfg, setter, value));
        c.ctx().runOnClient(mc -> Mod.call(cfg, "save"));
        c.ctx().runOnClient(mc -> Mod.staticCall(cls, "load"));
        Object now = c.onClient(mc -> Mod.call(Mod.cfg(cls), getter));
        boolean same = now.equals(value) || (now instanceof Integer i && value instanceof Integer v
                && (i & 0xFFFFFF) == (v & 0xFFFFFF));
        c.check(same, cls + "." + getter + " came back " + now + " after save + reload, want " + value);
        Object cfg2 = c.onClient(mc -> Mod.cfg(cls));
        c.ctx().runOnClient(mc -> Mod.call(cfg2, setter, restore));
        c.ctx().runOnClient(mc -> Mod.call(cfg2, "save"));
    }
}

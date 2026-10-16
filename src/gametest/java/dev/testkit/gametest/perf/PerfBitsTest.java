package dev.testkit.gametest.perf;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.Scenario;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 95-perf-bits: the mod's {@code util/FeatureBits} follow every gated switch (fps-idle-2-1016, 2026-10-16). ~1 min, only
 * when named. Fails on main (no FeatureBits) and on any branch that gates a listener without a row here.
 *
 * <p>For every bit: the switch off -> bit off, on -> bit on, both through the config's own setter; a {@code load()}
 * (what a profile apply does) -> bit equals the loaded switch; the switch restored. Then the safety net: a switch set
 * BEHIND the setter's back (reflection on the field) must be found by the skip re-check within a few seconds of frames,
 * with the ERROR line that names it, and the bit turned on - so a forgotten {@code changed()} is loud, not silent.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class PerfBitsTest implements FabricClientGameTest {

    private static final String NAME = "95-perf-bits";
    private static final String BITS = "util.FeatureBits";

    /** bit name -> config class (under com.killer560.hub.), setter, raw getter. Add a row with every gated listener. */
    static final Map<String, String[]> GATED = new LinkedHashMap<>();

    static {
        for (String s : new String[]{"Beams", "Blaze", "Boulder", "IceFill", "IcePath", "Quiz", "TeleportMaze", "TicTacToe",
                "Water", "Weirdos"}) {
            GATED.put(s + " Solver render", new String[]{"puzzlesolvers." + s + "SolverConfig", "setEnabled", "isEnabledRaw"});
            GATED.put(s + " Solver tick", new String[]{"puzzlesolvers." + s + "SolverConfig", "setEnabled", "isEnabledRaw"});
        }
        GATED.put("Livid Solver tick", new String[]{"boss.LividSolverConfig", "setEnabled", "isEnabledRaw"});
        GATED.put("Livid Solver render", new String[]{"boss.LividSolverConfig", "setEnabled", "isEnabledRaw"});
        GATED.put("P4 Platform render", new String[]{"p4platform.P4PlatformHighlightConfig", "setEnabled", "isEnabledRaw"});
        GATED.put("Waypoint Routes render", new String[]{"routes.WaypointRoutesConfig", "setEnabled", "isEnabledRaw"});
        GATED.put("Pathfinding render", new String[]{"pathfinding.PathfindingConfig", "setEnabled", "isEnabledRaw"});
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String filter = System.getProperty("testkit.scenario", "");
        if (filter.isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        List<String> failures = new ArrayList<>();
        List<String> report = new ArrayList<>();
        ctx.waitTicks(20);
        try {
            failures.addAll(ctx.computeOnClient(mc -> followSetters(report)));
            try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
                ctx.waitTicks(60);
                failures.addAll(safetyNet(ctx, report));
            }
        } catch (AssertionError | RuntimeException e) {
            failures.add("threw: " + e);
        }
        for (String line : report) {
            System.out.println("[" + NAME + "] " + line);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("[" + NAME + "] " + failures.size() + " failure(s):\n  " + String.join("\n  ", failures));
        }
        System.out.println("[" + NAME + "] PASS");
    }

    private static Map<String, Boolean> snapshot() {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (String line : ((String) Mod.staticCall(BITS, "snapshot")).split("\n")) {
            int eq = line.lastIndexOf('=');
            if (eq > 0) {
                m.put(line.substring(0, eq), Boolean.parseBoolean(line.substring(eq + 1)));
            }
        }
        return m;
    }

    /** Client thread. */
    private static List<String> followSetters(List<String> report) {
        List<String> failures = new ArrayList<>();
        Map<String, Boolean> bits = snapshot();
        report.add(bits.size() + " bit(s): " + bits.keySet());
        if (bits.isEmpty()) {
            failures.add("no FeatureBits registered - nothing is gated");
            return failures;
        }
        for (String name : bits.keySet()) {
            if (!GATED.containsKey(name)) {
                failures.add("bit '" + name + "' has no row in 95-perf-bits (add its config, setter and raw getter)");
            }
        }
        for (Map.Entry<String, String[]> e : GATED.entrySet()) {
            String name = e.getKey();
            if (!bits.containsKey(name)) {
                failures.add("bit '" + name + "' is not registered");
                continue;
            }
            String[] c = e.getValue();
            Object cfg = Mod.cfg(c[0]);
            boolean was = (Boolean) Mod.call(cfg, c[2]);
            try {
                Mod.call(cfg, c[1], false);
                boolean off = snapshot().get(name);
                Mod.call(cfg, c[1], true);
                boolean on = snapshot().get(name);
                if (off || !on) {
                    failures.add(name + ": setter off -> bit " + off + ", setter on -> bit " + on + " (want false, true)");
                }
                // A profile apply reloads the config from disk: the bit must follow whatever was loaded.
                Mod.staticCall(c[0], "load");
                Object loaded = Mod.cfg(c[0]);
                boolean fileValue = (Boolean) Mod.call(loaded, c[2]);
                boolean afterLoad = snapshot().get(name);
                if (afterLoad != fileValue) {
                    failures.add(name + ": after load() the switch is " + fileValue + " but the bit is " + afterLoad);
                }
            } finally {
                Mod.call(Mod.cfg(c[0]), c[1], was);
            }
        }
        report.add("setters and load() checked for " + GATED.size() + " bit(s)");
        return failures;
    }

    /** A switch flipped on behind the setter must be caught by the skip re-check (ERROR + bit on). */
    private static List<String> safetyNet(ClientGameTestContext ctx, List<String> report) {
        List<String> failures = new ArrayList<>();
        String name = "Beams Solver render";
        String cls = "puzzlesolvers.BeamsSolverConfig";
        long mark = LogTap.mark();
        boolean was = ctx.computeOnClient(mc -> (Boolean) Mod.call(Mod.cfg(cls), "isEnabledRaw"));
        ctx.runOnClient(mc -> {
            Mod.call(Mod.cfg(cls), "setEnabled", false);
            snapshot(); // refreshed: off
            setField(Mod.cfg(cls), "enabled", true); // behind the setter's back
        });
        ctx.waitTicks(60); // ~1.5 frames a tick in a gametest loop: well over 64 skips
        boolean bit = ctx.computeOnClient(mc -> {
            // read the live bit WITHOUT a refresh: FeatureBits.ON[id]
            String snap = currentWithoutRefresh(name);
            return Boolean.parseBoolean(snap);
        });
        List<String> errors = LogTap.modErrorsSince(mark);
        boolean named = errors.stream().anyMatch(l -> l.contains("FeatureBits") && l.contains(name));
        report.add("safety net: field set behind the setter -> bit " + bit + " after 60 ticks, ERROR naming it: " + named);
        if (!bit || !named) {
            failures.add("a switch set without FeatureBits.changed() was not caught by the re-check (bit " + bit
                    + ", ERROR line " + named + ")");
        }
        ctx.runOnClient(mc -> Mod.call(Mod.cfg(cls), "setEnabled", was));
        return failures;
    }

    private static String currentWithoutRefresh(String name) {
        try {
            Class<?> c = Mod.cls(BITS);
            Field on = c.getDeclaredField("ON");
            Field names = c.getDeclaredField("names");
            on.setAccessible(true);
            names.setAccessible(true);
            boolean[] bits = (boolean[]) on.get(null);
            String[] ns = (String[]) names.get(null);
            for (int i = 0; i < ns.length; i++) {
                if (name.equals(ns[i])) {
                    return Boolean.toString(bits[i]);
                }
            }
            return "missing";
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void setField(Object target, String field, boolean v) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.setBoolean(target, v);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}

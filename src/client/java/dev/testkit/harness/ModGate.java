package dev.testkit.harness;

import net.fabricmc.loader.api.FabricLoader;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns the scenarios of a {@link RequiresMod} class into SKIP rows when that mod is not loaded.
 *
 * <p>{@code MixinClientGameTestRunner} opens the gate before each test class and closes it after. While it names a
 * missing mod, {@code Scenario.skip} and {@code hx.Session} record each name the filter selects as {@code SKIP -
 * needs <mod>} and run nothing, and a throw out of the class is filed as that skip instead of a failure (nothing it
 * could have tested was there). List mode is unaffected, so a sharded run still plans every name and each one still
 * gets its row.
 */
public final class ModGate {

    private static volatile String missing;
    private static volatile String testClass;
    private static final Set<String> skipped = new LinkedHashSet<>();
    private static int skippedThisClass;

    private ModGate() {
    }

    /** Before a test class runs: note which required mod (if any) is absent. */
    public static void begin(Class<?> test) {
        testClass = test.getSimpleName();
        missing = null;
        skippedThisClass = 0;
        for (Class<?> c = test; c != null; c = c.getEnclosingClass()) {
            RequiresMod req = c.getAnnotation(RequiresMod.class);
            if (req != null) {
                for (String id : req.value()) {
                    if (!FabricLoader.getInstance().isModLoaded(id)) {
                        missing = id;
                        return;
                    }
                }
                return;
            }
        }
    }

    public static void end() {
        missing = null;
        testClass = null;
    }

    /** The required mod that is not loaded for the running class, or null when the class may run. */
    public static String missing() {
        return missing;
    }

    /** One line saying why. */
    public static String reason() {
        return "needs " + missing + " (not loaded in this run)";
    }

    /**
     * Record {@code name} as skipped (once) and say so. Called for each selected scenario, Session or case while the
     * gate names a missing mod.
     */
    public static synchronized void skip(String name) {
        if (!skipped.add(name)) {
            return;
        }
        skippedThisClass++;
        SuiteVerdict.started(name);
        SuiteVerdict.finished(name);
        System.out.println("[" + name + "] SKIPPED - " + reason());
        if (!Report.recorded(name)) {
            Report.caseFinished(name, "SKIP", "", reason(), List.of(), List.of());
        }
    }

    /** The class threw while gated: file it as the skip it is, under the class name if it selected nothing. */
    public static void skipThrown(Throwable t) {
        System.out.println("[suite] " + testClass + " stopped while gated (" + reason() + "): "
                + t.getClass().getSimpleName() + ": " + t.getMessage() + " - recorded as SKIP, not a failure");
        if (skippedThisClass == 0) {
            skip(testClass);
        }
    }
}

package dev.testkit.gametest;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * This machine's configuration, as build.gradle resolved it from testkit.properties / testkit.local.properties /
 * TESTKIT_* environment variables (see testkit.properties for every key). Nothing in a scenario names a path on one
 * particular machine: it asks here, and a value that is not configured comes back as a path that does not exist, so
 * the scenario's own "is the data there?" check SKIPs it with a reason instead of failing.
 */
public final class Machine {

    /** What to tell someone whose machine has no room captures configured. */
    public static final String CAPTURES_HINT = "room captures and room database not found - set prismInstances "
            + "(and roomsInstance/simInstance) in testkit.local.properties to a PrismLauncher instances folder whose "
            + "killer560s-mod config holds them";

    private Machine() {
    }

    private static String prop(String key, String env, String fallback) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) {
            v = env == null ? null : System.getenv(env);
        }
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    /** The killer560s-mod checkout, or null when none is configured or it does not exist. */
    public static Path modSource() {
        String p = prop("testkit.modSource", "TESTKIT_MOD_SOURCE", "");
        if (p.isEmpty()) {
            return null;
        }
        Path path = Path.of(p);
        return Files.isDirectory(path) ? path : null;
    }

    /** The configured PrismLauncher instances folder, or null. */
    public static Path prismInstances() {
        String p = prop("testkit.prismInstances", "TESTKIT_PRISM_INSTANCES", "");
        return p.isEmpty() ? null : Path.of(p);
    }

    /** Instance whose config holds the room captures most sim scenarios copy (roomsInstance). */
    public static String roomsInstance() {
        return prop("testkit.roomsInstance", "TESTKIT_ROOMS_INSTANCE", "26.1.2 (Mod Only Test)");
    }

    /** Instance whose captures the puzzle-solve and essence/aura scenarios copy (simInstance, TESTKIT_SIM_INSTANCE). */
    public static String simInstance() {
        // The environment variable first: it was the documented way to pick the instance before the config existed.
        String env = System.getenv("TESTKIT_SIM_INSTANCE");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return prop("testkit.simInstance", null, "Map Logger");
    }

    /**
     * {@code <prismInstances>/<instance>/minecraft/config}, forward slashes. When no instances folder is configured, a
     * path under build/ that never exists, so callers' existence checks fail and the scenario SKIPs.
     */
    public static String instanceConfigDir(String instance) {
        Path base = prismInstances();
        if (base == null) {
            base = Path.of(System.getProperty("testkit.checkout", "."), "build", "prism-instances-not-configured");
        }
        return base.resolve(instance).resolve("minecraft").resolve("config").toString().replace('\\', '/');
    }

    /** Config folder of {@link #roomsInstance()}. */
    public static String roomsConfigDir() {
        return instanceConfigDir(roomsInstance());
    }
}

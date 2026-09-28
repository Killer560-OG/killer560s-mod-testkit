package dev.testkit.gametest;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Reaches into the mod being tested by reflection.
 *
 * <p>The mod arrives through {@code -PmodUnderTest=...}, which puts it on the runtime classpath only - the
 * scenarios compile without it, so the harness still builds and its own tests still run on a machine that
 * has no copy of the mod. Reflection is the cost of that, and it is the right trade: a harness that only
 * compiles when one particular mod is present stops being a harness.
 *
 * <p>It also means what gets driven is the mod's <b>real public surface</b> - the same methods its own
 * menus and commands call - rather than a test-only hook added to make it drivable. Nothing here can
 * accidentally exercise a path the shipped mod does not have.
 *
 * <p>Every miss is loud. A renamed setter silently doing nothing would turn into a scenario that reports a
 * module clean because it never turned it on, which is the single worst failure this harness can have.
 */
public final class ModUnderTest {

    private ModUnderTest() {
    }

    public static boolean loaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    public static void require(String modId) {
        if (!loaded(modId)) {
            throw new AssertionError("Mod '" + modId + "' is not loaded. Pass it with "
                    + "-PmodUnderTest=C:/path/to/the.jar - without it this scenario would test nothing "
                    + "and report it as clean.");
        }
    }

    /** The singleton behind a {@code getInstance()} config class. */
    public static Object config(String className) {
        try {
            Class<?> type = Class.forName(className);
            return type.getMethod("getInstance").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not reach " + className + ".getInstance()", e);
        }
    }

    public static Object staticCall(String className, String method) {
        try {
            return Class.forName(className).getMethod(method).invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not call static " + className + "." + method + "()", e);
        }
    }

    /**
     * Switch a feature back off, for a scenario that turned it on.
     *
     * <p>A scenario must leave the mod as it found it. These configs are live singletons that outlive a
     * scenario, so one that enables an aura and walks away leaves it running through the NEXT scenario's setup -
     * and the next scenario then finds its lever already clicked and reports having sent nothing, which reads as
     * a broken feature rather than a dirty fixture. That cost three debugging rounds on one scenario.
     *
     * <p>Failures are swallowed on purpose: this runs in cleanup, where throwing would replace a real result
     * with a teardown error.
     */
    public static void turnOff(String configClass, String setter) {
        try {
            set(config(configClass), setter, false);
        } catch (Throwable ignored) {
            // cleanup must not become the failure
        }
    }

    public static void set(Object target, String setter, boolean value) {
        invoke(target, setter, boolean.class, value);
    }

    public static void set(Object target, String setter, int value) {
        invoke(target, setter, int.class, value);
    }

    public static void set(Object target, String setter, double value) {
        invoke(target, setter, double.class, value);
    }

    public static boolean getBoolean(Object target, String getter) {
        return (Boolean) call(target, getter);
    }

    public static int getInt(Object target, String getter) {
        return (Integer) call(target, getter);
    }

    private static Object call(Object target, String getter) {
        try {
            return target.getClass().getMethod(getter).invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not read " + target.getClass().getName() + "." + getter + "()", e);
        }
    }

    private static void invoke(Object target, String setter, Class<?> type, Object value) {
        try {
            target.getClass().getMethod(setter, type).invoke(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not call " + target.getClass().getName() + "." + setter
                    + "(" + type.getSimpleName() + ")", e);
        }
    }
}

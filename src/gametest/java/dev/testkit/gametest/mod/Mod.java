package dev.testkit.gametest.mod;

import dev.testkit.gametest.LogTap;
import dev.testkit.harness.Coverage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reflection layer v2 over killer560s-mod, for scenarios that compile without it.
 *
 * <p>Class names may be fully qualified or relative to {@code com.killer560.hub} ({@code "secrets.DungeonState"}).
 * Every miss is LOUD - an {@link AssertionError} naming the class and member - because a renamed setter that quietly
 * did nothing is a scenario that reports a feature clean without ever turning it on. Every class reached is marked
 * in {@link Coverage}.
 *
 * <pre>{@code
 * try (var on = Mod.with("playerstats.PlayerStatsConfig", "Enabled", true)) {   // restored on close
 *     ...
 * }
 * String floor = (String) Mod.staticCall("secrets.DungeonState", "getFloor");
 * String hp = (String) Mod.field("playerstats.PlayerStatsFeature", "health");
 * Pattern p = Mod.pattern("leapmenu.PartyTracker", "TAB_REGEX");
 * Mod.Mark mark = Mod.mark();  ...  Mod.assertHealthy(mark);
 * }</pre>
 *
 * <p>Thread: config setters and most feature state belong to the client thread. Call these inside
 * {@code ctx.runOnClient}/{@code computeOnClient} when they touch game state; pure reads of static fields are fine
 * from the test thread. Frozen after WP1 (ask in docs/requests/ for additions).
 */
public final class Mod {

    public static final String ROOT = "com.killer560.hub.";
    private static final String BUILD_VARIANT = ROOT + "BuildVariant";
    private static final String FEATURE_GUARD = ROOT + "util.FeatureGuard";
    private static final String CHAT_OBSERVER = ROOT + "util.ChatObserver";

    private Mod() {
    }

    // ---- classes ----------------------------------------------------------------------------------------------

    /** Load a mod class; {@code name} may be relative to {@code com.killer560.hub}. */
    public static Class<?> cls(String name) {
        String fq = name.startsWith(ROOT) || name.startsWith("net.") || name.startsWith("java.") ? name : ROOT + name;
        try {
            Class<?> c = Class.forName(fq);
            if (fq.startsWith(ROOT)) {
                Coverage.markClass(fq);
            }
            return c;
        } catch (ClassNotFoundException e) {
            throw new AssertionError("[mod] no class " + fq + " in the mod under test (-PmodUnderTest)", e);
        }
    }

    /** Whether a class exists (e.g. a feature only some builds have). Never throws. */
    public static boolean has(String name) {
        try {
            cls(name);
            return true;
        } catch (AssertionError e) {
            return false;
        }
    }

    /** True for the cheat variant ({@code BuildVariant.CHEAT_FEATURES_ENABLED}). */
    public static boolean isCheat() {
        return (Boolean) staticField(BUILD_VARIANT, "CHEAT_FEATURES_ENABLED");
    }

    /** True unless the jar is a release build ({@code BuildVariant.DEV_TOOLS}). */
    public static boolean isDevTools() {
        return (Boolean) staticField(BUILD_VARIANT, "DEV_TOOLS");
    }

    // ---- config singletons ------------------------------------------------------------------------------------

    /** The {@code getInstance()} singleton of a config class. */
    public static Object cfg(String configClass) {
        return staticCall(configClass, "getInstance");
    }

    /** {@code cfg(configClass).setter(value)}; the setter is found by name and a compatible single parameter. */
    public static void set(String configClass, String setter, Object value) {
        call(cfg(configClass), setter, value);
    }

    /** {@code cfg(configClass).getter()}. */
    public static Object get(String configClass, String getter) {
        return call(cfg(configClass), getter);
    }

    /**
     * Set a config property for the duration of a try block and put the old value back on close.
     * {@code property} is the bean name: {@code "Enabled"} uses {@code setEnabled} and {@code isEnabled}/
     * {@code getEnabled}. Restoring matters because the client JVM - and its config singletons - outlive every case.
     */
    public static AutoCloseable with(String configClass, String property, Object value) {
        Object target = cfg(configClass);
        String getter = findMethod(target.getClass(), "is" + property, 0) != null ? "is" + property : "get" + property;
        Object old = call(target, getter);
        call(target, "set" + property, value);
        return () -> call(target, "set" + property, old);
    }

    // ---- members ----------------------------------------------------------------------------------------------

    /** A static field's value (any visibility). */
    public static Object field(String className, String field) {
        return staticField(className, field);
    }

    /** An instance field's value (any visibility), searching superclasses. */
    public static Object field(Object target, String field) {
        try {
            return findField(target.getClass(), field).get(target);
        } catch (IllegalAccessException e) {
            throw new AssertionError("[mod] cannot read " + target.getClass().getName() + "." + field, e);
        }
    }

    /** Write a static field (non-final). For flags that are plain statics, e.g. SpotifyLyricsFeature.enabled. */
    public static void setField(String className, String field, Object value) {
        Field f = findField(cls(className), field);
        if (Modifier.isFinal(f.getModifiers())) {
            throw new AssertionError("[mod] " + className + "." + field + " is final; use its setter");
        }
        try {
            f.set(null, value);
        } catch (IllegalAccessException e) {
            throw new AssertionError("[mod] cannot write " + className + "." + field, e);
        }
    }

    /** A {@code Pattern} constant, e.g. {@code pattern("leapmenu.PartyTracker", "TAB_REGEX")}. */
    public static Pattern pattern(String className, String field) {
        Object v = staticField(className, field);
        if (!(v instanceof Pattern p)) {
            throw new AssertionError("[mod] " + className + "." + field + " is not a Pattern but "
                    + (v == null ? "null" : v.getClass().getName()));
        }
        return p;
    }

    /** Call a static method by name; overloads are chosen by argument count and compatibility. */
    public static Object staticCall(String className, String method, Object... args) {
        Class<?> c = cls(className);
        Method m = resolve(c, method, args, true);
        try {
            return m.invoke(null, args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("[mod] " + c.getName() + "." + method + " threw: " + rootCause(e), e);
        }
    }

    /** Call an instance method by name; overloads are chosen by argument count and compatibility. */
    public static Object call(Object target, String method, Object... args) {
        Method m = resolve(target.getClass(), method, args, false);
        try {
            return m.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("[mod] " + target.getClass().getName() + "." + method + " threw: "
                    + rootCause(e), e);
        }
    }

    /** An enum constant of a mod enum, by name. */
    public static Object enumValue(String enumClass, String constant) {
        for (Object o : cls(enumClass).getEnumConstants()) {
            if (((Enum<?>) o).name().equals(constant)) {
                return o;
            }
        }
        throw new AssertionError("[mod] no constant " + constant + " on " + enumClass);
    }

    // ---- health ---------------------------------------------------------------------------------------------

    /** What {@link #assertHealthy} compares against: taken at the start of a case. */
    public record Mark(Set<String> guardDisabled, long chatFailures, long logSeq) {
    }

    public static Mark mark() {
        LogTap.install();
        return new Mark(guardDisabled(), chatFailures(), LogTap.mark());
    }

    /** Features {@code FeatureGuard} has switched off this session. */
    @SuppressWarnings("unchecked")
    public static Set<String> guardDisabled() {
        return new LinkedHashSet<>((Set<String>) staticCall(FEATURE_GUARD, "disabled"));
    }

    /** {@code ChatObserver.failures()}: chat listener/rewriter throws caught since start-up; -1 on an older jar. */
    public static long chatFailures() {
        if (!has(CHAT_OBSERVER)) {
            return -1;
        }
        try {
            return ((Number) staticCall(CHAT_OBSERVER, "failures")).longValue();
        } catch (AssertionError e) {
            return -1;
        }
    }

    /**
     * Fail if, since {@code since}: FeatureGuard disabled a feature, a chat listener threw (ChatObserver.failures()
     * moved - the counter, the last failure and the per-listener counts are quoted), or a killer560smod logger
     * printed an ERROR line. Each is the mod hurting itself, whatever the case was asserting.
     */
    public static void assertHealthy(Mark since) {
        List<String> problems = new ArrayList<>();
        Set<String> nowDisabled = guardDisabled();
        nowDisabled.removeAll(since.guardDisabled());
        if (!nowDisabled.isEmpty()) {
            problems.add("FeatureGuard disabled " + nowDisabled);
        }
        long failures = chatFailures();
        if (since.chatFailures() >= 0 && failures > since.chatFailures()) {
            Object last = staticCall(CHAT_OBSERVER, "lastFailure");
            Object byListener = staticCall(CHAT_OBSERVER, "failuresByListener");
            problems.add("ChatObserver.failures() " + since.chatFailures() + " -> " + failures + ", last: " + last
                    + ", by listener: " + (byListener instanceof Map<?, ?> m ? m : byListener));
        }
        List<String> errors = LogTap.modErrorsSince(since.logSeq());
        if (!errors.isEmpty()) {
            problems.add(errors.size() + " killer560smod ERROR line(s), first: " + errors.get(0));
        }
        if (!problems.isEmpty()) {
            throw new AssertionError("[mod] not healthy: " + String.join("; ", problems));
        }
    }

    // ---- internals --------------------------------------------------------------------------------------------

    private static Object staticField(String className, String field) {
        try {
            return findField(cls(className), field).get(null);
        } catch (IllegalAccessException e) {
            throw new AssertionError("[mod] cannot read " + className + "." + field, e);
        }
    }

    private static Field findField(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // try the superclass
            }
        }
        throw new AssertionError("[mod] no field " + name + " on " + c.getName());
    }

    private static Method findMethod(Class<?> c, String name, int arity) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == arity) {
                    return m;
                }
            }
        }
        return null;
    }

    private static Method resolve(Class<?> c, String name, Object[] args, boolean wantStatic) {
        List<String> seen = new ArrayList<>();
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(name)) {
                    continue;
                }
                seen.add(m.toGenericString());
                if (m.getParameterCount() != args.length || Modifier.isStatic(m.getModifiers()) != wantStatic) {
                    continue;
                }
                if (compatible(m.getParameterTypes(), args)) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        StringBuilder types = new StringBuilder();
        for (Object a : args) {
            types.append(types.isEmpty() ? "" : ", ").append(a == null ? "null" : a.getClass().getSimpleName());
        }
        throw new AssertionError("[mod] no " + (wantStatic ? "static " : "") + "method " + c.getName() + "." + name
                + "(" + types + ")" + (seen.isEmpty() ? "" : "; candidates: " + seen));
    }

    private static boolean compatible(Class<?>[] params, Object[] args) {
        for (int i = 0; i < params.length; i++) {
            Class<?> p = box(params[i]);
            if (args[i] == null) {
                if (params[i].isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (!p.isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> box(Class<?> c) {
        if (!c.isPrimitive()) {
            return c;
        }
        return switch (c.getName()) {
            case "boolean" -> Boolean.class;
            case "int" -> Integer.class;
            case "long" -> Long.class;
            case "double" -> Double.class;
            case "float" -> Float.class;
            case "short" -> Short.class;
            case "byte" -> Byte.class;
            case "char" -> Character.class;
            default -> Void.class;
        };
    }

    private static String rootCause(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.toString();
    }
}

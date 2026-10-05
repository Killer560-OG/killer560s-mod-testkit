package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What WP5 needs on top of {@link Mod} (frozen): constructors, private static fields of any type, and list
 * elements. Every miss throws an {@link AssertionError} naming the member, like Mod.
 */
final class R {

    private R() {
    }

    /** {@code new cls(args)} through the first declared constructor whose arity and types fit (any visibility). */
    static Object construct(String cls, Object... args) {
        Class<?> c = Mod.cls(cls);
        List<String> seen = new ArrayList<>();
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            seen.add(k.toGenericString());
            if (k.getParameterCount() != args.length || !fits(k.getParameterTypes(), args)) {
                continue;
            }
            try {
                k.setAccessible(true);
                return k.newInstance(args);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("[logic] new " + c.getName() + " threw: " + LogicCase.rootCause(e), e);
            }
        }
        throw new AssertionError("[logic] no constructor of " + c.getName() + " fits " + args.length
                + " argument(s); candidates: " + seen);
    }

    /** A static field of any type and visibility (final included), read. */
    static Object get(String cls, String field) {
        try {
            return field(Mod.cls(cls), field).get(null);
        } catch (IllegalAccessException e) {
            throw new AssertionError("[logic] cannot read " + cls + "." + field, e);
        }
    }

    /** Write a static field even when it is not final-free (volatile/private); refuses static final. */
    static void set(String cls, String field, Object value) {
        Field f = field(Mod.cls(cls), field);
        if (Modifier.isFinal(f.getModifiers())) {
            throw new AssertionError("[logic] " + cls + "." + field + " is final");
        }
        try {
            f.set(null, value);
        } catch (IllegalAccessException e) {
            throw new AssertionError("[logic] cannot write " + cls + "." + field, e);
        }
    }

    /** An instance field (any visibility), searching superclasses. */
    static Object get(Object target, String field) {
        try {
            return field(target.getClass(), field).get(target);
        } catch (IllegalAccessException e) {
            throw new AssertionError("[logic] cannot read " + target.getClass().getName() + "." + field, e);
        }
    }

    static void set(Object target, String field, Object value) {
        try {
            field(target.getClass(), field).set(target, value);
        } catch (IllegalAccessException e) {
            throw new AssertionError("[logic] cannot write " + target.getClass().getName() + "." + field, e);
        }
    }

    /**
     * A pattern by catalog-style ref: {@code Class#FIELD} (a Pattern field) or {@code Class#FIELD[i]} (element i of a
     * List/array of patterns).
     */
    static Pattern pattern(String ref) {
        int hash = ref.indexOf('#');
        if (hash <= 0) {
            throw new AssertionError("[logic] bad pattern ref " + ref);
        }
        String cls = ref.substring(0, hash);
        String rest = ref.substring(hash + 1);
        int br = rest.indexOf('[');
        if (br < 0) {
            Object v = get(cls, rest);
            if (v instanceof Pattern p) {
                return p;
            }
            throw new AssertionError("[logic] " + ref + " is not a Pattern but " + (v == null ? "null" : v.getClass()));
        }
        int index = Integer.parseInt(rest.substring(br + 1, rest.indexOf(']')));
        Object v = get(cls, rest.substring(0, br));
        Object el;
        if (v instanceof List<?> l) {
            el = index < l.size() ? l.get(index) : null;
        } else if (v instanceof Object[] a) {
            el = index < a.length ? a[index] : null;
        } else {
            throw new AssertionError("[logic] " + ref + ": field is neither a List nor an array");
        }
        if (el instanceof Pattern p) {
            return p;
        }
        throw new AssertionError("[logic] " + ref + " has no Pattern at index " + index);
    }

    private static Field field(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // superclass next
            }
        }
        throw new AssertionError("[logic] no field " + name + " on " + c.getName());
    }

    private static boolean fits(Class<?>[] params, Object[] args) {
        for (int i = 0; i < params.length; i++) {
            Class<?> p = params[i];
            Object a = args[i];
            if (a == null) {
                if (p.isPrimitive()) {
                    return false;
                }
                continue;
            }
            Class<?> boxed = p.isPrimitive() ? box(p) : p;
            if (!boxed.isInstance(a)) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> box(Class<?> c) {
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
}

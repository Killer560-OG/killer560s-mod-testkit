package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * The few reflection moves {@link Mod} (frozen) does not have: writing an instance field or a static field that may
 * be private, and calling a no-argument private method with the target's own exception surfacing unwrapped. Every
 * miss throws, naming the member.
 */
final class R {

    private R() {
    }

    static Field field(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // superclass
            }
        }
        throw new AssertionError("[ui] no field " + name + " on " + c.getName());
    }

    static Object get(Object target, String name) {
        try {
            return field(target.getClass(), name).get(target);
        } catch (IllegalAccessException e) {
            throw new AssertionError("[ui] cannot read " + name, e);
        }
    }

    static Object getStatic(Class<?> c, String name) {
        try {
            return field(c, name).get(null);
        } catch (IllegalAccessException e) {
            throw new AssertionError("[ui] cannot read " + c.getName() + "." + name, e);
        }
    }

    static void set(Object target, String name, Object value) {
        try {
            field(target.getClass(), name).set(target, value);
        } catch (IllegalAccessException e) {
            throw new AssertionError("[ui] cannot write " + name, e);
        }
    }

    static void setStatic(Class<?> c, String name, Object value) {
        Field f = field(c, name);
        if (Modifier.isFinal(f.getModifiers())) {
            throw new AssertionError("[ui] " + c.getName() + "." + name + " is final");
        }
        try {
            f.set(null, value);
        } catch (IllegalAccessException e) {
            throw new AssertionError("[ui] cannot write " + c.getName() + "." + name, e);
        }
    }

    /** Call a no-arg method (any visibility), rethrowing what IT threw rather than a reflection wrapper. */
    static Object call0(Object target, String name) throws Throwable {
        Method m = null;
        for (Class<?> k = target.getClass(); k != null && m == null; k = k.getSuperclass()) {
            for (Method candidate : k.getDeclaredMethods()) {
                if (candidate.getName().equals(name) && candidate.getParameterCount() == 0) {
                    m = candidate;
                    break;
                }
            }
        }
        if (m == null) {
            throw new AssertionError("[ui] no method " + name + "() on " + target.getClass().getName());
        }
        m.setAccessible(true);
        try {
            return m.invoke(target);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** Static no-arg call, unwrapped. */
    static Object callStatic0(Class<?> c, String name) throws Throwable {
        Method m = c.getDeclaredMethod(name);
        m.setAccessible(true);
        try {
            return m.invoke(null);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    static Class<?> cls(String name) {
        return Mod.cls(name);
    }
}

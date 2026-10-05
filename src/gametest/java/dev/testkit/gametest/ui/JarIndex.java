package dev.testkit.gametest.ui;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Lists classes in the loaded mod jar, so a sweep covers what the jar HAS rather than a list kept here: a config
 * or screen added tomorrow shows up in the next run without anyone editing the testkit.
 */
final class JarIndex {

    static final String MOD_ID = "killer560smod";
    static final String ROOT = "com.killer560.hub.";

    private JarIndex() {
    }

    /** Fully-qualified names of top-level (no '$') classes under com.killer560.hub whose simple name passes. */
    static List<String> classNames(Predicate<String> simpleName) {
        TreeSet<String> out = new TreeSet<>();
        ModContainer mod = FabricLoader.getInstance().getModContainer(MOD_ID)
                .orElseThrow(() -> new AssertionError("[ui] " + MOD_ID + " is not loaded (-PmodUnderTest)"));
        for (Path root : mod.getRootPaths()) {
            Path base = root.resolve("com/killer560/hub");
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(base)) {
                walk.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    String fq = rel.substring(0, rel.length() - ".class".length()).replace('/', '.');
                    if (fq.contains("$")) {
                        return;
                    }
                    String simple = fq.substring(fq.lastIndexOf('.') + 1);
                    if (simpleName.test(simple)) {
                        out.add(fq);
                    }
                });
            } catch (Exception e) {
                throw new AssertionError("[ui] could not list the mod jar at " + root + ": " + e, e);
            }
        }
        return new ArrayList<>(out);
    }

    /** Load without initialising (so listing a class never runs its static init). Null if it cannot be loaded. */
    static Class<?> peek(String fq) {
        try {
            return Class.forName(fq, false, JarIndex.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    /** Concrete, non-mixin subclasses of {@code type} in the jar, among classes whose simple name passes. */
    static List<Class<?>> concreteSubclasses(Class<?> type, Predicate<String> simpleName) {
        List<Class<?>> out = new ArrayList<>();
        for (String fq : classNames(simpleName)) {
            if (fq.contains(".mixin.")) {
                continue;
            }
            Class<?> c = peek(fq);
            if (c != null && type.isAssignableFrom(c) && !Modifier.isAbstract(c.getModifiers()) && !c.isInterface()) {
                out.add(c);
            }
        }
        return out;
    }

    /** "gui.ModScreen" for com.killer560.hub.gui.ModScreen. */
    static String rel(String fq) {
        return fq.startsWith(ROOT) ? fq.substring(ROOT.length()) : fq;
    }
}

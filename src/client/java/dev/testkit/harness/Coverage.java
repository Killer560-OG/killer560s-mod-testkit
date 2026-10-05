package dev.testkit.harness;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which parts of the mod a run actually exercised, written to {@code coverage.md} beside the report.
 *
 * <p>A case calls {@link #mark} for each mod class it drove or asserted on ({@code Mod} does it automatically for
 * every class it reflects into). At the end the mod jar itself is listed: every top-level package under
 * {@code com.killer560.hub} and every {@code *Config} class (about 150), each shown touched or untouched. The
 * listing comes from the loaded jar, not from a list kept here, so a new feature shows up as untouched on its own.
 *
 * <p>Marked means "a case reached it", never "it works" - the case's own verdict says that.
 */
public final class Coverage {

    public static final String MOD_ID = "killer560smod";
    public static final String ROOT = "com.killer560.hub.";

    /** package -> classes marked in it (simple names). */
    private static final Map<String, Set<String>> MARKS = new ConcurrentHashMap<>();
    /** class -> cases that marked it. */
    private static final Map<String, Set<String>> BY_CASE = new ConcurrentHashMap<>();
    private static volatile String currentCase = "";

    private Coverage() {
    }

    /** The case marks from here on are attributed to (Session/Scenario set it). */
    public static void currentCase(String name) {
        currentCase = name == null ? "" : name;
    }

    /** Mark a mod class by package ("secrets") and simple name ("DungeonState"). */
    public static void mark(String pkg, String cls) {
        MARKS.computeIfAbsent(pkg, k -> ConcurrentHashMap.newKeySet()).add(cls);
        BY_CASE.computeIfAbsent(pkg + "." + cls, k -> ConcurrentHashMap.newKeySet()).add(currentCase);
    }

    /** Mark by fully-qualified (or {@code com.killer560.hub}-relative) class name; nested classes count as the outer. */
    public static void markClass(String className) {
        String rel = className.startsWith(ROOT) ? className.substring(ROOT.length()) : className;
        int dollar = rel.indexOf('$');
        if (dollar >= 0) {
            rel = rel.substring(0, dollar);
        }
        int dot = rel.lastIndexOf('.');
        if (dot <= 0) {
            mark("(root)", rel);
            return;
        }
        // Top-level package only: "gui.tab.HomeMainTab" counts for gui.
        String pkg = rel.substring(0, rel.indexOf('.'));
        mark(pkg, rel.substring(dot + 1));
    }

    public static Map<String, Set<String>> marks() {
        Map<String, Set<String>> copy = new TreeMap<>();
        MARKS.forEach((k, v) -> copy.put(k, new TreeSet<>(v)));
        return copy;
    }

    static void write(Path file) {
        Set<String> packages = new TreeSet<>();
        Set<String> configs = new TreeSet<>();
        Optional<ModContainer> mod = FabricLoader.getInstance().getModContainer(MOD_ID);
        if (mod.isPresent()) {
            for (Path root : mod.get().getRootPaths()) {
                Path hub = root.resolve("com/killer560/hub");
                if (!Files.isDirectory(hub)) {
                    continue;
                }
                try (var walk = Files.walk(hub)) {
                    walk.forEach(p -> {
                        String rel = hub.relativize(p).toString().replace('\\', '/');
                        if (rel.isEmpty()) {
                            return;
                        }
                        if (Files.isDirectory(p) && !rel.contains("/") && !rel.equals("mixin")) {
                            packages.add(rel);
                        } else if (rel.endsWith("Config.class") && !rel.contains("$") && !rel.contains("/mixin/")) {
                            configs.add(rel.substring(0, rel.length() - ".class".length()).replace('/', '.'));
                        }
                    });
                } catch (IOException e) {
                    System.out.println("[coverage] could not list " + hub + ": " + e);
                }
            }
        }
        Map<String, Set<String>> marks = marks();
        StringBuilder md = new StringBuilder("# coverage\n\n");
        if (mod.isEmpty()) {
            md.append("The mod (").append(MOD_ID).append(") was not loaded, so nothing can be listed.\n");
        }
        long touchedPkgs = packages.stream().filter(marks::containsKey).count();
        long touchedCfgs = configs.stream().filter(c -> {
            int dot = c.indexOf('.');
            String pkg = dot < 0 ? "(root)" : c.substring(0, dot);
            return marks.getOrDefault(pkg, Set.of()).contains(c.substring(c.lastIndexOf('.') + 1));
        }).count();
        md.append("Packages touched: ").append(touchedPkgs).append(" / ").append(packages.size()).append('\n');
        md.append("Config classes touched: ").append(touchedCfgs).append(" / ").append(configs.size())
                .append("\n\n");
        md.append("## Packages\n\n| package | touched | classes marked |\n|---|---|---|\n");
        for (String pkg : packages) {
            Set<String> classes = marks.getOrDefault(pkg, Set.of());
            md.append("| ").append(pkg).append(" | ").append(classes.isEmpty() ? "no" : "yes").append(" | ")
                    .append(String.join(", ", classes)).append(" |\n");
        }
        md.append("\n## Config classes\n\n| config | touched | by |\n|---|---|---|\n");
        for (String cfg : configs) {
            int dot = cfg.indexOf('.');
            String pkg = dot < 0 ? "(root)" : cfg.substring(0, dot);
            String simple = cfg.substring(cfg.lastIndexOf('.') + 1);
            boolean touched = marks.getOrDefault(pkg, Set.of()).contains(simple);
            Set<String> by = BY_CASE.getOrDefault(pkg + "." + simple, Set.of());
            md.append("| ").append(cfg).append(" | ").append(touched ? "yes" : "no").append(" | ")
                    .append(String.join(", ", new TreeSet<>(by))).append(" |\n");
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, md.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("[coverage] could not write " + file + ": " + e);
        }
    }
}

package dev.testkit.gametest.logic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.testkit.gametest.Fixtures;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * {@code testkit-logic/pattern-catalog.json}, written by {@code tools/extract-patterns.py}: every Pattern.compile in
 * the mod source with its class, field, file:line and regex text. Also where the mod source and sibling worktrees
 * are.
 */
final class Catalog {

    record Entry(String id, String cls, String field, Integer index, String file, int line, String regex,
                 String flags, String kind) {

        /** Reflection ref for {@link R#pattern}: null for local/dynamic sites. */
        String ref() {
            if (field == null || !(kind.equals("field") || kind.equals("element"))) {
                return null;
            }
            return index == null ? cls + "#" + field : cls + "#" + field + "[" + index + "]";
        }
    }

    final String modGitSha;
    final String generatedAt;
    final List<Entry> entries;
    final Path file;

    private Catalog(Path file, String sha, String at, List<Entry> entries) {
        this.file = file;
        this.modGitSha = sha;
        this.generatedAt = at;
        this.entries = entries;
    }

    /** The resources folder the gametest API reports, else the checkout's src/gametest/resources. */
    static Path resources() {
        String res = System.getProperty("fabric.client.gametest.testModResourcesPath");
        if (res != null) {
            return Path.of(res);
        }
        return Path.of(System.getProperty("testkit.checkout", "."), "src", "gametest", "resources");
    }

    static Catalog load() {
        Path p = resources().resolve("testkit-logic").resolve("pattern-catalog.json");
        if (!Files.isRegularFile(p)) {
            throw new AssertionError("[logic] no pattern catalog at " + p
                    + " - run: python tools/extract-patterns.py -PmodSource=C:/Users/Hunter/killer560s-mod");
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            throw new AssertionError("[logic] cannot read " + p + ": " + e, e);
        }
        List<Entry> out = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("patterns")) {
            JsonObject o = el.getAsJsonObject();
            out.add(new Entry(str(o, "id"), str(o, "cls"), str(o, "field"),
                    o.has("index") && !o.get("index").isJsonNull() ? o.get("index").getAsInt() : null,
                    str(o, "file"), o.get("line").getAsInt(), str(o, "regex"), str(o, "flags"), str(o, "kind")));
        }
        return new Catalog(p, str(root, "modGitSha"), str(root, "generatedAt"), out);
    }

    /** The mod checkout: -Dtestkit.modSource, $TESTKIT_MOD_SOURCE, else C:/Users/Hunter/killer560s-mod. */
    static Path modSource() {
        String p = System.getProperty("testkit.modSource");
        if (p == null || p.isBlank()) {
            p = System.getenv("TESTKIT_MOD_SOURCE");
        }
        if (p == null || p.isBlank()) {
            p = "C:/Users/Hunter/killer560s-mod";
        }
        return Path.of(p);
    }

    /** The 7-hex commit in the mod jar's path (".../main-8c43a6d/..."), or null. */
    static String jarSha() {
        String jars = System.getProperty("fabric.addMods", "");
        Matcher m = java.util.regex.Pattern.compile("-([0-9a-f]{7,40})[/\\\\]").matcher(jars);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Fixture roots in OTHER checkouts of this repo (WP worktrees under killer560s-mod-testkit-wt/, and the main
     * checkout), for a preview of fixtures not merged yet. Never includes this checkout.
     */
    static List<Path> siblingFixtureRoots() {
        List<Path> out = new ArrayList<>();
        Path self = Fixtures.root().toAbsolutePath().normalize();
        Path checkout = Path.of(System.getProperty("testkit.checkout", ".")).toAbsolutePath().normalize();
        List<Path> candidates = new ArrayList<>();
        Path parent = checkout.getParent();
        if (parent != null) {
            String base = checkout.getFileName().toString();
            Path wtDir = parent.getFileName().toString().equals("killer560s-mod-testkit-wt") ? parent
                    : parent.resolve("killer560s-mod-testkit-wt");
            Path main = wtDir.getParent() == null ? null : wtDir.getParent().resolve("killer560s-mod-testkit");
            if (main != null) {
                candidates.add(main);
            }
            if (Files.isDirectory(wtDir)) {
                try (var list = Files.list(wtDir)) {
                    list.sorted().forEach(candidates::add);
                } catch (IOException ignored) {
                    // preview only
                }
            }
            if (base.isEmpty()) {
                return out;
            }
        }
        for (Path c : candidates) {
            Path root = c.resolve("src").resolve("gametest").resolve("resources").resolve("testkit-fixtures")
                    .toAbsolutePath().normalize();
            if (!root.equals(self) && Files.isDirectory(root)) {
                out.add(root);
            }
        }
        return out;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }
}

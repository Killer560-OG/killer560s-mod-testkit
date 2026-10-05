package dev.testkit.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.testkit.gametest.mod.Mod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads and checks fixtures: {@code src/gametest/resources/testkit-fixtures/<area>/*.json}, schema in
 * {@code testkit-fixtures/SCHEMA.md} beside them.
 *
 * <pre>{@code
 * List<Fixtures.Fixture> tab = Fixtures.load("tab");          // every fixture under testkit-fixtures/tab/
 * Fixtures.Fixture f = Fixtures.byId("tab.dungeon-teammate-mage");
 * List<String> problems = Fixtures.checkPatterns(f);           // against the LOADED mod's Pattern constants
 * }</pre>
 *
 * <p>Files are read from the gametest resources folder the Fabric gametest API reports
 * ({@code fabric.client.gametest.testModResourcesPath}), falling back to {@code src/gametest/resources} under the
 * checkout. Frozen after WP1.
 */
public final class Fixtures {

    public static final Set<String> KINDS = Set.of("chat", "overlay", "title", "sidebar", "tab", "item", "menu",
            "stand", "map");

    /** One fixture. {@code json} is the whole object, for kind-specific fields. */
    public record Fixture(String id, String kind, JsonElement payload, String sourceFile, int sourceLine,
                          List<String> matches, List<String> mustNotMatch, String mode, boolean strip,
                          String verifiedBy, Path file, JsonObject json) {

        /** The texts patterns are checked against (see SCHEMA.md); empty for kinds with no text. */
        public List<String> texts() {
            List<String> out = new ArrayList<>();
            switch (kind) {
                case "chat", "overlay", "title" -> {
                    if (payload.isJsonPrimitive()) {
                        out.add(payload.getAsString());
                    }
                }
                case "sidebar" -> payload.getAsJsonObject().getAsJsonArray("lines").forEach(e -> out.add(e.getAsString()));
                case "tab" -> payload.getAsJsonObject().getAsJsonArray("entries").forEach(e -> out.add(
                        e.isJsonObject() ? e.getAsJsonObject().get("text").getAsString() : e.getAsString()));
                default -> {
                    // no text
                }
            }
            return out;
        }
    }

    private Fixtures() {
    }

    /** The fixtures root folder. */
    public static Path root() {
        String res = System.getProperty("fabric.client.gametest.testModResourcesPath");
        if (res != null) {
            return Path.of(res).resolve("testkit-fixtures");
        }
        String checkout = System.getProperty("testkit.checkout", ".");
        return Path.of(checkout, "src", "gametest", "resources", "testkit-fixtures");
    }

    /** Every fixture in every area. */
    public static List<Fixture> all() {
        return load("");
    }

    /** Every fixture under {@code testkit-fixtures/<area>/} (recursively); {@code ""} for all. */
    public static List<Fixture> load(String area) {
        Path dir = area.isEmpty() ? root() : root().resolve(area);
        List<Fixture> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted()::iterator) {
                if (p.toString().endsWith(".json") && !p.getParent().equals(root())) {
                    out.addAll(parse(p));
                }
            }
        } catch (IOException e) {
            throw new AssertionError("[fixtures] cannot list " + dir, e);
        }
        return out;
    }

    public static Fixture byId(String id) {
        for (Fixture f : all()) {
            if (f.id().equals(id)) {
                return f;
            }
        }
        throw new AssertionError("[fixtures] no fixture with id " + id + " under " + root());
    }

    /** Parse one file (a JSON array of fixture objects). Throws on malformed JSON, not on schema problems. */
    public static List<Fixture> parse(Path file) {
        JsonElement root;
        try {
            root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            throw new AssertionError("[fixtures] cannot read " + file + ": " + e, e);
        }
        if (!root.isJsonArray()) {
            throw new AssertionError("[fixtures] " + file + " must be a JSON array of fixtures");
        }
        List<Fixture> out = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray()) {
            JsonObject o = el.getAsJsonObject();
            JsonObject src = o.has("source") && o.get("source").isJsonObject() ? o.getAsJsonObject("source") : null;
            out.add(new Fixture(str(o, "id"), str(o, "kind"), o.get("payload"),
                    src == null ? null : str(src, "file"), src != null && src.has("line") ? src.get("line").getAsInt() : -1,
                    list(o, "matches"), list(o, "mustNotMatch"), o.has("mode") ? str(o, "mode") : "find",
                    !o.has("strip") || o.get("strip").getAsBoolean(), str(o, "verifiedBy"), file, o));
        }
        return out;
    }

    /** Schema problems for one fixture (empty when valid). Does not need the mod. */
    public static List<String> validate(Fixture f) {
        List<String> p = new ArrayList<>();
        String where = f.file().getFileName() + " " + (f.id() == null ? "(no id)" : f.id()) + ": ";
        if (f.id() == null || f.id().isBlank()) {
            p.add(where + "missing id");
        }
        if (f.kind() == null || !KINDS.contains(f.kind())) {
            p.add(where + "kind must be one of " + KINDS + ", got " + f.kind());
        }
        if (f.payload() == null || f.payload().isJsonNull()) {
            p.add(where + "missing payload");
        }
        if (f.sourceFile() == null || f.sourceLine() <= 0) {
            p.add(where + "source {file, line} is required (never invented)");
        }
        if (f.verifiedBy() == null || !(f.verifiedBy().equals("regex") || f.verifiedBy().startsWith("capture:"))) {
            p.add(where + "verifiedBy must be \"regex\" or \"capture:<ref>\"");
        }
        if (!f.mode().equals("find") && !f.mode().equals("matches")) {
            p.add(where + "mode must be find or matches");
        }
        boolean hasText = Set.of("chat", "overlay", "title", "sidebar", "tab").contains(f.kind());
        if (!hasText && (!f.matches().isEmpty() || !f.mustNotMatch().isEmpty())) {
            p.add(where + "kind " + f.kind() + " has no text, so it cannot list matches/mustNotMatch");
        }
        return p;
    }

    /** Duplicate ids across a list of fixtures. */
    public static List<String> duplicateIds(List<Fixture> all) {
        Set<String> seen = new HashSet<>();
        List<String> dups = new ArrayList<>();
        for (Fixture f : all) {
            if (f.id() != null && !seen.add(f.id())) {
                dups.add(f.id());
            }
        }
        return dups;
    }

    /**
     * Check {@code matches}/{@code mustNotMatch} against the LOADED mod's Pattern constants. Returns problems (empty
     * when it agrees). A pattern that cannot be found is a problem too, never a pass.
     */
    public static List<String> checkPatterns(Fixture f) {
        List<String> p = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (String t : f.texts()) {
            String s = f.strip() ? stripFormatting(t) : t;
            texts.add(s == null ? "" : s.trim());
        }
        for (String ref : f.matches()) {
            Pattern pattern = resolve(ref, p, f);
            if (pattern != null && texts.stream().noneMatch(t -> hit(pattern, t, f.mode()))) {
                p.add(f.id() + ": should match " + ref + " but none of " + texts + " does");
            }
        }
        for (String ref : f.mustNotMatch()) {
            Pattern pattern = resolve(ref, p, f);
            if (pattern != null) {
                for (String t : texts) {
                    if (hit(pattern, t, f.mode())) {
                        p.add(f.id() + ": must NOT match " + ref + " but \"" + t + "\" does");
                    }
                }
            }
        }
        return p;
    }

    /** Whether the fixture's source file exists under a checkout of the mod (WP5 passes -PmodSource). */
    public static boolean sourceExists(Fixture f, Path modSourceRoot) {
        return f.sourceFile() != null && Files.isRegularFile(modSourceRoot.resolve(f.sourceFile()));
    }

    /** Vanilla-equivalent of {@code ChatFormatting.stripFormatting}: drops a section sign and the char after it. */
    public static String stripFormatting(String s) {
        return s == null ? null : s.replaceAll("(?i)§[0-9A-FK-ORX]", "");
    }

    private static boolean hit(Pattern pattern, String text, String mode) {
        Matcher m = pattern.matcher(text);
        return "matches".equals(mode) ? m.matches() : m.find();
    }

    private static Pattern resolve(String ref, List<String> problems, Fixture f) {
        int hash = ref.indexOf('#');
        if (hash <= 0) {
            problems.add(f.id() + ": bad pattern ref " + ref + " (want Class#FIELD)");
            return null;
        }
        try {
            return Mod.pattern(ref.substring(0, hash), ref.substring(hash + 1));
        } catch (AssertionError e) {
            problems.add(f.id() + ": " + e.getMessage());
            return null;
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static List<String> list(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            JsonArray a = o.getAsJsonArray(key);
            a.forEach(e -> out.add(e.getAsString()));
        }
        return out;
    }
}

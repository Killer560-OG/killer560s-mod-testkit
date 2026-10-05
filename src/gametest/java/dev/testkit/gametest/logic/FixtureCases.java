package dev.testkit.gametest.logic;

import com.google.gson.JsonElement;

import dev.testkit.gametest.Fixtures;
import dev.testkit.harness.Report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 350 catalog vs live jar, 351 fixture integrity, 352 pattern coverage. */
final class FixtureCases {

    private FixtureCases() {
    }

    // ---- 350 ----------------------------------------------------------------------------------------------

    /**
     * The catalog (from the mod SOURCE) agrees with the loaded JAR: every static Pattern field and list element it
     * names exists and has the same regex text, and every static Pattern field in a catalogued class is catalogued
     * (so the extractor is not silently missing declarations).
     */
    static void catalog(LogicCase c) {
        Catalog cat = Catalog.load();
        c.check("catalog has entries", cat.entries.size() > 300, cat.entries.size() + " entries");
        String jar = Catalog.jarSha();
        c.note("catalog: " + cat.entries.size() + " Pattern.compile sites at mod " + cat.modGitSha + " ("
                + cat.generatedAt + "); jar under test built from " + (jar == null ? "?" : jar));
        if (jar != null && cat.modGitSha != null && !cat.modGitSha.startsWith(jar)) {
            c.note("catalog commit " + cat.modGitSha + " is not the jar's " + jar
                    + " - differences below may be source moving on, not the jar");
        }
        int compared = 0;
        Map<String, Set<String>> byClass = new HashMap<>();
        for (Catalog.Entry e : cat.entries) {
            String ref = e.ref();
            if (ref == null) {
                continue;
            }
            byClass.computeIfAbsent(e.cls(), k -> new LinkedHashSet<>()).add(e.field());
            Pattern live;
            try {
                live = R.pattern(ref);
            } catch (AssertionError err) {
                c.check("live " + ref, false, err.getMessage());
                continue;
            }
            if (e.regex() != null) {
                compared++;
                c.check("regex " + ref + " (" + e.file() + ":" + e.line() + ")", live.pattern().equals(e.regex()),
                        "source /" + e.regex() + "/ vs jar /" + live.pattern() + "/");
            }
            if (e.flags() != null && e.flags().contains("CASE_INSENSITIVE")) {
                c.check("flags " + ref, (live.flags() & Pattern.CASE_INSENSITIVE) != 0, "flags " + live.flags());
            }
        }
        // The other direction: a static Pattern field the jar has that the catalog does not.
        int missing = 0;
        for (Map.Entry<String, Set<String>> cls : byClass.entrySet()) {
            Class<?> k = dev.testkit.gametest.mod.Mod.cls(cls.getKey());
            for (var f : k.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == Pattern.class
                        && !cls.getValue().contains(f.getName())) {
                    missing++;
                    c.check("catalogued " + cls.getKey() + "#" + f.getName(), false,
                            "the jar has this static Pattern but the catalog does not");
                }
            }
        }
        c.note("compared " + compared + " regex texts with the jar; " + missing + " uncatalogued static Pattern fields");
        c.check("compared some regexes", compared > 300, compared + " compared");
    }

    // ---- 351 ----------------------------------------------------------------------------------------------

    /** Schema, unique ids, source file+line exist in the mod, matches/mustNotMatch/gates agree with the jar. */
    static void integrity(LogicCase c) {
        Path mod = Catalog.modSource();
        c.check("mod source at " + mod, Files.isDirectory(mod.resolve("src/main/java/com/killer560/hub")),
                "set -Dtestkit.modSource or TESTKIT_MOD_SOURCE");
        List<Fixtures.Fixture> all = Fixtures.all();
        c.check("fixtures found under " + Fixtures.root(), all.size() >= 20, all.size() + " fixture(s)");
        Map<String, Integer> perFile = new TreeMap<>();
        for (Fixtures.Fixture f : all) {
            perFile.merge(Fixtures.root().relativize(f.file()).toString().replace('\\', '/'), 1, Integer::sum);
        }
        c.note("fixtures in this checkout: " + all.size() + " " + perFile);
        for (String dup : Fixtures.duplicateIds(all)) {
            c.check("unique id " + dup, false, "duplicate id");
        }
        Map<String, List<String>> sourceCache = new HashMap<>();
        for (Fixtures.Fixture f : all) {
            checkOne(c, f, mod, sourceCache, true);
        }
        // Preview of other worktrees' fixtures (not merged yet): problems are notes, never this case's failure.
        int preview = 0;
        List<String> previewProblems = new ArrayList<>();
        for (Path root : Catalog.siblingFixtureRoots()) {
            for (Fixtures.Fixture f : parseTree(root, previewProblems)) {
                preview++;
                LogicCase probe = LogicCase.collector("preview");
                checkOne(probe, f, mod, sourceCache, false);
                probe.failures().forEach(p -> previewProblems.add(root + ": " + p));
            }
        }
        c.note("sibling worktrees: " + preview + " fixture(s) previewed, " + previewProblems.size() + " problem(s)");
        previewProblems.stream().limit(40).forEach(p -> c.note("preview: " + p));
    }

    private static void checkOne(LogicCase c, Fixtures.Fixture f, Path mod, Map<String, List<String>> cache,
                                 boolean strictSource) {
        String id = f.id() == null ? f.file().getFileName() + "(no id)" : f.id();
        List<String> schema = Fixtures.validate(f);
        c.check("schema " + id, schema.isEmpty(), String.join("; ", schema));
        if (f.sourceFile() != null) {
            boolean exists = Fixtures.sourceExists(f, mod);
            c.check("source exists " + id, exists, f.sourceFile());
            if (exists) {
                List<String> lines = cache.computeIfAbsent(f.sourceFile(), k -> readLines(mod.resolve(k)));
                boolean inRange = f.sourceLine() >= 1 && f.sourceLine() <= lines.size();
                c.check("source line in range " + id, inRange, f.sourceFile() + ":" + f.sourceLine() + " of "
                        + lines.size());
                if (inRange) {
                    String text = lines.get(f.sourceLine() - 1).trim();
                    boolean substantive = text.length() > 3 && !text.matches("[{}();,\\s]*");
                    c.check("source line is substantive " + id, substantive,
                            f.sourceFile() + ":" + f.sourceLine() + " reads \"" + text + "\"");
                    c.check("source line relates to the fixture " + id, relates(text, f),
                            f.sourceFile() + ":" + f.sourceLine() + " reads \"" + text
                                    + "\" - no word of the payload, no cited field, no Pattern.compile (line moved?)");
                }
            }
        }
        List<String> problems = Fixtures.checkPatterns(f);
        c.check("patterns " + id, problems.isEmpty(), String.join("; ", problems));
        for (String ref : gates(f)) {
            Pattern p;
            try {
                p = R.pattern(ref);
            } catch (AssertionError e) {
                c.check("gate resolves " + id + " " + ref, false, e.getMessage());
                continue;
            }
            List<String> texts = texts(f);
            c.check("gate " + id + " " + ref, texts.stream().anyMatch(t -> hit(p, t, f.mode())),
                    "none of " + texts + " " + f.mode() + " /" + p.pattern() + "/");
        }
    }

    // ---- 352 ----------------------------------------------------------------------------------------------

    /**
     * Pattern coverage: how many of the catalog's static Pattern fields a fixture cites. The figure is reported, not
     * gated (WP2-WP7 own most fixtures); what IS gated is that every action-gating pattern has a positive fixture
     * (the hostile sweep in 353 relies on one per pattern as its positive control).
     */
    static void coverage(LogicCase c) {
        Catalog cat = Catalog.load();
        Set<String> cited = new LinkedHashSet<>();
        Set<String> positive = new LinkedHashSet<>();
        for (Fixtures.Fixture f : Fixtures.all()) {
            cited.addAll(f.matches());
            cited.addAll(f.mustNotMatch());
            cited.addAll(gates(f));
            positive.addAll(f.matches());
            positive.addAll(gates(f));
        }
        Set<String> previewCited = new LinkedHashSet<>(cited);
        for (Path root : Catalog.siblingFixtureRoots()) {
            for (Fixtures.Fixture f : parseTree(root, new ArrayList<>())) {
                previewCited.addAll(f.matches());
                previewCited.addAll(f.mustNotMatch());
                previewCited.addAll(gates(f));
            }
        }
        int statics = 0;
        int covered = 0;
        int coveredWithPreview = 0;
        Map<String, int[]> perPkg = new TreeMap<>();
        Map<String, List<String>> uncovered = new TreeMap<>();
        for (Catalog.Entry e : cat.entries) {
            String ref = e.ref();
            if (ref == null) {
                continue;
            }
            statics++;
            String pkg = e.cls().contains(".") ? e.cls().substring(0, e.cls().indexOf('.')) : "(root)";
            int[] row = perPkg.computeIfAbsent(pkg, k -> new int[2]);
            row[0]++;
            if (cited.contains(ref)) {
                covered++;
                row[1]++;
            } else {
                uncovered.computeIfAbsent(pkg, k -> new ArrayList<>()).add(ref.substring(ref.indexOf('#') + 1)
                        + " (" + e.file().substring(e.file().lastIndexOf('/') + 1) + ":" + e.line() + ")");
            }
            if (previewCited.contains(ref)) {
                coveredWithPreview++;
            }
        }
        double pct = 100.0 * covered / Math.max(1, statics);
        c.note(String.format(Locale.ROOT, "pattern coverage: %d / %d static Pattern fields and list elements cited "
                + "by a fixture in this checkout (%.1f%%); %d (%.1f%%) counting sibling worktrees", covered, statics,
                pct, coveredWithPreview, 100.0 * coveredWithPreview / Math.max(1, statics)));
        c.check("statics counted", statics > 300, statics + "");
        // Every action-gating pattern (the hostile sweep's list) must have a positive fixture.
        for (String ref : HostileCases.actionGating()) {
            c.check("positive fixture for action-gating " + ref, positive.contains(ref), "no fixture matches it");
        }
        StringBuilder md = new StringBuilder("# pattern coverage\n\n");
        md.append(String.format(Locale.ROOT, "%d / %d static Pattern fields and list elements cited by a fixture "
                + "(%.1f%%), catalog at mod %s. \"Cited\" means a fixture names it; it proves agreement with the regex, "
                + "not Hypixel truth.\n\n| package | cited | total |\n|---|---|---|\n", covered, statics, pct,
                cat.modGitSha));
        perPkg.forEach((k, v) -> md.append("| ").append(k).append(" | ").append(v[1]).append(" | ").append(v[0])
                .append(" |\n"));
        md.append("\n## not cited\n\n");
        uncovered.forEach((k, v) -> md.append("- **").append(k).append("**: ").append(String.join(", ", v))
                .append('\n'));
        try {
            Files.createDirectories(Report.dir());
            Files.writeString(Report.dir().resolve("pattern-coverage.md"), md.toString(), StandardCharsets.UTF_8);
            c.note("wrote " + Report.dir().resolve("pattern-coverage.md"));
        } catch (IOException e) {
            c.note("could not write pattern-coverage.md: " + e);
        }
    }

    // ---- shared -----------------------------------------------------------------------------------------------

    /** The WP5 extension field {@code gates}: refs (including {@code Class#FIELD[i]} list elements). */
    static List<String> gates(Fixtures.Fixture f) {
        List<String> out = new ArrayList<>();
        if (f.json().has("gates") && f.json().get("gates").isJsonArray()) {
            for (JsonElement e : f.json().getAsJsonArray("gates")) {
                out.add(e.getAsString());
            }
        }
        return out;
    }

    /** The texts patterns see: stripped (unless strip:false) and trimmed, as Fixtures.checkPatterns does. */
    static List<String> texts(Fixtures.Fixture f) {
        List<String> out = new ArrayList<>();
        for (String t : f.texts()) {
            String s = f.strip() ? Fixtures.stripFormatting(t) : t;
            out.add(s == null ? "" : s.trim());
        }
        return out;
    }

    static boolean hit(Pattern p, CharSequence text, String mode) {
        Matcher m = p.matcher(text);
        return "matches".equals(mode) ? m.matches() : m.find();
    }

    static List<Fixtures.Fixture> parseTree(Path root, List<String> problems) {
        List<Fixtures.Fixture> out = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk.sorted()::iterator) {
                if (p.toString().endsWith(".json") && !p.getParent().equals(root)) {
                    try {
                        out.addAll(Fixtures.parse(p));
                    } catch (AssertionError e) {
                        problems.add(e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            problems.add("cannot list " + root + ": " + e);
        }
        return out;
    }

    /**
     * Whether a cited source line plausibly is where the fixture came from: it compiles a pattern, names a field the
     * fixture cites, or shares a word (3+ letters) with the payload. Catches a citation left behind when the mod
     * source moved, which "the line exists" never would.
     */
    static boolean relates(String line, Fixtures.Fixture f) {
        if (line.contains("Pattern.compile")) {
            return true;
        }
        List<String> refs = new ArrayList<>(f.matches());
        refs.addAll(f.mustNotMatch());
        refs.addAll(gates(f));
        for (String ref : refs) {
            String field = ref.substring(ref.indexOf('#') + 1).replaceAll("\\[\\d+]$", "");
            if (line.contains(field)) {
                return true;
            }
        }
        String lower = line.toLowerCase(Locale.ROOT);
        for (String t : texts(f)) {
            for (String word : t.split("[^A-Za-z]+")) {
                if (word.length() >= 3 && lower.contains(word.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return f.texts().isEmpty();
    }

    private static List<String> readLines(Path p) {
        try {
            return Files.readAllLines(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
    }
}

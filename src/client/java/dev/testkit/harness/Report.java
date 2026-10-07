package dev.testkit.harness;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The run's report, written to {@code build/testkit-report/} (Gradle sets {@code -Dtestkit.reportDir} and wipes it
 * at the start of every run):
 *
 * <pre>
 * summary.md      one row per case: status, seconds, anticheat verdict, the one line that decided it
 * summary.json    the same rows, machine-readable, plus run metadata (suite, filter, mod jar, ports)
 * cases/NAME.log  per case: the verdict, its detail, notes, the server console slice and the client log slice
 * screens/NAME-N.png  screenshots a case took (Session takes one automatically when a case fails)
 * coverage.md     which mod packages and *Config classes a case marked (see Coverage)
 * </pre>
 *
 * <p>Rows come from {@code Scenario.run} (one per scenario) and {@code Session.test} (one per case).
 * {@link SuiteVerdict#finish} calls {@link #finish} at the end of the run. Everything here swallows its own IO
 * errors: a report that cannot be written must never be the reason a run fails.
 *
 * <p>Frozen after WP1.
 */
public final class Report {

    /** PASS, FAIL, SKIP, or FLAGGED (a case that expected flags and recorded them). */
    public record Row(String name, String status, double seconds, String anticheat, String detail) {
    }

    private static final List<Row> ROWS = new ArrayList<>();
    private static final Map<String, Long> STARTED = new LinkedHashMap<>();
    private static final Map<String, List<String>> NOTES = new LinkedHashMap<>();
    private static final Map<String, Integer> SHOTS = new LinkedHashMap<>();

    private Report() {
    }

    public static Path dir() {
        return Path.of(System.getProperty("testkit.reportDir", "testkit-report"));
    }

    /** Safe as a file name on Windows. */
    public static String fileName(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public static synchronized void caseStarted(String name) {
        STARTED.put(name, System.nanoTime());
    }

    /** Whether a row was already written for {@code name} (so a later generic failure does not add a second). */
    public static synchronized boolean recorded(String name) {
        for (Row r : ROWS) {
            if (r.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** A line for this case's log and its summary row - e.g. "health 1234/2345 read from overlay chat". */
    public static synchronized void note(String name, String text) {
        NOTES.computeIfAbsent(name, k -> new ArrayList<>()).add(text);
        System.out.println("[" + name + "] " + text);
    }

    /**
     * Record one case's verdict and write {@code cases/<name>.log}.
     *
     * @param serverLines the server console since the case's mark (may be empty)
     * @param clientLines the mod/chat log since the case's mark (may be empty)
     */
    public static synchronized void caseFinished(String name, String status, String anticheat, String detail,
                                                 List<String> serverLines, List<String> clientLines) {
        Long start = STARTED.remove(name);
        double seconds = start == null ? 0 : (System.nanoTime() - start) / 1e9;
        String oneLine = detail == null ? "" : detail.lines().findFirst().orElse("");
        ROWS.removeIf(r -> r.name().equals(name));
        ROWS.add(new Row(name, status, seconds, anticheat == null ? "" : anticheat, oneLine));
        StringBuilder log = new StringBuilder();
        log.append("case:      ").append(name).append('\n');
        log.append("status:    ").append(status).append('\n');
        log.append("seconds:   ").append(String.format(java.util.Locale.ROOT, "%.1f", seconds)).append('\n');
        log.append("anticheat: ").append(anticheat == null ? "" : anticheat).append('\n');
        log.append("detail:    ").append(detail == null ? "" : detail).append('\n');
        for (String n : NOTES.getOrDefault(name, List.of())) {
            log.append("note:      ").append(n).append('\n');
        }
        log.append("\n--- server console since the case's mark (").append(serverLines.size()).append(" lines) ---\n");
        serverLines.forEach(l -> log.append(l).append('\n'));
        log.append("\n--- client: mod log and chat since the case's mark (").append(clientLines.size())
                .append(" lines) ---\n");
        clientLines.forEach(l -> log.append(l).append('\n'));
        write(dir().resolve("cases").resolve(fileName(name) + ".log"), log.toString());
    }

    /** Copy a screenshot the case took into {@code screens/<name>-N.png}. Returns the copy, or null. */
    public static synchronized Path screenshot(String name, Path taken) {
        if (taken == null || !Files.isRegularFile(taken)) {
            return null;
        }
        int n = SHOTS.merge(name, 1, Integer::sum);
        Path target = dir().resolve("screens").resolve(fileName(name) + "-" + n + ".png");
        try {
            Files.createDirectories(target.getParent());
            Files.copy(taken, target, StandardCopyOption.REPLACE_EXISTING);
            note(name, "screenshot " + target.getFileName());
            return target;
        } catch (IOException e) {
            System.out.println("[report] could not copy screenshot " + taken + ": " + e);
            return null;
        }
    }

    public static synchronized List<Row> rows() {
        return new ArrayList<>(ROWS);
    }

    /** Write summary.md, summary.json and coverage.md. Called once, by {@link SuiteVerdict#finish}. */
    public static synchronized void finish(String suiteSummary) {
        int pass = 0;
        int fail = 0;
        int other = 0;
        int skipped = 0;
        for (Row r : ROWS) {
            switch (r.status()) {
                case "PASS", "FLAGGED" -> pass++;
                case "FAIL" -> fail++;
                default -> other++;
            }
            if (r.status().equals("SKIP")) {
                skipped++;
            }
        }
        String suite = System.getProperty("testkit.suite", "");
        String filter = System.getProperty("testkit.scenario", "");
        StringBuilder md = new StringBuilder("# testkit report\n\n");
        md.append("- when: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append('\n');
        md.append("- suite: ").append(suite.isEmpty() ? "(none)" : suite).append('\n');
        md.append("- filter: ").append(filter.isEmpty() ? "(all)" : "`" + filter + "`").append('\n');
        md.append("- mod under test: `").append(System.getProperty("fabric.addMods", "(none)")).append("`\n");
        md.append("- checkout: `").append(System.getProperty("testkit.checkout", "?")).append("`, hx port ")
                .append(System.getProperty("testkit.hx.port", "?")).append('\n');
        md.append("- anticheat: ").append(Boolean.getBoolean("testkit.nogrim") ? "REMOVED (-Pnogrim)" : "GrimAC")
                .append('\n');
        md.append("- totals: ").append(pass).append(" passed, ").append(fail).append(" failed, ").append(other)
                .append(" other").append(skipped > 0 ? " (" + skipped + " skipped)" : "").append("\n\n");
        md.append("| case | status | s | anticheat | detail |\n|---|---|---|---|---|\n");
        for (Row r : ROWS) {
            md.append("| [").append(r.name()).append("](cases/").append(fileName(r.name())).append(".log) | ")
                    .append(r.status()).append(" | ")
                    .append(String.format(java.util.Locale.ROOT, "%.1f", r.seconds())).append(" | ")
                    .append(cell(r.anticheat())).append(" | ").append(cell(r.detail())).append(" |\n");
        }
        md.append("\n```\n").append(suiteSummary).append("\n```\n");
        write(dir().resolve("summary.md"), md.toString());

        JsonObject json = new JsonObject();
        json.addProperty("suite", suite);
        json.addProperty("filter", filter);
        json.addProperty("modUnderTest", System.getProperty("fabric.addMods", ""));
        json.addProperty("checkout", System.getProperty("testkit.checkout", ""));
        json.addProperty("hxPort", System.getProperty("testkit.hx.port", ""));
        json.addProperty("nogrim", Boolean.getBoolean("testkit.nogrim"));
        json.addProperty("passed", pass);
        json.addProperty("failed", fail);
        JsonArray rows = new JsonArray();
        for (Row r : ROWS) {
            JsonObject o = new JsonObject();
            o.addProperty("name", r.name());
            o.addProperty("status", r.status());
            o.addProperty("seconds", r.seconds());
            o.addProperty("anticheat", r.anticheat());
            o.addProperty("detail", r.detail());
            JsonArray notes = new JsonArray();
            NOTES.getOrDefault(r.name(), List.of()).forEach(notes::add);
            o.add("notes", notes);
            rows.add(o);
        }
        json.add("cases", rows);
        write(dir().resolve("summary.json"), new GsonBuilder().setPrettyPrinting().create().toJson(json));
        Coverage.write(dir().resolve("coverage.md"));
        System.out.println("[report] " + ROWS.size() + " row(s) written to " + dir().toAbsolutePath());
    }

    private static String cell(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    private static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("[report] could not write " + file + ": " + e);
        }
    }
}

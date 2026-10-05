package dev.testkit.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * List mode ({@code -PlistScenarios=<file>}): the client starts, every test class runs its filter logic, and
 * instead of running anything each scenario and Session case reports its NAME and whether the filter selects it.
 * No server is started and no world is opened. The result is written as a TSV for {@code run-sharded.ps1}.
 *
 * <p>Columns: {@code name, state, unit, kind}. {@code state} is {@code all} (selected), {@code part} (a Session
 * that only some of its cases select) or {@code no}. {@code unit} is the test-class run the name belongs to (one
 * {@code runTest} call, numbered in run order): everything in a unit may share a world, a room or a session, so a
 * sharder keeps a unit together. {@code kind} is {@code scenario}, {@code session} or {@code case}.
 *
 * <p>Because the harness itself answers "is this selected", the list cannot disagree with a real run about what a
 * filter means; the sharder never re-implements the matching.
 */
public final class ScenarioList {

    private record Entry(String name, String state, int unit, String kind, String parent) {
    }

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private static int unit = 0;

    private ScenarioList() {
    }

    public static boolean active() {
        String f = System.getProperty("testkit.listFile", "");
        return !f.isBlank();
    }

    /** A new test class run starts (called before each {@code runTest}). */
    public static synchronized void nextUnit() {
        unit++;
    }

    public static synchronized void record(String name, String state, String kind) {
        record(name, state, kind, "");
    }

    /** @param parent for a {@code case}: the session it belongs to */
    public static synchronized void record(String name, String state, String kind, String parent) {
        Entry old = ENTRIES.get(name);
        // First sight wins, except that a later "selected" upgrades an unselected record of the same name.
        if (old == null || (old.state().equals("no") && !state.equals("no"))) {
            ENTRIES.put(name, new Entry(name, state, unit, kind, parent));
        }
    }

    /** Write the list. Called once, from {@link SuiteVerdict#finish}. */
    public static synchronized void write() {
        StringBuilder sb = new StringBuilder("# name\tstate\tunit\tkind\tparent\n");
        for (Entry e : ENTRIES.values()) {
            sb.append(e.name()).append('\t').append(e.state()).append('\t').append(e.unit()).append('\t')
                    .append(e.kind()).append('\t').append(e.parent()).append('\n');
        }
        Path file = Path.of(System.getProperty("testkit.listFile"));
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            System.out.println("[list] " + ENTRIES.size() + " name(s) written to " + file);
        } catch (IOException e) {
            System.out.println("[list] could not write " + file + ": " + e);
        }
    }
}

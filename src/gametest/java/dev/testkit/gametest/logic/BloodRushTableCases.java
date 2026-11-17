package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

/**
 * 1116-logic-bloodrush-table: Blood Rush's boxes and role split for every room of the shared table equal an independent
 * implementation of the table's rules. The fixture (testkit-fixtures/br-table-fixture.txt.gz) is written straight from the
 * table's source files by the mod's tools/bloodrush/gen-br-fixture.py, not from the jar's br-data.json, so a bug in the
 * jar's generator or loader shows up as a difference. Lines: B (a box, in table order), P (the plan for a room entered at a
 * door, per team size, normal and master, killer and door runner), M (a miniboss room).
 */
public final class BloodRushTableCases {

    static final String FIXTURE = "/testkit-fixtures/br-table-fixture.txt.gz";
    private static final String FEATURE = "bloodrush.BloodRushHelperFeature";

    private BloodRushTableCases() {
    }

    /** One fixture file's lines. */
    public static List<String> fixture() throws Exception {
        List<String> out = new ArrayList<>();
        try (InputStream in = BloodRushTableCases.class.getResourceAsStream(FIXTURE)) {
            if (in == null) {
                throw new AssertionError("fixture missing: " + FIXTURE);
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) {
                    if (!l.isBlank()) {
                        out.add(l);
                    }
                }
            }
        }
        return out;
    }

    /** "roles|stacks" with stacks collapsed so "no stacks" reads the same however the jar spells it. */
    private static String norm(String s) {
        int bar = s.indexOf('|');
        if (bar < 0) {
            return s;
        }
        String stacks = s.substring(bar + 1);
        return s.substring(0, bar) + "|" + (stacks.replace(";", "").replace(",", "").isEmpty() ? "" : stacks);
    }

    static void table(LogicCase c) throws Exception {
        List<String> lines = fixture();
        Map<String, List<String>> boxes = new LinkedHashMap<>();
        int plans = 0;
        int planned = 0;
        int mismatchPlans = 0;
        List<String> bad = new ArrayList<>();
        for (String l : lines) {
            String[] f = l.split("\\|", 10);
            if (f[0].equals("B")) {
                boxes.computeIfAbsent(f[1], k -> new ArrayList<>()).add(f[3]);
            } else if (f[0].equals("P")) {
                plans++;
                int wx = f[6].equals("-") ? Integer.MIN_VALUE : Integer.parseInt(f[6]);
                int wz = f[7].equals("-") ? 0 : Integer.parseInt(f[7]);
                String got = (String) Mod.staticCall(FEATURE, "planForTest", f[1], Integer.parseInt(f[4]), Integer.parseInt(f[5]),
                        wx, wz, Integer.parseInt(f[8]), f[2].equals("1"), f[3].equals("1"));
                String want = f[9];
                if (!want.equals("none")) {
                    planned++;
                }
                if (!norm(got).equals(norm(want))) {
                    mismatchPlans++;
                    if (bad.size() < 6) {
                        bad.add(String.join("|", java.util.Arrays.copyOf(f, 9)) + " want " + want + " got " + got);
                    }
                }
            }
        }
        c.check("the fixture covers 80+ rooms with boxes", boxes.size() >= 80, boxes.size() + " room(s)");
        c.check("the fixture has plans with a real answer to compare (the feature acted)", planned >= 500, planned + " of " + plans);
        int mismatchBoxes = 0;
        for (Map.Entry<String, List<String>> e : boxes.entrySet()) {
            String got = (String) Mod.staticCall(FEATURE, "tableBoxesForTest", e.getKey());
            String want = String.join(";", e.getValue());
            if (!got.equals(want)) {
                mismatchBoxes++;
                if (bad.size() < 6) {
                    bad.add("boxes of " + e.getKey() + " want " + want + " got " + got);
                }
            }
        }
        c.check("every room's boxes equal the independent table", mismatchBoxes == 0, mismatchBoxes + " differ; " + bad);
        c.check("every plan equals the independent rules", mismatchPlans == 0, mismatchPlans + " of " + plans + " differ; " + bad);
        TreeSet<String> fixtureRooms = new TreeSet<>(boxes.keySet());
        TreeSet<String> jarRooms = new TreeSet<>(List.of(((String) Mod.staticCall(FEATURE, "tableRoomsForTest")).split(",")));
        c.check("the jar's room list equals the table's", fixtureRooms.equals(jarRooms), "fixture " + fixtureRooms.size()
                + ", jar " + jarRooms.size());
        c.check("a room the table lacks has no boxes (fallback)", Mod.staticCall(FEATURE, "tableBoxesForTest", "Rare Overgrown")
                .equals("none"));
    }
}

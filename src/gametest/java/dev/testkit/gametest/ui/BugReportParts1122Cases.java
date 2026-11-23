package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 1122-ui-bugreport-parts (bugreportsize-1122, killer560 2026-11-22: "Make the zip have no size limit"): a report made large
 * by the mod's test-only filler entry (incompressible text, 20 million characters) is saved WHOLE (the zip holds every
 * character of the filler), and Save and Post against the loopback relay sends it in parts of at most 7.5 MB in order (request 1 declares the
 * parts, total and names and carries part 1; the others come as /bug-report/part?session=..&n=N), with bytes that rejoin
 * to the saved file's sha256; the screen shows "Posting part N of M"; a part that fails once is retried and the post still
 * completes; a part that keeps failing ends with "stopped at part N" and the zip stays saved. A second post with the part
 * size forced to 2 MB checks the order across more than two parts.
 */
final class BugReportParts1122Cases {

    private BugReportParts1122Cases() {
    }

    private static String filler(int chars) {
        Random r = new Random(1122);
        StringBuilder sb = new StringBuilder(chars + 8);
        while (sb.length() < chars) {
            for (int i = 0; i < 5; i++) {
                sb.append((char) ('a' + r.nextInt(26)));
            }
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    /** Clicks Save and Post, polls the status line (recording each "Posting part" one) until done, up to 150 s. */
    private static String post(UiCase c, Screen s, Set<String> seen) {
        InvBatchCases.clickWidget(c, s, BugReportMenu1122Cases.button(c, s, "Save and Post"));
        long deadline = System.currentTimeMillis() + 150000;
        c.ticks(2);
        do {
            String st = BugReportMenu1122Cases.status(c, s);
            if (st.startsWith("Posting part")) {
                seen.add(st);
            }
            c.ticks(2);
        } while (System.currentTimeMillis() < deadline && Boolean.TRUE.equals(c.onClient(mc -> Mod.call(s, "busy"))));
        return BugReportMenu1122Cases.status(c, s);
    }

    static void parts(UiCase c) throws Exception {
        Screen s = BugReportMenu1122Cases.open(c, "bugreport");
        if (s == null) {
            return;
        }
        String title = "Big report " + (System.currentTimeMillis() % 100000);
        BugReportMenu1122Cases.typeInto(c, s, BugReportMenu1122Cases.titleBox(c, s), title);
        BugReportMenu1122Cases.typeInto(c, s, BugReportMenu1122Cases.descBox(c, s), "A report too big for one upload");
        String filler = filler(20_000_000);
        Mod.staticCall("bugreport.BugReportFeature", "setFillerForTest", filler);
        Mod.staticCall("bugreport.BugReportPost", "setTokenForTest", "testkit-bugreport-parts-token");
        Path dir = (Path) Mod.staticCall("util.ModPaths", "config", "bug-reports");
        try (BugReportMenu1122Cases.Stub stub = new BugReportMenu1122Cases.Stub()) {
            // ---- A: the real part size (7.5 MB), a part that fails once is retried ----
            stub.partDelayMs = 500;
            stub.failOncePart = 2;
            long start = System.currentTimeMillis();
            Thread.sleep(1100);
            Set<String> seen = new LinkedHashSet<>();
            String status = post(c, s, seen);
            c.note("A status: " + status + "; seen " + seen);
            Path zip = BugReportMenu1122Cases.newest(dir, start);
            c.check(zip != null, "no zip saved");
            if (zip == null) {
                return;
            }
            byte[] saved = Files.readAllBytes(zip);
            c.note("saved zip: " + saved.length + " bytes");
            c.check(saved.length > 7_500_000, "the test report is not over 7.5 MB (" + saved.length + "), so it proves nothing");
            Map<String, String> z = BugReportMenu1122Cases.unzip(saved);
            c.check(filler.equals(z.get("filler.txt")), "the saved zip's filler.txt is not the full filler: "
                    + (z.get("filler.txt") == null ? "missing" : z.get("filler.txt").length() + " characters of " + filler.length()));
            c.check(z.getOrDefault("mod-log.txt", "").lines().count() > 20,
                    "mod-log.txt looks cut");
            c.check(status.startsWith("Posted: https://discord.com/channels/"), "A did not end Posted: " + status);
            checkParts(c, stub, saved, 7_500_000, true);
            c.check(seen.stream().anyMatch(t -> t.matches("Posting part \\d+ of \\d+\\.\\.\\.")), "the screen never showed Posting part N of M: " + seen);
            c.check(stub.attempts.getOrDefault(2, 0) == 2, "part 2 was not retried once: " + stub.attempts);

            // ---- B: 2 MB parts (more than two parts, order), a part that always fails ends with the saved text ----
            stub.bodies.clear();
            stub.partQueries.clear();
            stub.partBodies.clear();
            stub.attempts.clear();
            stub.failOncePart = -1;
            stub.failPart = 3;
            stub.partDelayMs = 0;
            Mod.staticCall("bugreport.BugReportPost", "setPartSizeForTest", 2_000_000);
            start = System.currentTimeMillis();
            Thread.sleep(1100);
            status = post(c, s, new LinkedHashSet<>());
            c.note("B status: " + status);
            c.check(status.startsWith("Saved, but not posted: stopped at part 3 of ") && status.contains("2 are in the thread"),
                    "a part that keeps failing did not say how far it got: " + status);
            c.check(stub.attempts.getOrDefault(3, 0) == 2, "the failing part was tried " + stub.attempts.getOrDefault(3, 0) + " times, not twice");
            c.check(stub.partQueries.size() == 1, "only part 2 should have been accepted: " + stub.partQueries);
            Path zipB = BugReportMenu1122Cases.newest(dir, start);
            c.check(zipB != null && zipB.toFile().length() > 7_500_000, "the failed post's zip is not saved whole");

            // ---- C: same, all parts accepted ----
            stub.bodies.clear();
            stub.partQueries.clear();
            stub.partBodies.clear();
            stub.attempts.clear();
            stub.failPart = -1;
            start = System.currentTimeMillis();
            Thread.sleep(1100);
            status = post(c, s, new LinkedHashSet<>());
            c.check(status.startsWith("Posted: "), "C did not end Posted: " + status);
            Path zipC = BugReportMenu1122Cases.newest(dir, start);
            c.check(zipC != null, "C saved no zip");
            if (zipC != null) {
                checkParts(c, stub, Files.readAllBytes(zipC), 2_000_000, false);
            }
        } finally {
            Mod.staticCall("bugreport.BugReportFeature", "setFillerForTest", (Object) null);
            Mod.staticCall("bugreport.BugReportPost", "setTokenForTest", (Object) null);
            Mod.staticCall("bugreport.BugReportPost", "setPartSizeForTest", 0);
            BugReportMenu1122Cases.close(c);
        }
    }

    private static void checkParts(UiCase c, BugReportMenu1122Cases.Stub stub, byte[] saved, int partSize, boolean realSize) throws Exception {
        c.check(stub.bodies.size() == 1, "request 1 was sent " + stub.bodies.size() + " times");
        if (stub.bodies.isEmpty()) {
            return;
        }
        com.google.gson.JsonObject body = com.google.gson.JsonParser.parseString(stub.bodies.get(0)).getAsJsonObject();
        int parts = (int) Math.ceil(saved.length / (double) partSize);
        c.note("parts expected " + parts + ", declared " + body.get("parts") + ", later parts " + stub.partQueries.size());
        c.check(body.has("parts") && body.get("parts").getAsInt() == parts, "declared parts " + body.get("parts") + " != " + parts);
        c.check(body.has("total") && body.get("total").getAsLong() == saved.length, "declared total " + body.get("total") + " != " + saved.length);
        c.check(body.has("partSize") && body.get("partSize").getAsInt() == partSize, "declared partSize " + body.get("partSize"));
        List<String> names = new ArrayList<>();
        body.getAsJsonArray("names").forEach(e -> names.add(e.getAsString()));
        c.check(names.contains("filler.txt") && names.contains("note.txt"), "names lack the zip's files: " + names);
        c.check(parts >= (realSize ? 2 : 4), "only " + parts + " parts, so the order proves too little");
        c.check(stub.partQueries.size() == parts - 1, "later parts sent: " + stub.partQueries.size() + ", expected " + (parts - 1));
        for (int i = 0; i < stub.partQueries.size(); i++) {
            String q = stub.partQueries.get(i);
            c.check(q.endsWith("n=" + (i + 2)) && q.contains("session=0123456789abcdef0123456789abcdef"),
                    "part request " + i + " is out of order or lacks the session: " + q);
        }
        java.io.ByteArrayOutputStream joined = new java.io.ByteArrayOutputStream();
        joined.write(java.util.Base64.getDecoder().decode(body.get("zip").getAsString()));
        for (byte[] p : stub.partBodies) {
            c.check(p.length <= partSize, "a part is " + p.length + " bytes, over " + partSize);
            joined.write(p);
        }
        c.check(sha(joined.toByteArray()).equals(sha(saved)), "the parts do not rejoin to the saved file: " + joined.size() + " vs " + saved.length);
    }
}

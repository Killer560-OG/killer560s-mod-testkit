package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 888-890 (croesus-track, 2026-10-12). killer560: "if it moves it still needs to be tracked." A run is known by party,
 * floor and its expiry instant (from "Chests expire in ..."), and the list's order decides between same-party runs whose
 * expiry text is the same; what was learned is kept in killer560smod-croesus-runs.json across restarts.
 * <ul>
 *   <li>888: run 10 (unclaimed) expires and every later run moves one slot back - two same-party (Bravo) runs, one worth
 *       a key, one not, and a key-used run: each keeps its state in its new slot from the first frame.</li>
 *   <li>889: two Bravo runs with the very same "expire in 2d 3h" stay distinct, before and after the shift.</li>
 *   <li>890: after looking at the runs, the memory is reloaded from disk (a restart) and the shifted list still reads
 *       needs-a-key / opened; the file holds the two runs. Then (croesus-outline, 2026-10-13, "make it save across
 *       reloading my instance") his real run file, names anonymized (testkit-fixtures/croesus-runs-real-2026-10-12.json,
 *       59 runs over three pages): every page read, a restart, page 1 alone - the same states and the same totals, with
 *       no "+" and no "Pages seen".</li>
 *   <li>906 (croesus-page-leak, mod d877a026, 2026-10-24, "randomly marks a run as not seen before"): one party's runs
 *       over pages 1 and 2, every one remembered and recently seen except the last on page 2; read page 1, page 2, then
 *       page 1 again. Page 2 keeps each run's own state (the old matcher shifted page 1's last run onto page 2's first
 *       head and every page 2 run took its neighbor's), and page 1 has no "not looked at" afterwards.</li>
 * </ul>
 */
final class CroesusTrackCases {

    private CroesusTrackCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "888-menu-croesus-track-shift", c -> shift(c, false, "1d 20h!"));
        MenuSuite.test(s, "889-menu-croesus-track-same-expiry", c -> shift(c, false, "2d 3h!"));
        MenuSuite.test(s, "890-menu-croesus-track-restart", c -> {
            shift(c, true, "1d 20h!");
            real(c);
        });
        MenuSuite.test(s, "906-menu-croesus-page-leak", CroesusTrackCases::pageLeak);
    }

    static JsonObject head(String player, String stateLine, String expiry) {
        return FlipCroesusCases.item("minecraft:player_head", "§cThe Catacombs", "§7Floor VII", "", "§7Players:",
                " §a" + player + "§f: §eMage (39)", "", "§7Completed: §a1 day ago", "", stateLine,
                "§8Chests expire in " + expiry, "", "§eClick to view chests!");
    }

    static void shift(Session c, boolean restart, String secondExpiry) throws Exception {
        try (AutoCloseable w = CroesusStableCases.world(c)) {
            JsonObject bravoA = head("Bravo", "§7Opened Chest: §4Bedrock", "2d 3h!");
            JsonObject bravoB = head("Bravo", "§7Opened Chest: §4Bedrock", secondExpiry);
            JsonObject done = head("Charlie", "§aNo more chests to open!", "1d 2h!");
            Map<Integer, JsonObject> before = new LinkedHashMap<>();
            before.put(10, head("Alpha", "§cNo chests opened yet!", "2d 5h!"));
            before.put(11, bravoA);
            before.put(12, bravoB);
            before.put(13, done);
            JsonObject list = CroesusStableCases.list(before, Map.of(
                    11, FlipCroesusCases.open(CroesusKeyCases.runView("Testkit Idol")),
                    12, FlipCroesusCases.open(CroesusKeyCases.runView("Testkit Bead"))));
            CroesusStableCases.showList(c, list, 10);
            CroesusStableCases.press(c, 11, CroesusKeyCases.RUN);
            CroesusStableCases.showList(c, list, 10);
            CroesusStableCases.press(c, 12, CroesusKeyCases.RUN);
            CroesusStableCases.recording = true;
            CroesusStableCases.showList(c, list, 15);
            List<CroesusStableCases.Frame> pre = CroesusStableCases.take(c).stream()
                    .filter(f -> f.list() && f.items().equals("xxxx")).toList();
            c.check(!pre.isEmpty() && pre.stream().allMatch(f -> f.states().equals("1234")),
                    "before the shift: " + CroesusStableCases.log(pre) + " (want 1 2 3 4)");
            String file = "";
            if (restart) {
                Path p = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-croesus-runs.json"));
                drainRunsWriter();
                file = Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
                int runs = file.split("\"sig\"", -1).length - 1;
                c.check(runs == 2, "the run file holds " + runs + " run(s), want 2: " + file);
                c.ctx().runOnClient(mc -> Mod.staticCall("croesus.ChestProfitFeature", "reloadRunMemoryForTest"));
            }
            // Run 10 expires: every later run moves one slot back.
            Map<Integer, JsonObject> after = new LinkedHashMap<>();
            after.put(10, bravoA);
            after.put(11, bravoB);
            after.put(12, done);
            CroesusStableCases.recording = true;
            CroesusStableCases.showList(c, CroesusStableCases.list(after, Map.of()), 15);
            List<CroesusStableCases.Frame> post = CroesusStableCases.take(c).stream()
                    .filter(f -> f.list() && f.items().equals("xxx.")).toList();
            c.check(!post.isEmpty() && post.stream().allMatch(f -> f.states().startsWith("234") && f.hidden().startsWith("-HH")),
                    "after the shift" + (restart ? " and a restart" : "") + ": " + CroesusStableCases.log(post)
                            + " (want 2 3 4, hidden -HH, from the first frame)");
            c.note("before: " + CroesusStableCases.log(pre) + "; after the shift" + (restart ? " + reload from disk" : "")
                    + ": " + CroesusStableCases.log(post) + (restart ? "; file " + file.replaceAll("\\s+", " ") : ""));
        }
    }

    /**
     * Waits until every save CroesusRunMemory has queued on its writer thread is on disk. The test reads and writes the
     * run file itself; a save still in flight then either lands on top of the fixture or finds the file held open by this
     * read (on Windows the replace fails, and jars before 2026-10-22 dropped that save: 890 lost pages 2 and 3).
     */
    static void drainRunsWriter() throws Exception {
        ((java.util.concurrent.ExecutorService) Mod.field("croesus.CroesusRunMemory", "WRITER")).submit(() -> { })
                .get(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    static final int[] HEAD_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    static final long HOUR = 3_600_000L;
    static final long DAY = 24 * HOUR;

    /** "Chests expire in" as Hypixel words it, hours rounded down (the real files all carry an hour tolerance). */
    static String expiry(long rem) {
        long d = rem / DAY;
        long h = rem % DAY / HOUR;
        long m = rem % HOUR / 60_000L;
        return d > 0 ? (h > 0 ? d + "d " + h + "h!" : d + "d!") : h > 0 ? h + "h!" : m + "m!";
    }

    static JsonObject realHead(String sig, String stateLine, String expiry) {
        String[] part = sig.split("\\|");
        String[] lore = new String[part.length - 2 + 9];
        int k = 0;
        lore[k++] = "§7" + part[1];
        lore[k++] = "";
        lore[k++] = "§7Players:";
        for (int i = 2; i < part.length; i++) {
            lore[k++] = " §a" + part[i];
        }
        lore[k++] = "";
        lore[k++] = stateLine;
        lore[k++] = "§8Chests expire in " + expiry;
        lore[k++] = "";
        lore[k++] = "§eClick to view chests!";
        lore[k] = "";
        return FlipCroesusCases.item("minecraft:player_head", "§c" + part[0], java.util.Arrays.copyOf(lore, k));
    }

    /** His real file, every time moved to now; a restart in between; the list's states and totals must carry over. */
    static void real(Session c) throws Exception {
        JsonObject fx;
        try (InputStream in = CroesusTrackCases.class.getResourceAsStream("/testkit-fixtures/croesus-runs-real-2026-10-12.json")) {
            fx = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
        long now = System.currentTimeMillis();
        long dt = now - fx.get("ref").getAsLong();
        JsonArray runs = fx.getAsJsonArray("runs");
        Map<Integer, JsonObject> byOrder = new java.util.TreeMap<>();
        for (JsonElement el : runs) {
            JsonObject r = el.getAsJsonObject();
            r.addProperty("expiresAt", r.get("expiresAt").getAsLong() + dt);
            if (r.has("claimAt")) {
                r.addProperty("claimAt", r.get("claimAt").getAsLong() + dt);
            }
            byOrder.put(r.get("order").getAsInt(), r);
        }
        int max = ((java.util.TreeMap<Integer, JsonObject>) byOrder).lastKey();
        int pages = max / HEAD_SLOTS.length + 1;
        try (AutoCloseable w = CroesusStableCases.world(c)) {
            Path p = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-croesus-runs.json"));
            JsonObject file = new JsonObject();
            file.add("runs", runs);
            Files.createDirectories(p.getParent());
            drainRunsWriter(); // a save queued by shift() or the reset must not land on top of the fixture
            Files.writeString(p, file.toString(), StandardCharsets.UTF_8);
            c.ctx().runOnClient(mc -> Mod.staticCall("croesus.ChestProfitFeature", "reloadRunMemoryForTest"));
            // The heads: each remembered run where its order puts it (its real expiry 20 minutes into the hour its
            // estimate covers); an order nothing was remembered for gets an unclaimed run of its own.
            List<JsonObject> lists = new java.util.ArrayList<>();
            Map<Integer, Byte> want = new java.util.HashMap<>();
            for (int pg = 0; pg < pages; pg++) {
                Map<Integer, JsonObject> heads = new LinkedHashMap<>();
                for (int i = 0; i < HEAD_SLOTS.length; i++) {
                    int order = pg * HEAD_SLOTS.length + i;
                    if (order > max) {
                        break;
                    }
                    JsonObject r = byOrder.get(order);
                    if (r == null) {
                        heads.put(HEAD_SLOTS[i], realHead("The Catacombs|Floor VII|Filler" + order + ": Mage (40)",
                                "§cNo chests opened yet!", expiry(DAY + order * 60_000L)));
                        want.put(order, (byte) 1);
                        continue;
                    }
                    long rem = r.get("expiresAt").getAsLong() + 20 * 60_000L - now;
                    String claim = r.has("claimType") ? r.get("claimType").getAsString() : null;
                    String line = claim == null ? "§cNo chests opened yet!"
                            : "§7Opened Chest: §4" + claim.charAt(0) + claim.substring(1).toLowerCase(java.util.Locale.ROOT);
                    heads.put(HEAD_SLOTS[i], realHead(r.get("sig").getAsString(), line, expiry(rem)));
                    byte st = 1;
                    if (claim != null) {
                        st = 3;
                        for (Map.Entry<String, JsonElement> ch : r.getAsJsonObject("chests").entrySet()) {
                            if (!ch.getKey().equals(claim) && ch.getValue().getAsLong() >= 500_000L) {
                                st = 2;
                            }
                        }
                    }
                    want.put(order, st);
                }
                Map<Integer, JsonObject> slots = new LinkedHashMap<>(heads);
                slots.put(49, FlipCroesusCases.item("minecraft:barrier", "§cClose"));
                Map<Integer, JsonObject> on = new LinkedHashMap<>();
                heads.keySet().forEach(k -> on.put(k, new JsonObject()));
                JsonObject m = FlipCroesusCases.menu("(" + (pg + 1) + "/" + pages + ") Croesus", 6, slots, on);
                m.addProperty("fill", false);
                lists.add(m);
            }
            int[] matched = new int[2];
            int claimedRuns = (int) want.entrySet().stream().filter(e -> byOrder.containsKey(e.getKey()) && e.getValue() != 1).count();
            StringBuilder bad = new StringBuilder();
            for (int pass = 0; pass < 2; pass++) {
                int upTo = pass == 0 ? pages : 1; // after the restart: page 1 only
                for (int pg = 0; pg < upTo; pg++) {
                    MenuKit.show(c, lists.get(pg));
                    MenuKit.awaitScreen(c, "(" + (pg + 1) + "/" + pages + ") Croesus", 100);
                    c.ctx().getInput().setCursorPos(2, 2);
                    c.ctx().waitTicks(8);
                    byte[] st = c.onClient(mc -> (byte[]) Mod.staticCall("croesus.ChestProfitFeature", "runStatesForTest"));
                    for (int i = 0; i < HEAD_SLOTS.length; i++) {
                        int order = pg * HEAD_SLOTS.length + i;
                        if (!want.containsKey(order)) {
                            continue;
                        }
                        byte got = HEAD_SLOTS[i] < st.length ? st[HEAD_SLOTS[i]] : -1;
                        if (got == want.get(order)) {
                            if (want.get(order) != 1) {
                                matched[pass]++;
                            }
                        } else if (bad.length() < 600) {
                            bad.append(pass == 0 ? "first" : "after restart").append(" order ").append(order)
                                    .append(" state ").append(got).append(" want ").append(want.get(order)).append("; ");
                        }
                    }
                }
                if (pass == 0) {
                    int[] before = c.onClient(mc -> (int[]) Mod.staticCall("croesus.ChestProfitFeature", "runTotals"));
                    c.ctx().runOnClient(mc -> {
                        Mod.staticCall("croesus.ChestProfitFeature", "reloadRunMemoryForTest");
                        // A restart wipes every static: jars before croesus-outline kept the page counts in
                        // ChestProfitFeature.PAGE_COUNTS, which the reload above does not touch.
                        try {
                            Object counts = Mod.field("croesus.ChestProfitFeature", "PAGE_COUNTS");
                            if (counts instanceof Map<?, ?> m) {
                                m.clear();
                            }
                        } catch (Throwable ignored) {
                            // this jar keeps them with the run memory
                        }
                    });
                    MenuKit.reset(c);
                    fx.addProperty("before", java.util.Arrays.toString(before));
                }
            }
            int[] after = c.onClient(mc -> (int[]) Mod.staticCall("croesus.ChestProfitFeature", "runTotals"));
            List<String> panel = CroesusUiCases.panel(c);
            String before = fx.get("before").getAsString();
            int claimedPage1 = (int) want.entrySet().stream().filter(e -> e.getKey() < HEAD_SLOTS.length
                    && byOrder.containsKey(e.getKey()) && e.getValue() != 1).count();
            c.note(String.format("his file: %d runs, %d claimed ones on the list; matched %d/%d on the first read, %d/%d on"
                    + " page 1 after the restart; totals {unclaimed, claimed, pages current, pages, key, not looked at} %s"
                    + " before, %s after; panel %s", runs.size(), claimedRuns, matched[0], claimedRuns, matched[1],
                    claimedPage1, before, java.util.Arrays.toString(after), panel));
            c.check(bad.length() == 0, "states: " + bad);
            c.check(matched[0] == claimedRuns && matched[1] == claimedPage1, "matched " + matched[0] + "/" + claimedRuns
                    + " then " + matched[1] + "/" + claimedPage1);
            c.check(before.equals(java.util.Arrays.toString(after)) && after[2] == pages && after[3] == pages,
                    "totals before the restart " + before + ", after it with page 1 alone " + java.util.Arrays.toString(after));
            c.check(panel.stream().noneMatch(l -> l.startsWith("Pages seen") || l.contains("+")),
                    "the info box after the restart is partial: " + panel);
        }
    }

    /**
     * 906: one party (Bravo) over two pages, every run expiring inside every other's hour-wide window. Orders 0..36 are
     * remembered (claimed Bedrock; even orders worth a key, odd ones not) and were seen a minute ago; order 37, the last on
     * page 2, never was. Read page 1, page 2, page 1 again: every remembered run keeps its own state and the unremembered
     * one reads "not looked at". Before mod d877a026 the page 2 read voted for a shift of one (page 1's last run, seen a
     * moment before, counted as "reachable" from page 2's first slot), so each page 2 run took its neighbor's state, and
     * page 1's last run, moved onto page 2, read "not looked at" on page 1 afterwards.
     */
    static void pageLeak(Session c) throws Exception {
        final int last = 37; // overall place of the run nobody remembered: the tenth head of page 2
        final int pages = 2;
        String sig = "The Catacombs|Floor VII|Bravo: Mage (40)";
        long now = System.currentTimeMillis();
        long expiresAt = now + 40 * HOUR; // "1d 16h!" for all of them, read to the hour
        JsonArray runs = new JsonArray();
        Map<Integer, Byte> want = new java.util.HashMap<>();
        for (int order = 0; order < last; order++) {
            JsonObject r = new JsonObject();
            r.addProperty("id", "testkit-906-" + order);
            r.addProperty("sig", sig);
            r.addProperty("expiresAt", expiresAt);
            r.addProperty("tolMs", HOUR);
            r.addProperty("order", order);
            r.addProperty("seenAt", now - 60_000L);
            JsonObject chests = new JsonObject();
            chests.addProperty("OBSIDIAN", order % 2 == 0 ? 600_000L : 100_000L);
            r.add("chests", chests);
            r.addProperty("claimType", "BEDROCK");
            r.addProperty("claimKeyed", false);
            r.addProperty("claimAt", now - HOUR);
            runs.add(r);
            want.put(order, (byte) (order % 2 == 0 ? 2 : 3));
        }
        want.put(last, (byte) 5);
        try (AutoCloseable w = CroesusStableCases.world(c)) {
            Path p = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-croesus-runs.json"));
            JsonObject file = new JsonObject();
            file.add("runs", runs);
            Files.createDirectories(p.getParent());
            drainRunsWriter(); // a save queued by the reset must not land on top of the fixture
            Files.writeString(p, file.toString(), StandardCharsets.UTF_8);
            c.ctx().runOnClient(mc -> Mod.staticCall("croesus.ChestProfitFeature", "reloadRunMemoryForTest"));
            List<JsonObject> lists = new java.util.ArrayList<>();
            for (int pg = 0; pg < pages; pg++) {
                Map<Integer, JsonObject> heads = new LinkedHashMap<>();
                for (int i = 0; i < HEAD_SLOTS.length; i++) {
                    int order = pg * HEAD_SLOTS.length + i;
                    if (order > last) {
                        break;
                    }
                    heads.put(HEAD_SLOTS[i], realHead(sig, "§7Opened Chest: §4Bedrock", expiry(40 * HOUR + 20 * 60_000L)));
                }
                Map<Integer, JsonObject> slots = new LinkedHashMap<>(heads);
                slots.put(49, FlipCroesusCases.item("minecraft:barrier", "§cClose"));
                Map<Integer, JsonObject> on = new LinkedHashMap<>();
                heads.keySet().forEach(k -> on.put(k, new JsonObject()));
                JsonObject m = FlipCroesusCases.menu("(" + (pg + 1) + "/" + pages + ") Croesus", 6, slots, on);
                m.addProperty("fill", false);
                lists.add(m);
            }
            StringBuilder bad = new StringBuilder();
            int[] checked = new int[3];
            int[] unknownOnPage1 = new int[2];
            int[] readOrder = {0, 1, 0};
            String[] label = {"page 1", "page 2", "page 1 again"};
            for (int step = 0; step < readOrder.length; step++) {
                int pg = readOrder[step];
                MenuKit.show(c, lists.get(pg));
                MenuKit.awaitScreen(c, "(" + (pg + 1) + "/" + pages + ") Croesus", 100);
                c.ctx().getInput().setCursorPos(2, 2);
                c.ctx().waitTicks(8);
                byte[] st = c.onClient(mc -> (byte[]) Mod.staticCall("croesus.ChestProfitFeature", "runStatesForTest"));
                for (int i = 0; i < HEAD_SLOTS.length; i++) {
                    int order = pg * HEAD_SLOTS.length + i;
                    if (!want.containsKey(order)) {
                        continue;
                    }
                    byte got = HEAD_SLOTS[i] < st.length ? st[HEAD_SLOTS[i]] : -1;
                    checked[step]++;
                    if (pg == 0 && got == 5) {
                        unknownOnPage1[step == 0 ? 0 : 1]++;
                    }
                    if (got != want.get(order) && bad.length() < 600) {
                        bad.append(label[step]).append(" order ").append(order).append(" state ").append(got)
                                .append(" want ").append(want.get(order)).append("; ");
                    }
                }
            }
            c.note(String.format("%d remembered Bravo runs, order %d not; heads checked %s; \"not looked at\" on page 1: %d"
                    + " first read, %d after page 2", last, last, java.util.Arrays.toString(checked), unknownOnPage1[0],
                    unknownOnPage1[1]));
            // Premise: every head of each read was there to be judged (the menus drew and the states were read).
            c.check(checked[0] == HEAD_SLOTS.length && checked[1] == last + 1 - HEAD_SLOTS.length
                    && checked[2] == HEAD_SLOTS.length, "heads checked " + java.util.Arrays.toString(checked));
            c.check(bad.length() == 0, "states: " + bad);
            c.check(unknownOnPage1[1] == 0, unknownOnPage1[1] + " run(s) on page 1 read \"not looked at\" after page 2 was read");
        }
    }
}

package dev.testkit.gametest.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.testkit.gametest.mod.Mod;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The website's boards from the mod (modlb5-1120, every run + tick time + party since sharesite-upload-fixes-1126): p3 (F7) and
 * m7p3 (M7), both the Terminals split ONLY, f7, m7, f7solo, m7solo. Finished runs are fed to {@code ShareToWebsite.onRun} as Run
 * Summary records; every step asserts what the STUB relay received, so a feature that never sent cannot pass.
 * <pre>
 * 1120-logic-modboards    every finished F7/M7 run uploads each board it counts for (a slower run too); not-beaten boss, sim,
 *                         a non-Hypixel server and Share To Website OFF send nothing; Terminals-only P3
 * 1123-logic-p3board      no minimum time on P3; F7 sends p3, M7 sends m7p3, Goldor never counts
 * 1126-logic-share-fields upload bodies: score, ticks, floor, runId, party, party start/end/changed, solo only when solo
 *                         start to end, the same runId from two clients, a run without tick time has no ticks field
 * 1126-logic-share-sim    a sim run and a sim terminal solve send nothing (with a real run as the control that DOES send)
 * 1126-logic-p3-resync    old Terminals + Goldor P3 is replaced once from Run History's best Terminals splits
 * </pre>
 */
public final class ModBoardsCases {

    static final String FEATURE = ShareSiteCases.FEATURE;
    static volatile ClientGameTestContext ctx;
    private static final String PATH = "POST /site/leaderboards/mod-entry Bearer " + ShareSiteCases.TOKEN;
    private static final String[] UUIDS = {"11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222",
            "33333333-3333-4333-8333-333333333333"};

    private ModBoardsCases() {
    }

    private static boolean waitFor(BooleanSupplier ok, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (ok.getAsBoolean()) return true;
            ctx.waitTicks(2);
        }
        return ok.getAsBoolean();
    }

    private static Path bestsFile() {
        return (Path) Mod.staticCall("shared.SharedRecords", "pathOf", Mod.field("shared.RecordCatalog", "BOARD_BESTS"));
    }

    /** A finished run record as JSON. {@code splits}: name, ms pairs (no tick time). */
    private static JsonObject json(String floor, long totalMs, int party, boolean defeated, Object... splits) {
        JsonObject o = new JsonObject();
        o.addProperty("startedAtMs", System.currentTimeMillis() - totalMs);
        o.addProperty("endedAtMs", System.currentTimeMillis());
        o.addProperty("floor", floor);
        o.addProperty("totalMs", totalMs);
        if (defeated) o.addProperty("hypixelTime", "06m 40s");
        o.addProperty("partySize", party);
        JsonArray arr = new JsonArray();
        for (int i = 0; i < splits.length; i += 2) {
            JsonObject s = new JsonObject();
            s.addProperty("name", (String) splits[i]);
            s.addProperty("ms", (Long) splits[i + 1]);
            arr.add(s);
        }
        o.add("splits", arr);
        return o;
    }

    private static void feed(JsonObject o) {
        Object record = Mod.staticCall("runsummary.RunRecord", "fromJson", o);
        ctx.runOnClient(mc -> Mod.staticCall(FEATURE, "onRun", record, 5));
    }

    private static void run(String floor, long totalMs, int party, boolean defeated, Object... splits) {
        feed(json(floor, totalMs, party, defeated, splits));
    }

    private static List<JsonObject> boards(ShareSiteCases.Stub stub) {
        return stub.bodies.stream().filter(b -> b.has("cat")).toList();
    }

    private static List<JsonObject> boards(ShareSiteCases.Stub stub, String cat) {
        return boards(stub).stream().filter(b -> cat.equals(b.get("cat").getAsString())).toList();
    }

    private static long boardMs(ShareSiteCases.Stub stub, String cat) {
        return boards(stub, cat).stream().mapToLong(b -> b.get("ms").getAsLong()).findFirst().orElse(-1L);
    }

    private static int count(ShareSiteCases.Stub stub) {
        return stub.count(PATH);
    }

    /** Waits for the upload chain to go idle, then a little longer, so "nothing was sent" is not just "not yet". */
    private static void settle() {
        ctx.waitTicks(40);
        waitFor(() -> ((List<?>) Mod.staticCall(FEATURE, "boardsPendingForTest")).isEmpty(), 5_000);
        Mod.staticCall("shared.SharedWriter", "drain", 5_000L);
    }

    /** Sharing on, a real-run host, an empty stub. */
    private static void begin(ShareSiteCases.Stub stub) {
        ctx.runOnClient(mc -> {
            Mod.staticCall(FEATURE, "setEnabled", true);
            Mod.staticCall(FEATURE, "boardsAnyHostForTest", true);
        });
        waitFor(() -> !ShareSiteCases.pendingDelete(), 5_000);
        stub.calls.clear();
        stub.bodies.clear();
    }

    private static void end() {
        ctx.runOnClient(mc -> {
            Mod.staticCall(FEATURE, "boardsAnyHostForTest", false);
            Mod.staticCall("secrets.DungeonState", "setRoomSim", false);
            Mod.staticCall(FEATURE, "setEnabled", true);
        });
        Mod.staticCall("shared.SharedWriter", "drain", 5_000L);
    }

    static void boards(LogicCase c) throws Exception {
        try (ShareSiteCases.Stub stub = new ShareSiteCases.Stub()) {
            begin(stub);
            try {
                // 1. A finished F7 with a party of five: f7 and p3 (the Terminals split only, 30 s, never + Goldor), not solo.
                run("F7", 400_000L, 5, true, "Maxor", 30_000L, "Terminals", 30_000L, "Goldor", 10_000L);
                c.check("a finished F7 uploads f7 and p3", waitFor(() -> count(stub) == 2, 10_000), stub.calls.toString());
                settle();
                c.eq("f7 carries the run time", 400_000L, boardMs(stub, "f7"));
                c.eq("p3 is the Terminals split only", 30_000L, boardMs(stub, "p3"));
                c.check("no solo board for a party of five", boardMs(stub, "f7solo") < 0);

                // 2. A slower run, with a slower P3: EVERY run uploads, not only a personal best.
                run("F7", 410_000L, 5, true, "Terminals", 35_000L, "Goldor", 10_000L);
                c.check("a slower run uploads too (4 board uploads)", waitFor(() -> count(stub) == 4, 10_000), stub.calls.toString());
                settle();
                c.eq("two f7 entries", 2, boards(stub, "f7").size());
                c.eq("two p3 entries", 2, boards(stub, "p3").size());

                // 3. A solo F7: f7 and f7solo (no Terminals split, so no p3).
                feed(solo(json("F7", 390_000L, 1, true)));
                c.check("a solo F7 uploads f7 and f7solo", waitFor(() -> count(stub) == 6, 10_000), stub.calls.toString());
                settle();
                c.eq("f7solo carries the time", 390_000L, boardMs(stub, "f7solo"));
                c.eq("no p3 without a Terminals split", 2, boards(stub, "p3").size());

                // 4. M7 solo: m7, m7solo and m7p3.
                feed(solo(json("M7", 500_000L, 1, true, "Terminals", 30_000L, "Goldor", 10_000L)));
                c.check("a solo M7 uploads m7, m7solo and m7p3", waitFor(() -> count(stub) == 9, 10_000), stub.calls.toString());
                settle();
                c.eq("m7 time", 500_000L, boardMs(stub, "m7"));
                c.eq("m7solo time", 500_000L, boardMs(stub, "m7solo"));
                c.eq("m7p3 is the Terminals split", 30_000L, boardMs(stub, "m7p3"));
                c.eq("m7p3 carries the M7 floor", "M7", boards(stub, "m7p3").get(0).get("floor").getAsString());

                // 5. The boss was not beaten: nothing, however fast.
                run("F7", 200_000L, 1, false, "Terminals", 10_000L, "Goldor", 5_000L);
                settle();
                c.eq("a run without the boss's defeat sends nothing", 9, count(stub));

                // 6. The sim sends nothing.
                ctx.runOnClient(mc -> Mod.staticCall("secrets.DungeonState", "setRoomSim", true));
                run("F7", 200_000L, 1, true, "Terminals", 10_000L, "Goldor", 5_000L);
                settle();
                c.eq("a sim run sends nothing", 9, count(stub));
                ctx.runOnClient(mc -> Mod.staticCall("secrets.DungeonState", "setRoomSim", false));

                // 7. A server that is not Hypixel SkyBlock (the testkit's own) sends nothing.
                ctx.runOnClient(mc -> Mod.staticCall(FEATURE, "boardsAnyHostForTest", false));
                run("F7", 200_000L, 1, true, "Terminals", 10_000L, "Goldor", 5_000L);
                settle();
                c.eq("a run off Hypixel sends nothing", 9, count(stub));
                ctx.runOnClient(mc -> Mod.staticCall(FEATURE, "boardsAnyHostForTest", true));

                // 8. Share To Website OFF: nothing goes, the delete does.
                ctx.runOnClient(mc -> Mod.staticCall(FEATURE, "setEnabled", false));
                c.check("OFF sends the delete", waitFor(() -> stub.count("DELETE /site/stats Bearer " + ShareSiteCases.TOKEN) == 1, 10_000), stub.calls.toString());
                run("F7", 200_000L, 1, true, "Terminals", 10_000L, "Goldor", 5_000L);
                settle();
                c.eq("with Share To Website OFF a run sends nothing", 9, count(stub));
                c.eq("and nothing is waiting", 0, ((List<?>) Mod.staticCall(FEATURE, "boardsPendingForTest")).size());

                // 9. Back ON: the next run goes again (f7 only: a party of five, no Terminals split).
                ctx.runOnClient(mc -> Mod.staticCall(FEATURE, "setEnabled", true));
                waitFor(() -> !ShareSiteCases.pendingDelete(), 5_000);
                run("F7", 450_000L, 5, true);
                c.check("back ON a run uploads (f7)", waitFor(() -> count(stub) == 10, 10_000), stub.calls.toString());
            } finally {
                end();
            }
        }
    }

    /** 1123-logic-p3board: P3 has no minimum time; F7 sends p3, M7 sends m7p3, and Goldor never counts. */
    static void p3Board(LogicCase c) throws Exception {
        try (ShareSiteCases.Stub stub = new ShareSiteCases.Stub()) {
            begin(stub);
            try {
                run("F7", 300_000L, 5, true, "Terminals", 3_000L, "Goldor", 2_000L);
                c.check("a 3 s F7 Terminals split is sent as p3", waitFor(() -> boardMs(stub, "p3") == 3_000L, 10_000), stub.calls.toString());
                settle();
                int before = count(stub);
                run("M7", 280_000L, 5, true, "Terminals", 1_000L, "Goldor", 1_000L);
                c.check("an M7 uploads m7 and m7p3", waitFor(() -> count(stub) == before + 2, 10_000), stub.calls.toString());
                settle();
                c.eq("m7p3 is the Terminals split", 1_000L, boardMs(stub, "m7p3"));
                c.eq("the only p3 sent is the F7 one", 1, boards(stub, "p3").size());
            } finally {
                end();
            }
        }
    }

    /** The record JSON with the party and share facts of a run played alone from start to end. */
    private static JsonObject solo(JsonObject o) {
        return withShare(o, -1L, 1, 1, false);
    }

    private static JsonObject withShare(JsonObject o, long totalTick, int start, int end, boolean changed) {
        JsonObject sh = new JsonObject();
        sh.addProperty("totalTickMs", totalTick);
        sh.addProperty("partyStart", start);
        sh.addProperty("partyEnd", end);
        sh.addProperty("partyChanged", changed);
        o.add("share", sh);
        return o;
    }

    private static JsonObject withParty(JsonObject o, String... uuids) {
        JsonArray party = new JsonArray();
        for (String u : uuids) {
            JsonObject m = new JsonObject();
            m.addProperty("uuid", u);
            m.addProperty("name", "P" + u.charAt(0));
            party.add(m);
        }
        o.add("party", party);
        return o;
    }

    private static JsonObject tickSplit(JsonObject o, String name, long ms, long tick) {
        JsonObject s = new JsonObject();
        s.addProperty("name", name);
        s.addProperty("ms", ms);
        s.addProperty("tickMs", tick);
        o.getAsJsonArray("splits").add(s);
        return o;
    }

    /** 1126-logic-share-fields: what each upload carries. */
    static void fields(LogicCase c) throws Exception {
        try (ShareSiteCases.Stub stub = new ShareSiteCases.Stub()) {
            begin(stub);
            try {
                // A five-player F7 with tick time, a Terminals tick split, three uuids and Hypixel's score.
                JsonObject a = withParty(withShare(json("F7", 400_000L, 5, true), 396_500L, 5, 5, false), UUIDS[0], UUIDS[1], UUIDS[2]);
                tickSplit(a, "Terminals", 30_000L, 29_450L);
                a.addProperty("hypixelScore", 305);
                a.addProperty("hypixelRank", "S+");
                feed(a);
                c.check("run + f7 + p3 reached the relay", waitFor(() -> count(stub) == 2 && stub.count("POST /site/stats Bearer " + ShareSiteCases.TOKEN) == 1, 10_000), stub.calls.toString());
                settle();
                JsonObject run = stub.bodies.stream().filter(b -> b.has("runs")).findFirst().orElseThrow().getAsJsonArray("runs").get(0).getAsJsonObject();
                c.eq("the stats run carries the score", 305, run.get("score").getAsInt());
                JsonObject f7 = boards(stub, "f7").get(0);
                JsonObject p3 = boards(stub, "p3").get(0);
                c.eq("f7 real ms", 400_000L, f7.get("ms").getAsLong());
                c.eq("f7 ticks", 396_500L, f7.get("ticks").getAsLong());
                c.eq("p3 real ms", 30_000L, p3.get("ms").getAsLong());
                c.eq("p3 ticks", 29_450L, p3.get("ticks").getAsLong());
                c.eq("floor", "F7", f7.get("floor").getAsString());
                c.eq("team", 5, f7.get("team").getAsInt());
                c.eq("party at start", 5, f7.get("partyStart").getAsInt());
                c.eq("party at end", 5, f7.get("partyEnd").getAsInt());
                c.check("party did not change", !f7.get("partyChanged").getAsBoolean());
                c.check("startedAt and endedAt present", f7.get("startedAt").getAsLong() > 0 && f7.get("endedAt").getAsLong() >= f7.get("startedAt").getAsLong());
                List<String> party = new ArrayList<>();
                f7.getAsJsonArray("party").forEach(e -> party.add(e.getAsString()));
                c.check("the party uuids are sent", party.size() == 3 && party.containsAll(List.of(UUIDS)), party.toString());
                c.check("a runId starting with the floor", f7.get("runId").getAsString().startsWith("F7:"), f7.toString());
                c.eq("p3 shares the team's runId", f7.get("runId").getAsString(), p3.get("runId").getAsString());
                c.check("no player name is sent", !stub.bodies.toString().contains("P1"), stub.bodies.toString());

                // The same team's run seen by another client (party listed in another order, its own start time): the same runId.
                int before = count(stub);
                feed(withParty(withShare(json("F7", 401_000L, 5, true), -1L, 5, 5, false), UUIDS[2], UUIDS[0], UUIDS[1]));
                c.check("the second client's run uploaded", waitFor(() -> count(stub) == before + 1, 10_000), stub.calls.toString());
                settle();
                JsonObject b = boards(stub, "f7").get(1);
                c.eq("the same runId from the other client", f7.get("runId").getAsString(), b.get("runId").getAsString());
                c.check("a run without tick time has no ticks field", !b.has("ticks"), b.toString());

                // Solo means one player from start to end: party size 1 but a change on the way is not solo.
                int n = count(stub);
                feed(withShare(json("F7", 390_000L, 1, true), -1L, 1, 1, true));
                c.check("a run whose party changed uploads f7 only", waitFor(() -> count(stub) == n + 1, 10_000), stub.calls.toString());
                settle();
                c.eq("no f7solo for a changed party", 0, boards(stub, "f7solo").size());
                feed(withShare(json("F7", 380_000L, 1, true), -1L, 1, 2, false));
                settle();
                c.eq("no f7solo when the party ended at two", 0, boards(stub, "f7solo").size());
                feed(solo(json("F7", 370_000L, 1, true)));
                c.check("a solo run uploads f7solo", waitFor(() -> boards(stub, "f7solo").size() == 1, 10_000), stub.calls.toString());
            } finally {
                end();
            }
        }
    }

    /** 1126-logic-share-sim: nothing from the sim; the same calls on a real host DO send (the control). */
    static void sim(LogicCase c) throws Exception {
        try (ShareSiteCases.Stub stub = new ShareSiteCases.Stub()) {
            begin(stub);
            try {
                ctx.runOnClient(mc -> Mod.staticCall(FEATURE, "onTerminalSolved", "Panes", 4.25));
                c.eq("control: a real terminal solve is kept", 1, Mod.staticCall(FEATURE, "termsForTest"));
                run("F7", 400_000L, 5, true, "Terminals", 30_000L);
                c.check("control: a real run reaches the relay", waitFor(() -> stub.count("POST /site/stats Bearer " + ShareSiteCases.TOKEN) == 1 && count(stub) == 2, 10_000), stub.calls.toString());
                settle();
                int posts = stub.count("POST");
                c.eq("control: the kept solve went with the run", 0, Mod.staticCall(FEATURE, "termsForTest"));

                ctx.runOnClient(mc -> Mod.staticCall("secrets.DungeonState", "setRoomSim", true));
                ctx.runOnClient(mc -> Mod.staticCall(FEATURE, "onTerminalSolved", "Panes", 4.25));
                c.eq("a sim terminal solve is not kept", 0, Mod.staticCall(FEATURE, "termsForTest"));
                run("F7", 400_000L, 5, true, "Terminals", 30_000L);
                ctx.runOnClient(mc -> Mod.staticCall(FEATURE, "flush"));
                settle();
                c.eq("a sim run sends nothing at all (stats or boards)", posts, stub.count("POST"));
                c.eq("and nothing waits", 0, ((List<?>) Mod.staticCall(FEATURE, "boardsPendingForTest")).size());
                c.eq("and no solve waits", 0, Mod.staticCall(FEATURE, "termsForTest"));
                ctx.runOnClient(mc -> Mod.staticCall("secrets.DungeonState", "setRoomSim", false));

                ctx.runOnClient(mc -> Mod.staticCall(FEATURE, "boardsAnyHostForTest", false));
                ctx.runOnClient(mc -> Mod.staticCall(FEATURE, "onTerminalSolved", "Panes", 4.25));
                run("F7", 400_000L, 5, true, "Terminals", 30_000L);
                settle();
                c.eq("a non-Hypixel server keeps no solve", 0, Mod.staticCall(FEATURE, "termsForTest"));
                c.eq("and sends nothing", posts, stub.count("POST"));
            } finally {
                end();
            }
        }
    }

    /** 1126-logic-p3-resync: the old P3 (Terminals + Goldor) on the site is replaced once from Run History's Terminals splits. */
    static void p3Resync(LogicCase c) throws Exception {
        Path f = bestsFile();
        byte[] saved = Files.exists(f) ? Files.readAllBytes(f) : null;
        Object old = Mod.staticCall("runsummary.RunHistoryStore", "runs");
        try (ShareSiteCases.Stub stub = new ShareSiteCases.Stub()) {
            Files.deleteIfExists(f);
            begin(stub);
            try {
                List<Object> runs = new ArrayList<>();
                for (JsonObject o : List.of(
                        tickSplit(json("F7", 300_000L, 5, true, "Goldor", 8_000L), "Terminals", 26_970L, 26_800L),
                        json("F7", 310_000L, 5, true, "Terminals", 31_000L, "Goldor", 5_000L),
                        json("M7", 400_000L, 5, true, "Terminals", 40_000L))) {
                    runs.add(Mod.staticCall("runsummary.RunRecord", "fromJson", o));
                }
                ctx.runOnClient(mc -> Mod.staticCall("runsummary.RunHistoryStore", "setRunsForTest", runs));
                ctx.runOnClient(mc -> Mod.staticCall(FEATURE, "boardsResyncForTest"));
                c.check("the best Terminals splits per floor are re-uploaded", waitFor(() -> count(stub) == 2, 10_000), stub.calls.toString());
                settle();
                JsonObject p3 = boards(stub, "p3").get(0);
                c.eq("p3 is the best Terminals split, not Terminals + Goldor", 26_970L, p3.get("ms").getAsLong());
                c.eq("with its tick time", 26_800L, p3.get("ticks").getAsLong());
                c.eq("m7p3 from the M7 run", 40_000L, boardMs(stub, "m7p3"));
                c.check("the resync mark was stored", Files.exists(f) && Files.readString(f).contains("p3-terminals-resync"), Files.exists(f) ? Files.readString(f) : "no file");
                ctx.runOnClient(mc -> Mod.staticCall(FEATURE, "boardsResyncForTest"));
                settle();
                c.eq("a second join sends nothing more", 2, count(stub));
            } finally {
                end();
                @SuppressWarnings("unchecked") List<Object> restore = (List<Object>) old;
                ctx.runOnClient(mc -> Mod.staticCall("runsummary.RunHistoryStore", "setRunsForTest", restore));
                if (saved != null) Files.write(f, saved);
                else Files.deleteIfExists(f);
            }
        }
    }
}

package dev.testkit.gametest.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.testkit.gametest.mod.Mod;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 1120-logic-modboards: the mod times the website's five boards (p3, f7, m7, f7solo, m7solo) and uploads a new personal best to
 * {@code POST /site/leaderboards/mod-entry} through the Share To Website path (mod modlb5-1120). Finished runs are fed to
 * {@code ShareToWebsite.onRun} as Run Summary records; every step asserts what the STUB relay received, so a feature that
 * never sent cannot pass. Covered: the right boards and times (F7, F7 solo, M7 solo, P3 from the Terminals + Goldor splits),
 * a slower run sends nothing, a boss not beaten sends nothing, the sim sends nothing, a server that is not Hypixel sends
 * nothing, Share To Website OFF sends nothing, and a faster P3 alone sends only P3.
 */
public final class ModBoardsCases {

    static final String FEATURE = ShareSiteCases.FEATURE;
    static volatile ClientGameTestContext ctx;

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

    /** One finished run, as Run Summary would hand it over. {@code splits}: name, ms pairs; {@code defeated}: the boss line was seen. */
    private static void run(String floor, long totalMs, int party, boolean defeated, Object... splits) {
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
        Object record = Mod.staticCall("runsummary.RunRecord", "fromJson", o);
        ctx.runOnClient(mc -> Mod.staticCall(FEATURE, "onRun", record, 5));
    }

    private static List<JsonObject> boards(ShareSiteCases.Stub stub) {
        return stub.bodies.stream().filter(b -> b.has("cat")).toList();
    }

    private static long boardMs(ShareSiteCases.Stub stub, String cat) {
        return boards(stub).stream().filter(b -> cat.equals(b.get("cat").getAsString())).mapToLong(b -> b.get("ms").getAsLong()).findFirst().orElse(-1L);
    }

    private static int count(ShareSiteCases.Stub stub) {
        return (int) stub.calls.stream().filter(s -> s.startsWith("POST /site/leaderboards/mod-entry Bearer " + ShareSiteCases.TOKEN)).count();
    }

    /** Waits for the upload chain to go idle, then a little longer, so "nothing was sent" is not just "not yet". */
    private static void settle() {
        ctx.waitTicks(40);
        waitFor(() -> ((List<?>) Mod.staticCall(FEATURE, "boardsPendingForTest")).isEmpty(), 5_000);
        Mod.staticCall("shared.SharedWriter", "drain", 5_000L);
    }

    static void boards(LogicCase c) throws Exception {
        Path f = bestsFile();
        byte[] saved = Files.exists(f) ? Files.readAllBytes(f) : null;
        try (ShareSiteCases.Stub stub = new ShareSiteCases.Stub()) {
            Files.deleteIfExists(f);
            ctx.runOnClient(mc -> {
                Mod.staticCall(FEATURE, "setEnabled", true);
                Mod.staticCall(FEATURE, "boardsAnyHostForTest", true);
            });
            waitFor(() -> !ShareSiteCases.pendingDelete(), 5_000);
            stub.calls.clear();
            stub.bodies.clear();

            // 1. A finished F7 with a party of five: f7 and p3 (Terminals + Goldor), not the solo board.
            run("F7", 400_000L, 5, true, "Maxor", 30_000L, "Terminals", 30_000L, "Goldor", 10_000L);
            c.check("a finished F7 uploads f7 and p3", waitFor(() -> count(stub) == 2, 10_000), stub.calls.toString());
            settle();
            c.eq("f7 carries the run time", 400_000L, boardMs(stub, "f7"));
            c.eq("p3 is Terminals + Goldor", 40_000L, boardMs(stub, "p3"));
            c.check("no solo board for a party of five", boardMs(stub, "f7solo") < 0);
            JsonObject f7 = boards(stub).stream().filter(b -> "f7".equals(b.get("cat").getAsString())).findFirst().orElseThrow();
            c.check("the upload carries the floor, team size, end time, version, commit and variant",
                    "F7".equals(f7.get("floor").getAsString()) && f7.get("team").getAsInt() == 5 && f7.get("endedAt").getAsLong() > 0
                            && f7.has("version") && f7.has("commit") && f7.has("variant") && f7.get("v").getAsInt() == 1, f7.toString());
            c.check("the stored best was written", Files.exists(f) && Files.readString(f).contains("\"f7\""), Files.exists(f) ? Files.readString(f) : "no file");

            // 2. A slower run, with a slower P3: nothing.
            run("F7", 410_000L, 5, true, "Terminals", 35_000L, "Goldor", 10_000L);
            settle();
            c.eq("a slower run sends nothing (still 2 board uploads)", 2, count(stub));

            // 3. A faster solo F7: f7 and f7solo, both at the new time.
            run("F7", 390_000L, 1, true);
            c.check("a faster solo F7 uploads f7 and f7solo", waitFor(() -> count(stub) == 4, 10_000), stub.calls.toString());
            settle();
            c.eq("f7 now holds the faster time", 390_000L, boards(stub).stream().filter(b -> "f7".equals(b.get("cat").getAsString()))
                    .mapToLong(b -> b.get("ms").getAsLong()).min().orElse(-1L));
            c.eq("f7solo carries it", 390_000L, boardMs(stub, "f7solo"));

            // 4. M7 solo: m7 and m7solo with the M7 floor.
            run("M7", 500_000L, 1, true, "Terminals", 30_000L, "Goldor", 10_000L);
            c.check("a solo M7 uploads m7 and m7solo", waitFor(() -> count(stub) == 6, 10_000), stub.calls.toString());
            settle();
            c.eq("m7 time", 500_000L, boardMs(stub, "m7"));
            c.eq("m7solo time", 500_000L, boardMs(stub, "m7solo"));
            c.check("no P3 from an M7 (the P3 board is F7)", boardMs(stub, "p3") == 40_000L && boards(stub).stream().filter(b -> "p3".equals(b.get("cat").getAsString())).count() == 1);

            // 5. A faster P3 alone sends only p3.
            run("F7", 395_000L, 5, true, "Terminals", 20_000L, "Goldor", 10_000L);
            c.check("a faster P3 on a slower run uploads only p3", waitFor(() -> count(stub) == 7, 10_000), stub.calls.toString());
            settle();
            c.eq("p3 time", 30_000L, boards(stub).stream().filter(b -> "p3".equals(b.get("cat").getAsString())).mapToLong(b -> b.get("ms").getAsLong()).min().orElse(-1L));

            // 6. The boss was not beaten: nothing, however fast.
            run("F7", 200_000L, 1, false, "Terminals", 10_000L, "Goldor", 5_000L);
            settle();
            c.eq("a run without the boss's defeat sends nothing", 7, count(stub));

            // 7. The sim sends nothing.
            ctx.runOnClient(mc -> Mod.staticCall("secrets.DungeonState", "setRoomSim", true));
            try {
                run("F7", 200_000L, 1, true, "Terminals", 10_000L, "Goldor", 5_000L);
                settle();
                c.eq("a sim run sends nothing", 7, count(stub));
            } finally {
                ctx.runOnClient(mc -> Mod.staticCall("secrets.DungeonState", "setRoomSim", false));
            }

            // 8. A server that is not Hypixel SkyBlock (the testkit's own) sends nothing.
            ctx.runOnClient(mc -> Mod.staticCall(FEATURE, "boardsAnyHostForTest", false));
            run("F7", 200_000L, 1, true, "Terminals", 10_000L, "Goldor", 5_000L);
            settle();
            c.eq("a run off Hypixel sends nothing", 7, count(stub));
            ctx.runOnClient(mc -> Mod.staticCall(FEATURE, "boardsAnyHostForTest", true));

            // 9. Share To Website OFF: nothing goes, the delete does.
            ctx.runOnClient(mc -> Mod.staticCall(FEATURE, "setEnabled", false));
            c.check("OFF sends the delete", waitFor(() -> stub.count("DELETE /site/stats Bearer " + ShareSiteCases.TOKEN) == 1, 10_000), stub.calls.toString());
            run("F7", 200_000L, 1, true, "Terminals", 10_000L, "Goldor", 5_000L);
            settle();
            c.eq("with Share To Website OFF a record run sends nothing", 7, count(stub));
            c.eq("and nothing is waiting", 0, ((List<?>) Mod.staticCall(FEATURE, "boardsPendingForTest")).size());

            // 10. Back ON: the same record run now beats the stored best and goes.
            ctx.runOnClient(mc -> Mod.staticCall(FEATURE, "setEnabled", true));
            waitFor(() -> !ShareSiteCases.pendingDelete(), 5_000);
            run("F7", 200_000L, 1, true, "Terminals", 10_000L, "Goldor", 5_000L);
            c.check("back ON the record run uploads (f7, f7solo, p3)", waitFor(() -> count(stub) == 10, 10_000), stub.calls.toString());
        } finally {
            ctx.runOnClient(mc -> {
                Mod.staticCall(FEATURE, "boardsAnyHostForTest", false);
                Mod.staticCall("secrets.DungeonState", "setRoomSim", false);
                Mod.staticCall(FEATURE, "setEnabled", true);
            });
            Mod.staticCall("shared.SharedWriter", "drain", 5_000L);
            if (saved != null) Files.write(f, saved);
            else Files.deleteIfExists(f);
        }
    }
}

package dev.testkit.gametest.logic;

import com.google.gson.JsonParser;

import dev.testkit.gametest.mod.Mod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 1120-logic-async-save (freezefix-1120): the Custom Scoreboard's extra-data save ran on the client tick and a stalled
 * disk write froze the game for 22 s. The shared writer thread is held busy with a latch, then the tick-side update
 * runs twice with a changed value each time: it must return at once (the write is queued, not done), the file must
 * not be touched yet, and once the writer is released the file must hold the NEWEST snapshot.
 */
final class AsyncSaveCases {

    private static final String DATA = "scoreboard.ScoreboardExtraData";
    private static final String WRITER = "shared.SharedWriter";

    private AsyncSaveCases() {
    }

    static void run(LogicCase c) throws Exception {
        Path file = (Path) Mod.field(DATA, "DATA_PATH");
        Mod.staticCall(WRITER, "drain", 5000L);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"magicalPower\":1}", StandardCharsets.UTF_8);
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch holding = new CountDownLatch(1);
        Mod.staticCall(WRITER, "submit", (Runnable) () -> {
            holding.countDown();
            try {
                hold.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        });
        Mod.staticCall(DATA, "load");
        try {
            c.check("the writer thread is held busy", holding.await(5, TimeUnit.SECONDS), "");
            for (int v : new int[] {111, 222}) {
                Mod.setField(DATA, "magicalPower", v);
                Mod.setField(DATA, "dirty", true);
                Mod.setField(DATA, "lastSaveMs", 0L);
                long t0 = System.nanoTime();
                Mod.staticCall(DATA, "update", (Object) null, false);
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                c.check("update with a pending save returns while the writer is blocked (" + v + ")", ms < 1500, ms + " ms");
                c.check("the save was taken (dirty cleared)", Boolean.FALSE.equals(Mod.field(DATA, "dirty")), "");
            }
            c.eq("nothing reached the disk while the writer was blocked", 1,
                    JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject()
                            .get("magicalPower").getAsInt());
        } finally {
            hold.countDown();
        }
        Mod.staticCall(WRITER, "drain", 10000L);
        c.eq("after the writer ran, the file holds the newest snapshot", 222,
                JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject()
                        .get("magicalPower").getAsInt());
    }
}

package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.TextRuns;
import dev.testkit.gametest.mod.Mod;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 1122-ui-bugreport-text (bugreporttext-1122): the Bug Report screen no longer lists what the zip holds, says once that the
 * report holds none of the player's personal information, and draws its description counter once; the game log in the zip
 * has chat text removed and addresses, e-mails and other players' UUIDs replaced.
 * 1122-ui-bugreport-bg (bugreporttext-1122): Save and Save and Post keep going after the screen is closed (next tick),
 * after another screen opens and after the mod's disconnect handlers run (a world change): the zip is whole on disk, every
 * part and the final post reach the loopback relay, the result is a chat line, the bug reports folder is opened exactly once
 * per report for each button, a reopened screen shows the running one.
 */
final class BugReportBackground1122Cases {
    private BugReportBackground1122Cases() {
    }

    private static List<String> drawn(UiCase c, Screen s) {
        return c.onClient(mc -> {
            GuiRenderState state = new GuiRenderState();
            s.extractRenderState(new GuiGraphicsExtractor(mc, state, -1, -1), 0, 0, 0f);
            List<String> out = new ArrayList<>();
            state.forEachText(t -> out.add(TextRuns.string(t)));
            return out;
        });
    }

    static void text(UiCase c) throws Exception {
        Screen s = BugReportMenu1122Cases.open(c, "bugreport");
        if (s == null) {
            return;
        }
        try {
            c.ticks(3);
            List<String> d = drawn(c, s);
            String all = String.join("\n", d);
            c.note("drawn: " + d);
            c.check(!all.contains("The zip will hold") && !all.contains("note.txt") && !all.contains("mod-log.txt"),
                    "the old list of the zip's contents is still drawn: " + d);
            c.check(d.contains("This report contains none of your personal information."),
                    "the personal-information line is not drawn: " + d);
            c.check(d.stream().filter(t -> t.matches("\\d+ ?/ ?3000")).count() == 1,
                    "the description counter is not drawn exactly once: " + d);
            // The zip's game log: chat text gone, addresses and e-mails replaced.
            String red = redactedLog(c);
            c.check(red != null && !red.contains("secret chat words") && !red.contains("203.0.113.9")
                            && !red.contains("someone@example.com") && red.contains("[CHAT] <chat removed>") && red.contains("<ip>")
                            && !red.contains("privateserver-example") && !red.contains("123e4567")
                            && !red.toLowerCase().contains(System.getProperty("user.name").toLowerCase()),
                    "the log in the zip still carries chat text, an address or an e-mail: " + red);
        } finally {
            BugReportMenu1122Cases.close(c);
        }
    }

    /** What the zip builder makes of a log with a chat line, an address and an e-mail in it. */
    private static String redactedLog(UiCase c) throws Exception {
        Path game = (Path) Mod.staticCall("util.ModPaths", "config", "bug-reports");
        Path logs = game.getParent().getParent().getParent().resolve("logs");
        Path latest = logs.resolve("latest.log");
        byte[] old = Files.isRegularFile(latest) ? Files.readAllBytes(latest) : null;
        Files.createDirectories(logs);
        Files.writeString(latest, "[12:00:00] [Render thread/INFO]: [CHAT] Other: secret chat words\n"
                + "[12:00:01] [Render thread/INFO]: Connecting to 203.0.113.9, 25565\n"
                + "[12:00:02] [main/INFO]: mail someone@example.com\n"
                + "[12:00:03] [main/INFO]: Connecting to play.privateserver-example.net, 25565\n"
                + "[12:00:04] [main/INFO]: other 123e4567-e89b-12d3-a456-426614174000 here\n"
                + "[12:00:05] [main/INFO]: path C:\\Users\\" + System.getProperty("user.name") + "\\AppData\\x and "
                + System.getProperty("user.name") + "\n");
        try {
            return (String) Mod.staticCall("bugreport.BugReportFeature", "readRedactedLog", "TestKitSelf", null);
        } finally {
            if (old != null) {
                Files.write(latest, old);
            }
        }
    }

    private static int opensAny(long mark, Path dir) {
        int n = 0;
        String a = dir.toString();
        String b = a.replace('\\', '/');
        for (String l : LogTap.since(mark)) {
            n += l.contains("would open") && (l.contains(a) || l.contains(b)) ? 1 : 0;
        }
        return n;
    }

    private static boolean chat(long mark, String text) {
        for (String l : LogTap.since(mark)) {
            if (l.contains(text)) {
                return true;
            }
        }
        return false;
    }

    private static void waitRunning(UiCase c, boolean want) {
        long end = System.currentTimeMillis() + 60000;
        while (System.currentTimeMillis() < end && Boolean.TRUE.equals(Mod.staticCall("bugreport.BugReportFeature", "running")) != want) {
            c.ticks(5);
        }
    }

    static void background(UiCase c) throws Exception {
        Screen s = BugReportMenu1122Cases.open(c, "bugreport");
        if (s == null) {
            return;
        }
        String title = "Background " + (System.currentTimeMillis() % 100000);
        BugReportMenu1122Cases.typeInto(c, s, BugReportMenu1122Cases.titleBox(c, s), title);
        BugReportMenu1122Cases.typeInto(c, s, BugReportMenu1122Cases.descBox(c, s), "closed while sending");
        String filler = BugReportParts1122Cases.filler(20_000_000);
        Mod.staticCall("bugreport.BugReportPost", "setTokenForTest", "testkit-bugreport-bg-token");
        Path dir = (Path) Mod.staticCall("util.ModPaths", "config", "bug-reports");
        try (BugReportMenu1122Cases.Stub stub = new BugReportMenu1122Cases.Stub()) {
            // ---- Save, screen closed on the next tick ----
            long mark = LogTap.mark();
            long start = System.currentTimeMillis();
            Thread.sleep(1100);
            InvBatchCases.clickWidget(c, s, BugReportMenu1122Cases.button(c, s, "Save"));
            BugReportMenu1122Cases.close(c);
            waitRunning(c, false);
            Path zip = BugReportMenu1122Cases.newest(dir, start);
            c.check(zip != null, "Save then Close left no zip");
            if (zip != null) {
                c.check(BugReportMenu1122Cases.unzip(Files.readAllBytes(zip)).getOrDefault("note.txt", "").contains(title),
                        "the zip written after the screen closed is not whole");
            }
            c.ticks(5);
            c.check(opensAny(mark, dir) == 1, "Save opened the bug reports folder " + opensAny(mark, dir) + " times, not once");

            // ---- Save and Post with parts, closed, then another screen, then a world change ----
            Mod.staticCall("bugreport.BugReportFeature", "setFillerForTest", filler);
            Mod.staticCall("bugreport.BugReportPost", "setPartSizeForTest", 2_000_000);
            stub.partDelayMs = 300;
            mark = LogTap.mark();
            start = System.currentTimeMillis();
            Thread.sleep(1100);
            Screen s2 = BugReportMenu1122Cases.open(c, "bugreport");
            if (s2 == null) {
                return;
            }
            BugReportMenu1122Cases.typeInto(c, s2, BugReportMenu1122Cases.titleBox(c, s2), title);
            InvBatchCases.clickWidget(c, s2, BugReportMenu1122Cases.button(c, s2, "Save and Post"));
            BugReportMenu1122Cases.close(c);
            c.onClient(mc -> {
                mc.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true));
                return null;
            });
            c.check(Boolean.TRUE.equals(Mod.staticCall("bugreport.BugReportFeature", "running")), "the send was not running after the screen closed");
            c.onClient(mc -> {
                ClientPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(mc.getConnection(), mc);
                return null;
            });
            // a second report while one sends is refused, and a reopened screen shows the running one
            Screen s3 = BugReportMenu1122Cases.open(c, "bugreport");
            if (s3 != null) {
                c.check(Boolean.TRUE.equals(c.onClient(mc -> Mod.call(s3, "busy"))), "a reopened screen does not show the running send");
            }
            waitRunning(c, false);
            c.ticks(5);
            Path zip2 = BugReportMenu1122Cases.newest(dir, start);
            c.check(zip2 != null, "Save and Post then Close left no zip");
            c.check(stub.bodies.size() == 1, "the relay got " + stub.bodies.size() + " first requests");
            if (zip2 != null && !stub.bodies.isEmpty()) {
                long total = Files.size(zip2);
                int parts = (int) Math.ceil(total / 2_000_000.0);
                c.check(parts >= 4 && stub.partQueries.size() == parts - 1,
                        "parts that reached the relay: " + stub.partQueries.size() + ", expected " + (parts - 1));
            }
            c.check(chat(mark, "Posted to the Discord"), "no 'Posted to the Discord' chat line after the screen was gone");
            c.check(opensAny(mark, dir) == 1, "Save and Post opened the bug reports folder " + opensAny(mark, dir) + " times, not once");
        } finally {
            Mod.staticCall("bugreport.BugReportFeature", "setFillerForTest", (Object) null);
            Mod.staticCall("bugreport.BugReportPost", "setTokenForTest", (Object) null);
            Mod.staticCall("bugreport.BugReportPost", "setPartSizeForTest", 0);
            c.onClient(mc -> {
                mc.setScreen(null);
                return null;
            });
        }
    }
}

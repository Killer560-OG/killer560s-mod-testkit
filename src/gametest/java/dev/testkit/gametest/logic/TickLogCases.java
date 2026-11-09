package dev.testkit.gametest.logic;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 1105-logic-ticklog / 1105-logic-ticklog-off (mod branch ticklog-1105; killer560, 2026-11-05: "add a logger for all tick
 * related timers for just my instance. It can go off of stuff like mask procs to tell when one actually goes off").
 * {@code util.TickAudit} runs only where the game folder holds the marker {@code killer560smod-ticklog}.
 *
 * <p>With the marker: Goldor's line starts Goldor's tick on a hypixel.net-paced clock; the case drives ticks until the
 * audit logs "Goldor tick reached 0", three ticks later a Spirit Mask proc arrives (the truth), and the TRUE line must read
 * +3 server ticks off. Then Oruo's "answered" line predicts the next question line in 40 ticks, it comes after 38, and the
 * TRUE line must read -2. The lines must also be in the killer560-ticklog/ file, and ending the world writes a summary
 * naming both timers. Without the marker the same run logs nothing and writes nothing.
 */
final class TickLogCases {

    private static final String CLOCK = "util.PerTickServerClock";
    private static final String AUDIT = "util.TickAudit";
    private static final String TT = "ticktimers.TickTimersFeature";
    private static final String GT = "ticktimers.GoldorTick";
    private static final String QUIZ = "puzzlesolvers.QuizState";
    private static final String PROC = "Second Wind Activated! Your Spirit Mask saved your life!";

    private TickLogCases() {
    }

    private static Path marker() {
        return FabricLoader.getInstance().getGameDir().resolve("killer560smod-ticklog");
    }

    private static void tick() {
        Mod.staticCall(CLOCK, "pingForTestTimed");
        Mod.staticCall(CLOCK, "tickForTest");
        Mod.staticCall(GT, "endOfTick");
    }

    private static List<String> audit(long mark) {
        return LogTap.since(mark).stream().filter(l -> l.contains("(ticklog)")).toList();
    }

    /** Goldor's tick to its first 0 plus 3, a mask proc; Quiz's next line 2 early. Returns the ticks driven. */
    private static int drive(long mark, boolean expectLines) {
        Object simWas = R.get("secrets.DungeonState", "simOverrideActive");
        boolean gateWas = (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled");
        Mod.staticCall("util.SkyblockGate", "setEnabled", false);
        R.set("secrets.DungeonState", "simOverrideActive", true);
        int driven = 0;
        try (AutoCloseable on = Mod.with("ticktimers.TickTimersConfig", "Enabled", true);
             AutoCloseable g = Mod.with("ticktimers.TickTimersConfig", "GoldorTimer", true)) {
            Mod.staticCall(CLOCK, "hypixelForTest", Boolean.TRUE);
            Mod.staticCall(CLOCK, "untrustForTest");
            for (int i = 0; i < 20; i++) {
                tick();
            }
            Mod.staticCall(TT, "resetAll");
            Mod.staticCall(TT, "onChatMessage", Component.literal("[BOSS] Goldor: Who dares trespass into my domain?"));
            // Without the marker no line comes, so drive the same 3 ticks past Goldor's first predicted hit (line+42).
            int zeroAt = -1;
            for (int i = 0; i < 80 && zeroAt < 0; i++) {
                tick();
                driven++;
                boolean zero = expectLines ? audit(mark).stream().anyMatch(l -> l.contains("Goldor tick reached 0"))
                        : (Integer) Mod.staticCall(GT, "remaining") == 0;
                if (zero) {
                    zeroAt = i;
                }
            }
            for (int i = 0; i < 3; i++) {
                tick();
            }
            Mod.staticCall(TT, "onChatMessage", Component.literal(PROC));
            Mod.staticCall(GT, "endOfTick");
            Mod.staticCall(TT, "resetAll");
            Mod.staticCall(QUIZ, "onMessage", Component.literal("[STATUE] Oruo the Omniscient: Steve answered Question #1 correctly!"));
            for (int i = 0; i < 38; i++) {
                tick();
            }
            Mod.staticCall(QUIZ, "onMessage", Component.literal("[STATUE] Oruo the Omniscient: 2 questions left... Then you will have proven your worth to me!"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            Mod.staticCall(QUIZ, "reset", "testkit");
            Mod.staticCall(CLOCK, "hypixelForTest", (Object) null);
            Mod.staticCall(CLOCK, "untrustForTest");
            R.set("secrets.DungeonState", "simOverrideActive", simWas);
            Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas);
        }
        return driven;
    }

    static void ticklog(LogicCase c) throws Exception {
        LogTap.install();
        Path marker = marker();
        Files.writeString(marker, "");
        try {
            Mod.staticCall(AUDIT, "refresh");
            c.check("marker turns the audit on", (Boolean) Mod.staticCall(AUDIT, "on"));
            long mark = LogTap.mark();
            int driven = drive(mark, true);
            List<String> lines = audit(mark);
            c.note("audit lines: " + lines.size() + " (Goldor's 0 after " + driven + " ticks)");
            for (String l : lines) {
                c.note(l.length() > 300 ? l.substring(0, 300) : l);
            }
            String zero = lines.stream().filter(l -> l.contains("Goldor tick reached 0")).findFirst().orElse("");
            c.check("Goldor tick reached 0 logged with every clock", zero.contains("server tick") && zero.contains("wall ")
                    && zero.contains("client tick") && zero.contains("game time") && zero.contains("trusted")
                    && zero.contains("hypixel.net chain") && zero.contains("wall credit"), zero);
            String goldor = lines.stream().filter(l -> l.contains("Goldor tick TRUE (a mask proc")).findFirst().orElse("");
            c.check("mask proc 3 ticks after Goldor's 0 reads +3", goldor.contains(": +3 server ticks,"), goldor);
            c.check("the TRUE line carries ms and game ticks", goldor.contains(" ms (") && goldor.contains("game ticks off"), goldor);
            String quiz = lines.stream().filter(l -> l.contains("Quiz next line TRUE")).findFirst().orElse("");
            c.check("Quiz next line predicted in 40, came at 38: reads -2", quiz.contains(": -2 server ticks,"), quiz);
            c.check("Quiz prediction logged", lines.stream().anyMatch(l -> l.contains("Quiz next line predicted in 40 ticks")));
            Path file = (Path) Mod.staticCall(AUDIT, "fileForTest");
            String text = file == null ? "" : Files.readString(file);
            c.check("lines written to killer560-ticklog/", file != null
                    && file.getParent().getFileName().toString().equals("killer560-ticklog")
                    && text.contains("Goldor tick TRUE (a mask proc") && text.contains("Quiz next line TRUE"), String.valueOf(file));
            long sum = LogTap.mark();
            Mod.staticCall(AUDIT, "endWorldForTest");
            String summary = audit(sum).stream().filter(l -> l.contains("world summary")).findFirst().orElse("");
            c.check("world summary names both timers with their offsets", summary.contains("Goldor tick (")
                    && summary.contains("Quiz next line (") && summary.contains("mean -2.0 / max 2 ticks"), summary);
        } finally {
            Files.deleteIfExists(marker);
            Mod.staticCall(AUDIT, "refresh");
        }
    }

    static void ticklogOff(LogicCase c) throws Exception {
        LogTap.install();
        Files.deleteIfExists(marker());
        Mod.staticCall(AUDIT, "refresh");
        c.check("no marker: audit off", !(Boolean) Mod.staticCall(AUDIT, "on"));
        Path file = (Path) Mod.staticCall(AUDIT, "fileForTest");
        long before = file == null ? -1 : Files.size(file);
        long mark = LogTap.mark();
        int driven = drive(mark, false);
        c.check("the run drove Goldor's tick to 0 (it acted)", driven > 0 && driven < 80, "driven " + driven);
        Mod.staticCall(AUDIT, "endWorldForTest");
        List<String> lines = audit(mark);
        c.check("no ticklog line without the marker", lines.isEmpty(), String.join(" | ", lines));
        Path after = (Path) Mod.staticCall(AUDIT, "fileForTest");
        c.check("no file line without the marker", after == file && (after == null || Files.size(after) == before),
                file + " " + before + " -> " + after);
        c.check("Goldor still logged its own hit (the run happened)", LogTap.since(mark).stream()
                .anyMatch(l -> l.contains("Goldor hit (a mask proc)")));
    }
}

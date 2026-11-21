package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 1119-logic-hidden-process (mod modconwin-1119): console children the game starts (Spotify's reader, Copy Screenshot,
 * ffmpeg, reg.exe) run under {@code conhost.exe --headless} so no command prompt window can appear. Asserts the mod's
 * {@code util.HiddenProcess} builds that command line and that its {@code run} still hands back the child's exit code and
 * output (the headless host drops both on its own). A non-Windows host runs the plain launch and only the output check applies.
 */
final class HiddenProcessCases {

    private static final String CLS = "util.HiddenProcess";

    private HiddenProcessCases() {
    }

    @SuppressWarnings("unchecked")
    static void headless(LogicCase c) throws Exception {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> cmd = (List<String>) Mod.staticCall(CLS, "headless", List.of("powershell.exe", "-NoProfile"));
        if (win) {
            c.check("the command line starts with conhost.exe --headless", cmd.size() == 4
                    && cmd.get(0).toLowerCase().endsWith("conhost.exe") && cmd.get(1).equals("--headless")
                    && cmd.get(2).equals("powershell.exe"), String.valueOf(cmd));
        } else {
            c.eq("no conhost here: the command is unchanged", List.of("powershell.exe", "-NoProfile"), cmd);
        }
        Path log = Files.createTempFile("modconwin-case-", ".log");
        try {
            List<String> script = win
                    ? List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "Write-Output 'hello hidden'; exit 7")
                    : List.of("sh", "-c", "echo 'hello hidden'; exit 7");
            Object exit = Mod.staticCall(CLS, "run", script, Map.<String, String>of(), log, 120_000L);
            String out = Files.readString(log);
            c.eq("the child's exit code comes back", 7, exit);
            c.check("the child's output comes back", out.contains("hello hidden"), out);
        } finally {
            Files.deleteIfExists(log);
        }
        c.note("headless() builds conhost --headless; run() returned exit 7 and the output of a real child");
    }
}

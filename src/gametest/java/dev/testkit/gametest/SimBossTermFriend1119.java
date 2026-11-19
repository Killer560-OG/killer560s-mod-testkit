package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.testkit.gametest.SimBossTests.call;
import static dev.testkit.gametest.SimBossTests.expect;
import static dev.testkit.gametest.SimBossTests.println;
import static dev.testkit.gametest.SimBossTests.start;
import static dev.testkit.gametest.SimBossTests.until;
import static dev.testkit.gametest.SimBossTests.waitChat;

/**
 * {@code 1119-boss-term-click-limit} (friendterms-1119, killer560 2026-11-18: a friend's Auto Terminals "dont always work" in
 * the sim). Hypixel takes at most 5 terminal clicks in 10 ticks and drops the rest without an answer, and the sim does the
 * same. With the friend's settings (Min/Max 90-120 ms, First Click Delay 0, Burst Finish at 5) Auto Terminals sent a 6th
 * click inside the window on every board, the sim dropped it, and the terminal waited a retry timeout per lost click. Each
 * S1 terminal is opened with a real stand click and must be SOLVED by Auto Terminals (the sim's solved count, its "activated a
 * terminal!" line) with no click over the limit (the sim's own "N over the click limit" on its solved line).
 */
final class SimBossTermFriend1119 {

    static final String NAME = "1119-boss-term-click-limit";
    private static final String TERMS = "com.killer560.hub.bosssim.BossTerminals";
    private static final String STATIONS = "com.killer560.hub.bosssim.BossStations";
    private static final String TERM_CFG = "com.killer560.hub.terminals.TerminalSolverConfig";
    private static final Pattern SOLVED = Pattern.compile("terminal S1 (T\\d) solved \\((\\w+) after (\\d+) click\\(s\\), (\\d+) over the click limit\\)");

    private SimBossTermFriend1119() {
    }

    static void run(ClientGameTestContext ctx, List<String> f) {
        start(ctx, "S1", true);
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(TERM_CFG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setAutoTerminalsEnabled", true);
            for (String s : new String[]{"Panes", "Rubix", "Numbers", "StartsWith", "Select", "Melody"}) {
                ModUnderTest.set(cfg, "setAuto" + s + "Enabled", true);
                ModUnderTest.set(cfg, "set" + s + "Enabled", true);
            }
            // The friend's terminal settings (his bug report, 2026-11-19 00:07).
            ModUnderTest.set(cfg, "setAutoClickMaxDelayMs", 120);
            ModUnderTest.set(cfg, "setAutoClickMinDelayMs", 90);
            ModUnderTest.set(cfg, "setFirstClickDelayMs", 0);
            ModUnderTest.set(cfg, "setBurstFinish", true);
            ModUnderTest.set(cfg, "setBurstSize", 5);
            ModUnderTest.set(cfg, "setBurstMode", false);
            ModUnderTest.set(cfg, "setAutoStreamSafe", false);
            ModUnderTest.set(cfg, "setQueueTerms", false);
            ModUnderTest.set(cfg, "setHoverTerminalsEnabled", false);
            ModUnderTest.set(cfg, "setHoldToClickEnabled", false);
            ModUnderTest.set(cfg, "setCustomGuiEnabled", true);
            ModUnderTest.set(cfg, "setHideMouseCursor", true);
        });
        ctx.waitTicks(5);
        String[] types = {"NUMBERS", "PANES", "RUBIX", "SELECT", "STARTS_WITH"};
        int over = 0;
        int boards = 0;
        int t = 0;
        for (String label : new String[]{"T1", "T2", "T3", "T4", "T5"}) {
            if (ModUnderTest.staticCall(STATIONS, "position", new Class<?>[]{int.class, String.class}, new Object[]{1, label}) == null) {
                continue;
            }
            String type = types[t++ % types.length];
            System.setProperty("killer560.bosssim.terminal", type);
            long mark = LogTap.mark();
            int solvedBefore = (Integer) call(TERMS, "solvedCount");
            int openedBefore = (Integer) call(TERMS, "openedCount");
            if (!SimBossTests.clickTerminal(ctx, 1, label, "Inactive Terminal")
                    || !until(ctx, label + " opened", mc -> (Integer) call(TERMS, "openedCount") > openedBefore, 20)) {
                f.add("S1 " + label + " never opened");
                continue;
            }
            long openedAt = System.currentTimeMillis();
            boolean solved = until(ctx, label + " solved", mc -> (Integer) call(TERMS, "solvedCount") > solvedBefore, 600);
            long ms = System.currentTimeMillis() - openedAt;
            if (!solved) {
                f.add("S1 " + label + " (" + type + "): Auto Terminals never solved it in 30 s");
                ctx.runOnClient(mc -> {
                    if (mc.player.containerMenu != mc.player.inventoryMenu) {
                        mc.player.closeContainer();
                    }
                });
                SimBossTests.serverRun(ctx, s -> ModUnderTest.staticCall(STATIONS, "testComplete",
                        new Class<?>[]{MinecraftServer.class, int.class, String.class}, new Object[]{s, 1, label}));
                continue;
            }
            if (!waitChat(ctx, mark, "activated a terminal!", 20)) {
                f.add("S1 " + label + ": solved but no 'activated a terminal!' line");
            }
            boards++;
            String line = null;
            for (String l : LogTap.since(mark)) {
                if (l.contains("terminal S1 " + label + " solved")) {
                    line = l;
                }
            }
            Matcher m = line == null ? null : SOLVED.matcher(line);
            if (m == null || !m.find()) {
                f.add("S1 " + label + ": no sim 'solved' line to read the click limit from");
                continue;
            }
            int limited = Integer.parseInt(m.group(4));
            over += limited;
            println(NAME, String.format(Locale.ROOT, "S1 %s %s solved in %d ms, %s click(s), %d over the click limit",
                    label, m.group(2), ms, m.group(3), limited));
        }
        System.clearProperty("killer560.bosssim.terminal");
        expect(f, "boards solved by Auto Terminals", boards >= 4, true);
        expect(f, "clicks over Hypixel's 5-in-10-ticks limit (dropped by the server)", over, 0);
    }
}

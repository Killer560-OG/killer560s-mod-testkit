package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import dev.testkit.compat.McCompat;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * <b>1117-sim-help</b> (mod simhelp-1117, killer560 2026-11-17: "in our sim if they run /help have it open a custom menu that
 * helps walk them through how the sim works"). Inside a real (empty) sim world: the first-entry hint line was sent and its flag
 * saved; {@code /help} opens SimHelpScreen (not a chat line, not the server); every page lays out with no text past its box at
 * 1920x1080 GUI 1-4 and 854x480 GUI 2 (a page taller than its box must scroll); every command root the sim registers (found by
 * comparing the live dispatcher with the sim flag on and off) has a guide row, and every row's root is in the dispatcher; Esc
 * closes. Outside the sim /help is not registered for use and opens nothing of ours. Screenshots of page 1 and the Commands page
 * go to killer560s-mod-logs/simhelp-1117/ on a cheat run.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimHelpTests implements FabricClientGameTest {

    static final String NAME = "1117-sim-help";
    private static final String HELP = "com.killer560.hub.roomsim.SimHelp";
    private static final String SCREEN = "com.killer560.hub.roomsim.SimHelpScreen";
    private static final String STATE = "com.killer560.hub.roomsim.SimState";
    private static final String PREFS = "com.killer560.hub.roomsim.SimMenuPrefs";
    private static final Path SHOTS = Path.of("C:/Users/Hunter/killer560s-mod-logs/simhelp-1117");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        List<String> problems = new ArrayList<>();
        List<String> facts = new ArrayList<>();
        Report.caseStarted(NAME);
        int[] oldWindow = ctx.computeOnClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.options.guiScale().get()});
        try {
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
            run(ctx, problems, facts);
        } catch (Throwable t) {
            problems.add("threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            try {
                setWindow(ctx, oldWindow[0], oldWindow[1], oldWindow[2]);
            } catch (Throwable ignored) {
            }
            SimMobFloorTests.leave(ctx);
        }
        for (String f : facts) {
            System.out.println("[" + NAME + "] FACT " + f);
        }
        Report.caseFinished(NAME, problems.isEmpty() ? "PASS" : "FAIL", "n/a (singleplayer)",
                problems.isEmpty() ? String.join("; ", facts) : String.join("; ", problems), List.of(), List.of());
        SuiteVerdict.finished(NAME);
        if (!problems.isEmpty()) {
            throw new AssertionError(NAME + ": " + String.join(" | ", problems));
        }
    }

    private void run(ClientGameTestContext ctx, List<String> problems, List<String> facts) throws Exception {
        // Outside the sim: not usable, and typing it opens nothing of ours.
        ctx.waitFor(mc -> McCompat.screen(mc) != null, 1200);
        boolean outsideUsable = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(STATE, "isActive"));
        check(problems, !outsideUsable, "the sim was already active before the test");
        // Enter the sim: an empty sim world from the title screen.
        ctx.runOnClient(mc -> {
            try {
                Object p = ModUnderTest.staticCall(PREFS, "cached");
                p.getClass().getField("helpHintShown").setBoolean(p, false);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        long mark = LogTap.mark();
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimWorld", "open",
                new Class<?>[]{net.minecraft.client.Minecraft.class, String.class}, new Object[]{mc, "gametest"})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimState", "canAct",
                new Class<?>[]{net.minecraft.client.Minecraft.class}, new Object[]{mc}), 600);
        ctx.waitTicks(80);
        boolean hintLine = LogTap.since(mark).stream().anyMatch(l -> l.contains("Type /help for a guide to the Dungeon Sim."));
        boolean flag = ctx.computeOnClient(mc -> {
            try {
                Object p = ModUnderTest.staticCall(PREFS, "cached");
                return p.getClass().getField("helpHintShown").getBoolean(p);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        check(problems, hintLine, "first entry did not say 'Type /help for a guide to the Dungeon Sim.'");
        check(problems, flag, "first-entry flag helpHintShown was not saved");
        facts.add("first entry: hint line " + hintLine + ", flag " + flag);

        // Command coverage: every root the sim turns on is in the guide, and every guide root exists.
        @SuppressWarnings("unchecked")
        List<String> covered = (List<String>) ModUnderTest.staticCall(HELP, "coveredRoots");
        Set<String> all = new TreeSet<>();
        Set<String> inSim = new TreeSet<>();
        Set<String> outSim = new TreeSet<>();
        ctx.runOnClient(mc -> {
            try {
                var d = (com.mojang.brigadier.CommandDispatcher<?>) Class.forName(
                        "net.fabricmc.fabric.impl.command.client.ClientCommandInternals").getMethod("getActiveDispatcher").invoke(null);
                roots(d, all, inSim);
                java.lang.reflect.Field f = Class.forName(STATE).getDeclaredField("active");
                f.setAccessible(true);
                f.setBoolean(null, false);
                try {
                    roots(d, new TreeSet<>(), outSim);
                } finally {
                    f.setBoolean(null, true);
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        Set<String> simOnly = new TreeSet<>(inSim);
        simOnly.removeAll(outSim);
        for (String r : simOnly) {
            check(problems, covered.contains(r), "sim command /" + r + " has no row in SimHelp (add one line to its Commands page)");
        }
        for (String r : covered) {
            check(problems, all.contains(r), "guide row names /" + r + " but the dispatcher has no such command");
        }
        check(problems, simOnly.size() >= 15, "only " + simOnly.size() + " sim-only commands found, the check did not act: " + simOnly);
        facts.add(simOnly.size() + " sim-only commands all covered (" + covered.size() + " roots in the guide)");

        // /help opens the guide.
        ctx.runOnClient(mc -> mc.player.connection.sendCommand("help"));
        ctx.waitFor(mc -> McCompat.screen(mc) != null && McCompat.screen(mc).getClass().getName().equals(SCREEN), 200);
        int pages = ctx.computeOnClient(mc -> (Integer) ModUnderTest.call(McCompat.screen(mc), "pageCount", new Class<?>[0], new Object[0]));
        check(problems, pages >= 8, "guide has only " + pages + " pages");
        facts.add("/help opened the guide, " + pages + " pages");

        int[][] sizes = {{1920, 1080, 1}, {1920, 1080, 2}, {1920, 1080, 3}, {1920, 1080, 4}, {854, 480, 2}};
        int scrolling = 0;
        for (int[] s : sizes) {
            setWindow(ctx, s[0], s[1], s[2]);
            // open it fresh at this size, as a player would (a harness resize does not re-run init)
            ctx.runOnClient(mc -> mc.execute(() -> {
                McCompat.setScreen(mc, null);
                ModUnderTest.staticCall(HELP, "open", new Class<?>[]{net.minecraft.client.Minecraft.class}, new Object[]{mc});
            }));
            ctx.waitTicks(4);
            for (int p = 0; p < pages; p++) {
                final int page = p;
                ctx.runOnClient(mc -> ModUnderTest.call(McCompat.screen(mc), "showPage", new Class<?>[]{int.class}, new Object[]{page}));
                ctx.waitTicks(2);
                String at = s[0] + "x" + s[1] + " GUI " + s[2] + " page " + (p + 1);
                int[] m = ctx.computeOnClient(mc -> {
                    Object sc = McCompat.screen(mc);
                    int[] te = (int[]) ModUnderTest.call(sc, "textExtent", new Class<?>[0], new Object[0]);
                    int[] ce = (int[]) ModUnderTest.call(sc, "contentExtent", new Class<?>[0], new Object[0]);
                    int[] pb = (int[]) ModUnderTest.call(sc, "themedPanelBounds", new Class<?>[0], new Object[0]);
                    int ms = (Integer) ModUnderTest.call(sc, "maxScroll", new Class<?>[0], new Object[0]);
                    int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
                    return new int[]{te[0], te[1], ce[0], ce[1], pb[0], pb[1], pb[2], pb[3], ms, sw, sh, (Integer) ModUnderTest.call(sc, "page", new Class<?>[0], new Object[0])};
                });
                check(problems, m[11] == page, at + ": showPage did not switch");
                check(problems, m[0] > 0 && m[0] <= m[1], at + ": page list text reaches " + m[0] + " past " + m[1]);
                check(problems, m[2] > 0 && m[2] <= m[3], at + ": content text reaches " + m[2] + " past " + m[3] + " (or none drawn)");
                check(problems, m[4] >= 0 && m[5] >= 0 && m[4] + m[6] <= m[9] && m[5] + m[7] <= m[10], at + ": panel " + m[4] + "," + m[5] + " " + m[6] + "x" + m[7] + " leaves the " + m[9] + "x" + m[10] + " screen");
                check(problems, m[5] + 36 + pages * 14 <= m[5] + m[7] - 26, at + ": page list runs into the buttons");
                if (m[8] > 0) {
                    scrolling++;
                }
                if (s[0] == 1920 && s[2] == 2 && (p == 0 || p == 3)) {
                    Path shot = ctx.takeScreenshot(dev.testkit.harness.Report.fileName(NAME + "-page" + (p + 1)));
                    Report.screenshot(NAME, shot);
                    if (dev.testkit.gametest.mod.Mod.isCheat() && System.getProperty("testkit.mc", "").isEmpty()) {
                        Files.createDirectories(SHOTS);
                        Files.copy(shot, SHOTS.resolve("page" + (p + 1) + ".png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        facts.add("every page fits at 1920x1080 GUI 1-4 and 854x480 GUI 2 (" + scrolling + " page views scroll)");

        // Esc closes.
        setWindow(ctx, oldW(ctx), oldH(ctx), 2);
        ctx.runOnClient(mc -> mc.execute(() -> McCompat.screen(mc).keyPressed(new net.minecraft.client.input.KeyEvent(256, 0, 0))));
        ctx.waitTicks(5);
        String after = ctx.computeOnClient(mc -> McCompat.screen(mc) == null ? "none" : McCompat.screen(mc).getClass().getName());
        check(problems, !after.equals(SCREEN), "Esc did not close the guide (" + after + ")");
        facts.add("Esc closed it (screen now " + after + ")");

        // Outside the sim /help opens nothing of ours.
        ctx.runOnClient(mc -> {
            try {
                java.lang.reflect.Field f = Class.forName(STATE).getDeclaredField("active");
                f.setAccessible(true);
                f.setBoolean(null, false);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        try {
            ctx.runOnClient(mc -> {
                try {
                    var d = (com.mojang.brigadier.CommandDispatcher<?>) Class.forName(
                            "net.fabricmc.fabric.impl.command.client.ClientCommandInternals").getMethod("getActiveDispatcher").invoke(null);
                    Set<String> usable = new TreeSet<>();
                    roots(d, new TreeSet<>(), usable);
                    if (usable.contains("help")) {
                        throw new AssertionError("/help is usable with the sim off");
                    }
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
        } catch (AssertionError e) {
            problems.add(e.getMessage());
        } finally {
            ctx.runOnClient(mc -> {
                try {
                    java.lang.reflect.Field f = Class.forName(STATE).getDeclaredField("active");
                    f.setAccessible(true);
                    f.setBoolean(null, true);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
        }
        facts.add("with the sim off /help is not usable");
    }

    private static int oldW(ClientGameTestContext ctx) {
        return 1920;
    }

    private static int oldH(ClientGameTestContext ctx) {
        return 1080;
    }

    /** Every root name into {@code all}; those whose requirement passes (a null source: the sim gates ignore it) into {@code usable}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void roots(com.mojang.brigadier.CommandDispatcher d, Set<String> all, Set<String> usable) {
        for (Object o : d.getRoot().getChildren()) {
            var node = (com.mojang.brigadier.tree.CommandNode) o;
            all.add(node.getName());
            try {
                if (node.getRequirement().test(null)) {
                    usable.add(node.getName());
                }
            } catch (RuntimeException ignored) {
                // a requirement that needs a real source is not a sim gate
            }
        }
    }

    private static void setWindow(ClientGameTestContext ctx, int w, int h, int gui) {
        ctx.getInput().resizeWindow(w, h);
        ctx.waitTicks(3);
        ctx.runOnClient(mc -> {
            mc.options.guiScale().set(gui);
            mc.resizeGui();
            var sc = McCompat.screen(mc);
            if (sc != null) {
                sc.resize(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
            }
        });
        ctx.waitTicks(6);
    }

    private static void check(List<String> problems, boolean ok, String problem) {
        if (!ok && problems.size() < 40 && !problems.contains(problem)) {
            problems.add(problem);
        }
    }
}

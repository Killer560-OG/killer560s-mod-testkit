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
import java.util.Map;
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

        // The harness window resize does not change a Screen's own size (it stays 854x480), so each size is given to the
        // screen the way vanilla does (Screen.resize(w, h)) and measured from its layout, with no render involved.
        // simhelp-polish-1118: Close bottom left, Back then Next bottom right, the page count on the panel's center, nothing overlapping.
        // Vanilla never scales a window below 320x240 (Window.calculateScale), so 640x480 GUI 2 is the smallest real canvas;
        // 854x480 GUI 4 (213x120) cannot happen and was dropped.
        int[][] sizes = {{1920, 1080, 1}, {1920, 1080, 2}, {1920, 1080, 3}, {1920, 1080, 4}, {854, 480, 2}, {640, 480, 2}};
        int scrolling = 0;
        int checked = 0;
        for (int[] s : sizes) {
            final int sw = s[0] / s[2], sh = s[1] / s[2];
            for (int p = 0; p < pages; p++) {
                final int page = p;
                String at = s[0] + "x" + s[1] + " GUI " + s[2] + " (" + sw + "x" + sh + ") page " + (p + 1);
                Object[] got = ctx.computeOnClient(mc -> {
                    var sc = McCompat.screen(mc);
                    // Auto Scale lays a mod screen out on a canvas F times the size it is given (AutoScaleScreenMixin): find F, undo it
                    sc.resize(sw, sh);
                    double k = sc.width / (double) sw;
                    sc.resize((int) Math.round(sw / k), (int) Math.round(sh / k));
                    ModUnderTest.call(sc, "showPage", new Class<?>[]{int.class}, new Object[]{page});
                    int[] ce = (int[]) ModUnderTest.call(sc, "contentExtent", new Class<?>[0], new Object[0]);
                    int ms = (Integer) ModUnderTest.call(sc, "maxScroll", new Class<?>[0], new Object[0]);
                    int pg = (Integer) ModUnderTest.call(sc, "page", new Class<?>[0], new Object[0]);
                    @SuppressWarnings("unchecked")
                    Map<String, int[]> boxes = new java.util.LinkedHashMap<>((Map<String, int[]>) ModUnderTest.call(sc, "layoutBoxes", new Class<?>[0], new Object[0]));
                    return new Object[]{boxes, new int[]{ce[0], ce[1], ms, pg, sc.width, sc.height}};
                });
                @SuppressWarnings("unchecked")
                Map<String, int[]> b = (Map<String, int[]>) got[0];
                int[] m = (int[]) got[1];
                check(problems, m[3] == page, at + ": showPage did not switch");
                check(problems, m[0] > 0 && m[0] <= m[1], at + ": content text reaches " + m[0] + " past " + m[1] + " (or none laid out)");
                int[] panel = b.get("panel"), close = b.get("close"), back = b.get("back"), next = b.get("next"), label = b.get("label"), well = b.get("well"), text = b.get("text");
                if (panel == null || close == null || back == null || next == null || label == null || well == null || text == null) {
                    check(problems, false, at + ": layoutBoxes is missing a box: " + b.keySet());
                    continue;
                }
                checked++;
                check(problems, panel[0] >= 0 && panel[1] >= 0 && panel[0] + panel[2] <= m[4] && panel[1] + panel[3] <= m[5] && Math.abs(m[4] - sw) <= 2,
                        at + ": panel " + str(panel) + " leaves the " + m[4] + "x" + m[5] + " canvas (asked " + sw + ")");
                check(problems, close[0] + close[2] <= back[0], at + ": Close " + str(close) + " is not left of Back " + str(back));
                check(problems, back[0] + back[2] <= next[0], at + ": Back " + str(back) + " is not left of Next " + str(next));
                check(problems, close[0] < panel[0] + panel[2] / 2 && next[0] > panel[0] + panel[2] / 2, at + ": Close not on the left half or Next not on the right half");
                int rightmost = 0;
                for (var e : b.entrySet()) {
                    if (!e.getKey().equals("panel") && !e.getKey().equals("well") && !e.getKey().equals("text") && !e.getKey().startsWith("pic")) {
                        rightmost = Math.max(rightmost, e.getValue()[0] + e.getValue()[2]);
                    }
                }
                check(problems, next[0] + next[2] == rightmost && next[0] + next[2] <= panel[0] + panel[2], at + ": Next is not the rightmost control (" + str(next) + ", rightmost " + rightmost + ")");
                int labelCx2 = 2 * label[0] + label[2], panelCx2 = 2 * panel[0] + panel[2];
                check(problems, Math.abs(labelCx2 - panelCx2) <= 2, at + ": page label center " + labelCx2 / 2.0 + " is not the panel center " + panelCx2 / 2.0);
                check(problems, Math.abs((2 * label[1] + label[3]) - (2 * next[1] + next[3])) <= 2, at + ": page label is not centered on the button row");
                check(problems, close[1] == back[1] && back[1] == next[1], at + ": Close, Back and Next are not on one row");
                for (String k : List.of("close", "back", "next")) {
                    check(problems, !overlap(label, b.get(k)), at + ": page label " + str(label) + " overlaps " + k + " " + str(b.get(k)));
                    check(problems, well[1] + well[3] <= b.get(k)[1], at + ": the well runs into " + k);
                }
                int pics = 0;
                List<int[]> items = new ArrayList<>();
                for (var e : b.entrySet()) {
                    if (e.getKey().startsWith("page")) items.add(e.getValue());
                    if (e.getKey().startsWith("pic")) {
                        int[] pic = e.getValue();
                        pics++;
                        // A picture is readable: over 20 tall, or as wide as the text allows (a wide strip such as the chat line).
                        check(problems, pic[2] > 20 && (pic[3] > 20 || pic[2] >= text[2] - 2) && pic[0] >= text[0] && pic[0] + pic[2] <= text[0] + text[2] + 1,
                                at + ": " + e.getKey() + " " + str(pic) + " is outside the text area " + str(text));
                    }
                }
                check(problems, pics >= 1, at + ": no picture on the page");
                check(problems, items.size() == pages, at + ": " + items.size() + " page entries, not " + pages);
                for (int i = 0; i < items.size(); i++) {
                    check(problems, !overlap(items.get(i), close), at + ": page entry " + (i + 1) + " " + str(items.get(i)) + " overlaps Close " + str(close));
                    if (i > 0) check(problems, !overlap(items.get(i), items.get(i - 1)), at + ": page entries " + i + " and " + (i + 1) + " overlap");
                }
                for (int i = 0; i < pics; i++) {
                    for (int j = i + 1; j < pics; j++) {
                        check(problems, !overlap(b.get("pic" + i), b.get("pic" + j)), at + ": pictures " + i + " and " + j + " overlap");
                    }
                }
                if (m[2] > 0) {
                    scrolling++;
                }
            }
        }
        check(problems, checked == sizes.length * pages, "only " + checked + " of " + sizes.length * pages + " layouts were checked");
        facts.add("buttons, label center and no overlaps on " + checked + " layouts at 1920x1080 GUI 1-4, 854x480 GUI 2 and 640x480 GUI 2 (" + scrolling + " page views scroll)");

        // The real window: every page draws its pictures (ready within 10 s, real pixels in each box), and the accent follows the theme.
        // A fresh screen opened by /help: the size loop's hand resizes leave the old one's layout off the drawn frame.
        ctx.runOnClient(mc -> McCompat.setScreen(mc, null));
        setWindow(ctx, 1280, 720, 2);
        ctx.runOnClient(mc -> mc.player.connection.sendCommand("help"));
        ctx.waitFor(mc -> McCompat.screen(mc) != null && McCompat.screen(mc).getClass().getName().equals(SCREEN), 200);
        ctx.waitTicks(4);
        int pictures = 0;
        for (int p = 0; p < pages; p++) {
            final int page = p;
            ctx.runOnClient(mc -> {
                var sc = McCompat.screen(mc);
                ModUnderTest.call(sc, "showPage", new Class<?>[]{int.class}, new Object[]{page});
                for (int i = 0; i < 80; i++) {
                    sc.mouseScrolled(1, 1, 0, -1); // to the bottom, where the pictures are
                }
            });
            int want = ctx.computeOnClient(mc -> (int) ((Map<?, ?>) ModUnderTest.call(McCompat.screen(mc), "layoutBoxes", new Class<?>[0], new Object[0]))
                    .keySet().stream().filter(k -> k.toString().startsWith("pic")).count());
            long t0 = System.currentTimeMillis();
            boolean ready = false;
            while (System.currentTimeMillis() - t0 < 10_000) {
                ctx.waitTicks(2);
                int shown = ctx.computeOnClient(mc -> (Integer) ModUnderTest.call(McCompat.screen(mc), "picturesShown", new Class<?>[0], new Object[0]));
                if (shown == want) {
                    ready = true;
                    break;
                }
            }
            long took = System.currentTimeMillis() - t0;
            check(problems, ready && want > 0, "page " + (p + 1) + ": " + want + " picture(s) not all drawn within 10 s");
            ctx.waitTicks(2);
            Path shot = ctx.takeScreenshot(dev.testkit.harness.Report.fileName(NAME + "-page" + (p + 1)));
            Report.screenshot(NAME, shot);
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(shot.toFile());
            Object[] lay = picBoxes(ctx);
            double f = img.getWidth() / (double) (Integer) lay[1];
            for (int[] pic : castBoxes(lay[0])) {
                int colors = distinctColors(img, pic, f);
                check(problems, colors >= 24, "page " + (p + 1) + ": picture " + str(pic) + " has only " + colors + " colors on screen (not drawn)");
                pictures++;
            }
            facts.add("page " + (p + 1) + ": " + want + " picture(s) drawn after " + took + " ms");
        }
        check(problems, pictures >= pages, "only " + pictures + " pictures measured on " + pages + " pages");

        // Theme: the selected page entry's accent strip changes color from Amber to Ocean; Dark Mode darkens the panel.
        int[] amber = accentAndPanel(ctx, "AMBER", false);
        int[] ocean = accentAndPanel(ctx, "OCEAN", false);
        int[] dark = accentAndPanel(ctx, "AMBER", true);
        accentAndPanel(ctx, "AMBER", false);
        check(problems, colorDistance(amber[0], ocean[0]) > 60, "the accent strip did not follow the theme: Amber " + Integer.toHexString(amber[0]) + ", Ocean " + Integer.toHexString(ocean[0]));
        check(problems, brightness(dark[1]) < brightness(amber[1]), "Dark Mode did not darken the panel: " + Integer.toHexString(amber[1]) + " -> " + Integer.toHexString(dark[1]));
        facts.add("accent strip Amber " + Integer.toHexString(amber[0]) + " / Ocean " + Integer.toHexString(ocean[0]) + "; panel " + Integer.toHexString(amber[1]) + " / Dark Mode " + Integer.toHexString(dark[1]));

        // Esc closes.
        boolean[] handled = new boolean[1];
        // 26.3's KeyboardHandler closes a screen on Esc itself (Screen.keyPressed returns false for it): it asks shouldCloseOnEsc, then onClose
        ctx.runOnClient(mc -> {
            handled[0] = McCompat.screen(mc).shouldCloseOnEsc();
            if (handled[0]) {
                McCompat.screen(mc).onClose();
            }
        });
        ctx.waitTicks(5);
        String after = ctx.computeOnClient(mc -> McCompat.screen(mc) == null ? "none" : McCompat.screen(mc).getClass().getName());
        check(problems, !after.equals(SCREEN), "Esc did not close the guide (" + after + ", shouldCloseOnEsc " + handled[0] + ")");
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

    private static String str(int[] r) {
        return r == null ? "none" : r[0] + "," + r[1] + " " + r[2] + "x" + r[3];
    }

    private static boolean overlap(int[] a, int[] b) {
        return a != null && b != null && a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
    }

    /** {the open screen's picture boxes (List of int[]), its width}. */
    private static Object[] picBoxes(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            var sc = McCompat.screen(mc);
            List<int[]> out = new ArrayList<>();
            for (var e : ((Map<?, ?>) ModUnderTest.call(sc, "layoutBoxes", new Class<?>[0], new Object[0])).entrySet()) {
                if (e.getKey().toString().startsWith("pic")) out.add((int[]) e.getValue());
            }
            return new Object[]{out, sc.width};
        });
    }

    @SuppressWarnings("unchecked")
    private static List<int[]> castBoxes(Object o) {
        return (List<int[]>) o;
    }

    /** Distinct colors (low 4 bits per channel dropped) on a 24x24 grid inside a box given in screen units, {@code f} pixels per unit. */
    private static int distinctColors(java.awt.image.BufferedImage img, int[] box, double f) {
        Set<Integer> seen = new java.util.HashSet<>();
        for (int i = 1; i < 24; i++) {
            for (int j = 1; j < 24; j++) {
                int x = (int) ((box[0] + box[2] * i / 24.0) * f), y = (int) ((box[1] + box[3] * j / 24.0) * f);
                if (x >= 0 && y >= 0 && x < img.getWidth() && y < img.getHeight()) seen.add(img.getRGB(x, y) & 0xF0F0F0);
            }
        }
        return seen.size();
    }

    /** With {@code theme} and Dark Mode {@code dark}: {page 1's accent strip pixel, a panel pixel}, read off a screenshot. */
    private static int[] accentAndPanel(ClientGameTestContext ctx, String theme, boolean dark) throws Exception {
        Object[] lay = ctx.computeOnClient(mc -> {
            try {
                Object cfg = ModUnderTest.config("com.killer560.hub.gui.MenuConfig");
                ModUnderTest.call(cfg, "setTheme", new Class<?>[]{Class.forName("com.killer560.hub.gui.MenuTheme")},
                        new Object[]{ModUnderTest.enumValue("com.killer560.hub.gui.MenuTheme", theme)});
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            Object dm = dev.testkit.gametest.mod.Mod.cfg("darkmode.DarkModeConfig");
            dev.testkit.gametest.mod.Mod.call(dm, "setEnabled", dark);
            if (dark) dev.testkit.gametest.mod.Mod.call(dm, "setOpacity", 50);
            var sc = McCompat.screen(mc);
            sc.resize(sc.width, sc.height);
            ModUnderTest.call(sc, "showPage", new Class<?>[]{int.class}, new Object[]{0});
            Map<?, ?> b = (Map<?, ?>) ModUnderTest.call(sc, "layoutBoxes", new Class<?>[0], new Object[0]);
            return new Object[]{b.get("page0"), b.get("panel"), sc.width};
        });
        ctx.waitTicks(4);
        Path shot = ctx.takeScreenshot(dev.testkit.harness.Report.fileName(NAME + "-theme-" + theme + (dark ? "-dark" : "")));
        Report.screenshot(NAME, shot);
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(shot.toFile());
        double f = img.getWidth() / (double) (Integer) lay[2];
        int[] item = (int[]) lay[0], panel = (int[]) lay[1];
        int strip = img.getRGB((int) ((item[0] + 2) * f), (int) ((item[1] + item[3] / 2.0) * f));
        int bg = img.getRGB((int) ((panel[0] + panel[2] - 5) * f), (int) ((panel[1] + panel[3] / 2.0) * f));
        return new int[]{strip, bg};
    }

    private static int colorDistance(int a, int b) {
        return Math.abs((a >> 16 & 255) - (b >> 16 & 255)) + Math.abs((a >> 8 & 255) - (b >> 8 & 255)) + Math.abs((a & 255) - (b & 255));
    }

    private static int brightness(int c) {
        return (c >> 16 & 255) + (c >> 8 & 255) + (c & 255);
    }

    private static void check(List<String> problems, boolean ok, String problem) {
        if (!ok && problems.size() < 40 && !problems.contains(problem)) {
            problems.add(problem);
        }
    }
}

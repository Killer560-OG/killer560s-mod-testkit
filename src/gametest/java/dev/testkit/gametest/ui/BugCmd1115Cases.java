package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** 1115-ui-bugcmd (bugcmd-1115; bugreportmenu-1122): /bugreport and /logreport are client roots and each opens the Bug
 *  Report screen; "/bugreport &lt;text&gt;" puts the text in its Title box and Save writes the zip. */
final class BugCmd1115Cases {
    private BugCmd1115Cases() {
    }

    static void bugCmd(UiCase c) throws Exception {
        List<String> roots = c.onClient(mc -> {
            var d = net.fabricmc.fabric.impl.command.client.ClientCommandInternals.getActiveDispatcher();
            return d == null ? List.<String>of() : d.getRoot().getChildren().stream().map(n -> n.getName()).toList();
        });
        for (String r : List.of("bugreport", "logreport")) {
            c.check(roots.contains(r), "/" + r + " is not a client root");
        }
        Path dir = (Path) Mod.staticCall("util.ModPaths", "config", "bug-reports");
        for (String r : List.of("bugreport", "logreport")) {
            long before = count(dir);
            var s = BugReportMenu1122Cases.open(c, r);
            c.ticks(10);
            c.check(count(dir) == before, "/" + r + " wrote a zip before Save was clicked");
            if (s != null) {
                BugReportMenu1122Cases.close(c);
            }
        }
        contents(c, dir);
    }

    /** gardencopper-1121: with a known sidebar and tab line, "/bugreport <note>" writes note.txt, the escaped sidebar, the tab
     *  info line, the island, and what the Copper reader parsed. */
    private static void contents(UiCase c, Path dir) throws Exception {
        c.onClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                var sb = server.getScoreboard();
                var obj = sb.addObjective("k560bugrep", net.minecraft.world.scores.criteria.ObjectiveCriteria.DUMMY,
                        net.minecraft.network.chat.Component.literal("SKYBLOCK"),
                        net.minecraft.world.scores.criteria.ObjectiveCriteria.RenderType.INTEGER, false, null);
                String[] lines = {"§fCopper: §c1,234", "§fPurse: §699,000"};
                for (int i = 0; i < lines.length; i++) {
                    String owner = "§" + Integer.toHexString(i) + "§r";
                    var team = sb.addPlayerTeam("k560bugrep" + i);
                    team.setPlayerPrefix(net.minecraft.network.chat.Component.literal(lines[i]));
                    sb.addPlayerToTeam(owner, team);
                    sb.getOrCreatePlayerScore(net.minecraft.world.scores.ScoreHolder.forNameOnly(owner), obj).set(lines.length - i);
                }
                sb.setDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR, obj);
            });
            mc.getConnection().getListedOnlinePlayers().iterator().next()
                    .setTabListDisplayName(net.minecraft.network.chat.Component.literal("Area: Garden"));
            return null;
        });
        c.ticks(6);
        long start = System.currentTimeMillis();
        Thread.sleep(1100);
        var screen = BugReportMenu1122Cases.open(c, "bugreport the copper line is missing");
        if (screen == null) {
            return;
        }
        String typed = BugReportMenu1122Cases.field(c, screen, "titleText");
        c.check("the copper line is missing".equals(typed), "the Title box does not hold the command's text: " + typed);
        InvBatchCases.clickWidget(c, screen, BugReportMenu1122Cases.button(c, screen, "Save"));
        String status = BugReportMenu1122Cases.waitDone(c, screen);
        c.note("Save: " + status);
        BugReportMenu1122Cases.close(c);
        Path zip = BugReportMenu1122Cases.newest(dir, start);
        c.check(zip != null, "/bugreport <note> then Save produced no new zip (" + status + ")");
        if (zip == null) {
            return;
        }
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(zip.toFile())) {
            String note = read(z, "note.txt");
            String side = read(z, "sidebar.txt");
            String readers = read(z, "readers.txt");
            String state = read(z, "state.txt");
            c.note("sidebar.txt: " + side.replace("\n", " | "));
            c.note("readers.txt Copper: " + readers.lines().filter(l -> l.startsWith("Copper")).findFirst().orElse("-"));
            c.check(note.contains("the copper line is missing"), "note.txt lacks the typed note: " + note);
            c.check(side.contains("Island: Garden"), "sidebar.txt lacks Island: Garden: " + side);
            c.check(side.contains("Area: Garden"), "sidebar.txt lacks the tab info line: " + side);
            c.check(side.contains("\\u00a7fCopper: \\u00a7c1,234") && side.contains("Purse: \\u00a7699,000"),
                    "sidebar.txt lacks the escaped sidebar lines: " + side);
            c.check(readers.contains("Copper: '1,234' -> 1,234") && readers.contains("Purse: '99,000' -> 99,000"),
                    "readers.txt lacks the parsed Copper/Purse: " + readers);
            c.check(state.contains("Language:") && state.contains("Skyblock Only gate:") && state.contains("Held item Skyblock id:"),
                    "state.txt lacks its fields: " + state);
            c.check(read(z, "hud.txt").contains("enabled in settings"), "hud.txt lacks its table");
        }
    }

    private static String read(java.util.zip.ZipFile z, String name) throws Exception {
        var e = z.getEntry(name);
        if (e == null) {
            return "<missing " + name + ">";
        }
        return new String(z.getInputStream(e).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static long count(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".zip")).count();
        }
    }
}

package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** 1115-ui-bugcmd (bugcmd-1115): /bugreport and /logreport are client roots and each builds a new report zip. */
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
            long start = System.currentTimeMillis();
            Thread.sleep(1100); // the zip name carries a timestamp: never reuse the previous one's name
            c.onClient(mc -> {
                mc.player.connection.sendCommand(r);
                return null;
            });
            long deadline = System.currentTimeMillis() + 15000;
            while (count(dir) <= before && System.currentTimeMillis() < deadline) {
                c.ticks(5);
            }
            boolean fresh;
            try (Stream<Path> s = Files.list(dir)) {
                fresh = s.anyMatch(p -> p.toString().endsWith(".zip") && p.toFile().lastModified() >= start);
            }
            c.note("/" + r + ": zips " + before + " -> " + count(dir));
            c.check(count(dir) > before && fresh, "/" + r + " produced no new zip in " + dir);
        }
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

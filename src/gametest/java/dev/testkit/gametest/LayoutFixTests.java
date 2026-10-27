package dev.testkit.gametest;

import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 1026-layout-* (mod layout-fixes-1026, from the 2026-10-26 instance audit): the config tree only holds what is used.
 * <ul>
 *   <li>1026-layout-empty: after boot no folder under config/killer560 is empty (ModPaths.config no longer makes one
 *       per loaded feature, and migrateAll removes empty ones), and a setting whose folder is missing still saves.</li>
 *   <li>1026-layout-unfiled: Cape, Zoom, Mod Users, Hoppity's egg tracker, Mark tested, Legit GFS and Streamer Mode
 *       left in other/ move to their own folders with their values.</li>
 *   <li>1026-layout-stale: a copy in a former folder whose new spot already has one stays put, and is logged.</li>
 *   <li>1026-layout-maplog: 25 map logs leave the newest 20.</li>
 *   <li>1026-layout-retired: removed features' files move to retired/ (never deleted).</li>
 *   <li>1026-layout-profile: a profile save carries nothing from retired/.</li>
 * </ul>
 * Needs the old layout planted before the mod starts: {@code -Extra @("-PseedConfig=<testkit>/fixtures/seed-layout-fixes-1026")}.
 * Without the seed the five seeded cases are SKIP rows; 1026-layout-empty runs either way.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class LayoutFixTests implements FabricClientGameTest {

    /** file -> {new folder, a JSON key the seed set, the seeded value}. */
    private static final Map<String, String[]> UNFILED = new LinkedHashMap<>();

    static {
        UNFILED.put("killer560smod-cape.json", new String[]{"interface/cape", "customColourA", "1193046"});
        UNFILED.put("killer560smod-zoom.json", new String[]{"interface/zoom", "zoomLevel", "7.0"});
        UNFILED.put("killer560smod-modusers.json", new String[]{"interface/modusers", "seeOthers", "true"});
        UNFILED.put("killer560smod-eggtracker.json", new String[]{"skyblock/chocolatefactory", "hunts", null});
        UNFILED.put("killer560smod-tested-cheat.json", new String[]{"system/testing", "variant", "cheat"});
        UNFILED.put("killer560smod-legitgfs.json", new String[]{"skyblock/legitgfs", "enabled", "false"});
        UNFILED.put("killer560smod-streamer.json", new String[]{"system/streamer", "option", "DISABLE_CHEATS"});
    }

    /** Retired name -> where the seed put it (relative to config/). */
    private static final Map<String, String> RETIRED = new LinkedHashMap<>();

    static {
        RETIRED.put("killer560smod-proximityvoice.json", "killer560/other");
        RETIRED.put("killer560smod-chat-log.txt", "killer560/other");
        RETIRED.put("killer560smod-teleports.log", "");
        RETIRED.put("killer560smod-sim-runs", "killer560/dungeons/sim");
        RETIRED.put("killer560smod-ambience", "killer560/other");
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        empty(ctx);
        boolean seeded = ctx.computeOnClient(mc -> {
            Path root = root();
            return Files.isDirectory(root.resolve("dungeons/maplog"))
                    || Files.exists(root.resolve("dungeons/autoroutes/killer560smod-zpew.json"))
                    || Files.isDirectory(root.resolve("retired"));
        });
        String why = "nothing planted: run with -PseedConfig=<testkit>/fixtures/seed-layout-fixes-1026";
        seededCase(ctx, seeded, why, "1026-layout-unfiled", this::unfiled);
        seededCase(ctx, seeded, why, "1026-layout-stale", this::stale);
        seededCase(ctx, seeded, why, "1026-layout-maplog", this::maplog);
        seededCase(ctx, seeded, why, "1026-layout-retired", this::retired);
        seededCase(ctx, seeded, why, "1026-layout-profile", this::profile);
    }

    private static Path root() {
        return (Path) Mod.staticCall("util.ModPaths", "root");
    }

    private static void seededCase(ClientGameTestContext ctx, boolean seeded, String why, String name,
                                   Function<List<String>, Void> body) {
        if (Scenario.skip(name)) {
            return;
        }
        if (!seeded) {
            Scenario.skipped(name, why);
            return;
        }
        Scenario.run(ctx, name, (server, scenario) -> TestMap.on(server).platform(-7000, 150, -7000, 6).survival()
                .spawn(-6999.5, -6999.5, 0f).build(), (server, scenario) -> {
            List<String> lines = ctx.computeOnClient(mc -> {
                List<String> out = new ArrayList<>();
                body.apply(out);
                return out;
            });
            report(scenario, lines);
        });
    }

    private static void report(Scenario scenario, List<String> lines) {
        List<String> problems = new ArrayList<>();
        for (String l : lines) {
            scenario.log(l);
            if (l.startsWith("PROBLEM")) {
                problems.add(l.substring(8));
            }
        }
        if (!problems.isEmpty()) {
            throw new AssertionError(String.join("; ", problems));
        }
    }

    // ---------------------------------------------------------------------------------------------------- (a)

    private void empty(ClientGameTestContext ctx) {
        String name = "1026-layout-empty";
        if (Scenario.skip(name)) {
            return;
        }
        Scenario.run(ctx, name, (server, scenario) -> TestMap.on(server).platform(-7000, 150, -7000, 6).survival()
                .spawn(-6999.5, -6999.5, 0f).build(), (server, scenario) -> {
            List<String> lines = ctx.computeOnClient(mc -> {
                List<String> out = new ArrayList<>();
                Path root = root();
                List<String> folders = new ArrayList<>();
                List<String> empties = new ArrayList<>();
                // Depth 3 too: a feature folder holding only an empty data folder (BreakerAura, GIFs) is as empty.
                try (Stream<Path> walk = Files.walk(root, 3)) {
                    for (Path p : (Iterable<Path>) walk::iterator) {
                        if (p.equals(root) || !Files.isDirectory(p)) {
                            continue;
                        }
                        String rel = root.relativize(p).toString().replace('\\', '/');
                        folders.add(rel);
                        try (DirectoryStream<Path> s = Files.newDirectoryStream(p)) {
                            if (!s.iterator().hasNext()) {
                                empties.add(rel);
                            }
                        }
                    }
                } catch (Exception e) {
                    out.add("PROBLEM could not walk " + root + ": " + e);
                }
                out.add("folders after boot: " + folders.size() + " (empty " + empties.size() + ")");
                if (folders.isEmpty()) {
                    out.add("PROBLEM no folder at all under " + root + " - the check would prove nothing");
                }
                for (String e : empties) {
                    out.add("PROBLEM empty folder after boot: " + e);
                }

                // The startup cleanup: an empty feature folder goes, one holding anything stays.
                Path probeEmpty = root.resolve("interface/lf-empty-probe");
                Path probeFull = root.resolve("interface/lf-full-probe");
                try {
                    Files.createDirectories(probeEmpty);
                    Files.createDirectories(probeFull);
                    Files.writeString(probeFull.resolve("keep.txt"), "keep", StandardCharsets.UTF_8);
                    Object removed = Mod.staticCall("util.ModPaths", "removeEmptyFolders");
                    out.add("removeEmptyFolders removed " + removed + "; empty probe left " + Files.exists(probeEmpty)
                            + ", full probe left " + Files.exists(probeFull.resolve("keep.txt")));
                    if (Files.exists(probeEmpty)) {
                        out.add("PROBLEM the empty probe folder was not removed");
                    }
                    if (!Files.exists(probeFull.resolve("keep.txt"))) {
                        out.add("PROBLEM a folder with a file in it was touched");
                    }
                    Files.deleteIfExists(probeFull.resolve("keep.txt"));
                    Files.deleteIfExists(probeFull);
                } catch (Exception e) {
                    out.add("PROBLEM probe folders: " + e);
                }

                // A setting whose folder does not exist still saves: the save makes the folder.
                Path dir = root.resolve("interface/fullbright");
                Path file = dir.resolve("killer560smod-fullbright.json");
                try {
                    Files.deleteIfExists(file);
                    Files.deleteIfExists(dir);
                    boolean before = Boolean.TRUE.equals(Mod.get("fullbright.FullbrightConfig", "isEnabled"));
                    out.add("fullbright folder before the save: " + Files.exists(dir));
                    Mod.set("fullbright.FullbrightConfig", "setEnabled", !before);
                    Mod.call(Mod.cfg("fullbright.FullbrightConfig"), "save");
                    String text = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
                    out.add("after the save: file " + (text != null) + ", text " + (text == null ? "-" : text.replaceAll("\\s+", " ")));
                    if (text == null) {
                        out.add("PROBLEM a save into a missing folder wrote nothing");
                    } else if (!text.replaceAll("\\s", "").contains("\"enabled\":" + !before)) {
                        out.add("PROBLEM the saved file does not hold the changed value");
                    }
                    Mod.set("fullbright.FullbrightConfig", "setEnabled", before);
                    Mod.call(Mod.cfg("fullbright.FullbrightConfig"), "save");
                } catch (Exception e) {
                    out.add("PROBLEM fullbright save: " + e);
                }
                return out;
            });
            report(scenario, lines);
        });
    }

    // ---------------------------------------------------------------------------------------------------- (b)

    private Void unfiled(List<String> out) {
        Path root = root();
        for (Map.Entry<String, String[]> e : UNFILED.entrySet()) {
            String file = e.getKey();
            String[] want = e.getValue();
            Path expected = root.resolve(want[0]).resolve(file);
            Path old = root.resolve("other").resolve(file);
            String folder = (String) Mod.staticCall("util.ModPaths", "folderFor", file);
            String value = "-";
            if (Files.exists(expected)) {
                try {
                    com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(
                            Files.readString(expected, StandardCharsets.UTF_8)).getAsJsonObject();
                    value = !o.has(want[1]) ? "missing" : want[2] == null ? o.get(want[1]).toString()
                            : o.get(want[1]).getAsString();
                } catch (Exception ex) {
                    value = "unreadable: " + ex;
                }
            }
            out.add(file + ": row " + folder + ", in " + want[0] + " " + Files.exists(expected) + ", left in other/ "
                    + Files.exists(old) + ", " + want[1] + " = " + value);
            if (!want[0].equals(folder)) {
                out.add("PROBLEM " + file + " has row " + folder + ", not " + want[0]);
            }
            if (!Files.exists(expected) || Files.exists(old)) {
                out.add("PROBLEM " + file + " was not moved out of other/ into " + want[0]);
            }
            if (want[2] != null && !want[2].equals(value)) {
                out.add("PROBLEM " + file + " lost its " + want[1] + " (" + value + ", seeded " + want[2] + ")");
            }
            if (want[2] == null && !value.contains("\"eggs\":7")) {
                out.add("PROBLEM " + file + " lost its hunts (" + value + ")");
            }
        }
        // The features read the moved files, not defaults.
        Object zoom = Mod.get("zoom.ZoomConfig", "getZoomLevel");
        Object see = Mod.get("modusers.ModUserConfig", "isSeeOthers");
        Object gfs = Mod.staticCall("gfs.LegitGfs", "isEnabled");
        out.add("loaded: zoomLevel " + zoom + ", Mod Users seeOthers " + see + ", Legit GFS enabled " + gfs);
        if (!(zoom instanceof Number n) || Math.abs(n.floatValue() - 7f) > 0.001f) {
            out.add("PROBLEM Zoom did not load the moved zoomLevel");
        }
        if (!Boolean.TRUE.equals(see)) {
            out.add("PROBLEM Mod Users did not load the moved seeOthers");
        }
        if (!Boolean.FALSE.equals(gfs)) {
            out.add("PROBLEM Legit GFS did not load the moved enabled");
        }
        if (Mod.isCheat()) {
            Object option = Mod.staticCall("streamer.StreamerMode", "option");
            out.add("loaded: Streamer Mode option " + option);
            if (!"DISABLE_CHEATS".equals(String.valueOf(option))) {
                out.add("PROBLEM Streamer Mode did not load the moved option");
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------- (c)

    private Void stale(List<String> out) {
        Path root = root();
        Path old = root.resolve("dungeons/autoroutes/killer560smod-zpew.json");
        Path live = root.resolve("dungeons/zpew/killer560smod-zpew.json");
        String oldText = read(old);
        String liveText = read(live);
        out.add("old copy: " + flat(oldText));
        out.add("live copy: " + flat(liveText));
        if (oldText == null || !oldText.contains("\"stale\"")) {
            out.add("PROBLEM the stale copy in dungeons/autoroutes was moved, changed or deleted");
        }
        if (liveText == null || !liveText.contains("\"live\"")) {
            out.add("PROBLEM the live copy in dungeons/zpew was overwritten or deleted");
        }
        Object chain = Mod.get("zpew.ZeroPingEtherwarpConfig", "isChain");
        out.add("loaded: ZPEW chain " + chain + " (live false, stale true)");
        if (!Boolean.FALSE.equals(chain)) {
            out.add("PROBLEM ZPEW loaded the stale copy");
        }
        List<String> warn = logLines("stale copy left at");
        int matching = 0;
        for (String l : warn) {
            if (l.contains("killer560smod-zpew.json") && l.contains("WARN")) {
                matching++;
                out.add("log: " + l.trim());
            }
        }
        if (matching != 1) {
            out.add("PROBLEM expected exactly one WARN naming the stale zpew copy, found " + matching);
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------- (d)

    private Void maplog(List<String> out) {
        Path dir = root().resolve("dungeons/maplog");
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, "killer560smod-maplog-*.log")) {
            for (Path p : s) {
                names.add(p.getFileName().toString());
            }
        } catch (Exception e) {
            out.add("PROBLEM could not list " + dir + ": " + e);
        }
        names.sort(null);
        out.add("map logs left: " + names.size() + " " + (names.isEmpty() ? "" : names.get(0) + " .. "
                + names.get(names.size() - 1)));
        List<String> seededLeft = new ArrayList<>();
        for (String n : names) {
            if (n.startsWith("killer560smod-maplog-2026-10-01_00-")) {
                seededLeft.add(n);
            }
        }
        if (seededLeft.size() != 20) {
            out.add("PROBLEM expected the newest 20 of the 25 seeded logs, found " + seededLeft.size());
        }
        for (int i = 0; i < 25; i++) {
            String n = String.format("killer560smod-maplog-2026-10-01_00-%02d-00.log", i);
            boolean left = names.contains(n);
            if (i < 5 && left) {
                out.add("PROBLEM old log " + n + " was kept");
            } else if (i >= 5 && !left) {
                out.add("PROBLEM new log " + n + " was deleted");
            }
        }
        List<String> log = logLines("Map logger kept the newest");
        log.forEach(l -> out.add("log: " + l.trim()));
        if (log.stream().noneMatch(l -> l.contains("deleted 5 "))) {
            out.add("PROBLEM no log line saying 5 old map logs were deleted");
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------- (e)

    private Void retired(List<String> out) {
        Path root = root();
        Path config = root.getParent();
        Path retired = root.resolve("retired");
        for (Map.Entry<String, String> e : RETIRED.entrySet()) {
            String name = e.getKey();
            Path was = e.getValue().isEmpty() ? config.resolve(name) : config.resolve(e.getValue()).resolve(name);
            Path now = retired.resolve(name);
            out.add(name + ": in retired/ " + Files.exists(now) + ", left at " + e.getValue() + " " + Files.exists(was));
            if (!Files.exists(now) || Files.exists(was)) {
                out.add("PROBLEM " + name + " was not moved into retired/");
            }
        }
        String voice = read(retired.resolve("killer560smod-proximityvoice.json"));
        String ambience = read(retired.resolve("killer560smod-ambience/killer560smod-ambience-settings.json"));
        String runs = read(retired.resolve("killer560smod-sim-runs/run-1.json"));
        if (voice == null || !voice.contains("\"retired\"") || ambience == null || runs == null) {
            out.add("PROBLEM a retired file lost its contents (proximityvoice " + flat(voice) + ", ambience "
                    + flat(ambience) + ", sim-runs " + flat(runs) + ")");
        }
        List<String> log = logLines("[ModPaths] retired ");
        log.forEach(l -> out.add("log: " + l.trim()));
        if (log.size() < RETIRED.size()) {
            out.add("PROBLEM expected a log line for each of " + RETIRED.size() + " retired entries, found " + log.size());
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------- (f)

    private Void profile(List<String> out) {
        Path root = root();
        if (!Files.exists(root.resolve("retired/killer560smod-ambience/killer560smod-ambience-settings.json"))) {
            out.add("PROBLEM the retired setting-shaped file is not there, so the check would prove nothing");
            return null;
        }
        String name = "lf-retired-check";
        Object result = Mod.staticCall("profiles.ProfileManager", "saveCurrentAsProfile", name);
        out.add("save: " + result);
        Path dir = ((Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-profiles")).resolve(name);
        List<String> carried = new ArrayList<>();
        int files = 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                files++;
                String rel = dir.relativize(p).toString().replace('\\', '/');
                for (String retiredName : RETIRED.keySet()) {
                    if (rel.contains(retiredName)) {
                        carried.add(rel);
                    }
                }
                if (rel.contains("killer560smod-ambience") || rel.contains("retired")) {
                    carried.add(rel);
                }
            }
        } catch (Exception e) {
            out.add("PROBLEM could not walk the saved profile " + dir + ": " + e);
        }
        out.add("profile holds " + files + " file(s); from retired/: " + carried);
        if (files == 0) {
            out.add("PROBLEM the profile save wrote nothing, so the check would prove nothing");
        }
        if (!carried.isEmpty()) {
            out.add("PROBLEM the profile carries retired files: " + carried);
        }
        Mod.staticCall("profiles.ProfileManager", "deleteProfile", name);
        return null;
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private static String read(Path p) {
        try {
            return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return "unreadable: " + e;
        }
    }

    private static String flat(String s) {
        return s == null ? "missing" : s.replaceAll("\\s+", " ");
    }

    /** Lines of this client's latest.log holding {@code needle} (the startup lines come before any LogTap). */
    private static List<String> logLines(String needle) {
        List<String> out = new ArrayList<>();
        Path log = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve("logs/latest.log");
        try {
            for (String l : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                if (l.contains(needle)) {
                    out.add(l);
                }
            }
        } catch (Exception e) {
            out.add("could not read " + log + ": " + e);
        }
        return out;
    }
}

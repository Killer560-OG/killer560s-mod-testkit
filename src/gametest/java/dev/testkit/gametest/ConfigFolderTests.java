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

/**
 * 1025-config-folders (killer560, 2026-10-25: the autoroutes folder "should only hold my autoroutes settings and the auto
 * routes"; mod f1409898). Etherwarp Waypoints, Etherwarp Overlay, Waypoint Routes, Zero Ping Etherwarp and Left Click
 * Etherwarp each have their own {@code dungeons/<name>} folder now; a file an older build left in {@code dungeons/autoroutes}
 * is moved on startup (ModPaths.migrateAll's sweep) or on lookup, with its contents kept.
 *
 * <p>Needs the old files planted BEFORE the mod starts: run with
 * {@code -Extra @("-PseedConfig=<testkit>/fixtures/seed-config-folders-1025")}. Without the seed it is a SKIP row.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class ConfigFolderTests implements FabricClientGameTest {

    private static final String NAME = "1025-config-folders";

    /** file -> {new folder, a JSON key the seed set to a non-default value, that value}. */
    private static final Map<String, String[]> FILES = new LinkedHashMap<>();

    static {
        FILES.put("killer560smod-etherwarp.json", new String[]{"dungeons/etherwarp", "throughWalls", "true"});
        FILES.put("killer560smod-etherwarpoverlay.json", new String[]{"dungeons/etherwarpoverlay", "fullBlock", "true"});
        FILES.put("killer560smod-waypointroutes.json", new String[]{"dungeons/waypointroutes", "showNumbers", "false"});
        FILES.put("killer560smod-zpew.json", new String[]{"dungeons/zpew", "chain", "false"});
        FILES.put("killer560smod-leftclickew.json", new String[]{"dungeons/leftclickew", "enabled", "true"});
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        boolean seeded = ctx.computeOnClient(mc -> {
            Path root = (Path) Mod.staticCall("util.ModPaths", "root");
            for (Map.Entry<String, String[]> e : FILES.entrySet()) {
                if (Files.exists(root.resolve(e.getValue()[0]).resolve(e.getKey()))
                        || Files.exists(root.resolve("dungeons/autoroutes").resolve(e.getKey()))) {
                    return true;
                }
            }
            return false;
        });
        if (!seeded) {
            Scenario.skipped(NAME, "no old-folder files planted: run with -PseedConfig=<testkit>/fixtures/seed-config-folders-1025");
            return;
        }
        Scenario.run(ctx, NAME, (server, scenario) -> TestMap.on(server).platform(-7000, 150, -7000, 6).survival()
                .spawn(-6999.5, -6999.5, 0f).build(), (server, scenario) -> {
            List<String> problems = new ArrayList<>();
            List<String> lines = ctx.computeOnClient(mc -> {
                List<String> out = new ArrayList<>();
                Path root = (Path) Mod.staticCall("util.ModPaths", "root");
                Path oldDir = root.resolve("dungeons/autoroutes");
                for (Map.Entry<String, String[]> e : FILES.entrySet()) {
                    String file = e.getKey();
                    String[] want = e.getValue();
                    Path expected = root.resolve(want[0]).resolve(file);
                    Path looked = (Path) Mod.staticCall("util.ModPaths", "config", file);
                    boolean there = Files.exists(expected);
                    boolean oldThere = Files.exists(oldDir.resolve(file));
                    String value = "-";
                    if (there) {
                        try {
                            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(
                                    Files.readString(expected, StandardCharsets.UTF_8)).getAsJsonObject();
                            value = o.has(want[1]) ? o.get(want[1]).getAsString() : "missing";
                        } catch (Exception ex) {
                            value = "unreadable: " + ex;
                        }
                    }
                    out.add(file + ": lookup " + root.relativize(looked).toString().replace('\\', '/') + ", in "
                            + want[0] + " " + there + ", left in dungeons/autoroutes " + oldThere + ", " + want[1] + " = "
                            + value + " (seeded " + want[2] + ")");
                    if (!looked.normalize().equals(expected.normalize())) {
                        out.add("PROBLEM " + file + " is looked up at " + looked + ", not " + expected);
                    }
                    if (!there || oldThere) {
                        out.add("PROBLEM " + file + " was not moved to " + want[0]);
                    }
                    if (!want[2].equals(value)) {
                        out.add("PROBLEM " + file + " lost its " + want[1] + " (" + value + ", seeded " + want[2] + ")");
                    }
                }
                // What the loaded settings say (the mod read the moved files, not defaults).
                out.add("loaded: Etherwarp Overlay fullBlock " + Mod.get("etherwarpoverlay.EtherwarpOverlayConfig", "isFullBlock")
                        + ", Waypoint Routes showNumbers " + Mod.get("routes.WaypointRoutesConfig", "isShowNumbers")
                        + ", ZPEW chain " + Mod.get("zpew.ZeroPingEtherwarpConfig", "isChain"));
                if (!Boolean.TRUE.equals(Mod.get("etherwarpoverlay.EtherwarpOverlayConfig", "isFullBlock"))
                        || !Boolean.FALSE.equals(Mod.get("routes.WaypointRoutesConfig", "isShowNumbers"))
                        || !Boolean.FALSE.equals(Mod.get("zpew.ZeroPingEtherwarpConfig", "isChain"))) {
                    out.add("PROBLEM the loaded settings are not the seeded values");
                }
                // dungeons/autoroutes keeps only Auto Routes' own files.
                List<String> left = new ArrayList<>();
                if (Files.isDirectory(oldDir)) {
                    try (DirectoryStream<Path> s = Files.newDirectoryStream(oldDir)) {
                        for (Path p : s) {
                            String n = p.getFileName().toString();
                            left.add(n);
                            String folder = (String) Mod.staticCall("util.ModPaths", "folderFor", n);
                            if (!"dungeons/autoroutes".equals(folder)) {
                                out.add("PROBLEM dungeons/autoroutes still holds " + n + " (belongs in " + folder + ")");
                            }
                        }
                    } catch (java.io.IOException ex) {
                        out.add("PROBLEM could not list dungeons/autoroutes: " + ex);
                    }
                }
                out.add("dungeons/autoroutes now holds " + left);
                return out;
            });
            for (String l : lines) {
                scenario.log(l);
                if (l.startsWith("PROBLEM")) {
                    problems.add(l.substring(8));
                }
            }
            if (!problems.isEmpty()) {
                throw new AssertionError(String.join("; ", problems));
            }
        });
    }
}

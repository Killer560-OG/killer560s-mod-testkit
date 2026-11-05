package dev.testkit.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 1105-fairy-hub (killer560, 2026-11-05: "you got that world download of the hub right so you should be able to test the
 * auto fairy soul stuff on it as well"): Auto Fairy Souls on the REAL Hypixel hub, on GrimAC. The server loads the
 * owner's private hub capture ({@code /k560worldexport}, cropped to the main island), the mod gets SkyHanni's real HUB
 * graph, and every soul stand stands where Hypixel's stood (the capture's {@code entities.json}, the soul head texture).
 * Proof is the server's, as in {@link FairyIslandTests}: a soul counts only on a {@code use_entity} on its stand.
 * <p>
 * The capture is PRIVATE and never in this repo: {@code -Pfairy.hubWorld=<save>} (property {@code testkit.fairy.hubWorld}),
 * default {@code killer560s-mod-logs/world-captures/hub-1104-main} beside the mod checkout. The graph is the mod's own
 * download in the rooms instance ({@code testkit.fairy.hubGraph} overrides). Missing either: the case SKIPs and says so.
 * {@code testkit.fairy.hubSouls} (default 10) souls are required in {@code testkit.fairy.hubMinutes} (default 8, under
 * the 9.5-minute NegativeTimer harness quirk) from {@code testkit.fairy.hubStart} (x,y,z[,yaw,pitch], default the
 * /hub warp). {@code testkit.fairy.hubOnly=3,7} routes to those souls only (this case's indexes, logged at the start).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class FairyHubTests implements FabricClientGameTest {

    static final String NAME = "1105-fairy-hub";
    /** The skin hash of Hypixel's fairy soul head (all 80 hub soul stands in the capture wear it). */
    static final String SOUL_SKIN = "299ea120bd83d0c81a3c4627f5bce1b12fb03bcb57779c63dcc77e3f4ae8a793";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        if (!Mod.isCheat()) {
            Scenario.skipped(NAME, "Auto Fairy Souls is cheat build only");
            return;
        }
        Path world = hubWorld();
        Path graph = hubGraph();
        if (world == null || graph == null) {
            Scenario.skipped(NAME, "no hub capture (" + world + ") or HUB graph (" + graph + ") on this machine");
            return;
        }
        JsonObject meta = meta(world, graph);
        JsonArray spawn = meta.getAsJsonArray("spawn");
        TestServer.seedWorldFrom(world, new int[]{(int) Math.floor(spawn.get(0).getAsDouble()), spawn.get(1).getAsInt(),
                (int) Math.floor(spawn.get(2).getAsDouble())});
        try {
            Scenario.run(ctx, NAME, (server, scenario) -> TestMap.on(server).survival().build(), (server, scenario) -> {
                server.command("gamerule random_tick_speed 0");
                String only = System.getProperty("testkit.fairy.hubOnly", "");
                FairyIslandTests.onlySouls = only.isBlank() ? null : java.util.Arrays.stream(only.split(","))
                        .map(String::trim).map(Integer::valueOf).collect(java.util.stream.Collectors.toSet());
                int need = Math.min(Integer.getInteger("testkit.fairy.hubSouls", 10),
                        only.isBlank() ? Integer.MAX_VALUE : FairyIslandTests.onlySouls.size());
                int maxTicks = Integer.getInteger("testkit.fairy.hubMinutes", 8) * 60 * 20;
                scenario.log("hub world " + world.getFileName() + ", " + meta.getAsJsonArray("souls").size() + " soul stands, "
                        + meta.getAsJsonObject("graph").size() + " graph nodes; need " + need + " souls in " + maxTicks + " ticks");
                FairyIslandTests.skipPlace = true;
                FairyIslandTests.stopAfterSouls = need;
                FairyIslandTests.startOverride = System.getProperty("testkit.fairy.hubStart", "");
                FairyIslandTests.Outcome o;
                try {
                    o = FairyIslandTests.runIsland(ctx, server, scenario, "HUB", meta,
                            Integer.getInteger("testkit.fairy.speed", 100), maxTicks);
                } finally {
                    FairyIslandTests.skipPlace = false;
                    FairyIslandTests.stopAfterSouls = Integer.MAX_VALUE;
                    FairyIslandTests.startOverride = null;
                    FairyIslandTests.onlySouls = null;
                }
                scenario.log(o.line());
                int flags = scenario.flags().size();
                FairyIslandTests.appendRow("HUB_WORLD", 100, o, flags);
                // the run must have acted: real walking and real clicks the server took
                if (o.collected < need || o.travelled < 30 * need || o.clicks < o.collected) {
                    o.missed.stream().limit(20).forEach(m -> scenario.log("MISSED " + m));
                    throw new AssertionError(String.format(Locale.US, "hub: collected %d/%d needed, traveled %.0f blocks, "
                            + "%d clicks - %s", o.collected, need, o.travelled, o.clicks, o.stopReason));
                }
            });
        } finally {
            TestServer.seedWorldFrom(null, null);
        }
    }

    static Path hubWorld() {
        String p = System.getProperty("testkit.fairy.hubWorld", "");
        Path dir = !p.isBlank() ? Path.of(p) : Machine.modSource() == null ? null
                : Machine.modSource().resolveSibling("killer560s-mod-logs").resolve("world-captures").resolve("hub-1104-main");
        return dir != null && Files.isRegularFile(dir.resolve("level.dat")) && Files.isRegularFile(dir.resolve("entities.json"))
                ? dir : null;
    }

    static Path hubGraph() {
        String p = System.getProperty("testkit.fairy.hubGraph", "");
        Path f = !p.isBlank() ? Path.of(p) : Machine.prismInstances() == null ? null
                : Path.of(Machine.instanceConfigDir(Machine.roomsInstance()), "killer560", "skyblock", "pathfinding",
                "killer560smod-pathfinding", "graphs", "HUB.json");
        return f != null && Files.isRegularFile(f) ? f : null;
    }

    /** FairyIslandTests' meta shape: graph, spawn, and one soul per hub soul stand (its stand spot and nearest node). */
    static JsonObject meta(Path world, Path graphFile) {
        try {
            JsonObject graph = JsonParser.parseString(Files.readString(graphFile, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray entities = JsonParser.parseString(Files.readString(world.resolve("entities.json"), StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("entities");
            List<double[]> stands = new ArrayList<>();
            for (JsonElement el : entities) {
                JsonObject e = el.getAsJsonObject();
                if (!e.has("head") || !e.getAsJsonObject("head").toString().contains("textures") || !SOUL_SKIN.equals(skin(e))) {
                    continue;
                }
                double[] s = {e.get("x").getAsDouble(), e.get("y").getAsDouble(), e.get("z").getAsDouble()};
                // the capture holds each stand several times (a few hundredths apart in y)
                if (stands.stream().noneMatch(u -> Math.abs(u[0] - s[0]) < .6 && Math.abs(u[1] - s[1]) < .6 && Math.abs(u[2] - s[2]) < .6)) {
                    stands.add(s);
                }
            }
            JsonArray souls = new JsonArray();
            for (double[] s : stands) {
                String best = null;
                double bestD = Double.MAX_VALUE;
                for (Map.Entry<String, JsonElement> n : graph.entrySet()) {
                    String[] p = n.getValue().getAsJsonObject().get("Position").getAsString().split(":");
                    double d = Math.pow(Double.parseDouble(p[0]) + .5 - s[0], 2) + Math.pow(Double.parseDouble(p[1]) - s[1] - 1.5, 2)
                            + Math.pow(Double.parseDouble(p[2]) + .5 - s[2], 2);
                    if (d < bestD) {
                        bestD = d;
                        best = n.getKey();
                    }
                }
                JsonObject soul = new JsonObject();
                JsonArray stand = new JsonArray();
                JsonArray head = new JsonArray();
                for (int k = 0; k < 3; k++) {
                    stand.add(s[k]);
                    head.add((int) Math.floor(s[k] + (k == 1 ? 1.5 : 0)));
                }
                soul.add("stand", stand);
                soul.add("head", head);
                soul.addProperty("node", best);
                souls.add(soul);
            }
            // /hub lands on the warp node (SkyHanni's "/hub" warp tag)
            JsonArray spawn = new JsonArray();
            for (Map.Entry<String, JsonElement> n : graph.entrySet()) {
                JsonObject o = n.getValue().getAsJsonObject();
                if (o.has("Name") && "/hub".equals(o.get("Name").getAsString())) {
                    String[] p = o.get("Position").getAsString().split(":");
                    spawn.add(Double.parseDouble(p[0]) + .5);
                    spawn.add((int) Double.parseDouble(p[1]));
                    spawn.add(Double.parseDouble(p[2]) + .5);
                }
            }
            JsonObject meta = new JsonObject();
            meta.add("graph", graph);
            meta.add("souls", souls);
            meta.add("spawn", spawn);
            return meta;
        } catch (java.io.IOException | RuntimeException e) {
            throw new AssertionError("could not read the hub capture " + world + " / graph " + graphFile + ": " + e, e);
        }
    }

    private static String skin(JsonObject e) {
        try {
            String v = e.getAsJsonObject("head").getAsJsonObject("components").getAsJsonObject("minecraft:profile")
                    .getAsJsonArray("properties").get(0).getAsJsonObject().get("value").getAsString();
            String url = JsonParser.parseString(new String(Base64.getDecoder().decode(v), StandardCharsets.UTF_8)).getAsJsonObject()
                    .getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").getAsString();
            return url.substring(url.lastIndexOf('/') + 1);
        } catch (RuntimeException ex) {
            return "";
        }
    }
}

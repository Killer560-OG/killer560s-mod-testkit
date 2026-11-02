package dev.testkit.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.testkit.gametest.hx.Hx;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 1101-fairy-island-*: Auto Fairy Souls over a whole island (killer560, 2026-11-01: "you can essentially recreate an
 * entire island to test it on"). Each island is SkyHanni's real navigation graph and NEU's real soul coordinates, with
 * terrain made up per edge by {@code tools/fairy-islands/gen.py} (block steps, slabs, stairs, ladders up every edge
 * steeper than 1:1, fenced single-wide lanes, open doors, water pools, low tunnels, carpet / snow / path / soul sand
 * floors, villager crowds). The soul stand is a real-size invisible armor stand whose head is at NEU's block - up to
 * 3.6 blocks from its graph node, as on Hypixel.
 * <p>
 * Proof is the server's: a soul counts only when the server received a {@code use_entity} on that soul's stand. The
 * case logs the rate (collected / souls), the time, every soul missed with the mod's own reason, the ways back planned,
 * failsafes and the anticheat lines, appends a row to {@code build/testkit-report/fairy-islands.tsv}, then requires
 * every soul collected and GrimAC silent.
 * <p>
 * Filters: {@code 1101-fairy-island} (all), or one name such as {@code 1101-fairy-island-GOLD_MINES}. System
 * property {@code testkit.fairy.speed} (Hypixel speed, default 100) and {@code testkit.fairy.maxMinutes} (default 15).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class FairyIslandTests implements FabricClientGameTest {

    static final String CFG = "pathfinding.PathfindingConfig";
    static final String RUNNER = "pathfinding.AutoSoulRunner";
    static final String WALKER = "com.killer560.hub.pathfinding.AutoWalker";
    static final String SOUL_LINE = "§d§lSOUL! §fYou found a §dFairy Soul§f!";
    static final List<String> ISLANDS = List.of("GOLD_MINES", "THE_PARK", "BACKWATER_BAYOU", "HUB_EAST", "SPIDER_DEN");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Mod.isCheat()) {
            for (String n : ISLANDS) {
                if (!Scenario.skip("1101-fairy-island-" + n)) {
                    Scenario.skipped("1101-fairy-island-" + n, "Auto Fairy Souls is cheat build only");
                }
            }
            return;
        }
        AssertionError first = null;
        for (String island : ISLANDS) {
            try {
                island(ctx, island);
            } catch (AssertionError e) {
                first = first == null ? e : first;
            }
        }
        if (first != null) {
            throw first;
        }
    }

    // ------------------------------------------------------------------ data

    static JsonObject meta(String island) {
        try (InputStream in = FairyIslandTests.class.getResourceAsStream("/fairy-islands/" + island + ".meta.json")) {
            if (in == null) {
                throw new AssertionError("no fairy-islands/" + island + ".meta.json (run tools/fairy-islands/gen.py)");
            }
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    static List<String> blocks(String island) {
        List<String> out = new ArrayList<>();
        try (InputStream raw = FairyIslandTests.class.getResourceAsStream("/fairy-islands/" + island + ".blocks.gz");
             BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (!line.isBlank()) {
                    out.add(line);
                }
            }
        } catch (java.io.IOException | NullPointerException e) {
            throw new AssertionError("could not read fairy-islands/" + island + ".blocks.gz: " + e, e);
        }
        return out;
    }

    /** Sends the island's blocks (supports first, as the file is ordered) in batches through Hx dungeon.blocks. */
    static void place(Hx hx, List<String> lines) {
        for (int i = 0; i < lines.size(); i += 2000) {
            JsonArray batch = new JsonArray();
            for (String l : lines.subList(i, Math.min(lines.size(), i + 2000))) {
                batch.add(l);
            }
            JsonObject a = new JsonObject();
            a.add("blocks", batch);
            hx.call("dungeon.blocks", a);
        }
    }

    // ------------------------------------------------------------------ the case

    private static void island(ClientGameTestContext ctx, String island) {
        String name = "1101-fairy-island-" + island;
        Scenario.run(ctx, name, (server, scenario) -> TestMap.on(server).survival().build(), (server, scenario) -> {
            JsonObject meta = meta(island);
            int speed = Integer.getInteger("testkit.fairy.speed", 100);
            int maxTicks = Integer.getInteger("testkit.fairy.maxMinutes", 15) * 60 * 20;
            Outcome o = runIsland(ctx, server, scenario, island, meta, speed, maxTicks);
            scenario.log(o.line());
            o.missed.forEach(m -> scenario.log("MISSED " + m));
            int flags = scenario.flags().size();
            scenario.log("anticheat lines so far: " + flags);
            appendRow(island, speed, o, flags);
            if (o.collected < o.souls) {
                throw new AssertionError(island + ": collected " + o.collected + "/" + o.souls + " - " + o.missed);
            }
        });
    }

    static final class Outcome {
        int souls;
        int collected;
        int ticks;
        double travelled;
        int rescues;
        int rescuesDone;
        int sidesteps;
        int failsafes;
        int skipped;
        int clicks;
        int warps;
        String stopReason = "";
        boolean activeAtEnd;
        final List<String> missed = new ArrayList<>();
        final Map<Integer, Integer> collectedAt = new LinkedHashMap<>();

        String line() {
            return String.format(Locale.US, "ISLAND RESULT: collected %d/%d (%.0f%%) in %d ticks (%.1f min), traveled %.0f "
                            + "blocks, ways back planned %d done %d, sidesteps %d, failsafes %d, skipped %d, soul clicks %d, "
                            + "warps %d, active at end %s, stop '%s'", collected, souls, souls == 0 ? 0 : 100.0 * collected / souls,
                    ticks, ticks / 1200.0, travelled, rescues, rescuesDone, sidesteps, failsafes, skipped, clicks, warps,
                    activeAtEnd, stopReason);
        }
    }

    static void appendRow(String island, int speed, Outcome o, int flags) {
        try {
            Path f = Path.of("build", "testkit-report", "fairy-islands.tsv");
            Files.createDirectories(f.getParent());
            String jar = System.getProperty("testkit.modUnderTest", System.getProperty("fabric.addMods", "?"));
            String row = String.join("\t", java.time.LocalDateTime.now().toString(), island, String.valueOf(speed),
                    o.collected + "/" + o.souls, String.valueOf(o.ticks), String.format(Locale.US, "%.0f", o.travelled),
                    String.valueOf(o.rescues), String.valueOf(o.failsafes), String.valueOf(flags),
                    String.join(" | ", o.missed), jar) + System.lineSeparator();
            Files.writeString(f, row, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // the log line carries the same
        }
    }

    @SuppressWarnings("unchecked")
    static Outcome runIsland(ClientGameTestContext ctx, TestServer server, Scenario scenario, String island, JsonObject meta,
                             int speed, int maxTicks) {
        Outcome o = new Outcome();
        JsonArray souls = meta.getAsJsonArray("souls");
        o.souls = souls.size();
        JsonArray spawn = meta.getAsJsonArray("spawn");
        try (Hx hx = Hx.connect()) {
            long t0 = System.currentTimeMillis();
            List<String> lines = blocks(island);
            place(hx, lines);
            server.sync(300);
            scenario.log("placed " + lines.size() + " blocks in " + (System.currentTimeMillis() - t0) + " ms");
            for (JsonElement c : meta.getAsJsonArray("crowds")) {
                JsonArray p = c.getAsJsonArray();
                for (int k = 0; k < 2; k++) {
                    server.command(String.format(Locale.ROOT, "summon minecraft:villager %.2f %d %.2f "
                                    + "{NoAI:1b,Invulnerable:1b,PersistenceRequired:1b,Silent:1b}",
                            p.get(0).getAsDouble() + (k == 0 ? -0.3 : 0.3), p.get(1).getAsInt(), p.get(2).getAsDouble()));
                }
            }
            server.command(String.format(Locale.ROOT, "attribute @p minecraft:movement_speed base set %s",
                    new java.math.BigDecimal(speed).movePointLeft(3).toPlainString()));
            server.command("effect clear @p");
            server.command(String.format(Locale.ROOT, "tp @p %.2f %d %.2f 0 0", spawn.get(0).getAsDouble(),
                    spawn.get(1).getAsInt(), spawn.get(2).getAsDouble()));
            server.command("spawnpoint @p " + (int) Math.floor(spawn.get(0).getAsDouble()) + " " + spawn.get(1).getAsInt()
                    + " " + (int) Math.floor(spawn.get(2).getAsDouble()));
            server.sync(60);
            ctx.waitTicks(80);
            double[] at = scenario.playerPosition();
            if (Math.abs(at[1] - spawn.get(1).getAsDouble()) > 1.5) {
                throw new AssertionError("the player is not on the island's spawn pad: " + at[0] + " " + at[1] + " " + at[2]);
            }

            // the graph as the cached HUB graph, island Hub, settings
            String graph = meta.getAsJsonObject("graph").toString();
            ctx.runOnClient(mc -> {
                try {
                    Path dir = (Path) Mod.staticCall("pathfinding.GraphRepository", "dataDir");
                    Files.createDirectories(dir);
                    Files.writeString(dir.resolve("HUB.json"), graph, StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new AssertionError("could not write the island graph: " + e, e);
                }
                ((Map<String, Object>) Mod.field("com.killer560.hub.pathfinding.GraphRepository", "LOADED")).remove("HUB");
                ((Map<String, Object>) Mod.field("com.killer560.hub.pathfinding.GraphRepository", "NEXT_ATTEMPT")).remove("HUB");
                Mod.staticCall(RUNNER, "stop", "testkit setup", false);
                Mod.staticCall("pathfinding.FairySoulsFeature", "stopGuide", false);
                Mod.set(CFG, "setEnabled", true);
                Mod.set(CFG, "setAutoStartOnIsland", false);
                Mod.set(CFG, "setSoulMode", Mod.enumValue("pathfinding.PathfindingConfig$SoulMode", "ROUTE"));
                Mod.set(CFG, "setAutoWalk", true);
                Mod.set(CFG, "setAutoSouls", true);
                Mod.set(CFG, "setAutoMode", Mod.enumValue("pathfinding.PathfindingConfig$AutoMode", "WALK"));
                Mod.set(CFG, "setAutoCollect", true);
                Mod.set(CFG, "setAutoSprint", true);
                Mod.set(CFG, "setChatFeedback", true);
                try {
                    Mod.set(CFG, "setAllowPearls", false);
                } catch (RuntimeException | AssertionError ignored) {
                    // not in this jar
                }
            });
            hx.sidebar("SKYBLOCK", "⏣ Village", "Purse: 0");
            hx.tab(List.of("Area: Hub", "Profile: Test"), null, null);
            ctx.waitFor(mc -> "HUB".equals(Mod.staticCall("pathfinding.IslandDetector", "graphIsland")), 200);
            ctx.waitFor(mc -> "Test".equals(Mod.staticCall("pathfinding.ProfileTracker", "displayName")), 200);
            ctx.waitFor(mc -> (Boolean) Mod.staticCall("pathfinding.GraphRepository", "isLoaded", "HUB"), 400);
            ctx.runOnClient(mc -> Mod.staticCall("pathfinding.FairySoulsFeature", "resetIsland"));
            hx.removeStands();
            Map<String, Integer> stands = new HashMap<>();
            for (int i = 0; i < souls.size(); i++) {
                JsonArray head = souls.get(i).getAsJsonObject().getAsJsonArray("head");
                // A full-size invisible stand wearing a head: the head renders in NEU's block, sitting on what is under it.
                String uuid = hx.stand(head.get(0).getAsInt() + 0.5, head.get(1).getAsInt() - 1.4, head.get(2).getAsInt() + 0.5,
                        null, "marker", false, "invisible", true, "helmet", "minecraft:player_head");
                stands.put(uuid, i);
            }
            ctx.waitTicks(10);
            long since = hx.head();
            long cmdSince = since;
            long logMark = LogTap.mark();
            // -Pfairy.start=x,y,z: start the run there instead of the spawn (a repro of one stretch of an island run)
            String startAt = System.getProperty("testkit.fairy.start", "");
            if (!startAt.isBlank()) {
                String[] xyz = startAt.split(",");
                server.command("tp @p " + xyz[0] + " " + xyz[1] + " " + xyz[2]);
                server.sync(30);
                ctx.waitTicks(40);
                scenario.log("started at " + startAt + " (-Pfairy.start)");
            }
            ctx.runOnClient(mc -> Mod.staticCall(RUNNER, "start"));
            double[] prev = null;
            for (int tick = 0; tick < maxTicks; tick++) {
                ctx.waitTick();
                double[] s = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
                if (prev != null) {
                    double step = Math.hypot(s[0] - prev[0], s[2] - prev[2]);
                    if (step < 3.0) {
                        o.travelled += step;
                    }
                }
                prev = s;
                if (tick < Integer.getInteger("testkit.fairy.traceTicks", 0)) {
                    // -Pfairy.traceTicks=N: every tick of the first N, what the walker pressed and how he moved
                    String tr = ctx.computeOnClient(mc -> String.format(Locale.US,
                            "pos %.4f %.4f %.4f v %.4f %.4f %.4f yaw %.2f pitch %.2f ground %s ladder %s water %s hcol %s "
                                    + "keys %s%s%s%s%s%s rescue %s", mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                            mc.player.getDeltaMovement().x, mc.player.getDeltaMovement().y, mc.player.getDeltaMovement().z,
                            mc.player.getYRot(), mc.player.getXRot(), mc.player.onGround(), mc.player.onClimbable(),
                            mc.player.isInWater(), mc.player.horizontalCollision,
                            (Boolean) Mod.field(WALKER, "wantForward") ? "W" : "", (Boolean) Mod.field(WALKER, "wantBack") ? "S" : "",
                            (Boolean) Mod.field(WALKER, "wantLeft") ? "A" : "", (Boolean) Mod.field(WALKER, "wantRight") ? "D" : "",
                            (Boolean) Mod.field(WALKER, "wantJump") ? "J" : "", (Boolean) Mod.field(WALKER, "wantSprint") ? "+spr" : "",
                            Mod.staticCall("pathfinding.AutoWalker", "isRescuing")));
                    scenario.log("TRACE t" + tick + " " + tr);
                }
                if (tick % 200 == 0) {
                    String w = ctx.computeOnClient(mc -> "rescue=" + Mod.staticCall("pathfinding.AutoWalker", "isRescuing")
                            + " fwd=" + Mod.field(WALKER, "wantForward") + " jump=" + Mod.field(WALKER, "wantJump")
                            + " ladder=" + mc.player.onClimbable() + " water=" + mc.player.isInWater()
                            + " hcol=" + mc.player.horizontalCollision);
                    scenario.log(String.format(Locale.US, "t%d pos %.2f %.2f %.2f collected %d/%d %s", tick, s[0], s[1], s[2],
                            o.collectedAt.size(), o.souls, w));
                }
                // Hypixel's side of Warp When Stuck: /warp hub puts him on the island's spawn.
                for (JsonObject e : hx.events(cmdSince, "cmd")) {
                    cmdSince = Math.max(cmdSince, e.get("seq").getAsLong());
                    if (e.get("command").getAsString().trim().equals("warp hub")) {
                        o.warps++;
                        server.command(String.format(Locale.ROOT, "tp @p %.2f %d %.2f", spawn.get(0).getAsDouble(),
                                spawn.get(1).getAsInt(), spawn.get(2).getAsDouble()));
                        scenario.log(String.format(Locale.US, "t%d /warp hub from %.1f %.1f %.1f - sent to the spawn", tick,
                                s[0], s[1], s[2]));
                    }
                }
                for (JsonObject e : hx.events(since, "use_entity")) {
                    since = Math.max(since, e.get("seq").getAsLong());
                    Integer idx = stands.get(e.get("uuid").getAsString());
                    if (idx != null && !o.collectedAt.containsKey(idx)) {
                        o.collectedAt.put(idx, tick);
                        hx.chat(SOUL_LINE);
                        hx.call("entity.remove", "uuid", e.get("uuid").getAsString());
                        scenario.log(String.format(Locale.US, "soul %d collected at t%d from %.2f %.2f %.2f", idx, tick,
                                s[0], s[1], s[2]));
                    }
                }
                o.ticks = tick + 1;
                if (o.collectedAt.size() >= o.souls) {
                    ctx.waitTicks(10);
                    break;
                }
                if (tick > 40 && tick % 20 == 0 && !ctx.computeOnClient(mc -> (Boolean) Mod.staticCall(RUNNER, "isActive"))) {
                    break;
                }
            }
            o.collected = o.collectedAt.size();
            o.activeAtEnd = ctx.computeOnClient(mc -> (Boolean) Mod.staticCall(RUNNER, "isActive"));
            ctx.runOnClient(mc -> {
                o.rescues = (Integer) Mod.staticCall("pathfinding.AutoWalker", "rescuesPlanned");
                o.rescuesDone = (Integer) Mod.staticCall("pathfinding.AutoWalker", "rescuesDone");
                o.sidesteps = (Integer) Mod.staticCall("pathfinding.AutoWalker", "sidesteps");
                o.failsafes = (Integer) Mod.staticCall("pathfinding.AutoWalker", "failsafes");
                o.clicks = (Integer) Mod.staticCall(RUNNER, "clicksSent");
                Object sk = Mod.field("com.killer560.hub.pathfinding.AutoSoulRunner", "skippedThisRun");
                o.skipped = sk instanceof Integer i ? i : -1;
                Object reason = Mod.staticCall("pathfinding.AutoWalker", "stopReason");
                o.stopReason = reason == null ? "" : reason.toString();
            });
            ctx.runOnClient(mc -> Mod.staticCall(RUNNER, "stop", "testkit end", false));
            // Why each missed soul was missed: the mod's own lines about skipping, in order.
            List<String> why = new ArrayList<>();
            for (String l : LogTap.since(logMark)) {
                if (l.contains("skipping") || l.contains("No way out") || l.contains("no way back") || l.contains("Stopped")
                        || l.contains("could not be reached")) {
                    why.add(l.replaceAll("^.*?\\] ", ""));
                }
            }
            for (int i = 0; i < souls.size(); i++) {
                if (!o.collectedAt.containsKey(i)) {
                    JsonObject sj = souls.get(i).getAsJsonObject();
                    o.missed.add("soul " + i + " head " + sj.getAsJsonArray("head") + " node " + sj.get("node").getAsString());
                }
            }
            if (!why.isEmpty()) {
                o.missed.add("mod said: " + String.join(" / ", why.subList(Math.max(0, why.size() - 12), why.size())));
            }
        }
        return o;
    }
}

package dev.testkit.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.testkit.gametest.hx.Hx;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.SignBlockEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 1103-worldexport-*: the mod's world exporter (killer560, 2026-11-03: "a full world download of the hypixel skyblock hub
 * for stuff like fairy soul pathfinding testing"), proven on the testkit's own server because the test cannot reach
 * Hypixel.
 * <ul>
 * <li>{@code 1103-worldexport-command}: {@code /k560worldexport} exists in a cheat dev build and in no other build.</li>
 * <li>{@code 1103-worldexport-export}: builds BAYOU_SPILL (a fairy island, ~18k blocks) plus a sign and a head on the
 * server, lets the client load it, reads the coverage (nothing missing in the island's box), records a sample of the
 * client's blocks, and exports through the mod. Requires region files, the level files, the entity list with the two
 * head stands, and a sampled block sheet.</li>
 * <li>{@code 1103-worldexport-reload-fairy}: starts a NEW server on the export alone ({@code TestServer.seedWorldFrom}),
 * requires every sampled block, the sign text and the head to read back the same on the client, then runs the mod's real
 * fairy soul pathfinding from beside the pool to soul 3 (the 1102-fairy-soul3 stretch) over the exported terrain and
 * requires the soul collected.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class WorldExportTests implements FabricClientGameTest {

    private static final String ISLAND = "BAYOU_SPILL";
    private static final String EXPORT_NAME = "testkit-worldexport";
    private static final BlockPos SIGN = new BlockPos(30, 240, -10);
    private static final BlockPos HEAD = new BlockPos(32, 240, -10);
    private static final String SIGN_TEXT = "WEX-1103";

    /** position -> block id the client saw before the export. */
    private static final Map<BlockPos, String> SAMPLE = new LinkedHashMap<>();
    private static Path exported;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        boolean ok = Mod.isCheat() && Mod.isDevTools();
        if (!Scenario.skip("1103-worldexport-command")) {
            Scenario.run(ctx, "1103-worldexport-command", (server, scenario) -> TestMap.on(server).survival().build(),
                    (server, scenario) -> {
                        boolean has = ctx.computeOnClient(mc -> {
                            var d = net.fabricmc.fabric.impl.command.client.ClientCommandInternals.getActiveDispatcher();
                            return d != null && d.getRoot().getChild("k560worldexport") != null;
                        });
                        if (has != ok) {
                            throw new AssertionError("/k560worldexport " + (has ? "exists" : "is missing") + " but cheat="
                                    + Mod.isCheat() + " devTools=" + Mod.isDevTools());
                        }
                        scenario.log("/k560worldexport " + (has ? "registered (cheat dev build)" : "absent (as it must be)"));
                    });
        }
        if (!ok) {
            for (String n : new String[]{"1103-worldexport-export", "1103-worldexport-reload-fairy", "1104-worldexport-guide"}) {
                if (!Scenario.skip(n)) {
                    Scenario.skipped(n, "the world exporter is cheat dev builds only");
                }
            }
            return;
        }
        if (!Scenario.skip("1104-worldexport-guide")) {
            guideCase(ctx);
        }
        boolean built = false;
        if (!Scenario.skip("1103-worldexport-export")) {
            exportCase(ctx);
            built = true;
        }
        if (!Scenario.skip("1103-worldexport-reload-fairy")) {
            if (!built || exported == null) {
                exported = Path.of(System.getProperty("killer560.worldexport.dir", TestServer.savesDir().resolve("wex").toString()))
                        .resolve(EXPORT_NAME);
                if (!Files.isRegularFile(exported.resolve("level.dat"))) {
                    throw new AssertionError("no export at " + exported + " - run 1103-worldexport-export first");
                }
            }
            reloadCase(ctx);
        }
    }

    // ------------------------------------------------------------------ capture area and end-of-walk guide

    /**
     * 1104-worldexport-guide (killer560, 2026-11-04: highlight the chunk to scan and a line to it near the end): with an
     * area one column of chunks larger than what the client holds, the guide must pick a MISSING chunk, the nearest one,
     * from the tick; a far area (most chunks missing) shows nothing; the area survives a reload from its file; an export
     * keeps only chunks inside the area.
     */
    @SuppressWarnings("unchecked")
    private static void guideCase(ClientGameTestContext ctx) {
        System.setProperty("killer560.worldexport.dir", TestServer.savesDir().resolve("wex-guide").toString());
        Scenario.run(ctx, "1104-worldexport-guide", (server, scenario) -> TestMap.on(server).survival().build(),
                (server, scenario) -> {
                    server.sync(60);
                    ctx.waitTicks(60);
                    java.util.Set<Long> have = new java.util.HashSet<>((java.util.Set<Long>) ctx.computeOnClient(mc ->
                            ((java.util.Map<Long, ?>) Mod.staticCall("worldexport.WorldExporter", "collect", mc.level)).keySet()));
                    int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                    for (long k : have) {
                        minX = Math.min(minX, net.minecraft.world.level.ChunkPos.getX(k));
                        maxX = Math.max(maxX, net.minecraft.world.level.ChunkPos.getX(k));
                        minZ = Math.min(minZ, net.minecraft.world.level.ChunkPos.getZ(k));
                        maxZ = Math.max(maxZ, net.minecraft.world.level.ChunkPos.getZ(k));
                    }
                    scenario.log("client holds " + have.size() + " chunks, box " + minX + "," + minZ + " to " + maxX + "," + maxZ);
                    if (have.size() < 30) {
                        throw new AssertionError("too few chunks loaded to test the guide: " + have.size());
                    }
                    int[] pc = ctx.computeOnClient(mc -> new int[]{mc.player.blockPosition().getX() >> 4,
                            mc.player.blockPosition().getZ() >> 4});

                    // 1. one missing column on the +x side: the tick must highlight the nearest missing chunk
                    Mod.staticCall("worldexport.CaptureGuide", "setArea", minX, minZ, maxX + 1, maxZ);
                    Mod.staticCall("worldexport.EntityRecorder", "setRecording", true);
                    ctx.waitTicks(40);
                    int[] t = (int[]) Mod.staticCall("worldexport.CaptureGuide", "target");
                    if (t == null) {
                        throw new AssertionError("the guide shows nothing with one column of chunks missing (counts "
                                + java.util.Arrays.toString((int[]) Mod.staticCall("worldexport.CaptureGuide", "lastCounts")) + ")");
                    }
                    if (t[0] != maxX + 1 || t[1] < minZ || t[1] > maxZ || have.contains(net.minecraft.world.level.ChunkPos.pack(t[0], t[1]))) {
                        throw new AssertionError("the guide targets " + t[0] + "," + t[1] + ", not a missing chunk of column " + (maxX + 1));
                    }
                    int best = Integer.MAX_VALUE;
                    for (int z = minZ; z <= maxZ; z++) {
                        best = Math.min(best, (maxX + 1 - pc[0]) * (maxX + 1 - pc[0]) + (z - pc[1]) * (z - pc[1]));
                    }
                    int got = (t[0] - pc[0]) * (t[0] - pc[0]) + (t[1] - pc[1]) * (t[1] - pc[1]);
                    if (got != best) {
                        throw new AssertionError("the guide's chunk is at squared distance " + got + ", the nearest missing is " + best);
                    }
                    scenario.log("PASS guide targets missing chunk " + t[0] + "," + t[1] + " (nearest of the missing column)");

                    // 2. a far-off area: nearly all missing, so no guide
                    Mod.staticCall("worldexport.CaptureGuide", "setArea", minX, minZ, maxX + 60, maxZ + 60);
                    ctx.waitTicks(40);
                    if (Mod.staticCall("worldexport.CaptureGuide", "target") != null) {
                        throw new AssertionError("the guide shows with most of the area missing");
                    }
                    scenario.log("PASS no guide while most of the area is missing");

                    // 3. the area survives a restart (memory dropped, read from area.json)
                    Mod.staticCall("worldexport.CaptureGuide", "setArea", minX, minZ, maxX + 1, maxZ);
                    Mod.staticCall("worldexport.CaptureGuide", "forgetMemory");
                    int[] back = (int[]) Mod.staticCall("worldexport.CaptureGuide", "area");
                    if (back == null || back[0] != minX || back[1] != minZ || back[2] != maxX + 1 || back[3] != maxZ) {
                        throw new AssertionError("the area did not reload: " + java.util.Arrays.toString(back));
                    }
                    scenario.log("PASS area reloads from its file");

                    // 4. an export keeps only chunks inside the area
                    Mod.staticCall("worldexport.EntityRecorder", "setRecording", false);
                    Mod.staticCall("worldexport.CaptureGuide", "setArea", minX, minZ, minX + 1, minZ + 1);
                    Object result = ctx.computeOnClient(mc -> Mod.staticCall("worldexport.WorldExporter", "export", mc.level, "guide-crop"));
                    int chunks = ((Number) Mod.call(result, "chunks")).intValue();
                    if (chunks < 1 || chunks > 4 || chunks >= have.size()) {
                        throw new AssertionError("export kept " + chunks + " chunks for a 2x2 area (client held " + have.size() + ")");
                    }
                    scenario.log("PASS export cropped to the area: " + chunks + " of " + have.size() + " chunks");
                    Mod.staticCall("worldexport.CaptureGuide", "clearArea");
                    if (Mod.staticCall("worldexport.CaptureGuide", "area") != null) {
                        throw new AssertionError("clearArea left an area");
                    }
                });
    }

    private static String id(net.minecraft.world.level.block.state.BlockState s) {
        return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
    }

    private static String baseId(String line) {
        String b = line.substring(line.indexOf(' ', line.indexOf(' ', line.indexOf(' ') + 1) + 1) + 1).trim();
        int br = b.indexOf('[');
        if (br >= 0) {
            b = b.substring(0, br);
        }
        return b.contains(":") ? b : "minecraft:" + b;
    }

    private static BlockPos posOf(String line) {
        String[] p = line.split(" ");
        return new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
    }

    // ------------------------------------------------------------------ export

    private static void exportCase(ClientGameTestContext ctx) {
        Path base = TestServer.savesDir().resolve("wex");
        System.setProperty("killer560.worldexport.dir", base.toString());
        Scenario.run(ctx, "1103-worldexport-export", (server, scenario) -> TestMap.on(server).survival().build(),
                (server, scenario) -> {
                    JsonObject meta = FairyIslandTests.meta(ISLAND);
                    JsonArray spawn = meta.getAsJsonArray("spawn");
                    try (Hx hx = Hx.connect()) {
                        server.command(String.format(java.util.Locale.ROOT, "tp @p %.2f %d %.2f 0 0", spawn.get(0).getAsDouble(),
                                spawn.get(1).getAsInt() + 3, spawn.get(2).getAsDouble()));
                        server.sync(60);
                        ctx.waitTicks(40);
                        List<String> lines = FairyIslandTests.blocks(ISLAND);
                        FairyIslandTests.place(hx, lines);
                        server.command("setblock " + SIGN.getX() + " " + SIGN.getY() + " " + SIGN.getZ() + " minecraft:oak_sign");
                        server.command("data merge block " + SIGN.getX() + " " + SIGN.getY() + " " + SIGN.getZ()
                                + " {front_text:{messages:['\"" + SIGN_TEXT + "\"','\"\"','\"\"','\"\"']}}");
                        server.command("setblock " + HEAD.getX() + " " + HEAD.getY() + " " + HEAD.getZ() + " minecraft:player_head");
                        server.sync(300);
                        ctx.waitTicks(80);
                        scenario.log("placed " + lines.size() + " island blocks + a sign and a head");
                        // two head stands, how Hypixel shows a fairy soul
                        hx.stand(33.5, 175.0, -45.5, null, "marker", false, "invisible", true, "helmet", "minecraft:player_head");
                        hx.stand(2.5, 180.0, 10.5, "Soul", "marker", false, "invisible", false, "helmet", "minecraft:player_head");
                        Mod.staticCall("worldexport.EntityRecorder", "setRecording", true);
                        ctx.waitTicks(60);

                        // sample the client's own blocks
                        Random rnd = new Random(1103);
                        SAMPLE.clear();
                        int wrong = 0;
                        for (int i = 0; i < 600; i++) {
                            String line = lines.get(rnd.nextInt(lines.size()));
                            BlockPos pos = posOf(line);
                            String want = baseId(line);
                            String have = ctx.computeOnClient(mc -> id(mc.level.getBlockState(pos)));
                            if (!want.equals(have)) {
                                wrong++;
                                scenario.log("sample differs at " + pos.toShortString() + ": line says " + want + ", client has " + have);
                            }
                            SAMPLE.put(pos, have);
                        }
                        if (wrong > 12) {
                            throw new AssertionError("the client did not receive the island: " + wrong + "/600 sampled blocks differ");
                        }

                        // coverage of the island's own box: nothing may be missing
                        JsonArray b = meta.getAsJsonArray("bounds");
                        int[] box = {b.get(0).getAsInt(), b.get(2).getAsInt(), b.get(3).getAsInt(), b.get(5).getAsInt()};
                        Object cov = ctx.computeOnClient(mc -> Mod.staticCall("worldexport.WorldExportCommand", "measure", mc.level, box));
                        int missing = ((Number) Mod.call(cov, "missing")).intValue();
                        int boxChunks = ((Number) Mod.call(cov, "boxChunks")).intValue();
                        scenario.log("coverage of the island box: " + (boxChunks - missing) + "/" + boxChunks + " chunks");
                        if (missing != 0 || boxChunks == 0) {
                            throw new AssertionError("the coverage readout says " + missing + " of " + boxChunks + " island chunks are missing");
                        }
                        // a box far away must read as missing, so the readout is not just always zero
                        int[] far = {5000, 5000, 5100, 5100};
                        Object cov2 = ctx.computeOnClient(mc -> Mod.staticCall("worldexport.WorldExportCommand", "measure", mc.level, far));
                        int missing2 = ((Number) Mod.call(cov2, "missing")).intValue();
                        if (missing2 != ((Number) Mod.call(cov2, "boxChunks")).intValue() || missing2 == 0) {
                            throw new AssertionError("a box with nothing in it did not read as all missing: " + missing2);
                        }

                        Object result = ctx.computeOnClient(mc -> {
                            try {
                                return Mod.staticCall("worldexport.WorldExporter", "export", mc.level, EXPORT_NAME);
                            } catch (RuntimeException e) {
                                throw new AssertionError("export threw: " + e + " / " + e.getCause(), e);
                            }
                        });
                        int chunks = ((Number) Mod.call(result, "chunks")).intValue();
                        int bes = ((Number) Mod.call(result, "blockEntities")).intValue();
                        int ents = ((Number) Mod.call(result, "entities")).intValue();
                        exported = (Path) Mod.call(result, "folder");
                        scenario.log("exported " + chunks + " chunks, " + bes + " block entities, " + ents + " entities to " + exported);
                        if (chunks < boxChunks || bes < 2 || ents < 2) {
                            throw new AssertionError("export is thin: chunks " + chunks + " (island box " + boxChunks
                                    + "), block entities " + bes + ", entities " + ents);
                        }
                        Path region = exported.resolve("dimensions/minecraft/overworld/region");
                        long mca;
                        try (var s = Files.list(region)) {
                            mca = s.filter(p -> p.toString().endsWith(".mca") && p.toFile().length() > 8192).count();
                        } catch (IOException e) {
                            throw new AssertionError(e);
                        }
                        if (mca == 0 || !Files.isRegularFile(exported.resolve("level.dat"))
                                || !Files.isRegularFile(exported.resolve("data/minecraft/world_gen_settings.dat"))) {
                            throw new AssertionError("missing world files in " + exported + " (non-empty .mca " + mca + ")");
                        }
                        JsonObject ej;
                        try {
                            ej = JsonParser.parseString(Files.readString(exported.resolve("entities.json"), StandardCharsets.UTF_8))
                                    .getAsJsonObject();
                        } catch (IOException e) {
                            throw new AssertionError(e);
                        }
                        int heads = 0;
                        for (JsonElement e : ej.getAsJsonArray("entities")) {
                            if (e.getAsJsonObject().has("head")) {
                                heads++;
                            }
                        }
                        if (heads < 2) {
                            throw new AssertionError("entities.json holds " + heads + " head stands, wanted 2");
                        }
                        Mod.staticCall("worldexport.EntityRecorder", "setRecording", false);
                        scenario.log(mca + " region files, entities.json has " + heads + " head stands");
                    }
                });
    }

    // ------------------------------------------------------------------ reload + fairy

    private static void reloadCase(ClientGameTestContext ctx) {
        try {
            TestServer.seedWorldFrom(exported, new int[]{32, 179, -10});
            Scenario.run(ctx, "1103-worldexport-reload-fairy", (server, scenario) -> TestMap.on(server).survival().build(),
                    (server, scenario) -> {
                        server.command("gamerule random_tick_speed 0");
                        JsonObject meta = FairyIslandTests.meta(ISLAND);
                        JsonArray spawn = meta.getAsJsonArray("spawn");
                        server.command(String.format(java.util.Locale.ROOT, "tp @p %.2f %d %.2f 0 0", spawn.get(0).getAsDouble(),
                                spawn.get(1).getAsInt() + 3, spawn.get(2).getAsDouble()));
                        server.sync(60);
                        ctx.waitTicks(120);
                        if (SAMPLE.isEmpty()) {
                            throw new AssertionError("no block sample (the export case did not run in this JVM)");
                        }
                        List<String> bad = new ArrayList<>();
                        for (Map.Entry<BlockPos, String> e : SAMPLE.entrySet()) {
                            String have = ctx.computeOnClient(mc -> id(mc.level.getBlockState(e.getKey())));
                            // The export carries no light (the server relights), and grass in the dark dies to dirt on a random
                            // tick. That is the server's rule, not a lost block: tolerated for grass only.
                            if (!have.equals(e.getValue()) && !(e.getValue().endsWith("grass_block") && have.endsWith(":dirt"))) {
                                bad.add(e.getKey().toShortString() + " wanted " + e.getValue() + " got " + have);
                            }
                        }
                        scenario.log("reloaded world: " + (SAMPLE.size() - bad.size()) + "/" + SAMPLE.size() + " sampled blocks match");
                        if (!bad.isEmpty()) {
                            throw new AssertionError("blocks differ after the reload: " + bad.subList(0, Math.min(8, bad.size())));
                        }
                        String signText = ctx.computeOnClient(mc -> mc.level.getBlockEntity(SIGN) instanceof SignBlockEntity s
                                ? s.saveWithFullMetadata(mc.level.registryAccess()).toString() : "no sign entity");
                        String headBlock = ctx.computeOnClient(mc -> id(mc.level.getBlockState(HEAD)));
                        boolean headEntity = ctx.computeOnClient(mc -> mc.level.getBlockEntity(HEAD) != null);
                        scenario.log("sign reads '" + signText + "', head block " + headBlock + " entity " + headEntity);
                        if (!signText.contains(SIGN_TEXT) || !headBlock.endsWith("player_head") || !headEntity) {
                            throw new AssertionError("block entities did not survive: sign '" + signText + "', head " + headBlock);
                        }

                        // the mod's real fairy soul pathfinding over the exported terrain: soul 3, as 1102-fairy-soul3
                        FairyIslandTests.startOverride = System.getProperty("testkit.fairy.soul3Start", "53.5,192.0,-8.5,180,10");
                        FairyIslandTests.stopAfterSouls = 1;
                        FairyIslandTests.skipPlace = true;
                        FairyIslandTests.Outcome o;
                        try {
                            o = FairyIslandTests.runIsland(ctx, server, scenario, ISLAND, meta, 100, 1200);
                        } finally {
                            FairyIslandTests.startOverride = null;
                            FairyIslandTests.stopAfterSouls = Integer.MAX_VALUE;
                            FairyIslandTests.skipPlace = false;
                        }
                        scenario.log(o.line());
                        if (!o.collectedAt.containsKey(3)) {
                            throw new AssertionError("the walker did not reach soul 3 on the exported world - collected "
                                    + o.collectedAt.keySet() + "; " + o.missed);
                        }
                        scenario.log("soul 3 reached on the exported world");
                    });
        } finally {
            TestServer.seedWorldFrom(null, null);
        }
    }
}

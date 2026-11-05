package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod chscan-1104 (killer560, 2026-11-04: "it should do a scan of every chunk once for the structures", "there should be a
 * waypoint there with the name of the structure as well"). 1104-chscan-scan, cheat jar only: in the Crystal Hollows the
 * Structure Scanner finds a Mines of Divan column 9 chunks away (past the old 8-chunk cap) from the chunks already loaded
 * when it is switched on, adds it to the map as SCANNED, and the world waypoint draws the name "Mines of Divan"; a Jungle
 * Temple column in a chunk that was NOT loaded then is found once that chunk arrives (the chunk-load path).
 */
final class HxChScan1104Cases {

    private static final String CFG = "mining.scanner.StructureScannerConfig";
    private static final String FEATURE = "mining.scanner.StructureScannerFeature";
    private static final String STATE = "miningqol.MiningState";
    /** Where the player stands for the first scan, and the Mines of Divan column (chunk 34 vs 43: 9 chunks out). */
    private static final int[] B = {700, 100, 300};
    private static final int[] DIVAN = {552, 60, 300};
    /** Far from B (chunk 13 vs 43, past render distance 12): the Jungle Temple column, loaded only when the player goes there. */
    private static final int[] A = {210, 100, 210};
    private static final int[] TEMPLE = {212, 60, 212};

    private HxChScan1104Cases() {
    }

    static void register(Session s) {
        s.test("1104-chscan-scan", HxChScan1104Cases::scan);
    }

    /** Every spot the case uses, kept loaded on the server so its setblock/fill land (9 chunks out is past what a
     *  fresh teleport has loaded). */
    private static final int[][] SPOTS = {A, B, DIVAN, TEMPLE, {572, 100, 320}};
    /** The Mines of Divan as built here: a 21 x 7 x 21 block of marker blocks whose corner holds the matched column, so its
     *  true center is (562, 63, 310), not the column (scanfold-1105, killer560 2026-11-05: "make this waypoint actually be
     *  in the middle of the area"). */
    private static final int[] DIVAN_CENTER = {562, 63, 310};

    private static void forceload(Session c, String verb) {
        for (int[] at : SPOTS) {
            c.server().command(String.format(Locale.ROOT, "forceload %s %d %d", verb, at[0], at[2]));
        }
    }

    private static void column(Session c, int[] at, List<String> blocks) {
        for (int i = 0; i < blocks.size(); i++) {
            c.server().command(String.format(Locale.ROOT, "setblock %d %d %d %s", at[0], at[1] + i, at[2], blocks.get(i)));
        }
    }

    private static void clear(Session c, int[] at) {
        c.server().command("fill 552 60 300 572 66 320 minecraft:air");
        c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air",
                at[0], at[1], at[2], at[0], at[1] + 3, at[2]));
    }

    private static void tp(Session c, int[] at) {
        // The floor first (its chunk is forceloaded), so the player lands on it instead of falling (Grim Simulation).
        c.server().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:stone", at[0], at[1] - 1, at[2]));
        c.server().command(String.format(Locale.ROOT, "tp @a %.1f %d %.1f 0 90", at[0] + 0.5, at[1], at[2] + 0.5));
    }

    private static int[] xyz(String blockPos) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("x=(-?\\d+), y=(-?\\d+), z=(-?\\d+)").matcher(blockPos);
        return m.find() ? new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))}
                : new int[]{Integer.MIN_VALUE, 0, 0};
    }

    private static boolean near(int[] a, int[] b, int tol) {
        return Math.abs(a[0] - b[0]) <= tol && Math.abs(a[1] - b[1]) <= tol && Math.abs(a[2] - b[2]) <= tol;
    }

    /** On the client thread only (inside onClient or a waitUntil predicate). */
    private static Object at(String sig) {
        return Mod.staticCall(FEATURE, "position", Mod.enumValue("mining.scanner.ChSignature", sig));
    }

    private static Object position(Session c, String sig) {
        return c.onClient(mc -> at(sig));
    }

    private static void scan(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: the Structure Scanner is cheat-only and never registered");
            return;
        }
        double[] home = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        int rd = c.onClient(mc -> mc.options.renderDistance().get());
        List<String> divan = List.of("minecraft:quartz_pillar", "minecraft:quartz_stairs", "minecraft:stone_brick_stairs",
                "minecraft:chiseled_stone_bricks");
        List<String> temple = List.of("minecraft:bedrock", "minecraft:bedrock", "minecraft:clay", "minecraft:clay");
        try (Settings set = new Settings(c).with(CFG, "Enabled", false).with(CFG, "Waypoints", true)
                .with(CFG, "ShowOnMap", true).with(CFG, "ChatOnFind", false)
                // Live Map's Keep Chunks Loaded (default ON) cancels the forget-chunk packet, so the temple would never unload.
                .with("livemap.LiveMapConfig", "KeepChunksLoaded", false)) {
            // Both columns placed while the scanner is off; the temple's chunk then unloads on the client (the player goes
            // 30 chunks away).
            forceload(c, "add");
            // The test client's render distance is 5; the Mines of Divan sits 9 chunks out (server view distance 10).
            c.ctx().runOnClient(mc -> {
                mc.options.renderDistance().set(12);
                mc.options.broadcastOptions();
            });
            c.ctx().waitTicks(20);
            tp(c, A);
            c.ctx().waitTicks(40);
            column(c, TEMPLE, temple);
            tp(c, B);
            c.ctx().waitTicks(40);
            c.server().command("fill 552 60 300 572 66 320 minecraft:chiseled_stone_bricks");
            column(c, DIVAN, divan);
            // The real (vanilla) chunk: level.getChunk also serves the Chunk Cache's kept copy (default ON). The forget
            // packet can lag under load: give it up to 10 s.
            boolean templeLoaded = true;
            for (int i = 0; i < 20 && templeLoaded; i++) {
                c.ctx().waitTicks(10);
                templeLoaded = c.onClient(mc -> Mod.staticCall("chunkcache.ChunkCacheManager", "vanillaGetChunk",
                        mc.level, TEMPLE[0] >> 4, TEMPLE[2] >> 4,
                        net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) != null);
            }
            c.note("temple chunk loaded at B: " + templeLoaded);
            c.check(!templeLoaded, "the Jungle Temple chunk is still loaded at B, so the chunk-load path is not tested");

            c.ctx().runOnClient(mc -> {
                Mod.staticCall("mining.chmap.ChDiscovery", "clear");
                Mod.call(Mod.cfg(CFG), "setEnabled", true);
            });
            c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Mithril Deposits", "Purse: 0");
            c.hx().tab(List.of("Area: Crystal Hollows", "Profile: Test", ""), null, null);
            c.waitUntil("Crystal Hollows", mc -> "CRYSTAL_HOLLOWS".equals(Mod.staticCall(STATE, "island")), 100);

            c.waitUntil("the scanner finds the Mines of Divan 9 chunks out", mc -> at("MINES_OF_DIVAN") != null, 400);
            c.note("Mines of Divan at " + position(c, "MINES_OF_DIVAN"));
            int[] got = xyz(String.valueOf(position(c, "MINES_OF_DIVAN")));
            c.check(near(got, DIVAN_CENTER, 3), "Mines of Divan marked at " + java.util.Arrays.toString(got)
                    + ", want within 3 blocks of the structure's center " + java.util.Arrays.toString(DIVAN_CENTER)
                    + " (not the stair column at its corner)");
            Object find = c.onClient(mc -> Mod.staticCall("mining.chmap.ChDiscovery", "find",
                    Mod.enumValue("mining.chmap.ChStructure", "MINES_OF_DIVAN")));
            c.check(find != null && "SCANNED".equals(String.valueOf(Mod.field(find, "source"))),
                    "Show On Map: the map holds " + find + ", want a SCANNED Mines of Divan");
            c.check(find != null && Math.abs((double) Mod.call(find, "centreX") - DIVAN_CENTER[0]) <= 3
                            && Math.abs((double) Mod.call(find, "centreZ") - DIVAN_CENTER[2]) <= 3,
                    "the map's Mines of Divan is centered on " + (find == null ? "nothing" : Mod.call(find, "centreX") + ", "
                            + Mod.call(find, "centreZ")) + ", want the structure's center");
            // Waypoint color = the zone's Crystal Hollows Walls color, and follows a change of it.
            Object divanKind = Mod.enumValue("mining.chmap.ChStructure", "MINES_OF_DIVAN");
            Object zone = Mod.enumValue("miningqol.CrystalHollowsWalls$Zone", "MITHRIL");
            Object qol = Mod.staticCall("miningqol.MiningQolConfig", "getInstance");
            int before = c.onClient(mc -> (Integer) Mod.call(divanKind, "zoneColor", 562.0, 63.0, 310.0));
            int want = c.onClient(mc -> 0xFF000000 | (Integer) Mod.call(qol, "getChWallColor", zone));
            c.check(before == want, "Mines of Divan waypoint color " + Integer.toHexString(before) + ", want its zone's "
                    + Integer.toHexString(want));
            int oldRgb = want & 0xFFFFFF;
            c.ctx().runOnClient(mc -> Mod.call(qol, "setChWallColor", zone, 0x123456));
            int changed = c.onClient(mc -> (Integer) Mod.call(divanKind, "zoneColor", 562.0, 63.0, 310.0));
            c.ctx().runOnClient(mc -> Mod.call(qol, "setChWallColor", zone, oldRgb));
            c.check(changed == 0xFF123456, "after changing the Mithril zone color the waypoint is " + Integer.toHexString(changed));
            c.waitUntil("the waypoint draws its name", mc -> "Mines of Divan".equals(Mod.staticCall(FEATURE, "lastLabel")), 60);
            c.check(position(c, "JUNGLE_TEMPLE") == null, "the Jungle Temple was found while its chunk was not loaded");

            tp(c, A);
            c.waitUntil("the Jungle Temple is found once its chunk loads", mc -> at("JUNGLE_TEMPLE") != null, 600);
            c.waitUntil("the temple waypoint draws its name", mc -> "Jungle Temple".equals(Mod.staticCall(FEATURE, "lastLabel")), 60);
            c.note("Jungle Temple at " + position(c, "JUNGLE_TEMPLE") + "; last label " + c.onClient(mc ->
                    Mod.staticCall(FEATURE, "lastLabel")));

            // Switched off and on, it scans from scratch (no Scan Again button any more).
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setEnabled", false));
            c.ctx().waitTicks(3);
            c.check(position(c, "JUNGLE_TEMPLE") == null, "switched off, the scanner still holds its finds");
        } finally {
            // Each column cleared while the player stands by it (a server fill needs the chunk loaded).
            tp(c, A);
            c.ctx().waitTicks(20);
            clear(c, TEMPLE);
            tp(c, B);
            c.ctx().waitTicks(20);
            clear(c, DIVAN);
            c.ctx().runOnClient(mc -> Mod.staticCall("mining.chmap.ChDiscovery", "clear"));
            c.server().command(String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f", home[0], home[1], home[2]));
            forceload(c, "remove");
            c.ctx().runOnClient(mc -> {
                mc.options.renderDistance().set(rd);
                mc.options.broadcastOptions();
            });
            hub(c);
        }
    }
}

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
    /** Where the player stands for the first scan, and the Mines of Divan column (chunk 34 vs 25: 9 chunks out). */
    private static final int[] B = {400, 100, 300};
    private static final int[] DIVAN = {552, 60, 300};
    /** Far from B (chunk 13 vs 25): the Jungle Temple column, loaded only when the player goes there. */
    private static final int[] A = {210, 100, 210};
    private static final int[] TEMPLE = {212, 60, 212};

    private HxChScan1104Cases() {
    }

    static void register(Session s) {
        s.test("1104-chscan-scan", HxChScan1104Cases::scan);
    }

    /** Every spot the case uses, kept loaded on the server so its setblock/fill land (9 chunks out is past what a
     *  fresh teleport has loaded). */
    private static final int[][] SPOTS = {A, B, DIVAN, TEMPLE};

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
        c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air",
                at[0], at[1], at[2], at[0], at[1] + 3, at[2]));
    }

    private static void tp(Session c, int[] at) {
        c.server().command(String.format(Locale.ROOT, "tp @a %.1f %d %.1f 0 90", at[0] + 0.5, at[1], at[2] + 0.5));
        c.server().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:stone", at[0], at[1] - 1, at[2]));
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
        List<String> divan = List.of("minecraft:quartz_pillar", "minecraft:quartz_stairs", "minecraft:stone_brick_stairs",
                "minecraft:chiseled_stone_bricks");
        List<String> temple = List.of("minecraft:bedrock", "minecraft:bedrock", "minecraft:clay", "minecraft:clay");
        try (Settings set = new Settings(c).with(CFG, "Enabled", false).with(CFG, "Waypoints", true)
                .with(CFG, "ShowOnMap", true).with(CFG, "ChatOnFind", false)) {
            // Both columns placed while the scanner is off; the temple's chunk then unloads on the client (the player goes
            // 12+ chunks away).
            forceload(c, "add");
            c.ctx().waitTicks(20);
            tp(c, A);
            c.ctx().waitTicks(40);
            column(c, TEMPLE, temple);
            tp(c, B);
            c.ctx().waitTicks(40);
            column(c, DIVAN, divan);
            c.ctx().waitTicks(10);
            boolean templeLoaded = c.onClient(mc -> mc.level.getChunk(TEMPLE[0] >> 4, TEMPLE[2] >> 4,
                    net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) != null);
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
            c.check(String.valueOf(position(c, "MINES_OF_DIVAN")).contains("x=552, y=65, z=300"),
                    "Mines of Divan marked at " + position(c, "MINES_OF_DIVAN") + ", want 552, 65, 300");
            Object find = c.onClient(mc -> Mod.staticCall("mining.chmap.ChDiscovery", "find",
                    Mod.enumValue("mining.chmap.ChStructure", "MINES_OF_DIVAN")));
            c.check(find != null && "SCANNED".equals(String.valueOf(Mod.field(find, "source"))),
                    "Show On Map: the map holds " + find + ", want a SCANNED Mines of Divan");
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
            hub(c);
        }
    }
}

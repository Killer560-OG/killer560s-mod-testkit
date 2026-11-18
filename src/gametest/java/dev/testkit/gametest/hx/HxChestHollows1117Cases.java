package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * chesthl-ch-1117 (killer560 2026-11-17: "it should never under any circumstance turn on outside the crystal hollows"):
 * chest tracking, boxes, hides and clicks run only after a positive Crystal Hollows read made in the CURRENT world. No line,
 * dig or empty read turns it on; the read is forgotten on a world change; another island's read clears the chests; an
 * empty read in the same world keeps them.
 */
final class HxChestHollows1117Cases {

    private static final String CFG = "powdermining.PowderMiningConfig";
    private static final String TC = "powdermining.TreasureChests";
    private static final String STATE = "miningqol.MiningState";
    private static final String UNCOVERED = "§6You uncovered a treasure chest!";

    private HxChestHollows1117Cases() {
    }

    static void register(Session s) {
        s.test("1117-chesthl-hollows", HxChestHollows1117Cases::inHollows);
        s.test("1117-chesthl-other-islands", HxChestHollows1117Cases::otherIslands);
        s.test("1117-chesthl-lapse", HxChestHollows1117Cases::lapse);
        s.test("1117-chesthl-worldchange", HxChestHollows1117Cases::worldChange);
        s.test("1117-chesthl-entrypoints", HxChestHollows1117Cases::entryPoints);
    }

    private static void hollows(Session c) {
        c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Jungle", "Purse: 0");
        c.hx().tab(List.of("Area: Crystal Hollows", "Profile: Test", "", "Powders:", " Gemstone: 1,000", ""), null, null);
        c.waitUntil("Crystal Hollows", mc -> "CRYSTAL_HOLLOWS".equals(Mod.staticCall(STATE, "island")), 100);
    }

    /** A tab list read as "island name" (an Area line), or with none at all (the lapse). */
    private static void area(Session c, String area, String island) {
        c.hx().tab(area == null ? List.of("Profile: Test", "") : List.of("Area: " + area, "Profile: Test", ""), null, null);
        c.waitUntil("island " + island, mc -> island.equals(Mod.staticCall(STATE, "island")), 100);
    }

    private static Settings on(Session c, Settings s) {
        Object cfg = c.onClient(mc -> Mod.cfg(CFG));
        boolean old = c.onClient(mc -> (Boolean) Mod.call(cfg, "isTreasureChestRaw"));
        return s.custom(() -> Mod.call(cfg, "setTreasureChest", true), () -> Mod.call(cfg, "setTreasureChest", old));
    }

    private static int intCall(Session c, String m) {
        return c.onClient(mc -> ((Number) Mod.staticCall(TC, m)).intValue());
    }

    private static boolean tracked(Session c, int x, int y, int z) {
        return c.onClient(mc -> (Boolean) Mod.staticCall(TC, "tracked", x, y, z));
    }

    private static void setblock(Session c, int x, int y, int z, String block) {
        c.server().command(String.format(Locale.ROOT, "setblock %d %d %d %s", x, y, z, block));
    }

    private static int[] feet(Session c) {
        double[] at = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        return new int[]{(int) Math.floor(at[0]), (int) Math.floor(at[1]), (int) Math.floor(at[2])};
    }

    private static void claim(Session c, int x, int y, int z) {
        c.hx().chat(UNCOVERED);
        c.ctx().waitTicks(2);
        setblock(c, x, y, z, "minecraft:chest");
        c.waitUntil("claimed " + x + " " + y + " " + z, mc -> (Boolean) Mod.staticCall(TC, "tracked", x, y, z), 40);
    }

    /** The uncovered line, his dig beside the spot, then a chest block there; waits and returns. Nothing may track it. */
    private static void tryClaim(Session c, int x, int y, int z) {
        c.hx().chat(UNCOVERED);
        c.ctx().runOnClient(mc -> Mod.staticCall(TC, "digForTest", x, y, z));
        c.ctx().waitTicks(2);
        setblock(c, x, y, z, "minecraft:chest");
        c.ctx().waitTicks(30);
    }

    private static void nothing(Session c, String where, int x, int y, int z, int boxes0) {
        c.check(!tracked(c, x, y, z) && intCall(c, "count") == 0, where + ": a chest was tracked (count " + intCall(c, "count") + ")");
        c.check(intCall(c, "boxFrames") == boxes0, where + ": a chest box was drawn (" + boxes0 + " -> " + intCall(c, "boxFrames") + ")");
        c.check(intCall(c, "digCount") == 0, where + ": his dig was recorded");
    }

    private static void inHollows(Session c) {
        int[] f = feet(c);
        int x = f[0] + 3, y = f[1], z = f[2];
        try (Settings set = on(c, new Settings(c)).with(CFG, "ChestAlertSound", false).with(CFG, "ChestAlertTitle", false)) {
            hollows(c);
            int b0 = intCall(c, "boxFrames");
            claim(c, x, y, z);
            c.waitUntil("box drawn", mc -> ((Number) Mod.staticCall(TC, "boxFrames")).intValue() > b0, 60);
            c.check(intCall(c, "count") >= 1, "Hollows: no chest tracked");
        } finally {
            setblock(c, x, y, z, "minecraft:air");
            hub(c);
        }
    }

    private static void otherIslands(Session c) {
        int[] f = feet(c);
        int x = f[0] + 3, y = f[1], z = f[2];
        try (Settings set = on(c, new Settings(c)).with(CFG, "ChestAlertSound", false).with(CFG, "ChestAlertTitle", false)) {
            // (e) leaving the Hollows to a known other island clears what was tracked, at once
            hollows(c);
            claim(c, x, y, z);
            area(c, "Dwarven Mines", "DWARVEN_MINES");
            c.waitUntil("chests cleared", mc -> ((Number) Mod.staticCall(TC, "count")).intValue() == 0, 40);
            setblock(c, x, y, z, "minecraft:air");
            c.ctx().waitTicks(5);
            // (b) on each other island the line + dig + chest produce nothing
            for (String where : new String[]{"Dwarven Mines", "Mineshaft", "hub"}) {
                if (where.equals("hub")) hub(c);
                else c.hx().tab(List.of("Area: " + where, "Profile: Test", ""), null, null);
                c.ctx().waitTicks(12);
                int b0 = intCall(c, "boxFrames");
                tryClaim(c, x, y, z);
                nothing(c, where, x, y, z, b0);
                setblock(c, x, y, z, "minecraft:air");
                c.ctx().waitTicks(3);
            }
        } finally {
            setblock(c, x, y, z, "minecraft:air");
            hub(c);
        }
    }

    /** (d) The Area line lapses to empty in the same world: tracked chests stay and a second one is still claimed. */
    private static void lapse(Session c) {
        int[] f = feet(c);
        int x = f[0] + 3, y = f[1], z = f[2];
        try (Settings set = on(c, new Settings(c)).with(CFG, "ChestAlertSound", false).with(CFG, "ChestAlertTitle", false)) {
            hollows(c);
            claim(c, x, y, z);
            area(c, null, "");
            c.ctx().waitTicks(20);
            c.check(tracked(c, x, y, z), "an empty island read in the Hollows dropped the tracked chest");
            claim(c, x, y + 0, z + 3);
            c.check(intCall(c, "count") >= 2, "after the lapse a second chest was not claimed");
        } finally {
            setblock(c, x, y, z, "minecraft:air");
            setblock(c, x, y, z + 3, "minecraft:air");
            hub(c);
        }
    }

    /** Hollows -> world change -> an empty read on the new world: line + digging + chest block track nothing. */
    private static void worldChange(Session c) {
        int[] f = feet(c);
        double px = f[0] + 0.5, py = f[1], pz = f[2] + 0.5;
        try (Settings set = on(c, new Settings(c)).with(CFG, "ChestAlertSound", false).with(CFG, "ChestAlertTitle", false)) {
            hollows(c);
            claim(c, f[0] + 3, f[1], f[2]);
            HxRejoin1111Cases.awayAndBack(c, px, py, pz);
            c.hx().tab(List.of("Profile: Test", ""), null, null); // the new world's tab list: no Area line
            c.waitUntil("island read empty", mc -> "".equals(Mod.staticCall(STATE, "island")), 100);
            c.waitUntil("chests gone with the world", mc -> ((Number) Mod.staticCall(TC, "count")).intValue() == 0, 60);
            f = feet(c);
            int b0 = intCall(c, "boxFrames");
            tryClaim(c, f[0] + 3, f[1], f[2]);
            nothing(c, "new world, empty read", f[0] + 3, f[1], f[2], b0);
        } finally {
            setblock(c, f[0] + 3, f[1], f[2], "minecraft:air");
            hub(c);
        }
    }

    /** Gate off (hub): every entry point is driven and none creates, records or draws a chest. */
    private static void entryPoints(Session c) {
        int[] f = feet(c);
        int x = f[0] + 3, y = f[1], z = f[2];
        BlockPos p = new BlockPos(x, y, z);
        try (Settings set = on(c, new Settings(c)).with(CFG, "ChestAlertSound", false).with(CFG, "ChestAlertTitle", false)
                .with(CFG, "TreasureChestHideOpened", true)) {
            hub(c);
            c.ctx().waitTicks(12);
            int b0 = intCall(c, "boxFrames");
            int cl0 = intCall(c, "claims");
            int al0 = intCall(c, "alerts");
            setblock(c, x, y, z, "minecraft:chest");
            c.ctx().waitTicks(5);
            c.hx().chat(UNCOVERED); // onUncovered
            c.ctx().runOnClient(mc -> {
                Mod.staticCall(TC, "onBlockChanged", p); // onBlockChanged
                Mod.staticCall(TC, "onChunkLoaded", x >> 4, z >> 4); // onChunkLoaded
                Mod.staticCall(TC, "useForTest", x, y, z); // onUseBlock
                Mod.staticCall(TC, "digForTest", x, y, z); // onDigSent
                Mod.staticCall(TC, "onChestOpened", p); // onChestOpened
                if ((Boolean) Mod.staticCall(TC, "shouldHide", p)) throw new AssertionError("hide mixin question true"); // hide mixin
            });
            c.hx().chat("§6You uncovered a treasure chest!");
            c.ctx().waitTicks(40); // tick, render, the triggerbot and the HUD run on their own
            nothing(c, "gate off", x, y, z, b0);
            c.check(intCall(c, "claims") == cl0 && intCall(c, "alerts") == al0 && intCall(c, "hiddenCount") == 0,
                    "gate off: a claim, alert or hide happened (claims " + cl0 + " -> " + intCall(c, "claims") + ", hidden "
                            + intCall(c, "hiddenCount") + ")");
            c.check(c.onClient(mc -> (Long) Mod.staticCall(TC, "secondsLeft")) < 0, "gate off: the chest timer HUD has a chest");
            // and the same calls DO act once the Hollows is read (the feature acted)
            setblock(c, x, y, z, "minecraft:air");
            c.ctx().waitTicks(5);
            hollows(c);
            claim(c, x, y, z);
            c.check(intCall(c, "count") >= 1, "Hollows: the same chest was not tracked");
        } finally {
            setblock(c, x, y, z, "minecraft:air");
            hub(c);
        }
    }
}

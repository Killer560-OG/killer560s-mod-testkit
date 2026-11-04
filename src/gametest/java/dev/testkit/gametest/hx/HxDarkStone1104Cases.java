package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import net.minecraft.core.BlockPos;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * 1104-mining-dark-stone (mod darkstone-1104): a wall of stone fills the view; with Dark Stone ON its mean brightness
 * falls to about (1 - level) of the OFF reading, and a wall of iron ore (not in the set) reads the same ON and OFF.
 * Asserts the mesher acted (DarkStone.darkenedCount rose) and that the view was re-meshed live by the switch.
 */
final class HxDarkStone1104Cases {

    private static final String CFG = "miningqol.MiningQolConfig";
    private static final String STATE = "miningqol.MiningState";
    private static final String DS = "miningqol.DarkStone";

    private HxDarkStone1104Cases() {
    }

    static void register(Session s) {
        s.test("1104-mining-dark-stone", HxDarkStone1104Cases::darkStone);
    }

    private static void mines(Session c) {
        c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Royal Mines", "Purse: 0");
        List<String> tab = new ArrayList<>(List.of("Area: Dwarven Mines", "Profile: Test", ""));
        c.hx().tab(tab, null, null);
        c.waitUntil("Dwarven Mines", mc -> "DWARVEN_MINES".equals(Mod.staticCall(STATE, "island")), 100);
    }

    private static double mean(Session c, String name) throws Exception {
        c.ctx().waitTicks(30);
        Path p = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        dev.testkit.harness.Report.screenshot(name, p);
        BufferedImage img = javax.imageio.ImageIO.read(p.toFile());
        int w = img.getWidth();
        int h = img.getHeight();
        long sum = 0;
        long n = 0;
        for (int y = h * 3 / 10; y < h * 6 / 10; y += 2) {
            for (int x = w * 3 / 10; x < w * 7 / 10; x += 2) {
                int rgb = img.getRGB(x, y);
                sum += ((rgb >> 16) & 255) + ((rgb >> 8) & 255) + (rgb & 255);
                n += 3;
            }
        }
        return sum / (double) n;
    }

    private static void wall(Session c, BlockPos f, String block) {
        BlockPos a = f.offset(-5, -2, -3);
        BlockPos b = f.offset(5, 6, -3);
        c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", a.getX(), a.getY(), a.getZ(), b.getX(),
                b.getY(), b.getZ(), block));
        c.server().sync(10);
    }

    private static void darkStone(Session c) {
        BlockPos f = c.onClient(mc -> mc.player.blockPosition());
        try (Settings set = new Settings(c)) {
            mines(c);
            c.server().command(String.format(Locale.ROOT, "tp @p %d %d %d 180 0", f.getX(), f.getY(), f.getZ()));
            c.server().sync(10);
            wall(c, f, "minecraft:stone");
            double stoneOff = mean(c, "1104-darkstone-stone-off");
            set.with(CFG, "DarkStone", true).with(CFG, "DarkStoneLevel", 60);
            long before = c.onClient(mc -> (Long) Mod.staticCall(DS, "darkenedCount"));
            c.waitUntil("Dark Stone active", mc -> (Boolean) Mod.staticCall(DS, "isOn"), 60);
            double stoneOn = mean(c, "1104-darkstone-stone-on");
            long after = c.onClient(mc -> (Long) Mod.staticCall(DS, "darkenedCount"));
            c.note(String.format(Locale.ROOT, "stone mean off %.1f on %.1f, %d mesher draws darkened", stoneOff, stoneOn,
                    after - before));
            c.note("tinted vertices " + c.onClient(mc -> (Long) Mod.staticCall(DS, "tintedCount")));
            c.check(c.onClient(mc -> (Long) Mod.staticCall(DS, "tintedCount")) > 0, "no vertex was tinted");
            c.check(after > before, "the mesher darkened nothing");
            c.check(stoneOn < stoneOff * 0.55, String.format(Locale.ROOT, "stone not darker: off %.1f on %.1f", stoneOff, stoneOn));
            wall(c, f, "minecraft:iron_ore");
            double oreOn = mean(c, "1104-darkstone-ore-on");
            c.waitUntil("off", mc -> true, 1);
            set.with(CFG, "DarkStone", false);
            c.waitUntil("Dark Stone off", mc -> !(Boolean) Mod.staticCall(DS, "isOn"), 60);
            double oreOff = mean(c, "1104-darkstone-ore-off");
            c.note(String.format(Locale.ROOT, "iron ore mean on %.1f off %.1f", oreOn, oreOff));
            c.check(Math.abs(oreOn - oreOff) < oreOff * 0.03, "ore changed: on " + oreOn + " off " + oreOff);
            double stoneBack = (wallThenMean(c, f));
            c.check(Math.abs(stoneBack - stoneOff) < stoneOff * 0.10 && stoneBack > stoneOn * 2, "stone did not return: " + stoneBack + " vs " + stoneOff);
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            BlockPos a = f.offset(-5, -2, -3);
            BlockPos b = f.offset(5, 6, -3);
            c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", a.getX(), a.getY(),
                    a.getZ(), b.getX(), b.getY(), b.getZ()));
            hub(c);
        }
    }

    private static double wallThenMean(Session c, BlockPos f) throws Exception {
        wall(c, f, "minecraft:stone");
        return mean(c, "1104-darkstone-stone-off2");
    }
}

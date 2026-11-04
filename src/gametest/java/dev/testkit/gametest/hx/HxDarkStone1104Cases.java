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
        s.test("1104-mining-dark-perf", HxDarkStone1104Cases::darkPerf);
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

    private static BufferedImage shot(Session c, String name) throws Exception {
        c.ctx().waitTicks(30);
        Path p = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        dev.testkit.harness.Report.screenshot(name, p);
        return javax.imageio.ImageIO.read(p.toFile());
    }

    /** Mean of the three channels over the centre of the view. */
    private static double centreMean(BufferedImage img) {
        long sum = 0;
        long n = 0;
        for (int y = img.getHeight() * 3 / 10; y < img.getHeight() * 6 / 10; y += 2) {
            for (int x = img.getWidth() * 3 / 10; x < img.getWidth() * 7 / 10; x += 2) {
                int rgb = img.getRGB(x, y);
                sum += ((rgb >> 16) & 255) + ((rgb >> 8) & 255) + (rgb & 255);
                n += 3;
            }
        }
        return sum / (double) n;
    }

    private static double sat(int rgb) {
        int r = (rgb >> 16) & 255;
        int g = (rgb >> 8) & 255;
        int b = rgb & 255;
        int mx = Math.max(r, Math.max(g, b));
        return mx == 0 ? 0 : (mx - Math.min(r, Math.min(g, b))) / (double) mx;
    }

    /**
     * Over the pixels the OFF picture shows as a red speck (saturated, red) and as stone (unsaturated, mid brightness):
     * [0] mean ON/OFF brightness ratio of the specks, [1] of the stone part, [2] counts specks, [3] counts stone.
     */
    private static double[] oreRatios(BufferedImage off, BufferedImage on) {
        double sp = 0;
        double spOn = 0;
        double bg = 0;
        double bgOn = 0;
        int ns = 0;
        int nb = 0;
        for (int y = off.getHeight() * 3 / 10; y < off.getHeight() * 6 / 10; y++) {
            for (int x = off.getWidth() * 3 / 10; x < off.getWidth() * 7 / 10; x++) {
                int a = off.getRGB(x, y);
                int b = on.getRGB(x, y);
                int ra = (a >> 16) & 255;
                int ga = (a >> 8) & 255;
                int ba = a & 255;
                double lo = ra + ga + ba;
                double ln = ((b >> 16) & 255) + ((b >> 8) & 255) + (b & 255);
                if (sat(a) > 0.45 && ra > ga * 2) {
                    sp += lo;
                    spOn += ln;
                    ns++;
                } else if (sat(a) < 0.12 && lo > 150) {
                    bg += lo;
                    bgOn += ln;
                    nb++;
                }
            }
        }
        return new double[] {ns == 0 ? -1 : spOn / sp, nb == 0 ? -1 : bgOn / bg, ns, nb};
    }

    private static void darkStone(Session c) {
        BlockPos f = c.onClient(mc -> mc.player.blockPosition());
        try (Settings set = new Settings(c)) {
            mines(c);
            c.server().command(String.format(Locale.ROOT, "tp @p %d %d %d 180 0", f.getX(), f.getY(), f.getZ()));
            c.server().sync(10);
            wall(c, f, "minecraft:stone");
            BufferedImage stoneOff = shot(c, "1104-darkstone-stone-off");
            wall(c, f, "minecraft:redstone_ore");
            BufferedImage oreOff = shot(c, "1104-darkstone-redstone-off");
            wall(c, f, "minecraft:polished_diorite");
            BufferedImage titOff = shot(c, "1104-darkstone-titanium-off");
            wall(c, f, "minecraft:stone");

            set.with(CFG, "DarkStone", true).with(CFG, "DarkStoneLevel", 60);
            c.waitUntil("Dark Stone active", mc -> (Boolean) Mod.staticCall(DS, "isOn"), 60);
            long sprites = c.onClient(mc -> (Long) Mod.staticCall(DS, "darkenedCount"));
            c.note("sprites darkened " + sprites + ", redraw " + c.onClient(mc -> {
                try {
                    return (Long) Mod.staticCall(DS, "lastApplyMicros");
                } catch (Throwable e) {
                    return -1L;
                }
            }) + " us");
            c.check(sprites >= 16, "too few sprites darkened: " + sprites);
            BufferedImage stoneOn = shot(c, "1104-darkstone-stone-on");
            double stoneRatio = centreMean(stoneOn) / centreMean(stoneOff);
            c.note(String.format(Locale.ROOT, "stone ON/OFF %.3f", stoneRatio));
            c.check(stoneRatio < 0.5, "stone not darker: ratio " + stoneRatio);

            wall(c, f, "minecraft:redstone_ore");
            BufferedImage oreOn = shot(c, "1104-darkstone-redstone-on");
            double[] r = oreRatios(oreOff, oreOn);
            c.note(String.format(Locale.ROOT, "redstone ore: specks ON/OFF %.3f (%d px), stone part ON/OFF %.3f (%d px)", r[0],
                    (int) r[2], r[1], (int) r[3]));
            c.check(r[2] > 100 && r[3] > 100, "ore picture has no speck or stone pixels to compare");
            c.check(Math.abs(r[0] - 1.0) < 0.07, "specks changed: ON/OFF " + r[0]);
            c.check(Math.abs(r[1] - stoneRatio) < 0.07, "ore background " + r[1] + " does not match stone " + stoneRatio);

            wall(c, f, "minecraft:polished_diorite");
            BufferedImage titOn = shot(c, "1104-darkstone-titanium-on");
            double tr = centreMean(titOn) / centreMean(titOff);
            c.note(String.format(Locale.ROOT, "titanium (polished diorite) ON/OFF %.3f", tr));
            c.check(Math.abs(tr - 1.0) < 0.03, "titanium changed: " + tr);

            set.with(CFG, "DarkStone", false);
            c.waitUntil("Dark Stone off", mc -> !(Boolean) Mod.staticCall(DS, "isOn"), 60);
            long left = c.onClient(mc -> (Long) Mod.staticCall(DS, "darkenedCount"));
            c.check(left == 0, "sprites still darkened after OFF: " + left);
            wall(c, f, "minecraft:stone");
            double back = centreMean(shot(c, "1104-darkstone-stone-back")) / centreMean(stoneOff);
            c.note(String.format(Locale.ROOT, "stone back OFF/OFF %.3f", back));
            c.check(Math.abs(back - 1.0) < 0.06, "stone did not return: " + back);
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            BlockPos a = f.offset(-5, -2, -3);
            BlockPos b = f.offset(5, 6, -3);
            c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", a.getX(), a.getY(), a.getZ(),
                    b.getX(), b.getY(), b.getZ()));
            hub(c);
        }
    }

    /** The redraw hitch of switching Dark Stone ON and OFF; the mesher is not involved at all. */
    private static void darkPerf(Session c) {
        try (Settings set = new Settings(c)) {
            mines(c);
            long[] on = new long[5];
            long[] off = new long[5];
            for (int i = 0; i < 5; i++) {
                set.with(CFG, "DarkStone", true);
                c.waitUntil("on", mc -> (Boolean) Mod.staticCall(DS, "isOn"), 60);
                on[i] = c.onClient(mc -> (Long) Mod.staticCall(DS, "lastApplyMicros"));
                set.with(CFG, "DarkStone", false);
                c.waitUntil("off", mc -> !(Boolean) Mod.staticCall(DS, "isOn"), 60);
                off[i] = c.onClient(mc -> (Long) Mod.staticCall(DS, "lastApplyMicros"));
            }
            c.note("PERF atlas redraw ON us " + java.util.Arrays.toString(on) + " OFF us " + java.util.Arrays.toString(off));
        } finally {
            hub(c);
        }
    }

    private static double wallThenMean(Session c, BlockPos f) throws Exception {
        wall(c, f, "minecraft:stone");
        return mean(c, "1104-darkstone-stone-off2");
    }
}

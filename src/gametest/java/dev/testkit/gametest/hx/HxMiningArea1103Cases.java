package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod branch mithrilarea-1103 (killer560, 2026-11-03):
 * <ul>
 *   <li>1103-mining-block-area: "make it so its not based off of my location but the blocks location". With a test graph
 *       (SkyHanni format) whose area nodes put one mithril block in the Upper Mines and one in Cliffside Veins, and "Upper
 *       Mines Mithril" open, the mesher draws only the Cliffside block as stone - standing in the Royal Mines, in the
 *       Upper Mines and in Cliffside Veins alike. A block with no graph node near follows his own area.</li>
 *   <li>1103-mining-block-area-titanium: Titanium Highlight in Commission Area mode does the same per block.</li>
 *   <li>1103-mining-highlight-latency: "make it update stuff like highlighted blocks faster": a titanium block turning to
 *       bedrock and back, and a carpet losing and getting back its lantern, change the highlight on the tick the client
 *       sees the block change (event path, not the once-a-second scan).</li>
 *   <li>1103-mining-carpet-stable: "moving around still messes up the carpet highlight": while he jumps and sneaks, the
 *       carpet's top face is filled in every sampled frame, and the ground just past the carpet never is - with Improved
 *       Transparency off and on.</li>
 * </ul>
 */
final class HxMiningArea1103Cases {

    private static final String CFG = "miningqol.MiningQolConfig";
    private static final String STATE = "miningqol.MiningState";
    private static final String STONE = "miningqol.MithrilStone";
    private static final String MAP = "miningqol.MinesAreaMap";
    private static final String HL = "miningqol.MiningHighlights";

    private HxMiningArea1103Cases() {
    }

    static void register(Session s) {
        s.test("1103-mining-block-area", HxMiningArea1103Cases::blockArea);
        s.test("1103-mining-block-area-titanium", HxMiningArea1103Cases::blockAreaTitanium);
        s.test("1103-mining-highlight-latency", HxMiningArea1103Cases::latency);
        s.test("1103-mining-carpet-stable", HxMiningArea1103Cases::carpetStable);
    }

    // ---- shared ---------------------------------------------------------------------------------------------------

    private static void mines(Session c, String area, String... coms) {
        c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ " + area, "Purse: 0");
        List<String> tab = new ArrayList<>();
        tab.add("Area: Dwarven Mines");
        tab.add("Profile: Test");
        tab.add("");
        if (coms.length > 0) {
            tab.add("Commissions:");
            for (String com : coms) {
                tab.add(" " + com);
            }
            tab.add("");
        }
        c.hx().tab(tab, null, null);
        c.waitUntil("Dwarven Mines, area " + area, mc -> "DWARVEN_MINES".equals(Mod.staticCall(STATE, "island"))
                && area.equals(Mod.staticCall(STATE, "area")), 100);
        int n = coms.length;
        c.waitUntil(n + " commission(s) read", mc -> (Boolean) Mod.staticCall(STATE, "commissionsKnown") == (n > 0)
                && ((List<?>) Mod.staticCall(STATE, "commissions")).size() == n, 100);
    }

    private static BlockPos feet(Session c) {
        return c.onClient(mc -> mc.player.blockPosition());
    }

    private static void setb(Session c, BlockPos p, String block) {
        c.server().command("setblock " + p.getX() + " " + p.getY() + " " + p.getZ() + " " + block);
    }

    private static String node(int id, BlockPos p, String area, String neighbours) {
        String tags = area == null ? "" : ",\"Name\":\"" + area + "\",\"Tags\":[\"area\"]";
        return "\"" + id + "\":{\"Position\":\"" + p.getX() + ".0:" + p.getY() + ".0:" + p.getZ() + ".0\"" + tags
                + ",\"Neighbours\":{" + neighbours + "}}";
    }

    /**
     * A graph round {@code f}: an Upper Mines area node 10 west, a Cliffside Veins one 10 east, plain nodes 5 west and 5
     * east, the four in a line. The west half takes Upper Mines, the east half Cliffside Veins; nothing 24+ blocks away.
     */
    private static void useGraph(Session c, BlockPos f) {
        String json = "{" + node(1, f.offset(-10, 0, 0), "Upper Mines", "\"2\":5.0")
                + "," + node(2, f.offset(-5, 0, 0), null, "\"1\":5.0,\"3\":10.0")
                + "," + node(3, f.offset(5, 0, 0), null, "\"2\":10.0,\"4\":5.0")
                + "," + node(4, f.offset(10, 0, 0), "Cliffside Veins", "\"3\":5.0") + "}";
        c.ctx().runOnClient(mc -> Mod.staticCall(MAP, "useGraphJsonForTest", json));
        c.waitUntil("the test area map in use", mc -> (Boolean) Mod.staticCall(MAP, "loaded"), 20);
    }

    private static void clearGraph(Session c) {
        c.ctx().runOnClient(mc -> Mod.staticCall(MAP, "clearTestGraphForTest"));
    }

    private static String areaOf(Session c, BlockPos p) {
        return c.onClient(mc -> (String) Mod.staticCall(STONE, "areaOf", p.getX(), p.getY(), p.getZ()));
    }

    // ---- 1103-mining-block-area -------------------------------------------------------------------------------------

    private static void blockArea(Session c) {
        BlockPos f = feet(c);
        BlockPos upper = f.offset(-6, 1, -3);
        BlockPos cliff = f.offset(6, 1, -3);
        BlockPos far = f.offset(0, 1, 30);
        List<BlockPos> all = List.of(upper, cliff, far);
        try (Settings set = new Settings(c).with(CFG, "MithrilStone", true)) {
            useGraph(c, f);
            c.check("Upper Mines".equals(areaOf(c, upper)), "the west block's area is " + areaOf(c, upper));
            c.check("Cliffside Veins".equals(areaOf(c, cliff)), "the east block's area is " + areaOf(c, cliff));
            c.check(areaOf(c, far) == null, "a block 30 blocks from any node got an area: " + areaOf(c, far));
            setb(c, upper, "minecraft:gray_wool");
            setb(c, cliff, "minecraft:prismarine");
            setb(c, far, "minecraft:light_blue_wool");
            for (String standIn : new String[]{"Royal Mines", "Upper Mines", "Cliffside Veins"}) {
                mines(c, standIn, "Upper Mines Mithril: 10%");
                c.waitUntil("the swap is on (" + standIn + ")", mc -> (Boolean) Mod.staticCall(STONE, "isEnabled"), 40);
                c.ctx().runOnClient(mc -> {
                    Mod.staticCall(STONE, "recordSwapsForTest", true);
                    Mod.staticCall(STONE, "remeshForTest");
                });
                c.waitUntil("the mesher drew the Cliffside block as stone (" + standIn + ")",
                        mc -> (Boolean) Mod.staticCall(STONE, "swappedAt", cliff.getX(), cliff.getY(), cliff.getZ()), 100);
                c.ctx().waitTicks(10);
                boolean upperSwapped = c.onClient(mc -> (Boolean) Mod.staticCall(STONE, "swappedAt", upper.getX(),
                        upper.getY(), upper.getZ()));
                boolean farSwapped = c.onClient(mc -> (Boolean) Mod.staticCall(STONE, "swappedAt", far.getX(), far.getY(),
                        far.getZ()));
                c.check(!upperSwapped, "standing in " + standIn + ", the Upper Mines block was drawn as stone");
                // The far block has no area: it follows where he stands.
                boolean farWant = !standIn.equals("Upper Mines");
                c.check(farSwapped == farWant, "standing in " + standIn + ", the far (unknown area) block stone=" + farSwapped);
                c.note("standing in " + standIn + ": Upper Mines block mithril, Cliffside block stone, far block stone="
                        + farSwapped);
            }
            // Mithril Miner (no area): nothing is stone anywhere.
            mines(c, "Royal Mines", "Mithril Miner: 10%");
            c.waitUntil("off with Mithril Miner", mc -> !(Boolean) Mod.staticCall(STONE, "isEnabled"), 40);
        } finally {
            c.ctx().runOnClient(mc -> Mod.staticCall(STONE, "recordSwapsForTest", false));
            for (BlockPos b : all) {
                setb(c, b, "minecraft:air");
            }
            clearGraph(c);
            hub(c);
        }
    }

    // ---- 1103-mining-block-area-titanium ----------------------------------------------------------------------------

    private static void blockAreaTitanium(Session c) {
        BlockPos f = feet(c);
        BlockPos upper = f.offset(-6, 1, 3);
        BlockPos cliff = f.offset(6, 1, 3);
        Object area = c.onClient(mc -> Mod.enumValue("miningqol.MiningQolConfig$TitaniumMode", "COMMISSION"));
        try (Settings set = new Settings(c).with(CFG, "Titanium", true).with(CFG, "TitaniumMode", area)) {
            useGraph(c, f);
            setb(c, upper, "minecraft:polished_diorite");
            setb(c, cliff, "minecraft:polished_diorite");
            for (String standIn : new String[]{"Cliffside Veins", "Upper Mines", "Royal Mines"}) {
                mines(c, standIn, "Upper Mines Titanium: 10%");
                c.waitUntil("the Upper Mines titanium highlighted (" + standIn + ")", mc -> (Boolean) Mod.staticCall(HL,
                        "hasTitaniumAt", upper.getX(), upper.getY(), upper.getZ()), 60);
                c.ctx().waitTicks(25);
                boolean cl = c.onClient(mc -> (Boolean) Mod.staticCall(HL, "hasTitaniumAt", cliff.getX(), cliff.getY(),
                        cliff.getZ()));
                c.check(!cl, "standing in " + standIn + ", the Cliffside Veins titanium was highlighted");
            }
            c.note("Upper Mines Titanium: only the Upper Mines block, from Cliffside Veins, Upper Mines and Royal Mines");
        } finally {
            setb(c, upper, "minecraft:air");
            setb(c, cliff, "minecraft:air");
            clearGraph(c);
            hub(c);
        }
    }

    // ---- 1103-mining-highlight-latency ------------------------------------------------------------------------------

    /** Polls each client tick until the client's block at p is {@code block}; then the ticks until {@code has} says want. */
    private static int[] follow(Session c, BlockPos p, String block, String hook, boolean want) {
        setb(c, p, block);
        int seen = -1;
        int lag = -1;
        for (int t = 0; t < 60 && lag < 0; t++) {
            boolean[] r = c.onClient(mc -> new boolean[]{
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p).getBlock())
                            .toString().equals(block),
                    (Boolean) Mod.staticCall(HL, hook, p.getX(), p.getY(), p.getZ())});
            if (r[0] && seen < 0) {
                seen = t;
            }
            if (seen >= 0 && r[1] == want) {
                lag = t - seen;
            }
            if (lag < 0) {
                c.ctx().waitTicks(1);
            }
        }
        return new int[]{seen, lag};
    }

    private static void latency(Session c) {
        BlockPos f = feet(c);
        BlockPos ti = f.offset(-3, 1, 3);
        BlockPos lantern = f.offset(3, 0, 3);
        BlockPos carpet = lantern.above();
        Object always = c.onClient(mc -> Mod.enumValue("miningqol.MiningQolConfig$TitaniumMode", "ALWAYS"));
        try (Settings set = new Settings(c).with(CFG, "Titanium", true).with(CFG, "TitaniumMode", always)
                .with(CFG, "Carpet", true)) {
            mines(c, "Royal Mines", "Goblin Slayer: 10%");
            setb(c, lantern, "minecraft:sea_lantern");
            setb(c, carpet, "minecraft:gray_carpet");
            c.ctx().waitTicks(25);
            int events = c.onClient(mc -> ((Number) Mod.staticCall(HL, "blockEvents")).intValue());
            List<String> rows = new ArrayList<>();
            int[][] r = {
                    follow(c, ti, "minecraft:polished_diorite", "hasTitaniumAt", true),
                    follow(c, ti, "minecraft:bedrock", "hasTitaniumAt", false),
                    follow(c, ti, "minecraft:polished_diorite", "hasTitaniumAt", true),
            };
            String[] what = {"titanium placed", "titanium to bedrock", "bedrock to titanium"};
            for (int i = 0; i < r.length; i++) {
                rows.add(what[i] + " lag " + r[i][1]);
                c.check(r[i][0] >= 0, what[i] + ": the client never saw the block change");
                c.check(r[i][1] >= 0 && r[i][1] <= 1, what[i] + ": the highlight took " + r[i][1] + " tick(s)");
            }
            // The carpet loses its lantern, then gets it back.
            int[] carpetOff = followCarpet(c, lantern, carpet, "minecraft:stone", false);
            int[] carpetOn = followCarpet(c, lantern, carpet, "minecraft:sea_lantern", true);
            c.check(carpetOff[1] >= 0 && carpetOff[1] <= 1, "carpet lost its lantern: the fill took " + carpetOff[1] + " tick(s)");
            c.check(carpetOn[1] >= 0 && carpetOn[1] <= 1, "carpet got its lantern back: the fill took " + carpetOn[1] + " tick(s)");
            rows.add("carpet off/on lag " + carpetOff[1] + "/" + carpetOn[1]);
            int after = c.onClient(mc -> ((Number) Mod.staticCall(HL, "blockEvents")).intValue());
            c.check(after > events, "no block event reached the highlights");
            c.note(String.join("; ", rows) + "; block events " + (after - events));
        } finally {
            for (BlockPos b : List.of(ti, lantern, carpet)) {
                setb(c, b, "minecraft:air");
            }
            hub(c);
        }
    }

    private static int[] followCarpet(Session c, BlockPos lantern, BlockPos carpet, String block, boolean want) {
        setb(c, lantern, block);
        int seen = -1;
        int lag = -1;
        for (int t = 0; t < 60 && lag < 0; t++) {
            boolean[] r = c.onClient(mc -> new boolean[]{
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(lantern).getBlock())
                            .toString().equals(block),
                    (Boolean) Mod.staticCall(HL, "hasCarpetAt", carpet.getX(), carpet.getY(), carpet.getZ())});
            if (r[0] && seen < 0) {
                seen = t;
            }
            if (seen >= 0 && r[1] == want) {
                lag = t - seen;
            }
            if (lag < 0) {
                c.ctx().waitTicks(1);
            }
        }
        return new int[]{seen, lag};
    }

    // ---- 1103-mining-carpet-stable ----------------------------------------------------------------------------------

    /** Screen pixel of world point p in the current camera, or null when behind. */
    private static int[] project(net.minecraft.client.Minecraft mc, Vec3 p) {
        var cam = mc.gameRenderer.mainCamera();
        Vec3 d = p.subtract(cam.position());
        var fw = cam.forwardVector();
        var up = cam.upVector();
        var left = cam.leftVector();
        double depth = d.x * fw.x() + d.y * fw.y() + d.z * fw.z();
        if (depth <= 0.05) {
            return null;
        }
        double u = -(d.x * left.x() + d.y * left.y() + d.z * left.z());
        double v = d.x * up.x() + d.y * up.y() + d.z * up.z();
        double fpx = (mc.getWindow().getHeight() / 2.0) / Math.tan(Math.toRadians(cam.getFov()) / 2.0);
        return new int[]{(int) Math.round(mc.getWindow().getWidth() / 2.0 + u / depth * fpx),
                (int) Math.round(mc.getWindow().getHeight() / 2.0 - v / depth * fpx)};
    }

    private static int greenness(BufferedImage img, int x, int y) {
        int a = img.getRGB(x, y);
        return ((a >> 8) & 255) - Math.max((a >> 16) & 255, a & 255);
    }

    /** Pixels in a 5x5 patch round (x, y) at least {@code min} green over red/blue. */
    private static int greenPatch(BufferedImage img, int[] at, int min) {
        int n = 0;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int x = at[0] + dx, y = at[1] + dy;
                if (x >= 0 && y >= 0 && x < img.getWidth() && y < img.getHeight() && greenness(img, x, y) >= min) {
                    n++;
                }
            }
        }
        return n;
    }

    private static void setImprovedTransparency(net.minecraft.client.Minecraft mc, boolean on) {
        try {
            Object opt = mc.options.getClass().getMethod("improvedTransparency").invoke(mc.options);
            opt.getClass().getMethod("set", Object.class).invoke(opt, on);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("no improvedTransparency option on this version", e);
        }
    }

    private static Boolean improvedTransparency(net.minecraft.client.Minecraft mc) {
        try {
            Object opt = mc.options.getClass().getMethod("improvedTransparency").invoke(mc.options);
            return (Boolean) opt.getClass().getMethod("get").invoke(opt);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static void carpetStable(Session c) throws Exception {
        BlockPos f = feet(c);
        BlockPos lantern = f.offset(0, -1, -2);
        BlockPos carpet = lantern.above();
        List<BlockPos> platform = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -4; dz <= 0; dz++) {
                platform.add(f.offset(dx, -1, dz));
            }
        }
        boolean hideGui = c.onClient(mc -> dev.testkit.compat.McCompat.hudHidden(mc));
        List<String> was = c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            for (BlockPos b : platform) {
                out.add(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(b).getBlock())
                        .toString());
            }
            return out;
        });
        Boolean itBefore = c.onClient(HxMiningArea1103Cases::improvedTransparency);
        try (Settings set = new Settings(c).with(CFG, "Carpet", true).with(CFG, "CarpetColor", 0xFF00FF00)) {
            for (BlockPos b : platform) {
                setb(c, b, "minecraft:stone");
            }
            setb(c, lantern, "minecraft:sea_lantern");
            setb(c, carpet, "minecraft:gray_carpet");
            mines(c, "Royal Mines", "Goblin Slayer: 10%");
            c.waitUntil("the carpet found", mc -> (Boolean) Mod.staticCall(HL, "hasCarpetAt", carpet.getX(),
                    carpet.getY(), carpet.getZ()), 60);
            c.ctx().runOnClient(mc -> {
                dev.testkit.compat.McCompat.setHudHidden(mc, true);
                dev.testkit.compat.McCompat.clearChatAndToasts(mc);
            });
            Vec3 top = new Vec3(carpet.getX() + 0.5, carpet.getY() + 0.0625, carpet.getZ() + 0.5);
            // Ground just past the carpet's far edge (stone, never filled).
            Vec3 past = new Vec3(carpet.getX() + 0.5, carpet.getY(), carpet.getZ() - 0.45);
            List<Boolean> modes = itBefore == null ? List.of(false) : List.of(false, true);
            for (boolean it : modes) {
                if (itBefore != null) {
                    c.ctx().runOnClient(mc -> setImprovedTransparency(mc, it));
                }
                c.ctx().waitTicks(10);
                int frames = 0;
                int bad = 0;
                int leak = 0;
                int minIn = 25;
                List<String> fails = new ArrayList<>();
                for (int i = 0; i < 24; i++) {
                    final int k = i;
                    // Jump the whole time; sneak in alternate stretches of 6 samples; look at the carpet.
                    c.ctx().runOnClient(mc -> {
                        mc.options.keyJump.setDown(true);
                        mc.options.keyShift.setDown((k / 6) % 2 == 1);
                        Vec3 eye = mc.player.getEyePosition();
                        Vec3 d = top.subtract(eye);
                        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
                        mc.player.setXRot((float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z))));
                    });
                    c.ctx().waitTicks(1 + (i % 3));
                    int[][] at = c.onClient(mc -> new int[][]{project(mc, top), project(mc, past)});
                    java.nio.file.Path p = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(
                            c.name() + "-it" + it + "-" + i));
                    BufferedImage img = javax.imageio.ImageIO.read(p.toFile());
                    if (at[0] == null || at[1] == null) {
                        continue;
                    }
                    // takeScreenshot may read a different-size framebuffer than the window.
                    double sx = img.getWidth() / (double) c.onClient(mc -> mc.getWindow().getWidth());
                    double sy = img.getHeight() / (double) c.onClient(mc -> mc.getWindow().getHeight());
                    int[] in = {(int) (at[0][0] * sx), (int) (at[0][1] * sy)};
                    int[] out = {(int) (at[1][0] * sx), (int) (at[1][1] * sy)};
                    int g = greenPatch(img, in, 60);
                    int o = greenPatch(img, out, 60);
                    frames++;
                    minIn = Math.min(minIn, g);
                    if (g < 20) {
                        bad++;
                        fails.add("sample " + i + " in=" + g + "@" + in[0] + "," + in[1]);
                    }
                    if (o > 3) {
                        leak++;
                        fails.add("sample " + i + " past=" + o + "@" + out[0] + "," + out[1]);
                    }
                }
                c.ctx().runOnClient(mc -> {
                    mc.options.keyJump.setDown(false);
                    mc.options.keyShift.setDown(false);
                });
                c.note(String.format(Locale.ROOT, "Improved Transparency %s: %d frames, carpet top filled in all but %d"
                        + " (lowest 5x5 patch %d/25), fill past the edge in %d %s", it, frames, bad, minIn, leak,
                        fails.isEmpty() ? "" : fails.subList(0, Math.min(6, fails.size())).toString()));
                c.check(frames >= 20, "only " + frames + " frames sampled");
                c.check(bad == 0, "Improved Transparency " + it + ": the carpet's top was not filled in " + bad + " frame(s): " + fails);
                c.check(leak == 0, "Improved Transparency " + it + ": the fill showed past the carpet in " + leak + " frame(s): " + fails);
            }
        } finally {
            c.ctx().runOnClient(mc -> {
                mc.options.keyJump.setDown(false);
                mc.options.keyShift.setDown(false);
                dev.testkit.compat.McCompat.setHudHidden(mc, hideGui);
                if (itBefore != null) {
                    setImprovedTransparency(mc, itBefore);
                }
            });
            setb(c, carpet, "minecraft:air");
            for (int i = 0; i < platform.size(); i++) {
                setb(c, platform.get(i), was.get(i));
            }
            hub(c);
        }
    }
}

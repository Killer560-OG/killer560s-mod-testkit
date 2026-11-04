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
        s.test("1103-mining2-carpet-texture", HxMiningArea1103Cases::carpetTexture);
        s.test("1103-mining2-area-inside", HxMiningArea1103Cases::areaInside);
        s.test("1103-mining-titanium-sight", HxMiningArea1103Cases::titaniumSight);
    }

    /** Owner 2026-11-03: without Through Walls, titanium in sight gets its whole box and hidden titanium none; with
     *  Through Walls, Exposed Only keeps only titanium with a face open to air. */
    private static void titaniumSight(Session c) {
        BlockPos f = feet(c);
        BlockPos open = f.offset(-3, 1, 3);
        BlockPos hid = f.offset(4, 1, -4);
        List<BlockPos> shell = new ArrayList<>();
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            shell.add(hid.relative(d));
        }
        Object always = c.onClient(mc -> Mod.enumValue("miningqol.MiningQolConfig$TitaniumMode", "ALWAYS"));
        try (Settings set = new Settings(c).with(CFG, "Titanium", true).with(CFG, "TitaniumMode", always)
                .with(CFG, "TitaniumEsp", false).with(CFG, "TitaniumExposedOnly", true)) {
            mines(c, "Royal Mines");
            setb(c, open, "minecraft:polished_diorite");
            for (BlockPos b : shell) {
                setb(c, b, "minecraft:stone");
            }
            setb(c, hid, "minecraft:polished_diorite");
            c.waitUntil("both titanium blocks tracked", mc -> (Boolean) Mod.staticCall(HL, "hasTitaniumAt", open.getX(),
                    open.getY(), open.getZ()) && (Boolean) Mod.staticCall(HL, "hasTitaniumAt", hid.getX(), hid.getY(), hid.getZ()), 60);
            c.ctx().waitTicks(5);
            c.check(drew(c, open), "titanium in plain sight was not drawn");
            c.check(!drew(c, hid), "titanium hidden inside stone was drawn without Through Walls");
            String note = "no walls: open drawn, hidden not";
            if (Mod.isCheat()) {
                Mod.set(CFG, "setTitaniumEsp", true);
                Mod.set(CFG, "setTitaniumExposedOnly", true);
                c.ctx().waitTicks(5);
                c.check(drew(c, open) && !drew(c, hid), "Through Walls + Exposed Only: open " + drew(c, open) + ", hidden "
                        + drew(c, hid) + " (want true, false)");
                Mod.set(CFG, "setTitaniumExposedOnly", false);
                c.ctx().waitTicks(5);
                c.check(drew(c, open) && drew(c, hid), "Through Walls, Exposed Only off: hidden titanium not drawn");
                Mod.set(CFG, "setTitaniumEsp", false);
                Mod.set(CFG, "setTitaniumExposedOnly", true);
                note += "; walls+exposed: hidden skipped; walls alone: hidden drawn";
            }
            c.note(note);
        } finally {
            setb(c, hid, "minecraft:air");
            for (BlockPos b : shell) {
                setb(c, b, "minecraft:air");
            }
            setb(c, open, "minecraft:air");
            hub(c);
        }
    }

    private static boolean drew(Session c, BlockPos p) {
        return c.onClient(mc -> (Boolean) Mod.staticCall(HL, "drewTitaniumAt", p.getX(), p.getY(), p.getZ()));
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
        BlockPos far = f.offset(20, 1, 30);
        List<BlockPos> all = List.of(upper, cliff, far);
        try (Settings set = new Settings(c).with(CFG, "MithrilStone", true)) {
            useGraph(c, f);
            c.check("Upper Mines".equals(areaOf(c, upper)), "the west block's area is " + areaOf(c, upper));
            c.check("Cliffside Veins".equals(areaOf(c, cliff)), "the east block's area is " + areaOf(c, cliff));
            c.check("Cliffside Veins".equals(areaOf(c, far)), "a block 30 blocks from any node: " + areaOf(c, far)
                    + " (its nearest named area is Cliffside Veins)");
            setb(c, upper, "minecraft:gray_wool");
            setb(c, cliff, "minecraft:prismarine");
            setb(c, far, "minecraft:light_blue_wool");
            for (String standIn : new String[]{"Royal Mines", "Upper Mines", "Cliffside Veins"}) {
                mines(c, standIn, "Upper Mines Mithril: 10%");
                c.waitUntil("the swap is on (" + standIn + ")", mc -> (Boolean) Mod.staticCall(STONE, "isEnabled"), 40);
                // isEnabled can still be true from the case before, whose commission wanted other areas: wait until the
                // mod's tick has taken THIS commission (Cliffside stone, Upper Mines not), then let any section compile
                // started under the old answer finish before recording, or it records the Upper Mines block as stone
                // (gate-1104a 26.3 cheat, "standing in Royal Mines").
                c.waitUntil("the Upper Mines Mithril commission applied (" + standIn + ")",
                        mc -> (Boolean) Mod.staticCall(STONE, "stoneAt", cliff.getX(), cliff.getY(), cliff.getZ())
                                && !(Boolean) Mod.staticCall(STONE, "stoneAt", upper.getX(), upper.getY(), upper.getZ()), 40);
                c.ctx().waitTicks(10);
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
                // The far block is outside every node's reach but still has its own area (the nearest one, Cliffside
                // Veins), whatever area he stands in.
                boolean farWant = true;
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

    private static void carpetTexture(Session c) {
        BlockPos f = feet(c);
        BlockPos lantern = f.offset(0, -1, -2);
        BlockPos carpet = lantern.above();
        BlockPos onStone = f.offset(3, -1, -2);
        BlockPos light = f.offset(-3, -1, -2);
        BlockPos lightCarpet = light.above();
        List<BlockPos> all = List.of(lantern, carpet, onStone, onStone.above(), light, lightCarpet);
        try (Settings set = new Settings(c).with(CFG, "Carpet", true)) {
            setb(c, lantern, "minecraft:sea_lantern");
            setb(c, carpet, "minecraft:gray_carpet");
            setb(c, onStone, "minecraft:stone");
            setb(c, onStone.above(), "minecraft:gray_carpet");
            setb(c, light, "minecraft:sea_lantern");
            setb(c, lightCarpet, "minecraft:light_blue_carpet");
            mines(c, "Royal Mines", "Goblin Slayer: 10%");
            long swaps = c.onClient(mc -> (Long) Mod.staticCall(HL, "carpetSwapCount"));
            c.waitUntil("both vein carpets found", mc -> (Boolean) Mod.staticCall(HL, "hasCarpetAt", carpet.getX(),
                    carpet.getY(), carpet.getZ()) && (Boolean) Mod.staticCall(HL, "hasCarpetAt", lightCarpet.getX(),
                    lightCarpet.getY(), lightCarpet.getZ()), 60);
            c.waitUntil("the mesher drew them as red carpet", mc -> (Long) Mod.staticCall(HL, "carpetSwapCount") >= swaps + 2, 200);
            c.check((Boolean) c.onClient(mc -> Mod.staticCall(HL, "carpetRetextured", carpet.getX(), carpet.getY(), carpet.getZ())),
                    "the gray vein carpet is not retextured");
            c.check((Boolean) c.onClient(mc -> Mod.staticCall(HL, "carpetRetextured", lightCarpet.getX(), lightCarpet.getY(),
                    lightCarpet.getZ())), "the light blue vein carpet is not retextured");
            c.check(!(Boolean) c.onClient(mc -> Mod.staticCall(HL, "carpetRetextured", onStone.getX(), onStone.getY() + 1, onStone.getZ())),
                    "a carpet on plain stone was retextured");
            // Off: the carpets go back to their own look (the mesher is asked again, and answers null).
            c.ctx().runOnClient(mc -> Mod.set(CFG, "setCarpet", false));
            c.waitUntil("retexture off with the switch", mc -> !(Boolean) Mod.staticCall(HL, "carpetRetextured", carpet.getX(),
                    carpet.getY(), carpet.getZ()), 100);
            c.note("2 vein carpets (gray, light blue) drawn as red carpet by the mesher; a carpet on stone untouched; off restores");
        } finally {
            for (BlockPos b : all) {
                setb(c, b, "minecraft:air");
            }
            hub(c);
        }
    }

    /**
     * "if i am in an area looking out with the stone highlight stuff on it treats everything outside my area as still in the
     * area": standing in one area, the blocks of the other keep their own answer. Both ways round.
     */
    private static void areaInside(Session c) {
        BlockPos f = feet(c);
        BlockPos upper = f.offset(-6, 1, -3);
        BlockPos cliff = f.offset(6, 1, -3);
        BlockPos upperFar = f.offset(-12, 1, 26);
        BlockPos cliffFar = f.offset(12, 1, 26);
        List<BlockPos> all = List.of(upper, cliff, upperFar, cliffFar);
        try (Settings set = new Settings(c).with(CFG, "MithrilStone", true)) {
            useGraph(c, f);
            for (BlockPos b : all) {
                setb(c, b, "minecraft:gray_wool");
            }
            c.check("Upper Mines".equals(areaOf(c, upperFar)), "the far west block is " + areaOf(c, upperFar));
            c.check("Cliffside Veins".equals(areaOf(c, cliffFar)), "the far east block is " + areaOf(c, cliffFar));
            // Standing in Upper Mines with only Cliffside Veins open: Cliffside blocks mithril, Upper Mines blocks stone.
            inside(c, "Upper Mines", "Cliffside Veins Mithril: 10%", all, upper, cliff, upperFar, cliffFar, true, false);
            // The reverse.
            inside(c, "Cliffside Veins", "Upper Mines Mithril: 10%", all, upper, cliff, upperFar, cliffFar, false, true);
            c.note("in Upper Mines with Cliffside Veins open: Cliffside blocks mithril (near and far), Upper blocks stone; and the reverse");
        } finally {
            c.ctx().runOnClient(mc -> Mod.staticCall(STONE, "recordSwapsForTest", false));
            for (BlockPos b : all) {
                setb(c, b, "minecraft:air");
            }
            clearGraph(c);
            hub(c);
        }
    }

    private static void inside(Session c, String standIn, String com, List<BlockPos> all, BlockPos upper, BlockPos cliff,
                               BlockPos upperFar, BlockPos cliffFar, boolean upperStone, boolean cliffStone) {
        mines(c, standIn, com);
        c.waitUntil("the swap is on (" + standIn + ")", mc -> (Boolean) Mod.staticCall(STONE, "isEnabled"), 40);
        c.ctx().waitTicks(12); // the commission the tick has just read must be the one the mesher judges by
        c.ctx().runOnClient(mc -> {
            Mod.staticCall(STONE, "recordSwapsForTest", true);
            Mod.staticCall(STONE, "remeshForTest");
        });
        BlockPos stoneOne = upperStone ? upper : cliff;
        c.waitUntil("the mesher drew a stone block (" + standIn + ")",
                mc -> (Boolean) Mod.staticCall(STONE, "swappedAt", stoneOne.getX(), stoneOne.getY(), stoneOne.getZ()), 100);
        c.ctx().waitTicks(10);
        for (Object[] row : new Object[][]{{"near Upper Mines", upper, upperStone}, {"far Upper Mines", upperFar, upperStone},
                {"near Cliffside Veins", cliff, cliffStone}, {"far Cliffside Veins", cliffFar, cliffStone}}) {
            BlockPos b = (BlockPos) row[1];
            boolean swapped = c.onClient(mc -> (Boolean) Mod.staticCall(STONE, "swappedAt", b.getX(), b.getY(), b.getZ()));
            c.check(swapped == (Boolean) row[2], "standing in " + standIn + " (" + com + "), the " + row[0]
                    + " block was drawn as " + (swapped ? "stone" : "mithril") + ", wanted " + ((Boolean) row[2] ? "stone" : "mithril"));
        }
    }
}

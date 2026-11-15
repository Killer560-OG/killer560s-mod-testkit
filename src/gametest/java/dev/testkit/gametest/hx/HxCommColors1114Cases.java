package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Commission Block Colors (mod glacitecomm-b-1114): with an open collector commission the blocks it counts are highlighted
 * (or left alone) and the other ores are dimmed, read off what the chunk mesher drew (the mod's recorded swaps); DONE, no
 * commission or the switch off draws everything normally; a percent moving does not re-mesh again.
 * <ul>
 *   <li>1114-commcolors-tungsten: "Tungsten Collector: 12%" - clay (tungsten) is drawn as wool, terracotta (umber) and packed
 *       ice (glacite) as stone, an aquamarine glass as gray glass, plain cobblestone untouched; Highlight off leaves clay as it is.</li>
 *   <li>1114-commcolors-gem: "Aquamarine Gemstone Collector" - the aquamarine glass is the wanted one, tungsten clay is dimmed.</li>
 *   <li>1114-commcolors-normal: DONE, no commission and the switch off draw all of it normally.</li>
 *   <li>1114-commcolors-remesh: one re-mesh per change of the wanted set, none for a percent or while idle.</li>
 * </ul>
 */
final class HxCommColors1114Cases {

    private static final String CFG = "commissioncolors.CommissionColorsConfig";
    private static final String CC = "commissioncolors.CommissionColors";
    private static final String STATE = "miningqol.MiningState";

    private HxCommColors1114Cases() {
    }

    static void register(Session s) {
        s.test("1114-commcolors-tungsten", HxCommColors1114Cases::tungsten);
        s.test("1114-commcolors-gem", HxCommColors1114Cases::gem);
        s.test("1114-commcolors-normal", HxCommColors1114Cases::normal);
        s.test("1114-commcolors-remesh", HxCommColors1114Cases::remesh);
    }

    private static void feed(Session c, String... coms) {
        c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Glacite Tunnels", "Purse: 0");
        List<String> tab = new ArrayList<>(List.of("Area: Dwarven Mines", "Profile: Test", ""));
        if (coms.length > 0) {
            tab.add("Commissions:");
            for (String com : coms) tab.add(" " + com);
            tab.add("");
        }
        c.hx().tab(tab, null, null);
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

    /** What the mesher drew at p in place of its own block: a registry id, or "as is". */
    private static String drawn(Session c, BlockPos p) {
        Block b = c.onClient(mc -> (Block) Mod.staticCall(CC, "drawnAt", p.getX(), p.getY(), p.getZ()));
        return b == null ? "as is" : BuiltInRegistries.BLOCK.getKey(b).getPath();
    }

    private static void mesh(Session c) {
        c.ctx().waitTicks(10);
        c.ctx().runOnClient(mc -> {
            Mod.staticCall(CC, "recordForTest", true);
            Mod.staticCall("miningqol.MithrilStone", "remeshForTest");
        });
        c.ctx().waitTicks(40);
    }

    private static void waitActive(Session c, boolean on) {
        c.waitUntil("commission colors " + (on ? "active" : "idle"), mc -> (Boolean) Mod.staticCall(CC, "isActive") == on, 100);
    }

    private static final String[] BLOCKS = {"minecraft:clay", "minecraft:terracotta", "minecraft:packed_ice",
            "minecraft:blue_stained_glass", "minecraft:cobblestone"};

    private static List<BlockPos> place(Session c) {
        BlockPos f = feet(c);
        List<BlockPos> ps = new ArrayList<>();
        for (int i = 0; i < BLOCKS.length; i++) {
            BlockPos p = f.offset(3 + i, 1, 4);
            ps.add(p);
            setb(c, p, BLOCKS[i]);
        }
        c.ctx().waitTicks(10);
        return ps;
    }

    private static void clear(Session c, List<BlockPos> ps) {
        c.ctx().runOnClient(mc -> Mod.staticCall(CC, "recordForTest", false));
        for (BlockPos p : ps) setb(c, p, "minecraft:air");
    }

    private static void tungsten(Session c) {
        List<BlockPos> ps = place(c);
        try (Settings set = new Settings(c).with(CFG, "Enabled", true)) {
            feed(c, "Tungsten Collector: 12%");
            waitActive(c, true);
            mesh(c);
            String clay = drawn(c, ps.get(0)), umber = drawn(c, ps.get(1)), glacite = drawn(c, ps.get(2)),
                    gem = drawn(c, ps.get(3)), cobble = drawn(c, ps.get(4));
            c.note("clay=" + clay + " terracotta=" + umber + " packed_ice=" + glacite + " blue glass=" + gem + " cobble=" + cobble);
            c.check(clay.endsWith("_wool"), "wanted tungsten clay should be highlighted wool, was " + clay);
            c.check(umber.equals("stone"), "umber terracotta should be dimmed to stone, was " + umber);
            c.check(glacite.equals("stone"), "glacite packed ice should be dimmed to stone, was " + glacite);
            c.check(gem.equals("gray_stained_glass"), "aquamarine glass should be dimmed to gray glass, was " + gem);
            c.check(cobble.endsWith("_wool"), "plain cobblestone counts for tungsten and is highlighted, was " + cobble);
            c.check((Long) c.onClient(mc -> Mod.staticCall(CC, "swappedCount")) > 0, "the mesher hook never acted");
            // Another ore wanted: plain cobblestone is never dimmed.
            feed(c, "Glacite Collector: 12%");
            mesh(c);
            String other = drawn(c, ps.get(4));
            c.check(other.equals("as is"), "plain cobblestone is never dimmed, was " + other);
            feed(c, "Tungsten Collector: 12%");
            mesh(c);
            // Highlight off: the wanted block renders as it is.
            try (Settings s2 = new Settings(c).with(CFG, "Highlight", false)) {
                c.ctx().waitTicks(10);
                mesh(c);
                String again = drawn(c, ps.get(0));
                c.check(again.equals("as is"),
                        "with Highlight off the clay should render as is, was " + again);
            }
        } finally {
            clear(c, ps);
            feed(c);
        }
    }

    private static void gem(Session c) {
        List<BlockPos> ps = place(c);
        try (Settings set = new Settings(c).with(CFG, "Enabled", true)) {
            feed(c, "Aquamarine Gemstone Collector: 40%");
            waitActive(c, true);
            mesh(c);
            String gem = drawn(c, ps.get(3)), clay = drawn(c, ps.get(0));
            c.note("blue glass=" + gem + " clay=" + clay);
            c.check(gem.endsWith("_stained_glass") && !gem.equals("gray_stained_glass"),
                    "wanted aquamarine glass should be highlighted glass, was " + gem);
            c.check(clay.equals("stone"), "tungsten clay is not wanted and should be dimmed, was " + clay);
        } finally {
            clear(c, ps);
            feed(c);
        }
    }

    private static void normal(Session c) {
        List<BlockPos> ps = place(c);
        try (Settings set = new Settings(c).with(CFG, "Enabled", true)) {
            feed(c, "Tungsten Collector: DONE");
            c.ctx().waitTicks(20);
            c.check(!(Boolean) c.onClient(mc -> Mod.staticCall(CC, "isActive")), "a DONE commission activated the swap");
            mesh(c);
            for (BlockPos p : ps) c.check(drawn(c, p).equals("as is"), "DONE: " + p + " drawn as " + drawn(c, p));
            feed(c);
            c.ctx().waitTicks(20);
            c.check(!(Boolean) c.onClient(mc -> Mod.staticCall(CC, "isActive")), "no commission activated the swap");
            // Open commission, then the switch off: back to normal.
            feed(c, "Tungsten Collector: 5%");
            waitActive(c, true);
            try (Settings off = new Settings(c).with(CFG, "Enabled", false)) {
                waitActive(c, false);
                mesh(c);
                for (BlockPos p : ps) c.check(drawn(c, p).equals("as is"), "switch off: " + p + " drawn as " + drawn(c, p));
            }
        } finally {
            clear(c, ps);
            feed(c);
        }
    }

    private static void remesh(Session c) {
        try (Settings set = new Settings(c).with(CFG, "Enabled", true)) {
            feed(c, "Tungsten Collector: 12%");
            waitActive(c, true);
            c.ctx().waitTicks(20);
            int r0 = c.onClient(mc -> (Integer) Mod.staticCall(CC, "remeshCount"));
            c.ctx().waitTicks(80);
            c.check((Integer) c.onClient(mc -> Mod.staticCall(CC, "remeshCount")) == r0, "re-meshed while idle");
            feed(c, "Tungsten Collector: 60%");
            c.ctx().waitTicks(40);
            c.check((Integer) c.onClient(mc -> Mod.staticCall(CC, "remeshCount")) == r0, "a percent change re-meshed");
            feed(c, "Umber Collector: 3%");
            c.waitUntil("the wanted set moved", mc -> (Integer) Mod.staticCall(CC, "remeshCount") > r0, 100);
            c.ctx().waitTicks(40);
            int r1 = c.onClient(mc -> (Integer) Mod.staticCall(CC, "remeshCount"));
            c.check(r1 == r0 + 1, "a new wanted set should re-mesh exactly once, was " + (r1 - r0));
            c.note("remeshes: idle 0, percent 0, new set " + (r1 - r0));
        } finally {
            feed(c);
        }
    }
}

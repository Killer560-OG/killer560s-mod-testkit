package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod chestmiss-1113 (killer560, 2026-11-13: "my friend is using our powder chest highlighter and very consistently is having
 * chests spawn that it doesnt highlight", "it has happened to me before a lot too"). A powder chest appears at or beside the
 * block he just dug (his 11-10 log: 355 of 357 chests within 2 blocks of a dig of his), so his own dig packets are the proof
 * that a chest is his; the "uncovered" line only confirms. Every dig here is the real attack key (creative: the block breaks
 * and START_DESTROY_BLOCK goes through the send tap).
 * <ul>
 *   <li>1113-chestmiss-noline: a chest where he dug with no uncovered line (lost, hidden or reworded) is highlighted, stays
 *       past the 3 s line window, and logs one diagnostic.</li>
 *   <li>1113-chestmiss-other: another player's chest 4 blocks away, his line and his chest in three orders: the line used to
 *       go to whichever chest came first, so his own was left dark.</li>
 *   <li>1113-chestmiss-far: his chest more than 10 blocks from where he stands now (he dug, then moved).</li>
 *   <li>1113-chestmiss-island: a tab list with no Area line (island read lapses): the line was dropped and no chest tracked.</li>
 *   <li>1113-chestmiss-section: the chest arrives in a multi-block section update (server fill).</li>
 * </ul>
 */
final class HxChestMiss1113Cases {

    private static final String CFG = "powdermining.PowderMiningConfig";
    private static final String TC = "powdermining.TreasureChests";
    private static final String STATE = "miningqol.MiningState";
    private static final String UNCOVERED = "§6You uncovered a treasure chest!";

    private HxChestMiss1113Cases() {
    }

    static void register(Session s) {
        s.test("1113-chestmiss-noline", HxChestMiss1113Cases::noLine);
        s.test("1113-chestmiss-other", HxChestMiss1113Cases::other);
        s.test("1113-chestmiss-far", HxChestMiss1113Cases::far);
        s.test("1113-chestmiss-island", HxChestMiss1113Cases::island);
        s.test("1113-chestmiss-section", HxChestMiss1113Cases::section);
    }

    private interface Body {
        void run(Session c, int[] f);
    }

    /** Treasure Chest Highlight on (alerts quiet), Crystal Hollows, out of every window; cleanup always. */
    private static void with(Session c, Body body) {
        int[] f = feet(c);
        String mode = c.onClient(mc -> mc.gameMode.getPlayerMode().name().toLowerCase(Locale.ROOT));
        c.server().command("gamemode creative @p"); // his dig breaks the block at once (the session plays in survival)
        Object cfg = c.onClient(mc -> Mod.cfg(CFG));
        boolean old = c.onClient(mc -> (Boolean) Mod.call(cfg, "isTreasureChestRaw"));
        try (Settings set = new Settings(c).custom(() -> Mod.call(cfg, "setTreasureChest", true),
                () -> Mod.call(cfg, "setTreasureChest", old)).with(CFG, "ChestAlertSound", false).with(CFG, "ChestAlertTitle", false)) {
            hollows(c);
            c.ctx().waitTicks(120); // past the 5 s dig window and the 3 s line window of any earlier case
            body.run(c, f);
        } finally {
            c.ctx().runOnClient(mc -> mc.options.keyAttack.setDown(false));
            c.server().command("gamemode " + mode + " @p");
            c.server().command(String.format(Locale.ROOT, "tp @p %d.5 %d %d.5", f[0], f[1], f[2]));
            c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", f[0] - 6, f[1], f[2] - 6,
                    f[0] + 6, f[1] + 2, f[2] + 6));
            c.ctx().runOnClient(mc -> Mod.staticCall(CFG, "load"));
            hub(c);
        }
    }

    // ---- 1113-chestmiss-noline ---------------------------------------------------------------------------------------

    private static void noLine(Session c) {
        with(c, (cc, f) -> {
            int d0 = intCall(c, "diagnostics", 0);
            int[] h = {f[0] + 2, f[1], f[2]};
            int[] side = {f[0] + 2, f[1], f[2] + 1};
            dig(c, h);
            put(c, true, h);
            c.ctx().waitTicks(3);
            c.check(tracked(c, h), "a chest where he just dug was not highlighted (no uncovered line came)");
            int[] g = {f[0] - 2, f[1], f[2] + 2};
            dig(c, g);
            int[] beside = {g[0], g[1], g[2] + 1};
            put(c, true, beside);
            c.ctx().waitTicks(3);
            c.check(tracked(c, beside), "a chest one block from his dig was not highlighted (no uncovered line came)");
            c.ctx().waitTicks(80); // past the 3 s line window
            c.check(tracked(c, h) && tracked(c, beside), "his chests lost the highlight when no line came within 3 s");
            c.check(intCall(c, "digCount", 0) > 0, "no dig of his was seen by the send tap (mixin did not act)");
            c.check(intCall(c, "diagnostics", 0) > d0, "no diagnostic for chests that got no uncovered line");
            String last = c.onClient(mc -> String.valueOf(Mod.staticCall(TC, "lastDiagnostic")));
            c.check(last.contains("no uncovered line"), "diagnostic text: " + last);
            c.check(!tracked(c, side), "a spot where no chest came is tracked");
        });
    }

    // ---- 1113-chestmiss-other ----------------------------------------------------------------------------------------

    private static void other(Session c) {
        with(c, (cc, f) -> {
            String[] orders = {"LOH", "OLH", "LHO"};
            int[][] mine = {{f[0] + 2, f[1], f[2]}, {f[0] + 2, f[1], f[2] + 2}, {f[0] + 2, f[1], f[2] - 2}};
            int[][] theirs = {{f[0] - 4, f[1], f[2]}, {f[0] - 4, f[1], f[2] + 3}, {f[0] - 4, f[1], f[2] - 3}};
            for (int i = 0; i < orders.length; i++) {
                int[] h = mine[i], o = theirs[i];
                int d0 = intCall(c, "diagnostics", 0);
                dig(c, h);
                for (char ch : orders[i].toCharArray()) {
                    switch (ch) {
                        case 'L' -> c.hx().chat(UNCOVERED);
                        case 'O' -> put(c, true, o);
                        default -> put(c, true, h);
                    }
                    c.ctx().waitTicks(2);
                }
                c.ctx().waitTicks(16); // past the fallback that lets a waiting line take a near chest
                c.check(tracked(c, h), orders[i] + ": his own chest (where he dug) was not highlighted; the other player's "
                        + (tracked(c, o) ? "took his line" : "was not either"));
                c.check(!tracked(c, o), orders[i] + ": another player's chest 4 blocks away was highlighted as his");
                c.ctx().waitTicks(70);
                c.check(!tracked(c, o), orders[i] + ": the other player's chest was claimed late");
                c.check(intCall(c, "diagnostics", 0) > d0, orders[i] + ": no diagnostic for the unclaimed chest");
                put(c, false, h, o);
                c.ctx().waitTicks(20);
            }
        });
    }

    // ---- 1113-chestmiss-far ------------------------------------------------------------------------------------------

    private static void far(Session c) {
        with(c, (cc, f) -> {
            int[] h = {f[0] + 2, f[1], f[2]};
            dig(c, h);
            c.server().command(String.format(Locale.ROOT, "tp @p %d.5 %d %d.5", f[0] - 12, f[1], f[2]));
            c.waitUntil("moved 12 blocks", mc -> mc.player.getX() < f[0] - 10, 60);
            c.hx().chat(UNCOVERED);
            c.ctx().waitTicks(2);
            put(c, true, h);
            c.ctx().waitTicks(16);
            c.check(tracked(c, h), "his chest 14 blocks from where he stands (he dug, then moved) was not highlighted");
        });
    }

    // ---- 1113-chestmiss-island ---------------------------------------------------------------------------------------

    private static void island(Session c) {
        with(c, (cc, f) -> {
            // The tab list without its Area line: the island read lapses.
            c.hx().tab(List.of("Profile: Test", "", "Powders:", " Gemstone: 1,000", ""), null, null);
            c.waitUntil("island read lapses", mc -> !"CRYSTAL_HOLLOWS".equals(Mod.staticCall(STATE, "island")), 100);
            String read = c.onClient(mc -> String.valueOf(Mod.staticCall(STATE, "island")));
            int[] h = {f[0] + 2, f[1], f[2]};
            dig(c, h); // line, then chest
            c.hx().chat(UNCOVERED);
            c.ctx().waitTicks(2);
            put(c, true, h);
            c.ctx().waitTicks(4);
            c.check(tracked(c, h), "line then chest with the island read '" + read + "': not highlighted");
            c.ctx().waitTicks(120); // the line ages out of its window (PowderMining.RECENT_MS)
            int[] g = {f[0] - 2, f[1], f[2] + 2};
            dig(c, g); // chest, then line
            put(c, true, g);
            c.ctx().waitTicks(2);
            c.hx().chat(UNCOVERED);
            c.ctx().waitTicks(4);
            c.check(tracked(c, g), "chest then line with the island read '" + read + "': not highlighted");
        });
    }

    // ---- 1113-chestmiss-section --------------------------------------------------------------------------------------

    private static void section(Session c) {
        with(c, (cc, f) -> {
            int[] h = {f[0] + 2, f[1], f[2]};
            dig(c, h);
            c.hx().chat(UNCOVERED);
            c.ctx().waitTicks(2);
            // Two blocks of one section in one server tick: a section blocks update, not a block update.
            c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:chest", h[0], h[1], h[2], h[0],
                    h[1] + 1, h[2]));
            c.waitUntil("the chests reach the client", mc -> mc.level.getBlockState(new net.minecraft.core.BlockPos(h[0], h[1],
                    h[2])).is(net.minecraft.world.level.block.Blocks.CHEST), 40);
            c.ctx().waitTicks(4);
            c.check(tracked(c, h), "his chest from a section update was not highlighted");
        });
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    /** He digs the block at p with the real attack key (creative breaks it at once). */
    private static void dig(Session c, int[] p) {
        c.server().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:stone", p[0], p[1], p[2]));
        c.waitUntil("stone at the dig spot", mc -> !mc.level.getBlockState(new net.minecraft.core.BlockPos(p[0], p[1], p[2]))
                .isAir(), 40);
        look(c, p[0] + 0.5, p[1] + 0.5, p[2] + 0.5);
        c.ctx().waitTicks(2);
        c.ctx().runOnClient(mc -> mc.options.keyAttack.setDown(true)); // held: creative waits 5 ticks after an earlier dig
        c.waitUntil("his dig broke the block", mc -> mc.level.getBlockState(new net.minecraft.core.BlockPos(p[0], p[1], p[2]))
                .isAir(), 40);
        c.ctx().runOnClient(mc -> mc.options.keyAttack.setDown(false));
        c.ctx().waitTicks(2);
    }

    private static void put(Session c, boolean chest, int[]... ps) {
        c.ctx().runOnClient(mc -> {
            for (int[] p : ps) {
                mc.getConnection().handleBlockUpdate(new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(
                        new net.minecraft.core.BlockPos(p[0], p[1], p[2]), (chest ? net.minecraft.world.level.block.Blocks.CHEST
                        : net.minecraft.world.level.block.Blocks.AIR).defaultBlockState()));
            }
        });
    }

    private static void hollows(Session c) {
        c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Jungle", "Purse: 0");
        c.hx().tab(List.of("Area: Crystal Hollows", "Profile: Test", "", "Powders:", " Gemstone: 1,000", ""), null, null);
        c.waitUntil("Crystal Hollows", mc -> "CRYSTAL_HOLLOWS".equals(Mod.staticCall(STATE, "island")), 100);
    }

    /** A static int of TreasureChests, or {@code missing} when the jar has no such method (an older jar). */
    private static int intCall(Session c, String m, int missing) {
        return c.onClient(mc -> {
            try {
                return ((Number) Mod.staticCall(TC, m)).intValue();
            } catch (RuntimeException | AssertionError e) {
                return missing;
            }
        });
    }

    private static boolean tracked(Session c, int[] p) {
        return c.onClient(mc -> (Boolean) Mod.staticCall(TC, "tracked", p[0], p[1], p[2]));
    }

    private static int[] feet(Session c) {
        double[] at = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        return new int[]{(int) Math.floor(at[0]), (int) Math.floor(at[1]), (int) Math.floor(at[2])};
    }

    private static void look(Session c, double x, double y, double z) {
        c.ctx().runOnClient(mc -> {
            net.minecraft.world.phys.Vec3 d = new net.minecraft.world.phys.Vec3(x, y, z).subtract(mc.player.getEyePosition());
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
            mc.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
        });
    }
}

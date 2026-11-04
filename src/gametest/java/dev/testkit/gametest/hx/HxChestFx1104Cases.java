package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod chestfx-1104 (killer560, 2026-11-04), Treasure Chest Highlight.
 * <ul>
 *   <li>1104-chestfx-hide-resend: "hide chest can occasionally bug out after it almost fully opens. itll show the chest and
 *       flicker". A clicked chest is hidden (the render mixin skips it: hiddenSkips moves while it is on screen); the server
 *       then sends air, a client tick sees the air, and the chest comes back. Before the fix that tick dropped the hide and the
 *       re-sent chest was drawn; now it stays hidden (still skipped every frame), the spot is forgotten a few seconds after
 *       the chest is really gone, and a fresh claim at the same spot is drawn again.</li>
 *   <li>1104-chestfx-tracer: three claimed chests, oldest the farthest. Chain draws crosshair -> oldest -> middle -> newest
 *       (each segment's endpoints checked against the chest centers), From Crosshair one line from the crosshair to each;
 *       every segment has its chest's box color (a set Box Color with Fade off, then the fade's per-chest red).</li>
 * </ul>
 */
final class HxChestFx1104Cases {

    private static final String CFG = "powdermining.PowderMiningConfig";
    private static final String TC = "powdermining.TreasureChests";
    private static final String STATE = "miningqol.MiningState";
    private static final String UNCOVERED = "§6You uncovered a treasure chest!";

    private HxChestFx1104Cases() {
    }

    static void register(Session s) {
        s.test("1104-chestfx-hide-resend", HxChestFx1104Cases::hideResend);
        s.test("1104-chestfx-tracer", HxChestFx1104Cases::tracer);
        s.test("1104-chestfx-loot-owner", HxChestFx1104Cases::lootOwner);
    }

    // ---- 1104-chestfx-loot-owner -------------------------------------------------------------------------------------

    /**
     * "i just found a chest that isnt highlighting" (killer560 2026-11-04, a live chest beside a highlighted one, with "This
     * chest has already been looted." x32 in chat). The loot block was given to the last HIGHLIGHTED chest he clicked, so a
     * click on an untracked chest (already looted) or a second chest clicked before the first one's loot came took the
     * highlight off a live chest. Now the loot belongs to the last chest clicked of any kind, and a lid opening (block event)
     * removes exactly the chest it opened.
     */
    private static void lootOwner(Session c) {
        int[] f = feet(c);
        int y = f[1];
        int[] a = {f[0] + 3, y, f[2] + 1};
        int[] b = {f[0] - 3, y, f[2] + 1};
        int[] x = {f[0], y, f[2] + 3};
        Settings s0 = new Settings(c);
        on(c, s0, "TreasureChest");
        try (Settings set = s0.with(CFG, "ChestAlertSound", false).with(CFG, "ChestAlertTitle", false)) {
            hollows(c);
            setblock(c, x[0], x[1], x[2], "minecraft:chest"); // a chest that is not his (an old, looted one)
            c.ctx().waitTicks(80); // out of the claim window
            claim(c, a[0], a[1], a[2]);
            claim(c, b[0], b[1], b[2]);
            c.check(!tracked(c, x[0], x[1], x[2]), "the untracked chest was claimed");

            // He clicks B (lockpicking), then the looted chest X; X's "loot" (any loot block) must not take B's highlight.
            use(c, b);
            use(c, x);
            c.ctx().runOnClient(mc -> Mod.staticCall(TC, "onLooted"));
            c.check(tracked(c, b[0], b[1], b[2]), "a click on an untracked chest let its loot take a live chest's highlight");
            c.check(tracked(c, a[0], a[1], a[2]), "the other live chest lost its highlight");

            // He clicks A, then B before A's loot comes; A's lid opens: A is done, B stays.
            int op0 = intCall(c, "opened");
            use(c, a);
            use(c, b);
            c.ctx().runOnClient(mc -> mc.getConnection().handleBlockEvent(new net.minecraft.network.protocol.game
                    .ClientboundBlockEventPacket(new net.minecraft.core.BlockPos(a[0], a[1], a[2]),
                    net.minecraft.world.level.block.Blocks.CHEST, 1, 1)));
            c.check(intCall(c, "opened") == op0 + 1, "the lid opening was not seen (block event hook did not act)");
            c.check(!tracked(c, a[0], a[1], a[2]), "the opened chest kept its highlight");
            c.ctx().runOnClient(mc -> Mod.staticCall(TC, "onLooted"));
            c.check(tracked(c, b[0], b[1], b[2]), "the opened chest's loot took the highlight of the next chest he clicked");
            // B's own loot then ends B.
            c.ctx().runOnClient(mc -> Mod.staticCall(TC, "onLooted"));
            c.check(!tracked(c, b[0], b[1], b[2]), "B's loot did not end B");
            c.check(c.events("container.click").isEmpty(), "the chest features clicked something");
        } finally {
            for (int[] p : new int[][]{a, b, x}) {
                setblock(c, p[0], p[1], p[2], "minecraft:air");
            }
            c.ctx().runOnClient(mc -> Mod.staticCall(CFG, "load"));
            hub(c);
        }
    }

    private static void use(Session c, int[] p) {
        c.ctx().runOnClient(mc -> Mod.staticCall(TC, "useForTest", p[0], p[1], p[2]));
    }

    private static void hollows(Session c) {
        c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Jungle", "Purse: 0");
        c.hx().tab(List.of("Area: Crystal Hollows", "Profile: Test", "", "Powders:", " Gemstone: 1,000", ""), null, null);
        c.waitUntil("Crystal Hollows", mc -> "CRYSTAL_HOLLOWS".equals(Mod.staticCall(STATE, "island")), 100);
    }

    private static Settings on(Session c, Settings s, String prop) {
        Object cfg = c.onClient(mc -> Mod.cfg(CFG));
        boolean old = c.onClient(mc -> (Boolean) Mod.call(cfg, "is" + prop + "Raw"));
        return s.custom(() -> Mod.call(cfg, "set" + prop, true), () -> Mod.call(cfg, "set" + prop, old));
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

    /** The line then the chest block: a claim, waited for. */
    private static void claim(Session c, int x, int y, int z) {
        c.hx().chat(UNCOVERED);
        c.ctx().waitTicks(2);
        setblock(c, x, y, z, "minecraft:chest");
        c.waitUntil("claimed " + x + " " + y + " " + z, mc -> (Boolean) Mod.staticCall(TC, "tracked", x, y, z), 40);
    }

    private static void look(Session c, double x, double y, double z) {
        c.ctx().runOnClient(mc -> {
            net.minecraft.world.phys.Vec3 d = new net.minecraft.world.phys.Vec3(x, y, z).subtract(mc.player.getEyePosition());
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
            mc.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
        });
    }

    // ---- 1104-chestfx-hide-resend ------------------------------------------------------------------------------------

    private static void hideResend(Session c) {
        int[] f = feet(c);
        int x = f[0] + 3, y = f[1], z = f[2];
        Settings s0 = new Settings(c);
        on(c, s0, "TreasureChest");
        try (Settings set = s0.with(CFG, "ChestAlertSound", false).with(CFG, "ChestAlertTitle", false)
                .with(CFG, "TreasureChestHideOpened", true).with(CFG, "TreasureChestTracer", false)) {
            hollows(c);
            claim(c, x, y, z);
            look(c, x + 0.5, y + 0.4, z + 0.5);
            c.ctx().waitTicks(5);
            int sk0 = intCall(c, "hiddenSkips");
            c.ctx().waitTicks(10);
            c.check(intCall(c, "hiddenSkips") == sk0, "a chest he never clicked was skipped");

            c.ctx().runOnClient(mc -> Mod.staticCall(TC, "useForTest", x, y, z));
            c.check(intCall(c, "hiddenCount") == 1, "the clicked chest is not hidden");
            sk0 = intCall(c, "hiddenSkips");
            c.ctx().waitTicks(10);
            int sk1 = intCall(c, "hiddenSkips");
            c.check(sk1 > sk0, "the hidden chest on screen was never skipped (the render mixin did not act)");

            // The flicker: the server takes the chest away and sends it again, and a client tick falls between the two.
            setblock(c, x, y, z, "minecraft:air");
            c.waitUntil("the air arrived", mc -> !mc.level.getBlockState(new net.minecraft.core.BlockPos(x, y, z))
                    .is(net.minecraft.world.level.block.Blocks.CHEST), 40);
            c.ctx().waitTicks(3);
            setblock(c, x, y, z, "minecraft:chest");
            c.waitUntil("the chest came back", mc -> mc.level.getBlockState(new net.minecraft.core.BlockPos(x, y, z))
                    .is(net.minecraft.world.level.block.Blocks.CHEST), 40);
            c.ctx().waitTicks(3);
            c.check(intCall(c, "hiddenCount") == 1, "the re-sent chest lost its hide (it shows and flickers)");
            int sk2 = intCall(c, "hiddenSkips");
            c.ctx().waitTicks(20);
            int sk3 = intCall(c, "hiddenSkips");
            c.check(sk3 >= sk2 + 10, "the re-sent chest was drawn: " + (sk3 - sk2) + " skips in 20 ticks");
            c.note("skips while first hidden " + (sk1 - sk0) + ", after the re-send " + (sk3 - sk2) + " in 20 ticks");

            // Really gone: forgotten a few seconds later (bounded), and a fresh claim at that spot is drawn again.
            setblock(c, x, y, z, "minecraft:air");
            c.waitUntil("the hide ends after the chest is gone",
                    mc -> ((Number) Mod.staticCall(TC, "hiddenCount")).intValue() == 0, 200);
            claim(c, x, y, z);
            c.ctx().waitTicks(5);
            int sk4 = intCall(c, "hiddenSkips");
            c.ctx().waitTicks(10);
            c.check(intCall(c, "hiddenSkips") == sk4, "a fresh chest at the opened spot was hidden");
            c.check(c.events("container.click").isEmpty(), "the chest features clicked something");
        } finally {
            setblock(c, x, y, z, "minecraft:air");
            c.ctx().runOnClient(mc -> Mod.staticCall(CFG, "load"));
            hub(c);
        }
    }

    // ---- 1104-chestfx-tracer -----------------------------------------------------------------------------------------

    private static void tracer(Session c) {
        int[] f = feet(c);
        int y = f[1];
        // Oldest farthest, newest nearest: a nearest-first order would draw the chain backwards.
        int[][] chests = {{f[0] + 6, y, f[2] + 2}, {f[0] - 4, y, f[2] + 1}, {f[0] + 2, y, f[2] - 2}};
        Settings s0 = new Settings(c);
        on(c, s0, "TreasureChest");
        Object chain = Mod.enumValue(CFG + "$TracerMode", "CHAIN");
        Object cross = Mod.enumValue(CFG + "$TracerMode", "FROM_CROSSHAIR");
        try (Settings set = s0.with(CFG, "ChestAlertSound", false).with(CFG, "ChestAlertTitle", false)
                .with(CFG, "TreasureChestTracer", true).with(CFG, "TreasureChestFade", false)
                .with(CFG, "TreasureChestColor", 0xFF00FF00).with(CFG, "TreasureChestTracerMode", chain)
                .with(CFG, "TreasureChestEsp", false)) {
            hollows(c);
            for (int[] p : chests) {
                claim(c, p[0], p[1], p[2]);
                c.ctx().waitTicks(20); // ages apart, so the fade's colors differ below
            }
            List<double[]> segs = segments(c);
            c.check(segs.size() == 3, "chain: " + segs.size() + " segments for 3 chests");
            for (int i = 0; i < segs.size(); i++) {
                double[] s = segs.get(i);
                int[] to = chests[i];
                c.check(s[0] == (i == 0 ? 1 : 0), "chain segment " + i + " from crosshair flag " + s[0]);
                c.check(near(s, 4, to), "chain segment " + i + " ends at " + fmt(s, 4) + ", not chest " + i);
                if (i > 0) {
                    c.check(near(s, 1, chests[i - 1]), "chain segment " + i + " starts at " + fmt(s, 1) + ", not chest " + (i - 1));
                }
                c.check(s[7] == 0 && s[8] == 1 && s[9] == 0, "chain segment " + i + " color " + fmt(s, 7) + ", box is green");
            }
            c.note("chain " + fmt(segs.get(0), 4) + " -> " + fmt(segs.get(1), 4) + " -> " + fmt(segs.get(2), 4));

            // Fade to Red on: each line takes ITS chest's faded color, so older chests' lines are redder.
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setTreasureChestFade", true));
            c.ctx().waitTicks(3);
            segs = segments(c);
            c.check(segs.size() == 3, "fade: " + segs.size() + " segments");
            c.check(segs.get(0)[7] > segs.get(1)[7] && segs.get(1)[7] > segs.get(2)[7] && segs.get(2)[7] > 0,
                    "fade reds not oldest-first: " + segs.get(0)[7] + ", " + segs.get(1)[7] + ", " + segs.get(2)[7]);
            for (double[] s : segs) {
                c.check(Math.abs(s[7] + s[8] - 1.0) < 1e-4 && s[9] == 0, "fade color " + fmt(s, 7) + " is not green toward red");
            }

            c.ctx().runOnClient(mc -> Mod.with(CFG, "TreasureChestTracerMode", cross));
            c.ctx().waitTicks(3);
            segs = segments(c);
            c.check(segs.size() == 3, "from crosshair: " + segs.size() + " segments");
            boolean[] seen = new boolean[3];
            for (double[] s : segs) {
                c.check(s[0] == 1, "from crosshair: a segment not from the crosshair");
                for (int k = 0; k < 3; k++) {
                    seen[k] |= near(s, 4, chests[k]);
                }
            }
            c.check(seen[0] && seen[1] && seen[2], "from crosshair: not one line to each chest");
            c.check(c.events("container.click").isEmpty(), "the chest features clicked something");
        } finally {
            for (int[] p : chests) {
                setblock(c, p[0], p[1], p[2], "minecraft:air");
            }
            c.ctx().runOnClient(mc -> Mod.staticCall(CFG, "load"));
            hub(c);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<double[]> segments(Session c) {
        int fr = intCall(c, "tracerFrames");
        c.waitUntil("a tracer frame", mc -> ((Number) Mod.staticCall(TC, "tracerFrames")).intValue() > fr, 40);
        return c.onClient(mc -> (List<double[]>) Mod.staticCall(TC, "tracerSegments"));
    }

    /** {@code s[o..o+2]} is the center of the chest at {@code p} (box center: x+0.5, y+0.4375, z+0.5). */
    private static boolean near(double[] s, int o, int[] p) {
        return Math.abs(s[o] - (p[0] + 0.5)) < 0.01 && Math.abs(s[o + 1] - (p[1] + 0.4375)) < 0.01
                && Math.abs(s[o + 2] - (p[2] + 0.5)) < 0.01;
    }

    private static String fmt(double[] s, int o) {
        return String.format(Locale.ROOT, "(%.3f %.3f %.3f)", s[o], s[o + 1], s[o + 2]);
    }
}

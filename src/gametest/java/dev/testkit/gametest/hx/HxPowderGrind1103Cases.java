package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod powdergrind-1103 (killer560, 2026-11-03: "implement everything you think i need ... to gemstone powder grind ... no
 * macros"). Every feature is draw-, sound- or chat-only; each case also checks nothing was clicked.
 * <ul>
 *   <li>1103-powdergrind-chest: in the Crystal Hollows, a chest block with no "You uncovered a treasure chest!" line is never
 *       claimed; the line then a chest block (and a chest block then the line) is claimed, the alert counts, the box is
 *       drawn (frame counter moves), the HUD timer reads about 60 s and is drawn; a player's "[VIP] Bob: You uncovered ..."
 *       claims nothing; Through Walls is on only on a cheat jar; the chest block gone drops it; outside the Crystal Hollows
 *       the line does nothing.</li>
 *   <li>1103-powdergrind-lockpick: crit particles on the east side of a claimed chest make the Lockpick Helper point at
 *       EAST and draw (cheat jar); on a legit jar nothing is sampled or drawn.</li>
 *   <li>1103-powdergrind-loot: Hypixel's loot block (separator, CHEST LOCKPICKED, REWARDS, rewards, separator) is counted
 *       by the Powder Tracker (chests, gemstone and mithril powder, a gemstone with its icon) and, with Chest Loot Compact
 *       on, hidden and replaced by one line; a separator that opens no chest block is shown again; Compact off still
 *       counts; "2X POWDER STARTED!" fires the 2x Powder Alert once and a player's copy does not.</li>
 * </ul>
 */
final class HxPowderGrind1103Cases {

    private static final String CFG = "powdermining.PowderMiningConfig";
    private static final String TC = "powdermining.TreasureChests";
    private static final String LP = "powdermining.LockpickHelper";
    private static final String TR = "powdermining.PowderTracker";
    private static final String PC = "powdermining.PowderChat";
    private static final String STATE = "miningqol.MiningState";
    private static final String UNCOVERED = "§6You uncovered a treasure chest!";
    private static final String SEP = "§e§l" + "▬".repeat(64);

    private HxPowderGrind1103Cases() {
    }

    static void register(Session s) {
        s.test("1103-powdergrind-chest", HxPowderGrind1103Cases::chest);
        s.test("1103-powdergrind-lockpick", HxPowderGrind1103Cases::lockpick);
        s.test("1103-powdergrind-loot", HxPowderGrind1103Cases::loot);
    }

    private static void hollows(Session c) {
        c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Jungle", "Purse: 0");
        c.hx().tab(List.of("Area: Crystal Hollows", "Profile: Test", "", "Powders:", " Gemstone: 1,000", ""), null, null);
        c.waitUntil("Crystal Hollows", mc -> "CRYSTAL_HOLLOWS".equals(Mod.staticCall(STATE, "island")), 100);
    }

    /** A main switch ON for the case (read raw: the gated getter reads false outside Skyblock), restored on close. */
    private static Settings on(Session c, Settings s, String prop) {
        Object cfg = c.onClient(mc -> Mod.cfg(CFG));
        boolean old = c.onClient(mc -> (Boolean) Mod.call(cfg, "is" + prop + "Raw"));
        return s.custom(() -> Mod.call(cfg, "set" + prop, true), () -> Mod.call(cfg, "set" + prop, old));
    }

    private static int intCall(Session c, String cls, String m) {
        return c.onClient(mc -> ((Number) Mod.staticCall(cls, m)).intValue());
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

    // ---- 1103-powdergrind-chest -------------------------------------------------------------------------------------

    private static void chest(Session c) {
        int[] f = feet(c);
        int bx = f[0], by = f[1], bz = f[2];
        Settings s0 = new Settings(c);
        on(c, s0, "TreasureChest");
        on(c, s0, "ChestAlert");
        try (Settings set = s0.with(CFG, "ChestAlertSound", false).with(CFG, "ChestAlertTitle", false)
                .with(CFG, "TreasureChestTimer", true).with(CFG, "TreasureChestEsp", false)) {
            // Outside the Crystal Hollows: the line does nothing.
            hub(c);
            int a0 = intCall(c, TC, "alerts");
            c.hx().chat(UNCOVERED);
            c.ctx().waitTicks(5);
            c.check(intCall(c, TC, "alerts") == a0, "the uncovered line fired outside the Crystal Hollows");

            hollows(c);
            // A chest with no line: never claimed.
            setblock(c, bx + 3, by, bz, "minecraft:chest");
            c.ctx().waitTicks(80);
            c.check(!tracked(c, bx + 3, by, bz), "a chest with no uncovered line was claimed");
            setblock(c, bx + 3, by, bz, "minecraft:air");
            c.ctx().waitTicks(80); // the line's match window runs out unclaimed

            // A player's copy of the line, then a chest: nothing.
            c.hx().chat("§7[VIP] Bob§f: You uncovered a treasure chest!");
            setblock(c, bx - 3, by, bz, "minecraft:chest");
            c.ctx().waitTicks(10);
            c.check(!tracked(c, bx - 3, by, bz), "a player's chat line claimed a chest");
            c.check(intCall(c, TC, "alerts") == a0, "a player's chat line fired the alert");
            setblock(c, bx - 3, by, bz, "minecraft:air");
            c.ctx().waitTicks(80);

            // The line, then the chest: claimed.
            int claims0 = intCall(c, TC, "claims");
            c.hx().chat(UNCOVERED);
            c.waitUntil("the alert", mc -> ((Number) Mod.staticCall(TC, "alerts")).intValue() == a0 + 1, 20);
            setblock(c, bx + 3, by, bz + 1, "minecraft:chest");
            c.waitUntil("line then chest claimed", mc -> (Boolean) Mod.staticCall(TC, "tracked", bx + 3, by, bz + 1), 40);
            int fr0 = intCall(c, TC, "boxFrames");
            c.ctx().waitTicks(20);
            c.check(intCall(c, TC, "boxFrames") > fr0, "no box drawn on the claimed chest");
            String hud = c.onClient(mc -> (String) Mod.staticCall(TC, "hudLineNow"));
            c.check(hud != null && hud.matches(".*Treasure Chest.*(5[5-9]|60)s.*"), "HUD line " + hud);
            c.waitUntil("the timer HUD is drawn",
                    mc -> (Boolean) Mod.staticCall("hud.HudSeen", "drawnRecently", "mining_treasure_chest"), 40);
            c.check(!c.onClient(mc -> (Boolean) Mod.staticCall(TC, "throughWalls")), "through walls with the setting OFF");
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setTreasureChestEsp", true));
            c.ctx().waitTicks(4);
            boolean walls = c.onClient(mc -> (Boolean) Mod.staticCall(TC, "throughWalls"));
            c.check(walls == Mod.isCheat(), "Through Walls is " + walls + " on a " + (Mod.isCheat() ? "cheat" : "legit") + " jar");
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setTreasureChestEsp", false));
            c.note("line then chest: " + hud);

            // The chest, then the line: claimed too.
            setblock(c, bx - 3, by, bz + 1, "minecraft:chest");
            c.ctx().waitTicks(5);
            c.check(!tracked(c, bx - 3, by, bz + 1), "claimed before its line");
            c.hx().chat(UNCOVERED);
            c.waitUntil("chest then line claimed", mc -> (Boolean) Mod.staticCall(TC, "tracked", bx - 3, by, bz + 1), 40);
            c.check(intCall(c, TC, "claims") == claims0 + 2, "claims " + (intCall(c, TC, "claims") - claims0));

            // Opening the FARTHER of two chests drops that one, not the nearest (killer560, 2026-11-03).
            int[][] two = {{bx + 3, by, bz + 1}, {bx - 3, by, bz + 1}};
            double d0 = c.onClient(mc -> mc.player.getEyePosition().distanceToSqr(two[0][0] + 0.5, two[0][1] + 0.5, two[0][2] + 0.5));
            double d1 = c.onClient(mc -> mc.player.getEyePosition().distanceToSqr(two[1][0] + 0.5, two[1][1] + 0.5, two[1][2] + 0.5));
            if (Math.abs(d0 - d1) < 0.01) {
                c.ctx().runOnClient(mc -> { mc.player.snapTo(bx + 1.5, mc.player.getY(), bz + 1.5, mc.player.getYRot(), mc.player.getXRot()); return null; });
                c.ctx().waitTicks(2);
                d0 = c.onClient(mc -> mc.player.getEyePosition().distanceToSqr(two[0][0] + 0.5, two[0][1] + 0.5, two[0][2] + 0.5));
                d1 = c.onClient(mc -> mc.player.getEyePosition().distanceToSqr(two[1][0] + 0.5, two[1][1] + 0.5, two[1][2] + 0.5));
            }
            int[] far = d0 > d1 ? two[0] : two[1];
            int[] near = d0 > d1 ? two[1] : two[0];
            c.check(Math.abs(d0 - d1) >= 0.01, "the two chests are equally far, the case proves nothing");
            c.ctx().runOnClient(mc -> { Mod.staticCall(TC, "onUseBlock", new net.minecraft.core.BlockPos(far[0], far[1], far[2])); Mod.staticCall(TC, "onLooted"); return null; });
            c.check(!tracked(c, far[0], far[1], far[2]), "the opened (farther) chest kept its highlight");
            c.check(tracked(c, near[0], near[1], near[2]), "the nearer chest lost its highlight when the farther one was opened");
            c.ctx().runOnClient(mc -> { Mod.staticCall(TC, "onLooted"); return null; });
            c.check(!tracked(c, near[0], near[1], near[2]), "with no click remembered, the nearest chest was not dropped");
            setblock(c, bx + 3, by, bz + 1, "minecraft:air");
            setblock(c, bx - 3, by, bz + 1, "minecraft:air");
            c.ctx().waitTicks(5);
            c.hx().chat(UNCOVERED);
            setblock(c, bx + 3, by, bz + 1, "minecraft:chest");
            c.waitUntil("re-claimed for the gone-block check", mc -> (Boolean) Mod.staticCall(TC, "tracked", bx + 3, by, bz + 1), 40);

            // A chest block gone: dropped.
            setblock(c, bx + 3, by, bz + 1, "minecraft:air");
            c.waitUntil("a gone chest is dropped", mc -> !(Boolean) Mod.staticCall(TC, "tracked", bx + 3, by, bz + 1), 40);

            // The switch OFF: nothing tracked or drawn.
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setTreasureChest", false));
            c.waitUntil("cleared with the switch OFF", mc -> ((Number) Mod.staticCall(TC, "count")).intValue() == 0, 20);
            fr0 = intCall(c, TC, "boxFrames");
            c.ctx().waitTicks(10);
            c.check(intCall(c, TC, "boxFrames") == fr0, "drew with the switch OFF");
            c.check(c.events("container.click").isEmpty(), "the chest features clicked something");
        } finally {
            setblock(c, bx + 3, by, bz + 1, "minecraft:air");
            setblock(c, bx - 3, by, bz + 1, "minecraft:air");
            setblock(c, bx + 3, by, bz, "minecraft:air");
            setblock(c, bx - 3, by, bz, "minecraft:air");
            c.ctx().runOnClient(mc -> Mod.staticCall(CFG, "load"));
            hub(c);
        }
    }

    // ---- 1103-powdergrind-lockpick ----------------------------------------------------------------------------------

    private static void lockpick(Session c) {
        int[] f = feet(c);
        int x = f[0] + 2, y = f[1], z = f[2];
        Settings s0 = new Settings(c);
        on(c, s0, "TreasureChest");
        on(c, s0, "LockpickHelper");
        try (Settings set = s0.with(CFG, "ChestAlertSound", false)) {
            hollows(c);
            c.hx().chat(UNCOVERED);
            setblock(c, x, y, z, "minecraft:chest");
            c.waitUntil("the chest claimed", mc -> (Boolean) Mod.staticCall(TC, "tracked", x, y, z), 40);
            int s1 = intCall(c, LP, "samples"), fr = intCall(c, LP, "frames");
            for (int i = 0; i < 6; i++) {
                c.server().command(String.format(Locale.ROOT, "particle minecraft:crit %.3f %.3f %.3f 0 0 0 0 2 force",
                        x + 0.97, y + 0.55, z + 0.5));
                c.ctx().waitTicks(2);
            }
            c.ctx().waitTicks(5);
            int got = intCall(c, LP, "samples") - s1;
            String face = c.onClient(mc -> (String) Mod.staticCall(LP, "faceNow"));
            int drawn = intCall(c, LP, "frames") - fr;
            c.note("samples +" + got + ", face " + face + ", frames +" + drawn);
            if (Mod.isCheat()) {
                c.check(got > 0, "no crit particle sampled at the chest");
                c.check("EAST".equals(face), "the helper points at " + face + ", not EAST");
                c.check(drawn > 0, "the helper drew nothing");
            } else {
                c.check(got == 0 && face == null && drawn == 0, "the Lockpick Helper acted on a legit jar");
            }
            c.check(c.events("container.click").isEmpty(), "the Lockpick Helper clicked something");
        } finally {
            setblock(c, x, y, z, "minecraft:air");
            c.ctx().runOnClient(mc -> Mod.staticCall(CFG, "load"));
            hub(c);
        }
    }

    // ---- 1103-powdergrind-loot --------------------------------------------------------------------------------------

    private static void block(Session c, int gem) {
        c.hx().chat(SEP);
        c.hx().chat("§6§l  CHEST LOCKPICKED ");
        c.hx().chat(""); // Hypixel sends a blank line here (his log, 2026-11-03 22:56)
        c.hx().chat("§a§l  REWARDS");
        c.hx().chat("§d    Gemstone Powder §8x" + gem);
        c.hx().chat("§2    Mithril Powder §8x153");
        c.hx().chat("§6    ⸕ Rough Amber Gemstone §8x24");
        c.hx().chat("§9    Ascension Rope");
        c.hx().chat(SEP);
    }

    private static void loot(Session c) {
        Settings s0 = new Settings(c);
        on(c, s0, "PowderTracker");
        on(c, s0, "LootCompact");
        on(c, s0, "DoublePowder");
        try (Settings set = s0.with(CFG, "DoublePowderSound", false)) {
            hollows(c);
            c.ctx().runOnClient(mc -> Mod.staticCall(TR, "reset"));
            int comp0 = intCall(c, PC, "compactLines"), hid0 = intCall(c, PC, "hiddenLines");
            block(c, 537);
            c.waitUntil("the chest counted", mc -> ((Number) Mod.staticCall(TR, "chests")).intValue() == 1, 40);
            long gem = c.onClient(mc -> ((Number) Mod.staticCall(TR, "gemstone")).longValue());
            long mith = c.onClient(mc -> ((Number) Mod.staticCall(TR, "mithril")).longValue());
            long amber = c.onClient(mc -> ((Number) Mod.staticCall(TR, "item", "Rough Amber Gemstone")).longValue());
            long rope = c.onClient(mc -> ((Number) Mod.staticCall(TR, "item", "Ascension Rope")).longValue());
            c.check(gem == 537 && mith == 153 && amber == 24 && rope == 1,
                    "tracked gem " + gem + ", mithril " + mith + ", amber " + amber + ", rope " + rope);
            c.waitUntil("one compact line", mc -> ((Number) Mod.staticCall(PC, "compactLines")).intValue() == comp0 + 1, 20);
            String line = c.onClient(mc -> (String) Mod.staticCall(PC, "lastCompact"));
            int hid = intCall(c, PC, "hiddenLines") - hid0;
            c.check(line != null && line.contains("+537 Gemstone") && line.contains("+153 Mithril"), "compact line " + line);
            c.check(hid >= 6, "hidden lines " + hid);
            c.note("compact: " + line + ", hidden " + hid);

            // A separator that opens no chest block is shown again.
            int h1 = intCall(c, PC, "hiddenLines");
            c.hx().chat(SEP);
            c.hx().chat("§eSomething else entirely");
            c.ctx().waitTicks(10);
            c.check(intCall(c, PC, "hiddenLines") == h1, "a lone separator stayed hidden");

            // Compact off: shown, still counted.
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setLootCompact", false));
            int comp1 = intCall(c, PC, "compactLines"), h2 = intCall(c, PC, "hiddenLines");
            block(c, 100);
            c.waitUntil("the second chest counted", mc -> ((Number) Mod.staticCall(TR, "chests")).intValue() == 2, 40);
            c.check(intCall(c, PC, "compactLines") == comp1 && intCall(c, PC, "hiddenLines") == h2,
                    "Compact off still hid lines");
            c.check(c.onClient(mc -> ((Number) Mod.staticCall(TR, "gemstone")).longValue()) == 637, "gemstone after two");
            c.waitUntil("the tracker HUD is drawn",
                    mc -> (Boolean) Mod.staticCall("hud.HudSeen", "drawnRecently", "mining_powder_tracker"), 40);

            // 2x Powder: a player's copy does nothing, Hypixel's fires once.
            int d0 = intCall(c, PC, "doubleAlerts");
            c.hx().chat("§7Bob§f: 2X POWDER STARTED!");
            c.ctx().waitTicks(5);
            c.check(intCall(c, PC, "doubleAlerts") == d0, "a player's line fired the 2x alert");
            c.hx().chat("§b§l2X POWDER STARTED! §7Powder is doubled!");
            c.waitUntil("the 2x alert", mc -> ((Number) Mod.staticCall(PC, "doubleAlerts")).intValue() == d0 + 1, 20);
            c.check(c.onClient(mc -> (Boolean) Mod.staticCall(PC, "doublePowderActive")), "2x not active after the start line");
            c.hx().chat("§b§l2X POWDER ENDED!");
            c.waitUntil("2x ended", mc -> !(Boolean) Mod.staticCall(PC, "doublePowderActive"), 20);
            c.check(c.events("container.click").isEmpty(), "the loot features clicked something");
        } finally {
            c.ctx().runOnClient(mc -> Mod.staticCall(CFG, "load"));
            c.ctx().runOnClient(mc -> Mod.staticCall(TR, "reset"));
            hub(c);
        }
    }
}

package dev.testkit.gametest.hx;

import dev.testkit.gametest.LogTap;
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
        s.test("1104-powdergrind-keep-rare", HxPowderGrind1103Cases::keepRare);
        s.test("1104-powdertrk-tab", HxPowderGrind1103Cases::tabPowder);
    }

    private static void hollows(Session c) {
        c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Jungle", "Purse: 0");
        c.hx().tab(List.of("Area: Crystal Hollows", "Profile: Test", "", "Powders:", " Gemstone: 1,000", ""), null, null);
        c.waitUntil("Crystal Hollows", mc -> "CRYSTAL_HOLLOWS".equals(Mod.staticCall(STATE, "island")), 100);
    }

    /** The Hollows tab with a Powders widget of these totals ("Mithril: 1,000"); none at all with no lines. */
    private static void powders(Session c, String... lines) {
        List<String> tab = new java.util.ArrayList<>(List.of("Area: Crystal Hollows", "Profile: Test", ""));
        if (lines.length > 0) {
            tab.add("Powders:");
            for (String l : lines) tab.add(" " + l);
        }
        tab.add("");
        c.hx().tab(tab, null, null);
    }

    private static long tr(Session c, String m) {
        return c.onClient(mc -> ((Number) Mod.staticCall(TR, m)).longValue());
    }

    /**
     * powdertrk-1104 (killer560: "it doesnt apply any buffs"): powder comes from the tab's Powders totals, so a 2x chest
     * counts double; the loot lines are not added on top; spending and a HotM-reset refund are not gains; with no widget
     * the loot lines count.
     */
    private static void tabPowder(Session c) {
        Settings s0 = new Settings(c);
        on(c, s0, "PowderTracker");
        try (Settings set = s0.with(CFG, "DoublePowderSound", false)) {
            hollows(c);
            powders(c, "Mithril: 1,000", "Gemstone: 1,000");
            c.waitUntil("the widget read", mc -> (Boolean) Mod.staticCall(TR, "widgetShows", "Mithril"), 40);
            c.ctx().runOnClient(mc -> Mod.staticCall(TR, "reset"));
            c.hx().chat("§b§l2X POWDER STARTED! §7Powder is doubled!");
            c.waitUntil("2x active", mc -> (Boolean) Mod.staticCall(PC, "doublePowderActive"), 20);
            block(c, 537); // the loot line says 537 and 153; the profile got twice that
            c.waitUntil("the chest counted", mc -> ((Number) Mod.staticCall(TR, "chests")).intValue() == 1, 40);
            c.check(tr(c, "gemstone") == 0 && tr(c, "mithril") == 0,
                    "loot lines counted while the widget shows: gem " + tr(c, "gemstone") + ", mithril " + tr(c, "mithril"));
            powders(c, "Mithril: 1,306", "Gemstone: 2,074");
            c.waitUntil("the 2x gain read", mc -> ((Number) Mod.staticCall(TR, "gemstone")).longValue() == 1074, 40);
            c.ctx().waitTicks(30);
            c.check(tr(c, "gemstone") == 1074 && tr(c, "mithril") == 306,
                    "after the 2x chest: gem " + tr(c, "gemstone") + " (want 1074), mithril " + tr(c, "mithril") + " (want 306)");
            String hud = c.onClient(mc -> String.join("|", (List<String>) Mod.staticCall(TR, "lines")));
            c.check(hud.contains("Gemstone: §d1,074") && hud.contains("2x"), "HUD " + hud);
            // A gain with no chest (ore mining, a goblin) counts; spending does not take any back.
            powders(c, "Mithril: 1,306", "Gemstone: 74");
            c.ctx().waitTicks(25);
            powders(c, "Mithril: 1,306", "Gemstone: 574");
            c.waitUntil("the gain after spending", mc -> ((Number) Mod.staticCall(TR, "gemstone")).longValue() == 1574, 40);
            // A HotM reset refunds millions in one read: not a gain.
            powders(c, "Mithril: 2,501,306", "Gemstone: 574");
            c.ctx().waitTicks(25);
            c.check(tr(c, "mithril") == 306, "a refund counted: mithril " + tr(c, "mithril"));
            // No widget: the loot lines are the fallback, counted once.
            powders(c);
            c.waitUntil("the widget gone", mc -> !(Boolean) Mod.staticCall(TR, "widgetShows", "Gemstone"), 40);
            block(c, 100);
            c.waitUntil("the second chest counted", mc -> ((Number) Mod.staticCall(TR, "chests")).intValue() == 2, 40);
            c.check(tr(c, "gemstone") == 1674 && tr(c, "mithril") == 459,
                    "fallback: gem " + tr(c, "gemstone") + " (want 1674), mithril " + tr(c, "mithril") + " (want 459)");
            // The widget back: its first read is a baseline, not a gain.
            powders(c, "Mithril: 9,000", "Gemstone: 9,000");
            c.waitUntil("the widget back", mc -> (Boolean) Mod.staticCall(TR, "widgetShows", "Gemstone"), 40);
            c.check(tr(c, "gemstone") == 1674, "the first read after the widget came back counted " + tr(c, "gemstone"));
            c.note("2x chest " + 1074 + " gem from the tab, fallback +100, uptime " + tr(c, "activeMs") + " ms");
        } finally {
            c.hx().chat("§b§l2X POWDER ENDED!");
            c.ctx().runOnClient(mc -> Mod.staticCall(CFG, "load"));
            c.ctx().runOnClient(mc -> Mod.staticCall(TR, "reset"));
            hub(c);
        }
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

            // Opening a chest drops THAT chest, not the nearest (killer560, 2026-11-03). Each of two chests is opened in turn:
            // a nearest-chest rule drops the same one both times, so it fails one round whatever the distances are.
            int[][] two = {{bx + 3, by, bz + 1}, {bx - 3, by, bz + 1}};
            for (int round = 0; round < 2; round++) {
                int[] open = two[round];
                int[] other = two[1 - round];
                c.check(tracked(c, open[0], open[1], open[2]) && tracked(c, other[0], other[1], other[2]),
                        "round " + round + ": both chests tracked before opening");
                c.ctx().runOnClient(mc -> {
                    Mod.staticCall(TC, "onUseBlock", new net.minecraft.core.BlockPos(open[0], open[1], open[2]));
                    Mod.staticCall(TC, "onLooted");
                });
                c.check(!tracked(c, open[0], open[1], open[2]), "round " + round + ": the opened chest kept its highlight");
                c.check(tracked(c, other[0], other[1], other[2]), "round " + round + ": the other chest lost its highlight");
                // Put the opened chest back as a fresh claim for the next round.
                setblock(c, open[0], open[1], open[2], "minecraft:air");
                c.ctx().waitTicks(5);
                c.hx().chat(UNCOVERED);
                setblock(c, open[0], open[1], open[2], "minecraft:chest");
                c.waitUntil("round " + round + " re-claimed", mc -> (Boolean) Mod.staticCall(TC, "tracked", open[0], open[1], open[2]), 40);
            }

            // A chest block gone: dropped.
            setblock(c, bx + 3, by, bz + 1, "minecraft:air");
            c.waitUntil("a gone chest is dropped", mc -> !(Boolean) Mod.staticCall(TC, "tracked", bx + 3, by, bz + 1), 40);

            // The switch OFF: nothing tracked or drawn.
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setTreasureChest", false));
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setLockpickHelper", false)); // it tracks chests too, on by default now
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
            // Legit in both builds since 2026-11-03.
            c.check(got > 0, "no crit particle sampled at the chest");
            c.check("EAST".equals(face), "the helper points at " + face + ", not EAST");
            c.check(drawn > 0, "the helper drew nothing");
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

    private static void rareBlock(Session c, String... rewards) {
        c.hx().chat(SEP);
        c.hx().chat("§6§l  CHEST LOCKPICKED ");
        c.hx().chat("");
        c.hx().chat("§a§l  REWARDS");
        for (String r : rewards) {
            c.hx().chat("§9    " + r);
        }
        c.hx().chat(SEP);
    }

    /** One block through the Show mode now set: the new compact line (null when none was printed), and the chest counted. */
    private static String showMode(Session c, String label, String... rewards) {
        int comp = intCall(c, PC, "compactLines"), chests = intCall(c, TR, "chests");
        String before = c.onClient(mc -> (String) Mod.staticCall(PC, "lastCompact"));
        rareBlock(c, rewards);
        c.waitUntil(label + ": the chest counted", mc -> ((Number) Mod.staticCall(TR, "chests")).intValue() == chests + 1, 40);
        c.ctx().waitTicks(8);
        int printed = intCall(c, PC, "compactLines") - comp;
        String line = c.onClient(mc -> (String) Mod.staticCall(PC, "lastCompact"));
        c.note(label + ": printed " + printed + ", " + (printed > 0 ? line : "-"));
        c.check(printed <= 1, label + ": printed " + printed + " lines");
        return printed == 1 ? line : null;
    }

    private static int chatCount(long mark, String text) {
        int n = 0;
        for (String l : LogTap.since(mark)) {
            if (l.contains("[CHAT]") && l.contains(text)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Keep Rare Rewards (mod chport-1104, from staging-mining-3's Powder Chest Chat Filter): with Chest Loot Compact hiding a
     * chest's block, a rare reward's own line still reaches the chat window (read from the client log's [CHAT] lines, the
     * window's own record), a common one does not; switched off, the rare line is hidden too; the switch survives a re-read.
     */
    private static void keepRare(Session c) {
        Settings s0 = new Settings(c);
        on(c, s0, "PowderTracker");
        on(c, s0, "LootCompact");
        Object cfg = c.onClient(mc -> Mod.cfg(CFG));
        boolean was = c.onClient(mc -> (Boolean) Mod.call(cfg, "isKeepRareRewards"));
        try (Settings set = s0) {
            hollows(c);
            c.ctx().runOnClient(mc -> Mod.call(cfg, "setKeepRareRewards", true));
            String[] rich = {"Gemstone Powder §8x50", "⸕ Flawless Ruby Gemstone", "Ascension Rope"};

            long m1 = LogTap.mark();
            int k0 = intCall(c, PC, "keptRare");
            int comp0 = intCall(c, PC, "compactLines");
            rareBlock(c, rich);
            c.waitUntil("Keep Rare on: the compact line", mc -> ((Number) Mod.staticCall(PC, "compactLines")).intValue() == comp0 + 1, 40);
            c.ctx().waitTicks(5);
            int kept = intCall(c, PC, "keptRare") - k0;
            int ruby = chatCount(m1, "Flawless Ruby Gemstone"), rope = chatCount(m1, "Ascension Rope");
            c.note("Keep Rare on: kept " + kept + ", chat shows the ruby " + ruby + "x, the rope " + rope + "x");
            c.check(kept == 1, "Keep Rare on: kept " + kept + " rare lines, want 1");
            c.check(ruby == 1, "Keep Rare on: the Flawless Ruby line reached chat " + ruby + " times, want 1");
            c.check(rope == 0, "Keep Rare on: the common Ascension Rope line reached chat");

            c.ctx().runOnClient(mc -> Mod.call(cfg, "setKeepRareRewards", false));
            long m2 = LogTap.mark();
            int k1 = intCall(c, PC, "keptRare");
            int comp1 = intCall(c, PC, "compactLines");
            rareBlock(c, rich);
            c.waitUntil("Keep Rare off: the compact line", mc -> ((Number) Mod.staticCall(PC, "compactLines")).intValue() == comp1 + 1, 40);
            c.ctx().waitTicks(5);
            c.check(intCall(c, PC, "keptRare") == k1, "Keep Rare off still kept a line");
            c.check(chatCount(m2, "Flawless Ruby Gemstone") == 0, "Keep Rare off: the ruby line reached chat");

            // Survives a save and a re-read: off saved, on in memory, re-read answers off.
            boolean reread = c.onClient(mc -> {
                Mod.call(cfg, "save");
                Mod.call(cfg, "setKeepRareRewards", true);
                Mod.staticCall(CFG, "load");
                return (Boolean) Mod.call(Mod.cfg(CFG), "isKeepRareRewards");
            });
            c.check(!reread, "Keep Rare Rewards OFF did not survive a save and a re-read");
        } finally {
            c.ctx().runOnClient(mc -> {
                Object fresh = Mod.cfg(CFG);
                Mod.call(fresh, "setKeepRareRewards", was);
                Mod.call(fresh, "save");
            });
            c.ctx().runOnClient(mc -> Mod.staticCall(TR, "reset"));
            hub(c);
        }
    }

    private static void loot(Session c) {
        Settings s0 = new Settings(c);
        on(c, s0, "PowderTracker");
        on(c, s0, "LootCompact");
        on(c, s0, "DoublePowder");
        try (Settings set = s0.with(CFG, "DoublePowderSound", false)) {
            hollows(c);
            powders(c); // no Powders widget: the loot lines are what counts (1104-powdertrk-tab covers the widget)
            c.waitUntil("no widget", mc -> !(Boolean) Mod.staticCall(TR, "widgetShows", "Gemstone"), 40);
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

            // Show modes (killer560, 2026-11-03): All -> Powder Only -> Powder + Rare -> Hide All -> All, by left clicks.
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setLootCompact", true));
            String[] rich = {"Gemstone Powder §8x200", "⸕ Flawless Ruby Gemstone", "Ascension Rope"};
            String all = showMode(c, "All", rich);
            c.check(all != null && all.contains("+200 Gemstone") && all.contains("+2 items"), "All: " + all);
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "stepLootShow", 1));
            String po = showMode(c, "Powder Only", rich);
            c.check(po != null && po.contains("+200 Gemstone") && !po.contains("item"), "Powder Only: " + po);
            c.check(showMode(c, "Powder Only, no powder", "Ascension Rope", "⸕ Flawless Ruby Gemstone") == null,
                    "Powder Only printed a line with no powder");
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "stepLootShow", 1));
            String pr = showMode(c, "Powder + Rare", rich);
            c.check(pr != null && pr.contains("+200 Gemstone") && pr.contains("+1 item") && !pr.contains("+2 items"),
                    "Powder + Rare: " + pr);
            c.check(showMode(c, "Powder + Rare, only common", "Ascension Rope", "Gold Essence §8x2") == null,
                    "Powder + Rare printed a line with nothing rare or powder");
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "stepLootShow", 1));
            int h0 = intCall(c, PC, "hiddenLines");
            c.check(showMode(c, "Hide All", rich) == null, "Hide All printed a line");
            c.check(intCall(c, PC, "hiddenLines") - h0 >= 4, "Hide All did not hide the block");
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "stepLootShow", -1));
            String back = showMode(c, "Right click back to Powder + Rare", rich);
            c.check(back != null && back.contains("+1 item"), "right click did not step back: " + back);
            c.check(c.events("container.click").isEmpty(), "the loot features clicked something");
        } finally {
            c.ctx().runOnClient(mc -> Mod.staticCall(CFG, "load"));
            c.ctx().runOnClient(mc -> Mod.staticCall(TR, "reset"));
            hub(c);
        }
    }
}

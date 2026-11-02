package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * The pre-2.0 mining features (mod doc-mining-1101, killer560's to-do Doc "MINING FEATURES PRE 2.0"), package
 * {@code miningqol}. Every case puts the client on the Dwarven Mines the way Hypixel does - the tab's "Area: Dwarven Mines"
 * line, a Commissions widget, the sidebar's area line - and proves the feature ACTED, not only that its state changed:
 * a HUD element drawn, labels or boxes drawn in the world, the chunk mesher drawing mithril blocks as stone.
 * <ul>
 *   <li>1101-mining-commissions: the HUD lines from the widget (DONE green), a waypoint at Lava Springs for "Lava Springs
 *       Mithril", four for an Aquamarine collector, all seven Emissaries while a commission is DONE and none once nothing
 *       is; labels drawn; the HUD element drawn.</li>
 *   <li>1101-mining-ability: a player's "Pickobulus is now available!" line does nothing; Hypixel's used and ready lines
 *       pop "Pickobulus READY" (drawn), the cooldown is measured, and the timer then counts from the next use, from
 *       "This ability is on cooldown for 20s." and from the tab's Pickaxe Ability widget.</li>
 *   <li>1101-mining-pickobulus: holding a tool whose lore says "Ability: Pickobulus" and aiming at a stone wall, every
 *       outlined block is one of the wall's and there are at least five; outlines drawn; a plain pickaxe and a
 *       "Pickobulus: 30s" widget each turn it off.</li>
 *   <li>1101-mining-carpet: a gray carpet on a Sea Lantern is found and filled, one on stone is not.</li>
 *   <li>1101-mining-mithril-stone: with no mithril commission the swap is on and the mesher swaps mithril blocks (the
 *       mixin's own count rises after the re-mesh); an area commission turns it off in that area only, Mithril Miner and
 *       Universal Area Access turn it off, and no Commissions widget leaves the world alone.</li>
 *   <li>1101-mining-titanium: Always finds the placed polished diorite; Commission Area finds it only with Titanium
 *       Miner or the area's titanium commission in that area; Universal Area Access finds it; Through Walls only on a
 *       cheat jar.</li>
 *   <li>1101-mining-raffle: a dropped Name Tag is boxed, a dropped stone is not; boxes drawn; Through Walls only on a
 *       cheat jar.</li>
 * </ul>
 * None of these features sends anything; the session's GrimAC check covers that.
 */
final class HxMining1101Cases {

    private static final String CFG = "miningqol.MiningQolConfig";
    private static final String STATE = "miningqol.MiningState";

    private HxMining1101Cases() {
    }

    static void register(Session s) {
        s.test("1101-mining-commissions", HxMining1101Cases::commissions);
        s.test("1101-mining-ability", HxMining1101Cases::ability);
        s.test("1101-mining-pickobulus", HxMining1101Cases::pickobulus);
        s.test("1101-mining-carpet", HxMining1101Cases::carpet);
        s.test("1101-mining-mithril-stone", HxMining1101Cases::mithrilStone);
        s.test("1101-mining-titanium", HxMining1101Cases::titanium);
        s.test("1101-mining-raffle", HxMining1101Cases::raffle);
    }

    // ---- shared ---------------------------------------------------------------------------------------------------

    /** Dwarven Mines: sidebar area {@code area}, tab Area line, and a Commissions widget with {@code coms} (none: no widget). */
    private static void mines(Session c, String area, String... coms) {
        mines(c, area, List.of(), coms);
    }

    private static void mines(Session c, String area, List<String> extraTab, String... coms) {
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
        tab.addAll(extraTab);
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

    private static int intCall(Session c, String cls, String m) {
        return c.onClient(mc -> ((Number) Mod.staticCall(cls, m)).intValue());
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Session c, String cls, String m) {
        return c.onClient(mc -> new ArrayList<>((List<String>) Mod.staticCall(cls, m)));
    }

    // ---- 1101-mining-commissions ------------------------------------------------------------------------------------

    private static void commissions(Session c) {
        String hud = "miningqol.CommissionHud";
        try (Settings set = new Settings(c).with(CFG, "CommissionHud", true).with(CFG, "CommissionWaypoints", true)
                .with(CFG, "CommissionEmissaries", true).with(CFG, "CommissionColorProgress", true)) {
            mines(c, "Lava Springs", "Lava Springs Mithril: 45%", "Goblin Slayer: DONE",
                    "Aquamarine Gemstone Collector: 10%");
            c.waitUntil("the HUD has the commissions", mc -> ((List<?>) Mod.staticCall(hud, "lines")).size() == 4, 60);
            List<String> lines = strings(c, hud, "lines");
            c.note("HUD: " + lines);
            c.check(lines.get(1).contains("Lava Springs Mithril") && lines.get(1).contains("45%"), "line 1 is " + lines.get(1));
            c.check(lines.get(2).contains("Goblin Slayer: §aDONE"), "DONE is not green: " + lines.get(2));
            List<String> wps = strings(c, hud, "waypointNames");
            c.note("waypoints: " + wps);
            c.check(wps.contains("Lava Springs Mithril@60,197,-15"), "no Lava Springs waypoint");
            c.check(wps.stream().filter(w -> w.startsWith("Aquamarine@")).count() == 4, "not 4 Aquamarine spots");
            c.check(wps.stream().filter(w -> w.startsWith("Emissary@")).count() == 7, "not 7 Emissaries with a DONE");
            int frames = intCall(c, hud, "labelFrames");
            c.ctx().waitTicks(10);
            int frames2 = intCall(c, hud, "labelFrames");
            c.check(frames2 > frames, "the labels were never drawn (" + frames + " -> " + frames2 + ")");
            c.waitUntil("the Commission HUD element is drawn",
                    mc -> (Boolean) Mod.staticCall("hud.HudSeen", "drawnRecently", "mining_commissions"), 60);
            // Nothing DONE: no Emissaries.
            mines(c, "Lava Springs", "Lava Springs Mithril: 50%", "Goblin Slayer: 10%");
            c.waitUntil("no Emissary once nothing is DONE",
                    mc -> ((List<?>) Mod.staticCall(hud, "waypointNames")).stream().noneMatch(w -> w.toString()
                            .startsWith("Emissary@")), 60);
            c.note("labels drawn " + (frames2 - frames) + " frames in 10 ticks; Emissaries gone with nothing DONE");
        } finally {
            hub(c);
        }
    }

    // ---- 1101-mining-ability ----------------------------------------------------------------------------------------

    private static void ability(Session c) {
        String a = "miningqol.AbilityReadyAlert";
        try (Settings set = new Settings(c).with(CFG, "AbilityAlert", true).with(CFG, "AbilityTimer", true)
                .with(CFG, "AbilitySound", false)) {
            mines(c, "Lava Springs", "Goblin Slayer: 10%");
            int shown = intCall(c, a, "popupsShown");
            c.hx().chat("§7Bob§7: Pickobulus is now available!");
            c.hx().chat("§7[VIP] Bob: You used your Pickobulus Pickaxe Ability!");
            c.ctx().waitTicks(5);
            c.check(intCall(c, a, "popupsShown") == shown, "a player's chat line popped the alert");
            c.hx().chat("§aYou used your §r§6Pickobulus §r§aPickaxe Ability!");
            c.ctx().waitTicks(40);
            int frames = intCall(c, a, "popupFrames");
            c.hx().chat("§a§r§6Pickobulus §r§ais now available!");
            c.waitUntil("the popup", mc -> (Integer) Mod.staticCall(a, "popupsShown") == shown + 1, 20);
            String popup = c.onClient(mc -> (String) Mod.staticCall(a, "popup"));
            c.check("§a§lPickobulus READY".equals(popup), "popup text " + popup);
            c.waitUntil("the popup is drawn", mc -> (Integer) Mod.staticCall(a, "popupFrames") > frames, 20);
            Long learned = c.onClient(mc -> (Long) Mod.staticCall(a, "learnedMs", "Pickobulus"));
            c.check(learned != null && learned >= 1500 && learned <= 4000, "measured cooldown " + learned + " ms");
            // Next use: the timer counts from the measured cooldown.
            c.hx().chat("§aYou used your §r§6Pickobulus §r§aPickaxe Ability!");
            c.waitUntil("the timer counts the measured cooldown",
                    mc -> ((List<?>) Mod.staticCall(a, "timer")).stream().anyMatch(s -> s.toString().contains("Pickobulus")), 20);
            // Hypixel's cooldown reply.
            c.hx().chat("§cThis ability is on cooldown for 20s.");
            c.waitUntil("the timer reads ~20s",
                    mc -> ((List<?>) Mod.staticCall(a, "timer")).stream().anyMatch(s -> s.toString().matches(".*Pickobulus: §e(19|20)s")), 20);
            c.waitUntil("the timer HUD is drawn",
                    mc -> (Boolean) Mod.staticCall("hud.HudSeen", "drawnRecently", "mining_ability_timer"), 40);
            // The tab widget wins.
            mines(c, "Lava Springs", List.of("Pickaxe Ability:", " Pickobulus: 1m 5s", ""), "Goblin Slayer: 10%");
            c.waitUntil("the timer reads the tab's 1m 5s",
                    mc -> ((List<?>) Mod.staticCall(a, "timer")).stream().anyMatch(s -> s.toString().matches(".*Pickobulus: §e1m [45]s")), 40);
            c.note("player lines ignored; popup drawn; measured " + learned + " ms; timer from use, reply and tab "
                    + strings(c, a, "timer"));
        } finally {
            hub(c);
        }
    }

    // ---- 1101-mining-pickobulus -------------------------------------------------------------------------------------

    private static void pickobulus(Session c) {
        String p = "miningqol.PickobulusEstimate";
        BlockPos f = feet(c);
        List<BlockPos> wall = new ArrayList<>();
        for (int y = 0; y <= 3; y++) {
            for (int z = -2; z <= 2; z++) {
                wall.add(f.offset(4, y, z));
            }
        }
        try (Settings set = new Settings(c).with(CFG, "Pickobulus", true)) {
            for (BlockPos b : wall) {
                setb(c, b, "minecraft:stone");
            }
            c.hx().give(0, "minecraft:diamond_pickaxe[lore=[{text:\"Ability: Pickobulus  RIGHT CLICK\",italic:false}]]");
            c.ctx().runOnClient(mc -> {
                mc.player.getInventory().setSelectedSlot(0);
                mc.player.setYRot(-90f);
                mc.player.setXRot(10f);
            });
            mines(c, "Royal Mines", "Goblin Slayer: 10%");
            c.waitUntil("an estimate", mc -> "ok".equals(Mod.staticCall(p, "status"))
                    && (Integer) Mod.staticCall(p, "count") > 0, 60);
            int count = intCall(c, p, "count");
            @SuppressWarnings("unchecked")
            List<BlockPos> est = c.onClient(mc -> new ArrayList<>(
                    (List<BlockPos>) Mod.staticCall(p, "estimate", mc.level, wall.get(7))));
            c.note("estimate: " + count + " block(s) from the aim; from the wall's middle: " + est.size());
            c.check(count >= 5, "only " + count + " block(s) outlined");
            c.check(wall.containsAll(est) && !est.isEmpty(), "an outlined block is not in the wall: " + est);
            int frames = intCall(c, p, "frames");
            c.ctx().waitTicks(10);
            c.check(intCall(c, p, "frames") > frames, "the outlines were never drawn");
            // A plain pickaxe: off.
            c.hx().give(0, "minecraft:diamond_pickaxe");
            c.waitUntil("not holding Pickobulus", mc -> "not holding".equals(Mod.staticCall(p, "status")), 40);
            c.check(intCall(c, p, "count") == 0, "outlines without a Pickobulus tool");
            // On cooldown: off.
            c.hx().give(0, "minecraft:diamond_pickaxe[lore=[{text:\"Ability: Pickobulus  RIGHT CLICK\",italic:false}]]");
            mines(c, "Royal Mines", List.of("Pickaxe Ability:", " Pickobulus: 30s", ""), "Goblin Slayer: 10%");
            c.waitUntil("cooldown hides it", mc -> "cooldown".equals(Mod.staticCall(p, "status")), 40);
        } finally {
            for (BlockPos b : wall) {
                setb(c, b, "minecraft:air");
            }
            c.hx().give(0, "minecraft:air");
            hub(c);
        }
    }

    // ---- 1101-mining-carpet -----------------------------------------------------------------------------------------

    private static void carpet(Session c) {
        String h = "miningqol.MiningHighlights";
        BlockPos f = feet(c);
        BlockPos lantern = f.offset(3, 0, 3);
        BlockPos onStone = f.offset(-3, 0, 3);
        try (Settings set = new Settings(c).with(CFG, "Carpet", true)) {
            setb(c, lantern, "minecraft:sea_lantern");
            setb(c, lantern.above(), "minecraft:gray_carpet");
            setb(c, onStone, "minecraft:stone");
            setb(c, onStone.above(), "minecraft:gray_carpet");
            mines(c, "Royal Mines", "Goblin Slayer: 10%");
            c.waitUntil("one vein carpet found", mc -> (Integer) Mod.staticCall(h, "carpetCount") == 1, 60);
            int frames = intCall(c, h, "carpetFrames");
            c.ctx().waitTicks(10);
            c.check(intCall(c, h, "carpetFrames") > frames, "the carpet was never filled");
            c.note("1 of 2 carpets (the one on a Sea Lantern) found and drawn");
        } finally {
            for (BlockPos b : List.of(lantern, lantern.above(), onStone, onStone.above())) {
                setb(c, b, "minecraft:air");
            }
            hub(c);
        }
    }

    // ---- 1101-mining-mithril-stone ----------------------------------------------------------------------------------

    private static void mithrilStone(Session c) {
        String m = "miningqol.MithrilStone";
        BlockPos f = feet(c);
        List<BlockPos> ore = List.of(f.offset(2, 0, -3), f.offset(3, 0, -3), f.offset(2, 1, -3));
        try (Settings set = new Settings(c).with(CFG, "MithrilStone", true).with(CFG, "UniversalAccess", false)) {
            setb(c, ore.get(0), "minecraft:gray_wool");
            setb(c, ore.get(1), "minecraft:prismarine");
            setb(c, ore.get(2), "minecraft:light_blue_wool");
            mines(c, "Royal Mines", "Goblin Slayer: 10%");
            c.waitUntil("the swap is on", mc -> (Boolean) Mod.staticCall(m, "isActive"), 40);
            long before = c.onClient(mc -> (Long) Mod.staticCall(m, "swappedCount"));
            // A block change re-meshes that section too; the remesh on the switch has already been asked for.
            setb(c, f.offset(3, 1, -3), "minecraft:cyan_terracotta");
            c.waitUntil("the mesher drew mithril as stone", mc -> (Long) Mod.staticCall(m, "swappedCount") >= before + 3, 100);
            long after = c.onClient(mc -> (Long) Mod.staticCall(m, "swappedCount"));
            // An area commission: off in that area only.
            mines(c, "Lava Springs", "Lava Springs Mithril: 10%");
            c.waitUntil("off in Lava Springs with Lava Springs Mithril", mc -> !(Boolean) Mod.staticCall(m, "isActive"), 40);
            mines(c, "Royal Mines", "Lava Springs Mithril: 10%");
            c.waitUntil("on in the Royal Mines with Lava Springs Mithril", mc -> (Boolean) Mod.staticCall(m, "isActive"), 40);
            mines(c, "Royal Mines", "Royal Mines Titanium: 10%");
            c.waitUntil("off with the area's titanium commission", mc -> !(Boolean) Mod.staticCall(m, "isActive"), 40);
            mines(c, "Upper Mines", "Mithril Miner: 10%");
            c.waitUntil("off with Mithril Miner", mc -> !(Boolean) Mod.staticCall(m, "isActive"), 40);
            mines(c, "Upper Mines", "Mithril Miner: DONE", "Goblin Slayer: 10%");
            c.waitUntil("on once Mithril Miner is DONE", mc -> (Boolean) Mod.staticCall(m, "isActive"), 40);
            c.ctx().runOnClient(mc -> Mod.set(CFG, "setUniversalAccess", true));
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "save"));
            c.waitUntil("off with Universal Area Access", mc -> !(Boolean) Mod.staticCall(m, "isActive"), 40);
            c.ctx().runOnClient(mc -> Mod.set(CFG, "setUniversalAccess", false));
            mines(c, "Upper Mines");
            c.waitUntil("off with no Commissions widget", mc -> !(Boolean) Mod.staticCall(m, "isActive"), 40);
            c.note("swapped " + (after - before) + " mithril draw(s) after the switch; area, titanium, anywhere, DONE,"
                    + " universal and no-widget rules hold");
        } finally {
            for (BlockPos b : ore) {
                setb(c, b, "minecraft:air");
            }
            setb(c, f.offset(3, 1, -3), "minecraft:air");
            hub(c);
        }
    }

    // ---- 1101-mining-titanium ---------------------------------------------------------------------------------------

    private static void titanium(Session c) {
        String h = "miningqol.MiningHighlights";
        BlockPos f = feet(c);
        BlockPos ti = f.offset(-3, 1, -3);
        Object always = c.onClient(mc -> Mod.enumValue("miningqol.MiningQolConfig$TitaniumMode", "ALWAYS"));
        Object area = c.onClient(mc -> Mod.enumValue("miningqol.MiningQolConfig$TitaniumMode", "COMMISSION"));
        try (Settings set = new Settings(c).with(CFG, "Titanium", true).with(CFG, "TitaniumMode", always)
                .with(CFG, "UniversalAccess", false).with(CFG, "TitaniumEsp", true)) {
            mines(c, "Royal Mines", "Goblin Slayer: 10%");
            c.ctx().waitTicks(25);
            int base = intCall(c, h, "titaniumCount");
            setb(c, ti, "minecraft:polished_diorite");
            c.waitUntil("Always finds it", mc -> (Integer) Mod.staticCall(h, "titaniumCount") == base + 1, 60);
            int frames = intCall(c, h, "titaniumFrames");
            c.ctx().waitTicks(10);
            c.check(intCall(c, h, "titaniumFrames") > frames, "titanium never drawn");
            boolean walls = c.onClient(mc -> (Boolean) Mod.staticCall(h, "titaniumThroughWalls"));
            c.check(walls == Mod.isCheat(), "Through Walls is " + walls + " on a " + (Mod.isCheat() ? "cheat" : "legit") + " jar");
            c.ctx().runOnClient(mc -> Mod.set(CFG, "setTitaniumMode", area));
            c.waitUntil("Commission Area, no titanium commission: none",
                    mc -> (Integer) Mod.staticCall(h, "titaniumCount") == 0, 60);
            mines(c, "Lava Springs", "Royal Mines Titanium: 10%");
            c.ctx().waitTicks(30);
            c.check(intCall(c, h, "titaniumCount") == 0, "Royal Mines Titanium showed titanium in Lava Springs");
            mines(c, "Royal Mines", "Royal Mines Titanium: 10%");
            c.waitUntil("Royal Mines Titanium in the Royal Mines", mc -> (Integer) Mod.staticCall(h, "titaniumCount") >= 1, 60);
            mines(c, "Lava Springs", "Titanium Miner: 10%");
            c.waitUntil("Titanium Miner anywhere", mc -> (Integer) Mod.staticCall(h, "titaniumCount") >= 1, 60);
            mines(c, "Lava Springs", "Goblin Slayer: 10%");
            c.waitUntil("none again", mc -> (Integer) Mod.staticCall(h, "titaniumCount") == 0, 60);
            c.ctx().runOnClient(mc -> Mod.set(CFG, "setUniversalAccess", true));
            c.waitUntil("Universal Area Access", mc -> (Integer) Mod.staticCall(h, "titaniumCount") >= 1, 60);
            c.note("Always, Commission Area (area and anywhere), Universal; Through Walls " + walls);
        } finally {
            setb(c, ti, "minecraft:air");
            hub(c);
        }
    }

    // ---- 1101-mining-raffle -----------------------------------------------------------------------------------------

    private static void raffle(Session c) {
        String h = "miningqol.MiningHighlights";
        BlockPos f = feet(c);
        try (Settings set = new Settings(c).with(CFG, "Raffle", true).with(CFG, "RaffleEsp", true)) {
            mines(c, "Divan's Gateway", "Lucky Raffle: 10%");
            c.server().command("summon item " + (f.getX() + 2.5) + " " + f.getY() + " " + (f.getZ() + 0.5)
                    + " {Item:{id:\"minecraft:name_tag\",count:1},PickupDelay:32767,NoGravity:1b}");
            c.server().command("summon item " + (f.getX() - 2.5) + " " + f.getY() + " " + (f.getZ() + 0.5)
                    + " {Item:{id:\"minecraft:stone\",count:1},PickupDelay:32767,NoGravity:1b}");
            c.waitUntil("one ticket", mc -> (Integer) Mod.staticCall(h, "ticketCount") == 1, 60);
            int frames = intCall(c, h, "raffleFrames");
            c.ctx().waitTicks(10);
            c.check(intCall(c, h, "raffleFrames") > frames, "the ticket box was never drawn");
            boolean walls = c.onClient(mc -> (Boolean) Mod.staticCall(h, "raffleThroughWalls"));
            c.check(walls == Mod.isCheat(), "Through Walls is " + walls + " on a " + (Mod.isCheat() ? "cheat" : "legit") + " jar");
            c.note("1 of 2 dropped items boxed; Through Walls " + walls);
        } finally {
            c.server().command("kill @e[type=item]");
            hub(c);
        }
    }
}

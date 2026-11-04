package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod chestgoblin-1104 (killer560, 2026-11-03: hide a chest after clicking it, tracer lines and a green-to-red countdown for
 * treasure chests; a Golden / Diamond Goblin box and tracer).
 * <ul>
 *   <li>1104-chestgoblin-chest: the countdown color runs green (full minute) -> yellow (half) -> red (none); a claimed chest
 *       draws its tracer and countdown (frame counters move, the shown text is "Ns" and the color is the one for the time
 *       left); before the click the render skip never fires, after the click (Hide Opened Chests ON) the chest's model is
 *       skipped every frame (the mixin's counter moves) while the block is still a chest (no ghost block); with the setting
 *       OFF the skip stops.</li>
 *   <li>1104-chestgoblin-goblin: a NoAI mob named "Golden Goblin" (and one named "Diamond Goblin") is picked and gets a box and
 *       a tracer; an unnamed mob and a "Goblin" are not; behind a stone wall the legit box stops and Through Walls brings it
 *       back only on a cheat jar; the switch OFF draws nothing.</li>
 * </ul>
 */
final class HxChestGoblin1104Cases {

    private static final String PCFG = "powdermining.PowderMiningConfig";
    private static final String MCFG = "miningqol.MiningQolConfig";
    private static final String TC = "powdermining.TreasureChests";
    private static final String GG = "miningqol.GoldenGoblin";
    private static final String STATE = "miningqol.MiningState";
    private static final String UNCOVERED = "§6You uncovered a treasure chest!";
    private static final String TAIL = ",NoAI:1b,Silent:1b,PersistenceRequired:1b}";

    private HxChestGoblin1104Cases() {
    }

    static void register(Session s) {
        s.test("1104-chestgoblin-chest", HxChestGoblin1104Cases::chest);
        s.test("1104-chestgoblin-goblin", HxChestGoblin1104Cases::goblin);
    }

    private static int intCall(Session c, String cls, String m) {
        return c.onClient(mc -> ((Number) Mod.staticCall(cls, m)).intValue());
    }

    private static int[] feet(Session c) {
        double[] at = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        return new int[]{(int) Math.floor(at[0]), (int) Math.floor(at[1]), (int) Math.floor(at[2])};
    }

    private static int color(Session c, long ticks) {
        return c.onClient(mc -> ((Number) Mod.staticCall(TC, "countdownColor", ticks)).intValue());
    }

    // ---- 1104-chestgoblin-chest -------------------------------------------------------------------------------------

    private static void chest(Session c) {
        int[] f = feet(c);
        int bx = f[0], by = f[1], bz = f[2];
        int cx = bx + 3, cz = bz;
        Object cfg = c.onClient(mc -> Mod.cfg(PCFG));
        boolean old = c.onClient(mc -> (Boolean) Mod.call(cfg, "isTreasureChestRaw"));
        Settings s0 = new Settings(c).custom(() -> Mod.call(cfg, "setTreasureChest", true),
                () -> Mod.call(cfg, "setTreasureChest", old));
        try (Settings set = s0.with(PCFG, "TreasureChestCountdown", true).with(PCFG, "TreasureChestTracer", true)
                .with(PCFG, "TreasureChestHideOpened", true).with(PCFG, "ChestAlertSound", false)
                .with(PCFG, "ChestAlertTitle", false).with(PCFG, "TreasureChestEsp", false)) {
            // The color scale, no world needed.
            int full = color(c, 1200), half = color(c, 600), none = color(c, 0);
            c.check(((full >> 8) & 255) == 255 && ((full >> 16) & 255) < 20, "full minute is not green: " + Integer.toHexString(full));
            c.check(((half >> 8) & 255) == 255 && ((half >> 16) & 255) == 255, "half a minute is not yellow: " + Integer.toHexString(half));
            c.check(((none >> 16) & 255) == 255 && ((none >> 8) & 255) < 20, "no time left is not red: " + Integer.toHexString(none));
            c.check(c.onClient(mc -> "42s".equals(Mod.staticCall(TC, "countdownText", 840L))), "countdown text for 840 ticks");

            c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Jungle", "Purse: 0");
            c.hx().tab(List.of("Area: Crystal Hollows", "Profile: Test", "", "Powders:", " Gemstone: 1,000", ""), null, null);
            c.waitUntil("Crystal Hollows", mc -> "CRYSTAL_HOLLOWS".equals(Mod.staticCall(STATE, "island")), 100);
            c.ctx().runOnClient(mc -> {
                mc.player.setYRot(-90f); // east, toward the chest
                mc.player.setXRot(20f);
            });
            c.hx().chat(UNCOVERED);
            c.server().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:chest", cx, by, cz));
            c.waitUntil("the chest claimed", mc -> (Boolean) Mod.staticCall(TC, "tracked", cx, by, cz), 40);

            int tr0 = intCall(c, TC, "tracerFrames"), cd0 = intCall(c, TC, "countdownFrames");
            int skip0 = intCall(c, TC, "hiddenSkips");
            c.ctx().waitTicks(30);
            c.check(intCall(c, TC, "tracerFrames") > tr0, "no tracer drawn to the claimed chest");
            c.check(intCall(c, TC, "countdownFrames") > cd0, "no countdown drawn over the claimed chest");
            String text = c.onClient(mc -> (String) Mod.staticCall(TC, "lastCountdownText"));
            int shown = c.onClient(mc -> ((Number) Mod.staticCall(TC, "lastCountdownColor")).intValue());
            c.check(text != null && text.matches("(59|60|58)s"), "countdown text " + text);
            c.check(((shown >> 8) & 255) == 255 && ((shown >> 16) & 255) < 30, "fresh chest countdown not green: " + Integer.toHexString(shown));
            c.check(intCall(c, TC, "hiddenSkips") == skip0 && intCall(c, TC, "hiddenCount") == 0,
                    "the render skip fired before any click");

            // The click: the model is skipped every frame, the block stays a chest.
            c.ctx().runOnClient(mc -> Mod.staticCall(TC, "onUseBlock", new BlockPos(cx, by, cz)));
            c.check(intCall(c, TC, "hiddenCount") == 1, "the clicked chest was not marked hidden");
            int s1 = intCall(c, TC, "hiddenSkips");
            c.ctx().waitTicks(30);
            int s2 = intCall(c, TC, "hiddenSkips");
            c.note("hidden chest: render skips +" + (s2 - s1) + " in 30 ticks");
            c.check(s2 > s1, "the chest's model was still extracted for drawing after the click");
            c.check(c.onClient(mc -> mc.level.getBlockState(new BlockPos(cx, by, cz)).is(net.minecraft.world.level.block.Blocks.CHEST)),
                    "the chest block was changed on the client (ghost block)");
            c.check(c.onClient(mc -> (Boolean) Mod.staticCall(TC, "tracked", cx, by, cz)), "the click removed the tracking");

            // Setting OFF: the skip stops (the chest draws again).
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(PCFG), "setTreasureChestHideOpened", false));
            c.ctx().waitTicks(4);
            int s3 = intCall(c, TC, "hiddenSkips");
            c.ctx().waitTicks(20);
            c.check(intCall(c, TC, "hiddenSkips") == s3, "the chest stayed hidden with the setting OFF");
            c.check(c.events("container.click").isEmpty(), "Treasure Chest clicked something");
        } finally {
            c.server().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:air", cx, by, cz));
            c.ctx().runOnClient(mc -> Mod.staticCall(PCFG, "load"));
            hub(c);
        }
    }

    // ---- 1104-chestgoblin-goblin ------------------------------------------------------------------------------------

    private static int mobTagged(net.minecraft.client.Minecraft mc, String tag) {
        for (Entity e : mc.level.entitiesForRendering()) {
            // Tags are server data, so the client mob is told by its name (plain = a zombie with none).
            String n = e.hasCustomName() ? e.getCustomName().getString() : "";
            boolean zombie = e instanceof net.minecraft.world.entity.monster.zombie.Zombie;
            boolean hit = switch (tag) {
                case "k1104golden" -> n.equals("Golden Goblin");
                case "k1104diamond" -> n.equals("Diamond Goblin");
                case "k1104goblin" -> n.equals("Goblin");
                default -> zombie && n.isEmpty();
            };
            if (hit && e.isAlive()) {
                return e.getId();
            }
        }
        return -1;
    }

    private static boolean targeted(Session c, int id) {
        return c.onClient(mc -> ((List<?>) Mod.staticCall(GG, "targetIds")).contains(id));
    }

    private static void goblin(Session c) {
        double[] at = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        int bx = (int) Math.floor(at[0]), by = (int) Math.floor(at[1]), bz = (int) Math.floor(at[2]);
        Object cfg = c.onClient(mc -> Mod.cfg(MCFG));
        boolean old = c.onClient(mc -> (Boolean) Mod.call(cfg, "isGoldenGoblinRaw"));
        Settings s0 = new Settings(c).custom(() -> Mod.call(cfg, "setGoldenGoblin", true),
                () -> Mod.call(cfg, "setGoldenGoblin", old));
        String wall = String.format(Locale.ROOT, "%d %d %d %d %d %d", bx - 4, by, bz - 6, bx + 4, by + 6, bz - 6);
        try (Settings set = s0.with(MCFG, "GoldenGoblinBox", true).with(MCFG, "GoldenGoblinTracer", true)
                .with(MCFG, "GoldenGoblinEsp", false)) {
            c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Royal Mines", "Purse: 0");
            c.hx().tab(List.of("Area: Dwarven Mines", "Profile: Test", "", "Commissions:", " Goblin Slayer: 10%", ""), null, null);
            c.waitUntil("Dwarven Mines", mc -> "DWARVEN_MINES".equals(Mod.staticCall(STATE, "island")), 100);
            c.ctx().runOnClient(mc -> {
                mc.player.setYRot(180f);
                mc.player.setXRot(0f);
            });
            // Decoys: an unnamed mob and a plain "Goblin" are never picked.
            c.server().command(String.format(Locale.ROOT, "summon zombie %.2f %.2f %.2f", at[0] + 3, at[1], at[2] - 10)
                    + " {Tags:[\"k1104gb\",\"k1104plain\"]" + TAIL);
            c.server().command(String.format(Locale.ROOT, "summon zombie %.2f %.2f %.2f", at[0] - 3, at[1], at[2] - 10)
                    + " {Tags:[\"k1104gb\",\"k1104goblin\"],CustomName:\"Goblin\",CustomNameVisible:1b" + TAIL);
            // The two we want.
            c.server().command(String.format(Locale.ROOT, "summon zombie %.2f %.2f %.2f", at[0], at[1], at[2] - 12)
                    + " {Tags:[\"k1104gb\",\"k1104golden\"],CustomName:\"Golden Goblin\",CustomNameVisible:1b" + TAIL);
            c.server().command(String.format(Locale.ROOT, "summon zombie %.2f %.2f %.2f", at[0] + 6, at[1], at[2] - 12)
                    + " {Tags:[\"k1104gb\",\"k1104diamond\"],CustomName:\"Diamond Goblin\",CustomNameVisible:1b" + TAIL);
            c.waitUntil("the mobs on the client", mc -> mobTagged(mc, "k1104golden") >= 0 && mobTagged(mc, "k1104diamond") >= 0
                    && mobTagged(mc, "k1104plain") >= 0 && mobTagged(mc, "k1104goblin") >= 0, 80);
            int golden = c.onClient(mc -> mobTagged(mc, "k1104golden"));
            int diamond = c.onClient(mc -> mobTagged(mc, "k1104diamond"));
            int plain = c.onClient(mc -> mobTagged(mc, "k1104plain"));
            int goblin = c.onClient(mc -> mobTagged(mc, "k1104goblin"));
            c.waitUntil("the Golden Goblin picked", mc -> ((List<?>) Mod.staticCall(GG, "targetIds")).contains(golden), 60);
            c.waitUntil("the Diamond Goblin picked", mc -> ((List<?>) Mod.staticCall(GG, "targetIds")).contains(diamond), 60);
            c.check(!targeted(c, plain), "an unnamed mob was picked");
            c.check(!targeted(c, goblin), "a plain Goblin was picked");
            int b0 = intCall(c, GG, "boxFrames"), t0 = intCall(c, GG, "tracerFrames");
            c.ctx().waitTicks(20);
            c.check(intCall(c, GG, "boxFrames") > b0, "no box drawn on the goblins");
            c.check(intCall(c, GG, "tracerFrames") > t0, "no tracer drawn to the goblins");

            // Behind a wall: legit stops, Through Walls (cheat jar only) brings it back.
            c.server().command("fill " + wall + " minecraft:stone");
            c.waitUntil("the Golden Goblin out of sight", mc -> !((List<?>) Mod.staticCall(GG, "targetIds")).contains(golden), 60);
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(MCFG), "setGoldenGoblinEsp", true));
            c.ctx().waitTicks(12);
            boolean back = targeted(c, golden);
            c.check(back == Mod.isCheat(), "Through Walls picks the walled goblin: " + back + " on a " + (Mod.isCheat() ? "cheat" : "legit") + " jar");
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(MCFG), "setGoldenGoblinEsp", false));
            c.server().command("fill " + wall + " minecraft:air");

            // The switch OFF: nothing drawn.
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(MCFG), "setGoldenGoblin", false));
            c.ctx().waitTicks(8);
            b0 = intCall(c, GG, "boxFrames");
            t0 = intCall(c, GG, "tracerFrames");
            c.ctx().waitTicks(20);
            c.check(intCall(c, GG, "boxFrames") == b0 && intCall(c, GG, "tracerFrames") == t0, "drew with the switch OFF");
            c.check(c.events("container.click").isEmpty(), "Golden Goblin clicked something");
        } finally {
            c.server().command("kill @e[tag=k1104gb]");
            c.server().command("fill " + wall + " minecraft:air");
            c.ctx().runOnClient(mc -> Mod.staticCall(MCFG, "load"));
            hub(c);
        }
    }
}

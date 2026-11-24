package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod beeheemoth-foragingtab-1123 (killer560, 2026-11-23: Beeheemoth highlight + ESP, hide the tree gift messages).
 * <ul>
 *   <li>1123-foraging-beeheemoth: a NoAI mob named "Beeheemoth" is picked and boxed only on Galatea with the switch ON;
 *       a "Bee" and an unnamed mob never; off the island and with the switch OFF nothing is picked or drawn; behind a
 *       wall the legit box stops and ESP brings it back on a cheat jar only; the color follows the theme until set.</li>
 *   <li>1123-foraging-treegift: the real block (separator, TREE GIFT, blank, You helped cut, rewards gained, closing
 *       separator) leaves the chat window while an unrelated message and a separator pair around another message stay;
 *       Keep Rare Drops keeps BONUS GIFT and its item (with the closing separator), OFF hides them too; OFF switch hides
 *       nothing.</li>
 * </ul>
 */
final class HxForaging1123Cases {

    private static final String BH = "foraging.BeeheemothHighlight";
    private static final String TG = "foraging.TreeGiftHider";
    private static final String TAIL = ",NoAI:1b,Silent:1b,PersistenceRequired:1b}";
    private static final String SEP = "▬".repeat(64);

    private HxForaging1123Cases() {
    }

    static void register(Session s) {
        s.test("1123-foraging-beeheemoth", HxForaging1123Cases::beeheemoth);
        s.test("1123-foraging-treegift", HxForaging1123Cases::treeGift);
    }

    private static int intCall(Session c, String m) {
        return c.onClient(mc -> ((Number) Mod.staticCall(BH, m)).intValue());
    }

    private static boolean targeted(Session c, int id) {
        return c.onClient(mc -> ((List<?>) Mod.staticCall(BH, "targetIds")).contains(id));
    }

    private static void set(Session c, String cls, String key, Object v) {
        c.ctx().runOnClient(mc -> Mod.call(Mod.field(cls, "CFG"), "set", key, v));
    }

    private static int mobNamed(net.minecraft.client.Minecraft mc, String name) {
        for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
            String n = e.hasCustomName() ? e.getCustomName().getString() : "";
            boolean zombie = e instanceof net.minecraft.world.entity.monster.zombie.Zombie;
            if (zombie && e.isAlive() && n.equals(name)) {
                return e.getId();
            }
        }
        return -1;
    }

    private static void beeheemoth(Session c) {
        double[] at = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        int bx = (int) Math.floor(at[0]), by = (int) Math.floor(at[1]), bz = (int) Math.floor(at[2]);
        String wall = String.format(Locale.ROOT, "%d %d %d %d %d %d", bx - 4, by, bz - 6, bx + 4, by + 6, bz - 6);
        boolean wasOn = c.onClient(mc -> (Boolean) Mod.call(Mod.field(BH, "CFG"), "isEnabledRaw"));
        try {
            c.ctx().runOnClient(mc -> {
                Mod.call(Mod.field(BH, "CFG"), "setEnabled", true);
                mc.player.setYRot(180f);
                mc.player.setXRot(0f);
            });
            set(c, BH, "box", true);
            set(c, BH, "esp", false);
            set(c, BH, "color", 0xFFCC6600);
            c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Torrhus Canyon", "Purse: 0");
            // Off the island first.
            c.hx().tab(List.of("Area: Dwarven Mines", "Profile: Test", ""), null, null);
            c.waitUntil("Dwarven Mines", mc -> "DWARVEN_MINES".equals(Mod.staticCall("pathfinding.IslandDetector", "graphIsland")), 100);
            c.server().command(String.format(Locale.ROOT, "summon zombie %.2f %.2f %.2f", at[0], at[1], at[2] - 12)
                    + " {Tags:[\"k1123bee\",\"k1123big\"],CustomName:\"Beeheemoth\",CustomNameVisible:1b" + TAIL);
            c.server().command(String.format(Locale.ROOT, "summon zombie %.2f %.2f %.2f", at[0] + 4, at[1], at[2] - 12)
                    + " {Tags:[\"k1123bee\"],CustomName:\"Bee\",CustomNameVisible:1b" + TAIL);
            c.server().command(String.format(Locale.ROOT, "summon zombie %.2f %.2f %.2f", at[0] - 4, at[1], at[2] - 12)
                    + " {Tags:[\"k1123bee\"]" + TAIL);
            c.waitUntil("the mobs on the client", mc -> mobNamed(mc, "Beeheemoth") >= 0 && mobNamed(mc, "Bee") >= 0, 80);
            int big = c.onClient(mc -> mobNamed(mc, "Beeheemoth"));
            int bee = c.onClient(mc -> mobNamed(mc, "Bee"));
            int plain = c.onClient(mc -> mobNamed(mc, ""));
            int f0 = intCall(c, "boxFrames");
            c.ctx().waitTicks(24);
            c.check(!targeted(c, big), "off the island the Beeheemoth was picked");
            c.check(intCall(c, "boxFrames") == f0, "off the island a box was drawn");
            // On Galatea.
            c.hx().tab(List.of("Area: Galatea", "Profile: Test", ""), null, null);
            c.waitUntil("Galatea", mc -> "GALATEA".equals(Mod.staticCall("pathfinding.IslandDetector", "graphIsland")), 100);
            c.waitUntil("the Beeheemoth picked", mc -> ((List<?>) Mod.staticCall(BH, "targetIds")).contains(big), 80);
            c.check(!targeted(c, bee) && !targeted(c, plain), "a Bee or an unnamed mob was picked");
            int b0 = intCall(c, "boxFrames");
            c.ctx().waitTicks(20);
            c.check(intCall(c, "boxFrames") > b0, "no box drawn on the Beeheemoth");
            int theme = intCall(c, "drawnArgb");
            c.check(theme != 0, "the box color was never recorded");
            set(c, BH, "color", 0xFF336699);
            c.ctx().waitTicks(8);
            c.check(intCall(c, "drawnArgb") == 0xFF336699, "custom color not drawn: " + Integer.toHexString(intCall(c, "drawnArgb")));
            set(c, BH, "color", 0xFFCC6600);
            // Behind a wall: legit stops, ESP (cheat jar only) brings it back.
            c.server().command("fill " + wall + " minecraft:stone");
            c.waitUntil("the Beeheemoth out of sight", mc -> !((List<?>) Mod.staticCall(BH, "targetIds")).contains(big), 80);
            set(c, BH, "esp", true);
            c.ctx().waitTicks(12);
            boolean back = targeted(c, big);
            c.check(back == Mod.isCheat(), "ESP picks the walled Beeheemoth: " + back + " on a " + (Mod.isCheat() ? "cheat" : "legit") + " jar");
            set(c, BH, "esp", false);
            c.server().command("fill " + wall + " minecraft:air");
            c.ctx().waitTicks(12);
            // Highlight toggle OFF draws nothing while it is still tracked; switch OFF picks nothing.
            set(c, BH, "box", false);
            c.ctx().waitTicks(6);
            int g0 = intCall(c, "boxFrames");
            c.ctx().waitTicks(16);
            c.check(intCall(c, "boxFrames") == g0, "drew with Highlight OFF");
            set(c, BH, "box", true);
            c.ctx().runOnClient(mc -> Mod.call(Mod.field(BH, "CFG"), "setEnabled", false));
            c.ctx().waitTicks(8);
            int h0 = intCall(c, "boxFrames");
            c.ctx().waitTicks(16);
            c.check(intCall(c, "boxFrames") == h0 && !targeted(c, big), "drew or picked with the switch OFF");
        } finally {
            c.server().command("kill @e[tag=k1123bee]");
            c.server().command("fill " + wall + " minecraft:air");
            c.ctx().runOnClient(mc -> {
                Mod.call(Mod.field(BH, "CFG"), "setEnabled", wasOn);
                Mod.call(Mod.field(BH, "CFG"), "load");
            });
            hub(c);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> lines(Session c) {
        List<String> raw = c.onClient(mc -> (List<String>) Mod.staticCall("chattidy.ChatTidy", "testChatLines", 100));
        return raw.stream().map(l -> l.replaceAll("§.", "")).toList();
    }

    /** The chat lines newer than the newest one containing {@code marker}, newest first. */
    private static List<String> since(Session c, String marker) {
        List<String> all = lines(c);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).contains(marker)) {
                return all.subList(0, i);
            }
        }
        return all;
    }

    private static long seps(List<String> l) {
        return l.stream().filter(x -> x.trim().equals(SEP)).count();
    }

    private static boolean has(List<String> l, String part) {
        return l.stream().anyMatch(x -> x.contains(part));
    }

    private static void say(Session c, String legacy) {
        c.hx().chat(legacy);
        c.ctx().waitTicks(4);
    }

    private static void block(Session c, String... middle) {
        say(c, "§a" + SEP);
        say(c, "§a§l                                 TREE GIFT");
        say(c, " ");
        say(c, "§7                 You helped cut §a100%§7 of the Fig Tree.");
        say(c, "§f                       §e+5 rewards gained! §8(hover)");
        for (String m : middle) {
            say(c, m);
        }
        say(c, "§a" + SEP);
    }

    private static void treeGift(Session c) {
        boolean wasOn = c.onClient(mc -> (Boolean) Mod.call(Mod.field(TG, "CFG"), "isEnabledRaw"));
        try {
            c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Torrhus Canyon", "Purse: 0");
            c.ctx().runOnClient(mc -> Mod.call(Mod.field(TG, "CFG"), "setEnabled", false));
            set(c, TG, "rare", true);
            // Switch OFF: the block shows in full.
            say(c, "Marker off");
            block(c);
            List<String> n = since(c, "Marker off");
            c.check(has(n, "TREE GIFT") && has(n, "You helped cut") && seps(n) == 2, "OFF: the block was changed: " + n);
            c.ctx().runOnClient(mc -> Mod.call(Mod.field(TG, "CFG"), "setEnabled", true));

            // ON: a plain block disappears with both separators; the line before it and a pair around another message stay.
            say(c, "Unrelated line");
            block(c);
            n = since(c, "Unrelated line");
            c.check(n.isEmpty(), "ON: something of the block is still in chat: " + n);
            c.check(has(lines(c), "Unrelated line"), "ON: the unrelated line before the block was removed: " + lines(c));
            say(c, "§a" + SEP);
            say(c, "Some other box message");
            say(c, "§a" + SEP);
            n = since(c, "Unrelated line");
            c.check(seps(n) == 2 && has(n, "Some other box message"), "ON: another message's separators were eaten: " + n);

            // Rare drops kept.
            say(c, "Marker rare");
            block(c, " ", "§d§l                                BONUS GIFT", "§a                         Stretching Sticks x2 (10%)");
            n = since(c, "Marker rare");
            c.check(has(n, "BONUS GIFT") && has(n, "Stretching Sticks x2"), "rare ON: the bonus gift was hidden: " + n);
            c.check(!has(n, "TREE GIFT") && !has(n, "You helped cut"), "rare ON: the routine lines stayed: " + n);
            c.check(seps(n) == 1, "rare ON: expected only the closing separator: " + n);
            say(c, "Marker fell");
            block(c, "§6                      A Firefox fell from the Tree!");
            c.check(has(since(c, "Marker fell"), "A Firefox fell from the Tree!"), "rare ON: the mob line was hidden: " + lines(c));

            // Keep Rare Drops OFF hides those too.
            set(c, TG, "rare", false);
            say(c, "Marker rare off");
            block(c, " ", "§d§l                                BONUS GIFT", "§a                         Stretching Sticks x9 (10%)");
            block(c, "§6                      A Wolfie fell from the Tree!");
            n = since(c, "Marker rare off");
            c.check(n.isEmpty(), "rare OFF: part of the block is still in chat: " + n);
            // A player's copy of the header is never a block start.
            say(c, "§7[VIP] Bob§f: TREE GIFT");
            c.check(has(lines(c), "[VIP] Bob: TREE GIFT"), "a player's line was hidden");
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.call(Mod.field(TG, "CFG"), "setEnabled", wasOn);
                Mod.call(Mod.field(TG, "CFG"), "load");
            });
            hub(c);
        }
    }
}

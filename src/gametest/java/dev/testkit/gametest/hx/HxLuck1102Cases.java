package dev.testkit.gametest.hx;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * How Lucky Are You coverage (mod branch luck2-1101, to-do Doc #59/#70 "literally every drop in the game", "killed with
 * looting or killed with magic find", "the smallest things like a 1/10 drop"). Every case drives the mod through what
 * the server sends - a real mob dying after a real attack-key hit, system chat lines, a real chest menu clicked with the
 * real cursor, the real RNG Meter menu - and asserts the tracker's counters moved by the exact expected amount, with a
 * control in the same case that must NOT move them.
 * <ul>
 *   <li>1102-luck-mobkill: a "[Lv73] Zealot" nametag mob hit with a Looting IV sword at 100% Magic Find counts one
 *       mob:Zealot attempt and adds Enchanted Ender Pearl at 0.02 x 1.6 x 2; a Water Hydra (sea creature) killed with no
 *       Looting adds Aquamarine Dye at 2e-5 x 2; a Zealot killed by the server without his hit counts nothing.</li>
 *   <li>1102-luck-slayerlevel: Hypixel's "Zombie Slayer LVL 2" line after a Revenant V kill leaves Warden Heart (needs
 *       LVL 7) out of the expectation and keeps Foul Flesh in; the same line in party chat sets nothing.</li>
 *   <li>1102-luck-meter: the Revenant RNG Meter menu (Selected Drop Warden Heart, 50%) makes the next kill add Warden
 *       Heart at 2x its chance, and the kill adds 1,500 meter XP.</li>
 *   <li>1102-luck-kuudra: a click on an Infernal Paid Chest's open button (real cursor) counts one kuudra:Infernal attempt
 *       with Wheel of Fate and Fatal Tempo as drops; a second chest answered "You cannot afford this!" counts nothing.</li>
 *   <li>1102-luck-nucleus: the CRYSTAL NUCLEUS LOOT BUNDLE message counts one bundle with Divan's Alloy and Jade Dye; the
 *       same header typed by a player starts nothing.</li>
 *   <li>1102-luck-experiment: a Superpairs (Metaphysical) claim with Looting V counts one experiment:Metaphysical attempt
 *       (1/3,700 per ultra-rare book) and the book as a drop.</li>
 *   <li>1102-luck-unknownfloor: a Croesus claim logged with floor Unknown holding a Dark Claymore counts as M7 Bedrock; one
 *       with only a book counts under chest:Unknown:Bedrock with no expectation.</li>
 * </ul>
 */
final class HxLuck1102Cases {

    private static final String LS = "luck.LuckStore";
    private static final String LD = "luck.LuckDetector";
    private static final String LK = "luck.LuckKills";
    private static final String CFG = "luck.LuckConfig";

    private HxLuck1102Cases() {
    }

    static void register(Session s) {
        s.test("1102-luck-mobkill", HxLuck1102Cases::mobKill);
        s.test("1102-luck-slayerlevel", HxLuck1102Cases::slayerLevel);
        s.test("1102-luck-meter", HxLuck1102Cases::meter);
        s.test("1102-luck-kuudra", HxLuck1102Cases::kuudra);
        s.test("1102-luck-nucleus", HxLuck1102Cases::nucleus);
        s.test("1102-luck-experiment", HxLuck1102Cases::experiment);
        s.test("1102-luck-unknownfloor", HxLuck1102Cases::unknownFloor);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static Object cfg() {
        return Mod.staticCall(CFG, "getInstance");
    }

    private static Object row(Session c, String key) {
        return c.onClient(mc -> {
            @SuppressWarnings("unchecked")
            List<Object> rows = (List<Object>) Mod.staticCall(LS, "rows", null,
                    Mod.enumValue(CFG + "$ChestOdds", "BASE"), false);
            for (Object r : rows) {
                if (key.equals(Mod.call(r, "key"))) {
                    return r;
                }
            }
            return null;
        });
    }

    private static double expected(Session c, String key) {
        Object r = row(c, key);
        return r == null ? 0 : ((Number) Mod.call(r, "expected")).doubleValue();
    }

    private static double base(Session c, String key) {
        Object r = row(c, key);
        return r == null ? 0 : ((Number) Mod.call(r, "expectedBase")).doubleValue();
    }

    private static long got(Session c, String key) {
        Object r = row(c, key);
        return r == null ? 0 : ((Number) Mod.call(r, "got")).longValue();
    }

    private static long attempts(Session c, String activity) {
        return c.onClient(mc -> {
            @SuppressWarnings("unchecked")
            Map<String, Long> m = (Map<String, Long>) Mod.staticCall(LS, "attempts", (Object) null);
            return m.getOrDefault(activity, 0L);
        });
    }

    private static String decision(Session c) {
        return c.onClient(mc -> (String) Mod.field(LD, "lastDecision"));
    }

    private static void skyblock(Session c, String... extra) {
        List<String> lines = new ArrayList<>(List.of("§710/31/26 §8m12AB", "", " §7⏣ §bHub", ""));
        lines.addAll(List.of(extra));
        lines.add("§ewww.hypixel.net");
        c.hx().sidebar("§e§lSKYBLOCK", lines.toArray(new String[0]));
    }

    /** Runs {@code body} with Magic Find set to Manual {@code mf}, restoring the settings after. */
    private static void withMf(Session c, int mf, Runnable body) {
        Object cfg = c.onClient(mc -> cfg());
        Object mode0 = c.onClient(mc -> Mod.call(cfg, "getMagicFindMode"));
        int mf0 = c.onClient(mc -> (Integer) Mod.call(cfg, "getManualMagicFind"));
        try {
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setMagicFindMode", Mod.enumValue(CFG + "$MagicFindMode", "MANUAL"));
                Mod.call(cfg, "setManualMagicFind", mf);
                Mod.staticCall(LD, "resetForTest");
            });
            body.run();
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setMagicFindMode", mode0);
                Mod.call(cfg, "setManualMagicFind", mf0);
            });
        }
    }

    // ---- 1102-luck-mobkill ---------------------------------------------------------------------------------------

    /** A one-health, no-AI husk named like a Hypixel mob, 2.2 blocks in front of him; returns its client entity id. */
    private static int summon(Session c, String name, String tag) {
        c.server().command("kill @e[type=minecraft:husk]");
        c.server().command("execute as @p at @s run summon minecraft:husk ^ ^ ^2.2 {NoAI:1b,Silent:1b,"
                + "PersistenceRequired:1b,CustomNameVisible:1b,CustomName:\"" + name + "\",Health:1.0f,"
                + "attributes:[{id:\"minecraft:max_health\",base:1.0d}],Tags:[\"" + tag + "\"]}");
        String plain = name.substring(0, Math.min(12, name.length()));
        c.waitUntil("the " + name + " husk reached the client", mc -> find(mc, plain) != null, 60);
        return c.onClient(mc -> find(mc, plain).getId());
    }

    private static Entity find(Minecraft mc, String namePart) {
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.hasCustomName() && e.getCustomName().getString().contains(namePart) && e.isAlive()
                    && e.distanceTo(mc.player) < 6) {
                return e;
            }
        }
        return null;
    }

    /** Turns him to the mob and presses the real attack key once. */
    private static void hit(Session c, int id) {
        c.ctx().runOnClient(mc -> {
            Entity e = mc.level.getEntity(id);
            Vec3 eye = mc.player.getEyePosition();
            Vec3 at = e.getBoundingBox().getCenter();
            double dx = at.x - eye.x;
            double dy = at.y - eye.y;
            double dz = at.z - eye.z;
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            mc.player.setYRot(yaw);
            mc.player.setXRot(Math.max(-90, Math.min(90, pitch)));
        });
        c.ctx().waitTicks(6);
        c.ctx().getInput().pressKey(o -> o.keyAttack);
    }

    private static void mobKill(Session c) {
        skyblock(c);
        withMf(c, 100, () -> {
            String zealot = "mob:Zealot";
            String pearl = zealot + "|Enchanted Ender Pearl";
            String hydra = "mob:Water Hydra";
            String dye = hydra + "|Aquamarine Dye";
            long z0 = attempts(c, zealot);
            long h0 = attempts(c, hydra);
            double pe0 = expected(c, pearl);
            double pb0 = base(c, pearl);
            double de0 = expected(c, dye);
            c.ctx().runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));

            // Control: a Zealot the server kills, never hit by him, counts nothing (Mob Kills: Hit By Me).
            summon(c, "[Lv73] Zealot 13,000/13,000❤", "luckctl");
            c.server().command("kill @e[tag=luckctl]");
            c.ctx().waitTicks(40);
            c.check(attempts(c, zealot) == z0, "a Zealot killed without his hit counted a kill");

            // 1. Zealot, Looting IV sword, 100% Magic Find.
            c.hx().give(0, "minecraft:iron_sword[custom_data={enchantments:{looting:4}}]");
            int k0 = c.onClient(mc -> (Integer) Mod.field(LK, "killsCounted"));
            int zid = summon(c, "[Lv73] Zealot 13,000/13,000❤", "luckmob");
            hit(c, zid);
            c.waitUntil("the Zealot kill was counted", mc -> attempts(c, zealot) == z0 + 1, 80);
            c.note("Zealot: " + c.onClient(mc -> Mod.field(LK, "lastKill")) + "; " + decision(c));
            c.check((Integer) c.onClient(mc -> Mod.field(LK, "killsCounted")) == k0 + 1, "killsCounted did not move once");
            double pe = expected(c, pearl) - pe0;
            double pb = base(c, pearl) - pb0;
            c.note("Enchanted Ender Pearl added " + pe + " (base " + pb + "), want 0.02 x 1.6 x 2 = 0.064");
            c.check(Math.abs(pb - 0.02) < 1e-9, "base expectation added " + pb + ", want 0.02");
            c.check(Math.abs(pe - 0.064) < 1e-9, "Looting IV + 100% MF added " + pe + ", want 0.064");

            // 2. Water Hydra (a sea creature), no Looting: Magic Find only.
            c.hx().give(0, "minecraft:iron_sword");
            int hid = summon(c, "[Lv100] Water Hydra 500k/500k❤", "luckmob");
            hit(c, hid);
            c.waitUntil("the Water Hydra kill was counted", mc -> attempts(c, hydra) == h0 + 1, 80);
            double de = expected(c, dye) - de0;
            c.note("Aquamarine Dye added " + de + ", want 2e-5 x 2 = 4e-5; " + decision(c));
            c.check(Math.abs(de - 4e-5) < 1e-12, "no-Looting 100% MF Hydra added " + de + ", want 4e-5");
            c.check(attempts(c, zealot) == z0 + 1, "the Hydra kill also counted a Zealot");

            // A drop line right after the Hydra kill is the Hydra's.
            c.hx().chat("§6§lRARE DROP! §r§5Aquamarine Dye §r§b(+100% §r§b✯ Magic Find§r§b)");
            c.waitUntil("the dye drop line was counted under the Hydra", mc -> got(c, dye) >= 1, 40);
            c.hx().give(0, "minecraft:air");
        });
    }

    // ---- 1102-luck-slayerlevel ----------------------------------------------------------------------------------

    /** One boss: the quest on the sidebar, both completion lines, then {@code levelLine}. Waits out the 5 s in which the
     *  mod reads a second completion line as the same boss. */
    private static void bossKill(Session c, String levelLine) {
        c.ctx().waitTicks(110);
        skyblock(c, "§cSlayer Quest", "§4Revenant Horror §cV", "§eSlay the boss!", "");
        c.ctx().waitTicks(30);
        c.hx().chat("  §r§a§lSLAYER QUEST COMPLETE!");
        c.hx().chat("§a§lNICE! SLAYER BOSS SLAIN!");
        if (levelLine != null) {
            c.hx().chat(levelLine);
        }
    }

    private static void slayerLevel(Session c) {
        withMf(c, 0, () -> {
            String act = "slayer:revenant:5";
            String warden = act + "|Warden Heart";
            String flesh = act + "|Foul Flesh";
            try {
                // Forged first: a level line in party chat sets nothing.
                c.ctx().runOnClient(mc -> Mod.staticCall(LS, "setSlayerLevel", "revenant", 9));
                c.hx().chat("§9Party §8> §b[MVP§c+§b] HxMateA§f: Zombie Slayer LVL 2 - Next LVL in 1,234 XP!");
                c.ctx().waitTicks(10);
                int forged = c.onClient(mc -> (Integer) Mod.staticCall(LS, "slayerLevel", "revenant"));
                c.check(forged == 9, "a party chat level line set the slayer level to " + forged);

                long a0 = attempts(c, act);
                double w0 = base(c, warden);
                double f0 = base(c, flesh);
                bossKill(c, "   §5§l» §7Zombie Slayer LVL 2 §5- §7Next LVL in §d1,234 XP§7!");
                c.waitUntil("the kill counted at once on the level line", mc -> attempts(c, act) == a0 + 1, 30);
                int lvl = c.onClient(mc -> (Integer) Mod.staticCall(LS, "slayerLevel", "revenant"));
                double dw = base(c, warden) - w0;
                double df = base(c, flesh) - f0;
                c.note("level " + lvl + "; Warden added " + dw + ", Foul Flesh added " + df + "; " + decision(c));
                c.check(lvl == 2, "the level line set level " + lvl + ", want 2");
                c.check(dw == 0, "Warden Heart (needs LVL 7) was expected at slayer level 2: +" + dw);
                c.check(Math.abs(df - 0.1360821936) < 1e-9, "Foul Flesh (no level) added " + df);

                // Maxed: everything back in.
                bossKill(c, "  Zombie Slayer LVL 9 - LVL MAXED OUT!");
                c.waitUntil("the second kill counted", mc -> attempts(c, act) == a0 + 2, 30);
                double dw2 = base(c, warden) - w0;
                c.check(Math.abs(dw2 - 2.0 / 14697) < 1e-9, "at LVL 9 Warden Heart added " + dw2);

                // No level line at all: counted after 3 s with the last level seen.
                bossKill(c, null);
                c.ctx().waitTicks(20);
                c.check(attempts(c, act) == a0 + 2, "a kill with no level line was counted before 3 s");
                c.waitUntil("a kill with no level line was counted after 3 s", mc -> attempts(c, act) == a0 + 3, 80);
            } finally {
                skyblock(c);
                c.ctx().runOnClient(mc -> Mod.staticCall(LS, "setSlayerLevel", "revenant", 9));
            }
        });
    }

    // ---- 1102-luck-meter ----------------------------------------------------------------------------------------

    private static void meter(Session c) {
        withMf(c, 0, () -> {
            String act = "slayer:revenant:5";
            String warden = act + "|Warden Heart";
            try {
                c.ctx().runOnClient(mc -> Mod.staticCall(LS, "setMeter", "slayer:revenant", null));
                // The real RNG Meter menu, read by the RNG Meter overlay: Warden Heart selected at 50%.
                c.hx().menu("Revenant Horror RNG Meter", 6, Map.of(4,
                        "minecraft:rotten_flesh[custom_name={text:\"Revenant Horror\",italic:false},lore=["
                                + "\"Selected Drop\",\"Warden Heart\",\"\",\"Progress: 50.0%\",\"1,000,000/2,000,000\"]]"),
                        null);
                c.waitUntil("the meter was read from the menu", mc ->
                        c.onClient(m -> Mod.staticCall(LS, "meter", "slayer:revenant")) != null, 100);
                Object m0 = c.onClient(mc -> Mod.staticCall(LS, "meter", "slayer:revenant"));
                c.note("meter read: " + m0);
                c.check("Warden Heart".equals(Mod.call(m0, "item")) && ((Number) Mod.call(m0, "xp")).longValue() == 1_000_000,
                        "meter read as " + m0);
                c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
                c.ctx().waitTicks(5);

                long a0 = attempts(c, act);
                double e0 = expected(c, warden);
                double b0 = base(c, warden);
                bossKill(c, "  Zombie Slayer LVL 9 - LVL MAXED OUT!");
                c.waitUntil("the kill counted", mc -> attempts(c, act) == a0 + 1, 30);
                double de = expected(c, warden) - e0;
                double db = base(c, warden) - b0;
                c.note("Warden added " + de + " with the meter at 50% (base " + db + "); " + decision(c));
                c.check(Math.abs(db - 2.0 / 14697) < 1e-9, "base added " + db);
                c.check(Math.abs(de - 2 * 2.0 / 14697) < 1e-9, "a 50% meter added " + de + ", want 2x the chance");
                Object m1 = c.onClient(mc -> Mod.staticCall(LS, "meter", "slayer:revenant"));
                c.check(((Number) Mod.call(m1, "xp")).longValue() == 1_001_500, "a Tier V kill left meter XP at "
                        + Mod.call(m1, "xp") + ", want 1,001,500");

                // The meter's drop comes: its price comes off the stored XP.
                c.hx().chat("§5§lPRAY TO RNGESUS DROP!  §r§7(§r§f§r§6Warden Heart§r§7)");
                c.waitUntil("the meter was spent", mc -> ((Number) Mod.call(c.onClient(m -> Mod.staticCall(LS, "meter",
                        "slayer:revenant")), "xp")).longValue() == 0, 40);
            } finally {
                skyblock(c);
                c.ctx().runOnClient(mc -> {
                    McCompat.setScreen(mc, null);
                    Mod.staticCall(LS, "setMeter", "slayer:revenant", null);
                });
            }
        });
    }

    // ---- 1102-luck-kuudra ---------------------------------------------------------------------------------------

    private static String paidChest(String key) {
        return "minecraft:chest[custom_name={text:\"Open Reward Chest\",italic:false},lore=[\"Contents\",\"Wheel of Fate\","
                + "\"Enchanted Book (Fatal Tempo I)\",\"Crimson Essence x1,000\",\"\",\"Cost\",\"" + key + "\",\"\","
                + "\"Click to open!\"]]";
    }

    /** Puts the real cursor on chest slot {@code slot} of the open 6-row menu and clicks it. */
    private static void clickSlot(Session c, int slot) {
        double[] w = c.onClient(mc -> {
            var screen = McCompat.screen(mc);
            int left = (screen.width - 176) / 2;
            int top = (screen.height - 222) / 2;
            double gx = left + 8 + (slot % 9) * 18 + 8;
            double gy = top + 18 + (slot / 9) * 18 + 8;
            return new double[]{gx * mc.getWindow().getScreenWidth() / (double) mc.getWindow().getGuiScaledWidth(),
                    gy * mc.getWindow().getScreenHeight() / (double) mc.getWindow().getGuiScaledHeight()};
        });
        c.ctx().getInput().setCursorPos(w[0], w[1]);
        c.ctx().waitTicks(3);
        c.ctx().getInput().pressMouse(dev.testkit.compat.McKeys.mouse(0));
    }

    private static void kuudra(Session c) {
        skyblock(c);
        c.ctx().runOnClient(mc -> Mod.staticCall(LD, "resetForTest"));
        String act = "kuudra:Infernal";
        long a0 = attempts(c, act);
        long w0 = got(c, act + "|Wheel of Fate");
        long t0 = got(c, act + "|Enchanted Book (Fatal Tempo I)");
        try {
            c.hx().menu("Paid Chest", 6, Map.of(31, paidChest("Infernal Kuudra Key")), null);
            c.waitUntil("the Paid Chest menu opened", mc -> McCompat.screen(mc) != null
                    && McCompat.screen(mc).getTitle().getString().equals("Paid Chest"), 60);
            c.ctx().waitTicks(5);
            clickSlot(c, 31);
            c.waitUntil("the Infernal claim was counted", mc -> attempts(c, act) == a0 + 1, 80);
            c.note("claim: " + c.onClient(mc -> Mod.field("luck.LuckKuudra", "lastDecision")) + "; " + decision(c));
            c.check(got(c, act + "|Wheel of Fate") == w0 + 1, "Wheel of Fate was not counted as a drop");
            c.check(got(c, act + "|Enchanted Book (Fatal Tempo I)") == t0 + 1, "Fatal Tempo was not counted");
            double exp = expected(c, act + "|Wheel of Fate");
            c.check(exp > 0, "no Wheel of Fate expectation after an Infernal claim");
            // A second click in the same menu is the same chest.
            clickSlot(c, 31);
            c.ctx().waitTicks(40);
            c.check(attempts(c, act) == a0 + 1, "a second click on the same Paid Chest counted again");
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));

            // Rolled back: "You cannot afford this!".
            c.hx().menu("Paid Chest", 6, Map.of(31, paidChest("Infernal Kuudra Key")), null);
            c.waitUntil("the second Paid Chest menu opened", mc -> McCompat.screen(mc) != null, 60);
            c.ctx().waitTicks(5);
            clickSlot(c, 31);
            c.ctx().waitTicks(4);
            c.hx().chat("§cYou cannot afford this!");
            c.ctx().waitTicks(50);
            c.note("rollback: " + c.onClient(mc -> Mod.field("luck.LuckKuudra", "lastDecision")));
            c.check(attempts(c, act) == a0 + 1, "a Paid Chest Hypixel refused counted a claim");
        } finally {
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
        }
    }

    // ---- 1102-luck-nucleus --------------------------------------------------------------------------------------

    private static void nucleus(Session c) {
        skyblock(c);
        c.ctx().runOnClient(mc -> Mod.staticCall(LD, "resetForTest"));
        long a0 = attempts(c, "nucleus");
        long d0 = got(c, "nucleus|Divan's Alloy");
        long j0 = got(c, "nucleus|Jade Dye");
        String bar = "§3§l" + "▬".repeat(64);
        // Forged: a player's line holding the header starts nothing.
        c.hx().chat("§7[VIP] HxBob§f: CRYSTAL NUCLEUS LOOT BUNDLE");
        c.hx().chat("    §5Divan's Alloy §8x1");
        c.hx().chat(bar);
        c.ctx().waitTicks(50);
        c.check(attempts(c, "nucleus") == a0, "a player's chat line counted a Nucleus bundle");
        // Real.
        c.hx().chat(bar);
        c.hx().chat("  §r§5§lCRYSTAL NUCLEUS LOOT BUNDLE");
        c.hx().chat("    §5Divan's Alloy §8x1");
        c.hx().chat("    §aJade Dye");
        c.hx().chat("    §9Fine Ruby Gemstone §8x1");
        c.hx().chat("    §aMithril Powder §8x2,500");
        c.hx().chat(bar);
        // The closing bar repeats the opening one inside ChatObserver's 250 ms window and is dropped: the bundle ends
        // on its 1.5 s timeout then.
        c.waitUntil("the bundle was counted", mc -> attempts(c, "nucleus") == a0 + 1, 80);
        c.note(decision(c));
        c.check(got(c, "nucleus|Divan's Alloy") == d0 + 1, "Divan's Alloy was not counted");
        c.check(got(c, "nucleus|Jade Dye") == j0 + 1, "Jade Dye was not counted");
        double e = expected(c, "nucleus|Divan's Alloy");
        c.check(e > 0, "no Divan's Alloy expectation after a bundle");
    }

    // ---- 1102-luck-experiment -----------------------------------------------------------------------------------

    private static void experiment(Session c) {
        skyblock(c);
        c.ctx().runOnClient(mc -> Mod.staticCall(LD, "resetForTest"));
        String act = "experiment:Metaphysical";
        String book = act + "|Enchanted Book (Looting V)";
        long a0 = attempts(c, act);
        long g0 = got(c, book);
        double e0 = base(c, book);
        try {
            c.hx().menu("Superpairs (Metaphysical)", 6, Map.of(), null);
            c.waitUntil("the Superpairs menu opened", mc -> McCompat.screen(mc) != null
                    && McCompat.screen(mc).getTitle().getString().startsWith("Superpairs"), 60);
            c.ctx().waitTicks(10);
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
            c.hx().chat("§aYou claimed the Superpairs rewards!");
            c.hx().chat("  +Looting V");
            c.hx().chat("  +2x Titanic Experience Bottle");
            c.waitUntil("the Superpairs claim was counted", mc -> attempts(c, act) == a0 + 1, 120);
            c.note(decision(c));
            c.check(got(c, book) == g0 + 1, "Looting V was not counted as an ultra-rare drop");
            double de = base(c, book) - e0;
            c.check(Math.abs(de - 0.01 / 37) < 1e-9, "one Metaphysical experiment added " + de + ", want 1/3,700");
        } finally {
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
        }
    }

    // ---- 1102-luck-unknownfloor ---------------------------------------------------------------------------------

    private static void unknownFloor(Session c) {
        long m0 = attempts(c, "chest:M7:Bedrock");
        long u0 = attempts(c, "chest:Unknown:Bedrock");
        long cl0 = got(c, "chest:M7:Bedrock|Dark Claymore");
        c.ctx().runOnClient(mc -> {
            try {
                Class<?> priced = Mod.cls("croesus.DungeonChestValuer$PricedItem");
                Class<?> value = Mod.cls("croesus.DungeonChestValuer$ChestValue");
                Object clay = priced.getConstructors()[0].newInstance("Dark Claymore", "DARK_CLAYMORE", 1, 100_000_000L, false);
                Object bk = priced.getConstructors()[0].newInstance("Enchanted Book (Ultimate Wise II)", "", 1, 0L, false);
                Object bedrock = Mod.enumValue("croesus.DungeonChestValuer$ChestType", "BEDROCK");
                Object v1 = value.getConstructors()[0].newInstance(bedrock, 0, 4_000_000L, List.of(clay, bk),
                        100_000_000L, 96_000_000L, false, 0, false);
                Object v2 = value.getConstructors()[0].newInstance(bedrock, 0, 4_000_000L, List.of(bk),
                        0L, -4_000_000L, false, 0, false);
                Mod.staticCall("croesus.CroesusProfitLog", "record", null, v1, "test");
                Mod.staticCall("croesus.CroesusProfitLog", "record", null, v2, "test");
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        long m1 = attempts(c, "chest:M7:Bedrock");
        long u1 = attempts(c, "chest:Unknown:Bedrock");
        c.note("M7 Bedrock " + m0 + " -> " + m1 + ", Unknown Bedrock " + u0 + " -> " + u1);
        c.check(m1 == m0 + 1, "the Unknown claim with a Dark Claymore was not counted as M7 Bedrock");
        c.check(got(c, "chest:M7:Bedrock|Dark Claymore") == cl0 + 1, "its Dark Claymore was not counted");
        c.check(u1 == u0 + 1, "the Unknown claim with only a book was not counted under Unknown");
        c.check(got(c, "chest:Unknown:Bedrock|Enchanted Book (Ultimate Wise II)") >= 1, "its book was not listed");
        c.check(expected(c, "chest:Unknown:Bedrock|Enchanted Book (Ultimate Wise II)") == 0,
                "an Unknown-floor claim got an expectation");
    }
}

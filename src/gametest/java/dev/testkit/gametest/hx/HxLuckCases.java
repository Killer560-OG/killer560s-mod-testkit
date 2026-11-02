package dev.testkit.gametest.hx;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.inventory.InventoryScreen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * How Lucky Are You / Full RNG Tracker (mod branch doc-rng-1101, killer560's to-do Doc 2026-11-01). Every case drives the
 * mod through what Hypixel sends (system chat, the sidebar, real inventory items, a real Q drop and pickup) and asserts the
 * tracker's own counters moved - or, for the forged and re-picked cases, did NOT move while a control in the same case did.
 * <ul>
 *   <li>1101-luck-table: the shipped drop table loads (over 1,000 entries), with Warden Heart at 2/14,697 per Revenant
 *       Horror V (the wiki's weights) and Necron's Handle at the wiki's F7 Bedrock average; the luck math is sane.</li>
 *   <li>1101-luck-slayer: a slayer quest on the sidebar plus SLAYER QUEST COMPLETE and NICE! SLAYER BOSS SLAIN (one boss)
 *       counts ONE Revenant Horror V attempt at the set Magic Find; the bracketed slayer drop lines (with "2x" and the
 *       Magic Find suffix) count; the same words in party chat and all chat count nothing.</li>
 *   <li>1101-luck-chest: Croesus claims count as chest attempts, with the Handle in the claim as a drop.</li>
 *   <li>1101-luck-pickup: a dye given with a new item UUID counts; the SAME dye thrown with a real Q press and picked back
 *       up does not; a dye gained while a screen is open does not; a dye gained right after its drop line is that line's.</li>
 *   <li>1101-luck-calc: /luck calc numbers, the /luck screen opens, /profit lists the Luck card.</li>
 *   <li>1101-luck-share: Share My Luck sends luck.v1 only with Show Me As A Mod User on; a received packet is kept only with
 *       See Other Mod Users on and every field sane.</li>
 *   <li>1101-luck-settings: the settings survive save/load and a profile reload, and have tooltips.</li>
 * </ul>
 */
final class HxLuckCases {

    private static final String DT = "luck.DropTable";
    private static final String LS = "luck.LuckStore";
    private static final String LD = "luck.LuckDetector";
    private static final String CFG = "luck.LuckConfig";
    private static final String MATH = "luck.LuckMath";
    private static final String SHARE = "luck.LuckShare";
    private static final String MUC = "modusers.ModUserConfig";

    private HxLuckCases() {
    }

    static void register(Session s) {
        s.test("1101-luck-table", HxLuckCases::tableCase);
        s.test("1101-luck-slayer", HxLuckCases::slayer);
        s.test("1101-luck-chest", HxLuckCases::chest);
        s.test("1101-luck-pickup", HxLuckCases::pickup);
        s.test("1101-luck-calc", HxLuckCases::calc);
        s.test("1101-luck-share", HxLuckCases::share);
        s.test("1101-luck-settings", HxLuckCases::settings);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static Object table(Session c) {
        return c.onClient(mc -> Mod.staticCall(DT, "get"));
    }

    private static Object cfg() {
        return Mod.staticCall(CFG, "getInstance");
    }

    /** The row for {@code key} across every profile (BASE chest odds, odds at each attempt's Magic Find), or null. */
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

    private static long got(Session c, String key) {
        Object r = row(c, key);
        return r == null ? 0 : ((Number) Mod.call(r, "got")).longValue();
    }

    private static long attempts(Session c, String activity) {
        return c.onClient(mc -> {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Long> m = (java.util.Map<String, Long>) Mod.staticCall(LS, "attempts", (Object) null);
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

    // ---- 1101-luck-table ---------------------------------------------------------------------------------------------

    private static void tableCase(Session c) {
        Object t = table(c);
        int n = c.onClient(mc -> ((List<?>) Mod.call(t, "drops")).size());
        c.note("drop table entries: " + n);
        c.check(n > 1000, "the shipped drop table has only " + n + " entries (want > 1000)");
        Object warden = c.onClient(mc -> Mod.call(t, "byKey", "slayer:revenant:5|Warden Heart"));
        c.check(warden != null, "no Warden Heart for Revenant Horror V in the table");
        double wc = c.onClient(mc -> (Double) Mod.call(warden, "chance"));
        c.note("Warden Heart chance " + wc + " (2/14697 = " + 2.0 / 14697 + ")");
        c.check(Math.abs(wc - 2.0 / 14697) < 1e-9, "Warden Heart chance " + wc + ", want 2/14,697 from the wiki's weights");
        Object handle = c.onClient(mc -> Mod.call(t, "byKey", "chest:F7:Bedrock|Necron's Handle"));
        c.check(handle != null, "no Necron's Handle in chest:F7:Bedrock");
        double hc = c.onClient(mc -> (Double) Mod.call(handle, "chance"));
        double hm = c.onClient(mc -> (Double) Mod.call(handle, "chanceMax"));
        c.note("Handle F7 Bedrock: no bonuses " + hc + ", S+ max " + hm);
        c.check(Math.abs(hc - 0.001067) < 1e-9 && Math.abs(hm - 0.001176) < 1e-9,
                "Handle chances " + hc + "/" + hm + ", want the wiki's 0.1067% / 0.1176%");
        // Math: 0 drops with 0.69 expected is unlucky (e^-0.69/2 = 25%); 3 with 1 expected is lucky.
        double p0 = c.onClient(mc -> (Double) Mod.staticCall(MATH, "percentile", 0L, 0.69));
        double p3 = c.onClient(mc -> (Double) Mod.staticCall(MATH, "percentile", 3L, 1.0));
        double atLeast = c.onClient(mc -> (Double) Mod.staticCall(MATH, "atLeastOne", 0.001, 1000L));
        c.note("percentile(0, 0.69)=" + p0 + " percentile(3, 1)=" + p3 + " atLeastOne(1/1000, 1000)=" + atLeast);
        c.check(Math.abs(p0 - Math.exp(-0.69) / 2) < 1e-9, "percentile(0, 0.69) = " + p0);
        c.check(p3 > 0.9 && p3 < 0.99, "percentile(3, 1.0) = " + p3);
        c.check(Math.abs(atLeast - (1 - Math.pow(0.999, 1000))) < 1e-9, "atLeastOne = " + atLeast);
    }

    // ---- 1101-luck-slayer --------------------------------------------------------------------------------------------

    private static void slayer(Session c) {
        String act = "slayer:revenant:5";
        String warden = act + "|Warden Heart";
        String flesh = act + "|Foul Flesh";
        Object cfg = c.onClient(mc -> cfg());
        Object mode0 = c.onClient(mc -> Mod.call(cfg, "getMagicFindMode"));
        int mf0 = c.onClient(mc -> (Integer) Mod.call(cfg, "getManualMagicFind"));
        try {
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setMagicFindMode", Mod.enumValue(CFG + "$MagicFindMode", "MANUAL"));
                Mod.call(cfg, "setManualMagicFind", 100);
                Mod.staticCall(LD, "resetForTest");
            });
            long a0 = attempts(c, act);
            long w0 = got(c, warden);
            long f0 = got(c, flesh);

            // Forged first: the kill and drop words typed by other players count nothing.
            skyblock(c, "§cSlayer Quest", "§4Revenant Horror §cV", "§eSlay the boss!", "");
            c.ctx().waitTicks(30);
            c.hx().chat("§9Party §8> §b[MVP§c+§b] HxMateA§f: SLAYER QUEST COMPLETE!");
            c.hx().chat("§7[VIP] HxBob§f: PRAY TO RNGESUS DROP! (Warden Heart) (+300% ✯ Magic Find)");
            c.hx().chat("§9Party §8> §b[MVP§c+§b] HxMateA§f: RARE DROP! Warden Heart");
            c.ctx().waitTicks(10);
            c.check(attempts(c, act) == a0, "a party chat 'SLAYER QUEST COMPLETE!' counted a Revenant V kill");
            c.check(got(c, warden) == w0, "a player's chat line counted a Warden Heart");

            // Real: both completion lines for one boss count one kill at 100% MF.
            c.hx().chat("  §r§a§lSLAYER QUEST COMPLETE!");
            c.hx().chat("§a§lNICE! SLAYER BOSS SLAIN!");
            c.waitUntil("one Revenant V kill counted", mc -> attempts(c, act) == a0 + 1, 40);
            c.ctx().waitTicks(10);
            long a1 = attempts(c, act);
            c.note("attempts " + a0 + " -> " + a1 + "; " + decision(c));
            c.check(a1 == a0 + 1, "two completion lines for one boss counted " + (a1 - a0) + " kills");
            Object wr = row(c, warden);
            double exp = ((Number) Mod.call(wr, "expected")).doubleValue();
            double base = ((Number) Mod.call(wr, "expectedBase")).doubleValue();
            c.note("Warden Heart expected after one kill at 100% MF: " + exp + " (base " + base + ")");
            c.check(Math.abs(base - 2.0 / 14697) < 1e-9 && Math.abs(exp - 2 * 2.0 / 14697) < 1e-9,
                    "one kill added expected " + exp + " / base " + base + ", want 2x and 1x the 2/14,697 chance");

            // The drop lines, as SkyHanni's regex tests show Hypixel's slayer drops.
            c.hx().chat("§5§lPRAY TO RNGESUS DROP!  §r§7(§r§f§r§6Warden Heart§r§7) §r§b(+250% §r§b✯ Magic Find§r§b)");
            c.hx().chat("§b§lRARE DROP! §r§7(§r§f§r§72x §r§f§r§9Foul Flesh§r§7) §r§b(+250% ✯ Magic Find)");
            c.waitUntil("Warden Heart and Foul Flesh counted",
                    mc -> got(c, warden) == w0 + 1 && got(c, flesh) == f0 + 1, 40);
            c.note("Warden " + w0 + " -> " + got(c, warden) + ", Foul Flesh " + f0 + " -> " + got(c, flesh));
            Object last = c.onClient(mc -> ((List<?>) Mod.staticCall(LS, "recent", 1)).get(0));
            c.check(((Number) Mod.call(last, "amount")).intValue() == 2 && ((Number) Mod.call(last, "magicFind")).intValue() == 250,
                    "the 2x Foul Flesh line logged amount " + Mod.call(last, "amount") + " at " + Mod.call(last, "magicFind")
                            + "% MF, want 2 at 250%");
            // A minute-old quest no longer exists once the sidebar leaves it; a lone completion line then counts nothing.
            skyblock(c);
            c.ctx().waitTicks(10);
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setMagicFindMode", mode0);
                Mod.call(cfg, "setManualMagicFind", mf0);
            });
        }
    }

    // ---- 1101-luck-chest ---------------------------------------------------------------------------------------------

    private static void chest(Session c) {
        String act = "chest:F7:Bedrock";
        long a0 = attempts(c, act);
        long h0 = got(c, act + "|Necron's Handle");
        c.ctx().runOnClient(mc -> {
            try {
                Class<?> priced = Mod.cls("croesus.DungeonChestValuer$PricedItem");
                Class<?> value = Mod.cls("croesus.DungeonChestValuer$ChestValue");
                Class<?> type = Mod.cls("croesus.DungeonChestValuer$ChestType");
                Object handle = priced.getConstructors()[0].newInstance("Necron's Handle", "NECRON_HANDLE", 1, 100_000_000L, false);
                Object book = priced.getConstructors()[0].newInstance("Enchanted Book (Ultimate Wise II)", "", 1, 0L, false);
                Object bedrock = Mod.enumValue("croesus.DungeonChestValuer$ChestType", "BEDROCK");
                for (int i = 0; i < 2; i++) {
                    Object v = value.getConstructors()[0].newInstance(bedrock, 0, 4_000_000L,
                            i == 0 ? List.of(handle, book) : List.of(book), 100_000_000L, 96_000_000L, false, 0, false);
                    Mod.staticCall("croesus.CroesusProfitLog", "record", "F7", v, "test");
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        long a1 = attempts(c, act);
        long h1 = got(c, act + "|Necron's Handle");
        Object hr = row(c, act + "|Necron's Handle");
        double exp = ((Number) Mod.call(hr, "expected")).doubleValue();
        c.note("F7 Bedrock claims " + a0 + " -> " + a1 + ", Handles " + h0 + " -> " + h1 + ", expected " + exp);
        c.check(a1 == a0 + 2, "two Bedrock claims counted " + (a1 - a0) + " chest attempts");
        c.check(h1 == h0 + 1, "the claimed Handle counted " + (h1 - h0));
        c.check(Math.abs(exp - a1 * 0.001067) < 1e-9, "Handle expected " + exp + ", want " + a1 + " x 0.1067%");
        long book = got(c, act + "|Enchanted Book (Ultimate Wise II)");
        c.check(book >= 2, "the two Ultimate Wise II books counted " + book);
    }

    // ---- 1101-luck-pickup --------------------------------------------------------------------------------------------

    private static String dye(String uuid) {
        return "minecraft:red_dye[custom_name={text:\"Pelt Dye\",italic:false},custom_data={id:\"DYE_PELT\",uuid:\"" + uuid + "\"}]";
    }

    private static void pickup(Session c) {
        String key = "untracked|Pelt Dye";
        skyblock(c);
        c.ctx().runOnClient(mc -> {
            McCompat.setScreen(mc, null);
            Mod.staticCall(LD, "resetForTest");
            mc.player.getInventory().setSelectedSlot(5);
        });
        c.hx().give(5, "minecraft:air");
        c.ctx().waitTicks(20); // baseline
        long g0 = got(c, key);

        // 1. A new dye with a new UUID: a real drop.
        c.hx().give(5, dye("luck-uuid-1"));
        c.waitUntil("the first Pelt Dye counted", mc -> got(c, key) == g0 + 1, 60);
        c.note("new dye -> " + decision(c));

        // 2. Thrown with a real Q press, then picked back up: NOT a drop.
        int ex0 = c.onClient(mc -> (Integer) Mod.field(LD, "excludedPickups"));
        c.ctx().getInput().pressKey(o -> o.keyDrop);
        c.waitUntil("the dye left the inventory", mc -> mc.player.getInventory().getItem(5).isEmpty(), 40);
        c.ctx().waitTicks(10);
        c.server().command("tp @e[type=item] @a[limit=1]");
        c.waitUntil("the thrown dye was picked back up", mc -> {
            for (int i = 0; i < 36; i++) {
                if (mc.player.getInventory().getItem(i).getHoverName().getString().equals("Pelt Dye")) {
                    return true;
                }
            }
            return false;
        }, 200);
        c.ctx().waitTicks(20);
        int ex1 = c.onClient(mc -> (Integer) Mod.field(LD, "excludedPickups"));
        c.note("after Q + pickup: got " + got(c, key) + ", excluded " + ex0 + " -> " + ex1 + "; " + decision(c));
        c.check(got(c, key) == g0 + 1, "a Pelt Dye thrown and picked back up counted as a new drop");
        c.check(ex1 > ex0, "the pickup was not seen at all (excludedPickups did not move): the exclusion did not act");

        // 3. A second copy of an already-seen UUID (no loss in between): not a drop.
        c.hx().give(7, dye("luck-uuid-1"));
        c.ctx().waitTicks(20);
        c.check(got(c, key) == g0 + 1, "a stack with an already-seen UUID counted as a drop");

        // 4. Gained while a screen is open (a trade, a chest, storage): not a drop, even after it closes.
        c.ctx().runOnClient(mc -> McCompat.setScreen(mc, new InventoryScreen(mc.player)));
        c.ctx().waitTicks(5);
        c.hx().give(8, dye("luck-uuid-2"));
        c.ctx().waitTicks(10);
        c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
        c.ctx().waitTicks(20);
        c.check(got(c, key) == g0 + 1, "a dye gained with a screen open counted as a drop");

        // 5. Control: a new UUID with no line, no screen: counts.
        c.hx().give(4, dye("luck-uuid-4"));
        c.waitUntil("the second real Pelt Dye counted", mc -> got(c, key) == g0 + 2, 60);
        // 6. A drop line, then the dye lands: one drop, not two.
        c.hx().chat("§d§lCRAZY RARE DROP! §r§fPelt Dye");
        c.waitUntil("the drop line counted", mc -> got(c, key) == g0 + 3, 40);
        c.hx().give(6, dye("luck-uuid-3"));
        c.ctx().waitTicks(20);
        c.note("line + landing -> " + got(c, key) + "; " + decision(c));
        c.check(got(c, key) == g0 + 3, "a drop line and its dye landing counted twice");

        for (int slot : new int[]{4, 5, 6, 7, 8}) {
            c.hx().give(slot, "minecraft:air");
        }
    }

    // ---- 1101-luck-calc ----------------------------------------------------------------------------------------------

    private static void calc(Session c) {
        @SuppressWarnings("unchecked")
        List<String> lines = c.onClient(mc -> (List<String>) Mod.staticCall("luck.LuckFeature", "calcLines", 10000, 0,
                "Necron's Handle"));
        c.note(String.join(" | ", lines));
        c.check(lines.stream().anyMatch(l -> l.contains("F7 Bedrock Chest") && l.contains("10.67 expected")),
                "/luck calc 10000 0 Necron's Handle did not give 10.67 expected for F7 Bedrock: " + lines);
        @SuppressWarnings("unchecked")
        List<String> warden = c.onClient(mc -> (List<String>) Mod.staticCall("luck.LuckFeature", "calcLines", 7348, 100,
                "Warden Heart"));
        c.note(String.join(" | ", warden));
        c.check(warden.size() == 1 && warden.get(0).contains("2.00 expected"), "Warden Heart at 100% MF over 7,348: " + warden);
        c.ctx().runOnClient(mc -> Mod.staticCall("luck.LuckFeature", "open"));
        c.waitUntil("the How Lucky Are You screen opened",
                mc -> McCompat.screen(mc) != null && McCompat.screen(mc).getClass().getSimpleName().equals("LuckScreen"), 40);
        int rows = c.onClient(mc -> ((List<?>) Mod.call(McCompat.screen(mc), "rows")).size());
        c.note("screen rows: " + rows);
        c.check(rows > 0, "the screen lists no drops after the slayer and chest cases");
        c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
        Object card = c.onClient(mc -> Mod.staticCall("gui.profit.ProfitTracker", "byWord", "luck"));
        c.check(card != null, "/profit luck names no tracker");
    }

    // ---- 1101-luck-share ---------------------------------------------------------------------------------------------

    private static void share(Session c) {
        Object cfg = c.onClient(mc -> cfg());
        Object mu = c.onClient(mc -> Mod.staticCall(MUC, "getInstance"));
        boolean share0 = c.onClient(mc -> (Boolean) Mod.call(cfg, "isShareMyLuck"));
        boolean muShare0 = c.onClient(mc -> (Boolean) Mod.call(mu, "isShare"));
        boolean see0 = c.onClient(mc -> (Boolean) Mod.call(mu, "isSeeOthers"));
        List<String> sent = new CopyOnWriteArrayList<>();
        BiPredicate<String, JsonElement> sender = (k, v) -> sent.add(k + "=" + v);
        try {
            c.ctx().runOnClient(mc -> {
                Mod.staticCall(SHARE, "clearForTest");
                Mod.setField(SHARE, "testSender", sender);
                Mod.call(cfg, "setShareMyLuck", true);
                Mod.call(mu, "setShare", false);
            });
            c.ctx().waitTicks(60);
            c.check(sent.isEmpty(), "Share My Luck sent with Show Me As A Mod User off: " + sent);
            c.ctx().runOnClient(mc -> Mod.call(mu, "setShare", true));
            c.waitUntil("a luck.v1 packet was handed to the relay", mc -> !sent.isEmpty(), 80);
            c.note("sent: " + sent.get(0));
            c.check(sent.get(0).startsWith("luck.v1={\"luck\":"), "unexpected packet " + sent.get(0));

            // Receive.
            String good = "{\"luck\":0.62,\"drops\":3,\"got\":4,\"expected\":3.1,\"rows\":[[\"Warden Heart\",1,0.5]]}";
            c.ctx().runOnClient(mc -> Mod.call(mu, "setSeeOthers", false));
            boolean off = c.onClient(mc -> (Boolean) Mod.staticCall(SHARE, "receive", "HxMateA", JsonParser.parseString(good)));
            c.check(!off, "a luck packet was kept with See Other Mod Users off");
            c.ctx().runOnClient(mc -> Mod.call(mu, "setSeeOthers", true));
            boolean on = c.onClient(mc -> (Boolean) Mod.staticCall(SHARE, "receive", "HxMateA", JsonParser.parseString(good)));
            c.check(on, "a sane luck packet was dropped with See Other Mod Users on");
            for (String bad : List.of("{\"luck\":5,\"drops\":3,\"got\":4,\"expected\":3.1,\"rows\":[]}",
                    "{\"luck\":0.5,\"drops\":3,\"got\":-4,\"expected\":3.1,\"rows\":[]}",
                    "{\"luck\":0.5,\"drops\":3,\"got\":4,\"expected\":3.1,\"rows\":[[\"§cRed\",1,1]]}",
                    "{\"luck\":0.5}", "[]")) {
                boolean kept = c.onClient(mc -> (Boolean) Mod.staticCall(SHARE, "receive", "HxMateB", JsonParser.parseString(bad)));
                c.check(!kept, "a forged luck packet was kept: " + bad);
            }
            boolean badName = c.onClient(mc -> (Boolean) Mod.staticCall(SHARE, "receive", "bad name!", JsonParser.parseString(good)));
            c.check(!badName, "a packet from an impossible name was kept");
            int others = c.onClient(mc -> ((List<?>) Mod.staticCall(SHARE, "others")).size());
            c.check(others == 1, "others() lists " + others + " players, want HxMateA only");
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.setField(SHARE, "testSender", null);
                Mod.call(cfg, "setShareMyLuck", share0);
                Mod.call(mu, "setShare", muShare0);
                Mod.call(mu, "setSeeOthers", see0);
                Mod.staticCall(SHARE, "clearForTest");
            });
        }
    }

    // ---- 1101-luck-settings ------------------------------------------------------------------------------------------

    private static void settings(Session c) {
        Object cfg = c.onClient(mc -> cfg());
        boolean share0 = c.onClient(mc -> (Boolean) Mod.call(cfg, "isShareMyLuck"));
        c.check(!share0, "Share My Luck is on by default (it must be opt-in)");
        try {
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setAnnounce", false);
                Mod.call(cfg, "setChestOdds", Mod.enumValue(CFG + "$ChestOdds", "MAX"));
                Mod.call(cfg, "setManualMagicFind", 345);
                Mod.call(cfg, "setShareMyLuck", true);
                Mod.call(cfg, "save");
                Mod.staticCall(CFG, "load");
            });
            Object back = c.onClient(mc -> cfg());
            c.check(!(Boolean) c.onClient(mc -> Mod.call(back, "isAnnounce")), "Drop Chat Line did not survive a reload");
            c.check("MAX".equals(c.onClient(mc -> Mod.call(back, "getChestOdds").toString())), "Chest Odds did not survive");
            c.check((Integer) c.onClient(mc -> Mod.call(back, "getManualMagicFind")) == 345, "Magic Find did not survive");
            c.ctx().runOnClient(mc -> Mod.staticCall("profiles.ProfileManager", "reloadAllConfigs"));
            Object after = c.onClient(mc -> cfg());
            c.check((Boolean) c.onClient(mc -> Mod.call(after, "isShareMyLuck")), "a profile reload lost Share My Luck");
            for (String label : List.of("RNG Tracker", "Open How Lucky Are You", "Drop Chat Line", "Count Dye Pickups",
                    "Chest Odds", "Magic Find", "Share My Luck")) {
                String tip = c.onClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "describe", "How Lucky Are You", label));
                c.check(tip != null && !tip.isBlank(), "no tooltip for " + label);
            }
        } finally {
            c.ctx().runOnClient(mc -> {
                Object o = cfg();
                Mod.call(o, "setAnnounce", true);
                Mod.call(o, "setChestOdds", Mod.enumValue(CFG + "$ChestOdds", "BASE"));
                Mod.call(o, "setManualMagicFind", 0);
                Mod.call(o, "setShareMyLuck", false);
                Mod.call(o, "save");
            });
        }
    }
}

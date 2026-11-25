package dev.testkit.gametest.menu;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.testkit.gametest.menu.PvStyle1104Cases.*;

/**
 * pv-styles-exact-1124 (killer560, 2026-11-24: "look up the other profile viewer styles exactly and use them. every bit
 * of it"): each non-Default /pv Style against its original's own numbers. One case per style opens every page on a
 * seeded offline profile (slayers, essence, kills, ironman) and checks:
 * <ul>
 * <li>1124-menu-pvexact-neu: NEU Classic's frame (no Switch/Recent column; recent players as right-edge tabs from its
 * sprite; the 100 px name box under the panel's right end; the ironman icon at -21, +5 under the panel; the profile
 * name in teal; tab order Basic, Dungeon, Extra, Inventories, Collections, Pets ... by real clicks) and its Basic page
 * (name strip, Senither and Lily weights, Net Worth, SkyBlock level 123 with 45/100, the 2 x 8 grid at 237 + 86c,
 * 24 + 21r with Social at 132, 124, the boss-bar bars, the outlined colored names, NEU's hover lines).</li>
 * <li>1124-menu-pvexact-sbpv{light,dark,clear}: SkyBlockPV's main page (Info 10 px down with Purse, Bank, SkyBlock
 * Level, First Join, Skill Avg, Fairy Souls, K/D, Net Worth in that order, their hovers; Skills, Slayer and Essence
 * chip sections with the original's chip hovers).</li>
 * </ul>
 * Each saves its Basic Info screenshot for the owner.
 */
final class PvExact1124Cases {
    private PvExact1124Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1124-menu-pvexact-neu", c -> run(c, "NEU"));
        MenuSuite.test(s, "1124-menu-pvexact-sbpvlight", c -> run(c, "SBPV_LIGHT"));
        MenuSuite.test(s, "1124-menu-pvexact-sbpvdark", c -> run(c, "SBPV_DARK"));
        MenuSuite.test(s, "1124-menu-pvexact-sbpvclear", c -> run(c, "SBPV_CLEAR"));
    }

    /** PvStyle1104Cases' profile plus an ironman game mode, essence, a Revenant slayer with tier kills, kill and death
     *  stats, 12,345 SkyBlock XP (level 123, 45/100) and a first join. */
    static final String EXACT_PROFILE = PROFILE
            .replace("\"selected\":true,", "\"selected\":true,\"game_mode\":\"ironman\",")
            .replace("{\"coin_purse\":123456}", "{\"coin_purse\":123456,\"essence\":{\"WITHER\":{\"current\":1234},\"DRAGON\":{\"current\":56}}}")
            .replace("\"rift\":{", "\"slayer\":{\"slayer_bosses\":{\"zombie\":{\"xp\":1500000,\"boss_kills_tier_0\":10,\"boss_kills_tier_4\":3}}},"
                    + "\"player_stats\":{\"kills\":{\"total\":500,\"zombie\":500,\"zombie_1\":20},\"deaths\":{\"total\":20}},"
                    // 1124c: SkyBlockPV's Fishing, Chocolate Factory and Loadout pages.
                    + "\"trophy_fish\":{\"sulphur_skitter\":3,\"sulphur_skitter_bronze\":3,\"total_caught\":3,\"last_caught\":\"sulphur_skitter/bronze\"},"
                    + "\"events\":{\"easter\":{\"chocolate\":1234567,\"total_chocolate\":2500000,\"chocolate_level\":2,\"employees\":{\"rabbit_bro\":10},"
                    + "\"rabbits\":{\"aaron\":2,\"able\":1,\"collected_eggs\":{}},\"rabbit_hitmen\":{\"rabbit_hitmen_slots\":3}}},"
                    // 1124d: tree gifts, personal bests and essence perks, and a HotM tree for the Loadout trees.
                    + "\"foraging\":{\"tree_gifts\":{\"FIG\":12,\"milestone_tier_claimed\":{\"FIG\":2}},\"starlyn\":{\"personal_bests\":{\"FIG_LOG\":4321}}},"
                    + "\"skill_tree\":{\"nodes\":{\"mining\":{\"mining_speed\":10,\"core_of_the_mountain\":1}},\"experience\":{\"mining\":5000}},"
                    + "\"loadout\":{\"loadouts\":{\"1\":{\"id\":1,\"name\":\"Boss\",\"favoredBait\":\"MINNOW_BAIT\"}}},"
                    + "\"leveling\":{\"experience\":12345},\"profile\":{\"first_join\":1600000000000,\"cookie_buff_active\":true},"
                    + "\"jacobs_contest\":{\"perks\":{\"double_drops\":15},\"medals_inv\":{\"gold\":4}},\"rift\":{")
            // 1124b: a maxed Social (NEU's maxed bar), a bank history, and a worn diamond set for the model's armor.
            .replace("\"SKILL_FARMING\":3000000}", "\"SKILL_FARMING\":3000000,\"SKILL_SOCIAL\":5000000},\"perks\":{\"midas_lure\":5,\"drake_piper\":1,\"agatha_fig_personal_best\":1,\"agatha_fig_fortune\":50}")
            .replace("\"banking\":{\"balance\":4200000}", "\"banking\":{\"balance\":4200000,\"transactions\":["
                    + "{\"amount\":250000,\"timestamp\":1700000000000,\"action\":\"DEPOSIT\",\"initiator_name\":\"§bPvStyleTest\"},"
                    + "{\"amount\":1000,\"timestamp\":1700000500000,\"action\":\"WITHDRAW\",\"initiator_name\":\"§bPvStyleTest\"}]}")
            .replace("\"inv_contents\":{\"type\":0,\"data\":\"\"}", "\"inv_contents\":{\"type\":0,\"data\":\"\"},\"inv_armor\":{\"type\":0,\"data\":\""
                    + armorB64() + "\"}");

    /** Hypixel's legacy inventory blob for a worn diamond set, boots first: {i:[{id, Count, Damage}...]} gzipped, base64. */
    private static String armorB64() {
        try {
            net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
            for (short id : new short[]{313, 312, 311, 310}) {
                net.minecraft.nbt.CompoundTag t = new net.minecraft.nbt.CompoundTag();
                t.putShort("id", id);
                t.putByte("Count", (byte) 1);
                t.putShort("Damage", (short) 0);
                list.add(t);
            }
            net.minecraft.nbt.CompoundTag root = new net.minecraft.nbt.CompoundTag();
            root.put("i", list);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            net.minecraft.nbt.NbtIo.writeCompressed(root, out);
            return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void run(Session c, String style) throws Exception {
        c.check(EXACT_PROFILE.contains("slayer_bosses") && EXACT_PROFILE.contains("ironman") && EXACT_PROFILE.contains("WITHER")
                        && EXACT_PROFILE.contains("SKILL_SOCIAL") && EXACT_PROFILE.contains("transactions") && EXACT_PROFILE.contains("inv_armor"),
                "the seed edits did not apply");
        Object oldStyle = c.onClient(mc -> Mod.call(Mod.cfg(PVCFG), "getStyle"));
        boolean oldRemember = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(PVCFG), "isRememberLastPage"));
        try (SocialCases.Env env = new SocialCases.Env(c)) {
            BazaarV3Cases.setWindow(c, 1600, 900, 2);
            c.ctx().runOnClient(mc -> {
                Object cfg = Mod.cfg(PVCFG);
                Mod.call(cfg, "setRememberLastPage", false);
                Mod.call(cfg, "setStyle", Mod.enumValue(PVCFG + "$Style", style));
                Mod.call(cfg, "save");
            });
            seedWith(c, EXACT_PROFILE);
            Screen s = c.onClient(mc -> {
                try {
                    Object target = Mod.cls("profileviewer.ProfileViewerFeature$Target")
                            .getDeclaredConstructor(String.class, UUID.class).newInstance("PvStyleTest", ID);
                    Screen pv = (Screen) Mod.cls("profileviewer.screen.ProfileViewerScreen")
                            .getDeclaredConstructor(Screen.class, target.getClass()).newInstance(null, target);
                    McCompat.setScreen(mc, pv);
                    return pv;
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("cannot open the PV: " + e, e);
                }
            });
            c.waitUntil("the PV shows the offline profile", mc -> "READY".equals(String.valueOf(Mod.field(s, "state"))), 100);
            c.waitUntil("the profile's extras are parsed", mc -> ((java.util.concurrent.Future<?>) Mod.call(Mod.call(s, "profile"), "extras")).isDone(), 400);
            c.ctx().waitTicks(5);
            c.check(style.equals(c.onClient(mc -> String.valueOf(Mod.field(s, "pvStyle")))), "the screen is not in style " + style);

            List<String> bad = new ArrayList<>();
            int[] geo = c.onClient(mc -> new int[]{i(s, "panelX"), i(s, "panelY"), i(s, "panelW"), i(s, "panelH"), i(s, "contentY"), s.width});
            BufferedImage img = BazaarReskinCases.shot(c, style.toLowerCase() + "-basic"); // kept for the owner
            double scale = img.getWidth() / (double) geo[5];
            // The player model wears the profile's armor (NEU's fake player / SkyBlockPV's mannequin), not the bare skin widget.
            c.waitUntil("the model widget is dressed", mc -> {
                for (Object w : s.children()) {
                    if (w.getClass().getSimpleName().equals("PvModel")) return Mod.field(w, "armor") != null;
                }
                return false;
            }, 200);
            String head = c.onClient(mc -> {
                for (Object w : s.children()) {
                    if (w.getClass().getSimpleName().equals("PvModel")) {
                        Object e = Mod.field(w, "entity");
                        return String.valueOf(Mod.call(e, "getItemBySlot", net.minecraft.world.entity.EquipmentSlot.HEAD));
                    }
                }
                return "no model";
            });
            PvStyle1104Cases.look(bad, head.contains("diamond_helmet"), style + ": the model's head slot holds " + head + ", expected the diamond helmet");
            if (style.equals("NEU")) {
                neu(c, s, bad, img, scale, geo);
            } else {
                sbpv(c, s, bad, img, scale, geo, style);
            }

            // Every page draws in this style without an error (the client would stop on a render exception).
            // SkyBlockPV adds Loadout, Fishing, Foraging, Chocolate Factory, Kills (14-18); NEU Classic adds Trophy Fish (19).
            int[] pageList = style.equals("NEU") ? new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 19}
                    : java.util.stream.IntStream.range(0, 19).toArray();
            for (int page : pageList) {
                final int p = page;
                c.ctx().runOnClient(mc -> Mod.call(s, "setPage", p));
                c.ctx().waitTicks(4);
                BazaarReskinCases.shot(c, style.toLowerCase() + "-page" + p); // every page per style, kept for the owner
                boolean ok = c.onClient(mc -> McCompat.screen(mc) == s && "READY".equals(String.valueOf(Mod.field(s, "state")))
                        && (Integer) Mod.field(s, "page") == p);
                PvStyle1104Cases.look(bad, ok, style + ": page " + p + " did not stay open and ready");
            }
            c.note(style + ": " + bad.size() + " differences");
            c.check(bad.isEmpty(), bad.size() + " differences: " + String.join("; ", bad));
        } finally {
            c.ctx().runOnClient(mc -> {
                McCompat.setScreen(mc, null);
                Object cfg = Mod.cfg(PVCFG);
                Mod.call(cfg, "setStyle", oldStyle);
                Mod.call(cfg, "setRememberLastPage", oldRemember);
                Mod.call(cfg, "save");
                ((Map<?, ?>) Mod.field(API, "CACHE")).remove(ID);
            });
        }
    }

    private static void neu(Session c, Screen s, List<String> bad, BufferedImage img, double scale, int[] g) throws Exception {
        int px = g[0], py = g[1], pw = g[2], ph = g[3];
        // Chrome: no Switch/Recent column; the name box at the panel's bottom right, 100 wide.
        boolean side = c.onClient(mc -> Boolean.TRUE.equals(Mod.field(s, "sidebarVisible")));
        PvStyle1104Cases.look(bad, !side, "NEU: the Switch/Recent column is drawn");
        int[] box = c.onClient(mc -> {
            Object b = Mod.field(s, "switchBox");
            return b == null ? new int[]{-1, -1, -1} : new int[]{(Integer) Mod.call(b, "getX"), (Integer) Mod.call(b, "getY"), (Integer) Mod.call(b, "getWidth")};
        });
        // ThemedEditBox's text area sits 5 in and centered in its 100 x 20 frame: 90 x 9 at +5, +6.
        PvStyle1104Cases.look(bad, box[0] == px + pw - 95 && box[1] == py + ph + 11 && box[2] == 90,
                "NEU: name box text area at " + box[0] + "," + box[1] + " w" + box[2] + ", expected " + (px + pw - 95) + "," + (py + ph + 11) + " w90 (a 100 x 20 frame at " + (px + pw - 100) + "," + (py + ph + 5) + ")");
        // The ironman icon at -21, +5 under the panel: an opaque pixel of pv_ironman.png.
        BufferedImage iron = res("neu/pv_ironman.png");
        PvStyle1104Cases.look(bad, iron != null, "NEU: no pv_ironman.png in the jar");
        if (iron != null) {
            int[] op = opaque(iron, 0, 0, 16, 16);
            int got = gp(img, scale, px - 21 + op[0], py + ph + 5 + op[1]);
            PvStyle1104Cases.look(bad, op[2] == 0 || near(got, op[2]), "NEU: ironman icon pixel " + String.format("%06X, expected %06X", got, op[2]));
        }
        // The profile name in NEU's teal (3FE0D0) on the profile button under the panel.
        int teal = 0;
        for (int x = px; x < px + 110; x++) {
            for (int y = py + ph + 5; y < py + ph + 25; y++) {
                teal += near(gp(img, scale, x, y), 0x3FE0D0) ? 1 : 0;
            }
        }
        PvStyle1104Cases.look(bad, teal >= 10, "NEU: " + teal + " teal profile-name pixels under the panel (want 10+)");
        // Your own head's tab, top right, from NEU's recent_top sprite (32 x 28 from its 30 x 28 cell, 3 px into the panel).
        BufferedImage rt = res("neu/recent_top.png");
        PvStyle1104Cases.look(bad, rt != null, "NEU: no recent_top.png in the jar");
        if (rt != null) {
            int hits = 0, tried = 0;
            for (int dx = 25; dx < 31; dx++) {
                for (int dy = 1; dy < 5; dy++) {
                    int argb = rt.getRGB(dx * 30 / 32, dy);
                    if ((argb >>> 24) != 255) continue;
                    tried++;
                    hits += near(gp(img, scale, px + pw - 3 + dx, py + dy), argb & 0xFFFFFF) ? 1 : 0;
                }
            }
            PvStyle1104Cases.look(bad, tried > 0 && hits * 2 >= tried, "NEU: recent tab pixels " + hits + "/" + tried + " match recent_top.png");
        }
        // Tab order by real clicks: slot 2 is Extra (our Misc, 12), slot 4 Collections (4), slot 5 Pets (3).
        int[] order = c.onClient(mc -> ((int[]) Mod.field("profileviewer.screen.ProfileViewerScreen", "NEU_TAB_ORDER")).clone());
        PvStyle1104Cases.look(bad, java.util.Arrays.equals(order, new int[]{0, 1, 12, 2, 4, 3, 5, 19, 6, 7, 8, 9, 10, 11, 13}),
                "NEU: tab order " + java.util.Arrays.toString(order));
        int[][] clicks = {{2, 12}, {4, 4}, {5, 3}, {7, 19}, {0, 0}};
        for (int[] k : clicks) {
            BazaarReskinCases.pressAt(c, new int[]{px + k[0] * 28, py - 28 + 4, 28, 19}, 0);
            int page = c.onClient(mc -> (Integer) Mod.field(s, "page"));
            PvStyle1104Cases.look(bad, page == k[1], "NEU: a click on tab slot " + k[0] + " opened page " + page + ", expected " + k[1]);
        }
        c.ctx().waitTicks(3);
        neuTrophy(c, s, bad, scale);

        // Basic page content, built once (NeuBasic).
        Object nb = c.onClient(mc -> Mod.field(s, "neuBasic"));
        c.check(nb != null, "NEU: the Basic page built no content (neuBasic is null)");
        String[] w = c.onClient(mc -> (String[]) Mod.field(nb, "weights"));
        PvStyle1104Cases.look(bad, w[0].startsWith("§aSenither Weight: §6") && w[1].startsWith("§aLily Weight: §6"),
                "NEU: weight lines " + w[0] + " | " + w[1]);
        String worth = c.onClient(mc -> (String) Mod.field(nb, "worthLine"));
        PvStyle1104Cases.look(bad, worth.startsWith("§aNet Worth: "), "NEU: net worth line " + worth);
        String lvl = c.onClient(mc -> Mod.field(nb, "sbLevel") + " " + Mod.field(nb, "sbProgress"));
        PvStyle1104Cases.look(bad, lvl.equals("123 45/100"), "NEU: SkyBlock level shows " + lvl + ", expected 123 45/100");
        List<?> cells = c.onClient(mc -> (List<?>) Mod.field(nb, "cells"));
        c.check(cells != null && cells.size() == 18, "NEU: " + (cells == null ? "no" : cells.size()) + " grid cells, expected 10 skills + Social + Hunting + 6 slayers");
        String[] names = {"Taming", "Mining", "Foraging", "Enchanting", "Carpentry", "Farming", "Combat", "Fishing", "Alchemy",
                "Runecrafting", "Social", "Hunting", "Rev Slayer", "Tara Slayer", "Sven Slayer", "Ender Slayer", "Blaze Slayer", "Vampire Slayer"};
        int[][] at = {{237, 24}, {237, 45}, {237, 66}, {237, 87}, {237, 108}, {237, 129}, {237, 150}, {237, 171}, {323, 24}, {323, 45},
                {132, 124}, {132, 145}, {323, 66}, {323, 87}, {323, 108}, {323, 129}, {323, 150}, {323, 171}};
        for (int k = 0; k < 18; k++) {
            Object cell = cells.get(k);
            String nm = (String) Mod.field(cell, "name");
            int cx = (Integer) Mod.field(cell, "x"), cy = (Integer) Mod.field(cell, "y");
            PvStyle1104Cases.look(bad, names[k].equals(nm) && cx == at[k][0] && cy == at[k][1],
                    "NEU: cell " + k + " is " + nm + " at " + cx + "," + cy + ", expected " + names[k] + " at " + at[k][0] + "," + at[k][1]);
        }
        PvStyle1104Cases.look(bad, !"0".equals(Mod.field(cells.get(6), "level")), "NEU: Combat (55M XP) shows level 0");
        PvStyle1104Cases.look(bad, !"0".equals(Mod.field(cells.get(12), "level")), "NEU: Rev Slayer (1.5M XP) shows level 0");
        String tam = tip(cells.get(0)), rev = tip(cells.get(12)), farm = tip(cells.get(5));
        PvStyle1104Cases.look(bad, farm.contains("|§7Double Drops: §630%|§7Pelts: §e0| |§7§cBronze: §f0|§7§fSilver: §f0|§7§6Gold: §f4"),
                "NEU: Farming hover lacks BasicPage's extras: " + farm);
        PvStyle1104Cases.look(bad, Boolean.TRUE.equals(Mod.field(cells.get(10), "maxed")), "NEU: Social (5M XP) is not maxed");
        PvStyle1104Cases.look(bad, Mod.field(nb, "pet") != null, "NEU: no active pet floating by the player (the profile has an active Ender Dragon)");
        String nm = c.onClient(mc -> (String) Mod.field(nb, "name"));
        PvStyle1104Cases.look(bad, nm.endsWith("PvStyleTest §7♲") && (nm.startsWith("§7PvStyleTest") || nm.contains("]")),
                "NEU: name line " + nm + ", expected the rank-colored name and the ironman icon");
        String mvp = (String) Mod.staticCall("profileviewer.screen.ExtraPages", "rankPrefix",
                com.google.gson.JsonParser.parseString("{\"newPackageRank\":\"MVP_PLUS\",\"rankPlusColor\":\"GOLD\"}").getAsJsonObject());
        PvStyle1104Cases.look(bad, "§b[MVP§6+§b]".equals(mvp), "NEU: MVP+ with a gold plus gives the prefix " + mvp);
        // NEU's maxed bar is the make_gold shader's drifting rainbow: Social's bar row shows several hues, never one flat color.
        java.util.Set<Integer> hues = new java.util.HashSet<>();
        for (int x = 134; x < 211; x += 3) {
            int rgb = gp(img, scale, px + x, py + 124 + 8);
            float[] hsb = java.awt.Color.RGBtoHSB(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, null);
            if (hsb[1] > 0.3f && hsb[2] > 0.3f) hues.add(Math.round(hsb[0] * 12) % 12);
        }
        PvStyle1104Cases.look(bad, hues.size() >= 3, "NEU: Social's maxed bar shows " + hues.size() + " hue bands (want 3+ for the rainbow)");
        PvStyle1104Cases.look(bad, tam.startsWith("Taming|§7Progress: §5") && tam.contains("|§7Total XP: §5"), "NEU: Taming hover " + tam);
        PvStyle1104Cases.look(bad, rev.contains("|§7T1 Kills: §c10") && rev.contains("|§7T5 Kills: §c3"), "NEU: Rev Slayer hover " + rev);

        // Pixels: Taming's name in NEU's pink (FF55FF) right of its icon, its empty boss bar (magenta background) at y + 6,
        // and the white name over its dark strip at y 54.
        int pink = 0;
        for (int x = 251; x < 290; x++) {
            for (int y = 20; y < 29; y++) {
                pink += near(gp(img, scale, px + x, py + y), 0xFF55FF) ? 1 : 0;
            }
        }
        PvStyle1104Cases.look(bad, pink >= 10, "NEU: " + pink + " pink Taming-name pixels (want 10+)");
        int bar = gp(img, scale, px + 277, py + 32);
        int r = bar >> 16 & 0xFF, gr = bar >> 8 & 0xFF, b = bar & 0xFF;
        PvStyle1104Cases.look(bad, r > gr + 20 && b > gr, "NEU: Taming's bar pixel " + String.format("%06X", bar) + " is not the boss bar's magenta");
        int white = 0;
        for (int x = 23; x < 104; x++) {
            for (int y = 54; y < 62; y++) {
                int rgb = gp(img, scale, px + x, py + y);
                white += near(rgb, 0xAAAAAA) || near(rgb, 0xFFFFFF) || near(rgb, 0x55FFFF) ? 1 : 0;
            }
        }
        PvStyle1104Cases.look(bad, white >= 10, "NEU: " + white + " name pixels at y 54 (want 10+)");
    }

    /** 1124h: NEU Classic's Trophy Fish page (the fixture caught 3 Bronze Sulphur Skitters). */
    private static void neuTrophy(Session c, Screen s, List<String> bad, double scale) throws Exception {
        c.ctx().runOnClient(mc -> Mod.call(s, "setPage", 19));
        Object page = c.onClient(mc -> Mod.field(s, "neuTrophy"));
        Object m = null;
        for (int i = 0; i < 40 && m == null; i++) {
            c.ctx().waitTicks(2);
            m = c.onClient(mc -> {
                java.util.concurrent.CompletableFuture<?> f = (java.util.concurrent.CompletableFuture<?>) Mod.field(page, "model");
                return f != null && f.isDone() ? f.getNow(null) : null;
            });
        }
        c.check(m != null, "NEU: the Trophy Fish page built no content");
        c.ctx().waitTicks(4);
        BufferedImage img = BazaarReskinCases.shot(c, "neu-trophy");
        Object model = m;
        List<?> fish = c.onClient(mc -> (List<?>) Mod.field(model, "fish"));
        PvStyle1104Cases.look(bad, fish.size() == 18, "NEU trophy: " + fish.size() + " fish slots, expected 18");
        Object f0 = fish.get(0);
        String f0s = Mod.field(f0, "name") + " best " + Mod.field(f0, "best") + " at " + Mod.field(f0, "x") + "," + Mod.field(f0, "y")
                + " total " + Mod.field(f0, "total");
        PvStyle1104Cases.look(bad, f0s.equals("Sulphur_skitter best 0 at 277,46 total 3"), "NEU trophy: first slot " + f0s);
        Object f17 = fish.get(17);
        PvStyle1104Cases.look(bad, (Integer) Mod.field(f17, "best") == -1 && (Integer) Mod.field(f17, "x") == 277 && (Integer) Mod.field(f17, "y") == 142,
                "NEU trophy: last slot " + Mod.field(f17, "name") + " best " + Mod.field(f17, "best"));
        String[] helm = c.onClient(mc -> (String[]) Mod.field(model, "helmetText"));
        PvStyle1104Cases.look(bad, "§c1/15".equals(helm[0]) && "§c0/18".equals(helm[3]), "NEU trophy: helmet counts " + String.join(",", helm));
        long caught = c.onClient(mc -> (Long) Mod.field(model, "totalCaught"));
        PvStyle1104Cases.look(bad, caught == 3, "NEU trophy: Total Caught " + caught);
        String tip0 = c.onClient(mc -> text((List<?>) Mod.call(page, "tip", model, 0, f0)));
        PvStyle1104Cases.look(bad, tip0.startsWith("§fSulphur Skitter|") && tip0.contains("|§bDiamond: §c✖|") && tip0.contains("|§8Bronze: §63|")
                && tip0.endsWith("|§7Total: §63"), "NEU trophy: Sulphur Skitter hover " + tip0);
        String tip17 = c.onClient(mc -> text((List<?>) Mod.call(page, "tip", model, 17, f17)));
        PvStyle1104Cases.look(bad, tip17.contains("|§c§c✖ Not Discovered|"), "NEU trophy: missing fish hover " + tip17);
        // The first slot's ring is pv_elements' cell tinted bronze (FF8200): its gray edge reads orange.
        int px = c.onClient(mc -> (Integer) Mod.field(s, "panelX")), py = c.onClient(mc -> (Integer) Mod.field(s, "panelY"));
        int ring = gp(img, scale, px + 275 + 10, py + 44);
        int r = ring >> 16 & 0xFF, gr = ring >> 8 & 0xFF, b = ring & 0xFF;
        PvStyle1104Cases.look(bad, r > gr + 25 && gr >= b, "NEU trophy: bronze ring pixel " + String.format("%06X", ring));
        c.ctx().runOnClient(mc -> Mod.call(s, "setPage", 0));
        c.ctx().waitTicks(3);
    }

    private static void sbpv(Session c, Screen s, List<String> bad, BufferedImage img, double scale, int[] g, String style) throws Exception {
        Object m = c.onClient(mc -> Mod.field(s, "sbpvMain"));
        c.check(m != null, style + ": the main page built no content (sbpvMain is null)");
        String[] info = c.onClient(mc -> (String[]) Mod.field(m, "info"));
        String all = String.join(" | ", info);
        String[] starts = {"Purse: 123,456", "Bank: ", "Cookie Buff: §aActive", "SkyBlock Level: 123.45", "First Join: 2020.09.1", "Skill Avg: ",
                "Fairy Souls: 0", "K/D: ", "Net Worth: "};
        PvStyle1104Cases.look(bad, info.length == starts.length, style + ": " + info.length + " Info lines: " + all);
        for (int k = 0; k < Math.min(info.length, starts.length); k++) {
            PvStyle1104Cases.look(bad, info[k].startsWith(starts[k]), style + ": Info line " + k + " is " + info[k] + ", expected " + starts[k] + "...");
        }
        PvStyle1104Cases.look(bad, all.contains("K/D: 500/20 (25)"), style + ": K/D line in " + all);
        List<?>[] tips = c.onClient(mc -> (List<?>[]) Mod.field(m, "infoTips"));
        PvStyle1104Cases.look(bad, tips.length > 3 && tips[3] != null && text(tips[3]).contains("Progress: 45/100"),
                style + ": SkyBlock Level hover " + (tips.length > 3 && tips[3] != null ? text(tips[3]) : "none"));
        String bank = (tips.length > 1 && tips[1] != null ? text(tips[1]) : "none").replaceAll("§.", "");
        PvStyle1104Cases.look(bad, bank.contains("Solo Bank:") && bank.contains("Profile Bank:") && bank.contains("Bank History:")
                        && bank.indexOf("1,000 withdrawn by PvStyleTest") >= 0 && bank.indexOf("1,000 withdrawn") < bank.indexOf("250,000 deposited by PvStyleTest")
                        && bank.contains("Date: 2023.11."),
                style + ": Bank hover (newest first, with Bank History) " + bank);
        String tag = c.onClient(mc -> { Object t = Mod.field(s, "sbpvTag"); return t == null ? "none" : ((Component) t).getString(); });
        PvStyle1104Cases.look(bad, tag.equals("[123] PvStyleTest"), style + ": name tag over the model " + tag);
        // Tabs in SkyBlockPV's order; Combat holds Dungeons, Bestiary and Crimson Isle as right-edge category tabs.
        int[] order = c.onClient(mc -> ((int[]) Mod.field("profileviewer.screen.ProfileViewerScreen", "SBPV_TAB_ORDER")).clone());
        PvStyle1104Cases.look(bad, java.util.Arrays.equals(order, new int[]{0, 1, 2, 14, 4, 5, 15, 16, 3, 10, 8, 17, 9, 11, 12, 13}), style + ": tab order " + java.util.Arrays.toString(order));
        Object st = Mod.enumValue(PVCFG + "$Style", style);
        String chrome = "profileviewer.screen.PvStyleChrome";
        int x0 = g[0] + (Integer) Mod.staticCall(chrome, "tabX0", st), tw = (Integer) Mod.staticCall(chrome, "tabW", st),
                th = (Integer) Mod.staticCall(chrome, "tabH", st), ty = g[1] - (Integer) Mod.staticCall(chrome, "tabRise", st);
        int step = Math.min((Integer) Mod.staticCall(chrome, "tabStep", st), (g[0] + g[2] - x0) / order.length);
        BazaarReskinCases.pressAt(c, new int[]{x0 + step + 2, ty + 2, tw - 4, th - 4}, 0);
        int pg = c.onClient(mc -> (Integer) Mod.field(s, "page"));
        PvStyle1104Cases.look(bad, pg == 1, style + ": the Combat tab opened page " + pg + ", expected Dungeons (1)");
        int[][] side = {{1, 6}, {2, 7}, {3, 18}, {0, 1}};
        for (int[] k : side) {
            BazaarReskinCases.pressAt(c, new int[]{g[0] + g[2] - 9 + 12, g[1] + 20 + 22 * k[0] + 4, 14, 12}, 0);
            int got2 = c.onClient(mc -> (Integer) Mod.field(s, "page"));
            PvStyle1104Cases.look(bad, got2 == k[1], style + ": Combat category tab " + k[0] + " opened page " + got2 + ", expected " + k[1]);
        }
        sbpvPages(c, s, bad, style);
        c.ctx().runOnClient(mc -> Mod.call(s, "setPage", 7));
        c.ctx().waitTicks(2);
        BazaarReskinCases.shot(c, style.toLowerCase() + "-crimson");
        BazaarReskinCases.pressAt(c, new int[]{x0 + step + 2, ty + 2, tw - 4, th - 4}, 0);
        int stay = c.onClient(mc -> (Integer) Mod.field(s, "page"));
        PvStyle1104Cases.look(bad, stay == 7, style + ": the Combat tab on Crimson Isle moved to page " + stay + " (should stay)");
        c.ctx().runOnClient(mc -> Mod.call(s, "setPage", 0));
        c.ctx().waitTicks(3);
        List<?> sections = c.onClient(mc -> (List<?>) Mod.field(m, "sections"));
        List<String> titles = new ArrayList<>();
        for (Object sec : sections) titles.add((String) Mod.field(sec, "title"));
        PvStyle1104Cases.look(bad, titles.equals(List.of("Skills", "Slayer", "Essence")), style + ": sections " + titles);
        if (sections.size() >= 2) {
            List<?>[] sk = (List<?>[]) Mod.field(sections.get(0), "tips");
            String combat = text(sk[2]);
            PvStyle1104Cases.look(bad, combat.startsWith("§eCombat|§7Exp: ") && combat.contains("|§7Progress: "), style + ": Combat chip hover " + combat);
            List<?>[] sl = (List<?>[]) Mod.field(sections.get(1), "tips");
            String zombie = "";
            for (List<?> t : sl) if (t != null && text(t).contains("Kills: §710")) zombie = text(t);
            PvStyle1104Cases.look(bad, zombie.contains("§8/§70§8/") && zombie.contains("|§7Exp: §e1,500,000"), style + ": Revenant chip hover " + zombie);
        }
        if (sections.size() >= 3) {
            String[] ev = (String[]) Mod.field(sections.get(2), "values");
            PvStyle1104Cases.look(bad, ev.length == 2, style + ": " + ev.length + " essence chips, expected 2");
        }
        // The Info title bar sits 10 px under the content top (MainScreen's spacer): its sprite's pixel at x + 5.
        String theme = style.equals("SBPV_LIGHT") ? "light" : style.equals("SBPV_DARK") ? "dark" : "clear";
        BufferedImage title = res("sbpv/" + theme + "/title.png");
        int cy = g[4] + 10;
        int got = gp(img, scale, g[0] + 5, cy + 10), exp = title == null ? -1 : blend(title.getRGB(25, 25), gp(img, scale, g[0] + 5, cy - 4));
        PvStyle1104Cases.look(bad, near(got, exp), style + ": Info title bar at contentY + 20 is " + String.format("%06X, its sprite gives %06X", got, exp));
    }

    /** 1124c: SkyBlockPV's own Loadout, Fishing, Foraging, Chocolate Factory and Kills pages build from the profile. */
    private static void sbpvPages(Session c, Screen s, List<String> bad, String style) throws Exception {
        Object pages = c.onClient(mc -> Mod.field(s, "sbpvPages"));
        c.ctx().runOnClient(mc -> Mod.call(s, "setPage", 14));
        c.waitUntil("SkyBlockPV's page model is built", mc -> {
            Object f = Mod.field(pages, "model");
            return f != null && ((java.util.concurrent.Future<?>) f).isDone();
        }, 400);
        c.ctx().waitTicks(3);
        java.util.concurrent.Future<?> fut = c.onClient(mc -> (java.util.concurrent.Future<?>) Mod.field(pages, "model"));
        Object m = fut.get();
        String kills = String.join(" | ", (String[]) Mod.field(m, "kills"));
        PvStyle1104Cases.look(bad, kills.contains("Zombie: 520"), style + ": Kills column (zombie_1 folded into Zombie) " + kills);
        String cf = String.join(" | ", (String[]) Mod.field(m, "cfInfo"));
        PvStyle1104Cases.look(bad, cf.startsWith("Chocolate: §61.2M") && cf.contains("Prestige Level: §d2"), style + ": Chocolate Factory info " + cf);
        String fish = String.join(" | ", (String[]) Mod.field(m, "fishInfo"));
        PvStyle1104Cases.look(bad, !fish.isEmpty(), style + ": Fishing Information is empty");
        String trophy = "";
        for (Object[] row : (Object[][]) Mod.field(m, "bigTable")) {
            for (Object cell : row) {
                String t = text((List<?>) Mod.field(cell, "tip"));
                if (t.contains("Sulphur Skitter") && t.contains(": 3")) trophy = t;
            }
        }
        PvStyle1104Cases.look(bad, !trophy.isEmpty(), style + ": no Trophy Fish cell for 3 bronze Sulphur Skitters");
        Object[] ordered = (Object[]) Mod.field(m, "ordered");
        PvStyle1104Cases.look(bad, ordered.length == 1 && "Boss".equals(Mod.field(ordered[0], "name")), style + ": " + ordered.length + " loadouts");
        List<?>[] loTips = c.onClient(mc -> (List<?>[]) Mod.field(pages, "loTips"));
        PvStyle1104Cases.look(bad, loTips != null && loTips[0] != null && text(loTips[0]).contains("Boss"),
                style + ": Loadout selector hover " + (loTips == null || loTips[0] == null ? "none" : text(loTips[0])));
        String forg = String.join(" | ", (String[]) Mod.field(m, "forInfo"));
        PvStyle1104Cases.look(bad, !forg.isEmpty(), style + ": Foraging Information is empty");
        // 1124d: the bundled repo tables acted (rarities, hitman cost, perks, gift maxima, personal bests, loadout trees, gear).
        String rar = String.join(" | ", (String[]) Mod.field(m, "rarityCounts"));
        List<?>[] rarTips = (List<?>[]) Mod.field(m, "rarityTips");
        PvStyle1104Cases.look(bad, rar.startsWith("§b0") && rar.endsWith("§f2") && text(rarTips[rarTips.length - 1]).replaceAll("§.", "").contains("Total: 3"),
                style + ": Rarities chips " + rar);
        List<?>[] cfTips = (List<?>[]) Mod.field(m, "cfInfoTips");
        String hit = "";
        for (List<?> t : cfTips) if (t != null && text(t).replaceAll("§.", "").startsWith("Paid:")) hit = text(t).replaceAll("§.", "");
        PvStyle1104Cases.look(bad, hit.equals("Paid: 100,000/32M (0.31%)"), style + ": hitman hover '" + hit + "'");
        PvStyle1104Cases.look(bad, fish.contains("Midas Lure: §c5§r/10") && fish.contains("Drake Piper: §a1§r/1"), style + ": fishing perks " + fish);
        PvStyle1104Cases.look(bad, forg.contains("Fig Gifts: §c2§r/§a7") && forg.contains("Total Fig Gifts: 12"), style + ": tree gifts " + forg);
        String pb = String.join(" | ", ((String[][]) Mod.field(m, "pbLines"))[0]);
        PvStyle1104Cases.look(bad, pb.equals("Fig Personal Bests: §aYes | Fig Best: §c4,321§r/§a100,000 | Fig Fortune Level: §a50§r/§a50"),
                style + ": Fig personal bests " + pb);
        Object hotm = ((Object[][]) Mod.field(m, "trees"))[0][0];
        String counts = hotm == null ? "" : String.join(",", (String[]) Mod.field(hotm, "counts"));
        PvStyle1104Cases.look(bad, (counts.contains("10") || counts.contains("50")), style + ": HOTM Loadout tree " + (hotm == null ? "missing" : counts));
        // 1124f: SkyBlockPV's Mining page (Information lines, full HotM tree with its tier column and perk-text hovers).
        String mine = String.join(" | ", (String[]) Mod.field(m, "mineInfo")).replaceAll("§.", "");
        PvStyle1104Cases.look(bad, mine.contains("HotM") || mine.contains("Rock"), style + ": Mining Information " + mine);
        Object full = ((Object[][]) Mod.field(m, "fullTrees"))[0][0];
        String speedTip = "";
        if (full != null) {
            for (List<?> t : (List<?>[]) Mod.field(full, "tips")) {
                String x = t == null ? "" : text(t).replaceAll("§.", "");
                if (x.startsWith("Mining Speed|")) speedTip = x;
            }
        }
        // The shown profile may be this fixture's (level 10) or 1104's (50): the perk text must be filled for that level (20 a level).
        java.util.regex.Matcher lv = java.util.regex.Pattern.compile("^Mining Speed\\|Level (\\d+)(/50)?\\|").matcher(speedTip);
        int speedLevel = lv.find() ? Integer.parseInt(lv.group(1)) : -1;
        PvStyle1104Cases.look(bad, speedLevel > 0 && !speedTip.matches("(?s).*%[a-z_]+%.*")
                        && speedTip.contains("+" + String.format(java.util.Locale.ROOT, "%,d", 20 * speedLevel)),
                style + ": Mining Speed node hover (level and filled perk text) '" + speedTip + "'");
        PvStyle1104Cases.look(bad, full != null && (int) Mod.field(full, "w") > (int) Mod.field(hotm, "w"),
                style + ": full HotM tree has no tier column");
        for (int sub = 0; sub < 3; sub++) {
            int v = sub;
            c.ctx().runOnClient(mc -> { Mod.call(s, "setPage", 5); setInt(s, "subView", v); });
            c.ctx().waitTicks(3);
            BazaarReskinCases.shot(c, style.toLowerCase() + "-mining" + sub);
        }
        // 1124g: SkyBlockPV's Farming page (Main and Crop) from a seeded garden and the bundled garden tables.
        farming(c, s, pages, m, bad, style);
        c.ctx().runOnClient(mc -> setInt(s, "subView", 0));
        for (int[] page : new int[][]{{17}, {16}, {14}}) {
            c.ctx().runOnClient(mc -> Mod.call(s, "setPage", page[0]));
            c.ctx().waitTicks(3);
            BazaarReskinCases.shot(c, style.toLowerCase() + "-page" + page[0]);
        }
        c.ctx().runOnClient(mc -> Mod.call(s, "setPage", 15));
        c.waitUntil("the Fishing Gear is built", mc -> {
            Object g = Mod.field(m, "fishGear");
            return g != null && ((java.util.concurrent.Future<?>) g).isDone();
        }, 400);
        Object gear = ((java.util.concurrent.Future<?>) Mod.field(m, "fishGear")).get();
        PvStyle1104Cases.look(bad, gear != null && ((List<?>) Mod.field(gear, "tools")).size() == 4, style + ": Fishing Gear " + gear);
        c.ctx().waitTicks(3);
        BazaarReskinCases.shot(c, style.toLowerCase() + "-page15");
        c.ctx().runOnClient(mc -> { Mod.call(s, "setPage", 15); setInt(pages, "fishTab", 1); });
        c.ctx().waitTicks(3);
        BazaarReskinCases.shot(c, style.toLowerCase() + "-frogs");
        c.ctx().runOnClient(mc -> setInt(pages, "fishTab", 0));
    }

    private static final String GARDEN = "{\"garden_experience\":1000,\"crop_upgrade_levels\":{\"WHEAT\":9,\"CARROT_ITEM\":2},"
            + "\"resources_collected\":{\"WHEAT\":1000},\"garden_upgrades\":{\"MAX_OCCUPANCY\":1},"
            + "\"commission_data\":{\"visits\":{\"adventurer\":3},\"completed\":{\"adventurer\":2},\"unique_npcs_served\":1},"
            + "\"active_commissions\":{\"adventurer\":{\"position\":1,\"status\":\"STARTED\",\"requirement\":[{\"original_item\":\"WHEAT\","
            + "\"original_amount\":64,\"item\":\"WHEAT\",\"amount\":32}]}}}";

    /** SkyBlockPV's Farming page: Information, Visitors, Visitor Queue, Contests, Chips and Crop, all from the seeded garden. */
    private static void farming(Session c, Screen s, Object pages, Object m, List<String> bad, String style) throws Exception {
        c.ctx().runOnClient(mc -> {
            try {
                java.lang.reflect.Field ef = s.getClass().getDeclaredField("extraPages");
                ef.setAccessible(true);
                Object ep = ef.get(s);
                java.lang.reflect.Field af = ep.getClass().getDeclaredField("aux");
                af.setAccessible(true);
                Class<?> ar = Class.forName("com.killer560.hub.profileviewer.api.ProfileViewerApi$AuxResult", true, s.getClass().getClassLoader());
                Object res = ar.getDeclaredConstructor(com.google.gson.JsonObject.class, String.class, long.class)
                        .newInstance(com.google.gson.JsonParser.parseString(GARDEN).getAsJsonObject(), "test", 0L);
                @SuppressWarnings("unchecked")
                Map<String, Object> aux = (Map<String, Object>) af.get(ep);
                aux.put("GARDEN:p1", java.util.concurrent.CompletableFuture.completedFuture(res));
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            Mod.call(s, "setPage", 10);
            setInt(s, "subView", 0);
        });
        c.waitUntil("the Farming page is built", mc -> {
            Object f = Mod.field(m, "farm");
            return f != null && ((java.util.concurrent.Future<?>) f).isDone();
        }, 400);
        Object f = ((java.util.concurrent.Future<?>) Mod.field(m, "farm")).get();
        PvStyle1104Cases.look(bad, f != null, style + ": no Farming model");
        if (f == null) return;
        String[] info = (String[]) Mod.field(f, "info");
        List<?>[] infoTips = (List<?>[]) Mod.field(f, "infoTips");
        String all = String.join(" | ", info);
        PvStyle1104Cases.look(bad, info[1].equals("§8Garden Level: §25") && text(infoTips[1]).startsWith("§7To lvl 5: §2480§a/§2"),
                style + ": Garden Level line " + info[1] + " / " + (infoTips[1] == null ? "no hover" : text(infoTips[1])));
        PvStyle1104Cases.look(bad, info[3].equals("§8Medals: §c0§8/§70§8/§64") && info[5].equals("§8Farming Level Cap: §c0§8/10")
                && info[6].equals("§8Double Drops: §a15§8/15") && infoTips[6] == null, style + ": Information " + all);
        PvStyle1104Cases.look(bad, infoTips[5] != null && text(infoTips[5]).contains("§7Gold Medal: §20§a/§216§7 (§30%§7)"),
                style + ": Farming Level Cap hover " + (infoTips[5] == null ? "none" : text(infoTips[5])));
        String vis = String.join(" | ", (String[]) Mod.field(f, "visitors"));
        PvStyle1104Cases.look(bad, vis.startsWith("§8Unique Served: §a1§8/§a146 | §8Total§8: §a2§8/§a3 | §aUncommon§8: §a2§8/§a3")
                && vis.contains("§8Max Occupancy: §a1§2/§a5"), style + ": Visitors " + vis);
        Object[] queue = (Object[]) Mod.field(f, "queue");
        List<?>[] qTips = (List<?>[]) Mod.field(f, "queueTips");
        String q = qTips.length == 0 ? "" : text(qTips[0]);
        PvStyle1104Cases.look(bad, queue.length == 1 && (int) Mod.field(f, "queueRows") == 2 && q.startsWith("§a§lCombat Merchant")
                && q.contains("§7Queue Position: §e#1") && q.contains("§7Status: §cStarted") && q.contains("§a32§7/§864"), style + ": Visitor Queue " + q);
        String[][] cropText = (String[][]) Mod.field(f, "cropText");
        List<?>[][] cropTips = (List<?>[][]) Mod.field(f, "cropTips");
        String up = text(cropTips[0][0]), ms = text(cropTips[0][1]);
        PvStyle1104Cases.look(bad, cropText[0][0].equals("§a9") && cropText[5][0].equals("§c2") && up.contains("Copper paid: §c7,685§6/§c7,685") && !up.contains("("),
                style + ": Wheat crop upgrade " + cropText[0][0] + " " + up);
        PvStyle1104Cases.look(bad, cropText[0][1].equals("§c5") && ms.startsWith("§lWheat Milestone|§7Progress: §c5§7/46|§7Progress to 6: §e"),
                style + ": Wheat milestone " + ms);
        String contest = text(((List<?>[]) Mod.field(f, "contestTips"))[0]);
        PvStyle1104Cases.look(bad, contest.startsWith("§f§lWheat||§7Brackets: §c◌§7◌§6◌§3◌§b◌|§7Personal Best: §cNot Unlocked!|§7Contests participated: §e0"),
                style + ": Wheat contest hover " + contest);
        String chips = String.join(",", (String[]) Mod.field(f, "chipText")), chip = text(((List<?>[]) Mod.field(f, "chipTips"))[0]);
        PvStyle1104Cases.look(bad, chips.equals("§c0,§c0,§c0,§c0,§c0,§c0,§c0,§c0,§c0,§c0") && chip.contains("§7Sowdust: §c0§7/§2"),
                style + ": Chips " + chips + " / " + chip);
        c.waitUntil("the Farming gear is built", mc -> {
            Object g = Mod.field(f, "gear");
            return g != null && ((java.util.concurrent.Future<?>) g).isDone();
        }, 400);
        for (int sub = 0; sub < 2; sub++) {
            int v = sub;
            c.ctx().runOnClient(mc -> setInt(s, "subView", v));
            c.ctx().waitTicks(3);
            BazaarReskinCases.shot(c, style.toLowerCase() + "-farming" + sub);
        }
    }

    private static void setInt(Object target, String name, int v) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.setInt(target, v);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot set " + name, e);
        }
    }

    /** The first fully opaque pixel in the box: {x, y, rgb}; {0, 0, 0} when none. */
    private static int[] opaque(BufferedImage im, int x0, int y0, int w, int h) {
        for (int y = y0 + h / 2; y < y0 + h; y++) {
            for (int x = x0 + w / 2; x < x0 + w; x++) {
                int argb = im.getRGB(x, y);
                if ((argb >>> 24) == 255 && (argb & 0xFFFFFF) != 0) return new int[]{x, y, argb & 0xFFFFFF};
            }
        }
        return new int[]{0, 0, 0};
    }

    private static String tip(Object cell) {
        return text((List<?>) Mod.field(cell, "tip"));
    }

    private static String text(List<?> lines) {
        StringBuilder sb = new StringBuilder();
        for (Object o : lines) {
            if (sb.length() > 0) sb.append('|');
            sb.append(((Component) o).getString());
        }
        return sb.toString();
    }
}

package dev.testkit.gametest.menu;

import com.google.gson.JsonParser;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.screens.Screen;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * pvstyle-1104 (killer560 2026-11-04: "add different formats to the pv menu. you should be able to change them in the
 * settings or in the pv menu. add the old neu style theme and the ones from the mod skyblockpv").
 * <p>
 * 1104-menu-pvstyle: opens /pv on an offline profile at 1600x900 GUI 2, then presses the in-screen Style button (a real
 * left click) through every Style. Each press must change the config, the screen and the saved file at once, lay the
 * page out for that style (icon tabs above the panel, content under a 26 px header), draw that style's panel edge
 * (sampled off a screenshot, kept for the owner), and let a real click on an icon tab change the page.
 * pvthemes-1108 (killer560 2026-11-08: "make sure they are exactly 1-1 of the other mods"): each icon style also has
 * the original's panel size and place, its Style button where the original keeps its controls, and its own sprite
 * pixels on the panel edge and the selected tab. A right click
 * steps back, and a fresh load of the file keeps the last style (a restart).
 */
final class PvStyle1104Cases {

    private PvStyle1104Cases() {
    }

    static final String PVCFG = "profileviewer.ProfileViewerConfig";
    static final String API = "profileviewer.api.ProfileViewerApi";
    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final String PROFILE = "{\"success\":true,\"profiles\":[{\"profile_id\":\"p1\",\"cute_name\":\"Mango\",\"selected\":true,"
            + "\"banking\":{\"balance\":4200000},\"members\":{\"000000000000000000000000000000a1\":{\"currencies\":{\"coin_purse\":123456},"
            + "\"player_data\":{\"experience\":{\"SKILL_COMBAT\":55000000,\"SKILL_MINING\":12000000,\"SKILL_FARMING\":3000000}},"
            + "\"inventory\":{\"inv_contents\":{\"type\":0,\"data\":\"\"}},"
            + "\"rift\":{\"castle\":{\"grubber_stacks\":3}},\"bestiary\":{\"kills\":{\"zombie_1\":100}},\"collection\":{\"WHEAT\":1000},\"nether_island_player_data\":{\"selected_faction\":\"mages\",\"mages_reputation\":1200,"
            + "\"kuudra_completed_tiers\":{\"none\":5,\"highest_wave_none\":3},\"dojo\":{\"dojo_points_mob_kb\":500}},\"mining_core\":{\"powder_mithril\":500000,\"powder_spent_mithril\":100000,"
            + "\"crystals\":{\"jade_crystal\":{\"state\":\"FOUND\",\"total_placed\":3}}},"
            + "\"skill_tree\":{\"experience\":{\"mining\":600000},\"nodes\":{\"mining\":{\"mining_speed\":50}}},\"pets_data\":{\"pets\":[{\"type\":\"ENDER_DRAGON\",\"tier\":\"LEGENDARY\",\"exp\":25000000,\"active\":true}]},"
            + "\"dungeons\":{\"dungeon_types\":{\"catacombs\":{\"experience\":600000000}}}}}}]}";

    static final String[] ORDER = {"DEFAULT", "NEU", "SBPV_LIGHT", "SBPV_DARK", "SBPV_CLEAR"};
    /** The panel's left edge at x + 0 and x + 1 (mid height), per style: the originals' own sprite pixels (pvthemes-1108:
     *  NEU pv_bg.png, SkyBlockPV buttons/normal.png of its default, darkmode and neu themes). Null: Default follows the theme. */
    static final int[][] EDGE = {null, {0x000000, 0x555555}, {0x1E1E1F, 0xECEDEE}, {0x000000, 0x656565}, {0x000000, 0x555555}};
    /** The selected first tab's pixel at column 10, row 2 (NEU, pv_elements v 52) or row 3 (SkyBlockPV tabs/top/selected). */
    static final int[] TAB = {0, 0x555555, 0x3C8527, 0x3C8527, 0x555555};

    static void register(Session s) {
        MenuSuite.test(s, "1104-menu-pvstyle", PvStyle1104Cases::styles);
        MenuSuite.test(s, "1110-menu-pvtweak", PvStyle1104Cases::tweak);
    }

    @SuppressWarnings("unchecked")
    private static void seed(Session c) {
        seedWith(c, PROFILE);
    }

    @SuppressWarnings("unchecked")
    private static void seedWith(Session c, String json) {
        c.onClient(mc -> {
            try {
                List<Object> parsed = (List<Object>) Mod.staticCall("profileviewer.data.SbProfile", "parseAll",
                        JsonParser.parseString(json).getAsJsonObject(), ID);
                Class<?> resCls = Mod.cls(API + "$ProfilesResult");
                Object result = resCls.getDeclaredConstructor(UUID.class, List.class, String.class, long.class)
                        .newInstance(ID, parsed, "PvStyleTest", System.currentTimeMillis());
                Constructor<?> cached = Mod.cls(API + "$Cached").getDeclaredConstructor(resCls, long.class);
                cached.setAccessible(true);
                ((Map<UUID, Object>) Mod.field(API, "CACHE")).put(ID, cached.newInstance(result, System.currentTimeMillis()));
                return null;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not seed the PV cache: " + e, e);
            }
        });
    }

    private static String style(Session c) {
        return c.onClient(mc -> String.valueOf(Mod.call(Mod.cfg(PVCFG), "getStyle")));
    }

    private static int i(Object o, String f) {
        return (Integer) Mod.field(o, f);
    }

    /** pvtweak-1110: a class level past 50 shows as it is (hover tooltip says "Healer 51"), Master floors sit under their own
     *  divider and header inside the panel, and the Recent sidebar highlights the viewed player under the player's own row. */
    static void tweak(Session c) throws Exception {
        try (SocialCases.Env env = new SocialCases.Env(c)) {
            BazaarV3Cases.setWindow(c, 1600, 900, 2);
            c.ctx().runOnClient(mc -> {
                Object cfg = Mod.cfg(PVCFG);
                Mod.call(cfg, "setRememberLastPage", false);
                Mod.call(cfg, "setStyle", Mod.enumValue(PVCFG + "$Style", "DEFAULT"));
            });
            seedXp(c);
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
            c.ctx().runOnClient(mc -> Mod.call(s, "setPage", 1));
            c.ctx().waitTicks(4);
            int[] g = c.onClient(mc -> new int[]{i(s, "contentX"), i(s, "contentY"), i(s, "contentW"), i(s, "contentH"), i(s, "panelY"), i(s, "panelH"), i(s, "sidebarX"), i(s, "sidebarW"), i(s, "sidebarY")});
            int classH = Math.max(16, Math.min(20, (g[3] - 68) / 6));
            BazaarReskinCases.cursorTo(c, new int[]{g[0] + 6, g[1] + 33 + classH, 60, 14});
            String tip = c.onClient(mc -> {
                Object t = Mod.field(s, "pendingTooltip");
                return t == null ? "" : String.valueOf(((List<?>) t).get(0));
            });
            c.check(tip.contains("Healer 51"), "the Healer bar of an 800M-XP class says '" + tip + "', expected level 51 (past 50)");
            c.note("healer tooltip: " + tip);
            // Master rows: the last row ends inside the panel and the header sits between the two groups.
            int rowH = Math.max(10, Math.min(12, (g[3] - 38) / 17));
            int endY = g[1] + 5 + 12 + 8 * rowH + 4 + 1 + 3 + 8 * rowH;
            c.check(endY <= g[1] + g[3], "the Master rows end at " + endY + ", past the panel bottom " + (g[1] + g[3]));
            BufferedImage img = BazaarReskinCases.shot(c, "pvtweak");
            c.note("pvtweak shot taken; panel content " + g[0] + "," + g[1] + " " + g[2] + "x" + g[3] + ", sidebar " + g[6] + "," + g[8] + " w" + g[7]
                    + ", Master end " + endY);
            c.check(img.getWidth() > 0, "no screenshot");
        } finally {
            c.ctx().runOnClient(mc -> {
                McCompat.setScreen(mc, null);
                ((Map<?, ?>) Mod.field(API, "CACHE")).remove(ID);
            });
        }
    }

    private static void seedXp(Session c) {
        String saved = PROFILE;
        seedWith(c, saved.replace("\"dungeon_types\"", "\"player_classes\":{\"healer\":{\"experience\":800000000},\"mage\":{\"experience\":30000000}},\"dungeon_types\""));
    }

    static void styles(Session c) throws Exception {
        Object oldStyle = c.onClient(mc -> Mod.call(Mod.cfg(PVCFG), "getStyle"));
        boolean oldRemember = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(PVCFG), "isRememberLastPage"));
        Path file = c.onClient(mc -> (Path) Mod.field(PVCFG, "CONFIG_PATH"));
        try (SocialCases.Env env = new SocialCases.Env(c)) {
            BazaarV3Cases.setWindow(c, 1600, 900, 2);
            c.ctx().runOnClient(mc -> {
                Object cfg = Mod.cfg(PVCFG);
                Mod.call(cfg, "setRememberLastPage", false);
                Mod.call(cfg, "setStyle", Mod.enumValue(PVCFG + "$Style", "DEFAULT"));
                Mod.call(cfg, "save");
            });
            seed(c);
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
            // The extra pages draw "Loading" until the profile's extras are parsed (off thread; slow under a loaded gate).
            c.waitUntil("the profile's extras are parsed", mc -> ((java.util.concurrent.Future<?>) Mod.call(Mod.call(s, "profile"), "extras")).isDone(), 400);
            c.ctx().waitTicks(3);

            // The look checks are gathered so one run shows every style (and a base jar every difference).
            List<String> bad = new java.util.ArrayList<>();
            for (int n = 0; n < ORDER.length; n++) {
                String want = ORDER[n];
                if (n > 0) {
                    int[] btn = c.onClient(mc -> ((int[]) Mod.field(s, "styleButton")).clone());
                    c.check(btn[2] > 0, "no Style button was drawn in style " + ORDER[n - 1]);
                    BazaarReskinCases.pressAt(c, btn, 0);
                    c.ctx().waitTicks(3);
                }
                String cfgStyle = style(c);
                String screenStyle = c.onClient(mc -> String.valueOf(Mod.field(s, "pvStyle")));
                String saved = Files.readString(file, StandardCharsets.UTF_8);
                c.check(want.equals(cfgStyle), "after " + n + " presses the config style is " + cfgStyle + ", expected " + want);
                c.check(want.equals(screenStyle), "the screen draws " + screenStyle + ", expected " + want);
                c.check(saved.contains("\"style\": \"" + want + "\""), "the saved file does not say style " + want);
                boolean same = c.onClient(mc -> McCompat.screen(mc) == s);
                c.check(same, "the PV is no longer the open screen in style " + want);

                int[] geo = c.onClient(mc -> new int[]{i(s, "panelX"), i(s, "panelY"), i(s, "panelW"), i(s, "panelH"),
                        i(s, "contentY"), s.width});
                // Each style's content fits: the panel sits inside the window, and the Switch/Recent column (when drawn)
                // starts right of the panel and ends inside the window.
                int[] side = c.onClient(mc -> new int[]{i(s, "sidebarX"), i(s, "sidebarW"), i(s, "sidebarH"),
                        Boolean.TRUE.equals(Mod.field(s, "sidebarVisible")) ? 1 : 0});
                look(bad, geo[0] >= 0 && geo[0] + geo[2] <= geo[5], want + ": the panel leaves the window");
                look(bad, side[3] == 0 || (side[0] >= geo[0] + geo[2] && side[0] + side[1] <= geo[5] && side[2] == geo[3]),
                        want + ": the Switch/Recent column at " + side[0] + " w" + side[1] + " h" + side[2] + " overlaps the panel or leaves the window");
                boolean icons = n > 0;
                boolean neu = n == 1;
                int rise = neu ? 28 : 22;
                if (icons) {
                    // pvthemes-1108: the originals' sizes on an 800 x 450 GUI, centered. NEU GuiProfileViewer 431 x 202;
                    // SkyBlockPV BaseWindowedPvScreen height (int) (450 * 0.65) = 292, width (int) (292 * 16 / 9) = 519.
                    int[] want4 = neu ? new int[]{184, 124, 431, 202} : new int[]{140, 79, 519, 292};
                    look(bad, geo[0] == want4[0] && geo[1] == want4[1] && geo[2] == want4[2] && geo[3] == want4[3],
                            want + ": panel " + geo[0] + "," + geo[1] + " " + geo[2] + "x" + geo[3] + ", expected "
                                    + want4[0] + "," + want4[1] + " " + want4[2] + "x" + want4[3]);
                    look(bad, geo[4] == geo[1] + 26, want + ": content starts at " + geo[4] + ", expected panelY + 26 = " + (geo[1] + 26));
                    // The Style button is top left (5,5), 20 tall, in every style (killer560, 2026-11-09).
                    int[] sb = c.onClient(mc -> ((int[]) Mod.field(s, "styleButton")).clone());
                    look(bad, sb[0] == 5 && sb[1] == 5 && sb[3] == 20,
                            want + ": the Style button is at " + sb[0] + "," + sb[1] + " " + sb[2] + "x" + sb[3]);
                } else {
                    int[] sb = c.onClient(mc -> ((int[]) Mod.field(s, "styleButton")).clone());
                    look(bad, sb[0] == 5 && sb[1] == 5 && sb[2] > 0, "Default: the Style button is at " + sb[0] + "," + sb[1]);
                    c.check(geo[4] == geo[1] + 29 + 2 * 21, "Default: content starts at " + geo[4] + ", expected panelY + 71");
                }

                BufferedImage img = BazaarReskinCases.shot(c, want.toLowerCase());
                double scale = img.getWidth() / (double) geo[5];
                int px = (int) ((geo[0] + 1) * scale + scale / 2);
                int py = (int) ((geo[1] + geo[3] / 2) * scale + scale / 2);
                int rgb = img.getRGB(px, py) & 0xFFFFFF;
                int rgb0 = img.getRGB((int) (geo[0] * scale + scale / 2), py) & 0xFFFFFF;
                c.note(want + ": panel " + geo[0] + "," + geo[1] + " " + geo[2] + "x" + geo[3] + ", content y " + geo[4]
                        + ", edge pixels " + String.format("%06X %06X", rgb0, rgb));
                if (EDGE[n] != null) {
                    look(bad, near(rgb0, EDGE[n][0]) && near(rgb, EDGE[n][1]), want + ": the panel edge is "
                            + String.format("%06X %06X", rgb0, rgb) + ", expected " + String.format("%06X %06X", EDGE[n][0], EDGE[n][1]));
                    int tx = geo[0] + (neu ? 0 : 20) + 10;
                    int ty = geo[1] - rise + (neu ? 2 : 3);
                    int tab = img.getRGB((int) (tx * scale + scale / 2), (int) (ty * scale + scale / 2)) & 0xFFFFFF;
                    look(bad, near(tab, TAB[n]), want + ": the selected tab's pixel is " + String.format("%06X", tab)
                            + ", expected " + String.format("%06X", TAB[n]));
                }

                if (icons) {
                    lookRound2(c, s, bad, n, img, scale, geo);
                }

                if (icons) {
                    // A real click on the second icon tab opens Dungeons; the first brings Basic Info back.
                    int x0 = n == 1 ? 0 : 20;
                    int step = n == 1 ? 28 : 22;
                    int w = n == 1 ? 28 : 20;
                    int ty = geo[1] - rise;
                    int tabH = 25;
                    BazaarReskinCases.pressAt(c, new int[]{geo[0] + x0 + step, ty + 4, w, tabH - 6}, 0);
                    int page = c.onClient(mc -> (Integer) Mod.field(s, "page"));
                    look(bad, page == 1, want + ": a click on the second tab left the page at " + page);
                    BufferedImage dung = BazaarReskinCases.shot(c, want.toLowerCase() + "-dungeons"); // kept for the owner
                    if (neu) {
                        // pv_dung over pv_bg: the floors box's top edge (row 16, x 154..277).
                        neuOverlay(bad, "NEU Dungeons", dung, scale, geo, "neu/pv_dung.png", 16, 154, 278);
                    }
                    BazaarReskinCases.pressAt(c, new int[]{geo[0] + x0, ty + 4, w, tabH - 6}, 0);
                    page = c.onClient(mc -> (Integer) Mod.field(s, "page"));
                    look(bad, page == 0, want + ": a click on the first tab left the page at " + page);
                }
            }

            c.check(bad.isEmpty(), bad.size() + " look differences: " + String.join("; ", bad));

            // Wraps to Default, then a right click steps back to the last one.
            int[] btn = c.onClient(mc -> ((int[]) Mod.field(s, "styleButton")).clone());
            BazaarReskinCases.pressAt(c, btn, 0);
            c.check("DEFAULT".equals(style(c)), "the Style button did not wrap to Default: " + style(c));
            btn = c.onClient(mc -> ((int[]) Mod.field(s, "styleButton")).clone());
            BazaarReskinCases.pressAt(c, btn, 1);
            c.check("SBPV_CLEAR".equals(style(c)), "a right click did not step back to SBPV_CLEAR: " + style(c));

            // A restart: the file read again keeps it.
            String reloaded = c.onClient(mc -> {
                Mod.staticCall(PVCFG, "load");
                return String.valueOf(Mod.call(Mod.cfg(PVCFG), "getStyle"));
            });
            c.check("SBPV_CLEAR".equals(reloaded), "after a reload the style is " + reloaded);
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

    /** pvthemes-1108 round 2: the SkyBlockPv styles draw text with no shadow (NEU still has one), NEU's Basic page lies
     *  over pv_basic.png, and every icon style's item slot is that mod's own slot sprite. */
    private static void lookRound2(Session c, Screen s, List<String> bad, int n, BufferedImage img, double scale, int[] geo)
            throws Exception {
        String want = ORDER[n];
        boolean neu = n == 1;
        int[] cg = c.onClient(mc -> new int[]{i(s, "contentX"), i(s, "contentY"), i(s, "contentW"), i(s, "contentH")});
        int cx = cg[0], cy = cg[1], cw = cg[2], ch = cg[3];
        // The stat lines (SB Level, Fairy Souls, Purse, Bank, Networth): NEU's stats box, else SkyBlockPV's Info box.
        // Round 3: SkyBlockPV's own main page puts them in its Info label (title bar 20 high, lines from 7 into the box).
        int sx = neu ? geo[0] + 132 : geo[0] + 10;
        int sy = neu ? geo[1] + 53 : cy + 27;
        if (!neu) {
            BufferedImage title = res("sbpv/" + (n == 2 ? "light" : n == 3 ? "dark" : "clear") + "/title.png");
            int got = gp(img, scale, geo[0] + 5, cy + 10), exp = title == null ? -1 : blend(title.getRGB(25, 25), gp(img, scale, geo[0] + 5, cy - 4));
            look(bad, near(got, exp), want + ": Info title bar at x + 5 is " + String.format("%06X, its sprite gives %06X", got, exp));
        }
        double[] sh = shadowShare(img, scale, sx, sy, neu ? 85 : 110, Math.min(55, cy + ch - sy));
        c.note(want + ": text " + (int) sh[1] + " px, shadowed share " + String.format("%.2f", sh[0]));
        look(bad, sh[1] >= 30, want + ": no stat text found at " + sx + "," + sy);
        look(bad, neu ? sh[0] > 0.15 : sh[0] < 0.05, want + ": text shadow share " + String.format("%.2f", sh[0])
                + (neu ? ", NEU draws a shadow" : ", SkyBlockPV draws none"));
        if (neu) {
            // pv_basic over pv_bg: the skills box's top edge (row 15, x 232..412).
            neuOverlay(bad, "NEU Basic", img, scale, geo, "neu/pv_basic.png", 15, 232, 413);
        }

        // Inventories page: the first main-grid slot (ProfileViewerScreen.drawInventories' layout) against the sprite.
        c.ctx().runOnClient(mc -> Mod.call(s, "setPage", 2));
        c.ctx().waitTicks(5);
        BufferedImage inv = BazaarReskinCases.shot(c, want.toLowerCase() + "-inventory");
        // Round 3: SkyBlockPV's Inventory view is one centered row, armor 2 x 4 + 10 px + the 9 x 4 grid, 20 px cells, 2 px in.
        int mainX = geo[0] + (geo[2] - 238) / 2 + 56, top = geo[1] + (geo[3] - 84) / 2 + 2;
        BufferedImage sprite = neu ? null : res("sbpv/" + (n == 2 ? "light" : n == 3 ? "dark" : "clear") + "/slot.png");
        if (neu) {
            // Round 3: NEU's Inventories page lies over pv_invs.png (the weapon/armor boxes' top edge, row 6).
            neuOverlay(bad, "NEU Inventories", inv, scale, geo, "neu/pv_invs.png", 6, 139, 212);
            // ... and its Pets page over pv_pets.png (the white pet frame's top edge, row 45).
            c.ctx().runOnClient(mc -> Mod.call(s, "setPage", 3));
            c.ctx().waitTicks(5);
            BufferedImage pets = BazaarReskinCases.shot(c, "neu-pets");
            neuOverlay(bad, "NEU Pets", pets, scale, geo, "neu/pv_pets.png", 45, 213, 292);
            // The seed's one (active) pet sits in NEU's first grid cell (20, 31), its cell tinted orange (pet_sel.png).
            BufferedImage sel = res("neu/pet_sel.png"), under = res("neu/panel.png");
            if (sel == null || under == null) {
                look(bad, false, "NEU Pets: no pet_sel.png in the jar");
            } else {
                int got = gp(pets, scale, geo[0] + 20, geo[1] + 31), exp = blend(sel.getRGB(0, 0), under.getRGB(20, 31) & 0xFFFFFF);
                look(bad, near(got, exp), "NEU Pets: first pet cell corner " + String.format("%06X, expected %06X", got, exp));
            }
            // ... and Collections: the seed's 1000 Wheat (tiers 50/1000, injected below, the tier list is a download) maxes
            // the first cell of NEU's grid (54, 31), filled orange (pet_sel.png) over its pv_elements cell.
            BufferedImage cols = collectionsShot(c, s, "neu-collections");
            BufferedImage tab = res("neu/inv_tab.png");
            if (sel == null || under == null || tab == null) {
                look(bad, false, "NEU Collections: no inv_tab.png in the jar");
            } else {
                int got = gp(cols, scale, geo[0] + 54, geo[1] + 31);
                int exp = blend(sel.getRGB(0, 0), blend(tab.getRGB(0, 0), under.getRGB(54, 31) & 0xFFFFFF));
                look(bad, near(got, exp), "NEU Collections: first collection cell corner " + String.format("%06X, expected %06X", got, exp));
            }
            // Round 4: NEU's Mining page (plain pv_bg): the HOTM view's selector cell turned over at 226, 18, and the
            // seed's found Jade crystal's label in NEU's green (55FF55) from 20, 101.
            c.ctx().runOnClient(mc -> Mod.call(s, "setPage", 5));
            c.ctx().waitTicks(5);
            BufferedImage mine = BazaarReskinCases.shot(c, "neu-mining");
            BufferedImage on = res("neu/inv_tab_on.png");
            if (on == null || under == null) {
                look(bad, false, "NEU Mining: no inv_tab_on.png in the jar");
            } else {
                int got = gp(mine, scale, geo[0] + 226, geo[1] + 18), exp = blend(on.getRGB(0, 0), under.getRGB(226, 18) & 0xFFFFFF);
                look(bad, near(got, exp), "NEU Mining: HOTM cell corner " + String.format("%06X, expected %06X", got, exp));
            }
            int green = 0;
            for (int gx = 20; gx < 50; gx++) {
                for (int gy = 101; gy < 109; gy++) {
                    green += near(gp(mine, scale, geo[0] + gx, geo[1] + gy), 0x55FF55) ? 1 : 0;
                }
            }
            look(bad, green >= 10, "NEU Mining: " + green + " Jade-green label pixels at 20, 101 (want 10+)");
            // NEU's Crimson Isle page: pv_crimson_isle_page's boxes, and the red "Kuudra Stats" heading at y 14.
            c.ctx().runOnClient(mc -> Mod.call(s, "setPage", 7));
            c.ctx().waitTicks(5);
            BufferedImage crim = BazaarReskinCases.shot(c, "neu-crimson");
            neuOverlay(bad, "NEU Crimson", crim, scale, geo, "neu/pv_crimson.png", 100, 40, 100);
            int red = 0;
            for (int gx = 40; gx < 118; gx++) {
                for (int gy = 14; gy < 22; gy++) {
                    red += near(gp(crim, scale, geo[0] + gx, geo[1] + gy), 0xFF5555) ? 1 : 0;
                }
            }
            look(bad, red >= 10, "NEU Crimson: " + red + " red Kuudra Stats heading pixels at y 14 (want 10+)");
            // NEU's Museum page: the museum answer goes straight into the page's aux cache (no network here), then
            // pv_museum's boxes, the Weapons cell turned over at 16, 172 and the gold "Museum Value" at 21, 25.
            c.ctx().runOnClient(mc -> {
                try {
                    Class<?> ar = Mod.cls("profileviewer.api.ProfileViewerApi$AuxResult");
                    Object answer = ar.getDeclaredConstructor(com.google.gson.JsonObject.class, String.class, long.class).newInstance(
                            JsonParser.parseString("{\"000000000000000000000000000000a1\":{\"value\":2500000,\"items\":{},\"special\":[]}}")
                                    .getAsJsonObject(), "test", 0L);
                    ((Map<String, Object>) Mod.field(Mod.field(s, "extraPages"), "aux")).put("MUSEUM:p1", CompletableFuture.completedFuture(answer));
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
                Mod.call(s, "setPage", 8);
            });
            c.ctx().waitTicks(5);
            BufferedImage mus = BazaarReskinCases.shot(c, "neu-museum");
            neuOverlay(bad, "NEU Museum", mus, scale, geo, "neu/pv_museum.png", 64, 30, 120);
            if (on != null && under != null) {
                int got = gp(mus, scale, geo[0] + 16, geo[1] + 172), exp = blend(on.getRGB(0, 0), under.getRGB(16, 172) & 0xFFFFFF);
                look(bad, near(got, exp), "NEU Museum: Weapons cell corner " + String.format("%06X, expected %06X", got, exp));
            }
            int gold = 0;
            for (int gx = 21; gx < 80; gx++) {
                for (int gy = 25; gy < 33; gy++) {
                    gold += near(gp(mus, scale, geo[0] + gx, geo[1] + gy), 0xFFAA00) ? 1 : 0;
                }
            }
            look(bad, gold >= 10, "NEU Museum: " + gold + " gold Museum Value pixels at 21, 25 (want 10+)");
            // NEU's Bestiary page: a two-category table goes into the download's future; Zombie (100 kills) is maxed, so the
            // first cell's tier reads gold at 29, 73 and the first category cell at 30, 10 is the selected one.
            BufferedImage bes = remoteShot(c, s, "BESTIARY", "parseBestiary", "{\"brackets\":{\"1\":[10,100]},"
                    + "\"island\":{\"name\":\"Private Island\",\"icon\":{\"item\":\"GRASS\"},\"mobs\":[{\"name\":\"Zombie\",\"cap\":100,\"bracket\":1,\"mobs\":[\"zombie_1\"],\"item\":\"ROTTEN_FLESH\"}]},"
                    + "\"hub\":{\"name\":\"Hub\",\"icon\":{\"item\":\"BRICK\"},\"mobs\":[{\"name\":\"Crypt Ghoul\",\"cap\":100,\"bracket\":1,\"mobs\":[\"ghoul_1\"],\"item\":\"BONE\"}]}}",
                    6, "neu-bestiary");
            neuOverlay(bad, "NEU Bestiary", bes, scale, geo, "neu/pv_bestiary.png", 120, 290, 390);
            if (on != null && under != null) {
                int got = gp(bes, scale, geo[0] + 30, geo[1] + 10), exp = blend(on.getRGB(0, 0), under.getRGB(30, 10) & 0xFFFFFF);
                look(bad, near(got, exp), "NEU Bestiary: first category cell corner " + String.format("%06X, expected %06X", got, exp));
            }
            int tierGold = 0;
            for (int gx = 29; gx < 50; gx++) {
                for (int gy = 73; gy < 81; gy++) {
                    tierGold += near(gp(bes, scale, geo[0] + gx, geo[1] + gy), 0xFFAA00) ? 1 : 0;
                }
            }
            look(bad, tierGold >= 3, "NEU Bestiary: " + tierGold + " gold tier pixels under Zombie at 29, 73 (want 3+)");
            // NEU's Rift page: the seed has Rift data but no inventory, so RiftPage's red "Inventory API not enabled!" is
            // centered at 61 over pv_rift.
            c.ctx().runOnClient(mc -> Mod.call(s, "setPage", 9));
            c.ctx().waitTicks(5);
            BufferedImage rift = BazaarReskinCases.shot(c, "neu-rift");
            neuOverlay(bad, "NEU Rift", rift, scale, geo, "neu/pv_rift.png", 120, 340, 416);
            int riftRed = 0;
            for (int gx = 140; gx < 290; gx++) {
                for (int gy = 61; gy < 69; gy++) {
                    riftRed += near(gp(rift, scale, geo[0] + gx, geo[1] + gy), 0xFF5555) ? 1 : 0;
                }
            }
            look(bad, riftRed >= 20, "NEU Rift: " + riftRed + " red 'Inventory API not enabled!' pixels at 61 (want 20+)");
            // NEU's Garden page (Farming): a garden answer goes into ExtraPages' aux cache for the seed profile, so GardenPage's
            // yellow "Crop Milestones" header is drawn at 26 + 70, 15 over garden/background.
            c.ctx().runOnClient(mc -> {
                try {
                    java.lang.reflect.Field ef = s.getClass().getDeclaredField("extraPages");
                    ef.setAccessible(true);
                    Object ep = ef.get(s);
                    java.lang.reflect.Field af = ep.getClass().getDeclaredField("aux");
                    af.setAccessible(true);
                    Class<?> ar = Class.forName("com.killer560.hub.profileviewer.api.ProfileViewerApi$AuxResult", true, s.getClass().getClassLoader());
                    Object res = ar.getDeclaredConstructor(com.google.gson.JsonObject.class, String.class, long.class).newInstance(JsonParser.parseString(
                            "{\"unlocked_plots_ids\":[\"beginner_1\"],\"crop_upgrade_levels\":{\"WHEAT\":9},\"resources_collected\":{\"WHEAT\":1000},"
                            + "\"garden_experience\":100,\"commission_data\":{\"visits\":{\"adventurer\":3},\"completed\":{\"adventurer\":2},"
                            + "\"unique_npcs_served\":1},\"composter_data\":{\"upgrades\":{\"speed\":4}}}").getAsJsonObject(), "test", 0L);
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> aux = (java.util.Map<String, Object>) af.get(ep);
                    aux.put("GARDEN:p1", CompletableFuture.completedFuture(res));
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
                Mod.call(s, "setPage", 10);
            });
            c.ctx().waitTicks(5);
            BufferedImage gar = BazaarReskinCases.shot(c, "neu-garden");
            neuOverlay(bad, "NEU Garden", gar, scale, geo, "neu/pv_garden.png", 70, 340, 400);
            int yellow = 0;
            for (int gx = 40; gx < 150; gx++) {
                for (int gy = 15; gy < 23; gy++) {
                    yellow += near(gp(gar, scale, geo[0] + gx, geo[1] + gy), 0xFFFF55) ? 1 : 0;
                }
            }
            look(bad, yellow >= 20, "NEU Garden: " + yellow + " yellow 'Crop Milestones' pixels at 15 (want 20+)");
        } else if (sprite == null) {
            look(bad, false, want + ": no slot sprite in the jar");
        } else {
            int bg = gp(inv, scale, mainX - 2, top + 9);
            int sw = sprite.getWidth();
            int[][] pairs = {{0, 0, 0, 0}, {1, 1, 1, 1}, {9, 9, sw / 2, sw / 2}};
            for (int[] q : pairs) {
                int sp = sprite.getRGB(q[2], q[3]);
                if ((sp >>> 24) == 0) {
                    continue;
                }
                int got = gp(inv, scale, mainX + q[0], top + q[1]), exp = blend(sp, bg);
                look(bad, near(got, exp), want + ": slot pixel +" + q[0] + "," + q[1] + " is " + String.format("%06X", got)
                        + ", the slot sprite gives " + String.format("%06X", exp));
            }
            // Round 3: SkyBlockPV's Pets page puts the seed's active pet in the first 22 px slot at the panel's x + 5, at the
            // content top, the slot tinted green (PvColors.GREEN); the 5 px gap after it shows the panel.
            c.ctx().runOnClient(mc -> Mod.call(s, "setPage", 3));
            c.ctx().waitTicks(5);
            BufferedImage pets = BazaarReskinCases.shot(c, want.toLowerCase() + "-pets");
            int px = geo[0] + 5, sp = sprite.getRGB(1, 1);
            int tinted = (sp & 0xFF000000) | (((sp >> 16) & 0xFF) * 0x55 / 255) << 16 | (sp & 0xFF00) | ((sp & 0xFF) * 0x55 / 255);
            int got = gp(pets, scale, px + 1, cy + 1), exp = blend(tinted, gp(pets, scale, px + 24, cy + 11));
            look(bad, near(got, exp), want + ": active pet slot +1,+1 is " + String.format("%06X", got)
                    + ", the green-tinted slot gives " + String.format("%06X", exp));
            // ... and its Collections page: the maxed Wheat entry alone, centered, its 91 px bar (bar_foreground_maxed.png,
            // gold mid-bar E8B923) at item 16 + 5 in, 12 below the content top.
            BufferedImage cols = collectionsShot(c, s, want.toLowerCase() + "-collections");
            int w0 = 21 + c.onClient(mc -> Math.max(mc.font.width("Wheat: 1,000"), 94 + mc.font.width("Maxed")));
            int bx = geo[0] + 10 + (geo[2] - 20 - w0) / 2 + 21;
            int bar = gp(cols, scale, bx + 45, cy + 14);
            look(bad, near(bar, 0xE8B923), want + ": Wheat's maxed bar middle is " + String.format("%06X", bar) + ", expected E8B923");
            // ... and its Dungeons page (DungeonScreen): the seed's Catacombs (600M XP, past 50) is the first Dungeon Levels
            // line, its maxed 91 px bar 10 under it, so the bar's middle row (content top + 27 + 12) reads gold E8B923.
            c.ctx().runOnClient(mc -> Mod.call(s, "setPage", 1));
            c.ctx().waitTicks(5);
            BufferedImage dun = BazaarReskinCases.shot(c, want.toLowerCase() + "-sbpv-dungeons");
            int gold = 0;
            for (int gx = geo[0]; gx < geo[0] + geo[2]; gx++) {
                gold += near(gp(dun, scale, gx, cy + 39), 0xE8B923) ? 1 : 0;
            }
            look(bad, gold >= 40, want + ": " + gold + " gold Catacombs bar pixels on the Dungeons page's row " + (cy + 39) + " (want 40+)");
        }
        c.ctx().runOnClient(mc -> Mod.call(s, "setPage", 0));
        c.ctx().waitTicks(3);
    }

    /** A page shot with one of ExtraTables' downloads (field, its parser) answered by the given JSON for the shot. */
    private static BufferedImage remoteShot(Session c, Screen s, String field, String parser, String json, int page, String name) throws Exception {
        Object remote = c.onClient(mc -> Mod.field("profileviewer.data.ExtraTables", field));
        java.lang.reflect.Field future = remote.getClass().getDeclaredField("future");
        future.setAccessible(true);
        Object old = future.get(remote);
        Object parsed = c.onClient(mc -> Mod.staticCall("profileviewer.data.ExtraTables", parser, JsonParser.parseString(json).getAsJsonObject()));
        try {
            synchronized (remote) {
                future.set(remote, CompletableFuture.completedFuture(parsed));
            }
            c.ctx().runOnClient(mc -> Mod.call(s, "setPage", page));
            c.ctx().waitTicks(5);
            return BazaarReskinCases.shot(c, name);
        } finally {
            synchronized (remote) {
                future.set(remote, old);
            }
        }
    }

    /** Collections (page 4) with a two-tier Wheat collection injected for the shot (the tier list is a download). */
    private static BufferedImage collectionsShot(Session c, Screen s, String name) throws Exception {
        Object remote = c.onClient(mc -> Mod.field("profileviewer.data.ExtraTables", "COLLECTIONS"));
        java.lang.reflect.Field future = remote.getClass().getDeclaredField("future");
        future.setAccessible(true);
        Object old = future.get(remote);
        Object cats = c.onClient(mc -> Mod.staticCall("profileviewer.data.ExtraTables", "parseCollections", JsonParser.parseString("{\"collections\":{\"FARMING\":{\"name\":\"Farming\",\"items\":{\"WHEAT\":{\"name\":\"Wheat\","
                        + "\"maxTiers\":2,\"tiers\":[{\"tier\":1,\"amountRequired\":50},{\"tier\":2,\"amountRequired\":1000}]}}}}}").getAsJsonObject()));
        BufferedImage cols;
        try {
            synchronized (remote) {
                future.set(remote, java.util.concurrent.CompletableFuture.completedFuture(cats));
            }
            c.ctx().runOnClient(mc -> Mod.call(s, "setPage", 4));
            c.ctx().waitTicks(5);
            cols = BazaarReskinCases.shot(c, name);
        } finally {
            synchronized (remote) {
                future.set(remote, old);
            }
        }
        return cols;
    }

    /** Up to 8 pixels on one row where NEU's page texture changes pv_bg must show the page texture over pv_bg. */
    private static void neuOverlay(List<String> bad, String what, BufferedImage img, double scale, int[] geo, String page,
                                   int row, int x0, int x1) throws IOException {
        BufferedImage over = res(page), under = res("neu/panel.png");
        if (over == null || under == null) {
            look(bad, false, what + ": no " + page + " in the jar");
            return;
        }
        int checked = 0, wrong = 0;
        String first = "";
        for (int x = x0; x < x1 && checked < 8; x++) {
            int bgPx = under.getRGB(x, row) & 0xFFFFFF, exp = blend(over.getRGB(x, row), bgPx);
            if (near(exp, bgPx)) {
                continue;
            }
            checked++;
            int got = gp(img, scale, geo[0] + x, geo[1] + row);
            if (!near(got, exp)) {
                wrong++;
                if (first.isEmpty()) {
                    first = " (x " + x + ": " + String.format("%06X, expected %06X", got, exp) + ")";
                }
            }
        }
        look(bad, checked > 0 && wrong == 0, what + ": " + wrong + " of " + checked + " page-texture pixels differ" + first);
    }

    /** {shadowed share, text pixels} in a GUI rect: a text pixel is far from the rect's commonest color; it is shadowed
     *  when the pixel one down-right is vanilla's shadow of it ((c & 0xFCFCFC) >> 2) and neither text nor background. */
    private static double[] shadowShare(BufferedImage img, double scale, int x0, int y0, int w, int h) {
        Map<Integer, Integer> freq = new java.util.HashMap<>();
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                freq.merge(gp(img, scale, x, y), 1, Integer::sum);
            }
        }
        int bg = freq.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0);
        int text = 0, shadowed = 0;
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                int p = gp(img, scale, x, y);
                if (!far(p, bg)) {
                    continue;
                }
                text++;
                int q = gp(img, scale, x + 1, y + 1);
                if (!near(q, p) && !near(q, bg) && near(q, (p & 0xFCFCFC) >> 2)) {
                    shadowed++;
                }
            }
        }
        return new double[]{text == 0 ? 0 : shadowed / (double) text, text};
    }

    private static int gp(BufferedImage img, double scale, int gx, int gy) {
        return img.getRGB((int) (gx * scale + scale / 2), (int) (gy * scale + scale / 2)) & 0xFFFFFF;
    }

    private static int blend(int argb, int bg) {
        double a = (argb >>> 24) / 255.0;
        int out = 0;
        for (int sh = 0; sh <= 16; sh += 8) {
            out |= (int) Math.round(((argb >> sh) & 0xFF) * a + ((bg >> sh) & 0xFF) * (1 - a)) << sh;
        }
        return out;
    }

    private static BufferedImage res(String path) throws IOException {
        try (InputStream in = Mod.cls("profileviewer.screen.ProfileViewerScreen")
                .getResourceAsStream("/assets/killer560smod/textures/gui/sprites/pv/" + path)) {
            return in == null ? null : ImageIO.read(in);
        }
    }

    private static boolean far(int a, int b) {
        for (int sh = 0; sh <= 16; sh += 8) {
            if (Math.abs(((a >> sh) & 0xFF) - ((b >> sh) & 0xFF)) > 40) {
                return true;
            }
        }
        return false;
    }

    private static void look(List<String> bad, boolean ok, String what) {
        if (!ok) {
            bad.add(what);
        }
    }

    private static boolean near(int a, int b) {
        for (int sh = 0; sh <= 16; sh += 8) {
            if (Math.abs(((a >> sh) & 0xFF) - ((b >> sh) & 0xFF)) > 8) {
                return false;
            }
        }
        return true;
    }
}

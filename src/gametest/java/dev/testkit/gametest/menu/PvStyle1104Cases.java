package dev.testkit.gametest.menu;

import com.google.gson.JsonParser;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.screens.Screen;

import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
            + "\"dungeons\":{\"dungeon_types\":{\"catacombs\":{\"experience\":180000000}}}}}}]}";

    static final String[] ORDER = {"DEFAULT", "NEU", "SBPV_LIGHT", "SBPV_DARK", "SBPV_CLEAR"};
    /** The panel's left edge at x + 0 and x + 1 (mid height), per style: the originals' own sprite pixels (pvthemes-1108:
     *  NEU pv_bg.png, SkyBlockPV buttons/normal.png of its default, darkmode and neu themes). Null: Default follows the theme. */
    static final int[][] EDGE = {null, {0x000000, 0x555555}, {0x1E1E1F, 0xECEDEE}, {0x000000, 0x656565}, {0x000000, 0x555555}};
    /** The selected first tab's pixel at column 10, row 2 (NEU, pv_elements v 52) or row 3 (SkyBlockPV tabs/top/selected). */
    static final int[] TAB = {0, 0x555555, 0x3C8527, 0x3C8527, 0x555555};

    static void register(Session s) {
        MenuSuite.test(s, "1104-menu-pvstyle", PvStyle1104Cases::styles);
    }

    @SuppressWarnings("unchecked")
    private static void seed(Session c) {
        c.onClient(mc -> {
            try {
                List<Object> parsed = (List<Object>) Mod.staticCall("profileviewer.data.SbProfile", "parseAll",
                        JsonParser.parseString(PROFILE).getAsJsonObject(), ID);
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
                    // NEU: the Style dropdown 106 right of the profile one, 3 under the panel; SkyBlockPV: top left, 20 tall.
                    int[] sb = c.onClient(mc -> ((int[]) Mod.field(s, "styleButton")).clone());
                    boolean at = neu ? sb[0] == geo[0] + 106 && sb[1] == geo[1] + geo[3] + 3 && sb[2] == 100 && sb[3] == 20
                            : sb[0] == 5 && sb[1] == 5 && sb[3] == 20;
                    look(bad, at, want + ": the Style button is at " + sb[0] + "," + sb[1] + " " + sb[2] + "x" + sb[3]);
                } else {
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
                    // A real click on the second icon tab opens Dungeons; the first brings Basic Info back.
                    int x0 = n == 1 ? 0 : 20;
                    int step = n == 1 ? 28 : 22;
                    int w = n == 1 ? 28 : 20;
                    int ty = geo[1] - rise;
                    int tabH = 25;
                    BazaarReskinCases.pressAt(c, new int[]{geo[0] + x0 + step, ty + 4, w, tabH - 6}, 0);
                    int page = c.onClient(mc -> (Integer) Mod.field(s, "page"));
                    look(bad, page == 1, want + ": a click on the second tab left the page at " + page);
                    BazaarReskinCases.shot(c, want.toLowerCase() + "-dungeons"); // kept for the owner
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

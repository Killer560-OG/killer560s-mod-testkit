package dev.testkit.gametest.menu;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.Machine;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.ui.ThemeAssetFeed;
import net.minecraft.client.gui.screens.Screen;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;

import static dev.testkit.gametest.menu.PvStyle1104Cases.*;

/**
 * pv-styles-exact-1124k: NEU Classic's panorama behind the Basic page's player (BasicPage: the box at 23, 44, 81 x 108,
 * turning on its own and dragged by a held mouse button). The pictures are the mod's on-demand {@code pv-panorama} group
 * (not in the jar; the published pv-panorama-1 release, served by ThemeAssetFeed). 1124-menu-pvexact-neupano checks:
 * <ul>
 * <li>with corrupt downloads (sha256 rejected, nothing kept) the page opens and draws, and the box shows the page's own
 * plain background;</li>
 * <li>with the feed serving the "unknown" set, its six files are downloaded, byte-equal to the release's, decoded,
 * uploaded and drawn: the box's pixels change from the plain shot and turn over time;</li>
 * <li>a real press in the box and a release 30 px right adds 30 degrees; a status in mode mining_3 picks that set.</li>
 * </ul>
 */
final class PvPanorama1124Cases {
    private PvPanorama1124Cases() {
    }

    static final String TA = "mainmenu.ThemeAssets";

    static void register(Session s) {
        MenuSuite.test(s, "1124-menu-pvexact-neupano", PvPanorama1124Cases::run);
    }

    private static Screen open(Session c) {
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
        c.ctx().waitTicks(10);
        return s;
    }

    private static String pano(Session c, Screen s) {
        return c.onClient(mc -> String.valueOf(Mod.call(s, "panoramaForTest")));
    }

    /** Mean absolute RGB difference over the box's left strip (clear of the model and the name strip), in GUI px. */
    private static double diff(BufferedImage a, BufferedImage b, double scale, int gx, int gy) {
        long sum = 0;
        int n = 0;
        for (int y = gy + 66; y < gy + 148; y += 2) {
            for (int x = gx + 25; x < gx + 40; x++) {
                int p = gp(a, scale, x, y), q = gp(b, scale, x, y);
                sum += Math.abs((p >> 16 & 255) - (q >> 16 & 255)) + Math.abs((p >> 8 & 255) - (q >> 8 & 255)) + Math.abs((p & 255) - (q & 255));
                n++;
            }
        }
        return sum / (double) n;
    }

    private static void run(Session c) throws Exception {
        Path src = Machine.modSource() == null ? null : Machine.modSource().resolve("theme-assets/pv-panorama");
        c.check(src != null && Files.isRegularFile(src.resolve("pvpano_unknown_0.webp")),
                "no mod checkout with theme-assets/pv-panorama (TESTKIT_MOD_SOURCE)");
        Object oldStyle = c.onClient(mc -> Mod.call(Mod.cfg(PVCFG), "getStyle"));
        boolean oldRemember = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(PVCFG), "isRememberLastPage"));
        Path[] placed = new Path[6];
        java.util.List<Path> extraPlaced = new java.util.ArrayList<>();
        try (SocialCases.Env env = new SocialCases.Env(c)) {
            BazaarV3Cases.setWindow(c, 1600, 900, 2);
            c.ctx().runOnClient(mc -> {
                Object cfg = Mod.cfg(PVCFG);
                Mod.call(cfg, "setRememberLastPage", false);
                Mod.call(cfg, "setStyle", Mod.enumValue(PVCFG + "$Style", "NEU"));
                Mod.call(cfg, "save");
            });
            for (int i = 0; i < 6; i++) {
                final int k = i;
                placed[i] = c.onClient(mc -> (Path) Mod.staticCall(TA, "fileForTest", "pvpano_unknown_" + k + ".webp"));
                c.check(placed[i] != null, "pvpano_unknown_" + i + ".webp is not in the jar's manifest");
                Files.deleteIfExists(placed[i]);
            }
            c.onClient(mc -> {
                try {
                    Mod.staticCall(TA, "resetForTest");
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                return null;
            });
            seedWith(c, PvExact1124Cases.EXACT_PROFILE);
            ThemeAssetFeed.start(); // the published pv-panorama-1 release, served from the mod checkout's theme-assets/pv-panorama
            for (int i = 0; i < 6; i++) {
                ThemeAssetFeed.CORRUPT.add("pvpano_unknown_" + i + ".webp");
                ThemeAssetFeed.CORRUPT.add("pvpano_mining_3_" + i + ".webp");
            }

            // 1. Every download arrives corrupt (rejected by the sha256 check): the page draws, the box is plain.
            Screen s = open(c);
            int[] geo = c.onClient(mc -> new int[]{i(s, "panelX"), i(s, "panelY"), s.width});
            String st0 = pano(c, s);
            c.note("no files: " + st0);
            c.check(st0.startsWith("unknown waiting"), "with no files the panorama reads " + st0 + ", expected the unknown set waiting");
            int downloads = c.onClient(mc -> (Integer) Mod.staticCall(TA, "downloadsForTest"));
            String state0 = c.onClient(mc -> String.valueOf(Mod.staticCall(TA, "stateForTest", "pvpano_unknown_0.webp")));
            int rejected = c.onClient(mc -> (Integer) Mod.staticCall(TA, "rejectedForTest"));
            c.check(downloads >= 1 && rejected >= 1, downloads + " downloads, " + rejected + " rejected: the published release was not asked for the corrupt files");
            c.check("FAILED".equals(state0), "unknown_0 is " + state0 + ", expected FAILED (corrupt download rejected)");
            c.check(!Files.exists(placed[0]), "a rejected file was kept in the cache");
            BufferedImage plain = BazaarReskinCases.shot(c, "neupano-plain");
            double scale = plain.getWidth() / (double) geo[2];
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));

            // 2. The unknown set placed in the sandbox store: decoded, uploaded and drawn.
            ThemeAssetFeed.CORRUPT.clear();
            for (int i = 0; i < 6; i++) {
                ThemeAssetFeed.CORRUPT.add("pvpano_mining_3_" + i + ".webp"); // step 5's set stays waiting
            }
            c.onClient(mc -> {
                try {
                    Mod.staticCall(TA, "resetForTest");
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                return null;
            });
            Screen s2 = open(c);
            c.waitUntil("the panorama is up", mc -> String.valueOf(Mod.call(s2, "panoramaForTest")).startsWith("unknown ready"), 200);
            c.ctx().waitTicks(10);
            String st1 = pano(c, s2);
            c.note("placed: " + st1);
            int drawn = Integer.parseInt(st1.replaceAll(".*drawn=(\\d+).*", "$1"));
            c.check(drawn > 3, "the panorama drew " + drawn + " frames");
            BufferedImage a = BazaarReskinCases.shot(c, "neupano-drawn");
            double d0 = diff(plain, a, scale, geo[0], geo[1]);
            c.note("box difference from the plain page: " + String.format("%.1f", d0));
            c.check(d0 > 20, "the box looks like the plain page (mean difference " + String.format("%.1f", d0) + ")");
            int downloads2 = c.onClient(mc -> (Integer) Mod.staticCall(TA, "downloadsForTest"));
            c.check(downloads2 == 6, downloads2 + " downloads for the unknown set, expected its six files from the feed");
            for (int i = 0; i < 6; i++) {
                c.check(Files.mismatch(placed[i], src.resolve("pvpano_unknown_" + i + ".webp")) == -1L, "downloaded unknown_" + i + " differs from the release file");
            }

            // 3. It turns on its own, and a drag of 30 px turns it 30 degrees more.
            Object p = c.onClient(mc -> Mod.field(s2, "neuPanorama"));
            float r0 = c.onClient(mc -> (Float) Mod.field(p, "rotation"));
            c.ctx().waitTicks(20);
            float r1 = c.onClient(mc -> (Float) Mod.field(p, "rotation"));
            c.check(r1 != r0, "the panorama did not turn on its own (" + r0 + ")");
            int bx = geo[0] + 63, by = geo[1] + 98;
            c.ctx().runOnClient(mc -> Mod.call(s2, "mouseClicked", new net.minecraft.client.input.MouseButtonEvent(bx, by,
                    new net.minecraft.client.input.MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0)), false));
            c.ctx().waitTicks(2);
            boolean dragging = c.onClient(mc -> (Integer) Mod.field(p, "clickedX") == bx);
            float r2 = c.onClient(mc -> (Float) Mod.field(p, "rotation"));
            c.ctx().runOnClient(mc -> Mod.call(s2, "mouseReleased", new net.minecraft.client.input.MouseButtonEvent(bx + 30, by,
                    new net.minecraft.client.input.MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0))));
            float r3 = c.onClient(mc -> (Float) Mod.field(p, "rotation"));
            float turned = ((r3 - r2) % 360 + 360) % 360;
            c.note("rotation " + r0 + " -> " + r1 + ", drag " + r2 + " -> " + r3);
            c.check(dragging, "a press in the box did not start a drag");
            c.check(Math.abs(turned - 30) < 1.5f, "a 30 px drag turned it " + turned + " degrees");
            BufferedImage b = BazaarReskinCases.shot(c, "neupano-turned");
            double d1 = diff(a, b, scale, geo[0], geo[1]);
            c.note("box difference after the turn: " + String.format("%.1f", d1));
            c.check(d1 > 5, "the box did not change after turning (" + String.format("%.1f", d1) + ")");

            // 4. A hub status shows the hub set of the current SkyBlock half day (both placed): a shot for the eye (horizon level).
            for (String set : new String[]{"hub_day", "hub_night"}) {
                for (int i = 0; i < 6; i++) {
                    final String n = "pvpano_" + set + "_" + i + ".webp";
                    Path f = c.onClient(mc -> (Path) Mod.staticCall(TA, "fileForTest", n));
                    Files.createDirectories(f.getParent());
                    Files.copy(src.resolve(n), f, StandardCopyOption.REPLACE_EXISTING);
                    extraPlaced.add(f);
                }
            }
            status(c, "hub");
            c.waitUntil("the hub panorama is up", mc -> String.valueOf(Mod.call(s2, "panoramaForTest")).matches("hub_(day|night) ready.*"), 200);
            c.ctx().waitTicks(5);
            c.note("hub status: " + pano(c, s2));
            BazaarReskinCases.shot(c, "neupano-hub");

            // 5. A status in mode mining_3 shows that set (not placed: waiting, the unknown textures released).
            status(c, "mining_3");
            c.ctx().waitTicks(5);
            String st2 = pano(c, s2);
            c.note("mining_3 status: " + st2);
            c.check(st2.startsWith("mining_3 waiting"), "with a mining_3 status the panorama reads " + st2);
        } finally {
            c.ctx().runOnClient(mc -> {
                McCompat.setScreen(mc, null);
                Object cfg = Mod.cfg(PVCFG);
                Mod.call(cfg, "setStyle", oldStyle);
                Mod.call(cfg, "setRememberLastPage", oldRemember);
                Mod.call(cfg, "save");
                ((Map<?, ?>) Mod.field(API, "CACHE")).remove(ID);
                ((Map<?, ?>) Mod.field(API, "STATUS_CACHE")).remove(ID);
            });
            for (Path f : placed) {
                if (f != null) Files.deleteIfExists(f);
            }
            for (Path f : extraPlaced) Files.deleteIfExists(f);
        }
    }

    /** Puts an online SkyBlock status in {@code mode} into the PV's status cache for the test player. */
    private static void status(Session c, String mode) {
            c.ctx().runOnClient(mc -> {
                try {
                    Class<?> sr = Mod.cls(API + "$StatusResult");
                    Class<?> ss = Mod.cls(API + "$StatusState");
                    Object r = sr.getDeclaredConstructor(ss, String.class, long.class, long.class, String.class)
                            .newInstance(Mod.enumValue(API + "$StatusState", "ONLINE"), "SkyBlock: Dwarven Mines", 0L,
                                    System.currentTimeMillis(), mode);
                    @SuppressWarnings("unchecked")
                    Map<UUID, Object> cache = (Map<UUID, Object>) Mod.field(API, "STATUS_CACHE");
                    cache.put(ID, r);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
    }
}

package dev.testkit.gametest.hx;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.TestEnemy;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.CameraType;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Player;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Name Animation (mod branch name-wave, relay branch name-wave, killer560 2026-10-13: "if someone has our custom name
 * with a fade color, they can have it pulsate and kind of do a wave ... where the color shifts right"), 960-965. The
 * own name is faded green (00FF00) to blue (0000FF) over the 12 letters of {@link #NAME}, and the mod's animation clock
 * is frozen ({@code NameAnimation.freezeClockForTest}) so two frames are exact: Wave at 1x moves one letter per 250 ms,
 * Pulse at 1x is brightest at 0 ms and darkest (55%) at 1000 ms.
 * <ul>
 *   <li>960: Wave in chat - the glyph colours the Font hook paints at 250 ms are the 0 ms colours moved one letter to
 *       the right, and the SCREENSHOT agrees: each letter's measured colour (blue share) at 250 ms is the one its left
 *       neighbour had at 0 ms. Frames are in the report's screens
 *       ({@code -Dtestkit.nameWaveShots=<dir>} also copies them there by name).</li>
 *   <li>961: Pulse in chat - the same pixels are about 55% as bright at 1000 ms, with the same hue per letter.</li>
 *   <li>962: Off - glyph colours and pixels identical at two times.</li>
 *   <li>963: Wave everywhere else the name is drawn: the tab list, the own F5 nametag, and the Player Cosmetics preview
 *       (each: the name run found, and most of its pixels a different colour 250 ms later).</li>
 *   <li>964: the setting survives a restart (load from disk, profile reload), has tooltips, shows as two cycling
 *       buttons beside the fade in the menu, and is what Share if Supporter sends.</li>
 *   <li>965: another player's relay entry ({@code GET /supporters} body with {@code animation}, the mod's own parser)
 *       animates their name - glyphs shifted one letter at 250 ms and their nametag's pixels changing; bad animation
 *       values are dropped, and the supporters cache keeps it.</li>
 * </ul>
 */
final class HxNameWaveCases {

    private static final String NC = "namechanger.NameChangerConfig";
    private static final String NF = "namechanger.NameChangerFeature";
    private static final String NA = "namechanger.NameAnimation";
    private static final String SC = "supporters.SupportersConfig";
    private static final String SF = "supporters.SupportersFeature";
    private static final String NAME = "Gradientname";
    private static final long T0 = 0L;
    private static final long WAVE_STEP = 250L;
    /** Optional {@code -Dtestkit.nameWaveShots=<dir>}: also copy the named frames there (never a path in this file). */
    private static final String SHOTS = System.getProperty("testkit.nameWaveShots", "");

    private HxNameWaveCases() {
    }

    static void register(Session s) {
        s.test("960-hx-name-wave-chat", HxNameWaveCases::waveChat);
        s.test("961-hx-name-pulse-chat", HxNameWaveCases::pulseChat);
        s.test("962-hx-name-anim-off", HxNameWaveCases::off);
        s.test("963-hx-name-wave-everywhere", HxNameWaveCases::everywhere);
        s.test("964-hx-name-anim-saved", HxNameWaveCases::saved);
        s.test("965-hx-name-anim-relay", HxNameWaveCases::relay);
    }

    // ---- shared -----------------------------------------------------------------------------------------------------

    private static Object mode(String name) {
        try {
            Class<?> k = Mod.cls(NA + "$Mode");
            for (Object o : k.getEnumConstants()) {
                if (((Enum<?>) o).name().equals(name)) {
                    return o;
                }
            }
        } catch (RuntimeException e) {
            throw new AssertionError("no NameAnimation.Mode." + name, e);
        }
        throw new AssertionError("no NameAnimation.Mode." + name);
    }

    /** The own fade ({@link HxCosmeticNameCases#fade}, the same name and colours) with an animation at 1x. */
    private static HxKit.Settings animated(Session c, String mode) {
        return HxCosmeticNameCases.fade(c).with(NC, "OwnAnimation", mode(mode)).with(NC, "OwnAnimationSpeed", 1.0);
    }

    private static void freeze(Session c, long ms) {
        c.onClient(mc -> {
            Mod.staticCall(NA, "freezeClockForTest", ms);
            return null;
        });
    }

    /** What the Font hook paints for the own IGN at the frozen time {@code ms}. */
    private static int[] ownGlyphs(Session c, String self, long ms) {
        freeze(c, ms);
        return c.onClient(mc -> (int[]) Mod.staticCall(NA, "coloursNow",
                (FormattedCharSequence) Mod.staticCall("namechanger.NameReplacer", "replace",
                        Component.literal(self).getVisualOrderText())));
    }

    /** The frame at the frozen time {@code ms}; with {@code keepAs}, also copied to {@link #SHOTS} under that name. */
    private static BufferedImage shotAt(Session c, long ms, String tag, String keepAs) throws Exception {
        freeze(c, ms);
        c.ctx().waitTicks(4);
        Path p = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-" + tag));
        System.out.println("[" + c.name() + "] screenshot " + tag + ": " + p);
        if (keepAs != null) {
            save(p, keepAs);
        }
        return javax.imageio.ImageIO.read(p.toFile());
    }

    private static BufferedImage shotAt(Session c, long ms, String tag) throws Exception {
        return shotAt(c, ms, tag, null);
    }

    private static double blue(int rgb) {
        int g = (rgb >> 8) & 255, b = rgb & 255;
        return g + b == 0 ? 0 : b / (double) (g + b);
    }

    private static String hex(int[] a) {
        List<Integer> l = new ArrayList<>();
        for (int v : a) {
            l.add(v);
        }
        return HxCosmeticNameCases.hexList(l);
    }

    private static String shares(double[] a) {
        StringBuilder sb = new StringBuilder();
        for (double v : a) {
            sb.append(sb.isEmpty() ? "" : " ").append(String.format(java.util.Locale.ROOT, "%.2f", v));
        }
        return sb.toString();
    }

    /** The prefix widths of {@link #NAME} in GUI units (letter k spans [w[k], w[k+1]) ). */
    private static int[] advances(Session c) {
        return c.onClient(mc -> {
            int[] w = new int[NAME.length() + 1];
            for (int k = 0; k <= NAME.length(); k++) {
                w[k] = mc.font.width(NAME.substring(0, k));
            }
            return w;
        });
    }

    /**
     * Per letter of the name run {@code r}: mean colour of the pixels that are on the green-blue line in {@code mask}
     * (the frame the run was found in), read from {@code img}. -1 for a letter with no such pixel.
     */
    private static int[] letters(BufferedImage img, BufferedImage mask, HxCosmeticNameCases.Run r, int[] adv, double k) {
        int[] out = new int[NAME.length()];
        for (int i = 0; i < out.length; i++) {
            int xa = (int) Math.round(r.x0() + adv[i] * k);
            int xb = (int) Math.round(r.x0() + (adv[i + 1] - 1) * k);
            long sr = 0, sg = 0, sb = 0, n = 0;
            for (int y = r.y0(); y <= r.y1(); y++) {
                for (int x = xa; x < xb && x < img.getWidth(); x++) {
                    if (HxCosmeticNameCases.onLine(mask.getRGB(x, y))) {
                        int p = img.getRGB(x, y);
                        sr += (p >> 16) & 255;
                        sg += (p >> 8) & 255;
                        sb += p & 255;
                        n++;
                    }
                }
            }
            out[i] = n == 0 ? -1 : (int) ((sr / n) << 16 | (sg / n) << 8 | (sb / n));
        }
        return out;
    }

    /** Share of the on-line pixels of {@code r} in {@code a} that are a different colour (by more than 30) in {@code b}. */
    private static double changedShare(BufferedImage a, BufferedImage b, HxCosmeticNameCases.Run r) {
        int on = 0, moved = 0;
        for (int y = r.y0(); y <= r.y1(); y++) {
            for (int x = r.x0(); x <= r.x1(); x++) {
                int p = a.getRGB(x, y);
                if (!HxCosmeticNameCases.onLine(p)) {
                    continue;
                }
                on++;
                int q = b.getRGB(x, y);
                int d = Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)) + Math.abs(((p >> 8) & 255) - ((q >> 8) & 255))
                        + Math.abs((p & 255) - (q & 255));
                if (d > 30) {
                    moved++;
                }
            }
        }
        return on == 0 ? 0 : moved / (double) on;
    }

    private static HxCosmeticNameCases.Run nameRun(Session c, HxCosmeticNameCases.Fade f, String where) {
        c.check(!f.names().isEmpty(), "no faded name run found in " + where + ": " + f);
        return f.names().get(0);
    }

    private static void save(Path shot, String name) {
        if (SHOTS.isBlank()) {
            return;
        }
        try {
            Path dir = Path.of(SHOTS);
            Files.createDirectories(dir);
            Files.copy(shot, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            System.out.println("[name-wave] could not copy " + shot + ": " + e);
        }
    }

    private static void unfreeze(Session c) {
        freeze(c, -1L);
    }

    private static void chatName(Session c, String self) {
        HxCosmeticNameCases.clearChat(c);
        c.hx().call("chat", "text", self, "overlay", false);
        c.ctx().waitTicks(5);
    }

    // ---- 960 wave in chat -------------------------------------------------------------------------------------------

    private static void waveChat(Session c) throws Exception {
        String self = self(c);
        try (HxKit.Settings s = animated(c, "WAVE")) {
            int[] g0 = ownGlyphs(c, self, T0);
            int[] g1 = ownGlyphs(c, self, T0 + WAVE_STEP);
            c.note("glyphs at 0 ms:   " + hex(g0));
            c.note("glyphs at 250 ms: " + hex(g1));
            c.check(g0.length == NAME.length() && g0[0] == 0x00FF00 && g0[g0.length - 1] == 0x0000FF,
                    "at 0 ms the wave is not the plain fade: " + hex(g0));
            boolean shifted = g1.length == g0.length;
            for (int k = 1; shifted && k < g1.length; k++) {
                shifted = g1[k] == g0[k - 1];
            }
            c.check(shifted, "at 250 ms the glyph colors are not the 0 ms ones moved one letter right");
            c.check(g1[0] == g0[1], "letter 0 does not loop back to the fade's second color: " + Integer.toHexString(g1[0]));

            HxCosmeticNameCases.look(c, 0f, -30f);
            HxCosmeticNameCases.clearChat(c);
            BufferedImage base = shotAt(c, T0, "base");
            chatName(c, self);
            BufferedImage[] frames = new BufferedImage[4];
            for (int f = 0; f < frames.length; f++) {
                frames[f] = shotAt(c, T0 + f * WAVE_STEP / 2, "wave-" + f, "chat-wave-" + (f * WAVE_STEP / 2) + "ms.png");
            }
            BufferedImage a = frames[0];
            BufferedImage b = frames[2]; // 250 ms
            HxCosmeticNameCases.Fade fa = HxCosmeticNameCases.fadeOf(a, base, 0, a.getHeight() / 2, a.getWidth() / 2, a.getHeight());
            HxCosmeticNameCases.Fade fb = HxCosmeticNameCases.fadeOf(b, base, 0, b.getHeight() / 2, b.getWidth() / 2, b.getHeight());
            c.note("0 ms: " + fa);
            c.note("250 ms: " + fb);
            HxCosmeticNameCases.Run ra = nameRun(c, fa, "chat at 0 ms");
            HxCosmeticNameCases.Run rb = nameRun(c, fb, "chat at 250 ms");
            int width = c.onClient(mc -> mc.getWindow().getGuiScaledWidth());
            double k = a.getWidth() / (double) width;
            int[] adv = advances(c);
            int[] pa = letters(a, a, ra, adv, k);
            int[] pb = letters(b, b, rb, adv, k);
            double[] sa = new double[pa.length], sb = new double[pb.length];
            for (int i = 0; i < pa.length; i++) {
                sa[i] = pa[i] < 0 ? -1 : blue(pa[i]);
                sb[i] = pb[i] < 0 ? -1 : blue(pb[i]);
            }
            c.note("pixel blue share by letter at 0 ms:   " + shares(sa));
            c.note("pixel blue share by letter at 250 ms: " + shares(sb));
            c.check(ra.x0() == rb.x0(), "the name moved on screen between frames (" + ra.x0() + " vs " + rb.x0() + ")");
            int matched = 0, moved = 0;
            for (int i = 0; i < pa.length; i++) {
                c.check(sa[i] >= 0 && Math.abs(sa[i] - blue(g0[i])) <= 0.05,
                        "letter " + i + " on screen at 0 ms is not its glyph color: " + sa[i] + " vs " + blue(g0[i]));
                if (i > 0 && Math.abs(sb[i] - sa[i - 1]) <= 0.04) {
                    matched++;
                }
                if (Math.abs(sb[i] - sa[i]) >= 0.05) {
                    moved++;
                }
            }
            c.note("letters showing their left neighbor's 0 ms color: " + matched + "/" + (pa.length - 1)
                    + ", letters whose color changed: " + moved + "/" + pa.length);
            c.check(matched == pa.length - 1, "on screen the colors did not move one letter right");
            c.check(moved >= pa.length - 2, "on screen too few letters changed color: " + moved);
        } finally {
            unfreeze(c);
            HxCosmeticNameCases.clearChat(c);
        }
    }

    // ---- 961 pulse in chat ------------------------------------------------------------------------------------------

    private static void pulseChat(Session c) throws Exception {
        String self = self(c);
        try (HxKit.Settings s = animated(c, "PULSE")) {
            int[] g0 = ownGlyphs(c, self, T0);
            int[] g1 = ownGlyphs(c, self, 1000L);
            c.note("glyphs at 0 ms:    " + hex(g0));
            c.note("glyphs at 1000 ms: " + hex(g1));
            boolean dimmed = g0.length == g1.length && g0.length == NAME.length();
            for (int i = 0; dimmed && i < g0.length; i++) {
                int want = scale(g0[i], 0.55);
                dimmed = close(g1[i], want, 2);
            }
            c.check(g0.length > 0 && g0[0] == 0x00FF00, "at 0 ms the pulse is not at full brightness: " + hex(g0));
            c.check(dimmed, "at 1000 ms the glyphs are not the fade at 55%");

            HxCosmeticNameCases.look(c, 0f, -30f);
            HxCosmeticNameCases.clearChat(c);
            BufferedImage base = shotAt(c, T0, "base");
            chatName(c, self);
            BufferedImage a = shotAt(c, T0, "bright", "chat-pulse-0ms.png");
            BufferedImage b = shotAt(c, 1000L, "dim", "chat-pulse-1000ms.png");
            HxCosmeticNameCases.Fade fa = HxCosmeticNameCases.fadeOf(a, base, 0, a.getHeight() / 2, a.getWidth() / 2, a.getHeight());
            c.note("0 ms: " + fa);
            HxCosmeticNameCases.Run r = nameRun(c, fa, "chat at 0 ms");
            double ka = 0, kb = 0;
            int n = 0, hueOk = 0;
            for (int y = r.y0(); y <= r.y1(); y++) {
                for (int x = r.x0(); x <= r.x1(); x++) {
                    int p = a.getRGB(x, y);
                    if (!HxCosmeticNameCases.onLine(p)) {
                        continue;
                    }
                    int q = b.getRGB(x, y);
                    ka += Math.max((p >> 8) & 255, p & 255);
                    kb += Math.max((q >> 8) & 255, q & 255);
                    n++;
                    if (Math.abs(blue(p) - blue(q)) <= 0.05) {
                        hueOk++;
                    }
                }
            }
            double ratio = n == 0 ? 0 : kb / ka;
            c.note(String.format(java.util.Locale.ROOT, "%d name pixels; brightness at 1000 ms / 0 ms = %.3f; hue kept on %d",
                    n, ratio, hueOk));
            c.check(n >= 30, "too few name pixels: " + n);
            c.check(ratio >= 0.50 && ratio <= 0.60, "the pulse did not dim the name to about 55%: " + ratio);
            c.check(hueOk >= n * 0.95, "the pulse changed the hue, not just the brightness: " + hueOk + "/" + n);
        } finally {
            unfreeze(c);
            HxCosmeticNameCases.clearChat(c);
        }
    }

    private static int scale(int rgb, double k) {
        return ((int) Math.round(((rgb >> 16) & 255) * k) << 16) | ((int) Math.round(((rgb >> 8) & 255) * k) << 8)
                | (int) Math.round((rgb & 255) * k);
    }

    private static boolean close(int a, int b, int tol) {
        return Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) <= tol && Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) <= tol
                && Math.abs((a & 255) - (b & 255)) <= tol;
    }

    // ---- 962 off ----------------------------------------------------------------------------------------------------

    private static void off(Session c) throws Exception {
        String self = self(c);
        try (HxKit.Settings s = animated(c, "OFF")) {
            int[] g0 = ownGlyphs(c, self, T0);
            int[] g1 = ownGlyphs(c, self, T0 + WAVE_STEP);
            int[] g2 = ownGlyphs(c, self, 1000L);
            c.note("glyphs at 0 / 250 / 1000 ms: " + hex(g0) + " | " + hex(g1) + " | " + hex(g2));
            c.check(g0.length == NAME.length() && java.util.Arrays.equals(g0, g1) && java.util.Arrays.equals(g0, g2),
                    "an Off name's colors changed over time");
            HxCosmeticNameCases.look(c, 0f, -30f);
            HxCosmeticNameCases.clearChat(c);
            BufferedImage base = shotAt(c, T0, "base");
            chatName(c, self);
            BufferedImage a = shotAt(c, T0, "t0");
            BufferedImage b = shotAt(c, T0 + WAVE_STEP, "t250");
            HxCosmeticNameCases.Fade fa = HxCosmeticNameCases.fadeOf(a, base, 0, a.getHeight() / 2, a.getWidth() / 2, a.getHeight());
            c.note("0 ms: " + fa);
            HxCosmeticNameCases.Run r = nameRun(c, fa, "chat");
            double share = changedShare(a, b, r);
            c.note("share of name pixels that changed: " + share);
            c.check(share == 0.0, "an Off name's pixels changed between 0 and 250 ms: " + share);
        } finally {
            unfreeze(c);
            HxCosmeticNameCases.clearChat(c);
        }
    }

    // ---- 963 tab, nametag, preview ----------------------------------------------------------------------------------

    private static void everywhere(Session c) throws Exception {
        String self = self(c);
        try (HxKit.Settings s = animated(c, "WAVE")) {
            // Tab list.
            tab(c, "template", "dungeon", "players", List.of(player("HxMateA", "Mage", null), player(self, "Archer", null)));
            c.ctx().waitTicks(10);
            HxCosmeticNameCases.look(c, 0f, -30f);
            HxCosmeticNameCases.clearChat(c);
            BufferedImage closed = shotAt(c, T0, "tab-closed");
            HxCosmeticNameCases.tabDown(c, true);
            BufferedImage t0 = shotAt(c, T0, "tab-0", "tab-wave-0ms.png");
            BufferedImage t1 = shotAt(c, T0 + WAVE_STEP, "tab-250", "tab-wave-250ms.png");
            HxCosmeticNameCases.tabDown(c, false);
            HxCosmeticNameCases.Fade ft = HxCosmeticNameCases.fadeOf(t0, closed, 0, 0, t0.getWidth(), t0.getHeight() / 2);
            c.note("tab: " + ft);
            double tabShare = changedShare(t0, t1, nameRun(c, ft, "the tab list"));
            c.note("tab: share of name pixels a different color at 250 ms: " + tabShare);
            c.check(tabShare >= 0.6, "the tab list name did not animate: " + tabShare);
            hub(c);

            // Own F5 nametag.
            HxCosmeticNameCases.look(c, 0f, 0f);
            HxCosmeticNameCases.camera(c, CameraType.THIRD_PERSON_BACK);
            HxCosmeticNameCases.clearChat(c);
            BufferedImage offTag;
            try (HxKit.Settings f5off = new HxKit.Settings(c).with(SC, "OwnNameInF5", false)) {
                offTag = shotAt(c, T0, "f5-off");
            }
            try (HxKit.Settings f5on = new HxKit.Settings(c).with(SC, "OwnNameInF5", true)) {
                BufferedImage n0 = shotAt(c, T0, "f5-0", "own-nametag-wave-0ms.png");
                BufferedImage n1 = shotAt(c, T0 + WAVE_STEP, "f5-250", "own-nametag-wave-250ms.png");
                HxCosmeticNameCases.Fade fn = HxCosmeticNameCases.fadeOf(n0, offTag, 0, 0, n0.getWidth(), n0.getHeight());
                c.note("nametag: " + fn);
                double tagShare = changedShare(n0, n1, nameRun(c, fn, "the own F5 nametag"));
                c.note("nametag: share of name pixels a different color at 250 ms: " + tagShare);
                c.check(tagShare >= 0.6, "the own F5 nametag did not animate: " + tagShare);
            } finally {
                HxCosmeticNameCases.camera(c, CameraType.FIRST_PERSON);
            }

            // Player Cosmetics preview.
            Screen screen = HxCosmeticNameCases.openCosmetics(c);
            int[] rect = c.onClient(mc -> {
                for (var child : HxCosmeticNameCases.widgets(screen)) {
                    if (child instanceof StringWidget w && w.getMessage().getString().contains("Preview: ")) {
                        return new int[]{w.getX(), w.getY(), w.getWidth(), w.getHeight()};
                    }
                }
                return null;
            });
            c.check(rect != null, "no Preview line in Player Cosmetics > Custom Names");
            BufferedImage p0 = shotAt(c, T0, "preview-0", "preview-wave-0ms.png");
            BufferedImage p1 = shotAt(c, T0 + WAVE_STEP, "preview-250", "preview-wave-250ms.png");
            int width = c.onClient(mc -> McCompat.screen(mc).width);
            double k = p0.getWidth() / (double) width;
            HxCosmeticNameCases.Fade fp = HxCosmeticNameCases.fadeOf(p0, null, (int) (rect[0] * k), (int) (rect[1] * k) - 2,
                    (int) ((rect[0] + rect[2]) * k), (int) ((rect[1] + rect[3]) * k) + 2);
            c.note("preview: " + fp);
            double previewShare = changedShare(p0, p1, nameRun(c, fp, "the preview"));
            c.note("preview: share of name pixels a different color at 250 ms: " + previewShare);
            c.check(previewShare >= 0.6, "the Player Cosmetics preview did not animate: " + previewShare);
        } finally {
            unfreeze(c);
            HxCosmeticNameCases.tabDown(c, false);
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
        }
    }

    // ---- 964 saved, menu, tooltips, share ---------------------------------------------------------------------------

    private static void saved(Session c) throws Exception {
        Path file = c.onClient(mc -> (Path) Mod.field(NC, "CONFIG_PATH"));
        byte[] before = Files.exists(file) ? Files.readAllBytes(file) : null;
        // The shared names file (mod names-profiles-1015) is put back too, or a later case would load the wave from it.
        Path sharedFile = c.onClient(mc -> {
            try {
                return (Path) Mod.field(NC, "NAMES_PATH");
            } catch (AssertionError e) {
                return null; // a jar from before the shared names file
            }
        });
        byte[] sharedBefore = sharedFile != null && Files.exists(sharedFile) ? Files.readAllBytes(sharedFile) : null;
        try (HxKit.Settings s = HxCosmeticNameCases.fade(c)) {
            c.onClient(mc -> {
                Mod.set(NC, "setOwnAnimation", mode("WAVE"));
                Mod.set(NC, "setOwnAnimationSpeed", 1.75);
                Mod.call(Mod.cfg(NC), "save");
                return null;
            });
            // The names are saved in the shared names file since mod names-profiles-1015 (by default, outside the profile's file).
            Path namesFile = c.onClient(mc -> {
                try {
                    return Boolean.TRUE.equals(Mod.get(NC, "isPerProfileNames")) ? file : (Path) Mod.field(NC, "NAMES_PATH");
                } catch (AssertionError e) {
                    return file; // a jar from before the shared names file
                }
            });
            String json = Files.readString(namesFile);
            c.check(json.contains("\"ownAnimation\": \"wave\"") && json.contains("\"ownAnimationSpeed\": 1.75"),
                    "the saved file lacks the animation: " + json);
            Object[] loaded = c.onClient(mc -> {
                Mod.staticCall(NC, "load");
                return new Object[]{Mod.get(NC, "getOwnAnimation"), Mod.get(NC, "getOwnAnimationSpeed")};
            });
            c.note("after load(): " + loaded[0] + " at " + loaded[1]);
            c.check(loaded[0] == mode("WAVE") && ((Double) loaded[1]) == 1.75, "load() did not read the animation back");
            // The static side of a restart: the Font hook animates from the loaded setting.
            String self = self(c);
            int[] g0 = ownGlyphs(c, self, T0);
            int[] g1 = ownGlyphs(c, self, T0 + 1000L); // 1.75x: 7 letters a second
            boolean seven = g1.length == g0.length && g1.length == NAME.length();
            for (int k = 7; seven && k < g1.length; k++) {
                seven = g1[k] == g0[k - 7];
            }
            c.note("after load(), glyphs at 0 / 1000 ms: " + hex(g0) + " | " + hex(g1));
            c.check(seven, "after load() the name does not wave 7 letters in a second (1.75x)");
            Object[] profile = c.onClient(mc -> {
                Mod.set(NC, "setOwnAnimation", mode("OFF")); // in memory only
                Mod.set(NC, "setOwnAnimationSpeed", 3.0);
                Mod.staticCall("profiles.ProfileManager", "reloadAllConfigs");
                return new Object[]{Mod.get(NC, "getOwnAnimation"), Mod.get(NC, "getOwnAnimationSpeed")};
            });
            c.check(profile[0] == mode("WAVE") && ((Double) profile[1]) == 1.75,
                    "ProfileManager.reloadAllConfigs did not restore the animation: " + profile[0] + " " + profile[1]);
            boolean defaults = c.onClient(mc -> {
                try {
                    var k = Mod.cls(NC).getDeclaredConstructor();
                    k.setAccessible(true);
                    Object fresh = k.newInstance();
                    return Mod.call(fresh, "getOwnAnimation") == mode("OFF")
                            && ((Double) Mod.call(fresh, "getOwnAnimationSpeed")) == 1.0;
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            c.check(defaults, "the default is not Off at 1x");

            String tipA = c.onClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "describe", "Name Changer",
                    "Name Animation: Wave"));
            String tipS = c.onClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "describe", "Name Changer",
                    "Animation Speed: 1.75x"));
            c.note("tooltips: " + tipA + " | " + tipS);
            c.check(tipA != null && tipA.contains("Wave") && tipS != null && tipS.contains("3x"), "missing tooltips");

            Screen screen = HxCosmeticNameCases.openCosmetics(c);
            List<String> cycling = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                for (AbstractWidget w : HxCosmeticNameCases.widgets(screen)) {
                    String m = w.getMessage().getString();
                    if ((m.startsWith("Name Animation:") || m.startsWith("Animation Speed:"))
                            && Boolean.TRUE.equals(Mod.call(w, "isCycling"))) {
                        out.add(m);
                    }
                }
                return out;
            });
            c.note("cycling buttons: " + cycling);
            c.check(cycling.contains("Name Animation: Wave") && cycling.contains("Animation Speed: 1.75x"),
                    "the menu does not show the two cycling buttons with the saved values: " + cycling);
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });

            // Share if Supporter sends it, in the relay's shape.
            String share = c.onClient(mc -> {
                Object spec = Mod.staticCall(NF, "ownShareAnimation");
                return spec == null ? "null"
                        : Mod.staticCall("supporters.SupporterEntry", "animationJson", spec).toString();
            });
            c.note("shared animation: " + share);
            c.check("{\"mode\":\"wave\",\"speed\":1.75}".equals(share), "Share if Supporter would not send the wave: " + share);
            String noFade = c.onClient(mc -> {
                Mod.set(NC, "setOwnColorFadeEnabled", false);
                Object spec = Mod.staticCall(NF, "ownShareAnimation");
                Mod.set(NC, "setOwnColorFadeEnabled", true);
                return String.valueOf(spec);
            });
            c.check("null".equals(noFade), "a flat name would still share an animation: " + noFade);
        } finally {
            unfreeze(c);
            if (before != null) {
                Files.write(file, before);
            } else {
                Files.deleteIfExists(file);
            }
            if (sharedFile != null) {
                if (sharedBefore != null) {
                    Files.write(sharedFile, sharedBefore);
                } else {
                    Files.deleteIfExists(sharedFile);
                }
            }
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.staticCall(NC, "load");
                return null;
            });
        }
    }

    // ---- 965 another player's relay entry ---------------------------------------------------------------------------

    private static String relayBody(UUID wave, UUID bad1, UUID bad2, UUID bad3) {
        StringBuilder colors = new StringBuilder();
        for (int i = 0; i < NAME.length(); i++) {
            int g = Math.round(255 - 255f * i / (NAME.length() - 1));
            colors.append(i == 0 ? "" : ",").append((g << 8) | (255 - g));
        }
        String list = "[" + colors + "]";
        return "{\"version\":9,\"supporters\":["
                + "{\"uuid\":\"" + wave + "\",\"name\":\"&a" + NAME + "\",\"scale\":1,\"colors\":" + list
                + ",\"animation\":{\"mode\":\"wave\",\"speed\":1}},"
                + "{\"uuid\":\"" + bad1 + "\",\"name\":\"&aBadModeName\",\"scale\":1,\"colors\":[1,2,3,4,5,6,7,8,9,10,11],"
                + "\"animation\":{\"mode\":\"rainbow\",\"speed\":1}},"
                + "{\"uuid\":\"" + bad2 + "\",\"name\":\"&aBadSpeed\",\"scale\":1,\"colors\":[1,2,3,4,5,6,7,8],"
                + "\"animation\":{\"mode\":\"pulse\",\"speed\":9}},"
                + "{\"uuid\":\"" + bad3 + "\",\"name\":\"&dNoColors\",\"scale\":1,\"animation\":{\"mode\":\"wave\",\"speed\":1}}]}";
    }

    private static void relay(Session c) throws Exception {
        double[] at = c.scenario().playerPosition();
        HxCosmeticNameCases.camera(c, CameraType.FIRST_PERSON);
        TestEnemy bot = TestEnemy.named("Wv", "F").at(at[0], at[1], at[2] + 5).frozen(true).spawn(c.ctx(), c.server());
        Object savedMap = c.onClient(mc -> Mod.field(SF, "byUuid"));
        try (HxKit.Settings s = new HxKit.Settings(c).with(SC, "ShowSupporterNames", false).with(NC, "RandomizeOthers", false)) {
            String botName = bot.name();
            UUID botId = c.onClient(mc -> {
                for (Player p : mc.level.players()) {
                    if (p.getGameProfile().name().equals(botName)) {
                        return p.getUUID();
                    }
                }
                return null;
            });
            c.check(botId != null, "the bot " + botName + " is not in the client's world (premise)");
            UUID b1 = UUID.fromString("2f2e2d2c-2b2a-4928-a726-252423222120");
            UUID b2 = UUID.fromString("3f3e3d3c-3b3a-4938-b736-353433323130");
            UUID b3 = UUID.fromString("4f4e4d4c-4b4a-4948-8746-454443424140");
            String body = relayBody(botId, b1, b2, b3);
            c.note("relay body: " + body);
            List<String> parsed = c.onClient(mc -> {
                Mod.staticCall("players.PlayerNames", "noteKnown", botId, botName);
                List<?> list = (List<?>) Mod.staticCall("supporters.SupportersFetcher", "parseSupporters",
                        com.google.gson.JsonParser.parseString(body).getAsJsonObject().get("supporters"));
                Mod.staticCall(SF, "apply", 9, list, false);
                Mod.set(SC, "setShowSupporterNames", true);
                List<String> out = new ArrayList<>();
                for (Object e : list) {
                    out.add(Mod.call(e, "uuid") + "=" + Mod.call(e, "animation"));
                }
                return out;
            });
            c.note("parsed animations: " + parsed);
            c.check(parsed.size() == 4 && parsed.get(0).endsWith("=Spec[mode=WAVE, speed=1.0]"),
                    "the wave entry's animation was not read: " + parsed);
            c.check(parsed.get(1).endsWith("=null") && parsed.get(2).endsWith("=null") && parsed.get(3).endsWith("=null"),
                    "an unknown mode, an out-of-range speed or an animation without colors was not dropped: " + parsed);

            freeze(c, T0);
            int[] g0 = c.onClient(mc -> (int[]) Mod.staticCall(NA, "coloursNow",
                    ((Component) Mod.staticCall(SF, "displayNameFor", botId)).getVisualOrderText()));
            freeze(c, T0 + WAVE_STEP);
            int[] g1 = c.onClient(mc -> (int[]) Mod.staticCall(NA, "coloursNow",
                    ((Component) Mod.staticCall(SF, "displayNameFor", botId)).getVisualOrderText()));
            c.note("their glyphs at 0 ms:   " + hex(g0));
            c.note("their glyphs at 250 ms: " + hex(g1));
            boolean shifted = g0.length == NAME.length() && g1.length == g0.length && g0[0] == 0x00FF00;
            for (int k = 1; shifted && k < g1.length; k++) {
                shifted = g1[k] == g0[k - 1];
            }
            c.check(shifted, "the other player's name does not wave one letter right at 250 ms");

            // Their nametag on screen.
            HxCosmeticNameCases.look(c, 0f, 0f);
            HxCosmeticNameCases.clearChat(c);
            BufferedImage off;
            try (HxKit.Settings o = new HxKit.Settings(c).with(SC, "ShowSupporterNames", false)) {
                off = shotAt(c, T0, "nametag-off");
            }
            BufferedImage n0 = shotAt(c, T0, "nametag-0", "other-player-nametag-0ms.png");
            BufferedImage n1 = shotAt(c, T0 + WAVE_STEP, "nametag-250", "other-player-nametag-250ms.png");
            HxCosmeticNameCases.Fade fn = HxCosmeticNameCases.fadeOf(n0, off, 0, 0, n0.getWidth(), n0.getHeight());
            c.note("their nametag: " + fn);
            double share = changedShare(n0, n1, nameRun(c, fn, "their nametag"));
            c.note("their nametag: share of name pixels a different color at 250 ms: " + share);
            c.check(share >= 0.6, "the other player's nametag did not animate: " + share);

            // The cache keeps the animation across a restart.
            boolean cached = c.onClient(mc -> {
                Object loaded = Mod.staticCall("supporters.SupportersCache", "load");
                List<?> entries = (List<?>) Mod.call(loaded, "entries");
                Object fresh = Mod.staticCall("supporters.SupportersFetcher", "parseSupporters",
                        com.google.gson.JsonParser.parseString(body).getAsJsonObject().get("supporters"));
                Mod.staticCall("supporters.SupportersCache", "save", 9, fresh);
                Object again = Mod.call(Mod.staticCall("supporters.SupportersCache", "load"), "entries");
                boolean ok = false;
                for (Object e : (List<?>) again) {
                    if (botId.equals(Mod.call(e, "uuid"))) {
                        ok = String.valueOf(Mod.call(e, "animation")).equals("Spec[mode=WAVE, speed=1.0]");
                    }
                }
                Mod.staticCall("supporters.SupportersCache", "save", (Integer) Mod.call(loaded, "version"), entries);
                return ok;
            });
            c.check(cached, "the supporters cache lost the animation");
        } finally {
            unfreeze(c);
            c.onClient(mc -> {
                Mod.setField(SF, "byUuid", savedMap);
                Mod.staticCall(NA, "setShared", List.of());
                return null;
            });
            bot.remove();
            hub(c);
        }
    }
}

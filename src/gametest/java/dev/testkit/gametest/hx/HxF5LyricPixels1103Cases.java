package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.CameraType;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod branch f5lyrics2-1103 (killer560, 2026-11-03: "spotify lyrics still arent showing in f5"). 1103-f5-lyric-pixels
 * reads the REAL Spotify on this PC (Windows media session and window title, lrclib reached through a
 * {@code -PnetOverride=lrclib=https://lrclib.net} hole in the offline switch), with his own Song Tag settings (Song In F5,
 * My Lyrics In F5, Highlight Word In F5, My Name In F5) and a Hypixel-shaped scoreboard sent by the SERVER (a team that
 * hides name tags, a below-name score). In third person back, against a gray wall, it screenshots the space over your head
 * with My Lyrics In F5 off twice (the noise floor) and on once, and counts the pixels that changed above the head. It does
 * this as the player and as his Play As Mob skin (snow golem): his config has Play As Mob on, and on the old jar the mob's
 * state took the song line from the player's state but not the lyric (old jar: player 1095 pixels, snow golem 0, its state
 * lyric=null). When Spotify has no playing track within 20 s, a made-up track on the real clock stands in (noted). The HUD
 * stays up (F1 hides every name tag) with the Spotify HUD off, so nothing else over the head changes. Pass: in both forms the lyric adds at least 150 text
 * pixels over the head and the two "off" shots differ by under 40.
 */
final class HxF5LyricPixels1103Cases {

    private static final String TAGC = "spotify.SongShareConfig";
    private static final String HC = "spotify.SpotifyHudConfig";
    private static final String SLF = "spotify.SpotifyLyricsFeature";
    private static final String STD = "spotify.SongTagDraw";
    private static final String SC = "supporters.SupportersConfig";

    private HxF5LyricPixels1103Cases() {
    }

    static void register(Session s) {
        s.test("1103-f5-lyric-pixels", HxF5LyricPixels1103Cases::body);
    }

    private static int[] project(net.minecraft.client.Minecraft mc, Vec3 p) {
        var cam = mc.gameRenderer.mainCamera();
        Vec3 d = p.subtract(cam.position());
        var fw = cam.forwardVector();
        var up = cam.upVector();
        var left = cam.leftVector();
        double depth = d.x * fw.x() + d.y * fw.y() + d.z * fw.z();
        if (depth <= 0.05) {
            return null;
        }
        double u = -(d.x * left.x() + d.y * left.y() + d.z * left.z());
        double v = d.x * up.x() + d.y * up.y() + d.z * up.z();
        double fpx = (mc.getWindow().getHeight() / 2.0) / Math.tan(Math.toRadians(cam.getFov()) / 2.0);
        return new int[]{(int) Math.round(mc.getWindow().getWidth() / 2.0 + u / depth * fpx),
                (int) Math.round(mc.getWindow().getHeight() / 2.0 - v / depth * fpx)};
    }

    private static void fill(Session c, int x0, int y0, int z0, int x1, int y1, int z1, String block) {
        c.server().command("fill " + x0 + " " + y0 + " " + z0 + " " + x1 + " " + y1 + " " + z1 + " " + block);
    }

    /** Changed pixels between two shots inside a box, and the box around the changes ("x0,y0-x1,y1" or "none"). */
    private static Object[] diff(BufferedImage a, BufferedImage b, int x0, int y0, int x1, int y1) {
        int n = 0, bx0 = Integer.MAX_VALUE, by0 = Integer.MAX_VALUE, bx1 = -1, by1 = -1;
        for (int y = Math.max(0, y0); y < Math.min(a.getHeight(), y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(a.getWidth(), x1); x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                int d = Math.max(Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)),
                        Math.max(Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)), Math.abs((p & 255) - (q & 255))));
                if (d > 40) {
                    n++;
                    bx0 = Math.min(bx0, x);
                    by0 = Math.min(by0, y);
                    bx1 = Math.max(bx1, x);
                    by1 = Math.max(by1, y);
                }
            }
        }
        return new Object[]{n, n == 0 ? "none" : bx0 + "," + by0 + "-" + bx1 + "," + by1};
    }

    private static String str(Object t) {
        return t == null ? "null" : "'" + ((Component) t).getString() + "'";
    }

    /** What the level renderer is handed for your own player this frame (Play As Mob swap included). */
    private static String facts(Session c) {
        return c.onClient(mc -> {
            EntityRenderState st = mc.getEntityRenderDispatcher().extractEntity(mc.player, 1f);
            return st.getClass().getSimpleName() + " nameTag=" + (st.nameTag == null ? "null" : "'" + st.nameTag.getString() + "'")
                    + " attach=" + st.nameTagAttachment + " score=" + (st.scoreText == null ? "null" : "set")
                    + " song=" + str(Mod.call(st, "killer560smod$songTag"))
                    + " lyric=" + str(Mod.call(st, "killer560smod$lyricTag"));
        });
    }

    private static boolean lyricNow(Session c) {
        return c.onClient(mc -> Mod.staticCall(STD, "lyricFor", mc.player) != null);
    }

    private static void body(Session c) throws Exception {
        BlockPos f = c.onClient(mc -> mc.player.blockPosition());
        boolean hideGui = c.onClient(mc -> dev.testkit.compat.McCompat.hudHidden(mc));
        CameraType camBefore = c.onClient(mc -> mc.options.getCameraType());
        String self = self(c);
        List<String> fails = new ArrayList<>();
        int y0 = f.getY();
        // His 26.3 instance's Song Tag, Spotify HUD and Supporters settings (2026-11-03), as far as they touch F5.
        try (Settings set = new Settings(c).with(TAGC, "ShareSong", false)
                .with(TAGC, "SongInF5", true)
                .with(TAGC, "LyricsOverHead", false)
                .with(TAGC, "HighlightWordInF5", true)
                .with(TAGC, "CustomF5Colors", false)
                .with(HC, "HudEnabled", false)
                .with(HC, "WordTimedLyrics", true)
                .with(HC, "MusicSource", Mod.enumValue("spotify.SpotifyHudConfig$MusicSource", "MUSIC_PLAYERS"))
                .with(SC, "OwnNameInF5", true)
                .with(SC, "OwnScale", 1.0f)
                .with(SC, "OwnMob", "snow_golem")
                .with(SC, "PlayAsMob", false)) {
            fill(c, f.getX() - 10, y0 - 1, f.getZ() - 8, f.getX() + 10, y0 - 1, f.getZ() + 8, "minecraft:stone");
            fill(c, f.getX() - 10, y0, f.getZ() - 8, f.getX() + 10, y0 + 14, f.getZ() + 5, "minecraft:air");
            fill(c, f.getX() - 10, y0, f.getZ() + 6, f.getX() + 10, y0 + 14, f.getZ() + 6, "minecraft:gray_concrete");
            // Hypixel's own-player scoreboard, from the server: a team hiding name tags and a below-name score.
            c.server().command("team add k560f5px");
            c.server().command("team modify k560f5px nametagVisibility never");
            c.server().command("team join k560f5px " + self);
            c.server().command("scoreboard objectives add k560f5px dummy");
            c.server().command("scoreboard objectives setdisplay below_name k560f5px");
            c.server().command("scoreboard players set " + self + " k560f5px 20");
            c.ctx().runOnClient(mc -> {
                // F1 hides every name tag, vanilla and ours alike (OwnNameTagF5Mixin, LivingEntityRenderer), so the HUD stays up.
                dev.testkit.compat.McCompat.setHudHidden(mc, false);
                dev.testkit.compat.McCompat.clearChatAndToasts(mc);
            });
            HxCosmeticNameCases.look(c, 0f, 0f);
            HxCosmeticNameCases.camera(c, CameraType.THIRD_PERSON_BACK);
            // Real Spotify: a track with synced lines (lrclib), within a sung line.
            run(c, () -> Mod.set(TAGC, "setLyricsOverHead", true));
            boolean real;
            try {
                c.waitUntil("Spotify playing a track with synced lyrics (real Spotify + lrclib)", mc -> {
                    Object snap = Mod.staticCall(SLF, "snapshot");
                    return (Boolean) Mod.call(snap, "hasTrack") && !((List<?>) Mod.call(snap, "lines")).isEmpty();
                }, 400);
                real = true;
            } catch (AssertionError e) {
                // Spotify paused or with nothing loaded: the same render path with a made-up track on the real clock.
                real = false;
                long now = System.currentTimeMillis();
                List<Object> lines = c.onClient(mc -> List.of(HxSpotifyControlsCases.line(0.0, "the first line"),
                        HxSpotifyControlsCases.line(4.0, "<0:04.00>Hello <0:04.60>from <0:05.20>the <0:05.80>lyric"),
                        HxSpotifyControlsCases.line(900.0, "much later")));
                Object snap = c.onClient(mc -> HxSpotifyControlsCases.snapshot(5000, now, lines));
                c.ctx().runOnClient(mc -> Mod.setField(SLF, "testSnapshot", snap));
            }
            String track = c.onClient(mc -> {
                Object snap = Mod.staticCall(SLF, "snapshot");
                return Mod.call(snap, "title") + " - " + Mod.call(snap, "artist") + " (" + ((List<?>) Mod.call(snap, "lines")).size() + " lines)";
            });
            c.note((real ? "real Spotify: " : "Spotify has no playing track, made-up snapshot on the real clock: ") + track);
            for (String form : List.of("player", "snow_golem")) {
                run(c, () -> Mod.set(SC, "setPlayAsMob", form.equals("snow_golem")));
                run(c, () -> Mod.set(TAGC, "setLyricsOverHead", true));
                c.waitUntil("a lyric line being sung", mc -> Mod.staticCall(STD, "lyricFor", mc.player) != null, 1200);
                c.ctx().waitTicks(5);
                String onFacts = facts(c);
                long sub0 = (Long) c.onClient(mc -> Mod.field(STD, "ownLyricSubmitted"));
                c.ctx().waitTicks(5);
                long subs = (Long) c.onClient(mc -> Mod.field(STD, "ownLyricSubmitted")) - sub0;
                String lyric = c.onClient(mc -> (String) Mod.field(STD, "lastOwnLyric"));
                java.nio.file.Path pOn = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-" + form + "-on"));
                boolean stillSung = lyricNow(c);
                run(c, () -> Mod.set(TAGC, "setLyricsOverHead", false));
                c.ctx().waitTicks(5);
                java.nio.file.Path pOff1 = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-" + form + "-off1"));
                c.ctx().waitTicks(5);
                java.nio.file.Path pOff2 = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-" + form + "-off2"));
                String offFacts = facts(c);
                int[] head = c.onClient(mc -> {
                    int[] p = project(mc, mc.player.position().add(0, 1.9, 0));
                    return new int[]{p == null ? -1 : p[0], p == null ? -1 : p[1], mc.getWindow().getWidth(), mc.getWindow().getHeight()};
                });
                BufferedImage on = javax.imageio.ImageIO.read(pOn.toFile());
                BufferedImage off1 = javax.imageio.ImageIO.read(pOff1.toFile());
                BufferedImage off2 = javax.imageio.ImageIO.read(pOff2.toFile());
                double sx = on.getWidth() / (double) head[2], sy = on.getHeight() / (double) head[3];
                int hx = (int) (head[0] * sx), hy = (int) (head[1] * sy);
                int x0 = hx - on.getWidth() / 3, x1 = hx + on.getWidth() / 3;
                Object[] lyricPx = diff(on, off1, x0, 0, x1, hy);
                Object[] noise = diff(off1, off2, x0, 0, x1, hy);
                String msg = String.format(Locale.ROOT, "%s: lyric '%s' (still sung at the shot: %s), lyric submits in 5 ticks %d; "
                                + "pixels changed over the head (head at %d,%d of %dx%d) on vs off %d [%s], off vs off %d [%s]; "
                                + "state on: %s; state off: %s; shots %s %s %s",
                        form, lyric, stillSung, subs, hx, hy, on.getWidth(), on.getHeight(), (int) lyricPx[0], lyricPx[1],
                        (int) noise[0], noise[1], onFacts, offFacts, pOn.getFileName(), pOff1.getFileName(), pOff2.getFileName());
                c.note(msg);
                if (!stillSung || (int) lyricPx[0] < 150 || (int) noise[0] >= 40) {
                    fails.add(msg);
                }
            }
            c.check(fails.isEmpty(), String.join("; ", fails));
        } finally {
            c.server().command("team remove k560f5px");
            c.server().command("scoreboard objectives remove k560f5px");
            c.ctx().runOnClient(mc -> {
                Mod.setField(SLF, "testSnapshot", null);
                dev.testkit.compat.McCompat.setHudHidden(mc, hideGui);
                mc.options.setCameraType(camBefore);
            });
            fill(c, f.getX() - 10, y0, f.getZ() - 8, f.getX() + 10, y0 + 14, f.getZ() + 8, "minecraft:air");
        }
    }
}

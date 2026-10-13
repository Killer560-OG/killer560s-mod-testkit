package dev.testkit.gametest.ui;

import com.mojang.authlib.GameProfile;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Shared trails (mod trail-share, relay trail-share; killer560 2026-10-13: "it should be toggleable to see in cosmetics
 * for whether it is shared or not and it can only be shared by a supporter"). The relay's supporter entry gained an
 * optional {@code trail}; Player Cosmetics gained Share Trail (supporters only) and Show Others' Trails.
 * <ul>
 *   <li>1036 send: the share push ({@code SupportersSelfService.lastAttempt}, set before the network call, so it reads
 *       the same offline) carries the trail in the relay's exact shape only when linked AND Share if Supporter AND Share
 *       Trail AND the Trail are on; nothing at all is attempted for a non-supporter; with no display name the trail goes
 *       up alone (name ""). The tab's Share Trail is greyed out with "Only supporters can share a trail." until linked,
 *       then live; both switches default ON, survive save + load and a profile reload, and have tooltips.</li>
 *   <li>1037 remote: a relay entry (parsed by the mod's own {@code SupportersFetcher.parseSupporters}) gives a fake
 *       player walking across the view a ribbon: tracked, laid and drawn while he moves, and on screen a band behind him
 *       (against his walk), red at his end and leaning blue at the far end - the same renderer as your own.</li>
 *   <li>1038 hide: Show Others' Trails OFF drops every slot, draws nothing, and the frame matches one where he shares no
 *       trail at all; your own ribbon keeps drawing; ON brings his back.</li>
 *   <li>1039 limits: a squares entry lays squares behind him; a player who leaves loses his slot; 20 sharers keep only
 *       16; bad trail values are dropped by the parser; the supporters cache keeps a trail.</li>
 * </ul>
 */
final class TrailShareCases {

    private static final String CFG = "trail.TrailConfig";
    private static final String STYLE = "trail.TrailConfig$Style";
    private static final String MODE = "trail.TrailConfig$ColorMode";
    private static final String SC = "supporters.SupportersConfig";
    private static final String SF = "supporters.SupportersFeature";
    private static final String SA = "supporters.SupportersAutoShare";
    private static final String SS = "supporters.SupportersSelfService";
    private static final String NC = "namechanger.NameChangerConfig";
    private static final String RT = "trail.RemoteTrails";

    private static final UUID MATE = UUID.fromString("7a7b7c7d-1111-4222-8333-444455556666");
    private static final int MATE_ENTITY = 917_001;
    private static final String RIBBON_TRAIL =
            "{\"style\":\"ribbon\",\"mode\":\"gradient\",\"colors\":[16711680,16776960,255],\"width\":0.8,\"length\":4,\"opacity\":100}";
    private static final String SQUARES_TRAIL =
            "{\"style\":\"squares\",\"mode\":\"gradient\",\"colors\":[16711680,255],\"width\":0.5,\"length\":60,\"opacity\":100}";
    private static final double STEP = 0.15;

    private TrailShareCases() {
    }

    private static boolean ready(UiCase c) {
        if (!Mod.has(RT)) {
            c.problem("this jar has no shared trails (mod before trail-share)");
            return false;
        }
        return true;
    }

    // ---- relay entries ------------------------------------------------------------------------------------------

    private static String entry(UUID id, String name, String trail) {
        return "{\"uuid\":\"" + id + "\",\"name\":\"" + name + "\",\"scale\":1" + (trail == null ? "" : ",\"trail\":" + trail) + "}";
    }

    /** Parses {@code entries} with the mod's own parser and applies them as the supporter list. Returns the parsed list. */
    private static List<?> applyEntries(UiCase c, String... entries) {
        String body = "{\"version\":77,\"supporters\":[" + String.join(",", entries) + "]}";
        return c.onClient(mc -> {
            List<?> list = (List<?>) Mod.staticCall("supporters.SupportersFetcher", "parseSupporters",
                    com.google.gson.JsonParser.parseString(body).getAsJsonObject().get("supporters"));
            Mod.staticCall(SF, "apply", 77, list, false);
            return list;
        });
    }

    // ---- 1036 send --------------------------------------------------------------------------------------------------

    /** Forgets the last push, so the next shareCurrent is judged afresh (not throttled, not "unchanged"). */
    private static void unthrottle() {
        Mod.setField(SA, "lastPushedKey", null);
        Mod.setField(SA, "lastPushAtMs", -1L);
    }

    private static final Consumer<String> NO_ERROR = e -> {
    };

    /** Runs one shareCurrent; returns the attempt it made, or "(none)". */
    private static String push(UiCase c) {
        return c.onClient(mc -> {
            unthrottle();
            int before = (Integer) Mod.staticCall(SS, "attempts");
            Mod.staticCall(SA, "shareCurrent", NO_ERROR);
            int after = (Integer) Mod.staticCall(SS, "attempts");
            return after == before ? "(none)" : String.valueOf(Mod.staticCall(SS, "lastAttempt"));
        });
    }

    static void send(UiCase c) throws Exception {
        if (!ready(c)) {
            return;
        }
        Object[] saved = c.onClient(mc -> new Object[]{Mod.get(NC, "getOwnDisplayName"), Mod.get(NC, "getOwnColor"),
                Mod.get(SC, "isShareIfSupporter"), Mod.get(SC, "isShareTrail"), Mod.get(SC, "isShowOthersTrails")});
        try {
            c.onClient(mc -> {
                Object t = Mod.cfg(CFG);
                Mod.call(t, "setEnabled", true);
                Mod.call(t, "setStyle", Mod.enumValue(STYLE, "RIBBON"));
                Mod.call(t, "setColorMode", Mod.enumValue(MODE, "GRADIENT"));
                Mod.call(t, "setColorCount", 3);
                Mod.call(t, "setHeadColor", 0xFFFF0000);
                Mod.call(t, "setMidColor", 0xFFFFFF00);
                Mod.call(t, "setTailColor", 0xFF0000FF);
                Mod.call(t, "setRibbonWidth", 0.8f);
                Mod.call(t, "setRibbonSeconds", 1.5f);
                Mod.call(t, "setOpacityPercent", 70);
                Mod.set(NC, "setOwnDisplayName", "TrailGuy");
                Mod.set(NC, "setOwnColor", 0);
                Mod.set(SC, "setShareIfSupporter", true);
                Mod.set(SC, "setShareTrail", true);
                return null;
            });
            String want = "\"trail\":{\"style\":\"ribbon\",\"mode\":\"gradient\",\"colors\":[16711680,16776960,255],"
                    + "\"width\":0.8,\"length\":1.5,\"opacity\":70}";

            c.onClient(mc -> {
                Mod.staticCall(SA, "setLinked", false);
                return null;
            });
            String notLinked = push(c);
            c.note("not a supporter: " + notLinked);
            c.check("(none)".equals(notLinked), "a non-supporter attempted a share: " + notLinked);

            c.onClient(mc -> {
                Mod.staticCall(SA, "setLinked", true);
                return null;
            });
            String on = push(c);
            c.note("supporter, everything on: " + on);
            c.check(on.startsWith("POST ") && on.contains("\"name\":\"TrailGuy\"") && on.endsWith(want + "}"),
                    "the push does not carry the trail in the relay's shape (want ..." + want + "}): " + on);

            c.onClient(mc -> {
                Mod.set(SC, "setShareTrail", false);
                return null;
            });
            String shareOff = push(c);
            c.note("Share Trail off: " + shareOff);
            c.check(shareOff.startsWith("POST ") && !shareOff.contains("trail"), "Share Trail OFF still sends a trail: " + shareOff);

            c.onClient(mc -> {
                Mod.set(SC, "setShareTrail", true);
                Mod.call(Mod.cfg(CFG), "setEnabled", false);
                return null;
            });
            String trailOff = push(c);
            c.note("Trail itself off: " + trailOff);
            c.check(trailOff.startsWith("POST ") && !trailOff.contains("trail"), "a Trail that is off is still shared: " + trailOff);

            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setEnabled", true);
                Mod.set(SC, "setShareIfSupporter", false);
                return null;
            });
            String masterOff = push(c);
            c.note("Share if Supporter off: " + masterOff);
            c.check("(none)".equals(masterOff), "Share if Supporter OFF still pushed: " + masterOff);

            c.onClient(mc -> {
                Mod.set(SC, "setShareIfSupporter", true);
                Mod.set(NC, "setOwnDisplayName", "");
                return null;
            });
            String trailOnly = push(c);
            c.note("no display name, trail on: " + trailOnly);
            c.check(trailOnly.startsWith("POST {\"name\":\"\"") && trailOnly.contains(want),
                    "with no display name the trail does not go up alone: " + trailOnly);
            c.onClient(mc -> {
                Mod.set(SC, "setShareTrail", false);
                return null;
            });
            String nothing = push(c);
            c.note("no display name, Share Trail off: " + nothing);
            c.check("DELETE".equals(nothing), "with no name and no trail the entry is not cleared: " + nothing);

            // The tab: greyed out with the reason until linked.
            c.onClient(mc -> {
                Mod.set(SC, "setShareTrail", true);
                Mod.staticCall(SA, "setLinked", false);
                return null;
            });
            Object tab = c.onClient(mc -> {
                try {
                    return Mod.cls("gui.tab.CosmeticsTab").getDeclaredConstructor().newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("could not make a CosmeticsTab: " + e, e);
                }
            });
            List<String> rowsOut = rows(c, tab);
            c.ticks(40); // its own /supporters/me fetch fails offline at once and says "not linked"
            c.onClient(mc -> {
                Mod.staticCall(SA, "setLinked", false);
                return null;
            });
            List<String> rowsNo = rows(c, tab);
            c.onClient(mc -> {
                Mod.staticCall(SA, "setLinked", true);
                return null;
            });
            List<String> rowsYes = rows(c, tab);
            c.note("first build: " + rowsOut);
            c.note("not linked: " + rowsNo);
            c.note("linked: " + rowsYes);
            c.check(rowsNo.contains("Share Trail: OFF [inactive]"), "Share Trail is not greyed out for a non-supporter: " + rowsNo);
            c.check(rowsNo.contains("Only supporters can share a trail."), "no one-line reason for a non-supporter: " + rowsNo);
            c.check(rowsNo.contains("Show Others' Trails: ON"), "Show Others' Trails is missing or not ON: " + rowsNo);
            c.check(rowsYes.contains("Share Trail: ON"), "Share Trail is not live for a supporter: " + rowsYes);
            c.check(rowsYes.stream().noneMatch(r -> r.startsWith("Only supporters")), "a supporter still sees the reason: " + rowsYes);

            // Saved, loaded, defaults, profile reload, tooltips.
            Object[] round = c.onClient(mc -> {
                Mod.set(SC, "setShareTrail", false);
                Mod.set(SC, "setShowOthersTrails", false);
                Mod.call(Mod.cfg(SC), "save");
                Mod.staticCall(SC, "load");
                Object a = Mod.get(SC, "isShareTrail");
                Object b = Mod.get(SC, "isShowOthersTrails");
                Mod.set(SC, "setShareTrail", true); // memory only
                Mod.set(SC, "setShowOthersTrails", true);
                Mod.staticCall("profiles.ProfileManager", "reloadAllConfigs");
                Object pa = Mod.get(SC, "isShareTrail");
                Object pb = Mod.get(SC, "isShowOthersTrails");
                Object fresh;
                try {
                    var k = Mod.cls(SC).getDeclaredConstructor();
                    k.setAccessible(true);
                    fresh = k.newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
                return new Object[]{a, b, pa, pb, Mod.call(fresh, "isShareTrail"), Mod.call(fresh, "isShowOthersTrails")};
            });
            c.note("after save+load: share " + round[0] + ", show " + round[1] + "; after profile reload: " + round[2] + ", "
                    + round[3] + "; defaults: " + round[4] + ", " + round[5]);
            c.check(Boolean.FALSE.equals(round[0]) && Boolean.FALSE.equals(round[1]), "save + load lost the switches");
            c.check(Boolean.FALSE.equals(round[2]) && Boolean.FALSE.equals(round[3]), "a profile reload did not restore the switches");
            c.check(Boolean.TRUE.equals(round[4]) && Boolean.TRUE.equals(round[5]), "the switches do not default ON");
            for (String label : new String[]{"Share Trail: ON", "Show Others' Trails: ON"}) {
                String tip = c.onClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "describe", "Player Cosmetics", label));
                c.note("tooltip '" + label + "': " + tip);
                c.check(tip != null && tip.toLowerCase(Locale.ROOT).contains("trail"), "no tooltip for '" + label + "'");
            }
        } finally {
            c.onClient(mc -> {
                Mod.set(NC, "setOwnDisplayName", saved[0]);
                Mod.set(NC, "setOwnColor", saved[1]);
                Mod.set(SC, "setShareIfSupporter", saved[2]);
                Mod.set(SC, "setShareTrail", saved[3]);
                Mod.set(SC, "setShowOthersTrails", saved[4]);
                Mod.call(Mod.cfg(SC), "save");
                Mod.staticCall(SA, "setLinked", false);
                unthrottle();
                Object t = Mod.cfg(CFG);
                Mod.call(t, "setEnabled", false);
                Mod.call(t, "setStyle", Mod.enumValue(STYLE, "SQUARES"));
                Mod.call(t, "save");
                return null;
            });
        }
    }

    /** The Player Cosmetics tab's rows (" [inactive]" for a greyed-out button; text lines are never active), colour
     *  codes stripped. */
    private static List<String> rows(UiCase c, Object tab) {
        return c.onClient(mc -> {
            @SuppressWarnings("unchecked")
            List<AbstractWidget> ws = (List<AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0, 320, (Runnable) () -> {
            });
            List<String> out = new ArrayList<>();
            for (AbstractWidget w : ws) {
                String msg = net.minecraft.ChatFormatting.stripFormatting(w.getMessage().getString());
                boolean button = !(w instanceof net.minecraft.client.gui.components.StringWidget);
                out.add(msg + (button && !w.active ? " [inactive]" : ""));
            }
            return out;
        });
    }

    // ---- the scene for 1037-1039 ----------------------------------------------------------------------------------

    private static double[] origin;
    private static float savedYaw;
    private static float savedPitch;
    private static Object savedMap;

    /** First person, HUD hidden, 3 blocks above the grass looking south and down; your own trail off. */
    private static void scene(UiCase c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            McCompat.clearChatAndToasts(mc);
            McCompat.setHudHidden(mc, true);
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            if (origin == null) {
                savedYaw = mc.player.getYRot();
                savedPitch = mc.player.getXRot();
                origin = new double[]{Math.floor(mc.player.getX()) + 0.5, Math.floor(mc.player.getY()) + 3,
                        Math.floor(mc.player.getZ()) + 0.5};
                savedMap = Mod.field(SF, "byUuid");
            }
            Mod.call(Mod.cfg(CFG), "setEnabled", false);
            Mod.set(SC, "setShowOthersTrails", true);
            mc.player.getAbilities().flying = true;
            mc.player.setPos(origin[0], origin[1], origin[2]);
            mc.player.setDeltaMovement(Vec3.ZERO);
            mc.player.setYRot(0f);
            mc.player.setXRot(30f);
            return null;
        });
        c.ticks(10);
    }

    private static void restore(UiCase c) {
        c.onClient(mc -> {
            removeMates(mc, 0, 20);
            if (savedMap != null) {
                Mod.setField(SF, "byUuid", savedMap);
                savedMap = null;
            }
            Mod.set(SC, "setShowOthersTrails", true);
            Object t = Mod.cfg(CFG);
            Mod.call(t, "setEnabled", false);
            Mod.call(t, "setStyle", Mod.enumValue(STYLE, "SQUARES"));
            McCompat.setHudHidden(mc, false);
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            if (origin != null) {
                mc.player.setPos(origin[0], origin[1] - 3, origin[2]);
                mc.player.setYRot(savedYaw);
                mc.player.setXRot(savedPitch);
                origin = null;
            }
            return null;
        });
        c.ticks(2);
    }

    private static UUID mateId(int i) {
        return i == 0 ? MATE : new UUID(0x7a7b7c7d11114222L, 0x8333000000000000L + i);
    }

    /** Puts fake player {@code i} at x, ground level, z (spawning him the first time). */
    private static void mateAt(Minecraft mc, int i, double x, double z) {
        double y = origin[1] - 3;
        Entity existing = mc.level.getEntity(MATE_ENTITY + i);
        if (existing != null) {
            existing.setPos(x, y, z);
            return;
        }
        RemotePlayer p = new RemotePlayer(mc.level, new GameProfile(mateId(i), "TrailMate" + i));
        p.setId(MATE_ENTITY + i);
        p.setPos(x, y, z);
        mc.level.addEntity(p);
    }

    private static void removeMates(Minecraft mc, int from, int to) {
        for (int i = from; i < to; i++) {
            if (mc.level != null && mc.level.getEntity(MATE_ENTITY + i) != null) {
                mc.level.removeEntity(MATE_ENTITY + i, Entity.RemovalReason.DISCARDED);
            }
        }
    }

    /** The mate walks west (toward -x, to the right of the screen when facing south) for {@code ticks} ticks, 8 blocks
     *  in front of you, from 4 blocks east of you. */
    private static void walkMate(UiCase c, int ticks) {
        for (int k = 0; k <= ticks; k++) {
            double x = origin[0] + 4 - STEP * k;
            c.onClient(mc -> {
                mateAt(mc, 0, x, origin[2] + 8);
                return null;
            });
            c.ticks(1);
        }
    }

    private static BufferedImage shot(UiCase c, String label, int waitTicks) {
        c.ticks(waitTicks);
        String name = c.name() + "-" + label;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        Path kept = dev.testkit.harness.Report.screenshot(name, taken);
        c.note(label + " -> " + (kept != null ? kept : taken));
        try {
            return javax.imageio.ImageIO.read(taken.toFile());
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read " + taken, e);
        }
    }

    private static int delta(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF));
    }

    /** What changed between two frames: count, x range, and mean dRGB of the right, middle and left thirds of it. */
    record Diff(int changed, int minX, int maxX, double[] right, double[] middle, double[] left) {
        String describe() {
            return String.format(Locale.ROOT, "changed %d px over x %d..%d; right third dRGB %s, middle %s, left %s",
                    changed, minX, maxX, rgb(right), rgb(middle), rgb(left));
        }
    }

    private static String rgb(double[] d) {
        return String.format(Locale.ROOT, "(%+.0f,%+.0f,%+.0f)", d[0], d[1], d[2]);
    }

    private static Diff diff(BufferedImage off, BufferedImage on) {
        int w = on.getWidth(), h = on.getHeight();
        int changed = 0, minX = w, maxX = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (delta(on.getRGB(x, y), off.getRGB(x, y)) > 30) {
                    changed++;
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                }
            }
        }
        double[][] sum = new double[3][3];
        int[] n = new int[3];
        if (changed > 0) {
            double span = Math.max(1, maxX - minX + 1);
            for (int y = 0; y < h; y++) {
                for (int x = minX; x <= maxX; x++) {
                    int a = on.getRGB(x, y), b = off.getRGB(x, y);
                    if (delta(a, b) <= 30) {
                        continue;
                    }
                    double f = (x - minX) / span;
                    int seg = f >= 2.0 / 3 ? 0 : f >= 1.0 / 3 ? 1 : 2;
                    sum[seg][0] += ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
                    sum[seg][1] += ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
                    sum[seg][2] += (a & 0xFF) - (b & 0xFF);
                    n[seg]++;
                }
            }
        }
        double[][] mean = new double[3][3];
        for (int s = 0; s < 3; s++) {
            for (int k = 0; k < 3; k++) {
                mean[s][k] = n[s] == 0 ? 0 : sum[s][k] / n[s];
            }
        }
        return new Diff(changed, minX, maxX, mean[0], mean[1], mean[2]);
    }

    private static Object rt(String method, Object... args) {
        return Mod.staticCall(RT, method, args);
    }

    // ---- 1037 remote ------------------------------------------------------------------------------------------------

    static void remote(UiCase c) {
        if (!ready(c)) {
            return;
        }
        try {
            scene(c);
            c.onClient(mc -> {
                mateAt(mc, 0, origin[0] + 4, origin[2] + 8);
                return null;
            });
            List<?> parsed = applyEntries(c, entry(MATE, "", RIBBON_TRAIL));
            String spec = c.onClient(mc -> String.valueOf(Mod.call(parsed.get(0), "trail")));
            c.note("parsed trail: " + spec);
            c.check(spec.equals(RIBBON_TRAIL), "the mod did not read the relay's trail back as sent: " + spec);
            c.ticks(3);
            long f0 = c.onClient(mc -> ((Number) rt("framesDrawn")).longValue());
            walkMate(c, 53);
            long drawn = c.onClient(mc -> ((Number) rt("framesDrawn")).longValue()) - f0;
            double[] st = c.onClient(mc -> (double[]) rt("ribbonState", MATE));
            int tracked = c.onClient(mc -> ((Number) rt("tracked")).intValue());
            c.note(String.format(Locale.ROOT, "walk: tracked %d, %d frames with a shared trail drawn; his ribbon %s",
                    tracked, drawn, st == null ? "none" : String.format(Locale.ROOT, "%.0f points, %.2f blocks, %.0f frames",
                            st[0], st[1], st[2])));
            c.check(tracked == 1, "the fake player's trail is not tracked (" + tracked + ")");
            c.check(drawn > 20, "his trail drew in only " + drawn + " frames during a 53-tick walk");
            c.check(st != null && st[0] > 10 && st[1] > 3.0, "his ribbon is not laid behind him: "
                    + (st == null ? "none" : st[0] + " points, " + st[1] + " blocks"));
            BufferedImage on = shot(c, "on", 2);
            c.onClient(mc -> {
                Mod.set(SC, "setShowOthersTrails", false);
                return null;
            });
            BufferedImage off = shot(c, "off", 4);
            Diff d = diff(off, on);
            c.note("his band: " + d.describe());
            c.check(d.changed() > 1500, "no band on screen behind him: " + d.describe());
            // He walked to the right of the screen, so the band streams out to his left: his end (right) is the head
            // colour, red; the far end (left) leans further to the tail colour, blue.
            c.check(d.right()[0] > 40 && d.right()[0] - d.right()[1] > 40,
                    "the band at his end is not the head colour (red): " + d.describe());
            c.check(d.left()[2] - d.left()[0] > d.right()[2] - d.right()[0] + 30,
                    "the band does not run red at him to blue at the far end: " + d.describe());
        } finally {
            restore(c);
        }
    }

    // ---- 1038 hide ------------------------------------------------------------------------------------------------

    static void hide(UiCase c) {
        if (!ready(c)) {
            return;
        }
        try {
            scene(c);
            c.onClient(mc -> {
                mateAt(mc, 0, origin[0] + 4, origin[2] + 8);
                Object t = Mod.cfg(CFG);
                Mod.call(t, "setStyle", Mod.enumValue(STYLE, "RIBBON"));
                Mod.call(t, "setRibbonSeconds", 4.0f);
                Mod.call(t, "setShowInFirstPerson", true);
                Mod.call(t, "setEnabled", true);
                return null;
            });
            applyEntries(c, entry(MATE, "", RIBBON_TRAIL));
            c.onClient(mc -> {
                Mod.set(SC, "setShowOthersTrails", false);
                return null;
            });
            c.ticks(3);
            long remote0 = c.onClient(mc -> ((Number) rt("framesDrawn")).longValue());
            long own0 = c.onClient(mc -> ((Number) Mod.staticCall("trail.RibbonTrail", "framesDrawn")).longValue());
            // You move as well, so your own ribbon has something to draw.
            for (int k = 0; k <= 53; k++) {
                double x = origin[0] + 4 - STEP * k;
                double sx = origin[0] - STEP * k * 0.5;
                c.onClient(mc -> {
                    mateAt(mc, 0, x, origin[2] + 8);
                    mc.player.setPos(sx, origin[1], origin[2]);
                    return null;
                });
                c.ticks(1);
            }
            long remoteDrawn = c.onClient(mc -> ((Number) rt("framesDrawn")).longValue()) - remote0;
            long ownDrawn = c.onClient(mc -> ((Number) Mod.staticCall("trail.RibbonTrail", "framesDrawn")).longValue()) - own0;
            int tracked = c.onClient(mc -> ((Number) rt("tracked")).intValue());
            int lastDrawn = c.onClient(mc -> ((Number) rt("lastDrawnTrails")).intValue());
            c.note("Show Others' Trails OFF: tracked " + tracked + ", his trail drawn in " + remoteDrawn
                    + " frames (last " + lastDrawn + "); your own ribbon drawn in " + ownDrawn + " frames");
            c.check(tracked == 0 && remoteDrawn == 0 && lastDrawn == 0, "Show Others' Trails OFF still keeps or draws his trail");
            c.check(ownDrawn > 20, "your own ribbon stopped drawing with Show Others' Trails OFF (" + ownDrawn + " frames)");

            // On screen: OFF looks exactly like him sharing no trail at all.
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setEnabled", false);
                return null;
            });
            BufferedImage hidden = shot(c, "hidden", 4);
            applyEntries(c, entry(MATE, "", null));
            c.onClient(mc -> {
                Mod.set(SC, "setShowOthersTrails", true);
                return null;
            });
            BufferedImage none = shot(c, "no-trail", 4);
            Diff same = diff(none, hidden);
            c.note("OFF against no trail at all: " + same.describe());
            c.check(same.changed() < 50, "Show Others' Trails OFF does not look like no trail: " + same.describe());

            // ON again: his trail comes back.
            applyEntries(c, entry(MATE, "", RIBBON_TRAIL));
            long back0 = c.onClient(mc -> ((Number) rt("framesDrawn")).longValue());
            walkMate(c, 30);
            long back = c.onClient(mc -> ((Number) rt("framesDrawn")).longValue()) - back0;
            c.note("ON again: his trail drawn in " + back + " frames");
            c.check(back > 10, "turning Show Others' Trails back ON did not bring his trail back");
        } finally {
            restore(c);
        }
    }

    // ---- 1039 limits ----------------------------------------------------------------------------------------------

    static void limits(UiCase c) {
        if (!ready(c)) {
            return;
        }
        try {
            scene(c);
            // Squares behind him.
            c.onClient(mc -> {
                mateAt(mc, 0, origin[0] + 4, origin[2] + 8);
                return null;
            });
            applyEntries(c, entry(MATE, "", SQUARES_TRAIL));
            c.ticks(2);
            walkMate(c, 30);
            int squares = c.onClient(mc -> ((Number) rt("squareCount", MATE)).intValue());
            int drawnNow = c.onClient(mc -> ((Number) rt("lastDrawnTrails")).intValue());
            c.note("squares: " + squares + " laid, trails drawn last frame " + drawnNow);
            c.check(squares >= 25, "a squares trail laid only " + squares + " squares over a 30-tick walk");
            c.check(drawnNow == 1, "his squares were not drawn last frame (" + drawnNow + ")");

            // He leaves: his slot goes on the next tick.
            c.onClient(mc -> {
                removeMates(mc, 0, 1);
                return null;
            });
            c.ticks(2);
            int afterLeave = c.onClient(mc -> ((Number) rt("tracked")).intValue());
            c.note("after he left: tracked " + afterLeave);
            c.check(afterLeave == 0, "a player who left kept his trail slot");

            // Twenty sharers: only the nearest 16 are kept.
            String[] many = new String[20];
            for (int i = 0; i < 20; i++) {
                many[i] = entry(mateId(i), "", SQUARES_TRAIL);
            }
            c.onClient(mc -> {
                for (int i = 0; i < 20; i++) {
                    mateAt(mc, i, origin[0] - 10 + i, origin[2] + 6 + (i % 3));
                }
                return null;
            });
            applyEntries(c, many);
            c.ticks(3);
            int cap = c.onClient(mc -> ((Number) rt("tracked")).intValue());
            c.note("20 sharers in view: tracked " + cap);
            c.check(cap == 16, "20 sharers kept " + cap + " trails, not the 16 cap");
            c.onClient(mc -> {
                removeMates(mc, 0, 20);
                return null;
            });

            // The parser drops bad trails (every value the relay refuses), keeps good ones, and the cache keeps them.
            String[] bad = {
                    "{\"style\":\"sparkles\",\"mode\":\"gradient\",\"colors\":[1,2],\"width\":0.5,\"length\":1,\"opacity\":50}",
                    "{\"style\":\"ribbon\",\"mode\":\"gradient\",\"colors\":[1,2],\"width\":9,\"length\":1,\"opacity\":50}",
                    "{\"style\":\"ribbon\",\"mode\":\"gradient\",\"colors\":[1],\"width\":0.5,\"length\":1,\"opacity\":50}",
                    "{\"style\":\"ribbon\",\"mode\":\"gradient\",\"colors\":[1,2],\"width\":0.5,\"length\":1,\"opacity\":50,\"x\":1}",
                    "{\"style\":\"squares\",\"mode\":\"gradient\",\"colors\":[1,2],\"width\":0.5,\"length\":2.5,\"opacity\":50}",
                    "{\"style\":\"ribbon\",\"mode\":\"gradient\",\"colors\":[1,2],\"width\":0.5,\"length\":1,\"opacity\":500}",
                    "\"ribbon\""};
            String[] entries = new String[bad.length + 2];
            for (int i = 0; i < bad.length; i++) {
                entries[i] = entry(mateId(i + 1), "Bad" + i, bad[i]);
            }
            entries[bad.length] = entry(MATE, "&aGood", RIBBON_TRAIL);
            entries[bad.length + 1] = entry(mateId(19), "&bPlain", null);
            List<?> parsed = applyEntries(c, entries);
            List<String> trails = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                for (Object e : parsed) {
                    out.add(String.valueOf(Mod.call(e, "trail")));
                }
                return out;
            });
            c.note("parsed trails: " + trails);
            c.check(trails.size() == entries.length, "the parser dropped whole entries: " + trails.size());
            c.check(trails.subList(0, bad.length).stream().allMatch("null"::equals), "a bad trail was not dropped: " + trails);
            c.check(trails.get(bad.length).equals(RIBBON_TRAIL) && "null".equals(trails.get(bad.length + 1)),
                    "the good trail or the plain entry was misread: " + trails);
            String cached = c.onClient(mc -> {
                Object loaded = Mod.staticCall("supporters.SupportersCache", "load");
                List<?> before = (List<?>) Mod.call(loaded, "entries");
                Mod.staticCall("supporters.SupportersCache", "save", 77, parsed);
                List<?> again = (List<?>) Mod.call(Mod.staticCall("supporters.SupportersCache", "load"), "entries");
                String got = "missing";
                for (Object e : again) {
                    if (MATE.equals(Mod.call(e, "uuid"))) {
                        got = String.valueOf(Mod.call(e, "trail"));
                    }
                }
                Mod.staticCall("supporters.SupportersCache", "save", (Integer) Mod.call(loaded, "version"), before);
                return got;
            });
            c.note("cache round trip: " + cached);
            c.check(RIBBON_TRAIL.equals(cached), "the supporters cache lost the trail: " + cached);
        } finally {
            restore(c);
        }
    }
}

package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 600-601 (mod branch map-heads, killer560 2026-10-07): "For the player heads it shouldn't have that arrow for where
 * they face, it should just rotate their head to face whatever direction they are facing, with the top of their head
 * being their facing direction."
 *
 * <p>The Dungeon Map HUD at GUI scale 2 on a green-room background (the Entrance's #00FF00, where the frame matters),
 * his own marker alone, facing north (yaw 180), east (270), south (0), west (90) and north-east (225). Each frame is
 * diffed against a frame with his marker off the map ({@link MapHeadCases}' scene and finders).
 * <ul>
 *   <li>600-ui-map-head-turn: Player Heads ON. His skin's face is found at the turn the heading asks for (the arrow's
 *       {@code 180 + yaw}, so north is upright and east a quarter turn clockwise), within 10 degrees; nothing of the
 *       marker reaches past the framed head (no arrow, no tick); the edge is still the dark frame (85% of the boundary).</li>
 *   <li>601-ui-map-arrow-turn: Player Heads OFF, the same five headings. An outlined green arrow, no face, its tip (the
 *       marker pixel furthest from its centroid) along the heading within 15 degrees - arrow mode unchanged.</li>
 * </ul>
 * {@code TESTKIT_HUDFIX_SHOTS=<dir>} also gets every GUI 2 frame.
 */
final class MapHeadTurnCases {

    private static final String CFG = "livemap.LiveMapConfig";
    private static final int GREEN_ROOM = 0xFF00FF00;
    private static final int SELF_GREEN = 0x55FF55;
    private static final float[] YAWS = {180f, 270f, 0f, 90f, 225f};
    private static final String[] NAMES = {"north", "east", "south", "west", "northeast"};

    private MapHeadTurnCases() {
    }

    static void heads(UiCase c) throws Exception {
        run(c, true);
    }

    static void arrows(UiCase c) throws Exception {
        run(c, false);
    }

    private static void run(UiCase c, boolean heads) throws Exception {
        int[] window = HudEditorCases.windowSize(c);
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        double[] startPos = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        List<AutoCloseable> restore = new ArrayList<>();
        double[] self = MapHeadCases.room(2, 2);
        double[] away = {self[0], -185 - 48};
        boolean hasSetting = hasMethod(Mod.cfg(CFG), "setPlayerHeads");
        try {
            HudEditorCases.setWindow(c, 854, 480, 2);
            c.onClient(mc -> {
                restore.add(0, Mod.with("hud.HudConfig", "AutoScale", false));
                restore.add(0, Mod.with(CFG, "Enabled", true));
                restore.add(0, Mod.with(CFG, "ShowTeammates", false));
                restore.add(0, Mod.with(CFG, "IconScale", 2.0f));
                restore.add(0, Mod.with(CFG, "RoomPx", 20));
                restore.add(0, Mod.with(CFG, "MapBackground", GREEN_ROOM));
                if (hasSetting) {
                    restore.add(0, Mod.with(CFG, "PlayerHeads", heads));
                }
                Mod.staticCall("secrets.DungeonState", "setRoomSim", true);
                dev.testkit.compat.McCompat.setHudHidden(mc, false);
                return null;
            });
            if (!hasSetting) {
                c.problem("this jar has no Player Heads setting");
                return;
            }
            MapHeadCases.teleport(c, away[0], away[1], 180f);
            MapHeadCases.Shot base = MapHeadCases.shot(c, "base");
            int[] frame = MapHeadCases.frameBox(base.img(), GREEN_ROOM);
            if (frame == null) {
                c.problem("no map frame of the green room colour on screen - the map HUD did not draw");
                return;
            }
            int[] face = MapHeadCases.expectedFace(c, null, true);
            if (heads && face == null) {
                c.problem("could not read his skin texture");
                return;
            }
            for (int i = 0; i < YAWS.length; i++) {
                float yaw = YAWS[i];
                String tag = NAMES[i];
                MapHeadCases.teleport(c, self[0], self[1], yaw);
                MapHeadCases.Shot s = MapHeadCases.shot(c, (heads ? "head-" : "arrow-") + tag);
                save(c, s.img(), (heads ? "head-" : "arrow-") + tag);
                List<MapHeadCases.Cluster> found = MapHeadCases.clusters(base.img(), s.img(), frame);
                if (found.size() != 1) {
                    c.problem(tag + ": expected his one marker on the map, found " + found.size() + " " + found);
                    continue;
                }
                MapHeadCases.Cluster cl = found.get(0);
                MapHeadCases.RotFace f = MapHeadCases.findRotated(s.img(), cl, face);
                MapHeadCases.Outline o = MapHeadCases.outline(s.img(), base.img(), cl, GREEN_ROOM, SELF_GREEN);
                if (heads) {
                    int need = yaw % 90 == 0 ? 56 : 48;
                    c.note(tag + " (yaw " + (int) yaw + "): " + cl + "; edge " + o);
                    if (f.score() < need) {
                        c.problem(tag + ": his face is not drawn (best " + f + ", need " + need + "/64)");
                        continue;
                    }
                    MapHeadCases.headTurn(c, tag, f, cl, yaw);
                    if (o.coverage() < 0.85) {
                        c.problem(String.format(Locale.ROOT, "%s: only %.0f%% of the head's edge is the dark frame",
                                tag, o.coverage() * 100));
                    }
                } else {
                    int green = 0;
                    double sx = 0, sy = 0;
                    for (int[] p : cl.pixels) {
                        sx += p[0] + 0.5;
                        sy += p[1] + 0.5;
                        if (MapHeadCases.delta(s.img().getRGB(p[0], p[1]), SELF_GREEN) <= 60) {
                            green++;
                        }
                    }
                    double mx = sx / cl.size(), my = sy / cl.size();
                    double far = -1;
                    double tx = 0, ty = 0;
                    for (int[] p : cl.pixels) {
                        double d = Math.hypot(p[0] + 0.5 - mx, p[1] + 0.5 - my);
                        if (d > far) {
                            far = d;
                            tx = p[0] + 0.5;
                            ty = p[1] + 0.5;
                        }
                    }
                    // Screen heading of the tip: 0 = up (-y), clockwise positive, the convention of the head's turn.
                    int got = (int) Math.floorMod(Math.round(Math.toDegrees(Math.atan2(tx - mx, -(ty - my)))), 360);
                    int want = MapHeadCases.expectedTurn(yaw);
                    int off = MapHeadCases.angleOff(got, want);
                    c.note(String.format(Locale.ROOT, "%s (yaw %d): %s; %d green px; tip at %d deg (want %d, off %d);"
                            + " best face %d/64; edge %s", tag, (int) yaw, cl, green, got, want, off,
                            f.score(), o));
                    if (green < 20) {
                        c.problem(tag + ": heads off, but no green arrow (" + green + " green px)");
                    }
                    if (face != null && f.score() >= 56) {
                        c.problem(tag + ": heads OFF draws his face (" + f + ")");
                    }
                    if (off > 15) {
                        c.problem(tag + ": the arrow points " + got + " deg, the heading wants " + want);
                    }
                    if (o.coverage() < 0.85) {
                        c.problem(String.format(Locale.ROOT, "%s: only %.0f%% of the arrow's edge is outline", tag,
                                o.coverage() * 100));
                    }
                }
            }
        } finally {
            try {
                c.onClient(mc -> {
                    for (AutoCloseable r : restore) {
                        try {
                            r.close();
                        } catch (Exception e) {
                            System.out.println("[" + c.name() + "] restore failed: " + e);
                        }
                    }
                    Mod.call(Mod.cfg(CFG), "save");
                    Mod.staticCall("secrets.DungeonState", "setRoomSim", false);
                    var server = mc.getSingleplayerServer();
                    var uuid = mc.player.getUUID();
                    server.execute(() -> server.getPlayerList().getPlayer(uuid)
                            .teleportTo(startPos[0], startPos[1], startPos[2]));
                    return null;
                });
                c.ticks(5);
                HudEditorCases.restoreWindow(c, window, gui);
            } catch (Throwable t) {
                c.note("cleanup: " + UiCase.describe(t));
            }
        }
    }

    private static void save(UiCase c, BufferedImage img, String label) {
        String dir = System.getenv("TESTKIT_HUDFIX_SHOTS");
        if (dir == null || dir.isBlank()) {
            return;
        }
        try {
            String jar = Mod.isCheat() ? "cheat" : "legit";
            String mc = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                    .getMetadata().getVersion().getFriendlyString();
            Path out = Path.of(dir).resolve(c.name() + "-" + label + "-" + mc + "-" + jar + ".png");
            Files.createDirectories(out.getParent());
            javax.imageio.ImageIO.write(img, "png", out.toFile());
        } catch (Exception | Error e) {
            c.note("could not save " + label + ": " + e);
        }
    }

    private static boolean hasMethod(Object target, String name) {
        for (java.lang.reflect.Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }
}

package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 395-ui-map-heads: the Dungeon Map's Player Heads option and the outlined arrow (mod 2026-10-07, map-heads), judged
 * from screenshots of the REAL map HUD and the Interactive Map.
 *
 * <p>killer560: "for the map make an option to have people's symbols be by player head for the default ones. It should
 * work on your own head as well. Also don't make the green blend in so well with green room for the arrow pointer."
 *
 * <p>The scene: the singleplayer world with {@code DungeonState.setRoomSim(true)} (so the map HUD draws), Auto Scale
 * off, and the map's Map Background set to a room colour - the Entrance's #00FF00 (the green room), then Normal's
 * #724318 - so the whole map is "a room" of that colour. Him at map room (1,1) facing yaw 30; two teammates added to the
 * client as {@code RemotePlayer}s with real v4 UUIDs: MapHeadMate at room (3,1) facing yaw 90, WITH a PlayerInfo
 * (injected through {@code handlePlayerInfoUpdate}, as a server sends it), and MapArrowMate at room (1,3) facing yaw 180,
 * with NO PlayerInfo (no skin known). The party tracker is empty, so the map takes every real player as a teammate.
 *
 * <p>Every frame is diffed against a no-marker frame of the same map (him placed where his marker lands outside the
 * map's frame, teammates hidden); a pixel counts above a summed RGB delta of 30. The changed pixels inside the frame
 * fall into one cluster per marker. A HEAD is proven by finding, in the cluster, an 8x8 grid (at any turn, since mod
 * map-heads 2026-10-08 turns the head to the heading: {@link #findRotated}) whose cell centres match
 * that player's skin face (face layer with the hat layer over it, read from the skin texture the client holds for
 * them) on at least 56 of 64 cells. An arrow's OUTLINE is measured on its boundary pixels (cluster pixels touching an
 * unchanged map pixel): the share that are the outline colour, and the WCAG contrast of the boundary against the room.
 */
final class MapHeadCases {

    static final String CFG = "livemap.LiveMapConfig";
    static final int GREEN_ROOM = 0xFF00FF00;
    static final int BROWN_ROOM = 0xFF724318;
    static final int SELF_GREEN = 0x55FF55;
    static final double START = -185;
    static final double FLY_Y = 100;
    static final float SELF_YAW = 30f;
    static final float HEAD_YAW = 90f;
    static final float ARROW_YAW = 180f;
    static final UUID HEAD_ID = UUID.fromString("5f1c0a2e-3b4d-4c6e-8f10-395000000001");
    static final UUID ARROW_ID = UUID.fromString("5f1c0a2e-3b4d-4c6e-8f10-395000000002");
    static final String HEAD_NAME = "MapHeadMate";
    static final String ARROW_NAME = "MapArrowMate";
    static final int HEAD_ENTITY = -395001;
    static final int ARROW_ENTITY = -395002;
    /** Cells of 64 that must match the skin's face for a head to count as drawn. */
    static final int FACE_CELLS = 56;
    /** Boundary share in the outline colour, and contrast against the room, an outlined marker must reach. */
    static final double MIN_COVERAGE = 0.85;
    static final double MIN_CONTRAST = 4.5;

    private MapHeadCases() {
    }

    static void run(UiCase c) throws Exception {
        boolean hasSetting = hasMethod(Mod.cfg(CFG), "setPlayerHeads");
        if (!hasSetting) {
            c.problem("this jar has no Player Heads setting (LiveMapConfig.setPlayerHeads) - mod before map-heads");
        }
        double[] startPos = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        boolean hideGuiWas = c.onClient(mc -> dev.testkit.compat.McCompat.hudHidden(mc));
        List<AutoCloseable> restore = new ArrayList<>();
        double[] self = room(1, 1);
        double[] mateHead = room(3, 1);
        double[] mateArrow = room(1, 3);
        // Where his marker lands well above the map's frame, for the no-marker frames.
        double[] away = {self[0], START - 48};
        try {
            c.onClient(mc -> {
                restore.add(0, Mod.with("hud.HudConfig", "AutoScale", false));
                restore.add(0, Mod.with(CFG, "Enabled", true));
                restore.add(0, Mod.with(CFG, "ShowTeammates", true));
                restore.add(0, Mod.with(CFG, "ClassRecolorTeammates", false));
                restore.add(0, Mod.with(CFG, "IconScale", 1.5f));
                restore.add(0, Mod.with(CFG, "RoomPx", 20));
                restore.add(0, Mod.with(CFG, "MapBackground", GREEN_ROOM));
                if (hasSetting) {
                    restore.add(0, Mod.with(CFG, "PlayerHeads", false));
                }
                Mod.staticCall("secrets.DungeonState", "setRoomSim", true);
                dev.testkit.compat.McCompat.setHudHidden(mc, false);
                dev.testkit.compat.McCompat.clearChatAndToasts(mc);
                return null;
            });
            // The party tracker is session state: an hx session earlier in the same client (111's party chat, 100's
            // tab list) leaves HxMateA/HxMateB in it, and then the map rightly draws only those two - never this
            // case's RemotePlayers. Empty it for the case (every real player is then a teammate) and restore it.
            List<?> party = c.onClient(mc -> (List<?>) Mod.staticCall("leapmenu.PartyTracker", "teammates"));
            c.note("party tracker teammates before the case: " + party + " (cleared for the case)");
            c.onClient(mc -> {
                @SuppressWarnings("unchecked")
                java.util.Set<String> members = (java.util.Set<String>) Mod.field("leapmenu.PartyTracker", "MEMBERS");
                List<String> was = new ArrayList<>(members);
                members.clear();
                restore.add(0, () -> {
                    members.clear();
                    members.addAll(was);
                });
                return null;
            });

            // An earlier case's map (a door cell left in the grid by the sim / interactive map cases) changes colour
            // between frames and reads as a fourth marker in a full ui run (2026-10-08): start from an empty grid.
            c.onClient(mc -> {
                Mod.staticCall("livemap.LiveMapFeature", "resetGrid", "395-ui-map-heads start");
                return null;
            });
            c.ticks(2);

            // ---- the frames on the green room ----
            Shot greenBase = baseShot(c, "green-base", GREEN_ROOM, away);
            int[] frame = frameBox(greenBase.img, GREEN_ROOM);
            if (frame == null) {
                c.problem("no map frame of the green room colour on screen - the map HUD did not draw");
                return;
            }
            c.note(String.format(Locale.ROOT, "map frame on screen %d,%d..%d,%d", frame[0], frame[1], frame[2],
                    frame[3]));
            placeMarkers(c, self, mateHead, mateArrow);
            int[] headFace = expectedFace(c, HEAD_ID, false);
            int[] selfFace = expectedFace(c, null, true);
            c.check(headFace != null && selfFace != null, "could not read the skin textures the client holds");

            setHeads(c, hasSetting, false);
            Shot greenOff = shot(c, "green-heads-off");
            List<Cluster> off = clusters(greenBase.img, greenOff.img, frame);
            Cluster[] offM = assign(c, "green, heads off", off);

            if (hasSetting) {
                setHeads(c, true, true);
            }
            Shot greenOn = shot(c, "green-heads-on");
            List<Cluster> on = clusters(greenBase.img, greenOn.img, frame);
            Cluster[] onM = assign(c, "green, heads on", on);

            // Heads off: three arrows, no face anywhere.
            if (offM != null) {
                for (int i = 0; i < 3; i++) {
                    int[] want = i == 0 ? selfFace : i == 1 ? headFace : null;
                    if (want != null) {
                        RotFace f = findRotated(greenOff.img, offM[i], want);
                        if (f.score >= FACE_CELLS) {
                            c.problem("heads OFF but marker " + i + " draws a face (" + f.score + "/64)");
                        }
                    }
                }
                Outline so = outline(greenOff.img, greenBase.img, offM[0], GREEN_ROOM, SELF_GREEN);
                Outline bo = outline(greenOff.img, greenBase.img, offM[2], GREEN_ROOM, 0xFFFFFF);
                c.note("green room, his arrow: " + so);
                c.note("green room, MapArrowMate's arrow: " + bo);
                requireOutline(c, "his arrow on the green room", so, true);
                requireOutline(c, "a teammate's arrow on the green room", bo, true);
            }

            // Heads on: his face, MapHeadMate's face, each TURNED so the top of the head is the heading (mod map-heads
            // 2026-10-08, "it should just rotate their head ... with the top of their head being their facing
            // direction"; before, an upright face with a heading tick); MapArrowMate still an arrow.
            if (onM != null) {
                RotFace fs = findRotated(greenOn.img, onM[0], selfFace);
                RotFace fh = findRotated(greenOn.img, onM[1], headFace);
                RotFace fa = findRotated(greenOn.img, onM[2], headFace);
                c.note("heads on: his face " + fs + "; MapHeadMate's face " + fh + "; MapArrowMate (no skin) best "
                        + fa.score + "/64");
                if (fs.score < FACE_CELLS) {
                    c.problem("heads ON: his own marker does not draw his skin's face (best " + fs.score + "/64)");
                }
                if (fh.score < FACE_CELLS) {
                    c.problem("heads ON: MapHeadMate (PlayerInfo known) does not draw their face (best " + fh.score
                            + "/64)");
                }
                Outline ao = outline(greenOn.img, greenBase.img, onM[2], GREEN_ROOM, 0xFFFFFF);
                c.note("heads on, MapArrowMate (no skin known): " + onM[2].size() + " px, " + ao);
                if (fa.score >= FACE_CELLS || onM[2].size() < 20 || ao.coverage < MIN_COVERAGE) {
                    c.problem("heads ON: MapArrowMate has no skin known and should keep an outlined arrow ("
                            + onM[2].size() + " px, " + ao + ")");
                }
                if (fs.score >= FACE_CELLS) {
                    headTurn(c, "his", fs, onM[0], SELF_YAW);
                }
                if (fh.score >= FACE_CELLS) {
                    headTurn(c, "MapHeadMate's", fh, onM[1], HEAD_YAW);
                }
            }

            // ---- the brown (Normal) room: arrows still outlined, and visible ----
            setHeads(c, hasSetting, false);
            Shot brownBase = baseShot(c, "brown-base", BROWN_ROOM, away);
            int[] bframe = frameBox(brownBase.img, BROWN_ROOM);
            if (bframe == null) {
                c.problem("no map frame of the brown room colour on screen");
            } else {
                placeMarkers(c, self, mateHead, mateArrow);
                Shot brownOff = shot(c, "brown-heads-off");
                Cluster[] bm = assign(c, "brown, heads off", clusters(brownBase.img, brownOff.img, bframe));
                if (bm != null) {
                    Outline so = outline(brownOff.img, brownBase.img, bm[0], BROWN_ROOM, SELF_GREEN);
                    Outline bo = outline(brownOff.img, brownBase.img, bm[2], BROWN_ROOM, 0xFFFFFF);
                    c.note("brown room, his arrow: " + so);
                    c.note("brown room, MapArrowMate's arrow: " + bo);
                    requireOutline(c, "his arrow on the brown room", so, false);
                    requireOutline(c, "a teammate's arrow on the brown room", bo, false);
                }
            }
            if (hasSetting) {
                Object white = Mod.staticCall("livemap.MapPainter", "outlineFor", 0xFF202020);
                Object black = Mod.staticCall("livemap.MapPainter", "outlineFor", 0xFF000000 | SELF_GREEN);
                c.note(String.format(Locale.ROOT, "outlineFor(dark #202020) = %08X, outlineFor(his green) = %08X",
                        (Integer) white, (Integer) black));
                c.check((Integer) white == 0xFFFFFFFF && (Integer) black == 0xFF000000,
                        "a dark fill must get a light outline and a light fill a dark one");
            }

            // ---- the Interactive Map screen ----
            if (Mod.has("livemap.InteractiveMapScreen") && hasSetting) {
                interactiveMap(c, selfFace, headFace);
            }

            // ---- a single sim room draws at the normal F7 (6x6) cell size ----
            singleSimRoom(c, away);

            // ---- persistence ----
            if (hasSetting) {
                Object before = Mod.cfg(CFG);
                c.onClient(mc -> {
                    Mod.call(Mod.cfg(CFG), "setPlayerHeads", true);
                    Mod.call(Mod.cfg(CFG), "save");
                    Mod.staticCall(CFG, "load");
                    return null;
                });
                Object reloaded = Mod.cfg(CFG);
                boolean on1 = (Boolean) Mod.call(reloaded, "isPlayerHeads");
                c.onClient(mc -> {
                    Mod.call(Mod.cfg(CFG), "setPlayerHeads", false);
                    Mod.call(Mod.cfg(CFG), "save");
                    Mod.staticCall(CFG, "load");
                    return null;
                });
                boolean on2 = (Boolean) Mod.call(Mod.cfg(CFG), "isPlayerHeads");
                c.note("Player Heads after save + load: ON -> " + on1 + ", OFF -> " + on2 + " (new instance "
                        + (reloaded != before) + ")");
                c.check(reloaded != before, "LiveMapConfig.load() kept the same instance - the reload proved nothing");
                c.check(on1 && !on2, "Player Heads did not survive a config reload (" + on1 + ", " + on2 + ")");
            }
        } finally {
            try {
                c.onClient(mc -> {
                    dev.testkit.compat.McCompat.setScreen(mc, null);
                    removeTeammates(mc);
                    for (AutoCloseable r : restore) {
                        try {
                            r.close();
                        } catch (Exception e) {
                            System.out.println("[395-ui-map-heads] restore failed: " + e);
                        }
                    }
                    Mod.call(Mod.cfg(CFG), "save");
                    Mod.staticCall("secrets.DungeonState", "setRoomSim", false);
                    dev.testkit.compat.McCompat.setHudHidden(mc, hideGuiWas);
                    var server = mc.getSingleplayerServer();
                    var uuid = mc.player.getUUID();
                    server.execute(() -> {
                        var sp = server.getPlayerList().getPlayer(uuid);
                        sp.teleportTo(startPos[0], startPos[1], startPos[2]);
                    });
                    return null;
                });
                c.ticks(5);
            } catch (Throwable t) {
                c.note("cleanup: " + UiCase.describe(t));
            }
        }
    }

    // ---- scene ------------------------------------------------------------------------------------------------

    /** World x/z of map room slot (col, row): {@code LiveMapFeature.START_X} plus 32 a slot. */
    static double[] room(int col, int row) {
        return new double[]{START + 32 * col, START + 32 * row};
    }

    static void setHeads(UiCase c, boolean hasSetting, boolean on) {
        if (!hasSetting) {
            return;
        }
        c.onClient(mc -> {
            Mod.call(Mod.cfg(CFG), "setPlayerHeads", on);
            return null;
        });
    }

    /** The no-marker frame: map background {@code room}, teammates hidden, him where his marker is off the frame. */
    static Shot baseShot(UiCase c, String label, int room, double[] away) {
        c.onClient(mc -> {
            Mod.call(Mod.cfg(CFG), "setMapBackground", room);
            Mod.call(Mod.cfg(CFG), "setShowTeammates", false);
            return null;
        });
        teleport(c, away[0], away[1], SELF_YAW);
        return shot(c, label);
    }

    /** Him at {@code self}, teammates shown and (re)added at their rooms. */
    static void placeMarkers(UiCase c, double[] self, double[] mateHead, double[] mateArrow) {
        teleport(c, self[0], self[1], SELF_YAW);
        c.onClient(mc -> {
            Mod.call(Mod.cfg(CFG), "setShowTeammates", true);
            addTeammates(mc, mateHead, mateArrow);
            return null;
        });
        c.ticks(3);
        String seen = c.onClient(mc -> {
            StringBuilder sb = new StringBuilder();
            for (Player p : mc.level.players()) {
                sb.append(p.getGameProfile().name()).append(String.format(Locale.ROOT, "(%.0f,%.0f) ", p.getX(),
                        p.getZ()));
            }
            return sb.toString().trim();
        });
        c.note("players in the client level: " + seen);
    }

    static void teleport(UiCase c, double x, double z, float yaw) {
        c.onClient(mc -> {
            mc.player.getAbilities().flying = true;
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                sp.getAbilities().mayfly = true;
                sp.getAbilities().flying = true;
                sp.onUpdateAbilities();
                sp.teleportTo(server.overworld(), x, FLY_Y, z, java.util.Set.<net.minecraft.world.entity.Relative>of(),
                        yaw, 0f, false);
            });
            return null;
        });
        c.ctx().waitFor(mc -> Math.abs(mc.player.getX() - x) < 0.01 && Math.abs(mc.player.getZ() - z) < 0.01, 200);
        c.ticks(5);
        c.onClient(mc -> {
            mc.player.getAbilities().flying = true;
            mc.player.setYRot(yaw);
            mc.player.setXRot(0f);
            return null;
        });
    }

    /** MapHeadMate gets a PlayerInfo the way a server sends one; MapArrowMate gets none. Both are client entities. */
    static void addTeammates(Minecraft mc, double[] head, double[] arrow) {
        if (mc.getConnection().getPlayerInfo(HEAD_ID) == null) {
            GameProfile profile = new GameProfile(HEAD_ID, HEAD_NAME);
            ClientboundPlayerInfoUpdatePacket.Entry entry = new ClientboundPlayerInfoUpdatePacket.Entry(HEAD_ID,
                    profile, true, 0, GameType.SURVIVAL, Component.literal(HEAD_NAME), true, 0, null);
            ClientboundPlayerInfoUpdatePacket packet = new ClientboundPlayerInfoUpdatePacket(
                    EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE),
                    List.of());
            try {
                Field f = ClientboundPlayerInfoUpdatePacket.class.getDeclaredField("entries");
                f.setAccessible(true);
                f.set(packet, List.of(entry));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not fill the player info packet: " + e, e);
            }
            mc.getConnection().handlePlayerInfoUpdate(packet);
        }
        spawn(mc, HEAD_ENTITY, new GameProfile(HEAD_ID, HEAD_NAME), head, HEAD_YAW);
        spawn(mc, ARROW_ENTITY, new GameProfile(ARROW_ID, ARROW_NAME), arrow, ARROW_YAW);
    }

    static void spawn(Minecraft mc, int id, GameProfile profile, double[] at, float yaw) {
        Entity existing = mc.level.getEntity(id);
        if (existing != null) {
            existing.setPos(at[0], FLY_Y, at[1]);
            existing.setYRot(yaw);
            return;
        }
        RemotePlayer p = new RemotePlayer(mc.level, profile);
        p.setId(id);
        p.setPos(at[0], FLY_Y, at[1]);
        p.setYRot(yaw);
        p.setYHeadRot(yaw);
        mc.level.addEntity(p);
    }

    static void removeTeammates(Minecraft mc) {
        for (int id : new int[]{HEAD_ENTITY, ARROW_ENTITY}) {
            if (mc.level != null && mc.level.getEntity(id) != null) {
                mc.level.removeEntity(id, Entity.RemovalReason.DISCARDED);
            }
        }
        if (mc.getConnection() != null && mc.getConnection().getPlayerInfo(HEAD_ID) != null) {
            mc.getConnection().handlePlayerInfoRemove(new ClientboundPlayerInfoRemovePacket(List.of(HEAD_ID)));
        }
    }

    // ---- a single sim room ------------------------------------------------------------------------------------

    /**
     * killer560, 2026-10-07: "if it is only a single room on the map for sim, then have it at the normal F7 scale
     * size, not scaled up for just the one room." The sim's own single-room publish ({@code SimBuilder.
     * publishSingleRoomMap}, what Load a Room and All Rooms call) of the built-in flat test room at room slot (2,2),
     * with the sim flagged active so the map fits the way it does in the sim. The map's fit scale must be the 6x6
     * floor's (1.0, a room 16 units), and the room's fill on screen must measure the 6x6 cell size within one unit.
     */
    static void singleSimRoom(UiCase c, double[] away) {
        if (!Mod.has("roomsim.FlatTestRoom") || !Mod.has("roomsim.SimBuilder")) {
            c.note("single sim room: this jar has no sim - skipped");
            return;
        }
        c.onClient(mc -> {
            Mod.call(Mod.cfg(CFG), "setShowTeammates", false);
            Mod.call(Mod.cfg(CFG), "setMapBackground", GREEN_ROOM);
            return null;
        });
        teleport(c, away[0], away[1], SELF_YAW);
        Shot base = shot(c, "sim-room-base");
        int[] frame = frameBox(base.img, GREEN_ROOM);
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isOnSkyblock"));
        try {
            c.onClient(mc -> {
                Mod.setField("roomsim.SimState", "active", true);
                Object room = Mod.staticCall("roomsim.FlatTestRoom", "build");
                Mod.staticCall("roomsim.SimBuilder", "publishSingleRoomMap", room, 4);
                return null;
            });
            c.ticks(10);
            float[] fit = c.onClient(mc -> (float[]) Mod.staticCall("livemap.MapPainter", "floorFit",
                    Mod.staticCall("livemap.LiveMapFeature", "groupsView")));
            int roomPx = c.onClient(mc -> (Integer) Mod.call(Mod.cfg(CFG), "getRoomPx"));
            double hudScale = c.onClient(mc -> ((Number) Mod.staticCall("hud.HudElementRegistry", "resolveScale",
                    Mod.staticCall("hud.HudElementRegistry", "byId", "live_map"))).doubleValue());
            double gui = c.onClient(mc -> (double) mc.getWindow().getGuiScale());
            double cellUnits = 16 * (roomPx / 16.0) * fit[0];
            Shot built = shot(c, "sim-room-built");
            List<Cluster> drawn = frame == null ? List.of() : clusters(base.img, built.img, frame);
            Cluster biggest = null;
            for (Cluster cl : drawn) {
                if (biggest == null || cl.size() > biggest.size()) {
                    biggest = cl;
                }
            }
            double px = biggest == null ? 0 : Math.max(biggest.x1 - biggest.x0 + 1, biggest.y1 - biggest.y0 + 1);
            double drawnUnits = px / gui / hudScale;
            c.note(String.format(Locale.ROOT, "single sim room: map fit scale %.3f (6x6 floor = 1.000); cell %.1f HUD "
                    + "units, 6x6 cell %d; drawn room %s = %.1f units (gui %.0f, HUD scale %.2f)", fit[0], cellUnits,
                    roomPx, biggest, drawnUnits, gui, hudScale));
            if (Math.abs(cellUnits - roomPx) > 1) {
                c.problem(String.format(Locale.ROOT, "a single sim room is drawn at %.1f units a cell, not the 6x6 "
                        + "floor's %d (fit scale %.2f)", cellUnits, roomPx, fit[0]));
            }
            if (biggest == null) {
                c.problem("a single sim room drew nothing on the map");
            } else if (Math.abs(drawnUnits - roomPx) > 1) {
                c.problem(String.format(Locale.ROOT, "a single sim room measures %.1f units on screen, the 6x6 cell "
                        + "is %d", drawnUnits, roomPx));
            }
        } finally {
            c.onClient(mc -> {
                Mod.setField("roomsim.SimState", "active", false);
                Mod.staticCall("livemap.LiveMapFeature", "resetGrid", "395-ui-map-heads cleanup");
                return null;
            });
            c.ticks(2);
            removeSimSidebar(c, gateWas);
        }
    }

    /**
     * While {@code SimState.active} is set, the sim's {@code SimSidebar} writes its "SKYBLOCK" objective
     * ({@code k560sim}) into THIS world's scoreboard - in the sim that is the sim's own world, here it is the UI
     * suite's. Flipping the flag back by reflection skips the sim's teardown, so the objective stayed on the sidebar,
     * {@code SkyblockGate} read the UI world as Skyblock, and 401-ui-scoreboard-editor (next in the suite) drew the
     * live Skyblock board instead of its preview (2026-10-07). Take the objective and its teams off on the server,
     * forget the sidebar's state, and put the gate's verdict back - it would otherwise hold "Skyblock" for 10 s.
     */
    static void removeSimSidebar(UiCase c, boolean gateWas) {
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        c.onClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                var board = server.getScoreboard();
                var objective = board.getObjective("k560sim");
                if (objective != null) {
                    board.removeObjective(objective);
                }
                for (int i = 0; i < 16; i++) {
                    var team = board.getPlayerTeam("k560t" + i);
                    if (team != null) {
                        board.removePlayerTeam(team);
                    }
                }
                done.set(true);
            });
            return null;
        });
        c.ctx().waitFor(mc -> done.get(), 100);
        c.ctx().waitFor(mc -> mc.level.getScoreboard().getObjective("k560sim") == null, 100);
        c.onClient(mc -> {
            Mod.staticCall("roomsim.SimSidebar", "reset");
            Mod.setField("util.SkyblockGate", "onSkyblock", gateWas);
            return null;
        });
        c.ticks(2);
        boolean sb = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isOnSkyblock"));
        c.note("sim sidebar removed; SkyblockGate.isOnSkyblock " + sb + " (was " + gateWas + " before the sim room)");
        c.check(sb == gateWas, "the sim room's sidebar still makes the UI world read as Skyblock");
    }

    // ---- the Interactive Map ------------------------------------------------------------------------------------

    static void interactiveMap(UiCase c, int[] selfFace, int[] headFace) {
        c.onClient(mc -> {
            Mod.call(Mod.cfg(CFG), "setEnabled", false); // the HUD map must not change under the screen
            Mod.call(Mod.cfg(CFG), "setPlayerHeads", false);
            try {
                Screen s = (Screen) Mod.cls("livemap.InteractiveMapScreen").getConstructor(boolean.class)
                        .newInstance(false);
                dev.testkit.compat.McCompat.setScreen(mc, s);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            return null;
        });
        c.ticks(5);
        Shot imOff = shot(c, "im-heads-off");
        c.onClient(mc -> {
            Mod.call(Mod.cfg(CFG), "setPlayerHeads", true);
            return null;
        });
        c.ticks(3);
        Shot imOn = shot(c, "im-heads-on");
        int[] whole = {0, 0, imOn.img.getWidth() - 1, imOn.img.getHeight() - 1};
        List<Cluster> diff = clusters(imOff.img, imOn.img, whole);
        int bestSelf = 0;
        int bestHead = 0;
        for (Cluster cl : diff) {
            bestSelf = Math.max(bestSelf, findRotated(imOn.img, cl, selfFace).score);
            bestHead = Math.max(bestHead, findRotated(imOn.img, cl, headFace).score);
        }
        int bestOffSelf = 0;
        for (Cluster cl : diff) {
            bestOffSelf = Math.max(bestOffSelf, findRotated(imOff.img, cl, selfFace).score);
        }
        c.note("Interactive Map: " + diff.size() + " changed region(s) heads off -> on; his face " + bestSelf
                + "/64, MapHeadMate's " + bestHead + "/64; heads off his face " + bestOffSelf + "/64");
        if (bestSelf < FACE_CELLS || bestHead < FACE_CELLS) {
            c.problem("the Interactive Map does not draw the heads (his " + bestSelf + "/64, MapHeadMate's "
                    + bestHead + "/64)");
        }
        if (bestOffSelf >= FACE_CELLS) {
            c.problem("the Interactive Map draws his face with Player Heads OFF");
        }
        c.onClient(mc -> {
            dev.testkit.compat.McCompat.setScreen(mc, null);
            Mod.call(Mod.cfg(CFG), "setEnabled", true);
            Mod.call(Mod.cfg(CFG), "setPlayerHeads", false);
            return null;
        });
        c.ticks(2);
    }

    // ---- skins ------------------------------------------------------------------------------------------------

    /** The 8x8 face (face layer, hat layer alpha-blended over it) of the skin the client holds for a player, as
     *  64 RGB values; {@code self} reads his own PlayerInfo. */
    static int[] expectedFace(UiCase c, UUID id, boolean self) {
        return c.onClient(mc -> {
            PlayerInfo info = self ? mc.getConnection().getPlayerInfo(mc.player.getUUID())
                    : mc.getConnection().getPlayerInfo(id);
            if (info == null) {
                return null;
            }
            Identifier tex = info.getSkin().body().texturePath();
            BufferedImage img;
            try (InputStream in = mc.getResourceManager().getResource(tex).orElseThrow().open()) {
                img = javax.imageio.ImageIO.read(in);
            } catch (Exception e) {
                System.out.println("[395-ui-map-heads] could not read " + tex + ": " + e);
                return null;
            }
            c.note((self ? "his" : HEAD_NAME + "'s") + " skin: " + tex);
            int[] out = new int[64];
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int face = img.getRGB(8 + x, 8 + y);
                    int hat = img.getRGB(40 + x, 8 + y);
                    double a = ((hat >>> 24) & 0xFF) / 255.0;
                    int r = (int) Math.round(((face >> 16) & 0xFF) * (1 - a) + ((hat >> 16) & 0xFF) * a);
                    int g = (int) Math.round(((face >> 8) & 0xFF) * (1 - a) + ((hat >> 8) & 0xFF) * a);
                    int b = (int) Math.round((face & 0xFF) * (1 - a) + (hat & 0xFF) * a);
                    out[y * 8 + x] = (r << 16) | (g << 8) | b;
                }
            }
            return out;
        });
    }

    record Face(int score, int x0, int y0, int side) {
        @Override
        public String toString() {
            return score + "/64 cells at " + x0 + "," + y0 + " side " + side + " px";
        }
    }

    /** The best 8x8 grid (any square inside the cluster's box, padded 2 px) against a skin face. */
    static Face findFace(BufferedImage img, Cluster cl, int[] face) {
        if (face == null) {
            return new Face(0, 0, 0, 0);
        }
        int bx0 = Math.max(0, cl.x0 - 2), by0 = Math.max(0, cl.y0 - 2);
        int bx1 = Math.min(img.getWidth() - 1, cl.x1 + 2), by1 = Math.min(img.getHeight() - 1, cl.y1 + 2);
        int maxSide = Math.min(bx1 - bx0 + 1, by1 - by0 + 1);
        Face best = new Face(0, 0, 0, 0);
        for (int side = 8; side <= maxSide; side++) {
            for (int y0 = by0; y0 + side - 1 <= by1; y0++) {
                for (int x0 = bx0; x0 + side - 1 <= bx1; x0++) {
                    int score = 0;
                    for (int k = 0; k < 64 && score + (64 - k) > best.score; k++) {
                        int px = x0 + (int) (((k % 8) + 0.5) * side / 8.0);
                        int py = y0 + (int) (((k / 8) + 0.5) * side / 8.0);
                        if (delta(img.getRGB(px, py), face[k]) <= 60) {
                            score++;
                        }
                    }
                    if (score > best.score) {
                        best = new Face(score, x0, y0, side);
                    }
                }
            }
        }
        return best;
    }

    /** A face found at any turn: cells matched, the turn (degrees clockwise on screen, 0 = upright), its centre and
     *  side in screen pixels. */
    record RotFace(int score, int angle, double cx, double cy, double side) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%d/64 cells, turned %d deg, centre %.1f,%.1f, side %.1f px", score, angle,
                    cx, cy, side);
        }
    }

    /**
     * The best 8x8 grid against a skin face at any turn: every 5 degrees, every side from 8 px to the cluster's
     * narrower box side, the centre within 8 px of the cluster box's centre. A cell is sampled at its centre turned
     * about the grid's centre (screen y down, so a positive angle is clockwise, as the mod's pose rotate draws it).
     */
    static RotFace findRotated(BufferedImage img, Cluster cl, int[] face) {
        if (face == null) {
            return new RotFace(0, 0, 0, 0, 0);
        }
        // The cluster's box, padded, as an array: millions of samples, getRGB is too slow for them.
        int bx0 = Math.max(0, cl.x0 - 4), by0 = Math.max(0, cl.y0 - 4);
        int bx1 = Math.min(img.getWidth() - 1, cl.x1 + 4), by1 = Math.min(img.getHeight() - 1, cl.y1 + 4);
        int bw = bx1 - bx0 + 1, bh = by1 - by0 + 1;
        int[] px = img.getRGB(bx0, by0, bw, bh, null, 0, bw);
        int maxSide = Math.min(cl.x1 - cl.x0 + 1, cl.y1 - cl.y0 + 1) + 2;
        // The head is most of the marker, but an upright head with a heading tick (mod before map-heads) puts the box
        // centre off the face's: look up to 8 px either way.
        double bcx = (cl.x0 + cl.x1 + 1) / 2.0, bcy = (cl.y0 + cl.y1 + 1) / 2.0;
        RotFace best = new RotFace(0, 0, 0, 0, 0);
        for (int deg = 0; deg < 360; deg += 5) {
            double cos = Math.cos(Math.toRadians(deg)), sin = Math.sin(Math.toRadians(deg));
            for (int side = Math.max(8, maxSide / 2); side <= maxSide; side++) {
                for (int ox = -8; ox <= 8; ox++) {
                    for (int oy = -8; oy <= 8; oy++) {
                        double cx = bcx + ox, cy = bcy + oy;
                        int score = 0;
                        for (int k = 0; k < 64 && score + (64 - k) > best.score; k++) {
                            double u = ((k % 8) + 0.5) / 8.0 * side - side / 2.0;
                            double v = ((k / 8) + 0.5) / 8.0 * side - side / 2.0;
                            int x = (int) Math.floor(cx + u * cos - v * sin) - bx0;
                            int y = (int) Math.floor(cy + u * sin + v * cos) - by0;
                            if (x >= 0 && y >= 0 && x < bw && y < bh && delta(px[y * bw + x], face[k]) <= 60) {
                                score++;
                            }
                        }
                        if (score > best.score) {
                            best = new RotFace(score, deg, cx, cy, side);
                        }
                    }
                }
            }
        }
        return best;
    }

    /** The turn the mod gives a head for this yaw: the arrow's, {@code 180 + yaw}, on the north-up map. */
    static int expectedTurn(float yaw) {
        return (int) Math.floorMod(Math.round(180 + yaw), 360);
    }

    static int angleOff(int a, int b) {
        int d = Math.floorMod(a - b, 360);
        return Math.min(d, 360 - d);
    }

    /** The head's top points along the heading, and nothing (no arrow, no tick) reaches past the framed head. */
    static void headTurn(UiCase c, String who, RotFace f, Cluster cl, float yaw) {
        int want = expectedTurn(yaw);
        int off = angleOff(f.angle(), want);
        // The framed head's half-diagonal (face side + a frame unit each side) plus 2 px of edge blending.
        double reach = (f.side() / 2.0 + f.side() / 8.0) * Math.sqrt(2) + 2;
        int beyond = 0;
        for (int[] p : cl.pixels) {
            if (Math.hypot(p[0] + 0.5 - f.cx(), p[1] + 0.5 - f.cy()) > reach) {
                beyond++;
            }
        }
        c.note(String.format(Locale.ROOT, "%s head: %s; yaw %.0f wants a %d deg turn, off by %d; %d px beyond the"
                + " framed head (reach %.1f px)", who, f, yaw, want, off, beyond, reach));
        if (off > 10) {
            c.problem(String.format(Locale.ROOT, "%s head is turned %d deg, the heading (yaw %.0f) wants %d", who,
                    f.angle(), yaw, want));
        }
        if (beyond > 0) {
            c.problem(who + " head has " + beyond + " px drawn beyond the framed head - an arrow or tick is still drawn");
        }
    }

    // ---- outline ------------------------------------------------------------------------------------------------

    record Outline(int boundary, int inOutline, double coverage, double contrast, double fillContrast) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%d boundary px, %d in the outline colour (%.0f%%), boundary contrast "
                    + "%.2f:1 against the room, fill contrast %.2f:1", boundary, inOutline, coverage * 100, contrast,
                    fillContrast);
        }
    }

    /**
     * Boundary pixels: cluster pixels with a 4-neighbour the frame left unchanged. The outline colour is whichever of
     * black / white is further from the fill; coverage is the boundary's share within 90 summed RGB of it. Contrast is
     * WCAG's, the mean boundary colour against the room.
     */
    static Outline outline(BufferedImage img, BufferedImage base, Cluster cl, int room, int fill) {
        double fillLuma = luminance(fill);
        int outlineColor = fillLuma > 0.18 ? 0x000000 : 0xFFFFFF;
        java.util.Set<Long> in = new java.util.HashSet<>();
        for (int[] p : cl.pixels) {
            in.add(((long) p[0] << 32) | p[1]);
        }
        int boundary = 0, dark = 0;
        double r = 0, g = 0, b = 0;
        for (int[] p : cl.pixels) {
            boolean edge = false;
            int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : nb) {
                int x = p[0] + d[0], y = p[1] + d[1];
                if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) {
                    continue;
                }
                if (!in.contains(((long) x << 32) | y) && !changed(base.getRGB(x, y), img.getRGB(x, y))) {
                    edge = true;
                }
            }
            if (!edge) {
                continue;
            }
            boundary++;
            int px = img.getRGB(p[0], p[1]);
            if (delta(px, outlineColor) <= 90) {
                dark++;
            }
            r += (px >> 16) & 0xFF;
            g += (px >> 8) & 0xFF;
            b += px & 0xFF;
        }
        if (boundary == 0) {
            return new Outline(0, 0, 0, 1, 1);
        }
        int mean = ((int) (r / boundary) << 16) | ((int) (g / boundary) << 8) | (int) (b / boundary);
        return new Outline(boundary, dark, dark / (double) boundary, contrast(mean, room), contrast(fill, room));
    }

    static void requireOutline(UiCase c, String what, Outline o, boolean outlineMustContrast) {
        if (o.boundary < 10) {
            c.problem(what + ": no marker found (" + o + ")");
            return;
        }
        if (o.coverage < MIN_COVERAGE) {
            c.problem(String.format(Locale.ROOT, "%s: only %.0f%% of its edge is outline (need %.0f%%)", what,
                    o.coverage * 100, MIN_COVERAGE * 100));
        }
        double best = outlineMustContrast ? o.contrast : Math.max(o.contrast, o.fillContrast);
        if (best < MIN_CONTRAST) {
            c.problem(String.format(Locale.ROOT, "%s: contrast %.2f:1 against the room, need %.1f:1", what, best,
                    MIN_CONTRAST));
        }
    }

    static double luminance(int rgb) {
        double[] ch = {((rgb >> 16) & 0xFF) / 255.0, ((rgb >> 8) & 0xFF) / 255.0, (rgb & 0xFF) / 255.0};
        for (int i = 0; i < 3; i++) {
            ch[i] = ch[i] <= 0.03928 ? ch[i] / 12.92 : Math.pow((ch[i] + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2];
    }

    static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    // ---- pixels -------------------------------------------------------------------------------------------------

    record Shot(BufferedImage img) {
    }

    static Shot shot(UiCase c, String label) {
        c.onClient(mc -> {
            dev.testkit.compat.McCompat.clearChatAndToasts(mc);
            return null;
        });
        c.ticks(4);
        String yaws = c.onClient(mc -> {
            StringBuilder sb = new StringBuilder("his yaw " + mc.player.getYRot() + "; map markers:");
            for (Object mp : (List<?>) Mod.staticCall("livemap.InteractiveMapFeature", "playersCached", mc)) {
                sb.append(' ').append(mp);
            }
            return sb.toString();
        });
        c.note(label + ": " + yaws);
        String name = c.name() + "-" + label;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        Path kept = dev.testkit.harness.Report.screenshot(name, taken);
        c.note(label + " -> " + (kept != null ? kept : taken));
        try {
            return new Shot(javax.imageio.ImageIO.read(taken.toFile()));
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read " + taken + ": " + e);
        }
    }

    static int delta(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF));
    }

    static boolean changed(int a, int b) {
        return delta(a, b) > 30;
    }

    /** Bounding box of the pixels within 8 of the room colour - the map's interior. */
    static int[] frameBox(BufferedImage img, int room) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (delta(img.getRGB(x, y), room) <= 8) {
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                    n++;
                }
            }
        }
        return n < 400 ? null : new int[]{x0, y0, x1, y1};
    }

    static final class Cluster {
        final List<int[]> pixels = new ArrayList<>();
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;

        void add(int x, int y) {
            pixels.add(new int[]{x, y});
            x0 = Math.min(x0, x);
            y0 = Math.min(y0, y);
            x1 = Math.max(x1, x);
            y1 = Math.max(y1, y);
        }

        int size() {
            return pixels.size();
        }

        double cx() {
            return (x0 + x1) / 2.0;
        }

        double cy() {
            return (y0 + y1) / 2.0;
        }

        @Override
        public String toString() {
            return size() + " px at " + x0 + "," + y0 + ".." + x1 + "," + y1;
        }
    }

    /** Changed pixels inside {@code box}, grouped by 8-connectivity with a 2 px reach; groups under 15 px dropped. */
    static List<Cluster> clusters(BufferedImage base, BufferedImage img, int[] box) {
        int w = img.getWidth(), h = img.getHeight();
        boolean[] ch = new boolean[w * h];
        for (int y = Math.max(0, box[1]); y <= Math.min(h - 1, box[3]); y++) {
            for (int x = Math.max(0, box[0]); x <= Math.min(w - 1, box[2]); x++) {
                ch[y * w + x] = changed(base.getRGB(x, y), img.getRGB(x, y));
            }
        }
        boolean[] seen = new boolean[w * h];
        List<Cluster> out = new ArrayList<>();
        for (int i = 0; i < ch.length; i++) {
            if (!ch[i] || seen[i]) {
                continue;
            }
            Cluster cl = new Cluster();
            ArrayDeque<Integer> q = new ArrayDeque<>();
            q.add(i);
            seen[i] = true;
            while (!q.isEmpty()) {
                int p = q.poll();
                int px = p % w, py = p / w;
                cl.add(px, py);
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        int x = px + dx, y = py + dy;
                        if (x < 0 || y < 0 || x >= w || y >= h) {
                            continue;
                        }
                        int j = y * w + x;
                        if (ch[j] && !seen[j]) {
                            seen[j] = true;
                            q.add(j);
                        }
                    }
                }
            }
            if (cl.size() >= 15) {
                out.add(cl);
            }
        }
        return out;
    }

    /** {self, MapHeadMate, MapArrowMate} by layout: him top-left, MapHeadMate to his right, MapArrowMate below. */
    static Cluster[] assign(UiCase c, String what, List<Cluster> found) {
        c.note(what + ": " + found.size() + " marker region(s): " + found);
        if (found.size() != 3) {
            c.problem(what + ": expected 3 marker regions on the map, found " + found.size() + " " + found);
            return null;
        }
        Cluster self = null, right = null, below = null;
        for (Cluster cl : found) {
            if (self == null || cl.cx() + cl.cy() < self.cx() + self.cy()) {
                self = cl;
            }
            if (right == null || cl.cx() - cl.cy() > right.cx() - right.cy()) {
                right = cl;
            }
            if (below == null || cl.cy() - cl.cx() > below.cy() - below.cx()) {
                below = cl;
            }
        }
        if (self == right || self == below || right == below) {
            c.problem(what + ": the marker regions are not laid out as placed " + found);
            return null;
        }
        return new Cluster[]{self, right, below};
    }

    static boolean hasMethod(Object target, String name) {
        for (java.lang.reflect.Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }
}

package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * simmobs2-1101 (doc-audit-1101 package P4): proofs on a generated sim floor WITH MOBS (the Map Designer's Mobs floor,
 * {@link SimMobFloorTests#buildMobFloor}), where earlier cases only drove test hooks.
 * <ul>
 *   <li><b>1102-sim-instaclear</b> - Insta Clear Warning's real map path. He teleports from the Entrance straight into a key
 *       room nobody has discovered, so the sim insta clears it and its key is lost ("Sim key room: X insta cleared"). The
 *       warning must then come from the map alone (KeyRooms.guards, the map's cleared check, the locked door): the
 *       warning itself, the party message as a real {@code /pc} packet, the HUD popup drawn, and the requeue
 *       ({@code /joininstance catacombs_floor_seven}) only once the creation cooldown (5 s left at the teleport) and the
 *       Requeue Delay (1 s) are over. Control: nothing warns while he stands in the Entrance before it.</li>
 *   <li><b>1102-sim-felskulls</b> - Dungeon ESP on the sim's real Fels and starred mobs: every sleeping STARRED Fel's skull
 *       (an invisible enderman under it) is drawn in the Fel color; an unstarred skull with no starred mob within the pack
 *       radius is not drawn while its room's starred mobs live. Then the starred highlight at two room tiles: for a mob
 *       room at least 56 blocks away, the server takes its star-tag stands away (as Hypixel's shorter misc-entity range
 *       does) and every one of its starred mobs must stay drawn in the starred color.</li>
 * </ul>
 * Only when named. GrimAC does not apply (the sim's integrated server).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimMobP4Tests implements FabricClientGameTest {

    static final String IC = "1102-sim-instaclear";
    static final String FELS = "1102-sim-felskulls";
    private static final String ICW = "com.killer560.hub.instaclearwarning.InstaClearWarningFeature";
    private static final String ICW_CFG = "com.killer560.hub.instaclearwarning.InstaClearWarningConfig";
    private static final String CC = "com.killer560.hub.creationcooldown.CreationCooldownFeature";
    private static final String CC_CFG = "com.killer560.hub.creationcooldown.CreationCooldownConfig";
    private static final String DQ_CFG = "com.killer560.hub.dungeonqueue.DungeonQueueConfig";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String KEY_ROOMS = "com.killer560.hub.autoclear.KeyRooms";
    private static final String SIM_FLOOR = "com.killer560.hub.roomsim.SimMobFloor";
    private static final String SIM_KEY_ROOM = "com.killer560.hub.roomsim.SimKeyRoom";
    private static final String SIM_MOBS = "com.killer560.hub.roomsim.SimMobs";
    private static final String ESP = "com.killer560.hub.mobesp.MobEspFeature";
    private static final String ESP_CFG = "com.killer560.hub.mobesp.MobEspConfig";
    private static final String LINE = "Blood rush room was instacleared we must restart";

    private static String current = "";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank()) {
            return;
        }
        for (String name : List.of(IC, FELS)) {
            if (Scenario.skip(name)) {
                continue;
            }
            current = name;
            ModUnderTest.require("killer560smod");
            LogTap.install();
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
            if (SimMapTests.copyRoomsForOthers() < 20) {
                Scenario.skipped(name, "needs real room captures and room database");
                continue;
            }
            Scenario.ensureRoomDatabase(ctx);
            ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "forceReload"));
            ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "isReady"), 2400);
            ctx.runOnClient(mc -> {
                ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                        "setAutoPuzzlesMasterEnabled", false);
                Object map = ModUnderTest.config("com.killer560.hub.livemap.LiveMapConfig");
                ModUnderTest.set(map, "setEnabled", true);
            });
            int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
            ctx.runOnClient(mc -> mc.options.renderDistance().set(16));
            List<String> problems = new ArrayList<>();
            List<String> facts = new ArrayList<>();
            try {
                if (SimMobFloorTests.buildMobFloor(ctx, problems, facts)) {
                    if (name.equals(IC)) {
                        instaClear(ctx, problems, facts);
                    } else {
                        felSkulls(ctx, problems, facts);
                    }
                }
            } catch (Throwable t) {
                problems.add("threw: " + t);
                t.printStackTrace(System.out);
            } finally {
                dev.testkit.harness.PacketTrace.tap = null;
                ctx.runOnClient(mc -> {
                    Object icw = ModUnderTest.config(ICW_CFG);
                    ModUnderTest.set(icw, "setEnabled", false);
                    ModUnderTest.staticCall(CC, "testReset");
                });
                SimMobFloorTests.leave(ctx);
                ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
                ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_FLOOR, "setEnabled", new Class<?>[]{boolean.class},
                        new Object[]{false}));
            }
            for (String f : facts) {
                println("FACT " + f);
            }
            if (!problems.isEmpty()) {
                for (String p : problems) {
                    println("PROBLEM " + p);
                }
                throw new AssertionError(name + ": " + problems.size() + " problem(s): " + String.join("; ", problems));
            }
            println("PASS");
        }
    }

    // ---- 1102-sim-instaclear ------------------------------------------------------------------------------------------

    private static void instaClear(ClientGameTestContext ctx, List<String> problems, List<String> facts) {
        ctx.runOnClient(mc -> {
            Object icw = ModUnderTest.config(ICW_CFG);
            ModUnderTest.set(icw, "setEnabled", true);
            ModUnderTest.set(icw, "setHudPopup", true);
            ModUnderTest.set(icw, "setPartyMessage", true);
            ModUnderTest.call(icw, "setMessage", new Class<?>[]{String.class}, new Object[]{LINE});
            ModUnderTest.set(icw, "setRequeue", true);
            ModUnderTest.call(ModUnderTest.config(DQ_CFG), "setDelaySeconds", new Class<?>[]{int.class}, new Object[]{1});
            ModUnderTest.set(ModUnderTest.config(CC_CFG), "setBlockCreation", false);
        });
        List<Object[]> sent = new CopyOnWriteArrayList<>();
        dev.testkit.harness.PacketTrace.tap = p -> {
            if (p instanceof ServerboundChatCommandPacket cmd) {
                sent.add(new Object[]{System.currentTimeMillis(), cmd.command()});
            }
        };
        facts.add("guards at GO: " + ctx.computeOnClient(mc -> guards()));
        // Control: in the Entrance, nothing cleared, nothing warns.
        int w0 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(ICW, "warnings"));
        ctx.waitTicks(100);
        int w1 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(ICW, "warnings"));
        facts.add("control: warnings in the Entrance over 5 s: " + (w1 - w0));
        if (w1 != w0) {
            problems.add("CONTROL FAILED: Insta Clear Warning warned with nothing cleared");
            return;
        }
        @SuppressWarnings("unchecked")
        List<String> keyRooms = ctx.computeOnClient(mc -> (List<String>) ModUnderTest.staticCall(SIM_FLOOR, "keyRoomsForTest"));
        // Not the first key room: the room before it is where he stands at GO with its door open, so the sim counts it
        // discovered (no insta clear). Any later Wither key room sits behind a shut door he has not stood at.
        String target = null;
        for (int i = 1; i < keyRooms.size() && target == null; i++) {
            String[] k = keyRooms.get(i).split("\\|");
            if ("wither".equals(k[1])) {
                target = k[0];
            }
        }
        facts.add("key rooms " + keyRooms + "; target " + target);
        if (target == null) {
            problems.add("no undiscovered Wither key room to insta clear (key rooms " + keyRooms + ")");
            return;
        }
        final String room = target;
        BlockPos spot = standIn(ctx, room);
        if (spot == null) {
            problems.add("no block to stand on found in " + room);
            return;
        }
        long markTp = LogTap.mark();
        long ccStart = System.currentTimeMillis() - 25_000L;
        ctx.runOnClient(mc -> ModUnderTest.staticCall(CC, "start", new Class<?>[]{long.class}, new Object[]{ccStart}));
        long ccEnd = ccStart + 30_000L;
        int wBefore = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(ICW, "warnings"));
        SimMobFloorTests.teleport(ctx, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
        boolean insta = false;
        for (int i = 0; i < 100 && !insta; i += 5) {
            ctx.waitTicks(5);
            insta = LogTap.since(markTp).stream().anyMatch(l -> l.contains("Sim key room: " + room + " insta cleared"));
        }
        facts.add("teleported into " + room + " at " + spot.toShortString() + ": sim insta clear " + insta);
        if (!insta) {
            problems.add("the sim did not insta clear " + room + " on the teleport - nothing to warn about");
            return;
        }
        long warnAt = -1;
        for (int i = 0; i < 400 && warnAt < 0; i += 2) {
            ctx.waitTicks(2);
            if (ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(ICW, "warnings")) > wBefore) {
                warnAt = System.currentTimeMillis();
            }
        }
        for (String l : LogTap.since(markTp)) {
            if (l.contains("[InstaClearWarning]")) {
                println("  " + l.replaceAll("^.*\\[InstaClearWarning\\]", "[InstaClearWarning]"));
            }
        }
        String warned = ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(ICW, "lastWarnedRoom")));
        facts.add("warning: " + (warnAt < 0 ? "NONE in 20 s" : "for " + warned) + "; guards now " + ctx.computeOnClient(mc -> guards())
                + "; quiet ends " + ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(ICW, "quietEnds"))));
        if (warnAt < 0 || !room.equals(warned)) {
            problems.add("Insta Clear Warning did not warn for " + room + " from the map (last warned " + warned + ")");
            return;
        }
        boolean waited = LogTap.since(markTp).stream().anyMatch(l -> l.contains("[InstaClearWarning] " + room)
                && l.contains("cleared on the map - waiting for its key"));
        if (!waited) {
            problems.add("the warning did not come through the map's cleared check (no 'cleared on the map' line)");
        }
        // The party line, the popup, the requeue after the cooldown.
        String pc = null;
        for (int i = 0; i < 40 && pc == null; i++) {
            pc = firstSent(sent, "pc ");
            if (pc == null) {
                ctx.waitTicks(1);
            }
        }
        facts.add("party message sent: " + pc);
        if (!("pc " + LINE).equals(pc)) {
            problems.add("the party message was " + pc);
        }
        String drawn = null;
        for (int i = 0; i < 40 && !room.equals(drawn); i++) {
            ctx.waitTicks(1);
            drawn = ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(ICW, "lastDrawn")));
        }
        facts.add("HUD popup drawn for: " + drawn);
        if (!room.equals(drawn)) {
            problems.add("the HUD popup was not drawn for " + room + " (" + drawn + ")");
        }
        boolean pendingAtWarn = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(ICW, "requeuePending"));
        boolean earlyRequeue = firstSent(sent, "joininstance") != null && System.currentTimeMillis() < ccEnd;
        Object[] requeue = null;
        for (int i = 0; i < 400 && requeue == null; i += 2) {
            ctx.waitTicks(2);
            for (Object[] s : sent) {
                if (((String) s[1]).startsWith("joininstance")) {
                    requeue = s;
                    break;
                }
            }
        }
        long sentAt = requeue == null ? -1 : (Long) requeue[0];
        facts.add(String.format(Locale.US, "requeue: pending at the warning %s, sent %s %.1f s after the cooldown ended"
                + " (warning %.1f s before it ended)", pendingAtWarn, requeue == null ? "NONE" : "/" + requeue[1],
                (sentAt - ccEnd) / 1000.0, (ccEnd - warnAt) / 1000.0));
        if (requeue == null || !"joininstance catacombs_floor_seven".equals(requeue[1])) {
            problems.add("no requeue /joininstance catacombs_floor_seven was sent (" + (requeue == null ? "none" : requeue[1]) + ")");
        } else if (earlyRequeue || sentAt < ccEnd) {
            problems.add("the requeue went before the creation cooldown was over");
        } else if (warnAt < ccEnd && !pendingAtWarn) {
            problems.add("the requeue was not held pending while the cooldown ran");
        }
    }

    private static String firstSent(List<Object[]> sent, String prefix) {
        for (Object[] s : sent) {
            if (((String) s[1]).startsWith(prefix)) {
                return (String) s[1];
            }
        }
        return null;
    }

    /** Insta Clear Warning's real guards (KeyRooms.guards on the live map) as "room->door". */
    private static String guards() {
        Object layout = ModUnderTest.staticCall(LAYOUT, "current");
        List<String> out = new ArrayList<>();
        for (Object g : (List<?>) ModUnderTest.staticCall(KEY_ROOMS, "guards", new Class<?>[]{layout.getClass()},
                new Object[]{layout})) {
            int r = (Integer) ModUnderTest.call(g, "room", new Class<?>[0], new Object[0]);
            int d = (Integer) ModUnderTest.call(g, "door", new Class<?>[0], new Object[0]);
            out.add(ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r}) + "->" + d);
        }
        return out.toString();
    }

    /** A block he can stand on near the middle of {@code room}'s first tile, read on the integrated server. */
    private static BlockPos standIn(ClientGameTestContext ctx, String room) {
        AtomicReference<Object> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "current");
            int r = roomIndex(layout, room);
            if (r < 0) {
                out.set("none");
                return;
            }
            int[] tiles = (int[]) ModUnderTest.call(layout, "tiles", new Class<?>[]{int.class}, new Object[]{r});
            BlockPos c = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                    new Object[]{tiles[0]});
            int floorY = (int) Math.floor(mc.player.getY());
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                var level = server.overworld();
                for (int rad = 0; rad <= 8; rad++) {
                    for (int dx = -rad; dx <= rad; dx++) {
                        for (int dz = -rad; dz <= rad; dz++) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) != rad) {
                                continue;
                            }
                            for (int y = floorY - 4; y <= floorY + 6; y++) {
                                BlockPos p = new BlockPos(c.getX() + dx, y, c.getZ() + dz);
                                if (!level.getBlockState(p.below()).isAir() && level.getBlockState(p.below()).isSolidRender()
                                        && level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir()) {
                                    out.set(p);
                                    return;
                                }
                            }
                        }
                    }
                }
                out.set("none");
            });
        });
        ctx.waitFor(mc -> out.get() != null, 200);
        return out.get() instanceof BlockPos b ? b : null;
    }

    private static int roomIndex(Object layout, String room) {
        int n = (Integer) ModUnderTest.call(layout, "roomCount", new Class<?>[0], new Object[0]);
        for (int r = 0; r < n; r++) {
            if (room.equals(ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r}))) {
                return r;
            }
        }
        return -1;
    }

    // ---- 1102-sim-felskulls -------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void felSkulls(ClientGameTestContext ctx, List<String> problems, List<String> facts) {
        boolean[] was = ctx.computeOnClient(mc -> {
            Object cfg = ModUnderTest.config(ESP_CFG);
            return new boolean[]{(Boolean) ModUnderTest.call(cfg, "getFelSkullsRaw", new Class<?>[0], new Object[0]),
                    (Boolean) ModUnderTest.call(cfg, "getStarredMobsRaw", new Class<?>[0], new Object[0])};
        });
        try {
            ctx.runOnClient(mc -> {
                Object cfg = ModUnderTest.config(ESP_CFG);
                ModUnderTest.set(cfg, "setFelSkulls", true);
                ModUnderTest.set(cfg, "setStarredMobs", true);
                ModUnderTest.set(cfg, "setThroughWalls", true);
            });
            ctx.waitTicks(40);
            int felColor = ctx.computeOnClient(mc -> (Integer) ModUnderTest.call(ModUnderTest.config(ESP_CFG), "getFelColor",
                    new Class<?>[0], new Object[0]));
            int starColor = ctx.computeOnClient(mc -> (Integer) ModUnderTest.call(ModUnderTest.config(ESP_CFG),
                    "getStarredColor", new Class<?>[0], new Object[0]));
            // The sim's own dormant Fels (server truth), and what the client has at each.
            List<double[]> dormant = ctx.computeOnClient(mc -> (List<double[]>) ModUnderTest.staticCall(SIM_MOBS, "dormantFels"));
            String[] verdict = ctx.computeOnClient(mc -> {
                Map<Integer, Integer> drawn = (Map<Integer, Integer>) ModUnderTest.staticCall(ESP, "drawnColors");
                List<?> starredMobs = (List<?>) ModUnderTest.staticCall(ESP, "starredMobs", new Class<?>[]{Minecraft.class},
                        new Object[]{mc});
                int starredSkulls = 0;
                int starredDrawn = 0;
                int loneUnstarred = 0;
                int loneDrawn = 0;
                int nearUnstarred = 0;
                int nearDrawn = 0;
                int missing = 0;
                StringBuilder bad = new StringBuilder();
                for (double[] f : dormant) {
                    ArmorStand skull = null;
                    boolean starred = false;
                    for (Entity e : mc.level.entitiesForRendering()) {
                        if (Math.abs(e.getX() - f[0]) < 0.6 && Math.abs(e.getZ() - f[2]) < 0.6) {
                            if (e instanceof ArmorStand st && !st.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
                                skull = st;
                            }
                            if (dev.testkit.compat.McEntities.endermanClass().isInstance(e) && e.isInvisible()) {
                                starred = true;
                            }
                        }
                    }
                    if (skull == null) {
                        missing++;
                        continue;
                    }
                    Integer color = drawn.get(skull.getId());
                    boolean isDrawn = color != null && color == felColor;
                    double nearest = Double.MAX_VALUE;
                    for (Object o : starredMobs) {
                        Entity m = (Entity) o;
                        if (dev.testkit.compat.McEntities.endermanClass().isInstance(m)
                                && m.distanceTo(skull) < 1.0) {
                            continue;   // the Fel's own sleeper
                        }
                        nearest = Math.min(nearest, m.distanceTo(skull));
                    }
                    if (starred) {
                        starredSkulls++;
                        starredDrawn += isDrawn ? 1 : 0;
                        if (!isDrawn) {
                            bad.append(String.format(Locale.US, " starred skull #%d at %.0f %.0f %.0f not drawn (%s);",
                                    skull.getId(), f[0], f[1], f[2], skull.getItemBySlot(EquipmentSlot.HEAD).getItem()));
                        }
                    } else if (nearest > 6.0) {
                        loneUnstarred++;
                        loneDrawn += isDrawn ? 1 : 0;
                        if (isDrawn) {
                            bad.append(String.format(Locale.US, " lone unstarred skull #%d (%.1f from a starred mob) drawn;",
                                    skull.getId(), nearest));
                        }
                    } else {
                        nearUnstarred++;
                        nearDrawn += isDrawn ? 1 : 0;
                    }
                }
                return new String[]{String.format(Locale.US, "dormant Fels %d (client missing %d): starred %d drawn %d;"
                                + " unstarred alone %d drawn %d; unstarred by a starred mob %d drawn %d (the pack rule)",
                        dormant.size(), missing, starredSkulls, starredDrawn, loneUnstarred, loneDrawn, nearUnstarred,
                        nearDrawn), bad.toString(), String.valueOf(starredSkulls), String.valueOf(starredDrawn),
                        String.valueOf(loneDrawn)};
            });
            facts.add("Fel Skulls: " + verdict[0]);
            if (dormant.isEmpty() || Integer.parseInt(verdict[2]) == 0) {
                problems.add("no sleeping starred Fel on the floor to check (" + verdict[0] + ")");
            } else if (!verdict[1].isEmpty()) {
                problems.add("Fel Skulls wrong:" + verdict[1]);
            }

            // ---- the starred highlight two room tiles away, with the star stands gone ----
            Object[] far = ctx.computeOnClient(mc -> {
                List<?> starredMobs = (List<?>) ModUnderTest.staticCall(ESP, "starredMobs", new Class<?>[]{Minecraft.class},
                        new Object[]{mc});
                Vec3 me = mc.player.position();
                List<Integer> ids = new ArrayList<>();
                List<double[]> at = new ArrayList<>();
                double minD = Double.MAX_VALUE;
                // The nearest starred zombie/skeleton at least 56 blocks (about two room tiles) away, and its room-mates.
                Entity pick = null;
                for (Object o : starredMobs) {
                    Entity m = (Entity) o;
                    double d = Math.hypot(m.getX() - me.x, m.getZ() - me.z);
                    if (d >= 56 && d < minD && !m.isInvisible()) {
                        minD = d;
                        pick = m;
                    }
                }
                if (pick == null) {
                    return null;
                }
                Object layout = ModUnderTest.staticCall(LAYOUT, "current");
                int room = (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class, double.class},
                        new Object[]{pick.getX(), pick.getZ()});
                for (Object o : starredMobs) {
                    Entity m = (Entity) o;
                    int r = (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class, double.class},
                            new Object[]{m.getX(), m.getZ()});
                    if (r == room && !m.isInvisible()) {
                        ids.add(m.getId());
                        at.add(new double[]{m.getX(), m.getY(), m.getZ()});
                    }
                }
                return new Object[]{ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{room}), ids, at,
                        minD};
            });
            if (far == null) {
                problems.add("no starred mob 56+ blocks away to check the far highlight");
                return;
            }
            List<Integer> ids = (List<Integer>) far[1];
            List<double[]> at = (List<double[]>) far[2];
            String before = drawnState(ctx, ids, starColor);
            // The server takes their star stands away: what Hypixel's misc-entity range does past ~32 blocks.
            AtomicReference<Integer> removed = new AtomicReference<>();
            ctx.runOnClient(mc -> {
                var server = mc.getSingleplayerServer();
                server.execute(() -> {
                    int n = 0;
                    var level = server.overworld();
                    for (double[] p : at) {
                        var box = new net.minecraft.world.phys.AABB(p[0] - 0.7, p[1], p[2] - 0.7, p[0] + 0.7, p[1] + 4.0, p[2] + 0.7);
                        for (ArmorStand st : level.getEntitiesOfClass(ArmorStand.class, box)) {
                            String nm = st.getName().getString();
                            if (nm.contains("✯") && nm.contains("❤")) {
                                st.discard();
                                n++;
                            }
                        }
                    }
                    removed.set(n);
                });
            });
            ctx.waitFor(mc -> removed.get() != null, 200);
            ctx.waitTicks(20);
            int standsLeft = ctx.computeOnClient(mc -> {
                int n = 0;
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (e instanceof ArmorStand st && st.hasCustomName()) {
                        String nm = st.getName().getString();
                        for (double[] p : at) {
                            if (nm.contains("✯") && Math.abs(st.getX() - p[0]) < 0.7 && Math.abs(st.getZ() - p[2]) < 0.7) {
                                n++;
                            }
                        }
                    }
                }
                return n;
            });
            String after = drawnState(ctx, ids, starColor);
            facts.add(String.format(Locale.US, "far starred mobs: room %s, %d mob(s), nearest %.0f blocks away; drawn before %s;"
                    + " %d star stand(s) removed (%d left on the client); drawn after %s", far[0], ids.size(), (Double) far[3],
                    before, removed.get(), standsLeft, after));
            if (ids.isEmpty() || removed.get() == 0 || standsLeft > 0) {
                problems.add("the far-highlight check did not run (mobs " + ids.size() + ", stands removed " + removed.get()
                        + ", left " + standsLeft + ")");
            } else if (!before.startsWith(ids.size() + "/")) {
                problems.add("control: the far starred mobs were not all drawn with their star stands (" + before + ")");
            } else if (!after.startsWith(ids.size() + "/")) {
                problems.add("starred mobs two room tiles away lost their highlight once their star stands went (" + after + ")");
            }
        } finally {
            ctx.runOnClient(mc -> {
                Object cfg = ModUnderTest.config(ESP_CFG);
                ModUnderTest.set(cfg, "setFelSkulls", was[0]);
                ModUnderTest.set(cfg, "setStarredMobs", was[1]);
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static String drawnState(ClientGameTestContext ctx, List<Integer> ids, int color) {
        return ctx.computeOnClient(mc -> {
            Map<Integer, Integer> drawn = (Map<Integer, Integer>) ModUnderTest.staticCall(ESP, "drawnColors");
            int n = 0;
            for (int id : ids) {
                Integer c = drawn.get(id);
                n += c != null && c == color ? 1 : 0;
            }
            return n + "/" + ids.size();
        });
    }

    private static void println(String s) {
        System.out.println("[" + current + "] " + s);
    }
}

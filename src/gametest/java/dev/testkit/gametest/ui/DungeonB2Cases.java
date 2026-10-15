package dev.testkit.gametest.ui;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.Window;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.PacketTrace;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * killer560's 2026-10-08/09 dungeon list, second batch (mod branch dungeon-b2), the parts a singleplayer world proves.
 *
 * <p>686 Blood Camp Trigger Bot ("I tried doing it on F1 with triggerbot and it didn't work"): a Watcher and a blood
 * mob's skull stand are summoned and the stand is moved along a real trip. (A) He stands 14 blocks from where it lands,
 * aimed at the landing box: the Trigger Bot must click once, 3 (+-1) ticks before the kill moment, a swing (nothing in melee reach) -
 * before the fix it needed the landing spot inside 3 blocks and sent nothing at all. (B) He stands 2 blocks from it and a
 * zombie appears on the landing spot as the stand arrives: the click must be an attack on that zombie and the zombie must
 * lose health on the server. Plus: a player-model NPC (version-2 UUID) counts as a mob to hit, a real player (v4) does not.
 * The legit jar must click nothing.
 *
 * <p>687 Door Keys circle: a "Wither Key" stand over a stone floor; the circle the mod draws is centred under it, on the
 * floor, with radius = the 0.27.2 key pickup range ({@code AutoSecretConfig.keyPickupRange}, 1 + 5 by default); a
 * "Blood Key" picture at GUI 2 against a Door-Keys-off frame of the same view.
 *
 * <p>679 Tic Tac Toe: on every position where it is the computer's turn the prediction is never one of the computer's
 * best replies and is his best answer after each of them (independent minimax); his reported case (computer must block)
 * shows the square after the block, not the block. Prevent Misclicks: a real {@code useItemOn} on another board square
 * fails and sends no use packet, on the shown square it goes out, and sneaking lets a blocked one through. Picture.
 *
 * <p>680 Interactive Map binds (cheat): through real mouse and keyboard input on the real menu, Open Key takes K and
 * Start Key takes mouse button 4; Ticks Between Etherwarps is on the tab, 1..10, and survives a reload.
 *
 * <p>681 Auto Kick: Party Finder only - joiners judged by their S+ time on the queued floor (ok / warn / kick), nothing
 * for himself, a forged chat line, or while in a dungeon; the tab without the removed notes; sliders 30 s..4 min (F7 6,
 * M7 7) through real drags; an old file's 0 and Kick Specific read back as the top of the range and Warn Only.
 *
 * <p>682 Dungeon ESP: "Wither Boss Highlight" label, one ESP switch for every ESP row, an old file migrates.
 */
final class DungeonB2Cases {

    private static final String BC_CFG = "bloodcamp.BloodCampConfig";
    private static final String BC = "bloodcamp.BloodCampFeature";
    private static final String TTT = "puzzlesolvers.TicTacToeSolverFeature";
    private static final String TTT_CFG = "puzzlesolvers.TicTacToeSolverConfig";
    private static final String AK = "autokick.AutoKickFeature";
    private static final String AK_CFG = "autokick.AutoKickConfig";
    private static final String ESP_CFG = "mobesp.MobEspConfig";
    private static final String MAP_CFG = "livemap.LiveMapConfig";
    private static final Path SHOTS = Path.of("C:/Users/Hunter/killer560s-mod-logs/dungeon-b2-shots");

    private DungeonB2Cases() {
    }

    // =================================================================================================================
    // 686 Blood Camp Trigger Bot
    // =================================================================================================================

    static void bloodTrigger(UiCase c) throws Exception {
        Object cfg = c.onClient(mc -> Mod.cfg(BC_CFG));
        Map<Path, byte[]> saved = snapshotConfig(c, BC_CFG);
        Difficulty[] oldDifficulty = new Difficulty[1];
        boolean cheat = Mod.isCheat();
        try {
            // A container an earlier case left open (player.containerMenu not the inventory) makes ActionGate hold every
            // world action back - correctly - and in the full suite after the 2026-10-11 merge one was (case 686's first
            // suite run: "BLOOD_CAMP held back: a headless container is open"). Close it; this case is about Blood Camp.
            String leftOpen = c.onClient(mc -> {
                if (mc.player.containerMenu != mc.player.inventoryMenu) {
                    String what = mc.player.containerMenu.getClass().getSimpleName();
                    mc.player.closeContainer();
                    return what;
                }
                return null;
            });
            if (leftOpen != null) {
                c.note("closed a container an earlier case left open: " + leftOpen);
                c.ticks(2);
            }
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setShowOverlay", true);
                Mod.call(cfg, "setCountdownStartSound", false);
                Mod.call(cfg, "setKillSound", false);
                Mod.call(cfg, "setTriggerBotEnabled", true);
                Mod.call(cfg, "setAuraEnabled", false);
                Mod.call(cfg, "setAutoDetectLag", false);
                Mod.call(cfg, "setTriggerOffsetTicks", -1);
                Mod.staticCall("secrets.DungeonState", "setRoomSim", true);
                oldDifficulty[0] = mc.getSingleplayerServer().getWorldData().getDifficulty();
                return null;
            });
            server(c, s -> s.setDifficulty(Difficulty.EASY, true));
            command(c, "kill @e[tag=bcb2]");
            command(c, "tp @a 40.5 -60 30.5 0 0");
            c.ticks(10);
            String watcherTex = anyOf(Mod.field(Mod.ROOT + BC, "WATCHER_SKULL_TEXTURES"));
            String mobTex = anyOf(Mod.field(Mod.ROOT + BC, "MOB_SKULL_TEXTURES"));
            command(c, "summon zombie 40.5 -60 50.5 {Tags:[\"bcb2\"],NoAI:1b,NoGravity:1b,Silent:1b,Invulnerable:1b,"
                    + "PersistenceRequired:1b,equipment:{head:" + head(watcherTex) + "}}");
            c.ctx().waitFor(mc -> Mod.field(Mod.ROOT + BC, "watcherEntityId") != null, 200);

            // ---- NPC rule -------------------------------------------------------------------------------------------
            boolean[] npc = c.onClient(mc -> {
                RemotePlayer v2 = new RemotePlayer(mc.level, new GameProfile(
                        UUID.fromString("1a2b3c4d-1111-2222-3333-444455556666"), "Revoker"));
                RemotePlayer v4 = new RemotePlayer(mc.level, new GameProfile(
                        UUID.fromString("1a2b3c4d-1111-4222-9333-444455556666"), "RealGuy"));
                return new boolean[]{(Boolean) Mod.staticCall(BC, "isAttackableMob", v2),
                        (Boolean) Mod.staticCall(BC, "isAttackableMob", v4)};
            });
            c.note("player-model NPC (v2 UUID) attackable " + npc[0] + "; real player (v4) attackable " + npc[1]);
            c.check(npc[0], "a player-model NPC (version-2 UUID) is not treated as a mob to hit");
            c.check(!npc[1], "a real player (version-4 UUID) is treated as a mob to hit");

            // ---- (A) far: aimed from 14 blocks, a swing ---------------------------------------------------------------
            int clicks0 = triggerInt(c, "triggerClicks");
            int attacks0 = triggerInt(c, "triggerAttacks");
            Trip a = trip(c, mobTex, new Vec3(32.5, -60, 44.5), null, "bcb2a", false);
            int clicksA = triggerInt(c, "triggerClicks") - clicks0;
            int attacksA = triggerInt(c, "triggerAttacks") - attacks0;
            c.note(String.format(Locale.ROOT, "(A) %s wave, he stood %.1f blocks from the landing spot: %d click(s), %d"
                            + " attack(s), first click at remaining %s; swings sent %d, attack packets %d; last '%s'",
                    a.first ? "first" : "later", a.distance, clicksA, attacksA, a.clickAt, a.swings, a.interacts,
                    lastTrigger(c)));
            if (cheat) {
                c.check(a.tracked, "(A) the stand's trip was never tracked - nothing to test");
                c.check(clicksA == 1, "(A) aimed at the landing box from " + String.format(Locale.ROOT, "%.1f", a.distance)
                        + " blocks the Trigger Bot clicked " + clicksA + " time(s), want exactly 1");
                c.check(attacksA == 0 && a.interacts == 0, "(A) nothing is in melee reach, yet an attack went out");
                c.check(a.swings >= 1, "(A) no swing packet went out");
                // killer560 2026-10-15: Trigger Offset -1 (the default) clicks one tick AFTER the countdown's end
                // (Auto Detect Lag is off here, so the ping is left out): the first x.8 grid value below -1 is -1.2. 792
                // measures it against the kill timer with a real round trip.
                // The mod's own record of the tick it clicked on (this case's clickAt is read a tick later).
                double firedAt = c.onClient(mc -> (Double) Mod.staticCall(BC, "lastTriggerAt"));
                c.check(firedAt >= -1.25 && firedAt <= -1.15, "(A) the click went out at " + firedAt
                        + " ticks left, want -1.2: one tick after the countdown's end");
            } else {
                c.check(clicksA == 0 && a.swings == 0, "legit jar: the Trigger Bot clicked (" + clicksA + ")");
            }

            // ---- (B) near, a zombie lands: an attack that hurts it -----------------------------------------------------
            command(c, "kill @e[tag=bcb2a]");
            c.ticks(5);
            int clicksB0 = triggerInt(c, "triggerClicks");
            int attacksB0 = triggerInt(c, "triggerAttacks");
            Trip b = trip(c, mobTex, new Vec3(30.5, -60, 46.5), new double[]{0, -2.6}, "bcb2b", true);
            int clicksB = triggerInt(c, "triggerClicks") - clicksB0;
            int attacksB = triggerInt(c, "triggerAttacks") - attacksB0;
            float[] hp = zombieHealth(c, "bcb2mob");
            c.note(String.format(Locale.ROOT, "(B) %.1f blocks from the landing spot, husk on it: %d click(s), %d attack(s),"
                            + " attack packets %d; zombie health %s of %s; last '%s'", b.distance, clicksB, attacksB,
                    b.interacts, hp == null ? "gone" : String.valueOf(hp[0]), hp == null ? "?" : String.valueOf(hp[1]),
                    lastTrigger(c)));
            if (cheat) {
                c.check(clicksB == 1 && attacksB == 1, "(B) want one click that is an attack, got " + clicksB + " / "
                        + attacksB);
                c.check(b.interacts >= 1, "(B) no attack packet went out");
                c.check(hp == null || hp[0] < hp[1], "(B) the husk on the landing spot was not hurt (" + hp[0] + ")");
            } else {
                c.check(clicksB == 0 && b.interacts == 0, "legit jar: the Trigger Bot attacked");
                c.check(hp != null && hp[0] == hp[1], "legit jar: the mob on the landing spot lost health");
            }
        } finally {
            command(c, "kill @e[tag=bcb2]");
            command(c, "kill @e[tag=bcb2a]");
            command(c, "kill @e[tag=bcb2b]");
            command(c, "kill @e[tag=bcb2mob]");
            if (oldDifficulty[0] != null) {
                Difficulty d = oldDifficulty[0];
                server(c, s -> s.setDifficulty(d, true));
            }
            c.onClient(mc -> {
                Mod.staticCall("secrets.DungeonState", "setRoomSim", false);
                return null;
            });
            restoreConfig(c, saved, BC_CFG);
        }
    }

    private record Trip(boolean first, boolean tracked, double distance, Double clickAt, int swings, int interacts) {
    }

    /**
     * One blood mob trip: a skull stand summoned at {@code start} and moved +x tick by tick for the mod's own trip
     * length, him aimed at the landing box every tick. {@code standOff} null: he stays where he is; else he is put at
     * the landing spot + (dx, dz). {@code zombie}: a zombie appears on the landing spot as the stand arrives.
     */
    private static Trip trip(UiCase c, String mobTex, Vec3 start, double[] standOff, String tag, boolean zombie) {
        boolean first;
        try {
            first = (Boolean) c.onClient(mc -> Mod.staticCall("bloodcamp.BloodCampMoveTimer", "isFirstSpawns"));
        } catch (RuntimeException | AssertionError e) {
            first = true;
        }
        double distance = first ? 16.1 : 11.9;
        int moveTicks = (int) Math.round((first ? 40 : 0) + 38 + 0.8) - 4;
        double step = distance / moveTicks;
        Vec3 end = start.add(distance, 0, 0);
        if (standOff != null) {
            command(c, String.format(Locale.ROOT, "tp @a %.3f -60 %.3f 0 0", end.x + standOff[0], end.z + standOff[1]));
            c.ticks(5);
        }
        command(c, String.format(Locale.ROOT, "summon armor_stand %.3f %.3f %.3f {Tags:[\"%s\"],NoGravity:1b,"
                + "equipment:{head:%s}}", start.x, start.y, start.z, tag, head(mobTex)));
        c.ticks(10);
        Vec3 aim = end.add(0, 1.0, 0);
        c.onClient(mc -> {
            PacketTrace.start();
            return null;
        });
        boolean tracked = false;
        Double clickAt = null;
        int clicksBefore = triggerInt(c, "triggerClicks");
        int total = moveTicks + 45;
        for (int i = 1; i <= total; i++) {
            if (i <= moveTicks) {
                double f = i == moveTicks ? distance : step * i;
                move(c, tag, start.add(f, 0, 0));
                // Five ticks before the stand arrives: the click now comes 3 ticks before the countdown ends.
                if (i == moveTicks - 5 && zombie) {
                    // A husk: a zombie burns in daylight, which took 2 health off it with no attack at all (legit run).
                    command(c, String.format(Locale.ROOT, "summon husk %.3f %.3f %.3f {Tags:[\"bcb2mob\"],NoAI:1b,"
                            + "Silent:1b,PersistenceRequired:1b,Health:20f}", end.x, end.y, end.z));
                }
            }
            c.onClient(mc -> {
                Vec3 eye = mc.player.getEyePosition();
                Vec3 d = aim.subtract(eye);
                float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
                float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
                mc.player.setYRot(yaw);
                mc.player.setXRot(pitch);
                return null;
            });
            c.ticks(1);
            tracked |= c.onClient(mc -> endVectorOf(tag) != null);
            if (clickAt == null && triggerInt(c, "triggerClicks") > clicksBefore) {
                clickAt = c.onClient(mc -> remainingOf(tag));
            }
        }
        double dist = c.onClient(mc -> mc.player.getEyePosition().distanceTo(end.add(0, 1, 0)));
        int[] packets = c.onClient(mc -> {
            PacketTrace.stop();
            // The attack's protocol id: "interact" on older versions, its own "attack" packet on newer ones.
            return new int[]{PacketTrace.sent("swing"), PacketTrace.sent("interact") + PacketTrace.sent("attack")};
        });
        return new Trip(first, tracked, dist, clickAt, packets[0], packets[1]);
    }

    /** The tracked trip's predicted spot (each trip runs with the previous stand killed, so there is one). */
    private static Vec3 endVectorOf(String tag) {
        @SuppressWarnings("unchecked")
        Map<Object, Object> mobs = (Map<Object, Object>) Mod.field(Mod.ROOT + BC, "bloodMobs");
        for (Map.Entry<Object, Object> m : mobs.entrySet()) {
            Object end = Mod.field(m.getValue(), "endVector");
            if (end != null) {
                return (Vec3) end;
            }
        }
        return null;
    }

    private static Double remainingOf(String tag) {
        Minecraft mc = Minecraft.getInstance();
        @SuppressWarnings("unchecked")
        Map<Object, Object> mobs = (Map<Object, Object>) Mod.field(Mod.ROOT + BC, "bloodMobs");
        Double best = null;
        for (Map.Entry<Object, Object> m : mobs.entrySet()) {
            Object data = m.getValue();
            double r = (Double) Mod.staticCall(BC, "remainingTicks", data, mc.level.getGameTime());
            best = best == null ? r : Math.max(best, r);
        }
        return best;
    }

    private static int triggerInt(UiCase c, String method) {
        try {
            return c.onClient(mc -> (Integer) Mod.staticCall(BC, method));
        } catch (RuntimeException | AssertionError e) {
            return 0;   // a jar without the counters
        }
    }

    private static String lastTrigger(UiCase c) {
        try {
            return c.onClient(mc -> (String) Mod.staticCall(BC, "lastTrigger"));
        } catch (RuntimeException | AssertionError e) {
            return "?";
        }
    }

    /** {health, max} of the server entity with {@code tag}, or null when it is gone (killed). */
    private static float[] zombieHealth(UiCase c, String tag) {
        AtomicReference<float[]> out = new AtomicReference<>();
        server(c, s -> {
            for (Entity e : s.overworld().getEntities(EntityTypeTest.forClass(Entity.class),
                    e -> e.entityTags().contains(tag))) {
                if (e instanceof LivingEntity l && l.isAlive()) {
                    out.set(new float[]{l.getHealth(), l.getMaxHealth()});
                }
            }
        });
        return out.get();
    }

    // =================================================================================================================
    // 687 Door Keys circle
    // =================================================================================================================

    static void doorKeyRing(UiCase c) throws Exception {
        Map<Path, byte[]> saved = snapshotConfig(c, "doorkeys.DoorKeysConfig");
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            command(c, "tp @a 270.5 116 259.5 0 50");
            c.ticks(20);
            command(c, "fill 255 99 255 285 99 285 minecraft:stone");
            command(c, "fill 255 100 255 285 110 285 minecraft:air");
            command(c, "kill @e[tag=b2key]");
            command(c, "summon armor_stand 270.5 100.6 270.5 {Tags:[\"b2key\"],NoGravity:1b,Invisible:1b}");
            server(c, s -> {
                for (ArmorStand st : s.overworld().getEntities(EntityTypeTest.forClass(ArmorStand.class),
                        e -> e.entityTags().contains("b2key"))) {
                    st.setCustomName(Component.literal("Wither Key"));
                    st.setCustomNameVisible(true);
                }
            });
            c.onClient(mc -> {
                Object k = Mod.cfg("doorkeys.DoorKeysConfig");
                Mod.call(k, "setEnabled", true);
                Mod.call(k, "setHighlightWither", true);
                Mod.call(k, "setHighlightBlood", true);
                Mod.call(k, "setShowTracer", true);
                Mod.call(k, "setThroughWalls", false);
                Mod.staticCall("secrets.DungeonState", "setRoomSim", true);
                return null;
            });
            c.ticks(10);
            double[] ring = c.onClient(mc -> (double[]) Mod.staticCall("doorkeys.DoorKeysFeature", "pickupRing"));
            double want = c.onClient(mc -> (Double) Mod.call(Mod.cfg("autosecret.AutoSecretConfig"), "keyPickupRange"));
            double bonus = ((Number) Mod.field("doorkeys.DungeonKeys", "KEY_RANGE_BONUS")).doubleValue();
            c.note("wither key at 270.5,100.6,270.5 over a floor whose top is y 100: circle " + fmt(ring)
                    + "; pickup range " + want + " (0.27.2 bonus " + bonus + ")");
            c.check(ring != null, "no circle for the key");
            if (ring != null) {
                c.check(Math.abs(ring[0] - 270.5) < 1e-6 && Math.abs(ring[2] - 270.5) < 1e-6, "circle not centered under the key");
                c.check(Math.abs(ring[1] - 100.02) < 1e-6, "circle not on the floor top (y " + ring[1] + ")");
                c.check(Math.abs(ring[3] - want) < 1e-9 && want >= bonus, "circle radius " + ring[3] + ", pickup range " + want);
            }
            // The picture: a Blood Key (red reads on stone), against a Door-Keys-off frame of the same view.
            server(c, s -> {
                for (ArmorStand st : s.overworld().getEntities(EntityTypeTest.forClass(ArmorStand.class),
                        e -> e.entityTags().contains("b2key"))) {
                    st.setCustomName(Component.literal("Blood Key"));
                }
            });
            c.ticks(5);
            command(c, "kill @e[tag=b2key]");
            command(c, "summon armor_stand 270.5 100.6 270.5 {Tags:[\"b2key\"],NoGravity:1b,Invisible:1b}");
            server(c, s -> {
                for (ArmorStand st : s.overworld().getEntities(EntityTypeTest.forClass(ArmorStand.class),
                        e -> e.entityTags().contains("b2key"))) {
                    st.setCustomName(Component.literal("Blood Key"));
                    st.setCustomNameVisible(true);
                }
            });
            c.ticks(60);   // the floor's chunks render
            lookAt(c, new Vec3(270.5, 100.0, 270.5));
            Path on = shot(c, "door-key-circle");
            c.onClient(mc -> {
                Mod.call(Mod.cfg("doorkeys.DoorKeysConfig"), "setEnabled", false);
                return null;
            });
            c.ticks(5);
            Path off = shot(c, "door-key-circle-off");
            int changed = redChanged(on, off);
            c.note(changed + " red pixel(s) differ from the Door-Keys-off frame");
            c.check(changed >= 150, "the circle is barely in the picture (" + changed + " red px)");
        } finally {
            command(c, "kill @e[tag=b2key]");
            c.onClient(mc -> {
                Mod.staticCall("secrets.DungeonState", "setRoomSim", false);
                mc.options.guiScale().set(gui);
                mc.resizeGui();
                return null;
            });
            restoreConfig(c, saved, "doorkeys.DoorKeysConfig");
        }
    }

    // =================================================================================================================
    // 679 Tic Tac Toe
    // =================================================================================================================

    static void ticTacToe(UiCase c) throws Exception {
        // ---- every computer-to-move position, against an independent minimax ------------------------------------
        // One client task for the whole sweep (thousands of calls); {positions, predicted, wrong} + the first wrong one.
        Object[] sweep = c.onClient(mc -> {
            int positions = 0;
            int predicted = 0;
            int wrong = 0;
            String firstWrong = "";
            for (int code = 0; code < 19683; code++) {
                char[] b = new char[9];
                int x = 0;
                int o = 0;
                int v = code;
                for (int i = 0; i < 9; i++) {
                    int d = v % 3;
                    v /= 3;
                    b[i] = d == 0 ? '.' : d == 1 ? 'X' : 'O';
                    x += d == 1 ? 1 : 0;
                    o += d == 2 ? 1 : 0;
                }
                // X (the computer) moves first, so it is the computer's turn when the counts are equal.
                if (x != o || winner(b) != 0 || x + o == 9) {
                    continue;
                }
                positions++;
                String s = new String(b);
                Integer p = (Integer) Mod.staticCall(TTT, "predictionFor", s);
                if (p == null) {
                    continue;
                }
                predicted++;
                int bestX = Integer.MAX_VALUE;
                List<Integer> replies = new ArrayList<>();
                for (int r = 0; r < 9; r++) {
                    if (b[r] != '.') {
                        continue;
                    }
                    b[r] = 'X';
                    int val = minimax(b, true);
                    b[r] = '.';
                    if (val < bestX) {
                        bestX = val;
                        replies.clear();
                    }
                    if (val == bestX) {
                        replies.add(r);
                    }
                }
                String why = null;
                if (b[p] != '.') {
                    why = "not empty";
                } else if (replies.contains(p)) {
                    why = "the computer's own best reply";
                } else {
                    for (int r : replies) {
                        b[r] = 'X';
                        int bestO = Integer.MIN_VALUE;
                        for (int q = 0; q < 9; q++) {
                            if (b[q] == '.') {
                                b[q] = 'O';
                                bestO = Math.max(bestO, minimax(b, false));
                                b[q] = '.';
                            }
                        }
                        b[p] = 'O';
                        int mine = minimax(b, false);
                        b[p] = '.';
                        b[r] = '.';
                        if (mine != bestO) {
                            why = "after reply " + r + " it is worth " + mine + ", best " + bestO;
                            break;
                        }
                    }
                }
                if (why != null) {
                    wrong++;
                    if (firstWrong.isEmpty()) {
                        firstWrong = s + " -> " + p + " (" + why + ")";
                    }
                }
            }
            return new Object[]{positions, predicted, wrong, firstWrong};
        });
        int positions = (Integer) sweep[0];
        int predicted = (Integer) sweep[1];
        int wrong = (Integer) sweep[2];
        String firstWrong = (String) sweep[3];
        c.note(positions + " computer-to-move positions; a prediction on " + predicted + "; wrong " + wrong
                + (firstWrong.isEmpty() ? "" : ", first " + firstWrong));
        c.check(positions > 2000 && predicted > 500, "too few positions / predictions to mean anything");
        c.check(wrong == 0, wrong + " predictions are not a safe best click, first " + firstWrong);
        // His case: X 0, O 4, X 8, O 1 - the computer must block at 7; then he must block at 6.
        Integer his = c.onClient(mc -> (Integer) Mod.staticCall(TTT, "predictionFor", "XO..O...X"));
        c.note("X . . / . O . / . . X with O on 1 (computer must block 7): prediction " + his);
        c.check(Integer.valueOf(6).equals(his), "the prediction for the must-block board is " + his + ", want 6 (not 7,"
                + " the computer's own square)");

        // ---- Prevent Misclicks on a board of nine stone blocks --------------------------------------------------
        Map<Path, byte[]> saved = snapshotConfig(c, TTT_CFG);
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        int[] cr = {300, 300, 0};
        BlockPos[] cells = c.onClient(mc -> {
            BlockPos[] out = new BlockPos[9];
            for (int i = 0; i < 9; i++) {
                out[i] = (BlockPos) Mod.staticCall(TTT, "indexToPos", i, cr);
            }
            return out;
        });
        // The board is a 3x3 wall: stand square in front of it (along the axis its columns do not run on).
        boolean colsAlongX = cells[0].getX() != cells[2].getX();
        Vec3 front = Vec3.atCenterOf(cells[4]).add(colsAlongX ? 0 : 5, -1.1, colsAlongX ? 5 : 0);
        try {
            command(c, String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f", front.x, front.y, front.z));
            c.ticks(40);
            for (BlockPos p : cells) {
                command(c, "setblock " + p.getX() + " " + p.getY() + " " + p.getZ() + " minecraft:stone");
            }
            c.ctx().waitFor(mc -> mc.level.getBlockState(cells[8]).isSolid(), 100);
            c.onClient(mc -> {
                Object t = Mod.cfg(TTT_CFG);
                Mod.call(t, "setEnabled", true);
                Mod.call(t, "setShowPrediction", true);
                Mod.call(t, "setPreventMisclicks", true);
                Mod.call(t, "setFill", false);
                Mod.staticCall("secrets.DungeonState", "setRoomSim", true);
                Mod.setField(Mod.ROOT + TTT, "boardCr", cr);
                Mod.setField(Mod.ROOT + TTT, "bestMove", null);
                Mod.setField(Mod.ROOT + TTT, "predictedMove", cells[6]);
                return null;
            });
            c.ticks(5);
            Object target = c.onClient(mc -> Mod.staticCall(TTT, "currentTarget"));
            c.check(cells[6].equals(target), "the shown square is " + target + ", not the one set");
            int blocked0 = c.onClient(mc -> (Integer) Mod.staticCall(TTT, "blockedClicks"));
            c.note("board cells " + cells[0].toShortString() + " .. " + cells[8].toShortString() + ", he stands at "
                    + String.format(Locale.ROOT, "%.1f,%.1f,%.1f", front.x, front.y, front.z));
            Object[] wrongClick = useOn(c, cells[0]);
            Object[] rightClick = useOn(c, cells[6]);
            c.onClient(mc -> {
                mc.options.keyShift.setDown(true);
                return null;
            });
            c.ticks(3);
            Object[] sneakClick = useOn(c, cells[0]);
            c.onClient(mc -> {
                mc.options.keyShift.setDown(false);
                return null;
            });
            int blocked = c.onClient(mc -> (Integer) Mod.staticCall(TTT, "blockedClicks")) - blocked0;
            c.note("wrong square: " + wrongClick[0] + ", " + wrongClick[1] + " use packet(s); shown square: "
                    + rightClick[0] + ", " + rightClick[1] + "; wrong square sneaking: " + sneakClick[0] + ", "
                    + sneakClick[1] + "; blocked " + blocked);
            c.check(wrongClick[0] == InteractionResult.FAIL && (Integer) wrongClick[1] == 0 && blocked == 1,
                    "a click on another square was not stopped before it was sent");
            c.check(rightClick[0] != InteractionResult.FAIL && (Integer) rightClick[1] >= 1,
                    "the click on the shown square was stopped");
            c.check(sneakClick[0] != InteractionResult.FAIL && (Integer) sneakClick[1] >= 1,
                    "sneaking did not let the click through");
            // Off: nothing is blocked.
            c.onClient(mc -> {
                Mod.call(Mod.cfg(TTT_CFG), "setPreventMisclicks", false);
                return null;
            });
            Object[] offClick = useOn(c, cells[0]);
            c.check(offClick[0] != InteractionResult.FAIL, "with Prevent Misclicks off a click was stopped");

            // Picture: the yellow outline on the square he clicks next.
            c.ticks(60);   // the board's chunk section re-renders
            lookAt(c, Vec3.atCenterOf(cells[4]));
            shot(c, "ttt-prediction");
        } finally {
            for (BlockPos p : cells) {
                command(c, "setblock " + p.getX() + " " + p.getY() + " " + p.getZ() + " minecraft:air");
            }
            c.onClient(mc -> {
                Mod.setField(Mod.ROOT + TTT, "boardCr", null);
                Mod.setField(Mod.ROOT + TTT, "predictedMove", null);
                Mod.staticCall("secrets.DungeonState", "setRoomSim", false);
                mc.options.keyShift.setDown(false);
                mc.options.guiScale().set(gui);
                mc.resizeGui();
                return null;
            });
            restoreConfig(c, saved, TTT_CFG);
        }
    }

    private static void lookAt(UiCase c, Vec3 target) {
        c.onClient(mc -> {
            Vec3 d = target.subtract(mc.player.getEyePosition());
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
            mc.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
            return null;
        });
        c.ticks(2);
    }

    /** A real {@code useItemOn} on {@code pos}'s south face: {result, use_item_on packets sent}. */
    private static Object[] useOn(UiCase c, BlockPos pos) {
        Object result = c.onClient(mc -> {
            PacketTrace.start();
            return mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0, 0.5), Direction.SOUTH, pos, false));
        });
        c.ticks(2);
        int sent = c.onClient(mc -> {
            PacketTrace.stop();
            return PacketTrace.sent("use_item_on");
        });
        return new Object[]{result, sent};
    }

    private static int winner(char[] b) {
        int[][] lines = {{0, 1, 2}, {3, 4, 5}, {6, 7, 8}, {0, 3, 6}, {1, 4, 7}, {2, 5, 8}, {0, 4, 8}, {2, 4, 6}};
        for (int[] l : lines) {
            if (b[l[0]] != '.' && b[l[0]] == b[l[1]] && b[l[0]] == b[l[2]]) {
                return b[l[0]] == 'O' ? 1 : -1;
            }
        }
        return 0;
    }

    /** +1 O wins, 0 draw, -1 X wins with perfect play. */
    private static int minimax(char[] b, boolean oToMove) {
        int w = winner(b);
        if (w != 0) {
            return w;
        }
        boolean full = true;
        int best = oToMove ? -2 : 2;
        for (int i = 0; i < 9; i++) {
            if (b[i] != '.') {
                continue;
            }
            full = false;
            b[i] = oToMove ? 'O' : 'X';
            int v = minimax(b, !oToMove);
            b[i] = '.';
            best = oToMove ? Math.max(best, v) : Math.min(best, v);
        }
        return full ? 0 : best;
    }

    // =================================================================================================================
    // 680 Interactive Map binds and Ticks Between Etherwarps
    // =================================================================================================================

    static void interactiveMapBinds(UiCase c) throws Exception {
        if (!Mod.isCheat()) {
            c.note("legit jar: no Interactive Map tab - nothing to bind");
            return;
        }
        Object cfg = c.onClient(mc -> Mod.cfg(MAP_CFG));
        Map<Path, byte[]> saved = snapshotConfig(c, MAP_CFG);
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            c.onClient(mc -> {
                Mod.call(cfg, "setInteractiveMapEnabled", true);
                Mod.call(cfg, "setOpenKeyCode", -1);
                Mod.call(cfg, "setStartKeyCode", -1);
                Mod.call(cfg, "setEtherwarpTickGap", 1);
                mc.options.guiScale().set(2);
                mc.resizeGui();
                return null;
            });
            Screen s = openTab(c, "Interactive Map");
            // Open Key <- K, through the real keyboard.
            double[] open = button(c, s, "Open Key:");
            realClick(c, s, open[0] + open[2] / 2, open[1] + open[3] / 2, 0);
            String listening = labelOf(c, s, "Open Key:");
            c.ctx().getInput().pressKey(75);
            c.ticks(3);
            int openCode = c.onClient(mc -> (Integer) Mod.call(cfg, "getOpenKeyCode"));
            String openLabel = labelOf(c, s, "Open Key:");
            // Start Key <- mouse button 4 (GLFW 3), through the real mouse.
            double[] start = button(c, s, "Start Key:");
            realClick(c, s, start[0] + start[2] / 2, start[1] + start[3] / 2, 0);
            realClick(c, s, start[0] + start[2] / 2, start[1] + start[3] / 2, 3);
            int startCode = c.onClient(mc -> (Integer) Mod.call(cfg, "getStartKeyCode"));
            int wantMouse = c.onClient(mc -> (Integer) Mod.staticCall("util.KeyUtil", "codeForMouseButton", 3));
            String startLabel = labelOf(c, s, "Start Key:");
            c.note("Open Key: after the click '" + listening + "', after K code " + openCode + " label '" + openLabel
                    + "'; Start Key after mouse 4: code " + startCode + " (want " + wantMouse + ") label '" + startLabel + "'");
            c.check(openCode == 75, "Open Key did not take K from the keyboard (code " + openCode + ")");
            c.check(startCode == wantMouse, "Start Key did not take mouse button 4 (code " + startCode + ")");
            c.check(!openLabel.contains("Press any key") && !startLabel.contains("Press any key"),
                    "a bind button still says Press any key after it was set");

            // Ticks Between Etherwarps.
            String ticksLabel = labelOf(c, s, "Ticks Between Etherwarps:");
            c.check(ticksLabel != null, "no Ticks Between Etherwarps slider on the tab");
            int[] clamp = c.onClient(mc -> {
                Mod.call(cfg, "setEtherwarpTickGap", 0);
                int lo = (Integer) Mod.call(cfg, "getEtherwarpTickGap");
                Mod.call(cfg, "setEtherwarpTickGap", 99);
                int hi = (Integer) Mod.call(cfg, "getEtherwarpTickGap");
                Mod.call(cfg, "setEtherwarpTickGap", 3);
                Mod.call(cfg, "save");
                Mod.staticCall(MAP_CFG, "load");
                return new int[]{lo, hi, (Integer) Mod.call(Mod.cfg(MAP_CFG), "getEtherwarpTickGap")};
            });
            c.note("slider '" + ticksLabel + "'; set 0 -> " + clamp[0] + ", 99 -> " + clamp[1] + ", 3 after a reload -> "
                    + clamp[2]);
            c.check(clamp[0] == 1 && clamp[1] == 10 && clamp[2] == 3, "Ticks Between Etherwarps range/persistence wrong");
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                mc.options.guiScale().set(gui);
                mc.resizeGui();
                return null;
            });
            restoreConfig(c, saved, MAP_CFG);
        }
    }

    // =================================================================================================================
    // 681 Auto Kick
    // =================================================================================================================

    static void autoKick(UiCase c) throws Exception {
        Map<Path, byte[]> saved = snapshotConfig(c, AK_CFG);
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        String self = c.onClient(mc -> mc.player.getGameProfile().name());
        try {
            c.onClient(mc -> {
                Mod.staticCall("secrets.DungeonState", "setRoomSim", false);
                Object k = Mod.cfg(AK_CFG);
                Mod.call(k, "setEnabled", true);
                Mod.call(k, "setQueueFloor", Mod.enumValue(AK_CFG + "$Floor", "F7"));
                Mod.call(k, "setTargetSeconds", Mod.enumValue(AK_CFG + "$Floor", "F7"), 300);
                stats(mc, "FastGuy", "4:12");
                stats(mc, "SlowGuy", "5:40");
                stats(mc, "NoPbGuy", null);
                stats(mc, "Kicker", "6:00");
                stats(mc, "DungeonGuy", "9:00");
                stats(mc, self, "9:00");
                return null;
            });
            int d0 = decisions(c).size();
            line(c, "Party Finder > FastGuy joined the dungeon group! (Mage Level 42)");
            // Through the real chat path too (ChatObserver), not only the method.
            c.onClient(mc -> {
                // The hook the chat mixin calls for every line added to chat (ChatObserver's listeners).
                Mod.staticCall("util.ChatObserver", "onChatComponentAdd",
                        Component.literal("Party Finder > SlowGuy joined the dungeon group! (Berserk Level 40)"));
                return null;
            });
            line(c, "Party Finder > NoPbGuy joined the dungeon group! (Tank Level 35)");
            line(c, "Party Finder > " + self + " joined the dungeon group! (Healer Level 30)");
            line(c, "[MVP+] Bob: Party Finder > SlowGuy joined the dungeon group! (Mage Level 1)");
            c.onClient(mc -> {
                Mod.staticCall("secrets.DungeonState", "setRoomSim", true);
                return null;
            });
            c.ticks(2);
            line(c, "Party Finder > DungeonGuy joined the dungeon group! (Archer Level 30)");
            c.onClient(mc -> {
                Mod.staticCall("secrets.DungeonState", "setRoomSim", false);
                return null;
            });
            line(c, "Party Finder > Kicker joined the dungeon group! (Mage Level 50)");
            // Kicks go out at most one per 3 s (AutoKickFeature.KICK_MIN_GAP_MS) and SlowGuy's went first.
            for (int i = 0; i < 100; i++) {
                c.ticks(1);
                @SuppressWarnings("unchecked")
                List<String> sent = c.onClient(mc -> (List<String>) Mod.staticCall(AK, "kicksSent"));
                if (sent.contains("Kicker")) {
                    break;
                }
            }
            List<String> got = decisions(c);
            List<String> mine = got.subList(Math.min(d0, got.size()), got.size());
            @SuppressWarnings("unchecked")
            List<String> kicks = c.onClient(mc -> (List<String>) Mod.staticCall(AK, "kicksSent"));
            c.note("decisions: " + mine + "; kicks sent " + kicks);
            c.check(mine.contains("FastGuy F7 S+ 4:12 limit 5:00 -> ok"), "FastGuy (4:12 under 5:00) was not let in");
            c.check(mine.contains("SlowGuy F7 S+ 5:40 limit 5:00 -> kick") && kicks.contains("SlowGuy"),
                    "SlowGuy (5:40 over 5:00) was not kicked");
            // Judged a kick; the kick itself is still queued (one per KICK_MIN_GAP_MS) when the case enters a dungeon,
            // which drops the queue on purpose - so only the decision is his.
            c.check(mine.contains("NoPbGuy F7 S+ none limit 5:00 -> kick"), "NoPbGuy (no S+) was not judged a kick");
            c.check(!kicks.contains("FastGuy"), "FastGuy (under the limit) was kicked");
            c.check(mine.stream().filter(s -> s.startsWith("SlowGuy")).count() == 1, "the forged chat line counted");
            c.check(mine.stream().noneMatch(s -> s.startsWith(self + " ")), "his own join was judged");
            c.check(mine.stream().noneMatch(s -> s.startsWith("DungeonGuy")), "a join while in a dungeon was judged");
            c.check(mine.contains("Kicker F7 S+ 6:00 limit 5:00 -> kick") && kicks.contains("Kicker"),
                    "Auto Kick did not kick Kicker");
            c.check(mine.stream().noneMatch(s -> s.endsWith("-> warn")), "a joiner was only warned about: " + mine);

            // ---- the tab -----------------------------------------------------------------------------------------------
            c.onClient(mc -> {
                mc.options.guiScale().set(2);
                mc.resizeGui();
                return null;
            });
            Screen s = openTab(c, "Auto Kick");
            List<String> labels = labels(c, s);
            String all = String.join(" | ", labels).toLowerCase(Locale.ROOT);
            c.note("Auto Kick tab rows: " + labels);
            c.check(!all.contains("remove real") && !all.contains("0 = disabled") && !all.contains("ships")
                    && !all.contains("disabled"), "a removed note is still on the tab");
            c.check(all.contains("floor source") && all.contains("judge by"), "no Floor Source / Judge By row");
            c.check(!all.contains("action mode") && !all.contains("warn only"), "the removed Action Mode row is still there");
            String[][] ends = {{"Floor 1:", "0:30", "4:00"}, {"Floor 7:", "0:30", "6:00"}, {"Master 7:", "0:30", "7:00"}};
            for (String[] e : ends) {
                double[] b = slider(c, s, e[0]);
                drag(c, s, b[0] + b[2] / 2, b[1] + b[3] / 2, b[0] - 30);
                String lo = labelOf(c, s, e[0]);
                b = slider(c, s, e[0]);
                drag(c, s, b[0] + b[2] / 2, b[1] + b[3] / 2, b[0] + b[2] + 30);
                String hi = labelOf(c, s, e[0]);
                c.note(e[0] + " dragged left '" + lo + "', right '" + hi + "'");
                c.check(lo != null && lo.endsWith(e[1]) && hi != null && hi.endsWith(e[2]),
                        e[0] + " range is not " + e[1] + ".." + e[2] + " (" + lo + " / " + hi + ")");
            }
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });

            // ---- an old file -------------------------------------------------------------------------------------------
            Path p = saved.keySet().iterator().next();
            Files.writeString(p, "{\"enabled\":true,\"mode\":\"KICK_SPECIFIC\",\"specificMembers\":\"a\",\"target.F1\":0,"
                    + "\"target.F7\":200,\"target.M7\":3600}");
            Object[] old = c.onClient(mc -> {
                Mod.staticCall(AK_CFG, "load");
                Object k = Mod.cfg(AK_CFG);
                return new Object[]{"(no mode since 2026-10-14)",
                        Mod.call(k, "getTargetSeconds", Mod.enumValue(AK_CFG + "$Floor", "F1")),
                        Mod.call(k, "getTargetSeconds", Mod.enumValue(AK_CFG + "$Floor", "F7")),
                        Mod.call(k, "getTargetSeconds", Mod.enumValue(AK_CFG + "$Floor", "M7"))};
            });
            c.note("old file: mode " + old[0] + ", F1 " + old[1] + ", F7 " + old[2] + ", M7 " + old[3]);
            c.check((Integer) old[1] == 240 && (Integer) old[2] == 200
                    && (Integer) old[3] == 420, "an old file did not read back as expected");
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.staticCall("secrets.DungeonState", "setRoomSim", false);
                mc.options.guiScale().set(gui);
                mc.resizeGui();
                return null;
            });
            restoreConfig(c, saved, AK_CFG);
        }
    }

    private static void line(UiCase c, String text) {
        c.onClient(mc -> {
            Mod.staticCall(AK, "onLine", text);
            return null;
        });
        c.ticks(3);
    }

    @SuppressWarnings("unchecked")
    private static List<String> decisions(UiCase c) {
        return c.onClient(mc -> (List<String>) Mod.staticCall(AK, "decisions"));
    }

    /** Puts a Party Finder lookup answer for {@code name} into the stats cache (offline test client). */
    @SuppressWarnings("unchecked")
    private static void stats(Minecraft mc, String name, String sPlusF7) {
        JsonObject normal = new JsonObject();
        JsonObject sPlus = new JsonObject();
        if (sPlusF7 != null) {
            sPlus.addProperty("floor_7", sPlusF7);
        }
        normal.add("s_plus", sPlus);
        try {
            Class<?> rec = Mod.cls("partyfinder.PartyFinderStatsApi$PlayerStats");
            Object st = rec.getDeclaredConstructor(double.class, int.class, double.class, JsonObject.class,
                    JsonObject.class, long.class).newInstance(40.0, 1000, 8.0, normal, new JsonObject(),
                    System.currentTimeMillis());
            ((Map<String, Object>) Mod.field("partyfinder.PartyFinderStatsApi", "CACHE"))
                    .put(name.toLowerCase(Locale.ROOT), st);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // =================================================================================================================
    // 682 Dungeon ESP
    // =================================================================================================================

    static void dungeonEsp(UiCase c) throws Exception {
        Map<Path, byte[]> saved = snapshotConfig(c, ESP_CFG);
        boolean cheat = Mod.isCheat();
        try {
            c.onClient(mc -> {
                Object e = Mod.cfg(ESP_CFG);
                Mod.call(e, "setEsp", false);
                Mod.call(e, "setWitherTracer", true);
                return null;
            });
            // small-dungeon (2026-10-15): the Tracer is the Wither Boss Highlight's, on both builds, and the ESP has no
            // colour row of its own.
            String tab = cheat ? "Dungeon ESP" : "Starred Mob Hitboxes";
            Screen s = openTab(c, tab);
            List<String> off = labels(c, s);
            c.note(tab + " rows, ESP off: " + off);
            c.check(off.stream().anyMatch(l -> l.startsWith("Wither Boss Highlight:")), "no 'Wither Boss Highlight' toggle");
            c.check(off.stream().noneMatch(l -> l.startsWith("Wither Highlight:")), "the old 'Wither Highlight' label is still there");
            c.check(off.stream().noneMatch(l -> l.startsWith("Through Walls:") || l.startsWith("Wither Bosses:")),
                    "a separate Through Walls / Wither Bosses toggle is still there");
            if (cheat) {
                c.check(off.stream().anyMatch(l -> l.startsWith("ESP:")), "no ESP switch");
                c.check(off.stream().anyMatch(l -> l.startsWith("Tracer:")), "no Tracer row in the Wither Boss Highlight section");
                c.check(off.stream().noneMatch(l -> l.startsWith("Wither Boss Color")), "the ESP still has a Wither Boss Color row");
                boolean[] before = espState(c);
                // Real press on the ESP switch.
                double[] b = button(c, s, "ESP:");
                realClick(c, s, b[0] + b[2] / 2, b[1] + b[3] / 2, 0);
                boolean[] after = espState(c);
                List<String> on = labels(c, s);
                c.note("ESP off: throughWalls/withers/tracer " + java.util.Arrays.toString(before) + "; after one press: "
                        + java.util.Arrays.toString(after) + "; rows " + on);
                c.check(!before[0] && !before[1], "with ESP off something still draws through walls");
                c.check(after[0] && after[1], "one ESP press did not turn every ESP item on");
                c.check(on.stream().anyMatch(l -> l.startsWith("Tracer:")) && on.stream().noneMatch(l -> l.startsWith("Wither Boss Color")),
                        "the Tracer row is gone or a Wither Boss Color row appeared under the switch");
            } else {
                c.check(off.stream().noneMatch(l -> l.startsWith("ESP:")), "legit jar shows an ESP switch");
                c.onClient(mc -> {
                    Mod.call(Mod.cfg(ESP_CFG), "setEsp", true);
                    return null;
                });
                boolean[] st = espState(c);
                c.check(!st[0] && !st[1], "legit jar: ESP config on reports ESP active");
                c.check(off.stream().anyMatch(l -> l.startsWith("Tracer:")), "legit jar: no Tracer row in the Wither Boss Highlight section");
            }
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            // An old file: Through Walls on, Wither Bosses off -> ESP on.
            Path p = saved.keySet().iterator().next();
            Files.writeString(p, "{\"starredMobs\":true,\"throughWalls\":true,\"withers\":false}");
            boolean migrated = c.onClient(mc -> {
                Mod.staticCall(ESP_CFG, "load");
                return (Boolean) Mod.call(Mod.cfg(ESP_CFG), "isEspRaw");
            });
            c.note("old file (throughWalls on, withers off): ESP " + migrated);
            c.check(migrated, "an old file with Through Walls on did not turn the ESP switch on");
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            restoreConfig(c, saved, ESP_CFG);
        }
    }

    private static boolean[] espState(UiCase c) {
        return c.onClient(mc -> {
            Object e = Mod.cfg(ESP_CFG);
            return new boolean[]{(Boolean) Mod.call(e, "isThroughWalls"), (Boolean) Mod.call(e, "isWithersEnabled"),
                    (Boolean) Mod.call(e, "isWitherTracerEnabled")};
        });
    }

    // =================================================================================================================
    // menu driving (real input, the mod's Auto Scale in the path)
    // =================================================================================================================

    /** Opens the real ModScreen with the tab named {@code name} selected and its folder sections expanded. */
    static Screen openTab(UiCase c, String name) {
        Screen s = c.onClient(mc -> {
            try {
                ModScreenDriver d = new ModScreenDriver(mc);
                List<Object> tops = d.topTabs();
                for (int i = 0; i < tops.size(); i++) {
                    List<Object[]> chain = new ArrayList<>();
                    if (find(d, tops.get(i), name, chain)) {
                        d.select(i);
                        for (Object[] step : chain) {
                            d.expanded(step[0]).add((Integer) step[1]);
                        }
                        d.openChain(chain);
                        McCompat.setScreen(mc, d.screen);
                        return d.screen;
                    }
                }
                return null;
            } catch (Throwable t) {
                throw new AssertionError("could not open gui.ModScreen: " + UiCase.describe(t), t);
            }
        });
        if (s == null) {
            throw new AssertionError("no '" + name + "' tab in the mod menu");
        }
        c.ticks(5);
        return s;
    }

    private static boolean find(ModScreenDriver d, Object tab, String name, List<Object[]> chain) {
        if (name.equals(R.get(tab, "name"))) {
            return true;
        }
        if (!d.isFolder(tab)) {
            return false;
        }
        List<Object> subs = d.subTabs(tab);
        for (int j = 0; j < subs.size(); j++) {
            chain.add(new Object[]{tab, j});
            if (find(d, subs.get(j), name, chain)) {
                return true;
            }
            chain.remove(chain.size() - 1);
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<AbstractWidget> paneChildren(Screen s) {
        AbstractWidget pane = (AbstractWidget) R.get(s, "contentPane");
        return pane == null ? List.of() : new ArrayList<>((Collection<AbstractWidget>) R.get(pane, "children"));
    }

    static List<String> labels(UiCase c, Screen s) {
        return c.onClient(mc -> {
            try {
                R.call0(s, "rebuild");
            } catch (Throwable t) {
                throw new AssertionError(t);
            }
            List<String> out = new ArrayList<>();
            for (AbstractWidget w : paneChildren(s)) {
                out.add(ModScreenDriver.label(w));
            }
            return out;
        });
    }

    private static String labelOf(UiCase c, Screen s, String prefix) {
        for (String l : labels(c, s)) {
            if (l.startsWith(prefix)) {
                return l;
            }
        }
        return null;
    }

    /** The widget whose label starts {@code prefix} (a slider when {@code slider}), scrolled into view; {x, y, w, h}. */
    private static double[] widget(UiCase c, Screen s, String prefix, boolean slider) {
        double[] box = c.onClient(mc -> {
            try {
                for (int attempt = 0; attempt < 3; attempt++) {
                    R.call0(s, "rebuild");
                    AbstractWidget pane = (AbstractWidget) R.get(s, "contentPane");
                    for (AbstractWidget w : paneChildren(s)) {
                        if ((!slider || w instanceof AbstractSliderButton) && ModScreenDriver.label(w).startsWith(prefix)) {
                            int top = pane.getY() + 4;
                            int bottom = pane.getY() + pane.getHeight() - 4;
                            if (w.getY() >= top && w.getY() + w.getHeight() <= bottom) {
                                return new double[]{w.getX(), w.getY(), w.getWidth(), w.getHeight()};
                            }
                            int offset = (Integer) R.getStatic(s.getClass(), "scrollOffset");
                            R.setStatic(s.getClass(), "scrollOffset", Math.max(0, offset + (w.getY() - top) - 20));
                            break;
                        }
                    }
                }
                return null;
            } catch (Throwable t) {
                throw new AssertionError(UiCase.describe(t), t);
            }
        });
        if (box == null) {
            throw new AssertionError("no visible row labeled '" + prefix + "...'");
        }
        return box;
    }

    private static double[] button(UiCase c, Screen s, String prefix) {
        return widget(c, s, prefix, false);
    }

    private static double[] slider(UiCase c, Screen s, String prefix) {
        return widget(c, s, prefix, true);
    }

    private static void realClick(UiCase c, Screen s, double sx, double sy, int button) {
        double[] a = toWindow(c, s, sx, sy);
        c.ctx().getInput().setCursorPos(a[0], a[1]);
        c.ticks(2);
        c.ctx().getInput().pressMouse(button);
        c.ticks(3);
    }

    private static void drag(UiCase c, Screen s, double sx, double sy, double toX) {
        double[] a = toWindow(c, s, sx, sy);
        c.ctx().getInput().setCursorPos(a[0], a[1]);
        c.ticks(2);
        c.ctx().getInput().holdMouse(0);
        c.ticks(2);
        for (int i = 1; i <= 6; i++) {
            double[] p = toWindow(c, s, sx + (toX - sx) * i / 6.0, sy);
            c.ctx().getInput().setCursorPos(p[0], p[1]);
            c.ticks(1);
        }
        c.ctx().getInput().releaseMouse(0);
        c.ticks(3);
    }

    /** Screen (layout) coordinates -> window coordinates, through the factor the screen was laid out with. */
    private static double[] toWindow(UiCase c, Screen s, double sx, double sy) {
        return c.onClient(mc -> {
            float f = ((Number) Mod.staticCall("hud.AutoScale", "appliedFactor", s)).floatValue();
            Window w = mc.getWindow();
            return new double[]{sx * f * w.getScreenWidth() / (double) w.getGuiScaledWidth(),
                    sy * f * w.getScreenHeight() / (double) w.getGuiScaledHeight()};
        });
    }

    // =================================================================================================================
    // shared
    // =================================================================================================================

    /** A GUI-2 picture of the world view (no screen), kept in the report and copied to {@link #SHOTS}. */
    static Path shot(UiCase c, String suffix) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            McCompat.clearChatAndToasts(mc);
            mc.options.guiScale().set(2);
            mc.resizeGui();
            return null;
        });
        c.ticks(5);
        String name = c.name() + "-" + suffix;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        dev.testkit.harness.Report.screenshot(name, taken);
        int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
        c.check(gs == 2, "the picture is at GUI " + gs + ", not 2");
        try {
            Path out = SHOTS.resolve(name + "-" + (Mod.isCheat() ? "cheat" : "legit") + ".png");
            Files.createDirectories(SHOTS);
            Files.copy(taken, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            c.note("picture " + out);
        } catch (java.io.IOException e) {
            c.note("could not copy the picture: " + e);
        }
        return taken;
    }

    /** Pixels that are clearly red in {@code a} and differ from {@code b} by more than 30 summed RGB. */
    private static int redChanged(Path a, Path b) {
        try {
            java.awt.image.BufferedImage ia = javax.imageio.ImageIO.read(a.toFile());
            java.awt.image.BufferedImage ib = javax.imageio.ImageIO.read(b.toFile());
            int n = 0;
            for (int y = 0; y < Math.min(ia.getHeight(), ib.getHeight()); y++) {
                for (int x = 0; x < Math.min(ia.getWidth(), ib.getWidth()); x++) {
                    int p = ia.getRGB(x, y);
                    int q = ib.getRGB(x, y);
                    int r = (p >> 16) & 255;
                    int g = (p >> 8) & 255;
                    int bl = p & 255;
                    int d = Math.abs(r - ((q >> 16) & 255)) + Math.abs(g - ((q >> 8) & 255)) + Math.abs(bl - (q & 255));
                    if (d > 30 && r > 120 && r > g * 2 && r > bl * 2) {
                        n++;
                    }
                }
            }
            return n;
        } catch (java.io.IOException e) {
            return -1;
        }
    }

    private static String fmt(double[] v) {
        if (v == null) {
            return "none";
        }
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < v.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(String.format(Locale.ROOT, "%.3f", v[i]));
        }
        return sb.append(")").toString();
    }

    static Map<Path, byte[]> snapshotConfig(UiCase c, String cls) {
        Map<Path, byte[]> out = new java.util.LinkedHashMap<>();
        Path p = c.onClient(mc -> (Path) R.getStatic(R.cls(cls), "CONFIG_PATH"));
        try {
            out.put(p, Files.exists(p) ? Files.readAllBytes(p) : null);
        } catch (java.io.IOException e) {
            c.note("snapshot " + p + ": " + e);
        }
        return out;
    }

    static void restoreConfig(UiCase c, Map<Path, byte[]> saved, String cls) {
        try {
            for (Map.Entry<Path, byte[]> e : saved.entrySet()) {
                if (e.getValue() == null) {
                    Files.deleteIfExists(e.getKey());
                } else {
                    Files.write(e.getKey(), e.getValue());
                }
            }
            c.onClient(mc -> {
                Mod.staticCall(cls, "load");
                return null;
            });
        } catch (Throwable t) {
            c.note("config restore: " + UiCase.describe(t));
        }
    }

    private static String head(String texture) {
        return "{id:\"minecraft:player_head\",count:1,components:{\"minecraft:profile\":{properties:[{name:\"textures\","
                + "value:\"" + texture + "\"}]}}}";
    }

    private static String anyOf(Object set) {
        return (String) ((Collection<?>) set).iterator().next();
    }

    private static void move(UiCase c, String tag, Vec3 p) {
        c.onClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                for (ArmorStand s : server.overworld().getEntities(EntityTypeTest.forClass(ArmorStand.class),
                        e -> e.entityTags().contains(tag))) {
                    s.setPos(p.x, p.y, p.z);
                }
            });
            return null;
        });
    }

    static void command(UiCase c, String cmd) {
        server(c, s -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), cmd));
    }

    static void server(UiCase c, java.util.function.Consumer<net.minecraft.server.MinecraftServer> task) {
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        c.onClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                try {
                    task.accept(server);
                } finally {
                    done.set(true);
                }
            });
            return null;
        });
        c.ctx().waitFor(mc -> done.get(), 100);
    }

}

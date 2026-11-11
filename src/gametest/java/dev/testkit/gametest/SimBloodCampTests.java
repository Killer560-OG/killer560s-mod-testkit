package dev.testkit.gametest;

import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * simblood-1110 (killer560, 2026-11-10: "would it be possible for you to fully recreate the blood camp and the mobs in each
 * room ... as an option to have them spawn or not spawn"; "for regular mobs i only want starred to spawn").
 * <ul>
 *   <li><b>1110-sim-reallooks</b> - Spawn Starred Mobs and Real Mob Looks on, spots seeded for every mob room, a recorded look
 *       (a skeleton in a blue leather chestplate, room yaw 0) put for half of them: on the server every starred mob of a
 *       look room is that skeleton in that chestplate, turned by a whole quarter per room rotation (the same turn for one
 *       rotation, different ones across rotations), and every starred mob of the other rooms is a dummy without it. Then
 *       Real Mob Looks off, the same looks in memory, a new floor: no starred mob wears the chestplate.</li>
 *   <li><b>1110-sim-bloodcamp</b> - Watcher Blood Camp on, Blood Camp helper on: standing in Blood, the Watcher greets the
 *       room, 16 skulls (F7) fly out of the cases 16.1 blocks (first wave of 4) or 11.9, each lands as a one-HP mob that a
 *       player hit cannot kill before the floor's move time and can after; the helper predicts every landing within a block
 *       and tags the landed mobs (Blood Mobs ESP's list); after the last kill "You have proven yourself. You may pass.",
 *       the camp census line counts the trips, and BossPortal opens on the Watcher's line.</li>
 *   <li><b>1110-sim-bloodcamp-off</b> - both switches off (today's behavior): in Blood no Watcher, no camp, and the portal
 *       opens on the five-second stand-in.</li>
 *   <li><b>1110-sim-switches</b> - the two switches save, a profile reload re-reads them, and the Sim Settings tab shows
 *       Watcher Blood Camp always and Real Mob Looks only while Spawn Starred Mobs is on.</li>
 * </ul>
 * Only when named. GrimAC does not apply (the sim's integrated server).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimBloodCampTests implements FabricClientGameTest {

    static final String LOOKS_CASE = "1110-sim-reallooks";
    static final String CAMP = "1110-sim-bloodcamp";
    static final String OFF = "1110-sim-bloodcamp-off";
    static final String SWITCHES = "1110-sim-switches";
    private static final String PREFS = "com.killer560.hub.roomsim.SimMenuPrefs";
    private static final String CAMP_CLS = "com.killer560.hub.roomsim.SimBloodCamp";
    private static final String LOOKS = SimBloodRushTests.LOOKS;
    private static final String BR_SPOTS = "com.killer560.hub.bloodrush.BloodRushSpots";
    private static final String BC = "com.killer560.hub.bloodcamp.BloodCampFeature";
    private static final String BC_CFG = "com.killer560.hub.bloodcamp.BloodCampConfig";
    private static final String PORTAL = "com.killer560.hub.bosssim.BossPortal";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String SIM_RUN = "com.killer560.hub.roomsim.SimRun";
    private static final String SIM_MOBS = "com.killer560.hub.roomsim.SimMobs";
    private static final String SIM_LIVE = "com.killer560.hub.roomsim.SimLiveMobs";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank()) {
            return;
        }
        List<String> failed = new ArrayList<>();
        for (String name : List.of(SWITCHES, LOOKS_CASE, CAMP, OFF)) {
            if (Scenario.skip(name)) {
                continue;
            }
            ModUnderTest.require("killer560smod");
            LogTap.install();
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
            if (!name.equals(SWITCHES)) {
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
                    ModUnderTest.set(ModUnderTest.config("com.killer560.hub.livemap.LiveMapConfig"), "setEnabled", true);
                });
            }
            List<String> problems = new ArrayList<>();
            List<String> facts = new ArrayList<>();
            Report.caseStarted(name);
            try {
                switch (name) {
                    case SWITCHES -> switches(ctx, problems, facts);
                    case LOOKS_CASE -> realLooks(ctx, problems, facts);
                    case CAMP -> camp(ctx, problems, facts);
                    default -> campOff(ctx, problems, facts);
                }
            } catch (Throwable t) {
                problems.add("threw: " + t);
                t.printStackTrace(System.out);
            } finally {
                ctx.runOnClient(mc -> {
                    prefs(false, false, false);
                    ModUnderTest.staticCall(LOOKS, "resetForTest", new Class<?>[]{boolean.class}, new Object[]{true});
                    ModUnderTest.staticCall(BR_SPOTS, "resetForTest", new Class<?>[]{boolean.class}, new Object[]{true});
                    ModUnderTest.set(ModUnderTest.config(BC_CFG), "setEnabled", false);
                });
                if (!name.equals(SWITCHES)) {
                    SimMobFloorTests.leave(ctx);
                }
            }
            for (String f : facts) {
                println("FACT " + f);
            }
            String verdict = problems.isEmpty() ? "PASS" : "FAIL";
            Report.caseFinished(name, verdict, "n/a (singleplayer)",
                    problems.isEmpty() ? String.join("; ", facts) : String.join("; ", problems), List.of(), List.of());
            SuiteVerdict.finished(name);
            if (!problems.isEmpty()) {
                for (String p : problems) {
                    println("PROBLEM " + p);
                }
                failed.add(name + ": " + String.join("; ", problems));
                continue;
            }
            println("PASS");
        }
        if (!failed.isEmpty()) {
            throw new AssertionError(String.join(" | ", failed));
        }
    }

    /** Client thread: the three saved sim switches, as the Sim Settings rows set them. */
    private static void prefs(boolean mobs, boolean realMobs, boolean bloodCamp) {
        Object p = ModUnderTest.staticCall(PREFS, "load");
        try {
            p.getClass().getField("realMobs").setBoolean(p, realMobs);
            p.getClass().getField("bloodCamp").setBoolean(p, bloodCamp);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("SimMenuPrefs.realMobs / bloodCamp (old jar?)", e);
        }
        ModUnderTest.call(p, "save", new Class<?>[0], new Object[0]);
        SimBloodRushTests.setSpawnStarred(mobs);
    }

    private static boolean field(Object o, String f) {
        try {
            return o.getClass().getField(f).getBoolean(o);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("SimMenuPrefs." + f, e);
        }
    }

    // ---- 1110-sim-switches ------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void switches(ClientGameTestContext ctx, List<String> problems, List<String> facts) {
        ctx.runOnClient(mc -> {
            Object fresh = ModUnderTest.staticCall(PREFS, "cached");
            prefs(false, true, true);
            java.nio.file.Path file = ModUnderTest.modConfig("killer560smod-sim-menu.json");
            String text;
            try {
                text = java.nio.file.Files.readString(file);
            } catch (java.io.IOException e) {
                text = "";
            }
            ModUnderTest.staticCall(PREFS, "reload");
            Object again = ModUnderTest.staticCall(PREFS, "cached");
            facts.add("saved file has realMobs " + text.contains("\"realMobs\": true") + ", bloodCamp "
                    + text.contains("\"bloodCamp\": true") + "; after reload realMobs " + field(again, "realMobs")
                    + ", bloodCamp " + field(again, "bloodCamp") + " (a new object " + (again != fresh) + ")");
            if (!text.contains("\"realMobs\": true") || !text.contains("\"bloodCamp\": true")) {
                problems.add("the switches were not written to " + file.getFileName() + ": " + text);
            }
            if (!field(again, "realMobs") || !field(again, "bloodCamp") || again == fresh) {
                problems.add("reload() did not re-read the saved switches");
            }
            for (boolean mobs : new boolean[]{false, true}) {
                SimBloodRushTests.setSpawnStarred(mobs);
                ModUnderTest.staticCall(PREFS, "load");
                Object tab;
                try {
                    tab = Class.forName("com.killer560.hub.gui.tab.SimSettingsTab").getConstructor().newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("SimSettingsTab", e);
                }
                List<Object> w = (List<Object>) ModUnderTest.call(tab, "buildWidgets",
                        new Class<?>[]{int.class, int.class, int.class, Runnable.class}, new Object[]{0, 0, 300, (Runnable) () -> { }});
                List<String> labels = new ArrayList<>();
                for (Object o : w) {
                    labels.add(((net.minecraft.client.gui.components.AbstractWidget) o).getMessage().getString());
                }
                boolean looks = labels.stream().anyMatch(l -> l.startsWith("Real Mob Looks"));
                boolean camp = labels.stream().anyMatch(l -> l.startsWith("Watcher Blood Camp"));
                facts.add("Spawn Starred Mobs " + mobs + ": rows Real Mob Looks " + looks + ", Watcher Blood Camp " + camp);
                if (looks != mobs || !camp) {
                    problems.add("Sim Settings rows with Spawn Starred Mobs " + mobs + ": " + labels);
                }
            }
            prefs(false, false, false);
            Object off = ModUnderTest.staticCall(PREFS, "load");
            if (field(off, "realMobs") || field(off, "bloodCamp")) {
                problems.add("the switches did not save OFF");
            }
        });
    }

    // ---- 1110-sim-reallooks ------------------------------------------------------------------------------------------

    private static void realLooks(ClientGameTestContext ctx, List<String> problems, List<String> facts) {
        Map<String, Integer> rotation = new HashMap<>();
        Map<String, List<int[]>> rel = new HashMap<>();
        Set<String> lookRooms = new HashSet<>();
        Map<String, List<Object[]>> mobs = floorWithLooks(ctx, true, rotation, rel, lookRooms, facts, problems);
        if (mobs == null) {
            return;
        }
        Map<Integer, Set<Integer>> turnByRotation = new HashMap<>();
        int looked = 0, dummies = 0;
        for (Map.Entry<String, List<Object[]>> e : mobs.entrySet()) {
            boolean want = lookRooms.contains(e.getKey());
            for (Object[] m : e.getValue()) {
                boolean skeleton = "skeleton".equals(m[0]);
                boolean chest = (Boolean) m[1];
                if (want) {
                    if (!skeleton || !chest) {
                        problems.add(e.getKey() + ": a starred mob is " + m[0] + (chest ? " in" : " without") + " the recorded chestplate");
                    }
                    int turn = Math.floorMod(Math.round((Float) m[2]), 360);
                    if (turn % 90 > 1 && turn % 90 < 89) {
                        problems.add(e.getKey() + ": yaw " + m[2] + " is not the recorded 0 turned by a quarter");
                    }
                    turnByRotation.computeIfAbsent(rotation.get(e.getKey()), k -> new HashSet<>()).add(Math.round(turn / 90f) % 4);
                    looked++;
                } else {
                    if (chest) {
                        problems.add(e.getKey() + ": a room with no recorded look got one");
                    }
                    dummies++;
                }
            }
        }
        facts.add(looked + " starred mob(s) as recorded, " + dummies + " dummies; quarter turns by room rotation " + turnByRotation);
        if (looked < 3 || dummies < 1) {
            problems.add("too little to judge: " + looked + " recorded-look mobs, " + dummies + " dummies");
        }
        Set<Integer> distinct = new HashSet<>();
        for (Set<Integer> t : turnByRotation.values()) {
            if (t.size() != 1) {
                problems.add("one room rotation gave different turns: " + turnByRotation);
            }
            distinct.addAll(t);
        }
        if (turnByRotation.size() < 2 || distinct.size() != turnByRotation.size()) {
            problems.add("the look's yaw does not follow the room rotation: " + turnByRotation);
        }
        // Real Mob Looks off: the same looks stay in memory and change nothing.
        Map<String, List<Object[]>> off = floorWithLooks(ctx, false, new HashMap<>(), new HashMap<>(), new HashSet<>(), facts,
                problems);
        if (off == null) {
            return;
        }
        int offMobs = 0;
        for (List<Object[]> l : off.values()) {
            for (Object[] m : l) {
                offMobs++;
                if ((Boolean) m[1]) {
                    problems.add("Real Mob Looks off still dressed a mob as recorded");
                    break;
                }
            }
        }
        facts.add("Real Mob Looks off: " + offMobs + " starred mob(s), none dressed");
        if (offMobs < 3) {
            problems.add("Real Mob Looks off: only " + offMobs + " starred mobs to judge");
        }
    }

    /**
     * A new F7 floor with Spawn Starred Mobs on, spots seeded for every mob room before GO and a look put for every other
     * one; returns each placed room's server starred mobs as {type, wears the chestplate, yaw}, or null on a failure.
     */
    private static Map<String, List<Object[]>> floorWithLooks(ClientGameTestContext ctx, boolean realMobs,
            Map<String, Integer> rotation, Map<String, List<int[]>> rel, Set<String> lookRooms, List<String> facts,
            List<String> problems) {
        ctx.runOnClient(mc -> {
            ModUnderTest.staticCall(BR_SPOTS, "resetForTest", new Class<?>[]{boolean.class}, new Object[]{false});
            ModUnderTest.staticCall(LOOKS, "resetForTest", new Class<?>[]{boolean.class}, new Object[]{false});
            prefs(true, realMobs, false);
            @SuppressWarnings("unchecked")
            Map<String, Integer> shipped = (Map<String, Integer>) ModUnderTest.staticCall(BR_SPOTS, "bundledCounts");
            for (String room : new ArrayList<>(shipped.keySet())) {
                ModUnderTest.staticCall(BR_SPOTS, "putBundledForTest", new Class<?>[]{String.class, List.class},
                        new Object[]{room, new ArrayList<int[]>()});
            }
        });
        long mark = LogTap.mark();
        SimBloodRushTests.startFloor(ctx);
        Map<String, List<BlockPos>> expected = new HashMap<>();
        for (int i = 0; i < 40 && expected.isEmpty(); i++) {
            ctx.waitTicks(2);
            ctx.runOnClient(mc -> SimBloodRushTests.seed(expected, rotation, rel));
        }
        ctx.runOnClient(mc -> {
            List<String> rooms = new ArrayList<>(rel.keySet());
            java.util.Collections.sort(rooms);
            for (int i = 0; i < rooms.size(); i += 2) {
                lookRooms.add(rooms.get(i));
                for (int[] p : rel.get(rooms.get(i))) {
                    ModUnderTest.staticCall(LOOKS, "putForTest", new Class<?>[]{String.class, int.class, int.class, int.class,
                            String.class, String.class, String.class, float.class}, new Object[]{rooms.get(i), p[0], p[1], p[2],
                            "minecraft:skeleton", "Skeleton Master", null, 0f});
                }
            }
        });
        boolean running = false;
        for (int i = 0; i < 400 && !running; i++) {
            ctx.waitTicks(1);
            running = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_RUN, "isRunning"));
        }
        String placedLine = null;
        for (int i = 0; i < 200 && placedLine == null; i += 5) {
            ctx.waitTicks(5);
            placedLine = LogTap.since(mark).stream().filter(l -> l.contains("[Sim] live mobs placed:")).findFirst().orElse(null);
        }
        String looksLine = LogTap.since(mark).stream().filter(l -> l.contains("[Sim] real mob looks:")).findFirst().orElse("(none)");
        facts.add("Real Mob Looks " + realMobs + ": seeded " + expected.size() + " room(s), looks for " + lookRooms.size()
                + "; " + looksLine.replaceAll("^.*\\[Sim\\] ", ""));
        if (placedLine == null) {
            problems.add("no mobs were placed at GO");
            return null;
        }
        if (realMobs == looksLine.equals("(none)")) {
            problems.add("the 'real mob looks' line " + (realMobs ? "is missing" : "showed with the switch off"));
        }
        @SuppressWarnings("unchecked")
        Map<String, List<BlockPos>> placed = ctx.computeOnClient(mc ->
                (Map<String, List<BlockPos>>) ModUnderTest.staticCall(SIM_LIVE, "learnedSpotsPlaced"));
        @SuppressWarnings("unchecked")
        List<UUID[]> pairs = ctx.computeOnClient(mc -> (List<UUID[]>) ModUnderTest.staticCall(SIM_MOBS, "starredPairs"));
        List<Object[]> all = SimBloodRushTests.onServer(ctx, (level, sp) -> {
            List<Object[]> out = new ArrayList<>();
            for (UUID[] p : pairs) {
                Entity e = level.getEntity(p[0]);
                if (e instanceof LivingEntity l && e.isAlive() && !dev.testkit.compat.McEntities.endermanClass().isInstance(e)) {
                    out.add(new Object[]{e.getType().toShortString(), l.getItemBySlot(EquipmentSlot.CHEST).is(Items.LEATHER_CHESTPLATE),
                            e.getYRot(), e.getX(), e.getZ()});
                }
            }
            return out;
        });
        Map<String, List<Object[]>> byRoom = new HashMap<>();
        ctx.runOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "current");
            for (Object[] m : all) {
                int r = (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class, double.class},
                        new Object[]{m[3], m[4]});
                String room = (String) ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r});
                if (room != null && placed.containsKey(room)) {
                    byRoom.computeIfAbsent(room, k -> new ArrayList<>()).add(m);
                }
            }
        });
        return byRoom;
    }

    // ---- 1110-sim-bloodcamp / -off ----------------------------------------------------------------------------------

    /** A new F7 floor (no room mobs), GO, then teleported into Blood; returns Blood's center, or null. */
    private static double[] intoBlood(ClientGameTestContext ctx, boolean camp, List<String> problems) {
        ctx.runOnClient(mc -> prefs(false, false, camp));
        SimBloodRushTests.startFloor(ctx);
        boolean running = false;
        for (int i = 0; i < 400 && !running; i++) {
            ctx.waitTicks(1);
            running = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_RUN, "isRunning"));
        }
        double[] c = ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "current");
            int n = (Integer) ModUnderTest.call(layout, "roomCount", new Class<?>[0], new Object[0]);
            for (int r = 0; r < n; r++) {
                if ("Blood".equals(ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r}))) {
                    int[] tiles = (int[]) ModUnderTest.call(layout, "tiles", new Class<?>[]{int.class}, new Object[]{r});
                    BlockPos p = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                            new Object[]{tiles[0]});
                    return new double[]{p.getX() + 0.5, p.getY(), p.getZ() + 0.5};
                }
            }
            return null;
        });
        if (!running || c == null) {
            problems.add("no run to judge (running " + running + ", Blood room " + (c != null) + ")");
            return null;
        }
        SimMobFloorTests.teleport(ctx, c[0] + 4, c[1], c[2] + 4);
        return c;
    }

    private static int[] state(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (int[]) ModUnderTest.staticCall(CAMP_CLS, "state"));
    }

    @SuppressWarnings("unchecked")
    private static void camp(ClientGameTestContext ctx, List<String> problems, List<String> facts) {
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(BC_CFG), "setEnabled", true));
        long mark = LogTap.mark();
        double[] c = intoBlood(ctx, true, problems);
        if (c == null) {
            return;
        }
        int[] st = state(ctx);
        for (int i = 0; i < 40 && st[0] < 0; i++) {
            ctx.waitTicks(1);
            st = state(ctx);
        }
        if (st[0] < 0) {
            problems.add("standing in Blood never started the camp");
            return;
        }
        int total = st[4];
        // Every landing the helper predicts, by stand id (sampled until the trip ends).
        Map<Integer, Vec3> predicted = new HashMap<>();
        Set<Integer> tagged = new HashSet<>();
        Set<Integer> seenMobs = new HashSet<>();
        boolean earlyHitSurvived = false, earlyHitTried = false;
        int kills = 0;
        for (int t = 0; t < 2400 && st[5] == 0; t++) {
            ctx.waitTicks(1);
            ctx.runOnClient(mc -> {
                Map<?, ?> m;
                try {
                    var f = Class.forName(BC).getDeclaredField("bloodMobs");
                    f.setAccessible(true);
                    m = new HashMap<>((Map<?, ?>) f.get(null));
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("BloodCampFeature.bloodMobs", e);
                }
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    try {
                        var ef = e.getValue().getClass().getDeclaredField("endVector");
                        ef.setAccessible(true);
                        Object end = ef.get(e.getValue());
                        if (end != null) {
                            predicted.put(((Entity) e.getKey()).getId(), (Vec3) end);
                        }
                    } catch (ReflectiveOperationException ex) {
                        throw new AssertionError("BloodMobState.endVector", ex);
                    }
                }
            });
            List<Integer> live = ctx.computeOnClient(mc -> (List<Integer>) ModUnderTest.staticCall(CAMP_CLS, "liveMobIds"));
            st = state(ctx);
            if (live.isEmpty() || t % 5 != 0) {
                continue;
            }
            Set<Integer> esp = ctx.computeOnClient(mc -> new HashSet<>((Set<Integer>) ModUnderTest.staticCall(BC, "bloodMobIds")));
            int killableAt = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(
                    "com.killer560.hub.bloodcamp.BloodCampMoveTimer", "fastWatcherMoveTicksForTest",
                    new Class<?>[]{String.class}, new Object[]{"F7"}));
            boolean canKill = st[0] >= killableAt + 2;
            for (int id : live) {
                if (esp.contains(id)) {
                    tagged.add(id);
                }
                seenMobs.add(id);
            }
            if (!canKill && earlyHitTried) {
                continue;
            }
            List<Integer> targets = canKill ? live : List.of(live.get(0));
            int dead = SimBloodRushTests.onServer(ctx, (level, sp) -> {
                int n = 0;
                for (int id : targets) {
                    Entity e = level.getEntity(id);
                    if (e instanceof LivingEntity l) {
                        l.hurtServer(level, level.damageSources().playerAttack(sp), 5f);
                        if (!l.isAlive()) n++;
                    }
                }
                return n;
            });
            if (!canKill) {
                earlyHitTried = true;
                earlyHitSurvived = dead == 0;
            } else {
                kills += dead;
            }
        }
        List<double[]> paths = ctx.computeOnClient(mc -> (List<double[]>) ModUnderTest.staticCall(CAMP_CLS, "paths"));
        List<String> log = LogTap.since(mark);
        boolean greeted = log.stream().anyMatch(l -> l.contains("[CHAT]") && l.contains("[BOSS] The Watcher: Things feel a little more roomy now, eh?"));
        boolean moveLine = log.stream().anyMatch(l -> l.contains("[CHAT]") && l.contains("[BOSS] The Watcher: Let's see how you can handle this."));
        boolean pass = log.stream().anyMatch(l -> l.contains("[CHAT]") && l.contains("[BOSS] The Watcher: You have proven yourself. You may pass."));
        String census = log.stream().filter(l -> l.contains("camp census:")).findFirst().orElse("(none)");
        boolean portalByLine = false;
        for (int i = 0; i < 100 && !portalByLine; i += 5) {
            ctx.waitTicks(5);
            portalByLine = LogTap.since(mark).stream().anyMatch(l -> l.contains("[BossSim] Blood done (the Watcher's line)"));
        }
        int[] portal = ctx.computeOnClient(mc -> (int[]) ModUnderTest.staticCall(PORTAL, "portal", new Class<?>[0], new Object[0]));
        // The helper's predictions against the real ends: every path has a prediction within a block.
        int matched = 0, firstLen = 0, laterLen = 0;
        double worst = 0;
        for (int i = 0; i < paths.size(); i++) {
            double[] p = paths.get(i);
            Vec3 to = new Vec3(p[3], p[4], p[5]);
            double len = new Vec3(p[0], p[1], p[2]).distanceTo(to);
            if (i < 4 && Math.abs(len - 16.1) < 0.01) firstLen++;
            if (i >= 4 && Math.abs(len - 11.9) < 0.01) laterLen++;
            double best = Double.MAX_VALUE;
            for (Vec3 v : predicted.values()) {
                best = Math.min(best, v.distanceTo(to));
            }
            worst = Math.max(worst, best);
            if (best <= 1.0) matched++;
        }
        st = state(ctx);
        facts.add(String.format(Locale.ROOT, "camp: %d mobs, %d sent, %d landed, %d killed (%d by this test), done %s; greeting %s,"
                        + " move line %s, pass line %s; paths %d (first 16.1: %d/4, later 11.9: %d/%d); helper predicted %d,"
                        + " %d/%d within 1 block (worst %.2f); Blood Mobs ESP tagged %d/%d landed mobs; early hit survived %s;"
                        + " census '%s'; portal %s by line %s",
                total, st[1], st[2], st[3], kills, st[5] == 1, greeted, moveLine, pass, paths.size(), firstLen, laterLen,
                paths.size() - 4, predicted.size(), matched, paths.size(), worst, tagged.size(), seenMobs.size(), earlyHitSurvived,
                census.replaceAll("^.*\\[BloodCamp\\] ", ""), portal == null ? "null" : portal[0] + "," + portal[1] + "," + portal[2],
                portalByLine));
        if (total != 16) problems.add("F7 camp has " + total + " mobs, want 16 (9 + floor)");
        if (st[5] != 1 || st[3] != total || st[2] != total) problems.add("the camp did not finish: " + java.util.Arrays.toString(st));
        if (!greeted || !moveLine || !pass) problems.add("Watcher lines missing (greeting " + greeted + ", move " + moveLine + ", pass " + pass + ")");
        if (!earlyHitTried || !earlyHitSurvived) problems.add("a mob hit before the move time died (or was never tried)");
        if (paths.size() != total || firstLen != 4 || laterLen != total - 4) problems.add("trip lengths are not 16.1 / 11.9");
        if (matched != paths.size()) problems.add("the Blood Camp helper predicted " + matched + " of " + paths.size() + " landings within a block");
        if (tagged.size() < seenMobs.size() - 1 || tagged.isEmpty()) problems.add("Blood Mobs ESP tagged only " + tagged.size() + " of " + seenMobs.size());
        if (!census.contains(total + " skull trips (4 first wave)")) problems.add("no matching camp census line: " + census);
        if (portal == null || !portalByLine) problems.add("the portal did not open on the Watcher's line");
    }

    private static void campOff(ClientGameTestContext ctx, List<String> problems, List<String> facts) {
        long mark = LogTap.mark();
        double[] c = intoBlood(ctx, false, problems);
        if (c == null) {
            return;
        }
        boolean standIn = false;
        for (int i = 0; i < 200 && !standIn; i += 5) {
            ctx.waitTicks(5);
            standIn = LogTap.since(mark).stream().anyMatch(l -> l.contains("[BossSim] Blood done (stand-in)"));
        }
        int[] st = state(ctx);
        boolean watcher = LogTap.since(mark).stream().anyMatch(l -> l.contains("[CHAT]") && l.contains("[BOSS] The Watcher"));
        int starred = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_MOBS, "starredCount"));
        facts.add("off: camp state " + java.util.Arrays.toString(st) + ", Watcher line " + watcher + ", stand-in portal " + standIn
                + ", starred " + starred);
        if (st[0] != -1 || st[4] != 0 || watcher) problems.add("the camp ran with Watcher Blood Camp off");
        if (!standIn) problems.add("the five-second stand-in did not open the portal");
        if (starred != 0) problems.add(starred + " starred mobs with Spawn Starred Mobs off");
    }

    private static void println(String s) {
        System.out.println("[" + SimBloodCampTests.class.getSimpleName() + "] " + s);
    }
}

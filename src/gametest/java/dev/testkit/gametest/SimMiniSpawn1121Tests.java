package dev.testkit.gametest;

import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * sim-minispawn-1121 (killer560, 2026-11-21: "in miniboss rooms it still spawns them randomly instead of where the mini
 * actually spawns").
 * <ul>
 *   <li><b>1121-sim-miniboss-spot</b> - Spawn Starred Mobs and Real Mob Looks on, a miniboss spot recorded (room-relative,
 *       yaw 90) for each miniboss (CHAMPION) room, that room pinned onto a new F7 in different cells until two rooms in two
 *       rotations were built: the room's miniboss is a real player-type entity of the recorded name standing within 0.5
 *       block of the recorded spot turned with the room, facing the recorded yaw turned with it. Then Real Mob Looks off on
 *       the same room: the miniboss is not on the recorded spot (today's random one).</li>
 * </ul>
 */
public class SimMiniSpawn1121Tests implements FabricClientGameTest {

    static final String CASE = "1121-sim-miniboss-spot";
    private static final String LOOKS = SimBloodRushTests.LOOKS;
    private static final String LIB = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_RUN = "com.killer560.hub.roomsim.SimRun";
    private static final String SIM_MOBS = "com.killer560.hub.roomsim.SimMobs";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String COORDS = "com.killer560.hub.autoroutes.RouteCoords";
    private static final Set<String> NAMES = Set.of("Lost Adventurer", "Shadow Assassin", "Angry Archaeologist",
            "Frozen Adventurer", "King Midas");
    private static final int[] REL = {9, 69, 21};
    private static final float REL_YAW = 90f;
    private static final int[] CELLS = {14, 15, 20, 9, 21, 8};

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(CASE)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        List<String> problems = new ArrayList<>();
        List<String> facts = new ArrayList<>();
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(CASE, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(LIB, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(LIB, "isReady"), 2400);
        ctx.runOnClient(mc -> {
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"), "setAutoPuzzlesMasterEnabled", false);
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.livemap.LiveMapConfig"), "setEnabled", true);
        });
        Report.caseStarted(CASE);
        try {
            body(ctx, problems, facts);
        } catch (Throwable t) {
            problems.add("threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> {
                SimBloodCampTests.prefs(false, false, false);
                ModUnderTest.staticCall(LOOKS, "resetForTest", new Class<?>[]{boolean.class}, new Object[]{true});
            });
            SimMobFloorTests.leave(ctx);
        }
        for (String f : facts) {
            println("FACT " + f);
        }
        Report.caseFinished(CASE, problems.isEmpty() ? "PASS" : "FAIL", "n/a (singleplayer)",
                problems.isEmpty() ? String.join("; ", facts) : String.join("; ", problems), List.of(), List.of());
        SuiteVerdict.finished(CASE);
        if (!problems.isEmpty()) {
            for (String p : problems) {
                println("PROBLEM " + p);
            }
            throw new AssertionError(CASE + ": " + String.join("; ", problems));
        }
        println("PASS");
    }

    @SuppressWarnings("unchecked")
    private static void body(ClientGameTestContext ctx, List<String> problems, List<String> facts) {
        List<String> champions = ctx.computeOnClient(mc -> {
            List<String> out = new ArrayList<>();
            for (String n : (List<String>) ModUnderTest.staticCall(LIB, "names")) {
                if ("CHAMPION".equalsIgnoreCase((String) ModUnderTest.staticCall(FLOOR_GEN, "typeOf",
                        new Class<?>[]{String.class}, new Object[]{n}))
                        && (Boolean) ModUnderTest.staticCall(LIB, "isUsable", new Class<?>[]{String.class}, new Object[]{n})
                        && (Integer) ModUnderTest.staticCall(LIB, "cellFootprint", new Class<?>[]{String.class}, new Object[]{n}) == 1) {
                    out.add(n);
                }
            }
            return out;
        });
        facts.add("miniboss rooms in the database: " + champions);
        if (champions.isEmpty()) {
            problems.add("no usable one-cell miniboss room in the room database");
            return;
        }
        Set<String> done = new HashSet<>();
        Set<Integer> rotations = new HashSet<>();
        String offRoom = null;
        for (int attempt = 0; attempt < 8 && (done.size() < 2 || rotations.size() < 2); attempt++) {
            String room = champions.get((attempt / 2) % champions.size());
            int cell = CELLS[attempt % CELLS.length];
            String code = ctx.computeOnClient(mc -> plan(room, cell));
            if (code == null) {
                println("could not pin " + room + " at cell " + cell);
                continue;
            }
            int[] cr = run(ctx, room, code, true, problems, facts);
            if (cr == null) {
                return;
            }
            done.add(room);
            rotations.add(cr[2]);
            offRoom = room;
            if (!problems.isEmpty()) {
                return;
            }
        }
        facts.add("built " + done + " in rotations " + rotations);
        if (done.size() < 2 || rotations.size() < 2) {
            problems.add("too little to judge: rooms " + done + ", rotations " + rotations);
            return;
        }
        // Real Mob Looks off: the same record changes nothing, the miniboss is not placed on it.
        String code = null;
        for (int i = 0; i < CELLS.length && code == null; i++) {
            final int cell = CELLS[i];
            final String r = offRoom;
            code = ctx.computeOnClient(mc -> plan(r, cell));
        }
        if (code != null) {
            run(ctx, offRoom, code, false, problems, facts);
        }
    }

    /** Plans an F7 with {@code room} pinned at {@code cell}; its code, or null when the pin was dropped. Client thread. */
    private static String plan(String room, int cell) {
        Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
        Map<Integer, String> pins = new HashMap<>();
        pins.put(cell, room);
        Object planned = ModUnderTest.staticCall(FLOOR_GEN, "plan", new Class<?>[]{floor.getClass(), int.class, int.class,
                Map.class}, new Object[]{floor, 3, 6, pins});
        if (planned == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        List<String> kept = (List<String>) ModUnderTest.call(planned, "keptPins", new Class<?>[0], new Object[0]);
        return kept == null || kept.isEmpty() ? null : (String) ModUnderTest.call(planned, "code", new Class<?>[0], new Object[0]);
    }

    /**
     * Builds the floor with the miniboss spot recorded for {@code room}, waits for the mobs and checks the miniboss's place.
     * @return {clayX, clayZ, rotation} of the room, or null when the floor did not come up (a problem recorded)
     */
    @SuppressWarnings("unchecked")
    private static int[] run(ClientGameTestContext ctx, String room, String code, boolean real, List<String> problems,
            List<String> facts) {
        ctx.runOnClient(mc -> {
            ModUnderTest.staticCall(LOOKS, "resetForTest", new Class<?>[]{boolean.class}, new Object[]{false});
            SimBloodCampTests.prefs(true, real, false);
            if (real) {
                try {
                    ModUnderTest.staticCall(LOOKS, "putMinibossForTest", new Class<?>[]{String.class, int.class, int.class, int.class,
                            String.class, float.class}, new Object[]{room, REL[0], REL[1], REL[2], "Shadow Assassin", REL_YAW});
                } catch (Throwable t) {
                    throw new AssertionError("SimMobLooks.putMinibossForTest missing (old jar?)", t);
                }
            }
            ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class}, new Object[]{"gametest"});
        });
        long mark = LogTap.mark();
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            ModUnderTest.staticCall(SIM_STATE, "setFloorLabel", new Class<?>[]{String.class}, new Object[]{"F7"});
            java.util.function.Consumer<Minecraft> build = c -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder",
                    "build", new Class<?>[]{Minecraft.class, String.class}, new Object[]{c, code});
            ModUnderTest.staticCall("com.killer560.hub.roomsim.SimWorld", "open", new Class<?>[]{Minecraft.class, String.class,
                    java.util.function.Consumer.class, String.class}, new Object[]{mc, code, build, "Generating Floor 7"});
        }));
        ctx.waitFor(mc -> mc.level != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_RUN, "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        String placed = null;
        for (int i = 0; i < 200 && placed == null; i += 5) {
            ctx.waitTicks(5);
            placed = LogTap.since(mark).stream().filter(l -> l.contains("[Sim] live mobs placed:")).findFirst().orElse(null);
        }
        if (placed == null) {
            problems.add(room + ": no mobs were placed at GO");
            return null;
        }
        // The room's frame, from the layout.
        int[] cr = ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "current");
            int n = (Integer) ModUnderTest.call(layout, "roomCount", new Class<?>[0], new Object[0]);
            for (int r = 0; r < n; r++) {
                if (room.equals(ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r}))) {
                    return (int[]) ModUnderTest.call(layout, "clayRotation", new Class<?>[]{int.class}, new Object[]{r});
                }
            }
            return null;
        });
        if (cr == null) {
            problems.add(room + " is not on the built floor");
            return null;
        }
        Object[] exp = ctx.computeOnClient(mc -> {
            try {
                Class<?> fc = Class.forName(COORDS + "$Frame");
                Object frame = fc.getConstructor(String.class, int.class, int.class, int.class).newInstance(room, cr[0], cr[1], cr[2]);
                BlockPos b = (BlockPos) ModUnderTest.staticCall(COORDS, "toRealBlock", new Class<?>[]{fc, BlockPos.class},
                        new Object[]{frame, new BlockPos(REL[0], REL[1], REL[2])});
                float yaw = (Float) ModUnderTest.staticCall(COORDS, "toRealYaw", new Class<?>[]{fc, float.class}, new Object[]{frame, REL_YAW});
                return new Object[]{b, yaw};
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        BlockPos want = (BlockPos) exp[0];
        float wantYaw = (Float) exp[1];
        List<UUID[]> pairs = ctx.computeOnClient(mc -> (List<UUID[]>) ModUnderTest.staticCall(SIM_MOBS, "starredPairs"));
        List<Object[]> bosses = SimBloodRushTests.onServer(ctx, (level, sp) -> {
            List<Object[]> out = new ArrayList<>();
            for (UUID[] p : pairs) {
                Entity e = level.getEntity(p[0]);
                if (e instanceof ServerPlayer b && b.isAlive() && NAMES.contains(b.getName().getString())) {
                    out.add(new Object[]{b.getName().getString(), b.getX(), b.getY(), b.getZ(), b.getYRot()});
                }
            }
            return out;
        });
        int rot = cr[2];
        facts.add(room + " (rotation " + rot + ", Real Mob Looks " + real + "): " + bosses.size() + " miniboss(es), wanted "
                + want.toShortString() + " yaw " + wantYaw);
        if (bosses.size() != 1) {
            problems.add(room + ": " + bosses.size() + " miniboss(es) placed, expected 1");
            return cr;
        }
        Object[] b = bosses.get(0);
        double dx = (Double) b[1] - (want.getX() + 0.5);
        double dz = (Double) b[3] - (want.getZ() + 0.5);
        double dy = (Double) b[2] - want.getY();
        double dist = Math.sqrt(dx * dx + dz * dz);
        facts.add(room + ": miniboss " + b[0] + " stands " + String.format("%.2f", dist) + " blocks from the recorded spot (dy "
                + String.format("%.2f", dy) + "), yaw " + b[4]);
        if (real) {
            if (dist > 0.5 || Math.abs(dy) > 1.0) {
                problems.add(room + ": the miniboss stands " + String.format("%.2f", dist) + " blocks (dy " + dy + ") off the recorded spot");
            }
            if (!"Shadow Assassin".equals(b[0])) {
                problems.add(room + ": the miniboss is " + b[0] + ", recorded Shadow Assassin");
            }
            float dyaw = Math.abs(((Float) b[4] - wantYaw) % 360f);
            if (Math.min(dyaw, 360f - dyaw) > 2f) {
                problems.add(room + ": the miniboss faces " + b[4] + ", recorded yaw turned with the room is " + wantYaw);
            }
        } else if (dist < 0.5) {
            problems.add(room + ": Real Mob Looks off still put the miniboss on the recorded spot");
        }
        return cr;
    }

    private static void println(String s) {
        System.out.println("[" + SimMiniSpawn1121Tests.class.getSimpleName() + "] " + s);
    }
}

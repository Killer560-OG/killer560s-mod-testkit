package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

/**
 * 1961-1978: room levers and the gates they drop (killer560, 2026-10-16, "this lever in grass ruins should drop this
 * gate", then a pass through every room). Everything is read back from the integrated SERVER's level.
 *
 * <ul>
 *   <li><b>1961-sim-gate-sweep</b>: {@code SimRoomLevers.sweep()} over the whole room library, from the captures alone:
 *       every rule's lever is a captured lever or one the sim places into air, and every rule takes at least one
 *       captured block. Every room on his list has its rules. The captured levers no rule names are printed.</li>
 *   <li><b>1962-sim-gate-grassruin</b>: Grass Ruin solo. The basement lever drops exactly the window's 33 iron bars
 *       (the two andesite corners stay), and a 1 x 2 way through the window opens where there was none.</li>
 *   <li><b>1963-1976</b>: each other room on his list, solo: stood in reach of each lever (the spot found on the
 *       server), used as the player, the lever must flip on the server, its gate's blocks must all go, the rest of
 *       its box must stay as it was, and every gate whose lever is still off must stay whole.</li>
 *   <li><b>1977-sim-gate-floor</b>: Grass Ruin and Layers on one hand-drawn floor with the Entrance; the same checks
 *       for all three of their gates.</li>
 *   <li><b>1978-sim-gate-survey</b>: screenshots only, from fixed spots; runs only when named.</li>
 *   <li><b>1979-sim-gate-reset</b>: Grass Ruin's window dropped, then {@code /reset}: the bars are back, the lever is
 *       off, and it drops them again.</li>
 * </ul>
 * Each gate is written here independently of the mod's table: "lever|from|to|count|only", capture-local, count being
 * how many blocks the gate holds and only the block ids it is made of (empty for the whole box).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimLeverGateTests implements FabricClientGameTest {

    private static final String NAME = "196x-sim-lever-gates";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_LEVERS = "com.killer560.hub.roomsim.SimRoomLevers";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";

    /** Room -> its gates, in the order they are flicked. */
    private static final Map<String, String[]> GATES = new LinkedHashMap<>();

    static {
        // 2026-10-17: the upstairs lever drops the wood door across the gallery ("this lever drops this door").
        GATES.put("Grass Ruin", new String[]{"32,53,7|37,52,8|37,58,12|33|iron_bars",
                "32,82,3|29,63,14|35,71,14|59|oak_wood+spruce_stairs+oak_log"});
        GATES.put("Layers", new String[]{"6,76,3|5,69,10|7,73,10|13|oak_wood+spruce_stairs+iron_bars",
                "9,90,33|46,69,47|46,72,49|12|"});
        GATES.put("Long Hall", new String[]{"21,70,28|20,84,11|22,88,11|15|"});
        GATES.put("Mines", new String[]{"53,79,10|47,80,11|47,83,15|20|", "15,80,4|4,90,19|8,95,19|30|",
                "35,79,60|50,78,58|50,84,60|21|"});
        GATES.put("Mossy", new String[]{"64,70,30|55,89,3|56,95,6|56|"});
        GATES.put("Overgrown", new String[]{"4,81,16|25,79,11|28,82,11|16|"});
        GATES.put("Pipes", new String[]{"53,94,16|62,54,24|66,63,24|50|"});
        GATES.put("Pirate", new String[]{"44,82,16|6,82,15|7,89,18|64|"});
        GATES.put("Pressure Plates", new String[]{"6,71,26|12,70,22|12,75,26|30|", "22,83,16|54,93,15|55,97,17|30|"});
        GATES.put("Purple Flags", new String[]{"30,55,28|25,49,13|27,53,13|15|"});
        GATES.put("Redstone Crypt", new String[]{"16,68,16|5,69,15|6,74,17|33|oak_log"});
        GATES.put("Redstone Key", new String[]{"6,70,28|25,69,11|27,74,11|16|acacia_log"});
        GATES.put("Supertall", new String[]{"51,77,47|43,73,30|44,81,34|90|", "10,98,25|43,95,30|44,103,34|90|",
                "13,131,8|30,117,20|34,125,21|90|", "54,142,52|52,139,55|54,139,57|9|"});
        GATES.put("Water", new String[]{"16,70,8|7,69,5|8,72,6|16|"});
        GATES.put("Withermancer", new String[]{"25,61,4|27,111,15|27,117,17|19|iron_bars"});
        // 2026-10-17: the cobblestone WALL lattices, Buttons' two levers, and the sweep's rooms as he confirmed them.
        GATES.put("Rails", new String[]{"46,71,24|54,49,42|58,54,42|21|cobblestone_wall"});
        GATES.put("Spider", new String[]{"28,93,33|21,83,31|21,87,35|16|cobblestone_wall"});
        GATES.put("Buttons", new String[]{"59,70,37|30,73,18|30,76,20|12|", "43,81,58|56,80,16|58,88,16|27|"});
        GATES.put("End", new String[]{"8,84,6|14,69,11|18,76,11|40|"});
        GATES.put("Gravel", new String[]{"5,69,64|19,69,34|21,73,34|15|"});
        GATES.put("Dip", new String[]{"5,79,20|17,78,17|18,82,18|20|"});
        GATES.put("Lava Ravine", new String[]{"43,84,55|7,88,38|11,92,38|25|"});
        GATES.put("Lots Of Floors", new String[]{"19,62,17|20,76,25|21,81,27|36|"});
        GATES.put("Museum", new String[]{"54,84,27|48,69,30|48,77,34|45|"});
        GATES.put("Rare Pillars", new String[]{"7,88,28|11,69,11|11,75,13|21|"});
        GATES.put("Red Green", new String[]{"2,61,16|8,74,15|8,77,17|12|"});
        GATES.put("Red Blue", new String[]{"55,69,6|57,69,12|60,74,12|24|"});
        // The 2026-10-12 lever and the route's other one both drop the oak log door (his /simwhere: capture 8,81,25).
        GATES.put("Atlas", new String[]{"61,90,34|7,80,25|9,83,26|24|"});
        GATES.put("Criss Cross", new String[]{"16,75,2|15,66,14|17,70,14|14|oak_log"});
    }

    private static final String[] ROOM_CASES = {"1963-sim-gate-layers", "1964-sim-gate-long-hall",
            "1965-sim-gate-mines", "1966-sim-gate-mossy", "1967-sim-gate-overgrown", "1968-sim-gate-pipes",
            "1969-sim-gate-pirate", "1970-sim-gate-pressure-plates", "1971-sim-gate-purple-flags",
            "1972-sim-gate-redstone-crypt", "1973-sim-gate-redstone-key", "1974-sim-gate-supertall",
            "1975-sim-gate-water", "1976-sim-gate-withermancer",
            // 2026-10-17 (sim-rails-spider-1017)
            "1981-sim-gate-rails", "1982-sim-gate-spider", "1983-sim-gate-buttons", "1984-sim-gate-end",
            "1985-sim-gate-gravel", "1986-sim-gate-dip", "1987-sim-gate-criss-cross", "1988-sim-gate-lava-ravine",
            "1989-sim-gate-lots-of-floors", "1990-sim-gate-museum",
            "1991-sim-gate-rare-pillars", "1992-sim-gate-red-green",
            "1993-sim-gate-red-blue", "1994-sim-gate-atlas"};
    private static final String[] ROOM_OF_CASE = {"Layers", "Long Hall", "Mines", "Mossy", "Overgrown", "Pipes",
            "Pirate", "Pressure Plates", "Purple Flags", "Redstone Crypt", "Redstone Key", "Supertall", "Water",
            "Withermancer", "Rails", "Spider", "Buttons", "End", "Gravel", "Dip", "Criss Cross", "Lava Ravine",
            "Lots Of Floors", "Museum", "Rare Pillars", "Red Green", "Red Blue", "Atlas"};

    private static UUID playerId;
    private static final List<String> RESULTS = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        List<String> selected = new ArrayList<>();
        // The sweep last: it needs no world, and a failure with no world loaded ends the loop.
        List<String> all = new ArrayList<>(List.of("1962-sim-gate-grassruin"));
        all.addAll(Arrays.asList(ROOM_CASES));
        all.add("1977-sim-gate-floor");
        all.add("1979-sim-gate-reset");
        all.add("1980-sim-gate-where");
        all.add("1961-sim-gate-sweep");
        for (String c : all) {
            if (!Scenario.skip(c)) {
                selected.add(c);
            }
        }
        String filter = System.getProperty("testkit.scenario", "");
        if (filter.contains("survey") && !Scenario.skip("1978-sim-gate-survey")) {
            selected.add("1978-sim-gate-survey");
        }
        if (selected.isEmpty()) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(20);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            for (String c : selected) {
                Scenario.skipped(c, "needs real room captures and room database");
            }
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        ctx.runOnClient(mc -> mc.options.guiScale().set(2));
        try {
            for (String c : selected) {
                long mark = LogTap.mark();
                try {
                    say("---- " + c + " ----");
                    switch (c) {
                        case "1961-sim-gate-sweep" -> caseSweep(ctx);
                        case "1962-sim-gate-grassruin" -> caseGrassRuin(ctx);
                        case "1977-sim-gate-floor" -> caseFloor(ctx);
                        case "1978-sim-gate-survey" -> caseSurvey(ctx);
                        case "1979-sim-gate-reset" -> caseReset(ctx);
                        case "1980-sim-gate-where" -> caseWhere(ctx);
                        default -> {
                            int i = Arrays.asList(ROOM_CASES).indexOf(c);
                            String room = ROOM_OF_CASE[i];
                            build(ctx, room);
                            gates(ctx, c, room, GATES.get(room));
                        }
                    }
                    RESULTS.add(c + " PASS");
                    System.out.println("[" + c + "] PASS");
                } catch (Throwable t) {
                    RESULTS.add(c + " FAIL: " + t.getMessage());
                    System.out.println("[" + c + "] FAIL: " + t);
                    List<String> since = LogTap.since(mark);
                    for (String l : since.subList(Math.max(0, since.size() - 40), since.size())) {
                        System.out.println("[" + c + "]   log: " + l);
                    }
                    SuiteVerdict.failCase(c, t);
                    if (ctx.computeOnClient(mc -> mc.level == null)) {
                        break;
                    }
                }
            }
        } finally {
            teardown(ctx);
        }
        System.out.println("[" + NAME + "] SUMMARY");
        for (String r : RESULTS) {
            System.out.println("[" + NAME + "]   " + r);
        }
    }

    // ============================================================================================ 1961

    @SuppressWarnings("unchecked")
    private void caseSweep(ClientGameTestContext ctx) {
        List<String> lines = ctx.computeOnClient(mc -> (List<String>) ModUnderTest.staticCall(SIM_LEVERS, "sweep"));
        check(lines != null && !lines.isEmpty(), "the sweep returned nothing");
        int rules = 0;
        int unbound = 0;
        Map<String, Integer> rulesPerRoom = new HashMap<>();
        List<String> bad = new ArrayList<>();
        for (String l : lines) {
            say("sweep: " + l);
            if (l.startsWith("RULE ")) {
                rules++;
                String room = l.substring(5, l.indexOf(" lever "));
                rulesPerRoom.merge(room, 1, Integer::sum);
                boolean leverOk = l.contains(" is lever") || l.contains(" is air (placed");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("takes (\\d+) of (\\d+)$").matcher(l);
                boolean takesOk = m.find() && Integer.parseInt(m.group(1)) > 0;
                if (!leverOk || !takesOk || l.contains("no capture")) {
                    bad.add(l);
                }
            } else if (l.startsWith("UNBOUND ")) {
                unbound++;
            }
        }
        say("sweep: " + rules + " rule(s), " + unbound + " captured lever(s) with no rule, " + bad.size() + " bad rule(s)");
        check(bad.isEmpty(), bad.size() + " rule(s) arm on no lever or take nothing: " + bad);
        for (Map.Entry<String, String[]> e : GATES.entrySet()) {
            // The mod's own table must hold at least every gate this test knows for the room.
            int have = rulesPerRoom.getOrDefault(e.getKey(), 0);
            check(have >= e.getValue().length, e.getKey() + " has " + have + " rule(s), expected at least "
                    + e.getValue().length);
        }
    }

    // ============================================================================================ 1962

    private void caseGrassRuin(ClientGameTestContext ctx) {
        String room = "Grass Ruin";
        build(ctx, room);
        // The way through: the middle of the window, two high from its floor.
        BlockPos low = capW(ctx, room, 37, 52, 10);
        BlockPos high = capW(ctx, room, 37, 53, 10);
        BlockPos cornerA = capW(ctx, room, 37, 58, 8);
        BlockPos cornerB = capW(ctx, room, 37, 58, 12);
        BlockPos chest = capW(ctx, room, 60, 55, 17);
        boolean openBefore = passable(ctx, low) && passable(ctx, high);
        String chestBlock = blockName(ctx, chest);
        say("Grass Ruin before: way through the window open " + openBefore + ", chest " + chestBlock);
        check(!openBefore, "the window is already passable before the lever");
        // A trapped chest when the floor's mimic landed on it.
        check(chestBlock.equals("chest") || chestBlock.equals("trapped_chest"),
                "no chest at capture 60,55,17 - it is " + chestBlock);
        gates(ctx, "1962-sim-gate-grassruin", room, GATES.get(room));
        boolean openAfter = passable(ctx, low) && passable(ctx, high);
        String a = blockName(ctx, cornerA);
        String b = blockName(ctx, cornerB);
        say("Grass Ruin after: way through the window open " + openAfter + ", arch corners " + a + "/" + b);
        check(openAfter, "the window is still not passable after the lever");
        check(a.equals("polished_andesite") && b.equals("polished_andesite"),
                "the lever took the window's andesite corners (" + a + ", " + b + ")");
    }

    // ============================================================================================ 1977

    private void caseFloor(ClientGameTestContext ctx) {
        Map<Integer, String> floor = new LinkedHashMap<>();
        floor.put(0, "Grass Ruin");         // cells (0,0) and (1,0)
        floor.put(2, "Entrance");           // (2,0)
        floor.put(6, "Layers");             // its 2 x 2 box from (0,1)
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(FLOOR_GEN, "buildExplicit",
                new Class<?>[]{Minecraft.class, Map.class}, new Object[]{mc, floor})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        ctx.waitTicks(60);
        playerId = ctx.computeOnClient(mc -> mc.player.getUUID());
        int armed = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_LEVERS, "armedCount"));
        say("floor built: " + armed + " lever(s) armed");
        check(armed >= 3, "only " + armed + " lever(s) armed on a floor holding Grass Ruin and Layers");
        gates(ctx, "1977-sim-gate-floor Grass Ruin", "Grass Ruin", GATES.get("Grass Ruin"));
        gates(ctx, "1977-sim-gate-floor Layers", "Layers", GATES.get("Layers"));
    }

    // ============================================================================================ 1980

    /**
     * /simwhere names the room for every block of its walls and edges (killer560, 2026-10-16: on Spider's facade and
     * Atlas' log wall it said "not inside any placed room"). For each room built alone: every column of the capture's
     * outer ring and of the ring one in, at the room's door height, must be named as that room. Then the points he
     * /simwhere'd are read back: Rails' wall at world (-81, -57, -95) must be capture (56, 51, 42), which also proves
     * the solo build lands where his did, and Spider's (-116, -4, -104) and Atlas' (-129, -30, -112) are printed.
     */
    private void caseWhere(ClientGameTestContext ctx) {
        String[][] rooms = {{"Rails", "-81,-57,-95"}, {"Spider", "-116,-4,-104"}, {"Atlas", "-129,-30,-112"},
                {"Buttons", ""}};
        List<String> bad = new ArrayList<>();
        for (String[] r : rooms) {
            String room = r[0];
            build(ctx, room);
            int[] size = roomSize(ctx, room);
            int checked = 0;
            int wrong = 0;
            String firstWrong = null;
            for (int ring = 0; ring <= 1; ring++) {
                for (int x = ring; x < size[0] - ring; x++) {
                    for (int z = ring; z < size[1] - ring; z++) {
                        if (x != ring && x != size[0] - 1 - ring && z != ring && z != size[1] - 1 - ring) {
                            continue;
                        }
                        BlockPos w = capW(ctx, room, x, 70, z);
                        String named = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(
                                "com.killer560.hub.roomsim.SimWhere", "roomNameAt",
                                new Class<?>[]{BlockPos.class}, new Object[]{w}));
                        checked++;
                        if (!room.equals(named)) {
                            wrong++;
                            if (firstWrong == null) {
                                firstWrong = "capture " + x + ",70," + z + " -> " + named;
                            }
                        }
                    }
                }
            }
            say("1980 " + room + ": " + checked + " edge column(s), " + wrong + " not named " + room
                    + (firstWrong == null ? "" : " (first: " + firstWrong + ")"));
            if (wrong > 0) {
                bad.add(room + " " + wrong + "/" + checked);
            }
            if (!r[1].isEmpty()) {
                int[] p = ints(r[1]);
                BlockPos his = new BlockPos(p[0], p[1], p[2]);
                String named = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(
                        "com.killer560.hub.roomsim.SimWhere", "roomNameAt", new Class<?>[]{BlockPos.class},
                        new Object[]{his}));
                int[] local = ctx.computeOnClient(mc -> (int[]) ModUnderTest.staticCall(
                        "com.killer560.hub.roomsim.SimWhere", "captureLocalAt", new Class<?>[]{BlockPos.class},
                        new Object[]{his}));
                String block = blockName(ctx, his);
                say("1980 " + room + ": his /simwhere point " + his.toShortString() + " is " + block + ", named " + named
                        + ", capture-local " + (local == null ? "none" : local[0] + "," + local[1] + "," + local[2]));
                if (!room.equals(named)) {
                    bad.add(room + " his point named " + named);
                }
                if (room.equals("Rails") && (local == null || local[0] != 56 || local[1] != 51 || local[2] != 42)) {
                    bad.add("Rails' point is not capture 56,51,42 - the solo build is not where his was");
                }
            }
        }
        check(bad.isEmpty(), "/simwhere: " + bad);
    }

    // ============================================================================================ 1979

    /**
     * Grass Ruin solo: the lever drops the window, then {@code /reset} (typed as he types it) must put the 33 bars back
     * and the lever off, and the lever must drop them again - the rebuild re-arms it.
     */
    private void caseReset(ClientGameTestContext ctx) {
        String room = "Grass Ruin";
        build(ctx, room);
        gates(ctx, "1979-sim-gate-reset first", room, GATES.get(room));
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.player.connection.sendCommand("reset"));
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        ctx.waitTicks(60);
        long after = Scenario.simBuildCount(ctx);
        say("1979 /reset: builds " + before + " -> " + after);
        check(after > before, "/reset built nothing");
        // gates() checks the window is whole (33/33) and the lever off before it flicks again.
        gates(ctx, "1979-sim-gate-reset after /reset", room, GATES.get(room));
    }

    // ============================================================================================ 1978

    private void caseSurvey(ClientGameTestContext ctx) {
        // room | stand x,y,z (feet, capture) | look x,y,z (capture) | name
        String[] views = {
                "Rails|46,71,27|46,71,24|rails-lever",
                "Rails|46,71,27|50,72,27|rails-east", "Rails|46,71,27|42,72,27|rails-west",
                "Rails|46,71,27|46,72,31|rails-south",
                "Rails|57,49,55|61,50,55|rails-chest7",
                "Spider|24,84,33|28,85,33|spider-chest", "Spider|24,93,33|28,93,33|spider-lever",
                "Mines|6,90,13|6,92,19|mines-wall-north", "Mines|6,90,25|6,92,19|mines-wall-south",
                "Mines|15,81,8|15,80,4|mines-lever7",
                "Purple Flags|29,49,28|30,55,28|pf-cell", "Purple Flags|30,49,23|30,50,30|pf-corridor",
                "Layers|6,69,14|6,70,10|layers-door", "Layers|9,90,36|9,90,33|layers-lever2",
                "Supertall|51,77,49|43,77,32|st-l1", "Supertall|10,98,27|43,99,32|st-l2"};
        String built = null;
        for (String v : views) {
            String[] f = v.split("\\|");
            if (!f[0].equals(built)) {
                build(ctx, f[0]);
                built = f[0];
            }
            int[] s = ints(f[1]);
            int[] l = ints(f[2]);
            BlockPos feet = capW(ctx, f[0], s[0], s[1], s[2]);
            BlockPos look = capW(ctx, f[0], l[0], l[1], l[2]);
            double ex = feet.getX() + 0.5;
            double ey = feet.getY() + 1.62;
            double ez = feet.getZ() + 0.5;
            double dx = look.getX() + 0.5 - ex;
            double dy = look.getY() + 0.5 - ey;
            double dz = look.getZ() + 0.5 - ez;
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            onServer(ctx, (level, sp) -> {
                sp.getAbilities().mayfly = true;
                sp.getAbilities().flying = true;
                sp.onUpdateAbilities();
                return null;
            });
            tp(ctx, ex, feet.getY(), ez, yaw, pitch);
            ctx.waitTicks(30);
            shot(ctx, "1978-" + f[3]);
        }
    }

    // ============================================================================================ the gate check

    /**
     * For each gate in turn: the lever is a lever and off, its gate holds exactly its count of blocks (all of its box
     * when it names no blocks), he is stood in reach and uses the lever as the player; then the lever is on, the gate's
     * blocks are gone, the rest of the box is as it was, and every later gate is still whole.
     */
    private void gates(ClientGameTestContext ctx, String c, String room, String[] gates) {
        List<BlockPos> levers = new ArrayList<>();
        List<List<BlockPos>> boxes = new ArrayList<>();
        List<Set<String>> onlys = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        int[] size = roomSize(ctx, room);
        for (String g : gates) {
            String[] f = g.split("\\|", -1);
            // The gates are written against the bundled captures; a local capture may be the room taken a quarter or
            // half turn round (Mod Only Test's Overgrown is turned 180). Use the turn that lands the lever on a lever.
            int k = 0;
            for (int t = 0; t < 4; t++) {
                if (size[0] != size[1] && (t & 1) == 1) {
                    continue;
                }
                int[] lt = turn(ints(f[0]), t, size);
                if (blockName(ctx, capW(ctx, room, lt[0], lt[1], lt[2])).equals("lever")) {
                    k = t;
                    break;
                }
            }
            if (k != 0) {
                say(c + " " + room + ": this capture is turned " + k + " quarter(s) from the bundled one");
            }
            int[] lv = turn(ints(f[0]), k, size);
            int[] a = turn(ints(f[1]), k, size);
            int[] b = turn(ints(f[2]), k, size);
            levers.add(capW(ctx, room, lv[0], lv[1], lv[2]));
            List<BlockPos> box = new ArrayList<>();
            for (int x = Math.min(a[0], b[0]); x <= Math.max(a[0], b[0]); x++) {
                for (int y = Math.min(a[1], b[1]); y <= Math.max(a[1], b[1]); y++) {
                    for (int z = Math.min(a[2], b[2]); z <= Math.max(a[2], b[2]); z++) {
                        box.add(capW(ctx, room, x, y, z));
                    }
                }
            }
            boxes.add(box);
            counts.add(Integer.parseInt(f[3]));
            onlys.add(f[4].isEmpty() ? Set.of() : Set.of(f[4].split("\\+")));
        }
        for (int i = 0; i < gates.length; i++) {
            BlockPos lever = levers.get(i);
            String leverBlock = blockName(ctx, lever);
            Map<BlockPos, String> before = states(ctx, boxes.get(i));
            int gateBefore = gateCount(before, onlys.get(i));
            say(c + " " + room + " gate " + (i + 1) + ": lever " + leverBlock + " at " + lever.toShortString()
                    + ", gate " + gateBefore + "/" + counts.get(i));
            check(leverBlock.equals("lever"), room + " gate " + (i + 1) + ": no lever at " + gates[i].split("\\|")[0]
                    + " - it is " + leverBlock);
            check(gateBefore == counts.get(i), room + " gate " + (i + 1) + " holds " + gateBefore + " block(s) before"
                    + " its lever, expected " + counts.get(i));
            check(!powered(ctx, lever), room + " lever " + (i + 1) + " is already on before the flick");
            double reach = standInReach(ctx, lever);
            ctx.runOnClient(mc -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(lever), Direction.UP, lever, false)));
            ctx.waitTicks(15);
            boolean flipped = powered(ctx, lever);
            Map<BlockPos, String> after = states(ctx, boxes.get(i));
            int gateAfter = gateCount(after, onlys.get(i));
            int changedOther = 0;
            for (Map.Entry<BlockPos, String> e : before.entrySet()) {
                boolean gateBlock = !e.getValue().equals("air")
                        && (onlys.get(i).isEmpty() || onlys.get(i).contains(e.getValue()));
                if (!gateBlock && !e.getValue().equals(after.get(e.getKey()))) {
                    changedOther++;
                }
            }
            StringBuilder others = new StringBuilder();
            for (int j = i + 1; j < gates.length; j++) {
                int s = gateCount(states(ctx, boxes.get(j)), onlys.get(j));
                others.append(" gate ").append(j + 1).append(' ').append(s).append('/').append(counts.get(j));
                check(s == counts.get(j), room + " lever " + (i + 1) + " also took " + (counts.get(j) - s)
                        + " block(s) of gate " + (j + 1));
            }
            say(c + " after lever " + (i + 1) + " (eye " + String.format(Locale.ROOT, "%.2f", reach) + " from it): powered "
                    + flipped + ", gate " + gateAfter + "/" + counts.get(i) + ", other box blocks changed "
                    + changedOther + ";" + (others.length() == 0 ? " no later gates" : others.toString()));
            check(flipped, room + " lever " + (i + 1) + " did not flip on the server");
            check(gateAfter == 0, gateAfter + " block(s) of " + room + " gate " + (i + 1) + " still standing");
            check(changedOther == 0, room + " lever " + (i + 1) + " changed " + changedOther
                    + " block(s) of its box that are not its gate");
        }
    }

    private static int gateCount(Map<BlockPos, String> states, Set<String> only) {
        int n = 0;
        for (String s : states.values()) {
            if (!s.equals("air") && (only.isEmpty() || only.contains(s))) {
                n++;
            }
        }
        return n;
    }

    /**
     * Puts him on the standable spot nearest the lever (eye within 4.45 of its block's box, solid underfoot, room for both
     * halves of him), found on the server, facing the lever. Returns the eye distance.
     */
    private static double standInReach(ClientGameTestContext ctx, BlockPos lever) {
        BlockPos spot = onServer(ctx, (level, sp) -> {
            BlockPos best = null;
            double bestD = 99;
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    for (int dy = -7; dy <= 2; dy++) {
                        BlockPos f = lever.offset(dx, dy, dz);
                        if (f.equals(lever) || f.above().equals(lever)) {
                            continue;
                        }
                        if (!level.getBlockState(f).getCollisionShape(level, f).isEmpty()
                                || !level.getBlockState(f.above()).getCollisionShape(level, f.above()).isEmpty()
                                || level.getBlockState(f.below()).getCollisionShape(level, f.below()).isEmpty()
                                || !level.getFluidState(f).isEmpty()) {
                            continue;
                        }
                        // To the lever's block BOX, the measure the server uses (Purple Flags' lever hangs at the top of
                        // a shaft: 4.4 to its box from the floor, 4.9 to its centre).
                        double d = Math.sqrt(new net.minecraft.world.phys.AABB(lever)
                                .distanceToSqr(new Vec3(f.getX() + 0.5, f.getY() + 1.62, f.getZ() + 0.5)));
                        if (d < bestD && d <= 4.45) {
                            bestD = d;
                            best = f;
                        }
                    }
                }
            }
            return best;
        });
        check(spot != null, "no standing spot in reach of the lever at " + lever.toShortString());
        double ex = spot.getX() + 0.5;
        double ey = spot.getY() + 1.62;
        double ez = spot.getZ() + 0.5;
        double dx = lever.getX() + 0.5 - ex;
        double dy = lever.getY() + 0.5 - ey;
        double dz = lever.getZ() + 0.5 - ez;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        tp(ctx, ex, spot.getY(), ez, yaw, pitch);
        ctx.waitTicks(10);
        return ctx.computeOnClient(mc -> Math.sqrt(Vec3.atCenterOf(lever).distanceToSqr(mc.player.getEyePosition())));
    }

    // ============================================================================================ helpers

    /** The capture's sizeX and sizeZ, read off RoomLibrary's room. */
    private static int[] roomSize(ClientGameTestContext ctx, String room) {
        return ctx.computeOnClient(mc -> {
            Object r = ModUnderTest.staticCall(ROOM_LIBRARY, "get", new Class<?>[]{String.class}, new Object[]{room});
            try {
                return new int[]{r.getClass().getField("sizeX").getInt(r), r.getClass().getField("sizeZ").getInt(r)};
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("no size for " + room, e);
            }
        });
    }

    /** (x, z) turned k quarter turns inside the capture, the mod's own convention: (x, z) -> (sizeZ-1-z, x). */
    private static int[] turn(int[] p, int k, int[] size) {
        int x = p[0];
        int z = p[2];
        int sx = size[0];
        int sz = size[1];
        for (int i = 0; i < k; i++) {
            int nx = sz - 1 - z;
            z = x;
            x = nx;
            int w = sx;
            sx = sz;
            sz = w;
        }
        return new int[]{x, p[1], z};
    }

    private static int[] ints(String s) {
        String[] p = s.split(",");
        return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())};
    }

    private static Map<BlockPos, String> states(ClientGameTestContext ctx, List<BlockPos> ps) {
        return onServer(ctx, (level, sp) -> {
            Map<BlockPos, String> out = new LinkedHashMap<>();
            for (BlockPos p : ps) {
                out.put(p, BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).getPath());
            }
            return out;
        });
    }

    private static boolean passable(ClientGameTestContext ctx, BlockPos p) {
        return onServer(ctx, (level, sp) -> level.getBlockState(p).getCollisionShape(level, p).isEmpty());
    }

    private static boolean powered(ClientGameTestContext ctx, BlockPos p) {
        return onServer(ctx, (level, sp) -> {
            BlockState s = level.getBlockState(p);
            return s.hasProperty(LeverBlock.POWERED) && s.getValue(LeverBlock.POWERED);
        });
    }

    /** Builds one room on its own and waits until he stands in it. */
    private static void build(ClientGameTestContext ctx, String room) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildSingleRoom",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, room})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        int landed = -1;
        for (int t = 0; t < 1800; t++) {
            boolean ok = ctx.computeOnClient(mc -> mc.player != null && mc.player.onGround()
                    && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir());
            if (ok) {
                landed = t;
                break;
            }
            ctx.waitTicks(1);
        }
        check(landed >= 0, "the client never stood in " + room);
        ctx.waitTicks(20);
        playerId = ctx.computeOnClient(mc -> mc.player.getUUID());
        say("built " + room + " (stood after " + landed + " ticks)");
    }

    private static BlockPos capW(ClientGameTestContext ctx, String room, int x, int y, int z) {
        BlockPos p = ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(SIM_LEVERS, "capturedWorld",
                new Class<?>[]{String.class, int.class, int.class, int.class}, new Object[]{room, x, y, z}));
        check(p != null, room + " is not placed");
        return p;
    }

    private static String blockName(ClientGameTestContext ctx, BlockPos p) {
        return onServer(ctx, (level, sp) -> BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).getPath());
    }

    private static void tp(ClientGameTestContext ctx, double x, double y, double z, float yaw, float pitch) {
        onServer(ctx, (level, sp) -> {
            sp.teleportTo(level, x, y, z, java.util.Set.<net.minecraft.world.entity.Relative>of(), yaw, pitch, false);
            return null;
        });
        ctx.runOnClient(mc -> {
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
        });
        ctx.waitTicks(5);
    }

    private static void shot(ClientGameTestContext ctx, String name) {
        Path taken = ctx.takeScreenshot(dev.testkit.harness.Report.fileName(name));
        Path kept = dev.testkit.harness.Report.screenshot(name, taken);
        say("screenshot " + name + ": " + kept);
    }

    @SuppressWarnings("unchecked")
    private static <T> T onServer(ClientGameTestContext ctx, BiFunction<ServerLevel, ServerPlayer, T> f) {
        AtomicReference<Object[]> box = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID id = playerId != null ? playerId : mc.player.getUUID();
            server.execute(() -> {
                try {
                    box.set(new Object[]{f.apply(server.overworld(), server.getPlayerList().getPlayer(id)), null});
                } catch (Throwable t) {
                    box.set(new Object[]{null, t});
                }
            });
        });
        ctx.waitFor(mc -> box.get() != null, 400);
        if (box.get()[1] != null) {
            throw new AssertionError("server task threw", (Throwable) box.get()[1]);
        }
        return (T) box.get()[0];
    }

    private static void teardown(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            try {
                ModUnderTest.staticCall(SIM_STATE, "leave", new Class<?>[]{}, new Object[]{});
            } catch (Throwable ignored) {
                // never let cleanup replace the verdict
            }
        });
        ctx.runOnClient(mc -> mc.execute(() -> {
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                mc.disconnectWithSavingScreen();
            }
        }));
        ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 1200);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> mc.execute(() ->
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen, 400);
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    private static void say(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}

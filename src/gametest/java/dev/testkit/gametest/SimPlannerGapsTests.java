package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Interactive Map planner's "no way" answers (mod branch planner-gaps, 2026-10-07), each judged against a ground
 * truth: breadth-first over real etherwarps cast on the SERVER's level with the walk the sim's server judges a use by,
 * landings kept to the mod's own rules (cover, blacklist, no warp on from a trap, maze or Boulder).
 *
 * <p><b>404-sim-planner-gaps</b> (pinned F7 of 98-sim-insta-clear-live): four spots on Atlas's ledge from which the
 * floor graph "proved" no way to the room's centre block while three or four warps reach it. Plans each and RUNS the
 * trip with {@code ClearExecutor.etherPath}: he must travel, end on the block in Atlas, and the sim's server must refuse
 * none of the planned warps. Fails on mod 799c5c2f ("no way ... not trying room by room").
 *
 * <p><b>404-sim-planner-cracks</b> (its own crafted floor; name it alone): the two dead spots of 131-sim-auto-clear on
 * 2026-10-07, rebuilt where they stood (Archway at clay -138,-200 rotation 90, Perch at clay -40,-200 rotation 0). Both
 * are a one-block floor in the open seam between two rooms' walls beside a doorway, walled off from the doorway: the
 * only warps out land on the dungeon's roof, which the landing rule refuses. The planner must answer "no way" there and
 * run nothing; from the doorway two blocks south the same trip must arrive.
 *
 * <p><b>404-sim-planner-sweep</b> (pinned F7): plans from every standable spot of a 2-block grid over each room's tiles
 * (seams included) to that room's centre block, one line per spot so two jars compare line by line, judges every "no
 * way" against the ground truth (fails on any the ground truth reaches) and runs a sample of the found plans.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimPlannerGapsTests implements FabricClientGameTest {

    private static final String GAPS = "404-sim-planner-gaps";
    private static final String CRACKS = "404-sim-planner-cracks";
    private static final String SWEEP = "404-sim-planner-sweep";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    private static final String EXECUTOR = "com.killer560.hub.livemap.autoclear.ClearExecutor";
    private static final String PATHFINDER = "com.killer560.hub.livemap.autoclear.EtherwarpPathfinder";
    private static final String TELEPORT_UTILS = "com.killer560.hub.livemap.autoclear.TeleportUtils";
    private static final int GRID = 11;

    /**
     * Dip, Archway (1x2, rotation 90), Dome, End, Overgrown and Perch along the top row with a normal door between each
     * pair, and the Entrance under Perch. Made with tools of the planner-gaps session (MapCode's wire form).
     */
    static final String GAPS_FLOOR_CODE =
            "MC2:CwcDRGlwB0FyY2h3YXkERG9tZQNFbmQJT3Zlcmdyb3duBVBlcmNoCEVudHJhbmNlAAEAAQAAAAIBAQAAAAMAAQAAAAQAAQAA"
            + "AAUAAQAAAAYAAAAAAAAAAAIBAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAIBAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAcA"
            + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    /** 98-sim-insta-clear-live's pinned F7 (InstaClearTests.LIVE_FLOOR_CODE). */
    static final String SWEEP_FLOOR_CODE =
            "MC2:CxUIRW50cmFuY2UJV2F0ZXJmYWxsBUZsYWdzBUZhaXJ5BU1pbmVzBUF0bGFzBUJsb29kBU1vc3N5CVF1YWQgTGF2YQRRdWl6"
            + "CEljZSBQYXRoDkRvdWJsZSBEaWFtb25kC1dhdGVyIEJvYXJkCE5ldyBUcmFwDFJlZHN0b25lIEtleQNFbmQJT3Zlcmdyb3duA0Rp"
            + "cAtQcmlzb24gQ2VsbAREb21lDlJhcmUgT3Zlcmdyb3duABEDAQAAABAAAQAAAAICAAICAAICAAICAAICAAICAAICAAAAAAAAAAAA"
            + "AAAAAQAAAAAAAAAAAAAAAAAAAAAABAAAAA4DAAAAABMAAQAAAAMDAAMDAAMDAQAAAAQAAAAAAAEDAQAAAAAAAAAAAAAAAAMDAAMD"
            + "AAMDAAAAAQAAAAAAAAAAAAwAAQAAAAgBAAAAAAMDAAMDAAMDAAAAAAUAAAUAAAUAAAAAAAAAAAgBAAAAAAAAAAAAAAAAAAAAAAUA"
            + "AAUAAAUAAA0CAQAAAAgBAQAAAAYBAAYBAAYBAQAAAAUAAAUAAAUAAAAAAAAAAAgBAAAAAAYBAAYBAAYBAAAAAAAAAAAAAAAAAAsD"
            + "AQAAAAgBAAAAAAYBAAYBAAYBAwAAAAcAAAAAABUBAAAAAAAAAAgBAAAAAAAAAAAAAQAAAAAAAAAAAAAAAQAAABIBAQAAAAgBAAAA"
            + "AAoDAQAAAAkCAQAAAA8CAQAAABQB";

    /**
     * A dead spot as the evidence logs recorded it: the room, its clay corner and rotation there, his feet and the
     * block planned to - y relative to the floor (world y less that run's sim y offset).
     */
    private record Spot(String label, String room, int clayX, int clayZ, int rotation, double fx, double fyRel,
                        double fz, int tx, int tyRel, int tz) {
    }

    private static final List<Spot> SPOTS = List.of(
            // flaky-autos-fix2-run2.log 06:20:03 (sim y offset -99): from (-168.50, -30.00, -187.50) to -154, -31, -188
            new Spot("archway-west-door", "Archway", -138, -200, 90, -168.5, 69.0, -187.5, -154, 68, -188),
            // flaky-autos-fix3-run2.log 06:30:38 (sim y offset -87): from (-40.50, -18.00, -187.50) to -30, -20, -179
            new Spot("perch-west-door", "Perch", -40, -200, 0, -40.5, 69.0, -187.5, -30, 67, -179));

    /**
     * The sweep's real gaps on the pinned F7 (mod 799c5c2f, 2026-10-07): spots on Atlas's ledge by the seam between its
     * two tile rows, from which the planner "proved" no way to the room's centre block while three or four warps reach it
     * (the sweep's breadth-first ground truth). World coordinates: the pinned floor always sits at the same height.
     */
    private static final double[][] ATLAS_GAPS = {
            {-120.5, -13.0, -74.5}, {-88.5, -13.0, -74.5}, {-120.5, -13.0, -70.5}, {-88.5, -13.0, -70.5}};
    private static final BlockPos ATLAS_TARGET = new BlockPos(-122, -18, -90);

    private static final Pattern WARM = Pattern.compile("\\[Path\\] floor graph warm: (\\d+) node");
    private static final Pattern QUICK_WARM = Pattern.compile("\\[Path\\] quick floor graph warm: (\\d+) node\\(s\\), ([0-9.]+) ms");
    private static final Pattern FULL_WARM_MS = Pattern.compile("\\[Path\\] floor graph warm: (\\d+) node\\(s\\), ([0-9.]+) ms");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank()) {
            return;
        }
        boolean gaps = !Scenario.skip(GAPS);
        boolean sweep = !Scenario.skip(SWEEP);
        boolean cracks = !Scenario.skip(CRACKS);
        if (cracks && (gaps || sweep)) {
            // Two floors in one client: the second build into the open world never warmed its graph (2026-10-07).
            System.out.println("[" + CRACKS + "] SKIPPED - runs on its own floor; name it alone (-Scenario " + CRACKS + ")");
            cracks = false;
        }
        if (!gaps && !sweep && !cracks) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(GAPS, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"));
        Object mapCfg = ctx.computeOnClient(mc -> ModUnderTest.config(LIVE_MAP_CONFIG));
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> {
            mc.options.renderDistance().set(16);
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                    "setAutoPuzzlesMasterEnabled", false);
            ModUnderTest.set(mapCfg, "setEnabled", true);
            ModUnderTest.set(mapCfg, "setInteractiveMapEnabled", false);
            ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class}, new Object[]{"gametest"});
        });
        List<String> gapFailures = new ArrayList<>();
        List<String> sweepFailures = new ArrayList<>();
        List<String> crackFailures = new ArrayList<>();
        try {
            if (cracks) {
                try {
                    buildFloor(ctx, CRACKS, GAPS_FLOOR_CODE);
                    cracksBody(ctx, crackFailures);
                } catch (Throwable t) {
                    crackFailures.add("scenario threw: " + t);
                    t.printStackTrace(System.out);
                }
            } else {
                try {
                    buildFloor(ctx, gaps ? GAPS : SWEEP, SWEEP_FLOOR_CODE);
                } catch (Throwable t) {
                    (gaps ? gapFailures : sweepFailures).add("scenario threw: " + t);
                    t.printStackTrace(System.out);
                    gaps = false;
                    sweep = false;
                }
                if (gaps) {
                    try {
                        gapsBody(ctx, gapFailures);
                    } catch (Throwable t) {
                        gapFailures.add("scenario threw: " + t);
                        t.printStackTrace(System.out);
                    }
                }
                if (sweep) {
                    try {
                        sweepBody(ctx, sweepFailures);
                    } catch (Throwable t) {
                        sweepFailures.add("scenario threw: " + t);
                        t.printStackTrace(System.out);
                    }
                }
            }
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(EXECUTOR, "cancel"));
            ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "leave"));
            ctx.runOnClient(mc -> mc.execute(() -> {
                if (mc.level != null) {
                    mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                    mc.disconnectWithSavingScreen();
                }
            }));
            ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null);
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> mc.execute(() ->
                    McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
            ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen);
        }
        List<String> all = new ArrayList<>();
        if (cracks) {
            if (crackFailures.isEmpty()) {
                System.out.println("[" + CRACKS + "] PASS");
            } else {
                System.out.println("[" + CRACKS + "] FAIL - " + String.join("; ", crackFailures));
                all.addAll(crackFailures);
            }
        }
        if (gaps) {
            if (gapFailures.isEmpty()) {
                System.out.println("[" + GAPS + "] PASS");
            } else {
                System.out.println("[" + GAPS + "] FAIL - " + String.join("; ", gapFailures));
                all.addAll(gapFailures);
            }
        }
        if (sweep) {
            if (sweepFailures.isEmpty()) {
                System.out.println("[" + SWEEP + "] PASS");
            } else {
                System.out.println("[" + SWEEP + "] FAIL - " + String.join("; ", sweepFailures));
                all.addAll(sweepFailures);
            }
        }
        if (!all.isEmpty()) {
            throw new AssertionError(all.size() + " problem(s): " + String.join("; ", all));
        }
    }

    // ------------------------------------------------------------------------------------------------ floors

    /**
     * Builds {@code code} (a fresh sim world the first time, into the open one after), waits for the floor's chunks,
     * puts the Aspect of the Void in his hand, starts the run (the entrance gate opens) and switches the Interactive
     * Map on until the full floor graph reports warm.
     */
    private static void buildFloor(ClientGameTestContext ctx, String name, String code) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(LIVE_MAP_CONFIG), "setInteractiveMapEnabled", false));
        boolean open = ctx.computeOnClient(mc -> mc.getSingleplayerServer() != null);
        ctx.runOnClient(mc -> mc.execute(() -> {
            ModUnderTest.staticCall(SIM_STATE, "setFloorLabel", new Class<?>[]{String.class}, new Object[]{"F7"});
            if (open) {
                ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "build",
                        new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, code});
            } else {
                java.util.function.Consumer<Minecraft> build = c -> ModUnderTest.staticCall(
                        "com.killer560.hub.roomsim.SimBuilder", "build", new Class<?>[]{Minecraft.class, String.class},
                        new Object[]{c, code});
                ModUnderTest.staticCall("com.killer560.hub.roomsim.SimWorld", "open",
                        new Class<?>[]{Minecraft.class, String.class, java.util.function.Consumer.class, String.class},
                        new Object[]{mc, code, build, "Generating Floor 7"});
            }
        }));
        ctx.waitFor(mc -> mc.level != null);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitTicks(60);
        boolean ready = false;
        for (int i = 0; i < 180 && !ready; i++) {
            ready = ctx.computeOnClient(mc -> mc.level != null && mc.player != null
                    && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir());
            if (!ready) {
                ctx.waitTicks(10);
            }
        }
        println(name, "client floor ready: " + ready);
        awaitFloorChunks(ctx, name);
        giveAotv(ctx, name);
        long markGo = LogTap.mark();
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
        for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomsim.SimRun", "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(5);
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(LIVE_MAP_CONFIG), "setInteractiveMapEnabled", true));
        String warm = null;
        String quick = null;
        for (int i = 0; i < 1800 && warm == null; i++) {
            ctx.waitTicks(1);
            for (String l : LogTap.since(markGo)) {
                Matcher q = QUICK_WARM.matcher(l);
                if (q.find() && Integer.parseInt(q.group(1)) >= 1000) {
                    quick = l.replaceAll("^.*\\[Path\\]", "[Path]");
                }
                Matcher m = WARM.matcher(l);
                if (m.find() && Integer.parseInt(m.group(1)) >= 3000) {
                    warm = l.replaceAll("^.*\\[Path\\]", "[Path]");
                }
            }
        }
        println(name, "quick graph: " + quick);
        println(name, "full graph: " + (warm == null ? "NOT warm after 90 s" : warm));
        if (warm == null) {
            throw new AssertionError("the floor graph never reported warm (3,000+ nodes) in 90 s");
        }
    }

    private static void awaitFloorChunks(ClientGameTestContext ctx, String name) {
        int missing = -1;
        int waited = 0;
        for (; waited < 1800; waited += 10) {
            missing = ctx.computeOnClient(mc -> {
                BlockPos first = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                        new Object[]{0});
                BlockPos last = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                        new Object[]{GRID * GRID - 1});
                int n = 0;
                for (int cx = (first.getX() - 16) >> 4; cx <= (last.getX() + 16) >> 4; cx++) {
                    for (int cz = (first.getZ() - 16) >> 4; cz <= (last.getZ() + 16) >> 4; cz++) {
                        if (!mc.level.hasChunk(cx, cz)) {
                            n++;
                        }
                    }
                }
                return n;
            });
            if (missing == 0) {
                break;
            }
            ctx.waitTicks(10);
        }
        println(name, "floor chunks on the client: " + (missing == 0 ? "all, after " + waited / 20.0 + " s"
                : missing + " still missing after 90 s"));
    }

    private static void giveAotv(ClientGameTestContext ctx, String name) {
        AtomicReference<Boolean> given = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp == null) {
                    given.set(false);
                    return;
                }
                var inv = sp.getInventory();
                for (int i = 0; i < 36; i++) {
                    inv.setItem(i, ItemStack.EMPTY);
                }
                inv.setItem(0, (ItemStack) ModUnderTest.staticCall(SIM_ITEMS, "build", new Class<?>[]{String.class},
                        new Object[]{"ASPECT_OF_THE_VOID"}));
                given.set(true);
            });
        });
        ctx.waitFor(mc -> given.get() != null, 200);
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        ctx.waitTicks(10);
        String held = ctx.computeOnClient(mc -> mc.player.getMainHandItem().getHoverName().getString());
        println(name, "in hand: " + held);
        if (!held.contains("Aspect of the Void")) {
            throw new AssertionError("the Aspect of the Void never reached his hand (" + held + ")");
        }
    }

    // ------------------------------------------------------------------------------------------------ gaps

    /**
     * The two evidence spots, rebuilt. Each is judged against the ground truth (bfsOracle): when no chain of real
     * etherwarps reaches the block, the planner must say so and run nothing (no move, no refused warp); when one does,
     * the trip must arrive. Then a control from the doorway beside the spot (2 blocks south, where he would stand on the
     * way in): the trip must arrive with nothing refused.
     */
    private static void cracksBody(ClientGameTestContext ctx, List<String> failures) {
        int off = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(LAYOUT, "simYOffset"));
        println(CRACKS, "sim y offset " + off);
        for (Spot s : SPOTS) {
            // The room where it stood in the evidence run, or this is not a reproduction.
            String clay = ctx.computeOnClient(mc -> {
                Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
                int n = (Integer) ModUnderTest.call(layout, "roomCount", new Class<?>[]{}, new Object[]{});
                for (int r = 0; r < n; r++) {
                    if (s.room.equals(ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r}))) {
                        int[] c = (int[]) ModUnderTest.call(layout, "clayRotation", new Class<?>[]{int.class},
                                new Object[]{r});
                        return c == null ? "no clay" : c[0] + "," + c[1] + " rotation " + c[2];
                    }
                }
                return "not on the map";
            });
            String want = s.clayX + "," + s.clayZ + " rotation " + s.rotation;
            println(CRACKS, s.label + ": " + s.room + " at clay " + clay + " (evidence run: " + want + ")");
            if (!want.equals(clay)) {
                failures.add(s.label + ": " + s.room + " is at clay " + clay + ", not " + want + " - not a reproduction");
                continue;
            }
            Vec3 feet = new Vec3(s.fx, s.fyRel + off, s.fz);
            BlockPos target = new BlockPos(s.tx, s.tyRel + off, s.tz);
            if (!place(ctx, feet)) {
                failures.add(s.label + ": could not put him on " + fmt(feet));
                continue;
            }
            dumpAround(ctx, CRACKS, s.label, feet, target);
            println(CRACKS, s.label + ": first warps - " + oracle(ctx, feet, target, s.room, false, 0.5f));
            String truth = bfsOracle(ctx, feet, target);
            println(CRACKS, s.label + ": ground truth - " + truth);
            String plan = planOnPlanner(ctx, feet, target);
            println(CRACKS, s.label + ": planner from " + fmt(feet) + " to " + target.toShortString() + ": " + plan);
            List<String> tripFailures = new ArrayList<>();
            String trip = runTrip(ctx, CRACKS, s.label, feet, target, s.room, tripFailures);
            println(CRACKS, s.label + ": " + trip);
            if (truth.startsWith("REACHABLE")) {
                failures.addAll(tripFailures);
            } else {
                if (!plan.startsWith("none")) {
                    failures.add(s.label + ": the planner found " + plan + " where no chain of warps reaches the block ("
                            + truth + ")");
                }
                for (String f : tripFailures) {
                    if (f.contains("refused")) {
                        failures.add(f);
                    }
                }
            }
            // The control: the doorway floor two blocks south, on the door's own line.
            Vec3 door = new Vec3(s.fx, s.fyRel + off, s.fz + 2.0);
            if (!place(ctx, door)) {
                failures.add(s.label + ": could not put him on the doorway " + fmt(door));
                continue;
            }
            String doorTrip = runTrip(ctx, CRACKS, s.label + "-doorway", door, target, s.room, failures);
            println(CRACKS, s.label + "-doorway: " + doorTrip);
        }
    }

    /**
     * The sweep's real gaps: from each Atlas ledge spot, plan to the room's centre block and run the trip; it must
     * arrive with nothing refused. The ground truth is printed beside it.
     */
    private static void gapsBody(ClientGameTestContext ctx, List<String> failures) {
        int off = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(LAYOUT, "simYOffset"));
        println(GAPS, "sim y offset " + off);
        for (double[] f : ATLAS_GAPS) {
            Vec3 feet = new Vec3(f[0], f[1], f[2]);
            String label = String.format(Locale.US, "atlas-%.0f-%.0f", f[0], f[2]);
            boolean standable = ctx.computeOnClient(mc -> {
                BlockPos below = BlockPos.containing(feet).below();
                return !mc.level.getBlockState(below).getCollisionShape(mc.level, below).isEmpty();
            });
            if (!standable || !place(ctx, feet)) {
                failures.add(label + ": could not put him on " + fmt(feet) + " - the pinned floor is not where it was");
                continue;
            }
            String truth = bfsOracle(ctx, feet, ATLAS_TARGET);
            println(GAPS, label + ": ground truth - " + truth);
            Matcher via = Pattern.compile("(-?[0-9]+),(-?[0-9]+),(-?[0-9]+)").matcher(truth.replaceAll("^.*via ", ""));
            while (truth.contains(" via ") && via.find()) {
                BlockPos b = new BlockPos(Integer.parseInt(via.group(1)), Integer.parseInt(via.group(2)),
                        Integer.parseInt(via.group(3)));
                println(GAPS, label + ":   via " + b.toShortString() + ": " + ctx.computeOnClient(mc -> {
                    StringBuilder sb = new StringBuilder();
                    for (int dy = 0; dy <= 2; dy++) {
                        BlockPos q = b.above(dy);
                        var st = mc.level.getBlockState(q);
                        sb.append(dy == 0 ? "" : " | +").append(dy == 0 ? "" : dy + " ").append(st)
                                .append(" top ").append(String.format(Locale.US, "%.2f",
                                        st.getCollisionShape(mc.level, q).isEmpty() ? 0.0
                                                : st.getCollisionShape(mc.level, q).max(Direction.Axis.Y)));
                    }
                    return sb.toString();
                }));
            }
            println(GAPS, label + ": the single fan search (findPath, no floor graph): " + fanSearch(ctx, feet, ATLAS_TARGET));
            if (truth.contains(" via ")) {
                List<BlockPos> chain = new ArrayList<>();
                Matcher m2 = Pattern.compile("(-?[0-9]+),(-?[0-9]+),(-?[0-9]+)").matcher(truth.replaceAll("^.*via ", ""));
                while (m2.find()) {
                    chain.add(new BlockPos(Integer.parseInt(m2.group(1)), Integer.parseInt(m2.group(2)),
                            Integer.parseInt(m2.group(3))));
                }
                chain.add(ATLAS_TARGET);
                println(GAPS, label + ": the planner's own aim along the ground truth's chain: "
                        + ctx.computeOnClient(mc -> hopCheck(mc, feet, chain)));
            }
            String plan = planOnPlanner(ctx, feet, ATLAS_TARGET);
            println(GAPS, label + ": planner from " + fmt(feet) + " to " + ATLAS_TARGET.toShortString() + ": " + plan);
            String trip = runTrip(ctx, GAPS, label, feet, ATLAS_TARGET, "Atlas", failures);
            println(GAPS, label + ": " + trip);
        }
    }

    /**
     * Ground truth for "could a player etherwarp out of here": every 0.5 degree of yaw and pitch from his sneaking eye,
     * cast on the SERVER's level with the walk the sim's server judges a real etherwarp by
     * ({@code TeleportUtils.traverseVoxels(level, ..., true)}, 61 blocks). Says how many distinct blocks a first warp can
     * land on, how many of those are in {@code room} within 4 blocks of the target's height, whether the target itself
     * is one, and (with {@code twoHops}) whether any second warp from a first landing (every 2 degrees) lands on it.
     */
    private static String oracle(ClientGameTestContext ctx, Vec3 feet, BlockPos target, String room, boolean twoHops,
                                 float step) {
        AtomicReference<String> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                try {
                    var level = server.overworld();
                    java.util.Set<BlockPos> first = casts(level, feet, step);
                    int inRoom = 0;
                    for (BlockPos b : first) {
                        int r = (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class,
                                double.class}, new Object[]{b.getX() + 0.5, b.getZ() + 0.5});
                        if (room.equals(ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r}))
                                && Math.abs(b.getY() - target.getY()) <= 4) {
                            inRoom++;
                        }
                    }
                    String two = "";
                    if (twoHops && !first.contains(target)) {
                        int tried = 0;
                        BlockPos via = null;
                        for (BlockPos b : first) {
                            if (tried++ >= 300) {
                                break;
                            }
                            if (casts(level, new Vec3(b.getX() + 0.5, b.getY() + 1.05, b.getZ() + 0.5), 2f)
                                    .contains(target)) {
                                via = b;
                                break;
                            }
                        }
                        two = via == null ? "; no second warp from " + Math.min(tried, first.size())
                                + " first landing(s) lands on it" : "; two warps reach it, via " + via.toShortString();
                    }
                    StringBuilder sample = new StringBuilder();
                    int k = 0;
                    for (BlockPos b : first) {
                        if (k++ < 12) {
                            sample.append(' ').append(b.toShortString().replace(" ", ""));
                        }
                    }
                    out.set(first.size() + " first landing(s) (" + inRoom + " in " + room + " near the target's height)"
                            + ", target " + (first.contains(target) ? "IS" : "is not") + " one" + two + "; e.g."
                            + sample);
                } catch (RuntimeException | AssertionError e) {
                    out.set("threw " + e);
                }
            });
        });
        for (int i = 0; i < 2400 && out.get() == null; i++) {
            ctx.waitTicks(1);
        }
        return out.get() == null ? "no answer in 120 s" : out.get();
    }

    /** Server thread: the distinct blocks an etherwarp from {@code feet} (sneaking eye) lands on, every step degrees. */
    private static java.util.Set<BlockPos> casts(net.minecraft.server.level.ServerLevel level, Vec3 feet, float step) {
        java.util.Set<BlockPos> out = new java.util.HashSet<>();
        double ey = feet.y + 1.27;
        for (float yaw = -180f; yaw < 180f; yaw += step) {
            for (float pitch = -89.5f; pitch <= 89.5f; pitch += step) {
                Vec3 look = (Vec3) ModUnderTest.staticCall(TELEPORT_UTILS, "getLook",
                        new Class<?>[]{float.class, float.class}, new Object[]{yaw, pitch});
                Object r = ModUnderTest.staticCall(TELEPORT_UTILS, "traverseVoxels", new Class<?>[]{Level.class,
                        double.class, double.class, double.class, double.class, double.class, double.class,
                        boolean.class}, new Object[]{level, feet.x, ey, feet.z, feet.x + look.x * 61.0,
                        ey + look.y * 61.0, feet.z + look.z * 61.0, true});
                Object ok = ModUnderTest.call(r, "succeeded", new Class<?>[]{}, new Object[]{});
                if (Boolean.TRUE.equals(ok)) {
                    out.add((BlockPos) ModUnderTest.call(r, "pos", new Class<?>[]{}, new Object[]{}));
                }
            }
        }
        return out;
    }


    /** The mod's dead-end rooms (EtherwarpPathfinder.deadEnds by name): a trap, a maze, Boulder. */
    private static boolean isDeadEnd(Object layout, double x, double z) {
        int r = (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class, double.class},
                new Object[]{x, z});
        String n = (String) ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r});
        return n != null && (n.contains("Trap") || n.contains("Maze") || n.contains("Boulder"));
    }

    private static java.lang.reflect.Method lookM;
    private static java.lang.reflect.Method walkM;
    private static java.lang.reflect.Method okM;
    private static java.lang.reflect.Method posM;

    /**
     * Ground truth for a sweep "no way": breadth-first over etherwarps cast on the SERVER's level every 3 degrees of
     * yaw and pitch from each landing (sneaking eye at landing top + 1.05 + 1.27), landings kept to the mod's cover rule
     * (above floor + 6 there must be a non-air block within 64 over it), up to 4 warps and 250 landings expanded.
     * @return "REACHABLE in N warp(s) via ..." / "sealed: ..." (the whole reachable set expanded, no target) /
     *         "unsure: ..." (capped)
     */
    private static String bfsOracle(ClientGameTestContext ctx, Vec3 feet, BlockPos target) {
        AtomicReference<String> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            int floor = 69 + (Integer) ModUnderTest.staticCall(LAYOUT, "simYOffset");
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            java.util.function.Predicate<BlockPos> deadEnd = b -> isDeadEnd(layout, b.getX() + 0.5, b.getZ() + 0.5);
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                try {
                    var level = server.overworld();
                    java.util.Map<BlockPos, BlockPos> parent = new java.util.HashMap<>();
                    List<BlockPos> frontier = new ArrayList<>();
                    for (BlockPos b : casts3(level, feet, floor)) {
                        parent.put(b, null);
                        frontier.add(b);
                    }
                    int expanded = 0;
                    int depth = 1;
                    while (!frontier.isEmpty()) {
                        if (parent.containsKey(target)) {
                            StringBuilder via = new StringBuilder();
                            for (BlockPos b = parent.get(target); b != null; b = parent.get(b)) {
                                via.insert(0, b.toShortString().replace(" ", "") + " ");
                            }
                            out.set("REACHABLE in " + depth + " warp(s) via " + via.toString().trim());
                            return;
                        }
                        if (depth >= 4 || expanded >= 250) {
                            out.set("unsure: " + parent.size() + " landing(s) within " + depth + " warp(s), " + expanded
                                    + " expanded, target not among them");
                            return;
                        }
                        List<BlockPos> next = new ArrayList<>();
                        for (BlockPos f : frontier) {
                            if (deadEnd.test(f)) {
                                continue;   // the mod never warps on from a trap, maze or Boulder (deadEnds)
                            }
                            if (expanded++ >= 250) {
                                break;
                            }
                            for (BlockPos b : casts3(level, new Vec3(f.getX() + 0.5, f.getY() + 1.05, f.getZ() + 0.5),
                                    floor)) {
                                if (!parent.containsKey(b)) {
                                    parent.put(b, f);
                                    next.add(b);
                                }
                            }
                        }
                        frontier = next;
                        depth++;
                    }
                    out.set(parent.containsKey(target) ? "REACHABLE (last ring)" : "sealed: every one of "
                            + parent.size() + " landing(s) reachable from here expanded, target not among them");
                } catch (Exception | AssertionError e) {
                    out.set("unsure: threw " + e);
                }
            });
        });
        for (int i = 0; i < 6000 && out.get() == null; i++) {
            ctx.waitTicks(1);
        }
        return out.get() == null ? "unsure: no answer in 300 s" : out.get();
    }

    /** The mod's landing blacklist (LevelEtherGrid.blackListed): never a landing unless it is the goal itself. */
    private static boolean blackListed(net.minecraft.world.level.block.state.BlockState state) {
        var block = state.getBlock();
        boolean bottomSlab = block instanceof net.minecraft.world.level.block.SlabBlock
                && state.hasProperty(net.minecraft.world.level.block.SlabBlock.TYPE)
                && state.getValue(net.minecraft.world.level.block.SlabBlock.TYPE)
                == net.minecraft.world.level.block.state.properties.SlabType.BOTTOM;
        return bottomSlab || block instanceof net.minecraft.world.level.block.CarpetBlock
                || block instanceof net.minecraft.world.level.block.WallBlock
                || block instanceof net.minecraft.world.level.block.FenceBlock
                || block instanceof net.minecraft.world.level.block.FenceGateBlock
                || block instanceof net.minecraft.world.level.block.HopperBlock
                || block instanceof net.minecraft.world.level.block.CauldronBlock
                || block instanceof net.minecraft.world.level.block.BannerBlock;
    }

    /** Server thread: covered landings of an etherwarp from {@code feet} every 3 degrees. */
    private static java.util.Set<BlockPos> casts3(net.minecraft.server.level.ServerLevel level, Vec3 feet, int floor)
            throws Exception {
        if (lookM == null) {
            Class<?> tu = Class.forName(TELEPORT_UTILS);
            lookM = tu.getMethod("getLook", float.class, float.class);
            walkM = tu.getMethod("traverseVoxels", Level.class, double.class, double.class, double.class, double.class,
                    double.class, double.class, boolean.class);
            Class<?> rr = Class.forName(TELEPORT_UTILS + "$RaycastResult");
            okM = rr.getMethod("succeeded");
            posM = rr.getMethod("pos");
        }
        java.util.Set<BlockPos> out = new java.util.HashSet<>();
        double ey = feet.y + 1.27;
        for (float yaw = -180f; yaw < 180f; yaw += 3f) {
            for (float pitch = -88.5f; pitch <= 88.5f; pitch += 3f) {
                Vec3 look = (Vec3) lookM.invoke(null, yaw, pitch);
                Object r = walkM.invoke(null, level, feet.x, ey, feet.z, feet.x + look.x * 61.0, ey + look.y * 61.0,
                        feet.z + look.z * 61.0, true);
                if (!(Boolean) okM.invoke(r)) {
                    continue;
                }
                BlockPos b = (BlockPos) posM.invoke(r);
                if (blackListed(level.getBlockState(b))) {
                    continue;
                }
                if (b.getY() > floor + 6) {
                    boolean covered = false;
                    for (int y = b.getY() + 3; y <= Math.min(level.getMaxY(), b.getY() + 64) && !covered; y++) {
                        covered = !level.getBlockState(new BlockPos(b.getX(), y, b.getZ())).isAir();
                    }
                    if (!covered) {
                        continue;
                    }
                }
                out.add(b);
            }
        }
        return out;
    }

    /** Server teleport onto {@code feet}, then waits until the client stands there on the ground. */
    private static boolean place(ClientGameTestContext ctx, Vec3 feet) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID me = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(me);
                if (sp != null) {
                    sp.teleportTo(server.overworld(), feet.x, feet.y, feet.z,
                            java.util.Set.of(net.minecraft.world.entity.Relative.Y_ROT,
                                    net.minecraft.world.entity.Relative.X_ROT), 0f, 0f, false);
                }
            });
        });
        for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> mc.player.onGround()
                && mc.player.position().distanceTo(feet) < 0.1); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(10);
        return ctx.computeOnClient(mc -> mc.player.onGround() && mc.player.position().distanceTo(feet) < 0.1);
    }

    /** The blocks around his feet, layer by layer: '#' solid, '.' air, '@' him, 'T' the line towards the target. */
    private static void dumpAround(ClientGameTestContext ctx, String name, String label, Vec3 feet, BlockPos target) {
        List<String> lines = ctx.computeOnClient(mc -> {
            List<String> out = new ArrayList<>();
            int fx = (int) Math.floor(feet.x);
            int fy = (int) Math.floor(feet.y);
            int fz = (int) Math.floor(feet.z);
            for (int dy = 3; dy >= -1; dy--) {
                out.add("y " + (fy + dy) + " (feet " + (dy >= 0 ? "+" : "") + dy + "), x " + (fx - 4) + ".." + (fx + 4)
                        + " across, z " + (fz - 4) + ".." + (fz + 4) + " down");
                for (int dz = -4; dz <= 4; dz++) {
                    StringBuilder row = new StringBuilder("   ");
                    for (int dx = -4; dx <= 4; dx++) {
                        BlockPos p = new BlockPos(fx + dx, fy + dy, fz + dz);
                        boolean solid = !mc.level.getBlockState(p).getCollisionShape(mc.level, p).isEmpty();
                        row.append(dx == 0 && dz == 0 && (dy == 0 || dy == 1) ? '@' : solid ? '#' : '.');
                    }
                    out.add(row.toString());
                }
            }
            return out;
        });
        for (String l : lines) {
            println(name, "  " + label + " " + l);
        }
    }


    /**
     * Client thread: each hop of {@code chain} from {@code feet} through the mod's own EtherSearch over its own
     * LevelEtherGrid - whether the block is etherwarpable to it, whether {@code aim} and {@code aimFirm} find a hop.
     */
    private static String hopCheck(Minecraft mc, Vec3 feet, List<BlockPos> chain) {
        try {
            Class<?> gridC = Class.forName("com.killer560.hub.livemap.autoclear.LevelEtherGrid");
            var ctor = gridC.getDeclaredConstructor(Level.class);
            ctor.setAccessible(true);
            Object grid = ctor.newInstance(mc.level);
            Class<?> searchC = Class.forName("com.killer560.hub.livemap.autoclear.EtherSearch");
            Class<?> gridI = Class.forName("com.killer560.hub.livemap.autoclear.EtherSearch$Grid");
            Object search = searchC.getConstructor(gridI).newInstance(grid);
            var ew = searchC.getMethod("etherwarpable", int.class, int.class, int.class);
            var aim = searchC.getMethod("aim", double.class, double.class, double.class, int.class, int.class,
                    int.class, double.class);
            var firm = searchC.getMethod("aimFirm", double.class, double.class, double.class, int.class, int.class,
                    int.class, double.class);
            var flags = gridC.getMethod("flags", int.class, int.class, int.class);
            StringBuilder sb = new StringBuilder();
            double ex = feet.x;
            double ey = feet.y + 1.27;
            double ez = feet.z;
            for (BlockPos b : chain) {
                sb.append(String.format(Locale.US, " [eye %.2f %.2f %.2f -> %s: etherwarpable %s, flags %s/%s/%s, aim %s,"
                                + " firm %s]", ex, ey, ez, b.toShortString(),
                        ew.invoke(search, b.getX(), b.getY(), b.getZ()),
                        flags.invoke(grid, b.getX(), b.getY(), b.getZ()),
                        flags.invoke(grid, b.getX(), b.getY() + 1, b.getZ()),
                        flags.invoke(grid, b.getX(), b.getY() + 2, b.getZ()),
                        aim.invoke(search, ex, ey, ez, b.getX(), b.getY(), b.getZ(), 56.0),
                        firm.invoke(search, ex, ey, ez, b.getX(), b.getY(), b.getZ(), 56.0)));
                ex = b.getX() + 0.5;
                ey = b.getY() + 1.05 + 1.27;
                ez = b.getZ() + 0.5;
            }
            return sb.toString();
        } catch (Exception e) {
            return "threw " + e;
        }
    }

    /** EtherwarpPathfinder.findPath (one A* over the yaw/pitch fan, no graph) from feet to target, for comparison. */
    private static String fanSearch(ClientGameTestContext ctx, Vec3 feet, BlockPos target) {
        AtomicReference<String> result = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            Object cfg = ModUnderTest.staticCall(EXECUTOR, "pathConfig");
            double range = (Double) ModUnderTest.staticCall(EXECUTOR, "hopRange");
            Runnable task = () -> {
                long t0 = System.nanoTime();
                try {
                    Object path = ModUnderTest.staticCall(PATHFINDER, "findPath", new Class<?>[]{Vec3.class,
                                    BlockPos.class, cfg.getClass(), double.class, boolean.class, layout.getClass()},
                            new Object[]{feet, target, cfg, range, false, layout});
                    result.set(String.format(Locale.US, "%s (%.2f ms)", path == null ? "none"
                            : ((List<?>) path).size() + " warp(s)", (System.nanoTime() - t0) / 1e6));
                } catch (RuntimeException | AssertionError e) {
                    result.set("threw " + e);
                }
            };
            ModUnderTest.staticCall(EXECUTOR, "onPlanner", new Class<?>[]{Runnable.class}, new Object[]{task});
        });
        for (int i = 0; i < 600 && result.get() == null; i++) {
            ctx.waitTicks(1);
        }
        return result.get() == null ? "no answer in 30 s" : result.get();
    }

    /** The planner's answer from {@code feet} to {@code target}, as Auto Clear asks it (on the planner thread). */
    private static String planOnPlanner(ClientGameTestContext ctx, Vec3 feet, BlockPos target) {
        AtomicReference<String> result = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            Object cfg = ModUnderTest.staticCall(EXECUTOR, "pathConfig");
            double range = (Double) ModUnderTest.staticCall(EXECUTOR, "hopRange");
            Runnable task = () -> {
                long t0 = System.nanoTime();
                try {
                    Object path = ModUnderTest.staticCall(PATHFINDER, "findDungeonPath",
                            new Class<?>[]{Vec3.class, BlockPos.class, cfg.getClass(), double.class, layout.getClass()},
                            new Object[]{feet, target, cfg, range, layout});
                    double ms = (System.nanoTime() - t0) / 1e6;
                    result.set(path == null ? String.format(Locale.US, "none (%.2f ms)", ms)
                            : String.format(Locale.US, "%d warp(s) (%.2f ms)", ((List<?>) path).size(), ms));
                } catch (RuntimeException | AssertionError e) {
                    result.set("threw " + e);
                }
            };
            ModUnderTest.staticCall(EXECUTOR, "onPlanner", new Class<?>[]{Runnable.class}, new Object[]{task});
        });
        for (int i = 0; i < 600 && result.get() == null; i++) {
            ctx.waitTicks(1);
        }
        return result.get() == null ? "no answer in 30 s" : result.get();
    }

    /**
     * Runs the trip with the Interactive Map's executor (what Auto Clear does) and judges it: he moved, he ended on
     * the target block in the target room, the server refused nothing. Failures go into {@code failures}.
     */
    private static String runTrip(ClientGameTestContext ctx, String name, String label, Vec3 feet, BlockPos target,
                                  String room, List<String> failures) {
        long mark = LogTap.mark();
        ctx.runOnClient(mc -> ModUnderTest.staticCall(EXECUTOR, "etherPath", new Class<?>[]{BlockPos.class,
                Runnable.class}, new Object[]{target, (Runnable) () -> { }}));
        boolean busySeen = false;
        int ticks = 0;
        for (; ticks < 400; ticks++) {
            ctx.waitTicks(1);
            boolean busy = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isBusy"));
            busySeen |= busy;
            if (busySeen && !busy) {
                break;
            }
        }
        ctx.waitTicks(10);
        Vec3 end = ctx.computeOnClient(mc -> mc.player.position());
        String endRoom = ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            int r = (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class, double.class},
                    new Object[]{end.x, end.z});
            return (String) ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r});
        });
        int refused = 0;
        List<String> paths = new ArrayList<>();
        for (String l : LogTap.since(mark)) {
            refused += l.contains("no etherwarp target there") ? 1 : 0;
            if (l.contains("[Path]") && (l.contains("warp(s)") || l.contains("no way") || l.contains("Failed"))) {
                paths.add(l.replaceAll("^.*?\\[Path\\]", "[Path]"));
            }
            if (l.contains("[Interactive Map] Failed")) {
                paths.add("[Interactive Map] Failed");
            }
        }
        for (String p : paths) {
            println(name, "    " + label + " log: " + (p.length() > 400 ? p.substring(0, 400) + "..." : p));
        }
        double travelled = feet.distanceTo(end);
        BlockPos stoodOn = BlockPos.containing(end.x, end.y - 0.2, end.z);
        double fromTarget = new Vec3(target.getX() + 0.5, target.getY() + 1.0, target.getZ() + 0.5).distanceTo(end);
        String summary = String.format(Locale.US, "trip: busy seen %s, %d tick(s), travelled %.1f blocks, ended %s on %s"
                        + " in %s, %.2f from the target's top, %d refusal(s)", busySeen, ticks, travelled, fmt(end),
                stoodOn.toShortString(), endRoom, fromTarget, refused);
        if (!busySeen) {
            failures.add(label + ": the executor never took the trip (" + String.join(" | ", paths) + ")");
        }
        if (travelled < 5.0) {
            failures.add(String.format(Locale.US, "%s: travelled %.1f blocks - no trip happened", label, travelled));
        }
        if (fromTarget > 1.5) {
            failures.add(String.format(Locale.US, "%s: ended %.1f blocks from the target block's top (%s)", label,
                    fromTarget, stoodOn.toShortString()));
        }
        if (!room.equals(endRoom)) {
            failures.add(label + ": ended in " + endRoom + ", not " + room);
        }
        if (refused > 0) {
            failures.add(label + ": the server refused " + refused + " planned warp(s) (\"no etherwarp target there\")");
        }
        return summary;
    }

    // ------------------------------------------------------------------------------------------------ sweep

    /** A start spot of the sweep: feet, the room's index and the room's centre block. */
    private record Start(int room, String roomName, Vec3 feet, BlockPos target) {
    }

    private static void sweepBody(ClientGameTestContext ctx, List<String> failures) {
        List<Start> starts = ctx.computeOnClient(SimPlannerGapsTests::sweepStarts);
        println(SWEEP, starts.size() + " start spot(s)");
        if (starts.size() < 200) {
            failures.add("only " + starts.size() + " start spot(s) - the floor is not on the client");
            return;
        }
        // One planner task per room: every spot, timed one by one.
        List<String> results = new ArrayList<>();
        int noWay = 0;
        int found = 0;
        long warps = 0;
        double totalMs = 0;
        double maxMs = 0;
        List<Start> foundStarts = new ArrayList<>();
        int i = 0;
        while (i < starts.size()) {
            int j = Math.min(starts.size(), i + 40);
            List<Start> batch = starts.subList(i, j);
            AtomicReference<List<Object[]>> out = new AtomicReference<>();
            ctx.runOnClient(mc -> {
                Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
                Object cfg = ModUnderTest.staticCall(EXECUTOR, "pathConfig");
                double range = (Double) ModUnderTest.staticCall(EXECUTOR, "hopRange");
                Runnable task = () -> {
                    List<Object[]> res = new ArrayList<>();
                    for (Start s : batch) {
                        long t0 = System.nanoTime();
                        Object path;
                        try {
                            path = ModUnderTest.staticCall(PATHFINDER, "findDungeonPath",
                                    new Class<?>[]{Vec3.class, BlockPos.class, cfg.getClass(), double.class,
                                            layout.getClass()}, new Object[]{s.feet, s.target, cfg, range, layout});
                        } catch (RuntimeException | AssertionError e) {
                            path = null;
                        }
                        res.add(new Object[]{path == null ? -1 : ((List<?>) path).size(), (System.nanoTime() - t0) / 1e6});
                    }
                    out.set(res);
                };
                ModUnderTest.staticCall(EXECUTOR, "onPlanner", new Class<?>[]{Runnable.class}, new Object[]{task});
            });
            for (int k = 0; k < 2400 && out.get() == null; k++) {
                ctx.waitTicks(1);
            }
            if (out.get() == null) {
                failures.add("the planner did not answer a batch of " + batch.size() + " in 120 s");
                return;
            }
            for (int k = 0; k < batch.size(); k++) {
                Start s = batch.get(k);
                int n = (Integer) out.get().get(k)[0];
                double ms = (Double) out.get().get(k)[1];
                totalMs += ms;
                maxMs = Math.max(maxMs, ms);
                if (n < 0) {
                    noWay++;
                } else {
                    found++;
                    warps += n;
                    foundStarts.add(s);
                }
                results.add(String.format(Locale.US, "spot %s %.1f %.1f %.1f -> %s (%.2f ms)", s.roomName, s.feet.x,
                        s.feet.y, s.feet.z, n < 0 ? "none" : n + " warp(s)", ms));
            }
            i = j;
        }
        for (String r : results) {
            println(SWEEP, r);
        }
        println(SWEEP, String.format(Locale.US, "summary: %d spot(s), %d no way, %d found with %d warp(s) in all; planner"
                + " %.0f ms in all, mean %.2f ms, max %.1f ms", starts.size(), noWay, found, warps, totalMs,
                totalMs / starts.size(), maxMs));
        // Each "no way" against the ground truth (bfsOracle): warps cast on the server's level by the walk the sim's
        // server judges an etherwarp with, landings kept to the mod's own cover rule, up to four warps. "sealed" means
        // no chain of warps reaches the block; "REACHABLE" means the planner missed a real path.
        int sealed = 0;
        int reachable = 0;
        int unsure = 0;
        for (int k = 0; k < results.size(); k++) {
            if (!results.get(k).contains("-> none")) {
                continue;
            }
            Start s = starts.get(k);
            boolean dead = ctx.computeOnClient(mc -> isDeadEnd(ModUnderTest.staticCall(LAYOUT, "capture"), s.feet.x,
                    s.feet.z));
            String o = dead ? "sealed by rule: no etherwarp from a trap, maze or Boulder room" : bfsOracle(ctx, s.feet,
                    s.target);
            if (o.startsWith("REACHABLE")) {
                reachable++;
            } else if (o.startsWith("sealed")) {
                sealed++;
            } else {
                unsure++;
            }
            println(SWEEP, "no way from " + s.roomName + " " + fmt(s.feet) + " to " + s.target.toShortString() + ": " + o);
        }
        println(SWEEP, "no-way spots judged: " + (sealed + reachable + unsure) + ", sealed " + sealed + ", REACHABLE "
                + reachable + ", unsure " + unsure);
        if (reachable > 0) {
            failures.add(reachable + " no-way spot(s) the ground truth reaches with warps");
        }
        // Run a sample of the found plans: every 25th, so a planned hop the server refuses shows up.
        int ran = 0;
        int refusedTrips = 0;
        for (int k = 0; k < foundStarts.size() && ran < 12; k += Math.max(1, foundStarts.size() / 12)) {
            Start s = foundStarts.get(k);
            if (!place(ctx, s.feet)) {
                continue;
            }
            List<String> f = new ArrayList<>();
            String trip = runTrip(ctx, SWEEP, "sweep-" + s.roomName, s.feet, s.target, s.roomName, f);
            ran++;
            println(SWEEP, "ran " + s.roomName + " from " + fmt(s.feet) + ": " + trip
                    + (f.isEmpty() ? "" : " - " + String.join("; ", f)));
            for (String x : f) {
                if (x.contains("refused")) {
                    refusedTrips++;
                    failures.add(x);
                }
            }
        }
        println(SWEEP, "ran " + ran + " sampled plan(s), " + refusedTrips + " with a refused warp");
    }

    /** Client thread: every standable spot on a 2-block grid over each room's tiles, seams included. */
    private static List<Start> sweepStarts(Minecraft mc) {
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        int n = (Integer) ModUnderTest.call(layout, "roomCount", new Class<?>[]{}, new Object[]{});
        int off = (Integer) ModUnderTest.staticCall(LAYOUT, "simYOffset");
        Level level = mc.level;
        List<Start> out = new ArrayList<>();
        for (int r = 0; r < n; r++) {
            String name = (String) ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r});
            if (name == null) {
                continue;
            }
            int[] tiles = (int[]) ModUnderTest.call(layout, "tiles", new Class<?>[]{int.class}, new Object[]{r});
            if (tiles == null || tiles.length == 0) {
                continue;
            }
            BlockPos centre = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                    new Object[]{tiles[0]});
            BlockPos target = (BlockPos) ModUnderTest.staticCall("com.killer560.hub.livemap.autoclear.TeleportUtils",
                    "etherwarpableInTile", new Class<?>[]{BlockPos.class, Vec3.class},
                    new Object[]{centre, Vec3.atCenterOf(centre)});
            if (target == null) {
                continue;
            }
            for (int t : tiles) {
                if ((t % GRID) % 2 != 0 || (t / GRID) % 2 != 0) {
                    continue;   // a connector cell of a big room: its tiles cover it
                }
                BlockPos c = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                        new Object[]{t});
                for (int dx = -16; dx <= 16; dx += 2) {
                    for (int dz = -16; dz <= 16; dz += 2) {
                        int x = c.getX() + dx;
                        int z = c.getZ() + dz;
                        int here = (Integer) ModUnderTest.call(layout, "roomAtWorld",
                                new Class<?>[]{double.class, double.class}, new Object[]{x + 0.5, z + 0.5});
                        if (here != r) {
                            continue;
                        }
                        for (int y = 64 + off; y <= 84 + off; y++) {
                            BlockPos below = new BlockPos(x, y - 1, z);
                            BlockPos feet = new BlockPos(x, y, z);
                            var shape = level.getBlockState(below).getCollisionShape(level, below);
                            if (shape.isEmpty() || shape.max(Direction.Axis.Y) < 1.0
                                    || !level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                                    || !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) {
                                continue;
                            }
                            if (target.getX() == x && target.getZ() == z) {
                                continue;
                            }
                            out.add(new Start(r, name, new Vec3(x + 0.5, y, z + 0.5), target));
                        }
                    }
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ misc

    private static String fmt(Vec3 v) {
        return String.format(Locale.US, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }

    private static void println(String name, String s) {
        System.out.println("[" + name + "] " + s);
    }
}

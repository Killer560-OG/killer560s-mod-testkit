package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 141-sim-autopilot: Dungeon Autopilot (mod 2026-10-06, cheat build) on a generated sim F7, judged on what it did.
 *
 * <p>One floor. Mob rooms: R on the Blood Rush Split path, O1 and O2 off it, one starred zombie each (every other mob room
 * is marked cleared, as the map would show it). Routes are SEEDED for O1 and two secret rooms S1/S2 (102's one-node
 * ROTATE start node). Score Calculator on, Auto Puzzles off (the sim's puzzles are 93-solve's business). Three runs:
 * <ol>
 *   <li><b>party</b> - Run Mode Party, a teammate "in" O2 ({@code testSetTeammateRooms}). While any route is left the
 *       pick is a route; the clears come after; O2 is never cleared (its zombie stays alive); it never touches the blood
 *       door and stops "nothing left".</li>
 *   <li><b>solo</b> - Run Mode Solo, R's zombie back. Every pick is the best score per second of what it logged; R and
 *       O2 are cleared; with nothing left it goes to the blood door, opens it (the sim's blood door needs no key), and
 *       stops handing back for the blood camp.</li>
 *   <li><b>blood first</b> - the blood door shut again (its blocks put back) and R's and O1's zombies back, Solo +
 *       Blood First: the first pick is R (the rush path), then the blood door, BEFORE anything off the path.</li>
 * </ol>
 * Each run must have moved him and ended by itself. Only when named (several minutes).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimAutopilotTests implements FabricClientGameTest {

    private static final String NAME = "141-sim-autopilot";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_MOBS = "com.killer560.hub.roomsim.SimMobs";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String SIM_ROOM_STATE = "com.killer560.hub.roomsim.SimRoomState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String ROOM_STATUS = "com.killer560.hub.livemap.RoomStatus";
    private static final String TELEPORT_UTILS = "com.killer560.hub.livemap.autoclear.TeleportUtils";
    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    private static final String STORE = "com.killer560.hub.autoroutes.RouteStore";
    private static final String COORDS = "com.killer560.hub.autoroutes.RouteCoords";
    private static final String FRAME = "com.killer560.hub.autoroutes.RouteCoords$Frame";
    private static final String AR_CONFIG = "com.killer560.hub.autoroutes.AutoRoutesConfig";
    private static final String AS = "com.killer560.hub.autosecret.AutoSecretFeature";
    private static final String AS_CONFIG = "com.killer560.hub.autosecret.AutoSecretConfig";
    private static final String AUTO_CLEAR = "com.killer560.hub.autoclear.AutoClearFeature";
    private static final String AUTO_CLEAR_CONFIG = "com.killer560.hub.autoclear.AutoClearConfig";
    private static final String SCORE_CONFIG = "com.killer560.hub.scorecalc.ScoreCalculatorConfig";
    /** Score Calculator as this case found it; put back in the finally, so later cases see a fresh config. */
    private static boolean scoreWasOn;
    private static final int GRID = 11;
    private static final int CASE_TICKS = 20 * 240;

    /** "pick CLEAR Mushroom 4.67/9.0s=0.519" and each "; "-separated ranked entry. */
    private static final Pattern CAND = Pattern.compile("(SECRET|CLEAR|PUZZLE|EXPLORE|KEY) (.+?) (-?[\\d.]+)/([\\d.]+)s=(-?[\\d.]+)( \\(teammate\\))?$");

    private record Room(String name, String type, boolean mob, boolean cleared, boolean rush, int x, int z) {
        static Room parse(String line) {
            String[] p = line.split("\\|");
            return new Room(p[0], p[1], p[2].equals("1"), p[3].equals("1"), p[4].equals("1"),
                    Integer.parseInt(p[5]), Integer.parseInt(p[6]));
        }
    }

    /** RoomStatus's view: id, name, type, unfound, main tile. */
    private record Status(int id, String name, String type, int unfound, int mainTile) {
    }

    private record Cand(String kind, String room, double gain, double seconds, double rate, boolean teammate) {
        static Cand parse(String s) {
            Matcher m = CAND.matcher(s.trim());
            if (!m.find()) {
                return null;
            }
            return new Cand(m.group(1), m.group(2), Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4)),
                    Double.parseDouble(m.group(5)), m.group(6) != null);
        }
    }

    /** One logged decision: the pick (null for a door/finish line) and every ranked candidate. */
    private record Decision(Cand pick, List<Cand> ranked, String line) {
    }

    private static final class Result {
        double travelled;
        int ticks;
        boolean finished;
        String stopReason;
        List<String> log = new ArrayList<>();
        List<Decision> decisions = new ArrayList<>();
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Scenario.skip(LOGIC)) {
            ModUnderTest.require("killer560smod");
            logicCase(ctx);
        }
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        ctx.runOnClient(mc -> {
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                    "setAutoPuzzlesMasterEnabled", false);
            Object map = ModUnderTest.config(LIVE_MAP_CONFIG);
            ModUnderTest.set(map, "setEnabled", true);
            ModUnderTest.set(map, "setInteractiveMapEnabled", true);
            Object ar = ModUnderTest.config(AR_CONFIG);
            ModUnderTest.set(ar, "setEnabled", true);
            ModUnderTest.set(ar, "setLegitMode", false);
            ModUnderTest.set(ar, "setStartFromStartNodeOnly", true);
            ModUnderTest.call(ar, "setHeight", new Class<?>[]{float.class}, new Object[]{1.0f});
            scoreWasOn = ModUnderTest.getBoolean(ModUnderTest.config(SCORE_CONFIG), "isEnabled");
            ModUnderTest.set(ModUnderTest.config(SCORE_CONFIG), "setEnabled", true);
            Object as = ModUnderTest.config(AS_CONFIG);
            ModUnderTest.set(as, "setChatFeedback", true);
            ModUnderTest.set(as, "setRouteStallSeconds", 10);
            ModUnderTest.set(as, "setInstaClear", false);
            ModUnderTest.set(as, "setDoPuzzles", true);
            ModUnderTest.set(as, "setAutopilotHud", true);
        });
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> mc.options.renderDistance().set(16));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                new Object[]{"gametest"}));

        List<String> failures = new ArrayList<>();
        try {
            body(ctx, failures);
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> {
                if ((Boolean) ModUnderTest.staticCall(AS, "isRunning")) {
                    ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class},
                            new Object[]{"test over", true});
                }
                ModUnderTest.staticCall(AS, "testSetTeammateRooms", new Class<?>[]{java.util.Collection.class},
                        new Object[]{null});
                ModUnderTest.staticCall(AUTO_CLEAR, "cancel");
                ModUnderTest.set(ModUnderTest.config(AUTO_CLEAR_CONFIG), "setEnabled", false);
                ModUnderTest.set(ModUnderTest.config(SCORE_CONFIG), "setEnabled", scoreWasOn);
            });
            writeRoutes(ctx, Map.of());
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
        if (!failures.isEmpty()) {
            for (String f : failures) {
                println("FAIL: " + f);
            }
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
        println("PASS");
    }

    private static void body(ClientGameTestContext ctx, List<String> failures) {
        // ---- a floor with a mob room on the blood rush, two off it, and two more secret rooms ----------------------
        List<Room> rooms = null;
        Room rush = null;
        List<Room> off = new ArrayList<>();
        List<Status> secretRooms = new ArrayList<>();
        for (int attempt = 1; attempt <= 4; attempt++) {
            long before = Scenario.simBuildCount(ctx);
            ctx.runOnClient(mc -> mc.execute(() -> {
                Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
                ModUnderTest.staticCall(FLOOR_GEN, "generate",
                        new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                        new Object[]{mc, floor, 3, 4});
            }));
            ctx.waitFor(mc -> mc.level != null);
            Scenario.awaitSimBuild(ctx, before);
            ctx.waitTicks(80);
            awaitFloorChunks(ctx);
            rooms = floor(ctx);
            rush = null;
            off.clear();
            Vec3 me = ctx.computeOnClient(mc -> mc.player.position());
            List<Room> offAll = new ArrayList<>();
            for (Room r : rooms) {
                if (strands(r.name())) {
                    continue;
                }
                if (r.mob() && !r.cleared() && r.rush() && rush == null) {
                    rush = r;
                } else if (r.mob() && !r.cleared() && !r.rush()) {
                    offAll.add(r);
                }
            }
            offAll.sort((a, b) -> Double.compare(dist(me, a), dist(me, b)));
            for (int i = 0; i < Math.min(2, offAll.size()); i++) {
                off.add(offAll.get(i));
            }
            secretRooms.clear();
            // Any other room with secrets (most are mob rooms too; those are marked cleared, so only their route counts).
            List<String> taken = new ArrayList<>();
            if (rush != null) {
                taken.add(rush.name());
            }
            off.forEach(o -> taken.add(o.name()));
            for (Status s : ctx.computeOnClient(SimAutopilotTests::readStatus)) {
                if (s.unfound() > 0 && !taken.contains(s.name()) && !s.name().equals("Unknown") && !strands(s.name())
                        && !List.of("PUZZLE", "TRAP", "BLOOD", "ENTRANCE").contains(s.type()) && secretRooms.size() < 2) {
                    secretRooms.add(s);
                }
            }
            println("floor " + attempt + ": " + rooms.size() + " rooms; rush path "
                    + rooms.stream().filter(Room::rush).map(Room::name).toList() + "; R " + (rush == null ? "none" : rush.name())
                    + "; O " + off.stream().map(Room::name).toList() + "; secret rooms "
                    + secretRooms.stream().map(s -> s.name() + "=" + s.unfound()).toList());
            if (rush != null && off.size() == 2 && secretRooms.size() == 2) {
                break;
            }
            rush = null;
        }
        if (rush == null) {
            failures.add("no generated floor in 4 had a rush mob room, two off it and two secret rooms");
            return;
        }
        Room roomR = rush;
        Room roomO1 = off.get(0);
        Room roomO2 = off.get(1);

        // ---- loadout, then the run starts --------------------------------------------------------------------
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
                String[] ids = {"ASPECT_OF_THE_VOID", "HYPERION", "BAT_WAND"};
                for (int i = 0; i < ids.length; i++) {
                    inv.setItem(i, (ItemStack) ModUnderTest.staticCall(SIM_ITEMS, "build", new Class<?>[]{String.class},
                            new Object[]{ids[i]}));
                }
                given.set(true);
            });
        });
        ctx.waitFor(mc -> given.get() != null, 200);
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
        for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomsim.SimRun", "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(40);
        ground(ctx);

        List<String> keep = List.of(roomR.name(), roomO1.name(), roomO2.name());
        int marked = 0;
        for (Room r : rooms) {
            if (r.mob() && !keep.contains(r.name())) {
                ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "markCleared",
                        new Class<?>[]{String.class}, new Object[]{r.name()}));
                marked++;
            }
        }
        println(marked + " other mob room(s) marked cleared; R=" + roomR.name() + " O1=" + roomO1.name() + " O2="
                + roomO2.name());

        // ---- routes ---------------------------------------------------------------------------------------------
        Map<String, JsonObject> routes = new HashMap<>();
        List<Status> statuses = ctx.computeOnClient(SimAutopilotTests::readStatus);
        List<String> routed = new ArrayList<>();
        for (Status s : statuses) {
            boolean want = s.name().equals(roomO1.name()) && s.unfound() > 0
                    || secretRooms.stream().anyMatch(x -> x.name().equals(s.name()));
            if (!want) {
                continue;
            }
            JsonObject route = routeFor(ctx, s);
            if (route != null) {
                routes.put(s.name(), route);
                routed.add(s.name());
            }
        }
        writeRoutes(ctx, routes);
        println("routes seeded: " + routed);
        if (routed.size() < 2) {
            failures.add("fewer than two routes could be seeded (" + routed + ")");
            return;
        }

        // ---- starred mobs and Auto Clear ---------------------------------------------------------------------
        Map<String, UUID> mobs = new LinkedHashMap<>();
        for (Room r : List.of(roomR, roomO1, roomO2)) {
            UUID id = spawnOne(ctx, r);
            if (id == null) {
                failures.add("could not place a starred zombie in " + r.name());
                return;
            }
            mobs.put(r.name(), id);
        }
        ctx.waitTicks(40);
        println("mobs: " + aliveReport(ctx, mobs));
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(AUTO_CLEAR_CONFIG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setHyperionHops", true);
            ModUnderTest.call(cfg, "setWeapon", new Class<?>[]{ModUnderTest.enumValue(AUTO_CLEAR_CONFIG + "$Weapon",
                    "HYPERION").getClass()}, new Object[]{ModUnderTest.enumValue(AUTO_CLEAR_CONFIG + "$Weapon", "HYPERION")});
        });

        int bloodCell = ctx.computeOnClient(mc -> (Integer) ModUnderTest.call(ModUnderTest.staticCall(LAYOUT, "capture"),
                "bloodDoor", new Class<?>[]{}, new Object[]{}));
        if (bloodCell < 0) {
            failures.add("the floor has no blood door on the map");
            return;
        }
        BlockPos bloodLock = ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(LAYOUT, "doorBlock",
                new Class<?>[]{int.class}, new Object[]{bloodCell}));
        Map<BlockPos, BlockState> bloodBlocks = snapshotDoor(ctx, bloodLock);
        println("blood door at cell " + bloodCell + ", lock " + bloodLock.toShortString() + ", " + bloodBlocks.size()
                + " door block(s) recorded");

        // ==== case 1: Party, a teammate in O2 ====
        setMode(ctx, "PARTY", false);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(AS, "testSetTeammateRooms", new Class<?>[]{java.util.Collection.class},
                new Object[]{List.of(roomO2.name())}));
        Result party = runCase(ctx, "party");
        ctx.runOnClient(mc -> ModUnderTest.staticCall(AS, "testSetTeammateRooms", new Class<?>[]{java.util.Collection.class},
                new Object[]{null}));
        Map<String, String> alive1 = aliveReport(ctx, mobs);
        println("party: mobs " + alive1);
        checkActed(failures, "party", party);
        boolean secretLeft = true;
        int firstClear = -1;
        int lastSecret = -1;
        for (int i = 0; i < party.decisions.size(); i++) {
            Decision d = party.decisions.get(i);
            if (d.pick() == null) {
                continue;
            }
            boolean anySecret = d.ranked().stream().anyMatch(c -> c.kind().equals("SECRET"));
            if (anySecret && !d.pick().kind().equals("SECRET")) {
                failures.add("party: picked " + d.pick().kind() + " " + d.pick().room() + " while a route was left: " + d.line());
            }
            if (d.pick().kind().equals("SECRET")) {
                lastSecret = i;
                double best = d.ranked().stream().filter(c -> c.kind().equals("SECRET")).mapToDouble(Cand::rate).max().orElse(0);
                if (d.pick().rate() + 1e-6 < best) {
                    failures.add("party: route pick " + d.pick().room() + " rate " + d.pick().rate() + " < best route " + best);
                }
            } else if (firstClear < 0) {
                firstClear = i;
            }
            if (!d.pick().kind().equals("SECRET") && (d.pick().teammate() || d.pick().room().equals(roomO2.name()))) {
                failures.add("party: picked " + d.pick().kind() + " " + d.pick().room() + ", a room a teammate is in");
            }
        }
        println("party: " + party.decisions.size() + " decision(s), last route pick #" + lastSecret + ", first other pick #"
                + firstClear);
        if (firstClear >= 0 && lastSecret > firstClear) {
            failures.add("party: a route was picked after a clear");
        }
        for (String r : routed) {
            if (party.log.stream().noneMatch(l -> l.contains("[AutoSecret] route " + r + " finished"))) {
                failures.add("party: the route in " + r + " never finished");
            }
        }
        Map<String, Boolean> cleared1 = cleared(ctx);
        println("party: cleared on the map " + cleared1);
        if (!cleared1.get(roomR.name()) || !cleared1.get(roomO1.name())) {
            failures.add("party: R/O1 not cleared once the routes were done (" + cleared1 + ")");
        }
        if (cleared1.get(roomO2.name())) {
            failures.add("party: O2 (teammate inside) was cleared");
        }
        if (party.log.stream().anyMatch(l -> l.contains("right-clicked the blood door"))) {
            failures.add("party: it clicked the blood door");
        }
        if (party.stopReason == null || !party.stopReason.contains("nothing left")) {
            failures.add("party: did not stop with 'nothing left' (" + party.stopReason + ")");
        }

        // ==== case 2: Solo, R's zombie back ====
        UUID r2 = respawn(ctx, roomR);
        if (r2 == null) {
            failures.add("solo: could not put R's zombie back");
            return;
        }
        mobs.put(roomR.name(), r2);
        setMode(ctx, "SOLO", false);
        Result solo = runCase(ctx, "solo");
        Map<String, String> alive2 = aliveReport(ctx, mobs);
        println("solo: mobs " + alive2);
        checkActed(failures, "solo", solo);
        checkBestRate(failures, "solo", solo);
        Map<String, Boolean> cleared2 = cleared(ctx);
        if (!cleared2.get(roomR.name()) || !cleared2.get(roomO2.name())) {
            failures.add("solo: R/O2 not cleared on the map (" + cleared2 + ")");
        }
        boolean clicked = solo.log.stream().anyMatch(l -> l.contains("right-clicked the blood door"));
        boolean bloodOpen = ctx.computeOnClient(mc -> mc.level.getBlockState(bloodLock).isAir());
        int lastPick = -1;
        int bloodLine = -1;
        for (int i = 0; i < solo.log.size(); i++) {
            String l = solo.log.get(i);
            if (l.contains("| pick ")) {
                lastPick = i;
            }
            if (bloodLine < 0 && l.contains("going to the blood door")) {
                bloodLine = i;
            }
        }
        println("solo: blood door clicked " + clicked + ", lock block air " + bloodOpen + ", blood trip at line " + bloodLine
                + ", last pick at line " + lastPick + ", stopped: " + solo.stopReason);
        if (!clicked || !bloodOpen) {
            failures.add("solo: it did not open the blood door (clicked " + clicked + ", air " + bloodOpen + ")");
        }
        if (bloodLine >= 0 && lastPick > bloodLine) {
            failures.add("solo: it picked more work after going to the blood door - the blood door must be last");
        }
        if (solo.stopReason == null || !solo.stopReason.contains("blood camp")) {
            failures.add("solo: did not hand back for the blood camp (" + solo.stopReason + ")");
        }

        // ==== case 3: Blood First (Solo), the blood door shut again, R and O1 back ====
        restoreDoor(ctx, bloodBlocks);
        boolean shut = ctx.computeOnClient(mc -> !mc.level.getBlockState(bloodLock).isAir());
        UUID r3 = respawn(ctx, roomR);
        UUID o3 = respawn(ctx, roomO1);
        if (!shut || r3 == null || o3 == null) {
            failures.add("blood first: could not set up (door shut " + shut + ", R " + r3 + ", O1 " + o3 + ")");
            return;
        }
        mobs.put(roomR.name(), r3);
        mobs.put(roomO1.name(), o3);
        setMode(ctx, "SOLO", true);
        Result bf = runCase(ctx, "bloodfirst");
        Map<String, String> alive3 = aliveReport(ctx, mobs);
        println("blood first: mobs " + alive3);
        checkActed(failures, "bloodfirst", bf);
        Decision first = bf.decisions.stream().filter(d -> d.pick() != null).findFirst().orElse(null);
        int doorAt = -1;
        int firstOff = -1;
        int seen = 0;
        for (int i = 0; i < bf.log.size(); i++) {
            String l = bf.log.get(i);
            if (doorAt < 0 && l.contains("| pick DOOR") && l.contains("(blood)")) {
                doorAt = i;
            }
            if (l.contains("| pick ") && !l.contains("| pick DOOR")) {
                Cand c = Cand.parse(l.substring(l.indexOf("| pick ") + 7, l.indexOf(" - ", l.indexOf("| pick ")) < 0
                        ? l.length() : l.indexOf(" - ", l.indexOf("| pick "))));
                seen++;
                if (c != null && !c.room().equals(roomR.name()) && firstOff < 0) {
                    firstOff = i;
                }
            }
        }
        // The mod logs what the Run Mode alone would have picked beside a Blood First pick ("| by rate alone: ...").
        String byRate = null;
        if (first != null) {
            int at = first.line().indexOf("| by rate alone: ");
            int end = first.line().indexOf(" | pick ");
            if (at >= 0 && end > at) {
                byRate = first.line().substring(at + 17, end);
            }
        }
        Cand normal = byRate == null ? null : Cand.parse(byRate);
        boolean wouldDiffer = normal != null && !normal.room().equals(roomR.name());
        println("blood first: first pick " + (first == null ? "none" : first.pick().kind() + " " + first.pick().room())
                + ", blood door picked at line " + doorAt + ", first off-path pick at line " + firstOff + " (" + seen
                + " room pick(s)); blood door air " + ctx.computeOnClient(mc -> mc.level.getBlockState(bloodLock).isAir())
                + ", stopped: " + bf.stopReason);
        if (first == null || !first.pick().room().equals(roomR.name())) {
            failures.add("blood first: the first pick was not R (" + (first == null ? "none" : first.pick().room()) + ")");
        }
        if (doorAt < 0) {
            failures.add("blood first: it never went for the blood door");
        } else if (firstOff >= 0 && firstOff < doorAt) {
            failures.add("blood first: an off-path room was picked before the blood door");
        }
        if (!ctx.computeOnClient(mc -> mc.level.getBlockState(bloodLock).isAir())) {
            failures.add("blood first: the blood door is still shut");
        }
        Map<String, Boolean> cleared3 = cleared(ctx);
        if (!cleared3.get(roomR.name()) || !cleared3.get(roomO1.name())) {
            failures.add("blood first: R and O1 not both cleared on the map (" + cleared3 + ")");
        }
        println("blood first: by rate alone the first pick would have been " + byRate + " - Blood First changed the order: "
                + wouldDiffer + (wouldDiffer ? "" : " (R was first by rate anyway; the push is still checked above)"));
    }

    // ================================================================================================ logic

    private static final String LOGIC = "362-logic-autopilot";
    private static final String PLANNER = "com.killer560.hub.autosecret.AutopilotPlanner";
    private static final String SCORE = "com.killer560.hub.autosecret.AutopilotScore";

    /**
     * 362-logic-autopilot: the planner and score model with made-up candidates and runs - no world. Solo takes the best
     * score per second and drops what is worth nothing; Party takes a route over anything faster and never a teammate's
     * room; Blood First takes the rush path in order; the secret value stops at the S+ need (plus one), per the floor's
     * required percentage.
     */
    private static void logicCase(ClientGameTestContext ctx) {
        List<String> problems = new ArrayList<>();
        ctx.runOnClient(mc -> {
            Object a = cand("CLEAR", "A", 4.0, 10.0, false, -1);   // 0.40/s
            Object b = cand("SECRET", "B", 3.0, 5.0, false, -1);   // 0.60/s
            Object c = cand("PUZZLE", "C", 14.0, 30.0, false, -1); // 0.47/s
            check(problems, "solo: best rate (SECRET B)", pickOf(choose(false, a, b, c)), "SECRET B");
            Object b0 = cand("SECRET", "B", 0.0, 5.0, false, -1);
            check(problems, "solo: a zero-gain route is dropped (PUZZLE C)", pickOf(choose(false, a, b0, c)), "PUZZLE C");
            Object slow = cand("SECRET", "S", 0.5, 20.0, false, -1); // 0.025/s
            check(problems, "party: a slow route beats a fast clear", pickOf(choose(true, a, slow, c)), "SECRET S");
            Object aMate = cand("CLEAR", "A", 40.0, 10.0, true, -1);
            check(problems, "party: no route left, the teammate's room is skipped", pickOf(choose(true, aMate, c)),
                    "PUZZLE C");
            check(problems, "party: only a teammate's room - nothing", pickOf(choose(true, aMate)), "none");
            check(problems, "solo: a teammate marker does not matter alone", pickOf(choose(false, aMate, c)), "CLEAR A");
            Object y = cand("CLEAR", "Y", 1.0, 30.0, false, 1);
            Object x = cand("CLEAR", "X", 9.0, 3.0, false, 2);
            Object z = cand("CLEAR", "Z", 50.0, 1.0, false, -1);
            check(problems, "blood first: the rush room nearest the Entrance", pickOf(chooseBloodFirst(x, y, z)), "CLEAR Y");
            check(problems, "blood first: nothing on the path - nothing", pickOf(chooseBloodFirst(z)), "none");
            Object t1 = cand("CLEAR", "T1", 2.0, 4.0, false, -1);
            Object t2 = cand("CLEAR", "T2", 1.0, 2.0, false, -1);
            check(problems, "tie: the shorter one", pickOf(choose(false, t1, t2)), "CLEAR T2");

            Object f7 = state("F7", 28, 10, 100, 50, 5, 0, 0, 100, 250);
            check(problems, "room value 140/28", String.format(Locale.US, "%.3f", (Double) score("roomValue", f7)), "5.000");
            check(problems, "puzzle value room + 10", String.format(Locale.US, "%.3f", (Double) score("puzzleValue", f7)),
                    "15.000");
            // F7: need ceil(100 x 1.0 x (40 - 5) / 40) = 88, + margin 1, - 50 found = 39.
            check(problems, "F7 useful secrets", String.valueOf(score("usefulSecrets", f7)), "39");
            check(problems, "F7 solo gain of 50 secrets (39 x 0.4)", String.format(Locale.US, "%.2f",
                    (Double) secretGain(f7, 50, false)), "15.60");
            check(problems, "F7 party gain of 50 secrets (+11 x 0.4 x 0.25)", String.format(Locale.US, "%.2f",
                    (Double) secretGain(f7, 50, true)), "16.70");
            Object f5 = state("F5", 28, 10, 100, 50, 5, 0, 0, 100, 250);
            // F5: need ceil(100 x 0.7 x 35 / 40) = 62, + 1 - 50 = 13.
            check(problems, "F5 useful secrets", String.valueOf(score("usefulSecrets", f5)), "13");
            Object done = state("F5", 28, 10, 100, 63, 5, 0, 0, 100, 250);
            check(problems, "F5 past the need: solo gain 0", String.format(Locale.US, "%.2f",
                    (Double) secretGain(done, 10, false)), "0.00");
            check(problems, "300 reached", String.valueOf(score("reached300", state("F7", 28, 28, 100, 90, 5, 0, 0, 100, 300))),
                    "true");
            check(problems, "250 not reached", String.valueOf(score("reached300", f7)), "false");
            // autopilot2: crypt nodes (5 cap, less what is blown) and keys in Party's first tier.
            Object c2 = state("F7", 28, 10, 100, 50, 5, 0, 0, 100, 250, 2);
            check(problems, "crypt gain: 4 nodes, 2 blown -> 3", String.format(Locale.US, "%.1f", (Double) cryptGain(c2, 4)),
                    "3.0");
            check(problems, "crypt gain: 1 node, 2 blown -> 1", String.format(Locale.US, "%.1f", (Double) cryptGain(c2, 1)),
                    "1.0");
            Object c5 = state("F7", 28, 10, 100, 50, 5, 0, 0, 100, 250, 5);
            check(problems, "crypt gain: 5 blown -> 0", String.format(Locale.US, "%.1f", (Double) cryptGain(c5, 3)), "0.0");
            Object key = cand("KEY", "Wither Key#7", 10.0, 3.0, false, -1);
            check(problems, "party: a key beside a slower route", pickOf(choose(true, slow, key, a)), "KEY Wither Key#7");
            check(problems, "party: a key before a clear when no route", pickOf(choose(true, key, aMate, a)),
                    "KEY Wither Key#7");
            // keys-range: base + 5 (0.27.2); the Magnetic Talisman does not apply to keys.
            check(problems, "key range base 1.0", String.format(Locale.US, "%.1f", (Double) ModUnderTest.staticCall(
                    "com.killer560.hub.doorkeys.DungeonKeys", "pickupRange", new Class<?>[]{double.class},
                    new Object[]{1.0})), "6.0");
        });
        for (String p : problems) {
            System.out.println("[" + LOGIC + "] FAIL: " + p);
        }
        if (!problems.isEmpty()) {
            throw new AssertionError(problems.size() + " problem(s): " + String.join("; ", problems));
        }
        System.out.println("[" + LOGIC + "] PASS");
    }

    private static void check(List<String> problems, String what, String got, String want) {
        System.out.println("[" + LOGIC + "] " + what + ": " + got + (got.equals(want) ? "" : " (want " + want + ")"));
        if (!got.equals(want)) {
            problems.add(what + ": got " + got + ", want " + want);
        }
    }

    private static Object cand(String kind, String room, double gain, double seconds, boolean mate, int rush) {
        try {
            Class<?> k = Class.forName(PLANNER + "$Kind");
            Class<?> c = Class.forName(PLANNER + "$Candidate");
            return c.getConstructor(k, String.class, double.class, double.class, boolean.class, int.class)
                    .newInstance(ModUnderTest.enumValue(PLANNER + "$Kind", kind), room, gain, seconds, mate, rush);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Object choose(boolean party, Object... cands) {
        return ModUnderTest.staticCall(PLANNER, "choose", new Class<?>[]{boolean.class, List.class},
                new Object[]{party, List.of(cands)});
    }

    private static Object chooseBloodFirst(Object... cands) {
        return ModUnderTest.staticCall(PLANNER, "chooseBloodFirst", new Class<?>[]{List.class}, new Object[]{List.of(cands)});
    }

    private static String pickOf(Object choice) {
        if (choice == null) {
            return "none";
        }
        Object pick = ModUnderTest.call(choice, "pick", new Class<?>[]{}, new Object[]{});
        return ModUnderTest.call(pick, "kind", new Class<?>[]{}, new Object[]{}) + " "
                + ModUnderTest.call(pick, "room", new Class<?>[]{}, new Object[]{});
    }

    private static Object state(String floor, int totalRooms, int completed, int totalSecrets, int found, int bonus,
                                int deathPenalty, int failed, int speed, int total) {
        return state(floor, totalRooms, completed, totalSecrets, found, bonus, deathPenalty, failed, speed, total, 0);
    }

    private static Object cryptGain(Object state, int nodes) {
        return ModUnderTest.staticCall(SCORE, "cryptGain", new Class<?>[]{state.getClass(), int.class},
                new Object[]{state, nodes});
    }

    private static Object state(String floor, int totalRooms, int completed, int totalSecrets, int found, int bonus,
                                int deathPenalty, int failed, int speed, int total, int crypts) {
        try {
            Class<?> c = Class.forName(SCORE + "$State");
            Class<?>[] t = {String.class, int.class, int.class, int.class, int.class, int.class, int.class, int.class,
                    int.class, int.class, int.class};
            return c.getConstructor(t).newInstance(floor, totalRooms, completed, totalSecrets, found, bonus, deathPenalty,
                    failed, speed, total, crypts);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Object score(String method, Object state) {
        return ModUnderTest.staticCall(SCORE, method, new Class<?>[]{state.getClass()}, new Object[]{state});
    }

    private static Object secretGain(Object state, int n, boolean party) {
        return ModUnderTest.staticCall(SCORE, "secretGain", new Class<?>[]{state.getClass(), int.class, boolean.class},
                new Object[]{state, n, party});
    }

    // ================================================================================================ the run

    private static void setMode(ClientGameTestContext ctx, String mode, boolean bloodFirst) {
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(AS_CONFIG);
            Object m = ModUnderTest.enumValue(AS_CONFIG + "$RunMode", mode);
            ModUnderTest.call(cfg, "setRunMode", new Class<?>[]{m.getClass()}, new Object[]{m});
            ModUnderTest.set(cfg, "setBloodFirst", bloodFirst);
        });
        ground(ctx);
    }

    private static Result runCase(ClientGameTestContext ctx, String label) {
        Result r = new Result();
        long mark = LogTap.mark();
        Boolean started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "startAutopilot"));
        if (!Boolean.TRUE.equals(started)) {
            r.stopReason = "refused to start";
            r.log = LogTap.since(mark);
            return r;
        }
        Vec3 last = ctx.computeOnClient(mc -> mc.player.position());
        for (int t = 0; t < CASE_TICKS; t += 5) {
            ctx.waitTicks(5);
            Vec3 now = ctx.computeOnClient(mc -> mc.player.position());
            r.travelled += now.distanceTo(last);
            last = now;
            r.ticks = t + 5;
            if (!(Boolean) ctx.computeOnClient(mc -> ModUnderTest.staticCall(AS, "isRunning"))) {
                r.finished = true;
                break;
            }
            if (t % 400 == 395) {
                println(label + " t=" + (t + 5) / 20 + "s: " + ctx.computeOnClient(mc -> String.join(" | ",
                        (String[]) ModUnderTest.staticCall(AS, "autopilotHud"))));
            }
        }
        if (!r.finished) {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class},
                    new Object[]{"test timeout", true}));
        }
        ctx.waitTicks(5);
        r.log = LogTap.since(mark);
        for (String l : r.log) {
            if (l.contains("[AutoSecret] stopped: ")) {
                r.stopReason = l.substring(l.indexOf("[AutoSecret] stopped: ") + 22);
            }
            int p = l.indexOf("| pick ");
            if (p >= 0) {
                int end = l.indexOf(" - ", p);
                Cand pick = Cand.parse(l.substring(p + 7, end < 0 ? l.length() : end));
                List<Cand> ranked = new ArrayList<>();
                int rk = l.indexOf("| ranked: ");
                if (rk >= 0) {
                    for (String part : l.substring(rk + 10).split("; ")) {
                        Cand c = Cand.parse(part);
                        if (c != null) {
                            ranked.add(c);
                        }
                    }
                }
                r.decisions.add(new Decision(pick, ranked, l.replaceAll("^.*?\\[Autopilot\\]", "[Autopilot]")));
            }
        }
        println(String.format(Locale.US, "%s: %.1f s, travelled %.1f blocks, %d decision(s), stopped: %s", label,
                r.ticks / 20.0, r.travelled, r.decisions.size(), r.stopReason));
        for (String l : r.log) {
            if (l.contains("[Autopilot]") || l.contains("[AutoSecret]") || l.contains("[AutoClear] done")
                    || l.contains("[AutoClear] stopped")) {
                String s = l.replaceAll("^.*?(\\[Autopilot\\]|\\[AutoSecret\\]|\\[AutoClear\\])", "$1");
                println("  " + (s.length() > 600 ? s.substring(0, 600) + "..." : s));
            }
        }
        return r;
    }

    private static void checkActed(List<String> failures, String label, Result r) {
        if (!r.finished) {
            failures.add(label + ": still running after " + CASE_TICKS / 20 + " s");
        }
        if (r.travelled < 10) {
            failures.add(String.format(Locale.US, "%s: travelled only %.1f blocks - nothing happened", label, r.travelled));
        }
        if (r.decisions.isEmpty()) {
            failures.add(label + ": no [Autopilot] decision was logged");
        }
        if (r.log.stream().anyMatch(l -> l.contains("Exception") && l.contains("autosecret"))) {
            failures.add(label + ": an exception mentioning autosecret was logged");
        }
    }

    /** Every pick is the logged best score per second, and its logged rate is gain/seconds. */
    private static void checkBestRate(List<String> failures, String label, Result r) {
        for (Decision d : r.decisions) {
            if (d.pick() == null || d.ranked().isEmpty()) {
                continue;
            }
            double best = d.ranked().stream().mapToDouble(Cand::rate).max().orElse(0);
            if (d.pick().rate() + 1e-6 < best) {
                failures.add(label + ": pick " + d.pick().room() + " rate " + d.pick().rate() + " below the best " + best
                        + ": " + d.line());
            }
            for (Cand c : d.ranked()) {
                double recomputed = c.gain() / Math.max(0.5, c.seconds());
                // The log rounds gain and seconds, so allow their rounding (2%) on top of the rate's own.
                if (Math.abs(recomputed - c.rate()) > 0.02 * Math.abs(c.rate()) + 0.002) {
                    failures.add(label + ": " + c.room() + " logged rate " + c.rate() + " but gain/seconds is " + recomputed);
                }
            }
        }
    }

    // ================================================================================================ the world

    private static void ground(ClientGameTestContext ctx) {
        for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> mc.player.onGround()); i++) {
            ctx.waitTicks(1);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Room> floor(ClientGameTestContext ctx) {
        List<String> lines = ctx.computeOnClient(mc -> (List<String>) ModUnderTest.staticCall(AUTO_CLEAR, "floorReport"));
        List<Room> out = new ArrayList<>();
        for (String l : lines) {
            out.add(Room.parse(l));
        }
        return out;
    }

    private static List<Status> readStatus(Minecraft mc) {
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) ModUnderTest.staticCall(ROOM_STATUS, "rooms");
        List<Status> out = new ArrayList<>();
        for (Object r : list) {
            out.add(new Status((Integer) ModUnderTest.call(r, "room", new Class<?>[]{}, new Object[]{}),
                    (String) ModUnderTest.call(r, "name", new Class<?>[]{}, new Object[]{}),
                    (String) ModUnderTest.call(r, "type", new Class<?>[]{}, new Object[]{}),
                    (Integer) ModUnderTest.call(r, "unfound", new Class<?>[]{}, new Object[]{}),
                    (Integer) ModUnderTest.call(r, "mainTile", new Class<?>[]{}, new Object[]{})));
        }
        return out;
    }

    /** The mod leaves these out (the map cannot path out of them): Autopilot.strands. */
    private static boolean strands(String name) {
        return name.contains("Maze") || name.contains("Boulder") || name.contains("Trap");
    }

    private static double dist(Vec3 me, Room r) {
        double dx = me.x - r.x();
        double dz = me.z - r.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** A one-node route: a ROTATE start node standing on a standable block of the room's main tile (102's). */
    private static JsonObject routeFor(ClientGameTestContext ctx, Status r) {
        return ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            int[] cr = (int[]) ModUnderTest.call(layout, "clayRotation", new Class<?>[]{int.class}, new Object[]{r.id()});
            if (cr == null) {
                return null;
            }
            BlockPos centre = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                    new Object[]{r.mainTile()});
            BlockPos stand = (BlockPos) ModUnderTest.staticCall(TELEPORT_UTILS, "etherwarpableInTile",
                    new Class<?>[]{BlockPos.class, Vec3.class}, new Object[]{centre, Vec3.atCenterOf(centre)});
            if (stand == null) {
                return null;
            }
            Object frame;
            try {
                frame = Class.forName(FRAME).getConstructor(String.class, int.class, int.class, int.class)
                        .newInstance(r.name(), cr[0], cr[1], cr[2]);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            Vec3 rel = (Vec3) ModUnderTest.staticCall(COORDS, "toRelative", new Class<?>[]{frame.getClass(), Vec3.class},
                    new Object[]{frame, new Vec3(stand.getX() + 0.5, stand.getY() + 1, stand.getZ() + 0.5)});
            JsonObject node = new JsonObject();
            node.addProperty("type", "ROTATE");
            node.addProperty("x", rel.x);
            node.addProperty("y", rel.y);
            node.addProperty("z", rel.z);
            node.addProperty("yaw", 0f);
            node.addProperty("pitch", 0f);
            node.addProperty("start", true);
            JsonArray nodes = new JsonArray();
            nodes.add(node);
            JsonObject route = new JsonObject();
            route.add("nodes", nodes);
            return route;
        });
    }

    private static void writeRoutes(ClientGameTestContext ctx, Map<String, JsonObject> routes) {
        ctx.runOnClient(mc -> {
            try {
                Path file = (Path) ModUnderTest.staticCall(STORE, "routesFile");
                JsonObject all = new JsonObject();
                routes.forEach(all::add);
                JsonObject root = new JsonObject();
                root.addProperty("version", 1);
                root.add("routes", all);
                Files.createDirectories(file.getParent());
                Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
                ModUnderTest.staticCall(STORE, "reload");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        ctx.waitTicks(2);
    }

    /** One starred zombie on an open floor spot near the room's first tile's centre; its UUID, or null. */
    private static UUID spawnOne(ClientGameTestContext ctx, Room room) {
        AtomicReference<BlockPos> spot = new AtomicReference<>();
        AtomicReference<Boolean> done = new AtomicReference<>();
        int yHint = ctx.computeOnClient(mc -> ((BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter",
                new Class<?>[]{int.class}, new Object[]{0})).getY());
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                ServerLevel level = server.overworld();
                search:
                for (int ring = 2; ring <= 10; ring += 2) {
                    for (int dx = -ring; dx <= ring; dx += 2) {
                        for (int dz = -ring; dz <= ring; dz += 2) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                                continue;
                            }
                            for (int y = yHint - 6; y <= yHint + 6; y++) {
                                BlockPos feet = new BlockPos(room.x() + dx, y, room.z() + dz);
                                if (solid(level, feet.below()) && !solid(level, feet) && !solid(level, feet.above())
                                        && !solid(level, feet.above(2))) {
                                    spot.set(feet);
                                    break search;
                                }
                            }
                        }
                    }
                }
                done.set(true);
            });
        });
        ctx.waitFor(mc -> done.get() != null, 200);
        if (spot.get() == null) {
            return null;
        }
        int before = starredPairCount(ctx);
        ctx.runOnClient(mc -> {
            Object k = ModUnderTest.enumValue(SIM_MOBS + "$Kind", "ZOMBIE");
            ModUnderTest.staticCall(SIM_MOBS, "spawnStarred", new Class<?>[]{Minecraft.class, BlockPos.class, k.getClass()},
                    new Object[]{mc, spot.get(), k});
        });
        ctx.waitTicks(3);
        return newestStarred(ctx, before);
    }

    /** A new zombie in a room that was cleared, then the room un-cleared (the sim re-clears it when that zombie dies). */
    private static UUID respawn(ClientGameTestContext ctx, Room room) {
        UUID id = spawnOne(ctx, room);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "clearRoom", new Class<?>[]{String.class},
                new Object[]{room.name()}));
        ctx.waitTicks(30);
        return id;
    }

    private static boolean solid(ServerLevel level, BlockPos p) {
        return !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    /** Every non-air block in the blood door's 5x5x5 box around its lock block, to put the door back later. */
    private static Map<BlockPos, BlockState> snapshotDoor(ClientGameTestContext ctx, BlockPos lock) {
        AtomicReference<Map<BlockPos, BlockState>> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                ServerLevel level = server.overworld();
                Map<BlockPos, BlockState> m = new LinkedHashMap<>();
                BlockState at = level.getBlockState(lock);
                for (BlockPos p : BlockPos.betweenClosed(lock.offset(-2, -1, -2), lock.offset(2, 4, 2))) {
                    BlockState s = level.getBlockState(p);
                    if (s.getBlock() == at.getBlock()) {
                        m.put(p.immutable(), s);
                    }
                }
                out.set(m);
            });
        });
        ctx.waitFor(mc -> out.get() != null, 200);
        return out.get();
    }

    private static void restoreDoor(ClientGameTestContext ctx, Map<BlockPos, BlockState> blocks) {
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                ServerLevel level = server.overworld();
                blocks.forEach(level::setBlockAndUpdate);
                done.set(true);
            });
        });
        ctx.waitFor(mc -> done.get() != null, 200);
        ctx.waitTicks(20);
    }

    @SuppressWarnings("unchecked")
    private static int starredPairCount(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> ((List<UUID[]>) ModUnderTest.staticCall(SIM_MOBS, "starredPairs")).size());
    }

    @SuppressWarnings("unchecked")
    private static UUID newestStarred(ClientGameTestContext ctx, int before) {
        for (int i = 0; i < 40; i++) {
            List<UUID[]> pairs = ctx.computeOnClient(mc -> (List<UUID[]>) ModUnderTest.staticCall(SIM_MOBS, "starredPairs"));
            if (pairs.size() > before) {
                return pairs.get(pairs.size() - 1)[0];
            }
            ctx.waitTicks(1);
        }
        return null;
    }

    private static Map<String, String> aliveReport(ClientGameTestContext ctx, Map<String, UUID> mobs) {
        AtomicReference<Map<String, String>> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                ServerLevel level = server.overworld();
                Map<String, String> m = new LinkedHashMap<>();
                mobs.forEach((room, id) -> {
                    Entity e = level.getEntity(id);
                    // Info only: a null is dead OR an unloaded section; the checks read the map's clears instead.
                    m.put(room, e != null ? (e.isAlive() ? "alive" : "dead") : "gone-or-unloaded");
                });
                out.set(m);
            });
        });
        ctx.waitFor(mc -> out.get() != null, 200);
        return out.get();
    }

    /** Room name -> cleared on the map, as Auto Clear reads it. */
    private static Map<String, Boolean> cleared(ClientGameTestContext ctx) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (Room r : floor(ctx)) {
            m.put(r.name(), r.cleared());
        }
        return m;
    }

    private static void awaitFloorChunks(ClientGameTestContext ctx) {
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
        println("floor chunks on the client: " + (missing == 0 ? "all, after " + waited / 20.0 + " s"
                : missing + " still missing after 90 s"));
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}

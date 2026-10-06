package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.suggestion.Suggestion;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * 97-sim-roomcycle: the sim's All Rooms (route practice) - a three-way set menu, one room at a time, /next and /back.
 *
 * <p>killer560 (2026-10-06): "make it only have one room at a time excluding any with 0 secrets or puzzles, then make
 * the command /next take me to the next room and /back take me back a room. also make it so when I select it then it
 * goes to a new menu that says rooms without routes or all rooms or rooms with auto routes."
 *
 * <p>The ORACLE is independent of the mod, as in 97-sim-roompick: eligibility read straight from the copied
 * {@code rooms-modern.json} (not PUZZLE/BLOOD/ENTRANCE/FAIRY, secrets &gt; 0), "has routes" from the route file this
 * scenario writes (routes on the 2nd and 3rd eligible rooms and on a puzzle room), order from the library's names.
 * Every menu press goes through the screen's own mouseClicked at the widget's drawn centre, and /next and /back go
 * through {@code sendCommand}, which Fabric's client command API intercepts as it does a typed command.
 *
 * <p>For each step it requires: a build actually ran (or, at an end, did NOT), the sim reports exactly ONE placed
 * room and it is the expected one, the chat line "Room i/n: name (N secrets, routes: yes/no)" was shown with the
 * database's secret count, and the blocks in the room's cell changed (read on the server thread).
 */
public class SimRoomCycleTests implements FabricClientGameTest {

    private static final String NAME = "97-sim-roomcycle";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String ROOM_INDEX = "com.killer560.hub.roomsim.SimRoomIndex";
    private static final String MENU = "com.killer560.hub.roomsim.SimMenuScreen";
    private static final String ROUTES = "com.killer560.hub.roomsim.SimRoomRoutes";
    private static final String CYCLE = "com.killer560.hub.roomsim.SimRoomCycle";
    private static final String STORE = "com.killer560.hub.autoroutes.RouteStore";
    private static final String KEY_UTIL = "com.killer560.hub.util.KeyUtil";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String HOME_LABEL = "All Rooms (route practice)";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            System.out.println("[" + NAME + "] SKIPPED - needs his real rooms and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);

        // ---- the oracle -------------------------------------------------------------------------------
        Map<String, JsonObject> db = readDatabase();
        @SuppressWarnings("unchecked")
        List<String> library = ctx.computeOnClient(mc ->
                new ArrayList<>((List<String>) ModUnderTest.staticCall(ROOM_LIBRARY, "names")));
        List<String> eligible = new ArrayList<>();
        String puzzle = null;
        int zeroSecret = 0;
        int puzzles = 0;
        for (String n : library) {
            JsonObject o = db.get(n);
            if (oracleEligible(o)) {
                eligible.add(n);
            }
            if (o != null && "PUZZLE".equals(type(o))) {
                puzzles++;
                if (puzzle == null) {
                    puzzle = n;
                }
            }
            if (o != null && secrets(o) <= 0 && !"PUZZLE".equals(type(o))) {
                zeroSecret++;
            }
        }
        System.out.println("[" + NAME + "] library " + library.size() + ": " + eligible.size() + " eligible; "
                + puzzles + " puzzle and " + zeroSecret + " other 0-secret room(s) the sets must leave out");
        if (eligible.size() < 8 || puzzle == null || zeroSecret == 0) {
            throw new AssertionError("not enough rooms to test the exclusion with: eligible=" + eligible.size()
                    + " puzzle=" + puzzle + " zeroSecret=" + zeroSecret);
        }
        List<String> routed = List.of(eligible.get(1), eligible.get(2));
        List<String> seeded = new ArrayList<>(routed);
        seeded.add(puzzle);
        writeRoutes(ctx, seeded);
        List<String> expectHas = new ArrayList<>(routed);
        List<String> expectNone = new ArrayList<>(eligible);
        expectNone.removeAll(routed);

        List<String> failures = new ArrayList<>();
        long mark = LogTap.mark();
        try {
            // ---- the menu, from the title screen -----------------------------------------------------
            openCycleMenu(ctx);
            Map<String, Integer> counts = new HashMap<>();
            for (String l : buttonLabels(ctx)) {
                for (String opt : new String[]{"Rooms Without Routes", "All Rooms", "Rooms With Auto Routes"}) {
                    if (l.startsWith(opt + " (")) {
                        try {
                            counts.put(opt, Integer.parseInt(l.substring(opt.length() + 2, l.length() - 1)));
                        } catch (NumberFormatException ignored) {
                            // a "(loading...)" label; reported below as a missing count
                        }
                    }
                }
            }
            System.out.println("[" + NAME + "] menu: " + buttonLabels(ctx) + " counts " + counts);
            check(failures, Integer.valueOf(expectNone.size()).equals(counts.get("Rooms Without Routes")),
                    "Rooms Without Routes count " + counts.get("Rooms Without Routes") + ", oracle " + expectNone.size());
            check(failures, Integer.valueOf(eligible.size()).equals(counts.get("All Rooms")),
                    "All Rooms count " + counts.get("All Rooms") + ", oracle " + eligible.size());
            check(failures, Integer.valueOf(expectHas.size()).equals(counts.get("Rooms With Auto Routes")),
                    "Rooms With Auto Routes count " + counts.get("Rooms With Auto Routes") + ", oracle "
                            + expectHas.size());
            check(failures, buttonLabels(ctx).contains("Back"), "no Back button on the route practice menu");

            // Keybind rows: capture a mouse button through the screen, read the stored bind back, then clear it.
            press(ctx, l -> l.startsWith("Next Room"), "Next Room");
            clickRaw(ctx, 3);
            int code = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(CYCLE, "getNextKey"));
            int want = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(KEY_UTIL, "codeForMouseButton",
                    new Class<?>[]{int.class}, new Object[]{3}));
            System.out.println("[" + NAME + "] Next Room bind after a mouse-4 capture: " + code + " (want " + want
                    + "), labels " + buttonLabels(ctx));
            check(failures, code == want, "Next Room did not capture mouse 4: " + code);
            ctx.runOnClient(mc -> ModUnderTest.staticCall(CYCLE, "setNextKey", new Class<?>[]{int.class},
                    new Object[]{-1}));

            // ---- Rooms With Auto Routes: opens the world from the title screen --------------------------
            choose(ctx, "Rooms With Auto Routes");
            ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
            awaitLoaded(ctx, expectHas.get(0));
            checkSet(ctx, failures, "WITH_ROUTES", expectHas, db);
            checkSuggestions(ctx, failures);
            walk(ctx, failures, db, expectHas, routed, new int[]{-1, 1, 1, -1}, 0);

            // ---- Rooms Without Routes, from the menu opened inside the sim -------------------------------
            openCycleMenu(ctx);
            choose(ctx, "Rooms Without Routes");
            awaitLoaded(ctx, expectNone.get(0));
            checkSet(ctx, failures, "WITHOUT_ROUTES", expectNone, db);
            walk(ctx, failures, db, expectNone, routed, new int[]{1, 1, -1}, 0);

            // ---- All Rooms --------------------------------------------------------------------------------
            openCycleMenu(ctx);
            choose(ctx, "All Rooms");
            awaitLoaded(ctx, eligible.get(0));
            checkSet(ctx, failures, "ALL", eligible, db);
            walk(ctx, failures, db, eligible, routed, new int[]{1, 1}, 0);

            for (String e : LogTap.modErrorsSince(mark)) {
                failures.add("mod ERROR: " + e);
            }
            if (!failures.isEmpty()) {
                throw new AssertionError(failures.size() + " problem(s): " + failures);
            }
            System.out.println("[" + NAME + "] PASS - three sets, exclusions and route filter right, one room at a "
                    + "time, /next and /back step and stop at the ends");
        } finally {
            ctx.runOnClient(mc -> {
                try {
                    ModUnderTest.staticCall(CYCLE, "setNextKey", new Class<?>[]{int.class}, new Object[]{-1});
                } catch (Throwable ignored) {
                    // cleanup never replaces the verdict
                }
            });
            writeRoutes(ctx, List.of());
            teardown(ctx);
        }
    }

    // =========================================================================================== steps

    /**
     * Runs /next (1) or /back (-1) for each entry of {@code moves}, starting at index {@code at}, checking each one
     * against the oracle list: a move past either end must load nothing.
     */
    private static void walk(ClientGameTestContext ctx, List<String> failures, Map<String, JsonObject> db,
                             List<String> list, List<String> routed, int[] moves, int at) {
        for (int move : moves) {
            String cmd = move > 0 ? "next" : "back";
            int target = at + move;
            String from = soloRoom(ctx);
            long fpBefore = fingerprint(ctx);
            long builds = Scenario.simBuildCount(ctx);
            long mark = LogTap.mark();
            ctx.runOnClient(mc -> mc.player.connection.sendCommand(cmd));
            if (target < 0 || target >= list.size()) {
                ctx.waitTicks(40);
                long after = Scenario.simBuildCount(ctx);
                String still = soloRoom(ctx);
                boolean said = chatSince(mark).stream().anyMatch(l -> l.contains(move > 0
                        ? "That is the last room" : "That is the first room"));
                System.out.println("[" + NAME + "] /" + cmd + " at " + (at + 1) + "/" + list.size() + ": builds "
                        + builds + " -> " + after + ", room " + from + " -> " + still + ", said so " + said);
                check(failures, after == builds && from.equals(still) && said,
                        "/" + cmd + " past the end should load nothing and say so (builds " + builds + " -> " + after
                                + ", " + from + " -> " + still + ", said " + said + ")");
                continue;
            }
            String expected = list.get(target);
            Scenario.awaitSimBuild(ctx, builds);
            awaitLoaded(ctx, expected);
            String now = soloRoom(ctx);
            int placed = placedCount(ctx);
            long fpAfter = fingerprint(ctx);
            int secrets = secrets(db.get(expected));
            String line = "Room " + (target + 1) + "/" + list.size() + ": " + expected + " (" + secrets
                    + (secrets == 1 ? " secret" : " secrets") + ", routes: " + (routed.contains(expected) ? "yes" : "no")
                    + ")";
            boolean said = chatSince(mark).stream().anyMatch(l -> l.contains(line));
            System.out.println("[" + NAME + "] /" + cmd + ": " + from + " -> " + now + " (expected " + expected
                    + "), placed " + placed + ", cell blocks " + fpBefore + " -> " + fpAfter + ", chat \"" + line
                    + "\" " + (said ? "shown" : "MISSING"));
            check(failures, expected.equals(now), "/" + cmd + " loaded " + now + ", expected " + expected);
            check(failures, placed == 1, "/" + cmd + " left " + placed + " room(s) placed, expected exactly 1");
            check(failures, fpAfter != fpBefore && fpAfter != 0, "/" + cmd + " did not change the room's blocks ("
                    + fpBefore + " -> " + fpAfter + ")");
            check(failures, said, "/" + cmd + " did not show \"" + line + "\"");
            at = target;
        }
    }

    private static void checkSet(ClientGameTestContext ctx, List<String> failures, String choice,
                                 List<String> expected, Map<String, JsonObject> db) {
        String got = ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(CYCLE, "choice")));
        @SuppressWarnings("unchecked")
        List<String> rooms = ctx.computeOnClient(mc ->
                new ArrayList<>((List<String>) ModUnderTest.staticCall(CYCLE, "currentRooms")));
        int placed = placedCount(ctx);
        System.out.println("[" + NAME + "] set " + got + ": " + rooms.size() + " room(s)"
                + (rooms.size() <= 6 ? " " + rooms : "") + ", first loaded " + soloRoom(ctx) + ", placed " + placed);
        check(failures, choice.equals(got), "chose " + choice + " but the mod reports " + got);
        check(failures, rooms.equals(expected), choice + " lists " + rooms.size() + " room(s), oracle " + expected.size()
                + "; extra " + minus(rooms, expected) + ", missing " + minus(expected, rooms));
        for (String r : rooms) {
            JsonObject o = db.get(r);
            if (!oracleEligible(o)) {
                failures.add(choice + " contains " + r + " (type " + (o == null ? "?" : type(o)) + ", secrets "
                        + (o == null ? "?" : secrets(o)) + ")");
            }
        }
        check(failures, placed == 1, choice + " start left " + placed + " room(s) placed, expected exactly 1");
    }

    /** /next and /back tab-complete in the sim: asked of Fabric's client dispatcher, as the chat box asks it. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void checkSuggestions(ClientGameTestContext ctx, List<String> failures) {
        for (String[] probe : new String[][]{{"nex", "next"}, {"bac", "back"}}) {
            List<String> got = ctx.computeOnClient(mc -> {
                try {
                    // Fabric API 0.155 has no api ClientCommandManager (ClassNotFoundException on the first run);
                    // the mod's own CommandTreeRefresh reads the dispatcher from ClientCommandInternals.
                    Class<?> ccm = Class.forName("net.fabricmc.fabric.impl.command.client.ClientCommandInternals");
                    CommandDispatcher d = (CommandDispatcher) ccm.getMethod("getActiveDispatcher").invoke(null);
                    Object source = mc.player.connection.getSuggestionsProvider();
                    List<String> out = new ArrayList<>();
                    for (Object s : ((com.mojang.brigadier.suggestion.Suggestions) d.getCompletionSuggestions(
                            d.parse(probe[0], source)).get()).getList()) {
                        out.add(((Suggestion) s).getText());
                    }
                    return out;
                } catch (Exception e) {
                    return List.of("ERROR " + e);
                }
            });
            System.out.println("[" + NAME + "] completions for \"" + probe[0] + "\": " + got);
            check(failures, got.contains(probe[1]), "\"" + probe[0] + "\" does not tab-complete to " + probe[1]
                    + ": " + got);
        }
    }

    // =========================================================================================== menu

    private static void openCycleMenu(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.execute(() -> {
            try {
                Screen s = (Screen) Class.forName(MENU).getConstructor(Screen.class).newInstance((Object) null);
                McCompat.setScreen(mc, s);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }));
        ctx.waitFor(mc -> McCompat.screen(mc) != null && McCompat.screen(mc).getClass().getName().equals(MENU), 400);
        ctx.waitTicks(3);
        press(ctx, l -> l.equals(HOME_LABEL), HOME_LABEL);
        // The database is loaded (ensureRoomDatabase), but the counts are drawn from it - wait for real ones.
        for (int i = 0; i < 100 && buttonLabels(ctx).stream().anyMatch(l -> l.contains("loading")); i++) {
            ctx.waitTicks(2);
        }
        ctx.waitTicks(2);
    }

    /** Presses one of the three set buttons, and waits for a room build to finish. */
    private static void choose(ClientGameTestContext ctx, String option) {
        long before = Scenario.simBuildCount(ctx);
        press(ctx, l -> l.startsWith(option + " (") && Character.isDigit(l.charAt(option.length() + 2)), option);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
    }

    private static void awaitLoaded(ClientGameTestContext ctx, String room) {
        for (int i = 0; i < 200 && !room.equals(soloRoom(ctx)); i++) {
            ctx.waitTicks(2);
        }
        ctx.waitTicks(20);
    }

    private static void press(ClientGameTestContext ctx, Predicate<String> which, String what) {
        String result = ctx.computeOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            if (s == null) {
                return "no screen";
            }
            for (var child : s.children()) {
                if (child instanceof AbstractWidget w && which.test(strip(w.getMessage().getString()))) {
                    double x = w.getX() + w.getWidth() / 2.0;
                    double y = w.getY() + w.getHeight() / 2.0;
                    boolean took = s.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)), false);
                    s.mouseReleased(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)));
                    return took ? "ok" : "click at " + x + "," + y + " was not taken";
                }
            }
            return "no button \"" + what + "\"";
        });
        if (!"ok".equals(result)) {
            throw new AssertionError(result + " on " + buttonLabels(ctx));
        }
        ctx.waitTicks(3);
    }

    /** A press of mouse {@code button} in an empty corner of the screen, as a bind capture takes it. */
    private static void clickRaw(ClientGameTestContext ctx, int button) {
        ctx.runOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            s.mouseClicked(new MouseButtonEvent(2, 2, new MouseButtonInfo(button, 0)), false);
            s.mouseReleased(new MouseButtonEvent(2, 2, new MouseButtonInfo(button, 0)));
        });
        ctx.waitTicks(3);
    }

    private static List<String> buttonLabels(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            List<String> out = new ArrayList<>();
            Screen s = McCompat.screen(mc);
            if (s != null) {
                for (var child : s.children()) {
                    if (child instanceof AbstractWidget w) {
                        out.add(strip(w.getMessage().getString()));
                    }
                }
            }
            return out;
        });
    }

    // =========================================================================================== reads

    private static String soloRoom(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(ROUTES, "currentSoloRoom"));
    }

    private static int placedCount(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> ((List<?>) ModUnderTest.staticCall(ROOM_INDEX, "placed")).size());
    }

    /**
     * A hash of the non-air blocks in the single-room cell (and their count folded in), read on the SERVER thread -
     * never the render thread (mod docs/LESSONS.md). 0 when nothing could be read.
     */
    private static long fingerprint(ClientGameTestContext ctx) {
        AtomicReference<Long> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            if (server == null) {
                out.set(0L);
                return;
            }
            try {
                int grid = Class.forName(LAYOUT).getField("GRID").getInt(null);
                int centre = (grid / 2) & ~1;
                BlockPos c = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                        new Object[]{centre * grid + centre});
                server.execute(() -> {
                    ServerLevel level = server.overworld();
                    long h = 0;
                    long n = 0;
                    BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
                    for (int x = c.getX() - 15; x <= c.getX() + 15; x++) {
                        for (int z = c.getZ() - 15; z <= c.getZ() + 15; z++) {
                            for (int y = level.getMinY(); y < level.getMaxY(); y++) {
                                p.set(x, y, z);
                                var st = level.getBlockState(p);
                                if (!st.isAir()) {
                                    n++;
                                    h = h * 31 + (st.hashCode() ^ (x * 73856093L) ^ (y * 19349663L) ^ (z * 83492791L));
                                }
                            }
                        }
                    }
                    out.set(n == 0 ? 0L : (h ^ (n << 40)));
                });
            } catch (ReflectiveOperationException e) {
                out.set(0L);
            }
        });
        ctx.waitFor(mc -> out.get() != null, 400);
        return out.get();
    }

    private static List<String> chatSince(long mark) {
        return LogTap.since(mark);
    }

    private static void check(List<String> failures, boolean ok, String what) {
        if (!ok) {
            failures.add(what);
            System.out.println("[" + NAME + "] FAIL: " + what);
        }
    }

    private static String strip(String s) {
        String t = net.minecraft.ChatFormatting.stripFormatting(s);
        return t == null ? s : t;
    }

    private static List<String> minus(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.removeAll(b);
        return out.size() > 8 ? out.subList(0, 8) : out;
    }

    // =========================================================================================== data

    private static Map<String, JsonObject> readDatabase() {
        try {
            Path file = ModUnderTest.modConfig("killer560smod-roomdata").resolve("rooms-modern.json");
            JsonArray arr = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray();
            Map<String, JsonObject> out = new HashMap<>();
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                out.put(o.get("name").getAsString(), o);
            }
            return out;
        } catch (Exception e) {
            throw new AssertionError("could not read the copied room database: " + e);
        }
    }

    private static String type(JsonObject o) {
        return o.has("type") ? o.get("type").getAsString().toUpperCase(Locale.ROOT) : "";
    }

    private static int secrets(JsonObject o) {
        return o != null && o.has("secrets") ? o.get("secrets").getAsInt() : 0;
    }

    private static boolean oracleEligible(JsonObject o) {
        if (o == null) {
            return false;
        }
        String t = type(o);
        return secrets(o) > 0 && !t.equals("PUZZLE") && !t.equals("BLOOD") && !t.equals("ENTRANCE")
                && !t.equals("FAIRY");
    }

    /** Writes the routes file with two WALK nodes in each named room, then has the mod re-read it. */
    private static void writeRoutes(ClientGameTestContext ctx, List<String> rooms) {
        ctx.runOnClient(mc -> {
            try {
                Path file = (Path) ModUnderTest.staticCall(STORE, "routesFile");
                JsonObject routes = new JsonObject();
                for (String room : Set.copyOf(rooms)) {
                    JsonArray nodes = new JsonArray();
                    for (int i = 0; i < 2; i++) {
                        JsonObject n = new JsonObject();
                        n.addProperty("type", "WALK");
                        n.addProperty("x", 3.5 + i);
                        n.addProperty("y", 200.0);
                        n.addProperty("z", 3.5);
                        nodes.add(n);
                    }
                    JsonObject r = new JsonObject();
                    r.add("nodes", nodes);
                    routes.add(room, r);
                }
                JsonObject root = new JsonObject();
                root.addProperty("version", 1);
                root.add("routes", routes);
                Files.createDirectories(file.getParent());
                Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
                ModUnderTest.staticCall(STORE, "reload");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void teardown(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            try {
                ModUnderTest.staticCall(SIM_STATE, "leave");
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
}

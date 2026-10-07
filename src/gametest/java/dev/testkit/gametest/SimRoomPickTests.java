package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 97: the sim room picker's filters (Your routes, Size), and that a room loaded by itself has no next-room action.
 *
 * <p>killer560 (2026-10-05): "I would like a way to sort maps based off of if they have secret routes in them or
 * not, and while in a solo map an option that says something like go to a new room with 0 routes in it [...] You
 * do not need to have puzzles, blood, green, or fairy, or any 0 secret rooms in this."
 *
 * <p>The ORACLE is independent of the code under test: eligibility is read here straight out of the copied
 * {@code rooms-modern.json} (type not PUZZLE/BLOOD/ENTRANCE/FAIRY, secrets &gt; 0), and "has routes" is the route
 * file this scenario writes itself. The mod is only asked for its library's room names (the list order) and for
 * what its screens show.
 *
 * <p>Seeded: routes on the 2nd and 3rd eligible rooms in list order, and on one PUZZLE room - so the filter has a
 * route-bearing room it must still hide, and the walk from the 1st eligible room has to skip two routed rooms.
 * Every press goes through the real widget at its drawn centre, via the screen's own mouseClicked.
 *
 * <p>Since mod sim-filters (killer560, 2026-10-07: "the single room should have the same filter option"), the
 * picker's Routes, shape and Puzzles buttons are rows of the shared Filters panel on the picker's own filter
 * ({@code SimRoomFilters.PICKER}). The routes views are now chosen on that panel's "Your routes" row, and a Size 1x1
 * chip must leave exactly the rooms whose shape is 1x1. And the pause menu's "Next room with no routes" on a room
 * loaded by itself is GONE ("If i load a single room by itself [...] it shouldnt have the next room [...] work or
 * the menu thing for it"): its button must not be there and {@code /simbuild noroutes} must load nothing.
 */
public class SimRoomPickTests implements FabricClientGameTest {

    private static final String NAME = "97-sim-roompick";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String MENU = "com.killer560.hub.roomsim.SimMenuScreen";
    private static final String ROUTES = "com.killer560.hub.roomsim.SimRoomRoutes";
    private static final String STORE = "com.killer560.hub.autoroutes.RouteStore";
    private static final String FILTERS = "com.killer560.hub.roomsim.SimRoomFilters";
    private static final String FILTER_SCREEN = "com.killer560.hub.roomsim.SimRoomFilterScreen";

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
        List<String> excluded = new ArrayList<>();
        String puzzle = null;
        for (String n : library) {
            if (oracleEligible(db.get(n))) {
                eligible.add(n);
            } else {
                excluded.add(n);
                if (puzzle == null && db.get(n) != null && "PUZZLE".equals(type(db.get(n)))) {
                    puzzle = n;
                }
            }
        }
        System.out.println("[" + NAME + "] library " + library.size() + " room(s): " + eligible.size()
                + " eligible, " + excluded.size() + " excluded (puzzle/blood/entrance/fairy/0 secrets)");
        if (eligible.size() < 8 || puzzle == null) {
            throw new AssertionError("not enough rooms to test with: " + eligible.size() + " eligible, puzzle="
                    + puzzle);
        }
        String start = eligible.get(0);
        Set<String> routed = new LinkedHashSet<>(List.of(eligible.get(1), eligible.get(2)));
        Set<String> seeded = new LinkedHashSet<>(routed);
        seeded.add(puzzle);
        writeRoutes(ctx, seeded);
        int seededNodes = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(ROUTES, "routeNodes",
                new Class<?>[]{String.class}, new Object[]{eligible.get(1)}));
        System.out.println("[" + NAME + "] seeded routes on " + seeded + "; the mod reads " + seededNodes
                + " node(s) for " + eligible.get(1));
        if (seededNodes != 2) {
            throw new AssertionError("the route store did not pick up the seeded file (" + seededNodes
                    + " nodes, expected 2) - the rest of this would be measuring nothing");
        }

        try {
            pickerPart(ctx, library, eligible, excluded, routed, puzzle);
            soloPart(ctx, library, eligible, routed, start);
            System.out.println("[" + NAME + "] PASS - the picker's Your routes and Size filters show exactly the "
                    + "right rooms, and a room loaded by itself has no next-room button or command");
        } finally {
            ctx.runOnClient(mc -> {
                try {
                    ModUnderTest.call(pickerFilter(), "clear", new Class<?>[]{}, new Object[]{});
                } catch (Throwable ignored) {
                    // cleanup never replaces the verdict
                }
            });
            writeRoutes(ctx, Set.of());
            teardown(ctx);
        }
    }

    // =========================================================================================== picker

    private void pickerPart(ClientGameTestContext ctx, List<String> library, List<String> eligible,
                            List<String> excluded, Set<String> routed, String puzzle) {
        ctx.runOnClient(mc -> ModUnderTest.call(pickerFilter(), "clear", new Class<?>[]{}, new Object[]{}));
        ctx.runOnClient(mc -> mc.execute(() -> {
            try {
                Screen s = (Screen) Class.forName(MENU).getMethod("roomPicker", Screen.class).invoke(null,
                        (Object) null);
                McCompat.setScreen(mc, s);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }));
        ctx.waitFor(mc -> McCompat.screen(mc) != null && McCompat.screen(mc).getClass().getName().equals(MENU));
        ctx.waitTicks(5);

        Map<String, List<String>> seen = new HashMap<>();
        seen.put("Any", listed(ctx));
        for (String choice : new String[]{"No routes", "Has routes", "Any"}) {
            setOnPanel(ctx, "Your routes", choice);
            seen.put(choice, listed(ctx));
            System.out.println("[" + NAME + "] picker, Your routes = " + choice + ": " + seen.get(choice).size()
                    + " room(s)" + (seen.get(choice).size() <= 6 ? " " + seen.get(choice) : "") + "; buttons "
                    + buttonLabels(ctx));
        }
        List<String> all = seen.get("Any");
        List<String> none = seen.get("No routes");
        List<String> has = seen.get("Has routes");
        if (all.size() != library.size()) {
            throw new AssertionError("Your routes = Any lists " + all.size() + " of the library's " + library.size());
        }
        List<String> expectNone = new ArrayList<>(eligible);
        expectNone.removeAll(routed);
        if (!none.equals(expectNone)) {
            throw new AssertionError("\"No routes\" lists " + none.size() + " room(s), expected " + expectNone.size()
                    + "; extra " + minus(none, expectNone) + ", missing " + minus(expectNone, none));
        }
        if (!new LinkedHashSet<>(has).equals(routed)) {
            throw new AssertionError("\"Has routes\" lists " + has + ", expected exactly " + routed
                    + " (the routed puzzle room " + puzzle + " must stay hidden)");
        }
        for (List<String> view : List.of(none, has)) {
            for (String n : view) {
                if (excluded.contains(n)) {
                    throw new AssertionError("an excluded room (puzzle/blood/entrance/fairy/0 secrets) appeared: "
                            + n);
                }
            }
        }

        // Size 1x1: exactly the rooms the mod's own shape answer (the database's shape) calls 1x1, and fewer than all.
        setOnPanel(ctx, "Size", "1x1");
        List<String> small = listed(ctx);
        List<String> expectSmall = new ArrayList<>();
        for (String n : library) {
            String shape = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(FILTERS, "shapeKey",
                    new Class<?>[]{String.class}, new Object[]{n}));
            if ("1x1".equals(shape)) {
                expectSmall.add(n);
            }
        }
        System.out.println("[" + NAME + "] picker, Size 1x1: " + small.size() + " room(s), expected "
                + expectSmall.size() + " of " + library.size());
        if (!small.equals(expectSmall) || small.size() >= library.size() || small.isEmpty()) {
            throw new AssertionError("Size 1x1 lists " + small.size() + ", expected " + expectSmall.size() + " of "
                    + library.size() + "; extra " + minus(small, expectSmall) + ", missing " + minus(expectSmall, small));
        }
        boolean labelled = buttonLabels(ctx).contains("Filters (1)");
        if (!labelled) {
            throw new AssertionError("the picker's Filters button does not show one filter: " + buttonLabels(ctx));
        }

        // Saved: Your routes on Has routes, then make the mod forget and re-read the file.
        setOnPanel(ctx, "Your routes", "Has routes");
        String saved = ctx.computeOnClient(mc -> {
            Object f = pickerFilter();
            ModUnderTest.call(f, "load", new Class<?>[]{}, new Object[]{});
            return ModUnderTest.call(f, "routes", new Class<?>[]{}, new Object[]{}) + " / 1x1 "
                    + ModUnderTest.call(f, "hasSize", new Class<?>[]{String.class}, new Object[]{"1x1"});
        });
        System.out.println("[" + NAME + "] picker filter after a reload from disk: " + saved);
        if (!"HAS / 1x1 true".equals(saved)) {
            throw new AssertionError("the picker's filter did not survive a reload from its file: " + saved);
        }
        ctx.runOnClient(mc -> ModUnderTest.call(pickerFilter(), "clear", new Class<?>[]{}, new Object[]{}));
        ctx.runOnClient(mc -> mc.execute(() -> McCompat.setScreen(mc, null)));
        ctx.waitFor(mc -> McCompat.screen(mc) == null || McCompat.screen(mc) instanceof
                net.minecraft.client.gui.screens.TitleScreen, 200);
    }

    /**
     * Opens the picker's Filters panel with its button, presses the chip {@code label} in {@code row} at its drawn
     * centre, and comes back with Done. The chip is found by its row, since "Any" is in several.
     */
    private static void setOnPanel(ClientGameTestContext ctx, String row, String label) {
        pressButtonStartingWith(ctx, "Filters");
        ctx.waitFor(mc -> McCompat.screen(mc) != null
                && McCompat.screen(mc).getClass().getName().equals(FILTER_SCREEN), 200);
        ctx.waitTicks(3);
        String result = ctx.computeOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            for (var child : s.children()) {
                if (child instanceof AbstractWidget w && w.getClass().getName().endsWith("$Chip")
                        && row.equals(ModUnderTest.call(w, "row", new Class<?>[]{}, new Object[]{}))
                        && label.equals(strip(w.getMessage().getString())) && w.visible) {
                    double x = w.getX() + w.getWidth() / 2.0;
                    double y = w.getY() + w.getHeight() / 2.0;
                    boolean took = s.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)), false);
                    s.mouseReleased(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)));
                    return took ? "ok" : "click at " + x + "," + y + " was not taken";
                }
            }
            return "no visible chip " + row + "/" + label;
        });
        if (!"ok".equals(result)) {
            throw new AssertionError(result + " on " + buttonLabels(ctx));
        }
        ctx.waitTicks(3);
        if ("Size".equals(row)) {
            // Load a Room's panel with a chip chosen, for comparing its look against Design a Map's.
            java.nio.file.Path p = ctx.takeScreenshot(dev.testkit.harness.Report.fileName(NAME + "-filter-panel"));
            dev.testkit.harness.Report.screenshot(NAME, p);
            System.out.println("[" + NAME + "] filter panel screenshot " + p);
        }
        pressButtonStartingWith(ctx, "Done");
        ctx.waitFor(mc -> McCompat.screen(mc) != null && McCompat.screen(mc).getClass().getName().equals(MENU), 200);
        ctx.waitTicks(3);
    }

    private static Object pickerFilter() {
        try {
            return Class.forName(FILTERS).getField("PICKER").get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no SimRoomFilters.PICKER in the mod under test: " + e);
        }
    }

    // =========================================================================================== solo room

    private void soloPart(ClientGameTestContext ctx, List<String> library, List<String> eligible,
                          Set<String> routed, String start) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildSingleRoom",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, start})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        ctx.waitTicks(20);
        String current = soloRoom(ctx);
        System.out.println("[" + NAME + "] solo room loaded: " + current);
        if (!start.equals(current)) {
            throw new AssertionError("loaded " + start + " but the sim reports " + current);
        }

        // The pause menu: Change Room, and no next-room button of any kind.
        ctx.runOnClient(mc -> mc.execute(() -> McCompat.setScreen(mc,
                new net.minecraft.client.gui.screens.PauseScreen(true))));
        ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.PauseScreen, 200);
        ctx.waitTicks(3);
        List<String> labels = buttonLabels(ctx);
        System.out.println("[" + NAME + "] pause menu on a room loaded by itself: " + labels);
        if (!labels.contains("Change Room") || labels.stream().anyMatch(l -> l.startsWith("Next room"))) {
            throw new AssertionError("the pause menu on a room loaded by itself should have Change Room and no "
                    + "next-room button: " + labels);
        }
        ctx.runOnClient(mc -> mc.execute(() -> McCompat.setScreen(mc, null)));
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 200);

        // /simbuild noroutes, the old button's command: loads nothing here and says why.
        long b = Scenario.simBuildCount(ctx);
        long mark = LogTap.mark();
        ctx.runOnClient(mc -> mc.player.connection.sendCommand("simbuild noroutes"));
        ctx.waitTicks(40);
        long after = Scenario.simBuildCount(ctx);
        String still = soloRoom(ctx);
        boolean said = LogTap.since(mark).stream().anyMatch(l -> l.contains("only work in All Rooms"));
        System.out.println("[" + NAME + "] /simbuild noroutes on a room loaded by itself: builds " + b + " -> " + after
                + ", room " + current + " -> " + still + ", said why " + said);
        if (after != b || !current.equals(still) || !said) {
            throw new AssertionError("/simbuild noroutes on a room loaded by itself should load nothing and say so: "
                    + "builds " + b + " -> " + after + ", room " + current + " -> " + still + ", said " + said);
        }
    }

    private static String soloRoom(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(ROUTES, "currentSoloRoom"));
    }

    // =========================================================================================== widgets

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

    /**
     * A left click at the widget's DRAWN centre, sent to the SCREEN - so it has to reach this widget through the
     * screen's own dispatch, as his click would, not by calling the widget directly.
     */
    private static void pressButtonStartingWith(ClientGameTestContext ctx, String label) {
        String result = ctx.computeOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            if (s == null) {
                return "no screen";
            }
            for (var child : s.children()) {
                if (child instanceof AbstractWidget w && strip(w.getMessage().getString()).startsWith(label)) {
                    double x = w.getX() + w.getWidth() / 2.0;
                    double y = w.getY() + w.getHeight() / 2.0;
                    boolean took = s.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)), false);
                    s.mouseReleased(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)));
                    return took ? "ok" : "click at " + x + "," + y + " was not taken";
                }
            }
            return "no button \"" + label + "\"";
        });
        if (!"ok".equals(result)) {
            throw new AssertionError(result + " on " + buttonLabels(ctx));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> listed(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            return new ArrayList<>((List<String>) ModUnderTest.call(s, "listedRooms", new Class<?>[]{},
                    new Object[]{}));
        });
    }

    private static String strip(String s) {
        String t = net.minecraft.ChatFormatting.stripFormatting(s);
        return t == null ? s : t;
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

    private static boolean oracleEligible(JsonObject o) {
        if (o == null) {
            return false;
        }
        int secrets = o.has("secrets") ? o.get("secrets").getAsInt() : 0;
        String t = type(o);
        return secrets > 0 && !t.equals("PUZZLE") && !t.equals("BLOOD") && !t.equals("ENTRANCE")
                && !t.equals("FAIRY");
    }

    /** Writes the routes file with two WALK nodes in each named room, then has the mod re-read it. */
    private static void writeRoutes(ClientGameTestContext ctx, Set<String> rooms) {
        ctx.runOnClient(mc -> {
            try {
                Path file = (Path) ModUnderTest.staticCall(STORE, "routesFile");
                JsonObject routes = new JsonObject();
                for (String room : rooms) {
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

    private static List<String> minus(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.removeAll(b);
        return out.size() > 8 ? out.subList(0, 8) : out;
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

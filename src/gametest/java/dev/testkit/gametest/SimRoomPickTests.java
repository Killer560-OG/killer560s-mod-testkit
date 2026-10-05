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
 * 97: the sim room picker's Routes filter, and the pause menu's "Next room with no routes".
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
 */
public class SimRoomPickTests implements FabricClientGameTest {

    private static final String NAME = "97-sim-roompick";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String MENU = "com.killer560.hub.roomsim.SimMenuScreen";
    private static final String ROUTES = "com.killer560.hub.roomsim.SimRoomRoutes";
    private static final String STORE = "com.killer560.hub.autoroutes.RouteStore";
    private static final String NEXT_LABEL = "Next room with no routes";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
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
            System.out.println("[" + NAME + "] PASS - the Routes filter shows exactly the right rooms and the pause "
                    + "menu walks eligible rooms with no routes");
        } finally {
            ctx.runOnClient(mc -> {
                try {
                    ModUnderTest.staticCall(ROUTES, "setFilter",
                            new Class<?>[]{ModUnderTest.enumValue(ROUTES + "$Filter", "ALL").getClass()},
                            new Object[]{ModUnderTest.enumValue(ROUTES + "$Filter", "ALL")});
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
        for (int press = 0; press < 3; press++) {
            String label = routesButtonLabel(ctx);
            List<String> listed = listed(ctx);
            seen.put(label, listed);
            System.out.println("[" + NAME + "] picker \"" + label + "\": " + listed.size() + " room(s)"
                    + (listed.size() <= 6 ? " " + listed : ""));
            pressButtonStartingWith(ctx, label);
            ctx.waitTicks(3);
        }
        List<String> all = seen.get("Routes: All");
        List<String> none = seen.get("No routes");
        List<String> has = seen.get("Has routes");
        if (all == null || none == null || has == null) {
            throw new AssertionError("pressing the Routes button three times did not show all three views: "
                    + seen.keySet());
        }
        if (all.size() != library.size()) {
            throw new AssertionError("\"Routes: All\" lists " + all.size() + " of the library's " + library.size());
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

        // Saved: put it on Has routes, then make the mod forget and re-read the file.
        while (!routesButtonLabel(ctx).equals("Has routes")) {
            pressButtonStartingWith(ctx, routesButtonLabel(ctx));
            ctx.waitTicks(2);
        }
        String saved = ctx.computeOnClient(mc -> {
            ModUnderTest.staticCall(ROUTES, "load");
            return String.valueOf(ModUnderTest.staticCall(ROUTES, "getFilter"));
        });
        System.out.println("[" + NAME + "] filter after a reload from disk: " + saved);
        if (!"HAS".equals(saved)) {
            throw new AssertionError("the Routes filter did not survive a reload from its file: " + saved);
        }
        ctx.runOnClient(mc -> mc.execute(() -> McCompat.setScreen(mc, null)));
        ctx.waitFor(mc -> McCompat.screen(mc) == null || McCompat.screen(mc) instanceof
                net.minecraft.client.gui.screens.TitleScreen, 200);
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

        List<String> walked = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String expected = oracleNext(library, eligible, routed, current);
            openPause(ctx);
            long b = Scenario.simBuildCount(ctx);
            pressButtonStartingWith(ctx, NEXT_LABEL);
            Scenario.awaitSimBuild(ctx, b);
            ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
            ctx.waitTicks(20);
            String now = soloRoom(ctx);
            int nodes = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(ROUTES, "routeNodes",
                    new Class<?>[]{String.class}, new Object[]{now}));
            System.out.println("[" + NAME + "] press " + (i + 1) + ": " + current + " -> " + now + " (expected "
                    + expected + ", " + nodes + " route node(s))");
            if (now == null || now.equals(current)) {
                throw new AssertionError("press " + (i + 1) + " did not change the room (" + current + " -> " + now
                        + ")");
            }
            if (!eligible.contains(now) || routed.contains(now) || nodes != 0) {
                throw new AssertionError("press " + (i + 1) + " loaded " + now + ", which is not an eligible room "
                        + "with no routes");
            }
            if (!now.equals(expected)) {
                throw new AssertionError("press " + (i + 1) + " loaded " + now + "; list order after " + current
                        + " is " + expected);
            }
            walked.add(now);
            current = now;
        }
        if (walked.containsAll(routed) || walked.stream().anyMatch(routed::contains)) {
            throw new AssertionError("the walk entered a routed room: " + walked);
        }

        // ---- none left: route every eligible room but the one standing --------------------------------
        Set<String> everyOther = new LinkedHashSet<>(eligible);
        everyOther.remove(current);
        writeRoutes(ctx, everyOther);
        openPause(ctx);
        long b = Scenario.simBuildCount(ctx);
        pressButtonStartingWith(ctx, NEXT_LABEL);
        ctx.waitTicks(40);
        String label = buttonLabels(ctx).stream().filter(l -> l.startsWith("No other room")).findFirst()
                .orElse(null);
        long after = Scenario.simBuildCount(ctx);
        String still = soloRoom(ctx);
        System.out.println("[" + NAME + "] with every other room routed: button now \"" + label + "\", builds "
                + b + " -> " + after + ", still in " + still);
        if (label == null || after != b || !current.equals(still)) {
            throw new AssertionError("with no room left the button should say so and load nothing; label=" + label
                    + ", builds " + b + " -> " + after + ", room " + current + " -> " + still);
        }
        ctx.runOnClient(mc -> mc.execute(() -> McCompat.setScreen(mc, null)));
    }

    private static String oracleNext(List<String> library, List<String> eligible, Set<String> routed,
                                     String current) {
        int at = library.indexOf(current);
        for (int k = 1; k <= library.size(); k++) {
            String n = library.get((at + k) % library.size());
            if (!n.equals(current) && eligible.contains(n) && !routed.contains(n)) {
                return n;
            }
        }
        return null;
    }

    private static void openPause(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.execute(() -> McCompat.setScreen(mc,
                new net.minecraft.client.gui.screens.PauseScreen(true))));
        ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.PauseScreen, 200);
        ctx.waitTicks(3);
        List<String> labels = buttonLabels(ctx);
        if (labels.stream().noneMatch(l -> l.equals(NEXT_LABEL)) || !labels.contains("Change Room")) {
            throw new AssertionError("the pause menu in a solo room has no \"" + NEXT_LABEL + "\" beside Change Room: "
                    + labels);
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

    private static String routesButtonLabel(ClientGameTestContext ctx) {
        for (String l : buttonLabels(ctx)) {
            if (l.equals("Routes: All") || l.equals("No routes") || l.equals("Has routes")) {
                return l;
            }
        }
        throw new AssertionError("no Routes button on the room picker: " + buttonLabels(ctx));
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

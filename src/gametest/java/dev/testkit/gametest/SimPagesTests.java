package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 97-sim-pages: the Dungeon Sim menu's pages (killer560, 2026-10-15).
 *
 * <ul>
 *   <li>"remove the pick what to practice" - the menu opens straight onto a page: its mode is never the old HOME, and
 *       nothing it draws says "Pick what to practice".</li>
 *   <li>Route practice: "remove the top text and the no puzzles blood text stuff. Have the order be all rooms no routes
 *       with routes." - neither line is drawn, and the three set buttons run All Rooms, Rooms Without Routes, Rooms
 *       With Auto Routes top to bottom.</li>
 *   <li>Load a Room: "my mouse can be all the way out to the right side of the page not hovering anything and it will
 *       still select a room" - a click right of the rows loads nothing, a click on a row loads exactly that room. "have
 *       a sort button as well as filter for things like secret count room size" - Sort by Secrets orders the list by
 *       the room DATABASE's secret count (read here from rooms-modern.json, not from the mod), a heading click turns
 *       it round, Size sorts by tiles, and the page's own Size and Secrets chips leave exactly the matching rooms.</li>
 * </ul>
 *
 * <p>Every press goes through the screen's own mouseClicked at the widget's drawn center. Failures are collected and
 * all reported, so one run on the old jar shows every part that is missing.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimPagesTests implements FabricClientGameTest {

    private static final String NAME = "97-sim-pages";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String MENU = "com.killer560.hub.roomsim.SimMenuScreen";
    private static final String ROUTES = "com.killer560.hub.roomsim.SimRoomRoutes";
    private static final String FILTERS = "com.killer560.hub.roomsim.SimRoomFilters";
    private static final String FILTER = "com.killer560.hub.roomsim.SimRoomFilter";

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
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomdatabase.RoomDatabase",
                "isReady"), 2400);
        resetPrefs(ctx);
        clearPicker(ctx);

        List<String> failures = new ArrayList<>();
        try {
            openingPart(ctx, failures);
            practicePart(ctx, failures);
            pickerPart(ctx, failures);
            clickPart(ctx, failures);
        } finally {
            clearPicker(ctx);
            resetPrefs(ctx);
            teardown(ctx);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s): " + String.join(" | ", failures));
        }
        System.out.println("[" + NAME + "] PASS - the menu opens on a page, route practice has no top/exclusion text "
                + "and lists All Rooms first, the room list sorts and filters by the database's numbers, and only a "
                + "click on a row loads it");
    }

    // =========================================================================================== opening

    private void openingPart(ClientGameTestContext ctx, List<String> failures) {
        openMenu(ctx, false);
        String mode = ctx.computeOnClient(mc -> String.valueOf(field(McCompat.screen(mc), "mode")));
        List<String> texts = drawnTexts(ctx);
        boolean pickLine = texts.stream().anyMatch(t -> t.contains("Pick what to practice"));
        System.out.println("[" + NAME + "] opened on mode " + mode + ", draws \"Pick what to practice\": " + pickLine
                + "; buttons " + buttonLabels(ctx));
        check(failures, !"HOME".equals(mode), "the menu opened on the in-between HOME page");
        check(failures, !pickLine, "the menu still draws \"Pick what to practice\"");
    }

    // =========================================================================================== route practice

    private void practicePart(ClientGameTestContext ctx, List<String> failures) {
        String nav = buttonLabels(ctx).contains("Route Practice") ? "Route Practice" : "All Rooms (route practice)";
        pressButton(ctx, nav, true);
        ctx.waitTicks(3);
        for (int i = 0; i < 100 && buttonLabels(ctx).stream().anyMatch(l -> l.contains("loading")); i++) {
            ctx.waitTicks(2);
        }
        Map<String, Integer> y = ctx.computeOnClient(mc -> {
            Map<String, Integer> out = new HashMap<>();
            for (var child : McCompat.screen(mc).children()) {
                if (child instanceof AbstractWidget w) {
                    String l = strip(w.getMessage().getString());
                    for (String opt : new String[]{"Rooms Without Routes (", "All Rooms (", "Rooms With Auto Routes ("}) {
                        if (l.startsWith(opt) && !l.contains("route practice")) {
                            out.put(opt.substring(0, opt.length() - 2), w.getY());
                        }
                    }
                }
            }
            return out;
        });
        List<String> texts = drawnTexts(ctx);
        boolean top = texts.stream().anyMatch(t -> t.contains("One room at a time"));
        boolean exclusion = texts.stream().anyMatch(t -> t.contains("No puzzles, Blood"));
        System.out.println("[" + NAME + "] route practice: set buttons at y " + y + ", top line " + top
                + ", exclusion line " + exclusion);
        Integer all = y.get("All Rooms");
        Integer none = y.get("Rooms Without Routes");
        Integer has = y.get("Rooms With Auto Routes");
        check(failures, all != null && none != null && has != null, "route practice is missing a set button: " + y);
        if (all != null && none != null && has != null) {
            check(failures, all < none && none < has,
                    "route practice order is not All Rooms, Rooms Without Routes, Rooms With Auto Routes: " + y);
        }
        check(failures, !top, "route practice still draws \"One room at a time - /next and /back...\"");
        check(failures, !exclusion, "route practice still draws \"No puzzles, Blood, Entrance, Fairy...\"");
    }

    // =========================================================================================== room list

    private void pickerPart(ClientGameTestContext ctx, List<String> failures) {
        openMenu(ctx, true);
        Map<String, JsonObject> db = readDatabase();
        List<String> all = listed(ctx);
        System.out.println("[" + NAME + "] Load a Room lists " + all.size() + " room(s); buttons " + buttonLabels(ctx));
        ctx.takeScreenshot(dev.testkit.harness.Report.fileName(NAME + "-rooms"));
        boolean hasSort = buttonLabels(ctx).stream().anyMatch(l -> l.startsWith("Sort:"));
        check(failures, hasSort, "Load a Room has no Sort button");
        if (hasSort) {
            // Name -> Type -> Size -> Secrets: three left clicks on the cycling button.
            for (int i = 0; i < 6 && !label(ctx, "Sort:").startsWith("Sort: Secrets"); i++) {
                pressButton(ctx, "Sort:", false);
                if (label(ctx, "Sort:").startsWith("Sort: Size")) {
                    List<String> bySize = listed(ctx);
                    int bad = firstOutOfOrder(bySize, n -> tiles(shapeKey(ctx, n)), false);
                    System.out.println("[" + NAME + "] Sort: Size - first out of order at " + bad + " of "
                            + bySize.size());
                    check(failures, bad < 0 && bySize.size() == all.size(),
                            "Sort: Size is not smallest first by tiles at row " + bad);
                }
            }
            List<String> bySecrets = listed(ctx);
            int bad = firstOutOfOrder(bySecrets, n -> secrets(db, n), true);
            System.out.println("[" + NAME + "] " + label(ctx, "Sort:") + ": first five " + head(bySecrets, db)
                    + ", first out of order at " + bad);
            check(failures, label(ctx, "Sort:").startsWith("Sort: Secrets"), "Sort never reached Secrets");
            check(failures, bad < 0 && bySecrets.size() == all.size(),
                    "Sort: Secrets is not most secrets first (database count) at row " + bad);
            int[] head = ctx.computeOnClient(mc -> (int[]) ModUnderTest.call(McCompat.screen(mc), "columnHeaderBounds",
                    new Class<?>[]{String.class}, new Object[]{"SECRETS"}));
            if (head == null) {
                failures.add("no Secrets column heading");
            } else {
                pressAt(ctx, head[0] + head[2] / 2.0, head[1] + head[3] / 2.0);
                List<String> up = listed(ctx);
                int badUp = firstOutOfOrder(up, n -> secrets(db, n), false);
                System.out.println("[" + NAME + "] Secrets heading clicked: " + label(ctx, "Sort:") + ", first five "
                        + head(up, db) + ", first out of order at " + badUp);
                check(failures, badUp < 0, "a click on the Secrets heading did not turn the order to fewest first");
            }
            // Back to Name so the filter checks below compare against library order.
            for (int i = 0; i < 8 && !label(ctx, "Sort:").startsWith("Sort: Name"); i++) {
                pressButton(ctx, "Sort:", false);
            }
        }

        // The page's own Size chip: exactly the rooms the mod's shape answer calls 1x2.
        if (!buttonLabels(ctx).contains("1x2")) {
            check(failures, false, "Load a Room has no Size chips on the page");
        } else {
            pressButton(ctx, "1x2", false);
            List<String> got = listed(ctx);
            List<String> want = new ArrayList<>();
            for (String n : all) {
                if ("1x2".equals(shapeKey(ctx, n))) {
                    want.add(n);
                }
            }
            System.out.println("[" + NAME + "] Size chip 1x2: " + got.size() + " room(s), expected " + want.size());
            check(failures, !want.isEmpty() && new java.util.HashSet<>(got).equals(new java.util.HashSet<>(want)),
                    "the 1x2 chip lists " + got.size() + " room(s), expected " + want.size());
            pressButton(ctx, "1x2", false);
            check(failures, listed(ctx).size() == all.size(), "turning the 1x2 chip off did not bring every room back");
        }
        if (buttonLabels(ctx).stream().noneMatch(l -> l.startsWith("Secrets: "))) {
            check(failures, false, "Load a Room has no Secrets chip on the page");
        } else {
            pressButton(ctx, "Secrets: ", false);   // Any -> None
            pressButton(ctx, "Secrets: ", false);   // None -> 1 to 3
            String l = label(ctx, "Secrets: ");
            List<String> got = listed(ctx);
            List<String> want = new ArrayList<>();
            for (String n : all) {
                int s = secrets(db, n);
                if (s >= 1 && s <= 3) {
                    want.add(n);
                }
            }
            System.out.println("[" + NAME + "] " + l + ": " + got.size() + " room(s), database says " + want.size());
            check(failures, l.equals("Secrets: 1 to 3") && new java.util.HashSet<>(got).equals(
                    new java.util.HashSet<>(want)), "the Secrets chip at " + l + " lists " + got.size()
                    + " room(s), the database has " + want.size() + " with 1 to 3");
            clearPicker(ctx);
        }
    }

    // =========================================================================================== clicks

    private void clickPart(ClientGameTestContext ctx, List<String> failures) {
        openMenu(ctx, true);
        List<String> rooms = listed(ctx);
        if (rooms.isEmpty()) {
            failures.add("Load a Room lists nothing to click");
            return;
        }
        // Row 0's band: the new screen says where; the old one drew rows from panelY + 80, 14 high.
        int[] row = ctx.computeOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            try {
                return (int[]) ModUnderTest.call(s, "roomRowBounds", new Class<?>[]{int.class}, new Object[]{0});
            } catch (Throwable t) {
                int panelY = (Integer) field(s, "panelY");
                int panelX = (Integer) field(s, "panelX");
                int panelW = (Integer) field(s, "panelW");
                return new int[]{panelX + 6, panelY + 80, panelW - 12, 14};
            }
        });
        int width = ctx.computeOnClient(mc -> McCompat.screen(mc).width);
        double y = row[1] + row[3] / 2.0;
        long before = Scenario.simBuildCount(ctx);
        // His click: level with a row, far out to the right of the panel, over nothing.
        double farRight = Math.min(width - 2, row[0] + row[2] + 60);
        pressAt(ctx, farRight, y);
        ctx.waitTicks(10);
        boolean stillOpen = ctx.computeOnClient(mc -> McCompat.screen(mc) != null
                && McCompat.screen(mc).getClass().getName().equals(MENU));
        boolean simOn = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_STATE, "isActive"));
        System.out.println("[" + NAME + "] click at x " + farRight + " (row 0 spans x " + row[0] + ".." + (row[0] + row[2])
                + ", screen " + width + ") y " + y + ": menu still open " + stillOpen + ", sim started " + simOn);
        check(failures, stillOpen && !simOn, "a click right of the rows (x " + farRight + ") loaded a room");
        if (!stillOpen || simOn) {
            // The stray click is opening a sim world: let it finish before leaving it, or the teardown races it.
            ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
            Scenario.awaitSimBuild(ctx, before);
            teardown(ctx);
            openMenu(ctx, true);
            before = Scenario.simBuildCount(ctx);
        }
        // And on the row: loads exactly that room.
        String want = listed(ctx).get(0);
        pressAt(ctx, row[0] + Math.min(40, row[2] / 2.0), y);
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitTicks(20);
        String loaded = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(ROUTES, "currentSoloRoom"));
        System.out.println("[" + NAME + "] click on row 0 (" + want + "): loaded " + loaded);
        check(failures, want.equals(loaded), "a click on row 0 (" + want + ") loaded " + loaded);
    }

    // =========================================================================================== helpers

    private static void check(List<String> failures, boolean ok, String message) {
        if (!ok) {
            failures.add(message);
            System.out.println("[" + NAME + "] FAIL " + message);
        }
    }

    private static void openMenu(ClientGameTestContext ctx, boolean picker) {
        ctx.runOnClient(mc -> mc.execute(() -> {
            try {
                Screen s = picker
                        ? (Screen) Class.forName(MENU).getMethod("roomPicker", Screen.class).invoke(null, (Object) null)
                        : (Screen) Class.forName(MENU).getConstructor(Screen.class).newInstance((Object) null);
                McCompat.setScreen(mc, s);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }));
        ctx.waitFor(mc -> McCompat.screen(mc) != null && McCompat.screen(mc).getClass().getName().equals(MENU), 400);
        ctx.waitTicks(5);
        if (picker) {
            for (int i = 0; i < 100 && listed(ctx).isEmpty(); i++) {
                ctx.waitTicks(2);
            }
        }
    }

    /** What the open screen draws, as text runs, rendered into a fresh state. */
    private static List<String> drawnTexts(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            GuiRenderState state = new GuiRenderState();
            GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
            s.extractRenderState(g, -1, -1, 0f);
            List<String> out = new ArrayList<>();
            state.forEachText(t -> out.add(TextRuns.string(t)));
            return out;
        });
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

    private static String label(ClientGameTestContext ctx, String prefix) {
        for (String l : buttonLabels(ctx)) {
            if (l.startsWith(prefix)) {
                return l;
            }
        }
        return "";
    }

    /** A left click at the drawn center of the first widget labeled {@code label} (or starting with it). */
    private static void pressButton(ClientGameTestContext ctx, String label, boolean exact) {
        String result = ctx.computeOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            for (var child : s.children()) {
                if (child instanceof AbstractWidget w) {
                    String l = strip(w.getMessage().getString());
                    if (exact ? l.equals(label) : l.startsWith(label)) {
                        double x = w.getX() + w.getWidth() / 2.0;
                        double y = w.getY() + w.getHeight() / 2.0;
                        s.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)), false);
                        s.mouseReleased(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)));
                        return "ok";
                    }
                }
            }
            return "no button \"" + label + "\"";
        });
        if (!"ok".equals(result)) {
            throw new AssertionError(result + " on " + buttonLabels(ctx));
        }
        ctx.waitTicks(3);
    }

    private static void pressAt(ClientGameTestContext ctx, double x, double y) {
        ctx.runOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            s.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)), false);
            if (McCompat.screen(mc) == s) {
                s.mouseReleased(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)));
            }
        });
        ctx.waitTicks(3);
    }

    @SuppressWarnings("unchecked")
    private static List<String> listed(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> new ArrayList<>((List<String>) ModUnderTest.call(McCompat.screen(mc),
                "listedRooms", new Class<?>[]{}, new Object[]{})));
    }

    private static Object field(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                // try the superclass
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("no field " + name + " on " + target.getClass().getName());
    }

    private static String strip(String s) {
        String t = net.minecraft.ChatFormatting.stripFormatting(s);
        return t == null ? s : t;
    }

    private static String shapeKey(ClientGameTestContext ctx, String name) {
        return ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(FILTERS, "shapeKey",
                new Class<?>[]{String.class}, new Object[]{name}));
    }

    private static int tiles(String shape) {
        return switch (shape) {
            case "1x2" -> 2;
            case "1x3", "L" -> 3;
            case "1x4", "2x2" -> 4;
            default -> 1;
        };
    }

    /** The first index whose key breaks the order (descending or ascending), or -1. */
    private static int firstOutOfOrder(List<String> list, java.util.function.ToIntFunction<String> key,
                                       boolean descending) {
        for (int i = 1; i < list.size(); i++) {
            int a = key.applyAsInt(list.get(i - 1));
            int b = key.applyAsInt(list.get(i));
            if (descending ? b > a : b < a) {
                return i;
            }
        }
        return -1;
    }

    private static String head(List<String> list, Map<String, JsonObject> db) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(5, list.size()); i++) {
            out.add(list.get(i) + "=" + secrets(db, list.get(i)));
        }
        return out.toString();
    }

    private static Map<String, JsonObject> readDatabase() {
        try {
            Path file = ModUnderTest.modConfig("killer560smod-roomdata").resolve("rooms-modern.json");
            JsonArray arr = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray();
            Map<String, JsonObject> out = new HashMap<>();
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                out.put(o.get("name").getAsString().toLowerCase(Locale.ROOT), o);
            }
            return out;
        } catch (Exception e) {
            throw new AssertionError("could not read the copied room database: " + e);
        }
    }

    /** The database's secret count, 0 for a room it does not know (as the mod reads an unknown room). */
    private static int secrets(Map<String, JsonObject> db, String name) {
        JsonObject o = db.get(name.toLowerCase(Locale.ROOT));
        return o != null && o.has("secrets") ? o.get("secrets").getAsInt() : 0;
    }

    private static void clearPicker(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            try {
                Object picker = Class.forName(FILTERS).getField("PICKER").get(null);
                ModUnderTest.call(picker, "clear", new Class<?>[]{}, new Object[]{});
            } catch (Throwable ignored) {
                // cleanup never replaces the verdict
            }
        });
    }

    /** Forgets the menu's remembered page and sorts, so later scenarios see the defaults. */
    private static void resetPrefs(ClientGameTestContext ctx) {
        try {
            Files.deleteIfExists(ModUnderTest.modConfig("killer560smod-sim-menu.json"));
        } catch (Exception ignored) {
            // nothing to forget
        }
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

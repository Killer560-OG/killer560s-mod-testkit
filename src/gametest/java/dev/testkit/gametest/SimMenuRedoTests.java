package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 97-sim-menu-redo: the Dungeon Sim menu redo (killer560, 2026-10-15).
 *
 * <ul>
 *   <li>"Clicking Dungeon Sim opens Create a New Map by default" - the title screen's real Dungeon Sim button, pressed
 *       with the mouse in a session that has not opened the menu, opens the map designer; the designer shows Previous
 *       Runs, Load a Room and Route Practice; Route Practice opens that page; after the menu is closed, the same button
 *       opens Route Practice again (the last page this session).</li>
 *   <li>"Remove the 134 rooms captured text" and "remove the page title text in the top right of the header" - no page
 *       draws "N rooms captured" / "No rooms loaded", nor any text in the right half of the header bar.</li>
 *   <li>Sorted by Routes: every "-" room, then every "no routes" room, then rooms with nodes by count; the heading clicked
 *       again turns all of it round. Read from the mod's own route answers (SimRoomRoutes) per listed room.</li>
 *   <li>"really hard to tell which filters are selected" - the 1x2 chip turned on is sampled from a real screenshot
 *       against the 1x1 chip left off: their faces must differ clearly, as must the Secrets chip on "1 to 3" against
 *       Kind on Any, and the Filters button with filters on against off. With 1x2, 1x3 and Secrets chosen, the button
 *       reads "Filters (3)": one per lit chip.</li>
 *   <li>Route Practice's set buttons are sized to their labels, not the page width.</li>
 * </ul>
 *
 * <p>Screenshots of every page (and Load a Room under the Light theme) are kept as 97-sim-menu-redo-*.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimMenuRedoTests implements FabricClientGameTest {

    private static final String NAME = "97-sim-menu-redo";
    private static final String MENU = "com.killer560.hub.roomsim.SimMenuScreen";
    private static final String EDITOR = "com.killer560.hub.roomsim.SimMapEditorScreen";
    private static final String NAV = "com.killer560.hub.roomsim.SimMenuNav";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String ROUTES = "com.killer560.hub.roomsim.SimRoomRoutes";
    private static final String FILTERS = "com.killer560.hub.roomsim.SimRoomFilters";
    private static final Pattern CAPTURED = Pattern.compile("(?i)\\d+ rooms captured|no rooms loaded");

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
        resetPrefs();
        clearPicker(ctx);
        // A fresh game session as far as the menu knows (an earlier scenario in this client may have opened it).
        ctx.runOnClient(mc -> {
            try {
                Class.forName(NAV).getMethod("forgetSession").invoke(null);
            } catch (ReflectiveOperationException e) {
                System.out.println("[" + NAME + "] no SimMenuNav.forgetSession in this jar: " + e);
            }
        });

        List<String> failures = new ArrayList<>();
        // A real monitor's size (1920x1080 at GUI scale 2, a 960x540 screen): the gametest window's own framebuffer is
        // cropped by takeScreenshot, and the pixel checks need the whole panel in the shot.
        int[] oldWindow = ctx.computeOnClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        int oldGui = ctx.computeOnClient(mc -> mc.options.guiScale().get());
        try {
            ctx.getInput().resizeWindow(1920, 1080);
            ctx.waitTicks(3);
            ctx.runOnClient(mc -> {
                mc.options.guiScale().set(2);
                mc.resizeGui();
            });
            ctx.waitTicks(3);
            openingPart(ctx, failures);
            pickerPart(ctx, failures);
            previousPart(ctx, failures);
            lightPart(ctx, failures);
            smallPart(ctx, failures);
        } finally {
            ctx.runOnClient(mc -> mc.options.guiScale().set(oldGui));
            ctx.getInput().resizeWindow(oldWindow[0], oldWindow[1]);
            ctx.waitTicks(3);
            ctx.runOnClient(mc -> mc.resizeGui());
            setTheme(ctx, "AMBER");
            clearPicker(ctx);
            resetPrefs();
            ctx.runOnClient(mc -> mc.execute(() -> McCompat.setScreen(mc, new TitleScreen())));
            ctx.waitTicks(5);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s): " + String.join(" | ", failures));
        }
        System.out.println("[" + NAME + "] PASS - Dungeon Sim opens the designer first and the last page after, no room "
                + "count or header page title, Routes sorts - / no routes / nodes, chosen chips are drawn lit and "
                + "counted, and route practice buttons fit their labels");
    }

    // =========================================================================================== opening

    private void openingPart(ClientGameTestContext ctx, List<String> failures) {
        String how = pressDungeonSim(ctx);
        String first = screenName(ctx);
        System.out.println("[" + NAME + "] first open (" + how + "): " + first + ", controls " + DesignerKit.labels(ctx));
        shot(ctx, "designer");
        check(failures, EDITOR.equals(first), "the first Dungeon Sim open this session showed " + first
                + ", not the map designer");
        noCountNoTitle(ctx, failures, "first open");
        if (!EDITOR.equals(first)) {
            return;
        }
        List<String> labels = DesignerKit.labels(ctx);
        for (String page : new String[]{"Previous Runs", "Load a Room", "Route Practice"}) {
            check(failures, labels.contains(page), "the designer has no way to " + page + ": " + labels);
        }
        // Route Practice from the designer's own column.
        boolean clicked = DesignerKit.click(ctx, "Route Practice");
        ctx.waitTicks(5);
        String page = mode(ctx);
        System.out.println("[" + NAME + "] Route Practice from the designer: clicked " + clicked + ", now " + screenName(ctx)
                + " mode " + page);
        check(failures, clicked && MENU.equals(screenName(ctx)) && "CYCLE".equals(page),
                "Route Practice on the designer did not open the route practice page (" + screenName(ctx) + " " + page + ")");
        for (int i = 0; i < 100 && DesignerKit.labels(ctx).stream().noneMatch(l -> l.startsWith("All Rooms (")); i++) {
            ctx.waitTicks(2);
        }
        shot(ctx, "practice");
        noCountNoTitle(ctx, failures, "route practice");
        // The set buttons: sized to their labels, not stretched across the page.
        int[] sizes = ctx.computeOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            int contentW = (Integer) DesignerKit.reflect(s, "contentW");
            int widest = 0;
            int labelW = 0;
            for (var ch : s.children()) {
                if (ch instanceof AbstractWidget w) {
                    String l = DesignerKit.label(w);
                    if (l.startsWith("All Rooms (") || l.startsWith("Rooms Without Routes (")
                            || l.startsWith("Rooms With Auto Routes (")) {
                        widest = Math.max(widest, w.getWidth());
                        labelW = Math.max(labelW, mc.font.width(l));
                    }
                }
            }
            return new int[]{widest, labelW, contentW};
        });
        System.out.println("[" + NAME + "] practice set buttons: widest " + sizes[0] + ", longest label " + sizes[1]
                + ", page width " + sizes[2]);
        check(failures, sizes[0] > 0 && sizes[0] >= sizes[1] + 8 && sizes[0] <= Math.max(200, sizes[1] + 60)
                        && sizes[0] < sizes[2] * 0.75,
                "the practice set buttons are " + sizes[0] + " wide for a " + sizes[1] + "-unit label on a " + sizes[2]
                        + "-unit page - not sized to their labels");

        // Close the menu the way he does (Esc), then the same button again: back on Route Practice.
        ctx.runOnClient(mc -> McCompat.screen(mc).onClose());
        ctx.waitTicks(5);
        String closedTo = screenName(ctx);
        String how2 = pressDungeonSim(ctx);
        String again = screenName(ctx);
        String againMode = MENU.equals(again) ? mode(ctx) : "-";
        System.out.println("[" + NAME + "] Esc -> " + closedTo + "; Dungeon Sim again (" + how2 + "): " + again + " mode "
                + againMode);
        check(failures, MENU.equals(again) && "CYCLE".equals(againMode), "reopening the sim menu showed " + again + " "
                + againMode + ", not Route Practice, the page it was last on");
    }

    // =========================================================================================== Load a Room

    private void pickerPart(ClientGameTestContext ctx, List<String> failures) {
        if (!MENU.equals(screenName(ctx))) {
            openMenuDirect(ctx);
        }
        boolean clicked = DesignerKit.click(ctx, "Load a Room");
        ctx.waitTicks(5);
        for (int i = 0; i < 100 && listed(ctx).isEmpty(); i++) {
            ctx.waitTicks(2);
        }
        System.out.println("[" + NAME + "] Load a Room clicked " + clicked + ": mode " + mode(ctx) + ", " + listed(ctx).size()
                + " rooms, controls " + DesignerKit.labels(ctx));
        check(failures, "ROOM".equals(mode(ctx)), "Load a Room did not open the room list");
        noCountNoTitle(ctx, failures, "Load a Room");
        shot(ctx, "room");
        routesPart(ctx, failures);
        chipsPart(ctx, failures);
    }

    private void routesPart(ClientGameTestContext ctx, List<String> failures) {
        int[] head = ctx.computeOnClient(mc -> (int[]) ModUnderTest.call(McCompat.screen(mc), "columnHeaderBounds",
                new Class<?>[]{String.class}, new Object[]{"ROUTES"}));
        if (head == null) {
            failures.add("no Routes column heading");
            return;
        }
        int all = listed(ctx).size();
        for (int round = 0; round < 2; round++) {
            DesignerKit.clickAt(ctx, head[0] + head[2] / 2.0, head[1] + head[3] / 2.0, 0);
            boolean descending = ctx.computeOnClient(mc -> {
                Object prefs = DesignerKit.reflect(McCompat.screen(mc), "prefs");
                return (Boolean) DesignerKit.reflect(prefs, "roomDescending");
            });
            String sort = ctx.computeOnClient(mc -> String.valueOf(DesignerKit.reflect(
                    DesignerKit.reflect(McCompat.screen(mc), "prefs"), "roomSort")));
            List<String> rooms = listed(ctx);
            int[] counts = new int[3];
            int bad = -1;
            int prev = descending ? Integer.MAX_VALUE : Integer.MIN_VALUE;
            for (int i = 0; i < rooms.size(); i++) {
                int rank = rank(ctx, rooms.get(i));
                counts[Math.min(2, rank)]++;
                if (bad < 0 && (descending ? rank > prev : rank < prev)) {
                    bad = i;
                }
                prev = rank;
            }
            System.out.println("[" + NAME + "] sort " + sort + (descending ? " descending" : " ascending") + ": "
                    + counts[0] + " '-', " + counts[1] + " 'no routes', " + counts[2] + " with nodes; first out of order "
                    + "at " + bad + (bad >= 0 ? " (" + rooms.get(bad) + ")" : ""));
            check(failures, "ROUTES".equals(sort), "the Routes heading did not sort by Routes (" + sort + ")");
            check(failures, counts[0] > 0 && counts[1] > 0, "the Routes check measured nothing: " + counts[0] + " '-' and "
                    + counts[1] + " 'no routes' rooms");
            check(failures, rooms.size() == all, "sorting changed the room count");
            check(failures, bad < 0, "sorted by Routes " + (descending ? "descending" : "ascending") + ", row " + bad
                    + " (" + (bad >= 0 ? rooms.get(bad) : "") + ") breaks '-' / 'no routes' / nodes order");
            if (round == 0) {
                shot(ctx, "room-routes-" + (descending ? "desc" : "asc"));
            }
        }
    }

    /** "-" 0, "no routes" 1, a room with nodes 2 + nodes; read from the mod's route answers, not the screen's. */
    private static int rank(ClientGameTestContext ctx, String room) {
        return ctx.computeOnClient(mc -> {
            int nodes = (Integer) ModUnderTest.staticCall(ROUTES, "routeNodes", new Class<?>[]{String.class},
                    new Object[]{room});
            if (nodes > 0) {
                return 2 + nodes;
            }
            boolean eligible = (Boolean) ModUnderTest.staticCall(ROUTES, "isEligible", new Class<?>[]{String.class},
                    new Object[]{room});
            return eligible ? 1 : 0;
        });
    }

    private void chipsPart(ClientGameTestContext ctx, List<String> failures) {
        clearPicker(ctx);
        rebuild(ctx);
        BufferedImage off = shot(ctx, "room-chips-off");
        int[] filtersOff = face(ctx, off, "Filters");
        // On: 1x2 and 1x3 chips, and Secrets stepped Any -> None -> 1 to 3.
        DesignerKit.click(ctx, "1x2");
        DesignerKit.click(ctx, "1x3");
        clickPrefix(ctx, "Secrets: ");
        clickPrefix(ctx, "Secrets: ");
        ctx.waitTicks(3);
        BufferedImage on = shot(ctx, "room-chips-on");
        int[] oneByOne = face(ctx, on, "1x1");
        int[] oneByTwo = face(ctx, on, "1x2");
        int[] secrets = facePrefix(ctx, on, "Secrets: ");
        int[] kind = facePrefix(ctx, on, "Kind: ");
        int[] filtersOn = facePrefix(ctx, on, "Filters");
        String filtersLabel = labelPrefix(ctx, "Filters");
        String secretsLabel = labelPrefix(ctx, "Secrets: ");
        System.out.println("[" + NAME + "] chip faces: 1x1 off " + hex(oneByOne) + ", 1x2 on " + hex(oneByTwo)
                + "; " + secretsLabel + " " + hex(secrets) + " vs Kind Any " + hex(kind) + "; Filters off " + hex(filtersOff)
                + " vs '" + filtersLabel + "' " + hex(filtersOn));
        check(failures, distance(oneByOne, oneByTwo) >= 90, "the 1x2 chip on (" + hex(oneByTwo) + ") is drawn like the "
                + "1x1 chip off (" + hex(oneByOne) + ")");
        check(failures, "Secrets: 1 to 3".equals(secretsLabel), "the Secrets chip read " + secretsLabel);
        check(failures, distance(secrets, kind) >= 90, "the Secrets chip on 1 to 3 (" + hex(secrets) + ") is drawn like "
                + "Kind on Any (" + hex(kind) + ")");
        check(failures, distance(filtersOff, filtersOn) >= 90, "the Filters button with filters on (" + hex(filtersOn)
                + ") is drawn like with none (" + hex(filtersOff) + ")");
        check(failures, "Filters (3)".equals(filtersLabel), "with three chips lit the button reads '" + filtersLabel
                + "', not 'Filters (3)'");
        clearPicker(ctx);
        rebuild(ctx);
    }

    // =========================================================================================== other pages

    private void previousPart(ClientGameTestContext ctx, List<String> failures) {
        boolean clicked = DesignerKit.click(ctx, "Previous Runs");
        ctx.waitTicks(5);
        System.out.println("[" + NAME + "] Previous Runs clicked " + clicked + ": mode " + mode(ctx));
        noCountNoTitle(ctx, failures, "Previous Runs");
        shot(ctx, "previous");
        // And back to the designer from the column.
        boolean toDesigner = DesignerKit.click(ctx, "Create a New Map");
        ctx.waitTicks(5);
        System.out.println("[" + NAME + "] Create a New Map clicked " + toDesigner + ": " + screenName(ctx));
        check(failures, EDITOR.equals(screenName(ctx)), "Create a New Map in the column did not open the designer");
    }

    private void lightPart(ClientGameTestContext ctx, List<String> failures) {
        setTheme(ctx, "LIGHT");
        if (!DesignerKit.click(ctx, "Load a Room")) {
            openMenuDirect(ctx);
        }
        ctx.waitTicks(5);
        DesignerKit.click(ctx, "2x2");
        clickPrefix(ctx, "Kind: ");
        ctx.waitTicks(3);
        BufferedImage img = shot(ctx, "room-light");
        int[] onFace = face(ctx, img, "2x2");
        int[] offFace = face(ctx, img, "1x1");
        System.out.println("[" + NAME + "] Light theme: 2x2 on " + hex(onFace) + ", 1x1 off " + hex(offFace));
        check(failures, distance(onFace, offFace) >= 90, "under Light the 2x2 chip on (" + hex(onFace)
                + ") is drawn like 1x1 off (" + hex(offFace) + ")");
        DesignerKit.click(ctx, "Create a New Map");
        ctx.waitTicks(5);
        shot(ctx, "designer-light");
        clearPicker(ctx);
    }

    /** The two busiest pages at a small screen (GUI scale 4 on 1920x1080: 480x270), for the layout at its limit. */
    private void smallPart(ClientGameTestContext ctx, List<String> failures) {
        setTheme(ctx, "AMBER");
        // Auto Scale off for this part: it would scale the small screen back up and the shot would show a crop.
        Object hud = ctx.computeOnClient(mc -> ModUnderTest.config("com.killer560.hub.hud.HudConfig"));
        boolean oldAuto = ctx.computeOnClient(mc -> ModUnderTest.getBoolean(hud, "isAutoScale"));
        ctx.runOnClient(mc -> {
            ModUnderTest.set(hud, "setAutoScale", false);
            mc.options.guiScale().set(4);
            mc.resizeGui();
        });
        ctx.waitTicks(3);
        if (!EDITOR.equals(screenName(ctx))) {
            DesignerKit.click(ctx, "Create a New Map");
        }
        rebuild(ctx);
        shot(ctx, "designer-small");
        layout(ctx, failures, "small designer");
        DesignerKit.click(ctx, "Load a Room");
        ctx.waitTicks(5);
        shot(ctx, "room-small");
        layout(ctx, failures, "small Load a Room");
        DesignerKit.click(ctx, "Route Practice");
        ctx.waitTicks(5);
        shot(ctx, "practice-small");
        layout(ctx, failures, "small Route Practice");
        ctx.runOnClient(mc -> {
            ModUnderTest.set(hud, "setAutoScale", oldAuto);
            mc.options.guiScale().set(2);
            mc.resizeGui();
        });
        ctx.waitTicks(3);
    }

    /** Every control inside the panel, and no two controls' boxes intersecting. */
    private static void layout(ClientGameTestContext ctx, List<String> failures, String where) {
        List<String> problems = ctx.computeOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            List<String> out = new ArrayList<>();
            int px = (Integer) DesignerKit.reflect(s, "panelX");
            int py = (Integer) DesignerKit.reflect(s, "panelY");
            int pw = (Integer) DesignerKit.reflect(s, "panelW");
            int ph = (Integer) DesignerKit.reflect(s, "panelH");
            List<AbstractWidget> ws = new ArrayList<>();
            for (var ch : s.children()) {
                if (ch instanceof AbstractWidget w && w.visible) {
                    ws.add(w);
                }
            }
            for (int i = 0; i < ws.size(); i++) {
                AbstractWidget a = ws.get(i);
                if (a.getX() < px || a.getY() < py || a.getX() + a.getWidth() > px + pw || a.getY() + a.getHeight() > py + ph) {
                    out.add("'" + DesignerKit.label(a) + "' leaves the panel");
                }
                for (int j = i + 1; j < ws.size(); j++) {
                    AbstractWidget b = ws.get(j);
                    if (a.getX() < b.getX() + b.getWidth() && b.getX() < a.getX() + a.getWidth()
                            && a.getY() < b.getY() + b.getHeight() && b.getY() < a.getY() + a.getHeight()) {
                        out.add("'" + DesignerKit.label(a) + "' overlaps '" + DesignerKit.label(b) + "'");
                    }
                }
            }
            out.add(0, s.width + "x" + s.height + ", " + ws.size() + " controls");
            return out;
        });
        System.out.println("[" + NAME + "] " + where + " layout: " + problems);
        for (int i = 1; i < problems.size(); i++) {
            failures.add(where + ": " + problems.get(i));
        }
    }

    // =========================================================================================== helpers

    /** No room count anywhere, and nothing written in the right half of the header bar. */
    private static void noCountNoTitle(ClientGameTestContext ctx, List<String> failures, String where) {
        int[] panel = ctx.computeOnClient(mc -> new int[]{(Integer) DesignerKit.reflect(McCompat.screen(mc), "panelX"),
                (Integer) DesignerKit.reflect(McCompat.screen(mc), "panelY"),
                (Integer) DesignerKit.reflect(McCompat.screen(mc), "panelW")});
        List<DesignerKit.Run> runs = DesignerKit.texts(ctx);
        boolean title = false;
        for (DesignerKit.Run r : runs) {
            if (CAPTURED.matcher(r.text()).find()) {
                failures.add(where + " draws '" + r.text() + "'");
            }
            if (r.y() >= panel[1] + 4 && r.y() < panel[1] + 26 && r.x() > panel[0] + panel[2] / 2) {
                failures.add(where + " draws '" + r.text() + "' at the right of the header bar");
            }
            title |= r.text().equals("DUNGEON SIM") || r.text().equals("DESIGN A MAP");
        }
        System.out.println("[" + NAME + "] " + where + ": " + runs.size() + " text runs read, menu title found " + title);
        check(failures, title && runs.size() > 5, where + ": the text read-back measured nothing (" + runs.size()
                + " runs, title " + title + ")");
    }

    private static String pressDungeonSim(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.execute(() -> McCompat.setScreen(mc, new TitleScreen())));
        ctx.waitFor(mc -> McCompat.screen(mc) instanceof TitleScreen, 400);
        ctx.waitTicks(40);
        if (DesignerKit.click(ctx, "Dungeon Sim")) {
            ctx.waitTicks(8);
            return "title screen's Dungeon Sim button";
        }
        openMenuDirect(ctx);
        return "no Dungeon Sim button on the title screen - opened directly";
    }

    private static void openMenuDirect(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.execute(() -> {
            try {
                Screen s;
                try {
                    s = (Screen) Class.forName(MENU).getMethod("open", Screen.class).invoke(null, (Object) null);
                } catch (NoSuchMethodException e) {
                    s = (Screen) Class.forName(MENU).getConstructor(Screen.class).newInstance((Object) null);
                }
                McCompat.setScreen(mc, s);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }));
        ctx.waitTicks(8);
    }

    private static String screenName(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> McCompat.screen(mc) == null ? "none" : McCompat.screen(mc).getClass().getName());
    }

    private static String mode(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            return s != null && s.getClass().getName().equals(MENU) ? String.valueOf(DesignerKit.reflect(s, "mode")) : "-";
        });
    }

    @SuppressWarnings("unchecked")
    private static List<String> listed(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            if (s == null || !s.getClass().getName().equals(MENU)) {
                return new ArrayList<String>();
            }
            return new ArrayList<>((List<String>) ModUnderTest.call(s, "listedRooms", new Class<?>[]{}, new Object[]{}));
        });
    }

    private static void rebuild(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            s.resize(s.width, s.height);
        });
        ctx.waitTicks(3);
    }

    private static boolean clickPrefix(ClientGameTestContext ctx, String prefix) {
        String l = labelPrefix(ctx, prefix);
        return l != null && DesignerKit.click(ctx, l);
    }

    private static String labelPrefix(ClientGameTestContext ctx, String prefix) {
        for (String l : DesignerKit.labels(ctx)) {
            if (l.startsWith(prefix)) {
                return l;
            }
        }
        return null;
    }

    private static int[] facePrefix(ClientGameTestContext ctx, BufferedImage img, String prefix) {
        String l = labelPrefix(ctx, prefix);
        return l == null ? null : face(ctx, img, l);
    }

    /**
     * The colour of a control's face in the screenshot: the pixel 3 units in from its left edge at mid height, clear of
     * the centred label and of the outline.
     */
    private static int[] face(ClientGameTestContext ctx, BufferedImage img, String label) {
        // GUI units to framebuffer pixels through the mod's Auto Scale, as DesignerKit.toWindow does for clicks.
        double[] at = ctx.computeOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            float f = 1f;
            try {
                f = ((Number) ModUnderTest.staticCall("com.killer560.hub.hud.AutoScale", "appliedFactor",
                        new Class<?>[]{Screen.class}, new Object[]{s})).floatValue();
            } catch (RuntimeException | AssertionError ignored) {
                // no Auto Scale in this jar: plain GUI units
            }
            var win = mc.getWindow();
            double perUnit = f * win.getWidth() / (double) win.getGuiScaledWidth();
            for (var ch : s.children()) {
                if (ch instanceof AbstractWidget w && DesignerKit.label(w).equals(label)) {
                    return new double[]{(w.getX() + 3.5) * perUnit, (w.getY() + w.getHeight() / 2.0) * perUnit,
                            win.getWidth()};
                }
            }
            return null;
        });
        if (at == null || img == null) {
            return null;
        }
        double sc = img.getWidth() / at[2];
        int rgb = img.getRGB((int) (at[0] * sc), (int) (at[1] * sc));
        return new int[]{(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF};
    }

    private static int distance(int[] a, int[] b) {
        if (a == null || b == null) {
            return -1;
        }
        return Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]);
    }

    private static String hex(int[] c) {
        return c == null ? "none" : String.format(Locale.ROOT, "#%02X%02X%02X", c[0], c[1], c[2]);
    }

    private static BufferedImage shot(ClientGameTestContext ctx, String label) {
        ctx.waitTicks(4);
        String name = NAME + "-" + label;
        Path taken = ctx.takeScreenshot(dev.testkit.harness.Report.fileName(name));
        dev.testkit.harness.Report.screenshot(name, taken);
        try {
            return javax.imageio.ImageIO.read(taken.toFile());
        } catch (Exception e) {
            System.out.println("[" + NAME + "] could not read " + taken + ": " + e);
            return null;
        }
    }

    private static void setTheme(ClientGameTestContext ctx, String theme) {
        ctx.runOnClient(mc -> {
            try {
                Object cfg = ModUnderTest.config("com.killer560.hub.gui.MenuConfig");
                Object value = ModUnderTest.enumValue("com.killer560.hub.gui.MenuTheme", theme);
                ModUnderTest.call(cfg, "setTheme", new Class<?>[]{Class.forName("com.killer560.hub.gui.MenuTheme")}, new Object[]{value});
                Screen s = McCompat.screen(mc);
                if (s != null) {
                    s.resize(s.width, s.height);
                }
            } catch (Throwable t) {
                System.out.println("[" + NAME + "] could not set the theme " + theme + ": " + t);
            }
        });
        ctx.waitTicks(3);
    }

    private static void check(List<String> failures, boolean ok, String message) {
        if (!ok) {
            failures.add(message);
            System.out.println("[" + NAME + "] FAIL " + message);
        }
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

    private static void resetPrefs() {
        try {
            Files.deleteIfExists(ModUnderTest.modConfig("killer560smod-sim-menu.json"));
        } catch (Exception ignored) {
            // nothing to forget
        }
    }
}

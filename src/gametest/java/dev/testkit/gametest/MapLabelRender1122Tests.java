package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 1122-map-labels-clean (mod branch mapname-1122; killer560, 2026-11-22, his F7 run: "look at the map for supertall the name
 * is all bugged out"). The Dungeon Map HUD of a sim floor with the 2x2 Supertall and 1x1 neighbours, room labels on
 * Name + Secrets and Predictive Rooms on, is rendered through the mod's own {@code live_map} element into a private render
 * state (the way {@code SimMapItemTests.extract} does), three frames in a row (first paint, then the replayed ones), and every
 * drawn text run is read back: each must be a room-name word or a secrets count of a placed room, no word of a name may be
 * drawn twice, and no two runs may overlap (a label drawn over another is the "55upertall8" smear).
 * <p>
 * The cause (mapname-1122-cont, from his screenshot: white rows 12 apart from GUI 10,60 with orange keys and five-decimal
 * values, "Y: 69.00000", under the room fills): his Advanced Position HUD, never moved from its default 10,60, drew under
 * his Dungeon Map, which he keeps at 1,-4 x1.3; the map's 20%-white background let its lines show between the rooms. So
 * the whole in-game HUD layer (the mod's own {@code HudInGameRenderer.draw}) is also rendered with his layout and
 * Advanced Position on (5 decimals, facing), and no text run that the map itself does not draw may meet the map's box;
 * the premise is that Advanced Position really drew its lines in that frame.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class MapLabelRender1122Tests implements FabricClientGameTest {

    private static final String NAME = "1122-map-labels-clean";
    private static final String MAP = "livemap.LiveMapConfig";
    private static final int ROOM_GRID = 6;
    private static final float S = 2f;

    private final List<String> all = new ArrayList<>();

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
        ctx.runOnClient(mc -> Scenario.loadRoomDatabaseNow());
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomdatabase.RoomDatabase", "isReady"), 1000);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "isReady"));
        List<AutoCloseable> restore = new ArrayList<>();
        try {
            ctx.runOnClient(mc -> {
                restore.add(0, Mod.with("hud.HudConfig", "AutoScale", false));
                restore.add(0, Mod.with(MAP, "Enabled", true));
                restore.add(0, Mod.with(MAP, "RoomLabels", 4));
                restore.add(0, Mod.with(MAP, "FunnyMap", false));
                restore.add(0, Mod.with("livemap.predict.PredictiveRoomsConfig", "Enabled", true));
                restore.add(0, Mod.with("mapping.MappingConfig", "ExtraInfoEnabled", false));
                restore.add(0, Mod.with("position.PositionConfig", "Enabled", true));
                restore.add(0, Mod.with("position.PositionConfig", "DecimalPlaces", 5));
                restore.add(0, Mod.with("position.PositionConfig", "ShowFacing", true));
                boolean gate = (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled");
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                restore.add(0, () -> Mod.staticCall("util.SkyblockGate", "setEnabled", gate));
                // His layout (26.3 hud.json): the map at 1,-4 x1.3, Advanced Position never placed (its default 10,60).
                Object hud = Mod.staticCall("hud.HudConfig", "getInstance");
                for (String id : new String[]{"live_map", "advanced_position"}) {
                    int[] old = (int[]) Mod.call(hud, "savedPosition", id);
                    float oldScale = (Float) Mod.call(hud, "getScale", id, 1f);
                    boolean had = (Boolean) Mod.call(hud, "hasPosition", id);
                    restore.add(0, () -> {
                        if (had && old != null) {
                            Mod.call(hud, "setPosition", id, old[0], old[1]);
                        } else {
                            Mod.call(hud, "clearPosition", id);
                        }
                        Mod.call(hud, "setScale", id, oldScale);
                    });
                }
                Mod.call(hud, "setPosition", "live_map", 1, -4);
                Mod.call(hud, "setScale", "live_map", 1.3f);
                Mod.call(hud, "clearPosition", "advanced_position");
                Mod.call(hud, "setScale", "advanced_position", 1f);
            });
            Map<Integer, String> placements = new LinkedHashMap<>();
            placements.put(1 * ROOM_GRID + 1, "Entrance");
            placements.put(2 * ROOM_GRID + 1, "Andesite");
            placements.put(2 * ROOM_GRID + 2, "Supertall");
            placements.put(1 * ROOM_GRID + 3, "Lots Of Floors");
            placements.put(4 * ROOM_GRID + 3, "Lower Blaze");
            placements.put(1 * ROOM_GRID + 4, "Criss Cross");
            SimMapItemTests.build(ctx, placements);
            ctx.waitTicks(20);
            Report.caseStarted(NAME);
            List<String> problems = new ArrayList<>();
            List<String> seen = new ArrayList<>();
            for (int frame = 0; frame < 3; frame++) {
                List<Run> runs = ctx.computeOnClient(MapLabelRender1122Tests::render);
                check(frame, runs, problems, seen);
                // A first frame stamps the map as drawn (HudSeen), which is what the other elements lay out against.
                ctx.computeOnClient(MapLabelRender1122Tests::hudLayer);
                ctx.waitTicks(4);
                checkHud(frame, ctx.computeOnClient(MapLabelRender1122Tests::hudLayer), problems);
                ctx.waitTicks(2);
            }
            boolean supertallDrawn = seen.stream().anyMatch(s -> s.contains("Supertall"));
            if (!supertallDrawn) {
                problems.add("premise: no run of the Supertall label was drawn (" + seen + ")");
            }
            row(problems.isEmpty(), problems.isEmpty() ? "every run is a clean label: " + seen.get(seen.size() - 1)
                    : String.join("; ", problems));
        } catch (Throwable t) {
            all.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> {
                McCompat.setScreen(mc, null);
                for (AutoCloseable r : restore) {
                    try {
                        r.close();
                    } catch (Exception e) {
                        System.out.println("[" + NAME + "] restore failed: " + e);
                    }
                }
            });
            ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimState", "leave"));
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
        if (!all.isEmpty()) {
            throw new AssertionError(String.join(" | ", all));
        }
        System.out.println("[" + NAME + "] PASS - every case passed");
    }

    private record Run(String text, ScreenRectangle box) {
        @Override
        public String toString() {
            return "'" + text + "'@" + box.left() + "," + box.top() + "," + box.width() + "x" + box.height();
        }
    }

    private static List<Run> render(net.minecraft.client.Minecraft mc) {
        Object element = Mod.staticCall("hud.HudElementRegistry", "byId", "live_map");
        GuiRenderState state = new GuiRenderState();
        GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
        g.pose().pushMatrix();
        try {
            g.pose().translate(20, 20);
            g.pose().scale(S, S);
            Method render = Mod.cls("hud.HudElement").getMethod("render", GuiGraphicsExtractor.class, int.class, int.class);
            render.invoke(element, g, 0, 0);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("render of live_map threw: " + e.getCause(), e);
        } finally {
            g.pose().popMatrix();
        }
        List<Run> out = new ArrayList<>();
        state.forEachText(t -> out.add(new Run(TextRuns.string(t), TextRuns.box(t))));
        return out;
    }

    /** The map's drawn box in GUI pixels, set by {@link #hudLayer}. */
    private static ScreenRectangle mapBox;

    /** One frame of the in-game HUD layer exactly as the game draws it (HudInGameRenderer.draw), every text run. */
    private static List<Run> hudLayer(net.minecraft.client.Minecraft mc) {
        GuiRenderState state = new GuiRenderState();
        GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
        try {
            Method draw = Mod.cls("hud.HudInGameRenderer").getDeclaredMethod("draw", GuiGraphicsExtractor.class);
            draw.setAccessible(true);
            draw.invoke(null, g);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("the in-game HUD draw threw: " + e.getCause(), e);
        }
        Object map = Mod.staticCall("hud.HudElementRegistry", "byId", "live_map");
        int[] p = (int[]) Mod.staticCall("hud.HudElementRegistry", "resolvePosition", map);
        float s = (Float) Mod.staticCall("hud.HudElementRegistry", "resolveScale", map);
        int w = Math.round((Integer) Mod.call(map, "width") * s);
        int h = Math.round((Integer) Mod.call(map, "height") * s);
        mapBox = new ScreenRectangle(p[0], p[1], w, h);
        List<Run> out = new ArrayList<>();
        state.forEachText(t -> out.add(new Run(TextRuns.string(t), TextRuns.box(t))));
        return out;
    }

    /** No text the map does not draw itself may meet the map's box, and Advanced Position must really have drawn. */
    private static void checkHud(int frame, List<Run> runs, List<String> problems) {
        List<Run> position = runs.stream().filter(r -> r.text().matches("(X|Y|Z): -?\\d+\\.\\d{5}")).toList();
        if (position.size() != 3) {
            problems.add("premise: frame " + frame + " Advanced Position drew " + position + " (want its X, Y and Z lines)");
        }
        boolean mapLabel = runs.stream().anyMatch(r -> r.text().equals("Supertall"));
        if (!mapLabel) {
            problems.add("premise: frame " + frame + " the in-game HUD drew no Supertall label (map box " + mapBox + ")");
        }
        for (Run r : runs) {
            boolean mapOwn = r.text().matches("[A-Za-z']+|\\d+/\\d+|\\d+|\\?");
            if (!mapOwn && overlaps(r.box(), mapBox)) {
                problems.add("frame " + frame + ": " + r + " drawn on the Dungeon Map's box " + mapBox.left() + ","
                        + mapBox.top() + "," + mapBox.width() + "x" + mapBox.height());
            }
        }
    }

    private static boolean overlaps(ScreenRectangle a, ScreenRectangle b) {
        int w = Math.min(a.right(), b.right()) - Math.max(a.left(), b.left());
        int h = Math.min(a.bottom(), b.bottom()) - Math.max(a.top(), b.top());
        return w > 1 && h > 1;
    }

    private static void check(int frame, List<Run> runs, List<String> problems, List<String> seen) {
        seen.clear();
        for (Run r : runs) {
            seen.add(r.toString());
            if (!r.text().matches("[A-Za-z']+|\\d+/\\d+|\\d+|\\?")) {
                problems.add("frame " + frame + ": stray text " + r);
            }
        }
        for (int i = 0; i < runs.size(); i++) {
            for (int j = i + 1; j < runs.size(); j++) {
                if (overlaps(runs.get(i).box(), runs.get(j).box())) {
                    problems.add("frame " + frame + ": " + runs.get(i) + " overlaps " + runs.get(j));
                }
            }
        }
        for (String word : new String[]{"Supertall", "Andesite", "Lower", "Blaze", "Criss", "Cross"}) {
            long n = runs.stream().filter(r -> r.text().equals(word)).count();
            if (n > 1) {
                problems.add("frame " + frame + ": '" + word + "' drawn " + n + " times");
            }
        }
    }

    private void row(boolean pass, String detail) {
        System.out.println("[" + NAME + "] " + (pass ? "PASS" : "FAIL") + " - " + detail);
        Report.caseFinished(NAME, pass ? "PASS" : "FAIL", "n/a (singleplayer)", detail, List.of(), List.of());
        SuiteVerdict.finished(NAME);
        if (!pass) {
            all.add(NAME + ": " + detail);
        }
    }
}

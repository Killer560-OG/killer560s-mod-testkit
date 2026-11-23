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

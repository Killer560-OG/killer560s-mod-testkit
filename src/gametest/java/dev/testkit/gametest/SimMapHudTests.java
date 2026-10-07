package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Three Dungeon Map / HUD reports of killer560's from 2026-10-07, each on a sim floor, each read from what the mod
 * DRAWS (the HUD element's render extracted into a throwaway {@link GuiRenderState}: its fills and its text runs).
 *
 * <ul>
 *   <li><b>99-sim-map-fit</b> - "the map doesn't rescale very well for other floors". Floors drawn on 4x5 (F1's
 *       grid), 5x5 (F3's), 6x6 (F7's) and a 4x3 block that does not start at slot 0, built from two rooms at
 *       opposite corners. The Dungeon Map HUD's room fills must fill the map's frame on the long side (within
 *       {@link #FIT_MARGIN} units) and be centred on the short one; and on the cheat jar an Interactive Map press at
 *       the drawn centre of each room must resolve to that room's cell.</li>
 *   <li><b>99-sim-extra-info</b> - "I have the extra info overlay on for the map but the info isn't coming up below
 *       the map". With Score Calculator OFF (the fresh-config default), Map Extras' Extra Info Overlay ON and the
 *       Interactive Map's Extra Info ON: the Dungeon Map HUD must draw Score / Secrets / Crypts lines under the map,
 *       and the Interactive Map legend must lay out its Extra Info section with a Crypts row.</li>
 *   <li><b>99-sim-secrets-boss</b> - "in boss room remove the secrets: 0/? menu". The Secrets HUD draws in the
 *       clear; the player is placed in the M7 boss room (the sim reports M7), the live map's boss latch must fire
 *       (so the check is not vacuous), and the element must then draw nothing; in the HUD editor it still draws
 *       its sample; after a new floor is built (a fresh run's clear) it draws again.</li>
 * </ul>
 */
public class SimMapHudTests implements FabricClientGameTest {

    private static final String FIT = "99-sim-map-fit";
    private static final String EXTRA = "99-sim-extra-info";
    private static final String BOSS = "99-sim-secrets-boss";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final int ROOM_GRID = 6;
    private static final int GRID = 11;
    /** HUD render scale and origin for the extracted frames. */
    private static final float S = 2f;
    private static final int X0 = 20;
    private static final int Y0 = 20;
    /** Map units the drawn grid may fall short of the frame by, on its long side and in centring. */
    private static final float FIT_MARGIN = 2f;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        boolean fit = !Scenario.skip(FIT);
        boolean extra = !Scenario.skip(EXTRA);
        boolean boss = !Scenario.skip(BOSS);
        if (!fit && !extra && !boss) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            System.out.println("[" + FIT + "] SKIPPED - needs his real rooms and room database");
            return;
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomdatabase.RoomDatabase", "ensureLoading"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomdatabase.RoomDatabase", "isReady"),
                1000);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "isReady"));

        List<AutoCloseable> restore = new ArrayList<>();
        ctx.runOnClient(mc -> {
            // Auto Scale off: with it on a mod screen is laid out at guiSize / factor and drawn under a pose scale, so
            // the Interactive Map's drawn rectangles and its cellAt() coordinates differ by that factor (0.5 in the
            // default gametest window) and a press computed from one would be measured in the other.
            restore.add(0, Mod.with("hud.HudConfig", "AutoScale", false));
            restore.add(0, Mod.with("livemap.LiveMapConfig", "Enabled", true));
            restore.add(0, Mod.with("autopuzzles.AutoPuzzlesConfig", "AutoPuzzlesMasterEnabled", false));
            restore.add(0, Mod.with("scorecalc.ScoreCalculatorConfig", "Enabled", false));
            restore.add(0, Mod.with("mapping.MappingConfig", "ExtraInfoEnabled", false));
            restore.add(0, Mod.with("dungeoninfo.DungeonInfoConfig", "SecretsHudEnabled", true));
            if (Mod.isCheat()) {
                restore.add(0, Mod.with("livemap.LiveMapConfig", "InteractiveMapEnabled", true));
                restore.add(0, Mod.with("livemap.LiveMapConfig", "ShowExtraInfo", true));
            }
        });
        Map<String, List<String>> failures = new LinkedHashMap<>();
        failures.put(FIT, new ArrayList<>());
        failures.put(EXTRA, new ArrayList<>());
        failures.put(BOSS, new ArrayList<>());
        try {
            if (fit) {
                fitCases(ctx, failures.get(FIT));
            }
            if (extra) {
                extraInfo(ctx, failures.get(EXTRA));
            }
            if (boss) {
                secretsBoss(ctx, failures.get(BOSS));
            }
        } catch (Throwable t) {
            String which = boss ? BOSS : extra ? EXTRA : FIT;
            failures.get(which).add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> {
                McCompat.setScreen(mc, null);
                for (AutoCloseable r : restore) {
                    try {
                        r.close();
                    } catch (Exception e) {
                        System.out.println("[" + FIT + "] restore failed: " + e);
                    }
                }
            });
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
        for (Map.Entry<String, List<String>> e : failures.entrySet()) {
            boolean ran = e.getKey().equals(FIT) ? fit : e.getKey().equals(EXTRA) ? extra : boss;
            if (!ran) {
                continue;
            }
            System.out.println("[" + e.getKey() + "] " + (e.getValue().isEmpty() ? "PASS" : "FAIL: "
                    + String.join("; ", e.getValue())));
            for (String f : e.getValue()) {
                all.add(e.getKey() + ": " + f);
            }
        }
        if (!all.isEmpty()) {
            throw new AssertionError(all.size() + " problem(s): " + String.join("; ", all));
        }
    }

    // ---- 99-sim-map-fit ----------------------------------------------------------------------------------------

    private static void fitCases(ClientGameTestContext ctx, List<String> failures) {
        // {name, col0, row0, col1, row1}: Entrance in one corner, New Trap in the opposite one.
        int[][] floors = {{0, 0, 3, 4}, {0, 0, 4, 4}, {0, 0, 5, 5}, {1, 1, 4, 3}};
        String[] names = {"4x5 (F1's grid)", "5x5 (F3's grid)", "6x6 (F7's grid)", "4x3 from slot (1,1)"};
        for (int i = 0; i < floors.length; i++) {
            int[] f = floors[i];
            Map<Integer, String> placements = new LinkedHashMap<>();
            placements.put(f[1] * ROOM_GRID + f[0], "Entrance");
            placements.put(f[3] * ROOM_GRID + f[2], "New Trap");
            build(ctx, placements);
            ctx.waitTicks(20);
            String tag = names[i];
            Frame frame = ctx.computeOnClient(mc -> renderHud(mc, "live_map"));
            int[] box = frame.box();
            List<int[]> rects = new ArrayList<>();
            for (int[] r : frame.fills()) {
                int w = r[2] - r[0];
                int h = r[3] - r[1];
                if (w >= box[2] - box[0] - 4 || h >= box[3] - box[1] - 4) {
                    continue; // the map's background and its outline edges
                }
                rects.add(r);
            }
            if (rects.isEmpty()) {
                failures.add(tag + ": the Dungeon Map HUD drew no rooms (" + frame.fills().size() + " fill(s))");
                continue;
            }
            int[] g = union(rects);
            int mapPx = (box[2] - box[0]) / (int) S;
            float inner = (mapPx - 4) * S;
            float left = (g[0] - (box[0] + 2 * S)) / S;
            float right = ((box[2] - 2 * S) - g[2]) / S;
            float top = (g[1] - (box[1] + 2 * S)) / S;
            float bottom = ((box[3] - 2 * S) - g[3]) / S;
            float gw = (g[2] - g[0]) / S;
            float gh = (g[3] - g[1]) / S;
            float longSide = Math.max(gw, gh);
            println(FIT, String.format(Locale.ROOT, "%s: frame %d units, inner %.0f; drawn grid %.1fx%.1f, gaps "
                            + "L %.1f T %.1f R %.1f B %.1f (%d room/door fill(s))", tag, mapPx, inner / S, gw, gh, left,
                    top, right, bottom, rects.size()));
            if (longSide < inner / S - FIT_MARGIN) {
                failures.add(String.format(Locale.ROOT, "%s: the drawn floor's long side is %.1f of the frame's %.0f "
                        + "units - it does not fill the map", tag, longSide, inner / S));
            }
            if (Math.abs(left - right) > FIT_MARGIN || Math.abs(top - bottom) > FIT_MARGIN) {
                failures.add(String.format(Locale.ROOT, "%s: the drawn floor is not centred (L %.1f R %.1f, T %.1f "
                        + "B %.1f)", tag, left, right, top, bottom));
            }
            if (Mod.isCheat()) {
                imClicks(ctx, failures, tag, new int[][]{{f[0], f[1]}, {f[2], f[3]}});
            }
        }
    }

    /** An Interactive Map press at the drawn centre of each room must resolve to that room's cell. */
    private static void imClicks(ClientGameTestContext ctx, List<String> failures, String tag, int[][] rooms) {
        Screen screen = openMap(ctx);
        List<int[]> squares = ctx.computeOnClient(mc -> {
            GuiRenderState state = new GuiRenderState();
            GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
            screen.extractRenderStateWithTooltipAndSubtitles(g, -1, -1, 0f);
            int[] panel = (int[]) Mod.call(screen, "panel");
            List<int[]> fills = new ArrayList<>();
            state.forEachElement(el -> {
                ScreenRectangle b = el.bounds();
                if (b == null || !el.getClass().getSimpleName().contains("ColoredRectangle") || transparent(el)) {
                    return;
                }
                int[] r = {b.left(), b.top(), b.right(), b.bottom()};
                int w = r[2] - r[0];
                int h = r[3] - r[1];
                if (r[0] < panel[0] || r[1] < panel[1] || r[2] > panel[2] || r[3] > panel[3]
                        || w >= panel[2] - panel[0] - 4 || h >= panel[3] - panel[1] - 4) {
                    return;
                }
                fills.add(r);
            }, GuiRenderState.TraverseRange.ALL);
            int side = 0;
            for (int[] r : fills) {
                if (Math.abs((r[2] - r[0]) - (r[3] - r[1])) <= 2) {
                    side = Math.max(side, r[2] - r[0]);
                }
            }
            List<int[]> out = new ArrayList<>();
            for (int[] r : fills) {
                if (Math.abs((r[2] - r[0]) - (r[3] - r[1])) <= 2 && r[2] - r[0] >= side - 2) {
                    out.add(r);
                }
            }
            return out;
        });
        if (squares.size() < 2) {
            List<String> debug = ctx.computeOnClient(mc -> {
                GuiRenderState state = new GuiRenderState();
                GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
                screen.extractRenderStateWithTooltipAndSubtitles(g, -1, -1, 0f);
                List<String> out = new ArrayList<>();
                out.add("panel " + java.util.Arrays.toString((int[]) Mod.call(screen, "panel")) + " screen "
                        + screen.width + "x" + screen.height + " current " + (McCompat.screen(mc) == screen));
                state.forEachElement(el -> {
                    ScreenRectangle b = el.bounds();
                    if (out.size() < 40 && b != null) {
                        out.add(el.getClass().getSimpleName() + " " + b.left() + "," + b.top() + " " + b.width() + "x"
                                + b.height());
                    }
                }, GuiRenderState.TraverseRange.ALL);
                return out;
            });
            println(FIT, tag + ": Interactive Map fills: " + debug);
            failures.add(tag + ": the Interactive Map drew " + squares.size() + " room square(s), expected 2");
            return;
        }
        squares.sort((a, b) -> Integer.compare(a[0] + a[1], b[0] + b[1]));
        int[][] picks = {squares.get(0), squares.get(squares.size() - 1)};
        for (int i = 0; i < 2; i++) {
            int[] r = picks[i];
            double cx = (r[0] + r[2]) / 2.0;
            double cy = (r[1] + r[3]) / 2.0;
            int want = 2 * rooms[i][0] + 2 * rooms[i][1] * GRID;
            int got = ctx.computeOnClient(mc -> (Integer) Mod.call(screen, "cellAt", cx, cy));
            println(FIT, String.format(Locale.ROOT, "%s: Interactive Map room square %s, press at (%.1f, %.1f) -> cell "
                    + "%d (room slot %d,%d expects %d)", tag, java.util.Arrays.toString(r), cx, cy, got, rooms[i][0],
                    rooms[i][1], want));
            if (got != want) {
                failures.add(tag + ": an Interactive Map press on room slot (" + rooms[i][0] + "," + rooms[i][1]
                        + ")'s drawn centre resolved to cell " + got + ", not " + want);
            }
        }
        ctx.runOnClient(mc -> McCompat.setScreen(mc, null));
        ctx.waitTicks(2);
    }

    // ---- 99-sim-extra-info -------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void extraInfo(ClientGameTestContext ctx, List<String> failures) {
        Map<Integer, String> placements = new LinkedHashMap<>();
        placements.put(2 * ROOM_GRID + 2, "Entrance");
        placements.put(2 * ROOM_GRID + 3, "New Trap");
        build(ctx, placements);
        ctx.runOnClient(mc -> Mod.set("mapping.MappingConfig", "setExtraInfoEnabled", true));
        boolean scoreOn = ctx.computeOnClient(mc -> (Boolean) Mod.get("scorecalc.ScoreCalculatorConfig", "isEnabled"));
        // The estimate is recalculated every 10 ticks; give it several.
        ctx.waitTicks(60);
        Frame frame = ctx.computeOnClient(mc -> renderHud(mc, "live_map"));
        println(EXTRA, "Score Calculator enabled: " + scoreOn + "; Dungeon Map HUD box " + java.util.Arrays.toString(
                frame.box()) + ", texts " + frame.texts());
        if (scoreOn) {
            failures.add("Score Calculator is ON - the case is about it being off");
        }
        boolean score = false;
        boolean secrets = false;
        boolean crypts = false;
        for (String t : frame.texts()) {
            score |= t.startsWith("Score");
            secrets |= t.startsWith("Secrets");
            crypts |= t.startsWith("Crypts");
        }
        if (!score || !secrets || !crypts) {
            failures.add("the Dungeon Map HUD drew no Extra Info under the map with Score Calculator off (Score "
                    + score + ", Secrets " + secrets + ", Crypts " + crypts + ")");
        }
        if (Mod.isCheat()) {
            Screen screen = openMap(ctx);
            ctx.waitTicks(3);
            List<String> raw = ctx.computeOnClient(mc -> (List<String>) Mod.call(screen, "legendTextBoxes"));
            boolean header = false;
            boolean cryptsRow = false;
            for (String r : raw) {
                String t = r.substring(0, r.indexOf('|'));
                header |= t.equals("Extra Info");
                cryptsRow |= t.equals("Crypts");
            }
            println(EXTRA, "Interactive Map legend: Extra Info header " + header + ", Crypts row " + cryptsRow);
            if (!header || !cryptsRow) {
                failures.add("the Interactive Map legend has no Extra Info section with Score Calculator off (header "
                        + header + ", Crypts row " + cryptsRow + ")");
            }
            ctx.runOnClient(mc -> McCompat.setScreen(mc, null));
        }
        ctx.runOnClient(mc -> Mod.set("mapping.MappingConfig", "setExtraInfoEnabled", false));
    }

    // ---- 99-sim-secrets-boss -----------------------------------------------------------------------------------

    private static void secretsBoss(ClientGameTestContext ctx, List<String> failures) {
        Map<Integer, String> placements = new LinkedHashMap<>();
        placements.put(1 * ROOM_GRID + 1, "Entrance");
        placements.put(1 * ROOM_GRID + 2, "New Trap");
        build(ctx, placements);
        ctx.waitTicks(20);
        List<String> clear = ctx.computeOnClient(mc -> renderHud(mc, "dungeon_info").texts());
        println(BOSS, "in the clear: " + clear);
        if (!hasSecrets(clear)) {
            failures.add("the Secrets HUD drew nothing in the clear (" + clear + ") - nothing below can mean anything");
            return;
        }
        // The M7 boss room (the sim reports M7): x/z 60 is inside LiveMapFeature's F7 boss bounds and outside the
        // floor's room grid. Flying, so he hangs there rather than falling out of the bounds.
        place(ctx, 60.5, 150, 60.5);
        boolean latched = false;
        for (int i = 0; i < 100 && !latched; i++) {
            ctx.waitTicks(1);
            latched = ctx.computeOnClient(mc ->
                    (Boolean) Mod.staticCall("livemap.LiveMapFeature", "isInBoss"));
        }
        double[] at = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        println(BOSS, "placed at " + String.format(Locale.ROOT, "%.1f %.1f %.1f", at[0], at[1], at[2])
                + "; boss latched: " + latched);
        if (!latched) {
            failures.add("the boss latch never fired at the M7 boss room - the hidden check would be vacuous");
            return;
        }
        List<String> inBoss = ctx.computeOnClient(mc -> renderHud(mc, "dungeon_info").texts());
        println(BOSS, "in the boss: " + inBoss);
        if (hasSecrets(inBoss)) {
            failures.add("the Secrets HUD still draws in the boss room: " + inBoss);
        }
        ctx.runOnClient(mc -> {
            try {
                McCompat.setScreen(mc, (Screen) Mod.cls("hud.HudEditorScreen").getConstructor(Screen.class)
                        .newInstance((Object) null));
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        ctx.waitTicks(2);
        List<String> editor = ctx.computeOnClient(mc -> renderHud(mc, "dungeon_info").texts());
        ctx.runOnClient(mc -> McCompat.setScreen(mc, null));
        println(BOSS, "in the HUD editor, in the boss: " + editor);
        if (!hasSecrets(editor)) {
            failures.add("the Secrets HUD draws nothing in the HUD editor in the boss - it cannot be moved there");
        }
        // A fresh run: a new floor resets the live map's boss latch.
        Map<Integer, String> again = new LinkedHashMap<>();
        again.put(3 * ROOM_GRID + 3, "Entrance");
        again.put(3 * ROOM_GRID + 4, "New Trap");
        build(ctx, again);
        ctx.waitTicks(20);
        boolean stillBoss = ctx.computeOnClient(mc -> (Boolean) Mod.staticCall("livemap.LiveMapFeature", "isInBoss"));
        List<String> fresh = ctx.computeOnClient(mc -> renderHud(mc, "dungeon_info").texts());
        println(BOSS, "fresh run's clear (boss latched " + stillBoss + "): " + fresh);
        if (!hasSecrets(fresh)) {
            failures.add("the Secrets HUD does not draw again in a fresh run's clear: " + fresh);
        }
    }

    private static boolean hasSecrets(List<String> texts) {
        for (String t : texts) {
            if (t.startsWith("Secrets")) {
                return true;
            }
        }
        return false;
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    /** What one HUD element drew: its box {x0, y0, x1, y1}, its opaque fills, and its text runs as plain strings. */
    record Frame(int[] box, List<int[]> fills, List<String> texts) {
    }

    /** Extracts element {@code id}'s render at ({@link #X0}, {@link #Y0}), scale {@link #S}. Client thread. */
    private static Frame renderHud(Minecraft mc, String id) {
        Object element = Mod.staticCall("hud.HudElementRegistry", "byId", id);
        if (element == null) {
            throw new AssertionError("no HUD element " + id);
        }
        int w = (Integer) Mod.call(element, "width");
        int h = (Integer) Mod.call(element, "height");
        GuiRenderState state = new GuiRenderState();
        GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
        g.pose().pushMatrix();
        try {
            g.pose().translate(X0, Y0);
            g.pose().scale(S, S);
            Method render = Mod.cls("hud.HudElement").getMethod("render", GuiGraphicsExtractor.class, int.class,
                    int.class);
            render.invoke(element, g, 0, 0);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("render of " + id + " threw: " + e.getCause(), e);
        } finally {
            g.pose().popMatrix();
        }
        List<int[]> fills = new ArrayList<>();
        state.forEachElement(el -> {
            ScreenRectangle b = el.bounds();
            if (b != null && el.getClass().getSimpleName().contains("ColoredRectangle") && !transparent(el)) {
                fills.add(new int[]{b.left(), b.top(), b.right(), b.bottom()});
            }
        }, GuiRenderState.TraverseRange.ALL);
        List<String> texts = new ArrayList<>();
        state.forEachText(t -> texts.add(TextRuns.string(t)));
        return new Frame(new int[]{X0, Y0, X0 + Math.round(w * S), Y0 + Math.round(h * S)}, fills, texts);
    }

    private static boolean transparent(Object el) {
        try {
            int c1 = (Integer) el.getClass().getMethod("col1").invoke(el);
            int c2 = (Integer) el.getClass().getMethod("col2").invoke(el);
            return (c1 >>> 24) == 0 && (c2 >>> 24) == 0;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static int[] union(List<int[]> rects) {
        int[] u = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int[] r : rects) {
            u[0] = Math.min(u[0], r[0]);
            u[1] = Math.min(u[1], r[1]);
            u[2] = Math.max(u[2], r[2]);
            u[3] = Math.max(u[3], r[3]);
        }
        return u;
    }

    private static void build(ClientGameTestContext ctx, Map<Integer, String> placements) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(FLOOR_GEN, "buildExplicit",
                new Class<?>[]{Minecraft.class, Map.class}, new Object[]{mc, placements})));
        ctx.waitFor(mc -> mc.level != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitTicks(60);
    }

    private static Screen openMap(ClientGameTestContext ctx) {
        Screen s = ctx.computeOnClient(mc -> {
            try {
                Screen screen = (Screen) Mod.cls("livemap.InteractiveMapScreen").getConstructor(boolean.class)
                        .newInstance(false);
                McCompat.setScreen(mc, screen);
                return screen;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        ctx.waitTicks(5);
        return s;
    }

    /** Server-side teleport with flight on, so he stays where he is put. */
    private static void place(ClientGameTestContext ctx, double x, double y, double z) {
        AtomicReference<String> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp == null) {
                    done.set("no server player");
                    return;
                }
                sp.getAbilities().mayfly = true;
                sp.getAbilities().flying = true;
                sp.onUpdateAbilities();
                sp.teleportTo(x, y, z);
                done.set("ok");
            });
        });
        ctx.waitFor(mc -> done.get() != null, 200);
        ctx.waitTicks(5);
        ctx.runOnClient(mc -> {
            mc.player.getAbilities().flying = true;
        });
    }

    private static void println(String name, String s) {
        System.out.println("[" + name + "] " + s);
    }
}

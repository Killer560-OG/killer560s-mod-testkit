package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.Report;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 99-sim-im: killer560's 2026-10-05 Interactive Map report, reproduced in the sim.
 *
 * <p>"it thinks that it is going through trap even though it isnt": every map press from Atlas got "[Sim] No
 * abilities in a trap room" and "Warp 1 never landed", and the sidebar said "Room: Atlas" while he stood in Museum.
 * His log shows the second floor of the session built by {@code SimWorld.open}'s in-world shortcut ("already in the
 * sim world"), which never handed {@code SimState} the new map code - so every room lookup the sim SERVER makes (the
 * trap-ability rule, the sidebar's Room line) decoded the FIRST floor.
 *
 * <p>So: floor A from the title screen with New Trap on tile (1,2); then floor B built from inside the sim with Atlas
 * (2x2) on tiles (1,2)-(2,3) and New Trap beside it on (0,3) - his layout. Then, checked against what happened:
 * <ol>
 *   <li>the rebuild really took the in-world path (its log line), and {@code SimState.roomNameAt} names every tile of
 *       floor B correctly;</li>
 *   <li>standing in Atlas (its tile (1,2), where floor A had New Trap) the sidebar's room is Atlas, the live map agrees, and a map
 *       press on Temple PLANS and MOVES him there (no trap refusal, no "Already there", over 5 blocks);</li>
 *   <li>standing in New Trap, an Aspect of the Void use is refused with the trap line and he does not move;</li>
 *   <li>the Interactive Map's legend, with Extra Info showing, has no overlapping boxes at 854x480 and 1920x1080,
 *       and switching the ONE Room Labels setting (the Dungeon Map's) changes what the Interactive Map draws.</li>
 * </ol>
 */
public class SimInteractiveMapTests implements FabricClientGameTest {

    private static final String NAME = "99-sim-im";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    private static final String IM_FEATURE = "com.killer560.hub.livemap.InteractiveMapFeature";
    private static final String IM_SCREEN = "com.killer560.hub.livemap.InteractiveMapScreen";
    private static final String EXECUTOR = "com.killer560.hub.livemap.autoclear.ClearExecutor";
    private static final String AUTO_CONFIG = "com.killer560.hub.autopuzzles.AutoPuzzlesConfig";
    private static final String SCORE_CONFIG = "com.killer560.hub.scorecalc.ScoreCalculatorConfig";
    private static final String HUD_CONFIG = "com.killer560.hub.hud.HudConfig";
    private static final int GRID = 11;
    private static final int ROOM_GRID = 6;
    private static final String TRAP_LINE = "No abilities in a trap room";

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
        ctx.runOnClient(mc -> Scenario.loadRoomDatabaseNow());
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomdatabase.RoomDatabase", "isReady"),
                1000);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"));
        Object mapCfg = ctx.computeOnClient(mc -> ModUnderTest.config(LIVE_MAP_CONFIG));
        int oldLabels = ctx.computeOnClient(mc -> (Integer) ModUnderTest.call(mapCfg, "getRoomLabels",
                new Class<?>[]{}, new Object[]{}));
        Object scoreCfg = ctx.computeOnClient(mc -> ModUnderTest.config(SCORE_CONFIG));
        boolean oldScore = ctx.computeOnClient(mc -> ModUnderTest.getBoolean(scoreCfg, "isEnabled"));
        Object hudCfg = ctx.computeOnClient(mc -> ModUnderTest.config(HUD_CONFIG));
        boolean oldAuto = ctx.computeOnClient(mc -> ModUnderTest.getBoolean(hudCfg, "isAutoScale"));
        ctx.runOnClient(mc -> {
            // Score Calculator on: the legend's Extra Info (and its S+ row, the one that overlapped) only draws
            // once it has a live estimate.
            ModUnderTest.set(scoreCfg, "setEnabled", true);
            ModUnderTest.set(ModUnderTest.config(AUTO_CONFIG), "setAutoPuzzlesMasterEnabled", false);
            ModUnderTest.set(mapCfg, "setEnabled", true);
            ModUnderTest.set(mapCfg, "setInteractiveMapEnabled", true);
        });
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        int[] oldWindow = ctx.computeOnClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        int oldGui = ctx.computeOnClient(mc -> mc.options.guiScale().get());
        ctx.runOnClient(mc -> mc.options.renderDistance().set(16));

        List<String> failures = new ArrayList<>();
        try {
            body(ctx, failures);
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> {
                McCompat.setScreen(mc, null);
                ModUnderTest.set(mapCfg, "setRoomLabels", oldLabels);
                ModUnderTest.set(scoreCfg, "setEnabled", oldScore);
                ModUnderTest.set(hudCfg, "setAutoScale", oldAuto);
                mc.options.renderDistance().set(renderBefore);
                mc.options.guiScale().set(oldGui);
            });
            ctx.getInput().resizeWindow(oldWindow[0], oldWindow[1]);
            ctx.waitTicks(3);
            ctx.runOnClient(Minecraft::resizeGui);
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
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
        System.out.println("[" + NAME + "] PASS");
    }

    private static int key(int gx, int gz) {
        return gz * ROOM_GRID + gx;
    }

    private static void build(ClientGameTestContext ctx, Map<Integer, String> placements) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(FLOOR_GEN, "buildExplicit",
                new Class<?>[]{Minecraft.class, Map.class}, new Object[]{mc, placements})));
        ctx.waitFor(mc -> mc.level != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitTicks(60);
    }

    private static void body(ClientGameTestContext ctx, List<String> failures) throws Exception {
        // ---- floor A from the title screen: New Trap on tile (1,2) ------------------------------------------
        Map<Integer, String> floorA = new LinkedHashMap<>();
        floorA.put(key(1, 2), "New Trap");
        floorA.put(key(2, 2), "Entrance");
        build(ctx, floorA);
        String codeA = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(SIM_STATE, "mapCode"));
        String a12 = roomAt(ctx, 1, 2);
        println("floor A built; room at tile (1,2) by SimState: " + a12);
        if (!"New Trap".equals(a12)) {
            failures.add("floor A: SimState names tile (1,2) '" + a12 + "', not New Trap - the setup is wrong");
        }

        // ---- floor B from INSIDE the sim: his Atlas + New Trap layout ---------------------------------------
        Map<Integer, String> floorB = new LinkedHashMap<>();
        floorB.put(key(1, 2), "Atlas");
        floorB.put(key(0, 3), "New Trap");
        floorB.put(key(3, 2), "Temple");
        floorB.put(key(3, 3), "Entrance");
        long markB = LogTap.mark();
        build(ctx, floorB);
        boolean inWorld = false;
        for (String l : LogTap.since(markB)) {
            inWorld |= l.contains("already in the sim world");
        }
        String codeB = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(SIM_STATE, "mapCode"));
        println("floor B built; in-world rebuild path taken: " + inWorld + "; map code changed: " + !codeB.equals(codeA));
        if (!inWorld) {
            failures.add("floor B was not built by the in-world rebuild (no 'already in the sim world' line) - "
                    + "the case under test never ran");
        }
        int[][] expect = {{1, 2}, {2, 2}, {1, 3}, {2, 3}, {0, 3}, {3, 2}, {3, 3}};
        String[] names = {"Atlas", "Atlas", "Atlas", "Atlas", "New Trap", "Temple", "Entrance"};
        for (int i = 0; i < expect.length; i++) {
            String got = roomAt(ctx, expect[i][0], expect[i][1]);
            println("  SimState.roomNameAt tile (" + expect[i][0] + "," + expect[i][1] + ") = " + got);
            if (!names[i].equals(got)) {
                failures.add("after the rebuild SimState names tile (" + expect[i][0] + "," + expect[i][1] + ") '"
                        + got + "', the floor has " + names[i]);
            }
        }

        // ---- the run: gate open, AOTV in hand --------------------------------------------------------------
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
        for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomsim.SimRun", "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        giveAotv(ctx);

        // ---- standing in Atlas -----------------------------------------------------------------------------
        standOn(ctx, 1, 2);
        String side = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(SIM_STATE, "currentRoomName"));
        String live = liveRoomName(ctx);
        Vec3 at = ctx.computeOnClient(mc -> mc.player.position());
        println(String.format("in Atlas at (%.1f, %.1f, %.1f): sim sidebar room '%s', live map room '%s'",
                at.x, at.y, at.z, side, live));
        if (!"Atlas".equals(side)) {
            failures.add("standing in Atlas the sim's sidebar room is '" + side + "'");
        }
        if (!"Atlas".equals(live)) {
            failures.add("standing in Atlas the live map's room is '" + live + "'");
        }

        // A map press on Temple: must plan, warp and arrive - the press his log refused with the trap line.
        for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> mc.player.onGround()); i++) {
            ctx.waitTicks(1);
        }
        long mark = LogTap.mark();
        int templeCell = 2 * 2 * GRID + 2 * 3;
        ctx.runOnClient(mc -> ModUnderTest.staticCall(IM_FEATURE, "onMapPress", new Class<?>[]{int.class},
                new Object[]{templeCell}));
        boolean busySeen = false;
        for (int t = 0; t < 400; t++) {
            ctx.waitTicks(1);
            boolean busy = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isBusy"));
            busySeen |= busy;
            if (busySeen && !busy) {
                break;
            }
        }
        ctx.waitTicks(10);
        Vec3 after = ctx.computeOnClient(mc -> mc.player.position());
        boolean trap = false;
        boolean already = false;
        boolean found = false;
        for (String l : LogTap.since(mark)) {
            trap |= l.contains(TRAP_LINE);
            already |= l.contains("Already there");
            found |= l.contains("Found path");
        }
        String end = liveRoomName(ctx);
        double moved = at.distanceTo(after);
        println(String.format("press on Temple: found path %s, trap refusal %s, 'Already there' %s; moved %.1f blocks,"
                + " ended in '%s'", found, trap, already, moved, end));
        if (trap) {
            failures.add("a map press from Atlas was refused with '" + TRAP_LINE + "'");
        }
        if (already || !found) {
            failures.add("a map press on Temple from Atlas did not plan a path (found " + found + ", already there "
                    + already + ")");
        }
        if (moved < 5 || !"Temple".equals(end)) {
            failures.add(String.format("the press did not take him to Temple (moved %.1f, ended in '%s')", moved, end));
        }

        // ---- standing in New Trap: the rule itself ----------------------------------------------------------
        standOn(ctx, 0, 3);
        String trapSide = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(SIM_STATE, "currentRoomName"));
        Vec3 inTrap = ctx.computeOnClient(mc -> mc.player.position());
        mark = LogTap.mark();
        ctx.runOnClient(mc -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND));
        ctx.waitTicks(20);
        Vec3 trapAfter = ctx.computeOnClient(mc -> mc.player.position());
        boolean refused = false;
        for (String l : LogTap.since(mark)) {
            refused |= l.contains(TRAP_LINE);
        }
        println(String.format("in New Trap (sidebar '%s'): AOTV use refused %s, moved %.2f blocks", trapSide, refused,
                inTrap.distanceTo(trapAfter)));
        if (!"New Trap".equals(trapSide)) {
            failures.add("standing in New Trap the sim's sidebar room is '" + trapSide + "'");
        }
        if (!refused || inTrap.distanceTo(trapAfter) > 0.5) {
            failures.add("an AOTV use inside New Trap was not refused (line " + refused + ", moved "
                    + inTrap.distanceTo(trapAfter) + ")");
        }

        // ---- the Interactive Map's panel and labels ----------------------------------------------------------
        panel(ctx, failures);
    }

    private static String roomAt(ClientGameTestContext ctx, int gx, int gz) {
        return ctx.computeOnClient(mc -> {
            BlockPos c = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                    new Object[]{2 * gz * GRID + 2 * gx});
            return (String) ModUnderTest.staticCall(SIM_STATE, "roomNameAt", new Class<?>[]{int.class, int.class},
                    new Object[]{c.getX(), c.getZ()});
        });
    }

    private static String liveRoomName(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            int r = (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class, double.class},
                    new Object[]{mc.player.getX(), mc.player.getZ()});
            return r < 0 ? null : (String) ModUnderTest.call(layout, "name", new Class<?>[]{int.class},
                    new Object[]{r});
        });
    }

    /**
     * Stands him on tile (gx, gz) of the 6x6 room grid: the lowest solid block in the tile's centre column with two
     * blocks of air on it, found and teleported to ON THE SERVER THREAD. Not the sim's /goto: that scans the
     * ServerLevel from the render thread, and in the gametest's client/server lockstep a chunk load there deadlocks
     * the client (thread dump, 2026-10-05: render thread parked in ServerChunkCache.getChunk under goTo).
     */
    private static void standOn(ClientGameTestContext ctx, int gx, int gz) {
        AtomicReference<String> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            BlockPos c = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                    new Object[]{2 * gz * GRID + 2 * gx});
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp == null) {
                    done.set("no server player");
                    return;
                }
                var level = sp.level();
                for (int y = level.getMinY(); y < level.getMaxY() - 2; y++) {
                    BlockPos b = new BlockPos(c.getX(), y, c.getZ());
                    if (!level.getBlockState(b).isAir() && level.getBlockState(b.above()).isAir()
                            && level.getBlockState(b.above(2)).isAir()) {
                        sp.teleportTo(c.getX() + 0.5, y + 1, c.getZ() + 0.5);
                        done.set("placed at " + c.getX() + "," + (y + 1) + "," + c.getZ());
                        return;
                    }
                }
                done.set("no standable block in the column at " + c.getX() + "," + c.getZ());
            });
        });
        ctx.waitFor(mc -> done.get() != null, 200);
        println("tile (" + gx + "," + gz + "): " + done.get());
        ctx.waitTicks(20);
        for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> mc.player.onGround()); i++) {
            ctx.waitTicks(1);
        }
    }

    private static void giveAotv(ClientGameTestContext ctx) {
        AtomicReference<Boolean> given = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
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
    }

    // ---- panel + labels --------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void panel(ClientGameTestContext ctx, List<String> failures) throws Exception {
        Object mapCfg = ctx.computeOnClient(mc -> ModUnderTest.config(LIVE_MAP_CONFIG));
        // {width, height, GUI scale, Auto Scale}: the default window, 1080p with Auto Scale (laid out at 854x480 like
        // the baseline), and 1080p with Auto Scale OFF - a 640x360 screen, the tightest of the three.
        int[][] sizes = {{854, 480, 2, 1}, {1920, 1080, 3, 1}, {1920, 1080, 3, 0}};
        Object hudCfg = ctx.computeOnClient(mc -> ModUnderTest.config(HUD_CONFIG));
        for (int[] sz : sizes) {
            ctx.runOnClient(mc -> ModUnderTest.set(hudCfg, "setAutoScale", sz[3] == 1));
            ctx.getInput().resizeWindow(sz[0], sz[1]);
            ctx.waitTicks(3);
            ctx.runOnClient(mc -> {
                mc.options.guiScale().set(sz[2]);
                mc.resizeGui();
            });
            ctx.waitTicks(3);
            Screen s = openMap(ctx);
            List<String> raw = ctx.computeOnClient(mc -> (List<String>) ModUnderTest.call(s, "legendTextBoxes",
                    new Class<?>[]{}, new Object[]{}));
            int[] legend = ctx.computeOnClient(mc -> (int[]) ModUnderTest.call(s, "legendPanelRect",
                    new Class<?>[]{}, new Object[]{}));
            String tag = sz[0] + "x" + sz[1] + (sz[3] == 1 ? "" : "-noautoscale");
            Path shot = ctx.takeScreenshot(Report.fileName(NAME + "-panel-" + tag));
            Report.screenshot(NAME, shot);
            println("panel at " + tag + " (screen " + s.width + "x" + s.height + "), legend "
                    + java.util.Arrays.toString(legend) + ", screenshot " + shot);
            println("  boxes: " + raw);
            boolean extra = false;
            List<int[]> boxes = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            for (String r : raw) {
                String[] f = r.split("\\|");
                int n = f.length;
                String t = String.join("|", java.util.Arrays.copyOf(f, n - 4));
                extra |= t.equals("Extra Info");
                texts.add(t);
                boxes.add(new int[]{Integer.parseInt(f[n - 4]), Integer.parseInt(f[n - 3]),
                        Integer.parseInt(f[n - 2]), Integer.parseInt(f[n - 1])});
            }
            if (!extra) {
                failures.add("the legend at " + tag + " has no Extra Info section - the S+ row was "
                        + "never laid out, so its overlap check would be vacuous");
            }
            for (int i = 0; i < boxes.size(); i++) {
                int[] x = boxes.get(i);
                if (legend == null || x[0] < legend[0] || x[1] < legend[1] || x[2] > legend[2] || x[3] > legend[3]) {
                    failures.add(tag + ": '" + texts.get(i) + "' is outside the legend panel");
                }
                for (int j = i + 1; j < boxes.size(); j++) {
                    int[] y = boxes.get(j);
                    if (x[0] < y[2] && y[0] < x[2] && x[1] < y[3] && y[1] < x[3]) {
                        failures.add(tag + ": '" + texts.get(i) + "' overlaps '" + texts.get(j) + "'");
                    }
                }
            }
        }

        // Labels: the Dungeon Map's Room Labels (the one setting) Off vs Room Name; the map panel must differ.
        Screen s = openMap(ctx);
        int[] mapPanel = ctx.computeOnClient(mc -> (int[]) ModUnderTest.call(s, "panel", new Class<?>[]{},
                new Object[]{}));
        ctx.runOnClient(mc -> ModUnderTest.set(mapCfg, "setRoomLabels", 0));
        ctx.waitTicks(3);
        Path off = ctx.takeScreenshot(Report.fileName(NAME + "-labels-off"));
        ctx.waitTicks(3);
        Path off2 = ctx.takeScreenshot(Report.fileName(NAME + "-labels-off-again"));
        ctx.runOnClient(mc -> ModUnderTest.set(mapCfg, "setRoomLabels", 3));
        ctx.waitTicks(3);
        Path names = ctx.takeScreenshot(Report.fileName(NAME + "-labels-names"));
        ctx.waitTicks(3);
        // Control: the same setting twice. Whatever differs here (the world behind the translucent panel moving)
        // is noise the real comparison has to clear.
        Path names2 = ctx.takeScreenshot(Report.fileName(NAME + "-labels-names-again"));
        Report.screenshot(NAME, off);
        Report.screenshot(NAME, names);
        double toPx = ctx.computeOnClient(mc -> {
            float f = ((Number) ModUnderTest.staticCall("com.killer560.hub.hud.AutoScale", "appliedFactor",
                    new Class<?>[]{Screen.class}, new Object[]{s})).floatValue();
            return f * mc.getWindow().getGuiScale();
        });
        // The top three quarters of the map: the chat's fading lines cross its bottom edge in the gametest window.
        int cut = mapPanel[1] + (mapPanel[3] - mapPanel[1]) * 3 / 4;
        // Only pixels that held still across BOTH controls count: the world behind the translucent panel can
        // move by tens of thousands of pixels between shots (96,679 on 2026-10-05), which swamped a plain diff.
        int x0 = (int) (mapPanel[0] * toPx), y0 = (int) (mapPanel[1] * toPx);
        int x1 = (int) (mapPanel[2] * toPx), y1 = (int) (cut * toPx);
        int[] counts = stableDiff(off, off2, names, names2, x0, y0, x1, y1);
        int diff = counts[0];
        int stable = counts[1];
        println("Room Labels Off vs Room Name: " + diff + " steady pixel(s) of the Interactive Map's panel differ, of "
                + stable + " steady; controls (same setting twice) moved " + diffPixels(off, off2, x0, y0, x1, y1)
                + " and " + diffPixels(names, names2, x0, y0, x1, y1));
        if (stable < 10_000) {
            failures.add("too little of the Interactive Map held still between shots (" + stable
                    + " steady pixels) to compare Room Labels - the check would be vacuous");
        } else if (diff < 50) {
            failures.add("changing the Dungeon Map's Room Labels did not change the Interactive Map (" + diff
                    + " pixels differ)");
        }
        ctx.runOnClient(mc -> McCompat.setScreen(mc, null));
    }

    private static Screen openMap(ClientGameTestContext ctx) {
        Screen s = ctx.computeOnClient(mc -> {
            try {
                Screen screen = (Screen) Class.forName(IM_SCREEN).getConstructor(boolean.class).newInstance(false);
                McCompat.setScreen(mc, screen);
                return screen;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        ctx.waitTicks(5);
        return s;
    }

    private static int diffPixels(Path a, Path b, int x0, int y0, int x1, int y1) throws java.io.IOException {
        BufferedImage ia = javax.imageio.ImageIO.read(a.toFile());
        BufferedImage ib = javax.imageio.ImageIO.read(b.toFile());
        int n = 0;
        for (int y = Math.max(0, y0); y < Math.min(y1, Math.min(ia.getHeight(), ib.getHeight())); y++) {
            for (int x = Math.max(0, x0); x < Math.min(x1, Math.min(ia.getWidth(), ib.getWidth())); x++) {
                if (ia.getRGB(x, y) != ib.getRGB(x, y)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** {pixels that differ between a and b where a==a2 and b==b2, pixels where a==a2 and b==b2}. */
    private static int[] stableDiff(Path a, Path a2, Path b, Path b2, int x0, int y0, int x1, int y1)
            throws java.io.IOException {
        BufferedImage ia = javax.imageio.ImageIO.read(a.toFile());
        BufferedImage ia2 = javax.imageio.ImageIO.read(a2.toFile());
        BufferedImage ib = javax.imageio.ImageIO.read(b.toFile());
        BufferedImage ib2 = javax.imageio.ImageIO.read(b2.toFile());
        int h = Math.min(Math.min(ia.getHeight(), ia2.getHeight()), Math.min(ib.getHeight(), ib2.getHeight()));
        int w = Math.min(Math.min(ia.getWidth(), ia2.getWidth()), Math.min(ib.getWidth(), ib2.getWidth()));
        int diff = 0;
        int stable = 0;
        for (int y = Math.max(0, y0); y < Math.min(y1, h); y++) {
            for (int x = Math.max(0, x0); x < Math.min(x1, w); x++) {
                if (ia.getRGB(x, y) != ia2.getRGB(x, y) || ib.getRGB(x, y) != ib2.getRGB(x, y)) {
                    continue;
                }
                stable++;
                if (ia.getRGB(x, y) != ib.getRGB(x, y)) {
                    diff++;
                }
            }
        }
        return new int[]{diff, stable};
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}

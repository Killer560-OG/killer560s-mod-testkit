package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 1118-sim-streamsafe-map (mod streamsafemap-1118): killer560, 2026-11-18: "if I am screen sharing or recording my game then
 * you don't see my funny map style instead you would see the normal map style however I can still see the funny map", and
 * "For show mimic that should also be included as not shown on the normal map".
 *
 * <p>The five-room sim floor of 1995 (the map item shows four rooms; Funny Map draws the fifth, Fairy). Every room's tiles
 * are latched as the mimic's, with Show Mimic Room on, so the mimic tint is on every room the Funny Map draws. Read from the
 * game's own framebuffer ({@code takeScreenshot}: what OBS Game Capture, Discord and F2 get) over the Dungeon Map's square:
 * <ul>
 *   <li>B: Funny Map off, no mimic latched (the plain normal map); A0: Funny Map on; A: Funny Map on, mimic latched;</li>
 *   <li>game-frame-normal: with Stream Safe on (Funny Map, mimic latched) the frame equals B, and the HUD's draw names only
 *       the 4 rooms the item shows (A and B differ: the premise that the Funny Map shows more);</li>
 *   <li>mimic-hidden: A differs from A0 (the tint is there on the Funny Map) and the Stream Safe frame has none of it;</li>
 *   <li>overlay-window: a window owned by the game window over the map's exact screen rectangle, styles layered,
 *       transparent, no-activate and tool window, GetWindowDisplayAffinity 0x11, shown;</li>
 *   <li>overlay-is-funny: its pushed pixels equal A wherever they are opaque (mimic tint included) and not A0's;</li>
 *   <li>capture-omits-overlay (window shown only, -ShowWindow): a GDI screen capture (tools/screen-capture-rect.ps1, BitBlt
 *       with CAPTUREBLT) of that rectangle equals B while the overlay shows; with the affinity set to 0 for one capture it
 *       shows the overlay's Funny Map instead, so the window really is on screen there and only the affinity hides it;</li>
 *   <li>off-frees: Stream Safe off destroys the window and the frame is the Funny Map again (A).</li>
 * </ul>
 * Legit jar: the setting never applies (no overlay, the frame stays the normal map). Images go to
 * killer560s-mod-logs/streamsafemap-1118/ when that folder exists, else the report folder.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class StreamSafeMapTests implements FabricClientGameTest {

    private static final String NAME = "1118-sim-streamsafe-map";
    private static final String R_GAME = "1118-streamsafe-game-frame-normal";
    private static final String R_MIMIC = "1118-streamsafe-mimic-hidden-in-game";
    private static final String R_WINDOW = "1118-streamsafe-overlay-window";
    private static final String R_OVERLAY = "1118-streamsafe-overlay-is-funny";
    private static final String R_CAPTURE = "1118-streamsafe-capture-omits-overlay";
    private static final String R_OFF = "1118-streamsafe-off-frees";
    private static final String R_LEGIT = "1118-streamsafe-legit-normal";
    private static final String MAP = "livemap.LiveMapConfig";
    private static final String PAINTER = "livemap.MapPainter";
    private static final String SAFE = "livemap.StreamSafeMap";
    private static final int ROOM_GRID = 6;
    private static final long EX_WANT = 0x00080000L | 0x20L | 0x08000000L | 0x80L;

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
        boolean cheat = Mod.isCheat();
        boolean hidden = Boolean.getBoolean("testkit.hiddenWindow");
        Path out = outDir();
        try {
            ctx.runOnClient(mc -> {
                restore.add(0, Mod.with("hud.HudConfig", "AutoScale", false));
                restore.add(0, Mod.with(MAP, "Enabled", true));
                restore.add(0, Mod.with(MAP, "RoomLabels", 1));
                restore.add(0, Mod.with(MAP, "ColourByType", true));
                restore.add(0, Mod.with(MAP, "DarkenUnopened", 0f));
                SimMapItemTests.tryWith(restore, "livemap.predict.PredictiveRoomsConfig", "Enabled", false);
                SimMapItemTests.tryWith(restore, "livemap.predict.PredictiveRoomsConfig", "SpecialGuess", false);
                SimMapItemTests.tryWith(restore, MAP, "CustomCheckmarks", false);
                SimMapItemTests.tryWith(restore, MAP, "FunnyMap", false);
                Mod.set(MAP, "setShowMimicRoom", true);
                Mod.set(MAP, "setStreamSafe", false);
                try {
                    Mod.set(MAP, "setMapThemeName", "REAL");
                } catch (AssertionError oldJar) {
                    // old jar
                }
                restore.add(0, Mod.with("autopuzzles.AutoPuzzlesConfig", "AutoPuzzlesMasterEnabled", false));
                restore.add(0, Mod.with("mapping.MappingConfig", "ExtraInfoEnabled", false));
                McCompat.setScreen(mc, null);
            });
            Map<Integer, String> placements = new LinkedHashMap<>();
            placements.put(1 * ROOM_GRID + 1, "Entrance");
            placements.put(1 * ROOM_GRID + 2, "New Trap");
            placements.put(1 * ROOM_GRID + 3, "Andesite");
            placements.put(2 * ROOM_GRID + 2, "Fairy");
            placements.put(2 * ROOM_GRID + 3, "Cell");
            SimMapItemTests.lastOrigin = null;
            SimMapItemTests.build(ctx, placements);
            ctx.waitTicks(20);
            SimMapItemTests.allFills(ctx);
            SimMapItemTests.giveMap(ctx, SimMapItemTests.paintMap());
            Boolean calibrated = false;
            for (int i = 0; i < 60 && !Boolean.TRUE.equals(calibrated); i++) {
                ctx.waitTicks(5);
                calibrated = SimMapItemTests.hook(ctx, PAINTER, "mapItemCalibrated");
            }
            if (!Boolean.TRUE.equals(calibrated)) {
                row(cheat ? R_GAME : R_LEGIT, false, "premise: the painted map in slot 9 was never read");
                return;
            }
            ctx.waitTicks(10);
            int[] rect = ctx.computeOnClient(StreamSafeMapTests::mapRect);   // the map square, framebuffer px
            println("map square in the framebuffer: " + str(rect));

            // B: the plain normal map.
            BufferedImage b = shot(ctx, out, "B-normal");
            int normalRooms = drawnRooms(ctx);

            if (!cheat) {
                ctx.runOnClient(mc -> {
                    Mod.set(MAP, "setFunnyMap", true);
                    Mod.set(MAP, "setStreamSafe", true);
                });
                waitWall(ctx, 600);
                BufferedImage l = shot(ctx, out, "legit-funny-streamsafe");
                Object info = SimMapItemTests.hook(ctx, SAFE, "overlayInfo");
                double d = diff(l, b, rect);
                row(R_LEGIT, info == null && d < 0.005 && normalRooms > 0, "legit, Funny Map + Stream Safe saved ON: overlay "
                        + (info == null ? "none" : "EXISTS") + ", frame vs normal " + pct(d) + " differ, " + normalRooms
                        + " rooms drawn");
                return;
            }

            // A0: Funny Map; A: Funny Map with every room latched as the mimic's.
            ctx.runOnClient(mc -> Mod.set(MAP, "setFunnyMap", true));
            ctx.waitTicks(5);
            BufferedImage a0 = shot(ctx, out, "A0-funny");
            int funnyRooms = drawnRooms(ctx);
            ctx.runOnClient(mc -> {
                @SuppressWarnings("unchecked")
                Set<Integer> tiles = (Set<Integer>) Mod.field(PAINTER, "MIMIC_TILES");
                for (int t = 0; t < 121; t++) {
                    tiles.add(t);
                }
            });
            ctx.waitTicks(5);
            BufferedImage a = shot(ctx, out, "A-funny-mimic");
            double funnyVsNormal = diff(a0, b, rect);
            double mimicVsNot = diff(a, a0, rect);
            println("rooms drawn: normal " + normalRooms + ", funny " + funnyRooms + "; A0 vs B " + pct(funnyVsNormal)
                    + ", A vs A0 " + pct(mimicVsNot));

            // Stream Safe on.
            ctx.runOnClient(mc -> Mod.set(MAP, "setStreamSafe", true));
            long pushes0 = pushes(ctx);
            long t0 = System.currentTimeMillis();
            while (!hidden && pushes(ctx) < pushes0 + 3 && System.currentTimeMillis() - t0 < 15_000) {
                ctx.waitTicks(2);
            }
            ctx.waitTicks(3);
            BufferedImage c = shot(ctx, out, "C-game-frame-streamsafe");
            int safeRooms = drawnRooms(ctx);
            double gameVsNormal = diff(c, b, rect);
            double gameVsFunny = diff(c, a, rect);
            row(R_GAME, gameVsNormal < 0.005 && funnyVsNormal > 0.02 && safeRooms == normalRooms && funnyRooms > normalRooms
                            && normalRooms > 0,
                    "Stream Safe frame vs normal B " + pct(gameVsNormal) + " differ (want ~0), vs Funny A " + pct(gameVsFunny)
                            + "; HUD draw names " + safeRooms + " rooms (normal " + normalRooms + ", funny " + funnyRooms
                            + "); premise A0 vs B " + pct(funnyVsNormal));
            row(R_MIMIC, mimicVsNot > 0.02 && gameVsNormal < 0.005, "mimic tint on the Funny Map: A vs A0 " + pct(mimicVsNot)
                    + " differ; Stream Safe frame vs plain normal " + pct(gameVsNormal));

            if (hidden) {
                for (String r : new String[]{R_WINDOW, R_OVERLAY, R_CAPTURE, R_OFF}) {
                    Scenario.skipped(r, "the client window is hidden (run with -ShowWindow): no overlay is shown over a hidden"
                            + " window");
                }
                Object info = SimMapItemTests.hook(ctx, SAFE, "overlayInfo");
                row(R_OFF, info == null, "hidden window: overlay " + (info == null ? "never created" : "EXISTS"));
                return;
            }

            long[] info = SimMapItemTests.hook(ctx, SAFE, "overlayInfo");
            long[] client = ctx.computeOnClient(mc -> {
                long game = (Long) Mod.staticCall("compat.McWindow", "win32Handle", mc.getWindow());
                int[] cr = (int[]) Mod.staticCall("util.Win32", "clientRectOnScreen", game);
                return cr == null ? null
                        : new long[]{game, cr[0], cr[1], cr[2], cr[3], mc.getWindow().getWidth(), mc.getWindow().getHeight()};
            });
            println("overlay " + str(info) + ", game client " + str(client));
            // The framebuffer is stretched over the client area (the gametest keeps it 854x480 whatever the window's
            // size), so the overlay's place and size are the map square's, scaled to client pixels.
            double sx = client == null ? 1 : client[3] / (double) client[5];
            double sy = client == null ? 1 : client[4] / (double) client[6];
            boolean windowOk = info != null && client != null && info[0] != 0
                    && (info[5] & EX_WANT) == EX_WANT && info[6] == 0x11 && info[7] == 1 && info[11] == client[0]
                    && Math.abs(info[9] - rect[0]) <= 1 && Math.abs(info[10] - rect[1]) <= 1
                    && Math.abs(info[3] - rect[2] * sx) <= 2 && Math.abs(info[4] - rect[3] * sy) <= 2
                    && Math.abs(info[1] - (client[1] + rect[0] * sx)) <= 2 && Math.abs(info[2] - (client[2] + rect[1] * sy)) <= 2;
            row(R_WINDOW, windowOk, info == null ? "no overlay window" : String.format(Locale.ROOT,
                    "hwnd %x owner %x (game %x) at %d,%d %dx%d (map square at client %d,%d + fb %d,%d %dx%d, client/fb %.3f x %.3f), ex style %x"
                            + " (want %x set), affinity %x (want 11), shown %d, pushes %d",
                    info[0], info[11], client == null ? 0 : client[0], info[1], info[2], info[3], info[4],
                    client == null ? 0 : client[1], client == null ? 0 : client[2], rect[0], rect[1], rect[2], rect[3], sx, sy,
                    info[5], EX_WANT, info[6], info[7], info[8]));

            Object[] push = SimMapItemTests.hook(ctx, SAFE, "lastPush");
            double[] vsA = overlayMatch(push, a, rect);
            double[] vsA0 = overlayMatch(push, a0, rect);
            if (push != null) {
                save(overlayImage(push), out, "overlay-pushed");
            }
            row(R_OVERLAY, vsA[1] > 1000 && vsA[0] > 0.98 && vsA0[0] < vsA[0] - 0.05,
                    "overlay's opaque pixels " + (long) vsA[1] + ": match Funny+mimic A " + pct(vsA[0]) + ", Funny without mimic A0 "
                            + pct(vsA0[0]));

            // Capture proof: what a GDI screen recorder sees over the map's rectangle.
            if (info != null) {
                covered = 0;
                BufferedImage d1 = capture(ctx, out, "D1-capture-excluded", info);
                ctx.runOnClient(mc -> Mod.staticCall(SAFE, "setAffinityForTest", 0));
                ctx.waitTicks(5);
                BufferedImage d2 = capture(ctx, out, "D2-capture-affinity0", info);
                ctx.runOnClient(mc -> Mod.staticCall(SAFE, "setAffinityForTest", 0x11));
                ctx.waitTicks(5);
                BufferedImage d3 = capture(ctx, out, "D3-capture-excluded-again", info);
                if (covered != 0 || d1 == null || d2 == null || d3 == null) {
                    // Another window over the test client's map square: a screen capture would show that window, so
                    // it proves nothing either way (the client sits at the bottom of the z-order and is never raised).
                    Scenario.skipped(R_CAPTURE, "capture cannot be made honestly here: " + (covered < 0 ? "capture failed"
                            : covered + " of 9 points of the map square are under another window"));
                } else {
                    // The screen shows the framebuffer stretched to the client size, so the frames are scaled to the
                    // capture first; the stretch filter differs, so the test asks which map each capture is NEARER to.
                    int[] whole = {0, 0, d1.getWidth(), d1.getHeight()};
                    BufferedImage bS = scaled(crop(b, rect), d1.getWidth(), d1.getHeight());
                    BufferedImage aS = scaled(crop(a, rect), d1.getWidth(), d1.getHeight());
                    save(bS, out, "B-normal-scaled");
                    save(aS, out, "A-funny-mimic-scaled");
                    double d1VsB = diff(d1, bS, whole);
                    double d1VsA = diff(d1, aS, whole);
                    double d2VsA = diff(d2, aS, whole);
                    double d2VsB = diff(d2, bS, whole);
                    double d3VsB = diff(d3, bS, whole);
                    double d3VsA = diff(d3, aS, whole);
                    double d1VsD2 = diff(d1, d2, whole);
                    double d1VsD3 = diff(d1, d3, whole);
                    boolean ok = d1VsB + 0.05 < d1VsA && d3VsB + 0.05 < d3VsA && d2VsA + 0.05 < d2VsB
                            && d1VsD2 > 0.05 && d1VsD3 < 0.01;
                    row(R_CAPTURE, ok, "GDI screen capture of the map square (" + d1.getWidth() + "x" + d1.getHeight()
                            + "): excluded vs normal B " + pct(d1VsB) + " / vs Funny A " + pct(d1VsA)
                            + "; affinity 0 vs A " + pct(d2VsA) + " / vs B " + pct(d2VsB) + "; excluded again vs B "
                            + pct(d3VsB) + " / vs A " + pct(d3VsA) + "; excluded vs affinity 0 " + pct(d1VsD2)
                            + " (want large), excluded vs excluded again " + pct(d1VsD3) + " (want ~0)");
                }
            } else {
                row(R_CAPTURE, false, "no overlay window to capture over");
            }

            ctx.runOnClient(mc -> Mod.set(MAP, "setStreamSafe", false));
            ctx.waitTicks(10);
            Object after = SimMapItemTests.hook(ctx, SAFE, "overlayInfo");
            BufferedImage e = shot(ctx, out, "E-streamsafe-off");
            double offVsA = diff(e, a, rect);
            int offRooms = drawnRooms(ctx);
            row(R_OFF, after == null && offRooms == funnyRooms, "Stream Safe off: overlay " + (after == null ? "freed"
                    : "STILL THERE") + ", HUD draws " + offRooms + " rooms (funny " + funnyRooms + "), frame vs Funny A "
                    + pct(offVsA) + " (chat behind the map fades, so pixels are not compared)");
        } catch (Throwable t) {
            all.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> {
                try {
                    @SuppressWarnings("unchecked")
                    Set<Integer> tiles = (Set<Integer>) Mod.field(PAINTER, "MIMIC_TILES");
                    tiles.clear();
                } catch (Throwable ignored) {
                    // old jar
                }
                McCompat.setScreen(mc, null);
                try {
                    Mod.set(MAP, "setStreamSafe", false);
                    Mod.set(MAP, "setShowMimicRoom", true);
                } catch (Throwable ignored) {
                    // old jar
                }
                for (AutoCloseable r : restore) {
                    try {
                        r.close();
                    } catch (Exception ex) {
                        println("restore failed: " + ex);
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
        println("PASS - every stream safe case passed");
    }

    /** The Dungeon Map square {x, y, w, h} in framebuffer pixels, from its HUD position and scale. */
    private static int[] mapRect(net.minecraft.client.Minecraft mc) {
        Object element = Mod.staticCall("hud.HudElementRegistry", "byId", "live_map");
        int[] pos = (int[]) Mod.staticCall("hud.HudElementRegistry", "resolvePosition", element);
        float scale = (Float) Mod.staticCall("hud.HudElementRegistry", "resolveScale", element);
        int side = (Integer) Mod.call(element, "width");
        int gs = Math.max(1, (int) Math.ceil(mc.getWindow().getGuiScale()));
        int x0 = (int) Math.floor(pos[0] * gs);
        int y0 = (int) Math.floor(pos[1] * gs);
        int x1 = (int) Math.ceil((pos[0] + side * scale) * gs);
        int y1 = (int) Math.ceil((pos[1] + side * scale) * gs);
        return new int[]{x0, y0, x1 - x0, y1 - y0};
    }

    private static int drawnRooms(ClientGameTestContext ctx) {
        List<int[]> rf = SimMapItemTests.roomFills(ctx);
        int n = 0;
        for (int[] r : new int[][]{{1, 1}, {2, 1}, {3, 1}, {2, 2}, {3, 2}}) {
            if (SimMapItemTests.colourAt(rf, r[0], r[1]) != null) {
                n++;
            }
        }
        return n;
    }

    private static long pushes(ClientGameTestContext ctx) {
        long[] info = SimMapItemTests.hook(ctx, SAFE, "overlayInfo");
        return info == null ? 0L : info[8];
    }

    private static void waitWall(ClientGameTestContext ctx, long ms) {
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < ms) {
            ctx.waitTicks(1);
        }
    }

    private static BufferedImage shot(ClientGameTestContext ctx, Path out, String name) throws Exception {
        Path p = ctx.takeScreenshot(Report.fileName(NAME + "-" + name));
        Files.copy(p, out.resolve(name + ".png"), StandardCopyOption.REPLACE_EXISTING);
        return javax.imageio.ImageIO.read(p.toFile());
    }

    /** Runs tools/screen-capture-rect.ps1 over the overlay's screen rectangle, letting the client draw meanwhile. */
    private static BufferedImage capture(ClientGameTestContext ctx, Path out, String name, long[] info) throws Exception {
        Path script = null;
        for (Path d = Path.of(System.getProperty("user.dir")).toAbsolutePath(); d != null && script == null; d = d.getParent()) {
            Path f = d.resolve("tools/screen-capture-rect.ps1");
            if (Files.exists(f)) {
                script = f;
            }
        }
        if (script == null) {
            println("tools/screen-capture-rect.ps1 not found above " + System.getProperty("user.dir"));
            return null;
        }
        Path png = out.resolve(name + ".png");
        Path txt = out.resolve(name + ".txt");
        List<String> cmd = new java.util.ArrayList<>(List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", script.toString(), "-X", Long.toString(info[1]), "-Y", Long.toString(info[2]),
                "-Width", Long.toString(info[3]), "-Height", Long.toString(info[4]), "-Game", Long.toString(info[11]),
                "-Out", png.toString()));
        if ("1".equals(System.getenv("STREAMSAFE_RAISE"))) {
            cmd.add("-Raise");   // the second PC only: nobody uses its desktop
        }
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(txt.toFile()).start();
        long deadline = System.currentTimeMillis() + 60_000;
        while (p.isAlive() && System.currentTimeMillis() < deadline) {
            ctx.waitTicks(1);
        }
        if (p.isAlive()) {
            p.destroyForcibly();
            return null;
        }
        String said = Files.exists(txt) ? Files.readString(txt) : "";
        println("capture " + name + ": " + said.trim());
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("covered (\\d+)").matcher(said);
        covered = m.find() ? Math.max(covered, Integer.parseInt(m.group(1))) : -1;
        return Files.exists(png) ? javax.imageio.ImageIO.read(png.toFile()) : null;
    }

    /** Points of the map square under another window in the last captures (-1: the script said nothing). */
    private static int covered;

    private static BufferedImage scaled(BufferedImage img, int w, int h) {
        BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = o.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        return o;
    }

    /** Share of pixels in {@code r} of the two images that differ by more than 6 in any channel (1 if one is missing). */
    private static double diff(BufferedImage x, BufferedImage y, int[] r) {
        if (x == null || y == null) {
            return 1.0;
        }
        long n = 0;
        long bad = 0;
        for (int j = r[1]; j < r[1] + r[3]; j++) {
            for (int i = r[0]; i < r[0] + r[2]; i++) {
                if (i >= x.getWidth() || j >= x.getHeight() || i >= y.getWidth() || j >= y.getHeight()) {
                    continue;
                }
                n++;
                if (!close(x.getRGB(i, j), y.getRGB(i, j))) {
                    bad++;
                }
            }
        }
        return n == 0 ? 1.0 : bad / (double) n;
    }

    private static boolean close(int p, int q) {
        for (int s = 0; s <= 16; s += 8) {
            if (Math.abs(((p >> s) & 0xFF) - ((q >> s) & 0xFF)) > 6) {
                return false;
            }
        }
        return true;
    }

    /** {share of the overlay's opaque pixels equal to the image's, how many opaque pixels}. */
    private static double[] overlayMatch(Object[] push, BufferedImage img, int[] rect) {
        if (push == null || img == null) {
            return new double[]{0, 0};
        }
        int[] px = (int[]) push[0];
        int[] sr = (int[]) push[1];
        int w = sr[2];
        int h = sr[3];
        long n = 0;
        long ok = 0;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int p = px[j * w + i];
                if ((p >>> 24) != 0xFF) {
                    continue;
                }
                int x = rect[0] + i;
                int y = rect[1] + j;
                if (x >= img.getWidth() || y >= img.getHeight()) {
                    continue;
                }
                n++;
                if (close(p | 0xFF000000, img.getRGB(x, y))) {
                    ok++;
                }
            }
        }
        return new double[]{n == 0 ? 0 : ok / (double) n, n};
    }

    private static BufferedImage overlayImage(Object[] push) {
        int[] px = (int[]) push[0];
        int[] sr = (int[]) push[1];
        BufferedImage img = new BufferedImage(sr[2], sr[3], BufferedImage.TYPE_INT_ARGB_PRE);
        img.setRGB(0, 0, sr[2], sr[3], px, 0, sr[2]);
        return img;
    }

    private static BufferedImage crop(BufferedImage img, int[] r) {
        int w = Math.min(r[2], img.getWidth() - r[0]);
        int h = Math.min(r[3], img.getHeight() - r[1]);
        return img.getSubimage(r[0], r[1], w, h);
    }

    private static void save(BufferedImage img, Path out, String name) {
        try {
            javax.imageio.ImageIO.write(img, "png", out.resolve(name + ".png").toFile());
        } catch (Exception e) {
            println("could not save " + name + ": " + e);
        }
    }

    private static Path outDir() {
        String env = System.getenv("STREAMSAFE_OUT");
        Path logs = env != null && !env.isBlank() ? Path.of(env)
                : Path.of(System.getProperty("user.home"), "killer560s-mod-logs", "streamsafemap-1118");
        Path dir = Files.isDirectory(logs) ? logs.resolve("run-" + System.currentTimeMillis())
                : Path.of(System.getProperty("user.dir"), "streamsafe-shots");
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            println("could not make " + dir + ": " + e);
        }
        return dir;
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.2f%%", v * 100);
    }

    private static String str(long[] v) {
        return v == null ? "null" : java.util.Arrays.toString(v);
    }

    private static String str(int[] v) {
        return v == null ? "null" : java.util.Arrays.toString(v);
    }

    private void row(String name, boolean pass, String detail) {
        System.out.println("[" + name + "] " + (pass ? "PASS" : "FAIL") + " - " + detail);
        Report.caseStarted(name);
        Report.caseFinished(name, pass ? "PASS" : "FAIL", "n/a (singleplayer)", detail, List.of(), List.of());
        SuiteVerdict.finished(name);
        if (!pass) {
            all.add(name + ": " + detail);
        }
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}

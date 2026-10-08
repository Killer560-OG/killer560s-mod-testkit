package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 524-ui-map-info-rows (mod branch map-info-bat, killer560 2026-10-07): the Dungeon Map's Extra Info filled in with a
 * real-looking run - "Try to make it smaller and only take up 2 rows", "Abbreviate Mimic to M, Prince to P and Bat to B".
 * At GUI scale 2 and 4: exactly two lines and exactly two separate ink bands, every ink pixel inside the map's width,
 * no label spilling past it, each band at most 5 units tall; row 1 reads Score, Secrets and Crypts and row 2 Deaths
 * then M/P/B each with a tick or a cross; the B tick is DRAWN (switching the bat off turns green pixels in row 2 red);
 * and Split Timers saved on top of the rows is drawn clear of the map's box. {@code TESTKIT_HUDFIX_SHOTS=<dir>} also
 * gets the GUI 2 pictures and a 4x crop of the map and its rows.
 *
 * <p>Run against a jar from before map-info-bat it fails (four lines, no B), which is how the "before" pictures were
 * taken: the setup only writes fields both versions have, or the old bat flag when the new set is missing.
 */
final class MapInfoBatCases {

    private static final String SCF = "scorecalc.ScoreCalculatorFeature";
    private static final String HUD = "hud.HudConfig";
    private static final String MAP = "livemap.LiveMapConfig";
    private static final String MAPPING = "mapping.MappingConfig";
    private static final String SPLITS = "splittimers.SplitTimersConfig";
    private static final int MAP_BG = HudFixCases.MAP_BG;
    private static final int GREEN = 0x55FF55;
    private static final int RED = 0xFF5555;
    private static final Pattern ROW1 = Pattern.compile("^Score \\d{1,3} \\((?:S\\+|S|A|B|C|D)\\)  Secrets \\d{1,3}/(?:\\d{1,3}|\\?)"
            + "  Crypts \\d/5$");
    private static final Pattern ROW2 = Pattern.compile("^Deaths \\d{1,2}(?:  M [✔✘])?  P [✔✘]  B [✔✘](?:x\\d)?$");

    private MapInfoBatCases() {
    }

    static void rows(UiCase c) throws Exception {
        Map<Path, byte[]> saved = HudFixCases.snapshot(c, HUD, MAP, MAPPING, SPLITS);
        int[] window = HudEditorCases.windowSize(c);
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            int[][] windows = {{854, 480, 2}, {1708, 960, 4}};
            for (int[] w : windows) {
                HudEditorCases.setWindow(c, w[0], w[1], w[2]);
                measure(c, "gui" + w[2]);
            }
        } finally {
            c.onClient(mc -> {
                Mod.staticCall("secrets.DungeonState", "setRoomSim", false);
                return null;
            });
            HudFixCases.clearRun(c);
            HudEditorCases.restoreWindow(c, window, gui);
            HudFixCases.restore(c, saved);
        }
    }

    /** A run in progress on M7 (the sim's floor): 54 of 60 secrets, 5 crypts, a death, mimic yes, prince no, bat as given. */
    private static void fill(UiCase c, boolean bat) {
        c.onClient(mc -> {
            Mod.setField(SCF, "runLevel", new WeakReference<>(mc.level));
            Mod.setField(SCF, "secretsPercent", 90.0);
            Mod.setField(SCF, "secretsFound", 54);
            Mod.setField(SCF, "crypts", 5);
            Mod.setField(SCF, "completedRooms", 30);
            Mod.setField(SCF, "clearedPercent", 100);
            Mod.setField(SCF, "deaths", 1);
            Mod.setField(SCF, "puzzleCount", 3);
            Mod.setField(SCF, "puzzlesCompleted", 3);
            Mod.setField(SCF, "secondsElapsed", 400);
            Mod.setField(SCF, "mimicKilled", true);
            Mod.setField(SCF, "princeKilled", false);
            setBat(bat);
            return null;
        });
        c.ticks(15); // the estimate is recomputed every 10 ticks
    }

    @SuppressWarnings("unchecked")
    private static void setBat(boolean bat) {
        Set<String> killers;
        try {
            killers = (Set<String>) Mod.field(SCF, "batKillers");
        } catch (AssertionError old) {
            Mod.setField(SCF, "batKilled", bat); // a jar from before map-info-bat
            return;
        }
        killers.clear();
        if (bat) {
            killers.add("hxmatea");
        }
    }

    private static void measure(UiCase c, String tag) throws Exception {
        c.onClient(mc -> {
            Mod.staticCall("secrets.DungeonState", "setRoomSim", true);
            Object map = Mod.cfg(MAP);
            Mod.call(map, "setEnabled", true);
            Mod.call(map, "setRoomPx", 16);
            Mod.call(map, "setMapBackground", MAP_BG);
            Mod.call(map, "setMapBorderColor", 0);
            Mod.call(map, "save");
            Mod.call(Mod.cfg(MAPPING), "setExtraInfoEnabled", true);
            Mod.call(Mod.cfg(MAPPING), "save");
            Mod.call(Mod.cfg(SPLITS), "setEnabled", false);
            Object hud = Mod.cfg(HUD);
            Mod.call(hud, "setAutoScale", false);
            Mod.call(hud, "setGlobalScale", 1.0f);
            Mod.call(hud, "setScale", "live_map", 1.0f);
            Mod.call(hud, "setPosition", "live_map", 10, 10);
            Mod.call(hud, "save");
            return null;
        });
        HudFixCases.clearRun(c);
        fill(c, true);
        int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
        boolean estimate = c.onClient(mc -> Mod.staticCall(SCF, "currentResult") != null);
        HudFixCases.check(c, estimate, tag + ": no score estimate after filling the run in - the rows would read '?'");
        int[] size = c.onClient(mc -> {
            Object e = Mod.staticCall("hud.HudElementRegistry", "byId", "live_map");
            return new int[]{(Integer) Mod.call(e, "width"), (Integer) Mod.call(e, "height")};
        });
        int mapPx = size[0];
        List<String> lines = lines(c);
        List<String> plain = new ArrayList<>();
        for (String l : lines) {
            plain.add(l.replaceAll("§.", ""));
        }
        c.note(tag + ": GUI scale " + gs + ", Dungeon Map " + size[0] + "x" + size[1] + " units at 10,10 (map " + mapPx
                + " square, Extra Info " + (size[1] - mapPx) + " units), lines " + plain);
        HudFixCases.check(c, lines.size() == 2, tag + ": Extra Info has " + lines.size() + " lines, want exactly 2: " + plain);
        if (plain.size() == 2) {
            HudFixCases.check(c, ROW1.matcher(plain.get(0)).matches(), tag + ": row 1 is \"" + plain.get(0)
                    + "\", want Score, Secrets and Crypts");
            HudFixCases.check(c, ROW2.matcher(plain.get(1)).matches(), tag + ": row 2 is \"" + plain.get(1)
                    + "\", want Deaths then M / P / B with ticks");
            HudFixCases.check(c, plain.get(1).contains("M ✔") && plain.get(1).contains("P ✘")
                    && plain.get(1).contains("B ✔"), tag + ": row 2 \"" + plain.get(1) + "\" does not show mimic yes,"
                    + " prince no, bat yes");
        }

        BufferedImage on = HudFixCases.shot(c, tag + "-bat");
        saveCrop(c, on, gs, mapPx, size[1], tag);
        fill(c, false);
        List<String> offLines = lines(c);
        BufferedImage noBat = HudFixCases.shot(c, tag + "-nobat");
        c.onClient(mc -> {
            Mod.call(Mod.cfg(MAPPING), "setExtraInfoEnabled", false);
            return null;
        });
        c.ticks(4);
        HudFixCases.check(c, size[1] > mapPx, tag + ": the map's box has no Extra Info section - the check would be vacuous");

        // ---- ink: two separate bands inside the map's width ---------------------------------------------------------
        int top = (10 + mapPx) * gs;
        int x0 = 10 * gs;
        int x1 = (10 + mapPx) * gs;
        int y1 = Math.min(on.getHeight(), (10 + size[1]) * gs);
        int[] rows = new int[on.getHeight()];
        int tx0 = Integer.MAX_VALUE, tx1 = -1, n = 0;
        for (int y = top; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if ((on.getRGB(x, y) & 0xFFFFFF) != (MAP_BG & 0xFFFFFF)) {
                    rows[y]++;
                    n++;
                    tx0 = Math.min(tx0, x);
                    tx1 = Math.max(tx1, x);
                }
            }
        }
        int spill = 0;
        for (int y = top; y < y1; y++) {
            for (int x = x1; x < Math.min(on.getWidth(), x1 + 60 * gs); x++) {
                // Labels only: the map's own player arrow (green, drawn at the map's edge when the player stands off
                // the grid, as here) sits beside the rows and is not text.
                if ((on.getRGB(x, y) & 0xFFFFFF) == 0xFFAA00) {
                    spill++;
                }
            }
        }
        List<int[]> bands = new ArrayList<>();
        int start = -1;
        for (int y = top; y <= y1; y++) {
            boolean ink = y < y1 && rows[y] > 0;
            if (ink && start < 0) {
                start = y;
            } else if (!ink && start >= 0) {
                bands.add(new int[]{start, y - 1});
                start = -1;
            }
        }
        StringBuilder b = new StringBuilder();
        double tallest = 0;
        for (int[] band : bands) {
            double hgt = (band[1] - band[0] + 1) / (double) gs;
            tallest = Math.max(tallest, hgt);
            b.append(String.format(Locale.ROOT, " %.1f..%.1f", band[0] / (double) gs, (band[1] + 1) / (double) gs));
        }
        c.note(String.format(Locale.ROOT, "%s: ink %d px, x %.1f..%.1f units (map 10..%d), %d band(s):%s; tallest %.1f"
                        + " units; label pixels past the map's right edge %d", tag, n, tx0 / (double) gs,
                (tx1 + 1) / (double) gs, 10 + mapPx, bands.size(), b, tallest, spill));
        HudFixCases.check(c, n > 0, tag + ": Extra Info drew no text - nothing measured");
        HudFixCases.check(c, tx0 >= x0 && tx1 < x1, tag + ": Extra Info ink outside the map's width");
        HudFixCases.check(c, spill == 0, tag + ": " + spill + " label pixels drawn past the map's right edge");
        HudFixCases.check(c, bands.size() == 2, tag + ": " + bands.size() + " ink bands for 2 rows - rows touch, overlap"
                + " or wrapped");
        HudFixCases.check(c, tallest <= 5.0, String.format(Locale.ROOT, "%s: a row is %.1f units tall, want at most 5",
                tag, tallest));

        // ---- B is drawn: the bat's tick turns into a cross in row 2's pixels ------------------------------------------
        if (bands.size() == 2) {
            int[] r2 = bands.get(1);
            int[] withBat = colours(on, x0, x1, r2);
            int[] without = colours(noBat, x0, x1, r2);
            c.note(String.format(Locale.ROOT, "%s: row 2 green/red pixels with the bat %d/%d, without %d/%d (lines %s)",
                    tag, withBat[0], withBat[1], without[0], without[1], offLines));
            HudFixCases.check(c, withBat[0] > without[0] && withBat[1] < without[1], tag + ": switching the bat off did"
                    + " not turn a green tick in row 2 into a red cross - B's tick is not what is drawn");
        }

        // ---- Split Timers saved on the rows is drawn clear of the map's box --------------------------------------------
        c.onClient(mc -> {
            Mod.call(Mod.cfg(MAPPING), "setExtraInfoEnabled", true);
            Object st = Mod.cfg(SPLITS);
            Mod.call(st, "setEnabled", true);
            Object hud = Mod.cfg(HUD);
            Mod.call(hud, "setScale", "split_timers", 1.0f);
            Mod.call(hud, "setPosition", "split_timers", 12, 10 + mapPx + 2);
            Mod.call(hud, "save");
            return null;
        });
        HudFixCases.injectRun(c);
        c.ticks(10);
        String boxes = c.onClient(mc -> {
            int[] mb = HudFixCases.drawnBox(Mod.staticCall("hud.HudElementRegistry", "byId", "live_map"));
            int[] sb = HudFixCases.drawnBox(Mod.staticCall("hud.HudElementRegistry", "byId", "split_timers"));
            long since = ((Number) Mod.staticCall("hud.HudSeen", "msSince", "split_timers",
                    System.currentTimeMillis())).longValue();
            long mapSince = ((Number) Mod.staticCall("hud.HudSeen", "msSince", "live_map",
                    System.currentTimeMillis())).longValue();
            return mb[0] + " " + mb[1] + " " + mb[2] + " " + mb[3] + " " + sb[0] + " " + sb[1] + " " + sb[2] + " " + sb[3]
                    + " " + since + " " + mapSince;
        });
        String[] v = boxes.split(" ");
        int[] mb = {Integer.parseInt(v[0]), Integer.parseInt(v[1]), Integer.parseInt(v[2]), Integer.parseInt(v[3])};
        int[] sb = {Integer.parseInt(v[4]), Integer.parseInt(v[5]), Integer.parseInt(v[6]), Integer.parseInt(v[7])};
        long since = Long.parseLong(v[8]);
        long mapSince = Long.parseLong(v[9]);
        HudFixCases.shot(c, tag + "-splits");
        c.note(tag + ": Split Timers saved at 12," + (10 + mapPx + 2) + " (on the rows): map box " + HudEditorCases.str(mb)
                + ", Split Timers drawn " + HudEditorCases.str(sb) + "; drew " + since + " ms ago, map " + mapSince + " ms ago");
        HudFixCases.check(c, since < 1000 && mapSince < 1000, tag + ": Split Timers or the map did not draw - vacuous");
        HudFixCases.check(c, mb[3] - mb[1] == size[1], tag + ": the map's drawn box is " + (mb[3] - mb[1])
                + " tall, its height() " + size[1]);
        boolean meets = sb[0] < mb[2] && mb[0] < sb[2] && sb[1] < mb[3] && mb[1] < sb[3];
        HudFixCases.check(c, !meets, tag + ": Split Timers " + HudEditorCases.str(sb) + " is drawn on the Dungeon Map "
                + HudEditorCases.str(mb));
        HudFixCases.clearRun(c);
        c.onClient(mc -> {
            Mod.call(Mod.cfg(SPLITS), "setEnabled", false);
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private static List<String> lines(UiCase c) {
        return c.onClient(mc -> List.copyOf((List<String>) Mod.staticCall(SCF, "mapInfoLines", false)));
    }

    /** {green, red} pixel counts (the tick and cross colours, exact) in one band across the map's width. */
    private static int[] colours(BufferedImage img, int x0, int x1, int[] band) {
        int g = 0, r = 0;
        for (int y = band[0]; y <= band[1]; y++) {
            for (int x = x0; x < x1; x++) {
                int rgb = img.getRGB(x, y) & 0xFFFFFF;
                if (rgb == GREEN) {
                    g++;
                } else if (rgb == RED) {
                    r++;
                }
            }
        }
        return new int[]{g, r};
    }

    /** A 4x nearest-neighbour crop of the map and its rows, for looking at (GUI 2 only, like the pictures). */
    private static void saveCrop(UiCase c, BufferedImage img, int gs, int mapPx, int height, String tag) {
        String dir = System.getenv("TESTKIT_HUDFIX_SHOTS");
        if (dir == null || dir.isBlank() || gs != 2) {
            return;
        }
        try {
            int x = Math.max(0, 6 * gs);
            int y = Math.max(0, (10 + mapPx - 20) * gs);
            int w = Math.min(img.getWidth() - x, (mapPx + 8) * gs);
            int h = Math.min(img.getHeight() - y, (height - mapPx + 24) * gs);
            BufferedImage crop = img.getSubimage(x, y, w, h);
            BufferedImage big = new BufferedImage(w * 4, h * 4, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = big.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.drawImage(crop, 0, 0, w * 4, h * 4, null);
            g2.dispose();
            String jar = Mod.isCheat() ? "cheat" : "legit";
            String mc = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                    .getMetadata().getVersion().getFriendlyString();
            Path out = Path.of(dir).resolve(c.name() + "-" + tag + "-crop4x-" + mc + "-" + jar + ".png");
            Files.createDirectories(out.getParent());
            javax.imageio.ImageIO.write(big, "png", out.toFile());
            c.note("4x crop -> " + out);
        } catch (Exception | Error e) {
            c.note("could not save the crop: " + e);
        }
    }
}

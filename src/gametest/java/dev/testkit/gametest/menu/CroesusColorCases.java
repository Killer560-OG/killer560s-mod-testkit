package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 898 (croesus-colors, 2026-10-12). killer560: "these colors are hard to actually tell if they are different" - the
 * not-looked-at gold border and the unclaimed orange one read the same over Hypixel's green and red run heads.
 * <p>
 * One page with every state twice, over a green head (row 2) and a red head (row 3): unclaimed, needs a key, not looked
 * at, opened, key used. Each state's look is four pixels of the shot: its top-left corner (border / corner mark / tint),
 * a pixel of the key and "?" marks, a pixel of the dark plate behind them, and the middle of the left edge (where the
 * key border runs and the corner marks do not). The centre is the head itself and tells nothing. Two states differ by the
 * largest CIEDE2000 deltaE of the four, so a treatment difference (a mark, a plate, a border) counts as well as hue -
 * magenta and blue are close hues to a protanope, and the key mark is what tells them apart there; every pair on the same head must differ by at least {@link #MIN_DE}, and still by
 * {@link #MIN_DE_CVD} after a protanopia and a deuteranopia simulation (Machado et al. 2009, severity 1). Also: a config
 * still on the old needs-a-key gold moves to the new default, a picked colour stays.
 */
final class CroesusColorCases {

    private CroesusColorCases() {
    }

    static final double MIN_DE = 30.0;
    static final double MIN_DE_CVD = 20.0;
    static final String[] NAMES = {"unclaimed", "needs-key", "not-looked-at", "opened", "key-used"};

    static void register(Session s) {
        MenuSuite.test(s, "898-menu-croesus-state-colours-distinct", CroesusColorCases::colours);
    }

    static JsonObject head(String item, String player, String stateLine, String expiry) {
        return FlipCroesusCases.item(item, "§cThe Catacombs", "§7Floor VII", "", "§7Players:",
                " §a" + player + "§f: §eMage (39)", "", stateLine, "§8Chests expire in " + expiry, "", "§eClick to view chests!");
    }

    static void colours(Session c) throws Exception {
        try (AutoCloseable w = CroesusStableCases.world(c)) {
            c.ctx().runOnClient(mc -> Mod.set("croesus.CroesusConfig", "setHideOpenedRuns", false));
            Map<Integer, JsonObject> heads = new LinkedHashMap<>();
            Map<Integer, JsonObject> on = new LinkedHashMap<>();
            String[] items = {"minecraft:green_concrete", "minecraft:red_concrete"};
            int[] rowStart = {10, 19};
            for (int r = 0; r < 2; r++) {
                int s0 = rowStart[r];
                String it = items[r];
                String p = r == 0 ? "G" : "R";
                heads.put(s0, head(it, p + "new", "§cNo chests opened yet!", "2d 1h!"));
                heads.put(s0 + 1, head(it, p + "key", "§7Opened Chest: §4Bedrock", "2d 2h!"));
                heads.put(s0 + 2, head(it, p + "look", "§7Opened Chest: §4Bedrock", "2d 3h!"));
                heads.put(s0 + 3, head(it, p + "open", "§7Opened Chest: §4Bedrock", "2d 4h!"));
                heads.put(s0 + 4, head(it, p + "used", "§aNo more chests to open!", "2d 5h!"));
                on.put(s0 + 1, FlipCroesusCases.open(CroesusKeyCases.runView("Testkit Idol")));
                on.put(s0 + 3, FlipCroesusCases.open(CroesusKeyCases.runView("Testkit Bead")));
            }
            JsonObject list = CroesusStableCases.list(heads, on);
            for (int slot : new int[]{11, 13, 20, 22}) {
                CroesusStableCases.showList(c, list, 8);
                CroesusStableCases.press(c, slot, CroesusKeyCases.RUN);
            }
            CroesusStableCases.showList(c, list, 15);
            byte[] st = c.onClient(mc -> (byte[]) Mod.staticCall("croesus.ChestProfitFeature", "runStatesForTest"));
            int[] want = {1, 2, 5, 3, 4};
            for (int r = 0; r < 2; r++) {
                for (int i = 0; i < 5; i++) {
                    c.check(st[rowStart[r] + i] == want[i], "slot " + (rowStart[r] + i) + " is state " + st[rowStart[r] + i]
                            + ", want " + want[i] + " (" + NAMES[i] + ")");
                }
            }
            BufferedImage img = CroesusUiCases.shot(c, "colours");
            double[] g = CroesusUiCases.geometry(c);
            double px = img.getWidth() / g[2];
            StringBuilder report = new StringBuilder();
            List<String> failures = new java.util.ArrayList<>();
            String[] bg = {"green", "red"};
            for (int r = 0; r < 2; r++) {
                int[][] samples = new int[5][];
                for (int i = 0; i < 5; i++) {
                    int[] xy = CroesusUiCases.slotXY(c, rowStart[r] + i);
                    double sx = (g[0] + xy[0]) * px;
                    double sy = (g[1] + xy[1]) * px;
                    samples[i] = new int[]{img.getRGB((int) (sx + px / 2), (int) (sy + px / 2)) & 0xFFFFFF,
                            img.getRGB((int) (sx + 13.5 * px), (int) (sy + 2.5 * px)) & 0xFFFFFF,
                            img.getRGB((int) (sx + 10.5 * px), (int) (sy + 5.5 * px)) & 0xFFFFFF,
                            img.getRGB((int) (sx + px / 2), (int) (sy + 8.5 * px)) & 0xFFFFFF};
                }
                for (String vision : new String[]{"normal", "protanopia", "deuteranopia"}) {
                    double min = Double.MAX_VALUE;
                    String worst = "";
                    for (int i = 0; i < 5; i++) {
                        for (int j = i + 1; j < 5; j++) {
                            double d = 0;
                            for (int k = 0; k < 4; k++) {
                                d = Math.max(d, deltaE(sim(samples[i][k], vision), sim(samples[j][k], vision)));
                            }
                            if (d < min) {
                                min = d;
                                worst = NAMES[i] + "/" + NAMES[j];
                            }
                        }
                    }
                    double need = vision.equals("normal") ? MIN_DE : MIN_DE_CVD;
                    if (min < need) {
                        failures.add(bg[r] + " heads, " + vision + ": " + worst + " differ by only deltaE "
                                + String.format("%.1f", min) + " (need " + need + ")");
                    }
                    report.append(bg[r]).append('/').append(vision).append(" min ").append(String.format("%.1f", min))
                            .append(" (").append(worst).append("); ");
                }
                StringBuilder px3 = new StringBuilder();
                for (int i = 0; i < 5; i++) {
                    px3.append(NAMES[i]).append('=').append(String.format("%06X/%06X/%06X/%06X", samples[i][0], samples[i][1], samples[i][2], samples[i][3])).append(' ');
                }
                report.append(bg[r]).append(" samples ").append(px3).append("; ");
            }
            // Every vision and background is measured and noted before any verdict, so one run shows the whole table.
            c.note(report.toString());
            c.check(failures.isEmpty(), String.join("; ", failures));
            List<String> panel = CroesusUiCases.panel(c);
            boolean legend = panel.stream().anyMatch(l -> l.contains("new") && l.contains("key") && l.contains("look")
                    && l.contains("done") && l.contains("used"));
            c.check(legend, "no colour legend line in the info box: " + panel);
            // The old gold default moves to the new one; a picked colour stays.
            String migrate = migration(c);
            c.note("legend " + legend + "; " + migrate);
        }
    }

    static String migration(Session c) throws Exception {
        Path file = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-croesus.json"));
        byte[] was = Files.exists(file) ? Files.readAllBytes(file) : null;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "{\"runKeyColor\": " + 0xFFD000 + ", \"runOpenedColor\": " + 0x2F6FD0 + "}",
                    StandardCharsets.UTF_8);
            c.ctx().runOnClient(mc -> Mod.staticCall("croesus.CroesusConfig", "load"));
            int moved = c.onClient(mc -> (Integer) Mod.call(Mod.cfg("croesus.CroesusConfig"), "getRunKeyColor"));
            int movedOpen = c.onClient(mc -> (Integer) Mod.call(Mod.cfg("croesus.CroesusConfig"), "getRunOpenedColor"));
            Files.writeString(file, "{\"runKeyColor\": " + 0x123456 + ", \"runOpenedColor\": " + 0x654321 + "}",
                    StandardCharsets.UTF_8);
            c.ctx().runOnClient(mc -> Mod.staticCall("croesus.CroesusConfig", "load"));
            int kept = c.onClient(mc -> (Integer) Mod.call(Mod.cfg("croesus.CroesusConfig"), "getRunKeyColor"));
            int keptOpen = c.onClient(mc -> (Integer) Mod.call(Mod.cfg("croesus.CroesusConfig"), "getRunOpenedColor"));
            String said = String.format("old defaults key FFD000 -> %06X, opened 2F6FD0 -> %06X; picked 123456 -> %06X,"
                    + " 654321 -> %06X", moved, movedOpen, kept, keptOpen);
            c.check(moved == 0xCC0096 && movedOpen == 0x578DD9 && kept == 0x123456 && keptOpen == 0x654321,
                    said + " (want CC0096, 578DD9, kept, kept)");
            return said;
        } finally {
            if (was == null) {
                Files.deleteIfExists(file);
            } else {
                Files.write(file, was);
            }
            c.ctx().runOnClient(mc -> Mod.staticCall("croesus.CroesusConfig", "load"));
        }
    }

    // ---- colour maths ---------------------------------------------------------------------------------------------

    static double lin(double v) {
        v /= 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    static int gamma(double v) {
        v = Math.max(0, Math.min(1, v));
        double s = v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
        return (int) Math.round(s * 255);
    }

    /** Machado, Oliveira, Fernandes 2009, severity 1.0, in linear RGB. */
    static int sim(int rgb, String vision) {
        double[][] m = switch (vision) {
            case "protanopia" -> new double[][]{{0.152286, 1.052583, -0.204868}, {0.114503, 0.786281, 0.099216},
                    {-0.003882, -0.048116, 1.051998}};
            case "deuteranopia" -> new double[][]{{0.367322, 0.860646, -0.227968}, {0.280085, 0.672501, 0.047413},
                    {-0.011820, 0.042940, 0.968881}};
            default -> null;
        };
        if (m == null) {
            return rgb;
        }
        double r = lin(rgb >> 16 & 255), g = lin(rgb >> 8 & 255), b = lin(rgb & 255);
        int nr = gamma(m[0][0] * r + m[0][1] * g + m[0][2] * b);
        int ng = gamma(m[1][0] * r + m[1][1] * g + m[1][2] * b);
        int nb = gamma(m[2][0] * r + m[2][1] * g + m[2][2] * b);
        return nr << 16 | ng << 8 | nb;
    }

    static double[] lab(int rgb) {
        double r = lin(rgb >> 16 & 255), g = lin(rgb >> 8 & 255), b = lin(rgb & 255);
        double x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047;
        double y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        double z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883;
        double fx = f(x), fy = f(y), fz = f(z);
        return new double[]{116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    static double f(double t) {
        return t > 216.0 / 24389 ? Math.cbrt(t) : (24389.0 / 27 * t + 16) / 116;
    }

    /** CIEDE2000 (Sharma, Wu, Dalal 2005): CIE76 rates orange against gold at 39 where they look alike on a 1 px border. */
    static double deltaE(int c1, int c2) {
        double[] p = lab(c1), q = lab(c2);
        double cb = (Math.hypot(p[1], p[2]) + Math.hypot(q[1], q[2])) / 2;
        double g = 0.5 * (1 - Math.sqrt(Math.pow(cb, 7) / (Math.pow(cb, 7) + Math.pow(25, 7))));
        double a1 = (1 + g) * p[1], a2 = (1 + g) * q[1];
        double c1p = Math.hypot(a1, p[2]), c2p = Math.hypot(a2, q[2]);
        double h1 = (Math.toDegrees(Math.atan2(p[2], a1)) + 360) % 360;
        double h2 = (Math.toDegrees(Math.atan2(q[2], a2)) + 360) % 360;
        double dL = q[0] - p[0], dC = c2p - c1p, dh = h2 - h1;
        if (c1p * c2p == 0) {
            dh = 0;
        } else if (dh > 180) {
            dh -= 360;
        } else if (dh < -180) {
            dh += 360;
        }
        double dH = 2 * Math.sqrt(c1p * c2p) * Math.sin(Math.toRadians(dh / 2));
        double lb = (p[0] + q[0]) / 2, cbp = (c1p + c2p) / 2;
        double hb = c1p * c2p == 0 ? h1 + h2 : Math.abs(h1 - h2) <= 180 ? (h1 + h2) / 2 : (h1 + h2 + 360) / 2;
        double t = 1 - 0.17 * Math.cos(Math.toRadians(hb - 30)) + 0.24 * Math.cos(Math.toRadians(2 * hb))
                + 0.32 * Math.cos(Math.toRadians(3 * hb + 6)) - 0.20 * Math.cos(Math.toRadians(4 * hb - 63));
        double dth = 30 * Math.exp(-Math.pow((hb - 275) / 25, 2));
        double rc = 2 * Math.sqrt(Math.pow(cbp, 7) / (Math.pow(cbp, 7) + Math.pow(25, 7)));
        double sl = 1 + 0.015 * Math.pow(lb - 50, 2) / Math.sqrt(20 + Math.pow(lb - 50, 2));
        double sc = 1 + 0.045 * cbp, sh = 1 + 0.015 * cbp * t, rt = -Math.sin(Math.toRadians(2 * dth)) * rc;
        return Math.sqrt(Math.pow(dL / sl, 2) + Math.pow(dC / sc, 2) + Math.pow(dH / sh, 2) + rt * (dC / sc) * (dH / sh));
    }
}

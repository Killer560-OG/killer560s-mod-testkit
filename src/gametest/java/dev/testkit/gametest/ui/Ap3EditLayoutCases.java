package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 1110-ui-ap3-edit-layout (mod ap3editspace-1110, cheat jar): the AP3 node editor's panel grows with the rows laid out, for
 * every node type, so nothing hangs below the border (Save and Cancel sat half outside it) and no row leaves a half empty.
 * Per type, with the editor open (and again with the type dropdown open): every widget inside the panel, no two widgets
 * overlap, the Save/Cancel bottom sits 6-14 px above the panel's bottom edge, the panel is on screen, and each row of
 * widgets reaches the panel's right padding (an empty right half would stop short).
 */
final class Ap3EditLayoutCases {

    private static final String SCREEN = "ap3.Ap3EditScreen";
    private static final String[] TYPES = {"ALIGN", "AXIS_ALIGN", "WALK", "RUN", "LEAP", "LEAP_COUNTER", "TERMINAL", "STOP", "LOOK",
            "BOOM", "STOPWATCH", "JUMP", "EDGE", "BLOCK", "FAST_ALIGN", "PATH", "NO_GO", "TERM_AURA", "USE", "BLINK_CHARGE",
            "BLINK_RELEASE", "CRYSTAL"};
    private static final int PAD = 10;

    private Ap3EditLayoutCases() {
    }

    static void run(UiCase c) throws Exception {
        if (!Mod.isCheat()) {
            c.note("legit jar: AP3 is cheat-only, skipped");
            return;
        }
        Object cfg = Mod.cfg("ap3.Ap3Config");
        String oldFile = (String) Mod.call(cfg, "getChainsFile");
        boolean oldForce = (Boolean) Mod.staticCall("ap3.Ap3Feature", "isForceDungeon");
        Path file = null;
        try {
            StringBuilder nodes = new StringBuilder();
            for (int i = 0; i < TYPES.length; i++) {
                nodes.append(i == 0 ? "" : ",").append("{ \"type\": \"").append(TYPES[i]).append("\", \"x\": ").append(940.5 + i * 3)
                        .append(", \"y\": 70.0, \"z\": 20.5, \"width\": 1.0, \"length\": 1.0 }");
            }
            String content = "{ \"version\": 2, \"chains\": { \"BOSS\": { \"class\": \"\", \"nodes\": [" + nodes + "] } } }";
            file = c.onClient(mc -> {
                Mod.call(cfg, "setChainsFile", "testkit-ap3-layout-1110");
                Mod.staticCall("ap3.Ap3Feature", "setForceDungeon", true);
                Path f = (Path) Mod.staticCall("ap3.Ap3Store", "file");
                try {
                    Files.createDirectories(f.getParent());
                    Files.writeString(f, content, StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new AssertionError("cannot write " + f, e);
                }
                Mod.staticCall("ap3.Ap3Store", "reload");
                return f;
            });
            int checked = 0;
            for (int i = 0; i < TYPES.length; i++) {
                Screen s = open(c, i);
                checked += verify(c, s, TYPES[i]);
                // The type dropdown pushes the rest down; the panel must still contain it.
                AbstractWidget typeBtn = c.onClient(mc -> {
                    for (Object o : s.children()) {
                        if (o instanceof AbstractWidget w && w.visible && ModScreenDriver.label(w).trim().startsWith("Type")) {
                            return w;
                        }
                    }
                    return null;
                });
                c.check(typeBtn != null, TYPES[i] + ": no Type button");
                Ap3EditCases.clickFor1026(c, s, typeBtn);
                c.ticks(2);
                checked += verify(c, s, TYPES[i] + " (type list open)");
            }
            c.check(checked == TYPES.length * 2, "only " + checked + " of " + TYPES.length * 2 + " layouts were checked");
            c.note("checked " + checked + " editor layouts");
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.call(cfg, "setChainsFile", oldFile);
                Mod.staticCall("ap3.Ap3Feature", "setForceDungeon", oldForce);
                Mod.staticCall("ap3.Ap3Store", "reload");
                return null;
            });
            if (file != null) {
                Files.deleteIfExists(file);
            }
        }
    }

    /** Checks one open editor; returns 1 once it asserted (so a skipped check cannot pass silently). */
    private static int verify(UiCase c, Screen s, String what) {
        int[] p = c.onClient(mc -> (int[]) Mod.call(s, "themedPanelBounds"));
        int sw = c.onClient(mc -> s.width);
        int sh = c.onClient(mc -> s.height);
        List<int[]> r = c.onClient(mc -> {
            List<int[]> out = new ArrayList<>();
            for (Object o : s.children()) {
                if (o instanceof AbstractWidget w && w.visible) {
                    out.add(new int[]{w.getX(), w.getY(), w.getWidth(), w.getHeight()});
                }
            }
            return out;
        });
        c.check(r.size() > 8, what + ": only " + r.size() + " widgets");
        c.check(p[2] > 0 && p[3] > 0, what + ": no panel bounds");
        c.check(p[0] >= 0 && p[0] + p[2] <= sw, what + ": panel off screen sideways " + java.util.Arrays.toString(p) + " in " + sw);
        int bottom = 0;
        for (int i = 0; i < r.size(); i++) {
            int[] a = r.get(i);
            bottom = Math.max(bottom, a[1] + a[3]);
            boolean in = a[0] >= p[0] && a[1] >= p[1] && a[0] + a[2] <= p[0] + p[2] && a[1] + a[3] <= p[1] + p[3];
            c.check(in, what + ": widget " + java.util.Arrays.toString(a) + " outside panel " + java.util.Arrays.toString(p));
            for (int j = i + 1; j < r.size(); j++) {
                int[] b = r.get(j);
                boolean overlap = a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
                c.check(!overlap, what + ": widgets overlap " + java.util.Arrays.toString(a) + " / " + java.util.Arrays.toString(b));
            }
        }
        int pad = p[1] + p[3] - bottom;
        c.check(pad >= 6 && pad <= 14, what + ": bottom padding " + pad + " (panel " + java.util.Arrays.toString(p) + ")");
        c.check(p[1] >= 0 && (p[1] + p[3] <= sh || p[1] <= 4), what + ": panel " + java.util.Arrays.toString(p) + " off a " + sh + " high screen");
        Map<Integer, Integer> rightByRow = new TreeMap<>();
        for (int[] a : r) {
            rightByRow.merge(a[1], a[0] + a[2], Math::max);
        }
        for (Map.Entry<Integer, Integer> e : rightByRow.entrySet()) {
            c.check(Math.abs(e.getValue() - (p[0] + p[2] - PAD)) <= 1,
                    what + ": the row at y=" + e.getKey() + " stops at x=" + e.getValue() + " (right padding is " + (p[0] + p[2] - PAD) + ")");
        }
        return 1;
    }

    private static Screen open(UiCase c, int index) {
        Screen s = c.onClient(mc -> {
            try {
                Constructor<?> k = Mod.cls(SCREEN).getConstructor(Screen.class, int.class);
                Screen screen = (Screen) k.newInstance(null, index);
                McCompat.setScreen(mc, screen);
                return screen;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("cannot open " + SCREEN + ": " + UiCase.describe(e), e);
            }
        });
        c.ticks(3);
        c.check(c.onClient(McCompat::screen) == s, "the AP3 editor did not stay open");
        return s;
    }
}

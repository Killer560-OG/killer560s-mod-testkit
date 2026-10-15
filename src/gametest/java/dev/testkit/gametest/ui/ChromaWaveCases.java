package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * The chroma waterfall (mod chroma-waterfall, killer560 2026-10-13: "make it so it waterfalls the color from top left to
 * bottom right in a wavy manner"). Every chroma colour now comes from {@code util/ChromaWave}: the hue at a point is
 * {@code (x + y + wobble) / span - t / period}. The chroma clock is frozen with {@code ChromaWave.setClockOverride} so
 * two frames are a known time apart, and everything is judged from screenshots.
 *
 * <ul>
 *   <li>971: a real enchanted-item tooltip (Enchant Colours, Perfect Chroma) drawn on black at a fixed place, with two
 *       IDENTICAL enchant lines, so each glyph of line 2 sits exactly under its twin on line 1. (1) hue rises along a
 *       line and the top-left and bottom-right glyphs differ, (2) at a fixed pixel the hue falls over time - the bands
 *       move toward the bottom right, (3) a line-2 glyph is a fixed step past its line-1 twin (a per-line restart
 *       would make them equal). Control: Enchant Colours off leaves no chroma pixel.</li>
 *   <li>972: the Custom Scoreboard's Chroma Border, drawn through its own {@code drawRoundedBorder}: corners differ and
 *       every border pixel's hue moves over time.</li>
 *   <li>973: the Custom Crosshair's Chroma: its pixels change over time and match ChromaWave at the screen centre.</li>
 * </ul>
 */
final class ChromaWaveCases {

    private static final String WAVE = "util.ChromaWave";
    private static final String ENCHANT_CFG = "enchantcolors.EnchantColorsConfig";
    private static final long T0 = 1_000_000_000_000L;
    private static final Path SHOTS = Path.of("C:/Users/Hunter/killer560s-mod-logs/chroma-shots");

    private ChromaWaveCases() {
    }

    // ------------------------------------------------------------------ 971

    static void enchantTooltip(UiCase c) throws Exception {
        Object cfg = Mod.cfg(ENCHANT_CFG);
        boolean wasEnabled = (Boolean) Mod.call(cfg, "isEnabledRaw");
        boolean wasChroma = (Boolean) Mod.call(cfg, "isPerfectChroma");
        float wasSpeed = (Float) Mod.call(cfg, "getChromaSpeed");
        float wasSize = (Float) Mod.call(cfg, "getChromaSize");
        try {
            ItemStack stack = c.onClient(mc -> perfectStack());
            int[] at = {30, 40};
            Canvas canvas = c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setPerfectChroma", true);
                Mod.call(cfg, "setChromaSpeed", 1f);
                Mod.call(cfg, "setChromaSize", 1f);
                Mod.staticCall("enchantcolors.EnchantColorsFeature", "clearCache");
                Mod.staticCall(WAVE, "setClockOverride", T0);
                Canvas s = new Canvas((g, scr) -> g.setTooltipForNextFrame(scr.font(),
                        Screen.getTooltipFromItem(Minecraft.getInstance(), stack), Optional.empty(), at[0], at[1]));
                McCompat.setScreen(mc, s);
                return s;
            });
            c.ticks(6);
            // The lines the tooltip really shows: the enchant segments must carry the Perfect spec's colour.
            int specSegments = c.onClient(mc -> {
                Object spec = Mod.staticCall("enchantcolors.EnchantColorsFeature", "perfectChroma");
                TextColor tc = (TextColor) Mod.call(spec, "textColor");
                int[] n = {0};
                for (Component line : Screen.getTooltipFromItem(mc, stack)) {
                    line.visit((Style st, String s) -> {
                        if (st.getColor() == tc && !s.isBlank()) {
                            n[0]++;
                        }
                        return Optional.empty();
                    }, Style.EMPTY);
                }
                return n[0];
            });
            c.note("tooltip segments carrying the Perfect chroma spec: " + specSegments);
            c.check(specSegments >= 4, "only " + specSegments + " enchant segment(s) carry the chroma spec (want 4)");

            Frame f0 = shot(c, canvas, "t0");
            setClock(c, T0 + 250);
            Frame f1 = shot(c, canvas, "t250");
            setClock(c, T0 + 500);
            shot(c, canvas, "t500");
            setClock(c, T0 + 750);
            shot(c, canvas, "t750");

            // Control: Enchant Colours off -> the same lines in Hypixel's grey, no chroma pixel at all.
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", false);
                Mod.staticCall("enchantcolors.EnchantColorsFeature", "clearCache");
                return null;
            });
            setClock(c, T0);
            Frame off = shot(c, canvas, "off");
            // Whatever is saturated with the feature off (the mod's own tooltip frame) is not chroma: masked out.
            List<int[]> offPx = chromaPixels(off.img, null);
            boolean[][] mask = new boolean[off.img.getWidth()][off.img.getHeight()];
            for (int[] p : offPx) {
                mask[p[0]][p[1]] = true;
            }
            List<int[]> p0 = chromaPixels(f0.img, mask);
            List<int[]> p1 = chromaPixels(f1.img, mask);
            c.note("frame " + f0.img.getWidth() + "x" + f0.img.getHeight() + ", " + f0.scale + " px per GUI unit; chroma px: on "
                    + p0.size() + ", 250 ms later " + p1.size() + "; " + offPx.size() + " saturated px with the feature off"
                    + " (the tooltip frame) masked out");
            c.check(p0.size() > 200, "only " + p0.size() + " chroma px drawn - the tooltip's Perfect enchants are not chroma");
            if (p0.size() <= 200) {
                return;
            }

            // Text lines: rows holding chroma pixels, split at gaps.
            List<int[]> bands = bands(p0);
            c.note("chroma line bands (pixel rows): " + bands.stream().map(Arrays::toString).toList());
            c.check(bands.size() >= 2, "fewer than 2 chroma lines: " + bands.size());
            if (bands.size() < 2) {
                return;
            }
            int step = bands.get(1)[0] - bands.get(0)[0];
            float[][] hue0 = hueMap(f0.img, p0);
            float[][] hue1 = hueMap(f1.img, p1);

            // (1) along the diagonal: hue rises toward the right (pairs 10 GUI units apart on line 1), and the top-left
            //     glyph differs from the bottom-right one.
            int dx = (int) Math.round(10 * f0.scale);
            List<Float> along = new ArrayList<>();
            int[] b0 = bands.get(0);
            for (int[] p : p0) {
                if (p[1] < b0[0] || p[1] > b0[1]) {
                    continue;
                }
                float h2 = at(hue0, p[0] + dx, p[1]);
                if (h2 >= 0) {
                    along.add(circ(h2 - hue0[p[0]][p[1]]));
                }
            }
            float alongMedian = median(along);
            float expectAlong = 10f / 160f * 360f;
            int[] tl = extreme(p0, true);
            int[] br = extreme(p0, false);
            float tlbr = Math.abs(circ(hue0[br[0]][br[1]] - hue0[tl[0]][tl[1]]));
            c.note(String.format("(1) along a line: %d pairs 10 GUI apart, median hue step %+.1f deg (formula %+.1f, wobble"
                            + " moves it); top-left px %d,%d hue %.0f vs bottom-right px %d,%d hue %.0f: %.0f deg apart",
                    along.size(), alongMedian, expectAlong, tl[0], tl[1], hue0[tl[0]][tl[1]], br[0], br[1],
                    hue0[br[0]][br[1]], tlbr));
            c.check(along.size() > 50 && alongMedian > 8 && alongMedian < 45,
                    "hue does not rise steadily along the line: median " + alongMedian + " over " + along.size());
            c.check(tlbr > 15, "top-left and bottom-right glyphs are the same color (" + tlbr + " deg apart)");

            // (2) over time: at a fixed pixel the hue falls by ~30 deg in 250 ms (speed 1 = 3 s a cycle), the opposite
            //     sign of the rise toward the bottom right, so the bands move toward the bottom right.
            List<Float> moved = new ArrayList<>();
            for (int[] p : p0) {
                float h = at(hue1, p[0], p[1]);
                if (h >= 0) {
                    moved.add(circ(h - hue0[p[0]][p[1]]));
                }
            }
            float movedMedian = median(moved);
            c.note(String.format("(2) 250 ms later: %d px compared, median hue change %+.1f deg (formula -30, wobble"
                    + " drift moves it)", moved.size(), movedMedian));
            c.check(moved.size() > 200 && movedMedian < -15 && movedMedian > -45,
                    "the pattern did not move toward the bottom right: median change " + movedMedian);

            // (3) line 2 continues line 1: each glyph pixel against its twin one line below.
            List<Float> down = new ArrayList<>();
            for (int[] p : p0) {
                if (p[1] < b0[0] || p[1] > b0[1]) {
                    continue;
                }
                float h2 = at(hue0, p[0], p[1] + step);
                if (h2 >= 0) {
                    down.add(circ(h2 - hue0[p[0]][p[1]]));
                }
            }
            float downMedian = median(down);
            float stepGui = (float) (step / f0.scale);
            c.note(String.format("(3) line 2 under line 1 (%.1f GUI below): %d twin px, median hue step %+.1f deg (formula"
                    + " %+.1f; a per-line restart would be 0)", stepGui, down.size(), downMedian, stepGui / 160f * 360f));
            c.check(down.size() > 50 && downMedian > 8 && downMedian < 40,
                    "line 2 does not continue line 1's band: median twin step " + downMedian + " over " + down.size());
        } finally {
            try {
                c.onClient(mc -> {
                    Mod.staticCall(WAVE, "setClockOverride", -1L);
                    Mod.call(cfg, "setEnabled", wasEnabled);
                    Mod.call(cfg, "setPerfectChroma", wasChroma);
                    Mod.call(cfg, "setChromaSpeed", wasSpeed);
                    Mod.call(cfg, "setChromaSize", wasSize);
                    Mod.call(cfg, "save");
                    McCompat.setScreen(mc, null);
                    return null;
                });
            } catch (Throwable t) {
                c.note("cleanup: " + UiCase.describe(t));
            }
        }
    }

    /** A paper with four Perfect enchants on two identical lore lines (tier max levels read from the mod's table). */
    private static ItemStack perfectStack() {
        @SuppressWarnings("unchecked")
        Map<String, int[]> tiers = (Map<String, int[]>) Mod.field("enchantcolors.EnchantColorsDefaults", "TIERS");
        int sharp = tiers.get("sharpness")[1];
        int crit = tiers.get("critical")[1];
        CompoundTag ench = new CompoundTag();
        ench.putInt("sharpness", sharp);
        ench.putInt("critical", crit);
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "CHROMA_TEST");
        tag.put("enchantments", ench);
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Chroma Test").withStyle(st -> st.withColor(TextColor.fromRgb(0xFFFFFF))
                .withItalic(false)));
        String text = "Sharpness " + sharp + ", Critical " + crit;
        Component line = Component.literal(text).withStyle(st -> st.withColor(TextColor.fromRgb(0xAAAAAA)).withItalic(false));
        stack.set(DataComponents.LORE, new ItemLore(List.of(line, line)));
        return stack;
    }

    // ------------------------------------------------------------------ 974

    /**
     * Every Turbo-Crop VII is Perfect chroma (mod turbo-chroma, killer560 2026-10-13: "all turbo crop 7's are not chroma'd
     * either like they should be"). Only turbo_cactus / turbo_coco had an id-to-lore alias, so "turbo_wheat" was looked
     * up as "turbo wheat" while the lore prints "Turbo-Wheat" and the enchant was never tiered. A hoe with real NBT
     * enchantments turbo_wheat 7, turbo_carrot 7 and turbo_cactus 7 (the aliased control), one lore line each: all three
     * lines must draw chroma - hue rising along the line and falling over 250 ms, like 971. A second hoe with turbo_wheat
     * 5 must draw Turbo-Wheat V in the static Great colour: same hue along the line and over time.
     */
    static void turboCrops(UiCase c) throws Exception {
        Object cfg = Mod.cfg(ENCHANT_CFG);
        boolean wasEnabled = (Boolean) Mod.call(cfg, "isEnabledRaw");
        boolean wasChroma = (Boolean) Mod.call(cfg, "isPerfectChroma");
        boolean wasTrueMax = (Boolean) Mod.call(cfg, "isTrueMaxDetection");
        float wasSpeed = (Float) Mod.call(cfg, "getChromaSpeed");
        float wasSize = (Float) Mod.call(cfg, "getChromaSize");
        int wasGreat = (Integer) Mod.call(cfg, "getGreatColor");
        int great = 0xFFFFAA00;
        try {
            String[] sevenNames = {"Turbo-Wheat VII", "Turbo-Carrot VII", "Turbo-Cacti VII"};
            ItemStack seven = c.onClient(mc -> turboHoe(new String[]{"turbo_wheat", "turbo_carrot", "turbo_cactus"},
                    new int[]{7, 7, 7}, sevenNames));
            ItemStack five = c.onClient(mc -> turboHoe(new String[]{"turbo_wheat"}, new int[]{5},
                    new String[]{"Turbo-Wheat V"}));
            java.util.concurrent.atomic.AtomicReference<ItemStack> shown = new java.util.concurrent.atomic.AtomicReference<>(seven);
            int[] at = {30, 40};
            Canvas canvas = c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setPerfectChroma", true);
                Mod.call(cfg, "setTrueMaxDetection", true);
                Mod.call(cfg, "setChromaSpeed", 1f);
                Mod.call(cfg, "setChromaSize", 1f);
                Mod.call(cfg, "setGreatColor", great);
                Mod.staticCall("enchantcolors.EnchantColorsFeature", "clearCache");
                Mod.staticCall(WAVE, "setClockOverride", T0);
                Canvas s = new Canvas((g, scr) -> g.setTooltipForNextFrame(scr.font(),
                        Screen.getTooltipFromItem(Minecraft.getInstance(), shown.get()), Optional.empty(), at[0], at[1]));
                McCompat.setScreen(mc, s);
                return s;
            });
            c.ticks(6);

            // The styles the tooltip really carries, per enchant segment.
            String[] allNames = {"Turbo-Wheat VII", "Turbo-Carrot VII", "Turbo-Cacti VII", "Turbo-Wheat V"};
            String[] styleOf = c.onClient(mc -> {
                Object spec = Mod.staticCall("enchantcolors.EnchantColorsFeature", "perfectChroma");
                TextColor tc = (TextColor) Mod.call(spec, "textColor");
                String[] out = new String[allNames.length];
                for (int i = 0; i < allNames.length; i++) {
                    ItemStack st = i < 3 ? seven : five;
                    String want = allNames[i];
                    for (Component line : Screen.getTooltipFromItem(mc, st)) {
                        int k = i;
                        line.visit((Style sty, String s) -> {
                            if (s.trim().equals(want) && out[k] == null) {
                                TextColor col = sty.getColor();
                                out[k] = col == tc ? "chroma" : col == null ? "none" : String.format("#%06X", col.getValue());
                            }
                            return Optional.empty();
                        }, Style.EMPTY);
                    }
                    if (out[i] == null) {
                        out[i] = "not one segment";
                    }
                }
                return out;
            });
            String greatHex = String.format("#%06X", great & 0xFFFFFF);
            for (int i = 0; i < allNames.length; i++) {
                c.note("style of " + allNames[i] + ": " + styleOf[i]);
            }
            for (int i = 0; i < 3; i++) {
                c.check("chroma".equals(styleOf[i]), allNames[i] + " is not styled Perfect chroma: " + styleOf[i]);
            }
            c.check(greatHex.equals(styleOf[3]), "Turbo-Wheat V is not the static Great color " + greatHex + ": " + styleOf[3]);

            // ---- the three VII lines, from pixels
            Frame f0 = shot(c, canvas, "vii-t0");
            setClock(c, T0 + 250);
            Frame f1 = shot(c, canvas, "vii-t250");
            boolean[][] mask = offMask(c, cfg, canvas, "vii-off");
            List<int[]> p0 = chromaPixels(f0.img, mask);
            List<int[]> p1 = chromaPixels(f1.img, mask);
            List<int[]> bands = bands(p0);
            c.note("VII hoe: " + p0.size() + " saturated px, line bands " + bands.stream().map(Arrays::toString).toList());
            c.check(bands.size() == 3, "want 3 colored enchant lines on the VII hoe, found " + bands.size());
            float[][] hue0 = hueMap(f0.img, p0);
            float[][] hue1 = hueMap(f1.img, p1);
            int dx = (int) Math.round(10 * f0.scale);
            for (int b = 0; b < Math.min(3, bands.size()); b++) {
                int[] band = bands.get(b);
                List<Float> along = new ArrayList<>();
                List<Float> moved = new ArrayList<>();
                for (int[] p : p0) {
                    if (p[1] < band[0] || p[1] > band[1]) {
                        continue;
                    }
                    float h2 = at(hue0, p[0] + dx, p[1]);
                    if (h2 >= 0) {
                        along.add(circ(h2 - hue0[p[0]][p[1]]));
                    }
                    float h1 = at(hue1, p[0], p[1]);
                    if (h1 >= 0) {
                        moved.add(circ(h1 - hue0[p[0]][p[1]]));
                    }
                }
                float a = median(along);
                float m = median(moved);
                c.note(String.format("%s: %d pairs 10 GUI apart, median hue step %+.1f deg (formula %+.1f); %d px 250 ms"
                        + " later, median change %+.1f deg (formula -30)", sevenNames[b], along.size(), a, 10f / 160f * 360f,
                        moved.size(), m));
                c.check(along.size() > 30 && a > 8 && a < 45,
                        sevenNames[b] + " is not chroma along the line: median step " + a + " over " + along.size());
                c.check(moved.size() > 30 && m < -15 && m > -45,
                        sevenNames[b] + " does not move over time: median change " + m + " over " + moved.size());
            }

            // ---- Turbo-Wheat V: a static tier colour
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", true);
                Mod.staticCall("enchantcolors.EnchantColorsFeature", "clearCache");
                shown.set(five);
                return null;
            });
            setClock(c, T0);
            Frame g0 = shot(c, canvas, "v-t0");
            setClock(c, T0 + 250);
            Frame g1 = shot(c, canvas, "v-t250");
            boolean[][] mask5 = offMask(c, cfg, canvas, "v-off");
            List<int[]> q0 = chromaPixels(g0.img, mask5);
            List<int[]> q1 = chromaPixels(g1.img, mask5);
            float[][] hq0 = hueMap(g0.img, q0);
            float[][] hq1 = hueMap(g1.img, q1);
            float greatHue = hue(great);
            List<Float> off = new ArrayList<>();
            float worst = 0;
            for (int[] p : q0) {
                float d = Math.abs(circ(hq0[p[0]][p[1]] - greatHue));
                off.add(d);
                worst = Math.max(worst, d);
            }
            List<Float> movedV = new ArrayList<>();
            for (int[] p : q0) {
                float h1 = at(hq1, p[0], p[1]);
                if (h1 >= 0) {
                    movedV.add(Math.abs(circ(h1 - hq0[p[0]][p[1]])));
                }
            }
            float medOff = median(off);
            float medMoved = median(movedV);
            c.note(String.format("Turbo-Wheat V: %d saturated px in %d band(s); median %.1f deg from Great's hue %.0f (worst"
                            + " %.1f); %d px 250 ms later, median |change| %.1f deg", q0.size(), bands(q0).size(), medOff,
                    greatHue, worst, movedV.size(), medMoved));
            c.check(q0.size() > 30 && bands(q0).size() == 1, "Turbo-Wheat V drew " + q0.size() + " colored px in "
                    + bands(q0).size() + " band(s), want one line in the Great color");
            c.check(medOff < 6 && worst < 20, "Turbo-Wheat V is not the Great color: median " + medOff + " deg off");
            c.check(movedV.size() > 30 && medMoved < 3, "Turbo-Wheat V changes color over time: median " + medMoved);
        } finally {
            try {
                c.onClient(mc -> {
                    Mod.staticCall(WAVE, "setClockOverride", -1L);
                    Mod.call(cfg, "setEnabled", wasEnabled);
                    Mod.call(cfg, "setPerfectChroma", wasChroma);
                    Mod.call(cfg, "setTrueMaxDetection", wasTrueMax);
                    Mod.call(cfg, "setChromaSpeed", wasSpeed);
                    Mod.call(cfg, "setChromaSize", wasSize);
                    Mod.call(cfg, "setGreatColor", wasGreat);
                    Mod.call(cfg, "save");
                    Mod.staticCall("enchantcolors.EnchantColorsFeature", "clearCache");
                    McCompat.setScreen(mc, null);
                    return null;
                });
            } catch (Throwable t) {
                c.note("cleanup: " + UiCase.describe(t));
            }
        }
    }

    /** The same tooltip with Enchant Colours off: whatever is saturated then (the tooltip frame) is not enchant colour. */
    private static boolean[][] offMask(UiCase c, Object cfg, Canvas canvas, String label) throws Exception {
        c.onClient(mc -> {
            Mod.call(cfg, "setEnabled", false);
            Mod.staticCall("enchantcolors.EnchantColorsFeature", "clearCache");
            return null;
        });
        setClock(c, T0);
        Frame off = shot(c, canvas, label);
        boolean[][] mask = new boolean[off.img.getWidth()][off.img.getHeight()];
        for (int[] p : chromaPixels(off.img, null)) {
            mask[p[0]][p[1]] = true;
        }
        return mask;
    }

    /** A Hypixel-style hoe: NBT {@code enchantments} ids/levels and one grey lore line per enchant, as the game prints it. */
    private static ItemStack turboHoe(String[] ids, int[] levels, String[] loreLines) {
        CompoundTag ench = new CompoundTag();
        for (int i = 0; i < ids.length; i++) {
            ench.putInt(ids[i], levels[i]);
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "THEORETICAL_HOE_WHEAT_3");
        tag.put("enchantments", ench);
        ItemStack stack = new ItemStack(Items.DIAMOND_HOE);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Turbo Test Hoe").withStyle(st -> st.withColor(TextColor.fromRgb(0xFFFFFF))
                .withItalic(false)));
        List<Component> lore = new ArrayList<>();
        for (String l : loreLines) {
            lore.add(Component.literal(l).withStyle(st -> st.withColor(TextColor.fromRgb(0xAAAAAA)).withItalic(false)));
        }
        stack.set(DataComponents.LORE, new ItemLore(lore));
        return stack;
    }

    // ------------------------------------------------------------------ 972

    static void scoreboardBorder(UiCase c) throws Exception {
        String sbCfg = "scoreboard.CustomScoreboardConfig";
        Object cfg = Mod.cfg(sbCfg);
        boolean wasChroma = (Boolean) Mod.call(cfg, "isChromaBorder");
        int wasSpeed = (Integer) Mod.call(cfg, "getChromaSpeed");
        int wasColor = (Integer) Mod.call(cfg, "getBorderColor");
        try {
            Method draw = Mod.cls("scoreboard.CustomScoreboardFeature").getDeclaredMethod("drawRoundedBorder",
                    GuiGraphicsExtractor.class, int.class, int.class, int.class, int.class, int.class, int.class,
                    Mod.cls(sbCfg), long.class, float.class);
            draw.setAccessible(true);
            int bx = 40, by = 40, bw = 100, bh = 100, bt = 3;
            Canvas canvas = c.onClient(mc -> {
                Mod.call(cfg, "setChromaBorder", true);
                Mod.call(cfg, "setChromaSpeed", 5);
                Mod.call(cfg, "setBorderColor", 0xFFFFFFFF);
                Mod.staticCall(WAVE, "setClockOverride", T0);
                Canvas s = new Canvas((g, scr) -> {
                    try {
                        draw.invoke(null, g, bx, by, bw, bh, 0, bt, cfg, 0L, 1f);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                });
                McCompat.setScreen(mc, s);
                return s;
            });
            c.ticks(6);
            Frame f0 = shot(c, canvas, "t0");
            setClock(c, T0 + 250);
            Frame f1 = shot(c, canvas, "t250");
            setClock(c, T0 + 500);
            shot(c, canvas, "t500");
            List<int[]> p0 = chromaPixels(f0.img, null);
            float[][] hue0 = hueMap(f0.img, p0);
            float[][] hue1 = hueMap(f1.img, chromaPixels(f1.img, null));
            double s = f0.scale;
            int[] tl = {(int) ((bx + 1) * s), (int) ((by + 1) * s)};
            int[] br = {(int) ((bx + bw - 2) * s), (int) ((by + bh - 2) * s)};
            float htl = at(hue0, tl[0], tl[1]);
            float hbr = at(hue0, br[0], br[1]);
            List<Float> moved = new ArrayList<>();
            for (int[] p : p0) {
                float h = at(hue1, p[0], p[1]);
                if (h >= 0) {
                    moved.add(circ(h - hue0[p[0]][p[1]]));
                }
            }
            float med = median(moved);
            c.note(String.format("border chroma px %d; top-left hue %.0f, bottom-right hue %.0f; 250 ms later %d px"
                    + " compared, median change %+.1f deg (formula -30)", p0.size(), htl, hbr, moved.size(), med));
            c.check(p0.size() > 300, "the chroma border drew only " + p0.size() + " saturated px");
            c.check(htl >= 0 && hbr >= 0 && Math.abs(circ(hbr - htl)) > 30,
                    "the border's corners are not different colors: " + htl + " vs " + hbr);
            c.check(moved.size() > 300 && med < -15 && med > -45, "the border's chroma did not flow: median " + med);
        } finally {
            try {
                c.onClient(mc -> {
                    Mod.staticCall(WAVE, "setClockOverride", -1L);
                    Mod.call(cfg, "setChromaBorder", wasChroma);
                    Mod.call(cfg, "setChromaSpeed", wasSpeed);
                    Mod.call(cfg, "setBorderColor", wasColor);
                    McCompat.setScreen(mc, null);
                    return null;
                });
            } catch (Throwable t) {
                c.note("cleanup: " + UiCase.describe(t));
            }
        }
    }

    // ------------------------------------------------------------------ 973

    static void crosshair(UiCase c) throws Exception {
        String chCfg = "crosshair.CustomCrosshairConfig";
        Object cfg = Mod.cfg(chCfg);
        boolean wasEnabled = (Boolean) Mod.call(cfg, "isEnabledRaw");
        String wasCode = (String) Mod.call(cfg, "toShareCode");
        float pitchWas = c.onClient(mc -> mc.player.getXRot());
        try {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                McCompat.clearChatAndToasts(mc);
                mc.player.setXRot(90f);
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setStyle", Mod.enumValue(chCfg + "$Style", "CROSS"));
                Mod.call(cfg, "setSizeMode", Mod.enumValue(chCfg + "$SizeMode", "PIXELS"));
                Mod.call(cfg, "setScale", 1f);
                Mod.call(cfg, "setLength", 10f);
                Mod.call(cfg, "setThickness", 3f);
                Mod.call(cfg, "setGap", 4f);
                Mod.call(cfg, "setOutline", false);
                Mod.call(cfg, "setDot", false);
                Mod.call(cfg, "setColor", 0xFFFFFFFF);
                Mod.call(cfg, "setInvertBlend", false);
                Mod.call(cfg, "setColorOnBlock", false);
                Mod.call(cfg, "setColorOnEntity", false);
                Mod.call(cfg, "setAttackIndicator", false);
                Mod.call(cfg, "setChroma", true);
                Mod.call(cfg, "setChromaSpeed", 1f);
                Mod.staticCall(WAVE, "setClockOverride", T0);
                return null;
            });
            c.ticks(10);
            BufferedImage a = hudShot(c, "t0");
            float want0 = expectedCentreHue(c, T0);
            setClock(c, T0 + 750);
            BufferedImage b = hudShot(c, "t750");
            float want1 = expectedCentreHue(c, T0 + 750);
            float[] got0 = centreHue(a);
            float[] got1 = centreHue(b);
            c.note(String.format("crosshair: %d / %d chroma px at the center; mean hue %.0f then %.0f (ChromaWave at the"
                    + " center says %.0f then %.0f)", (int) got0[1], (int) got1[1], got0[0], got1[0], want0, want1));
            c.check(got0[1] > 20 && got1[1] > 20, "the chroma crosshair drew too few saturated px: " + got0[1] + ", " + got1[1]);
            c.check(Math.abs(circ(got1[0] - got0[0])) > 40, "the crosshair's chroma did not change over 750 ms");
            c.check(Math.abs(circ(got0[0] - want0)) < 25 && Math.abs(circ(got1[0] - want1)) < 25,
                    "the crosshair's color is not ChromaWave's at the screen center");
        } finally {
            try {
                c.onClient(mc -> {
                    Mod.staticCall(WAVE, "setClockOverride", -1L);
                    Mod.call(cfg, "applyShareCode", wasCode);
                    Mod.call(cfg, "setEnabled", wasEnabled);
                    Mod.call(cfg, "save");
                    mc.player.setXRot(pitchWas);
                    return null;
                });
            } catch (Throwable t) {
                c.note("cleanup: " + UiCase.describe(t));
            }
        }
    }

    private static float expectedCentreHue(UiCase c, long t) {
        return c.onClient(mc -> {
            float cx = mc.getWindow().getGuiScaledWidth() / 2f;
            float cy = mc.getWindow().getGuiScaledHeight() / 2f;
            int rgb = (Integer) Mod.staticCall(WAVE, "rainbow", cx, cy, t, 1f, 1f, 1f, 1f);
            return hue(rgb);
        });
    }

    /** Mean hue (circular) and count of saturated bright pixels within 40 px of the frame centre. */
    private static float[] centreHue(BufferedImage img) {
        int cx = img.getWidth() / 2;
        int cy = img.getHeight() / 2;
        double sx = 0;
        double sy = 0;
        int n = 0;
        float[] hsb = new float[3];
        for (int y = cy - 40; y < cy + 40; y++) {
            for (int x = cx - 40; x < cx + 40; x++) {
                int rgb = img.getRGB(x, y);
                Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, hsb);
                if (hsb[1] > 0.7f && hsb[2] > 0.7f) {
                    sx += Math.cos(hsb[0] * 2 * Math.PI);
                    sy += Math.sin(hsb[0] * 2 * Math.PI);
                    n++;
                }
            }
        }
        double h = Math.toDegrees(Math.atan2(sy, sx));
        return new float[]{(float) (h < 0 ? h + 360 : h), n};
    }

    // ------------------------------------------------------------------ helpers

    /** A screen that paints black and then whatever the case draws, every frame. */
    static final class Canvas extends Screen {
        private final BiConsumer<GuiGraphicsExtractor, Canvas> body;

        Canvas(BiConsumer<GuiGraphicsExtractor, Canvas> body) {
            super(Component.literal("chroma"));
            this.body = body;
        }

        net.minecraft.client.gui.Font font() {
            return this.font;
        }

        @Override
        public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            g.fill(0, 0, this.width, this.height, 0xFF000000);
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            super.extractRenderState(g, mouseX, mouseY, delta);
            body.accept(g, this);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    private record Frame(BufferedImage img, double scale) {
    }

    private static void setClock(UiCase c, long t) {
        c.onClient(mc -> Mod.staticCall(WAVE, "setClockOverride", t));
        c.ticks(3);
    }

    private static Frame shot(UiCase c, Canvas canvas, String label) throws Exception {
        c.ticks(3);
        BufferedImage img = save(c, label);
        int guiW = c.onClient(mc -> canvas.width);
        return new Frame(img, img.getWidth() / (double) guiW);
    }

    private static BufferedImage hudShot(UiCase c, String label) throws Exception {
        c.ticks(3);
        return save(c, label);
    }

    private static BufferedImage save(UiCase c, String label) throws Exception {
        String name = c.name() + "-" + label;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        dev.testkit.harness.Report.screenshot(name, taken);
        try {
            Files.createDirectories(SHOTS);
            Files.copy(taken, SHOTS.resolve(name + ".png"), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            c.note("could not copy " + name + " to " + SHOTS + ": " + e);
        }
        return javax.imageio.ImageIO.read(taken.toFile());
    }

    /** Saturated, bright pixels {x, y}: chroma glyph/border pixels (shadows are 25% brightness; grey text unsaturated). */
    private static List<int[]> chromaPixels(BufferedImage img, boolean[][] mask) {
        List<int[]> out = new ArrayList<>();
        float[] hsb = new float[3];
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, hsb);
                if (hsb[1] > 0.6f && hsb[2] > 0.6f && (mask == null || !mask[x][y])) {
                    out.add(new int[]{x, y});
                }
            }
        }
        return out;
    }

    private static float[][] hueMap(BufferedImage img, List<int[]> px) {
        float[][] m = new float[img.getWidth()][img.getHeight()];
        for (float[] col : m) {
            Arrays.fill(col, -1f);
        }
        for (int[] p : px) {
            m[p[0]][p[1]] = hue(img.getRGB(p[0], p[1]));
        }
        return m;
    }

    private static float hue(int rgb) {
        float[] hsb = Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
        return hsb[0] * 360f;
    }

    private static float at(float[][] m, int x, int y) {
        return x < 0 || y < 0 || x >= m.length || y >= m[0].length ? -1f : m[x][y];
    }

    /** Signed smallest difference, -180..180. */
    private static float circ(float d) {
        float r = ((d % 360f) + 540f) % 360f - 180f;
        return r;
    }

    private static float median(List<Float> v) {
        if (v.isEmpty()) {
            return Float.NaN;
        }
        List<Float> s = new ArrayList<>(v);
        s.sort(Float::compare);
        return s.get(s.size() / 2);
    }

    /** Rows holding chroma pixels, grouped into bands {top, bottom} split at empty rows. */
    private static List<int[]> bands(List<int[]> px) {
        java.util.TreeSet<Integer> rows = new java.util.TreeSet<>();
        for (int[] p : px) {
            rows.add(p[1]);
        }
        List<int[]> out = new ArrayList<>();
        int top = -1;
        int last = -10;
        for (int r : rows) {
            if (r != last + 1) {
                if (top >= 0) {
                    out.add(new int[]{top, last});
                }
                top = r;
            }
            last = r;
        }
        if (top >= 0) {
            out.add(new int[]{top, last});
        }
        return out;
    }

    /** The pixel with the smallest (or largest) x + y. */
    private static int[] extreme(List<int[]> px, boolean min) {
        int[] best = px.get(0);
        for (int[] p : px) {
            int s = p[0] + p[1];
            if (min ? s < best[0] + best[1] : s > best[0] + best[1]) {
                best = p;
            }
        }
        return best;
    }
}

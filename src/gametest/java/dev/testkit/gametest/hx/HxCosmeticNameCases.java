package dev.testkit.gametest.hx;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.TestEnemy;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Player;

import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Player Cosmetics names (mod branch cosmetic-names, killer560 2026-10-11), 811-818:
 * <ul>
 *   <li>811-814: "fade color is broken and just shows a bunch of random colors in between". The fade was written as
 *       Bungee's {@code §x§R§R§G§G§B§B} escape, which vanilla's decoder misreads as six legacy color codes. Each case
 *       paints the own name faded green (00FF00) to blue (0000FF) and reads the SCREENSHOT: every name pixel lies on the
 *       green-blue line (red ~0, green + blue ~255), the blue share rises left to right, and it spans the two ends -
 *       in chat (811), the tab list (812), the own F5 nametag (813) and the Name Changer tab's preview (814).</li>
 *   <li>815: another player's supporter name in the tab list - in a Hypixel-style text row (a fake profile whose text
 *       carries the name, which the UUID lookup never matched) as well as the real row; and the Nickhider's fake name in
 *       the same text row.</li>
 *   <li>816: the nametag follows the player's size: a bot at Others' Size 0.5, 1 and 2, the tag box and head found in
 *       the screenshot against a no-bot frame; the sky gap between them must exist and stay about the same.</li>
 *   <li>817: My Name in F5 - the own tag in third person back and front only with the setting on, never in first
 *       person (screenshot diff on vs off, and the render state's nameTag).</li>
 *   <li>818: My Name in F5 is saved, loaded, restored by a profile reload, tooltipped and in the tab.</li>
 *   <li>819: a supporter's shared fade - the relay's {@code GET /supporters} body with a per-letter {@code colors}
 *       list, read by the mod's own parser - paints green to blue on that player's nametag and in the tab list
 *       (screenshots); an old entry with no {@code colors} and one with a wrong-length list keep their single color;
 *       the cache keeps the list; and your own fade is what Share if Supporter would send.</li>
 * </ul>
 */
final class HxCosmeticNameCases {

    private static final String NC = "namechanger.NameChangerConfig";
    private static final String NF = "namechanger.NameChangerFeature";
    private static final String SC = "supporters.SupportersConfig";
    private static final String SF = "supporters.SupportersFeature";
    private static final int FROM = 0xFF00FF00;
    private static final int TO = 0xFF0000FF;
    private static final String FADE_NAME = "Gradientname";

    private HxCosmeticNameCases() {
    }

    static void register(Session s) {
        s.test("811-hx-cosmetic-fade-chat", HxCosmeticNameCases::fadeChat);
        s.test("812-hx-cosmetic-fade-tab", HxCosmeticNameCases::fadeTab);
        s.test("813-hx-cosmetic-fade-nametag", HxCosmeticNameCases::fadeNametag);
        s.test("814-hx-cosmetic-fade-preview", HxCosmeticNameCases::fadePreview);
        s.test("815-hx-cosmetic-supporter-tab", HxCosmeticNameCases::supporterTab);
        s.test("816-hx-cosmetic-nametag-scale", HxCosmeticNameCases::nametagScale);
        s.test("817-hx-cosmetic-f5-own-name", HxCosmeticNameCases::f5OwnName);
        s.test("818-hx-cosmetic-f5-setting-saved", HxCosmeticNameCases::f5Saved);
        // Fade share (mod branch fade-share, relay branch fade-share), own block.
        s.test("819-hx-cosmetic-fade-share", HxCosmeticNameCases::fadeShare);
    }

    // ---- shared -----------------------------------------------------------------------------------------------------

    /** Own name changed to {@link #FADE_NAME}, faded {@link #FROM} -> {@link #TO}, no letter colors. */
    static Settings fade(Session c) {
        c.onClient(mc -> {
            Mod.call(Mod.cfg(NC), "clearOwnCharColors");
            return null;
        });
        return new Settings(c).with(NC, "Enabled", true).with(NC, "OwnNameEnabled", true)
                .with(NC, "OwnDisplayName", FADE_NAME).with(NC, "OwnColor", FROM)
                .with(NC, "OwnColorFadeEnabled", true).with(NC, "OwnColorFadeTo", TO)
                .with(NC, "RandomizeOthers", false);
    }

    static void look(Session c, float yaw, float pitch) {
        c.onClient(mc -> {
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
            mc.player.yRotO = yaw;
            mc.player.xRotO = pitch;
            return null;
        });
    }

    static void camera(Session c, CameraType type) {
        c.onClient(mc -> {
            mc.options.setCameraType(type);
            return null;
        });
    }

    static void clearChat(Session c) {
        c.onClient(mc -> {
            McCompat.clearChatAndToasts(mc);
            return null;
        });
    }

    static BufferedImage shot(Session c, String tag) throws Exception {
        c.ctx().waitTicks(4);
        Path p = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-" + tag));
        System.out.println("[" + c.name() + "] screenshot " + tag + ": " + p);
        return javax.imageio.ImageIO.read(p.toFile());
    }

    static boolean onLine(int rgb) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        int sum = g + b;
        return r <= 70 && Math.max(g, b) >= 110 && sum >= 170 && sum <= 330;
    }

    private static boolean bright(int rgb) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        return Math.max(r, Math.max(g, b)) >= 110;
    }

    private static boolean pink(int rgb) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        return r >= 215 && b >= 215 && g >= 55 && g <= 115;
    }

    private static boolean neutral(int rgb) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) <= 25;
    }

    private static boolean changed(BufferedImage a, BufferedImage b, int x, int y) {
        if (b == null) {
            return true;
        }
        int p = a.getRGB(x, y), q = b.getRGB(x, y);
        int d = Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)) + Math.abs(((p >> 8) & 255) - ((q >> 8) & 255))
                + Math.abs((p & 255) - (q & 255));
        return d > 30;
    }

    /** A translucent overlay over the base (chat/tab/nametag background): every channel scaled by about the same factor. */
    private static boolean overlay(int p, int q) {
        double[] k = new double[3];
        for (int c = 0; c < 3; c++) {
            int a = (p >> (16 - 8 * c)) & 255, b = (q >> (16 - 8 * c)) & 255;
            k[c] = (a + 4) / (double) (b + 4);
        }
        double mean = (k[0] + k[1] + k[2]) / 3;
        return Math.abs(k[0] - mean) < 0.12 && Math.abs(k[1] - mean) < 0.12 && Math.abs(k[2] - mean) < 0.12;
    }

    /** One run of green-blue glyph pixels on one text line. */
    record Run(int x0, int x1, int y0, int y1, int onLine, int foreign, int columns, int drops, double tMin, double tMax) {
        boolean isName() {
            return onLine >= 30 && tMin <= 0.25 && tMax >= 0.75;
        }

        boolean clean() {
            return foreign * 10 <= onLine && drops <= Math.max(1, columns / 40);
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "[x %d-%d y %d-%d: on-line %d, foreign %d, %d columns, %d drops, blue %.2f..%.2f]",
                    x0, x1, y0, y1, onLine, foreign, columns, drops, tMin, tMax);
        }
    }

    /** Every faded-name run in the rectangle (pixels that changed vs {@code base}, or all when base is null). */
    record Fade(List<Run> names, int otherRuns) {
        boolean ok() {
            return !names.isEmpty() && names.stream().allMatch(Run::clean);
        }

        @Override
        public String toString() {
            return names.size() + " name run(s) " + names + ", " + otherRuns + " other green/blue run(s)";
        }
    }

    static Fade fadeOf(BufferedImage img, BufferedImage base, int x0, int y0, int x1, int y1) {
        y0 = Math.max(0, y0);
        x0 = Math.max(0, x0);
        y1 = Math.min(img.getHeight(), y1);
        x1 = Math.min(img.getWidth(), x1);
        boolean[][] on = new boolean[y1 - y0][x1 - x0];
        boolean[] rowHas = new boolean[y1 - y0];
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if (changed(img, base, x, y) && onLine(img.getRGB(x, y))
                        && (base == null || !overlay(img.getRGB(x, y), base.getRGB(x, y)))) {
                    on[y - y0][x - x0] = true;
                    rowHas[y - y0] = true;
                }
            }
        }
        List<Run> names = new ArrayList<>();
        int others = 0;
        int y = 0;
        while (y < rowHas.length) {
            if (!rowHas[y]) {
                y++;
                continue;
            }
            int top = y;
            while (y < rowHas.length && (rowHas[y] || (y + 1 < rowHas.length && rowHas[y + 1]))) {
                y++;
            }
            int bottom = y - 1;
            // Split the line into runs at horizontal gaps wider than 12 px.
            int x = 0;
            while (x < x1 - x0) {
                boolean col = false;
                for (int yy = top; yy <= bottom && !col; yy++) {
                    col = on[yy][x];
                }
                if (!col) {
                    x++;
                    continue;
                }
                int left = x, last = x;
                while (x < x1 - x0 && x - last <= 12) {
                    for (int yy = top; yy <= bottom; yy++) {
                        if (on[yy][x]) {
                            last = x;
                            break;
                        }
                    }
                    x++;
                }
                Run r = run(img, base, x0, y0, on, left, last, top, bottom);
                if (r.isName()) {
                    names.add(r);
                } else {
                    others++;
                }
            }
        }
        return new Fade(names, others);
    }

    private static Run run(BufferedImage img, BufferedImage base, int ox, int oy, boolean[][] on, int left, int right,
                           int top, int bottom) {
        TreeMap<Integer, double[]> cols = new TreeMap<>();
        int n = 0, foreign = 0;
        for (int yy = top; yy <= bottom; yy++) {
            for (int xx = left; xx <= right; xx++) {
                int p = img.getRGB(ox + xx, oy + yy);
                if (on[yy][xx]) {
                    n++;
                    int g = (p >> 8) & 255, b = p & 255;
                    double[] acc = cols.computeIfAbsent(xx, k -> new double[2]);
                    acc[0] += b / (double) (g + b);
                    acc[1]++;
                } else if (changed(img, base, ox + xx, oy + yy) && bright(p) && !neutral(p)
                        && (base == null || !overlay(p, base.getRGB(ox + xx, oy + yy)))
                        // where the base frame had white text (the real name the styled one replaced), what shows
                        // through now is background, not a foreign color of the name
                        && (base == null || !(neutral(base.getRGB(ox + xx, oy + yy)) && bright(base.getRGB(ox + xx, oy + yy))))) {
                    foreign++;
                }
            }
        }
        double max = -1, tMin = 2, tMax = -1;
        int drops = 0;
        for (double[] acc : cols.values()) {
            double t = acc[0] / acc[1];
            tMin = Math.min(tMin, t);
            tMax = Math.max(tMax, t);
            if (t < max - 0.12) {
                drops++;
            }
            max = Math.max(max, t);
        }
        return new Run(ox + left, ox + right, oy + top, oy + bottom, n, foreign, cols.size(), drops, tMin, tMax);
    }

    /** The per-glyph colors a sequence paints, in order. */
    static List<Integer> colours(FormattedCharSequence seq) {
        List<Integer> out = new ArrayList<>();
        seq.accept((pos, style, cp) -> {
            TextColor tc = style.getColor();
            out.add(tc == null ? -1 : tc.getValue());
            return true;
        });
        return out;
    }

    // ---- 811 chat ---------------------------------------------------------------------------------------------------

    private static void fadeChat(Session c) throws Exception {
        String self = self(c);
        try (Settings s = fade(c)) {
            // The real Font path: the replacer's output for a sequence holding the own IGN.
            List<Integer> painted = c.onClient(mc -> colours((FormattedCharSequence) Mod.staticCall(
                    "namechanger.NameReplacer", "replace", Component.literal(self).getVisualOrderText())));
            c.note("replacer colors: " + hexList(painted));
            c.check(painted.size() == FADE_NAME.length(), "replacer painted " + painted.size() + " glyphs for "
                    + FADE_NAME.length() + " letters");
            int prevB = -1;
            for (int rgb : painted) {
                int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
                c.check(rgb >= 0 && r == 0 && Math.abs(g + b - 255) <= 1, "a letter is off the green-blue line: "
                        + Integer.toHexString(rgb));
                c.check(b >= prevB, "blue went down along the name: " + hexList(painted));
                prevB = b;
            }
            c.check(painted.get(0) == (FROM & 0xFFFFFF) && painted.get(painted.size() - 1) == (TO & 0xFFFFFF),
                    "the ends are not the two picked colors: " + hexList(painted));

            look(c, 0f, -30f);
            clearChat(c);
            BufferedImage base = shot(c, "base");
            c.hx().call("chat", "text", self, "overlay", false);
            c.ctx().waitTicks(5);
            BufferedImage img = shot(c, "chat");
            Fade f = fadeOf(img, base, 0, img.getHeight() / 2, img.getWidth() / 2, img.getHeight());
            c.note("chat: " + f);
            c.check(f.ok(), "chat name is not a clean green->blue fade: " + f);
        } finally {
            clearChat(c);
        }
    }

    static String hexList(List<Integer> l) {
        StringBuilder sb = new StringBuilder();
        for (int v : l) {
            sb.append(sb.isEmpty() ? "" : " ").append(String.format("%06x", v & 0xFFFFFF));
        }
        return sb.toString();
    }

    // ---- 812 tab ----------------------------------------------------------------------------------------------------

    static void tabDown(Session c, boolean down) {
        c.onClient(mc -> {
            mc.options.keyPlayerList.setDown(down);
            return null;
        });
    }

    private static void fadeTab(Session c) throws Exception {
        String self = self(c);
        try (Settings s = fade(c)) {
            tab(c, "template", "dungeon", "players", List.of(player("HxMateA", "Mage", null), player(self, "Archer", null)));
            c.ctx().waitTicks(10);
            look(c, 0f, -30f);
            clearChat(c);
            BufferedImage base = shot(c, "closed");
            tabDown(c, true);
            BufferedImage img = shot(c, "open");
            Fade f = fadeOf(img, base, 0, 0, img.getWidth(), img.getHeight() / 2);
            c.note("tab: " + f);
            // Other rows are in the shot too, so foreign color is not judged here; the name must still be a clean fade.
            c.check(f.ok(), "own name in the tab list is not a green->blue fade: " + f);
        } finally {
            tabDown(c, false);
            hub(c);
        }
    }

    // ---- 813 nametag ------------------------------------------------------------------------------------------------

    private static void fadeNametag(Session c) throws Exception {
        try (Settings s = fade(c)) {
            look(c, 0f, 0f);
            camera(c, CameraType.THIRD_PERSON_BACK);
            clearChat(c);
            BufferedImage off;
            try (Settings f5off = new Settings(c).with(SC, "OwnNameInF5", false)) {
                off = shot(c, "f5-off");
            }
            try (Settings f5on = new Settings(c).with(SC, "OwnNameInF5", true)) {
                BufferedImage on = shot(c, "f5-on");
                Fade f = fadeOf(on, off, 0, 0, on.getWidth(), on.getHeight());
                c.note("nametag: " + f);
                c.check(f.ok(), "own nametag is not a clean green->blue fade: " + f);
            }
        } finally {
            camera(c, CameraType.FIRST_PERSON);
        }
    }

    // ---- 814 preview ------------------------------------------------------------------------------------------------

    /** The real ModScreen with Player Cosmetics' drawer open and Custom Names open in it. */
    @SuppressWarnings("unchecked")
    static Screen openCosmetics(Session c) {
        Screen s = c.onClient(mc -> {
            try {
                Class<?> cls = Mod.cls("gui.ModScreen");
                Mod.setField("gui.ModScreen", "searchQuery", "");
                Mod.setField("gui.ModScreen", "scrollOffset", 0);
                Mod.setField("gui.ModScreen", "selectedTab", 0);
                Screen screen = (Screen) cls.getConstructor(Screen.class).newInstance((Object) null);
                screen.init(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
                List<Object> tops = (List<Object>) Mod.field("gui.ModScreen", "tabs");
                Class<?> folder = Mod.cls("gui.tab.FolderTab");
                for (int i = 0; i < tops.size(); i++) {
                    Object t = tops.get(i);
                    if (!folder.isInstance(t)) {
                        continue;
                    }
                    List<Object> subs = (List<Object>) Mod.field(t, "subTabs");
                    for (int j = 0; j < subs.size(); j++) {
                        if ("Player Cosmetics".equals(Mod.field(subs.get(j), "name"))) {
                            Mod.setField("gui.ModScreen", "selectedTab", i);
                            // Option A menu (mod menu-a): the feature's drawer, not an accordion section.
                            Mod.setField("gui.ModScreen", "openFeature", subs.get(j));
                            setInstanceField(subs.get(j), "customNamesOpen", true);
                            McCompat.setScreen(mc, screen);
                            return screen;
                        }
                    }
                }
                return null;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        if (s == null) {
            throw new AssertionError("no Player Cosmetics tab in the mod menu");
        }
        c.ctx().waitTicks(5);
        return s;
    }

    /** Every widget on the screen, the scrolling content pane's children included. */
    static List<AbstractWidget> widgets(Object parent) {
        List<AbstractWidget> out = new ArrayList<>();
        if (!(parent instanceof net.minecraft.client.gui.components.events.ContainerEventHandler h)) {
            return out;
        }
        for (Object k : h.children()) {
            if (k instanceof AbstractWidget w) {
                out.add(w);
            }
            if (k != parent) {
                out.addAll(widgets(k));
            }
        }
        return out;
    }

    private static void fadePreview(Session c) throws Exception {
        try (Settings s = fade(c)) {
            Screen screen = openCosmetics(c);
            int[] rect = c.onClient(mc -> {
                for (var child : widgets(screen)) {
                    if (child instanceof StringWidget w && w.getMessage().getString().contains("Preview: ")) {
                        return new int[]{w.getX(), w.getY(), w.getWidth(), w.getHeight()};
                    }
                }
                return null;
            });
            c.check(rect != null, "no Preview line in Player Cosmetics > Custom Names");
            List<Integer> painted = c.onClient(mc -> {
                for (var child : widgets(screen)) {
                    if (child instanceof StringWidget w && w.getMessage().getString().contains("Preview: ")) {
                        return colours(w.getMessage().getVisualOrderText());
                    }
                }
                return List.of();
            });
            List<Integer> name = painted.subList(Math.max(0, painted.size() - FADE_NAME.length()), painted.size());
            c.note("preview widget " + java.util.Arrays.toString(rect) + ", name colors " + hexList(name));
            c.check(name.get(0) == (FROM & 0xFFFFFF) && name.get(name.size() - 1) == (TO & 0xFFFFFF),
                    "preview ends are not the picked colors: " + hexList(name));
            BufferedImage img = shot(c, "preview");
            int width = c.onClient(mc -> McCompat.screen(mc).width);
            double k = img.getWidth() / (double) width;
            Fade f = fadeOf(img, null, (int) (rect[0] * k), (int) (rect[1] * k) - 2, (int) ((rect[0] + rect[2]) * k),
                    (int) ((rect[1] + rect[3]) * k) + 2);
            c.note("preview (" + String.format("%.2f", k) + " px/unit): " + f);
            c.check(f.ok(), "the preview is not a clean green->blue fade: " + f);
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
        }
    }

    // ---- 815 supporter name in tab ----------------------------------------------------------------------------------

    private static Object supporterEntry(UUID id, String raw) throws Exception {
        Constructor<?> k = Mod.cls("supporters.SupporterEntry").getDeclaredConstructor(UUID.class, String.class, float.class);
        k.setAccessible(true);
        return k.newInstance(id, raw, 1.0f);
    }

    static Component nameForDisplay(Minecraft mc, PlayerInfo info) {
        try {
            Object hud = McCompat.hud(mc);
            Object tabList = hud.getClass().getMethod("getTabList").invoke(hud);
            return (Component) tabList.getClass().getMethod("getNameForDisplay", PlayerInfo.class).invoke(tabList, info);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no getTabList/getNameForDisplay: " + e, e);
        }
    }

    static PlayerInfo textRowFor(Minecraft mc, String name) {
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            Component d = info.getTabListDisplayName();
            if (d != null && d.getString().contains(name) && !name.equals(info.getProfile().name())) {
                return info;
            }
        }
        return null;
    }

    private static void supporterTab(Session c) throws Exception {
        String self = self(c);
        double[] at = c.scenario().playerPosition();
        TestEnemy bot = TestEnemy.named("Ct", "T").at(at[0] + 3, at[1], at[2] + 3).frozen(true).spawn(c.ctx(), c.server());
        Object savedMap = c.onClient(mc -> Mod.field(SF, "byUuid"));
        try {
            String botName = bot.name();
            UUID botId = c.onClient(mc -> {
                for (Player p : mc.level.players()) {
                    if (p.getGameProfile().name().equals(botName)) {
                        return p.getUUID();
                    }
                }
                return null;
            });
            c.check(botId != null, "the bot " + botName + " is not in the client's world (premise)");
            tab(c, "template", "dungeon", "players", List.of(player(botName, "Mage", null), player(self, "Archer", null)));
            c.waitUntil("a Hypixel-style text row carries " + botName, mc -> textRowFor(mc, botName) != null, 60);

            // Nickhider first, supporter names off: the text row is painted with the fake name.
            try (Settings nick = new Settings(c).with(NC, "RandomizeOthers", true).with(SC, "ShowSupporterNames", false)) {
                c.waitUntil("the Nickhider collected " + botName, mc -> Mod.staticCall(NF, "fakeNameFor", botName) != null
                        && ((Set<?>) Mod.field(NF, "SEEN_PLAYERS")).contains(botName), 60);
                String fake = c.onClient(mc -> (String) Mod.staticCall(NF, "fakeNameFor", botName));
                c.waitUntil("the text row is painted as " + fake, mc -> {
                    PlayerInfo row = textRowFor(mc, botName);
                    if (row == null) {
                        return false;
                    }
                    Component shown = nameForDisplay(mc, row);
                    FormattedCharSequence painted = (FormattedCharSequence) Mod.staticCall("namechanger.NameReplacer",
                            "replace", shown.getVisualOrderText());
                    StringBuilder sb = new StringBuilder();
                    painted.accept((p, st, cp) -> {
                        sb.appendCodePoint(cp);
                        return true;
                    });
                    return sb.toString().contains(fake) && !sb.toString().contains(botName);
                }, 60);
                c.note("Nickhider: text row shows " + fake + " for " + botName);
            }

            // Supporter: a pink name shared over the relay for the bot's UUID.
            c.onClient(mc -> {
                try {
                    Mod.staticCall("players.PlayerNames", "noteKnown", botId, botName);
                    Mod.staticCall(SF, "apply", 1, List.of(supporterEntry(botId, "&dHxSupp")), false);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                return null;
            });
            try (Settings sup = new Settings(c).with(SC, "ShowSupporterNames", true)) {
                c.ctx().waitTicks(2);
                String dbg = c.onClient(mc -> "supporters " + Mod.staticCall(SF, "supporterCount") + ", by UUID "
                        + Mod.staticCall("supporters.PlayerNameDisplay", "displayNameFor", botId) + ", name for UUID "
                        + Mod.staticCall("players.PlayerNames", "nameFor", botId) + ", by IGN " + Mod.staticCall(SF, "shownByIgn")
                        + ", bot UUID version " + botId.version());
                c.note(dbg);
                c.ctx().waitTicks(25);
                String dbg2 = c.onClient(mc -> {
                    PlayerInfo info = textRowFor(mc, botName);
                    if (info == null) {
                        return "no text row";
                    }
                    Component shown = nameForDisplay(mc, info);
                    Object direct = Mod.staticCall("supporters.SupportersTabText", "compute", shown,
                            Mod.staticCall(SF, "shownByIgn"));
                    return "row shows '" + shown.getString() + "', display name '" + info.getTabListDisplayName().getString()
                            + "', compute -> " + (direct == null ? "null" : "'" + ((Component) direct).getString() + "'");
                });
                c.note(dbg2);
                c.waitUntil("the text row shows HxSupp instead of " + botName, mc -> {
                    PlayerInfo info = textRowFor(mc, botName);
                    if (info == null) {
                        return false;
                    }
                    String s = nameForDisplay(mc, info).getString();
                    return s.contains("HxSupp") && !s.contains(botName);
                }, 60);
                String row = c.onClient(mc -> nameForDisplay(mc, textRowFor(mc, botName)).getString());
                String real = c.onClient(mc -> {
                    PlayerInfo info = mc.getConnection().getPlayerInfo(botId);
                    return info == null ? null : nameForDisplay(mc, info).getString();
                });
                c.note("text row: '" + row + "'; real row: '" + real + "'");
                c.check(real == null || real.contains("HxSupp"), "the bot's own tab row shows '" + real + "'");
                look(c, 0f, -30f);
                clearChat(c);
                tabDown(c, true);
                BufferedImage img = shot(c, "tab-supporter");
                int pink = 0;
                for (int y = 0; y < img.getHeight() / 2; y++) {
                    for (int x = 0; x < img.getWidth(); x++) {
                        if (pink(img.getRGB(x, y))) {
                            pink++;
                        }
                    }
                }
                c.note("pink (§d) pixels in the tab list: " + pink);
                c.check(pink >= 20, "no supporter-pink name drawn in the tab list (" + pink + " px)");
            }
        } finally {
            tabDown(c, false);
            c.onClient(mc -> {
                Mod.setField(SF, "byUuid", savedMap);
                return null;
            });
            bot.remove();
            hub(c);
        }
    }

    // ---- 816 nametag height -----------------------------------------------------------------------------------------

    /** Rows {top, bottom} of the first changed run in the center band, then the top of the next run (-1 if none). */
    static int[] tagAndHead(BufferedImage img, BufferedImage base) {
        int w = img.getWidth();
        int x0 = w / 2 - w / 10, x1 = w / 2 + w / 10;
        boolean[] rows = new boolean[img.getHeight()];
        for (int y = 0; y < img.getHeight(); y++) {
            int n = 0;
            for (int x = x0; x < x1; x++) {
                if (!changed(img, base, x, y)) {
                    continue;
                }
                int p = img.getRGB(x, y), q = base.getRGB(x, y);
                boolean cloud = (neutral(p) && ((p >> 16) & 255) >= 200) || (neutral(q) && ((q >> 16) & 255) >= 200);
                if (!cloud) {
                    n++;
                }
            }
            rows[y] = n >= 3;
        }
        int y = 0;
        while (y < rows.length && !rows[y]) {
            y++;
        }
        int top = y;
        while (y < rows.length && rows[y]) {
            y++;
        }
        int bottom = y - 1;
        while (y < rows.length && !rows[y]) {
            y++;
        }
        return new int[]{top, bottom, y < rows.length ? y : -1};
    }

    private static void nametagScale(Session c) throws Exception {
        double[] at = c.scenario().playerPosition();
        camera(c, CameraType.FIRST_PERSON);
        look(c, 0f, -12f);
        clearChat(c);
        BufferedImage base = shot(c, "no-bot");
        TestEnemy bot = TestEnemy.named("Ct", "S").at(at[0], at[1], at[2] + 5).frozen(true).spawn(c.ctx(), c.server());
        try (Settings s = new Settings(c).with(SC, "ShowSupporterSizes", true)) {
            String botName = bot.name();
            double[] scales = {0.5, 1.0, 2.0};
            double[] gaps = new double[3];
            double[] boxes = new double[3];
            for (int i = 0; i < 3; i++) {
                float sc = (float) scales[i];
                c.onClient(mc -> {
                    Mod.set(SC, "setOthersScale", sc);
                    return null;
                });
                look(c, 0f, -12f);
                clearChat(c);
                double[] st = c.onClient(mc -> {
                    for (Player p : mc.level.players()) {
                        if (p.getGameProfile().name().equals(botName)) {
                            EntityRenderState state = mc.getEntityRenderDispatcher().extractEntity(p, 1f);
                            double y = state.nameTagAttachment == null ? Double.NaN : state.nameTagAttachment.y;
                            float scale = state instanceof LivingEntityRenderState l ? l.scale : Float.NaN;
                            return new double[]{y, scale, state.nameTag == null ? 0 : 1};
                        }
                    }
                    return null;
                });
                c.check(st != null && st[2] == 1, "the bot has no nametag in its render state at " + sc + "x");
                BufferedImage img = shot(c, "scale-" + sc);
                int[] r = tagAndHead(img, base);
                int boxRows = r[1] - r[0] + 1;
                boxes[i] = boxRows;
                gaps[i] = r[2] < 0 ? Double.NaN : (r[2] - r[1] - 1) * 0.25 / boxRows;
                c.note(String.format(java.util.Locale.ROOT,
                        "%.1fx: state tag y %.3f, model scale %.2f, y/scale %.3f; shot: tag rows %d-%d (%d), head top row %d, gap %.3f blocks",
                        sc, st[0], st[1], st[0] / st[1], r[0], r[1], boxRows, r[2], gaps[i]));
            }
            for (int i = 0; i < 3; i++) {
                c.check(!Double.isNaN(gaps[i]) && gaps[i] >= 0 && gaps[i] <= 0.6,
                        "at " + scales[i] + "x the tag is not just above the head (gap " + gaps[i] + " blocks)");
                c.check(boxes[i] <= boxes[1] * 1.5 + 2, "at " + scales[i] + "x the tag run is " + boxes[i]
                        + " rows against " + boxes[1] + " at 1x - the tag runs into the body");
                c.check(Math.abs(gaps[i] - gaps[1]) <= 0.25, "the gap at " + scales[i] + "x (" + gaps[i]
                        + ") is not the gap at 1x (" + gaps[1] + ")");
            }
        } finally {
            c.onClient(mc -> {
                Mod.set(SC, "setOthersScale", 1.0f);
                return null;
            });
            bot.remove();
        }
    }

    // ---- 817 own name in F5 -----------------------------------------------------------------------------------------

    private static int diffCount(BufferedImage a, BufferedImage b) {
        int n = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = a.getWidth() / 4; x < a.getWidth() * 3 / 4; x++) {
                if (changed(a, b, x, y)) {
                    int p = a.getRGB(x, y), q = b.getRGB(x, y);
                    boolean cloud = (neutral(p) && ((p >> 16) & 255) >= 200) || (neutral(q) && ((q >> 16) & 255) >= 200);
                    if (!cloud) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    private static boolean ownTagInState(Session c) {
        return c.onClient(mc -> mc.getEntityRenderDispatcher().extractEntity(mc.player, 1f).nameTag != null);
    }

    private static void f5OwnName(Session c) throws Exception {
        try (Settings s = new Settings(c).with(NC, "Enabled", true).with(NC, "OwnNameEnabled", true)
                .with(NC, "OwnDisplayName", "Fivename").with(NC, "OwnColor", 0xFFFF55FF)
                .with(NC, "OwnColorFadeEnabled", false)) {
            c.onClient(mc -> {
                Mod.call(Mod.cfg(NC), "clearOwnCharColors");
                return null;
            });
            look(c, 0f, 0f);
            clearChat(c);
            for (CameraType type : new CameraType[]{CameraType.FIRST_PERSON, CameraType.THIRD_PERSON_BACK,
                    CameraType.THIRD_PERSON_FRONT}) {
                camera(c, type);
                BufferedImage off, on;
                boolean stateOff, stateOn;
                try (Settings o = new Settings(c).with(SC, "OwnNameInF5", false)) {
                    off = shot(c, type.name().toLowerCase() + "-off");
                    stateOff = ownTagInState(c);
                }
                try (Settings o = new Settings(c).with(SC, "OwnNameInF5", true)) {
                    on = shot(c, type.name().toLowerCase() + "-on");
                    stateOn = ownTagInState(c);
                }
                int pink = 0;
                for (int y = 0; y < on.getHeight(); y++) {
                    for (int x = 0; x < on.getWidth(); x++) {
                        if (pink(on.getRGB(x, y)) && !pink(off.getRGB(x, y))) {
                            pink++;
                        }
                    }
                }
                int diff = diffCount(on, off);
                c.note(type + ": changed px on vs off " + diff + ", new pink px " + pink + ", render-state tag off "
                        + stateOff + " / on " + stateOn);
                c.check(!stateOff, type + ": own tag in the render state with the setting off");
                if (type == CameraType.FIRST_PERSON) {
                    c.check(!stateOn && pink == 0, type + ": own tag shown in first person (state " + stateOn + ", " + pink + " px)");
                } else {
                    c.check(stateOn && pink >= 15, type + ": own tag not shown with the setting on (state " + stateOn
                            + ", " + pink + " pink px)");
                }
            }
        } finally {
            camera(c, CameraType.FIRST_PERSON);
        }
    }

    // ---- 818 setting saved ------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void f5Saved(Session c) throws Exception {
        Path file = c.onClient(mc -> (Path) Mod.field(SC, "CONFIG_PATH"));
        byte[] before = Files.exists(file) ? Files.readAllBytes(file) : null;
        try {
            c.onClient(mc -> {
                Mod.set(SC, "setOwnNameInF5", true);
                Mod.call(Mod.cfg(SC), "save");
                return null;
            });
            String json = Files.readString(file);
            c.check(json.contains("\"ownNameInF5\": true"), "saved file lacks ownNameInF5 true: " + json);
            boolean loaded = c.onClient(mc -> {
                Mod.staticCall(SC, "load");
                return (Boolean) Mod.get(SC, "isOwnNameInF5");
            });
            c.check(loaded, "load() did not read ownNameInF5 back");
            boolean profile = c.onClient(mc -> {
                Mod.set(SC, "setOwnNameInF5", false); // in memory only
                Mod.staticCall("profiles.ProfileManager", "reloadAllConfigs");
                return (Boolean) Mod.get(SC, "isOwnNameInF5");
            });
            c.check(profile, "ProfileManager.reloadAllConfigs did not restore ownNameInF5");
            String tip = c.onClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "describe", "Player Cosmetics",
                    "My Name in F5: §cOFF"));
            c.note("tooltip: " + tip);
            c.check(tip != null && tip.contains("F5"), "no tooltip for My Name in F5: " + tip);
            Screen screen = openCosmetics(c);
            List<String> labels = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                for (var child : widgets(screen)) {
                    if (child instanceof AbstractWidget w) {
                        out.add(w.getMessage().getString());
                    }
                }
                return out;
            });
            c.check(labels.stream().anyMatch(l -> l.startsWith("My Name in F5: ")), "no My Name in F5 button: " + labels);
            boolean defaultOff = c.onClient(mc -> {
                try {
                    Constructor<?> k = Mod.cls(SC).getDeclaredConstructor();
                    k.setAccessible(true);
                    return !(Boolean) Mod.call(k.newInstance(), "isOwnNameInF5");
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            c.check(defaultOff, "My Name in F5 is not off by default");
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            if (before != null) {
                Files.write(file, before);
            } else {
                Files.deleteIfExists(file);
            }
            c.onClient(mc -> {
                Mod.staticCall(SC, "load");
                return null;
            });
        }
    }

    // ---- 819 fade share ---------------------------------------------------------------------------------------------

    /** The relay's GET /supporters body for three entries (shape from the relay's own discord-smoke run). */
    static String relayBody(UUID styled, UUID old, UUID bad) {
        StringBuilder colors = new StringBuilder();
        for (int i = 0; i < FADE_NAME.length(); i++) {
            int g = Math.round(255 - 255f * i / (FADE_NAME.length() - 1));
            colors.append(i == 0 ? "" : ",").append((g << 8) | (255 - g));
        }
        return "{\"version\":7,\"supporters\":["
                + "{\"uuid\":\"" + styled + "\",\"name\":\"&a" + FADE_NAME + "\",\"scale\":1,\"colors\":[" + colors + "]},"
                + "{\"uuid\":\"" + old + "\",\"name\":\"&dOldEntry\",\"scale\":1},"
                + "{\"uuid\":\"" + bad + "\",\"name\":\"&bBadList\",\"scale\":1,\"colors\":[65280,255]}]}";
    }

    private static void fadeShare(Session c) throws Exception {
        String self = self(c);
        double[] at = c.scenario().playerPosition();
        camera(c, CameraType.FIRST_PERSON);
        TestEnemy bot = TestEnemy.named("Ct", "F").at(at[0], at[1], at[2] + 5).frozen(true).spawn(c.ctx(), c.server());
        Object savedMap = c.onClient(mc -> Mod.field(SF, "byUuid"));
        try (Settings s = new Settings(c).with(SC, "ShowSupporterNames", false).with(NC, "RandomizeOthers", false)) {
            String botName = bot.name();
            UUID botId = c.onClient(mc -> {
                for (Player p : mc.level.players()) {
                    if (p.getGameProfile().name().equals(botName)) {
                        return p.getUUID();
                    }
                }
                return null;
            });
            c.check(botId != null, "the bot " + botName + " is not in the client's world (premise)");
            UUID oldId = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100");
            UUID badId = UUID.fromString("1f1e1d1c-1b1a-4918-9716-151413121110");
            String body = relayBody(botId, oldId, badId);
            c.note("relay body: " + body);
            c.onClient(mc -> {
                Mod.staticCall("players.PlayerNames", "noteKnown", botId, botName);
                Object list = Mod.staticCall("supporters.SupportersFetcher", "parseSupporters",
                        com.google.gson.JsonParser.parseString(body).getAsJsonObject().get("supporters"));
                Mod.staticCall(SF, "apply", 7, list, false);
                return null;
            });
            String[] shown = c.onClient(mc -> {
                Mod.set(SC, "setShowSupporterNames", true);
                Map<?, ?> m = (Map<?, ?>) Mod.field(SF, "byUuid");
                String[] out = new String[3];
                UUID[] ids = {botId, oldId, badId};
                for (int i = 0; i < 3; i++) {
                    out[i] = (String) Mod.call(m.get(ids[i]), "displayText");
                }
                return out;
            });
            c.note("painted: styled '" + shown[0] + "', old '" + shown[1] + "', bad list '" + shown[2] + "'");
            c.check("§dOldEntry".equals(shown[1]), "an entry without colors is not its single color: " + shown[1]);
            c.check("§bBadList".equals(shown[2]), "a wrong-length colors list was not dropped: " + shown[2]);
            List<Integer> painted = c.onClient(mc -> colours(((Component) Mod.staticCall(SF, "displayNameFor", botId))
                    .getVisualOrderText()));
            c.note("nametag component colors: " + hexList(painted));
            c.check(painted.size() == FADE_NAME.length() && painted.get(0) == 0x00FF00
                    && painted.get(painted.size() - 1) == 0x0000FF, "the shared fade is not 00ff00..0000ff: " + hexList(painted));

            // The cache keeps the list across a restart.
            boolean cached = c.onClient(mc -> {
                Object loaded = Mod.staticCall("supporters.SupportersCache", "load");
                List<?> entries = (List<?>) Mod.call(loaded, "entries");
                Object before = Mod.staticCall("supporters.SupportersFetcher", "parseSupporters",
                        com.google.gson.JsonParser.parseString(body).getAsJsonObject().get("supporters"));
                Mod.staticCall("supporters.SupportersCache", "save", 7, before);
                Object again = Mod.call(Mod.staticCall("supporters.SupportersCache", "load"), "entries");
                boolean ok = false;
                for (Object e : (List<?>) again) {
                    if (botId.equals(Mod.call(e, "uuid"))) {
                        int[] col = (int[]) Mod.call(e, "colors");
                        ok = col != null && col.length == FADE_NAME.length();
                    }
                }
                Mod.staticCall("supporters.SupportersCache", "save", (Integer) Mod.call(loaded, "version"), entries);
                return ok;
            });
            c.check(cached, "the supporters cache lost the colors list");

            // Upload side: your own fade is what Share if Supporter sends.
            try (Settings f = fade(c)) {
                int[] mine = c.onClient(mc -> (int[]) Mod.staticCall(NF, "ownShareColours"));
                List<Integer> l = new ArrayList<>();
                if (mine != null) {
                    for (int v : mine) {
                        l.add(v);
                    }
                }
                c.note("own share colors: " + hexList(l));
                c.check(mine != null && mine.length == FADE_NAME.length() && mine[0] == 0x00FF00
                        && mine[mine.length - 1] == 0x0000FF, "own fade is not shared as 00ff00..0000ff: " + hexList(l));
            }

            // Screens: the bot's nametag (vs supporter names off) and the tab list.
            look(c, 0f, 0f);
            clearChat(c);
            BufferedImage off;
            try (Settings o = new Settings(c).with(SC, "ShowSupporterNames", false)) {
                off = shot(c, "nametag-off");
            }
            BufferedImage on = shot(c, "nametag-on");
            Fade tag = fadeOf(on, off, 0, 0, on.getWidth(), on.getHeight());
            c.note("nametag: " + tag);
            c.check(tag.ok(), "the supporter's nametag is not a clean green->blue fade: " + tag);

            tab(c, "template", "dungeon", "players", List.of(player(botName, "Mage", null), player(self, "Archer", null)));
            c.waitUntil("the text row shows " + FADE_NAME, mc -> {
                PlayerInfo info = textRowFor(mc, botName);
                return info != null && nameForDisplay(mc, info).getString().contains(FADE_NAME);
            }, 60);
            look(c, 0f, -30f);
            clearChat(c);
            BufferedImage closed = shot(c, "tab-closed");
            tabDown(c, true);
            BufferedImage open = shot(c, "tab-open");
            Fade tabFade = fadeOf(open, closed, 0, 0, open.getWidth(), open.getHeight() / 2);
            c.note("tab: " + tabFade);
            c.check(tabFade.ok(), "the supporter's name in the tab list is not a clean green->blue fade: " + tabFade);
        } finally {
            tabDown(c, false);
            c.onClient(mc -> {
                Mod.setField(SF, "byUuid", savedMap);
                return null;
            });
            bot.remove();
            hub(c);
        }
    }
}

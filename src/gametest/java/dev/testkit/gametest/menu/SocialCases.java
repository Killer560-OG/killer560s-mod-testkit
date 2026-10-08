package dev.testkit.gametest.menu;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.TextRuns;
import dev.testkit.gametest.hx.HxKit;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.ui.Frames;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * killer560's 2026-10-08 social report (mod {@code social/}): Best Friends and the Friends List. 591-599.
 *
 * <ul>
 *   <li>591-menu-social-bf-sort-box: the Best Friends screen's Sort and filter labels, for every sort mode (cycled by
 *       real presses), lie inside their buttons with at least 2 units of room each side, the controls do not overlap
 *       each other or the list, and all of it is on screen - at six window/GUI sizes and with the Unicode font.</li>
 *   <li>592-menu-social-bf-times: the compact ("1h 32m", "10d 3h 10m") and to-the-second ("1h 32m 5s") formats; a
 *       stored player's row draws the compact form, a real press on the row opens the detail with the detailed form,
 *       and while that player is partied the detail's seconds count up second by second.</li>
 *   <li>593-menu-social-bf-wallclock: a fake partymate joins by Hypixel's own chat line, stays two minutes of wall
 *       clock (with a save + reload of the store in the middle) and leaves; the time the tracker stored and the time
 *       the menu shows must each be within 1 s of the wall clock, and a save + reload afterwards keeps it exactly.</li>
 *   <li>594-menu-social-fl-tab-lines: the Friends List settings tab no longer has the "/fl opens our list" line or
 *       the "N friends on your list" line (Use Our /fl on and off), and keeps its two buttons.</li>
 *   <li>595-menu-social-fl-sync-progress: a three-page {@code /fl} (Hypixel's "Friends (Page X of 3)" headers and
 *       '&gt;&gt;' click events, fixtures {@code chat.fl-*}) walked with the screen open: the status line reads
 *       "Syncing... page 1/3" then "page 2/3", a real press on Refresh mid-sync keeps that line, and in every frame the
 *       line lies outside the list and inside the panel.</li>
 *   <li>596-menu-social-fl-status-layout: with 30 friends, at six window/GUI sizes and the Unicode font, the status
 *       line (synced, a button message, never synced) and the detail page's line lie outside the list, off every
 *       widget and inside the panel.</li>
 * </ul>
 *
 * Screenshots at GUI 2 also go to the folder in {@code TESTKIT_SOCIAL_SHOTS} when it is set.
 */
final class SocialCases {

    private static final String BF_SCREEN = "social.BestFriendsScreen";
    private static final String FL_SCREEN = "social.FriendsListScreen";
    private static final String TRACKER = "social.BestFriendsTracker";
    private static final String STORE = "social.BestFriendsStore";
    private static final String SYNC = "social.FriendsListSync";

    /** {window w, h, GUI scale, screenshot at this size}. */
    private static final int[][] SIZES = {{854, 480, 2, 0}, {854, 480, 3, 0}, {1280, 720, 2, 0}, {1920, 1080, 2, 1},
            {1920, 1080, 4, 0}, {2560, 1440, 3, 0}};

    private SocialCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "591-menu-social-bf-sort-box", SocialCases::sortBox);
        MenuSuite.test(s, "592-menu-social-bf-times", SocialCases::times);
        MenuSuite.test(s, "593-menu-social-bf-wallclock", SocialCases::wallClock);
        MenuSuite.test(s, "594-menu-social-fl-tab-lines", SocialCases::tabLines);
        MenuSuite.test(s, "595-menu-social-fl-sync-progress", SocialCases::syncProgress);
        MenuSuite.test(s, "596-menu-social-fl-status-layout", SocialCases::statusLayout);
    }

    // ---- shared ------------------------------------------------------------------------------------------------

    /** One drawn text run: its plain string and advance box (GUI units). */
    record Txt(String s, int x, int y, int w, int h) {
        boolean intersects(int[] r) {
            return x < r[0] + r[2] && r[0] < x + w && y < r[1] + r[3] && r[1] < y + h;
        }

        boolean inside(int[] r, int pad) {
            return x >= r[0] + pad && y >= r[1] && x + w <= r[0] + r[2] - pad && y + h <= r[1] + r[3];
        }

        @Override
        public String toString() {
            return "'" + s + "' @" + x + "," + y + " " + w + "x" + h;
        }
    }

    /** Every text the screen draws in one off-screen extract (mouse off everything). Client thread. */
    static List<Txt> texts(Minecraft mc, Screen screen) {
        GuiRenderState state = new GuiRenderState();
        GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
        screen.extractRenderStateWithTooltipAndSubtitles(g, -1, -1, 0.5f);
        List<Txt> out = new ArrayList<>();
        state.forEachText(t -> {
            ScreenRectangle b = TextRuns.box(t);
            out.add(new Txt(TextRuns.string(t), b.left(), b.top(), b.width(), b.height()));
        });
        return out;
    }

    /** A widget's label without formatting codes, as the drawn text run reads. */
    static String plain(AbstractWidget w) {
        return w.getMessage().getString().replaceAll("§.", "");
    }

    static int[] rect(AbstractWidget w) {
        return new int[]{w.getX(), w.getY(), w.getWidth(), w.getHeight()};
    }

    static boolean overlap(int[] a, int[] b) {
        return a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
    }

    static String str(int[] r) {
        return r[0] + "," + r[1] + " " + r[2] + "x" + r[3];
    }

    static int intField(Object o, String name) {
        return ((Number) Mod.field(o, name)).intValue();
    }

    static Screen current(Session c) {
        return c.onClient(McCompat::screen);
    }

    static Screen open(Session c, String cls) {
        c.ctx().runOnClient(mc -> {
            try {
                Screen s = (Screen) Mod.cls(cls).getConstructor(Screen.class).newInstance((Screen) null);
                McCompat.setScreen(mc, s);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        c.ctx().waitTicks(4);
        Screen s = current(c);
        c.check(s != null && s.getClass().getName().endsWith(cls), cls + " did not open (open: " + s + ")");
        return s;
    }

    static void closeScreen(Session c) {
        c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
        c.ctx().waitTicks(2);
    }

    static int[] window(Session c) {
        return c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(), mc.options.guiScale().get()});
    }

    static void unicode(Session c, boolean on) {
        c.ctx().runOnClient(mc -> mc.options.forceUnicodeFont().set(on));
        c.ctx().waitTicks(10);
    }

    /** A widget whose label (plain) starts with {@code prefix}. */
    static AbstractWidget widget(Session c, Screen s, String prefix) {
        return c.onClient(mc -> {
            for (AbstractWidget w : Frames.widgets(s)) {
                if (plain(w).startsWith(prefix)) {
                    return w;
                }
            }
            return null;
        });
    }

    static void pressAt(Session c, int[] box) {
        BazaarReskinCases.pressAt(c, box, 0);
    }

    /** A screenshot kept in the report, and copied to TESTKIT_SOCIAL_SHOTS when that is set. */
    static void shot(Session c, String label) {
        c.ctx().getInput().setCursorPos(2, 2);
        c.ctx().waitTicks(6);
        String name = c.name() + "-" + label;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        Path kept = dev.testkit.harness.Report.screenshot(name, taken);
        String dir = System.getenv("TESTKIT_SOCIAL_SHOTS");
        String copied = "";
        if (dir != null && !dir.isBlank()) {
            try {
                Path d = Path.of(dir);
                Files.createDirectories(d);
                Path to = d.resolve(dev.testkit.harness.Report.fileName(name) + ".png");
                Files.copy(taken, to, StandardCopyOption.REPLACE_EXISTING);
                copied = ", copied to " + to;
            } catch (java.io.IOException e) {
                copied = ", copy failed: " + e;
            }
        }
        c.note("screenshot " + label + " -> " + (kept != null ? kept : taken) + copied);
    }

    /** Setup shared by every case: no screen, Auto Scale off, SkyblockGate off; restored on close. */
    static final class Env implements AutoCloseable {
        final Session c;
        final MenuKit.Cfg cfg;
        final boolean gate;
        final int[] window;
        final boolean unicode;
        final boolean bfEnabled;

        Env(Session c) {
            this.c = c;
            MenuKit.reset(c);
            cfg = new MenuKit.Cfg(c);
            cfg.set("hud.HudConfig", "AutoScale", false);
            gate = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
            window = window(c);
            unicode = c.onClient(mc -> mc.options.forceUnicodeFont().get());
            bfEnabled = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg("social.BestFriendsConfig"), "getEnabledRaw"));
            c.ctx().runOnClient(mc -> Mod.staticCall("util.SkyblockGate", "setEnabled", false));
        }

        @Override
        public void close() {
            try {
                closeScreen(c);
                if (c.onClient(mc -> mc.options.forceUnicodeFont().get()) != unicode) {
                    unicode(c, unicode);
                }
                BazaarV3Cases.setWindow(c, window[0], window[1], window[2]);
                c.ctx().runOnClient(mc -> Mod.call(Mod.cfg("social.BestFriendsConfig"), "setEnabled", bfEnabled));
            } finally {
                cfg.close();
                c.ctx().runOnClient(mc -> Mod.staticCall("util.SkyblockGate", "setEnabled", gate));
            }
        }
    }

    // ---- Best Friends: stored / shown time, old jar and new -----------------------------------------------------

    /** Stored time in ms: {@code totalPartyMs} (since 2026-10-08) or whole {@code totalPartySeconds} before it. */
    static long storedMs(Object record) {
        if (record == null) {
            return 0L;
        }
        try {
            Field f = record.getClass().getDeclaredField("totalPartyMs");
            f.setAccessible(true);
            return f.getLong(record);
        } catch (NoSuchFieldException e) {
            try {
                Field f = record.getClass().getDeclaredField("totalPartySeconds");
                f.setAccessible(true);
                return f.getLong(record) * 1000L;
            } catch (ReflectiveOperationException e2) {
                throw new AssertionError(e2);
            }
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    static boolean hasMethod(String cls, String name) {
        for (Method m : Mod.cls(cls).getMethods()) {
            if (m.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** What the menu shows: the live total where the jar has one, else the stored total (the old menu's number). */
    static long shownMs(Object record) {
        if (record == null) {
            return 0L;
        }
        if (hasMethod(TRACKER, "liveTotalMs")) {
            return ((Number) Mod.staticCall(TRACKER, "liveTotalMs", record)).longValue();
        }
        return storedMs(record);
    }

    @SuppressWarnings("unchecked")
    static boolean accruing(UUID id) {
        return ((Map<UUID, Long>) Mod.field(TRACKER, "SESSION_START")).containsKey(id);
    }

    @SuppressWarnings("unchecked")
    static List<String> teammates() {
        return (List<String>) Mod.staticCall("leapmenu.PartyTracker", "teammates");
    }

    static void leaveParty(Session c) {
        c.hx().chat("§eYou left the party.");
        c.waitUntil("PartyTracker empty after 'You left the party.'", mc -> teammates().isEmpty(), 60);
    }

    /** A partymate the tracker can key: its UUID seeded in the name cache, joined by Hypixel's own line. */
    static void join(Session c, UUID id, String name) {
        c.ctx().runOnClient(mc -> Mod.staticCall("players.PlayerNames", "noteKnown", id, name));
        c.hx().chat("§b" + name + " §ejoined the party.");
    }

    // ---- 591 ---------------------------------------------------------------------------------------------------

    static void sortBox(Session c) {
        try (Env env = new Env(c)) {
            Object cfg = c.onClient(mc -> Mod.cfg("social.BestFriendsConfig"));
            Object oldSort = c.onClient(mc -> Mod.call(cfg, "getSortMode"));
            Object oldFilter = c.onClient(mc -> Mod.call(cfg, "isDungeonOnlyFilter"));
            try {
                int frames = 0;
                for (int pass = 0; pass < SIZES.length + 1; pass++) {
                    boolean uni = pass == SIZES.length;
                    int[] sz = uni ? new int[]{1920, 1080, 2, 1} : SIZES[pass];
                    if (uni) {
                        unicode(c, true);
                    }
                    BazaarV3Cases.setWindow(c, sz[0], sz[1], sz[2]);
                    String tag = sz[0] + "x" + sz[1] + "-gui" + sz[2] + (uni ? "-unicode" : "");
                    Screen s = open(c, BF_SCREEN);
                    int[] gui = BazaarV3Cases.guiSize(c);
                    Set<String> modes = new LinkedHashSet<>();
                    for (int press = 0; press < 4; press++) {
                        Screen live = current(c);
                        AbstractWidget sort = widget(c, live, "Sort:");
                        c.check(sort != null, tag + ": no Sort button");
                        frames++;
                        modes.add(plain(sort));
                        frame(c, live, tag + " [" + plain(sort) + "]", gui);
                        if (sz[3] == 1 && press == 0) {
                            shot(c, "bf-list-" + tag);
                        }
                        // Cycle the sort by a real press on the button.
                        pressAt(c, rect(sort));
                        c.ctx().waitTicks(2);
                    }
                    // Filter in its other state too.
                    AbstractWidget filter = c.onClient(mc -> {
                        for (AbstractWidget w : Frames.widgets(McCompat.screen(mc))) {
                            String m = plain(w);
                            if (m.startsWith("Any Party") || m.startsWith("Dungeon Only")) {
                                return w;
                            }
                        }
                        return null;
                    });
                    c.check(filter != null, tag + ": no filter button");
                    pressAt(c, rect(filter));
                    frame(c, current(c), tag + " [filter toggled]", gui);
                    frames++;
                    c.check(modes.size() >= 3, tag + ": real presses on Sort showed only " + modes);
                    c.note(tag + ": GUI " + gui[0] + "x" + gui[1] + ", sort labels " + modes + " inside their box");
                    closeScreen(c);
                }
                c.note(frames + " frames: every Sort/filter label inside its button (2 units of room), no overlaps");
            } finally {
                c.ctx().runOnClient(mc -> {
                    Mod.call(cfg, "setSortMode", oldSort);
                    Mod.call(cfg, "setDungeonOnlyFilter", oldFilter);
                    Mod.call(cfg, "save");
                });
            }
        }
    }

    /** Every control's label inside its control, controls apart, on screen, and above the list. */
    private static void frame(Session c, Screen s, String tag, int[] gui) {
        List<String> problems = c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            List<Txt> texts = texts(mc, s);
            List<AbstractWidget> ws = Frames.widgets(s);
            int listX = intField(s, "listX");
            int listY = intField(s, "listY");
            int listW = intField(s, "listW");
            int listH = intField(s, "listH");
            int[] list = {listX - 1, listY - 1, listW + 2, listH + 2};
            for (int i = 0; i < ws.size(); i++) {
                AbstractWidget w = ws.get(i);
                int[] r = rect(w);
                if (r[0] < 0 || r[1] < 0 || r[0] + r[2] > gui[0] || r[1] + r[3] > gui[1]) {
                    out.add("off screen: " + w.getMessage().getString() + " " + str(r));
                }
                if (overlap(r, list)) {
                    out.add("over the list: " + w.getMessage().getString() + " " + str(r) + " list " + str(list));
                }
                for (int j = i + 1; j < ws.size(); j++) {
                    if (overlap(r, rect(ws.get(j)))) {
                        out.add("overlap: " + w.getMessage().getString() + " " + str(r) + " / "
                                + ws.get(j).getMessage().getString() + " " + str(rect(ws.get(j))));
                    }
                }
                String label = plain(w);
                if (label.isEmpty() || w instanceof net.minecraft.client.gui.components.EditBox) {
                    continue;
                }
                Txt drawn = null;
                for (Txt t : texts) {
                    if (t.s().equals(label) && overlap(new int[]{t.x(), t.y(), t.w(), t.h()}, r)) {
                        drawn = t;
                        break;
                    }
                }
                if (drawn == null) {
                    out.add("label not drawn: " + label);
                } else if (!drawn.inside(r, 2)) {
                    out.add("label outside its box: " + drawn + " in " + str(r));
                }
            }
            return out;
        });
        c.check(problems.isEmpty(), tag + ": " + String.join(" | ", problems));
    }

    // ---- 592 ---------------------------------------------------------------------------------------------------

    static void times(Session c) {
        long[][] table = {
                {0L}, {38_400L}, {45L * 60_000L}, {(3600L + 32 * 60 + 5) * 1000L + 999L},
                {(10 * 86_400L + 3 * 3600 + 10 * 60 + 5) * 1000L}, {(86_400L + 59) * 1000L}};
        String[][] want = {{"0s", "0s"}, {"38s", "38s"}, {"45m", "45m 0s"}, {"1h 32m", "1h 32m 5s"},
                {"10d 3h 10m", "10d 3h 10m 5s"}, {"1d 0h 0m", "1d 0h 0m 59s"}};
        for (int i = 0; i < table.length; i++) {
            long ms = table[i][0];
            String compact = c.onClient(mc -> (String) Mod.staticCall(BF_SCREEN, "formatCompact", ms));
            String detail = c.onClient(mc -> (String) Mod.staticCall(BF_SCREEN, "formatDetailed", ms));
            c.check(want[i][0].equals(compact), ms + " ms: compact '" + compact + "' != '" + want[i][0] + "'");
            c.check(want[i][1].equals(detail), ms + " ms: detailed '" + detail + "' != '" + want[i][1] + "'");
        }
        c.note("formats: 1h 32m / 1h 32m 5s, 10d 3h 10m / 10d 3h 10m 5s, 45m / 45m 0s, 38s");

        UUID id = UUID.fromString("5a1c0de0-0000-4000-8000-000000000592");
        String name = "HxTimeMate";
        try (Env env = new Env(c)) {
            BazaarV3Cases.setWindow(c, 1920, 1080, 2);
            leaveParty(c);
            long stored = (3600L + 32 * 60 + 5) * 1000L + 250L;
            // A stored player with no runs is hidden by Dungeon Only.
            env.cfg.set("social.BestFriendsConfig", "DungeonOnlyFilter", false);
            c.ctx().runOnClient(mc -> {
                Mod.call(Mod.cfg("social.BestFriendsConfig"), "setEnabled", true);
                Object rec = Mod.staticCall(STORE, "getOrCreate", id, name);
                HxKit.setInstanceField(rec, "totalPartyMs", stored);
            });
            Screen s = open(c, BF_SCREEN);
            c.ctx().runOnClient(mc -> ((net.minecraft.client.gui.components.EditBox) Mod.field(s, "searchBox")).setValue(name));
            c.ctx().waitTicks(2);
            List<Txt> list = c.onClient(mc -> texts(mc, s));
            Txt row = list.stream().filter(t -> t.s().startsWith("1h 32m  |")).findFirst().orElse(null);
            c.check(row != null, "the row did not draw '1h 32m  | ...': " + list);
            c.check(list.stream().noneMatch(t -> t.s().contains("1h 32m 5s")), "the list row shows seconds");
            int listX = c.onClient(mc -> intField(s, "listX"));
            int listY = c.onClient(mc -> intField(s, "listY"));
            int listW = c.onClient(mc -> intField(s, "listW"));
            pressAt(c, new int[]{listX + 40, listY, listW - 80, 20});
            Screen detail = current(c);
            Object selected = c.onClient(mc -> Mod.field(detail, "selected"));
            c.check(selected != null, "a real press on the row did not open the player's detail");
            List<Txt> d = c.onClient(mc -> texts(mc, detail));
            c.check(d.stream().anyMatch(t -> t.s().equals("Time together  1h 32m 5s")),
                    "detail does not show 'Time together  1h 32m 5s': " + d);
            shot(c, "bf-detail-1920x1080-gui2");

            // Partied now: the detail must count up second by second.
            join(c, id, name);
            c.waitUntil("the tracker accruing for " + name, mc -> accruing(id), 100);
            List<Long> seen = new ArrayList<>();
            long until = System.nanoTime() + 4_500_000_000L;
            while (System.nanoTime() < until) {
                String line = c.onClient(mc -> {
                    for (Txt t : texts(mc, McCompat.screen(mc))) {
                        if (t.s().startsWith("Time together")) {
                            return t.s();
                        }
                    }
                    return "";
                });
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("^Time together {2}(\\d{1,3})h (\\d{1,2})m (\\d{1,2})s.*$").matcher(line);
                c.check(m.matches(), "detail line '" + line + "' is not 'Time together  Hh Mm Ss'");
                long secs = Long.parseLong(m.group(1)) * 3600 + Long.parseLong(m.group(2)) * 60 + Long.parseLong(m.group(3));
                if (seen.isEmpty() || seen.get(seen.size() - 1) != secs) {
                    seen.add(secs);
                }
                c.ctx().waitTicks(2);
            }
            c.check(seen.size() >= 4, "in 4.5 s the detail showed only " + seen + " (it must count up every second)");
            for (int i = 1; i < seen.size(); i++) {
                c.check(seen.get(i) - seen.get(i - 1) == 1, "the detail jumped " + seen.get(i - 1) + " -> " + seen.get(i));
            }
            c.note("row '" + row.s() + "'; detail 1h 32m 5s; partied, it counted " + seen + " (one step a second)");
            shot(c, "bf-detail-live-1920x1080-gui2");
            c.hx().chat("§b" + name + " §ehas left the party.");
            c.waitUntil("the tracker stopping for " + name, mc -> !accruing(id), 100);
        }
    }

    // ---- 593 ---------------------------------------------------------------------------------------------------

    static void wallClock(Session c) {
        UUID id = UUID.fromString("5a1c0de0-0000-4000-8000-000000000593");
        String name = "HxBestie";
        try (Env env = new Env(c)) {
            HxKit.hub(c);
            leaveParty(c);
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg("social.BestFriendsConfig"), "setEnabled", true));
            long base = c.onClient(mc -> storedMs(Mod.staticCall(STORE, "get", id)));
            join(c, id, name);
            c.waitUntil("the tracker accruing for " + name, mc -> accruing(id), 100);
            long t0 = System.nanoTime();

            // 60 s of wall clock, then a save + reload of the store mid-session.
            waitWall(c, t0, 60_000L);
            long[] reload = c.onClient(mc -> {
                long before = shownMs(Mod.staticCall(STORE, "get", id));
                long t = System.nanoTime();
                if (hasMethod(STORE, "saveNow")) {
                    Mod.staticCall(STORE, "saveNow");
                } else {
                    Mod.staticCall(STORE, "saveAsync");
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                Mod.staticCall(STORE, "load");
                long after = shownMs(Mod.staticCall(STORE, "get", id));
                return new long[]{before, after, (System.nanoTime() - t) / 1_000_000L};
            });
            long reloadDrift = reload[1] - reload[0] - reload[2];
            c.note(String.format(Locale.ROOT, "mid-session save+reload: shown %d ms before, %d ms after, %d ms apart (drift %d ms)",
                    reload[0], reload[1], reload[2], reloadDrift));

            waitWall(c, t0, 120_000L);
            long[] at = c.onClient(mc -> new long[]{shownMs(Mod.staticCall(STORE, "get", id)), System.nanoTime()});
            long shownErr = (at[0] - base) - (at[1] - t0) / 1_000_000L;

            c.hx().chat("§b" + name + " §ehas left the party.");
            c.waitUntil("the tracker stopping for " + name, mc -> !accruing(id), 100);
            long t1 = System.nanoTime();
            long stored = c.onClient(mc -> storedMs(Mod.staticCall(STORE, "get", id)));
            long wall = (t1 - t0) / 1_000_000L;
            long storedErr = (stored - base) - wall;

            long[] after = c.onClient(mc -> {
                long before = storedMs(Mod.staticCall(STORE, "get", id));
                if (hasMethod(STORE, "saveNow")) {
                    Mod.staticCall(STORE, "saveNow");
                } else {
                    Mod.staticCall(STORE, "saveAsync");
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                Mod.staticCall(STORE, "load");
                return new long[]{before, storedMs(Mod.staticCall(STORE, "get", id))};
            });

            c.note(String.format(Locale.ROOT, "wall clock %d ms partied; tracker stored %d ms (error %+d ms); menu showed "
                    + "%d ms at 120 s (error %+d ms); after leaving, save+reload %d -> %d ms", wall, stored - base,
                    storedErr, at[0] - base, shownErr, after[0], after[1]));
            System.out.println(String.format(Locale.ROOT, "[593] wall=%d stored=%d storedErr=%+d shownErr=%+d reloadDrift=%+d "
                    + "afterReload=%d->%d", wall, stored - base, storedErr, shownErr, reloadDrift, after[0], after[1]));
            c.check(wall >= 115_000L, "only " + wall + " ms of wall clock passed - the run did not exercise two minutes");
            c.check(stored - base > 60_000L, "the tracker stored " + (stored - base) + " ms - it never accrued");
            c.check(Math.abs(storedErr) <= 1000L, "stored time is " + storedErr + " ms off the wall clock over " + wall + " ms");
            c.check(Math.abs(shownErr) <= 1000L, "the menu's time is " + shownErr + " ms off the wall clock at 120 s");
            c.check(Math.abs(reloadDrift) <= 1000L, "a mid-session save+reload moved the shown time by " + reloadDrift + " ms");
            c.check(after[0] == after[1], "save+reload changed the stored time " + after[0] + " -> " + after[1]);
        }
    }

    /** Waits until {@code ms} of wall clock have passed since {@code t0}. */
    private static void waitWall(Session c, long t0, long ms) {
        long deadline = t0 + ms * 1_000_000L;
        c.waitUntil(ms / 1000 + " s of wall clock", mc -> System.nanoTime() >= deadline, (int) (ms / 50 * 3));
    }

    // ---- 594 ---------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static void tabLines(Session c) {
        Object cfg = c.onClient(mc -> Mod.cfg("social.FriendsListConfig"));
        boolean was = c.onClient(mc -> (Boolean) Mod.call(cfg, "isEnabled"));
        try {
            for (boolean on : new boolean[]{true, false}) {
                List<String> labels = c.onClient(mc -> {
                    Mod.call(cfg, "setEnabled", on);
                    Object tab;
                    try {
                        tab = Mod.cls("gui.tab.FriendsListTab").getConstructor().newInstance();
                    } catch (ReflectiveOperationException e) {
                        throw new AssertionError(e);
                    }
                    Runnable noop = () -> {
                    };
                    List<AbstractWidget> ws = (List<AbstractWidget>) Mod.call(tab, "buildWidgets", 10, 10, 300, noop);
                    List<String> out = new ArrayList<>();
                    for (AbstractWidget w : ws) {
                        out.add(w.getMessage().getString());
                    }
                    return out;
                });
                String tag = "Use Our /fl " + (on ? "ON" : "OFF");
                for (String l : labels) {
                    String low = l.toLowerCase(Locale.ROOT);
                    c.check(!low.contains("/fl opens our") && !low.contains("passes straight through"),
                            tag + ": the '/fl opens our list' line is still there: '" + l + "'");
                    c.check(!low.contains("on your list"), tag + ": the 'friends on your list' line is still there: '" + l + "'");
                }
                c.check(labels.stream().anyMatch(l -> l.startsWith("Use Our /fl")), tag + ": no Use Our /fl toggle: " + labels);
                c.check(labels.stream().anyMatch(l -> l.startsWith("Open Friends List")), tag + ": no Open button: " + labels);
                c.note(tag + ": " + labels);
            }
        } finally {
            c.ctx().runOnClient(mc -> Mod.call(cfg, "setEnabled", was));
        }
    }

    // ---- 595 ---------------------------------------------------------------------------------------------------

    static void syncProgress(Session c) {
        String sep = HxKit.text(HxKit.fx(c, "chat.fl-separator"));
        String online = HxKit.text(HxKit.fx(c, "chat.fl-friend-online"));
        try (Env env = new Env(c)) {
            BazaarV3Cases.setWindow(c, 1920, 1080, 2);
            seedFriends(c, 30);
            respond(c, "fl", "fl", page(c, sep, 1, 3, "/friend list 2", null, online, "HxPalB1 is currently offline"));
            respond(c, "friend", "friend list 2", page(c, sep, 2, 3, "/friend list 3", "/friend list 1",
                    "HxPalB2 is in SkyBlock - Garden", "HxPalB3 is currently offline"));
            respond(c, "friend", "friend list 3", page(c, sep, 3, 3, null, "/friend list 2", "HxPalB4 is currently offline"));
            c.ctx().runOnClient(mc -> Mod.setField(SYNC, "lastSyncAtMs", 0L));
            Screen s = open(c, FL_SCREEN);
            if (!c.onClient(mc -> (Boolean) Mod.staticCall(SYNC, "isSyncing"))) {
                boolean started = c.onClient(mc -> (Boolean) Mod.staticCall(SYNC, "requestSync", true));
                c.check(started, "FriendsListSync.requestSync refused to start");
            }
            Set<String> seen = new LinkedHashSet<>();
            List<String> problems = new ArrayList<>();
            boolean pressedRefresh = false;
            boolean shotTaken = false;
            String afterRefresh = null;
            for (int tick = 0; tick < 400; tick++) {
                boolean syncing = c.onClient(mc -> (Boolean) Mod.staticCall(SYNC, "isSyncing"));
                if (!syncing) {
                    break;
                }
                List<Txt> texts = c.onClient(mc -> texts(mc, s));
                int[][] r = c.onClient(mc -> layoutRects(s));
                for (Txt t : texts) {
                    if (t.s().startsWith("Syncing")) {
                        seen.add(t.s());
                        if (t.intersects(r[0])) {
                            problems.add("status over the list: " + t + " list " + str(r[0]));
                        }
                        if (!t.inside(r[1], 0)) {
                            problems.add("status outside the panel: " + t + " panel " + str(r[1]));
                        }
                    }
                }
                if (!pressedRefresh && seen.contains("Syncing... page 1/3")) {
                    pressedRefresh = true;
                    AbstractWidget refresh = widget(c, s, "Refresh");
                    c.check(refresh != null, "no Refresh button");
                    pressAt(c, rect(refresh));
                    afterRefresh = c.onClient(mc -> {
                        for (Txt t : texts(mc, s)) {
                            if (t.s().startsWith("Syncing") || t.s().startsWith("Already")) {
                                return t.s();
                            }
                        }
                        return "";
                    });
                }
                if (!shotTaken && seen.contains("Syncing... page 2/3")) {
                    shotTaken = true;
                    shot(c, "fl-syncing-1920x1080-gui2");
                }
                c.ctx().waitTicks(1);
            }
            c.check(!c.onClient(mc -> (Boolean) Mod.staticCall(SYNC, "isSyncing")), "the sync never finished");
            List<String> cmds = c.commands();
            c.check(cmds.contains("fl") && cmds.contains("friend list 2") && cmds.contains("friend list 3"),
                    "pages requested: " + cmds);
            c.check(seen.contains("Syncing... page 1/3") && seen.contains("Syncing... page 2/3"),
                    "status lines seen while syncing: " + seen);
            c.check(pressedRefresh && afterRefresh != null && afterRefresh.startsWith("Syncing... page"),
                    "Refresh pressed mid-sync left the status as '" + afterRefresh + "'");
            c.check(problems.isEmpty(), String.join(" | ", problems.subList(0, Math.min(6, problems.size()))));
            List<String> names = c.onClient(mc -> friendNames());
            c.check(names.containsAll(List.of("HxFriendA", "HxPalB1", "HxPalB2", "HxPalB3", "HxPalB4")), "synced " + names);
            c.note("status lines while syncing " + seen + "; Refresh mid-sync -> '" + afterRefresh
                    + "'; never over the list; synced " + names.size() + " friends from 3 pages");
        } finally {
            c.hx().call("surface.respond.clear", "root", "fl");
            c.hx().call("surface.respond.clear", "root", "friend");
        }
    }

    private static void respond(Session c, String root, String match, JsonObject reply) {
        HxKit.respond(c, root, match, reply);
    }

    /** One /fl page as a single multi-line message, as 132-hx-friendslist-paged-fl sends it. */
    private static JsonObject page(Session c, String sep, int page, int of, String next, String prev, String... lines) {
        JsonArray msg = new JsonArray();
        msg.add("");
        msg.add(sep + "\n");
        if (prev != null) {
            msg.add(HxKit.clickable("§e<< ", prev));
        }
        JsonObject head = new JsonObject();
        head.addProperty("text", "Friends (Page " + page + " of " + of + ")" + (next != null ? " " : ""));
        head.addProperty("color", "gold");
        msg.add(head);
        if (next != null) {
            msg.add(HxKit.clickable("§e>>", next));
        }
        StringBuilder body = new StringBuilder();
        for (String l : lines) {
            body.append('\n').append(l);
        }
        body.append('\n').append(sep);
        msg.add(body.toString());
        JsonObject o = new JsonObject();
        o.add("textJson", msg);
        return o;
    }

    @SuppressWarnings("unchecked")
    static List<String> friendNames() {
        List<String> out = new ArrayList<>();
        for (Object f : (List<Object>) Mod.call(Mod.cfg("social.FriendsListConfig"), "friends")) {
            out.add((String) Mod.field(f, "name"));
        }
        return out;
    }

    static void seedFriends(Session c, int n) {
        c.ctx().runOnClient(mc -> {
            List<String> names = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                names.add(String.format(Locale.ROOT, "HxPal%02d", i));
            }
            Mod.call(Mod.cfg("social.FriendsListConfig"), "applyRealSync", names, false);
        });
    }

    /** {list outline rect, panel rect} of a FriendsListScreen. */
    static int[][] layoutRects(Screen s) {
        int listX = intField(s, "listX");
        int listY = intField(s, "listY");
        int listW = intField(s, "listW");
        int listH = intField(s, "listH");
        int panelX = intField(s, "panelX");
        int panelY = intField(s, "panelY");
        int panelW = intField(s, "panelW");
        int panelH = intField(s, "panelH");
        return new int[][]{{listX - 1, listY - 1, listW + 2, listH + 2}, {panelX, panelY, panelW, panelH}};
    }

    // ---- 596 ---------------------------------------------------------------------------------------------------

    static void statusLayout(Session c) {
        try (Env env = new Env(c)) {
            seedFriends(c, 30);
            Predicate<String> isStatus = t -> t.contains("real friend(s), last synced") || t.startsWith("Already just synced")
                    || t.startsWith("Never synced") || t.startsWith("Hypixel's /fl may show more");
            int frames = 0;
            for (int pass = 0; pass < SIZES.length + 1; pass++) {
                boolean uni = pass == SIZES.length;
                int[] sz = uni ? new int[]{1920, 1080, 2, 0} : SIZES[pass];
                if (uni) {
                    unicode(c, true);
                }
                BazaarV3Cases.setWindow(c, sz[0], sz[1], sz[2]);
                String tag = sz[0] + "x" + sz[1] + "-gui" + sz[2] + (uni ? "-unicode" : "");
                // Synced just now: opening must not start a sync.
                seedFriends(c, 30);
                Screen s = open(c, FL_SCREEN);
                c.check(!c.onClient(mc -> (Boolean) Mod.staticCall(SYNC, "isSyncing")), tag + ": a sync started");
                for (String state : new String[]{"synced", "message", "never"}) {
                    c.ctx().runOnClient(mc -> {
                        Object cfg = Mod.cfg("social.FriendsListConfig");
                        switch (state) {
                            case "message" -> HxKit.setInstanceField(s, "statusMessage", "Already just synced - give it a moment.");
                            case "never" -> {
                                HxKit.setInstanceField(s, "statusMessage", "");
                                HxKit.setInstanceField(cfg, "everSynced", false);
                            }
                            default -> HxKit.setInstanceField(s, "statusMessage", "");
                        }
                    });
                    frames++;
                    checkStatus(c, s, tag + " " + state, isStatus, true);
                    if (sz[3] == 1 && state.equals("synced")) {
                        shot(c, "fl-list-" + tag);
                    }
                    c.ctx().runOnClient(mc -> HxKit.setInstanceField(Mod.cfg("social.FriendsListConfig"), "everSynced", true));
                }
                // The detail page: a real press on a row, then the button message above the bar.
                int[][] r = c.onClient(mc -> layoutRects(s));
                pressAt(c, new int[]{r[0][0] + 30, r[0][1] + 2, 60, 18});
                Screen d = current(c);
                c.check(c.onClient(mc -> Mod.field(d, "selected")) != null, tag + ": a press on a row did not open it");
                c.ctx().runOnClient(mc -> HxKit.setInstanceField(d, "statusMessage", "Sent /party invite HxPal01"));
                frames++;
                checkStatus(c, d, tag + " detail", t -> t.startsWith("Sent /party invite"), true);
                if (sz[3] == 1) {
                    shot(c, "fl-detail-" + tag);
                }
                closeScreen(c);
                c.note(tag + ": status line clear of the list, widgets and panel edge (list, message, never synced, detail)");
            }
            c.note(frames + " frames checked");
        }
    }

    private static void checkStatus(Session c, Screen s, String tag, Predicate<String> isStatus, boolean required) {
        List<String> problems = c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            int[][] r = layoutRects(s);
            List<Txt> texts = texts(mc, s);
            Txt status = null;
            for (Txt t : texts) {
                if (isStatus.test(t.s())) {
                    status = t;
                }
            }
            if (status == null) {
                if (required) {
                    out.add("no status line drawn; texts " + texts.stream().map(Txt::s).toList());
                }
                return out;
            }
            if (status.intersects(r[0])) {
                out.add("status over the list: " + status + " list " + str(r[0]));
            }
            if (!status.inside(r[1], 0)) {
                out.add("status outside the panel: " + status + " panel " + str(r[1]));
            }
            for (AbstractWidget w : Frames.widgets(s)) {
                if (status.intersects(rect(w))) {
                    out.add("status over " + w.getMessage().getString() + " " + str(rect(w)) + ": " + status);
                }
            }
            return out;
        });
        c.check(problems.isEmpty(), tag + ": " + String.join(" | ", problems));
    }
}

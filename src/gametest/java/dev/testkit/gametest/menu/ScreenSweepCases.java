package dev.testkit.gametest.menu;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.TextRuns;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.ui.Frames;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * killer560's 2026-10-13 sweep of the mod's other screens (mod sweep-a): each one drawn in the menu theme, and each with
 * its own Theme setting ("Theme Default" follows the menu). 950-957. Every themed screen is shot in Amber and Light;
 * the shot is read back and the panel's accent ring must be the palette's {@code FRAME_RING} for that theme, the panel
 * itself dark on Amber and light on Light (the pet wheel has no panel: its first tile), and on Light no text the screen
 * draws may carry a drop shadow or a bright base colour (the smear and the white-on-light the sweep fixes). Then each
 * screen's main action, done with real presses and typing:
 * <ul>
 *   <li>950-menu-sweep-pv: /pv on AntsRNG loads from the fake SkyBlockPV backend (the fixture 400 uses) to READY with
 *       the fixture's profile, and a real press on the Dungeons tab opens that page.</li>
 *   <li>951-menu-sweep-friends: 30 synced friends draw as rows; typing "al1" on the list (nothing focused) narrows it to
 *       the 10 names containing it, clearing the box shows all 30 again; a press on a row opens it.</li>
 *   <li>952-menu-sweep-bestfriends: a stored player draws as a row; typing narrows the list to him.</li>
 *   <li>953-menu-sweep-accounts: three fake Prism accounts (testkit's own accounts copy) list as buttons; the Direct
 *       Session Login, per-account proxy and Universal Proxy pages are shot too; a press on Back closes the list.</li>
 *   <li>954-menu-sweep-popups: the "updated to" popup (OK closes it) and Window Layout (the Windows button steps the
 *       count, Cancel closes it).</li>
 *   <li>955-menu-sweep-petwheel: the wheel opens with four pets; hovering a tile gives it the accent outline.</li>
 *   <li>956-menu-sweep-screen-theme: with the menu on Amber, the Friends List set to Light draws Light; back to Theme
 *       Default it follows the menu (Amber) again; the tab's Theme button steps both ways and the choice survives a
 *       re-read of its file.</li>
 *   <li>957-menu-sweep-search-hint: the mod menu's header search hint is drawn without a shadow on Light.</li>
 * </ul>
 * Shots also go to {@code TESTKIT_SWEEP_SHOTS}/&lt;screen&gt;/ when that is set.
 */
final class ScreenSweepCases {

    private ScreenSweepCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "950-menu-sweep-pv", ScreenSweepCases::pv);
        MenuSuite.test(s, "951-menu-sweep-friends", ScreenSweepCases::friends);
        MenuSuite.test(s, "952-menu-sweep-bestfriends", ScreenSweepCases::bestFriends);
        MenuSuite.test(s, "953-menu-sweep-accounts", ScreenSweepCases::accounts);
        MenuSuite.test(s, "954-menu-sweep-popups", ScreenSweepCases::popups);
        MenuSuite.test(s, "955-menu-sweep-petwheel", ScreenSweepCases::petWheel);
        MenuSuite.test(s, "956-menu-sweep-screen-theme", ScreenSweepCases::screenTheme);
        MenuSuite.test(s, "957-menu-sweep-search-hint", ScreenSweepCases::searchHint);
    }

    // ---- shared ---------------------------------------------------------------------------------------------------

    static final String[] THEMES = {"AMBER", "LIGHT"};

    /** Window, Auto Scale off, menu theme and every screen's own Theme put back on close. */
    static final class SweepEnv implements AutoCloseable {
        final Session c;
        final SocialCases.Env env;
        final String menuTheme;

        SweepEnv(Session c) {
            this.c = c;
            env = new SocialCases.Env(c);
            menuTheme = c.onClient(mc -> ((Enum<?>) Mod.call(Mod.cfg("gui.MenuConfig"), "getTheme")).name());
            BazaarV3Cases.setWindow(c, 1920, 1080, 2);
        }

        @Override
        public void close() {
            try {
                c.ctx().runOnClient(mc -> {
                    McCompat.setScreen(mc, null);
                    Object cfg = Mod.cfg("gui.MenuConfig");
                    Mod.call(cfg, "setTheme", Mod.enumValue("gui.MenuTheme", menuTheme));
                    for (String id : new String[]{"profileviewer", "friendslist", "bestfriends", "accounts", "autoupdate",
                            "windowlayout", "petwheel"}) {
                        Mod.staticCall("gui.ScreenThemes", "set", id, "DEFAULT");
                    }
                    Mod.staticCall("gui.ScreenThemes", "save");
                });
            } finally {
                env.close();
            }
        }
    }

    static void menuTheme(Session c, String theme) {
        c.ctx().runOnClient(mc -> Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", Mod.enumValue("gui.MenuTheme", theme)));
        c.ctx().waitTicks(2);
    }

    static Screen show(Session c, java.util.function.Function<Minecraft, Screen> make) {
        Screen s = c.onClient(mc -> {
            Screen screen = make.apply(mc);
            McCompat.setScreen(mc, screen);
            return screen;
        });
        c.ctx().waitTicks(4);
        Screen now = SocialCases.current(c);
        c.check(now == s, s.getClass().getSimpleName() + " did not open (open: " + now + ")");
        return s;
    }

    static Screen construct(String cls, Class<?>[] types, Object... args) {
        try {
            var k = Mod.cls(cls).getDeclaredConstructor(types);
            k.setAccessible(true);
            return (Screen) k.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot construct " + cls + ": " + e, e);
        }
    }

    static int[] bounds(Session c, Screen s) {
        return c.onClient(mc -> (int[]) Mod.call(s, "themedPanelBounds"));
    }

    static float lum(int c) {
        return (0.299f * ((c >> 16) & 0xFF) + 0.587f * ((c >> 8) & 0xFF) + 0.114f * (c & 0xFF)) / 255f;
    }

    static boolean near(int a, int b, int tol) {
        for (int sh = 0; sh < 24; sh += 8) {
            if (Math.abs(((a >> sh) & 0xFF) - ((b >> sh) & 0xFF)) > tol) {
                return false;
            }
        }
        return true;
    }

    static String hex(int c) {
        return String.format("#%06X", c & 0xFFFFFF);
    }

    /** A screenshot kept in the report and, when TESTKIT_SWEEP_SHOTS is set, copied as "<dir>/<screen>/<screen> - <view>.png". */
    static Path shot(Session c, String screenName, String view) {
        c.ctx().waitTicks(4);
        String name = c.name() + "-" + screenName.toLowerCase(Locale.ROOT).replace(' ', '-') + "-"
                + view.toLowerCase(Locale.ROOT).replace(' ', '-');
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        Path kept = dev.testkit.harness.Report.screenshot(name, taken);
        String dir = System.getenv("TESTKIT_SWEEP_SHOTS");
        String copied = "";
        if (dir != null && !dir.isBlank()) {
            try {
                Path d = Path.of(dir).resolve(screenName);
                Files.createDirectories(d);
                Path to = d.resolve(screenName + " - " + view + ".png");
                Files.copy(taken, to, StandardCopyOption.REPLACE_EXISTING);
                copied = ", copied to " + to;
            } catch (java.io.IOException e) {
                copied = ", copy failed: " + e;
            }
        }
        c.note("screenshot " + screenName + " / " + view + " -> " + (kept != null ? kept : taken) + copied);
        return taken;
    }

    /** Every text run the screen draws in one off-screen extract (mouse off everything), with its shadow flag. */
    static List<Object[]> runs(Minecraft mc, Screen screen) {
        GuiRenderState state = new GuiRenderState();
        GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
        screen.extractRenderStateWithTooltipAndSubtitles(g, -1, -1, 0.5f);
        List<Object[]> out = new ArrayList<>();
        state.forEachText(t -> out.add(new Object[]{TextRuns.string(t), runField(t, "dropShadow"), runField(t, "color")}));
        return out;
    }

    /** A text run's field: public in 26.1.2, private in 26.2, so read reflectively. */
    private static Object runField(net.minecraft.client.renderer.state.gui.GuiTextRenderState t, String name) {
        try {
            java.lang.reflect.Field f = net.minecraft.client.renderer.state.gui.GuiTextRenderState.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(t);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("GuiTextRenderState has no field " + name, e);
        }
    }

    /**
     * Shoots {@code s} in Amber and Light (the menu's theme, the screen on Theme Default) and checks its panel follows:
     * the ring pixel is the palette's FRAME_RING (when {@code ring}), the panel body dark / light, the two themes
     * different, and on Light every text run unshadowed with a dark base colour (item counts excepted).
     * Returns {amber body, light body}.
     */
    static int[] themed(Session c, Screen s, String screenName, boolean ring) throws Exception {
        int[] body = new int[2];
        for (int k = 0; k < THEMES.length; k++) {
            String theme = THEMES[k];
            menuTheme(c, theme);
            c.ctx().getInput().setCursorPos(2, 2);
            Path p = shot(c, screenName, theme.charAt(0) + theme.substring(1).toLowerCase(Locale.ROOT));
            BufferedImage img = javax.imageio.ImageIO.read(p.toFile());
            int[] b = bounds(c, s);
            double sc = img.getWidth() / (double) c.onClient(mc -> s.width);
            int want = c.onClient(mc -> (Integer) Mod.field("gui.MenuStyle", "FRAME_RING"));
            int bx = ring ? b[0] + 3 : b[0] + 3;
            int by = ring ? b[1] + b[3] / 2 : b[1] + b[3] - 4;
            int px = img.getRGB((int) ((bx + 0.5) * sc), (int) ((by + 0.5) * sc)) | 0xFF000000;
            body[k] = px;
            String line = theme + ": panel body " + hex(px) + " (lum " + String.format(Locale.ROOT, "%.2f", lum(px)) + ")";
            if (ring) {
                int rp = img.getRGB((int) ((b[0] + b[2] / 2 + 0.5) * sc), (int) ((b[1] - 1 + 0.5) * sc)) | 0xFF000000;
                line += ", ring " + hex(rp) + " (palette " + hex(want) + ")";
                c.check(near(rp, want, 3), screenName + " " + theme + ": the panel ring is " + hex(rp) + ", the palette says "
                        + hex(want));
            }
            c.note(line);
            if (theme.equals("LIGHT")) {
                c.check(lum(px) > 0.6f, screenName + " Light: the panel body " + hex(px) + " is not light");
                List<String> bad = c.onClient(mc -> {
                    List<String> out = new ArrayList<>();
                    for (Object[] r : runs(mc, s)) {
                        String str = (String) r[0];
                        if (str.isBlank() || str.matches("\\d+")) {
                            continue; // an item's stack count is vanilla's, white with a shadow on purpose
                        }
                        if ((Boolean) r[1]) {
                            out.add("shadowed '" + str + "'");
                        } else if (lum((Integer) r[2]) > 0.6f) {
                            out.add("bright " + hex((Integer) r[2]) + " '" + str + "'");
                        }
                    }
                    return out;
                });
                c.check(bad.isEmpty(), screenName + " Light: unreadable text " + bad);
            } else {
                c.check(lum(px) < 0.3f, screenName + " Amber: the panel body " + hex(px) + " is not dark");
            }
        }
        c.check(!near(body[0], body[1], 10), screenName + ": Amber and Light drew the same panel " + hex(body[0]));
        return body;
    }

    static AbstractWidget widget(Session c, Screen s, String prefix) {
        return SocialCases.widget(c, s, prefix);
    }

    static void press(Session c, AbstractWidget w) {
        SocialCases.pressAt(c, SocialCases.rect(w));
    }

    // ---- 950 PV ---------------------------------------------------------------------------------------------------

    static void pv(Session c) throws Exception {
        String portProp = System.getProperty("testkit.fakeHttp.port");
        c.check(portProp != null, "no testkit.fakeHttp.port: build.gradle must point pv-backend at the fake");
        Path fixtures = dev.testkit.gametest.Fixtures.root().getParent().resolve("testkit-http").resolve("partyfinder-stats");
        PartyFinderStatsCases.Player ants = PartyFinderStatsCases.REAL.get(0);
        UUID id = UUID.fromString(ants.uuid().replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
        try (SweepEnv env = new SweepEnv(c);
             PartyFinderStatsCases.Fake fake = new PartyFinderStatsCases.Fake(Integer.parseInt(portProp), fixtures)) {
            c.ctx().runOnClient(mc -> {
                ((java.util.Map<?, ?>) Mod.field("profileviewer.api.ProfileViewerApi", "CACHE")).remove(id);
                Object cfg = Mod.cfg("profileviewer.ProfileViewerConfig");
                Mod.call(cfg, "setRememberLastPage", false);
            });
            Screen s = show(c, mc -> {
                try {
                    Object target = Mod.cls("profileviewer.ProfileViewerFeature$Target")
                            .getDeclaredConstructor(String.class, UUID.class).newInstance(ants.name(), id);
                    return construct("profileviewer.screen.ProfileViewerScreen",
                            new Class<?>[]{Screen.class, target.getClass()}, null, target);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            c.waitUntil("the PV loads AntsRNG from the fake backend",
                    mc -> !"LOADING".equals(String.valueOf(Mod.field(s, "state"))), 300);
            String state = c.onClient(mc -> String.valueOf(Mod.field(s, "state")));
            c.note("state " + state + "; fake hits " + new java.util.TreeMap<>(fake.hits));
            c.check("READY".equals(state), "the PV did not load the fixture: state " + state + ", status "
                    + c.onClient(mc -> Mod.field(s, "status")));
            c.check(fake.count("/pv/profiles/" + ants.uuid()) + fake.count("/pv/profiles/" + id) >= 1,
                    "the PV never asked the fake backend for the profile: " + fake.hits);
            themed(c, s, "Profile Viewer", true);
            // A real press on the Dungeons tab (second tab of the first row).
            int[] tab = c.onClient(mc -> {
                @SuppressWarnings("unchecked")
                List<int[]> rects = (List<int[]>) Mod.field(s, "tabRects");
                int[] r = rects.get(1);
                int px = (Integer) Mod.field(s, "panelX");
                int py = (Integer) Mod.field(s, "panelY");
                return new int[]{px + r[0], py + 28 + r[1] * 21, r[2], 18};
            });
            menuTheme(c, "AMBER");
            SocialCases.pressAt(c, tab);
            int page = c.onClient(mc -> (Integer) Mod.field(s, "page"));
            c.check(page == 1, "a press on the Dungeons tab left the PV on page " + page);
            c.note("a real press on the Dungeons tab opened page " + page);
            for (String theme : THEMES) {
                menuTheme(c, theme);
                shot(c, "Profile Viewer", "Dungeons " + theme.charAt(0) + theme.substring(1).toLowerCase(Locale.ROOT));
            }
        }
    }

    // ---- 951 Friends List -----------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static List<String> visibleNames(Session c, Screen s) {
        return c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            for (Object f : (List<Object>) Mod.field(s, "visible")) {
                out.add((String) Mod.field(f, "name"));
            }
            return out;
        });
    }

    static void friends(Session c) throws Exception {
        try (SweepEnv env = new SweepEnv(c)) {
            SocialCases.seedFriends(c, 30);
            Screen s = SocialCases.open(c, "social.FriendsListScreen");
            themed(c, s, "Friends List", true);
            menuTheme(c, "AMBER");
            boolean rowDrawn = c.onClient(mc -> runs(mc, s).stream().anyMatch(r -> ((String) r[0]).startsWith("HxPal01")));
            c.check(rowDrawn, "no row for HxPal01 drawn");
            // Typing on the list with nothing focused narrows it (killer560's to-do 2026-10-13).
            c.ctx().getInput().typeChars("al1");
            c.ctx().waitTicks(3);
            List<String> names = visibleNames(c, s);
            c.note("after typing 'al1': " + names.size() + " shown " + names);
            c.check(names.size() == 10 && names.stream().allMatch(n -> n.toLowerCase(Locale.ROOT).contains("al1")),
                    "typing 'al1' shows " + names);
            boolean drawnOnly = c.onClient(mc -> {
                List<Object[]> r = runs(mc, s);
                return r.stream().anyMatch(x -> ((String) x[0]).startsWith("HxPal10"))
                        && r.stream().noneMatch(x -> ((String) x[0]).startsWith("HxPal02"));
            });
            c.check(drawnOnly, "the drawn rows do not follow the search (HxPal10 shown, HxPal02 hidden)");
            for (String theme : THEMES) {
                menuTheme(c, theme);
                shot(c, "Friends List", "Search " + theme.charAt(0) + theme.substring(1).toLowerCase(Locale.ROOT));
            }
            menuTheme(c, "AMBER");
            // Clearing the box shows everyone again.
            c.ctx().runOnClient(mc -> ((net.minecraft.client.gui.components.EditBox) Mod.field(s, "searchBox")).setValue(""));
            c.ctx().waitTicks(2);
            int all = visibleNames(c, s).size();
            c.check(all == 30, "after clearing the search " + all + " friends show, not 30");
            c.note("cleared: all " + all + " shown");
            // A real press on the first row opens it.
            int[][] r = c.onClient(mc -> SocialCases.layoutRects(s));
            SocialCases.pressAt(c, new int[]{r[0][0] + 30, r[0][1] + 2, 60, 18});
            Screen d = SocialCases.current(c);
            c.check(c.onClient(mc -> Mod.field(d, "selected")) != null, "a press on a row did not open it");
            for (String theme : THEMES) {
                menuTheme(c, theme);
                shot(c, "Friends List", "Detail " + theme.charAt(0) + theme.substring(1).toLowerCase(Locale.ROOT));
            }
        }
    }

    // ---- 952 Best Friends -----------------------------------------------------------------------------------------

    static void bestFriends(Session c) throws Exception {
        UUID one = UUID.fromString("5a1c0de0-0000-4000-8000-000000000952");
        UUID two = UUID.fromString("5a1c0de0-0000-4000-8000-000000000953");
        try (SweepEnv env = new SweepEnv(c)) {
            env.env.cfg.set("social.BestFriendsConfig", "DungeonOnlyFilter", false);
            c.ctx().runOnClient(mc -> {
                Mod.call(Mod.cfg("social.BestFriendsConfig"), "setEnabled", true);
                dev.testkit.gametest.hx.HxKit.setInstanceField(
                        Mod.staticCall("social.BestFriendsStore", "getOrCreate", one, "HxSweepMate"), "totalPartyMs", 5_520_000L);
                dev.testkit.gametest.hx.HxKit.setInstanceField(
                        Mod.staticCall("social.BestFriendsStore", "getOrCreate", two, "HxOtherPal"), "totalPartyMs", 60_000L);
            });
            Screen s = SocialCases.open(c, "social.BestFriendsScreen");
            themed(c, s, "Best Friends", true);
            menuTheme(c, "AMBER");
            c.ctx().getInput().typeChars("sweep");
            c.ctx().waitTicks(3);
            @SuppressWarnings("unchecked")
            List<String> names = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                for (Object r : (List<Object>) Mod.field(s, "visible")) {
                    out.add((String) Mod.field(r, "lastKnownName"));
                }
                return out;
            });
            c.note("after typing 'sweep': " + names);
            c.check(names.equals(List.of("HxSweepMate")), "typing 'sweep' shows " + names);
            for (String theme : THEMES) {
                menuTheme(c, theme);
                shot(c, "Best Friends", "Search " + theme.charAt(0) + theme.substring(1).toLowerCase(Locale.ROOT));
            }
        }
    }

    // ---- 953 Swap Accounts ----------------------------------------------------------------------------------------

    static void accounts(Session c) throws Exception {
        Path file = Path.of(System.getProperty("prismaccountswitcher.accountsFile"));
        String before = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        StringBuilder json = new StringBuilder("{\"accounts\":[");
        String[] names = {"HxAltOne", "HxAltTwo", "HxAltThree"};
        for (int i = 0; i < names.length; i++) {
            json.append(i == 0 ? "" : ",").append("{\"type\":\"MSA\",\"msa-client-id\":\"testkit-client\",")
                    .append("\"msa\":{\"refresh_token\":\"testkit-not-a-token\"},")
                    .append("\"profile\":{\"id\":\"0000000000004000800000000000095").append(i).append("\",\"name\":\"")
                    .append(names[i]).append("\"}}");
        }
        json.append("]}");
        try (SweepEnv env = new SweepEnv(c)) {
            Files.writeString(file, json.toString(), StandardCharsets.UTF_8);
            Screen s = show(c, mc -> construct("accounts.gui.AccountSwitcherScreen", new Class<?>[]{Screen.class}, (Object) null));
            for (String n : names) {
                c.check(widget(c, s, n) != null, "no button for the account " + n);
            }
            c.note("the three fake accounts list as buttons");
            themed(c, s, "Swap Accounts", true);
            Screen direct = show(c, mc -> construct("accounts.gui.DirectSessionLoginScreen",
                    new Class<?>[]{Screen.class, Screen.class}, s, null));
            themed(c, direct, "Direct Session Login", true);
            Object account = c.onClient(mc -> {
                try {
                    return Mod.cls("accounts.core.PrismAccount").getDeclaredConstructor(String.class, String.class,
                            String.class, String.class).newInstance("00000000000040008000000000000950", "HxAltOne",
                            "testkit-client", "testkit-not-a-token");
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            Screen accProxy = show(c, mc -> construct("accounts.gui.AccountProxyConfigScreen",
                    new Class<?>[]{Screen.class, account.getClass()}, s, account));
            themed(c, accProxy, "Account Proxy", true);
            Screen proxy = show(c, mc -> construct("proxy.gui.ProxyConfigScreen", new Class<?>[]{Screen.class}, s));
            themed(c, proxy, "Universal Proxy", true);
            // Back on the list: a real press on Back closes it.
            Screen list = show(c, mc -> construct("accounts.gui.AccountSwitcherScreen", new Class<?>[]{Screen.class}, (Object) null));
            menuTheme(c, "AMBER");
            press(c, widget(c, list, "Back"));
            Screen now = SocialCases.current(c);
            c.check(now == null, "a press on Back left " + now + " open");
            c.note("a real press on Back closed the list");
        } finally {
            if (before == null) {
                Files.deleteIfExists(file);
            } else {
                Files.writeString(file, before, StandardCharsets.UTF_8);
            }
        }
    }

    // ---- 954 popups -----------------------------------------------------------------------------------------------

    static void popups(Session c) throws Exception {
        try (SweepEnv env = new SweepEnv(c)) {
            String notes = "## What's new\n- **Screens** now follow the menu theme\n- The Friends List narrows as you type\n\n"
                    + "Fixes: the search hint no longer smears on Light.";
            Screen up = show(c, mc -> construct("autoupdate.UpdatedScreen",
                    new Class<?>[]{Screen.class, String.class, String.class, String.class}, null, "abc1234", "dev-build", notes));
            themed(c, up, "Updated Popup", true);
            menuTheme(c, "AMBER");
            press(c, widget(c, up, "OK"));
            c.check(SocialCases.current(c) == null, "OK did not close the popup");
            c.note("a real press on OK closed the popup");

            Object wl = c.onClient(mc -> Mod.cfg("windowlayout.WindowLayoutConfig"));
            int count = c.onClient(mc -> ((Number) Mod.call(wl, "getWindowsPerMonitor")).intValue());
            try {
                Screen w = show(c, mc -> construct("windowlayout.WindowLayoutScreen", new Class<?>[]{Screen.class}, (Object) null));
                themed(c, w, "Window Layout", true);
                menuTheme(c, "AMBER");
                press(c, widget(c, w, "Windows:"));
                int after = c.onClient(mc -> ((Number) Mod.call(wl, "getWindowsPerMonitor")).intValue());
                c.check(after != count, "a press on Windows: left the count at " + after);
                c.note("Windows: stepped " + count + " -> " + after);
                shot(c, "Window Layout", "Stepped Amber");
                press(c, widget(c, w, "Cancel"));
                c.check(SocialCases.current(c) == null, "Cancel did not close Window Layout");
            } finally {
                c.ctx().runOnClient(mc -> {
                    Mod.call(wl, "setWindowsPerMonitor", count);
                    Mod.call(wl, "save");
                });
            }
        }
    }

    // ---- 955 pet wheel --------------------------------------------------------------------------------------------

    static void petWheel(Session c) throws Exception {
        try (SweepEnv env = new SweepEnv(c)) {
            Screen s = show(c, mc -> {
                try {
                    Class<?> entry = Mod.cls("petwheel.PetEntry");
                    var k = entry.getDeclaredConstructor(String.class, String.class, String.class, int.class, String.class);
                    List<Object> pets = new ArrayList<>();
                    String[][] p = {{"Golden Dragon", "LEGENDARY"}, {"Spirit", "LEGENDARY"}, {"Ender Dragon", "EPIC"},
                            {"Black Cat", "MYTHIC"}};
                    for (int i = 0; i < p.length; i++) {
                        pets.add(k.newInstance("tk-sweep-pet-" + i, p[i][0], p[i][1], 100, null));
                    }
                    Object mode = Mod.enumValue("petwheel.PetWheelConfig$InteractionMode", "PRESS_CLICK");
                    return construct("petwheel.PetWheelScreen", new Class<?>[]{List.class, float.class, mode.getClass()},
                            pets, 1.5f, mode);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            themed(c, s, "Pet Wheel", false);
            // Hover the first tile: its outline turns the accent.
            menuTheme(c, "AMBER");
            int[] b = bounds(c, s);
            BazaarReskinCases.cursorTo(c, b);
            Path p = shot(c, "Pet Wheel", "Hover Amber");
            BufferedImage img = javax.imageio.ImageIO.read(p.toFile());
            double sc = img.getWidth() / (double) c.onClient(mc -> s.width);
            int edge = img.getRGB((int) ((b[0] + 0.5) * sc), (int) ((b[1] + b[3] / 2 + 0.5) * sc)) | 0xFF000000;
            int or = c.onClient(mc -> (Integer) Mod.field("gui.MenuStyle", "OR"));
            c.note("hovered tile edge " + hex(edge) + ", accent " + hex(or));
            c.check(near(edge, or, 6), "the hovered tile's edge is " + hex(edge) + ", not the accent " + hex(or));
            c.ctx().getInput().setCursorPos(2, 2);
        }
    }

    // ---- 956 per-screen Theme -------------------------------------------------------------------------------------

    static void screenTheme(Session c) throws Exception {
        try (SweepEnv env = new SweepEnv(c)) {
            SocialCases.seedFriends(c, 10);
            menuTheme(c, "AMBER");
            c.ctx().runOnClient(mc -> Mod.staticCall("gui.ScreenThemes", "set", "friendslist", "LIGHT"));
            Screen s = SocialCases.open(c, "social.FriendsListScreen");
            int[] light = sample(c, s, "Own Light, menu Amber");
            c.check(lum(light[0]) > 0.6f, "Friends List set to Light with the menu on Amber drew " + hex(light[0]));
            c.ctx().runOnClient(mc -> Mod.staticCall("gui.ScreenThemes", "set", "friendslist", "DEFAULT"));
            int[] dflt = sample(c, s, "Theme Default, menu Amber");
            c.check(lum(dflt[0]) < 0.3f, "Friends List back on Theme Default with the menu on Amber drew " + hex(dflt[0]));
            c.check(near(dflt[1], c.onClient(mc -> (Integer) Mod.field("gui.MenuStyle", "FRAME_RING")), 3)
                            && near(dflt[1], amberRing(c), 3),
                    "on Theme Default the ring " + hex(dflt[1]) + " is not the menu's Amber ring");
            c.note("own Light: body " + hex(light[0]) + "; Theme Default: body " + hex(dflt[0]) + ", ring " + hex(dflt[1]));
            SocialCases.closeScreen(c);

            // The tab's button: left click forward, right click back, saved to disk.
            String why = c.onClient(mc -> {
                AbstractWidget b = (AbstractWidget) Mod.staticCall("gui.ScreenThemes", "button", "friendslist", 0, 0, 160, 20,
                        new String[0]);
                String first = b.getMessage().getString();
                Mod.call(b, "press");
                String second = b.getMessage().getString();
                b.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(5, 5,
                        new net.minecraft.client.input.MouseButtonInfo(1, 0)), false);
                String third = b.getMessage().getString();
                if (!first.equals("Theme: Theme Default") || !second.equals("Theme: Amber") || !third.equals(first)) {
                    return "the button read '" + first + "', then '" + second + "' after a left click, '" + third
                            + "' after a right click";
                }
                Mod.call(b, "press");
                Mod.call(b, "press");
                Mod.call(b, "press"); // Amber, Dark, Light
                Mod.staticCall("gui.ScreenThemes", "load");
                Object got = Mod.staticCall("gui.ScreenThemes", "get", "friendslist");
                if (!"LIGHT".equals(got)) {
                    return "after three left clicks and a re-read of the file the choice is " + got + ", not LIGHT";
                }
                return null;
            });
            c.check(why == null, why);
            c.note("Theme button: Theme Default -> Amber -> (right click) Theme Default; Light survived a re-read of "
                    + c.onClient(mc -> Mod.staticCall("gui.ScreenThemes", "path")));
        }
    }

    static int amberRing(Session c) {
        return c.onClient(mc -> {
            Object t = Mod.enumValue("gui.MenuTheme", "AMBER");
            int[] base = (int[]) Mod.call(t, "base");
            int panel = base[1] | 0xFF000000;
            int accent = base[0] | 0xFF000000;
            int r = 0;
            for (int sh = 0; sh < 32; sh += 8) {
                int a = (panel >>> sh) & 0xFF;
                int b = (accent >>> sh) & 0xFF;
                r |= (Math.round(a + (b - a) * 0.25f) & 0xFF) << sh;
            }
            return r;
        });
    }

    /** {body pixel, ring pixel} of a framed screen as drawn now. */
    static int[] sample(Session c, Screen s, String view) throws Exception {
        c.ctx().getInput().setCursorPos(2, 2);
        Path p = shot(c, "Screen Theme", view);
        BufferedImage img = javax.imageio.ImageIO.read(p.toFile());
        int[] b = bounds(c, s);
        double sc = img.getWidth() / (double) c.onClient(mc -> s.width);
        int body = img.getRGB((int) ((b[0] + 3 + 0.5) * sc), (int) ((b[1] + b[3] / 2 + 0.5) * sc)) | 0xFF000000;
        int ring = img.getRGB((int) ((b[0] + b[2] / 2 + 0.5) * sc), (int) ((b[1] - 1 + 0.5) * sc)) | 0xFF000000;
        return new int[]{body, ring};
    }

    // ---- 957 the menu's search hint -------------------------------------------------------------------------------

    static void searchHint(Session c) throws Exception {
        try (SweepEnv env = new SweepEnv(c)) {
            Screen s = show(c, mc -> construct("gui.ModScreen", new Class<?>[]{Screen.class}, (Object) null));
            for (String theme : THEMES) {
                menuTheme(c, theme);
                Object[] hint = c.onClient(mc -> {
                    for (Object[] r : runs(mc, s)) {
                        if (((String) r[0]).startsWith("Search features")) {
                            return r;
                        }
                    }
                    return null;
                });
                c.check(hint != null, theme + ": the search hint is not drawn");
                if (hint != null) {
                    c.note(theme + ": hint '" + hint[0] + "' shadow " + hint[1] + " color " + hex((Integer) hint[2]));
                    c.check(!(Boolean) hint[1] || theme.equals("AMBER"), theme + ": the search hint is drawn with a shadow");
                    if (theme.equals("LIGHT")) {
                        c.check(lum((Integer) hint[2]) < 0.6f, "Light: the search hint color " + hex((Integer) hint[2])
                                + " is too light");
                    }
                }
                shot(c, "Mod Menu Search", theme.charAt(0) + theme.substring(1).toLowerCase(Locale.ROOT));
            }
            // Focused: the hint goes, as vanilla's did.
            c.ctx().runOnClient(mc -> {
                net.minecraft.client.gui.components.EditBox f = (net.minecraft.client.gui.components.EditBox) Mod.field(s, "searchField");
                s.setFocused(f);
                f.setFocused(true);
            });
            boolean gone = c.onClient(mc -> runs(mc, s).stream().noneMatch(r -> ((String) r[0]).startsWith("Search features")));
            c.check(gone, "the search hint still shows with the box focused");
        }
    }

    @SuppressWarnings("unused")
    private static List<AbstractWidget> widgets(Screen s) {
        return Frames.widgets(s);
    }
}

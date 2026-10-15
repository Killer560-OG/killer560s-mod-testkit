package dev.testkit.gametest.menu;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.ui.Frames;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * killer560 (2026-10-15, swap-accounts-ui): "please redo how this looks. Also change set instance proxy to be called set
 * universal proxy." The Swap Accounts screen (title screen, Swap Accounts) with 14 fake Prism accounts, the third one
 * being the account the client is signed in as and the fourth carrying its own proxy.
 * <ul>
 *   <li>1850-menu-swap-accounts-layout: shots in Amber / Dark / Light / Ocean and at the small window; every account row
 *       spans the list's width, its Copy ID and proxy buttons sit at the row's right end, no two controls overlap and
 *       every control is inside the panel (big window and small), the logged-in account says "Active" in its own row,
 *       the proxied account says "Own proxy" and an unproxied one "No proxy", the footer says "Set Universal Proxy" and
 *       nothing says "Instance Proxy", a search box exists, typing narrows the rows, and the wheel scrolls the last
 *       account into the list.</li>
 *   <li>1851-menu-swap-accounts-buttons: each control's job by real presses: a row starts a sign-in as THAT account,
 *       Copy ID fetches THAT account's session (both fail offline and say whose), the row's proxy button opens that
 *       account's proxy page, Set Universal Proxy opens the proxy page titled Universal Proxy, the Universal switch
 *       refuses with no proxy set ("Set Universal Proxy first"), turns on and off with one (only against the test
 *       copy of the shared universal file - never the real one), Direct Session Login opens its page, Back closes.</li>
 * </ul>
 * Shots also go to {@code TESTKIT_SWEEP_SHOTS}/Swap Accounts/ when that is set.
 */
final class SwapAccountsCases {

    private SwapAccountsCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1850-menu-swap-accounts-layout", SwapAccountsCases::layout);
        MenuSuite.test(s, "1851-menu-swap-accounts-buttons", SwapAccountsCases::buttons);
    }

    static final String[] NAMES = {"HxSwapAlpha", "HxSwapBravo", "HxSwapActive", "HxSwapProxied", "HxSwapEcho",
            "HxSwapFoxtrot", "HxSwapGolf", "HxSwapHotel", "HxSwapIndia", "HxSwapJuliet", "HxSwapKilo", "HxSwapLima",
            "HxSwapMike", "HxSwapLast"};
    static final int ACTIVE = 2;
    static final int PROXIED = 3;

    static String uuidOf(int i) {
        return String.format(Locale.ROOT, "5a1c0de000004000800000000018%04d", i);
    }

    /** The fake accounts file, the proxied account's own proxy, put back on close. */
    static final class Accounts implements AutoCloseable {
        final Session c;
        final Path file;
        final String before;
        final String activeUuid;

        Accounts(Session c) throws Exception {
            this.c = c;
            file = Path.of(System.getProperty("prismaccountswitcher.accountsFile"));
            before = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
            activeUuid = c.onClient(mc -> mc.getUser().getProfileId().toString().replace("-", ""));
            StringBuilder json = new StringBuilder("{\"accounts\":[");
            for (int i = 0; i < NAMES.length; i++) {
                String id = i == ACTIVE ? activeUuid : uuidOf(i);
                json.append(i == 0 ? "" : ",").append("{\"type\":\"MSA\",\"msa-client-id\":\"testkit-client\",")
                        .append("\"msa\":{\"refresh_token\":\"testkit-not-a-token-").append(i).append("\"},")
                        .append("\"profile\":{\"id\":\"").append(id).append("\",\"name\":\"").append(NAMES[i]).append("\"}}");
            }
            json.append("]}");
            Files.writeString(file, json.toString(), StandardCharsets.UTF_8);
            c.ctx().runOnClient(mc -> {
                try {
                    Object profile = Mod.cls("proxy.config.AccountProxyProfile").getDeclaredConstructor().newInstance();
                    Mod.call(profile, "parseAndSetAddress", "127.0.0.1:1");
                    Mod.staticCall("proxy.config.AccountProxyStore", "set", uuidOf(PROXIED), profile);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
        }

        @Override
        public void close() throws Exception {
            c.ctx().runOnClient(mc -> Mod.staticCall("proxy.config.AccountProxyStore", "remove", uuidOf(PROXIED)));
            if (before == null) {
                Files.deleteIfExists(file);
            } else {
                Files.writeString(file, before, StandardCharsets.UTF_8);
            }
        }
    }

    static Screen open(Session c) {
        return ScreenSweepCases.show(c, mc -> ScreenSweepCases.construct("accounts.gui.AccountSwitcherScreen",
                new Class<?>[]{Screen.class}, (Object) null));
    }

    static List<AbstractWidget> widgets(Session c, Screen s) {
        return c.onClient(mc -> new ArrayList<>(Frames.widgets(s)));
    }

    /** The account's own row: the widget whose label is exactly the name (the old screen's account button too). */
    static AbstractWidget row(Session c, Screen s, String name) {
        return c.onClient(mc -> {
            for (AbstractWidget w : Frames.widgets(s)) {
                if (SocialCases.plain(w).equals(name)) {
                    return w;
                }
            }
            return null;
        });
    }

    /** A widget whose label starts with {@code prefix} and that sits in {@code row}'s band (its vertical middle inside it). */
    static AbstractWidget inRow(Session c, Screen s, AbstractWidget row, String prefix) {
        return c.onClient(mc -> {
            for (AbstractWidget w : Frames.widgets(s)) {
                int mid = w.getY() + w.getHeight() / 2;
                if (w != row && SocialCases.plain(w).startsWith(prefix) && mid >= row.getY()
                        && mid < row.getY() + row.getHeight()) {
                    return w;
                }
            }
            return null;
        });
    }

    /** The list's box: listX/listY/listW/listH (fields the old screen has as well). */
    static int[] list(Session c, Screen s) {
        return c.onClient(mc -> new int[]{SocialCases.intField(s, "listX"), SocialCases.intField(s, "listY"),
                SocialCases.intField(s, "listW"), SocialCases.intField(s, "listH")});
    }

    /** Visible widgets that overlap each other, or leave the panel or the screen. */
    static List<String> layoutProblems(Session c, Screen s, String tag) {
        int[] panel = ScreenSweepCases.bounds(c, s);
        return c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            List<AbstractWidget> ws = new ArrayList<>();
            for (AbstractWidget w : Frames.widgets(s)) {
                if (w.visible) {
                    ws.add(w);
                }
            }
            for (int i = 0; i < ws.size(); i++) {
                int[] a = SocialCases.rect(ws.get(i));
                if (a[0] < panel[0] || a[1] < panel[1] || a[0] + a[2] > panel[0] + panel[2] || a[1] + a[3] > panel[1] + panel[3]) {
                    out.add(tag + ": '" + SocialCases.plain(ws.get(i)) + "' " + SocialCases.str(a) + " leaves the panel "
                            + SocialCases.str(panel));
                }
                if (a[0] < 0 || a[1] < 0 || a[0] + a[2] > s.width || a[1] + a[3] > s.height) {
                    out.add(tag + ": '" + SocialCases.plain(ws.get(i)) + "' " + SocialCases.str(a) + " leaves the screen");
                }
                for (int j = i + 1; j < ws.size(); j++) {
                    int[] b = SocialCases.rect(ws.get(j));
                    boolean rowAndItsButton = (a[3] > b[3] + 4 && b[0] >= a[0] && b[0] + b[2] <= a[0] + a[2])
                            || (b[3] > a[3] + 4 && a[0] >= b[0] && a[0] + a[2] <= b[0] + b[2]);
                    if (SocialCases.overlap(a, b) && !rowAndItsButton) {
                        out.add(tag + ": '" + SocialCases.plain(ws.get(i)) + "' " + SocialCases.str(a) + " overlaps '"
                                + SocialCases.plain(ws.get(j)) + "' " + SocialCases.str(b));
                    }
                }
            }
            return out;
        });
    }

    /** Drawn texts (plain) inside a box. */
    static List<String> textsIn(Session c, Screen s, int[] box) {
        return c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            for (SocialCases.Txt t : SocialCases.texts(mc, s)) {
                if (t.intersects(box)) {
                    out.add(t.s().replaceAll("§.", ""));
                }
            }
            return out;
        });
    }

    static List<String> allTexts(Session c, Screen s) {
        return c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            for (SocialCases.Txt t : SocialCases.texts(mc, s)) {
                out.add(t.s().replaceAll("§.", ""));
            }
            return out;
        });
    }

    /** The names whose row exists and is visible. */
    static List<String> visibleRows(Session c, Screen s) {
        return c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            for (AbstractWidget w : Frames.widgets(s)) {
                String p = SocialCases.plain(w);
                if (w.visible && p.startsWith("HxSwap")) {
                    out.add(p);
                }
            }
            return out;
        });
    }

    // ---- 1850 layout ------------------------------------------------------------------------------------------------

    static void layout(Session c) throws Exception {
        List<String> fails = new ArrayList<>();
        try (ScreenSweepCases.SweepEnv env = new ScreenSweepCases.SweepEnv(c); Accounts acc = new Accounts(c)) {
            Screen s = open(c);
            // Shots first, so a failing jar still leaves its picture.
            for (String theme : new String[]{"AMBER", "DARK", "LIGHT", "OCEAN"}) {
                ScreenSweepCases.menuTheme(c, theme);
                c.ctx().getInput().setCursorPos(2, 2);
                ScreenSweepCases.shot(c, "Swap Accounts", theme.charAt(0) + theme.substring(1).toLowerCase(Locale.ROOT));
            }
            ScreenSweepCases.menuTheme(c, "AMBER");
            AbstractWidget hoverRow = row(c, s, NAMES[0]);
            if (hoverRow != null) {
                BazaarReskinCases.cursorTo(c, new int[]{hoverRow.getX() + 40, hoverRow.getY() + 2, 20, 4});
                ScreenSweepCases.shot(c, "Swap Accounts", "Hover");
                c.ctx().getInput().setCursorPos(2, 2);
            }

            ScreenSweepCases.themed(c, s, "Swap Accounts", true);
            ScreenSweepCases.menuTheme(c, "AMBER");

            int[] list = list(c, s);
            int[] panel = ScreenSweepCases.bounds(c, s);
            c.note("panel " + SocialCases.str(panel) + ", list " + SocialCases.str(list) + ", screen "
                    + c.onClient(mc -> s.width + "x" + s.height));
            int shown = 0;
            for (String n : NAMES) {
                AbstractWidget r = row(c, s, n);
                if (r == null) {
                    fails.add("no widget for the account " + n);
                    continue;
                }
                if (!r.visible) {
                    continue;
                }
                shown++;
                if (r.getWidth() < list[2] * 0.85) {
                    fails.add(n + ": the row is " + r.getWidth() + " wide in a " + list[2] + "-wide list (under 85%)");
                }
                AbstractWidget copy = inRow(c, s, r, "Copy ID");
                AbstractWidget proxy = inRow(c, s, r, "Set Proxy");
                if (proxy == null) {
                    proxy = inRow(c, s, r, "Edit Proxy");
                }
                if (copy == null || proxy == null) {
                    fails.add(n + ": Copy ID " + copy + ", proxy button " + proxy + " not both in its row");
                    continue;
                }
                int rowRight = r.getX() + r.getWidth();
                int rightmost = Math.max(copy.getX() + copy.getWidth(), proxy.getX() + proxy.getWidth());
                if (rowRight - rightmost > 16) {
                    fails.add(n + ": its buttons end at x " + rightmost + ", " + (rowRight - rightmost)
                            + " short of the row's right end " + rowRight);
                }
            }
            c.note(shown + " rows visible at " + c.onClient(mc -> s.width + "x" + s.height));
            if (shown < 5) {
                fails.add("only " + shown + " rows visible in a " + list[3] + "-tall list");
            }

            AbstractWidget active = row(c, s, NAMES[ACTIVE]);
            if (active != null) {
                List<String> t = textsIn(c, s, SocialCases.rect(active));
                c.note("active row texts " + t);
                if (t.stream().noneMatch(x -> x.equals("Active"))) {
                    fails.add("the signed-in account's row does not say Active: " + t);
                }
            }
            long activeCount = allTexts(c, s).stream().filter(x -> x.equals("Active")).count();
            if (activeCount != 1) {
                fails.add("'Active' is drawn " + activeCount + " times, not once");
            }
            AbstractWidget proxied = row(c, s, NAMES[PROXIED]);
            if (proxied != null) {
                List<String> t = textsIn(c, s, SocialCases.rect(proxied));
                c.note("proxied row texts " + t);
                if (t.stream().noneMatch(x -> x.startsWith("Own proxy"))) {
                    fails.add("the proxied account's row does not say Own proxy: " + t);
                }
            }
            AbstractWidget plainRow = row(c, s, NAMES[0]);
            if (plainRow != null) {
                List<String> t = textsIn(c, s, SocialCases.rect(plainRow));
                if (t.stream().noneMatch(x -> x.equals("No proxy"))) {
                    fails.add("an unproxied account's row does not say No proxy: " + t);
                }
            }

            if (ScreenSweepCases.widget(c, s, "Set Universal Proxy") == null) {
                fails.add("no Set Universal Proxy button");
            }
            List<String> instance = new ArrayList<>();
            for (AbstractWidget w : widgets(c, s)) {
                if (SocialCases.plain(w).contains("Instance Proxy")) {
                    instance.add(SocialCases.plain(w));
                }
            }
            allTexts(c, s).stream().filter(x -> x.contains("Instance Proxy")).forEach(instance::add);
            if (!instance.isEmpty()) {
                fails.add("still says Instance Proxy: " + instance);
            }
            fails.addAll(layoutProblems(c, s, "960x540"));

            // The wheel brings the last account into the list.
            BazaarReskinCases.cursorTo(c, new int[]{list[0] + 20, list[1] + list[3] / 2, 4, 4});
            for (int k = 0; k < 8; k++) {
                c.ctx().getInput().scroll(-3.0);
            }
            c.ctx().waitTicks(10);
            AbstractWidget last = row(c, s, NAMES[NAMES.length - 1]);
            boolean lastIn = last != null && c.onClient(mc -> last.visible && last.getY() >= list[1]
                    && last.getY() + last.getHeight() <= list[1] + list[3] + 1);
            c.note("after scrolling down, the last row " + (last == null ? "missing" : SocialCases.str(SocialCases.rect(last))
                    + " visible " + last.visible));
            if (!lastIn) {
                fails.add("scrolling down did not bring " + NAMES[NAMES.length - 1] + " fully into the list");
            }
            ScreenSweepCases.shot(c, "Swap Accounts", "Scrolled");

            // Typing narrows the list (14 accounts: a search box is there).
            EditBox search = null;
            for (AbstractWidget w : widgets(c, s)) {
                if (w instanceof EditBox e) {
                    search = e;
                }
            }
            if (search == null) {
                fails.add("no search box with " + NAMES.length + " accounts");
            } else {
                c.ctx().getInput().setCursorPos(2, 2);
                c.ctx().getInput().typeChars("lima");
                c.ctx().waitTicks(4);
                List<String> rows = visibleRows(c, s);
                c.note("after typing 'lima': " + rows);
                if (!rows.equals(List.of("HxSwapLima"))) {
                    fails.add("typing 'lima' shows " + rows);
                }
                ScreenSweepCases.shot(c, "Swap Accounts", "Search");
                EditBox box = search;
                c.ctx().runOnClient(mc -> box.setValue(""));
                c.ctx().waitTicks(3);
                int back = c.onClient(mc -> {
                    int n = 0;
                    for (AbstractWidget w : Frames.widgets(s)) {
                        if (SocialCases.plain(w).startsWith("HxSwap")) {
                            n++;
                        }
                    }
                    return n;
                });
                if (back != NAMES.length) {
                    fails.add("after clearing the search " + back + " rows exist, not " + NAMES.length);
                }
            }

            // Small window and big GUI scales: still inside the panel, nothing overlapping, a row and the footer shown.
            int[][] sizes = {{854, 480, 0}, {854, 480, 3}, {1280, 720, 4}, {1920, 1080, 4}};
            for (int[] sz : sizes) {
                BazaarV3Cases.setWindow(c, sz[0], sz[1], sz[2]);
                Screen now = SocialCases.current(c);
                String tag = sz[0] + "x" + sz[1] + " gui " + (sz[2] == 0 ? "auto" : sz[2]) + " ("
                        + c.onClient(mc -> now.width + "x" + now.height) + ")";
                c.ctx().getInput().setCursorPos(2, 2);
                ScreenSweepCases.shot(c, "Swap Accounts", "Small " + sz[0] + "x" + sz[1] + " gui " + (sz[2] == 0 ? "auto" : sz[2]));
                fails.addAll(layoutProblems(c, now, tag));
                List<String> rows = visibleRows(c, now);
                c.note(tag + ": rows visible " + rows.size());
                if (rows.isEmpty()) {
                    fails.add(tag + ": no account row visible");
                }
                for (String f : new String[]{"Back", "Direct Session Login", "Set Universal Proxy", "Universal"}) {
                    AbstractWidget w = ScreenSweepCases.widget(c, now, f);
                    if (w == null || !w.visible) {
                        fails.add(tag + ": no visible " + f);
                    }
                }
            }
        }
        for (String f : fails) {
            c.note("FAIL " + f);
        }
        c.check(fails.isEmpty(), fails.size() + " problem(s): " + fails);
    }

    // ---- 1851 buttons -----------------------------------------------------------------------------------------------

    static String status(Session c, Screen s) {
        return c.onClient(mc -> String.valueOf(Mod.field(s, "statusMessage")));
    }

    static void waitIdle(Session c, Screen s) {
        c.waitUntil("the sign-in attempt finishes (offline, so it fails)",
                mc -> !Boolean.TRUE.equals(Mod.field(s, "busy")), 200);
        c.ctx().waitTicks(2);
    }

    /** The universal proxy file the mod writes, and whether the test hook points it into build/ (never the real one). */
    static Path universalFile(Session c) {
        Object p = c.onClient(mc -> Mod.field("proxy.config.ProxyConfig", "UNIVERSAL_PATH"));
        return p instanceof Path path ? path : null;
    }

    static void buttons(Session c) throws Exception {
        List<String> fails = new ArrayList<>();
        try (ScreenSweepCases.SweepEnv env = new ScreenSweepCases.SweepEnv(c); Accounts acc = new Accounts(c)) {
            ScreenSweepCases.menuTheme(c, "AMBER");
            Screen s = open(c);

            // A row press signs in as THAT account (offline: it fails, naming it).
            AbstractWidget bravo = row(c, s, NAMES[1]);
            c.check(bravo != null, "no row for " + NAMES[1]);
            SocialCases.pressAt(c, new int[]{bravo.getX() + 30, bravo.getY() + 2, 30, Math.max(4, bravo.getHeight() - 4)});
            waitIdle(c, s);
            String st = status(c, s);
            c.note("after a press on " + NAMES[1] + "'s row: " + st);
            if (!st.contains("sign in as " + NAMES[1])) {
                fails.add("a press on " + NAMES[1] + "'s row did not try to sign in as it: " + st);
            }
            Screen still = SocialCases.current(c);
            if (still != s) {
                fails.add("the failed sign-in left " + still + " open instead of the list");
            }

            // Copy ID on another row fetches THAT account's session.
            Screen s2 = SocialCases.current(c);
            AbstractWidget echo = row(c, s2, NAMES[4]);
            AbstractWidget copy = echo == null ? null : inRow(c, s2, echo, "Copy ID");
            if (copy == null) {
                fails.add("no Copy ID in " + NAMES[4] + "'s row");
            } else {
                SocialCases.pressAt(c, SocialCases.rect(copy));
                waitIdle(c, s2);
                st = status(c, s2);
                c.note("after Copy ID on " + NAMES[4] + ": " + st);
                if (!st.contains("session ID for " + NAMES[4])) {
                    fails.add("Copy ID on " + NAMES[4] + " did not fetch its session: " + st);
                }
            }

            // The row's proxy button opens that account's proxy page.
            AbstractWidget proxied = row(c, s2, NAMES[PROXIED]);
            AbstractWidget pb = proxied == null ? null : inRow(c, s2, proxied, "Edit Proxy");
            if (pb == null && proxied != null) {
                pb = inRow(c, s2, proxied, "Set Proxy");
                fails.add("the proxied account's button says '" + (pb == null ? null : SocialCases.plain(pb)) + "', not Edit Proxy");
            }
            if (pb == null) {
                fails.add("no proxy button in " + NAMES[PROXIED] + "'s row");
            } else {
                SocialCases.pressAt(c, SocialCases.rect(pb));
                Screen page = SocialCases.current(c);
                String cls = page == null ? "null" : page.getClass().getSimpleName();
                String who = page == null ? null : c.onClient(mc -> {
                    Object a = Mod.field(page, "account");
                    return a == null ? null : (String) Mod.call(a, "displayName");
                });
                c.note("the proxy button opened " + cls + " for " + who);
                if (!"AccountProxyConfigScreen".equals(cls) || !NAMES[PROXIED].equals(who)) {
                    fails.add("the proxy button opened " + cls + " for " + who);
                }
                ScreenSweepCases.shot(c, "Swap Accounts", "Account Proxy");
            }

            // Set Universal Proxy opens the proxy page, titled Universal Proxy.
            Screen list = open(c);
            AbstractWidget setU = ScreenSweepCases.widget(c, list, "Set Universal Proxy");
            if (setU == null) {
                fails.add("no Set Universal Proxy button");
            } else {
                SocialCases.pressAt(c, SocialCases.rect(setU));
                Screen page = SocialCases.current(c);
                String cls = page == null ? "null" : page.getClass().getSimpleName();
                String title = page == null ? null : c.onClient(mc -> page.getTitle().getString());
                c.note("Set Universal Proxy opened " + cls + " '" + title + "'");
                if (!"ProxyConfigScreen".equals(cls) || !"Universal Proxy".equals(title)) {
                    fails.add("Set Universal Proxy opened " + cls + " '" + title + "'");
                }
                ScreenSweepCases.shot(c, "Swap Accounts", "Universal Proxy Page");
            }

            // The Universal switch, only against the test hook's file.
            list = open(c);
            Path uni = universalFile(c);
            String hook = System.getProperty("prismaccountswitcher.universalProxyFile");
            boolean safe = uni != null && hook != null && uni.toAbsolutePath().normalize()
                    .equals(Path.of(hook).toAbsolutePath().normalize());
            c.note("universal proxy file " + uni + " (hook " + hook + ")");
            if (!safe) {
                fails.add("the mod's universal proxy file is " + uni + ", not the test hook's " + hook
                        + " - the Universal switch was NOT pressed (it would write the real shared file)");
            } else {
                Files.deleteIfExists(uni);
                Object[] saved = c.onClient(mc -> {
                    Object inst = Mod.staticCall("proxy.config.ProxyConfig", "getInstance");
                    return new Object[]{Mod.call(inst, "getAddressString"), Mod.call(inst, "isEnabled")};
                });
                try {
                    c.ctx().runOnClient(mc -> {
                        Object inst = Mod.staticCall("proxy.config.ProxyConfig", "getInstance");
                        Mod.call(inst, "parseAndSetAddress", "");
                    });
                    AbstractWidget sw = ScreenSweepCases.widget(c, list, "Universal");
                    if (sw == null) {
                        fails.add("no Universal switch");
                    } else {
                        SocialCases.pressAt(c, SocialCases.rect(sw));
                        String label = c.onClient(mc -> SocialCases.plain(sw));
                        boolean on = c.onClient(mc -> (Boolean) Mod.staticCall("proxy.config.ProxyConfig", "isUniversalActive"));
                        c.note("Universal with no proxy set: '" + label + "', active " + on);
                        if (on || !label.contains("Set Universal Proxy first")) {
                            fails.add("Universal with no proxy set says '" + label + "', active " + on);
                        }
                        ScreenSweepCases.shot(c, "Swap Accounts", "Universal Refused");
                        c.ctx().runOnClient(mc -> {
                            Object inst = Mod.staticCall("proxy.config.ProxyConfig", "getInstance");
                            Mod.call(inst, "parseAndSetAddress", "127.0.0.1:1");
                        });
                        SocialCases.pressAt(c, SocialCases.rect(sw));
                        on = c.onClient(mc -> (Boolean) Mod.staticCall("proxy.config.ProxyConfig", "isUniversalActive"));
                        label = c.onClient(mc -> SocialCases.plain(sw));
                        c.note("Universal with a proxy: '" + label + "', active " + on + ", file " + Files.exists(uni));
                        if (!on || !label.contains("ON")) {
                            fails.add("Universal did not turn on: '" + label + "', active " + on);
                        }
                        Screen nowList = SocialCases.current(c);
                        AbstractWidget plainRow = row(c, nowList, NAMES[0]);
                        if (plainRow != null) {
                            List<String> t = textsIn(c, nowList, SocialCases.rect(plainRow));
                            if (t.stream().noneMatch(x -> x.startsWith("Universal proxy"))) {
                                fails.add("with Universal on, a row does not say Universal proxy: " + t);
                            }
                        }
                        ScreenSweepCases.shot(c, "Swap Accounts", "Universal On");
                        AbstractWidget sw2 = ScreenSweepCases.widget(c, nowList, "Universal");
                        SocialCases.pressAt(c, SocialCases.rect(sw2));
                        on = c.onClient(mc -> (Boolean) Mod.staticCall("proxy.config.ProxyConfig", "isUniversalActive"));
                        label = c.onClient(mc -> SocialCases.plain(sw2));
                        c.note("Universal pressed again: '" + label + "', active " + on);
                        if (on || !label.contains("OFF")) {
                            fails.add("Universal did not turn off: '" + label + "', active " + on);
                        }
                    }
                } finally {
                    c.ctx().runOnClient(mc -> {
                        Object inst = Mod.staticCall("proxy.config.ProxyConfig", "getInstance");
                        Mod.call(inst, "parseAndSetAddress", (String) saved[0]);
                        Mod.call(inst, "setEnabled", (Boolean) saved[1]);
                        Mod.call(inst, "save");
                    });
                    Files.deleteIfExists(uni);
                }
            }

            // Direct Session Login opens its page; Back closes the list.
            list = open(c);
            AbstractWidget direct = ScreenSweepCases.widget(c, list, "Direct Session Login");
            if (direct == null) {
                fails.add("no Direct Session Login button");
            } else {
                SocialCases.pressAt(c, SocialCases.rect(direct));
                Screen page = SocialCases.current(c);
                String cls = page == null ? "null" : page.getClass().getSimpleName();
                c.note("Direct Session Login opened " + cls);
                if (!"DirectSessionLoginScreen".equals(cls)) {
                    fails.add("Direct Session Login opened " + cls);
                }
            }
            list = open(c);
            AbstractWidget back = ScreenSweepCases.widget(c, list, "Back");
            SocialCases.pressAt(c, SocialCases.rect(back));
            Screen after = SocialCases.current(c);
            c.note("after Back: " + after);
            if (after != null) {
                fails.add("Back left " + after + " open");
            }
        }
        for (String f : fails) {
            c.note("FAIL " + f);
        }
        c.check(fails.isEmpty(), fails.size() + " problem(s): " + fails);
    }
}

package dev.testkit.gametest.menu;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.ui.Frames;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * acctswap-1117 (killer560, 2026-11-17): the Swap Accounts screen's Sort button (Default, Name A-Z, Name Z-A, Recently
 * Played, Most Playtime), its Proxy filter (All, With Proxy, No Proxy) and the "Playing as" badge that must fit any name.
 * Fake accounts only (SwapAccountsCases.Accounts), no token anywhere. Playtime and last-played are seeded through the
 * mod's own record call into the test client's sandbox store.
 */
final class AcctSwap1117Cases {

    private AcctSwap1117Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1117-ui-acctswap-sort-filter", AcctSwap1117Cases::sortFilter);
        MenuSuite.test(s, "1117-ui-acctswap-badge", AcctSwap1117Cases::badge);
    }

    private static final String[] NAMES = SwapAccountsCases.NAMES;

    private static AbstractWidget button(Session c, Screen s, String prefix) {
        return c.onClient(mc -> {
            for (AbstractWidget w : Frames.widgets(s)) {
                if (SocialCases.plain(w).startsWith(prefix)) {
                    return w;
                }
            }
            return null;
        });
    }

    /** Presses the button whose label starts with {@code prefix} until it reads {@code label} (at most 6 presses). */
    private static void cycleTo(Session c, Screen s, String prefix, String label) {
        for (int i = 0; i < 6; i++) {
            AbstractWidget b = button(c, s, prefix);
            c.check(b != null, "no button '" + prefix + "'");
            if (SocialCases.plain(b).equals(label)) {
                return;
            }
            SocialCases.pressAt(c, SocialCases.rect(b));
            c.ctx().waitTicks(4);
        }
        AbstractWidget b = button(c, s, prefix);
        c.check(b != null && SocialCases.plain(b).equals(label), "the '" + prefix + "' button never read '" + label + "'");
    }

    /** The shown rows' names, top to bottom. */
    private static List<String> order(Session c, Screen s) {
        List<String> known = Arrays.asList(NAMES);
        List<int[]> ys = new ArrayList<>();
        List<String> names = new ArrayList<>();
        c.onClient(mc -> {
            for (AbstractWidget w : Frames.widgets(s)) {
                String n = SocialCases.plain(w);
                if (known.contains(n)) {
                    ys.add(new int[]{w.getY(), names.size()});
                    names.add(n);
                }
            }
            return null;
        });
        ys.sort(Comparator.comparingInt(a -> a[0]));
        List<String> out = new ArrayList<>();
        for (int[] y : ys) {
            out.add(names.get(y[1]));
        }
        return out;
    }

    private static List<String> byName(List<String> names, boolean reverse) {
        List<String> out = new ArrayList<>(names);
        out.sort(Comparator.comparing(x -> x.toLowerCase(Locale.ROOT)));
        if (reverse) {
            java.util.Collections.reverse(out);
        }
        return out;
    }

    private static void expect(List<String> fails, String what, List<String> want, List<String> got) {
        if (!want.equals(got)) {
            fails.add(what + ": wanted " + want + " but the list reads " + got);
        }
    }

    static void sortFilter(Session c) throws Exception {
        List<String> fails = new ArrayList<>();
        try (ScreenSweepCases.SweepEnv env = new ScreenSweepCases.SweepEnv(c);
             SwapAccountsCases.Accounts acc = new SwapAccountsCases.Accounts(c)) {
            ScreenSweepCases.menuTheme(c, "AMBER");
            BazaarV3Cases.setWindow(c, 1920, 1080, 2);
            long far = System.currentTimeMillis() + 1_000_000_000L;
            // playtime: 5 > 1 > 8; last used: 8 > 1 > 5 (all later than the test client's own, which is only "now")
            long[][] seed = {{5, 9_000_000_000L, far + 1000}, {1, 5_000_000_000L, far + 3000}, {8, 100_000_000L, far + 9000}};
            c.ctx().runOnClient(mc -> {
                for (long[] e : seed) {
                    Mod.staticCall("accounts.AccountActivity", "record", SwapAccountsCases.uuidOf((int) e[0]), e[1], e[2]);
                }
            });
            Screen s = SwapAccountsCases.open(c);
            c.ctx().waitTicks(10);
            List<String> all = Arrays.asList(NAMES);
            cycleTo(c, s, "Sort:", "Sort: Default");
            cycleTo(c, s, "Proxy:", "Proxy: All");
            List<String> def = order(c, s);
            expect(fails, "Default", all, def);

            cycleTo(c, s, "Sort:", "Sort: Name A-Z");
            List<String> az = order(c, s);
            expect(fails, "Name A-Z", byName(all, false), az);
            cycleTo(c, s, "Sort:", "Sort: Name Z-A");
            expect(fails, "Name Z-A", byName(all, true), order(c, s));
            if (az.equals(def)) {
                fails.add("Name A-Z left the list in the default order: the sort did not act");
            }

            // the test client's own account has a "now" entry of its own: leave it out and check the rest
            String self = NAMES[SwapAccountsCases.ACTIVE];
            List<String> rest = new ArrayList<>(byName(all, false));
            for (int i : new int[]{8, 1, 5}) {
                rest.remove(NAMES[i]);
            }
            List<String> wantRecent = new ArrayList<>(List.of(NAMES[8], NAMES[1], NAMES[5]));
            wantRecent.addAll(rest);
            cycleTo(c, s, "Sort:", "Sort: Recently Played");
            List<String> recent = order(c, s);
            expect(fails, "Recently Played", minus(wantRecent, self), minus(recent, self));
            List<String> wantPlay = new ArrayList<>(List.of(NAMES[5], NAMES[1], NAMES[8]));
            wantPlay.addAll(rest);
            cycleTo(c, s, "Sort:", "Sort: Most Playtime");
            List<String> play = order(c, s);
            expect(fails, "Most Playtime", minus(wantPlay, self), minus(play, self));
            c.note("default " + def + "\nrecent " + recent + "\nplaytime " + play);

            // the proxy filter: only HxSwapProxied has an own proxy
            cycleTo(c, s, "Proxy:", "Proxy: With Proxy");
            expect(fails, "With Proxy", List.of(NAMES[SwapAccountsCases.PROXIED]), order(c, s));
            String shown = String.join(" | ", SwapAccountsCases.allTexts(c, s));
            if (!shown.contains("1 of " + NAMES.length + " accounts")) {
                fails.add("With Proxy: the header count does not say '1 of " + NAMES.length + " accounts': " + shown);
            }
            if (!shown.contains("Search 1 account...")) {
                fails.add("With Proxy: the search hint does not follow the filter: " + shown);
            }
            cycleTo(c, s, "Proxy:", "Proxy: No Proxy");
            List<String> noProxy = new ArrayList<>(wantPlay);
            noProxy.remove(NAMES[SwapAccountsCases.PROXIED]);
            expect(fails, "No Proxy (still sorted by playtime)", minus(noProxy, self), minus(order(c, s), self));

            // filter + search together
            EditBox box = c.onClient(mc -> {
                for (AbstractWidget w : Frames.widgets(s)) {
                    if (w instanceof EditBox e) {
                        return e;
                    }
                }
                return null;
            });
            c.check(box != null, "no search box");
            c.ctx().runOnClient(mc -> box.setValue("golf"));
            c.ctx().waitTicks(3);
            expect(fails, "No Proxy + search golf", List.of(NAMES[6]), order(c, s));
            c.ctx().runOnClient(mc -> box.setValue("proxied"));
            c.ctx().waitTicks(3);
            expect(fails, "No Proxy + search proxied", List.of(), order(c, s));
            c.ctx().runOnClient(mc -> box.setValue(""));
            c.ctx().waitTicks(3);

            // the choice is saved: reload from disk, then a new screen shows it on its buttons
            Object sortNow = Mod.staticCall("accounts.AccountViewPrefs", "sort");
            Mod.staticCall("accounts.AccountViewPrefs", "reload");
            Object sortBack = Mod.staticCall("accounts.AccountViewPrefs", "sort");
            Object filterBack = Mod.staticCall("accounts.AccountViewPrefs", "filter");
            c.note("saved " + sortNow + " -> reloaded " + sortBack + " / " + filterBack);
            if (!"PLAYTIME".equals(String.valueOf(sortBack)) || !"NONE".equals(String.valueOf(filterBack))) {
                fails.add("a reload from disk gives sort " + sortBack + " and filter " + filterBack
                        + ", not PLAYTIME and NONE");
            }
            Screen again = SwapAccountsCases.open(c);
            c.ctx().waitTicks(8);
            AbstractWidget sb = button(c, again, "Sort:");
            AbstractWidget fb = button(c, again, "Proxy:");
            if (sb == null || !SocialCases.plain(sb).equals("Sort: Most Playtime")
                    || fb == null || !SocialCases.plain(fb).equals("Proxy: No Proxy")) {
                fails.add("a new screen's buttons read '" + (sb == null ? null : SocialCases.plain(sb)) + "' and '"
                        + (fb == null ? null : SocialCases.plain(fb)) + "'");
            }
            // put the defaults back
            cycleTo(c, again, "Sort:", "Sort: Default");
            cycleTo(c, again, "Proxy:", "Proxy: All");
        }
        c.check(fails.isEmpty(), "Swap Accounts sort/filter: " + fails);
    }

    /** The list without the test client's own account (it has a "now" entry of its own, so its place depends on the timing). */
    private static List<String> minus(List<String> names, String self) {
        List<String> out = new ArrayList<>(names);
        out.remove(self);
        return out;
    }

    static void badge(Session c) throws Exception {
        List<String> fails = new ArrayList<>();
        String name = "WWWWWWWWWWWWWWWW";
        System.setProperty("killer560smod.test.playerName", name);
        try (ScreenSweepCases.SweepEnv env = new ScreenSweepCases.SweepEnv(c);
             SwapAccountsCases.Accounts acc = new SwapAccountsCases.Accounts(c)) {
            ScreenSweepCases.menuTheme(c, "AMBER");
            int shown = 0;
            for (int[] sz : new int[][]{{1920, 1080, 2}, {854, 480, 2}, {1920, 1080, 4}}) {
                BazaarV3Cases.setWindow(c, sz[0], sz[1], sz[2]);
                Screen s = SwapAccountsCases.open(c);
                c.ctx().getInput().setCursorPos(2, 2);
                c.ctx().waitTicks(10);
                String tag = sz[0] + "x" + sz[1] + " gui " + sz[2];
                String want = "Playing as " + name;
                int chipW = c.onClient(mc -> SocialCases.intField(s, "chipW"));
                String text = c.onClient(mc -> (String) Mod.field(s, "chipText"));
                int textW = c.onClient(mc -> mc.font.width(want));
                List<String> drawn = SwapAccountsCases.allTexts(c, s);
                c.note(tag + ": chip " + chipW + " wide for a " + textW + " wide text; drawn "
                        + drawn.stream().filter(t -> t.contains("Playing as")).toList());
                if (chipW == 0) {
                    if (sz[2] < 4) {
                        fails.add(tag + ": the badge is hidden at a size that has room");
                    }
                    continue;
                }
                shown++;
                if (!want.equals(text)) {
                    fails.add(tag + ": the badge's text is '" + text + "', not '" + want + "'");
                }
                if (drawn.stream().noneMatch(t -> t.equals(want))) {
                    fails.add(tag + ": no drawn text equals '" + want + "': " + drawn);
                }
                if (chipW < textW + 18) {
                    fails.add(tag + ": the badge is " + chipW + " wide for " + textW + " of text");
                }
                int chipX = c.onClient(mc -> SocialCases.intField(s, "chipX"));
                int sw = c.onClient(mc -> s.width);
                if (chipX < 0 || chipX + chipW > sw) {
                    fails.add(tag + ": the badge (" + chipX + "+" + chipW + ") leaves the screen (" + sw + ")");
                }
                for (AbstractWidget w : widgets(c, s)) {
                    int[] r = SocialCases.rect(w);
                    boolean sameRow = r[1] < c.onClient(mc -> SocialCases.intField(s, "toolbarY")) + 18
                            && r[1] + r[3] > c.onClient(mc -> SocialCases.intField(s, "toolbarY"));
                    if (sameRow && w.visible && r[0] < chipX + chipW && r[0] + r[2] > chipX && r[2] > 0
                            && r[1] < c.onClient(mc -> SocialCases.intField(s, "listY")) - 6) {
                        fails.add(tag + ": '" + SocialCases.plain(w) + "' overlaps the badge");
                    }
                }
            }
            if (shown < 2) {
                fails.add("the badge was measured at only " + shown + " sizes");
            }
        } finally {
            System.clearProperty("killer560smod.test.playerName");
        }
        c.check(fails.isEmpty(), "Playing-as badge: " + fails);
    }

    private static List<AbstractWidget> widgets(Session c, Screen s) {
        return SwapAccountsCases.widgets(c, s);
    }
}

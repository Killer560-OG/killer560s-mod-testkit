package dev.testkit.gametest.menu;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 1640-1644: killer560's 2026-10-15 profit-by-account item (mod profit-accounts-1015): "For profit trackers by default
 * have it separate profit from different accounts on the same profile. Have an option to show all profit though from
 * all accounts. Also have options to share it between profiles or separate by profiles."
 * <ul>
 *   <li>1640 two fake accounts claim one experiment each through the real screens: This Account shows each one's own
 *       claim, All Accounts both; the file stores each claim under its account's UUID.</li>
 *   <li>1641 Dungeon Profit: This Account against All Accounts on the no-argument totals every screen reads, and the
 *       /croesus screen's Account switch stepping This Account, All Accounts, each other account and back (left and
 *       right click); a reset with one account picked hides only that account.</li>
 *   <li>1642 mod profiles: two claims under two real mod profiles (saved and applied through ProfileManager). Shared
 *       Across Profiles counts both in either profile; Per Mod Profile counts only the applied profile's, both ways; a
 *       rename keeps the profile's profit; the setting survives applying a profile and no profile carries it.</li>
 *   <li>1643 migration: an old Croesus log (claims with no account, with the current account's name, with a stranger's
 *       name) and an old Experiments file (one pot, no buckets): the unnamed and the current-name claims become the
 *       current account's (written back with its UUID and the current mod profile), the stranger's stays theirs.</li>
 *   <li>1644 the settings: Show Profit and Profit Data rows in the Croesus and Experiments tabs, cycling both ways,
 *       surviving a reload, described by their tooltips; Per Mod Profile pins the screens' Mod Profile switch.</li>
 * </ul>
 */
final class ProfitAccountCases {

    private static final String CONFIG = TrackerCases.CONFIG;
    private static final String PROFIT = TrackerCases.PROFIT;
    private static final String SCOPE = TrackerCases.SCOPE;
    private static final String CROESUS = TrackerCases.CROESUS;
    private static final String PCFG = "gui.profit.ProfitConfig";
    private static final String PM = "profiles.ProfileManager";

    private static final String A_NAME = "TkAlpha";
    private static final String A_ID = "a1a1a1a1-0000-4000-8000-000000000001";
    private static final String B_NAME = "TkBravo";
    private static final String B_ID = "b2b2b2b2-0000-4000-8000-000000000002";

    private ProfitAccountCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1640-menu-profit-two-accounts", ProfitAccountCases::twoAccounts);
        MenuSuite.test(s, "1641-menu-profit-this-vs-all", ProfitAccountCases::thisVsAll);
        MenuSuite.test(s, "1642-menu-profit-mod-profiles", ProfitAccountCases::modProfiles);
        MenuSuite.test(s, "1643-menu-profit-migration", ProfitAccountCases::migration);
        MenuSuite.test(s, "1644-menu-profit-settings", ProfitAccountCases::settings);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static String hex(String uuid) {
        return uuid.replace("-", "").toLowerCase(java.util.Locale.ROOT);
    }

    private static Object pcfg() {
        return Mod.cfg(PCFG);
    }

    private static void show(String constant) {
        Mod.call(pcfg(), "setShow", Mod.enumValue(PCFG + "$Show", constant));
        Mod.call(pcfg(), "save");
    }

    private static void dataMode(String constant) {
        Mod.call(pcfg(), "setDataMode", Mod.enumValue(PCFG + "$DataMode", constant));
        Mod.call(pcfg(), "save");
    }

    private static void account(String name, String id) {
        Mod.staticCall(SCOPE, "setAccountForTest", name, id);
    }

    /** The no-argument total every /profit screen and the hub card read (what is shown). */
    private static long shownSessions() {
        return ((Number) Mod.staticCall(PROFIT, "getTotalSessions")).longValue();
    }

    private static long shownCroesusProfit() {
        return TrackerCases.profit((Map<?, ?>) Mod.staticCall(CROESUS, "allTime"));
    }

    /** Snapshot of a file to put back afterwards. */
    private record Saved(Path path, byte[] bytes) {
        static Saved of(Path p) throws Exception {
            return new Saved(p, Files.exists(p) ? Files.readAllBytes(p) : null);
        }

        void restore() throws Exception {
            if (bytes == null) {
                Files.deleteIfExists(path);
            } else {
                Files.createDirectories(path.getParent());
                Files.write(path, bytes);
            }
        }
    }

    private static Saved savedConfig(Session c) throws Exception {
        return Saved.of(c.onClient(mc -> (Path) Mod.field(PCFG, "PATH")));
    }

    private static void restoreConfig(Session c, Saved saved) throws Exception {
        saved.restore();
        c.ctx().runOnClient(mc -> Mod.staticCall(PCFG, "load"));
    }

    private static String entry(String ts, String account, String accountId, String mod, long profit) {
        StringBuilder key = new StringBuilder();
        if (account != null) {
            key.append("\"account\":\"").append(account).append("\",");
        }
        if (accountId != null) {
            key.append("\"accountId\":\"").append(accountId).append("\",");
        }
        if (mod != null) {
            key.append("\"sbProfile\":\"Apple\",\"modProfile\":\"").append(mod).append("\",");
        }
        return "{" + key + "\"timestamp\":\"" + ts + "\",\"floor\":\"F7\",\"chest\":\"Bedrock\",\"source\":\"t\","
                + "\"items\":[],\"cost\":100,\"value\":" + (profit + 100) + ",\"profit\":" + profit + "}";
    }

    private static void runOn(Session c, ClientTask r) {
        c.ctx().runOnClient(mc -> {
            try {
                r.run(mc);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    private interface ClientTask {
        void run(net.minecraft.client.Minecraft mc) throws Exception;
    }

    private static String text(AbstractWidget w) {
        return w.getMessage().getString().replaceAll("§.", "");
    }

    // ---- 1640 ------------------------------------------------------------------------------------------------------

    static void twoAccounts(Session c) throws Exception {
        MenuKit.reset(c);
        Saved cfgFile = savedConfig(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "ProfitTrackerEnabled", true).set(CONFIG, "AutonomousMode", false);
            runOn(c, mc -> {
                Mod.staticCall(PROFIT, "reset");
                show("THIS_ACCOUNT");
                dataMode("SHARED");
                account(A_NAME, A_ID);
            });
            TrackerCases.claim(c, 1);
            runOn(c, mc -> account(B_NAME, B_ID));
            TrackerCases.claim(c, 2);
            long asB = c.onClient(mc -> shownSessions());
            runOn(c, mc -> account(A_NAME, A_ID));
            long asA = c.onClient(mc -> shownSessions());
            runOn(c, mc -> show("ALL_ACCOUNTS"));
            long all = c.onClient(mc -> shownSessions());
            List<String> ids = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                for (Object k : (Collection<?>) Mod.staticCall(PROFIT, "keys")) {
                    out.add((String) Mod.call(k, "accountId"));
                }
                return out;
            });
            Path file = c.onClient(mc -> (Path) Mod.field(PROFIT, "DATA_PATH"));
            String json = Files.readString(file, StandardCharsets.UTF_8);
            boolean fileA = json.contains(hex(A_ID));
            boolean fileB = json.contains(hex(B_ID));
            c.note("This Account as " + B_NAME + ": " + asB + ", as " + A_NAME + ": " + asA + "; All Accounts: " + all
                    + "; key account ids " + ids + "; file holds A " + fileA + ", B " + fileB);
            c.check(asA == 1 && asB == 1, "each account should see its own one claim, got A " + asA + ", B " + asB);
            c.check(all == 2, "All Accounts should add both accounts' claims: " + all);
            c.check(ids.contains(hex(A_ID)) && ids.contains(hex(B_ID)), "the claims are not keyed by account UUID: " + ids);
            c.check(fileA && fileB, "the saved file does not store both accounts' UUIDs");
        } finally {
            MenuKit.reset(c);
            runOn(c, mc -> {
                account(null, null);
                Mod.staticCall(PROFIT, "reset");
            });
            restoreConfig(c, cfgFile);
        }
    }

    // ---- 1641 ------------------------------------------------------------------------------------------------------

    static void thisVsAll(Session c) throws Exception {
        Path path = c.onClient(mc -> (Path) Mod.field(CROESUS, "PATH"));
        Saved log = Saved.of(path);
        Saved cfgFile = savedConfig(c);
        try {
            runOn(c, mc -> {
                show("THIS_ACCOUNT");
                dataMode("SHARED");
                account(A_NAME, A_ID);
            });
            String file = "{\"version\":2,\"resets\":[],\"entries\":["
                    + entry("2026-01-03T00:00:01Z", A_NAME, hex(A_ID), "none", 100) + ","
                    + entry("2026-01-03T00:00:02Z", B_NAME, hex(B_ID), "none", 700) + ","
                    + entry("2026-01-03T00:00:03Z", "Stranger", null, "none", 50) + "]}";
            Files.createDirectories(path.getParent());
            Files.writeString(path, file, StandardCharsets.UTF_8);
            runOn(c, mc -> Mod.staticCall(CROESUS, "load"));
            long mine = c.onClient(mc -> shownCroesusProfit());
            runOn(c, mc -> show("ALL_ACCOUNTS"));
            long all = c.onClient(mc -> shownCroesusProfit());
            c.note("This Account (" + A_NAME + "): " + mine + ", All Accounts: " + all);
            c.check(mine == 100, "This Account should count only " + A_NAME + "'s 100: " + mine);
            c.check(all == 850, "All Accounts should count 850: " + all);

            // The /croesus screen's Account switch is the same setting: This, All, each other account, wrapping.
            runOn(c, mc -> {
                show("THIS_ACCOUNT");
                Object overview = Mod.enumValue("croesus.CroesusTrackerScreen$View", "OVERVIEW");
                Screen s = (Screen) Mod.cls("croesus.CroesusTrackerScreen")
                        .getConstructor(Screen.class, Mod.cls("croesus.CroesusTrackerScreen$View"))
                        .newInstance(null, overview);
                McCompat.setScreen(mc, s);
            });
            c.ctx().waitTicks(3);
            List<String> steps = new ArrayList<>();
            List<Long> profits = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                String label = c.onClient(mc -> text(accountSwitch(McCompat.screen(mc))));
                steps.add(label);
                profits.add(c.onClient(mc -> shownCroesusProfit()));
                if (i < 4) { // four steps round: This, All, Stranger, TkBravo, back to This
                    runOn(c, mc -> TrackerCases.press(accountSwitch(McCompat.screen(mc))));
                    c.ctx().waitTicks(1);
                }
            }
            runOn(c, mc -> TrackerCases.pressSecondary(accountSwitch(McCompat.screen(mc))));
            c.ctx().waitTicks(1);
            String back = c.onClient(mc -> text(accountSwitch(McCompat.screen(mc))));
            c.note("Account switch: " + steps + " showing " + profits + "; right click from the start: " + back);
            c.check(steps.equals(List.of("This Account", "All Accounts", "Account: Stranger", "Account: " + B_NAME,
                    "This Account")), "Account switch steps " + steps);
            c.check(profits.equals(List.of(100L, 850L, 50L, 700L, 100L)), "profit per step " + profits);
            c.check(back.equals("Account: " + B_NAME), "right click from This Account went to '" + back + "'");

            // A reset with B picked hides only B's claims.
            runOn(c, mc -> {
                Screen s = McCompat.screen(mc);
                TrackerCases.press(TrackerCases.button(s, "Reset Totals"));
            });
            String armed = c.onClient(mc -> text(TrackerCases.button(McCompat.screen(mc), "Reset ")));
            runOn(c, mc -> TrackerCases.press(TrackerCases.button(McCompat.screen(mc), "Reset ")));
            c.ctx().waitTicks(10);
            runOn(c, mc -> show("ALL_ACCOUNTS"));
            long afterAll = c.onClient(mc -> shownCroesusProfit());
            c.note("reset with " + B_NAME + " picked ('" + armed + "'): All Accounts now " + afterAll);
            c.check(armed.contains(B_NAME), "the armed reset does not name the picked account: " + armed);
            c.check(afterAll == 150, "resetting " + B_NAME + " should leave 150 for everyone else: " + afterAll);
        } finally {
            runOn(c, mc -> {
                McCompat.setScreen(mc, null);
                account(null, null);
            });
            log.restore();
            runOn(c, mc -> Mod.staticCall(CROESUS, "load"));
            restoreConfig(c, cfgFile);
        }
    }

    private static AbstractWidget accountSwitch(Screen s) {
        for (AbstractWidget w : TrackerCases.buttons(s)) {
            String t = text(w);
            if (t.equals("This Account") || t.equals("All Accounts") || t.startsWith("Account: ")) {
                return w;
            }
        }
        throw new AssertionError("no Account switch on " + s + ": " + TrackerCases.labels(s));
    }

    // ---- 1642 ------------------------------------------------------------------------------------------------------

    private static Path profileDir(String name) {
        return ((Path) Mod.field(PM, "PROFILES_DIR")).resolve(name);
    }

    private static void apply(String name) {
        Object r = Mod.staticCall(PM, "applyProfile", name);
        if (!(Boolean) Mod.call(r, "success")) {
            throw new AssertionError("applying " + name + " failed: " + Mod.call(r, "message"));
        }
    }

    static void modProfiles(Session c) throws Exception {
        MenuKit.reset(c);
        Saved cfgFile = savedConfig(c);
        Path marker = c.onClient(mc -> (Path) Mod.field(PM, "ACTIVE_MARKER"));
        Saved markerFile = Saved.of(marker);
        String pa = "tk-1642-a";
        String pa2 = "tk-1642-a2";
        String pb = "tk-1642-b";
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "ProfitTrackerEnabled", true).set(CONFIG, "AutonomousMode", false);
            runOn(c, mc -> {
                Mod.staticCall(PROFIT, "reset");
                show("THIS_ACCOUNT");
                dataMode("SHARED");
                Mod.call(Mod.cfg(CONFIG), "save");
                Mod.staticCall(PM, "saveCurrentAsProfile", pa);
                Mod.staticCall(PM, "saveCurrentAsProfile", pb);
                apply(pa);
                Mod.set(CONFIG, "setProfitTrackerEnabled", true);
                Mod.set(CONFIG, "setAutonomousMode", false);
            });
            TrackerCases.claim(c, 1);
            runOn(c, mc -> {
                apply(pb);
                Mod.set(CONFIG, "setProfitTrackerEnabled", true);
                Mod.set(CONFIG, "setAutonomousMode", false);
            });
            TrackerCases.claim(c, 2);
            String active = c.onClient(mc -> (String) Mod.staticCall(SCOPE, "currentModProfile"));
            long sharedB = c.onClient(mc -> shownSessions());
            runOn(c, mc -> dataMode("PER_PROFILE"));
            long perB = c.onClient(mc -> shownSessions());
            runOn(c, mc -> apply(pa));
            long perA = c.onClient(mc -> shownSessions());
            String modeAfterApply = c.onClient(mc -> String.valueOf(Mod.call(pcfg(), "getDataMode")));
            boolean carried = c.onClient(mc -> {
                Path f = (Path) Mod.field(PCFG, "PATH");
                return Files.exists(profileDir(pa).resolve(f.getFileName()))
                        || Files.exists(profileDir(pb).resolve(f.getFileName()));
            });
            runOn(c, mc -> dataMode("SHARED"));
            long sharedA = c.onClient(mc -> shownSessions());
            c.note("active after second apply '" + active + "'; Shared: in " + pb + " " + sharedB + ", in " + pa + " "
                    + sharedA + "; Per Mod Profile: " + pb + " " + perB + ", " + pa + " " + perA
                    + "; Profit Data after applying a profile: " + modeAfterApply + "; a profile carries the setting: "
                    + carried);
            c.check(pb.equals(active), "the second profile is not the active one: " + active);
            c.check(sharedB == 2 && sharedA == 2, "Shared Across Profiles should show both claims in either profile");
            c.check(perB == 1 && perA == 1, "Per Mod Profile should show each profile's own claim, got " + pb + " " + perB
                    + ", " + pa + " " + perA);
            c.check("PER_PROFILE".equals(modeAfterApply), "applying a profile changed Profit Data to " + modeAfterApply);
            c.check(!carried, "a profile folder carries the profit trackers' settings file");

            // A rename keeps the profile's profit under Per Mod Profile.
            runOn(c, mc -> {
                dataMode("PER_PROFILE");
                Object r = Mod.staticCall(PM, "renameProfile", pa, pa2);
                if (!(Boolean) Mod.call(r, "success")) {
                    throw new AssertionError("rename failed: " + Mod.call(r, "message"));
                }
            });
            long renamed = c.onClient(mc -> shownSessions());
            String now = c.onClient(mc -> (String) Mod.staticCall(SCOPE, "currentModProfile"));
            c.note("after renaming " + pa + " to " + pa2 + " (active '" + now + "'): Per Mod Profile shows " + renamed);
            c.check(pa2.equals(now) && renamed == 1, "the renamed profile lost its profit: " + renamed + " (active " + now + ")");
        } finally {
            MenuKit.reset(c);
            runOn(c, mc -> {
                for (String p : new String[]{pa, pa2, pb}) {
                    if (Files.isDirectory(profileDir(p))) {
                        Mod.staticCall(PM, "deleteProfile", p);
                    }
                }
                Mod.staticCall(PROFIT, "reset");
            });
            markerFile.restore();
            restoreConfig(c, cfgFile);
        }
    }

    // ---- 1643 ------------------------------------------------------------------------------------------------------

    static void migration(Session c) throws Exception {
        Path cpath = c.onClient(mc -> (Path) Mod.field(CROESUS, "PATH"));
        Path epath = c.onClient(mc -> (Path) Mod.field(PROFIT, "DATA_PATH"));
        Saved clog = Saved.of(cpath);
        Saved elog = Saved.of(epath);
        Saved cfgFile = savedConfig(c);
        try {
            String me = c.onClient(mc -> mc.getUser().getName());
            String myId = c.onClient(mc -> hex(mc.getUser().getProfileId().toString()));
            String mod = c.onClient(mc -> (String) Mod.staticCall(SCOPE, "currentModProfile"));
            runOn(c, mc -> {
                account(null, null);
                show("THIS_ACCOUNT");
                dataMode("SHARED");
            });
            String old = "{\"version\":2,\"resets\":[],\"entries\":["
                    + entry("2026-01-04T00:00:01Z", null, null, null, 100) + ","
                    + entry("2026-01-04T00:00:02Z", me, null, "none", 200) + ","
                    + entry("2026-01-04T00:00:03Z", "Stranger", null, "none", 400) + "]}";
            Files.createDirectories(cpath.getParent());
            Files.writeString(cpath, old, StandardCharsets.UTF_8);
            runOn(c, mc -> Mod.staticCall(CROESUS, "load"));
            long mine = c.onClient(mc -> shownCroesusProfit());
            Object strangerFilter = TrackerCases.filter("Stranger", null, null);
            long stranger = c.onClient(mc -> TrackerCases.profit(TrackerCases.croesusAllTime(strangerFilter)));
            runOn(c, mc -> show("ALL_ACCOUNTS"));
            long all = c.onClient(mc -> shownCroesusProfit());
            c.ctx().waitTicks(10); // the log writes on its own thread
            com.google.gson.JsonArray entries = com.google.gson.JsonParser.parseString(
                    Files.readString(cpath, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("entries");
            List<String> ids = new ArrayList<>();
            List<String> mods = new ArrayList<>();
            for (var e : entries) {
                var o = e.getAsJsonObject();
                ids.add(o.has("accountId") ? o.get("accountId").getAsString() : "-");
                mods.add(o.has("modProfile") ? o.get("modProfile").getAsString() : "-");
            }
            c.note("Croesus old log: This Account " + mine + ", Stranger " + stranger + ", All Accounts " + all
                    + "; written back with account ids " + ids + " (current " + myId + "), mod profiles " + mods
                    + " (current '" + mod + "')");
            c.check(mine == 300, "the unnamed and the current-name claims should be this account's 300: " + mine);
            c.check(stranger == 400 && all == 700, "the stranger's claim should stay theirs (400 of 700): " + stranger
                    + " of " + all);
            c.check(ids.equals(List.of(myId, myId, "-")), "account ids written back " + ids);
            c.check(mods.get(0).equals(mod), "the unnamed claim got mod profile " + mods.get(0) + ", want " + mod);

            // Experiments: a file from before buckets (one pot of totals) becomes the current account's.
            String oldExp = "{\"totals\":{\"trackingSinceMs\":1,\"lastSummary\":\"\",\"sessions\":3,\"xpGained\":90000,"
                    + "\"rewardValueCoins\":1500.0,\"bitsSpent\":150,\"sessionsPerGame\":{\"Chronomatron\":3},\"items\":{}},"
                    + "\"sessionLog\":[]}";
            Files.writeString(epath, oldExp, StandardCharsets.UTF_8);
            runOn(c, mc -> {
                show("THIS_ACCOUNT");
                Mod.staticCall(PROFIT, "reload");
            });
            long expMine = c.onClient(mc -> shownSessions());
            runOn(c, mc -> account(B_NAME, B_ID));
            long expOther = c.onClient(mc -> shownSessions());
            runOn(c, mc -> account(null, null));
            String written = Files.readString(epath, StandardCharsets.UTF_8);
            c.note("Experiments old pot: this account " + expMine + " sessions, another account " + expOther
                    + "; file now names the account: " + written.contains(myId));
            c.check(expMine == 3 && expOther == 0, "the old pot should be this account's 3 sessions (other account "
                    + expOther + "): " + expMine);
            c.check(written.contains(myId), "the migrated Experiments file does not hold the account UUID");
        } finally {
            runOn(c, mc -> account(null, null));
            clog.restore();
            elog.restore();
            runOn(c, mc -> {
                Mod.staticCall(CROESUS, "load");
                Mod.staticCall(PROFIT, "reload");
            });
            restoreConfig(c, cfgFile);
        }
    }

    // ---- 1644 ------------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<AbstractWidget> tab(String cls) throws Exception {
        Object tab = Mod.cls(cls).getConstructor().newInstance();
        return (List<AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0, 300, (Runnable) () -> { });
    }

    private static AbstractWidget row(List<AbstractWidget> ws, String prefix) {
        for (AbstractWidget w : ws) {
            if (text(w).startsWith(prefix)) {
                return w;
            }
        }
        return null;
    }

    static void settings(Session c) throws Exception {
        Saved cfgFile = savedConfig(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "ProfitTrackerEnabled", true).set("croesus.CroesusConfig", "LoggerEnabled", true);
            runOn(c, mc -> {
                show("THIS_ACCOUNT");
                dataMode("SHARED");
            });
            for (String cls : new String[]{"gui.tab.CroesusTab", "gui.tab.ExperimentsTab"}) {
                List<String> seen = new ArrayList<>();
                String why = c.onClient(mc -> {
                    try {
                        List<AbstractWidget> ws = tab(cls);
                        AbstractWidget s = row(ws, "Show Profit");
                        AbstractWidget d = row(ws, "Profit Data");
                        if (s == null || d == null) {
                            List<String> all = new ArrayList<>();
                            ws.forEach(w -> all.add(text(w)));
                            return "missing Show Profit / Profit Data rows: " + all;
                        }
                        if (!(Boolean) Mod.call(s, "isCycling") || !(Boolean) Mod.call(d, "isCycling")) {
                            return "the rows are not cycling settings";
                        }
                        seen.add(text(s));
                        TrackerCases.press(s);
                        seen.add(text(s) + "=" + Mod.call(pcfg(), "getShow"));
                        TrackerCases.pressSecondary(s);
                        seen.add(text(s) + "=" + Mod.call(pcfg(), "getShow"));
                        seen.add(text(d));
                        TrackerCases.press(d);
                        seen.add(text(d) + "=" + Mod.call(pcfg(), "getDataMode"));
                        TrackerCases.pressSecondary(d);
                        seen.add(text(d) + "=" + Mod.call(pcfg(), "getDataMode"));
                        TrackerCases.press(s);
                        TrackerCases.press(d);
                        Mod.staticCall(PCFG, "load"); // a restart
                        seen.add("reloaded " + Mod.call(pcfg(), "getShow") + "/" + Mod.call(pcfg(), "getDataMode"));
                        String tipS = (String) Mod.staticCall("gui.SettingTooltips", "describe", cls.endsWith("CroesusTab")
                                ? "Croesus" : "Experiments", text(s));
                        String tipD = (String) Mod.staticCall("gui.SettingTooltips", "describe", cls.endsWith("CroesusTab")
                                ? "Croesus" : "Experiments", text(d));
                        if (tipS == null || !tipS.contains("This Account") || tipD == null || !tipD.contains("Per Mod Profile")) {
                            return "tooltips missing: '" + tipS + "' / '" + tipD + "'";
                        }
                        show("THIS_ACCOUNT");
                        dataMode("SHARED");
                        return null;
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                });
                c.note(cls + ": " + seen);
                c.check(why == null, cls + ": " + why);
                c.check(seen.equals(List.of("Show Profit: This Account", "Show Profit: All Accounts=ALL_ACCOUNTS",
                        "Show Profit: This Account=THIS_ACCOUNT", "Profit Data: Shared Across Profiles",
                        "Profit Data: Per Mod Profile=PER_PROFILE", "Profit Data: Shared Across Profiles=SHARED",
                        "reloaded ALL_ACCOUNTS/PER_PROFILE")), cls + " steps " + seen);
            }

            // Per Mod Profile pins the /profit screen's Mod Profile switch to the current mod profile.
            runOn(c, mc -> {
                dataMode("PER_PROFILE");
                Screen s = (Screen) Mod.cls("gui.profit.ExperimentsProfitScreen").getConstructor(Screen.class)
                        .newInstance((Object) null);
                McCompat.setScreen(mc, s);
            });
            c.ctx().waitTicks(3);
            String mod = c.onClient(mc -> (String) Mod.staticCall(SCOPE, "currentModProfile"));
            String before = c.onClient(mc -> text(TrackerCases.button(McCompat.screen(mc), "Mod Profile")));
            runOn(c, mc -> TrackerCases.press(TrackerCases.button(McCompat.screen(mc),
                    "Mod Profile")));
            c.ctx().waitTicks(1);
            String after = c.onClient(mc -> text(TrackerCases.button(McCompat.screen(mc), "Mod Profile")));
            Object viewMod = c.onClient(mc -> {
                try {
                    Object v = Mod.staticCall(PROFIT, "view");
                    return v.getClass().getField("modProfile").get(v);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            c.note("Per Mod Profile: switch '" + before + "', after a click '" + after + "', saved filter " + viewMod);
            c.check(before.equals("Mod Profile: " + mod) && after.equals(before) && viewMod == null,
                    "the Mod Profile switch should name '" + mod + "' and not step: '" + before + "' -> '" + after + "'");
        } finally {
            runOn(c, mc -> McCompat.setScreen(mc, null));
            restoreConfig(c, cfgFile);
        }
    }
}

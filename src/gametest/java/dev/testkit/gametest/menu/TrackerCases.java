package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.inventory.ContainerInput;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 731-736: killer560's 2026-10-11 tracker items (mod branch trackers).
 * <ul>
 *   <li>731 Experimentation Table profit by account / Skyblock profile / mod profile: two claims on two Skyblock profiles,
 *       each counted under its own scope and both under All Profit; a scoped reset clears only its scope.</li>
 *   <li>732 Dungeon (Croesus) profit the same way, from log files: an old file's earlier reset is kept, claims split by
 *       account and profile, a scoped reset hides only that account and survives a reload, the entries stay.</li>
 *   <li>733 the /profit screens: All Profit / Account (This Account, All Accounts since profit-accounts-1015) / Profile / Mod Profile switches, and Reset Totals turns red asking
 *       "Reset ...?", resets on the second press, and turns back after 3 s.</li>
 *   <li>734 the Experiments tab: no Reset Profit Totals, no totals text, Open /profit where the reset was.</li>
 *   <li>735 Auto E-Table (cheat): a Superpairs board whose clicks run out (and stays open, as on Hypixel) gets exactly one
 *       Escape (one container close, no clicks after the end), then the rewards are claimed. Legit: nothing sent.</li>
 *   <li>736 RNG Meter: a reward that reaches an already-scanned page later is still read (it was committed after three
 *       frames and never read again), and an abbreviated current score ("1.2M/5M") parses.</li>
 * </ul>
 */
final class TrackerCases {

    static final String CONFIG = "experiments.ExperimentsConfig";
    static final String PROFIT = "experiments.ExperimentsProfitTracker";
    static final String SCOPE = "gui.profit.ProfitScope";
    static final String CROESUS = "croesus.CroesusProfitLog";
    static final String BUTTON = "gui.SettingsButtonWidget";

    private TrackerCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "731-menu-profit-scope-experiments", TrackerCases::experimentsScope);
        MenuSuite.test(s, "732-menu-profit-scope-croesus", TrackerCases::croesusScope);
        MenuSuite.test(s, "733-menu-profit-screen-reset-red", TrackerCases::screens);
        MenuSuite.test(s, "734-menu-exp-tab-profit-row", TrackerCases::tab);
        MenuSuite.test(s, "735-menu-exp-superpairs-escape", TrackerCases::superpairsEscape);
        MenuSuite.test(s, "736-menu-rng-late-rewards", TrackerCases::rngLate);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    static Object filter(String account, String profile, String modProfile) throws Exception {
        Object f = Mod.cls(SCOPE + "$Filter").getConstructor().newInstance();
        f.getClass().getField("account").set(f, account);
        f.getClass().getField("profile").set(f, profile);
        f.getClass().getField("modProfile").set(f, modProfile);
        return f;
    }

    static long sessions(Object filter) {
        return ((Number) Mod.staticCall(PROFIT, "getTotalSessions", filter)).longValue();
    }

    static void clearView(Object view) throws Exception {
        Mod.call(view, "clear");
    }

    /** One Chronomatron claim through the real screens, as 227 does; waits for the tracker to commit it. */
    static void claim(Session c, long expectTotal) throws Exception {
        JsonObject puzzle = MenuKit.obj("{\"title\":\"Chronomatron (High)\",\"rows\":6,\"fill\":true}");
        MenuKit.show(c, puzzle);
        MenuKit.awaitScreen(c, "Chronomatron (High)", 100);
        c.ctx().waitTicks(10);
        MenuKit.show(c, MenuKit.menu("menus.experiment-over-chronomatron"));
        MenuKit.awaitScreen(c, "Experiment Over", 100);
        c.ctx().waitTicks(10);
        MenuKit.click(c, 22, 0, ContainerInput.PICKUP);
        Object all = filter(null, null, null);
        c.waitUntil(expectTotal + " logged experiment(s)", mc -> sessions(all) == expectTotal, 100);
        MenuKit.reset(c);
    }

    static List<AbstractWidget> buttons(Screen screen) {
        List<AbstractWidget> out = new ArrayList<>();
        for (GuiEventListener l : screen.children()) {
            if (l instanceof AbstractWidget w && w.getClass().getName().endsWith("SettingsButtonWidget")) {
                out.add(w);
            }
        }
        return out;
    }

    static AbstractWidget button(Screen screen, String prefix) {
        for (AbstractWidget w : buttons(screen)) {
            if (w.getMessage().getString().replaceAll("§.", "").startsWith(prefix)) {
                return w;
            }
        }
        return null;
    }

    static String labels(Screen screen) {
        List<String> l = new ArrayList<>();
        buttons(screen).forEach(w -> l.add(w.getMessage().getString().replaceAll("§.", "")));
        return l.toString();
    }

    /** Runs a SettingsButtonWidget's own press handler (what a left click runs). */
    static void press(AbstractWidget w) throws Exception {
        Object onPress = Mod.field(w, "onPress");
        Class<?> btn = Mod.cls(BUTTON);
        Method m = Mod.cls(BUTTON + "$OnPress").getMethod("onPress", btn);
        m.invoke(onPress, w);
    }

    static void pressSecondary(AbstractWidget w) throws Exception {
        Object onPress = Mod.field(w, "onSecondaryPress");
        Class<?> btn = Mod.cls(BUTTON);
        Method m = Mod.cls(BUTTON + "$OnPress").getMethod("onPress", btn);
        m.invoke(onPress, w);
    }

    static boolean danger(AbstractWidget w) {
        return (Boolean) Mod.call(w, "isDanger");
    }

    // ---- 731 -------------------------------------------------------------------------------------------------------

    static void experimentsScope(Session c) throws Exception {
        MenuKit.reset(c);
        Object profileWas = c.onClient(mc -> Mod.field("pathfinding.ProfileTracker", "profileName"));
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "ProfitTrackerEnabled", true).set(CONFIG, "AutonomousMode", false);
            String account = c.onClient(mc -> mc.getUser().getName());
            c.ctx().runOnClient(mc -> {
                Mod.staticCall(PROFIT, "reset");
                try {
                    clearView(Mod.staticCall(PROFIT, "view"));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                Mod.setField("pathfinding.ProfileTracker", "profileName", "");
                Mod.setField(SCOPE, "lastSkyblockProfile", "");
            });
            claim(c, 1);                       // profile not known yet -> "?"
            c.ctx().runOnClient(mc -> Mod.setField("pathfinding.ProfileTracker", "profileName", "Mango"));
            claim(c, 2);                       // on Skyblock profile Mango
            long all = sessions(filter(null, null, null));
            long mine = sessions(filter(account, null, null));
            long other = sessions(filter("NotAnAccount", null, null));
            long mango = sessions(filter(null, "Mango", null));
            long unknown = sessions(filter(null, "?", null));
            String mod = c.onClient(mc -> (String) Mod.staticCall(SCOPE, "currentModProfile"));
            long modNow = sessions(filter(null, null, mod));
            int keys = c.onClient(mc -> ((Collection<?>) Mod.staticCall(PROFIT, "keys")).size());
            c.note("all " + all + ", account " + account + " " + mine + ", other account " + other + ", profile Mango "
                    + mango + ", profile ? " + unknown + ", mod profile '" + mod + "' " + modNow + ", " + keys + " keys");
            c.check(all == 2 && mine == 2 && other == 0 && mango == 1 && unknown == 1 && modNow == 2 && keys == 2,
                    "scoped counts wrong (want all 2, account 2, other 0, Mango 1, ? 1, mod 2, keys 2)");
            long xpMango = ((Number) c.onClient(mc -> {
                try {
                    return Mod.staticCall(PROFIT, "getTotalXp", filter(null, "Mango", null));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            })).longValue();
            c.check(xpMango == 30_000, "Mango's bucket XP " + xpMango + ", one +30k claim");

            Object mangoFilter = filter(null, "Mango", null);
            c.ctx().runOnClient(mc -> Mod.staticCall(PROFIT, "reset", mangoFilter));
            long afterAll = sessions(filter(null, null, null));
            long afterMango = sessions(filter(null, "Mango", null));
            c.note("reset Mango only: all " + afterAll + ", Mango " + afterMango);
            c.check(afterAll == 1 && afterMango == 0, "a Mango-only reset left all " + afterAll + ", Mango " + afterMango);
        } finally {
            MenuKit.reset(c);
            c.ctx().runOnClient(mc -> {
                Mod.staticCall(PROFIT, "reset");
                Mod.setField("pathfinding.ProfileTracker", "profileName", profileWas);
            });
        }
    }

    // ---- 732 -------------------------------------------------------------------------------------------------------

    private static String entry(String ts, String account, String profile, long profit) {
        String key = account == null ? "" : "\"account\":\"" + account + "\",\"sbProfile\":\"" + profile
                + "\",\"modProfile\":\"none\",";
        return "{" + key + "\"timestamp\":\"" + ts + "\",\"floor\":\"F7\",\"chest\":\"Bedrock\",\"source\":\"t\","
                + "\"items\":[],\"cost\":100,\"value\":" + (profit + 100) + ",\"profit\":" + profit + "}";
    }

    static Map<?, ?> croesusAllTime(Object filter) {
        return (Map<?, ?>) Mod.staticCall(CROESUS, "allTime", filter);
    }

    static long chests(Map<?, ?> totals) {
        Object all = totals.get("All");
        return all == null ? 0 : ((Number) Mod.field(all, "chests")).longValue();
    }

    static long profit(Map<?, ?> totals) {
        Object all = totals.get("All");
        return all == null ? 0 : ((Number) Mod.field(all, "profit")).longValue();
    }

    /**
     * Waits until every save CroesusProfitLog has queued on its writer thread is on disk (a no-op queued behind them).
     * Reading the log migrates old claims and queues a save; on the sweep of 2026-10-21 that save replaced the file while
     * this case was writing the next fixture, load() found no file ("Could not read ... starting a fresh log") and every
     * total read 0. Nothing in the game reloads this log after startup, so only a test that writes the file can race it.
     */
    static void drainCroesusWriter() throws Exception {
        ((java.util.concurrent.ExecutorService) Mod.field(CROESUS, "WRITER")).submit(() -> { })
                .get(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    static void croesusScope(Session c) throws Exception {
        Path path = c.onClient(mc -> (Path) Mod.field(CROESUS, "PATH"));
        drainCroesusWriter();
        byte[] before = Files.exists(path) ? Files.readAllBytes(path) : null;
        try {
            Files.createDirectories(path.getParent());
            // An old file: three claims, totals counting only the last two (an old Reset totals cleared the first).
            String legacy = "{\"totals\":{\"All\":{\"chests\":2,\"cost\":200,\"value\":2000,\"profit\":1800}},\"entries\":["
                    + entry("2026-01-01T00:00:01Z", null, null, 900) + ","
                    + entry("2026-01-01T00:00:02Z", null, null, 900) + ","
                    + entry("2026-01-01T00:00:03Z", null, null, 900) + "]}";
            Files.writeString(path, legacy, StandardCharsets.UTF_8);
            c.ctx().runOnClient(mc -> Mod.staticCall(CROESUS, "load"));
            Object all = filter(null, null, null);
            long legacyChests = c.onClient(mc -> chests(croesusAllTime(all)));
            int legacyEntries = c.onClient(mc -> ((Number) Mod.staticCall(CROESUS, "entryCount")).intValue());
            c.note("old file: " + legacyEntries + " entries, " + legacyChests + " counted");
            c.check(legacyChests == 2 && legacyEntries == 3, "old file read as " + legacyChests + " counted of "
                    + legacyEntries + ", want 2 of 3 (its earlier reset kept)");

            String v2 = "{\"version\":2,\"resets\":[],\"entries\":["
                    + entry("2026-01-02T00:00:01Z", "AcctA", "Apple", 500) + ","
                    + entry("2026-01-02T00:00:02Z", "AcctB", "Apple", 700) + ","
                    + entry("2026-01-02T00:00:03Z", "AcctA", "Banana", 300) + "]}";
            drainCroesusWriter(); // reading the old file queued a save (its claims were given an account)
            Files.writeString(path, v2, StandardCharsets.UTF_8);
            c.ctx().runOnClient(mc -> Mod.staticCall(CROESUS, "load"));
            Object a = filter("AcctA", null, null);
            Object b = filter("AcctB", null, null);
            Object banana = filter(null, "Banana", null);
            long pa = c.onClient(mc -> profit(croesusAllTime(a)));
            long pb = c.onClient(mc -> profit(croesusAllTime(b)));
            long pBanana = c.onClient(mc -> profit(croesusAllTime(banana)));
            long pAll = c.onClient(mc -> profit(croesusAllTime(all)));
            c.note("profit A " + pa + ", B " + pb + ", Banana " + pBanana + ", all " + pAll);
            c.check(pa == 800 && pb == 700 && pBanana == 300 && pAll == 1500, "scoped profit wrong (want 800/700/300/1500)");

            c.ctx().runOnClient(mc -> Mod.staticCall(CROESUS, "reset", a));
            drainCroesusWriter(); // the log writes on its own thread
            c.ctx().runOnClient(mc -> Mod.staticCall(CROESUS, "load"));
            long ra = c.onClient(mc -> chests(croesusAllTime(a)));
            long rb = c.onClient(mc -> profit(croesusAllTime(b)));
            int entries = c.onClient(mc -> ((Number) Mod.staticCall(CROESUS, "entryCount")).intValue());
            c.note("after resetting AcctA and reloading: A " + ra + " chests, B profit " + rb + ", " + entries + " entries kept");
            c.check(ra == 0 && rb == 700 && entries == 3, "AcctA reset: A " + ra + " chests, B " + rb + ", entries " + entries);
        } finally {
            drainCroesusWriter();
            if (before != null) {
                Files.write(path, before);
            } else {
                Files.deleteIfExists(path);
            }
            c.ctx().runOnClient(mc -> Mod.staticCall(CROESUS, "load"));
        }
    }

    // ---- 733 -------------------------------------------------------------------------------------------------------

    static void screens(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "ProfitTrackerEnabled", true).set(CONFIG, "AutonomousMode", false);
            String account = c.onClient(mc -> mc.getUser().getName());
            c.ctx().runOnClient(mc -> {
                Mod.staticCall(PROFIT, "reset");
                try {
                    clearView(Mod.staticCall(PROFIT, "view"));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            claim(c, 1);
            c.ctx().runOnClient(mc -> {
                try {
                    Screen s = (Screen) Mod.cls("gui.profit.ExperimentsProfitScreen").getConstructor(Screen.class)
                            .newInstance((Object) null);
                    McCompat.setScreen(mc, s);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            c.ctx().waitTicks(3);
            String first = c.onClient(mc -> labels(McCompat.screen(mc)));
            c.note("experiments screen buttons: " + first);
            c.check(first.contains("All Profit") && first.contains("Account") && first.contains("Profile")
                    && first.contains("Mod Profile"), "scope switches missing: " + first);

            // Account switch (since profit-accounts-1015 the shared Show Profit setting, This Account by default):
            // This Account -> All Accounts, right click back; then All Profit selects everything for the reset below.
            Path pcfgPath = c.onClient(mc -> (Path) Mod.field("gui.profit.ProfitConfig", "PATH"));
            byte[] pcfgBefore = Files.exists(pcfgPath) ? Files.readAllBytes(pcfgPath) : null;
            try {
                c.ctx().runOnClient(mc -> {
                    Object pc = Mod.cfg("gui.profit.ProfitConfig");
                    Mod.call(pc, "setShow", Mod.enumValue("gui.profit.ProfitConfig$Show", "THIS_ACCOUNT"));
                    Mod.call(pc, "setDataMode", Mod.enumValue("gui.profit.ProfitConfig$DataMode", "SHARED"));
                    McCompat.setScreen(mc, null);
                    try {
                        Screen s = (Screen) Mod.cls("gui.profit.ExperimentsProfitScreen").getConstructor(Screen.class)
                                .newInstance((Object) null);
                        McCompat.setScreen(mc, s);
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                });
                c.ctx().waitTicks(3);
                String start = c.onClient(mc -> button(McCompat.screen(mc), "This Account") == null ? labels(McCompat.screen(mc))
                        : "This Account");
                c.ctx().runOnClient(mc -> {
                    try {
                        press(button(McCompat.screen(mc), "This Account"));
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                });
                Object picked = c.onClient(mc -> String.valueOf(Mod.call(Mod.cfg("gui.profit.ProfitConfig"), "getShow")));
                String afterPick = c.onClient(mc -> labels(McCompat.screen(mc)));
                c.check("This Account".equals(start), "the Account switch does not start on This Account: " + start);
                c.check("ALL_ACCOUNTS".equals(picked) && afterPick.contains("All Accounts"),
                        "Account switch went to " + picked + ": " + afterPick);
                c.ctx().runOnClient(mc -> {
                    try {
                        pressSecondary(button(McCompat.screen(mc), "All Accounts")); // back to This Account
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                });
                Object back = c.onClient(mc -> String.valueOf(Mod.call(Mod.cfg("gui.profit.ProfitConfig"), "getShow")));
                c.check("THIS_ACCOUNT".equals(back), "right click did not step back to This Account: " + back);
                c.note("Account switch: " + afterPick + "; right click -> This Account");
                c.ctx().runOnClient(mc -> {
                    try {
                        press(button(McCompat.screen(mc), "All Profit"));
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                });
            } finally {
                if (pcfgBefore == null) {
                    Files.deleteIfExists(pcfgPath);
                } else {
                    Files.write(pcfgPath, pcfgBefore);
                }
            }

            // Reset: first press arms it red, wait it out (back to normal), arm again, second press resets.
            boolean[] states = new boolean[4];
            String[] armedLabel = new String[1];
            c.ctx().runOnClient(mc -> {
                try {
                    Screen s = McCompat.screen(mc);
                    AbstractWidget r = button(s, "Reset Totals");
                    states[0] = danger(r);
                    press(r);
                    AbstractWidget armed = button(McCompat.screen(mc), "Reset ");
                    states[1] = danger(armed);
                    armedLabel[0] = armed.getMessage().getString();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            c.check(!states[0] && states[1], "Reset Totals before " + states[0] + " / after first press " + states[1]
                    + " red, label '" + armedLabel[0] + "'");
            c.check(armedLabel[0].startsWith("Reset All Profit"), "armed label '" + armedLabel[0] + "'");
            c.ctx().waitTicks(80); // > 3 s
            AbstractWidget timedOut = c.onClient(mc -> button(McCompat.screen(mc), "Reset Totals"));
            c.check(timedOut != null && !danger(timedOut), "the armed reset never turned back after 3 s: "
                    + c.onClient(mc -> labels(McCompat.screen(mc))));
            long kept = sessions(filter(null, null, null));
            c.check(kept == 1, "waiting it out reset anyway: " + kept + " sessions");
            c.ctx().runOnClient(mc -> {
                try {
                    press(button(McCompat.screen(mc), "Reset Totals"));
                    press(button(McCompat.screen(mc), "Reset "));
                    states[2] = danger(button(McCompat.screen(mc), "Reset Totals"));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            long left = sessions(filter(null, null, null));
            c.check(left == 0 && !states[2], "second press: " + left + " sessions left, button red " + states[2]);
            c.note("reset: plain -> red '" + armedLabel[0] + "' -> back after 3 s (kept " + kept + ") -> confirm cleared to "
                    + left);

            // Dungeon Profit screen: the same red confirm in its header, and the switches.
            c.ctx().runOnClient(mc -> {
                try {
                    Object overview = Mod.enumValue("croesus.CroesusTrackerScreen$View", "OVERVIEW");
                    Screen s = (Screen) Mod.cls("croesus.CroesusTrackerScreen")
                            .getConstructor(Screen.class, Mod.cls("croesus.CroesusTrackerScreen$View"))
                            .newInstance(null, overview);
                    McCompat.setScreen(mc, s);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            c.ctx().waitTicks(3);
            String cro = c.onClient(mc -> labels(McCompat.screen(mc)));
            c.ctx().runOnClient(mc -> {
                try {
                    press(button(McCompat.screen(mc), "Reset Totals"));
                    states[3] = danger(button(McCompat.screen(mc), "Reset "));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            c.note("dungeon screen buttons: " + cro + "; armed red " + states[3]);
            c.check(cro.contains("All Profit") && cro.contains("Mod Profile") && states[3],
                    "dungeon screen: switches or red reset missing: " + cro);
        } finally {
            c.ctx().runOnClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.staticCall("gui.profit.ProfitConfig", "load"); // its file was put back above
            });
            MenuKit.reset(c);
            c.ctx().runOnClient(mc -> Mod.staticCall(PROFIT, "reset"));
        }
    }

    // ---- 734 -------------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static void tab(Session c) throws Exception {
        List<String> found = new ArrayList<>();
        int[] geo = new int[6];
        c.ctx().runOnClient(mc -> {
            try {
                Object tab = Mod.cls("gui.tab.ExperimentsTab").getConstructor().newInstance();
                List<AbstractWidget> ws = (List<AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0, 300,
                        (Runnable) () -> { });
                for (AbstractWidget w : ws) {
                    String t = w.getMessage().getString().replaceAll("§.", "");
                    found.add(t);
                    if (t.startsWith("Profit Tracker")) {
                        geo[0] = w.getX();
                        geo[1] = w.getY();
                        geo[2] = w.getWidth();
                    } else if (t.equals("Open /profit")) {
                        geo[3] = w.getX();
                        geo[4] = w.getY();
                        geo[5] = w.getWidth();
                    }
                }
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        c.note("Experiments tab rows: " + found + "; Profit Tracker at " + geo[0] + "," + geo[1] + " w" + geo[2]
                + ", Open /profit at " + geo[3] + "," + geo[4] + " w" + geo[5]);
        String joined = String.join("|", found);
        c.check(!joined.contains("Reset Profit Totals") && !joined.contains("Click again"), "reset button still there");
        c.check(!joined.contains("Sessions") && !joined.contains("Reward value") && !joined.contains("Last:"),
                "totals text still there");
        c.check(found.contains("Open /profit"), "no Open /profit button");
        c.check(geo[4] == geo[1] && geo[3] == geo[0] + geo[2] + 6 && geo[5] == geo[2],
                "Open /profit is not where the reset was (right half beside Profit Tracker)");
    }

    // ---- 735 -------------------------------------------------------------------------------------------------------

    static void superpairsEscape(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c); AutoCloseable armed = ExperimentCases.autoConfig(c, cfg)) {
            cfg.set(CONFIG, "AutoSwapGuardianPet", false);
            JsonObject st = c.hx().call("menu.experiment", "game", "SUPERPAIRS", "seed", 31, "pairs", 6, "clicks", 4,
                    "holdOnOut", true).getAsJsonObject();
            int board = MenuKit.awaitScreen(c, st.get("title").getAsString(), 100);
            if (!MenuKit.cheat()) {
                c.ctx().waitTicks(160);
                int clicks = c.events("container.click").size();
                int closes = c.events("container.close").size();
                c.check(clicks == 0 && closes == 0, "legit jar sent " + clicks + " click(s), " + closes + " close(s)");
                c.note("legit jar: 0 clicks, 0 closes in 160 ticks on a Superpairs board with Auto E-Table armed");
                return;
            }
            JsonObject over = MenuKit.awaitEvent(c, "experiment.over", 1200);
            String reason = over.get("reason").getAsString();
            c.check(reason.equals("out of clicks"), "the board ended with '" + reason + "', want out of clicks");
            long overTick = over.get("tick").getAsLong();
            int boardId = c.events("experiment.opened").isEmpty() ? board
                    : c.events("experiment.opened").get(0).has("containerId")
                    ? c.events("experiment.opened").get(0).get("containerId").getAsInt() : board;
            c.waitUntil("the Escape on the finished board", mc -> !c.events("container.close").isEmpty(), 200);
            c.ctx().waitTicks(60);
            List<JsonObject> closes = c.events("container.close");
            long late = c.events("container.click").stream().filter(e -> e.get("tick").getAsLong() > overTick).count();
            String screenNow = c.onClient(MenuKit::screenTitle);
            c.note("Superpairs out of clicks after " + over.get("clicks") + " clicks; then " + closes.size()
                    + " close(s) " + closes + ", " + late + " click(s) after the end, screen now '" + screenNow + "'");
            c.check(closes.size() == 1, closes.size() + " closes, want exactly one Escape");
            c.check(closes.get(0).get("containerId").getAsInt() == boardId || boardId < 0,
                    "the close was not for the board " + boardId);
            c.check(late == 0, late + " click(s) on the finished board");
            // Hypixel answers the Escape with the rewards: the claim still follows.
            MenuKit.show(c, MenuKit.menu("menus.experiment-over-chronomatron"));
            c.waitUntil("the claim click on the reward screen", mc -> c.events("menu.action").stream()
                    .anyMatch(e -> e.getAsJsonObject("action").has("chat")), 400);
            c.note("rewards claimed after the Escape");
        } finally {
            MenuKit.reset(c);
        }
    }

    // ---- 736 -------------------------------------------------------------------------------------------------------

    private static String reward(String name, String line) {
        return "minecraft:paper[custom_name={text:\"" + name + "\",italic:false},lore=[{text:\"" + line
                + "\",italic:false},{text:\"\",italic:false},{text:\"Click to select!\",italic:false}]]";
    }

    static void rngLate(Session c) throws Exception {
        MenuKit.reset(c);
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        String group = "Testkit Late RNG Meter";
        try {
            c.ctx().runOnClient(mc -> Mod.staticCall("util.SkyblockGate", "setEnabled", false));
            JsonObject spec = MenuKit.obj("{\"title\":\"(1/2) " + group + "\",\"rows\":6,\"fill\":true,\"slots\":{}}");
            spec.getAsJsonObject("slots").addProperty("10", reward("Testkit Reward A", "Experimental XP: 1,200/150k"));
            MenuKit.show(c, spec);
            MenuKit.awaitScreen(c, "(1/2) " + group, 100);
            c.ctx().waitTicks(30); // well past the three-frame first commit
            Map<?, ?> first = c.onClient(mc -> Map.copyOf((Map<?, ?>) Mod.staticCall("rngmeter.RngItemLog", "get", group)));
            c.check(first.containsKey("Testkit Reward A"), "the first reward was never read: " + first);
            // A reward the server sends after the page was first read (same window, same title).
            JsonObject set = new JsonObject();
            set.addProperty("slot", 11);
            set.addProperty("stack", reward("Testkit Reward B", "Experimental XP: 1.2M/5M"));
            c.hx().call("menu.set", set);
            c.waitUntil("the late reward in the RNG log", mc ->
                    ((Map<?, ?>) Mod.staticCall("rngmeter.RngItemLog", "get", group)).containsKey("Testkit Reward B"), 60);
            Map<?, ?> log = c.onClient(mc -> Map.copyOf((Map<?, ?>) Mod.staticCall("rngmeter.RngItemLog", "get", group)));
            int shown = c.onClient(mc -> ((Number) Mod.staticCall("rngmeter.RngMeterOverlay", "committedCount")).intValue());
            c.note("RNG log for '" + group + "': " + log + "; overlay rows " + shown);
            c.check(((Number) log.get("Testkit Reward A")).longValue() == 150_000L
                    && ((Number) log.get("Testkit Reward B")).longValue() == 5_000_000L, "required scores wrong: " + log);
            c.check(shown >= 2, "the overlay shows " + shown + " rewards, want both");
        } finally {
            MenuKit.reset(c);
            c.ctx().runOnClient(mc -> Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas));
        }
    }
}

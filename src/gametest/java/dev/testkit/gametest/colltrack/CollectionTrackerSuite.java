package dev.testkit.gametest.colltrack;

import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.TestMap;
import dev.testkit.gametest.hx.Session;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Collection Tracker (mod colltrack-1104; killer560, 2026-11-04: "add a collection tracker. you select what collections you
 * want and it shows up on your hud"). Two picks, Wheat and Mithril, against the bundled Hypixel list (the real tiers):
 * <ul>
 *   <li>1104-colltrack-list: the bundled list reads 90 collections in 6 categories.</li>
 *   <li>1104-colltrack-hud: a fake {@code /v2/skyblock/profiles} body (selected co-op profile, two members, plus an
 *       unselected profile with other numbers) puts Wheat at 12,000 (VII, 80.0% to VIII) and Mithril at 3,000 (III, 30.0%
 *       to IV) on the HUD, which is drawn.</li>
 *   <li>1104-colltrack-sacks: a [Sacks] line's hover raises Wheat by 100, ignores Enchanted Wheat and an unpicked Carrot,
 *       and the same hover on a non-sack line does nothing.</li>
 *   <li>1104-colltrack-pickup: 10 Wheat given into the inventory raise it by 10; putting them into the sack (inventory
 *       loss, then "+10 Wheat") is not counted again.</li>
 *   <li>1104-colltrack-persist: the switch, Show Rate and both picks survive save and load.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class CollectionTrackerSuite implements FabricClientGameTest {

    public static final String SESSION = "1104-colltrack-session";

    private static final String CT = "com.killer560.hub.collectiontracker.CollectionTracker";
    private static final String CFG = "com.killer560.hub.collectiontracker.CollectionTrackerConfig";
    private static final String GATE = "com.killer560.hub.util.SkyblockGate";
    private static final Path SHOTS = Path.of(System.getProperty("user.home"), "killer560s-mod-logs", "colltrack-1104");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        Session.run(ctx, SESSION,
                (server, s) -> TestMap.on(server)
                        .platform(-760, 150, -760, 6)
                        .catchFloor(140)
                        .survival()
                        .clearInventory()
                        .spawn(-759.5, -759.5, 0f)
                        .build(),
                s -> {
                    ModUnderTest.require("killer560smod");
                    s.test("1104-colltrack-list", CollectionTrackerSuite::list);
                    s.test("1104-colltrack-hud", CollectionTrackerSuite::hud);
                    s.test("1104-colltrack-sacks", CollectionTrackerSuite::sacks);
                    s.test("1104-colltrack-pickup", CollectionTrackerSuite::pickup);
                    s.test("1109-colltrack-recent", CollectionTrackerSuite::recent);
                    s.test("1104-colltrack-persist", CollectionTrackerSuite::persist);
                    s.test("1116-colltrack-firstload", CollectionTrackerSuite::firstLoad);
                    s.test("1116-colltrack-rate", CollectionTrackerSuite::rate);
                    s.test("1117-colltrack-compact-replay", CollectionTrackerSuite::compactReplay);
                    s.test("1116-colltrack-screen", CollectionTrackerSuite::screen);
                    s.test("1116-colltrack-enchanted", CollectionTrackerSuite::enchanted);
                    s.test("1116-colltrack-once", CollectionTrackerSuite::once);
                    s.test("1116-colltrack-hide-empty", CollectionTrackerSuite::hideEmpty);
                });
    }

    // ------------------------------------------------------------------ cases

    private static void list(Session c) {
        int n = c.onClient(mc -> {
            try {
                return (Integer) ModUnderTest.staticCall(CT, "bundledCountForTest");
            } catch (RuntimeException e) {
                throw e;
            }
        });
        c.waitUntil("the collection list is loaded", mc -> ModUnderTest.staticCall(CT, "catalog") != null, 100);
        int cats = c.onClient(mc -> ((List<?>) ModUnderTest.call(ModUnderTest.staticCall(CT, "catalog"), "categories",
                new Class<?>[0], new Object[0])).size());
        c.note("bundled list: " + n + " collections, " + cats + " categories");
        c.check(n == 90, "the bundled list should hold 90 collections, read " + n);
        c.check(cats == 6, "the bundled list should hold 6 categories, read " + cats);
    }

    private static void hud(Session c) throws Exception {
        setUp(c);
        String self = c.onClient(mc -> mc.getUser().getProfileId().toString().replace("-", ""));
        String coop = "0123456789abcdef0123456789abcdef";
        String body = "{\"success\":true,\"profiles\":["
                + "{\"profile_id\":\"p1\",\"cute_name\":\"Banana\",\"selected\":false,\"members\":{\"" + self
                + "\":{\"collection\":{\"WHEAT\":99999,\"MITHRIL_ORE\":99999}}}},"
                + "{\"profile_id\":\"p2\",\"cute_name\":\"Apple\",\"selected\":true,\"members\":{\"" + self
                + "\":{\"collection\":{\"WHEAT\":11500,\"MITHRIL_ORE\":3000,\"CARROT_ITEM\":50}},\"" + coop
                + "\":{\"collection\":{\"WHEAT\":500}}}}]}";
        @SuppressWarnings("unchecked")
        Map<String, Long> read = c.onClient(mc -> (Map<String, Long>) ModUnderTest.staticCall(CT, "applyProfilesForTest",
                new Class<?>[]{String.class, UUID.class}, new Object[]{body, mc.getUser().getProfileId()}));
        c.note("profile amounts read: " + read);
        c.waitUntil("the profile amounts reach the tracker", mc -> amount("WHEAT") == 12000 && amount("MITHRIL_ORE") == 3000, 100);
        c.ctx().waitTicks(10);
        List<String> lines = lines(c);
        c.note("HUD: " + lines);
        c.check(lines.size() == 3 && lines.get(0).equals("Collections"), "the HUD should be a header and two lines: " + lines);
        c.check(lines.contains("Wheat VII: 12,000 (80.0% to VIII) +0/h"), "Wheat line wrong: " + lines);
        c.check(lines.contains("Mithril III: 3,000 (30.0% to IV) +0/h"), "Mithril line wrong: " + lines);
        c.check(c.onClient(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.hud.HudSeen", "drawnRecently",
                new Class<?>[]{String.class}, new Object[]{"collection_tracker"})), "the Collection Tracker HUD was not drawn");
        Path p = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-hud"));
        c.note("screenshot " + p);
        c.check(Files.exists(p), "no screenshot was written");
        Files.createDirectories(SHOTS);
        Files.copy(p, SHOTS.resolve(c.name() + "-hud.png"), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void sacks(Session c) throws Exception {
        long before = amount(c, "WHEAT");
        c.check(before == 12000, "Wheat should start at 12,000 (from the hud case), is " + before);
        String hover = "Added items:\\n  +100 Wheat (Farming Sack)\\n  +5 Enchanted Wheat (Farming Sack)\\n  +7 Carrot (Farming Sack)";
        // Not a sack line: same hover, ignored.
        c.server().command("tellraw @a {\"text\":\"Wheat is nice\",\"hover_event\":{\"action\":\"show_text\",\"value\":\"" + hover + "\"}}");
        c.server().command("tellraw @a {\"text\":\"[Sacks] +112 items. (Last 10s.)\",\"hover_event\":{\"action\":\"show_text\",\"value\":\""
                + hover + "\"}}");
        c.waitUntil("the sack line raises Wheat", mc -> amount("WHEAT") != 12000, 100);
        c.ctx().waitTicks(10);
        long after = amount(c, "WHEAT");
        c.note("Wheat " + before + " -> " + after + "; HUD " + lines(c));
        // colltrack-rate-1116: an enchanted form counts as the base it is made of (5 Enchanted Wheat = 800 Wheat); before, it was ignored.
        c.check(after == 12900, "a [Sacks] hover of +100 Wheat and +5 Enchanted Wheat should raise Wheat to 12,900 (Carrot not picked, ignored), is " + after);
        c.check(amount(c, "MITHRIL_ORE") == 3000, "Mithril moved on a Wheat sack line");
        c.check(lines(c).stream().anyMatch(l -> l.startsWith("Wheat VII: 12,900 (86.0% to VIII) +")), "the HUD did not follow the sack line: " + lines(c));
    }

    private static void pickup(Session c) {
        c.ctx().waitTicks(30); // one inventory scan with no Wheat first
        long before = amount(c, "WHEAT");
        c.server().command("give @a minecraft:wheat[custom_data={id:\"WHEAT\"}] 10");
        c.waitUntil("the pickup raises Wheat", mc -> amount("WHEAT") != before, 100);
        long got = amount(c, "WHEAT");
        c.check(got == before + 10, "10 Wheat picked up should add 10, " + before + " -> " + got);
        // Into the sack: the inventory loses them, then Hypixel says +10 Wheat. Counted once.
        c.server().command("clear @a minecraft:wheat");
        c.ctx().waitTicks(30);
        c.server().command("tellraw @a {\"text\":\"[Sacks] +10 items. (Last 5s.)\",\"hover_event\":{\"action\":\"show_text\",\"value\":"
                + "\"Added items:\\n  +10 Wheat (Farming Sack)\"}}");
        c.ctx().waitTicks(30);
        long end = amount(c, "WHEAT");
        c.note("Wheat " + before + " -> " + got + " (pickup) -> " + end + " (into the sack)");
        c.check(end == got, "Wheat put into the sack was counted again: " + got + " -> " + end);
    }

    /** Only Recent (colrecent-1109): window 10 s; Wheat gains, Mithril gains 5 s later; at 11 s only Mithril is listed; Wheat returns on its next gain. */
    private static void recent(Session c) {
        setUp(c); // picks Wheat and Mithril itself, so the case also runs alone

        Class<?>[] bool = {boolean.class};
        c.onClient(mc -> {
            Object cfg = ModUnderTest.config(CFG);
            ModUnderTest.call(cfg, "setRecentSeconds", new Class<?>[]{int.class}, new Object[]{10});
            ModUnderTest.call(cfg, "setOnlyRecent", bool, new Object[]{true});
            ModUnderTest.call(cfg, "save", new Class<?>[0], new Object[0]);
            ModUnderTest.staticCall(CFG, "load");
            return null;
        });
        String saved = c.onClient(mc -> {
            Object cfg = ModUnderTest.config(CFG);
            return ModUnderTest.getBoolean(cfg, "isOnlyRecent") + " " + ModUnderTest.call(cfg, "getRecentSeconds", new Class<?>[0], new Object[0]);
        });
        c.check(saved.equals("true 10"), "Only Recent and its window did not survive save and load: " + saved);
        c.ctx().waitTicks(210); // the earlier cases just gained Wheat; let the 10 s window pass first
        c.check(!lines(c).toString().contains("Wheat") && !lines(c).toString().contains("Mithril"),
                "Only Recent listed a pick with no gain yet: " + lines(c));
        sack(c, "+100 Wheat (Farming Sack)");
        c.ctx().waitTicks(20);
        c.note("20 ticks after the Wheat sack line: " + lines(c) + " wheat=" + amount(c, "WHEAT"));
        c.waitUntil("Wheat listed after its gain",mc -> linesIn().toString().contains("Wheat"), 100);
        c.ctx().waitTicks(100);
        sack(c, "+5 Mithril (Mining Sack)");
        c.waitUntil("Mithril listed after its gain", mc -> linesIn().toString().contains("Mithril"), 100);
        c.check(lines(c).toString().contains("Wheat"), "Wheat should still be listed 5 s after its gain: " + lines(c));
        c.waitUntil("Wheat drops off", mc -> !linesIn().toString().contains("Wheat"), 160);
        String both = lines(c).toString();
        c.note("after Wheat's window: " + both);
        c.check(both.contains("Mithril"), "Mithril (gained 5 s later) should still be listed: " + both);
        sack(c, "+100 Wheat (Farming Sack)");
        c.waitUntil("Wheat returns on its next gain", mc -> linesIn().toString().contains("Wheat"), 100);
        c.onClient(mc -> {
            Object cfg = ModUnderTest.config(CFG);
            ModUnderTest.call(cfg, "setOnlyRecent", bool, new Object[]{false});
            ModUnderTest.call(cfg, "setRecentSeconds", new Class<?>[]{int.class}, new Object[]{60});
            ModUnderTest.call(cfg, "save", new Class<?>[0], new Object[0]);
            return null;
        });
    }

    /** colltrack-hide-1116: Only Recent with nothing gained draws nothing in game (no title, no line), shows on a gain, hides again after the window; the HUD editor still previews it. */
    private static void hideEmpty(Session c) throws Exception {
        setUp(c);
        c.onClient(mc -> {
            Object cfg = ModUnderTest.config(CFG);
            ModUnderTest.call(cfg, "setRecentSeconds", new Class<?>[]{int.class}, new Object[]{10});
            ModUnderTest.call(cfg, "setOnlyRecent", new Class<?>[]{boolean.class}, new Object[]{true});
            return null;
        });
        long until = System.currentTimeMillis() + 12_000; // earlier cases gained Wheat: let any 10 s window pass on the real clock
        while (System.currentTimeMillis() < until) {
            c.ctx().waitTicks(20);
        }
        List<String> idle = lines(c);
        c.check(idle.isEmpty(), "Only Recent with nothing gained should draw nothing in game, but the HUD lists: " + idle);
        c.check(hudHeight(c) == 0 && hudWidth(c) == 0, "the empty HUD should have no size: " + hudWidth(c) + "x" + hudHeight(c));
        sack(c, "+100 Wheat (Farming Sack)");
        c.waitUntil("the HUD returns on a gain", mc -> linesIn().toString().contains("Wheat"), 100);
        c.check(lines(c).get(0).equals("Collections") && hudHeight(c) > 0, "after a gain the HUD should list its title and Wheat: " + lines(c));
        long gainedAt = System.currentTimeMillis();
        while (System.currentTimeMillis() < gainedAt + 11_000) {
            c.ctx().waitTicks(20);
        }
        c.waitUntil("the HUD hides again after the window", mc -> linesIn().isEmpty(), 100);
        c.onClient(mc -> {
            try {
                net.minecraft.client.gui.screens.Screen editor = (net.minecraft.client.gui.screens.Screen) Class.forName("com.killer560.hub.hud.HudEditorScreen")
                        .getConstructor(net.minecraft.client.gui.screens.Screen.class).newInstance((Object) null);
                dev.testkit.compat.McCompat.setScreen(mc, editor);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            return null;
        });
        c.ctx().waitTicks(5);
        List<String> edit = lines(c);
        c.check(edit.size() == 3 && edit.get(0).equals("Collections"), "the HUD editor should still preview the title and both picks while nothing was gained: " + edit);
        c.onClient(mc -> {
            dev.testkit.compat.McCompat.setScreen(mc, null);
            Object cfg = ModUnderTest.config(CFG);
            ModUnderTest.call(cfg, "setOnlyRecent", new Class<?>[]{boolean.class}, new Object[]{false});
            ModUnderTest.call(cfg, "setRecentSeconds", new Class<?>[]{int.class}, new Object[]{60});
            return null;
        });
    }

    private static int hudHeight(Session c) {
        return c.onClient(mc -> hudInt("height"));
    }

    private static int hudWidth(Session c) {
        return c.onClient(mc -> hudInt("width"));
    }

    private static int hudInt(String method) {
        try {
            var f = Class.forName(CT + "$Hud").getDeclaredField("INSTANCE");
            f.setAccessible(true);
            return (Integer) Class.forName(CT + "$Hud").getMethod(method).invoke(f.get(null));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void sack(Session c, String item) {
        c.server().command("tellraw @a {\"text\":\"[Sacks] +100 items. (Last 5s.)\",\"hover_event\":{\"action\":\"show_text\",\"value\":"
                + "\"Added items:\\n  " + item + "\"}}");
    }

    private static void persist(Session c) {
        c.onClient(mc -> {
            Object cfg = ModUnderTest.config(CFG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setShowRate", false);
            ModUnderTest.call(cfg, "save", new Class<?>[0], new Object[0]);
            ModUnderTest.call(cfg, "setSelected", new Class<?>[]{String.class, boolean.class}, new Object[]{"WHEAT", false});
            ModUnderTest.set(cfg, "setEnabled", false);
            ModUnderTest.staticCall(CFG, "load");
            return null;
        });
        String got = c.onClient(mc -> {
            Object cfg = ModUnderTest.config(CFG);
            return ModUnderTest.getBoolean(cfg, "isEnabledRaw") + " " + ModUnderTest.getBoolean(cfg, "isShowRate") + " "
                    + ModUnderTest.call(cfg, "getSelected", new Class<?>[0], new Object[0]);
        });
        c.note("after save, change, load: " + got);
        c.check(got.equals("true false [WHEAT, MITHRIL_ORE]"), "the settings did not survive save and load: " + got);
        c.check(amount(c, "WHEAT") != Long.MIN_VALUE, "Wheat is no longer tracked after the load");
        // Leave it off for the cases after this suite.
        c.onClient(mc -> {
            Object cfg = ModUnderTest.config(CFG);
            ModUnderTest.set(cfg, "setEnabled", false);
            ModUnderTest.set(cfg, "setShowRate", true);
            ModUnderTest.call(cfg, "save", new Class<?>[0], new Object[0]);
            ModUnderTest.staticCall(GATE, "setEnabled", new Class<?>[]{boolean.class}, new Object[]{true});
            return null;
        });
    }

    /**
     * The first read of a saved file, with no instance stored yet (a fresh game). On 2026-11-16 that read recursed
     * (load, setRecentSeconds, selectionChanged, getInstance, load ...) and crashed his game with a StackOverflowError;
     * every other case here calls load() while an instance exists, which hid it.
     */
    private static void firstLoad(Session c) {
        String got = c.onClient(mc -> {
            Object cfg = ModUnderTest.config(CFG);
            ModUnderTest.call(cfg, "setRecentSeconds", new Class<?>[]{int.class}, new Object[]{120});
            ModUnderTest.call(cfg, "save", new Class<?>[0], new Object[0]);
            try {
                java.lang.reflect.Field f = Class.forName(CFG).getDeclaredField("instance");
                f.setAccessible(true);
                f.set(null, null);
                Object fresh;
                try {
                    fresh = ModUnderTest.config(CFG);
                } catch (StackOverflowError e) {
                    return "StackOverflowError";
                }
                String out = String.valueOf(ModUnderTest.call(fresh, "getRecentSeconds", new Class<?>[0], new Object[0]));
                ModUnderTest.call(fresh, "setRecentSeconds", new Class<?>[]{int.class}, new Object[]{60});
                ModUnderTest.call(fresh, "save", new Class<?>[0], new Object[0]);
                return out;
            } catch (ReflectiveOperationException e) {
                return "reflection failed: " + e;
            }
        });
        c.note("first read of a saved file with no instance: recentSeconds " + got);
        c.check(got.equals("120"), "the first read of the saved settings did not return them: " + got);
    }

    // ------------------------------------------------------------------ colltrack-rate-1116

    private static final String GOLD = "GOLD_INGOT";
    private static final String GIVE_GOLD = "give @a minecraft:gold_ingot[custom_data={id:\"GOLD_INGOT\"}] ";

    /** Gold alone picked, no scan history, the sack/inventory state clean, a 10 s Only Recent window. */
    private static void goldUp(Session c) {
        c.server().command("clear @a");
        c.onClient(mc -> {
            ModUnderTest.staticCall(GATE, "setEnabled", new Class<?>[]{boolean.class}, new Object[]{false});
            Object cfg = ModUnderTest.config(CFG);
            for (Object id : List.copyOf((List<?>) ModUnderTest.call(cfg, "getSelected", new Class<?>[0], new Object[0]))) {
                ModUnderTest.call(cfg, "setSelected", new Class<?>[]{String.class, boolean.class}, new Object[]{id, false});
            }
            ModUnderTest.call(cfg, "setSelected", new Class<?>[]{String.class, boolean.class}, new Object[]{GOLD, true});
            ModUnderTest.call(cfg, "setRecentSeconds", new Class<?>[]{int.class}, new Object[]{10});
            ModUnderTest.call(cfg, "setOnlyRecent", new Class<?>[]{boolean.class}, new Object[]{true});
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setShowRate", true);
            ModUnderTest.staticCall(CT, "resetForTest");
            return null;
        });
        c.ctx().waitTicks(30); // one scan with the clean inventory as the baseline
    }

    private static void goldDown(Session c) {
        c.onClient(mc -> {
            Object cfg = ModUnderTest.config(CFG);
            ModUnderTest.call(cfg, "setOnlyRecent", new Class<?>[]{boolean.class}, new Object[]{false});
            ModUnderTest.call(cfg, "setRecentSeconds", new Class<?>[]{int.class}, new Object[]{60});
            ModUnderTest.call(cfg, "save", new Class<?>[0], new Object[0]);
            ModUnderTest.set(cfg, "setEnabled", false);
            ModUnderTest.staticCall(GATE, "setEnabled", new Class<?>[]{boolean.class}, new Object[]{true});
            return null;
        });
    }

    private static long gained(Session c) {
        return c.onClient(mc -> (Long) ModUnderTest.staticCall(CT, "gainedForTest", new Class<?>[]{String.class}, new Object[]{GOLD}));
    }

    private static long rateOf(Session c, int window) {
        return c.onClient(mc -> (Long) ModUnderTest.staticCall(CT, "rateForTest", new Class<?>[]{String.class, int.class},
                new Object[]{GOLD, window}));
    }

    /** Waits on the real clock (ticks stretch under load). */
    private static void waitMs(Session c, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            c.ctx().waitTicks(5);
        }
    }

    private static void waitGained(Session c, long want) {
        c.waitUntil("gained reaches " + want, mc -> gainedNow() == want, 200);
    }

    private static long gainedNow() {
        return (Long) ModUnderTest.staticCall(CT, "gainedForTest", new Class<?>[]{String.class}, new Object[]{GOLD});
    }

    /**
     * The rate is the gain over the recent window, not the session average: 100 gold, then 300 six seconds later (a faster
     * stretch) reads 400 over the window; after the first 100 has left the window only the 300 is counted; after a whole idle
     * window it reads 0; the next gain reads from that gain alone. The 10 s window is Only Recent's seconds.
     */
    private static void rate(Session c) {
        goldUp(c);
        sack(c, "+100 Gold Ingot (Mining Sack)");
        waitGained(c, 100);
        long t0 = System.currentTimeMillis();
        long r1 = rateOf(c, 10);
        c.note("100 gold: rate " + r1);
        c.check(r1 == 100 * 360L, "100 gold in a 10 s window should read 36,000/h, read " + r1);
        waitMs(c, 6000);
        sack(c, "+300 Gold Ingot (Mining Sack)");
        waitGained(c, 400);
        long r2 = rateOf(c, 10);
        c.note("400 gold: rate " + r2);
        c.check(r2 == 400 * 360L, "400 gold inside the window should read 144,000/h, read " + r2);
        waitMs(c, t0 + 11500 - System.currentTimeMillis());
        long r3 = rateOf(c, 10);
        c.note("first 100 left the window: rate " + r3);
        c.check(r3 == 300 * 360L, "after the first 100 left the window only the 300 counts (108,000/h), read " + r3);
        waitMs(c, 11000);
        long r4 = rateOf(c, 10);
        c.check(r4 == 0, "an idle window should read 0/h, not a decaying average: " + r4);
        sack(c, "+50 Gold Ingot (Mining Sack)");
        waitGained(c, 450);
        long r5 = rateOf(c, 10);
        c.check(r5 == 50 * 360L, "the next gain reads from that gain alone (18,000/h), read " + r5);
        goldDown(c);
    }

    /** One gold mining session's [Sacks] messages in order (recorded 2026-11-17, counts only): each hover line is "+/-n Item (Sack)". */
    private static final String[][] GOLD_SESSION = {
        {"+1 Enchanted Gold Ingot (Enchanted Mining Sack)", "+20122 Gold Ingot (Enchanted Mining Sack)"},
        {"+94 Enchanted Gold Block (Enchanted Mining Sack)", "+106 Enchanted Gold Ingot (Enchanted Mining Sack)", "+1696 Gold Ingot (Enchanted Mining Sack)", "-15040 Enchanted Gold Ingot (Enchanted Mining Sack)", "-16800 Gold Ingot (Enchanted Mining Sack)"},
        {"+40 Enchanted Gold Block (Enchanted Mining Sack)", "+6401 Enchanted Gold Ingot (Enchanted Mining Sack)", "+1038944 Gold Ingot (Enchanted Mining Sack)", "-6400 Enchanted Gold Ingot (Enchanted Mining Sack)", "-1023840 Gold Ingot (Enchanted Mining Sack)"},
        {"+68 Enchanted Gold Block (Enchanted Mining Sack)", "+10810 Enchanted Gold Ingot (Enchanted Mining Sack)", "+1729600 Gold Ingot (Enchanted Mining Sack)", "-10880 Enchanted Gold Ingot (Enchanted Mining Sack)", "-1729600 Gold Ingot (Enchanted Mining Sack)"},
        {"+29 Enchanted Gold Block (Enchanted Mining Sack)", "+4700 Enchanted Gold Ingot (Enchanted Mining Sack)", "+736960 Gold Ingot (Enchanted Mining Sack)", "-4640 Enchanted Gold Ingot (Enchanted Mining Sack)", "-752000 Gold Ingot (Enchanted Mining Sack)"},
        {"+61 Enchanted Gold Block (Enchanted Mining Sack)", "+9776 Enchanted Gold Ingot (Enchanted Mining Sack)", "+1579200 Gold Ingot (Enchanted Mining Sack)", "-9760 Enchanted Gold Ingot (Enchanted Mining Sack)", "-1564160 Gold Ingot (Enchanted Mining Sack)"},
        {"+45 Enchanted Gold Block (Enchanted Mining Sack)", "+7114 Enchanted Gold Ingot (Enchanted Mining Sack)", "+1123233 Gold Ingot (Enchanted Mining Sack)", "-7200 Enchanted Gold Ingot (Enchanted Mining Sack)", "-1138240 Gold Ingot (Enchanted Mining Sack)"},
        {"+1 Enchanted Gold Block (Enchanted Mining Sack)", "+157 Enchanted Gold Ingot (Enchanted Mining Sack)", "+25140 Gold Ingot (Enchanted Mining Sack)", "-160 Enchanted Gold Ingot (Enchanted Mining Sack)", "-25120 Gold Ingot (Enchanted Mining Sack)"},
        {"+5 Enchanted Gold Block (Enchanted Mining Sack)", "+833 Enchanted Gold Ingot (Enchanted Mining Sack)", "+133610 Gold Ingot (Enchanted Mining Sack)", "-800 Enchanted Gold Ingot (Enchanted Mining Sack)", "-132480 Gold Ingot (Enchanted Mining Sack)"},
        {"+35 Enchanted Gold Ingot (Enchanted Mining Sack)", "+4425 Gold Ingot (Enchanted Mining Sack)", "-5600 Gold Ingot (Enchanted Mining Sack)"},
        {"+1 Enchanted Gold Block (Enchanted Mining Sack)", "+185 Enchanted Gold Ingot (Enchanted Mining Sack)", "+29440 Gold Ingot (Enchanted Mining Sack)", "-160 Enchanted Gold Ingot (Enchanted Mining Sack)", "-29440 Gold Ingot (Enchanted Mining Sack)"},
        {"+5 Enchanted Gold Block (Enchanted Mining Sack)", "+717 Enchanted Gold Ingot (Enchanted Mining Sack)", "+115790 Gold Ingot (Enchanted Mining Sack)", "-800 Enchanted Gold Ingot (Enchanted Mining Sack)", "-114400 Gold Ingot (Enchanted Mining Sack)"},
        {"+71 Enchanted Gold Ingot (Enchanted Mining Sack)", "+9890 Gold Ingot (Enchanted Mining Sack)", "-11360 Gold Ingot (Enchanted Mining Sack)"},
        {"+4 Enchanted Gold Block (Enchanted Mining Sack)", "+572 Enchanted Gold Ingot (Enchanted Mining Sack)", "+91005 Gold Ingot (Enchanted Mining Sack)", "-640 Enchanted Gold Ingot (Enchanted Mining Sack)", "-90880 Gold Ingot (Enchanted Mining Sack)"},
        {"+3 Enchanted Gold Block (Enchanted Mining Sack)", "+580 Enchanted Gold Ingot (Enchanted Mining Sack)", "+92290 Gold Ingot (Enchanted Mining Sack)", "-480 Enchanted Gold Ingot (Enchanted Mining Sack)", "-92320 Gold Ingot (Enchanted Mining Sack)"},
        {"+2 Enchanted Gold Block (Enchanted Mining Sack)", "+243 Enchanted Gold Ingot (Enchanted Mining Sack)", "+38800 Gold Ingot (Enchanted Mining Sack)", "-320 Enchanted Gold Ingot (Enchanted Mining Sack)", "-38880 Gold Ingot (Enchanted Mining Sack)"},
    };

    /**
     * colltrack-accuracy-1116: a sack that compacts prints the new form as a gain and the old one as a take-out in the same
     * message ("+94 Enchanted Gold Block ... -15,040 Enchanted Gold Ingot"). Only the block was counted, so 1,856 real gold
     * read as a 2.4M gain and the HUD rate ran at 300M/h while the real net gain was 6.77M for the whole session. Replays the
     * recorded messages: the tracker's gain must match the net (enchanted forms at base value, take-outs subtracted) within 2%.
     */
    private static void compactReplay(Session c) {
        goldUp(c);
        long t0 = System.currentTimeMillis();
        long truth = 0;
        long[] mult = {1, 160, 25600};
        for (int i = 0; i < GOLD_SESSION.length; i++) {
            StringBuilder hover = new StringBuilder("Added items:");
            long net = 0;
            for (String line : GOLD_SESSION[i]) {
                hover.append("\n  ").append(line);
                long n = Long.parseLong(line.substring(0, line.indexOf(' ')));
                String item = line.substring(line.indexOf(' ') + 1, line.lastIndexOf(" ("));
                net += n * (item.equals("Enchanted Gold Block") ? mult[2] : item.equals("Enchanted Gold Ingot") ? mult[1] : mult[0]);
            }
            truth += net;
            c.server().command("tellraw @a {\"text\":\"[Sacks] +1 items. (Last 5s.)\",\"hover_event\":{\"action\":\"show_text\",\"value\":\""
                    + hover + "\"}}");
            long want = truth;
            c.waitUntil("message " + (i + 1) + " reaches " + want, mc -> gainedNow() >= want * 98 / 100, 200);
            c.ctx().waitTicks(4);
            long got = gained(c);
            c.check(Math.abs(got - want) <= want * 2 / 100,
                    "after sack message " + (i + 1) + " the tracker gained " + got + ", the net of the messages is " + want);
            if (i == 1) {
                c.check(got == 20282 + 1856, "the compacting message (+94 blocks, -15,040 ingots, -16,800 gold) must add its net 1,856, total " + got);
            }
        }
        long rate = rateOf(c, 300);
        c.note("session net " + truth + ", tracker gained " + gained(c) + ", rate " + rate);
        c.check(truth == 6773185, "the replay table changed: net " + truth);
        long secs = (System.currentTimeMillis() - t0) / 1000;
        c.check(gained(c) > 0 && (secs >= 25 ? rate > 0 : Math.abs(rate - gained(c) * 120) <= gained(c) * 120 / 50),
                "the rate should be the gain over the 30 s minimum window (" + gained(c) * 120 + "/h), read " + rate + " after " + secs + " s");
        goldDown(c);
    }

    /** Gains while a container screen is open count; moving items between the menu and the inventory does not. */
    private static net.minecraft.world.SimpleContainer BOX;

    private static void screen(Session c) {
        goldUp(c);
        // A menu holding 64 gold: its filling up is no gain.
        c.onClient(mc -> {
            net.minecraft.world.SimpleContainer box = BOX = new net.minecraft.world.SimpleContainer(27);
            box.setItem(0, gold("GOLD_INGOT", 64));
            net.minecraft.world.inventory.ChestMenu menu = net.minecraft.world.inventory.ChestMenu.threeRows(7, mc.player.getInventory(), box);
            dev.testkit.compat.McCompat.setScreen(mc, new net.minecraft.client.gui.screens.inventory.ContainerScreen(menu,
                    mc.player.getInventory(), net.minecraft.network.chat.Component.literal("Chest")));
            return null;
        });
        c.ctx().waitTicks(30);
        c.check(gained(c) == 0, "a menu filling with gold counted as a gain: " + gained(c));
        // Take it out by hand (menu slot -> inventory slot 20): a move, not a gain.
        c.onClient(mc -> {
            net.minecraft.world.inventory.AbstractContainerMenu menu = mc.player.containerMenu;
            BOX.setItem(0, net.minecraft.world.item.ItemStack.EMPTY);
            mc.player.getInventory().setItem(20, gold("GOLD_INGOT", 64));
            return null;
        });
        c.ctx().waitTicks(30);
        c.check(gained(c) == 0, "taking gold out of the open menu counted as a gain: " + gained(c));
        // A real pickup with the screen still open is counted.
        c.server().command(GIVE_GOLD + "10");
        c.waitUntil("a pickup with the screen open counts", mc -> gainedNow() > 0, 200);
        c.check(gained(c) == 10, "10 gold picked up with a screen open should add exactly 10, is " + gained(c));
        // Put the 64 back into the menu: not a loss that later cancels a sack line either way, and no gain.
        c.onClient(mc -> {
            mc.player.getInventory().setItem(20, net.minecraft.world.item.ItemStack.EMPTY);
            BOX.setItem(0, gold("GOLD_INGOT", 64));
            return null;
        });
        c.ctx().waitTicks(30);
        c.check(gained(c) == 10, "putting gold into the open menu changed the gain: " + gained(c));
        c.onClient(mc -> {
            dev.testkit.compat.McCompat.setScreen(mc, null);
            return null;
        });
        c.ctx().waitTicks(30);
        c.check(gained(c) == 10, "closing the menu changed the gain: " + gained(c));
        goldDown(c);
    }

    /** Enchanted Gold is 160 Gold Ingot, its block 25,600; compacting 160 ingots into one changes nothing. */
    private static void enchanted(Session c) {
        goldUp(c);
        c.server().command("give @a minecraft:gold_ingot[custom_data={id:\"ENCHANTED_GOLD\"}] 1");
        waitGained(c, 160);
        c.server().command("give @a minecraft:gold_block[custom_data={id:\"ENCHANTED_GOLD_BLOCK\"}] 1");
        waitGained(c, 160 + 25600);
        // 160 ingots picked up, then compacted into one Enchanted Gold in place (the compactor): counted once.
        c.server().command(GIVE_GOLD + "160");
        waitGained(c, 160 + 25600 + 160);
        c.onClient(mc -> {
            var inv = mc.player.getInventory();
            int first = -1;
            for (int i = 0; i < 36; i++) {
                var st = inv.getItem(i);
                if (!st.isEmpty() && st.is(net.minecraft.world.item.Items.GOLD_INGOT)
                        && st.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA) != null
                        && "GOLD_INGOT".equals(st.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag().getStringOr("id", ""))) {
                    first = first < 0 ? i : first;
                    inv.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
                }
            }
            if (first < 0) throw new IllegalStateException("no gold ingots to compact");
            inv.setItem(first, gold("ENCHANTED_GOLD", 1));
            return null;
        });
        c.ctx().waitTicks(30);
        c.check(gained(c) == 160 + 25600 + 160, "compacting 160 ingots into Enchanted Gold changed the gain: " + gained(c));
        sack(c, "+2 Enchanted Gold (Mining Sack)");
        c.ctx().waitTicks(40);
        c.check(gained(c) == 160 + 25600 + 160 + 320, "a sack line of 2 Enchanted Gold should add 320: " + gained(c));
        goldDown(c);
    }

    /** A sack line that explains an inventory loss is not a second gain, for plain and enchanted gold. */
    private static void once(Session c) {
        goldUp(c);
        c.server().command(GIVE_GOLD + "10");
        waitGained(c, 10);
        c.server().command("clear @a minecraft:gold_ingot");
        c.ctx().waitTicks(30);
        sack(c, "+10 Gold Ingot (Mining Sack)");
        c.ctx().waitTicks(30);
        c.check(gained(c) == 10, "gold put into the sack was counted twice: " + gained(c));
        c.server().command("give @a minecraft:gold_ingot[custom_data={id:\"ENCHANTED_GOLD\"}] 1");
        waitGained(c, 170);
        c.server().command("clear @a minecraft:gold_ingot");
        c.ctx().waitTicks(30);
        sack(c, "+1 Enchanted Gold (Mining Sack)");
        c.ctx().waitTicks(30);
        c.check(gained(c) == 170, "Enchanted Gold put into the sack was counted twice: " + gained(c));
        goldDown(c);
    }

    private static net.minecraft.world.item.ItemStack gold(String id, int n) {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("id", id);
        net.minecraft.world.item.ItemStack st = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_INGOT, n);
        st.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
        return st;
    }

    // ------------------------------------------------------------------ helpers

    private static void setUp(Session c) {
        c.onClient(mc -> {
            ModUnderTest.staticCall(GATE, "setEnabled", new Class<?>[]{boolean.class}, new Object[]{false});
            Object cfg = ModUnderTest.config(CFG);
            for (Object id : List.copyOf((List<?>) ModUnderTest.call(cfg, "getSelected", new Class<?>[0], new Object[0]))) {
                ModUnderTest.call(cfg, "setSelected", new Class<?>[]{String.class, boolean.class}, new Object[]{id, false});
            }
            for (String id : new String[]{"WHEAT", "MITHRIL_ORE"}) {
                ModUnderTest.call(cfg, "setSelected", new Class<?>[]{String.class, boolean.class}, new Object[]{id, true});
            }
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setShowRate", true);
            ModUnderTest.staticCall(CT, "resetForTest");
            return null;
        });
    }

    private static long amount(String id) {
        return (Long) ModUnderTest.staticCall(CT, "amountForTest", new Class<?>[]{String.class}, new Object[]{id});
    }

    private static long amount(Session c, String id) {
        return c.onClient(mc -> amount(id));
    }

    @SuppressWarnings("unchecked")
    private static List<String> linesIn() {
        return ((List<String>) ModUnderTest.staticCall(CT, "lines")).stream().map(s -> s.replaceAll("§.", "")).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> lines(Session c) {
        return c.onClient(mc -> ((List<String>) ModUnderTest.staticCall(CT, "lines")).stream()
                .map(s -> s.replaceAll("§.", "")).toList());
    }
}

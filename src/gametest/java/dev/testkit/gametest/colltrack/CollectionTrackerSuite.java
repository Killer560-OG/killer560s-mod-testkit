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
        c.check(after == 12100, "a [Sacks] +100 Wheat line should raise Wheat to 12,100 (once, Enchanted Wheat not counted), is " + after);
        c.check(amount(c, "MITHRIL_ORE") == 3000, "Mithril moved on a Wheat sack line");
        c.check(lines(c).contains("Wheat VII: 12,100 (80.6% to VIII) +0/h"), "the HUD did not follow the sack line: " + lines(c));
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

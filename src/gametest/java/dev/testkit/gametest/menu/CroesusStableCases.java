package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 884-887 (croesus-stable, 2026-10-12), killer560's two reports from real use:
 * "I am opening a run to see chests that I have already opened and it is hiding a bunch of other runs that I already
 * claimed. Sometimes they come back sometimes they disappear" and "after claiming a chest sometimes the chest will
 * reappear orange like it hasn't been checked yet for needing a key when it should read it while I am in the chest".
 * <p>
 * The first is runs that look alike: the same floor with the same party, all day. A run was known by its name, floor
 * and players only, so looking at one run's chests re-colored every run with that party. The second is Hypixel's list
 * head lagging a claim by a refresh. Every frame of every screen is recorded ({@code hidesSlot}, {@code runStateForTest}).
 * <ul>
 *   <li>884: four claimed runs with the same party; look at run 10 (worth a key) and run 11 (not) in turn, list - run -
 *       list, three times: over 200+ list frames each run's state changes at most once (not looked at -> known), 12 and
 *       13 are never touched, 10 ends needs-a-key and 11 opened.</li>
 *   <li>885: no run state or hiding on any frame of a run view or a reward chest screen.</li>
 *   <li>886: claim the first chest in its chest screen, back to a list whose head still says "No chests opened yet!":
 *       needs-a-key on the first frame; then claim the keyed chest, back to a head still saying "Opened Chest": key-used
 *       on the first frame.</li>
 *   <li>887: a refreshed head (other "Completed" / expiry text, rank-colored names split differently) keeps its state.</li>
 * </ul>
 */
final class CroesusStableCases {

    private CroesusStableCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "884-menu-croesus-same-party-no-flips", CroesusStableCases::sameParty);
        MenuSuite.test(s, "885-menu-croesus-no-hide-in-run-views", CroesusStableCases::noHideInViews);
        MenuSuite.test(s, "886-menu-croesus-claim-lagging-head", CroesusStableCases::lagging);
        MenuSuite.test(s, "887-menu-croesus-lore-refresh-stable", CroesusStableCases::refresh);
    }

    // ---- frame recorder -----------------------------------------------------------------------------------------------

    record Frame(String title, boolean list, String items, String hidden, String states, boolean anyHidden, boolean anyState) {
    }

    static final List<Frame> FRAMES = java.util.Collections.synchronizedList(new ArrayList<>());
    static volatile boolean recording = false;
    static boolean hooked = false;
    static final int[] WATCH = {10, 11, 12, 13};

    static void hook(Session c) {
        c.ctx().runOnClient(mc -> {
            if (hooked) {
                return;
            }
            hooked = true;
            ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
                if (!(screen instanceof AbstractContainerScreen<?> cs)) {
                    return;
                }
                ScreenEvents.afterExtract(screen).register((s, g, mx, my, d) -> {
                    if (recording) {
                        FRAMES.add(frame(cs));
                    }
                });
            });
        });
    }

    static Frame frame(AbstractContainerScreen<?> cs) {
        String title = cs.getTitle().getString();
        boolean list = title.matches("^(?:\\(\\d+/\\d+\\) )?Croesus$");
        StringBuilder items = new StringBuilder();
        StringBuilder hidden = new StringBuilder();
        StringBuilder states = new StringBuilder();
        var slots = cs.getMenu().slots;
        for (int slot : WATCH) {
            items.append(slots.get(slot).getItem().isEmpty() ? '.' : 'x');
            hidden.append((Boolean) Mod.staticCall("croesus.ChestProfitFeature", "hidesSlot", cs, slots.get(slot)) ? 'H' : '-');
            states.append(Mod.staticCall("croesus.ChestProfitFeature", "runStateForTest", cs, slot));
        }
        boolean anyHidden = false;
        boolean anyState = false;
        for (int i = 0; i < Math.max(0, slots.size() - 36); i++) {
            anyHidden |= (Boolean) Mod.staticCall("croesus.ChestProfitFeature", "hidesSlot", cs, slots.get(i));
            anyState |= ((Number) Mod.staticCall("croesus.ChestProfitFeature", "runStateForTest", cs, i)).intValue() != 0;
        }
        return new Frame(title, list, items.toString(), hidden.toString(), states.toString(), anyHidden, anyState);
    }

    static List<Frame> take(Session c) {
        recording = false;
        List<Frame> out = c.onClient(mc -> {
            synchronized (FRAMES) {
                return List.copyOf(FRAMES);
            }
        });
        FRAMES.clear();
        return out;
    }

    // ---- world ---------------------------------------------------------------------------------------------------------

    static AutoCloseable world(Session c) {
        hook(c);
        MenuKit.reset(c);
        MenuKit.Cfg cfg = new MenuKit.Cfg(c);
        cfg.set("croesus.CroesusConfig", "ChestProfitEnabled", true).set("croesus.CroesusConfig", "HighlightRuns", true)
                .set("croesus.CroesusConfig", "HideOpenedRuns", true)
                .set("croesus.CroesusConfig", "AutoMinProfitK", 100).set("croesus.CroesusConfig", "AutoKeyMinProfitK", 500)
                .set("croesus.CroesusConfig", "ChestKeyMode", Mod.enumValue("croesus.CroesusConfig$ChestKeyMode", "SET_PROFIT"));
        c.ctx().runOnClient(mc -> {
            Object prices = Mod.field("rngmeter.RngMeterEngine", "PRICES");
            CroesusKeyCases.PRICES.forEach((id, p) -> Mod.call(prices, "putBazaarPriceForTest", id, p[0], p[1]));
            Mod.staticCall("croesus.ChestProfitFeature", "clearKeyWorthForTest");
        });
        FRAMES.clear();
        return () -> {
            recording = false;
            c.ctx().runOnClient(mc -> {
                Object prices = Mod.field("rngmeter.RngMeterEngine", "PRICES");
                CroesusKeyCases.PRICES.keySet().forEach(id -> Mod.call(prices, "removeBazaarPriceForTest", id));
                Mod.staticCall("croesus.ChestProfitFeature", "clearKeyWorthForTest");
            });
            MenuKit.reset(c);
            cfg.close();
        };
    }

    static JsonObject list(Map<Integer, JsonObject> heads, Map<Integer, JsonObject> on) {
        Map<Integer, JsonObject> slots = new LinkedHashMap<>(heads);
        slots.put(49, FlipCroesusCases.item("minecraft:barrier", "§cClose"));
        Map<Integer, JsonObject> actions = new LinkedHashMap<>(on);
        heads.keySet().forEach(k -> actions.putIfAbsent(k, new JsonObject()));
        JsonObject m = FlipCroesusCases.menu("(1/1) Croesus", 6, slots, actions);
        m.addProperty("fill", false);
        return m;
    }

    static void showList(Session c, JsonObject list, int ticks) {
        MenuKit.show(c, list);
        MenuKit.awaitScreen(c, "(1/1) Croesus", 100);
        c.ctx().getInput().setCursorPos(2, 2);
        c.ctx().waitTicks(ticks);
    }

    static void press(Session c, int slot, String expectTitle) {
        CroesusUiCases.hover(c, slot);
        c.ctx().getInput().pressMouse(0);
        MenuKit.awaitScreen(c, expectTitle, 100);
        c.ctx().getInput().setCursorPos(2, 2);
        c.ctx().waitTicks(8);
    }

    static String log(List<Frame> frames) {
        StringBuilder sb = new StringBuilder();
        String last = null;
        int run = 0;
        for (Frame f : frames) {
            String k = (f.list() ? "L" : "V") + ":" + f.items() + "/" + f.hidden() + "/" + f.states();
            if (k.equals(last)) {
                run++;
                continue;
            }
            if (last != null) {
                sb.append(last).append(" x").append(run + 1).append("; ");
            }
            last = k;
            run = 0;
        }
        if (last != null) {
            sb.append(last).append(" x").append(run + 1);
        }
        return sb.toString();
    }

    // ---- 884 ----------------------------------------------------------------------------------------------------------

    static void sameParty(Session c) throws Exception {
        try (AutoCloseable w = world(c)) {
            Map<Integer, JsonObject> heads = new LinkedHashMap<>();
            for (int s : WATCH) {
                heads.put(s, CroesusKeyCases.head("Bravo", "§7Opened Chest: §4Bedrock"));
            }
            JsonObject list = list(heads, Map.of(10, FlipCroesusCases.open(CroesusKeyCases.runView("Testkit Idol")),
                    11, FlipCroesusCases.open(CroesusKeyCases.runView("Testkit Bead"))));
            recording = true;
            for (int round = 0; round < 3; round++) {
                showList(c, list, 25);
                press(c, 10, CroesusKeyCases.RUN);
                showList(c, list, 25);
                press(c, 11, CroesusKeyCases.RUN);
            }
            showList(c, list, 25);
            List<Frame> frames = take(c);
            List<Frame> lists = frames.stream().filter(f -> f.list() && f.items().equals("xxxx")).toList();
            c.check(lists.size() >= 200, "only " + lists.size() + " list frames recorded");
            for (int i = 0; i < WATCH.length; i++) {
                int changes = 0;
                char prev = 0;
                for (Frame f : lists) {
                    char st = f.states().charAt(i);
                    if (prev != 0 && st != prev) {
                        changes++;
                    }
                    prev = st;
                }
                c.check(changes <= 1, "run " + WATCH[i] + " changed state " + changes + " times: " + log(frames));
            }
            Frame last = lists.get(lists.size() - 1);
            c.check(last.states().equals("2355") && last.hidden().equals("-H--"), "final states " + last.states()
                    + " hidden " + last.hidden() + " (want 2355 / -H--): " + log(frames));
            c.note(lists.size() + " list frames, " + (frames.size() - lists.size()) + " other: " + log(frames));
        }
    }

    // ---- 885 ----------------------------------------------------------------------------------------------------------

    static void noHideInViews(Session c) throws Exception {
        try (AutoCloseable w = world(c)) {
            JsonObject view = CroesusKeyCases.runView("Testkit Idol");
            view.getAsJsonObject("on").add("11", FlipCroesusCases.open(FlipCroesusCases.chestScreen("Obsidian Chest",
                    "Testkit Idol", "§6500,000 Coins", true)));
            Map<Integer, JsonObject> heads = new LinkedHashMap<>();
            for (int s : WATCH) {
                heads.put(s, CroesusKeyCases.head("Hotel" + s, "§aNo more chests to open!"));
            }
            JsonObject list = list(heads, Map.of(10, FlipCroesusCases.open(view)));
            recording = true;
            showList(c, list, 10);
            CroesusUiCases.hover(c, 10);
            c.ctx().getInput().pressMouse(0); // a hidden run still opens (texture-only hide)
            MenuKit.awaitScreen(c, CroesusKeyCases.RUN, 100);
            c.ctx().waitTicks(20);
            press(c, 11, "Obsidian Chest");
            c.ctx().waitTicks(20);
            List<Frame> frames = take(c);
            List<Frame> views = frames.stream().filter(f -> !f.list()).toList();
            long bad = views.stream().filter(f -> f.anyHidden() || f.anyState()).count();
            long listHidden = frames.stream().filter(f -> f.list() && f.hidden().equals("HHHH")).count();
            c.check(views.size() >= 40, "only " + views.size() + " run/chest view frames");
            c.check(bad == 0, bad + " run/chest view frames had a hidden slot or a run state: " + log(frames));
            c.check(listHidden > 0, "control: the list never hid its four key-used runs: " + log(frames));
            c.note(views.size() + " run-view/chest frames, 0 with a hidden slot or a state; list frames hid all four: " + log(frames));
        }
    }

    // ---- 886 ----------------------------------------------------------------------------------------------------------

    static JsonObject freshRunView(JsonObject bedrockScreen) {
        return FlipCroesusCases.menu(CroesusKeyCases.RUN, 6, Map.of(
                10, FlipCroesusCases.item("minecraft:chest", "§4Bedrock Chest", "§7Contents", "§aTestkit Relic", "",
                        "§7Cost", "§61,000,000 Coins"),
                11, FlipCroesusCases.item("minecraft:chest", "§5Obsidian Chest", "§7Contents", "§aTestkit Idol", "",
                        "§7Cost", "§6500,000 Coins"),
                49, FlipCroesusCases.item("minecraft:arrow", "§aGo Back")), Map.of(10, FlipCroesusCases.open(bedrockScreen)));
    }

    static void lagging(Session c) throws Exception {
        try (AutoCloseable w = world(c)) {
            JsonObject bedrock = FlipCroesusCases.chestScreen("Bedrock Chest", "Testkit Relic", "§61,000,000 Coins", false);
            JsonObject unopened = list(Map.of(10, CroesusKeyCases.head("Golf", "§cNo chests opened yet!")),
                    Map.of(10, FlipCroesusCases.open(freshRunView(bedrock))));
            showList(c, unopened, 10);
            press(c, 10, CroesusKeyCases.RUN);
            press(c, 10, "Bedrock Chest");
            CroesusUiCases.hover(c, 31);
            c.ctx().getInput().pressMouse(0); // the claim; the server closes Croesus, as Hypixel does
            MenuKit.awaitNoScreen(c, 60);
            // Back to the list, its head one refresh behind: still "No chests opened yet!".
            FRAMES.clear();
            recording = true;
            showList(c, unopened, 10);
            List<Frame> a = take(c).stream().filter(f -> f.list() && f.items().charAt(0) == 'x').toList();
            c.check(!a.isEmpty() && a.get(0).states().charAt(0) == '2' && a.stream().allMatch(f -> f.states().charAt(0) == '2'),
                    "after the first claim, a lagging head read " + log(a) + " (want needs-key, 2, from the first frame)");
            // The head catches up: still needs a key. Then the keyed claim, and a head still saying "Opened Chest".
            JsonObject keyed = FlipCroesusCases.chestScreen("Obsidian Chest", "Testkit Idol", "§6500,000 Coins", true);
            JsonObject opened = list(Map.of(10, CroesusKeyCases.head("Golf", "§7Opened Chest: §4Bedrock")),
                    Map.of(10, FlipCroesusCases.open(withKeyedObsidian(keyed))));
            recording = true;
            showList(c, opened, 10);
            List<Frame> b = take(c).stream().filter(f -> f.list() && f.items().charAt(0) == 'x').toList();
            c.check(!b.isEmpty() && b.stream().allMatch(f -> f.states().charAt(0) == '2'), "head caught up: " + log(b));
            press(c, 10, CroesusKeyCases.RUN);
            press(c, 11, "Obsidian Chest");
            CroesusUiCases.hover(c, 31);
            c.ctx().getInput().pressMouse(0);
            MenuKit.awaitNoScreen(c, 60);
            recording = true;
            showList(c, opened, 10);
            List<Frame> d = take(c).stream().filter(f -> f.list() && f.items().charAt(0) == 'x').toList();
            c.check(!d.isEmpty() && d.stream().allMatch(f -> f.states().charAt(0) == '4' && f.hidden().charAt(0) == 'H'),
                    "after the keyed claim, a head still saying Opened Chest read " + log(d) + " (want key-used, 4, hidden)");
            c.note("lagging head after claim 1: " + log(a) + "; caught up: " + log(b) + "; after the keyed claim: " + log(d));
        }
    }

    static JsonObject withKeyedObsidian(JsonObject keyedScreen) {
        JsonObject v = CroesusKeyCases.runView("Testkit Idol");
        v.getAsJsonObject("on").add("11", FlipCroesusCases.open(keyedScreen));
        return v;
    }

    // ---- 887 ----------------------------------------------------------------------------------------------------------

    static JsonObject rankHead(String completed, String expiry) {
        return FlipCroesusCases.item("minecraft:player_head", "§cThe Catacombs", "§7Floor VII", "", "§7Players:",
                " §b[MVP§c+§b] India§f: §eMage (39)", " §aJuliet§f: §eTank (45)", "", "§7Completed: §a" + completed, "",
                "§7Opened Chest: §4Bedrock", "§8Chests expire in " + expiry, "", "§eClick to view chests!");
    }

    static void refresh(Session c) throws Exception {
        try (AutoCloseable w = world(c)) {
            JsonObject a = list(Map.of(10, rankHead("2 hours ago", "1d 10h!")),
                    Map.of(10, FlipCroesusCases.open(CroesusKeyCases.runView("Testkit Bead"))));
            showList(c, a, 10);
            press(c, 10, CroesusKeyCases.RUN);
            recording = true;
            showList(c, a, 15);
            c.hx().call("menu.set", dev.testkit.gametest.hx.Hx.args("slot", 10, "stack", rankHead("3 hours ago", "1d 9h!")));
            c.ctx().waitTicks(15);
            List<Frame> f = take(c).stream().filter(x -> x.list() && x.items().charAt(0) == 'x').toList();
            c.check(!f.isEmpty() && f.stream().allMatch(x -> x.states().charAt(0) == '3' && x.hidden().charAt(0) == 'H'),
                    "a refreshed head changed state: " + log(f));
            c.note("before and after the head's Completed / expiry lines changed: " + log(f));
        }
    }
}

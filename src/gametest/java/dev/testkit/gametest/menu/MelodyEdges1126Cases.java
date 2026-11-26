package dev.testkit.gametest.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Latency;
import dev.testkit.harness.WireCapture;

import net.minecraft.world.inventory.ContainerInput;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * melody-edges-missed-1126 (owner 11-26: "if it has multiple edges at the start it doesnt get all of them or multiple at
 * the end sometimes it misses them"; his setting Skip Mode Edges, Lookahead 2). 4-row boards with 2, 3 and 4 edge rows
 * (columns 1 and 5) in a row at the start and at the end, on two servers: "instant" (the next row at once, the boss sim's
 * way) and "hypixel" (rowAtStep: the next row only at the pane's next step, with the pane one column on, as his Hypixel
 * logs show). Stream Safe off and on, at 0/65/137/200 ms. Pass, judged by the server: every row solved, no click the
 * server did not take (a click on a row before it is active, or off its target), no pass of the pane over an active row's
 * target left unclicked unless the mod let it go as too late, and every row taken within ping + 3 ticks (Stream Safe + 4)
 * of its pane arriving on the target. Legit jar: Auto Melody sends nothing.
 */
final class MelodyEdges1126Cases {

    private static final String FEATURE = TerminalCases.FEATURE;
    private static final String CONFIG = TerminalCases.CONFIG;
    private static final int[][] BOARDS = {
            {5, 1, 3, 2}, {5, 1, 5, 3}, {5, 1, 5, 1}, {5, 5, 3, 2},   // edges at the start: 2, 3, 4, same edge twice
            {3, 2, 1, 5}, {2, 5, 1, 5}, {3, 1, 5, 1}, {2, 3, 5, 5}};  // edges at the end
    private static final String[] MODELS = {"instant", "hypixel"};

    private MelodyEdges1126Cases() {
    }

    static void register(Session s) {
        for (int ms : new int[]{0, 65, 137, 200}) {
            MenuSuite.test(s, "1126-menu-term-melody-edges-p" + ms, c -> edges(c, ms));
        }
        MenuSuite.test(s, "1126-same-melody-edges", c -> sameTick(c, false));
        MenuSuite.test(s, "1126-same-melody-edges-ss", c -> sameTick(c, true));
    }

    private static void forgetRowDelay() {
        try {
            Mod.setField(FEATURE, "melodyRowShowDelayMs", 0L);
        } catch (AssertionError e) {
            // a jar without it
        }
    }

    private static int lateSkips(Session c) {
        return c.onClient(mc -> ((Number) Mod.field(FEATURE, "melodyLateSkips")).intValue());
    }

    private static MenuKit.Cfg config(Session c, boolean ss) {
        MenuKit.Cfg cfg = new MenuKit.Cfg(c);
        cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", true).set(CONFIG, "AutoMelodyEnabled", true)
                .set(CONFIG, "HoverTerminalsEnabled", false).set(CONFIG, "HoldToClickEnabled", false)
                .set(CONFIG, "AutoStreamSafe", ss).set(CONFIG, "BlockInputWhileAutoClicking", false)
                .set(CONFIG, "MelodySkipMode", Mod.enumValue(CONFIG + "$MelodySkipMode", "EDGES"))
                .set(CONFIG, "MelodyLookaheadClicks", 2);
        return cfg;
    }

    private static JsonObject open(Session c, int[] targets, String model, int seed) {
        JsonArray ts = new JsonArray();
        for (int t : targets) {
            ts.add(t);
        }
        JsonObject args = new JsonObject();
        args.addProperty("type", "MELODY");
        args.addProperty("seed", seed);
        args.addProperty("interval", 10);
        args.addProperty("bandRows", 4);
        args.addProperty("rowAtStep", model.equals("hypixel"));
        args.add("targets", ts);
        return c.hx().call("menu.terminal", args).getAsJsonObject();
    }

    /** Legit: Auto Melody is cheat-only, so a Melody left open gets no click at all. */
    private static void legit(Session c) throws Exception {
        try (MenuKit.Cfg cfg = config(c, false)) {
            int head = c.events("terminal.click").size();
            JsonObject t = open(c, BOARDS[0], "instant", 1126);
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            c.ctx().waitTicks(80);
            int clicks = c.events("terminal.click").size() - head;
            c.ctx().runOnClient(mc -> mc.player.closeContainer());
            c.note("legit jar: " + clicks + " click(s) on a Melody left open 4 s");
            c.check(clicks == 0, "legit jar: Auto Melody clicked " + clicks + " time(s)");
        } finally {
            MenuKit.reset(c);
        }
    }

    static void edges(Session c, int ms) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            legit(c);
            return;
        }
        int lagCap = (ms + 49) / 50 + 3;
        List<String> problems = new ArrayList<>();
        long total = 0;
        try {
            c.onClient(mc -> {
                Latency.install(ms);
                return null;
            });
            c.ctx().waitTicks(60);
            for (boolean ss : new boolean[]{false, true}) {
                try (MenuKit.Cfg cfg = config(c, ss)) {
                    for (String model : MODELS) {
                        c.onClient(mc -> {
                            forgetRowDelay();
                            return null;
                        });
                        for (int b = 0; b < BOARDS.length; b++) {
                            int[] targets = BOARDS[b];
                            String name = model + (ss ? " ss " : " ") + Arrays.toString(targets);
                            int clickHead = c.events("terminal.click").size();
                            int solvedHead = c.events("terminal.solved").size();
                            int late0 = lateSkips(c);
                            JsonObject t = open(c, targets, model, 1126 + b);
                            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
                            c.waitUntil("Melody " + name + " solved", mc -> c.events("terminal.solved").size() > solvedHead, 2400);
                            MenuKit.awaitNoScreen(c, 40);
                            JsonObject solved = c.events("terminal.solved").get(solvedHead);
                            int late = lateSkips(c) - late0;
                            int missed = solved.get("missedPasses").getAsInt();
                            long ticks = solved.get("solvedTick").getAsLong() - solved.get("openedTick").getAsLong();
                            total += ticks;
                            List<Long> lags = new ArrayList<>();
                            for (var e : solved.getAsJsonArray("onTargetLags")) {
                                lags.add(e.getAsLong());
                            }
                            List<JsonObject> all = c.events("terminal.click");
                            int good = 0, wrong = 0;
                            for (JsonObject e : all.subList(clickHead, all.size())) {
                                if (e.get("correct").getAsBoolean()) good++;
                                else wrong++;
                            }
                            int cap = lagCap + (ss ? 4 : 0);
                            c.note(name + ": " + ticks + " ticks, rows taken " + lags + " ticks after the pane arrived (cap "
                                    + cap + "), " + wrong + " click(s) not taken, " + missed + " pass(es) unclicked, " + late
                                    + " let go as too late");
                            if (good != 4) problems.add(name + ": " + good + " rows taken of 4");
                            if (wrong > 0) problems.add(name + ": " + wrong + " click(s) the server did not take");
                            if (missed > late) problems.add(name + ": " + missed + " pass(es) over a target unclicked, " + late
                                    + " of them let go as too late");
                            if (lags.size() != 4 || lags.stream().anyMatch(l -> l > cap)) {
                                problems.add(name + ": rows taken " + lags + " ticks after the pane arrived (cap " + cap + ")");
                            }
                        }
                    }
                }
            }
            c.note("TOTAL " + total + " ticks over " + BOARDS.length * 4 + " boards at " + ms + " ms");
            c.check(problems.isEmpty(), String.join("; ", problems));
        } finally {
            c.onClient(mc -> {
                Latency.remove();
                forgetRowDelay();
                return null;
            });
            MenuKit.reset(c);
        }
    }

    /**
     * SAME-TICK GRIM CASE: in EVERY client tick of the board his own container click (END_CLIENT_TICK, a mouse event), his
     * hotbar swap, right click (use_item) and swing (START_CLIENT_TICK, keybinds), plus three extra solver frames per tick,
     * on boards with edge runs at the start and the end, 50 ms latency, Block Input off so his clicks reach the wire. On the
     * wire: some tick carries a Melody click together with his click and swap, and no tick carries two Melody clicks.
     */
    static void sameTick(Session c, boolean ss) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            legit(c);
            return;
        }
        WireCapture.describe = p -> p.getClass().getSimpleName();
        boolean[] open = {false};
        List<String> problems = new ArrayList<>();
        try (MenuKit.Cfg cfg = config(c, ss)) {
            c.onClient(mc -> {
                Latency.install(50);
                forgetRowDelay();
                return null;
            });
            c.ctx().runOnClient(mc -> mc.player.getInventory().clearContent());
            c.onClient(mc -> {
                TermBurst1111Cases.hook();
                TermBurst1111Cases.perTickEnd = () -> {
                    if (!(dev.testkit.compat.McCompat.screen(mc)
                            instanceof net.minecraft.client.gui.screens.inventory.ContainerScreen s)) {
                        open[0] = false;
                        return;
                    }
                    open[0] = true;
                    mc.gameMode.handleContainerInput(s.getMenu().containerId, s.getMenu().slots.size() - 1, 0,
                            ContainerInput.PICKUP, mc.player);
                    for (int i = 0; i < 3; i++) {
                        Mod.staticCall(FEATURE, "refreshState");
                    }
                };
                TermBurst1111Cases.perTick = () -> {
                    if (!open[0]) return;
                    mc.player.getInventory().setSelectedSlot((mc.player.getInventory().getSelectedSlot() + 1) % 9);
                    mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND);
                    dev.testkit.compat.McCompat.swingArm(mc.player);
                };
                return null;
            });
            int[][] boards = {{5, 5, 1, 2}, {2, 3, 1, 1}, {5, 1, 5, 3}};
            for (String model : MODELS) {
                for (int b = 0; b < boards.length; b++) {
                    String name = model + (ss ? " ss " : " ") + Arrays.toString(boards[b]);
                    int clickHead = c.events("terminal.click").size();
                    int solvedHead = c.events("terminal.solved").size();
                    WireCapture.start();
                    JsonObject t = open(c, boards[b], model, 2126 + b);
                    int rows = t.has("rows") ? t.get("rows").getAsInt() : 6;
                    MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
                    c.waitUntil("Melody " + name + " solved", mc -> c.events("terminal.solved").size() > solvedHead, 2400);
                    MenuKit.awaitNoScreen(c, 40);
                    c.ctx().waitTicks(4);
                    List<WireCapture.Sent> sent = WireCapture.snapshot();
                    WireCapture.stop();
                    int[] cur = new int[2];
                    int shared = 0, maxClicks = 0;
                    for (WireCapture.Sent w : sent) {
                        if (!w.phase().equals("play")) continue;
                        String n = w.className() + " " + w.type();
                        if (n.contains("ClientTickEnd")) {
                            if (cur[0] >= 2 && cur[1] > 0) shared++;
                            maxClicks = Math.max(maxClicks, cur[0]);
                            cur = new int[2];
                        } else if (n.contains("ContainerClick")) {
                            cur[0]++;
                        } else if (n.contains("SetCarriedItem")) {
                            cur[1]++;
                        }
                    }
                    int good = 0, wrong = 0;
                    List<JsonObject> all = c.events("terminal.click");
                    for (JsonObject e : all.subList(clickHead, all.size())) {
                        if (e.get("slot").getAsInt() >= rows * 9) continue;
                        if (e.get("correct").getAsBoolean()) good++;
                        else wrong++;
                    }
                    c.note(name + ": " + good + " rows taken, " + wrong + " grid click(s) not taken, " + shared
                            + " tick(s) carried a Melody click with his click and swap, max " + maxClicks
                            + " container clicks in a tick (his one + the mod's)");
                    if (good != 4) problems.add(name + ": " + good + " rows taken of 4");
                    if (wrong > 0) problems.add(name + ": " + wrong + " grid click(s) not taken");
                    if (shared == 0) problems.add(name + ": no Melody click shared a tick with his click and swap");
                    if (maxClicks > 2) problems.add(name + ": " + maxClicks + " container clicks in one tick (two Melody clicks)");
                }
            }
            c.check(problems.isEmpty(), String.join("; ", problems));
        } finally {
            TermBurst1111Cases.perTickEnd = null;
            TermBurst1111Cases.perTick = null;
            WireCapture.stop();
            WireCapture.describe = null;
            c.onClient(mc -> {
                Latency.remove();
                forgetRowDelay();
                return null;
            });
            MenuKit.reset(c);
        }
    }
}

package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.WireCapture;

import net.minecraft.world.inventory.ContainerInput;

import java.util.ArrayList;
import java.util.List;

/**
 * streamsafe-terms-human-1126 (owner 11-26: "make them look more human"). Auto Terminals' Stream Safe hand at his own
 * settings (Reaction Time 140, Cursor Speed 140%, Miss 0, Overshoot 0, First Click Delay 300), against the pace of his own
 * hand on terminals (logger mod, 11-24: first click 642-1827 ms after the open, gaps 464-773 ms with p50 533, so the
 * shortest gap was 0.87 of the typical one). The mod's hand is faster by his own settings, so the bands are the SHAPE of
 * his timing, not its speed:
 * <ul>
 *   <li>1126-menu-term-ss-human-*, one per order-free type and Numbers: 8 boards. Every board solved with no wrong click
 *       and the clicks on the wire (the hand acted); never two clicks in one wire tick; gaps between clicks on different
 *       slots (client send times) never under 100 ms, their p10 at least 0.7 and their minimum at least 0.5 of their median
 *       (his 0.87 and 0.87; 11-25 logs: 48 ms against ~125); a Rubix pane's repeat click never under 95 ms; the first click never before the First Click
 *       Delay and spread over at least 60 ms across the boards (11-25 logs: every first click within 2 ms of the delay);
 *       the first click 1.2 to 6 typical gaps after the open (his 1.2-3.4). Legit: Auto Terminals sends nothing.</li>
 *   <li>1126-same-ss-human: SAME-TICK GRIM CASE with Stream Safe on: in every client tick his container click (END_CLIENT_TICK),
 *       hotbar swap, right click and swing (START_CLIENT_TICK); some wire tick carries a hand click together with his click
 *       and swap, and no wire tick carries two of the hand's clicks.</li>
 * </ul>
 */
final class TermSsHuman1126Cases {

    private static final String CONFIG = TerminalCases.CONFIG;
    private static final String CURSOR = TermHumanCases.CURSOR;
    private static final int BOARDS = 8;
    private static final int FIRST_DELAY = 300;

    private TermSsHuman1126Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1126-menu-term-ss-human-panes", c -> stats(c, TerminalCases.SPECS.get(0)));
        MenuSuite.test(s, "1126-menu-term-ss-human-rubix", c -> stats(c, TerminalCases.SPECS.get(1)));
        MenuSuite.test(s, "1126-menu-term-ss-human-numbers", c -> stats(c, TerminalCases.NUMBERS_10));
        MenuSuite.test(s, "1126-menu-term-ss-human-startswith", c -> stats(c, TerminalCases.SPECS.get(3)));
        MenuSuite.test(s, "1126-menu-term-ss-human-select", c -> stats(c, TerminalCases.SPECS.get(4)));
        MenuSuite.test(s, "1126-same-ss-human", TermSsHuman1126Cases::sameTick);
    }

    private static MenuKit.Cfg config(Session c, TerminalCases.Spec spec) {
        MenuKit.Cfg cfg = new MenuKit.Cfg(c);
        cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", true).set(CONFIG, spec.auto(), true)
                .set(CONFIG, "CustomGuiEnabled", true).set(CONFIG, "HoverTerminalsEnabled", false)
                .set(CONFIG, "HoldToClickEnabled", false).set(CONFIG, "BurstMode", false).set(CONFIG, "BurstFinish", false)
                .set(CONFIG, "QueueTerms", false).set(CONFIG, "BlockInputWhileAutoClicking", false)
                .set(CONFIG, "CursorMissChance", 0).set(CONFIG, "CursorOvershootChance", 0)
                .set(CONFIG, "CursorReactionMs", 140).set(CONFIG, "FakeCursorSpeed", 140)
                .set(CONFIG, "FirstClickDelayMs", FIRST_DELAY).set(CONFIG, "AutoStreamSafe", true);
        return cfg;
    }

    /** The spec with its seed moved on by {@code by}, so the boards differ. */
    private static TerminalCases.Spec reseed(TerminalCases.Spec spec, int by) {
        Object[] a = spec.args().clone();
        for (int i = 0; i + 1 < a.length; i += 2) {
            if ("seed".equals(a[i])) a[i + 1] = ((Integer) a[i + 1]) + by;
        }
        return new TerminalCases.Spec(spec.type(), a, spec.auto());
    }

    private static void legit(Session c, TerminalCases.Spec spec) throws Exception {
        try (MenuKit.Cfg cfg = config(c, spec)) {
            int head = c.events("terminal.click").size();
            JsonObject t = TerminalCases.open(c, spec);
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            c.ctx().waitTicks(70);
            int clicks = c.events("terminal.click").size() - head;
            c.ctx().runOnClient(mc -> mc.player.closeContainer());
            c.note("legit jar: " + clicks + " click(s) on a " + spec.type() + " board left open 3.5 s with Stream Safe on");
            c.check(clicks == 0, "legit jar: Auto Terminals clicked " + clicks + " time(s)");
        } finally {
            MenuKit.reset(c);
        }
    }

    private static long median(List<Long> v) {
        List<Long> s = new ArrayList<>(v);
        s.sort(null);
        return s.isEmpty() ? 0 : s.get(s.size() / 2);
    }

    static void stats(Session c, TerminalCases.Spec spec) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            legit(c, spec);
            return;
        }
        WireCapture.describe = p -> p.getClass().getSimpleName();
        List<String> problems = new ArrayList<>();
        List<Long> firsts = new ArrayList<>();
        List<Long> gaps = new ArrayList<>();
        List<Long> repeats = new ArrayList<>();
        List<Long> solveTicks = new ArrayList<>();
        int clicksTotal = 0;
        int maxWirePerTick = 0;
        try (MenuKit.Cfg cfg = config(c, spec)) {
            for (int b = 0; b < BOARDS; b++) {
                TerminalCases.Spec board = reseed(spec, b * 7);
                int head = c.events("terminal.click").size();
                int solvedHead = c.events("terminal.solved").size();
                WireCapture.start();
                JsonObject t = TerminalCases.open(c, board);
                MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
                c.waitUntil(spec.type() + " board " + b + " solved", mc -> c.events("terminal.solved").size() > solvedHead, 900);
                MenuKit.awaitNoScreen(c, 60);
                c.ctx().waitTicks(3);
                List<WireCapture.Sent> sent = WireCapture.snapshot();
                WireCapture.stop();
                JsonObject solved = c.events("terminal.solved").get(solvedHead);
                solveTicks.add(solved.get("solvedTick").getAsLong() - solved.get("openedTick").getAsLong());
                int wrong = solved.get("wrong").getAsInt();
                if (wrong > 0) problems.add("board " + b + ": " + wrong + " wrong");
                List<JsonObject> evs = c.events("terminal.click");
                List<Integer> slots = new ArrayList<>();
                for (JsonObject e : evs.subList(head, evs.size())) slots.add(e.get("slot").getAsInt());
                List<Long> at = new ArrayList<>();
                int inTick = 0;
                for (WireCapture.Sent w : sent) {
                    if (!w.phase().equals("play")) continue;
                    String n = w.className() + " " + w.type();
                    if (n.contains("ClientTickEnd")) {
                        maxWirePerTick = Math.max(maxWirePerTick, inTick);
                        inTick = 0;
                    } else if (n.contains("ContainerClick")) {
                        inTick++;
                        at.add(w.nanos() / 1_000_000L);
                    }
                }
                maxWirePerTick = Math.max(maxWirePerTick, inTick);
                clicksTotal += at.size();
                if (at.isEmpty()) problems.add("board " + b + ": no click on the wire");
                if (at.size() != slots.size()) {
                    problems.add("board " + b + ": " + at.size() + " clicks on the wire, the server took " + slots.size());
                }
                for (int i = 1; i < Math.min(at.size(), slots.size()); i++) {
                    long g = at.get(i) - at.get(i - 1);
                    (slots.get(i).equals(slots.get(i - 1)) ? repeats : gaps).add(g);
                }
                long first = c.onClient(mc -> ((Number) Mod.field(CURSOR, "firstClickAfterOpenMs")).longValue());
                firsts.add(first);
                System.out.println("[menu] " + c.name() + " board " + b + ": first " + first + " ms, slots " + slots);
            }
        } finally {
            WireCapture.stop();
            WireCapture.describe = null;
            MenuKit.reset(c);
        }
        long gMed = median(gaps);
        long gMin = gaps.stream().mapToLong(Long::longValue).min().orElse(0);
        List<Long> fs = new ArrayList<>(firsts);
        fs.sort(null);
        long fMin = fs.isEmpty() ? 0 : fs.get(0);
        long fSpread = fs.isEmpty() ? 0 : fs.get(fs.size() - 1) - fs.get(0);
        long fMed = median(firsts);
        double ratio = gMed == 0 ? 0 : fMed / (double) gMed;
        long rMin = repeats.stream().mapToLong(Long::longValue).min().orElse(-1);
        List<Long> gs = new ArrayList<>(gaps);
        gs.sort(null);
        c.note(String.format("%s: %d boards, %d clicks; first click %s ms (median %d, spread %d); gaps n=%d min %d p10 %d p50 %d p90 %d"
                        + " (min/median %.2f, his 0.87); repeats %s; first/median gap %.1f (his 1.2-3.4); max %d click(s) in a wire tick;"
                        + " solve ticks %s", spec.type(), BOARDS, clicksTotal, fs, fMed, fSpread, gs.size(), gMin,
                gs.isEmpty() ? 0 : gs.get(gs.size() / 10), gMed, gs.isEmpty() ? 0 : gs.get(gs.size() * 9 / 10),
                gMed == 0 ? 0 : gMin / (double) gMed, repeats, ratio, maxWirePerTick, solveTicks));
        c.check(problems.isEmpty(), "problems: " + problems);
        c.check(clicksTotal >= BOARDS, "the hand sent only " + clicksTotal + " clicks over " + BOARDS + " boards");
        c.check(maxWirePerTick <= 1, maxWirePerTick + " clicks of the hand in one wire tick");
        c.check(gaps.size() >= 5, "only " + gaps.size() + " gaps between different slots");
        c.check(gMin >= 100, "a gap of " + gMin + " ms between two slots (under 100 ms, faster than a hand)");
        long gP10 = gs.isEmpty() ? 0 : gs.get(gs.size() / 10);
        c.check(gP10 >= 0.7 * gMed, "gap p10 " + gP10 + " ms against a median of " + gMed + " (his own p10 was 0.87 of his median)");
        c.check(gMin >= 0.5 * gMed, "gaps reach down to " + gMin + " ms against a median of " + gMed
                + " (his own shortest was 0.87 of his median)");
        c.check(rMin < 0 || rMin >= 95, "a Rubix repeat click " + rMin + " ms after the one before");
        c.check(fMin >= FIRST_DELAY - 5, "a first click " + fMin + " ms after the open, before the First Click Delay");
        c.check(fSpread >= 60, "first clicks all within " + fSpread + " ms of each other " + fs + " (pinned to the delay)");
        c.check(ratio >= 1.2 && ratio <= 6, String.format("first click %.1f typical gaps after the open (his 1.2-3.4)", ratio));
    }

    static void sameTick(Session c) throws Exception {
        TerminalCases.Spec select = TerminalCases.SPECS.get(4);
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            legit(c, select);
            return;
        }
        WireCapture.describe = p -> p.getClass().getSimpleName();
        boolean[] open = {false};
        List<String> problems = new ArrayList<>();
        List<TerminalCases.Spec> boards = List.of(reseed(select, 101), reseed(select, 102), reseed(select, 103),
                reseed(TerminalCases.SPECS.get(0), 101), reseed(TerminalCases.SPECS.get(1), 101));
        try (MenuKit.Cfg cfg = config(c, select)) {
            cfg.set(CONFIG, "AutoPanesEnabled", true).set(CONFIG, "AutoRubixEnabled", true);
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
                };
                TermBurst1111Cases.perTick = () -> {
                    if (!open[0]) return;
                    mc.player.getInventory().setSelectedSlot((mc.player.getInventory().getSelectedSlot() + 1) % 9);
                    mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND);
                    dev.testkit.compat.McCompat.swingArm(mc.player);
                };
                return null;
            });
            for (TerminalCases.Spec board : boards) {
                String name = board.type() + " " + board.args()[1];
                int head = c.events("terminal.click").size();
                int solvedHead = c.events("terminal.solved").size();
                WireCapture.start();
                JsonObject t = TerminalCases.open(c, board);
                int rows = t.has("rows") ? t.get("rows").getAsInt() : 6;
                MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
                c.waitUntil(name + " solved", mc -> c.events("terminal.solved").size() > solvedHead, 1200);
                MenuKit.awaitNoScreen(c, 60);
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
                for (JsonObject e : all.subList(head, all.size())) {
                    if (e.get("slot").getAsInt() >= rows * 9) continue;
                    if (e.get("correct").getAsBoolean()) good++;
                    else wrong++;
                }
                JsonObject solved = c.events("terminal.solved").get(solvedHead);
                c.note(name + ": " + good + " grid clicks taken, " + wrong + " not taken, " + shared
                        + " wire tick(s) carried a hand click with his click and swap, max " + maxClicks
                        + " container clicks in a tick (his one + the hand's), server max per tick "
                        + solved.get("maxClicksPerTick").getAsInt());
                if (good == 0) problems.add(name + ": the hand clicked nothing");
                if (wrong > 0) problems.add(name + ": " + wrong + " grid click(s) not taken");
                if (shared == 0) problems.add(name + ": no hand click shared a tick with his click and swap");
                if (maxClicks > 2) problems.add(name + ": " + maxClicks + " container clicks in one tick (two from the hand)");
            }
            c.check(problems.isEmpty(), String.join("; ", problems));
        } finally {
            TermBurst1111Cases.perTickEnd = null;
            TermBurst1111Cases.perTick = null;
            WireCapture.stop();
            WireCapture.describe = null;
            MenuKit.reset(c);
        }
    }
}

package dev.testkit.gametest.perf;

import dev.testkit.gametest.Scenario;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.perf.PerfBench.Switch;
import dev.testkit.harness.EventSpan;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 95-perf-idle: what the mod costs while its features are OFF or at their DEFAULTS (fps-idle-1015, 2026-10-15). About
 * 4 minutes, only when named; no probe needed (it reads {@link EventSpan} and the render thread's allocation).
 *
 * <p>In {@link PerfBench}'s scene:
 * <ol>
 *   <li><b>one text per chat line</b>: two ChatObserver subscribers and a raw Fabric GAME listener read a line's text
 *       with {@code ChatObserver.strip} / {@code raw}; they must all be handed the SAME String (the line is walked into a
 *       string once, not once per reader). Main walked it again for every reader: fails there.</li>
 *   <li><b>chat cost per line</b>: a corpus of Hypixel-shaped lines (party, guild, all chat, NPC/boss, system, action
 *       bar) pushed through the real {@code ChatListener.handleSystemMessage} path, OFF and DEFAULTS: render-thread
 *       bytes and microseconds per line, the bytes against a budget.</li>
 *   <li><b>idle ticks</b>: START/END_CLIENT_TICK listener span (every listener, unwrapped), OFF and DEFAULTS: bytes
 *       and microseconds per tick, reported (the sim's own client ticks are in it).</li>
 * </ol>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class PerfIdleTest implements FabricClientGameTest {

    private static final String NAME = "95-perf-idle";

    /** Chat bytes per line allowed with everything OFF / at DEFAULTS (vanilla's own share, mostly ICU Bidi and line
     *  wrapping, is ~14 KB of it). Mod main 69505e45 measured 51.9 / 53.3 KB, fps-idle-1015 15.5 / 16.8 KB (2026-10-15,
     *  back to back on one machine). */
    private static final int CHAT_BYTES_OFF = Integer.getInteger("testkit.perf.idleChatOff", 24_000);
    private static final int CHAT_BYTES_DEFAULTS = Integer.getInteger("testkit.perf.idleChatDefaults", 26_000);

    /** {0} is replaced by the line's number: every line differs, and no line is a prefix of another (the observer drops
     *  repeats and prefix-repeats within 250 ms). */
    private static final String[] CHAT = {
            "§9Party §8> §b[MVP§c+§b] Someone{0}§f: anyone got a spare spirit leap",
            "§2Guild > §a[VIP] Other{0}§f: gg on the m7 run",
            "§7[MVP§d++§7] RandomPlayer{0}§f: selling hyperion cheap",
            "§dFrom §a[VIP] Friend{0}§7: hi, coming to the dungeon hub?",
            "§e[NPC] §bMort{0}§f: Here, I found this map when I first entered the dungeon.",
            "§c[BOSS] The Watcher{0}§r§f: Things feel a little more roomy now, eh?",
            "§aYou have {0} unclaimed rewards!",
            "§6Your Bone Plating reduced the damage you took by {0}.5!",
            "§7You are playing on profile: §eNotAProfile{0}",
            "§b[MVP§3+§b] Teammate{0} §efound a §dWither Essence§e! Everyone gains an extra essence!",
    };
    private static final String ACTION_BAR = "§c1200/1200❤     §a512§a❈ Defense     §b{0}/800✎ Mana";
    private static final int LINES = 400;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String filter = System.getProperty("testkit.scenario", "");
        if (filter.isBlank() || Scenario.skip(NAME)) {
            return;
        }
        List<String> report = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        if (!PerfBench.open(ctx, NAME, report)) {
            return;
        }
        Map<Switch, Boolean> defaults = new LinkedHashMap<>();
        try {
            List<Switch> all = ctx.computeOnClient(mc -> PerfBench.switches());
            defaults.putAll(ctx.computeOnClient(mc -> PerfBench.states(all)));
            Map<Switch, Boolean> off = PerfBench.allOff(all);

            // ---- 1. one text per line ----
            ctx.runOnClient(mc -> PerfBench.apply(off));
            ctx.waitTicks(20);
            failures.addAll(ctx.computeOnClient(mc -> sharedText(mc, report)));

            // ---- optional: where a chat line's time and bytes go (-Dtestkit.perf.idleJfr=<file>), OFF ----
            String jfr = System.getProperty("testkit.perf.idleJfr", "");
            if (!jfr.isBlank()) {
                try {
                    jdk.jfr.Recording rec = new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"));
                    rec.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(1));
                    rec.enable("jdk.ObjectAllocationSample").with("throttle", "20000/s");
                    rec.start();
                    for (int t = 0; t < 200; t++) {
                        ctx.runOnClient(mc -> chatLines(mc, 20, 2));
                        ctx.waitTicks(1);
                    }
                    rec.stop();
                    java.nio.file.Path out = java.nio.file.Path.of(jfr);
                    java.nio.file.Files.createDirectories(out.toAbsolutePath().getParent());
                    rec.dump(out);
                    rec.close();
                    report.add("chat JFR (OFF, 4000 lines): " + out.toAbsolutePath());
                } catch (Exception e) {
                    report.add("chat JFR failed: " + e);
                }
            }

            // ---- 2 + 3, OFF then DEFAULTS ----
            for (Object[] cfg : new Object[][]{{"OFF", off, CHAT_BYTES_OFF}, {"DEFAULTS", defaults, CHAT_BYTES_DEFAULTS}}) {
                String label = (String) cfg[0];
                @SuppressWarnings("unchecked")
                Map<Switch, Boolean> state = (Map<Switch, Boolean>) cfg[1];
                ctx.runOnClient(mc -> PerfBench.apply(state));
                ctx.waitTicks(60);
                // warm-up pass, then the measured one
                ctx.runOnClient(mc -> chatLines(mc, LINES, 0));
                ctx.waitTicks(10);
                double[] chat = ctx.computeOnClient(mc -> chatLines(mc, LINES, 1));
                String line = String.format(Locale.ROOT, "%-8s chat: %.0f B/line, %.2f us/line over %d lines (%d chat + %d"
                                + " action bar)", label, chat[0], chat[1], LINES, LINES - LINES / 5, LINES / 5);
                report.add(line);
                if (chat[0] > (Integer) cfg[2]) {
                    failures.add(line + " (budget " + cfg[2] + " B/line)");
                }
                ctx.waitTicks(20);
                ctx.runOnClient(mc -> {
                    dev.testkit.harness.BenchCamera.start(0f, 10f, PerfBench.PERIOD);
                    EventSpan.start();
                });
                ctx.waitTicks(400);
                String span = ctx.computeOnClient(mc -> {
                    EventSpan.stop();
                    return String.format(Locale.ROOT, "%-8s ticks: START %.1f us %.0f B, END %.1f us %.0f B per tick (%d ticks)"
                                    + " | frames: after-terrain %.2f us, after-features %.2f us per frame (%d frames)",
                            label, EventSpan.usPerCall(EventSpan.Kind.START_TICK),
                            EventSpan.bytesPerCall(EventSpan.Kind.START_TICK), EventSpan.usPerCall(EventSpan.Kind.END_TICK),
                            EventSpan.bytesPerCall(EventSpan.Kind.END_TICK), EventSpan.calls(EventSpan.Kind.END_TICK),
                            EventSpan.usPerCall(EventSpan.Kind.AFTER_TERRAIN), EventSpan.usPerCall(EventSpan.Kind.AFTER_FEATURES),
                            EventSpan.calls(EventSpan.Kind.AFTER_FEATURES));
                });
                report.add(span);
                if (!span.contains("(") || ctx.computeOnClient(mc -> EventSpan.calls(EventSpan.Kind.END_TICK)) < 300) {
                    failures.add(label + ": the tick span recorded under 300 ticks of 400 - its listeners did not run");
                }
            }
        } finally {
            try {
                if (!defaults.isEmpty()) {
                    ctx.runOnClient(mc -> PerfBench.apply(defaults));
                }
            } catch (Throwable ignored) {
                // restoring config must not mask the real failure
            }
            for (String line : report) {
                System.out.println("[" + NAME + "] " + line);
            }
            PerfBench.close(ctx);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("[" + NAME + "] " + failures.size() + " failure(s):\n  " + String.join("\n  ", failures));
        }
        System.out.println("[" + NAME + "] PASS");
    }

    // ---- 1 ----------------------------------------------------------------------------------------------------------

    private static final List<Object> SEEN_A = new ArrayList<>();
    private static final List<Object> SEEN_B = new ArrayList<>();
    private static final List<Object> SEEN_RAW = new ArrayList<>();
    private static volatile String watchFor;
    private static boolean installed;

    /** Render thread. */
    private static List<String> sharedText(Minecraft mc, List<String> report) {
        List<String> failures = new ArrayList<>();
        if (!installed) {
            installed = true;
            Consumer<Component> a = m -> record(m, SEEN_A);
            Consumer<Component> b = m -> record(m, SEEN_B);
            Mod.staticCall("util.ChatObserver", "subscribe", a);
            Mod.staticCall("util.ChatObserver", "subscribe", b);
            ClientReceiveMessageEvents.GAME.register((m, overlay) -> {
                if (!overlay) {
                    record(m, SEEN_RAW);
                }
            });
        }
        SEEN_A.clear();
        SEEN_B.clear();
        SEEN_RAW.clear();
        String text = "§9Party §8> §b[MVP§c+§b] SharedTextProbe§f: one walk per line please";
        watchFor = "SharedTextProbe";
        chatListener(mc).handleSystemMessage(Component.literal(text), false);
        watchFor = null;
        String expected = "Party > [MVP+] SharedTextProbe: one walk per line please";
        if (SEEN_A.size() != 1 || SEEN_B.size() != 1 || SEEN_RAW.size() != 1) {
            failures.add("the probe line reached subscriber A " + SEEN_A.size() + "x, B " + SEEN_B.size() + "x, the raw"
                    + " GAME listener " + SEEN_RAW.size() + "x (want 1 each) - nothing was measured");
            return failures;
        }
        if (!expected.equals(SEEN_A.get(0)) || !expected.equals(SEEN_B.get(0)) || !expected.equals(SEEN_RAW.get(0))) {
            failures.add("ChatObserver.strip gave '" + SEEN_A.get(0) + "' / '" + SEEN_B.get(0) + "' / '" + SEEN_RAW.get(0)
                    + "', want '" + expected + "'");
        }
        Map<Object, Boolean> distinct = new IdentityHashMap<>();
        distinct.put(SEEN_A.get(0), true);
        distinct.put(SEEN_B.get(0), true);
        distinct.put(SEEN_RAW.get(0), true);
        String line = "one line, three readers (two subscribers, one raw GAME listener): " + distinct.size()
                + " distinct String instance(s) from ChatObserver.strip";
        report.add(line);
        if (distinct.size() != 1) {
            failures.add(line + " (want 1: the line's text made once and shared)");
        }
        // Outside a delivery, strip must still be a fresh read of the component (a component changed later must not be
        // served old text).
        net.minecraft.network.chat.MutableComponent built = Component.literal("built ");
        String first = (String) Mod.staticCall("util.ChatObserver", "strip", (Component) built);
        built.append("later");
        String second = (String) Mod.staticCall("util.ChatObserver", "strip", (Component) built);
        if (!"built ".equals(first) || !"built later".equals(second)) {
            failures.add("ChatObserver.strip outside a chat delivery served '" + first + "' then '" + second
                    + "' for a component changed in between (want 'built ' then 'built later')");
        }
        return failures;
    }

    private static void record(Component m, List<Object> into) {
        String w = watchFor;
        if (w == null) {
            return;
        }
        String s = (String) Mod.staticCall("util.ChatObserver", "strip", m);
        if (s.contains(w)) {
            into.add(s);
        }
    }

    /** {@code Minecraft.getChatListener()} on 26.1, {@code chatListener()} on 26.2 (by reflection, no compat edit). */
    private static net.minecraft.client.multiplayer.chat.ChatListener chatListener(Minecraft mc) {
        for (String n : new String[]{"getChatListener", "chatListener"}) {
            try {
                return (net.minecraft.client.multiplayer.chat.ChatListener) Minecraft.class.getMethod(n).invoke(mc);
            } catch (NoSuchMethodException ignored) {
                // the other version's name
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("no chat listener accessor on Minecraft");
    }

    // ---- 2 ----------------------------------------------------------------------------------------------------------

    private static int serial = 100_000;

    /** Render thread: {bytes per line, us per line}. */
    private static double[] chatLines(Minecraft mc, int n, int pass) {
        var listener = chatListener(mc);
        Component[] lines = new Component[n];
        boolean[] overlay = new boolean[n];
        for (int i = 0; i < n; i++) {
            int id = serial++;
            boolean bar = i % 5 == 4;
            String s = bar ? ACTION_BAR.replace("{0}", Integer.toString(id % 800))
                    : CHAT[i % CHAT.length].replace("{0}", Integer.toString(id));
            lines[i] = Component.literal(s);
            overlay[i] = bar;
        }
        long b0 = PerfBench.threadAllocated();
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            listener.handleSystemMessage(lines[i], overlay[i]);
        }
        long t1 = System.nanoTime();
        long b1 = PerfBench.threadAllocated();
        return new double[]{(b1 - b0) / (double) n, (t1 - t0) / 1e3 / n};
    }
}

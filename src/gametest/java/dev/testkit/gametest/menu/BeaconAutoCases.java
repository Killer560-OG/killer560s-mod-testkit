package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.PacketTrace;
import dev.testkit.harness.WireCapture;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Auto Tune (foragebeacon-auto-1123, cheat build): a fake Moonglade Beacon menu on the real server ({@code menu.beacon},
 * testkit HxBeacon: panes step with the recorded periods, buttons act like the recording, the tune completes only with color,
 * speed, pitch AND slot matching) and a real GrimAC. Every case asserts the feature acted: the server saw its clicks
 * ({@code beacon.click}), the wire carried them, and the tune completed ({@code beacon.done}).
 * <ul>
 *   <li>1123-menu-autotune-wraps / -pause / -right / -upgrade: start states needing every wrap, only Pause alignment, right
 *       clicks, and the two-beat upgrade; Click Delay 2 (its fastest) and at most one click a tick, never closer than the delay.</li>
 *   <li>1124-menu-autotune-samecolor: his 11-24 upgrade's start, both beacon beats brown, at the default Click Delay 4.</li>
 *   <li>1124-menu-autotune-run1 / -run2: his two slow Auto Tunes of 11-24 11:16 PM as they opened (607 and 458 ticks; the
 *       second with both beacon beats stepping together, so their pitches are only heard together); each must complete
 *       faster than his own hand tune that night (324 ticks).</li>
 *   <li>1125-menu-autotune-phase / -phase-jitter: your pane on the beacon pane's slot, everything matched, but stepping N ticks
 *       after it (N = 1..14 at speed 5; the jitter variant varies the latency every 10 ticks): his VIII upgrade of 11-25 sat
 *       5 ticks out and Auto Tune gave up. The fake server completes only in step (see HxBeacon); each must end in step.</li>
 *   <li>1125-menu-autotune-viii: that upgrade as it opened (white and pink speed 5 stepping together).</li>
 *   <li>1125-menu-autotune-slowboost / -slowwait: speed 1, 10 ticks behind (a pause would wait 375 ticks: it must run at Speed 5
 *       and be done in well under that) and 4 ticks ahead (waiting is quicker: no Speed click at all).</li>
 *   <li>1123-menu-autotune-off: OFF (and the legit jar, which has no such setting) sends nothing.</li>
 *   <li>1123-same-autotune-click / -swap / -close: his own container click, hotbar swap and menu close in the SAME client tick
 *       as an Auto Tune click ({@link SameTick}); run on default and experimental GrimAC (separate checkouts).</li>
 * </ul>
 */
final class BeaconAutoCases {

    private static final String TUNER = "foraging.BeaconTuner";
    private static final String WRAPS = "{\"beacon\":[{\"color\":\"white\",\"speed\":1,\"pitch\":0,\"idx\":2}],"
            + "\"mine\":[{\"color\":\"red\",\"speed\":5,\"pitch\":2,\"idx\":2,\"since\":3}]}";
    private static final String PAUSE = "{\"beacon\":[{\"color\":\"lime\",\"speed\":4,\"pitch\":1,\"idx\":1}],"
            + "\"mine\":[{\"color\":\"lime\",\"speed\":4,\"pitch\":1,\"idx\":4,\"since\":10}]}";
    private static final String RIGHT = "{\"beacon\":[{\"color\":\"cyan\",\"speed\":3,\"pitch\":0,\"idx\":5}],"
            + "\"mine\":[{\"color\":\"brown\",\"speed\":5,\"pitch\":1,\"idx\":0,\"since\":7}]}";
    private static final String UPGRADE = "{\"mode\":\"upgrade\",\"beacon\":[{\"color\":\"purple\",\"speed\":4,\"pitch\":0,\"idx\":1},"
            + "{\"color\":\"light_blue\",\"speed\":3,\"pitch\":1,\"idx\":4}],\"mine\":[{\"color\":\"orange\",\"speed\":4,\"pitch\":0,\"idx\":2},"
            + "{\"color\":\"red\",\"speed\":4,\"pitch\":2,\"idx\":5}]}";
    /** His upgrade of 11-24 as it opened: both beacon beats brown (speed 5 Low, speed 4 High), his blue speed 1 Normal and orange speed 2 High. */
    private static final String SAME_COLOR = "{\"mode\":\"upgrade\",\"beacon\":[{\"color\":\"brown\",\"speed\":5,\"pitch\":0,\"idx\":0},"
            + "{\"color\":\"brown\",\"speed\":4,\"pitch\":2,\"idx\":3}],\"mine\":[{\"color\":\"blue\",\"speed\":1,\"pitch\":1,\"idx\":6},"
            + "{\"color\":\"orange\",\"speed\":2,\"pitch\":2,\"idx\":2}]}";

    /** His slow Auto Tune of 11-24 11:16 PM as it opened (logger session of that night): beacon green speed 3 Low and orange speed 4 High, his light blue speed 2 Low and green speed 5 Normal; it took 607 ticks. */
    private static final String RUN1 = "{\"mode\":\"upgrade\",\"beacon\":[{\"color\":\"green\",\"speed\":3,\"pitch\":0,\"idx\":1,\"since\":0},"
            + "{\"color\":\"orange\",\"speed\":4,\"pitch\":2,\"idx\":5,\"since\":1}],\"mine\":[{\"color\":\"light_blue\",\"speed\":2,\"pitch\":0,\"idx\":2,\"since\":1},"
            + "{\"color\":\"green\",\"speed\":5,\"pitch\":1,\"idx\":5,\"since\":1}]}";
    /** The next one (11:16:57 PM, 458 ticks, the first click at 201): both beacon beats speed 4 stepping together (pink High, light blue Normal), his magenta speed 1 Normal and cyan speed 5 High. */
    private static final String RUN2 = "{\"mode\":\"upgrade\",\"beacon\":[{\"color\":\"pink\",\"speed\":4,\"pitch\":2,\"idx\":1,\"since\":1},"
            + "{\"color\":\"light_blue\",\"speed\":4,\"pitch\":1,\"idx\":5,\"since\":1}],\"mine\":[{\"color\":\"magenta\",\"speed\":1,\"pitch\":1,\"idx\":5,\"since\":1},"
            + "{\"color\":\"cyan\",\"speed\":5,\"pitch\":2,\"idx\":1,\"since\":1}]}";
    /** His own hand tune that night took 324 ticks from the menu opening (11:14:57.9 to 11:15:14.1 PM): Auto Tune must beat it. */
    private static final int HAND = 324;

    /**
     * One beat matched in everything but phase: the beacon's step timer starts as the menu opens (as recorded 11-25, and as
     * the Tuner reads it), yours steps {@code lag} ticks after it (negative: earlier; a negative since counts up to 0 first).
     */
    private static String phase(String color, int speed, int pitch, int lag) {
        String b = "{\"color\":\"" + color + "\",\"speed\":" + speed + ",\"pitch\":" + pitch + ",\"idx\":3,\"since\":";
        return "{\"beacon\":[" + b + "0}],\"mine\":[" + b + (-lag) + "}]}";
    }

    /** His VIII upgrade of 11-25 11:46:57 AM as it opened: beacon pink speed 5 Low at 0 and white speed 5 High at 4 (stepping together), his green speed 1 Low at 5 and cyan speed 4 Normal at 1. */
    private static final String VIII = "{\"mode\":\"upgrade\",\"beacon\":[{\"color\":\"pink\",\"speed\":5,\"pitch\":0,\"idx\":0,\"since\":0},"
            + "{\"color\":\"white\",\"speed\":5,\"pitch\":2,\"idx\":4,\"since\":0}],\"mine\":[{\"color\":\"green\",\"speed\":1,\"pitch\":0,\"idx\":5,\"since\":1},"
            + "{\"color\":\"cyan\",\"speed\":4,\"pitch\":1,\"idx\":1,\"since\":1}]}";

    /** His Tune Frequency of 11-25 4:54 PM as it opened, the server answering each click 6 ticks late (it took 4 there). */
    private static final String STALE = "{\"answerLag\":6,\"beacon\":[{\"color\":\"purple\",\"speed\":1,\"pitch\":2,\"idx\":6,\"since\":0}],"
            + "\"mine\":[{\"color\":\"light_blue\",\"speed\":4,\"pitch\":0,\"idx\":1,\"since\":0}]}";

    /**
     * His upgrade T7 of 11-25 4:54 PM as it opened: both beacon beats magenta speed 3 stepping together (pitches High at 0 and
     * Normal at 3, only heard together), his two white panes at speed 4 and 2, Normal. Auto Tune paired the pitches the wrong
     * way round and gave up; he finished it by hand.
     */
    private static final String T7 = "{\"mode\":\"upgrade\",\"beacon\":[{\"color\":\"magenta\",\"speed\":3,\"pitch\":2,\"idx\":0,\"since\":0},"
            + "{\"color\":\"magenta\",\"speed\":3,\"pitch\":1,\"idx\":3,\"since\":0}],\"mine\":[{\"color\":\"white\",\"speed\":4,\"pitch\":1,\"idx\":6,\"since\":0},"
            + "{\"color\":\"white\",\"speed\":2,\"pitch\":1,\"idx\":2,\"since\":20}]}";

    private BeaconAutoCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1123-menu-autotune-off", c -> run(c, WRAPS, false, 4, null, 200));
        if (!Mod.isCheat()) {
            return;
        }
        MenuSuite.test(s, "1123-menu-autotune-wraps", c -> run(c, WRAPS, true, 2, null, 1800));
        MenuSuite.test(s, "1123-menu-autotune-pause", c -> run(c, PAUSE, true, 2, null, 1800));
        MenuSuite.test(s, "1123-menu-autotune-right", c -> run(c, RIGHT, true, 3, null, 1800));
        MenuSuite.test(s, "1123-menu-autotune-upgrade", c -> run(c, UPGRADE, true, 2, null, 2600));
        MenuSuite.test(s, "1124-menu-autotune-samecolor", c -> run(c, SAME_COLOR, true, 4, null, 2600));
        MenuSuite.test(s, "1124-menu-autotune-run1", c -> run(c, RUN1, true, 4, null, 1400, HAND));
        MenuSuite.test(s, "1124-menu-autotune-run2", c -> run(c, RUN2, true, 4, null, 1400, HAND));
        MenuSuite.test(s, "1125-menu-autotune-phase", c -> phaseRuns(c, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14}, false));
        MenuSuite.test(s, "1125-menu-autotune-phase-jitter", c -> phaseRuns(c, new int[]{2, 5, 9, 13}, true));
        MenuSuite.test(s, "1125-menu-autotune-viii", c -> inStep(c, run(c, VIII, true, 4, null, 1400, 0, false), "VIII"));
        MenuSuite.test(s, "1125-menu-autotune-slowboost", c -> {
            Result r = run(c, phase("cyan", 1, 1, 10), true, 4, null, 600, 200, false);
            inStep(c, r, "slow 10 behind");
            c.check(r.btn().stream().anyMatch(k -> k[1] == 48), "a pane 10 ticks behind a speed 1 beacon was not run at Speed 5 (it waited the lap)");
        });
        MenuSuite.test(s, "1125-menu-autotune-stale", c -> {
            Result r = run(c, STALE, true, 4, null, 900, 0, false);
            int first = r.btn().stream().filter(k -> k[1] != 48).mapToInt(k -> k[0]).min().orElse(Integer.MAX_VALUE);
            long speed = r.btn().stream().filter(k -> k[1] == 48 && k[0] < first).count();
            c.check(speed <= 3, "Speed was clicked " + speed + " times before anything else for a 2-click change (+1 to run at Speed 5): it clicked again on the old button");
        });
        MenuSuite.test(s, "1125-menu-autotune-t7", c -> run(c, T7, true, 4, null, 1400, 0, false));
        MenuSuite.test(s, "1125-menu-autotune-slowwait", c -> {
            Result r = run(c, phase("cyan", 1, 1, -4), true, 4, null, 600, 160, false);
            inStep(c, r, "slow 4 ahead");
            c.check(r.btn().stream().noneMatch(k -> k[1] == 48), "a pane 4 ticks ahead of a speed 1 beacon was run at Speed 5 instead of a 4-tick pause");
        });
        MenuSuite.test(s, "1123-same-autotune-click", c -> run(c, WRAPS, true, 2, SameTick.Act.CLICK, 1800));
        MenuSuite.test(s, "1123-same-autotune-swap", c -> run(c, PAUSE, true, 2, SameTick.Act.SWAP, 1800));
        MenuSuite.test(s, "1123-same-autotune-close", c -> run(c, PAUSE, true, 2, SameTick.Act.CLOSE, 400));
    }

    /** Every container click the client sent, by slot, with the client tick (counted by tick_end packets) it went out in. */
    private static final class Clicks implements Consumer<Packet<?>> {
        final List<int[]> sent = new ArrayList<>();
        final Consumer<Packet<?>> prev = PacketTrace.tap;
        int tick;

        @Override
        public synchronized void accept(Packet<?> p) {
            if (prev != null) {
                prev.accept(p);
            }
            if (p instanceof ServerboundContainerClickPacket cp) {
                sent.add(new int[]{tick, cp.slotNum()});
            } else if (p instanceof ServerboundClientTickEndPacket) {
                tick++;
            }
        }

        /** Clicks on the buttons (slots 37-52), not his own click on the last slot. */
        synchronized List<int[]> buttons() {
            return sent.stream().filter(k -> k[1] >= 37 && k[1] <= 52).toList();
        }
    }

    private static boolean button(Packet<?> p) {
        return p instanceof ServerboundContainerClickPacket cp && cp.slotNum() >= 37 && cp.slotNum() <= 52;
    }

    private static long chat(long mark, String needle) {
        return dev.testkit.gametest.LogTap.since(mark).stream().filter(l -> l.contains("[CHAT]") && l.contains(needle)).count();
    }

    /** The server's beacon.done event (null: never done) and the button clicks on the wire as {client tick, slot}. */
    private record Result(JsonObject done, List<int[]> btn) {
    }

    /**
     * Every lag in {@code lags}: the tune completes within one lap plus the start; at a steady latency every pane ends in
     * step too. Under jitter only completing counts: the unpause lands a changing round trip late, and on Hypixel an
     * unpause on the right slot completes out of step (his VIII of 11-25, sv 436: about 12 ticks out, still done).
     */
    private static void phaseRuns(Session c, int[] lags, boolean jitter) throws Exception {
        for (int lag : lags) {
            Result r = run(c, phase("lime", 5, 1, lag), true, 4, null, 400, 105 + 120, jitter);
            if (!jitter) {
                inStep(c, r, "lag " + lag);
            } else if (r.done() != null) {
                c.note("lag " + lag + ": done at " + r.done().get("tick") + ", phase " + r.done().get("phase"));
            }
        }
    }

    /** At the completing click every one of your beats stepped on the beacon's tick (within one): the phase the server reports. */
    private static void inStep(Session c, Result r, String what) {
        if (r.done() == null) {
            return; // run() already failed it
        }
        String ph = r.done().get("phase").toString();
        c.note(what + ": done at " + r.done().get("tick") + ", phase " + ph);
        c.check(ph.matches("\\[(-?[01],?)+\\]"), what + ": a pane ended out of step with its beacon pane (ticks apart " + ph + ")");
    }

    private static void run(Session c, String spec, boolean auto, int delay, SameTick.Act act, int maxTicks) throws Exception {
        run(c, spec, auto, delay, act, maxTicks, 0);
    }

    private static Result run(Session c, String spec, boolean auto, int delay, SameTick.Act act, int maxTicks, int budget) throws Exception {
        return run(c, spec, auto, delay, act, maxTicks, budget, false);
    }

    /** {@code budget} > 0: the tune must complete within that many server ticks of the menu opening; {@code jitter}: latency 0-120 ms, changed every 10 ticks. */
    private static Result run(Session c, String spec, boolean auto, int delay, SameTick.Act act, int maxTicks, int budget, boolean jitter) throws Exception {
        boolean has = Mod.isCheat();
        Object cfg = Mod.field(TUNER, "CFG");
        boolean wasOn = (Boolean) Mod.call(cfg, "on", "enabled");
        boolean wasAuto = has && (Boolean) Mod.call(cfg, "on", "autoTune");
        int wasDelay = has ? (Integer) Mod.call(cfg, "num", "tuneDelay") : 0;
        c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Moonglade Marsh", "Purse: 0");
        c.waitUntil("Skyblock", mc -> Boolean.TRUE.equals(Mod.staticCall("util.SkyblockGate", "allows")), 100);
        Clicks tap = new Clicks();
        SameTick st = null;
        try {
            c.onClient(mc -> {
                Mod.call(cfg, "set", "enabled", true);
                if (has) {
                    Mod.call(cfg, "set", "autoTune", auto);
                    Mod.call(cfg, "set", "tuneDelay", delay);
                } else {
                    // The legit jar has no such setting: setting it does nothing.
                    Mod.call(cfg, "set", "autoTune", true);
                }
                return null;
            });
            JsonObject args = JsonParser.parseString(spec).getAsJsonObject();
            boolean upgrade = args.has("mode");
            String title = upgrade ? "Upgrade Signal Strength" : "Tune Frequency";
            long mark = c.hx().head();
            long logMark = dev.testkit.gametest.LogTap.mark();
            WireCapture.start();
            PacketTrace.tap = tap;
            if (act != null) {
                SameTick.Act a = act;
                st = c.onClient(mc -> {
                    SameTick s = SameTick.start(a, 1, BeaconAutoCases::button, 0, 1);
                    return a == SameTick.Act.CLOSE ? s.armedBy(() -> !tap.buttons().isEmpty()) : s;
                });
            }
            c.hx().call("menu.beacon", args);
            c.waitUntil("the beacon menu", mc -> McCompat.screen(mc) instanceof AbstractContainerScreen<?> s
                    && s.getTitle().getString().equals(title), 80);
            boolean done = false;
            java.util.Random rnd = new java.util.Random(spec.hashCode());
            for (int t = 0; t < maxTicks && !done; t += 10) {
                if (jitter) {
                    int ms = rnd.nextInt(121);
                    c.onClient(mc -> {
                        dev.testkit.harness.Latency.install(ms);
                        return null;
                    });
                }
                c.ctx().waitTicks(10);
                done = !c.hx().events(mark, "beacon.done").isEmpty();
                if (act == SameTick.Act.CLOSE && !tap.buttons().isEmpty() && t > 60) {
                    break;
                }
                if (!auto && t >= 150) {
                    break;
                }
            }
            if (jitter) {
                c.onClient(mc -> {
                    dev.testkit.harness.Latency.remove();
                    return null;
                });
            }
            c.ctx().waitTicks(act == SameTick.Act.CLOSE ? 40 : 5);
            WireCapture.stop();
            List<int[]> btn = tap.buttons();
            int served = c.hx().events(mark, "beacon.click").size();
            int maxPerTick = 0;
            int minGap = 1 << 20;
            for (int i = 0; i < btn.size(); i++) {
                int same = 0;
                for (int[] k : btn) {
                    same += k[0] == btn.get(i)[0] ? 1 : 0;
                }
                maxPerTick = Math.max(maxPerTick, same);
                if (i > 0) {
                    minGap = Math.min(minGap, btn.get(i)[0] - btn.get(i - 1)[0]);
                }
            }
            c.note(String.format(java.util.Locale.ROOT, "autotune %s: %d button clicks on the wire, %d at the server, done=%b, most in one tick %d, closest %d ticks apart",
                    act == null ? (auto ? "plain" : "off") : act.name(), btn.size(), served, done, maxPerTick, minGap));
            if (!auto || !has) {
                c.check(btn.isEmpty() && served == 0 && !done, "Auto Tune clicked with the setting OFF / in a build without it (" + btn.size() + " clicks)");
                return new Result(null, btn);
            }
            c.check(!btn.isEmpty() && served > 0, "Auto Tune sent no click (the feature did not act): wire " + btn.size() + ", server " + served);
            c.check(btn.size() == served, "the wire carried " + btn.size() + " button clicks but the server saw " + served);
            c.check(maxPerTick <= 1, "two Auto Tune clicks in one client tick (" + maxPerTick + ")");
            c.check(btn.size() < 2 || minGap >= delay, "clicks " + minGap + " ticks apart, under Click Delay " + delay);
            if (act == SameTick.Act.CLOSE) {
                int after = c.hx().events(mark, "beacon.click").size();
                c.ctx().waitTicks(40);
                c.check(c.hx().events(mark, "beacon.click").size() == after && !done, "Auto Tune went on after the menu closed (or finished it)");
                SameTick s2 = st;
                st = null;
                SameTick.judge(c, s2, "auto tune close", SameTick.Expect.SHARE);
                return new Result(null, btn);
            }
            if (!done) { // what the server and the Tuner each held when it gave up
                c.note("server " + c.hx().call("menu.beacon.state") + "; tuner tgt " + java.util.Arrays.deepToString((int[][]) Mod.field(TUNER, "tgtRaw"))
                        + " cur " + java.util.Arrays.deepToString((int[][]) Mod.field(TUNER, "curS")) + " guessing " + Mod.field(TUNER, "guessing")
                        + " swap " + Mod.field(TUNER, "swap"));
            }
            c.check(done, "the tune never completed in " + maxTicks + " ticks (clicks " + served + ")");
            if (done) { // 11-25 4:54 PM: tunes completed on a Speed click said "stopped, the menu closed"
                c.waitUntil("Auto Tune's last line", mc -> chat(logMark, "[Auto Tune] done") + chat(logMark, "[Auto Tune] stopped") > 0, 40);
                c.check(chat(logMark, "[Auto Tune] done") == 1 && chat(logMark, "[Auto Tune] stopped") == 0,
                        "a completed tune did not end with Auto Tune's 'done' line");
            }
            if (budget > 0 && done) {
                int took = c.hx().events(mark, "beacon.done").get(0).get("tick").getAsInt();
                c.note("autotune took " + took + " ticks (his hand tune " + budget + ")");
                c.check(took <= budget, "Auto Tune took " + took + " ticks, slower than his " + budget + "-tick hand tune");
            }
            if (act != null) {
                SameTick s2 = st;
                st = null;
                SameTick.judge(c, s2, "auto tune " + act, act == SameTick.Act.CLICK ? SameTick.Expect.SHARE : SameTick.Expect.ACTED);
            }
            return new Result(done ? c.hx().events(mark, "beacon.done").get(0) : null, btn);
        } finally {
            WireCapture.stop();
            PacketTrace.tap = tap.prev;
            if (st != null) {
                SameTick s3 = st;
                c.onClient(mc -> {
                    s3.close();
                    return null;
                });
            }
            c.hx().call("menu.close");
            c.onClient(mc -> {
                Mod.call(cfg, "set", "enabled", wasOn);
                if (has) {
                    Mod.call(cfg, "set", "autoTune", wasAuto);
                    Mod.call(cfg, "set", "tuneDelay", wasDelay);
                }
                return null;
            });
            c.hx().sidebarClear();
        }
    }
}

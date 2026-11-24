package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.PacketTrace;

import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.util.Util;

import java.util.List;

/**
 * Auto Melody's Harp (cheat; autoharp-1120) against {@code HxHarp}, a fake "Harp - Test" whose rows advance like
 * Hypixel's. Every case asserts the feature acted: the fake server judged each note's click (ok), none wrong, none
 * missed, the mod counted the same clicks, and no server tick carried two container clicks.
 * <ul>
 *   <li>1120-autoharp-normal: no lag, no ping.</li>
 *   <li>1120-autoharp-lag: the rows stall for 3-12 ticks at four steps (irregular server ticks), Auto Detect Lag on.</li>
 *   <li>1120-autoharp-ping: a 200 ms round trip (faked in the server and fed to the ping tracker), Auto Detect Ping
 *       on: notes are clicked early and still land.</li>
 *   <li>1120-autoharp-manual: a 200 ms round trip with both auto switches off and Click Offset +200 (the offset is the
 *       whole lead in ms with both off, so the honest value is the round trip; harpmiss-1122).</li>
 *   <li>1120-autoharp-off: the feature OFF clicks nothing; ON, the song list ("Melody's Harp") gets no click.</li>
 *   <li>1120-same-autoharp-click / -key: his own container click lands in every client tick through the screen /
 *       at END (the feature's chords click on consecutive ticks too); run on default and experimental GrimAC.</li>
 *   <li>1121-autoharp-burst: four 250-350 ms server freezes, each paid back in a burst that leaves the next note in the
 *       click row for about one server tick (harpmiss5-1121: the shipped jar got 13 of 17 and a wrong click).</li>
 *   <li>1122-autoharp-ping150 / -ping300 / -ping320: 150, 300 and 320 ms round trips (320: two rows early, the wait
 *       after the board ends just before the next step), Auto Detect Ping on (harpmiss-1122: a click was
 *       timed by client ticks, not by when the board arrived, so a 150 ms trip clicked at the very end of the note's step).</li>
 *   <li>1124-autoharp-spike / -spike-same: his Pachelbel run (autoharp-misses-1124): clicks answered in 100 ms while the
 *       ping tracker reads 200 ms for steps 6-15: 17 of 17 (the shipped jar clicked early off the ping); -same adds his
 *       clicks. -spike-own adds two clicks whose own trip is 400 ms: those two notes are lost late, nothing else, and no
 *       click is handled before its note.</li>
 * </ul>
 */
final class AutoHarpCases {

    private static final String FEATURE = "islands.AutoHarpFeature";
    static final String STEPS = "[[0],[1,4],[2],[3],[5],[6,0],[],[3],[1],[2,5],[4],[0,6],[3],[2]]";
    static final int NOTES = 17;
    /** Server freezes of 250-350 ms ([step, ticks into it, ticks frozen]), each paid back in a burst: before the first note
     *  reaches the click row, on the last tick before a note does, and twice while a note sits in it. */
    private static final String BURSTS = "F[[3,3,5],[6,4,6],[9,1,7],[12,2,5]]";

    private AutoHarpCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1120-autoharp-normal", c -> play(c, 0, "[]", true, true, 0, null));
        MenuSuite.test(s, "1120-autoharp-lag", c -> play(c, 0, "[[2,6],[5,3],[8,12],[11,4]]", true, true, 0, null));
        MenuSuite.test(s, "1120-autoharp-ping", c -> play(c, 200, "[]", true, true, 0, null));
        MenuSuite.test(s, "1120-autoharp-manual", c -> play(c, 200, "[]", false, false, 200, null));
        MenuSuite.test(s, "1120-autoharp-off", AutoHarpCases::off);
        MenuSuite.test(s, "1120-same-autoharp-click", c -> play(c, 0, "[]", true, true, 0, SameTick.Act.SCREEN_CLICK));
        MenuSuite.test(s, "1120-same-autoharp-key", c -> play(c, 0, "[]", true, true, 0, SameTick.Act.CLICK));
        MenuSuite.test(s, "1121-autoharp-burst", c -> play(c, 0, BURSTS, true, true, 0, null));
        MenuSuite.test(s, "1122-autoharp-ping150", c -> play(c, 150, "[]", true, true, 0, null));
        MenuSuite.test(s, "1122-autoharp-ping300", c -> play(c, 300, "[]", true, true, 0, null));
        MenuSuite.test(s, "1122-autoharp-ping320", c -> play(c, 320, "[]", true, true, 0, null));
        MenuSuite.test(s, "1124-autoharp-spike", c -> play(c, 100, "[]", true, true, 0, null, "[]", PONG_HIGH));
        MenuSuite.test(s, "1124-autoharp-spike-same", c -> play(c, 100, "[]", true, true, 0, SameTick.Act.SCREEN_CLICK, "[]", PONG_HIGH));
        MenuSuite.test(s, "1124-autoharp-spike-own", c -> play(c, 100, "[]", true, true, 0, null, OWN_SPIKE, PONG_HIGH));
    }

    /** His Pachelbel run on Hypixel (autoharp-misses-1124): his clicks were answered in about 100 ms while the ping read
     *  200 ms for seconds (his pong 150-201 while notes 42-43 took 91-114 ms); the shipped jar clicked a row early off that
     *  ping and those clicks were handled a tick before their note. [from step, to step, ms]: the ping tracker reads ms while
     *  the shown board is in those steps, from step 6, after the first clicks were answered (before any answer the ping is
     *  all a client has, so a high one at the very first note is not this bug). Every note is savable here: 17 of 17. */
    private static final int[] PONG_HIGH = {6, 15, 200};
    /** -own adds his other kind of miss: clicks whose OWN trip spiked past the 250 ms a note stays (his notes 20, 23, 29, 45:
     *  286-430 ms, the board's timing unchanged). Clicks reaching the server in steps 11-12 take 400 ms; nothing the client
     *  has seen predicts that (the clicks before were answered in 100 ms), so their notes (j=7, j=8: one each) cannot be
     *  saved, and the best any rule can do is lose those two, late, and play the other 15. Round 9 lost two or three more
     *  (13-16 of 17 over runs of one jar): clicks sent a row early off a lead of 160-166 against true 100 ms trips, which
     *  the plings' render-thread timing and the fake's tick coin flip had read long; both are gone (fix10), so the bound
     *  stays 15 with the two lost late and none early. */
    private static final String OWN_SPIKE = "[[11,400,50],[13,100,50]]";
    private static final int OWN_LOST = 2;

    static JsonObject spec(int pingMs, String stalls) {
        return spec(pingMs, stalls, "[]");
    }

    static JsonObject spec(int pingMs, String stalls, String pingSteps) {
        JsonObject o = MenuKit.obj("{}");
        o.add("pingSteps", MenuKit.obj("{\"s\":" + pingSteps + "}").getAsJsonArray("s"));
        o.add("steps", MenuKit.obj("{\"s\":" + STEPS + "}").getAsJsonArray("s"));
        boolean freeze = stalls.startsWith("F");
        o.add(freeze ? "freezes" : "stalls", MenuKit.obj("{\"s\":" + (freeze ? stalls.substring(1) : stalls) + "}").getAsJsonArray("s"));
        o.addProperty("stepTicks", 5);
        o.addProperty("pingMs", pingMs);
        return o;
    }

    private static void feedPing(Session c, int pingMs) {
        long sent = Util.getMillis() - pingMs;
        c.ctx().runOnClient(mc -> Mod.staticCall("util.PingTracker", "onPong", sent));
    }

    /** A number from the feature, or -1 on a jar that lacks the method (the origin jar for the 1124 cases). */
    private static int modInt(Session c, String method) throws Exception {
        try {
            return ((Number) c.onClient(mc -> Mod.staticCall(FEATURE, method))).intValue();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static void set(Session c, Object cfg, String key, Object v) {
        c.ctx().runOnClient(mc -> Mod.call(cfg, "set", key, v));
    }

    private static void play(Session c, int pingMs, String stalls, boolean lag, boolean ping, int offset, SameTick.Act his)
            throws Exception {
        play(c, pingMs, stalls, lag, ping, offset, his, "[]", null);
    }

    /** {@code pingSteps} changes the faked round trip mid-song (HxHarp); {@code pong} = {from step, to step, ms} feeds the
     *  ping tracker another reading over those steps. With {@code pong}, no click may go early: the clicks' own answers
     *  show a trip short enough to click every note once it shows. */
    private static void play(Session c, int pingMs, String stalls, boolean lag, boolean ping, int offset, SameTick.Act his,
            String pingSteps, int[] pong) throws Exception {
        int allowMissed = "[]".equals(pingSteps) ? 0 : OWN_LOST;
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Auto Melody's Harp does not exist");
            return;
        }
        Object cfg = c.onClient(mc -> Mod.field(FEATURE, "CFG"));
        int before = (Integer) c.onClient(mc -> Mod.staticCall(FEATURE, "clickCount"));
        int earlyBefore = (Integer) c.onClient(mc -> Mod.staticCall(FEATURE, "earlyClickCount"));
        SameTick st = null;
        try {
            set(c, cfg, "detectLag", lag);
            set(c, cfg, "detectPing", ping);
            set(c, cfg, "offset", offset);
            set(c, cfg, "enabled", true);
            if (his != null) {
                st = c.onClient(mc -> SameTick.start(his, 1, p -> p instanceof ServerboundContainerClickPacket, 4, 5));
            }
            // The ping tracker's window holds the last five round trips: fill it with this case's own (an earlier case's
            // 400 ms would otherwise still be in it), and keep feeding it every tick while a ping is being faked.
            for (int i = 0; i < 5; i++) {
                feedPing(c, pingMs);
            }
            c.ctx().runOnClient(mc -> PacketTrace.start());
            c.hx().call("menu.harp", spec(pingMs, stalls, pingSteps));
            MenuKit.awaitScreen(c, "Harp - Test", 100);
            int pongHits = 0;
            long leadMin = Long.MAX_VALUE;
            long leadMax = 0;
            int plingsBefore = pong == null ? 0 : modInt(c, "plingCount");
            for (int t = 0; t < 1200 && c.events("harp.done").isEmpty(); t++) {
                int fed = pingMs;
                if (pong != null) {
                    List<JsonObject> r = c.events("harp.render");
                    int shown = r.isEmpty() ? 0 : r.get(r.size() - 1).get("step").getAsInt();
                    fed = shown >= pong[0] && shown <= pong[1] ? pong[2] : pingMs;
                    pongHits += fed == pong[2] ? 1 : 0;
                    long ld = modInt(c, "lead");
                    leadMin = Math.min(leadMin, ld);
                    leadMax = Math.max(leadMax, ld);
                }
                if (fed > 0) {
                    feedPing(c, fed);
                }
                c.ctx().waitTicks(1);
            }
            int perClientTick = c.onClient(mc -> PacketTrace.maxPerTick("container_click"));
            c.ctx().runOnClient(mc -> PacketTrace.stop());
            c.check(!c.events("harp.done").isEmpty(), "the song never finished");
            JsonObject done = c.events("harp.done").get(0);
            int mine = (Integer) c.onClient(mc -> Mod.staticCall(FEATURE, "clickCount")) - before;
            int early = (Integer) c.onClient(mc -> Mod.staticCall(FEATURE, "earlyClickCount")) - earlyBefore;
            List<JsonObject> clicks = c.events("harp.click");
            int maxTick = MenuKit.maxPerTick(MenuKit.clicksPerTick(c));
            c.note("done " + done + ", mod clicks " + mine + " (early " + early + "), fake-server clicks " + clicks.size()
                    + ", max container clicks in one server tick " + maxTick);
            StringBuilder seq = new StringBuilder();
            long worst = 0;
            long first = Long.MAX_VALUE;
            for (JsonObject k : clicks) {
                seq.append(k.get("step").getAsInt()).append(':').append(k.get("col").getAsInt())
                        .append(k.get("ok").getAsBoolean() ? "" : "!").append(' ');
                worst = Math.max(worst, k.has("ms") ? k.get("ms").getAsLong() : 0);
                first = Math.min(first, k.has("ms") ? k.get("ms").getAsLong() : 0);
            }
            StringBuilder dbg = new StringBuilder();
            for (JsonObject r : c.events("harp.render")) {
                dbg.append('R').append(r.get("step").getAsInt()).append('@').append(r.get("at").getAsLong()).append('/').append(r.get("adv").getAsLong()).append(' ');
            }
            for (JsonObject f : c.events("harp.freeze")) {
                dbg.append('F').append(f.get("step").getAsInt()).append('x').append(f.get("ticks").getAsInt()).append('@').append(f.get("at").getAsLong()).append(' ');
            }
            for (JsonObject k : clicks) {
                dbg.append('C').append(k.get("step").getAsInt()).append(':').append(k.get("col").getAsInt())
                        .append(k.get("ok").getAsBoolean() ? "" : "!").append('@').append(k.has("rx") ? k.get("rx").getAsLong() : -1).append(' ');
            }
            c.note("timeline (R step@shown/began, F freeze, C step:col@received, server ms): " + dbg.toString().trim());
            c.note("clicks step:column (! = wrong): " + seq.toString().trim() + "; latest click " + worst
                    + " ms into its step, earliest " + first + " ms; mod timeline early rows now " + Mod.staticCall(FEATURE, "lastEarlyRows"));
            int plings = pong == null ? 0 : modInt(c, "plingCount") - plingsBefore;
            if (pong != null) {
                c.note("plings read " + plings + ", lead " + leadMin + "-" + leadMax + " ms while the ping read " + pong[2]
                        + ", longest pling wait network to render thread " + modInt(c, "plingWaitMax") + " ms");
            }
            c.check(done.get("ok").getAsInt() >= NOTES - allowMissed, "right clicks " + done.get("ok") + " of " + NOTES);
            c.check(done.get("wrong").getAsInt() <= allowMissed, done.get("wrong") + " wrong click(s)");
            c.check(done.get("missed").getAsInt() <= allowMissed, done.get("missed") + " missed note(s)");
            c.check(mine == NOTES, "the mod counted " + mine + " clicks, not " + NOTES);
            c.check(clicks.size() == NOTES, "the server got " + clicks.size() + " harp clicks, not " + NOTES);
            if (pong != null) {
                c.check(plings > 0, "the feature read no pling (answer) to its clicks");
                c.check(pongHits > 0, "the ping tracker was never fed " + pong[2] + " ms");
                // No click is handled before its note: a wrong click may only be a late one of the spike (steps 12-14).
                for (JsonObject k : clicks) {
                    int st0 = k.get("step").getAsInt();
                    c.check(k.get("ok").getAsBoolean() || (allowMissed > 0 && st0 >= 12 && st0 <= 14),
                            "click handled in step " + st0 + " column " + k.get("col") + " missed its note (early, or outside the spike)");
                }
            }
            c.note("most container clicks the client sent in one client tick: " + perClientTick);
            c.check(his != null || perClientTick == 1, "container clicks in one client tick: " + perClientTick);
            if (pingMs >= 150 && (ping || offset >= 200)) {
                c.check(early > 0, "no note was clicked early at a " + pingMs + " ms round trip");
            } else if (pingMs == 0) {
                c.check(early == 0, early + " early click(s) with no ping");
            }
            if (his != null) {
                SameTick.judge(c, st, "auto harp with his " + his, SameTick.Expect.SHARE);
                st = null;
            }
        } finally {
            if (st != null) {
                SameTick last = st;
                c.onClient(mc -> {
                    last.close();
                    return null;
                });
            }
            set(c, cfg, "enabled", false);
            set(c, cfg, "detectLag", true);
            set(c, cfg, "detectPing", true);
            set(c, cfg, "offset", 0);
            MenuKit.reset(c);
        }
    }

    private static void off(Session c) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Auto Melody's Harp does not exist");
            return;
        }
        Object cfg = c.onClient(mc -> Mod.field(FEATURE, "CFG"));
        int before = (Integer) c.onClient(mc -> Mod.staticCall(FEATURE, "clickCount"));
        try {
            set(c, cfg, "enabled", false);
            c.hx().call("menu.harp", spec(0, "[]"));
            MenuKit.awaitScreen(c, "Harp - Test", 100);
            c.ctx().waitTicks(120);
            int off = (Integer) c.onClient(mc -> Mod.staticCall(FEATURE, "clickCount")) - before;
            c.check(off == 0 && c.events("harp.click").isEmpty(), "clicked with the feature OFF: " + off);
            MenuKit.reset(c);
            set(c, cfg, "enabled", true);
            JsonObject list = MenuKit.obj("{\"title\":\"Melody's Harp\",\"rows\":6,\"fill\":true,\"slots\":"
                    + "{\"37\":\"minecraft:quartz_block\",\"40\":\"minecraft:quartz_block\"}}");
            MenuKit.show(c, list);
            MenuKit.awaitScreen(c, "Melody's Harp", 100);
            c.ctx().waitTicks(60);
            int inList = (Integer) c.onClient(mc -> Mod.staticCall(FEATURE, "clickCount")) - before;
            c.check(inList == 0 && c.events("menu.click").isEmpty(), "clicked in the song list: " + inList);
            c.note("OFF: 0 clicks over 120 ticks in a running song; ON in the song list: 0 clicks over 60 ticks");
        } finally {
            set(c, cfg, "enabled", false);
            MenuKit.reset(c);
        }
    }
}

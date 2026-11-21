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
 *   <li>1120-autoharp-manual: a 200 ms round trip with both auto switches off and Click Offset +300.</li>
 *   <li>1120-autoharp-off: the feature OFF clicks nothing; ON, the song list ("Melody's Harp") gets no click.</li>
 *   <li>1120-same-autoharp-click / -key: his own container click lands in every client tick through the screen /
 *       at END (the feature's chords click on consecutive ticks too); run on default and experimental GrimAC.</li>
 * </ul>
 */
final class AutoHarpCases {

    private static final String FEATURE = "islands.AutoHarpFeature";
    private static final String STEPS = "[[0],[1,4],[2],[3],[5],[6,0],[],[3],[1],[2,5],[4],[0,6],[3],[2]]";
    private static final int NOTES = 17;

    private AutoHarpCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1120-autoharp-normal", c -> play(c, 0, "[]", true, true, 0, null));
        MenuSuite.test(s, "1120-autoharp-lag", c -> play(c, 0, "[[2,6],[5,3],[8,12],[11,4]]", true, true, 0, null));
        MenuSuite.test(s, "1120-autoharp-ping", c -> play(c, 200, "[]", true, true, 0, null));
        MenuSuite.test(s, "1120-autoharp-manual", c -> play(c, 200, "[]", false, false, 300, null));
        MenuSuite.test(s, "1120-autoharp-off", AutoHarpCases::off);
        MenuSuite.test(s, "1120-same-autoharp-click", c -> play(c, 0, "[]", true, true, 0, SameTick.Act.SCREEN_CLICK));
        MenuSuite.test(s, "1120-same-autoharp-key", c -> play(c, 0, "[]", true, true, 0, SameTick.Act.CLICK));
    }

    private static JsonObject spec(int pingMs, String stalls) {
        JsonObject o = MenuKit.obj("{}");
        o.add("steps", MenuKit.obj("{\"s\":" + STEPS + "}").getAsJsonArray("s"));
        o.add("stalls", MenuKit.obj("{\"s\":" + stalls + "}").getAsJsonArray("s"));
        o.addProperty("stepTicks", 5);
        o.addProperty("pingMs", pingMs);
        return o;
    }

    private static void feedPing(Session c, int pingMs) {
        long sent = Util.getMillis() - pingMs;
        c.ctx().runOnClient(mc -> Mod.staticCall("util.PingTracker", "onPong", sent));
    }

    private static void set(Session c, Object cfg, String key, Object v) {
        c.ctx().runOnClient(mc -> Mod.call(cfg, "set", key, v));
    }

    private static void play(Session c, int pingMs, String stalls, boolean lag, boolean ping, int offset, SameTick.Act his)
            throws Exception {
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
            c.hx().call("menu.harp", spec(pingMs, stalls));
            MenuKit.awaitScreen(c, "Harp - Test", 100);
            for (int t = 0; t < 1200 && c.events("harp.done").isEmpty(); t++) {
                if (pingMs > 0) {
                    feedPing(c, pingMs);
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
            for (JsonObject k : clicks) {
                seq.append(k.get("step").getAsInt()).append(':').append(k.get("col").getAsInt())
                        .append(k.get("ok").getAsBoolean() ? "" : "!").append(' ');
                worst = Math.max(worst, k.has("ms") ? k.get("ms").getAsLong() : 0);
            }
            c.note("clicks step:column (! = wrong): " + seq.toString().trim() + "; latest click " + worst
                    + " ms into its step; mod timeline early rows now " + Mod.staticCall(FEATURE, "lastEarlyRows"));
            c.check(done.get("ok").getAsInt() == NOTES, "right clicks " + done.get("ok") + " of " + NOTES);
            c.check(done.get("wrong").getAsInt() == 0, done.get("wrong") + " wrong click(s)");
            c.check(done.get("missed").getAsInt() == 0, done.get("missed") + " missed note(s)");
            c.check(mine == NOTES, "the mod counted " + mine + " clicks, not " + NOTES);
            c.note("most container clicks the client sent in one client tick: " + perClientTick);
            c.check(his != null || perClientTick == 1, "container clicks in one client tick: " + perClientTick);
            if (pingMs >= 200 && (ping || offset >= 300)) {
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

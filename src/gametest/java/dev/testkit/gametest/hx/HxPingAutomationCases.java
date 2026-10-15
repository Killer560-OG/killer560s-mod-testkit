package dev.testkit.gametest.hx;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Latency;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/**
 * Mod branch ping-automation (2026-10-11): Blood Camp, Auto Routes, AP3, Terminal Protection and Auto Debuff time
 * themselves by {@code util.PingTracker.automationMs()} - the median of the last five measured round trips - instead
 * of the tab-list latency, which Hypixel leaves at about 1 ms. Every case runs on the real dedicated server with
 * {@link Latency} adding a round trip, the way 724 proves the HUD.
 *
 * <ul>
 *   <li>791 Tracker: nothing on, no requests; each automation feature alone keeps one request a second going, all of
 *   them together still one a second (legit jar: only Blood Camp and Terminal Protection exist to ask); the reading
 *   follows +200 ms; one 2 s pong moves the mean (HUD) but not the median (automation); before the first pong it is the
 *   tab-list value.</li>
 *   <li>792 Blood Camp: a Watcher and a blood mob's skull stand on the server, its move packets sent on server ticks
 *   the case knows. The moment the box turns green / the kill sound is due (ping &gt; time left) comes one measured
 *   round trip earlier with +200 ms. The Trigger Bot (cheat jar, Trigger Offset -1, the default since bloodcamp-1015)
 *   clicks exactly one tick after that kill timer, locally and with +200 ms, so its attack reaches the server after
 *   the countdown's end (read off the server's own attack_entity tick); Trigger Offset +2 clicks exactly 3 ticks
 *   earlier than -1. The Watcher stands in his room tile (-7, -7): the Trigger Bot only works in the Blood Room.</li>
 *   <li>793 Auto Debuff (cheat): the spray lead {@code leadOffset} grows by round(ping / 50) ticks; Ping Compensation off,
 *   it does not.</li>
 *   <li>794 Auto Routes (cheat): a boom that changed nothing waits {@code 4 + ceil(ping / 50)} ticks: 5 locally, 8-9
 *   with +200 ms.</li>
 *   <li>795 AP3 (cheat): the placed-block grace is {@code 2 + ping / 50} ticks: 2 locally, 6 with +200 ms.</li>
 *   <li>796 Terminal Protection: a "Click in order!" menu from the server; the protection window (400 ms) is measured
 *   tick by tick through the mod's own {@code withinProtectionWindow}: about 400 ms locally, about 400 - ping with
 *   +200 ms, 400 again with Subtract Ping off.</li>
 * </ul>
 */
final class HxPingAutomationCases {

    private static final String PING = "util.PingTracker";
    private static final String LAG_CFG = "lagdisplay.LagDisplayConfig";
    private static final String PARTY_CFG = "partycommands.PartyCommandsConfig";
    private static final String BC_CFG = "bloodcamp.BloodCampConfig";
    private static final String BC = "bloodcamp.BloodCampFeature";
    private static final String TQ_CFG = "terminals.TerminalQolConfig";
    private static final String TQ = "terminals.TerminalQolFeature";
    private static final String AD_CFG = "autodebuff.AutoDebuffConfig";
    private static final String AR_CFG = "autoroutes.AutoRoutesConfig";
    private static final String AP3_CFG = "ap3.Ap3Config";
    private static final int LAG_MS = 200;

    private HxPingAutomationCases() {
    }

    static void register(Session s) {
        s.test("791-hx-ping-automation-tracker", HxPingAutomationCases::tracker);
        s.test("792-hx-ping-bloodcamp", HxPingAutomationCases::bloodCamp);
        s.test("793-hx-ping-autodebuff", HxPingAutomationCases::autoDebuff);
        s.test("794-hx-ping-autoroutes-boom", HxPingAutomationCases::autoRoutes);
        s.test("795-hx-ping-ap3-grace", HxPingAutomationCases::ap3);
        s.test("796-hx-ping-terminal-protection", HxPingAutomationCases::terminals);
    }

    // ---- shared -----------------------------------------------------------------------------------------------------

    /** Everything that wants pings switched off (and the Skyblock gate, so a feature switched on is really on). */
    private static HxKit.Settings allOff(Session c) {
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        HxKit.Settings s = new HxKit.Settings(c)
                .custom(() -> Mod.staticCall("util.SkyblockGate", "setEnabled", false),
                        () -> Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas))
                .with(LAG_CFG, "Enabled", false)
                .with(PARTY_CFG, "Enabled", false)
                .with(BC_CFG, "Enabled", false)
                .with(TQ_CFG, "ProtectionEnabled", false);
        if (Mod.isCheat()) {
            s.with(AD_CFG, "Enabled", false).with(AR_CFG, "Enabled", false).with(AP3_CFG, "Enabled", false);
        }
        return s;
    }

    private static int sent(Session c) {
        return c.onClient(mc -> (Integer) Mod.staticCall(PING, "pingsSent"));
    }

    private static int auto(Session c) {
        return c.onClient(mc -> (Integer) Mod.staticCall(PING, "automationMs"));
    }

    /** Requests sent over 80 ticks (after a 25-tick settle so a just-flipped demand is in force). */
    private static int requestsIn80(Session c) {
        c.ctx().waitTicks(25);
        int a = sent(c);
        c.ctx().waitTicks(80);
        return sent(c) - a;
    }

    private static void lagOn(Session c) {
        c.onClient(mc -> {
            Latency.install(LAG_MS);
            return null;
        });
        c.waitUntil("the automation ping follows +" + LAG_MS + " ms",
                mc -> (Integer) Mod.staticCall(PING, "automationMs") >= LAG_MS * 9 / 10, 600);
    }

    private static void lagOff(Session c) {
        c.onClient(mc -> {
            Latency.remove();
            return null;
        });
        c.waitUntil("the automation ping drops back", mc -> (Integer) Mod.staticCall(PING, "automationMs") < 100, 600);
    }

    /** Waits for pings to have been measured with no added latency. */
    private static int baseline(Session c) {
        c.waitUntil("a measured local ping", mc -> (Boolean) Mod.staticCall(PING, "hasMeasurement")
                && (Integer) Mod.staticCall(PING, "automationMs") < 100, 400);
        return auto(c);
    }

    // ---- 791 --------------------------------------------------------------------------------------------------------

    private static void tracker(Session c) {
        boolean cheat = Mod.isCheat();
        try (HxKit.Settings off = allOff(c)) {
            int none = requestsIn80(c);
            c.note("nothing that wants ping on: " + none + " request(s) in 80 ticks");
            c.check(none == 0, none + " ping requests went out with nothing wanting them");

            String[][] features = {
                    {BC_CFG, "Enabled", "Blood Camp", "legit"},
                    {TQ_CFG, "ProtectionEnabled", "Terminal Protection", "legit"},
                    {AD_CFG, "Enabled", "Auto Debuff", "cheat"},
                    {AR_CFG, "Enabled", "Auto Routes", "cheat"},
                    {AP3_CFG, "Enabled", "AP3", "cheat"},
            };
            for (String[] f : features) {
                boolean exists = cheat || f[3].equals("legit");
                if (!exists) {
                    continue;
                }
                int n;
                try (HxKit.Settings on = new HxKit.Settings(c).with(f[0], f[1], true)) {
                    if (f[0].equals(TQ_CFG)) {
                        on.with(TQ_CFG, "ProtectionSubtractPing", true);
                    }
                    if (f[0].equals(AD_CFG)) {
                        on.with(AD_CFG, "PingCompensation", true);
                    }
                    n = requestsIn80(c);
                }
                c.note(f[2] + " alone: " + n + " request(s) in 80 ticks");
                c.check(n >= 3 && n <= 5, f[2] + " on alone sent " + n + " ping requests in 80 ticks, want one a second");
            }
            // Terminal Protection without Subtract Ping does not need it.
            try (HxKit.Settings on = new HxKit.Settings(c).with(TQ_CFG, "ProtectionEnabled", true)
                    .with(TQ_CFG, "ProtectionSubtractPing", false)) {
                int n = requestsIn80(c);
                c.note("Terminal Protection with Subtract Ping off: " + n + " request(s)");
                c.check(n == 0, "Terminal Protection without Subtract Ping still sent " + n + " requests");
            }
            // All of them at once (plus the HUD): still one request a second.
            HxKit.Settings all = new HxKit.Settings(c).with(BC_CFG, "Enabled", true)
                    .with(TQ_CFG, "ProtectionEnabled", true).with(TQ_CFG, "ProtectionSubtractPing", true)
                    .with(LAG_CFG, "Enabled", true).with(LAG_CFG, "ShowPing", true);
            if (cheat) {
                all.with(AD_CFG, "Enabled", true).with(AD_CFG, "PingCompensation", true)
                        .with(AR_CFG, "Enabled", true).with(AP3_CFG, "Enabled", true);
            }
            try (all) {
                int n = requestsIn80(c);
                c.note("every ping user on at once: " + n + " request(s) in 80 ticks");
                c.check(n >= 3 && n <= 5, "with every ping user on, " + n + " requests in 80 ticks - more than one a second");

                // Reading: local, then +200 ms.
                int base = baseline(c);
                lagOn(c);
                int lagged;
                try {
                    lagged = auto(c);
                    int mean = c.onClient(mc -> (Integer) Mod.staticCall(PING, "measuredMs"));
                    c.note("automation ping local " + base + " ms, with +" + LAG_MS + " ms " + lagged + " ms (HUD mean "
                            + mean + ")");
                    c.check(lagged >= LAG_MS * 9 / 10 && lagged < LAG_MS + 150, "automation ping " + lagged
                            + " ms with " + LAG_MS + " ms injected");
                } finally {
                    lagOff(c);
                }

                // One 2 s pong: the HUD's mean jumps, the automation's median does not.
                int before = auto(c);
                c.onClient(mc -> {
                    Mod.staticCall(PING, "onPong", (Long) Mod.staticCall("net.minecraft.util.Util", "getMillis") - 2000L);
                    return null;
                });
                int medianAfter = auto(c);
                int meanAfter = c.onClient(mc -> (Integer) Mod.staticCall(PING, "measuredMs"));
                c.note("one 2000 ms pong: automation " + before + " -> " + medianAfter + " ms, HUD mean " + meanAfter + " ms");
                long spike = c.onClient(mc -> (Long) Mod.staticCall(PING, "lastSampleMs"));
                c.check(spike >= 2000 && meanAfter > 300, "the injected 2000 ms pong never reached the samples (last "
                        + spike + ", mean " + meanAfter + ")");
                c.check(medianAfter < 100, "one late pong moved the automation ping to " + medianAfter + " ms");

                // Before the first pong of a connection: the tab-list value, as before.
                int[] fb = c.onClient(mc -> {
                    Mod.staticCall(PING, "reset");
                    return new int[]{(Boolean) Mod.staticCall(PING, "hasMeasurement") ? 1 : 0,
                            (Integer) Mod.staticCall(PING, "automationMs"), (Integer) Mod.staticCall(PING, "tabListMs")};
                });
                c.note("before the first pong: measured " + (fb[0] == 1) + ", automation " + fb[1] + " ms, tab list "
                        + fb[2] + " ms");
                c.check(fb[0] == 0 && fb[1] == Math.max(0, fb[2]), "before any pong the automation ping is " + fb[1]
                        + " ms, not the tab list's " + fb[2]);
                c.waitUntil("pings are measured again", mc -> (Boolean) Mod.staticCall(PING, "hasMeasurement"), 200);
            }
        }
    }

    // ---- 792 --------------------------------------------------------------------------------------------------------

    private record Trip(boolean tracked, double killAt, int killPing, double triggerAt, int clicks, int attacks,
                        Double serverLead) {
    }

    private static String head(String texture) {
        return "player_head[profile={properties:[{name:\"textures\",value:\"" + texture + "\"}]}]";
    }

    private static String anyOf(Object set) {
        return (String) ((Collection<?>) set).iterator().next();
    }

    /** The one tracked mob's predicted landing spot, or null. */
    private static Vec3 endVector() {
        Map<?, ?> mobs = (Map<?, ?>) Mod.field(Mod.ROOT + BC, "bloodMobs");
        for (Object data : mobs.values()) {
            Object end = Mod.field(data, "endVector");
            if (end != null) {
                return (Vec3) end;
            }
        }
        return null;
    }

    /**
     * A blood mob trip timed on the SERVER's clock: three move packets for a skull stand, one a tick, each sent by the
     * server on a tick it reports (Hx {@code entity.nudge}; the tracker's own cadence would hide which tick the trip
     * began on). The mod's countdown starts at the first packet it receives, so the mob is due on server tick
     * {@code first + (first wave ? 78.8 : 38.8)}. A husk stands on the predicted landing spot, he looks at it, and the
     * server's own {@code attack_entity} tick gives how early the attack arrived.
     */
    private static Trip trip(Session c, String mobTex) {
        boolean firstWave = c.onClient(mc -> (Boolean) Mod.staticCall("bloodcamp.BloodCampMoveTimer", "isFirstSpawns"));
        double scale = firstWave ? 16.1 : 11.9;
        double due = (firstWave ? 40 : 0) + 38 + 0.8;
        double y = 151.0;
        double z = -417.5;
        // The mod's start is the stand's spot + the packet's WHOLE blocks (Noamm's integer division): 1 for 1.2.
        double x0 = -420.5 - scale - 1.0;
        c.onClient(mc -> {
            Mod.setField(BC, "lastKillableAt", Double.NaN);
            Mod.setField(BC, "lastTriggerAt", Double.NaN);
            return null;
        });
        int clicks0 = c.onClient(mc -> (Integer) Mod.staticCall(BC, "triggerClicks"));
        int attacks0 = c.onClient(mc -> (Integer) Mod.staticCall(BC, "triggerAttacks"));
        String uuid = c.hx().stand(x0, y, z, null, "marker", false, "invisible", false, "helmet", head(mobTex));
        boolean tracked = false;
        long firstTick = -1;
        long since;
        try {
            c.ctx().waitTicks(10);
            for (int i = 0; i < 3; i++) {
                long t = c.hx().call("entity.nudge", "uuid", uuid, "dx", 1.2).getAsLong();
                if (firstTick < 0) {
                    firstTick = t;
                }
                c.ctx().waitTicks(1);
            }
            c.waitUntil("the trip is tracked", mc -> endVector() != null, 100);
            tracked = true;
            Vec3 end = c.onClient(mc -> endVector());
            c.server().command(String.format(Locale.ROOT, "summon husk %.3f %.3f %.3f {Tags:[\"pnghusk\"],NoAI:1b,"
                    + "Silent:1b,Invulnerable:1b,PersistenceRequired:1b}", end.x, end.y, end.z));
            since = c.hx().head();
            for (int i = 0; i < 160; i++) {
                Boolean done = c.onClient(mc -> {
                    Vec3 e = endVector();
                    if (e == null) {
                        return false;
                    }
                    Vec3 eye = mc.player.getEyePosition();
                    Vec3 d = e.add(0, 1.0, 0).subtract(eye);
                    mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
                    mc.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
                    double killAt = (Double) Mod.staticCall(BC, "lastKillableAt");
                    double trig = (Double) Mod.staticCall(BC, "lastTriggerAt");
                    return !Double.isNaN(killAt) && (!Mod.isCheat() || !Double.isNaN(trig));
                });
                if (done) {
                    break;
                }
                c.ctx().waitTicks(1);
            }
            c.ctx().waitTicks(10); // the attack's packet reaches the server
        } finally {
            c.hx().call("entity.remove", "uuid", uuid);
            c.server().command("kill @e[tag=pnghusk]");
            c.ctx().waitTicks(5);
        }
        Double serverLead = null;
        for (com.google.gson.JsonObject e : c.hx().events(since, "attack_entity")) {
            serverLead = firstTick + due - e.get("tick").getAsLong();
            break;
        }
        double killAt = c.onClient(mc -> (Double) Mod.staticCall(BC, "lastKillableAt"));
        int killPing = c.onClient(mc -> (Integer) Mod.staticCall(BC, "lastKillablePingMs"));
        double trig = c.onClient(mc -> (Double) Mod.staticCall(BC, "lastTriggerAt"));
        int clicks = c.onClient(mc -> (Integer) Mod.staticCall(BC, "triggerClicks")) - clicks0;
        int attacks = c.onClient(mc -> (Integer) Mod.staticCall(BC, "triggerAttacks")) - attacks0;
        return new Trip(tracked, killAt, killPing, trig, clicks, attacks, serverLead);
    }

    private static String fmt(Trip t) {
        return String.format(Locale.ROOT, "killable at %.1f ticks left (ping %d ms), trigger %s, %d click(s), %d attack(s),"
                        + " attack reached the server %s", t.killAt, t.killPing,
                Double.isNaN(t.triggerAt) ? "none" : String.format(Locale.ROOT, "at %.1f ticks left", t.triggerAt),
                t.clicks, t.attacks, t.serverLead == null ? "never" : String.format(Locale.ROOT, "%.1f ticks early",
                        t.serverLead));
    }

    private static void bloodCamp(Session c) {
        boolean cheat = Mod.isCheat();
        String watcherTex = c.onClient(mc -> anyOf(Mod.field(Mod.ROOT + BC, "WATCHER_SKULL_TEXTURES")));
        String mobTex = c.onClient(mc -> anyOf(Mod.field(Mod.ROOT + BC, "MOB_SKULL_TEXTURES")));
        try (HxKit.Settings off = allOff(c)) {
            HxKit.Settings on = new HxKit.Settings(c)
                    .custom(() -> Mod.staticCall("secrets.DungeonState", "setRoomSim", true),
                            () -> Mod.staticCall("secrets.DungeonState", "setRoomSim", false))
                    .with(BC_CFG, "Enabled", true)
                    .with(BC_CFG, "ShowOverlay", true)
                    .with(BC_CFG, "CountdownStartSound", false)
                    .with(BC_CFG, "KillSound", false)
                    .with(BC_CFG, "AuraEnabled", false)
                    .with(BC_CFG, "AutoDetectLag", true)
                    .with(BC_CFG, "TriggerOffsetTicks", -1);
            if (cheat) {
                on.with(BC_CFG, "TriggerBotEnabled", true);
            }
            try (on) {
                c.onClient(mc -> {
                    mc.player.closeContainer();
                    return null;
                });
                // In his room tile (-7, -7) - the Trigger Bot only works inside the Blood Room (bloodcamp-1015).
                c.hx().stand(-416.5, 151, -412.5, null, "marker", false, "invisible", false, "helmet", head(watcherTex));
                c.waitUntil("Blood Camp sees the Watcher", mc -> Mod.field(Mod.ROOT + BC, "watcherEntityId") != null, 200);

                int base = baseline(c);
                Trip a = trip(c, mobTex);
                c.note("local (" + base + " ms): " + fmt(a));
                lagOn(c);
                Trip b;
                int lagged;
                try {
                    lagged = auto(c);
                    b = trip(c, mobTex);
                    c.note("+" + LAG_MS + " ms (" + lagged + " ms): " + fmt(b));
                } finally {
                    lagOff(c);
                }
                // Trigger Offset +2 instead of -1 must click exactly 3 ticks earlier.
                Trip h = null;
                if (cheat) {
                    c.onClient(mc -> {
                        Mod.call(Mod.cfg(BC_CFG), "setTriggerOffsetTicks", 2);
                        return null;
                    });
                    h = trip(c, mobTex);
                    c.note("local, Trigger Offset +2: " + fmt(h));
                }
                c.check(a.tracked && b.tracked, "a trip was never tracked - nothing to measure");
                // The box / kill sound: unchanged, Noamm's ping > time left.
                double want = (b.killPing - a.killPing) / 50.0;
                double shift = b.killAt - a.killAt;
                c.note(String.format(Locale.ROOT, "kill moment came %.1f ticks earlier; the ping difference is %.1f ticks",
                        shift, want));
                c.check(!Double.isNaN(a.killAt) && a.killAt <= 0.05 && a.killAt > -1.05,
                        "locally the box turned green at " + a.killAt + " ticks left, want the kill moment (0)");
                c.check(Math.abs(shift - want) <= 1.0 && shift >= 3.0, String.format(Locale.ROOT,
                        "with +%d ms the kill moment moved %.1f ticks, want about %.1f", LAG_MS, shift, want));
                if (cheat) {
                    c.check(a.clicks == 1 && b.clicks == 1 && a.attacks == 1 && b.attacks == 1,
                            "want one attack per trip, got clicks " + a.clicks + "/" + b.clicks + ", attacks " + a.attacks
                                    + "/" + b.attacks);
                    // killer560 2026-10-15: "Triggerbot was attacking them before the kill timer actually went off".
                    c.check(Math.abs((a.killAt - a.triggerAt) - 1.0) < 0.01 && Math.abs((b.killAt - b.triggerAt) - 1.0) < 0.01,
                            "Trigger Offset -1: the click went " + (a.killAt - a.triggerAt) + " / " + (b.killAt - b.triggerAt)
                                    + " ticks after the kill timer, want exactly 1");
                    c.check(a.serverLead != null && a.serverLead < 0 && a.serverLead > -4.5, "at " + base
                            + " ms the attack reached the server " + a.serverLead + " ticks before due, want just after it");
                    c.check(b.serverLead != null && b.serverLead < 0 && b.serverLead > -4.5, "with +" + LAG_MS
                            + " ms the attack reached the server " + b.serverLead + " ticks before due, want just after it");
                    // The client fired one round trip earlier to get there.
                    double fireShift = b.triggerAt - a.triggerAt;
                    c.note(String.format(Locale.ROOT, "client fired %.1f ticks earlier with +%d ms (%.1f ticks of ping)",
                            fireShift, LAG_MS, lagged / 50.0));
                    c.check(Math.abs(fireShift - lagged / 50.0) <= 1.0, "the client's click moved " + fireShift
                            + " ticks with " + lagged + " ms");
                    c.check(Math.abs((h.triggerAt - a.triggerAt) - 3.0) < 0.01, "Trigger Offset +2 against -1 moved the"
                            + " click " + (h.triggerAt - a.triggerAt) + " ticks, want exactly 3");
                    c.check(h.serverLead != null && a.serverLead != null
                                    && Math.abs((h.serverLead - a.serverLead) - 3.0) <= 1.0 + 1e-6,
                            "Trigger Offset +2 against -1 moved the server-side hit " + (h.serverLead == null
                                    || a.serverLead == null ? "?" : String.valueOf(h.serverLead - a.serverLead))
                                    + " ticks, want 3 (+-1)");
                } else {
                    c.check(a.clicks == 0 && b.clicks == 0 && a.serverLead == null && b.serverLead == null,
                            "legit jar: the Trigger Bot attacked");
                }
            } finally {
                c.hx().removeStands();
            }
        }
    }

    // ---- 793-795 ----------------------------------------------------------------------------------------------------

    /** A timing function read locally and with +200 ms, with {@code cfg} feature on (it keeps the pings going). */
    private static int[] localAndLagged(Session c, java.util.function.Function<Minecraft, Integer> fn) {
        baseline(c);
        int local = c.onClient(fn);
        int pingLocal = auto(c);
        lagOn(c);
        try {
            int lagged = c.onClient(fn);
            int pingLagged = auto(c);
            return new int[]{local, pingLocal, lagged, pingLagged};
        } finally {
            lagOff(c);
        }
    }

    private static void autoDebuff(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: no Auto Debuff");
            return;
        }
        try (HxKit.Settings off = allOff(c);
             HxKit.Settings on = new HxKit.Settings(c).with(AD_CFG, "Enabled", true)
                     .with(AD_CFG, "PingCompensation", true).with(AD_CFG, "ManualOffsetTicks", 0)) {
            int[] r = localAndLagged(c, mc -> (Integer) Mod.staticCall("autodebuff.AutoDebuffFeature", "leadOffset", mc,
                    Mod.cfg(AD_CFG)));
            c.note("spray lead: " + r[0] + " tick(s) at " + r[1] + " ms, " + r[2] + " tick(s) at " + r[3] + " ms");
            c.check(r[0] == Math.round(r[1] / 50.0f), "local lead " + r[0]);
            c.check(r[2] == Math.round(r[3] / 50.0f) && r[2] >= 3, "with +" + LAG_MS + " ms the lead is " + r[2]
                    + " tick(s), want round(" + r[3] + " / 50)");
            // Compensation off: no lead whatever the ping (and nothing else wanting it, so requests stop).
            c.onClient(mc -> {
                Mod.call(Mod.cfg(AD_CFG), "setPingCompensation", false);
                return null;
            });
            int offLead = c.onClient(mc -> (Integer) Mod.staticCall("autodebuff.AutoDebuffFeature", "leadOffset", mc,
                    Mod.cfg(AD_CFG)));
            c.check(offLead == 0, "Ping Compensation off still leads by " + offLead);
        }
    }

    private static void autoRoutes(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: no Auto Routes");
            return;
        }
        try (HxKit.Settings off = allOff(c);
             HxKit.Settings on = new HxKit.Settings(c).with(AR_CFG, "Enabled", true)) {
            int[] r = localAndLagged(c, mc -> (Integer) Mod.staticCall("autoroutes.RouteExecutor", "boomSettleTicks"));
            c.note("boom settle: " + r[0] + " tick(s) at " + r[1] + " ms, " + r[2] + " tick(s) at " + r[3] + " ms");
            c.check(r[0] == Math.min(20, Math.max(4, 4 + (r[1] + 49) / 50)), "local settle " + r[0]);
            c.check(r[2] == 4 + (r[3] + 49) / 50 && r[2] >= 8, "with +" + LAG_MS + " ms a boom settles in " + r[2]
                    + " tick(s), want 4 + ceil(" + r[3] + " / 50)");
        }
    }

    private static void ap3(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: no AP3");
            return;
        }
        try (HxKit.Settings off = allOff(c);
             HxKit.Settings on = new HxKit.Settings(c).with(AP3_CFG, "Enabled", true)) {
            int[] r = localAndLagged(c, mc -> (Integer) Mod.staticCall("ap3.Ap3Executor", "placedGraceTicks"));
            c.note("placed-block grace: " + r[0] + " tick(s) at " + r[1] + " ms, " + r[2] + " tick(s) at " + r[3] + " ms");
            c.check(r[0] == 2 + r[1] / 50, "local grace " + r[0]);
            c.check(r[2] == 2 + r[3] / 50 && r[2] >= 5, "with +" + LAG_MS + " ms the grace is " + r[2]
                    + " tick(s), want 2 + " + r[3] + " / 50");
        }
    }

    // ---- 796 --------------------------------------------------------------------------------------------------------

    /** Opens a Numbers terminal from the server and measures, tick by tick, how long Protection holds clicks off. */
    private static long protectionWindowMs(Session c) {
        c.hx().menu("Click in order!", 4, Map.of(), null);
        c.waitUntil("the terminal opens", mc -> McCompat.screen(mc) instanceof AbstractContainerScreen<?> s
                && s.getTitle().getString().equals("Click in order!"), 200);
        long[] out = {-1};
        try {
            for (int i = 0; i < 40; i++) {
                boolean within = c.onClient(mc -> (Boolean) Mod.staticCall(TQ, "withinProtectionWindow"));
                if (!within) {
                    long opened = c.onClient(mc -> (Long) Mod.field(Mod.ROOT + TQ, "terminalOpenedAtMs"));
                    out[0] = System.currentTimeMillis() - opened;
                    break;
                }
                c.ctx().waitTicks(1);
            }
        } finally {
            c.onClient(mc -> {
                mc.player.closeContainer();
                return null;
            });
            c.ctx().waitTicks(5);
        }
        return out[0];
    }

    private static void terminals(Session c) {
        try (HxKit.Settings off = allOff(c);
             HxKit.Settings on = new HxKit.Settings(c).with(TQ_CFG, "ProtectionEnabled", true)
                     .with(TQ_CFG, "ProtectionThresholdMs", 400).with(TQ_CFG, "ProtectionSubtractPing", true)) {
            int base = baseline(c);
            long local = protectionWindowMs(c);
            lagOn(c);
            long lagged;
            long noSubtract;
            int ping;
            try {
                ping = auto(c);
                lagged = protectionWindowMs(c);
                c.onClient(mc -> {
                    Mod.call(Mod.cfg(TQ_CFG), "setProtectionSubtractPing", false);
                    return null;
                });
                noSubtract = protectionWindowMs(c);
                c.onClient(mc -> {
                    Mod.call(Mod.cfg(TQ_CFG), "setProtectionSubtractPing", true);
                    return null;
                });
            } finally {
                lagOff(c);
            }
            c.note("protection window: " + local + " ms at " + base + " ms ping, " + lagged + " ms at " + ping
                    + " ms, " + noSubtract + " ms at that ping with Subtract Ping off");
            c.check(local >= 400 - base - 10 && local <= 400 - base + 70, "locally the window was " + local + " ms");
            c.check(Math.abs(lagged - (400 - ping)) <= 70, "with +" + LAG_MS + " ms the window was " + lagged
                    + " ms, want about " + (400 - ping));
            c.check(noSubtract >= 390 && noSubtract <= 470, "Subtract Ping off: the window was " + noSubtract + " ms");
        }
    }
}

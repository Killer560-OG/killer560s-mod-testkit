package dev.testkit.gametest.boss;

import com.google.gson.JsonObject;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * killer560, 2026-11-02: "if i leap to someone and they are in one of the posmsg waypoints it tries to send that at the
 * same time as other chat messages. delay it till the leap messages go off." (mod posmsgleap-1102, util/OutgoingChat).
 * His 26.3 log: "Leaping to X" went, the Posmsg line left in the same tick and Hypixel answered "Woah slow down, you're
 * doing that too fast!"; the Cringe line 10 ticks later went through.
 * <ul>
 *   <li>1102-posmsg-leap-tp-first: a Spirit Leap menu, then the teleport into the ring, THEN "You have teleported to
 *       HxMateA!" a few ticks later. The server must get "Leaping to", the Cringe line and the Posmsg line in that
 *       order, each at least {@link #MIN_GAP_TICKS} server ticks after the one before. On main's jar the Posmsg line
 *       goes the tick he lands, first.</li>
 *   <li>1102-posmsg-leap-line-first: the leap line, then the teleport. Same order and gaps. On main's jar the Posmsg
 *       line goes one to three ticks after "Leaping to", before Cringe.</li>
 *   <li>1102-posmsg-alone: a teleport into the ring with no leap menu and nothing else waiting: the Posmsg line must
 *       reach the server within {@link #ALONE_MAX_TICKS} ticks (never held).</li>
 * </ul>
 * The Hx server does not model Hypixel's chat limiter; the gap assertion stands in for "no rate-limit line": 250 ms
 * (the mod's OutgoingChat.MIN_GAP_MS, his choice 2026-11-11) is the gap.
 */
final class PosmsgLeapCases {

    static final String FEATURE = "posmsg.PosmsgFeature";
    static final String CONFIG = "posmsg.PosmsgConfig";
    /** 250 ms is 5 ticks; one tick of slack for where in the server tick each packet lands. */
    static final int MIN_GAP_TICKS = 4;
    static final int ALONE_MAX_TICKS = 6;
    static final String POS_LINE = "Hx at ee2";
    static final String LEAP_LINE = "Hx leaping to HxMateA";

    static final int AX = 70;
    static final int BX = 82;
    static final int Y = 180;
    static final int Z = 70;

    private PosmsgLeapCases() {
    }

    static void register(Session s) {
        // Alone first: a leap case's menu sighting must not be within Posmsg's leap window when it runs.
        s.test("1102-posmsg-alone", PosmsgLeapCases::alone);
        s.test("1102-posmsg-leap-tp-first", c -> leap(c, true));
        s.test("1102-posmsg-leap-line-first", c -> leap(c, false));
    }

    /** F7 boss (sidebar, x/z inside the boss box), two platforms, a ring on B, Leap Message and Cringe on. */
    private static List<AutoCloseable> setUp(Session c) {
        List<AutoCloseable> undo = new ArrayList<>();
        c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
        c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
        DungeonFixBossCases.floor(c, AX, Y, Z);
        DungeonFixBossCases.floor(c, BX, Y, Z);
        TerminalAuraCases.tp(c, AX + 0.5, Y, Z + 0.5, 0f);
        c.waitUntil("Floor7Tracker.inF7Boss", mc -> Boolean.TRUE.equals(Mod.staticCall("fastleap.Floor7Tracker", "inF7Boss")), 100);

        Object cfg = c.onClient(mc -> Mod.cfg(CONFIG));
        boolean wasOn = c.onClient(mc -> (Boolean) Mod.call(cfg, "isEnabledRaw"));
        c.onClient(mc -> {
            Mod.call(cfg, "setEnabled", true);
            return null;
        });
        undo.add(() -> c.onClient(mc -> Mod.call(cfg, "setEnabled", wasOn)));
        String id = c.onClient(mc -> {
            Object e = Mod.call(cfg, "addNew");
            set(e, "name", "Hx EE2");
            set(e, "message", POS_LINE);
            set(e, "x", BX + 0.5);
            set(e, "y", (double) Y);
            set(e, "z", Z + 0.5);
            set(e, "radius", 2.0);
            set(e, "enabled", true);
            set(e, "configured", true);
            return (String) Mod.field(e, "id");
        });
        undo.add(() -> c.onClient(mc -> Mod.call(cfg, "remove", id)));
        undo.add(c.onClient(mc -> Mod.with("leapmessage.LeapMessageConfig", "Enabled", true)));
        undo.add(c.onClient(mc -> Mod.with("leapmessage.LeapMessageConfig", "LeapingToEnabled", true)));
        undo.add(c.onClient(mc -> Mod.with("leapmessage.LeapMessageConfig", "CringeEnabled", true)));
        undo.add(c.onClient(mc -> Mod.with("leapmessage.LeapMessageConfig", "CustomMessage", "Hx leaping to {name}")));
        undo.add(c.onClient(mc -> Mod.with("cringe.CringeConfig", "Enabled", true)));
        undo.add(c.onClient(mc -> Mod.with("emotes.ChatEmoteConfig", "Enabled", false)));
        undo.add(c.onClient(mc -> Mod.with("translate.TranslateConfig", "Enabled", false)));
        c.onClient(mc -> {
            Mod.setField(FEATURE, "lastSendMs", 0L);
            return null;
        });
        // Let any earlier case's line clear the queue's gap.
        c.ctx().waitTicks(20);
        return undo;
    }

    private static void set(Object target, String field, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getField(field);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot set PosmsgEntry." + field, e);
        }
    }

    private static void tearDown(Session c, List<AutoCloseable> undo) throws Exception {
        TerminalAuraCases.closeScreen(c);
        for (int i = undo.size() - 1; i >= 0; i--) {
            undo.get(i).close();
        }
        c.hx().sidebarClear();
    }

    private static void leap(Session c, boolean tpFirst) throws Exception {
        List<AutoCloseable> undo = setUp(c);
        try {
            long head = c.hx().head();
            c.hx().menu("Spirit Leap", 3, Map.of(11, "minecraft:player_head"), null);
            c.waitUntil("the Spirit Leap menu opens", mc -> McCompat.screen(mc) instanceof AbstractContainerScreen<?> s
                    && s.getTitle().getString().contains("Spirit Leap"), 60);
            c.ctx().waitTicks(3);
            // The click: Hypixel closes the menu, teleports and says "You have teleported to".
            TerminalAuraCases.closeScreen(c);
            if (tpFirst) {
                c.hx().call("dungeon.tp", "x", BX + 0.5, "y", (double) Y, "z", Z + 0.5, "yaw", 0f, "pitch", 0f);
                c.waitUntil("the client in the ring", mc -> mc.player != null
                        && mc.player.position().distanceTo(new Vec3(BX + 0.5, Y, Z + 0.5)) < 0.05, 100);
                c.ctx().waitTicks(4);
                c.hx().chat("§dYou have teleported to §bHxMateA§d!");
            } else {
                c.hx().chat("§dYou have teleported to §bHxMateA§d!");
                c.ctx().waitTicks(2);
                c.hx().call("dungeon.tp", "x", BX + 0.5, "y", (double) Y, "z", Z + 0.5, "yaw", 0f, "pitch", 0f);
            }
            c.waitUntil("three party lines at the server", mc -> pcEvents(c, head).size() >= 3, 120);
            c.ctx().waitTicks(20);
            List<JsonObject> pcs = pcEvents(c, head);
            List<String> lines = new ArrayList<>();
            List<Long> ticks = new ArrayList<>();
            for (JsonObject e : pcs) {
                lines.add(e.get("command").getAsString());
                ticks.add(e.get("tick").getAsLong());
            }
            c.note((tpFirst ? "teleport, then leap line" : "leap line, then teleport") + ": " + lines + " at server ticks "
                    + ticks);
            c.check(pcs.size() == 3, "want exactly 3 party lines, got " + lines);
            c.check(lines.get(0).equals("pc " + LEAP_LINE), "first line is not the leap announce: " + lines);
            c.check(!lines.get(1).contains(LEAP_LINE) && !lines.get(1).contains(POS_LINE),
                    "second line is not the Cringe line: " + lines);
            c.check(lines.get(2).equals("pc " + POS_LINE), "the Posmsg line is not last: " + lines);
            for (int i = 1; i < ticks.size(); i++) {
                long gap = ticks.get(i) - ticks.get(i - 1);
                c.check(gap >= MIN_GAP_TICKS, "lines " + (i - 1) + " and " + i + " only " + gap + " ticks apart: " + lines
                        + " " + ticks);
            }
        } finally {
            tearDown(c, undo);
        }
    }

    private static void alone(Session c) throws Exception {
        List<AutoCloseable> undo = setUp(c);
        try {
            long head = c.hx().head();
            c.hx().call("dungeon.tp", "x", BX + 0.5, "y", (double) Y, "z", Z + 0.5, "yaw", 0f, "pitch", 0f);
            long sentAt = c.hx().call("server.tick").getAsLong();
            c.waitUntil("the Posmsg line at the server", mc -> !pcEvents(c, head).isEmpty(), 60);
            c.ctx().waitTicks(20);
            List<JsonObject> pcs = pcEvents(c, head);
            long took = pcs.get(0).get("tick").getAsLong() - sentAt;
            c.note("teleport into the ring with nothing waiting: " + pcs.get(0).get("command").getAsString() + " "
                    + took + " server tick(s) after the teleport; " + pcs.size() + " line(s)");
            c.check(pcs.size() == 1 && pcs.get(0).get("command").getAsString().equals("pc " + POS_LINE),
                    "want only the Posmsg line, got " + pcs);
            c.check(took <= ALONE_MAX_TICKS, "the Posmsg line waited " + took + " ticks with nothing else pending");
        } finally {
            tearDown(c, undo);
        }
    }

    private static List<JsonObject> pcEvents(Session c, long head) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject e : c.hx().events(head, "cmd")) {
            if (e.get("command").getAsString().startsWith("pc ")) {
                out.add(e);
            }
        }
        return out;
    }
}

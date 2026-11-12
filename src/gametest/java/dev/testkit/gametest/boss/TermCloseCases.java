package dev.testkit.gametest.boss;

import com.google.gson.JsonObject;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.util.ArrayList;
import java.util.List;

/**
 * killer560, 2026-11-11 on real Hypixel F7: "something keeps closing my terminals" (mod termclose-1111). His logs: every
 * terminal Hypixel closed unsolved had one of the mod's own party lines (Leap Message, Cringe, Posmsg) sent just before or
 * as it opened; none closed with nothing in flight. util/OutgoingChat now holds its lines while he stands at a P3
 * terminal, from his click on the stand until a menu opens, and until the terminal has been open 250 ms.
 * <ul>
 *   <li>1111-termclose-click: a real interact on the Hx terminal stand and a queued mod line in the same tick. The line
 *       must not reach the server until the terminal has been open about 250 ms, the terminal must still be open when it
 *       does, and it must arrive (held, never dropped).</li>
 *   <li>1111-termclose-free: the same spot with no click: the line goes within a few ticks (the hold acts only on a
 *       terminal).</li>
 *   <li>1111-termclose-near: P3 running, standing 2.5 blocks from the S1 terminal 1 spot (P3 Status' table): the line
 *       waits about 250 ms (NEAR_WAIT_MS) and then goes anyway.</li>
 * </ul>
 */
final class TermCloseCases {

    static final String LINE = "Hx termclose line";
    static final String SOLVER_CFG = "terminals.TerminalSolverConfig";
    /** OutgoingChat.TERMINAL_SETTLE_MS is 250 ms = 5 ticks; slack for where in a server tick each packet lands. */
    static final int SETTLE_MIN_TICKS = 4;

    private TermCloseCases() {
    }

    static void register(Session s) {
        s.test("1111-termclose-free", c -> free(c));
        s.test("1111-termclose-click", c -> click(c));
        s.test("1111-termclose-near", c -> near(c));
    }

    private static List<AutoCloseable> setUp(Session c) {
        List<AutoCloseable> undo = new ArrayList<>();
        undo.add(c.onClient(mc -> Mod.with(SOLVER_CFG, "AutoTerminalsEnabled", false)));
        if (Mod.isCheat()) {
            undo.add(c.onClient(mc -> Mod.with(TerminalAuraCases.CONFIG, "Enabled", false)));
        }
        c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
        c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
        c.hx().call("boss.term.build", "x", TerminalAuraCases.X, "z", TerminalAuraCases.Z, "groundY", TerminalAuraCases.G);
        TerminalAuraCases.tp(c, TerminalAuraCases.X - 1.5, TerminalAuraCases.G + 3, TerminalAuraCases.Z + 0.5, -90f);
        c.ctx().waitTicks(30); // past any earlier line's gap and echo wait
        return undo;
    }

    private static void tearDown(Session c, List<AutoCloseable> undo) throws Exception {
        TerminalAuraCases.closeScreen(c);
        for (int i = undo.size() - 1; i >= 0; i--) {
            undo.get(i).close();
        }
        c.hx().sidebarClear();
    }

    private static void queue() {
        Mod.staticCall("util.OutgoingChat", "command", "pc " + LINE);
    }

    private static void free(Session c) throws Exception {
        List<AutoCloseable> undo = setUp(c);
        try {
            long head = c.hx().head();
            long at = c.hx().call("server.tick").getAsLong();
            c.onClient(mc -> {
                queue();
                return null;
            });
            c.waitUntil("the line at the server", mc -> !lines(c, head).isEmpty(), 40);
            long took = lines(c, head).get(0).get("tick").getAsLong() - at;
            c.note("no terminal: the line reached the server " + took + " tick(s) after it was queued");
            c.check(took <= 6, "with no terminal the line still waited " + took + " ticks");
        } finally {
            tearDown(c, undo);
        }
    }

    private static void click(Session c) throws Exception {
        List<AutoCloseable> undo = setUp(c);
        try {
            JsonObject st = c.hx().call("boss.term.spawn").getAsJsonObject();
            int standId = st.get("standId").getAsInt();
            c.waitUntil("the client to have the terminal stand", mc -> mc.level.getEntity(standId) != null, 200);
            c.ctx().waitTicks(5);
            long head = c.hx().head();
            int interacts = c.events("term.interact").size();
            // His click on the stand and a mod line asked for in the same tick (a Posmsg / Cringe line as he clicks).
            c.onClient(mc -> {
                var stand = mc.level.getEntity(standId);
                var eye = mc.player.getEyePosition();
                var hit = stand.getBoundingBox().inflate(0.1).clip(eye, eye.add(mc.player.getViewVector(1f).scale(5)))
                        .orElse(stand.getBoundingBox().getCenter());
                mc.gameMode.interact(mc.player, stand, new net.minecraft.world.phys.EntityHitResult(stand, hit),
                        net.minecraft.world.InteractionHand.MAIN_HAND);
                queue();
                return null;
            });
            c.waitUntil("the click reaching the server", mc -> c.events("term.interact").size() > interacts, 40);
            c.waitUntil("the terminal screen", mc -> terminalOpen(mc), 60);
            long openTick = c.hx().call("server.tick").getAsLong();
            c.check(lines(c, head).isEmpty(), "the line went before the terminal opened: " + lines(c, head));
            c.waitUntil("the held line at the server", mc -> !lines(c, head).isEmpty(), 80);
            boolean stillOpen = c.onClient(mc -> terminalOpen(mc));
            List<JsonObject> got = lines(c, head);
            long gap = got.get(0).get("tick").getAsLong() - openTick;
            c.note("click + line in one tick: line at the server " + gap + " server tick(s) after the terminal showed, "
                    + got.size() + " line(s), terminal still open: " + stillOpen);
            c.check(gap >= SETTLE_MIN_TICKS, "the line went only " + gap + " ticks after the terminal opened");
            c.check(stillOpen, "the terminal closed while the line was held");
            c.check(got.size() == 1, "want the line once, got " + got);
        } finally {
            tearDown(c, undo);
        }
    }

    private static void near(Session c) throws Exception {
        List<AutoCloseable> undo = setUp(c);
        try {
            // P3 Status' S1 terminal 1 is at (110, 113, 73); stand 2.5 blocks from it.
            DungeonFixBossCases.floor(c, 110, 113, 75);
            TerminalAuraCases.tp(c, 110.5, 113, 75.5, 0f);
            c.hx().chat(TerminalAuraCases.GOLDOR);
            c.waitUntil("P3 running, section 1", mc -> Boolean.TRUE.equals(Mod.staticCall("leveraura.P3SectionTracker", "running"))
                    && (Integer) Mod.staticCall("leveraura.P3SectionTracker", "section") == 1, 60);
            c.ctx().waitTicks(30);
            long head = c.hx().head();
            long at = c.hx().call("server.tick").getAsLong();
            c.onClient(mc -> {
                queue();
                return null;
            });
            c.waitUntil("the line at the server after the near wait", mc -> !lines(c, head).isEmpty(), 60);
            long took = lines(c, head).get(0).get("tick").getAsLong() - at;
            c.note("at a P3 terminal: the line reached the server " + took + " tick(s) after it was queued");
            c.check(took >= 4 && took <= 12, "near wait " + took + " ticks, want about 5 (250 ms)");
        } finally {
            tearDown(c, undo);
        }
    }

    private static boolean terminalOpen(net.minecraft.client.Minecraft mc) {
        return McCompat.screen(mc) instanceof AbstractContainerScreen<?> s && s.getTitle().getString().contains("Click in order");
    }

    private static List<JsonObject> lines(Session c, long head) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject e : c.hx().events(head, "cmd")) {
            if (e.get("command").getAsString().equals("pc " + LINE)) {
                out.add(e);
            }
        }
        return out;
    }
}

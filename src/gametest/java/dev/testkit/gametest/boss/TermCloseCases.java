package dev.testkit.gametest.boss;

import com.google.gson.JsonObject;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.util.ArrayList;
import java.util.List;

/**
 * killer560, 2026-11-11 on real Hypixel F7: "something keeps closing my terminals" (mod termclose-1111); 2026-11-12: "yes
 * nothing goes off in a terminal" (mod chatnow-1112). util/OutgoingChat sends every mod line at once, except while a
 * terminal GUI is open: then it is held and goes out the tick the terminal closes.
 * <ul>
 *   <li>1111-termclose-free: no terminal: the line reaches the server within a few ticks of being asked for.</li>
 *   <li>1111-termclose-click: a real interact opens the Hx terminal; a mod line asked for while it is open must NOT reach
 *       the server while the terminal stays open (and is counted pending), and must arrive exactly once after it closes.</li>
 * </ul>
 */
final class TermCloseCases {

    static final String LINE = "Hx termclose line";
    static final String SOLVER_CFG = "terminals.TerminalSolverConfig";

    private TermCloseCases() {
    }

    static void register(Session s) {
        s.test("1111-termclose-free", c -> free(c));
        s.test("1111-termclose-click", c -> click(c));
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
        c.ctx().waitTicks(10);
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
            c.onClient(mc -> {
                var stand = mc.level.getEntity(standId);
                var eye = mc.player.getEyePosition();
                var hit = stand.getBoundingBox().inflate(0.1).clip(eye, eye.add(mc.player.getViewVector(1f).scale(5)))
                        .orElse(stand.getBoundingBox().getCenter());
                mc.gameMode.interact(mc.player, stand, new net.minecraft.world.phys.EntityHitResult(stand, hit),
                        net.minecraft.world.InteractionHand.MAIN_HAND);
                return null;
            });
            c.waitUntil("the click reaching the server", mc -> c.events("term.interact").size() > interacts, 40);
            c.waitUntil("the terminal screen", mc -> terminalOpen(mc), 60);
            // A mod line asked for while the terminal is open: held, nothing goes out.
            c.onClient(mc -> {
                queue();
                return null;
            });
            c.ctx().waitTicks(20);
            boolean stillOpen = c.onClient(mc -> terminalOpen(mc));
            int pending = c.onClient(mc -> (Integer) Mod.staticCall("util.OutgoingChat", "pending"));
            c.check(stillOpen, "the terminal closed by itself, the hold was not tested");
            c.check(lines(c, head).isEmpty(), "the line went while the terminal was open: " + lines(c, head));
            c.check(pending == 1, "want the line counted as held (1), got " + pending);
            // Close it: the held line goes out, once.
            long closeAt = c.hx().call("server.tick").getAsLong();
            TerminalAuraCases.closeScreen(c);
            c.waitUntil("the held line at the server after the terminal closed", mc -> !lines(c, head).isEmpty(), 60);
            c.ctx().waitTicks(10);
            List<JsonObject> got = lines(c, head);
            long took = got.get(0).get("tick").getAsLong() - closeAt;
            c.note("line asked for inside an open terminal: nothing for 20 ticks, " + took
                    + " server tick(s) after it closed, " + got.size() + " line(s)");
            c.check(got.size() == 1, "want the line once, got " + got);
            c.check(took <= 8, "the held line left " + took + " ticks after the terminal closed");
            c.check((Integer) c.onClient(mc -> Mod.staticCall("util.OutgoingChat", "pending")) == 0, "line still pending");
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

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
 *   <li>1115-termclose-stillhold (mod termclose-1115, killer560 2026-11-15 "Why is it closing terminals sometimes"):
 *       util/StillHold holds a container close sent while the server has him moving (sneak here) until he is still. A
 *       close held across a terminal opening went out the tick the terminal screen released his keys, and Hypixel shut
 *       the terminal (Fast Leap's hidden-menu close, sent while sprinting). Control: a held close goes once he stops.
 *       Case: a close held when the terminal opens never reaches the server, and the terminal stays open.</li>
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
        s.test("1115-termclose-stillhold", c -> stillHold(c));
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

    private static void sendClose(Session c, int id) {
        c.onClient(mc -> {
            mc.player.connection.send(new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(id));
            return null;
        });
    }

    private static List<JsonObject> closes(Session c, long head, int id) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject e : c.hx().events(head, "container.close")) {
            if (e.get("containerId").getAsInt() == id) {
                out.add(e);
            }
        }
        return out;
    }

    private static void sneak(Session c, boolean down) {
        c.onClient(mc -> {
            mc.options.keyShift.setDown(down);
            return null;
        });
    }

    private static void stillHold(Session c) throws Exception {
        List<AutoCloseable> undo = setUp(c);
        try {
            // Control: sneaking holds the close, standing still lets it go.
            long head = c.hx().head();
            sneak(c, true);
            c.ctx().waitTicks(5);
            sendClose(c, 98);
            c.ctx().waitTicks(15);
            int heldCtl = closes(c, head, 98).size();
            sneak(c, false);
            c.waitUntil("the held control close after he stood still", mc -> !closes(c, head, 98).isEmpty(), 60);
            c.check(heldCtl == 0, "the close went while sneaking: StillHold did not hold it");
            // Case: a close held while a terminal opens must never reach the server.
            JsonObject st = c.hx().call("boss.term.spawn").getAsJsonObject();
            int standId = st.get("standId").getAsInt();
            c.waitUntil("the client to have the terminal stand", mc -> mc.level.getEntity(standId) != null, 200);
            c.ctx().waitTicks(5);
            long head2 = c.hx().head();
            sneak(c, true);
            c.ctx().waitTicks(5);
            sendClose(c, 97);
            c.ctx().waitTicks(10);
            int heldCase = closes(c, head2, 97).size();
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
            // The screen released his keys (vanilla setScreen), so the server now has him still: the old jar sent it here.
            c.ctx().waitTicks(40);
            boolean stillOpen = c.onClient(mc -> terminalOpen(mc));
            boolean sneakUp = c.onClient(mc -> !mc.options.keyShift.isDown());
            List<JsonObject> stale = closes(c, head2, 97);
            c.note("control close held " + (heldCtl == 0) + "; case close held before the open " + (heldCase == 0)
                    + ", keys released by the screen " + sneakUp + ", stale closes at the server after it " + stale.size()
                    + ", terminal open " + stillOpen);
            c.check(heldCase == 0, "the case close went while sneaking: the hold was not tested");
            c.check(sneakUp, "the terminal screen did not release sneak: the release path was not tested");
            c.check(stale.isEmpty(), "a close held from before the terminal opened reached the server: " + stale);
            c.check(stillOpen, "the terminal closed");
        } finally {
            sneak(c, false);
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

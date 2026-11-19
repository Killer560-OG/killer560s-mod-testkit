package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import dev.testkit.compat.McCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static dev.testkit.gametest.SimBossTests.call;
import static dev.testkit.gametest.SimBossTests.expect;
import static dev.testkit.gametest.SimBossTests.println;
import static dev.testkit.gametest.SimBossTests.start;
import static dev.testkit.gametest.SimBossTests.until;

/**
 * {@code 1119-boss-term-friend-numbers} (friendterms2-1119, killer560 2026-11-19: a friend's Auto Terminals "will literally
 * never get a single click right or click at all" on the sim's Click in Order board, Burst Finish off; his own terminals
 * work and the friend's work on Hypixel). Runs with whatever settings the client has (a run seeds the friend's whole config
 * with -PseedConfig), opens a Numbers board in the S1 boss sim with a real stand click, and requires Auto Terminals to
 * SOLVE it: the sim's own click count (server side, what really arrived) must reach the ten numbers. Every half second it
 * prints what the solver sees, so a stall names its gate.
 */
final class SimBossTermNumbers1119 {

    static final String NAME = "1119-boss-term-friend-numbers";
    private static final String TERMS = "com.killer560.hub.bosssim.BossTerminals";
    private static final String STATIONS = "com.killer560.hub.bosssim.BossStations";
    private static final String SOLVER = "com.killer560.hub.terminals.TerminalSolverFeature";
    private static final String TERM_CFG = "com.killer560.hub.terminals.TerminalSolverConfig";

    private SimBossTermNumbers1119() {
    }

    static void run(ClientGameTestContext ctx, List<String> f) {
        start(ctx, "S1", true);
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(TERM_CFG);
            println(NAME, "settings: auto=" + ModUnderTest.getBoolean(cfg, "isAutoTerminalsEnabled")
                    + " autoNumbers=" + ModUnderTest.getBoolean(cfg, "isAutoNumbersEnabled")
                    + " burstFinish=" + ModUnderTest.getBoolean(cfg, "isBurstFinish")
                    + " min=" + ModUnderTest.getInt(cfg, "getAutoClickMinDelayMs")
                    + " max=" + ModUnderTest.getInt(cfg, "getAutoClickMaxDelayMs")
                    + " first=" + ModUnderTest.getInt(cfg, "getFirstClickDelayMs")
                    + " customGui=" + ModUnderTest.getBoolean(cfg, "isCustomGuiEnabled"));
        });
        ctx.waitTicks(5);
        System.setProperty("killer560.bosssim.terminal", "NUMBERS");
        try {
            int solvedBefore = (Integer) call(TERMS, "solvedCount");
            int openedBefore = (Integer) call(TERMS, "openedCount");
            Wire wire = new Wire();
            ctx.runOnClient(wire::install);
            if (!SimBossTests.clickTerminal(ctx, 1, "T1", "Inactive Terminal")
                    || !until(ctx, "T1 opened", mc -> (Integer) call(TERMS, "openedCount") > openedBefore, 20)) {
                ctx.runOnClient(mc -> wire.remove());
                f.add("S1 T1 never opened");
                return;
            }
            long openedAt = System.currentTimeMillis();
            int[] simClicks = {0, 0};
            boolean solved = false;
            for (int i = 0; i < 30 && !solved; i++) {
                ctx.waitTicks(10);
                solved = (Integer) call(TERMS, "solvedCount") > solvedBefore;
                ctx.runOnClient(mc -> {
                    MinecraftServer s = mc.getSingleplayerServer();
                    s.execute(() -> {
                        var sp = s.getPlayerList().getPlayers().get(0);
                        Object menu = sp.containerMenu;
                        Object term = field(menu, "term");
                        if (term != null) {
                            simClicks[0] = (Integer) field(term, "clicks");
                            simClicks[1] = (Integer) field(term, "limited");
                        }
                    });
                });
                final boolean done = solved;
                ctx.runOnClient(mc -> println(NAME, String.format("t=%d ms solved=%s simClicks=%d limited=%d %s",
                        System.currentTimeMillis() - openedAt, done, simClicks[0], simClicks[1], solverState(mc))));
            }
            ctx.runOnClient(mc -> wire.remove());
            println(NAME, "wire: " + wire.clicks + " container click(s) sent, " + wire.boardSlots
                    + " slot packet(s) for the board's window, " + wire.blind + " click(s) sent before any slot packet ("
                    + wire.seenIn + " packets in, " + wire.seenOut + " out, window " + wire.window + ")");
            // The friend's Odin (TerminalUtils / TerminalHandler, odtheking/Odin main) solves a terminal ONLY from
            // AbstractContainerMenu.setItem, which a slot packet calls and a whole-window contents packet never does, and
            // its Terminal Solver cancels every slotClicked outside that solution. A click sent before any slot packet of the
            // board is one Odin cancels: on Hypixel (one slot packet per slot) that never happens.
            expect(f, "container clicks sent (Auto Terminals acted)", wire.clicks > 0, true);
            expect(f, "slot packets describing the board (as Hypixel sends it)", wire.boardSlots > 0, true);
            expect(f, "clicks sent before any slot packet of the board (Odin would cancel each)", wire.blind, 0);
            if (!solved) {
                f.add("Numbers board never solved in 15 s (sim received " + simClicks[0] + " click(s))");
                ctx.runOnClient(mc -> {
                    if (mc.player.containerMenu != mc.player.inventoryMenu) {
                        mc.player.closeContainer();
                    }
                });
                SimBossTests.serverRun(ctx, s -> ModUnderTest.staticCall(STATIONS, "testComplete",
                        new Class<?>[]{MinecraftServer.class, int.class, String.class}, new Object[]{s, 1, "T1"}));
            } else {
                println(NAME, "solved in " + (System.currentTimeMillis() - openedAt) + " ms");
            }
        } finally {
            System.clearProperty("killer560.bosssim.terminal");
        }
    }

    private static String solverState(Minecraft mc) {
        var screen = McCompat.screen(mc);
        StringBuilder b = new StringBuilder("screen=").append(screen == null ? "none" : screen.getClass().getSimpleName());
        for (String n : new String[]{"currentType", "hasStabilizedOnce", "clickContentStabilized", "foreignClickFailed",
                "stabilizedAtMs", "loadedContainerId", "terminalOpenedAtMs", "nextAutoClickAllowedAtMs", "stickySlot"}) {
            b.append(' ').append(n).append('=').append(staticField(SOLVER, n));
        }
        Object h = staticField(SOLVER, "currentHighlights");
        Object p = staticField(SOLVER, "pendingClicks");
        b.append(" highlights=").append(h instanceof Map<?, ?> m ? m.keySet() : h);
        b.append(" pending=").append(p instanceof Map<?, ?> m ? m.keySet() : p);
        b.append(" menuId=").append(mc.player.containerMenu.containerId);
        return b.toString();
    }

    /** Counts, on the client's own connection, the board's slot packets in and the container clicks out. */
    static final class Wire extends io.netty.channel.ChannelDuplexHandler {
        private static final String NAME = "testkit-termwire";
        volatile int clicks;
        volatile int boardSlots;
        volatile int blind;
        private volatile int window = -1;
        private io.netty.channel.Channel channel;

        void install(Minecraft mc) {
            for (Field fl : net.minecraft.network.Connection.class.getDeclaredFields()) {
                if (io.netty.channel.Channel.class.isAssignableFrom(fl.getType())) {
                    try {
                        fl.setAccessible(true);
                        channel = (io.netty.channel.Channel) fl.get(mc.getConnection().getConnection());
                    } catch (IllegalAccessException e) {
                        throw new AssertionError(e);
                    }
                }
            }
            if (channel.pipeline().get(NAME) != null) {
                channel.pipeline().remove(NAME);
            }
            channel.pipeline().addFirst(NAME, this);
        }

        void remove() {
            if (channel != null && channel.pipeline().get(NAME) != null) {
                channel.pipeline().remove(NAME);
            }
        }

        volatile int seenIn;
        volatile int seenOut;

        @Override
        public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) throws Exception {
            seenIn++;
            if (msg instanceof net.minecraft.network.protocol.game.ClientboundOpenScreenPacket p) {
                window = p.getContainerId();
                boardSlots = 0;
            } else if (msg instanceof net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket p
                    && p.getContainerId() > 0 && (window < 0 || p.getContainerId() == window)) {
                boardSlots++;
            }
            super.channelRead(ctx, msg);
        }

        @Override
        public void write(io.netty.channel.ChannelHandlerContext ctx, Object msg, io.netty.channel.ChannelPromise promise) throws Exception {
            seenOut++;
            if (msg instanceof net.minecraft.network.protocol.game.ServerboundContainerClickPacket p && p.containerId() > 0
                    && (window < 0 || p.containerId() == window)) {
                clicks++;
                if (boardSlots == 0) {
                    blind++;
                }
            }
            super.write(ctx, msg, promise);
        }

        @Override
        public boolean isSharable() {
            return true;
        }
    }

    static Object staticField(String cls, String name) {
        try {
            Field fl = Class.forName(cls).getDeclaredField(name);
            fl.setAccessible(true);
            return fl.get(null);
        } catch (ReflectiveOperationException e) {
            return "?";
        }
    }

    static Object field(Object o, String name) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field fl = c.getDeclaredField(name);
                fl.setAccessible(true);
                return fl.get(o);
            } catch (NoSuchFieldException e) {
                // superclass next
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }
}
